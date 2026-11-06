"use strict";
const {
    initializeApp
} = require("firebase-admin/app");
const {
    getFirestore
} = require("firebase-admin/firestore");
const {
    onCall,
    HttpsError
} = require("firebase-functions/v2/https");
const {
    createService,
    DomainError
} = require("./service");
initializeApp();
const service = createService(getFirestore());
exports.classPulse = onCall({
    region: "asia-northeast3",
    timeoutSeconds: 60,
    maxInstances: 10
}, async request => {
    if (!request.auth) throw new HttpsError("unauthenticated", "로그인이 필요해요.", {
        reason: "AUTH_REQUIRED"
    });
    try {
        return await service.dispatch(request.auth.uid, request.data, request.auth.token.name || "");
    } catch (error) {
        if (error instanceof DomainError) throw new HttpsError(error.code, error.message, {
            reason: error.reason
        });
        // Do not log tokens, invite values, questions or answer bodies.
        throw new HttpsError("unavailable", "서버 연결을 확인한 뒤 다시 시도해 주세요.", {
            reason: "SERVER_UNAVAILABLE"
        });
    }
});
