// 가짜 공급자 시험 (U1 3.5 Step 46 · J110 선 형식 · J131 · RD-23 · SD-15 · SD-20 · contracts/ai-io.md 3절)
// 무작위 포트에 띄운 가짜 공급자에 ws 클라이언트(= realtime ProviderClient 역할)로 붙어 시나리오별 응답 · 금지 패턴 출력 ·
// 입력 · 출력 해시 기록을 확인한다. 실공급자 · 실제 키는 쓰지 않는다(Bearer 값은 시험이 만든 무작위 문자열).
import { randomBytes } from "node:crypto";
import http from "node:http";
import { afterEach, describe, expect, it } from "vitest";
import WebSocket from "ws";
import { INPUT_PAYLOAD_BYTES, sha256Hex, synthesizeInputPayload } from "../../lib/frameCodec.mjs";
import { main, parseArgs, startFakeProvider } from "../../fake-provider/server.mjs";
import {
  FORBIDDEN_PATTERN,
  TURN_SCRIPTS,
  buildTurn,
  outputPcmFor,
  parseList,
  scenarioForConnection,
  splitInTwo,
  textSha256,
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
    chunkIntervalMs: 1,
    onRecord: () => {},
    ...options,
  });
  providers.push(provider);
  return provider;
}

/** realtime ProviderClient처럼 붙는다. 받은 메시지를 모은다. */
function connect(
  provider,
  { bearer = randomBytes(16).toString("base64url"), handshakeTimeout = 2000 } = {},
) {
  const headers = bearer === null ? {} : { authorization: `Bearer ${bearer}` };
  const ws = new WebSocket(provider.url, { headers, handshakeTimeout });
  sockets.push(ws);
  const messages = [];
  const waiters = [];
  ws.on("message", (data) => {
    messages.push(JSON.parse(data.toString("utf8")));
    for (const waiter of waiters.splice(0)) {
      waiter();
    }
  });
  // 정리(terminate) 중에 나는 오류가 처리되지 않은 이벤트가 되지 않게 늘 받는다
  ws.on("error", () => {});
  const opened = new Promise((resolve, reject) => {
    ws.once("open", resolve);
    ws.once("unexpected-response", (req, res) => {
      res.resume();
      req.destroy();
      reject(Object.assign(new Error("unexpected"), { status: res.statusCode }));
    });
    ws.once("error", reject);
  });
  async function waitFor(predicate, timeoutMs = 3000) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const found = messages.find(predicate);
      if (found) {
        return found;
      }
      const left = deadline - Date.now();
      if (left <= 0) {
        throw new Error("기다린 메시지가 오지 않았다");
      }
      await new Promise((resolve) => {
        const timer = setTimeout(resolve, left);
        waiters.push(() => {
          clearTimeout(timer);
          resolve();
        });
      });
    }
  }
  return { ws, messages, opened, waitFor, bearer };
}

function sessionStart() {
  return {
    type: "session.start",
    model: "gpt-live-1",
    instructions: "시험 지시문",
    childContext: { displayName: "하늘", age: 8, interests: ["공룡"] },
    audio: { input: "pcm16le/24000/1", output: "pcm16le/24000/1" },
  };
}

function sendFrames(ws, from, count) {
  const payloads = [];
  for (let i = 0; i < count; i += 1) {
    const payload = synthesizeInputPayload(from + i);
    payloads.push(payload);
    ws.send(
      JSON.stringify({ type: "session.input_audio.append", audio: payload.toString("base64") }),
    );
  }
  return payloads;
}

/** 루프백 GET(프록시 설정과 무관). */
function httpGet(port, path) {
  return new Promise((resolve, reject) => {
    http
      .get({ host: "127.0.0.1", port, path, agent: false }, (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () =>
          resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString("utf8") }),
        );
      })
      .on("error", reject);
  });
}

const decodeAudio = (messages, responseId) =>
  Buffer.concat(
    messages
      .filter((m) => m.type === "session.output_audio.delta" && m.responseId === responseId)
      .map((m) => Buffer.from(m.audio, "base64")),
  );

