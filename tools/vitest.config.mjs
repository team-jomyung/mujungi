import { defineConfig } from "vitest/config";

// 무중 시험 도구 실행기 (U1 3.5 Step 6 · J106 · J129)
// tools는 시험 지원 코드라 커버리지 하한 대상이 아니다(J107). 시험 파일은 기능 이름 폴더(tests/skeleton/ · tests/voice-session/ …)에 둔다.
// CI(환경 변수 CI)에서만 실패한 시험을 최대 2회 더 돌린다. 로컬은 재시도하지 않는다.
export default defineConfig({
  test: {
    environment: "node",
    include: ["tests/**/*.test.mjs"],
    retry: process.env.CI ? 2 : 0,
    // 하위 프로세스(검증 스크립트 · 가짜 서버)를 띄우는 시험이 있어 기본 5초보다 넉넉히 둔다
    testTimeout: 30_000,
  },
});
