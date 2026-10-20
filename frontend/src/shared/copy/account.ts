// U2 보호자 계정 화면 문구 (docs/units/u2/design.md 6절 · 화면 설계 v1.5j 문구표 · mockups.md 9절 · U2-J16)
//
// ko.ts와 같은 세 묶음 규칙이다. 화면 코드는 글을 직접 쓰지 않고 이 모듈과 ko.ts만 쓴다(ESLint react/jsx-no-literals).
// - ACCOUNT_COPY: 문구표 원문(키 C.화면.그림.순번). 그대로 옮기고 고치지 않는다(02-1 F2 생성형 AI 안내는 mockups §1 사람 지시).
// - ACCOUNT_PROPOSED_COPY: 문구표에 없어 mockups.md 9절이 제안한 문구(가정 C2, 임시).
// - ACCOUNT_TEMP_COPY: 어느 설계 문서에도 글이 없어 U2가 임시로 둔 문구(report.md 목록, 문구표 개정 때 바꾼다).
// {아이} 자리는 아래 함수가 채운다.

/** 문구표 원문. */
export const ACCOUNT_COPY = {
  login: {
    brand: "무중", // C.S-01.0.1
    subtitle: "아이의 이야기를 함께 살펴봐요", // C.S-01.0.2
    google: "구글로 시작", // C.S-01.0.3
    aiNotice: "생성형 AI를 사용하는 서비스예요.", // 화면 설계 최종안 v1.5 · S-01 하단 안내
  },
  consent: {
    title: "시작 전에 확인해 주세요", // C.S-02.1.1
    voiceAndText: "음성 수집 · 글로 옮겨 저장", // C.S-02.1.2
    required: "필수", // C.S-02.1.3
    detail: "자세히 보기 ›", // C.S-02.1.4
    guardianView: "보호자 열람", // C.S-02.1.5
    reportSharing: "리포트 생성 · 외부 전달", // C.S-02.1.6
    rawAudio: "원음 보관", // C.S-02.1.7
    agree: "동의하고 계속", // C.S-02.1.8
    sheetConfirm: "확인", // C.S-02-1.35.11
  },
  leaveConsent: {
    title: "동의하지 않으면 무중을 쓸 수 없어요", // C.P-05.64.1
    body: "네 항목에 모두 동의해야 시작할 수 있어요. 나가면 로그아웃돼요.", // C.P-05.64.2
    logout: "로그아웃", // C.P-05.64.3
    keep: "계속 동의하기", // C.P-05.64.4
  },
  child: {
    firstTitle: "아이 정보를 알려 주세요", // C.S-03.40.1
    nameLabel: "이름 또는 별명", // C.S-03.40.2
    ageLabel: "나이", // C.S-03.40.3
    interestsLabel: "관심사 (예: 공룡, 블록 놀이)", // C.S-03.40.4
    confirm: "확인", // C.S-03.40.5 · C.S-03.36.6
    viewTitle: "아이 정보", // C.S-03.2.1 '← 아이 정보'
    edit: "정보 수정", // C.S-03.2.5
    cancel: "취소", // C.S-03.36.5
    ageSuffix: "세", // C.S-03.2.3 '7세'
    deviceLinePrefix: "기기 · ", // C.S-03.2.4 '기기 · 안정'
  },
  home: {
    childSection: "아이", // C.S-04.3.2
    childEdit: "수정", // C.S-04.3.4
    activitySection: "지금 활동", // C.S-04.3.9
    activityNone: "진행 중인 활동 없음", // C.S-04.66.10
    activityNoneBody: "자유대화 · 연습 탭에서 시작해요.", // C.S-04.66.11
    activityLocked: "지금은 시작할 수 없어요", // C.S-04.4.10
    lockedNoDevice: "기기를 먼저 등록해 주세요.", // C.S-04.4.11
    lockedUnstable: "기기 네트워크가 불안정해요. 안정되면 자유대화 · 연습 탭이 열려요.", // C.S-04.67.10
  },
  settings: {
    title: "설정", // C.S-SET0.5.1 '← 설정'
    logout: "로그아웃", // C.S-SET0.5.2
    deleteAccount: "계정 삭제", // C.S-SET0.5.3
  },
  deleteDialog: {
    title: "정말 계정을 삭제할까요?", // C.P-07.65.1
    bodySuffix: "의 기록 · 리포트 · 동의 · 기기 등록이 모두 지워지고 되돌릴 수 없어요. 남길 리포트는 먼저 저장해 두세요.", // C.P-07.65.2
    no: "아니오", // C.P-07.65.3
    yes: "삭제", // C.P-07.65.4
  },
  device: {
    title: "대화 기기 상태", // C.S-05.6.1 '← 대화 기기 상태'
    registrationNone: "기기 등록 · 없음", // C.S-05.6.3
    registrationDonePrefix: "기기 등록 · 완료 (", // C.S-05.7.3 '기기 등록 · 완료 (7F3K)'
    registrationDoneSuffix: ")",
    networkBeforeRegistration: "기기 네트워크 현황 · 등록 뒤 표시", // C.S-05.6.4
    networkPrefix: "기기 네트워크 현황 · ", // C.S-05.7.4 '기기 네트워크 현황 · 안정'
    verdictRegister: "기기를 등록해 주세요", // C.S-05.6.5
    verdictRegisterBody: "아이 기기에 뜬 8자리 코드를 넣어요.", // C.S-05.6.6
    register: "기기 등록", // C.S-05.6.7
    verdictReady: "시작할 준비가 되었어요", // C.S-05.7.6
    verdictBlocked: "지금은 시작할 수 없어요", // C.S-05.61.6
    unstableBody: "기기 네트워크가 불안정해요. 기기를 공유기 가까이 두거나 와이파이를 확인해 주세요.", // C.S-05.61.7
    disconnectedBody: "기기 연결이 끊겼어요. 기기 전원과 와이파이를 확인해 주세요.", // C.S-05.62.7
    stopSectionSuffix: "의 종료 표현", // C.S-05.6.8 '민준의 종료 표현'
    stopSettings: "설정", // C.S-05.6.10
    sinceSuffix: "부터", // C.S-05.62.5 '10:34부터'
    disconnectsSuffix: "회", // C.S-05.61.5 '끊김 3회'
    rttPrefix: "지연 ", // C.S-05.7.5 '지연 0.3초'
    secondsSuffix: "초",
  },
  stopExpressions: {
    defaultText: "무중아 그만할래", // C.S-SET1.9.2
    remove: "삭제", // C.S-SET1.9.4
    add: "+ 표현 추가", // C.S-SET1.9.6
    cancel: "취소", // C.S-SET1.9.7
    save: "저장", // C.S-SET1.9.8
    newLabel: "새 표현", // C.S-SET1.63.5
    inputConfirm: "확인", // C.S-SET1.63.8
  },
} as const;

