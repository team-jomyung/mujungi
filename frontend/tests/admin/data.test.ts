import { afterEach, describe, expect, it, vi } from "vitest";
import {
  dateInRange,
  koreaDate,
  MAX_RANGE_DAYS,
  recentRange,
  validRange,
} from "@/admin/data/dates";
import { createHttpAdminSource } from "@/admin/data/http";
import { createMockAdminSource } from "@/admin/data/mock";
import { parseActivity, parseCosts, parseOverview, parseQuality } from "@/admin/data/parsers";
import type { AdminFilter } from "@/admin/data/types";
const today = "2026-10-26";
const filter: AdminFilter = {
  ...recentRange(30, today),
  type: "ALL"
};
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllEnvs();
});
describe("관리자 합성 데이터", () => {
  it("금액 필드와 합성 음성 계산 예시는 USD 단위를 사용한다", async () => {
    const source = createMockAdminSource(today);
    const detail = await source.costDetail("COST-MJ-1092");
    const voice = detail.phases.find((phase) => phase.phase === "VOICE");
    expect(voice?.voiceSeconds).toBe(246);
    expect(voice?.estimatedUsd).toBe("0.205");
    expect(voice?.calculation).toContain("합성 예시");
    expect(voice?.calculation).not.toContain("환율");
    expect(detail.estimatedUsd).toBe("0.2074");
    expect((await source.costDetail("COST-OTHER-01")).estimatedUsd).toBe("0.0009");
  });
  it("기간·대화 유형·아이·보호자 필터를 적용하고 페이지가 겹치지 않는다", async () => {
    const source = createMockAdminSource(today);
    const first = await source.activities(filter, 0);
    const second = await source.activities(filter, 1);
    expect(first.total).toBe(42);
    expect(first.items).toHaveLength(8);
    expect(second.items.every(item => !first.items.some(other => other.id === item.id))).toBe(true);
    const timestamps = [...first.items, ...second.items].map(item => Date.parse(item.startedAt));
    expect(timestamps).toEqual([...timestamps].sort((a, b) => b - a));
    expect((await source.overview(filter)).recentActivities).toEqual(first.items.slice(0, 5));
    const result = await source.activities({
      ...filter,
      from: today,
      to: today,
      type: "PRACTICE",
      childId: "C-002",
      guardianId: "G-001"
    }, 0);
    expect(result.total).toBe(1);
    expect(result.items[0]).toMatchObject({
      type: "PRACTICE",
      childId: "C-002",
      guardianId: "G-001",
      status: "FAILED_TO_START"
    });
    expect((await source.activities({
      ...filter,
      query: "no-such-child"
    }, 0)).total).toBe(0);
  });
  it("미확인 사용량과 확인된 캐시 0을 구분하고 USD 소계가 합계와 맞는다", async () => {
    const source = createMockAdminSource(today);
    const unknown = await source.costDetail("COST-MJ-1094");
    expect(unknown.status).toBe("UNKNOWN");
    expect(unknown.estimatedUsd).toBeNull();
    expect(unknown.inputTokens).toBeNull();
    const known = await source.costDetail("COST-OTHER-01");
    expect(known.phases[0].cachedInputTokens).toBe(0);
    const costs = await source.costs(filter, 0);
    expect(costs.totals.unknownCount).toBeGreaterThan(0);
    expect(costs.otherCosts.find(item => item.id === "COST-UNLINKED-01")?.estimatedUsd).toBeNull();
    expect(costs.totals.nonActivityUsd).toBeNull();
    expect(Number(costs.totals.collectedUsd)).toBeCloseTo(
      Number(costs.totals.activityUsd ?? "0") + Number(costs.totals.failedStartUsd ?? "0")
        + Number(costs.totals.unlinkedUsd ?? "0"),
      12,
    );
    const timestamps = costs.activities.items.map(item => Date.parse(item.observedAt));
    expect(timestamps).toEqual([...timestamps].sort((a, b) => b - a));
  });
  it("미확인 건수는 목록 항목 수가 아니라 계산하지 못한 호출 수다", async () => {
    const source = createMockAdminSource(today);
    const first = await source.costs(filter, 0);
    const pages = await Promise.all(Array.from(
      { length: Math.ceil(first.activities.total / first.activities.pageSize) },
      (_, page) => source.costs(filter, page),
    ));
    const items = [...pages.flatMap(page => page.activities.items), ...first.otherCosts];
    const details = await Promise.all(items.map(item => source.costDetail(item.id)));
    const unmeasuredCalls = details.flatMap(detail => detail.phases)
      .filter(phase => phase.estimatedUsd === null)
      .reduce((total, phase) => total + phase.calls, 0);
    expect(first.totals.unknownCount).toBe(unmeasuredCalls);
    expect(first.totals.unknownCount).toBeGreaterThan(items.filter(item => item.estimatedUsd === null).length);
  });
  it("미수집 활동은 활동 시각으로 목록에 표시하고 금액과 토큰을 미확인으로 유지한다", async () => {
    const source = createMockAdminSource(today);
    const detail = await source.costDetail("COST-MJ-1058");
    const activity = await source.activity("MJ-1058");
    expect(detail.status).toBe("NOT_COLLECTED");
    expect(detail.estimatedUsd).toBeNull();
    expect(detail.phases).toEqual([]);
    const result = await source.costs({ ...filter, from: "2026-10-14", to: "2026-10-14" }, 0);
    expect(result.activities.total).toBe(3);
    expect(result.activities.items).toContainEqual(expect.objectContaining({
      id: detail.id,
      activityId: activity.id,
      observedAt: activity.startedAt,
      status: "NOT_COLLECTED",
      estimatedUsd: null,
      inputTokens: null,
      outputTokens: null,
      voiceSeconds: null,
    }));
    expect(result.activities.items.every(item => item.status === "NOT_COLLECTED" && item.estimatedUsd === null)).toBe(true);
    expect(result.totals.collectedUsd).toBeNull();
    expect(result.totals.activityUsd).toBeNull();
    expect(result.totals.unknownCount).toBe(0);
  });
  it("활동 행 없는 시작 실패 비용은 대화 외 목록에 두고 활동 필터 중에는 숨긴다", async () => {
    const source = createMockAdminSource(today);
    const all = await source.costs(filter, 0);
    const preparation = all.otherCosts.filter(item => item.category === "FAILED_TO_START");
    expect(preparation.length).toBeGreaterThan(0);
    expect(preparation.every(item => item.activityId === null && item.childName === null && item.type === null)).toBe(true);
    expect(all.activities.items.every(item => item.activityId !== null)).toBe(true);
    expect(all.otherCosts.every(item => item.category !== "NON_ACTIVITY")).toBe(true);
    expect(all.totals.nonActivityUsd).toBeNull();
    expect((await source.costs({ ...filter, from: today, to: today }, 0)).otherCosts.length).toBeGreaterThan(0);
    for (const condition of [
      { type: "PRACTICE" as const },
      { guardianId: "G-001" },
      { childId: "C-002" },
      { deviceId: "D-202" },
      { status: "INTERRUPTED" as const },
      { query: "아이" },
    ]) {
      expect((await source.costs({ ...filter, ...condition }, 0)).otherCosts).toEqual([]);
    }
  });
  it("기기 필터와 품질 목록 전체 수를 유지하고 미래 날짜는 거절한다", async () => {
    const source = createMockAdminSource(today);
    const result = await source.activities({ ...filter, deviceId: "D-202" }, 0);
    expect(result.total).toBeGreaterThan(0);
    expect(result.items.every(item => item.deviceId === "D-202")).toBe(true);
    expect((await source.activities({ ...filter, deviceId: "missing-device" }, 0)).total).toBe(0);
    const quality = await source.quality(filter);
    expect(quality.recordsTotal).toBe((await source.activities({ ...filter, type: "PRACTICE" }, 0)).total);
    expect(quality.recordsLimit).toBe(100);
    expect(quality.records.length).toBe(Math.min(quality.recordsTotal, quality.recordsLimit));
    expect(quality.records.flatMap(record => record.scenes).some(scene => scene.retryCount === null)).toBe(true);
    expect(validRange(today, "2026-10-27", today)).toBe(false);
    expect(() => source.overview({ ...filter, to: "2026-10-27" })).toThrow(RangeError);
  });
  it("명시적인 수락과 거절만 수락률의 분모로 사용한다", async () => {
    const quality = await createMockAdminSource(today).quality(filter);
    const { accepted, declined, rate, requested, unclear, unanswered, notOffered, unknown } = quality.acceptance;
    expect(rate).toBe(accepted / (accepted + declined));
    expect(requested + unclear + notOffered + unknown).toBeGreaterThan(0);
    expect(unanswered).toBeNull();
    expect(quality.focusExamples).toBeLessThanOrEqual(quality.helpTypes.find(item => item.kind === "FOCUS_HELP")!.count);
    const answers = quality.records.flatMap(record => record.scenes.map(scene => scene.helpResponse));
    expect({
      accepted,
      declined,
      requested,
      unclear,
      notOffered,
      unknown
    }).toEqual({
      accepted: answers.filter(answer => answer === "ACCEPT").length,
      declined: answers.filter(answer => answer === "DECLINE").length,
      requested: answers.filter(answer => answer === "REQUESTED").length,
      unclear: answers.filter(answer => answer === "UNCLEAR").length,
      notOffered: quality.records.flatMap(record => record.scenes).filter(scene => scene.helpOffer === "NOT_OFFERED").length,
      unknown: quality.records.flatMap(record => record.scenes).filter(scene => scene.helpOffer === "UNKNOWN").length
    });
    expect(quality.records.flatMap(record => record.scenes).some(scene => scene.helpResponse === "DECLINE" && scene.helpRecords.length === 0)).toBe(true);
  });
  it("선택한 기간의 활동이 없는 날짜도 일별 차트에 유지한다", async () => {
    const overview = await createMockAdminSource(today).overview(filter);
    expect(overview.daily).toHaveLength(30);
    expect(overview.daily[0]).toEqual({
      date: filter.from,
      freeTalk: 0,
      practice: 0
    });
    expect(overview.daily.at(-1)?.date).toBe(today);
    expect(() => createMockAdminSource(today).overview({
      ...filter,
      from: "0001-01-01"
    })).toThrow(RangeError);
  });
  it("중첩 상세까지 관리자 응답 계약과 일치한다", async () => {
    const source = createMockAdminSource(today);
    const activity = await source.activity("MJ-1094");
    expect(parseActivity(activity)).toEqual(activity);
    expect(parseOverview(await source.overview(filter))).toBeDefined();
    expect(parseQuality(await source.quality(filter))).toBeDefined();
    expect(parseCosts(await source.costs(filter, 0))).toBeDefined();
  });
  it("빈 결과의 평균·비용은 미확인이며 운영 미리보기는 거부한다", async () => {
    const source = createMockAdminSource(today, {
      empty: true
    });
    expect((await source.overview(filter)).averageSeconds.all).toBeNull();
    expect((await source.activities(filter, 0)).items).toEqual([]);
    expect((await source.costs(filter, 0)).totals.collectedUsd).toBeNull();
    expect((await source.me()).role).toBe("ADMIN");
    vi.stubEnv("NODE_ENV", "production");
    expect(() => createMockAdminSource(today)).toThrow(expect.objectContaining({
      kind: "notReady"
    }));
  });
  it("취소한 미리보기 요청은 완료하지 않는다", async () => {
    const controller = new AbortController();
    const pending = createMockAdminSource(today).activities(filter, 0, controller.signal);
    controller.abort();
    await expect(pending).rejects.toMatchObject({
      kind: "aborted"
    });
  });
});
describe("관리자 HTTP 경계", () => {
  it("알려진 오류 코드를 읽고 본문 추적 ID를 헤더보다 우선한다", async () => {
    const fetchImpl = vi.fn(async () => Response.json({
      code: "RATE_LIMITED",
      message: "서버 원시 문장",
      traceId: "body-trace"
    }, {
      status: 429,
      headers: {
        "X-Trace-Id": "header-trace"
      }
    }));
    await expect(createHttpAdminSource({
      timeoutMs: 1000,
      fetchImpl
    }).me()).rejects.toMatchObject({
      kind: "http",
      status: 429,
      code: "RATE_LIMITED",
      traceId: "body-trace"
    });
    await expect(createHttpAdminSource({
      timeoutMs: 1000,
      fetchImpl
    }).me()).rejects.not.toHaveProperty("message", "서버 원시 문장");
  });
  it.each([401, 403])("모르는 코드여도 상태 %s와 헤더 추적 ID를 보존한다", async (status) => {
    const fetchImpl = vi.fn(async () => Response.json({
      code: "FUTURE_ADMIN_CODE",
      message: "서버 원시 문장"
    }, {
      status,
      headers: {
        "X-Trace-Id": "header-trace"
      }
    }));
    await expect(createHttpAdminSource({
      timeoutMs: 1000,
      fetchImpl
    }).me()).rejects.toMatchObject({
      kind: status === 401 ? "unauthenticated" : "forbidden",
      status,
      code: "UNKNOWN",
      traceId: "header-trace"
    });
  });
  it("오류 본문이 JSON이 아니어도 헤더 추적 ID를 읽는다", async () => {
    const fetchImpl = vi.fn(async () => new Response("<html>오류</html>", {
      status: 503,
      headers: {
        "X-Trace-Id": "header-only"
      }
    }));
    await expect(createHttpAdminSource({
      timeoutMs: 1000,
      fetchImpl
    }).me()).rejects.toMatchObject({
      kind: "http",
      status: 503,
      code: "UNKNOWN",
      traceId: "header-only"
    });
  });
  it.each([401, 403])("HTML 오류 본문이어도 인증 상태 %s를 유지하고 합성 데이터로 대체하지 않는다", async (status) => {
    const fetchImpl = vi.fn(async () => new Response("not json", {
      status
    }));
    await expect(createHttpAdminSource({
      timeoutMs: 1000,
      fetchImpl
    }).me()).rejects.toMatchObject({
      kind: status === 401 ? "unauthenticated" : "forbidden",
      status
    });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });
  it("동일 출처 쿠키·저장 금지·한국 시간과 인코딩한 필터를 전달한다", async () => {
    const fetchImpl = vi.fn(async () => Response.json({
      items: [],
      page: 0,
      pageSize: 8,
      total: 0
    }));
    await createHttpAdminSource({
      timeoutMs: 1000,
      fetchImpl
    }).activities({
      ...filter,
      query: "아이 & test",
      type: "PRACTICE",
      deviceId: "00000000-0000-4000-8000-000000000003"
    }, 0);
    const [url, init] = fetchImpl.mock.calls[0] as unknown as [
      string,
      RequestInit
    ];
    const parsed = new URL(url, "https://localhost");
    expect(parsed.pathname).toBe("/api/admin/activities");
    expect(parsed.searchParams.get("query")).toBe("아이 & test");
    expect(parsed.searchParams.get("timezone")).toBe("Asia/Seoul");
    expect(parsed.searchParams.get("deviceId")).toBe("00000000-0000-4000-8000-000000000003");
    expect(init).toMatchObject({
      method: "GET",
      credentials: "same-origin",
      cache: "no-store"
    });
  });
  it("잘못된 중첩 응답을 거부하고 누락 값을 0으로 바꾸지 않는다", async () => {
    const payload = await createMockAdminSource(today).costs(filter, 0);
    const fetchImpl = vi.fn(async () => Response.json({
      ...payload,
      activities: {
        ...payload.activities,
        items: [{
            ...payload.activities.items[0],
            estimatedUsd: 0
          }]
      }
    }));
    await expect(createHttpAdminSource({
      timeoutMs: 1000,
      fetchImpl
    }).costs(filter, 0)).rejects.toMatchObject({
      kind: "invalidResponse"
    });
    expect(() => parseActivity({
      ...awaitedActivity(),
      transcript: [{
          sequence: 1,
          speaker: "SYSTEM",
          text: "bad",
          at: today
        }]
    })).toThrow();
  });
  it("이미 취소된 요청은 네트워크 호출을 시작하지 않는다", async () => {
    const fetchImpl = vi.fn();
    const controller = new AbortController();
    controller.abort();
    await expect(createHttpAdminSource({
      timeoutMs: 1000,
      fetchImpl
    }).me(controller.signal)).rejects.toMatchObject({
      kind: "aborted"
    });
    expect(fetchImpl).not.toHaveBeenCalled();
  });
  it("조회 기간 상한을 넘으면 네트워크 호출 전에 거부한다", () => {
    const fetchImpl = vi.fn();
    expect(() => createHttpAdminSource({
      timeoutMs: 1000,
      fetchImpl
    }).overview({
      ...filter,
      from: "0001-01-01"
    })).toThrow(RangeError);
    expect(fetchImpl).not.toHaveBeenCalled();
  });
  it("설정된 제한 시간이 지나면 요청을 취소하고 자동 재시도하지 않는다", async () => {
    vi.useFakeTimers();
    const fetchImpl = vi.fn((_url: string, init: RequestInit) => new Promise<Response>((_resolve, reject) => init.signal?.addEventListener("abort", () => reject(new Error("aborted")))));
    const pending = createHttpAdminSource({
      timeoutMs: 1500,
      fetchImpl
    }).me();
    const assertion = expect(pending).rejects.toMatchObject({
      kind: "timeout"
    });
    await vi.advanceTimersByTimeAsync(1500);
    await assertion;
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });
});
function awaitedActivity() {
  return {
    id: "id",
    guardianId: "g",
    guardianName: "g",
    deviceId: "d",
    childId: "c",
    childName: "c",
    type: "FREE_TALK",
    status: "COMPLETED",
    endCategory: "ENDED",
    startedAt: `${today}T00:00:00Z`,
    endedAt: null,
    durationSeconds: null,
    childTurns: null,
    aiTurns: null,
    endReason: "",
    transcriptAvailable: true,
    scenes: []
  };
}
describe("한국 시각 날짜 기준", () => {
  it("양 끝 날짜를 포함하는 한국 날짜를 사용한다", () => {
    expect(koreaDate(new Date("2026-10-25T15:00:00Z"))).toBe(today);
    expect(dateInRange("2026-10-25T14:59:59Z", today, today)).toBe(false);
    expect(dateInRange("2026-10-26T14:59:59Z", today, today)).toBe(true);
    expect(dateInRange("2026-10-26T15:00:00Z", today, today)).toBe(false);
  });
  it("없는 날짜·역순·미래 날짜를 거부한다", () => {
    expect(validRange("2026-02-30", today, today)).toBe(false);
    expect(validRange(today, "2026-10-25", today)).toBe(false);
    expect(validRange(today, "2026-10-27", today)).toBe(false);
    expect(recentRange(7, today)).toEqual({
      from: "2026-10-20",
      to: today
    });
  });
  it("윤년을 포함하여 최대 366일의 날짜를 조회한다", () => {
    expect(MAX_RANGE_DAYS).toBe(366);
    expect(validRange("2025-10-27", today, today)).toBe(true); // 365일
    expect(validRange("2025-10-26", today, today)).toBe(true); // 366일
    expect(validRange("2025-10-25", today, today)).toBe(false); // 367일
    expect(validRange("2024-01-01", "2024-12-31", today)).toBe(true);
  });
});
