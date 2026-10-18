"use client";

import { useQuery } from "@tanstack/react-query";
import type { ApiClient } from "../../common/api/apiClient";
import { accountQueryKeys, asObject, ignoreBody, requiredString } from "../../common/api/account";
import { useApiClient } from "../../common/api/queries";
import { ResponseShapeError } from "../../common/api/types";

// 03 아이 정보 · SET-01 종료 표현의 읽기 · 요청 (docs/units/u2/design.md 4절 · contracts/parent-api.yaml U2 경로)
// 받는 쪽 규칙(계약 10절): 모르는 필드는 무시한다. 필수 필드가 없으면 ResponseShapeError.
// 서버 호출만 한다(BR2.3). 이동 · 문구 선택 · 상태 보관은 useChildScreen · useStopExpressionsScreen이 한다.

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

function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
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

export function useChildQuery() {
  const api = useApiClient();
  return useQuery({ queryKey: accountQueryKeys.child, queryFn: ({ signal }) => fetchChild(api, signal) });
}

export function useStopExpressionsQuery() {
  const api = useApiClient();
  return useQuery({ queryKey: accountQueryKeys.stopExpressions, queryFn: ({ signal }) => fetchStopExpressions(api, signal) });
}
