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
import java.util.stream.Stream;
import kr.mujoong.realtime.session.SessionEvent;
import kr.mujoong.realtime.session.SessionStopExpression;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.MigrationSet;
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
 * Realtime 스키마 통합 시험(U1 3.5 Step 14 · U3 L1이 V2를 더함). 실제 init 스크립트 + 두 마이그레이션 위치로 만든 DB에서 표 · 열 ·
 * 형식 · 색인 · 이력과 JPA 엔터티 대조(ddl-auto=validate)를 본다.
 *
 * <p>증명: BR8.2 · J43(V1이 표 7개를 만든다) · U3 design.md 3절(V2가 표 3개를 더한다: 끊김 사건 7열 · 원음 메타 10열 · 끊김 구간 6열,
 * V1 표는 그대로) · 열 수(realtime_activity_session 29 · realtime_device_connection 10 · Outbox/Inbox 각 17 · realtime_transcript 12,
 * E2 · E4 · E5) · A3 trace_id · J115 · PD-8(fillfactor 90 · 진행 중 세션 부분 유일 색인 둘) · J116(core 다음 적용이라 버전 0 기준선 +
 * V1 + V2) · Step 11 JPA 엔터티 validate.
 */
@Tag("skeleton")
@Tag("db")
@Tag("service-guardrail")
class RealtimeSchemaIT {

    /** V1 일곱 표 + U3 V2 세 표 + U4b V3 한 표 + U5b V4 다섯 표 + U11 V5 한 표 + U13 V6 한 표 + U12 V7 두 표(pg_tables 이름 순). */
    static final List<String> REALTIME_TABLES = List.of(
            "guardrail_event",
            "realtime_activity_session",
            "realtime_audio_recording",
            "realtime_device_connection",
            "realtime_device_disconnect_event",
            "realtime_inbox_event",
            "realtime_judgment_live",
            "realtime_outbox_event",
            "realtime_practice_attempt",
            "realtime_practice_cue",
            "realtime_practice_evaluation",
            "realtime_practice_help",
            "realtime_practice_response",
            "realtime_practice_scene_run",
            "realtime_result_input_snapshot",
            "realtime_session_event",
            "realtime_session_gap",
            "realtime_session_goal",
            "realtime_session_stop_expression",
            "realtime_transcript");

    /** U3 V2가 더한 표(이 중 하나라도 V1 표와 겹치면 더하기만 한다는 BR8.2를 어긴 것이다). */
    static final List<String> V2_TABLES = List.of(
            "realtime_audio_recording", "realtime_device_disconnect_event", "realtime_session_gap");

    private static MujoongPostgres postgres;

    @BeforeAll
    static void startDatabase() {
        postgres = MujoongPostgres.sharedOrAbort();
    }

    @Test
    void migrationsCreateTheRequiredRealtimeTablesAfterAVersionZeroBaseline() throws SQLException {
        try (Connection owner = postgres.connect(Role.OWNER)) {
            List<List<Object>> tables = query(owner,
                    "select tablename, tableowner from pg_tables where schemaname = 'realtime' order by tablename");
            // 필요한 표가 모두 있다. 뒤 마이그레이션이 표를 더해도 이 시험은 고치지 않는다
            assertThat(tables.stream().map(row -> (String) row.get(0)).toList()).containsAll(REALTIME_TABLES);
            assertThat(tables).allSatisfy(row -> assertThat(row.get(1)).isEqualTo("mujoong_owner"));

            // core 위치가 먼저 flyway 스키마를 썼으므로 realtime 이력은 버전 0 기준선 뒤 저장소 마이그레이션 파일 순서대로 모두 성공이다(J116 보완)
            assertThat(query(owner,
                    "select version, type, success from flyway.realtime_schema_history order by installed_rank"))
                    .containsExactlyElementsOf(Stream.concat(Stream.of(List.<Object>of("0", "BASELINE", true)),
                            postgres.migrationVersions(MigrationSet.REALTIME).stream()
                                    .map(version -> List.<Object>of(version, "SQL", true))).toList());
        }
        try (Connection realtime = postgres.connect(Role.REALTIME_APP)) {
            // 기동 점검 S2가 읽는 자기 이력 표: 성공한 버전 행이 보인다
            assertThat(single(realtime,
                    "select count(*) > 0 from flyway.realtime_schema_history where success and type = 'SQL'"))
                    .isEqualTo(true);
        }
    }

