// 보호자 웹이 쓰는 Core REST 응답 형식과 읽기 함수 (U1 3.5 Step 39 · 계약 3절 · contracts/schemas · BR4.6 · BR6.15)
// 받는 쪽 규칙(계약 10절): 모르는 필드는 무시하고 모르는 enum 값은 'UNKNOWN'으로 읽어 오류로 끊지 않는다.
// 필수 필드가 없거나 형식이 틀리면 ResponseShapeError(화면은 일반 오류로 처리한다).
// U4a: 04 currentSession · 12 덧붙임(gaps · technicalPause · silenceWarning)은 api/activity.ts가 읽는다.

import { parseLiveExtras, parseSessionSummary, type ActivityKind, type LiveExtras, type SessionSummary } from "./activity";
import { parsePracticeProgress, type PracticeProgress, type ProgressIssue } from "./practiceProgress";

/** 08 서술 칸 상한(activity-free-request.schema.json maxLength). */
export const FREE_TEXT_MAX_LENGTH = 200;

/** 등록 코드 칸 수(BR1.1). */
export const REGISTRATION_CODE_LENGTH = 8;

export class ResponseShapeError extends Error {
  constructor(readonly field: string) {
    super(`응답 형식이 계약과 다르다: ${field}`);
    this.name = "ResponseShapeError";
  }
}

export type DeviceNetworkState = "STABLE" | "UNSTABLE" | "DISCONNECTED" | "UNRESPONSIVE" | "NONE" | "UNKNOWN";

export type LockReasonCode =
  | "NO_DEVICE"
  | "DEVICE_UNSTABLE"
  | "DEVICE_DISCONNECTED"
  | "DEVICE_UNRESPONSIVE"
  | "DEVICE_STATUS_UNKNOWN";

export interface DeviceView {
  readonly registration: {
    readonly status: "NONE" | "REGISTERED" | "UNKNOWN";
    readonly codePrefix: string | null;
    readonly registeredAt: string | null;
  };
  readonly network: {
    readonly state: DeviceNetworkState;
    readonly rttMs: number | null;
    readonly recentDisconnects: number | null;
    readonly since: string | null;
    readonly lastSeenAt: string | null;
    readonly lastReportedAt: string | null;
  };
  readonly verdict: {
    readonly canStart: boolean;
    readonly reasonCode: LockReasonCode | null;
  };
}

export interface ChildSummary {
  readonly childId: string;
  readonly displayName: string;
  readonly age: number;
  readonly interests: readonly string[];
}

export interface HomeView {
  readonly child: ChildSummary | null;
  readonly device: DeviceView;
  readonly lock: { readonly locked: boolean; readonly reasonCode: LockReasonCode | null };
  /** 지금 활동(진행 중 세션 요약 또는 null, U4a · AC1.4.13). */
  readonly currentSession: SessionSummary | null;
}

export type SessionStatus =
  | "REQUESTED"
  | "PREPARING"
  | "DEVICE_ACK"
  | "WAITING_CHILD"
  | "ACTIVE"
  | "CLOSING"
  | "ENDED"
  | "ENDED_UNCONFIRMED"
  | "ABORTED"
  | "DECLINED"
  | "FAILED"
  | "UNKNOWN";

export type EndReason =
  | "GUARDIAN_STOP"
  | "CHILD_STOP"
  | "SILENCE"
  | "DISCONNECT"
  | "CONSENT_WITHDRAWN"
  | "UNCONFIRMED"
  | "DECLINED"
  | "FAILED"
  | "UNKNOWN";

export interface Bubble {
  readonly seq: number;
  readonly speaker: "CHILD" | "AI";
  readonly text: string;
  readonly final: boolean;
  readonly uncertain: boolean;
  readonly startedAt: string;
  readonly endedAt: string | null;
}

export interface LiveView {
  readonly sessionId: string;
  readonly activityType: ActivityKind;
  readonly practice: PracticeProgress | null;
  readonly practiceIssue: ProgressIssue;
  readonly status: SessionStatus;
  readonly endReason: EndReason | null;
  readonly failureCode: string | null;
  readonly confirmed: boolean;
  readonly requestedAt: string | null;
  readonly startedAt: string | null;
  readonly endedAt: string | null;
  readonly statusVersion: number;
  readonly serverTime: string | null;
  readonly observedAt: string | null;
  readonly stale: boolean;
  readonly items: readonly Bubble[];
  readonly nextSeq: number;
  /** 끊김 · 미확인 구간(U4a · AC3.2.10). */
  readonly gaps: LiveExtras["gaps"];
  /** 기술적 일시 정지(U4a · AC6.6.1). */
  readonly technicalPause: LiveExtras["technicalPause"];
  /** 공백 경고(U4a · AC3.2.8). */
  readonly silenceWarning: LiveExtras["silenceWarning"];
}

