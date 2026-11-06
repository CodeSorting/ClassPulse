package com.example.classpulse.auth;

import android.app.Activity;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException;
import androidx.credentials.exceptions.GetCredentialUnsupportedException;
import androidx.credentials.exceptions.NoCredentialException;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Explicit button flow supports both existing accounts and adding a Google account. */
final class CredentialManagerGoogleCredentials implements GoogleCredentials {
    private final Context application;

    CredentialManagerGoogleCredentials(Context context) {
        application = context.getApplicationContext();
    }

    @Override
    public void request(
            Activity activity,
            String serverClientId,
            CancellationSignal cancellation,
            Callback callback) {
        try {
            GetCredentialRequest request =
                    new GetCredentialRequest.Builder()
                            .addCredentialOption(
                                    new GetSignInWithGoogleOption.Builder(serverClientId).build())
                            .build();
            CredentialManager.create(application)
                    .getCredentialAsync(
                            activity,
                            request,
                            cancellation,
                            ContextCompat.getMainExecutor(application),
                            new CredentialManagerCallback<
                                    GetCredentialResponse, GetCredentialException>() {
                                @Override
                                public void onResult(GetCredentialResponse response) {
                                    try {
                                        Credential credential = response.getCredential();
                                        if (!(credential instanceof CustomCredential)
                                                || !GoogleIdTokenCredential
                                                        .TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                                        .equals(credential.getType())) {
                                            callback.onError(AuthFailure.REJECTED);
                                            return;
                                        }
                                        callback.onToken(
                                                GoogleIdTokenCredential.createFrom(
                                                                ((CustomCredential) credential)
                                                                        .getData())
                                                        .getIdToken());
                                    } catch (Exception error) {
                                        callback.onError(AuthFailure.REJECTED);
                                    }
                                }

                                @Override
                                public void onError(GetCredentialException error) {
                                    if (error instanceof GetCredentialCancellationException)
                                        callback.onError(AuthFailure.CANCELLED);
                                    else if (error instanceof NoCredentialException)
                                        callback.onError(AuthFailure.NO_ACCOUNT);
                                    else if (error
                                                    instanceof
                                                    GetCredentialProviderConfigurationException
                                            || error instanceof GetCredentialUnsupportedException)
                                        callback.onError(AuthFailure.PROVIDER_UNAVAILABLE);
                                    else callback.onError(AuthFailure.UNKNOWN);
                                }
                            });
        } catch (Exception error) {
            callback.onError(AuthFailure.CONFIGURATION);
        }
    }

    @Override
    public Runnable clear(ClearCallback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        CancellationSignal cancellation = new CancellationSignal();
        AtomicBoolean finished = new AtomicBoolean();
        Runnable[] timeout = new Runnable[1];
        Consumer<Boolean> finish =
                cleared -> {
                    if (!finished.compareAndSet(false, true)) return;
                    main.removeCallbacks(timeout[0]);
                    callback.onComplete(cleared);
                };
        Runnable cancel =
                () -> {
                    if (finished.get()) return;
                    try {
                        cancellation.cancel();
                    } catch (RuntimeException ignored) {
                    } finally {
                        finish.accept(false);
                    }
                };
        timeout[0] = cancel;
        main.postDelayed(cancel, 5000);
        try {
            CredentialManager.create(application)
                    .clearCredentialStateAsync(
                            new ClearCredentialStateRequest(),
                            cancellation,
                            ContextCompat.getMainExecutor(application),
                            new CredentialManagerCallback<Void, ClearCredentialException>() {
                                @Override
                                public void onResult(Void ignored) {
                                    finish.accept(true);
                                }

                                @Override
                                public void onError(ClearCredentialException error) {
                                    finish.accept(false);
                                }
                            });
        } catch (Exception error) {
            finish.accept(false);
        }
        return cancel;
    }
}