describe("정상 연결 · 턴(J110 선 형식)", () => {
  it("session.start에 session.started로 답하고 기록에는 키 목록만 남긴다(SD-20)", async () => {
    const records = [];
    const provider = await start({ onRecord: (entry) => records.push(entry) });
    const client = connect(provider);
    await client.opened;
    client.ws.send(JSON.stringify(sessionStart()));
    const started = await client.waitFor((m) => m.type === "session.started");

    expect(started.providerSessionId).toBe("fake-provider-1");
    const startRecord = records.find((entry) => entry.ev === "session.start");
    expect(startRecord.keys).toEqual(["audio", "childContext", "instructions", "model", "type"]);
    expect(startRecord.childContextKeys).toEqual(["age", "displayName", "interests"]);
    expect(records.find((entry) => entry.ev === "connect")).toMatchObject({
      bearer: true,
      scenario: "ok",
      path: "/v1/live/sessions",
    });
    // 지시문 · 아이 문맥 값 · Bearer 값은 기록에 없다(SD-15)
    const text = JSON.stringify(records);
    expect(text).not.toContain("시험 지시문");
    expect(text).not.toContain("하늘");
    expect(text).not.toContain(client.bearer);
  });

  it("입력 프레임 묶음마다 아이 전사 → 말 끝 → 확정 → AI 전사 · 출력 음성 → 끝 순서로 보낸다", async () => {
    const provider = await start({ turns: ["normal"] });
    const client = connect(provider);
    await client.opened;
    client.ws.send(JSON.stringify(sessionStart()));
    await client.waitFor((m) => m.type === "session.started");
    sendFrames(client.ws, 0, 3);
    await client.waitFor((m) => m.type === "session.output.done");

    const types = client.messages
      .map((m) => m.type)
      .filter((type) => type !== "session.output_audio.delta");
    expect(types).toEqual([
      "session.started",
      "session.input_transcript.delta",
      "session.input_transcript.delta",
      "session.input_audio.speech_stopped",
      "session.input_transcript.done",
      "session.output_transcript.delta",
      "session.output_transcript.delta",
      "session.output.done",
    ]);
    const script = TURN_SCRIPTS.normal;
    const childDeltas = client.messages
      .filter((m) => m.type === "session.input_transcript.delta")
      .map((m) => m.delta);
    expect(childDeltas.join("")).toBe(script.childText);
    expect(client.messages.find((m) => m.type === "session.input_transcript.done").transcript).toBe(
      script.childText,
    );
    const aiDeltas = client.messages
      .filter((m) => m.type === "session.output_transcript.delta")
      .map((m) => m.delta);
    expect(aiDeltas.join("")).toBe(script.aiText);
    expect(aiDeltas.join("")).not.toContain(FORBIDDEN_PATTERN);
  });

  it("받은 입력 해시 · 보낸 출력 해시를 기록한다(J131 무변환 확인의 기준)", async () => {
    const records = [];
    const provider = await start({
      turns: ["normal", "forbidden"],
      onRecord: (entry) => records.push(entry),
    });
    const client = connect(provider);
    await client.opened;
    client.ws.send(JSON.stringify(sessionStart()));
    await client.waitFor((m) => m.type === "session.started");
    const first = sendFrames(client.ws, 0, 3);
    await client.waitFor((m) => m.type === "session.output.done" && m.responseId === "resp-1-1");
    const second = sendFrames(client.ws, 3, 3);
    await client.waitFor((m) => m.type === "session.output.done" && m.responseId === "resp-1-2");

    const turns = records.filter((entry) => entry.ev === "turn");
    expect(turns.map((turn) => turn.kind)).toEqual(["normal", "forbidden"]);
    expect(turns[0]).toMatchObject({
      turn: 1,
      frames: 3,
      inputBytes: 3 * INPUT_PAYLOAD_BYTES,
      inputSha256: sha256Hex(Buffer.concat(first)),
    });
    expect(turns[1]).toMatchObject({
      turn: 2,
      frames: 3,
      inputSha256: sha256Hex(Buffer.concat(second)),
    });
    for (const [index, turn] of turns.entries()) {
      const audio = decodeAudio(client.messages, turn.responseId);
      expect(audio.length).toBe(turn.outputBytes);
      expect(sha256Hex(audio)).toBe(turn.outputSha256);
      expect(turn.aiTextSha256).toBe(textSha256(TURN_SCRIPTS[turn.kind].aiText));
      expect(index + 1).toBe(turn.turn);
    }
    const lastInput = records.filter((entry) => entry.ev === "input").at(-1);
    expect(lastInput).toMatchObject({
      frames: 6,
      bytes: 6 * INPUT_PAYLOAD_BYTES,
      sha256: sha256Hex(Buffer.concat([...first, ...second])),
    });
    // 금지 턴 AI 문장에는 시드 사전 SCORE 패턴이 있고, 기록에는 문장 원문이 없다
    const forbiddenText = client.messages
      .filter((m) => m.type === "session.output_transcript.delta" && m.responseId === "resp-1-2")
      .map((m) => m.delta)
      .join("");
    expect(forbiddenText).toContain(FORBIDDEN_PATTERN);
    expect(JSON.stringify(records)).not.toContain(FORBIDDEN_PATTERN);
    expect(JSON.stringify(records)).not.toContain(TURN_SCRIPTS.normal.childText);
    // 턴 계획을 다 쓰면 더 보내지 않는다
    sendFrames(client.ws, 6, 3);
    client.ws.send(JSON.stringify({ type: "session.close" }));
    await new Promise((resolve) => client.ws.once("close", resolve));
    expect(records.filter((entry) => entry.ev === "turn")).toHaveLength(2);
    expect(records.find((entry) => entry.ev === "session.close")).toMatchObject({ inputFrames: 9 });
  });

  it("규격 밖 입력 길이는 badLength로, 모르는 type은 type만 기록한다", async () => {
    const records = [];
    const provider = await start({ onRecord: (entry) => records.push(entry) });
    const client = connect(provider);
    await client.opened;
    client.ws.send(
      JSON.stringify({
        type: "session.input_audio.append",
        audio: Buffer.alloc(10).toString("base64"),
      }),
    );
    client.ws.send(JSON.stringify({ type: "session.unknown", secret: "값" }));
    client.ws.send("not json");
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(records.find((entry) => entry.ev === "input")).toMatchObject({ badLength: 10 });
    expect(records.find((entry) => entry.ev === "recv-unknown")).toMatchObject({
      type: "session.unknown",
    });
    expect(records.some((entry) => entry.ev === "bad-message")).toBe(true);
    expect(JSON.stringify(records)).not.toContain("값");
  });
});

