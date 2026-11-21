"use strict";
const {
    test,
    before,
    after
} = require("node:test");
const assert = require("node:assert/strict");
const {
    randomUUID
} = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const {
    initializeApp,
    deleteApp
} = require("firebase-admin/app");
const {
    FieldValue,
    getFirestore
} = require("firebase-admin/firestore");
const {
    initializeTestEnvironment,
    assertFails,
    assertSucceeds
} = require("@firebase/rules-unit-testing");
const {
    doc,
    getDoc,
    setDoc,
    collection,
    query,
    where,
    getDocs,
    orderBy
} = require("firebase/firestore");
const {
    createService,
    hash
} = require("../src/service");
let app, db, env, service;
const owner = "owner-test",
    student = "student-test",
    other = "other-student";
const op = () => randomUUID();
const invoke = (uid, command, payload) => service.dispatch(uid, {
    command,
    payload
}, "테스트 교수");
const mutate = (uid, command, payload) => invoke(uid, command, {
    operationId: op(),
    ...payload
});
const expectReason = (action, reason) => assert.rejects(action, e => e.reason === reason);
async function fixture() {
    const sid = op(),
        topics = [{
            id: op(),
            title: "조건부확률"
        }, {
            id: op(),
            title: "베이즈 정리"
        }];
    await mutate(owner, "createSession", {
        sessionId: sid,
        title: "통계학개론",
        description: "",
        topics
    });
    return {
        sid,
        topics
    };
}
async function invite(f) {
    await mutate(owner, "issueInvite", {
        sessionId: f.sid
    });
    return invoke(owner, "getInvite", {
        sessionId: f.sid
    });
}
async function join(uid, f, inv) {
    const p = await invoke(uid, "resolveInvite", {
        kind: "CODE",
        value: (inv || await invite(f)).code
    });
    return mutate(uid, "joinSession", {
        previewToken: p.previewToken
    });
}
test("only the owner can delete idle classes and deletion revokes links and cached lesson references", async () => {
    const f = await fixture();
    const inv = await invite(f);
    await join(student, f, inv);
    const preview = await invoke(other, "resolveInvite", { kind: "CODE", value: inv.code });
    const payload = { sessionId: f.sid, operationId: op() };
    await expectReason(mutate(student, "deleteSession", { sessionId: f.sid }), "OWNER_REQUIRED");
    await start(f);
    await expectReason(invoke(owner, "deleteSession", payload), "LESSON_LIVE");
    await mutate(owner, "changeProgress", { sessionId: f.sid, expectedProgressVersion: 1, action: "END" });
    const deleted = await invoke(owner, "deleteSession", payload);
    assert.equal(deleted.session.id, f.sid);
    assert.ok(deleted.session.deletedAt);
    assert.deepEqual(await invoke(owner, "deleteSession", payload), deleted);
    for (const uid of [owner, student]) {
        const ref = (await db.doc(`users/${uid}/lessonRefs/${f.sid}`).get()).data();
        assert.equal(ref.state, "DELETED");
        assert.equal(ref.sessionRevision, deleted.session.revision);
        assert.equal(ref.joinedAt, undefined);
        await expectReason(invoke(uid, "getSessionBootstrap", { sessionId: f.sid }), "NOT_FOUND");
        const client = env.authenticatedContext(uid).firestore();
        await assertFails(getDoc(doc(client, "sessions", f.sid, "topics", f.topics[0].id)));
    }
    await expectReason(invoke(other, "resolveInvite", { kind: "CODE", value: inv.code }), "ADMISSION_CLOSED");
    await expectReason(mutate(other, "joinSession", { previewToken: preview.previewToken }), "NOT_FOUND");
    const ready = await fixture();
    await mutate(owner, "deleteSession", { sessionId: ready.sid });
    await expectReason(invoke(owner, "getSessionBootstrap", { sessionId: ready.sid }), "NOT_FOUND");
});

async function start(f) {
    return mutate(owner, "changeProgress", {
        sessionId: f.sid,
        expectedProgressVersion: 0,
        action: "START"
    });
}
before(async () => {
    assert.match(process.env.FIRESTORE_EMULATOR_HOST || "", /^(127\.0\.0\.1|localhost):8080$/, "Tests must use the local emulator");
    app = initializeApp({
        projectId: "demo-classpulse"
    }, "server-tests");
    db = getFirestore(app);
    env = await initializeTestEnvironment({
        projectId: "demo-classpulse",
        firestore: {
            host: "127.0.0.1",
            port: 8080,
            rules: fs.readFileSync(path.join(__dirname, "../../firestore.rules"), "utf8")
        }
    });
    await env.clearFirestore();
    service = createService(db);
});
after(async () => {
    await env?.cleanup();
    await db?.terminate();
    if (app) await deleteApp(app);
});

