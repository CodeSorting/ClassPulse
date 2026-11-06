package com.example.classpulse.auth;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.graphics.Bitmap;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.classpulse.MainActivity;
import com.example.classpulse.R;
import com.example.classpulse.data.LocalDatabaseScope;
import com.example.classpulse.ui.AppViewModel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GoogleLoginInstrumentedTest {
    private ActivityScenario<MainActivity> scenario;
    private AuthTestEnvironment auth;
    private LocalDatabaseScope database;
    private Context context;
    private SharedPreferences prefs;
    private String previousRole;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences("local_demo", 0);
        previousRole = prefs.getString("role", null);
        prefs.edit().putString("role", "NONE").commit();
        database = new LocalDatabaseScope(context);
        main(() -> auth = AuthTestEnvironment.signedOut());
    }

    @After
    public void tearDown() {
        try {
            if (scenario != null) scenario.close();
        } finally {
            try {
                main(() -> auth.close());
            } finally {
                try {
                    database.close();
                } finally {
                    SharedPreferences.Editor edit = prefs.edit();
                    if (previousRole == null) edit.remove("role");
                    else edit.putString("role", previousRole);
                    edit.commit();
                }
            }
        }
    }

    private void main(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private void launch() {
        scenario = ActivityScenario.launch(MainActivity.class);
        idle();
    }

    private void idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void tap(String text) {
        if ("Google 계정 로그아웃".equals(text)) onView(withText(text)).perform(scrollTo(), click());
        else onView(withText(text)).perform(click());
        idle();
    }

    private void profile() {
        onView(withId(R.id.nav_settings)).perform(click());
        idle();
        checkRoute("account");
    }

    private void tabs() {
        for (int id : new int[] {R.id.nav_home, R.id.nav_records, R.id.nav_settings})
            onView(withId(id)).check(matches(isDisplayed()));
    }

    private void token() {
        main(() -> auth.credentials.token());
        idle();
    }

    private void succeed(String uid, String email) {
        main(() -> auth.backend.succeed(uid, "테스트 사용자", email));
        idle();
    }

    private void checkRoute(String route) {
        scenario.onActivity(activity -> {
            activity.getSupportFragmentManager().executePendingTransactions();
            assertEquals(
                    route,
                    activity.getSupportFragmentManager().findFragmentById(R.id.main).getTag());
        });
    }

    private void capture(String name) throws Exception {
        Bitmap bitmap =
                InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File dir = new File(context.getExternalFilesDir(null), "verification");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally {
            bitmap.recycle();
        }
    }

    @Test
    public void loginCancellationAllowsRetryAndSuccessShowsRealAccountScreen() throws Exception {
        launch();
        tap("Google로 로그인");
        checkRoute("account");
        assertEquals(1, auth.credentials.requests);
        main(() -> auth.credentials.fail(AuthFailure.CANCELLED));
        idle();
        onView(withText(AuthFailure.CANCELLED.message)).check(matches(isDisplayed()));
        tap("Google로 로그인");
        assertEquals(2, auth.credentials.requests);
        token();
        onView(withText("로그인 확인 중…")).check(matches(isDisplayed()));
        succeed("firebase-user-a", "a@example.test");
        checkRoute("online_home");
        tabs();
        profile();
        onView(withText("a@example.test")).check(matches(isDisplayed()));
        scenario.onActivity(activity -> {
            AppViewModel demo = new ViewModelProvider(activity).get(AppViewModel.class);
            assertEquals("NONE", demo.role());
            assertEquals("", demo.userId());
            assertEquals(0, activity.getSupportFragmentManager().getBackStackEntryCount());
        });
        onView(withText("시연 수업 참여 코드: 4821")).check(doesNotExist());
        capture("google-account-test-session");
    }

    @Test
    public void stalePickerResponseAfterRecreationDoesNotSignIn() {
        launch();
        tap("Google로 로그인");
        GoogleCredentials.Callback old = auth.credentials.pending;
        scenario.recreate();
        idle();
        main(() -> old.onToken("late-test-token"));
        idle();
        assertEquals(0, auth.backend.signIns);
        onView(withText("Google로 로그인")).check(matches(isEnabled()));
        tap("Google로 로그인");
        token();
        succeed("firebase-user-b", "b@example.test");
        checkRoute("online_home");
        tabs();
        profile();
        onView(withText("b@example.test")).check(matches(isDisplayed()));
    }

    @Test
    public void firebaseExchangeSurvivesActivityRecreation() {
        launch();
        tap("Google로 로그인");
        token();
        scenario.recreate();
        idle();
        onView(withText("로그인 확인 중…")).check(matches(isDisplayed()));
        succeed("firebase-user-c", "c@example.test");
        checkRoute("online_home");
        profile();
        onView(withText("c@example.test")).check(matches(isDisplayed()));
        assertEquals(1, auth.credentials.requests);
        assertEquals(1, auth.backend.signIns);
    }

    @Test
    public void networkFailureIsRetryableAndDoesNotInventAnAccount() {
        launch();
        tap("Google로 로그인");
        token();
        main(() -> auth.backend.fail(AuthFailure.NETWORK));
        idle();
        onView(withText(AuthFailure.NETWORK.message)).check(matches(isDisplayed()));
        assertNull(auth.backend.user);
        tap("Google로 로그인");
        assertEquals(2, auth.credentials.requests);
    }

    @Test
    public void signOutClearsProviderAndRestoresSignedOutStart() {
        main(() ->
                auth.backend.user =
                        new AuthIdentity("firebase-user-a", "테스트 사용자", "a@example.test"));
        launch();
        checkRoute("online_home");
        profile();
        tap("Google 계정 로그아웃");
        tap("로그아웃");
        onView(withText("로그아웃했어요.")).check(matches(isDisplayed()));
        onView(withText("a@example.test")).check(doesNotExist());
        assertNull(auth.backend.user);
        assertEquals(1, auth.credentials.clears);
        tap("시작 화면으로");
        checkRoute("welcome");
        scenario.recreate();
        idle();
        checkRoute("welcome");
    }

    @Test
    public void missingClearCallbackAllowsWelcomeAndLoginAfterRecreation() {
        main(() -> {
            auth.backend.user = new AuthIdentity("firebase-user-a", "테스트 사용자", "a@example.test");
            auth.credentials.deferClear = true;
        });
        launch();
        profile();
        tap("Google 계정 로그아웃");
        tap("로그아웃");
        assertNull(auth.backend.user);
        onView(withText("로그아웃 중…")).check(doesNotExist());
        onView(withText("Google로 로그인")).check(matches(isEnabled()));
        onView(withText("시작 화면으로")).check(matches(isEnabled()));
        scenario.recreate();
        idle();
        onView(withText("로그아웃 중…")).check(doesNotExist());
        tap("시작 화면으로");
        checkRoute("welcome");
        tap("Google로 로그인");
        checkRoute("account");
        assertEquals(1, auth.credentials.requests);
        assertEquals(1, auth.credentials.clearCancellations);
        main(() -> auth.credentials.pendingClear.onComplete(false));
        idle();
        onView(withText("Google 계정 선택 중…")).check(matches(isDisplayed()));
        token();
        succeed("firebase-user-b", "b@example.test");
        checkRoute("online_home");
        profile();
        onView(withText("b@example.test")).check(matches(isDisplayed()));
        onView(withText("a@example.test")).check(doesNotExist());
        main(() -> auth.credentials.pendingClear.onComplete(true));
        idle();
        onView(withText("b@example.test")).check(matches(isDisplayed()));
    }

    @Test
    public void reopeningAfterPendingLogoutDoesNotRestoreSigningOutState() {
        main(() -> {
            auth.backend.user = new AuthIdentity("firebase-user-a", "테스트 사용자", "a@example.test");
            auth.credentials.deferClear = true;
        });
        launch();
        profile();
        tap("Google 계정 로그아웃");
        tap("로그아웃");
        GoogleCredentials.ClearCallback old = auth.credentials.pendingClear;
        scenario.close();
        scenario = null;
        launch();
        checkRoute("welcome");
        tap("Google로 로그인");
        main(() -> old.onComplete(false));
        idle();
        onView(withText("Google 계정 선택 중…")).check(matches(isDisplayed()));
        token();
        succeed("firebase-user-b", "b@example.test");
        checkRoute("online_home");
        profile();
        onView(withText("b@example.test")).check(matches(isDisplayed()));
    }

    @Test
    public void cachedAccountRestoresWithoutPickerAndCannotOpenDemoRoutesImplicitly() {
        main(() ->
                auth.backend.user =
                        new AuthIdentity("firebase-user-a", "테스트 사용자", "a@example.test"));
        launch();
        checkRoute("online_home");
        tabs();
        scenario.close();
        scenario = null;
        launch();
        checkRoute("online_home");
        assertEquals(0, auth.credentials.requests);
        scenario.onActivity(activity -> activity.navigate("home"));
        idle();
        checkRoute("account");
        onView(withText("a@example.test")).check(matches(isDisplayed()));
    }

    @Test
    public void incomingInviteSurvivesGoogleLoginAndRecreation() {
        String link =
                "classpulse://join/invite/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        Intent intent =
                new Intent(context, MainActivity.class)
                        .setData(Uri.parse(link))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        scenario = ActivityScenario.launch(intent);
        idle();
        tap("Google로 로그인");
        token();
        succeed("firebase-user-a", "a@example.test");
        scenario.recreate();
        idle();
        scenario.onActivity(activity ->
                assertEquals(
                        link,
                        new ViewModelProvider(activity).get(AppViewModel.class).pendingInvite()));
        onView(withText(link)).check(matches(isDisplayed()));
        checkRoute("online_join");
        androidx.test.espresso.Espresso.pressBack();
        idle();
        checkRoute("online_home");
        tabs();
    }

    @Test
    public void newInviteForSignedInAccountKeepsOneAccountRoot() {
        main(() ->
                auth.backend.user =
                        new AuthIdentity("firebase-user-a", "테스트 사용자", "a@example.test"));
        launch();
        String link =
                "classpulse://join/invite/abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        scenario.onActivity(activity -> {
            Intent launchIntent = activity.getIntent();
            try {
                InstrumentationRegistry.getInstrumentation()
                        .callActivityOnNewIntent(
                                activity, new Intent(launchIntent).setData(Uri.parse(link)));
                assertEquals(link, activity.getIntent().getDataString());
                assertEquals(
                        link,
                        new ViewModelProvider(activity).get(AppViewModel.class).pendingInvite());
            } finally {
                // ActivityScenario 1.7.0 matches lifecycle events by the original Intent data.
                // Restore only its tracking metadata after verifying the real onNewIntent result.
                activity.setIntent(launchIntent);
            }
        });
        idle();
        checkRoute("online_join");
        scenario.onActivity(activity -> {
            assertEquals(
                    link, new ViewModelProvider(activity).get(AppViewModel.class).pendingInvite());
            assertEquals(1, activity.getSupportFragmentManager().getBackStackEntryCount());
        });
        onView(withText(link)).check(matches(isDisplayed()));
        assertEquals(0, auth.credentials.requests);
    }

    @Test
    public void explicitDemoEntryCanReturnToCachedGoogleAccount() {
        main(() ->
                auth.backend.user =
                        new AuthIdentity("firebase-user-a", "테스트 사용자", "a@example.test"));
        launch();
        profile();
        onView(withText("학생 시연으로 살펴보기")).perform(scrollTo(), click());
        idle();
        checkRoute("home");
        onView(withId(R.id.nav_settings)).perform(click());
        idle();
        tap("내 Google 계정");
        checkRoute("account");
        onView(withText("a@example.test")).check(matches(isDisplayed()));
        assertEquals(0, auth.credentials.requests);
        scenario.onActivity(activity ->
                assertEquals("", new ViewModelProvider(activity).get(AppViewModel.class).userId()));
    }

    @Test
    public void threeOnlineTabsRemainSelectedAndDoNotBuildAStack() throws Exception {
        main(() ->
                auth.backend.user =
                        new AuthIdentity("firebase-user-a", "테스트 사용자", "a@example.test"));
        launch();
        tabs();
        onView(withId(R.id.nav_home)).check(matches(isSelected()));
        for (int i = 0; i < 3; i++) {
            onView(withId(R.id.nav_records)).perform(click());
            idle();
            checkRoute("online_records");
            tabs();
            onView(withId(R.id.nav_records)).check(matches(isSelected()));
            scenario.recreate();
            idle();
            checkRoute("online_records");
            onView(withId(R.id.nav_records)).check(matches(isSelected()));
            profile();
            tabs();
            onView(withId(R.id.nav_settings)).check(matches(isSelected()));
            onView(withId(R.id.nav_home)).perform(click());
            idle();
            checkRoute("online_home");
        }
        scenario.onActivity(activity ->
                assertEquals(0, activity.getSupportFragmentManager().getBackStackEntryCount()));
        capture("online-home-three-tabs");
        profile();
        capture("online-profile-three-tabs");
    }
}
