package kr.mujoong.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 10 엔터티 단위 시험(DB 없음): Guardian · Child · Consent의 생성 규칙과 값 동일성. DB 대조와 왕복은 {@code CoreSchemaIT}.
 * 근거: entities.md Guardian · Child · Consent · BR6.1 ①(동의 유효) · BR8.7(문자열 표현에 개인정보 없음).
 */
@Tag("skeleton")
class IdentityEntitiesTest {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");

    @Test
    void newGuardianIsActiveAndComparedById() {
        UUID id = UUID.randomUUID();
        Guardian guardian = new Guardian(id, T0);
        assertThat(guardian.getId()).isEqualTo(id);
        assertThat(guardian.getStatus()).isEqualTo(Guardian.STATUS_ACTIVE);
        assertThat(guardian.getCreatedAt()).isEqualTo(T0);
        assertThat(guardian.getUpdatedAt()).isEqualTo(T0);
        assertThat(guardian.isDeletionRequested()).isFalse();
        assertThat(guardian).isEqualTo(new Guardian(id, T0.plusSeconds(1))).hasSameHashCodeAs(new Guardian(id, T0));
        assertThat(guardian).isNotEqualTo(new Guardian(UUID.randomUUID(), T0)).isNotEqualTo("guardian");
        assertThat(new Guardian()).isNotEqualTo(new Guardian());
        assertThat(guardian.toString()).contains(id.toString());
    }

    @Test
    void guardianAndChildRequireIdsAndClockTime() {
        assertThatThrownBy(() -> new Guardian(null, T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Guardian(UUID.randomUUID(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Child(UUID.randomUUID(), null, "하늘", 8, null, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Child(UUID.randomUUID(), UUID.randomUUID(), null, 8, null, T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void childKeepsContextFieldsButNeverPrintsThem() {
        UUID id = UUID.randomUUID();
        UUID guardianId = UUID.randomUUID();
        Child child = new Child(id, guardianId, "하늘", 8, "[\"공룡\",\"그림\"]", T0);
        assertThat(child.getId()).isEqualTo(id);
        assertThat(child.getGuardianId()).isEqualTo(guardianId);
        assertThat(child.getDisplayName()).isEqualTo("하늘");
        assertThat(child.getAge()).isEqualTo(8);
        assertThat(child.getInterestsJson()).isEqualTo("[\"공룡\",\"그림\"]");
        assertThat(child.getStatus()).isEqualTo(Child.STATUS_ACTIVE);
        assertThat(child.getCreatedAt()).isEqualTo(T0);
        assertThat(child.getUpdatedAt()).isEqualTo(T0);
        assertThat(child.getDeletedAt()).isNull();
        // BR8.7: 이름 · 관심사는 로그로 새지 않게 문자열 표현에 없다
        assertThat(child.toString()).doesNotContain("하늘").doesNotContain("공룡");
        assertThat(child).isEqualTo(new Child(id, guardianId, "다른", null, null, T0)).hasSameHashCodeAs(child);
        assertThat(child).isNotEqualTo(new Child(UUID.randomUUID(), guardianId, "하늘", 8, null, T0));
        // 나이 · 관심사는 선택(검증은 U2)
        assertThat(new Child(UUID.randomUUID(), guardianId, "하늘", null, null, T0).getAge()).isNull();
    }

    @Test
    void consentIsGivenOnlyWithinItsEffectiveWindow() {
        Consent consent = new Consent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Consent.TYPE_VOICE_AND_TEXT,
                Consent.STATUS_GIVEN, 1, "1.0", T0);
        assertThat(consent.isGivenAt(T0)).isTrue();
        assertThat(consent.isGivenAt(T0.plusSeconds(86_400))).isTrue();
        assertThat(consent.isGivenAt(T0.minusMillis(1))).as("효력 시작 전").isFalse();
        assertThat(consent.getEffectiveFrom()).isEqualTo(T0);
        assertThat(consent.getEffectiveTo()).isNull();
        assertThat(consent.getCreatedAt()).isEqualTo(T0);
        assertThat(consent.getConsentType()).isEqualTo("VOICE_AND_TEXT");
        assertThat(consent.getConsentStatus()).isEqualTo("GIVEN");
        assertThat(consent.getConsentVersion()).isEqualTo(1);
        assertThat(consent.getPolicyVersion()).isEqualTo("1.0");
        assertThat(consent.getActorGuardianId()).isNotNull();
        assertThat(consent.getChildId()).isNotNull();
        assertThat(consent).isEqualTo(consent).isNotEqualTo(null);
        assertThat(consent.hashCode()).isEqualTo(Consent.class.hashCode());
        assertThat(consent.toString()).contains("VOICE_AND_TEXT");

        Consent withdrawn = new Consent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Consent.TYPE_RAW_AUDIO,
                Consent.STATUS_WITHDRAWN, 2, "1.0", T0);
        assertThat(withdrawn.isGivenAt(T0.plusSeconds(1))).isFalse();
        assertThatThrownBy(() -> consent.isGivenAt(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void consentRejectsUnknownTypesAndStatuses() {
        UUID id = UUID.randomUUID();
        assertThat(Consent.TYPES).containsExactlyInAnyOrder("VOICE_AND_TEXT", "GUARDIAN_VIEW", "REPORT_SHARING", "RAW_AUDIO");
        assertThatThrownBy(() -> new Consent(id, id, id, "MARKETING", Consent.STATUS_GIVEN, 1, "1.0", T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Consent(id, id, id, Consent.TYPE_GUARDIAN_VIEW, "MAYBE", 1, "1.0", T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Consent(id, id, id, null, Consent.STATUS_GIVEN, 1, "1.0", T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Consent(id, id, id, Consent.TYPE_REPORT_SHARING, Consent.STATUS_GIVEN, 1, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
