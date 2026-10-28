import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import {
  parseActivity,
  parseActivitySummary,
  parseCostDetail,
  parseCosts,
  parseGuardian,
  parseMe,
  parseOverview,
  parsePage,
  parseQuality,
} from "@/admin/data/parsers";
import { repoRoot } from "../support/sourceScan";
import {
  activityDetail,
  activityPage,
  activitySummary,
  costDetail,
  costs,
  costSummary,
  guardians,
  overview,
  phase,
  quality,
  scene,
} from "./fixtures/contractResponses";

interface ContractSchema {
  $ref?: string;
  $defs?: Record<string, ContractSchema>;
  type?: string | string[];
  enum?: unknown[];
  anyOf?: ContractSchema[];
  required?: string[];
  properties?: Record<string, ContractSchema>;
  items?: ContractSchema;
  additionalProperties?: boolean;
  pattern?: string;
  maxLength?: number;
  maxItems?: number;
}

const schemaRoot = path.join(repoRoot, "contracts/schemas");
const parentApi = readFileSync(path.join(repoRoot, "contracts/parent-api.yaml"), "utf8").replace(/\r\n/g, "\n");
const common = readSchema("admin-common.schema.json");
const qualitySchema = readSchema("admin-quality-response.schema.json");
const costsSchema = readSchema("admin-costs-response.schema.json");

interface ResponseCase {
  name: string;
  endpoint: string;
  schema: string;
  parse: (value: unknown) => unknown;
  example: unknown;
}

const responses: ResponseCase[] = [
  {
    name: "관리자 확인",
    endpoint: "/admin/me",
    schema: "admin-me-response.schema.json",
    parse: parseMe,
    example: identityExample(),
  },
  {
    name: "대시보드",
    endpoint: "/admin/overview",
    schema: "admin-overview-response.schema.json",
    parse: parseOverview,
    example: overview,
  },
  {
    name: "활동 목록",
    endpoint: "/admin/activities",
    schema: "admin-activity-page-response.schema.json",
    parse: parsePage(parseActivitySummary),
    example: activityPage,
  },
  {
    name: "활동 상세",
    endpoint: "/admin/activities/{sessionId}",
    schema: "admin-activity-detail-response.schema.json",
    parse: parseActivity,
    example: activityDetail,
  },
  {
    name: "보호자·기기 목록",
    endpoint: "/admin/guardians",
    schema: "admin-guardian-page-response.schema.json",
    parse: parsePage(parseGuardian),
    example: guardians,
  },
  {
    name: "연습·AI 품질",
    endpoint: "/admin/quality",
    schema: "admin-quality-response.schema.json",
    parse: parseQuality,
    example: quality,
  },
  {
    name: "API·비용 목록",
    endpoint: "/admin/costs",
    schema: "admin-costs-response.schema.json",
    parse: parseCosts,
    example: costs,
  },
  {
    name: "비용 상세",
    endpoint: "/admin/costs/{costId}",
    schema: "admin-cost-detail-response.schema.json",
    parse: parseCostDetail,
    example: costDetail,
  },
];

