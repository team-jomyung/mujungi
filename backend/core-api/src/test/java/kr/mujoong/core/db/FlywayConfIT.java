package kr.mujoong.core.db;

import static kr.mujoong.core.db.CoreDbFixtures.expectSqlStateAutoCommit;
import static kr.mujoong.core.db.CoreDbFixtures.query;
import static kr.mujoong.core.db.CoreDbFixtures.single;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.MigrationSet;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * migrate 컨테이너 Flyway 설정 {@code infra/migrate/flyway-core.conf} · {@code flyway-realtime.conf} 통합 시험(U1 3.5 Step 10 · 11 ·
 * 14 · J116 · report-g1-b 기준선 문제).
 *
 * <p>설정 파일을 그대로 읽어(위치만 저장소 경로로 바꿈) 빈 DB에 core → realtime 순서로 적용하고, 시험 백엔드 MujoongPostgres.flyway와
 * 같은 설정인지 대조한다. 증명: 두 위치가 flyway 스키마를 함께 써도 두 번째 migrate가 성공(baselineOnMigrate · baselineVersion 0),
 * 기준선이 없으면 실패, 다시 돌리면 0건(멱등), afterMigrate.sql의 자기 이력 표 SELECT, clean 막힘, 설정 파일에 접속 정보 · 비밀 없음.
 * 3.6 수정 분량: 설정 파일이 검증을 끄지 않아 적용된 파일을 고치거나 낮은 버전을 늦게 끼우면 migrate가 아무것도 하기 전에 거부한다(배포 때
 * 이미 적용된 스키마와 저장소가 어긋나는 것을 막는다). 두 시험은 공용 인스턴스에 검증만 하고 이력 표를 바꾸지 않는다.
 */
@Tag("u1")
@Tag("db")
class FlywayConfIT {

    @ParameterizedTest
    @EnumSource(MigrationSet.class)
    void confFilesMatchTheTestBackendFlywaySettingsAndCarryNoConnectionSecrets(MigrationSet set) throws IOException {
        Map<String, String> conf = readConf(set);
        assertThat(conf.keySet()).noneMatch(key -> key.matches("flyway\\.(url|user|password|jdbcProperties.*)"));
        assertThat(String.join("\n", conf.values())).doesNotContainIgnoringCase("password");

        Configuration fromConf = Flyway.configure().configuration(withRepoLocation(set, conf));
        // 비교용 설정만 만든다(접속하지 않는다). 시험 백엔드는 공용 인스턴스의 설정을 쓴다
        Configuration fromTestBackend = MujoongPostgres.sharedOrAbort().flyway(set).getConfiguration();
        assertThat(fromConf.getTable()).isEqualTo(set.historyTable()).isEqualTo(fromTestBackend.getTable());
        assertThat(fromConf.getDefaultSchema()).isEqualTo("flyway").isEqualTo(fromTestBackend.getDefaultSchema());
        assertThat(fromConf.getSchemas()).containsExactly("flyway", set.appSchema())
                .containsExactly(fromTestBackend.getSchemas());
        assertThat(fromConf.isCreateSchemas()).isFalse().isEqualTo(fromTestBackend.isCreateSchemas());
        assertThat(fromConf.isCleanDisabled()).isTrue().isEqualTo(fromTestBackend.isCleanDisabled());
        assertThat(fromConf.isBaselineOnMigrate()).isTrue().isEqualTo(fromTestBackend.isBaselineOnMigrate());
        assertThat(fromConf.getBaselineVersion().getVersion()).isEqualTo("0")
                .isEqualTo(fromTestBackend.getBaselineVersion().getVersion());
        // migrate 이미지 안 위치(Step 44가 같은 경로로 SQL을 복사한다)
        assertThat(conf.get("flyway.locations")).isEqualTo("filesystem:/flyway/sql/" + set.appSchema());
    }

