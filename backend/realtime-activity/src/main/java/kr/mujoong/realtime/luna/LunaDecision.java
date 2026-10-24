package kr.mujoong.realtime.luna;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Decisions API 판정 성공 결과 — realtime (FT-1023-d). 응답 검증(질문 이름 · 선택지 안 · 확률이 모든 선택지를 덮음 · 0 ~ 1 · 고른 선택지의 확률이 가장
 * 높음)을 통과한 값만 담는다. 확률은 그대로 돌려주고 1 · 2순위 · 하한 판단은 판정 구현(U11)이 한다. 공급자 {@code confidence}는 담지 않는다(판정
 * 규칙은 선택지 확률 기준이다). 문자열 표현은 값을 숨긴다(BR6.2).
 *
 * @param choice 공급자가 고른 선택지
 * @param probabilities 선택지별 확률(보낸 선택지 순서)
 * @param responseModel 공급자 응답의 model 값
 * @param promptVersion 요청 때 보낸 프롬프트 판
 * @param latency 시도 지연
 * @param attemptsUsed 쓴 시도 수(언제나 1, NONE)
 */
public record LunaDecision(String choice, List<Probability> probabilities, String responseModel, String promptVersion,
        Duration latency, int attemptsUsed) {

    public LunaDecision {
        Objects.requireNonNull(choice, "choice");
        probabilities = List.copyOf(probabilities);
        Objects.requireNonNull(responseModel, "responseModel");
        Objects.requireNonNull(promptVersion, "promptVersion");
        Objects.requireNonNull(latency, "latency");
        if (attemptsUsed != 1) {
            throw new IllegalArgumentException("realtime 판정 호출은 1회다(NONE)");
        }
    }

    @Override
    public String toString() {
        return "LunaDecision[redacted]";
    }

    /** 선택지 하나의 확률(0 ~ 1). */
    public record Probability(String value, double probability) {

        public Probability {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String toString() {
            return "Probability[redacted]";
        }
    }
}
