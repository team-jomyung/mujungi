package kr.mujoong.realtime.luna;

import java.time.Duration;
import java.util.Objects;

/**
 * Luna 판정 호출 성공 결과 — realtime (U9 entities LunaResult · BR4.3). 구조화 출력의 확률 값은 그대로 돌려준다(U11이 쓴다). 문자열 표현은
 * 본문을 숨긴다(BR6.2).
 *
 * @param outputJson 계약 스키마 검증을 통과한 출력 JSON 텍스트
 * @param responseModel 공급자 응답의 model 값
 * @param promptVersion 요청 때 보낸 프롬프트 판
 * @param latency 시도 지연
 * @param attemptsUsed 쓴 시도 수(언제나 1, NONE)
 */
public record LunaResult(String outputJson, String responseModel, String promptVersion, Duration latency, int attemptsUsed) {

    public LunaResult {
        Objects.requireNonNull(outputJson, "outputJson");
        Objects.requireNonNull(responseModel, "responseModel");
        Objects.requireNonNull(promptVersion, "promptVersion");
        Objects.requireNonNull(latency, "latency");
        if (attemptsUsed != 1) {
            throw new IllegalArgumentException("realtime 판정 호출은 1회다(NONE)");
        }
    }

    @Override
    public String toString() {
        return "LunaResult[redacted]";
    }
}
