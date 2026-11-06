package com.example.classpulse.online;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static com.example.classpulse.online.OnlineDatabase.*;

/** Account-owned Room cache and durable command queue. All SQLite access is off main. */
public final class OnlineRepository implements AutoCloseable {
    public interface Callback {
        void success(JSONObject value);

        void error(String reason, String message);
    }

    public final String uid;
    final OnlineDatabase db;
    private final Store store;
    private final OnlineTransport transport;
    private final Context context;
    private final boolean schedule;
    private final ExecutorService io = Executors.newSingleThreadExecutor(),
            network = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<OnlineTransport.Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final Map<String, Callback> callbacks = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private long lastClock;
    private final MutableLiveData<String> notice = new MutableLiveData<>("");

    OnlineRepository(
            Context context,
            String uid,
            OnlineDatabase db,
            OnlineTransport transport,
            boolean schedule) {
        this.context = context.getApplicationContext();
        this.uid = uid;
        this.db = db;
        store = db.store();
        this.transport = transport;
        this.schedule = schedule;
    }

    public LiveData<List<Session>> sessions() {
        return store.sessions();
    }

    public LiveData<List<Document>> documents(String sid) {
        return store.documents(sid);
    }

    public LiveData<List<Draft>> drafts(String sid) {
        return store.drafts(sid);
    }

    public LiveData<List<Outbox>> operations() {
        return store.operations();
    }

    public LiveData<List<Conflict>> conflicts(String sid) {
        return store.conflicts(sid);
    }

    public LiveData<String> notice() {
        return notice;
    }

    private boolean active() {
        return !closed && transport.authenticatedAs(uid);
    }

    private void local(Runnable action) {
        if (!closed)
            io.execute(() -> {
                if (!closed) action.run();
            });
    }

    private void success(Callback callback, JSONObject value) {
        if (callback != null)
            main.post(() -> {
                if (active()) callback.success(value);
            });
    }

    private void failure(Callback callback, String code, String message) {
        notice.postValue(message);
        if (callback != null)
            main.post(() -> {
                if (active()) callback.error(code, message);
            });
    }

    private OnlineTransport.Failure error(Throwable e) {
        while (e.getCause() != null
                && (e instanceof ExecutionException || e instanceof CompletionException))
            e = e.getCause();
        return e instanceof OnlineTransport.Failure
                ? (OnlineTransport.Failure) e
                : new OnlineTransport.Failure("NETWORK", "연결을 확인한 뒤 다시 시도해 주세요. 작성한 내용은 보관돼요.");
    }

    private long nextClock() {
        lastClock =
                Math.max(
                        Math.max(System.currentTimeMillis(), lastClock + 1),
                        store.newestOperationTime() + 1);
        return lastClock;
    }

    private void session(JSONObject data) {
        String id = data.optString("id");
        if (id.isEmpty()) return;
        Session old = store.session(id);
        if (old != null && old.revision > data.optLong("revision")) return;
        Session row = new Session();
        row.id = id;
        row.json = data.toString();
        row.title = data.optString("title");
        row.ownerUid = data.optString("ownerUid");
        row.status = data.optString("status");
        row.revision = data.optLong("revision");
        row.createdAt = data.optLong("createdAt");
        row.verifiedAt = System.currentTimeMillis();
        row.accessible = data.isNull("deletedAt");
        store.session(row);
    }

    private void document(String sid, String kind, String id, JSONObject data) {
        Document old = store.document(sid, kind, id);
        if (old != null && old.revision > data.optLong("revision")) return;
        Document row = new Document();
        row.sessionId = sid;
        row.kind = kind;
        row.id = id;
        row.json = data.toString();
        row.revision = data.optLong("revision");
        row.updatedAt = data.optLong("updatedAt", data.optLong("createdAt"));
        row.verifiedAt = System.currentTimeMillis();
        row.deleted = !data.isNull("deletedAt");
        store.document(row);
        if ("QUESTION_DRAFT".equals(kind)
                || "MARK".equals(kind)
                || "ANSWER_DRAFT".equals(kind) && id.startsWith("answer_"))
            personalSnapshot(sid, kind, "ANSWER_DRAFT".equals(kind) ? id.substring(7) : id, data);
    }

