import { describe, expect, it, vi } from "vitest";
import { DeviceSession, type DeviceSessionDependencies, type SessionPlayer } from "@/device/ws/deviceSession";
import { INPUT_FRAME_SAMPLES } from "@/shared/protocol/frameCodec";
import type { ApprovedAudio, PrefetchFlag, ReadinessFlag, ServerMessage } from "@/shared/protocol/messages";
import { CONTENT_VERSION_ID, OP_ID, OTHER_SESSION_ID, PLAYBACK_ID, SESSION_ID, deferred } from "../../u1/support/fixtures";

// 기기 세션 이어가기 · 명령 재전송 시험 (U3-R16 · U3-J4 · U3-J5 · U3-J7)
// 끊기면 세션 id를 기억해 HELLO.resume에 싣고, HELLO_OK.session에 따라 잇거나 잊는다. 같은 opId로 다시 온 명령은 한 번만 한다.

const AUDIO: ApprovedAudio[] = [{ contentCode: "CLOSING", contentVersionId: CONTENT_VERSION_ID, audioUrl: "/media/approved/closing-v1.wav" }];
const SECOND_OP_ID = "00000000-0000-4000-8000-000000000071";

function setup(options: { mic?: ReadinessFlag; prefetch?: PrefetchFlag | Promise<PrefetchFlag> } = {}) {
  const player = {
    prefetch: vi.fn(async (items: readonly ApprovedAudio[]) => {
      void items;
      return options.prefetch ?? "OK";
    }),
    beginStream: vi.fn(),
    appendStream: vi.fn(),
    endStream: vi.fn(),
    playApproved: vi.fn(async () => undefined),
    stopAll: vi.fn(),
    reset: vi.fn(),
  } satisfies SessionPlayer;
  const deps = {
    send: vi.fn<DeviceSessionDependencies["send"]>(),
    sendBinary: vi.fn<DeviceSessionDependencies["sendBinary"]>(),
    player,
    readiness: () => ({ mic: options.mic ?? "OK", player: "OK" as const }),
    onInputWindowChange: vi.fn<(open: boolean) => void>(),
    onView: vi.fn(),
    nowMs: () => Date.UTC(2026, 9, 15, 1, 0, 0),
  };
  return { session: new DeviceSession(deps), deps, player };
}

function start(sessionId = SESSION_ID, opId = OP_ID): ServerMessage {
  return { type: "START_SESSION", opId, sessionId, approvedAudio: AUDIO };
}

const frame = () => new Int16Array(INPUT_FRAME_SAMPLES);
const flushPromises = () => new Promise<void>((resolve) => setTimeout(resolve, 0));
const readyCount = (send: ReturnType<typeof vi.fn>) => send.mock.calls.filter(([type]) => type === "DEVICE_READY").length;

