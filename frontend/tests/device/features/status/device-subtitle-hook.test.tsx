import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SUBTITLE_HOLD_MS, useDeviceSubtitle } from "@/device/features/status/useDeviceSubtitle";

// 기기 자막 상태: 나눠 보낸 응답의 묶음 사이 틈에서 깜빡이지 않고 이어 붙인다(docs/ui/device-subtitle.md).

describe("useDeviceSubtitle", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("말하기가 잠깐 끝나도 바로 지우지 않고, 그 사이 온 다음 글은 이어 붙인다", () => {
    const { result } = renderHook(() => useDeviceSubtitle());
    act(() => {
      result.current.speakingChanged(true);
      result.current.show("안녕, 하늘아!");
      result.current.speakingChanged(false);
    });
    expect(result.current.subtitle).toBe("안녕, 하늘아!");
    act(() => {
      vi.advanceTimersByTime(SUBTITLE_HOLD_MS - 100);
      result.current.speakingChanged(true);
      result.current.show("같이 놀자고 말해 볼까?");
    });
    expect(result.current.subtitle).toBe("안녕, 하늘아! 같이 놀자고 말해 볼까?");
  });

  it("말하기가 끝나고 잠시 뒤에 지우고, 다음 응답은 새 글로 시작한다", () => {
    const { result } = renderHook(() => useDeviceSubtitle());
    act(() => {
      result.current.speakingChanged(true);
      result.current.show("첫 응답");
      result.current.speakingChanged(false);
      vi.advanceTimersByTime(SUBTITLE_HOLD_MS);
    });
    expect(result.current.subtitle).toBeNull();
    act(() => {
      result.current.show("둘째 응답");
    });
    expect(result.current.subtitle).toBe("둘째 응답");
  });

  it("STOPPED(null) · 끊김은 바로 지운다", () => {
    const { result } = renderHook(() => useDeviceSubtitle());
    act(() => {
      result.current.speakingChanged(true);
      result.current.show("말하는 중");
      result.current.show(null);
    });
    expect(result.current.subtitle).toBeNull();
    act(() => {
      result.current.show("다시");
      result.current.clear();
    });
    expect(result.current.subtitle).toBeNull();
  });
  it("무중이 발화(talking)는 묶음 사이 틈에 이어지고, 틈이 지나거나 끊기면 끝난다", () => {
    const { result } = renderHook(() => useDeviceSubtitle());
    expect(result.current.talking).toBe(false);
    act(() => {
      result.current.speakingChanged(true);
    });
    expect(result.current.talking).toBe(true);
    act(() => {
      result.current.speakingChanged(false);
      vi.advanceTimersByTime(SUBTITLE_HOLD_MS - 100);
    });
    expect(result.current.talking).toBe(true);
    act(() => {
      result.current.speakingChanged(true);
      result.current.speakingChanged(false);
      vi.advanceTimersByTime(SUBTITLE_HOLD_MS);
    });
    expect(result.current.talking).toBe(false);
    act(() => {
      result.current.speakingChanged(true);
      result.current.clear();
    });
    expect(result.current.talking).toBe(false);
  });
});
