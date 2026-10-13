package kr.mujoong.core.db;

import static kr.mujoong.core.db.CoreDbFixtures.T0;
import static kr.mujoong.core.db.CoreDbFixtures.expectSqlStateAutoCommit;
import static kr.mujoong.core.db.CoreDbFixtures.query;
import static kr.mujoong.core.db.CoreDbFixtures.single;
import static kr.mujoong.core.db.CoreDbFixtures.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.MigrationSet;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 스키마 · 권한 통합 시험(U1 3.5 Step 14 · B1 ②). 실제 {@code infra/postgres/init/} 스크립트(역할 · 스키마 · 권한 · 기본 권한 ·
 * pg_hba)와 두 마이그레이션 위치(afterMigrate.sql 포함)로 만든 DB에서 역할마다 되는 것과 안 되는 것을 SQLSTATE로 확인한다.
 *
 * <p>증명: BR8.1 · NFR9.7 · SD-13(core_app ↔ realtime_app 교차 읽기 · 쓰기 42501, 앱의 CREATE · ALTER · TRUNCATE · DROP 거부,
 * public 사용 불가, 자기 이력 표만 SELECT, 백업은 읽기만, 기본 권한) · SD-24(앱의 data_class 변경 거부) · 편차 (x) · J116 ·
 * J135(pg_hba 한 대역 + reject, 대역 밖 출발지 28000).
 */
@Tag("u1")
@Tag("db")
class SchemaPrivilegeIT {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String INVALID_AUTHORIZATION = "28000";

    private static MujoongPostgres postgres;

    @BeforeAll
    static void startDatabase() {
        postgres = MujoongPostgres.sharedOrAbort();
    }

