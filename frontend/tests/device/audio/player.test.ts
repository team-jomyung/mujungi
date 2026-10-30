import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AudioPlayer, createWebAudioOutput, fetchApprovedAudio } from "@/device/audio/player";
import type { ApprovedAudio, PlaybackMarkPayload } from "@/shared/protocol/messages";
import { pcm16ToLittleEndianBytes } from "@/shared/protocol/frameCodec";
import { CONTENT_VERSION_ID, PLAYBACK_ID, SESSION_ID } from "../../support/fixtures";
import { FakeAudioContext, FakeOutput, type FakeClip } from "../../support/fakeAudio";

// 아이 기기 재생기 시험 (U1 3.5 Step 41 · BR7.6 · BR6.10 · BR9.6 · AC6.2.9 · J31 · J73)
// OUTPUT_BEGIN 묶음의 0x02와 START_SESSION으로 미리 받은 승인 음성만 재생한다. 모르는 contentCode는 버리고(FAILED),
// 대기열이 20초 분량을 넘으면 QUEUE_OVERFLOW, 멈춤은 STOPPED, '말하는 중'은 실제로 소리가 나는 동안만이다.

const CLOSING: ApprovedAudio = { contentCode: "CLOSING", contentVersionId: CONTENT_VERSION_ID, audioUrl: "/media/approved/closing-v1.wav" };
const FILLER: ApprovedAudio = {
  contentCode: "WAITING_FILLER",
  contentVersionId: "00000000-0000-4000-8000-000000000041",
  audioUrl: "/media/approved/waiting-filler-v1.wav",
};
const OTHER_PLAYBACK = "00000000-0000-4000-8000-000000000081";

/** n초 분량의 24kHz PCM16 본문. */
function pcmSeconds(seconds: number): Uint8Array {
  return pcm16ToLittleEndianBytes(new Int16Array(Math.round(seconds * 24_000)));
}

function setup(options: { fetchAudio?: (url: string) => Promise<ArrayBuffer>; queueMaxMs?: number } = {}) {
  const output = new FakeOutput();
  const marks: Array<{ sessionId: string; mark: PlaybackMarkPayload }> = [];
  const speaking: boolean[] = [];
  const fetchAudio = vi.fn(options.fetchAudio ?? (async (url: string) => new ArrayBuffer(url.length)));
  const player = new AudioPlayer<FakeClip>({
    output,
    fetchAudio,
    queueMaxMs: options.queueMaxMs ?? 20_000,
    nowMs: () => Date.UTC(2026, 9, 15, 1, 0, 5),
    onMark: (sessionId, mark) => marks.push({ sessionId, mark }),
    onSpeakingChange: (value) => speaking.push(value),
  });
  const statuses = () => marks.map(({ mark }) => `${mark.status}${mark.failureReason ? `:${mark.failureReason}` : ""}${mark.stopCause ? `:${mark.stopCause}` : ""}`);
  return { output, player, marks, speaking, fetchAudio, statuses };
}

