package kr.mujoong.realtime.common.db;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Realtime DB 연결마다 대기 시간 상한을 건다 (U1 3.5 Step 16 · RD-22 · NFR7.6).
 *
 * <p>앱 DataSource(Hikari)가 새 연결을 열 때 {@code lock_timeout = mujoong.limits.db-lock-timeout}(2초) ·
 * {@code statement_timeout = mujoong.limits.db-statement-timeout}(10초)을 그 연결 세션에 둔다. 락 대기 초과는 55P03이 되어
 * {@link DbRetry}의 재시도 대상이고, 문장 상한(10초)은 Inbox claim-lease(30초)보다 짧다. 값은 설정 키에서만 읽는다(BR8.11).
 * 빈 후처리기라 설정 레코드 빈 대신 {@link Environment}에서 두 키만 바인딩한다(이른 초기화 회피).
 */
@Component
public class DbSessionLimits implements BeanPostProcessor {

    /** 락 대기 상한 키. */
    public static final String LOCK_TIMEOUT_KEY = "mujoong.limits.db-lock-timeout";
    /** 문장 실행 상한 키. */
    public static final String STATEMENT_TIMEOUT_KEY = "mujoong.limits.db-statement-timeout";

    private final Duration lockTimeout;
    private final Duration statementTimeout;

    /** 운영: 두 값을 설정 키에서 읽는다. 키가 없거나 1ms 미만이면 기동이 실패한다. */
    @Autowired
    public DbSessionLimits(Environment environment) {
        this(bind(environment, LOCK_TIMEOUT_KEY), bind(environment, STATEMENT_TIMEOUT_KEY));
    }

    public DbSessionLimits(Duration lockTimeout, Duration statementTimeout) {
        this.lockTimeout = positive(lockTimeout, LOCK_TIMEOUT_KEY);
        this.statementTimeout = positive(statementTimeout, STATEMENT_TIMEOUT_KEY);
    }

    /** 새 연결에서 한 번 실행하는 문장(한 문장, 값은 밀리초 문자열). */
    public String connectionInitSql() {
        return "select set_config('lock_timeout', '" + lockTimeout.toMillis() + "ms', false),"
                + " set_config('statement_timeout', '" + statementTimeout.toMillis() + "ms', false)";
    }

    /** Hikari DataSource에 연결 초기화 문장을 건다. 풀이 연결을 열기 전(빈 초기화 전)에 불린다. */
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource dataSource) {
            apply(dataSource);
        }
        return bean;
    }

    /** {@code dataSource}에 상한을 건다. 이미 다른 초기화 문장이 있으면 덮지 않고 실패한다(숨은 덮어쓰기 방지). */
    public void apply(HikariDataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        String existing = dataSource.getConnectionInitSql();
        String initSql = connectionInitSql();
        if (existing != null && !existing.isBlank() && !existing.equals(initSql)) {
            throw new IllegalStateException(
                    "spring.datasource.hikari.connection-init-sql이 이미 있다. DB 대기 상한(RD-22)과 함께 쓸 수 없다");
        }
        dataSource.setConnectionInitSql(initSql);
    }

    public Duration lockTimeout() {
        return lockTimeout;
    }

    public Duration statementTimeout() {
        return statementTimeout;
    }

    private static Duration bind(Environment environment, String key) {
        return Binder.get(environment)
                .bind(key, Duration.class)
                .orElseThrow(() -> new IllegalStateException("필수 설정 키가 없다: " + key));
    }

    private static Duration positive(Duration value, String key) {
        if (value == null || value.isZero() || value.isNegative() || value.toMillis() < 1) {
            throw new IllegalArgumentException("설정 키는 1ms 이상이어야 한다: " + key);
        }
        return value;
    }
}
