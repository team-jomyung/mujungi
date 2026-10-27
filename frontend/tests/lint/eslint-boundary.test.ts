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
    ["src/parent/features/home/__sample__.tsx", 'import { connectWsClient } from "../../../device/ws/wsClient";\nexport const sample = connectWsClient;\n'],
    ["src/app/parent/__sample__.tsx", 'import DevicePage from "@/app/device/page";\nexport const sample = DevicePage;\n'],
  ])("보호자 영역(%s)이 기기 영역을 import하면 lint가 실패한다", async (filePath, code) => {
    const rules = await rulesFor(filePath, code);
    expect(rules).toContain("no-restricted-imports");
    expect(rules).toContain("import/no-restricted-paths");
  }, TIMEOUT_MS);

  it.each([
    ["src/device/__sample__.ts", 'import { createApiClient } from "@/parent/common/api/apiClient";\nexport const sample = createApiClient;\n'],
    ["src/app/device/__sample__.tsx", 'import { HomeScreen } from "../../parent/features/home/HomeScreen";\nexport const sample = HomeScreen;\n'],
  ])("기기 영역(%s)이 보호자 영역을 import하면 lint가 실패한다", async (filePath, code) => {
    const rules = await rulesFor(filePath, code);
    expect(rules).toContain("no-restricted-imports");
    expect(rules).toContain("import/no-restricted-paths");
  }, TIMEOUT_MS);

  it.each([
    ["src/shared/__sample__.ts", 'import { PARENT_ROUTES } from "@/parent/common/navigation";\nexport const sample = PARENT_ROUTES;\n'],
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

describe("관리자 영역 경계(JIRA-279)", () => {
  it.each([
    ["src/admin/__sample__.ts", 'import { PARENT_ROUTES } from "@/parent/common/navigation";\nexport const sample = PARENT_ROUTES;\n'],
    ["src/admin/features/activity/__sample__.ts", 'import { createApiClient } from "../../../parent/common/api/apiClient";\nexport const sample = createApiClient;\n'],
    ["src/admin/__device_sample__.ts", 'import { deviceReducer } from "@/device/machine/deviceMachine";\nexport const sample = deviceReducer;\n'],
    ["src/app/admin/__sample__.tsx", 'import DevicePage from "../device/page";\nexport const sample = DevicePage;\n'],
    ["src/app/admin/__parent_sample__.tsx", 'import ParentLayout from "@/app/parent/layout";\nexport const sample = ParentLayout;\n'],
  ])("관리자(%s)는 보호자 · 기기 기능 및 라우트를 import하지 않는다", async (filePath, code) => {
    const rules = await rulesFor(filePath, code);
    expect(rules).toContain("no-restricted-imports");
    expect(rules).toContain("import/no-restricted-paths");
  }, TIMEOUT_MS);

  it.each([
    ["src/parent/__sample__.ts", "@/admin/data/types"],
    ["src/parent/common/__sample__.ts", "../../admin/data/types"],
    ["src/parent/features/home/__sample__.ts", "@/admin/data/types"],
    ["src/app/parent/__sample__.ts", "@/admin/data/types"],
    ["src/device/__sample__.ts", "../admin/data/types"],
    ["src/app/device/__sample__.ts", "@/admin/data/types"],
    ["src/shared/__sample__.ts", "@/admin/data/types"],
    ["src/shared/__relative_sample__.ts", "../admin/data/types"],
  ])("보호자 · 기기 · 공용(%s)은 관리자 타입도 import하지 않는다", async (filePath, importPath) => {
    const rules = await rulesFor(filePath, `import type { AdminFilter } from "${importPath}";\nexport type Sample = AdminFilter;\n`);
    expect(rules).toContain("no-restricted-imports");
    expect(rules).toContain("import/no-restricted-paths");
  }, TIMEOUT_MS);

  it.each([
    ["src/admin/__sample__.ts", 'import { COPY } from "@/shared/copy/ko";\nexport const sample = COPY;\n'],
    ["src/admin/features/activity/__sample__.ts", 'import type { AdminFilter } from "../../data/types";\nexport type Sample = AdminFilter;\n'],
    ["src/app/admin/__sample__.ts", 'import type { AdminFilter } from "@/admin/data/types";\nexport type Sample = AdminFilter;\n'],
  ])("관리자(%s)는 공용 계층과 관리자 영역을 import할 수 있다", async (filePath, code) => {
    expect(await rulesFor(filePath, code)).toEqual([]);
  }, TIMEOUT_MS);
});

describe("관리자 공통 · 기능 의존 방향", () => {
  it.each([
    ["src/admin/common/__sample__.ts", "@/admin/features/activity/ActivityScreen"],
    ["src/admin/common/__relative_sample__.ts", "../features/activity/ActivityScreen"],
    ["src/admin/data/__sample__.ts", "@/admin/features/activity/ActivityScreen"],
    ["src/admin/data/__relative_sample__.ts", "../features/activity/ActivityScreen"],
  ])("공통 · 데이터 계층(%s)은 기능을 import하지 않는다", async (filePath, importPath) => {
    const rules = await rulesFor(
      filePath,
      `import { ActivityScreen } from "${importPath}";\nexport const sample = ActivityScreen;\n`,
    );
    expect(rules).toContain("no-restricted-imports");
    expect(rules).toContain("import/no-restricted-paths");
  }, TIMEOUT_MS);

  const features = [
    ["overview", "OverviewScreen"],
    ["activity", "ActivityScreen"],
    ["quality", "QualityScreen"],
    ["cost", "CostScreen"],
  ] as const;
  const crossFeatureImports = features.flatMap(([name]) =>
    features.filter(([other]) => name !== other).flatMap(([other, screen]) => [
      [name, `@/admin/features/${other}/${screen}`, screen],
      [name, `../${other}/${screen}`, screen],
    ]),
  );

  it.each(crossFeatureImports)("기능 %s에서 다른 기능 %s를 import하면 실패한다", async (name, importPath, component) => {
    const rules = await rulesFor(
      `src/admin/features/${name}/__sample__.ts`,
      `import { ${component} } from "${importPath}";\nexport const sample = ${component};\n`,
    );
    expect(rules).toContain("no-restricted-imports");
    expect(rules).toContain("import/no-restricted-paths");
  }, TIMEOUT_MS);

  it.each(features)("기능 %s는 공통 코드를 별칭과 상대 경로로 import할 수 있다", async (name) => {
    const filePath = `src/admin/features/${name}/__sample__.ts`;
    for (const importPath of ["@/admin/common/Badge", "../../common/Badge"]) {
      const rules = await rulesFor(filePath, `import { Badge } from "${importPath}";\nexport const sample = Badge;\n`);
      expect(rules).toEqual([]);
    }
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

  it("서비스 워커 등록은 U7 전용 모듈 밖에서 오류다(micPermission.ts 포함)", async () => {
    const code = 'export function sample() {\n  return navigator.serviceWorker.register("/sw.js");\n}\n';
    expect(await rulesFor("src/device/__sample__.ts", code)).toContain("no-restricted-syntax");
    expect(await rulesFor("src/device/mic/micPermission.ts", code)).toContain("no-restricted-syntax");
    expect(await rulesFor("src/shared/__sample__.ts", code)).toContain("no-restricted-syntax");
    expect(await rulesFor("src/parent/__sample__.ts", code)).toContain("no-restricted-syntax");
    expect(await rulesFor("src/parent/common/push/browserPush.ts", code)).toEqual([]);
    const mic = "export function sample() { return navigator.mediaDevices.getUserMedia({ audio: true }); }";
    expect(await rulesFor("src/parent/common/push/browserPush.ts", mic)).toContain("no-restricted-syntax");
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
