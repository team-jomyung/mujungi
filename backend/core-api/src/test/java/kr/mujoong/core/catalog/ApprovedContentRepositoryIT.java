package kr.mujoong.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import kr.mujoong.core.common.db.CoreDataAccessContext;
import kr.mujoong.testsupport.db.MujoongPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 승인 문구 JPA 저장소 통합 시험(U1 3.5 Step 17 · BR6.5 · J114 · PD-8 SQ-19).
 *
 * <p>증명: 코드의 APPROVED 최고 버전만 고른다(DRAFT · RETIRED · 낮은 버전 제외), 모르는 코드 · 빈 코드는 없음, 현재 정책 버전은
 * CONSENT_POLICY의 APPROVED 최고 버전 content_text다. 공용 DB에 시드 행이 있어도 되돌리는 트랜잭션 안에서만 바꾼다. 3.6 수정 분량: 다른
 * 코드의 버전이 섞이지 않는다, APPROVED가 하나도 없으면 최고 버전도 없다, 같은 (종류, 버전) · 같은 코드는 DB 유일 키가 막아 '최고 버전'이
 * 하나로 정해진다(SQ-19).
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

    @Test
    void theLatestVersionOfOneCodeNeverComesFromAnotherCode() {
        CoreDataAccessContext.runner(postgres).run(context -> {
            ApprovedContentRepository repository = context.getBean(ApprovedContentRepository.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            context.getBean(TransactionTemplate.class).executeWithoutResult(status -> {
                status.setRollbackOnly();
                String mine = code();
                String other = code();
                UUID mineId = UUID.randomUUID();
                UUID otherId = UUID.randomUUID();
                repository.saveAndFlush(new ApprovedContent(mineId, mine, ApprovedContent.TYPE_APPROVED_AUDIO, T0));
                repository.saveAndFlush(new ApprovedContent(otherId, other, ApprovedContent.TYPE_APPROVED_AUDIO, T0));
                save(jdbc, mineId, 1, "내 문구", "APPROVED");
                for (int version = 1; version <= 3; version++) {
                    save(jdbc, otherId, version, "남의 문구 " + version, "APPROVED");
                }

                ApprovedContentVersion latest = repository.findLatestApproved(mine).orElseThrow();
                assertThat(latest.getContentId()).isEqualTo(mineId);
                assertThat(latest.getVersion()).as("다른 코드의 더 높은 버전을 고르지 않는다").isEqualTo(1);
                assertThat(latest.getContentText()).isEqualTo("내 문구");
                assertThat(repository.findApprovedVersions(mine, Limit.of(10)))
                        .extracting(ApprovedContentVersion::getContentId).containsOnly(mineId);
                assertThat(repository.findLatestApproved(other).map(ApprovedContentVersion::getVersion)).contains(3);
            });
        });
    }

    @Test
    void aCodeWithoutAnyApprovedVersionHasNoLatestUntilOneIsApproved() {
        CoreDataAccessContext.runner(postgres).run(context -> {
            ApprovedContentRepository repository = context.getBean(ApprovedContentRepository.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            context.getBean(TransactionTemplate.class).executeWithoutResult(status -> {
                status.setRollbackOnly();
                String code = code();
                UUID contentId = UUID.randomUUID();
                repository.saveAndFlush(new ApprovedContent(contentId, code, ApprovedContent.TYPE_APPROVED_AUDIO, T0));
                save(jdbc, contentId, 1, "은퇴한 문구", "RETIRED");
                save(jdbc, contentId, 2, "초안", "DRAFT");

                assertThat(repository.findByContentCode(code)).as("종류 행은 있다").isPresent();
                assertThat(repository.findLatestApproved(code)).as("APPROVED가 없으면 쓸 문구도 없다").isEmpty();
                assertThat(repository.findApprovedVersions(code, Limit.of(5))).isEmpty();

                save(jdbc, contentId, 3, "승인된 문구", "APPROVED");
                assertThat(repository.findLatestApproved(code).map(ApprovedContentVersion::getContentText))
                        .contains("승인된 문구");
            });
        });
    }

    @Test
    void duplicateVersionsOrCodesAreRejectedSoTheLatestVersionIsUnambiguous() {
        CoreDataAccessContext.runner(postgres).run(context -> {
            ApprovedContentRepository repository = context.getBean(ApprovedContentRepository.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            TransactionTemplate tx = context.getBean(TransactionTemplate.class);
            String code = code();

            // 같은 종류의 같은 버전 두 행(SQ-19 유일 키) — 예외로 트랜잭션째 되돌린다
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                UUID contentId = UUID.randomUUID();
                repository.saveAndFlush(new ApprovedContent(contentId, code, ApprovedContent.TYPE_APPROVED_AUDIO, T0));
                save(jdbc, contentId, 1, "첫 문구", "APPROVED");
                save(jdbc, contentId, 1, "같은 버전의 다른 문구", "APPROVED");
            })).isInstanceOf(DataIntegrityViolationException.class)
                    .hasStackTraceContaining("core_approved_content_version_uk");

            // 같은 코드의 종류 행 두 개(content_code 유일 키)
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                repository.saveAndFlush(new ApprovedContent(UUID.randomUUID(), code, ApprovedContent.TYPE_APPROVED_AUDIO, T0));
                repository.saveAndFlush(new ApprovedContent(UUID.randomUUID(), code, ApprovedContent.TYPE_APPROVED_AUDIO, T0));
            })).isInstanceOf(DataIntegrityViolationException.class)
                    .hasStackTraceContaining("core_approved_content_code_uk");

            assertThat(repository.findByContentCode(code)).as("실패한 트랜잭션은 아무것도 남기지 않는다").isEmpty();
            assertThat(repository.findLatestApproved(code)).isEmpty();
        });
    }

    private static String code() {
        return "TEST_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static void save(JdbcTemplate jdbc, UUID contentId, int version, String text, String status) {
        jdbc.update("insert into core.core_approved_content_version (id, content_id, version, content_text, audio_path,"
                + " status, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), contentId, version, text, "test-v" + version + ".wav", status,
                java.time.OffsetDateTime.ofInstant(T0, java.time.ZoneOffset.UTC));
    }
}
