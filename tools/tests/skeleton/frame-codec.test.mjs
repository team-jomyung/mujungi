// 기기 WSS 바이너리 프레임 · 합성 PCM · 해시 시험 (U1 3.5 Step 46 · 계약 4.2 · J27 · BR7.1 · BR7.6 · 편차 (s) · J131)
// 프레임 바이트 규격은 backend AudioFrameCodec · frontend frameCodec.ts와 같은 계약 상수다. 여기서는 도구 쪽 구현을
// 계약 숫자(965바이트 · 빅엔디언 순번 · 0x01/0x02)로 직접 대조한다.
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import {
  AUDIO_FORMAT,
  FRAME_KIND_INPUT,
  FRAME_KIND_OUTPUT,
  INPUT_FRAME_BYTES,
  INPUT_PAYLOAD_BYTES,
  MAX_FRAME_SEQ,
  RunningHash,
  decodeFrame,
  encodeInputFrame,
  encodeOutputFrame,
  inspectWav,
  isValidFrameSeq,
  nextFrameSeq,
  pcmDurationMs,
  sha256Hex,
  sha256OfChunks,
  synthesizeInputPayload,
  synthesizeTone,
} from "../../lib/frameCodec.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const sha = (bytes) => createHash("sha256").update(bytes).digest("hex");

describe("입력 프레임 0x01(J27 · BR7.1)", () => {
  it("20ms 본문 960바이트 + 머리 5바이트 = 965바이트이고 순번은 빅엔디언 uint32다", () => {
    const payload = synthesizeInputPayload(0);
    const frame = encodeInputFrame(0x01020304, payload);

    expect(INPUT_PAYLOAD_BYTES).toBe(960);
    expect(INPUT_FRAME_BYTES).toBe(965);
    expect(frame.length).toBe(965);
    expect(frame[0]).toBe(0x01);
    expect([...frame.subarray(1, 5)]).toEqual([0x01, 0x02, 0x03, 0x04]);
    expect(frame.subarray(5).equals(payload)).toBe(true);
  });

  it("964 · 966바이트 본문은 만들지 않는다(편차 (s) 4400 대상)", () => {
    expect(() => encodeInputFrame(1, Buffer.alloc(958))).toThrow(RangeError);
    expect(() => encodeInputFrame(1, Buffer.alloc(962))).toThrow(RangeError);
  });

  it("순번은 0 ~ uint32 범위만 받고 끝에서 0으로 돈다", () => {
    expect(isValidFrameSeq(0)).toBe(true);
    expect(isValidFrameSeq(MAX_FRAME_SEQ)).toBe(true);
    expect(isValidFrameSeq(MAX_FRAME_SEQ + 1)).toBe(false);
    expect(isValidFrameSeq(-1)).toBe(false);
    expect(isValidFrameSeq(1.5)).toBe(false);
    expect(() => encodeInputFrame(MAX_FRAME_SEQ + 1, synthesizeInputPayload(0))).toThrow(
      RangeError,
    );
    expect(nextFrameSeq(41)).toBe(42);
    expect(nextFrameSeq(MAX_FRAME_SEQ)).toBe(0);
  });
});

describe("프레임 읽기 decodeFrame", () => {
  it("입력 · 출력 프레임을 같은 종류 · 순번 · 본문으로 되읽는다", () => {
    const input = decodeFrame(encodeInputFrame(7, synthesizeInputPayload(3)));
    expect(input.ok).toBe(true);
    expect(input.frame.kind).toBe(FRAME_KIND_INPUT);
    expect(input.frame.seq).toBe(7);
    expect(input.frame.payload.equals(synthesizeInputPayload(3))).toBe(true);

    const pcm = synthesizeTone(100);
    const output = decodeFrame(encodeOutputFrame(MAX_FRAME_SEQ, pcm));
    expect(output.ok).toBe(true);
    expect(output.frame.kind).toBe(FRAME_KIND_OUTPUT);
    expect(output.frame.seq).toBe(MAX_FRAME_SEQ);
    expect(output.frame.payload.equals(pcm)).toBe(true);
  });

  it("ArrayBuffer · TypedArray · 조각 배열(ws 조각 메시지)도 같은 결과다", () => {
    const frame = encodeInputFrame(9, synthesizeInputPayload(1));
    const asArrayBuffer = frame.buffer.slice(frame.byteOffset, frame.byteOffset + frame.byteLength);
    const fromArrayBuffer = decodeFrame(asArrayBuffer);
    const fromView = decodeFrame(new Uint8Array(asArrayBuffer));
    const fromChunks = decodeFrame([frame.subarray(0, 100), frame.subarray(100)]);
    for (const decoded of [fromArrayBuffer, fromView, fromChunks]) {
      expect(decoded.ok).toBe(true);
      expect(decoded.frame.seq).toBe(9);
      expect(decoded.frame.payload.length).toBe(960);
    }
    expect(() => decodeFrame("문자열")).toThrow(TypeError);
  });

  it("규격 밖 프레임은 사유만 돌려주고 본문을 담지 않는다", () => {
    const valid = encodeInputFrame(1, synthesizeInputPayload(0));
    const shortInput = Buffer.concat([valid.subarray(0, 964)]);
    const longInput = Buffer.concat([valid, Buffer.alloc(1)]);
    const unknownKind = Buffer.from(valid);
    unknownKind[0] = 0x03;
    const oddOutput = Buffer.concat([Buffer.from([0x02, 0, 0, 0, 1]), Buffer.alloc(3)]);

    expect(decodeFrame(Buffer.alloc(5))).toEqual({ ok: false, reason: "TOO_SHORT" });
    expect(decodeFrame(shortInput)).toEqual({ ok: false, reason: "INPUT_LENGTH" });
    expect(decodeFrame(longInput)).toEqual({ ok: false, reason: "INPUT_LENGTH" });
    expect(decodeFrame(unknownKind)).toEqual({ ok: false, reason: "UNKNOWN_KIND" });
    expect(decodeFrame(oddOutput)).toEqual({ ok: false, reason: "OUTPUT_LENGTH" });
  });

  it("출력 프레임 본문은 비지 않은 2바이트 배수여야 만든다", () => {
    expect(() => encodeOutputFrame(1, Buffer.alloc(0))).toThrow(RangeError);
    expect(() => encodeOutputFrame(1, Buffer.alloc(3))).toThrow(RangeError);
    expect(encodeOutputFrame(1, Buffer.alloc(4)).length).toBe(9);
  });
});

