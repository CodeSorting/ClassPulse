package com.example.classpulse.online;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import com.google.firebase.functions.*;
import com.google.android.gms.tasks.Task;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Managed Firebase only: no custom HTTP host or credentials embedded in the app. */
public final class FirebaseOnlineTransport implements OnlineTransport {
    private static final Set<String> configured = new HashSet<>();
    private final FirebaseAuth auth;
    private final FirebaseFirestore db;
    private final FirebaseFunctions functions;

    public FirebaseOnlineTransport(FirebaseApp app) {
        auth = FirebaseAuth.getInstance(app);
        db = FirebaseFirestore.getInstance(app);
        functions = FirebaseFunctions.getInstance(app, "asia-northeast3");
        synchronized (configured) {
            if (configured.add(app.getName()))
                db.setFirestoreSettings(
                        new FirebaseFirestoreSettings.Builder()
                                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                                .build());
        }
    }

    @Override
    public boolean authenticatedAs(String uid) {
        return auth.getCurrentUser() != null && uid.equals(auth.getCurrentUser().getUid());
    }

    private static Failure failure(Exception error) {
        if (error instanceof FirebaseFunctionsException) {
            FirebaseFunctionsException f = (FirebaseFunctionsException) error;
            Object details = f.getDetails();
            String reason =
                    details instanceof Map
                            ? String.valueOf(((Map<?, ?>) details).get("reason"))
                            : f.getCode().name();
            if (f.getCode() == FirebaseFunctionsException.Code.NOT_FOUND
                    && !(details instanceof Map))
                return new Failure(
                        "SERVER_NOT_DEPLOYED", "Firebase 수업 기능을 아직 연결하지 못했어요. 서버 배포 상태를 확인해 주세요.");
            return new Failure(reason, f.getMessage() == null ? "서버 연결을 확인해 주세요." : f.getMessage());
        }
        if (error instanceof FirebaseFirestoreException
                && ((FirebaseFirestoreException) error).getCode()
                        == FirebaseFirestoreException.Code.PERMISSION_DENIED)
            return new Failure("PERMISSION_DENIED", "이 수업을 볼 권한이 있는지 다시 확인해 주세요.");
        return new Failure("NETWORK", "서버 연결을 확인한 뒤 다시 시도해 주세요.", error);
    }