test("preview does not join; code and link confirm the same lesson; other UID cannot use preview", async () => {
    const f = await fixture(),
        inv = await invite(f);
    const preview = await invoke(student, "resolveInvite", {
        kind: "CODE",
        value: inv.code
    });
    assert.equal((await db.doc(`sessions/${f.sid}/members/${student}`).get()).exists, false);
    await expectReason(() => mutate(other, "joinSession", {
        previewToken: preview.previewToken
    }), "INVALID_PREVIEW");
    const result = await mutate(student, "joinSession", {
        previewToken: preview.previewToken
    });
    assert.equal(result.sessionId, f.sid);
    const token = new URL(inv.link).pathname.split("/").pop();
    const fromLink = await invoke(other, "resolveInvite", {
        kind: "LINK",
        value: token
    });
    assert.equal(fromLink.sessionId, f.sid);
    await mutate(other, "joinSession", {
        previewToken: fromLink.previewToken
    });
    assert.equal((await db.doc(`sessions/${f.sid}/analytics/summary`).get()).data().studentCount, 2);
});
test("0042 remains a string; collision, rotation and server expiry are enforced", async () => {
    let now = Date.now();
    const timed = createService(db, {
        clock: () => now,
        randomCode: () => "0042"
    });
    const f = await fixture(),
        g = await fixture();
    const call = (command, payload) => timed.dispatch(owner, {
        command,
        payload: {
            operationId: op(),
            ...payload
        }
    });
    await call("issueInvite", {
        sessionId: f.sid
    });
    const inv = await invoke(owner, "getInvite", {
        sessionId: f.sid
    });
    assert.equal(inv.code, "0042");
    await expectReason(() => call("issueInvite", {
        sessionId: g.sid
    }), "CODES_BUSY");
    const preview = await invoke(student, "resolveInvite", {
        kind: "CODE",
        value: "0042"
    });
    await call("issueInvite", {
        sessionId: f.sid
    });
    await expectReason(() => mutate(student, "joinSession", {
        previewToken: preview.previewToken
    }), "INVITE_EXPIRED");
    now += 25 * 3600000;
    await expectReason(() => timed.dispatch(student, {
        command: "resolveInvite",
        payload: {
            kind: "CODE",
            value: "0042"
        }
    }), "INVITE_EXPIRED");
});
test("same command is committed once; a reused operation with changed payload is rejected", async () => {
    const sid = op(),
        payload = {
            operationId: op(),
            sessionId: sid,
            title: "원본",
            description: "",
            topics: [{
                id: op(),
                title: "주제"
            }]
        };
    const results = await Promise.all(Array.from({
        length: 10
    }, () => invoke(owner, "createSession", payload)));
    assert.ok(results.every(r => r.sessionId === sid));
    assert.equal((await db.doc(`sessions/${sid}/analytics/summary`).get()).data().studentCount, 0);
    await expectReason(() => invoke(owner, "createSession", {
        ...payload,
        title: "위조된 변경"
    }), "OPERATION_REUSED");
    await expectReason(() => mutate(student, "createSession", {
        sessionId: op(),
        title: "수업",
        description: "",
        topics: [{
            id: op(),
            title: "주제"
        }],
        ownerUid: owner
    }), "FORGED_IDENTITY");
});
test("progress CAS advances once; revisit preserves completion; end does not close questions", async () => {
    const f = await fixture();
    await join(student, f);
    await start(f);
    const attempts = await Promise.allSettled(Array.from({
        length: 2
    }, () => mutate(owner, "changeProgress", {
        sessionId: f.sid,
        expectedProgressVersion: 1,
        action: "COMPLETE_NEXT"
    })));
    assert.equal(attempts.filter(r => r.status === "fulfilled").length, 1);
    assert.equal(attempts.find(r => r.status === "rejected").reason.reason, "VERSION_CONFLICT");
    await mutate(owner, "changeProgress", {
        sessionId: f.sid,
        expectedProgressVersion: 2,
        action: "REVISIT",
        topicId: f.topics[0].id
    });
    let state = await invoke(student, "getSessionBootstrap", {
        sessionId: f.sid
    });
    assert.ok(state.session.progress.completedTopicIds.includes(f.topics[0].id));
    assert.equal(state.session.progress.resumeTopicId, f.topics[1].id);
    await mutate(owner, "changeProgress", {
        sessionId: f.sid,
        expectedProgressVersion: 3,
        action: "RESUME"
    });
    await mutate(owner, "changeProgress", {
        sessionId: f.sid,
        expectedProgressVersion: 4,
        action: "END"
    });
    state = await invoke(student, "getSessionBootstrap", {
        sessionId: f.sid
    });
    assert.equal(state.session.status, "ENDED");
    assert.equal(state.session.questionsOpen, true);
    await mutate(student, "submitQuestion", {
        sessionId: f.sid,
        id: op(),
        topicId: f.topics[0].id,
        text: "종료 후 질문",
        baseRevision: 0
    });
    await expectReason(() => mutate(student, "changeProgress", {
        sessionId: f.sid,
        expectedProgressVersion: 5,
        action: "END"
    }), "OWNER_REQUIRED");
});
test("subtopics are grouped in the outline and progress follows teachable topics", async () => {
    const f = { sid: op() };
    const parent = { id: op(), title: "조건부확률" };
    const first = { id: op(), title: "정의", parentTopicId: parent.id };
    const second = { id: op(), title: "연습", parentTopicId: parent.id };
    const standalone = { id: op(), title: "정리" };
    const sessionInfo = { sessionId: f.sid, title: "목차가 있는 수업", description: "" };
    await mutate(owner, "createSession", {
        ...sessionInfo,
        topics: [first, parent, standalone, second]
    });
    let state = await invoke(owner, "getSessionBootstrap", { sessionId: f.sid });
    assert.deepEqual(state.topics.map(({ id, position, parentTopicId }) => [id, position, parentTopicId]), [
        [parent.id, 0, null],
        [first.id, 1, parent.id],
        [second.id, 2, parent.id],
        [standalone.id, 3, null]
    ]);

    await mutate(owner, "updateOutline", {
        ...sessionInfo,
        baseRevision: state.session.outlineVersion,
        // A previous APK sends id and title without a parent field.
        topics: [parent, second, standalone, { id: first.id, title: "정의 다시 보기" }]
    });
    await join(student, f);
    state = await invoke(student, "getSessionBootstrap", { sessionId: f.sid });
    assert.equal(state.topics[2].title, "정의 다시 보기");
    assert.deepEqual(state.topics.map(topic => topic.id), [parent.id, second.id, first.id, standalone.id]);

    const update = (action, expectedProgressVersion, extra = {}) => mutate(owner, "changeProgress", {
        sessionId: f.sid,
        action,
        expectedProgressVersion,
        ...extra
    });
    let { session } = await start(f);
    assert.equal(session.progress.currentTopicId, second.id);
    await mutate(student, "submitReaction", {
        sessionId: f.sid,
        visitId: session.progress.currentVisitId,
        topicId: second.id,
        type: "UNDERSTANDING"
    });
    ({ session } = await update("COMPLETE_NEXT", 1));
    assert.equal(session.progress.currentTopicId, first.id);
    await expectReason(() => update("REVISIT", 2, { topicId: parent.id }), "NOT_COMPLETED");
    ({ session } = await update("REVISIT", 2, { topicId: second.id }));
    assert.equal(session.progress.currentTopicId, second.id);
    assert.equal(session.progress.resumeTopicId, first.id);
    ({ session } = await update("COMPLETE_NEXT", 3));
    assert.equal(session.progress.currentTopicId, first.id);
    ({ session } = await update("COMPLETE_NEXT", 4));
    assert.equal(session.progress.currentTopicId, standalone.id);
    ({ session } = await update("COMPLETE_NEXT", 5));
    assert.equal(session.progress.currentVisitId, null);
    assert.deepEqual(session.progress.completedTopicIds, [second.id, first.id, standalone.id]);
});
test("invalid topic trees are rejected before a session is created", async () => {
    const root = { id: op(), title: "대주제" };
    const child = { id: op(), title: "소주제", parentTopicId: root.id };
    const invalidOutlines = [
        [root, { ...child, parentTopicId: op() }],
        [{ ...root, parentTopicId: root.id }],
        [root, child, { id: op(), title: "셋째 단계", parentTopicId: child.id }],
        [root, { ...child, id: root.id }]
    ];
    for (const topics of invalidOutlines) {
        const sessionId = op();
        await expectReason(() => mutate(owner, "createSession", {
            sessionId,
            title: "수업",
            topics
        }), "INVALID_TOPICS");
        assert.equal((await db.doc(`sessions/${sessionId}`).get()).exists, false);
    }
});
test("reaction deduplication, distinct student counts, revisit and stale-visit rejection", async () => {
    const f = await fixture();
    await join(student, f);
    const started = await start(f);
    const visit = started.session.progress.currentVisitId;
    const payload = {
        sessionId: f.sid,
        visitId: visit,
        topicId: f.topics[0].id,
        type: "UNDERSTANDING"
    };
    await Promise.all(Array.from({
        length: 4
    }, () => mutate(student, "submitReaction", payload)));
    await mutate(student, "submitReaction", {
        ...payload,
        type: "TERM"
    });
    let summary = (await db.doc(`sessions/${f.sid}/analytics/summary`).get()).data();
    assert.equal(summary.reactionEventCount, 2);
    assert.equal(summary.uniqueReactingStudents, 1);
    await mutate(owner, "changeProgress", {
        sessionId: f.sid,
        expectedProgressVersion: 1,
        action: "COMPLETE_NEXT"
    });
    await expectReason(() => mutate(student, "submitReaction", {
        ...payload,
        type: "SPEED"
    }), "STALE_VISIT");
    await mutate(owner, "changeProgress", {
        sessionId: f.sid,
        expectedProgressVersion: 2,
        action: "REVISIT",
        topicId: f.topics[0].id
    });
    const current = await invoke(student, "getSessionBootstrap", {
        sessionId: f.sid
    });
    await mutate(student, "submitReaction", {
        ...payload,
        visitId: current.session.progress.currentVisitId
    });
    summary = (await db.doc(`sessions/${f.sid}/analytics/summary`).get()).data();
    assert.equal(summary.reactionEventCount, 3);
    assert.equal(summary.uniqueReactingStudents, 1);
    assert.equal(summary.uniqueStudentsByType.UNDERSTANDING, 1);
});
test("canceling a reaction updates all scopes without losing other visits, types, or students", async () => {
    const f = await fixture();
    const inv = await invite(f);
    await join(student, f, inv);
    await join(other, f, inv);
    const started = await start(f);
    const topicId = f.topics[0].id;
    const first = { sessionId: f.sid, topicId, visitId: started.session.progress.currentVisitId,
        type: "TERM" };
    await mutate(student, "submitReaction", first);
    await mutate(owner, "changeProgress", {
        sessionId: f.sid, expectedProgressVersion: 1, action: "COMPLETE_NEXT"
    });
    const revisit = await mutate(owner, "changeProgress", {
        sessionId: f.sid, topicId, expectedProgressVersion: 2, action: "REVISIT"
    });
    const visitId = revisit.session.progress.currentVisitId;
    const current = { ...first, visitId };
    await mutate(student, "submitReaction", current);
    await mutate(student, "submitReaction", { ...current, type: "SPEED" });
    await mutate(other, "submitReaction", current);

    const base = `sessions/${f.sid}`;
    // Simulate presence documents written before per-type counts were stored.
    const scopes = ["summary", `topic_${topicId}`, `visit_${visitId}`];
    for (const scope of scopes) {
        const ref = db.doc(`${base}/reactionPresence/${hash([scope, student])}`);
        const { uid, types } = (await ref.get()).data();
        await ref.set({ uid, types });
    }
    const cancel = { ...current, operationId: op(), selected: false, baseRevision: 1 };
    const canceled = await invoke(student, "setReaction", cancel);
    assert.ok(canceled.receipt.deletedAt);
    assert.equal(canceled.receipt.revision, 2);
    assert.deepEqual(await invoke(student, "setReaction", cancel), canceled);
    assert.equal((await mutate(student, "setReaction", { ...current, selected: false, baseRevision: 1 }))
        .receipt.revision, 2);
    await expectReason(() => mutate(student, "setReaction", { ...first, selected: false, baseRevision: 1 }),
        "STALE_VISIT");
    const stats = async scope => (await db.doc(`${base}/analytics/${scope}`).get()).data();
    for (const scope of scopes.slice(0, 2)) {
        const value = await stats(scope);
        assert.equal(value.reactionEventCount, 3);
        assert.equal(value.uniqueReactingStudents, 2);
        assert.equal(value.eventCountsByType.TERM, 2);
        assert.equal(value.uniqueStudentsByType.TERM, 2);
    }
    let value = await stats(scopes[2]);
    assert.equal(value.reactionEventCount, 2);
    assert.equal(value.uniqueReactingStudents, 2);
    assert.equal(value.uniqueStudentsByType.TERM, 1);

    const receipt = (await db.doc(`users/${student}/lessonData/${f.sid}/reactionReceipts/${canceled.reactionId}`).get()).data();
    assert.ok(receipt.deletedAt);
    assert.equal(receipt.uid, undefined);
    const restored = await mutate(student, "setReaction", { ...current, selected: true, baseRevision: 2 });
    assert.equal(restored.receipt.deletedAt, null);
    assert.equal(restored.receipt.revision, 3);
    await expectReason(() => mutate(student, "setReaction", { ...current, selected: false, baseRevision: 1 }),
        "REACTION_CHANGED");
    await invoke(student, "setReaction", cancel); // Late replay cannot cancel the new selection.
    assert.equal((await stats("summary")).reactionEventCount, 4);

    const closed = await mutate(owner, "setQuestionsOpen", { sessionId: f.sid,
        baseRevision: revisit.session.revision, open: false });
    await expectReason(() => mutate(student, "setReaction", { ...current, selected: false, baseRevision: 3 }),
        "REACTIONS_CLOSED");
    await mutate(owner, "setQuestionsOpen", { sessionId: f.sid, baseRevision: closed.session.revision, open: true });
    await mutate(student, "setReaction", { ...current, selected: false, baseRevision: 3 });
    await mutate(student, "setReaction", { ...current, type: "SPEED", selected: false, baseRevision: 1 });
    value = await stats(scopes[2]);
    assert.equal(value.reactionEventCount, 1);
    assert.equal(value.uniqueReactingStudents, 1);
    assert.equal(value.uniqueStudentsByType.SPEED, 0);
    assert.equal((await stats("summary")).uniqueReactingStudents, 2);
});

