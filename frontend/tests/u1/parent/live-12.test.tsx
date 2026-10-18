import { onlineManager } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { parseHome, parseLiveView } from "@/parent/api/types";
import { TranscriptList } from "@/parent/components/TranscriptList";
import { LiveScreen } from "@/parent/features/live/LiveScreen";
import { SESSION_ID, bubble, deferred, errorResponse, homeBody, jsonResponse, liveViewBody } from "../support/fixtures";
import { resetRouter, router } from "../support/nextNavigation";
import { renderParent, type ParentHarness, type RenderParentOptions } from "../support/parentHarness";

vi.mock("next/navigation", async () => (await import("../support/nextNavigation")).nextNavigationMock);

// 12 진행 시험 (U1 3.5 Step 42 · BR6.15 · BR6.16 · PD-12 · J120 · AC1.1.9 · mockups 9-13 · 9-16)
// poll-interval(주입 40ms)마다 sinceSeq=nextSeq로 읽고 seq로 합친다. 숨으면 멈추고 보이면 즉시 1회, 진행 중 요청에 합류,
// 연속 3회 실패 토스트, 끝난 상태 · CLOSING은 17, FAILED는 08 사유 카드, 「이야기 끝내기」는 ACTIVE일 때만.
// 401이면 시험 로그인으로 가고 끝내기 요청은 0건이다(세션은 그대로, BR6.16).

const POLL_MS = 40;
const LIVE_PATH = /^\/api\/sessions\/[^/]+\?sinceSeq=\d+$/;

function renderLive(handler: (sinceSeq: number, index: number) => Response | Promise<Response>, options: Partial<RenderParentOptions> = {}) {
  let index = 0;
  return renderParent(<LiveScreen sessionId={SESSION_ID} />, {
    timing: { parentPollIntervalMs: POLL_MS },
    ...options,
    handler: (path, init) => {
      if (path.endsWith("/end")) {
        return options.handler?.(path, init) ?? errorResponse(500, "INTERNAL_ERROR");
      }
      index += 1;
      return handler(Number(new URL(path, "https://x.invalid").searchParams.get("sinceSeq")), index);
    },
  });
}

function liveCalls(harness: ParentHarness) {
  return harness.requests(LIVE_PATH);
}

function setVisibility(state: DocumentVisibilityState) {
  Object.defineProperty(document, "visibilityState", { configurable: true, value: state });
  window.dispatchEvent(new Event("visibilitychange"));
}

const pause = (ms: number) => act(() => new Promise<void>((resolve) => setTimeout(resolve, ms)));

beforeEach(() => {
  resetRouter();
});

afterEach(() => {
  Reflect.deleteProperty(document, "visibilityState");
  // 오프라인 시험이 바꾼 TanStack 온라인 상태(모듈 단일 객체)를 되돌린다.
  onlineManager.setOnline(true);
});

