"use client";

import type { ApiClient } from "../../common/api/apiClient";
import { parseRegisterResult, type RegisterResult } from "../../common/api/types";

// 05a 기기 등록 요청 (U1 3.5 Step 39). 서버 호출만 한다.

/** POST /api/device/register — 05a 코드 등록(CSRF 필수). */
export function registerDevice(api: ApiClient, code: string): Promise<RegisterResult> {
  return api.request({ method: "POST", path: "/api/device/register", body: { code }, parse: parseRegisterResult, csrf: true });
}
