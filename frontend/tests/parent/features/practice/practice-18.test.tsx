import { act, cleanup, fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ParentPracticePage from "@/app/parent/practice/page";
import { PracticeScreen } from "@/parent/features/practice/PracticeScreen";
import { useToast } from "@/shared/toast/ToastProvider";
import { deferred, errorResponse, jsonResponse } from "../../../support/fixtures";
import { resetRouter, router } from "../../../support/nextNavigation";
import { renderParent, type RequestHandler } from "../../../support/parentHarness";
import { G1, G2, G3, GOALS, PATH, RESTORE_UNTIL, goal } from "../../../support/practiceFixtures";

vi.mock("next/navigation", async () => (await import("../../../support/nextNavigation")).nextNavigationMock);

const UNDO_MS = 100;
const row = (id = G1) => screen.getByTestId(`goal-${id}`);
const remove = (name = "친구에게 부탁하기") => screen.getByRole("button", { name: `삭제 — ${name}` });
const favorite = (name = "친구에게 부탁하기", active = false) => screen.getByRole("button", { name: `즐겨찾기 ${active ? "해제" : "추가"} — ${name}` });
const check = (name = "친구에게 부탁하기") => screen.getByRole("checkbox", { name: `선택 — ${name}` });

async function advance(ms = 1) { await act(async () => { await vi.advanceTimersByTimeAsync(ms); }); }

function ReplaceToast() {
  const toast = useToast();
  return <button type="button" onClick={() => toast.show({ message: "새 알림" })}>새 알림</button>;
}

function setup(handler: RequestHandler = () => jsonResponse(200, { items: GOALS })) {
  return renderParent(<><PracticeScreen /><ReplaceToast /></>, { handler, timing: { toastDurationMs: 40, toastActionMinMs: UNDO_MS } });
}

beforeEach(() => { vi.useFakeTimers(); resetRouter(); });
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); });

