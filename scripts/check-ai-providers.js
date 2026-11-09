"use strict";

const { openAsBlob } = require("node:fs");
const { basename } = require("node:path");
const { ProviderError, transcribeClovaSample, rewriteQuestion } = require("../functions/src/ai-providers");

async function main() {
    const [provider, file, ...extra] = process.argv.slice(2);
    if (provider === "gpt" && !file) {
        const draft = await rewriteQuestion("조건부 확률에서 왜 분모에 조건의 확률이 들어가는지 잘 모르겠어요.");
        console.log(`GPT 질문 정리 성공 (${draft.text.length}자)`);
    } else if (provider === "stt" && file && extra.length === 0) {
        const transcript = await transcribeClovaSample(await openAsBlob(file), basename(file));
        if (!transcript.text.trim()) throw new ProviderError("CLOVA_SPEECH", "NO_SPEECH");
        console.log(`CLOVA Speech 변환 성공 (${transcript.text.length}자, ${transcript.segments.length}개 구간)`);
    } else {
        console.error("사용법: check-ai-providers.js gpt | stt <짧은 음성 파일>");
        process.exitCode = 1;
    }
}

main().catch(error => {
    console.error(error instanceof ProviderError ? error.message : "테스트 음성 파일을 열 수 없어요.");
    process.exitCode = 1;
});
