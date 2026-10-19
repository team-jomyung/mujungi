"use client";

import { useQuery } from "@tanstack/react-query";
import type { ApiClient } from "../../common/api/apiClient";
import { accountQueryKeys, asObject, ignoreBody, requiredString } from "../../common/api/account";
import { useApiClient } from "../../common/api/queries";
import { ResponseShapeError } from "../../common/api/types";

// 02 동의 안내의 약관 읽기 · 동의 저장 요청 (docs/units/u2/design.md 4절 · contracts/parent-api.yaml U2 경로)
// 받는 쪽 규칙(계약 10절): 모르는 필드는 무시하고 모르는 enum은 건너뛴다. 필수 필드가 없으면 ResponseShapeError.
// 서버 호출만 한다. 이동 · 문구 선택 · 상태 보관은 useConsentScreen이 한다.

export type ConsentType = "VOICE_AND_TEXT" | "GUARDIAN_VIEW" | "REPORT_SHARING" | "RAW_AUDIO";

/** 화면 순서(02 항목 순서). */
export const CONSENT_TYPES: readonly ConsentType[] = ["VOICE_AND_TEXT", "GUARDIAN_VIEW", "REPORT_SHARING", "RAW_AUDIO"];

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

function isConsentType(value: unknown): value is ConsentType {
  return typeof value === "string" && (CONSENT_TYPES as readonly string[]).includes(value);
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

export function usePolicyQuery() {
  const api = useApiClient();
  return useQuery({ queryKey: accountQueryKeys.policy, queryFn: ({ signal }) => fetchPolicy(api, signal) });
}
