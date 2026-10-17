// 가짜 공급자 U3 추가분 시험 (편차 (aa) → (U11-a) · U3-J12 · U3-J17 · contracts/ai-io.md 3절)
// 응답 취소가 지원 목록 밖 type으로 거절되는지, 지시 추가는 원문 없이 해시만 기록하는지, --confidence가 아이 전사 확정에 실리는지 본다.
import { randomBytes } from "node:crypto";
import { afterEach, describe, expect, it } from "vitest";
import WebSocket from "ws";
import { synthesizeInputPayload } from "../../lib/frameCodec.mjs";
import { parseArgs, startFakeProvider } from "../../fake-provider/server.mjs";
import { buildTurn, textSha256 } from "../../fake-provider/scenarios.mjs";

const providers = [];
const sockets = [];

afterEach(async () => {
  for (const socket of sockets.splice(0)) {
    socket.terminate();
  }
  for (const provider of providers.splice(0)) {
    await provider.close();
  }
});

async function start(options = {}) {
  const provider = await startFakeProvider({
    port: 0,
    framesPerTurn: 3,
    responseDelayMs: 5,
    chunkIntervalMs: 1,
    onRecord: () => {},
    ...options,
  });
  providers.push(provider);
  return provider;
}

function connect(provider) {
  const ws = new WebSocket(provider.url, { headers: { authorization: `Bearer ${randomBytes(16).toString("base64url")}` } });
  sockets.push(ws);
  const messages = [];
  ws.on("message", (data) => messages.push(JSON.parse(data.toString("utf8"))));
  ws.on("error", () => {});
  const opened = new Promise((resolve, reject) => {
    ws.once("open", resolve);
    ws.once("error", reject);
  });
  return { ws, messages, opened };
}

async function waitUntil(condition, timeoutMs = 3000) {
  const deadline = Date.now() + timeoutMs;
  while (!condition()) {
    if (Date.now() > deadline) {
      throw new Error("기다린 조건이 오지 않았다");
    }
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

async function startedSession(provider) {
  const client = connect(provider);
  await client.opened;
  client.ws.send(JSON.stringify({ type: "session.start", session: { model: "m", instructions: "i", audio: { format: { type: "audio/pcm", rate: 24000 } } } }));
  await waitUntil(() => client.messages.some((message) => message.type === "session.started"));
  return client;
}

function sendFrames(ws, count) {
  for (let index = 0; index < count; index += 1) {
    ws.send(JSON.stringify({ type: "session.input_audio.append", audio: synthesizeInputPayload(index).toString("base64") }));
  }
}

describe("가짜 공급자 — 응답 취소는 지원 목록 밖(U11 편차 (U11-a), 옛 U3-R5)", () => {
  it("응답 취소 type에는 error{invalid_value}로 답하고 출력을 멈추지 않는다(끼어들기는 서버 출력 세대가 버린다)", async () => {
    const provider = await start({ turns: ["normal"], chunkBytes: 480, chunkIntervalMs: 20 });
    const client = await startedSession(provider);
    sendFrames(client.ws, 3);
    await waitUntil(() => client.messages.some((message) => message.type === "session.output_audio.delta"));

    client.ws.send(JSON.stringify({ type: "session.response.cancel" }));
    await waitUntil(() => client.messages.some((message) => message.type === "error"));
    const turn = provider.records.find((entry) => entry.ev === "turn");
    await waitUntil(
      () =>
        client.messages
          .filter((message) => message.type === "session.output_audio.delta")
          .reduce((sum, message) => sum + Buffer.from(message.delta, "base64").length, 0) === turn.outputBytes,
    );

    expect(client.messages.find((message) => message.type === "error").error).toMatchObject({
      type: "invalid_request_error",
      code: "invalid_value",
      param: "type",
    });
    expect(provider.records.find((entry) => entry.ev === "recv-unsupported")).toMatchObject({ type: "session.response.cancel" });
    expect(provider.records.some((entry) => entry.ev === "turn-cancelled")).toBe(false);
  });
});

describe("가짜 공급자 — 지시 교정 · 신뢰도(U3-J12 · U3-J17)", () => {
  it("교정 문장은 해시 · 길이만 남기고 원문은 기록하지 않는다", async () => {
    const records = [];
    const provider = await start({ onRecord: (entry) => records.push(JSON.stringify(entry)) });
    const client = await startedSession(provider);
    const text = "방금 응답은 아이에게 전하지 않았다. 한두 문장의 완결된 말로 짧게 다시 답한다.";
    client.ws.send(JSON.stringify({ type: "session.instructions.append", text }));
    await waitUntil(() => provider.records.some((entry) => entry.ev === "session.instructions.append"));
    expect(provider.records.find((entry) => entry.ev === "session.instructions.append")).toMatchObject({
      textSha256: textSha256(text),
      textLength: text.length,
    });
    expect(records.join("\n")).not.toContain("완결된 말");
  });

  it("--confidence를 주면 아이 전사 확정에 싣고, 주지 않으면 싣지 않는다", async () => {
    const provider = await start({ turns: ["normal"], confidence: 0.4 });
    const client = await startedSession(provider);
    sendFrames(client.ws, 3);
    await waitUntil(() => client.messages.some((message) => message.type === "session.input_transcript.done"));
    expect(client.messages.find((message) => message.type === "session.input_transcript.done").confidence).toBe(0.4);

    const plain = buildTurn({ connection: 1, turn: 1, kind: "normal" });
    const done = plain.steps.find((step) => step.message.type === "session.input_transcript.done").message;
    expect("confidence" in done).toBe(false);
  });

  it("신뢰도 인자는 0 ~ 1만 받는다", async () => {
    expect(parseArgs(["--confidence", "0.4"]).confidence).toBe(0.4);
    expect(parseArgs(["--confidence", "1"]).confidence).toBe(1);
    expect(parseArgs(["--confidence", "0"]).confidence).toBe(0);
    for (const wrong of ["1.5", "-0.1", "abc", ".5"]) {
      expect(() => parseArgs(["--confidence", wrong]), wrong).toThrow(RangeError);
    }
    await expect(startFakeProvider({ port: 0, confidence: 2, onRecord: () => {} })).rejects.toThrow(RangeError);
  });
});
