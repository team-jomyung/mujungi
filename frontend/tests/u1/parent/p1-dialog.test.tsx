import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ConfirmDialog } from "@/parent/components/ConfirmDialog";
import { Live12 } from "@/parent/screens/Live12";
import { SESSION_ID, deferred, errorResponse, jsonResponse, liveViewBody } from "../support/fixtures";
import { resetRouter, router } from "../support/nextNavigation";
import { renderParent, type ParentHarness } from "../support/parentHarness";

vi.mock("next/navigation", async () => (await import("../support/nextNavigation")).nextNavigationMock);

// P1 끝내기 시험 (U1 3.5 Step 42 · BR6.9 · RD-21 · J111 · J120 · AC3.3.1 · AC3.3.8 · AC3.3.9 · mockups 9-18 · 편차 (w))
// 「아니오」 기본 초점 · Tab 가둠 · Escape 닫힘, 202 → 17, 409 SESSION_NOT_ACTIVE는 상태를 한 번 다시 읽어 종료일 때만 17,
// 503 · 401 · 네트워크는 '끝내기 요청을 보내지 못했어요'(종료 요청됨 아님), 재시도는 같은 requestId.

function DialogHarness({ busy = false, dismissible = true }: { busy?: boolean; dismissible?: boolean }) {
  const [open, setOpen] = useState(false);
  const [confirmed, setConfirmed] = useState(0);
  return (
    <div>
      <button type="button" data-testid="opener" onClick={() => setOpen(true)}>
        열기
      </button>
      <span data-testid="confirmed">{confirmed}</span>
      <ConfirmDialog
        open={open}
        title="정말 끝낼까요?"
        body="설명"
        confirmLabel="네, 끝낼게요"
        cancelLabel="아니오"
        onConfirm={() => setConfirmed((count) => count + 1)}
        onCancel={() => setOpen(false)}
        busy={busy}
        dismissible={dismissible}
        testIdPrefix="p1"
      />
    </div>
  );
}

