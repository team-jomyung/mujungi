package kr.mujoong.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.identity.DevSeedRunner;
import kr.mujoong.core.identity.SeedIds;
import kr.mujoong.core.support.BusinessFixtures;
import kr.mujoong.core.support.BusinessFixtures.Family;
import kr.mujoong.core.support.CoreBusinessContext;
import kr.mujoong.core.support.LiveContentFixtures;
import kr.mujoong.core.support.LiveContentFixtures.SpeechRow;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import kr.mujoong.testsupport.secret.TestSecrets;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 참조 시드 004(U8 승인 발화 APPROVED_SPEECH 5종) · 006(연습 대화 C안 고정 승인 발화 6종) 시험 (U8 Step 11 · functional-spec 8.1 · BR1.1 ·
 * BR1.2 · 설계 검토 R-01 · docs/practice-direct-ask.md 5장).
 *
 * <p>공용 DB의 002 DRAFT 상태와 부딪치지 않게 따로 띄운 DB를 쓴다. 001 → 004(002가 없는 DB) → 002 → 003 → 004 → 001 ~ 004 한 바퀴를 적용하며
 * 확인: 002 없이도 004가 들어감, HELP_OFFER 종류 바꾸기 · v1 DRAFT RETIRED, 두 번 적용해도 같은 행(멱등), SeedIds · DevSeedRunner와 같은
 * UUID · 글, NFR2 시드 금지 표현 사전 0건, 실제 자유대화 시작 패키지에 approvedSpeech 두 code가 실리고 approvedContent와 겹치지 않음.
 */
@Tag("live-content")
@Tag("db")
class SeedLiveContentReferenceSqlIT {

    private static final List<String> SPEECH_CODES = LiveContentFixtures.SPEECH_ROWS.stream().map(SpeechRow::code).toList();
    private static final List<String> DIRECT_ASK_CODES = LiveContentFixtures.DIRECT_ASK_ROWS.stream().map(SpeechRow::code).toList();

    private static MujoongPostgres postgres;
    private static DbTestSupport db;
    private static List<String> afterFirstPass;
    private static List<String> beforeU2;
    private static List<String> directAskFirst;

