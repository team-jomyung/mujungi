import { dateInRange, shiftDate, validDate, validRange } from "./dates";
import { AdminDataError, assertNotAborted } from "./errors";
import type {
  ActivityDetail,
  ActivitySummary,
  AdminDataSource,
  AdminFilter,
  ConsentAnswer,
  CostDetail,
  CostPhase,
  CostSummary,
  GuardianSummary,
  Page,
  Quality,
  SceneResult,
} from "./types";
const PAGE_SIZE = 8;
const SCOPE = "GPT-Live · Luna 사용량과 USD 금액의 합성 예시입니다. 제브 비용은 제외합니다. 실제 청구액과 다를 수 있습니다.";
const lifetimeScope = "이 활동의 준비부터 종료 후 처리까지 누적한 사용량입니다. 기간 목록은 API 호출 완료 시각(한국 시간)을 기준으로 하므로 상세 합계와 다를 수 있습니다.";
function pageOf<T>(items: T[], page: number): Page<T> {
  const current = Number.isInteger(page) && page >= 0 ? page : 0;
  return {
    items: items.slice(current * PAGE_SIZE, (current + 1) * PAGE_SIZE),
    page: current,
    pageSize: PAGE_SIZE,
    total: items.length
  };
}

function summary(activity: ActivityDetail): ActivitySummary {
  return {
    id: activity.id,
    guardianId: activity.guardianId,
    guardianName: activity.guardianName,
    deviceId: activity.deviceId,
    childId: activity.childId,
    childName: activity.childName,
    type: activity.type,
    status: activity.status,
    startedAt: activity.startedAt,
    endedAt: activity.endedAt,
    durationSeconds: activity.durationSeconds,
    childTurns: activity.childTurns,
    aiTurns: activity.aiTurns,
    endReason: activity.endReason
  };
}

const knownSum = (values: (number | null)[]): number | null => {
  const known = values.filter((value): value is number => value !== null);
  return known.length ? known.reduce((sum, value) => sum + value, 0) : null;
};

const average = (items: ActivityDetail[]): number | null => {
  const seconds = items.map(item => item.durationSeconds).filter((value): value is number => value !== null);
  return seconds.length ? Math.round(seconds.reduce((sum, value) => sum + value, 0) / seconds.length) : null;
};

function matches(activity: ActivitySummary, filter: AdminFilter, includeDate = true): boolean {
  const query = filter.query?.trim().toLowerCase() ?? "";
  const searchFields = [
    activity.id,
    activity.childName,
    activity.guardianName,
    activity.guardianId,
    activity.deviceId,
  ];
  return (!includeDate || dateInRange(activity.startedAt, filter.from, filter.to))
    && (filter.type === "ALL" || activity.type === filter.type)
    && (!filter.status || filter.status === "ALL" || activity.status === filter.status)
    && (!filter.guardianId || activity.guardianId === filter.guardianId)
    && (!filter.childId || activity.childId === filter.childId)
    && (!query || searchFields.some(value => value.toLowerCase().includes(query)));
}