describe("ConfirmDialog(접근성 O4 · U4)", () => {
  it("열리면 alertdialog이고 안전한 「아니오」에 초점, 닫히면 연 단추로 초점을 돌려준다", async () => {
    const user = userEvent.setup();
    render(<DialogHarness />);
    await user.click(screen.getByTestId("opener"));
    const dialog = screen.getByRole("alertdialog", { name: "정말 끝낼까요?" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(dialog).toHaveAccessibleDescription("설명");
    expect(screen.getByTestId("p1-no")).toHaveFocus();
    await user.click(screen.getByTestId("p1-no"));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(screen.getByTestId("opener")).toHaveFocus();
  });

  it("Tab · Shift+Tab은 팝업 안에서만 돈다", async () => {
    const user = userEvent.setup();
    render(<DialogHarness />);
    await user.click(screen.getByTestId("opener"));
    await user.tab();
    expect(screen.getByTestId("p1-yes")).toHaveFocus();
    await user.tab();
    expect(screen.getByTestId("p1-no")).toHaveFocus();
    await user.tab({ shift: true });
    expect(screen.getByTestId("p1-yes")).toHaveFocus();
  });

  it("Escape · 바깥 누름으로 닫고, 확인을 누르면 확인을 부른다", async () => {
    const user = userEvent.setup();
    render(<DialogHarness />);
    await user.click(screen.getByTestId("opener"));
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("alertdialog")).toBeNull();
    await user.click(screen.getByTestId("opener"));
    fireEvent.click(screen.getByTestId("p1-backdrop"));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    await user.click(screen.getByTestId("opener"));
    fireEvent.click(screen.getByTestId("p1-dialog"));
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    await user.click(screen.getByTestId("p1-yes"));
    expect(screen.getByTestId("confirmed")).toHaveTextContent("1");
  });

  it("요청 중(busy)이면 두 단추가 잠기고 Escape · 바깥 누름으로 닫히지 않는다", async () => {
    const user = userEvent.setup();
    render(<DialogHarness busy />);
    await user.click(screen.getByTestId("opener"));
    expect(screen.getByTestId("p1-yes")).toBeDisabled();
    expect(screen.getByTestId("p1-no")).toBeDisabled();
    fireEvent.keyDown(screen.getByTestId("p1-dialog"), { key: "Escape" });
    fireEvent.click(screen.getByTestId("p1-backdrop"));
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    fireEvent.keyDown(screen.getByTestId("p1-dialog"), { key: "Tab" });
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
  });

  it("dismissible이 아니면 Escape로 닫히지 않는다", async () => {
    const user = userEvent.setup();
    render(<DialogHarness dismissible={false} />);
    await user.click(screen.getByTestId("opener"));
    await user.keyboard("{Escape}");
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
  });
});

describe("Live12 P1 — 끝내기 요청(BR6.9)", () => {
  type EndScript = (requestId: string, index: number) => Response | Promise<Response>;

  function renderActive(end: EndScript, afterEnd: string = "ACTIVE"): ParentHarness {
    let ends = 0;
    return renderParent(<Live12 sessionId={SESSION_ID} />, {
      timing: { parentPollIntervalMs: 40 },
      handler: (path, init) => {
        if (path.endsWith("/end")) {
          ends += 1;
          return end((JSON.parse(String(init.body)) as { requestId: string }).requestId, ends);
        }
        return jsonResponse(200, liveViewBody(ends > 0 ? afterEnd : "ACTIVE"));
      },
    });
  }

  async function openDialog() {
    await waitFor(() => expect(screen.getByTestId("live-end")).toBeEnabled());
    fireEvent.click(screen.getByTestId("live-end"));
    expect(screen.getByRole("alertdialog", { name: "정말 끝낼까요?" })).toBeInTheDocument();
    expect(screen.getByTestId("p1-no")).toHaveFocus();
  }

  function endRequestIds(harness: ParentHarness): string[] {
    return harness.requests(/\/end$/).map((request) => (request.body as { requestId: string }).requestId);
  }

  beforeEach(() => {
    resetRouter();
  });

  it("「아니오」면 12에 그대로 있고 끝내기 요청을 보내지 않는다(AC3.3.1)", async () => {
    const harness = renderActive(() => jsonResponse(202, { status: "CLOSING" }));
    await openDialog();
    fireEvent.click(screen.getByTestId("p1-no"));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(harness.requests(/\/end$/)).toHaveLength(0);
    expect(router.push).not.toHaveBeenCalled();
  });

  it("「네, 끝낼게요」 → CSRF를 실은 POST /end {requestId} → 202면 17로 간다", async () => {
    const harness = renderActive(() => jsonResponse(202, { status: "CLOSING" }));
    await openDialog();
    fireEvent.click(screen.getByTestId("p1-yes"));
    await waitFor(() => expect(router.push).toHaveBeenCalledWith(`/parent/sessions/${SESSION_ID}/done`));
    const [request] = harness.requests(/\/end$/);
    expect(request.path).toBe(`/api/sessions/${SESSION_ID}/end`);
    expect(request.headers["x-csrf-token"]).toBe(harness.csrfToken);
    expect((request.body as { requestId: string }).requestId).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("요청 중에는 두 단추가 잠겨 두 번 보내지 않는다", async () => {
    const pending = deferred<Response>();
    const harness = renderActive(() => pending.promise);
    await openDialog();
    fireEvent.click(screen.getByTestId("p1-yes"));
    await waitFor(() => expect(screen.getByTestId("p1-yes")).toBeDisabled());
    fireEvent.click(screen.getByTestId("p1-yes"));
    pending.resolve(jsonResponse(202, { status: "CLOSING" }));
    await waitFor(() => expect(router.push).toHaveBeenCalledTimes(1));
    expect(harness.requests(/\/end$/)).toHaveLength(1);
  });

  it("네트워크 오류는 '끝내기 요청을 보내지 못했어요'이고 종료 요청됨으로 보이지 않으며 다시 누르면 같은 requestId다(AC3.3.9 · RD-21)", async () => {
    const harness = renderActive((_requestId, index) => {
      if (index === 1) {
        throw new TypeError("offline");
      }
      return jsonResponse(202, { status: "CLOSING" });
    });
    await openDialog();
    fireEvent.click(screen.getByTestId("p1-yes"));
    expect(await screen.findByTestId("toast")).toHaveTextContent("끝내기 요청을 보내지 못했어요. 인터넷을 확인해 주세요.");
    expect(router.push).not.toHaveBeenCalled();
    expect(screen.getByTestId("live-end")).toBeEnabled();
    expect(screen.getByTestId("live-screen")).toHaveAttribute("data-state", "ACTIVE");
    await openDialog();
    fireEvent.click(screen.getByTestId("p1-yes"));
    await waitFor(() => expect(router.push).toHaveBeenCalledWith(`/parent/sessions/${SESSION_ID}/done`));
    const ids = endRequestIds(harness);
    expect(ids).toHaveLength(2);
    expect(ids[1]).toBe(ids[0]);
  });

  it("503 REALTIME_UNAVAILABLE도 같은 안내이고 세션은 ACTIVE로 남는다(AC3.3.9)", async () => {
    renderActive(() => errorResponse(503, "REALTIME_UNAVAILABLE"));
    await openDialog();
    fireEvent.click(screen.getByTestId("p1-yes"));
    expect(await screen.findByTestId("toast")).toHaveTextContent("끝내기 요청을 보내지 못했어요. 인터넷을 확인해 주세요.");
    expect(screen.getByTestId("live-end")).toBeEnabled();
  });

  it("401이면 같은 안내 뒤 01로 간다(U2가 01을 만듦)", async () => {
    renderActive(() => errorResponse(401, "UNAUTHENTICATED"));
    await openDialog();
    fireEvent.click(screen.getByTestId("p1-yes"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
    expect(screen.getByTestId("toast")).toHaveTextContent("끝내기 요청을 보내지 못했어요. 인터넷을 확인해 주세요.");
  });

  it.each([
    ["ENDED", `/parent/sessions/${SESSION_ID}/done`],
    ["ABORTED", `/parent/sessions/${SESSION_ID}/done`],
    ["CLOSING", `/parent/sessions/${SESSION_ID}/done`],
    ["FAILED", "/parent/free?failed=1"],
  ])("409 SESSION_NOT_ACTIVE 뒤 다시 읽은 상태가 %s면 %s로 간다(AC3.3.8 · J120)", async (status, target) => {
    renderActive(() => errorResponse(409, "SESSION_NOT_ACTIVE"), status);
    await openDialog();
    fireEvent.click(screen.getByTestId("p1-yes"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith(target));
    expect(router.push).not.toHaveBeenCalled();
  });

  it("409 뒤 아직 시작 중(PREPARING)이면 12에 머물며 토스트만 보이고 다음 시도는 새 requestId다(J111 · (w))", async () => {
    let preparingOnce = false;
    let ends = 0;
    const harness = renderParent(<Live12 sessionId={SESSION_ID} />, {
      timing: { parentPollIntervalMs: 40 },
      handler: (path) => {
        if (path.endsWith("/end")) {
          ends += 1;
          preparingOnce = ends === 1;
          return ends === 1 ? errorResponse(409, "SESSION_NOT_ACTIVE") : jsonResponse(202, { status: "CLOSING" });
        }
        if (preparingOnce) {
          preparingOnce = false;
          return jsonResponse(200, liveViewBody("PREPARING"));
        }
        return jsonResponse(200, liveViewBody("ACTIVE"));
      },
    });
    await openDialog();
    fireEvent.click(screen.getByTestId("p1-yes"));
    expect(await screen.findByTestId("toast")).toHaveTextContent("아직 시작하는 중이에요. 시작된 뒤 다시 눌러 주세요.");
    expect(router.replace).not.toHaveBeenCalled();
    await openDialog();
    fireEvent.click(screen.getByTestId("p1-yes"));
    await waitFor(() => expect(router.push).toHaveBeenCalledWith(`/parent/sessions/${SESSION_ID}/done`));
    const ids = endRequestIds(harness);
    expect(ids).toHaveLength(2);
    expect(ids[1]).not.toBe(ids[0]);
  });

  it("409 뒤 다시 읽기마저 실패하면 보내지 못했다고 알린다", async () => {
    let failRead = false;
    renderParent(<Live12 sessionId={SESSION_ID} />, {
      timing: { parentPollIntervalMs: 10_000 },
      handler: (path) => {
        if (path.endsWith("/end")) {
          failRead = true;
          return errorResponse(409, "SESSION_NOT_ACTIVE");
        }
        return failRead ? errorResponse(500, "INTERNAL_ERROR") : jsonResponse(200, liveViewBody("ACTIVE"));
      },
    });
    await openDialog();
    fireEvent.click(screen.getByTestId("p1-yes"));
    expect(await screen.findByTestId("toast")).toHaveTextContent("끝내기 요청을 보내지 못했어요. 인터넷을 확인해 주세요.");
  });

  it("404면 '찾을 수 없어요' 뒤 홈으로 간다", async () => {
    renderActive(() => errorResponse(404, "NOT_FOUND"));
    await openDialog();
    fireEvent.click(screen.getByTestId("p1-yes"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent"));
    expect(screen.getByTestId("toast")).toHaveTextContent("찾을 수 없어요.");
  });
});
