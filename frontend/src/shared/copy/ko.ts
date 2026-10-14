// 화면 문구 (U1 3.5 Step 37 · 화면 설계 v1.5j 문구표 · mockups.md 9절 · J118 · BR9.4 · NFR1 · NFR2)
//
// 화면 코드는 글을 직접 쓰지 않고 이 모듈만 쓴다(ESLint react/jsx-no-literals). 세 묶음으로 나눠 출처를 남긴다.
// - COPY: 화면 설계 v1.5j 문구표 원문(키 C.화면.그림.순번). 그대로 옮기고 고치지 않는다.
// - PROPOSED_COPY: 문구표에 없어 mockups.md 9절 · interaction-spec · 승인된 규칙(BR)이 제안한 문구(가정 C2, 임시).
// - TEMP_COPY: 어느 설계 문서에도 글이 없어 3.5가 임시로 둔 문구(J118). code-summary에 목록을 남기고 문구표 개정 때 바꾼다.
// 아이 기기 화면 글은 '연결을 확인하는 중' · 마이크 허용 안내 · 재접속 보조 줄 '다시 연결하고 있어요'(U3)뿐이다(BR9.4). 화면에
// '(문구 미정)'을 보이지 않는다.
// {이름} 같은 자리는 copy 함수가 채운다(받침 조사는 particle.ts).

import { attachParticle } from "./particle";

/** 문구표 원문. */
export const COPY = {
  brand: "무중", // C.S-01.0.1 · C.D-00.30.1
  device: {
    d0Guide: "보호자 앱에서 이 코드를 넣어 주세요", // C.D-00.30.2
    d0CodeSeparator: " · ", // C.D-00.30.3 '7F3K · 92QD'의 묶음 구분
    d0Registered: "등록되었어요", // C.D-00.30.4
    t19Standby: "대기", // C.D-19.31.2
    t19Listening: "듣는 중", // C.D-19.31.3
    t19Thinking: "생각 중", // C.D-19.31.4
    t19Speaking: "말하는 중", // C.D-19.31.5
    t19Closing: "마무리", // C.D-19.31.6
    t19Disconnected: "연결 끊김", // C.D-19.31.7(U3: 한 번 연결됐던 기기의 재접속 주기, U3-J19)
    t19Off: "끄기", // C.D-19.31.8
    t19On: "켜기", // C.D-19.39.2 · C.D-19.57.4
    t19MicDeniedTitle: "마이크를 허용해 주세요", // C.D-19.57.2
    t19MicDeniedBody: "브라우저 설정에서 마이크와 소리를 켠 뒤 다시 「켜기」를 눌러요.", // C.D-19.57.3
  },
  home: {
    titleSuffix: "의 오늘", // C.S-04.3.1 '민준의 오늘'
    deviceSection: "대화 기기", // C.S-04.3.5
    stable: "안정", // C.S-04.3.6
    lastCheckedPrefix: "마지막 확인 ", // C.S-04.3.7 '마지막 확인 10:32'
    viewDeviceStatus: "기기 상태 보기 ›", // C.S-04.3.8
    unstable: "불안정", // C.S-04.67.6
    unstableReason: "안정될 때까지 시작할 수 없어요", // C.S-04.67.7
    noDevice: "연결된 기기 없음", // C.S-04.4.6
    noDeviceBody: "아이 기기에 뜬 코드로 등록해요.", // C.S-04.4.7
    registerDevice: "기기 등록", // C.S-04.4.8
  },
  tabs: {
    home: "홈", // C.S-04.3.13
    free: "자유대화", // C.S-04.3.14
    practice: "연습", // C.S-04.3.15
    records: "기록", // C.S-04.3.16
  },
  register: {
    title: "기기 등록", // C.S-05A.8.1 '← 기기 등록'
    backArrow: "←", // C.S-05A.8.1
    guide: "아이 기기 화면의 8자리 코드", // C.S-05A.8.2
    codeNotFound: "없는 코드예요", // C.S-05A.8.5
    cancel: "취소", // C.S-05A.8.6
    submit: "등록", // C.S-05A.8.7
  },
  free: {
    title: "오늘 아이와 자유롭게 이야기해요", // C.S-08.10.1
    knownContentLabel: "알고 있는 내용", // C.S-08.10.2
    knownContentExample:
      "친구와 블록 놀이를 하다가 다퉜다고 들었어요. 친구가 그만하자고 했는데 계속 쌓으려고 했대요.", // C.S-08.10.3
    situationLabel: "관련 상황", // C.S-08.10.4
    situationFriend: "친구", // C.S-08.10.5
    situationSchool: "학교", // C.S-08.10.6
    situationFamily: "가족", // C.S-08.10.7
    situationAcademy: "학원", // C.S-08.10.8
    situationOther: "기타", // C.S-08.10.9
    sourceLabel: "정보 출처", // C.S-08.10.10
    sourceSeen: "직접 봄", // C.S-08.10.11
    sourceHeardFromChild: "아이에게 들음", // C.S-08.10.12
    questionLabel: "궁금한 내용", // C.S-08.10.13
    questionExample: "친구가 그만하자고 할 때 아이가 어떻게 받아들이는지 궁금해요", // C.S-08.10.14
    start: "자유대화 시작", // C.S-08.10.15
  },
  live: {
    header: "자유대화", // C.S-12.11.1 · C.S-12.12.1
    endButton: "이야기 끝내기", // C.S-12.11.5 · C.S-12.12.9
  },
  endDialog: {
    title: "정말 끝낼까요?", // C.P-01.13.1
    no: "아니오", // C.P-01.13.2
    yes: "네, 끝낼게요", // C.P-01.13.3
  },
  done: {
    header: "자유대화", // C.S-17.16.1
    endedTitle: "오늘 자유대화가 끝났어요", // C.S-17.16.2
    closingTitle: "오늘 자유대화를 마무리하고 있어요", // C.S-17.73.2
    closingBody: "무중이 마무리 인사를 하고 있어요", // C.S-17.73.4
    closingNote: "기기가 멈추면 기록을 정리해요.", // C.S-17.73.5
    unconfirmedTitle: "기기 응답 없이 끝냈어요", // C.S-17.58.4
    unconfirmedBody:
      "기기가 30초 동안 멈춤을 알리지 않아 끝낸 것으로 처리했어요. 다음 시작 전에 기기 상태를 다시 확인해요.", // C.S-17.58.5
    abortedTitle: "연결이 끊겨 끝났어요", // C.S-17.71.4
    abortedBodySuffix: " 기기 연결이 끊겨 다시 잇지 못했어요. 끊기기 전까지의 기록은 남아요.", // C.S-17.71.5 '10:34 …'
    home: "홈으로", // C.S-17.16.6
  },
} as const;

