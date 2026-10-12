import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { normalizeExpression, StopExpressions01, validateExpression } from "@/parent/screens/StopExpressions01";
import { errorResponse, homeBody, jsonResponse } from "../../u1/support/fixtures";
import { resetRouter, router } from "../../u1/support/nextNavigation";
import { renderParent, type RequestHandler } from "../../u1/support/parentHarness";
import { noContent, stopBody } from "../support/accountFixtures";

vi.mock("next/navigation", async () => (await import("../../u1/support/nextNavigation")).nextNavigationMock);

// SET-01 종료 표현 설정 시험 (U2 · US2.3 · AC2.3.1 ~ AC2.3.6 · AC2.3.8 · mockups S-SET1 · 9-11)

function handler(texts: readonly string[], overrides: Partial<Record<string, RequestHandler>> = {}): RequestHandler {
  return (path, init) => {
    const key = `${init.method ?? "GET"} ${path}`;
    const override = overrides[key];
    if (override !== undefined) {
      return override(path, init);
    }
    switch (key) {
      case "GET /api/child/stop-expressions":
        return jsonResponse(200, stopBody(texts));
      case "GET /api/home":
        return jsonResponse(200, homeBody("STABLE"));
      case "PUT /api/child/stop-expressions":
        return noContent();
      default:
        return errorResponse(404, "NOT_FOUND");
    }
  };
}

async function renderStops(texts: readonly string[] = ["이제 그만"], overrides: Partial<Record<string, RequestHandler>> = {}) {
  const harness = renderParent(<StopExpressions01 />, { handler: handler(texts, overrides) });
  await waitFor(() => expect(screen.getByTestId("stop-list")).toHaveAttribute("aria-busy", "false"));
  return harness;
}

function addExpression(text: string) {
  fireEvent.click(screen.getByTestId("stop-add"));
  fireEvent.change(screen.getByTestId("stop-input"), { target: { value: text } });
  fireEvent.click(screen.getByTestId("stop-input-confirm"));
}

beforeEach(() => {
  resetRouter();
});

describe("SET-01 목록(AC2.3.1 · AC2.3.8)", () => {
  it("머리는 '← {아이}의 종료 표현'이고 기본 표현은 단추 없는 고정 카드다", async () => {
    await renderStops();
    expect(await screen.findByRole("heading", { name: "하늘의 종료 표현" })).toBeInTheDocument();
    const fixed = screen.getByTestId("stop-default");
    expect(fixed).toHaveTextContent("“무중아 그만할래”");
    expect(fixed.className).toContain("mj-stop-card--fixed");
    expect(within(fixed).queryAllByRole("button")).toHaveLength(0);
    expect(screen.getAllByTestId("stop-item")).toHaveLength(1);
    expect(screen.getByTestId("stop-item-remove-1")).toHaveTextContent("삭제");
  });
});

