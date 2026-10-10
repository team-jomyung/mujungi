package kr.mujoong.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.common.db.CoreDataAccessContext;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.device.DeviceJdbcRepository;
import kr.mujoong.core.identity.Guardian;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.messaging.CoreInboxRepository;
import kr.mujoong.core.messaging.CoreOutboxRepository;
import kr.mujoong.testsupport.db.MujoongPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Step 15 데이터 접근 빈의 Spring 연결 시험(U1 3.5 Step 17 · RD-22 · RD-24).
 *
 * <p>증명: 운영 설정(application.yml + test 프로필)으로 저장소 · 재시도 · 연결 상한 빈이 뜬다, 앱 DataSource 연결에 lock_timeout 2초 ·
 * statement_timeout 10초가 걸린다, 재시도 횟수는 {@code mujoong.limits.db-retry-attempts}(2)다, JPA와 JDBC 저장소가 한 트랜잭션 ·
 * 한 연결을 함께 쓴다(서비스 한 곳이 경계, RD-22).
 */
@Tag("u1")
@Tag("db")
class CoreDataAccessWiringIT {

    private static MujoongPostgres postgres;

    @BeforeAll
    static void startDatabase() {
        postgres = MujoongPostgres.sharedOrAbort();
    }

    @Test
    void dataAccessBeansWireFromTheProductionConfiguration() {
        CoreDataAccessContext.runner(postgres).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DeviceJdbcRepository.class)
                    .hasSingleBean(ActivityCopyRepository.class)
                    .hasSingleBean(CoreOutboxRepository.class)
                    .hasSingleBean(CoreInboxRepository.class)
                    .hasSingleBean(GuardianRepository.class);
            assertThat(context.getBean(DbRetry.class).maxRetries()).isEqualTo(2);

            DataSource dataSource = context.getBean(DataSource.class);
            assertThat(dataSource).isInstanceOf(HikariDataSource.class);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("show lock_timeout", String.class)).isEqualTo("2s");
            assertThat(jdbc.queryForObject("show statement_timeout", String.class)).isEqualTo("10s");
            assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo("core_app");
        });
    }

    @Test
    void theProductionRetryBeanWaitsAJitterAndRetriesIdempotentWork() {
        CoreDataAccessContext.runner(postgres).run(context -> {
            DbRetry retry = context.getBean(DbRetry.class);
            AtomicInteger attempts = new AtomicInteger();
            long started = System.nanoTime();
            retry.run(DbRetry.Idempotency.IDEMPOTENT, () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new PessimisticLockingFailureException("교착", new SQLException("시험 교착", "40P01"));
                }
            });
            assertThat(attempts).hasValue(2);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("지터는 50ms 이하").isLessThan(Duration.ofSeconds(5));
        });
    }

    @Test
    void jpaAndJdbcRepositoriesShareOneTransactionAndConnection() {
        CoreDataAccessContext.runner(postgres).run(context -> {
            GuardianRepository guardians = context.getBean(GuardianRepository.class);
            DeviceJdbcRepository devices = context.getBean(DeviceJdbcRepository.class);
            TransactionTemplate tx = context.getBean(TransactionTemplate.class);
            UUID guardianId = UUID.randomUUID();

            Optional<String> seenByJdbc = tx.execute(status -> {
                status.setRollbackOnly();
                guardians.saveAndFlush(new Guardian(guardianId, Instant.parse("2001-03-01T00:00:00Z")));
                // JPA로 쓴 행을 같은 트랜잭션의 JDBC 저장소가 본다(같은 연결)
                return devices.lockGuardianStatusForShare(guardianId);
            });

            assertThat(seenByJdbc).contains(Guardian.STATUS_ACTIVE);
            assertThat(guardians.findById(guardianId)).as("되돌린 트랜잭션은 남기지 않는다").isEmpty();
        });
    }
}
