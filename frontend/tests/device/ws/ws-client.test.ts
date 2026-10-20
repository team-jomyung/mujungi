import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { connectWsClient, deviceSocketUrl, type WsClientHandlers } from "@/device/ws/wsClient";
import { encodeInputFrame, encodeOutputFrame, INPUT_FRAME_SAMPLES } from "@/shared/protocol/frameCodec";
import { CONTENT_VERSION_ID, OP_ID, PLAYBACK_ID, SESSION_ID } from "../../support/fixtures";
import { FakeSocket, helloOkPayload } from "../../support/fakeSocket";

// 기기 WSS 클라이언트 시험 (U1 3.5 Step 41 · 계약 4.2 · BR3.6 · BR3.7 · BR9.7 · OD-8 · R3-4 · J27 · J123 · AC6.2.8 ~ AC6.2.11)
// seq는 방향마다 1부터, 열리면 HELLO, HELLO_OK 뒤 heartbeatIntervalMs마다 HEARTBEAT(봉투 sentAt은 송신 직전 시계),
// opId가 붙은 서버 명령은 받자마자 ACK, 닫기는 코드와 '기기가 닫았는지'를 넘긴다.

let clock = 0;

function connect() {
  const socket = new FakeSocket();
  const handlers = {
    onReady: vi.fn<WsClientHandlers["onReady"]>(),
    onMessage: vi.fn<WsClientHandlers["onMessage"]>(),
    onOutputFrame: vi.fn<WsClientHandlers["onOutputFrame"]>(),
    onClose: vi.fn<WsClientHandlers["onClose"]>(),
  };
  const createSocket = vi.fn((url: string) => {
    expect(url).toBe("wss://localhost:8443/ws/device");
    return socket;
  });
  const client = connectWsClient(
    { url: "wss://localhost:8443/ws/device", appVersion: "mujoong-device-web/u1", heartbeatFallbackMs: 5_000, nowMs: () => clock, createSocket },
    handlers,
  );
  return { socket, handlers, client, createSocket };
}

function openReady(intervalMs = 5_000) {
  const context = connect();
  context.socket.open();
  context.socket.serverSend("HELLO_OK", helloOkPayload(intervalMs));
  return context;
}

beforeEach(() => {
  vi.useFakeTimers();
  clock = Date.UTC(2026, 9, 15, 1, 0, 0);
});

afterEach(() => {
  vi.useRealTimers();
});

describe("wsClient — 연결 · HELLO", () => {
  it("열리면 seq 1 HELLO를 보내고 토큰 없이 앱 판 · 음성 형식만 싣는다(J29 · BR3.6)", () => {
    const { socket } = connect();
    expect(socket.binaryType).toBe("arraybuffer");
    expect(socket.sent).toHaveLength(0);
    socket.open();
    const [hello] = socket.sentEnvelopes();
    expect(hello).toEqual({
      type: "HELLO",
      seq: 1,
      sentAt: "2026-10-15T01:00:00.000Z",
      payload: { appVersion: "mujoong-device-web/u1", audio: ["pcm16le/24000/1"], resume: null },
    });
  });

  it("HELLO_OK를 받으면 한 번만 준비를 알리고, 그 전에 온 서버 메시지 · 0x02는 넘기지 않는다(BR3.7)", () => {
    const { socket, handlers, client } = connect();
    socket.open();
    socket.serverSend("SESSION_STATE", { view: "LISTENING" }, { sessionId: SESSION_ID });
    socket.serverBinary(encodeOutputFrame(1, Uint8Array.of(1, 0)));
    expect(handlers.onMessage).not.toHaveBeenCalled();
    expect(handlers.onOutputFrame).not.toHaveBeenCalled();
    expect(client.isReady).toBe(false);
    socket.serverSend("HELLO_OK", helloOkPayload());
    socket.serverSend("HELLO_OK", helloOkPayload());
    expect(handlers.onReady).toHaveBeenCalledTimes(1);
    expect(client.isReady).toBe(true);
  });

  it("같은 origin 기기 WSS 주소를 만든다(https → wss)", () => {
    expect(deviceSocketUrl({ protocol: "https:", host: "localhost:8443" })).toBe("wss://localhost:8443/ws/device");
    expect(deviceSocketUrl({ protocol: "http:", host: "localhost:3000" })).toBe("ws://localhost:3000/ws/device");
  });
});

