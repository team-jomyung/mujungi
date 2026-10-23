package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import kr.mujoong.realtime.session.NoticeAssetCatalog.Verification;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("service-guardrail")
class NoticeAssetServiceGuardrailTest {
    private static final Duration LIMIT = Duration.ofSeconds(20);
    @Test void explicitlyInjectedVerifiedSyntheticAssetMatchesCurrentTextAndAudio() {
        assertThat(ServiceGuardrailFixtures.catalog().verified(ServiceGuardrailFixtures.item(), ServiceGuardrailFixtures.audio(),
                "service-0.1", LIMIT)).isPresent();
    }
    @Test void operationalManifestHasNoSpokenApprovalAndFailsClosed() {
        assertThat(new NoticeAssetCatalog().verified(ServiceGuardrailFixtures.item(), ServiceGuardrailFixtures.audio(),
                "service-0.1", LIMIT)).isEmpty();
    }
    @Test void placeholderDoesNotBecomeSpeechBecauseItsFileExists() {
        var catalog = new NoticeAssetCatalog(List.of(ServiceGuardrailFixtures.asset(Verification.PLACEHOLDER)),
                path -> ServiceGuardrailFixtures.wav(ServiceGuardrailFixtures.FRAMES));
        assertThat(catalog.verified(ServiceGuardrailFixtures.item(), ServiceGuardrailFixtures.audio(), "service-0.1", LIMIT)).isEmpty();
    }
    @Test void badAudioHashIsRejectedDuringLoad() {
        assertThatThrownBy(() -> new NoticeAssetCatalog(List.of(ServiceGuardrailFixtures.asset(Verification.SPOKEN_VERIFIED)),
                path -> new byte[44])).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void changedTextAndPolicyVersionAreRejected() {
        var catalog = ServiceGuardrailFixtures.catalog();
        var wrong = new SessionExecutor.ApprovedItem(ServiceGuardrailFixtures.CODE, ServiceGuardrailFixtures.INTRO, "다른 안내");
        assertThat(catalog.verified(wrong, ServiceGuardrailFixtures.audio(), "service-0.1", LIMIT)).isEmpty();
        assertThat(catalog.verified(ServiceGuardrailFixtures.item(), ServiceGuardrailFixtures.audio(), "old-policy", LIMIT)).isEmpty();
    }
    @Test void mismatchedCurrentContentVersionAndMissingAudioAreRejected() {
        var wrong = new SessionExecutor.ApprovedItem(ServiceGuardrailFixtures.CODE, UUID.randomUUID(), ServiceGuardrailFixtures.TEXT);
        assertThat(ServiceGuardrailFixtures.catalog().verified(wrong, ServiceGuardrailFixtures.audio(), "service-0.1", LIMIT)).isEmpty();
        assertThat(ServiceGuardrailFixtures.catalog().verified(ServiceGuardrailFixtures.item(), List.of(), "service-0.1", LIMIT)).isEmpty();
    }
    @Test void audioMustFitExistingServerPlaybackBudget() {
        assertThat(ServiceGuardrailFixtures.catalog().verified(ServiceGuardrailFixtures.item(), ServiceGuardrailFixtures.audio(),
                "service-0.1", Duration.ofMillis(99))).isEmpty();
        assertThat(ServiceGuardrailFixtures.catalog().verified(ServiceGuardrailFixtures.item(), ServiceGuardrailFixtures.audio(),
                "service-0.1", Duration.ofMillis(100))).isPresent();
    }
    @Test void malformedWavAndDuplicateVersionCannotBeCertified() {
        var original = ServiceGuardrailFixtures.asset(Verification.SPOKEN_VERIFIED);
        assertThatThrownBy(() -> new NoticeAssetCatalog(List.of(original, original),
                path -> ServiceGuardrailFixtures.wav(ServiceGuardrailFixtures.FRAMES))).isInstanceOf(IllegalArgumentException.class);
        byte[] bad = {1, 2, 3};
        var metadata = new NoticeAssetCatalog.Asset(original.contentVersionId(), original.contentCode(), original.audioUrl(),
                original.resource(), original.textSha256(), NoticeAssetCatalog.sha256(bad), original.frames(),
                Verification.SPOKEN_VERIFIED, original.reviewVersion(), original.policyVersion(), true);
        assertThat(new NoticeAssetCatalog(List.of(metadata), path -> bad).verified(ServiceGuardrailFixtures.item(),
                ServiceGuardrailFixtures.audio(), "service-0.1", LIMIT)).isEmpty();
    }
}
