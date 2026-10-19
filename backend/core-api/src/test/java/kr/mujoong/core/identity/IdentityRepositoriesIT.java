package kr.mujoong.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import kr.mujoong.core.common.db.CoreDataAccessContext;
import kr.mujoong.testsupport.db.MujoongPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보호자 · 아이 · 동의 JPA 저장소 통합 시험(U1 3.5 Step 17 · BR6.1 ① · J114 · BR6.5).
 *
 * <p>증명: 보호자 · 아이 읽기(보호자당 아이 하나), 현재 정책 버전의 4항목이 모두 GIVEN일 때만 시작 가능(항목 누락 · 철회 · 다른 정책 버전 ·
 * 효력 시작 전은 거절), 항목마다 가장 높은 consent_version이 현재 상태다. 모든 행은 되돌리는 트랜잭션 안에서 만든다. 3.6 수정 분량: 효력
 * 끝 시각은 배타다, consent_version이 같으면 효력 시작이 늦은 행이 현재 상태다, 보호자당 아이 둘째 행은 DB 유일 키가 막는다(findByGuardianId가
 * 하나만 돌려줄 수 있는 근거).
 */
@Tag("skeleton")
@Tag("db")
class IdentityRepositoriesIT {

    private static final Instant T0 = Instant.parse("2001-03-01T00:00:00Z");

    private static MujoongPostgres postgres;

    @BeforeAll
    static void startDatabase() {
        postgres = MujoongPostgres.sharedOrAbort();
    }

    @Test
    void guardianAndChildAreReadBack() {
        inRolledBackTx((context, guardians, children, consents) -> {
            UUID guardianId = UUID.randomUUID();
            UUID childId = UUID.randomUUID();
            guardians.saveAndFlush(new Guardian(guardianId, T0));
            children.saveAndFlush(new Child(childId, guardianId, "하늘", 8, "[\"공룡\",\"그림\"]", T0));

            Guardian guardian = guardians.findById(guardianId).orElseThrow();
            assertThat(guardian.isDeletionRequested()).isFalse();
            Child child = children.findByGuardianId(guardianId).orElseThrow();
            assertThat(child.getId()).isEqualTo(childId);
            assertThat(child.getAge()).isEqualTo(8);
            assertThat(children.findByGuardianId(UUID.randomUUID())).isEmpty();
        });
    }

    @Test
    void allFourConsentsGivenUnderTheCurrentPolicyAllowAStart() {
        inRolledBackTx((context, guardians, children, consents) -> {
            UUID childId = household(guardians, children);
            UUID guardianId = children.findById(childId).orElseThrow().getGuardianId();
            for (String type : Consent.TYPES) {
                consents.save(new Consent(UUID.randomUUID(), guardianId, childId, type, Consent.STATUS_GIVEN, 1, "1.0", T0));
            }
            consents.flush();

            assertThat(consents.hasAllGiven(childId, "1.0", T0)).isTrue();
            assertThat(consents.hasAllGiven(childId, "1.0", T0.minusMillis(1))).as("효력 시작 전").isFalse();
            assertThat(consents.hasAllGiven(childId, "2.0", T0.plusSeconds(1))).as("다른 정책 버전").isFalse();
            assertThat(consents.hasAllGiven(childId, " ", T0)).isFalse();
            assertThat(consents.hasAllGiven(childId, null, T0)).isFalse();
            assertThat(consents.findEffective(childId, "1.0", T0)).hasSize(4);
        });
    }

