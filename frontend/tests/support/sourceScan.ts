import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

// 정적 검사 도우미 (U1 3.5 Step 41 · 42): 프런트 소스 파일 목록과 주석을 뺀 코드.
// 설명 주석에 적힌 금지 이름(예: 'localStorage 금지')은 검사 대상이 아니다.

export const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
export const repoRoot = path.resolve(frontendRoot, "..");

/** dir 아래 .ts · .tsx 파일(절대 경로). 없는 디렉터리는 빈 목록. */
export function sourceFiles(dir: string): string[] {
  if (!existsSync(dir)) {
    return [];
  }
  return readdirSync(dir).flatMap((entry) => {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) {
      return sourceFiles(full);
    }
    return /\.(ts|tsx)$/.test(entry) ? [full] : [];
  });
}

/** 주석을 뺀 코드. 문자열 안의 '//'(예: URL)는 지우지 않는다. */
export function codeOf(file: string): string {
  return readFileSync(file, "utf8")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/(^|[^:"'`\\])\/\/.*$/gm, "$1");
}

/** 프런트 루트 기준 상대 경로. */
export function relative(file: string): string {
  return path.relative(frontendRoot, file).split(path.sep).join("/");
}
