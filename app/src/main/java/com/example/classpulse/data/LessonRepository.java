package com.example.classpulse.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.room.Room;
import com.example.classpulse.domain.Models;
import com.example.classpulse.data.LocalEntities.*;
import org.json.JSONArray;
import org.json.JSONException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local-only demo repository. A local callback means a Room transaction committed,
 * never that Firebase, Google authentication, STT or AI accepted a request.
 */
public final class LessonRepository {
    public interface Callback<T> {
        void onSuccess(T value);

        void onError(String message);
    }

    private interface Operation<T> {
        T run() throws Exception;
    }

    private static volatile LessonRepository instance;
    private final AppDatabase db;
    private final LessonDao dao;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LiveData<List<Models.Lesson>> lessons;
    private final MutableLiveData<String> initializationError = new MutableLiveData<>();
    private static final List<String> REACTION_TYPES =
            Arrays.asList(Models.UNDERSTANDING, Models.SPEED, Models.TERM, Models.CALCULATION);

    public static LessonRepository get(Context context) {
        if (instance == null)
            synchronized (LessonRepository.class) {
                if (instance == null)
                    instance =
                            new LessonRepository(
                                    Room.databaseBuilder(
                                                    context.getApplicationContext(),
                                                    AppDatabase.class,
                                                    "classpulse-local-demo.db")
                                            .build(),
                                    true);
            }
        return instance;
    }

    private LessonRepository(AppDatabase database, boolean seed) {
        db = database;
        dao = db.lessonDao();
        lessons = Transformations.map(dao.observeGraphs(), LessonRepository::mapGraphs);
        if (seed) retryInitialization();
    }

    static LessonRepository forTests(AppDatabase database) {
        return new LessonRepository(database, false);
    }

    static synchronized LessonRepository swapForTests(LessonRepository replacement) {
        LessonRepository previous = instance;
        instance = replacement;
        return previous;
    }

    List<Models.Lesson> snapshotForTests() {
        return mapGraphs(dao.graphs());
    }

    void closeForTests() {
        io.shutdownNow();
        db.close();
    }

    public LiveData<List<Models.Lesson>> observeLessons() {
        return lessons;
    }

    public LiveData<String> initializationError() {
        return initializationError;
    }

    public void retryInitialization() {
        initializationError.postValue(null);
        io.execute(() -> {
            try {
                db.runInTransaction(this::seedDemo);
            } catch (Exception e) {
                Log.e("LessonRepository", "Local demo initialization failed", e);
                initializationError.postValue("이 기기의 시연 자료를 준비하지 못했어요. 저장 공간을 확인한 뒤 다시 시도해 주세요.");
            }
        });
    }

