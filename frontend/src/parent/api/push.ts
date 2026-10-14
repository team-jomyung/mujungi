import type { ApiClient } from "./apiClient";
import { ResponseShapeError } from "./types";

// U7 C6 · U7-a/U7-J3: 세션 GET, 쓰기 CSRF, 현재 브라우저 endpoint만 해제한다.
export interface PushConfig {
  readonly enabled: boolean;
  readonly publicKey: string | null;
}

export interface PushSubscriptionBody {
  readonly endpoint: string;
  readonly keys: { readonly p256dh: string; readonly auth: string };
  readonly platform: "WEB";
}

function parseConfig(value: unknown): PushConfig {
  if (typeof value !== "object" || value === null) throw new ResponseShapeError("pushConfig");
  const body = value as Record<string, unknown>;
  if (body.enabled === false && body.publicKey === null) return { enabled: false, publicKey: null };
  if (body.enabled === true && typeof body.publicKey === "string" && body.publicKey.length > 0) {
    return { enabled: true, publicKey: body.publicKey };
  }
  throw new ResponseShapeError("pushConfig");
}

export function fetchPushConfig(api: ApiClient, signal?: AbortSignal): Promise<PushConfig> {
  return api.request({ method: "GET", path: "/api/push-subscription/vapid-public-key", csrf: false, parse: parseConfig, signal });
}

export function putPushSubscription(api: ApiClient, body: PushSubscriptionBody): Promise<void> {
  return api.request({ method: "PUT", path: "/api/push-subscription", body, csrf: true, parse: () => undefined });
}

export function deletePushSubscription(api: ApiClient, endpoint: string): Promise<void> {
  return api.request({ method: "DELETE", path: "/api/push-subscription", body: { endpoint }, csrf: true, parse: () => undefined });
}
