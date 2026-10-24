package kr.mujoong.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.support.CoreBusinessContext;
import kr.mujoong.core.support.LiveContentFixtures;
import kr.mujoong.core.support.LiveContentFixtures.SpeechRow;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.secret.TestSecrets;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * dev 시드의 U8 승인 발화(U8 Step 11 · BR1.1 · 설계 검토 R-01). 시드 SQL이 없는 빈 DB에서 {@link DevSeedRunner}가 004와 같은 UUID · 판 ·
 * 글 · 종류(APPROVED_SPEECH) · 음성 없음으로 다섯 code를 넣고, 다시 돌리면 0행이며, 002가 뒤에 들어와도 HELP_OFFER 종류를 바꿔 v2가 APPROVED
 * 최고 판으로 남는지 본다(002 v1 DRAFT의 RETIRED는 004 몫). 연습 대화 C안 고정 승인 발화 6종도 006과 같은 값으로 넣고, 006 SQL을 뒤에 적용해도
 * 행이 늘지 않는지 본다. 공용 DB를 건드리지 않게 따로 띄운 DB를 쓴다.
 */
@Tag("live-content")
@Tag("db")
class LiveContentDevSeedRunnerIT {

    private static MujoongPostgres postgres;
    private static DbTestSupport db;

    @BeforeAll
    static void start() {
        postgres = MujoongPostgres.builder().start();
        db = DbTestSupport.coreApp(postgres);
    }

    @AfterAll
    static void stop() {
        if (db != null) {
            db.close();
        }
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    void devSeedWritesTheSame004RowsIsIdempotentAndLeavesSeed002DraftsToSeed004() {
        runner().run(context -> {
            DevSeedRunner seed = context.getBean(DevSeedRunner.class);
            seed.seed();

            for (SpeechRow row : allSpeechRows()) {
                Map<String, Object> version = version(row.code(), "APPROVED");
                assertThat(version).as(row.code()).containsEntry("content_id", row.contentId())
                        .containsEntry("id", row.versionId()).containsEntry("version", row.version())
                        .containsEntry("content_text", row.text()).containsEntry("content_type", "APPROVED_SPEECH");
                assertThat(version.get("audio_path")).as(row.code() + " 음성 없음").isNull();
            }
            assertThat(DevSeedRunner.SPEECH_SEEDS).extracting(DevSeedRunner.SpeechSeed::code)
                    .containsExactlyElementsOf(LiveContentFixtures.SPEECH_ROWS.stream().map(SpeechRow::code).toList());
            assertThat(DevSeedRunner.DIRECT_ASK_SEEDS).extracting(DevSeedRunner.SpeechSeed::code)
                    .containsExactlyElementsOf(LiveContentFixtures.DIRECT_ASK_ROWS.stream().map(SpeechRow::code).toList());
            assertThat(seed.seed()).as("다시 돌리면 아무것도 넣거나 고치지 않는다").isZero();

            LiveContentFixtures.applyDirectAskSeed(postgres);
            assertThat(seed.seed()).as("006 SQL 뒤에도 같은 행이라 더 넣지 않는다(006 확인 단계도 통과)").isZero();

            LiveContentFixtures.applySeed(postgres, "002-u2-reference.sql");
            assertThat(seed.seed()).as("002 뒤에도 종류 행은 이미 APPROVED_SPEECH").isZero();
            assertThat(status("HELP_OFFER", 1)).as("dev 시드는 002 v1 DRAFT를 바꾸지 않는다").isEqualTo("DRAFT");
            assertThat(version("HELP_OFFER", "APPROVED")).containsEntry("version", 2);

            LiveContentFixtures.applyU8Seed(postgres);
            assertThat(status("HELP_OFFER", 1)).as("004가 v1 DRAFT를 RETIRED로").isEqualTo("RETIRED");
        });
    }

    private static List<SpeechRow> allSpeechRows() {
        var rows = new java.util.ArrayList<>(LiveContentFixtures.SPEECH_ROWS);
        rows.addAll(LiveContentFixtures.DIRECT_ASK_ROWS);
        return rows;
    }

    private static ApplicationContextRunner runner() {
        return CoreBusinessContext.runner(postgres, new MutableClock(SeedIds.U8_SEED_TIME), URI.create("http://127.0.0.1:9"),
                TestSecrets.internalServiceToken())
                .withPropertyValues("spring.profiles.active=test,dev", "mujoong.dev-login.enabled=true")
                .withUserConfiguration(DevSeedRunner.class);
    }

    private static Map<String, Object> version(String code, String status) {
        List<Map<String, Object>> rows = db.jdbc().queryForList("""
                select c.id as content_id, c.content_type, v.id, v.version, v.content_text, v.audio_path
                  from core.core_approved_content c join core.core_approved_content_version v on v.content_id = c.id
                 where c.content_code = :code and v.status = :status order by v.version desc
                """, new MapSqlParameterSource("code", code).addValue("status", status));
        assertThat(rows).as(code + " " + status).isNotEmpty();
        return rows.get(0);
    }

    private static String status(String code, int version) {
        return db.jdbc().queryForObject("""
                select v.status from core.core_approved_content c join core.core_approved_content_version v on v.content_id = c.id
                 where c.content_code = :code and v.version = :version
                """, new MapSqlParameterSource("code", code).addValue("version", version), String.class);
    }
}
