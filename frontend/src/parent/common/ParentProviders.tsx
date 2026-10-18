"use client";

import { QueryClientProvider, type QueryClient } from "@tanstack/react-query";
import { useEffect, useState, type ReactNode } from "react";
import { createApiClient, type ApiClient } from "./api/apiClient";
import { ApiClientContext, createParentQueryClient } from "./api/queries";
import { ToastProvider } from "@/shared/toast/ToastProvider";
import { useTiming } from "@/shared/timing/TimingProvider";

// 보호자 영역 공통 문맥 (U1 3.5 Step 40 · PD-12 · SD-6 · interaction-spec 1절 토스트)
// TanStack Query · API 클라이언트(요청 제한 시간 = mujoong.timing.parent.request-timeout) · 토스트를 레이아웃 수준에 둔다.
// 앱 시작 때 CSRF 토큰을 한 번 받아 둔다(익명이면 401이라 무시하고, 로그인 직후 다시 받는다, SD-6 단계 1).

export interface ParentProvidersProps {
  readonly children: ReactNode;
  /** 시험 대역 주입용. */
  readonly api?: ApiClient;
  readonly queryClient?: QueryClient;
}

export function ParentProviders({ children, api: injectedApi, queryClient: injectedClient }: ParentProvidersProps) {
  const timing = useTiming();
  const [queryClient] = useState<QueryClient>(() => injectedClient ?? createParentQueryClient());
  const [api] = useState<ApiClient>(() => injectedApi ?? createApiClient({ timeoutMs: timing.parentRequestTimeoutMs }));

  useEffect(() => {
    void api.csrf.ensure().catch(() => undefined);
  }, [api]);

  return (
    <QueryClientProvider client={queryClient}>
      <ApiClientContext.Provider value={api}>
        <ToastProvider>{children}</ToastProvider>
      </ApiClientContext.Provider>
    </QueryClientProvider>
  );
}
