// 가짜 공급자 실호출 관측 선 형식 시험 (U11 FR12.3 · BR11.10 · 편차 (U11-a))
// 응답 id · 응답 끝 사건이 없고, 음성은 delta(base64)이며, 지원 목록 밖 type에는 error{invalid_value}로 답하고, 응답 경계는 조각 사이 무음임을 본다.
// 실공급자 · 실제 키는 쓰지 않는다. 시험이 만든 무작위 값만 쓴다.
import { randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it } from "vitest";
import WebSocket from "ws";
import { sha256Hex, synthesizeInputPayload } from "../../lib/frameCodec.mjs";
import { startFakeProvider } from "../../fake-provider/server.mjs";
import {
  ACK_LEAD_MS,
  FILLER_MS,
  SUPPORTED_TYPES,
  buildTurn,
  missingDelegationIdError,
  outputPcmFor,
  responseCreateRefused,
  unsupportedTypeError,
} from "../../fake-provider/scenarios.mjs";

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
    chunkBytes: 960,
    chunkIntervalMs: 2,
    onRecord: () => {},
    ...options,
  });
  providers.push(provider);
  return provider;
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

async function session(provider, { bearer = true } = {}) {
  const headers = bearer ? { authorization: `Bearer ${randomBytes(16).toString("base64url")}` } : {};
  const ws = new WebSocket(provider.url, { headers });
  sockets.push(ws);
  const received = [];
  ws.on("message", (data) => received.push({ at: Date.now(), message: JSON.parse(data.toString("utf8")) }));
  ws.on("error", () => {});
  await new Promise((resolve, reject) => {
    ws.once("open", resolve);
    ws.once("error", reject);
  });
  ws.send(
    JSON.stringify({
      type: "session.start",
      session: { model: "m", instructions: "i", audio: { format: { type: "audio/pcm", rate: 24000 } } },
    }),
  );
  await waitUntil(() => received.some((entry) => entry.message.type === "session.started"));
  return { ws, received };
}

const types = (client) => client.received.map((entry) => entry.message.type);

