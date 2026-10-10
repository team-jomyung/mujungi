// 기기 WSS 바이너리 음성 프레임 · 합성 PCM (U1 3.5 Step 45 · 계약 4.2 · J27 · BR7.1 · BR7.6 · 편차 (s))
//
//   [0]     종류: 0x01 입력(기기 → 서버) · 0x02 출력(서버 → 기기)
//   [1..4]  프레임 순번 uint32 빅엔디언
//   [5..]   PCM16LE · 24kHz · 모노
//
// 입력 프레임은 20ms = 480표본 = 960바이트라 전체 965바이트다. 출력 프레임 본문은 길이 자유이되 2바이트(표본) 배수다.
// 바이트 규격은 backend AudioFrameCodec · frontend frameCodec.ts와 같은 값이다(시간 설정이 아니라 계약의 형식 상수).
// 이 모듈은 본문을 저장 · 기록하지 않는다. 시험 도구가 만드는 소리는 모두 합성음(톤)이고 개인정보가 없다.
import { createHash } from "node:crypto";

export const FRAME_KIND_INPUT = 0x01;
export const FRAME_KIND_OUTPUT = 0x02;
/** 머리 길이(종류 1 + 순번 4). */
export const FRAME_HEADER_BYTES = 5;
export const SAMPLE_BYTES = 2;
export const SAMPLE_RATE = 24_000;
/** 입력 프레임 한 장의 표본 수(20ms × 24kHz). */
export const INPUT_FRAME_SAMPLES = 480;
export const INPUT_PAYLOAD_BYTES = INPUT_FRAME_SAMPLES * SAMPLE_BYTES;
/** 입력 프레임 전체 길이(965바이트). */
export const INPUT_FRAME_BYTES = FRAME_HEADER_BYTES + INPUT_PAYLOAD_BYTES;
/** 입력 프레임 한 장의 길이(ms). */
export const INPUT_FRAME_MS = 20;
/** 1ms 분량 바이트(24kHz × 2바이트). */
export const BYTES_PER_MS = (SAMPLE_RATE * SAMPLE_BYTES) / 1000;
/** 음성 형식 표기(HELLO.audio · HELLO_OK.audio · 공급자 session.start.audio). */
export const AUDIO_FORMAT = "pcm16le/24000/1";
/** 순번 상한(uint32). */
export const MAX_FRAME_SEQ = 0xffff_ffff;

function toBuffer(data) {
  if (Buffer.isBuffer(data)) {
    return data;
  }
  if (data instanceof ArrayBuffer) {
    return Buffer.from(data);
  }
  if (ArrayBuffer.isView(data)) {
    return Buffer.from(data.buffer, data.byteOffset, data.byteLength);
  }
  if (Array.isArray(data)) {
    // ws는 조각난 메시지를 Buffer 배열로 줄 수 있다
    return Buffer.concat(data.map(toBuffer));
  }
  throw new TypeError("바이너리 프레임은 Buffer · ArrayBuffer · TypedArray여야 한다");
}

/** 프레임 순번 범위 확인. */
export function isValidFrameSeq(seq) {
  return Number.isInteger(seq) && seq >= 0 && seq <= MAX_FRAME_SEQ;
}

/** 다음 순번(uint32 범위를 넘으면 0부터 다시). */
export function nextFrameSeq(seq) {
  return seq >= MAX_FRAME_SEQ ? 0 : seq + 1;
}

function encodeFrame(kind, seq, payload) {
  if (!isValidFrameSeq(seq)) {
    throw new RangeError("프레임 순번이 uint32 범위 밖이다");
  }
  const body = toBuffer(payload);
  const frame = Buffer.alloc(FRAME_HEADER_BYTES + body.length);
  frame.writeUInt8(kind, 0);
  frame.writeUInt32BE(seq, 1);
  body.copy(frame, FRAME_HEADER_BYTES);
  return frame;
}

/** 입력 프레임(0x01, 965바이트). 본문이 정확히 960바이트가 아니면 오류. */
export function encodeInputFrame(seq, payload) {
  const body = toBuffer(payload);
  if (body.length !== INPUT_PAYLOAD_BYTES) {
    throw new RangeError(`입력 프레임 본문은 ${INPUT_PAYLOAD_BYTES}바이트여야 한다(J27)`);
  }
  return encodeFrame(FRAME_KIND_INPUT, seq, body);
}

/** 출력 프레임(0x02). 본문은 비지 않은 2바이트 배수. 시험 대역(가짜 서버)용이다. */
export function encodeOutputFrame(seq, payload) {
  const body = toBuffer(payload);
  if (body.length === 0 || body.length % SAMPLE_BYTES !== 0) {
    throw new RangeError("출력 프레임 본문은 비지 않은 2바이트 배수여야 한다");
  }
  return encodeFrame(FRAME_KIND_OUTPUT, seq, body);
}

/**
 * 바이너리 프레임을 읽는다. 형식이 틀리면 사유만 돌려준다(본문 값은 담지 않는다).
 * @returns {{ok: true, frame: {kind: number, seq: number, payload: Buffer}} | {ok: false, reason: string}}
 */
