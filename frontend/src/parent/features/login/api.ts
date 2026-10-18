"use client";

import type { ApiClient } from "../../common/api/apiClient";
import { parseDevLogin, type DevLoginResult } from "../../common/api/types";

// 01 시험 로그인 요청 (U1 3.5 Step 39 · J119). 서버 호출만 한다(BR2.3).

/** POST /api/dev/login — 시험 로그인(CSRF 면제, dev 프로필 + enabled에서만 200, J119). 성공하면 세션이 바뀌어 CSRF를 다시 받는다. */
export async function devLogin(api: ApiClient): Promise<DevLoginResult> {
  const result = await api.request({ method: "POST", path: "/api/dev/login", parse: parseDevLogin, csrf: false });
  api.csrf.clear();
  // 로그인 직후 받아 둔다(SD-6 단계 1). 실패해도 다음 변경 요청 앞에서 다시 받으므로 로그인 결과는 그대로다.
  await api.csrf.ensure().catch(() => undefined);
  return result;
}
