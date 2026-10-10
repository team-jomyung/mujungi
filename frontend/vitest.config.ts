import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

// 무중 웹 시험 실행기 (U1 3.5 Step 6 · J106 · J107 · J137)
// - 환경 jsdom, 공통 준비 tests/u1/setup.ts(Testing Library · jest-dom)
// - 커버리지 v8, 대상 src/**/*.{ts,tsx}, 제외는 *.d.ts만, 줄 커버리지 하한 80%(낮추지 않는다)
// - MUJOONG_COVERAGE_SCOPE=<이름>이면 coverage-scope/<이름>.json의 파일만 잰다. 목록이 비었거나 없는 파일이 있으면
//   실행을 멈춘다(빈 측정으로 통과하지 않음). 목록은 source-manifest의 유닛 소스로 채운다(J137).
const frontendRoot = path.dirname(fileURLToPath(import.meta.url));

function coverageInclude(): string[] {
  const scopeName = process.env.MUJOONG_COVERAGE_SCOPE;
  if (!scopeName) {
    return ["src/**/*.{ts,tsx}"];
  }
  const listPath = path.join(frontendRoot, "coverage-scope", `${scopeName}.json`);
  if (!existsSync(listPath)) {
    throw new Error(`커버리지 범위 목록이 없다: frontend/coverage-scope/${scopeName}.json (J137)`);
  }
  const parsed: unknown = JSON.parse(readFileSync(listPath, "utf8"));
  const files =
    typeof parsed === "object" && parsed !== null && Array.isArray((parsed as { files?: unknown }).files)
      ? ((parsed as { files: unknown[] }).files.filter((entry) => typeof entry === "string") as string[])
      : [];
  if (files.length === 0) {
    throw new Error(`frontend/coverage-scope/${scopeName}.json에 파일이 없다. source-manifest의 유닛 소스로 채운다(J137).`);
  }
  const relativeFiles = files.map((entry) => entry.replace(/^frontend\//, ""));
  const missing = relativeFiles.filter((entry) => !existsSync(path.join(frontendRoot, entry)));
  if (missing.length > 0) {
    throw new Error(`frontend/coverage-scope/${scopeName}.json에 없는 파일이 있다: ${missing.join(", ")}`);
  }
  return relativeFiles;
}

export default defineConfig({
  resolve: {
    alias: {
      "@": path.join(frontendRoot, "src"),
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["tests/u1/setup.ts"],
    include: ["tests/**/*.test.{ts,tsx}"],
    coverage: {
      provider: "v8",
      include: coverageInclude(),
      exclude: ["**/*.d.ts"],
      reporter: ["text-summary", "html", "json-summary"],
      reportsDirectory: "coverage",
      thresholds: {
        lines: 80,
      },
    },
  },
});