describe("AudioPlayer — 묶음 재생(OUTPUT_BEGIN · 0x02 · OUTPUT_END)", () => {
  it("묶음의 0x02를 이어 붙여 재생하고 STARTED · COMPLETED를 알린다", () => {
    const { output, player, marks, speaking } = setup();
    player.beginStream(SESSION_ID, PLAYBACK_ID);
    player.appendStream(pcmSeconds(0.5));
    player.appendStream(pcmSeconds(0.5));
    expect(output.scheduled.map((entry) => entry.whenSec)).toEqual([0, 0.5]);
    expect(player.isSpeaking).toBe(false);
    output.startAll();
    expect(player.isSpeaking).toBe(true);
    player.endStream(PLAYBACK_ID);
    output.endAll();
    expect(marks.map(({ mark }) => mark.status)).toEqual(["STARTED", "COMPLETED"]);
    expect(marks[0]).toEqual({
      sessionId: SESSION_ID,
      mark: {
        playbackId: PLAYBACK_ID,
        status: "STARTED",
        playedRange: null,
        observedAt: "2026-10-15T01:00:05.000Z",
        stopCause: null,
        failureReason: null,
      },
    });
    expect(speaking).toEqual([true, false]);
  });

  it("OUTPUT_BEGIN 없이 온 0x02 · OUTPUT_END 뒤 0x02 · 빈 본문은 버린다(BR7.6)", () => {
    const { output, player } = setup();
    player.appendStream(pcmSeconds(0.1));
    player.beginStream(SESSION_ID, PLAYBACK_ID);
    player.endStream(PLAYBACK_ID);
    player.appendStream(pcmSeconds(0.1));
    player.beginStream(SESSION_ID, OTHER_PLAYBACK);
    player.appendStream(new Uint8Array(1));
    expect(output.scheduled).toHaveLength(0);
  });

  it("새 OUTPUT_BEGIN은 앞 묶음을 닫고, 앞 묶음은 낸 조각이 끝나면 COMPLETED다", () => {
    const { output, player, statuses } = setup();
    player.beginStream(SESSION_ID, PLAYBACK_ID);
    player.appendStream(pcmSeconds(0.2));
    player.beginStream(SESSION_ID, OTHER_PLAYBACK);
    player.appendStream(pcmSeconds(0.2));
    expect(output.scheduled.map((entry) => entry.whenSec)).toEqual([0, 0.2]);
    output.scheduled[0].callbacks.onEnded();
    expect(statuses()).toEqual(["STARTED", "COMPLETED"]);
    player.beginStream(SESSION_ID, OTHER_PLAYBACK);
    expect(output.scheduled).toHaveLength(2);
  });

  it("조각 없이 끝난 묶음 · 모르는 id의 OUTPUT_END는 표식을 내지 않는다", () => {
    const { player, marks } = setup();
    player.beginStream(SESSION_ID, PLAYBACK_ID);
    player.endStream(PLAYBACK_ID);
    player.endStream(OTHER_PLAYBACK);
    expect(marks).toEqual([]);
  });

  it("대기 분량이 output-queue-max(20초)를 넘으면 그 재생을 QUEUE_OVERFLOW로 끝낸다(J31 · J73)", () => {
    const { output, player, statuses } = setup();
    player.beginStream(SESSION_ID, PLAYBACK_ID);
    player.appendStream(pcmSeconds(15));
    player.appendStream(pcmSeconds(5));
    expect(output.scheduled).toHaveLength(2);
    player.appendStream(pcmSeconds(0.1));
    expect(statuses()).toEqual(["FAILED:QUEUE_OVERFLOW"]);
    expect(output.scheduled.every((entry) => entry.stopped)).toBe(true);
    // 실패한 묶음의 다음 0x02는 버린다.
    player.appendStream(pcmSeconds(0.1));
    expect(output.scheduled).toHaveLength(2);
  });

  it("출력 장치를 쓸 수 없으면 AUDIO_DEVICE_ERROR로 알린다", () => {
    const { output, player, statuses } = setup();
    output.available = false;
    player.beginStream(SESSION_ID, PLAYBACK_ID);
    player.appendStream(pcmSeconds(0.1));
    expect(statuses()).toEqual(["FAILED:AUDIO_DEVICE_ERROR"]);
  });
});

