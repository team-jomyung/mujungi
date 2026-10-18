// 기기 시뮬레이터 · 제어 봉투 시험 (U1 3.5 Step 46 · RD-23 · J143 · R3-4 · OD-8 · SEC-07 · 계약 4.2 · device-ws.asyncapi.yaml)
// 무작위 포트의 대역 WS 서버(realtime DeviceGateway 역할)에 시뮬레이터를 붙여, HEARTBEAT 송신 직전 sentAt(J143) · 명령 즉시 ACK ·
// 승인 음성 미리 받기 → DEVICE_READY · 0x02 재생 표식과 해시 · STOP_OUTPUT · CLOSE_TRANSPORT 처리를 확인한다.
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { WebSocketServer } from "ws";
import {
  encodeOutputFrame,
  decodeFrame,
  sha256Hex,
  synthesizeTone,
  synthesizeInputPayload,
} from "../../lib/frameCodec.mjs";
import {
  buildDeviceEnvelope,
  createSeqCounter,
  isDateTime,
  parseServerEnvelope,
  playbackMarkPayload,
} from "../../lib/envelope.mjs";
import {
  DeviceSimulator,
  SimulatorTimeoutError,
  playbackSha256,
  probeUpgrade,
} from "../../device-simulator/simulator.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const ALLOWED_ORIGIN = "https://mujoong.invalid";
const SESSION_ID = "11111111-2222-4333-8444-555555555555";
const HEARTBEAT_MS = 80;

