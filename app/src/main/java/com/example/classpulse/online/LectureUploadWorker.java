package com.example.classpulse.online;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.BackoffPolicy;
import com.google.firebase.FirebaseApp;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageException;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;
import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public final class LectureUploadWorker extends Worker {
    private volatile UploadTask activeUpload;

    public LectureUploadWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    public static String workName(String id) {
        return "lecture-upload-" + id;
    }

    public static void enqueue(Context context, String uid, String sessionId, String id) {
        LectureRecordingStore.state(context, uid, sessionId, id, "QUEUED", "");
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(LectureUploadWorker.class)
                .setInputData(new Data.Builder().putString("uid", uid)
                        .putString("sessionId", sessionId).putString("id", id).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        FirebaseApp app = LectureRecording.firebaseApp();
        String uid = getInputData().getString("uid");
        String sessionId = getInputData().getString("sessionId");
        String id = getInputData().getString("id");
        FirebaseUser user = FirebaseAuth.getInstance(app).getCurrentUser();
        if (uid == null || user == null || !uid.equals(user.getUid()) || sessionId == null || id == null) {
            if (uid != null && sessionId != null && id != null)
                LectureRecordingStore.state(getApplicationContext(), uid, sessionId, id, "FAILED", "같은 계정으로 로그인한 뒤 다시 시도해 주세요.");
            return Result.failure();
        }
        File file = LectureRecording.file(getApplicationContext(), uid, sessionId, id);
        if (!file.isFile() || file.length() == 0 || file.length() > LectureRecording.MAX_BYTES) {
            LectureRecordingStore.state(getApplicationContext(), uid, sessionId, id, "FAILED", "저장된 녹음 파일을 확인해 주세요.");
            return Result.failure();
        }
        try {
            LectureRecordingStore.state(getApplicationContext(), uid, sessionId, id, "UPLOADING", "");
            StorageReference object = FirebaseStorage.getInstance(app).getReference()
                    .child("lecture-recordings/" + sessionId + "/" + uid + "/" + id + ".m4a");
            boolean uploaded = false;
            try {
                Tasks.await(object.getMetadata(), 30, TimeUnit.SECONDS);
                uploaded = true;
            } catch (ExecutionException error) {
                if (!(error.getCause() instanceof StorageException) ||
                        ((StorageException) error.getCause()).getErrorCode() != StorageException.ERROR_OBJECT_NOT_FOUND) {
                    throw error;
                }
            }
            if (!uploaded) {
                activeUpload = object.putFile(Uri.fromFile(file),
                        new StorageMetadata.Builder().setContentType("audio/mp4").build());
                activeUpload.addOnProgressListener(snapshot -> {
                    if (snapshot.getTotalByteCount() > 0) {
                        int percent = (int) (100 * snapshot.getBytesTransferred() / snapshot.getTotalByteCount());
                        setProgressAsync(new Data.Builder().putInt("percent", percent).build());
                    }
                });
                Tasks.await(activeUpload, 6, TimeUnit.MINUTES);
            }
            if (isStopped()) return Result.retry();
            JSONObject recording = LectureRecordingStore.get(getApplicationContext(), uid, sessionId, id);
            if (!recording.optString("topicId").isEmpty()) {
                LectureRecordingStore.state(getApplicationContext(), uid, sessionId, id, "SUBMITTING", "");
                JSONObject result = new FirebaseOnlineTransport(app).call("learning.startLectureStt",
                        OnlineJson.values("sessionId", sessionId, "recordingId", id,
                                "topicId", recording.optString("topicId"), "startedAt", recording.optLong("startedAt"),
                                "endedAt", recording.optLong("endedAt"))).get(185, TimeUnit.SECONDS);
                JSONObject job = result.optJSONObject("job");
                if (job == null) throw new IllegalStateException("Missing STT job");
                LectureRecordingStore.state(getApplicationContext(), uid, sessionId, id, job.optString("state"), job.optString("error"));
            }
            return Result.success();
        } catch (Exception error) {
            if (activeUpload != null) activeUpload.cancel();
            Throwable cause = error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error;
            boolean retry = true;
            String message = "연결을 확인한 뒤 다시 시도해 주세요.";
            if (cause instanceof StorageException) {
                int code = ((StorageException) cause).getErrorCode();
                if (code == StorageException.ERROR_NOT_AUTHENTICATED ||
                        code == StorageException.ERROR_NOT_AUTHORIZED ||
                        code == StorageException.ERROR_QUOTA_EXCEEDED) retry = false;
            }
            if (cause instanceof OnlineTransport.Failure) {
                OnlineTransport.Failure failure = (OnlineTransport.Failure) cause;
                message = failure.getMessage();
                retry = java.util.Arrays.asList("NETWORK", "UNAVAILABLE", "SERVER_UNAVAILABLE", "DEADLINE_EXCEEDED", "INTERNAL", "RATE_LIMITED")
                        .contains(failure.reason);
            }
            retry = retry && getRunAttemptCount() < 6;
            LectureRecordingStore.state(getApplicationContext(), uid, sessionId, id, retry ? "QUEUED" : "FAILED", message);
            return retry ? Result.retry() : Result.failure();
        }
    }

    @Override
    public void onStopped() {
        UploadTask upload = activeUpload;
        if (upload != null) upload.cancel();
    }
}