test("understood is counted and canceled independently on existing class analytics", async () => {
    const f = await fixture();
    await join(student, f);
    const started = await start(f);
    const topicId = f.topics[0].id;
    const visitId = started.session.progress.currentVisitId;
    const payload = { sessionId: f.sid, topicId, visitId };
    await mutate(student, "submitReaction", { ...payload, type: "UNDERSTANDING" });
    const stats = ["summary", "topic_" + topicId, "visit_" + visitId]
        .map(scope => db.doc("sessions/" + f.sid + "/analytics/" + scope));
    for (const ref of stats)
        await ref.update({
            "eventCountsByType.UNDERSTOOD": FieldValue.delete(),
            "uniqueStudentsByType.UNDERSTOOD": FieldValue.delete()
        });

    const reaction = { ...payload, type: "UNDERSTOOD" };
    const submitted = await mutate(student, "setReaction", { ...reaction, selected: true, baseRevision: 0 });
    assert.equal(submitted.receipt.type, "UNDERSTOOD");
    for (const ref of stats) {
        const value = (await ref.get()).data();
        assert.equal(value.reactionEventCount, 2);
        assert.equal(value.uniqueReactingStudents, 1);
        assert.equal(value.eventCountsByType.UNDERSTANDING, 1);
        assert.equal(value.eventCountsByType.UNDERSTOOD, 1);
        assert.equal(value.uniqueStudentsByType.UNDERSTOOD, 1);
    }

    const canceled = await mutate(student, "setReaction", {
        ...reaction, selected: false, baseRevision: submitted.receipt.revision
    });
    assert.ok(canceled.receipt.deletedAt);
    for (const ref of stats) {
        const value = (await ref.get()).data();
        assert.equal(value.reactionEventCount, 1);
        assert.equal(value.uniqueReactingStudents, 1);
        assert.equal(value.eventCountsByType.UNDERSTANDING, 1);
        assert.equal(value.eventCountsByType.UNDERSTOOD, 0);
        assert.equal(value.uniqueStudentsByType.UNDERSTOOD, 0);
    }
});

