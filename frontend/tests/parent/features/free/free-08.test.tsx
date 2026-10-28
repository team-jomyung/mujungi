import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { FreeTalkScreen } from "@/parent/features/free/FreeTalkScreen";
import { SESSION_ID, deferred, errorResponse, jsonResponse } from "../../../support/fixtures";
import { resetRouter, router } from "../../../support/nextNavigation";
import type { ParentHarness, RequestHandler } from "../../../support/parentHarness";
import { renderParent } from "../../../support/readyActivityHarness";

vi.mock("next/navigation", async () => (await import("../../../support/nextNavigation")).nextNavigationMock);

// 08 자유대화 준비 시험 (U1 3.5 Step 42 · BR6.1 · BR6.2 · BR6.4 · AC3.1.1 · AC3.1.3 · AC3.1.4 · AC3.1.6 · mockups 9-12 · J118 · J120 · 표 2.9-C)
// 네 칸 모두 선택, 서술 칸 200자 상한 · 카운터, 칩 단일 선택(radiogroup), 전송 중 단추 꺼짐, 202 → 12, 거절 사유 하나,
// idempotencyKey는 전달 불명 · CSRF 재시도일 때만 같은 키(같은 본문), 그 밖에는 새 키.

const ACCEPTED = () => jsonResponse(202, { sessionId: SESSION_ID, status: "REQUESTED" });

function setup(handler: RequestHandler, returnedFailed = false): ParentHarness {
  return renderParent(<FreeTalkScreen returnedFailed={returnedFailed} />, { handler });
}

function startBodies(harness: ParentHarness): Array<{ idempotencyKey: string; context?: Record<string, unknown> }> {
  return harness.requests("/api/activities/free").map((request) => request.body as { idempotencyKey: string; context?: Record<string, unknown> });
}

it("보호자 입력의 대화 전 참고·분석 활용과 출처 구분을 안내한다", () => {
  setup(() => ACCEPTED());
  expect(screen.getByTestId("free-context-usage")).toHaveTextContent("중립적인 대화 주제");
  expect(screen.getByTestId("free-context-usage")).toHaveTextContent("보호자의 설명과 아이의 말은 구분");
});

beforeEach(() => {
  resetRouter();
});

