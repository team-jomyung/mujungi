import { act, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { HomeScreen } from "@/parent/features/home/HomeScreen";
import { errorResponse, homeBody, jsonResponse, type NetworkState } from "../../../support/fixtures";
import { renderParent } from "../../../support/parentHarness";
import { resetRouter, router } from "../../../support/nextNavigation";

vi.mock("next/navigation", async () => (await import("../../../support/nextNavigation")).nextNavigationMock);
class FakeEvents extends EventTarget {
  static all: FakeEvents[] = [];
  closed = false;
  onerror: (() => void) | null = null;
  constructor(readonly url: string) { super(); FakeEvents.all.push(this); }
  close() { this.closed = true; }
  event(name: string) { this.dispatchEvent(new Event(name)); }
}
const pause = (ms: number) => act(() => new Promise<void>(resolve => setTimeout(resolve, ms)));
function visibility(value: DocumentVisibilityState) {
  Object.defineProperty(document, "visibilityState", { configurable: true, value });
  document.dispatchEvent(new Event("visibilitychange", { bubbles: true }));
}
beforeEach(() => { resetRouter(); FakeEvents.all = []; vi.stubGlobal("EventSource", FakeEvents); });
afterEach(() => { Reflect.deleteProperty(document, "visibilityState"); vi.unstubAllGlobals(); });

it("SSE ready 뒤에는 짧은 주기 조회를 멈추고 알림으로 상태와 시작 잠금을 함께 갱신한다", async () => {
  let state: NetworkState = "DISCONNECTED";
  const h = renderParent(<HomeScreen />, { timing: { parentPollIntervalMs: 40, homeReconcileIntervalMs: 5000 }, handler: () => jsonResponse(200, homeBody(state)) });
  await screen.findByTestId("home-device-card");
  act(() => FakeEvents.all[0].event("ready"));
  await pause(60);
  const before = h.requests("/api/home").length;
  await pause(160);
  expect(h.requests("/api/home")).toHaveLength(before);
  state = "STABLE";
  act(() => FakeEvents.all[0].event("home-changed"));
  await waitFor(() => expect(screen.getByTestId("home-device-card")).toHaveAttribute("data-state", "stable"));
  expect(screen.getByTestId("tab-free")).not.toHaveAttribute("aria-disabled");
  expect(h.requests("/api/home")).toHaveLength(before + 1);
  expect(FakeEvents.all[0].url).toBe("/api/home/events");
});

it("SSE 오류 때 조회로 복구하고 재연결 ready 뒤 다시 짧은 조회를 멈춘다", async () => {
  const h = renderParent(<HomeScreen />, { timing: { parentPollIntervalMs: 40, homeReconcileIntervalMs: 5000 }, handler: () => jsonResponse(200, homeBody("STABLE")) });
  await screen.findByTestId("home-device-card");
  const events = FakeEvents.all[0];
  act(() => events.event("ready"));
  await pause(60);
  const initial = h.requests("/api/home").length;
  act(() => events.onerror?.());
  await waitFor(() => expect(h.requests("/api/home").length).toBeGreaterThan(initial + 1));
  act(() => events.event("ready"));
  await pause(60);
  const restored = h.requests("/api/home").length;
  await pause(120);
  expect(h.requests("/api/home")).toHaveLength(restored);
});

it("숨김·화면 이탈 시 연결을 닫고 복귀하면 새 연결로 재조회하며 옛 알림은 무시한다", async () => {
  const h = renderParent(<HomeScreen />, { timing: { parentPollIntervalMs: 5000 }, handler: () => jsonResponse(200, homeBody("STABLE")) });
  await screen.findByTestId("home-device-card");
  const old = FakeEvents.all[0];
  act(() => visibility("hidden"));
  expect(old.closed).toBe(true);
  const before = h.requests("/api/home").length;
  act(() => old.event("home-changed"));
  await pause(40);
  expect(h.requests("/api/home")).toHaveLength(before);
  act(() => visibility("visible"));
  expect(FakeEvents.all).toHaveLength(2);
  act(() => FakeEvents.all[1].event("ready"));
  await waitFor(() => expect(h.requests("/api/home").length).toBeGreaterThan(before));
  h.view.unmount();
  expect(FakeEvents.all[1].closed).toBe(true);
});

it("로그인 만료 시 SSE도 닫고 자동 재조회를 멈춘다", async () => {
  let expired = false;
  const h = renderParent(<HomeScreen />, { timing: { parentPollIntervalMs: 40 }, handler: path => path === "/api/home" && expired ? errorResponse(401, "UNAUTHENTICATED") : jsonResponse(200, homeBody("STABLE")) });
  await screen.findByTestId("home-device-card");
  expired = true;
  act(() => FakeEvents.all[0].event("home-changed"));
  await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/parent/login"));
  expect(FakeEvents.all[0].closed).toBe(true);
  const count = h.requests("/api/home").length;
  await pause(120);
  expect(h.requests("/api/home")).toHaveLength(count);
});

it("정상 SSE에서도 유실·시간 경과를 위한 느린 보완 조회를 유지한다", async () => {
  const h = renderParent(<HomeScreen />, { timing: { parentPollIntervalMs: 5000, homeReconcileIntervalMs: 80 }, handler: () => jsonResponse(200, homeBody("STABLE")) });
  await screen.findByTestId("home-device-card");
  act(() => FakeEvents.all[0].event("ready"));
  await waitFor(() => expect(h.requests("/api/home").length).toBeGreaterThan(2));
});

it("조회 진행 중 받은 변경 알림도 기존 응답 뒤에 다시 조회해 놓치지 않는다", async () => {
  let delayed: Promise<Response> | null = null;
  let release: (response: Response) => void = () => undefined;
  let state: NetworkState = "DISCONNECTED";
  renderParent(<HomeScreen />, { timing: { parentPollIntervalMs: 5000 }, handler: path => path === "/api/home" && delayed !== null ? delayed : jsonResponse(200, homeBody(state)) });
  await screen.findByTestId("home-device-card");
  delayed = new Promise<Response>(resolve => { release = resolve; });
  act(() => FakeEvents.all[0].event("home-changed"));
  state = "STABLE";
  act(() => FakeEvents.all[0].event("home-changed"));
  act(() => { const response = jsonResponse(200, homeBody("DISCONNECTED")); delayed = null; release(response); });
  await waitFor(() => expect(screen.getByTestId("home-device-card")).toHaveAttribute("data-state", "stable"));
});
