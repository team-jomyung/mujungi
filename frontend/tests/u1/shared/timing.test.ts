import { readFileSync } from "node:fs";
import path from "node:path";
import { renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import timingDefaults from "@/shared/timing/timing-defaults.json";
import { readTimingFromEnv } from "@/shared/timing/readTimingFromEnv";
import { useTiming } from "@/shared/timing/TimingProvider";
import {
  TIMING_KEYS,
  TimingConfigError,
  defaultTiming,
  parseDuration,
  resolveTiming,
  toEnvVarName,
  type Timing,
} from "@/shared/timing/timing";
import { codeOf, frontendRoot, repoRoot, sourceFiles } from "../support/sourceScan";
import { timingWrapper } from "../support/timingWrapper";

// web 시간 설정 시험 (U1 3.5 Step 41 · NFR6.1 · BR8.11 · LC-2 · J94 · J122 · contracts/timing.md)
// 기본값 한 곳 · 런타임 환경 변수 주입 · 형식 오류는 멈춤 · 화면 코드에 박힌 시간 숫자 없음.

afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe("timing — 기본값과 계약", () => {
  it("기본값은 contracts/timing.md와 같은 값이다(2s · 10s · 2s · 5s · 20s · 5s · 3s)", () => {
    expect(defaultTiming()).toEqual({
      parentPollIntervalMs: 2_000,
      parentRequestTimeoutMs: 10_000,
      parentLoadingNoticeDelayMs: 5_000,
      bootstrapStatusPollMs: 2_000,
      deviceHeartbeatIntervalMs: 5_000,
      deviceOutputQueueMaxMs: 20_000,
      deviceWebRetryIntervalMs: 5_000,
      deviceWebRegisteredDisplayMs: 3_000,
      toastDurationMs: 4_000,
      toastActionMinMs: 3_000,
    } satisfies Timing);
    const contract = readFileSync(path.join(repoRoot, "contracts/timing.md"), "utf8");
    const defaults = timingDefaults as Record<string, string>;
    for (const spec of TIMING_KEYS.filter((key) => key.envVar !== null)) {
      const escaped = spec.configKey.replace(/[.-]/g, (char) => `\\${char}`);
      const row = new RegExp(`^\\|\\s*\`${escaped}\`\\s*\\|\\s*\`([^\`]+)\``, "m").exec(contract);
      expect(row, spec.configKey).not.toBeNull();
      expect(defaults[spec.configKey], spec.configKey).toBe(row?.[1]);
    }
  });

  it("환경 변수 이름은 Spring 이완 바인딩 규칙이다", () => {
    expect(toEnvVarName("mujoong.timing.parent.poll-interval")).toBe("MUJOONG_TIMING_PARENT_POLLINTERVAL");
    expect(toEnvVarName("mujoong.device-web.retry-interval")).toBe("MUJOONG_DEVICEWEB_RETRYINTERVAL");
    expect(TIMING_KEYS.filter((key) => key.envVar === null).map((key) => key.configKey)).toEqual(["ui.toast-duration", "ui.toast-action-min"]);
  });

  it.each([
    ["500ms", 500],
    ["2s", 2_000],
    ["10m", 600_000],
    ["24h", 86_400_000],
    ["30d", 2_592_000_000],
    ["250", 250],
    [" 3s ", 3_000],
  ])("Duration 표기 %s는 %i ms다", (text, expected) => {
    expect(parseDuration(text)).toBe(expected);
  });

  it.each(["", "0s", "-1s", "1.5s", "5x", "s", "2 s", "abc"])("틀린 Duration 표기 %j는 null이다", (text) => {
    expect(parseDuration(text)).toBeNull();
  });
});

describe("timing — 런타임 주입(NFR6.1 · 빌드 때 굽지 않음)", () => {
  it("환경 변수 값이 기본값을 덮고 빈 값은 기본값을 쓴다", () => {
    const timing = resolveTiming({ MUJOONG_TIMING_PARENT_POLLINTERVAL: "1500ms", MUJOONG_DEVICEWEB_RETRYINTERVAL: " " });
    expect(timing.parentPollIntervalMs).toBe(1_500);
    expect(timing.deviceWebRetryIntervalMs).toBe(5_000);
  });

  it("형식이 틀린 값은 키 이름만 담은 오류로 멈추고 값은 내보내지 않는다", () => {
    let caught: unknown = null;
    try {
      resolveTiming({ MUJOONG_TIMING_DEVICE_HEARTBEATINTERVAL: "fast-9q" });
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(TimingConfigError);
    expect((caught as TimingConfigError).configKey).toBe("MUJOONG_TIMING_DEVICE_HEARTBEATINTERVAL");
    expect((caught as Error).message).not.toContain("fast-9q");
  });

  it("readTimingFromEnv는 계약 키 환경 변수만 읽고 화면 키(ui.*)는 바꾸지 않는다", () => {
    vi.stubEnv("MUJOONG_TIMING_BOOTSTRAP_STATUSPOLL", "750ms");
    vi.stubEnv("UI_TOASTDURATION", "1ms");
    const timing = readTimingFromEnv();
    expect(timing.bootstrapStatusPollMs).toBe(750);
    expect(timing.toastDurationMs).toBe(4_000);
    expect(readTimingFromEnv({ MUJOONG_TIMING_PARENT_REQUESTTIMEOUT: "3s" }).parentRequestTimeoutMs).toBe(3_000);
  });

  it("TimingProvider가 준 값을 그대로 돌려주고 Provider가 없으면 숫자를 지어내지 않고 멈춘다", () => {
    const injected = resolveTiming({ MUJOONG_TIMING_PARENT_POLLINTERVAL: "40ms" });
    const { result } = renderHook(() => useTiming(), { wrapper: timingWrapper(injected) });
    expect(result.current).toBe(injected);
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    expect(() => renderHook(() => useTiming())).toThrow(/TimingProvider/);
  });
});

describe("timing — 박힌 시간 숫자 없음(NFR6.1 · PD-14)", () => {
  const files = sourceFiles(path.join(frontendRoot, "src"));

  it("src 어디에도 setTimeout · setInterval에 숫자 지연을 직접 쓰지 않는다", () => {
    const offenders = files.filter((file) => /set(Timeout|Interval)\([^;]*?,\s*\d/.test(readFileSync(file, "utf8")));
    expect(offenders).toEqual([]);
  });

  it("계약 시간값(2 · 3 · 4 · 5 · 10 · 20초)을 밀리초 숫자로 박은 코드가 없다(주석 제외)", () => {
    const offenders = files.filter((file) => /\b(2|3|4|5|10|20)_?000\b/.test(codeOf(file)));
    expect(offenders).toEqual([]);
  });

  it("빌드 때 굽는 NEXT_PUBLIC_* 환경 변수를 쓰지 않는다(J94 · J122)", () => {
    const offenders = files.filter((file) => codeOf(file).includes("NEXT_PUBLIC_"));
    expect(offenders).toEqual([]);
  });
});
