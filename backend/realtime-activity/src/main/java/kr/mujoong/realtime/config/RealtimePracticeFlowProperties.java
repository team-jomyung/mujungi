package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.nonNegative;
import static kr.mujoong.realtime.config.RealtimeProperties.required;
import static kr.mujoong.realtime.config.RealtimeProperties.unitInterval;

/**
 * U12 새 연습 흐름의 설정(시간 값이 아니라 timing.md에 두지 않고 {@code contracts/ai-io.md} 10.14 표가 정본이다).
 * {@code retryMax}는 장면당 '다시 해 보기' 최대 횟수(기본 1 — C안은 도움 · 다시 해 보기를 장면마다 한 번 둔다, docs/practice-direct-ask.md 3.3),
 * {@code reexplainMax}는 장면당 상황 다시 설명 최대 횟수(기본 1, AC1.3.x)다. 0은 '하지 않음'이라 허용한다.
 * {@code flexibleDialogue}는 연습 대화 경로를 고른다: 거짓(기본, 키가 없어도 거짓)이면 C안 '무중이 직접 묻기', 참이면 유연 대화다. 유연 대화는
 * 설정 하나로 되돌릴 수 있게 남겨 둔 경로다(같은 문서 7.1). {@code directAsk}는 C안만 쓰는 판정 설정이다. 시간 한도는 {@code timing.conversation.*}가
 * 맡는다.
 */
public record RealtimePracticeFlowProperties(Integer retryMax, Integer reexplainMax, boolean flexibleDialogue, DirectAsk directAsk) {
    public RealtimePracticeFlowProperties {
        nonNegative(retryMax, "mujoong.practice-flow.retry-max");
        nonNegative(reexplainMax, "mujoong.practice-flow.reexplain-max");
        required(directAsk, "mujoong.practice-flow.direct-ask");
    }

    /**
     * C안 '무중이 직접 묻기'만 쓰는 판정 설정(시간 값이 아니다, ai-io.md 10.14 · 편차 FT-1024-h).
     *
     * @param j2MinConfidence 기회(J2) 판정에만 쓰는 낮춘 확률 하한(0 이상 1 이하, 기본 0.45). 흐름이 판정 요청에 데이터로 싣고 실행부 검증기가 전역 하한
     *     ({@code mujoong.judgment.min-confidence})보다 낮을 때만 쓴다. 다른 지점 · 유연 대화 · 최소 차이는 그대로다. 허용 범위(0.3 이상)는 연습 흐름
     *     설정 객체가 기동 때 한 번 더 본다
     */
    public record DirectAsk(Double j2MinConfidence) {
        public DirectAsk {
            unitInterval(j2MinConfidence, "mujoong.practice-flow.direct-ask.j2-min-confidence");
        }
    }
}
