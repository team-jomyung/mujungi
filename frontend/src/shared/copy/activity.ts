// U4a 활동 세션 화면 문구 (docs/units/u4a/design.md 6절 · 화면 설계 v1.5j 문구표 · mockups.md §1 · §9 · U4a-J13 ~ J17)
//
// ko.ts · account.ts와 같은 세 묶음 규칙이다. 화면 코드는 글을 직접 쓰지 않고 이 모듈 · ko.ts · account.ts만 쓴다(ESLint react/jsx-no-literals).
// - ACTIVITY_COPY: 문구표 원문(키 C.화면.그림.순번). 그대로 옮기고 고치지 않는다(17 자동 종료 둘째 줄은 mockups §1 F1-a 사람 지시).
// - ACTIVITY_PROPOSED_COPY: 문구표에 없어 mockups.md 9절 · S-04 표가 제안한 문구(가정 C2, 임시).
// - ACTIVITY_TEMP_COPY: 어느 설계 문서에도 글이 없어 U4a가 임시로 둔 문구(report.md 목록, 문구표 개정 때 바꾼다).
// {아이} · 시각 자리는 아래 함수가 채운다(받침 조사는 particle.ts).

import { attachParticle } from "./particle";

/** 문구표 원문. */
export const ACTIVITY_COPY = {
  home: {
    viewLive: "진행 화면 보기 ›", // C.S-04.3.11
    startedSuffix: " 시작", // C.S-04.3.12 '10:31 시작'
    freeTalk: "자유대화", // C.S-04.3.10 '민준 · 자유대화 4분째'
  },
  live: {
    silentSuffix: "째 말이 없어요", // C.S-12.51.3 '… · 4분째 말이 없어요'
    warnTitle: "2분 더 말이 없으면 활동이 끝나요", // C.S-12.51.5
    warnBody: "아이가 다시 말하면 그대로 이어져요.", // C.S-12.51.6
    gapSuffix: " 잠깐 끊김", // C.S-12.12.7 '10:33 ~ 10:34 잠깐 끊김'
  },
  unexpectedEnd: {
    title: "대화가 예상치 못하게 끝났어요", // C.P-02.14.1
    confirm: "확인", // C.P-02.14.2
  },
  noResponse: {
    title: "아이가 대답하지 않아 시작하지 못했어요", // C.P-03.15.1
    confirm: "확인", // C.P-03.15.2
  },
  nonParticipation: {
    refused: "아이가 지금은 이야기하지 않기로 했어요",
    unknown: "이야기를 시작하지 않았어요",
  },
  done: {
    loading: "활동 상태를 불러오고 있어요",
    loadFailed: "활동 상태를 불러오지 못했어요",
    unknown: "활동 상태를 확인하고 있어요",
    cleaning: "기록을 정리하고 있어요", // C.S-17.16.4
    viewRecord: "오늘 기록 보기", // C.S-17.16.5
    childStopSuffix: " 끝내자고 했어요", // C.S-17.72.4 '민준이 끝내자고 했어요'
    silenceTitle: "말이 없어 자동으로 끝났어요", // C.S-17.53.4
    silenceBody: "공백 6분 · 6분 동안 말소리가 없어 활동을 끝냈어요.", // C.S-17.53.5 · mockups §1 F1-a(사람 지시로 고친 글)
  },
} as const;

/** 문구표에 없어 mockups.md 9절 · S-04 표가 제안한 문구(가정 C2, 임시). */
export const ACTIVITY_PROPOSED_COPY = {
  home: {
    closingSuffix: " 종료 처리 중", // mockups S-04 '{아이} · 자유대화 종료 처리 중'(CLOSING, B1)
    unknown: "지금 상태를 불러오지 못했어요", // mockups 9-5
    lastCheckedPrefix: "마지막 확인 ", // mockups 9-5 '마지막 확인 hh:mm'
    recheck: "다시 확인", // mockups 9-5 「다시 확인」
  },
  live: {
    recovering: "대화 연결을 다시 잇고 있어요", // mockups 9-15(점선과 함께)
    offlinePrefix: "실시간 상태를 불러오지 못하고 있어요", // mockups 9-17
    unconfirmedSuffix: " 확인되지 않은 구간", // mockups 9-25 'hh:mm ~ hh:mm 확인되지 않은 구간'
  },
  free: {
    rejectScenarioGenFailed: "연습 상황을 만들지 못했어요. 잠시 뒤 다시 시도해 주세요.", // mockups 9-12 SCENARIO_GEN_FAILED
  },
} as const;

