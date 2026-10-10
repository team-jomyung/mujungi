import timingDefaults from "./timing-defaults.json";

// web 시간 설정 (U1 3.5 Step 37 · BR8.11 · NFR6.1 · LC-2 · PD-14 · contracts/timing.md 1 · 3 · 6절)
// - 화면 · 기기 코드는 숫자 시간값을 박지 않고 이 모듈의 Timing 값만 쓴다.
// - 기본값은 timing-defaults.json 한 곳(백엔드 application.yml에 해당)이고, 운영 값은 서버 컴포넌트가 기동 환경 변수에서
//   읽어 첫 응답에 싣는다(readTimingFromEnv.ts · TimingProvider.tsx). 빌드 때 굽지 않는다(NEXT_PUBLIC_* 금지, J94 · J122).
// - 환경 변수 이름은 Spring 이완 바인딩 규칙(대문자, '.' → '_', '-' 제거)이다. 예: mujoong.timing.parent.poll-interval →
//   MUJOONG_TIMING_PARENT_POLLINTERVAL (contracts/timing.md '읽는 법').

/** web이 쓰는 시간 값(밀리초). */
export interface Timing {
  /** 12 진행 폴링 주기(mujoong.timing.parent.poll-interval, BR6.15 · PD-12). */
  readonly parentPollIntervalMs: number;
  /** 보호자 웹 REST 요청 제한 시간(mujoong.timing.parent.request-timeout, J80 · RD-21). */
  readonly parentRequestTimeoutMs: number;
  /** D0 등록 상태 확인 주기(mujoong.timing.bootstrap.status-poll, BR1.7). */
  readonly bootstrapStatusPollMs: number;
  /** 기기 HEARTBEAT 주기의 대체값. HELLO_OK.heartbeatIntervalMs가 있으면 그 값이 우선이다(mujoong.timing.device.heartbeat-interval). */
  readonly deviceHeartbeatIntervalMs: number;
  /** 기기 재생 대기열 상한(초 분량, mujoong.timing.device.output-queue-max, J31 · J73). */
  readonly deviceOutputQueueMaxMs: number;
  /** D0 · T19 실패 뒤 재시도 · 재접속 간격(mujoong.device-web.retry-interval, BR9.4 · BR9.7). */
  readonly deviceWebRetryIntervalMs: number;
  /** 「등록되었어요」 표시 시간(mujoong.device-web.registered-display, BR9.2 · J56). */
  readonly deviceWebRegisteredDisplayMs: number;
  /** 조작 없는 토스트가 떠 있는 시간(ui.toast-duration, interaction-spec 1절 · 가정 C5). */
  readonly toastDurationMs: number;
  /** 조작이 달린 토스트의 최소 표시 시간(ui.toast-action-min, interaction-spec 1절 · B25). */
  readonly toastActionMinMs: number;
}

export type TimingField = keyof Timing;

export interface TimingKeySpec {
  readonly field: TimingField;
  /** timing-defaults.json · contracts/timing.md의 키. */
  readonly configKey: string;
  /** 덮어쓰는 환경 변수 이름. 계약 밖 화면 키(ui.*)는 null이다. */
  readonly envVar: string | null;
}

/** 설정 키 → 환경 변수 이름(Spring 이완 바인딩: 대문자, '.' → '_', '-' 제거). */
export function toEnvVarName(configKey: string): string {
  return configKey.replace(/-/g, "").replace(/\./g, "_").toUpperCase();
}

function contractKey(field: TimingField, configKey: string): TimingKeySpec {
  return { field, configKey, envVar: toEnvVarName(configKey) };
}

/** web이 읽는 키 목록. 계약 키는 환경 변수로 덮어쓸 수 있다. */
export const TIMING_KEYS: readonly TimingKeySpec[] = [
  contractKey("parentPollIntervalMs", "mujoong.timing.parent.poll-interval"),
  contractKey("parentRequestTimeoutMs", "mujoong.timing.parent.request-timeout"),
  contractKey("bootstrapStatusPollMs", "mujoong.timing.bootstrap.status-poll"),
  contractKey("deviceHeartbeatIntervalMs", "mujoong.timing.device.heartbeat-interval"),
  contractKey("deviceOutputQueueMaxMs", "mujoong.timing.device.output-queue-max"),
  contractKey("deviceWebRetryIntervalMs", "mujoong.device-web.retry-interval"),
  contractKey("deviceWebRegisteredDisplayMs", "mujoong.device-web.registered-display"),
  { field: "toastDurationMs", configKey: "ui.toast-duration", envVar: null },
  { field: "toastActionMinMs", configKey: "ui.toast-action-min", envVar: null },
];

/** 시간 설정 값이 형식에 맞지 않을 때. 메시지에는 키 이름만 넣고 값은 넣지 않는다. */
export class TimingConfigError extends Error {
  constructor(readonly configKey: string) {
    super(`시간 설정 값의 형식이 틀렸다: ${configKey} (예: 500ms · 2s · 10m · 24h · 30d)`);
    this.name = "TimingConfigError";
  }
}

const UNIT_TO_MS: Readonly<Record<string, number>> = {
  ms: 1,
  s: 1_000,
  m: 60_000,
  h: 3_600_000,
  d: 86_400_000,
};

const DURATION_PATTERN = /^(\d+)(ms|s|m|h|d)?$/;

/**
 * Spring Duration 짧은 표기를 밀리초로 바꾼다. 단위가 없으면 밀리초다(Spring 기본).
 * 0 이하 · 정수 아님 · 모르는 단위는 null.
 */
export function parseDuration(text: string): number | null {
  const match = DURATION_PATTERN.exec(text.trim());
  if (match === null) {
    return null;
  }
  const amount = Number(match[1]);
  const unit = match[2] ?? "ms";
  const ms = amount * UNIT_TO_MS[unit];
  if (!Number.isSafeInteger(ms) || ms <= 0) {
    return null;
  }
  return ms;
}

type DefaultsFile = Readonly<Record<string, string>>;

const DEFAULTS: DefaultsFile = timingDefaults as DefaultsFile;

function readDefault(spec: TimingKeySpec): number {
  const raw = DEFAULTS[spec.configKey];
  const parsed = typeof raw === "string" ? parseDuration(raw) : null;
  if (parsed === null) {
    throw new TimingConfigError(spec.configKey);
  }
  return parsed;
}

/**
 * 기본값 위에 환경 변수 값을 덮어써 Timing을 만든다. 빈 문자열 · 없는 변수는 기본값을 쓴다.
 * 형식이 틀린 값은 조용히 무시하지 않고 TimingConfigError로 멈춘다(fail fast).
 */
export function resolveTiming(source: Readonly<Record<string, string | undefined>>): Timing {
  const values: Partial<Record<TimingField, number>> = {};
  for (const spec of TIMING_KEYS) {
    const override = spec.envVar === null ? undefined : source[spec.envVar];
    if (override !== undefined && override.trim() !== "") {
      const parsed = parseDuration(override);
      if (parsed === null) {
        throw new TimingConfigError(spec.envVar ?? spec.configKey);
      }
      values[spec.field] = parsed;
    } else {
      values[spec.field] = readDefault(spec);
    }
  }
  return values as Timing;
}

/** 환경 변수 없이 기본값만으로 만든 Timing(시험 · 개발 기본). */
export function defaultTiming(): Timing {
  return resolveTiming({});
}
