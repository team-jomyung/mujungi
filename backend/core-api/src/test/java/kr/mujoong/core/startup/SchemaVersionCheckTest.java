package kr.mujoong.core.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S2 이력 버전 확인 (RD-12 S2 · BR8.2 · J116): 앱이 들고 온 최고 버전 이상이면 통과, 미적용 · 낮은 버전(기준선 0만) · 읽기 실패는
 * 기동 거부이고 거부 줄에는 errorKind · 표 이름 · 버전 숫자만 있다. 버전 비교는 숫자 단위다.
 *
 * <p>시험 자료는 실제 마이그레이션 번호를 적지 않는다. 오래된 버전(0.1 · 기준선 0)과 앞선 버전(9000)은 어떤 마이그레이션이 더해져도 각각
 * 기대 버전보다 낮고 높다. 그래서 마이그레이션을 더해도 이 시험은 고치지 않는다.
 */
@Tag("skeleton")
class SchemaVersionCheckTest {

    /** 실제 마이그레이션은 V1부터라 언제나 기대 버전보다 낮은 버전. */
    private static final String OLDER = "0.1";
    /** 실제 마이그레이션 번호와 겹치지 않는 앞선 버전(다음 배포가 먼저 올린 이력). */
    private static final String NEWER = "9000";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SchemaVersionCheck check = new SchemaVersionCheck(jdbc);
    private final String expected = SchemaVersionCheck.expectedVersion();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private PrintStream originalErr;

    @BeforeEach
    void captureStderr() {
        originalErr = System.err;
        System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStderr() {
        System.setErr(originalErr);
    }

    @Test
    void theExpectedVersionIsTheHighestBundledMigrationAndIsAboveTheFixtures() {
        // 앱이 들고 온 마이그레이션에서 읽은 값이다(숫자를 적지 않는다). 시험 자료의 전제를 확인한다
        assertThat(SchemaVersionCheck.compareVersions(expected, OLDER)).isPositive();
        assertThat(SchemaVersionCheck.compareVersions(expected, "0")).isPositive();
        assertThat(SchemaVersionCheck.compareVersions(NEWER, expected)).isPositive();
    }

    @Test
    void appliedVersionAtOrAboveTheBundledMigrationPasses() {
        history(List.of("0", OLDER, expected));
        assertThat(check.verify()).isEqualTo(expected);

        history(List.of(OLDER, expected, NEWER));
        assertThat(check.verify()).isEqualTo(NEWER);
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void emptyHistoryIsRejectedAsNotMigrated() {
        history(List.of());

        assertThatThrownBy(check::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SchemaVersionCheck.HISTORY_TABLE);
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("SchemaNotMigrated")
                .contains(StartupFailureLine.PREFIX);
    }

    @Test
    void onlyTheBaselineRowIsRejectedAsBehind() {
        history(List.of("0"));

        assertThatThrownBy(check::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0 < 기대 " + expected);
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("SchemaVersionBehind");
    }

    @Test
    void anOlderHistoryCannotStartTheApplication() {
        history(List.of("0", OLDER));

        assertThatThrownBy(check::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(OLDER + " < 기대 " + expected);
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("SchemaVersionBehind");
    }

    @Test
    void unreadableHistoryIsRejectedWithoutTheDriverMessage() {
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenThrow(new DataAccessResourceFailureException("permission denied for host secret-db.invalid"));

        assertThatThrownBy(check::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret-db");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("SchemaHistoryUnreadable")
                .doesNotContain("secret-db");
    }

    @Test
    void versionsCompareNumericallyNotAsText() {
        assertThat(SchemaVersionCheck.compareVersions("2", "10")).isNegative();
        assertThat(SchemaVersionCheck.compareVersions("1.1", "1")).isPositive();
        assertThat(SchemaVersionCheck.compareVersions("1.0", "1")).isZero();
        assertThat(SchemaVersionCheck.compareVersions("1.10", "1.9")).isPositive();
    }

    @Test
    void historyIsReadOnlyForSuccessfulVersionedRows() {
        when(jdbc.queryForList(anyString(), eq(String.class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            assertThat(sql).contains(SchemaVersionCheck.HISTORY_TABLE).contains("success").contains("version is not null");
            return List.of(OLDER, expected);
        });

        assertThat(check.verify()).isEqualTo(expected);
    }

    private void history(List<String> versions) {
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(versions);
    }
}
