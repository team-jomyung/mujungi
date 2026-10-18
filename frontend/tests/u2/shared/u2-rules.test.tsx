import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { BottomSheet } from "@/parent/components/BottomSheet";
import { ChildScreen } from "@/parent/features/child/ChildScreen";
import { ConsentScreen } from "@/parent/features/consent/ConsentScreen";
import { DeviceScreen } from "@/parent/features/device/DeviceScreen";
import { HomeScreen } from "@/parent/features/home/HomeScreen";
import { LoginScreen } from "@/parent/features/login/LoginScreen";
import { StopExpressionsScreen } from "@/parent/features/child/StopExpressionsScreen";
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
import { errorResponse, homeBody, jsonResponse } from "../../u1/support/fixtures";
import { resetRouter } from "../../u1/support/nextNavigation";
import { renderParent, type RequestHandler } from "../../u1/support/parentHarness";
import { childBody, meBody, policyBody, stopBody } from "../support/accountFixtures";

vi.mock("next/navigation", async () => (await import("../../u1/support/nextNavigation")).nextNavigationMock);

// U2 공통 규칙 시험: data-testid(U1 Step 42와 같은 규칙) · 바텀 시트 초점 · 문구 모듈

const INTERACTIVE = [
  "button",
  "a[href]",
  "input",
  "select",
  "textarea",
  "[role='button']",
  "[role='tab']",
  "[role='checkbox']",
  "[tabindex]:not([tabindex='-1'])",
].join(", ");
const TEST_ID_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;

function expectTestIdRule() {
  const missing = Array.from(document.body.querySelectorAll<HTMLElement>(INTERACTIVE))
    .filter((element) => !element.hasAttribute("data-testid"))
    .map((element) => element.outerHTML.slice(0, 120));
  expect(missing).toEqual([]);
  const bad = Array.from(document.body.querySelectorAll<HTMLElement>("[data-testid]"))
    .map((element) => element.getAttribute("data-testid") ?? "")
    .filter((testId) => !TEST_ID_PATTERN.test(testId));
  expect(bad).toEqual([]);
}

const allOk: RequestHandler = (path) => {
  switch (path) {
    case "/api/home":
      return jsonResponse(200, homeBody("STABLE"));
    case "/api/me":
      return jsonResponse(200, meBody());
    case "/api/policies/current":
      return jsonResponse(200, policyBody());
    case "/api/child":
      return jsonResponse(200, childBody());
    case "/api/child/stop-expressions":
      return jsonResponse(200, stopBody(["이제 그만"]));
    default:
      return errorResponse(404, "NOT_FOUND");
  }
};

beforeEach(() => {
  resetRouter();
});

describe("data-testid 규칙 — U2 화면", () => {
  it("01 · 02(+02-1 · P5) · 03(보기 · 수정) · 04(+SET0 · P7) · 05 · SET-01(입력 칸)", async () => {
    const login = renderParent(<LoginScreen navigate={vi.fn()} />, { handler: allOk });
    expectTestIdRule();
    login.view.unmount();

    const consent = renderParent(<ConsentScreen />, { handler: allOk });
    await waitFor(() => expect(screen.getByTestId("consent-detail-voice")).toBeEnabled());
    fireEvent.click(screen.getByTestId("consent-detail-voice"));
    expectTestIdRule();
    fireEvent.click(screen.getByTestId("terms-confirm"));
    fireEvent.click(screen.getByTestId("consent-back"));
    expectTestIdRule();
    consent.view.unmount();

    const child = renderParent(<ChildScreen />, { handler: allOk });
    fireEvent.click(await screen.findByTestId("child-edit"));
    expectTestIdRule();
    child.view.unmount();

    const home = renderParent(<HomeScreen />, { handler: allOk });
    await screen.findByTestId("home-child-card");
    expectTestIdRule();
    fireEvent.click(screen.getByTestId("home-settings"));
    expectTestIdRule();
    fireEvent.click(screen.getByTestId("settings-delete"));
    expectTestIdRule();
    home.view.unmount();

    const device = renderParent(<DeviceScreen />, { handler: allOk });
    await screen.findByTestId("device-stop-summary");
    expectTestIdRule();
    device.view.unmount();

    renderParent(<StopExpressionsScreen />, { handler: allOk });
    fireEvent.click(await screen.findByTestId("stop-item-edit-1"));
    expectTestIdRule();
  });
});

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
