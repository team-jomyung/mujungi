import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ConsentScreen } from "@/parent/features/consent/ConsentScreen";
import { errorResponse, jsonResponse } from "../../../support/fixtures";
import { resetRouter, router } from "../../../support/nextNavigation";
import { renderParent, type RequestHandler } from "../../../support/parentHarness";
import { meBody, noContent, policyBody } from "../../../support/accountFixtures";

vi.mock("next/navigation", async () => (await import("../../../support/nextNavigation")).nextNavigationMock);

// 02 기록 · 동의 안내 + 02-1 약관 시트 + P5 시험 (U2 · US1.2 · AC1.2.1 ~ AC1.2.9 · mockups S-02 · S-02-1 · P-05)

const CHECKS = ["voice", "view", "report", "audio"] as const;

function handler(overrides: Partial<Record<string, RequestHandler>> = {}): RequestHandler {
  return (path, init) => {
    const key = `${init.method ?? "GET"} ${path}`;
    const override = overrides[key];
    if (override !== undefined) {
      return override(path, init);
    }
    switch (key) {
      case "GET /api/policies/current":
        return jsonResponse(200, policyBody());
      case "PUT /api/consents":
      case "POST /api/auth/logout":
        return noContent();
      case "GET /api/me":
        return jsonResponse(200, meBody({ childRegistered: false }));
      default:
        return errorResponse(404, "NOT_FOUND");
    }
  };
}

async function renderConsent(overrides: Partial<Record<string, RequestHandler>> = {}) {
  const harness = renderParent(<ConsentScreen />, { handler: handler(overrides) });
  await waitFor(() => expect(screen.getByTestId("consent-check-voice")).toBeEnabled());
  return harness;
}

function checkAll() {
  for (const check of CHECKS) {
    fireEvent.click(screen.getByTestId(`consent-check-${check}`));
  }
}

beforeEach(() => {
  resetRouter();
});

