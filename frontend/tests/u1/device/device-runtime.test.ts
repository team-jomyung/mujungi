import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import { DEVICE_APP_VERSION, startDeviceRuntime } from "@/device/runtime/deviceRuntime";
import { holdScreenWakeLock } from "@/device/power/wakeLock";
import { decodeFrame, encodeOutputFrame, INPUT_FRAME_BYTES, pcm16ToLittleEndianBytes } from "@/shared/protocol/frameCodec";
import type { SessionView } from "@/shared/protocol/messages";
import { CONTENT_VERSION_ID, OP_ID, PLAYBACK_ID, SESSION_ID, testTiming } from "../support/fixtures";
import { FakeAudioContext, FakeWorkletNode, fakeStream } from "../support/fakeAudio";
import { FakeSocket, helloOkPayload } from "../support/fakeSocket";

// 켜짐 상태 기기 실행부 시험 (U1 3.5 Step 41 · BR9.5 · BR9.7 · BR7.1 · BR7.6 · AC6.2.1 · AC6.2.3 · K5)
// 캡처 · 재생 · 화면 켜짐 유지 · WSS · 세션 처리를 묶은 실행부를 가짜 소켓 · AudioContext · AudioWorkletNode로 돌린다.

interface SentinelDouble {
  released: boolean;
  readonly release: Mock<() => Promise<void>>;
}

interface WakeLockDouble {
  readonly request: Mock<(type: "screen") => Promise<SentinelDouble>>;
  readonly sentinels: SentinelDouble[];
}

function wakeLockDouble(): WakeLockDouble {
  const sentinels: SentinelDouble[] = [];
  const request = vi.fn<(type: "screen") => Promise<SentinelDouble>>(async () => {
    const sentinel: SentinelDouble = {
      released: false,
      release: vi.fn(async () => {
        sentinel.released = true;
      }),
    };
    sentinels.push(sentinel);
    return sentinel;
  });
  return { request, sentinels };
}

function events() {
  return {
    onReady: vi.fn<() => void>(),
    onClosed: vi.fn<(code: number, closedByDevice: boolean) => void>(),
    onView: vi.fn<(view: SessionView) => void>(),
    onSpeaking: vi.fn<(speaking: boolean) => void>(),
  };
}

const flushPromises = () => vi.advanceTimersByTimeAsync(0);

let worklets: FakeWorkletNode[] = [];

