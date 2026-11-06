package com.example.classpulse.auth;

import android.app.Activity;
import android.os.CancellationSignal;

public interface GoogleCredentials {
    interface Callback {
        void onToken(String idToken);

        void onError(AuthFailure failure);
    }

    interface ClearCallback {
        void onComplete(boolean cleared);
    }

    void request(
            Activity activity,
            String serverClientId,
            CancellationSignal cancellation,
            Callback callback);

    /** Best-effort provider cleanup. Returns a cancellation handle; does not sign out Firebase. */
    Runnable clear(ClearCallback callback);
}
