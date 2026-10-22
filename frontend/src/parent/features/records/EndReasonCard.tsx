import type { RecordDetail } from "../../common/api/results";
import { formatHhmm } from "@/shared/copy/timeText";
import { RESULT_TEMP_COPY as C, endReasonLine, gapLine } from "@/shared/copy/result";
/** 수신 공백을 아이의 침묵·거부로 해석하지 않는다. */
export function EndReasonCard({ record }: { record: RecordDetail }) {
  const { session, gaps, recordScope, endConfirmed } = record;
  const technical = gaps.length > 0 || !endConfirmed || ["ABORTED", "FAILED", "ENDED_UNCONFIRMED"].includes(session.status);
  const reason = session.endDetail === "PRACTICE_OUTPUT_FAILED" ? "음성 안내를 준비하지 못해 연습이 중단됐어요" : session.endDetail === "PRACTICE_COMPLETED" ? "연습을 마쳤어요" : session.status === "FAILED" || session.status === "ENDED_UNCONFIRMED" ? "기기 응답 없이 끝냈어요" : ({ GUARDIAN_STOP: "보호자가 끝냈어요", CHILD_STOP: "아이가 끝냈어요", SILENCE: "말이 없어 자동으로 끝났어요", DISCONNECT: "연결이 끊겨 끝났어요", CONSENT_WITHDRAWN: "동의가 철회되어 끝났어요" } as Record<string, string>)[session.endReason ?? ""] ?? "활동이 끝났어요";
  return <section className="mj-card" data-testid="record-end-reason"><p>{endReasonLine(reason, formatHhmm(session.endedAt) ?? "시각 미확인")}</p>{technical ? <p data-testid="record-end-detail">{endConfirmed && session.status !== "FAILED" ? `${formatHhmm(session.endedAt) ?? ""} 종료 확인됨 · ` : ""}{recordScope ? `${formatHhmm(recordScope.from)} ~ ${formatHhmm(recordScope.to)} 기록 확보` : "확보한 기록이 없어요"}{gaps.map((g, i) => <span key={i}>{gapLine(formatHhmm(g.from), formatHhmm(g.to))}</span>)}</p> : null}{!endConfirmed || session.status === "FAILED" ? <p data-testid="record-end-unconfirmed">{C.unconfirmed}</p> : null}</section>;
}
