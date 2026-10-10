import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { DevLogin } from "@/parent/screens/DevLogin";
import { deferred, errorResponse, homeBody, jsonResponse } from "../support/fixtures";
import { resetRouter, router } from "../support/nextNavigation";
import { renderParent } from "../support/parentHarness";

vi.mock("next/navigation", async () => (await import("../support/nextNavigation")).nextNavigationMock);

// 시험 로그인 화면 시험 (U1 3.5 Step 42 · BR8.4 · J119 · SD-14 · AC1.1.7 · AC1.1.9)
// 아이디 · 비밀번호 입력란 없이 시드 보호자 세션만 연다. 성공하면 CSRF를 다시 받고 앞 사용자의 조회를 버린 뒤 04로,
// 서버가 404(꺼짐)면 안내만 한다. 다른 화면의 401은 여기로 온다(라우트 · 화면 시험이 확인).

const GUARDIAN = { guardianId: "00000000-0000-4000-8000-000000000001" };

beforeEach(() => {
  resetRouter();
});

describe("시험 로그인(AC1.1.7)", () => {
  it("누르면 CSRF 없이 POST /api/dev/login → 세션이 바뀌어 CSRF를 다시 받고 조회 캐시를 버린 뒤 04로 간다", async () => {
    const harness = renderParent(<DevLogin />, { handler: () => jsonResponse(200, GUARDIAN) });
    harness.queryClient.setQueryData(["home"], homeBody("STABLE"));
    await waitFor(() => expect(harness.requests("/api/session/csrf")).toHaveLength(1));
    fireEvent.click(screen.getByTestId("dev-login-submit"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent"));
    const login = harness.requests("/api/dev/login");
    expect(login).toHaveLength(1);
    expect(login[0].method).toBe("POST");
    expect(login[0].headers["x-csrf-token"]).toBeUndefined();
    expect(harness.requests("/api/session/csrf")).toHaveLength(2);
    expect(harness.queryClient.getQueryData(["home"])).toBeUndefined();
  });

  it("입력란 없이 단추 하나이고 요청 중에는 꺼져 두 번 보내지 않는다", async () => {
    const pending = deferred<Response>();
    const harness = renderParent(<DevLogin />, { handler: () => pending.promise });
    expect(screen.queryAllByRole("textbox")).toHaveLength(0);
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("시험 로그인");
    fireEvent.click(screen.getByTestId("dev-login-submit"));
    expect(screen.getByTestId("dev-login-submit")).toBeDisabled();
    fireEvent.click(screen.getByTestId("dev-login-submit"));
    pending.resolve(jsonResponse(200, GUARDIAN));
    await waitFor(() => expect(router.replace).toHaveBeenCalledTimes(1));
    expect(harness.requests("/api/dev/login")).toHaveLength(1);
  });
});

describe("시험 로그인 — 거절", () => {
  it("서버가 404(시험 로그인 꺼짐)면 '시험 로그인을 쓸 수 없어요'를 보이고 이동하지 않는다", async () => {
    renderParent(<DevLogin />, { handler: () => errorResponse(404, "NOT_FOUND") });
    fireEvent.click(screen.getByTestId("dev-login-submit"));
    const error = await screen.findByTestId("dev-login-error");
    expect(error).toHaveTextContent("시험 로그인을 쓸 수 없어요");
    expect(error).toHaveAttribute("role", "alert");
    expect(router.replace).not.toHaveBeenCalled();
    expect(screen.getByTestId("dev-login-submit")).toBeEnabled();
  });

  it("그 밖의 오류는 code 문장, 망 오류는 일반 문장이다", async () => {
    let calls = 0;
    renderParent(<DevLogin />, {
      handler: () => {
        calls += 1;
        if (calls === 1) {
          return errorResponse(503, "SERVICE_NOT_READY");
        }
        throw new TypeError("offline");
      },
    });
    fireEvent.click(screen.getByTestId("dev-login-submit"));
    expect(await screen.findByTestId("dev-login-error")).toHaveTextContent("잠시 뒤 다시 시도해 주세요.");
    fireEvent.click(screen.getByTestId("dev-login-submit"));
    await waitFor(() => expect(screen.getByTestId("dev-login-error")).toHaveTextContent("잠시 문제가 생겼어요. 다시 시도해 주세요."));
  });
});
