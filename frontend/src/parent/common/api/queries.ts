"use client";

import { QueryClient } from "@tanstack/react-query";
import { createContext, useContext } from "react";
import type { ApiClient } from "./apiClient";

// 보호자 웹 TanStack Query 설정과 API 클라이언트 문맥 (U1 3.5 Step 39 · PD-12 · 계약 3절)
// 기능별 요청 함수는 각 기능의 api.ts(features/{home,device,free,live,login})로 나뉘었다(단계 2, 3.a). 여기에는 모든 화면이 쓰는 것만 남는다.
// 진입 때 1회 조회가 기본이다(포커스 복귀 · 재연결 재조회 끔, 자동 재시도 없음). 12의 2초 폴링은 features/live/useLivePolling.ts가 한다.
// 브라우저의 온라인 표시와 상관없이 요청을 보내고 실패를 화면이 알린다(networkMode always). 기본값 'online'이면 offline
// 사건 뒤 요청이 멈춘 채(paused) 실패로 세지 않아 토스트 · 「다시 시도」가 뜨지 않는다. 온라인으로 돌아오면
// 12 · 17은 이어지는 폴링이, 04는 「다시 시도」가 다시 읽는다.

export const queryKeys = {
  home: ["home"] as const,
  session: (sessionId: string) => ["session", sessionId] as const,
};

/** 보호자 웹 QueryClient. 실패는 화면이 토스트 + 「다시 시도」로 다룬다(자동 재시도 없음, 오프라인에서도 요청해 실패로 센다). */
export function createParentQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        staleTime: 0,
        networkMode: "always",
        refetchOnWindowFocus: false,
        refetchOnReconnect: false,
      },
      mutations: { retry: false, networkMode: "always" },
    },
  });
}

export const ApiClientContext = createContext<ApiClient | null>(null);

export function useApiClient(): ApiClient {
  const client = useContext(ApiClientContext);
  if (client === null) {
    throw new Error("ApiClientContext가 없다: 보호자 레이아웃이 API 클라이언트를 두지 않았다.");
  }
  return client;
}