describe("12 — 폴링과 말풍선(BR6.15 · PD-12)", () => {
  it("sinceSeq는 앞 응답의 nextSeq이고, 같은 seq 조각은 덮어쓰며 seq 순서로 보인다(J23)", async () => {
    const harness = renderLive((sinceSeq) => {
      if (sinceSeq === 0) {
        return jsonResponse(200, liveViewBody("ACTIVE", { items: [bubble({ seq: 1, speaker: "CHILD", text: "공룡" }), bubble({ seq: 2, speaker: "AI", text: "정말", final: false })], nextSeq: 1 }));
      }
      return jsonResponse(200, liveViewBody("ACTIVE", { items: [bubble({ seq: 2, speaker: "AI", text: "정말 멋지다" }), bubble({ seq: 3, speaker: "CHILD", text: "응" })], nextSeq: 3 }));
    });
    await waitFor(() => expect(screen.getAllByTestId("live-bubble")).toHaveLength(3));
    const bubbles = screen.getAllByTestId("live-bubble");
    expect(bubbles.map((node) => node.getAttribute("data-seq"))).toEqual(["1", "2", "3"]);
    expect(bubbles.map((node) => node.getAttribute("data-speaker"))).toEqual(["CHILD", "AI", "CHILD"]);
    expect(bubbles[1]).toHaveTextContent("정말 멋지다");
    expect(bubbles[1]).toHaveAttribute("data-final", "true");
    await waitFor(() => expect(liveCalls(harness).some((call) => call.path.endsWith("sinceSeq=3"))).toBe(true));
    expect(liveCalls(harness)[0].path).toBe(`/api/sessions/${SESSION_ID}?sinceSeq=0`);
    expect(screen.getByTestId("live-transcript")).toHaveAttribute("role", "log");
  });

  it("시작 직후 라벨은 '준비하고 있어요' → '기기가 응답했어요' → ACTIVE면 아이 이름 줄이고 「이야기 끝내기」는 ACTIVE에서만 켜진다", async () => {
    renderLive(
      (_sinceSeq, index) => jsonResponse(200, liveViewBody(index === 1 ? "PREPARING" : index === 2 ? "DEVICE_ACK" : "ACTIVE")),
      { prepare: (queryClient) => queryClient.setQueryData(["home"], parseHome(homeBody("STABLE"))) },
    );
    expect(await screen.findByText("준비하고 있어요")).toBeInTheDocument();
    expect(screen.getByTestId("live-end")).toBeDisabled();
    expect(await screen.findByText("기기가 응답했어요")).toBeInTheDocument();
    expect(screen.getByTestId("live-end")).toBeDisabled();
    expect(await screen.findByText("하늘이 이야기하고 있어요")).toBeInTheDocument();
    expect(screen.getByTestId("live-end")).toBeEnabled();
    expect(screen.getByTestId("live-elapsed")).toHaveTextContent("10:00 시작 · 3분째");
    expect(screen.getByTestId("live-screen")).toHaveAttribute("data-state", "ACTIVE");
  });

  it("아이를 기다리는 중(WAITING_CHILD, U4가 보냄)이면 '무중이 {아이}에게 말을 걸고 있어요'다", async () => {
    renderLive(() => jsonResponse(200, liveViewBody("WAITING_CHILD")), {
      prepare: (queryClient) => queryClient.setQueryData(["home"], parseHome(homeBody("STABLE"))),
    });
    expect(await screen.findByText("무중이 하늘에게 말을 걸고 있어요")).toBeInTheDocument();
    // U4a(AC3.3.8 · C.S-12.11.5): 참여 기다리는 중에도 「이야기 끝내기」가 켜진다(U1은 ACTIVE만이었다)
    expect(screen.getByTestId("live-end")).toBeEnabled();
  });

  it("아이 이름을 모르면(홈 응답 없음) ACTIVE 부제를 생략하고 12에서 홈을 따로 읽지 않는다", async () => {
    const harness = renderLive(() => jsonResponse(200, liveViewBody("ACTIVE")));
    await waitFor(() => expect(screen.getByTestId("live-end")).toBeEnabled());
    expect(screen.queryByTestId("live-status")).toBeNull();
    expect(harness.requests("/api/home")).toHaveLength(0);
  });

  it("stale이면 '기기 상태를 확인할 수 없어요 · 마지막 확인 hh:mm'을 보인다(mockups 9-16)", async () => {
    renderLive(() => jsonResponse(200, liveViewBody("ACTIVE", { stale: true, observedAt: "2026-10-15T01:02:00Z" })));
    expect(await screen.findByTestId("live-stale")).toHaveTextContent("기기 상태를 확인할 수 없어요 · 마지막 확인 10:02");
  });

  it("탭이 숨으면 멈추고 다시 보이면 바로 한 번 읽는다(PD-12)", async () => {
    const harness = renderLive(() => jsonResponse(200, liveViewBody("ACTIVE")));
    await waitFor(() => expect(liveCalls(harness).length).toBeGreaterThanOrEqual(2));
    act(() => setVisibility("hidden"));
    await pause(POLL_MS);
    const whileHidden = liveCalls(harness).length;
    await pause(POLL_MS * 4);
    expect(liveCalls(harness).length).toBe(whileHidden);
    act(() => setVisibility("visible"));
    await waitFor(() => expect(liveCalls(harness).length).toBeGreaterThan(whileHidden));
  });

  it("응답이 늦으면 다음 간격은 진행 중 요청에 합류해 동시에 둘을 보내지 않는다", async () => {
    let inFlight = 0;
    let maxInFlight = 0;
    const harness = renderLive(async () => {
      inFlight += 1;
      maxInFlight = Math.max(maxInFlight, inFlight);
      await new Promise((resolve) => setTimeout(resolve, POLL_MS * 3));
      inFlight -= 1;
      return jsonResponse(200, liveViewBody("ACTIVE"));
    });
    await waitFor(() => expect(liveCalls(harness).length).toBeGreaterThanOrEqual(2), { timeout: 2_000 });
    expect(maxInFlight).toBe(1);
  });
});

