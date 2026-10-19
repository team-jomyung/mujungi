package kr.mujoong.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.support.BusinessFixtures;
import kr.mujoong.core.support.CoreBusinessContext;
import kr.mujoong.core.support.LiveContentFixtures;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.secret.TestSecrets;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * dev 시드 (U1 3.5 Step 28 · BR8.4 · BR8.3 · J114 · J117 · NFR9.11 · AC1.1.7). dev 프로필 + {@code mujoong.dev-login.enabled=true}일 때만 빈이
 * 생기고, 두 번 돌려도 보호자 1 · 아이 1 · 동의 4 · 정책 1 · 승인 문구 2 · 종료 표현 0이다. 3.6 수정 분량: 참조 시드(001-reference.sql)가 먼저
 * 들어가 있으면 가족 6행만 더하고 두 시드의 고정 UUID가 같다(J117), 다시 돌려도 개발자가 손본 시드 가족 행을 덮지 않는다, 스위치는 시험 로그인
 * 관문과 같이 'true'(대소문자 무시)일 때만 켜진다.
 */
@Tag("skeleton")
@Tag("db")
class DevSeedRunnerIT {

    private static MujoongPostgres postgres;

    private DbTestSupport db;

    @BeforeAll
    static void database() {
        postgres = MujoongPostgres.sharedOrAbort();
    }

    @BeforeEach
    void setUp() {
        db = DbTestSupport.coreApp();
        db.trackGuardian(SeedIds.GUARDIAN);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void seedingTwiceLeavesExactlyTheSeedRows() {
        runner().withPropertyValues("spring.profiles.active=test,dev", "mujoong.dev-login.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(DevSeedRunner.class);
            DevSeedRunner seed = context.getBean(DevSeedRunner.class);

            seed.seed();
            assertThat(seed.seed()).isZero();

            MapSqlParameterSource ids = new MapSqlParameterSource("g", SeedIds.GUARDIAN).addValue("c", SeedIds.CHILD);
            assertThat(count("select count(*) from core.core_guardian where id = :g", ids)).isEqualTo(1);
            assertThat(count("select count(*) from core.core_child where guardian_id = :g", ids)).isEqualTo(1);
            assertThat(count("select count(*) from core.core_consent where child_id = :c and consent_status = 'GIVEN'"
                    + " and policy_version = '1.0'", ids)).isEqualTo(4);
            assertThat(count("select count(*) from core.core_stop_expression", ids)).isZero();
            assertThat(count("select count(*) from core.core_approved_content where content_code = 'CONSENT_POLICY'", ids))
                    .isEqualTo(1);
            assertThat(count("select count(*) from core.core_approved_content where content_code in ('CLOSING', 'WAITING_FILLER')",
                    ids)).isEqualTo(2);
            assertThat(db.jdbc().queryForObject("""
                    select display_name || '|' || age || '|' || interests_json::text from core.core_child where id = :c
                    """, ids, String.class)).isEqualTo("하늘|8|[\"공룡\", \"그림\"]");
            assertThat(context.getBean(kr.mujoong.core.catalog.ApprovedContentRepository.class).findCurrentPolicyVersion())
                    .contains("1.0");
        });
    }

    @Test
    void seedRowsUseTheFixedSeedTimeNotTheClock() {
        runner().withPropertyValues("spring.profiles.active=test,dev", "mujoong.dev-login.enabled=true").run(context -> {
            context.getBean(DevSeedRunner.class).seed();

            List<Instant> times = db.jdbc().queryForList(
                    "select created_at from core.core_guardian where id = :g",
                    new MapSqlParameterSource("g", SeedIds.GUARDIAN), java.time.OffsetDateTime.class)
                    .stream().map(java.time.OffsetDateTime::toInstant).toList();
            assertThat(times).containsExactly(SeedIds.SEED_TIME);
        });
    }

    @Test
    void noBeanWithoutDevProfileOrWithoutSwitch() {
        runner().withPropertyValues("spring.profiles.active=test,dev").run(context ->
                assertThat(context).doesNotHaveBean(DevSeedRunner.class));
        runner().withPropertyValues("spring.profiles.active=test", "mujoong.dev-login.enabled=true").run(context ->
                assertThat(context).doesNotHaveBean(DevSeedRunner.class));
        assertThat(count("select count(*) from core.core_guardian where id = :g",
                new MapSqlParameterSource("g", SeedIds.GUARDIAN))).isZero();
    }