    private void personalSnapshot(String sid, String kind, String id, JSONObject data) {
        Draft current = store.draft(sid, kind, id);
        long revision = data.optLong("revision");
        if (current != null && revision <= current.serverRevision) return;
        Outbox matching = store.operation(data.optString("lastOperationId"));
        if (current != null
                && current.dirty
                && (matching == null || !matching.entityKey.equals(kind + ":" + sid + ":" + id))) {
            Conflict c = new Conflict();
            c.sessionId = sid;
            c.kind = kind;
            c.id = id;
            c.localText = current.text;
            c.remoteJson = data.toString();
            c.remoteRevision = revision;
            store.conflict(c);
            return;
        }
        if (current == null) {
            current = new Draft();
            current.id = id;
            current.sessionId = sid;
            current.kind = kind;
        }
        boolean preserve =
                current.dirty
                        && matching != null
                        && current.localRevision > matching.localRevisionAtEnqueue;
        if (!preserve) {
            current.text = data.optString("text");
            current.topicId = data.optString("topicId");
            current.dirty = false;
        }
        current.serverRevision = revision;
        current.closed =
                !data.isNull("submittedQuestionId")
                        || !data.isNull("deletedAt")
                        || (preserve && current.closed);
        if ("ANSWER_DRAFT".equals(kind))
            current.baseAnswerVersion = data.optLong("baseAnswerVersion");
        if (current.closed)
            current.state = data.isNull("deletedAt") ? "SERVER_RECEIVED" : "DELETED";
        else if (!preserve) current.state = "SAVED";
        store.draft(current);
        if (matching != null) {
            matching.state = "ACKED";
            store.operation(matching);
        }
        store.clearConflict(sid, kind, id);
    }

    private void applyResult(Outbox op, JSONObject result) {
        JSONObject root = result.optJSONObject("session");
        if (root != null) session(root);
        JSONObject question = result.optJSONObject("question");
        if (question != null)
            document(
                    op.sessionId,
                    "QUESTION",
                    question.optString("id", result.optString("id")),
                    question);
        JSONObject value = result.optJSONObject("document");
        if (value != null) {
            String kind =
                    "saveAnswerDraft".equals(op.command)
                            ? "ANSWER_DRAFT"
                            : op.entityKey.startsWith("MARK:") ? "MARK" : "QUESTION_DRAFT";
            String id = result.optString("id");
            document(op.sessionId, kind, "ANSWER_DRAFT".equals(kind) ? "answer_" + id : id, value);
        }
        if (result.optJSONObject("draft") != null)
            document(
                    op.sessionId,
                    "ANSWER_DRAFT",
                    "answer_" + result.optString("id"),
                    result.optJSONObject("draft"));
        if (result.optJSONObject("receipt") != null)
            document(
                    op.sessionId,
                    "OWN_REACTION",
                    result.optString("reactionId"),
                    result.optJSONObject("receipt"));
        if ("submitQuestion".equals(op.command)) {
            String id = OnlineJson.object(op.payload).optString("id");
            Draft d = store.draft(op.sessionId, "QUESTION_DRAFT", id);
            if (d != null) {
                d.closed = true;
                d.dirty = false;
                d.state = "SERVER_RECEIVED";
                store.draft(d);
            }
        }
    }

    private void bootstrap(JSONObject value) {
        JSONObject s = value.optJSONObject("session");
        if (s == null) return;
        db.runInTransaction(() -> {
            session(s);
            JSONArray topics = value.optJSONArray("topics");
            if (topics != null)
                for (int i = 0; i < topics.length(); i++) {
                    JSONObject t = topics.optJSONObject(i);
                    if (t != null) document(s.optString("id"), "TOPIC", t.optString("id"), t);
                }
        });
    }

