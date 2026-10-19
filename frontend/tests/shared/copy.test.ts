import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import {
  COPY,
  PROPOSED_COPY,
  TEMP_COPY,
  abortedBody,
  codeCellLabel,
  counterText,
  doneTimeRangeLine,
  formatRegistrationCode,
  guardianStopDetail,
  homeTitle,
  lastCheckedLine,
  liveElapsedLine,
  liveStaleLine,
  liveWaitingChildLine,
  speakerPrefix,
  unresponsiveLastLine,
} from "@/shared/copy/ko";
import { elapsedMinuteOrdinal, formatHhmm, minutesBetween } from "@/shared/copy/timeText";
import { ERROR_CODES, isErrorCode, messageForErrorCode, normalizeErrorCode } from "@/shared/errors/codeToCopy";

// 화면 문구 · 시각 표기 · 오류 code 문장 시험 (U1 3.5 Step 41 · 문구표 v1.5j · J118 · BR8.6 · BR8.10 · contracts/errors.md)

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..");

function allStrings(value: unknown): string[] {
  if (typeof value === "string") {
    return [value];
  }
  if (typeof value === "object" && value !== null) {
    return Object.values(value).flatMap(allStrings);
  }
  return [];
}

describe("ko — 자리를 채우는 문구", () => {
  it("D0 코드는 4 · 4로 나눠 보이고 8자가 아니면 그대로 둔다(BR1.1 · C.D-00.30.3)", () => {
    expect(formatRegistrationCode("7F3K92QD")).toBe("7F3K · 92QD");
    expect(formatRegistrationCode("7F3K92Q")).toBe("7F3K92Q");
    expect(formatRegistrationCode("")).toBe("");
  });

  it("04 제목은 '{이름}의 오늘'이고 이름이 없으면 서비스 이름이다(C.S-04.3.1)", () => {
    expect(homeTitle("하늘")).toBe("하늘의 오늘");
    expect(homeTitle(null)).toBe("무중");
    expect(homeTitle("")).toBe("무중");
  });

  it("시간 줄 · 상태 줄을 문구표 꼴로 만든다", () => {
    expect(lastCheckedLine("10:32")).toBe("마지막 확인 10:32");
    expect(unresponsiveLastLine("10:30")).toBe("마지막 10:30");
    expect(liveElapsedLine("10:31", 4)).toBe("10:31 시작 · 4분째");
    expect(liveStaleLine("10:32")).toBe("기기 상태를 확인할 수 없어요 · 마지막 확인 10:32");
    expect(liveStaleLine(null)).toBe("기기 상태를 확인할 수 없어요");
    expect(liveWaitingChildLine("하늘")).toBe("무중이 하늘에게 말을 걸고 있어요");
    expect(doneTimeRangeLine("10:31", "10:52", 21)).toBe("10:31 ~ 10:52 · 21분");
    expect(guardianStopDetail("10:52")).toBe("「이야기 끝내기」 · 10:52");
    expect(abortedBody("10:34")).toBe("10:34 기기 연결이 끊겨 다시 잇지 못했어요. 끊기기 전까지의 기록은 남아요.");
    expect(abortedBody(null)).toBe("기기 연결이 끊겨 다시 잇지 못했어요. 끊기기 전까지의 기록은 남아요.");
  });

  it("보조기기 이름 · 글자 수 표기를 만든다(interaction-spec 3.7 · 3.9 · 3.10)", () => {
    expect(codeCellLabel(1)).toBe("1번째 글자");
    expect(counterText(120, 200)).toBe("120/200");
    expect(speakerPrefix("AI", "하늘")).toBe("무중:");
    expect(speakerPrefix("CHILD", "하늘")).toBe("하늘:");
    expect(speakerPrefix("CHILD", null)).toBe(":");
  });

  it("화면에 '(문구 미정)'이나 원시 오류 code를 문구로 두지 않는다(J118 · U6)", () => {
    const strings = [...allStrings(COPY), ...allStrings(PROPOSED_COPY), ...allStrings(TEMP_COPY)];
    expect(strings.some((text) => text.includes("문구 미정"))).toBe(false);
    expect(strings.filter((text) => /^[A-Z][A-Z_]+$/.test(text))).toEqual([]);
  });

  it("아이 기기 화면 글은 문구표 원문과 '연결을 확인하는 중' · '다시 연결하고 있어요'(U3 · mockups 9-31)뿐이다(BR9.4)", () => {
    expect(COPY.device.d0Guide).toBe("보호자 앱에서 이 코드를 넣어 주세요");
    expect(COPY.device.d0Registered).toBe("등록되었어요");
    expect(COPY.device.t19MicDeniedTitle).toBe("마이크를 허용해 주세요");
    expect(COPY.device.t19MicDeniedBody).toBe("브라우저 설정에서 마이크와 소리를 켠 뒤 다시 「켜기」를 눌러요.");
    expect(COPY.device.t19Disconnected).toBe("연결 끊김");
    expect(PROPOSED_COPY.device).toEqual({ d0Checking: "연결을 확인하는 중", t19Reconnecting: "다시 연결하고 있어요" });
  });
});

