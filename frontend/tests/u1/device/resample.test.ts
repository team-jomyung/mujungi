import { describe, expect, it } from "vitest";
import { FrameAssembler, LinearResampler, createCaptureConverter, floatToPcm16 } from "@/device/audio/resample";
import { INPUT_FRAME_SAMPLES } from "@/shared/protocol/frameCodec";

// 비율 변환 · 프레임 조립 시험 (U1 3.5 Step 41 · J121 · J27 · J138)
// 48kHz(또는 44.1kHz) 문맥 → 24kHz 선형 보간, 20ms = 480표본 프레임 조립, 조각 경계에서 끊김 없음.

function ramp(length: number, start = 0): Float32Array {
  return Float32Array.from({ length }, (_, index) => (start + index) / 1_000);
}

function concat(parts: readonly Float32Array[]): number[] {
  return parts.flatMap((part) => Array.from(part));
}

describe("LinearResampler", () => {
  it("48 → 24kHz는 표본을 하나 걸러 고른다", () => {
    const resampler = new LinearResampler(48_000, 24_000);
    expect(Array.from(resampler.process(Float32Array.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)))).toEqual([0, 2, 4, 6, 8]);
  });

  it("조각으로 나눠 넣어도 한 번에 넣은 것과 같은 값이다(경계 표본 보간)", () => {
    const whole = new LinearResampler(44_100, 24_000).process(ramp(882));
    const chunked = new LinearResampler(44_100, 24_000);
    const parts = [ramp(128), ramp(128, 128), ramp(300, 256), ramp(326, 556)].map((part) => chunked.process(part));
    const joined = concat(parts);
    expect(joined).toHaveLength(whole.length);
    joined.forEach((value, index) => expect(value).toBeCloseTo(whole[index], 5));
  });

  it("직선 신호는 보간해도 직선 위에 있다(44.1 → 24kHz)", () => {
    const output = new LinearResampler(44_100, 24_000).process(ramp(441));
    const step = 44_100 / 24_000;
    output.forEach((value, index) => expect(value).toBeCloseTo((index * step) / 1_000, 5));
    expect(output.length).toBe(240);
  });

  it("비율이 같으면 복사만 하고 빈 조각은 빈 결과다", () => {
    const resampler = new LinearResampler(24_000, 24_000);
    const input = Float32Array.of(0.1, 0.2);
    const output = resampler.process(input);
    expect(Array.from(output)).toEqual(Array.from(input));
    expect(output).not.toBe(input);
    expect(new LinearResampler(48_000, 24_000).process(new Float32Array(0))).toHaveLength(0);
  });

  it("0 이하 · 숫자 아닌 비율은 거부한다", () => {
    expect(() => new LinearResampler(0, 24_000)).toThrow(RangeError);
    expect(() => new LinearResampler(48_000, -1)).toThrow(RangeError);
    expect(() => new LinearResampler(Number.NaN, 24_000)).toThrow(RangeError);
  });
});

describe("floatToPcm16", () => {
  it("−1.0 ~ 1.0을 PCM16으로 바꾸고 범위 밖은 자른다", () => {
    expect(Array.from(floatToPcm16(Float32Array.of(-1, 0, 1, 0.5, -0.5, 2, -2)))).toEqual([-32768, 0, 32767, 16384, -16384, 32767, -32768]);
  });
});

describe("FrameAssembler — 480표본 프레임", () => {
  it("128표본 조각 4개(512)에서 프레임 1개를 만들고 남은 32표본은 다음으로 넘긴다", () => {
    const assembler = new FrameAssembler();
    const pushes = [0, 1, 2].map((offset) => assembler.push(Int16Array.from({ length: 128 }, (_, index) => offset * 128 + index)));
    expect(pushes.every((frames) => frames.length === 0)).toBe(true);
    const frames = assembler.push(Int16Array.from({ length: 128 }, (_, index) => 384 + index));
    expect(frames).toHaveLength(1);
    expect(frames[0]).toHaveLength(INPUT_FRAME_SAMPLES);
    expect(frames[0][0]).toBe(0);
    expect(frames[0][479]).toBe(479);
    const next = assembler.push(new Int16Array(448));
    expect(next).toHaveLength(1);
    expect(next[0][0]).toBe(480);
    expect(next[0][31]).toBe(511);
  });

  it("큰 조각은 프레임 여러 개로 나누고 프레임마다 새 배열이다", () => {
    const assembler = new FrameAssembler();
    const frames = assembler.push(Int16Array.from({ length: 1_000 }, (_, index) => index));
    expect(frames).toHaveLength(2);
    expect(frames[0]).not.toBe(frames[1]);
    expect(frames[1][0]).toBe(480);
  });

  it("reset은 모은 표본을 버리고, 틀린 프레임 크기는 거부한다", () => {
    const assembler = new FrameAssembler();
    assembler.push(new Int16Array(400).fill(7));
    assembler.reset();
    const frames = assembler.push(new Int16Array(480).fill(1));
    expect(frames).toHaveLength(1);
    expect(frames[0].every((sample) => sample === 1)).toBe(true);
    expect(() => new FrameAssembler(0)).toThrow(RangeError);
    expect(() => new FrameAssembler(2.5)).toThrow(RangeError);
  });
});

describe("createCaptureConverter — 문맥 비율 → 24kHz 20ms 프레임", () => {
  it("48kHz 문맥의 20ms(960표본)는 24kHz 프레임 1개가 된다(24kHz 거부 시 보간 경로)", () => {
    const converter = createCaptureConverter(48_000);
    expect(converter.push(new Float32Array(959).fill(0.25))).toHaveLength(0);
    const frames = converter.push(new Float32Array(3).fill(0.25));
    expect(frames).toHaveLength(1);
    expect(frames[0].every((sample) => sample === Math.round(0.25 * 0x7fff))).toBe(true);
  });

  it("24kHz 문맥은 변환 없이 480표본씩 묶는다", () => {
    const converter = createCaptureConverter(24_000);
    const input = Float32Array.from({ length: 480 }, (_, index) => (index % 2 === 0 ? 0.5 : -0.5));
    const [frame] = converter.push(input);
    expect(Array.from(frame)).toEqual(Array.from(floatToPcm16(input)));
  });

  it("reset 뒤에는 앞 조각의 남은 표본이 섞이지 않는다", () => {
    const converter = createCaptureConverter(24_000);
    converter.push(new Float32Array(300).fill(1));
    converter.reset();
    const [frame] = converter.push(new Float32Array(480).fill(-1));
    expect(frame.every((sample) => sample === -32768)).toBe(true);
  });
});