    public void read(String command, JSONObject payload, Callback callback) {
        if (!active()) {
            failure(callback, "AUTH_REQUIRED", "다시 로그인해 주세요.");
            return;
        }
        transport
                .call(command, payload)
                .whenComplete((value, e) -> {
                    if (!active()) return;
                    if (e != null) {
                        OnlineTransport.Failure f = error(e);
                        failure(callback, f.reason, f.getMessage());
                        return;
                    }
                    local(() -> {
                        if (!active()) return;
                        if ("getSessionBootstrap".equals(command)) bootstrap(value);
                        success(callback, value);
                    });
                });
    }

    public OnlineTransport.Subscription watchHome() {
        OnlineTransport.Sink errors =
                new OnlineTransport.Sink() {
                    public void document(String k, String id, JSONObject v) {}

                    public void error(String r, String m) {
                        failure(null, r, m);
                    }
                };
        OnlineTransport.Subscription sub =
                transport.watchHome(
                        uid,
                        sid ->
                                read(
                                        "getSessionBootstrap",
                                        OnlineJson.values("sessionId", sid),
                                        null),
                        errors);
        subscriptions.add(sub);
        return () -> {
            sub.close();
            subscriptions.remove(sub);
        };
    }

    public void more(String sid, String kind, String lastId, boolean owner, Callback callback) {
        transport
                .page(uid, sid, kind, owner, lastId)
                .whenComplete((result, e) -> {
                    if (!active()) return;
                    if (e != null) {
                        OnlineTransport.Failure f = error(e);
                        failure(callback, f.reason, f.getMessage());
                        return;
                    }
                    local(() -> {
                        JSONArray docs = result.optJSONArray("documents");
                        if (docs != null)
                            db.runInTransaction(() -> {
                                for (int i = 0; i < docs.length(); i++) {
                                    JSONObject doc = docs.optJSONObject(i);
                                    if (doc != null) document(sid, kind, doc.optString("id"), doc);
                                }
                            });
                        success(
                                callback,
                                OnlineJson.values("count", docs == null ? 0 : docs.length()));
                    });
                });
    }

    public OnlineTransport.Subscription watchLesson(String sid) {
        AtomicBoolean stopped = new AtomicBoolean();
        List<OnlineTransport.Subscription> current = new CopyOnWriteArrayList<>();
        read(
                "getSessionBootstrap",
                OnlineJson.values("sessionId", sid),
                new Callback() {
                    public void success(JSONObject value) {
                        if (stopped.get() || !active()) return;
                        OnlineTransport.Subscription sub =
                                transport.watchLesson(
                                        uid,
                                        sid,
                                        value.optBoolean("owner"),
                                        new OnlineTransport.Sink() {
                                            public void document(
                                                    String kind, String id, JSONObject data) {
                                                local(() -> {
                                                    if (!active() || stopped.get()) return;
                                                    db.runInTransaction(() -> {
                                                        if ("SESSION".equals(kind)) {
                                                            JSONObject root =
                                                                    OnlineJson.object(
                                                                            data.toString());
                                                            try {
                                                                root.put("id", sid);
                                                            } catch (Exception ignored) {
                                                            }
                                                            Session old = store.session(sid);
                                                            long outline =
                                                                    old == null
                                                                            ? 0
                                                                            : OnlineJson.object(
                                                                                            old.json)
                                                                                    .optLong(
                                                                                            "outlineVersion");
                                                            session(root);
                                                            if (root.optLong("outlineVersion")
                                                                    > outline)
                                                                read(
                                                                        "getSessionBootstrap",
                                                                        OnlineJson.values(
                                                                                "sessionId", sid),
                                                                        null);
                                                        } else
                                                            OnlineRepository.this.document(
                                                                    sid, kind, id, data);
                                                    });
                                                });
                                            }

                                            public void error(String code, String message) {
                                                if (stopped.get()) return;
                                                failure(null, code, message);
                                                if ("PERMISSION_DENIED".equals(code))
                                                    local(() -> {
                                                        Session row = store.session(sid);
                                                        if (row != null) {
                                                            row.accessible = false;
                                                            store.session(row);
                                                        }
                                                    });
                                            }
                                        });
                        current.add(sub);
                        subscriptions.add(sub);
                    }

                    public void error(String reason, String message) {
                        if ("PERMISSION_DENIED".equals(reason)
                                || "MEMBERSHIP_REQUIRED".equals(reason))
                            local(() -> {
                                Session row = store.session(sid);
                                if (row != null) {
                                    row.accessible = false;
                                    store.session(row);
                                }
                            });
                    }
                });
        return () -> {
            stopped.set(true);
            for (OnlineTransport.Subscription s : current) {
                s.close();
                subscriptions.remove(s);
            }
        };
    }

