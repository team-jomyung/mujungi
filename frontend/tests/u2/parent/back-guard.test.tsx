import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { StrictMode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useBackGuard } from "@/parent/hooks/useBackGuard";
import { Child03 } from "@/parent/screens/Child03";
import { Consent02 } from "@/parent/screens/Consent02";
import { ACCOUNT_COPY } from "@/shared/copy/account";
import { errorResponse, homeBody, jsonResponse } from "../../u1/support/fixtures";
import { resetRouter, router } from "../../u1/support/nextNavigation";
import { renderParent, type RequestHandler } from "../../u1/support/parentHarness";
import { childBody, meBody, noContent, policyBody } from "../support/accountFixtures";

vi.mock("next/navigation", async () => (await import("../../u1/support/nextNavigation")).nextNavigationMock);

// 기기 뒤로가기 가드 시험 (U2 · 리뷰 5 · AC1.2.5 · AC1.3.2): 가드는 같은 주소 기록 칸을 한 칸만 쌓고(StrictMode 포함), 화면을 마치고 떠날 때
// 그 칸을 되돌린 뒤 이동한다 — 02 · 03을 마친 뒤 뒤로가기가 끝난 화면으로 돌아가지 않는다.

const GUARD_KEY = "__mujoongBackGuard";

function isGuardEntry(): boolean {
  const state: unknown = window.history.state;
  return typeof state === "object" && state !== null && GUARD_KEY in state;
}

/** 시험 대상 화면 대역: 가드를 켜고 「떠나기」로 이동한다. */
function GuardProbe({ onBack, navigate }: { readonly onBack: () => void; readonly navigate: () => void }) {
  const { leave } = useBackGuard(true, onBack);
  return (
    <button type="button" data-testid="leave" onClick={() => leave(navigate)}>
      {ACCOUNT_COPY.settings.logout}
    </button>
  );
}

function routeAware(handlers: Record<string, RequestHandler>): RequestHandler {
  return (path, init) => {
    const handler = handlers[`${init.method ?? "GET"} ${path}`];
    return handler === undefined ? errorResponse(404, "NOT_FOUND") : handler(path, init);
  };
}

beforeEach(() => {
  resetRouter();
  // 앞 시험이 남긴 가드 칸을 일반 칸으로 바꿔 둔다(같은 문서의 기록은 시험 사이에 이어진다)
  window.history.replaceState(null, "", window.location.href);
});

describe("useBackGuard — 기록 칸", () => {
  it("StrictMode에서도 한 칸만 쌓는다", () => {
    const before = window.history.length;
    render(
      <StrictMode>
        <GuardProbe onBack={vi.fn()} navigate={vi.fn()} />
      </StrictMode>,
    );
    expect(window.history.length).toBe(before + 1);
    expect(isGuardEntry()).toBe(true);
  });

  it("기기 뒤로가기는 칸을 다시 쌓고 onBack을 부른다", async () => {
    const onBack = vi.fn();
    render(<GuardProbe onBack={onBack} navigate={vi.fn()} />);
    act(() => window.history.back());
    await waitFor(() => expect(onBack).toHaveBeenCalledTimes(1));
    expect(isGuardEntry()).toBe(true);
  });

  it("떠날 때는 쌓은 칸을 되돌린 뒤 이동하고 onBack을 부르지 않는다", async () => {
    const onBack = vi.fn();
    const seen: boolean[] = [];
    const navigate = vi.fn(() => seen.push(isGuardEntry()));
    render(<GuardProbe onBack={onBack} navigate={navigate} />);
    expect(isGuardEntry()).toBe(true);

    fireEvent.click(screen.getByTestId("leave"));

    await waitFor(() => expect(navigate).toHaveBeenCalledTimes(1));
    expect(seen).toEqual([false]);
    expect(onBack).not.toHaveBeenCalled();
  });
});

