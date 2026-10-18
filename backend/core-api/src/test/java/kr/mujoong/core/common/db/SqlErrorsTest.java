package kr.mujoong.core.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * {@link SqlErrors} 단위 시험(U1 3.5 Step 17 · RD-22). 원인 사슬에서 SQLSTATE · 위반 제약 이름을 꺼내는지 본다.
 */
@Tag("skeleton")
class SqlErrorsTest {

    @ParameterizedTest
    @ValueSource(strings = {"40P01", "40001", "55P03"})
    void deadlockSerializationAndLockTimeoutAreRetryable(String state) {
        RuntimeException wrapped = new RuntimeException("감싼 오류", new SQLException("x", state));
        assertThat(SqlErrors.isRetryable(wrapped)).isTrue();
        assertThat(SqlErrors.sqlState(wrapped)).contains(state);
    }

    @ParameterizedTest
    @ValueSource(strings = {"23505", "23503", "57014", "42501"})
    void otherStatesAreNotRetryable(String state) {
        assertThat(SqlErrors.isRetryable(new SQLException("x", state))).isFalse();
    }

    @Test
    void constraintNameComesFromThePostgresServerMessage() {
        PSQLException unique = serverError("23505", "core_device_active_guardian_uk");
        DuplicateKeyException translated = new DuplicateKeyException("유일 위반", unique);

        assertThat(SqlErrors.isUniqueViolation(translated)).isTrue();
        assertThat(SqlErrors.isUniqueViolation(translated, "core_device_active_guardian_uk")).isTrue();
        assertThat(SqlErrors.isUniqueViolation(translated, "core_device_pending_code_uk")).isFalse();
        assertThat(SqlErrors.constraintName(translated)).contains("core_device_active_guardian_uk");
        assertThat(SqlErrors.isForeignKeyViolation(translated)).isFalse();

        DataIntegrityViolationException foreignKey = new DataIntegrityViolationException("외래 키",
                serverError("23503", "core_activity_device_fk"));
        assertThat(SqlErrors.isForeignKeyViolation(foreignKey)).isTrue();
        assertThat(SqlErrors.isUniqueViolation(foreignKey, "core_activity_device_fk")).isFalse();
    }

    @Test
    void missingStateOrNonDatabaseErrorsYieldNothing() {
        assertThat(SqlErrors.sqlState(null)).isEmpty();
        assertThat(SqlErrors.sqlState(new IllegalStateException("업무 오류"))).isEmpty();
        assertThat(SqlErrors.constraintName(new SQLException("드라이버 아닌 오류", "23505"))).isEmpty();
        // SQLSTATE 없는 바깥 SQLException은 건너뛰고 안쪽 상태를 찾는다
        SQLException outer = new SQLException("바깥", (String) null, new SQLException("안쪽", "40P01"));
        assertThat(SqlErrors.sqlState(outer)).contains("40P01");
    }

    @Test
    void causeChainsDeeperThanTheLimitAreNotFollowed() {
        Throwable chain = new SQLException("맨 안", "40P01");
        for (int i = 0; i < 20; i++) {
            chain = new RuntimeException("감쌈 " + i, chain);
        }
        assertThat(SqlErrors.sqlState(chain)).isEmpty();
    }

    private static PSQLException serverError(String state, String constraint) {
        return new PSQLException(new ServerErrorMessage("SERROR\0C" + state + "\0M시험 위반\0n" + constraint + "\0\0"));
    }
}
