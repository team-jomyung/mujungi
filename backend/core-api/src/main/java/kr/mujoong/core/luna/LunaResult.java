package kr.mujoong.core.luna;

import java.time.Duration;
import java.util.Objects;

/**
 * Luna 호출 성공 결과 (U9 entities LunaResult · BR4.3). 문자열 표현은 본문을 숨긴다(BR6.2).
 *
 * @param outputJson 스키마 검증(있을 때)을 통과한 출력 JSON 텍스트
 * @param responseModel 공급자 응답의 model 값
 * @param promptVersion 요청 때 보낸 프롬프트 판
 * @param latency 성공한 시도의 지연
 * @param attemptsUsed 쓴 시도 수(1 ~ 3)
 * @param schemaRetried 스키마 불일치 재요청을 썼는가(U10이 내용 재생성 예산에 센다)
 */
public record LunaResult(String outputJson, String responseModel, String promptVersion, Duration latency, int attemptsUsed,
        boolean schemaRetried) {

    public LunaResult {
        Objects.requireNonNull(outputJson, "outputJson");
        Objects.requireNonNull(responseModel, "responseModel");
        Objects.requireNonNull(promptVersion, "promptVersion");
        Objects.requireNonNull(latency, "latency");
        if (attemptsUsed < 1 || attemptsUsed > 3) {
            throw new IllegalArgumentException("attemptsUsed는 1 ~ 3이다");
        }
    }

    @Override
    public String toString() {
        return "LunaResult[redacted]";
    }
}
