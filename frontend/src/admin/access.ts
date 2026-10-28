import { AdminDataError } from "./data/errors";
import type { AdminDataSource } from "./data/types";

/** 어느 조회에서든 인증·권한이 만료되면 이전 조회 내용을 포함한 관리자 영역을 차단한다. */
export function guardAdminAccess(source: AdminDataSource, denied: (error: AdminDataError) => void): AdminDataSource {
  async function request<T>(operation: () => Promise<T>, signal?: AbortSignal): Promise<T> {
    try {
      return await operation();
    } catch (error) {
      const rejected = error instanceof AdminDataError
        && (error.kind === "unauthenticated" || error.kind === "forbidden");
      if (!signal?.aborted && rejected) {
        denied(error);
      }
      throw error;
    }
  }
  return {
    me: (signal) => request(() => source.me(signal), signal),
    overview: (filter, signal) => request(() => source.overview(filter, signal), signal),
    activities: (filter, page, signal) => request(() => source.activities(filter, page, signal), signal),
    activity: (id, signal) => request(() => source.activity(id, signal), signal),
    guardians: (filter, page, signal) => request(() => source.guardians(filter, page, signal), signal),
    quality: (filter, signal) => request(() => source.quality(filter, signal), signal),
    costs: (filter, page, signal) => request(() => source.costs(filter, page, signal), signal),
    costDetail: (id, signal) => request(() => source.costDetail(id, signal), signal),
  };
}
