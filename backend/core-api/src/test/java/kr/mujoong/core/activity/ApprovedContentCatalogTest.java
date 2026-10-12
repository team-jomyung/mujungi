package kr.mujoong.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.core.activity.ApprovedContentCatalog.ApprovedItem;
import kr.mujoong.core.activity.ApprovedContentCatalog.PinnedContent;
import kr.mujoong.core.catalog.ApprovedContent;
import kr.mujoong.core.catalog.ApprovedContentRepository;
import kr.mujoong.core.catalog.ApprovedContentVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 승인 문구 카탈로그 한 조회 경로(U2 · 2차 리뷰 6): 세션 패키지 문구(CLOSING · WAITING_FILLER)는 {@code latestApproved}와 같은 버전이고 음성이
 * 있을 때만 나온다. 약관처럼 음성 없는 문구는 {@code latestApproved} · {@code pin}에만 나온다.
 */
@Tag("u2")
class ApprovedContentCatalogTest {

    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

    private final ApprovedContentRepository repository = mock(ApprovedContentRepository.class);
    private final ApprovedContentCatalog catalog = new ApprovedContentCatalog(repository);

    @Test
    void sessionItemsAreTheLatestApprovedVersionWithAudio() {
        ApprovedContentVersion closing = version("마칠게요", "closing-v2.wav", ApprovedContentVersion.STATUS_APPROVED);
        when(repository.findLatestApproved(ApprovedContent.CODE_CLOSING)).thenReturn(Optional.of(closing));

        ApprovedItem item = catalog.closing().orElseThrow();
        PinnedContent pinned = catalog.latestApproved(ApprovedContent.CODE_CLOSING).orElseThrow();

        assertThat(item.contentVersionId()).isEqualTo(pinned.contentVersionId()).isEqualTo(closing.getId());
        assertThat(item.text()).isEqualTo(pinned.text());
        assertThat(item.audioUrl()).isEqualTo(pinned.audioUrl()).isEqualTo("/media/approved/closing-v2.wav");
        assertThat(catalog.pin(List.of(ApprovedContent.CODE_CLOSING))).containsOnlyKeys(ApprovedContent.CODE_CLOSING);
    }

    @Test
    void contentWithoutAudioIsNotASessionItemButCanStillBeRead() {
        ApprovedContentVersion silent = version("기다려 줘", null, ApprovedContentVersion.STATUS_APPROVED);
        when(repository.findLatestApproved(ApprovedContent.CODE_WAITING_FILLER)).thenReturn(Optional.of(silent));

        assertThat(catalog.waitingFiller()).as("음성이 없으면 세션을 시작할 수 없다").isEmpty();
        assertThat(catalog.latestApproved(ApprovedContent.CODE_WAITING_FILLER))
                .hasValueSatisfying(content -> assertThat(content.audioUrl()).isNull());
    }

    @Test
    void aVersionThatIsNotApprovedIsNeverRead() {
        when(repository.findLatestApproved(ApprovedContent.CODE_CLOSING))
                .thenReturn(Optional.of(version("초안", "closing-v3.wav", ApprovedContentVersion.STATUS_DRAFT)));

        assertThat(catalog.closing()).isEmpty();
        assertThat(catalog.latestApproved(ApprovedContent.CODE_CLOSING)).isEmpty();
        assertThat(catalog.pin(List.of(ApprovedContent.CODE_CLOSING))).isEmpty();
    }

    private static ApprovedContentVersion version(String text, String audio, String status) {
        return new ApprovedContentVersion(UUID.randomUUID(), UUID.randomUUID(), 1, text, audio, status, NOW);
    }
}
