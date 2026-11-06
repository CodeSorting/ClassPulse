package com.example.classpulse.auth;

import java.util.function.Consumer;

public interface AuthBackend {
    interface Callback {
        void onSuccess(AuthIdentity identity);

        void onError(AuthFailure failure);
    }

    AuthIdentity currentUser();

    Runnable subscribe(Consumer<AuthIdentity> observer);

    void signInWithGoogleIdToken(String idToken, Callback callback);

    void signOut();
}