describe("AudioPlayer — 승인 음성(PLAY_APPROVED_CONTENT)", () => {
  it("START_SESSION에서 모두 받으면 OK, 재생은 미리 받은 것을 다시 받지 않고 한 번 낸다(BR6.10)", async () => {
    const { output, player, fetchAudio, statuses } = setup();
    expect(await player.prefetch([CLOSING, FILLER])).toBe("OK");
    expect(fetchAudio.mock.calls.map(([url]) => url)).toEqual([CLOSING.audioUrl, FILLER.audioUrl]);
    await player.playApproved(SESSION_ID, PLAYBACK_ID, "CLOSING", CONTENT_VERSION_ID);
    await player.playApproved(SESSION_ID, PLAYBACK_ID, "CLOSING", CONTENT_VERSION_ID);
    expect(fetchAudio).toHaveBeenCalledTimes(2);
    expect(output.scheduled).toHaveLength(1);
    output.startAll();
    output.endAll();
    expect(statuses()).toEqual(["STARTED", "COMPLETED"]);
  });

  it("세션 목록에 없는 contentCode · 판은 재생하지 않고 FETCH_FAILED로 알린다(BR7.6)", async () => {
    const { output, player, fetchAudio, statuses } = setup();
    await player.prefetch([CLOSING]);
    await player.playApproved(SESSION_ID, PLAYBACK_ID, "SURPRISE", CONTENT_VERSION_ID);
    await player.playApproved(SESSION_ID, OTHER_PLAYBACK, "CLOSING", "00000000-0000-4000-8000-000000000049");
    expect(output.scheduled).toHaveLength(0);
    expect(fetchAudio).toHaveBeenCalledTimes(1);
    expect(statuses()).toEqual(["FAILED:FETCH_FAILED", "FAILED:FETCH_FAILED"]);
  });

  it("미리 받기에 하나라도 실패하면 PARTIAL이고, 재생 때 다시 받아 실패하면 FETCH_FAILED다", async () => {
    const failing = new Set([FILLER.audioUrl]);
    const { player, fetchAudio, statuses } = setup({
      fetchAudio: async (url) => {
        if (failing.has(url)) {
          throw new Error("HTTP 404");
        }
        return new ArrayBuffer(8);
      },
    });
    expect(await player.prefetch([CLOSING, FILLER])).toBe("PARTIAL");
    await player.playApproved(SESSION_ID, PLAYBACK_ID, FILLER.contentCode, FILLER.contentVersionId);
    expect(fetchAudio).toHaveBeenCalledTimes(3);
    expect(statuses()).toEqual(["FAILED:FETCH_FAILED"]);
  });

  it("해석할 수 없는 파일은 DECODE_ERROR, 재생 장치가 없으면 AUDIO_DEVICE_ERROR다", async () => {
    const decodeFailing = setup({ fetchAudio: async () => new ArrayBuffer(3) });
    decodeFailing.output.decodeDurations.set(3, "fail");
    await decodeFailing.player.prefetch([CLOSING]);
    await decodeFailing.player.playApproved(SESSION_ID, PLAYBACK_ID, "CLOSING", CONTENT_VERSION_ID);
    expect(decodeFailing.statuses()).toEqual(["FAILED:DECODE_ERROR"]);
    const noDevice = setup();
    await noDevice.player.prefetch([CLOSING]);
    noDevice.output.available = false;
    await noDevice.player.playApproved(SESSION_ID, PLAYBACK_ID, "CLOSING", CONTENT_VERSION_ID);
    expect(noDevice.statuses()).toEqual(["FAILED:AUDIO_DEVICE_ERROR"]);
  });

  it("승인 음성을 받는 사이 STOP_OUTPUT이 오면 받은 뒤에도 재생하지 않는다(BR6.10)", async () => {
    const { output, player, statuses } = setup();
    player.prefetch([CLOSING]).catch(() => undefined);
    const playing = player.playApproved(SESSION_ID, PLAYBACK_ID, "CLOSING", CONTENT_VERSION_ID);
    player.stopAll("STOP_OUTPUT");
    await playing;
    expect(output.scheduled).toHaveLength(0);
    expect(statuses()).toEqual(["STOPPED:STOP_OUTPUT"]);
  });

  it("한 재생이 실패해도 다른 재생의 대기열 끝은 지킨다", async () => {
    const { output, player, statuses } = setup();
    player.beginStream(SESSION_ID, PLAYBACK_ID);
    player.appendStream(pcmSeconds(2));
    await player.prefetch([CLOSING]);
    output.available = false;
    await player.playApproved(SESSION_ID, OTHER_PLAYBACK, "CLOSING", CONTENT_VERSION_ID);
    output.available = true;
    expect(statuses()).toEqual(["FAILED:AUDIO_DEVICE_ERROR"]);
    player.appendStream(pcmSeconds(1));
    expect(output.scheduled.map((entry) => entry.whenSec)).toEqual([0, 2]);
  });

  it("아주 짧은 조각은 끝 알림이 먼저 와도 STARTED → COMPLETED 순서로 알린다", async () => {
    const { output, player, statuses } = setup();
    await player.prefetch([FILLER]);
    await player.playApproved(SESSION_ID, PLAYBACK_ID, FILLER.contentCode, FILLER.contentVersionId);
    output.scheduled[0].callbacks.onEnded();
    output.scheduled[0].callbacks.onStarted();
    expect(statuses()).toEqual(["STARTED", "COMPLETED"]);
  });
});