/** 문구표에 없어 mockups.md 9절 · interaction-spec · 승인 규칙이 제안한 문구(가정 C2, 임시). */
export const PROPOSED_COPY = {
  device: {
    d0Checking: "연결을 확인하는 중", // BR9.4 · interaction-spec 4절 D0 · 개발명세 D-00 오류
    t19Reconnecting: "다시 연결하고 있어요", // mockups 9-31 · AC6.2.6(U3 재접속 주기의 보조 줄)
  },
  home: {
    disconnected: "끊김", // mockups S-04 대화 기기 · AC1.4.3
    disconnectedReason: "전원 · 와이파이를 확인해 주세요", // mockups S-04 · AC1.4.3
    unresponsive: "응답 없음", // mockups S-04 · AC1.4.3
    unresponsiveReason: "기기를 다시 켜 보세요", // mockups S-04 · AC1.4.3
    unresponsiveLastPrefix: "마지막 ", // mockups S-04 '마지막 hh:mm'
    unknown: "기기 상태 확인 불가", // BR4.3 ③ · BR4.6
  },
  lockToast: {
    DEVICE_UNSTABLE: "기기가 안정될 때까지 시작할 수 없어요", // mockups 9-6
    DEVICE_DISCONNECTED: "기기 연결이 끊겨 시작할 수 없어요", // mockups 9-6
    DEVICE_UNRESPONSIVE: "기기가 응답하지 않아 시작할 수 없어요", // mockups 9-6
    NO_DEVICE: "먼저 기기를 등록해 주세요", // mockups 9-6
  },
  closingTabToast: "끝나는 중이에요", // mockups S-17 BTN-HOME · TABS
  register: {
    submitting: "등록하고 있어요", // mockups 9-9
    codeAlreadyRegistered: "이미 등록된 코드예요", // mockups 9-10
    cellLabelSuffix: "번째 글자", // interaction-spec 3.7 'n번째 글자'
  },
  free: {
    rejectSessionInProgress: "진행 중인 활동이 있어요. 끝난 뒤 시작할 수 있어요.", // mockups 9-12 SESSION_IN_PROGRESS
    rejectDeviceNotStable: "기기가 안정될 때까지 시작할 수 없어요", // mockups 9-12 DEVICE_NOT_STABLE
    rejectConsentMissing: "동의를 먼저 마쳐 주세요", // mockups 9-12 CONSENT_MISSING
    rejectDeviceNoAck: "기기가 응답하지 않아 시작하지 못했어요. 기기를 확인하고 다시 시도해 주세요.", // mockups 9-12 DEVICE_NO_ACK
    counterSeparator: "/", // interaction-spec 3.9 '200/200'
  },
  live: {
    preparing: "준비하고 있어요", // mockups 9-13 REQUESTED · PREPARING
    deviceAcked: "기기가 응답했어요", // mockups 9-13 DEVICE_ACK
    staleSuffix: "기기 상태를 확인할 수 없어요 · 마지막 확인 ", // mockups 9-16
    staleWithoutTime: "기기 상태를 확인할 수 없어요", // mockups 9-16(마지막 확인 시각이 없을 때)
    pollingTrouble: "연결이 원활하지 않아요", // interaction-spec 1절 로딩 · PD-12(연속 3회 실패)
    mujoongSpeaker: "무중", // interaction-spec 3.10 발화자 숨김 접두 '무중:'
    speakerSuffix: ":", // interaction-spec 3.10
  },
  endSendFailed: "끝내기 요청을 보내지 못했어요. 인터넷을 확인해 주세요.", // mockups 9-18
  done: {
    guardianStopTitle: "보호자가 끝냈어요", // mockups 9-19
  },
  retry: "다시 시도", // interaction-spec 1절 오류 「다시 시도」
} as const;