describe("관리자 응답 계약 대조", () => {
  // 관리자 확인만 YAML의 명시 예시를 읽는다.
  // 나머지는 스키마에 맞춘 합성 응답이며 실제 서버 응답으로 취급하지 않는다.
  it.each(responses)("$name 응답은 계약 경로·스키마와 일치하고 모든 필드를 보존한다", ({ endpoint, schema, parse, example }) => {
    const reference = operation(endpoint).match(/schema:\s*\{\s*\$ref:\s*"schemas\/([^"\n]+)"\s*\}/);
    expect(reference?.[1]).toBe(schema);
    expectContractFields(example, readSchema(schema), schema);
    expect(parse(example)).toEqual(example);
  });

  it.each(responses)("$name 응답의 계약 필수 필드는 생략할 수 없다", ({ schema, parse, example }) => {
    const required = readSchema(schema).required;
    expect(required?.length).toBeGreaterThan(0);
    for (const key of required!) {
      const missing = { ...(example as Record<string, unknown>) };
      delete missing[key];
      expect(() => parse(missing), key).toThrow(expect.objectContaining({ kind: "invalidResponse" }));
    }
  });

  it.each(["0", "0.5", "12.345678", "0.00000049", "12345678901234567890.12345678", null])(
    "USD 값 %j를 숫자로 바꾸지 않고 합계·목록·상세·단계에 보존한다",
    (value) => {
      if (value !== null) {
        expect(common.$defs!.Usd.type).toBe("string");
        expect(value.length).toBeLessThanOrEqual(common.$defs!.Usd.maxLength!);
        expect(value).toMatch(new RegExp(common.$defs!.Usd.pattern!));
      }
      const list = parseCosts({
        ...costs,
        totals: {
          ...costs.totals,
          collectedUsd: value,
          activityUsd: value,
          failedStartUsd: value,
          unlinkedUsd: value,
        },
        activities: { ...costs.activities, items: [{ ...costSummary, estimatedUsd: value }] },
      });
      expect(list.totals.collectedUsd).toBe(value);
      expect(list.totals.activityUsd).toBe(value);
      expect(list.totals.failedStartUsd).toBe(value);
      expect(list.totals.unlinkedUsd).toBe(value);
      expect(list.activities.items[0].estimatedUsd).toBe(value);
      const detail = parseCostDetail({
        ...costDetail,
        estimatedUsd: value,
        phases: [{ ...phase, estimatedUsd: value }],
      });
      expect(detail.estimatedUsd).toBe(value);
      expect(detail.phases[0].estimatedUsd).toBe(value);
    },
  );

  it.each([0, 0.5, "-1", "1e-8", "0.123456789", "1,000", "", "1".repeat(41)])(
    "USD 계약에 맞지 않는 값 %j는 응답 오류다",
    (value) => {
      expect(() => parseCostDetail({ ...costDetail, estimatedUsd: value }))
        .toThrow(expect.objectContaining({ kind: "invalidResponse" }));
    },
  );

  it("판별 불가한 미응답과 수집 범위 밖 비용은 계약대로 null이다", () => {
    expect(qualitySchema.properties!.acceptance.properties!.unanswered.type).toBe("null");
    expect(costsSchema.properties!.totals.properties!.nonActivityUsd.type).toBe("null");
    expect(parseQuality(quality).acceptance.unanswered).toBeNull();
    expect(parseCosts(costs).totals.nonActivityUsd).toBeNull();
    expect(() => parseQuality({ ...quality, acceptance: { ...quality.acceptance, unanswered: 0 } }))
      .toThrow(expect.objectContaining({ kind: "invalidResponse" }));
    expect(() => parseCosts({ ...costs, totals: { ...costs.totals, nonActivityUsd: "0" } }))
      .toThrow(expect.objectContaining({ kind: "invalidResponse" }));
  });

  it.each([null, 0, 2])("장면 횟수 %j에서 미확인과 확인된 0을 구분한다", (value) => {
    expect(common.$defs!.SceneResult.properties!.retryCount.type).toContain("null");
    expect(common.$defs!.SceneResult.properties!.reExplanationCount.type).toContain("null");
    const result = parseActivity({
      ...activityDetail,
      scenes: [{ ...scene, retryCount: value, reExplanationCount: value }],
    });
    expect(result.scenes[0].retryCount).toBe(value);
    expect(result.scenes[0].reExplanationCount).toBe(value);
  });

  it.each(enumValues(common.$defs!.EndCategory))("계약 종료 분류 %s를 읽어서 보존한다", (endCategory) => {
    const result = parseActivitySummary({ ...activitySummary, endCategory });
    expect(result.endCategory).toBe(endCategory);
  });

  it.each(enumValues(common.$defs!.SceneResult.properties!.helpOffer))(
    "계약 도움 제안 상태 %s를 읽어서 보존한다",
    (helpOffer) => {
      const helpResponse = helpOffer === "OFFERED" ? "ACCEPT" : helpOffer === "REQUESTED" ? "REQUESTED" : null;
      const result = parseActivity({ ...activityDetail, scenes: [{ ...scene, helpOffer, helpResponse }] });
      expect(result.scenes[0].helpOffer).toBe(helpOffer);
      expect(result.scenes[0].helpResponse).toBe(helpResponse);
    },
  );

  it.each(enumValues(common.$defs!.CollectionStatus))("계약 수집 상태 %s를 읽어서 보존한다", (status) => {
    expect(parseCostDetail({ ...costDetail, status }).status).toBe(status);
  });

  it.each(enumValues(common.$defs!.CostSummary.properties!.category))(
    "계약 비용 분류 %s를 읽어서 보존한다",
    (category) => {
      expect(parseCostDetail({ ...costDetail, category }).category).toBe(category);
    },
  );

  it.each(enumValues(common.$defs!.CostPhase.properties!.phase))(
    "계약 사용 단계 %s를 읽어서 보존한다",
    (usagePhase) => {
      const result = parseCostDetail({ ...costDetail, phases: [{ ...phase, phase: usagePhase }] });
      expect(result.phases[0].phase).toBe(usagePhase);
    },
  );

  it("품질의 전체 건수·목록 한도와 제안 전 종료·확인 불가 집계를 버리지 않는다", () => {
    const result = parseQuality(quality);
    expect(result.recordsTotal).toBeGreaterThan(result.records.length);
    expect(result.recordsLimit).toBe(qualitySchema.properties!.records.maxItems);
    expect(result.acceptance.notOffered).toBe(quality.acceptance.notOffered);
    expect(result.acceptance.unknown).toBe(quality.acceptance.unknown);
    expect(result).toEqual(quality);
  });
});

