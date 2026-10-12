import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { startDeviceRuntime } from "@/device/runtime/deviceRuntime";
import { holdScreenWakeLock } from "@/device/power/wakeLock";
import { encodeOutputFrame, pcm16ToLittleEndianBytes } from "@/shared/protocol/frameCodec";
import type { SessionView } from "@/shared/protocol/messages";
import { CONTENT_VERSION_ID, OP_ID, PLAYBACK_ID, SESSION_ID, testTiming } from "../../u1/support/fixtures";
import { FakeAudioContext, FakeWorkletNode } from "../../u1/support/fakeAudio";
import { FakeSocket, helloOkPayload } from "../../u1/support/fakeSocket";

// 켜짐 실행부 U3 시험 (U3-R16 · U3-R17 · U3-J20 · AC6.2.6 · AC6.2.7 · AC6.2.12)
// 끊긴 뒤 HELLO.resume으로 세션을 잇고, 상태가 바뀌면 DEVICE_STATE를 보내며, 소리가 나는 동안 아이 음성이 쌓이면 재생을 멈춘다.

interface FakeTrack {
  readonly stop: ReturnType<typeof vi.fn>;
  readonly addEventListener: ReturnType<typeof vi.fn>;
  readonly removeEventListener: ReturnType<typeof vi.fn>;
  fire(type: string): void;
}

