import type { ApiClient } from "./apiClient";
import type { EndReason, SessionStatus } from "./types";

// U4a 활동 세션 응답 읽기 (docs/units/u4a/design.md 4절 · 계약 3절 SessionSummary · LiveView · 편차)
// - 04 지금 활동: GET /api/home의 currentSession · GET /api/sessions/current({session}) — 진행 중(REQUESTED ~ CLOSING) 요약 또는 null
// - 12 덧붙임: LiveView의 gaps · technicalPause · silenceWarning(서버가 계약 모양으로 거른 값. 모르는 값은 버린다)
// 이 모듈은 types.ts의 형식만 가져오고(형식 가져오기는 실행 때 사라진다) types.ts가 이 모듈의 읽기 함수를 부른다.

export type ActivityKind = "FREE_TALK" | "PRACTICE" | "UNKNOWN";

export interface SessionSummary {
  readonly sessionId: string;
  readonly activityType: ActivityKind;
  readonly status: SessionStatus;
  readonly endReason: EndReason | null;
  readonly failureCode: string | null;
  readonly confirmed: boolean;
  readonly requestedAt: string | null;
  readonly startedAt: string | null;
  readonly endedAt: string | null;
}

export interface LiveGap {
  readonly from: string;
  /** 진행 중인 끊김이면 null. */
  readonly to: string | null;
  readonly kind: "DISCONNECT" | "UNCONFIRMED";
}

export interface TechnicalPause {
  readonly kind: "DEVICE" | "PROVIDER";
  readonly since: string;
}

export interface SilenceWarning {
  readonly silentSince: string;
  readonly endsAt: string;
}

export interface LiveExtras {
  readonly gaps: readonly LiveGap[];
  readonly technicalPause: TechnicalPause | null;
  readonly silenceWarning: SilenceWarning | null;
}

type Json = Record<string, unknown>;

const STATUSES: readonly SessionStatus[] = [
  "REQUESTED",
  "PREPARING",
  "DEVICE_ACK",
  "WAITING_CHILD",
  "ACTIVE",
  "CLOSING",
  "ENDED",
  "ENDED_UNCONFIRMED",
  "ABORTED",
  "DECLINED",
  "FAILED",
];
const END_REASONS: readonly EndReason[] = [
  "GUARDIAN_STOP",
  "CHILD_STOP",
  "SILENCE",
  "DISCONNECT",
  "CONSENT_WITHDRAWN",
  "UNCONFIRMED",
  "DECLINED",
  "FAILED",
];

/** 진행 중(종료 아님) 상태 — 04 '진행 중' · 탭 이동(AC1.4.10 · AC1.4.13). */
export const IN_PROGRESS_STATUSES: readonly SessionStatus[] = [
  "REQUESTED",
  "PREPARING",
  "DEVICE_ACK",
  "WAITING_CHILD",
  "ACTIVE",
  "CLOSING",
];

function isObject(value: unknown): value is Json {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function text(object: Json, field: string): string | null {
  const value = object[field];
  return typeof value === "string" && value.length > 0 ? value : null;
}

function isTime(value: unknown): value is string {
  return typeof value === "string" && value.length > 0 && !Number.isNaN(Date.parse(value));
}

function parseGap(value: unknown): LiveGap | null {
  if (!isObject(value) || !isTime(value.from)) {
    return null;
  }
  if (value.kind !== "DISCONNECT" && value.kind !== "UNCONFIRMED") {
    return null;
  }
  if (value.to !== null && value.to !== undefined && !isTime(value.to)) {
    return null;
  }
  return { from: value.from, to: isTime(value.to) ? value.to : null, kind: value.kind };
}

/** LiveView 본문에서 12 덧붙임을 읽는다(틀린 항목은 버리고, 없으면 빈 목록 · null). */
export function parseLiveExtras(root: Json): LiveExtras {
  const gaps = Array.isArray(root.gaps) ? root.gaps.map(parseGap).filter((gap): gap is LiveGap => gap !== null) : [];
  const pause = root.technicalPause;
  let technicalPause: TechnicalPause | null = null;
  if (isObject(pause) && isTime(pause.since) && (pause.kind === "DEVICE" || pause.kind === "PROVIDER")) {
    technicalPause = { kind: pause.kind, since: pause.since };
  }
  const warning = root.silenceWarning;
  const silenceWarning =
    isObject(warning) && isTime(warning.silentSince) && isTime(warning.endsAt)
      ? { silentSince: warning.silentSince, endsAt: warning.endsAt }
      : null;
  return { gaps, technicalPause, silenceWarning };
}

/** SessionSummary를 읽는다. null · 모양이 아니면 null(지금 활동 없음으로 다루지 않고 호출자가 판단). */
export function parseSessionSummary(value: unknown): SessionSummary | null {
  if (!isObject(value)) {
    return null;
  }
  const sessionId = text(value, "sessionId");
  if (sessionId === null) {
    return null;
  }
  const status = STATUSES.find((candidate) => candidate === value.status) ?? "UNKNOWN";
  const endReason =
    value.endReason === null || value.endReason === undefined
      ? null
      : (END_REASONS.find((candidate) => candidate === value.endReason) ?? "UNKNOWN");
  const activityType: ActivityKind =
    value.activityType === "FREE_TALK" || value.activityType === "PRACTICE" ? value.activityType : "UNKNOWN";
  return {
    sessionId,
    activityType,
    status,
    endReason,
    failureCode: text(value, "failureCode"),
    confirmed: value.confirmed === true,
    requestedAt: text(value, "requestedAt"),
    startedAt: text(value, "startedAt"),
    endedAt: text(value, "endedAt"),
  };
}

/** GET /api/sessions/current 본문 {session}(편차). */
export function parseCurrentSession(value: unknown): SessionSummary | null {
  return isObject(value) ? parseSessionSummary(value.session) : null;
}

/** GET /api/sessions/current — 04 「다시 확인」 · 알림 진입 재조회(AC1.4.8 · AC1.7.4). */
export function fetchCurrentSession(api: ApiClient, signal?: AbortSignal): Promise<SessionSummary | null> {
  return api.request({ method: "GET", path: "/api/sessions/current", parse: parseCurrentSession, csrf: false, signal });
}

/** 진행 중(REQUESTED ~ CLOSING)인지. */
export function isInProgressStatus(status: SessionStatus): boolean {
  return IN_PROGRESS_STATUSES.includes(status);
}
