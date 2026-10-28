import type { AdminFilter } from "./types";

export const MAX_ADMIN_QUERY_LENGTH = 50;

/** 날짜와 페이지 외에 활동에 연결되는 조회 조건이 있는지 확인한다. */
export function hasActivityFilter(filter: AdminFilter): boolean {
  return filter.type !== "ALL"
    || Boolean(filter.query?.trim() || filter.guardianId || filter.childId || filter.deviceId)
    || Boolean(filter.status && filter.status !== "ALL");
}
