package kr.mujoong.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.common.db.SqlErrors;
import kr.mujoong.core.consent.PolicyTermsDevSeed;
import kr.mujoong.core.consent.PolicyTermsDevSeed.TermsSeed;
import kr.mujoong.core.support.ParentAccountFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * U2 V2 스키마 · 참조 시드 002 시험(core_app 역할로). 증명: 아이 CHECK(AC1.3.8) · 종료 표현 유일 · 글자 수(AC2.3.6) · 동의 child_id 비움
 * (U2-J10) · 구글 계정 유일(AC1.6.7) · 보호자당 열린 삭제 요청 하나(AC1.6.7) · 시드 002 멱등과 dev 시드와 같은 약관 글(R7).
 */
@Tag("parent-account")
@Tag("db")
class SchemaIT {

    private static final Instant T0 = DbTestSupport.EPOCH;
    /** PostgreSQL check_violation. */
    private static final String CHECK_VIOLATION = "23514";

    private DbTestSupport db;

    @BeforeEach
    void setUp() {
        db = DbTestSupport.coreApp();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void aGuardianStillHasOnlyOneChildAndTheChildTableIsUnchanged() {
        UUID guardian = db.insertGuardian("ACTIVE");
        assertThat(insertChild(guardian, "하늘", 8, "[\"공룡\"]")).isEqualTo(1);
        expectViolation(SqlErrors.UNIQUE_VIOLATION, () -> insertChild(guardian, "바다", 9, "[]"));
        // 규칙 밖 값(나이 13)은 DB가 막지 않는다 — 앱(ChildInput)이 검증하고 U1 패키지가 거른다(V2 주석)
        assertThat(insertChild(db.insertGuardian("ACTIVE"), "하늘", 13, "[]")).isEqualTo(1);
    }

    @Test
    void stopExpressionsAreUniquePerChildAfterNormalizationAndTwoToTwentyCharacters() {
        UUID guardian = db.insertGuardian("ACTIVE");
        UUID child = db.insertChild(guardian);
        assertThat(insertExpression(child, "이제 그만", "이제그만", 1)).isEqualTo(1);
        expectViolation(SqlErrors.UNIQUE_VIOLATION, () -> insertExpression(child, "이제그만", "이제그만", 2));
        expectViolation(CHECK_VIOLATION, () -> insertExpression(child, "가", "가", 2));
        expectViolation(CHECK_VIOLATION, () -> insertExpression(child, "끝낼래", "끝낼래", 6));
        UUID otherChild = db.insertChild(db.insertGuardian("ACTIVE"));
        assertThat(insertExpression(otherChild, "이제 그만", "이제그만", 1)).as("다른 아이는 같은 표현을 가질 수 있다").isEqualTo(1);
    }

    @Test
    void consentRowsMayHaveNoChildAndReferenceTheTermsVersion() {
        ParentAccountFixtures.applyU2Seed(db.postgres());
        UUID guardian = db.insertGuardian("ACTIVE");
        MapSqlParameterSource params = ClockParams.at(T0);
        ClockParams.uuid(params, "id", UUID.randomUUID());
        ClockParams.uuid(params, "g", guardian);
        ClockParams.uuid(params, "terms", PolicyTermsDevSeed.TERMS.get(0).versionId());
        assertThat(db.jdbc().update("""
                insert into core.core_consent (id, actor_guardian_id, child_id, consent_type, consent_status, consent_version,
                                               policy_version, effective_from, created_at, terms_version_id)
                values (:id, :g, null, 'VOICE_AND_TEXT', 'GIVEN', 1, '1.0', :now, :now, :terms)
                """, params)).isEqualTo(1);
        ClockParams.uuid(params, "id", UUID.randomUUID());
        ClockParams.uuid(params, "terms", UUID.randomUUID());
        expectViolation(SqlErrors.FOREIGN_KEY_VIOLATION, () -> db.jdbc().update("""
                insert into core.core_consent (id, actor_guardian_id, child_id, consent_type, consent_status, consent_version,
                                               policy_version, effective_from, created_at, terms_version_id)
                values (:id, :g, null, 'VOICE_AND_TEXT', 'GIVEN', 1, '1.0', :now, :now, :terms)
                """, params));
    }

    @Test
    void aGoogleAccountBelongsToOneGuardianAndBlocksDeletingTheGuardianFirst() {
        UUID guardian = db.insertGuardian("ACTIVE");
        String subject = "sub-" + UUID.randomUUID();
        assertThat(insertSocial(guardian, subject)).isEqualTo(1);
        expectViolation(SqlErrors.UNIQUE_VIOLATION, () -> insertSocial(db.insertGuardian("ACTIVE"), subject));
        expectViolation(SqlErrors.FOREIGN_KEY_VIOLATION, () -> db.jdbc().update(
                "delete from core.core_guardian where id = :g", new MapSqlParameterSource("g", guardian)));
    }

    @Test
    void aGuardianHasAtMostOneOpenDeletionRequestAndDoneRowsOutliveTheGuardian() {
        UUID guardian = db.insertGuardian("DELETION_REQUESTED");
        UUID first = UUID.randomUUID();
        assertThat(insertRequest(first, guardian, "REQUESTED", null)).isEqualTo(1);
        expectViolation(SqlErrors.UNIQUE_VIOLATION, () -> insertRequest(UUID.randomUUID(), guardian, "REQUESTED", null));
        expectViolation(CHECK_VIOLATION, () -> insertRequest(UUID.randomUUID(), guardian, "DONE", null));
        db.jdbc().update("update core.core_data_deletion_request set status = 'DONE', completed_at = created_at where id = :id",
                new MapSqlParameterSource("id", first));
        assertThat(insertRequest(UUID.randomUUID(), guardian, "REQUESTED", null)).as("완료 뒤 새 요청은 된다").isEqualTo(1);
        expectViolation(CHECK_VIOLATION, () -> insertRequest(UUID.randomUUID(), UUID.randomUUID(), "CANCELLED", null));
        // 논리 참조: 보호자 행이 없어도 요청 행이 있을 수 있다(완료 기록이 남는다)
        UUID orphan = UUID.randomUUID();
        assertThat(insertRequest(UUID.randomUUID(), orphan, "DONE", T0)).isEqualTo(1);
        db.jdbc().update("delete from core.core_data_deletion_request where guardian_id = :g",
                new MapSqlParameterSource("g", orphan));
    }

    @Test
    void referenceSeed002IsIdempotentAndMatchesTheDevSeed() {
        ParentAccountFixtures.applyU2Seed(db.postgres());
        List<String> afterFirst = seeded();
        ParentAccountFixtures.applyU2Seed(db.postgres());

        assertThat(seeded()).isEqualTo(afterFirst);
        assertThat(count("select count(*) from core.core_approved_content c join core.core_approved_content_version v"
                + " on v.content_id = c.id where c.content_code like 'POLICY_TERMS_%' and v.status = 'APPROVED'")).isEqualTo(4);
        assertThat(count("select count(*) from core.core_approved_content c join core.core_approved_content_version v"
                + " on v.content_id = c.id where c.content_code in ('INTRO_FREE_TALK', 'INTRO_PRACTICE', 'RE_PROMPT',"
                + " 'DECLINE_ALTERNATIVE', 'SILENCE_WARNING', 'STOP_CONFIRM', 'HELP_OFFER') and v.status = 'DRAFT'"))
                .as("세션 문구 자리는 사람 승인 전 DRAFT").isEqualTo(7);
        for (TermsSeed seed : PolicyTermsDevSeed.TERMS) {
            String text = db.jdbc().queryForObject("select content_text from core.core_approved_content_version where id = :id",
                    new MapSqlParameterSource("id", seed.versionId()), String.class);
            assertThat(text).as(seed.code() + " — SQL 시드와 dev 시드의 글이 같다").isEqualTo(seed.text());
        }
        PolicyTermsDevSeed devSeed = new PolicyTermsDevSeed(db.jdbc(), new DataSourceTransactionManager(db.dataSource()));
        assertThat(devSeed.seed()).as("SQL 시드 뒤 dev 시드는 같은 행이라 아무것도 넣지 않는다").isZero();
        devSeed.run(null);
        assertThat(seeded()).isEqualTo(afterFirst);
    }

    // ------------------------------------------------------------------ 도움

    private List<String> seeded() {
        return db.jdbc().queryForList("""
                select c.content_code || '|' || v.version || '|' || v.status || '|' || md5(v.content_text)
                  from core.core_approved_content c join core.core_approved_content_version v on v.content_id = c.id
                 where c.id::text like '00000000-0000-4000-8000-0000000000a%'
                 order by c.content_code
                """, new MapSqlParameterSource(), String.class);
    }

    private int insertChild(UUID guardian, String name, int age, String interests) {
        MapSqlParameterSource params = ClockParams.at(T0);
        ClockParams.uuid(params, "id", UUID.randomUUID());
        ClockParams.uuid(params, "g", guardian);
        ClockParams.text(params, "name", name);
        ClockParams.integer(params, "age", age);
        ClockParams.text(params, "interests", interests);
        return db.jdbc().update("""
                insert into core.core_child (id, guardian_id, display_name, age, interests_json, status, created_at, updated_at)
                values (:id, :g, :name, :age, cast(:interests as jsonb), 'ACTIVE', :now, :now)
                """, params);
    }

    private int insertExpression(UUID child, String text, String normalized, int order) {
        MapSqlParameterSource params = ClockParams.at(T0);
        ClockParams.uuid(params, "id", UUID.randomUUID());
        ClockParams.uuid(params, "c", child);
        ClockParams.text(params, "text", text);
        ClockParams.text(params, "n", normalized);
        ClockParams.integer(params, "o", order);
        return db.jdbc().update("""
                insert into core.core_stop_expression (id, child_id, expression_text, normalized_text, sort_order,
                                                       created_at, updated_at)
                values (:id, :c, :text, :n, :o, :now, :now)
                """, params);
    }

    private int insertSocial(UUID guardian, String subject) {
        MapSqlParameterSource params = ClockParams.at(T0);
        ClockParams.uuid(params, "id", UUID.randomUUID());
        ClockParams.uuid(params, "g", guardian);
        ClockParams.text(params, "s", subject);
        return db.jdbc().update("""
                insert into core.core_social_account (id, guardian_id, provider, provider_user_id, created_at, updated_at)
                values (:id, :g, 'GOOGLE', :s, :now, :now)
                """, params);
    }

    private int insertRequest(UUID id, UUID guardian, String status, Instant completedAt) {
        MapSqlParameterSource params = ClockParams.at(T0);
        ClockParams.uuid(params, "id", id);
        ClockParams.uuid(params, "g", guardian);
        ClockParams.text(params, "status", status);
        ClockParams.instant(params, "done", completedAt);
        return db.jdbc().update("""
                insert into core.core_data_deletion_request (id, guardian_id, status, requested_at, blocked_at, completed_at,
                                                             next_attempt_at, created_at, updated_at)
                values (:id, :g, :status, :now, :now, :done, :now, :now, :now)
                """, params);
    }

    private int count(String sql) {
        Integer value = db.jdbc().queryForObject(sql, new MapSqlParameterSource(), Integer.class);
        return value == null ? -1 : value;
    }

    private static void expectViolation(String sqlState, Runnable statement) {
        assertThatThrownBy(statement::run).isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlErrors.sqlState(e)).contains(sqlState));
    }
}
