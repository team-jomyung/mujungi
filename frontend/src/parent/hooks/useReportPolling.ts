"use client";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { fetchReport, reportKey } from "../api/reports";
import { useApiClient } from "../api/queries";
import { useTiming } from "@/shared/timing/TimingProvider";
/** BR11.8: 화면을 떠나거나 취소하면 요청·시한 타이머를 해제한다. */
export function useReportPolling(reportId: string | null, startedAt: number | null) {
  const api = useApiClient(), timing = useTiming(); const [expiredRequest, setExpiredRequest] = useState<string | null>(null);
  const expired = reportId !== null && expiredRequest === `${reportId}:${startedAt}`;
  const query = useQuery({ queryKey: reportKey(reportId ?? ""), queryFn: ({ signal }) => fetchReport(api, reportId!, signal), enabled: reportId !== null && !expired, retry: false,
    refetchInterval: current => reportId !== null && !expired && (current.state.data === undefined || current.state.data.status === "GENERATING") ? timing.parentPollIntervalMs : false });
  const terminal = query.data !== undefined && query.data.status !== "GENERATING";
  useEffect(() => { if (reportId === null || startedAt === null || terminal) return; const timer = setTimeout(() => setExpiredRequest(`${reportId}:${startedAt}`), Math.max(0, timing.reportReadyTargetMs - (Date.now() - startedAt))); return () => clearTimeout(timer); }, [reportId, startedAt, timing.reportReadyTargetMs, terminal]);
  return { ...query, expired };
}
