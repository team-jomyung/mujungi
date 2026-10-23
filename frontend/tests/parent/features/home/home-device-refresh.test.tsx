import { focusManager, onlineManager } from "@tanstack/react-query";
import { act, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { HomeScreen } from "@/parent/features/home/HomeScreen";
import { useHomeQuery } from "@/parent/common/api/home";
import { errorResponse, homeBody, jsonResponse, type NetworkState } from "../../../support/fixtures";
import { renderParent } from "../../../support/parentHarness";
import { resetRouter, router } from "../../../support/nextNavigation";

vi.mock("next/navigation", async () => (await import("../../../support/nextNavigation")).nextNavigationMock);

const pause = (ms: number) => act(() => new Promise<void>(resolve => setTimeout(resolve, ms)));

function visibility(state: DocumentVisibilityState) {
  Object.defineProperty(document, "visibilityState", { configurable: true, value: state });
  window.dispatchEvent(new Event("visibilitychange"));
}

beforeEach(() => { resetRouter(); focusManager.setFocused(undefined); });
afterEach(() => {
  Reflect.deleteProperty(document, "visibilityState");
  focusManager.setFocused(undefined);
  onlineManager.setOnline(true);
});

it("기기를 켜고 끄면 상태 카드와 두 시작 탭의 잠금이 함께 자동 갱신된다", async () => {
  let state: NetworkState = "DISCONNECTED";
  renderParent(<HomeScreen />, { timing: { parentPollIntervalMs: 40 }, handler: () => jsonResponse(200, homeBody(state)) });
  await screen.findByTestId("home-device-card");
  for (state of ["STABLE", "UNSTABLE", "UNKNOWN", "DISCONNECTED", "STABLE"] as NetworkState[]) {
    await waitFor(() => expect(screen.getByTestId("home-device-card")).toHaveAttribute("data-state", state.toLowerCase()));
    for (const tab of ["tab-free", "tab-practice"]) {
      if (state === "STABLE") expect(screen.getByTestId(tab)).not.toHaveAttribute("aria-disabled");
      else expect(screen.getByTestId(tab)).toHaveAttribute("aria-disabled", "true");
    }
  }
  expect(router.replace).not.toHaveBeenCalled();
});

it("숨겨진 홈의 주기 조회는 멈추고 보이면 다음 주기를 기다리지 않고 재조회한다", async () => {
  const h = renderParent(<HomeScreen />, { timing: { parentPollIntervalMs: 5000 }, handler: () => jsonResponse(200, homeBody("STABLE")) });
  await screen.findByTestId("home-device-card");
  act(() => visibility("hidden"));
  await pause(100);
  expect(h.requests("/api/home")).toHaveLength(1);
  act(() => visibility("visible"));
  await waitFor(() => expect(h.requests("/api/home")).toHaveLength(2));
});

it("짧은 주기에서도 숨김 상태에서는 요청을 늘리지 않는다", async () => {
  const h = renderParent(<HomeScreen />, { timing: { parentPollIntervalMs: 40 }, handler: () => jsonResponse(200, homeBody("STABLE")) });
  await screen.findByTestId("home-device-card");
  act(() => visibility("hidden"));
  await pause(60);
  const count = h.requests("/api/home").length;
  await pause(160);
  expect(h.requests("/api/home")).toHaveLength(count);
});

it.each([401, 403, 404])("접근 오류 %s 뒤에는 주기·복귀·재연결 조회를 멈춘다", async status => {
  const h = renderParent(<HomeScreen />, { timing: { parentPollIntervalMs: 40 }, handler: path => path === "/api/home" ? errorResponse(status, "UNAUTHENTICATED") : jsonResponse(200, homeBody("STABLE")) });
  await screen.findByTestId("toast");
  act(() => { visibility("hidden"); onlineManager.setOnline(false); });
  act(() => { visibility("visible"); onlineManager.setOnline(true); });
  await pause(160);
  expect(h.requests("/api/home")).toHaveLength(1);
});

it("일시 오류에는 마지막 카드를 유지하고 시작을 잠근 뒤 자동 복구한다", async () => {
  let fail = false;
  const h = renderParent(<HomeScreen />, { timing: { parentPollIntervalMs: 40 }, handler: path => path === "/api/home" && fail ? errorResponse(500, "INTERNAL_ERROR") : jsonResponse(200, homeBody("STABLE")) });
  await screen.findByTestId("home-device-card");
  fail = true;
  await screen.findByTestId("toast");
  expect(screen.getByTestId("home-device-card")).toHaveAttribute("data-state", "stable");
  expect(screen.getByTestId("tab-free")).toHaveAttribute("aria-disabled", "true");
  await waitFor(() => expect(h.requests("/api/home").length).toBeGreaterThan(2));
  fail = false;
  await waitFor(() => expect(screen.getByTestId("tab-free")).not.toHaveAttribute("aria-disabled"));
});

it("홈 이외 화면에서 공유 조회 훅을 쓰면 주기 조회를 추가하지 않는다", async () => {
  function OtherScreen() { const q = useHomeQuery(); return <span>{q.data ? "ready" : "loading"}</span>; }
  const h = renderParent(<OtherScreen />, { timing: { parentPollIntervalMs: 40 }, handler: () => jsonResponse(200, homeBody("STABLE")) });
  await screen.findByText("ready");
  await pause(160);
  expect(h.requests("/api/home")).toHaveLength(1);
  h.view.unmount();
  await pause(80);
  expect(h.requests("/api/home")).toHaveLength(1);
});
