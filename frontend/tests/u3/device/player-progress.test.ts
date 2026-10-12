import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AudioPlayer } from "@/device/audio/player";
import type { ApprovedAudio, PlaybackMarkPayload } from "@/shared/protocol/messages";
import { pcm16ToLittleEndianBytes } from "@/shared/protocol/frameCodec";
import { CONTENT_VERSION_ID, PLAYBACK_ID, SESSION_ID } from "../../u1/support/fixtures";
import { FakeOutput, type FakeClip } from "../../u1/support/fakeAudio";

// 재생 진행 표식 시험 (U3-J10 · 계약 PLAYBACK_MARK PROGRESS · playback-mark-interval 1초)
// 소리가 나는 동안 1초마다 PROGRESS{playedRange}, 멈추면 STOPPED에 그때까지 재생한 구간을 싣는다.

const INTERVAL_MS = 1_000;
const AUDIO: ApprovedAudio = { contentCode: "WAITING_FILLER", contentVersionId: CONTENT_VERSION_ID, audioUrl: "/media/approved/filler.wav" };

function pcmSeconds(seconds: number): Uint8Array {
  return pcm16ToLittleEndianBytes(new Int16Array(Math.round(seconds * 24_000)));
}

function setup(progress: { readonly intervalMs?: number } = { intervalMs: INTERVAL_MS }) {
  const output = new FakeOutput();
  const marks: PlaybackMarkPayload[] = [];
  const player = new AudioPlayer<FakeClip>({
    output,
    fetchAudio: async (url: string) => new ArrayBuffer(url.length),
    queueMaxMs: 20_000,
    nowMs: () => Date.UTC(2026, 9, 15, 1, 0, 5),
    onMark: (_sessionId, mark) => marks.push(mark),
    onSpeakingChange: () => undefined,
    progressIntervalMs: progress.intervalMs,
  });
  return { output, player, marks };
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("AudioPlayer — PROGRESS · 재생 구간(U3-J10)", () => {
  it("소리가 나는 동안 1초마다 재생한 구간을 PROGRESS로 알리고 멈추면 STOPPED에 그 구간을 싣는다", () => {
    const { output, player, marks } = setup();
    player.beginStream(SESSION_ID, PLAYBACK_ID);
    player.appendStream(pcmSeconds(1));
    player.appendStream(pcmSeconds(1));
    vi.advanceTimersByTime(INTERVAL_MS);
    expect(marks).toEqual([]);

    output.startAll();
    output.time = 0.5;
    vi.advanceTimersByTime(INTERVAL_MS);
    output.time = 1.25;
    vi.advanceTimersByTime(INTERVAL_MS);
    output.time = 1.7;
    player.stopAll("LOCAL_VOICE_ACTIVITY");
    vi.advanceTimersByTime(INTERVAL_MS * 3);

    expect(marks.map((mark) => mark.status)).toEqual(["STARTED", "PROGRESS", "PROGRESS", "STOPPED"]);
    expect(marks[0].playedRange).toBeNull();
    expect(marks[1].playedRange).toEqual({ fromMs: 0, toMs: 500 });
    expect(marks[2].playedRange).toEqual({ fromMs: 0, toMs: 1_250 });
    expect(marks[3]).toMatchObject({ status: "STOPPED", stopCause: "LOCAL_VOICE_ACTIVITY", playedRange: { fromMs: 0, toMs: 1_700 } });
  });

  it("끝까지 재생하면 타이머가 멈추고 COMPLETED에는 구간을 싣지 않는다", () => {
    const { output, player, marks } = setup();
    player.beginStream(SESSION_ID, PLAYBACK_ID);
    player.appendStream(pcmSeconds(1));
    player.endStream(PLAYBACK_ID);
    output.startAll();
    output.time = 0.8;
    vi.advanceTimersByTime(INTERVAL_MS);
    output.time = 1;
    output.endAll();
    vi.advanceTimersByTime(INTERVAL_MS * 3);

    expect(marks.map((mark) => mark.status)).toEqual(["STARTED", "PROGRESS", "COMPLETED"]);
    expect(marks[1].playedRange).toEqual({ fromMs: 0, toMs: 800 });
    expect(marks[2].playedRange).toBeNull();
  });

  it("시작하기 전에 멈춘 재생은 구간이 없고, 승인 음성도 진행을 알린다", async () => {
    const { output, player, marks } = setup();
    player.beginStream(SESSION_ID, PLAYBACK_ID);
    player.appendStream(pcmSeconds(1));
    player.stopAll("STOP_OUTPUT");
    expect(marks).toEqual([expect.objectContaining({ status: "STOPPED", playedRange: null })]);

    await player.prefetch([AUDIO]);
    await player.playApproved(SESSION_ID, "00000000-0000-4000-8000-000000000081", "WAITING_FILLER", CONTENT_VERSION_ID);
    output.time = 0;
    output.startAll();
    output.time = 0.3;
    vi.advanceTimersByTime(INTERVAL_MS);
    expect(marks.at(-1)).toMatchObject({ status: "PROGRESS", playedRange: { fromMs: 0, toMs: 300 } });
  });

  it("주기가 없거나 0이면 PROGRESS를 보내지 않는다(U1 동작)", () => {
    for (const progress of [{}, { intervalMs: 0 }]) {
      const { output, player, marks } = setup(progress);
      player.beginStream(SESSION_ID, PLAYBACK_ID);
      player.appendStream(pcmSeconds(1));
      output.startAll();
      output.time = 0.5;
      vi.advanceTimersByTime(INTERVAL_MS * 2);
      expect(marks.map((mark) => mark.status)).toEqual(["STARTED"]);
      player.reset();
    }
  });
});