/** 문구표에 없어 mockups.md 9절이 제안한 문구(가정 C2). */
export const ACCOUNT_PROPOSED_COPY = {
  loginFailed: "로그인하지 못했어요. 다시 시도해 주세요.", // 9-1
  deletionInProgress: "계정을 삭제하고 있어요. 끝나면 새로 시작할 수 있어요.", // 9-7
  ageError: "나이는 6~12 사이로 넣어 주세요", // 9-2
  childSaveFailed: "저장하지 못했어요. 이전 값이 그대로예요.", // 9-2
  consentSaveFailed: "저장하지 못했어요", // interaction-spec 4절 02
  deviceLine: {
    STABLE: "안정", // 9-3
    UNSTABLE: "불안정", // 9-3
    DISCONNECTED: "끊김", // 9-3
    UNRESPONSIVE: "응답 없음", // 9-3
    NONE: "등록 안 됨", // 9-3
  },
  lockedDisconnected: "기기 연결이 끊겼어요. 전원과 와이파이를 확인해 주세요.", // 9-4
  lockedUnresponsive: "기기가 응답하지 않아요. 기기를 다시 켜 보세요.", // 9-4
  unresponsiveBody: "기기가 응답하지 않아요. 기기를 다시 켜 보세요.", // 9-8
  networkUnknownPrefix: "확인 안 됨", // interaction-spec 4절 05 '확인 안 됨 · 마지막 hh:mm'
  stopLengthError: "2~20자로 넣어 주세요", // 9-11
  stopDuplicateError: "이미 있는 표현이에요", // 9-11
  stopMaxError: "표현은 5개까지 담을 수 있어요", // 9-11
  stopSaveFailed: "저장하지 못했어요. 다시 시도해 주세요.", // 9-11
} as const;

/** 어느 설계 문서에도 글이 없어 U2가 임시로 둔 문구(report.md 목록). */
export const ACCOUNT_TEMP_COPY = {
  authenticating: "로그인하고 있어요", // 01 인증 중 진행 글(mockups S-01 상태 '인증 중', 글 없음)
  sessionExpired: "로그인이 필요해요.", // AC1.1.9 401 안내 토스트(errors.md UNAUTHENTICATED 문장과 같다)
  settingsButton: "설정 열기", // 04 「⚙」의 보조기기 이름(화면 글은 ⚙ 기호)
  settingsIcon: "⚙", // mockups S-04 「⚙」
  closeSheet: "닫기", // 시트 「←」의 보조기기 이름
  deleteFailed: "계정 삭제를 접수하지 못했어요. 다시 시도해 주세요.", // P7 실패 토스트
  logoutFailed: "로그아웃하지 못했어요. 다시 시도해 주세요.", // SET0 · P5 실패 토스트
  interestsHint: "쉼표로 나눠 5개까지, 하나에 10자까지 넣어요.", // 03 관심사 칸 도움말(초과 입력 막음 안내)
  networkUnknown: "기기 상태를 확인할 수 없어요", // 05 판정 카드 '확인 불가' 사유(mockups 9-16과 같은 문장)
  ageUnit: "세",
  stopSummaryPrefix: "추가 표현 ", // C.S-05.7.10 '추가 표현 2개 · "이제 그만" · "끝낼래"'의 앞
  stopSummaryCountSuffix: "개",
  quoteOpen: "“",
  quoteClose: "”",
  separator: " · ",
  editExpression: "표현 고치기", // SET-01 표현 카드의 보조기기 이름(누르면 수정 칸)
} as const;