test("closing question intake also rejects delayed reactions; reopening accepts both without duplicate counts", async () => {
    const f = await fixture();
    await join(student, f);
    const started = await start(f);
    const accepted = {
        operationId: op(), sessionId: f.sid, topicId: f.topics[0].id,
        visitId: started.session.progress.currentVisitId, type: "UNDERSTANDING"
    };
    await invoke(student, "submitReaction", accepted);
    // This payload represents a tap queued before the teacher closes intake.
    const delayed = { ...accepted, operationId: op(), type: "TERM" };
    const closed = await mutate(owner, "setQuestionsOpen", {
        sessionId: f.sid, baseRevision: started.session.revision, open: false
    });
    await expectReason(() => invoke(student, "submitReaction", delayed), "REACTIONS_CLOSED");
    await expectReason(() => mutate(student, "submitQuestion", {
        sessionId: f.sid, id: op(), topicId: f.topics[0].id, text: "마감 후 질문", baseRevision: 0
    }), "QUESTIONS_CLOSED");
    await expectReason(() => mutate(student, "setQuestionsOpen", {
        sessionId: f.sid, baseRevision: closed.session.revision, open: true
    }), "OWNER_REQUIRED");
    // Lost ACKs for a previously committed reaction remain safe to recover.
    await invoke(student, "submitReaction", accepted);
    assert.equal((await db.collection(`sessions/${f.sid}/reactionEvents`).get()).size, 1);
    assert.equal((await db.doc(`sessions/${f.sid}/analytics/summary`).get()).data().reactionEventCount, 1);
    const draftId = op();
    await mutate(student, "savePersonalDraft", {
        sessionId: f.sid, id: draftId, topicId: f.topics[0].id, text: "마감 중에도 남기는 초안", baseRevision: 0
    });
    assert.equal((await db.doc(`users/${student}/lessonData/${f.sid}/questionDrafts/${draftId}`).get()).data().text,
        "마감 중에도 남기는 초안");
    await expectReason(() => mutate(owner, "setQuestionsOpen", {
        sessionId: f.sid, baseRevision: started.session.revision, open: true
    }), "VERSION_CONFLICT");
    await mutate(owner, "setQuestionsOpen", {
        sessionId: f.sid, baseRevision: closed.session.revision, open: true
    });
    await mutate(student, "submitReaction", { ...delayed, operationId: op() });
    await mutate(student, "submitQuestion", {
        sessionId: f.sid, id: draftId, topicId: f.topics[0].id, text: "마감 중에도 남기는 초안", baseRevision: 1
    });
    assert.equal((await db.doc(`sessions/${f.sid}/analytics/summary`).get()).data().reactionEventCount, 2);
});

