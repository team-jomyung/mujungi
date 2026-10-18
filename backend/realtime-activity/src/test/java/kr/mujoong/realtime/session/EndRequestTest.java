package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.realtime.session.SessionMailbox.EndOutcome;
import kr.mujoong.realtime.testing.InMemoryTransitions;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * U4b 끝내기 응답표 시험 (L2 · FR1.3 · FR1.4 · BR1.5 · BR1.6 · BR1.7 · BR1.9 · functional-spec WF12).
 *
 * <p>실행기가 없는 행은 {@link EndRequestHandler}가 DB 행으로 답하고(사유 3 × 상태 5 칸), 실행기가 있으면 실행기가 응답표대로 전이 · 기기
 * 명령을 낸다. 같은 세션의 반복 요청은 전이 · 기기 종료 명령 없이 같은 답만 돌려준다.
 */
@Tag("session-runtime")
class EndRequestTest {

    private final SessionHarness h = new SessionHarness();

    // ------------------------------------------------------------------ 실행기 없는 행(DB로 답함)

    static Stream<Arguments> tableWithoutExecutor() {
        return Stream.of(
                // GUARDIAN_STOP: 실행기 없는 행은 CLOSING만 202 {CLOSING}, 나머지 409(U1 (w) 그대로)
                Arguments.of(EndRequestReason.GUARDIAN_STOP, SessionStatus.PREPARING, EndOutcome.NOT_ACTIVE,
                        SessionStatus.PREPARING),
                Arguments.of(EndRequestReason.GUARDIAN_STOP, SessionStatus.CLOSING, EndOutcome.CLOSING,
                        SessionStatus.CLOSING),
                Arguments.of(EndRequestReason.GUARDIAN_STOP, SessionStatus.ENDED, EndOutcome.NOT_ACTIVE,
                        SessionStatus.ENDED),
                // CONSENT_WITHDRAWN: 시작 단계 202 {} + FAILED(CONSENT_WITHDRAWN), 진행 중 202 {CLOSING}, CLOSING 202 {CLOSING}, 종료 409
                Arguments.of(EndRequestReason.CONSENT_WITHDRAWN, SessionStatus.PREPARING, EndOutcome.ACCEPTED_EMPTY,
                        SessionStatus.FAILED),
                Arguments.of(EndRequestReason.CONSENT_WITHDRAWN, SessionStatus.DEVICE_ACK, EndOutcome.ACCEPTED_EMPTY,
                        SessionStatus.FAILED),
                Arguments.of(EndRequestReason.CONSENT_WITHDRAWN, SessionStatus.WAITING_CHILD, EndOutcome.CLOSING,
                        SessionStatus.CLOSING),
                Arguments.of(EndRequestReason.CONSENT_WITHDRAWN, SessionStatus.ACTIVE, EndOutcome.CLOSING,
                        SessionStatus.CLOSING),
                Arguments.of(EndRequestReason.CONSENT_WITHDRAWN, SessionStatus.CLOSING, EndOutcome.CLOSING,
                        SessionStatus.CLOSING),
                Arguments.of(EndRequestReason.CONSENT_WITHDRAWN, SessionStatus.ABORTED, EndOutcome.NOT_ACTIVE,
                        SessionStatus.ABORTED),
                // CORE_ABANDONED: PREPARING ~ ACTIVE → FAILED + 202 {}, CLOSING · 종료 202 {} 무동작(BR1.9)
                Arguments.of(EndRequestReason.CORE_ABANDONED, SessionStatus.DEVICE_ACK, EndOutcome.ACCEPTED_EMPTY,
                        SessionStatus.FAILED),
                Arguments.of(EndRequestReason.CORE_ABANDONED, SessionStatus.WAITING_CHILD, EndOutcome.ACCEPTED_EMPTY,
                        SessionStatus.FAILED),
                Arguments.of(EndRequestReason.CORE_ABANDONED, SessionStatus.CLOSING, EndOutcome.ACCEPTED_EMPTY,
                        SessionStatus.CLOSING),
                Arguments.of(EndRequestReason.CORE_ABANDONED, SessionStatus.DECLINED, EndOutcome.ACCEPTED_EMPTY,
                        SessionStatus.DECLINED));
    }

