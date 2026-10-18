import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { D0View } from "@/device/components/D0View";
import { DebugLog } from "@/device/components/DebugLog";
import { T19View, type T19Display } from "@/device/components/T19View";
import { DevLoginScreen } from "@/parent/features/login/DevLoginScreen";
import { DoneScreen } from "@/parent/features/done/DoneScreen";
import { FreeTalkScreen } from "@/parent/features/free/FreeTalkScreen";
import { HomeScreen } from "@/parent/features/home/HomeScreen";
import { LiveScreen } from "@/parent/features/live/LiveScreen";
import { DeviceRegisterScreen } from "@/parent/features/device/DeviceRegisterScreen";
import { SESSION_ID, bubble, errorResponse, homeBody, jsonResponse, liveViewBody } from "../support/fixtures";
import { resetRouter } from "../support/nextNavigation";
import { renderParent } from "../support/parentHarness";

vi.mock("next/navigation", async () => (await import("../support/nextNavigation")).nextNavigationMock);

// data-testid 규칙 시험 (U1 3.5 Step 42 · 계획 '구현 공통 규칙' data-testid · flows 시트 3.4 · BR9.2)
// 화면마다 상호작용 요소(단추 · 링크 · 입력 · 탭 · 칩 · 대화상자 단추 · 조작이 달린 토스트)에 data-testid가 빠진 것이 0건이고,
// 이름은 kebab-case다. D0에는 클릭 요소가 없다. 계획이 정한 후보 이름이 실제로 있다.

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

function interactiveWithoutTestId(root: ParentNode = document.body): string[] {
  return Array.from(root.querySelectorAll<HTMLElement>(INTERACTIVE))
    .filter((element) => !element.hasAttribute("data-testid"))
    .map((element) => element.outerHTML.slice(0, 120));
}

function badTestIds(root: ParentNode = document.body): string[] {
  return Array.from(root.querySelectorAll<HTMLElement>("[data-testid]"))
    .map((element) => element.getAttribute("data-testid") ?? "")
    .filter((testId) => !TEST_ID_PATTERN.test(testId));
}

function expectRuleHolds() {
  expect(interactiveWithoutTestId()).toEqual([]);
  expect(badTestIds()).toEqual([]);
}

beforeEach(() => {
  resetRouter();
});

describe("기기 화면", () => {
  it.each(["d0-checking", "d0-code", "d0-registered"] as const)("D0 %s에는 상호작용 요소가 하나도 없다(BR9.2)", (display) => {
    const { container } = render(<D0View display={display} code="7F3K92QD" />);
    expect(container.querySelectorAll(INTERACTIVE)).toHaveLength(0);
    expect(badTestIds()).toEqual([]);
  });

  it.each<T19Display>(["off", "mic-request", "mic-denied", "connecting", "standby", "listening", "speaking", "closing", "reconnecting"])(
    "T19 %s의 상호작용 요소는 모두 data-testid가 있다",
    (display) => {
      render(<T19View display={display} label="대기" onTurnOn={() => undefined} onTurnOff={() => undefined} />);
      expectRuleHolds();
      expect(screen.queryByTestId("t19-on") ?? screen.queryByTestId("t19-off")).not.toBeNull();
    },
  );

  it("D0 코드 · 상태 로그에도 규칙이 지켜진다", () => {
    render(
      <>
        <D0View display="d0-code" code="7F3K92QD" />
        <DebugLog trail={["d0-checking", "d0-code"]} />
      </>,
    );
    expect(screen.getByTestId("d0-code")).toBeInTheDocument();
    expectRuleHolds();
  });
});

