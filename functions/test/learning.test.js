"use strict";

const { test, before, after } = require("node:test");
const assert = require("node:assert/strict");
const { randomUUID } = require("node:crypto");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const { initializeApp, deleteApp } = require("firebase-admin/app");
const { getFirestore, Timestamp } = require("firebase-admin/firestore");
const { initializeTestEnvironment, assertFails, assertSucceeds } = require("@firebase/rules-unit-testing");
const { ref, uploadBytes, getMetadata } = require("firebase/storage");
const { doc, getDoc } = require("firebase/firestore");
const { createService } = require("../src/service");
const { ProviderError } = require("../src/ai-providers");

const projectId = "demo-classpulse";
let app, db, env;
before(async () => {
    app = initializeApp({ projectId }, "learning-tests");
    db = getFirestore(app);
    env = await initializeTestEnvironment({
        projectId,
        firestore: { host: "127.0.0.1", port: 8080 },
        storage: {
            host: "127.0.0.1", port: 9199,
            rules: readFileSync(path.join(__dirname, "../../storage.rules"), "utf8")
        }
    });
});
after(async () => {
    await env?.cleanup();
    await db?.terminate();
    if (app) await deleteApp(app);
});

async function fixture(providers = {}) {
    const sid = randomUUID(), teacher = randomUUID(), student = randomUUID(), stranger = randomUUID();
    const topic = randomUUID(), nextTopic = randomUUID(), recordingId = randomUUID();
    const startedAt = Date.now() - 60000;
    const session = db.doc(`sessions/${sid}`);
    const batch = db.batch();
    batch.set(session, { status: "ENDED", ownerUid: teacher, deletedAt: null });
    batch.set(session.collection("members").doc(student), { state: "ACTIVE" });
    batch.set(session.collection("topics").doc(topic), { title: "조건부 확률", deletedAt: null });
    batch.set(session.collection("topics").doc(nextTopic), { title: "표본공간", deletedAt: null });
    batch.set(session.collection("visits").doc(randomUUID()), {
        topicId: topic, startedAt: Timestamp.fromMillis(startedAt), endedAt: Timestamp.fromMillis(startedAt + 8000)
    });
    batch.set(session.collection("visits").doc(randomUUID()), {
        topicId: nextTopic, startedAt: Timestamp.fromMillis(startedAt + 8000), endedAt: Timestamp.fromMillis(startedAt + 15000)
    });
    await batch.commit();
    let sent, launches = 0;
    const service = createService(db, {
        learningProviders: {
            startRecording: async recording => {
                sent = recording;
                launches += 1;
                if (providers.startRecording) await providers.startRecording(recording);
            },
            rewriteQuestion: async question => ({ text: question + "?" }),
            generateDraft: async (mode, input) => providers.generateDraft?.(mode, input) || { text: "검토할 AI 초안" }
        }
    });
    const run = (uid, command, payload = {}) => service.dispatchLearning(uid, {
        command, payload: { sessionId: sid, ...payload }
    });
    const mutate = (uid, command, payload) => run(uid, command, { operationId: randomUUID(), ...payload });
    const start = () => run(teacher, "startLectureStt", { startedAt, recordingId });
    const callback = () => ({
        result: "COMPLETED", text: "첫 주제 두 번째 주제",
        segments: [{ start: 100, end: 3000, text: "첫 주제" }, { start: 9000, end: 9500, text: "두 번째 주제" }],
        params: { userdata: sent.userdata }
    });
    return { sid, teacher, student, stranger, topic, nextTopic, recordingId, session, service, run,
        mutate, start, callback, sent: () => sent, launches: () => launches };
}