    @Test
    void v2OnlyAddsTablesAndTheAppRoleGetsDmlOnThemByDefaultPrivilege() throws SQLException {
        try (Connection owner = postgres.connect(Role.OWNER)) {
            // V2 파일은 CREATE TABLE · CREATE INDEX만 담는다(BR8.2: 열 삭제 · 이름 변경 · 필수화 없음)
            assertThat(single(owner, "select script from flyway.realtime_schema_history where version = '2'"))
                    .isEqualTo("V2__u3_voice_conversation.sql");
            assertThat(V2_TABLES).allSatisfy(table -> assertThat(REALTIME_TABLES).contains(table));
        }
        try (Connection realtime = postgres.connect(Role.REALTIME_APP)) {
            // 01-roles.sql 기본 권한(편차 (x)): realtime_app은 새 표에 DML을 받고 DDL은 못 한다
            for (String table : V2_TABLES) {
                assertThat(single(realtime,
                        "select has_table_privilege('realtime." + table + "', 'SELECT,INSERT,UPDATE,DELETE')"))
                        .as(table).isEqualTo(true);
            }
            // 외래 키는 세션 행을 지키고(ON DELETE RESTRICT), 세션 · 화자 유일, 끊김 창 질의 · gaps 조회 색인
            assertThat(query(realtime,
                    "select conname, confdeltype from pg_constraint where contype = 'f' and conrelid in"
                            + " ('realtime.realtime_audio_recording'::regclass, 'realtime.realtime_session_gap'::regclass)"
                            + " order by conname"))
                    .containsExactly(
                            List.of("realtime_audio_recording_session_fk", "r"),
                            List.of("realtime_session_gap_session_fk", "r"));
            assertThat(single(realtime,
                    "select count(*) from pg_constraint where contype = 'u' and conname = 'realtime_audio_recording_session_speaker_uk'"))
                    .isEqualTo(1L);
            assertThat(query(realtime,
                    "select indexname from pg_indexes where schemaname = 'realtime' and indexname in"
                            + " ('realtime_device_disconnect_event_device_idx', 'realtime_session_gap_session_idx')"
                            + " order by indexname"))
                    .containsExactly(List.of("realtime_device_disconnect_event_device_idx"),
                            List.of("realtime_session_gap_session_idx"));
            // CHECK 제약: kind · speaker · close_code 범위 · ended_at ≥ started_at · byte_count ≥ 0
            assertThat(query(realtime,
                    "select conname from pg_constraint where contype = 'c' and conrelid in"
                            + " ('realtime.realtime_device_disconnect_event'::regclass, 'realtime.realtime_audio_recording'::regclass,"
                            + " 'realtime.realtime_session_gap'::regclass) order by conname"))
                    .extracting(row -> (String) row.get(0))
                    .contains("realtime_device_disconnect_event_kind_ck", "realtime_device_disconnect_event_code_ck",
                            "realtime_audio_recording_speaker_ck", "realtime_audio_recording_bytes_ck",
                            "realtime_session_gap_kind_ck", "realtime_session_gap_order_ck");
        }
    }

