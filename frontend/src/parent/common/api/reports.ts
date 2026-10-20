import type { ApiClient } from "./apiClient";
import { object, rows, text, count } from "./results";

export function fetchSavedReports(api: ApiClient, page: number, signal?: AbortSignal) {
  return api.request({ method: "GET", path: `/api/reports?page=${page}`, csrf: false, signal, parse: value => {
    const r = object(value);
    return { items: rows(r.items).map(item => ({ reportId: text(item.reportId), periodFrom: text(item.periodFrom), periodTo: text(item.periodTo), activityCount: count(item.activityCount), savedAt: text(item.savedAt) })), hasMore: r.hasMore === true };
  } });
}
