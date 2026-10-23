import { describe, expect, it, vi } from "vitest";
import { DeviceSession, type DeviceSessionDependencies, type SessionPlayer } from "@/device/ws/deviceSession";
import { decodeFrame, INPUT_FRAME_BYTES, INPUT_FRAME_SAMPLES } from "@/shared/protocol/frameCodec";
import type { ApprovedAudio, PrefetchFlag, ReadinessFlag, ServerMessage } from "@/shared/protocol/messages";
import { CONTENT_VERSION_ID, OP_ID, OTHER_SESSION_ID, PLAYBACK_ID, SESSION_ID, deferred } from "../../support/fixtures";

// 기기 쪽 세션 처리 시험 (U1 3.5 Step 41 · 계약 4.2 · BR6.10 · BR7.1 · BR7.6 · BR9.6 · R3-8)
// START_SESSION → 미리 받기 → DEVICE_READY, 0x01은 DEVICE_READY 뒤 ~ CLOSE_TRANSPORT 전만, CLOSE_TRANSPORT → VOICE_SESSION_CLOSED.

const AUDIO: ApprovedAudio[] = [{ contentCode: "CLOSING", contentVersionId: CONTENT_VERSION_ID, audioUrl: "/media/approved/closing-v1.wav" }];

function playerDouble(prefetch: PrefetchFlag | Promise<PrefetchFlag> = "OK") {
  return {
    prefetch: vi.fn(async (items: readonly ApprovedAudio[]) => {
      void items;
      return prefetch;
    }),
    beginStream: vi.fn(),
    appendStream: vi.fn(),
    endStream: vi.fn(),
    playApproved: vi.fn(async () => undefined),
    stopAll: vi.fn(),
    reset: vi.fn(),
  } satisfies SessionPlayer;
}

function setup(options: { mic?: ReadinessFlag; player?: ReadinessFlag; prefetch?: PrefetchFlag | Promise<PrefetchFlag> } = {}) {
  let clock = Date.UTC(2026, 9, 15, 1, 0, 0);
  const player = playerDouble(options.prefetch);
  const deps = {
    send: vi.fn<DeviceSessionDependencies["send"]>(),
    sendBinary: vi.fn<DeviceSessionDependencies["sendBinary"]>(),
    player,
    readiness: () => ({ mic: options.mic ?? "OK", player: options.player ?? "OK" }),
    onInputWindowChange: vi.fn<(open: boolean) => void>(),
    onView: vi.fn(),
    nowMs: () => {
      clock += 1;
      return clock;
    },
  };
  const session = new DeviceSession(deps);
  return { session, deps, player };
}

function start(sessionId = SESSION_ID): ServerMessage {
  return { type: "START_SESSION", opId: OP_ID, sessionId, approvedAudio: AUDIO };
}

const silentFrame = () => new Int16Array(INPUT_FRAME_SAMPLES);
const flushPromises = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

