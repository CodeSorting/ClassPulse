"use strict";

const { randomUUID, randomBytes, timingSafeEqual } = require("node:crypto");
const { ProviderError, parseClovaTranscript } = require("./ai-providers");

const PART_LENGTH = 8000;
const MAX_PARTS = 150;
const PROCESSING_TTL = 2 * 60 * 60 * 1000;
const ACTIVE_STATES = ["SUBMITTING", "PROCESSING"];

function transcriptParts(result, visits, startedAt) {
    const segments = result.segments.length
        ? result.segments
        : [{ startMs: 0, endMs: 0, text: result.text }];
    const parts = [];
    let current;
    for (const segment of segments) {
        if (!segment.text.trim()) continue;
        const time = startedAt + (segment.startMs + segment.endMs) / 2;
        const visit = visits.find(v => time >= v.startedAt.toMillis() &&
            (!v.endedAt || time < v.endedAt.toMillis()));
        const topicId = visit?.topicId || "";
        let remaining = segment.text.trim();
        while (remaining) {
            if (!current || current.topicId !== topicId ||
                    current.text.length + remaining.length + 1 > PART_LENGTH) {
                current = {
                    id: String(parts.length).padStart(4, "0"),
                    topicId,
                    startMs: segment.startMs,
                    endMs: segment.endMs,
                    text: ""
                };
                parts.push(current);
                if (parts.length > MAX_PARTS) throw new ProviderError("CLOVA_SPEECH", "TOO_LONG");
            }
            let end = Math.min(remaining.length, PART_LENGTH);
            if (end < remaining.length && /[\uD800-\uDBFF]/.test(remaining[end - 1])) end -= 1;
            current.text += (current.text ? "\n" : "") + remaining.slice(0, end);
            current.endMs = segment.endMs;
            remaining = remaining.slice(end);
        }
    }
    if (!parts.length) throw new ProviderError("CLOVA_SPEECH", "NO_SPEECH");
    return parts;
}