describe("timeText — 서버 UTC → 화면 Asia/Seoul hh:mm(BR8.10)", () => {
  it("UTC 시각을 서울 24시간 hh:mm으로 보인다", () => {
    expect(formatHhmm("2026-10-15T01:32:00Z")).toBe("10:32");
    expect(formatHhmm("2026-10-15T15:05:00.123Z")).toBe("00:05");
  });

  it("없거나 읽을 수 없는 시각은 null이다", () => {
    expect(formatHhmm(null)).toBeNull();
    expect(formatHhmm(undefined)).toBeNull();
    expect(formatHhmm("")).toBeNull();
    expect(formatHhmm("not-a-time")).toBeNull();
  });

  it("두 시각 사이 분은 반올림하고 음수는 0이다", () => {
    expect(minutesBetween("2026-10-15T01:00:00Z", "2026-10-15T01:21:29Z")).toBe(21);
    expect(minutesBetween("2026-10-15T01:00:00Z", "2026-10-15T01:21:30Z")).toBe(22);
    expect(minutesBetween("2026-10-15T01:10:00Z", "2026-10-15T01:00:00Z")).toBe(0);
    expect(minutesBetween(null, "2026-10-15T01:00:00Z")).toBeNull();
  });

  it("'n분째'는 첫 1분 안이 1이다", () => {
    expect(elapsedMinuteOrdinal("2026-10-15T01:00:00Z", "2026-10-15T01:00:59Z")).toBe(1);
    expect(elapsedMinuteOrdinal("2026-10-15T01:00:00Z", "2026-10-15T01:03:00Z")).toBe(4);
    expect(elapsedMinuteOrdinal("2026-10-15T01:00:00Z", "2026-10-15T00:59:00Z")).toBe(1);
    expect(elapsedMinuteOrdinal("x", "2026-10-15T01:00:00Z")).toBeNull();
  });
});

describe("codeToCopy — 오류 code → 문장(BR8.6 · 계약 10절)", () => {
  const table = readFileSync(path.join(repoRoot, "contracts/errors.md"), "utf8");
  const rows = [...table.matchAll(/^\|\s*`([A-Z_]+)`\s*\|\s*[^|]+\|\s*([^|]+?)\s*\|/gm)].map((match) => ({
    code: match[1],
    message: match[2],
  }));

  it("contracts/errors.md 표의 code와 고정 문장이 TEMP_COPY.errors와 하나도 다르지 않다", () => {
    expect(rows.length).toBe(ERROR_CODES.length);
    expect(rows.map((row) => row.code).sort()).toEqual([...ERROR_CODES].sort());
    for (const row of rows) {
      expect(messageForErrorCode(normalizeErrorCode(row.code)), row.code).toBe(row.message);
    }
  });

  it("모르는 code · 문자열 아닌 값은 UNKNOWN이고 일반 문장을 쓴다", () => {
    expect(isErrorCode("CODE_NOT_FOUND")).toBe(true);
    expect(isErrorCode("NEW_CODE")).toBe(false);
    expect(normalizeErrorCode("NEW_CODE")).toBe("UNKNOWN");
    expect(normalizeErrorCode(404)).toBe("UNKNOWN");
    expect(normalizeErrorCode(undefined)).toBe("UNKNOWN");
    expect(messageForErrorCode("UNKNOWN")).toBe(TEMP_COPY.networkError);
  });
});
