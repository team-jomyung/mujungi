import { expect, it } from "vitest";

// 임시(병합 금지, JIRA-256 재시도 확인용): 처음 시도는 실패하고 재시도하면 통과한다
let attempts = 0;

it("tmp retry: 첫 시도 실패 · 재시도 통과", () => {
  attempts += 1;
  expect(attempts).toBeGreaterThan(1);
});