/** 설계 문서 어디에도 글이 없어 3.5가 임시로 둔 문구(J118). 문구표 개정 때 바꾼다. */
export const TEMP_COPY = {
  lockToastStatusUnknown: "기기 상태를 확인할 수 없어 시작할 수 없어요", // J118 잠긴 탭 DEVICE_STATUS_UNKNOWN
  registerTooManyAttempts: "잠시 뒤 다시 시도해 주세요", // J118 05a 429
  registerDeviceAlreadyActive: "이미 연결된 기기가 있어요", // J118 05a DEVICE_ALREADY_ACTIVE
  freeRealtimeUnavailable: "지금은 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요.", // J118 08 503
  livePreparingEndToast: "아직 시작하는 중이에요. 시작된 뒤 다시 눌러 주세요.", // J118 12 PREPARING 중 끝내기
  back: "뒤로", // 머리 「←」의 보조기기 이름(화면 글 아님)
  codeGroupLabel: "등록 코드 8자리", // 05a 코드 칸 묶음의 보조기기 이름(화면 글 아님)
  debugLogTitle: "상태 로그", // /device?debug=1 상태 로그 머리(BR9.6 · AC6.2.9, 디버그 전용)
  devLogin: {
    title: "시험 로그인", // J119 · BR8.4 (dev 프로필 전용 화면)
    body: "개발 · 시험 환경에서만 쓰는 로그인이에요.", // J119
    submit: "시험 로그인", // J119
    unavailable: "시험 로그인을 쓸 수 없어요", // J119 dev 밖 404
  },
  /**
   * 오류 봉투 code별 고정 message(contracts/errors.md 표와 같은 문장, J118 · BR8.6). 화면은 원시 code를 보이지 않고
   * 이 문장 또는 화면별 사유 문구를 고른다.
   */
  errors: {
    UNAUTHENTICATED: "로그인이 필요해요.",
    NOT_FOUND: "찾을 수 없어요.",
    VALIDATION_FAILED: "요청 형식이 올바르지 않아요.",
    CONSENT_MISSING: "동의가 필요해요.",
    ACCOUNT_DELETION_IN_PROGRESS: "계정 삭제가 진행 중이에요.",
    CSRF_TOKEN_INVALID: "보안 확인이 만료됐어요. 다시 시도해 주세요.",
    SESSION_IN_PROGRESS: "이미 진행 중인 활동이 있어요.",
    IDEMPOTENCY_CONFLICT: "같은 요청 번호로 다른 내용이 왔어요.",
    JOB_NOT_FAILED: "지금은 다시 시도할 수 없어요.",
    REPORT_NOT_READY: "리포트가 아직 준비되지 않았어요.",
    SESSION_NOT_ACTIVE: "지금은 끝낼 수 있는 상태가 아니에요.",
    ALREADY_SELECTED: "이미 담은 목표예요.",
    RESTORE_EXPIRED: "되돌릴 수 있는 시간이 지났어요.",
    SCENARIO_GEN_FAILED: "연습 상황을 준비하지 못했어요.",
    PUSH_SUBSCRIPTION_CONFLICT: "알림 구독을 다시 등록해 주세요.",
    DEVICE_NOT_STABLE: "기기 연결이 안정되지 않았어요.",
    REALTIME_UNAVAILABLE: "지금은 연결할 수 없어요. 잠시 뒤 다시 시도해 주세요.",
    CODE_NOT_FOUND: "없는 코드예요.",
    CODE_ALREADY_REGISTERED: "이미 등록된 코드예요.",
    DEVICE_ALREADY_ACTIVE: "이미 연결된 기기가 있어요.",
    TOO_MANY_ATTEMPTS: "잠시 뒤 다시 시도해 주세요.",
    RATE_LIMITED: "잠시 뒤 다시 시도해 주세요.",
    CODE_ISSUE_FAILED: "잠시 뒤 다시 시도해 주세요.",
    BOOTSTRAP_NOT_FOUND: "등록 정보를 찾을 수 없어요.",
    NOT_REGISTERED_YET: "아직 등록되지 않았어요.",
    BOOTSTRAP_CLOSED: "이 등록은 이미 끝났어요.",
    DEVICE_NOT_REGISTERED: "등록되지 않은 기기예요.",
    ALREADY_REGISTERED: "이미 등록된 기기예요.",
    INVALID_ENVELOPE: "요청 본문 형식이 올바르지 않아요.",
    EVENT_CONFLICT: "같은 이벤트에 다른 내용이 왔어요.",
    INVALID_DEVICE_TOKEN: "기기 인증에 실패했어요.",
    DEVICE_NOT_CONNECTED: "기기가 연결되어 있지 않아요.",
    SERVICE_NOT_READY: "잠시 뒤 다시 시도해 주세요.",
    INTERNAL_ERROR: "잠시 문제가 생겼어요. 다시 시도해 주세요.",
    UPSTREAM_UNAVAILABLE: "지금은 연결할 수 없어요. 잠시 뒤 다시 시도해 주세요.",
    GOOGLE_AUTH_FAILED: "로그인하지 못했어요. 다시 시도해 주세요.",
    CHILD_NOT_REGISTERED: "아이 정보가 아직 없어요.",
    CHILD_EXISTS: "이미 등록된 아이가 있어요.",
  },
  /** 응답 없음 · 시간 초과 · 모르는 code의 일반 문장(INTERNAL_ERROR 문장과 같다). */
  networkError: "잠시 문제가 생겼어요. 다시 시도해 주세요.",
} as const;

