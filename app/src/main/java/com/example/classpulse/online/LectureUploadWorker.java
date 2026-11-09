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

public final class LectureUploadWorker extends Worker {
    private volatile UploadTask activeUpload;

    public LectureUploadWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    public static String workName(String id) {
        return "lecture-upload-" + id;
    }

    public static void enqueue(Context context, String uid, String sessionId, String id) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(LectureUploadWorker.class)
                .setInputData(new Data.Builder().putString("uid", uid)
                        .putString("sessionId", sessionId).putString("id", id).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        String uid = getInputData().getString("uid");
        String sessionId = getInputData().getString("sessionId");
        String id = getInputData().getString("id");
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (uid == null || user == null || !uid.equals(user.getUid()) || sessionId == null || id == null) {
            return Result.failure();
        }
        File file = LectureRecording.file(getApplicationContext(), uid, sessionId, id);
        if (!file.isFile() || file.length() == 0 || file.length() > LectureRecording.MAX_BYTES) {
            return Result.failure();
        }
        try {
            StorageReference object = FirebaseStorage.getInstance().getReference()
                    .child("lecture-recordings/" + sessionId + "/" + uid + "/" + id + ".m4a");
            try {
                Tasks.await(object.getMetadata(), 30, TimeUnit.SECONDS);
                return Result.success();
            } catch (ExecutionException error) {
                if (!(error.getCause() instanceof StorageException) ||
                        ((StorageException) error.getCause()).getErrorCode() != StorageException.ERROR_OBJECT_NOT_FOUND) {
                    throw error;
                }
            }
            activeUpload = object.putFile(Uri.fromFile(file),
                    new StorageMetadata.Builder().setContentType("audio/mp4").build());
            activeUpload.addOnProgressListener(snapshot -> {
                if (snapshot.getTotalByteCount() > 0) {
                    int percent = (int) (100 * snapshot.getBytesTransferred() / snapshot.getTotalByteCount());
                    setProgressAsync(new Data.Builder().putInt("percent", percent).build());
                }
            });
            Tasks.await(activeUpload, 7, TimeUnit.MINUTES);
            return Result.success();
        } catch (Exception error) {
            if (activeUpload != null) activeUpload.cancel();
            Throwable cause = error.getCause();
            if (cause instanceof StorageException) {
                int code = ((StorageException) cause).getErrorCode();
                if (code == StorageException.ERROR_NOT_AUTHENTICATED ||
                        code == StorageException.ERROR_NOT_AUTHORIZED ||
                        code == StorageException.ERROR_QUOTA_EXCEEDED) return Result.failure();
            }
            return getRunAttemptCount() < 2 ? Result.retry() : Result.failure();
        }
    }

    @Override
    public void onStopped() {
        UploadTask upload = activeUpload;
        if (upload != null) upload.cancel();
    }
}
