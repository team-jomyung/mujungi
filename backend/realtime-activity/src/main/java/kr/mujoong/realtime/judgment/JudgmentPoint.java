package kr.mujoong.realtime.judgment;

/**
 * 판정 지점 (U11 FR1 · 원 기록 C-DS2). J1은 참여 확인(시작 응답), J6은 종료 의도이고 둘은 실행부가 직접 연다. J2 ~ J5는 연습 확장점이
 * 능력 {@code requestJudgment}로 연다(BR8.2). 선택지 코드는 판 리소스({@code judgment/option-sets-v1.json})가 정한다.
 */
public enum JudgmentPoint {
    J1,
    J2,
    J3,
    J4,
    J5,
    J6;

    /** 실행부가 직접 여는 지점인가(BR7.2): 자유대화는 J1 · J6뿐이다. */
    public boolean openedByExecutor() {
        return this == J1 || this == J6;
    }
}