function readSchema(file: string): ContractSchema {
  return JSON.parse(readFileSync(path.join(schemaRoot, file), "utf8")) as ContractSchema;
}

function operation(endpoint: string): string {
  const marker = `\n  ${endpoint}:\n`;
  const start = parentApi.indexOf(marker);
  if (start < 0) throw new Error(`관리자 계약 경로를 찾지 못했습니다: ${endpoint}`);
  const end = parentApi.indexOf("\n  /", start + marker.length);
  return parentApi.slice(start, end < 0 ? undefined : end);
}

function identityExample(): { displayName: string; role: string } {
  const example = operation("/admin/me").match(/example:\s*\{\s*displayName:\s*([^,}]+),\s*role:\s*([^,}]+)\s*\}/);
  if (!example) throw new Error("관리자 확인 계약의 명시 예시를 찾지 못했습니다.");
  return { displayName: example[1].trim(), role: example[2].trim() };
}

function enumValues(schema: ContractSchema): string[] {
  if (!schema.enum?.length || schema.enum.some((value) => typeof value !== "string")) {
    throw new Error("비어 있지 않은 문자열 열거 계약이 필요합니다.");
  }
  return schema.enum as string[];
}

// 필수 필드·기본 자료형·열거값만 대조한다.
// 날짜 형식과 모든 JSON Schema 키워드를 검증하는 검사기는 아니다.
function expectContractFields(value: unknown, schema: ContractSchema, file: string): void {
  if (schema.$ref) {
    const [targetFile, pointer] = schema.$ref.split("#");
    const sourceFile = targetFile || file;
    let target: unknown = readSchema(sourceFile);
    for (const key of (pointer ?? "").split("/").filter(Boolean)) {
      target = (target as Record<string, unknown>)[key];
    }
    expectContractFields(value, target as ContractSchema, sourceFile);
    return;
  }
  if (schema.anyOf) {
    const branch = schema.anyOf.find((option) => (option.type === "null") === (value === null));
    expect(branch).toBeDefined();
    expectContractFields(value, branch!, file);
    return;
  }
  if (schema.enum) expect(schema.enum).toContain(value);
  if (schema.type) {
    const types = Array.isArray(schema.type) ? schema.type : [schema.type];
    const kind = value === null ? "null" : Array.isArray(value) ? "array" : typeof value;
    expect(types.includes(kind) || (Number.isInteger(value) && types.includes("integer"))).toBe(true);
  }
  if (Array.isArray(value) && schema.items) {
    for (const item of value) expectContractFields(item, schema.items, file);
  } else if (value !== null && typeof value === "object" && schema.properties) {
    const fields = value as Record<string, unknown>;
    for (const key of schema.required ?? []) expect(fields, key).toHaveProperty(key);
    if (schema.additionalProperties === false) {
      expect(Object.keys(fields).sort()).toEqual(Object.keys(schema.properties).sort());
    }
    for (const [key, field] of Object.entries(schema.properties)) {
      if (key in fields) expectContractFields(fields[key], field, file);
    }
  }
}
