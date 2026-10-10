import { ApiError } from "./api/apiClient";
import type { LockReasonCode } from "./api/types";
import type { TabKey } from "./components/BottomTabBar";
import { PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";

// 보호자 화면 경로 · 탭 이동 · 공통 판단 (U1 3.5 Step 40 · J119 · J57 · BR4.5 · AC1.1.9)
// U1 라우트: /parent(04) · /parent/device(05 스텁 → 04) · /parent/device/register(05a) · /parent/free(08)
//           /parent/sessions/[id](12 + P1) · /parent/sessions/[id]/done(17) · /parent/dev-login(시험 로그인)
//           /parent/practice · /parent/records(스텁 → 04). 401은 시험 로그인으로 보낸다(01 화면은 U2).

export const PARENT_ROUTES = {
  home: "/parent",
  device: "/parent/device",
  register: "/parent/device/register",
  free: "/parent/free",
  freeFailed: "/parent/free?failed=1",
  practice: "/parent/practice",
  records: "/parent/records",
  devLogin: "/parent/dev-login",
  session: (sessionId: string) => `/parent/sessions/${encodeURIComponent(sessionId)}`,
  sessionDone: (sessionId: string) => `/parent/sessions/${encodeURIComponent(sessionId)}/done`,
} as const;

/** 탭 → 경로(연습 · 기록은 U5 · U6 전까지 04로 되돌리는 스텁). */
export function routeForTab(tab: TabKey): string {
  switch (tab) {
    case "free":
      return PARENT_ROUTES.free;
    case "practice":
      return PARENT_ROUTES.practice;
    case "records":
      return PARENT_ROUTES.records;
    default:
      return PARENT_ROUTES.home;
  }
}

/** 잠긴 탭을 눌렀을 때의 이유 토스트(mockups 9-6, 확인 불가는 J118 임시 문구). */
export function lockToastMessage(reason: LockReasonCode | null): string {
  switch (reason) {
    case "NO_DEVICE":
      return PROPOSED_COPY.lockToast.NO_DEVICE;
    case "DEVICE_UNSTABLE":
      return PROPOSED_COPY.lockToast.DEVICE_UNSTABLE;
    case "DEVICE_DISCONNECTED":
      return PROPOSED_COPY.lockToast.DEVICE_DISCONNECTED;
    case "DEVICE_UNRESPONSIVE":
      return PROPOSED_COPY.lockToast.DEVICE_UNRESPONSIVE;
    default:
      return TEMP_COPY.lockToastStatusUnknown;
  }
}

/** 401(로그인 필요 · 만료)인지. */
export function isUnauthenticated(error: unknown): boolean {
  return error instanceof ApiError && error.isUnauthenticated;
}
