package kr.mujoong.core.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** JIRA-280: 관리자 필터는 Asia/Seoul 날짜 · 최대 366일만 받고, 검색어를 문자열 표현에 내지 않는다. */
@Tag("admin")
class AdminFilterTest {

    @Test
    void datesBecomeAHalfOpenSeoulRange() {
        AdminFilter filter = AdminFilter.parse("2026-10-01", "2026-10-31", "Asia/Seoul", "PRACTICE", null, null, null, null, "COMPLETED");

        assertThat(filter.from()).isEqualTo(Instant.parse("2026-09-30T15:00:00Z"));
        assertThat(filter.to()).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
        assertThat(filter.type()).isEqualTo("PRACTICE");
        assertThat(filter.status()).isEqualTo("COMPLETED");
    }

    @Test
    void rangeIsAtMost366DaysIncludingBothEnds() {
        assertThat(AdminFilter.parse("2024-01-01", "2024-12-31", null, null, null, null, null, null, null)).isNotNull();
        assertRejected(() -> AdminFilter.parse("2024-01-01", "2025-01-01", null, null, null, null, null, null, null));
        assertRejected(() -> AdminFilter.parse("2026-10-02", "2026-10-01", null, null, null, null, null, null, null));
    }

    @Test
    void malformedValuesAreValidationFailures() {
        assertRejected(() -> AdminFilter.parse(null, "2026-10-01", null, null, null, null, null, null, null));
        assertRejected(() -> AdminFilter.parse("2026-10-1", "2026-10-01", null, null, null, null, null, null, null));
        assertRejected(() -> AdminFilter.parse("2026-10-01", "2026-10-01", "UTC", null, null, null, null, null, null));
        assertRejected(() -> AdminFilter.parse("2026-10-01", "2026-10-01", null, "ALL", null, null, null, null, null));
        assertRejected(() -> AdminFilter.parse("2026-10-01", "2026-10-01", null, null, null, "not-a-uuid", null, null, null));
        assertRejected(() -> AdminFilter.parse("2026-10-01", "2026-10-01", null, null, null, null, null, "D-206", null));
        assertRejected(() -> AdminFilter.parse("2026-10-01", "2026-10-01", null, null, null, null, null, null, "ENDED"));
        assertRejected(() -> AdminFilter.parse("2026-10-01", "2026-10-01", null, null, "가".repeat(51), null, null, null, null));
        assertRejected(() -> AdminFilter.page("-1"));
        assertRejected(() -> AdminFilter.pageSize("0"));
        assertRejected(() -> AdminFilter.pageSize("51"));
    }

    @Test
    void pagingDefaultsToTheFirstPageOfEight() {
        assertThat(AdminFilter.page(null)).isZero();
        assertThat(AdminFilter.pageSize(null)).isEqualTo(8);
        assertThat(AdminFilter.pageSize("50")).isEqualTo(50);
    }

    @Test
    void searchTextMatchesLiterallyAndStaysOutOfTheStringForm() {
        AdminFilter filter = AdminFilter.parse("2026-10-01", "2026-10-01", null, null, "  민_준%  ", null, null, null, null);

        assertThat(filter.query()).isEqualTo("민_준%");
        assertThat(filter.likePattern()).isEqualTo("%민\\_준\\%%");
        assertThat(filter.toString()).doesNotContain("민");
        assertThat(AdminFilter.parse("2026-10-01", "2026-10-01", null, null, "   ", null, null, null, null).query()).isNull();
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CoreApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