describe("18 — list, selection and scope", () => {
  it("keeps saved goals but caps each session at three and allows replacement", async () => {
    const fourth=goal({goalSelectionId:"10000000-0000-4000-8000-000000000004",displayName:"의견 묻기",sortOrder:4});
    const harness=setup(()=>jsonResponse(200,{items:[...GOALS,fourth]}));await advance();
    expect(screen.getByText(/한 번에 최대 3개/)).toBeVisible();
    fireEvent.click(check());fireEvent.click(check("차례 기다리기"));fireEvent.click(check("도움 요청하기"));
    expect(check("의견 묻기")).toBeDisabled();expect(check()).toBeEnabled();
    expect(screen.getByTestId("practice-start")).toHaveTextContent("선택한 3개");
    expect(screen.getAllByRole("checkbox")).toHaveLength(4);
    fireEvent.click(check());expect(check("의견 묻기")).toBeEnabled();fireEvent.click(check("의견 묻기"));
    expect(screen.getByTestId("practice-start")).toHaveTextContent("선택한 3개");
    expect(harness.requests(/activities/)).toHaveLength(0);
  });
  it("connects the actual route, enables explicit U5b start and never starts on selection", async () => {
    expect((await ParentPracticePage()).type).toBe(PracticeScreen);
    const harness = setup();
    await advance();
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("이번에 살펴볼 목표");
    expect(screen.getByTestId("tab-practice")).toHaveAttribute("aria-selected", "true");
    expect(screen.getByTestId("practice-start")).toHaveTextContent("목표를 골라 주세요");
    fireEvent.click(check());
    fireEvent.click(check("차례 기다리기"));
    expect(screen.getByTestId("practice-start")).toHaveTextContent("선택한 2개로 연습 시작");
    // U5b 통합 승인: 영구 비활성 자리 대신 선택 후 시작 가능, 선택만으로 전송하지 않음.
    expect(screen.getByTestId("practice-start")).toBeEnabled();
    expect(screen.queryByText("연습 시작 기능을 준비하고 있어요.")).toBeNull();
    expect(harness.requests(/activities/)).toHaveLength(0);
    fireEvent.click(check());
    expect(screen.getByTestId("practice-start")).toHaveTextContent("선택한 1개로 연습 시작");
  });

  it("groups favorites first, preserves saved order, and formats truthful metadata", async () => {
    setup(); await advance();
    expect(screen.getAllByRole("listitem").map((item) => item.getAttribute("data-testid"))).toEqual([`goal-${G3}`, `goal-${G1}`, `goal-${G2}`]);
    expect(within(row()).getByText("10/6 자유대화에서 담음 · 연습 2회")).toBeVisible();
    expect(row(G2)).toHaveTextContent("10/6 연습에서 담음 · 아직 안 함");
    expect(row(G3)).toHaveTextContent("담은 기록 확인 중 · 연습 횟수 확인 중");
  });

  it("waits for the real initial response before showing empty guidance", async () => {
    const response = deferred<Response>();
    setup(() => response.promise);
    expect(screen.getByText("목표를 불러오고 있어요.")).toBeVisible();
    expect(screen.queryByText("아직 담은 목표가 없어요")).toBeNull();
    response.resolve(jsonResponse(200, { items: [] })); await advance();
    expect(screen.getByText("아직 담은 목표가 없어요")).toBeVisible();
    expect(screen.getByText("이렇게 해 보세요")).toBeVisible();
    expect(screen.getAllByRole("listitem")).toHaveLength(3);
    expect(screen.getByRole("link", { name: "자유대화 하러 가기" })).toHaveAttribute("href", "/parent/free");
    expect(screen.queryByTestId("practice-start")).toBeNull();
  });

  it("uses native keyboard selection and independent, named favorite/delete controls", async () => {
    vi.useRealTimers();
    const user = userEvent.setup();
    setup();
    const checkbox = await screen.findByRole("checkbox", { name: "선택 — 친구에게 부탁하기" });
    checkbox.focus();
    await user.keyboard(" ");
    expect(checkbox).toBeChecked();
    expect(favorite()).toHaveAttribute("aria-pressed", "false");
    expect(remove()).toHaveAttribute("type", "button");
    expect(screen.getByText("1개 목표 선택")).toHaveAttribute("role", "status");
  });

  it("reads once on entry and uses the existing tab routes", async () => {
    const harness = setup(); await advance();
    fireEvent.click(screen.getByTestId("tab-practice"));
    fireEvent.click(screen.getByTestId("tab-home"));
    fireEvent.click(screen.getByTestId("tab-free"));
    fireEvent.click(screen.getByTestId("tab-records"));
    expect(router.push.mock.calls).toEqual([["/parent"], ["/parent/free"], ["/parent/records"]]);
    await advance(5000);
    expect(harness.requests(PATH)).toHaveLength(1);
  });
});

