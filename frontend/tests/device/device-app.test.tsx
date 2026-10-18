import { act, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DEVICE_ID } from "../support/fixtures";
import { advance, flush, registeredDeviceApi, renderDeviceApp, scriptedDeviceApi } from "../support/deviceHarness";
import { deviceRuntimeModule, micPermissionModule, runtimeControl } from "../support/runtimeMock";

vi.mock("@/device/runtime/deviceRuntime", async () => (await import("../support/runtimeMock")).deviceRuntimeModule);
vi.mock("@/device/mic/micPermission", async () => (await import("../support/runtimeMock")).micPermissionModule);

// DeviceApp 켜짐 동작 시험 (U1 3.5 Step 41 · BR9.5 · BR9.7 · BR3.7 · AC6.2.3 · AC6.2.8 · PD-14)
// 닫기 코드별 동작(4401 → D0, 4001 → 꺼짐, 그 밖 → 5초 뒤 재접속)과 「끄기」 · 화면 떠남의 정리를 가짜 시계로 본다.

async function turnOn(api = registeredDeviceApi()) {
  const view = renderDeviceApp(api);
  await flush();
  fireEvent.click(screen.getByTestId("t19-on"));
  await flush();
  act(() => runtimeControl.events().onReady());
  expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "standby");
  return { api, view };
}

beforeEach(() => {
  vi.useFakeTimers();
  runtimeControl.reset();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("DeviceApp — 닫기 코드(BR9.7)", () => {
  // U3-R16 · U3-J19: 한 번 연결됐던 기기는 1초 뒤 다시 접속하고 라벨은 '연결 끊김' · "다시 연결하고 있어요"다(U1은 5초 · 마지막 view 라벨)
  it.each([1001, 1011, 1013, 4400, 4408])("%i로 닫히면 '연결 끊김'을 보이고 정확히 1초 뒤 다시 접속한다", async (code) => {
    await turnOn();
    act(() => runtimeControl.events().onView("LISTENING"));
    act(() => runtimeControl.events().onClosed(code, false));
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "reconnecting");
    expect(screen.getByTestId("t19-label")).toHaveTextContent("연결 끊김");
    expect(screen.getByTestId("t19-sublabel")).toHaveTextContent("다시 연결하고 있어요");
    expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(1);
    await advance(999);
    expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(1);
    await advance(1);
    expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(2);
    expect(runtimeControl.runtime.shutdown).not.toHaveBeenCalled();
    // 다시 붙으면 대기에서 시작한다(세션 이어가기 · HELLO resume은 U3, BR9.7).
    act(() => runtimeControl.events().onReady());
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "standby");
  });

  it("재접속 주기(대기 → 접속 시도 → 다시 실패) 내내 '연결 끊김' 화면이고 간격은 1 · 2초로 는다(BR9.7 · U3-R16 · U3-J19)", async () => {
    await turnOn();
    act(() => runtimeControl.events().onView("LISTENING"));
    act(() => runtimeControl.events().onClosed(1006, false));
    const expectLastScreen = () => {
      const view = screen.getByTestId("t19-view");
      expect(view).toHaveAttribute("data-state", "reconnecting");
      expect(view.querySelector(".mj-character")).toHaveAttribute("data-expression", "reconnecting");
      expect(screen.getByTestId("t19-label")).toHaveTextContent("연결 끊김");
      expect(screen.getByTestId("t19-sublabel")).toHaveTextContent("다시 연결하고 있어요");
    };
    expectLastScreen();
    await advance(1_000);
    expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(2);
    expectLastScreen();
    // 서버가 계속 거절해도(1013) 다음 간격(2초) 뒤 다시 붙으며 화면은 바뀌지 않는다.
    act(() => runtimeControl.events().onClosed(1013, false));
    expectLastScreen();
    await advance(1_999);
    expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(2);
    await advance(1);
    expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(3);
    expectLastScreen();
    act(() => runtimeControl.events().onReady());
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "standby");
    expect(screen.getByTestId("t19-label")).toHaveTextContent("대기");
  });

  it("처음 연결 중(HELLO_OK 전)에는 라벨이 비고 표정은 'connecting'이다(재접속과 구분, BR9.4)", async () => {
    renderDeviceApp(registeredDeviceApi());
    await flush();
    fireEvent.click(screen.getByTestId("t19-on"));
    await flush();
    expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "connecting");
    expect(screen.getByTestId("t19-label")).toBeEmptyDOMElement();
  });

  it("처음 연결에 실패한 기기는 재접속 대기 · 재접속 시도 · 다시 실패 동안 라벨이 비고 표정은 'connecting' 그대로다(BR9.4 · J367)", async () => {
    renderDeviceApp(registeredDeviceApi());
    await flush();
    fireEvent.click(screen.getByTestId("t19-on"));
    await flush();
    const expectConnectingScreen = () => {
      const view = screen.getByTestId("t19-view");
      expect(view).toHaveAttribute("data-state", "connecting");
      expect(view.querySelector(".mj-character")).toHaveAttribute("data-expression", "connecting");
      expect(screen.getByTestId("t19-label")).toBeEmptyDOMElement();
    };
    expectConnectingScreen();
    // 첫 시도 실패 → 5초 대기 → 재접속 시도
    act(() => runtimeControl.events().onClosed(1006, false));
    expectConnectingScreen();
    await advance(5_000);
    expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(2);
    expectConnectingScreen();
    // 재시도도 실패(1013) → 다시 대기 → 다시 시도
    act(() => runtimeControl.events().onClosed(1013, false));
    expectConnectingScreen();
    await advance(5_000);
    expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(3);
    expectConnectingScreen();
    // 연결되면 대기이고, 한 번 연결된 뒤 끊기면 '연결 끊김' 라벨 · 'reconnecting' 표정(U3-J19, U1 J360은 마지막 view 라벨)
    act(() => runtimeControl.events().onReady());
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "standby");
    expect(screen.getByTestId("t19-label")).toHaveTextContent("대기");
    expect(screen.queryByTestId("t19-sublabel")).toBeNull();
    act(() => runtimeControl.events().onClosed(1006, false));
    const view = screen.getByTestId("t19-view");
    expect(view).toHaveAttribute("data-state", "reconnecting");
    expect(view.querySelector(".mj-character")).toHaveAttribute("data-expression", "reconnecting");
    expect(screen.getByTestId("t19-label")).toHaveTextContent("연결 끊김");
  });

  it("4401이면 실행부를 정리하고 /device/me로 확인해 401이면 D0 새 코드로 간다(AC6.2.8)", async () => {
    const api = scriptedDeviceApi({
      me: [{ kind: "registered", deviceId: DEVICE_ID }, { kind: "unregistered" }],
      status: [{ kind: "notFound" }],
      bootstrap: [{ kind: "issued", code: "7F3K92QD", expiresAt: "x" }],
    });
    await turnOn(api);
    act(() => runtimeControl.events().onClosed(4401, false));
    await flush();
    expect(runtimeControl.runtime.shutdown).toHaveBeenCalledWith(false);
    expect(api.me).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId("d0-code")).toHaveTextContent("7F3K · 92QD");
  });

  it("4001(대체됨)이면 D0이 아니라 꺼짐이고 자동으로 다시 붙지 않는다(BR3.7)", async () => {
    await turnOn();
    act(() => runtimeControl.events().onClosed(4001, false));
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "off");
    expect(runtimeControl.runtime.shutdown).toHaveBeenCalledWith(false);
    await advance(10_000);
    expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(1);
  });
});

