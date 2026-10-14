import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { parseHome } from "@/parent/api/types";
import { NoticeDialog } from "@/parent/components/NoticeDialog";
import { Done17 } from "@/parent/screens/Done17";
import { Free08 } from "@/parent/screens/Free08";
import { Home04 } from "@/parent/screens/Home04";
import { Live12 } from "@/parent/screens/Live12";
import { SESSION_ID, bubble, errorResponse, homeBody, jsonResponse, liveViewBody } from "../../u1/support/fixtures";
import { resetRouter, router } from "../../u1/support/nextNavigation";
import { renderParent, type RequestHandler } from "../../u1/support/parentHarness";
import { meBody } from "../../u2/support/accountFixtures";

vi.mock("next/navigation", async () => (await import("../../u1/support/nextNavigation")).nextNavigationMock);

// U4a 화면 시험 (docs/units/u4a/design.md 6절 · AC1.4.7 · AC1.4.8 · AC1.4.10 · AC3.2.8 · AC3.2.10 · AC3.2.12 · AC3.3.8 · AC3.4.1 ·
// AC3.5.1 · AC3.6.9 · AC3.4.3 · U4a-J12 ~ J17)

const POLL_MS = 40;
const ENDED_AT = "2026-10-15T01:21:00Z";

function liveHandler(body: Record<string, unknown>, extra: Record<string, RequestHandler> = {}): RequestHandler {
  return (path, init) => {
    const override = extra[`${init.method ?? "GET"} ${path.split("?")[0]}`];
    if (override !== undefined) {
      return override(path, init);
    }
    return jsonResponse(200, body);
  };
}

function renderLive(body: Record<string, unknown>, extra: Record<string, RequestHandler> = {}) {
  return renderParent(<Live12 sessionId={SESSION_ID} />, {
    timing: { parentPollIntervalMs: POLL_MS },
    handler: liveHandler(body, extra),
    prepare: (queryClient) => queryClient.setQueryData(["home"], parseHome(homeBody("STABLE"))),
  });
}

beforeEach(() => {
  resetRouter();
});

describe("12 — 끊김 · 복구 중 · 공백 경고 · 오프라인(AC3.2.8 · AC3.2.10 · AC3.2.12)", () => {
  it("끊김 구간은 말풍선 사이 점선 줄, 진행 중 구간은 '대화 연결을 다시 잇고 있어요'", async () => {
    renderLive(
      liveViewBody("ACTIVE", {
        items: [
          { ...bubble({ seq: 1, speaker: "AI", text: "안녕" }), startedAt: "2026-10-15T01:30:00Z" },
          { ...bubble({ seq: 2, speaker: "CHILD", text: "블록 쌓았어" }), startedAt: "2026-10-15T01:35:00Z" },
        ],
        nextSeq: 2,
        gaps: [
          { from: "2026-10-15T01:33:00Z", to: "2026-10-15T01:34:00Z", kind: "DISCONNECT" },
          { from: "2026-10-15T01:36:00Z", to: null, kind: "DISCONNECT" },
        ],
      }),
    );
    const gaps = await screen.findAllByTestId("live-gap");
    expect(gaps.map((node) => node.textContent)).toEqual(["10:33 ~ 10:34 잠깐 끊김", "대화 연결을 다시 잇고 있어요"]);
    expect(gaps[1]).toHaveAttribute("data-open", "true");
    const order = Array.from(screen.getByTestId("live-transcript").children).map((node) => node.getAttribute("data-testid"));
    expect(order).toEqual(["live-bubble", "live-gap", "live-bubble", "live-gap"]);
    expect(screen.queryByTestId("live-recovering")).toBeNull();
  });

  it("기술적 일시 정지만 있으면 복구 중 줄을 따로 보인다", async () => {
    renderLive(liveViewBody("ACTIVE", { technicalPause: { kind: "DEVICE", since: "2026-10-15T01:02:20Z" } }));
    expect(await screen.findByTestId("live-recovering")).toHaveTextContent("대화 연결을 다시 잇고 있어요");
  });

  it("공백 경고면 경과 줄에 'n분째 말이 없어요'와 경고 카드", async () => {
    renderLive(
      liveViewBody("ACTIVE", {
        serverTime: "2026-10-15T01:09:00Z",
        silenceWarning: { silentSince: "2026-10-15T01:05:00Z", endsAt: "2026-10-15T01:11:00Z" },
      }),
    );
    expect(await screen.findByTestId("live-silence-warning")).toHaveTextContent("2분 더 말이 없으면 활동이 끝나요");
    expect(screen.getByTestId("live-silence-warning")).toHaveTextContent("아이가 다시 말하면 그대로 이어져요.");
    expect(screen.getByTestId("live-elapsed")).toHaveTextContent("10:00 시작 · 10분째 · 4분째 말이 없어요");
  });

  it("응답 없음으로 폴링이 실패하면 오프라인 줄과 마지막으로 받은 시각", async () => {
    let fail = false;
    renderParent(<Live12 sessionId={SESSION_ID} />, {
      timing: { parentPollIntervalMs: POLL_MS },
      handler: () => {
        if (fail) {
          throw new TypeError("Failed to fetch");
        }
        fail = true;
        return jsonResponse(200, liveViewBody("ACTIVE", { serverTime: "2026-10-15T01:32:00Z" }));
      },
    });
    expect(await screen.findByTestId("live-offline")).toHaveTextContent("실시간 상태를 불러오지 못하고 있어요 · 마지막 확인 10:32");
  });
});

