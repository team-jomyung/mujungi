import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { LoginScreen } from "@/parent/features/login/LoginScreen";
import { LoginCallbackScreen } from "@/parent/features/login/LoginCallbackScreen";
import { errorResponse, jsonResponse } from "../../../support/fixtures";
import { resetRouter, router } from "../../../support/nextNavigation";
import { renderParent } from "../../../support/parentHarness";
import { meBody } from "../../../support/accountFixtures";

vi.mock("next/navigation", async () => (await import("../../../support/nextNavigation")).nextNavigationMock);

// 01 보호자 로그인 · 콜백 교환 시험 (U2 · US1.1 · AC1.1.1 ~ AC1.1.4 · AC1.1.7 · AC1.1.10 · AC1.6.4 · mockups S-01 · 9-1 · 9-7)

beforeEach(() => {
  resetRouter();
});

describe("01 화면", () => {
  it("로고 · 부제 · 「구글로 시작」만 있고 구글 외 로그인 · 아이디 · 비밀번호 칸이 없다(AC1.1.7)", () => {
    renderParent(<LoginScreen navigate={vi.fn()} />, { handler: () => jsonResponse(200, {}) });
    expect(screen.getByRole("heading", { name: "무중" })).toBeInTheDocument();
    expect(screen.getByText("아이의 이야기를 함께 살펴봐요")).toBeInTheDocument();
    expect(screen.getAllByRole("button")).toHaveLength(1);
    expect(screen.getByTestId("login-google")).toHaveTextContent("구글로 시작");
    expect(document.querySelectorAll("input")).toHaveLength(0);
    expect(screen.queryByTestId("login-deleting")).toBeNull();
  });

  it("누르면 인증 중으로 잠기고 서버의 구글 시작 경로로 한 번만 이동한다(AC1.1.10)", () => {
    const navigate = vi.fn();
    renderParent(<LoginScreen navigate={navigate} />, { handler: () => jsonResponse(200, {}) });
    fireEvent.click(screen.getByTestId("login-google"));
    fireEvent.click(screen.getByTestId("login-google"));
    expect(navigate).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledWith("/parent/login/google");
    expect(screen.getByTestId("login-google")).toBeDisabled();
    expect(screen.getByTestId("login-google")).toHaveTextContent("로그인하고 있어요");
  });

  it("실패로 돌아오면 토스트이고 다시 시도할 수 있다(AC1.1.4 · 9-1)", () => {
    renderParent(<LoginScreen failed navigate={vi.fn()} />, { handler: () => jsonResponse(200, {}) });
    expect(screen.getByTestId("toast")).toHaveTextContent("로그인하지 못했어요. 다시 시도해 주세요.");
    expect(screen.getByTestId("login-google")).toBeEnabled();
  });

  it("삭제 처리 중 계정이면 안내 카드만 보인다(AC1.6.4 · 9-7)", () => {
    renderParent(<LoginScreen deleting navigate={vi.fn()} />, { handler: () => jsonResponse(200, {}) });
    expect(screen.getByTestId("login-deleting")).toHaveTextContent("계정을 삭제하고 있어요. 끝나면 새로 시작할 수 있어요.");
  });
});

describe("01 콜백 교환", () => {
  it.each([
    [{ consentStatus: "NOT_GIVEN", childRegistered: false }, "/parent/consent"],
    [{ consentStatus: "GIVEN", childRegistered: false }, "/parent/child"],
    [{ consentStatus: "GIVEN", childRegistered: true }, "/parent"],
    [{ deletionStatus: "IN_PROGRESS" }, "/parent/login?deleting=1"],
  ])("Me %j이면 %s로 간다", async (options, route) => {
    const harness = renderParent(<LoginCallbackScreen code="code-1" redirectUri="https://mujoong.invalid/parent/login/callback" />, {
      handler: () => jsonResponse(200, meBody(options)),
    });
    expect(screen.getByTestId("login-callback-status")).toHaveTextContent("로그인하고 있어요");
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith(route));
    expect(harness.requests("/api/auth/google")).toHaveLength(1);
    expect(harness.requests("/api/auth/google")[0].body).toEqual({
      code: "code-1",
      redirectUri: "https://mujoong.invalid/parent/login/callback",
    });
  });

  it("교환이 실패하면 01 실패로 돌아간다(AC1.1.4)", async () => {
    renderParent(<LoginCallbackScreen code="bad" redirectUri="r" />, { handler: () => errorResponse(401, "GOOGLE_AUTH_FAILED") });
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login?failed=1"));
  });
});
