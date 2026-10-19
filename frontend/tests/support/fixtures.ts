import { defaultTiming, type Timing } from "@/shared/timing/timing";

// U1 프런트 시험 공통 픽스처 (U1 3.5 Step 41 · 42)
// 모든 값은 합성 값이다(개인정보 · 비밀 없음). 시각은 UTC이고 화면 표시는 Asia/Seoul(+9시간)이다.

export const SESSION_ID = "00000000-0000-4000-8000-000000000020";
export const OTHER_SESSION_ID = "00000000-0000-4000-8000-000000000021";
export const CHILD_ID = "00000000-0000-4000-8000-000000000010";
export const DEVICE_ID = "00000000-0000-4000-8000-000000000030";
export const CONTENT_VERSION_ID = "00000000-0000-4000-8000-000000000040";
export const OP_ID = "00000000-0000-4000-8000-000000000070";
export const PLAYBACK_ID = "00000000-0000-4000-8000-000000000080";
export const CONNECTION_ID = "00000000-0000-4000-8000-000000000060";
export const TRACE_ID = "0123456789abcdef0123456789abcdef";

/** 시험용 CSRF 토큰(실행마다 다름, 비밀 아님). */
export function makeCsrfToken(): string {
  return `t-${crypto.randomUUID()}`;
}

/** 기본값 위에 줄인 시간 값을 덮는다(TimingProvider 주입, NFR6.2). */
export function testTiming(overrides: Partial<Timing> = {}): Timing {
  return { ...defaultTiming(), ...overrides };
}

export function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

/** 계약 2절 오류 봉투. */
export function errorResponse(status: number, code: string): Response {
  return jsonResponse(status, { code, message: "m", traceId: TRACE_ID });
}

export type NetworkState = "STABLE" | "UNSTABLE" | "DISCONNECTED" | "UNRESPONSIVE" | "NONE" | "UNKNOWN";

export interface HomeBodyOptions {
  readonly childName?: string | null;
  readonly lastReportedAt?: string | null;
  readonly lastSeenAt?: string | null;
}

const LOCK_REASON_BY_STATE: Readonly<Record<NetworkState, string | null>> = {
  STABLE: null,
  UNSTABLE: "DEVICE_UNSTABLE",
  DISCONNECTED: "DEVICE_DISCONNECTED",
  UNRESPONSIVE: "DEVICE_UNRESPONSIVE",
  NONE: "NO_DEVICE",
  UNKNOWN: "DEVICE_STATUS_UNKNOWN",
};

/** GET /api/home 응답(J119 U1 모양). 10:32(KST) = 01:32Z. */
export function homeBody(state: NetworkState, options: HomeBodyOptions = {}): Record<string, unknown> {
  const reasonCode = LOCK_REASON_BY_STATE[state];
  const canStart = reasonCode === null;
  const childName = options.childName === undefined ? "하늘" : options.childName;
  return {
    child: childName === null ? null : { childId: CHILD_ID, displayName: childName, age: 8, interests: ["공룡", "그림"] },
    device: {
      registration: {
        status: state === "NONE" ? "NONE" : "REGISTERED",
        codePrefix: state === "NONE" ? null : "7F3K",
        registeredAt: state === "NONE" ? null : "2026-10-15T00:00:00Z",
      },
      network: {
        state,
        rttMs: state === "STABLE" ? 40 : null,
        recentDisconnects: 0,
        since: "2026-10-15T01:00:00Z",
        lastSeenAt: options.lastSeenAt === undefined ? "2026-10-15T01:30:00Z" : options.lastSeenAt,
        lastReportedAt: options.lastReportedAt === undefined ? "2026-10-15T01:32:00Z" : options.lastReportedAt,
      },
      verdict: { canStart, reasonCode },
    },
    currentSession: null,
    lock: { locked: !canStart, reasonCode },
  };
}

export interface BubbleBody {
  readonly seq: number;
  readonly speaker: "CHILD" | "AI";
  readonly text: string;
  readonly final?: boolean;
}

/** GET /api/sessions/{id} 응답(LiveView, BR6.15). 시작 10:00(KST) = 01:00Z. */
export function liveViewBody(status: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    sessionId: SESSION_ID,
    activityType: "FREE_TALK",
    status,
    endReason: null,
    failureCode: null,
    confirmed: false,
    requestedAt: "2026-10-15T00:59:58Z",
    startedAt: "2026-10-15T01:00:00Z",
    endedAt: null,
    statusVersion: 3,
    serverTime: "2026-10-15T01:02:30Z",
    observedAt: "2026-10-15T01:02:29Z",
    stale: false,
    technicalPause: null,
    silenceWarning: null,
    practice: null,
    gaps: [],
    items: [],
    nextSeq: 0,
    ...extra,
  };
}

export function bubble(body: BubbleBody): Record<string, unknown> {
  return {
    seq: body.seq,
    speaker: body.speaker,
    text: body.text,
    final: body.final ?? true,
    uncertain: false,
    startedAt: "2026-10-15T01:00:10Z",
    endedAt: null,
  };
}

/** 밖에서 풀 수 있는 약속(요청 중 상태 시험용). */
export interface Deferred<T> {
  readonly promise: Promise<T>;
  readonly resolve: (value: T) => void;
  readonly reject: (reason: unknown) => void;
}

export function deferred<T>(): Deferred<T> {
  let resolve: (value: T) => void = () => undefined;
  let reject: (reason: unknown) => void = () => undefined;
  const promise = new Promise<T>((onResolve, onReject) => {
    resolve = onResolve;
    reject = onReject;
  });
  return { promise, resolve, reject };
}