describe("SET-01 추가 · 수정 · 삭제(AC2.3.2 · AC2.3.3 · AC2.3.5 · AC2.3.6)", () => {
  it("「+ 표현 추가」는 '새 표현' 칸을 열고 「확인」은 목록에만 반영한다(저장 전)", async () => {
    const harness = await renderStops();
    fireEvent.click(screen.getByTestId("stop-add"));
    expect(screen.getByLabelText("새 표현")).toHaveFocus();
    expect(screen.getByTestId("stop-save")).toBeDisabled();
    fireEvent.change(screen.getByTestId("stop-input"), { target: { value: " 끝낼래 " } });
    fireEvent.keyDown(screen.getByTestId("stop-input"), { key: "Enter" });
    expect(screen.getAllByTestId("stop-item").map((item) => item.textContent)).toEqual(
      expect.arrayContaining([expect.stringContaining("“끝낼래”")]),
    );
    expect(screen.queryByTestId("stop-input")).toBeNull();
    expect(harness.requests("/api/child/stop-expressions").filter((request) => request.method === "PUT")).toHaveLength(0);
  });

  it.each([
    ["가", "2~20자로 넣어 주세요"],
    ["이제그만", "이미 있는 표현이에요"],
    ["무중아  그만할래", "이미 있는 표현이에요"],
  ])("'%s'은 '%s' 오류 글과 함께 반영되지 않는다", async (text, message) => {
    await renderStops();
    addExpression(text);
    expect(screen.getByTestId("stop-input-error")).toHaveTextContent(message);
    expect(screen.getByTestId("stop-input")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getAllByTestId("stop-item")).toHaveLength(1);
    expect(screen.getByTestId("stop-save")).toBeDisabled();
    fireEvent.change(screen.getByTestId("stop-input"), { target: { value: "끝낼래" } });
    expect(screen.queryByTestId("stop-input-error")).toBeNull();
  });

  it("추가 표현이 5개면 「+ 표현 추가」가 꺼진다", async () => {
    await renderStops(["하나둘", "둘셋", "셋넷", "넷다섯", "다섯여섯"]);
    expect(screen.getByTestId("stop-add")).toBeDisabled();
  });

  it("표현 카드를 누르면 그 칸이 수정 상태가 되고 「삭제」는 목록에서 뺀다", async () => {
    await renderStops(["이제 그만", "끝낼래"]);
    fireEvent.click(screen.getByTestId("stop-item-edit-1"));
    expect(screen.getByTestId("stop-input")).toHaveValue("이제 그만");
    fireEvent.change(screen.getByTestId("stop-input"), { target: { value: "이제 그만할래" } });
    fireEvent.click(screen.getByTestId("stop-input-confirm"));
    expect(screen.getByTestId("stop-item-edit-1")).toHaveTextContent("이제 그만할래");
    fireEvent.click(screen.getByTestId("stop-item-edit-2"));
    fireEvent.click(screen.getByTestId("stop-item-remove-2"));
    expect(screen.queryByTestId("stop-input")).toBeNull();
    expect(screen.getAllByTestId("stop-item")).toHaveLength(1);
  });
});

