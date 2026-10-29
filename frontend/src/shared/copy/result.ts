/** U6 임시 문구(TEMP_COPY). D 담당 확인 전 승인 설계의 문장을 사용한다. */
export const RESULT_TEMP_COPY = {
  records: "기록", readingRecords: "기록을 읽고 있어요", readingActivities: "활동을 읽고 있어요", recordReadFailed: "기록을 읽지 못했어요.", reportReadFailed: "리포트를 읽지 못했어요.", activitiesReadFailed: "활동을 읽지 못했어요.",
  viewRecord: "오늘 기록 보기", retryInRecord: "기록에서 다시 시도", helpPrefix: "제공한 도움 · ", tipConditionPrefix: "이럴 때 해 보세요 · ", limitationPrefix: "주의할 점 · ", evidencePrefix: "근거 · ",
  later: "나중에", savePdf: "PDF 저장", openFile: "파일 열기", toRecords: "기록으로", childInfo: "아이 정보 · 이름 · 나이만", cancel: "취소", save: "저장", rangeAgain: "범위 다시", missingSuffix: " · 자료 부족",
  backArrow: "←", previousArrow: "‹", nextArrow: "›", dot: " ·", declinedSuffix: " · 시작하지 못함",
  cleaning: "기록을 정리하고 있어요", done: "기록 정리가 끝났어요", failed: "기록을 정리하지 못했어요. 대화 기록은 남아 있어요.", insufficient: "정리할 자료가 부족해요. 대화 기록을 확인해 주세요.",
  emptyRecords: "아직 기록이 없어요. 활동을 마치면 여기에 기록이 남아요.", emptyDay: "활동이 없어요", noTips: "아직 보여 드릴 생활 팁이 없어요. 기록이 더 쌓이면 다시 살펴볼게요.", noCandidates: "아직 담을 연습 목표가 없어요.",
  unconfirmed: "끝나는 시각을 확인하지 못했어요. 확보한 기록까지만 보여 드려요.", notFound: "기록을 찾을 수 없어요", retry: "다시 시도", back: "뒤로", report: "상담용 리포트", reportCreate: "상담용 리포트 만들기",
  generating: "리포트를 만들고 있어요", generatingDetail: "고른 기록으로 10개 항목을 만들고 있어요.", reportFailed: "리포트를 만들지 못했어요", reportFailedDetail: "잠시 뒤 다시 시도해 주세요. 고른 범위는 그대로예요.", emptyRange: "이 기간엔 활동이 없어요", emptyRangeDetail: "다른 기간을 골라 주세요.",
  saveQuestion: "PDF로 저장할까요?", saveFailed: "저장하지 못했어요. 다시 시도해 주세요.", saved: "저장했어요", share: "공유하기 (문자 · 메일 · 메신저)", delivery: "전달은 보호자가 직접 해요. 앱이 자동으로 보내지 않아요.",
} as const;
export const sceneLabel = (number: number, chip: string) => `장면 ${number} · ${chip}`;
export const monthLabel = (month: string) => `${Number(month.slice(0, 4))}년 ${Number(month.slice(5))}월`;
export const recordTimeLine = (from: string, to: string, minutes: number) => `${from} ~ ${to} · ${minutes}분`;
export const endReasonLine = (reason: string, at: string) => `끝난 이유 · ${reason} · ${at}`;
export const gapLine = (from: string | null, to: string | null) => ` · ${from} ~ ${to ?? "끝 시각 미확인"} 미확인`;
export const durationLabel = (minutes: number) => ` · ${minutes}분`;
export const reportSummary = (from: string, to: string, count: number) => `${from} ~ ${to} · 활동 ${count}개`;
export const periodLine = (from: string, to: string) => `기간 · ${from} ~ ${to}`;
export const activityCountLine = (count: number) => `활동 · ${count}개`;
export const citationCountLine = (count: number) => `항목 · 10개 (인용 ${count}개 포함)`;
export const sectionTitle = (number: number, title: string) => `${number}. ${title}`;

/** 저장한 상담용 리포트 목록·재다운로드 문구. */
export const SAVED_REPORT_COPY = {
  title: "저장한 상담용 리포트", loading: "리포트를 불러오고 있어요.",
  failed: "리포트 목록을 불러오지 못했어요.", retry: "다시 시도",
  empty: "아직 저장한 리포트가 없어요. 리포트를 만든 뒤 PDF 저장을 눌러 주세요.",
  emptyPage: "이 페이지에 저장한 리포트가 없어요.",
  open: "리포트 다시 보기 · PDF 다운로드", download: "PDF 다시 다운로드",
  pages: "리포트 목록 페이지", previous: "이전", next: "다음",
} as const;
export const savedReportPeriod = (from: string, to: string) => `${from} ~ ${to}`;
export const savedReportSummary = (count: number, date: string) => `활동 ${count}개 · 저장일 ${date}`;
export const savedReportPage = (page: number) => `${page}페이지`;

export const UNSTARTED_RECORD_COPY = {
  status: "시작하지 못함",
  timeLabel: "시작 시각",
  timeUnknown: "시작 시각을 확인할 수 없어요",
} as const;

export const ACTIVITY_SELECTION_COPY = {
  filter: "대화 종류", all: "전체", freeTalk: "자유대화", practice: "연습대화",
  emptyFilter: "선택한 종류의 대화가 없어요.",
  instruction: "리포트에 담을 대화를 골라 주세요.",
  clear: "선택 모두 해제",
  check: "✓", timeUnknown: "시각 미확인", selectionLabel: "리포트에 담을 대화",
  pages: "대화 목록 페이지", previous: "이전", next: "다음",
} as const;
export const reportSelectionCount = (count: number) => `선택 ${count}개`;
export const reportActivityPosition = (total: number, from: number, to: number) => `전체 ${total}개 · ${from}–${to}번째`;
export const reportActivityLabel = (date: string, time: string | null, kind: string) => `${date} ${time ?? ACTIVITY_SELECTION_COPY.timeUnknown} · ${kind}`;
export const reportActivityPage = (page: number, total: number) => `${page} / ${total}페이지`;
