import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Home04 } from "@/parent/screens/Home04";
import { errorResponse, homeBody, jsonResponse, type NetworkState } from "../support/fixtures";
import { resetRouter, router } from "../support/nextNavigation";
import { renderParent } from "../support/parentHarness";

vi.mock("next/navigation", async () => (await import("../support/nextNavigation")).nextNavigationMock);

// 04 홈 시험 (U1 3.5 Step 42 · BR4.5 · BR4.6 · BR2.9 · J57 · J118 · AC1.4.2 ~ AC1.4.5 · AC1.4.9 · AC1.4.11 · AC1.4.15 · AC2.1.1 ~ AC2.1.6 · PD-12)
// 대화 기기 구역 6상태(색 + 아이콘 + 상태명 + 사유), 「기기 등록」은 등록이 없을 때만, 탭은 canStart일 때만 열리고
// 잠긴 탭은 aria-disabled + 이유 토스트(mockups 9-6) 뒤 05(U1 스텁 → 04). 진입 때 1회 조회.

function renderHome(body: Record<string, unknown>) {
  return renderParent(<Home04 />, { handler: () => jsonResponse(200, body) });
}

beforeEach(() => {
  resetRouter();
});

describe("04 대화 기기 구역 — 6상태 표시(BR4.6)", () => {
  it.each<[NetworkState, string, string | null, string | null]>([
    ["STABLE", "안정", null, "마지막 확인 10:32"],
    ["UNSTABLE", "불안정", "안정될 때까지 시작할 수 없어요", null],
    ["DISCONNECTED", "끊김", "전원 · 와이파이를 확인해 주세요", null],
    ["UNRESPONSIVE", "응답 없음", "기기를 다시 켜 보세요", "마지막 10:30"],
    ["UNKNOWN", "기기 상태 확인 불가", null, "마지막 확인 10:32"],
  ])("%s → '%s' · 사유 · 시간 줄 + 「기기 상태 보기 ›」(AC2.1.1 ~ AC2.1.6)", async (state, name, reason, timeLine) => {
    renderHome(homeBody(state));
    const card = await screen.findByTestId("home-device-card");
    expect(card).toHaveAttribute("data-state", state.toLowerCase());
    expect(screen.getByTestId("home-device-state-name")).toHaveTextContent(name);
    if (reason !== null) {
      expect(card).toHaveTextContent(reason);
    }
    if (timeLine !== null) {
      expect(card).toHaveTextContent(timeLine);
    }
    // 색만으로 구분하지 않는다: 아이콘 글자 + 상태명.
    expect(card.querySelector(".mj-status-dot")?.textContent).not.toBe("");
    expect(screen.getByTestId("home-device-status")).toHaveTextContent("기기 상태 보기 ›");
    expect(screen.queryByTestId("home-device-register")).toBeNull();
    fireEvent.click(screen.getByTestId("home-device-status"));
    expect(router.push).toHaveBeenCalledWith("/parent/device");
  });

  it("기기 없음은 '연결된 기기 없음' + 안내 + 「기기 등록」 → 05a이고 「기기 상태 보기」는 없다(BR2.9 · AC1.4.4)", async () => {
    renderHome(homeBody("NONE"));
    const card = await screen.findByTestId("home-device-card");
    expect(card).toHaveAttribute("data-state", "none");
    expect(card).toHaveTextContent("연결된 기기 없음");
    expect(card).toHaveTextContent("아이 기기에 뜬 코드로 등록해요.");
    expect(screen.queryByTestId("home-device-status")).toBeNull();
    fireEvent.click(screen.getByTestId("home-device-register"));
    expect(router.push).toHaveBeenCalledWith("/parent/device/register");
  });

  it("시각이 없으면 시간 줄을 빼고 상태명은 그대로 보인다", async () => {
    renderHome(homeBody("UNRESPONSIVE", { lastSeenAt: null, lastReportedAt: null }));
    const card = await screen.findByTestId("home-device-card");
    expect(card).toHaveTextContent("응답 없음");
    expect(card.querySelector(".mj-device-card__time")).toBeNull();
  });

  it("제목은 읽기 전에는 서비스 이름이고 읽으면 '{아이}의 오늘'이다(C.S-04.3.1)", async () => {
    renderHome(homeBody("STABLE"));
    expect(screen.getByTestId("home-title")).toHaveTextContent("무중");
    await screen.findByTestId("home-device-card");
    expect(screen.getByTestId("home-title")).toHaveTextContent("하늘의 오늘");
  });

  it("아이 정보가 없으면 제목이 '무중'이다", async () => {
    renderHome(homeBody("NONE", { childName: null }));
    await screen.findByTestId("home-device-card");
    expect(screen.getByTestId("home-title")).toHaveTextContent("무중");
  });
});