describe("연결 시나리오(RD-5 · NFR4.2)", () => {
  it("refuse는 업그레이드를 503으로, Bearer가 없으면 401로 거절한다", async () => {
    const provider = await start({ scenarios: ["refuse", "ok"] });
    const refused = connect(provider);
    await expect(refused.opened).rejects.toMatchObject({ status: 503 });
    const noKey = connect(provider, { bearer: null });
    await expect(noKey.opened).rejects.toMatchObject({ status: 401 });
  });

  it("no-started는 session.started를 보내지 않고, delayed는 지연 뒤에 보낸다", async () => {
    const records = [];
    const provider = await start({
      scenarios: ["no-started", "delayed"],
      startedDelayMs: 300,
      onRecord: (entry) => records.push(entry),
    });
    const withheld = connect(provider);
    await withheld.opened;
    withheld.ws.send(JSON.stringify(sessionStart()));
    const delayed = connect(provider);
    await delayed.opened;
    const sentAt = Date.now();
    delayed.ws.send(JSON.stringify(sessionStart()));
    await delayed.waitFor((m) => m.type === "session.started");

    expect(Date.now() - sentAt).toBeGreaterThanOrEqual(250);
    expect(withheld.messages.some((m) => m.type === "session.started")).toBe(false);
    expect(
      records.some((entry) => entry.ev === "session.started.withheld" && entry.conn === 1),
    ).toBe(true);
    expect(
      records.find((entry) => entry.ev === "session.started" && entry.conn === 2),
    ).toMatchObject({ delayedMs: 300 });
  });

  it("silent는 업그레이드에 아무 응답도 하지 않는다(연결 단계 무응답)", async () => {
    const provider = await start({ scenarios: ["silent"] });
    const client = connect(provider, { handshakeTimeout: 300 });
    await expect(client.opened).rejects.toThrow(/timed out/i);
  });
});

