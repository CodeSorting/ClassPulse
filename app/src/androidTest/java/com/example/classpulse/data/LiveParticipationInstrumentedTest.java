package com.example.classpulse.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.EditText;
import androidx.lifecycle.ViewModelProvider;
import androidx.room.Room;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.classpulse.MainActivity;
import com.example.classpulse.R;
import com.example.classpulse.auth.AuthTestEnvironment;
import com.example.classpulse.domain.Models;
import com.example.classpulse.ui.AppViewModel;
import com.example.classpulse.ui.LiveParticipationView;
import com.example.classpulse.ui.ScreenContext;
import java.io.File;
import java.io.FileOutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;

/** Real student screen with isolated Room data; also captures design QA evidence. */
@RunWith(AndroidJUnit4.class)
public class LiveParticipationInstrumentedTest {
    private Context context;
    private LessonRepository repo, previous;
    private AuthTestEnvironment auth;
    private ActivityScenario<MainActivity> scenario;
    private String previousRole, sid;
    private final List<Models.Topic> outline = new ArrayList<>();

    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private <T> T success(Consumer<LessonRepository.Callback<T>> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        action.accept(new LessonRepository.Callback<T>() {
            public void onSuccess(T value) { result.complete(value); }
            public void onError(String message) { result.completeExceptionally(new AssertionError(message)); }
        });
        return result.get(15, TimeUnit.SECONDS);
    }
    private void await(Callable<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < end) { if (condition.call()) return; Thread.sleep(80); }
        fail("Live participation screen did not reach expected state");
    }
    private Models.Lesson snapshot() { return repo.snapshotForTests().stream().filter(l -> l.id.equals(sid)).findFirst().get(); }
    private void topic(String title, String parent) {
        Models.Topic t = new Models.Topic(); t.id = UUID.randomUUID().toString(); t.title = title;
        t.parentTopicId = parent; t.position = outline.size(); outline.add(t);
    }
    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        previousRole = context.getSharedPreferences("local_demo", 0).getString("role", null);
        main(() -> {
            auth = AuthTestEnvironment.signedOut();
            repo = LessonRepository.forTests(Room.inMemoryDatabaseBuilder(context, AppDatabase.class).build());
            previous = LessonRepository.swapForTests(repo);
        });
        topic("신뢰적 데이터 전송", null);
        topic("오류 검출", outline.get(0).id); topic("재전송과 타이머", outline.get(0).id);
        topic("파이프라인 프로토콜", null);
        topic("Go-Back-N", outline.get(3).id); topic("Selective Repeat", outline.get(3).id);
        topic("TCP 혼잡 제어", null);
        topic("혼잡 윈도", outline.get(6).id); topic("혼잡 제어 알고리즘", outline.get(6).id);
        sid = success(cb -> repo.createLessonWithOutline("컴퓨터 네트워크", "", outline, cb));
        success((LessonRepository.Callback<String> cb) -> repo.joinById(Models.STUDENT_ID, sid, cb));
        success((LessonRepository.Callback<Void> cb) -> repo.startLesson(sid, cb));
        success((LessonRepository.Callback<Void> cb) -> repo.completeTopic(sid, cb));
        success((LessonRepository.Callback<Void> cb) -> repo.completeTopic(sid, cb));
        context.getSharedPreferences("local_demo", 0).edit().putString("role", "STUDENT").commit();
        scenario = ActivityScenario.launch(MainActivity.class);
        await(() -> {
            AtomicReference<Boolean> found = new AtomicReference<>(false);
            scenario.onActivity(a -> found.set(new ViewModelProvider(a).get(AppViewModel.class).lesson(sid) != null));
            return found.get();
        });
        scenario.onActivity(a -> {
            a.navigate("live", ScreenContext.empty().forLesson(sid));
            a.getSupportFragmentManager().executePendingTransactions();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    @After public void tearDown() {
        if (scenario != null) scenario.close();
        main(() -> { auth.close(); LessonRepository.swapForTests(previous); });
        repo.closeForTests();
        android.content.SharedPreferences.Editor edit = context.getSharedPreferences("local_demo", 0).edit();
        if (previousRole == null) edit.remove("role"); else edit.putString("role", previousRole);
        edit.commit();
        android.content.SharedPreferences drafts = context.getSharedPreferences("live_question_composer", 0);
        android.content.SharedPreferences.Editor clean = drafts.edit();
        for (String key : drafts.getAll().keySet()) if (key.startsWith("local/" + Models.STUDENT_ID + "/" + sid + "/")) clean.remove(key);
        clean.commit();
    }
    private void capture(String name) throws Exception {
        Thread.sleep(300);
        File directory = new File(context.getExternalFilesDir(null), "live-ui-qa"); directory.mkdirs();
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        image.recycle();
    }

    @Test public void pickerOverlaysWithoutMovingAnythingAndSendsToChosenMajor() throws Exception {
        capture("default");
        int[][] before = new int[3][2];
        int[] ids = {R.id.live_question_input, R.id.live_reactions, R.id.live_send};
        scenario.onActivity(a -> { for (int i = 0; i < ids.length; i++) a.findViewById(ids[i]).getLocationOnScreen(before[i]); });
        onView(withId(R.id.live_topic_button)).perform(click());
        capture("expanded");
        scenario.onActivity(a -> {
            assertTrue(((LiveParticipationView)a.findViewById(R.id.live_participation)).isExpanded());
            for (int i = 0; i < ids.length; i++) {
                int[] after = new int[2]; a.findViewById(ids[i]).getLocationOnScreen(after);
                assertArrayEquals("Picker must not move surrounding content", before[i], after);
            }
        });
        onView(withContentDescription("대주제 1, 신뢰적 데이터 전송")).perform(click());
        onView(withId(R.id.live_question_input)).perform(click(), replaceText("재전송은 언제 발생하나요?"));
        scenario.onActivity(a -> {
            EditText input = a.findViewById(R.id.live_question_input);
            input.requestFocus();
            ((android.view.inputmethod.InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
        await(() -> {
            AtomicReference<Boolean> visible = new AtomicReference<>(false);
            scenario.onActivity(a -> {
                androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets(a.findViewById(R.id.live_question_input));
                visible.set(insets != null && insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()));
            });
            return visible.get();
        });
        capture("keyboard");
        onView(withId(R.id.live_send)).check(matches(isDisplayed())).perform(click());
        await(() -> snapshot().questions.stream().anyMatch(q -> "SUBMITTED".equals(q.status)));
        Models.Question submitted = snapshot().questions.stream().filter(q -> "SUBMITTED".equals(q.status)).findFirst().get();
        assertEquals(outline.get(0).id, submitted.topicId);
        assertEquals("재전송은 언제 발생하나요?", submitted.text);
        onView(withId(R.id.live_question_input)).check(matches(withText("")));
        assertEquals(1, snapshot().questions.stream().filter(q -> "SUBMITTED".equals(q.status)).count());
    }

    @Test public void draftSurvivesProgressAndRotationThenMovesToRecordsOnEnd() throws Exception {
        onView(withId(R.id.live_question_input)).perform(replaceText("작성 중인 질문"), closeSoftKeyboard());
        success((LessonRepository.Callback<Void> cb) -> repo.completeTopic(sid, cb));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withId(R.id.live_question_input)).check(matches(withText("작성 중인 질문")));
        onView(withText("2.2 Selective Repeat")).check(matches(isDisplayed()));
        scenario.recreate();
        onView(withId(R.id.live_question_input)).check(matches(withText("작성 중인 질문")));
        onView(withId(R.id.live_topic_button)).check(matches(withText("2")));
        success((LessonRepository.Callback<Void> cb) -> repo.endLesson(sid, cb));
        await(() -> snapshot().questions.stream().anyMatch(q -> "작성 중인 질문".equals(q.text) && "DRAFT".equals(q.status)));
        await(() -> {
            AtomicReference<Boolean> ended = new AtomicReference<>(false);
            scenario.onActivity(a -> ended.set(a.findViewById(R.id.live_participation) == null));
            return ended.get();
        });
        onView(withId(R.id.live_participation)).check(doesNotExist());
        onView(withText("수업이 끝났어요")).check(matches(isDisplayed()));
    }

    @Test public void fourExistingReactionsPersistButUnderstoodRemainsOnlyAButton() throws Exception {
        onView(withId(R.id.live_understood)).perform(click());
        assertTrue(snapshot().reactions.isEmpty());
        for (int id : new int[]{R.id.live_confused, R.id.live_speed, R.id.live_term, R.id.live_calculation})
            onView(withId(id)).perform(click());
        await(() -> snapshot().reactions.size() == 4);
        assertTrue(snapshot().reactions.stream().noneMatch(r -> "UNDERSTOOD".equals(r.type)));
        onView(withId(R.id.live_topic_button)).perform(click());
        androidx.test.espresso.Espresso.pressBack();
        onView(withId(R.id.live_question_input)).check(matches(isDisplayed()));
        scenario.onActivity(a -> assertFalse(((LiveParticipationView)a.findViewById(R.id.live_participation)).isExpanded()));
    }
}
