import { ApiError, type ApiClient } from "../api/apiClient";
import { parseStartResult } from "../api/types";
import type { StartPort } from "./practiceStart";

/** 10초 응답 시한은 15초 준비 실패의 증거가 아니다. 준비 중/응답 불명은 조정자가 같은 요청으로 확인한다. */
export function practiceStartPort(api: ApiClient): StartPort {
  return async (body) => {
    try {
      const result = await api.request({ method: "POST", path: "/api/activities/practice", body, parse: parseStartResult, csrf: true });
      return { kind: "accepted", sessionId: result.sessionId };
    } catch (error) {
      if (!(error instanceof ApiError) || error.kind !== "http" || error.code === "SERVICE_NOT_READY"
          || error.code === "CSRF_TOKEN_INVALID" || ((error.status ?? 0) >= 500 && error.code !== "REALTIME_UNAVAILABLE")) throw error;
      return { kind: "rejected", code: error.isUnauthenticated ? "UNAUTHENTICATED" : error.code };
    }
  };
}