    @ParameterizedTest(name = "{0} @ {1} -> {2}")
    @MethodSource("tableWithoutExecutor")
    void withoutAnExecutorTheDatabaseRowAnswersByTheTable(EndRequestReason reason, SessionStatus state,
            EndOutcome expected, SessionStatus after) {
        MutableClock clock = new MutableClock();
        InMemoryTransitions transitions = new InMemoryTransitions(clock);
        UUID sessionId = UUID.randomUUID();
        transitions.seed(sessionId, UUID.randomUUID(), UUID.randomUUID());
        if (state != SessionStatus.PREPARING) {
            transitions.force(sessionId, state);
        }
        EndRequestHandler handler = handler(transitions, clock);

        assertThat(handler.end(sessionId, UUID.randomUUID(), reason, null)).isEqualTo(expected);
        assertThat(transitions.status(sessionId)).isEqualTo(after);
        if (reason == EndRequestReason.CONSENT_WITHDRAWN && after != state) {
            assertThat(transitions.find(sessionId).orElseThrow().endReason()).isEqualTo(EndReason.CONSENT_WITHDRAWN);
            assertThat(transitions.find(sessionId).orElseThrow().terminationRequestedAt()).isEqualTo(clock.now());
        }
    }

    @Test
    void unknownSessionsAreNotActiveExceptForTheCoreCancelWhichLeavesATombstone() {
        MutableClock clock = new MutableClock();
        InMemoryTransitions transitions = new InMemoryTransitions(clock);
        TombstoneMap tombstones = new TombstoneMap(clock, Duration.ofMinutes(10));
        EndRequestHandler handler = new EndRequestHandler(new SessionRegistry(), tombstones, transitions, clock,
                Duration.ofSeconds(2));
        UUID unknown = UUID.randomUUID();

        assertThat(handler.end(unknown, UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, null))
                .isEqualTo(EndOutcome.NOT_ACTIVE);
        assertThat(handler.end(unknown, UUID.randomUUID(), EndRequestReason.CONSENT_WITHDRAWN, null))
                .isEqualTo(EndOutcome.NOT_ACTIVE);
        assertThat(tombstones.contains(unknown)).isFalse();
        assertThat(handler.end(unknown, UUID.randomUUID(), EndRequestReason.CORE_ABANDONED, null))
                .isEqualTo(EndOutcome.ACCEPTED_EMPTY);
        assertThat(tombstones.contains(unknown)).isTrue();
    }