describe("가짜 공급자 — 실호출 관측 선 형식(U11-a)", () => {
  it("session.started는 중첩 session.id를 싣고 응답 id · 응답 끝 사건은 어디에도 없다", async () => {
    const provider = await start({ turns: ["normal"] });
    const client = await session(provider);
    client.ws.send(JSON.stringify({ type: "response.create" }));
    for (let i = 0; i < 3; i += 1) {
      client.ws.send(
        JSON.stringify({ type: "session.input_audio.append", audio: synthesizeInputPayload(i).toString("base64") }),
      );
    }
    await waitUntil(() => types(client).includes("session.output_audio.delta"));
    await new Promise((resolve) => setTimeout(resolve, 100));

    expect(client.received[0].message).toEqual({ type: "session.started", session: { id: "fake-provider-1" } });
    for (const { message } of client.received) {
      expect(message).not.toHaveProperty("responseId");
      expect(message.type).not.toBe("session.output.done");
      expect(message.type).not.toBe("session.error");
    }
  });

  it("출력 음성은 delta(base64) 하나만 싣는다 — audio · responseId 필드가 없다", () => {
    const built = buildTurn({ connection: 1, turn: 1, kind: "normal", chunkBytes: 960 });
    const audio = built.steps.filter((step) => step.message.type === "session.output_audio.delta");

    expect(audio.length).toBeGreaterThan(1);
    for (const step of audio) {
      expect(Object.keys(step.message).sort()).toEqual(["delta", "type"]);
      expect(Buffer.from(step.message.delta, "base64").length).toBeLessThanOrEqual(960);
    }
    expect(Buffer.concat(audio.map((step) => Buffer.from(step.message.delta, "base64"))).equals(built.pcm)).toBe(true);
  });

  it("응답 경계는 마지막 조각 뒤의 무음이다 — 조각이 모두 온 뒤에는 아무 사건도 따라오지 않는다", async () => {
    const provider = await start({ turns: ["normal"] });
    const client = await session(provider);
    for (let i = 0; i < 3; i += 1) {
      client.ws.send(
        JSON.stringify({ type: "session.input_audio.append", audio: synthesizeInputPayload(i).toString("base64") }),
      );
    }
    const turnEnd = () => provider.records.some((entry) => entry.ev === "turn-emitted");
    await waitUntil(turnEnd);
    // turn-emitted는 공급자가 보낸 시점이라 느린 러너에서는 마지막 조각이 아직 클라이언트에 닿기 전일 수 있다.
    // 수신 수를 세는 순간 고정하면 그 사이 도착한 조각이 "뒤따른 사건"으로 잡히므로, 150ms 동안 새 수신이 없을 때까지 기다린다.
    let count;
    do {
      count = client.received.length;
      await new Promise((resolve) => setTimeout(resolve, 150));
    } while (client.received.length !== count);

    expect(client.received).toHaveLength(count);
    expect(client.received.at(-1).message.type).toBe("session.output_audio.delta");
  });

  it("지원 목록 밖 type에는 error{invalid_request_error, invalid_value, param type}로 답하고 message를 되비추지 않는다", async () => {
    const records = [];
    const provider = await start({ onRecord: (entry) => records.push(entry) });
    const client = await session(provider);
    client.ws.send(JSON.stringify({ type: "session.response.create", secret: "비밀값" }));
    await waitUntil(() => types(client).includes("error"));

    const error = client.received.find((entry) => entry.message.type === "error").message;
    expect(error).toEqual(unsupportedTypeError());
    expect(error.error).toMatchObject({ type: "invalid_request_error", code: "invalid_value", param: "type" });
    expect(JSON.stringify(error)).not.toContain("session.response.create");
    expect(JSON.stringify(records)).not.toContain("비밀값");
  });

  it("지원 목록 열다섯 개 중 쓰지 않는 type은 error 없이 받는다(append · response.create는 아래에서 따로 엄격하게 본다)", async () => {
    const provider = await start();
    const client = await session(provider, { bearer: false });
    expect(SUPPORTED_TYPES).toHaveLength(15);
    for (const type of ["session.update", "session.thinking.append", "session.commentary.append", "decision.create"]) {
      client.ws.send(JSON.stringify({ type, text: "고정 문장" }));
    }
    await new Promise((resolve) => setTimeout(resolve, 100));

    expect(types(client)).toEqual(["session.started"]);
    expect(provider.records.filter((entry) => entry.ev === "recv-unsupported")).toHaveLength(0);
    expect(provider.records.find((entry) => entry.ev === "connect")).toMatchObject({ bearer: false });
  });

  it("session.instructions.append에 delegation_id 키가 없으면 missing_required_parameter로 거절하고 null이어도 키가 있으면 받는다", async () => {
    const provider = await start();
    const client = await session(provider);
    client.ws.send(JSON.stringify({ type: "session.instructions.append", event_id: "e1", content: "고정 문장" }));
    await waitUntil(() => types(client).includes("error"));
    expect(client.received.find((entry) => entry.message.type === "error").message).toEqual(missingDelegationIdError());
    expect(client.received.find((entry) => entry.message.type === "error").message.error).toMatchObject({
      code: "missing_required_parameter",
      param: "delegation_id",
    });
    expect(types(client)).not.toContain("session.instructions.appended");

    client.ws.send(JSON.stringify({ type: "session.instructions.append", event_id: "e2", delegation_id: null, content: "고정 문장" }));
    await waitUntil(() => types(client).includes("session.instructions.appended"));
    const ack = client.received.find((entry) => entry.message.type === "session.instructions.appended").message;
    expect(ack).toMatchObject({ client_event_id: "e2", start_ms: 0, end_ms: ACK_LEAD_MS });
    expect(typeof ack.event_id).toBe("string");
    const records = provider.records.filter((entry) => entry.ev === "session.instructions.append");
    expect(records[0]).toMatchObject({ delegationIdPresent: false });
    expect(records[1]).toMatchObject({ delegationIdPresent: true, delegationIdNull: true, textLength: 5, eventId: "e2" });
  });

  it("response.create는 Responses 위임 세션 전용이라 거절한다", async () => {
    const provider = await start();
    const client = await session(provider);
    client.ws.send(JSON.stringify({ type: "response.create" }));
    await waitUntil(() => types(client).includes("error"));

    expect(client.received.find((entry) => entry.message.type === "error").message).toEqual(responseCreateRefused());
    expect(provider.records.find((entry) => entry.ev === "response.create")).toBeDefined();
  });

  it("지시를 받은 뒤의 새 말은 확인 end_ms 뒤에서 시작하고 전사가 음성보다 앞서며 앞에 낡은 꼬리 채움 음성이 있다", async () => {
    const provider = await start({ turns: ["normal"], chunkBytes: 4800 });
    const client = await session(provider);
    client.ws.send(JSON.stringify({ type: "session.instructions.append", event_id: "e1", delegation_id: null, content: "고정 문장" }));
    await waitUntil(() => types(client).includes("session.instructions.appended"));
    for (let i = 0; i < 3; i += 1) {
      client.ws.send(
        JSON.stringify({ type: "session.input_audio.append", audio: synthesizeInputPayload(i).toString("base64") }),
      );
    }
    await waitUntil(() => provider.records.some((entry) => entry.ev === "turn-emitted"));
    await waitUntil(() => {
      const turn = provider.records.find((entry) => entry.ev === "turn");
      const speech = client.received.find((entry) => entry.message.type === "session.output_transcript.delta");
      if (!turn || !speech) return false;
      const bytes = client.received
        .filter((entry) => entry.message.type === "session.output_audio.delta")
        .reduce((sum, entry) => sum + Buffer.from(entry.message.delta, "base64").length, 0);
      return bytes >= turn.outputBytes + (speech.message.start_ms + FILLER_MS) * 48;
    });

    const ack = client.received.find((entry) => entry.message.type === "session.instructions.appended").message;
    const order = types(client).filter((type) => type !== "session.started");
    expect(order.indexOf("session.output_transcript.delta")).toBeLessThan(order.indexOf("session.output_audio.delta"));
    const firstSpeech = client.received.find((entry) => entry.message.type === "session.output_transcript.delta").message;
    expect(firstSpeech.start_ms).toBeGreaterThanOrEqual(ack.end_ms);
    const audioBytes = client.received
      .filter((entry) => entry.message.type === "session.output_audio.delta")
      .reduce((sum, entry) => sum + Buffer.from(entry.message.delta, "base64").length, 0);
    const turn = provider.records.find((entry) => entry.ev === "turn");
    expect(audioBytes).toBeGreaterThan(turn.outputBytes);
    expect(audioBytes - turn.outputBytes).toBe((firstSpeech.start_ms - 0 + FILLER_MS) * 48);
  });

  it("일반 지시 채움은 실제700ms 계약과 같고 서버 수신 경계 뒤에는 정확한 새 PCM만 남는다", async () => {
    const root = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
    const timing = readFileSync(resolve(root, "contracts/timing.md"), "utf8");
    const config = readFileSync(
      resolve(root, "backend/realtime-activity/src/main/resources/application.yml"), "utf8",
    );
    const contractRows = [...timing.matchAll(/^\| `mujoong\.timing\.provider\.new-speech-margin` \| `(\d+)ms` \|/gm)];
    const configRows = [...config.matchAll(/^      new-speech-margin: (\d+)ms\r?$/gm)];
    expect(contractRows).toHaveLength(1);
    expect(configRows).toHaveLength(1);
    const marginMs = Number(contractRows[0][1]);
    expect(marginMs).toBe(700);
    expect(Number(configRows[0][1])).toBe(marginMs);
    expect(FILLER_MS).toBe(marginMs);
    expect(FILLER_MS).toBe(700);

    const pcm = outputPcmFor(1, "normal");
    const prefix = Buffer.alloc((ACK_LEAD_MS + marginMs) * 48);
    const expected = Buffer.concat([prefix, pcm]);
    const built = buildTurn({ connection: 1, turn: 1, kind: "normal", pendingAckEndMs: ACK_LEAD_MS });
    const builtAudio = Buffer.concat(built.steps
      .filter((step) => step.message.type === "session.output_audio.delta")
      .map((step) => Buffer.from(step.message.delta, "base64")));
    expect(builtAudio.equals(expected)).toBe(true);

    const provider = await start({ turns: ["normal"], chunkBytes: 4800 });
    const client = await session(provider);
    client.ws.send(JSON.stringify({ type: "session.instructions.append", event_id: "baseline", delegation_id: null, content: "고정 문장" }));
    await waitUntil(() => types(client).includes("session.instructions.appended"));
    for (let i = 0; i < 3; i += 1) {
      client.ws.send(JSON.stringify({ type: "session.input_audio.append", audio: synthesizeInputPayload(i).toString("base64") }));
    }
    const receivedAudio = () => Buffer.concat(client.received
      .filter((entry) => entry.message.type === "session.output_audio.delta")
      .map((entry) => Buffer.from(entry.message.delta, "base64")));
    await waitUntil(() => provider.records.some((entry) => entry.ev === "turn-emitted"));
    await waitUntil(() => receivedAudio().length >= expected.length);
    const received = receivedAudio();
    expect(received.length).toBe(expected.length);
    expect(received.equals(expected)).toBe(true);
    const speech = client.received.find((entry) => entry.message.type === "session.output_transcript.delta").message;
    const accepted = received.subarray((speech.start_ms + marginMs) * 48);
    const turn = provider.records.find((entry) => entry.ev === "turn");
    expect(accepted.length).toBe(turn.outputBytes);
    expect(sha256Hex(accepted)).toBe(turn.outputSha256);
  });

});
