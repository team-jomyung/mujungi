package kr.mujoong.realtime.practice;

/** 계약 9절의 세 필드만 허용한다. 판정 기준·정답·보호자 원문을 받는 필드가 없다. */
public record CueBrief(String situationSummary, String partnerUtterance, String focus) {
    public CueBrief {
        PracticeChecks.text(situationSummary, "상황 요약"); PracticeChecks.text(partnerUtterance, "상대 발화");
        PracticeChecks.text(focus, "도움 초점");
    }
    @Override public String toString() { return "CueBrief[내용 비공개]"; }
}
