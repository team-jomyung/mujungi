import { describe, expect, it, vi } from "vitest";
import { guardAdminAccess } from "@/admin/access";
import { readAdminMode } from "@/admin/config";
import { AdminDataError } from "@/admin/data/errors";
import { createMockAdminSource } from "@/admin/data/mock";

describe("관리자 접근 경계", () => {
  it("운영 환경에서는 미리보기 설정을 넣어도 합성 데이터를 허용하지 않는다", () => {
    expect(readAdminMode({ NODE_ENV: "production", MUJOONG_ADMIN_PREVIEW: "true" })).toBe("disabled");
    expect(readAdminMode({ NODE_ENV: "development", MUJOONG_ADMIN_PREVIEW: "true" })).toBe("preview");
    expect(readAdminMode({ NODE_ENV: "production", MUJOONG_ADMIN_API_ENABLED: "true" })).toBe("api");
    expect(readAdminMode({})).toBe("disabled");
  });
  it.each(["unauthenticated", "forbidden"] as const)("상세 요청이 %s이면 관리자 전체 조회를 차단한다", async (kind) => {
    const base = createMockAdminSource("2026-10-26");
    const error = new AdminDataError(kind);
    const denied = vi.fn();
    const guarded = guardAdminAccess({ ...base, costDetail: async () => { throw error; } }, denied);
    await guarded.me();
    await expect(guarded.costDetail("activity-1")).rejects.toBe(error);
    expect(denied).toHaveBeenCalledWith(error);
  });
  it("취소한 이전 요청은 현재 조회 권한을 취소하지 않는다", async () => {
    const controller = new AbortController();
    controller.abort();
    const denied = vi.fn();
    const guarded = guardAdminAccess({ ...createMockAdminSource("2026-10-26"), activity: async () => { throw new AdminDataError("forbidden"); } }, denied);
    await expect(guarded.activity("old", controller.signal)).rejects.toThrow();
    expect(denied).not.toHaveBeenCalled();
  });
});
