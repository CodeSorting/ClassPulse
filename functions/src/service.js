"use strict";
const crypto = require("node:crypto");
const { createLearningService } = require("./learning");
const {
    Timestamp,
    FieldValue
} = require("firebase-admin/firestore");
const TYPES = ["UNDERSTANDING", "SPEED", "TERM", "CALCULATION"];
class DomainError extends Error {
    constructor(reason, message, code = "failed-precondition") {
        super(message);
        this.reason = reason;
        this.code = code;
    }
}
const fail = (reason, message, code) => {
    throw new DomainError(reason, message, code);
};

function id(v) {
    if (typeof v !== "string" || !/^[a-zA-Z0-9_-]{8,100}$/.test(v)) fail("INVALID_ID", "항목을 다시 선택해 주세요.", "invalid-argument");
    return v;
}

function text(v, max, required = true) {
    if (typeof v !== "string" || v.length > max || required && !v.trim()) fail("INVALID_TEXT", "입력 내용과 길이를 확인해 주세요.", "invalid-argument");
    return v.trim();
}

function integer(v) {
    if (!Number.isSafeInteger(v) || v < 0) fail("INVALID_VERSION", "버전을 다시 확인해 주세요.", "invalid-argument");
    return v;
}

function canonical(v) {
    if (Array.isArray(v)) return v.map(canonical);
    if (v && typeof v === "object") return Object.fromEntries(Object.keys(v).sort().map(k => [k, canonical(v[k])]));
    return v;
}
const hash = v => crypto.createHash("sha256").update(typeof v === "string" ? v : JSON.stringify(canonical(v))).digest("hex");

function wire(v) {
    if (v instanceof Timestamp) return v.toMillis();
    if (Array.isArray(v)) return v.map(wire);
    if (v && typeof v === "object") return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, wire(x)]));
    return v;
}

function fresh(now, values = {}) {
    return {
        schemaVersion: 1,
        revision: 1,
        createdAt: now,
        updatedAt: now,
        deletedAt: null,
        ...values
    };
}

function bump(old, now, values = {}) {
    return {
        ...old,
        ...values,
        revision: (old.revision || 0) + 1,
        updatedAt: now
    };
}

function emptyStats(now) {
    return fresh(now, {
        reactionEventCount: 0,
        uniqueReactingStudents: 0,
        eventCountsByType: Object.fromEntries(TYPES.map(x => [x, 0])),
        uniqueStudentsByType: Object.fromEntries(TYPES.map(x => [x, 0])),
        submittedQuestionCount: 0,
        unansweredQuestionCount: 0,
        studentCount: 0
    });
}