    @Test
    void aMissingOrWithdrawnItemBlocksTheStart() {
        inRolledBackTx((context, guardians, children, consents) -> {
            UUID childId = household(guardians, children);
            UUID guardianId = children.findById(childId).orElseThrow().getGuardianId();
            for (String type : Consent.TYPES) {
                if (!type.equals(Consent.TYPE_RAW_AUDIO)) {
                    consents.save(new Consent(UUID.randomUUID(), guardianId, childId, type, Consent.STATUS_GIVEN, 1, "1.0",
                            T0));
                }
            }
            consents.flush();
            assertThat(consents.hasAllGiven(childId, "1.0", T0)).as("항목 누락").isFalse();

            consents.save(new Consent(UUID.randomUUID(), guardianId, childId, Consent.TYPE_RAW_AUDIO, Consent.STATUS_GIVEN, 1,
                    "1.0", T0));
            consents.flush();
            assertThat(consents.hasAllGiven(childId, "1.0", T0)).isTrue();

            // 같은 항목의 더 높은 버전이 철회면 그 항목은 철회다
            consents.saveAndFlush(new Consent(UUID.randomUUID(), guardianId, childId, Consent.TYPE_GUARDIAN_VIEW,
                    Consent.STATUS_WITHDRAWN, 2, "1.0", T0.plusSeconds(10)));
            assertThat(consents.hasAllGiven(childId, "1.0", T0.plusSeconds(5))).as("철회 효력 전").isTrue();
            assertThat(consents.hasAllGiven(childId, "1.0", T0.plusSeconds(10))).as("철회 뒤").isFalse();
            assertThatThrownBy(() -> consents.hasAllGiven(null, "1.0", T0)).isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void aConsentStopsCountingExactlyAtItsEffectiveEnd() {
        inRolledBackTx((context, guardians, children, consents) -> {
            UUID childId = household(guardians, children);
            UUID guardianId = children.findById(childId).orElseThrow().getGuardianId();
            for (String type : Consent.TYPES) {
                if (!type.equals(Consent.TYPE_RAW_AUDIO)) {
                    consents.save(new Consent(UUID.randomUUID(), guardianId, childId, type, Consent.STATUS_GIVEN, 1, "1.0", T0));
                }
            }
            consents.flush();
            Instant end = T0.plusSeconds(10);
            // 엔터티에는 효력 끝을 정하는 쓰기 경로가 없어(U2 몫) 같은 트랜잭션에서 행으로 넣는다
            context.getBean(JdbcTemplate.class).update("""
                    insert into core.core_consent (id, actor_guardian_id, child_id, consent_type, consent_status, consent_version,
                                                   policy_version, effective_from, effective_to, created_at)
                    values (?, ?, ?, 'RAW_AUDIO', 'GIVEN', 1, '1.0', ?, ?, ?)
                    """, UUID.randomUUID(), guardianId, childId, utc(T0), utc(end), utc(T0));

            assertThat(consents.hasAllGiven(childId, "1.0", end.minusMillis(1))).as("효력 끝 1ms 전").isTrue();
            assertThat(consents.hasAllGiven(childId, "1.0", end)).as("효력 끝 시각은 이미 효력이 없다").isFalse();
            assertThat(consents.findEffective(childId, "1.0", end)).extracting(Consent::getConsentType)
                    .hasSize(3).doesNotContain(Consent.TYPE_RAW_AUDIO);
        });
    }

    @Test
    void withTheSameConsentVersionTheLaterEffectiveStartIsTheCurrentState() {
        inRolledBackTx((context, guardians, children, consents) -> {
            UUID childId = household(guardians, children);
            UUID guardianId = children.findById(childId).orElseThrow().getGuardianId();
            for (String type : Consent.TYPES) {
                consents.save(new Consent(UUID.randomUUID(), guardianId, childId, type, Consent.STATUS_GIVEN, 1, "1.0", T0));
            }
            // 같은 consent_version 1로 철회 → 다시 동의(버전을 올리지 않은 기록)
            consents.save(new Consent(UUID.randomUUID(), guardianId, childId, Consent.TYPE_VOICE_AND_TEXT,
                    Consent.STATUS_WITHDRAWN, 1, "1.0", T0.plusSeconds(5)));
            consents.saveAndFlush(new Consent(UUID.randomUUID(), guardianId, childId, Consent.TYPE_VOICE_AND_TEXT,
                    Consent.STATUS_GIVEN, 1, "1.0", T0.plusSeconds(8)));

            assertThat(consents.hasAllGiven(childId, "1.0", T0.plusSeconds(4))).as("철회 효력 전").isTrue();
            assertThat(consents.hasAllGiven(childId, "1.0", T0.plusSeconds(5))).as("같은 버전이면 늦게 시작한 철회가 이긴다")
                    .isFalse();
            assertThat(consents.hasAllGiven(childId, "1.0", T0.plusSeconds(7))).isFalse();
            assertThat(consents.hasAllGiven(childId, "1.0", T0.plusSeconds(8))).as("더 늦은 재동의가 이긴다").isTrue();
        });
    }

    @Test
    void aSecondChildForTheSameGuardianIsRejectedByTheDatabase() {
        inRolledBackTx((context, guardians, children, consents) -> {
            UUID childId = household(guardians, children);
            UUID guardianId = children.findById(childId).orElseThrow().getGuardianId();

            assertThatThrownBy(() -> children.saveAndFlush(new Child(UUID.randomUUID(), guardianId, "바다", 7, null, T0)))
                    .as("보호자당 아이 하나(findByGuardianId가 한 행만 돌려줄 근거)")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasStackTraceContaining("core_child_guardian_uk");
        });
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static UUID household(GuardianRepository guardians, ChildRepository children) {
        UUID guardianId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        guardians.saveAndFlush(new Guardian(guardianId, T0));
        children.saveAndFlush(new Child(childId, guardianId, "하늘", 8, null, T0));
        return childId;
    }

    @FunctionalInterface
    private interface Body {
        void run(ApplicationContext context, GuardianRepository guardians, ChildRepository children,
                ConsentRepository consents);
    }

    private static void inRolledBackTx(Body body) {
        CoreDataAccessContext.runner(postgres).run(context -> {
            assertThat(context).hasNotFailed();
            TransactionTemplate tx = context.getBean(TransactionTemplate.class);
            tx.executeWithoutResult(status -> {
                status.setRollbackOnly();
                body.run(context, context.getBean(GuardianRepository.class), context.getBean(ChildRepository.class),
                        context.getBean(ConsentRepository.class));
            });
        });
    }
}
