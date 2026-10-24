package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.nonNegative;

/**
 * U12 새 연습 흐름의 설정(시간 값이 아니라 timing.md에 두지 않고 {@code contracts/ai-io.md} 10.14 표가 정본이다).
 * {@code retryMax}는 장면당 '다시 해 보기' 최대 횟수(기본 1 — C안은 도움 · 다시 해 보기를 장면마다 한 번 둔다, docs/practice-direct-ask.md 3.3),
 * {@code reexplainMax}는 장면당 상황 다시 설명 최대 횟수(기본 1, AC1.3.x)다. 0은 '하지 않음'이라 허용한다.
 * {@code flexibleDialogue}는 연습 대화 경로를 고른다: 거짓(기본, 키가 없어도 거짓)이면 C안 '무중이 직접 묻기', 참이면 유연 대화다. 유연 대화는
 * 설정 하나로 되돌릴 수 있게 남겨 둔 경로다(같은 문서 7.1). 시간 한도는 {@code timing.conversation.*}가 맡는다.
 */
public record RealtimePracticeFlowProperties(Integer retryMax, Integer reexplainMax, boolean flexibleDialogue) {
    public RealtimePracticeFlowProperties {
        nonNegative(retryMax, "mujoong.practice-flow.retry-max");
        nonNegative(reexplainMax, "mujoong.practice-flow.reexplain-max");
    }
}
