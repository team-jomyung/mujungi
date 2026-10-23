import { describe, expect, it, vi } from "vitest";
import { AudioPlayer } from "@/device/audio/player";
import { FakeOutput } from "../../support/fakeAudio";
import { PLAYBACK_ID, SESSION_ID } from "../../support/fixtures";
import type { PlaybackMarkPayload } from "@/shared/protocol/messages";
const pcm = new Uint8Array(48000);
function setup() {
  const output = new FakeOutput(); const marks: PlaybackMarkPayload[] = [];
  const player = new AudioPlayer({ output, fetchAudio: async () => new ArrayBuffer(8), queueMaxMs: 2000, nowMs: () => 0, onMark: (_, mark) => marks.push(mark), onSpeakingChange: vi.fn() });
  player.beginStream(SESSION_ID, PLAYBACK_ID); player.appendStream(pcm); player.endStream(PLAYBACK_ID);
  return { player, output, marks, statuses: () => marks.map(m => m.status) };
}
describe("재생 수명과 늦은 오디오 콜백", () => {
  it("예약만으로 완료하지 않고 실제 정상 종료를 한 번 보고한다", () => {
    const h = setup(); expect(h.statuses()).toEqual([]); h.output.startAll(); expect(h.statuses()).toEqual(["STARTED"]);
    h.output.endAll(); h.output.scheduled[0].callbacks.onEnded(); expect(h.statuses()).toEqual(["STARTED", "COMPLETED"]);
  });
  it("STOP_OUTPUT 후 늦은 시작/끝은 STOPPED를 완료로 바꾸지 않는다", () => {
    const h = setup(); h.output.startAll(); h.player.stopAll("STOP_OUTPUT"); const cb = h.output.scheduled[0].callbacks; cb.onStarted(); cb.onEnded();
    expect(h.statuses()).toEqual(["STARTED", "STOPPED"]); expect(h.marks.at(-1)?.stopCause).toBe("STOP_OUTPUT");
  });
  it("끄기/CLOSE 중 stop이 종료 콜백을 동기로 발생시켜도 완료0이다", () => {
    const h = setup(); const entry = h.output.scheduled[0];
    const original = h.output.schedule.bind(h.output);
    // 기존 핸들은 같은 entry를 참조하므로 stop 구현에 재진입을 주입한다.
    h.player.reset();
    h.output.schedule = (clip, when, callbacks) => { const scheduled = original(clip, when, callbacks); return { stop: () => { callbacks.onEnded(); scheduled.stop(); } }; };
    h.player.beginStream(SESSION_ID, "new"); h.player.appendStream(pcm); h.player.endStream("new"); h.player.stopAll("CLOSE_TRANSPORT"); entry.callbacks.onEnded();
    expect(h.statuses()).toEqual(["STOPPED"]);
  });
  it("실패 정리의 동기/늦은 콜백도 FAILED만 유지한다", () => {
    const h = setup(); h.player.reset(); const original = h.output.schedule.bind(h.output);
    h.output.schedule = (clip, when, callbacks) => { original(clip, when, callbacks); return { stop: () => callbacks.onEnded() }; };
    h.player.beginStream(SESSION_ID, "failure"); h.player.appendStream(pcm); h.player.appendStream(pcm); h.player.appendStream(pcm);
    h.player.endStream("failure"); for (const e of h.output.scheduled) e.callbacks.onEnded();
    expect(h.statuses()).toEqual(["FAILED"]); expect(h.marks[0].failureReason).toBe("QUEUE_OVERFLOW");
  });
  it("끊김 reset 뒤 옛 콜백은 표식0이며 새 재생만 정상 완료한다", () => {
    const h = setup(); const old = h.output.scheduled[0].callbacks; h.player.reset();
    h.player.beginStream(SESSION_ID, "reconnect"); h.player.appendStream(pcm); h.player.endStream("reconnect"); old.onStarted(); old.onEnded(); expect(h.statuses()).toEqual([]);
    h.output.scheduled[1].callbacks.onStarted(); h.output.scheduled[1].callbacks.onEnded(); expect(h.statuses()).toEqual(["STARTED", "COMPLETED"]); expect(h.marks.every(m => m.playbackId === "reconnect")).toBe(true);
  });
  it("시작 전 즉시 끄기·중복 중단은 STOPPED 한 번이며 빈 큐 완료0이다", () => {
    const h = setup(); h.player.stopAll("CLOSE_TRANSPORT"); h.player.stopAll("CLOSE_TRANSPORT"); h.player.endStream(PLAYBACK_ID); h.output.scheduled[0].callbacks.onEnded();
    expect(h.statuses()).toEqual(["STOPPED"]); expect(h.player.isSpeaking).toBe(false);
    h.player.beginStream(SESSION_ID, "empty"); h.player.endStream("empty"); expect(h.statuses()).toEqual(["STOPPED"]);
  });
});
