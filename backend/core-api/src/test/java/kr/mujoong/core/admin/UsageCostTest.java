package kr.mujoong.core.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import kr.mujoong.core.admin.AdminPricingProperties.TokenRates;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** JIRA-280: USD 예상 비용 계산과 금액 문자열 형식(지수 표기 없음). */
@Tag("admin")
class UsageCostTest {

    private static final TokenRates RESPONSES = new TokenRates(new BigDecimal("0.10"), new BigDecimal("0.01"),
            new BigDecimal("0.125"), new BigDecimal("0.50"));
    private static final TokenRates DECISIONS = new TokenRates(new BigDecimal("0.10"), new BigDecimal("0.01"),
            new BigDecimal("0.125"), BigDecimal.ZERO);

    @Test
    void voiceIsBilledPerSecondFromThePerMinuteRate() {
        // 3분 = $0.15, 312.4초 = 312.4 ÷ 60 × 0.05
        assertThat(UsageCost.money(UsageCost.voice(180, new BigDecimal("0.05")))).isEqualTo("0.15");
        assertThat(UsageCost.money(UsageCost.voice(312.4, new BigDecimal("0.05")))).isEqualTo("0.26033333");
    }

    @Test
    void cacheReadAndWriteAreTakenOutOfInputAndReasoningIsNotAddedAgain() {
        // 일반 입력 30,000 − 2,000 − 1,000 = 27,000
        BigDecimal usd = UsageCost.tokens(30_000, 2_000, 1_000, 6_000, RESPONSES);
        // (27000 × 0.10 + 2000 × 0.01 + 1000 × 0.125 + 6000 × 0.50) ÷ 1,000,000 = 0.005845
        assertThat(UsageCost.money(usd)).isEqualTo("0.005845");
    }

    @Test
    void decisionsOutputIsFree() {
        assertThat(UsageCost.money(UsageCost.tokens(5_400, 1_200, 0, 60, DECISIONS))).isEqualTo("0.000432");
        assertThat(UsageCost.money(UsageCost.tokens(0, 0, 0, 1_000_000, DECISIONS))).isEqualTo("0");
    }

    @Test
    void moneyIsAPlainDecimalStringWithoutExponentSignOrComma() {
        String tiny = UsageCost.money(UsageCost.tokens(0, 1, 0, 0, RESPONSES));
        assertThat(tiny).isEqualTo("0.00000001").matches("^\\d+(\\.\\d+)?$");
        assertThat(UsageCost.money(new BigDecimal("1234567.5"))).isEqualTo("1234567.5");
        assertThat(UsageCost.money(BigDecimal.ZERO)).isEqualTo("0");
        assertThat(UsageCost.money(null)).isNull();
    }

    @Test
    void moneyRoundsHalfUpAtEightDecimals() {
        assertThat(UsageCost.money(new BigDecimal("0.000000005"))).isEqualTo("0.00000001");
        assertThat(UsageCost.money(new BigDecimal("0.000000004999"))).isEqualTo("0");
    }

    @Test
    void cachedNeverPushesUncachedInputBelowZero() {
        assertThat(UsageCost.money(UsageCost.tokens(100, 80, 40, 0, RESPONSES)))
                .isEqualTo(UsageCost.money(UsageCost.tokens(120, 80, 40, 0, RESPONSES)));
    }
}
