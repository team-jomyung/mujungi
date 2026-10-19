package kr.mujoong.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.support.BusinessFixtures;
import kr.mujoong.core.support.BusinessFixtures.Family;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.CoreWebApp.Result;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 참조 시드 003(세션 승인 문구 6종 v2) 시험 (U4a · U4a-J20 · U4b 설계 8절 1 · AC6.3.1 · AC6.3.4 · AC6.3.7 · AC6.3.9 · NFR2 · U2-J17).
 *
 * <p>공용 DB에 승인 문구를 커밋하면 U1 패키지 시험(문구 둘)과 부딪치므로 따로 띄운 DB에 001 → 002 → 003을 두 번씩 적용한다. 확인: 멱등,
 * 승인 카탈로그 규칙(다음 번호 버전 · 음성 파일 이름 · 계약 200자), 앞 DRAFT RETIRED, NFR2 시드 금지 표현 사전 0건, 시작 안내 순서(AC6.3.1),
 * 실제 시작 패키지에 자유대화 문구 다섯이 실리고 연습 문구는 실리지 않음.
 */
@Tag("activity-session")
@Tag("db")
class SeedActivitySessionReferenceSqlIT {

    static final List<String> SESSION_CODES = List.of("INTRO_FREE_TALK", "INTRO_PRACTICE", "RE_PROMPT", "DECLINE_ALTERNATIVE",
            "SILENCE_WARNING", "STOP_CONFIRM");

    private static MujoongPostgres postgres;
    private static DbTestSupport db;

    @BeforeAll
    static void start() {
        postgres = MujoongPostgres.builder().start();
        db = DbTestSupport.coreApp(postgres);
        for (int round = 0; round < 2; round++) {
            for (String seed : List.of("001-reference.sql", "002-u2-reference.sql", "003-u4a-reference.sql")) {
                apply(seed);
            }
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
    void applyingTwiceLeavesOneApprovedV2WithAudioPerCodeAndRetiresTheDraft() {
        for (String code : SESSION_CODES) {
            List<Map<String, Object>> versions = db.jdbc().queryForList("""
                    select v.version, v.status, v.audio_path, v.content_text from core.core_approved_content_version v
                      join core.core_approved_content c on c.id = v.content_id
                     where c.content_code = :code order by v.version
                    """, new MapSqlParameterSource("code", code));
            assertThat(versions).as(code).hasSize(2);
            assertThat(versions.get(0)).as(code + " v1").containsEntry("status", "RETIRED");
            assertThat(versions.get(1)).as(code + " v2").containsEntry("version", 2).containsEntry("status", "APPROVED");
            assertThat((String) versions.get(1).get("audio_path")).matches("^[A-Za-z0-9._-]+\\.wav$");
            assertThat(postgres.repoRoot().resolve("infra/seed-audio").resolve((String) versions.get(1).get("audio_path")))
                    .as("음성 파일이 시드 음성 디렉터리에 있다").isRegularFile();
            String text = (String) versions.get(1).get("content_text");
            assertThat(text.codePointCount(0, text.length())).as("계약 approvedContent.text 200자 이하").isBetween(1, 200);
        }
        assertThat(db.jdbc().queryForObject("""
                select v.status from core.core_approved_content_version v join core.core_approved_content c on c.id = v.content_id
                 where c.content_code = 'HELP_OFFER' and v.version = 1
                """, new MapSqlParameterSource(), String.class)).as("U5 몫은 그대로").isEqualTo("DRAFT");
    }

    @Test
    void phrasesPassTheSeedForbiddenExpressionDictionaryAndTheIntroFollowsAc631() throws IOException {
        List<String> patterns = new ArrayList<>();
        JsonNode dictionary = JsonMapper.builder().build().readTree(Files.readString(postgres.repoRoot()
                .resolve("backend/realtime-activity/src/main/resources/gate/seed-dictionary.json"), StandardCharsets.UTF_8));
        dictionary.path("entries").forEach(entry -> patterns.add(entry.path("pattern").asString()));
        assertThat(patterns).as("사전을 읽었다").isNotEmpty();
        for (String code : SESSION_CODES) {
            String normalized = normalize(text(code));
            assertThat(patterns).as(code + " NFR2 시드 금지 표현").noneMatch(normalized::contains);
        }

        String intro = text("INTRO_FREE_TALK");
        int ai = intro.indexOf("AI");
        int activity = intro.indexOf("자유롭게 이야기하는 시간");
        int record = intro.indexOf("글로 남아서");
        int guardian = intro.indexOf("어른이 볼 수 있어");
        int stop = intro.indexOf("\"무중아 그만할래\"");
        assertThat(intro).as("C.S-12.11.4 인사로 시작해 그 질문으로 묻는다").startsWith("안녕, 무중이야.").endsWith("오늘 나랑 이야기할래?");
        assertThat(List.of(ai, activity, record, guardian, stop)).as("AC6.3.1 순서: AI · 오늘 활동 · 기록 · 보호자 열람 · 그만 표현")
                .doesNotContain(-1).isSorted();
        assertThat(text("INTRO_PRACTICE")).contains("AI").contains("\"무중아 그만할래\"").endsWith("연습해 볼래?");
        assertThat(text("DECLINE_ALTERNATIVE")).as("AC6.3.4 다른 선택지를 한 번 묻고 재촉하지 않는다").contains("?")
                .contains("안 해도 돼");
    }

    @Test
    void aStartedFreeTalkCarriesTheFivePhrasesButNotThePracticeIntro() {
        String token = TestSecrets.internalServiceToken();
        try (ContractStubServer realtime = WebFixtures.realtime(token);
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
            List<String> keys = new ArrayList<>();
            sent.get(0).json().path("approvedContent").propertyNames().forEach(keys::add);
            assertThat(keys).containsExactly("CLOSING", "WAITING_FILLER", "INTRO_FREE_TALK", "RE_PROMPT", "DECLINE_ALTERNATIVE",
                    "SILENCE_WARNING", "STOP_CONFIRM");
            assertThat(sent.get(0).json().path("approvedContent").path("INTRO_FREE_TALK").path("text").asString())
                    .isEqualTo(text("INTRO_FREE_TALK"));
        } finally {
            db.cleanUp();
        }
    }

    // ------------------------------------------------------------------ 도움

    private static String text(String code) {
        return db.jdbc().queryForObject("""
                select v.content_text from core.core_approved_content_version v join core.core_approved_content c on c.id = v.content_id
                 where c.content_code = :code and v.status = 'APPROVED' order by v.version desc limit 1
                """, new MapSqlParameterSource("code", code), String.class);
    }

    /** 사전 판정과 같은 정규화(NFKC → 공백 · 구두점 제거 → 소문자, Realtime SeedDictionary.normalize와 같은 뜻). */
    static String normalize(String text) {
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC);
        return nfkc.replaceAll("[\\p{Z}\\s\\p{P}&&[^%]]", "").replaceAll("[\\u200B-\\u200D\\uFEFF]", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static void apply(String seedFile) {
        try (Connection owner = postgres.connect(Role.OWNER); Statement statement = owner.createStatement()) {
            statement.execute(Files.readString(postgres.repoRoot().resolve("infra/seed/" + seedFile), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException("참조 시드 적용 실패: " + seedFile, e);
        }
    }
}
