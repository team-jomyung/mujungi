import { appendFileSync, mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import type { Reporter, TestCase } from "vitest/node";

// CI 재시도 기록 리포터 (JIRA-256). CI에서 재시도한 시험의 횟수 · 최종 상태 · 최초 실패 원인을 JSON으로 남기고,
// 로그 경고(::warning::)와 실행 요약($GITHUB_STEP_SUMMARY)에 같은 내용을 적는다.
// 기록만 한다: 시험 판정 · 종료 코드는 바꾸지 않고, 저장 · 출력 오류는 경고로만 알린다(CI를 실패시키지 않는다).
// tools/tests/support/retry-reporter.mjs와 같은 동작이다(frontend · tools는 서로 import하지 않는다).

interface RetryRecord {
  readonly file: string;
  readonly name: string;
  readonly retryCount: number;
  readonly state: string;
  readonly flaky: boolean;
  readonly firstError: string | null;
}

const MESSAGE_LIMIT = 300;

export default class RetryReporter implements Reporter {
  private readonly records: RetryRecord[] = [];

  constructor(private readonly outputFile: string) {}

  onTestCaseResult(testCase: TestCase): void {
    try {
      const diagnostic = testCase.diagnostic();
      if (!diagnostic || diagnostic.retryCount === 0) {
        return;
      }
      const result = testCase.result();
      // 재시도 뒤 통과해도 앞선 시도의 오류는 errors에 남는다(Vitest TestResultPassed.errors)
      this.records.push({
        file: testCase.module.relativeModuleId,
        name: testCase.fullName,
        retryCount: diagnostic.retryCount,
        state: result.state,
        flaky: diagnostic.flaky,
        firstError: firstLine(result.errors?.[0]?.message),
      });
    } catch (error) {
      warn(`재시도 기록을 모으지 못했다: ${errorMessage(error)}`);
    }
  }

  onTestRunEnd(): void {
    for (const record of this.records) {
      const verdict = record.state === "passed" ? "재시도 뒤 통과" : "재시도 뒤에도 실패";
      warn(`${verdict}: ${record.file} > ${record.name} (재시도 ${record.retryCount}회) — 최초 실패: ${record.firstError ?? "알 수 없음"}`);
    }
    try {
      mkdirSync(path.dirname(this.outputFile), { recursive: true });
      writeFileSync(this.outputFile, `${JSON.stringify({ retried: this.records }, null, 2)}\n`);
    } catch (error) {
      warn(`재시도 기록 파일을 저장하지 못했다(시험 판정에는 영향 없음): ${errorMessage(error)}`);
    }
    const summaryFile = process.env.GITHUB_STEP_SUMMARY;
    if (summaryFile && this.records.length > 0) {
      try {
        appendFileSync(summaryFile, summaryTable(this.records));
      } catch (error) {
        warn(`실행 요약을 쓰지 못했다(시험 판정에는 영향 없음): ${errorMessage(error)}`);
      }
    }
  }
}

function summaryTable(records: readonly RetryRecord[]): string {
  const cell = (value: string) => value.replace(/\|/g, "\\|");
  const rows = records.map(
    (record) => `| ${cell(record.file)} | ${cell(record.name)} | ${record.retryCount} | ${record.state} | ${cell(record.firstError ?? "")} |`,
  );
  return ["", "### 재시도한 시험", "", "| 파일 | 시험 | 재시도 | 최종 | 최초 실패 |", "|---|---|---|---|---|", ...rows, ""].join("\n");
}

function firstLine(message: string | undefined): string | null {
  if (!message) {
    return null;
  }
  return message.split("\n")[0].slice(0, MESSAGE_LIMIT);
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function warn(message: string): void {
  try {
    // GitHub Actions 경고 주석. 줄바꿈은 주석을 끊으므로 공백으로 바꾼다
    process.stdout.write(`::warning title=retry-reporter::${message.replace(/\r?\n/g, " ")}\n`);
  } catch {
    // 표준 출력이 닫혀 있으면 경고도 남길 수 없다. 판정에 영향을 주지 않도록 여기서 멈춘다
  }
}
