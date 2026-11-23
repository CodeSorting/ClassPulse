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
        int[] ids = {R.id.student_question_input, R.id.student_reactions_card, R.id.student_question_send};
        scenario.onActivity(a -> { for (int i = 0; i < ids.length; i++) a.findViewById(ids[i]).getLocationOnScreen(before[i]); });
        onView(withId(R.id.student_question_topic)).perform(click());
        capture("expanded");
        scenario.onActivity(a -> {
            for (int i = 0; i < ids.length; i++) {
                int[] after = new int[2]; a.findViewById(ids[i]).getLocationOnScreen(after);
                assertArrayEquals("Picker must not move surrounding content", before[i], after);
            }
        });
        onView(withContentDescription("질문 대주제 1, 신뢰적 데이터 전송")).perform(click());
        onView(withId(R.id.student_question_input)).perform(click(), replaceText("재전송은 언제 발생하나요?"));
        scenario.onActivity(a -> {
            EditText input = a.findViewById(R.id.student_question_input);
            input.requestFocus();
            ((android.view.inputmethod.InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
        await(() -> {
            AtomicReference<Boolean> visible = new AtomicReference<>(false);
            scenario.onActivity(a -> {
                androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets(a.findViewById(R.id.student_question_input));
                visible.set(insets != null && insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()));
            });
            return visible.get();
        });
        capture("keyboard");
        onView(withId(R.id.student_question_send)).check(matches(isDisplayed())).perform(click());
        await(() -> snapshot().questions.stream().anyMatch(q -> "SUBMITTED".equals(q.status)));
        Models.Question submitted = snapshot().questions.stream().filter(q -> "SUBMITTED".equals(q.status)).findFirst().get();
        assertEquals(outline.get(0).id, submitted.topicId);
        assertEquals("재전송은 언제 발생하나요?", submitted.text);
        onView(withId(R.id.student_question_input)).check(matches(withText("")));
        assertEquals(1, snapshot().questions.stream().filter(q -> "SUBMITTED".equals(q.status)).count());
    }

    @Test public void draftSurvivesProgressAndRotationThenMovesToRecordsOnEnd() throws Exception {
        onView(withId(R.id.student_question_input)).perform(replaceText("작성 중인 질문"), closeSoftKeyboard());
        success((LessonRepository.Callback<Void> cb) -> repo.completeTopic(sid, cb));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withId(R.id.student_question_input)).check(matches(withText("작성 중인 질문")));
        onView(withText("2.2 Selective Repeat")).check(matches(isDisplayed()));
        scenario.recreate();
        onView(withId(R.id.student_question_input)).check(matches(withText("작성 중인 질문")));
        onView(withId(R.id.student_question_topic)).check(matches(withText("2")));
        success((LessonRepository.Callback<Void> cb) -> repo.endLesson(sid, cb));
        await(() -> snapshot().questions.stream().anyMatch(q -> "작성 중인 질문".equals(q.text) && "DRAFT".equals(q.status)));
        await(() -> {
            AtomicReference<Boolean> ended = new AtomicReference<>(false);
            scenario.onActivity(a -> ended.set(a.findViewById(R.id.student_outline) == null));
            return ended.get();
        });
        onView(withId(R.id.student_outline)).check(doesNotExist());
        onView(withText("수업이 끝났어요")).check(matches(isDisplayed()));
    }

    @Test public void fiveReactionsPersistAndUnderstoodCanBeCanceled() throws Exception {
        onView(withId(R.id.student_reaction_understood)).perform(click());
        await(() -> snapshot().reactions.size() == 1);
        assertEquals("UNDERSTOOD", snapshot().reactions.get(0).type);
        onView(withId(R.id.student_reaction_understood)).perform(click());
        await(() -> snapshot().reactions.isEmpty());
        for (int id : new int[]{R.id.student_reaction_understanding, R.id.student_reaction_speed, R.id.student_reaction_term, R.id.student_reaction_calculation})
            onView(withId(id)).perform(click());
        await(() -> snapshot().reactions.size() == 4);
        assertTrue(snapshot().reactions.stream().noneMatch(r -> "UNDERSTOOD".equals(r.type)));
        onView(withId(R.id.student_question_topic)).perform(click());
        androidx.test.espresso.Espresso.pressBack();
        onView(withId(R.id.student_question_input)).check(matches(isDisplayed()));
        onView(withContentDescription("질문 대주제 1, 신뢰적 데이터 전송")).check(doesNotExist());
    }

    private List<String> outlineState() {
        AtomicReference<List<String>> result = new AtomicReference<>();
        scenario.onActivity(a -> {
            android.widget.LinearLayout rows = a.findViewById(R.id.student_outline);
            List<String> state = new ArrayList<>();
            if (rows != null) for (int i = 0; i < rows.getChildCount(); i++) {
                View row = rows.getChildAt(i);
                android.widget.TextView title = row.findViewById(R.id.student_topic_title);
                if (title == null) continue;
                state.add(title.getText() + "|" + (row.findViewById(R.id.student_topic_done).getVisibility() == View.VISIBLE)
                        + "|" + (row.findViewById(R.id.student_topic_state).getVisibility() == View.VISIBLE));
            }
            result.set(state);
        });
        return result.get();
    }

    @Test public void teacherSharesStudentOutlineAndReactionIcons() throws Exception {
        List<String> student = outlineState();
        assertEquals(9, student.size());
        assertTrue(student.contains("1. 신뢰적 데이터 전송|true|false"));
        assertTrue(student.contains("2.1 Go-Back-N|false|true"));
        capture("shared-outline-student");
        scenario.onActivity(a -> {
            a.enterDemo("TEACHER");
            a.navigate("live", ScreenContext.empty().forLesson(sid));
            a.getSupportFragmentManager().executePendingTransactions();
        });
        await(() -> student.equals(outlineState()));
        onView(withText("오늘의 수업 목차")).check(matches(isDisplayed()));
        onView(withId(R.id.student_course_title)).check(matches(withText("컴퓨터 네트워크")));
        onView(withId(R.id.student_course_title)).perform(scrollTo());
        capture("shared-outline-teacher");
        scenario.recreate();
        await(() -> student.equals(outlineState()));

        onView(withId(R.id.reaction_pager)).perform(scrollTo());
        for (String label : new String[]{"이해됐어요", "이해가 안 돼요", "너무 빨라요", "용어가 어려워요", "계산을 놓쳤어요"}) {
            onView(org.hamcrest.Matchers.allOf(withId(R.id.icon), hasSibling(withText(label)), isDisplayed()))
                    .check((view, error) -> {
                        if (error != null) throw error;
                        assertNotNull("Reaction icon must be present", ((android.widget.ImageView) view).getDrawable());
                    });
        }
        capture("shared-teacher-reaction-icons");
        onView(withContentDescription("1.1 오류 검출, 완료, 눌러서 재설명")).perform(scrollTo(), click());
        await(() -> outline.get(1).id.equals(snapshot().currentTopicId));
        assertTrue(snapshot().topic(outline.get(1).id).completed);
        await(() -> outlineState().contains("1.1 오류 검출|true|true"));
        onView(withText("원래 진행 주제로 돌아가기")).perform(click());
        await(() -> outline.get(4).id.equals(snapshot().currentTopicId));
        List<String> teacher = outlineState();
        scenario.onActivity(a -> {
            a.enterDemo("STUDENT");
            a.navigate("live", ScreenContext.empty().forLesson(sid));
            a.getSupportFragmentManager().executePendingTransactions();
        });
        await(() -> teacher.equals(outlineState()));
        onView(withContentDescription("1.1 오류 검출, 완료, 눌러서 재설명")).check(doesNotExist());
        onView(withId(R.id.student_reaction_understood)).check(matches(isDisplayed()));
    }

}
