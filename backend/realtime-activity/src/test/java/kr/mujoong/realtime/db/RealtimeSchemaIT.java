package kr.mujoong.realtime.db;

import static kr.mujoong.realtime.db.RealtimeDbFixtures.T0;
import static kr.mujoong.realtime.db.RealtimeDbFixtures.VALID_SNAPSHOT;
import static kr.mujoong.realtime.db.RealtimeDbFixtures.query;
import static kr.mujoong.realtime.db.RealtimeDbFixtures.single;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import kr.mujoong.realtime.session.SessionEvent;
import kr.mujoong.realtime.session.SessionStopExpression;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Realtime V1 스키마 통합 시험(U1 3.5 Step 14). 실제 init 스크립트 + 두 마이그레이션 위치로 만든 DB에서 표 · 열 · 형식 · 색인 ·
 * 이력과 JPA 엔터티 대조(ddl-auto=validate)를 본다.
 *
 * <p>증명: BR8.2 · J43(V1이 표 7개를 만든다) · 열 수(realtime_activity_session 29 · realtime_device_connection 10 · Outbox/Inbox 각 17
 * · realtime_transcript 12, E2 · E4 · E5) · A3 trace_id · J115 · PD-8(fillfactor 90 · 진행 중 세션 부분 유일 색인 둘) · J116(core 다음
 * 적용이라 버전 0 기준선 + V1) · Step 11 JPA 엔터티 validate.
 */
@Tag("u1")
@Tag("db")
class RealtimeSchemaIT {

    static final List<String> REALTIME_TABLES = List.of(
            "realtime_activity_session",
            "realtime_device_connection",
            "realtime_inbox_event",
            "realtime_outbox_event",
            "realtime_session_event",
            "realtime_session_stop_expression",
            "realtime_transcript");

    private static MujoongPostgres postgres;

    @BeforeAll
    static void startDatabase() {
        postgres = MujoongPostgres.sharedOrAbort();
    }

    @Test
    void v1CreatesExactlyTheSevenRealtimeTablesAfterAVersionZeroBaseline() throws SQLException {
        try (Connection owner = postgres.connect(Role.OWNER)) {
            List<List<Object>> tables = query(owner,
                    "select tablename, tableowner from pg_tables where schemaname = 'realtime' order by tablename");
            assertThat(tables.stream().map(row -> (String) row.get(0)).toList()).containsExactlyElementsOf(REALTIME_TABLES);
            assertThat(tables).allSatisfy(row -> assertThat(row.get(1)).isEqualTo("mujoong_owner"));

            // core 위치가 먼저 flyway 스키마를 썼으므로 realtime 이력은 버전 0 기준선 뒤 V1이다(J116 보완)
            assertThat(query(owner,
                    "select version, type, success from flyway.realtime_schema_history order by installed_rank"))
                    .containsExactly(List.of("0", "BASELINE", true), List.of("1", "SQL", true));
        }
        try (Connection realtime = postgres.connect(Role.REALTIME_APP)) {
            // 기동 점검 S2가 읽는 최신 버전
            assertThat(single(realtime,
                    "select max(version) from flyway.realtime_schema_history where success and type = 'SQL'"))
                    .isEqualTo("1");
        }
    }

    @ParameterizedTest(name = "{0}: {1}열")
    @CsvSource({
        "realtime_activity_session, 29",
        "realtime_device_connection, 10",
        "realtime_outbox_event, 17",
        "realtime_inbox_event, 17",
        "realtime_transcript, 12",
        "realtime_session_event, 6",
        "realtime_session_stop_expression, 6",
    })
    void tablesHaveTheEntityColumnCounts(String table, int expectedColumns) throws SQLException {
        try (Connection realtime = postgres.connect(Role.REALTIME_APP)) {
            assertThat(single(realtime,
                    "select count(*) from information_schema.columns where table_schema = 'realtime' and table_name = ?",
                    table)).isEqualTo((long) expectedColumns);
        }
    }

    @Test
    void columnTypesFollowJ115AndTraceIdIsOptional() throws SQLException {
        try (Connection realtime = postgres.connect(Role.REALTIME_APP)) {
            assertThat(query(realtime,
                    "select table_name, data_type, character_maximum_length, is_nullable from information_schema.columns"
                            + " where table_schema = 'realtime' and column_name = 'trace_id' order by table_name"))
                    .containsExactly(
                            List.of("realtime_inbox_event", "character varying", 32, "YES"),
                            List.of("realtime_outbox_event", "character varying", 32, "YES"));
            assertThat(query(realtime,
                    "select distinct data_type from information_schema.columns where table_schema = 'realtime'"
                            + " and data_type like 'timestamp%'"))
                    .containsExactly(List.of("timestamp with time zone"));
            assertThat(query(realtime,
                    "select distinct data_type from information_schema.columns where table_schema = 'realtime'"
                            + " and column_name like '%json'"))
                    .containsExactly(List.of("jsonb"));
            // DB 시각 기본값 없음(R2)
            assertThat(query(realtime,
                    "select column_name from information_schema.columns where table_schema = 'realtime'"
                            + " and column_default ~* '(now|clock_timestamp|current_timestamp|localtimestamp)'"))
                    .isEmpty();
        }
    }