    @Test
    void confFilesMigrateAnEmptyDatabaseInOrderAndAreIdempotent() throws SQLException, IOException {
        try (MujoongPostgres postgres = MujoongPostgres.builder().migrate(false).start()) {
            Flyway core = flywayFromConf(postgres, MigrationSet.CORE, Map.of());
            Flyway realtime = flywayFromConf(postgres, MigrationSet.REALTIME, Map.of());

            // 기준선 없이 두 번째 위치를 돌리면 "비어 있지 않은 flyway 스키마 + 이력 표 없음"으로 실패한다(report-g1-b 재현)
            MigrateResult coreResult = core.migrate();
            // core 위치: V1(U1) · V2(U2) · V3(U4a) · V4(U5a) · V5(U6) · V6(U7) · V7(U5b) · V8(U15) · V9(U14)
            assertThat(coreResult.migrationsExecuted).isEqualTo(9);
            Flyway realtimeWithoutBaseline =
                    flywayFromConf(postgres, MigrationSet.REALTIME, Map.of("flyway.baselineOnMigrate", "false"));
            assertThatThrownBy(realtimeWithoutBaseline::migrate)
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("non-empty schema");

            // 설정 파일 그대로면 버전 0 기준선 뒤 V1 · V2(U3) · V3(U4b) · V4(U5b) · V5(U11)가 적용된다
            assertThat(realtime.migrate().migrationsExecuted).isEqualTo(5);
            // 다시 돌리면 적용할 것이 없다(배포마다 migrate를 불러도 안전)
            assertThat(core.migrate().migrationsExecuted).isZero();
            assertThat(realtime.migrate().migrationsExecuted).isZero();

            try (Connection owner = postgres.connect(Role.OWNER)) {
                assertThat(query(owner,
                        "select version, type, success from flyway.core_schema_history order by installed_rank"))
                        .containsExactly(List.of("1", "SQL", true), List.of("2", "SQL", true), List.of("3", "SQL", true),
                                List.of("4", "SQL", true), List.of("5", "SQL", true), List.of("6", "SQL", true),
                                List.of("7", "SQL", true), List.of("8", "SQL", true), List.of("9", "SQL", true));
                assertThat(query(owner,
                        "select version, type, success from flyway.realtime_schema_history order by installed_rank"))
                        .containsExactly(List.of("0", "BASELINE", true), List.of("1", "SQL", true), List.of("2", "SQL", true),
                                List.of("3", "SQL", true), List.of("4", "SQL", true), List.of("5", "SQL", true));
                // core 45(V1~V9) + realtime 17(V1~V5)
                assertThat(single(owner, "select count(*) from pg_tables where schemaname in ('core', 'realtime')"))
                        .as("core 45(V1 12 + U2 V2 2 + U4a V3 2 + U5a V4 7 + U6 V5 10 + U7 V6 2 + U5b V7 1 + U15 V8 6 + U14 V9 3)"
                                + " · realtime 17(V1 7 + U3 V2 3 + U4b V3 1 + U5b V4 5 + U11 V5 1)")
                        .isEqualTo(62L);
            }
            // afterMigrate.sql: 앱 역할은 자기 이력 표만 읽는다
            try (Connection coreApp = postgres.connect(Role.CORE_APP);
                    Connection realtimeApp = postgres.connect(Role.REALTIME_APP)) {
                // core V1 · V2(U2) · V3(U4a) · V4(U5a) · V5(U6) · V6(U7) · V7(U5b) · V8(U15) · V9(U14)
                // realtime BASELINE · V1 · V2(U3) · V3(U4b) · V4(U5b) · V5(U11)
                assertThat(single(coreApp, "select count(*) from flyway.core_schema_history")).isEqualTo(9L);
                assertThat(single(realtimeApp, "select count(*) from flyway.realtime_schema_history")).isEqualTo(6L);
                expectSqlStateAutoCommit("42501",
                        () -> query(coreApp, "select count(*) from flyway.realtime_schema_history"));
            }

            // 운영 DB를 지우는 clean은 막혀 있다
            assertThatThrownBy(core::clean).isInstanceOf(FlywayException.class);
        }
    }

