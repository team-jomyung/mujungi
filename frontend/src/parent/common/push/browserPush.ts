// 브라우저 서비스 워커 접근은 보호자 전용 이 모듈에서만 허용한다.
// /parent는 /parent/ scope 밖이다. 전역 ready 대신 반환 registration의 활성화를 기다린다.
export type BrowserSubscription = Pick<PushSubscription, "endpoint" | "toJSON" | "unsubscribe">;

const WORKER_PATH = "/parent/push-worker";
const WORKER_SCOPE = "/parent/";

function supported(): boolean {
  return typeof window !== "undefined" && window.isSecureContext === true
    && "Notification" in window && "PushManager" in window && "serviceWorker" in navigator;
}

function publicKeyBytes(publicKey: string): Uint8Array<ArrayBuffer> {
  const plain = publicKey.replaceAll("-", "+").replaceAll("_", "/");
  const decoded = atob(plain + "=".repeat((4 - plain.length % 4) % 4));
  const bytes = Uint8Array.from(decoded, (character) => character.charCodeAt(0));
  if (bytes.length !== 65 || bytes[0] !== 4) throw new Error("PUSH_PUBLIC_KEY_INVALID");
  return bytes;
}

function waitForActive(registration: ServiceWorkerRegistration, timeoutMs: number): Promise<void> {
  if (registration.active?.state === "activated") return Promise.resolve();
  const worker = registration.installing ?? registration.waiting ?? registration.active;
  if (worker === null) return Promise.reject(new Error("PUSH_WORKER_UNAVAILABLE"));
  return new Promise((resolve, reject) => {
    const finish = (failure?: Error) => {
      clearTimeout(timer);
      worker.removeEventListener("statechange", check);
      if (failure) reject(failure);
      else resolve();
    };
    const check = () => {
      if (worker.state === "activated") finish();
      else if (worker.state === "redundant") finish(new Error("PUSH_WORKER_UNAVAILABLE"));
    };
    const timer = setTimeout(() => finish(new Error("PUSH_WORKER_TIMEOUT")), timeoutMs);
    worker.addEventListener("statechange", check);
    check();
  });
}

export const browserPush = {
  supported,
  permission: (): NotificationPermission => typeof Notification === "undefined" ? "default" : Notification.permission,
  requestPermission: (): Promise<NotificationPermission> => Notification.requestPermission(),
  async getSubscription(): Promise<BrowserSubscription | null> {
    const registration = await navigator.serviceWorker.getRegistration(WORKER_SCOPE);
    return registration ? registration.pushManager.getSubscription() : null;
  },
  async subscribe(publicKey: string, timeoutMs: number): Promise<BrowserSubscription> {
    const applicationServerKey = publicKeyBytes(publicKey);
    const registration = await navigator.serviceWorker.register(WORKER_PATH, { scope: WORKER_SCOPE, updateViaCache: "none" });
    await waitForActive(registration, timeoutMs);
    return registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey });
  },
};