describe("DeviceSession — 이어가기(U3-R16)", () => {
  it("끊기면 입력 · 재생을 멈추되 세션 id를 기억하고, 같은 세션을 그대로 이으면 입력 창을 다시 열고 표시 상태를 맞춘다", async () => {
    const { session, deps, player } = setup();
    session.handleMessage(start());
    await flushPromises();
    expect(session.isInputOpen).toBe(true);

    session.handleDisconnect();
    expect(session.isInputOpen).toBe(false);
    expect(session.sessionId).toBeNull();
    expect(session.resumeSessionId).toBe(SESSION_ID);
    expect(player.reset).toHaveBeenCalledTimes(1);
    session.handleInputFrame(frame());
    expect(deps.sendBinary).not.toHaveBeenCalled();

    session.handleReady({ sessionId: SESSION_ID, view: "LISTENING", resumed: true });
    expect(session.sessionId).toBe(SESSION_ID);
    expect(session.isInputOpen).toBe(true);
    expect(deps.onView).toHaveBeenCalledWith("LISTENING");
    expect(readyCount(deps.send)).toBe(1);
    session.handleInputFrame(frame());
    expect(deps.sendBinary).toHaveBeenCalledTimes(1);
    expect(session.resumeSessionId).toBe(SESSION_ID);
  });

  it("미리 받는 사이 끊겼다가 이으면 그때 DEVICE_READY를 한 번 보낸다", async () => {
    const slow = deferred<PrefetchFlag>();
    const { session, deps } = setup({ prefetch: slow.promise });
    session.handleMessage(start());
    session.handleDisconnect();
    slow.resolve("PARTIAL");
    await flushPromises();
    expect(readyCount(deps.send)).toBe(0);

    session.handleReady({ sessionId: SESSION_ID, view: "UNKNOWN", resumed: true });
    expect(deps.send).toHaveBeenCalledWith("DEVICE_READY", { mic: "OK", player: "OK", prefetch: "PARTIAL" }, SESSION_ID);
    expect(deps.onView).not.toHaveBeenCalled();
    session.handleReady({ sessionId: SESSION_ID, view: "LISTENING", resumed: true });
    expect(readyCount(deps.send)).toBe(1);
  });

  it("아직 미리 받는 중에 이으면 끝났을 때 DEVICE_READY를 보낸다", async () => {
    const slow = deferred<PrefetchFlag>();
    const { session, deps } = setup({ prefetch: slow.promise });
    session.handleMessage(start());
    session.handleDisconnect();
    session.handleReady({ sessionId: SESSION_ID, view: "STANDBY", resumed: true });
    expect(readyCount(deps.send)).toBe(0);
    slow.resolve("OK");
    await flushPromises();
    expect(readyCount(deps.send)).toBe(1);
    expect(session.isInputOpen).toBe(true);
  });

  it("서버가 세션을 모르는 기기로 보거나(resumed false) 세션이 없으면 끊긴 세션을 잊고 다시 온 START_SESSION을 처음부터 한다", async () => {
    const { session, deps, player } = setup();
    session.handleMessage(start());
    await flushPromises();
    session.handleDisconnect();
    session.handleReady({ sessionId: SESSION_ID, view: "LISTENING", resumed: false });
    expect(session.sessionId).toBeNull();
    expect(session.resumeSessionId).toBeNull();
    expect(deps.onView).not.toHaveBeenCalled();
    // 같은 opId의 START_SESSION(U3-J5)을 다시 실행한다
    session.handleMessage(start());
    await flushPromises();
    expect(player.prefetch).toHaveBeenCalledTimes(2);
    expect(readyCount(deps.send)).toBe(2);
    expect(session.sessionId).toBe(SESSION_ID);

    session.handleDisconnect();
    session.handleReady(undefined);
    expect(session.resumeSessionId).toBeNull();
    session.handleDisconnect();
    session.handleReady({ sessionId: OTHER_SESSION_ID, view: "LISTENING", resumed: true });
    expect(session.sessionId).toBeNull();
  });

  it("끊김으로 멈춘 그 세션의 재생은 이어간 뒤 STOPPED(DISCONNECTED)로 알리고, 잇지 못하면 알리지 않는다", async () => {
    const { session, deps, player } = setup();
    session.handleMessage(start());
    await flushPromises();
    player.reset.mockReturnValueOnce([
      { sessionId: SESSION_ID, playbackId: PLAYBACK_ID, playedRange: { fromMs: 0, toMs: 1_200 } },
      { sessionId: OTHER_SESSION_ID, playbackId: "00000000-0000-4000-8000-000000000082", playedRange: null },
    ]);
    session.handleDisconnect();
    session.handleReady({ sessionId: SESSION_ID, view: "CLOSING", resumed: true });

    const marks = deps.send.mock.calls.filter(([type]) => type === "PLAYBACK_MARK");
    expect(marks).toEqual([
      [
        "PLAYBACK_MARK",
        {
          playbackId: PLAYBACK_ID,
          status: "STOPPED",
          playedRange: { fromMs: 0, toMs: 1_200 },
          observedAt: "2026-10-15T01:00:00.000Z",
          stopCause: "DISCONNECTED",
          failureReason: null,
        },
        SESSION_ID,
      ],
    ]);

    player.reset.mockReturnValueOnce([{ sessionId: SESSION_ID, playbackId: PLAYBACK_ID, playedRange: null }]);
    session.handleDisconnect();
    session.handleReady({ sessionId: SESSION_ID, view: "CLOSING", resumed: false });
    expect(deps.send.mock.calls.filter(([type]) => type === "PLAYBACK_MARK")).toHaveLength(1);
  });

  it("끊긴 세션이 없으면 HELLO_OK.session은 아무것도 하지 않는다", () => {
    const { session, deps } = setup();
    session.handleReady({ sessionId: SESSION_ID, view: "LISTENING", resumed: true });
    expect(session.sessionId).toBeNull();
    expect(deps.onView).not.toHaveBeenCalled();
  });

  it("마이크를 쓸 수 없으면 이어도 입력 창을 열지 않는다", async () => {
    const { session, deps } = setup({ mic: "UNAVAILABLE" });
    session.handleMessage(start());
    await flushPromises();
    session.handleDisconnect();
    session.handleReady({ sessionId: SESSION_ID, view: "LISTENING", resumed: true });
    expect(session.isInputOpen).toBe(false);
    expect(deps.onInputWindowChange).not.toHaveBeenCalledWith(true);
  });

  it("「끄기」로 잊으면 다음 HELLO에 resume을 싣지 않는다", async () => {
    const { session } = setup();
    session.handleMessage(start());
    await flushPromises();
    session.forget();
    expect(session.resumeSessionId).toBeNull();
    session.handleReady({ sessionId: SESSION_ID, view: "LISTENING", resumed: true });
    expect(session.sessionId).toBeNull();
  });
});

