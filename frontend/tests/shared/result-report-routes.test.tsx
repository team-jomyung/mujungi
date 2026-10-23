import { expect, it, vi } from "vitest";
import ParentRecordPage from "@/app/parent/records/[sessionId]/page";
import ParentReportPage from "@/app/parent/reports/[reportId]/page";
import { PARENT_ROUTES, routeForTab } from "@/parent/common/navigation";
import { defaultTiming, resolveTiming, TimingConfigError } from "@/shared/timing/timing";
import { REPORT_ID, SESSION_ID } from "../support/resultSupport";

vi.mock("next/navigation", () => ({ notFound: () => { throw new Error("NOT_FOUND"); } }));

it("기록과 리포트 URL의 UUID가 화면까지 보존된다", async () => {
  expect((await ParentRecordPage({ params: Promise.resolve({ sessionId: SESSION_ID }) })).props.sessionId).toBe(SESSION_ID);
  expect((await ParentReportPage({ params: Promise.resolve({ reportId: REPORT_ID }) })).props.reportId).toBe(REPORT_ID);
});
it.each(["bad-id", "../../other", ""])("틀린 식별자 %s는 기록과 리포트 모두 404다", async id => {
  await expect(ParentRecordPage({ params: Promise.resolve({ sessionId: id }) })).rejects.toThrow("NOT_FOUND");
  await expect(ParentReportPage({ params: Promise.resolve({ reportId: id }) })).rejects.toThrow("NOT_FOUND");
});
it("기록 탭과 리포트 복귀 경로를 실제 화면 주소로 만든다", () => {
  expect(routeForTab("records")).toBe("/parent/records");
  expect(PARENT_ROUTES.recordsOn("2026-10-15")).toBe("/parent/records?date=2026-10-15");
  expect(PARENT_ROUTES.reportNew()).toBe("/parent/reports/new");
  expect(PARENT_ROUTES.reportNew("2026-10-01", "2026-10-15")).toBe("/parent/reports/new?from=2026-10-01&to=2026-10-15");
  expect(PARENT_ROUTES.recordDetail("a/b")).toBe("/parent/records/a%2Fb");
  expect(PARENT_ROUTES.report("a/b")).toBe("/parent/reports/a%2Fb");
});
it("리포트 시한은 기본 60초이며 런타임 설정을 받는다", () => {
  expect(defaultTiming().reportReadyTargetMs).toBe(360_000);
  expect(resolveTiming({ MUJOONG_TIMING_REPORT_READYTARGET: "75s" }).reportReadyTargetMs).toBe(75_000);
});
it.each(["0s", "invalid-private-value"])("잘못된 리포트 시한 %s는 설정 오류로 거절한다", value => {
  expect(() => resolveTiming({ MUJOONG_TIMING_REPORT_READYTARGET: value })).toThrow(TimingConfigError);
  try { resolveTiming({ MUJOONG_TIMING_REPORT_READYTARGET: value }); }
  catch (error) { expect((error as Error).message).not.toContain(value); }
});