    @Test
    void reasonsOutsideTheTableAreRejected() {
        MutableClock clock = new MutableClock();
        InMemoryTransitions transitions = new InMemoryTransitions(clock);
        UUID sessionId = UUID.randomUUID();
        transitions.seed(sessionId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(handler(transitions, clock).end(sessionId, UUID.randomUUID(), EndRequestReason.UNRECOGNIZED, null))
                .isEqualTo(EndOutcome.UNSUPPORTED);
        assertThat(transitions.status(sessionId)).isEqualTo(SessionStatus.PREPARING);
    }

    // ------------------------------------------------------------------ 실행기가 있는 세션

    @Test
    void consentWithdrawnInTheStartPhaseFailsWithItsOwnReasonAndClosesTheTransport() {
        h.boot();
        CompletableFuture<EndOutcome> first = end(EndRequestReason.CONSENT_WITHDRAWN);
        CompletableFuture<EndOutcome> again = end(EndRequestReason.CONSENT_WITHDRAWN);
        h.drain();

        assertThat(first).isCompletedWithValue(EndOutcome.ACCEPTED_EMPTY);
        ActivitySessionRow row = h.transitions.find(h.sessionId).orElseThrow();
        assertThat(row.status()).isEqualTo(SessionStatus.FAILED);
        assertThat(row.endReason()).isEqualTo(EndReason.CONSENT_WITHDRAWN);
        assertThat(row.failureCode()).isNull();
        assertThat(h.link.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.CONSENT_WITHDRAWN);
        // 실행기가 끝난 뒤 남은 요청은 DB 행으로 답하도록 풀린다(전이 1회)
        assertThat(again).isCompletedExceptionally();
        assertThat(h.transitions.applied()).filteredOn(a -> a.to() == SessionStatus.FAILED).hasSize(1);
    }

    @Test
    void guardianStopInTheStartPhaseIsNotActive() {
        h.boot();
        h.mailbox.offerGeneral(new SessionMailbox.DeviceAck(h.link.connectionId(), h.startOpId(), h.clock.now()));
        h.drain();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.DEVICE_ACK);

        CompletableFuture<EndOutcome> stop = end(EndRequestReason.GUARDIAN_STOP);
        h.drain();

        assertThat(stop).isCompletedWithValue(EndOutcome.NOT_ACTIVE);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.DEVICE_ACK);
        assertThat(h.link.sent(StopOutput.class)).isEmpty();
    }

    @Test
    void consentWithdrawnWhileActiveRunsTheClosingSequenceWithBeforeClosingAndItsReason() {
        h.activate();
        CompletableFuture<EndOutcome> first = end(EndRequestReason.CONSENT_WITHDRAWN);
        h.drain();

        assertThat(first).isCompletedWithValue(EndOutcome.CLOSING);
        ActivitySessionRow row = h.transitions.find(h.sessionId).orElseThrow();
        assertThat(row.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(row.endReason()).isEqualTo(EndReason.CONSENT_WITHDRAWN);
        assertThat(row.terminationRequestedAt()).isNotNull();
        assertThat(h.link.sent(StopOutput.class)).extracting(StopOutput::reason)
                .containsExactly(StopOutput.Reason.BEFORE_CLOSING);
        PlayApprovedContent closing = h.link.sent(PlayApprovedContent.class).getLast();
        assertThat(closing.contentCode()).isEqualTo(PlayApprovedContent.CLOSING);

        mark(closing, PlaybackMark.Status.COMPLETED);

        assertThat(h.link.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.CONSENT_WITHDRAWN);
    }

    @Test
    void repeatedRequestsAreIdempotentAndTheFirstReasonStays() {
        h.activate();
        CompletableFuture<EndOutcome> stop = end(EndRequestReason.GUARDIAN_STOP);
        CompletableFuture<EndOutcome> consent = end(EndRequestReason.CONSENT_WITHDRAWN);
        CompletableFuture<EndOutcome> stopAgain = end(EndRequestReason.GUARDIAN_STOP);
        CompletableFuture<EndOutcome> cancel = end(EndRequestReason.CORE_ABANDONED);
        h.drain();

        assertThat(stop).isCompletedWithValue(EndOutcome.CLOSING);
        assertThat(consent).isCompletedWithValue(EndOutcome.CLOSING);
        assertThat(stopAgain).isCompletedWithValue(EndOutcome.CLOSING);
        assertThat(cancel).as("CLOSING의 Core 취소는 무동작(BR1.9)").isCompletedWithValue(EndOutcome.ACCEPTED_EMPTY);
        assertThat(h.transitions.applied()).filteredOn(a -> a.to() == SessionStatus.CLOSING).hasSize(1);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().endReason()).isEqualTo(EndReason.GUARDIAN_STOP);
        assertThat(h.link.sent(StopOutput.class)).as("기기 종료 명령 1회").hasSize(1);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
    }

    @Test
    void afterConsentIsWithdrawnLaterChildTranscriptsAreNotStored() {
        h.activate();
        h.provider.last().emit(new ProviderEvent.InputTranscriptDone("before", "공룡 좋아", null));
        h.drain();
        int rowsBefore = h.store.transcripts.size();

        end(EndRequestReason.CONSENT_WITHDRAWN);
        h.drain();
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("after", "그만"));
        h.provider.last().emit(new ProviderEvent.InputTranscriptDone("after", "그만할래", null));
        h.drain();

        assertThat(h.store.transcripts.stream().filter(entry -> entry.speaker()
                == kr.mujoong.realtime.log.TranscriptEntry.Speaker.CHILD)).hasSize(1);
        assertThat(h.store.transcripts.size()).isGreaterThanOrEqualTo(rowsBefore);
        assertThat(h.store.transcripts.stream().map(entry -> entry.text())).doesNotContain("그만할래", "그만");
    }

    @Test
    void theUnconfirmedEndOfAConsentClosingKeepsTheFirstReasonInTheDetail() {
        h.activate();
        end(EndRequestReason.CONSENT_WITHDRAWN);
        h.drain();

        h.advance(h.settings.closingConfirm());

        ActivitySessionRow row = h.transitions.find(h.sessionId).orElseThrow();
        assertThat(row.status()).isEqualTo(SessionStatus.ENDED_UNCONFIRMED);
        assertThat(row.endReason()).isEqualTo(EndReason.UNCONFIRMED);
        assertThat(row.endDetail()).isEqualTo("CONSENT_WITHDRAWN");
    }

    private CompletableFuture<EndOutcome> end(EndRequestReason reason) {
        CompletableFuture<EndOutcome> result = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), reason, result));
        return result;
    }

    private void mark(PlayApprovedContent play, PlaybackMark.Status status) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(play.playbackId(), status, null, h.clock.now(), null, null), h.clock.now()));
        h.drain();
    }

    private static EndRequestHandler handler(InMemoryTransitions transitions, MutableClock clock) {
        return new EndRequestHandler(new SessionRegistry(), new TombstoneMap(clock, Duration.ofMinutes(10)), transitions,
                clock, Duration.ofSeconds(2));
    }
}