export interface RegisterResult {
  readonly deviceId: string;
  readonly codePrefix: string;
  readonly registeredAt: string;
}

export interface StartResult {
  readonly sessionId: string;
}

export interface EndResult {
  readonly status: "CLOSING" | "UNKNOWN";
}

export interface CsrfToken {
  readonly token: string;
  readonly headerName: string;
}

export interface DevLoginResult {
  readonly guardianId: string;
}

export type SituationType = "FRIEND" | "SCHOOL" | "FAMILY" | "ACADEMY" | "OTHER";
export type SourceType = "SEEN" | "HEARD_FROM_CHILD";

export interface FreeTalkContext {
  readonly knownContent?: string;
  readonly situationType?: SituationType;
  readonly sourceType?: SourceType;
  readonly question?: string;
}

// ───────────── 읽기 도우미 ─────────────

type Json = Record<string, unknown>;

function asObject(value: unknown, field: string): Json {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new ResponseShapeError(field);
  }
  return value as Json;
}

function requiredString(object: Json, field: string): string {
  const value = object[field];
  if (typeof value !== "string" || value.length === 0) {
    throw new ResponseShapeError(field);
  }
  return value;
}

function optionalString(object: Json, field: string): string | null {
  const value = object[field];
  return typeof value === "string" && value.length > 0 ? value : null;
}

