import { describe, expect, it } from "vitest";
import { parseResult } from "../../src/parent/api/results";

function response() { return { jobStatus: "DONE", resultLevel: "FULL", sentences: [], practice: null as null | { goals: object[] }, tips: [], candidates: [{ recommendationId: "version-handle", displayName: "물어보기", displayEvidence: "합성 놀이 장면", evidence: [], selected: false, goalSelectionId: null }] as Record<string, unknown>[] }; }

describe("U14 result adapter", () => {
  it("maps safe displayEvidence to the existing 27 view model", () => { expect(parseResult(response()).candidates[0].reasonText).toBe("합성 놀이 장면"); });
  it("preserves legacy candidate responses", () => { const value = response(); value.candidates = [{ ...value.candidates[0], displayEvidence: undefined, reasonText: "기존 공개 근거" }]; expect(parseResult(value).candidates[0].reasonText).toBe("기존 공개 근거"); });
  it("keeps the immutable version handle for selection", () => { expect(parseResult(response()).candidates[0].recommendationId).toBe("version-handle"); });
  it("does not copy internal fields into the screen model", () => { const value = response(); value.candidates[0].reasonText = "비공개 원문"; value.candidates[0].fourElements = { situation: "비공개" }; value.candidates[0].skillRef = "seed:S1"; const result = parseResult(value); expect(JSON.stringify(result)).not.toContain("비공개"); expect(result.candidates[0]).not.toHaveProperty("skillRef"); });
  it("accepts an empty candidate list", () => { const value = response(); value.candidates = []; expect(parseResult(value).candidates).toEqual([]); });
  it("rejects missing display evidence and legacy reason", () => { const value = response(); delete value.candidates[0].displayEvidence; expect(() => parseResult(value)).toThrow(); });
  it("preserves AFTER_REASK with a third scene and the original chip", () => { const value = response(); value.practice = { goals: [{ goalSelectionId: "selection", displayName: "목표", order: 1, help: { count: 0, consent: "NONE" }, scenes: [{ sceneNo: 3, exposureType: "NEW_SCENARIO", sceneChip: "DONE_IMMEDIATE", chipText: "바로 전달", timing: "AFTER_REASK" }] }] }; expect(parseResult(value).practice?.goals[0].scenes[0]).toMatchObject({ sceneNo: 3, timing: "AFTER_REASK", sceneChip: "DONE_IMMEDIATE" }); });
  it("does not add a timing field to legacy scene models", () => { const value = response(); value.practice = { goals: [{ goalSelectionId: "selection", displayName: "목표", order: 1, help: { count: 0, consent: "NONE" }, scenes: [{ sceneNo: 1, exposureType: "ORIGINAL", sceneChip: "DONE_IMMEDIATE", chipText: "바로 전달" }] }] }; expect(parseResult(value).practice?.goals[0].scenes[0]).not.toHaveProperty("timing"); });
});
