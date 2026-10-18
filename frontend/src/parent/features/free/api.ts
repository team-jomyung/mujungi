"use client";

import type { ApiClient } from "../../common/api/apiClient";
import { parseStartResult, type FreeTalkContext, type StartResult } from "../../common/api/types";

// 08 자유대화 시작 요청 (U1 3.5 Step 39 · BR6.1 · BR6.2). 서버 호출만 한다(BR2.3).

export interface StartFreeTalkBody {
  readonly idempotencyKey: string;
  readonly context?: FreeTalkContext;
}

/** POST /api/activities/free — 08 시작(같은 키 재요청은 첫 응답 재생, BR6.2). */
export function startFreeTalk(api: ApiClient, body: StartFreeTalkBody): Promise<StartResult> {
  return api.request({ method: "POST", path: "/api/activities/free", body, parse: parseStartResult, csrf: true });
}