function fakeTrack(): FakeTrack {
  const listeners = new Map<string, Array<() => void>>();
  return {
    stop: vi.fn(),
    addEventListener: vi.fn((type: string, listener: () => void) => {
      listeners.set(type, [...(listeners.get(type) ?? []), listener]);
    }),
    removeEventListener: vi.fn(),
    fire(type: string) {
      for (const listener of listeners.get(type) ?? []) {
        listener();
      }
    },
  };
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
let visibility: DocumentVisibilityState = "visible";

beforeEach(() => {
  vi.useFakeTimers();
  worklets = [];
  visibility = "visible";
  Object.defineProperty(document, "visibilityState", { configurable: true, get: () => visibility });
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
  Reflect.deleteProperty(document, "visibilityState");
  Reflect.deleteProperty(navigator, "wakeLock");
});

async function startWith() {
  const sockets: FakeSocket[] = [];
  const handlers = events();
  const track = fakeTrack();
  const stream = { getTracks: () => [track], getAudioTracks: () => [track] } as unknown as MediaStream;
  const audioContext = new FakeAudioContext(24_000);
  const runtime = await startDeviceRuntime({
    audioContext: audioContext.asAudioContext(),
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
  return { runtime, sockets, handlers, track, audioContext };
}

function open(socket: FakeSocket, session?: Record<string, unknown>) {
  socket.open();
  socket.serverSend("HELLO_OK", session === undefined ? helloOkPayload() : { ...helloOkPayload(), session });
}

async function startSession(socket: FakeSocket) {
  socket.serverSend("START_SESSION", { approvedAudio: [] }, { opId: OP_ID, sessionId: SESSION_ID });
  await flushPromises();
}

describe("startDeviceRuntime — 이어가기(U3-R16)", () => {
  it("끊긴 뒤 다시 붙을 때 HELLO.resume에 세션을 싣고, 그대로 이으면 화면 전이 뒤 표시 상태를 맞추고 입력을 다시 보낸다", async () => {
    const { runtime, sockets, handlers } = await startWith();
    runtime.connect();
    open(sockets[0]);
    expect(sockets[0].sentOfType("HELLO")[0].payload.resume).toBeNull();
    await startSession(sockets[0]);
    expect(sockets[0].sentOfType("DEVICE_READY")).toHaveLength(1);
    sockets[0].serverClose(1006);
    expect(handlers.onClosed).toHaveBeenCalledWith(1006, false);

    runtime.connect();
    open(sockets[1], { sessionId: SESSION_ID, view: "LISTENING", resumed: true });

    expect(sockets[1].sentOfType("HELLO")[0].payload.resume).toEqual({ sessionId: SESSION_ID });
    expect(handlers.onReady).toHaveBeenCalledTimes(2);
    expect(handlers.onView).toHaveBeenCalledWith("LISTENING");
    expect(handlers.onView.mock.invocationCallOrder[0]).toBeGreaterThan(handlers.onReady.mock.invocationCallOrder[1]);
    expect(sockets[1].sentOfType("DEVICE_READY")).toHaveLength(0);
    worklets[0].emit(new Float32Array(480).fill(0.1));
    expect(sockets[1].sentBinary()).toHaveLength(1);
  });

  it("「끄기」로 정리하면 세션을 잊는다", async () => {
    const { runtime, sockets } = await startWith();
    runtime.connect();
    open(sockets[0]);
    await startSession(sockets[0]);
    runtime.shutdown(true);
    expect(sockets[0].closeCalls).toEqual([{ code: 1000, reason: "DEVICE_OFF" }]);
  });
});

describe("startDeviceRuntime — 끊김 뒤 명령 · 재생 끝 알림(U3-J7 · U3-R16)", () => {
  const PLAY_OP_ID = "00000000-0000-4000-8000-000000000073";
  const CLOSE_OP_ID = "00000000-0000-4000-8000-000000000074";

  it("마무리 문구 중 끊겼다 이으면 그 재생을 STOPPED(DISCONNECTED)로 알리고, 같은 opId로 다시 온 명령은 ACK만 한다", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(new ArrayBuffer(48))));
    const { runtime, sockets, audioContext } = await startWith();
    runtime.connect();
    open(sockets[0]);
    const approvedAudio = [{ contentCode: "CLOSING", contentVersionId: CONTENT_VERSION_ID, audioUrl: "/media/approved/closing-v1.wav" }];
    sockets[0].serverSend("START_SESSION", { approvedAudio }, { opId: OP_ID, sessionId: SESSION_ID });
    await flushPromises();
    const play = { playbackId: PLAYBACK_ID, contentCode: "CLOSING", contentVersionId: CONTENT_VERSION_ID };
    sockets[0].serverSend("PLAY_APPROVED_CONTENT", play, { opId: PLAY_OP_ID, sessionId: SESSION_ID });
    await flushPromises();
    expect(audioContext.sources).toHaveLength(1);
    sockets[0].serverClose(1006);

    runtime.connect();
    open(sockets[1], { sessionId: SESSION_ID, view: "CLOSING", resumed: true });
    expect(sockets[1].sentOfType("PLAYBACK_MARK").map((envelope) => envelope.payload)).toEqual([
      expect.objectContaining({ playbackId: PLAYBACK_ID, status: "STOPPED", stopCause: "DISCONNECTED" }),
    ]);
    sockets[1].serverSend("PLAY_APPROVED_CONTENT", play, { opId: PLAY_OP_ID, sessionId: SESSION_ID });
    await flushPromises();
    expect(sockets[1].sentOfType("ACK").map((envelope) => envelope.payload.ackOf)).toEqual([PLAY_OP_ID]);
    expect(audioContext.sources).toHaveLength(1);

    sockets[1].serverSend("CLOSE_TRANSPORT", { reason: "GUARDIAN_STOP" }, { opId: CLOSE_OP_ID, sessionId: SESSION_ID });
    expect(sockets[1].sentOfType("ACK").map((envelope) => envelope.payload.ackOf)).toEqual([PLAY_OP_ID, CLOSE_OP_ID]);
    expect(sockets[1].sentOfType("VOICE_SESSION_CLOSED")).toHaveLength(1);
  });

  it("다음 HEARTBEAT는 직전 왕복 시간을 싣는다(서버 불안정 판정 근거, U3-R1)", async () => {
    let now = Date.UTC(2026, 9, 15, 1, 0, 0);
    const sockets: FakeSocket[] = [];
    const runtime = await startDeviceRuntime({
      audioContext: new FakeAudioContext(24_000).asAudioContext(),
      stream: { getTracks: () => [], getAudioTracks: () => [] } as unknown as MediaStream,
      timing: testTiming(),
      socketUrl: "wss://localhost:8443/ws/device",
      nowMs: () => now,
      events: events(),
      createSocket: (url) => {
        const socket = new FakeSocket(url);
        sockets.push(socket);
        return socket;
      },
    });
    runtime.connect();
    open(sockets[0]);
    vi.advanceTimersByTime(5_000);
    const [first] = sockets[0].sentOfType("HEARTBEAT");
    expect(first.payload).toEqual({ hbSeq: 1, lastRttMs: null });
    now += 1_300;
    sockets[0].serverSend("HEARTBEAT_ACK", { hbSeq: 1, serverTime: "2026-10-15T01:00:01.300Z" });
    vi.advanceTimersByTime(5_000);
    expect(sockets[0].sentOfType("HEARTBEAT")[1].payload).toEqual({ hbSeq: 2, lastRttMs: 1_300 });
    runtime.shutdown(true);
  });
});