describe("SET-01 저장 · 취소(AC2.3.4 · AC2.3.6)", () => {
  it("「저장」은 목록을 통째로 보내고 05로 간다", async () => {
    const harness = await renderStops(["이제 그만"]);
    addExpression("끝낼래");
    fireEvent.click(screen.getByTestId("stop-item-remove-1"));
    fireEvent.click(screen.getByTestId("stop-save"));
    await waitFor(() => expect(router.push).toHaveBeenCalledWith("/parent/device"));
    const put = harness.requests("/api/child/stop-expressions").find((request) => request.method === "PUT");
    expect(put?.body).toEqual({ items: [{ text: "끝낼래" }] });
    expect(put?.headers["x-csrf-token"]).toBe(harness.csrfToken);
  });

  it("「취소」 · 「←」는 변경을 버리고 05로 간다", async () => {
    const harness = await renderStops();
    addExpression("끝낼래");
    fireEvent.click(screen.getByTestId("stop-cancel"));
    fireEvent.click(screen.getByTestId("stop-back"));
    expect(router.push.mock.calls).toEqual([["/parent/device"], ["/parent/device"]]);
    expect(harness.requests("/api/child/stop-expressions").filter((request) => request.method === "PUT")).toHaveLength(0);
  });

  it("저장 실패는 실패로 알리고 05로 가지 않는다 · 401은 01", async () => {
    const first = await renderStops(["이제 그만"], { "PUT /api/child/stop-expressions": () => errorResponse(500, "INTERNAL_ERROR") });
    fireEvent.click(screen.getByTestId("stop-save"));
    expect(await screen.findByTestId("toast")).toHaveTextContent("저장하지 못했어요. 다시 시도해 주세요.");
    expect(router.push).not.toHaveBeenCalled();
    first.view.unmount();

    await renderStops(["이제 그만"], { "PUT /api/child/stop-expressions": () => errorResponse(401, "UNAUTHENTICATED") });
    fireEvent.click(screen.getByTestId("stop-save"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
  });
});

describe("SET-01 조회 실패", () => {
  it("아이가 없으면 03, 401이면 01, 그 밖은 토스트 + 「다시 시도」다", async () => {
    const first = renderParent(<StopExpressions01 />, {
      handler: handler([], { "GET /api/child/stop-expressions": () => errorResponse(404, "CHILD_NOT_REGISTERED") }),
    });
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/child"));
    first.view.unmount();
    resetRouter();

    const second = renderParent(<StopExpressions01 />, { handler: () => errorResponse(401, "UNAUTHENTICATED") });
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
    second.view.unmount();

    let calls = 0;
    renderParent(<StopExpressions01 />, {
      handler: handler(["이제 그만"], {
        "GET /api/child/stop-expressions": () => {
          calls += 1;
          return calls === 1 ? errorResponse(500, "INTERNAL_ERROR") : jsonResponse(200, stopBody(["이제 그만"]));
        },
      }),
    });
    expect(screen.getByTestId("stop-add")).toBeDisabled();
    fireEvent.click(await screen.findByTestId("toast-action"));
    expect(await screen.findByTestId("stop-item")).toBeInTheDocument();
  });
});

describe("SET-01 서버와 같은 규칙(리뷰 8)", () => {
  // 공백 = 유니코드 White_Space 속성, 글자 수 = NFC 뒤 코드 포인트. 같은 표본을
  // backend core-api ChildRulesTest.whitespaceAndLengthFollowTheSameUnicodeRulesAsTheScreen이 쓴다.
  it("공백 · 글자 수 표본이 서버 판정과 같다", () => {
    expect(normalizeExpression("이제　그만")).toBe("이제그만");
    expect(normalizeExpression("이제 그만")).toBe("이제그만");
    expect(normalizeExpression("끝\u0085내")).toBe("끝내");
    expect(normalizeExpression("이제﻿그만")).toBe("이제﻿그만");
    expect(validateExpression(" 끝 ", [], null)).toBe("2~20자로 넣어 주세요");
    expect(validateExpression(" 끝내 ", [], null)).toBeNull();
    expect(validateExpression("가".repeat(20).normalize("NFD"), [], null)).toBeNull();
    expect(validateExpression("😀".repeat(20), [], null)).toBeNull();
    expect(validateExpression("😀".repeat(21), [], null)).toBe("2~20자로 넣어 주세요");
    expect(validateExpression("이제　그만", ["이제그만"], null)).toBe("이미 있는 표현이에요");
  });

  it("입력 칸은 UTF-16 maxLength로 자르지 않는다(이모지 20개도 칠 수 있고 넘으면 오류 글)", async () => {
    await renderStops();
    fireEvent.click(screen.getByTestId("stop-add"));
    expect(screen.getByTestId("stop-input")).not.toHaveAttribute("maxlength");
    fireEvent.change(screen.getByTestId("stop-input"), { target: { value: "😀".repeat(20) } });
    fireEvent.click(screen.getByTestId("stop-input-confirm"));
    expect(screen.queryByTestId("stop-input-error")).toBeNull();
    expect(screen.getAllByTestId("stop-item")).toHaveLength(2);
  });
});

describe("SET-01 검증 도우미", () => {
  it("정규화 · 검증은 서버 규칙(R9)과 같다", () => {
    expect(normalizeExpression(" Stop  It ")).toBe("stopit");
    expect(validateExpression("끝낼래", ["이제 그만"], null)).toBeNull();
    expect(validateExpression("가".repeat(21), [], null)).toBe("2~20자로 넣어 주세요");
    expect(validateExpression("이제 그만", ["이제 그만"], 0)).toBeNull();
    expect(validateExpression("여섯째", ["a1", "b2", "c3", "d4", "e5"], null)).toBe("표현은 5개까지 담을 수 있어요");
    expect(validateExpression("STOP IT", ["stopit"], null)).toBe("이미 있는 표현이에요");
  });
});