    @Test
    void anAppliedMigrationEditedAfterwardsIsRejectedBeforeAnythingRuns(@TempDir Path edited) throws IOException, SQLException {
        MujoongPostgres postgres = MujoongPostgres.sharedOrAbort();
        copyMigrations(postgres, MigrationSet.CORE, edited);
        Path v1 = edited.resolve("V1__core_base.sql");
        Files.writeString(v1, Files.readString(v1, StandardCharsets.UTF_8) + "\n-- 적용한 뒤에 고친 줄\n", StandardCharsets.UTF_8);
        List<List<Object>> before = coreHistory(postgres);

        Flyway changed = flywayFromConf(postgres, MigrationSet.CORE, Map.of("flyway.locations", "filesystem:" + edited));

        assertThatThrownBy(changed::validate).isInstanceOf(FlywayException.class).hasMessageContaining("checksum");
        assertThatThrownBy(changed::migrate).as("배포의 migrate도 같은 검증으로 멈춘다").isInstanceOf(FlywayException.class)
                .hasMessageContaining("checksum");
        assertThat(coreHistory(postgres)).as("이력 표는 그대로").isEqualTo(before);
        // 대조군: 고치지 않은 원본 위치는 같은 설정으로 검증을 통과한다
        flywayFromConf(postgres, MigrationSet.CORE, Map.of()).validate();
    }

    @Test
    void aLowerVersionAddedAfterTheAppliedOneIsRejectedInsteadOfRunningOutOfOrder(@TempDir Path late)
            throws IOException, SQLException {
        MujoongPostgres postgres = MujoongPostgres.sharedOrAbort();
        copyMigrations(postgres, MigrationSet.CORE, late);
        Files.writeString(late.resolve("V0_5__forgotten.sql"), "-- 늦게 끼운 낮은 버전\nselect 1;\n", StandardCharsets.UTF_8);
        List<List<Object>> before = coreHistory(postgres);

        Flyway withLateFile = flywayFromConf(postgres, MigrationSet.CORE, Map.of("flyway.locations", "filesystem:" + late));

        assertThatThrownBy(withLateFile::migrate).as("outOfOrder를 켜지 않았다").isInstanceOf(FlywayException.class)
                .hasMessageContaining("0.5");
        assertThat(coreHistory(postgres)).as("늦게 끼운 버전은 적용되지 않았다").isEqualTo(before);
    }

    // ------------------------------------------------------------------ 도구

    /** 저장소의 마이그레이션 위치 파일을 그대로 복사한다(afterMigrate.sql 포함). */
    private static void copyMigrations(MujoongPostgres postgres, MigrationSet set, Path target) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(postgres.repoRoot().resolve(set.relativeLocation()))) {
            for (Path file : files.toList()) {
                Files.copy(file, target.resolve(file.getFileName().toString()));
            }
        }
    }

    private static List<List<Object>> coreHistory(MujoongPostgres postgres) throws SQLException {
        try (Connection owner = postgres.connect(Role.OWNER)) {
            return query(owner, "select installed_rank, version, checksum, success from flyway.core_schema_history"
                    + " order by installed_rank");
        }
    }

    private static Path confPath(MigrationSet set) {
        return MujoongPostgres.sharedOrAbort().repoRoot().resolve("infra/migrate/flyway-" + set.appSchema() + ".conf");
    }

    /** migrate CLI와 같은 Java 속성 형식으로 읽는다. */
    private static Map<String, String> readConf(MigrationSet set) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(confPath(set), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        Map<String, String> conf = new LinkedHashMap<>();
        properties.stringPropertyNames().stream().sorted().forEach(key -> conf.put(key, properties.getProperty(key)));
        assertThat(conf).as(confPath(set).toString()).isNotEmpty();
        return conf;
    }

    /** 이미지 안 위치를 저장소의 원본 위치로 바꾼다(나머지 값은 그대로). */
    private static Map<String, String> withRepoLocation(MigrationSet set, Map<String, String> conf) {
        Map<String, String> copy = new LinkedHashMap<>(conf);
        copy.put("flyway.locations",
                "filesystem:" + MujoongPostgres.sharedOrAbort().repoRoot().resolve(set.relativeLocation()));
        return copy;
    }

    private static Flyway flywayFromConf(MujoongPostgres postgres, MigrationSet set, Map<String, String> overrides)
            throws IOException {
        Map<String, String> conf = withRepoLocation(set, readConf(set));
        conf.putAll(overrides);
        // 접속 정보는 migrate 진입 스크립트처럼 설정 파일 밖에서 준다(FLYWAY_URL · FLYWAY_USER · FLYWAY_PASSWORD 자리)
        conf.put("flyway.url", postgres.jdbcUrl());
        conf.put("flyway.user", postgres.username(Role.OWNER));
        conf.put("flyway.password", postgres.password(Role.OWNER));
        return Flyway.configure().configuration(conf).load();
    }
}
