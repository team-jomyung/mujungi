/** 연습 진행 단계 이름(계약의 stage 값). 단계 문구는 features/practice/copy.ts의 U5B_COPY.stages가 이 이름으로 맞춘다. */
export const PRACTICE_STAGE_NAMES = [
  "SCENARIO_INTRO",
  "FIRST_RESPONSE",
  "HELP_OFFER",
  "CUE",
  "AFTER_CUE_RESPONSE",
  "NEW_SCENARIO_RESPONSE",
] as const;

export type PracticeStage = (typeof PRACTICE_STAGE_NAMES)[number];
export type PracticeProgress = Readonly<{ goalIndex: number; goalCount: number; goalName: string; stage: PracticeStage }>;
export type ProgressIssue = "invalid" | "fetch" | null;

/** 반환 객체에는 계약의 네 필드만 복사한다. 알 수 없는 내부 정보는 상태에 보관하지 않는다. */
export function parsePracticeProgress(value: unknown): PracticeProgress | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  const raw = value as Record<string, unknown>;
  if (typeof raw.goalIndex !== "number" || !Number.isInteger(raw.goalIndex) || raw.goalIndex < 1
      || typeof raw.goalCount !== "number" || !Number.isInteger(raw.goalCount) || raw.goalCount < raw.goalIndex
      || typeof raw.goalName !== "string" || !raw.goalName.trim()
      || typeof raw.stage !== "string" || !(PRACTICE_STAGE_NAMES as readonly string[]).includes(raw.stage)) return null;
  return Object.freeze({ goalIndex: raw.goalIndex, goalCount: raw.goalCount, goalName: raw.goalName, stage: raw.stage as PracticeStage });
}
