import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ChildScreen, interestsWithinLimit, parseAge, parseInterests } from "@/parent/features/child/ChildScreen";
import { errorResponse, homeBody, jsonResponse, type NetworkState } from "../../../support/fixtures";
import { resetRouter, router } from "../../../support/nextNavigation";
import { renderParent, type RequestHandler } from "../../../support/parentHarness";
import { childBody } from "../../../support/accountFixtures";

vi.mock("next/navigation", async () => (await import("../../../support/nextNavigation")).nextNavigationMock);

// 03 아이 정보 시험 (U2 · US1.3 · AC1.3.1 ~ AC1.3.10 · mockups S-03 · 9-2 · 9-3)

function handler(overrides: Partial<Record<string, RequestHandler>> = {}, state: NetworkState = "STABLE"): RequestHandler {
  return (path, init) => {
    const key = `${init.method ?? "GET"} ${path}`;
    const override = overrides[key];
    if (override !== undefined) {
      return override(path, init);
    }
    switch (key) {
      case "GET /api/child":
        return jsonResponse(200, childBody());
      case "GET /api/home":
        return jsonResponse(200, homeBody(state));
      case "POST /api/child":
        return jsonResponse(201, childBody());
      case "PATCH /api/child":
        return jsonResponse(200, childBody({ age: 9, interests: ["공룡"] }));
      default:
        return errorResponse(404, "NOT_FOUND");
    }
  };
}

const notRegistered = { "GET /api/child": () => errorResponse(404, "CHILD_NOT_REGISTERED") };

function type(testId: string, value: string) {
  fireEvent.change(screen.getByTestId(testId), { target: { value } });
}

beforeEach(() => {
  resetRouter();
});