describe("보호자 화면", () => {
  it("04(시작 가능 · 기기 없음)와 잠긴 탭 토스트", async () => {
    const first = renderParent(<HomeScreen />, { handler: () => jsonResponse(200, homeBody("STABLE")) });
    await screen.findByTestId("home-device-card");
    expect(screen.getByTestId("home-device-status")).toBeInTheDocument();
    expectRuleHolds();
    first.view.unmount();
    renderParent(<HomeScreen />, { handler: () => jsonResponse(200, homeBody("NONE")) });
    await screen.findByTestId("home-device-register");
    fireEvent.click(screen.getByTestId("tab-free"));
    await screen.findByTestId("toast");
    expectRuleHolds();
  });

  it("04 오류 토스트의 「다시 시도」", async () => {
    renderParent(<HomeScreen />, { handler: () => errorResponse(500, "INTERNAL_ERROR") });
    await screen.findByTestId("toast-action");
    expectRuleHolds();
  });

  it("05a(빨간 카드 포함)", async () => {
    renderParent(<DeviceRegisterScreen />, { handler: () => errorResponse(404, "CODE_NOT_FOUND") });
    for (let index = 0; index < 8; index += 1) {
      fireEvent.change(screen.getByTestId(`code-cell-${index}`), { target: { value: "7F3K92QD"[index] } });
    }
    fireEvent.click(screen.getByTestId("register-submit"));
    await screen.findByTestId("register-error");
    expectRuleHolds();
  });

  it("08(칩 · 서술 칸 · 사유 카드 포함)", async () => {
    renderParent(<FreeTalkScreen returnedFailed />, { handler: () => jsonResponse(202, { sessionId: SESSION_ID }) });
    expect(screen.getByTestId("free-reason")).toBeInTheDocument();
    expect(screen.getAllByRole("radio")).toHaveLength(7);
    expectRuleHolds();
  });

  it("12 진행(말풍선 · stale 줄) + P1 대화상자", async () => {
    renderParent(<LiveScreen sessionId={SESSION_ID} />, {
      timing: { parentPollIntervalMs: 10_000 },
      handler: () =>
        jsonResponse(200, liveViewBody("ACTIVE", { stale: true, items: [bubble({ seq: 1, speaker: "CHILD", text: "공룡" })], nextSeq: 1 })),
    });
    await waitFor(() => expect(screen.getByTestId("live-end")).toBeEnabled());
    fireEvent.click(screen.getByTestId("live-end"));
    expect(screen.getByTestId("p1-yes")).toBeInTheDocument();
    expect(screen.getByTestId("p1-no")).toBeInTheDocument();
    expectRuleHolds();
  });

  it("17 종료와 시험 로그인", async () => {
    const done = renderParent(<DoneScreen sessionId={SESSION_ID} />, {
      timing: { parentPollIntervalMs: 10_000 },
      handler: () => jsonResponse(200, liveViewBody("ENDED", { endReason: "GUARDIAN_STOP", endedAt: "2026-10-15T01:21:00Z" })),
    });
    await screen.findByText("보호자가 끝냈어요");
    expect(screen.getByTestId("done-home")).toBeInTheDocument();
    expectRuleHolds();
    done.view.unmount();
    renderParent(<DevLoginScreen />, { handler: () => errorResponse(404, "NOT_FOUND") });
    fireEvent.click(screen.getByTestId("dev-login-submit"));
    await screen.findByTestId("dev-login-error");
    expectRuleHolds();
  });
});

describe("계획이 정한 후보 이름(flows 시트 3.4)", () => {
  it("핵심 data-testid가 실제 화면에 있다", async () => {
    render(<D0View display="d0-code" code="7F3K92QD" />);
    expect(screen.getByTestId("d0-code")).toBeInTheDocument();
    render(<T19View display="off" label={null} onTurnOn={() => undefined} onTurnOff={() => undefined} />);
    render(<T19View display="standby" label="대기" onTurnOn={() => undefined} onTurnOff={() => undefined} />);
    expect(screen.getByTestId("t19-on")).toBeInTheDocument();
    expect(screen.getByTestId("t19-off")).toBeInTheDocument();
    const names = ["code-cell-0", "code-cell-7", "register-submit"];
    renderParent(<DeviceRegisterScreen />, { handler: () => errorResponse(500, "INTERNAL_ERROR") });
    for (const name of names) {
      expect(screen.getByTestId(name)).toBeInTheDocument();
    }
  });
});
