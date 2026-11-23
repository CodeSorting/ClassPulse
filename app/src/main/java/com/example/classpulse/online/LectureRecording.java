package com.example.classpulse.online;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.example.classpulse.MainActivity;
import com.example.classpulse.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.FirebaseApp;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Source;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One consent starts a session: rotate files at major-unit boundaries, stop before ending class. */
public final class LectureRecording extends Service {
    private static final String CHANNEL = "lecture_recording";
    private static final String STOP = "classpulse.stop_lecture";
    private static final int NOTIFICATION_ID = 2201;
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final MutableLiveData<Event> events = new MutableLiveData<>();
    private static final Set<String> importing = ConcurrentHashMap.newKeySet();
    private static final ExecutorService fileIo = Executors.newSingleThreadExecutor();
    private static volatile String activeKey = "";
    private static LectureRecording instance;
    // Package-scoped emulator injection; production always uses the default Firebase app.
    static FirebaseApp testApp;
    static FirebaseApp firebaseApp() { return testApp == null ? FirebaseApp.getInstance() : testApp; }
    static boolean microphoneActive() { return instance != null && instance.recorder != null; }

    private MediaRecorder recorder;
    private FirebaseAuth auth;
    private String uid, sessionId;
    private Saved pending;
    private String pendingTopic = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LectureRecordingPlan plan = new LectureRecordingPlan();
    private final Map<String, String> groups = new HashMap<>();
    private ListenerRegistration sessionWatch;
    private boolean closing, verifiedSession;
    private final Runnable verifyTimeout = () -> finishRecording("수업 상태를 확인하지 못해 녹음을 멈췄어요. 연결 후 다시 시작해 주세요.");
    private final FirebaseAuth.AuthStateListener authListener = value -> {
        FirebaseUser user = value.getCurrentUser();
        if (user == null || !user.getUid().equals(uid)) finishRecording("계정이 바뀌어 녹음을 종료했어요.");
    };

    public static final class Saved {
        public final String id;
        public final long startedAt;
        public final File file;

        private Saved(String id, long startedAt, File file) {
            this.id = id;
            this.startedAt = startedAt;
            this.file = file;
        }
    }

    public static final class Event {
        public final String uid, sessionId, message;

        private Event(String uid, String sessionId, String message) {
            this.uid = uid;
            this.sessionId = sessionId;
            this.message = message;
        }
    }

    public static LiveData<Event> events() {
        return events;
    }

    public static boolean isRecording(String uid, String sessionId) {
        return activeKey.equals(key(uid, sessionId));
    }

    public static boolean hasActiveRecording() {
        return !activeKey.isEmpty();
    }

    public static boolean isImporting(String uid, String sessionId) {
        return importing.contains(key(uid, sessionId));
    }

    private static String key(String uid, String sessionId) {
        return uid + "/" + sessionId;
    }

    private static SharedPreferences files(Context context) {
        return context.getSharedPreferences("lecture_recordings", MODE_PRIVATE);
    }

    public static File file(Context context, String uid, String sessionId, String id) {
        return new File(context.getFilesDir(), "lecture-recordings/" + key(uid, sessionId) + "/" + id + ".m4a");
    }

    public static Saved saved(Context context, String uid, String sessionId) {
        SharedPreferences prefs = files(context);
        String key = key(uid, sessionId);
        if (!isRecording(uid, sessionId)) {
            String interrupted = prefs.getString(key + "/pending", "");
            if (!interrupted.isEmpty()) {
                File candidate = file(context, uid, sessionId, interrupted);
                long startedAt = prefs.getLong(key + "/pendingStartedAt", 0);
                String topic = prefs.getString(key + "/pendingTopic", "");
                SharedPreferences.Editor update = prefs.edit().remove(key + "/pending").remove(key + "/pendingStartedAt").remove(key + "/pendingTopic");
                if (startedAt > 0 && playable(candidate, false)) {
                    if (!topic.isEmpty()) {
                        JSONObject existing = LectureRecordingStore.get(context, uid, sessionId, interrupted);
                        if (existing.optString("id").isEmpty() && !LectureRecordingStore.save(context, uid, sessionId, OnlineJson.values(
                                "id", interrupted, "topicId", topic, "startedAt", startedAt,
                                "endedAt", startedAt + duration(candidate), "state", "SAVED", "error", ""))) return null;
                        LectureRecordingStore.resume(context, uid, sessionId);
                    }
                    update.putString(key, interrupted).putLong(key + "/startedAt", startedAt);
                }
                update.apply();
            }
        }
        String id = prefs.getString(key, "");
        if (id.isEmpty()) return null;
        File file = file(context, uid, sessionId, id);
        return file.isFile() ? new Saved(id, prefs.getLong(key + "/startedAt", 0L), file) : null;
    }