describe("04 하단 탭 — 잠금(BR4.5 · J57 · AC1.4.9 · AC1.4.11 · AC1.4.15)", () => {
  it("시작 가능이면 탭이 열려 자유대화 → 08 · 연습 · 기록 스텁으로 간다", async () => {
    renderHome(homeBody("STABLE"));
    await screen.findByTestId("home-device-card");
    expect(screen.getByRole("tablist")).toBeInTheDocument();
    expect(screen.getByTestId("tab-home")).toHaveAttribute("aria-selected", "true");
    expect(screen.getByTestId("tab-free")).not.toHaveAttribute("aria-disabled");
    fireEvent.click(screen.getByTestId("tab-free"));
    fireEvent.click(screen.getByTestId("tab-practice"));
    fireEvent.click(screen.getByTestId("tab-records"));
    fireEvent.click(screen.getByTestId("tab-home"));
    expect(router.push.mock.calls).toEqual([["/parent/free"], ["/parent/practice"], ["/parent/records"]]);
  });

  it.each<[NetworkState, string]>([
    ["NONE", "먼저 기기를 등록해 주세요"],
    ["UNSTABLE", "기기가 안정될 때까지 시작할 수 없어요"],
    ["DISCONNECTED", "기기 연결이 끊겨 시작할 수 없어요"],
    ["UNRESPONSIVE", "기기가 응답하지 않아 시작할 수 없어요"],
    ["UNKNOWN", "기기 상태를 확인할 수 없어 시작할 수 없어요"],
  ])("%s이면 자유대화 · 연습 탭이 잠기고 누르면 '%s' 토스트 뒤 05로 간다", async (state, message) => {
    renderHome(homeBody(state));
    await screen.findByTestId("home-device-card");
    for (const tab of ["tab-free", "tab-practice"]) {
      expect(screen.getByTestId(tab)).toHaveAttribute("aria-disabled", "true");
      expect(screen.getByTestId(tab)).toBeEnabled();
    }
    expect(screen.getByTestId("tab-records")).not.toHaveAttribute("aria-disabled");
    fireEvent.click(screen.getByTestId("tab-free"));
    expect(screen.getByTestId("toast")).toHaveTextContent(message);
    expect(router.push).toHaveBeenCalledWith("/parent/device");
    expect(router.push).not.toHaveBeenCalledWith("/parent/free");
  });

  it("홈을 아직 못 읽었으면 판정을 모르므로 잠근다(닫힌 쪽) · 자리 카드를 보인다", async () => {
    renderParent(<Home04 />, { handler: () => new Promise<Response>(() => undefined) });
    expect(screen.getByTestId("home-device-loading")).toHaveAttribute("aria-busy", "true");
    expect(screen.getByTestId("tab-free")).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(screen.getByTestId("tab-practice"));
    expect(screen.getByTestId("toast")).toHaveTextContent("기기 상태를 확인할 수 없어 시작할 수 없어요");
  });
});

describe("04 조회(PD-12)", () => {
  it("진입 때 홈을 한 번만 읽는다", async () => {
    const harness = renderHome(homeBody("STABLE"));
    await screen.findByTestId("home-device-card");
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(harness.requests("/api/home")).toHaveLength(1);
  });

  it("실패하면 토스트 + 「다시 시도」이고 다시 시도하면 다시 읽는다", async () => {
    let calls = 0;
    renderParent(<Home04 />, {
      handler: () => {
        calls += 1;
        return calls === 1 ? errorResponse(500, "INTERNAL_ERROR") : jsonResponse(200, homeBody("STABLE"));
      },
    });
    expect(await screen.findByTestId("toast")).toHaveTextContent("잠시 문제가 생겼어요. 다시 시도해 주세요.");
    fireEvent.click(screen.getByTestId("toast-action"));
    expect(await screen.findByTestId("home-device-card")).toHaveAttribute("data-state", "stable");
    expect(calls).toBe(2);
  });

  it("401이면 시험 로그인으로 보낸다(AC1.1.9 · J119)", async () => {
    renderParent(<Home04 />, { handler: () => errorResponse(401, "UNAUTHENTICATED") });
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/dev-login"));
    expect(screen.queryByTestId("toast")).toBeNull();
  });
});