    private Outbox add(
            String command,
            String sid,
            String entity,
            JSONObject payload,
            boolean foreground,
            long localRevision) {
        Outbox o = new Outbox();
        o.operationId = OnlineJson.id();
        o.ownerUid = uid;
        o.command = command;
        o.sessionId = sid;
        o.entityKey = entity;
        o.createdAt = nextClock();
        o.foreground = foreground;
        o.localRevisionAtEnqueue = localRevision;
        JSONObject copy = OnlineJson.object(payload.toString());
        try {
            copy.put("operationId", o.operationId);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        o.payload = copy.toString();
        store.operation(o);
        return o;
    }

    public void command(String command, String sid, JSONObject payload, Callback callback) {
        enqueue(command, sid, payload, true, callback);
    }

    public void background(String command, String sid, JSONObject payload, Callback callback) {
        enqueue(command, sid, payload, false, callback);
    }

    private void enqueue(
            String command, String sid, JSONObject payload, boolean foreground, Callback callback) {
        local(() -> {
            if (!active()) return;
            final Outbox[] created = new Outbox[1];
            db.runInTransaction(() -> {
                String key = command + ":" + sid;
                Outbox previous = store.lastForEntity(key);
                if (previous != null && "NEEDS_CONFIRMATION".equals(previous.state)) {
                    previous.state = "REJECTED";
                    previous.errorCode = "USER_RECONFIRMED";
                    store.operation(previous);
                }
                created[0] = add(command, sid, key, payload, foreground, 0);
            });
            if (callback != null) callbacks.put(created[0].operationId, callback);
            drainAsync(foreground);
        });
    }

    public void saveAnswerLocal(
            String sid, String id, String text, long baseRevision, long baseAnswerVersion) {
        local(() -> {
            Draft d = store.draft(sid, "ANSWER_DRAFT", id);
            if (d == null) {
                d = new Draft();
                d.sessionId = sid;
                d.kind = "ANSWER_DRAFT";
                d.id = id;
                d.serverRevision = baseRevision;
                d.baseAnswerVersion = baseAnswerVersion;
            }
            if (!d.text.equals(text)) {
                d.text = text;
                d.localRevision++;
                d.dirty = true;
                store.draft(d);
            }
        });
    }

    public void answer(
            String sid,
            String id,
            String text,
            boolean publish,
            long baseRevision,
            long baseAnswerVersion,
            Callback callback) {
        local(() -> {
            final Outbox[] created = new Outbox[1];
            db.runInTransaction(() -> {
                String key = "ANSWER_DRAFT:" + sid + ":" + id;
                Outbox previous = store.lastForEntity(key);
                if (previous != null && "NEEDS_CONFIRMATION".equals(previous.state)) {
                    previous.state = "REJECTED";
                    previous.errorCode = "USER_RECONFIRMED";
                    store.operation(previous);
                }
                Draft d = store.draft(sid, "ANSWER_DRAFT", id);
                if (d == null) {
                    d = new Draft();
                    d.id = id;
                    d.sessionId = sid;
                    d.kind = "ANSWER_DRAFT";
                }
                d.text = text;
                d.dirty = true;
                d.localRevision++;
                store.draft(d);
                created[0] =
                        add(
                                publish ? "publishAnswer" : "saveAnswerDraft",
                                sid,
                                key,
                                OnlineJson.values(
                                        "sessionId",
                                        sid,
                                        "id",
                                        id,
                                        "text",
                                        text,
                                        "baseRevision",
                                        baseRevision,
                                        "baseAnswerVersion",
                                        baseAnswerVersion),
                                true,
                                d.localRevision);
            });
            if (callback != null) callbacks.put(created[0].operationId, callback);
            drainAsync(true);
        });
    }

    public void saveDraft(
            String sid,
            String id,
            String topicId,
            String value,
            boolean submit,
            Callback callback) {
        savePersonal(sid, "QUESTION_DRAFT", id, topicId, value, submit, callback);
    }

    public void saveMark(String sid, String topicId, String value, Callback callback) {
        savePersonal(sid, "MARK", OnlineJson.id(), topicId, value, false, callback);
    }

    private void savePersonal(
            String sid,
            String kind,
            String id,
            String topicId,
            String value,
            boolean submit,
            Callback callback) {
        local(() -> {
            if (!active()) return;
            Draft old = store.draft(sid, kind, id);
            if (old != null && old.closed) {
                failure(callback, "DRAFT_CLOSED", "이미 전송을 준비한 질문이에요. 새 질문으로 작성해 주세요.");
                return;
            }
            if (!submit && old != null && old.text.equals(value) && old.topicId.equals(topicId)) {
                success(callback, new JSONObject());
                return;
            }
            if (value.length() > ("MARK".equals(kind) ? 200 : 1000)
                    || submit && value.trim().isEmpty()) {
                failure(callback, "INVALID_TEXT", "질문 내용과 길이를 확인해 주세요.");
                return;
            }
            final Outbox[] created = new Outbox[1];
            db.runInTransaction(() -> {
                Draft d = old == null ? new Draft() : old;
                d.id = id;
                d.sessionId = sid;
                d.kind = kind;
                d.topicId = topicId;
                d.text = value;
                d.localRevision++;
                d.dirty = true;
                d.closed = submit;
                d.state = submit ? "QUEUED" : "LOCAL_DRAFT";
                store.draft(d);
                String key = kind + ":" + sid + ":" + id;
                // Only never-sent saves can be superseded. An in-flight command stays immutable.
                for (Outbox p : store.pendingEntity(key))
                    if (p.attempts == 0 && "PENDING".equals(p.state)) {
                        p.state = "REJECTED";
                        p.errorCode = "SUPERSEDED";
                        store.operation(p);
                    }
                JSONObject payload =
                        OnlineJson.values(
                                "sessionId",
                                sid,
                                "id",
                                id,
                                "topicId",
                                topicId,
                                "text",
                                value,
                                "baseRevision",
                                d.serverRevision);
                created[0] =
                        add(
                                submit
                                        ? "submitQuestion"
                                        : "MARK".equals(kind) ? "saveMark" : "savePersonalDraft",
                                sid,
                                key,
                                payload,
                                false,
                                d.localRevision);
            });
            success(callback, OnlineJson.values("id", id, "state", "QUEUED"));
            drainAsync(false);
        });
    }

    public void resolveConflict(String sid, String kind, String id, boolean keepLocal) {
        local(() -> {
            Conflict c = store.conflict(sid, kind, id);
            Draft d = store.draft(sid, kind, id);
            if (c == null || d == null) return;
            JSONObject remote = OnlineJson.object(c.remoteJson);
            String localText = d.text, localTopic = d.topicId;
            db.runInTransaction(() -> {
                for (Outbox o : store.pendingEntity(kind + ":" + sid + ":" + id)) {
                    o.state = "REJECTED";
                    o.errorCode = "CONFLICT_RESOLVED";
                    store.operation(o);
                }
                d.serverRevision = c.remoteRevision;
                d.closed = !remote.isNull("submittedQuestionId") || !remote.isNull("deletedAt");
                d.dirty = false;
                d.state = d.closed ? "SERVER_RECEIVED" : "SAVED";
                if (!keepLocal || !d.closed) {
                    d.text = remote.optString("text");
                    d.topicId = remote.optString("topicId");
                }
                store.draft(d);
                store.clearConflict(sid, kind, id);
            });
            // Keep-local text is retained even when the remote draft was submitted/deleted.
            // A closed draft must be copied to a new ID explicitly, never resurrected.
            if (keepLocal && !d.closed)
                savePersonal(sid, kind, id, localTopic, localText, false, null);
        });
    }

    public void retry() {
        drainAsync(false);
    }

    private void drainAsync(boolean foreground) {
        if (!closed) network.execute(() -> drain(foreground));
        if (schedule) OnlineSyncWorker.schedule(context, uid);
    }

    boolean drain(boolean foreground) {
        if (!active()) return false;
        try {
            for (Outbox expired : store.expired(System.currentTimeMillis())) {
                JSONObject receipt =
                        transport.receipt(uid, expired.operationId).get(20, TimeUnit.SECONDS);
                if (!active()) return false;
                if ("COMMITTED".equals(receipt.optString("state"))) {
                    ack(expired, receipt.optJSONObject("result"));
                } else {
                    expired.state = expired.foreground ? "NEEDS_CONFIRMATION" : "RETRY_WAIT";
                    store.operation(expired);
                }
            }
            for (int n = 0; n < 30 && active(); n++) {
                Outbox candidate = store.next(foreground);
                if (candidate == null) return store.inFlightCount() == 0;
                final Outbox[] acquired = new Outbox[1];
                db.runInTransaction(() -> {
                    if (store.claim(candidate.operationId, System.currentTimeMillis() + 120000)
                            == 0) return;
                    Outbox o = store.operation(candidate.operationId);
                    if (o.attempts == 1
                            && (o.entityKey.startsWith("QUESTION_DRAFT:")
                                    || o.entityKey.startsWith("MARK:"))) {
                        JSONObject p = OnlineJson.object(o.payload);
                        Draft d =
                                store.draft(
                                        o.sessionId,
                                        o.entityKey.startsWith("MARK:") ? "MARK" : "QUESTION_DRAFT",
                                        p.optString("id"));
                        try {
                            p.put("baseRevision", d == null ? 0 : d.serverRevision);
                        } catch (Exception e) {
                            throw new IllegalArgumentException(e);
                        }
                        o.payload = p.toString();
                        store.operation(o);
                    }
                    acquired[0] = o;
                });
                Outbox o = acquired[0];
                if (o == null) continue;
                try {
                    JSONObject result =
                            transport
                                    .call(o.command, OnlineJson.object(o.payload))
                                    .get(50, TimeUnit.SECONDS);
                    if (!active()) return false;
                    ack(o, result);
                } catch (Exception e) {
                    if (!active()) return false;
                    OnlineTransport.Failure f = error(e);
                    boolean retry =
                            Arrays.asList(
                                            "NETWORK",
                                            "UNAVAILABLE",
                                            "SERVER_UNAVAILABLE",
                                            "DEADLINE_EXCEEDED",
                                            "INTERNAL")
                                    .contains(f.reason);
                    boolean conflict = "VERSION_CONFLICT".equals(f.reason);
                    o.state =
                            conflict
                                    ? "CONFLICT"
                                    : retry
                                            ? (o.foreground ? "NEEDS_CONFIRMATION" : "RETRY_WAIT")
                                            : "REJECTED";
                    o.errorCode = f.reason;
                    o.errorMessage = f.getMessage();
                    store.operation(o);
                    if (conflict && o.entityKey.startsWith("QUESTION_DRAFT:")) {
                        read(
                                "getSessionBootstrap",
                                OnlineJson.values("sessionId", o.sessionId),
                                null);
                    }
                    failure(callbacks.remove(o.operationId), f.reason, f.getMessage());
                    if (retry) return false;
                }
            }
            return true;
        } catch (Exception e) {
            failure(null, "NETWORK", "전송 상태를 다시 확인하고 있어요.");
            return false;
        }
    }

    private void ack(Outbox o, JSONObject value) {
        if (value == null) value = new JSONObject();
        final JSONObject result = value;
        db.runInTransaction(() -> {
            applyResult(o, result);
            o.state = "ACKED";
            o.errorCode = "";
            o.errorMessage = "";
            store.operation(o);
        });
        success(callbacks.remove(o.operationId), value);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (OnlineTransport.Subscription s : subscriptions) s.close();
        subscriptions.clear();
        callbacks.clear();
        network.shutdownNow();
        io.execute(() -> {
            try {
                network.awaitTermination(55, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            db.close();
        });
        io.shutdown();
    }
}
