package com.example.classpulse.online;

import android.Manifest;
import android.content.Context;
import androidx.lifecycle.Lifecycle;
import androidx.room.Room;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.WorkManager;
import com.example.classpulse.MainActivity;
import com.example.classpulse.auth.EmulatorAuthScope;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.storage.FirebaseStorage;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real microphone service + local Auth/Firestore/Storage. No paid STT API is used. */
@RunWith(AndroidJUnit4.class)
public class AutomaticRecordingInstrumentedTest {
    private Context context;
    private FirebaseApp app;
    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private FirebaseOnlineTransport transport;
    private OnlineRepository repo;
    private ActivityScenario<MainActivity> scenario;
    private EmulatorAuthScope authScope;
    private OnlineServices.Factory previous;
    private String uid, sid, first, second, child1, child2, previousRole;
    private long version;

    private void main(Runnable run) { InstrumentationRegistry.getInstrumentation().runOnMainSync(run); }
    private void await(Callable<Boolean> check) throws Exception {
        long limit = System.currentTimeMillis() + 45000;
        while (System.currentTimeMillis() < limit) {
            if (check.call()) return;
            Thread.sleep(150);
        }
        fail("Timed out waiting for automatic recording state");
    }
    private JSONObject mutate(String action, JSONObject payload) throws Exception {
        payload.put("operationId", OnlineJson.id());
        return transport.call(action, payload).get(45, TimeUnit.SECONDS);
    }
    private JSONObject progress(String action) throws Exception {
        JSONObject result = mutate("changeProgress", OnlineJson.values("sessionId", sid,
                "expectedProgressVersion", version, "action", action));
        version = result.getJSONObject("session").getLong("progressVersion");
        return result.getJSONObject("session");
    }
    private List<JSONObject> files() { return LectureRecordingStore.list(context, uid, sid); }
    private void start() throws Exception {
        scenario.onActivity(activity -> LectureRecording.start(activity, uid, sid));
        await(LectureRecording::microphoneActive);
        Thread.sleep(1800); // Enough AAC frames for MediaRecorder.stop() to finalize a playable file.
    }

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.RECORD_AUDIO);
        if (android.os.Build.VERSION.SDK_INT >= 37) InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(context.getPackageName(), "android.permission.ACCESS_LOCAL_NETWORK");
        String host = InstrumentationRegistry.getArguments().getString("firebaseEmulatorHost", "10.0.2.2");
        app = FirebaseApp.initializeApp(context, new FirebaseOptions.Builder()
                .setApplicationId("1:123456789:android:abcdef0123456789").setProjectId("demo-classpulse")
                .setApiKey("A_classpulse_emulator_only_placeholder0").setStorageBucket("demo-classpulse.appspot.com").build(), "recording-" + OnlineJson.id());
        app.setDataCollectionDefaultEnabled(false);
        auth = FirebaseAuth.getInstance(app);
        auth.useEmulator(host, 9099);
        Tasks.await(auth.createUserWithEmailAndPassword(OnlineJson.id() + "@example.test", "emulator-only-password"), 30, TimeUnit.SECONDS);
        uid = auth.getCurrentUser().getUid();
        transport = new FirebaseOnlineTransport(app);
        db = FirebaseFirestore.getInstance(app);
        db.useEmulator(host, 8080);
        FirebaseFunctions.getInstance(app, "asia-northeast3").useEmulator(host, 5001);
        FirebaseStorage.getInstance(app).useEmulator(host, 9199);
        repo = new OnlineRepository(context, uid, Room.inMemoryDatabaseBuilder(context, OnlineDatabase.class).build(), transport, false);
        sid = OnlineJson.id(); first = OnlineJson.id(); second = OnlineJson.id(); child1 = OnlineJson.id(); child2 = OnlineJson.id();
        mutate("createSession", OnlineJson.values("sessionId", sid, "title", "자동 녹음 검증", "description", "",
                "topics", new JSONArray().put(OnlineJson.values("id", first, "title", "대단원 1"))
                        .put(OnlineJson.values("id", child1, "title", "소단원 1", "parentTopicId", first))
                        .put(OnlineJson.values("id", child2, "title", "소단원 2", "parentTopicId", first))
                        .put(OnlineJson.values("id", second, "title", "대단원 2"))));
        progress("START");
        previous = OnlineServices.testFactory;
        previousRole = context.getSharedPreferences("local_demo", 0).getString("role", null);
        context.getSharedPreferences("local_demo", 0).edit().putString("role", "NONE").commit();
        main(() -> {
            LectureRecording.testApp = app;
            authScope = new EmulatorAuthScope(auth);
            OnlineServices.close();
            OnlineServices.testFactory = (c, identity) -> repo;
        });
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After public void tearDown() throws Exception {
        main(() -> LectureRecording.stop(context));
        if (db != null) Tasks.await(db.enableNetwork());
        if (uid != null && sid != null) for (JSONObject file : files()) {
            WorkManager.getInstance(context).cancelUniqueWork(LectureUploadWorker.workName(file.optString("id"))).getResult().get(10, TimeUnit.SECONDS);
        }
        if (scenario != null) scenario.close();
        main(() -> {
            OnlineServices.close();
            OnlineServices.testFactory = previous;
            if (authScope != null) authScope.close();
            LectureRecording.testApp = null;
        });
        android.content.SharedPreferences.Editor editor = context.getSharedPreferences("local_demo", 0).edit();
        if (previousRole == null) editor.remove("role"); else editor.putString("role", previousRole);
        editor.commit();
        if (db != null) Tasks.await(db.terminate(), 10, TimeUnit.SECONDS);
        // Keep the named app alive until in-flight cancelled workers have released their tasks.
    }

    @Test public void rotatesOnlyAcrossMajorUnitsAndUploadsEveryFileBeforeClassEnds() throws Exception {
        start();
        String initial = context.getSharedPreferences("lecture_recordings", 0).getString(uid + "/" + sid + "/pending", "");
        progress("COMPLETE_NEXT");
        Thread.sleep(1000);
        assertTrue(LectureRecording.microphoneActive());
        assertTrue(files().isEmpty());
        assertEquals(initial, context.getSharedPreferences("lecture_recordings", 0).getString(uid + "/" + sid + "/pending", ""));
        JSONObject next = progress("COMPLETE_NEXT");
        await(() -> files().size() == 1 && LectureRecording.microphoneActive());
        assertEquals(first, files().get(0).optString("topicId"));
        main(() -> LectureRecording.progress(uid, sid, next)); // Duplicate ACK must not rotate twice.
        Thread.sleep(1800);
        assertEquals(1, files().size());
        main(() -> LectureRecording.stop(context));
        assertFalse(LectureRecording.microphoneActive());
        assertEquals(2, files().size());
        main(() -> LectureRecording.progress(uid, sid, next));
        assertFalse(LectureRecording.microphoneActive());
        progress("END");
        for (JSONObject file : files()) {
            String id = file.optString("id");
            assertTrue(LectureRecording.file(context, uid, sid, id).length() > 0);
            await(() -> Tasks.await(db.document("sessions/" + sid + "/processingJobs/" + id).get(Source.SERVER), 10, TimeUnit.SECONDS).exists());
            assertTrue(Tasks.await(FirebaseStorage.getInstance(app).getReference()
                    .child("lecture-recordings/" + sid + "/" + uid + "/" + id + ".m4a").getMetadata(), 20, TimeUnit.SECONDS).getSizeBytes() > 0);
        }
        assertEquals(second, files().get(1).optString("topicId"));
        assertFalse(LectureRecording.isRecording(uid, sid));
    }

    @Test public void remoteClassEndStopsMicrophoneWithScreenInBackground() throws Exception {
        start();
        scenario.moveToState(Lifecycle.State.CREATED);
        assertTrue(LectureRecording.microphoneActive());
        progress("END");
        await(() -> !LectureRecording.isRecording(uid, sid));
        assertFalse(LectureRecording.microphoneActive());
        assertEquals(1, files().size());
    }

    @Test public void recoveryAfterFinalizingKeepsOriginalUploadMetadata() throws Exception {
        start();
        main(() -> LectureRecording.stop(context));
        assertEquals(1, files().size());
        JSONObject saved = files().get(0);
        long endedAt = saved.getLong("endedAt");
        String key = uid + "/" + sid;
        // Simulate process death after the durable file entry but before pending-journal cleanup.
        context.getSharedPreferences("lecture_recordings", 0).edit()
                .putString(key + "/pending", saved.getString("id"))
                .putString(key + "/pendingTopic", saved.getString("topicId"))
                .putLong(key + "/pendingStartedAt", saved.getLong("startedAt")).commit();
        assertEquals(saved.getString("id"), LectureRecording.saved(context, uid, sid).id);
        assertEquals(1, files().size());
        assertEquals(endedAt, files().get(0).getLong("endedAt"));
        assertFalse(context.getSharedPreferences("lecture_recordings", 0).contains(key + "/pending"));
        assertFalse(LectureRecording.microphoneActive());
    }

    @Test public void lostSessionConnectionStopsMicrophoneAndDoesNotSilentlyRestart() throws Exception {
        start();
        Tasks.await(db.disableNetwork());
        await(() -> !LectureRecording.isRecording(uid, sid));
        assertFalse(LectureRecording.microphoneActive());
        assertEquals(1, files().size());
        Tasks.await(db.enableNetwork());
        Thread.sleep(700);
        assertFalse(LectureRecording.microphoneActive());
        progress("END");
        scenario.onActivity(activity -> LectureRecording.start(activity, uid, sid));
        Thread.sleep(1800);
        assertFalse(LectureRecording.microphoneActive());
        assertFalse(LectureRecording.isRecording(uid, sid));
    }
}
