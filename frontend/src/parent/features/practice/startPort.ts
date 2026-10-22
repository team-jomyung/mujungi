import { ApiError, type ApiClient } from "../../common/api/apiClient";
import { parseStartResult } from "../../common/api/types";
import type { StartPort } from "./practiceStart";
import { defaultTiming } from "@/shared/timing/timing";

// 연습 시작 요청(U5b)을 둔다. 서버 호출과 응답 변환만 한다. 연습 목표 선택 요청은 common/api/practice.ts에 있다.

/** 시작만 별도 시한으로 준비 결과를 기다린다. 응답 불명은 여전히 같은 요청으로 확인한다. */
export function practiceStartPort(api: ApiClient, timeoutMs = defaultTiming().practiceStartRequestTimeoutMs): StartPort {
  return async (body) => {
    try {
      const result = await api.request({ method: "POST", path: "/api/activities/practice", body, parse: parseStartResult, csrf: true, timeoutMs });
      return { kind: "accepted", sessionId: result.sessionId };
    } catch (error) {
      if (!(error instanceof ApiError) || error.kind !== "http" || error.code === "SERVICE_NOT_READY"
          || error.code === "CSRF_TOKEN_INVALID" || ((error.status ?? 0) >= 500 && error.code !== "REALTIME_UNAVAILABLE")) throw error;
      return { kind: "rejected", code: error.isUnauthenticated ? "UNAUTHENTICATED" : error.code };
    }
  };
}