describe("startDeviceRuntime — DEVICE_STATE(U3-R17)", () => {
  it("모두 정상이면 연결 때 보내지 않고, 탭 숨김 · 마이크 트랙 끝 · 화면 켜짐 풀림을 바뀐 필드만 보낸다", async () => {
    const sentinelListeners: Array<() => void> = [];
    const sentinel = {
      released: false,
      release: vi.fn(async () => undefined),
      addEventListener: vi.fn((type: string, listener: () => void) => {
        if (type === "release") {
          sentinelListeners.push(listener);
        }
      }),
    };
    Object.defineProperty(navigator, "wakeLock", { configurable: true, value: { request: vi.fn(async () => sentinel) } });
    const { runtime, sockets, track } = await startWith();
    await flushPromises();
    runtime.connect();
    open(sockets[0]);
    expect(sockets[0].sentOfType("DEVICE_STATE")).toEqual([]);

    visibility = "hidden";
    document.dispatchEvent(new Event("visibilitychange"));
    track.fire("ended");
    sentinelListeners.forEach((listener) => listener());

    expect(sockets[0].sentOfType("DEVICE_STATE").map((envelope) => envelope.payload)).toEqual([
      { visibility: "HIDDEN" },
      { mic: "LOST" },
      { wakeLock: "RELEASED" },
    ]);
    expect(sockets[0].sentOfType("DEVICE_STATE").every((envelope) => envelope.sessionId === undefined)).toBe(true);

    // 다시 붙으면 정상과 다른 값만 한 번에 알린다
    sockets[0].serverClose(1006);
    runtime.connect();
    open(sockets[1]);
    expect(sockets[1].sentOfType("DEVICE_STATE").map((envelope) => envelope.payload)).toEqual([
      { mic: "LOST", visibility: "HIDDEN", wakeLock: "RELEASED" },
    ]);
    runtime.shutdown(true);
    expect(track.removeEventListener).toHaveBeenCalledWith("ended", expect.any(Function));
  });

  it("재생 장치 오류를 player ERROR로 알린다", async () => {
    const { runtime, sockets, audioContext } = await startWith();
    runtime.connect();
    open(sockets[0]);
    await startSession(sockets[0]);
    audioContext.state = "closed";
    sockets[0].serverSend("OUTPUT_BEGIN", { playbackId: PLAYBACK_ID }, { sessionId: SESSION_ID });
    sockets[0].serverBinary(encodeOutputFrame(1, pcm16ToLittleEndianBytes(new Int16Array(2_400))));
    expect(sockets[0].sentOfType("PLAYBACK_MARK")[0].payload).toMatchObject({ status: "FAILED", failureReason: "AUDIO_DEVICE_ERROR" });
    // 이 시험 환경에는 Wake Lock이 없어 연결 때 '켜짐 유지 안 됨'을 먼저 알린다(정상과 다른 값)
    expect(sockets[0].sentOfType("DEVICE_STATE").map((envelope) => envelope.payload)).toEqual([
      { wakeLock: "RELEASED" },
      { player: "ERROR" },
    ]);
  });
});

describe("startDeviceRuntime — 끼어들기(U3-J20)", () => {
  it("소리가 나는 동안 아이 음성이 500ms 쌓이면 재생을 멈추고 STOPPED(LOCAL_VOICE_ACTIVITY)를 보낸다", async () => {
    const { runtime, sockets, handlers } = await startWith();
    runtime.connect();
    open(sockets[0]);
    await startSession(sockets[0]);
    sockets[0].serverSend("OUTPUT_BEGIN", { playbackId: PLAYBACK_ID }, { sessionId: SESSION_ID });
    sockets[0].serverBinary(encodeOutputFrame(1, pcm16ToLittleEndianBytes(new Int16Array(24_000))));
    vi.advanceTimersByTime(0);
    expect(handlers.onSpeaking).toHaveBeenLastCalledWith(true);

    for (let index = 0; index < 24; index += 1) {
      worklets[0].emit(new Float32Array(480).fill(0.5));
    }
    expect(sockets[0].sentOfType("PLAYBACK_MARK").map((envelope) => envelope.payload.status)).toEqual(["STARTED"]);
    worklets[0].emit(new Float32Array(480).fill(0.5));

    const marks = sockets[0].sentOfType("PLAYBACK_MARK");
    expect(marks.map((envelope) => envelope.payload.status)).toEqual(["STARTED", "STOPPED"]);
    expect(marks[1].payload).toMatchObject({ playbackId: PLAYBACK_ID, stopCause: "LOCAL_VOICE_ACTIVITY", playedRange: { fromMs: 0, toMs: 0 } });
    expect(handlers.onSpeaking).toHaveBeenLastCalledWith(false);
    // 입력 프레임은 끼어들기와 상관없이 계속 서버로 간다(서버가 아이 말을 듣는다)
    expect(sockets[0].sentBinary()).toHaveLength(25);
  });

  it("소리가 나지 않을 때의 음성은 끼어들기가 아니다", async () => {
    const { runtime, sockets } = await startWith();
    runtime.connect();
    open(sockets[0]);
    await startSession(sockets[0]);
    for (let index = 0; index < 40; index += 1) {
      worklets[0].emit(new Float32Array(480).fill(0.5));
    }
    expect(sockets[0].sentOfType("PLAYBACK_MARK")).toEqual([]);
  });
});

describe("holdScreenWakeLock — 잡힘 · 풀림 알림(U3-R17)", () => {
  it("잡히면 true, 브라우저가 풀면 false를 알린다", async () => {
    const listeners: Array<() => void> = [];
    const sentinel = {
      released: false,
      release: vi.fn(async () => undefined),
      addEventListener: (type: "release", listener: () => void) => {
        void type;
        listeners.push(listener);
      },
    };
    const onChange = vi.fn();
    const handle = holdScreenWakeLock({ request: vi.fn(async () => sentinel) }, document, onChange);
    await flushPromises();
    expect(onChange).toHaveBeenCalledWith(true);
    listeners.forEach((listener) => listener());
    expect(onChange).toHaveBeenLastCalledWith(false);
    handle.release();
  });
});