describe("DeviceSession — 시작과 입력 창", () => {
  it("START_SESSION → 승인 음성 미리 받기 → DEVICE_READY{mic, player, prefetch} → 입력 창 열림", async () => {
    const { session, deps, player } = setup({ prefetch: "PARTIAL" });
    session.handleMessage(start());
    session.handleInputFrame(silentFrame());
    expect(deps.sendBinary).not.toHaveBeenCalled();
    await flushPromises();
    expect(player.prefetch).toHaveBeenCalledWith(AUDIO);
    expect(deps.send).toHaveBeenCalledWith("DEVICE_READY", { mic: "OK", player: "OK", prefetch: "PARTIAL" }, SESSION_ID);
    expect(session.sessionId).toBe(SESSION_ID);
    expect(session.isInputOpen).toBe(true);
    expect(deps.onInputWindowChange).toHaveBeenCalledWith(true);
  });

  it("입력 창이 열린 뒤 프레임은 965B 0x01로 순번 1부터 보낸다(BR7.1)", async () => {
    const { session, deps } = setup();
    session.handleMessage(start());
    await flushPromises();
    session.handleInputFrame(silentFrame());
    session.handleInputFrame(silentFrame());
    const frames = deps.sendBinary.mock.calls.map(([frame]) => frame);
    expect(frames.map((frame) => frame.byteLength)).toEqual([INPUT_FRAME_BYTES, INPUT_FRAME_BYTES]);
    expect(frames.map((frame) => {
      const decoded = decodeFrame(frame);
      return decoded.ok ? decoded.frame.seq : -1;
    })).toEqual([1, 2]);
  });

  it("마이크를 쓸 수 없으면 DEVICE_READY에 UNAVAILABLE을 알리고 입력 창을 열지 않는다", async () => {
    const { session, deps } = setup({ mic: "UNAVAILABLE", player: "UNAVAILABLE" });
    session.handleMessage(start());
    await flushPromises();
    expect(deps.send).toHaveBeenCalledWith("DEVICE_READY", { mic: "UNAVAILABLE", player: "UNAVAILABLE", prefetch: "OK" }, SESSION_ID);
    session.handleInputFrame(silentFrame());
    expect(deps.sendBinary).not.toHaveBeenCalled();
  });

  it("같은 START_SESSION은 한 번만, 다른 세션이 오면 앞 세션의 소리 · 입력을 정리하고 새로 시작한다", async () => {
    const { session, deps, player } = setup();
    session.handleMessage(start());
    await flushPromises();
    session.handleMessage(start());
    await flushPromises();
    expect(player.prefetch).toHaveBeenCalledTimes(1);
    session.handleMessage(start(OTHER_SESSION_ID));
    expect(player.stopAll).toHaveBeenCalledWith("CLOSE_TRANSPORT");
    expect(deps.onInputWindowChange).toHaveBeenLastCalledWith(false);
    await flushPromises();
    expect(session.sessionId).toBe(OTHER_SESSION_ID);
    expect(deps.send).toHaveBeenLastCalledWith("DEVICE_READY", expect.anything(), OTHER_SESSION_ID);
  });

  it("미리 받는 사이 세션이 바뀌면 앞 세션의 DEVICE_READY를 보내지 않는다", async () => {
    const slow = deferred<PrefetchFlag>();
    const { session, deps } = setup({ prefetch: slow.promise });
    session.handleMessage(start());
    session.handleDisconnect();
    slow.resolve("OK");
    await flushPromises();
    expect(deps.send).not.toHaveBeenCalledWith("DEVICE_READY", expect.anything(), SESSION_ID);
  });
});