describe("18 — read and favorite failures", () => {
  it("uses the configured slow-read boundary, retries, and ignores the late cancelled read", async () => {
    const first = deferred<Response>();
    let reads = 0;
    const harness = renderParent(<PracticeScreen />, {
      handler: () => ++reads === 1 ? first.promise : jsonResponse(200, { items: [goal()] }),
      timing: { parentLoadingNoticeDelayMs: 50, toastDurationMs: 40, toastActionMinMs: UNDO_MS },
    });
    await advance(49);
    expect(screen.queryByTestId("toast")).toBeNull();
    await advance(2);
    expect(screen.getByTestId("toast")).toHaveTextContent("연결이 원활하지 않아요");
    fireEvent.click(screen.getByTestId("toast-action")); await advance();
    expect(row()).toBeVisible();
    first.resolve(jsonResponse(200, { items: [] })); await advance();
    expect(row()).toBeVisible();
    expect(harness.requests(PATH)).toHaveLength(2);
  });

  it("clears its slow-read toast when the read eventually succeeds", async () => {
    const response = deferred<Response>();
    renderParent(<PracticeScreen />, { handler: () => response.promise, timing: { parentLoadingNoticeDelayMs: 30 } });
    await advance(31);
    expect(screen.getByTestId("toast")).toHaveTextContent("연결이 원활하지 않아요");
    response.resolve(jsonResponse(200, { items: [goal()] })); await advance();
    expect(row()).toBeVisible();
    expect(screen.queryByTestId("toast")).toBeNull();
  });
  it("shows a read error without inventing an empty list, then retries", async () => {
    let reads = 0;
    setup(() => ++reads === 1 ? errorResponse(503, "UPSTREAM_UNAVAILABLE") : jsonResponse(200, { items: [goal()] }));
    await advance();
    expect(screen.getByTestId("toast")).toHaveTextContent("목표를 불러오지 못했어요.");
    expect(screen.queryByText("아직 담은 목표가 없어요")).toBeNull();
    fireEvent.click(screen.getByTestId("toast-action")); await advance();
    expect(row()).toBeVisible();
    expect(reads).toBe(2);
  });

  it("offers an inline retry after the error toast disappears", async () => {
    let reads = 0;
    setup(() => ++reads === 1 ? errorResponse(503, "UPSTREAM_UNAVAILABLE") : jsonResponse(200, { items: [] }));
    await advance(); await advance(UNDO_MS);
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" })); await advance();
    expect(screen.getByText("아직 담은 목표가 없어요")).toBeVisible();
  });

  it("handles 401 with the shared login notice and route (AC1.1.9)", async () => {
    setup(() => errorResponse(401, "UNAUTHENTICATED")); await advance();
    expect(router.replace).toHaveBeenCalledWith("/parent/login");
    expect(screen.getByTestId("toast")).toHaveTextContent("로그인이 필요해요.");
  });

  it("optimistically favorites only that row and serializes rapid duplicate clicks", async () => {
    const response = deferred<Response>();
    const harness = setup((_path, init) => init.method === "PATCH" ? response.promise : jsonResponse(200, { items: GOALS }));
    await advance();
    fireEvent.click(favorite()); fireEvent.click(favorite()); await advance();
    expect(favorite("친구에게 부탁하기", true)).toBeDisabled();
    expect(remove()).toBeDisabled();
    expect(check()).not.toBeChecked();
    expect(harness.requests(`${PATH}/${G1}`).map((r) => r.body)).toEqual([{ isFavorite: true }]);
    response.resolve(jsonResponse(200, goal({ isFavorite: true }))); await advance();
    expect(favorite("친구에게 부탁하기", true)).toBeEnabled();
    expect(screen.getAllByRole("listitem")[0]).toBe(row());
  });

  it("rolls back only the failed favorite while preserving another row's successful update", async () => {
    const response = deferred<Response>();
    setup((path, init) => {
      if (init.method !== "PATCH") return jsonResponse(200, { items: GOALS });
      return path.endsWith(G1) ? response.promise : jsonResponse(200, { ...GOALS[0], isFavorite: true });
    });
    await advance();
    fireEvent.click(favorite());
    fireEvent.click(favorite("차례 기다리기")); await advance();
    response.resolve(errorResponse(400, "VALIDATION_FAILED")); await advance();
    expect(favorite()).toHaveAttribute("aria-pressed", "false");
    expect(favorite("차례 기다리기", true)).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByTestId("toast")).toHaveTextContent("즐겨찾기를 저장하지 못했어요");
  });

  it("retries an unsuccessful favorite and handles authentication failure during mutation", async () => {
    let patches = 0;
    setup((_path, init) => init.method !== "PATCH" ? jsonResponse(200, { items: [goal()] }) : ++patches === 1 ? errorResponse(400, "VALIDATION_FAILED") : errorResponse(401, "UNAUTHENTICATED"));
    await advance();
    fireEvent.click(favorite()); await advance();
    fireEvent.click(screen.getByTestId("toast-action")); await advance();
    expect(patches).toBe(2);
    expect(router.replace).toHaveBeenCalledWith("/parent/login");
  });
});

