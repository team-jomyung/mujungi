import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { StatusView, type StatusDisplay } from "@/device/features/status/StatusView";
import { flush, registeredDeviceApi, renderDeviceApp } from "../../../support/deviceHarness";
import { runtimeControl } from "../../../support/runtimeMock";

vi.mock("@/device/runtime/deviceRuntime", async () => (await import("../../../support/runtimeMock")).deviceRuntimeModule);
vi.mock("@/device/mic/micPermission", async () => (await import("../../../support/runtimeMock")).micPermissionModule);

// T19 상태 화면 시험 (U1 3.5 Step 41 · BR9.5 · BR9.6 · AC6.2.1 ~ AC6.2.3 · AC6.2.5 · AC6.2.9 · C.D-19.31 · 39 · 57)
// 6상태(꺼짐 · 대기 · 듣는 중 · 말하는 중 · 마무리 · 마이크 허용 안 됨) 라벨, '말하는 중'은 실제 재생 중일 때만, ?debug=1 상태 로그.

// 구석의 「동작 줄이기」 스위치(role=switch, docs/ui/device-subtitle.md 편차)를 뺀 조작 요소. 켜기 · 끄기는 여전히 하나뿐이다.
const INTERACTIVE =
  "button:not([role='switch']), a[href], input, select, textarea, [role='button'], [tabindex]:not([tabindex='-1']):not([role='switch'])";

function renderView(display: StatusDisplay, label: string | null = null) {
  const onTurnOn = vi.fn();
  const onTurnOff = vi.fn();
  const view = render(<StatusView display={display} label={label} onTurnOn={onTurnOn} onTurnOff={onTurnOff} />);
  return { ...view, onTurnOn, onTurnOff };
}

describe("StatusView", () => {
  it("꺼짐은 「켜기」 하나만 있고 누르면 켜기를 부른다(AC6.2.1)", () => {
    const { onTurnOn, container } = renderView("off");
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "off");
    fireEvent.click(screen.getByTestId("t19-on"));
    expect(onTurnOn).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("t19-on")).toHaveTextContent("켜기");
    expect(screen.queryByTestId("t19-label")).toBeNull();
    expect(container.querySelectorAll(INTERACTIVE)).toHaveLength(1);
  });

  it("마이크를 묻는 동안 「켜기」는 꺼져 두 번 누를 수 없다", () => {
    renderView("mic-request");
    expect(screen.getByTestId("t19-on")).toBeDisabled();
  });

  it("마이크 허용 안 됨은 문구표 두 줄과 다시 누를 수 있는 「켜기」를 보인다(AC6.2.2 · C.D-19.57)", () => {
    const { onTurnOn } = renderView("mic-denied");
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("마이크를 허용해 주세요");
    expect(alert).toHaveTextContent("브라우저 설정에서 마이크와 소리를 켠 뒤 다시 「켜기」를 눌러요.");
    expect(screen.getByTestId("t19-on")).toBeEnabled();
    fireEvent.click(screen.getByTestId("t19-on"));
    expect(onTurnOn).toHaveBeenCalledTimes(1);
  });

  it.each([
    ["standby", "대기"],
    ["listening", "듣는 중"],
    ["speaking", "말하는 중"],
    ["closing", "마무리"],
  ] as const)("켜짐 %s는 라벨 '%s'를 조용히 알리고 「끄기」 하나만 있다", (display, label) => {
    const { onTurnOff, container } = renderView(display, label);
    const labelNode = screen.getByTestId("t19-label");
    expect(labelNode).toHaveTextContent(label);
    expect(labelNode).toHaveAttribute("aria-live", "polite");
    expect(container.querySelectorAll(INTERACTIVE)).toHaveLength(1);
    fireEvent.click(screen.getByTestId("t19-off"));
    expect(onTurnOff).toHaveBeenCalledTimes(1);
  });

  it("「동작 줄이기」 스위치는 꺼짐 · 켜짐 화면 모두 하나 있고 기본은 꺼져 있으며 누르면 켜진다", () => {
    for (const display of ["off", "standby"] as const) {
      const { container, unmount } = renderView(display, "대기");
      const toggle = screen.getByTestId("t19-reduce-motion");
      expect(container.querySelectorAll("[role='switch']")).toHaveLength(1);
      expect(toggle).toHaveTextContent("동작 줄이기");
      expect(toggle).toHaveAttribute("aria-checked", "false");
      fireEvent.click(toggle);
      expect(toggle).toHaveAttribute("aria-checked", "true");
      fireEvent.click(toggle);
      expect(toggle).toHaveAttribute("aria-checked", "false");
      unmount();
    }
  });

  it("연결 중에는 라벨이 비어 있고 기술 문구가 없다(BR9.4)", () => {
    renderView("connecting", null);
    expect(screen.getByTestId("t19-label")).toBeEmptyDOMElement();
    expect(screen.getByTestId("t19-off")).toBeInTheDocument();
  });
});