describe("wsClient — HEARTBEAT(OD-8 · R3-4)", () => {
  it("HELLO_OK의 heartbeatIntervalMs마다 보내고 봉투 sentAt은 송신 직전 기기 시계다", () => {
    const { socket } = openReady(5_000);
    vi.advanceTimersByTime(4_999);
    expect(socket.sentOfType("HEARTBEAT")).toHaveLength(0);
    clock += 5_000;
    vi.advanceTimersByTime(1);
    const [first] = socket.sentOfType("HEARTBEAT");
    expect(first.seq).toBe(2);
    expect(first.sentAt).toBe("2026-10-15T01:00:05.000Z");
    expect(first.payload).toEqual({ hbSeq: 1, lastRttMs: null });
  });

  it("HEARTBEAT_ACK까지의 왕복 시간을 다음 HEARTBEAT의 lastRttMs로 보낸다", () => {
    const { socket } = openReady(1_000);
    vi.advanceTimersByTime(1_000);
    clock += 40;
    socket.serverSend("HEARTBEAT_ACK", { hbSeq: 1, serverTime: "t" });
    vi.advanceTimersByTime(1_000);
    const heartbeats = socket.sentOfType("HEARTBEAT");
    expect(heartbeats.map((heartbeat) => heartbeat.payload)).toEqual([
      { hbSeq: 1, lastRttMs: null },
      { hbSeq: 2, lastRttMs: 40 },
    ]);
    // 모르는 hbSeq의 ACK는 무시한다.
    socket.serverSend("HEARTBEAT_ACK", { hbSeq: 99, serverTime: "t" });
    vi.advanceTimersByTime(1_000);
    expect(socket.sentOfType("HEARTBEAT")[2].payload).toEqual({ hbSeq: 3, lastRttMs: 40 });
  });

  it("답이 없는 HEARTBEAT 기록은 최근 16개만 두어 아주 늦은 ACK는 왕복 시간에 쓰지 않는다", () => {
    const { socket } = openReady(1_000);
    vi.advanceTimersByTime(17_000);
    socket.serverSend("HEARTBEAT_ACK", { hbSeq: 1, serverTime: "t" });
    vi.advanceTimersByTime(1_000);
    expect(socket.sentOfType("HEARTBEAT").at(-1)?.payload).toEqual({ hbSeq: 18, lastRttMs: null });
  });
});