describe("12 — 끝내기 · P2 · P3 · 실패(AC3.3.8 · AC3.4.1 · AC3.5.1 · U4a-J12 · J16)", () => {
  it("WAITING_CHILD에서도 「이야기 끝내기」 → P1 → 202면 17", async () => {
    renderLive(liveViewBody("WAITING_CHILD"), {
      "POST /api/session/csrf": () => jsonResponse(200, { token: "t", headerName: "X-CSRF-TOKEN" }),
      "GET /api/session/csrf": () => jsonResponse(200, { token: "t", headerName: "X-CSRF-TOKEN" }),
      [`POST /api/sessions/${SESSION_ID}/end`]: () => jsonResponse(202, { status: "CLOSING" }),
    });
    await waitFor(() => expect(screen.getByTestId("live-end")).toBeEnabled());
    fireEvent.click(screen.getByTestId("live-end"));
    fireEvent.click(await screen.findByTestId("p1-yes"));
    await waitFor(() => expect(router.push).toHaveBeenCalledWith(`/parent/sessions/${SESSION_ID}/done`));
  });

  it("ABORTED · DISCONNECT면 P2 '대화가 예상치 못하게 끝났어요' → 「확인」 → 17", async () => {
    renderLive(liveViewBody("ABORTED", { endReason: "DISCONNECT", endedAt: ENDED_AT }));
    const dialog = await screen.findByTestId("p2-dialog");
    expect(dialog).toHaveTextContent("대화가 예상치 못하게 끝났어요");
    expect(screen.getByTestId("p2-confirm")).toHaveFocus();
    expect(router.replace).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId("p2-confirm"));
    expect(router.replace).toHaveBeenCalledWith(`/parent/sessions/${SESSION_ID}/done`);
  });

  it("DECLINED면 P3 '아이가 대답하지 않아 시작하지 못했어요' → 「확인」 → 04", async () => {
    renderLive(liveViewBody("DECLINED", { endReason: "DECLINED" }));
    expect(await screen.findByTestId("p3-dialog")).toHaveTextContent("아이가 대답하지 않아 시작하지 못했어요");
    fireEvent.click(screen.getByTestId("p3-confirm"));
    expect(router.replace).toHaveBeenCalledWith("/parent");
  });

  it("진행 중 서버 재시작 FAILED는 17, 공급자 실패는 08 사유 코드와 함께", async () => {
    const first = renderLive(liveViewBody("FAILED", { endReason: "FAILED", failureCode: "SERVER_RESTART" }));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith(`/parent/sessions/${SESSION_ID}/done`));
    first.view.unmount();
    resetRouter();
    renderLive(liveViewBody("FAILED", { endReason: "FAILED", failureCode: "PROVIDER_UNAVAILABLE", startedAt: null }));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/free?failed=1&reason=PROVIDER_UNAVAILABLE"));
  });
});

