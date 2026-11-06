package com.example.classpulse.auth;

import android.app.Activity;
import android.os.CancellationSignal;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import org.junit.Rule;
import org.junit.Test;
import java.util.function.Consumer;
import static org.junit.Assert.*;

public class AuthViewModelTest {
    @Rule public InstantTaskExecutorRule tasks = new InstantTaskExecutorRule();
    private static final AuthIdentity USER =
            new AuthIdentity("firebase-user-a", "테스트 사용자", "test@example.test");

    private static final class Backend implements AuthBackend {
        AuthIdentity user;
        Consumer<AuthIdentity> observer;
        Callback pending;
        int signIns, signOuts;
        boolean signOutThrows;

        @Override
        public AuthIdentity currentUser() {
            return user;
        }

        @Override
        public Runnable subscribe(Consumer<AuthIdentity> listener) {
            observer = listener;
            return () -> observer = null;
        }

        @Override
        public void signInWithGoogleIdToken(String token, Callback callback) {
            signIns++;
            pending = callback;
        }

        @Override
        public void signOut() {
            signOuts++;
            if (signOutThrows) throw new IllegalStateException("test failure");
            user = null;
            if (observer != null) observer.accept(null);
        }

        void complete() {
            user = USER;
            if (observer != null) observer.accept(user);
            pending.onSuccess(user);
        }
    }

    private static final class Credentials implements GoogleCredentials {
        ClearCallback clear;
        int clears, cancellations;
        boolean clearThrows;

        @Override
        public void request(
                Activity activity,
                String serverClientId,
                CancellationSignal cancellation,
                GoogleCredentials.Callback callback) {
            throw new AssertionError("No account UI in unit test");
        }

        @Override
        public Runnable clear(ClearCallback callback) {
            clears++;
            if (clearThrows) throw new IllegalStateException("test failure");
            clear = callback;
            return () -> cancellations++;
        }
    }

    @Test
    public void cancellingAndRetryingRejectsLateTokensFromPreviousRequest() {
        Backend backend = new Backend();
        AuthViewModel model = new AuthViewModel(backend);
        long old = model.beginCredentialRequest();
        assertEquals(-1, model.beginCredentialRequest());
        model.credentialFailed(old, AuthFailure.CANCELLED);
        assertFalse(model.current().busy());
        assertNull(model.current().user);
        long next = model.beginCredentialRequest();
        model.credentialReceived(old, "stale-test-token");
        assertEquals(0, backend.signIns);
        assertEquals(AuthViewModel.Phase.CHOOSING_ACCOUNT, model.current().phase);
        model.credentialReceived(next, "current-test-token");
        assertEquals(1, backend.signIns);
        backend.complete();
        assertEquals(USER.uid, model.current().user.uid);
        assertEquals(AuthViewModel.Phase.IDLE, model.current().phase);
    }

    @Test
    public void destroyedAccountPickerCannotAuthenticateAfterRecreation() {
        Backend backend = new Backend();
        AuthViewModel model = new AuthViewModel(backend);
        long request = model.beginCredentialRequest();
        model.abandonCredentialRequest(request);
        model.credentialReceived(request, "stale-test-token");
        assertEquals(0, backend.signIns);
        assertNull(model.current().user);
        assertFalse(model.current().busy());
        assertTrue(model.beginCredentialRequest() > request);
    }

    @Test
    public void firebaseExchangeSurvivesPickerDestructionAndPublishesVerifiedUser() {
        Backend backend = new Backend();
        AuthViewModel model = new AuthViewModel(backend);
        long request = model.beginCredentialRequest();
        model.credentialReceived(request, "test-token");
        model.abandonCredentialRequest(request);
        assertEquals(AuthViewModel.Phase.AUTHENTICATING, model.current().phase);
        backend.complete();
        assertEquals(USER.uid, model.current().user.uid);
        assertFalse(model.current().busy());
    }

    @Test
    public void failedExchangeKeepsAccountSignedOutAndAllowsRetry() {
        Backend backend = new Backend();
        AuthViewModel model = new AuthViewModel(backend);
        long request = model.beginCredentialRequest();
        model.credentialReceived(request, "test-token");
        backend.pending.onError(AuthFailure.NETWORK);
        assertNull(model.current().user);
        assertEquals(AuthFailure.NETWORK.message, model.current().message);
        assertFalse(model.current().busy());
        assertTrue(model.beginCredentialRequest() > request);
    }