    /**
     * U4b V3(FR7.1 · BR8.5 · 설계 6절): 결과 스냅숏 표 하나와 세션 guardian_id 일반 색인만 더한다. 앱 역할은 기본 권한으로 DML을 받고
     * 외래 키는 세션 행을 지키며(RESTRICT), (session_id, snapshot_version) 유일 · 판 · 상태 · 사유 · 해시 형식 · 본문 형식 CHECK가 있다.
     */
    @Test
    @Tag("session-runtime")
    void v3AddsTheResultSnapshotTableAndTheGuardianIndexOnly() throws SQLException {
        try (Connection owner = postgres.connect(Role.OWNER)) {
            assertThat(single(owner, "select script from flyway.realtime_schema_history where version = '3'"))
                    .isEqualTo("V3__u4b_session_lifecycle.sql");
        }
        try (Connection realtime = postgres.connect(Role.REALTIME_APP)) {
            assertThat(single(realtime,
                    "select has_table_privilege('realtime.realtime_result_input_snapshot', 'SELECT,INSERT,UPDATE,DELETE')"))
                    .isEqualTo(true);
            assertThat(single(realtime,
                    "select has_table_privilege('realtime.realtime_result_input_snapshot', 'TRUNCATE')"))
                    .as("앱 역할은 DDL성 권한이 없다").isEqualTo(false);
            assertThat(query(realtime,
                    "select conname, confdeltype from pg_constraint where contype = 'f'"
                            + " and conrelid = 'realtime.realtime_result_input_snapshot'::regclass"))
                    .containsExactly(List.of("realtime_result_input_snapshot_session_fk", "r"));
            assertThat(single(realtime,
                    "select count(*) from pg_constraint where contype = 'u'"
                            + " and conname = 'realtime_result_input_snapshot_version_uk'"))
                    .isEqualTo(1L);
            assertThat(query(realtime,
                    "select conname from pg_constraint where contype = 'c'"
                            + " and conrelid = 'realtime.realtime_result_input_snapshot'::regclass order by conname"))
                    .extracting(row -> (String) row.get(0))
                    .containsExactly("realtime_result_input_snapshot_hash_ck", "realtime_result_input_snapshot_reason_ck",
                            "realtime_result_input_snapshot_record_ck", "realtime_result_input_snapshot_status_ck",
                            "realtime_result_input_snapshot_version_ck");
            List<List<Object>> guardianIndex = query(realtime,
                    "select indexdef from pg_indexes where schemaname = 'realtime'"
                            + " and indexname = 'realtime_activity_session_guardian_idx'");
            assertThat(guardianIndex).singleElement().satisfies(row -> assertThat((String) row.get(0))
                    .startsWith("CREATE INDEX").contains("(guardian_id)").doesNotContain("WHERE"));
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
        "realtime_device_disconnect_event, 7",
        "realtime_audio_recording, 10",
        "realtime_session_gap, 6",
        "realtime_result_input_snapshot, 12",
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
                    "select indexname, indexdef from pg_indexes where schemaname = 'realtime'"
                            + " and tablename = 'realtime_activity_session' and indexdef like '%WHERE%'"
                            + " order by indexname");
            assertThat(partial.stream().map(row -> (String) row.get(0)).toList()).containsExactly(
                    "realtime_activity_session_in_progress_device_uk",
                    "realtime_activity_session_in_progress_guardian_uk");
            assertThat(partial).allSatisfy(row -> assertThat((String) row.get(1))
                    .startsWith("CREATE UNIQUE INDEX").contains("'PREPARING'").contains("'CLOSING'")
                    .doesNotContain("'ENDED'"));
            // V8 서비스 가드레일 사실 조회 색인: 부분 색인이지만 유일 색인은 아니다
            assertThat((String) single(realtime,
                    "select indexdef from pg_indexes where schemaname = 'realtime'"
                            + " and tablename = 'realtime_session_event'"
                            + " and indexname = 'realtime_session_guardrail_revision_idx'"))
                    .startsWith("CREATE INDEX")
                    .contains("realtime_session_event", "session_id", "revision", "DESC", "SESSION_GUARDRAIL_FACTS")
                    .doesNotContain("UNIQUE");
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
