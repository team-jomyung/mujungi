import { AUDIO_SAMPLE_RATE, littleEndianPcm16ToFloat32 } from "@/shared/protocol/frameCodec";
import { toIsoInstant } from "@/shared/protocol/wsEnvelope";
import type {
  ApprovedAudio,
  PlaybackFailureReason,
  PlaybackMarkPayload,
  PlaybackStatus,
  PlaybackStopCause,
  PrefetchFlag,
} from "@/shared/protocol/messages";

// 아이 기기 재생기 (U1 3.5 Step 38 · BR7.6 · BR6.10 · BR9.6 · AC6.2.9 · J31 · J73)
//
// - 재생 대상은 ① PLAY_APPROVED_CONTENT가 가리키는 승인 음성(START_SESSION.approvedAudio로 미리 받은 파일)과
//   ② OUTPUT_BEGIN으로 시작된 묶음의 0x02 프레임뿐이다. OUTPUT_BEGIN 없이 온 0x02와 모르는 contentCode는 버린다(BR7.6).
// - 재생은 한 줄 시간표에 이어 붙인다. 대기 중인 분량이 output-queue-max(20초 분량)를 넘으면 그 재생을 FAILED(QUEUE_OVERFLOW)로 끝낸다.
// - 실제로 소리가 나기 시작하면 STARTED, 끝까지 나면 COMPLETED, STOP_OUTPUT · CLOSE_TRANSPORT로 멈추면 STOPPED,
//   받기 · 해석 · 장치 오류 · 대기열 초과는 FAILED를 PLAYBACK_MARK로 알린다. observedAt은 기기 시계다(OD-8).
// - U3(U3-J10): 소리가 나는 동안 progressIntervalMs마다 PROGRESS{playedRange}를 보내고, STOPPED에도 그때까지 재생한 구간을 싣는다
//   (끼어들기 · 멈춤 시점의 재생 위치 근거). progressIntervalMs가 없으면 PROGRESS를 보내지 않는다.
// - '말하는 중'은 승인된 소리가 실제로 나는 동안만이다(BR9.6).
// Web Audio 의존은 AudioOutput 뒤로 숨겨 재생 규칙을 jsdom에서 시험할 수 있게 한다.

/** 재생할 수 있는 소리 조각(출력 어댑터가 만든다). */
export interface AudioClip {
  readonly durationSec: number;
}

export interface ScheduledPlayback {
  stop(): void;
}

export interface PlaybackCallbacks {
  /** 실제 재생이 시작될 때. */
  readonly onStarted: () => void;
  /** 끝까지 재생됐을 때(stop으로 멈춘 경우는 부르지 않는다). */
  readonly onEnded: () => void;
}

/** 소리 출력 장치(기본은 Web Audio). */
export interface AudioOutput<C extends AudioClip> {
  readonly available: boolean;
  /** 출력 장치 시계(초, 단조). */
  currentTime(): number;
  fromPcm(samples: Float32Array<ArrayBuffer>, sampleRate: number): C;
  decode(bytes: ArrayBuffer): Promise<C>;
  schedule(clip: C, whenSec: number, callbacks: PlaybackCallbacks): ScheduledPlayback;
}

export interface PlayerOptions<C extends AudioClip> {
  readonly output: AudioOutput<C>;
  /** 승인 음성 받기(같은 origin 상대 경로). 실패하면 예외. */
  readonly fetchAudio: (url: string) => Promise<ArrayBuffer>;
  /** 재생 대기열 상한(밀리초 분량, mujoong.timing.device.output-queue-max). */
  readonly queueMaxMs: number;
  /** 기기 벽시계(observedAt). */
  readonly nowMs: () => number;
  readonly onMark: (sessionId: string, mark: PlaybackMarkPayload) => void;
  readonly onSpeakingChange: (speaking: boolean) => void;
  /** PROGRESS 주기(mujoong.timing.device.playback-mark-interval, U3-J10). 없거나 0이면 보내지 않는다. */
  readonly progressIntervalMs?: number;
}

/** 연결이 끊겨 멈춘 재생(이어간 뒤 STOPPED · DISCONNECTED로 알린다, U3-R16). */
export interface InterruptedPlayback {
  readonly sessionId: string;
  readonly playbackId: string;
  readonly playedRange: { readonly fromMs: number; readonly toMs: number } | null;
}

interface Piece {
  readonly startSec: number;
  readonly endSec: number;
  handle: ScheduledPlayback | null;
  started: boolean;
  ended: boolean;
}

interface Playback {
  readonly playbackId: string;
  readonly sessionId: string;
  readonly kind: "stream" | "approved";
  readonly pieces: Piece[];
  /** OUTPUT_END를 받았거나(묶음) 조각 하나뿐인 승인 음성이면 true. */
  endReceived: boolean;
  state: "active" | "completed" | "stopped" | "failed";
  startedMarked: boolean;
}