/** realtime DeviceGateway 역할의 대역 서버. 받은 봉투 · 바이너리를 기록하고 시험이 명령을 보낸다. */
async function startGateway() {
  const received = [];
  const binaries = [];
  let socket = null;
  const nextSeq = createSeqCounter();
  const server = http.createServer((_req, res) => {
    res.writeHead(404);
    res.end();
  });
  const wss = new WebSocketServer({ noServer: true });
  server.on("upgrade", (req, sock, head) => {
    if (req.headers.origin !== ALLOWED_ORIGIN) {
      sock.end("HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
      return;
    }
    wss.handleUpgrade(req, sock, head, (ws) => {
      socket = ws;
      ws.cookie = req.headers.cookie ?? null;
      ws.on("message", (data, isBinary) => {
        if (isBinary) {
          binaries.push({ data: Buffer.from(data), at: Date.now() });
          return;
        }
        const envelope = JSON.parse(data.toString("utf8"));
        received.push({ envelope, recvAtMs: Date.now() });
        if (envelope.type === "HELLO") {
          send("HELLO_OK", {
            connectionId: randomUUID(),
            serverTime: new Date().toISOString(),
            heartbeatIntervalMs: HEARTBEAT_MS,
            audio: "pcm16le/24000/1",
            session: null,
          });
        } else if (envelope.type === "HEARTBEAT") {
          send("HEARTBEAT_ACK", {
            hbSeq: envelope.payload.hbSeq,
            serverTime: new Date().toISOString(),
          });
        }
      });
    });
  });
  function send(type, payload, { opId, sessionId, extra } = {}) {
    const envelope = { type, seq: nextSeq(), sentAt: new Date().toISOString() };
    if (opId) envelope.opId = opId;
    if (sessionId) envelope.sessionId = sessionId;
    envelope.payload = payload;
    Object.assign(envelope, extra ?? {});
    socket.send(JSON.stringify(envelope));
    return envelope;
  }
  function sendBinary(frame) {
    socket.send(frame, { binary: true });
  }
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = server.address().port;
  return {
    url: `ws://127.0.0.1:${port}/ws/device`,
    received,
    binaries,
    get socket() {
      return socket;
    },
    send,
    sendBinary,
    of: (type) => received.filter((entry) => entry.envelope.type === type),
    async close() {
      for (const client of wss.clients) client.terminate();
      await new Promise((resolve) => wss.close(resolve));
      server.closeAllConnections();
      await new Promise((resolve) => server.close(resolve));
    },
  };
}

/** 승인 음성 미리 받기 대역(저장소의 임시 WAV를 그대로 준다). */
function mediaClient() {
  const files = {
    "/media/approved/closing-v1.wav": "closing-v1.wav",
    "/media/approved/waiting-filler-v1.wav": "waiting-filler-v1.wav",
  };
  return {
    calls: [],
    async get(urlPath, extra) {
      this.calls.push({ urlPath, extra });
      const name = files[urlPath];
      if (!name) return { status: 404, body: Buffer.alloc(0) };
      return {
        status: 200,
        body: await readFile(path.join(repoRoot, "infra", "seed-audio", name)),
      };
    },
  };
}

const APPROVED = [
  {
    contentCode: "CLOSING",
    contentVersionId: "aaaaaaaa-0000-4000-8000-000000000001",
    audioUrl: "/media/approved/closing-v1.wav",
  },
  {
    contentCode: "WAITING_FILLER",
    contentVersionId: "aaaaaaaa-0000-4000-8000-000000000002",
    audioUrl: "/media/approved/waiting-filler-v1.wav",
  },
];

async function until(predicate, what, timeoutMs = 3000) {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error(`${what}: 기한 초과`);
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

let gateway;
let sim;

beforeEach(async () => {
  gateway = await startGateway();
});

afterEach(async () => {
  sim?.terminate();
  sim = undefined;
  await gateway.close();
});

async function connected(options = {}) {
  sim = new DeviceSimulator({
    url: gateway.url,
    origin: ALLOWED_ORIGIN,
    cookie: "MUJOONG_DEVICE=tok",
    mediaClient: mediaClient(),
    playbackSpeed: 50,
    ...options,
  });
  await sim.open(2000);
  await sim.hello(2000);
  return sim;
}

describe("연결 · HELLO · HEARTBEAT(J143)", () => {
  it("Cookie · Origin으로 붙고 HELLO_OK 뒤 바로 HEARTBEAT를 보낸다", async () => {
    await connected();
    expect(gateway.socket.cookie).toBe("MUJOONG_DEVICE=tok");
    const hello = gateway.of("HELLO")[0].envelope;
    expect(hello).toMatchObject({
      type: "HELLO",
      seq: 1,
      payload: { audio: ["pcm16le/24000/1"], resume: null },
    });
    expect(sim.heartbeatIntervalMs).toBe(HEARTBEAT_MS);
    await until(() => gateway.of("HEARTBEAT").length >= 1, "첫 HEARTBEAT");
    expect(gateway.of("HEARTBEAT")[0].envelope.seq).toBe(2);
  });

  it("HEARTBEAT 봉투 sentAt은 송신 직전 값이다(묵은 값 · 빈 값 아님)", async () => {
    await connected();
    await until(() => gateway.of("HEARTBEAT").length >= 4, "HEARTBEAT 4개");
    const beats = gateway.of("HEARTBEAT");
    for (const { envelope, recvAtMs } of beats) {
      expect(isDateTime(envelope.sentAt)).toBe(true);
      const lag = recvAtMs - Date.parse(envelope.sentAt);
      expect(lag).toBeGreaterThanOrEqual(-5);
      expect(lag).toBeLessThan(500);
    }
    const stamps = beats.map((beat) => Date.parse(beat.envelope.sentAt));
    for (let i = 1; i < stamps.length; i += 1) {
      expect(stamps[i]).toBeGreaterThan(stamps[i - 1]);
    }
    // hbSeq는 1부터 오르고, 두 번째부터 직전 왕복(lastRttMs)을 싣는다(R3-4)
    expect(beats.map((beat) => beat.envelope.payload.hbSeq)).toEqual(beats.map((_, i) => i + 1));
    expect(beats[0].envelope.payload.lastRttMs).toBeNull();
    expect(beats.slice(1).some((beat) => Number.isInteger(beat.envelope.payload.lastRttMs))).toBe(
      true,
    );
    expect(sim.lastRttMs).not.toBeNull();
  });

  it("기기 시계 차이(skewMs)는 sentAt에만 들어간다", async () => {
    await connected({ skewMs: 60_000, auto: { heartbeat: false } });
    sim.heartbeatNow();
    await until(() => gateway.of("HEARTBEAT").length === 1, "HEARTBEAT");
    const { envelope, recvAtMs } = gateway.of("HEARTBEAT")[0];
    const skew = Date.parse(envelope.sentAt) - recvAtMs;
    expect(skew).toBeGreaterThan(59_000);
    expect(skew).toBeLessThan(61_000);
  });

  it("허용되지 않은 Origin은 업그레이드 403이다(probeUpgrade · open)", async () => {
    expect(
      await probeUpgrade({ url: gateway.url, origin: "https://evil.invalid", timeoutMs: 2000 }),
    ).toEqual({ status: "refused", httpStatus: 403 });
    expect(
      await probeUpgrade({ url: gateway.url, origin: ALLOWED_ORIGIN, timeoutMs: 2000 }),
    ).toEqual({ status: "open" });
    const evil = new DeviceSimulator({
      url: gateway.url,
      origin: "https://evil.invalid",
      cookie: null,
    });
    await expect(evil.open(2000)).rejects.toMatchObject({
      name: "HandshakeRefusedError",
      status: 403,
    });
  });
});

describe("세션 명령 처리", () => {
  it("opId 명령은 받자마자 ACK(ackOf = opId)하고, 승인 음성을 미리 받은 뒤 DEVICE_READY를 보낸다", async () => {
    await connected();
    const opId = randomUUID();
    gateway.send("START_SESSION", { approvedAudio: APPROVED }, { opId, sessionId: SESSION_ID });
    await until(() => gateway.of("DEVICE_READY").length === 1, "DEVICE_READY");
    const ack = gateway.of("ACK")[0].envelope;
    expect(ack).toMatchObject({ sessionId: SESSION_ID, payload: { ackOf: opId } });
    const ackIndex = gateway.received.findIndex((entry) => entry.envelope.type === "ACK");
    const readyIndex = gateway.received.findIndex(
      (entry) => entry.envelope.type === "DEVICE_READY",
    );
    expect(ackIndex).toBeLessThan(readyIndex);
    expect(gateway.of("DEVICE_READY")[0].envelope).toMatchObject({
      sessionId: SESSION_ID,
      payload: { mic: "OK", player: "OK", prefetch: "OK" },
    });
    expect(sim.mediaClient.calls.map((call) => call.urlPath)).toEqual(
      APPROVED.map((item) => item.audioUrl),
    );
    expect(sim.mediaClient.calls.every((call) => call.extra.cookies === false)).toBe(true);
    expect(sim.protocolErrors).toEqual([]);
  });

  it("미리 받기에 실패하면 prefetch PARTIAL이고 그 문구 재생은 FAILED(FETCH_FAILED)다", async () => {
    await connected();
    const broken = [{ ...APPROVED[0], audioUrl: "/media/approved/missing.wav" }, APPROVED[1]];
    gateway.send(
      "START_SESSION",
      { approvedAudio: broken },
      { opId: randomUUID(), sessionId: SESSION_ID },
    );
    await until(() => gateway.of("DEVICE_READY").length === 1, "DEVICE_READY");
    expect(gateway.of("DEVICE_READY")[0].envelope.payload.prefetch).toBe("PARTIAL");
    const playbackId = randomUUID();
    gateway.send(
      "PLAY_APPROVED_CONTENT",
      { playbackId, contentCode: "CLOSING", contentVersionId: APPROVED[0].contentVersionId },
      { opId: randomUUID(), sessionId: SESSION_ID },
    );
    await until(() => gateway.of("PLAYBACK_MARK").length === 1, "FAILED 표식");
    expect(gateway.of("PLAYBACK_MARK")[0].envelope.payload).toMatchObject({
      playbackId,
      status: "FAILED",
      failureReason: "FETCH_FAILED",
    });
  });

  it("OUTPUT_BEGIN → 0x02 → OUTPUT_END는 STARTED · COMPLETED 표식이고 받은 바이트 해시를 기록한다", async () => {
    await connected();
    const playbackId = randomUUID();
    const parts = [synthesizeTone(2400, { freq: 300 }), synthesizeTone(2400, { freq: 310 })];
    gateway.sendBinary(encodeOutputFrame(1, synthesizeTone(10))); // OUTPUT_BEGIN 없이 온 0x02(BR7.6)
    gateway.send("OUTPUT_BEGIN", { playbackId, source: "GPT_LIVE" }, { sessionId: SESSION_ID });
    gateway.sendBinary(encodeOutputFrame(2, parts[0]));
    gateway.sendBinary(encodeOutputFrame(3, parts[1]));
    gateway.send("OUTPUT_END", { playbackId, totalMs: 200 });
    await until(() => gateway.of("PLAYBACK_MARK").length === 2, "STARTED · COMPLETED");
    const marks = gateway.of("PLAYBACK_MARK").map((entry) => entry.envelope.payload);
    expect(marks.map((mark) => mark.status)).toEqual(["STARTED", "COMPLETED"]);
    expect(
      marks.every((mark) => mark.playbackId === playbackId && isDateTime(mark.observedAt)),
    ).toBe(true);
    expect(playbackSha256(sim, playbackId)).toBe(sha256Hex(Buffer.concat(parts)));
    expect(sim.counters).toMatchObject({ outputBegin: 1, outputFrames: 3, orphanOutputBytes: 20 });
    expect(sim.playbacks.get(playbackId).bytes).toBe(9600);
  });

  it("STOP_OUTPUT은 재생 중인 것을 STOPPED로, CLOSE_TRANSPORT는 VOICE_SESSION_CLOSED로 답한다", async () => {
    await connected();
    const playbackId = randomUUID();
    gateway.send("OUTPUT_BEGIN", { playbackId, source: "GPT_LIVE" }, { sessionId: SESSION_ID });
    gateway.sendBinary(encodeOutputFrame(1, synthesizeTone(24_000)));
    await until(() => gateway.of("PLAYBACK_MARK").length === 1, "STARTED");
    gateway.send(
      "STOP_OUTPUT",
      { reason: "GUARDIAN_STOP" },
      { opId: randomUUID(), sessionId: SESSION_ID },
    );
    await until(() => gateway.of("PLAYBACK_MARK").length === 2, "STOPPED");
    expect(gateway.of("PLAYBACK_MARK")[1].envelope.payload).toMatchObject({
      status: "STOPPED",
      stopCause: "STOP_OUTPUT",
    });

    gateway.send(
      "CLOSE_TRANSPORT",
      { reason: "GUARDIAN_STOP" },
      { opId: randomUUID(), sessionId: SESSION_ID },
    );
    await until(() => gateway.of("VOICE_SESSION_CLOSED").length === 1, "VOICE_SESSION_CLOSED");
    const closed = gateway.of("VOICE_SESSION_CLOSED")[0].envelope;
    expect(closed.sessionId).toBe(SESSION_ID);
    expect(
      isDateTime(closed.payload.outputStoppedAt) && isDateTime(closed.payload.micClosedAt),
    ).toBe(true);
    expect(gateway.of("ACK")).toHaveLength(2);
  });

  it("auto.voiceSessionClosed가 거짓이면 CLOSE_TRANSPORT 뒤에도 보내지 않고 호출자가 보낸다(C11)", async () => {
    await connected({ auto: { voiceSessionClosed: false } });
    gateway.send(
      "CLOSE_TRANSPORT",
      { reason: "GUARDIAN_STOP" },
      { opId: randomUUID(), sessionId: SESSION_ID },
    );
    await until(
      () => sim.events.some((event) => event.kind === "close-transport"),
      "close-transport 사건",
    );
    const sent = await sim.sendInputFrames(3, { intervalMs: 1 });
    await until(() => gateway.binaries.length === 3, "0x01 3장");
    expect(gateway.of("VOICE_SESSION_CLOSED")).toHaveLength(0);
    sim.sendVoiceSessionClosed(SESSION_ID);
    await until(() => gateway.of("VOICE_SESSION_CLOSED").length === 1, "VOICE_SESSION_CLOSED");
    expect(sent.frames).toBe(3);
  });
});

describe("입력 프레임 · 기록 · 기한", () => {
  it("0x01은 965바이트 · 순번 1부터 · 본문 해시가 보낸 값과 같다", async () => {
    await connected({ auto: { heartbeat: false } });
    const sent = await sim.sendInputFrames(5, { intervalMs: 1 });
    await until(() => gateway.binaries.length === 5, "0x01 5장");
    const frames = gateway.binaries.map((entry) => decodeFrame(entry.data));
    expect(gateway.binaries.every((entry) => entry.data.length === 965)).toBe(true);
    expect(frames.map((frame) => frame.frame.seq)).toEqual([1, 2, 3, 4, 5]);
    expect(sent).toMatchObject({ frames: 5, bytes: 4800, firstSeq: 1, lastSeq: 5 });
    expect(sent.sha256).toBe(
      sha256Hex(Buffer.concat([0, 1, 2, 3, 4].map((i) => synthesizeInputPayload(i)))),
    );
  });

  it("계약 형식이 틀린 서버 봉투는 protocolErrors에 남고, ERROR는 개수를 센다", async () => {
    await connected();
    gateway.send("START_SESSION", { approvedAudio: [] }, { sessionId: SESSION_ID }); // opId 없음
    gateway.send("SESSION_STATE", { view: "LISTENING" }, { opId: randomUUID() }); // opId가 붙으면 안 됨
    gateway.send("SESSION_STATE", { view: "DANCING" });
    gateway.send("ERROR", { code: "BAD_PAYLOAD", refSeq: 3 });
    await until(() => sim.counters.errorsReceived === 1, "ERROR");
    expect(sim.protocolErrors.length).toBe(3);
    expect(sim.protocolErrors[0].problems.join(" ")).toContain("opId");
    expect(sim.protocolErrors[2].problems.join(" ")).toContain("view");
  });

  it("기다림에는 양수 기한이 있어야 하고, 넘으면 SimulatorTimeoutError다(SEC-05)", async () => {
    await connected();
    await expect(sim.waitFor(() => false, { timeoutMs: 0, what: "x" })).rejects.toBeInstanceOf(
      RangeError,
    );
    await expect(
      sim.waitFor(() => false, { timeoutMs: 80, what: "오지 않는 봉투" }),
    ).rejects.toBeInstanceOf(SimulatorTimeoutError);
    await expect(
      sim.waitForEvent(() => false, { timeoutMs: 80, what: "오지 않는 사건" }),
    ).rejects.toBeInstanceOf(SimulatorTimeoutError);
    expect(String(sim)).not.toContain("tok");
    gateway.socket.close(4401);
    expect(await sim.waitForClose(2000)).toBe(4401);
    await expect(sim.waitFor(() => false, { timeoutMs: 500, what: "닫힌 뒤" })).rejects.toThrow(
      /닫혔다/,
    );
  });
});

describe("제어 봉투(contracts/schemas/ws-*.schema.json)", () => {
  it("기기 봉투는 기기 type만, sessionId가 필요한 type은 UUID를 요구한다", () => {
    const envelope = buildDeviceEnvelope(
      "HEARTBEAT",
      { hbSeq: 1, lastRttMs: null },
      { seq: 1, sentAtMs: Date.UTC(2026, 9, 10) },
    );
    expect(envelope).toEqual({
      type: "HEARTBEAT",
      seq: 1,
      sentAt: "2026-10-10T00:00:00.000Z",
      payload: { hbSeq: 1, lastRttMs: null },
    });
    expect(() => buildDeviceEnvelope("HELLO_OK", {}, { seq: 1, sentAtMs: 0 })).toThrow(TypeError);
    expect(() => buildDeviceEnvelope("DEVICE_READY", {}, { seq: 1, sentAtMs: 0 })).toThrow(
      TypeError,
    );
    expect(() => buildDeviceEnvelope("HEARTBEAT", {}, { seq: 0, sentAtMs: 0 })).toThrow(RangeError);
    expect(() =>
      playbackMarkPayload({ playbackId: SESSION_ID, status: "PAUSED", observedAtMs: 0 }),
    ).toThrow(TypeError);
    const counter = createSeqCounter();
    expect([counter(), counter(), counter()]).toEqual([1, 2, 3]);
  });

  it("서버 봉투 확인은 모르는 필드 · 틀린 enum · 형식 문제를 모두 알려 준다", () => {
    const ok = parseServerEnvelope(
      JSON.stringify({
        type: "OUTPUT_END",
        seq: 4,
        sentAt: "2026-10-10T00:00:00.123456Z",
        sessionId: null,
        payload: { playbackId: SESSION_ID },
      }),
    );
    expect(ok.problems).toEqual([]);
    const bad = parseServerEnvelope(
      JSON.stringify({
        type: "STOP_OUTPUT",
        seq: 0,
        sentAt: "어제",
        extra: 1,
        payload: { reason: "LOUD" },
      }),
    );
    expect(bad.problems.join("\n")).toMatch(/extra/);
    expect(bad.problems.join("\n")).toMatch(/seq/);
    expect(bad.problems.join("\n")).toMatch(/sentAt/);
    expect(bad.problems.join("\n")).toMatch(/opId/);
    expect(bad.problems.join("\n")).toMatch(/sessionId/);
    expect(bad.problems.join("\n")).toMatch(/reason/);
    expect(parseServerEnvelope("{")).toEqual({ envelope: null, problems: ["JSON이 아니다"] });
    expect(parseServerEnvelope("[1]").envelope).toBeNull();
  });
});