describe("DeviceSession — 같은 opId로 다시 온 명령(U3-J7)", () => {
  it("이어간 뒤 같은 명령 · 같은 opId의 마무리 문구는 한 번만 재생하고, 새 opId는 그대로 한다", async () => {
    const { session, player } = setup();
    session.handleMessage(start());
    await flushPromises();
    const play: ServerMessage = {
      type: "PLAY_APPROVED_CONTENT",
      opId: SECOND_OP_ID,
      sessionId: SESSION_ID,
      playbackId: PLAYBACK_ID,
      contentCode: "CLOSING",
      contentVersionId: CONTENT_VERSION_ID,
    };
    session.handleMessage(play);
    session.handleDisconnect();
    session.handleReady({ sessionId: SESSION_ID, view: "CLOSING", resumed: true });
    session.handleMessage(play);
    expect(player.playApproved).toHaveBeenCalledTimes(1);
    session.handleMessage({ ...play, opId: "00000000-0000-4000-8000-000000000072" });
    expect(player.playApproved).toHaveBeenCalledTimes(2);
  });

  it("같은 opId라도 세션이 다르면 다른 명령이다", async () => {
    const { session, player } = setup();
    session.handleMessage(start(SESSION_ID));
    await flushPromises();
    session.handleMessage({ type: "STOP_OUTPUT", opId: SECOND_OP_ID, sessionId: SESSION_ID, reason: "GUARDIAN_STOP" });
    session.handleMessage({ type: "STOP_OUTPUT", opId: SECOND_OP_ID, sessionId: SESSION_ID, reason: "GUARDIAN_STOP" });
    session.handleMessage({ type: "STOP_OUTPUT", opId: SECOND_OP_ID, sessionId: OTHER_SESSION_ID, reason: "GUARDIAN_STOP" });
    expect(player.stopAll).toHaveBeenCalledTimes(2);
  });

  it("기억하는 opId는 최근 64개까지다", async () => {
    const { session, player } = setup();
    session.handleMessage(start());
    await flushPromises();
    const stop = (index: number): ServerMessage => ({
      type: "STOP_OUTPUT",
      opId: `00000000-0000-4000-8000-${String(index).padStart(12, "0")}`,
      sessionId: SESSION_ID,
      reason: "GUARDIAN_STOP",
    });
    for (let index = 100; index < 166; index += 1) {
      session.handleMessage(stop(index));
    }
    expect(player.stopAll).toHaveBeenCalledTimes(66);
    session.handleMessage(stop(100));
    expect(player.stopAll).toHaveBeenCalledTimes(67);
    session.handleMessage(stop(165));
    expect(player.stopAll).toHaveBeenCalledTimes(67);
  });
});

describe("DeviceSession — 다른 세션의 대기 표시(3차 리뷰 4)", () => {
  it("지금 세션이 아닌 세션의 STANDBY는 무시하고, 지금 세션 · 세션이 없을 때의 STANDBY는 따른다", async () => {
    const { session, deps } = setup();
    session.handleMessage(start());
    await flushPromises();
    deps.onView.mockClear();

    // 끝난 옛 세션을 대기로 돌려보내는 뒤늦은 명령이 새 세션 표시를 덮지 않는다
    session.handleMessage({ type: "SESSION_STATE", sessionId: OTHER_SESSION_ID, view: "STANDBY" });
    expect(deps.onView).not.toHaveBeenCalled();
    expect(session.sessionId).toBe(SESSION_ID);

    session.handleMessage({ type: "SESSION_STATE", sessionId: SESSION_ID, view: "LISTENING" });
    expect(deps.onView).toHaveBeenLastCalledWith("LISTENING");
    session.handleMessage({ type: "SESSION_STATE", sessionId: SESSION_ID, view: "STANDBY" });
    expect(deps.onView).toHaveBeenLastCalledWith("STANDBY");

    // 세션을 닫은 뒤에는 어느 세션의 STANDBY든 따른다(대기로 돌려보내기의 원래 뜻)
    session.handleMessage({ type: "CLOSE_TRANSPORT", opId: SECOND_OP_ID, sessionId: SESSION_ID, reason: "FAILED" });
    expect(session.sessionId).toBeNull();
    session.handleMessage({ type: "SESSION_STATE", sessionId: OTHER_SESSION_ID, view: "STANDBY" });
    expect(deps.onView).toHaveBeenLastCalledWith("STANDBY");
    expect(deps.onView).toHaveBeenCalledTimes(3);
  });
});