function optionalNumber(object: Json, field: string): number | null {
  const value = object[field];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function requiredBoolean(object: Json, field: string): boolean {
  const value = object[field];
  if (typeof value !== "boolean") {
    throw new ResponseShapeError(field);
  }
  return value;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[], fallback: T): T {
  return allowed.find((candidate) => candidate === value) ?? fallback;
}

const NETWORK_STATES: readonly DeviceNetworkState[] = ["STABLE", "UNSTABLE", "DISCONNECTED", "UNRESPONSIVE", "NONE", "UNKNOWN"];
const LOCK_REASONS: readonly LockReasonCode[] = [
  "NO_DEVICE",
  "DEVICE_UNSTABLE",
  "DEVICE_DISCONNECTED",
  "DEVICE_UNRESPONSIVE",
  "DEVICE_STATUS_UNKNOWN",
];
const SESSION_STATUSES: readonly SessionStatus[] = [
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
  "UNKNOWN",
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
  "UNKNOWN",
];

function readLockReason(value: unknown): LockReasonCode | null {
  if (value === null || value === undefined) {
    return null;
  }
  // 모르는 사유도 잠금 이유로는 '확인 불가'로 읽는다(닫힌 쪽).
  return oneOf(value, LOCK_REASONS, "DEVICE_STATUS_UNKNOWN");
}

// ───────────── 응답 읽기 ─────────────

export function parseDeviceView(value: unknown): DeviceView {
  const root = asObject(value, "device");
  const registration = asObject(root.registration, "device.registration");
  const network = asObject(root.network, "device.network");
  const verdict = asObject(root.verdict, "device.verdict");
  const canStart = requiredBoolean(verdict, "canStart");
  const reasonCode = readLockReason(verdict.reasonCode);
  return {
    registration: {
      status: oneOf(registration.status, ["NONE", "REGISTERED"] as const, "UNKNOWN"),
      codePrefix: optionalString(registration, "codePrefix"),
      registeredAt: optionalString(registration, "registeredAt"),
    },
    network: {
      state: oneOf(network.state, NETWORK_STATES, "UNKNOWN"),
      rttMs: optionalNumber(network, "rttMs"),
      recentDisconnects: optionalNumber(network, "recentDisconnects"),
      since: optionalString(network, "since"),
      lastSeenAt: optionalString(network, "lastSeenAt"),
      lastReportedAt: optionalString(network, "lastReportedAt"),
    },
    verdict: { canStart, reasonCode: canStart ? null : (reasonCode ?? "DEVICE_STATUS_UNKNOWN") },
  };
}

function parseChild(value: unknown): ChildSummary | null {
  if (value === null || value === undefined) {
    return null;
  }
  const child = asObject(value, "child");
  const interests = Array.isArray(child.interests) ? child.interests.filter((item): item is string => typeof item === "string") : [];
  return {
    childId: requiredString(child, "childId"),
    displayName: requiredString(child, "displayName"),
    age: optionalNumber(child, "age") ?? 0,
    interests,
  };
}

export function parseHome(value: unknown): HomeView {
  const root = asObject(value, "home");
  const device = parseDeviceView(root.device);
  const lock = asObject(root.lock, "lock");
  const locked = requiredBoolean(lock, "locked");
  return {
    child: parseChild(root.child),
    device,
    lock: { locked, reasonCode: locked ? (readLockReason(lock.reasonCode) ?? "DEVICE_STATUS_UNKNOWN") : null },
    currentSession: parseSessionSummary(root.currentSession),
  };
}

function parseBubble(value: unknown): Bubble | null {
  if (typeof value !== "object" || value === null) {
    return null;
  }
  const item = value as Json;
  const seq = item.seq;
  const speaker = item.speaker;
  if (typeof seq !== "number" || !Number.isInteger(seq) || seq < 1) {
    return null;
  }
  if (speaker !== "CHILD" && speaker !== "AI") {
    return null;
  }
  if (typeof item.text !== "string") {
    return null;
  }
  return {
    seq,
    speaker,
    text: item.text,
    final: item.final === true,
    uncertain: item.uncertain === true,
    startedAt: typeof item.startedAt === "string" ? item.startedAt : "",
    endedAt: optionalString(item, "endedAt"),
  };
}

export function parseLiveView(value: unknown): LiveView {
  const root = asObject(value, "liveView");
  const items = Array.isArray(root.items) ? root.items.map(parseBubble).filter((item): item is Bubble => item !== null) : [];
  const nextSeq = optionalNumber(root, "nextSeq");
  const endReason = root.endReason === null || root.endReason === undefined ? null : oneOf(root.endReason, END_REASONS, "UNKNOWN");
  const activityType = oneOf<ActivityKind>(root.activityType, ["FREE_TALK", "PRACTICE"], "UNKNOWN");
  const practice = activityType === "PRACTICE" ? parsePracticeProgress(root.practice) : null;
  return {
    sessionId: requiredString(root, "sessionId"),
    activityType,
    practice,
    practiceIssue: activityType === "PRACTICE" && root.status === "ACTIVE" && practice === null ? "invalid" : null,
    status: oneOf(root.status, SESSION_STATUSES, "UNKNOWN"),
    endReason,
    failureCode: optionalString(root, "failureCode"),
    confirmed: root.confirmed === true,
    requestedAt: optionalString(root, "requestedAt"),
    startedAt: optionalString(root, "startedAt"),
    endedAt: optionalString(root, "endedAt"),
    statusVersion: optionalNumber(root, "statusVersion") ?? 0,
    serverTime: optionalString(root, "serverTime"),
    observedAt: optionalString(root, "observedAt"),
    stale: root.stale === true,
    items,
    nextSeq: nextSeq !== null && Number.isInteger(nextSeq) && nextSeq >= 0 ? nextSeq : 0,
    ...parseLiveExtras(root),
  };
}

export function parseRegisterResult(value: unknown): RegisterResult {
  const root = asObject(value, "register");
  return {
    deviceId: requiredString(root, "deviceId"),
    codePrefix: requiredString(root, "codePrefix"),
    registeredAt: requiredString(root, "registeredAt"),
  };
}

export function parseStartResult(value: unknown): StartResult {
  const root = asObject(value, "start");
  return { sessionId: requiredString(root, "sessionId") };
}

export function parseEndResult(value: unknown): EndResult {
  const root = asObject(value, "end");
  return { status: root.status === "CLOSING" ? "CLOSING" : "UNKNOWN" };
}

export function parseCsrfToken(value: unknown): CsrfToken {
  const root = asObject(value, "csrf");
  return { token: requiredString(root, "token"), headerName: requiredString(root, "headerName") };
}

export function parseDevLogin(value: unknown): DevLoginResult {
  const root = asObject(value, "devLogin");
  return { guardianId: requiredString(root, "guardianId") };
}

/** 세션이 끝난 상태인지(12 폴링 멈춤 · 17 판정). */
export function isTerminalStatus(status: SessionStatus): boolean {
  return (
    status === "ENDED" ||
    status === "ENDED_UNCONFIRMED" ||
    status === "ABORTED" ||
    status === "DECLINED" ||
    status === "FAILED"
  );
}
