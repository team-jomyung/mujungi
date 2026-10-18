import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { HomeScreen } from "@/parent/features/home/HomeScreen";
import { errorResponse, homeBody, jsonResponse, type NetworkState } from "../../../support/fixtures";
import { resetRouter, router } from "../../../support/nextNavigation";
import { renderParent, type RequestHandler } from "../../../support/parentHarness";
import { meBody, noContent } from "../../../support/accountFixtures";

vi.mock("next/navigation", async () => (await import("../../../support/nextNavigation")).nextNavigationMock);

// 04 홈 U2 구역 · SET0 · P7 시험 (U2 · US1.4 · US1.5 · US1.6 · AC1.4.1 · AC1.4.6 · AC1.4.12 · AC1.4.14 · AC1.5.1 ~ AC1.5.4 ·
// AC1.6.1 · AC1.6.2 · AC1.6.9 · U2-J9 · U2-J15)

function handler(state: NetworkState, overrides: Partial<Record<string, RequestHandler>> = {}): RequestHandler {
  return (path, init) => {
    const key = `${init.method ?? "GET"} ${path}`;
    const override = overrides[key];
    if (override !== undefined) {
      return override(path, init);
    }
    switch (key) {
      case "GET /api/home":
        return jsonResponse(200, homeBody(state));
      case "GET /api/me":
        return jsonResponse(200, meBody());
      case "POST /api/auth/logout":
        return noContent();
      case "DELETE /api/me":
        return jsonResponse(202, { deletionRequestId: "00000000-0000-4000-8000-000000000050" });
      default:
        return errorResponse(404, "NOT_FOUND");
    }
  };
}

beforeEach(() => {
  resetRouter();
});

describe("04 아이 구역(AC1.4.1)", () => {
  it("'아이' · '하늘 · 8세' · 「수정」 → 03", async () => {
    renderParent(<HomeScreen />, { handler: handler("STABLE") });
    const card = await screen.findByTestId("home-child-card");
    expect(card).toHaveTextContent("아이");
    expect(card).toHaveTextContent("하늘 · 8세");
    fireEvent.click(screen.getByTestId("home-child-edit"));
    expect(router.push).toHaveBeenCalledWith("/parent/child");
  });

  it("아이가 없으면 아이 구역을 그리지 않는다", async () => {
    renderParent(<HomeScreen />, { handler: handler("STABLE", { "GET /api/home": () => jsonResponse(200, homeBody("STABLE", { childName: null })) }) });
    await screen.findByTestId("home-device-card");
    expect(screen.queryByTestId("home-child-card")).toBeNull();
  });
});

describe("04 지금 활동 구역(틀, AC1.4.6 · AC1.4.14 · J18)", () => {
  it.each<[NetworkState, string, string]>([
    ["STABLE", "진행 중인 활동 없음", "자유대화 · 연습 탭에서 시작해요."],
    ["NONE", "지금은 시작할 수 없어요", "기기를 먼저 등록해 주세요."],
    ["UNSTABLE", "지금은 시작할 수 없어요", "기기 네트워크가 불안정해요. 안정되면 자유대화 · 연습 탭이 열려요."],
    ["DISCONNECTED", "지금은 시작할 수 없어요", "기기 연결이 끊겼어요. 전원과 와이파이를 확인해 주세요."],
    ["UNRESPONSIVE", "지금은 시작할 수 없어요", "기기가 응답하지 않아요. 기기를 다시 켜 보세요."],
    ["UNKNOWN", "지금은 시작할 수 없어요", "기기 상태를 확인할 수 없어 시작할 수 없어요"],
  ])("기기 %s → '%s' + 사유", async (state, status, reason) => {
    renderParent(<HomeScreen />, { handler: handler(state) });
    const card = await screen.findByTestId("home-activity-card");
    expect(card).toHaveTextContent("지금 활동");
    expect(screen.getByTestId("home-activity-status")).toHaveTextContent(status);
    expect(card).toHaveTextContent(reason);
    expect(card).toHaveAttribute("data-state", state === "STABLE" ? "none" : "locked");
  });
});

describe("04 앱 시작 점검(U2-J9 · AC1.1.5 · AC1.2.7 · AC1.6.4 · U2-J8 · 편차 (U2-g))", () => {
  it.each([
    [{ consentStatus: "NOT_GIVEN", childRegistered: false }, "/parent/login"],
    [{ consentStatus: "GIVEN", childRegistered: false }, "/parent/login"],
    // 리뷰 6: 약관이 바뀐 로그인된 보호자는 01이 아니라 02 재동의
    [{ consentStatus: "OUTDATED", childRegistered: true }, "/parent/consent"],
    [{ consentStatus: "OUTDATED", childRegistered: false }, "/parent/consent"],
    [{ deletionStatus: "IN_PROGRESS" }, "/parent/login?deleting=1"],
  ])("Me %j이면 %s", async (options, route) => {
    renderParent(<HomeScreen />, { handler: handler("STABLE", { "GET /api/me": () => jsonResponse(200, meBody(options)) }) });
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith(route));
    expect(router.replace).toHaveBeenCalledTimes(1);
  });

  it("처음 한 번을 마쳤으면 머문다", async () => {
    const harness = renderParent(<HomeScreen />, { handler: handler("STABLE") });
    await screen.findByTestId("home-child-card");
    await waitFor(() => expect(harness.requests("/api/me")).toHaveLength(1));
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(router.replace).not.toHaveBeenCalled();
  });
});