describe("useBackGuard — 새로고침 뒤(2차 리뷰 1)", () => {
  // 새로고침해도 history.state의 가드 표시는 남지만 그 칸 앞의 칸은 이전 문서다. 그 표시를 '이 문서가 쌓은 칸'으로 믿으면
  // leave()의 back()이 문서 전체를 다시 불러 이동이 일어나지 않고, 기기 뒤로가기는 P5 대신 02를 다시 불러온다.
  function leaveStaleMarker() {
    window.history.replaceState({ [GUARD_KEY]: "이전-문서" }, "", window.location.href);
  }

  it("다른 문서가 남긴 가드 표시 위에서는 이 문서의 칸을 새로 쌓는다", () => {
    leaveStaleMarker();
    // 앞 시험의 back()이 남긴 앞쪽 칸 때문에 history.length는 그대로일 수 있어 쌓은 횟수를 센다
    const push = vi.spyOn(window.history, "pushState");
    render(<GuardProbe onBack={vi.fn()} navigate={vi.fn()} />);
    expect(push).toHaveBeenCalledTimes(1);
    push.mockRestore();
    expect((window.history.state as Record<string, unknown>)[GUARD_KEY]).not.toBe("이전-문서");
  });

  it("그 상태에서 기기 뒤로가기는 같은 문서 안에서 onBack(P5)을 부른다", async () => {
    leaveStaleMarker();
    const onBack = vi.fn();
    render(<GuardProbe onBack={onBack} navigate={vi.fn()} />);
    act(() => window.history.back());
    await waitFor(() => expect(onBack).toHaveBeenCalledTimes(1));
    expect((window.history.state as Record<string, unknown>)[GUARD_KEY]).not.toBe("이전-문서");
  });

  it("그 상태에서 떠나면 이 문서가 쌓은 칸만 되돌리고 이동한다", async () => {
    leaveStaleMarker();
    const seen: unknown[] = [];
    const navigate = vi.fn(() => seen.push((window.history.state as Record<string, unknown> | null)?.[GUARD_KEY]));
    render(<GuardProbe onBack={vi.fn()} navigate={navigate} />);
    fireEvent.click(screen.getByTestId("leave"));
    await waitFor(() => expect(navigate).toHaveBeenCalledTimes(1));
    expect(seen).toEqual(["이전-문서"]);
  });
});

describe("02 · 03을 마치면 가드 칸이 없다", () => {
  it("02 「동의하고 계속」 뒤 이동 때 가드 칸이 되돌려져 있다", async () => {
    const seen: boolean[] = [];
    router.replace.mockImplementation(() => {
      seen.push(isGuardEntry());
    });
    renderParent(<Consent02 />, {
      handler: routeAware({
        "GET /api/policies/current": () => jsonResponse(200, policyBody()),
        "PUT /api/consents": () => noContent(),
        "GET /api/me": () => jsonResponse(200, meBody({ childRegistered: false })),
      }),
    });
    await waitFor(() => expect(screen.getByTestId("consent-check-voice")).toBeEnabled());
    for (const check of ["voice", "view", "report", "audio"]) {
      fireEvent.click(screen.getByTestId(`consent-check-${check}`));
    }
    expect(isGuardEntry()).toBe(true);

    fireEvent.click(screen.getByTestId("consent-agree"));

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/child"));
    expect(seen).toEqual([false]);
  });

  it("03 처음 등록 「확인」 뒤 이동 때 가드 칸이 되돌려져 있다", async () => {
    const seen: boolean[] = [];
    router.replace.mockImplementation(() => {
      seen.push(isGuardEntry());
    });
    renderParent(<Child03 />, {
      handler: routeAware({
        "GET /api/child": () => errorResponse(404, "CHILD_NOT_REGISTERED"),
        "POST /api/child": () => jsonResponse(201, childBody()),
        "GET /api/home": () => jsonResponse(200, homeBody("STABLE")),
      }),
    });
    fireEvent.change(await screen.findByTestId("child-name"), { target: { value: "하늘" } });
    fireEvent.change(screen.getByTestId("child-age"), { target: { value: "8" } });
    expect(isGuardEntry()).toBe(true);

    fireEvent.click(screen.getByTestId("child-confirm"));

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent"));
    expect(seen).toEqual([false]);
  });
});
