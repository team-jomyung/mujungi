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
 * S2 이력 버전 확인 (RD-12 S2 · BR8.2 · J116): 앱이 들고 온 최고 버전(V9 → "9") 이상이면 통과, 미적용 · 낮은 버전(기준선 0만) · 읽기 실패는
 * 기동 거부이고 거부 줄에는 errorKind · 표 이름 · 버전 숫자만 있다. 버전 비교는 숫자 단위다.
 */
@Tag("u1")
class SchemaVersionCheckTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SchemaVersionCheck check = new SchemaVersionCheck(jdbc);
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
    void appliedVersionAtOrAboveTheBundledMigrationPasses() {
        // 앱이 들고 온 최고 버전: V1(U1) · V2(U2) · V3(U4a) · V4(U5a) · V5(U6) · V6(U7) · V7(U5b) · V8(U15) · V9(U14)
        assertThat(SchemaVersionCheck.expectedVersion()).isEqualTo("9");
        history(List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"));

        assertThat(check.verify()).isEqualTo("9");

        history(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"));
        assertThat(check.verify()).isEqualTo("10");
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

        assertThatThrownBy(check::verify).isInstanceOf(IllegalStateException.class).hasMessageContaining("0 < 기대 9");
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
            return List.of("1", "2", "3", "4", "5", "6", "7", "8", "9");
        });

        assertThat(check.verify()).isEqualTo("9");
    }

    @Test
    void historyBeforeTheU5aMigrationCannotStartTheApplication() {
        history(List.of("1", "2", "3"));
        assertThatThrownBy(check::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3 < 기대 9");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("SchemaVersionBehind");
    }

    private void history(List<String> versions) {
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(versions);
    }
    @Test
    void historyAtThePreviousU15VersionIsStillBehind() {
        history(List.of("1", "2", "3", "4", "5", "6", "7", "8"));
        assertThatThrownBy(check::verify).isInstanceOf(IllegalStateException.class).hasMessageContaining("8 < 기대 9");
    }
}
