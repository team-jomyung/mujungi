package kr.mujoong.core.db;

import static kr.mujoong.core.db.CoreDbFixtures.T0;
import static kr.mujoong.core.db.CoreDbFixtures.query;
import static kr.mujoong.core.db.CoreDbFixtures.single;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import kr.mujoong.core.catalog.ApprovedContent;
import kr.mujoong.core.catalog.ApprovedContentVersion;
import kr.mujoong.core.identity.Child;
import kr.mujoong.core.identity.Consent;
import kr.mujoong.core.identity.Guardian;
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
 * Core V1 스키마 통합 시험(U1 3.5 Step 14). 실제 init 스크립트 + 두 마이그레이션 위치로 만든 DB에서 표 · 열 · 형식 · 색인과
 * JPA 엔터티 대조(ddl-auto=validate)를 본다. U2가 V2(표 2개 · 동의 열 1개 · 부분 유일 색인 1개)를 더해 목록을 늘렸다.
 *
 * <p>증명: BR8.2 · J43(V1이 표 12개를 만든다) · E1 ~ E4 열 수(core_device 22 · core_activity 20 · Outbox/Inbox 각 17) · A3
 * trace_id 열 · J115(timestamptz · jsonb · uuid, DB 시각 기본값 없음) · PD-8(fillfactor 90 · 부분 유일 색인) · J116(이력 표) ·
 * Step 10 JPA 엔터티 validate.
 */
@Tag("u1")
@Tag("db")
class CoreSchemaIT {

    /** V1이 만드는 core 표 12개(J43) + U2 V2가 더하는 2개(core_data_deletion_request · core_social_account). */
    static final List<String> CORE_TABLES = List.of(
            "core_activity",
            "core_approved_content",
            "core_approved_content_version",
            "core_child",
            "core_consent",
            "core_data_deletion_request",
            "core_device",
            "core_guardian",
            "core_inbox_event",
            "core_outbox_event",
            "core_social_account",
            "core_spring_session",
            "core_spring_session_attributes",
            "core_stop_expression");

    private static MujoongPostgres postgres;

    @BeforeAll
    static void startDatabase() {
        postgres = MujoongPostgres.sharedOrAbort();
    }

    @Test
    void migrationsCreateExactlyTheCoreTablesOwnedByTheMigrationRole() throws SQLException {
        try (Connection owner = postgres.connect(Role.OWNER)) {
            List<List<Object>> tables = query(owner,
                    "select tablename, tableowner from pg_tables where schemaname = 'core' order by tablename");
            assertThat(tables.stream().map(row -> (String) row.get(0)).toList()).containsExactlyElementsOf(CORE_TABLES);
            // 편차 (x): 표 소유는 마이그레이션 역할, 앱 역할은 쓰는 역할일 뿐이다
            assertThat(tables).allSatisfy(row -> assertThat(row.get(1)).isEqualTo("mujoong_owner"));

            // 이력 표 flyway.core_schema_history에 V1 · V2(U2)가 차례로 성공으로 있다(J116). core 위치가 먼저라 기준선 행은 없다
            List<List<Object>> history = query(owner,
                    "select version, type, success from flyway.core_schema_history order by installed_rank");
            assertThat(history).containsExactly(List.of("1", "SQL", true), List.of("2", "SQL", true));
        }
    }

    @ParameterizedTest(name = "{0}: {1}열")
    @CsvSource({
        "core_device, 22",
        "core_activity, 20",
        "core_outbox_event, 17",
        "core_inbox_event, 17",
        "core_guardian, 4",
        "core_child, 9",
        "core_consent, 11",
        "core_stop_expression, 7",
        "core_social_account, 6",
        "core_data_deletion_request, 16",
        "core_approved_content, 4",
        "core_approved_content_version, 7",
        "core_spring_session, 7",
        "core_spring_session_attributes, 3",
    })
    void tablesHaveTheEntityColumnCounts(String table, int expectedColumns) throws SQLException {
        try (Connection core = postgres.connect(Role.CORE_APP)) {
            assertThat(single(core,
                    "select count(*) from information_schema.columns where table_schema = 'core' and table_name = ?", table))
                    .isEqualTo((long) expectedColumns);
        }
    }

