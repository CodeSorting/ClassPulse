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
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.example.classpulse.MainActivity;
import com.example.classpulse.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Records one lecture in app-private storage independently of the current screen. */
public final class LectureRecording extends Service {
    private static final String CHANNEL = "lecture_recording";
    private static final String STOP = "classpulse.stop_lecture";
    private static final int NOTIFICATION_ID = 2201;
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final MutableLiveData<Event> events = new MutableLiveData<>();
    private static final Set<String> importing = ConcurrentHashMap.newKeySet();
    private static final ExecutorService fileIo = Executors.newSingleThreadExecutor();
    private static String activeKey = "";

    private MediaRecorder recorder;
    private FirebaseAuth auth;
    private String uid, sessionId;
    private Saved pending;
    private final FirebaseAuth.AuthStateListener authListener = value -> {
        FirebaseUser user = value.getCurrentUser();
        if (user == null || !user.getUid().equals(uid)) finishRecording();
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
                SharedPreferences.Editor update = prefs.edit().remove(key + "/pending").remove(key + "/pendingStartedAt");
                if (startedAt > 0 && playable(candidate, false)) update.putString(key, interrupted).putLong(key + "/startedAt", startedAt);
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

    public static void start(Context context, String uid, String sessionId) {
        ContextCompat.startForegroundService(context, new Intent(context, LectureRecording.class)
                .putExtra("uid", uid).putExtra("sessionId", sessionId));
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, LectureRecording.class).setAction(STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            finishRecording();
            return START_NOT_STICKY;
        }
        if (recorder != null) return START_NOT_STICKY;
        uid = intent.getStringExtra("uid");
        sessionId = intent.getStringExtra("sessionId");
        auth = FirebaseAuth.getInstance();
        FirebaseUser user = auth.getCurrentUser();
        if (user == null || !user.getUid().equals(uid) || sessionId == null ||
                !sessionId.matches("[a-zA-Z0-9_-]{8,100}")) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            foreground();
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
                        what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) finishRecording();
            });
            recorder.setOnErrorListener((source, what, extra) -> finishRecording());
            recorder.prepare();
            recorder.start();
            pending = new Saved(id, System.currentTimeMillis(), file);
            activeKey = key(uid, sessionId);
            files(this).edit().putString(activeKey + "/pending", id)
                    .putLong(activeKey + "/pendingStartedAt", pending.startedAt).apply();
            auth.addAuthStateListener(authListener);
            events.setValue(new Event(uid, sessionId, "수업을 녹음하고 있어요."));
        } catch (Exception error) {
            if (recorder != null) recorder.release();
            recorder = null;
            events.setValue(new Event(uid, sessionId, "녹음을 시작하지 못했어요. 마이크 권한과 저장 공간을 확인해 주세요."));
            stopSelf();
        }
        return START_NOT_STICKY;
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
                        .setContentTitle("ClassPulse 수업 녹음 중")
                        .setContentText("수업 화면을 나가도 녹음이 이어져요.")
                        .setContentIntent(open)
                        .addAction(0, "녹음 종료", stop)
                        .setOngoing(true)
                        .build(),
                Build.VERSION.SDK_INT >= 30 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0);
    }

    private void finishRecording() {
        saveRecording();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void saveRecording() {
        if (recorder == null) return;
        boolean saved = false;
        try {
            recorder.stop();
            saved = pending != null && pending.file.length() > 0;
        } catch (RuntimeException ignored) {
            // Keep the prior completed recording when the current recording cannot be closed.
        } finally {
            recorder.release();
            recorder = null;
            activeKey = "";
            if (auth != null) auth.removeAuthStateListener(authListener);
        }
        String key = key(uid, sessionId);
        SharedPreferences.Editor update = files(this).edit().remove(key + "/pending").remove(key + "/pendingStartedAt");
        if (saved) update.putString(key, pending.id).putLong(key + "/startedAt", pending.startedAt);
        update.apply();
        pending = null;
        events.setValue(new Event(uid, sessionId, saved ? "녹음을 기기에 저장했어요." : "녹음을 저장하지 못했어요."));
    }

    @Override
    public void onDestroy() {
        saveRecording();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