describe("시나리오 데이터 · 인자", () => {
  it("턴은 같은 입력이면 같은 바이트이고 출력은 조각 크기대로 나뉜다", () => {
    const a = buildTurn({ connection: 1, turn: 1, kind: "normal", chunkBytes: 4800 });
    const b = buildTurn({ connection: 1, turn: 1, kind: "normal", chunkBytes: 4800 });
    expect(a.pcm.equals(b.pcm)).toBe(true);
    expect(a.pcm.equals(outputPcmFor(1, "normal"))).toBe(true);
    expect(outputPcmFor(2, "normal").equals(a.pcm)).toBe(false);
    const chunks = a.steps.filter((step) => step.message.type === "session.output_audio.delta");
    expect(chunks).toHaveLength(Math.ceil(a.pcm.length / 4800));
    expect(a.steps.at(-1).message).toEqual({ type: "session.output.done", responseId: "resp-1-1" });
    expect(() => buildTurn({ connection: 1, turn: 1, kind: "normal", chunkBytes: 3 })).toThrow(
      RangeError,
    );
    expect(() => buildTurn({ connection: 1, turn: 1, kind: "loud" })).toThrow(RangeError);
    expect(TURN_SCRIPTS.forbidden.aiText).toContain(FORBIDDEN_PATTERN);
    expect(splitInTwo("가나다")).toEqual(["가나", "다"]);
  });

  it("연결 시나리오 목록은 n번째 연결에 n번째 값, 끝 값을 반복한다", () => {
    expect(scenarioForConnection(["refuse", "ok"], 1)).toBe("refuse");
    expect(scenarioForConnection(["refuse", "ok"], 2)).toBe("ok");
    expect(scenarioForConnection(["refuse", "ok"], 5)).toBe("ok");
    expect(parseList(" ok , delayed ", ["ok", "delayed"], "시나리오")).toEqual(["ok", "delayed"]);
    expect(() => parseList("ok,loud", ["ok"], "시나리오")).toThrow(RangeError);
    expect(() => parseList("", ["ok"], "시나리오")).toThrow(RangeError);
  });

  it("CLI 인자 오류는 종료 코드 2, --help는 0, 상태 경로는 200이다", async () => {
    const out = [];
    const err = [];
    const io = {
      stdout: { write: (text) => out.push(text) },
      stderr: { write: (text) => err.push(text) },
    };
    expect((await main(["--scenario", "loud"], io)).exitCode).toBe(2);
    expect((await main(["--port"], io)).exitCode).toBe(2);
    expect((await main(["--chunk-bytes", "3"], io)).exitCode).toBe(2);
    expect((await main(["--help"], io)).exitCode).toBe(0);
    expect(out.join("")).toContain("종료 코드");
    expect(parseArgs(["--turns", "forbidden", "--frames-per-turn", "10"])).toEqual({
      turns: ["forbidden"],
      framesPerTurn: 10,
    });

    const provider = await start();
    const res = await httpGet(provider.port, "/healthz");
    expect(res.status).toBe(200);
    expect(JSON.parse(res.body)).toEqual({ status: "UP" });
    expect((await httpGet(provider.port, "/other")).status).toBe(404);
  });
});
