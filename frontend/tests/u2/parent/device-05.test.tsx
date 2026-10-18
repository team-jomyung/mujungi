import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { presentDeviceScreen, DeviceScreen } from "@/parent/features/device/DeviceScreen";
import { parseDeviceView } from "@/parent/common/api/types";
import { errorResponse, homeBody, jsonResponse, type NetworkState } from "../../u1/support/fixtures";
import { resetRouter, router } from "../../u1/support/nextNavigation";
import { renderParent, type RequestHandler } from "../../u1/support/parentHarness";
import { stopBody } from "../support/accountFixtures";

vi.mock("next/navigation", async () => (await import("../../u1/support/nextNavigation")).nextNavigationMock);

// 05 우리 집 기기 시험 (U2 · US2.1 · AC2.1.1 ~ AC2.1.9 · AC2.1.12 ~ AC2.1.14 · mockups S-05 · 9-8 · interaction-spec 4절 05)
// 10:32(KST) = 01:32Z, 10:30 = 01:30Z, 10:00 = 01:00Z(fixtures homeBody).

function handler(
  state: NetworkState,
  home: Record<string, unknown> = homeBody(state),
  overrides: Partial<Record<string, RequestHandler>> = {},
): RequestHandler {
  return (path, init) => {
    const key = `${init.method ?? "GET"} ${path}`;
    const override = overrides[key];
    if (override !== undefined) {
      return override(path, init);
    }
    switch (key) {
      case "GET /api/home":
        return jsonResponse(200, home);
      case "GET /api/child/stop-expressions":
        return jsonResponse(200, stopBody(["이제 그만", "끝낼래"]));
      default:
        return errorResponse(404, "NOT_FOUND");
    }
  };
}

function withNetwork(state: NetworkState, network: Record<string, unknown>): Record<string, unknown> {
  const body = homeBody(state);
  const device = body.device as Record<string, unknown>;
  return { ...body, device: { ...device, network: { ...(device.network as Record<string, unknown>), ...network } } };
}

beforeEach(() => {
  resetRouter();
});

describe("05 두 줄 + 판정 카드(AC2.1.1 ~ AC2.1.6 · AC2.1.13 · AC2.1.14)", () => {
  it("등록 없음: 등록 줄 '없음', 네트워크 줄 '등록 뒤 표시', 판정 '기기를 등록해 주세요' + 「기기 등록」 → 05a, 탭 잠김", async () => {
    renderParent(<DeviceScreen />, { handler: handler("NONE") });
    expect(await screen.findByTestId("device-registration")).toHaveTextContent("기기 등록 · 없음");
    expect(screen.getByTestId("device-network")).toHaveTextContent("기기 네트워크 현황 · 등록 뒤 표시");
    const verdict = screen.getByTestId("device-verdict");
    expect(verdict).toHaveTextContent("기기를 등록해 주세요");
    expect(verdict).toHaveTextContent("아이 기기에 뜬 8자리 코드를 넣어요.");
    fireEvent.click(screen.getByTestId("device-register"));
    expect(router.push).toHaveBeenCalledWith("/parent/device/register");
    expect(screen.getByTestId("tab-free")).toHaveAttribute("aria-disabled", "true");
  });

  it("안정: '완료 (7F3K)' · '안정' + '지연 0.3초' · '시작할 준비가 되었어요' · 머리 '마지막 확인 10:32'", async () => {
    renderParent(<DeviceScreen />, { handler: handler("STABLE", withNetwork("STABLE", { rttMs: 300 })) });
    expect(await screen.findByTestId("device-registration")).toHaveTextContent("기기 등록 · 완료 (7F3K)");
    expect(screen.getByTestId("device-network")).toHaveTextContent("기기 네트워크 현황 · 안정");
    expect(screen.getByTestId("device-network-detail")).toHaveTextContent("지연 0.3초");
    expect(screen.getByTestId("device-verdict")).toHaveTextContent("시작할 준비가 되었어요");
    expect(screen.queryByTestId("device-register")).toBeNull();
    expect(screen.getByTestId("device-last-checked")).toHaveTextContent("마지막 확인 10:32");
    expect(screen.getByRole("heading", { name: "대화 기기 상태" })).toBeInTheDocument();
    expect(screen.getByTestId("tab-free")).not.toHaveAttribute("aria-disabled");
  });

  it.each<[NetworkState, Record<string, unknown>, string, string | null, string]>([
    ["UNSTABLE", { recentDisconnects: 3, rttMs: 1200 }, "불안정", "끊김 3회 · 지연 1.2초",
      "기기 네트워크가 불안정해요. 기기를 공유기 가까이 두거나 와이파이를 확인해 주세요."],
    ["DISCONNECTED", { since: "2026-10-15T01:34:00Z" }, "끊김", "10:34부터", "기기 연결이 끊겼어요. 기기 전원과 와이파이를 확인해 주세요."],
    ["UNRESPONSIVE", {}, "응답 없음", "마지막 10:30", "기기가 응답하지 않아요. 기기를 다시 켜 보세요."],
    ["UNKNOWN", {}, "확인 안 됨", "마지막 10:32", "기기 상태를 확인할 수 없어요"],
  ])("%s: 네트워크 줄 · 판정 '지금은 시작할 수 없어요' + 사유(색 점 + 아이콘 + 상태명)", async (state, network, name, detail, reason) => {
    renderParent(<DeviceScreen />, { handler: handler(state, withNetwork(state, network)) });
    const rows = await screen.findByTestId("device-rows");
    expect(rows).toHaveAttribute("data-state", state.toLowerCase());
    expect(screen.getByTestId("device-network")).toHaveTextContent(`기기 네트워크 현황 · ${name}`);
    expect(rows.querySelector(".mj-status-dot")?.textContent).not.toBe("");
    if (detail !== null) {
      expect(screen.getByTestId("device-network-detail")).toHaveTextContent(detail);
    }
    const verdict = screen.getByTestId("device-verdict");
    expect(verdict).toHaveAttribute("role", "status");
    expect(verdict).toHaveTextContent("지금은 시작할 수 없어요");
    expect(verdict).toHaveTextContent(reason);
  });

  it("시각 · 지연 값이 없으면 보조 줄을 뺀다", () => {
    const device = parseDeviceView((withNetwork("DISCONNECTED", { since: null }).device as Record<string, unknown>));
    expect(presentDeviceScreen(device).networkDetail).toBeNull();
    const stable = parseDeviceView((withNetwork("STABLE", { rttMs: null }).device as Record<string, unknown>));
    expect(presentDeviceScreen(stable).networkDetail).toBeNull();
    const unresponsive = parseDeviceView((withNetwork("UNRESPONSIVE", { lastSeenAt: null, lastReportedAt: null }).device as Record<string, unknown>));
    expect(presentDeviceScreen(unresponsive).networkDetail).toBeNull();
    const unknown = parseDeviceView((withNetwork("UNKNOWN", { lastSeenAt: null, lastReportedAt: null }).device as Record<string, unknown>));
    expect(presentDeviceScreen(unknown).networkDetail).toBeNull();
    const noPrefix = parseDeviceView({ ...(homeBody("STABLE").device as Record<string, unknown>), registration: { status: "REGISTERED", codePrefix: null } });
    expect(presentDeviceScreen(noPrefix).registration).toBe("기기 등록 · 완료");
  });
});

