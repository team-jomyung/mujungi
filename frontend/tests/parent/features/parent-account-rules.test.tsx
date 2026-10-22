import { fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { BottomSheet } from "@/parent/common/components/BottomSheet";
import {
  ACCOUNT_COPY,
  ACCOUNT_PROPOSED_COPY,
  ACCOUNT_TEMP_COPY,
  childAgeInterestsLine,
  deleteDialogBody,
  registrationDoneLine,
  rttText,
  stopSummary,
} from "@/shared/copy/account";

// U2 공통 규칙 시험: 바텀 시트 초점 · 문구 모듈

function SheetHarness() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" data-testid="opener" onClick={() => setOpen(true)}>
        {ACCOUNT_COPY.settings.title}
      </button>
      <BottomSheet open={open} title={ACCOUNT_COPY.settings.title} onClose={() => setOpen(false)} withBack testIdPrefix="sample">
        <button type="button" data-testid="first-action">
          {ACCOUNT_COPY.settings.logout}
        </button>
        <button type="button" data-testid="last-action">
          {ACCOUNT_COPY.settings.deleteAccount}
        </button>
      </BottomSheet>
    </>
  );
}

describe("바텀 시트(interaction-spec 3.5)", () => {
  it("열리면 제목에 초점 · 배경 스크롤 잠금, Tab은 시트 안에서 돌고 닫히면 연 단추로 초점이 돌아온다", () => {
    render(<SheetHarness />);
    screen.getByTestId("opener").focus();
    fireEvent.click(screen.getByTestId("opener"));
    expect(screen.getByRole("heading", { name: "설정" })).toHaveFocus();
    expect(document.body.style.overflow).toBe("hidden");
    const sheet = screen.getByTestId("sample-sheet");

    screen.getByTestId("last-action").focus();
    fireEvent.keyDown(sheet, { key: "Tab" });
    expect(screen.getByTestId("sample-back")).toHaveFocus();
    fireEvent.keyDown(sheet, { key: "Tab", shiftKey: true });
    expect(screen.getByTestId("last-action")).toHaveFocus();
    fireEvent.keyDown(sheet, { key: "a" });
    fireEvent.click(sheet);
    expect(screen.getByTestId("sample-sheet")).toBeInTheDocument();

    fireEvent.keyDown(sheet, { key: "Escape" });
    expect(screen.queryByTestId("sample-sheet")).toBeNull();
    expect(screen.getByTestId("opener")).toHaveFocus();
    expect(document.body.style.overflow).toBe("");
  });
});

describe("U2 문구 모듈", () => {
  it("화면에 '(문구 미정)'이나 원시 code를 두지 않는다", () => {
    const strings = [ACCOUNT_COPY, ACCOUNT_PROPOSED_COPY, ACCOUNT_TEMP_COPY].flatMap(function all(value: unknown): string[] {
      if (typeof value === "string") {
        return [value];
      }
      return typeof value === "object" && value !== null ? Object.values(value).flatMap(all) : [];
    });
    expect(strings.some((text) => text.includes("문구 미정"))).toBe(false);
    expect(strings.filter((text) => /^[A-Z][A-Z_]+$/.test(text))).toEqual([]);
  });

  it("자리를 채우는 문구는 문구표 꼴이다", () => {
    expect(childAgeInterestsLine(7, ["공룡", "블록 놀이"])).toBe("7세 · 공룡, 블록 놀이");
    expect(childAgeInterestsLine(7, [])).toBe("7세");
    expect(deleteDialogBody("민준")).toBe(
      "민준의 기록 · 리포트 · 동의 · 기기 등록이 모두 지워지고 되돌릴 수 없어요. 남길 리포트는 먼저 저장해 두세요.",
    );
    expect(deleteDialogBody(null)).toContain("아이의 기록");
    expect(registrationDoneLine("7F3K")).toBe("기기 등록 · 완료 (7F3K)");
    expect(rttText(300)).toBe("지연 0.3초");
    expect(rttText(1000)).toBe("지연 1초");
    expect(stopSummary(["이제 그만", "끝낼래"])).toBe("추가 표현 2개 · “이제 그만” · “끝낼래”");
  });
});
