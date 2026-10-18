import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { PracticeHeader } from "@/parent/features/practice/PracticeHeader";
import { parsePracticeProgress, PracticeProgressTracker } from "@/parent/features/practice/practiceProgress";
import { U5B_COPY } from "@/parent/features/practice/u5bCopy";

afterEach(cleanup);
const valid = { goalIndex: 1, goalCount: 3, goalName: "차례", stage: "FIRST_RESPONSE" };
it("네 필드만 복사하고 판정과 원문을 보관하지 않는다", () => {
  const parsed = parsePracticeProgress({ ...valid, label: "functional", judgmentCriteria: "비밀 기준", scenario: "비공개 장면" });
  expect(parsed).toEqual(valid); expect(Object.keys(parsed!)).toHaveLength(4);
  render(<PracticeHeader progress={parsed} />); expect(screen.getByRole("region", { name: "연습 진행" })).toHaveTextContent("1/3번째 목표");
  expect(screen.getByRole("heading", { level: 2 }).textContent).toBe("연습 · 1/3번째 목표 · 차례");
  expect(document.body.textContent).not.toMatch(/functional|비밀|비공개/);
});
it("잘못된 타입·순번·이름·알 수 없는 단계를 거절한다", () => {
  for (const value of [null, [], "bad", { ...valid, goalIndex: 0 }, { ...valid, goalIndex: 4 }, { ...valid, goalCount: 1.5 },
    { ...valid, goalIndex: "1" }, { ...valid, goalName: " " }, { ...valid, stage: "EVALUATING" }, { ...valid, stage: "toString" }])
    expect(parsePracticeProgress(value)).toBeNull();
});
it("여섯 현재 단계 모두 정해진 문구로 표시한다", () => {
  for (const [stage, label] of Object.entries(U5B_COPY.stages)) {
    const view = render(<PracticeHeader progress={parsePracticeProgress({ ...valid, stage })} />);
    expect(screen.getByText(label)).toBeVisible(); view.unmount();
  }
});
it("자유대화에는 머리를 만들지 않고 연습 누락은 확인 불가다", () => {
  const tracker = new PracticeProgressTracker("session"); tracker.apply("session", 1, 1, "FREE_TALK", null);
  const view = render(<PracticeHeader progress={tracker.value} issue={tracker.issue} />); expect(view.container).toBeEmptyDOMElement();
  tracker.apply("session", 2, 2, "PRACTICE", null); view.rerender(<PracticeHeader progress={tracker.value} issue={tracker.issue} />);
  expect(screen.getByRole("status")).toHaveTextContent("현재 연습 단계를 확인할 수 없어요");
});
it("다른 세션·오래된 조회·낮은 상태 버전을 폐기한다", () => {
  const tracker = new PracticeProgressTracker("session"); expect(tracker.apply("session", 3, 4, "PRACTICE", valid)).toBe(true);
  expect(tracker.apply("other", 4, 5, "PRACTICE", { ...valid, goalIndex: 2 })).toBe(false);
  expect(tracker.apply("session", 2, 5, "PRACTICE", { ...valid, goalIndex: 2 })).toBe(false);
  expect(tracker.apply("session", 4, 3, "PRACTICE", { ...valid, goalIndex: 2 })).toBe(false);
  expect(tracker.value).toEqual(valid);
});
it("조회 실패는 마지막 확인값을 유지하고 최신 유효 조회로 해제된다", () => {
  const tracker = new PracticeProgressTracker("session"); tracker.apply("session", 1, 1, "PRACTICE", valid); tracker.failed("session", 2);
  render(<PracticeHeader progress={tracker.value} issue={tracker.issue} />);
  expect(screen.getByRole("status")).toHaveTextContent("마지막 확인"); expect(screen.getByRole("heading")).toHaveTextContent("차례");
  tracker.apply("session", 3, 1, "PRACTICE", { ...valid, stage: "CUE" }); expect(tracker.issue).toBeNull(); expect(tracker.value?.stage).toBe("CUE");
});
it("오래된 실패는 최신 진행 정보를 덮어쓰지 않는다", () => {
  const tracker = new PracticeProgressTracker("session"); tracker.apply("session", 3, 3, "PRACTICE", valid);
  tracker.failed("other", 4); tracker.failed("session", 2); tracker.failed("session", NaN); expect(tracker.issue).toBeNull();
});
it("표시는 조용한 상태 알림만 제공하고 단계 조작은 없다", () => {
  render(<PracticeHeader progress={parsePracticeProgress(valid)} />);
  expect(screen.getByRole("region")).toHaveAttribute("aria-live", "polite"); expect(screen.queryByRole("button")).toBeNull();
});