test("recording submission is scoped to the owner and one upload; verified callback builds private topic parts", async () => {
    const f = await fixture();
    await assert.rejects(f.run(f.student, "startLectureStt", { recordingId: f.recordingId, startedAt: Date.now() }), {
        reason: "OWNER_REQUIRED"
    });
    await Promise.all([f.start(), f.start()]);
    assert.equal(f.launches(), 1);
    assert.equal(f.sent().path, `lecture-recordings/${f.sid}/${f.teacher}/${f.recordingId}.m4a`);
    const callback = f.callback();
    assert.equal(await f.service.acceptLectureResult({
        ...callback, params: { userdata: { ...callback.params.userdata, proof: "A".repeat(43) } }
    }), false);
    assert.equal(await f.service.acceptLectureResult(callback), true);
    assert.equal(await f.service.acceptLectureResult(callback), false);
    const ownerView = await f.run(f.teacher, "getLectureMaterial");
    assert.equal(ownerView.job.state, "READY");
    assert.equal(ownerView.parts[0].topicId, f.topic);
    assert.equal(ownerView.parts[1].topicId, f.nextTopic);
    const learnerView = await f.run(f.student, "getLectureMaterial");
    assert.equal(learnerView.material, null);
    assert.deepEqual(learnerView.parts, []);
    assert.equal(learnerView.job, null);
    await assert.rejects(f.run(f.stranger, "getLectureMaterial"), { reason: "MEMBERSHIP_REQUIRED" });
});

test("a timeout that may have reached CLOVA cannot submit a duplicate recording", async () => {
    const f = await fixture({ startRecording: async () => { throw new ProviderError("CLOVA_SPEECH", "TIMEOUT"); } });
    assert.equal((await f.start()).job.state, "PROCESSING");
    assert.equal((await f.start()).job.state, "PROCESSING");
    assert.equal(f.launches(), 1);
    assert.equal(await f.service.acceptLectureResult(f.callback()), true);
});

test("an imported recording can be transcribed without assigning it to unrelated lesson visits", async () => {
    const f = await fixture();
    await f.run(f.teacher, "startLectureStt", { recordingId: f.recordingId, startedAt: 0 });
    assert.equal(await f.service.acceptLectureResult({ ...f.callback(), result: "SUCCEEDED", progress: 100 }), true);
    const result = await f.run(f.teacher, "getLectureMaterial");
    assert.equal(result.job.state, "READY");
    assert.equal(result.parts.length, 1);
    assert.equal(result.parts[0].topicId, "");
    assert.equal(result.parts[0].text, "첫 주제\n두 번째 주제");
});

test("students see the published transcript version and note, while AI uses the selected topic source", async () => {
    let input;
    const f = await fixture({ generateDraft: (mode, value) => { input = value; return { text: "요약 원고" }; } });
    await f.start();
    await f.service.acceptLectureResult(f.callback());
    const initial = await f.run(f.teacher, "getLectureMaterial");
    const edit = { baseRevision: initial.material.revision, partId: initial.parts[0].id, topicId: f.topic, text: "교수 수정" };
    await f.mutate(f.teacher, "saveLecturePart", edit);
    await assert.rejects(f.mutate(f.teacher, "saveLecturePart", edit), { reason: "VERSION_CONFLICT" });
    const version = await f.mutate(f.teacher, "publishLecture", { baseRevision: edit.baseRevision + 1 });
    assert.equal(version.version, 1);
    assert.equal((await f.run(f.student, "getLectureMaterial")).parts[0].text, "교수 수정");
    await f.mutate(f.teacher, "saveLecturePart", { ...edit, baseRevision: edit.baseRevision + 1, text: "미공개 수정" });
    assert.equal((await f.run(f.student, "getLectureMaterial")).parts[0].text, "교수 수정");
    assert.equal((await f.run(f.teacher, "generateLearningDraft", { mode: "summary", topicId: f.topic })).text, "요약 원고");
    assert.equal(input.transcript, "미공개 수정");
    await assert.rejects(f.run(f.student, "generateLearningDraft", { mode: "summary", topicId: f.topic }), { reason: "OWNER_REQUIRED" });
    assert.equal((await f.run(f.student, "generateLearningDraft", { mode: "rewrite", text: "궁금해요" })).text, "궁금해요?");
    const draft = { baseRevision: 0, topicId: f.topic, summary: "선택 요약", supplement: "설명", publish: false };
    await f.mutate(f.teacher, "saveLearningNote", draft);
    assert.deepEqual((await f.run(f.student, "getLectureMaterial")).notes, []);
    await f.mutate(f.teacher, "saveLearningNote", { ...draft, baseRevision: 1, publish: true });
    assert.equal((await f.run(f.student, "getLectureMaterial")).notes[0].summary, "선택 요약");
});

