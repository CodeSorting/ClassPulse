package com.example.classpulse.data;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.room.Room;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.classpulse.MainActivity;
import com.example.classpulse.R;
import com.example.classpulse.domain.Models;
import com.example.classpulse.ui.AppViewModel;
import com.example.classpulse.ui.ScreenContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/** Real Activity/Fragment regressions using an isolated Room DB, never user demo data. */
@RunWith(AndroidJUnit4.class)
public class NavigationInstrumentedTest {
    private Context context;
    private LessonRepository repo, previous;
    private ActivityScenario<MainActivity> scenario;
    private SharedPreferences preferences;
    private String previousRole;
    private com.example.classpulse.auth.AuthTestEnvironment authEnvironment;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = context.getSharedPreferences("local_demo", 0);
        previousRole = preferences.getString("role", null);
        main(() -> authEnvironment = com.example.classpulse.auth.AuthTestEnvironment.signedOut());
        AppDatabase db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).build();
        main(() -> {
            repo = LessonRepository.forTests(db);
            previous = LessonRepository.swapForTests(repo);
        });
    }

    @After
    public void tearDown() {
        if (scenario != null) scenario.close();
        main(() -> authEnvironment.close());
        main(() -> LessonRepository.swapForTests(previous));
        if (repo != null) repo.closeForTests();
        SharedPreferences.Editor edit = preferences.edit();
        if (previousRole == null) edit.remove("role");
        else edit.putString("role", previousRole);
        edit.commit();
    }

    private void main(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private <T> T success(Consumer<LessonRepository.Callback<T>> operation) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        operation.accept(
                new LessonRepository.Callback<T>() {
                    @Override
                    public void onSuccess(T value) {
                        result.set(value);
                        done.countDown();
                    }

                    @Override
                    public void onError(String message) {
                        error.set(message);
                        done.countDown();
                    }
                });
        await(done, "Repository callback");
        assertNull(error.get(), error.get());
        return result.get();
    }

    private void ok(Consumer<LessonRepository.Callback<Void>> operation) {
        success(operation);
    }

    private void await(CountDownLatch latch, String what) {
        try {
            assertTrue(what + " timed out", latch.await(15, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private String create(String title) {
        return success(
                cb -> repo.createLesson(title, "화면 회귀 테스트", Arrays.asList("첫 대주제", "둘째 대주제"), cb));
    }

    private void ended(String id) {
        ok(cb -> repo.startLesson(id, cb));
        ok(cb -> repo.endLesson(id, cb));
    }

    private void join(String id) {
        success((LessonRepository.Callback<String> cb) -> repo.joinById(Models.STUDENT_ID, id, cb));
    }

    private Models.Lesson snapshot(String id) {
        for (Models.Lesson l : repo.snapshotForTests()) if (l.id.equals(id)) return l;
        throw new AssertionError("Missing lesson " + id);
    }

    private String question(String id, String text) {
        return success(cb ->
                repo.submitQuestion(
                        null, id, snapshot(id).topics.get(0).id, Models.STUDENT_ID, text, cb));
    }

    private String quiz(String id, String title) {
        return success(cb ->
                repo.saveQuiz(
                        null,
                        id,
                        snapshot(id).topics.get(0).id,
                        title,
                        "문항: " + title,
                        Arrays.asList("첫 보기", "둘째 보기"),
                        0,
                        "설명: " + title,
                        true,
                        cb));
    }

    private void launch(String role) {
        preferences.edit().putString("role", role).commit();
        scenario = ActivityScenario.launch(MainActivity.class);
        awaitLessons(values -> !values.isEmpty());
    }

    private void awaitLessons(Predicate<List<Models.Lesson>> predicate) {
        CountDownLatch done = new CountDownLatch(1);
        Observer<List<Models.Lesson>> observer =
                values -> {
                    if (values != null && predicate.test(values)) done.countDown();
                };
        main(() -> repo.observeLessons().observeForever(observer));
        try {
            await(done, "Room UI snapshot");
        } finally {
            main(() -> repo.observeLessons().removeObserver(observer));
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void awaitLesson(String id, Predicate<Models.Lesson> predicate) {
        awaitLessons(values -> {
            for (Models.Lesson l : values) if (l.id.equals(id)) return predicate.test(l);
            return false;
        });
    }

    private void open(String route, ScreenContext screen) {
        scenario.onActivity(activity -> {
            activity.navigate(route, screen);
            activity.getSupportFragmentManager().executePendingTransactions();
        });
        awaitRoute(route);
    }

    private void awaitRoute(String route) {
        CountDownLatch done = new CountDownLatch(1);
        FragmentManager.FragmentLifecycleCallbacks callback =
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentResumed(
                            @NonNull FragmentManager manager, @NonNull Fragment fragment) {
                        if (route.equals(fragment.getTag())) done.countDown();
                    }
                };
        scenario.onActivity(activity -> {
            FragmentManager manager = activity.getSupportFragmentManager();
            manager.registerFragmentLifecycleCallbacks(callback, false);
            Fragment current = manager.findFragmentById(R.id.main);
            if (current != null && route.equals(current.getTag()) && current.isResumed())
                done.countDown();
        });
        try {
            await(done, "Route " + route);
        } finally {
            scenario.onActivity(activity ->
                    activity.getSupportFragmentManager()
                            .unregisterFragmentLifecycleCallbacks(callback));
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private ScreenContext screen(String id) {
        return ScreenContext.empty().forLesson(id);
    }

    private void field(String label, String text) {
        onView(withContentDescription(label))
                .perform(scrollTo(), click(), replaceText(text), closeSoftKeyboard());
    }

    private void tap(String text) {
        onView(allOf(withText(text), isDisplayed())).perform(click());
    }

    private void backTo(String route) {
        pressBack();
        awaitRoute(route);
    }

    private void recreate(String route) {
        scenario.recreate();
        awaitRoute(route);
    }

    private void scrollTap(String text) {
        onView(withText(text)).perform(scrollTo(), click());
    }

    private void switchRole(String label) {
        onView(withId(R.id.nav_settings)).perform(click());
        awaitRoute("settings");
        scrollTap(label);
        awaitRoute("home");
    }

    private void screenshot(String name) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap bitmap =
                InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File directory = new File(context.getExternalFilesDir(null), "verification");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        } catch (IOException error) {
            throw new AssertionError(error);
        } finally {
            bitmap.recycle();
        }
    }

    private void checkContext(String lesson, String question, String quiz) {
        scenario.onActivity(activity -> {
            ScreenContext actual =
                    ScreenContext.from(
                            activity.getSupportFragmentManager()
                                    .findFragmentById(R.id.main)
                                    .getArguments());
            assertEquals(lesson, actual.lessonId);
            assertEquals(question, actual.questionId);
            assertEquals(quiz, actual.quizId);
        });
    }

    @Test
    public void materialDraftStaysWithItsLessonAcrossBackAndRecreation() {
        String a = create("자료 수업 A"), b = create("자료 수업 B");
        ended(a);
        ended(b);
        ok(cb -> repo.saveMaterial(a, "A 공개 원문", "", "", true, cb));
        ok(cb -> repo.saveMaterial(b, "B 공개 원문", "", "", true, cb));
        launch("TEACHER");
        open("material_edit", screen(a));
        field("전체 강의 원문 · 필수", "A만의 편집 내용");
        open("material_edit", screen(b));
        field("전체 강의 원문 · 필수", "B의 저장하지 않은 내용");
        backTo("material_edit");
        checkContext(a, "", "");
        recreate("material_edit");
        onView(withContentDescription("전체 강의 원문 · 필수")).check(matches(withText("A만의 편집 내용")));
        tap("초안 저장");
        awaitLesson(a, l -> l.material.hasDraft && "A만의 편집 내용".equals(l.material.draftOriginal));
        assertEquals("A 공개 원문", snapshot(a).material.original);
        assertEquals("B 공개 원문", snapshot(b).material.original);
        assertFalse(snapshot(b).material.hasDraft);
    }

    @Test
    public void answerDraftStaysWithItsQuestionAcrossAnotherLessonAndRecreation() {
        String a = create("질문 수업 A"), b = create("질문 수업 B");
        join(a);
        join(b);
        String qa = question(a, "A 학생 질문"), qb = question(b, "B 학생 질문");
        launch("TEACHER");
        open("question_detail", screen(a).withQuestion(qa));
        field("답변 작성", "A 질문 답변 초안");
        open("question_detail", screen(b).withQuestion(qb));
        backTo("question_detail");
        recreate("question_detail");
        checkContext(a, qa, "");
        onView(withContentDescription("답변 작성")).check(matches(withText("A 질문 답변 초안")));
        tap("답변 초안 저장");
        awaitLesson(a, l -> "A 질문 답변 초안".equals(l.question(qa).answerDraft));
        assertEquals("", snapshot(b).question(qb).answerDraft);
        assertFalse(snapshot(a).question(qa).published);
    }

    @Test
    public void copiedQuizRemainsNewAfterRecreation() {
        String id = create("퀴즈 복사 수업");
        join(id);
        ended(id);
        String original = quiz(id, "기존 퀴즈");
        ok(cb -> repo.submitAttempt(id, original, Models.STUDENT_ID, 0, cb));
        launch("TEACHER");
        open("quiz_edit", screen(id).withQuiz(original));
        tap("이 내용으로 새 퀴즈 만들기");
        field("퀴즈 제목", "복사 후 수정 퀴즈");
        recreate("quiz_edit");
        onView(withContentDescription("퀴즈 제목")).check(matches(withText("복사 후 수정 퀴즈")));
        tap("초안 저장");
        awaitRoute("quiz_list");
        awaitLesson(id, l -> l.quizzes.size() == 2);
        assertEquals("기존 퀴즈", snapshot(id).quiz(original).title);
        assertTrue(
                snapshot(id).quizzes.stream()
                        .anyMatch(q ->
                                !original.equals(q.id)
                                        && q.hasDraft
                                        && "복사 후 수정 퀴즈".equals(q.draftTitle)));
    }

    @Test
    public void quizChoiceAndResultKeepOriginalQuizAcrossBackAndRecreation() {
        String a = create("퀴즈 수업 A"), b = create("퀴즈 수업 B");
        join(a);
        join(b);
        ended(a);
        ended(b);
        String qa = quiz(a, "A 퀴즈"), qb = quiz(b, "B 퀴즈");
        launch("STUDENT");
        open("quiz_take", screen(a).withQuiz(qa));
        tap("1. 첫 보기");
        open("quiz_take", screen(b).withQuiz(qb));
        backTo("quiz_take");
        recreate("quiz_take");
        checkContext(a, "", qa);
        onView(withText("✓ 1. 첫 보기")).check(matches(isDisplayed()));
        tap("답안 제출하기");
        tap("제출");
        awaitRoute("quiz_result");
        checkContext(a, "", qa);
        assertEquals(1, snapshot(a).attempts.size());
        assertTrue(snapshot(b).attempts.isEmpty());
        backTo("home");
    }

    @Test
    public void codeLookupDoesNotJoinUntilConsentAndBackReturnsHome() {
        String id = create("코드 참여 수업");
        String code = snapshot(id).code;
        launch("STUDENT");
        tap("코드 직접 입력");
        awaitRoute("join");
        field("참여 코드", code);
        tap("수업 확인하기");
        awaitRoute("confirm_join");
        checkContext(id, "", "");
        assertFalse(snapshot(id).isMember(Models.STUDENT_ID));
        recreate("confirm_join");
        tap("이 수업에 참여하기");
        awaitRoute("live");
        awaitLesson(id, l -> l.isMember(Models.STUDENT_ID));
        backTo("home");
        scenario.onActivity(activity ->
                assertEquals(0, activity.getSupportFragmentManager().getBackStackEntryCount()));
    }

    @Test
    public void coldInviteSurvivesRoleChoiceWithoutCodeAndReturnsHome() {
        String id = create("링크 초대 수업");
        preferences.edit().putString("role", "NONE").commit();
        Intent intent =
                new Intent(context, MainActivity.class)
                        .setData(Uri.parse("classpulse://join/" + id))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        scenario = ActivityScenario.launch(intent);
        awaitLessons(values -> !values.isEmpty());
        onView(withText("학생으로 시작")).perform(scrollTo(), click());
        awaitRoute("join");
        recreate("join");
        onView(withContentDescription("수업 초대 링크"))
                .check(matches(withText("classpulse://join/" + id)));
        tap("수업 확인하기");
        awaitRoute("confirm_join");
        assertFalse(snapshot(id).isMember(Models.STUDENT_ID));
        tap("이 수업에 참여하기");
        awaitRoute("live");
        checkContext(id, "", "");
        backTo("home");
    }

    @Test
    public void studentDraftStaysWithOriginalTopicAfterProgressAndRecreation() {
        String id = create("질문 작성 수업");
        join(id);
        ok(cb -> repo.startLesson(id, cb));
        String topic = snapshot(id).currentTopicId;
        launch("STUDENT");
        open("question_edit", screen(id).withTopic(topic));
        field("궁금한 내용", "첫 대주제에서 생긴 질문");
        recreate("question_edit");
        ok(cb -> repo.completeTopic(id, cb));
        awaitLesson(id, l -> !topic.equals(l.currentTopicId));
        onView(withContentDescription("궁금한 내용")).check(matches(withText("첫 대주제에서 생긴 질문")));
        tap("질문 제출하기");
        awaitRoute("question_detail");
        Models.Question submitted =
                snapshot(id).questions.stream()
                        .filter(q -> Models.SUBMITTED.equals(q.status))
                        .findFirst()
                        .orElseThrow(AssertionError::new);
        assertEquals(topic, submitted.topicId);
        assertEquals("첫 대주제에서 생긴 질문", submitted.text);
        backTo("home");
    }

    @Test
    public void topicAnalysisFiltersSubmittedQuestionsAndBlocksStudentAccess() {
        String id = create("분석 수업");
        join(id);
        String second = snapshot(id).topics.get(1).id;
        question(id, "첫 주제의 제출 질문");
        success((LessonRepository.Callback<String> cb) ->
                repo.submitQuestion(null, id, second, Models.STUDENT_ID, "둘째 주제의 제출 질문", cb));
        success((LessonRepository.Callback<String> cb) ->
                repo.saveDraft(null, id, second, Models.STUDENT_ID, "나만 보는 미제출 초안", cb));
        launch("TEACHER");
        open("analysis", screen(id));
        scrollTap("둘째 대주제");
        awaitRoute("questions");
        onView(withText("둘째 주제의 제출 질문")).check(matches(isDisplayed()));
        onView(withText("첫 주제의 제출 질문")).check(doesNotExist());
        onView(withText("나만 보는 미제출 초안")).check(doesNotExist());
        scenario.onActivity(activity -> {
            new ViewModelProvider(activity).get(AppViewModel.class).setRole("STUDENT");
            activity.navigateRoot("home");
        });
        awaitRoute("home");
        open("analysis", screen(id));
        onView(withText("이 수업의 교수자만 반응 분석을 볼 수 있어요.")).check(matches(isDisplayed()));
        onView(withText("오늘 설명을 돌아봐요")).check(doesNotExist());
    }

    @Test
    public void teacherAndStudentCompleteLocalLessonThroughNativeScreens() {
        String id = create("전체 동선 수업");
        String code = snapshot(id).code;
        launch("TEACHER");
        scrollTap("전체 동선 수업");
        awaitRoute("invite");
        tap("첫 대주제로 수업 시작");
        tap("수업 시작");
        awaitRoute("live");
        tap("완료하고 다음 주제");
        awaitLesson(id, l -> l.topics.get(0).completed);
        String questionTopic = snapshot(id).currentTopicId;
        backTo("invite");
        backTo("home");
        switchRole("학생 시연으로 전환");
        tap("코드 직접 입력");
        awaitRoute("join");
        field("참여 코드", code);
        tap("수업 확인하기");
        awaitRoute("confirm_join");
        tap("이 수업에 참여하기");
        awaitRoute("live");
        String[] reactions = {"이해가 안 돼요", "너무 빨라요", "용어가 어려워요", "계산을 놓쳤어요"};
        for (int i = 0; i < reactions.length; i++) {
            tap(reactions[i]);
            final int count = i + 1;
            awaitLesson(id, l -> l.reactions.size() == count);
        }
        for (String reaction : reactions)
            onView(withText("✓ " + reaction)).check(matches(isDisplayed()));
        screenshot("student-live-four-reactions");
        tap("질문 작성");
        awaitRoute("question_edit");
        field("궁금한 내용", "두 번째 주제의 계산이 궁금해요");
        tap("질문 제출하기");
        awaitRoute("question_detail");
        String questionId = snapshot(id).questions.get(0).id;
        assertEquals(questionTopic, snapshot(id).question(questionId).topicId);
        backTo("live");
        backTo("home");
        switchRole("교수자 시연으로 전환");
        scrollTap("전체 동선 수업");
        awaitRoute("live");
        tap("수업 종료");
        tap("수업 종료");
        awaitRoute("review");
        scrollTap("주제별 반응 분석");
        awaitRoute("analysis");
        onView(withText("이해가 안 돼요  1명 · 100%")).perform(scrollTo()).check(matches(isDisplayed()));
        scenario.onActivity(activity ->
                ((androidx.core.widget.NestedScrollView) activity.findViewById(R.id.scroll))
                        .scrollTo(0, 0));
        screenshot("teacher-topic-analysis");
        backTo("review");
        scrollTap("대주제별 질문·답변");
        awaitRoute("questions");
        scrollTap("두 번째 주제의 계산이 궁금해요");
        awaitRoute("question_detail");
        field("답변 작성", "분모를 먼저 구한 뒤 계산하면 됩니다.");
        tap("답변 공개하기");
        tap("공개");
        awaitLesson(id, l -> l.question(questionId).published);
        backTo("questions");
        backTo("review");
        scrollTap("전체 원문·요약·보충 설명");
        awaitRoute("material_edit");
        StringBuilder original = new StringBuilder("첫 대주제\n처음부터 포함한 원문입니다.\n둘째 대주제\n");
        for (int i = 0; i < 60; i++)
            original.append("강의 내용 ").append(i).append(" · 모든 설명을 보존합니다.\n");
        original.append("마지막 강의 문장입니다.");
        String fullText = original.toString();
        field("전체 강의 원문 · 필수", fullText);
        tap("검토한 자료 공개");
        tap("공개");
        awaitRoute("review");
        switchRole("학생 시연으로 전환");
        scrollTap("전체 동선 수업");
        awaitRoute("review");
        scrollTap("전체 강의 원문");
        awaitRoute("transcript");
        onView(withText(fullText)).check(matches(isDisplayed()));
        screenshot("student-full-transcript");
        tap("전체 자료 기기에 저장");
        awaitLesson(id, l -> !l.downloads.isEmpty());
        assertEquals(fullText, snapshot(id).downloads.get(0).original);
        assertEquals(4, snapshot(id).reactions.size());
        assertTrue(snapshot(id).question(questionId).published);
        assertEquals(questionTopic, snapshot(id).question(questionId).topicId);
    }

    @Test
    public void createAndEditLessonKeepTopicIdsAndDoNotLeaveCompletedFormsOnBackStack() {
        create("홈 초기 수업");
        launch("TEACHER");
        tap("새 수업 만들기");
        awaitRoute("create");
        tap("저장하고 초대하기");
        awaitRoute("create");
        field("수업명", "화면으로 생성한 수업");
        field("회차·설명", "1회차");
        tap("저장하고 초대하기");
        awaitRoute("invite");
        AtomicReference<String> id = new AtomicReference<>();
        scenario.onActivity(activity ->
                id.set(
                        ScreenContext.from(
                                        activity.getSupportFragmentManager()
                                                .findFragmentById(R.id.main)
                                                .getArguments())
                                .lessonId));
        List<Models.Topic> topics = snapshot(id.get()).topics;
        scrollTap("수업·대주제 수정");
        awaitRoute("edit_lesson");
        field("수업명", "수정한 수업명");
        field("1. 대주제", "수정한 첫 대주제");
        recreate("edit_lesson");
        tap("수정 내용 저장");
        awaitRoute("invite");
        awaitLesson(id.get(), l -> "수정한 수업명".equals(l.title));
        assertEquals(topics.get(0).id, snapshot(id.get()).topics.get(0).id);
        assertEquals("수정한 첫 대주제", snapshot(id.get()).topics.get(0).title);
        backTo("home");
        scenario.onActivity(activity ->
                assertEquals(0, activity.getSupportFragmentManager().getBackStackEntryCount()));
    }

    @Test
    public void allFourReactionButtonsRemainVisibleAndReadable() {
        String id = create("네 반응 배치 확인");
        join(id);
        ok(cb -> repo.startLesson(id, cb));
        launch("STUDENT");
        open("live", screen(id));
        String[] labels = {"이해가 안 돼요", "너무 빨라요", "용어가 어려워요", "계산을 놓쳤어요"};
        for (String label : labels) {
            onView(withText(label))
                    .check(matches(isCompletelyDisplayed()))
                    .check((view, error) -> {
                        if (error != null) throw error;
                        TextView text = (TextView) view;
                        assertNotNull(text.getLayout());
                        for (int line = 0; line < text.getLayout().getLineCount(); line++)
                            assertEquals(
                                    "Clipped reaction: " + label,
                                    0,
                                    text.getLayout().getEllipsisCount(line));
                        assertTrue(
                                "Reaction text overflows its button: " + label,
                                text.getLayout().getHeight()
                                        <= text.getHeight()
                                                - text.getTotalPaddingTop()
                                                - text.getTotalPaddingBottom()
                                                + 1);
                    });
        }
        screenshot("student-live-layout");
    }
}
