package kr.mujoong.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.common.db.CoreDataAccessContext;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.device.DeviceJdbcRepository;
import kr.mujoong.core.identity.Child;
import kr.mujoong.core.identity.ChildRepository;
import kr.mujoong.core.identity.Guardian;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.messaging.CoreInboxRepository;
import kr.mujoong.core.messaging.CoreInboxRepository.NewInboxEvent;
import kr.mujoong.core.messaging.CoreOutboxRepository;
import kr.mujoong.testsupport.db.MujoongPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Step 15 데이터 접근 빈의 Spring 연결 시험(U1 3.5 Step 17 · RD-22 · RD-24).
 *
 * <p>증명: 운영 설정(application.yml + test 프로필)으로 저장소 · 재시도 · 연결 상한 빈이 뜬다, 앱 DataSource 연결에 lock_timeout 2초 ·
 * statement_timeout 10초가 걸린다, 재시도 횟수는 {@code mujoong.limits.db-retry-attempts}(2)다, JPA와 JDBC 저장소가 한 트랜잭션 ·
 * 한 연결을 함께 쓴다(서비스 한 곳이 경계, RD-22). 3.6 수정 분량: 트랜잭션 뒤쪽 쓰기가 실패하면 앞의 JPA · JDBC 쓰기가 함께 되돌려진다,
 * 풀이 바닥나면 application.yml {@code connection-timeout}(2초) 뒤 실패하고 무한히 기다리지 않는다(NFR7.6 · PT-05).
 */
@Tag("skeleton")
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

    @Test
    void aLaterFailureRollsBackTheEarlierJpaAndJdbcWritesTogether() {
        CoreDataAccessContext.runner(postgres).run(context -> {
            GuardianRepository guardians = context.getBean(GuardianRepository.class);
            ChildRepository children = context.getBean(ChildRepository.class);
            CoreInboxRepository inbox = context.getBean(CoreInboxRepository.class);
            TransactionTemplate tx = context.getBean(TransactionTemplate.class);
            Instant at = Instant.parse("2001-03-01T00:00:00Z");
            UUID guardianId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();

            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                guardians.saveAndFlush(new Guardian(guardianId, at));
                inbox.insertReceived(new NewInboxEvent(eventId, "DeviceStatusChanged", 1, at, UUID.randomUUID(), guardianId,
                        "{}", DbTestSupport.randomHash(), null, at));
                // 마지막 쓰기가 외래 키를 어긴다(없는 보호자의 아이)
                children.saveAndFlush(new Child(UUID.randomUUID(), UUID.randomUUID(), "하늘", 8, null, at));
            })).isInstanceOf(DataIntegrityViolationException.class);

            assertThat(guardians.findById(guardianId)).as("앞의 JPA 쓰기도 되돌렸다").isEmpty();
            assertThat(inbox.findById(eventId)).as("같은 트랜잭션의 JDBC 저장소 쓰기도 되돌렸다").isEmpty();
        });
    }

    @Test
    void anExhaustedPoolFailsAfterTheConfiguredConnectionTimeoutInsteadOfWaitingForever() {
        CoreDataAccessContext.runner(postgres).run(context -> {
            HikariDataSource pool = (HikariDataSource) context.getBean(DataSource.class);
            assertThat(pool.getConnectionTimeout()).as("application.yml connection-timeout(Hikari 기본 30초가 아님)")
                    .isEqualTo(2_000L);
            List<Connection> held = new ArrayList<>();
            try {
                while (held.size() < pool.getMaximumPoolSize()) {
                    held.add(pool.getConnection());
                }
                long started = System.nanoTime();

                assertThatThrownBy(() -> new JdbcTemplate(pool).queryForObject("select 1", Integer.class))
                        .isInstanceOf(CannotGetJdbcConnectionException.class);
                Duration waited = Duration.ofNanos(System.nanoTime() - started);
                assertThat(waited).as("설정한 대기만큼 기다린 뒤 실패").isGreaterThanOrEqualTo(Duration.ofMillis(1_900))
                        .isLessThan(Duration.ofSeconds(15));
            } finally {
                for (Connection connection : held) {
                    connection.close();
                }
            }
            assertThat(new JdbcTemplate(pool).queryForObject("select 1", Integer.class)).as("돌려준 뒤에는 다시 얻는다")
                    .isEqualTo(1);
        });
    }
}