describe("18 — delayed deletion coordinator (AC4.1.6/11 · J3)", () => {
  it("hides immediately, keeps selection/favorite/order for undo, and sends no DELETE", async () => {
    const harness = setup(); await advance();
    fireEvent.click(check("도움 요청하기"));
    fireEvent.click(remove("도움 요청하기"));
    expect(screen.queryByTestId(`goal-${G3}`)).toBeNull();
    expect(screen.getByTestId("toast-region-polite")).toHaveTextContent("되돌리기");
    await advance(UNDO_MS - 1);
    expect(harness.requests(`${PATH}/${G3}`)).toHaveLength(0);
    fireEvent.click(screen.getByTestId("toast-action"));
    expect(row(G3)).toBeVisible();
    expect(check("도움 요청하기")).toBeChecked();
    expect(favorite("도움 요청하기", true)).toHaveAttribute("aria-pressed", "true");
    expect(screen.getAllByRole("listitem")[0]).toBe(row(G3));
    await advance(UNDO_MS * 2);
    expect(harness.requests(`${PATH}/${G3}`)).toHaveLength(0);
  });

  it("does not send DELETE while undo has focus, and commits once when expired focus leaves", async () => {
    const harness = setup((_path, init) => init.method === "DELETE" ? jsonResponse(200, { restoreUntil: RESTORE_UNTIL }) : jsonResponse(200, { items: GOALS }));
    await advance(); fireEvent.click(remove());
    fireEvent.focus(screen.getByTestId("toast-action")); await advance(UNDO_MS * 10);
    expect(harness.requests(`${PATH}/${G1}`)).toHaveLength(0);
    expect(screen.getByTestId("toast-action")).toBeVisible();
    fireEvent.blur(screen.getByTestId("toast-action")); await advance();
    expect(harness.requests(`${PATH}/${G1}`).map((r) => r.method)).toEqual(["DELETE"]);
    expect(screen.queryByTestId(`goal-${G1}`)).toBeNull();
    await advance(UNDO_MS);
    expect(harness.requests(`${PATH}/${G1}`)).toHaveLength(1);
  });

  it("undo remains valid after the nominal deadline while focused", async () => {
    const harness = setup(); await advance(); fireEvent.click(remove());
    fireEvent.focus(screen.getByTestId("toast-action")); await advance(UNDO_MS * 2);
    fireEvent.click(screen.getByTestId("toast-action")); await advance();
    expect(row()).toBeVisible();
    expect(harness.requests(`${PATH}/${G1}`)).toHaveLength(0);
  });

  it("a second delete cancels the first pending delete and gives the new row its full window", async () => {
    const harness = setup(); await advance(); fireEvent.click(remove()); await advance(UNDO_MS / 2);
    fireEvent.click(remove("차례 기다리기"));
    expect(row()).toBeVisible();
    expect(screen.queryByTestId(`goal-${G2}`)).toBeNull();
    await advance(UNDO_MS / 2);
    expect(harness.requests(`${PATH}/${G1}`)).toHaveLength(0);
    expect(harness.requests(`${PATH}/${G2}`)).toHaveLength(0);
    fireEvent.click(screen.getByTestId("toast-action")); await advance(UNDO_MS);
    expect(row(G2)).toBeVisible();
  });

  it("replacement toast cancels unsent deletion without closing the replacement", async () => {
    const harness = setup(); await advance(); fireEvent.click(remove());
    fireEvent.click(screen.getByRole("button", { name: "새 알림" }));
    expect(row()).toBeVisible();
    expect(screen.getByTestId("toast")).toHaveTextContent("새 알림");
    await advance(UNDO_MS * 2);
    expect(harness.requests(`${PATH}/${G1}`)).toHaveLength(0);
  });

  it.each(["tab", "unmount"])("%s cancels a pending deletion", async (mode) => {
    const harness = setup(); await advance(); fireEvent.click(remove());
    if (mode === "tab") fireEvent.click(screen.getByTestId("tab-home")); else harness.view.unmount();
    await advance(UNDO_MS * 2);
    expect(harness.requests(`${PATH}/${G1}`)).toHaveLength(0);
  });

  it("an explicit delete refusal restores the row and alerts without losing selected/favorite data", async () => {
    setup((_path, init) => init.method === "DELETE" ? errorResponse(403, "ACCOUNT_DELETION_IN_PROGRESS") : jsonResponse(200, { items: GOALS }));
    await advance(); fireEvent.click(check()); fireEvent.click(remove()); await advance(UNDO_MS + 1);
    expect(row()).toBeVisible(); expect(check()).toBeChecked();
    expect(screen.getByTestId("toast-region-assertive")).toHaveTextContent("삭제하지 못해 목표를 되돌렸어요.");
  });

  it("delete 401 restores local state and sends the user to the login route", async () => {
    setup((_path, init) => init.method === "DELETE" ? errorResponse(401, "UNAUTHENTICATED") : jsonResponse(200, { items: GOALS }));
    await advance(); fireEvent.click(remove()); await advance(UNDO_MS + 1);
    expect(router.replace).toHaveBeenCalledWith("/parent/login");
  });
});

