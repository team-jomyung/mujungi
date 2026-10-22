import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { FirstBootView } from "@/device/features/first-boot/FirstBootView";

// 기기 첫 화면 조작 요소 금지 시험 (U1 3.5 Step 42 · BR9.2)
// D0에는 아이가 누를 수 있는 요소가 하나도 없다. 다른 화면의 data-testid는 각 화면의 동작 시험이 필요한 요소를 찾으며 지킨다.

const INTERACTIVE = [
  "button",
  "a[href]",
  "input",
  "select",
  "textarea",
  "[role='button']",
  "[role='tab']",
  "[role='radio']",
  "[role='checkbox']",
  "[role='link']",
  "[contenteditable='true']",
  "[tabindex]:not([tabindex='-1'])",
].join(", ");
const TEST_ID_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;

function badTestIds(root: ParentNode = document.body): string[] {
  return Array.from(root.querySelectorAll<HTMLElement>("[data-testid]"))
    .map((element) => element.getAttribute("data-testid") ?? "")
    .filter((testId) => !TEST_ID_PATTERN.test(testId));
}

describe("기기 화면", () => {
  it.each(["d0-checking", "d0-code", "d0-registered"] as const)("D0 %s에는 상호작용 요소가 하나도 없다(BR9.2)", (display) => {
    const { container } = render(<FirstBootView display={display} code="7F3K92QD" />);
    expect(container.querySelectorAll(INTERACTIVE)).toHaveLength(0);
    expect(badTestIds()).toEqual([]);
  });
});
