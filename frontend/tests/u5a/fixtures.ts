import type { GoalSelection } from "@/parent/features/practice/api";

export const G1 = "10000000-0000-4000-8000-000000000001";
export const G2 = "10000000-0000-4000-8000-000000000002";
export const G3 = "10000000-0000-4000-8000-000000000003";
export const PATH = "/api/goal-selections";
export const RESTORE_UNTIL = "2026-10-12T06:00:05Z";

export function goal(overrides: Partial<GoalSelection> = {}): GoalSelection {
  return {
    goalSelectionId: G1,
    displayName: "친구에게 부탁하기",
    isFavorite: false,
    sortOrder: 1,
    selectedAt: "2026-10-06T00:00:00Z",
    sourceActivityType: "FREE_TALK",
    sourceActivityAt: "2026-10-05T15:30:00Z",
    practiceCount: 2,
    ...overrides,
  };
}

export const GOALS = [
  goal({ goalSelectionId: G2, displayName: "차례 기다리기", sortOrder: 2, sourceActivityType: "PRACTICE", practiceCount: 0 }),
  goal({ goalSelectionId: G3, displayName: "도움 요청하기", sortOrder: 3, isFavorite: true, sourceActivityAt: null, practiceCount: null }),
  goal(),
];