describe("DeviceApp — 「끄기」 · 정리", () => {
  it("「끄기」는 1000 DEVICE_OFF로 닫고 꺼짐이 된다(AC6.2.3 · AC6.2.11)", async () => {
    await turnOn();
    fireEvent.click(screen.getByTestId("t19-off"));
    expect(runtimeControl.runtime.shutdown).toHaveBeenCalledWith(true);
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "off");
    await advance(10_000);
    expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(1);
  });

  it("화면을 떠나면 실행부를 닫는다", async () => {
    const { view } = await turnOn();
    view.unmount();
    expect(runtimeControl.runtime.shutdown).toHaveBeenCalledWith(true);
  });

  it("실행부를 세우지 못하면 연결하지 않고 '마이크 허용 안 됨'으로 둔다", async () => {
    runtimeControl.startError = new Error("audio");
    renderDeviceApp(registeredDeviceApi());
    await flush();
    fireEvent.click(screen.getByTestId("t19-on"));
    await flush();
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "mic-denied");
    expect(runtimeControl.runtime.connect).not.toHaveBeenCalled();
    runtimeControl.startError = null;
    fireEvent.click(screen.getByTestId("t19-on"));
    await flush();
    expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(1);
  });

  it("「켜기」 처리 중 두 번째 누름은 무시한다", async () => {
    renderDeviceApp(registeredDeviceApi());
    await flush();
    const button = screen.getByTestId("t19-on");
    fireEvent.click(button);
    fireEvent.click(button);
    await flush();
    expect(deviceRuntimeModule.startDeviceRuntime).toHaveBeenCalledTimes(1);
  });

  it("실행부를 받은 직후 정상 대기 흐름이 이어진다(실행부의 시간 · 시계 주입)", async () => {
    await turnOn();
    const options = runtimeControl.options;
    expect(options?.timing.deviceOutputQueueMaxMs).toBe(20_000);
    expect(typeof options?.nowMs()).toBe("number");
    expect(options?.audioContext).toBeNull();
  });
});

describe("DeviceApp — 꺼짐 화면의 지연 적재(PD-14)", () => {
  it("유휴 시간 함수가 있으면 그것으로 미리 받고, 꺼짐을 벗어나면 취소한다", async () => {
    const requestIdle = vi.fn<(callback: () => void) => number>(() => 7);
    const cancelIdle = vi.fn<(handle: number) => void>();
    vi.stubGlobal("requestIdleCallback", requestIdle);
    vi.stubGlobal("cancelIdleCallback", cancelIdle);
    renderDeviceApp(registeredDeviceApi());
    await flush();
    expect(requestIdle).toHaveBeenCalledTimes(1);
    requestIdle.mock.calls[0][0]();
    fireEvent.click(screen.getByTestId("t19-on"));
    await flush();
    expect(cancelIdle).toHaveBeenCalledWith(7);
  });

  it("유휴 시간 함수가 없어도 타이머로 미리 받으며 클릭 전에는 마이크를 묻지 않는다(PD-16)", async () => {
    renderDeviceApp(registeredDeviceApi());
    await flush();
    await advance(0);
    expect(runtimeControl.options).toBeNull();
    expect(deviceRuntimeModule.startDeviceRuntime).not.toHaveBeenCalled();
    expect(micPermissionModule.requestMicrophone).not.toHaveBeenCalled();
  });
});
