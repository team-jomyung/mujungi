import { attachParticle } from "@/shared/copy/particle";
import type { GoalSelection } from "./api";
import type { PracticeStage } from "../../common/api/practiceProgress";

// 연습 화면(S-18)·진행(S-12) 문구를 한 파일에 둔다. PRACTICE_*는 목표 목록(U5a), U5B_COPY는 시작 · 진행(U5b)이다.

/** Screen v1.5j C.S-18.17/18, refined mockups 9-22. */
export const PRACTICE_COPY = {
  title: "이번에 살펴볼 목표",
  favorites: "★ 즐겨찾기",
  saved: "담은 목표",
  empty: "아직 담은 목표가 없어요",
  howTo: "이렇게 해 보세요",
  steps: ["자유대화 탭에서 아이와 이야기해요", "기록 탭에서 그날 기록을 열어요", '"연습 목표" 칸에서 담기를 눌러요'],
  goFree: "자유대화 하러 가기",
  choose: "목표를 골라 주세요",
  undo: "되돌리기",
  favoriteIcon: "★",
  notFavoriteIcon: "☆",
  deleteIcon: "×",
  start: (n: number) => `선택한 ${n}개로 연습 시작`,
} as const;

/** U5a-J2/J4: no invented count or enabled start while integrations are absent. */
export const PRACTICE_TEMP_COPY = {
  startUnavailable: "연습 시작 기능을 준비하고 있어요.",
  loading: "목표를 불러오고 있어요.",
  slowConnection: "연결이 원활하지 않아요",
  loadFailed: "목표를 불러오지 못했어요.",
  favoriteFailed: "즐겨찾기를 저장하지 못했어요. 다시 시도해 주세요.",
  deleteFailed: "삭제하지 못해 목표를 되돌렸어요.",
  verifying: "삭제 결과를 확인하고 있어요.",
  verifyFailed: "목표 상태를 확인하지 못했어요. 다시 확인해 주세요.",
  restoreExpired: "되돌릴 수 있는 시간이 지났어요. 목표 목록을 다시 확인했어요.",
  unknownSource: "담은 기록 확인 중",
  unknownCount: "연습 횟수 확인 중",
  deleted: (name: string) => `${name} 목표를 삭제했어요.`,
  select: (name: string) => `선택 — ${name}`,
  favorite: (name: string, active: boolean) => `즐겨찾기 ${active ? "해제" : "추가"} — ${name}`,
  remove: (name: string) => `삭제 — ${name}`,
  selectedCount: (n: number) => `${n}개 목표 선택`,
} as const;

const MONTH_DAY = new Intl.DateTimeFormat("en-US", { timeZone: "Asia/Seoul", month: "numeric", day: "numeric" });

export function goalMeta(goal: GoalSelection): string {
  const source = goal.sourceActivityAt !== null && (goal.sourceActivityType === "FREE_TALK" || goal.sourceActivityType === "PRACTICE")
    ? `${MONTH_DAY.format(new Date(goal.sourceActivityAt))} ${goal.sourceActivityType === "FREE_TALK" ? "자유대화" : "연습"}에서 담음`
    : PRACTICE_TEMP_COPY.unknownSource;
  const count = goal.practiceCount === null ? PRACTICE_TEMP_COPY.unknownCount : goal.practiceCount === 0 ? "아직 안 함" : `연습 ${goal.practiceCount}회`;
  return `${source} · ${count}`;
}

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