test("quiz drafts and keys stay private; a student's attempt uses the version they opened", async () => {
    const f = await fixture();
    const item = { prompt: "조건부 확률은?", choices: ["1", "2", "3", "4"], correctIndex: 2, explanation: "첫 버전 해설" };
    const base = { topicId: f.topic, title: "복습 퀴즈", items: [item], baseRevision: 0 };
    await f.mutate(f.teacher, "saveLearningQuiz", base);
    assert.deepEqual((await f.run(f.student, "getLearningQuizzes")).drafts, []);
    await assert.rejects(f.run(f.student, "getLearningQuiz", { quizId: f.topic, version: 1 }), { reason: "QUIZ_NOT_FOUND" });
    await f.mutate(f.teacher, "saveLearningQuiz", { ...base, baseRevision: 1, publish: true });
    const visible = await f.run(f.student, "getLearningQuiz", { quizId: f.topic, version: 1 });
    assert.deepEqual(visible.items, [{ prompt: item.prompt, choices: item.choices }]);
    const studentDb = env.authenticatedContext(f.student).firestore();
    await assertFails(getDoc(doc(studentDb, `sessions/${f.sid}/quizKeys/${f.topic}_1`)));
    await f.mutate(f.teacher, "saveLearningQuiz", {
        ...base, baseRevision: 2, publish: true, items: [{ ...item, correctIndex: 0, explanation: "둘째 버전" }]
    });
    const attempt = { quizId: f.topic, version: 1, attemptId: randomUUID(), answers: [2] };
    const graded = await f.mutate(f.student, "submitLearningQuiz", attempt);
    assert.equal(graded.score, 1);
    assert.equal(graded.items[0].explanation, "첫 버전 해설");
    assert.equal((await f.mutate(f.student, "submitLearningQuiz", attempt)).id, graded.id);
    await assert.rejects(f.mutate(f.student, "submitLearningQuiz", { ...attempt, answers: [0] }), { reason: "OPERATION_REUSED" });
    assert.equal((await f.run(f.student, "getLearningQuizzes")).attempts.length, 1);
    await assertFails(getDoc(doc(env.authenticatedContext(f.teacher).firestore(),
        `users/${f.student}/lessonData/${f.sid}/attempts/${attempt.attemptId}`)));
});

test("only the teacher can create and inspect the immutable raw recording", async () => {
    const f = await fixture();
    const object = `lecture-recordings/${f.sid}/${f.teacher}/${f.recordingId}.m4a`;
    const teacherFile = ref(env.authenticatedContext(f.teacher).storage(), object);
    const studentFile = ref(env.authenticatedContext(f.student).storage(), object);
    const bytes = new Uint8Array([1, 2, 3]);
    await assertFails(uploadBytes(studentFile, bytes, { contentType: "audio/mp4" }));
    await assertFails(uploadBytes(teacherFile, bytes, { contentType: "text/plain" }));
    await assertSucceeds(uploadBytes(teacherFile, bytes, { contentType: "audio/mp4" }));
    await assertSucceeds(getMetadata(teacherFile));
    await assertFails(getMetadata(studentFile));
    await assertFails(uploadBytes(teacherFile, bytes, { contentType: "audio/mp4" }));
});
