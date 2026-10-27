package kr.mujoong.core.admin;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 관리자 비용 화면의 단가(USD, JIRA-280 · contracts/ai-io.md 비용 절). 공식 요금표 값을 설정으로 두고, 조회할 때 저장된 토큰 · 초에 곱한다.
 * 단가를 바꾸면 지난 활동의 예상 비용도 새 단가로 다시 계산된다. 실제 청구액이 아니다.
 *
 * @param voiceUsdPerMinute GPT-Live 음성 세션 분당 단가(초 단위 과금)
 * @param responses Core Luna 호출(Responses API) 토큰 단가
 * @param decisions Realtime 진행 중 판정(Decisions API) 토큰 단가. 출력 토큰은 과금하지 않는다
 */
@ConfigurationProperties("mujoong.admin.pricing")
public record AdminPricingProperties(BigDecimal voiceUsdPerMinute, TokenRates responses, TokenRates decisions) {

    public AdminPricingProperties {
        nonNegative(voiceUsdPerMinute, "voice-usd-per-minute");
        if (responses == null || decisions == null) {
            throw new IllegalArgumentException("mujoong.admin.pricing 토큰 단가가 없다");
        }
    }

    /** 100만 토큰당 단가. 캐시 읽기 · 캐시 쓰기는 입력 토큰의 일부다. */
    public record TokenRates(BigDecimal inputUsdPerMillion, BigDecimal cachedInputUsdPerMillion,
            BigDecimal cacheWriteUsdPerMillion, BigDecimal outputUsdPerMillion) {

        public TokenRates {
            nonNegative(inputUsdPerMillion, "input-usd-per-million");
            nonNegative(cachedInputUsdPerMillion, "cached-input-usd-per-million");
            nonNegative(cacheWriteUsdPerMillion, "cache-write-usd-per-million");
            nonNegative(outputUsdPerMillion, "output-usd-per-million");
        }
    }

    private static void nonNegative(BigDecimal value, String key) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("mujoong.admin.pricing." + key + "는 0 이상이어야 한다");
        }
    }
}
