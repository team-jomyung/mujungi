package kr.mujoong.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.support.CoreBusinessContext;
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
 * 생기고, 두 번 돌려도 보호자 1 · 아이 1 · 동의 4 · 정책 1 · 승인 문구 2 · 종료 표현 0이다.
 */
@Tag("u1")
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
