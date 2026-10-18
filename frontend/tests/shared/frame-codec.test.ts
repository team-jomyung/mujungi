import { describe, expect, it } from "vitest";
import {
  AUDIO_FORMAT,
  FRAME_KIND_INPUT,
  FRAME_KIND_OUTPUT,
  INPUT_FRAME_BYTES,
  INPUT_FRAME_SAMPLES,
  MAX_FRAME_SEQ,
  decodeFrame,
  encodeInputFrame,
  encodeOutputFrame,
  isValidFrameSeq,
  littleEndianPcm16ToFloat32,
  nextFrameSeq,
  pcm16ToLittleEndianBytes,
} from "@/shared/protocol/frameCodec";

// 바이너리 음성 프레임 시험 (U1 3.5 Step 41 · J27 · 계약 4.2 · BR7.1 · BR7.6 · 편차 (s))
// 0x01 입력 = 1 + 4 + 960 = 965바이트, 순번 uint32 빅엔디언, 본문 PCM16LE 24kHz 모노.

describe("frameCodec — 입력 프레임 965B", () => {
  it("480표본을 965바이트로 싸고 순번은 빅엔디언, 본문은 리틀엔디언이다", () => {
    const samples = new Int16Array(INPUT_FRAME_SAMPLES);
    samples[0] = 0x1234;
    samples[1] = -2;
    samples[479] = 0x7fff;
    const frame = encodeInputFrame(0x01020304, samples);
    expect(INPUT_FRAME_BYTES).toBe(965);
    expect(frame.byteLength).toBe(965);
    expect(frame[0]).toBe(FRAME_KIND_INPUT);
    expect(Array.from(frame.subarray(1, 5))).toEqual([0x01, 0x02, 0x03, 0x04]);
    expect(Array.from(frame.subarray(5, 9))).toEqual([0x34, 0x12, 0xfe, 0xff]);
    expect(Array.from(frame.subarray(963, 965))).toEqual([0xff, 0x7f]);
  });

  it("싼 프레임을 다시 읽으면 종류 · 순번 · 본문이 같다", () => {
    const samples = Int16Array.from({ length: INPUT_FRAME_SAMPLES }, (_, index) => index - 240);
    const result = decodeFrame(encodeInputFrame(MAX_FRAME_SEQ, samples));
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.frame.kind).toBe(FRAME_KIND_INPUT);
    expect(result.frame.seq).toBe(MAX_FRAME_SEQ);
    expect(result.frame.payload.byteLength).toBe(960);
    expect(Array.from(result.frame.payload.subarray(0, 2))).toEqual(Array.from(pcm16ToLittleEndianBytes(Int16Array.of(-240))));
  });

  it("표본이 480개가 아니거나 순번이 uint32 밖이면 만들지 않는다", () => {
    expect(() => encodeInputFrame(1, new Int16Array(479))).toThrow(RangeError);
    expect(() => encodeInputFrame(1, new Int16Array(481))).toThrow(RangeError);
    expect(() => encodeInputFrame(-1, new Int16Array(INPUT_FRAME_SAMPLES))).toThrow(RangeError);
    expect(() => encodeInputFrame(MAX_FRAME_SEQ + 1, new Int16Array(INPUT_FRAME_SAMPLES))).toThrow(RangeError);
    expect(() => encodeInputFrame(1.5, new Int16Array(INPUT_FRAME_SAMPLES))).toThrow(RangeError);
  });

  it("입력 프레임 길이가 964 · 966바이트면 거부한다(편차 (s))", () => {
    const short = new Uint8Array(964);
    short[0] = FRAME_KIND_INPUT;
    const long = new Uint8Array(966);
    long[0] = FRAME_KIND_INPUT;
    expect(decodeFrame(short)).toEqual({ ok: false, reason: "INPUT_LENGTH" });
    expect(decodeFrame(long)).toEqual({ ok: false, reason: "INPUT_LENGTH" });
  });
});

describe("frameCodec — 출력 프레임 · 경계", () => {
  it("0x02 본문은 2바이트 배수면 길이 자유이고 다시 읽을 수 있다", () => {
    const payload = Uint8Array.of(1, 0, 2, 0, 3, 0);
    const frame = encodeOutputFrame(7, payload);
    expect(frame[0]).toBe(FRAME_KIND_OUTPUT);
    const result = decodeFrame(frame.buffer as ArrayBuffer);
    expect(result).toEqual({ ok: true, frame: { kind: FRAME_KIND_OUTPUT, seq: 7, payload: expect.any(Uint8Array) } });
    if (result.ok) {
      expect(Array.from(result.frame.payload)).toEqual([1, 0, 2, 0, 3, 0]);
    }
  });

  it("홀수 본문 · 빈 본문 · 모르는 종류 · 머리뿐인 프레임은 거부한다", () => {
    expect(() => encodeOutputFrame(1, new Uint8Array(0))).toThrow(RangeError);
    expect(() => encodeOutputFrame(1, new Uint8Array(3))).toThrow(RangeError);
    expect(() => encodeOutputFrame(MAX_FRAME_SEQ + 1, new Uint8Array(2))).toThrow(RangeError);
    expect(decodeFrame(Uint8Array.of(FRAME_KIND_OUTPUT, 0, 0, 0, 1, 9))).toEqual({ ok: false, reason: "OUTPUT_LENGTH" });
    expect(decodeFrame(Uint8Array.of(0x03, 0, 0, 0, 1, 9, 9))).toEqual({ ok: false, reason: "UNKNOWN_KIND" });
    expect(decodeFrame(Uint8Array.of(FRAME_KIND_OUTPUT, 0, 0, 0, 1))).toEqual({ ok: false, reason: "TOO_SHORT" });
  });

  it("DataView 같은 다른 보기에서도 원본 바이트 위치를 지켜 읽는다", () => {
    const frame = encodeOutputFrame(258, Uint8Array.of(9, 0));
    const padded = new Uint8Array(frame.byteLength + 3);
    padded.set(frame, 3);
    const result = decodeFrame(new DataView(padded.buffer, 3, frame.byteLength));
    expect(result.ok && result.frame.seq).toBe(258);
  });

  it("순번은 uint32 끝에서 0으로 돌아가고 범위 밖 값은 틀린 순번이다", () => {
    expect(nextFrameSeq(0)).toBe(1);
    expect(nextFrameSeq(MAX_FRAME_SEQ - 1)).toBe(MAX_FRAME_SEQ);
    expect(nextFrameSeq(MAX_FRAME_SEQ)).toBe(0);
    expect(isValidFrameSeq(0)).toBe(true);
    expect(isValidFrameSeq(MAX_FRAME_SEQ)).toBe(true);
    expect(isValidFrameSeq(-1)).toBe(false);
    expect(isValidFrameSeq(MAX_FRAME_SEQ + 1)).toBe(false);
  });

  it("PCM16LE를 −1.0 ~ 1.0으로 바꾸고 홀수 끝 바이트는 버린다", () => {
    const bytes = pcm16ToLittleEndianBytes(Int16Array.of(-32768, 0, 32767));
    const withOdd = new Uint8Array(bytes.byteLength + 1);
    withOdd.set(bytes);
    const floats = littleEndianPcm16ToFloat32(withOdd);
    expect(Array.from(floats)).toEqual([-1, 0, 1]);
    expect(AUDIO_FORMAT).toBe("pcm16le/24000/1");
  });
});
