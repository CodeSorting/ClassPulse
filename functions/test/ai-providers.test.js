"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const { parseClovaTranscript, startClovaTranscription, transcribeClovaSample, rewriteQuestion } =
    require("../src/ai-providers");

const env = {
    CLOVA_SPEECH_INVOKE_URL: "https://clovaspeech-gw.ncloud.com/external/v1/123/example/",
    CLOVA_SPEECH_SECRET_KEY: "test-clova-key",
    OPENAI_API_KEY: "test-openai-key"
};
const job = {
    audioUrl: "https://storage.example.test/lecture.m4a?signature=test%2Fvalue",
    callbackUrl: "https://functions.example.test/clova",
    jobId: "job-1"
};
const transcript = {
    result: "COMPLETED",
    text: "확률을 설명합니다.",
    segments: [{ start: 100, end: 850, text: "확률을 설명합니다." }]
};

test("CLOVA submits a Korean async URL request and returns its tracking token", async () => {
    const started = await startClovaTranscription(job, {
        env,
        fetchImpl: async (url, options) => {
            assert.equal(url, "https://clovaspeech-gw.ncloud.com/external/v1/123/example/recognizer/url");
            assert.equal(options.headers["X-CLOVASPEECH-API-KEY"], env.CLOVA_SPEECH_SECRET_KEY);
            assert.equal(options.redirect, "error");
            const payload = JSON.parse(options.body);
            assert.equal(payload.url, job.audioUrl);
            assert.equal(payload.callback, job.callbackUrl);
            assert.equal(payload.userdata.jobId, job.jobId);
            assert.equal(payload.language, "ko-KR");
            assert.equal(payload.completion, "async");
            return Response.json({ result: "SUCCEEDED", token: "provider-token" });
        }
    });
    assert.deepEqual(started, { token: "provider-token" });
});

test("CLOVA local sample sends multipart form data and keeps millisecond segment times", async () => {
    const converted = await transcribeClovaSample(new Blob(["fake-audio"]), "sample.m4a", {
        env,
        fetchImpl: async (url, options) => {
            assert.match(url, /\/recognizer\/upload$/);
            const request = new Request(url, options);
            assert.match(request.headers.get("content-type"), /^multipart\/form-data; boundary=/);
            const form = await request.formData();
            assert.equal(form.get("media").name, "sample.m4a");
            assert.equal(await form.get("media").text(), "fake-audio");
            assert.equal(JSON.parse(form.get("params")).completion, "sync");
            return Response.json(transcript);
        }
    });
    assert.deepEqual(converted, {
        text: transcript.text,
        segments: [{ startMs: 100, endMs: 850, text: transcript.text }]
    });
});

test("CLOVA rejects invalid results and credentials sent to an unexpected host", async () => {
    assert.throws(() => parseClovaTranscript({ result: "FAILED" }), { code: "REJECTED" });
    assert.throws(() => parseClovaTranscript({ result: "SUCCEEDED", token: "provider-token" }, {
        fromCallback: true
    }), { code: "REJECTED" });
    assert.throws(() => parseClovaTranscript({ ...transcript, result: "SUCCEEDED", progress: 100 }), {
        code: "REJECTED"
    });
    assert.throws(() => parseClovaTranscript({
        ...transcript, segments: [{ start: 850, end: 100, text: "broken" }]
    }), { code: "INVALID_RESPONSE" });
    const fetchImpl = async () => assert.fail("Invalid configuration must fail before fetch");
    await assert.rejects(startClovaTranscription(job, {
        env: { ...env, CLOVA_SPEECH_INVOKE_URL: "https://other.example.test/external/v1/123/example" },
        fetchImpl
    }), { code: "CONFIGURATION" });
    await assert.rejects(startClovaTranscription({ ...job, audioUrl: "http://example.test/audio" }, {
        env, fetchImpl
    }), { code: "INVALID_INPUT" });
    await assert.rejects(startClovaTranscription(job, {
        env, fetchImpl: async () => Response.json({ result: "SUCCEEDED" })
    }), { code: "INVALID_RESPONSE" });
});

test("GPT sends the question to Responses and reads text after a reasoning item", async () => {
    const result = await rewriteQuestion(" 원래 질문 ", {
        env,
        fetchImpl: async (url, options) => {
            assert.equal(url, "https://api.openai.com/v1/responses");
            assert.equal(options.headers.Authorization, "Bearer test-openai-key");
            const payload = JSON.parse(options.body);
            assert.equal(payload.model, "gpt-5-mini");
            assert.equal(payload.input, "원래 질문");
            assert.equal(payload.store, false);
            assert.ok(payload.max_output_tokens > 0);
            return Response.json({
                status: "completed",
                output: [
                    { type: "reasoning" },
                    { type: "message", role: "assistant", content: [{ type: "output_text", text: "정리한 질문" }] }
                ]
            });
        }
    });
    assert.deepEqual(result, { text: "정리한 질문" });
});

test("GPT rejects unusable drafts, including text that cannot be saved as a question", async () => {
    await assert.rejects(rewriteQuestion(" ", { env }), { code: "INVALID_INPUT" });
    await assert.rejects(rewriteQuestion("가".repeat(1001), { env }), { code: "INVALID_INPUT" });
    await assert.rejects(rewriteQuestion("질문", {
        env, fetchImpl: async () => Response.json({ status: "incomplete", output: [] })
    }), { code: "REJECTED" });
    await assert.rejects(rewriteQuestion("질문", {
        env, fetchImpl: async () => Response.json({
            status: "completed",
            output: [{ type: "message", role: "assistant", content: [{ type: "refusal", refusal: "provider text" }] }]
        })
    }), { message: "OPENAI: REJECTED" });
    await assert.rejects(rewriteQuestion("질문", {
        env, fetchImpl: async () => Response.json({
            status: "completed",
            output: [{ type: "message", role: "assistant", content: [{ type: "output_text", text: "가".repeat(1001) }] }]
        })
    }), { code: "INVALID_RESPONSE" });
});

test("provider failures expose safe error codes and never retry a paid request automatically", async () => {
    for (const [status, code] of [[403, "AUTHENTICATION"], [429, "RATE_LIMITED"], [503, "UNAVAILABLE"]]) {
        let requests = 0;
        await assert.rejects(rewriteQuestion("질문", {
            env, fetchImpl: async () => {
                requests += 1;
                return new Response("private provider details", { status });
            }
        }), { message: `OPENAI: ${code}`, status });
        assert.equal(requests, 1);
    }
    await assert.rejects(rewriteQuestion("질문", {
        env, fetchImpl: async () => { throw new DOMException("private timeout details", "TimeoutError"); }
    }), { message: "OPENAI: TIMEOUT" });
    await assert.rejects(rewriteQuestion("질문", {
        env, fetchImpl: async () => new Response("invalid JSON")
    }), { message: "OPENAI: INVALID_RESPONSE" });
    await assert.rejects(rewriteQuestion("질문", {
        env: {}, fetchImpl: async () => assert.fail("Missing key must fail before fetch")
    }), { message: "OPENAI: CONFIGURATION" });
});