    @Test
    void outboxAndInboxCarryAnOptionalTraceIdColumn() throws SQLException {
        try (Connection core = postgres.connect(Role.CORE_APP)) {
            List<List<Object>> columns = query(core,
                    "select table_name, data_type, character_maximum_length, is_nullable from information_schema.columns"
                            + " where table_schema = 'core' and column_name = 'trace_id' order by table_name");
            // A3: 네 표 중 core 쪽 둘, varchar(32) · NULL 허용
            assertThat(columns).containsExactly(
                    List.of("core_inbox_event", "character varying", 32, "YES"),
                    List.of("core_outbox_event", "character varying", 32, "YES"));
            // A4: guardian_id 열과 계정 삭제 참가자용 색인
            assertThat(single(core,
                    "select count(*) from pg_indexes where schemaname = 'core' and indexname in"
                            + " ('core_outbox_event_guardian_idx', 'core_inbox_event_guardian_idx')"))
                    .isEqualTo(2L);
        }
    }

    @Test
    void timeColumnsAreTimestamptzAndNoColumnDefaultReadsTheDatabaseClock() throws SQLException {
        try (Connection core = postgres.connect(Role.CORE_APP)) {
            List<List<Object>> timeColumns = query(core,
                    "select table_name || '.' || column_name, data_type from information_schema.columns"
                            + " where table_schema = 'core' and data_type like 'timestamp%'");
            // V1의 시각 열 39개(Spring Session 표는 epoch 밀리초 bigint라 제외된다)
            assertThat(timeColumns).hasSizeGreaterThanOrEqualTo(39);
            assertThat(timeColumns).allSatisfy(row -> assertThat(row.get(1)).as("%s", row.get(0))
                    .isEqualTo("timestamp with time zone"));

            // DB 시각 기본값 없음(R2 · NFR6.4): 시각을 만드는 함수 이름이 기본값에 0건
            List<List<Object>> clockDefaults = query(core,
                    "select table_name || '.' || column_name from information_schema.columns"
                            + " where table_schema = 'core' and column_default ~* '(now|clock_timestamp|statement_timestamp"
                            + "|transaction_timestamp|current_timestamp|localtimestamp|timeofday)'");
            assertThat(clockDefaults).isEmpty();

            // J115: json → jsonb, uuid → uuid
            assertThat(query(core,
                    "select distinct data_type from information_schema.columns where table_schema = 'core'"
                            + " and column_name like '%json'"))
                    .containsExactly(List.of("jsonb"));
            assertThat(single(core,
                    "select data_type from information_schema.columns where table_schema = 'core'"
                            + " and table_name = 'core_device' and column_name = 'id'"))
                    .isEqualTo("uuid");
        }
    }

    @Test
    void coreDeviceHasFillfactor90AndThePartialUniqueIndexesMatchTheirQueries() throws SQLException {
        try (Connection core = postgres.connect(Role.CORE_APP)) {
            assertThat(single(core,
                    "select array_to_string(reloptions, ',') from pg_class c join pg_namespace n on n.oid = c.relnamespace"
                            + " where n.nspname = 'core' and c.relname = 'core_device'"))
                    .isEqualTo("fillfactor=90");

            List<List<Object>> partial = query(core,
                    "select indexname, indexdef from pg_indexes where schemaname = 'core' and indexdef like '%WHERE%'"
                            + " order by indexname");
            assertThat(partial.stream().map(row -> (String) row.get(0)).toList()).containsExactly(
                    "core_activity_in_progress_guardian_uk",
                    "core_data_deletion_request_open_guardian_uk",
                    "core_device_active_guardian_uk",
                    "core_device_pending_code_uk");
            assertThat(partial).allSatisfy(row -> assertThat((String) row.get(1)).startsWith("CREATE UNIQUE INDEX"));
            assertThat((String) partial.get(2).get(1)).contains("(guardian_id)").contains("'REGISTERED'");
            assertThat((String) partial.get(3).get(1)).contains("(registration_code)").contains("'UNREGISTERED'");
            assertThat((String) partial.get(0).get(1)).contains("'REQUESTED'").contains("'CLOSING'")
                    .doesNotContain("'ENDED'");
            // U2: 보호자당 열린(REQUESTED) 삭제 요청 하나
            assertThat((String) partial.get(1).get(1)).contains("(guardian_id)").contains("'REQUESTED'");
        }
    }