    @Test
    void besideTheReferenceSeedOnlyTheFamilyIsAddedAndBothSeedsShareTheFixedIds() {
        BusinessFixtures.applyReferenceSeed(postgres);
        // U8: 참조 시드에 004의 승인 발화 행도 있는 상태로 둔다(공용 DB의 다른 시험 순서와 무관하게 가족 6행만 센다, 넣기 전용 픽스처)
        LiveContentFixtures.insertApprovedSpeech(postgres);
        // 다른 시험 클래스가 남긴 시드 가족이 있어도 빈 상태에서 센다(끝나면 다시 정리)
        db.cleanUp();
        db.trackGuardian(SeedIds.GUARDIAN);
        runner().withPropertyValues("spring.profiles.active=test,dev", "mujoong.dev-login.enabled=true").run(context -> {
            int inserted = context.getBean(DevSeedRunner.class).seed();

            assertThat(inserted).as("보호자 1 · 아이 1 · 동의 4만 새 행(정책 · 승인 문구는 참조 시드가 넣었다)").isEqualTo(6);
            Map<String, UUID> contentIds = Map.of("CLOSING", SeedIds.CONTENT_CLOSING,
                    "WAITING_FILLER", SeedIds.CONTENT_WAITING_FILLER, "CONSENT_POLICY", SeedIds.CONTENT_CONSENT_POLICY);
            Map<String, UUID> versionIds = Map.of("CLOSING", SeedIds.VERSION_CLOSING,
                    "WAITING_FILLER", SeedIds.VERSION_WAITING_FILLER, "CONSENT_POLICY", SeedIds.VERSION_CONSENT_POLICY);
            for (String code : contentIds.keySet()) {
                MapSqlParameterSource params = new MapSqlParameterSource("code", code);
                assertThat(db.jdbc().queryForObject("select id from core.core_approved_content where content_code = :code",
                        params, UUID.class)).as("%s 종류 행 고정 UUID", code).isEqualTo(contentIds.get(code));
                assertThat(db.jdbc().queryForList("""
                        select v.id from core.core_approved_content_version v
                          join core.core_approved_content c on c.id = v.content_id
                         where c.content_code = :code and v.version = 1
                        """, params, UUID.class)).as("%s v1 고정 UUID", code).containsExactly(versionIds.get(code));
            }
        });
    }

    @Test
    void reseedingKeepsChangesDevelopersMadeToTheSeedFamily() {
        runner().withPropertyValues("spring.profiles.active=test,dev", "mujoong.dev-login.enabled=true").run(context -> {
            DevSeedRunner seed = context.getBean(DevSeedRunner.class);
            seed.seed();
            MapSqlParameterSource ids = new MapSqlParameterSource("g", SeedIds.GUARDIAN).addValue("c", SeedIds.CHILD);
            // 개발자가 dev 데이터를 손봤다: 아이 이름 · 나이를 바꾸고 원음 동의를 철회했다(새 버전 행)
            db.jdbc().update("update core.core_child set display_name = '바다', age = 9 where id = :c", ids);
            new BusinessFixtures(db).consent(SeedIds.GUARDIAN, SeedIds.CHILD, "RAW_AUDIO", "WITHDRAWN",
                    SeedIds.SEED_TIME.plusSeconds(60));

            assertThat(seed.seed()).as("재기동 시드는 아무것도 넣거나 고치지 않는다").isZero();

            assertThat(db.jdbc().queryForObject("select display_name || '|' || age from core.core_child where id = :c", ids,
                    String.class)).isEqualTo("바다|9");
            assertThat(count("select count(*) from core.core_consent where child_id = :c", ids)).as("시드 4 + 철회 1").isEqualTo(5);
            assertThat(count("select count(*) from core.core_consent where child_id = :c and consent_type = 'RAW_AUDIO'"
                    + " and consent_status = 'WITHDRAWN' and consent_version = 2", ids)).isEqualTo(1);
        });
    }

    @Test
    void onlyATrueSwitchCreatesTheSeedBeanLikeTheDevLoginGate() {
        for (String value : List.of("false", "yes", "1", "on")) {
            runner().withPropertyValues("spring.profiles.active=test,dev", "mujoong.dev-login.enabled=" + value).run(context -> {
                assertThat(context).as("스위치 값 %s", value).hasNotFailed();
                assertThat(context).as("스위치 값 %s", value).doesNotHaveBean(DevSeedRunner.class);
            });
        }

        // 시험 로그인 요청 관문(DevLoginBlockedRecorder.allowedAtRequest)처럼 대소문자는 가리지 않는다
        runner().withPropertyValues("spring.profiles.active=test,dev", "mujoong.dev-login.enabled=TRUE").run(context ->
                assertThat(context).hasSingleBean(DevSeedRunner.class));
    }

    private ApplicationContextRunner runner() {
        return CoreBusinessContext.runner(postgres, new MutableClock(Instant.parse("2001-08-01T00:00:00Z")),
                URI.create("http://127.0.0.1:9"), TestSecrets.internalServiceToken())
                .withUserConfiguration(DevSeedRunner.class);
    }

    private int count(String sql, MapSqlParameterSource params) {
        Integer value = db.jdbc().queryForObject(sql, params, Integer.class);
        return value == null ? -1 : value;
    }
}