beforeEach(() => {
  vi.useFakeTimers();
  worklets = [];
  vi.stubGlobal(
    "AudioWorkletNode",
    class extends FakeWorkletNode {
      constructor(context: unknown, name: string, options: AudioWorkletNodeOptions) {
        super(context, name, options);
        worklets.push(this);
      }
    },
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  Reflect.deleteProperty(navigator, "wakeLock");
});

async function startWith(options: { audioContext?: FakeAudioContext | null } = {}) {
  const sockets: FakeSocket[] = [];
  const handlers = events();
  const { stream, tracks } = fakeStream();
  const audioContext = options.audioContext === undefined ? new FakeAudioContext(24_000) : options.audioContext;
  const runtime = await startDeviceRuntime({
    audioContext: audioContext?.asAudioContext() ?? null,
    stream,
    timing: testTiming(),
    socketUrl: "wss://localhost:8443/ws/device",
    nowMs: () => Date.UTC(2026, 9, 15, 1, 0, 0),
    events: handlers,
    createSocket: (url) => {
      const socket = new FakeSocket(url);
      sockets.push(socket);
      return socket;
    },
  });
  return { runtime, sockets, handlers, tracks, audioContext };
}

function ready(socket: FakeSocket) {
  socket.open();
  socket.serverSend("HELLO_OK", helloOkPayload());
}

describe("startDeviceRuntime — 연결과 세션", () => {
  it("connect는 한 번에 소켓 하나만 열고 HELLO의 앱 판을 싣는다(단일 비행)", async () => {
    const { runtime, sockets, handlers } = await startWith();
    runtime.connect();
    runtime.connect();
    expect(sockets).toHaveLength(1);
    expect(sockets[0].url).toBe("wss://localhost:8443/ws/device");
    ready(sockets[0]);
    expect(sockets[0].sentOfType("HELLO")[0].payload.appVersion).toBe(DEVICE_APP_VERSION);
    expect(handlers.onReady).toHaveBeenCalledTimes(1);
  });

  it("캡처가 서면 DEVICE_READY{OK, OK}를 보내고 마이크 조각을 965B 0x01로 보낸다(BR7.1)", async () => {
    const { runtime, sockets, audioContext } = await startWith();
    expect(audioContext?.resume).toHaveBeenCalled();
    runtime.connect();
    const socket = sockets[0];
    ready(socket);
    socket.serverSend("START_SESSION", { approvedAudio: [] }, { opId: OP_ID, sessionId: SESSION_ID });
    await flushPromises();
    expect(socket.sentOfType("DEVICE_READY")[0].payload).toEqual({ mic: "OK", player: "OK", prefetch: "OK" });
    worklets[0].emit(new Float32Array(480).fill(0.5));
    const [frame] = socket.sentBinary();
    expect(frame.byteLength).toBe(INPUT_FRAME_BYTES);
    const decoded = decodeFrame(frame);
    expect(decoded.ok && decoded.frame.seq).toBe(1);
  });

  it("AudioContext가 없으면 마이크 트랙을 바로 놓고 DEVICE_READY에 UNAVAILABLE을 알린다", async () => {
    const { runtime, sockets, tracks } = await startWith({ audioContext: null });
    expect(tracks[0].stop).toHaveBeenCalled();
    runtime.connect();
    ready(sockets[0]);
    sockets[0].serverSend("START_SESSION", { approvedAudio: [] }, { opId: OP_ID, sessionId: SESSION_ID });
    await flushPromises();
    expect(sockets[0].sentOfType("DEVICE_READY")[0].payload).toEqual({ mic: "UNAVAILABLE", player: "UNAVAILABLE", prefetch: "OK" });
  });

  it("worklet 적재 · 재개가 실패해도 연결은 하고 마이크만 UNAVAILABLE이다", async () => {
    const context = new FakeAudioContext(48_000);
    context.resume.mockRejectedValueOnce(new Error("not allowed"));
    context.audioWorklet.addModule.mockRejectedValueOnce(new Error("blocked"));
    const { runtime, sockets, tracks } = await startWith({ audioContext: context });
    expect(tracks[0].stop).toHaveBeenCalled();
    runtime.connect();
    ready(sockets[0]);
    sockets[0].serverSend("START_SESSION", { approvedAudio: [] }, { opId: OP_ID, sessionId: SESSION_ID });
    await flushPromises();
    expect(sockets[0].sentOfType("DEVICE_READY")[0].payload).toMatchObject({ mic: "UNAVAILABLE", player: "OK" });
  });

  it("묶음 0x02가 실제로 소리 나기 시작하면 PLAYBACK_MARK STARTED와 '말하는 중'을 알린다(BR9.6)", async () => {
    const { runtime, sockets, handlers, audioContext } = await startWith();
    runtime.connect();
    const socket = sockets[0];
    ready(socket);
    socket.serverSend("START_SESSION", { approvedAudio: [] }, { opId: OP_ID, sessionId: SESSION_ID });
    await flushPromises();
    socket.serverSend("OUTPUT_BEGIN", { playbackId: PLAYBACK_ID }, { sessionId: SESSION_ID });
    socket.serverBinary(encodeOutputFrame(1, pcm16ToLittleEndianBytes(new Int16Array(2_400))));
    expect(audioContext?.sources).toHaveLength(1);
    vi.advanceTimersByTime(0);
    expect(handlers.onSpeaking).toHaveBeenCalledWith(true);
    const [mark] = socket.sentOfType("PLAYBACK_MARK");
    expect(mark).toMatchObject({ sessionId: SESSION_ID, payload: { playbackId: PLAYBACK_ID, status: "STARTED" } });
    socket.serverSend("SESSION_STATE", { view: "CLOSING" }, { sessionId: SESSION_ID });
    expect(handlers.onView).toHaveBeenCalledWith("CLOSING");
  });

  it("승인 음성 미리 받기가 실패하면 prefetch PARTIAL로 알린다", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 404 })));
    const { runtime, sockets } = await startWith();
    runtime.connect();
    ready(sockets[0]);
    const approvedAudio = [{ contentCode: "CLOSING", contentVersionId: CONTENT_VERSION_ID, audioUrl: "/media/approved/closing-v1.wav" }];
    sockets[0].serverSend("START_SESSION", { approvedAudio }, { opId: OP_ID, sessionId: SESSION_ID });
    await flushPromises();
    expect(sockets[0].sentOfType("DEVICE_READY")[0].payload).toMatchObject({ prefetch: "PARTIAL" });
  });
});