describe("wsClient — seq · ACK · 메시지", () => {
  it("opId가 붙은 서버 명령 4종은 받자마자 ACK(ackOf)하고 보내는 seq는 1씩 는다(계약 4.2)", () => {
    const { socket, handlers } = openReady();
    socket.serverSend("START_SESSION", { approvedAudio: [] }, { opId: OP_ID, sessionId: SESSION_ID });
    socket.serverSend(
      "PLAY_APPROVED_CONTENT",
      { playbackId: PLAYBACK_ID, contentCode: "CLOSING", contentVersionId: CONTENT_VERSION_ID },
      { opId: "00000000-0000-4000-8000-000000000071", sessionId: SESSION_ID },
    );
    socket.serverSend("STOP_OUTPUT", { reason: "GUARDIAN_STOP" }, { opId: "00000000-0000-4000-8000-000000000072", sessionId: SESSION_ID });
    socket.serverSend("CLOSE_TRANSPORT", { reason: "ENDED" }, { opId: "00000000-0000-4000-8000-000000000073", sessionId: SESSION_ID });
    const acks = socket.sentOfType("ACK");
    expect(acks.map((ack) => ack.payload.ackOf)).toEqual([
      OP_ID,
      "00000000-0000-4000-8000-000000000071",
      "00000000-0000-4000-8000-000000000072",
      "00000000-0000-4000-8000-000000000073",
    ]);
    expect(socket.sentEnvelopes().map((envelope) => envelope.seq)).toEqual([1, 2, 3, 4, 5]);
    expect(handlers.onMessage.mock.calls.map(([message]) => message.type)).toEqual([
      "START_SESSION",
      "PLAY_APPROVED_CONTENT",
      "STOP_OUTPUT",
      "CLOSE_TRANSPORT",
    ]);
  });

  it("같은 seq는 한 번만 처리하고 모르는 type · 틀린 JSON · 틀린 payload는 버리되 연결을 유지한다(J123)", () => {
    const { socket, handlers, client } = openReady();
    socket.serverSend("SESSION_STATE", { view: "LISTENING" }, { seq: 10, sessionId: SESSION_ID });
    socket.serverSend("SESSION_STATE", { view: "THINKING" }, { seq: 10, sessionId: SESSION_ID });
    socket.serverSend("BRAND_NEW_TYPE", {}, { seq: 11 });
    socket.serverRaw("{oops");
    socket.serverSend("OUTPUT_BEGIN", { playbackId: "bad" }, { seq: 12, sessionId: SESSION_ID });
    expect(handlers.onMessage).toHaveBeenCalledTimes(1);
    expect(handlers.onMessage).toHaveBeenCalledWith({ type: "SESSION_STATE", sessionId: SESSION_ID, view: "LISTENING" });
    expect(handlers.onClose).not.toHaveBeenCalled();
    expect(socket.closeCalls).toHaveLength(0);
    expect(client.isReady).toBe(true);
  });

  it("0x02 출력 프레임만 넘기고 서버가 보낸 0x01 · 형식이 틀린 바이너리는 버린다", () => {
    const { socket, handlers } = openReady();
    socket.serverBinary(encodeOutputFrame(3, Uint8Array.of(1, 0, 2, 0)));
    socket.serverBinary(encodeInputFrame(4, new Int16Array(INPUT_FRAME_SAMPLES)));
    socket.serverBinary(Uint8Array.of(0x02, 0, 0, 0, 5, 1));
    expect(handlers.onOutputFrame).toHaveBeenCalledTimes(1);
    expect(handlers.onOutputFrame.mock.calls[0][0]).toMatchObject({ kind: 0x02, seq: 3 });
  });

  it("바이너리는 준비 뒤 열려 있을 때만 보내고 제어 메시지는 열려 있을 때만 보낸다", () => {
    const { socket, client } = connect();
    client.send("DEVICE_STATE", { mic: "OK" });
    client.sendBinary(Uint8Array.of(1));
    expect(socket.sent).toHaveLength(0);
    socket.open();
    client.sendBinary(Uint8Array.of(1));
    expect(socket.sentBinary()).toHaveLength(0);
    socket.serverSend("HELLO_OK", helloOkPayload());
    client.sendBinary(Uint8Array.of(1, 2));
    expect(socket.sentBinary()).toEqual([Uint8Array.of(1, 2)]);
    client.send("PLAYBACK_MARK", { status: "STARTED" }, SESSION_ID);
    expect(socket.sentOfType("PLAYBACK_MARK")[0].sessionId).toBe(SESSION_ID);
  });
});

describe("wsClient — 닫기(BR9.7 · AC6.2.3)", () => {
  it("기기가 1000 DEVICE_OFF로 닫으면 HEARTBEAT를 멈추고 closedByDevice=true로 알린다", () => {
    const { socket, handlers, client } = openReady(1_000);
    client.close(1000, "DEVICE_OFF");
    client.close(1000, "DEVICE_OFF");
    expect(socket.closeCalls).toEqual([{ code: 1000, reason: "DEVICE_OFF" }]);
    expect(handlers.onClose).toHaveBeenCalledWith(1000, true);
    expect(client.isReady).toBe(false);
    vi.advanceTimersByTime(10_000);
    expect(socket.sentOfType("HEARTBEAT")).toHaveLength(0);
  });

  it.each([4401, 4001, 1013])("서버가 %i로 닫으면 closedByDevice=false로 한 번만 알리고 그 뒤 송신은 버린다", (code) => {
    const { socket, handlers, client } = openReady(1_000);
    socket.serverClose(code);
    socket.serverClose(code);
    expect(handlers.onClose).toHaveBeenCalledTimes(1);
    expect(handlers.onClose).toHaveBeenCalledWith(code, false);
    client.send("HEARTBEAT", { hbSeq: 1, lastRttMs: null });
    client.close(1000, "DEVICE_OFF");
    expect(socket.closeCalls).toHaveLength(0);
    vi.advanceTimersByTime(5_000);
    expect(socket.sentOfType("HEARTBEAT")).toHaveLength(0);
  });

  it("오류 사건 자체로는 아무것도 하지 않고 뒤따르는 close가 동작을 정한다", () => {
    const { socket, handlers } = openReady();
    socket.onerror?.(new Event("error"));
    expect(handlers.onClose).not.toHaveBeenCalled();
  });
});
