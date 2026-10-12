package kr.mujoong.core.consent;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.identity.SeedIds;
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
 * dev 약관 본문 시드 (U2 · R7 · {@code infra/seed/002-u2-reference.sql}의 약관 4종과 같은 고정 UUID · 글).
 *
 * <p>U1 dev 시드(DevSeedRunner)와 같은 조건(dev 프로필 AND {@code mujoong.dev-login.enabled=true})에서만 빈이 생긴다. demo · prod는 참조 시드
 * SQL로 넣는다. {@code ON CONFLICT DO NOTHING}이라 SQL 시드와 함께 돌아도 결과가 같다(U2 시험이 두 경로의 행이 같음을 본다).
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "mujoong.dev-login.enabled", havingValue = "true")
public class PolicyTermsDevSeed implements ApplicationRunner {

    /** 약관 한 종류의 시드 값(002 SQL과 같다). */
    public record TermsSeed(UUID contentId, String code, UUID versionId, String text) {
    }

    /** 생성형 AI 안내 절(mockups F2). */
    static final String AI_NOTICE = "{\"heading\":\"생성형 AI 안내\",\"body\":\"무중은 생성형 AI가 아이와 대화하는 서비스예요. "
            + "아이가 듣는 말은 사람이 아니라 AI가 만들어요.\"}";

    /** 약관 4종(문구표 C.S-02-1.35 · 41 · 42 · 43 + F2). */
    public static final List<TermsSeed> TERMS = List.of(
            new TermsSeed(UUID.fromString("00000000-0000-4000-8000-0000000000a1"), "POLICY_TERMS_VOICE_AND_TEXT",
                    UUID.fromString("00000000-0000-4000-8000-0000000000b1"),
                    "{\"title\":\"음성 수집 · 글로 옮겨 저장\",\"sections\":[{\"heading\":\"수집하는 것\",\"body\":\"아이가 기기에서 말한 음성\"},"
                            + "{\"heading\":\"이용 목적\",\"body\":\"대화를 진행하고, 음성을 글로 옮겨 기록으로 저장\"},"
                            + "{\"heading\":\"보관\",\"body\":\"글 기록과 음성 원본 모두 계정을 삭제할 때까지\"}," + AI_NOTICE + ","
                            + "{\"heading\":\"동의하지 않으면\",\"body\":\"자유대화 · 연습을 시작할 수 없어요\"}]}"),
            new TermsSeed(UUID.fromString("00000000-0000-4000-8000-0000000000a2"), "POLICY_TERMS_GUARDIAN_VIEW",
                    UUID.fromString("00000000-0000-4000-8000-0000000000b2"),
                    "{\"title\":\"보호자 열람\",\"sections\":[{\"heading\":\"보는 사람\",\"body\":\"이 계정으로 로그인한 보호자\"},"
                            + "{\"heading\":\"볼 수 있는 것\",\"body\":\"활동 기록 · 대화 글 · 연습 결과 · 생활 팁\"},"
                            + "{\"heading\":\"이용 목적\",\"body\":\"아이의 대화와 연습을 보호자가 확인\"}," + AI_NOTICE + ","
                            + "{\"heading\":\"동의하지 않으면\",\"body\":\"자유대화 · 연습을 시작할 수 없어요\"}]}"),
            new TermsSeed(UUID.fromString("00000000-0000-4000-8000-0000000000a3"), "POLICY_TERMS_REPORT_SHARING",
                    UUID.fromString("00000000-0000-4000-8000-0000000000b3"),
                    "{\"title\":\"리포트 생성 · 외부 전달\",\"sections\":[{\"heading\":\"만드는 것\",\"body\":\"고른 기간의 기록으로 상담용 리포트(PDF)\"},"
                            + "{\"heading\":\"전달\",\"body\":\"보호자가 내용을 확인한 뒤 직접 전달해요. 앱이 자동으로 보내지 않아요\"},"
                            + "{\"heading\":\"보관\",\"body\":\"보호자가 지울 때까지\"}," + AI_NOTICE + ","
                            + "{\"heading\":\"동의하지 않으면\",\"body\":\"서비스를 시작할 수 없어요\"}]}"),
            new TermsSeed(UUID.fromString("00000000-0000-4000-8000-0000000000a4"), "POLICY_TERMS_RAW_AUDIO",
                    UUID.fromString("00000000-0000-4000-8000-0000000000b4"),
                    "{\"title\":\"원음 보관\",\"sections\":[{\"heading\":\"보관하는 것\",\"body\":\"아이 음성 원본\"},"
                            + "{\"heading\":\"이용 목적\",\"body\":\"글로 옮긴 내용을 다시 확인할 때\"},"
                            + "{\"heading\":\"보관\",\"body\":\"계정을 삭제할 때까지\"}," + AI_NOTICE + ","
                            + "{\"heading\":\"동의하지 않으면\",\"body\":\"서비스를 시작할 수 없어요\"}]}"));

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PolicyTermsDevSeed(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
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
        Integer inserted = transactions.execute(status -> {
            int rows = 0;
            for (TermsSeed terms : TERMS) {
                MapSqlParameterSource params = ClockParams.at(SeedIds.SEED_TIME);
                ClockParams.uuid(params, "contentId", terms.contentId());
                ClockParams.uuid(params, "versionId", terms.versionId());
                ClockParams.text(params, "code", terms.code());
                ClockParams.text(params, "text", terms.text());
                rows += jdbc.update("""
                        insert into core.core_approved_content (id, content_code, content_type, created_at)
                        values (:contentId, :code, 'POLICY_TERMS', :now)
                        on conflict do nothing
                        """, params);
                rows += jdbc.update("""
                        insert into core.core_approved_content_version (id, content_id, version, content_text, audio_path,
                                                                        status, created_at)
                        select cast(:versionId as uuid), content.id, 1, :text, null, 'APPROVED', :now
                          from core.core_approved_content content
                         where content.content_code = :code
                        on conflict do nothing
                        """, params);
            }
            return rows;
        });
        return inserted == null ? 0 : inserted;
    }
}
