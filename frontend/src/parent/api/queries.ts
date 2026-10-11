"use client";

import { QueryClient, useQuery } from "@tanstack/react-query";
import { createContext, useContext } from "react";
import type { ApiClient } from "./apiClient";
import {
  parseDevLogin,
  parseEndResult,
  parseHome,
  parseLiveView,
  parseRegisterResult,
  parseStartResult,
  type Bubble,
  type DevLoginResult,
  type EndResult,
  type FreeTalkContext,
  type HomeView,
  type LiveView,
  type RegisterResult,
  type StartResult,
} from "./types";

// 보호자 웹 요청 함수와 TanStack Query 설정 (U1 3.5 Step 39 · PD-12 · 계약 3절 · J119 · J361)
// 진입 때 1회 조회가 기본이다(포커스 복귀 · 재연결 재조회 끔, 자동 재시도 없음). 12의 2초 폴링은 hooks/useLivePolling.ts가 한다.
// 브라우저의 온라인 표시와 상관없이 요청을 보내고 실패를 화면이 알린다(networkMode always, J361). 기본값 'online'이면 offline
// 사건 뒤 요청이 멈춘 채(paused) 실패로 세지 않아 토스트 · 「다시 시도」가 뜨지 않는다(PD-12 · NFR4.16). 온라인으로 돌아오면
// 12 · 17은 이어지는 폴링이, 04는 「다시 시도」가 다시 읽는다.

export const queryKeys = {
  home: ["home"] as const,
  session: (sessionId: string) => ["session", sessionId] as const,
};

/** 보호자 웹 QueryClient. 실패는 화면이 토스트 + 「다시 시도」로 다룬다(자동 재시도 없음, 오프라인에서도 요청해 실패로 센다). */
export function createParentQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        staleTime: 0,
        networkMode: "always",
        refetchOnWindowFocus: false,
        refetchOnReconnect: false,
      },
      mutations: { retry: false, networkMode: "always" },
    },
  });
}

export const ApiClientContext = createContext<ApiClient | null>(null);

export function useApiClient(): ApiClient {
  const client = useContext(ApiClientContext);
  if (client === null) {
    throw new Error("ApiClientContext가 없다: 보호자 레이아웃이 API 클라이언트를 두지 않았다.");
  }
  return client;
}

// ───────────── 요청 ─────────────

/** GET /api/home — 04 세 구역(U1: child · device · lock). */
export function fetchHome(api: ApiClient, signal?: AbortSignal): Promise<HomeView> {
  return api.request({ method: "GET", path: "/api/home", parse: parseHome, csrf: false, signal });
}

/** POST /api/device/register — 05a 코드 등록(CSRF 필수, BR8.8). */
export function registerDevice(api: ApiClient, code: string): Promise<RegisterResult> {
  return api.request({ method: "POST", path: "/api/device/register", body: { code }, parse: parseRegisterResult, csrf: true });
}

export interface StartFreeTalkBody {
  readonly idempotencyKey: string;
  readonly context?: FreeTalkContext;
}

/** POST /api/activities/free — 08 시작(같은 키 재요청은 첫 응답 재생, BR6.2). */
export function startFreeTalk(api: ApiClient, body: StartFreeTalkBody): Promise<StartResult> {
  return api.request({ method: "POST", path: "/api/activities/free", body, parse: parseStartResult, csrf: true });
}

/** GET /api/sessions/{id}?sinceSeq=n — 12 진행 · 17 상태(BR6.15). */
export function fetchLiveView(api: ApiClient, sessionId: string, sinceSeq: number, signal?: AbortSignal): Promise<LiveView> {
  const path = `/api/sessions/${encodeURIComponent(sessionId)}?sinceSeq=${sinceSeq}`;
  return api.request({ method: "GET", path, parse: parseLiveView, csrf: false, signal });
}

/** POST /api/sessions/{id}/end — P1 끝내기(재시도는 같은 requestId, BR6.9 · RD-21). */
export function endSession(api: ApiClient, sessionId: string, requestId: string): Promise<EndResult> {
  const path = `/api/sessions/${encodeURIComponent(sessionId)}/end`;
  return api.request({ method: "POST", path, body: { requestId }, parse: parseEndResult, csrf: true });
}

/** POST /api/dev/login — 시험 로그인(CSRF 면제, dev 프로필 + enabled에서만 200, J119). 성공하면 세션이 바뀌어 CSRF를 다시 받는다. */
export async function devLogin(api: ApiClient): Promise<DevLoginResult> {
  const result = await api.request({ method: "POST", path: "/api/dev/login", parse: parseDevLogin, csrf: false });
  api.csrf.clear();
  // 로그인 직후 받아 둔다(SD-6 단계 1). 실패해도 다음 변경 요청 앞에서 다시 받으므로 로그인 결과는 그대로다.
  await api.csrf.ensure().catch(() => undefined);
  return result;
}

// ───────────── 화면 훅 ─────────────

/** 04 · 12의 홈 조회(진입 때 1회). */
export function useHomeQuery() {
  const api = useApiClient();
  return useQuery({ queryKey: queryKeys.home, queryFn: ({ signal }) => fetchHome(api, signal) });
}

/** 말풍선을 seq로 합친다(같은 seq는 새 값으로 덮어쓴다, 확정 전 조각 J23 · J120). */
export function mergeBubbles(previous: readonly Bubble[], incoming: readonly Bubble[]): Bubble[] {
  if (incoming.length === 0) {
    return [...previous];
  }
  const bySeq = new Map<number, Bubble>();
  for (const bubble of previous) {
    bySeq.set(bubble.seq, bubble);
  }
  for (const bubble of incoming) {
    bySeq.set(bubble.seq, bubble);
  }
  return [...bySeq.values()].sort((left, right) => left.seq - right.seq);
}

/** 폴링 응답을 앞 응답과 합친다: 상태는 새 응답, 말풍선은 seq로 합침, nextSeq는 새 응답 값. */
export function mergeLiveView(previous: LiveView | undefined, next: LiveView): LiveView {
  if (previous === undefined || previous.sessionId !== next.sessionId) {
    return next;
  }
  return { ...next, items: mergeBubbles(previous.items, next.items) };
}
