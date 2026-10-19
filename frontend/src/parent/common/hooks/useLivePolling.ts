"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useState } from "react";
import { ApiError } from "../api/apiClient";
import { queryKeys, useApiClient } from "../api/queries";
import { fetchLiveView, mergeLiveView } from "../api/live";
import type { LiveView, SessionStatus } from "../api/types";
import { useTiming } from "@/shared/timing/TimingProvider";

// 12 진행 폴링 (U1 3.5 Step 40 · PD-12 표 2.9-A · interaction-spec 1절 '폴링')
// - mujoong.timing.parent.poll-interval(2초)마다 GET /api/sessions/{id}?sinceSeq=n. 진행 중 요청이 있으면 다음 간격은 합류한다
//   (TanStack Query가 같은 키의 진행 중 요청을 다시 쓴다).
// - 탭이 숨으면 멈추고(refetchIntervalInBackground false) 보이면 즉시 1회 부른 뒤 다시 돈다(visibilitychange 재조회).
// - 실패해도 마지막 데이터를 두고 폴링을 이어 가며, 연속 실패 횟수를 화면에 넘긴다(3회면 토스트 + 「다시 시도」).
// - 말풍선은 seq로 합치고(확정 전 조각은 같은 seq로 다시 온다), sinceSeq는 앞 응답의 nextSeq다.
// - keepPolling이 false인 상태(종료 · 화면 이동 대상)를 받거나 401이면 멈춘다.

export interface LivePollingOptions {
  /** 이 상태를 받으면 계속 돌지(false면 멈춤). */
  readonly keepPolling: (status: SessionStatus) => boolean;
}

export interface LivePolling {
  readonly view: LiveView | undefined;
  readonly error: unknown;
  /** 연속 실패 횟수(성공하면 0). */
  readonly failureStreak: number;
  readonly refetch: () => void;
}

function isStopError(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 401 || error.status === 404);
}

export function useLivePolling(sessionId: string, options: LivePollingOptions): LivePolling {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const timing = useTiming();
  const [failureStreak, setFailureStreak] = useState(0);
  const { keepPolling } = options;
  const queryKey = queryKeys.session(sessionId);

  const query = useQuery({
    queryKey,
    queryFn: async ({ signal }) => {
      const previous = queryClient.getQueryData<LiveView>(queryKey);
      try {
        const view = await fetchLiveView(api, sessionId, previous?.nextSeq ?? 0, signal);
        setFailureStreak(0);
        return mergeLiveView(previous, view);
      } catch (error) {
        if (!(error instanceof ApiError && error.kind === "aborted")) {
          setFailureStreak((count) => count + 1);
        }
        throw error;
      }
    },
    refetchInterval: (current) => {
      if (isStopError(current.state.error)) {
        return false;
      }
      const data = current.state.data;
      return data === undefined || keepPolling(data.status) ? timing.parentPollIntervalMs : false;
    },
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: (current) => {
      if (isStopError(current.state.error)) {
        return false;
      }
      const data = current.state.data;
      return data === undefined || keepPolling(data.status);
    },
    retry: false,
  });

  const { refetch } = query;
  const refetchView = useCallback(() => {
    void refetch();
  }, [refetch]);
  return { view: query.data, error: query.error, failureStreak, refetch: refetchView };
}
