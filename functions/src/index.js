"use strict";
const {
    initializeApp
} = require("firebase-admin/app");
const {
    getFirestore
} = require("firebase-admin/firestore");
const {
    onCall, onRequest,
    HttpsError
} = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const { getStorage } = require("firebase-admin/storage");
const { ProviderError, LECTURE_AUDIO_LIMIT, submitClovaRecording, rewriteQuestion, generateDraft } =
    require("./ai-providers");
const {
    createService,
    DomainError
} = require("./service");
const app = initializeApp();
const region = "asia-northeast3";
const providerSecrets = ["CLOVA_SPEECH_INVOKE_URL", "CLOVA_SPEECH_SECRET_KEY", "OPENAI_API_KEY"]
    .map(defineSecret);

async function startRecording({ path, userdata }) {
    if (!app.options.storageBucket || !app.options.projectId) {
        throw new ProviderError("CLOVA_SPEECH", "CONFIGURATION");
    }
    const bucket = getStorage().bucket();
    const file = bucket.file(path);
    let metadata;
    try {
        [metadata] = await file.getMetadata();
    } catch (error) {
        if (Number(error.code) === 404) throw new ProviderError("CLOVA_SPEECH", "AUDIO_NOT_FOUND");
        throw new ProviderError("CLOVA_SPEECH", "CONFIGURATION");
    }
    const size = Number(metadata.size);
    if (!Number.isSafeInteger(size) || size <= 0 || size > LECTURE_AUDIO_LIMIT ||
            metadata.contentType !== "audio/mp4") throw new ProviderError("CLOVA_SPEECH", "INVALID_AUDIO");
    let audio;
    try {
        [audio] = await bucket.file(path, { generation: metadata.generation }).download();
    } catch {
        throw new ProviderError("CLOVA_SPEECH", "AUDIO_DOWNLOAD_FAILED");
    }
    const callback = `https://${region}-${app.options.projectId}.cloudfunctions.net/classPulseSttResult`;
    return submitClovaRecording(new Blob([audio], { type: "audio/mp4" }), callback, userdata);
}

const service = createService(getFirestore(), { learningProviders: { startRecording, rewriteQuestion, generateDraft } });
const invoke = dispatch => async request => {
    if (!request.auth) throw new HttpsError("unauthenticated", "로그인이 필요해요.", {
        reason: "AUTH_REQUIRED"
    });
    try {
        return await dispatch(request.auth.uid, request.data, request.auth.token.name || "");
    } catch (error) {
        if (error instanceof DomainError) throw new HttpsError(error.code, error.message, {
            reason: error.reason
        });
        // Do not log tokens, invite values, questions or answer bodies.
        throw new HttpsError("unavailable", "서버 연결을 확인한 뒤 다시 시도해 주세요.", {
            reason: "SERVER_UNAVAILABLE"
        });
    }
};

exports.classPulse = onCall({ region, timeoutSeconds: 60, maxInstances: 10 }, invoke(service.dispatch));
exports.classPulseLearning = onCall({
    region,
    timeoutSeconds: 180,
    memory: "512MiB",
    concurrency: 2,
    maxInstances: 5,
    secrets: providerSecrets
}, invoke(service.dispatchLearning));

exports.classPulseSttResult = onRequest({ region, timeoutSeconds: 90, memory: "512MiB", maxInstances: 5 },
    async (request, response) => {
        if (request.method !== "POST") {
            response.sendStatus(405);
            return;
        }
        try {
            await service.acceptLectureResult(request.body);
            response.sendStatus(204);
        } catch {
            response.sendStatus(503);
        }
    });
