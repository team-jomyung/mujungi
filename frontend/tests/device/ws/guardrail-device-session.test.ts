import { describe, expect, it, vi } from "vitest";
import { DeviceSession, type SessionPlayer } from "@/device/ws/deviceSession";
import { AudioPlayer } from "@/device/audio/player";
import { FakeOutput } from "../../support/fakeAudio";
import { CONTENT_VERSION_ID, OTHER_SESSION_ID, PLAYBACK_ID, SESSION_ID } from "../../support/fixtures";
import type { PlaybackMarkPayload, ServerMessage } from "@/shared/protocol/messages";
const start = (sessionId = SESSION_ID): ServerMessage => ({ type: "START_SESSION", opId: "start", sessionId, approvedAudio: [{ contentCode: "NOTICE", contentVersionId: CONTENT_VERSION_ID, audioUrl: "/media/approved/fake.wav" }] });
const play: ServerMessage = { type: "PLAY_APPROVED_CONTENT", sessionId: SESSION_ID, opId: "play", playbackId: PLAYBACK_ID, contentCode: "NOTICE", contentVersionId: CONTENT_VERSION_ID };
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };
function setup() {
  const output = new FakeOutput(); const send = vi.fn(); const input = vi.fn(); const view = vi.fn();
  const player = new AudioPlayer({ output, fetchAudio: async () => new ArrayBuffer(8), queueMaxMs: 20000, nowMs: () => 0, onMark: (id, mark) => session.sendPlaybackMark(id, mark), onSpeakingChange: vi.fn() });
  const session = new DeviceSession({ player, send, sendBinary: vi.fn(), readiness: () => ({ mic: "OK", player: "OK" }), onInputWindowChange: input, onView: view, nowMs: () => 0 });
  const marks = () => send.mock.calls.filter(c => c[0] === "PLAYBACK_MARK").map(c => (c[1] as PlaybackMarkPayload).status);
  return { session, player, output, send, input, view, marks };
}
describe("기기 현재 세션의 실제 확인 소비", () => {
  it("수신/프리패치/재생 예약은 완료가 아니며 실제 끝만 COMPLETED다", async () => {
    const h = setup(); h.session.handleMessage(start()); await flush(); h.session.handleMessage(play); await flush();
    expect(h.marks()).toEqual([]); h.output.startAll(); expect(h.marks()).toEqual(["STARTED"]);
    h.output.endAll(); expect(h.marks()).toEqual(["STARTED", "COMPLETED"]);
  });
  it("같은 명령/opId 재전송은 음원 실행과 완료를 반복하지 않는다", async () => {
    const h = setup(); h.session.handleMessage(start()); await flush(); h.session.handleMessage(play); h.session.handleMessage(play); await flush();
    expect(h.output.scheduled).toHaveLength(1); h.output.startAll(); h.output.endAll(); h.output.endAll(); expect(h.marks()).toEqual(["STARTED", "COMPLETED"]);
  });
  it("다른 세션의 STOP/END/CLOSE/상태가 현재 입력과 재생을 바꾸지 않는다", async () => {
    const h = setup(); h.session.handleMessage(start()); await flush(); h.session.handleMessage(play); await flush(); h.output.startAll();
    for (const message of [{ type: "STOP_OUTPUT", reason: "GUARDIAN_STOP", opId: "old-stop" }, { type: "OUTPUT_END", playbackId: PLAYBACK_ID }, { type: "CLOSE_TRANSPORT", opId: "old-close", reason: "ENDED" }, { type: "SESSION_STATE", view: "CLOSING" }]) h.session.handleMessage({ ...message, sessionId: OTHER_SESSION_ID } as ServerMessage);
    expect(h.player.isSpeaking).toBe(true); expect(h.session.isInputOpen).toBe(true); expect(h.view).not.toHaveBeenCalled(); expect(h.send.mock.calls.some(c => c[0] === "VOICE_SESSION_CLOSED")).toBe(false);
  });
  it("연결 해제 뒤 옛 콜백은 완료/현재 표시를 만들지 않고 재연결은 STOPPED다", async () => {
    const h = setup(); h.session.handleMessage(start()); await flush(); h.session.handleMessage(play); await flush(); h.output.startAll();
    const old = h.output.scheduled[0].callbacks; h.session.handleDisconnect(); old.onEnded();
    h.session.handleMessage({ type: "SESSION_STATE", sessionId: SESSION_ID, view: "CLOSING" });
    expect(h.view).not.toHaveBeenCalled(); expect(h.marks()).toEqual(["STARTED"]);
    h.session.handleReady({ sessionId: SESSION_ID, resumed: true, view: "LISTENING" }); old.onEnded(); expect(h.marks()).toEqual(["STARTED", "STOPPED"]);
  });
  it("새 세션으로 바뀐 뒤 옛 재생의 늦은 시작/끝은 새 세션을 바꾸지 않는다", async () => {
    const h = setup(); h.session.handleMessage(start()); await flush(); h.session.handleMessage(play); await flush(); const old = h.output.scheduled[0].callbacks;
    h.session.handleMessage(start(OTHER_SESSION_ID)); await flush(); old.onStarted(); old.onEnded();
    expect(h.session.sessionId).toBe(OTHER_SESSION_ID); expect(h.marks()).toEqual(["STOPPED"]);
  });
  it("현재 CLOSE는 입력·재생·대기열 정리 뒤에만 한 번 확인한다", async () => {
    const h = setup(); h.session.handleMessage(start()); await flush(); h.session.handleMessage(play); await flush();
    const stop = vi.spyOn(h.player, "stopAll"); const close: ServerMessage = { type: "CLOSE_TRANSPORT", opId: "close", sessionId: SESSION_ID, reason: "ENDED" };
    h.session.handleMessage(close); h.session.handleMessage(close);
    const closed = h.send.mock.calls.findIndex(c => c[0] === "VOICE_SESSION_CLOSED");
    expect(stop.mock.invocationCallOrder[0]).toBeLessThan(h.send.mock.invocationCallOrder[closed]);
    expect(h.input.mock.invocationCallOrder.at(-1)).toBeLessThan(stop.mock.invocationCallOrder[0]);
    expect(h.session.isInputOpen).toBe(false); expect(h.output.scheduled.every(e => e.stopped)).toBe(true);
    expect(h.send.mock.calls.filter(c => c[0] === "VOICE_SESSION_CLOSED")).toHaveLength(1);
    const _contract: SessionPlayer = h.player; expect(_contract).toBe(h.player);
  });
});
