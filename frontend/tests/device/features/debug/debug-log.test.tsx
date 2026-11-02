import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DebugLog } from "@/device/features/debug/DebugLog";
import { TEMP_COPY } from "@/shared/copy/ko";

// ?debug=1 화면의 입력 음량 관문 측정값(FT-1102-g): 음량 숫자와 상태만 보인다.

const copy = TEMP_COPY.debugInputGate;

describe("DebugLog 입력 음량 관문", () => {
  it("켜기 전(측정값 없음)에는 관문 칸을 보이지 않는다", () => {
    render(<DebugLog trail={["T19_OFF"]} />);
    expect(screen.queryByTestId("debug-input-gate")).toBeNull();
  });

  it("상태 · 지금 음량 · 기준 · 소음 바닥 · 측정 프레임을 보인다", () => {
    render(
      <DebugLog
        trail={[]}
        inputGate={{ threshold: 600, thresholdMax: 1500, floor: 200, calibrationFrames: 180, level: 950, state: "open" }}
      />,
    );
    expect(screen.getByTestId("debug-input-gate").dataset.state).toBe("open");
    expect(screen.getByTestId(`debug-input-gate-${copy.state}`).textContent).toBe(copy.states.open);
    expect(screen.getByTestId(`debug-input-gate-${copy.level}`).textContent).toBe("950");
    expect(screen.getByTestId(`debug-input-gate-${copy.threshold}`).textContent).toBe("600");
    expect(screen.getByTestId(`debug-input-gate-${copy.floor}`).textContent).toBe("200");
    expect(screen.getByTestId(`debug-input-gate-${copy.calibrationFrames}`).textContent).toBe("180");
  });

  it("측정 전이면 소음 바닥 자리에 '측정 전'을 보인다", () => {
    render(
      <DebugLog
        trail={[]}
        inputGate={{ threshold: 300, thresholdMax: 1500, floor: null, calibrationFrames: 0, level: 40, state: "closed" }}
      />,
    );
    expect(screen.getByTestId(`debug-input-gate-${copy.floor}`).textContent).toBe(copy.notMeasured);
  });
});
