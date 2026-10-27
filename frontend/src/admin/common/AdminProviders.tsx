"use client";

import { QueryClientProvider } from "@tanstack/react-query";
import { useEffect, useState, type ReactNode } from "react";
import { AdminQueryScope, createAdminQueryClient } from "./queries";

export function AdminProviders({ scope, children }: { scope: readonly string[]; children: ReactNode }) {
  const [client] = useState(createAdminQueryClient);
  useEffect(() => () => {
    // 모드 변경이나 관리자 화면 이탈 후에는 이전 요청과 캐시를 남기지 않는다.
    void client.cancelQueries();
    client.clear();
  }, [client]);
  return (
    <QueryClientProvider client={client}>
      <AdminQueryScope.Provider value={scope}>{children}</AdminQueryScope.Provider>
    </QueryClientProvider>
  );
}
