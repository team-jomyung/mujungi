/** 독립 U5b 부품 문구. 실제 화면 연결은 I5 합의 후 수행한다. */
export const U5B_COPY = {
  heading: (goalIndex: number, goalCount: number, goalName: string) =>
    `연습 · ${goalIndex}/${goalCount}번째 목표 · ${goalName}`,
  sending: "연습 시작을 요청하고 있어요.",
  unknown: "시작 결과를 확인하지 못했어요. 같은 요청으로 다시 확인해 주세요.",
  invalidProgress: "현재 연습 단계를 확인할 수 없어요.",
  fetchFailed: "진행 정보를 갱신하지 못했어요. 마지막 확인 내용을 표시해요.",
  rejected: {
    SCENARIO_GEN_FAILED: "상황을 준비하지 못했어요. 선택한 목표는 그대로 두었어요.",
    SESSION_IN_PROGRESS: "이미 진행 중인 활동이 있어요.",
    DEVICE_NOT_STABLE: "기기 연결을 확인해 주세요.",
    CONSENT_MISSING: "활동에 필요한 동의를 확인해 주세요.",
    IDEMPOTENCY_CONFLICT: "시작 요청을 확인할 수 없어요. 기존 요청 상태를 확인해 주세요.",
  },
  otherRejected: "연습을 시작하지 못했어요. 다시 확인해 주세요.",
  stages: {
    SCENARIO_INTRO: "상황 안내",
    FIRST_RESPONSE: "첫 반응 기다림",
    HELP_OFFER: "도움 동의 확인",
    CUE: "도움 제공",
    AFTER_CUE_RESPONSE: "도움 후 반응 기다림",
    NEW_SCENARIO_RESPONSE: "새 상황 반응 기다림",
  },
} as const;