    @Override
    public CompletableFuture<JSONObject> call(String command, JSONObject payload) {
        CompletableFuture<JSONObject> out = new CompletableFuture<>();
        functions
                .getHttpsCallable("classPulse")
                .call(OnlineJson.map(OnlineJson.values("command", command, "payload", payload)))
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        try {
                            out.complete(OnlineJson.json(task.getResult().getData()));
                        } catch (Exception e) {
                            out.completeExceptionally(
                                    new Failure(
                                            "INVALID_RESPONSE", "Firebase 수업 응답을 확인하지 못했어요.", e));
                        }
                    } else out.completeExceptionally(failure(task.getException()));
                });
        return out;
    }

    @Override
    public CompletableFuture<JSONObject> receipt(String uid, String operationId) {
        CompletableFuture<JSONObject> out = new CompletableFuture<>();
        db.document("users/" + uid + "/commandReceipts/" + operationId)
                .get(Source.SERVER)
                .addOnCompleteListener(t -> {
                    if (t.isSuccessful())
                        out.complete(
                                t.getResult().exists()
                                        ? OnlineJson.json(t.getResult().getData())
                                        : new JSONObject());
                    else out.completeExceptionally(failure(t.getException()));
                });
        return out;
    }

    @Override
    public CompletableFuture<JSONObject> page(
            String uid, String sid, String kind, boolean owner, String lastId) {
        CompletableFuture<JSONObject> out = new CompletableFuture<>();
        String collection = "PUBLIC_QNA".equals(kind) ? "publicQna" : "questions",
                time = "PUBLIC_QNA".equals(kind) ? "publishedAt" : "submittedAt";
        CollectionReference ref = db.collection("sessions/" + sid + "/" + collection);
        Query base = ref;
        if ("QUESTION".equals(kind) && !owner) base = base.whereEqualTo("authorUid", uid);
        final Query ordered =
                base.orderBy(time, Query.Direction.DESCENDING)
                        .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING)
                        .limit(30);
        java.util.function.Consumer<Query> fetch =
                query ->
                        query.get(Source.SERVER)
                                .addOnCompleteListener(task -> {
                                    if (!task.isSuccessful()) {
                                        out.completeExceptionally(failure(task.getException()));
                                        return;
                                    }
                                    List<Map<String, Object>> docs = new ArrayList<>();
                                    for (DocumentSnapshot d : task.getResult().getDocuments()) {
                                        Map<String, Object> data = new HashMap<>(d.getData());
                                        data.put("id", d.getId());
                                        docs.add(data);
                                    }
                                    out.complete(
                                            OnlineJson.json(
                                                    Collections.singletonMap("documents", docs)));
                                });
        if (lastId.isEmpty()) fetch.accept(ordered);
        else
            ref.document(lastId)
                    .get(Source.SERVER)
                    .addOnCompleteListener(task -> {
                        if (!task.isSuccessful()) {
                            out.completeExceptionally(failure(task.getException()));
                            return;
                        }
                        if (!task.getResult().exists()) {
                            out.completeExceptionally(
                                    new Failure("CURSOR_CHANGED", "목록을 새로 확인해 주세요."));
                            return;
                        }
                        fetch.accept(ordered.startAfter(task.getResult()));
                    });
        return out;
    }

    private void watch(
            Query query, String kind, Sink sink, List<ListenerRegistration> registrations) {
        registrations.add(
                query.addSnapshotListener((snapshot, error) -> {
                    if (error != null) {
                        Failure f = failure(error);
                        sink.error(f.reason, f.getMessage());
                        return;
                    }
                    if (snapshot == null || snapshot.getMetadata().isFromCache()) return;
                    // REMOVED from a limited query is not a deletion. Only server tombstones
                    // delete.
                    for (DocumentSnapshot doc : snapshot.getDocuments())
                        sink.document(kind, doc.getId(), OnlineJson.json(doc.getData()));
                }));
    }

    private void watch(
            DocumentReference ref,
            String kind,
            Sink sink,
            List<ListenerRegistration> registrations) {
        registrations.add(
                ref.addSnapshotListener((snapshot, error) -> {
                    if (error != null) {
                        Failure f = failure(error);
                        sink.error(f.reason, f.getMessage());
                        return;
                    }
                    if (snapshot != null
                            && snapshot.exists()
                            && !snapshot.getMetadata().isFromCache())
                        sink.document(kind, snapshot.getId(), OnlineJson.json(snapshot.getData()));
                }));
    }

    @Override
    public Subscription watchHome(
            String uid, java.util.function.Consumer<String> onId, Sink errors) {
        ListenerRegistration listener =
                db.collection("users/" + uid + "/lessonRefs")
                        .orderBy("joinedAt", Query.Direction.DESCENDING)
                        .limit(20)
                        .addSnapshotListener((snapshot, error) -> {
                            if (error != null) {
                                Failure f = failure(error);
                                errors.error(f.reason, f.getMessage());
                                return;
                            }
                            if (snapshot != null && !snapshot.getMetadata().isFromCache())
                                for (DocumentSnapshot d : snapshot.getDocuments())
                                    if ("ACTIVE".equals(d.getString("state")))
                                        onId.accept(d.getId());
                        });
        return listener::remove;
    }

    @Override
    public Subscription watchLesson(String uid, String sid, boolean owner, Sink sink) {
        List<ListenerRegistration> all = new ArrayList<>();
        String base = "sessions/" + sid;
        watch(db.document(base), "SESSION", sink, all);
        Query questions = db.collection(base + "/questions");
        if (!owner) questions = questions.whereEqualTo("authorUid", uid);
        watch(
                questions.orderBy("submittedAt", Query.Direction.DESCENDING).limit(30),
                "QUESTION",
                sink,
                all);
        watch(
                db.collection(base + "/publicQna")
                        .orderBy("publishedAt", Query.Direction.DESCENDING)
                        .limit(30),
                "PUBLIC_QNA",
                sink,
                all);
        String personal = "users/" + uid + "/lessonData/" + sid;
        watch(
                db.collection(personal + "/questionDrafts")
                        .orderBy("updatedAt", Query.Direction.DESCENDING)
                        .limit(100),
                "QUESTION_DRAFT",
                sink,
                all);
        watch(
                db.collection(personal + "/marks")
                        .orderBy("updatedAt", Query.Direction.DESCENDING)
                        .limit(100),
                "MARK",
                sink,
                all);
        watch(
                db.collection(personal + "/reactionReceipts")
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                        .limit(100),
                "OWN_REACTION",
                sink,
                all);
        if (owner) {
            watch(db.collection(base + "/teacherDrafts"), "ANSWER_DRAFT", sink, all);
            watch(db.document(base + "/analytics/summary"), "STATS", sink, all);
        }
        return () -> {
            for (ListenerRegistration r : all) r.remove();
        };
    }
}