test("only the author can withdraw an unanswered question and the cancellation is counted once", async () => {
    const f = await fixture();
    const inv = await invite(f);
    await join(student, f, inv);
    await join(other, f, inv);
    const topicId = f.topics[0].id;
    const payload = { sessionId: f.sid, topicId, id: op(), text: "취소할 질문", baseRevision: 0 };
    const submitted = { ...payload, operationId: op() };
    await invoke(student, "submitQuestion", submitted);
    await mutate(other, "submitQuestion", { ...payload, id: op(), text: "다른 학생의 질문" });
    const request = { sessionId: f.sid, id: payload.id, operationId: op() };
    for (const uid of [owner, other]) {
        await expectReason(() => invoke(uid, "withdrawQuestion", request), "QUESTION_AUTHOR_REQUIRED");
    }
    const session = await invoke(owner, "getSessionBootstrap", { sessionId: f.sid });
    await mutate(owner, "setQuestionsOpen", { sessionId: f.sid, baseRevision: session.session.revision, open: false });
    const withdrawn = await invoke(student, "withdrawQuestion", request);
    assert.equal(withdrawn.question.status, "WITHDRAWN");
    assert.ok(withdrawn.question.deletedAt);
    assert.equal(withdrawn.question.revision, 2);
    assert.equal(withdrawn.question.text, "");
    assert.deepEqual(await invoke(student, "withdrawQuestion", request), withdrawn);
    assert.deepEqual(await invoke(student, "withdrawQuestion", { ...request, operationId: op() }), withdrawn);
    await invoke(student, "submitQuestion", submitted); // Retry a previously acknowledged request.
    await expectReason(() => mutate(student, "submitQuestion", payload), "QUESTION_WITHDRAWN");
    await expectReason(() => mutate(owner, "publishAnswer", {
        sessionId: f.sid, id: payload.id, text: "늦은 답변", baseRevision: 0, baseAnswerVersion: 0
    }), "NOT_FOUND");
    for (const key of ["summary", `topic_${topicId}`]) {
        const stats = (await db.doc(`sessions/${f.sid}/analytics/${key}`).get()).data();
        assert.equal(stats.submittedQuestionCount, 1);
        assert.equal(stats.unansweredQuestionCount, 1);
    }
    const teacherQuestions = collection(env.authenticatedContext(owner).firestore(), "sessions", f.sid, "questions");
    assert.equal((await getDocs(query(teacherQuestions, orderBy("submittedAt", "desc")))).size, 1);
    const studentQuestions = collection(env.authenticatedContext(student).firestore(), "sessions", f.sid, "questions");
    assert.equal((await assertSucceeds(getDocs(query(studentQuestions,
        where("authorUid", "==", student), where("status", "==", "WITHDRAWN"))))).size, 1);
    await assertFails(getDocs(query(studentQuestions, where("status", "==", "WITHDRAWN"))));
});

