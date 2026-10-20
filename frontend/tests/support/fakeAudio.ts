import { vi } from "vitest";
import type { AudioClip, AudioOutput, PlaybackCallbacks, ScheduledPlayback } from "@/device/audio/player";

// 오디오 대역 (U1 3.5 Step 41 · unit-test-instructions 'AudioContext는 vi.fn 대역')
// - FakeOutput: 재생 규칙(AudioPlayer) 시험용 출력 장치. 예약한 조각을 기록하고 시작 · 끝을 시험이 직접 일으킨다.
// - FakeAudioContext: Web Audio 어댑터 · 캡처 · 기기 실행부 시험용 AudioContext 대역.

export interface FakeClip extends AudioClip {
  readonly label: string;
}

export interface ScheduledEntry {
  readonly clip: FakeClip;
  readonly whenSec: number;
  readonly callbacks: PlaybackCallbacks;
  stopped: boolean;
}

export class FakeOutput implements AudioOutput<FakeClip> {
  available = true;
  time = 0;
  readonly scheduled: ScheduledEntry[] = [];
  /** decode가 돌려줄 길이(초). 없으면 1초. 'fail'이면 해석 실패. */
  decodeDurations = new Map<number, number | "fail">();

  currentTime(): number {
    return this.time;
  }

  fromPcm(samples: Float32Array<ArrayBuffer>, sampleRate: number): FakeClip {
    return { label: `pcm:${samples.length}`, durationSec: samples.length / sampleRate };
  }

  async decode(bytes: ArrayBuffer): Promise<FakeClip> {
    const planned = this.decodeDurations.get(bytes.byteLength);
    if (planned === "fail") {
      throw new Error("decode");
    }
    return { label: `decoded:${bytes.byteLength}`, durationSec: planned ?? 1 };
  }

  schedule(clip: FakeClip, whenSec: number, callbacks: PlaybackCallbacks): ScheduledPlayback {
    const entry: ScheduledEntry = { clip, whenSec, callbacks, stopped: false };
    this.scheduled.push(entry);
    return {
      stop: () => {
        entry.stopped = true;
      },
    };
  }

  startAll(): void {
    for (const entry of this.scheduled) {
      if (!entry.stopped) {
        entry.callbacks.onStarted();
      }
    }
  }

  endAll(): void {
    for (const entry of this.scheduled) {
      if (!entry.stopped) {
        entry.callbacks.onEnded();
      }
    }
  }
}

export class FakeAudioBuffer {
  readonly copyToChannel = vi.fn();

  constructor(
    readonly length: number,
    readonly sampleRate: number,
  ) {}

  get duration(): number {
    return this.length / this.sampleRate;
  }
}

export class FakeBufferSource {
  buffer: FakeAudioBuffer | null = null;
  onended: (() => void) | null = null;
  startedAt: number | null = null;
  stopCount = 0;
  throwOnStop = false;
  readonly connect = vi.fn();
  readonly disconnect = vi.fn();

  start(when: number): void {
    this.startedAt = when;
  }

  stop(): void {
    this.stopCount += 1;
    if (this.throwOnStop) {
      throw new Error("InvalidStateError");
    }
  }
}

export interface FakeNode {
  readonly connect: ReturnType<typeof vi.fn>;
  readonly disconnect: ReturnType<typeof vi.fn>;
}

export class FakeAudioContext {
  state: AudioContextState = "running";
  currentTime = 0;
  readonly destination = { kind: "destination" };
  readonly sources: FakeBufferSource[] = [];
  readonly mediaSources: FakeNode[] = [];
  readonly audioWorklet = { addModule: vi.fn(async (url: string) => void url) };
  readonly resume = vi.fn(async () => {
    this.state = "running";
  });
  readonly close = vi.fn(async () => {
    this.state = "closed";
  });
  readonly decodeAudioData = vi.fn(async (bytes: ArrayBuffer) => new FakeAudioBuffer(Math.max(1, bytes.byteLength), 24_000));
  readonly createMediaStreamSource = vi.fn(() => {
    const node: FakeNode = { connect: vi.fn(), disconnect: vi.fn() };
    this.mediaSources.push(node);
    return node;
  });

  constructor(readonly sampleRate: number = 24_000) {}

  createBuffer(channels: number, length: number, sampleRate: number): FakeAudioBuffer {
    void channels;
    return new FakeAudioBuffer(length, sampleRate);
  }

  createBufferSource(): FakeBufferSource {
    const source = new FakeBufferSource();
    this.sources.push(source);
    return source;
  }

  asAudioContext(): AudioContext {
    return this as unknown as AudioContext;
  }
}

/** AudioWorkletNode 대역(port.onmessage로 표본 조각을 받는다). */
export class FakeWorkletNode {
  readonly port: { onmessage: ((event: MessageEvent<unknown>) => void) | null } = { onmessage: null };
  readonly connect = vi.fn();
  readonly disconnect = vi.fn();

  constructor(
    readonly context?: unknown,
    readonly name?: string,
    readonly options?: AudioWorkletNodeOptions,
  ) {}

  /** worklet이 메인 스레드로 표본 조각을 보낸 것처럼 한다. */
  emit(data: unknown): void {
    this.port.onmessage?.({ data } as MessageEvent<unknown>);
  }

  asNode(): AudioWorkletNode {
    return this as unknown as AudioWorkletNode;
  }
}

export interface FakeTrack {
  readonly stop: ReturnType<typeof vi.fn>;
}

export function fakeStream(trackCount = 1): { readonly stream: MediaStream; readonly tracks: FakeTrack[] } {
  const tracks: FakeTrack[] = Array.from({ length: trackCount }, () => ({ stop: vi.fn() }));
  const stream = { getTracks: () => tracks } as unknown as MediaStream;
  return { stream, tracks };
}