function createLearningService({ db, access, command, clock, id, text, integer, hash, fail, providers }) {
    const lectureDraft = ref => ref.collection("materialDrafts").doc("lecture");
    const lectureHead = ref => ref.collection("materialHead").doc("lecture");
    const jobRef = ref => ref.collection("processingJobs").doc("lecture");
    const fields = snapshot => ({ ...snapshot.data(), id: snapshot.id });
    const all = async (tx, query) => (await tx.get(query)).docs.map(fields);
    const checked = (uid, payload, ownerOnly, action) => db.runTransaction(async tx =>
        action(tx, await access(tx, payload.sessionId, uid, ownerOnly)));

    function equalRevision(expected, doc) {
        if (integer(expected) !== (doc?.revision || 0)) {
            fail("VERSION_CONFLICT", "다른 기기에서 내용이 바뀌었어요. 저장한 내용을 새로 불러와 주세요.");
        }
    }

    async function topicFor(tx, ref, topicId) {
        const topic = (await tx.get(ref.collection("topics").doc(id(topicId)))).data();
        if (!topic || topic.deletedAt) fail("INVALID_TOPIC", "이 수업의 대주제를 선택해 주세요.");
        return topic;
    }

    function providerFailure(error) {
        if (!(error instanceof ProviderError)) throw error;
        const messages = {
            CONFIGURATION: "서버의 STT 또는 GPT 연동 설정을 확인해 주세요.",
            AUTHENTICATION: "서버의 API 키와 사용 권한을 확인해 주세요.",
            RATE_LIMITED: "요청 한도에 도달했어요. 잠시 후 다시 시도해 주세요.",
            TIMEOUT: "응답 시간이 초과됐어요. 잠시 후 상태를 확인해 주세요.",
            AUDIO_NOT_FOUND: "업로드한 녹음을 찾지 못했어요. 파일을 다시 확인해 주세요.",
            AUDIO_DOWNLOAD_FAILED: "서버에서 녹음을 읽지 못했어요. 잠시 후 다시 시도해 주세요.",
            INVALID_AUDIO: "녹음 파일의 형식과 크기를 확인해 주세요.",
            NO_SPEECH: "인식된 음성이 없어요. 녹음을 확인해 주세요.",
            TOO_LONG: "원문이 너무 길어요. 녹음을 나누어 다시 시도해 주세요."
        };
        const reason = error.code;
        fail(reason, messages[reason] || "외부 서비스가 요청을 완료하지 못했어요. 잠시 후 다시 시도해 주세요.",
            reason === "RATE_LIMITED" ? "resource-exhausted" : "failed-precondition");
    }

    async function getLectureMaterial(uid, p) {
        return checked(uid, p, false, async (tx, { ref, owner }) => {
            const published = (await tx.get(lectureHead(ref))).data();
            const materialRef = owner
                ? lectureDraft(ref)
                : published ? ref.collection("materialReleases").doc(published.releaseId) : null;
            const material = materialRef ? (await tx.get(materialRef)).data() : null;
            const partQuery = material && materialRef
                ? (owner ? materialRef.collection("revisions").doc(material.sourceId) : materialRef)
                    .collection("parts").orderBy("id")
                : null;
            const parts = partQuery ? await all(tx, partQuery) : [];
            const notes = await all(tx, ref.collection(owner ? "learningNoteDrafts" : "learningNotes"));
            const job = owner ? (await tx.get(jobRef(ref))).data() || null : null;
            return { material: material || null, parts, notes, job, published: published || null };
        });
    }

    async function startLectureStt(uid, p) {
        const recordingId = id(p.recordingId);
        const startedAt = integer(p.startedAt);
        if (startedAt > clock() + 5 * 60 * 1000) {
            fail("INVALID_RECORDING_TIME", "녹음 시작 시간을 확인해 주세요.", "invalid-argument");
        }
        if (!providers.startRecording) fail("CONFIGURATION", "녹음 변환 서버 설정을 확인해 주세요.");
        const attempt = randomUUID();
        const proof = randomBytes(32).toString("base64url");
        const callback = db.collection("clovaCallbacks").doc(attempt);
        const reservation = await checked(uid, p, true, async (tx, { ref, s }) => {
            if (s.status !== "ENDED") fail("LESSON_NOT_ENDED", "수업을 종료한 뒤 녹음을 변환해 주세요.");
            const job = jobRef(ref);
            const existing = (await tx.get(job)).data();
            if (existing?.recordingId === recordingId && existing.state === "READY") return { previous: existing };
            if (existing && ACTIVE_STATES.includes(existing.state) && existing.expiresAt > clock()) {
                if (existing.recordingId === recordingId) return { previous: existing };
                fail("STT_BUSY", "이 수업의 다른 녹음을 변환하고 있어요.");
            }
            if (existing?.attempt) tx.delete(db.collection("clovaCallbacks").doc(existing.attempt));
            const state = { attempt, recordingId, startedAt, state: "SUBMITTING", expiresAt: clock() + PROCESSING_TTL };
            tx.set(job, state);
            tx.create(callback, { sessionId: p.sessionId, proofHash: hash(proof) });
            return { job, state };
        });
        if (reservation.previous) return { job: reservation.previous };
        const { job, state } = reservation;
        try {
            await providers.startRecording({
                path: `lecture-recordings/${p.sessionId}/${uid}/${recordingId}.m4a`,
                userdata: { attempt, proof }
            });
            return { job: await updateJob(job, attempt, "PROCESSING") };
        } catch (error) {
            if (!(error instanceof ProviderError)) {
                await updateJob(job, attempt, "PROCESSING");
                throw error;
            }
            const uncertain = error.code === "TIMEOUT" || error.code === "UNAVAILABLE";
            const next = await updateJob(job, attempt, uncertain ? "PROCESSING" : "FAILED", error.code);
            if (next?.state === "READY") return { job: next };
            if (uncertain) return { job: next || state };
            providerFailure(error);
        }
    }

    function updateJob(ref, attempt, state, error = "") {
        return db.runTransaction(async tx => {
            const current = (await tx.get(ref)).data();
            if (current?.attempt !== attempt || current.state !== "SUBMITTING") return current;
            const next = { ...current, state, error };
            tx.set(ref, next);
            if (state === "FAILED") tx.delete(db.collection("clovaCallbacks").doc(attempt));
            return next;
        });
    }

    async function acceptLectureResult(body) {
        const { attempt, proof } = body?.params?.userdata || {};
        if (typeof attempt !== "string" || !/^[0-9a-f-]{36}$/.test(attempt) ||
                typeof proof !== "string" || !/^[a-zA-Z0-9_-]{43}$/.test(proof)) return false;
        const callback = db.collection("clovaCallbacks").doc(attempt);
        return db.runTransaction(async tx => {
            const claim = (await tx.get(callback)).data();
            if (!claim || !timingSafeEqual(Buffer.from(claim.proofHash), Buffer.from(hash(proof)))) return false;
            const ref = db.collection("sessions").doc(claim.sessionId);
            const session = (await tx.get(ref)).data();
            const job = jobRef(ref);
            const current = (await tx.get(job)).data();
            if (!session || session.deletedAt || current?.attempt !== attempt ||
                    !ACTIVE_STATES.includes(current.state)) return false;
            const draft = lectureDraft(ref);
            const prior = (await tx.get(draft)).data();
            // Imported recordings have no start time on the lesson timeline.
            const visits = current.startedAt === 0 ? [] : await all(tx, ref.collection("visits").orderBy("startedAt"));
            let parts;
            try {
                parts = transcriptParts(parseClovaTranscript(body, { fromCallback: true }), visits, current.startedAt);
            } catch (error) {
                if (!(error instanceof ProviderError)) throw error;
                tx.update(job, { state: "FAILED", error: error.code });
                tx.delete(callback);
                return true;
            }
            const revision = draft.collection("revisions").doc(attempt);
            for (const part of parts) tx.set(revision.collection("parts").doc(part.id), part);
            tx.set(draft, {
                sourceId: attempt,
                recordingId: current.recordingId,
                revision: (prior?.revision || 0) + 1,
                partCount: parts.length
            });
            tx.update(job, { state: "READY", error: "" });
            tx.delete(callback);
            return true;
        });
    }

    function saveLecturePart(uid, p) {
        if (typeof p.partId !== "string" || !/^\d{4}$/.test(p.partId)) fail("INVALID_PART", "원문 구간을 선택해 주세요.");
        const value = text(p.text, PART_LENGTH);
        return command(uid, "saveLecturePart", p, async tx => {
            const { ref } = await access(tx, p.sessionId, uid, true);
            const draft = lectureDraft(ref);
            const material = (await tx.get(draft)).data();
            if (!material) fail("NO_TRANSCRIPT", "변환한 원문이 필요해요.");
            equalRevision(p.baseRevision, material);
            const part = draft.collection("revisions").doc(material.sourceId).collection("parts").doc(p.partId);
            if (!(await tx.get(part)).exists) fail("INVALID_PART", "원문 구간을 선택해 주세요.");
            const topicId = p.topicId || "";
            if (topicId) await topicFor(tx, ref, topicId);
            tx.update(part, { text: value, topicId });
            tx.update(draft, { revision: material.revision + 1 });
            return { revision: material.revision + 1 };
        });
    }

    function publishLecture(uid, p) {
        return command(uid, "publishLecture", p, async (tx, now) => {
            const { ref } = await access(tx, p.sessionId, uid, true);
            const draft = lectureDraft(ref);
            const material = (await tx.get(draft)).data();
            if (!material) fail("NO_TRANSCRIPT", "변환한 원문이 필요해요.");
            equalRevision(p.baseRevision, material);
            const head = lectureHead(ref);
            const existing = (await tx.get(head)).data();
            if (existing?.sourceRevision === material.revision) return existing;
            const parts = await all(tx, draft.collection("revisions").doc(material.sourceId)
                .collection("parts").orderBy("id"));
            if (parts.length !== material.partCount) fail("NO_TRANSCRIPT", "원문 구간을 다시 확인해 주세요.");
            const version = (existing?.version || 0) + 1;
            const release = ref.collection("materialReleases").doc(`lecture_${version}`);
            for (const part of parts) tx.create(release.collection("parts").doc(part.id), part);
            tx.create(release, { state: "READY", version, partCount: parts.length, publishedAt: now });
            const value = { releaseId: release.id, version, sourceRevision: material.revision };
            tx.set(head, value);
            return value;
        });
    }

    function saveLearningNote(uid, p) {
        const summary = text(p.summary, 6000, false);
        const supplement = text(p.supplement, 6000, false);
        return command(uid, "saveLearningNote", p, async (tx, now) => {
            const { ref } = await access(tx, p.sessionId, uid, true);
            await topicFor(tx, ref, p.topicId);
            const draft = ref.collection("learningNoteDrafts").doc(p.topicId);
            const prior = (await tx.get(draft)).data();
            equalRevision(p.baseRevision, prior);
            const value = { topicId: p.topicId, summary, supplement, revision: (prior?.revision || 0) + 1 };
            tx.set(draft, value);
            if (p.publish === true) tx.set(ref.collection("learningNotes").doc(p.topicId), { ...value, publishedAt: now });
            return value;
        });
    }

    async function consumeAiBudget(uid) {
        const ref = db.collection("learningAiUsage").doc(uid);
        await db.runTransaction(async tx => {
            const prior = (await tx.get(ref)).data() || {};
            const minute = Math.floor(clock() / 60000);
            const day = Math.floor(minute / 1440);
            const perMinute = prior.minute === minute ? prior.perMinute : 0;
            const perDay = prior.day === day ? prior.perDay : 0;
            if (perMinute >= 4 || perDay >= 40) {
                fail("RATE_LIMITED", "AI 요청 한도에 도달했어요. 잠시 후 다시 시도해 주세요.", "resource-exhausted");
            }
            tx.set(ref, { minute, day, perMinute: perMinute + 1, perDay: perDay + 1 });
        });
    }

    async function generateLearningDraft(uid, p) {
        const mode = p.mode;
        if (!["rewrite", "answer", "summary", "supplement", "quiz"].includes(mode)) {
            fail("INVALID_AI_MODE", "AI 기능을 다시 선택해 주세요.", "invalid-argument");
        }
        if (!providers.rewriteQuestion || !providers.generateDraft) fail("CONFIGURATION", "GPT 서버 설정을 확인해 주세요.");
        const input = await checked(uid, p, mode !== "rewrite", async (tx, { ref, owner }) => {
            if (mode === "rewrite") {
                if (owner) fail("STUDENT_REQUIRED", "학생 참여 계정으로 질문해 주세요.", "permission-denied");
                return text(p.text, 1000);
            }
            const question = mode === "answer"
                ? (await tx.get(ref.collection("questions").doc(id(p.questionId)))).data() : null;
            if (mode === "answer" && (!question || question.deletedAt)) fail("NOT_FOUND", "접수된 질문을 찾지 못했어요.", "not-found");
            const topicId = question?.topicId || p.topicId;
            const topic = await topicFor(tx, ref, topicId);
            const draft = lectureDraft(ref);
            const material = (await tx.get(draft)).data();
            const parts = material ? await all(tx, draft.collection("revisions").doc(material.sourceId)
                .collection("parts").orderBy("id")) : [];
            const transcript = parts.filter(part => part.topicId === topicId).map(part => part.text).join("\n");
            if (transcript.length > 100000) fail("AI_INPUT_TOO_LONG", "이 대주제의 원문이 길어요. 구간을 나누어 주세요.");
            const notes = (await tx.get(ref.collection("learningNoteDrafts").doc(topicId))).data();
            const questions = mode === "supplement" || mode === "quiz"
                ? (await all(tx, ref.collection("questions").where("topicId", "==", topicId).limit(20)))
                    .filter(item => !item.deletedAt).map(item => item.text)
                : [];
            if ((mode === "summary" && !transcript) ||
                    (mode === "quiz" && !transcript && !notes?.summary && !notes?.supplement && !questions.length)) {
                fail("NO_AI_SOURCE", "먼저 이 대주제에 원문이나 복습 자료를 준비해 주세요.");
            }
            return {
                topic: topic.title,
                transcript,
                summary: notes?.summary || "",
                supplement: notes?.supplement || "",
                question: question?.text || "",
                questions
            };
        });
        await consumeAiBudget(uid);
        try {
            if (mode === "rewrite") return await providers.rewriteQuestion(input);
            const result = await providers.generateDraft(mode, input);
            return mode === "quiz"
                ? { items: quizItems(result.items) }
                : { text: text(result.text, 6000) };
        } catch (error) {
            providerFailure(error);
        }
    }

    function quizItems(items) {
        if (!Array.isArray(items) || !items.length || items.length > 10) {
            fail("INVALID_QUIZ", "퀴즈 문제를 1~10개 작성해 주세요.", "invalid-argument");
        }
        return items.map(item => {
            if (!Array.isArray(item?.choices) || item.choices.length !== 4 ||
                    !Number.isInteger(item.correctIndex) || item.correctIndex < 0 || item.correctIndex >= 4) {
                fail("INVALID_QUIZ", "문제마다 보기 4개와 정답 하나를 선택해 주세요.", "invalid-argument");
            }
            const choices = item.choices.map(choice => text(choice, 500));
            if (new Set(choices).size !== choices.length) fail("INVALID_QUIZ", "서로 다른 보기를 입력해 주세요.", "invalid-argument");
            return { prompt: text(item.prompt, 1000), choices,
                correctIndex: item.correctIndex, explanation: text(item.explanation, 2000) };
        });
    }

    const attempts = (uid, sid) => db.collection(`users/${uid}/lessonData/${sid}/attempts`);

    function getLearningQuizzes(uid, p) {
        return checked(uid, p, false, async (tx, { ref, owner }) => ({
            quizzes: await all(tx, ref.collection("quizzes")),
            drafts: owner ? await all(tx, ref.collection("quizDrafts")) : [],
            attempts: owner ? [] : await all(tx, attempts(uid, p.sessionId).orderBy("submittedAt", "desc").limit(20))
        }));
    }

    function saveLearningQuiz(uid, p) {
        const items = quizItems(p.items);
        const title = text(p.title, 100);
        return command(uid, "saveLearningQuiz", p, async (tx, now) => {
            const { ref, s } = await access(tx, p.sessionId, uid, true);
            await topicFor(tx, ref, p.topicId);
            const draft = ref.collection("quizDrafts").doc(p.topicId);
            const current = (await tx.get(draft)).data();
            equalRevision(p.baseRevision, current);
            const quiz = ref.collection("quizzes").doc(p.topicId);
            const published = p.publish === true;
            if (published && s.status !== "ENDED") fail("LESSON_NOT_ENDED", "수업 종료 후 퀴즈를 공개해 주세요.");
            const meta = published ? (await tx.get(quiz)).data() : null;
            const value = { title, topicId: p.topicId, items, revision: (current?.revision || 0) + 1 };
            tx.set(draft, value);
            if (published) {
                const version = (meta?.version || 0) + 1;
                tx.set(quiz, { title, topicId: p.topicId, version, publishedAt: now });
                tx.create(quiz.collection("versions").doc(String(version)), {
                    title, version, topicId: p.topicId,
                    items: items.map(({ prompt, choices }) => ({ prompt, choices }))
                });
                tx.create(ref.collection("quizKeys").doc(`${p.topicId}_${version}`), {
                    items: items.map(({ correctIndex, explanation }) => ({ correctIndex, explanation }))
                });
            }
            return value;
        });
    }

    async function quizVersion(tx, ref, quizId, version) {
        const quiz = (await tx.get(ref.collection("quizzes").doc(id(quizId))
            .collection("versions").doc(String(integer(version))))).data();
        if (!quiz) fail("QUIZ_NOT_FOUND", "공개된 퀴즈를 찾지 못했어요.", "not-found");
        return quiz;
    }

    function getLearningQuiz(uid, p) {
        return checked(uid, p, false, async (tx, { ref }) => quizVersion(tx, ref, p.quizId, p.version));
    }

    function submitLearningQuiz(uid, p) {
        const attemptId = id(p.attemptId);
        return command(uid, "submitLearningQuiz", p, async (tx, now) => {
            const { ref, owner } = await access(tx, p.sessionId, uid);
            if (owner) fail("STUDENT_REQUIRED", "참여 학생 계정으로 풀이해 주세요.", "permission-denied");
            const attempt = attempts(uid, p.sessionId).doc(attemptId);
            const previous = (await tx.get(attempt)).data();
            if (previous) {
                if (previous.quizId !== p.quizId || previous.version !== p.version ||
                        previous.answersHash !== hash(p.answers ?? null)) fail("OPERATION_REUSED", "다시 풀기를 눌러 새로 제출해 주세요.");
                return previous;
            }
            const quiz = await quizVersion(tx, ref, p.quizId, p.version);
            if (!Array.isArray(p.answers) || p.answers.length !== quiz.items.length ||
                    p.answers.some(choice => !Number.isInteger(choice) || choice < 0 || choice >= 4)) {
                fail("INCOMPLETE_QUIZ", "모든 문제의 보기를 골라 주세요.", "invalid-argument");
            }
            const key = (await tx.get(ref.collection("quizKeys").doc(`${p.quizId}_${p.version}`))).data();
            if (!key || key.items.length !== quiz.items.length) fail("QUIZ_NOT_FOUND", "퀴즈 채점 정보를 찾지 못했어요.");
            const items = quiz.items.map((item, i) => ({ ...item, ...key.items[i], selected: p.answers[i] }));
            const result = { id: attemptId, quizId: p.quizId, version: p.version, title: quiz.title,
                items, score: items.filter(item => item.selected === item.correctIndex).length,
                submittedAt: now, answersHash: hash(p.answers) };
            tx.create(attempt, result);
            return result;
        });
    }

    return {
        acceptLectureResult,
        handlers: { getLectureMaterial, startLectureStt, saveLecturePart, publishLecture, saveLearningNote,
            generateLearningDraft, getLearningQuizzes, saveLearningQuiz, getLearningQuiz, submitLearningQuiz }
    };
}

module.exports = { createLearningService };
