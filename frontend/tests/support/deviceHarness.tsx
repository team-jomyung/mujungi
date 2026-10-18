import { act, render, type RenderResult } from "@testing-library/react";
import { vi, type Mock } from "vitest";
import { DeviceApp } from "@/device/DeviceApp";
import type { BootstrapResult, BootstrapStatusResult, ClaimResult, DeviceApi, MeResult } from "@/device/api/deviceApi";
import { TimingProvider } from "@/shared/timing/TimingProvider";
import type { Timing } from "@/shared/timing/timing";
import { DEVICE_ID, testTiming } from "./fixtures";

// 아이 기기 웹 시험 틀 (U1 3.5 Step 41): DeviceApp에 각본대로 답하는 기기 API 대역과 줄인 시간 값을 준다.
// 각본의 결과를 차례로 쓰고 다 쓰면 마지막 결과를 되풀이한다.

export interface DeviceScript {
  readonly me?: readonly MeResult[];
  readonly status?: readonly BootstrapStatusResult[];
  readonly bootstrap?: readonly BootstrapResult[];
  readonly claim?: readonly ClaimResult[];
}

export interface ScriptedDeviceApi extends DeviceApi {
  readonly me: Mock<() => Promise<MeResult>>;
  readonly status: Mock<() => Promise<BootstrapStatusResult>>;
  readonly bootstrap: Mock<() => Promise<BootstrapResult>>;
  readonly claim: Mock<() => Promise<ClaimResult>>;
}

function scripted<T>(results: readonly T[] | undefined, fallback: T): Mock<() => Promise<T>> {
  const queue = [...(results ?? [])];
  let last = fallback;
  return vi.fn(async () => {
    const next = queue.shift();
    if (next !== undefined) {
      last = next;
    }
    return last;
  });
}

export function scriptedDeviceApi(script: DeviceScript = {}): ScriptedDeviceApi {
  return {
    me: scripted<MeResult>(script.me, { kind: "unregistered" }),
    status: scripted<BootstrapStatusResult>(script.status, { kind: "notFound" }),
    bootstrap: scripted<BootstrapResult>(script.bootstrap, { kind: "failed" }),
    claim: scripted<ClaimResult>(script.claim, { kind: "failed" }),
  };
}

/** 등록된 기기(me 200) 대역. */
export function registeredDeviceApi(): ScriptedDeviceApi {
  return scriptedDeviceApi({ me: [{ kind: "registered", deviceId: DEVICE_ID }] });
}

export function renderDeviceApp(api: DeviceApi, options: { readonly debug?: boolean; readonly timing?: Partial<Timing> } = {}): RenderResult {
  return render(
    <TimingProvider timing={testTiming(options.timing)}>
      <DeviceApp debug={options.debug ?? false} api={api} />
    </TimingProvider>,
  );
}

/** 가짜 시계를 ms만큼 앞당기고 그 사이의 약속 · React 갱신을 마친다. */
export async function advance(ms: number): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

/** 지금 쌓인 약속 · React 갱신을 마친다(시간은 그대로). */
export async function flush(): Promise<void> {
  await advance(0);
}