const MS_PER_SECOND = 1_000;

function approvedKey(contentCode: string, contentVersionId: string): string {
  return `${contentCode}:${contentVersionId}`;
}

export class AudioPlayer<C extends AudioClip> {
  private readonly playbacks = new Map<string, Playback>();
  private readonly approved = new Map<string, ApprovedAudio>();
  private readonly clips = new Map<string, C>();
  private openStreamId: string | null = null;
  private timelineEndSec = 0;
  private speaking = false;
  private progressTimer: ReturnType<typeof setInterval> | null = null;

  constructor(private readonly options: PlayerOptions<C>) {}

  /** START_SESSION의 승인 음성을 미리 받는다. 모두 받으면 OK, 하나라도 못 받으면 PARTIAL(빠진 것은 재생 때 다시 받는다). */
  async prefetch(items: readonly ApprovedAudio[]): Promise<PrefetchFlag> {
    for (const item of items) {
      this.approved.set(approvedKey(item.contentCode, item.contentVersionId), item);
    }
    const results = await Promise.all(items.map((item) => this.loadClip(item).then(() => true, () => false)));
    return results.every(Boolean) ? "OK" : "PARTIAL";
  }

  /** OUTPUT_BEGIN: 이 묶음의 0x02만 받는다. 앞 묶음이 열려 있으면 더 받지 않게 닫는다. */
  beginStream(sessionId: string, playbackId: string): void {
    this.closeOpenStream();
    if (this.playbacks.has(playbackId)) {
      return;
    }
    this.playbacks.set(playbackId, {
      playbackId,
      sessionId,
      kind: "stream",
      pieces: [],
      endReceived: false,
      state: "active",
      startedMarked: false,
    });
    this.openStreamId = playbackId;
  }

  /** 0x02 본문(PCM16LE 24kHz). 열린 묶음이 없으면 버린다(BR7.6). */
  appendStream(payload: Uint8Array): void {
    const playback = this.openStreamId === null ? undefined : this.playbacks.get(this.openStreamId);
    if (playback === undefined || playback.state !== "active") {
      return;
    }
    const samples = littleEndianPcm16ToFloat32(payload);
    if (samples.length === 0) {
      return;
    }
    if (!this.options.output.available) {
      this.fail(playback, "AUDIO_DEVICE_ERROR");
      return;
    }
    this.enqueue(playback, this.options.output.fromPcm(samples, AUDIO_SAMPLE_RATE));
  }

  /** OUTPUT_END: 묶음을 닫는다. 이미 낸 조각이 모두 끝나면 COMPLETED. */
  endStream(playbackId: string): void {
    const playback = this.playbacks.get(playbackId);
    if (playback === undefined || playback.kind !== "stream") {
      return;
    }
    if (this.openStreamId === playbackId) {
      this.openStreamId = null;
    }
    playback.endReceived = true;
    this.maybeComplete(playback);
  }

  /** PLAY_APPROVED_CONTENT: 미리 받은 승인 음성을 한 번 재생한다. 모르는 contentCode는 재생하지 않는다(BR7.6). */
  async playApproved(sessionId: string, playbackId: string, contentCode: string, contentVersionId: string): Promise<void> {
    if (this.playbacks.has(playbackId)) {
      return;
    }
    const playback: Playback = {
      playbackId,
      sessionId,
      kind: "approved",
      pieces: [],
      endReceived: true,
      state: "active",
      startedMarked: false,
    };
    this.playbacks.set(playbackId, playback);
    const item = this.approved.get(approvedKey(contentCode, contentVersionId));
    if (item === undefined) {
      // 세션이 고정한 승인 음성 목록에 없는 것은 재생하지 않고 실패로 알린다(서버의 마무리 흐름이 기다리지 않게).
      this.fail(playback, "FETCH_FAILED");
      return;
    }
    let clip: C;
    try {
      clip = await this.loadClip(item);
    } catch (error) {
      this.fail(playback, error instanceof DecodeError ? "DECODE_ERROR" : "FETCH_FAILED");
      return;
    }
    if (playback.state !== "active") {
      return;
    }
    if (!this.options.output.available) {
      this.fail(playback, "AUDIO_DEVICE_ERROR");
      return;
    }
    this.enqueue(playback, clip);
  }

  /** STOP_OUTPUT · CLOSE_TRANSPORT: 재생 중 · 대기 중인 모든 소리를 멈추고 STOPPED를 알린다. */
  stopAll(cause: PlaybackStopCause): void {
    this.openStreamId = null;
    for (const playback of this.playbacks.values()) {
      if (playback.state !== "active") {
        continue;
      }
      const playedRange = this.playedRange(playback);
      playback.state = "stopped";
      this.stopPieces(playback);
      this.mark(playback, "STOPPED", { stopCause: cause, playedRange });
    }
    this.playbacks.clear();
    this.timelineEndSec = 0;
    this.updateSpeaking();
  }

