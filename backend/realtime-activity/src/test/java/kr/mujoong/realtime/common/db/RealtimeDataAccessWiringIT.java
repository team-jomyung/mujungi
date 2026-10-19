package kr.mujoong.realtime.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import kr.mujoong.contract.event.EventEnvelope;
import kr.mujoong.contract.event.payload.SessionStateChanged;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.config.RealtimeLimitsProperties;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.gateway.DeviceConnectionRepository;
import kr.mujoong.realtime.log.TranscriptRepository;
import kr.mujoong.realtime.messaging.NewOutboxEvent;
import kr.mujoong.realtime.messaging.RealtimeInboxRepository;
import kr.mujoong.realtime.messaging.RealtimeOutboxRepository;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.session.NewActivitySession;
import kr.mujoong.realtime.session.SessionEvent;
import kr.mujoong.realtime.session.SessionEventRepository;
import kr.mujoong.realtime.session.SessionStopExpression;
import kr.mujoong.realtime.session.SessionStopExpressionRepository;
import kr.mujoong.realtime.testing.RealtimeDb;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.persistence.autoconfigure.PersistenceExceptionTranslationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

/**
 * Realtime 데이터 접근 배선 통합 시험(U1 3.5 Step 18 · RD-22 · BR5.1 · BR6.6 ④).
 *
 * <p>운영 앱처럼 Boot 자동 설정(DataSource · JdbcTemplate · Hibernate JPA)에 저장소 빈과 {@link DbSessionLimits} 후처리기를 올려
 * 확인한다: 앱 풀의 연결에 설정 키 값의 lock_timeout · statement_timeout이 걸린다 · 트랜잭션 관리자는 JPA이고 JDBC 저장소가 그
 * 트랜잭션에 함께 묶인다(세션 행 · 기본 종료 표현 · SessionStateChanged가 한 커밋 또는 한 롤백) · Outbox의 MANDATORY 확인이 JPA
 * 트랜잭션을 활성으로 본다. 앱 전체 문맥 대신 이 묶음만 올려 다른 단계의 빈 공사 상태에 묶이지 않는다.
 *
 * <p>3.6 수정(BT-TC-10)에서 더한 셋: 상한 키가 없으면 상한 없는 풀이 열리기 전에 문맥이 서지 않는다 · 트랜잭션 중간의 DB 오류(유일 위반)는
 * 앞 저장소들의 쓰기까지 함께 되돌리고 예외 번역 프록시를 지나도 SQLSTATE를 잃지 않는다 · 저장소 프록시는 트랜잭션을 스스로 열지 않아
 * MANDATORY 쓰기가 트랜잭션 밖에서 실패한다.
 */
@Tag("skeleton")
@Tag("db")
class RealtimeDataAccessWiringIT {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");

    private static MujoongPostgres postgres;

    @BeforeAll
    static void startDatabase() {
        postgres = MujoongPostgres.sharedOrAbort();
    }

    /** 커밋한 행이 뒤 시험 클래스(재시작 복구 등)의 전체 조회에 섞이지 않게 비운다. */
    @AfterAll
    static void wipeCommittedRows() {
        try (RealtimeDb db = RealtimeDb.open()) {
            db.wipe();
        }
    }

