package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.nonNegative;

/**
 * U12 새 연습 흐름의 횟수 한도(BR9.2, 시간 값이 아니라 timing.md에 두지 않고 {@code contracts/ai-io.md} 10.14 표가 정본이다).
 * {@code retryMax}는 장면당 '다시 해 보기' 최대 횟수(기본 2, AC1.5.4), {@code reexplainMax}는 장면당 상황 다시 설명 최대 횟수(기본 1,
 * AC1.3.x)다. 0은 '하지 않음'이라 허용한다. 시간 한도는 {@code timing.conversation.*}가 맡는다.
 */
public record RealtimePracticeFlowProperties(Integer retryMax, Integer reexplainMax) {
    public RealtimePracticeFlowProperties {
        nonNegative(retryMax, "mujoong.practice-flow.retry-max");
        nonNegative(reexplainMax, "mujoong.practice-flow.reexplain-max");
    }
}
