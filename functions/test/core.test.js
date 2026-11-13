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
