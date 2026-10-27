package kr.mujoong.core.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import kr.mujoong.core.admin.AdminPricingProperties.TokenRates;

/**
 * 저장된 사용량에 단가를 곱한 예상 비용(USD, JIRA-280). 서버는 토큰 · 초 합계에서 금액을 한 번 계산해 소수 {@value #SCALE}자리로 반올림한다
 * (음성 초당 단가가 끝나지 않는 소수라 반올림 없는 값은 없다). 반올림한 금액을 다시 더하지 않으므로 목록 행의 합과 위 합계는 마지막 자리에서 다를 수
 * 있다. 금액 문자열은 지수 표기 · 부호 · 쉼표 없이 {@code ^\d+(\.\d+)?$} 꼴이다.
 */
public final class UsageCost {

    /** 금액 소수 자릿수. */
    public static final int SCALE = 8;

    private static final BigDecimal SECONDS_PER_MINUTE = BigDecimal.valueOf(60);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final int WORK_SCALE = 20;

    private UsageCost() {
    }

    /** 음성 초 × 분당 단가 ÷ 60. */
    public static BigDecimal voice(double seconds, BigDecimal usdPerMinute) {
        return BigDecimal.valueOf(seconds).multiply(usdPerMinute).divide(SECONDS_PER_MINUTE, WORK_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 토큰 비용. 캐시 읽기 · 쓰기는 입력에 포함된 값이라 일반 입력 = 입력 − 캐시 읽기 − 캐시 쓰기다(0 아래로 내려가지 않는다). 추론 토큰은 출력에
     * 이미 들어 있어 따로 더하지 않는다.
     */
    public static BigDecimal tokens(long input, long cachedInput, long cacheWrite, long output, TokenRates rates) {
        long uncached = Math.max(0, input - cachedInput - cacheWrite);
        BigDecimal sum = BigDecimal.valueOf(uncached).multiply(rates.inputUsdPerMillion())
                .add(BigDecimal.valueOf(cachedInput).multiply(rates.cachedInputUsdPerMillion()))
                .add(BigDecimal.valueOf(cacheWrite).multiply(rates.cacheWriteUsdPerMillion()))
                .add(BigDecimal.valueOf(output).multiply(rates.outputUsdPerMillion()));
        return sum.divide(MILLION, WORK_SCALE, RoundingMode.HALF_UP);
    }

    /** 금액 문자열(소수 {@value #SCALE}자리 반올림, 뒤 0 제거, 0은 "0"). null이면 null. */
    public static String money(BigDecimal usd) {
        if (usd == null) {
            return null;
        }
        BigDecimal rounded = usd.setScale(SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
        return rounded.signum() == 0 ? "0" : rounded.toPlainString();
    }

    /** 계산 설명에 쓰는 단가 · 수량 문자열(천 단위 쉼표). */
    static String number(Number value) {
        DecimalFormat format = new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return format.format(value);
    }

    static String rate(BigDecimal value) {
        return "$" + value.stripTrailingZeros().toPlainString();
    }
}