describe("02 — 네 항목 모두 필수(AC1.2.1 · AC1.2.4)", () => {
  it("제목 · 네 항목 · '필수' 배지가 보이고 네 개를 다 체크해야 「동의하고 계속」이 켜진다", async () => {
    await renderConsent();
    expect(screen.getByRole("heading", { name: "시작 전에 확인해 주세요" })).toBeInTheDocument();
    for (const label of ["음성 수집 · 글로 옮겨 저장", "보호자 열람", "리포트 생성 · 외부 전달", "원음 보관"]) {
      expect(screen.getByLabelText(new RegExp(label))).toBeInTheDocument();
    }
    expect(screen.getAllByText("필수")).toHaveLength(4);
    expect(screen.getByTestId("consent-agree")).toBeDisabled();
    fireEvent.click(screen.getByTestId("consent-check-voice"));
    fireEvent.click(screen.getByTestId("consent-check-view"));
    fireEvent.click(screen.getByTestId("consent-check-report"));
    expect(screen.getByTestId("consent-agree")).toBeDisabled();
    fireEvent.click(screen.getByTestId("consent-check-audio"));
    expect(screen.getByTestId("consent-agree")).toBeEnabled();
    fireEvent.click(screen.getByTestId("consent-check-audio"));
    expect(screen.getByTestId("consent-agree")).toBeDisabled();
  });

  it("동의하면 4항목을 현재 정책 버전으로 저장하고 아이가 없으면 03으로 간다", async () => {
    const harness = await renderConsent();
    checkAll();
    fireEvent.click(screen.getByTestId("consent-agree"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/child"));
    const saved = harness.requests("/api/consents")[0];
    expect(saved.method).toBe("PUT");
    expect(saved.headers["x-csrf-token"]).toBe(harness.csrfToken);
    expect(saved.body).toEqual({
      policyVersion: "1.0",
      items: [
        { type: "VOICE_AND_TEXT", agreed: true },
        { type: "GUARDIAN_VIEW", agreed: true },
        { type: "REPORT_SHARING", agreed: true },
        { type: "RAW_AUDIO", agreed: true },
      ],
    });
  });

  it("재동의(아이 있음)면 04로 간다", async () => {
    await renderConsent({ "GET /api/me": () => jsonResponse(200, meBody({ childRegistered: true })) });
    checkAll();
    fireEvent.click(screen.getByTestId("consent-agree"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent"));
  });

  it("저장이 실패하면 토스트이고 체크는 그대로다", async () => {
    await renderConsent({ "PUT /api/consents": () => errorResponse(500, "INTERNAL_ERROR") });
    checkAll();
    fireEvent.click(screen.getByTestId("consent-agree"));
    expect(await screen.findByTestId("toast")).toHaveTextContent("저장하지 못했어요");
    expect(screen.getByTestId("consent-check-audio")).toBeChecked();
    expect(router.replace).not.toHaveBeenCalled();
  });

  it("약관 버전이 바뀌었으면(400) 약관을 다시 읽고 체크를 비운다(편차 (U2-f))", async () => {
    let policyReads = 0;
    await renderConsent({
      "PUT /api/consents": () => errorResponse(400, "VALIDATION_FAILED"),
      "GET /api/policies/current": () => {
        policyReads += 1;
        return jsonResponse(200, policyBody(policyReads === 1 ? "1.0" : "1.1"));
      },
    });
    checkAll();
    fireEvent.click(screen.getByTestId("consent-agree"));
    await waitFor(() => expect(policyReads).toBe(2));
    expect(screen.getByTestId("consent-check-voice")).not.toBeChecked();
    expect(screen.getByTestId("consent-agree")).toBeDisabled();
  });

  it("401이면 안내 토스트 뒤 01이다(AC1.1.9)", async () => {
    await renderConsent({ "PUT /api/consents": () => errorResponse(401, "UNAUTHENTICATED") });
    checkAll();
    fireEvent.click(screen.getByTestId("consent-agree"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
  });

  it("약관을 못 읽으면 토스트 + 「다시 시도」, 401이면 01이다", async () => {
    let calls = 0;
    renderParent(<ConsentScreen />, {
      handler: handler({
        "GET /api/policies/current": () => {
          calls += 1;
          return calls === 1 ? errorResponse(503, "SERVICE_NOT_READY") : jsonResponse(200, policyBody());
        },
      }),
    });
    expect(await screen.findByTestId("toast")).toHaveTextContent("잠시 문제가 생겼어요. 다시 시도해 주세요.");
    expect(screen.getByTestId("consent-check-voice")).toBeDisabled();
    fireEvent.click(screen.getByTestId("toast-action"));
    await waitFor(() => expect(screen.getByTestId("consent-check-voice")).toBeEnabled());

    resetRouter();
    renderParent(<ConsentScreen />, { handler: () => errorResponse(401, "UNAUTHENTICATED") });
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
  });
});

describe("02-1 약관 시트(AC1.2.2 · AC1.2.3 · AC1.2.9)", () => {
  it("「자세히 보기 ›」는 그 항목 약관을 보이고 모든 항목에 생성형 AI 안내가 '동의하지 않으면' 앞에 있다", async () => {
    await renderConsent();
    for (const check of CHECKS) {
      fireEvent.click(screen.getByTestId(`consent-detail-${check}`));
      const body = screen.getByTestId("terms-body");
      const headings = Array.from(body.querySelectorAll("dt")).map((dt) => dt.textContent);
      expect(headings.slice(-2)).toEqual(["생성형 AI 안내", "동의하지 않으면"]);
      expect(body).toHaveTextContent("무중은 생성형 AI가 아이와 대화하는 서비스예요.");
      expect(screen.getByRole("dialog")).toHaveAttribute("aria-modal", "true");
      fireEvent.click(screen.getByTestId("terms-confirm"));
      expect(screen.queryByTestId("terms-sheet")).toBeNull();
    }
  });

  it("음성 수집 시트는 문구표 소제목과 본문이고 바깥 · Escape로 닫혀 체크 상태는 그대로다", async () => {
    await renderConsent();
    fireEvent.click(screen.getByTestId("consent-check-view"));
    fireEvent.click(screen.getByTestId("consent-detail-voice"));
    expect(screen.getByRole("heading", { name: "음성 수집 · 글로 옮겨 저장" })).toHaveFocus();
    expect(screen.getByTestId("terms-body")).toHaveTextContent("아이가 기기에서 말한 음성");
    fireEvent.click(screen.getByTestId("terms-backdrop"));
    expect(screen.queryByTestId("terms-sheet")).toBeNull();
    fireEvent.click(screen.getByTestId("consent-detail-audio"));
    fireEvent.keyDown(screen.getByTestId("terms-sheet"), { key: "Escape" });
    expect(screen.queryByTestId("terms-sheet")).toBeNull();
    expect(screen.getByTestId("consent-check-view")).toBeChecked();
  });
});

describe("P5 동의 전 나가기(AC1.2.5 · AC1.2.6 · AC1.2.8)", () => {
  it("「←」는 P5를 열고 기본 초점은 「계속 동의하기」이며 누르면 02로 돌아온다", async () => {
    await renderConsent();
    fireEvent.click(screen.getByTestId("consent-back"));
    expect(screen.getByRole("alertdialog")).toHaveTextContent("동의하지 않으면 무중을 쓸 수 없어요");
    expect(screen.getByRole("alertdialog")).toHaveTextContent("네 항목에 모두 동의해야 시작할 수 있어요. 나가면 로그아웃돼요.");
    expect(screen.getByTestId("p5-no")).toHaveFocus();
    expect(screen.getByTestId("p5-no")).toHaveTextContent("계속 동의하기");
    fireEvent.click(screen.getByTestId("p5-no"));
    expect(screen.queryByTestId("p5-dialog")).toBeNull();
  });

  it("기기 뒤로가기도 P5를 연다", async () => {
    await renderConsent();
    act(() => window.dispatchEvent(new PopStateEvent("popstate")));
    expect(screen.getByTestId("p5-dialog")).toBeInTheDocument();
  });

  it("「로그아웃」은 세션을 끝내고 01로 간다(이미 끝났어도 01, 다른 실패는 토스트)", async () => {
    const harness = await renderConsent();
    fireEvent.click(screen.getByTestId("consent-back"));
    fireEvent.click(screen.getByTestId("p5-yes"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
    expect(harness.requests("/api/auth/logout")).toHaveLength(1);

    harness.view.unmount();
    resetRouter();
    await renderConsent({ "POST /api/auth/logout": () => errorResponse(401, "UNAUTHENTICATED") });
    fireEvent.click(screen.getByTestId("consent-back"));
    fireEvent.click(screen.getByTestId("p5-yes"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
  });

  it("로그아웃이 실패하면 토스트이고 02에 남는다", async () => {
    await renderConsent({ "POST /api/auth/logout": () => errorResponse(500, "INTERNAL_ERROR") });
    fireEvent.click(screen.getByTestId("consent-back"));
    fireEvent.click(screen.getByTestId("p5-yes"));
    expect(await screen.findByTestId("toast")).toHaveTextContent("로그아웃하지 못했어요. 다시 시도해 주세요.");
    expect(router.replace).not.toHaveBeenCalled();
  });

  it("동의를 바꾸거나 거두는 단추가 없다(AC1.2.8)", async () => {
    await renderConsent();
    expect(screen.queryByText(/철회|동의 취소|바꾸기/)).toBeNull();
  });
});
