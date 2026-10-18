"use client";
import { useQuery } from "@tanstack/react-query";
import { ApiError } from "../api/apiClient";
import { useApiClient } from "../api/queries";
import { fetchResultStatus, resultStatusKey } from "../api/results";
import { useTiming } from "@/shared/timing/TimingProvider";
/** BR11.2: 요청 실패 뒤에도 다음 주기에 묻되 끝 상태·접근 거절은 멈춘다. */
export function useResultStatusPolling(sessionId: string, enabled = true) {
  const api = useApiClient(), timing = useTiming();
  return useQuery({ queryKey: resultStatusKey(sessionId), queryFn: ({ signal }) => fetchResultStatus(api, sessionId, signal), enabled, retry: false,
    refetchInterval: query => { if (!enabled || (query.state.error instanceof ApiError && [401, 403, 404].includes(query.state.error.status ?? 0))) return false; const status = query.state.data?.jobStatus; return status === undefined || status === "REQUESTED" || status === "RUNNING" ? timing.parentPollIntervalMs : false; }, refetchIntervalInBackground: false });
}
