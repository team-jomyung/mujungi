// 마이크 캡처 AudioWorklet 등록부 (U1 3.5 Step 38 · J121 · J138)
//
// AudioWorklet 전역(AudioWorkletProcessor · registerProcessor)은 jsdom에서 실행할 수 없으므로 처리기는 받은 표본을 복사해
// 메인 스레드로 넘기기만 하는 얇은 코드로 둔다. 비율 변환 · 480표본 프레임 조립은 resample.ts의 순수 함수가 메인 스레드에서 한다.
// 처리기 코드는 같은 origin의 라우트(/device/pcm-capture-worklet)가 이 문자열을 JavaScript로 내려 주고
// audioWorklet.addModule이 그 주소를 읽는다(CSP script-src 'self' · nonce · 'strict-dynamic', 호환은 X-6로 3.6 실기에서 확인).

/** registerProcessor 이름. */
export const PCM_CAPTURE_PROCESSOR_NAME = "mujoong-pcm-capture";

/** 처리기 코드를 내려 주는 같은 origin 경로(src/app/device/pcm-capture-worklet/route.ts). */
export const PCM_CAPTURE_WORKLET_PATH = "/device/pcm-capture-worklet";

/**
 * 처리기 코드. 첫 입력의 첫 채널 조각(보통 128표본)을 복사해 port로 보낸다. 출력에는 아무것도 쓰지 않아
 * 마이크 소리가 스피커로 나가지 않는다.
 */
export const PCM_CAPTURE_WORKLET_SOURCE = `"use strict";
class MujoongPcmCaptureProcessor extends AudioWorkletProcessor {
  process(inputs) {
    const input = inputs[0];
    const channel = input && input[0];
    if (channel && channel.length > 0) {
      const copy = channel.slice(0);
      this.port.postMessage(copy, [copy.buffer]);
    }
    return true;
  }
}
registerProcessor(${JSON.stringify(PCM_CAPTURE_PROCESSOR_NAME)}, MujoongPcmCaptureProcessor);
`;
