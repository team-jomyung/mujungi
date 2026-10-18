import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { DeviceRegisterScreen } from "@/parent/features/device/DeviceRegisterScreen";
import { deferred, errorResponse, jsonResponse } from "../support/fixtures";
import { resetRouter, router } from "../support/nextNavigation";
import { renderParent, type RequestHandler } from "../support/parentHarness";

vi.mock("next/navigation", async () => (await import("../support/nextNavigation")).nextNavigationMock);

// 05a 기기 등록 화면 시험 (U1 3.5 Step 42 · BR2.7 · BR2.8 · BR2.9 · AC2.2.1 ~ AC2.2.4 · AC2.2.8 · mockups 9-9 · 9-10 · J57 · J118)
// 8칸이 차야 「등록」, 전송 중 '등록하고 있어요', 404 · 409 · 429는 빨간 카드 + 입력 유지, 네트워크 · 5xx는 토스트 + 「다시 시도」,
// 성공 · 「취소」 · 「←」는 05(U1 스텁 → 04). 해제 · 페어링 · 검색 단추는 없다.

const CREATED = { deviceId: "00000000-0000-4000-8000-000000000030", codePrefix: "7F3K", registeredAt: "2026-10-15T01:00:00Z" };

async function typeCode(code = "7f3k92qd") {
  const user = userEvent.setup();
  await user.click(await screen.findByTestId("code-cell-0"));
  await user.paste(code);
  return user;
}

function codeValue(): string {
  return Array.from({ length: 8 }, (_, index) => (screen.getByTestId(`code-cell-${index}`) as HTMLInputElement).value).join("");
}

function setup(handler: RequestHandler) {
  return renderParent(<DeviceRegisterScreen />, { handler });
}

beforeEach(() => {
  resetRouter();
});

describe("05a — 정상 등록", () => {
  it("8칸을 채우면 「등록」이 켜지고, 전송 중에는 꺼진 채 '등록하고 있어요'이며, 201이면 05로 간다(AC2.2.1 · AC2.2.2)", async () => {
    const pending = deferred<Response>();
    const harness = setup(() => pending.promise);
    const submit = await screen.findByTestId("register-submit");
    expect(submit).toBeDisabled();
    expect(submit).toHaveTextContent("등록");
    const user = await typeCode();
    expect(submit).toBeEnabled();
    await user.click(submit);
    expect(submit).toBeDisabled();
    expect(submit).toHaveTextContent("등록하고 있어요");
    expect(screen.getByTestId("code-cell-0")).toBeDisabled();
    pending.resolve(jsonResponse(201, CREATED));
    await waitFor(() => expect(router.push).toHaveBeenCalledWith("/parent/device"));
    const [request] = harness.requests("/api/device/register");
    expect(request.body).toEqual({ code: "7F3K92QD" });
    expect(request.headers["x-csrf-token"]).toBe(harness.csrfToken);
  });

  it("등록이 끝나면 홈 조회를 새로 하게 무효화한다(04 · 05 갱신, BR2.9)", async () => {
    const harness = setup(() => jsonResponse(201, CREATED));
    harness.queryClient.setQueryData(["home"], { stale: true });
    const user = await typeCode();
    await user.click(screen.getByTestId("register-submit"));
    await waitFor(() => expect(router.push).toHaveBeenCalledWith("/parent/device"));
    expect(harness.queryClient.getQueryState(["home"])?.isInvalidated).toBe(true);
  });
});

describe("05a — 거절은 빨간 카드, 입력 유지(BR2.8 · AC2.2.3 · AC2.2.4)", () => {
  it.each([
    [404, "CODE_NOT_FOUND", "없는 코드예요"],
    [400, "VALIDATION_FAILED", "없는 코드예요"],
    [409, "CODE_ALREADY_REGISTERED", "이미 등록된 코드예요"],
    [409, "DEVICE_ALREADY_ACTIVE", "이미 연결된 기기가 있어요"],
    [429, "TOO_MANY_ATTEMPTS", "잠시 뒤 다시 시도해 주세요"],
  ])("%i %s → '%s'", async (status, code, message) => {
    setup(() => errorResponse(status, code));
    const user = await typeCode();
    await user.click(screen.getByTestId("register-submit"));
    const card = await screen.findByTestId("register-error");
    expect(card).toHaveTextContent(message);
    expect(card).toHaveAttribute("role", "alert");
    expect(codeValue()).toBe("7F3K92QD");
    expect(screen.getByTestId("code-cell-0")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByTestId("register-submit")).toBeEnabled();
    expect(router.push).not.toHaveBeenCalled();
  });

  it("카드가 뜬 뒤 칸을 고치면 카드가 사라지고 다시 등록할 수 있다", async () => {
    let calls = 0;
    setup(() => {
      calls += 1;
      return calls === 1 ? errorResponse(404, "CODE_NOT_FOUND") : jsonResponse(201, CREATED);
    });
    const user = await typeCode();
    await user.click(screen.getByTestId("register-submit"));
    await screen.findByTestId("register-error");
    await user.click(screen.getByTestId("code-cell-7"));
    await user.keyboard("{Backspace}X");
    expect(screen.queryByTestId("register-error")).toBeNull();
    await user.click(screen.getByTestId("register-submit"));
    await waitFor(() => expect(router.push).toHaveBeenCalledWith("/parent/device"));
  });
});