test("publishing an answer and withdrawing a submission cannot both win", async () => {
    const f = await fixture();
    await join(student, f);
    const payload = { sessionId: f.sid, id: op(), topicId: f.topics[0].id, text: "답변 공개 확인", baseRevision: 0 };
    const answer = { sessionId: f.sid, id: payload.id, text: "답변", baseRevision: 0, baseAnswerVersion: 0 };
    const request = { sessionId: f.sid, id: payload.id };
    await mutate(student, "submitQuestion", payload);
    const [publish, withdraw] = await Promise.allSettled([
        mutate(owner, "publishAnswer", answer), mutate(student, "withdrawQuestion", request)
    ]);
    assert.notEqual(publish.status, withdraw.status);
    const counted = (await db.doc(`sessions/${f.sid}/analytics/summary`).get()).data();
    assert.equal(counted.unansweredQuestionCount, 0);
    if (publish.status === "fulfilled") {
        assert.equal(withdraw.reason.reason, "QUESTION_ANSWERED");
        assert.equal(counted.submittedQuestionCount, 1);
    } else {
        assert.equal(publish.reason.reason, "NOT_FOUND");
        assert.equal(counted.submittedQuestionCount, 0);
    }
    const alreadyAnswered = op();
    await mutate(student, "submitQuestion", { ...payload, id: alreadyAnswered });
    await mutate(owner, "publishAnswer", { ...answer, id: alreadyAnswered });
    await expectReason(() => mutate(student, "withdrawQuestion", { ...request, id: alreadyAnswered }), "QUESTION_ANSWERED");
});

