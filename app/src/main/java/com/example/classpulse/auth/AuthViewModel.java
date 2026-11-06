package com.example.classpulse.auth;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

/** Activity-scoped authentication state; stores profiles and request IDs, never tokens. */
public final class AuthViewModel extends ViewModel {
    public enum Phase {
        IDLE,
        CHOOSING_ACCOUNT,
        AUTHENTICATING,
        SIGNING_OUT
    }

    public static final class State {
        public final AuthIdentity user;
        public final Phase phase;
        public final String message;

        State(AuthIdentity user, Phase phase, String message) {
            this.user = user;
            this.phase = phase;
            this.message = message;
        }

        public boolean busy() {
            return phase != Phase.IDLE;
        }
    }

    private final AuthBackend backend;
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final Runnable unsubscribe;
    private Runnable providerCleanup;
    private long generation;
    private boolean closed;

    public AuthViewModel() {
        this(AuthServices.backend());
    }

    AuthViewModel(AuthBackend backend) {
        this.backend = backend;
        state.setValue(new State(backend.currentUser(), Phase.IDLE, ""));
        unsubscribe =
                backend.subscribe(user -> {
                    if (closed) return;
                    State current = current();
                    state.setValue(new State(user, current.phase, current.message));
                });
    }

    public LiveData<State> state() {
        return state;
    }

    public State current() {
        return state.getValue();
    }

    public long beginCredentialRequest() {
        if (closed || current().busy() || current().user != null) return -1;
        long request = ++generation;
        cancelProviderCleanup();
        publish(null, Phase.CHOOSING_ACCOUNT, "");
        return request;
    }

    public void credentialFailed(long request, AuthFailure failure) {
        if (!accepts(request, Phase.CHOOSING_ACCOUNT)) return;
        publish(backend.currentUser(), Phase.IDLE, failure.message);
    }

    public void abandonCredentialRequest(long request) {
        if (!accepts(request, Phase.CHOOSING_ACCOUNT)) return;
        ++generation;
        publish(backend.currentUser(), Phase.IDLE, "로그인을 계속하려면 Google 버튼을 다시 눌러 주세요.");
    }

    public void credentialReceived(long request, String token) {
        if (!accepts(request, Phase.CHOOSING_ACCOUNT)) return;
        if (token == null || token.trim().isEmpty()) {
            credentialFailed(request, AuthFailure.REJECTED);
            return;
        }
        publish(null, Phase.AUTHENTICATING, "");
        backend.signInWithGoogleIdToken(
                token,
                new AuthBackend.Callback() {
                    @Override
                    public void onSuccess(AuthIdentity user) {
                        if (accepts(request, Phase.AUTHENTICATING)) publish(user, Phase.IDLE, "");
                    }

                    @Override
                    public void onError(AuthFailure failure) {
                        if (accepts(request, Phase.AUTHENTICATING))
                            publish(backend.currentUser(), Phase.IDLE, failure.message);
                    }
                });
    }

    public void signOut(GoogleCredentials credentials) {
        if (closed || current().busy() || current().user == null) return;
        long request = ++generation;
        cancelProviderCleanup();
        publish(current().user, Phase.SIGNING_OUT, "");
        try {
            backend.signOut();
        } catch (RuntimeException error) {
            publish(backend.currentUser(), Phase.IDLE, "로그아웃을 완료하지 못했어요. 다시 시도해 주세요.");
            return;
        }
        // Firebase signOut is synchronous. A provider that never calls back must not lock the UI.
        publish(null, Phase.IDLE, "로그아웃했어요.");
        if (!accepts(request, Phase.IDLE) || current().user != null) return;
        try {
            Runnable cleanup =
                    credentials.clear(cleared -> {
                        if (accepts(request, Phase.IDLE) && current().user == null)
                            publish(
                                    null,
                                    Phase.IDLE,
                                    cleared
                                            ? "로그아웃했어요."
                                            : "로그아웃했어요. 다음 로그인에서 사용할 Google 계정을 다시 확인해 주세요.");
                    });
            if (accepts(request, Phase.IDLE) && current().user == null) providerCleanup = cleanup;
            else if (cleanup != null) cleanup.run();
        } catch (RuntimeException error) {
            if (accepts(request, Phase.IDLE) && current().user == null)
                publish(null, Phase.IDLE, "로그아웃했어요. 다음 로그인에서 사용할 Google 계정을 다시 확인해 주세요.");
        }
    }

    private void cancelProviderCleanup() {
        Runnable cleanup = providerCleanup;
        providerCleanup = null;
        if (cleanup != null)
            try {
                cleanup.run();
            } catch (RuntimeException ignored) {
                /* Cleanup cannot block a new login. */
            }
    }

    private boolean accepts(long request, Phase phase) {
        return !closed && generation == request && current().phase == phase;
    }

    private void publish(AuthIdentity user, Phase phase, String message) {
        state.setValue(new State(user, phase, message));
    }

    @Override
    protected void onCleared() {
        closed = true;
        ++generation;
        cancelProviderCleanup();
        unsubscribe.run();
        super.onCleared();
    }
}
