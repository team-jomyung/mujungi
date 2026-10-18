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

/** 기존 폴링 어댑터가 세션·조회 세대·상태 버전을 제공할 때 사용하는 독립 보관 부품. */
export class PracticeProgressTracker {
  private generation = -1;
  private version = -1;
  value: PracticeProgress | null = null;
  issue: ProgressIssue = null;
  constructor(private readonly sessionId: string) {}
  apply(sessionId: string, generation: number, version: number, activity: "PRACTICE" | "FREE_TALK", practice: unknown): boolean {
    if (sessionId !== this.sessionId || generation < this.generation || version < this.version
        || !Number.isInteger(generation) || !Number.isInteger(version) || generation < 0 || version < 0) return false;
    this.generation = generation; this.version = version;
    if (activity === "FREE_TALK") { this.value = null; this.issue = null; return true; }
    const parsed = parsePracticeProgress(practice);
    if (!parsed) { this.issue = "invalid"; return true; }
    this.value = parsed; this.issue = null; return true;
  }
  failed(sessionId: string, generation: number): void {
    if (sessionId !== this.sessionId || generation < this.generation || !Number.isInteger(generation)) return;
    this.generation = generation; this.issue = "fetch";
  }
}
