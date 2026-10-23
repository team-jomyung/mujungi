import type { QueryClient } from "@tanstack/react-query";
import { ApiError, type ApiClient } from "./apiClient";
import { fetchLiveView, mergeLiveView } from "./live";
import type { LiveView } from "./types";

interface Scope {
  generation: number;
  sessionId: string | null;
  sequence: number;
  connectionRevision: number;
  accepted: WeakMap<LiveView, number>;
  clients: Set<QueryClient>;
}
const scopes = new WeakMap<ApiClient, Scope>();
function scopeFor(api: ApiClient): Scope {
  let scope = scopes.get(api);
  if (!scope) {
    scope = { generation: 0, sessionId: null, sequence: 0, connectionRevision: 0, accepted: new WeakMap(), clients: new Set() };
    scopes.set(api, scope);
  }
  return scope;
}

/** 연결 사건은 조회 성공이 아니다. 사건 이전 요청과 사실의 신뢰만 무효화한다. */
export function subscribeLiveConnection(api: ApiClient, listener: () => void): () => void {
  const change = () => {
    const scope = scopeFor(api);
    scope.generation += 1;
    scope.connectionRevision += 1;
    listener();
  };
  window.addEventListener("offline", change);
  window.addEventListener("online", change);
  return () => {
    window.removeEventListener("offline", change);
    window.removeEventListener("online", change);
  };
}

export function liveConnectionRevision(api: ApiClient): number {
  return scopeFor(api).connectionRevision;
}

export function hasCurrentLiveFacts(api: ApiClient, view: LiveView | undefined): boolean {
  const scope = scopeFor(api);
  return view !== undefined && typeof navigator !== "undefined" && navigator.onLine && scope.sessionId === view.sessionId && scope.accepted.get(view) === scope.generation;
}

/** 인증 경계에서 취소를 무시한 응답도 무효화한다. 원문은 저장하지 않는다. */
export function invalidateLiveIdentity(api: ApiClient): void {
  const scope = scopeFor(api);
  scope.generation += 1;
  scope.sessionId = null;
  for (const client of scope.clients) {
    for (const query of client.getQueryCache().findAll({ queryKey: ["session"] })) {
      // 진행 중인 401의 기존 오류 통지는 보존하면서 이전 사실을 즉시 지운다.
      query.setState({ data: undefined, dataUpdatedAt: 0 });
      if (query.getObserversCount() === 0) client.removeQueries({ queryKey: query.queryKey, exact: true });
    }
  }
}

export function leaveLiveSession(api: ApiClient, sessionId: string): void {
  const scope = scopeFor(api);
  if (scope.sessionId === sessionId) { scope.generation += 1; scope.sessionId = null; }
}

/** 세션 GET의 권한 거절은 그 세션만 지운다. 다른 계정/CSRF 정책은 바꾸지 않는다. */
function invalidateDeniedSession(api: ApiClient, sessionId: string): void {
  const scope = scopeFor(api);
  if (scope.sessionId === sessionId) {
    scope.generation += 1;
    scope.sessionId = null;
  }
  for (const client of scope.clients) {
    const key = ["session", sessionId] as const;
    const query = client.getQueryCache().find({ queryKey: key, exact: true });
    if (query === undefined) continue;
    query.setState({ data: undefined, dataUpdatedAt: 0 });
    if (query.getObserversCount() === 0) client.removeQueries({ queryKey: key, exact: true });
  }
}

/** 폴링과 P-01 충돌 재조회가 공유하는 단 하나의 적용 경계. */
export async function readCurrentLiveView(api: ApiClient, client: QueryClient, sessionId: string, signal?: AbortSignal): Promise<LiveView> {
  const scope = scopeFor(api);
  scope.clients.add(client);
  if (scope.sessionId !== sessionId) {
    scope.generation += 1;
    scope.sessionId = sessionId;
    client.removeQueries({ queryKey: ["session"], predicate: (query) => query.queryKey[1] !== sessionId });
  }
  const generation = scope.generation;
  const sequence = ++scope.sequence;
  const key = ["session", sessionId] as const;
  const previous = client.getQueryData<LiveView>(key);
  let next: LiveView;
  try {
    next = await fetchLiveView(api, sessionId, previous?.nextSeq ?? 0, signal);
  } catch (error) {
    if (error instanceof ApiError && error.status === 403) {
      if (signal?.aborted || generation !== scope.generation || scope.sessionId !== sessionId || sequence !== scope.sequence) {
        throw new ApiError("aborted", null, "UNKNOWN", null);
      }
      invalidateDeniedSession(api, sessionId);
    }
    throw error;
  }
  if (signal?.aborted || generation !== scope.generation || scope.sessionId !== sessionId || sequence !== scope.sequence) {
    throw new ApiError("aborted", null, "UNKNOWN", null);
  }
  const current = client.getQueryData<LiveView>(key);
  const merged = mergeLiveView(current, next);
  const applied = client.setQueryData<LiveView>(key, merged) ?? merged;
  // Query의 구조 공유로 같은 객체가 반환돼도 새 유효 응답은 신뢰할 수 있다.
  // 낮은 상태판을 버리고 반환한 이전 캐시는 새 성공의 근거가 아니다.
  if (current === undefined || next.statusVersion >= current.statusVersion) scope.accepted.set(applied, generation);
  return applied;
}