  /**
   * 연결이 끊겼을 때: 알릴 곳이 없으므로 표식 없이 모두 멈춘다. U3: 멈춘 재생을 돌려줘 세션을 이어 가면 STOPPED(DISCONNECTED)로 알리게 한다
   * (마무리 문구의 끝 표식을 기다리는 서버가 30초 확인 시한까지 기다리지 않게).
   */
  reset(): InterruptedPlayback[] {
    this.openStreamId = null;
    const interrupted: InterruptedPlayback[] = [];
    for (const playback of this.playbacks.values()) {
      if (playback.state === "active") {
        interrupted.push({
          sessionId: playback.sessionId,
          playbackId: playback.playbackId,
          playedRange: this.playedRange(playback),
        });
      }
      playback.state = "stopped";
      this.stopPieces(playback);
    }
    this.playbacks.clear();
    this.timelineEndSec = 0;
    this.updateSpeaking();
    return interrupted;
  }

  get isSpeaking(): boolean {
    return this.speaking;
  }

  private closeOpenStream(): void {
    if (this.openStreamId === null) {
      return;
    }
    const previous = this.playbacks.get(this.openStreamId);
    this.openStreamId = null;
    if (previous !== undefined) {
      previous.endReceived = true;
      this.maybeComplete(previous);
    }
  }

  private async loadClip(item: ApprovedAudio): Promise<C> {
    const key = approvedKey(item.contentCode, item.contentVersionId);
    const cached = this.clips.get(key);
    if (cached !== undefined) {
      return cached;
    }
    const bytes = await this.options.fetchAudio(item.audioUrl);
    let clip: C;
    try {
      clip = await this.options.output.decode(bytes);
    } catch {
      throw new DecodeError();
    }
    this.clips.set(key, clip);
    return clip;
  }

  private enqueue(playback: Playback, clip: C): void {
    const now = this.options.output.currentTime();
    const pendingSec = Math.max(0, this.timelineEndSec - now);
    if ((pendingSec + clip.durationSec) * MS_PER_SECOND > this.options.queueMaxMs) {
      this.fail(playback, "QUEUE_OVERFLOW");
      return;
    }
    const startSec = Math.max(now, this.timelineEndSec);
    const piece: Piece = { startSec, endSec: startSec + clip.durationSec, handle: null, started: false, ended: false };
    this.timelineEndSec = piece.endSec;
    playback.pieces.push(piece);
    piece.handle = this.options.output.schedule(clip, startSec, {
      onStarted: () => this.onPieceStarted(playback, piece),
      onEnded: () => this.onPieceEnded(playback, piece),
    });
  }

  private onPieceStarted(playback: Playback, piece: Piece): void {
    if (playback.state !== "active" || piece.started) {
      return;
    }
    piece.started = true;
    if (!playback.startedMarked) {
      playback.startedMarked = true;
      this.mark(playback, "STARTED", {});
    }
    this.updateSpeaking();
  }

  private onPieceEnded(playback: Playback, piece: Piece): void {
    if (playback.state !== "active" || piece.ended) {
      return;
    }
    // 아주 짧은 조각은 시작 알림보다 끝 알림이 먼저 올 수 있다.
    this.onPieceStarted(playback, piece);
    piece.ended = true;
    piece.handle = null;
    this.updateSpeaking();
    this.maybeComplete(playback);
  }

  private maybeComplete(playback: Playback): void {
    if (playback.state !== "active" || !playback.endReceived) {
      return;
    }
    if (!playback.pieces.every((piece) => piece.ended)) {
      return;
    }
    playback.state = "completed";
    this.playbacks.delete(playback.playbackId);
    if (playback.pieces.length > 0) {
      this.mark(playback, "COMPLETED", {});
    }
    this.updateSpeaking();
  }

  private fail(playback: Playback, reason: PlaybackFailureReason): void {
    if (playback.state !== "active") {
      return;
    }
    playback.state = "failed";
    this.stopPieces(playback);
    this.playbacks.delete(playback.playbackId);
    if (this.openStreamId === playback.playbackId) {
      this.openStreamId = null;
    }
    this.recomputeTimeline();
    this.mark(playback, "FAILED", { failureReason: reason });
    this.updateSpeaking();
  }

  private stopPieces(playback: Playback): void {
    for (const piece of playback.pieces) {
      piece.handle?.stop();
      piece.handle = null;
      piece.ended = true;
    }
  }

  private recomputeTimeline(): void {
    let end = 0;
    for (const playback of this.playbacks.values()) {
      for (const piece of playback.pieces) {
        if (!piece.ended) {
          end = Math.max(end, piece.endSec);
        }
      }
    }
    this.timelineEndSec = end;
  }

