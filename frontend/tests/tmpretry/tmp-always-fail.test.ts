import { expect, it } from "vitest";

// 임시(병합 금지, JIRA-256 실패 차단 확인용): 재시도해도 언제나 실패한다
it("tmp retry: 재시도해도 계속 실패", () => {
  expect("첫 시도").toBe("영원히 실패");
});
