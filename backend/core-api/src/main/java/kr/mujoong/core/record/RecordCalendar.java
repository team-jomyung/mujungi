package kr.mujoong.core.record;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 26 달력의 날짜 · 달 경계 (U4a ActivityRecord · AC5.1.1 · AC5.1.2 · U4a-J8).
 *
 * <p>보호자 화면은 시각을 Asia/Seoul로 보인다(frontend timeText.ts). 날짜 · 달 경계도 같은 시간대의 자정이다. 인자는 엄격한 형식만 받는다
 * (달 {@code yyyy-MM}, 날 {@code yyyy-MM-dd}).
 */
public final class RecordCalendar {

    /** 화면 표시와 같은 시간대. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private RecordCalendar() {
    }

    /** [시작, 끝) 시각 범위. */
    public record Range(Instant from, Instant to) {
    }

    /** {@code yyyy-MM}을 읽는다(형식이 아니면 빈 값). */
    public static Optional<YearMonth> month(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}")) {
            return Optional.empty();
        }
        try {
            return Optional.of(YearMonth.parse(value));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** {@code yyyy-MM-dd}를 읽는다(형식이 아니면 빈 값). */
    public static Optional<LocalDate> date(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** 달 하나의 범위(그달 1일 0시 ~ 다음 달 1일 0시, Asia/Seoul). */
    public static Range of(YearMonth month) {
        return new Range(month.atDay(1).atStartOfDay(ZONE).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant());
    }

    /** 날 하나의 범위(그날 0시 ~ 다음 날 0시, Asia/Seoul). */
    public static Range of(LocalDate day) {
        return new Range(day.atStartOfDay(ZONE).toInstant(), day.plusDays(1).atStartOfDay(ZONE).toInstant());
    }
}