    @Test
    void jpaEntitiesValidateAgainstV1AndRoundTripAsTheAppRole() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(CoreEntityScan.class)
                .withPropertyValues(
                        "spring.datasource.url=" + postgres.jdbcUrl(),
                        "spring.datasource.username=" + postgres.username(Role.CORE_APP),
                        "spring.datasource.password=" + postgres.password(Role.CORE_APP),
                        "spring.datasource.hikari.maximum-pool-size=2",
                        // core-api application.yml과 같은 JPA 설정(ddl-auto=validate · 기본 스키마 core · UTC)
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.jpa.open-in-view=false",
                        "spring.jpa.properties.hibernate.default_schema=core",
                        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EntityManagerFactory factory = context.getBean(EntityManagerFactory.class);
                    roundTrip(factory);
                });
    }

    private static void roundTrip(EntityManagerFactory factory) {
        UUID guardianId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID consentId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String contentCode = "TEST_" + contentId.toString().substring(0, 8);

        EntityManager entityManager = factory.createEntityManager();
        try {
            entityManager.getTransaction().begin();
            entityManager.persist(new Guardian(guardianId, T0));
            entityManager.persist(new Child(childId, guardianId, "하늘", 8, "[\"공룡\",\"그림\"]", T0));
            entityManager.persist(new Consent(consentId, guardianId, childId, Consent.TYPE_VOICE_AND_TEXT,
                    Consent.STATUS_GIVEN, 1, "1.0", T0));
            entityManager.persist(new ApprovedContent(contentId, contentCode, ApprovedContent.TYPE_APPROVED_AUDIO, T0));
            entityManager.persist(new ApprovedContentVersion(versionId, contentId, 1, "시험 문구", "test-v1.wav",
                    ApprovedContentVersion.STATUS_APPROVED, T0));
            entityManager.flush();
            entityManager.clear();

            Guardian guardian = entityManager.find(Guardian.class, guardianId);
            assertThat(guardian.getStatus()).isEqualTo(Guardian.STATUS_ACTIVE);
            assertThat(guardian.getCreatedAt()).isEqualTo(T0);
            assertThat(guardian.isDeletionRequested()).isFalse();

            Child child = entityManager.find(Child.class, childId);
            assertThat(child.getGuardianId()).isEqualTo(guardianId);
            assertThat(child.getDisplayName()).isEqualTo("하늘");
            assertThat(child.getAge()).isEqualTo(8);
            assertThat(child.getInterestsJson().replace(" ", "")).isEqualTo("[\"공룡\",\"그림\"]");
            assertThat(child.getDeletedAt()).isNull();

            Consent consent = entityManager.find(Consent.class, consentId);
            assertThat(consent.getPolicyVersion()).isEqualTo("1.0");
            assertThat(consent.isGivenAt(T0)).isTrue();
            assertThat(consent.getEffectiveTo()).isNull();

            ApprovedContentVersion version = entityManager.find(ApprovedContentVersion.class, versionId);
            assertThat(version.getContentId()).isEqualTo(contentId);
            assertThat(version.audioUrl()).contains("/media/approved/test-v1.wav");
            assertThat(entityManager.find(ApprovedContent.class, contentId).getContentCode()).isEqualTo(contentCode);
        } finally {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            entityManager.close();
        }
    }

    /** Step 10 엔터티 패키지 전부(identity · catalog와 뒤 단계가 더하는 엔터티)를 스캔한다. */
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackages = "kr.mujoong.core")
    static class CoreEntityScan {
    }
}