describe("05 종료 표현 구역 · 이동(AC2.1.7 · AC2.1.8 · AC2.1.12)", () => {
  it("'{아이}의 종료 표현' · 기본 표현 · 추가 요약 · 「설정」 → SET-01", async () => {
    renderParent(<DeviceScreen />, { handler: handler("STABLE") });
    const section = await screen.findByTestId("device-stop-section");
    expect(section).toHaveTextContent("하늘의 종료 표현");
    expect(section).toHaveTextContent("“무중아 그만할래”");
    expect(await screen.findByTestId("device-stop-summary")).toHaveTextContent("추가 표현 2개 · “이제 그만” · “끝낼래”");
    fireEvent.click(screen.getByTestId("device-stop-settings"));
    expect(router.push).toHaveBeenCalledWith("/parent/child/stop-expressions");
  });

  it("추가 표현이 없으면 요약 줄이 없고 아이가 없으면 구역이 없다", async () => {
    const first = renderParent(<DeviceScreen />, {
      handler: handler("STABLE", homeBody("STABLE"), { "GET /api/child/stop-expressions": () => jsonResponse(200, stopBody([])) }),
    });
    await screen.findByTestId("device-stop-section");
    expect(screen.queryByTestId("device-stop-summary")).toBeNull();
    first.view.unmount();

    renderParent(<DeviceScreen />, {
      handler: handler("STABLE", homeBody("STABLE", { childName: null }), {
        "GET /api/child/stop-expressions": () => errorResponse(404, "CHILD_NOT_REGISTERED"),
      }),
    });
    await screen.findByTestId("device-rows");
    expect(screen.queryByTestId("device-stop-section")).toBeNull();
  });

  it("「←」은 04, 열린 탭은 이동, 잠긴 탭은 이유 토스트만이다", async () => {
    const first = renderParent(<DeviceScreen />, { handler: handler("STABLE") });
    await screen.findByTestId("device-rows");
    fireEvent.click(screen.getByTestId("device-back"));
    fireEvent.click(screen.getByTestId("tab-free"));
    fireEvent.click(screen.getByTestId("tab-records"));
    expect(router.push.mock.calls).toEqual([["/parent"], ["/parent/free"], ["/parent/records"]]);
    first.view.unmount();
    resetRouter();

    renderParent(<DeviceScreen />, { handler: handler("UNSTABLE") });
    await screen.findByTestId("device-rows");
    fireEvent.click(screen.getByTestId("tab-practice"));
    expect(screen.getByTestId("toast")).toHaveTextContent("기기가 안정될 때까지 시작할 수 없어요");
    expect(router.push).not.toHaveBeenCalled();
  });
});

describe("05 조회 실패", () => {
  it("실패는 토스트 + 「다시 시도」이고 읽기 전에는 잠근다 · 401은 01", async () => {
    let calls = 0;
    const first = renderParent(<DeviceScreen />, {
      handler: handler("STABLE", homeBody("STABLE"), {
        "GET /api/home": () => {
          calls += 1;
          return calls === 1 ? errorResponse(500, "INTERNAL_ERROR") : jsonResponse(200, homeBody("STABLE"));
        },
      }),
    });
    expect(screen.getByTestId("device-loading")).toHaveAttribute("aria-busy", "true");
    expect(screen.getByTestId("tab-free")).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(await screen.findByTestId("toast-action"));
    expect(await screen.findByTestId("device-rows")).toHaveAttribute("data-state", "stable");
    first.view.unmount();

    renderParent(<DeviceScreen />, { handler: () => errorResponse(401, "UNAUTHENTICATED") });
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
  });
});