describe("합성음 · 해시(J131 무변환 중계 확인의 기준)", () => {
  it("같은 인자면 같은 바이트이고 프레임 번호마다 위상이 이어진다", () => {
    expect(synthesizeInputPayload(5).equals(synthesizeInputPayload(5))).toBe(true);
    expect(synthesizeInputPayload(5).equals(synthesizeInputPayload(6))).toBe(false);
    const joined = Buffer.concat([synthesizeInputPayload(0), synthesizeInputPayload(1)]);
    expect(joined.equals(synthesizeTone(960))).toBe(true);
    expect(() => synthesizeTone(-1)).toThrow(RangeError);
    expect(() => synthesizeTone(10, { amplitude: 2 })).toThrow(RangeError);
  });

  it("이어 붙이는 해시는 조각 경계와 무관하게 전체 SHA-256과 같다", () => {
    const frames = [0, 1, 2, 3].map((index) => synthesizeInputPayload(index));
    const whole = Buffer.concat(frames);
    const running = new RunningHash();
    running
      .update(whole.subarray(0, 1000))
      .update(whole.subarray(1000, 1001))
      .update(whole.subarray(1001));

    expect(running.hex()).toBe(sha(whole));
    expect(running.bytes).toBe(whole.length);
    expect(running.chunks).toBe(3);
    expect(sha256OfChunks(frames)).toBe(sha(whole));
    expect(sha256Hex(whole)).toBe(sha(whole));
    // hex()를 불러도 해시 상태가 이어진다
    running.update(Buffer.from([1]));
    expect(running.hex()).toBe(sha(Buffer.concat([whole, Buffer.from([1])])));
  });

  it("재생 시간은 24kHz · 16비트 · 모노 기준이다", () => {
    expect(pcmDurationMs(48_000)).toBe(1000);
    expect(pcmDurationMs(960)).toBe(20);
    expect(AUDIO_FORMAT).toBe("pcm16le/24000/1");
  });
});

describe("승인 음성 WAV 확인(미리 받기)", () => {
  it("저장소의 임시 WAV 2개는 PCM16 · 24kHz · 모노이고 CLOSING은 3초 이내다(J102)", async () => {
    const closing = inspectWav(
      await readFile(path.join(repoRoot, "infra", "seed-audio", "closing-v1.wav")),
    );
    const filler = inspectWav(
      await readFile(path.join(repoRoot, "infra", "seed-audio", "waiting-filler-v1.wav")),
    );
    expect(closing).toMatchObject({ ok: true, sampleRate: 24_000, channels: 1, bitsPerSample: 16 });
    expect(filler).toMatchObject({ ok: true, sampleRate: 24_000, channels: 1, bitsPerSample: 16 });
    expect(closing.durationMs).toBeGreaterThan(0);
    expect(closing.durationMs).toBeLessThanOrEqual(3000);
  });

  it("RIFF가 아니거나 형식이 다르거나 청크가 넘치면 거부한다", () => {
    const header = (sampleRate, channels) => {
      const wav = Buffer.alloc(44);
      wav.write("RIFF", 0, "ascii");
      wav.writeUInt32LE(36, 4);
      wav.write("WAVE", 8, "ascii");
      wav.write("fmt ", 12, "ascii");
      wav.writeUInt32LE(16, 16);
      wav.writeUInt16LE(1, 20);
      wav.writeUInt16LE(channels, 22);
      wav.writeUInt32LE(sampleRate, 24);
      wav.writeUInt32LE(sampleRate * channels * 2, 28);
      wav.writeUInt16LE(channels * 2, 32);
      wav.writeUInt16LE(16, 34);
      wav.write("data", 36, "ascii");
      wav.writeUInt32LE(0, 40);
      return wav;
    };
    expect(inspectWav(header(24_000, 1))).toMatchObject({ ok: true, dataBytes: 0 });
    expect(inspectWav(Buffer.from("not a wav file"))).toEqual({
      ok: false,
      reason: "NOT_RIFF_WAVE",
    });
    expect(inspectWav(header(48_000, 1))).toEqual({ ok: false, reason: "UNSUPPORTED_FORMAT" });
    expect(inspectWav(header(24_000, 2))).toEqual({ ok: false, reason: "UNSUPPORTED_FORMAT" });
    const overflow = header(24_000, 1);
    overflow.writeUInt32LE(1000, 40);
    expect(inspectWav(overflow)).toEqual({ ok: false, reason: "CHUNK_OVERFLOW" });
    expect(inspectWav(header(24_000, 1).subarray(0, 36))).toEqual({
      ok: false,
      reason: "MISSING_CHUNK",
    });
  });
});