/** U7 알림 정본: US1.7 AC1.7.1~1.7.3 · mockups §9-35(완료). */
export const PUSH_COPY = {
  problem: {
    VOICE_PROBLEM: "대화 중 음성 연결 문제가 발생했어요.",
    DEVICE_STATUS_UNKNOWN: "기기 상태를 확인할 수 없어요.",
    ENDED_BY_CONNECTION: "연결 문제로 활동이 종료됐어요.",
  },
  completedTitle: { FREE_TALK: "자유대화가 끝났어요", PRACTICE: "연습이 끝났어요" },
  completedBody: "앱에서 최신 상태를 확인해 주세요",
} as const;

/** U7 SET0 추가행 임시 문구: 기존 문구표에 설정 행이 없어 둠(U7 설계 · J118 관례). */
export const PUSH_TEMP_COPY = {
  heading: "이 기기 알림",
  enable: "알림 켜기",
  disable: "알림 끄기",
  reregister: "다시 등록",
  checking: "알림 설정을 확인하고 있어요",
  unsupported: "이 브라우저에서는 알림을 지원하지 않아요",
  unavailable: "알림을 준비하고 있어요",
  denied: "브라우저 설정에서 알림을 허용해 주세요.",
  dismissed: "알림이 허용되지 않았어요. 다시 켜서 허용해 주세요.",
  unverified: "알림 켜기를 눌러 이 계정의 알림을 등록해 주세요.",
  on: "이 기기에서 알림을 받아요",
  off: "이 기기 알림이 꺼져 있어요",
  loadFailed: "알림 설정을 불러오지 못했어요.",
  enableFailed: "알림을 켜지 못했어요. 다시 시도해 주세요.",
  disableFailed: "알림을 끄지 못했어요. 다시 시도해 주세요.",
  cleanupFailed: "서버 알림은 꺼졌어요. 이 기기의 알림 해제를 다시 시도해 주세요.",
} as const;

