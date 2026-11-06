package com.example.classpulse.auth;

import android.app.Activity;
import android.content.Context;
import android.os.CancellationSignal;
import java.util.function.Consumer;

/** androidTest-only providers: never signs into or out of the user's real Firebase account. */
public final class AuthTestEnvironment implements AutoCloseable {
    public final Backend backend = new Backend();
    public final Credentials credentials = new Credentials();
    private final AuthServices.Factory previous;
    private final com.example.classpulse.online.OfflineOnlineScope online;

    private AuthTestEnvironment() {
        online =
                new com.example.classpulse.online.OfflineOnlineScope(
                        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                                .getTargetContext(),
                        () -> backend.user == null ? "" : backend.user.uid);
        previous = AuthServices.testFactory;
        AuthServices.testFactory =
                new AuthServices.Factory() {
                    @Override
                    public AuthBackend backend() {
                        return backend;
                    }

                    @Override
                    public GoogleCredentials credentials(Context context) {
                        return credentials;
                    }
                };
    }

    public static AuthTestEnvironment signedOut() {
        return new AuthTestEnvironment();
    }

    @Override
    public void close() {
        online.close();
        AuthServices.testFactory = previous;
    }

    public static final class Backend implements AuthBackend {
        public AuthIdentity user;
        public Callback pending;
        public int signIns, signOuts;
        private Consumer<AuthIdentity> listener;

        @Override
        public AuthIdentity currentUser() {
            return user;
        }

        @Override
        public Runnable subscribe(Consumer<AuthIdentity> observer) {
            listener = observer;
            return () -> {
                if (listener == observer) listener = null;
            };
        }

        @Override
        public void signInWithGoogleIdToken(String token, Callback callback) {
            signIns++;
            pending = callback;
        }

        @Override
        public void signOut() {
            signOuts++;
            user = null;
            if (listener != null) listener.accept(null);
        }

        public void succeed(String uid, String name, String email) {
            user = new AuthIdentity(uid, name, email);
            if (listener != null) listener.accept(user);
            pending.onSuccess(user);
        }

        public void fail(AuthFailure failure) {
            pending.onError(failure);
        }
    }

    public static final class Credentials implements GoogleCredentials {
        public Callback pending;
        public int requests, clears, clearCancellations;
        public boolean clearSucceeds = true;
        public boolean deferClear;
        public ClearCallback pendingClear;

        @Override
        public void request(
                Activity activity,
                String serverClientId,
                CancellationSignal signal,
                Callback callback) {
            if (serverClientId == null || serverClientId.isEmpty())
                throw new AssertionError("Missing OAuth web client ID");
            requests++;
            pending = callback;
            signal.setOnCancelListener(() -> callback.onError(AuthFailure.CANCELLED));
        }

        @Override
        public Runnable clear(ClearCallback callback) {
            clears++;
            pendingClear = callback;
            if (!deferClear) callback.onComplete(clearSucceeds);
            return () -> clearCancellations++;
        }

        public void token() {
            pending.onToken("instrumentation-test-token");
        }

        public void fail(AuthFailure failure) {
            pending.onError(failure);
        }
    }
}