describe("03 처음 등록(AC1.3.1 · AC1.3.2 · AC1.3.5 · AC1.3.8)", () => {
  it("제목 · 세 칸 · 「확인」이 있고 뒤로 단추가 없으며 기기 뒤로가기도 아무 일 없다", async () => {
    renderParent(<ChildScreen />, { handler: handler(notRegistered) });
    expect(await screen.findByRole("heading", { name: "아이 정보를 알려 주세요" })).toBeInTheDocument();
    expect(screen.getByLabelText("이름 또는 별명")).toBeInTheDocument();
    expect(screen.getByLabelText("나이")).toBeInTheDocument();
    expect(screen.getByLabelText("관심사 (예: 공룡, 블록 놀이)")).toBeInTheDocument();
    expect(screen.queryByTestId("child-back")).toBeNull();
    act(() => window.dispatchEvent(new PopStateEvent("popstate")));
    expect(router.push).not.toHaveBeenCalled();
    expect(router.replace).not.toHaveBeenCalled();
    expect(screen.getByTestId("child-screen")).toHaveAttribute("data-mode", "first");
  });

  it("이름이 비면 「확인」이 꺼지고 나이가 6 ~ 12 밖이면 칸 아래 글이 붙는다(색만이 아님)", async () => {
    renderParent(<ChildScreen />, { handler: handler(notRegistered) });
    await screen.findByTestId("child-form");
    expect(screen.getByTestId("child-confirm")).toBeDisabled();
    type("child-name", "하늘");
    type("child-age", "13");
    expect(screen.getByTestId("child-age-error")).toHaveTextContent("나이는 6~12 사이로 넣어 주세요");
    expect(screen.getByTestId("child-age")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByTestId("child-confirm")).toBeDisabled();
    type("child-age", "8살");
    expect(screen.getByTestId("child-age")).toHaveValue("8");
    expect(screen.queryByTestId("child-age-error")).toBeNull();
    expect(screen.getByTestId("child-confirm")).toBeEnabled();
    type("child-name", "   ");
    expect(screen.getByTestId("child-confirm")).toBeDisabled();
    expect(screen.getByTestId("child-name")).toHaveAttribute("maxLength", "10");
  });

  it("관심사는 쉼표로 5개 · 하나에 10자를 넘는 입력을 막는다", async () => {
    renderParent(<ChildScreen />, { handler: handler(notRegistered) });
    await screen.findByTestId("child-form");
    type("child-interests", "공룡, 그림, 블록, 노래, 축구");
    type("child-interests", "공룡, 그림, 블록, 노래, 축구, 수영");
    expect(screen.getByTestId("child-interests")).toHaveValue("공룡, 그림, 블록, 노래, 축구");
    type("child-interests", "가나다라마바사아자차카");
    expect(screen.getByTestId("child-interests")).toHaveValue("공룡, 그림, 블록, 노래, 축구");
  });

  it("「확인」이면 다듬은 값으로 등록하고 04로 간다(AC1.3.1)", async () => {
    const harness = renderParent(<ChildScreen />, { handler: handler(notRegistered) });
    await screen.findByTestId("child-form");
    type("child-name", " 하늘 ");
    type("child-age", "8");
    type("child-interests", "공룡, 블록 놀이, 공룡, ");
    fireEvent.click(screen.getByTestId("child-confirm"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent"));
    const posted = harness.requests("/api/child").find((request) => request.method === "POST");
    expect(posted?.body).toEqual({ displayName: "하늘", age: 8, interests: ["공룡", "블록 놀이"] });
    expect(posted?.headers["x-csrf-token"]).toBe(harness.csrfToken);
  });

  it("저장이 실패하면 토스트이고 입력은 그대로다(AC1.3.6 · 9-2)", async () => {
    renderParent(<ChildScreen />, { handler: handler({ ...notRegistered, "POST /api/child": () => errorResponse(500, "INTERNAL_ERROR") }) });
    await screen.findByTestId("child-form");
    type("child-name", "하늘");
    type("child-age", "8");
    fireEvent.click(screen.getByTestId("child-confirm"));
    expect(await screen.findByTestId("toast")).toHaveTextContent("저장하지 못했어요. 이전 값이 그대로예요.");
    expect(screen.getByTestId("child-name")).toHaveValue("하늘");
    expect(router.replace).not.toHaveBeenCalled();
  });
});

describe("03 보기 · 수정(AC1.3.3 · AC1.3.4 · AC1.3.7 · AC1.3.9 · AC1.3.10)", () => {
  it("카드에 이름 · 'n세 · 관심사' · 기기 상태 줄 · 「정보 수정」이 보이고 선택 · 추가 · 삭제 단추는 없다", async () => {
    renderParent(<ChildScreen />, { handler: handler() });
    expect(await screen.findByTestId("child-card-name")).toHaveTextContent("하늘");
    expect(screen.getByTestId("child-card")).toHaveTextContent("8세 · 공룡, 그림");
    expect(await screen.findByTestId("child-card-device")).toHaveTextContent("기기 · 안정");
    expect(screen.getByTestId("child-edit")).toHaveTextContent("정보 수정");
    expect(screen.queryByText(/아이 추가|아이 삭제|아이 선택/)).toBeNull();
  });

  it.each<[NetworkState, string]>([
    ["UNSTABLE", "기기 · 불안정"],
    ["DISCONNECTED", "기기 · 끊김"],
    ["UNRESPONSIVE", "기기 · 응답 없음"],
    ["NONE", "기기 · 등록 안 됨"],
    ["UNKNOWN", "기기 · 기기 상태 확인 불가"],
  ])("기기 %s이면 '%s'(9-3)", async (state, line) => {
    renderParent(<ChildScreen />, { handler: handler({}, state) });
    expect(await screen.findByTestId("child-card-device")).toHaveTextContent(line);
  });

  it("「정보 수정」 → 바꾸고 「확인」이면 같은 아이를 고쳐 보기로 돌아온다", async () => {
    const harness = renderParent(<ChildScreen />, { handler: handler() });
    fireEvent.click(await screen.findByTestId("child-edit"));
    expect(screen.getByTestId("child-name")).toHaveValue("하늘");
    expect(screen.getByTestId("child-age")).toHaveValue("8");
    expect(screen.getByTestId("child-interests")).toHaveValue("공룡, 그림");
    type("child-age", "9");
    type("child-interests", "공룡");
    fireEvent.click(screen.getByTestId("child-confirm"));
    await waitFor(() => expect(screen.getByTestId("child-screen")).toHaveAttribute("data-mode", "view"));
    expect(screen.getByTestId("child-card")).toHaveTextContent("9세 · 공룡");
    expect(harness.requests("/api/child").find((request) => request.method === "PATCH")?.body).toEqual({
      displayName: "하늘",
      age: 9,
      interests: ["공룡"],
    });
  });

  it("「취소」는 원래 내용으로 돌아오고 「←」는 04로 간다", async () => {
    renderParent(<ChildScreen />, { handler: handler() });
    fireEvent.click(await screen.findByTestId("child-edit"));
    type("child-name", "바다");
    fireEvent.click(screen.getByTestId("child-cancel"));
    expect(screen.getByTestId("child-card-name")).toHaveTextContent("하늘");
    fireEvent.click(screen.getByTestId("child-edit"));
    expect(screen.getByTestId("child-name")).toHaveValue("하늘");
    fireEvent.click(screen.getByTestId("child-back"));
    expect(router.push).toHaveBeenCalledWith("/parent");
  });

  it("수정 저장 실패는 토스트이고 수정 상태가 남는다 · 401이면 01이다", async () => {
    renderParent(<ChildScreen />, { handler: handler({ "PATCH /api/child": () => errorResponse(500, "INTERNAL_ERROR") }) });
    fireEvent.click(await screen.findByTestId("child-edit"));
    fireEvent.click(screen.getByTestId("child-confirm"));
    expect(await screen.findByTestId("toast")).toHaveTextContent("저장하지 못했어요. 이전 값이 그대로예요.");
    expect(screen.getByTestId("child-screen")).toHaveAttribute("data-mode", "edit");

    resetRouter();
    renderParent(<ChildScreen />, { handler: () => errorResponse(401, "UNAUTHENTICATED") });
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
  });

  it("다른 조회 실패는 토스트 + 「다시 시도」다", async () => {
    let calls = 0;
    renderParent(<ChildScreen />, {
      handler: handler({
        "GET /api/child": () => {
          calls += 1;
          return calls === 1 ? errorResponse(500, "INTERNAL_ERROR") : jsonResponse(200, childBody());
        },
      }),
    });
    expect(screen.getByTestId("child-loading")).toBeInTheDocument();
    fireEvent.click(await screen.findByTestId("toast-action"));
    expect(await screen.findByTestId("child-card")).toBeInTheDocument();
  });
});

describe("03 입력 도우미", () => {
  it("관심사 · 나이를 읽는다", () => {
    expect(parseInterests(" 공룡 ,, 그림, 공룡 ")).toEqual(["공룡", "그림"]);
    expect(interestsWithinLimit("a, b, c, d, e")).toBe(true);
    expect(interestsWithinLimit("a, b, c, d, e, f")).toBe(false);
    expect(interestsWithinLimit("가나다라마바사아자차")).toBe(true);
    expect(parseAge("6")).toBe(6);
    expect(parseAge("12")).toBe(12);
    expect(parseAge("5")).toBeNull();
    expect(parseAge("")).toBeNull();
    expect(parseAge("1e1")).toBeNull();
  });
});
