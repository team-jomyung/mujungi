import { act, fireEvent, renderHook, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TimingProvider } from "@/shared/timing/TimingProvider";
import { ToastProvider, useToast, type ToastApi } from "@/shared/toast/ToastProvider";
import { testTiming } from "../support/fixtures";

// 토스트 시험 (U1 3.5 Step 41 · interaction-spec 1절 · 3.4 · 접근성 O5 · P1 · B25)
// 한 번에 하나, 새 토스트가 대체, 오류는 assertive · 그 밖은 polite, 조작 없는 토스트는 toast-duration 뒤 사라지고
// 조작이 달린 토스트는 toast-action-min 이상 · 초점이 안에 있는 동안 남는다.

const DURATION_MS = 100;
const ACTION_MIN_MS = 300;

function Wrapper({ children }: { children: ReactNode }) {
  return (
    <TimingProvider timing={testTiming({ toastDurationMs: DURATION_MS, toastActionMinMs: ACTION_MIN_MS })}>
      <ToastProvider>{children}</ToastProvider>
    </TimingProvider>
  );
}

function renderToasts(): ToastApi {
  return renderHook(() => useToast(), { wrapper: Wrapper }).result.current;
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("토스트", () => {
  it("안내는 polite 영역에 보이고 toast-duration 뒤 사라진다", () => {
    const toast = renderToasts();
    act(() => toast.show({ message: "기기가 안정될 때까지 시작할 수 없어요" }));
    expect(screen.getByTestId("toast-region-polite")).toHaveTextContent("기기가 안정될 때까지 시작할 수 없어요");
    expect(screen.getByTestId("toast-region-polite")).toHaveAttribute("aria-live", "polite");
    act(() => vi.advanceTimersByTime(DURATION_MS - 1));
    expect(screen.queryByTestId("toast")).not.toBeNull();
    act(() => vi.advanceTimersByTime(1));
    expect(screen.queryByTestId("toast")).toBeNull();
  });

  it("오류는 assertive(role=alert) 영역에 보이고 아이콘과 글을 함께 쓴다", () => {
    const toast = renderToasts();
    act(() => toast.show({ message: "잠시 문제가 생겼어요. 다시 시도해 주세요.", kind: "error" }));
    const region = screen.getByTestId("toast-region-assertive");
    expect(region).toHaveAttribute("role", "alert");
    expect(region).toHaveAttribute("aria-live", "assertive");
    expect(screen.getByTestId("toast")).toHaveAttribute("data-kind", "error");
    expect(screen.getByTestId("toast-region-polite")).toBeEmptyDOMElement();
  });

  it("조작이 달린 토스트는 toast-action-min 이상 남고 누르면 조작을 부른 뒤 사라진다", () => {
    const toast = renderToasts();
    const onPress = vi.fn();
    act(() => toast.show({ message: "연결이 원활하지 않아요", kind: "error", action: { label: "다시 시도", onPress } }));
    act(() => vi.advanceTimersByTime(ACTION_MIN_MS - 1));
    expect(screen.getByTestId("toast-action")).toHaveTextContent("다시 시도");
    fireEvent.click(screen.getByTestId("toast-action"));
    expect(onPress).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId("toast")).toBeNull();
  });

  it("초점이 토스트 안에 있으면 시간이 지나도 남고 초점이 나가면 사라진다(B25)", () => {
    const toast = renderToasts();
    act(() => toast.show({ message: "m", action: { label: "다시 시도", onPress: () => undefined } }));
    fireEvent.focus(screen.getByTestId("toast-action"));
    act(() => vi.advanceTimersByTime(ACTION_MIN_MS * 3));
    expect(screen.queryByTestId("toast")).not.toBeNull();
    fireEvent.blur(screen.getByTestId("toast-action"));
    expect(screen.queryByTestId("toast")).toBeNull();
  });

  it("새 토스트는 이전 것을 대체하고 dismiss로 바로 지울 수 있다", () => {
    const toast = renderToasts();
    act(() => toast.show({ message: "첫째" }));
    act(() => vi.advanceTimersByTime(DURATION_MS / 2));
    act(() => toast.show({ message: "둘째" }));
    expect(screen.getAllByTestId("toast")).toHaveLength(1);
    expect(screen.getByTestId("toast")).toHaveTextContent("둘째");
    // 대체된 토스트의 시간이 지나도 새 토스트는 제 시간을 다 쓴다.
    act(() => vi.advanceTimersByTime(DURATION_MS - 1));
    expect(screen.getByTestId("toast")).toHaveTextContent("둘째");
    act(() => vi.advanceTimersByTime(1));
    expect(screen.queryByTestId("toast")).toBeNull();
    act(() => toast.show({ message: "셋째" }));
    act(() => toast.dismiss());
    expect(screen.queryByTestId("toast")).toBeNull();
  });

  it("ToastProvider 밖에서 부르면 멈춘다", () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    expect(() => renderHook(() => useToast())).toThrow(/ToastProvider/);
  });
});
