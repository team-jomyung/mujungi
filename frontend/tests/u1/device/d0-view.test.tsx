import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FirstBootView } from "@/device/features/first-boot/FirstBootView";
import { DEVICE_ID } from "../support/fixtures";
import { advance, flush, renderDeviceApp, scriptedDeviceApi } from "../support/deviceHarness";

// D0 처음 켜기 화면 시험 (U1 3.5 Step 41 · BR9.2 · BR9.4 · AC6.1.1 ~ AC6.1.5 · J56 · C.D-00.30)
// 안내 + 4 · 4 코드, 등록되면 「등록되었어요」를 registered-display(3초) 동안 보인 뒤 T19 꺼짐. 클릭 요소가 없다.

const INTERACTIVE = "button, a[href], input, select, textarea, [role='button'], [tabindex]:not([tabindex='-1'])";
const ISSUED = { kind: "issued", code: "7F3K92QD", expiresAt: "2026-10-16T01:00:00Z" } as const;

describe("FirstBootView", () => {
  it("코드는 안내와 함께 4 · 4로 크게 보인다(AC6.1.1 · C.D-00.30.2 ~ 3)", () => {
    render(<FirstBootView display="d0-code" code="7F3K92QD" />);
    expect(screen.getByTestId("d0-view")).toHaveAttribute("data-state", "d0-code");
    expect(screen.getByText("보호자 앱에서 이 코드를 넣어 주세요")).toBeInTheDocument();
    expect(screen.getByTestId("d0-code")).toHaveTextContent("7F3K · 92QD");
    expect(screen.queryByTestId("d0-checking")).toBeNull();
  });

  it("코드를 받기 전에는 '연결을 확인하는 중'만 보이고 기술 문구가 없다(BR9.4)", () => {
    render(<FirstBootView display="d0-checking" code={null} />);
    expect(screen.getByTestId("d0-checking")).toHaveTextContent("연결을 확인하는 중");
    expect(screen.getByTestId("d0-checking")).toHaveAttribute("role", "status");
    expect(screen.queryByTestId("d0-code")).toBeNull();
    expect(screen.getByTestId("d0-view").textContent).toBe("무중연결을 확인하는 중");
  });

  it("등록되면 「등록되었어요」를 알리고 코드를 내린다", () => {
    render(<FirstBootView display="d0-registered" code="7F3K92QD" />);
    expect(screen.getByTestId("d0-registered")).toHaveTextContent("등록되었어요");
    expect(screen.getByTestId("d0-registered")).toHaveAttribute("role", "status");
    expect(screen.queryByTestId("d0-code")).toBeNull();
  });

  it("코드 상태인데 코드 값이 없으면 확인 중 문구로 대신한다", () => {
    render(<FirstBootView display="d0-code" code={null} />);
    expect(screen.getByTestId("d0-checking")).toBeInTheDocument();
  });

  it.each(["d0-checking", "d0-code", "d0-registered"] as const)("%s 화면에는 클릭 요소가 하나도 없다(BR9.2)", (display) => {
    const { container } = render(<FirstBootView display={display} code="7F3K92QD" />);
    expect(container.querySelectorAll(INTERACTIVE)).toHaveLength(0);
  });
});

describe("DeviceApp D0 흐름(가짜 시계)", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("코드 표시 → 2초마다 등록 확인 → claim → 「등록되었어요」 3초 → T19 꺼짐(AC6.1.2 · J56)", async () => {
    const api = scriptedDeviceApi({
      status: [{ kind: "notFound" }, { kind: "pending" }, { kind: "registered" }],
      bootstrap: [ISSUED],
      claim: [{ kind: "claimed", deviceId: DEVICE_ID }],
    });
    renderDeviceApp(api);
    await flush();
    expect(screen.getByTestId("d0-code")).toHaveTextContent("7F3K · 92QD");
    expect(api.status).toHaveBeenCalledTimes(1);
    await advance(1_999);
    expect(api.status).toHaveBeenCalledTimes(1);
    await advance(1);
    expect(api.status).toHaveBeenCalledTimes(2);
    await advance(2_000);
    expect(api.status).toHaveBeenCalledTimes(3);
    expect(api.claim).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("d0-registered")).toHaveTextContent("등록되었어요");
    await advance(2_999);
    expect(screen.getByTestId("d0-view")).toHaveAttribute("data-state", "d0-registered");
    await advance(1);
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "off");
    expect(screen.getByTestId("t19-on")).toBeInTheDocument();
  });

  it("코드 요청이 실패하면 '연결을 확인하는 중'을 두고 5초 뒤 같은 요청을 한다(BR9.4)", async () => {
    const api = scriptedDeviceApi({ status: [{ kind: "notFound" }], bootstrap: [{ kind: "failed" }, ISSUED] });
    renderDeviceApp(api);
    await flush();
    expect(screen.getByTestId("d0-checking")).toBeInTheDocument();
    expect(api.bootstrap).toHaveBeenCalledTimes(1);
    await advance(4_999);
    expect(api.bootstrap).toHaveBeenCalledTimes(1);
    await advance(1);
    expect(api.bootstrap).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId("d0-code")).toHaveTextContent("7F3K · 92QD");
  });

  it("코드가 보이는 중 확인이 실패해도 코드를 그대로 두고 5초 뒤 다시 묻는다", async () => {
    const api = scriptedDeviceApi({ status: [{ kind: "notFound" }, { kind: "failed" }, { kind: "pending" }], bootstrap: [ISSUED] });
    renderDeviceApp(api);
    await flush();
    await advance(2_000);
    expect(api.status).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId("d0-code")).toHaveTextContent("7F3K · 92QD");
    await advance(4_999);
    expect(api.status).toHaveBeenCalledTimes(2);
    await advance(1);
    expect(api.status).toHaveBeenCalledTimes(3);
    expect(screen.getByTestId("d0-code")).toBeInTheDocument();
  });

  it("주입한 시간 값으로 동작이 바뀐다(NFR6.1 · registered-display 1초)", async () => {
    const api = scriptedDeviceApi({ status: [{ kind: "registered" }], claim: [{ kind: "claimed", deviceId: DEVICE_ID }] });
    renderDeviceApp(api, { timing: { deviceWebRegisteredDisplayMs: 1_000 } });
    await flush();
    expect(screen.getByTestId("d0-registered")).toBeInTheDocument();
    await advance(1_000);
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "off");
  });

  it("쿠키가 지워진 기기(status 401)는 새 코드를 받고(BR9.3 · AC6.1.3), 화면에 클릭 요소가 없다", async () => {
    const api = scriptedDeviceApi({ status: [{ kind: "notFound" }], bootstrap: [ISSUED] });
    const { container } = renderDeviceApp(api);
    await flush();
    expect(api.me).toHaveBeenCalledTimes(1);
    expect(api.bootstrap).toHaveBeenCalledTimes(1);
    expect(container.querySelectorAll(INTERACTIVE)).toHaveLength(0);
  });
});