describe("startDeviceRuntime — 닫힘 · 정리", () => {
  it("서버가 닫으면 알리고, 다음 connect는 새 소켓을 연다(5초 재접속은 DeviceApp)", async () => {
    const { runtime, sockets, handlers } = await startWith();
    runtime.connect();
    ready(sockets[0]);
    sockets[0].serverClose(1013);
    expect(handlers.onClosed).toHaveBeenCalledWith(1013, false);
    runtime.connect();
    expect(sockets).toHaveLength(2);
  });

  it("「끄기」(shutdown true)는 1000 DEVICE_OFF로 닫고 마이크 · 화면 켜짐 유지 · AudioContext를 놓는다(AC6.2.3)", async () => {
    const wakeLock = wakeLockDouble();
    Object.defineProperty(navigator, "wakeLock", { configurable: true, value: { request: wakeLock.request } });
    const { runtime, sockets, handlers, tracks, audioContext } = await startWith();
    await flushPromises();
    expect(wakeLock.request).toHaveBeenCalledWith("screen");
    runtime.connect();
    ready(sockets[0]);
    runtime.shutdown(true);
    runtime.shutdown(true);
    expect(sockets[0].closeCalls).toEqual([{ code: 1000, reason: "DEVICE_OFF" }]);
    // 「끄기」는 닫기 1000뿐이고 따로 끝내기 신호를 보내지 않는다(AC6.2.11 · B16: 서버는 끊김 40초 경로).
    expect(sockets[0].sentEnvelopes().map((envelope) => envelope.type)).toEqual(["HELLO"]);
    expect(handlers.onClosed).not.toHaveBeenCalled();
    expect(tracks[0].stop).toHaveBeenCalled();
    expect(wakeLock.sentinels[0].release).toHaveBeenCalledTimes(1);
    expect(audioContext?.close).toHaveBeenCalledTimes(1);
    runtime.connect();
    expect(sockets).toHaveLength(1);
  });

  it("4001 · 4401 뒤 정리(shutdown false)는 소켓을 다시 닫지 않는다", async () => {
    const { runtime, sockets } = await startWith();
    runtime.connect();
    ready(sockets[0]);
    sockets[0].serverClose(4001);
    runtime.shutdown(false);
    expect(sockets[0].closeCalls).toEqual([]);
  });
});

describe("holdScreenWakeLock — 화면 켜짐 유지(BR9.5 · K5)", () => {
  it("지원하지 않으면 아무것도 하지 않는다", () => {
    expect(() => holdScreenWakeLock(undefined, document).release()).not.toThrow();
  });

  it("탭이 다시 보이면 브라우저가 푼 잠금을 다시 잡고, 놓은 뒤에는 잡지 않는다", async () => {
    const wakeLock = wakeLockDouble();
    const handle = holdScreenWakeLock({ request: wakeLock.request }, document);
    await flushPromises();
    expect(wakeLock.request).toHaveBeenCalledTimes(1);
    document.dispatchEvent(new Event("visibilitychange"));
    expect(wakeLock.request).toHaveBeenCalledTimes(1);
    wakeLock.sentinels[0].released = true;
    document.dispatchEvent(new Event("visibilitychange"));
    await flushPromises();
    expect(wakeLock.request).toHaveBeenCalledTimes(2);
    handle.release();
    handle.release();
    expect(wakeLock.sentinels[1].release).toHaveBeenCalledTimes(1);
    document.dispatchEvent(new Event("visibilitychange"));
    expect(wakeLock.request).toHaveBeenCalledTimes(2);
  });

  it("잡기 전에 놓으면 늦게 받은 잠금을 바로 놓고, 요청 거부는 기능을 막지 않는다", async () => {
    const wakeLock = wakeLockDouble();
    const handle = holdScreenWakeLock({ request: wakeLock.request }, document);
    handle.release();
    await flushPromises();
    expect(wakeLock.sentinels[0].release).toHaveBeenCalledTimes(1);
    const rejecting = {
      request: vi.fn<(type: "screen") => Promise<SentinelDouble>>(async () => {
        throw new Error("battery saver");
      }),
    };
    const rejected = holdScreenWakeLock(rejecting, document);
    await flushPromises();
    expect(() => rejected.release()).not.toThrow();
  });
});