    @Test
    void connectionLedgerHasFillfactor90AndSessionsHaveTwoPartialUniqueIndexes() throws SQLException {
        try (Connection realtime = postgres.connect(Role.REALTIME_APP)) {
            assertThat(single(realtime,
                    "select array_to_string(reloptions, ',') from pg_class c join pg_namespace n on n.oid = c.relnamespace"
                            + " where n.nspname = 'realtime' and c.relname = 'realtime_device_connection'"))
                    .isEqualTo("fillfactor=90");
            List<List<Object>> partial = query(realtime,
                    "select indexname, indexdef from pg_indexes where schemaname = 'realtime' and indexdef like '%WHERE%'"
                            + " order by indexname");
            assertThat(partial.stream().map(row -> (String) row.get(0)).toList()).containsExactly(
                    "realtime_activity_session_in_progress_device_uk",
                    "realtime_activity_session_in_progress_guardian_uk");
            assertThat(partial).allSatisfy(row -> assertThat((String) row.get(1))
                    .startsWith("CREATE UNIQUE INDEX").contains("'PREPARING'").contains("'CLOSING'")
                    .doesNotContain("'ENDED'"));
            // 전사 (session_id, sequence_no) 유일과 폴러 · 삭제 참가자 색인
            assertThat(single(realtime,
                    "select count(*) from pg_indexes where schemaname = 'realtime' and indexname in"
                            + " ('realtime_transcript_sequence_uk', 'realtime_outbox_event_poll_idx',"
                            + " 'realtime_inbox_event_poll_idx', 'realtime_outbox_event_guardian_idx',"
                            + " 'realtime_inbox_event_guardian_idx')"))
                    .isEqualTo(5L);
        }
    }

    @Test
    void jpaEntitiesValidateAgainstV1AndRoundTripAsTheAppRole() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(RealtimeEntityScan.class)
                .withPropertyValues(
                        "spring.datasource.url=" + postgres.jdbcUrl(),
                        "spring.datasource.username=" + postgres.username(Role.REALTIME_APP),
                        "spring.datasource.password=" + postgres.password(Role.REALTIME_APP),
                        "spring.datasource.hikari.maximum-pool-size=2",
                        // realtime-activity application.yml과 같은 JPA 설정
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.jpa.open-in-view=false",
                        "spring.jpa.properties.hibernate.default_schema=realtime",
                        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    roundTrip(context.getBean(EntityManagerFactory.class));
                });
    }

    private static void roundTrip(EntityManagerFactory factory) {
        UUID sessionId = UUID.randomUUID();
        UUID expressionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        EntityManager entityManager = factory.createEntityManager();
        try {
            entityManager.getTransaction().begin();
            // 외래 키의 부모 세션 행(BR6.6 INSERT 모양)
            entityManager.createNativeQuery(
                            "insert into realtime.realtime_activity_session (id, guardian_id, child_id, device_id, activity_type,"
                                    + " status, status_version, start_idempotency_key, consent_checked_at, consent_snapshot_json,"
                                    + " policy_version, data_collection_allowed, transcript_storage_allowed, created_at, updated_at)"
                                    + " values (?1, ?2, ?3, ?4, 'FREE_TALK', 'PREPARING', 1, ?5, ?6, cast(?7 as jsonb), '1.0',"
                                    + " true, true, ?6, ?6)")
                    .setParameter(1, sessionId)
                    .setParameter(2, UUID.randomUUID())
                    .setParameter(3, UUID.randomUUID())
                    .setParameter(4, UUID.randomUUID())
                    .setParameter(5, UUID.randomUUID().toString())
                    .setParameter(6, OffsetDateTime.ofInstant(T0, ZoneOffset.UTC))
                    .setParameter(7, VALID_SNAPSHOT)
                    .executeUpdate();
            entityManager.persist(new SessionStopExpression(expressionId, sessionId, "무중아 그만할래", true, 0, T0));
            entityManager.persist(new SessionEvent(eventId, sessionId, "END_REQUESTED", "request-1",
                    "{\"reason\":\"GUARDIAN_STOP\"}", T0));
            entityManager.flush();
            entityManager.clear();

            SessionStopExpression expression = entityManager.find(SessionStopExpression.class, expressionId);
            assertThat(expression.getSessionId()).isEqualTo(sessionId);
            assertThat(expression.getExpressionText()).isEqualTo("무중아 그만할래");
            assertThat(expression.isDefaultExpression()).isTrue();
            assertThat(expression.getCreatedAt()).isEqualTo(T0);

            SessionEvent event = entityManager.find(SessionEvent.class, eventId);
            assertThat(event.getEventType()).isEqualTo("END_REQUESTED");
            assertThat(event.getOpId()).isEqualTo("request-1");
            assertThat(event.getPayloadJson().replace(" ", "")).isEqualTo("{\"reason\":\"GUARDIAN_STOP\"}");
        } finally {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            entityManager.close();
        }
    }

    /** Step 11 엔터티 패키지(session과 뒤 단계가 더하는 엔터티)를 스캔한다. */
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackages = "kr.mujoong.realtime")
    static class RealtimeEntityScan {
    }
}
