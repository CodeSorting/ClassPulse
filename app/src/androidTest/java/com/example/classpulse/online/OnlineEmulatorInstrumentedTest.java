package com.example.classpulse.online;

import android.content.*;
import android.graphics.Bitmap;
import androidx.room.Room;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.classpulse.MainActivity;
import com.example.classpulse.R;
import com.example.classpulse.auth.EmulatorAuthScope;
import com.example.classpulse.data.LocalDatabaseScope;
import com.example.classpulse.ui.ScreenContext;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.*;
import com.google.firebase.auth.*;
import com.google.firebase.firestore.*;
import com.google.firebase.functions.FirebaseFunctions;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static org.hamcrest.Matchers.not;
import static androidx.test.espresso.matcher.ViewMatchers.*;

@RunWith(AndroidJUnit4.class)
public class OnlineEmulatorInstrumentedTest {
    private Context context;
    private final List<Client> clients = new ArrayList<>();
    private final List<OnlineTransport.Subscription> watchers = new ArrayList<>();
    private ActivityScenario<MainActivity> scenario;
    private EmulatorAuthScope authScope;
    private LocalDatabaseScope legacyScope;
    private OnlineServices.Factory previousFactory;
    private String previousRole;
    static final class Client {
        FirebaseApp app;
        FirebaseAuth auth;
        FirebaseFirestore firestore;
        OnlineDatabase db;
        OnlineRepository repo;
        FirebaseOnlineTransport transport;
        String uid;
    }

