import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SettingsSheet } from "@/parent/components/SettingsSheet";
import { errorResponse, jsonResponse } from "../../u1/support/fixtures";
import { renderParent, type RequestHandler } from "../../u1/support/parentHarness";
import { resetRouter, router } from "../../u1/support/nextNavigation";

const browser = vi.hoisted(() => ({
  supported: vi.fn(), permission: vi.fn(), requestPermission: vi.fn(), getSubscription: vi.fn(), subscribe: vi.fn(),
}));
vi.mock("@/parent/push/browserPush", () => ({ browserPush: browser }));
vi.mock("next/navigation", async () => (await import("../../u1/support/nextNavigation")).nextNavigationMock);

const endpoint = "https://push.example.test/subscription/current-device";
const subscription = () => ({ endpoint, toJSON: () => ({ endpoint, keys: { p256dh: "public-test-key", auth: "test-auth" } }), unsubscribe: vi.fn(async () => true) });

function renderSettings(options: { config?: unknown; subscription?: ReturnType<typeof subscription>; write?: RequestHandler } = {}) {
  browser.getSubscription.mockResolvedValue(options.subscription ?? null);
  return renderParent(<SettingsSheet open childName={null} onClose={vi.fn()} />, { handler: (path, init) => {
    if (path.endsWith("/vapid-public-key")) return jsonResponse(200, options.config ?? { enabled: true, publicKey: "runtime-public-key" });
    return options.write?.(path, init) ?? new Response(null, { status: 204 });
  } });
}

beforeEach(() => {
  vi.clearAllMocks();
  resetRouter();
  browser.supported.mockReturnValue(true);
  browser.permission.mockReturnValue("default");
  browser.requestPermission.mockResolvedValue("granted");
  browser.subscribe.mockResolvedValue(subscription());
});

