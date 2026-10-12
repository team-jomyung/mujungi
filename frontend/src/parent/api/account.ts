"use client";

import { useQuery } from "@tanstack/react-query";
import type { ApiClient } from "./apiClient";
import { useApiClient } from "./queries";
import { ResponseShapeError } from "./types";

// U2 보호자 계정 REST 형식 · 읽기 · 요청 · 조회 훅 (docs/units/u2/design.md 4절 · contracts/parent-api.yaml U2 경로 · me-response 등 스키마)
// 받는 쪽 규칙(계약 10절): 모르는 필드는 무시하고 모르는 enum은 'UNKNOWN'으로 읽는다. 필수 필드가 없으면 ResponseShapeError.

export type ConsentStatus = "NOT_GIVEN" | "GIVEN" | "OUTDATED" | "UNKNOWN";
export type ConsentType = "VOICE_AND_TEXT" | "GUARDIAN_VIEW" | "REPORT_SHARING" | "RAW_AUDIO";

/** 화면 순서(02 항목 순서). */
export const CONSENT_TYPES: readonly ConsentType[] = ["VOICE_AND_TEXT", "GUARDIAN_VIEW", "REPORT_SHARING", "RAW_AUDIO"];

export interface Me {
  readonly guardianId: string;
  readonly consentStatus: ConsentStatus;
  readonly childRegistered: boolean;
  readonly deletionInProgress: boolean;
}

export interface PolicySection {
  readonly heading: string;
  readonly body: string;
}

export interface PolicyItem {
  readonly type: ConsentType;
  readonly title: string;
  readonly sections: readonly PolicySection[];
}

export interface Policy {
  readonly policyVersion: string;
  readonly items: readonly PolicyItem[];
}

export interface Child {
  readonly childId: string;
  readonly displayName: string;
  readonly age: number;
  readonly interests: readonly string[];
}

export interface StopExpression {
  readonly id: string;
  readonly text: string;
  readonly sortOrder: number;
}

export interface StopExpressions {
  readonly defaultText: string;
  readonly items: readonly StopExpression[];
}

export interface ChildInput {
  readonly displayName: string;
  readonly age: number;
  readonly interests: readonly string[];
}

// ───────────── 읽기 ─────────────

type Json = Record<string, unknown>;

function asObject(value: unknown, field: string): Json {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new ResponseShapeError(field);
  }
  return value as Json;
}

function requiredString(object: Json, field: string): string {
  const value = object[field];
  if (typeof value !== "string" || value.length === 0) {
    throw new ResponseShapeError(field);
  }
  return value;
}

function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

function isConsentType(value: unknown): value is ConsentType {
  return typeof value === "string" && (CONSENT_TYPES as readonly string[]).includes(value);
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

export function parsePolicy(value: unknown): Policy {
  const root = asObject(value, "policy");
  const items = Array.isArray(root.items) ? root.items : [];
  const parsed: PolicyItem[] = items.flatMap((raw) => {
    const item = asObject(raw, "policy.items");
    if (!isConsentType(item.type)) {
      return [];
    }
    const sections = Array.isArray(item.sections) ? item.sections : [];
    return [
      {
        type: item.type,
        title: requiredString(item, "title"),
        sections: sections.map((section) => {
          const object = asObject(section, "policy.sections");
          return { heading: requiredString(object, "heading"), body: requiredString(object, "body") };
        }),
      },
    ];
  });
  if (CONSENT_TYPES.some((type) => !parsed.some((item) => item.type === type))) {
    throw new ResponseShapeError("policy.items");
  }
  return { policyVersion: requiredString(root, "policyVersion"), items: parsed };
}

export function parseChild(value: unknown): Child {
  const root = asObject(value, "child");
  const age = root.age;
  if (typeof age !== "number" || !Number.isInteger(age)) {
    throw new ResponseShapeError("age");
  }
  return {
    childId: requiredString(root, "childId"),
    displayName: requiredString(root, "displayName"),
    age,
    interests: stringList(root.interests),
  };
}

export function parseStopExpressions(value: unknown): StopExpressions {
  const root = asObject(value, "stopExpressions");
  const items = Array.isArray(root.items) ? root.items : [];
  return {
    defaultText: requiredString(root, "default"),
    items: items
      .map((raw) => {
        const item = asObject(raw, "stopExpressions.items");
        return {
          id: requiredString(item, "id"),
          text: requiredString(item, "text"),
          sortOrder: typeof item.sortOrder === "number" ? item.sortOrder : 0,
        };
      })
      .sort((left, right) => left.sortOrder - right.sortOrder),
  };
}

function ignoreBody(): void {
  return undefined;
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

/** GET /api/policies/current — 02 · 02-1 약관. */
export function fetchPolicy(api: ApiClient, signal?: AbortSignal): Promise<Policy> {
  return api.request({ method: "GET", path: "/api/policies/current", parse: parsePolicy, csrf: false, signal });
}

/** PUT /api/consents — 4항목 모두 동의. */
export function saveConsents(api: ApiClient, policyVersion: string): Promise<void> {
  return api.request({
    method: "PUT",
    path: "/api/consents",
    body: { policyVersion, items: CONSENT_TYPES.map((type) => ({ type, agreed: true })) },
    parse: ignoreBody,
    csrf: true,
  });
}

/** GET /api/child — 03. 아이가 없으면 404 CHILD_NOT_REGISTERED(ApiError). */
export function fetchChild(api: ApiClient, signal?: AbortSignal): Promise<Child> {
  return api.request({ method: "GET", path: "/api/child", parse: parseChild, csrf: false, signal });
}

/** POST /api/child — 03 처음 등록. */
export function registerChild(api: ApiClient, input: ChildInput): Promise<Child> {
  return api.request({ method: "POST", path: "/api/child", body: input, parse: parseChild, csrf: true });
}

/** PATCH /api/child — 03 수정. */
export function updateChild(api: ApiClient, input: ChildInput): Promise<Child> {
  return api.request({ method: "PATCH", path: "/api/child", body: input, parse: parseChild, csrf: true });
}

/** GET /api/child/stop-expressions — 05 · SET-01. */
export function fetchStopExpressions(api: ApiClient, signal?: AbortSignal): Promise<StopExpressions> {
  return api.request({ method: "GET", path: "/api/child/stop-expressions", parse: parseStopExpressions, csrf: false, signal });
}

/** PUT /api/child/stop-expressions — SET-01 「저장」(통째 교체). */
export function saveStopExpressions(api: ApiClient, texts: readonly string[]): Promise<void> {
  return api.request({
    method: "PUT",
    path: "/api/child/stop-expressions",
    body: { items: texts.map((text) => ({ text })) },
    parse: ignoreBody,
    csrf: true,
  });
}

// ───────────── 조회 훅(진입 때 1회) ─────────────

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

export function usePolicyQuery() {
  const api = useApiClient();
  return useQuery({ queryKey: accountQueryKeys.policy, queryFn: ({ signal }) => fetchPolicy(api, signal) });
}

export function useChildQuery() {
  const api = useApiClient();
  return useQuery({ queryKey: accountQueryKeys.child, queryFn: ({ signal }) => fetchChild(api, signal) });
}

export function useStopExpressionsQuery() {
  const api = useApiClient();
  return useQuery({ queryKey: accountQueryKeys.stopExpressions, queryFn: ({ signal }) => fetchStopExpressions(api, signal) });
}
