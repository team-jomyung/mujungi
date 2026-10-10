// 기기 WSS 바이너리 음성 프레임 (U1 3.5 Step 37 · 계약 4.2 · J27 · BR7.1 · BR7.6 · 편차 (s))
//
//   [0]     종류: 0x01 입력(기기 → 서버) · 0x02 출력(서버 → 기기)
//   [1..4]  프레임 순번 uint32 빅엔디언
//   [5..]   PCM16LE · 24kHz · 모노
//
// 입력 프레임은 20ms = 480표본 = 960바이트라 전체 965바이트다. 출력 프레임 본문은 길이 자유이되 2바이트(표본) 배수다.
// 형식 상수는 시간 설정이 아니라 계약의 바이트 규격이다(backend AudioFrameCodec과 같은 값). 이 모듈은 본문을 저장 · 기록하지 않는다.

export const FRAME_KIND_INPUT = 0x01;
export const FRAME_KIND_OUTPUT = 0x02;
export type FrameKind = typeof FRAME_KIND_INPUT | typeof FRAME_KIND_OUTPUT;

/** 머리 길이(종류 1 + 순번 4). */
export const FRAME_HEADER_BYTES = 5;
/** PCM16 표본 크기. */
export const SAMPLE_BYTES = 2;
/** 입력 프레임 한 장의 표본 수(20ms × 24kHz). */
export const INPUT_FRAME_SAMPLES = 480;
/** 입력 프레임 본문 길이. */
export const INPUT_PAYLOAD_BYTES = INPUT_FRAME_SAMPLES * SAMPLE_BYTES;
/** 입력 프레임 전체 길이(965바이트). */
export const INPUT_FRAME_BYTES = FRAME_HEADER_BYTES + INPUT_PAYLOAD_BYTES;
/** 음성 형식 표기(HELLO.audio · HELLO_OK.audio). */
export const AUDIO_FORMAT = "pcm16le/24000/1";
/** 음성 표본 비율. */
export const AUDIO_SAMPLE_RATE = 24_000;
/** 순번 상한(uint32). */
export const MAX_FRAME_SEQ = 0xffff_ffff;

export interface DecodedFrame {
  readonly kind: FrameKind;
  readonly seq: number;
  /** PCM16LE 본문(원본 버퍼의 보기, 복사하지 않음). */
  readonly payload: Uint8Array;
}

export type FrameRejectReason = "TOO_SHORT" | "UNKNOWN_KIND" | "INPUT_LENGTH" | "OUTPUT_LENGTH";

export type FrameDecodeResult =
  | { readonly ok: true; readonly frame: DecodedFrame }
  | { readonly ok: false; readonly reason: FrameRejectReason };

function toBytes(data: ArrayBuffer | ArrayBufferView): Uint8Array {
  if (data instanceof Uint8Array) {
    return data;
  }
  if (ArrayBuffer.isView(data)) {
    return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  }
  return new Uint8Array(data);
}

/** 바이너리 프레임을 읽는다. 형식이 틀리면 사유만 돌려준다(본문 값은 담지 않는다). */
export function decodeFrame(data: ArrayBuffer | ArrayBufferView): FrameDecodeResult {
  const bytes = toBytes(data);
  if (bytes.byteLength <= FRAME_HEADER_BYTES) {
    return { ok: false, reason: "TOO_SHORT" };
  }
  const kind = bytes[0];
  if (kind !== FRAME_KIND_INPUT && kind !== FRAME_KIND_OUTPUT) {
    return { ok: false, reason: "UNKNOWN_KIND" };
  }
  if (kind === FRAME_KIND_INPUT && bytes.byteLength !== INPUT_FRAME_BYTES) {
    return { ok: false, reason: "INPUT_LENGTH" };
  }
  const payloadLength = bytes.byteLength - FRAME_HEADER_BYTES;
  if (kind === FRAME_KIND_OUTPUT && payloadLength % SAMPLE_BYTES !== 0) {
    return { ok: false, reason: "OUTPUT_LENGTH" };
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const seq = view.getUint32(1, false);
  const payload = bytes.subarray(FRAME_HEADER_BYTES);
  return { ok: true, frame: { kind, seq, payload } };
}

/** 표본 배열을 PCM16LE 바이트로 바꾼다(플랫폼 바이트 순서와 무관). */
export function pcm16ToLittleEndianBytes(samples: Int16Array): Uint8Array {
  const bytes = new Uint8Array(samples.length * SAMPLE_BYTES);
  const view = new DataView(bytes.buffer);
  for (let index = 0; index < samples.length; index += 1) {
    view.setInt16(index * SAMPLE_BYTES, samples[index], true);
  }
  return bytes;
}

/** PCM16LE 바이트를 −1.0 ~ 1.0 부동소수 표본으로 바꾼다(재생용). 홀수 끝 바이트는 버린다. */
export function littleEndianPcm16ToFloat32(payload: Uint8Array): Float32Array<ArrayBuffer> {
  const sampleCount = Math.floor(payload.byteLength / SAMPLE_BYTES);
  const view = new DataView(payload.buffer, payload.byteOffset, sampleCount * SAMPLE_BYTES);
  const out = new Float32Array(sampleCount);
  for (let index = 0; index < sampleCount; index += 1) {
    const sample = view.getInt16(index * SAMPLE_BYTES, true);
    out[index] = sample < 0 ? sample / 0x8000 : sample / 0x7fff;
  }
  return out;
}

/** 프레임 순번 범위 확인. */
export function isValidFrameSeq(seq: number): boolean {
  return Number.isInteger(seq) && seq >= 0 && seq <= MAX_FRAME_SEQ;
}

/** 다음 순번(uint32 범위를 넘으면 0부터 다시). */
export function nextFrameSeq(seq: number): number {
  return seq >= MAX_FRAME_SEQ ? 0 : seq + 1;
}

/** 입력 프레임(0x01, 965바이트)을 만든다. 표본이 정확히 480개가 아니면 오류. */
export function encodeInputFrame(seq: number, samples: Int16Array): Uint8Array {
  if (samples.length !== INPUT_FRAME_SAMPLES) {
    throw new RangeError(`입력 프레임은 표본 ${INPUT_FRAME_SAMPLES}개여야 한다(J27)`);
  }
  if (!isValidFrameSeq(seq)) {
    throw new RangeError("프레임 순번이 uint32 범위 밖이다");
  }
  const frame = new Uint8Array(INPUT_FRAME_BYTES);
  const view = new DataView(frame.buffer);
  view.setUint8(0, FRAME_KIND_INPUT);
  view.setUint32(1, seq, false);
  frame.set(pcm16ToLittleEndianBytes(samples), FRAME_HEADER_BYTES);
  return frame;
}

/** 출력 프레임(0x02)을 만든다. 시험 대역(가짜 서버)용이며 기기는 보내지 않는다. */
export function encodeOutputFrame(seq: number, payload: Uint8Array): Uint8Array {
  if (payload.byteLength === 0 || payload.byteLength % SAMPLE_BYTES !== 0) {
    throw new RangeError("출력 프레임 본문은 비지 않은 2바이트 배수여야 한다");
  }
  if (!isValidFrameSeq(seq)) {
    throw new RangeError("프레임 순번이 uint32 범위 밖이다");
  }
  const frame = new Uint8Array(FRAME_HEADER_BYTES + payload.byteLength);
  const view = new DataView(frame.buffer);
  view.setUint8(0, FRAME_KIND_OUTPUT);
  view.setUint32(1, seq, false);
  frame.set(payload, FRAME_HEADER_BYTES);
  return frame;
}
