// @vitest-environment node
import vm from "node:vm";
import { describe, expect, it, vi } from "vitest";
import { GET, dynamic } from "@/app/parent/push-worker/route";

const sessionId = "00000000-0000-4000-8000-000000000071";
const origin = "https://app.example.test";
const target = origin + "/parent?notificationSessionId=" + sessionId;
const payload = { type: "ACTIVITY_COMPLETED", activityType: "FREE_TALK", sessionId, sentAt: "2026-10-14T00:00:00Z" };

async function worker(windows: unknown[] = []) {
  const listeners = new Map<string, (event: unknown) => void>();
  const showNotification = vi.fn(async () => undefined);
  const openWindow = vi.fn(async () => null);
  const matchAll = vi.fn(async () => windows);
  const response = GET();
  const source = await response.text();
  vm.runInNewContext(source, { URL, self: {
    location: { origin },
    registration: { showNotification },
    clients: { matchAll, openWindow },
    addEventListener: (name: string, callback: (event: unknown) => void) => listeners.set(name, callback),
  } });
  const dispatch = async (name: string, event: Record<string, unknown>) => {
    const waits: Promise<unknown>[] = [];
    listeners.get(name)?.({ ...event, waitUntil: (value: Promise<unknown>) => waits.push(value) });
    await Promise.all(waits);
  };
  return { response, source, listeners, showNotification, openWindow, matchAll, dispatch };
}

describe("U7 실제 서비스 워커 JavaScript", () => {
  it("Next standalone용 정적 JS이며 fetch·캐시·설치 처리기를 등록하지 않는다", async () => {
    const loaded = await worker();
    expect(dynamic).toBe("force-static");
    expect(loaded.response.headers.get("Content-Type")).toBe("text/javascript; charset=utf-8");
    expect(loaded.response.headers.get("Cache-Control")).toBe("no-cache");
    expect(loaded.response.headers.get("X-Content-Type-Options")).toBe("nosniff");
    expect([...loaded.listeners.keys()].sort()).toEqual(["notificationclick", "push"]);
  });

  it.each([
    ["VOICE_PROBLEM", "FREE_TALK", "무중", "대화 중 음성 연결 문제가 발생했어요."],
    ["DEVICE_STATUS_UNKNOWN", "FREE_TALK", "무중", "기기 상태를 확인할 수 없어요."],
    ["ENDED_BY_CONNECTION", "FREE_TALK", "무중", "연결 문제로 활동이 종료됐어요."],
    ["ACTIVITY_COMPLETED", "FREE_TALK", "자유대화가 끝났어요", "앱에서 최신 상태를 확인해 주세요"],
    ["ACTIVITY_COMPLETED", "PRACTICE", "연습이 끝났어요", "앱에서 최신 상태를 확인해 주세요"],
  ])("%s %s 문구를 고르고 데이터는 네 필드만 남긴다", async (type, activityType, title, body) => {
    const loaded = await worker();
    await loaded.dispatch("push", { data: { json: () => ({ ...payload, type, activityType, childName: "forbidden", transcript: "forbidden", score: 99 }) } });
    expect(loaded.showNotification).toHaveBeenCalledOnce();
    const [actualTitle, options] = loaded.showNotification.mock.calls[0] as unknown as [string, NotificationOptions];
    expect(actualTitle).toBe(title);
    expect(options.body).toBe(body);
    expect(options.data).toEqual({ ...payload, type, activityType });
    expect(options.tag).toBeTruthy();
    expect(JSON.stringify(options)).not.toContain("forbidden");
  });

  it.each([null, {}, { ...payload, type: "UNKNOWN" }, { ...payload, activityType: "UNKNOWN" }, { ...payload, sessionId: "../device" }, { ...payload, sentAt: "bad" }])("손상된 본문 %j는 알림을 만들지 않는다", async (value) => {
    const loaded = await worker();
    await loaded.dispatch("push", { data: { json: () => value } });
    expect(loaded.showNotification).not.toHaveBeenCalled();
  });

  it("본문이 없거나 JSON 읽기가 실패해도 처리기가 실패하지 않는다", async () => {
    const loaded = await worker();
    await loaded.dispatch("push", { data: null });
    await loaded.dispatch("push", { data: { json: () => { throw new Error("malformed"); } } });
    expect(loaded.showNotification).not.toHaveBeenCalled();
  });

  it("같은 origin의 부모 창만 이동·포커스하고 알림을 닫는다", async () => {
    const device = { url: origin + "/device", navigate: vi.fn(), focus: vi.fn() };
    const foreign = { url: "https://else.example.test/parent", navigate: vi.fn(), focus: vi.fn() };
    const parent = { url: origin + "/parent", navigate: vi.fn(), focus: vi.fn(async () => undefined) };
    parent.navigate.mockResolvedValue(parent);
    const loaded = await worker([device, foreign, parent]);
    const close = vi.fn();
    await loaded.dispatch("notificationclick", { notification: { data: payload, close } });
    expect(close).toHaveBeenCalledOnce();
    expect(loaded.matchAll).toHaveBeenCalledWith({ type: "window", includeUncontrolled: true });
    expect(parent.navigate).toHaveBeenCalledWith(target);
    expect(parent.focus).toHaveBeenCalledOnce();
    expect(device.navigate).not.toHaveBeenCalled();
    expect(foreign.navigate).not.toHaveBeenCalled();
    expect(loaded.openWindow).not.toHaveBeenCalled();
  });

  it("부모 창이 없으면 고정 부모 경로를 연다", async () => {
    const loaded = await worker([{ url: origin + "/device" }, { url: origin + "/parent-other" }]);
    await loaded.dispatch("notificationclick", { notification: { data: payload, close: vi.fn() } });
    expect(loaded.openWindow).toHaveBeenCalledWith(target);
  });

  it("변조된 클릭 데이터는 창을 열지 않는다", async () => {
    const loaded = await worker();
    await loaded.dispatch("notificationclick", { notification: { data: { ...payload, sessionId: "https://else.example.test" }, close: vi.fn() } });
    expect(loaded.openWindow).not.toHaveBeenCalled();
  });

  it("닫힌 부모 창의 navigate 실패·null은 새 창으로 복구한다", async () => {
    const loaded = await worker([
      { url: "not a URL" },
      { url: origin + "/parent/free", navigate: vi.fn(async () => { throw new Error("closed"); }) },
      { url: origin + "/parent/records", navigate: vi.fn(async () => null) },
    ]);
    await loaded.dispatch("notificationclick", { notification: { data: payload, close: vi.fn() } });
    expect(loaded.openWindow).toHaveBeenCalledWith(target);
  });

  it("같은 세션·유형 재시도는 sentAt이 달라도 같은 표시 tag를 쓴다", async () => {
    const loaded = await worker();
    await loaded.dispatch("push", { data: { json: () => payload } });
    await loaded.dispatch("push", { data: { json: () => ({ ...payload, sentAt: "2026-10-14T00:00:05Z" }) } });
    const calls = loaded.showNotification.mock.calls as unknown as [string, NotificationOptions][];
    expect(calls[0][1].tag).toBe(calls[1][1].tag);
  });
});