    @BeforeAll
    static void start() {
        postgres = MujoongPostgres.builder().start();
        db = DbTestSupport.coreApp(postgres);
        LiveContentFixtures.applySeed(postgres, "001-reference.sql");
        LiveContentFixtures.applyU8Seed(postgres);
        beforeU2 = snapshot();
        for (String seed : List.of("002-u2-reference.sql", "003-u4a-reference.sql", "004-u8-reference.sql")) {
            LiveContentFixtures.applySeed(postgres, seed);
        }
        afterFirstPass = snapshot();
        LiveContentFixtures.applyDirectAskSeed(postgres);
        directAskFirst = snapshot(DIRECT_ASK_CODES);
        for (String seed : List.of("001-reference.sql", "002-u2-reference.sql", "003-u4a-reference.sql", "004-u8-reference.sql",
                "006-practice-direct-ask-reference.sql")) {
            LiveContentFixtures.applySeed(postgres, seed);
        }
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
    void withoutSeed002The004SeedStillCreatesAllFiveSpeechCodes() {
        assertThat(beforeU2).as("002 없이 004만 적용한 DB").hasSize(5)
                .allMatch(row -> row.contains("|APPROVED_SPEECH|") && row.endsWith("|APPROVED|null"));
    }

    @Test
    void applyingAgainLeavesTheSameRows() {
        assertThat(snapshot()).isEqualTo(afterFirstPass);
    }

    @Test
    void helpOfferBecomesApprovedSpeechV2AndTheDraftIsRetired() {
        List<Map<String, Object>> versions = db.jdbc().queryForList("""
                select c.content_type, v.version, v.status, v.audio_path, v.content_text from core.core_approved_content_version v
                  join core.core_approved_content c on c.id = v.content_id
                 where c.content_code = 'HELP_OFFER' order by v.version
                """, new MapSqlParameterSource());

        assertThat(versions).hasSize(2);
        assertThat(versions.get(0)).containsEntry("content_type", "APPROVED_SPEECH").containsEntry("status", "RETIRED");
        assertThat(versions.get(1)).containsEntry("version", 2).containsEntry("status", "APPROVED")
                .containsEntry("content_text", SeedIds.HELP_OFFER_TEXT);
        assertThat(versions.get(1).get("audio_path")).isNull();
    }

    @Test
    void sqlSeedSeedIdsAndTheDevSeedUseTheSameIdsAndTexts() {
        for (SpeechRow row : LiveContentFixtures.SPEECH_ROWS) {
            MapSqlParameterSource params = new MapSqlParameterSource("code", row.code());
            assertThat(db.jdbc().queryForObject("select id from core.core_approved_content where content_code = :code", params,
                    UUID.class)).as(row.code() + " 종류 행").isEqualTo(row.contentId());
            Map<String, Object> version = db.jdbc().queryForMap("""
                    select v.id, v.version, v.content_text from core.core_approved_content_version v
                      join core.core_approved_content c on c.id = v.content_id
                     where c.content_code = :code and v.status = 'APPROVED'
                    """, params);
            assertThat(version).as(row.code()).containsEntry("id", row.versionId()).containsEntry("version", row.version())
                    .containsEntry("content_text", row.text());
            assertThat(row.text().codePointCount(0, row.text().length())).as("계약 approvedSpeech.text 200자 이하").isBetween(1, 200);
        }
        CoreBusinessContext.runner(postgres, new MutableClock(SeedIds.U8_SEED_TIME), java.net.URI.create("http://127.0.0.1:9"),
                TestSecrets.internalServiceToken())
                .withPropertyValues("spring.profiles.active=test,dev", "mujoong.dev-login.enabled=true")
                .withUserConfiguration(DevSeedRunner.class)
                .run(context -> {
                    List<String> before = snapshot();
                    List<String> directAskBefore = snapshot(DIRECT_ASK_CODES);
                    context.getBean(DevSeedRunner.class).seed();
                    assertThat(snapshot()).as("SQL 시드 뒤 dev 시드는 승인 발화 행을 바꾸지 않는다").isEqualTo(before);
                    assertThat(snapshot(DIRECT_ASK_CODES)).as("006 뒤 dev 시드도 C안 행을 바꾸지 않는다").isEqualTo(directAskBefore);
                });
    }

    @Test
    void theDirectAskSeedAddsSixVoicelessApprovedCodesWithTheSeedIdsAndAppliesTwiceAlike() {
        assertThat(directAskFirst).hasSize(6).allMatch(row -> row.contains("|APPROVED_SPEECH|") && row.endsWith("|APPROVED|null"));
        assertThat(snapshot(DIRECT_ASK_CODES)).as("두 번째 적용 뒤에도 같은 행").isEqualTo(directAskFirst);
        for (SpeechRow row : LiveContentFixtures.DIRECT_ASK_ROWS) {
            MapSqlParameterSource params = new MapSqlParameterSource("code", row.code());
            assertThat(db.jdbc().queryForObject("select id from core.core_approved_content where content_code = :code", params,
                    UUID.class)).as(row.code() + " 종류 행").isEqualTo(row.contentId());
            Map<String, Object> version = db.jdbc().queryForMap("""
                    select v.id, v.version, v.content_text, v.created_at from core.core_approved_content_version v
                      join core.core_approved_content c on c.id = v.content_id
                     where c.content_code = :code and v.status = 'APPROVED'
                    """, params);
            assertThat(version).as(row.code()).containsEntry("id", row.versionId()).containsEntry("version", row.version())
                    .containsEntry("content_text", row.text());
            assertThat(version.get("created_at")).isInstanceOf(Timestamp.class);
            assertThat(((Timestamp) version.get("created_at")).toInstant()).as(row.code() + " 고정 시각")
                    .isEqualTo(SeedIds.DIRECT_ASK_SEED_TIME);
            assertThat(row.text().codePointCount(0, row.text().length())).as("계약 approvedSpeech.text 200자 이하").isBetween(1, 200);
        }
        assertThat(DIRECT_ASK_CODES).doesNotContainAnyElementsOf(SPEECH_CODES);
    }

    @Test
    void theDirectAskSeedStopsInsteadOfSkippingWhenAnotherRowHoldsTheSameCodeAndVersion() throws SQLException, IOException {
        try (Connection owner = postgres.connect(Role.OWNER); Statement statement = owner.createStatement()) {
            owner.setAutoCommit(false);
            try {
                statement.execute("delete from core.core_approved_content_version where id = '"
                        + SeedIds.VERSION_WAIT_LEAD + "'");
                statement.execute("insert into core.core_approved_content_version (id, content_id, version, content_text,"
                        + " audio_path, status, created_at) values ('" + UUID.randomUUID() + "', '" + SeedIds.CONTENT_WAIT_LEAD
                        + "', 1, '다른 문장.', null, 'APPROVED', timestamptz '2026-10-24 00:00:00+00')");
                String seed = Files.readString(postgres.repoRoot().resolve("infra/seed/006-practice-direct-ask-reference.sql"),
                        StandardCharsets.UTF_8);
                assertThatThrownBy(() -> statement.execute(seed)).isInstanceOf(SQLException.class)
                        .hasMessageContaining("연습 C안 승인 발화 6종 등록이 다른 행과 충돌했다");
            } finally {
                owner.rollback();
            }
        }
        assertThat(snapshot(DIRECT_ASK_CODES)).as("되돌린 뒤 그대로").isEqualTo(directAskFirst);
    }

    @Test
    void speechPhrasesPassTheSeedForbiddenExpressionDictionary() throws IOException {
        List<String> patterns = new ArrayList<>();
        JsonNode dictionary = JsonMapper.builder().build().readTree(Files.readString(postgres.repoRoot()
                .resolve("backend/realtime-activity/src/main/resources/gate/seed-dictionary.json"), StandardCharsets.UTF_8));
        dictionary.path("entries").forEach(entry -> patterns.add(entry.path("pattern").asString()));

        assertThat(patterns).isNotEmpty();
        List<SpeechRow> rows = new ArrayList<>(LiveContentFixtures.SPEECH_ROWS);
        rows.addAll(LiveContentFixtures.DIRECT_ASK_ROWS);
        for (SpeechRow row : rows) {
            String normalized = SeedActivitySessionReferenceSqlIT.normalize(row.text());
            assertThat(patterns).as(row.code() + " NFR2 시드 금지 표현").noneMatch(normalized::contains);
        }
    }

    @Test
    void aStartedFreeTalkCarriesTheTwoFreeTalkSpeechCodesApartFromApprovedContent() {
        String token = TestSecrets.internalServiceToken();
        try (var realtime = WebFixtures.realtime(token);
                CoreWebApp app = CoreWebApp.builder(postgres).internalToken(token).realtime(realtime.baseUrl()).start()) {
            BusinessFixtures fixtures = new BusinessFixtures(db);
            Family family = fixtures.family(app.clock().now().minusSeconds(60));
            fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, app.clock().now(), app.clock().now());
            WebFixtures.Guardian guardian = WebFixtures.guardianFor(app, family.guardianId());

            Result started = app.post("/api/activities/free").session(guardian.session()).csrf(guardian.csrf())
                    .json("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}").send();

            assertThat(started.status()).isEqualTo(202);
            var sent = realtime.requests("POST", WebFixtures.RT_START);
            assertThat(sent).hasSize(1);
            assertThat(sent.get(0).schemaErrors()).isEmpty();
            JsonNode body = sent.get(0).json();
            List<String> speech = new ArrayList<>();
            body.path("approvedSpeech").propertyNames().forEach(speech::add);
            List<String> content = new ArrayList<>();
            body.path("approvedContent").propertyNames().forEach(content::add);
            assertThat(speech).containsExactly("D_REST_ACK_START", "D_REST_ACK");
            assertThat(content).doesNotContainAnyElementsOf(speech).doesNotContain("HELP_OFFER");
            assertThat(body.path("approvedSpeech").path("D_REST_ACK").path("contentVersionId").asString())
                    .isEqualTo(SeedIds.VERSION_D_REST_ACK.toString());
            assertThat(SPEECH_CODES).containsAll(speech);
        } finally {
            db.cleanUp();
        }
    }

    // ------------------------------------------------------------------ 도움

    /** U8 code의 종류 · 판 줄(정렬): code|종류|판|상태|음성. */
    private static List<String> snapshot() {
        return snapshot(SPEECH_CODES);
    }

    /** 주어진 code의 종류 · 판 줄(정렬): code|종류|판|상태|음성. */
    private static List<String> snapshot(List<String> codes) {
        return db.jdbc().queryForList("""
                select c.content_code || '|' || c.id || '|' || c.content_type || '|' || v.id || '|' || v.version || '|'
                       || md5(v.content_text) || '|' || v.status || '|' || coalesce(v.audio_path, 'null')
                  from core.core_approved_content c join core.core_approved_content_version v on v.content_id = c.id
                 where c.content_code in (:codes)
                 order by c.content_code, v.version
                """, new MapSqlParameterSource("codes", codes), String.class);
    }
}