function makeScene(index: number, scene: number): SceneResult {
  const answers: ConsentAnswer[] = ["ACCEPT", "DECLINE", "REQUESTED", "UNCLEAR", null];
  const answer = answers[(index + scene) % answers.length];
  const help = answer === "ACCEPT" || answer === "REQUESTED";
  const abstained = (index + scene) % 7 === 0;
  const failed = (index + scene) % 6 === 0;
  return {
    id: `SC-${index}-${scene}`,
    title: scene ? "다른 상황에서 부탁하기" : "친구에게 연필 빌리기",
    kind: scene ? "VARIANT" : "BASE",
    judgment: abstained ? "ABSTAINED" : failed ? "FAILURE" : "SUCCESS",
    reason: abstained ? null : failed ? "장면의 목표 표현이 확인되지 않았습니다." : "상대에게 필요한 것을 말하고 부탁하는 표현이 확인됐습니다.",
    responseTiming: help ? "AFTER_HELP" : "IMMEDIATE",
    abstainReason: abstained ? "LOW_CONFIDENCE" : null,
    helpResponse: answer,
    variantSkipReason: !scene && abstained ? "TIME_LIMIT" : null,
    retryCount: help ? 1 : 0,
    reExplanationCount: help && index % 3 === 0 ? 1 : 0,
    helpRecords: help ? [
      {
        id: `H-${index}-${scene}`,
        kind: "FOCUS_HELP",
        consentAnswer: answer,
        exampleGiven: index % 2 === 0
      },
      ...(index % 3 === 0 ? [{
          id: `H-${index}-${scene}-explain`,
          kind: "SITUATION_EXPLAIN" as const,
          consentAnswer: null,
          exampleGiven: false
        }] : []),
      ...(index % 4 === 0 ? [{
          id: `H-${index}-${scene}-example`,
          kind: "EXPRESSION_EXAMPLE" as const,
          consentAnswer: null,
          exampleGiven: false
        }] : []),
    ] : []
  };
}
/** 로컬 합성 데이터다. 운영 환경과 HTTP 오류 대체 경로에서는 사용할 수 없다. */
export function createMockAdminSource(anchorDate: string, options: {
  empty?: boolean;
} = {}): AdminDataSource {
  if (process.env.NODE_ENV === "production")
    throw new AdminDataError("notReady");
  if (!validDate(anchorDate))
    throw new Error("미리보기 기준일은 올바른 날짜여야 합니다.");
  const activities: ActivityDetail[] = [];
  const costs: CostDetail[] = [];
  for (let index = 0; index < (options.empty ? 0 : 42); index += 1) {
    const day = shiftDate(anchorDate, -Math.floor(index / 3));
    const child = index % 6 + 1;
    const guardian = Math.ceil(child / 2);
    const type = index % 3 === 1 ? "PRACTICE" : "FREE_TALK";
    const failed = type === "PRACTICE" && index % 10 === 1;
    const interrupted = !failed && index % 8 === 0;
    const seconds = failed ? null : [343, 517, 246, 720, 984, 420][index % 6];
    const startedAt = `${day}T${String(9 + index % 3 * 3).padStart(2, "0")}:20:00+09:00`;
    const endedAt = new Date(new Date(startedAt).getTime() + (seconds ?? 25) * 1000).toISOString();
    const scenes = type === "PRACTICE" && !failed ? [makeScene(index, 0), makeScene(index, 1)] : [];
    if (scenes[0]?.variantSkipReason)
      scenes.splice(1);
    const id = `MJ-${1094 - index}`;
    const childLines = type === "PRACTICE"
      ? ["연필이 없어서 친구에게 빌리고 싶어.", "연필 좀 빌려줄래?", "고마워. 다 쓰고 돌려줄게."]
      : ["오늘 친구랑 그림을 그렸어.", "커다란 바다랑 물고기를 그렸어.", "파란색을 같이 써서 재미있었어."];
    const aiLines = type === "PRACTICE"
      ? ["친구가 옆에 있어. 어떻게 말하면 좋을까?", "친구에게 필요한 것을 잘 말했네.", "빌린 다음에 할 말도 생각했구나."]
      : ["오늘 어떤 일이 있었는지 들려줄래?", "어떤 그림을 그렸어?", "친구와 함께 그리니까 즐거웠겠네."];
    const transcript = failed ? [] : aiLines.flatMap((text, line) => [
      {
        sequence: line * 2 + 1,
        speaker: "AI" as const,
        text,
        at: new Date(new Date(startedAt).getTime() + line * 60000).toISOString()
      },
      {
        sequence: line * 2 + 2,
        speaker: "CHILD" as const,
        text: childLines[line],
        at: new Date(new Date(startedAt).getTime() + line * 60000 + 15000).toISOString()
      },
    ]);
    const activity: ActivityDetail = {
      id,
      guardianId: `G-00${guardian}`,
      guardianName: `보호자 ${String(guardian).padStart(2, "0")}`,
      deviceId: `D-${200 + child}`,
      childId: `C-00${child}`,
      childName: `아이 ${String(child).padStart(2, "0")}`,
      type,
      status: failed ? "FAILED_TO_START" : interrupted ? "INTERRUPTED" : "COMPLETED",
      startedAt,
      endedAt,
      durationSeconds: seconds,
      childTurns: failed ? 0 : 3,
      aiTurns: failed ? 0 : 3,
      endReason: failed ? "연습 준비 실패" : interrupted ? "기기 연결 끊김" : index % 2 ? "보호자 종료" : "아이 종료 표현",
      transcript,
      transcriptAvailable: !failed,
      scenes
    };
    activities.push(activity);
    const status = index > 35 ? "NOT_COLLECTED" : index % 11 === 0 ? "UNKNOWN" : index % 5 === 0 ? "PARTIAL" : "COMPLETE";
    const unknown = status === "UNKNOWN" || status === "NOT_COLLECTED";
    const emptyUsage = {
      voiceSeconds: null,
      inputTokens: null,
      outputTokens: null,
      cachedInputTokens: null,
      cacheWriteTokens: null,
      reasoningTokens: null
    };
    const phases: CostPhase[] = [];
    if (type === "PRACTICE")
      phases.push({
        ...emptyUsage,
        phase: "PREPARATION",
        model: "GPT-6 Luna",
        calls: failed ? 3 : 2,
        inputTokens: unknown ? null : 6400,
        outputTokens: unknown ? null : 2100,
        cachedInputTokens: unknown ? null : 1600,
        cacheWriteTokens: unknown ? null : 1200,
        reasoningTokens: unknown ? null : 300,
        estimatedUsd: unknown ? null : 0.0021,
        status,
        calculation: unknown ? null : "합성 예시 금액입니다. 실제 단가를 적용한 청구 내역이 아닙니다."
      });
    if (!failed) {
      phases.push({
        ...emptyUsage,
        phase: "VOICE",
        model: "GPT-Live",
        calls: 1,
        voiceSeconds: unknown ? null : seconds,
        estimatedUsd: unknown || seconds === null ? null : seconds / 60 * 0.05,
        status,
        calculation: unknown ? null : "합성 예시: 연결 초 ÷ 60 × $0.05/분. 실제 적용 단가가 아닙니다."
      });
      if (type === "PRACTICE")
        phases.push({
          ...emptyUsage,
          phase: "JUDGMENT",
          model: "Luna · Decisions",
          calls: 4,
          inputTokens: unknown ? null : 8300,
          outputTokens: unknown ? null : 1600,
          cachedInputTokens: unknown ? null : 2048,
          cacheWriteTokens: null,
          reasoningTokens: unknown ? null : 800,
          estimatedUsd: unknown ? null : 0.0014,
          status,
          calculation: null
        });
      phases.push({
        ...emptyUsage,
        phase: "POST_PROCESSING",
        model: "GPT-6 Luna",
        calls: 3,
        inputTokens: unknown ? null : 10800,
        outputTokens: unknown ? null : 2400,
        cachedInputTokens: unknown ? null : 3072,
        cacheWriteTokens: unknown ? null : 1200,
        reasoningTokens: unknown ? null : 500,
        estimatedUsd: unknown ? null : 0.0024,
        status,
        calculation: null
      });
    }
    costs.push({
      id: `COST-${id}`,
      activityId: id,
      childName: activity.childName,
      type,
      category: failed ? "FAILED_TO_START" : "ACTIVITY",
      purpose: failed ? "연습 준비" : "대화 · 연습",
      observedAt: endedAt,
      status,
      voiceSeconds: unknown || failed ? null : seconds,
      inputTokens: knownSum(phases.map(phase => phase.inputTokens)),
      outputTokens: knownSum(phases.map(phase => phase.outputTokens)),
      estimatedUsd: knownSum(phases.map(phase => phase.estimatedUsd)),
      phases,
      scope: lifetimeScope
    });
  }
  if (!options.empty)
    costs.push({
      id: "COST-OTHER-01",
      activityId: null,
      childName: null,
      type: null,
      category: "NON_ACTIVITY",
      purpose: "활동 외 유지 작업",
      observedAt: `${anchorDate}T10:40:00+09:00`,
      status: "COMPLETE",
      voiceSeconds: null,
      inputTokens: 4096,
      outputTokens: 1000,
      estimatedUsd: 0.0009,
      phases: [{
          phase: "OTHER",
          model: "GPT-6 Luna",
          calls: 1,
          voiceSeconds: null,
          inputTokens: 4096,
          outputTokens: 1000,
          cachedInputTokens: 0,
          cacheWriteTokens: 0,
          reasoningTokens: 200,
          estimatedUsd: 0.0009,
          status: "COMPLETE",
          calculation: null
        }],
      scope: "활동과 연결되지 않는 호출을 별도로 집계한 합성 예시입니다."
    });
  if (!options.empty)
    costs.push({
      id: "COST-UNLINKED-01",
      activityId: null,
      childName: null,
      type: null,
      category: "UNLINKED",
      purpose: "활동 연결 미확인",
      observedAt: `${anchorDate}T11:40:00+09:00`,
      status: "UNKNOWN",
      voiceSeconds: null,
      inputTokens: null,
      outputTokens: null,
      estimatedUsd: null,
      phases: [],
      scope: "연결 정보 또는 사용량이 확인되지 않은 합성 예시입니다. $0.00으로 합산하지 않습니다."
    });
  activities.sort((a, b) => Date.parse(b.startedAt) - Date.parse(a.startedAt));
  costs.sort((a, b) => Date.parse(b.observedAt) - Date.parse(a.observedAt));
  const respond = async <T>(value: T, signal?: AbortSignal): Promise<T> => {
    assertNotAborted(signal);
    await Promise.resolve();
    assertNotAborted(signal);
    return structuredClone(value);
  };
  const validateFilter = (filter: AdminFilter) => {
    if (!validRange(filter.from, filter.to, anchorDate))
      throw new RangeError("미리보기 조회 기간은 올바른 날짜로 최대 366일까지 가능합니다.");
  };
  const filtered = (filter: AdminFilter) => {
    validateFilter(filter);
    return activities.filter(item => matches(item, filter));
  };
  return {
    me: signal => respond({
      displayName: "개발팀 · 미리보기",
      role: "ADMIN"
    }, signal),
    overview: (filter, signal) => {
      const items = filtered(filter);
      const started = items.filter(item => item.status !== "FAILED_TO_START");
      const free = started.filter(item => item.type === "FREE_TALK");
      const practice = started.filter(item => item.type === "PRACTICE");
      const daily: {
        date: string;
        freeTalk: number;
        practice: number;
      }[] = [];
      // 합성 기록이 없는 날도 조회 기간의 일별 차트에서 생략하지 않는다.
      for (let date = filter.from; date <= filter.to; date = shiftDate(date, 1))
        daily.push({
          date,
          freeTalk: free.filter(item => dateInRange(item.startedAt, date, date)).length,
          practice: practice.filter(item => dateInRange(item.startedAt, date, date)).length
        });
      return respond({
        activityCount: started.length,
        freeTalkCount: free.length,
        practiceCount: practice.length,
        activeChildren: new Set(started.map(item => item.childId)).size,
        childTurns: knownSum(started.map(item => item.childTurns)),
        aiTurns: knownSum(started.map(item => item.aiTurns)),
        averageSeconds: {
          all: average(started),
          freeTalk: average(free),
          practice: average(practice)
        },
        daily,
        endReasons: [{
            code: "COMPLETED",
            label: "정상 종료",
            count: items.filter(item => item.status === "COMPLETED").length
          }, {
            code: "INTERRUPTED",
            label: "연결 중단",
            count: items.filter(item => item.status === "INTERRUPTED").length
          }, {
            code: "FAILED_TO_START",
            label: "시작 실패",
            count: items.filter(item => item.status === "FAILED_TO_START").length
          }],
        recentActivities: items.slice(0, 5).map(summary)
      }, signal);
    },
    activities: (filter, page, signal) => respond(pageOf(filtered(filter).map(summary), page), signal),
    activity: async (id, signal) => {
      const item = activities.find(item => item.id === id);
      if (!item)
        throw new AdminDataError("http", 404);
      return respond(item, signal);
    },
    guardians: (filter, page, signal) => {
      const items = filtered(filter);
      const registered = activities.filter(item => matches(item, {
        ...filter,
        type: "ALL",
        status: "ALL"
      }, false));
      const guardians: GuardianSummary[] = [...new Set(registered.map(item => item.guardianId))].map(id => {
        const own = registered.filter(item => item.guardianId === id);
        return {
          id,
          name: own[0].guardianName,
          activityCount: items.filter(item => item.guardianId === id).length,
          devices: [...new Set(own.map(item => item.deviceId))].map(deviceId => {
            const device = own.filter(item => item.deviceId === deviceId);
            return {
              id: deviceId,
              name: deviceId,
              children: [...new Set(device.map(item => item.childId))].map(childId => ({
                id: childId,
                name: device.find(item => item.childId === childId)!.childName,
                activityCount: items.filter(item => item.childId === childId && item.deviceId === deviceId).length
              }))
            };
          })
        };
      });
      return respond(pageOf(guardians, page), signal);
    },
    quality: (filter, signal) => {
      const items = filtered(filter).filter(item => item.type === "PRACTICE" && item.status !== "FAILED_TO_START");
      const scenes = items.flatMap(item => item.scenes);
      const helps = scenes.flatMap(scene => scene.helpRecords);
      const answers = scenes.map(scene => scene.helpResponse);
      const accepted = answers.filter(value => value === "ACCEPT").length;
      const declined = answers.filter(value => value === "DECLINE").length;
      const quality: Quality = {
        startedPractices: items.length,
        helpExecutionCount: helps.length,
        retryCount: scenes.reduce((sum, scene) => sum + scene.retryCount, 0),
        acceptance: {
          accepted,
          declined,
          unclear: answers.filter(value => value === "UNCLEAR").length,
          requested: answers.filter(value => value === "REQUESTED").length,
          unanswered: answers.filter(value => value === null).length,
          rate: accepted + declined ? accepted / (accepted + declined) : null
        },
        helpTypes: ["FOCUS_HELP", "SITUATION_EXPLAIN", "EXPRESSION_EXAMPLE"].map(kind => ({
          kind: kind as "FOCUS_HELP" | "SITUATION_EXPLAIN" | "EXPRESSION_EXAMPLE",
          count: helps.filter(help => help.kind === kind).length
        })),
        focusExamples: helps.filter(help => help.kind === "FOCUS_HELP" && help.exampleGiven).length,
        records: items.map(item => ({
          activity: summary(item),
          scenes: item.scenes
        }))
      };
      return respond(quality, signal);
    },
    costs: (filter, page, signal) => {
      validateFilter(filter);
      const selected = costs.filter(cost => {
        if (!dateInRange(cost.observedAt, filter.from, filter.to)) return false;
        if (cost.activityId) {
          const activity = activities.find(item => item.id === cost.activityId);
          return activity !== undefined && matches(activity, filter, false);
        }
        return filter.type === "ALL"
          && !filter.childId
          && !filter.guardianId
          && (!filter.status || filter.status === "ALL")
          && (!filter.query || cost.purpose.includes(filter.query));
      });
      const group = (category: CostSummary["category"]) => knownSum(selected.filter(cost => cost.category === category).map(cost => cost.estimatedUsd));
      const toSummary = (cost: CostDetail): CostSummary => ({
        id: cost.id,
        activityId: cost.activityId,
        childName: cost.childName,
        type: cost.type,
        category: cost.category,
        purpose: cost.purpose,
        observedAt: cost.observedAt,
        status: cost.status,
        voiceSeconds: cost.voiceSeconds,
        inputTokens: cost.inputTokens,
        outputTokens: cost.outputTokens,
        estimatedUsd: cost.estimatedUsd
      });
      return respond({
        totals: {
          collectedUsd: knownSum(selected.map(item => item.estimatedUsd)),
          activityUsd: group("ACTIVITY"),
          failedStartUsd: group("FAILED_TO_START"),
          nonActivityUsd: group("NON_ACTIVITY"),
          unlinkedUsd: group("UNLINKED"),
          unknownCount: selected.filter(item => item.estimatedUsd === null).length
        },
        activities: pageOf(selected.filter(cost => cost.activityId !== null).map(toSummary), page),
        otherCosts: selected.filter(cost => cost.activityId === null).map(toSummary),
        scope: SCOPE
      }, signal);
    },
    costDetail: async (id, signal) => {
      const item = costs.find(item => item.id === id);
      if (!item)
        throw new AdminDataError("http", 404);
      return respond(item, signal);
    }
  };
}
