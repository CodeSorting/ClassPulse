package com.example.classpulse.online;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

/** Deferrable retransmission only; never advances a lecture or publishes on its own. */
public final class OnlineSyncWorker extends Worker {
    public OnlineSyncWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    static void schedule(Context c, String uid) {
        OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(OnlineSyncWorker.class)
                        .setInputData(new Data.Builder().putString("uid", uid).build())
                        .setConstraints(
                                new Constraints.Builder()
                                        .setRequiredNetworkType(NetworkType.CONNECTED)
                                        .build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        .build();
        WorkManager.getInstance(c)
                .enqueueUniqueWork(
                        "classpulse-sync-" + OnlineDatabase.accountKey(uid),
                        ExistingWorkPolicy.KEEP,
                        request);
    }

    @NonNull
    @Override
    public Result doWork() {
        String uid = getInputData().getString("uid");
        if (uid == null) return Result.failure();
        OnlineRepository repo = OnlineServices.worker(getApplicationContext(), uid);
        if (repo == null) return Result.failure();
        return repo.drain(false) ? Result.success() : Result.retry();
    }
}