// ───────────── 자리를 채우는 문구 ─────────────

/** '민준 · 7세'(C.S-04.3.3). */
export function childNameAgeLine(name: string, age: number): string {
  return `${name}${ACCOUNT_TEMP_COPY.separator}${age}${ACCOUNT_COPY.child.ageSuffix}`;
}

/** '7세 · 공룡, 블록 놀이'(C.S-03.2.3). 관심사가 없으면 나이만. */
export function childAgeInterestsLine(age: number, interests: readonly string[]): string {
  const ageText = `${age}${ACCOUNT_COPY.child.ageSuffix}`;
  return interests.length === 0 ? ageText : `${ageText}${ACCOUNT_TEMP_COPY.separator}${interests.join(", ")}`;
}

/** '기기 · 안정'(C.S-03.2.4 · 9-3). */
export function childDeviceLine(stateName: string): string {
  return `${ACCOUNT_COPY.child.deviceLinePrefix}${stateName}`;
}

/** '{아이}의 기록 · 리포트 · … 저장해 두세요.'(C.P-07.65.2). 이름이 없으면 '아이'. */
export function deleteDialogBody(childName: string | null): string {
  return `${childName ?? ACCOUNT_COPY.home.childSection}${ACCOUNT_COPY.deleteDialog.bodySuffix}`;
}

/** '기기 등록 · 완료 (7F3K)'(C.S-05.7.3). */
export function registrationDoneLine(codePrefix: string | null): string {
  return codePrefix === null
    ? ACCOUNT_COPY.device.registrationDonePrefix.replace(" (", "")
    : `${ACCOUNT_COPY.device.registrationDonePrefix}${codePrefix}${ACCOUNT_COPY.device.registrationDoneSuffix}`;
}

/** '기기 네트워크 현황 · 안정'(C.S-05.7.4). */
export function networkLine(stateName: string): string {
  return `${ACCOUNT_COPY.device.networkPrefix}${stateName}`;
}

/** '지연 0.3초'(C.S-05.7.5). 밀리초 → 초 한 자리. */
export function rttText(rttMs: number): string {
  return `${ACCOUNT_COPY.device.rttPrefix}${(Math.round(rttMs / 100) / 10).toString()}${ACCOUNT_COPY.device.secondsSuffix}`;
}

/** '끊김 3회'(C.S-05.61.5). */
export function disconnectsText(count: number): string {
  return `${ACCOUNT_PROPOSED_COPY.deviceLine.DISCONNECTED} ${count}${ACCOUNT_COPY.device.disconnectsSuffix}`;
}

/** '10:34부터'(C.S-05.62.5). */
export function sinceText(hhmm: string): string {
  return `${hhmm}${ACCOUNT_COPY.device.sinceSuffix}`;
}

/** '민준의 종료 표현'(C.S-05.6.8 · C.S-SET1.9.1). */
export function stopSectionTitle(childName: string): string {
  return `${childName}${ACCOUNT_COPY.device.stopSectionSuffix}`;
}

/** '"무중아 그만할래"'(C.S-05.6.9). */
export function quoted(text: string): string {
  return `${ACCOUNT_TEMP_COPY.quoteOpen}${text}${ACCOUNT_TEMP_COPY.quoteClose}`;
}

/** '추가 표현 2개 · "이제 그만" · "끝낼래"'(C.S-05.7.10). */
export function stopSummary(texts: readonly string[]): string {
  const head = `${ACCOUNT_TEMP_COPY.stopSummaryPrefix}${texts.length}${ACCOUNT_TEMP_COPY.stopSummaryCountSuffix}`;
  return [head, ...texts.map(quoted)].join(ACCOUNT_TEMP_COPY.separator);
}

export const BIRTHDATE_COPY = {
  label: "생년월일", year: "년도", month: "월", day: "일",
  hint: "등록일 기준 만 6세 이상~만 13세 미만으로 등록할 수 있어요.",
  editHint: "생년월일 수정은 최초 등록일의 만 나이로 확인해요. 등록 후 나이가 늘어나도 계속 이용할 수 있어요.",
  invalid: "등록일 기준 만 6세 이상~만 13세 미만인 생년월일을 선택해 주세요.",
} as const;
export const currentAgeLine = (age: number) => `현재 만 ${age}세`;
export const birthDateLine = (date: string) => `생년월일 · ${date}`;
