"use client";

import { useQueryClient } from "@tanstack/react-query";
import { createContext, useContext, useState, type ReactNode } from "react";
import { useTiming } from "@/shared/timing/TimingProvider";
import { guardAdminAccess } from "../access";
import type { AdminMode } from "../config";
import { AdminDataError } from "../data/errors";
import { createHttpAdminSource } from "../data/http";
import type { AdminDataSource, AdminIdentity } from "../data/types";
import type { PreviewState } from "./navigation";
import { useAdminQuery } from "./queries";

interface SessionState {
  source: AdminDataSource | null;
  identity: AdminIdentity | null;
  loading: boolean;
  error: unknown;
  reload: () => void;
}

const SessionContext = createContext<SessionState | null>(null);

export function useAdminSession(): SessionState {
  const session = useContext(SessionContext);
  if (!session) throw new Error("관리자 세션 제공자가 없습니다.");
  return session;
}

export function AdminSession({ mode, previewState, today, children }: {
  mode: AdminMode;
  previewState: PreviewState;
  today: string;
  children: ReactNode;
}) {
  const timing = useTiming();
  const client = useQueryClient();
  const [denial, setDenial] = useState<AdminDataError | null>(null);
  const query = useAdminQuery(["identity"], async (signal) => {
    if (mode === "disabled") throw new AdminDataError("notReady");
    let source: AdminDataSource;
    if (mode === "preview" && process.env.NODE_ENV !== "production") {
      if (previewState === "loading") {
        await new Promise<void>((_, reject) => {
          if (signal.aborted) {
            reject(new AdminDataError("aborted"));
            return;
          }
          signal.addEventListener("abort", () => reject(new AdminDataError("aborted")), { once: true });
        });
      }
      if (["unauthenticated", "forbidden", "network"].includes(previewState)) {
        throw new AdminDataError(previewState as "unauthenticated" | "forbidden" | "network");
      }
      const { createMockAdminSource } = await import("../data/mock");
      source = createMockAdminSource(today, { empty: previewState === "empty" });
    } else {
      source = createHttpAdminSource({ timeoutMs: timing.parentRequestTimeoutMs });
    }
    const identity = await source.me(signal);
    const guarded = guardAdminAccess(source, (error) => {
      // 먼저 전체 조회 영역을 숨긴다. 취소된 이전 요청은 guard에서 이미 제외했다.
      setDenial(error);
      void client.cancelQueries().then(() => client.removeQueries());
    });
    return { source: guarded, identity };
  }, denial === null);
  const value: SessionState = {
    source: denial ? null : query.data?.source ?? null,
    identity: denial ? null : query.data?.identity ?? null,
    loading: denial === null && query.loading,
    error: denial ?? query.error,
    reload: query.reload,
  };
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}
