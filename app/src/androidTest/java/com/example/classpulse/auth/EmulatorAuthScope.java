package com.example.classpulse.auth;

import android.content.Context;
import com.google.firebase.auth.FirebaseAuth;

/** Real Auth emulator adapter, never the production project's FirebaseAuth instance. */
public final class EmulatorAuthScope implements AutoCloseable {
    private final AuthServices.Factory previous;

    public EmulatorAuthScope(FirebaseAuth auth) {
        previous = AuthServices.testFactory;
        AuthServices.testFactory =
                new AuthServices.Factory() {
                    public AuthBackend backend() {
                        return new FirebaseAuthBackend(auth);
                    }

                    public GoogleCredentials credentials(Context context) {
                        return new AuthTestEnvironment.Credentials();
                    }
                };
    }

    public void close() {
        AuthServices.testFactory = previous;
    }
}