test("personal drafts stay private; submissions pin topic and reject edits; publish has no author fields", async () => {
    const f = await fixture();
    await join(student, f);
    const qid = op();
    const draft = {
        sessionId: f.sid,
        id: qid,
        topicId: f.topics[0].id,
        text: "원고",
        baseRevision: 0
    };
    await mutate(student, "savePersonalDraft", draft);
    assert.equal((await db.collection(`sessions/${f.sid}/questions`).get()).size, 0);
    await expectReason(() => mutate(student, "savePersonalDraft", {
        ...draft,
        text: "다른 기기의 원고"
    }), "VERSION_CONFLICT");
    await mutate(student, "submitQuestion", {
        ...draft,
        text: "제출 질문",
        baseRevision: 1
    });
    await expectReason(() => mutate(student, "submitQuestion", {
        ...draft,
        text: "변경",
        baseRevision: 2
    }), "QUESTION_LOCKED");
    await expectReason(() => mutate(student, "savePersonalDraft", {
        ...draft,
        baseRevision: 2
    }), "DRAFT_CLOSED");
    await mutate(owner, "saveAnswerDraft", {
        sessionId: f.sid,
        id: qid,
        text: "미공개 교수 초안",
        baseRevision: 0,
        baseAnswerVersion: 0
    });
    assert.equal((await db.doc(`sessions/${f.sid}/questions/${qid}`).get()).data().answer, "");
    await mutate(owner, "publishAnswer", {
        sessionId: f.sid,
        id: qid,
        text: "검토한 공개 답변",
        baseRevision: 1,
        baseAnswerVersion: 0
    });
    const q = (await db.doc(`sessions/${f.sid}/questions/${qid}`).get()).data();
    const pub = (await db.doc(`sessions/${f.sid}/publicQna/${q.publicId}`).get()).data();
    assert.equal(q.topicId, f.topics[0].id);
    assert.equal(pub.answerText, "검토한 공개 답변");
    assert.equal(Object.hasOwn(pub, "authorUid"), false);
    assert.equal(Object.hasOwn(pub, "answerDraft"), false);
    await mutate(owner, "publishAnswer", {
        sessionId: f.sid,
        id: qid,
        text: "수정 재공개",
        baseRevision: 2,
        baseAnswerVersion: 1
    });
    assert.equal((await db.doc(`sessions/${f.sid}/analytics/summary`).get()).data().unansweredQuestionCount, 0);
});
test("blank question drafts are rejected and deletion is limited to private unsubmitted drafts", async () => {
    const f = await fixture();
    await join(student, f);
    const topicId = f.topics[0].id;
    const payload = { sessionId: f.sid, topicId, id: op(), text: " \n \t ", baseRevision: 0 };
    const draft = db.doc(`users/${student}/lessonData/${f.sid}/questionDrafts/${payload.id}`);
    await expectReason(() => mutate(student, "savePersonalDraft", payload), "INVALID_TEXT");
    assert.equal((await draft.get()).exists, false);
    await mutate(student, "saveMark", { ...payload, id: op() });
    await mutate(student, "savePersonalDraft", { ...payload, text: "이어 쓸 원고" });
    await expectReason(() => mutate(student, "savePersonalDraft", { ...payload, baseRevision: 1 }), "INVALID_TEXT");
    assert.equal((await draft.get()).data().text, "이어 쓸 원고");
    const remove = { sessionId: f.sid, id: payload.id, kind: "questionDrafts", baseRevision: 1 };
    await expectReason(() => mutate(other, "deletePersonalItem", remove), "NOT_FOUND");
    const deleted = await mutate(student, "deletePersonalItem", remove);
    assert.ok(deleted.document.deletedAt);
    assert.equal(deleted.document.text, "");
    assert.equal((await mutate(student, "deletePersonalItem", remove)).document.revision, 2);
    await expectReason(() => mutate(student, "savePersonalDraft", { ...payload, text: "되돌리지 않음", baseRevision: 2 }), "DRAFT_CLOSED");
    const submitted = { ...payload, id: op(), text: "이미 제출한 질문" };
    await mutate(student, "submitQuestion", submitted);
    await expectReason(() => mutate(student, "deletePersonalItem", { ...remove, id: submitted.id }), "DRAFT_CLOSED");
});

