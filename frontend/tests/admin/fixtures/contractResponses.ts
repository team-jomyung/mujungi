// /admin/me 이외의 응답은 admin-* 스키마에 맞춰 작성한 합성 예시다.
// 실제 API 응답이나 계약 문서에 수록된 예시를 복사한 자료는 아니다.
export const activitySummary = {
  id: "11111111-1111-4111-8111-111111111111",
  guardianId: "22222222-2222-4222-8222-222222222222",
  guardianName: "보호자 222222",
  deviceId: "33333333-3333-4333-8333-333333333333",
  childId: "44444444-4444-4444-8444-444444444444",
  childName: "예시 아이",
  type: "PRACTICE",
  status: "COMPLETED",
  endCategory: "ENDED_UNCONFIRMED",
  startedAt: "2026-10-26T01:00:00Z",
  endedAt: "2026-10-26T01:00:45Z",
  durationSeconds: 45,
  childTurns: 1,
  aiTurns: 1,
  endReason: "종료 확인 안 됨",
};

export const scene = {
  id: "55555555-5555-4555-8555-555555555555",
  title: "친구에게 연필 빌리기",
  kind: "BASE",
  judgment: "NOT_EVALUATED",
  reason: null,
  responseTiming: null,
  abstainReason: null,
  helpResponse: null,
  helpOffer: "UNKNOWN",
  variantSkipReason: null,
  retryCount: null,
  reExplanationCount: null,
  helpRecords: [],
};

export const activityDetail = {
  ...activitySummary,
  transcriptAvailable: true,
  transcript: [
    {
      sequence: 1,
      speaker: "AI",
      text: "친구에게 어떻게 부탁하면 좋을까?",
      at: "2026-10-26T01:00:01Z",
    },
    {
      sequence: 2,
      speaker: "CHILD",
      text: "연필 좀 빌려줄래?",
      at: "2026-10-26T01:00:03Z",
    },
  ],
  scenes: [scene],
};

export const activityPage = {
  items: [activitySummary],
  page: 0,
  pageSize: 8,
  total: 1,
};

export const overview = {
  activityCount: 1,
  freeTalkCount: 0,
  practiceCount: 1,
  activeChildren: 1,
  childTurns: 1,
  aiTurns: 1,
  averageSeconds: { all: 45, freeTalk: null, practice: 45 },
  daily: [{ date: "2026-10-26", freeTalk: 0, practice: 1 }],
  endReasons: [{ code: "ENDED_UNCONFIRMED", label: "종료 확인 안 됨(완료로 집계)", count: 1 }],
  recentActivities: [activitySummary],
};

export const guardians = {
  items: [
    {
      id: activitySummary.guardianId,
      name: activitySummary.guardianName,
      activityCount: 1,
      devices: [
        {
          id: activitySummary.deviceId,
          name: "기기 333333",
          children: [{ id: activitySummary.childId, name: activitySummary.childName, activityCount: 1 }],
        },
      ],
    },
  ],
  page: 0,
  pageSize: 8,
  total: 1,
};

export const quality = {
  startedPractices: 125,
  helpExecutionCount: 1,
  retryCount: 0,
  acceptance: {
    accepted: 1,
    declined: 0,
    unclear: 0,
    requested: 0,
    unanswered: null,
    notOffered: 0,
    unknown: 124,
    rate: 1,
  },
  helpTypes: [
    { kind: "FOCUS_HELP", count: 1 },
    { kind: "SITUATION_EXPLAIN", count: 0 },
    { kind: "EXPRESSION_EXAMPLE", count: 0 },
  ],
  focusExamples: 0,
  records: [{ activity: activitySummary, scenes: [scene] }],
  recordsTotal: 125,
  recordsLimit: 100,
};

export const phase = {
  phase: "POST_PROCESSING",
  model: "GPT-6 Luna",
  calls: 3,
  voiceSeconds: null,
  inputTokens: 8192,
  outputTokens: 1024,
  cachedInputTokens: null,
  cacheWriteTokens: null,
  reasoningTokens: 256,
  estimatedUsd: "0.00000049",
  status: "PARTIAL",
  calculation: "스키마 대조용 합성 금액입니다.",
};

export const costSummary = {
  id: activitySummary.id,
  activityId: activitySummary.id,
  childName: activitySummary.childName,
  type: activitySummary.type,
  category: "ACTIVITY",
  purpose: "대화 · 연습",
  observedAt: "2026-10-26T01:02:00Z",
  status: "PARTIAL",
  voiceSeconds: null,
  inputTokens: 8192,
  outputTokens: 1024,
  estimatedUsd: "0.00000049",
};

export const costDetail = {
  ...costSummary,
  phases: [phase],
  scope: "이 활동 전체 기간의 스키마 대조용 합성 예시입니다.",
};

export const costs = {
  totals: {
    collectedUsd: "0.00000049",
    activityUsd: "0.00000049",
    failedStartUsd: "0",
    nonActivityUsd: null,
    unlinkedUsd: null,
    unknownCount: 2,
  },
  activities: { items: [costSummary], page: 0, pageSize: 8, total: 1 },
  otherCosts: [
    {
      id: "66666666-6666-4666-8666-666666666666",
      activityId: null,
      childName: null,
      type: "PRACTICE",
      category: "FAILED_TO_START",
      purpose: "연습 준비",
      observedAt: "2026-10-26T01:03:00Z",
      status: "COMPLETE",
      voiceSeconds: null,
      inputTokens: 0,
      outputTokens: 0,
      estimatedUsd: "0",
    },
  ],
  scope: "수집된 호출 기준의 스키마 대조용 합성 예시입니다.",
};
