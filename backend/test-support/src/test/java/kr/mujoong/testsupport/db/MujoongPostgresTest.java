package kr.mujoong.testsupport.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import kr.mujoong.testsupport.db.MujoongPostgres.Backend;
import kr.mujoong.testsupport.db.MujoongPostgres.MigrationSet;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import kr.mujoong.testsupport.db.MujoongPostgres.Selection;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * DB 시험 백엔드 자기 시험(J132 · J135 · J116 · SD-13). 두 백엔드가 같은 {@code infra/postgres/} 스크립트로 같은 DB를 만드는지 본다.
 * 쓸 수 없는 백엔드는 건너뛴다(가정 실패, 사유를 남긴다). 권한 · 제약의 전체 확인은 Step 14 시험이 한다.
 */
@Tag("skeleton")
@Tag("db")
class MujoongPostgresTest {

    @ParameterizedTest
    @EnumSource(Backend.class)
    void initScriptsCreateRolesSchemasAndSingleHbaAllowLine(Backend backend) throws SQLException {
        assumeAvailable(backend);
        try (MujoongPostgres postgres = MujoongPostgres.builder().selection(selectionOf(backend)).migrate(false).start()) {
            assertThat(postgres.backend()).isEqualTo(backend);
            assertThat(postgres.backendName()).isEqualTo(backend.wireName());
            assertThat(System.getProperty(MujoongPostgres.USED_BACKEND_PROPERTY)).isEqualTo(backend.wireName());

            for (Role role : Role.values()) {
                assertThat(queryString(postgres, role, "select current_user")).isEqualTo(role.roleName());
            }
            assertThat(queryString(postgres, Role.CORE_APP, "select current_setting('mujoong_db.data_class')"))
                    .isEqualTo("synthetic");
            assertThat(queryString(postgres, Role.CORE_APP, "select current_setting('TimeZone')")).isEqualTo("UTC");
            assertThat(queryString(postgres, Role.CORE_APP,
                    "select has_schema_privilege('core', 'USAGE')::text || has_schema_privilege('realtime', 'USAGE')::text"))
                    .isEqualTo("truefalse");
            assertThat(queryString(postgres, Role.REALTIME_APP,
                    "select has_schema_privilege('realtime', 'USAGE')::text || has_schema_privilege('core', 'USAGE')::text"))
                    .isEqualTo("truefalse");
            assertThat(queryString(postgres, Role.SUPERUSER, "select current_setting('password_encryption')"))
                    .isEqualTo("scram-sha-256");

            List<String> rules = postgres.pgHbaConf().lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.replaceAll("\\s+", " "))
                    .toList();
            assertThat(rules).containsExactly(
                    "host all all " + postgres.hbaAllowedCidr() + " scram-sha-256",
                    "local all all reject",
                    "host all all all reject");
        }
    }

    @ParameterizedTest
    @EnumSource(Backend.class)
    void connectionFromOutsideTheAllowedCidrIsRejected(Backend backend) {
        assumeAvailable(backend);
        // 192.0.2.1은 문서용 주소(RFC 5737)라 시험 출발지일 수 없다(J135)
        try (MujoongPostgres postgres = MujoongPostgres.builder()
                .selection(selectionOf(backend))
                .hbaAllowedCidr("192.0.2.1/32")
                .migrate(false)
                .start()) {
            assertThatThrownBy(() -> postgres.connect(Role.CORE_APP).close())
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("28000"));
        }
    }

    @ParameterizedTest
    @EnumSource(Backend.class)
    void twoMigrationSetsKeepSeparateHistoryTablesInFlywaySchema(Backend backend, @TempDir Path dir) throws Exception {
        assumeAvailable(backend);
        Path core = Files.createDirectories(dir.resolve("core"));
        Path realtime = Files.createDirectories(dir.resolve("realtime"));
        Files.writeString(core.resolve("V900__probe.sql"), "create table core.probe_core (id int primary key);\n");
        Files.writeString(realtime.resolve("V900__probe.sql"), "create table realtime.probe_rt (id int primary key);\n");
        try (MujoongPostgres postgres = MujoongPostgres.builder().selection(selectionOf(backend)).migrate(false).start()) {
            postgres.flyway(MigrationSet.CORE, "filesystem:" + core).migrate();
            postgres.flyway(MigrationSet.REALTIME, "filesystem:" + realtime).migrate();

            assertThat(queryString(postgres, Role.OWNER,
                    "select string_agg(table_name, ',' order by table_name) from information_schema.tables"
                            + " where table_schema = 'flyway'"))
                    .isEqualTo("core_schema_history,realtime_schema_history");
            // 기본 권한(SD-13): mujoong_owner가 만든 core 표에 core_app은 DML, realtime_app은 권한 없음
            assertThat(queryString(postgres, Role.CORE_APP,
                    "select has_table_privilege('core.probe_core', 'SELECT,INSERT,UPDATE,DELETE')::text"))
                    .isEqualTo("true");
            assertThatThrownBy(() -> queryString(postgres, Role.REALTIME_APP, "select count(*) from core.probe_core"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
    }

    private static void assumeAvailable(Backend backend) {
        boolean available = backend == Backend.DOCKER ? MujoongPostgres.isDockerAvailable() : MujoongPostgres.isLocalAvailable();
        Assumptions.assumeTrue(available, () -> backend.wireName() + " 백엔드를 쓸 수 없어 건너뜀(J132)");
    }

    private static Selection selectionOf(Backend backend) {
        return backend == Backend.DOCKER ? Selection.DOCKER : Selection.LOCAL;
    }

    private static String queryString(MujoongPostgres postgres, Role role, String sql) throws SQLException {
        try (Connection connection = postgres.connect(role);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