describe("18 — unknown DELETE result and bounded compensation", () => {
  it("re-reads an unreceived delete and keeps the existing row without restore POST", async () => {
    const harness = setup((_path, init) => {
      if (init.method === "DELETE") throw new TypeError("offline");
      return jsonResponse(200, { items: [goal()] });
    });
    await advance(); fireEvent.click(remove()); await advance(UNDO_MS + 1);
    expect(row()).toBeVisible();
    expect(harness.requests(PATH)).toHaveLength(2);
    expect(harness.requests(`${PATH}/${G1}/restore`)).toHaveLength(0);
  });

  it("compensates a committed DELETE whose response was lost using the same selection resource", async () => {
    let deleted = false;
    const harness = setup((path, init) => {
      if (init.method === "DELETE") { deleted = true; throw new TypeError("lost response"); }
      if (path.endsWith("/restore")) { deleted = false; return jsonResponse(200, goal()); }
      return jsonResponse(200, { items: deleted ? [] : [goal()] });
    });
    await advance(); fireEvent.click(remove()); await advance(UNDO_MS + 1);
    expect(row()).toBeVisible();
    expect(harness.requests(`${PATH}/${G1}/restore`)).toHaveLength(1);
    expect(remove()).toBeEnabled();
    expect(screen.getByTestId("toast")).toHaveTextContent("삭제하지 못해 목표를 되돌렸어요.");
  });

  it("converges to server deletion after restore expiry and never claims restoration", async () => {
    let deleted = false;
    setup((path, init) => {
      if (init.method === "DELETE") { deleted = true; throw new TypeError("lost"); }
      if (path.endsWith("/restore")) return errorResponse(410, "RESTORE_EXPIRED");
      return jsonResponse(200, { items: deleted ? [] : [goal()] });
    });
    await advance(); fireEvent.click(remove()); await advance(UNDO_MS + 1);
    expect(screen.queryByTestId(`goal-${G1}`)).toBeNull();
    expect(screen.getByText("아직 담은 목표가 없어요")).toBeVisible();
    expect(screen.getByTestId("toast")).toHaveTextContent("되돌릴 수 있는 시간이 지났어요");
    expect(screen.getByTestId("toast")).not.toHaveTextContent("되돌렸어요");
  });

  it("keeps an unverified row disabled, then retries reconciliation rather than sending a second DELETE", async () => {
    let reads = 0;
    const harness = setup((_path, init) => {
      if (init.method === "DELETE") throw new TypeError("offline");
      if (++reads === 2) throw new TypeError("still offline");
      return jsonResponse(200, { items: [goal()] });
    });
    await advance(); fireEvent.click(remove()); await advance(UNDO_MS + 1);
    expect(row()).toBeVisible(); expect(remove()).toBeDisabled();
    expect(screen.getByTestId("toast")).toHaveTextContent("목표 상태를 확인하지 못했어요");
    await advance(UNDO_MS + 1);
    expect(screen.queryByTestId("toast")).toBeNull();
    fireEvent.click(within(screen.getByTestId("practice-recovery")).getByRole("button", { name: "다시 시도" })); await advance();
    expect(remove()).toBeEnabled();
    expect(harness.requests(`${PATH}/${G1}`)).toHaveLength(1);
    expect(reads).toBe(3);
  });
});
