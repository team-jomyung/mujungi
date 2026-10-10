package kr.mujoong.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Step 10 엔터티 단위 시험(DB 없음): ApprovedContent · ApprovedContentVersion. DB 대조와 왕복은 {@code CoreSchemaIT}.
 * 근거: entities.md ApprovedContent(Version) · E7(audio_path는 /media/approved/ 아래 파일 이름) · 계약 ApprovedAudioUrl · J114.
 */
@Tag("u1")
class ApprovedContentEntitiesTest {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");

    @Test
    void approvedVersionGivesASameOriginAudioUrl() {
        UUID contentId = UUID.randomUUID();
        ApprovedContentVersion closing = new ApprovedContentVersion(UUID.randomUUID(), contentId, 1,
                "오늘 이야기 즐거웠어. 다음에 또 만나!", "closing-v1.wav", ApprovedContentVersion.STATUS_APPROVED, T0);
        assertThat(closing.audioUrl()).contains("/media/approved/closing-v1.wav");
        assertThat(closing.audioUrl().orElseThrow()).matches("^/media/approved/[A-Za-z0-9._-]+\\.wav$");
        assertThat(closing.isApproved()).isTrue();
        assertThat(closing.getContentId()).isEqualTo(contentId);
        assertThat(closing.getVersion()).isEqualTo(1);
        assertThat(closing.getContentText()).startsWith("오늘");
        assertThat(closing.getAudioPath()).isEqualTo("closing-v1.wav");
        assertThat(closing.getStatus()).isEqualTo("APPROVED");
        assertThat(closing.getCreatedAt()).isEqualTo(T0);
        assertThat(closing.getId()).isNotNull();
        assertThat(closing.toString()).contains("version=1");

        // 정책 버전 행(J114)은 음성이 없다
        ApprovedContentVersion policy = new ApprovedContentVersion(UUID.randomUUID(), UUID.randomUUID(), 3, "1.0", null,
                ApprovedContentVersion.STATUS_RETIRED, T0);
        assertThat(policy.audioUrl()).isEmpty();
        assertThat(policy.isApproved()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../etc/passwd.wav", "closing-v1.mp3", "dir/closing.wav", "closing v1.wav", ".wav2"})
    void audioPathMustBeAPlainWavFileName(String badPath) {
        assertThatThrownBy(() -> new ApprovedContentVersion(UUID.randomUUID(), UUID.randomUUID(), 1, "x", badPath,
                ApprovedContentVersion.STATUS_DRAFT, T0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void versionMustBePositiveAndStatusKnown() {
        UUID contentId = UUID.randomUUID();
        assertThatThrownBy(() -> new ApprovedContentVersion(UUID.randomUUID(), contentId, 0, "x", null,
                ApprovedContentVersion.STATUS_DRAFT, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovedContentVersion(UUID.randomUUID(), contentId, 1, "x", null, "PUBLISHED", T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovedContentVersion(UUID.randomUUID(), contentId, 1, null, null,
                ApprovedContentVersion.STATUS_DRAFT, T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApprovedContentVersion(UUID.randomUUID(), null, 1, "x", null,
                ApprovedContentVersion.STATUS_DRAFT, T0)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void approvedContentRequiresACodeAndTypeAndIsComparedById() {
        UUID id = UUID.randomUUID();
        ApprovedContent policy = new ApprovedContent(id, ApprovedContent.CODE_CONSENT_POLICY,
                ApprovedContent.TYPE_POLICY_VERSION, T0);
        assertThat(policy.getId()).isEqualTo(id);
        assertThat(policy.getContentCode()).isEqualTo("CONSENT_POLICY");
        assertThat(policy.getContentType()).isEqualTo("POLICY_VERSION");
        assertThat(policy.getCreatedAt()).isEqualTo(T0);
        assertThat(policy).isEqualTo(new ApprovedContent(id, ApprovedContent.CODE_CLOSING,
                ApprovedContent.TYPE_APPROVED_AUDIO, T0)).hasSameHashCodeAs(policy);
        assertThat(policy).isNotEqualTo(new ApprovedContent(UUID.randomUUID(), ApprovedContent.CODE_CONSENT_POLICY,
                ApprovedContent.TYPE_POLICY_VERSION, T0));
        assertThat(policy.toString()).contains("CONSENT_POLICY");

        assertThatThrownBy(() -> new ApprovedContent(id, " ", ApprovedContent.TYPE_APPROVED_AUDIO, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovedContent(id, ApprovedContent.CODE_WAITING_FILLER, null, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovedContent(id, ApprovedContent.CODE_WAITING_FILLER,
                ApprovedContent.TYPE_APPROVED_AUDIO, null)).isInstanceOf(NullPointerException.class);

        ApprovedContentVersion version = new ApprovedContentVersion(UUID.randomUUID(), id, 1, "x", null,
                ApprovedContentVersion.STATUS_APPROVED, T0);
        assertThat(version).isEqualTo(version).isNotEqualTo(new ApprovedContentVersion(UUID.randomUUID(), id, 1, "x", null,
                ApprovedContentVersion.STATUS_APPROVED, T0));
        assertThat(version.hashCode()).isEqualTo(ApprovedContentVersion.class.hashCode());
    }
}
