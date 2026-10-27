import { AdminDataError } from "./errors";
import type {
  ActivityDetail,
  ActivitySummary,
  AdminIdentity,
  CostDetail,
  CostPhase,
  Costs,
  CostSummary,
  GuardianSummary,
  Overview,
  Page,
  Quality,
  SceneResult,
} from "./types";
type Parser<T> = (value: unknown) => T;
function invalid(): never {
  throw new AdminDataError("invalidResponse");
}

const text: Parser<string> = value => typeof value === "string" ? value : invalid();
const timestamp: Parser<string> = value => {
  const valid = typeof value === "string"
    && /(?:Z|[+-]\d{2}:\d{2})$/.test(value)
    && Number.isFinite(Date.parse(value));
  return valid ? value : invalid();
};

const number: Parser<number> = value => typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : invalid();
const count: Parser<number> = value => Number.isInteger(number(value)) ? value as number : invalid();
const bool: Parser<boolean> = value => typeof value === "boolean" ? value : invalid();
const nullable = <T>(parser: Parser<T>): Parser<T | null> => value => value === null ? null : parser(value);
const array = <T>(parser: Parser<T>): Parser<T[]> => value => Array.isArray(value) ? value.map(parser) : invalid();
const oneOf = <T extends string>(...choices: T[]): Parser<T> => value => typeof value === "string" && choices.includes(value as T) ? value as T : invalid();
function object<T>(fields: {
  [K in keyof T]: Parser<T[K]>;
}): Parser<T> {
  return value => {
    if (!value || typeof value !== "object" || Array.isArray(value))
      return invalid();
    const input = value as Record<string, unknown>;
    const output: Record<string, unknown> = {};
    for (const key of Object.keys(fields) as (keyof T & string)[])
      output[key] = fields[key](input[key]);
    return output as T;
  };
}

const activityType = oneOf("FREE_TALK", "PRACTICE");
const activityFields = {
  id: text,
  guardianId: text,
  guardianName: text,
  deviceId: text,
  childId: text,
  childName: text,
  type: activityType,
  status: oneOf("COMPLETED", "INTERRUPTED", "FAILED_TO_START"),
  startedAt: timestamp,
  endedAt: nullable(timestamp),
  durationSeconds: nullable(number),
  childTurns: nullable(count),
  aiTurns: nullable(count),
  endReason: text
};

export const parseActivitySummary = object<ActivitySummary>(activityFields);
const helpKind = oneOf("FOCUS_HELP", "SITUATION_EXPLAIN", "EXPRESSION_EXAMPLE");
const parseScene = object<SceneResult>({
  id: text,
  title: text,
  kind: oneOf("BASE", "VARIANT"),
  judgment: oneOf("SUCCESS", "FAILURE", "ABSTAINED", "NOT_EVALUATED"),
  reason: nullable(text),
  responseTiming: nullable(text),
  abstainReason: nullable(text),
  variantSkipReason: nullable(text),
  helpResponse: nullable(oneOf("ACCEPT", "DECLINE", "REQUESTED", "UNCLEAR")),
  retryCount: count,
  reExplanationCount: count,
  helpRecords: array(object({
    id: text,
    kind: helpKind,
    consentAnswer: nullable(oneOf("ACCEPT", "DECLINE", "REQUESTED", "UNCLEAR")),
    exampleGiven: bool
  }))
});
export const parseActivity = object<ActivityDetail>({
  ...activityFields,
  transcriptAvailable: bool,
  transcript: array(object({
    sequence: count,
    speaker: oneOf("CHILD", "AI"),
    text,
    at: timestamp
  })),
  scenes: array(parseScene)
});
export const parsePage = <T>(parseItem: Parser<T>): Parser<Page<T>> => value => {
  const parsed = object<Page<T>>({
    items: array(parseItem),
    page: count,
    pageSize: count,
    total: count
  })(value);
  if (!parsed.pageSize || parsed.items.length > parsed.pageSize || parsed.items.length > parsed.total)
    return invalid();
  return parsed;
};

export const parseMe = object<AdminIdentity>({
  displayName: text,
  role: oneOf("ADMIN")
});
export const parseOverview = object<Overview>({
  activityCount: count,
  freeTalkCount: count,
  practiceCount: count,
  activeChildren: count,
  childTurns: nullable(count),
  aiTurns: nullable(count),
  averageSeconds: object({
    all: nullable(number),
    freeTalk: nullable(number),
    practice: nullable(number)
  }),
  daily: array(object({
    date: text,
    freeTalk: count,
    practice: count
  })),
  endReasons: array(object({
    code: text,
    label: text,
    count
  })),
  recentActivities: array(parseActivitySummary)
});
export const parseGuardian = object<GuardianSummary>({
  id: text,
  name: text,
  activityCount: count,
  devices: array(object({
    id: text,
    name: text,
    children: array(object({
      id: text,
      name: text,
      activityCount: count
    }))
  }))
});
const rate: Parser<number> = value => number(value) <= 1 ? value as number : invalid();
export const parseQuality = object<Quality>({
  startedPractices: count,
  helpExecutionCount: count,
  retryCount: count,
  acceptance: object({
    accepted: count,
    declined: count,
    unclear: count,
    requested: count,
    unanswered: count,
    rate: nullable(rate)
  }),
  helpTypes: array(object({
    kind: helpKind,
    count
  })),
  focusExamples: count,
  records: array(object({
    activity: parseActivitySummary,
    scenes: array(parseScene)
  }))
});
const collectionStatus = oneOf("COMPLETE", "PARTIAL", "UNKNOWN", "NOT_COLLECTED");
const costFields = {
  id: text,
  activityId: nullable(text),
  childName: nullable(text),
  type: nullable(activityType),
  category: oneOf("ACTIVITY", "FAILED_TO_START", "NON_ACTIVITY", "UNLINKED"),
  purpose: text,
  observedAt: timestamp,
  status: collectionStatus,
  voiceSeconds: nullable(number),
  inputTokens: nullable(count),
  outputTokens: nullable(count),
  estimatedUsd: nullable(number)
};

export const parseCostSummary = object<CostSummary>(costFields);
const parsePhase = object<CostPhase>({
  phase: oneOf("PREPARATION", "VOICE", "JUDGMENT", "POST_PROCESSING", "OTHER"),
  model: text,
  calls: count,
  voiceSeconds: nullable(number),
  inputTokens: nullable(count),
  outputTokens: nullable(count),
  cachedInputTokens: nullable(count),
  cacheWriteTokens: nullable(count),
  reasoningTokens: nullable(count),
  estimatedUsd: nullable(number),
  status: collectionStatus,
  calculation: nullable(text)
});
export const parseCostDetail = object<CostDetail>({
  ...costFields,
  phases: array(parsePhase),
  scope: text
});
export const parseCosts = object<Costs>({
  totals: object({
    collectedUsd: nullable(number),
    activityUsd: nullable(number),
    failedStartUsd: nullable(number),
    nonActivityUsd: nullable(number),
    unlinkedUsd: nullable(number),
    unknownCount: count
  }),
  activities: parsePage(parseCostSummary),
  otherCosts: array(parseCostSummary),
  scope: text
});