describe("DeviceSession — 서버 명령", () => {
  it("SESSION_STATE는 화면 view로, 모르는 view는 무시한다(BR9.6)", () => {
    const { session, deps } = setup();
    session.handleMessage({ type: "SESSION_STATE", sessionId: SESSION_ID, view: "CLOSING" });
    session.handleMessage({ type: "SESSION_STATE", sessionId: SESSION_ID, view: "UNKNOWN" });
    expect(deps.onView.mock.calls).toEqual([["CLOSING"]]);
  });

  it("지금 세션의 OUTPUT_BEGIN · PLAY_APPROVED_CONTENT만 재생기에 넘긴다(BR7.6)", async () => {
    const { session, player } = setup();
    session.handleMessage({ type: "OUTPUT_BEGIN", sessionId: SESSION_ID, playbackId: PLAYBACK_ID });
    session.handleOutputFrame({ kind: 0x02, seq: 1, payload: Uint8Array.of(1, 0) });
    expect(player.beginStream).not.toHaveBeenCalled();
    expect(player.appendStream).not.toHaveBeenCalled();
    session.handleMessage(start());
    await flushPromises();
    session.handleMessage({ type: "OUTPUT_BEGIN", sessionId: OTHER_SESSION_ID, playbackId: PLAYBACK_ID });
    session.handleMessage({ type: "OUTPUT_BEGIN", sessionId: SESSION_ID, playbackId: PLAYBACK_ID });
    session.handleOutputFrame({ kind: 0x02, seq: 1, payload: Uint8Array.of(1, 0) });
    session.handleMessage({ type: "OUTPUT_END", sessionId: SESSION_ID, playbackId: PLAYBACK_ID });
    const play = { opId: OP_ID, playbackId: PLAYBACK_ID, contentCode: "CLOSING", contentVersionId: CONTENT_VERSION_ID };
    session.handleMessage({ type: "PLAY_APPROVED_CONTENT", sessionId: OTHER_SESSION_ID, ...play });
    session.handleMessage({ type: "PLAY_APPROVED_CONTENT", sessionId: SESSION_ID, ...play });
    expect(player.beginStream).toHaveBeenCalledTimes(1);
    expect(player.appendStream).toHaveBeenCalledTimes(1);
    expect(player.endStream).toHaveBeenCalledWith(PLAYBACK_ID);
    expect(player.playApproved).toHaveBeenCalledTimes(1);
    expect(player.playApproved).toHaveBeenCalledWith(SESSION_ID, PLAYBACK_ID, "CLOSING", CONTENT_VERSION_ID);
  });

  it("STOP_OUTPUT은 재생을 멈추고(입력은 계속), ERROR는 화면 동작이 없다", async () => {
    const { session, deps, player } = setup();
    session.handleMessage(start());
    await flushPromises();
    session.handleMessage({ type: "STOP_OUTPUT", opId: OP_ID, sessionId: SESSION_ID, reason: "GUARDIAN_STOP" });
    session.handleMessage({ type: "ERROR", code: "UNKNOWN_TYPE", refSeq: 3 });
    expect(player.stopAll).toHaveBeenCalledWith("STOP_OUTPUT");
    session.handleInputFrame(silentFrame());
    expect(deps.sendBinary).toHaveBeenCalledTimes(1);
  });

  it("CLOSE_TRANSPORT는 입력을 먼저 닫고 재생을 정리한 뒤 VOICE_SESSION_CLOSED를 보낸다(BR6.10 · R3-8)", async () => {
    const { session, deps, player } = setup();
    session.handleMessage(start());
    await flushPromises();
    session.handleMessage({ type: "CLOSE_TRANSPORT", opId: OP_ID, sessionId: SESSION_ID, reason: "ENDED" });
    expect(player.stopAll).toHaveBeenCalledWith("CLOSE_TRANSPORT");
    const last = deps.send.mock.calls.at(-1);
    expect(last?.[0]).toBe("VOICE_SESSION_CLOSED");
    expect(last?.[2]).toBe(SESSION_ID);
    const { micClosedAt, outputStoppedAt } = last?.[1] as { micClosedAt: string; outputStoppedAt: string };
    expect(Date.parse(micClosedAt)).toBeLessThan(Date.parse(outputStoppedAt));
    expect(session.sessionId).toBeNull();
    session.handleInputFrame(silentFrame());
    expect(deps.sendBinary).not.toHaveBeenCalled();
  });

  it("세션이 없는데 온 CLOSE_TRANSPORT에도 확인을 보낸다", () => {
    const { session, deps } = setup();
    session.handleMessage({ type: "CLOSE_TRANSPORT", opId: OP_ID, sessionId: SESSION_ID, reason: "FAILED" });
    expect(deps.send).toHaveBeenCalledWith("VOICE_SESSION_CLOSED", expect.objectContaining({ micClosedAt: expect.any(String) }), SESSION_ID);
  });

  it("끊기면 입력 · 재생을 멈추고 세션을 잊으며, PLAYBACK_MARK는 그 세션으로 보낸다", async () => {
    const { session, deps, player } = setup();
    session.handleMessage(start());
    await flushPromises();
    session.handleDisconnect();
    expect(player.reset).toHaveBeenCalledTimes(1);
    expect(session.isInputOpen).toBe(false);
    expect(session.sessionId).toBeNull();
    const mark = {
      playbackId: PLAYBACK_ID,
      status: "STARTED" as const,
      playedRange: null,
      observedAt: "2026-10-15T01:00:00.000Z",
      stopCause: null,
      failureReason: null,
    };
    session.sendPlaybackMark(SESSION_ID, mark);
    expect(deps.send).not.toHaveBeenCalledWith("PLAYBACK_MARK", mark, SESSION_ID);
  });
});