describe("DeviceApp T19 — 표시 상태는 SESSION_STATE와 실제 재생(BR9.6)", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    runtimeControl.reset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  async function turnOnToStandby(debug = false) {
    renderDeviceApp(registeredDeviceApi(), { debug });
    await flush();
    fireEvent.click(screen.getByTestId("t19-on"));
    await flush();
    expect(runtimeControl.runtime.connect).toHaveBeenCalledTimes(1);
    act(() => runtimeControl.events().onReady());
  }

  it("「켜기」 → 허용 → 연결 → HELLO_OK 뒤 '대기'(AC6.2.1)", async () => {
    await turnOnToStandby();
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "standby");
    expect(screen.getByTestId("t19-label")).toHaveTextContent("대기");
    expect(runtimeControl.options?.timing.deviceHeartbeatIntervalMs).toBe(5_000);
  });

  it("듣는 중 → 말하는 중(재생 중만) → 듣는 중 → 마무리 → 대기(AC6.2.5)", async () => {
    await turnOnToStandby();
    const events = runtimeControl.events();
    act(() => events.onView("LISTENING"));
    expect(screen.getByTestId("t19-label")).toHaveTextContent("듣는 중");
    act(() => events.onSpeaking(true));
    expect(screen.getByTestId("t19-label")).toHaveTextContent("말하는 중");
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "speaking");
    act(() => events.onSpeaking(false));
    expect(screen.getByTestId("t19-label")).toHaveTextContent("듣는 중");
    act(() => events.onView("THINKING"));
    expect(screen.getByTestId("t19-label")).toHaveTextContent("생각 중");
    act(() => events.onView("CLOSING"));
    expect(screen.getByTestId("t19-label")).toHaveTextContent("마무리");
    act(() => events.onView("STANDBY"));
    expect(screen.getByTestId("t19-label")).toHaveTextContent("대기");
  });

  it("?debug=1이면 상태 로그를 보이고 마지막 줄이 화면 상태와 같다(AC6.2.9)", async () => {
    await turnOnToStandby(true);
    act(() => runtimeControl.events().onView("LISTENING"));
    const entries = screen.getAllByTestId("debug-log-entry").map((entry) => entry.textContent);
    expect(entries.at(-1)).toBe(screen.getByTestId("t19-view").getAttribute("data-state"));
  });

  it("debug가 아니면 상태 로그가 없다", async () => {
    await turnOnToStandby(false);
    expect(screen.queryByTestId("debug-log")).toBeNull();
  });

  it("마이크를 거부하면 실행부를 띄우지 않고(연결 0) '마이크 허용 안 됨'이다(AC6.2.2)", async () => {
    runtimeControl.micResult = { granted: false, reason: "denied" };
    renderDeviceApp(registeredDeviceApi());
    await flush();
    fireEvent.click(screen.getByTestId("t19-on"));
    await flush();
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "mic-denied");
    expect(runtimeControl.options).toBeNull();
    expect(runtimeControl.runtime.connect).not.toHaveBeenCalled();
  });
});