describe("12 — 실패 · 이동", () => {
  it("연속 3회 실패하면 '연결이 원활하지 않아요' 토스트를 한 번 보이고 폴링은 계속한다(PD-12)", async () => {
    const harness = renderLive((_sinceSeq, index) => (index <= 4 ? errorResponse(500, "INTERNAL_ERROR") : jsonResponse(200, liveViewBody("ACTIVE"))));
    const toast = await screen.findByTestId("toast");
    expect(toast).toHaveTextContent("연결이 원활하지 않아요");
    expect(liveCalls(harness).length).toBeGreaterThanOrEqual(3);
    expect(screen.getAllByTestId("toast")).toHaveLength(1);
    await waitFor(() => expect(screen.getByTestId("live-end")).toBeEnabled());
    expect(liveCalls(harness).length).toBeGreaterThanOrEqual(5);
  });

  it("브라우저가 오프라인이어도 요청해 실패로 세고 3회면 토스트 + 「다시 시도」, 온라인이 되면 다시 읽어 이어 간다(PD-12 · J361)", async () => {
    let online = true;
    let reads = 0;
    const harness = renderLive(() => {
      if (!online) {
        throw new TypeError("Failed to fetch");
      }
      reads += 1;
      return jsonResponse(200, liveViewBody("ACTIVE", { items: [bubble({ seq: reads, speaker: "CHILD", text: `말 ${reads}` })], nextSeq: reads }));
    });
    await waitFor(() => expect(screen.getByTestId("live-end")).toBeEnabled());
    // 비행기 모드 · 와이파이 꺼짐: 브라우저가 offline 사건을 내고 fetch는 TypeError로 끝난다.
    online = false;
    act(() => window.dispatchEvent(new Event("offline")));
    const callsAtOffline = liveCalls(harness).length;
    const toast = await screen.findByTestId("toast");
    expect(toast).toHaveTextContent("연결이 원활하지 않아요");
    expect(screen.getByTestId("toast-action")).toHaveTextContent("다시 시도");
    expect(liveCalls(harness).length - callsAtOffline).toBeGreaterThanOrEqual(3);
    // 마지막 데이터를 그대로 둔다.
    expect(screen.getByTestId("live-end")).toBeEnabled();
    const bubblesAtOffline = screen.getAllByTestId("live-bubble").length;
    online = true;
    act(() => window.dispatchEvent(new Event("online")));
    await waitFor(() => expect(screen.getAllByTestId("live-bubble").length).toBeGreaterThan(bubblesAtOffline));
    const callsAtRecovery = liveCalls(harness).length;
    await waitFor(() => expect(liveCalls(harness).length).toBeGreaterThan(callsAtRecovery));
  });

  it("실패 토스트의 「다시 시도」는 바로 한 번 읽는다", async () => {
    let fail = true;
    const harness = renderLive(() => (fail ? errorResponse(500, "INTERNAL_ERROR") : jsonResponse(200, liveViewBody("ACTIVE"))));
    await screen.findByTestId("toast");
    fail = false;
    const before = liveCalls(harness).length;
    fireEvent.click(screen.getByTestId("toast-action"));
    await waitFor(() => expect(liveCalls(harness).length).toBeGreaterThan(before));
    await waitFor(() => expect(screen.getByTestId("live-end")).toBeEnabled());
  });

  // U4a(AC3.5.1 · U4a-J12): DECLINED는 17이 아니라 P3 → 04다(tests/u4a/parent/live-12-u4a.test.tsx). 나머지 넷은 U1 그대로
  it.each(["CLOSING", "ENDED", "ENDED_UNCONFIRMED", "ABORTED"])("%s를 받으면 17로 가고 폴링을 멈춘다", async (status) => {
    const harness = renderLive(() => jsonResponse(200, liveViewBody(status)));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith(`/parent/sessions/${SESSION_ID}/done`));
    await pause(POLL_MS * 4);
    expect(liveCalls(harness)).toHaveLength(1);
  });

  it("DECLINED를 받으면 폴링을 멈춘다(이동은 P3 「확인」 뒤 04, U4a)", async () => {
    const harness = renderLive(() => jsonResponse(200, liveViewBody("DECLINED", { endReason: "DECLINED" })));
    expect(await screen.findByTestId("p3-dialog")).toBeInTheDocument();
    await pause(POLL_MS * 4);
    expect(liveCalls(harness)).toHaveLength(1);
    expect(router.replace).not.toHaveBeenCalledWith(`/parent/sessions/${SESSION_ID}/done`);
  });

  it("FAILED를 받으면 08 사유 카드로 간다(J120)", async () => {
    renderLive(() => jsonResponse(200, liveViewBody("FAILED")));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/free?failed=1"));
  });

  it("401이면 안내 토스트 뒤 01로 가고 폴링을 멈추며 끝내기 요청은 보내지 않는다(AC1.1.9 · BR6.16, U2가 01을 만듦)", async () => {
    const harness = renderLive((_sinceSeq, index) => (index === 1 ? jsonResponse(200, liveViewBody("ACTIVE")) : errorResponse(401, "UNAUTHENTICATED")));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
    expect(screen.getByTestId("toast")).toHaveTextContent("로그인이 필요해요.");
    const after = liveCalls(harness).length;
    await pause(POLL_MS * 4);
    act(() => setVisibility("hidden"));
    act(() => setVisibility("visible"));
    await pause(POLL_MS * 2);
    expect(liveCalls(harness).length).toBe(after);
    expect(harness.requests(/\/end$/)).toHaveLength(0);
  });

  it("404(남의 · 없는 세션)면 '찾을 수 없어요' 토스트 뒤 홈으로 간다(AC1.1.8)", async () => {
    renderLive(() => errorResponse(404, "NOT_FOUND"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent"));
    expect(screen.getByTestId("toast")).toHaveTextContent("찾을 수 없어요.");
  });

  it("진행 중에는 자유대화 · 연습 탭을 눌러도 12에 머물고 홈 · 기록은 이동한다", async () => {
    const pending = deferred<Response>();
    renderLive(() => pending.promise);
    for (const tab of ["tab-free", "tab-practice", "tab-home", "tab-records"]) {
      fireEvent.click(screen.getByTestId(tab));
    }
    expect(router.push.mock.calls).toEqual([["/parent"], ["/parent/records"]]);
    expect(screen.getByTestId("live-screen")).toHaveAttribute("data-state", "LOADING");
    pending.resolve(jsonResponse(200, liveViewBody("ACTIVE")));
    await waitFor(() => expect(screen.getByTestId("live-screen")).toHaveAttribute("data-state", "ACTIVE"));
  });
});

describe("TranscriptList — 자동 내림(접근성 P3 · P7)", () => {
  function items(count: number) {
    return parseLiveView(
      liveViewBody("ACTIVE", { items: Array.from({ length: count }, (_, index) => bubble({ seq: index + 1, speaker: index % 2 === 0 ? "CHILD" : "AI", text: `말 ${index + 1}` })) }),
    ).items;
  }

  function sizeList(list: HTMLElement, scrollHeight: number) {
    Object.defineProperty(list, "scrollHeight", { configurable: true, value: scrollHeight });
    Object.defineProperty(list, "clientHeight", { configurable: true, value: 100 });
  }

  it("아래를 보고 있으면 새 말풍선에 맞춰 내리고, 위로 올려 보는 중이면 내리지 않는다", () => {
    const { rerender } = render(<TranscriptList items={items(2)} childName="하늘" />);
    const list = screen.getByTestId("live-transcript");
    sizeList(list, 500);
    rerender(<TranscriptList items={items(3)} childName="하늘" />);
    expect(list.scrollTop).toBe(500);
    list.scrollTop = 0;
    fireEvent.scroll(list);
    sizeList(list, 600);
    rerender(<TranscriptList items={items(4)} childName="하늘" />);
    expect(list.scrollTop).toBe(0);
    list.scrollTop = 470;
    fireEvent.scroll(list);
    sizeList(list, 700);
    rerender(<TranscriptList items={items(5)} childName="하늘" />);
    expect(list.scrollTop).toBe(700);
  });

  it("발화자는 보조기기용 숨김 접두로 전한다(interaction-spec 3.10)", () => {
    render(<TranscriptList items={items(2)} childName="하늘" />);
    const [child, ai] = screen.getAllByTestId("live-bubble");
    expect(child).toHaveTextContent("하늘:말 1");
    expect(ai).toHaveTextContent("무중:말 2");
    expect(screen.getByTestId("live-transcript")).toHaveAttribute("aria-live", "polite");
  });
});