describe("08 — 입력과 시작(BR6.4 · AC3.1.1)", () => {
  it("네 칸을 모두 비워도 시작할 수 있고 본문은 idempotencyKey뿐이며 202면 12로 간다", async () => {
    const harness = setup(ACCEPTED);
    fireEvent.click(screen.getByTestId("free-start"));
    await waitFor(() => expect(router.push).toHaveBeenCalledWith(`/parent/sessions/${SESSION_ID}`));
    const [body] = startBodies(harness);
    expect(Object.keys(body)).toEqual(["idempotencyKey"]);
    expect(body.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(harness.requests("/api/activities/free")[0].headers["x-csrf-token"]).toBe(harness.csrfToken);
  });

  it("채운 칸만 context로 보낸다(서술 2칸 · 칩 2묶음)", async () => {
    const harness = setup(ACCEPTED);
    const user = userEvent.setup();
    await user.type(screen.getByTestId("free-known-content"), "블록 놀이");
    await user.click(screen.getByTestId("free-situation-friend"));
    await user.click(screen.getByTestId("free-source-heard-from-child"));
    await user.type(screen.getByTestId("free-question"), "어떻게 받아들이는지");
    await user.click(screen.getByTestId("free-start"));
    await waitFor(() => expect(router.push).toHaveBeenCalled());
    expect(startBodies(harness)[0].context).toEqual({
      knownContent: "블록 놀이",
      situationType: "FRIEND",
      sourceType: "HEARD_FROM_CHILD",
      question: "어떻게 받아들이는지",
    });
  });

  it("칩은 단일 선택이고 고른 칩을 다시 누르면 선택이 지워진다(radiogroup)", async () => {
    setup(ACCEPTED);
    const user = userEvent.setup();
    const group = screen.getByTestId("free-situation");
    expect(group).toHaveAttribute("role", "radiogroup");
    expect(group).toHaveAccessibleName("관련 상황");
    await user.click(screen.getByTestId("free-situation-school"));
    await user.click(screen.getByTestId("free-situation-family"));
    expect(screen.getByTestId("free-situation-family")).toBeChecked();
    expect(screen.getByTestId("free-situation-school")).not.toBeChecked();
    await user.click(screen.getByTestId("free-situation-family"));
    expect(screen.getByTestId("free-situation-family")).not.toBeChecked();
    expect(screen.getByTestId("free-situation-academy").closest("label")).toHaveTextContent("학원");
  });

  it("서술 칸은 200자에서 막히고 카운터가 '200/200'을 조용히 알린다(AC3.1.3)", () => {
    setup(ACCEPTED);
    const field = screen.getByTestId("free-known-content") as HTMLTextAreaElement;
    expect(screen.getByTestId("free-known-content-counter")).toHaveTextContent("0/200");
    fireEvent.change(field, { target: { value: "가".repeat(250) } });
    expect(field.value).toHaveLength(200);
    const counter = screen.getByTestId("free-known-content-counter");
    expect(counter).toHaveTextContent("200/200");
    expect(counter).toHaveAttribute("aria-live", "polite");
    expect(counter).toHaveClass("mj-field__counter--limit");
    expect(field).toHaveAttribute("maxlength", "200");
    expect(screen.getByLabelText("알고 있는 내용")).toBe(field);
  });

  it("전송 중에는 「자유대화 시작」과 입력이 꺼진다(BR6.4)", async () => {
    const pending = deferred<Response>();
    setup(() => pending.promise);
    fireEvent.click(screen.getByTestId("free-start"));
    await waitFor(() => expect(screen.getByTestId("free-start")).toBeDisabled());
    expect(screen.getByTestId("free-question")).toBeDisabled();
    fireEvent.click(screen.getByTestId("free-start"));
    pending.resolve(ACCEPTED());
    await waitFor(() => expect(router.push).toHaveBeenCalledTimes(1));
  });
});

describe("08 — 거절 사유(mockups 9-12 · AC3.1.6)", () => {
  it.each([
    [409, "SESSION_IN_PROGRESS", "진행 중인 활동이 있어요. 끝난 뒤 시작할 수 있어요."],
    [423, "DEVICE_NOT_STABLE", "기기가 안정될 때까지 시작할 수 없어요"],
    [403, "CONSENT_MISSING", "동의를 먼저 마쳐 주세요"],
    [503, "REALTIME_UNAVAILABLE", "지금은 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요."],
  ])("%i %s → 사유 카드 '%s'를 보이고 08에 머문다", async (status, code, message) => {
    setup(() => errorResponse(status, code));
    fireEvent.click(screen.getByTestId("free-start"));
    const reason = await screen.findByTestId("free-reason");
    expect(reason).toHaveTextContent(message);
    expect(reason).toHaveAttribute("role", "alert");
    expect(router.push).not.toHaveBeenCalled();
    expect(screen.getAllByTestId("free-reason")).toHaveLength(1);
  });

  it("화면 사유가 없는 거절(400)은 code 문장 토스트로 알린다", async () => {
    setup(() => errorResponse(400, "VALIDATION_FAILED"));
    fireEvent.click(screen.getByTestId("free-start"));
    expect(await screen.findByTestId("toast")).toHaveTextContent("요청 형식이 올바르지 않아요.");
    expect(screen.queryByTestId("free-reason")).toBeNull();
  });

  it("12에서 FAILED로 돌아오면 사유 카드 한 종류로 시작하고 다시 시작하면 지운다(J120)", async () => {
    setup(ACCEPTED, true);
    expect(screen.getByTestId("free-reason")).toHaveTextContent("기기가 응답하지 않아 시작하지 못했어요. 기기를 확인하고 다시 시도해 주세요.");
    fireEvent.click(screen.getByTestId("free-start"));
    await waitFor(() => expect(screen.queryByTestId("free-reason")).toBeNull());
  });

  it("401이면 안내 토스트 뒤 01로 보낸다(U2 · AC1.1.9)", async () => {
    setup(() => errorResponse(401, "UNAUTHENTICATED"));
    fireEvent.click(screen.getByTestId("free-start"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
    expect(screen.getByTestId("toast")).toHaveTextContent("로그인이 필요해요.");
  });
});

describe("08 — idempotencyKey 재사용(BR6.2 · 표 2.9-C · AC3.1.8)", () => {
  it("응답 없음이면 같은 키로 「다시 시도」하고, 거절 뒤에는 새 키다", async () => {
    let calls = 0;
    const harness = setup(() => {
      calls += 1;
      if (calls === 1) {
        throw new TypeError("offline");
      }
      return calls === 2 ? errorResponse(423, "DEVICE_NOT_STABLE") : ACCEPTED();
    });
    fireEvent.click(screen.getByTestId("free-start"));
    fireEvent.click(await screen.findByTestId("toast-action"));
    await screen.findByTestId("free-reason");
    fireEvent.click(screen.getByTestId("free-start"));
    await waitFor(() => expect(router.push).toHaveBeenCalled());
    const keys = startBodies(harness).map((body) => body.idempotencyKey);
    expect(keys[0]).toBe(keys[1]);
    expect(keys[2]).not.toBe(keys[1]);
  });

  it("응답 없음 뒤 입력을 고치면 새 키다(같은 본문일 때만 같은 키)", async () => {
    let calls = 0;
    const harness = setup(() => {
      calls += 1;
      if (calls === 1) {
        throw new TypeError("offline");
      }
      return ACCEPTED();
    });
    fireEvent.click(screen.getByTestId("free-start"));
    await screen.findByTestId("toast");
    fireEvent.click(screen.getByTestId("free-situation-other"));
    fireEvent.click(screen.getByTestId("free-start"));
    await waitFor(() => expect(router.push).toHaveBeenCalled());
    const keys = startBodies(harness).map((body) => body.idempotencyKey);
    expect(keys).toHaveLength(2);
    expect(keys[1]).not.toBe(keys[0]);
  });

  it("토스트가 떠 있는 동안 입력을 고치고 「다시 시도」를 누르면 고친 본문을 새 키로 보낸다(J232 · J362)", async () => {
    let calls = 0;
    const harness = setup(() => {
      calls += 1;
      if (calls === 1) {
        throw new TypeError("offline");
      }
      return ACCEPTED();
    });
    fireEvent.click(screen.getByTestId("free-start"));
    await screen.findByTestId("toast");
    fireEvent.click(screen.getByTestId("free-situation-other"));
    fireEvent.click(screen.getByTestId("toast-action"));
    await waitFor(() => expect(router.push).toHaveBeenCalledWith(`/parent/sessions/${SESSION_ID}`));
    const bodies = startBodies(harness);
    expect(bodies).toHaveLength(2);
    expect(bodies[0].context).toBeUndefined();
    expect(bodies[1].context).toEqual({ situationType: "OTHER" });
    expect(bodies[1].idempotencyKey).not.toBe(bodies[0].idempotencyKey);
  });

  it("「자유대화 시작」 요청이 진행 중이면 토스트의 「다시 시도」는 두 번째 요청을 보내지 않는다(BR6.4 · J362)", async () => {
    const pending = deferred<Response>();
    let calls = 0;
    const harness = setup(() => {
      calls += 1;
      if (calls === 1) {
        throw new TypeError("offline");
      }
      return pending.promise;
    });
    fireEvent.click(screen.getByTestId("free-start"));
    await screen.findByTestId("toast");
    fireEvent.click(screen.getByTestId("free-start"));
    await waitFor(() => expect(screen.getByTestId("free-start")).toBeDisabled());
    fireEvent.click(screen.getByTestId("toast-action"));
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(startBodies(harness)).toHaveLength(2);
    pending.resolve(ACCEPTED());
    await waitFor(() => expect(router.push).toHaveBeenCalledTimes(1));
    const keys = startBodies(harness).map((body) => body.idempotencyKey);
    expect(keys).toHaveLength(2);
    // 응답 없음 뒤 같은 본문이라 두 번째 시도는 같은 키다(표 2.9-C).
    expect(keys[1]).toBe(keys[0]);
  });

  it("CSRF 403이 두 번이면 멈추되 다음 시도는 같은 키다", async () => {
    let calls = 0;
    const harness = setup(() => {
      calls += 1;
      return calls <= 2 ? errorResponse(403, "CSRF_TOKEN_INVALID") : ACCEPTED();
    });
    fireEvent.click(screen.getByTestId("free-start"));
    expect(await screen.findByTestId("toast")).toHaveTextContent("보안 확인이 만료됐어요. 다시 시도해 주세요.");
    fireEvent.click(screen.getByTestId("toast-action"));
    await waitFor(() => expect(router.push).toHaveBeenCalled());
    const keys = new Set(startBodies(harness).map((body) => body.idempotencyKey));
    expect(startBodies(harness)).toHaveLength(3);
    expect(keys.size).toBe(1);
  });

  it("성공한 뒤 다시 시작하면 새 키다", async () => {
    const harness = setup(ACCEPTED);
    fireEvent.click(screen.getByTestId("free-start"));
    await waitFor(() => expect(router.push).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByTestId("free-start"));
    await waitFor(() => expect(router.push).toHaveBeenCalledTimes(2));
    const keys = startBodies(harness).map((body) => body.idempotencyKey);
    expect(keys[0]).not.toBe(keys[1]);
  });
});

describe("08 — 탭", () => {
  it("홈 · 기록 탭은 이동하고 지금 탭(자유대화)은 머문다", () => {
    setup(ACCEPTED);
    expect(screen.getByTestId("tab-free")).toHaveAttribute("aria-selected", "true");
    fireEvent.click(screen.getByTestId("tab-free"));
    fireEvent.click(screen.getByTestId("tab-home"));
    fireEvent.click(screen.getByTestId("tab-records"));
    expect(router.push.mock.calls).toEqual([["/parent"], ["/parent/records"]]);
  });
});
