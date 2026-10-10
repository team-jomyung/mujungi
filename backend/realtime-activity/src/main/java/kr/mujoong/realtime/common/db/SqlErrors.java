package kr.mujoong.realtime.common.db;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Realtime DB 예외 분류 (U1 3.5 Step 16 · RD-22 · J83).
 *
 * <p>Spring이 번역한 예외({@code DataAccessException})든 드라이버 예외든 원인 사슬에서 첫 {@link SQLException}의 SQLSTATE와
 * 위반 제약 이름을 꺼낸다. 메시지 · 값은 꺼내지 않는다(오류 메시지에 행 값이 담길 수 있다, BR8.7 · BR5.13).
 */
public final class SqlErrors {

    /** 유일 위반. */
    public static final String UNIQUE_VIOLATION = "23505";
    /** 외래 키 위반. */
    public static final String FOREIGN_KEY_VIOLATION = "23503";
    /** 교착(재시도 대상). */
    public static final String DEADLOCK_DETECTED = "40P01";
    /** 직렬화 실패(재시도 대상). */
    public static final String SERIALIZATION_FAILURE = "40001";
    /** 락 대기 초과 · NOWAIT 실패(재시도 대상, {@code lock_timeout}). */
    public static final String LOCK_NOT_AVAILABLE = "55P03";

    /** RD-22가 재시도를 허용하는 SQLSTATE 셋. 그 밖(문장 시간 초과 57014 포함)은 재시도하지 않는다. */
    public static final Set<String> RETRYABLE = Set.of(DEADLOCK_DETECTED, SERIALIZATION_FAILURE, LOCK_NOT_AVAILABLE);

    private static final int MAX_CAUSE_DEPTH = 16;

    private SqlErrors() {
    }

    /** 원인 사슬의 첫 SQLSTATE. */
    public static Optional<String> sqlState(Throwable error) {
        return firstSqlException(error).map(SQLException::getSQLState);
    }

    /** RD-22 재시도 대상(40P01 · 40001 · 55P03)인가. */
    public static boolean isRetryable(Throwable error) {
        return sqlState(error).filter(RETRYABLE::contains).isPresent();
    }

    /** 유일 위반(23505)인가. */
    public static boolean isUniqueViolation(Throwable error) {
        return sqlState(error).filter(UNIQUE_VIOLATION::equals).isPresent();
    }

    /** 외래 키 위반(23503)인가. */
    public static boolean isForeignKeyViolation(Throwable error) {
        return sqlState(error).filter(FOREIGN_KEY_VIOLATION::equals).isPresent();
    }

    /** 위반한 제약 · 색인 이름(PostgreSQL 서버 오류에 있을 때만). */
    public static Optional<String> constraintName(Throwable error) {
        return firstSqlException(error)
                .filter(PSQLException.class::isInstance)
                .map(PSQLException.class::cast)
                .map(PSQLException::getServerErrorMessage)
                .map(ServerErrorMessage::getConstraint);
    }

    private static Optional<SQLException> firstSqlException(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return Optional.of(sqlException);
            }
            current = current.getCause();
        }
        return Optional.empty();
    }
}
