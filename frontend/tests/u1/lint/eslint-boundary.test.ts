// @vitest-environment node
import { ESLint } from "eslint";
import { beforeAll, describe, expect, it } from "vitest";
import { frontendRoot } from "../support/sourceScan";

// 영역 경계 · 보안 lint 규칙 시험 (U1 3.5 Step 42 · PD-15 · NFR11.1 · SD-11 · NFR10.4 · PD-16)
// 위반 샘플은 파일로 두지 않고 ESLint API lintText(code, { filePath })로 넘긴다(`eslint .`이 일부러 넣은 위반을 잡지 않게).
// 실제 eslint.config.mjs를 그대로 읽으므로 설정에서 규칙이 빠지면 이 시험이 실패한다.

const TIMEOUT_MS = 60_000;
let eslint: ESLint;

async function rulesFor(filePath: string, code: string): Promise<string[]> {
  const [result] = await eslint.lintText(code, { filePath });
  return result.messages.filter((message) => message.severity === 2).map((message) => message.ruleId ?? "fatal");
}

beforeAll(() => {
  eslint = new ESLint({ cwd: frontendRoot });
});

describe("영역 경계(PD-15 · NFR11.1)", () => {
  it.each([
    ["src/parent/__sample__.tsx", 'import { DeviceApp } from "@/device/DeviceApp";\nexport const sample = DeviceApp;\n'],
    ["src/parent/screens/__sample__.tsx", 'import { connectWsClient } from "../../device/ws/wsClient";\nexport const sample = connectWsClient;\n'],
    ["src/app/parent/__sample__.tsx", 'import DevicePage from "@/app/device/page";\nexport const sample = DevicePage;\n'],
  ])("보호자 영역(%s)이 기기 영역을 import하면 lint가 실패한다", async (filePath, code) => {
    const rules = await rulesFor(filePath, code);
    expect(rules).toContain("no-restricted-imports");
    expect(rules).toContain("import/no-restricted-paths");
  }, TIMEOUT_MS);

  it.each([
    ["src/device/__sample__.ts", 'import { createApiClient } from "@/parent/api/apiClient";\nexport const sample = createApiClient;\n'],
    ["src/app/device/__sample__.tsx", 'import { Home04 } from "../../parent/screens/Home04";\nexport const sample = Home04;\n'],
  ])("기기 영역(%s)이 보호자 영역을 import하면 lint가 실패한다", async (filePath, code) => {
    const rules = await rulesFor(filePath, code);
    expect(rules).toContain("no-restricted-imports");
    expect(rules).toContain("import/no-restricted-paths");
  }, TIMEOUT_MS);

  it.each([
    ["src/shared/__sample__.ts", 'import { PARENT_ROUTES } from "@/parent/navigation";\nexport const sample = PARENT_ROUTES;\n'],
    ["src/shared/__sample2__.ts", 'import { deviceReducer } from "@/device/machine/deviceMachine";\nexport const sample = deviceReducer;\n'],
  ])("공용 계층(%s)이 두 영역을 import하면 lint가 실패한다", async (filePath, code) => {
    const rules = await rulesFor(filePath, code);
    expect(rules).toContain("no-restricted-imports");
    expect(rules).toContain("import/no-restricted-paths");
  }, TIMEOUT_MS);

  it("영역이 공용 계층을 import하는 것은 허용한다(양성 대조)", async () => {
    const rules = await rulesFor("src/parent/__sample__.ts", 'import { COPY } from "@/shared/copy/ko";\nexport const sample = COPY;\n');
    expect(rules).toEqual([]);
  }, TIMEOUT_MS);
});

describe("보안 · 기기 규칙(SD-11 · NFR10.4 · PD-16)", () => {
  it("dangerouslySetInnerHTML은 오류다(react/no-danger)", async () => {
    const rules = await rulesFor(
      "src/parent/__sample__.tsx",
      'export function Sample() {\n  return <div dangerouslySetInnerHTML={{ __html: "x" }} />;\n}\n',
    );
    expect(rules).toContain("react/no-danger");
  }, TIMEOUT_MS);

  it("getUserMedia는 micPermission.ts 밖에서 부르면 오류이고 그 파일에서는 허용된다", async () => {
    const code = "export async function sample() {\n  return navigator.mediaDevices.getUserMedia({ audio: true });\n}\n";
    expect(await rulesFor("src/device/__sample__.ts", code)).toContain("no-restricted-syntax");
    expect(await rulesFor("src/parent/__sample__.ts", code)).toContain("no-restricted-syntax");
    expect(await rulesFor("src/device/mic/micPermission.ts", code)).toEqual([]);
  }, TIMEOUT_MS);

  it("서비스 워커 등록은 어디서든 오류다(micPermission.ts 포함)", async () => {
    const code = 'export function sample() {\n  return navigator.serviceWorker.register("/sw.js");\n}\n';
    expect(await rulesFor("src/device/__sample__.ts", code)).toContain("no-restricted-syntax");
    expect(await rulesFor("src/device/mic/micPermission.ts", code)).toContain("no-restricted-syntax");
  }, TIMEOUT_MS);

  it("화면 글을 JSX에 직접 쓰면 오류다(문구는 copy 모듈에서만, react/jsx-no-literals)", async () => {
    const rules = await rulesFor("src/parent/__sample__.tsx", "export function Sample() {\n  return <p>안녕</p>;\n}\n");
    expect(rules).toContain("react/jsx-no-literals");
  }, TIMEOUT_MS);
});

describe("지금 코드", () => {
  it("src 전체가 경계 · 보안 규칙을 어기지 않는다", async () => {
    const results = await eslint.lintFiles(["src"]);
    const errors = results.flatMap((result) =>
      result.messages.filter((message) => message.severity === 2).map((message) => `${result.filePath}:${message.line} ${message.ruleId}`),
    );
    expect(errors).toEqual([]);
  }, TIMEOUT_MS);
});