describe("05a — 네트워크 · 5xx · 401", () => {
  it("네트워크 오류는 토스트 + 「다시 시도」이고 다시 시도하면 같은 코드로 보낸다(AC2.2.8)", async () => {
    let calls = 0;
    const harness = setup(() => {
      calls += 1;
      if (calls === 1) {
        throw new TypeError("offline");
      }
      return jsonResponse(201, CREATED);
    });
    const user = await typeCode();
    await user.click(screen.getByTestId("register-submit"));
    const toast = await screen.findByTestId("toast");
    expect(toast).toHaveTextContent("잠시 문제가 생겼어요. 다시 시도해 주세요.");
    expect(within(screen.getByTestId("toast-region-assertive")).getByTestId("toast")).toBe(toast);
    expect(codeValue()).toBe("7F3K92QD");
    await user.click(screen.getByTestId("toast-action"));
    await waitFor(() => expect(router.push).toHaveBeenCalledWith("/parent/device"));
    expect(harness.requests("/api/device/register").map((request) => request.body)).toEqual([{ code: "7F3K92QD" }, { code: "7F3K92QD" }]);
  });

  it("토스트가 떠 있는 동안 칸을 고치고 「다시 시도」를 누르면 화면에 보이는 고친 코드로 보낸다(BR2.8 · J362)", async () => {
    let calls = 0;
    const harness = setup(() => {
      calls += 1;
      return calls === 1 ? errorResponse(503, "SERVICE_NOT_READY") : jsonResponse(201, CREATED);
    });
    const user = await typeCode("7f3k92qx");
    await user.click(screen.getByTestId("register-submit"));
    await screen.findByTestId("toast");
    await user.click(screen.getByTestId("code-cell-7"));
    await user.keyboard("{Backspace}D");
    expect(codeValue()).toBe("7F3K92QD");
    await user.click(screen.getByTestId("toast-action"));
    await waitFor(() => expect(router.push).toHaveBeenCalledWith("/parent/device"));
    expect(harness.requests("/api/device/register").map((request) => request.body)).toEqual([{ code: "7F3K92QX" }, { code: "7F3K92QD" }]);
  });

  it("「등록」 요청이 진행 중이면 토스트의 「다시 시도」는 두 번째 요청을 보내지 않는다(BR2.8 · J362)", async () => {
    const pending = deferred<Response>();
    let calls = 0;
    const harness = setup(() => {
      calls += 1;
      if (calls === 1) {
        throw new TypeError("offline");
      }
      return pending.promise;
    });
    const user = await typeCode();
    await user.click(screen.getByTestId("register-submit"));
    await screen.findByTestId("toast");
    await user.click(screen.getByTestId("register-submit"));
    expect(screen.getByTestId("register-submit")).toHaveTextContent("등록하고 있어요");
    await user.click(screen.getByTestId("toast-action"));
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(harness.requests("/api/device/register")).toHaveLength(2);
    pending.resolve(jsonResponse(201, CREATED));
    await waitFor(() => expect(router.push).toHaveBeenCalledWith("/parent/device"));
    expect(router.push).toHaveBeenCalledTimes(1);
    expect(harness.requests("/api/device/register")).toHaveLength(2);
  });

  it("5xx는 code 문장 토스트로 알리고 빨간 카드를 띄우지 않는다", async () => {
    setup(() => errorResponse(503, "SERVICE_NOT_READY"));
    const user = await typeCode();
    await user.click(screen.getByTestId("register-submit"));
    expect(await screen.findByTestId("toast")).toHaveTextContent("잠시 뒤 다시 시도해 주세요.");
    expect(screen.queryByTestId("register-error")).toBeNull();
  });

  it("401이면 안내 토스트 뒤 01로 보낸다(U2 · AC1.1.9)", async () => {
    setup(() => errorResponse(401, "UNAUTHENTICATED"));
    const user = await typeCode();
    await user.click(screen.getByTestId("register-submit"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
    expect(screen.getByTestId("toast")).toHaveTextContent("로그인이 필요해요.");
  });
});

describe("05a — 미완성 코드(BR2.8)", () => {
  it("8칸 미만이면 Enter로도 보내지 않는다", async () => {
    const harness = setup(() => jsonResponse(201, CREATED));
    const user = userEvent.setup();
    await user.click(await screen.findByTestId("code-cell-0"));
    await user.keyboard("7F3K{Enter}");
    expect(harness.requests("/api/device/register")).toHaveLength(0);
    expect(screen.getByTestId("register-submit")).toBeDisabled();
  });
});

describe("05a — 이동과 단추 구성(BR2.8 · J57)", () => {
  it("「취소」 · 「←」는 05로 간다", async () => {
    setup(() => jsonResponse(201, CREATED));
    const user = userEvent.setup();
    await user.click(await screen.findByTestId("register-cancel"));
    await user.click(screen.getByRole("button", { name: "뒤로" }));
    expect(router.push.mock.calls).toEqual([["/parent/device"], ["/parent/device"]]);
  });

  it("단추는 「←」 · 「취소」 · 「등록」뿐이다(해제 · 페어링 · 검색 없음)", async () => {
    setup(() => jsonResponse(201, CREATED));
    await screen.findByTestId("register-form");
    const buttons = screen.getAllByRole("button").map((button) => button.getAttribute("data-testid"));
    expect(buttons).toEqual(["register-back", "register-cancel", "register-submit"]);
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("기기 등록");
    expect(screen.getByText("아이 기기 화면의 8자리 코드")).toBeInTheDocument();
  });
});
