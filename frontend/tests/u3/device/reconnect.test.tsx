import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { StatusView } from "@/device/features/status/StatusView";
import { deviceReducer, displayStateOf, initialDeviceState, pendingEffect, reconnectDelayMs, type DeviceEvent, type DeviceState } from "@/device/machine/deviceMachine";
import { DEVICE_ID } from "../../u1/support/fixtures";
import { advance, flush, registeredDeviceApi, renderDeviceApp } from "../../u1/support/deviceHarness";
import { runtimeControl } from "../../u1/support/runtimeMock";

vi.mock("@/device/runtime/deviceRuntime", async () => (await import("../../u1/support/runtimeMock")).deviceRuntimeModule);
vi.mock("@/device/mic/micPermission", async () => (await import("../../u1/support/runtimeMock")).micPermissionModule);

// 재접속 간격 · '연결 끊김' 화면 시험 (U3-R16 · U3-J19 · AC6.2.6 · C.D-19.31.7 · mockups 9-31)
// 한 번 연결됐던 기기는 1 · 2 · 4초, 그 뒤 5초마다 다시 접속하고 '연결 끊김' · "다시 연결하고 있어요"를 보인다. HELLO_OK면 처음부터 센다.

const run = (state: DeviceState, ...events: DeviceEvent[]) => events.reduce(deviceReducer, state);
const timer = (state: DeviceState): DeviceState => deviceReducer(state, { type: "TIMER_FIRED", stepId: state.stepId });
const ready = (state: DeviceState): DeviceState => deviceReducer(state, { type: "WS_READY", stepId: state.stepId });
const attempt = (state: DeviceState) => (state.screen === "t19" ? state.reconnectAttempt : -1);
const closed: DeviceEvent = { type: "WS_CLOSED", code: 1006, closedByDevice: false };

function poweredOn(): DeviceState {
  const off = run(initialDeviceState(), { type: "ME_RESULT", stepId: 1, result: { kind: "registered", deviceId: DEVICE_ID } });
  return ready(run(off, { type: "TURN_ON" }, { type: "MIC_RESULT", granted: true }));
}

beforeEach(() => {
  vi.useFakeTimers();
  runtimeControl.reset();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("재접속 간격(U3-R16)", () => {
  it("간격 목록의 자리를 쓰고 다 쓰면 재시도 간격이다", () => {
    const backoff = [1_000, 2_000, 4_000];
    expect([0, 1, 2, 3, 9].map((value) => reconnectDelayMs(value, backoff, 5_000))).toEqual([1_000, 2_000, 4_000, 5_000, 5_000]);
    expect(reconnectDelayMs(-1, backoff, 5_000)).toBe(5_000);
    expect(reconnectDelayMs(0, [], 5_000)).toBe(5_000);
  });

  it("실패한 접속 시도마다 자리가 늘고 HELLO_OK에서 0이 된다", () => {
    let state = run(poweredOn(), closed);
    expect(attempt(state)).toBe(0);
    expect(pendingEffect(state)).toEqual({ kind: "delay", delay: "reconnect" });
    state = timer(state);
    expect(pendingEffect(state)).toEqual({ kind: "connect" });
    expect(attempt(state)).toBe(0);
    state = run(state, closed);
    expect(attempt(state)).toBe(1);
    state = run(timer(state), closed);
    expect(attempt(state)).toBe(2);
    state = ready(timer(state));
    expect(displayStateOf(state)).toBe("standby");
    expect(attempt(state)).toBe(0);
    state = run(state, closed);
    expect(attempt(state)).toBe(0);
  });
});

describe("DeviceApp — '연결 끊김'과 늘어나는 재접속 간격(U3-R16 · U3-J19)", () => {
  async function turnOn() {
    renderDeviceApp(registeredDeviceApi());
    await flush();
    fireEvent.click(screen.getByTestId("t19-on"));
    await flush();
    act(() => runtimeControl.events().onReady());
  }

  it("1 · 2 · 4초, 그 뒤 5초 간격으로 다시 붙고 내내 '연결 끊김' · '다시 연결하고 있어요'다", async () => {
    await turnOn();
    act(() => runtimeControl.events().onView("THINKING"));
    act(() => runtimeControl.events().onClosed(1006, false));
    expect(screen.getByTestId("t19-label")).toHaveTextContent("연결 끊김");
    expect(screen.getByTestId("t19-sublabel")).toHaveTextContent("다시 연결하고 있어요");
    let connects = 1;
    for (const delay of [1_000, 2_000, 4_000, 5_000, 5_000]) {
      await advance(delay - 1);
      expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(connects);
      await advance(1);
      connects += 1;
      expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(connects);
      expect(screen.getByTestId("t19-label")).toHaveTextContent("연결 끊김");
      act(() => runtimeControl.events().onClosed(1013, false));
    }
    // 다시 연결되면 대기로 돌아오고 보조 줄이 사라지며, 다음 끊김은 다시 1초부터 센다
    await advance(5_000);
    act(() => runtimeControl.events().onReady());
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "standby");
    expect(screen.getByTestId("t19-label")).toHaveTextContent("대기");
    expect(screen.queryByTestId("t19-sublabel")).toBeNull();
    const before = runtimeControl.runtime.connect.mock.calls.length;
    act(() => runtimeControl.events().onClosed(1006, false));
    await advance(1_000);
    expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(before + 1);
  });

  it("한 번도 연결되지 않은 기기는 5초마다이고 '연결 중' 화면에 보조 줄이 없다(U1 BR9.4 · J367 그대로)", async () => {
    renderDeviceApp(registeredDeviceApi());
    await flush();
    fireEvent.click(screen.getByTestId("t19-on"));
    await flush();
    act(() => runtimeControl.events().onClosed(1006, false));
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "connecting");
    expect(screen.queryByTestId("t19-sublabel")).toBeNull();
    await advance(4_999);
    expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(1);
    await advance(1);
    expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(2);
  });
});

describe("StatusView — 보조 줄", () => {
  it("보조 줄은 켜짐 쪽 화면에서만 그리고 없으면 그리지 않는다", () => {
    const handlers = { onTurnOn: vi.fn(), onTurnOff: vi.fn() };
    const { rerender } = render(<StatusView display="reconnecting" label="연결 끊김" subLabel="다시 연결하고 있어요" {...handlers} />);
    expect(screen.getByTestId("t19-sublabel")).toHaveTextContent("다시 연결하고 있어요");
    rerender(<StatusView display="off" label={null} subLabel="다시 연결하고 있어요" {...handlers} />);
    expect(screen.queryByTestId("t19-sublabel")).toBeNull();
    rerender(<StatusView display="standby" label="대기" {...handlers} />);
    expect(screen.queryByTestId("t19-sublabel")).toBeNull();
  });
});
