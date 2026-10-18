import { fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createAudioContextInGesture } from "@/device/audio/audioContext";
import { startCapture } from "@/device/audio/capture";
import { PCM_CAPTURE_PROCESSOR_NAME, PCM_CAPTURE_WORKLET_PATH } from "@/device/audio/pcm-capture.worklet";
import { INPUT_FRAME_SAMPLES } from "@/shared/protocol/frameCodec";
import { FakeAudioContext, FakeWorkletNode, fakeStream } from "../../support/fakeAudio";
import { flush, registeredDeviceApi, renderDeviceApp } from "../../support/deviceHarness";

// 마이크 캡처 시험 (U1 3.5 Step 41 · J121 · J138 · BR7.1 · NFR10.4)
// AudioContext는 「켜기」 클릭 핸들러 안에서 만들고(24kHz를 먼저 청함), 거부되면 기본 비율 + 선형 보간 경로,
// worklet은 표본만 넘기고 메인 스레드가 480표본 프레임을 만든다. 멈추면 마이크 트랙을 놓는다.

async function capture(sampleRate: number) {
  const context = new FakeAudioContext(sampleRate);
  const node = new FakeWorkletNode();
  const { stream, tracks } = fakeStream(2);
  const pipeline = await startCapture(context.asAudioContext(), stream, { createWorkletNode: () => node.asNode() });
  const frames: Int16Array[] = [];
  pipeline.setFrameHandler((frame) => frames.push(frame));
  return { context, node, stream, tracks, pipeline, frames };
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("startCapture — 그래프와 프레임", () => {
  it("같은 origin worklet을 적재하고 마이크 → 처리기 → 목적지로 잇는다", async () => {
    const { context, node, stream } = await capture(24_000);
    expect(context.audioWorklet.addModule).toHaveBeenCalledWith(PCM_CAPTURE_WORKLET_PATH);
    expect(context.createMediaStreamSource).toHaveBeenCalledWith(stream);
    expect(context.mediaSources[0].connect).toHaveBeenCalledWith(node);
    expect(node.connect).toHaveBeenCalledWith(context.destination);
  });

  it("48kHz 문맥(24kHz 거부)이면 128표본 조각 8개(1024)에서 24kHz 480표본 프레임 1개를 만든다", async () => {
    const { node, frames } = await capture(48_000);
    for (let index = 0; index < 7; index += 1) {
      node.emit(new Float32Array(128).fill(0.1));
    }
    expect(frames).toHaveLength(0);
    node.emit(new Float32Array(128).fill(0.1));
    expect(frames).toHaveLength(1);
    expect(frames[0]).toHaveLength(INPUT_FRAME_SAMPLES);
  });

  it("24kHz 문맥이면 4조각(512)에서 변환 없이 프레임 1개다", async () => {
    const { node, frames } = await capture(24_000);
    for (let index = 0; index < 4; index += 1) {
      node.emit(new Float32Array(128).fill(-1));
    }
    expect(frames).toHaveLength(1);
    expect(frames[0].every((sample) => sample === -32768)).toBe(true);
  });

  it("Float32Array가 아닌 메시지는 버리고, 받을 곳이 없으면 프레임을 흘려 보낸다", async () => {
    const { node, frames, pipeline } = await capture(24_000);
    node.emit("not audio");
    node.emit(new Int16Array(480));
    pipeline.setFrameHandler(null);
    node.emit(new Float32Array(480));
    expect(frames).toHaveLength(0);
  });

  it("resetBuffer는 모은 표본을 버린다(세션 경계)", async () => {
    const { node, frames, pipeline } = await capture(24_000);
    node.emit(new Float32Array(400).fill(1));
    pipeline.resetBuffer();
    node.emit(new Float32Array(480).fill(-1));
    expect(frames).toHaveLength(1);
    expect(frames[0][0]).toBe(-32768);
  });

  it("stop은 그래프를 끊고 마이크 트랙을 모두 멈추며 두 번 불러도 한 번만 한다", async () => {
    const { node, frames, pipeline, tracks, context } = await capture(24_000);
    pipeline.stop();
    pipeline.stop();
    expect(pipeline.active).toBe(false);
    expect(tracks.every((track) => track.stop.mock.calls.length === 1)).toBe(true);
    expect(node.disconnect).toHaveBeenCalledTimes(1);
    expect(context.mediaSources[0].disconnect).toHaveBeenCalledTimes(1);
    expect(node.port.onmessage).toBeNull();
    expect(context.close).not.toHaveBeenCalled();
    expect(frames).toHaveLength(0);
  });

  it("worklet 적재가 실패하면 예외를 던져 호출자가 마이크를 UNAVAILABLE로 본다", async () => {
    const context = new FakeAudioContext(48_000);
    context.audioWorklet.addModule.mockRejectedValueOnce(new Error("blocked by CSP"));
    await expect(startCapture(context.asAudioContext(), fakeStream().stream)).rejects.toThrow("blocked by CSP");
    expect(context.createMediaStreamSource).not.toHaveBeenCalled();
  });

  it("기본 처리기 노드는 이름 · 모노 채널 설정으로 만든다", async () => {
    const created: FakeWorkletNode[] = [];
    vi.stubGlobal(
      "AudioWorkletNode",
      class extends FakeWorkletNode {
        constructor(context: unknown, name: string, options: AudioWorkletNodeOptions) {
          super(context, name, options);
          created.push(this);
        }
      },
    );
    const context = new FakeAudioContext(24_000);
    await startCapture(context.asAudioContext(), fakeStream().stream);
    expect(created).toHaveLength(1);
    expect(created[0].name).toBe(PCM_CAPTURE_PROCESSOR_NAME);
    expect(created[0].options).toMatchObject({ numberOfInputs: 1, channelCount: 1, channelCountMode: "explicit", outputChannelCount: [1] });
  });
});

describe("createAudioContextInGesture — 24kHz 문맥 요청(J121)", () => {
  it("24kHz · interactive로 먼저 만든다", () => {
    const calls: Array<AudioContextOptions | undefined> = [];
    class Accepting {
      constructor(options?: AudioContextOptions) {
        calls.push(options);
      }
    }
    expect(createAudioContextInGesture(Accepting as unknown as typeof AudioContext)).toBeInstanceOf(Accepting);
    expect(calls).toEqual([{ sampleRate: 24_000, latencyHint: "interactive" }]);
  });

  it("24kHz를 거부하면 기본 비율로 만들고(보간 경로), 둘 다 안 되거나 없으면 null이다", () => {
    const calls: Array<AudioContextOptions | undefined> = [];
    class RejectsRate {
      constructor(options?: AudioContextOptions) {
        calls.push(options);
        if (options?.sampleRate !== undefined) {
          throw new Error("NotSupportedError");
        }
      }
    }
    expect(createAudioContextInGesture(RejectsRate as unknown as typeof AudioContext)).toBeInstanceOf(RejectsRate);
    expect(calls[1]).toEqual({ latencyHint: "interactive" });
    class AlwaysRejects {
      constructor() {
        throw new Error("no audio");
      }
    }
    expect(createAudioContextInGesture(AlwaysRejects as unknown as typeof AudioContext)).toBeNull();
    expect(createAudioContextInGesture(undefined)).toBeNull();
  });
});

describe("DeviceApp — AudioContext는 「켜기」 클릭 핸들러 안에서만 만든다(J121 · NFR10.4)", () => {
  beforeEach(async () => {
    // 클릭 뒤 동적 import가 모듈 캐시에서 바로 풀리게 미리 적재해 둔다(시험 결정성).
    await import("@/device/mic/micPermission");
    await import("@/device/runtime/deviceRuntime");
    vi.useFakeTimers();
  });

  it("클릭 전에는 0개, 클릭하는 그 순간 24kHz로 1개를 만들고 마이크를 못 받으면 닫는다", async () => {
    const constructed: Array<AudioContextOptions | undefined> = [];
    const closed = vi.fn(async () => undefined);
    vi.stubGlobal(
      "AudioContext",
      class {
        state = "running";
        constructor(options?: AudioContextOptions) {
          constructed.push(options);
        }
        resume = vi.fn(async () => undefined);
        close = closed;
      },
    );
    renderDeviceApp(registeredDeviceApi());
    await flush();
    expect(constructed).toHaveLength(0);
    fireEvent.click(screen.getByTestId("t19-on"));
    expect(constructed).toEqual([{ sampleRate: 24_000, latencyHint: "interactive" }]);
    await flush();
    // jsdom에는 mediaDevices가 없어 마이크를 받지 못한다 → 문맥을 닫고 '마이크 허용 안 됨'.
    expect(closed).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "mic-denied");
  });
});