test("closed question intake preserves draft; referenced outline IDs and personal tombstones are protected", async () => {
    const f = await fixture();
    await join(student, f);
    const qid = op(),
        payload = {
            sessionId: f.sid,
            id: qid,
            topicId: f.topics[0].id,
            text: "보존할 원고",
            baseRevision: 0
        };
    await mutate(student, "savePersonalDraft", payload);
    const state = await invoke(owner, "getSessionBootstrap", {
        sessionId: f.sid
    });
    await mutate(owner, "setQuestionsOpen", {
        sessionId: f.sid,
        baseRevision: state.session.revision,
        open: false
    });
    await expectReason(() => mutate(student, "submitQuestion", {
        ...payload,
        baseRevision: 1
    }), "QUESTIONS_CLOSED");
    assert.equal((await db.doc(`users/${student}/lessonData/${f.sid}/questionDrafts/${qid}`).get()).data().text, "보존할 원고");
    await expectReason(() => mutate(owner, "updateOutline", {
        sessionId: f.sid,
        baseRevision: 1,
        title: "수업",
        description: "",
        topics: [f.topics[1]]
    }), "TOPIC_IN_USE");
    await mutate(student, "deletePersonalItem", {
        sessionId: f.sid,
        id: qid,
        kind: "questionDrafts",
        baseRevision: 1
    });
    await expectReason(() => mutate(student, "savePersonalDraft", {
        ...payload,
        baseRevision: 2
    }), "DRAFT_CLOSED");
});
test("security rules prevent private drafts, other questions, raw reaction access and all direct writes", async () => {
    const f = await fixture();
    await join(student, f);
    await join(other, f);
    const qid = op();
    await mutate(student, "submitQuestion", {
        sessionId: f.sid,
        id: qid,
        topicId: f.topics[0].id,
        text: "비공개 질문",
        baseRevision: 0
    });
    const sd = env.authenticatedContext(student).firestore(),
        od = env.authenticatedContext(other).firestore(),
        td = env.authenticatedContext(owner).firestore(),
        outsider = env.authenticatedContext("outsider").firestore(),
        anon = env.unauthenticatedContext().firestore();
    await assertSucceeds(getDoc(doc(sd, `sessions/${f.sid}`)));
    await assertFails(getDoc(doc(outsider, `sessions/${f.sid}`)));
    await assertFails(getDoc(doc(anon, `sessions/${f.sid}`)));
    await assertSucceeds(getDoc(doc(sd, `sessions/${f.sid}/questions/${qid}`)));
    await assertFails(getDoc(doc(od, `sessions/${f.sid}/questions/${qid}`)));
    await assertFails(getDocs(collection(sd, `sessions/${f.sid}/questions`)));
    await assertSucceeds(getDocs(query(collection(sd, `sessions/${f.sid}/questions`), where("authorUid", "==", student), orderBy("submittedAt", "desc"))));
    await assertFails(getDoc(doc(td, `users/${student}/lessonData/${f.sid}/questionDrafts/${qid}`)));
    await assertFails(getDocs(collection(td, `sessions/${f.sid}/reactionEvents`)));
    await assertFails(getDocs(collection(sd, `sessions/${f.sid}/analytics`)));
    await assertFails(getDoc(doc(sd, `sessions/${f.sid}/teacherDrafts/answer_${qid}`)));
    await assertFails(getDocs(collection(sd, "joinCodes")));
    await assertFails(setDoc(doc(td, `sessions/${f.sid}`), {
        ownerUid: student
    }));
    await assertFails(setDoc(doc(sd, `users/${student}/lessonData/${f.sid}/marks/${op()}`), {
        text: "직접 쓰기"
    }));
});
test("50 joined students produce exact counts under concurrent reaction submission", {
    timeout: 180000
}, async () => {
    const f = await fixture(),
        inv = await invite(f),
        students = Array.from({
            length: 50
        }, (_, i) => `load-student-${i}`);
    // Joining is setup; simultaneous class reaction is the measured contention case.
    for (const uid of students) await join(uid, f, inv);
    const begun = await start(f),
        visit = begun.session.progress.currentVisitId;
    const settled = await Promise.allSettled(students.map(uid => mutate(uid, "submitReaction", {
        sessionId: f.sid,
        visitId: visit,
        topicId: f.topics[0].id,
        type: "UNDERSTANDING"
    })));
    // Transient transaction failures are retried as the application outbox does, with a fresh
    // operation but the same business reaction ID; neither path may double count.
    for (let i = 0; i < settled.length; i++)
        if (settled[i].status === "rejected") await mutate(students[i], "submitReaction", {
            sessionId: f.sid,
            visitId: visit,
            topicId: f.topics[0].id,
            type: "UNDERSTANDING"
        });
    const summary = (await db.doc(`sessions/${f.sid}/analytics/summary`).get()).data();
    assert.equal(summary.studentCount, 50);
    assert.equal(summary.reactionEventCount, 50);
    assert.equal(summary.uniqueReactingStudents, 50);
});
test("callable requires real emulator Auth and executes a session command over HTTP", async () => {
    const endpoint = "http://127.0.0.1:5001/demo-classpulse/asia-northeast3/classPulse";
    const noAuth = await fetch(endpoint, {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify({
            data: {
                command: "getSessionBootstrap",
                payload: {
                    sessionId: op()
                }
            }
        })
    });
    assert.equal(noAuth.status, 401);
    const signed = await fetch("http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signUp?key=emulator-key", {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify({
            email: `${op()}@example.test`,
            password: "emulator-only-password",
            returnSecureToken: true
        })
    }).then(r => r.json());
    assert.ok(signed.idToken);
    const sid = op();
    const response = await fetch(endpoint, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${signed.idToken}`
        },
        body: JSON.stringify({
            data: {
                command: "createSession",
                payload: {
                    operationId: op(),
                    sessionId: sid,
                    title: "실제 Callable 경로",
                    description: "",
                    topics: [{
                        id: op(),
                        title: "대주제"
                    }]
                }
            }
        })
    });
    const body = await response.json();
    assert.equal(response.status, 200, JSON.stringify(body));
    assert.equal(body.result.sessionId, sid);
    assert.equal((await db.doc(`sessions/${sid}`).get()).data().ownerUid, signed.localId);
});
