"use client";

import { QueryClient, useQuery } from "@tanstack/react-query";
import { createContext, useContext } from "react";

export function createAdminQueryClient(): QueryClient {
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

// 제공자별로 모드·미리보기 상태·기준일을 키의 접두사에 넣는다.
export const AdminQueryScope = createContext<readonly string[]>(["admin", "isolated"]);

export function useAdminQuery<T>(key: readonly unknown[], load: (signal: AbortSignal) => Promise<T>, enabled = true) {
  const scope = useContext(AdminQueryScope);
  const query = useQuery({
    queryKey: [...scope, ...key],
    queryFn: ({ signal }) => load(signal),
    enabled,
  });
  return {
    data: query.isError ? null : query.data ?? null,
    error: query.error,
    loading: query.isPending || (query.isError && query.isFetching),
    reload: () => { void query.refetch(); },
  };
}
