/** 관리자 화면의 제안 DTO다. 구현된 백엔드 계약이 아니며 서버 연결 전에 합의해야 한다. */
export type ActivityType = "FREE_TALK" | "PRACTICE";
export type ActivityStatus = "COMPLETED" | "INTERRUPTED" | "FAILED_TO_START";
export type CollectionStatus = "COMPLETE" | "PARTIAL" | "UNKNOWN" | "NOT_COLLECTED";
export type ConsentAnswer = "ACCEPT" | "DECLINE" | "REQUESTED" | "UNCLEAR" | null;
export type HelpKind = "FOCUS_HELP" | "SITUATION_EXPLAIN" | "EXPRESSION_EXAMPLE";
export interface AdminFilter {
  from: string;
  to: string;
  type: "ALL" | ActivityType;
  query?: string;
  guardianId?: string;
  childId?: string;
  status?: "ALL" | ActivityStatus;
}

export interface Page<T> {
  items: T[];
  page: number;
  pageSize: number;
  total: number;
}

export interface AdminIdentity {
  displayName: string;
  role: "ADMIN";
}

export interface ActivitySummary {
  id: string;
  guardianId: string;
  guardianName: string;
  deviceId: string;
  childId: string;
  childName: string;
  type: ActivityType;
  status: ActivityStatus;
  startedAt: string;
  endedAt: string | null;
  durationSeconds: number | null;
  childTurns: number | null;
  aiTurns: number | null;
  endReason: string;
}

export interface TranscriptLine {
  sequence: number;
  speaker: "CHILD" | "AI";
  text: string;
  at: string;
}

export interface HelpRecord {
  id: string;
  kind: HelpKind;
  consentAnswer: ConsentAnswer;
  exampleGiven: boolean;
}

export interface SceneResult {
  id: string;
  title: string;
  kind: "BASE" | "VARIANT";
  judgment: "SUCCESS" | "FAILURE" | "ABSTAINED" | "NOT_EVALUATED";
  reason: string | null;
  responseTiming: string | null;
  abstainReason: string | null;
  /** 도움을 실행하지 않은 경우도 포함하는 장면 단위 제안 응답 값이다. */
  helpResponse: ConsentAnswer;
  variantSkipReason: string | null;
  retryCount: number;
  reExplanationCount: number;
  helpRecords: HelpRecord[];
}

export interface ActivityDetail extends ActivitySummary {
  transcript: TranscriptLine[];
  transcriptAvailable: boolean;
  scenes: SceneResult[];
}

export interface GuardianSummary {
  id: string;
  name: string;
  activityCount: number;
  devices: {
    id: string;
    name: string;
    children: {
      id: string;
      name: string;
      activityCount: number;
    }[];
  }[];
}

export interface Overview {
  activityCount: number;
  freeTalkCount: number;
  practiceCount: number;
  activeChildren: number;
  childTurns: number | null;
  aiTurns: number | null;
  averageSeconds: {
    all: number | null;
    freeTalk: number | null;
    practice: number | null;
  };
  daily: {
    date: string;
    freeTalk: number;
    practice: number;
  }[];
  endReasons: {
    code: string;
    label: string;
    count: number;
  }[];
  recentActivities: ActivitySummary[];
}

export interface QualityRecord {
  activity: ActivitySummary;
  scenes: SceneResult[];
}

export interface Quality {
  startedPractices: number;
  helpExecutionCount: number;
  retryCount: number;
  acceptance: {
    accepted: number;
    declined: number;
    unclear: number;
    requested: number;
    unanswered: number;
    rate: number | null;
  };
  helpTypes: {
    kind: HelpKind;
    count: number;
  }[];
  focusExamples: number;
  records: QualityRecord[];
}

export type CostCategory = "ACTIVITY" | "FAILED_TO_START" | "NON_ACTIVITY" | "UNLINKED";
export type CostPhaseName = "PREPARATION" | "VOICE" | "JUDGMENT" | "POST_PROCESSING" | "OTHER";
export interface CostPhase {
  phase: CostPhaseName;
  model: string;
  calls: number;
  voiceSeconds: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  cachedInputTokens: number | null;
  cacheWriteTokens: number | null;
  reasoningTokens: number | null;
  estimatedUsd: number | null;
  status: CollectionStatus;
  /** 서버가 제공하는 표시용 계산 설명이다. 브라우저는 단가를 적용하지 않는다. */
  calculation: string | null;
}

export interface CostSummary {
  id: string;
  activityId: string | null;
  childName: string | null;
  type: ActivityType | null;
  category: CostCategory;
  purpose: string;
  observedAt: string;
  status: CollectionStatus;
  voiceSeconds: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  estimatedUsd: number | null;
}

export interface CostDetail extends CostSummary {
  phases: CostPhase[];
  scope: string;
}

export interface Costs {
  totals: {
    collectedUsd: number | null;
    activityUsd: number | null;
    failedStartUsd: number | null;
    nonActivityUsd: number | null;
    unlinkedUsd: number | null;
    unknownCount: number;
  };
  activities: Page<CostSummary>;
  otherCosts: CostSummary[];
  scope: string;
}

export interface AdminDataSource {
  me(signal?: AbortSignal): Promise<AdminIdentity>;
  overview(filter: AdminFilter, signal?: AbortSignal): Promise<Overview>;
  activities(filter: AdminFilter, page: number, signal?: AbortSignal): Promise<Page<ActivitySummary>>;
  activity(id: string, signal?: AbortSignal): Promise<ActivityDetail>;
  guardians(filter: AdminFilter, page: number, signal?: AbortSignal): Promise<Page<GuardianSummary>>;
  quality(filter: AdminFilter, signal?: AbortSignal): Promise<Quality>;
  costs(filter: AdminFilter, page: number, signal?: AbortSignal): Promise<Costs>;
  costDetail(id: string, signal?: AbortSignal): Promise<CostDetail>;
}