    private void main(Runnable r) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(r);
    }

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (android.os.Build.VERSION.SDK_INT >= 37)
            InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .grantRuntimePermission(
                            context.getPackageName(), "android.permission.ACCESS_LOCAL_NETWORK");
        previousRole = context.getSharedPreferences("local_demo", 0).getString("role", null);
        previousFactory = OnlineServices.testFactory;
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (scenario != null) scenario.close();
        } finally {
            main(() -> {
                OnlineServices.close();
                OnlineServices.testFactory = previousFactory;
                if (authScope != null) authScope.close();
            });
            for (OnlineTransport.Subscription s : watchers) s.close();
            for (Client c : clients) {
                c.repo.close();
                Tasks.await(c.firestore.terminate(), 10, TimeUnit.SECONDS);
                // Firebase Auth's emulator registry keeps references to named apps. Leave these
                // alive until instrumentation exits so the next test class can also use Auth.
            }
            if (legacyScope != null) legacyScope.close();
            SharedPreferences.Editor edit = context.getSharedPreferences("local_demo", 0).edit();
            if (previousRole == null) edit.remove("role");
            else edit.putString("role", previousRole);
            edit.commit();
        }
    }

    private Client client() throws Exception {
        String host =
                InstrumentationRegistry.getArguments()
                        .getString("firebaseEmulatorHost", "10.0.2.2");
        Client c = new Client();
        c.app =
                FirebaseApp.initializeApp(
                        context,
                        new FirebaseOptions.Builder()
                                .setApplicationId("1:123456789:android:abcdef0123456789")
                                .setProjectId("demo-classpulse")
                                // Firebase Installations validates the format even in emulator tests.
                                .setApiKey("A_classpulse_emulator_only_placeholder0")
                                .build(),
                        "online-test-" + OnlineJson.id());
        c.app.setDataCollectionDefaultEnabled(false);
        c.auth = FirebaseAuth.getInstance(c.app);
        c.auth.useEmulator(host, 9099);
        Tasks.await(
                c.auth.createUserWithEmailAndPassword(
                        OnlineJson.id() + "@example.test", "emulator-only-password"),
                30,
                TimeUnit.SECONDS);
        c.uid = c.auth.getCurrentUser().getUid();
        c.transport = new FirebaseOnlineTransport(c.app);
        c.firestore = FirebaseFirestore.getInstance(c.app);
        c.firestore.useEmulator(host, 8080);
        FirebaseFunctions.getInstance(c.app, "asia-northeast3").useEmulator(host, 5001);
        c.db = Room.inMemoryDatabaseBuilder(context, OnlineDatabase.class).build();
        c.repo = new OnlineRepository(context, c.uid, c.db, c.transport, false);
        clients.add(c);
        return c;
    }

    private JSONObject call(Client c, String kind, JSONObject payload) throws Exception {
        return c.transport.call(kind, payload).get(40, TimeUnit.SECONDS);
    }

    private JSONObject mutate(Client c, String kind, JSONObject payload) throws Exception {
        payload.put("operationId", OnlineJson.id());
        return call(c, kind, payload);
    }

    private JSONObject command(Client c, String kind, String sid, JSONObject payload)
            throws Exception {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        c.repo.command(
                kind,
                sid,
                payload,
                new OnlineRepository.Callback() {
                    public void success(JSONObject v) {
                        result.complete(v);
                    }

                    public void error(String r, String m) {
                        result.completeExceptionally(new AssertionError(r + ": " + m));
                    }
                });
        return result.get(60, TimeUnit.SECONDS);
    }

    private JSONObject create(Client teacher) throws Exception {
        String sid = OnlineJson.id(), t1 = OnlineJson.id(), t2 = OnlineJson.id();
        JSONObject result =
                mutate(
                        teacher,
                        "createSession",
                        OnlineJson.values(
                                "sessionId",
                                sid,
                                "title",
                                "온라인 통계학 수업",
                                "description",
                                "",
                                "topics",
                                new JSONArray()
                                        .put(OnlineJson.values("id", t1, "title", "조건부확률"))
                                        .put(OnlineJson.values("id", t2, "title", "베이즈 정리"))));
        return OnlineJson.values("sid", sid, "first", t1, "second", t2, "result", result);
    }

    private void join(Client teacher, Client student, String sid) throws Exception {
        mutate(teacher, "issueInvite", OnlineJson.values("sessionId", sid));
        JSONObject inv = call(teacher, "getInvite", OnlineJson.values("sessionId", sid)),
                preview =
                        call(
                                student,
                                "resolveInvite",
                                OnlineJson.values("kind", "CODE", "value", inv.optString("code")));
        command(
                student,
                "joinSession",
                sid,
                OnlineJson.values("previewToken", preview.optString("previewToken")));
    }

    private void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < end) {
            if (condition.call()) return;
            Thread.sleep(100);
        }
        fail("Timed out waiting for Room/Firebase state");
    }

    private JSONObject cached(Client c, String sid) {
        OnlineDatabase.Session s = c.db.store().session(sid);
        return s == null ? new JSONObject() : OnlineJson.object(s.json);
    }

    @Test
    public void teacherCanRemoveClassFromBothCachedListsAfterConfirmation() throws Exception {
        Client teacher = client(), student = client(), away = client();
        String sid = create(teacher).optString("sid");
        join(teacher, student, sid);
        join(teacher, away, sid);
        watchers.add(student.repo.watchHome());
        OnlineTransport.Subscription firstVisit = away.repo.watchLesson(sid);
        await(() -> cached(student, sid).has("id") && cached(away, sid).has("id"));
        firstVisit.close();
        legacyScope = new LocalDatabaseScope(context);
        context.getSharedPreferences("local_demo", 0).edit().putString("role", "NONE").commit();
        main(() -> {
            authScope = new EmulatorAuthScope(teacher.auth);
            OnlineServices.close();
            OnlineServices.testFactory = (c, uid) -> teacher.repo;
        });
        scenario = ActivityScenario.launch(MainActivity.class);
        await(() -> cached(teacher, sid).has("id"));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withContentDescription("온라인 통계학 수업 수업 관리")).perform(scrollTo(), click());
        onView(withText("수업 삭제")).perform(click());
        onView(withText("취소")).perform(click());
        assertTrue(teacher.db.store().session(sid).accessible);
        onView(withContentDescription("온라인 통계학 수업 수업 관리")).perform(scrollTo(), click());
        onView(withText("수업 삭제")).perform(click());
        onView(withText("삭제")).perform(click());
        await(() -> !teacher.db.store().session(sid).accessible
                && !student.db.store().session(sid).accessible);
        assertTrue(away.db.store().session(sid).accessible);
        // This member opens a previously cached lesson without a home subscription.
        watchers.add(away.repo.watchLesson(sid));
        await(() -> !cached(away, sid).isNull("deletedAt") && !away.db.store().session(sid).accessible);
        onView(withText("온라인 통계학 수업")).check(
                androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
    }

    @Test
    public void groupedOutlineSurvivesRotationAndRemovedChildLeavesTheCache() throws Exception {
        Client teacher = client();
        legacyScope = new LocalDatabaseScope(context);
        context.getSharedPreferences("local_demo", 0).edit().putString("role", "NONE").commit();
        main(() -> {
            authScope = new EmulatorAuthScope(teacher.auth);
            OnlineServices.close();
            OnlineServices.testFactory = (c, uid) -> teacher.repo;
        });
        scenario = ActivityScenario.launch(MainActivity.class);
        onView(withText("새 수업 만들기")).perform(click());
        onView(withContentDescription("수업명")).perform(scrollTo(), replaceText("소주제 시연"), closeSoftKeyboard());
        onView(withContentDescription("1. 대주제")).perform(scrollTo(), replaceText("표본공간"), closeSoftKeyboard());
        onView(withContentDescription("1. 대주제에 소주제 추가")).perform(scrollTo(), click(), closeSoftKeyboard());
        onView(withContentDescription("1.1 소주제")).perform(scrollTo(), replaceText("사건의 정의"), closeSoftKeyboard());
        onView(withContentDescription("1. 대주제에 소주제 추가")).perform(scrollTo(), click(), closeSoftKeyboard());
        onView(withContentDescription("1.2 소주제")).perform(scrollTo(), replaceText("사건의 연산"), closeSoftKeyboard());
        scenario.recreate();
        onView(withContentDescription("1.2 소주제")).check(matches(withText("사건의 연산")));
        onView(withContentDescription("1.1 소주제")).perform(scrollTo());
        capture("grouped-outline-editor");
        onView(withText("수업 만들기")).perform(click());
        AtomicReference<String> sid = new AtomicReference<>();
        await(() -> {
            scenario.onActivity(a -> {
                androidx.fragment.app.Fragment fragment = a.getSupportFragmentManager().findFragmentById(R.id.main);
                if (fragment != null && "online_session".equals(fragment.getTag()))
                    sid.set(ScreenContext.from(fragment.getArguments()).lessonId);
            });
            return sid.get() != null && cached(teacher, sid.get()).optInt("topicCount") == 3;
        });
        JSONObject bootstrap = call(teacher, "getSessionBootstrap", OnlineJson.values("sessionId", sid.get()));
        JSONArray topics = bootstrap.getJSONArray("topics");
        String mainId = topics.getJSONObject(0).getString("id");
        assertEquals(mainId, topics.getJSONObject(2).getString("parentTopicId"));
        String removedId = topics.getJSONObject(2).getString("id");
        await(() -> teacher.db.store().document(sid.get(), "TOPIC", removedId) != null);
        onView(withText("목차 편집")).perform(scrollTo(), click());
        onView(withContentDescription("1.2 소주제 더보기")).perform(scrollTo(), click());
        onView(withText("소주제 삭제")).perform(click());
        onView(withText("삭제")).perform(click());
        onView(withText("수업 정보 저장")).perform(click());
        await(() -> cached(teacher, sid.get()).optInt("topicCount") == 2
                && teacher.db.store().document(sid.get(), "TOPIC", removedId) == null);
        onView(withText("1.1 사건의 정의")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withText("1.2 사건의 연산")).check(
                androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
    }

    private void capture(String name) throws Exception {
        Bitmap b = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(b);
        File dir = new File(context.getExternalFilesDir(null), "verification");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            b.recycle();
        }
    }

    private OnlineTransport gate(Client c, AtomicBoolean offline) {
        return new OnlineTransport() {
            public boolean authenticatedAs(String uid) {
                return c.transport.authenticatedAs(uid);
            }

            public CompletableFuture<JSONObject> call(String command, JSONObject p) {
                if (offline.get()) {
                    CompletableFuture<JSONObject> out = new CompletableFuture<>();
                    out.completeExceptionally(new Failure("NETWORK", "테스트 연결 끊김"));
                    return out;
                }
                return c.transport.call(command, p);
            }

            public CompletableFuture<JSONObject> receipt(String u, String o) {
                return c.transport.receipt(u, o);
            }

            public CompletableFuture<JSONObject> page(
                    String u, String s, String k, boolean o, String l) {
                return c.transport.page(u, s, k, o, l);
            }

            public Subscription watchHome(
                    String u, java.util.function.Consumer<String> cb, Sink s) {
                return c.transport.watchHome(u, cb, s);
            }

            public Subscription watchLesson(String u, String s, boolean owner, Sink sink) {
                return c.transport.watchLesson(u, s, owner, sink);
            }
        };
    }

    @Test
    public void
            twoFirebaseClientsExchangeProgressFourReactionsQuestionAndPublishedAnswerThroughRoom()
                    throws Exception {
        Client teacher = client(), student = client();
        JSONObject fixture = create(teacher);
        String sid = fixture.optString("sid"), topic = fixture.optString("first");
        join(teacher, student, sid);
        watchers.add(teacher.repo.watchLesson(sid));
        watchers.add(student.repo.watchLesson(sid));
        await(() -> cached(student, sid).has("progressVersion"));
        command(
                teacher,
                "changeProgress",
                sid,
                OnlineJson.values(
                        "sessionId", sid, "action", "START", "expectedProgressVersion", 0));
        await(() -> "LIVE".equals(cached(student, sid).optString("status")));
        String visit = cached(student, sid).getJSONObject("progress").optString("currentVisitId");
        for (String type : new String[] {"UNDERSTANDING", "SPEED", "TERM", "CALCULATION"}) {
            CompletableFuture<JSONObject> r = new CompletableFuture<>();
            student.repo.background(
                    "submitReaction",
                    sid,
                    OnlineJson.values(
                            "sessionId", sid, "topicId", topic, "visitId", visit, "type", type),
                    new OnlineRepository.Callback() {
                        public void success(JSONObject v) {
                            r.complete(v);
                        }

                        public void error(String c, String m) {
                            r.completeExceptionally(new AssertionError(c + ":" + m));
                        }
                    });
            r.get(40, TimeUnit.SECONDS);
        }
        await(() -> {
            OnlineDatabase.Document d = teacher.db.store().document(sid, "STATS", "summary");
            return d != null && OnlineJson.object(d.json).optInt("reactionEventCount") == 4;
        });
        String qid = OnlineJson.id();
        student.repo.saveDraft(sid, qid, topic, "조건부확률의 분모가 궁금해요", false, null);
        await(() -> {
            OnlineDatabase.Draft d = student.db.store().draft(sid, "QUESTION_DRAFT", qid);
            return d != null && !d.dirty;
        });
        assertNull(teacher.db.store().document(sid, "QUESTION", qid));
        command(
                teacher,
                "changeProgress",
                sid,
                OnlineJson.values(
                        "sessionId", sid, "action", "COMPLETE_NEXT", "expectedProgressVersion", 1));
        student.repo.saveDraft(sid, qid, topic, "조건부확률의 분모가 궁금해요", true, null);
        await(() -> teacher.db.store().document(sid, "QUESTION", qid) != null);
        assertEquals(
                topic,
                OnlineJson.object(teacher.db.store().document(sid, "QUESTION", qid).json)
                        .optString("topicId"));
        mutate(
                teacher,
                "saveAnswerDraft",
                OnlineJson.values(
                        "sessionId",
                        sid,
                        "id",
                        qid,
                        "text",
                        "교수 미공개 원고",
                        "baseRevision",
                        0,
                        "baseAnswerVersion",
                        0));
        assertNull(student.db.store().document(sid, "ANSWER_DRAFT", "answer_" + qid));
        mutate(
                teacher,
                "publishAnswer",
                OnlineJson.values(
                        "sessionId",
                        sid,
                        "id",
                        qid,
                        "text",
                        "분모는 조건 사건의 확률입니다.",
                        "baseRevision",
                        1,
                        "baseAnswerVersion",
                        0));
        await(() -> {
            OnlineDatabase.Document d = student.db.store().document(sid, "QUESTION", qid);
            return d != null && OnlineJson.object(d.json).optInt("answerVersion") == 1;
        });
        assertEquals(
                "분모는 조건 사건의 확률입니다.",
                OnlineJson.object(student.db.store().document(sid, "QUESTION", qid).json)
                        .optString("answer"));
    }

    private void showSession(Client account, String sid) throws Exception {
        legacyScope = new LocalDatabaseScope(context);
        context.getSharedPreferences("local_demo", 0).edit().putString("role", "NONE").commit();
        main(() -> {
            authScope = new EmulatorAuthScope(account.auth);
            OnlineServices.close();
            OnlineServices.testFactory = (c, uid) -> {
                assertEquals(account.uid, uid);
                return account.repo;
            };
        });
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(a -> a.openOnline("online_home"));
        scenario.onActivity(a -> a.navigate("online_session", ScreenContext.empty().forLesson(sid)));
        await(() -> cached(account, sid).has("progressVersion"));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void teacherEndsLastTopicAndTheEndedOutlineStaysHiddenAfterRecreation() throws Exception {
        Client teacher = client(), student = client();
        JSONObject fixture = create(teacher);
        String sid = fixture.optString("sid"), last = fixture.optString("second");
        join(teacher, student, sid);
        watchers.add(student.repo.watchLesson(sid));
        mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "START", "expectedProgressVersion", 0));
        mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "COMPLETE_NEXT", "expectedProgressVersion", 1));
        showSession(teacher, sid);
        await(() -> hasText("수업 목차"));
        onView(withText("수업 종료")).perform(scrollTo(), click());
        onView(withText("종료")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
        await(() -> "ENDED".equals(cached(teacher, sid).optString("status"))
                && "ENDED".equals(cached(student, sid).optString("status")));
        assertEndedSessionScreen();
        JSONObject progress = cached(student, sid).getJSONObject("progress");
        assertTrue(OnlineJson.contains(progress.getJSONArray("completedTopicIds"), last));
        assertTrue(progress.isNull("currentTopicId"));
        assertTrue(progress.isNull("currentVisitId"));
        assertTrue(progress.isNull("resumeTopicId"));
        scenario.recreate();
        await(() -> hasText("온라인 통계학 수업"));
        assertEndedSessionScreen();
        capture("online-teacher-ended-no-outline");
    }

    @Test
    public void studentSeesEndedStateWithoutOutlineAndCanStillSubmitAQuestion() throws Exception {
        Client teacher = client(), student = client();
        JSONObject fixture = create(teacher);
        String sid = fixture.optString("sid"), first = fixture.optString("first");
        join(teacher, student, sid);
        watchers.add(teacher.repo.watchLesson(sid));
        mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "START", "expectedProgressVersion", 0));
        showSession(student, sid);
        await(() -> hasEnabledReaction("이해됐어요"));
        mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "END", "expectedProgressVersion", 1));
        await(() -> "ENDED".equals(cached(student, sid).optString("status")));
        assertEndedSessionScreen();
        onView(withText("이해됐어요")).check(doesNotExist());
        JSONObject progress = cached(student, sid).getJSONObject("progress");
        assertTrue(OnlineJson.contains(progress.getJSONArray("completedTopicIds"), first));
        assertFalse(OnlineJson.contains(progress.getJSONArray("completedTopicIds"), fixture.optString("second")));
        scenario.recreate();
        await(() -> hasText("온라인 통계학 수업"));
        assertEndedSessionScreen();
        capture("online-student-ended-no-outline");
        onView(withText("질문하기")).perform(scrollTo(), click());
        onView(withContentDescription("질문 내용 · 1,000자 이내"))
                .perform(scrollTo(), click(), replaceText("종료 후에도 첫 주제에 질문해요"), closeSoftKeyboard());
        AtomicReference<String> question = new AtomicReference<>();
        scenario.onActivity(activity -> question.set(ScreenContext.from(activity.getSupportFragmentManager()
                .findFragmentById(R.id.main).getArguments()).questionId));
        onView(withText("질문 제출")).perform(click());
        await(() -> teacher.db.store().document(sid, "QUESTION", question.get()) != null);
        JSONObject submitted = OnlineJson.object(teacher.db.store().document(sid, "QUESTION", question.get()).json);
        assertEquals(first, submitted.optString("topicId"));
        assertEquals("종료 후에도 첫 주제에 질문해요", submitted.optString("text"));
        assertEndedSessionScreen();
    }

    private void assertEndedSessionScreen() throws Exception {
        await(() -> {
            try {
                onView(withText("수업 종료 · 질문과 답변을 이어가요")).perform(scrollTo());
                return true;
            } catch (androidx.test.espresso.NoMatchingViewException | AssertionError error) {
                return false;
            }
        });
        onView(withText("수업 목차")).check(doesNotExist());
        onView(withText("진행 중")).check(doesNotExist());
        onView(withText("마지막 대주제 완료")).check(doesNotExist());
    }

    @Test
    public void liveComposerSendsSelectedMajorAndKeepsDraftWhileIntakeIsClosed() throws Exception {
        Client teacher = client(), student = client();
        JSONObject fixture = create(teacher);
        String sid = fixture.optString("sid");
        join(teacher, student, sid);
        JSONObject started = mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "START", "expectedProgressVersion", 0));
        showSession(student, sid);
        onView(withId(R.id.student_question_topic)).perform(click());
        onView(withContentDescription("질문 대주제 2, 베이즈 정리")).perform(click());
        onView(withId(R.id.student_question_input)).perform(replaceText("이전 주제와 어떤 관계인가요?"), closeSoftKeyboard());
        onView(withId(R.id.student_question_send)).perform(click());
        await(() -> Tasks.await(teacher.firestore.collection("sessions/" + sid + "/questions")
                .get(Source.SERVER), 10, TimeUnit.SECONDS).size() == 1);
        QuerySnapshot submitted = Tasks.await(teacher.firestore.collection("sessions/" + sid + "/questions")
                .get(Source.SERVER), 10, TimeUnit.SECONDS);
        JSONObject received = OnlineJson.json(submitted.getDocuments().get(0).getData());
        assertEquals(fixture.optString("second"), received.optString("topicId"));
        assertEquals("이전 주제와 어떤 관계인가요?", received.optString("text"));
        assertEquals("SUBMITTED", received.optString("status"));
        onView(withId(R.id.student_question_input)).check(matches(withText("")));
        onView(withId(R.id.student_question_input)).perform(replaceText("마감 중에도 남아야 할 질문"), closeSoftKeyboard());
        JSONObject closed = mutate(teacher, "setQuestionsOpen", OnlineJson.values(
                "sessionId", sid, "baseRevision", started.getJSONObject("session").optLong("revision"), "open", false));
        await(() -> !cached(student, sid).optBoolean("questionsOpen", true));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withId(R.id.student_question_send)).check(matches(not(isEnabled())));
        onView(withId(R.id.student_question_input)).check(matches(withText("마감 중에도 남아야 할 질문")));
        scenario.recreate();
        onView(withId(R.id.student_question_input)).check(matches(withText("마감 중에도 남아야 할 질문")));
        mutate(teacher, "setQuestionsOpen", OnlineJson.values(
                "sessionId", sid, "baseRevision", closed.getJSONObject("session").optLong("revision"), "open", true));
        await(() -> cached(student, sid).optBoolean("questionsOpen"));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withId(R.id.student_question_send)).check(matches(isEnabled()));
    }

    @Test
    public void studentLastTopicHasNoAdvanceButtonAndSharedIntakeDisablesAllReactions()
            throws Exception {
        Client teacher = client(), student = client();
        JSONObject f = create(teacher);
        String sid = f.optString("sid");
        join(teacher, student, sid);
        mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "START", "expectedProgressVersion", 0));
        JSONObject last = mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "COMPLETE_NEXT", "expectedProgressVersion", 1));
        showSession(student, sid);
        await(() -> {
            JSONObject p = cached(student, sid).optJSONObject("progress");
            return p != null && f.optString("second").equals(p.optString("currentTopicId"));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withText("완료하고 다음 주제")).check(doesNotExist());
        onView(withText("다음 대주제로 넘어가기")).check(doesNotExist());
        onView(withText("마지막 대주제 완료")).check(doesNotExist());
        for (String label : new String[] {"이해가 안 돼요", "너무 빨라요", "용어가 어려워요", "계산을 놓쳤어요", "이해됐어요"})
            onView(withText(label)).check(matches(isEnabled()));
        JSONObject closed = mutate(teacher, "setQuestionsOpen", OnlineJson.values(
                "sessionId", sid, "baseRevision", last.getJSONObject("session").optLong("revision"), "open", false));
        await(() -> !cached(student, sid).optBoolean("questionsOpen", true));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        for (String label : new String[] {"이해가 안 돼요", "너무 빨라요", "용어가 어려워요", "계산을 놓쳤어요", "이해됐어요"})
            onView(withText(label)).check(matches(not(isEnabled())));
        mutate(teacher, "setQuestionsOpen", OnlineJson.values(
                "sessionId", sid, "baseRevision", closed.getJSONObject("session").optLong("revision"), "open", true));
        await(() -> cached(student, sid).optBoolean("questionsOpen"));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        for (String label : new String[] {"이해가 안 돼요", "너무 빨라요", "용어가 어려워요", "계산을 놓쳤어요", "이해됐어요"})
            onView(withText(label)).check(matches(isEnabled()));
    }

    @Test
    public void professorCompletesTheLastTopicWithoutShowingANextTopicAction() throws Exception {
        Client teacher = client();
        JSONObject f = create(teacher);
        String sid = f.optString("sid");
        mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "START", "expectedProgressVersion", 0));
        mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "COMPLETE_NEXT", "expectedProgressVersion", 1));
        showSession(teacher, sid);
        await(() -> teacher.db.store().document(sid, "TOPIC", f.optString("second")) != null);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withText("완료하고 다음 주제")).check(doesNotExist());
        onView(withText("마지막 대주제 완료")).perform(click());
        await(() -> {
            JSONObject p = cached(teacher, sid).optJSONObject("progress");
            return p != null && p.isNull("currentVisitId");
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withText("완료하고 다음 주제")).check(doesNotExist());
        onView(withText("마지막 대주제 완료")).check(doesNotExist());
        onView(withText("재설명 마치고 원래 주제로")).check(doesNotExist());
    }

    @Test
    public void understoodReactionCanBeCanceledAndAppearsInTeacherStatsAndStudentRecords() throws Exception {
        Client teacher = client(), student = client();
        String sid = create(teacher).optString("sid");
        join(teacher, student, sid);
        watchers.add(teacher.repo.watchLesson(sid));
        mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "START", "expectedProgressVersion", 0));
        showSession(student, sid);

        String label = "이해됐어요", selected = "✓ " + label;
        await(() -> teacherReactionCount(teacher, sid) == 0 && hasEnabledReaction(label));
        onView(withText(label)).perform(click());
        await(() -> teacherReactionCount(teacher, sid) == 1 && hasEnabledReaction(selected));
        onView(withText(selected)).perform(click());
        await(() -> teacherReactionCount(teacher, sid) == 0 && hasEnabledReaction(label));
        onView(withText(selected)).check(doesNotExist());
        scenario.recreate();
        await(() -> hasEnabledReaction(label));
        onView(withText(selected)).check(doesNotExist());
        onView(withText(label)).perform(click());
        await(() -> teacherReactionCount(teacher, sid) == 1 && hasEnabledReaction(selected));
        JSONObject counts = OnlineJson.object(teacher.db.store().document(sid, "STATS", "summary").json)
                .getJSONObject("eventCountsByType");
        assertEquals(1, counts.getInt("UNDERSTOOD"));
        assertEquals(0, counts.optInt("UNDERSTANDING"));

        scenario.onActivity(a -> a.navigate("online_records", ScreenContext.empty().forLesson(sid)));
        await(() -> hasEnabledReaction("내 반응"));
        clickStableText("내 반응");
        await(() -> hasTextInHierarchy(label));
        onView(withText(label)).perform(scrollTo()).check(matches(isDisplayed()));
    }

    @Test
    public void teacherSwipesBetweenMajorTopicReactionsAndKeepsSelectionAcrossUpdates() throws Exception {
        Client teacher = client(), student = client();
        String sid = OnlineJson.id(), major = OnlineJson.id(), first = OnlineJson.id(),
                second = OnlineJson.id(), next = OnlineJson.id(), empty = OnlineJson.id();
        mutate(teacher, "createSession", OnlineJson.values(
                "sessionId", sid, "title", "대주제별 반응 테스트", "description", "",
                "topics", new JSONArray()
                        .put(OnlineJson.values("id", major, "title", "확률 기초"))
                        .put(OnlineJson.values("id", first, "title", "확률의 정의", "parentTopicId", major))
                        .put(OnlineJson.values("id", second, "title", "독립 사건", "parentTopicId", major))
                        .put(OnlineJson.values("id", next, "title", "베이즈 정리"))
                        .put(OnlineJson.values("id", empty, "title", "분류"))));
        join(teacher, student, sid);
        watchers.add(student.repo.watchLesson(sid));
        JSONObject started = mutate(teacher, "changeProgress",
                OnlineJson.values("sessionId", sid, "action", "START", "expectedProgressVersion", 0));
        String visit = started.getJSONObject("session").getJSONObject("progress").getString("currentVisitId");
        mutate(student, "submitReaction", OnlineJson.values("sessionId", sid,
                "topicId", first, "visitId", visit, "type", "UNDERSTANDING"));
        JSONObject advanced = mutate(teacher, "changeProgress",
                OnlineJson.values("sessionId", sid, "action", "COMPLETE_NEXT", "expectedProgressVersion", 1));
        visit = advanced.getJSONObject("session").getJSONObject("progress").getString("currentVisitId");
        mutate(student, "submitReaction", OnlineJson.values("sessionId", sid,
                "topicId", second, "visitId", visit, "type", "UNDERSTOOD"));
        advanced = mutate(teacher, "changeProgress",
                OnlineJson.values("sessionId", sid, "action", "COMPLETE_NEXT", "expectedProgressVersion", 2));
        String nextVisit = advanced.getJSONObject("session").getJSONObject("progress").getString("currentVisitId");
        mutate(student, "submitReaction", OnlineJson.values("sessionId", sid,
                "topicId", next, "visitId", nextVisit, "type", "SPEED"));
        showSession(teacher, sid);
        await(() -> teacher.db.store().document(sid, "STATS", "topic_" + first) != null
                && teacher.db.store().document(sid, "STATS", "topic_" + second) != null
                && teacher.db.store().document(sid, "STATS", "topic_" + next) != null);
        onView(withId(R.id.reaction_pager)).perform(scrollTo());
        onView(withText("2 / 3")).check(matches(isDisplayed()));
        assertReactionCount("이해가 안 돼요", "0건");
        assertReactionCount("이해됐어요", "0건");
        assertReactionCount("너무 빨라요", "1건");
        onView(withId(R.id.reaction_pager)).perform(swipeRight());
        onView(withText("1 / 3")).check(matches(isDisplayed()));
        assertReactionCount("이해가 안 돼요", "1건");
        assertReactionCount("이해됐어요", "1건");
        assertReactionCount("너무 빨라요", "0건");
        onView(withContentDescription("이전 대주제 반응")).check(matches(not(isEnabled())));

        mutate(student, "submitReaction", OnlineJson.values("sessionId", sid,
                "topicId", next, "visitId", nextVisit, "type", "CALCULATION"));
        await(() -> OnlineJson.object(teacher.db.store().document(sid, "STATS", "topic_" + next).json)
                .optInt("reactionEventCount") == 2);
        onView(withId(R.id.reaction_pager)).perform(scrollTo());
        onView(withText("1 / 3")).check(matches(isDisplayed()));
        scenario.recreate();
        onView(withId(R.id.reaction_pager)).perform(scrollTo());
        onView(withText("1 / 3")).check(matches(isDisplayed()));
        onView(withId(R.id.reaction_pager)).perform(swipeLeft());
        assertReactionCount("계산을 놓쳤어요", "1건");
        onView(withContentDescription("다음 대주제 반응")).perform(click());
        onView(withText("3 / 3")).check(matches(isDisplayed()));
        assertReactionCount("이해됐어요", "0건");
        onView(withContentDescription("다음 대주제 반응")).check(matches(not(isEnabled())));
        onView(withContentDescription("이전 대주제 반응")).perform(click());

        JSONObject revisited = mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "REVISIT", "topicId", first, "expectedProgressVersion", 3));
        String revisit = revisited.getJSONObject("session").getJSONObject("progress").getString("currentVisitId");
        mutate(student, "submitReaction", OnlineJson.values("sessionId", sid,
                "topicId", first, "visitId", revisit, "type", "UNDERSTANDING"));
        await(() -> OnlineJson.object(teacher.db.store().document(sid, "STATS", "topic_" + first).json)
                .optInt("reactionEventCount") == 2);
        onView(withId(R.id.reaction_pager)).perform(scrollTo());
        onView(withText("1 / 3")).check(matches(isDisplayed()));
        assertReactionCount("이해가 안 돼요", "2건");
        mutate(student, "setReaction", OnlineJson.values("sessionId", sid,
                "topicId", first, "visitId", revisit, "type", "UNDERSTANDING",
                "selected", false, "baseRevision", 1));
        await(() -> OnlineJson.object(teacher.db.store().document(sid, "STATS", "topic_" + first).json)
                .optInt("reactionEventCount") == 1);
        onView(withId(R.id.reaction_pager)).perform(scrollTo());
        assertReactionCount("이해가 안 돼요", "1건");
        assertReactionCount("이해됐어요", "1건");
        assertNull(student.db.store().document(sid, "STATS", "summary"));
        assertNull(student.db.store().document(sid, "STATS", "topic_" + first));
        capture("teacher-major-topic-reactions");
    }

    private void assertReactionCount(String label, String count) throws Exception {
        await(() -> {
            try {
                onView(org.hamcrest.Matchers.allOf(withId(R.id.value),
                        hasSibling(withText(label)), isCompletelyDisplayed()))
                        .check(matches(withText(count)));
                return true;
            } catch (androidx.test.espresso.NoMatchingViewException
                     | androidx.test.espresso.AmbiguousViewMatcherException | AssertionError error) {
                // A swipe can briefly expose both pages while Firestore updates are rendered.
                return false;
            }
        });
    }

    @Test
    public void studentReferenceLayoutKeepsQuestionTopicAndTextWhileProgressChanges() throws Exception {
        Client teacher = client(), student = client();
        String sid = OnlineJson.id(), root1 = OnlineJson.id(), root2 = OnlineJson.id(), root3 = OnlineJson.id();
        JSONArray topics = new JSONArray()
                .put(OnlineJson.values("id", root1, "title", "신뢰적 데이터 전송"))
                .put(OnlineJson.values("id", OnlineJson.id(), "title", "오류 검출", "parentTopicId", root1))
                .put(OnlineJson.values("id", OnlineJson.id(), "title", "재전송과 타이머", "parentTopicId", root1))
                .put(OnlineJson.values("id", root2, "title", "파이프라인 프로토콜"))
                .put(OnlineJson.values("id", OnlineJson.id(), "title", "Go-Back-N", "parentTopicId", root2))
                .put(OnlineJson.values("id", OnlineJson.id(), "title", "Selective Repeat", "parentTopicId", root2))
                .put(OnlineJson.values("id", root3, "title", "TCP 혼잡 제어"))
                .put(OnlineJson.values("id", OnlineJson.id(), "title", "혼잡 윈도", "parentTopicId", root3))
                .put(OnlineJson.values("id", OnlineJson.id(), "title", "혼잡 제어 알고리즘", "parentTopicId", root3));
        mutate(teacher, "createSession", OnlineJson.values(
                "sessionId", sid, "title", "컴퓨터 네트워크", "description", "", "topics", topics));
        join(teacher, student, sid);
        watchers.add(teacher.repo.watchLesson(sid));
        mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "START", "expectedProgressVersion", 0));
        for (int version = 1; version <= 2; version++)
            mutate(teacher, "changeProgress", OnlineJson.values(
                    "sessionId", sid, "action", "COMPLETE_NEXT", "expectedProgressVersion", version));
        showSession(student, sid);
        await(() -> hasEnabledReaction("이해됐어요"));
        onView(withId(R.id.student_question_caption))
                .check(matches(withText("질문할 대주제 · 2. 파이프라인 프로토콜")));
        onView(withText("이해됐어요")).perform(click());
        await(() -> teacherReactionCount(teacher, sid) == 1 && hasEnabledReaction("✓ 이해됐어요"));
        capture("student-reference-default");
        onView(withId(R.id.student_question_topic)).perform(click());
        capture("student-reference-topic-selector");
        onView(withContentDescription("질문 대주제 1, 신뢰적 데이터 전송")).perform(click());
        onView(withId(R.id.student_question_input))
                .perform(click(), replaceText("오류 검출과 재전송의 관계가 궁금해요"), closeSoftKeyboard());
        mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "COMPLETE_NEXT", "expectedProgressVersion", 3));
        await(() -> cached(student, sid).optLong("progressVersion") == 4);
        onView(withId(R.id.student_question_input))
                .check(matches(withText("오류 검출과 재전송의 관계가 궁금해요")));
        onView(withId(R.id.student_question_caption))
                .check(matches(withText("질문할 대주제 · 1. 신뢰적 데이터 전송")));
        scenario.recreate();
        await(() -> hasEnabledReaction("이해됐어요"));
        onView(withId(R.id.student_question_input))
                .check(matches(withText("오류 검출과 재전송의 관계가 궁금해요")));
        onView(withId(R.id.student_question_send)).perform(click());
        await(() -> {
            OnlineDatabase.Document stats = teacher.db.store().document(sid, "STATS", "summary");
            return stats != null && OnlineJson.object(stats.json).optInt("submittedQuestionCount") == 1;
        });
        QuerySnapshot questions = Tasks.await(teacher.firestore.collection("sessions/" + sid + "/questions")
                .get(Source.SERVER), 10, TimeUnit.SECONDS);
        assertEquals(1, questions.size());
        assertEquals(root1, questions.getDocuments().get(0).getString("topicId"));
        onView(withId(R.id.student_question_input)).check(matches(withText("")));
        onView(withId(R.id.student_question_send)).check(matches(not(isEnabled())));
    }

    private boolean hasEnabledReaction(String label) {
        try {
            onView(withText(label)).check(matches(org.hamcrest.Matchers.allOf(isDisplayed(), isEnabled())));
            return true;
        } catch (androidx.test.espresso.NoMatchingViewException | AssertionError error) {
            return false;
        }
    }

    /** Room and the first remote snapshot can replace a record row between scroll and touch. */
    private void clickStableText(String text) throws Exception {
        onView(withText(text)).perform(scrollTo());
        AtomicReference<android.view.View> previous = new AtomicReference<>();
        AtomicLong since = new AtomicLong();
        await(() -> {
            AtomicReference<android.view.View> current = new AtomicReference<>();
            onView(withText(text)).check((view, error) -> {
                if (error != null) throw error;
                current.set(view);
            });
            if (previous.getAndSet(current.get()) != current.get()) {
                since.set(android.os.SystemClock.uptimeMillis());
                return false;
            }
            return android.os.SystemClock.uptimeMillis() - since.get() >= 400;
        });
        onView(withText(text)).perform(click());
    }

    private int teacherReactionCount(Client teacher, String sid) {
        OnlineDatabase.Document summary = teacher.db.store().document(sid, "STATS", "summary");
        return summary == null ? -1 : OnlineJson.object(summary.json).optInt("reactionEventCount", -1);
    }

    @Test
    public void nativeStudentScreenShowsReactionsAndSubmitsAQuestionToFirebase()
            throws Exception {
        Client teacher = client(), student = client();
        JSONObject f = create(teacher);
        String sid = f.optString("sid");
        join(teacher, student, sid);
        mutate(
                teacher,
                "changeProgress",
                OnlineJson.values(
                        "sessionId", sid, "action", "START", "expectedProgressVersion", 0));
        legacyScope = new LocalDatabaseScope(context);
        context.getSharedPreferences("local_demo", 0).edit().putString("role", "NONE").commit();
        main(() -> {
            authScope = new EmulatorAuthScope(student.auth);
            OnlineServices.close();
            OnlineServices.testFactory =
                    (c, uid) -> {
                        assertEquals(student.uid, uid);
                        return student.repo;
                    };
        });
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(a -> a.openOnline("online_home"));
        scenario.onActivity(
                a -> a.navigate("online_session", ScreenContext.empty().forLesson(sid)));
        await(() -> cached(student, sid).optString("status").equals("LIVE") && hasEnabledReaction("이해됐어요"));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        for (String label : new String[] {"이해가 안 돼요", "너무 빨라요", "용어가 어려워요", "계산을 놓쳤어요", "이해됐어요"})
            onView(withText(label)).check(matches(isDisplayed()));
        capture("online-student-live-emulator");
        onView(withText("용어가 어려워요")).perform(click());
        onView(withText("자세히 작성")).perform(click());
        onView(withContentDescription("질문 내용 · 1,000자 이내"))
                .perform(scrollTo(), click(), replaceText("화면에서 작성한 온라인 질문"), closeSoftKeyboard());
        scenario.recreate();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withText("화면에서 작성한 온라인 질문")).check(matches(isDisplayed()));
        onView(withText("질문 제출")).perform(click());
        await(() ->
                Tasks.await(
                                        teacher.firestore
                                                .collection("sessions/" + sid + "/questions")
                                                .get(Source.SERVER),
                                        10,
                                        TimeUnit.SECONDS)
                                .size()
                        == 1);
    }

    @Test
    public void backingOutOfBlankQuestionLeavesNoDraftAndSavedDraftCanBeDeleted() throws Exception {
        Client teacher = client(), student = client();
        String sid = create(teacher).optString("sid");
        join(teacher, student, sid);
        mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "START", "expectedProgressVersion", 0));
        showSession(student, sid);
        String entry = "자세히 작성";
        await(() -> hasText(entry));
        onView(withText(entry)).perform(click());
        await(() -> hasText("질문 제출"));
        String emptyId = currentQuestionId();
        onView(withId(R.id.back_button)).perform(click());

        await(() -> hasText(entry));
        onView(withText(entry)).perform(click());
        await(() -> hasText("질문 제출"));
        String savedId = currentQuestionId();
        assertNotEquals(emptyId, savedId);
        String text = "자동 저장하고 삭제할 초안";
        onView(withContentDescription("질문 내용 · 1,000자 이내"))
                .perform(scrollTo(), replaceText(text), closeSoftKeyboard());
        onView(withId(R.id.back_button)).perform(click());
        await(() -> {
            OnlineDatabase.Draft row = student.db.store().draft(sid, "QUESTION_DRAFT", savedId);
            return row != null && !row.dirty && row.serverRevision > 0;
        });
        assertNull(student.db.store().draft(sid, "QUESTION_DRAFT", emptyId));
        CollectionReference drafts = student.firestore.collection("users/" + student.uid
                + "/lessonData/" + sid + "/questionDrafts");
        assertFalse(Tasks.await(drafts.document(emptyId).get(Source.SERVER), 10, TimeUnit.SECONDS).exists());

        scenario.onActivity(a -> a.navigate("online_records", ScreenContext.empty().forLesson(sid)));
        await(() -> hasTextInHierarchy(text));
        onView(withContentDescription("질문 초안 더보기: " + text)).perform(scrollTo(), click());
        onView(withText("초안 삭제")).perform(click());
        onView(withText("삭제")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
        await(() -> "DELETED".equals(student.db.store().draft(sid, "QUESTION_DRAFT", savedId).state));
        await(() -> !hasTextInHierarchy(text));
        onView(withText(text)).check(doesNotExist());
        DocumentSnapshot deleted = Tasks.await(drafts.document(savedId).get(Source.SERVER), 10, TimeUnit.SECONDS);
        assertNotNull(deleted.getTimestamp("deletedAt"));
        assertEquals("", deleted.getString("text"));
    }

    private String currentQuestionId() {
        AtomicReference<String> result = new AtomicReference<>();
        scenario.onActivity(activity -> result.set(ScreenContext.from(activity.getSupportFragmentManager()
                .findFragmentById(R.id.main).getArguments()).questionId));
        assertFalse(result.get().isEmpty());
        return result.get();
    }

    @Test
    public void studentWithdrawsSubmittedQuestionAndTeacherCacheRemovesIt() throws Exception {
        Client teacher = client(), student = client();
        JSONObject fixture = create(teacher);
        String sid = fixture.optString("sid"), topic = fixture.optString("first"), question = OnlineJson.id();
        join(teacher, student, sid);
        watchers.add(teacher.repo.watchLesson(sid));
        String label = "제출을 취소할 질문";
        mutate(student, "submitQuestion", OnlineJson.values("sessionId", sid, "topicId", topic,
                "id", question, "text", label, "baseRevision", 0));
        showSession(student, sid);
        await(() -> student.db.store().document(sid, "QUESTION", question) != null
                && teacher.db.store().document(sid, "QUESTION", question) != null);
        scenario.onActivity(a -> a.navigate("online_records", ScreenContext.empty().forLesson(sid)));
        await(() -> hasTextInHierarchy(label));
        clickStableText(label);
        scenario.onActivity(a -> {
            a.getSupportFragmentManager().executePendingTransactions();
            androidx.fragment.app.Fragment current = a.getSupportFragmentManager().findFragmentById(R.id.main);
            assertEquals("online_question", current.getTag());
            assertEquals(question, ScreenContext.from(current.getArguments()).questionId);
        });
        capture("withdraw-question-detail");
        await(() -> hasText("아직 공개된 답변이 없어요."));
        onView(withText("제출 취소")).check(matches(isDisplayed()));
        onView(withText("제출 취소")).perform(click());
        onView(withText("계속 두기")).perform(click());
        onView(withText(label)).check(matches(isDisplayed()));
        onView(withText("제출 취소")).perform(click());
        onView(withText("제출 취소")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
        await(() -> hasText("제출이 취소된 질문이에요")
                && teacher.db.store().document(sid, "QUESTION", question).deleted);
        onView(withText("이전 화면으로")).perform(click());
        await(() -> hasTextInHierarchy("내 질문"));
        onView(withText(label)).check(doesNotExist());
        assertTrue(student.db.store().document(sid, "QUESTION", question).deleted);
    }

    @Test
    public void studentOpensPublishedQuizAndSubmitsForServerGrading() throws Exception {
        Client teacher = client(), student = client();
        JSONObject fixture = create(teacher);
        String sid = fixture.optString("sid");
        join(teacher, student, sid);
        mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "START", "expectedProgressVersion", 0));
        mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "END", "expectedProgressVersion", 1));
        mutate(teacher, "learning.saveLearningQuiz", OnlineJson.values(
                "sessionId", sid, "topicId", fixture.optString("first"), "title", "확률 복습 테스트",
                "baseRevision", 0, "publish", true,
                "items", new JSONArray().put(OnlineJson.values("prompt", "가능한 결과 수는?",
                        "choices", new JSONArray().put("한 개").put("두 개").put("세 개").put("네 개"),
                        "correctIndex", 1, "explanation", "가능한 결과를 세면 두 개예요."))));
        legacyScope = new LocalDatabaseScope(context);
        context.getSharedPreferences("local_demo", 0).edit().putString("role", "NONE").commit();
        main(() -> {
            authScope = new EmulatorAuthScope(student.auth);
            OnlineServices.close();
            OnlineServices.testFactory = (c, uid) -> {
                assertEquals(student.uid, uid);
                return student.repo;
            };
        });
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(a -> a.openOnline("online_home"));
        scenario.onActivity(a -> a.navigate("online_session", ScreenContext.empty().forLesson(sid)));
        await(() -> cached(student, sid).optString("status").equals("ENDED"));
        onView(withText("복습 퀴즈 풀기")).perform(scrollTo(), click());
        await(() -> hasText("확률 복습 테스트 · 풀기"));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withText("확률 복습 테스트 · 풀기")).perform(scrollTo(), click());
        try {
            await(() -> hasText("가능한 결과 수는?"));
        } catch (AssertionError error) {
            capture("online-quiz-question-timeout");
            throw error;
        }
        onView(withText("가능한 결과를 세면 두 개예요.")).check(
                androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
        onView(withText("두 개")).perform(scrollTo(), click());
        onView(withText("풀이 제출")).perform(scrollTo(), click());
        await(() -> hasText("가능한 결과를 세면 두 개예요."));
        assertEquals(1, call(student, "learning.getLearningQuizzes", OnlineJson.values("sessionId", sid))
                .getJSONArray("attempts").getJSONObject(0).getInt("score"));
    }

    @Test
    public void endedTeacherKeepsRecordingSummaryAndQuizOnSeparatePages() throws Exception {
        Client teacher = client();
        JSONObject fixture = create(teacher);
        String sid = fixture.optString("sid");
        mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "START", "expectedProgressVersion", 0));
        mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "END", "expectedProgressVersion", 1));
        showSession(teacher, sid);
        await(() -> hasText("1. 녹음·전체 원문 검토"));
        onView(withText("1. 녹음·전체 원문 검토")).perform(scrollTo(), click());
        await(() -> hasText("대단원 자동 녹음"));
        onView(withText("대주제 자료 편집")).check(doesNotExist());
        onView(withText("다음 단계: 복습 퀴즈 만들기")).check(doesNotExist());
        androidx.test.espresso.Espresso.pressBack();
        await(() -> hasText("2. 대주제별 요약·보충 설명"));
        onView(withText("2. 대주제별 요약·보충 설명")).perform(scrollTo(), click());
        await(() -> hasText("대주제 자료 편집"));
        onView(withText("대단원 자동 녹음")).check(doesNotExist());
        onView(withText("녹음별 처리 상태")).check(doesNotExist());
        onView(withText("다음 단계: 복습 퀴즈 만들기")).perform(scrollTo(), click());
        await(() -> hasText("대주제 선택 · 퀴즈 작성"));
    }

    @Test
    public void studentReadsPublishedSummaryAndSupplementDirectlyOnThePage() throws Exception {
        Client teacher = client(), student = client();
        JSONObject fixture = create(teacher);
        String sid = fixture.optString("sid");
        join(teacher, student, sid);
        mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "START", "expectedProgressVersion", 0));
        mutate(teacher, "changeProgress", OnlineJson.values(
                "sessionId", sid, "action", "END", "expectedProgressVersion", 1));
        mutate(teacher, "learning.saveLearningNote", OnlineJson.values(
                "sessionId", sid, "topicId", fixture.optString("first"), "baseRevision", 0,
                "summary", "확률은 가능한 결과와 사건을 비교해요.",
                "supplement", "분모에 어떤 사건이 들어가는지 먼저 확인해요.", "publish", true));
        showSession(student, sid);
        await(() -> hasText("대주제별 요약·보충 설명"));
        onView(withText("질문할 순간 표시")).check(doesNotExist());
        onView(withText("대주제별 요약·보충 설명")).perform(scrollTo(), click());
        await(() -> hasText("요약\n확률은 가능한 결과와 사건을 비교해요."));
        onView(withText("교수 보충 설명\n분모에 어떤 사건이 들어가는지 먼저 확인해요."))
                .perform(scrollTo()).check(matches(isDisplayed()));
    }

    private boolean hasTextInHierarchy(String text) {
        try {
            onView(withText(text)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)));
            return true;
        } catch (androidx.test.espresso.NoMatchingViewException | AssertionError error) {
            return false;
        }
    }

    private boolean hasText(String text) {
        try {
            onView(withText(text)).check(matches(isDisplayed()));
            return true;
        } catch (androidx.test.espresso.NoMatchingViewException | AssertionError error) {
            return false;
        }
    }

    @Test
    public void localDraftSurvivesTransportFailureThenRetriesWithoutDuplicatingSubmission()
            throws Exception {
        Client teacher = client(), student = client();
        JSONObject f = create(teacher);
        String sid = f.optString("sid"), topic = f.optString("first");
        join(teacher, student, sid);
        AtomicBoolean offline = new AtomicBoolean(true);
        OnlineTransport flaky =
                new OnlineTransport() {
                    public boolean authenticatedAs(String uid) {
                        return student.transport.authenticatedAs(uid);
                    }

                    public CompletableFuture<JSONObject> call(String k, JSONObject p) {
                        if (offline.get()) {
                            CompletableFuture<JSONObject> failed = new CompletableFuture<>();
                            failed.completeExceptionally(new Failure("NETWORK", "테스트 연결 끊김"));
                            return failed;
                        }
                        return student.transport.call(k, p);
                    }

                    public CompletableFuture<JSONObject> receipt(String u, String o) {
                        return student.transport.receipt(u, o);
                    }

                    public CompletableFuture<JSONObject> page(
                            String u, String s, String k, boolean o, String l) {
                        return student.transport.page(u, s, k, o, l);
                    }

                    public Subscription watchHome(
                            String u, java.util.function.Consumer<String> cb, Sink s) {
                        return student.transport.watchHome(u, cb, s);
                    }

                    public Subscription watchLesson(String u, String s, boolean o, Sink cb) {
                        return student.transport.watchLesson(u, s, o, cb);
                    }
                };
        OnlineDatabase isolated =
                Room.inMemoryDatabaseBuilder(context, OnlineDatabase.class).build();
        OnlineRepository repo = new OnlineRepository(context, student.uid, isolated, flaky, false);
        try {
            String qid = OnlineJson.id();
            repo.saveDraft(sid, qid, topic, "연결이 끊겨도 보관할 질문", true, null);
            await(() -> isolated.store().draft(sid, "QUESTION_DRAFT", qid) != null);
            assertEquals("연결이 끊겨도 보관할 질문", isolated.store().draft(sid, "QUESTION_DRAFT", qid).text);
            offline.set(false);
            repo.retry();
            await(() ->
                    "SERVER_RECEIVED"
                            .equals(isolated.store().draft(sid, "QUESTION_DRAFT", qid).state));
            repo.retry();
            assertEquals(
                    1,
                    Tasks.await(
                                    teacher.firestore
                                            .collection("sessions/" + sid + "/questions")
                                            .get(Source.SERVER),
                                    10,
                                    TimeUnit.SECONDS)
                            .size());
        } finally {
            repo.close();
        }
    }

    @Test
    public void changedAuthIdentityPreventsQueuedWritesFromUsingAnotherAccount() throws Exception {
        Client teacher = client(), student = client();
        JSONObject f = create(teacher);
        String sid = f.optString("sid"), qid = OnlineJson.id();
        join(teacher, student, sid);
        OnlineDatabase isolated =
                Room.inMemoryDatabaseBuilder(context, OnlineDatabase.class).build();
        AtomicBoolean offline = new AtomicBoolean(true);
        OnlineRepository queued =
                new OnlineRepository(context, student.uid, isolated, gate(student, offline), false);
        try {
            queued.saveDraft(sid, qid, f.optString("first"), "다른 계정으로 보내면 안 되는 질문", true, null);
            await(() -> isolated.store().draft(sid, "QUESTION_DRAFT", qid) != null);
            student.auth.signOut();
            offline.set(false);
            assertFalse(queued.drain(false));
            assertFalse(
                    Tasks.await(
                                    teacher.firestore
                                            .document("sessions/" + sid + "/questions/" + qid)
                                            .get(Source.SERVER),
                                    10,
                                    TimeUnit.SECONDS)
                            .exists());
            assertEquals(
                    "다른 계정으로 보내면 안 되는 질문", isolated.store().draft(sid, "QUESTION_DRAFT", qid).text);
            assertNotEquals(
                    OnlineDatabase.accountKey(teacher.uid), OnlineDatabase.accountKey(student.uid));
        } finally {
            queued.close();
        }
    }

    @Test
    public void durableOutboxReopensAndDeliversTheOriginalQuestionExactlyOnce() throws Exception {
        Client teacher = client(), student = client();
        JSONObject f = create(teacher);
        String sid = f.optString("sid"), qid = OnlineJson.id();
        join(teacher, student, sid);
        String filename = "classpulse-online-test-" + OnlineJson.id() + ".db";
        OnlineDatabase disk = Room.databaseBuilder(context, OnlineDatabase.class, filename).build();
        AtomicBoolean offline = new AtomicBoolean(true);
        OnlineRepository first =
                new OnlineRepository(context, student.uid, disk, gate(student, offline), false);
        OnlineRepository restored = null;
        try {
            first.saveDraft(sid, qid, f.optString("first"), "앱을 다시 열어도 남을 질문", true, null);
            await(() -> {
                OnlineDatabase.Outbox o =
                        disk.store().lastForEntity("QUESTION_DRAFT:" + sid + ":" + qid);
                return o != null && o.state.equals("RETRY_WAIT");
            });
            String operation =
                    disk.store().lastForEntity("QUESTION_DRAFT:" + sid + ":" + qid).operationId;
            first.close();
            await(() -> !disk.isOpen());
            OnlineDatabase reopened =
                    Room.databaseBuilder(context, OnlineDatabase.class, filename).build();
            restored =
                    new OnlineRepository(context, student.uid, reopened, student.transport, false);
            assertEquals(
                    "앱을 다시 열어도 남을 질문", reopened.store().draft(sid, "QUESTION_DRAFT", qid).text);
            restored.retry();
            await(() -> "ACKED".equals(reopened.store().operation(operation).state));
            assertEquals(
                    1,
                    Tasks.await(
                                    teacher.firestore
                                            .collection("sessions/" + sid + "/questions")
                                            .get(Source.SERVER),
                                    10,
                                    TimeUnit.SECONDS)
                            .size());
        } finally {
            first.close();
            if (restored != null) restored.close();
        }
    }

    @Test
    public void conflictingPrivateDraftsPreserveBothTextsAndResolveWithTheNewServerRevision()
            throws Exception {
        Client teacher = client(), student = client();
        JSONObject f = create(teacher);
        String sid = f.optString("sid"), topic = f.optString("first"), qid = OnlineJson.id();
        join(teacher, student, sid);
        mutate(
                student,
                "savePersonalDraft",
                OnlineJson.values(
                        "sessionId",
                        sid,
                        "id",
                        qid,
                        "topicId",
                        topic,
                        "text",
                        "공통 원고",
                        "baseRevision",
                        0));
        OnlineDatabase isolated =
                Room.inMemoryDatabaseBuilder(context, OnlineDatabase.class).build();
        AtomicBoolean offline = new AtomicBoolean(false);
        OnlineRepository replica =
                new OnlineRepository(context, student.uid, isolated, gate(student, offline), false);
        OnlineTransport.Subscription watcher = replica.watchLesson(sid);
        try {
            await(() -> isolated.store().draft(sid, "QUESTION_DRAFT", qid) != null);
            offline.set(true);
            replica.saveDraft(sid, qid, topic, "이 기기의 원고", false, null);
            await(() -> isolated.store().draft(sid, "QUESTION_DRAFT", qid).dirty);
            mutate(
                    student,
                    "savePersonalDraft",
                    OnlineJson.values(
                            "sessionId",
                            sid,
                            "id",
                            qid,
                            "topicId",
                            topic,
                            "text",
                            "다른 기기의 원고",
                            "baseRevision",
                            1));
            await(() -> isolated.store().conflict(sid, "QUESTION_DRAFT", qid) != null);
            assertEquals("이 기기의 원고", isolated.store().draft(sid, "QUESTION_DRAFT", qid).text);
            assertEquals(
                    "다른 기기의 원고",
                    OnlineJson.object(
                                    isolated.store()
                                            .conflict(sid, "QUESTION_DRAFT", qid)
                                            .remoteJson)
                            .optString("text"));
            offline.set(false);
            replica.resolveConflict(sid, "QUESTION_DRAFT", qid, true);
            await(() -> {
                OnlineDatabase.Draft d = isolated.store().draft(sid, "QUESTION_DRAFT", qid);
                return d.serverRevision == 3 && !d.dirty;
            });
            assertEquals("이 기기의 원고", isolated.store().draft(sid, "QUESTION_DRAFT", qid).text);
        } finally {
            watcher.close();
            replica.close();
        }
    }

    @Test
    public void questionPaginationRetrievesRecordsBeyondTheFirstThirty() throws Exception {
        Client teacher = client(), student = client();
        JSONObject f = create(teacher);
        String sid = f.optString("sid"), topic = f.optString("first");
        join(teacher, student, sid);
        for (int i = 0; i < 31; i++)
            mutate(
                    student,
                    "submitQuestion",
                    OnlineJson.values(
                            "sessionId",
                            sid,
                            "id",
                            OnlineJson.id(),
                            "topicId",
                            topic,
                            "text",
                            "목록 질문 " + i,
                            "baseRevision",
                            0));
        JSONObject first =
                teacher.transport
                        .page(teacher.uid, sid, "QUESTION", true, "")
                        .get(30, TimeUnit.SECONDS);
        JSONArray list = first.getJSONArray("documents");
        assertEquals(30, list.length());
        JSONObject second =
                teacher.transport
                        .page(
                                teacher.uid,
                                sid,
                                "QUESTION",
                                true,
                                list.getJSONObject(29).getString("id"))
                        .get(30, TimeUnit.SECONDS);
        assertEquals(1, second.getJSONArray("documents").length());
        assertNotEquals(
                list.getJSONObject(29).optString("id"),
                second.getJSONArray("documents").getJSONObject(0).optString("id"));
    }

    @Test
    public void recordsTabLoadsRealQuestionsPrivateDraftsAndMarksForSelectedLesson()
            throws Exception {
        Client teacher = client(), student = client();
        JSONObject f = create(teacher);
        String sid = f.optString("sid"), topic = f.optString("first");
        join(teacher, student, sid);
        String question = OnlineJson.id(), draft = OnlineJson.id(), mark = OnlineJson.id();
        mutate(
                student,
                "submitQuestion",
                OnlineJson.values(
                        "sessionId",
                        sid,
                        "id",
                        question,
                        "topicId",
                        topic,
                        "text",
                        "내 기록에서 확인할 제출 질문",
                        "baseRevision",
                        0));
        mutate(
                student,
                "savePersonalDraft",
                OnlineJson.values(
                        "sessionId",
                        sid,
                        "id",
                        draft,
                        "topicId",
                        topic,
                        "text",
                        "아직 제출하지 않은 내 원고",
                        "baseRevision",
                        0));
        mutate(
                student,
                "saveMark",
                OnlineJson.values(
                        "sessionId",
                        sid,
                        "id",
                        mark,
                        "topicId",
                        topic,
                        "text",
                        "분모를 다시 확인",
                        "baseRevision",
                        0));
        legacyScope = new LocalDatabaseScope(context);
        context.getSharedPreferences("local_demo", 0).edit().putString("role", "NONE").commit();
        main(() -> {
            authScope = new EmulatorAuthScope(student.auth);
            OnlineServices.close();
            OnlineServices.testFactory = (c, uid) -> student.repo;
        });
        scenario = ActivityScenario.launch(MainActivity.class);
        await(() -> cached(student, sid).has("id"));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withId(R.id.nav_home)).check(matches(isSelected()));
        onView(withId(R.id.nav_records)).perform(click());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withText("온라인 통계학 수업")).perform(click());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(a -> {
            a.getSupportFragmentManager().executePendingTransactions();
            androidx.fragment.app.Fragment selected =
                    a.getSupportFragmentManager().findFragmentById(R.id.main);
            assertEquals("online_records", selected.getTag());
            assertEquals("Selecting the lesson must open its records before waiting for Room",
                    sid, ScreenContext.from(selected.getArguments()).lessonId);
        });
        await(() ->
                student.db.store().document(sid, "QUESTION", question) != null
                        && student.db.store().draft(sid, "QUESTION_DRAFT", draft) != null
                        && student.db.store().draft(sid, "MARK", mark) != null);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withText("내 기록에서 확인할 제출 질문")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withText("아직 제출하지 않은 내 원고")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withText("분모를 다시 확인")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withId(R.id.nav_records)).check(matches(isSelected()));
        scenario.recreate();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withText("분모를 다시 확인")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withId(R.id.nav_settings)).perform(click());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withId(R.id.screen_title)).check(matches(withText("내 정보")));
        onView(withId(R.id.nav_settings)).check(matches(isSelected()));
        onView(withId(R.id.nav_home)).perform(click());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(a -> {
            assertEquals(
                    "online_home",
                    a.getSupportFragmentManager().findFragmentById(R.id.main).getTag());
            assertEquals(4, a.getSupportFragmentManager().getBackStackEntryCount());
        });
        String[] backRoutes = {"account", "online_records", "online_records", "online_home"};
        String[] backLessons = {"", sid, "", ""};
        for (int index = 0; index < backRoutes.length; index++) {
            androidx.test.espresso.Espresso.pressBack();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            final int backIndex = index;
            scenario.onActivity(a -> {
                androidx.fragment.app.Fragment current =
                        a.getSupportFragmentManager().findFragmentById(R.id.main);
                assertEquals(backRoutes[backIndex], current.getTag());
                assertEquals(backLessons[backIndex],
                        ScreenContext.from(current.getArguments()).lessonId);
                assertEquals(3 - backIndex,
                        a.getSupportFragmentManager().getBackStackEntryCount());
            });
            if (index == 0) {
                onView(withId(R.id.screen_title)).check(matches(withText("내 정보")));
                onView(withId(R.id.nav_settings)).check(matches(isSelected()));
            } else if (index == 1) {
                onView(withText("분모를 다시 확인")).perform(scrollTo()).check(matches(isDisplayed()));
                onView(withId(R.id.nav_records)).check(matches(isSelected()));
            } else if (index == 2) {
                onView(withText("수업별 기록을 확인해요")).check(matches(isDisplayed()));
                onView(withId(R.id.nav_records)).check(matches(isSelected()));
            } else {
                onView(withId(R.id.nav_home)).check(matches(isSelected()));
            }
        }
    }

    @Test
    public void nativeAccountChangeClearsThePreviousAccountsOnlineScreen() throws Exception {
        Client teacher = client(), student = client();
        JSONObject f = create(teacher);
        String sid = f.optString("sid");
        join(teacher, student, sid);
        legacyScope = new LocalDatabaseScope(context);
        context.getSharedPreferences("local_demo", 0).edit().putString("role", "NONE").commit();
        main(() -> {
            authScope = new EmulatorAuthScope(student.auth);
            OnlineServices.close();
            OnlineServices.testFactory =
                    (c, uid) -> uid.equals(student.uid) ? student.repo : teacher.repo;
        });
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(a -> a.openOnline("online_home"));
        scenario.onActivity(
                a -> a.navigate("online_session", ScreenContext.empty().forLesson(sid)));
        await(() -> cached(student, sid).has("id"));
        Tasks.await(
                student.auth.signInWithEmailAndPassword(
                        teacher.auth.getCurrentUser().getEmail(), "emulator-only-password"),
                20,
                TimeUnit.SECONDS);
        await(() -> {
            AtomicBoolean account = new AtomicBoolean();
            scenario.onActivity(a ->
                    account.set(
                            "account"
                                    .equals(
                                            a.getSupportFragmentManager()
                                                    .findFragmentById(R.id.main)
                                                    .getTag())));
            return account.get();
        });
        scenario.recreate();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withId(R.id.screen_title)).check(matches(withText("내 정보")));
    }
}