  private updateSpeaking(): void {
    let speaking = false;
    for (const playback of this.playbacks.values()) {
      if (playback.state === "active" && playback.pieces.some((piece) => piece.started && !piece.ended)) {
        speaking = true;
        break;
      }
    }
    if (speaking !== this.speaking) {
      this.speaking = speaking;
      this.updateProgressTimer();
      this.options.onSpeakingChange(speaking);
    }
  }

  /** 소리가 나는 동안만 PROGRESS 타이머를 돌린다(U3-J10). */
  private updateProgressTimer(): void {
    const interval = this.options.progressIntervalMs ?? 0;
    if (this.speaking && interval > 0 && this.progressTimer === null) {
      this.progressTimer = setInterval(() => this.reportProgress(), interval);
    } else if (!this.speaking && this.progressTimer !== null) {
      clearInterval(this.progressTimer);
      this.progressTimer = null;
    }
  }

  private reportProgress(): void {
    for (const playback of this.playbacks.values()) {
      if (playback.state === "active" && playback.startedMarked) {
        this.mark(playback, "PROGRESS", { playedRange: this.playedRange(playback) });
      }
    }
  }

  /** 시작한 재생의 지금까지 재생 구간(밀리초, 0부터). 시작하지 않았으면 null. */
  private playedRange(playback: Playback): { readonly fromMs: number; readonly toMs: number } | null {
    if (!playback.startedMarked) {
      return null;
    }
    const now = this.options.output.currentTime();
    let playedSec = 0;
    for (const piece of playback.pieces) {
      if (piece.started) {
        playedSec += Math.max(0, Math.min(now, piece.endSec) - piece.startSec);
      }
    }
    return { fromMs: 0, toMs: Math.round(playedSec * MS_PER_SECOND) };
  }

  private mark(
    playback: Playback,
    status: PlaybackStatus,
    extra: {
      stopCause?: PlaybackStopCause;
      failureReason?: PlaybackFailureReason;
      playedRange?: { readonly fromMs: number; readonly toMs: number } | null;
    },
  ): void {
    this.options.onMark(playback.sessionId, {
      playbackId: playback.playbackId,
      status,
      playedRange: extra.playedRange ?? null,
      observedAt: toIsoInstant(this.options.nowMs()),
      stopCause: extra.stopCause ?? null,
      failureReason: extra.failureReason ?? null,
    });
  }
}

class DecodeError extends Error {
  constructor() {
    super("승인 음성을 해석하지 못했다");
    this.name = "DecodeError";
  }
}

// ───────────── Web Audio 어댑터 ─────────────

export interface WebAudioClip extends AudioClip {
  readonly buffer: AudioBuffer;
}

/** Web Audio 출력. 마이크 캡처와 같은 AudioContext를 쓴다. */
export function createWebAudioOutput(context: AudioContext): AudioOutput<WebAudioClip> {
  return {
    get available() {
      return context.state !== "closed";
    },
    currentTime: () => context.currentTime,
    fromPcm(samples, sampleRate) {
      const buffer = context.createBuffer(1, samples.length, sampleRate);
      buffer.copyToChannel(samples, 0);
      return { buffer, durationSec: buffer.duration };
    },
    async decode(bytes) {
      const buffer = await context.decodeAudioData(bytes.slice(0));
      return { buffer, durationSec: buffer.duration };
    },
    schedule(clip, whenSec, callbacks) {
      const source = context.createBufferSource();
      source.buffer = clip.buffer;
      source.connect(context.destination);
      let finished = false;
      const startDelayMs = Math.max(0, (whenSec - context.currentTime) * MS_PER_SECOND);
      const startTimer = setTimeout(() => {
        if (!finished) {
          callbacks.onStarted();
        }
      }, startDelayMs);
      source.onended = () => {
        if (finished) {
          return;
        }
        finished = true;
        clearTimeout(startTimer);
        callbacks.onEnded();
      };
      source.start(whenSec);
      return {
        stop() {
          if (finished) {
            return;
          }
          finished = true;
          clearTimeout(startTimer);
          source.onended = null;
          try {
            source.stop();
          } catch {
            // 이미 끝난 노드는 stop이 예외를 낼 수 있다. 멈춤 상태는 같다.
          }
          source.disconnect();
        },
      };
    },
  };
}

/** 승인 음성 받기(같은 origin, 쿠키 불필요). 2xx가 아니면 예외. */
export async function fetchApprovedAudio(url: string): Promise<ArrayBuffer> {
  const response = await fetch(url, { credentials: "same-origin", cache: "force-cache" });
  if (!response.ok) {
    throw new Error(`승인 음성 받기 실패: HTTP ${response.status}`);
  }
  return response.arrayBuffer();
}
