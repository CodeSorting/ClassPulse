package com.example.classpulse.auth;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import java.util.function.Consumer;

/** The only production adapter that exchanges a Google ID token for a Firebase session. */
final class FirebaseAuthBackend implements AuthBackend {
    private final FirebaseAuth auth;

    FirebaseAuthBackend() {
        this(FirebaseAuth.getInstance());
    }

    FirebaseAuthBackend(FirebaseAuth auth) {
        this.auth = auth;
    }

    @Override
    public AuthIdentity currentUser() {
        return profile(auth.getCurrentUser());
    }

    @Override
    public Runnable subscribe(Consumer<AuthIdentity> observer) {
        FirebaseAuth.AuthStateListener listener =
                value -> observer.accept(profile(value.getCurrentUser()));
        auth.addAuthStateListener(listener);
        return () -> auth.removeAuthStateListener(listener);
    }

    @Override
    public void signInWithGoogleIdToken(String idToken, Callback callback) {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .addOnCompleteListener(task -> {
                    AuthIdentity identity = currentUser();
                    if (task.isSuccessful() && identity != null) callback.onSuccess(identity);
                    else callback.onError(failure(task.getException()));
                });
    }

    @Override
    public void signOut() {
        auth.signOut();
    }

    private static AuthIdentity profile(FirebaseUser user) {
        return user == null
                ? null
                : new AuthIdentity(user.getUid(), user.getDisplayName(), user.getEmail());
    }

    private static AuthFailure failure(Exception error) {
        if (error instanceof FirebaseNetworkException) return AuthFailure.NETWORK;
        if (error instanceof FirebaseAuthException) {
            String code = ((FirebaseAuthException) error).getErrorCode();
            if ("ERROR_OPERATION_NOT_ALLOWED".equals(code)
                    || "ERROR_INVALID_API_KEY".equals(code)
                    || "ERROR_APP_NOT_AUTHORIZED".equals(code)
                    || "ERROR_PROJECT_NOT_FOUND".equals(code)) return AuthFailure.CONFIGURATION;
            return AuthFailure.REJECTED;
        }
        return AuthFailure.UNKNOWN;
    }
}
