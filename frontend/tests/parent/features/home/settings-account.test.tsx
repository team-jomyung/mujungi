import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SettingsSheet } from "@/parent/features/home/SettingsSheet";
import { ACCOUNT_TEMP_COPY } from "@/shared/copy/account";
import { errorResponse, jsonResponse } from "../../../support/fixtures";
import { renderParent } from "../../../support/parentHarness";
import { resetRouter, router } from "../../../support/nextNavigation";

vi.mock("next/navigation", async () => (await import("../../../support/nextNavigation")).nextNavigationMock);
vi.mock("@/parent/common/push/browserPush", () => ({ browserPush: {
  supported: () => true, permission: () => "default", getSubscription: async () => null,
} }));

beforeEach(resetRouter);

describe("U7 알림 줄을 더한 SET0의 계정 흐름", () => {
  it.each(["logout", "delete"] as const)("푸시가 꺼진 서버에서도 %s 실패 뒤 재시도할 수 있다", async (action) => {
    let calls = 0;
    const path = action === "logout" ? "/api/auth/logout" : "/api/me";
    const harness = renderParent(<SettingsSheet open childName={null} onClose={vi.fn()} />, {
      handler: (requested) => {
        if (requested.endsWith("/vapid-public-key")) return jsonResponse(200, { enabled: false, publicKey: null });
        if (requested !== path || ++calls === 1) return errorResponse(500, "INTERNAL_ERROR");
        return action === "logout" ? new Response(null, { status: 204 }) : jsonResponse(202, { deletionRequestId: "test-request" });
      },
    });
    await screen.findByText("알림을 준비하고 있어요");
    fireEvent.click(screen.getByTestId("settings-" + action));
    if (action === "delete") fireEvent.click(screen.getByTestId("p7-yes"));
    await screen.findByText(action === "logout" ? ACCOUNT_TEMP_COPY.logoutFailed : ACCOUNT_TEMP_COPY.deleteFailed);
    expect(router.replace).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId(action === "logout" ? "settings-logout" : "p7-yes"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
    expect(harness.requests(path)).toHaveLength(2);
    expect(harness.requests("/api/push-subscription")).toHaveLength(0);
  });

  it.each(["logout", "delete"] as const)("알림 설정이 열려 있어도 %s 401은 로그인으로 간다", async (action) => {
    renderParent(<SettingsSheet open childName={null} onClose={vi.fn()} />, {
      handler: (path) => path.endsWith("/vapid-public-key")
        ? jsonResponse(200, { enabled: false, publicKey: null }) : errorResponse(401, "UNAUTHENTICATED"),
    });
    await screen.findByText("알림을 준비하고 있어요");
    fireEvent.click(screen.getByTestId("settings-" + action));
    if (action === "delete") fireEvent.click(screen.getByTestId("p7-yes"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
  });

  it("계정 삭제를 취소하면 알림 설정 줄로 돌아온다", async () => {
    renderParent(<SettingsSheet open childName={null} onClose={vi.fn()} />, {
      handler: () => jsonResponse(200, { enabled: false, publicKey: null }),
    });
    await screen.findByText("알림을 준비하고 있어요");
    fireEvent.click(screen.getByTestId("settings-delete"));
    fireEvent.click(screen.getByTestId("p7-no"));
    await screen.findByTestId("push-status");
    expect(router.replace).not.toHaveBeenCalled();
  });
});