/** 설계 문서 어디에도 글이 없어 U4a가 임시로 둔 문구(report.md 목록). */
export const ACTIVITY_TEMP_COPY = {
  doneUnconfirmed: "기기 종료 확인 · 확인 안 됨", // U4a-J15 · AC3.4.3(종료 사유와 따로 보이는 미확인 줄)
  minutesSuffix: "분", // '4분째'의 단위
  ordinalSuffix: "째", // '4분째'
  childFallback: "아이", // 아이 이름을 모를 때(12에서 홈을 거치지 않고 들어옴)
  separator: " · ", // 줄 조각 구분
  rangeSeparator: " ~ ", // 'hh:mm ~ hh:mm'
  // FT-1023-e: 연습 음성 출력 실패 종료(end_detail PRACTICE_OUTPUT_FAILED). 기기 연결 문제가 아니므로 연결 확인을 안내하지 않는다(임시)
  practiceOutputFailedTitle: "연습 음성을 재생하지 못했어요",
  practiceOutputFailedBody: "연습이 완료된 것은 아니에요. 잠시 뒤 다시 시작해 주세요.",
} as const;

// ───────────── 자리를 채우는 문구 ─────────────

/** '민준 · 자유대화 4분째'(C.S-04.3.10). */
export function homeActivityLine(childName: string | null, minuteOrdinal: number): string {
  const name = childName === null || childName.length === 0 ? "" : `${childName}${ACTIVITY_TEMP_COPY.separator}`;
  return `${name}${ACTIVITY_COPY.home.freeTalk} ${minuteOrdinal}${ACTIVITY_TEMP_COPY.minutesSuffix}${ACTIVITY_TEMP_COPY.ordinalSuffix}`;
}

/** '민준 · 자유대화 종료 처리 중'(mockups S-04 B1). 이름이 없으면 '자유대화 종료 처리 중'. */
export function homeClosingLine(childName: string | null): string {
  const line = `${ACTIVITY_COPY.home.freeTalk}${ACTIVITY_PROPOSED_COPY.home.closingSuffix}`;
  return childName === null || childName.length === 0 ? line : `${childName}${ACTIVITY_TEMP_COPY.separator}${line}`;
}

/** '10:31 시작'(C.S-04.3.12). */
export function startedAtLine(hhmm: string): string {
  return `${hhmm}${ACTIVITY_COPY.home.startedSuffix}`;
}

/** '마지막 확인 10:32'(mockups 9-5). 시각이 없으면 앞 글만. */
export function lastCheckedText(hhmm: string | null): string {
  return hhmm === null ? ACTIVITY_PROPOSED_COPY.home.lastCheckedPrefix.trim() : `${ACTIVITY_PROPOSED_COPY.home.lastCheckedPrefix}${hhmm}`;
}

/** '10:31 시작 · 9분째 · 4분째 말이 없어요'(C.S-12.51.3). */
export function silentElapsedLine(elapsedLine: string, silentMinutes: number): string {
  return `${elapsedLine}${ACTIVITY_TEMP_COPY.separator}${silentMinutes}${ACTIVITY_TEMP_COPY.minutesSuffix}${ACTIVITY_COPY.live.silentSuffix}`;
}

/** '"…"' 따옴표 묶음. */
function quoted(text: string): string {
  return `"${text}"`;
}

/** '10:33 ~ 10:34 잠깐 끊김'(C.S-12.12.7). */
export function gapLine(fromHhmm: string, toHhmm: string): string {
  return `${fromHhmm}${ACTIVITY_TEMP_COPY.rangeSeparator}${toHhmm}${ACTIVITY_COPY.live.gapSuffix}`;
}

/** '10:33 ~ 10:34 확인되지 않은 구간'(mockups 9-25). */
export function unconfirmedGapLine(fromHhmm: string, toHhmm: string): string {
  return `${fromHhmm}${ACTIVITY_TEMP_COPY.rangeSeparator}${toHhmm}${ACTIVITY_PROPOSED_COPY.live.unconfirmedSuffix}`;
}

/** '실시간 상태를 불러오지 못하고 있어요 · 마지막 확인 10:32'(mockups 9-17). */
export function offlineLine(lastHhmm: string | null): string {
  return lastHhmm === null
    ? ACTIVITY_PROPOSED_COPY.live.offlinePrefix
    : `${ACTIVITY_PROPOSED_COPY.live.offlinePrefix}${ACTIVITY_TEMP_COPY.separator}${ACTIVITY_PROPOSED_COPY.home.lastCheckedPrefix}${lastHhmm}`;
}

/** '민준이 끝내자고 했어요'(C.S-17.72.4). 이름이 없으면 '아이가'. */
export function childStopTitle(childName: string | null): string {
  const name = childName === null || childName.length === 0 ? ACTIVITY_TEMP_COPY.childFallback : childName;
  return `${attachParticle(name, "이/가")}${ACTIVITY_COPY.done.childStopSuffix}`;
}

/** 현재 공개 확정 행의 원문과 해당 행 시각만 조합한다. */
export function childStopDetail(text: string, hhmm: string | null): string {
  const expression = quoted(text);
  return hhmm === null ? expression : `${expression}${ACTIVITY_TEMP_COPY.separator}${hhmm}`;
}
