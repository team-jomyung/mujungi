package kr.mujoong.core.db;

import static kr.mujoong.core.db.CoreDbFixtures.query;
import static kr.mujoong.core.db.CoreDbFixtures.single;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** JIRA-272: 빈 DB와 수동 시딩된 DB 모두 배포 이미지의 참조 SQL로 최신 음성을 등록한다. */
@Tag("db")
@Tag("skeleton")
class ReferenceSeedMigrationIT {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deploymentSeedsMatchTheNoticeManifestAndAreAppliedOnlyOnce(boolean previouslySeeded, @TempDir Path imageSql)
            throws Exception {
        try (MujoongPostgres postgres = MujoongPostgres.builder().start()) {
            Path root = postgres.repoRoot();
            // 이미지에 실제 포함되는 파일과 이름을 Dockerfile에서 읽는다.
            var copies = Pattern.compile("COPY --from=reference_seed (\\S+) /flyway/sql/reference/(\\S+)")
                    .matcher(Files.readString(root.resolve("infra/migrate/Dockerfile")));
            int included = 0;
            while (copies.find()) {
                Files.copy(root.resolve("infra/seed/" + copies.group(1)), imageSql.resolve(copies.group(2)));
                included++;
            }
            try (var originals = Files.list(root.resolve("infra/seed"))) {
                assertThat(included).isEqualTo((int) originals.filter(p -> p.toString().endsWith(".sql")).count());
            }
            if (previouslySeeded) {
                try (Connection owner = postgres.connect(Role.OWNER); var statement = owner.createStatement()) {
                    owner.setAutoCommit(false);
                    try (var files = Files.list(imageSql)) {
                        for (Path file : files.sorted().toList()) {
                            if (!file.getFileName().toString().startsWith("V005__")) {
                                statement.execute(Files.readString(file));
                            }
                        }
                    }
                    owner.commit();
                }
            }

            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(root.resolve("infra/migrate/flyway-reference.conf"),
                    StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            Map<String, String> configuration = new LinkedHashMap<>();
            properties.forEach((key, value) -> configuration.put(key.toString(), value.toString()));
            configuration.put("flyway.locations", "filesystem:" + imageSql);
            configuration.put("flyway.url", postgres.jdbcUrl());
            configuration.put("flyway.user", postgres.username(Role.OWNER));
            configuration.put("flyway.password", postgres.password(Role.OWNER));
            Flyway reference = Flyway.configure().configuration(configuration).load();
            assertThat(reference.migrate().migrationsExecuted).isEqualTo(included);
            assertThat(reference.migrate().migrationsExecuted).isZero();
            reference.validate();

            String manifest = Files.readString(root.resolve(
                    "backend/realtime-activity/src/main/resources/service-policy/notice-assets.json"));
            try (Connection owner = postgres.connect(Role.OWNER)) {
                var audio = query(owner, "select id::text,audio_path from core.core_approved_content_version"
                        + " where status='APPROVED' and audio_path is not null");
                assertThat(audio).hasSize(8);
                for (var row : audio) {
                    assertThat(manifest).contains(row.get(0).toString(), "/media/approved/" + row.get(1));
                }
                assertThat(single(owner, "select version from core.core_approved_content_version"
                        + " where id='7dfe7883-ecdc-555d-b369-b66edde15377' and status='APPROVED'"))
                        .isEqualTo(3);
                // 연습 대화 C안 006: 이미지에 들어간 고정 승인 발화 6종이 음성 없는 APPROVED_SPEECH v1로 한 번 들어간다
                assertThat(single(owner, "select count(*) from core.core_approved_content c"
                        + " join core.core_approved_content_version v on v.content_id = c.id"
                        + " where c.content_code in ('VARIANT_OFFER','RETRY_PROMPT','INPUT_CONFIRM','SCENE_CLOSE','WAIT_LEAD','ASK_DEFAULT')"
                        + " and c.content_type = 'APPROVED_SPEECH' and v.status = 'APPROVED' and v.audio_path is null and v.version = 1"))
                        .isEqualTo(6L);
            }
        }
    }
}