describe("17 — 사유 표 · 정리 중 · 미확인 줄(AC3.6.9 · AC3.4.3 · AC3.6.10)", () => {
  function renderDone(body: Record<string, unknown>, resultStatus: () => string = () => "REQUESTED") {
    return renderParent(<Done17 sessionId={SESSION_ID} />, {
      timing: { parentPollIntervalMs: POLL_MS },
      handler: path => jsonResponse(200, path.endsWith("/result/status") ? { jobStatus: resultStatus(), sessionStatus: body.status, confirmed: body.confirmed } : body),
      prepare: (queryClient) => queryClient.setQueryData(["home"], parseHome(homeBody("STABLE"))),
    });
  }

  it("CHILD_STOP은 '하늘이 끝내자고 했어요' + 기본 표현 · 시각, 정리 중 줄", async () => {
    let jobStatus = "REQUESTED";
    renderDone(liveViewBody("ENDED", { endReason: "CHILD_STOP", confirmed: true, endedAt: ENDED_AT }), () => jobStatus);
    expect(await screen.findByText("하늘이 끝내자고 했어요")).toBeInTheDocument();
    expect(screen.getByTestId("done-reason")).toHaveTextContent("\"무중아 그만할래\" · 10:21");
    expect(screen.getByTestId("done-cleaning")).toHaveTextContent("기록을 정리하고 있어요");
    expect(screen.queryByTestId("done-unconfirmed")).toBeNull();
    expect(screen.queryByText(/기록 보기/)).toBeNull();
    jobStatus = "DONE";
    expect(await screen.findByTestId("done-view-record")).toHaveTextContent("오늘 기록 보기");
  });

  it("SILENCE는 자동 종료 사유(F1-a 문구)", async () => {
    renderDone(liveViewBody("ENDED", { endReason: "SILENCE", confirmed: true, endedAt: ENDED_AT }));
    expect(await screen.findByText("말이 없어 자동으로 끝났어요")).toBeInTheDocument();
    expect(screen.getByTestId("done-reason")).toHaveTextContent("공백 6분 · 6분 동안 말소리가 없어 활동을 끝냈어요.");
  });

  it("서버 재시작 FAILED는 08로 가지 않고 '기기 응답 없이 끝냈어요' + 미확인 줄", async () => {
    renderDone(liveViewBody("FAILED", { endReason: "FAILED", failureCode: "SERVER_RESTART", endedAt: ENDED_AT }));
    expect(await screen.findByText("기기 응답 없이 끝냈어요")).toBeInTheDocument();
    expect(screen.getByTestId("done-unconfirmed")).toHaveTextContent("기기 종료 확인 · 확인 안 됨");
    expect(router.replace).not.toHaveBeenCalled();
  });

  it("공급자 실패 FAILED는 08 사유 코드와 함께", async () => {
    renderDone(liveViewBody("FAILED", { failureCode: "PROVIDER_UNAVAILABLE" }));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/free?failed=1&reason=PROVIDER_UNAVAILABLE"));
  });
});