    private <T> void execute(Operation<T> operation, Callback<T> callback) {
        io.execute(() -> {
            try {
                T result = db.runInTransaction((Callable<T>) operation::run);
                if (callback != null) main.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                String message =
                        e instanceof IllegalArgumentException || e instanceof IllegalStateException
                                ? e.getMessage()
                                : "기기에 저장하지 못했어요. 입력한 내용을 유지한 채 다시 시도해 주세요.";
                if (callback != null)
                    main.post(() -> callback.onError(message == null ? "작업을 완료하지 못했어요." : message));
            }
        });
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String required(String value, String message) {
        String v = clean(value);
        if (v.isEmpty()) throw new IllegalArgumentException(message);
        return v;
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static String stableId(String... parts) {
        return UUID.nameUUIDFromBytes(String.join("\u001f", parts).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private LessonRow requireLesson(String id) {
        LessonRow l = dao.lesson(required(id, "수업을 선택해 주세요."));
        if (l == null) throw new IllegalArgumentException("이 기기에서 수업을 찾지 못했어요.");
        return l;
    }

    private TopicRow requireTopic(String sessionId, String id) {
        TopicRow t = dao.topic(required(id, "대주제를 선택해 주세요."));
        if (t == null || !sessionId.equals(t.sessionId))
            throw new IllegalArgumentException("이 수업의 대주제를 선택해 주세요.");
        return t;
    }

    private void requireStudent(String uid) {
        if (clean(uid).isEmpty() || Models.TEACHER_ID.equals(uid))
            throw new IllegalArgumentException("학생 시연 역할을 선택해 주세요.");
    }

    private void requireMember(String sessionId, String uid) {
        requireStudent(uid);
        if (dao.member(sessionId, uid) == null)
            throw new IllegalStateException("수업 정보를 확인한 뒤 먼저 참여해 주세요.");
    }

    private void requireLive(LessonRow l) {
        if (!Models.LIVE.equals(l.status)) throw new IllegalStateException("현재 진행 중인 수업이 아니에요.");
    }

    private void closeVisit(LessonRow l) {
        if (l.currentVisitId != null) {
            VisitRow v = dao.visit(l.currentVisitId);
            if (v != null && v.endedAt == 0) {
                v.endedAt = System.currentTimeMillis();
                dao.updateVisit(v);
            }
        }
        l.currentVisitId = null;
    }

    private void openVisit(LessonRow l, TopicRow t, boolean revisit) {
        VisitRow v = new VisitRow();
        v.id = id();
        v.sessionId = l.id;
        v.topicId = t.id;
        v.revisit = revisit;
        v.startedAt = System.currentTimeMillis();
        dao.insertVisit(v);
        l.currentTopicId = t.id;
        l.currentVisitId = v.id;
    }

    public void createLesson(
            String title, String description, List<String> topicTitles, Callback<String> cb) {
        execute(
                () -> {
                    String name = required(title, "수업 이름을 입력해 주세요.");
                    if (name.length() > 100)
                        throw new IllegalArgumentException("수업 이름은 100자까지 입력할 수 있어요.");
                    if (topicTitles == null || topicTitles.isEmpty() || topicTitles.size() > 30)
                        throw new IllegalArgumentException("대주제를 1개 이상, 30개 이하로 등록해 주세요.");
                    List<String> names = new ArrayList<>();
                    for (String t : topicTitles) {
                        String n = required(t, "빈 대주제 이름을 확인해 주세요.");
                        if (n.length() > 100)
                            throw new IllegalArgumentException("대주제 이름은 100자까지 입력할 수 있어요.");
                        names.add(n);
                    }
                    LessonRow l = new LessonRow();
                    l.id = id();
                    l.title = name;
                    l.description = clean(description);
                    l.teacherId = Models.TEACHER_ID;
                    l.createdAt = System.currentTimeMillis();
                    l.status = Models.READY;
                    l.code = newCode();
                    dao.insertLesson(l);
                    List<TopicRow> rows = new ArrayList<>();
                    for (int i = 0; i < names.size(); i++) {
                        TopicRow t = new TopicRow();
                        t.id = id();
                        t.sessionId = l.id;
                        t.title = names.get(i);
                        t.position = i;
                        rows.add(t);
                    }
                    dao.insertTopics(rows);
                    return l.id;
                },
                cb);
    }

    public void updateLesson(
            String sessionId,
            String title,
            String description,
            List<Models.Topic> topics,
            Callback<Void> cb) {
        execute(
                () -> {
                    LessonRow l = requireLesson(sessionId);
                    if (!Models.READY.equals(l.status))
                        throw new IllegalStateException("수업 시작 전의 대주제만 편집할 수 있어요.");
                    String name = required(title, "수업 이름을 입력해 주세요.");
                    if (name.length() > 100)
                        throw new IllegalArgumentException("수업 이름은 100자까지 입력할 수 있어요.");
                    if (topics == null || topics.isEmpty() || topics.size() > 30)
                        throw new IllegalArgumentException("대주제를 1개 이상, 30개 이하로 등록해 주세요.");
                    List<TopicRow> existing = dao.topics(l.id),
                            rows = new ArrayList<>(),
                            newRows = new ArrayList<>();
                    java.util.Set<String> retained = new java.util.HashSet<>();
                    for (int i = 0; i < topics.size(); i++) {
                        Models.Topic input = topics.get(i);
                        String nameTopic = required(input.title, "빈 대주제 이름을 확인해 주세요.");
                        if (nameTopic.length() > 100)
                            throw new IllegalArgumentException("대주제 이름은 100자까지 입력할 수 있어요.");
                        TopicRow t;
                        if (clean(input.id).isEmpty()) {
                            t = new TopicRow();
                            t.id = id();
                            t.sessionId = l.id;
                            newRows.add(t);
                        } else {
                            t = requireTopic(l.id, input.id);
                            if (!retained.add(t.id))
                                throw new IllegalArgumentException("같은 대주제가 중복됐어요. 목록을 확인해 주세요.");
                        }
                        t.title = nameTopic;
                        t.position = i;
                        rows.add(t);
                    }
                    for (TopicRow old : existing)
                        if (!retained.contains(old.id)) {
                            if (dao.topicReferenceCount(old.id) > 0)
                                throw new IllegalStateException(
                                        "질문이나 기록이 연결된 대주제는 삭제할 수 없어요. 이름이나 순서를 수정해 주세요.");
                            dao.deleteTopic(old);
                        }
                    dao.insertTopics(newRows);
                    for (TopicRow t : rows) if (!newRows.contains(t)) dao.updateTopic(t);
                    l.title = name;
                    l.description = clean(description);
                    dao.updateLesson(l);
                    return null;
                },
                cb);
    }

    private String newCode() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 10000; i++) {
            String c = String.format(java.util.Locale.US, "%04d", random.nextInt(10000));
            if (dao.lessonByCode(c) == null) return c;
        }
        throw new IllegalStateException("이 기기에 사용할 수 있는 참여 코드가 부족해요.");
    }

    public void joinByCode(String studentId, String code, Callback<String> cb) {
        execute(
                () -> {
                    requireStudent(studentId);
                    String c = clean(code);
                    if (!c.matches("[0-9]{4}"))
                        throw new IllegalArgumentException("참여 코드 네 자리를 모두 입력해 주세요.");
                    LessonRow l = dao.lessonByCode(c);
                    if (l == null)
                        throw new IllegalArgumentException(
                                "이 기기에 해당 코드의 수업이 없어요. 다른 기기의 수업 참여는 Firebase 연동 후 사용할 수 있어요.");
                    join(l, studentId);
                    return l.id;
                },
                cb);
    }

    public void joinById(String studentId, String sessionId, Callback<String> cb) {
        execute(
                () -> {
                    LessonRow l = requireLesson(sessionId);
                    join(l, studentId);
                    return l.id;
                },
                cb);
    }

    private void join(LessonRow l, String uid) {
        requireStudent(uid);
        if (dao.member(l.id, uid) != null) return;
        if (Models.ENDED.equals(l.status))
            throw new IllegalStateException(
                    "종료된 수업에 새로 참여하는 기능은 아직 제공하지 않아요. 기존 참여 수업은 내 수업에서 열어 주세요.");
        MemberRow m = new MemberRow();
        m.sessionId = l.id;
        m.studentId = uid;
        m.joinedAt = System.currentTimeMillis();
        dao.insertMember(m);
    }

    public void startLesson(String sessionId, Callback<Void> cb) {
        execute(
                () -> {
                    LessonRow l = requireLesson(sessionId);
                    if (Models.LIVE.equals(l.status)) return null;
                    if (!Models.READY.equals(l.status))
                        throw new IllegalStateException("종료한 수업을 다시 시작할 수 없어요.");
                    List<TopicRow> ts = dao.topics(l.id);
                    if (ts.isEmpty()) throw new IllegalStateException("대주제를 먼저 등록해 주세요.");
                    l.status = Models.LIVE;
                    openVisit(l, ts.get(0), false);
                    dao.updateLesson(l);
                    return null;
                },
                cb);
    }

    public void completeTopic(String sessionId, Callback<Void> cb) {
        execute(
                () -> {
                    LessonRow l = requireLesson(sessionId);
                    requireLive(l);
                    if (l.currentVisitId == null)
                        throw new IllegalStateException(
                                "모든 대주제를 마쳤어요. 수업 종료 또는 완료 주제 재방문을 선택해 주세요.");
                    TopicRow current = requireTopic(l.id, l.currentTopicId);
                    closeVisit(l);
                    current.completed = true;
                    dao.updateTopic(current);
                    if (l.resumeTopicId != null) {
                        TopicRow resume = requireTopic(l.id, l.resumeTopicId);
                        l.resumeTopicId = null;
                        if (!resume.completed) openVisit(l, resume, false);
                        else l.currentTopicId = resume.id;
                    } else {
                        for (TopicRow t : dao.topics(l.id))
                            if (!t.completed) {
                                openVisit(l, t, false);
                                break;
                            }
                    }
                    dao.updateLesson(l);
                    return null;
                },
                cb);
    }

    public void revisitTopic(String sessionId, String topicId, Callback<Void> cb) {
        execute(
                () -> {
                    LessonRow l = requireLesson(sessionId);
                    requireLive(l);
                    TopicRow target = requireTopic(l.id, topicId);
                    if (!target.completed) throw new IllegalStateException("완료한 대주제를 선택해 주세요.");
                    if (topicId.equals(l.currentTopicId) && l.resumeTopicId != null) return null;
                    if (l.resumeTopicId == null) l.resumeTopicId = l.currentTopicId;
                    closeVisit(l);
                    openVisit(l, target, true);
                    dao.updateLesson(l);
                    return null;
                },
                cb);
    }

    public void resumeTopic(String sessionId, Callback<Void> cb) {
        execute(
                () -> {
                    LessonRow l = requireLesson(sessionId);
                    requireLive(l);
                    if (l.resumeTopicId == null) throw new IllegalStateException("돌아갈 진행 주제가 없어요.");
                    TopicRow target = requireTopic(l.id, l.resumeTopicId);
                    closeVisit(l);
                    l.resumeTopicId = null;
                    if (!target.completed) openVisit(l, target, false);
                    else l.currentTopicId = target.id;
                    dao.updateLesson(l);
                    return null;
                },
                cb);
    }

    public void endLesson(String sessionId, Callback<Void> cb) {
        execute(
                () -> {
                    LessonRow l = requireLesson(sessionId);
                    if (Models.ENDED.equals(l.status)) return null;
                    requireLive(l);
                    closeVisit(l);
                    l.resumeTopicId = null;
                    l.status = Models.ENDED;
                    dao.updateLesson(l);
                    return null;
                },
                cb);
    }

    public void saveDraft(
            String existingId,
            String sessionId,
            String topicId,
            String studentId,
            String text,
            Callback<String> cb) {
        saveQuestion(existingId, sessionId, topicId, studentId, text, false, cb);
    }

    public void submitQuestion(
            String existingId,
            String sessionId,
            String topicId,
            String studentId,
            String text,
            Callback<String> cb) {
        saveQuestion(existingId, sessionId, topicId, studentId, text, true, cb);
    }

    private void saveQuestion(
            String existingId,
            String sessionId,
            String topicId,
            String uid,
            String text,
            boolean submit,
            Callback<String> cb) {
        execute(
                () -> {
                    LessonRow l = requireLesson(sessionId);
                    requireMember(l.id, uid);
                    requireTopic(l.id, topicId);
                    if (submit && !l.questionsOpen)
                        throw new IllegalStateException("이 수업의 질문 접수가 마감됐어요.");
                    String value = clean(text);
                    if (submit && value.isEmpty())
                        throw new IllegalArgumentException("질문 내용을 입력해 주세요.");
                    if (value.length() > 1000)
                        throw new IllegalArgumentException("질문은 1,000자까지 입력할 수 있어요.");
                    String requestedId = clean(existingId);
                    if (requestedId.length() > 100)
                        throw new IllegalArgumentException("질문을 준비하지 못했어요. 화면을 다시 열어 주세요.");
                    QuestionRow q = requestedId.isEmpty() ? null : dao.question(requestedId);
                    if (q != null) {
                        if (!q.studentId.equals(uid) || !q.sessionId.equals(l.id))
                            throw new IllegalStateException("이 질문을 수정할 수 없어요.");
                        if (!Models.DRAFT.equals(q.status)) {
                            if (q.topicId.equals(topicId) && q.text.equals(value) && submit)
                                return q.id;
                            throw new IllegalStateException(
                                    "접수된 질문의 내용과 대주제는 바꿀 수 없어요. 새 질문으로 작성해 주세요.");
                        }
                    } else {
                        q = new QuestionRow();
                        q.id = requestedId.isEmpty() ? id() : requestedId;
                        q.sessionId = l.id;
                        q.studentId = uid;
                        q.createdAt = System.currentTimeMillis();
                    }
                    q.topicId = topicId;
                    q.text = value;
                    q.updatedAt = System.currentTimeMillis();
                    if (submit) {
                        q.status = Models.SUBMITTED;
                        q.submittedAt = q.updatedAt;
                    }
                    dao.saveQuestion(q);
                    return q.id;
                },
                cb);
    }

    public void saveMark(
            String sessionId, String topicId, String uid, String keyword, Callback<String> cb) {
        execute(
                () -> {
                    requireLesson(sessionId);
                    requireMember(sessionId, uid);
                    requireTopic(sessionId, topicId);
                    String value = clean(keyword);
                    if (value.length() > 200)
                        throw new IllegalArgumentException("키워드는 200자까지 입력할 수 있어요.");
                    MarkRow m = new MarkRow();
                    m.id = id();
                    m.sessionId = sessionId;
                    m.topicId = topicId;
                    m.studentId = uid;
                    m.keyword = value;
                    m.createdAt = System.currentTimeMillis();
                    dao.saveMark(m);
                    return m.id;
                },
                cb);
    }

    public void react(String sessionId, String uid, String type, Callback<Void> cb) {
        execute(
                () -> {
                    LessonRow l = requireLesson(sessionId);
                    requireMember(l.id, uid);
                    requireLive(l);
                    if (l.currentVisitId == null)
                        throw new IllegalStateException("지금 진행 중인 대주제가 없어요.");
                    if (!REACTION_TYPES.contains(type))
                        throw new IllegalArgumentException("반응 유형을 다시 선택해 주세요.");
                    ReactionRow r = new ReactionRow();
                    r.id = stableId(uid, l.currentVisitId, type);
                    r.sessionId = l.id;
                    r.topicId = l.currentTopicId;
                    r.visitId = l.currentVisitId;
                    r.studentId = uid;
                    r.type = type;
                    r.createdAt = System.currentTimeMillis();
                    dao.insertReaction(r);
                    return null;
                },
                cb);
    }

    public void saveAnswer(String questionId, String answer, boolean publish, Callback<Void> cb) {
        execute(
                () -> {
                    QuestionRow q = dao.question(questionId);
                    if (q == null || Models.DRAFT.equals(q.status))
                        throw new IllegalStateException("접수된 질문을 선택해 주세요.");
                    String value = clean(answer);
                    if (publish && value.isEmpty())
                        throw new IllegalArgumentException("공개할 답변 내용을 입력해 주세요.");
                    if (value.length() > 20000)
                        throw new IllegalArgumentException("답변은 20,000자까지 입력할 수 있어요.");
                    q.answerDraft = value;
                    q.updatedAt = System.currentTimeMillis();
                    if (publish) {
                        q.answer = value;
                        q.published = true;
                        q.status = Models.ANSWERED;
                        q.answerVersion++;
                        q.answeredAt = q.updatedAt;
                        q.answerDraft = "";
                    }
                    dao.saveQuestion(q);
                    return null;
                },
                cb);
    }

    public void saveMaterial(
            String sessionId,
            String original,
            String summary,
            String supplement,
            boolean publish,
            Callback<Void> cb) {
        execute(
                () -> {
                    LessonRow l = requireLesson(sessionId);
                    if (publish && !Models.ENDED.equals(l.status))
                        throw new IllegalStateException("수업을 종료한 뒤 복습 자료를 공개해 주세요.");
                    String o = clean(original), s = clean(summary), p = clean(supplement);
                    if (publish && o.isEmpty())
                        throw new IllegalArgumentException("공개할 전체 강의 원문을 입력해 주세요.");
                    if (o.length() > 1000000 || s.length() > 100000 || p.length() > 100000)
                        throw new IllegalArgumentException("자료가 너무 길어요. 내용을 나누어 정리해 주세요.");
                    MaterialRow m = dao.material(l.id);
                    if (m == null) {
                        m = new MaterialRow();
                        m.sessionId = l.id;
                    }
                    m.draftOriginal = o;
                    m.draftSummary = s;
                    m.draftSupplement = p;
                    m.hasDraft = true;
                    m.updatedAt = System.currentTimeMillis();
                    if (publish) {
                        m.original = o;
                        m.summary = s;
                        m.supplement = p;
                        m.published = true;
                        m.version++;
                        m.hasDraft = false;
                    }
                    dao.saveMaterial(m);
                    return null;
                },
                cb);
    }

    public void downloadMaterial(String sessionId, String uid, Callback<Void> cb) {
        execute(
                () -> {
                    requireMember(sessionId, uid);
                    MaterialRow m = dao.material(sessionId);
                    if (m == null || !m.published || m.original.isEmpty())
                        throw new IllegalStateException("교수자가 공개한 전체 원문이 아직 없어요.");
                    DownloadRow d = new DownloadRow();
                    d.id = stableId(uid, sessionId);
                    d.sessionId = sessionId;
                    d.studentId = uid;
                    d.original = m.original;
                    d.summary = m.summary;
                    d.supplement = m.supplement;
                    d.version = m.version;
                    d.downloadedAt = System.currentTimeMillis();
                    dao.saveDownload(d);
                    return null;
                },
                cb);
    }

    public void clearDownloads(String uid, Callback<Void> cb) {
        execute(
                () -> {
                    requireStudent(uid);
                    dao.clearDownloads(uid);
                    return null;
                },
                cb);
    }

    public void saveQuiz(
            String existingId,
            String sessionId,
            String topicId,
            String title,
            String question,
            List<String> options,
            int correctIndex,
            String explanation,
            boolean publish,
            Callback<String> cb) {
        execute(
                () -> {
                    LessonRow l = requireLesson(sessionId);
                    requireTopic(l.id, topicId);
                    if (publish && !Models.ENDED.equals(l.status))
                        throw new IllegalStateException("복습 퀴즈는 수업 종료 후 공개해 주세요.");
                    String name = required(title, "퀴즈 제목을 입력해 주세요."),
                            qtext = required(question, "문제 내용을 입력해 주세요.");
                    if (name.length() > 100 || qtext.length() > 2000)
                        throw new IllegalArgumentException("퀴즈 제목이나 문제가 너무 길어요.");
                    if (options == null || options.size() < 2 || options.size() > 6)
                        throw new IllegalArgumentException("보기를 2개 이상, 6개 이하로 입력해 주세요.");
                    List<String> os = new ArrayList<>();
                    for (String option : options) {
                        String opt = required(option, "빈 보기를 확인해 주세요.");
                        if (opt.length() > 500)
                            throw new IllegalArgumentException("보기는 500자까지 입력할 수 있어요.");
                        os.add(opt);
                    }
                    if (correctIndex < 0 || correctIndex >= os.size())
                        throw new IllegalArgumentException("정답 보기를 선택해 주세요.");
                    String explain = clean(explanation);
                    if (publish && explain.isEmpty())
                        throw new IllegalArgumentException("학생이 확인할 해설을 입력해 주세요.");
                    QuizRow q = clean(existingId).isEmpty() ? null : dao.quiz(existingId);
                    if (!clean(existingId).isEmpty() && q == null)
                        throw new IllegalArgumentException("편집할 퀴즈를 찾지 못했어요.");
                    if (q != null && !q.sessionId.equals(l.id))
                        throw new IllegalStateException("다른 수업의 퀴즈는 수정할 수 없어요.");
                    if (q == null) {
                        q = new QuizRow();
                        q.id = id();
                        q.sessionId = l.id;
                        q.topicId = topicId;
                        q.createdAt = System.currentTimeMillis();
                    }
                    boolean changed =
                            !q.title.equals(name)
                                    || !q.question.equals(qtext)
                                    || !q.optionsJson.equals(json(os))
                                    || q.correctIndex != correctIndex
                                    || !q.explanation.equals(explain)
                                    || !q.topicId.equals(topicId);
                    if (publish && q.published && changed && dao.attemptCount(q.id) > 0)
                        throw new IllegalStateException(
                                "이미 응답이 있는 퀴즈예요. 기존 응답을 보존하려면 새 퀴즈로 만들어 주세요.");
                    q.draftTitle = name;
                    q.draftQuestion = qtext;
                    q.draftOptionsJson = json(os);
                    q.draftCorrectIndex = correctIndex;
                    q.draftExplanation = explain;
                    q.hasDraft = true;
                    if (!q.published || publish) {
                        q.topicId = topicId;
                        q.title = name;
                        q.question = qtext;
                        q.optionsJson = json(os);
                        q.correctIndex = correctIndex;
                        q.explanation = explain;
                    }
                    if (publish) {
                        if (!q.published || changed) q.version++;
                        q.published = true;
                        q.hasDraft = false;
                    }
                    dao.saveQuiz(q);
                    return q.id;
                },
                cb);
    }

    public void submitAttempt(
            String sessionId, String quizId, String uid, int choice, Callback<Void> cb) {
        execute(
                () -> {
                    LessonRow l = requireLesson(sessionId);
                    requireMember(l.id, uid);
                    if (!Models.ENDED.equals(l.status))
                        throw new IllegalStateException("수업이 끝난 뒤 복습 퀴즈에 참여해 주세요.");
                    QuizRow q = dao.quiz(quizId);
                    if (q == null || !q.sessionId.equals(l.id) || !q.published)
                        throw new IllegalStateException("공개된 복습 퀴즈를 선택해 주세요.");
                    List<String> options = parse(q.optionsJson);
                    if (choice < -1 || choice >= options.size())
                        throw new IllegalArgumentException("보기 또는 모르겠어요를 선택해 주세요.");
                    if (dao.attempt(uid, q.id) != null) return null;
                    AttemptRow a = new AttemptRow();
                    a.id = stableId(uid, q.id);
                    a.sessionId = l.id;
                    a.quizId = q.id;
                    a.studentId = uid;
                    a.choice = choice;
                    a.correct = choice == q.correctIndex;
                    a.quizVersion = q.version;
                    a.correctIndex = q.correctIndex;
                    a.question = q.question;
                    a.optionsJson = q.optionsJson;
                    a.explanation = q.explanation;
                    a.createdAt = System.currentTimeMillis();
                    dao.insertAttempt(a);
                    return null;
                },
                cb);
    }

    private static String json(List<String> values) {
        return new JSONArray(values).toString();
    }

    private static List<String> parse(String value) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(value);
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (JSONException ignored) {
            /* Only locally generated JSON is stored. */
        }
        return out;
    }

    private void seedDemo() {
        if (dao.countLessons() != 0) return;
        String[] names = {"조건부확률", "독립사건", "베이즈 정리", "예제 풀이"};
        long now = System.currentTimeMillis();
        for (int index = 0; index < 2; index++) {
            LessonRow l = new LessonRow();
            l.id = index == 0 ? "demo-session-current" : "demo-session-review";
            l.title = "통계학개론 · " + (index == 0 ? "3주차" : "2주차");
            l.description = index == 0 ? "수업을 준비하고 대주제별로 소통해 보세요." : "공개 원문·질문·퀴즈를 확인하는 시연용 예시 수업";
            l.code = index == 0 ? "4821" : "3759";
            l.teacherId = Models.TEACHER_ID;
            l.status = index == 0 ? Models.READY : Models.ENDED;
            l.createdAt = now - index * 604800000L;
            dao.insertLesson(l);
            List<TopicRow> ts = new ArrayList<>();
            for (int n = 0; n < names.length; n++) {
                TopicRow t = new TopicRow();
                t.id = l.id + "-topic-" + n;
                t.sessionId = l.id;
                t.title = names[n];
                t.position = n;
                t.completed = index == 1;
                ts.add(t);
            }
            dao.insertTopics(ts);
            if (index == 1) {
                l.currentTopicId = ts.get(3).id;
                dao.updateLesson(l);
                MemberRow m = new MemberRow();
                m.sessionId = l.id;
                m.studentId = Models.STUDENT_ID;
                m.joinedAt = l.createdAt;
                dao.insertMember(m);
                MaterialRow material = new MaterialRow();
                material.sessionId = l.id;
                material.published = true;
                material.version = 1;
                material.updatedAt = l.createdAt;
                material.original =
                        "[시연용 예시 원문]\n이 자료는 실제 녹음이나 STT 결과가 아닌 UI·복습 기능 확인용 예시입니다.\n\n01 조건부확률\n어떤 사건이 일어났다는 조건 아래에서 다른 사건이 일어날 확률을 살펴봅니다. 조건이 주어지면 표본공간이 달라집니다. P(A|B)는 사건 B가 일어난 경우 중 A도 일어나는 경우를 뜻합니다. 분모 P(B)는 조건에 해당하는 전체 확률이고, 분자 P(A∩B)는 두 사건이 동시에 일어나는 확률입니다.\n\n예를 들어 카드 한 장이 빨간색이라는 정보를 받았다면 전체 카드가 아니라 빨간 카드만을 기준으로 생각합니다. 조건을 무시한 확률과 조건이 주어진 확률은 구별해야 합니다.\n\n02 독립사건\n두 사건이 독립이면 한 사건이 일어났다는 사실이 다른 사건의 확률에 영향을 주지 않습니다. P(A|B)=P(A)가 성립할 때 독립이라고 할 수 있습니다. 두 사건이 겹치지 않는다는 뜻과 독립이라는 뜻은 다릅니다. 독립성은 사건의 관계를 확인한 다음 판단해야 합니다.\n\n동전을 두 번 던지는 상황을 생각해 봅시다. 첫 번째 결과가 두 번째 결과의 확률에 영향을 주지 않는다는 가정 아래 각각의 확률을 곱할 수 있습니다. 실제 문제에서는 이 가정이 적절한지 먼저 확인합니다.\n\n03 베이즈 정리\n관찰한 결과로부터 원인의 확률을 다시 계산합니다. 양성 판정을 받은 사람이 실제 환자일 확률을 예로 들어 봅니다. 분모는 양성 결과가 나온 모든 경우의 확률입니다. 실제 환자의 양성뿐 아니라 질병이 없는 사람의 위양성도 포함합니다.\n\n전체 양성 확률을 실제 환자의 양성과 위양성으로 나눈 뒤 더합니다. 검사 정확도가 높아도 질병이 드물면 위양성의 비중을 무시할 수 없습니다. 문제에 주어진 비율의 기준 집단을 먼저 확인하고 계산합니다.\n\n04 예제 풀이\n실제 환자 비율, 민감도, 위양성 비율을 각각 확인합니다. 환자 중 양성과 환자가 아닌 사람 중 양성은 서로 다른 사건입니다. 두 사건을 더한 값이 전체 양성 확률입니다. 베이즈 정리의 분자는 실제 환자이면서 양성인 확률이고 분모는 전체 양성 확률입니다.\n\n답을 계산한 뒤 0과 1 사이의 값인지, 문제의 질문과 조건을 올바르게 반영했는지 확인합니다. 궁금한 내용은 해당 대주제에 질문으로 남기고 교수자의 답변과 함께 다시 살펴볼 수 있습니다.\n\n[예시 원문 끝]";
                material.summary =
                        "[직접 작성한 시연용 예시 요약]\n조건부확률은 조건에 따라 표본공간을 바꾸어 생각합니다. 독립사건은 한 사건이 다른 사건의 확률에 영향을 주지 않는 관계입니다. 베이즈 정리는 관찰된 결과를 바탕으로 원인의 확률을 구합니다.";
                material.supplement =
                        "[교수 보충 설명 예시]\n전체 양성 확률에는 실제 양성과 위양성이 모두 포함됩니다. 두 경우를 구분한 뒤 더해 분모를 계산해 보세요.";
                dao.saveMaterial(material);
                QuestionRow q = new QuestionRow();
                q.id = "demo-review-question";
                q.sessionId = l.id;
                q.topicId = ts.get(2).id;
                q.studentId = Models.STUDENT_ID;
                q.text = "양성 확률을 구할 때 질병이 없는 사람의 양성 결과도 분모에 포함하나요?";
                q.answer = "네. 분모는 양성 결과가 나온 모든 경우의 확률입니다. 실제 환자의 양성과 위양성을 모두 포함합니다.";
                q.published = true;
                q.status = Models.ANSWERED;
                q.answerVersion = 1;
                q.createdAt = q.updatedAt = q.submittedAt = q.answeredAt = l.createdAt;
                dao.saveQuestion(q);
                QuizRow quiz = new QuizRow();
                quiz.id = "demo-review-quiz";
                quiz.sessionId = l.id;
                quiz.topicId = ts.get(2).id;
                quiz.title = "전체 양성 확률 확인";
                quiz.question = "전체 양성 확률에 포함되는 것은 무엇인가요?";
                quiz.optionsJson =
                        json(Arrays.asList("실제 환자의 양성만", "실제 환자의 양성과 위양성", "질병이 없는 사람의 음성만"));
                quiz.correctIndex = 1;
                quiz.explanation = "양성 결과가 나온 모든 경우를 포함해야 하므로 실제 환자의 양성과 위양성을 더합니다.";
                quiz.published = true;
                quiz.version = 1;
                quiz.createdAt = l.createdAt;
                dao.saveQuiz(quiz);
            }
        }
    }

    private static List<Models.Lesson> mapGraphs(List<LessonGraph> graphs) {
        List<Models.Lesson> result = new ArrayList<>();
        if (graphs == null) return result;
        for (LessonGraph g : graphs) {
            LessonRow e = g.lesson;
            Models.Lesson l = new Models.Lesson();
            l.id = e.id;
            l.title = e.title;
            l.description = e.description;
            l.code = e.code;
            l.teacherId = e.teacherId;
            l.status = e.status;
            l.currentTopicId = e.currentTopicId;
            l.currentVisitId = e.currentVisitId;
            l.resumeTopicId = e.resumeTopicId;
            l.createdAt = e.createdAt;
            l.questionsOpen = e.questionsOpen;
            for (TopicRow r : g.topics) {
                Models.Topic x = new Models.Topic();
                x.id = r.id;
                x.sessionId = r.sessionId;
                x.parentTopicId = r.parentTopicId;
                x.title = r.title;
                x.position = r.position;
                x.completed = r.completed;
                l.topics.add(x);
            }
            l.topics.sort(Comparator.comparingInt(x -> x.position));
            for (VisitRow r : g.visits) {
                Models.Visit x = new Models.Visit();
                x.id = r.id;
                x.sessionId = r.sessionId;
                x.topicId = r.topicId;
                x.startedAt = r.startedAt;
                x.endedAt = r.endedAt;
                x.revisit = r.revisit;
                l.visits.add(x);
            }
            l.visits.sort(Comparator.comparingLong(x -> x.startedAt));
            for (MemberRow r : g.members) {
                Models.Membership x = new Models.Membership();
                x.sessionId = r.sessionId;
                x.studentId = r.studentId;
                x.joinedAt = r.joinedAt;
                l.members.add(x);
            }
            for (QuestionRow r : g.questions) {
                Models.Question x = new Models.Question();
                x.id = r.id;
                x.sessionId = r.sessionId;
                x.topicId = r.topicId;
                x.studentId = r.studentId;
                x.text = r.text;
                x.answer = r.answer;
                x.answerDraft = r.answerDraft;
                x.status = r.status;
                x.published = r.published;
                x.answerVersion = r.answerVersion;
                x.createdAt = r.createdAt;
                x.updatedAt = r.updatedAt;
                x.submittedAt = r.submittedAt;
                x.answeredAt = r.answeredAt;
                l.questions.add(x);
            }
            l.questions.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
            for (ReactionRow r : g.reactions) {
                Models.Reaction x = new Models.Reaction();
                x.id = r.id;
                x.sessionId = r.sessionId;
                x.topicId = r.topicId;
                x.visitId = r.visitId;
                x.studentId = r.studentId;
                x.type = r.type;
                x.createdAt = r.createdAt;
                l.reactions.add(x);
            }
            l.reactions.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
            for (MarkRow r : g.marks) {
                Models.Mark x = new Models.Mark();
                x.id = r.id;
                x.sessionId = r.sessionId;
                x.topicId = r.topicId;
                x.studentId = r.studentId;
                x.keyword = r.keyword;
                x.createdAt = r.createdAt;
                l.marks.add(x);
            }
            l.marks.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
            for (MaterialRow r : g.materials) {
                Models.Material x = new Models.Material();
                x.sessionId = r.sessionId;
                x.original = r.original;
                x.summary = r.summary;
                x.supplement = r.supplement;
                x.draftOriginal = r.draftOriginal;
                x.draftSummary = r.draftSummary;
                x.draftSupplement = r.draftSupplement;
                x.hasDraft = r.hasDraft;
                x.published = r.published;
                x.version = r.version;
                x.updatedAt = r.updatedAt;
                l.material = x;
            }
            for (DownloadRow r : g.downloads) {
                Models.Download x = new Models.Download();
                x.id = r.id;
                x.sessionId = r.sessionId;
                x.studentId = r.studentId;
                x.original = r.original;
                x.summary = r.summary;
                x.supplement = r.supplement;
                x.version = r.version;
                x.downloadedAt = r.downloadedAt;
                l.downloads.add(x);
            }
            for (QuizRow r : g.quizzes) {
                Models.Quiz x = new Models.Quiz();
                x.id = r.id;
                x.sessionId = r.sessionId;
                x.topicId = r.topicId;
                x.title = r.title;
                x.question = r.question;
                x.options = Collections.unmodifiableList(parse(r.optionsJson));
                x.correctIndex = r.correctIndex;
                x.explanation = r.explanation;
                x.published = r.published;
                x.hasDraft = r.hasDraft;
                x.draftTitle = r.draftTitle;
                x.draftQuestion = r.draftQuestion;
                x.draftOptions = Collections.unmodifiableList(parse(r.draftOptionsJson));
                x.draftCorrectIndex = r.draftCorrectIndex;
                x.draftExplanation = r.draftExplanation;
                x.version = r.version;
                x.createdAt = r.createdAt;
                l.quizzes.add(x);
            }
            l.quizzes.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
            for (AttemptRow r : g.attempts) {
                Models.Attempt x = new Models.Attempt();
                x.id = r.id;
                x.sessionId = r.sessionId;
                x.quizId = r.quizId;
                x.studentId = r.studentId;
                x.choice = r.choice;
                x.correct = r.correct;
                x.quizVersion = r.quizVersion;
                x.correctIndex = r.correctIndex;
                x.question = r.question;
                x.options = Collections.unmodifiableList(parse(r.optionsJson));
                x.explanation = r.explanation;
                x.createdAt = r.createdAt;
                l.attempts.add(x);
            }
            l.attempts.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
            result.add(l);
        }
        return Collections.unmodifiableList(result);
    }
}
