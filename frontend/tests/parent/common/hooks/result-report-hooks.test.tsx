import { act, cleanup, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { useResultStatusPolling } from "@/parent/common/hooks/useResultStatusPolling";
import { useReportPolling } from "@/parent/features/reports/useReportPolling";
import { renderParent, jsonResponse, status, report, SESSION_ID, REPORT_ID } from "../../../support/resultSupport";
function ResultProbe({ enabled = true }: { enabled?: boolean }) { const result = useResultStatusPolling(SESSION_ID, enabled); return <p>{result.data?.jobStatus ?? "읽는 중"}</p>; }
function ReportProbe({ startedAt }: { startedAt: number }) { const result = useReportPolling(REPORT_ID, startedAt); return <p>{result.expired ? "시한 끝" : result.data?.status ?? "읽는 중"}</p>; }
const advance = (ms: number) => act(async () => { await vi.advanceTimersByTimeAsync(ms); });
beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(new Date("2026-10-15T01:30:00Z")); });
afterEach(() => { cleanup(); vi.useRealTimers(); });
it("2초 주기로 묻고 DONE에서 멈춘다", async () => {
  let calls = 0;
  const h = renderParent(<ResultProbe />, { timing: { parentPollIntervalMs: 2_000 }, handler: () => jsonResponse(200, status(++calls > 1 ? "DONE" : "REQUESTED")) });
  await advance(1);
  expect(screen.getByText("REQUESTED")).toBeInTheDocument();
  await advance(1_998);
  expect(h.requests(/result\/status/)).toHaveLength(1);
  await advance(2);
  expect(screen.getByText("DONE")).toBeInTheDocument();
  await advance(6_000);
  expect(h.requests(/result\/status/)).toHaveLength(2);
});
it.each(["NONE", "FAILED"])("%s는 반복하지 않는다", async jobStatus => {
  const h = renderParent(<ResultProbe />, { handler: () => jsonResponse(200, status(jobStatus)) });
  await advance(1);
  expect(screen.getByText(jobStatus)).toBeInTheDocument();
  await advance(6_000);
  expect(h.requests(/result\/status/)).toHaveLength(1);
});
it("떠나면 폴링을 멈춘다", async () => {
  const h = renderParent(<ResultProbe />, { timing: { parentPollIntervalMs: 2_000 }, handler: () => jsonResponse(200, status("RUNNING")) });
  await advance(2_001);
  expect(h.requests(/result\/status/)).toHaveLength(2);
  h.view.unmount();
  await advance(6_000);
  expect(h.requests(/result\/status/)).toHaveLength(2);
});
it("활성화 전에는 결과를 읽지 않는다", async () => {
  const h = renderParent(<ResultProbe enabled={false} />, { handler: () => jsonResponse(200, status()) });
  await advance(6_000);
  expect(h.requests(/result\/status/)).toHaveLength(0);
});
it("접수 뒤 60초 경계에서 기다리기와 폴링을 끝낸다", async () => {
  const h = renderParent(<ReportProbe startedAt={Date.now()} />, { timing: { reportReadyTargetMs: 60_000, parentPollIntervalMs: 2_000 }, handler: () => jsonResponse(200, report("GENERATING")) });
  await advance(59_999);
  expect(screen.getByText("GENERATING")).toBeInTheDocument();
  await advance(1);
  expect(screen.getByText("시한 끝")).toBeInTheDocument();
  const count = h.requests().length;
  await advance(6_000);
  expect(h.requests()).toHaveLength(count);
});
it("READY 응답은 시한 타이머와 폴링을 해제한다", async () => {
  const h = renderParent(<ReportProbe startedAt={Date.now()} />, { handler: () => jsonResponse(200, report("READY")) });
  await advance(1);
  expect(screen.getByText("READY")).toBeInTheDocument();
  await advance(60_000);
  expect(screen.queryByText("시한 끝")).toBeNull();
  expect(h.requests(`/api/reports/${REPORT_ID}`)).toHaveLength(1);
});