describe("04 — 지금 활동(AC1.4.7 · AC1.4.8 · AC1.4.10 · AC1.4.13)", () => {
  function homeHandler(current: Record<string, unknown> | null, overrides: Record<string, RequestHandler> = {}): RequestHandler {
    return (path, init) => {
      const key = `${init.method ?? "GET"} ${path}`;
      const override = overrides[key];
      if (override !== undefined) {
        return override(path, init);
      }
      if (key === "GET /api/home") {
        return jsonResponse(200, { ...homeBody("STABLE"), currentSession: current });
      }
      if (key === "GET /api/me") {
        return jsonResponse(200, meBody());
      }
      return errorResponse(404, "NOT_FOUND");
    };
  }

  const active = { sessionId: SESSION_ID, activityType: "FREE_TALK", status: "ACTIVE", confirmed: false, requestedAt: "2026-10-15T00:59:58Z", startedAt: "2026-10-15T01:00:00Z" };

  it("진행 중이면 '{아이} · 자유대화 n분째' · 'hh:mm 시작' · 「진행 화면 보기 ›」 → 12, 탭도 12", async () => {
    renderParent(<Home04 />, { handler: homeHandler(active) });
    const card = await screen.findByTestId("home-activity-card");
    expect(card).toHaveAttribute("data-state", "live");
    expect(screen.getByTestId("home-activity-status")).toHaveTextContent(/^하늘 · 자유대화 \d+분째$/);
    expect(card).toHaveTextContent("10:00 시작");
    fireEvent.click(screen.getByTestId("home-activity-open"));
    expect(router.push).toHaveBeenLastCalledWith(`/parent/sessions/${SESSION_ID}`);
    fireEvent.click(screen.getByTestId("tab-free"));
    expect(router.push).toHaveBeenLastCalledWith(`/parent/sessions/${SESSION_ID}`);
    fireEvent.click(screen.getByTestId("tab-practice"));
    expect(router.push).toHaveBeenLastCalledWith(`/parent/sessions/${SESSION_ID}`);
    fireEvent.click(screen.getByTestId("tab-records"));
    expect(router.push).toHaveBeenLastCalledWith("/parent/records");
  });

  it("CLOSING이면 '종료 처리 중' → 17", async () => {
    renderParent(<Home04 />, { handler: homeHandler({ ...active, status: "CLOSING", endReason: "GUARDIAN_STOP" }) });
    expect(await screen.findByTestId("home-activity-status")).toHaveTextContent("하늘 · 자유대화 종료 처리 중");
    fireEvent.click(screen.getByTestId("home-activity-open"));
    expect(router.push).toHaveBeenLastCalledWith(`/parent/sessions/${SESSION_ID}/done`);
  });

  it("조회 실패면 미확인 칸 · 「다시 확인」만 보이고, 다시 확인이 성공하면 실제 상태로", async () => {
    let fail = true;
    renderParent(<Home04 />, {
      handler: homeHandler(active, { "GET /api/home": () => (fail ? errorResponse(500, "INTERNAL_ERROR") : jsonResponse(200, { ...homeBody("STABLE"), currentSession: active })) }),
    });
    const card = await screen.findByTestId("home-activity-card");
    expect(card).toHaveAttribute("data-state", "unknown");
    expect(screen.getByTestId("home-activity-status")).toHaveTextContent("지금 상태를 불러오지 못했어요");
    expect(screen.getByTestId("home-activity-checked")).toHaveTextContent("마지막 확인");
    expect(card).not.toHaveTextContent("진행 중인 활동 없음");
    fail = false;
    fireEvent.click(screen.getByTestId("home-activity-recheck"));
    await waitFor(() => expect(screen.getByTestId("home-activity-card")).toHaveAttribute("data-state", "live"));
  });
});

describe("08 · P 팝업", () => {
  it("12에서 돌아온 공급자 실패는 그 사유 문구", () => {
    renderParent(<Free08 returnedFailed failureCode="PROVIDER_UNAVAILABLE" />, { handler: () => jsonResponse(202, { sessionId: SESSION_ID }) });
    expect(screen.getByTestId("free-reason")).toHaveTextContent("지금은 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요.");
  });

  it("알림 팝업은 닫히지 않고 Tab이 「확인」에 머문다", () => {
    const onConfirm = vi.fn();
    const { rerender } = render(<NoticeDialog open title="제목" confirmLabel="확인" onConfirm={onConfirm} testIdPrefix="p9" />);
    const confirm = screen.getByTestId("p9-confirm");
    expect(confirm).toHaveFocus();
    fireEvent.keyDown(screen.getByTestId("p9-dialog"), { key: "Tab" });
    fireEvent.keyDown(screen.getByTestId("p9-dialog"), { key: "Escape" });
    fireEvent.click(screen.getByTestId("p9-backdrop"));
    expect(confirm).toHaveFocus();
    expect(onConfirm).not.toHaveBeenCalled();
    fireEvent.click(confirm);
    expect(onConfirm).toHaveBeenCalledTimes(1);
    rerender(<NoticeDialog open={false} title="제목" confirmLabel="확인" onConfirm={onConfirm} testIdPrefix="p9" />);
    expect(screen.queryByTestId("p9-dialog")).toBeNull();
  });
});