    public static void importM4a(Context context, String uid, String sessionId, Uri source) {
        Context app = context.getApplicationContext();
        String key = key(uid, sessionId);
        if (!importing.add(key)) return;
        events.setValue(new Event(uid, sessionId, "녹음 파일을 불러오고 있어요."));
        fileIo.execute(() -> {
            String id = OnlineJson.id();
            File destination = file(app, uid, sessionId, id);
            File partial = new File(destination.getPath() + ".part");
            String message = "파일을 읽지 못했어요. 다시 선택해 주세요.";
            try {
                File directory = destination.getParentFile();
                if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) return;
                try (InputStream input = app.getContentResolver().openInputStream(source)) {
                    if (input == null) return;
                    try (FileOutputStream output = new FileOutputStream(partial)) {
                        byte[] buffer = new byte[32768];
                        long size = 0;
                        int bytes;
                        while ((bytes = input.read(buffer)) != -1) {
                            size += bytes;
                            if (size > MAX_BYTES) {
                                message = "64 MiB 이하의 녹음 파일을 선택해 주세요.";
                                return;
                            }
                            output.write(buffer, 0, bytes);
                        }
                    }
                }
                if (!playable(partial, true)) {
                    message = "재생할 수 있는 M4A 오디오 파일을 선택해 주세요.";
                    return;
                }
                if (!partial.renameTo(destination)) return;
                // Imported recordings have no timestamp on this session's lesson timeline.
                files(app).edit().putString(key, id).putLong(key + "/startedAt", 0L).apply();
                message = "테스트 녹음 파일을 불러왔어요. 녹음 업로드를 눌러 주세요.";
            } catch (Exception ignored) {
                // Keep the current recording when reading or copying the selected file fails.
            } finally {
                if (partial.exists()) partial.delete();
                importing.remove(key);
                events.postValue(new Event(uid, sessionId, message));
            }
        });
    }

    private static boolean playable(File file, boolean m4aOnly) {
        if (!file.isFile() || file.length() == 0) return false;
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(file.getAbsolutePath());
            String duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration == null || Long.parseLong(duration) <= 0) return false;
            if (!m4aOnly) return true;
            String mime = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            return ("audio/mp4".equals(mime) || "video/mp4".equals(mime) || "audio/x-m4a".equals(mime)) &&
                    "yes".equalsIgnoreCase(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)) &&
                    !"yes".equalsIgnoreCase(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));
        } catch (Exception ignored) {
            return false;
        } finally {
            try {
                reader.release();
            } catch (Exception ignored) {
                // The file may not have a readable media container after an abrupt stop.
            }
        }
    }

    private static long duration(File file) {
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(file.getAbsolutePath());
            return Long.parseLong(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Exception ignored) { return 1; }
        finally { try { reader.release(); } catch (Exception ignored) {} }
    }

    public static void start(Context context, String uid, String sessionId) {
        ContextCompat.startForegroundService(context, new Intent(context, LectureRecording.class)
                .putExtra("uid", uid).putExtra("sessionId", sessionId));
    }

    public static void stop(Context context) {
        // Called on the main thread by UI/notification. Release MIC before any network round trip.
        if (instance != null) instance.finishRecording("녹음을 종료했어요. 저장한 대단원은 업로드·변환을 계속해요.");
        else context.stopService(new Intent(context, LectureRecording.class));
    }

    public static void progress(String uid, String sid, JSONObject session) {
        if (instance != null && isRecording(uid, sid) && session != null) instance.applySession(session);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            finishRecording("녹음을 종료했어요.");
            return START_NOT_STICKY;
        }
        if (!activeKey.isEmpty() || closing) return START_NOT_STICKY;
        uid = intent.getStringExtra("uid");
        sessionId = intent.getStringExtra("sessionId");
        auth = FirebaseAuth.getInstance(firebaseApp());
        FirebaseUser user = auth.getCurrentUser();
        if (user == null || !user.getUid().equals(uid) || sessionId == null ||
                !sessionId.matches("[a-zA-Z0-9_-]{8,100}")) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            instance = this;
            activeKey = key(uid, sessionId);
            foreground();
            auth.addAuthStateListener(authListener);
            main.postDelayed(verifyTimeout, 15000);
            FirebaseFirestore db = FirebaseFirestore.getInstance(firebaseApp());
            db.collection("sessions").document(sessionId).collection("topics").get(Source.SERVER)
                    .addOnSuccessListener(topics -> {
                        if (closing) return;
                        for (com.google.firebase.firestore.DocumentSnapshot topic : topics) {
                            if (topic.get("deletedAt") != null) continue;
                            String parent = topic.getString("parentTopicId");
                            groups.put(topic.getId(), parent == null || parent.isEmpty() ? topic.getId() : parent);
                        }
                        sessionWatch = db.collection("sessions").document(sessionId)
                                .addSnapshotListener(MetadataChanges.INCLUDE, (snapshot, error) -> {
                                    if (closing) return;
                                    if (error != null || snapshot == null) {
                                        finishRecording("수업 연결을 확인하지 못해 녹음을 중단했어요."); return;
                                    }
                                    if (snapshot.getMetadata().isFromCache()) {
                                        if (verifiedSession) finishRecording("연결이 끊겨 녹음을 중단했어요. 연결 후 녹음을 다시 시작해 주세요.");
                                        return;
                                    }
                                    verifiedSession = true;
                                    main.removeCallbacks(verifyTimeout);
                                    if (!snapshot.exists()) finishRecording("수업이 없어 녹음을 종료했어요.");
                                    else applySession(OnlineJson.json(snapshot.getData()));
                                });
                    }).addOnFailureListener(error -> finishRecording("수업 목차를 확인하지 못해 녹음을 시작하지 않았어요."));
            events.setValue(new Event(uid, sessionId, "수업 상태를 확인한 뒤 대단원 자동 녹음을 시작해요."));
        } catch (Exception error) {
            finishRecording("녹음을 시작하지 못했어요. 마이크 권한과 저장 공간을 확인해 주세요.");
        }
        return START_NOT_STICKY;
    }

    private void applySession(JSONObject session) {
        if (closing) return;
        boolean live = uid.equals(session.optString("ownerUid")) && session.isNull("deletedAt")
                && "LIVE".equals(session.optString("status"));
        JSONObject progress = session.optJSONObject("progress");
        boolean hasVisit = progress != null && !progress.isNull("currentVisitId")
                && !progress.optString("currentVisitId").isEmpty();
        String group = hasVisit ? groups.get(progress.optString("currentTopicId")) : "";
        if (live && hasVisit && group == null) {
            finishRecording("현재 대단원을 찾지 못해 녹음을 중단했어요."); return;
        }
        LectureRecordingPlan.Action action = plan.update(session.optLong("progressVersion"), live, hasVisit, group == null ? "" : group);
        switch (action) {
            case STOP: finishRecording("수업이 종료되어 녹음을 멈췄어요. 마지막 녹음도 자동으로 변환해요."); break;
            case ROTATE:
            case START:
                if (!saveRecording()) { finishRecording("녹음 저장에 실패해 자동 녹음을 중단했어요."); break; }
                startUnit(group);
                break;
            case WAIT:
                if (!saveRecording()) { finishRecording("녹음 저장에 실패해 자동 녹음을 중단했어요."); break; }
                foreground();
                events.setValue(new Event(uid, sessionId, "모든 주제의 녹음을 마쳤어요. 마이크는 꺼져 있고 재설명 시작을 기다려요."));
                break;
            default: break;
        }
    }

    private void startUnit(String group) {
        if (closing) return;
        try {
            String id = OnlineJson.id();
            File file = file(this, uid, sessionId, id);
            File directory = file.getParentFile();
            if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
                throw new IllegalStateException("recording directory");
            }
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(48000);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioChannels(1);
            recorder.setMaxFileSize(MAX_BYTES);
            recorder.setMaxDuration(2 * 60 * 60 * 1000);
            recorder.setOutputFile(file.getAbsolutePath());
            recorder.setOnInfoListener((source, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                        what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) finishRecording("녹음 한도에 도달해 저장했어요. 필요하면 다시 시작해 주세요.");
            });
            recorder.setOnErrorListener((source, what, extra) -> finishRecording("마이크 오류로 녹음을 중단했어요."));
            recorder.prepare();
            pending = new Saved(id, System.currentTimeMillis(), file);
            pendingTopic = group;
            if (!files(this).edit().putString(activeKey + "/pending", id)
                    .putString(activeKey + "/pendingTopic", group)
                    .putLong(activeKey + "/pendingStartedAt", pending.startedAt).commit()) throw new IllegalStateException("recording journal");
            recorder.start();
            foreground();
            events.setValue(new Event(uid, sessionId, "대단원 녹음 중 · 다음 대단원으로 넘어가면 자동 업로드·STT를 시작해요."));
        } catch (Exception error) {
            finishRecording("녹음을 시작하지 못했어요. 마이크 권한과 저장 공간을 확인해 주세요.");
        }
    }

    private void foreground() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                new NotificationChannel(CHANNEL, "수업 녹음", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, LectureRecording.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        ServiceCompat.startForeground(this, NOTIFICATION_ID, new NotificationCompat.Builder(this, CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat_classpulse)
                        .setContentTitle(recorder == null ? "ClassPulse 자동 녹음 대기" : "ClassPulse 대단원 녹음 중")
                        .setContentText("대단원별 자동 변환 · 수업 종료 시 마이크가 꺼져요.")
                        .setContentIntent(open)
                        .addAction(0, "녹음 종료", stop)
                        .setOngoing(true)
                        .build(),
                Build.VERSION.SDK_INT >= 30 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0);
    }

    private void finishRecording(String message) {
        if (closing) return;
        closing = true;
        plan.close();
        main.removeCallbacks(verifyTimeout);
        if (sessionWatch != null) { sessionWatch.remove(); sessionWatch = null; }
        if (auth != null) auth.removeAuthStateListener(authListener);
        try {
            if (!saveRecording()) message = "녹음은 중단했지만 마지막 파일 저장에 실패했어요. 저장 공간을 확인해 주세요.";
        } finally {
            activeKey = "";
            events.setValue(new Event(uid, sessionId, message));
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private boolean saveRecording() {
        if (recorder == null) return true;
        boolean saved = false;
        try {
            recorder.stop();
            saved = pending != null && pending.file.length() > 0;
        } catch (RuntimeException ignored) {
            // Keep the prior completed recording when the current recording cannot be closed.
        } finally {
            recorder.release();
            recorder = null;
        }
        long endedAt = System.currentTimeMillis();
        String key = key(uid, sessionId);
        if (saved && !LectureRecordingStore.save(this, uid, sessionId, OnlineJson.values(
                "id", pending.id, "topicId", pendingTopic, "startedAt", pending.startedAt,
                "endedAt", Math.max(endedAt, pending.startedAt + 1), "state", "SAVED", "error", ""))) {
            pending = null;
            return false; // Keep pending journal for recovery before beginning another unit.
        }
        SharedPreferences.Editor update = files(this).edit().remove(key + "/pending").remove(key + "/pendingStartedAt").remove(key + "/pendingTopic");
        if (saved) update.putString(key, pending.id).putLong(key + "/startedAt", pending.startedAt);
        update.commit();
        if (saved) {
            try { LectureUploadWorker.enqueue(this, uid, sessionId, pending.id); }
            catch (RuntimeException ignored) { /* SAVED journal is rescheduled when the account reopens. */ }
        }
        pending = null;
        pendingTopic = "";
        events.setValue(new Event(uid, sessionId, saved ? "대단원 녹음을 저장했어요. 자동으로 업로드·변환해요." : "녹음이 너무 짧거나 저장하지 못했어요."));
        // A very short unit can fail MediaRecorder.stop(); never upload a broken container.
        return saved;
    }

    @Override
    public void onDestroy() {
        finishRecording("자동 녹음을 종료했어요. 저장한 파일의 처리는 계속해요.");
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
