package kr.mujoong.core.identity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.core.catalog.ApprovedContent;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * dev 시드 (U1 3.5 Step 21 · BR8.3 · BR8.4 · J114 · J117 · SD-14 · NFR9.11 · AC1.1.7).
 *
 * <p>{@code dev} 프로필이고 {@code mujoong.dev-login.enabled=true}일 때만 빈이 생긴다(그 밖의 조합에는 빈이 없다). 기동이 끝나면 한
 * 트랜잭션에 보호자 1 · 아이 1('하늘' · 8세 · 관심 ["공룡","그림"]) · 동의 4행(정책 '1.0' · GIVEN) · 정책 버전 1 · 승인 문구 2(CLOSING ·
 * WAITING_FILLER)를 고정 UUID와 {@code ON CONFLICT DO NOTHING}으로 넣는다. 몇 번 돌아도 결과가 같다. 종료 표현은 넣지 않는다(기본 종료
 * 표현은 코드 상수, BR6.5). 시각은 고정 시드 시각이다(DB 시각 함수 없음, R2).
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "mujoong.dev-login.enabled", havingValue = "true")
public class DevSeedRunner implements ApplicationRunner {

    private static final List<String> CONSENT_TYPES = List.of(
            Consent.TYPE_VOICE_AND_TEXT, Consent.TYPE_GUARDIAN_VIEW, Consent.TYPE_REPORT_SHARING, Consent.TYPE_RAW_AUDIO);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public DevSeedRunner(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /**
     * 시드를 넣는다.
     *
     * @return 이번에 새로 넣은 행 수(두 번째부터 0)
     */
    public int seed() {
        Integer inserted = transactions.execute(status -> insertAll());
        return inserted == null ? 0 : inserted;
    }

    private int insertAll() {
        int rows = 0;
        rows += jdbc.update("""
                insert into core.core_guardian (id, status, created_at, updated_at)
                values (:id, 'ACTIVE', :now, :now)
                on conflict do nothing
                """, seedTime().uuid("id", SeedIds.GUARDIAN).source());
        rows += jdbc.update("""
                insert into core.core_child (id, guardian_id, display_name, age, interests_json, status, created_at, updated_at)
                values (:id, :guardianId, :displayName, :age, cast(:interests as jsonb), :status, :now, :now)
                on conflict do nothing
                """, seedTime()
                .uuid("id", SeedIds.CHILD)
                .uuid("guardianId", SeedIds.GUARDIAN)
                .text("displayName", SeedIds.CHILD_DISPLAY_NAME)
                .integer("age", SeedIds.CHILD_AGE)
                .text("interests", SeedIds.CHILD_INTERESTS_JSON)
                .text("status", Child.STATUS_ACTIVE)
                .source());
        for (int i = 0; i < CONSENT_TYPES.size(); i++) {
            rows += jdbc.update("""
                    insert into core.core_consent (id, actor_guardian_id, child_id, consent_type, consent_status,
                                                   consent_version, policy_version, effective_from, created_at)
                    values (:id, :guardianId, :childId, :type, :status, 1, :policyVersion, :now, :now)
                    on conflict do nothing
                    """, seedTime()
                    .uuid("id", SeedIds.CONSENTS.get(i))
                    .uuid("guardianId", SeedIds.GUARDIAN)
                    .uuid("childId", SeedIds.CHILD)
                    .text("type", CONSENT_TYPES.get(i))
                    .text("status", Consent.STATUS_GIVEN)
                    .text("policyVersion", SeedIds.POLICY_VERSION)
                    .source());
        }
        rows += content(SeedIds.CONTENT_CONSENT_POLICY, ApprovedContent.CODE_CONSENT_POLICY,
                ApprovedContent.TYPE_POLICY_VERSION, SeedIds.VERSION_CONSENT_POLICY, SeedIds.POLICY_VERSION, null);
        rows += content(SeedIds.CONTENT_CLOSING, ApprovedContent.CODE_CLOSING, ApprovedContent.TYPE_APPROVED_AUDIO,
                SeedIds.VERSION_CLOSING, SeedIds.CLOSING_TEXT, SeedIds.CLOSING_AUDIO);
        rows += content(SeedIds.CONTENT_WAITING_FILLER, ApprovedContent.CODE_WAITING_FILLER,
                ApprovedContent.TYPE_APPROVED_AUDIO, SeedIds.VERSION_WAITING_FILLER, SeedIds.WAITING_FILLER_TEXT,
                SeedIds.WAITING_FILLER_AUDIO);
        return rows;
    }

    private static Params seedTime() {
        return new Params(ClockParams.at(SeedIds.SEED_TIME));
    }

    /** 형식 있는 매개변수 묶음(ClockParams 규칙: uuid는 OTHER, 문자열은 VARCHAR, :now는 timestamptz). */
    private record Params(MapSqlParameterSource source) {

        Params uuid(String name, UUID value) {
            ClockParams.uuid(source, name, value);
            return this;
        }

        Params text(String name, String value) {
            ClockParams.text(source, name, value);
            return this;
        }

        Params integer(String name, Integer value) {
            ClockParams.integer(source, name, value);
            return this;
        }
    }

    /** 종류 행과 버전 1 행(버전 행은 content_code로 종류 행에 붙는다 — 001-reference.sql과 같은 규칙). */
    private int content(
            UUID contentId, String code, String type, UUID versionId, String text, String audio) {
        int rows = jdbc.update("""
                insert into core.core_approved_content (id, content_code, content_type, created_at)
                values (:id, :code, :type, :now)
                on conflict do nothing
                """, seedTime().uuid("id", contentId).text("code", code).text("type", type).source());
        rows += jdbc.update("""
                insert into core.core_approved_content_version (id, content_id, version, content_text, audio_path, status, created_at)
                select cast(:versionId as uuid), content.id, 1, :text, :audio, 'APPROVED', :now
                  from core.core_approved_content content
                 where content.content_code = :code
                on conflict do nothing
                """, seedTime().uuid("versionId", versionId).text("text", text).text("audio", audio).text("code", code)
                .source());
        return rows;
    }
}