    @Test
    void theAppPoolCarriesTheConfiguredTimeoutsAndTheRepositoriesShareTheJpaTransaction() {
        runner("1500ms", "7s").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DataSource.class)).isInstanceOf(HikariDataSource.class);
            try (Connection connection = context.getBean(DataSource.class).getConnection();
                    Statement statement = connection.createStatement()) {
                assertThat(show(statement, "lock_timeout")).isEqualTo("1500ms");
                assertThat(show(statement, "statement_timeout")).isEqualTo("7s");
            }
            PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
            assertThat(manager).isInstanceOf(JpaTransactionManager.class);
            TransactionTemplate transactions = new TransactionTemplate(manager);
            ActivitySessionRepository sessions = context.getBean(ActivitySessionRepository.class);
            SessionStopExpressionRepository stops = context.getBean(SessionStopExpressionRepository.class);
            SessionEventRepository events = context.getBean(SessionEventRepository.class);
            RealtimeOutboxRepository outbox = context.getBean(RealtimeOutboxRepository.class);
            assertThat(context).hasSingleBean(DeviceConnectionRepository.class).hasSingleBean(TranscriptRepository.class)
                    .hasSingleBean(RealtimeInboxRepository.class).hasSingleBean(DbRetry.class);
            // 운영처럼 @Repository 빈은 예외 번역 프록시로 감싸인다(CGLIB). 프록시로도 같은 문장이 돈다
            assertThat(AopUtils.isAopProxy(sessions)).isTrue();

            NewActivitySession committed = newSession();
            NewActivitySession rolledBack = newSession();
            EventEnvelope committedEvent = preparing(committed);
            EventEnvelope rolledBackEvent = preparing(rolledBack);
            // BR6.6 ④: 세션 행 + 기본 종료 표현 + SessionStateChanged(PREPARING)가 한 트랜잭션
            transactions.executeWithoutResult(status -> start(sessions, stops, events, outbox, committed, committedEvent));
            transactions.executeWithoutResult(status -> {
                start(sessions, stops, events, outbox, rolledBack, rolledBackEvent);
                status.setRollbackOnly();
            });

            assertThat(sessions.find(committed.id())).map(row -> row.status()).contains(SessionStatus.PREPARING);
            assertThat(stops.findBySession(committed.id())).hasSize(1);
            assertThat(events.findBySession(committed.id())).hasSize(1);
            assertThat(outbox.find(committedEvent.eventId())).isPresent();
            assertThat(sessions.find(rolledBack.id())).isEmpty();
            assertThat(stops.findBySession(rolledBack.id())).isEmpty();
            assertThat(outbox.find(rolledBackEvent.eventId())).isEmpty();
        });
    }

    @Test
    void aForeignInitStatementStopsTheContextInsteadOfLosingTheTimeouts() {
        runner("2s", "10s")
                .withPropertyValues("spring.datasource.hikari.connection-init-sql=select 1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aMissingLimitKeyStopsTheContextBeforeAnUnlimitedPoolOpens() {
        runner("2s", null).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).hasMessageContaining(DbSessionLimits.STATEMENT_TIMEOUT_KEY);
        });
    }

    @Test
    void aFailingStatementRollsBackEveryRepositoryWriteAndKeepsItsSqlState() {
        runner("2s", "10s").run(context -> {
            assertThat(context).hasNotFailed();
            TransactionTemplate transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            ActivitySessionRepository sessions = context.getBean(ActivitySessionRepository.class);
            SessionStopExpressionRepository stops = context.getBean(SessionStopExpressionRepository.class);
            SessionEventRepository events = context.getBean(SessionEventRepository.class);
            RealtimeOutboxRepository outbox = context.getBean(RealtimeOutboxRepository.class);
            NewActivitySession session = newSession();
            EventEnvelope event = preparing(session);

            // 같은 eventId를 두 번 쓰면 두 번째 INSERT가 유일 위반이다 — 그 앞의 세션 행 · 종료 표현 · 기록 · 첫 Outbox 행도 함께 되돌린다
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                start(sessions, stops, events, outbox, session, event);
                outbox.insert(NewOutboxEvent.of(event, session.guardianId()), T0);
            })).isInstanceOf(DataAccessException.class)
                    .satisfies(error -> assertThat(SqlErrors.isUniqueViolation(error)).isTrue())
                    .satisfies(error -> assertThat(SqlErrors.isRetryable(error)).isFalse());

            assertThat(sessions.find(session.id())).isEmpty();
            assertThat(stops.findBySession(session.id())).isEmpty();
            assertThat(events.findBySession(session.id())).isEmpty();
            assertThat(outbox.find(event.eventId())).isEmpty();
        });
    }

    @Test
    void repositoryProxiesNeverOpenATransactionSoMandatoryWritesFailOutsideOne() {
        runner("2s", "10s").run(context -> {
            assertThat(context).hasNotFailed();
            ActivitySessionRepository sessions = context.getBean(ActivitySessionRepository.class);
            RealtimeOutboxRepository outbox = context.getBean(RealtimeOutboxRepository.class);
            NewActivitySession session = newSession();
            EventEnvelope event = preparing(session);

            assertThatThrownBy(() -> outbox.insert(NewOutboxEvent.of(event, session.guardianId()), T0))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> sessions.findForUpdate(session.id()))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThat(outbox.find(event.eventId())).isEmpty();
        });
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static void start(ActivitySessionRepository sessions, SessionStopExpressionRepository stops,
            SessionEventRepository events, RealtimeOutboxRepository outbox, NewActivitySession session,
            EventEnvelope event) {
        assertThat(sessions.insert(session, T0)).isTrue();
        stops.insertAll(List.of(new SessionStopExpression(UUID.randomUUID(), session.id(), "무중아 그만할래", true, 0, T0)));
        events.insert(new SessionEvent(UUID.randomUUID(), session.id(), "STARTED", null, null, T0));
        outbox.insert(NewOutboxEvent.of(event, session.guardianId()), T0);
    }

    /** 값이 null인 상한 키는 넣지 않는다(키 누락 시험). */
    private static ApplicationContextRunner runner(String lockTimeout, String statementTimeout) {
        List<String> properties = new ArrayList<>(List.of(
                "spring.datasource.url=" + postgres.jdbcUrl(),
                "spring.datasource.username=" + postgres.username(Role.REALTIME_APP),
                "spring.datasource.password=" + postgres.password(Role.REALTIME_APP),
                "spring.datasource.hikari.maximum-pool-size=2",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.open-in-view=false",
                "spring.jpa.properties.hibernate.default_schema=realtime",
                "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
                "mujoong.limits.db-retry-attempts=2"));
        if (lockTimeout != null) {
            properties.add(DbSessionLimits.LOCK_TIMEOUT_KEY + "=" + lockTimeout);
        }
        if (statementTimeout != null) {
            properties.add(DbSessionLimits.STATEMENT_TIMEOUT_KEY + "=" + statementTimeout);
        }
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
                        PersistenceExceptionTranslationAutoConfiguration.class))
                .withUserConfiguration(RealtimeDataAccess.class)
                .withPropertyValues(properties.toArray(String[]::new));
    }

    private static NewActivitySession newSession() {
        return new NewActivitySession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ActivityType.FREE_TALK, UUID.randomUUID().toString(), new ConsentSnapshot("1.0", true, true, false, false),
                new ChildContext("하늘", 8, List.of("공룡")), UUID.randomUUID(), null);
    }

    private static EventEnvelope preparing(NewActivitySession session) {
        return JsonCodec.shared().envelopeFor(UUID.randomUUID(), new SessionStateChanged(session.id(),
                session.guardianId(), session.childId(), ActivityType.FREE_TALK, SessionStatus.PREPARING, 1L, null, null,
                null, null, T0), T0, null);
    }

    private static String show(Statement statement, String setting) throws Exception {
        try (ResultSet rs = statement.executeQuery("show " + setting)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** 운영 앱의 데이터 접근 묶음(저장소 · 연결 상한 후처리기 · 재시도)과 엔터티 검증 범위. */
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = SessionEvent.class)
    @Import({DbSessionLimits.class, DbRetry.class, ActivitySessionRepository.class, SessionEventRepository.class,
            SessionStopExpressionRepository.class, DeviceConnectionRepository.class, TranscriptRepository.class,
            RealtimeOutboxRepository.class, RealtimeInboxRepository.class, RetryDependency.class})
    static class RealtimeDataAccess {
    }

    /** {@link DbRetry}의 운영 생성자가 받는 설정(이 묶음에는 RealtimeProperties 전체가 없어 limits만 대역으로 준다). */
    @Configuration(proxyBeanMethods = false)
    static class RetryDependency {

        @Bean
        RealtimeProperties realtimeProperties(Environment environment) {
            RealtimeProperties properties = mock(RealtimeProperties.class);
            when(properties.limits()).thenReturn(new RealtimeLimitsProperties(DataSize.ofKilobytes(8),
                    DataSize.ofMegabytes(2), 50, 1000, 100, 200, 2, 3, Duration.ofSeconds(2), Duration.ofSeconds(10),
                    environment.getRequiredProperty("mujoong.limits.db-retry-attempts", Integer.class), 1000));
            return properties;
        }
    }
}
