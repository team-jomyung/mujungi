import { describe, expect, it } from "vitest";
import { confirmedStopQuote, presentDone } from "@/parent/features/done/presentation";
import { parseLiveView } from "@/parent/common/api/types";
import { bubble, liveViewBody } from "../../../support/fixtures";
const facts = { schemaVersion: 1, notice: "COMPLETED", participation: "ACCEPTED", goodbye: "PLAYING", confirmedStopBubbleSeq: 7 };
const row = { ...bubble({ seq: 7, speaker: "CHILD", text: "오늘은 여기까지 할래" }), startedAt: "2026-10-15T01:12:00Z" };
const view = (extra: Record<string, unknown> = {}) => parseLiveView(liveViewBody("ENDED", { endReason: "CHILD_STOP", guardrail: facts, items: [row], endedAt: "2026-10-15T01:21:00Z", ...extra }));
describe("실제 종료 인용", () => {
  it("등록 표현과 다른 정확한 CHILD 원문과 그 행 시각만 표시한다", () => expect(confirmedStopQuote(view())).toBe('"오늘은 여기까지 할래" · 10:12'));
  it("참조 없음/null/다른 seq는 마지막 CHILD나 기본 표현으로 대체하지 않는다", () => {
    for (const seq of [null, 8]) expect(confirmedStopQuote(view({ guardrail: { ...facts, confirmedStopBubbleSeq: seq } }))).toBeNull();
    expect(confirmedStopQuote(view({ guardrail: null }))).toBeNull();
  });
  it("공개 items에 없는 숨김/삭제 행은 인용하지 않는다", () => expect(confirmedStopQuote(view({ items: [] }))).toBeNull());
  it("다른 화자/미완성/불확실/공백 행을 인용하지 않는다", () => {
    for (const patch of [{ speaker: "AI" }, { final: false }, { uncertain: true }, { uncertain: undefined }, { text: " \n " }]) expect(confirmedStopQuote(view({ items: [{ ...row, ...patch }] }))).toBeNull();
  });
  it("유효하지 않은 행 시각은 생략하고 종료 시각을 대입하지 않는다", () => expect(confirmedStopQuote(view({ items: [{ ...row, startedAt: "invalid" }] }))).toBe('"오늘은 여기까지 할래"'));
  it("한글/따옴표/역슬래시/줄바꿈/HTML처럼 보이는 원문을 그대로 전달한다", () => {
    const text = '한글 "그만" \\ \n<img src=x onerror=alert(1)>';
    expect(confirmedStopQuote(view({ items: [{ ...row, text }] }))).toBe(`"${text}" · 10:12`);
  });
  it("종료 사유 제목과 인용 줄은 별개이며 확인 상태도 보존한다", () => {
    const p = presentDone(view({ confirmed: true }), "하늘");
    expect(p.reasonTitle).toBe("하늘이 끝내자고 했어요"); expect(p.reasonBody).toBeNull();
    expect(p.stopQuote).toBe('"오늘은 여기까지 할래" · 10:12'); expect(p.unconfirmedLine).toBeNull();
  });
  it("연습/미확인 모드/다른 종료 사유/미조회는 인용하지 않는다", () => {
    for (const extra of [{ activityType: "PRACTICE" }, { activityType: "FUTURE" }, { endReason: "GUARDIAN_STOP" }]) expect(confirmedStopQuote(view(extra))).toBeNull();
    expect(confirmedStopQuote(undefined)).toBeNull();
  });
});