describe("U7 SET0 현재 기기 알림", () => {
  it("열기는 권한 요청·새 구독·PUT을 하지 않고 켜기에서만 순서대로 처리한다", async () => {
    const harness = renderSettings();
    const enable = await screen.findByTestId("push-enable");
    await waitFor(() => expect(enable).toBeEnabled());
    expect(browser.requestPermission).not.toHaveBeenCalled();
    expect(browser.subscribe).not.toHaveBeenCalled();
    expect(harness.requests("/api/push-subscription")).toHaveLength(0);
    fireEvent.click(enable);
    await waitFor(() => expect(screen.getByTestId("push-status")).toHaveAttribute("data-state", "on"));
    expect(browser.requestPermission).toHaveBeenCalledOnce();
    expect(browser.subscribe).toHaveBeenCalledOnce();
    const request = harness.requests("/api/push-subscription")[0];
    expect(request.method).toBe("PUT");
    expect(request.body).toEqual({ endpoint, keys: { p256dh: "public-test-key", auth: "test-auth" }, platform: "WEB" });
    expect(request.headers["x-csrf-token"]).toBe(harness.csrfToken);
  });

  it("브라우저 구독만으로 켜짐을 추정하지 않으며 현재 endpoint만 DELETE 후 해제한다", async () => {
    const current = subscription();
    const order: string[] = [];
    current.unsubscribe.mockImplementation(async () => { order.push("browser"); return true; });
    const harness = renderSettings({ subscription: current, write: (_path, init) => { order.push(init.method ?? ""); return new Response(null, { status: 204 }); } });
    const disable = await screen.findByTestId("push-disable");
    expect(screen.getByTestId("push-status")).not.toHaveAttribute("data-state", "on");
    fireEvent.click(disable);
    await waitFor(() => expect(current.unsubscribe).toHaveBeenCalledOnce());
    expect(order).toEqual(["DELETE", "browser"]);
    expect(harness.requests("/api/push-subscription")[0].body).toEqual({ endpoint });
    expect(screen.getByTestId("push-status")).toHaveAttribute("data-state", "off");
  });

  it("서버 키 미설정은 켜기만 막고 기존 구독 끄기·로그아웃을 유지한다", async () => {
    const current = subscription();
    renderSettings({ config: { enabled: false, publicKey: null }, subscription: current });
    await screen.findByTestId("push-disable");
    expect(screen.getByTestId("push-enable")).toBeDisabled();
    expect(screen.getByTestId("push-status")).toHaveTextContent("알림을 준비하고 있어요");
    expect(screen.getByTestId("settings-logout")).toBeEnabled();
    fireEvent.click(screen.getByTestId("push-disable"));
    await waitFor(() => expect(current.unsubscribe).toHaveBeenCalledOnce());
    expect(browser.requestPermission).not.toHaveBeenCalled();
  });

  it.each(["denied", "default"])("권한 응답 %s이면 PUT·구독 없이 안내한다", async (permission) => {
    browser.requestPermission.mockResolvedValue(permission);
    const harness = renderSettings();
    await waitFor(() => expect(screen.getByTestId("push-enable")).toBeEnabled());
    fireEvent.click(screen.getByTestId("push-enable"));
    await waitFor(() => expect(browser.requestPermission).toHaveBeenCalledOnce());
    expect(browser.subscribe).not.toHaveBeenCalled();
    expect(harness.requests("/api/push-subscription")).toHaveLength(0);
    expect(screen.getByTestId("push-status")).not.toHaveAttribute("data-state", "on");
  });

  it("미지원 브라우저는 알림만 비활성이고 서버와 권한 API를 부르지 않는다", async () => {
    browser.supported.mockReturnValue(false);
    const harness = renderSettings();
    expect(await screen.findByTestId("push-enable")).toBeDisabled();
    expect(screen.getByTestId("push-status")).toHaveTextContent("이 브라우저에서는 알림을 지원하지 않아요");
    expect(harness.requests(/push-subscription/)).toHaveLength(0);
    expect(browser.requestPermission).not.toHaveBeenCalled();
    expect(screen.getByTestId("settings-delete")).toBeEnabled();
  });

  it("DELETE 실패는 구독을 잃지 않고 같은 endpoint로 다시 시도한다", async () => {
    const current = subscription();
    let count = 0;
    const harness = renderSettings({ subscription: current, write: () => ++count === 1 ? errorResponse(500, "INTERNAL_ERROR") : new Response(null, { status: 204 }) });
    fireEvent.click(await screen.findByTestId("push-disable"));
    await waitFor(() => expect(screen.getByTestId("push-status")).toHaveTextContent("알림을 끄지 못했어요"));
    expect(current.unsubscribe).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId("push-disable"));
    await waitFor(() => expect(current.unsubscribe).toHaveBeenCalledOnce());
    expect(harness.requests("/api/push-subscription").map((request) => request.body)).toEqual([{ endpoint }, { endpoint }]);
  });

  it("타인 endpoint 충돌은 켜짐으로 표시하지 않고 명시적 재등록에서만 기존 구독을 해제한다", async () => {
    const current = subscription();
    const harness = renderSettings({ subscription: current, write: () => errorResponse(409, "PUSH_SUBSCRIPTION_CONFLICT") });
    await waitFor(() => expect(screen.getByTestId("push-enable")).toBeEnabled());
    fireEvent.click(screen.getByTestId("push-enable"));
    const retry = await screen.findByTestId("push-reregister");
    expect(current.unsubscribe).not.toHaveBeenCalled();
    expect(harness.requests("/api/push-subscription")).toHaveLength(1);
    expect(screen.getByTestId("push-status")).not.toHaveAttribute("data-state", "on");
    fireEvent.click(retry);
    await waitFor(() => expect(current.unsubscribe).toHaveBeenCalledOnce());
    expect(browser.subscribe).toHaveBeenCalledOnce();
  });

  it("연타는 권한 요청 1회이며 시트를 닫은 후의 실패는 새 화면에 토스트·이동을 만들지 않는다", async () => {
    let resolvePermission!: (value: NotificationPermission) => void;
    browser.requestPermission.mockReturnValue(new Promise<NotificationPermission>((resolve) => { resolvePermission = resolve; }));
    const harness = renderSettings({ write: () => errorResponse(401, "UNAUTHENTICATED") });
    await waitFor(() => expect(screen.getByTestId("push-enable")).toBeEnabled());
    fireEvent.click(screen.getByTestId("push-enable"));
    fireEvent.click(screen.getByTestId("push-enable"));
    expect(browser.requestPermission).toHaveBeenCalledOnce();
    harness.view.unmount();
    await act(async () => resolvePermission("granted"));
    expect(router.replace).not.toHaveBeenCalled();
  });

  it("설정 조회 실패 후 다시 시도하며 기존 구독 해제는 조회 실패 중에도 가능하다", async () => {
    const current = subscription();
    browser.getSubscription.mockResolvedValue(current);
    let configAttempts = 0;
    const harness = renderParent(<SettingsSheet open childName={null} onClose={vi.fn()} />, { handler: (path) => {
      if (path.endsWith("/vapid-public-key")) return ++configAttempts === 1
        ? errorResponse(500, "INTERNAL_ERROR") : jsonResponse(200, { enabled: true, publicKey: "runtime-public-key" });
      return new Response(null, { status: 204 });
    } });
    fireEvent.click(await screen.findByTestId("push-retry-config"));
    expect(screen.getByTestId("push-disable")).toBeEnabled();
    await waitFor(() => expect(screen.getByTestId("push-enable")).toBeEnabled());
    expect(harness.requests(/vapid-public-key/)).toHaveLength(2);
    expect(browser.requestPermission).not.toHaveBeenCalled();
  });

  it("PUT 응답 불명은 성공으로 표시하지 않고 동일 구독으로 재등록을 시도한다", async () => {
    const current = subscription();
    let writes = 0;
    const harness = renderSettings({ subscription: current, write: () => {
      if (++writes === 1) throw new TypeError("network");
      return new Response(null, { status: 204 });
    } });
    await waitFor(() => expect(screen.getByTestId("push-enable")).toBeEnabled());
    fireEvent.click(screen.getByTestId("push-enable"));
    await waitFor(() => expect(screen.getByTestId("push-status")).toHaveTextContent("알림을 켜지 못했어요"));
    expect(screen.getByTestId("push-status")).not.toHaveAttribute("data-state", "on");
    fireEvent.click(screen.getByTestId("push-enable"));
    await waitFor(() => expect(screen.getByTestId("push-status")).toHaveAttribute("data-state", "on"));
    expect(harness.requests("/api/push-subscription").map((request) => (request.body as { endpoint: string }).endpoint)).toEqual([endpoint, endpoint]);
    expect(current.unsubscribe).not.toHaveBeenCalled();
  });

  it("설정 재조회 중 끈 구독을 늦은 조회 결과로 되살리지 않고 새로 구독한다", async () => {
    const current = subscription();
    const replacement = { ...subscription(), endpoint: endpoint + "/replacement" };
    browser.getSubscription.mockResolvedValue(current);
    browser.subscribe.mockResolvedValue(replacement);
    let resolveConfig!: (response: Response) => void;
    const pendingConfig = new Promise<Response>((resolve) => { resolveConfig = resolve; });
    let configAttempts = 0;
    const harness = renderParent(<SettingsSheet open childName={null} onClose={vi.fn()} />, { handler: (path) => {
      if (path.endsWith("/vapid-public-key")) return ++configAttempts === 1
        ? errorResponse(500, "INTERNAL_ERROR") : pendingConfig;
      return new Response(null, { status: 204 });
    } });

    fireEvent.click(await screen.findByTestId("push-retry-config"));
    await waitFor(() => expect(browser.getSubscription).toHaveBeenCalledTimes(2));
    fireEvent.click(screen.getByTestId("push-disable"));
    await waitFor(() => expect(screen.queryByTestId("push-disable")).toBeNull());
    expect(current.unsubscribe).toHaveBeenCalledOnce();

    await act(async () => resolveConfig(jsonResponse(200, { enabled: true, publicKey: "runtime-public-key" })));
    expect(screen.getByTestId("push-status")).toHaveAttribute("data-state", "off");
    expect(screen.queryByTestId("push-disable")).toBeNull();
    await waitFor(() => expect(screen.getByTestId("push-enable")).toBeEnabled());
    fireEvent.click(screen.getByTestId("push-enable"));
    await waitFor(() => expect(screen.getByTestId("push-status")).toHaveAttribute("data-state", "on"));
    expect(browser.subscribe).toHaveBeenCalledOnce();
    expect(harness.requests("/api/push-subscription").map((request) => [request.method, (request.body as { endpoint: string }).endpoint]))
      .toEqual([["DELETE", endpoint], ["PUT", replacement.endpoint]]);
  });

  it("서버 해제 후 브라우저 해제 실패는 성공한 서버 등록을 되살리지 않고 정리 재시도한다", async () => {
    const current = subscription();
    current.unsubscribe.mockRejectedValueOnce(new Error("browser")).mockResolvedValue(true);
    const harness = renderSettings({ subscription: current });
    fireEvent.click(await screen.findByTestId("push-disable"));
    await waitFor(() => expect(screen.getByTestId("push-status")).toHaveTextContent("서버 알림은 꺼졌어요"));
    expect(screen.getByTestId("push-status")).not.toHaveAttribute("data-state", "on");
    fireEvent.click(screen.getByTestId("push-disable"));
    await waitFor(() => expect(screen.queryByTestId("push-disable")).toBeNull());
    expect(harness.requests("/api/push-subscription").map((request) => request.method)).toEqual(["DELETE", "DELETE"]);
  });

  it("이미 권한을 거부한 브라우저는 권한 팝업 대신 설정 안내를 보인다", async () => {
    browser.permission.mockReturnValue("denied");
    renderSettings();
    await waitFor(() => expect(screen.getByTestId("push-status")).toHaveTextContent("브라우저 설정에서 알림을 허용해 주세요"));
    expect(screen.getByTestId("push-enable")).toBeDisabled();
    expect(browser.requestPermission).not.toHaveBeenCalled();
  });

  it("키보드 켜기 후 같은 주 조작의 초점을 유지한다", async () => {
    renderSettings();
    await waitFor(() => expect(screen.getByTestId("push-enable")).toBeEnabled());
    const primary = screen.getByTestId("push-enable");
    primary.focus();
    fireEvent.click(primary);
    await waitFor(() => expect(screen.getByTestId("push-status")).toHaveAttribute("data-state", "on"));
    expect(screen.getByTestId("push-disable")).toBe(primary);
    expect(primary).toHaveFocus();
  });

  it.each([true, false])("기존 구독 끄기 후 초점을 남은 조작 또는 제목으로 복원한다(서버 활성 %s)", async (enabled) => {
    renderSettings({ subscription: subscription(), config: { enabled, publicKey: enabled ? "runtime-public-key" : null } });
    const disable = await screen.findByTestId("push-disable");
    disable.focus();
    fireEvent.click(disable);
    await waitFor(() => expect(screen.queryByTestId("push-disable")).toBeNull());
    expect(enabled ? screen.getByTestId("push-enable") : screen.getByRole("heading", { name: "이 기기 알림" })).toHaveFocus();
  });
});
