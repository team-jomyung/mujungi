import { attachParticle } from "@/shared/copy/particle";
import type { PracticeStage } from "../api/practiceProgress";

// 연습 시작 · 진행(S-12) 문구. 연습 화면과 진행 화면이 함께 쓰므로 공통에 둔다. 목표 목록 문구는 features/practice/copy.ts에 있다.

/** S12 · S18 및 refined-mockups 9-12/9-14. 진행 확인 문구는 U5b 통합 가정이다. */
export const U5B_COPY = {
  title: "연습",
  region: "연습 진행",
  endButton: "연습 끝내기",
  practicing: (childName: string) => `${attachParticle(childName, "이/가")} 연습하고 있어요`,
  heading: (goalIndex: number, goalCount: number, goalName: string) =>
    `연습 · ${goalIndex}/${goalCount}번째 목표 · ${goalName}`,
  sending: "연습 시작을 요청하고 있어요.",
  unknown: "시작 결과를 확인하지 못했어요. 같은 요청으로 다시 확인해 주세요.",
  invalidProgress: "현재 연습 단계를 확인할 수 없어요.",
  fetchFailed: "진행 정보를 갱신하지 못했어요. 마지막 확인 내용을 표시해요.",
  rejected: {
    SCENARIO_GEN_FAILED: "연습 상황을 만들지 못했어요. 잠시 뒤 다시 시도해 주세요.",
    SESSION_IN_PROGRESS: "진행 중인 활동이 있어요. 끝난 뒤 시작할 수 있어요.",
    DEVICE_NOT_STABLE: "기기가 안정될 때까지 시작할 수 없어요",
    CONSENT_MISSING: "동의를 먼저 마쳐 주세요",
    IDEMPOTENCY_CONFLICT: "시작 요청을 확인할 수 없어요. 기존 요청 상태를 확인해 주세요.",
  },
  otherRejected: "연습을 시작하지 못했어요. 다시 확인해 주세요.",
  stages: {
    SCENARIO_INTRO: "상황 듣는 중",
    FIRST_RESPONSE: "먼저 말해 보는 중",
    HELP_OFFER: "도움 받는 중",
    CUE: "도움 받는 중",
    AFTER_CUE_RESPONSE: "다시 말해 보는 중",
    NEW_SCENARIO_RESPONSE: "새 상황",
  } satisfies Readonly<Record<PracticeStage, string>>,
} as const;