    @Test
    public void missingProviderCallbackCannotKeepTheAccountSigningOut() {
        Backend backend = new Backend();
        backend.user = USER;
        AuthViewModel model = new AuthViewModel(backend);
        Credentials credentials = new Credentials();
        model.signOut(credentials);
        assertEquals(1, backend.signOuts);
        assertEquals(1, credentials.clears);
        assertNull(model.current().user);
        assertFalse(model.current().busy());
        assertEquals(AuthViewModel.Phase.IDLE, model.current().phase);
        assertEquals("로그아웃했어요.", model.current().message);
        model.signOut(credentials);
        assertEquals(1, backend.signOuts);
        assertEquals(1, credentials.clears);
        assertTrue(model.beginCredentialRequest() > 0);
    }

    @Test
    public void providerClearFailureDoesNotRestoreSignedOutAccount() {
        Backend backend = new Backend();
        backend.user = USER;
        AuthViewModel model = new AuthViewModel(backend);
        Credentials credentials = new Credentials();
        model.signOut(credentials);
        credentials.clear.onComplete(false);
        assertNull(model.current().user);
        assertNull(backend.user);
        assertFalse(model.current().busy());
        assertTrue(model.beginCredentialRequest() > 0);
    }

    @Test
    public void newLoginCancelsOldCleanupAndIgnoresItsLateFailure() {
        Backend backend = new Backend();
        backend.user = USER;
        AuthViewModel model = new AuthViewModel(backend);
        Credentials credentials = new Credentials();
        model.signOut(credentials);
        long next = model.beginCredentialRequest();
        assertTrue(next > 0);
        assertEquals(1, credentials.cancellations);
        credentials.clear.onComplete(false);
        assertEquals(AuthViewModel.Phase.CHOOSING_ACCOUNT, model.current().phase);
        assertEquals("", model.current().message);
        model.credentialReceived(next, "test-token");
        backend.complete();
        assertEquals(USER.uid, model.current().user.uid);
    }

    @Test
    public void lateProviderCompletionCannotEraseTheNextSignedInAccount() {
        Backend backend = new Backend();
        backend.user = USER;
        AuthViewModel model = new AuthViewModel(backend);
        Credentials credentials = new Credentials();
        model.signOut(credentials);
        long next = model.beginCredentialRequest();
        model.credentialReceived(next, "test-token");
        backend.complete();
        credentials.clear.onComplete(true);
        credentials.clear.onComplete(false);
        assertEquals(USER.uid, model.current().user.uid);
        assertEquals("", model.current().message);
        assertFalse(model.current().busy());
    }

    @Test
    public void throwingProviderCleanupDoesNotLockLogin() {
        Backend backend = new Backend();
        backend.user = USER;
        AuthViewModel model = new AuthViewModel(backend);
        Credentials credentials = new Credentials();
        credentials.clearThrows = true;
        model.signOut(credentials);
        assertNull(model.current().user);
        assertFalse(model.current().busy());
        assertTrue(model.beginCredentialRequest() > 0);
    }

    @Test
    public void failedFirebaseSignOutKeepsAccountUsableAndRetryable() {
        Backend backend = new Backend();
        backend.user = USER;
        backend.signOutThrows = true;
        AuthViewModel model = new AuthViewModel(backend);
        Credentials credentials = new Credentials();
        model.signOut(credentials);
        assertEquals(USER.uid, model.current().user.uid);
        assertFalse(model.current().busy());
        assertEquals(0, credentials.clears);
        backend.signOutThrows = false;
        model.signOut(credentials);
        assertNull(model.current().user);
        assertFalse(model.current().busy());
        assertEquals(1, credentials.clears);
    }

    @Test
    public void clearedViewModelCancelsOutstandingProviderCleanup() {
        Backend backend = new Backend();
        backend.user = USER;
        AuthViewModel model = new AuthViewModel(backend);
        Credentials credentials = new Credentials();
        model.signOut(credentials);
        model.onCleared();
        assertEquals(1, credentials.cancellations);
        credentials.clear.onComplete(false);
        assertNull(model.current().user);
        assertFalse(model.current().busy());
        assertEquals("로그아웃했어요.", model.current().message);
        assertEquals(-1, model.beginCredentialRequest());
    }

    @Test
    public void cachedFirebaseProfileIsAvailableWithoutAnotherPicker() {
        Backend backend = new Backend();
        backend.user = USER;
        AuthViewModel model = new AuthViewModel(backend);
        assertEquals(USER.uid, model.current().user.uid);
        assertEquals(-1, model.beginCredentialRequest());
        assertEquals(0, backend.signIns);
    }

    @Test
    public void clearedViewModelIgnoresLateAuthenticationCallback() {
        Backend backend = new Backend();
        AuthViewModel model = new AuthViewModel(backend);
        long request = model.beginCredentialRequest();
        model.credentialReceived(request, "test-token");
        model.onCleared();
        backend.complete();
        assertNull(model.current().user);
        assertEquals(-1, model.beginCredentialRequest());
        assertNull(backend.observer);
    }
}