// ───────────── 자리를 채우는 문구 ─────────────

/** '민준의 오늘'(C.S-04.3.1). 이름이 없으면 서비스 이름. */
export function homeTitle(childName: string | null): string {
  return childName === null || childName.length === 0 ? COPY.brand : `${childName}${COPY.home.titleSuffix}`;
}

/** '마지막 확인 10:32'(C.S-04.3.7). */
export function lastCheckedLine(hhmm: string): string {
  return `${COPY.home.lastCheckedPrefix}${hhmm}`;
}

/** '마지막 10:32'(mockups S-04 응답 없음). */
export function unresponsiveLastLine(hhmm: string): string {
  return `${PROPOSED_COPY.home.unresponsiveLastPrefix}${hhmm}`;
}

/** D0 코드 4 · 4 표시(BR1.1 · C.D-00.30.3). 8자가 아니면 그대로 보인다. */
export function formatRegistrationCode(code: string): string {
  if (code.length !== 8) {
    return code;
  }
  return `${code.slice(0, 4)}${COPY.device.d0CodeSeparator}${code.slice(4)}`;
}

/** '{n}번째 글자'(interaction-spec 3.7, n은 1부터). */
export function codeCellLabel(position: number): string {
  return `${position}${PROPOSED_COPY.register.cellLabelSuffix}`;
}

/** '120/200'(interaction-spec 3.9). */
export function counterText(length: number, max: number): string {
  return `${length}${PROPOSED_COPY.free.counterSeparator}${max}`;
}

/** '민준이 이야기하고 있어요'(C.S-12.12.2). */
export function liveTalkingLine(childName: string): string {
  return `${attachParticle(childName, "이/가")} 이야기하고 있어요`;
}

/** '무중이 민준에게 말을 걸고 있어요'(C.S-12.11.2, WAITING_CHILD는 U4가 보낸다). */
export function liveWaitingChildLine(childName: string): string {
  return `무중이 ${childName}에게 말을 걸고 있어요`;
}

/** '10:31 시작 · 4분째'(C.S-12.11.3). */
export function liveElapsedLine(startHhmm: string, minuteOrdinal: number): string {
  return `${startHhmm} 시작 · ${minuteOrdinal}분째`;
}

/** '기기 상태를 확인할 수 없어요 · 마지막 확인 10:32'(mockups 9-16). */
export function liveStaleLine(lastHhmm: string | null): string {
  return lastHhmm === null ? PROPOSED_COPY.live.staleWithoutTime : `${PROPOSED_COPY.live.staleSuffix}${lastHhmm}`;
}

/** 말풍선 발화자 숨김 접두 '무중:' · '{아이}:'(interaction-spec 3.10 · AC3.2.20). */
export function speakerPrefix(speaker: "AI" | "CHILD", childName: string | null): string {
  const name = speaker === "AI" ? PROPOSED_COPY.live.mujoongSpeaker : (childName ?? "");
  return `${name}${PROPOSED_COPY.live.speakerSuffix}`;
}

/** '10:31 ~ 10:52 · 21분'(C.S-17.16.3). */
export function doneTimeRangeLine(fromHhmm: string, toHhmm: string, minutes: number): string {
  return `${fromHhmm} ~ ${toHhmm} · ${minutes}분`;
}

/** '「이야기 끝내기」 · 10:52'(mockups 9-19). */
export function guardianStopDetail(hhmm: string): string {
  return `「${COPY.live.endButton}」 · ${hhmm}`;
}

/** '10:34 기기 연결이 끊겨 다시 잇지 못했어요. …'(C.S-17.71.5). 시각이 없으면 앞을 뺀다. */
export function abortedBody(hhmm: string | null): string {
  return hhmm === null ? COPY.done.abortedBodySuffix.trim() : `${hhmm}${COPY.done.abortedBodySuffix}`;
}
