"use client";

import { useQuery } from "@tanstack/react-query";
import { ApiError, type ApiClient } from "./apiClient";
import { useTiming } from "@/shared/timing/TimingProvider";
import { queryKeys, useApiClient } from "./queries";
import { parseHome, type HomeView } from "./types";

// 04 홈 조회 요청 (U1 3.5 Step 39 · PD-12 · 계약 3절). 서버 호출만 한다.

/** GET /api/home — 04 세 구역(U1: child · device · lock). */
export function fetchHome(api: ApiClient, signal?: AbortSignal): Promise<HomeView> {
  return api.request({ method: "GET", path: "/api/home", parse: parseHome, csrf: false, signal });
}

/** 홈 화면만 상태를 계속 확인한다. 다른 화면의 진입 조회는 그대로 둔다. */
export function useHomeQuery(poll = false) {
  const api = useApiClient();
  const timing = useTiming();
  return useQuery({
    queryKey: queryKeys.home,
    queryFn: ({ signal }) => fetchHome(api, signal),
    refetchInterval: (query) => poll && !isAccessError(query.state.error) ? timing.parentPollIntervalMs : false,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: (query) => poll && !isAccessError(query.state.error),
    refetchOnReconnect: (query) => poll && !isAccessError(query.state.error),
  });
}

function isAccessError(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 401 || error.status === 403 || error.status === 404);
}
