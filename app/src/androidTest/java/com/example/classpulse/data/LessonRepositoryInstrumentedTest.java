package com.example.classpulse.data;

import android.content.Context;
import android.os.Looper;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.classpulse.domain.Models;
import com.example.classpulse.data.LocalEntities.LessonRow;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LessonRepositoryInstrumentedTest {
    private Context context;
    private LessonRepository repo;
    private AppDatabase db;
    private static final String UID = Models.STUDENT_ID;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).build();
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> repo = LessonRepository.forTests(db));
    }

    @After
    public void tearDown() {
        if (repo != null) repo.closeForTests();
    }

    private <T> T success(Consumer<LessonRepository.Callback<T>> operation) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        operation.accept(
                new LessonRepository.Callback<T>() {
                    @Override
                    public void onSuccess(T result) {
                        if (Looper.myLooper() != Looper.getMainLooper())
                            error.set("Callback must run on main thread");
                        value.set(result);
                        latch.countDown();
                    }

                    @Override
                    public void onError(String message) {
                        error.set(message);
                        latch.countDown();
                    }
                });
        try {
            assertTrue("Repository callback timed out", latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        assertNull(error.get(), error.get());
        return value.get();
    }

    private void ok(Consumer<LessonRepository.Callback<Void>> action) {
        success(action);
    }

    private <T> String failure(Consumer<LessonRepository.Callback<T>> operation) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        operation.accept(
                new LessonRepository.Callback<T>() {
                    @Override
                    public void onSuccess(T ignored) {
                        latch.countDown();
                    }

                    @Override
                    public void onError(String message) {
                        error.set(message);
                        latch.countDown();
                    }
                });
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        assertNotNull("Expected validation failure", error.get());
        return error.get();
    }

    private String create() {
        return success(cb ->
                repo.createLesson(
                        "테스트 수업", "로컬 데이터 검증", Arrays.asList("조건부확률", "독립사건", "베이즈 정리"), cb));
    }

    private Models.Lesson lesson(String id) {
        for (Models.Lesson l : repo.snapshotForTests()) if (id.equals(l.id)) return l;
        throw new AssertionError("Missing lesson");
    }

    private void join(String id) {
        String joined = success(cb -> repo.joinById(UID, id, cb));
        assertEquals(id, joined);
    }

    @Test
    public void localOutlineProgressAndDeletionPreserveExistingCourseBoundaries() {
        Models.Topic main = topic("unit-topic", "함수", null);
        Models.Topic child = topic("child-topic", "정의역", main.id);
        Models.Topic next = topic("next-topic", "함수 계산", null);
        String id = success(cb -> repo.createLessonWithOutline(
                "목차 수업", "", Arrays.asList(child, main, next), cb));
        Models.Lesson saved = lesson(id);
        assertEquals(main.id, saved.topics.get(0).id);
        assertEquals(main.id, saved.topics.get(1).parentTopicId);
        assertEquals("1.1 정의역", saved.topicLabel(saved.topic(child.id)));

        Models.Topic newChild = topic("extra-topic", "치역", main.id);
        ok(cb -> repo.updateLesson(id, saved.title, saved.description,
                Arrays.asList(main, child, newChild, next), cb));
        join(id);
        ok(cb -> repo.startLesson(id, cb));
        assertEquals(child.id, lesson(id).currentTopicId);
        assertTrue(this.<Void>failure(cb -> repo.deleteLesson(id, Models.TEACHER_ID, cb)).contains("종료"));
        String question = success(cb -> repo.submitQuestion(null, id, child.id, UID, "정의역 질문", cb));
        ok(cb -> repo.completeTopic(id, cb));
        assertEquals(newChild.id, lesson(id).currentTopicId);
        ok(cb -> repo.completeTopic(id, cb));
        assertEquals(next.id, lesson(id).currentTopicId);
        assertEquals(child.id, lesson(id).question(question).topicId);
        ok(cb -> repo.endLesson(id, cb));
        assertTrue(this.<Void>failure(cb -> repo.deleteLesson(id, UID, cb)).contains("담당 교수"));
        assertTrue(lesson(id).isMember(UID));
        String another = create();
        ok(cb -> repo.deleteLesson(id, Models.TEACHER_ID, cb));
        assertNull(db.lessonDao().lesson(id));
        assertTrue(db.lessonDao().topics(id).isEmpty());
        assertNull(db.lessonDao().question(question));
        assertNotNull(db.lessonDao().lesson(another));
    }

    private Models.Topic topic(String id, String title, String parentTopicId) {
        Models.Topic topic = new Models.Topic();
        topic.id = id;
        topic.title = title;
        topic.parentTopicId = parentTopicId;
        return topic;
    }

    @Test
    public void sequentialProgressAndRevisitPreserveHistoryAndQuestionContext() {
        String id = create();
        join(id);
        ok(cb -> repo.startLesson(id, cb));
        Models.Lesson first = lesson(id);
        String topicA = first.topics.get(0).id,
                topicB = first.topics.get(1).id,
                topicC = first.topics.get(2).id;
        assertEquals(topicA, first.currentTopicId);
        ok(cb -> repo.completeTopic(id, cb));
        assertEquals(topicB, lesson(id).currentTopicId);
        String question =
                success(cb -> repo.submitQuestion(null, id, topicA, UID, "이전 대주제에 질문합니다", cb));
        ok(cb -> repo.completeTopic(id, cb));
        assertEquals(topicC, lesson(id).currentTopicId);
        String previousVisit = lesson(id).currentVisitId;
        ok(cb -> repo.revisitTopic(id, topicA, cb));
        Models.Lesson revisited = lesson(id);
        assertEquals(topicA, revisited.currentTopicId);
        assertEquals(topicC, revisited.resumeTopicId);
        assertTrue(revisited.topic(topicA).completed);
        assertTrue(revisited.topic(topicB).completed);
        assertFalse(revisited.topic(topicC).completed);
        assertEquals(topicA, revisited.question(question).topicId);
        ok(cb -> repo.resumeTopic(id, cb));
        Models.Lesson resumed = lesson(id);
        assertEquals(topicC, resumed.currentTopicId);
        assertNull(resumed.resumeTopicId);
        assertNotEquals(previousVisit, resumed.currentVisitId);
        ok(cb -> repo.completeTopic(id, cb));
        assertEquals(Models.LIVE, lesson(id).status);
        assertNull(lesson(id).currentVisitId);
        ok(cb -> repo.endLesson(id, cb));
        assertEquals(Models.ENDED, lesson(id).status);
        String after =
                success(cb -> repo.submitQuestion(null, id, topicA, UID, "수업 종료 후 질문도 남깁니다", cb));
        assertEquals(Models.SUBMITTED, lesson(id).question(after).status);
    }

    @Test
    public void closingQuestionIntakeRejectsReactionsWithoutChangingTheRoomSchema() {
        String id = create();
        join(id);
        ok(cb -> repo.startLesson(id, cb));
        String visit = lesson(id).currentVisitId;
        assertTrue(this.<Boolean>success(cb -> repo.toggleReaction(id, UID, visit, Models.UNDERSTANDING, cb)));
        LessonRow row = db.lessonDao().lesson(id);
        row.questionsOpen = false;
        db.lessonDao().updateLesson(row);
        assertTrue(this.<Boolean>failure(cb ->
                repo.toggleReaction(id, UID, visit, Models.UNDERSTANDING, cb)).contains("접수를 마감"));
        assertEquals(1, lesson(id).reactions.size());
        row.questionsOpen = true;
        db.lessonDao().updateLesson(row);
        assertFalse(this.<Boolean>success(cb -> repo.toggleReaction(id, UID, visit, Models.UNDERSTANDING, cb)));
        assertEquals(0, lesson(id).reactions.size());
        while (lesson(id).currentVisitId != null) ok(cb -> repo.completeTopic(id, cb));
        this.<Boolean>failure(cb -> repo.toggleReaction(id, UID, visit, Models.TERM, cb));
        assertEquals(0, lesson(id).reactions.size());
    }

    @Test
    public void reactionTogglesOnlyForTheCurrentVisitAndRequiresMembership() {
        String id = create();
        ok(cb -> repo.startLesson(id, cb));
        String visit = lesson(id).currentVisitId;
        this.<Boolean>failure(cb -> repo.toggleReaction(id, UID, visit, Models.UNDERSTANDING, cb));
        join(id);
        assertTrue(this.<Boolean>success(cb -> repo.toggleReaction(id, UID, visit, Models.UNDERSTANDING, cb)));
        assertFalse(this.<Boolean>success(cb -> repo.toggleReaction(id, UID, visit, Models.UNDERSTANDING, cb)));
        assertTrue(this.<Boolean>success(cb -> repo.toggleReaction(id, UID, visit, Models.UNDERSTANDING, cb)));
        assertTrue(this.<Boolean>success(cb -> repo.toggleReaction(id, UID, visit, Models.SPEED, cb)));
        assertEquals(2, lesson(id).reactions.size());
        String a = lesson(id).currentTopicId;
        ok(cb -> repo.completeTopic(id, cb));
        ok(cb -> repo.revisitTopic(id, a, cb));
        this.<Boolean>failure(cb -> repo.toggleReaction(id, UID, visit, Models.UNDERSTANDING, cb));
        String revisit = lesson(id).currentVisitId;
        assertTrue(this.<Boolean>success(cb -> repo.toggleReaction(id, UID, revisit, Models.UNDERSTANDING, cb)));
        assertFalse(this.<Boolean>success(cb -> repo.toggleReaction(id, UID, revisit, Models.UNDERSTANDING, cb)));
        assertEquals(2, lesson(id).reactions.size());
        ok(cb -> repo.endLesson(id, cb));
        this.<Boolean>failure(cb -> repo.toggleReaction(id, UID, revisit, Models.SPEED, cb));
        assertEquals(2, lesson(id).reactions.size());
    }

    @Test
    public void localDraftsRequireTextAndCanBeDeletedWithoutRemovingSubmittedQuestions() {
        String sid = create();
        join(sid);
        String topic = lesson(sid).topics.get(0).id;
        String draftId = java.util.UUID.randomUUID().toString();
        this.<String>failure(cb -> repo.saveDraft(draftId, sid, topic, UID, "  \t ", cb));
        assertNull(db.lessonDao().question(draftId));
        assertEquals(draftId, this.<String>success(cb -> repo.saveDraft(draftId, sid, topic, UID, "저장할 초안", cb)));
        this.<String>failure(cb -> repo.saveDraft(draftId, sid, topic, UID, "  ", cb));
        assertEquals("저장할 초안", lesson(sid).question(draftId).text);
        ok(cb -> repo.deleteQuestionDraft(sid, draftId, UID, cb));
        assertNull(lesson(sid).question(draftId));
        this.<String>failure(cb -> repo.saveDraft(draftId, sid, topic, UID, "늦은 자동 저장", cb));
        String submitted = success(cb -> repo.submitQuestion(null, sid, topic, UID, "제출한 질문", cb));
        this.<Void>failure(cb -> repo.deleteQuestionDraft(sid, submitted, UID, cb));
        assertEquals(Models.SUBMITTED, lesson(sid).question(submitted).status);
    }

    @Test
    public void onlyUnansweredSubmittedQuestionsCanBeWithdrawnFromTheLocalLesson() {
        String sid = create();
        join(sid);
        String topic = lesson(sid).topics.get(0).id;
        String question = success(cb -> repo.submitQuestion(null, sid, topic, UID, "취소할 질문", cb));
        ok(cb -> repo.withdrawQuestion(sid, question, UID, cb));
        ok(cb -> repo.withdrawQuestion(sid, question, UID, cb));
        assertNull(lesson(sid).question(question));
        assertTrue(this.<String>failure(cb ->
                repo.submitQuestion(question, sid, topic, UID, "취소할 질문", cb)).contains("취소"));
        this.<Void>failure(cb -> repo.saveAnswer(question, "늦은 답변", true, cb));

        String answered = success(cb -> repo.submitQuestion(null, sid, topic, UID, "남길 질문", cb));
        ok(cb -> repo.saveAnswer(answered, "공개 답변", true, cb));
        this.<Void>failure(cb -> repo.withdrawQuestion(sid, answered, UID, cb));
        assertEquals(Models.ANSWERED, lesson(sid).question(answered).status);
    }

    @Test
    public void clientDraftIdAndPublishedAnswersAreNotOverwrittenByPrivateDrafts() {
        String id = create();
        join(id);
        Models.Lesson l = lesson(id);
        String topic = l.topics.get(0).id,
                other = l.topics.get(1).id,
                clientId = UUID.randomUUID().toString();
        String saved = success(cb -> repo.saveDraft(clientId, id, topic, UID, "분모가 궁금해요", cb));
        assertEquals(clientId, saved);
        success((LessonRepository.Callback<String> cb) ->
                repo.saveDraft(clientId, id, topic, UID, "전체 분모가 궁금해요", cb));
        assertEquals(1, lesson(id).questions.size());
        success((LessonRepository.Callback<String> cb) ->
                repo.submitQuestion(clientId, id, topic, UID, "전체 분모가 궁금해요", cb));
        success((LessonRepository.Callback<String> cb) ->
                repo.submitQuestion(clientId, id, topic, UID, "전체 분모가 궁금해요", cb));
        assertEquals(1, lesson(id).questions.size());
        failure((LessonRepository.Callback<String> cb) ->
                repo.submitQuestion(clientId, id, other, UID, "전체 분모가 궁금해요", cb));
        assertEquals(topic, lesson(id).question(clientId).topicId);
        ok(cb -> repo.saveAnswer(clientId, "검토 중인 비공개 초안", false, cb));
        Models.Question q = lesson(id).question(clientId);
        assertFalse(q.published);
        assertEquals("", q.answer);
        assertEquals("검토 중인 비공개 초안", q.answerDraft);
        ok(cb -> repo.saveAnswer(clientId, "첫 번째 공개 답변", true, cb));
        ok(cb -> repo.saveAnswer(clientId, "아직 공개하지 않은 수정본", false, cb));
        q = lesson(id).question(clientId);
        assertEquals("첫 번째 공개 답변", q.answer);
        assertEquals(1, q.answerVersion);
        ok(cb -> repo.saveAnswer(clientId, "검토한 수정 공개본", true, cb));
        q = lesson(id).question(clientId);
        assertEquals("검토한 수정 공개본", q.answer);
        assertEquals(2, q.answerVersion);
        assertEquals("", q.answerDraft);
    }

    @Test
    public void materialPublicationAndDownloadCopiesRemainIndependent() {
        String id = create();
        join(id);
        String topic = lesson(id).topics.get(0).id;
        String draft = success(cb -> repo.saveDraft(null, id, topic, UID, "보존할 질문 초안", cb));
        String mark = success(cb -> repo.saveMark(id, topic, UID, "보존할 표시", cb));
        ok(cb -> repo.startLesson(id, cb));
        ok(cb -> repo.endLesson(id, cb));
        ok(cb -> repo.saveMaterial(id, "원문 전체 1", "", "보충 설명", false, cb));
        Models.Material m = lesson(id).material;
        assertFalse(m.published);
        assertTrue(m.hasDraft);
        assertEquals("", m.original);
        failure((LessonRepository.Callback<Void> cb) -> repo.downloadMaterial(id, UID, cb));
        ok(cb -> repo.saveMaterial(id, "원문 전체 1", "", "보충 설명", true, cb));
        ok(cb -> repo.downloadMaterial(id, UID, cb));
        assertEquals(1, lesson(id).downloads.get(0).version);
        ok(cb -> repo.saveMaterial(id, "원문 전체 2", "새 요약", "", false, cb));
        assertEquals("원문 전체 1", lesson(id).material.original);
        ok(cb -> repo.saveMaterial(id, "원문 전체 2", "새 요약", "", true, cb));
        assertEquals("원문 전체 1", lesson(id).downloads.get(0).original);
        assertEquals(2, lesson(id).material.version);
        ok(cb -> repo.downloadMaterial(id, UID, cb));
        assertEquals("원문 전체 2", lesson(id).downloads.get(0).original);
        ok(cb -> repo.clearDownloads(UID, cb));
        lHasPrivateRecords(id, draft, mark);
        assertTrue(lesson(id).downloads.isEmpty());
        assertEquals("원문 전체 2", lesson(id).material.original);
    }

    private void lHasPrivateRecords(String id, String draft, String mark) {
        assertNotNull(lesson(id).question(draft));
        assertTrue(lesson(id).marks.stream().anyMatch(m -> m.id.equals(mark)));
    }

    @Test
    public void quizAttemptIsAtomicAndUsesAnImmutableQuestionSnapshot() {
        String id = create();
        join(id);
        String topic = lesson(id).topics.get(0).id;
        List<String> options = Arrays.asList("첫 보기", "둘째 보기", "셋째 보기");
        failure((LessonRepository.Callback<String> cb) ->
                repo.saveQuiz(null, id, topic, "복습", "정답을 고르세요", options, 1, "둘째 보기 설명", true, cb));
        ok(cb -> repo.startLesson(id, cb));
        ok(cb -> repo.endLesson(id, cb));
        String quiz =
                success(cb ->
                        repo.saveQuiz(
                                null,
                                id,
                                topic,
                                "복습",
                                "정답을 고르세요",
                                options,
                                1,
                                "둘째 보기 설명",
                                true,
                                cb));
        ok(cb -> repo.submitAttempt(id, quiz, UID, 1, cb));
        ok(cb -> repo.submitAttempt(id, quiz, UID, 0, cb));
        Models.Lesson l = lesson(id);
        assertEquals(1, l.attempts.size());
        Models.Attempt a = l.attempts.get(0);
        assertTrue(a.correct);
        assertEquals(1, a.choice);
        assertEquals(options, a.options);
        assertEquals("둘째 보기 설명", a.explanation);
        assertEquals(1, a.quizVersion);
        failure((LessonRepository.Callback<String> cb) ->
                repo.saveQuiz(quiz, id, topic, "수정 퀴즈", "바뀐 문제", options, 0, "수정 해설", true, cb));
        assertEquals("정답을 고르세요", lesson(id).quiz(quiz).question);
        assertEquals("정답을 고르세요", lesson(id).attempts.get(0).question);
        failure((LessonRepository.Callback<Void> cb) ->
                repo.submitAttempt(id, quiz, "not-a-member", 1, cb));
    }

    @Test
    public void readyTopicEditingPreservesIdsAndRejectsDeletingReferencedTopics() {
        String id = create();
        join(id);
        Models.Lesson l = lesson(id);
        String first = l.topics.get(0).id;
        List<Models.Topic> reordered =
                Arrays.asList(l.topics.get(2), l.topics.get(0), l.topics.get(1));
        ok(cb -> repo.updateLesson(id, "수정한 수업", "설명", reordered, cb));
        assertEquals(first, lesson(id).topics.get(1).id);
        success((LessonRepository.Callback<String> cb) ->
                repo.saveMark(id, first, UID, "연결 보존", cb));
        Models.Lesson current = lesson(id);
        List<Models.Topic> withoutFirst =
                Arrays.asList(current.topics.get(0), current.topics.get(2));
        failure((LessonRepository.Callback<Void> cb) ->
                repo.updateLesson(id, "삭제 시도", "", withoutFirst, cb));
        assertEquals(3, lesson(id).topics.size());
        assertEquals("수정한 수업", lesson(id).title);
        ok(cb -> repo.startLesson(id, cb));
        failure((LessonRepository.Callback<Void> cb) ->
                repo.updateLesson(id, "진행 중 수정", "", reordered, cb));
    }

    @Test
    public void leadingZeroCodeIsNotConvertedToANumber() {
        LessonRow l = new LessonRow();
        l.id = "leading-zero";
        l.title = "앞자리 0 수업";
        l.code = "0007";
        l.teacherId = Models.TEACHER_ID;
        l.createdAt = 1;
        db.lessonDao().insertLesson(l);
        String id = success(cb -> repo.joinByCode(UID, "0007", cb));
        assertEquals(l.id, id);
        failure((LessonRepository.Callback<String> cb) -> repo.joinByCode(UID, "7", cb));
        assertEquals(1, lesson(l.id).members.size());
    }
}
