package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.UUID;
import kr.mujoong.contract.ws.message.PlaybackMark;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("service-guardrail")
class NoticeDeliveryServiceGuardrailTest {
    private final UUID session = UUID.randomUUID(), connection = UUID.randomUUID(), playback = UUID.randomUUID();
    private final Instant start = Instant.parse("2026-10-21T00:00:00Z");
    private final NoticeDelivery delivery = new NoticeDelivery(session, connection, playback, "prompt-v1", "service-0.1", start.plusSeconds(20));
    @Test void currentCompletedEvenWithoutStartedCanCompleteExactlyOnce() {
        assertThat(mark(PlaybackMark.Status.COMPLETED, start)).isTrue();
        assertThat(mark(PlaybackMark.Status.COMPLETED, start)).isFalse();
        assertThat(delivery.state()).isEqualTo(NoticeDelivery.State.COMPLETED);
    }
    @Test void startedAndProgressAreNotCompletion() {
        assertThat(mark(PlaybackMark.Status.STARTED, start)).isFalse();
        assertThat(mark(PlaybackMark.Status.PROGRESS, start)).isFalse();
        assertThat(delivery.permitsInput(start)).isFalse();
    }
    @Test void exactDeadlineExpiresBeforeCompleted() {
        assertThat(mark(PlaybackMark.Status.COMPLETED, start.plusSeconds(20))).isFalse();
        assertThat(delivery.state()).isEqualTo(NoticeDelivery.State.FAILED);
    }
    @Test void differentConnectionCannotCompleteOrChangeState() {
        assertThat(delivery.mark(session, UUID.randomUUID(), playback, PlaybackMark.Status.COMPLETED, start)).isFalse();
        assertThat(delivery.state()).isEqualTo(NoticeDelivery.State.PENDING);
    }
    @Test void differentSessionAndPlaybackCannotComplete() {
        assertThat(delivery.mark(UUID.randomUUID(), connection, playback, PlaybackMark.Status.COMPLETED, start)).isFalse();
        assertThat(delivery.mark(session, connection, UUID.randomUUID(), PlaybackMark.Status.COMPLETED, start)).isFalse();
    }
    @Test void stoppedAndFailedAreNotLaterResurrected() {
        assertThat(mark(PlaybackMark.Status.STOPPED, start)).isFalse();
        assertThat(mark(PlaybackMark.Status.COMPLETED, start)).isFalse();
        assertThat(delivery.state()).isEqualTo(NoticeDelivery.State.INVALIDATED);
    }
    @Test void invalidatedConnectionCannotAcceptLateCompletion() {
        delivery.invalidate();
        assertThat(mark(PlaybackMark.Status.COMPLETED, start)).isFalse();
    }
    @Test void inputBeganBeforeCompletionIsNeverPromotedByLateFinal() {
        mark(PlaybackMark.Status.COMPLETED, start.plusSeconds(1));
        assertThat(delivery.permitsInput(start)).isFalse();
        assertThat(delivery.permitsInput(start.plusSeconds(1))).isTrue();
    }
    private boolean mark(PlaybackMark.Status status, Instant at) { return delivery.mark(session, connection, playback, status, at); }
}
