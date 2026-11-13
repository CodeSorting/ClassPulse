package com.example.classpulse.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.classpulse.MainActivity;
import com.example.classpulse.R;
import com.example.classpulse.data.LocalDatabaseScope;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

/** System Back uses actual screen history; exit confirmation is restricted to the root. */
@RunWith(AndroidJUnit4.class)
public class BackNavigationInstrumentedTest {
    private ActivityScenario<MainActivity> scenario;
    private AuthTestEnvironment auth;
    private LocalDatabaseScope database;
    private SharedPreferences prefs;
    private String previousRole;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
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

    private void idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void launch(boolean signedIn) {
        if (signedIn) main(() -> auth.backend.user =
                new AuthIdentity("back-test-user", "뒤로가기 테스트", "back@example.test"));
        scenario = ActivityScenario.launch(MainActivity.class);
        idle();
    }

    private void route(String route) {
        scenario.onActivity(activity -> {
            activity.getSupportFragmentManager().executePendingTransactions();
            assertFalse(activity.isFinishing());
            assertEquals(route,
                    activity.getSupportFragmentManager().findFragmentById(R.id.main).getTag());
        });
    }

    private void tab(int viewId, String route) {
        onView(withId(viewId)).perform(click());
        idle();
        route(route);
    }

    @Test
    public void rootBackRequiresYesAndNoKeepsTheActivityOpen() {
        launch(false);
        route("welcome");
        pressBack();
        onView(withText("종료하시겠습니까?")).check(matches(isDisplayed()));
        onView(withText("아니요")).perform(click());
        route("welcome");
        pressBack();
        onView(withText("예")).perform(click());
        idle();
        long destroyDeadline = SystemClock.uptimeMillis() + 5000;
        while (scenario.getState() != Lifecycle.State.DESTROYED
                && SystemClock.uptimeMillis() < destroyDeadline) {
            SystemClock.sleep(50);
        }
        assertEquals("Confirming exit must finish the Activity", Lifecycle.State.DESTROYED,
                scenario.getState());
    }

    @Test
    public void backFromCancelledLoginReturnsToTheWelcomeScreen() {
        launch(false);
        onView(withText("Google로 로그인")).perform(click());
        main(() -> auth.credentials.fail(AuthFailure.CANCELLED));
        idle();
        route("account");
        pressBack();
        route("welcome");
        onView(withText("종료하시겠습니까?")).check(doesNotExist());
    }

    @Test
    public void tabHistorySurvivesRecreationAndBackReturnsThroughEachScreen() {
        launch(true);
        route("online_home");
        tab(R.id.nav_records, "online_records");
        tab(R.id.nav_settings, "account");
        scenario.recreate();
        idle();
        route("account");
        pressBack();
        route("online_records");
        pressBack();
        route("online_home");
        pressBack();
        onView(withText("종료하시겠습니까?")).check(matches(isDisplayed()));
        onView(withText("아니요")).perform(click());
        route("online_home");
    }

    @Test
    public void tappingTheCurrentTabDoesNotCreateAnotherHistoryEntry() {
        launch(true);
        tab(R.id.nav_records, "online_records");
        tab(R.id.nav_records, "online_records");
        scenario.onActivity(activity -> assertEquals(1,
                activity.getSupportFragmentManager().getBackStackEntryCount()));
        pressBack();
        route("online_home");
    }

    @Test
    public void accountActionsAlsoKeepTheirPreviousScreen() {
        launch(true);
        tab(R.id.nav_settings, "account");
        onView(withText("코드·링크로 온라인 수업 참여")).perform(click());
        idle();
        route("online_join");
        pressBack();
        route("account");
        pressBack();
        route("online_home");
    }

    @Test
    public void logoutClearsEveryPreviousAccountScreenBeforeBack() {
        launch(true);
        tab(R.id.nav_records, "online_records");
        tab(R.id.nav_settings, "account");
        scenario.onActivity(MainActivity::signOutGoogle);
        idle();
        route("account");
        scenario.onActivity(activity -> assertEquals(0,
                activity.getSupportFragmentManager().getBackStackEntryCount()));
        onView(withText("back@example.test")).check(doesNotExist());
        pressBack();
        onView(withText("종료하시겠습니까?")).check(matches(isDisplayed()));
        onView(withText("아니요")).perform(click());
        route("account");
    }
}
