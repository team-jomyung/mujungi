import type { GoalSelection } from "../../common/api/practice";

// 연습 화면(S-18) 목표 목록 문구(U5a)를 둔다. 시작 · 진행(S-12) 문구 U5B_COPY는 common/copy/practice.ts에 있다.

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
  selectionLimit: "한 번에 목표 1개를 골라 연습해요.",
  undo: "되돌리기",
  favoriteIcon: "★",
  notFavoriteIcon: "☆",
  deleteIcon: "×",
  start: (n: number) => `선택한 ${n}개로 연습 시작`,
} as const;

/** no invented count or enabled start while integrations are absent. */
export const PRACTICE_TEMP_COPY = {
  startUnavailable: "연습 시작 기능을 준비하고 있어요.",
  loading: "목표를 불러오고 있어요.",
  slowConnection: "연결이 원활하지 않아요",
  loadFailed: "목표를 불러오지 못했어요.",
  favoriteFailed: "즐겨찾기를 저장하지 못했어요. 다시 시도해 주세요.",
  deleteFailed: "삭제하지 못해 목표를 되돌렸어요.",
  deleting: "삭제 중이에요.",
  restoreFailed: "목표를 되돌리지 못했어요. 목록을 다시 확인해 주세요.",
  restoreUnavailable: "목표를 되돌릴 수 없어요. 새로고침해 목록을 확인해 주세요.",
  restored: (name: string) => `${name} 목표를 되돌렸어요.`,
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