describe("AudioPlayer — 멈춤(STOP_OUTPUT · CLOSE_TRANSPORT · 끊김)", () => {
  it("STOP_OUTPUT은 재생 · 대기 중인 소리를 모두 멈추고 STOPPED(stopCause)를 알린다", async () => {
    const { output, player, statuses, speaking } = setup();
    await player.prefetch([CLOSING]);
    player.beginStream(SESSION_ID, PLAYBACK_ID);
    player.appendStream(pcmSeconds(1));
    output.startAll();
    player.stopAll("STOP_OUTPUT");
    expect(statuses()).toEqual(["STARTED", "STOPPED:STOP_OUTPUT"]);
    expect(output.scheduled.every((entry) => entry.stopped)).toBe(true);
    expect(player.isSpeaking).toBe(false);
    expect(speaking).toEqual([true, false]);
    // 멈춘 뒤의 0x02는 새 OUTPUT_BEGIN 전까지 버린다.
    player.appendStream(pcmSeconds(0.1));
    expect(output.scheduled).toHaveLength(1);
    // 멈춘 뒤 대기열은 비어 새 소리는 지금 시각부터 이어진다.
    output.time = 3;
    await player.playApproved(SESSION_ID, OTHER_PLAYBACK, "CLOSING", CONTENT_VERSION_ID);
    expect(output.scheduled[1].whenSec).toBe(3);
  });

  it("끊김(reset)은 알릴 곳이 없으므로 표식 없이 멈춘다", () => {
    const { output, player, marks } = setup();
    player.beginStream(SESSION_ID, PLAYBACK_ID);
    player.appendStream(pcmSeconds(1));
    player.reset();
    expect(marks).toEqual([]);
    expect(output.scheduled[0].stopped).toBe(true);
    player.appendStream(pcmSeconds(1));
    expect(output.scheduled).toHaveLength(1);
  });

  it("멈춘 재생의 늦은 시작 · 끝 알림은 무시한다", () => {
    const { output, player, statuses } = setup();
    player.beginStream(SESSION_ID, PLAYBACK_ID);
    player.appendStream(pcmSeconds(1));
    const [entry] = output.scheduled;
    player.stopAll("CLOSE_TRANSPORT");
    entry.callbacks.onStarted();
    entry.callbacks.onEnded();
    expect(statuses()).toEqual(["STOPPED:CLOSE_TRANSPORT"]);
  });
});

describe("Web Audio 어댑터 · 승인 음성 받기", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("PCM을 버퍼로 만들고 예약 시각에 시작 알림, 끝나면 끝 알림을 한 번 낸다", () => {
    const context = new FakeAudioContext();
    const output = createWebAudioOutput(context.asAudioContext());
    expect(output.available).toBe(true);
    const clip = output.fromPcm(new Float32Array(2_400), 24_000);
    expect(clip.durationSec).toBeCloseTo(0.1);
    const onStarted = vi.fn();
    const onEnded = vi.fn();
    output.schedule(clip, 0.5, { onStarted, onEnded });
    const [source] = context.sources;
    expect(source.startedAt).toBe(0.5);
    expect(source.connect).toHaveBeenCalledWith(context.destination);
    vi.advanceTimersByTime(499);
    expect(onStarted).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(onStarted).toHaveBeenCalledTimes(1);
    source.onended?.();
    source.onended?.();
    expect(onEnded).toHaveBeenCalledTimes(1);
    context.state = "closed";
    expect(output.available).toBe(false);
    expect(output.currentTime()).toBe(0);
  });

  it("멈추면 소스를 멈추고 연결을 끊으며, 시작 전에 멈춘 소리는 시작 알림이 없다", () => {
    const context = new FakeAudioContext();
    const output = createWebAudioOutput(context.asAudioContext());
    const onStarted = vi.fn();
    const handle = output.schedule(output.fromPcm(new Float32Array(240), 24_000), 1, { onStarted, onEnded: vi.fn() });
    const [source] = context.sources;
    source.throwOnStop = true;
    handle.stop();
    handle.stop();
    expect(source.stopCount).toBe(1);
    expect(source.disconnect).toHaveBeenCalledTimes(1);
    expect(source.onended).toBeNull();
    vi.advanceTimersByTime(5_000);
    expect(onStarted).not.toHaveBeenCalled();
  });

  it("끝난 뒤의 멈춤은 아무것도 하지 않고, 해석은 원본을 복사해 넘긴다", async () => {
    const context = new FakeAudioContext();
    const output = createWebAudioOutput(context.asAudioContext());
    const handle = output.schedule(output.fromPcm(new Float32Array(24), 24_000), 0, { onStarted: vi.fn(), onEnded: vi.fn() });
    context.sources[0].onended?.();
    handle.stop();
    expect(context.sources[0].stopCount).toBe(0);
    const bytes = new ArrayBuffer(48);
    const decoded = await output.decode(bytes);
    expect(decoded.durationSec).toBeCloseTo(48 / 24_000);
    expect(context.decodeAudioData.mock.calls[0][0]).not.toBe(bytes);
  });

  it("승인 음성은 같은 origin에서 받고 2xx가 아니면 실패한다", async () => {
    const fetchMock = vi.fn(async (url: string) =>
      url.endsWith("closing-v1.wav") ? new Response(new Uint8Array(4), { status: 200 }) : new Response(null, { status: 404 }),
    );
    vi.stubGlobal("fetch", fetchMock);
    expect((await fetchApprovedAudio(CLOSING.audioUrl)).byteLength).toBe(4);
    expect(fetchMock).toHaveBeenCalledWith(CLOSING.audioUrl, { credentials: "same-origin", cache: "force-cache" });
    await expect(fetchApprovedAudio(FILLER.audioUrl)).rejects.toThrow(/404/);
  });
});

