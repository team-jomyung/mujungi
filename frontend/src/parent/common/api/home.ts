"use client";

import { useQuery } from "@tanstack/react-query";
import type { ApiClient } from "./apiClient";
import { queryKeys, useApiClient } from "./queries";
import { parseHome, type HomeView } from "./types";

// 04 홈 조회 요청 (U1 3.5 Step 39 · PD-12 · 계약 3절). 서버 호출만 한다.

/** GET /api/home — 04 세 구역(U1: child · device · lock). */
export function fetchHome(api: ApiClient, signal?: AbortSignal): Promise<HomeView> {
  return api.request({ method: "GET", path: "/api/home", parse: parseHome, csrf: false, signal });
}

/** 04 · 05 · 12의 홈 조회(진입 때 1회). */
export function useHomeQuery() {
  const api = useApiClient();
  return useQuery({ queryKey: queryKeys.home, queryFn: ({ signal }) => fetchHome(api, signal) });
}
