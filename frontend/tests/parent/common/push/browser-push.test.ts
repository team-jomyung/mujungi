import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { browserPush } from "@/parent/common/push/browserPush";

const bytes = Uint8Array.from({ length: 65 }, (_, index) => index === 0 ? 4 : index);
const publicKey = btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
const subscription = { endpoint: "https://push.example.test/test", toJSON: () => ({ keys: { p256dh: "key", auth: "auth" } }), unsubscribe: vi.fn(async () => true) };

function install(active: unknown = { state: "activated" }, installing: unknown = null) {
  const pushManager = {
    getSubscription: vi.fn(async () => null),
    subscribe: vi.fn<(options: PushSubscriptionOptionsInit) => Promise<typeof subscription>>(async () => subscription),
  };
  const registration = { active, installing, waiting: null, pushManager };
  const register = vi.fn(async () => registration);
  const getRegistration = vi.fn(async () => registration);
  const container = { register, getRegistration, get ready() { throw new Error("/parent에서 ready를 기다리면 안 됨"); } };
  Object.defineProperty(navigator, "serviceWorker", { configurable: true, value: container });
  return { register, getRegistration, registration, pushManager };
}

beforeEach(() => {
  vi.stubGlobal("isSecureContext", true);
  vi.stubGlobal("PushManager", class {});
  vi.stubGlobal("Notification", { permission: "default", requestPermission: vi.fn(async () => "granted") });
});
afterEach(() => {
  Reflect.deleteProperty(navigator, "serviceWorker");
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("U7 부모 전용 브라우저 구독", () => {
  it("지원·권한 조회는 팝업이나 등록을 만들지 않는다", async () => {
    const bridge = install();
    expect(browserPush.supported()).toBe(true);
    expect(browserPush.permission()).toBe("default");
    await expect(browserPush.getSubscription()).resolves.toBeNull();
    expect(bridge.getRegistration).toHaveBeenCalledWith("/parent/");
    expect(bridge.register).not.toHaveBeenCalled();
    expect(Notification.requestPermission).not.toHaveBeenCalled();
    await expect(browserPush.requestPermission()).resolves.toBe("granted");
  });

  it("보안 맥락·브라우저 API가 없으면 미지원이다", () => {
    install();
    vi.stubGlobal("isSecureContext", false);
    expect(browserPush.supported()).toBe(false);
    vi.stubGlobal("isSecureContext", true);
    Reflect.deleteProperty(navigator, "serviceWorker");
    expect(browserPush.supported()).toBe(false);
  });

  it("부모 scope만 등록하고 URL-safe 공개 키를 바이트로 구독한다(ready 사용 없음)", async () => {
    const bridge = install();
    await expect(browserPush.subscribe(publicKey, 1000)).resolves.toBe(subscription);
    expect(bridge.register).toHaveBeenCalledWith("/parent/push-worker", { scope: "/parent/", updateViaCache: "none" });
    const options = bridge.pushManager.subscribe.mock.calls[0]?.[0] as PushSubscriptionOptionsInit | undefined;
    expect(options?.userVisibleOnly).toBe(true);
    expect(Array.from(options?.applicationServerKey as Uint8Array)).toEqual(Array.from(bytes));
  });

  it("처음 등록은 활성화를 기다린 뒤에 구독한다", async () => {
    const installing = Object.assign(new EventTarget(), { state: "installing" });
    const bridge = install(null, installing);
    const pending = browserPush.subscribe(publicKey, 1000);
    await Promise.resolve();
    expect(bridge.pushManager.subscribe).not.toHaveBeenCalled();
    installing.state = "activated";
    installing.dispatchEvent(new Event("statechange"));
    await expect(pending).resolves.toBe(subscription);
  });

  it("워커 활성화가 끝나지 않으면 제한 시간 뒤 실패한다", async () => {
    vi.useFakeTimers();
    const installing = Object.assign(new EventTarget(), { state: "installing" });
    const bridge = install(null, installing);
    const pending = browserPush.subscribe(publicKey, 20);
    const rejected = expect(pending).rejects.toThrow();
    await vi.advanceTimersByTimeAsync(21);
    await rejected;
    expect(bridge.pushManager.subscribe).not.toHaveBeenCalled();
  });

  it("활성화가 실패하면 구독하지 않는다", async () => {
    const installing = Object.assign(new EventTarget(), { state: "redundant" });
    const bridge = install(null, installing);
    await expect(browserPush.subscribe(publicKey, 1000)).rejects.toThrow();
    expect(bridge.pushManager.subscribe).not.toHaveBeenCalled();
  });

  it("등록된 워커가 없으면 조회는 null이고 새로 등록하지 않는다", async () => {
    const getRegistration = vi.fn(async () => undefined);
    Object.defineProperty(navigator, "serviceWorker", { configurable: true, value: { getRegistration } });
    await expect(browserPush.getSubscription()).resolves.toBeNull();
  });

  it("공개 키가 틀리면 워커를 등록하지 않는다", async () => {
    const bridge = install();
    await expect(browserPush.subscribe("invalid-key", 1000)).rejects.toThrow();
    expect(bridge.register).not.toHaveBeenCalled();
  });

  it("활성·설치·대기 워커가 모두 없으면 실패한다", async () => {
    install(null, null);
    await expect(browserPush.subscribe(publicKey, 1000)).rejects.toThrow();
  });
});
