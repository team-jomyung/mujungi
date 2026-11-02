import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { PracticeHeader } from "@/parent/common/components/PracticeHeader";
import { parsePracticeProgress } from "@/parent/common/api/practiceProgress";
import { U5B_COPY } from "@/parent/common/copy/practice";

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
it("진행 정보가 없으면 머리를 만들지 않고, 연습 정보가 틀리면 확인 불가다", () => {
  const view = render(<PracticeHeader progress={null} issue={null} />); expect(view.container).toBeEmptyDOMElement();
  view.rerender(<PracticeHeader progress={null} issue="invalid" />);
  expect(screen.getByRole("status")).toHaveTextContent("현재 연습 단계를 확인할 수 없어요");
});
it("조회 실패는 마지막 확인값을 보이며 알린다", () => {
  render(<PracticeHeader progress={parsePracticeProgress(valid)} issue="fetch" />);
  expect(screen.getByRole("status")).toHaveTextContent("마지막 확인"); expect(screen.getByRole("heading")).toHaveTextContent("차례");
});
it("표시는 조용한 상태 알림만 제공하고 단계 조작은 없다", () => {
  render(<PracticeHeader progress={parsePracticeProgress(valid)} />);
  expect(screen.getByRole("region")).toHaveAttribute("aria-live", "polite"); expect(screen.queryByRole("button")).toBeNull();
});
