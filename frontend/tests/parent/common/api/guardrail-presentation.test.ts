import { describe, expect, it } from "vitest";
import { parseGuardrailPresentation as parse } from "@/parent/common/api/guardrailPresentation";
import { parseLiveView, ResponseShapeError } from "@/parent/common/api/types";
import { fetchLiveView } from "@/parent/common/api/live";
import { createApiClient } from "@/parent/common/api/apiClient";
import { jsonResponse, liveViewBody } from "../../../support/fixtures";

const facts = { schemaVersion: 1, notice: "COMPLETED", participation: "REFUSED", goodbye: "PLAYING", confirmedStopBubbleSeq: 7 };
describe("guardrail 선택 사실 파서", () => {
  it("v1 모든 사실과 안전한 참조만 읽고 추가 속성은 버린다", () => {
    expect(parse({ ...facts, ignored: "private" })).toEqual(facts);
    expect(parse({ ...facts, confirmedStopBubbleSeq: null })?.confirmedStopBubbleSeq).toBeNull();
  });
  it("누락/null/배열/지원 밖 판은 선택 객체만 null이다", () => {
    for (const value of [undefined, null, [], "bad", { ...facts, schemaVersion: 2 }]) {
      expect(parseLiveView(liveViewBody("ACTIVE", { guardrail: value }))).toMatchObject({ status: "ACTIVE", guardrail: null });
    }
  });
  it("모든 필수 필드의 부재와 잘못된 타입은 전체 객체 실패다", () => {
    for (const key of Object.keys(facts)) {
      const missing: Record<string, unknown> = { ...facts };
      delete missing[key];
      expect(parse(missing)).toBeNull();
      expect(parse({ ...facts, [key]: {} })).toBeNull();
    }
  });
  it("미래 문자열 enum을 성공/거절로 추정하지 않는다", () => {
    expect(parse({ ...facts, notice: "FUTURE", participation: "FUTURE", goodbye: "FUTURE" })).toMatchObject({ notice: "UNKNOWN", participation: "UNKNOWN", goodbye: "UNKNOWN" });
  });
  it("문자열/0/음수/분수/무한/안전 범위 밖 seq는 거절한다", () => {
    for (const seq of ["7", 0, -1, 1.5, Infinity, NaN, Number.MAX_SAFE_INTEGER + 1]) expect(parse({ ...facts, confirmedStopBubbleSeq: seq })).toBeNull();
    expect(parse({ ...facts, confirmedStopBubbleSeq: Number.MAX_SAFE_INTEGER })?.confirmedStopBubbleSeq).toBe(Number.MAX_SAFE_INTEGER);
  });
  it("기본 필수 값과 요청 세션 ID 거절을 완화하지 않는다", async () => {
    expect(() => parseLiveView({ guardrail: facts })).toThrow(ResponseShapeError);
    const api = createApiClient({ timeoutMs: 1000, fetchImpl: async () => jsonResponse(200, liveViewBody("ACTIVE", { sessionId: "other", guardrail: facts })) });
    await expect(fetchLiveView(api, "current", 0)).rejects.toMatchObject({ kind: "invalidResponse" });
  });
});
