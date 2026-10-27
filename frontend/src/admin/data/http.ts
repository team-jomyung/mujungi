import { AdminDataError, assertNotAborted, errorFromResponse } from "./errors";
import { validRange } from "./dates";
import {
  parseActivity,
  parseActivitySummary,
  parseCostDetail,
  parseCosts,
  parseGuardian,
  parseMe,
  parseOverview,
  parsePage,
  parseQuality,
} from "./parsers";
import type { AdminDataSource, AdminFilter } from "./types";

export interface AdminHttpOptions {
  timeoutMs: number;
  fetchImpl?: (url: string, init: RequestInit) => Promise<Response>;
}

/**
 * 조회 경로와 DTO는 제안이다.
 * 모든 경로의 서버 인가를 확인한 뒤 활성화한다.
 */
export function createHttpAdminSource({
  timeoutMs,
  fetchImpl = (url, init) => fetch(url, init),
}: AdminHttpOptions): AdminDataSource {
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    throw new Error("관리자 요청 제한 시간은 양수여야 합니다.");
  }

  async function get<T>(path: string, parse: (json: unknown) => T, signal?: AbortSignal): Promise<T> {
    assertNotAborted(signal);
    const controller = new AbortController();
    let timedOut = false;
    const onAbort = () => controller.abort();
    signal?.addEventListener("abort", onAbort, { once: true });
    const timer = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, timeoutMs);
    try {
      const response = await fetchImpl(`/api/admin${path}`, {
        method: "GET",
        credentials: "same-origin",
        cache: "no-store",
        headers: { Accept: "application/json" },
        signal: controller.signal,
      });
      assertNotAborted(signal);
      if (timedOut) {
        throw new AdminDataError("timeout");
      }
      let body: unknown;
      try {
        body = await response.json();
      } catch {
        // HTML·빈 본문이어도 인증 및 권한 오류의 HTTP 상태는 유지한다.
        if (response.ok) {
          throw new AdminDataError("invalidResponse", response.status);
        }
      }
      assertNotAborted(signal);
      if (timedOut) {
        throw new AdminDataError("timeout");
      }
      if (!response.ok) {
        throw errorFromResponse(response, body);
      }
      return parse(body);
    } catch (error) {
      if (signal?.aborted) {
        throw new AdminDataError("aborted");
      }
      if (timedOut) {
        throw new AdminDataError("timeout");
      }
      if (error instanceof AdminDataError) {
        throw error;
      }
      throw new AdminDataError("network");
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener("abort", onAbort);
    }
  }

  function query(filter: AdminFilter, page?: number): string {
    if (!validRange(filter.from, filter.to)) {
      throw new RangeError("관리자 조회 기간은 올바른 날짜로 최대 366일까지 가능합니다.");
    }
    const params = new URLSearchParams({
      from: filter.from,
      to: filter.to,
      timezone: "Asia/Seoul",
    });
    if (filter.type !== "ALL") {
      params.set("type", filter.type);
    }
    for (const key of ["query", "guardianId", "childId"] as const) {
      if (filter[key]) {
        params.set(key, filter[key]);
      }
    }
    if (filter.status && filter.status !== "ALL") {
      params.set("status", filter.status);
    }
    if (page !== undefined) {
      params.set("page", String(page));
      params.set("pageSize", "8");
    }
    return `?${params}`;
  }

  return {
    me: (signal) => get("/me", parseMe, signal),
    overview: (filter, signal) => get(`/overview${query(filter)}`, parseOverview, signal),
    activities: (filter, page, signal) => get(
      `/activities${query(filter, page)}`,
      parsePage(parseActivitySummary),
      signal,
    ),
    activity: (id, signal) => get(`/activities/${encodeURIComponent(id)}`, parseActivity, signal),
    guardians: (filter, page, signal) => get(`/guardians${query(filter, page)}`, parsePage(parseGuardian), signal),
    quality: (filter, signal) => get(`/quality${query(filter)}`, parseQuality, signal),
    costs: (filter, page, signal) => get(`/costs${query(filter, page)}`, parseCosts, signal),
    costDetail: (id, signal) => get(`/costs/${encodeURIComponent(id)}`, parseCostDetail, signal),
  };
}