describe("SET0 설정 시트(AC1.4.12 · AC1.5.1 ~ AC1.5.4)", () => {
  async function openSettings(overrides: Partial<Record<string, RequestHandler>> = {}) {
    const harness = renderParent(<HomeScreen />, { handler: handler("STABLE", overrides) });
    await screen.findByTestId("home-child-card");
    fireEvent.click(screen.getByTestId("home-settings"));
    return harness;
  }

  it("「⚙」은 '← 설정' · 「로그아웃」 · 「계정 삭제」(위험 색) 시트를 열고 「←」 · 바깥 · Escape로 닫힌다", async () => {
    await openSettings();
    expect(screen.getByTestId("home-settings")).toHaveAccessibleName("설정 열기");
    const sheet = screen.getByTestId("settings-sheet");
    expect(sheet).toHaveAttribute("role", "dialog");
    expect(screen.getByRole("heading", { name: "설정" })).toHaveFocus();
    expect(screen.getByTestId("settings-logout")).toHaveTextContent("로그아웃");
    expect(screen.getByTestId("settings-delete")).toHaveTextContent("계정 삭제");
    expect(screen.getByTestId("settings-delete").className).toContain("mj-button--danger");
    fireEvent.click(screen.getByTestId("settings-back"));
    expect(screen.queryByTestId("settings-sheet")).toBeNull();
    fireEvent.click(screen.getByTestId("home-settings"));
    fireEvent.click(screen.getByTestId("settings-backdrop"));
    expect(screen.queryByTestId("settings-sheet")).toBeNull();
    fireEvent.click(screen.getByTestId("home-settings"));
    fireEvent.keyDown(screen.getByTestId("settings-sheet"), { key: "Escape" });
    expect(screen.queryByTestId("settings-sheet")).toBeNull();
  });

  it("「로그아웃」은 세션만 끝내고 01로 간다(세션 종료 요청 아님, AC1.5.5)", async () => {
    const harness = await openSettings();
    fireEvent.click(screen.getByTestId("settings-logout"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
    expect(harness.requests("/api/auth/logout")).toHaveLength(1);
    expect(harness.requests(/\/api\/sessions\//)).toHaveLength(0);
  });

  it("이미 세션이 끝났으면(401) 로그아웃도 01이다", async () => {
    await openSettings({ "POST /api/auth/logout": () => errorResponse(401, "UNAUTHENTICATED") });
    fireEvent.click(screen.getByTestId("settings-logout"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
  });

  it("로그아웃 실패는 토스트이고 01로 가지 않는다", async () => {
    await openSettings({ "POST /api/auth/logout": () => errorResponse(500, "INTERNAL_ERROR") });
    fireEvent.click(screen.getByTestId("settings-logout"));
    expect(await screen.findByTestId("toast")).toHaveTextContent("로그아웃하지 못했어요. 다시 시도해 주세요.");
    expect(router.replace).not.toHaveBeenCalledWith("/parent/login");
  });
});

describe("P7 계정 삭제 확인(AC1.6.1 · AC1.6.2 · AC1.6.9)", () => {
  async function openP7(overrides: Partial<Record<string, RequestHandler>> = {}) {
    const harness = renderParent(<HomeScreen />, { handler: handler("STABLE", overrides) });
    await screen.findByTestId("home-child-card");
    fireEvent.click(screen.getByTestId("home-settings"));
    fireEvent.click(screen.getByTestId("settings-delete"));
    return harness;
  }

  it("제목 · '{아이}의 …' 본문 · 「아니오」(기본 초점) · 「삭제」(위험 단추)이고 「아니오」는 SET0으로 돌아온다", async () => {
    await openP7();
    const dialog = screen.getByRole("alertdialog");
    expect(dialog).toHaveTextContent("정말 계정을 삭제할까요?");
    expect(dialog).toHaveTextContent(
      "하늘의 기록 · 리포트 · 동의 · 기기 등록이 모두 지워지고 되돌릴 수 없어요. 남길 리포트는 먼저 저장해 두세요.",
    );
    expect(screen.getByTestId("p7-no")).toHaveFocus();
    expect(screen.getByTestId("p7-yes")).toHaveTextContent("삭제");
    expect(screen.getByTestId("p7-yes").className).toContain("mj-button--danger");
    fireEvent.click(screen.getByTestId("p7-no"));
    expect(screen.queryByTestId("p7-dialog")).toBeNull();
    expect(screen.getByTestId("settings-sheet")).toBeInTheDocument();
  });

  it("「삭제」는 접수(DELETE /api/me, CSRF) 뒤 01로 간다", async () => {
    const harness = await openP7();
    fireEvent.click(screen.getByTestId("p7-yes"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
    const deleted = harness.requests("/api/me").find((request) => request.method === "DELETE");
    expect(deleted?.headers["x-csrf-token"]).toBe(harness.csrfToken);
  });

  it("접수 실패는 토스트이고 P7에 남는다", async () => {
    await openP7({ "DELETE /api/me": () => errorResponse(500, "INTERNAL_ERROR") });
    fireEvent.click(screen.getByTestId("p7-yes"));
    expect(await screen.findByTestId("toast")).toHaveTextContent("계정 삭제를 접수하지 못했어요. 다시 시도해 주세요.");
    expect(router.replace).not.toHaveBeenCalledWith("/parent/login");
    expect(screen.getByTestId("p7-yes")).toBeEnabled();
  });

  it("이미 세션이 끝났으면(401) 01이다", async () => {
    await openP7({ "DELETE /api/me": () => errorResponse(401, "UNAUTHENTICATED") });
    fireEvent.click(screen.getByTestId("p7-yes"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
  });
});
