package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.VoiceSessionClosed;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * U4b 정산 창 시험 (L5 · FR7.1 · FR7.2 · BR8.1 · BR8.5 ~ BR8.7 · BR4.7).
 *
 * <p>종료 전이 뒤 공급자 닫힘 알림 또는 record-settle까지 정산 창을 열고, 그 안의 늦은 아이 전사는 발화 시작이 ended_at보다 이르면 저장하고 아니면
 * 버리며, 창이 끝나면 정산 입구를 세션당 한 번 부르는지 본다. DECLINED는 인사 순서 뒤에 정산한다.
 */
@Tag("session-runtime")
class SessionSettleTest {

    private final SessionHarness h = new SessionHarness();
    private final RecordingSettlement settlement = new RecordingSettlement();

    @Test
    void anEndedSessionSettlesOnceWhenTheSettleWindowEnds() {
        h.settlement = settlement;
        h.activate();
        endConfirmed();

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.ENDED);
        assertThat(h.executor.isTerminated()).as("정산 창 중").isFalse();
        assertThat(settlement.calls).isEmpty();

        h.advance(SessionHarness.LIFECYCLE.recordSettle());

        assertThat(settlement.calls).singleElement().satisfies(call -> {
            assertThat(call.sessionId()).isEqualTo(h.sessionId);
            assertThat(call.practice()).isNull();
            assertThat(call.cutoffAt()).isEqualTo(h.clock.now());
        });
        assertThat(h.executor.isTerminated()).isTrue();
        assertThat(h.transitions.find(h.sessionId).orElseThrow().providerClosedAt()).isNotNull();
    }

    @Test
    void theProviderCloseSignalEndsTheWindowEarly() {
        h.settlement = settlement;
        h.provider.signalClosedOnClose(true);
        h.activate();
        endConfirmed();

        assertThat(settlement.calls).as("닫힘 알림이 창을 닫는다").hasSize(1);
        assertThat(h.executor.isTerminated()).isTrue();
    }

    @Test
    void aLateTranscriptStartedBeforeTheEndIsKeptAndOneStartedAfterIsDropped() {
        h.settlement = settlement;
        h.activate();
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("early", "공룡"));
        h.drain();
        h.clock.advance(Duration.ofMillis(10));
        endConfirmed();
        Instant ended = h.transitions.find(h.sessionId).orElseThrow().endedAt();
        h.clock.advance(Duration.ofMillis(300));

        h.provider.last().emit(new ProviderEvent.InputTranscriptDone("early", "공룡이 좋아", 0.9));
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("late", "그리고"));
        h.provider.last().emit(new ProviderEvent.InputTranscriptDone("late", "그리고 또", 0.9));
        h.drain();
        h.advance(SessionHarness.LIFECYCLE.recordSettle());

        List<TranscriptEntry> children = h.store.transcripts.stream()
                .filter(entry -> entry.speaker() == TranscriptEntry.Speaker.CHILD).toList();
        assertThat(children).extracting(TranscriptEntry::text).contains("공룡이 좋아").doesNotContain("그리고 또", "그리고");
        assertThat(children.stream().filter(entry -> "공룡이 좋아".equals(entry.text())).findFirst().orElseThrow()
                .startedAt()).isBefore(ended);
        assertThat(settlement.calls).hasSize(1);
    }

    @Test
    void messagesDuringAndAfterTheWindowNeverTransitionAgain() {
        h.settlement = settlement;
        h.activate();
        endConfirmed();
        int applied = h.transitions.applied().size();
        CompletableFuture<SessionMailbox.EndOutcome> stop = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        h.drain();

        assertThat(stop).isCompletedWithValue(SessionMailbox.EndOutcome.NOT_ACTIVE);
        h.advance(SessionHarness.LIFECYCLE.recordSettle());
        assertThat(h.mailbox.putSafety(new SessionMailbox.ServerShutdown())).as("정산 뒤 편지함은 닫힌다").isFalse();
        assertThat(h.transitions.applied()).hasSize(applied);
    }

    @Test
    void aDeclinedSessionSettlesOnlyAfterTheFarewellOrder() {
        h.settlement = settlement;
        h.boot();
        h.ackAndReady();
        h.advanceAlive(Duration.ofSeconds(40));
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.DECLINED);
        PlayApprovedContent farewell = h.link.sent(PlayApprovedContent.class).getLast();
        h.advanceAlive(Duration.ofSeconds(5));
        assertThat(settlement.calls).as("인사 순서가 끝나기 전에는 정산하지 않는다").isEmpty();

        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(farewell.playbackId(), PlaybackMark.Status.COMPLETED, null, h.clock.now(), null, null),
                h.clock.now()));
        h.drain();
        h.advance(SessionHarness.LIFECYCLE.recordSettle());

        assertThat(settlement.calls).hasSize(1);
    }

    @Test
    void aStartPhaseFailureAlsoSettlesAnEmptyRecord() {
        h.settlement = settlement;
        h.boot();
        h.advance(h.settings.deviceAckTimeout());
        h.advance(SessionHarness.LIFECYCLE.recordSettle());

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.FAILED);
        assertThat(settlement.calls).hasSize(1);
    }

    @Test
    void aFailedCaptureIsLoggedAndTheExecutorStillCleansUp() {
        h.settlement = (sessionId, practice, cutoffAt) -> {
            throw new org.springframework.dao.TransientDataAccessResourceException("시험용 정산 실패");
        };
        h.activate();
        endConfirmed();
        h.advance(SessionHarness.LIFECYCLE.recordSettle());

        assertThat(h.executor.isTerminated()).isTrue();
        assertThat(h.logs.lines()).anyMatch(line -> line.contains("APP_ERROR"));
    }

    private void endConfirmed() {
        CompletableFuture<SessionMailbox.EndOutcome> stop = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        h.drain();
        h.mailbox.offerGeneral(new SessionMailbox.VoiceClosed(h.link.connectionId(),
                new VoiceSessionClosed(h.clock.now(), h.clock.now()), h.clock.now()));
        h.drain();
    }

    /** 정산 입구 대역(부른 기록). */
    static final class RecordingSettlement implements SessionSettlement {

        record Call(UUID sessionId, Object practice, Instant cutoffAt) {
        }

        final List<Call> calls = new ArrayList<>();

        @Override
        public synchronized boolean capture(UUID sessionId, Object practice, Instant cutoffAt) {
            calls.add(new Call(sessionId, practice, cutoffAt));
            return true;
        }
    }
}
