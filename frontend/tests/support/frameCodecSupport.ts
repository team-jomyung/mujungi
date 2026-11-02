import { FRAME_HEADER_BYTES, FRAME_KIND_OUTPUT, SAMPLE_BYTES, isValidFrameSeq } from "@/shared/protocol/frameCodec";

// 시험 대역(가짜 서버)이 기기로 보내는 출력 프레임(0x02)을 만든다. 기기 운영 코드는 출력 프레임을 만들지 않으므로 시험 지원 코드에 둔다.

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
