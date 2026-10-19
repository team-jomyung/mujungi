"use client";

import { useQuery } from "@tanstack/react-query";
import type { ApiClient } from "./apiClient";
import { useApiClient } from "./queries";
import { ResponseShapeError } from "./types";

// U2 보호자 계정(로그인 · 계정) REST 형식 · 읽기 · 요청 · 조회 훅 (docs/units/u2/design.md 4절 · contracts/parent-api.yaml U2 경로 · me-response 스키마)
// 동의(약관 · 동의 저장)는 features/consent/api.ts, 아이 · 종료 표현은 common/api/child.ts로 나뉘었다(단계 2, 3.a).
// 받는 쪽 규칙(계약 10절): 모르는 필드는 무시하고 모르는 enum은 'UNKNOWN'으로 읽는다. 필수 필드가 없으면 ResponseShapeError.

/** Me의 필드 형식이라 동의 기능이 아니라 여기에 둔다(공통 → 기능 import를 만들지 않는다). */
export type ConsentStatus = "NOT_GIVEN" | "GIVEN" | "OUTDATED" | "UNKNOWN";

export interface Me {
  readonly guardianId: string;
  readonly consentStatus: ConsentStatus;
  readonly childRegistered: boolean;
  readonly deletionInProgress: boolean;
}

// ───────────── 읽기 ─────────────

type Json = Record<string, unknown>;

// 아래 세 도우미는 동의 · 아이 기능의 api.ts도 같은 형식으로 읽으려고 내보낸다.
export function asObject(value: unknown, field: string): Json {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new ResponseShapeError(field);
  }
  return value as Json;
}

export function requiredString(object: Json, field: string): string {
  const value = object[field];
  if (typeof value !== "string" || value.length === 0) {
    throw new ResponseShapeError(field);
  }
  return value;
}

export function ignoreBody(): void {
  return undefined;
}

export function parseMe(value: unknown): Me {
  const root = asObject(value, "me");
  const status = root.consentStatus;
  if (typeof root.childRegistered !== "boolean") {
    throw new ResponseShapeError("childRegistered");
  }
  return {
    guardianId: requiredString(root, "guardianId"),
    consentStatus: status === "NOT_GIVEN" || status === "GIVEN" || status === "OUTDATED" ? status : "UNKNOWN",
    childRegistered: root.childRegistered,
    deletionInProgress: requiredString(root, "deletionStatus") === "IN_PROGRESS",
  };
}

// ───────────── 요청 ─────────────

/** POST /api/auth/google — 01 인가 코드 교환(CSRF 면제). 성공하면 세션이 바뀌어 CSRF를 다시 받는다. */
export async function loginWithGoogle(api: ApiClient, code: string, redirectUri: string): Promise<Me> {
  const me = await api.request({ method: "POST", path: "/api/auth/google", body: { code, redirectUri }, parse: parseMe, csrf: false });
  api.csrf.clear();
  if (!me.deletionInProgress) {
    await api.csrf.ensure().catch(() => undefined);
  }
  return me;
}

/** POST /api/auth/logout — 지금 세션만 끝낸다(세션 종료 요청 아님, B2). */
export async function logout(api: ApiClient): Promise<void> {
  await api.request({ method: "POST", path: "/api/auth/logout", parse: ignoreBody, csrf: true });
  api.csrf.clear();
}

/** GET /api/me — 01 · 04 분기. */
export function fetchMe(api: ApiClient, signal?: AbortSignal): Promise<Me> {
  return api.request({ method: "GET", path: "/api/me", parse: parseMe, csrf: false, signal });
}

/** DELETE /api/me — P7 계정 삭제 접수(즉시 차단 · 로그아웃). */
export async function requestAccountDeletion(api: ApiClient): Promise<string> {
  const id = await api.request({
    method: "DELETE",
    path: "/api/me",
    parse: (value) => requiredString(asObject(value, "deletion"), "deletionRequestId"),
    csrf: true,
  });
  api.csrf.clear();
  return id;
}

// ───────────── 조회 훅(진입 때 1회) ─────────────

/** 계정 조회 키 모음. 동의 · 아이 기능의 조회 훅도 같은 키 표를 쓴다(여러 화면이 서로의 캐시를 비우므로 한 곳에 둔다). */
export const accountQueryKeys = {
  me: ["me"] as const,
  policy: ["policy"] as const,
  child: ["child"] as const,
  stopExpressions: ["stopExpressions"] as const,
};

export function useMeQuery() {
  const api = useApiClient();
  return useQuery({ queryKey: accountQueryKeys.me, queryFn: ({ signal }) => fetchMe(api, signal) });
}