    @Test
    void coreAppCannotReadOrWriteTheRealtimeSchemaAndViceVersa() throws SQLException {
        try (Connection core = postgres.connect(Role.CORE_APP);
                Connection realtime = postgres.connect(Role.REALTIME_APP)) {
            // 자기 스키마는 읽고 쓴다(정상 경로)
            assertThat(single(core, "select count(*) >= 0 from core.core_device")).isEqualTo(true);
            assertThat(single(realtime, "select count(*) >= 0 from realtime.realtime_device_connection")).isEqualTo(true);

            // B1 ②: 교차 INSERT · SELECT는 정확히 42501
            expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE, () -> update(core,
                    "insert into realtime.realtime_device_connection (device_id, guardian_id, connection_state, updated_at)"
                            + " values (?, ?, 'OFFLINE', ?)",
                    UUID.randomUUID(), UUID.randomUUID(), T0));
            expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE,
                    () -> query(core, "select text from realtime.realtime_transcript limit 1"));
            expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE, () -> update(realtime,
                    "insert into core.core_guardian (id, status, created_at, updated_at) values (?, 'ACTIVE', ?, ?)",
                    UUID.randomUUID(), T0, T0));
            expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE,
                    () -> query(realtime, "select bootstrap_secret_hash from core.core_device limit 1"));
        }
    }

    @Test
    void appRolesCannotChangeSchemaObjectsEvenInTheirOwnSchema() throws SQLException {
        try (Connection core = postgres.connect(Role.CORE_APP);
                Connection realtime = postgres.connect(Role.REALTIME_APP)) {
            // DDL은 마이그레이션 역할만(BR8.2). 앱 역할은 CREATE 권한 · 표 소유가 없다
            expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE, () -> update(core, "create table core.app_made (id int)"));
            expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE,
                    () -> update(core, "alter table core.core_device add column app_made int"));
            expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE,
                    () -> update(core, "create index app_made_idx on core.core_device (created_at)"));
            expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE, () -> update(core, "truncate core.core_outbox_event"));
            expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE, () -> update(core, "drop table core.core_guardian"));

            expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE,
                    () -> update(realtime, "create table realtime.app_made (id int)"));
            expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE,
                    () -> update(realtime, "alter table realtime.realtime_transcript drop column text"));
            expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE, () -> update(realtime, "truncate realtime.realtime_transcript"));
        }
    }

    @Test
    void publicSchemaIsUnusableForEveryApplicationRole() throws SQLException {
        for (Role role : List.of(Role.CORE_APP, Role.REALTIME_APP, Role.OWNER, Role.BACKUP)) {
            try (Connection connection = postgres.connect(role)) {
                assertThat(single(connection, "select has_schema_privilege('public', 'CREATE')")).as(role.roleName())
                        .isEqualTo(false);
                expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE,
                        () -> update(connection, "create table public.not_allowed (id int)"));
            }
        }
        // 앱 역할의 search_path는 자기 스키마 하나다(01-roles.sql)
        try (Connection core = postgres.connect(Role.CORE_APP);
                Connection realtime = postgres.connect(Role.REALTIME_APP)) {
            assertThat(single(core, "select current_setting('search_path')")).isEqualTo("core");
            assertThat(single(realtime, "select current_setting('search_path')")).isEqualTo("realtime");
        }
    }

    @Test
    void eachAppReadsOnlyItsOwnFlywayHistoryTable() throws SQLException {
        try (Connection core = postgres.connect(Role.CORE_APP);
                Connection realtime = postgres.connect(Role.REALTIME_APP)) {
            // afterMigrate.sql이 준 자기 이력 표 SELECT(J116, 기동 점검 S2가 읽는다). core 최고 버전은 U5a의 V4
            assertThat(single(core,
                    "select max(version) from flyway.core_schema_history where success and type = 'SQL'"))
                    .isEqualTo("4");
            assertThat(single(realtime,
                    "select max(version) from flyway.realtime_schema_history where success and type = 'SQL'"))
                    .isEqualTo("2");
            expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE,
                    () -> query(core, "select version from flyway.realtime_schema_history"));
            expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE,
                    () -> query(realtime, "select version from flyway.core_schema_history"));
            // 이력 표를 고칠 수는 없다
            expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE,
                    () -> update(core, "delete from flyway.core_schema_history"));
        }
    }

    @Test
    void backupRoleReadsBothSchemasButWritesNothing() throws SQLException {
        try (Connection backup = postgres.connect(Role.BACKUP)) {
            assertThat(single(backup, "select count(*) >= 0 from core.core_device")).isEqualTo(true);
            assertThat(single(backup, "select count(*) >= 0 from realtime.realtime_transcript")).isEqualTo(true);
            expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE, () -> update(backup,
                    "insert into core.core_guardian (id, status, created_at, updated_at) values (?, 'ACTIVE', ?, ?)",
                    UUID.randomUUID(), T0, T0));
            expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE,
                    () -> update(backup, "delete from realtime.realtime_transcript"));
        }
    }

    @Test
    void appRolesCannotRelabelTheDatabaseDataClass() throws SQLException {
        for (Role role : List.of(Role.CORE_APP, Role.REALTIME_APP)) {
            try (Connection connection = postgres.connect(role)) {
                assertThat(single(connection, "select current_setting('mujoong_db.data_class')")).isEqualTo("synthetic");
                // SD-24: 데이터 등급 표지는 DB 소유자(슈퍼유저)만 바꾼다
                expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE,
                        () -> update(connection, "alter database mujoong set mujoong_db.data_class = 'real'"));
            }
        }
        try (Connection owner = postgres.connect(Role.OWNER)) {
            expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE,
                    () -> update(owner, "alter database mujoong set mujoong_db.data_class = 'real'"));
        }
    }

    @Test
    void everyTableAndSchemaIsOwnedByTheMigrationRole() throws SQLException {
        try (Connection owner = postgres.connect(Role.OWNER)) {
            // 편차 (x): 계약 C13 owner_role은 '쓰는 역할'이고 표 · 스키마 소유는 mujoong_owner
            assertThat(query(owner,
                    "select distinct tableowner from pg_tables where schemaname in ('core', 'realtime', 'flyway')"))
                    .containsExactly(List.of("mujoong_owner"));
            assertThat(query(owner,
                    "select nspname, pg_get_userbyid(nspowner) from pg_namespace"
                            + " where nspname in ('core', 'realtime', 'flyway') order by nspname"))
                    .containsExactly(
                            List.of("core", "mujoong_owner"),
                            List.of("flyway", "mujoong_owner"),
                            List.of("realtime", "mujoong_owner"));
            // 앱 역할은 슈퍼유저 · 역할 생성 · DB 생성 · 복제 · RLS 우회가 없다
            assertThat(single(owner,
                    "select count(*) from pg_roles where rolname in ('mujoong_owner', 'core_app', 'realtime_app', 'mujoong_backup')"
                            + " and not rolsuper and not rolcreaterole and not rolcreatedb and not rolreplication"
                            + " and not rolbypassrls"))
                    .isEqualTo(4L);
        }
    }

    @Test
    void defaultPrivilegesGiveTheCoreAppDmlOnTablesAddedByLaterMigrations() throws SQLException {
        Path testLocation = postgres.repoRoot().resolve("backend/core-api/src/test/resources/db/testmigration/core");
        // 공용 인스턴스에 V2를 남기지 않도록 따로 띄운다
        try (MujoongPostgres separate = MujoongPostgres.builder().start()) {
            separate.flyway(MigrationSet.CORE, "filesystem:" + testLocation).migrate();
            UUID id = UUID.randomUUID();
            try (Connection core = separate.connect(Role.CORE_APP);
                    Connection realtime = separate.connect(Role.REALTIME_APP)) {
                // SD-13: 소유자가 만든 새 core 표에 core_app의 SELECT · INSERT · UPDATE · DELETE가 기본 권한으로 붙는다
                assertThat(update(core, "insert into core.core_test_default_privilege_probe (id, note) values (?, 'a')", id))
                        .isEqualTo(1);
                assertThat(update(core, "update core.core_test_default_privilege_probe set note = 'b' where id = ?", id))
                        .isEqualTo(1);
                assertThat(single(core, "select note from core.core_test_default_privilege_probe where id = ?", id))
                        .isEqualTo("b");
                assertThat(update(core, "delete from core.core_test_default_privilege_probe where id = ?", id))
                        .isEqualTo(1);
                // TRUNCATE · DDL은 붙지 않는다
                expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE,
                        () -> update(core, "truncate core.core_test_default_privilege_probe"));
                expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE,
                        () -> update(core, "alter table core.core_test_default_privilege_probe add column x int"));
                // realtime_app은 권한이 없다
                expectSqlStateAutoCommit(INSUFFICIENT_PRIVILEGE,
                        () -> query(realtime, "select id from core.core_test_default_privilege_probe"));
                // 시험 V90(운영 V2 뒤)도 이력 표에 남고 core_app은 그 버전을 읽는다
                assertThat(single(core,
                        "select max(version::int) from flyway.core_schema_history where success and type = 'SQL'"))
                        .isEqualTo(90);
            }
        }
    }

    @Test
    void pgHbaAllowsOnlyTheGivenCidrWithScramAndRejectsEverythingElse() throws SQLException {
        List<String> rules = postgres.pgHbaConf().lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> line.replaceAll("\\s+", " "))
                .toList();
        // J135: 허용 줄은 주어진 대역의 scram-sha-256 하나, 나머지는 reject
        assertThat(rules).containsExactly(
                "host all all " + postgres.hbaAllowedCidr() + " scram-sha-256",
                "local all all reject",
                "host all all all reject");
        assertThat(postgres.hbaAllowedCidr()).endsWith("/32");
        try (Connection superuser = postgres.connect(Role.SUPERUSER)) {
            assertThat(single(superuser, "select current_setting('password_encryption')")).isEqualTo("scram-sha-256");
        }

        // 시험 출발지가 아닌 /32(문서용 주소 RFC 5737)를 허용 대역으로 준 별도 인스턴스에는 접속할 수 없다
        try (MujoongPostgres outsider = MujoongPostgres.builder().hbaAllowedCidr("192.0.2.1/32").migrate(false).start()) {
            assertThatThrownBy(() -> outsider.connect(Role.CORE_APP).close())
                    .isInstanceOfSatisfying(SQLException.class,
                            e -> assertThat(e.getSQLState()).isEqualTo(INVALID_AUTHORIZATION));
        }
    }
}
