import { createCaptureConverter, type CaptureConverter } from "./resample";
import { PCM_CAPTURE_PROCESSOR_NAME, PCM_CAPTURE_WORKLET_PATH } from "./pcm-capture.worklet";

// 마이크 캡처 연결 (U1 3.5 Step 38 · J121 · BR7.1 · NFR10.4)
// 마이크 스트림(「켜기」 클릭 뒤 micPermission.ts가 받은 것) → MediaStreamSource → AudioWorklet(표본 복사) → 메인 스레드
// 변환기(문맥 비율 → 24kHz, 480표본 프레임) → onFrame. 송신 여부(DEVICE_READY 뒤 ~ CLOSE_TRANSPORT 전)는 deviceSession이 정한다.
// 원음은 메모리에서만 흘러가고 저장 · 기록하지 않는다(BR7.1).

export interface CapturePipeline {
  /** 프레임을 받을 함수를 바꾼다(null이면 버린다). */
  setFrameHandler(handler: ((frame: Int16Array) => void) | null): void;
  /** 모아 둔 표본을 버린다(세션 경계). */
  resetBuffer(): void;
  /** 그래프를 끊고 마이크 트랙을 멈춘다. AudioContext는 닫지 않는다(재생과 함께 쓰므로 런타임이 닫는다). */
  stop(): void;
  readonly active: boolean;
}

export interface CaptureDependencies {
  /** 처리기 노드를 만든다(시험 대역 주입용, 기본은 AudioWorkletNode). */
  readonly createWorkletNode?: (context: AudioContext) => AudioWorkletNode;
  /** 처리기 코드 주소(기본은 같은 origin 라우트). */
  readonly workletUrl?: string;
}

function defaultWorkletNode(context: AudioContext): AudioWorkletNode {
  return new AudioWorkletNode(context, PCM_CAPTURE_PROCESSOR_NAME, {
    numberOfInputs: 1,
    numberOfOutputs: 1,
    outputChannelCount: [1],
    channelCount: 1,
    channelCountMode: "explicit",
  });
}

/**
 * 캡처를 시작한다. 처리기 적재가 실패하면 예외를 던지고 호출자는 마이크를 UNAVAILABLE로 본다.
 */
export async function startCapture(
  context: AudioContext,
  stream: MediaStream,
  dependencies: CaptureDependencies = {},
): Promise<CapturePipeline> {
  await context.audioWorklet.addModule(dependencies.workletUrl ?? PCM_CAPTURE_WORKLET_PATH);
  const source = context.createMediaStreamSource(stream);
  const node = (dependencies.createWorkletNode ?? defaultWorkletNode)(context);
  const converter: CaptureConverter = createCaptureConverter(context.sampleRate);
  let handler: ((frame: Int16Array) => void) | null = null;
  let active = true;

  node.port.onmessage = (event: MessageEvent<unknown>) => {
    if (!active || !(event.data instanceof Float32Array)) {
      return;
    }
    const frames = converter.push(event.data);
    if (handler === null) {
      return;
    }
    for (const frame of frames) {
      handler(frame);
    }
  };
  source.connect(node);
  // 일부 브라우저는 출력이 목적지에 이어지지 않은 노드를 처리하지 않는다. 처리기는 출력에 아무것도 쓰지 않는다.
  node.connect(context.destination);

  return {
    setFrameHandler(next) {
      handler = next;
    },
    resetBuffer() {
      converter.reset();
    },
    stop() {
      if (!active) {
        return;
      }
      active = false;
      handler = null;
      node.port.onmessage = null;
      source.disconnect();
      node.disconnect();
      for (const track of stream.getTracks()) {
        track.stop();
      }
    },
    get active() {
      return active;
    },
  };
}