function createService(db, {
    clock = Date.now,
    randomCode = () => String(crypto.randomInt(10000)).padStart(4, "0"),
    learningProviders = {}
} = {}) {
    const sessionRef = sid => db.doc(`sessions/${id(sid)}`);
    const personal = (uid, sid, kind, key) => db.doc(`users/${uid}/lessonData/${id(sid)}/${kind}/${id(key)}`);
    const nowStamp = () => Timestamp.fromMillis(clock());
    async function access(tx, sid, uid, ownerOnly = false) {
        const ref = sessionRef(sid),
            snap = await tx.get(ref),
            s = snap.data();
        if (!s || s.deletedAt) fail("NOT_FOUND", "수업을 찾지 못했어요.", "not-found");
        const owner = s.ownerUid === uid;
        if (ownerOnly && !owner) fail("OWNER_REQUIRED", "담당 교수만 할 수 있어요.", "permission-denied");
        const member = await tx.get(ref.collection("members").doc(uid));
        if (!owner && member.data()?.state !== "ACTIVE") fail("MEMBERSHIP_REQUIRED", "먼저 수업에 참여해 주세요.", "permission-denied");
        return {
            ref,
            s,
            owner,
            member: member.data()
        };
    }
    async function command(uid, kind, p, action) {
        id(p.operationId);
        const requestHash = hash({
            kind,
            p
        });
        const receipt = db.doc(`users/${uid}/commandReceipts/${p.operationId}`);
        return db.runTransaction(async tx => {
            const previous = await tx.get(receipt);
            if (previous.exists) {
                if (previous.data().payloadHash !== requestHash) fail("OPERATION_REUSED", "다른 내용에 같은 전송 번호를 사용할 수 없어요.", "already-exists");
                return previous.data().result;
            }
            const now = nowStamp(),
                result = wire(await action(tx, now));
            tx.create(receipt, fresh(now, {
                kind,
                payloadHash: requestHash,
                state: "COMMITTED",
                result,
                expiresAt: Timestamp.fromMillis(clock() + 30 * 86400000)
            }));
            return result;
        });
    }
    async function statsForQuestions(tx, ref, topicId, now, submittedDelta, unansweredDelta) {
        const refs = [ref.collection("analytics").doc("summary"), ref.collection("analytics").doc(`topic_${topicId}`)];
        return refs.map(target => ({
            ref: target,
            value: {
                revision: FieldValue.increment(1),
                updatedAt: now,
                submittedQuestionCount: FieldValue.increment(submittedDelta),
                unansweredQuestionCount: FieldValue.increment(unansweredDelta)
            }
        }));
    }

    function topicsInput(value) {
        if (!Array.isArray(value) || value.length < 1 || value.length > 100) fail("INVALID_TOPICS", "대주제를 1~100개 준비해 주세요.", "invalid-argument");
        const ids = new Set();
        return value.map((v, i) => {
            const topicId = id(v.id);
            if (ids.has(topicId) || v.parentTopicId != null) fail("INVALID_TOPICS", "대주제 ID와 구조를 확인해 주세요.", "invalid-argument");
            ids.add(topicId);
            return {
                id: topicId,
                title: text(v.title, 120),
                position: i,
                parentTopicId: null
            };
        });
    }
    async function createSession(uid, p, name) {
        const sid = id(p.sessionId),
            title = text(p.title, 120),
            description = text(p.description || "", 2000, false),
            topics = topicsInput(p.topics);
        return command(uid, "createSession", p, async (tx, now) => {
            const ref = sessionRef(sid);
            if ((await tx.get(ref)).exists) fail("ID_IN_USE", "이미 있는 수업 번호예요.", "already-exists");
            const s = fresh(now, {
                ownerUid: uid,
                teacherDisplayName: text(name || "담당 교수", 120),
                title,
                description,
                status: "READY",
                questionsOpen: true,
                admissionOpen: true,
                inviteEpoch: 0,
                outlineVersion: 1,
                topicCount: topics.length,
                progressVersion: 0,
                progress: {
                    currentTopicId: null,
                    currentVisitId: null,
                    currentVisitStartedAt: null,
                    resumeTopicId: null,
                    completedTopicIds: []
                }
            });
            tx.create(ref, s);
            for (const t of topics) {
                tx.create(ref.collection("topics").doc(t.id), fresh(now, {
                    ...t,
                    sessionId: sid
                }));
                tx.create(ref.collection("analytics").doc(`topic_${t.id}`), emptyStats(now));
            }
            tx.create(ref.collection("members").doc(uid), fresh(now, {
                role: "OWNER",
                state: "ACTIVE",
                joinedAt: now
            }));
            tx.create(db.doc(`users/${uid}/lessonRefs/${sid}`), fresh(now, {
                sessionId: sid,
                role: "OWNER",
                state: "ACTIVE",
                joinedAt: now
            }));
            tx.set(ref.collection("analytics").doc("summary"), emptyStats(now));
            return {
                sessionId: sid,
                session: {
                    id: sid,
                    ...s
                }
            };
        });
    }
    async function updateOutline(uid, p) {
        const topics = topicsInput(p.topics),
            title = text(p.title, 120),
            description = text(p.description || "", 2000, false);
        return command(uid, "updateOutline", p, async (tx, now) => {
            const {
                ref,
                s
            } = await access(tx, p.sessionId, uid, true);
            if (s.status !== "READY") fail("OUTLINE_LOCKED", "수업 시작 전만 목차를 편집할 수 있어요.");
            if (s.outlineVersion !== integer(p.baseRevision)) fail("VERSION_CONFLICT", "다른 기기에서 목차가 바뀌었어요.");
            const old = await tx.get(ref.collection("topics"));
            const summary = await tx.get(ref.collection("analytics").doc("summary"));
            const removed = old.docs.filter(d => !d.data().deletedAt && !topics.some(t => t.id === d.id));
            if (removed.length && (summary.data()?.studentCount || 0) > 0) fail("TOPIC_IN_USE", "참여 학생이 있는 수업의 대주제는 삭제할 수 없어요.");
            for (const t of topics) {
                const before = old.docs.find(d => d.id === t.id)?.data();
                if (before?.deletedAt) fail("TOPIC_DELETED", "삭제한 대주제 번호는 다시 사용할 수 없어요.");
            }
            const updated = bump(s, now, {
                title,
                description,
                outlineVersion: s.outlineVersion + 1,
                topicCount: topics.length
            });
            tx.set(ref, updated);
            for (const t of topics) {
                const before = old.docs.find(d => d.id === t.id)?.data();
                tx.set(ref.collection("topics").doc(t.id), before ? bump(before, now, t) : fresh(now, {
                    ...t,
                    sessionId: p.sessionId
                }));
                if (!before) tx.create(ref.collection("analytics").doc(`topic_${t.id}`), emptyStats(now));
            }
            for (const d of removed) tx.set(d.ref, bump(d.data(), now, {
                deletedAt: now
            }));
            return {
                sessionId: p.sessionId,
                session: {
                    id: p.sessionId,
                    ...updated
                }
            };
        });
    }
    async function bootstrap(uid, p) {
        return db.runTransaction(async tx => {
            const {
                ref,
                s,
                owner,
                member
            } = await access(tx, p.sessionId, uid);
            const topics = await tx.get(ref.collection("topics").orderBy("position"));
            return wire({
                session: {
                    id: p.sessionId,
                    ...s
                },
                topics: topics.docs.filter(x => !x.data().deletedAt).map(x => ({
                    id: x.id,
                    ...x.data()
                })),
                membership: member,
                owner
            });
        });
    }
    async function issueInvite(uid, p) {
        for (let attempt = 0; attempt < 30; attempt++) {
            const code = randomCode(),
                token = crypto.randomBytes(32).toString("base64url"),
                tokenHash = hash(token);
            if (!/^\d{4}$/.test(code)) throw new Error("Invalid code generator");
            try {
                return await command(uid, "issueInvite", p, async (tx, now) => {
                    const {
                        ref,
                        s
                    } = await access(tx, p.sessionId, uid, true);
                    if (s.status === "ENDED" || !s.admissionOpen) fail("ADMISSION_CLOSED", "신규 참여가 마감된 수업이에요.");
                    const codeRef = db.doc(`joinCodes/${code}`),
                        previous = await tx.get(codeRef);
                    if (previous.exists && previous.data().expiresAt.toMillis() > clock() && previous.data().sessionId !== p.sessionId) fail("CODE_COLLISION", "코드 충돌");
                    const currentRef = ref.collection("teacherDrafts").doc("invite_current"),
                        old = await tx.get(currentRef);
                    const oldCode = old.exists ? await tx.get(db.doc(`joinCodes/${old.data().code}`)) : null;
                    const oldLink = old.exists ? await tx.get(db.doc(`joinLinks/${old.data().tokenHash}`)) : null;
                    const epoch = s.inviteEpoch + 1,
                        expiresAt = Timestamp.fromMillis(clock() + 86400000);
                    const mapping = fresh(now, {
                        sessionId: p.sessionId,
                        epoch,
                        expiresAt
                    });
                    if (oldCode?.exists && oldCode.data().sessionId === p.sessionId && oldCode.id !== code) tx.update(oldCode.ref, {
                        expiresAt: now
                    });
                    if (oldLink?.exists && oldLink.data().sessionId === p.sessionId) tx.update(oldLink.ref, {
                        expiresAt: now
                    });
                    tx.set(codeRef, mapping);
                    tx.set(db.doc(`joinLinks/${tokenHash}`), mapping);
                    tx.set(currentRef, fresh(now, {
                        code,
                        tokenHash,
                        link: `classpulse://join/invite/${token}`,
                        epoch,
                        expiresAt
                    }));
                    tx.set(ref, bump(s, now, {
                        inviteEpoch: epoch
                    }));
                    return {
                        sessionId: p.sessionId,
                        epoch
                    };
                });
            } catch (e) {
                if (e.reason !== "CODE_COLLISION") throw e;
            }
        }
        fail("CODES_BUSY", "사용 가능한 코드를 준비하지 못했어요. 잠시 후 다시 시도해 주세요.", "resource-exhausted");
    }
    async function getInvite(uid, p) {
        return db.runTransaction(async tx => {
            const {
                ref
            } = await access(tx, p.sessionId, uid, true), value = await tx.get(ref.collection("teacherDrafts").doc("invite_current"));
            return wire(value.exists ? value.data() : {
                code: "",
                link: "",
                expiresAt: 0
            });
        });
    }

    function inviteLocation(p) {
        if (p.kind === "CODE" && typeof p.value === "string" && /^\d{4}$/.test(p.value)) return `joinCodes/${p.value}`;
        if (p.kind === "LINK" && typeof p.value === "string" && /^[a-zA-Z0-9_-]{40,100}$/.test(p.value)) return `joinLinks/${hash(p.value)}`;
        fail("INVALID_INVITE", "초대 링크 또는 코드 네 자리를 확인해 주세요.", "invalid-argument");
    }
    async function resolveInvite(uid, p) {
        const location = inviteLocation(p),
            previewToken = crypto.randomBytes(32).toString("base64url");
        // Commit the attempt counter even on an invalid invitation, so guessing is bounded.
        await db.runTransaction(async tx => {
            const ref = db.doc(`inviteAttempts/${hash(uid)}`),
                snapshot = await tx.get(ref),
                old = snapshot.data();
            const freshWindow = !old || clock() - old.windowStart.toMillis() >= 60000;
            if (!freshWindow && old.count >= 20) fail("TOO_MANY_ATTEMPTS", "참여 확인이 너무 많아요. 잠시 후 다시 시도해 주세요.", "resource-exhausted");
            tx.set(ref, {
                windowStart: freshWindow ? nowStamp() : old.windowStart,
                count: freshWindow ? 1 : old.count + 1
            });
        });
        return db.runTransaction(async tx => {
            const invite = await tx.get(db.doc(location)),
                v = invite.data();
            if (!v || v.expiresAt.toMillis() <= clock()) fail("INVITE_EXPIRED", "초대가 만료됐거나 코드를 찾지 못했어요.", "not-found");
            const ref = sessionRef(v.sessionId),
                session = await tx.get(ref),
                s = session.data();
            if (!s || s.deletedAt || s.status === "ENDED" || !s.admissionOpen || s.inviteEpoch !== v.epoch) fail("ADMISSION_CLOSED", "지금은 새로 참여할 수 없는 수업이에요.");
            tx.create(db.doc(`joinPreviews/${hash(previewToken)}`), {
                uid,
                sessionId: v.sessionId,
                epoch: v.epoch,
                location,
                expiresAt: Timestamp.fromMillis(clock() + 120000)
            });
            return {
                sessionId: v.sessionId,
                title: s.title,
                teacherDisplayName: s.teacherDisplayName,
                status: s.status,
                previewToken
            };
        });
    }
    async function joinSession(uid, p) {
        if (typeof p.previewToken !== "string" || !/^[a-zA-Z0-9_-]{40,100}$/.test(p.previewToken)) fail("INVALID_INVITE", "수업을 다시 확인해 주세요.", "invalid-argument");
        return command(uid, "joinSession", p, async (tx, now) => {
            const preview = await tx.get(db.doc(`joinPreviews/${hash(p.previewToken)}`)),
                v = preview.data();
            if (!v || v.uid !== uid) fail("INVALID_PREVIEW", "수업 확인부터 다시 진행해 주세요.", "permission-denied");
            const ref = sessionRef(v.sessionId),
                snapshot = await tx.get(ref),
                s = snapshot.data();
            if (!s || s.deletedAt) fail("NOT_FOUND", "수업을 찾지 못했어요.", "not-found");
            const memberRef = ref.collection("members").doc(uid),
                member = await tx.get(memberRef);
            if (member.data()?.state === "ACTIVE") return {
                sessionId: v.sessionId,
                alreadyJoined: true
            };
            if (member.exists) fail("MEMBERSHIP_REVOKED", "참여 권한을 담당 교수에게 확인해 주세요.", "permission-denied");
            const mapping = await tx.get(db.doc(v.location));
            const summaryRef = ref.collection("analytics").doc("summary");
            if (v.expiresAt.toMillis() <= clock() || !mapping.exists || mapping.data().expiresAt.toMillis() <= clock() || s.inviteEpoch !== v.epoch || mapping.data().epoch !== v.epoch || mapping.data().sessionId !== v.sessionId) fail("INVITE_EXPIRED", "초대가 바뀌었어요. 수업을 다시 확인해 주세요.");
            if (s.status === "ENDED" || !s.admissionOpen) fail("ADMISSION_CLOSED", "신규 참여가 마감됐어요.");
            tx.create(memberRef, fresh(now, {
                role: "STUDENT",
                state: "ACTIVE",
                joinedAt: now
            }));
            tx.set(db.doc(`users/${uid}/lessonRefs/${v.sessionId}`), fresh(now, {
                sessionId: v.sessionId,
                role: "STUDENT",
                state: "ACTIVE",
                joinedAt: now
            }));
            tx.update(summaryRef, {
                studentCount: FieldValue.increment(1),
                revision: FieldValue.increment(1),
                updatedAt: now
            });
            return {
                sessionId: v.sessionId,
                alreadyJoined: false
            };
        });
    }
    async function changeProgress(uid, p) {
        const action = p.action;
        if (!["START", "COMPLETE_NEXT", "REVISIT", "RESUME", "END"].includes(action)) fail("INVALID_ACTION", "진도 동작을 다시 선택해 주세요.", "invalid-argument");
        return command(uid, "changeProgress", p, async (tx, now) => {
            const {
                ref,
                s
            } = await access(tx, p.sessionId, uid, true);
            if (s.progressVersion !== integer(p.expectedProgressVersion)) fail("VERSION_CONFLICT", "다른 기기에서 진도가 바뀌었어요. 최신 진도를 확인해 주세요.");
            if (action === "START" ? s.status !== "READY" : s.status !== "LIVE") fail("INVALID_STATE", "현재 수업 상태에서는 할 수 없는 동작이에요.");
            const topicSnap = await tx.get(ref.collection("topics").orderBy("position")),
                topics = topicSnap.docs.filter(x => !x.data().deletedAt).map(x => ({
                    id: x.id,
                    ...x.data()
                }));
            const progress = {
                    ...s.progress,
                    completedTopicIds: [...s.progress.completedTopicIds]
                },
                version = s.progressVersion + 1;
            const oldVisit = progress.currentVisitId ? await tx.get(ref.collection("visits").doc(progress.currentVisitId)) : null;
            const fromTopicId = progress.currentTopicId,
                fromVisitId = progress.currentVisitId;
            let next = null,
                revisit = false,
                status = s.status;
            if (action === "START") {
                next = topics[0];
                status = "LIVE";
            }
            if (action === "COMPLETE_NEXT") {
                if (!progress.currentVisitId) fail("NO_ACTIVE_VISIT", "모든 주제를 마쳤어요. 수업 종료 또는 재설명을 선택해 주세요.");
                if (!progress.completedTopicIds.includes(progress.currentTopicId)) progress.completedTopicIds.push(progress.currentTopicId);
                if (progress.resumeTopicId) {
                    next = topics.find(t => t.id === progress.resumeTopicId && !progress.completedTopicIds.includes(t.id));
                    progress.resumeTopicId = null;
                } else next = topics.find(t => !progress.completedTopicIds.includes(t.id));
            }
            if (action === "REVISIT") {
                const target = id(p.topicId);
                if (!progress.completedTopicIds.includes(target)) fail("NOT_COMPLETED", "완료한 대주제를 골라 주세요.");
                next = topics.find(t => t.id === target);
                if (!next) fail("INVALID_TOPIC", "대주제를 찾지 못했어요.");
                if (!progress.resumeTopicId) progress.resumeTopicId = progress.currentTopicId;
                revisit = true;
            }
            if (action === "RESUME") {
                if (!progress.resumeTopicId) fail("NO_RESUME", "돌아갈 주제가 없어요.");
                const target = progress.resumeTopicId;
                progress.resumeTopicId = null;
                next = topics.find(t => t.id === target && !progress.completedTopicIds.includes(t.id));
                if (!next) progress.currentTopicId = target;
            }
            if (action === "END") {
                status = "ENDED";
                progress.resumeTopicId = null;
            }
            if (oldVisit?.exists) tx.set(oldVisit.ref, bump(oldVisit.data(), now, {
                endedAt: now,
                closedAtProgressVersion: version
            }));
            progress.currentVisitId = null;
            progress.currentVisitStartedAt = null;
            if (next) {
                const visitId = hash(`${p.sessionId}:${p.operationId}`);
                progress.currentTopicId = next.id;
                progress.currentVisitId = visitId;
                progress.currentVisitStartedAt = now;
                tx.create(ref.collection("visits").doc(visitId), fresh(now, {
                    topicId: next.id,
                    startedAt: now,
                    endedAt: null,
                    revisit,
                    openedAtProgressVersion: version,
                    closedAtProgressVersion: null
                }));
                tx.create(ref.collection("analytics").doc(`visit_${visitId}`), emptyStats(now));
            }
            const updated = bump(s, now, {
                status,
                progressVersion: version,
                progress,
                ...(status === "ENDED" ? {
                    admissionOpen: false
                } : {})
            });
            tx.set(ref, updated);
            tx.create(ref.collection("progressEvents").doc(hash(p.operationId)), fresh(now, {
                action,
                actorUid: uid,
                operationId: p.operationId,
                progressVersion: version,
                fromTopicId,
                fromVisitId,
                toTopicId: progress.currentTopicId,
                toVisitId: progress.currentVisitId
            }));
            return {
                sessionId: p.sessionId,
                session: {
                    id: p.sessionId,
                    ...updated
                }
            };
        });
    }
    async function setQuestionsOpen(uid, p) {
        if (typeof p.open !== "boolean") fail("INVALID_FIELD", "질문 접수 설정을 확인해 주세요.", "invalid-argument");
        return command(uid, "setQuestionsOpen", p, async (tx, now) => {
            const {
                ref,
                s
            } = await access(tx, p.sessionId, uid, true);
            if (integer(p.baseRevision) !== s.revision) fail("VERSION_CONFLICT", "수업 정보가 바뀌었어요. 다시 확인해 주세요.");
            const updated = bump(s, now, {
                questionsOpen: p.open
            });
            tx.set(ref, updated);
            return {
                session: {
                    id: p.sessionId,
                    ...updated
                }
            };
        });
    }
    async function savePersonal(uid, p, kind) {
        const key = id(p.id),
            topicId = id(p.topicId),
            value = text(p.text || "", kind === "marks" ? 200 : 1000, false);
        return command(uid, kind === "marks" ? "saveMark" : "savePersonalDraft", p, async (tx, now) => {
            const {
                ref,
                owner
            } = await access(tx, p.sessionId, uid);
            if (owner) fail("STUDENT_REQUIRED", "학생 참여 계정으로 기록해 주세요.", "permission-denied");
            const topic = await tx.get(ref.collection("topics").doc(topicId));
            if (!topic.exists || topic.data().deletedAt) fail("INVALID_TOPIC", "이 수업의 대주제를 선택해 주세요.");
            const target = personal(uid, p.sessionId, kind, key),
                snap = await tx.get(target),
                old = snap.data();
            if (old?.deletedAt || old?.submittedQuestionId) fail("DRAFT_CLOSED", "이미 제출하거나 삭제한 기록이에요.");
            if ((old?.revision || 0) !== integer(p.baseRevision)) fail("VERSION_CONFLICT", "다른 기기에서 기록이 바뀌었어요. 두 원고를 확인해 주세요.");
            const fields = {
                    sessionId: p.sessionId,
                    topicId,
                    text: value,
                    submittedQuestionId: null,
                    lastOperationId: p.operationId
                },
                v = old ? bump(old, now, fields) : fresh(now, fields);
            tx.set(target, v);
            return {
                id: key,
                kind,
                document: v
            };
        });
    }
    async function deletePersonal(uid, p) {
        if (!["marks", "questionDrafts"].includes(p.kind)) fail("INVALID_KIND", "삭제할 기록을 확인해 주세요.", "invalid-argument");
        return command(uid, "deletePersonalItem", p, async (tx, now) => {
            const target = personal(uid, p.sessionId, p.kind, p.id),
                snap = await tx.get(target),
                old = snap.data();
            if (!old) fail("NOT_FOUND", "기록을 찾지 못했어요.", "not-found");
            if (old.revision !== integer(p.baseRevision)) fail("VERSION_CONFLICT", "다른 기기에서 기록이 바뀌었어요.");
            const v = bump(old, now, {
                deletedAt: now
            });
            tx.set(target, v);
            return {
                id: p.id,
                kind: p.kind,
                document: v
            };
        });
    }
    async function submitQuestion(uid, p) {
        const qid = id(p.id),
            topicId = id(p.topicId),
            value = text(p.text, 1000);
        return command(uid, "submitQuestion", p, async (tx, now) => {
            const {
                ref,
                s,
                owner
            } = await access(tx, p.sessionId, uid);
            if (owner) fail("STUDENT_REQUIRED", "학생 참여 계정으로 질문해 주세요.", "permission-denied");
            const qref = ref.collection("questions").doc(qid),
                existing = await tx.get(qref);
            if (existing.exists) {
                const q = existing.data();
                if (q.authorUid !== uid || q.text !== value || q.topicId !== topicId) fail("QUESTION_LOCKED", "접수된 질문의 내용과 주제는 바꿀 수 없어요.");
                return {
                    id: qid,
                    question: {
                        id: qid,
                        ...q
                    }
                };
            }
            if (!s.questionsOpen) fail("QUESTIONS_CLOSED", "질문 접수가 마감됐어요. 작성한 내용은 보관돼요.");
            const topic = await tx.get(ref.collection("topics").doc(topicId));
            if (!topic.exists || topic.data().deletedAt) fail("INVALID_TOPIC", "이 수업의 대주제를 선택해 주세요.");
            const draftRef = personal(uid, p.sessionId, "questionDrafts", qid),
                draft = await tx.get(draftRef),
                d = draft.data();
            if (d?.deletedAt || d?.submittedQuestionId) fail("DRAFT_CLOSED", "이미 제출하거나 삭제한 초안이에요.");
            if ((d?.revision || 0) !== integer(p.baseRevision)) fail("VERSION_CONFLICT", "다른 기기에서 초안이 바뀌었어요. 원고를 확인해 주세요.");
            const stats = await statsForQuestions(tx, ref, topicId, now, 1, 1);
            const q = fresh(now, {
                sessionId: p.sessionId,
                authorUid: uid,
                sourceDraftId: qid,
                topicId,
                text: value,
                status: "SUBMITTED",
                submittedAt: now,
                answer: "",
                answerVersion: 0,
                publicId: crypto.randomUUID(),
                answeredAt: null
            });
            tx.create(qref, q);
            tx.set(draftRef, d ? bump(d, now, {
                text: value,
                topicId,
                submittedQuestionId: qid,
                lastOperationId: p.operationId
            }) : fresh(now, {
                sessionId: p.sessionId,
                topicId,
                text: value,
                submittedQuestionId: qid,
                lastOperationId: p.operationId
            }));
            for (const item of stats) tx.update(item.ref, item.value);
            return {
                id: qid,
                question: {
                    id: qid,
                    ...q
                }
            };
        });
    }
    async function submitReaction(uid, p) {
        const visitId = id(p.visitId),
            topicId = id(p.topicId);
        if (!TYPES.includes(p.type)) fail("INVALID_REACTION", "반응 종류를 확인해 주세요.", "invalid-argument");
        return command(uid, "submitReaction", p, async (tx, now) => {
            const {
                ref,
                s,
                owner
            } = await access(tx, p.sessionId, uid);
            if (owner) fail("STUDENT_REQUIRED", "학생 참여 계정으로 반응해 주세요.", "permission-denied");
            const reactionId = hash([p.sessionId, uid, visitId, p.type]),
                eventRef = ref.collection("reactionEvents").doc(reactionId),
                event = await tx.get(eventRef);
            if (event.exists) return {
                reactionId,
                receipt: {
                    id: reactionId,
                    topicId,
                    visitId,
                    type: p.type,
                    createdAt: event.data().createdAt,
                    revision: 1
                }
            };
            if (s.status !== "LIVE" || s.progress.currentVisitId !== visitId || s.progress.currentTopicId !== topicId) fail("STALE_VISIT", "진도가 바뀌어 지난 구간의 반응은 접수하지 않았어요.");
            const scopes = ["summary", `topic_${topicId}`, `visit_${visitId}`];
            const reads = [];
            for (const scope of scopes) {
                const a = ref.collection("analytics").doc(scope),
                    presence = ref.collection("reactionPresence").doc(hash([scope, uid]));
                reads.push({
                    a,
                    presence
                });
            }
            const snaps = await tx.getAll(...reads.map(x => x.presence));
            tx.create(eventRef, fresh(now, {
                uid,
                sessionId: p.sessionId,
                topicId,
                visitId,
                type: p.type
            }));
            const receipt = fresh(now, {
                topicId,
                visitId,
                type: p.type
            });
            tx.set(personal(uid, p.sessionId, "reactionReceipts", reactionId), receipt);
            reads.forEach((item, i) => {
                const prior = snaps[i].data(),
                    types = prior?.types || [];
                tx.update(item.a, {
                    reactionEventCount: FieldValue.increment(1),
                    uniqueReactingStudents: FieldValue.increment(prior ? 0 : 1),
                    [`eventCountsByType.${p.type}`]: FieldValue.increment(1),
                    [`uniqueStudentsByType.${p.type}`]: FieldValue.increment(types.includes(p.type) ? 0 : 1),
                    revision: FieldValue.increment(1),
                    updatedAt: now
                });
                tx.set(item.presence, prior ? bump(prior, now, {
                    types: [...new Set([...types, p.type])]
                }) : fresh(now, {
                    uid,
                    types: [p.type]
                }));
            });
            return {
                reactionId,
                receipt: {
                    id: reactionId,
                    ...receipt
                }
            };
        });
    }
    async function answer(uid, p, publish) {
        const qid = id(p.id),
            value = text(p.text || "", 20000, publish);
        return command(uid, publish ? "publishAnswer" : "saveAnswerDraft", p, async (tx, now) => {
            const {
                ref
            } = await access(tx, p.sessionId, uid, true), qref = ref.collection("questions").doc(qid), qs = await tx.get(qref), q = qs.data();
            if (!q || q.deletedAt) fail("NOT_FOUND", "접수된 질문을 찾지 못했어요.", "not-found");
            const dref = ref.collection("teacherDrafts").doc(`answer_${qid}`),
                ds = await tx.get(dref),
                d = ds.data();
            if ((d?.revision || 0) !== integer(p.baseRevision) || q.answerVersion !== integer(p.baseAnswerVersion)) fail("VERSION_CONFLICT", "다른 기기에서 답변이 바뀌었어요. 두 원고를 확인해 주세요.");
            const stats = publish ? await statsForQuestions(tx, ref, q.topicId, now, 0, q.answerVersion === 0 ? -1 : 0) : [];
            const draft = d ? bump(d, now, {
                text: publish ? "" : value,
                baseAnswerVersion: publish ? q.answerVersion + 1 : q.answerVersion,
                lastOperationId: p.operationId
            }) : fresh(now, {
                questionId: qid,
                text: publish ? "" : value,
                baseAnswerVersion: publish ? q.answerVersion + 1 : q.answerVersion,
                lastOperationId: p.operationId
            });
            tx.set(dref, draft);
            if (!publish) return {
                id: qid,
                kind: "answerDrafts",
                document: draft
            };
            const version = q.answerVersion + 1,
                updated = bump(q, now, {
                    answer: value,
                    answerVersion: version,
                    status: "ANSWERED",
                    answeredAt: now
                });
            tx.set(qref, updated);
            tx.set(ref.collection("publicQna").doc(q.publicId), fresh(now, {
                sessionId: p.sessionId,
                topicId: q.topicId,
                questionText: q.text,
                answerText: value,
                answerVersion: version,
                publishedAt: now,
                revision: version
            }));
            for (const item of stats) tx.update(item.ref, item.value);
            return {
                id: qid,
                question: {
                    id: qid,
                    ...updated
                },
                draft
            };
        });
    }
    const handlers = {
        createSession,
        updateOutline,
        getSessionBootstrap: bootstrap,
        issueInvite,
        getInvite,
        resolveInvite,
        joinSession,
        changeProgress,
        setQuestionsOpen,
        savePersonalDraft: (u, p) => savePersonal(u, p, "questionDrafts"),
        saveMark: (u, p) => savePersonal(u, p, "marks"),
        deletePersonalItem: deletePersonal,
        submitQuestion,
        submitReaction,
        saveAnswerDraft: (u, p) => answer(u, p, false),
        publishAnswer: (u, p) => answer(u, p, true)
    };
    const learning = createLearningService({
        db, access, command, clock, id, text, integer, hash, fail, providers: learningProviders
    });
    async function dispatch(routes, uid, request, name = "") {
            if (typeof uid !== "string" || !uid || uid.length > 128) fail("AUTH_REQUIRED", "로그인이 필요해요.", "unauthenticated");
            if (!request || typeof request !== "object" || !Object.hasOwn(routes, request.command) || !request.payload || typeof request.payload !== "object" || Array.isArray(request.payload)) fail("INVALID_COMMAND", "요청을 확인해 주세요.", "invalid-argument");
            if (Buffer.byteLength(JSON.stringify(request)) > 150000) fail("REQUEST_TOO_LARGE", "요청 내용이 너무 길어요.", "invalid-argument");
            const p = request.payload;
            if (["uid", "ownerUid", "authorUid", "teacherId", "studentId", "role"].some(k => Object.hasOwn(p, k))) fail("FORGED_IDENTITY", "요청에 권한 정보를 지정할 수 없어요.", "permission-denied");
            return wire(await routes[request.command](uid, p, name));
    }
    return {
        dispatch: (uid, request, name) => dispatch(handlers, uid, request, name),
        dispatchLearning: (uid, request) => dispatch(learning.handlers, uid, request),
        acceptLectureResult: learning.acceptLectureResult
    };
}
module.exports = {
    createService,
    DomainError,
    TYPES,
    hash
};