export function decodeFrame(data) {
  const bytes = toBuffer(data);
  if (bytes.length <= FRAME_HEADER_BYTES) {
    return { ok: false, reason: "TOO_SHORT" };
  }
  const kind = bytes[0];
  if (kind !== FRAME_KIND_INPUT && kind !== FRAME_KIND_OUTPUT) {
    return { ok: false, reason: "UNKNOWN_KIND" };
  }
  if (kind === FRAME_KIND_INPUT && bytes.length !== INPUT_FRAME_BYTES) {
    return { ok: false, reason: "INPUT_LENGTH" };
  }
  const payload = bytes.subarray(FRAME_HEADER_BYTES);
  if (kind === FRAME_KIND_OUTPUT && payload.length % SAMPLE_BYTES !== 0) {
    return { ok: false, reason: "OUTPUT_LENGTH" };
  }
  return { ok: true, frame: { kind, seq: bytes.readUInt32BE(1), payload } };
}

/** 바이트 길이를 재생 시간(ms)으로(24kHz · 16비트 · 모노). */
export function pcmDurationMs(byteLength) {
  return Math.round(byteLength / BYTES_PER_MS);
}

/**
 * 합성 톤 PCM16LE. 같은 인자면 같은 바이트다(난수 · 시각을 쓰지 않는다).
 * @param {number} sampleCount 표본 수
 * @param {{freq?: number, amplitude?: number, startSample?: number}} [options] amplitude는 0 ~ 1(최대값 대비)
 */
export function synthesizeTone(sampleCount, { freq = 220, amplitude = 0.2, startSample = 0 } = {}) {
  if (!Number.isInteger(sampleCount) || sampleCount < 0) {
    throw new RangeError("표본 수는 0 이상의 정수여야 한다");
  }
  if (!(amplitude >= 0 && amplitude <= 1)) {
    throw new RangeError("진폭은 0 ~ 1이어야 한다");
  }
  const out = Buffer.alloc(sampleCount * SAMPLE_BYTES);
  for (let i = 0; i < sampleCount; i += 1) {
    const value = Math.round(Math.sin((2 * Math.PI * freq * (startSample + i)) / SAMPLE_RATE) * amplitude * 32_767);
    out.writeInt16LE(value, i * SAMPLE_BYTES);
  }
  return out;
}

/** 입력 프레임 본문(960바이트) 하나. index는 세션 안 프레임 번호(0부터)라 프레임마다 위상이 이어진다. */
export function synthesizeInputPayload(index, { freq = 220, amplitude = 0.2 } = {}) {
  return synthesizeTone(INPUT_FRAME_SAMPLES, { freq, amplitude, startSample: index * INPUT_FRAME_SAMPLES });
}

/** SHA-256 16진. */
export function sha256Hex(data) {
  return createHash("sha256").update(toBuffer(data)).digest("hex");
}

/** 여러 조각을 순서대로 이어 붙인 SHA-256 16진(조각 경계와 무관하게 같은 값). */
export function sha256OfChunks(chunks) {
  const hash = createHash("sha256");
  for (const chunk of chunks) {
    hash.update(toBuffer(chunk));
  }
  return hash.digest("hex");
}

/** 이어 붙이는 해시(조각을 받을 때마다 update, 아무 때나 지금까지의 값을 본다). */
export class RunningHash {
  #hash = createHash("sha256");
  #bytes = 0;
  #chunks = 0;

  update(data) {
    const bytes = toBuffer(data);
    this.#hash.update(bytes);
    this.#bytes += bytes.length;
    this.#chunks += 1;
    return this;
  }

  get bytes() {
    return this.#bytes;
  }

  get chunks() {
    return this.#chunks;
  }

  /** 지금까지의 SHA-256 16진(해시 상태는 그대로 이어진다). */
  hex() {
    return this.#hash.copy().digest("hex");
  }
}

/**
 * WAV(RIFF · fmt · data)를 읽어 형식과 본문 길이를 돌려준다. 승인 음성 미리 받기 확인용(PCM16 · 24kHz · 모노만 OK).
 * @returns {{ok: true, sampleRate: number, channels: number, bitsPerSample: number, dataBytes: number, durationMs: number} | {ok: false, reason: string}}
 */
export function inspectWav(data) {
  const bytes = toBuffer(data);
  if (bytes.length < 12 || bytes.toString("ascii", 0, 4) !== "RIFF" || bytes.toString("ascii", 8, 12) !== "WAVE") {
    return { ok: false, reason: "NOT_RIFF_WAVE" };
  }
  let offset = 12;
  let format = null;
  let dataBytes = null;
  while (offset + 8 <= bytes.length) {
    const id = bytes.toString("ascii", offset, offset + 4);
    const size = bytes.readUInt32LE(offset + 4);
    const body = offset + 8;
    if (body + size > bytes.length) {
      return { ok: false, reason: "CHUNK_OVERFLOW" };
    }
    if (id === "fmt " && size >= 16) {
      format = {
        audioFormat: bytes.readUInt16LE(body),
        channels: bytes.readUInt16LE(body + 2),
        sampleRate: bytes.readUInt32LE(body + 4),
        bitsPerSample: bytes.readUInt16LE(body + 14),
      };
    } else if (id === "data") {
      dataBytes = size;
    }
    offset = body + size + (size % 2);
  }
  if (format === null || dataBytes === null) {
    return { ok: false, reason: "MISSING_CHUNK" };
  }
  if (format.audioFormat !== 1 || format.channels !== 1 || format.sampleRate !== SAMPLE_RATE || format.bitsPerSample !== 16) {
    return { ok: false, reason: "UNSUPPORTED_FORMAT" };
  }
  return {
    ok: true,
    sampleRate: format.sampleRate,
    channels: format.channels,
    bitsPerSample: format.bitsPerSample,
    dataBytes,
    durationMs: pcmDurationMs(dataBytes),
  };
}
