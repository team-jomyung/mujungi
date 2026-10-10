package kr.mujoong.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import kr.mujoong.core.common.db.CoreDataAccessContext;
import kr.mujoong.testsupport.db.MujoongPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 승인 문구 JPA 저장소 통합 시험(U1 3.5 Step 17 · BR6.5 · J114 · PD-8 SQ-19).
 *
 * <p>증명: 코드의 APPROVED 최고 버전만 고른다(DRAFT · RETIRED · 낮은 버전 제외), 모르는 코드 · 빈 코드는 없음, 현재 정책 버전은
 * CONSENT_POLICY의 APPROVED 최고 버전 content_text다. 공용 DB에 시드 행이 있어도 되돌리는 트랜잭션 안에서만 바꾼다.
 */
@Tag("u1")
@Tag("db")
class ApprovedContentRepositoryIT {

    private static final Instant T0 = Instant.parse("2001-03-01T00:00:00Z");

    private static MujoongPostgres postgres;

    @BeforeAll
    static void startDatabase() {
        postgres = MujoongPostgres.sharedOrAbort();
    }

    @Test
    void latestApprovedVersionIgnoresDraftRetiredAndLowerVersions() {
        CoreDataAccessContext.runner(postgres).run(context -> {
            ApprovedContentRepository repository = context.getBean(ApprovedContentRepository.class);
            context.getBean(TransactionTemplate.class).executeWithoutResult(status -> {
                status.setRollbackOnly();
                String code = "TEST_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                UUID contentId = UUID.randomUUID();
                repository.saveAndFlush(new ApprovedContent(contentId, code, ApprovedContent.TYPE_APPROVED_AUDIO, T0));
                save(context.getBean(JdbcTemplate.class), contentId, 1, "첫 문구", "APPROVED");
                save(context.getBean(JdbcTemplate.class), contentId, 2, "고친 문구", "APPROVED");
                save(context.getBean(JdbcTemplate.class), contentId, 3, "초안", "DRAFT");

                ApprovedContentVersion latest = repository.findLatestApproved(code).orElseThrow();
                assertThat(latest.getVersion()).isEqualTo(2);
                assertThat(latest.getContentText()).isEqualTo("고친 문구");
                assertThat(latest.audioUrl()).contains("/media/approved/test-v2.wav");
                assertThat(repository.findByContentCode(code)).map(ApprovedContent::getId).contains(contentId);
                assertThat(repository.findApprovedVersions(code, Limit.of(5))).extracting(ApprovedContentVersion::getVersion)
                        .as("APPROVED만 높은 버전부터").containsExactly(2, 1);
                assertThat(repository.findLatestApproved("NO_SUCH_CODE")).isEmpty();
                assertThat(repository.findLatestApproved(" ")).isEmpty();
                assertThat(repository.findLatestApproved(null)).isEmpty();
            });
        });
    }

    @Test
    void currentPolicyVersionIsTheLatestApprovedConsentPolicyText() {
        CoreDataAccessContext.runner(postgres).run(context -> {
            ApprovedContentRepository repository = context.getBean(ApprovedContentRepository.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            context.getBean(TransactionTemplate.class).executeWithoutResult(status -> {
                status.setRollbackOnly();
                // 시드가 이미 있으면 이 트랜잭션 안에서만 치우고 시험 값을 넣는다(끝에 되돌린다)
                jdbc.update("delete from core.core_approved_content_version where content_id in"
                        + " (select id from core.core_approved_content where content_code = 'CONSENT_POLICY')");
                jdbc.update("delete from core.core_approved_content where content_code = 'CONSENT_POLICY'");
                assertThat(repository.findCurrentPolicyVersion()).isEmpty();

                UUID contentId = UUID.randomUUID();
                repository.saveAndFlush(new ApprovedContent(contentId, ApprovedContent.CODE_CONSENT_POLICY,
                        ApprovedContent.TYPE_POLICY_VERSION, T0));
                save(jdbc, contentId, 1, "1.0", "APPROVED");
                save(jdbc, contentId, 2, "1.1", "RETIRED");
                assertThat(repository.findCurrentPolicyVersion()).contains("1.0");
                save(jdbc, contentId, 3, "1.2", "APPROVED");
                assertThat(repository.findCurrentPolicyVersion()).contains("1.2");
            });
        });
    }

    private static void save(JdbcTemplate jdbc, UUID contentId, int version, String text, String status) {
        jdbc.update("insert into core.core_approved_content_version (id, content_id, version, content_text, audio_path,"
                + " status, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), contentId, version, text, "test-v" + version + ".wav", status,
                java.time.OffsetDateTime.ofInstant(T0, java.time.ZoneOffset.UTC));
    }
}
