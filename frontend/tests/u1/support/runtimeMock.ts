import { vi } from "vitest";
import type { MicResult } from "@/device/mic/micPermission";
import type { DeviceRuntime, DeviceRuntimeEvents, DeviceRuntimeOptions } from "@/device/runtime/deviceRuntime";
import { fakeStream } from "./fakeAudio";

// DeviceApp 시험용 실행부 · 마이크 모듈 대역 (U1 3.5 Step 41)
// 시험 파일은 vi.mock("@/device/runtime/deviceRuntime", async () => (await import("../support/runtimeMock")).deviceRuntimeModule)
// · vi.mock("@/device/mic/micPermission", …micPermissionModule)로 쓰고, runtimeControl로 연결 사건을 일으킨다.

export const runtimeControl = {
  options: null as DeviceRuntimeOptions | null,
  runtime: {
    connect: vi.fn<() => void>(),
    shutdown: vi.fn<(closeSocket: boolean) => void>(),
  } satisfies DeviceRuntime,
  startError: null as Error | null,
  micResult: { granted: true, stream: fakeStream().stream } as MicResult,
  reset(): void {
    this.options = null;
    this.startError = null;
    this.micResult = { granted: true, stream: fakeStream().stream };
    this.runtime.connect.mockReset();
    this.runtime.shutdown.mockReset();
    deviceRuntimeModule.startDeviceRuntime.mockClear();
    micPermissionModule.requestMicrophone.mockClear();
  },
  /** 실행부가 받은 사건 함수(켜기 뒤에만 있다). */
  events(): DeviceRuntimeEvents {
    if (this.options === null) {
      throw new Error("실행부가 아직 시작되지 않았다");
    }
    return this.options.events;
  },
};

export const deviceRuntimeModule = {
  DEVICE_APP_VERSION: "mujoong-device-web/test",
  startDeviceRuntime: vi.fn(async (options: DeviceRuntimeOptions): Promise<DeviceRuntime> => {
    if (runtimeControl.startError !== null) {
      throw runtimeControl.startError;
    }
    runtimeControl.options = options;
    return runtimeControl.runtime;
  }),
};

export const micPermissionModule = {
  requestMicrophone: vi.fn(async (): Promise<MicResult> => runtimeControl.micResult),
};