describe("AudioPlayer — 참여 확인 안내 녹음 보호", () => {
  const INTRO: ApprovedAudio = {
    contentCode: "INTRO_FREE_TALK",
    contentVersionId: "00000000-0000-4000-8000-000000000051",
    audioUrl: "/media/approved/intro-free-talk-v1.wav",
  };

  it("안내 녹음이 재생 중인 동안만 isPlayingNotice가 참이다", async () => {
    const { output, player } = setup();
    await player.prefetch([INTRO, CLOSING]);
    expect(player.isPlayingNotice).toBe(false);
    await player.playApproved(SESSION_ID, PLAYBACK_ID, INTRO.contentCode, INTRO.contentVersionId);
    expect(player.isPlayingNotice).toBe(true);
    output.startAll();
    expect(player.isPlayingNotice).toBe(true);
    output.endAll();
    expect(player.isPlayingNotice).toBe(false);
  });

  it("안내 녹음이 아닌 승인 음성과 스트림 재생은 보호하지 않는다", async () => {
    const { output, player } = setup();
    await player.prefetch([CLOSING]);
    await player.playApproved(SESSION_ID, PLAYBACK_ID, CLOSING.contentCode, CLOSING.contentVersionId);
    expect(player.isPlayingNotice).toBe(false);
    player.stopAll("STOP_OUTPUT");
    player.beginStream(SESSION_ID, OTHER_PLAYBACK);
    player.appendStream(pcmSeconds(0.2));
    output.startAll();
    expect(player.isPlayingNotice).toBe(false);
  });

  // FT-1030-b: 연습 첫 대본은 서버가 interruptible=false로 보내고, 그 묶음이 재생 · 대기 중인 동안만 끼어들기로 끊지 않는다.
  it("끊지 말라고 한 스트림이 재생 · 대기 중인 동안만 isPlayingUninterruptible이 참이다", () => {
    const { output, player } = setup();
    player.beginStream(SESSION_ID, PLAYBACK_ID);
    player.appendStream(pcmSeconds(0.2));
    output.startAll();
    expect(player.isPlayingUninterruptible).toBe(false);
    player.stopAll("STOP_OUTPUT");
    player.beginStream(SESSION_ID, OTHER_PLAYBACK, false);
    player.appendStream(pcmSeconds(0.2));
    expect(player.isPlayingUninterruptible).toBe(true);
    output.startAll();
    expect(player.isPlayingUninterruptible).toBe(true);
    player.endStream(OTHER_PLAYBACK);
    output.endAll();
    expect(player.isPlayingUninterruptible).toBe(false);
  });
});
