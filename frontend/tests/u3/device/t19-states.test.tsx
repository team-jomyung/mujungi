import { act, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { advance, flush, registeredDeviceApi, renderDeviceApp } from "../../u1/support/deviceHarness";
import { runtimeControl } from "../../u1/support/runtimeMock";

vi.mock("@/device/runtime/deviceRuntime", async () => (await import("../../u1/support/runtimeMock")).deviceRuntimeModule);
vi.mock("@/device/mic/micPermission", async () => (await import("../../u1/support/runtimeMock")).micPermissionModule);

// T19 상태 화면 시험 (U3-R15 · 화면 설계 D-19 · C.D-19.31 · AC6.2.4 · AC6.2.6 · BR9.6)
// 꺼짐 → 「켜기」(마이크 허용 · 연결 중) → 대기 · 듣는 중 · 생각 중 · 말하는 중 · 마무리 → 연결 끊김 · 다시 연결 중 → 「끄기」 꺼짐,
// 마이크 허용 안 됨 → 「켜기」 다시. 표정(data-state) · 라벨 · 보조 줄을 함께 본다.

beforeEach(() => {
  vi.useFakeTimers();
  runtimeControl.reset();
});

afterEach(() => {
  vi.useRealTimers();
});

function expectScreen(state: string, label: string | null, subLabel: string | null = null) {
  const view = screen.getByTestId("t19-view");
  expect(view).toHaveAttribute("data-state", state);
  expect(view.querySelector(".mj-character")).toHaveAttribute("data-expression", state);
  if (label === null) {
    expect(screen.queryByTestId("t19-label")?.textContent ?? "").toBe("");
  } else {
    expect(screen.getByTestId("t19-label")).toHaveTextContent(label);
  }
  if (subLabel === null) {
    expect(screen.queryByTestId("t19-sublabel")).toBeNull();
  } else {
    expect(screen.getByTestId("t19-sublabel")).toHaveTextContent(subLabel);
  }
}

describe("T19 — 상태 여덟 가지와 켜기 · 끄기", () => {
  it("꺼짐부터 대기 · 듣는 중 · 생각 중 · 말하는 중 · 마무리 · 연결 끊김을 지나 「끄기」로 꺼진다", async () => {
    renderDeviceApp(registeredDeviceApi());
    await flush();
    expectScreen("off", null);
    expect(screen.getByTestId("t19-on")).toHaveTextContent("켜기");

    fireEvent.click(screen.getByTestId("t19-on"));
    await flush();
    expectScreen("connecting", null);
    act(() => runtimeControl.events().onReady());
    expectScreen("standby", "대기");
    act(() => runtimeControl.events().onView("LISTENING"));
    expectScreen("listening", "듣는 중");
    act(() => runtimeControl.events().onView("THINKING"));
    expectScreen("thinking", "생각 중");
    // '말하는 중'은 실제로 소리가 나는 동안만(BR9.6)
    act(() => runtimeControl.events().onSpeaking(true));
    expectScreen("speaking", "말하는 중");
    act(() => runtimeControl.events().onSpeaking(true));
    expectScreen("speaking", "말하는 중");
    act(() => runtimeControl.events().onSpeaking(false));
    expectScreen("thinking", "생각 중");
    act(() => runtimeControl.events().onView("CLOSING"));
    expectScreen("closing", "마무리");
    act(() => runtimeControl.events().onView("CLOSING"));
    act(() => runtimeControl.events().onView("STANDBY"));
    expectScreen("standby", "대기");

    act(() => runtimeControl.events().onClosed(1006, false));
    expectScreen("reconnecting", "연결 끊김", "다시 연결하고 있어요");
    // 재접속 중의 view · 말하기 사건은 화면을 바꾸지 않는다
    act(() => runtimeControl.events().onView("LISTENING"));
    act(() => runtimeControl.events().onSpeaking(true));
    expectScreen("reconnecting", "연결 끊김", "다시 연결하고 있어요");

    fireEvent.click(screen.getByTestId("t19-off"));
    await flush();
    expectScreen("off", null);
    expect(runtimeControl.runtime.shutdown).toHaveBeenCalledWith(true);
    await advance(10_000);
    expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(1);
  });

  it("마이크를 허용하지 않으면 '마이크를 허용해 주세요'이고 「켜기」로 다시 요청한다", async () => {
    runtimeControl.micResult = { granted: false, reason: "denied" };
    renderDeviceApp(registeredDeviceApi());
    await flush();
    fireEvent.click(screen.getByTestId("t19-on"));
    await flush();
    expectScreen("mic-denied", null);
    expect(screen.getByRole("alert")).toHaveTextContent("마이크를 허용해 주세요");

    runtimeControl.micResult = { granted: true, stream: { getTracks: () => [] } as unknown as MediaStream };
    fireEvent.click(screen.getByTestId("t19-on"));
    await flush();
    expectScreen("connecting", null);
    act(() => runtimeControl.events().onReady());
    expectScreen("standby", "대기");
  });

  it("다른 기기로 대체(4001)되면 재접속하지 않고 꺼짐이다", async () => {
    renderDeviceApp(registeredDeviceApi());
    await flush();
    fireEvent.click(screen.getByTestId("t19-on"));
    await flush();
    act(() => runtimeControl.events().onReady());
    act(() => runtimeControl.events().onClosed(4001, false));
    expectScreen("off", null);
    await advance(10_000);
    expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(1);
  });
});
