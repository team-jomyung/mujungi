import vm from "node:vm";
import { describe, expect, it, vi, type Mock } from "vitest";
import { startCapture } from "@/device/audio/capture";
import { PCM_CAPTURE_PROCESSOR_NAME, PCM_CAPTURE_WORKLET_SOURCE } from "@/device/audio/pcm-capture.worklet";
import { INPUT_FRAME_SAMPLES } from "@/shared/protocol/frameCodec";
import { FakeAudioContext, FakeWorkletNode, fakeStream } from "../support/fakeAudio";

// AudioWorklet 처리기 코드 시험 (U1 3.5 Step 41 · J121 · J138 · BR7.1)
// 처리기 코드는 라우트가 내려 주는 문자열이라 커버리지 도구가 보지 못한다. 여기서는 그 문자열을 window · document가 없는
// 별도 전역(node:vm)에서 AudioWorkletProcessor · registerProcessor 대역만 주고 실제로 실행해 동작을 확인한다.

type ProcessFn = (inputs: Float32Array[][], outputs: Float32Array[][], parameters: Record<string, Float32Array>) => boolean;

interface ProcessorInstance {
  readonly port: { postMessage: Mock<(message: unknown, transfer?: Transferable[]) => void> };
  process: ProcessFn;
}

interface LoadedWorklet {
  readonly registrations: Array<{ name: string; processor: unknown }>;
  readonly BaseProcessor: new () => { port: ProcessorInstance["port"] };
  create(): ProcessorInstance;
}

function loadWorklet(): LoadedWorklet {
  const registrations: Array<{ name: string; processor: unknown }> = [];
  class BaseProcessor {
    readonly port = { postMessage: vi.fn<(message: unknown, transfer?: Transferable[]) => void>() };
  }
  const scope = vm.createContext({
    AudioWorkletProcessor: BaseProcessor,
    registerProcessor: (name: string, processor: unknown) => {
      registrations.push({ name, processor });
    },
  });
  vm.runInContext(PCM_CAPTURE_WORKLET_SOURCE, scope, { filename: "pcm-capture-worklet.js" });
  return {
    registrations,
    BaseProcessor,
    create() {
      const Processor = registrations[0].processor as new () => ProcessorInstance;
      return new Processor();
    },
  };
}

function ramp(length: number, start = 0.25): Float32Array {
  return Float32Array.from({ length }, (_, index) => start + index / (length * 4));
}

describe("pcm-capture worklet — 등록", () => {
  it("AudioWorkletProcessor를 잇는 처리기 하나를 PCM_CAPTURE_PROCESSOR_NAME으로 등록한다", () => {
    const worklet = loadWorklet();
    expect(worklet.registrations).toHaveLength(1);
    expect(worklet.registrations[0].name).toBe(PCM_CAPTURE_PROCESSOR_NAME);
    expect(worklet.create()).toBeInstanceOf(worklet.BaseProcessor);
  });
});

describe("pcm-capture worklet — process", () => {
  it("첫 입력 첫 채널 조각을 복사해 그 버퍼를 넘기는 목록과 함께 port로 보내고 true를 돌려준다", () => {
    const processor = loadWorklet().create();
    const channel = ramp(128);
    expect(processor.process([[channel]], [[new Float32Array(128)]], {})).toBe(true);
    expect(processor.port.postMessage).toHaveBeenCalledTimes(1);
    const [message, transfer] = processor.port.postMessage.mock.calls[0];
    expect(message).toBeInstanceOf(Float32Array);
    const copy = message as Float32Array;
    expect(Array.from(copy)).toEqual(Array.from(channel));
    // 원본 입력 버퍼가 아니라 복사본의 버퍼만 넘긴다(브라우저가 재사용하는 입력 버퍼를 떼어 가지 않음).
    expect(copy.buffer).not.toBe(channel.buffer);
    expect(transfer).toEqual([copy.buffer]);
  });

  it("입력이 없거나(연결 전) 채널이 없거나 빈 조각이면 보내지 않고 true로 처리기를 살려 둔다", () => {
    const processor = loadWorklet().create();
    expect(processor.process([], [], {})).toBe(true);
    expect(processor.process([[]], [[new Float32Array(128)]], {})).toBe(true);
    expect(processor.process([[new Float32Array(0)]], [[new Float32Array(128)]], {})).toBe(true);
    expect(processor.port.postMessage).not.toHaveBeenCalled();
  });

  it("여러 채널 입력이면 첫 채널만 보낸다(모노 24kHz 전송, J27)", () => {
    const processor = loadWorklet().create();
    const left = ramp(128, 0.1);
    const right = ramp(128, -0.9);
    processor.process([[left, right]], [[new Float32Array(128)]], {});
    expect(Array.from(processor.port.postMessage.mock.calls[0][0] as Float32Array)).toEqual(Array.from(left));
  });

  it("출력 버퍼에 아무것도 쓰지 않아 마이크 소리가 스피커로 나가지 않는다", () => {
    const processor = loadWorklet().create();
    const output = new Float32Array(128);
    processor.process([[ramp(128)]], [[output]], {});
    expect(output.every((sample) => sample === 0)).toBe(true);
  });
});

describe("pcm-capture worklet ↔ startCapture", () => {
  it("처리기가 보낸 128표본 조각 4개를 메인 스레드가 받아 24kHz 480표본 프레임 1개를 만든다", async () => {
    const processor = loadWorklet().create();
    const node = new FakeWorkletNode();
    const context = new FakeAudioContext(24_000);
    const pipeline = await startCapture(context.asAudioContext(), fakeStream().stream, { createWorkletNode: () => node.asNode() });
    const frames: Int16Array[] = [];
    pipeline.setFrameHandler((frame) => frames.push(frame));
    // 실제 브라우저에서는 port 메시지가 AudioWorkletNode.port로 건너간다. 여기서는 그 다리를 대역으로 잇는다.
    processor.port.postMessage.mockImplementation((message) => node.emit(message));
    for (let index = 0; index < 4; index += 1) {
      processor.process([[new Float32Array(128).fill(0.5)]], [[new Float32Array(128)]], {});
    }
    expect(frames).toHaveLength(1);
    expect(frames[0]).toHaveLength(INPUT_FRAME_SAMPLES);
    expect(frames[0].every((sample) => sample === 16384)).toBe(true);
  });
});
