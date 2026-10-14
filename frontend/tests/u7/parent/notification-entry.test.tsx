import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Home04 } from "@/parent/screens/Home04";
import { SESSION_ID, errorResponse, homeBody, jsonResponse, liveViewBody } from "../../u1/support/fixtures";
import { meBody } from "../../u2/support/accountFixtures";
import { renderParent, type RequestHandler } from "../../u1/support/parentHarness";
import { resetRouter, router } from "../../u1/support/nextNavigation";

vi.mock("next/navigation", async () => (await import("../../u1/support/nextNavigation")).nextNavigationMock);

function renderEntry(session: RequestHandler, options: { me?: ReturnType<typeof meBody>; home?: ReturnType<typeof homeBody> } = {}) {
  return renderParent(<Home04 />, { handler: (path, init) => {
    if (path === "/api/home") return jsonResponse(200, options.home ?? homeBody("STABLE"));
    if (path === "/api/me") return jsonResponse(200, options.me ?? meBody());
    return session(path, init);
  } });
}

beforeEach(() => {
  resetRouter();
  window.history.replaceState(null, "", "/parent?notificationSessionId=" + SESSION_ID);
});
afterEach(() => window.history.replaceState(null, "", "/parent"));

describe("U7 알림 sessionId 재조회", () => {
  it.each(["REQUESTED", "PREPARING", "DEVICE_ACK", "WAITING_CHILD", "ACTIVE"])("최신 %s이면 12로 간다", async (status) => {
    const harness = renderEntry(() => jsonResponse(200, { ...liveViewBody("ACTIVE"), status }));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/sessions/" + SESSION_ID));
    expect(harness.requests("/api/sessions/" + SESSION_ID)).toHaveLength(1);
    expect(harness.requests("/api/sessions/current")).toHaveLength(0);
  });

  it.each(["CLOSING", "ENDED", "ENDED_UNCONFIRMED", "ABORTED", "DECLINED", "FAILED"])("최신 %s이면 17로 간다", async (status) => {
    renderEntry(() => jsonResponse(200, { ...liveViewBody("ACTIVE"), status }));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/sessions/" + SESSION_ID + "/done"));
  });

  it("홈 조회가 성공해도 알림 세션 조회 실패는 미확인이고 다시 확인은 같은 ID를 재조회한다", async () => {
    let attempt = 0;
    const harness = renderEntry(() => ++attempt === 1 ? errorResponse(500, "INTERNAL_ERROR") : jsonResponse(200, { ...liveViewBody("ACTIVE"), status: "ENDED" }));
    await waitFor(() => expect(screen.getByTestId("home-activity-card")).toHaveAttribute("data-state", "unknown"));
    expect(screen.getByTestId("home-activity-status")).toHaveTextContent("지금 상태를 불러오지 못했어요");
    expect(router.replace).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId("home-activity-recheck"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/sessions/" + SESSION_ID + "/done"));
    expect(harness.requests("/api/sessions/" + SESSION_ID)).toHaveLength(2);
  });

  it("모르는 상태는 활동 없음으로 오인하지 않는다", async () => {
    renderEntry(() => jsonResponse(200, { ...liveViewBody("ACTIVE"), status: "FUTURE" }));
    await waitFor(() => expect(screen.getByTestId("home-activity-card")).toHaveAttribute("data-state", "unknown"));
    expect(router.replace).not.toHaveBeenCalled();
  });

  it("401은 기존 로그인 만료 흐름이다", async () => {
    renderEntry(() => errorResponse(401, "UNAUTHENTICATED"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
  });

  it("푸시 없는 일반 진입은 기존 홈 요청만 한다", async () => {
    window.history.replaceState(null, "", "/parent");
    const harness = renderEntry(() => errorResponse(500, "INTERNAL_ERROR"));
    await screen.findByTestId("home-activity-card");
    expect(harness.requests(/\/api\/sessions/)).toHaveLength(0);
    expect(screen.getByTestId("home-activity-card")).toHaveAttribute("data-state", "none");
  });

  it("변조된 ID는 API URL에 넣지 않고 미확인으로 남긴다", async () => {
    window.history.replaceState(null, "", "/parent?notificationSessionId=..%2Fdevice");
    const harness = renderEntry(() => errorResponse(500, "INTERNAL_ERROR"));
    await waitFor(() => expect(screen.getByTestId("home-activity-card")).toHaveAttribute("data-state", "unknown"));
    expect(harness.requests(/\/api\/sessions/)).toHaveLength(0);
  });

  it.each([
    [{ consentStatus: "OUTDATED" }, "/parent/consent"],
    [{ consentStatus: "NOT_GIVEN" }, "/parent/login"],
    [{ childRegistered: false }, "/parent/login"],
    [{ deletionStatus: "IN_PROGRESS" }, "/parent/login?deleting=1"],
  ] as const)("알림 진입도 기존 계정 조건 %j을 먼저 확인한다", async (me, destination) => {
    const harness = renderEntry(() => jsonResponse(200, liveViewBody("ACTIVE")), { me: meBody(me) });
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith(destination));
    expect(harness.requests("/api/sessions/" + SESSION_ID)).toHaveLength(0);
    expect(router.replace).not.toHaveBeenCalledWith("/parent/sessions/" + SESSION_ID);
  });

  it.each([
    ["ENDED", "ACTIVE", "", "/done"],
    ["ACTIVE", "ENDED", "/done", ""],
  ])("캐시 %s 대신 새 %s 조회가 끝난 뒤 이동한다", async (cached, current, suffix, oldSuffix) => {
    let resolveSession!: (value: Response) => void;
    const response = new Promise<Response>((resolve) => { resolveSession = resolve; });
    const harness = renderParent(<Home04 />, {
      prepare: (client) => client.setQueryData(["notification-entry", SESSION_ID], liveViewBody(cached)),
      handler: (path) => path === "/api/home" ? jsonResponse(200, homeBody("STABLE"))
        : path === "/api/me" ? jsonResponse(200, meBody()) : response,
    });
    await waitFor(() => expect(harness.requests("/api/sessions/" + SESSION_ID)).toHaveLength(1));
    expect(router.replace).not.toHaveBeenCalled();
    await act(async () => resolveSession(jsonResponse(200, liveViewBody(current))));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/sessions/" + SESSION_ID + suffix));
    expect(router.replace).not.toHaveBeenCalledWith("/parent/sessions/" + SESSION_ID + oldSuffix);
  });

  it("캐시가 있어도 새 조회가 실패하면 이동하지 않고 미확인으로 남긴다", async () => {
    renderParent(<Home04 />, {
      prepare: (client) => client.setQueryData(["notification-entry", SESSION_ID], liveViewBody("ACTIVE")),
      handler: (path) => path === "/api/home" ? jsonResponse(200, homeBody("STABLE"))
        : path === "/api/me" ? jsonResponse(200, meBody()) : errorResponse(500, "INTERNAL_ERROR"),
    });
    await waitFor(() => expect(screen.getByTestId("home-activity-card")).toHaveAttribute("data-state", "unknown"));
    expect(router.replace).not.toHaveBeenCalled();
  });

  it("계정 확인 401은 세션을 조회하지 않고 로그인으로 보낸다", async () => {
    const harness = renderParent(<Home04 />, { handler: (path) => path === "/api/home"
      ? jsonResponse(200, homeBody("STABLE")) : errorResponse(401, "UNAUTHENTICATED") });
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
    expect(harness.requests("/api/sessions/" + SESSION_ID)).toHaveLength(0);
  });

  it.each(["STABLE", "NONE", "UNSTABLE", "DISCONNECTED", "UNRESPONSIVE", "UNKNOWN"] as const)(
    "푸시 없는 진입에서 기기 %s의 기존 탭 잠금과 이동을 유지한다", async (state) => {
      window.history.replaceState(null, "", "/parent");
      renderEntry(() => errorResponse(500, "INTERNAL_ERROR"), { home: homeBody(state) });
      await screen.findByTestId("home-activity-card");
      fireEvent.click(screen.getByTestId("tab-home"));
      expect(router.push).not.toHaveBeenCalled();
      fireEvent.click(screen.getByTestId("tab-free"));
      expect(router.push).toHaveBeenCalledWith(state === "STABLE" ? "/parent/free" : "/parent/device");
      fireEvent.click(screen.getByTestId("tab-records"));
      expect(router.push).toHaveBeenCalledWith("/parent/records");
    },
  );

  it.each(["ACTIVE", "CLOSING"])("푸시 없는 재진입은 기존 %s 활동 화면으로 이어진다", async (status) => {
    window.history.replaceState(null, "", "/parent");
    const home = { ...homeBody("STABLE"), currentSession: liveViewBody(status) };
    renderEntry(() => errorResponse(500, "INTERNAL_ERROR"), { home });
    const open = await screen.findByTestId("home-activity-open");
    fireEvent.click(open);
    const target = "/parent/sessions/" + SESSION_ID + (status === "CLOSING" ? "/done" : "");
    expect(router.push).toHaveBeenCalledWith(target);
    fireEvent.click(screen.getByTestId("tab-practice"));
    expect(router.push).toHaveBeenLastCalledWith(target);
  });
});
