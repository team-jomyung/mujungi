import { AUDIO_SAMPLE_RATE, INPUT_FRAME_SAMPLES } from "@/shared/protocol/frameCodec";

// 마이크 표본 → 20ms PCM16 프레임 (U1 3.5 Step 38 · J121 · J27 · J138)
// AudioWorklet은 받은 표본을 그대로 넘기는 얇은 등록부이고(pcm-capture.worklet.ts), 비율 변환과 480표본 프레임 조립은
// 여기 순수 함수가 한다(jsdom에서 시험 가능). 브라우저가 24kHz 문맥을 받아 주면 변환 없이 지나가고, 거부해 48kHz(또는
// 44.1kHz) 문맥이면 선형 보간으로 24kHz로 바꾼다. 원음은 메모리에만 있고 저장 · 기록하지 않는다(BR7.1).

/**
 * 선형 보간 비율 변환기. 조각 사이의 위치와 마지막 표본을 기억해 이어지는 조각을 끊김 없이 바꾼다.
 */
export class LinearResampler {
  private readonly step: number;
  private position = 0;
  private previous = 0;

  constructor(
    readonly inputRate: number,
    readonly outputRate: number,
  ) {
    if (!(inputRate > 0) || !(outputRate > 0)) {
      throw new RangeError("표본 비율은 0보다 커야 한다");
    }
    this.step = inputRate / outputRate;
  }

  /** 입력 조각을 바꿔 출력 표본을 돌려준다. 비율이 같으면 복사만 한다. */
  process(input: Float32Array): Float32Array {
    if (input.length === 0) {
      return new Float32Array(0);
    }
    if (this.inputRate === this.outputRate) {
      return input.slice();
    }
    const output: number[] = [];
    let position = this.position;
    for (;;) {
      const lower = Math.floor(position);
      const upper = lower + 1;
      if (upper >= input.length) {
        break;
      }
      const start = lower < 0 ? this.previous : input[lower];
      const end = input[upper];
      output.push(start + (end - start) * (position - lower));
      position += this.step;
    }
    // 다음 조각 기준 위치(−1 이상). −1 ~ 0이면 이번 조각의 마지막 표본과 다음 조각의 첫 표본 사이다.
    this.position = position - input.length;
    this.previous = input[input.length - 1];
    return Float32Array.from(output);
  }
}

/** −1.0 ~ 1.0 표본을 PCM16으로 바꾼다(범위 밖은 자른다). */
export function floatToPcm16(samples: Float32Array): Int16Array {
  const out = new Int16Array(samples.length);
  for (let index = 0; index < samples.length; index += 1) {
    const clamped = Math.max(-1, Math.min(1, samples[index]));
    out[index] = clamped < 0 ? Math.round(clamped * 0x8000) : Math.round(clamped * 0x7fff);
  }
  return out;
}

/** PCM16 표본을 모아 정확히 frameSamples개짜리 프레임을 만든다. 남은 표본은 다음 push로 이어진다. */
export class FrameAssembler {
  private buffer: Int16Array;
  private filled = 0;

  constructor(readonly frameSamples: number = INPUT_FRAME_SAMPLES) {
    if (!Number.isInteger(frameSamples) || frameSamples <= 0) {
      throw new RangeError("프레임 표본 수는 양의 정수여야 한다");
    }
    this.buffer = new Int16Array(frameSamples);
  }

  push(samples: Int16Array): Int16Array[] {
    const frames: Int16Array[] = [];
    let offset = 0;
    while (offset < samples.length) {
      const take = Math.min(this.frameSamples - this.filled, samples.length - offset);
      this.buffer.set(samples.subarray(offset, offset + take), this.filled);
      this.filled += take;
      offset += take;
      if (this.filled === this.frameSamples) {
        frames.push(this.buffer);
        this.buffer = new Int16Array(this.frameSamples);
        this.filled = 0;
      }
    }
    return frames;
  }

  /** 모은 표본을 버린다(세션 사이 · 끄기). */
  reset(): void {
    this.filled = 0;
  }
}

export interface CaptureConverter {
  /** 문맥 비율의 부동소수 조각을 넣으면 완성된 24kHz 480표본 프레임들을 돌려준다. */
  push(input: Float32Array): Int16Array[];
  reset(): void;
}

/** 문맥 표본 비율 → 24kHz 20ms 프레임 변환기. */
export function createCaptureConverter(inputRate: number, outputRate: number = AUDIO_SAMPLE_RATE): CaptureConverter {
  let resampler = new LinearResampler(inputRate, outputRate);
  const assembler = new FrameAssembler();
  return {
    push(input: Float32Array): Int16Array[] {
      return assembler.push(floatToPcm16(resampler.process(input)));
    },
    reset(): void {
      resampler = new LinearResampler(inputRate, outputRate);
      assembler.reset();
    },
  };
}
