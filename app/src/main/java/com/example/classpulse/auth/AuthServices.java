package com.example.classpulse.auth;

import android.content.Context;

/** Per-ViewModel backend; test override is package-private and only used by androidTest. */
public final class AuthServices {
    interface Factory {
        AuthBackend backend();

        GoogleCredentials credentials(Context context);
    }

    static Factory testFactory;

    private AuthServices() {}

    static AuthBackend backend() {
        return testFactory == null ? new FirebaseAuthBackend() : testFactory.backend();
    }

    public static GoogleCredentials credentials(Context context) {
        return testFactory == null
                ? new CredentialManagerGoogleCredentials(context)
                : testFactory.credentials(context);
    }
}
