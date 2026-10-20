package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.event.payload.SessionStateChanged;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.log.SessionGapRepository;
import kr.mujoong.realtime.testing.SessionHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * U4b 끊김 · 기술 일시정지 시험 (L4 · FR5.1 · FR2.1 · BR6.1 ~ BR6.4 · BR2.2 · BR7.2 · U4b-J5 · U4b-J13 · U4b-J14 · NFR2).
 *
 * <p>점선 9 · 11초, 끊김 39 · 41초 경계, WAITING_CHILD 끊김, CLOSING에는 끊김 전이가 없음, 미확인 구간, 일시정지 동안 참여 창이 멈췄다 남은 시간부터
 * 이어짐, 전이 시점의 일시정지가 SessionStateChanged에 실림을 본다.
 */
@Tag("session-runtime")
class TechnicalPauseTest {

    private final SessionHarness h = new SessionHarness();

    @Test
    void theDottedPauseStartsBetween9And11SecondsOfDeviceSilenceAndOpensTheGap() {
        h.activate();
        Instant last = h.clock.now();
        h.link.touch(last);

        h.advance(Duration.ofSeconds(9));
        assertThat(h.executor.liveState().technicalPause()).isNull();
        h.advance(Duration.ofSeconds(2));

        SessionStateChanged.TechnicalPause pause = h.executor.liveState().technicalPause();
        assertThat(pause).isNotNull();
        assertThat(pause.kind()).isEqualTo(SessionStateChanged.TechnicalPause.Kind.DEVICE);
        assertThat(pause.since()).as("마지막 기기 메시지 시각").isEqualTo(last);
        assertThat(h.gaps.opened()).singleElement().satisfies(gap -> {
            assertThat(gap.kind()).isEqualTo(SessionGapRepository.Kind.DISCONNECT);
            assertThat(gap.startedAt()).isEqualTo(last);
        });
        assertThat(events(IdleClock.EVENT_TECHNICAL_PAUSE_STARTED)).hasSize(1);
    }

    @Test
    void aMessageAgainEndsThePauseAndClosesTheGap() {
        h.activate();
        h.link.touch(h.clock.now());
        h.advance(Duration.ofSeconds(12));
        assertThat(h.executor.liveState().technicalPause()).isNotNull();

        h.link.touch(h.clock.now());
        h.advance(h.settings.timerTick());

        assertThat(h.executor.liveState().technicalPause()).isNull();
        assertThat(h.gaps.closed()).hasSize(1);
        assertThat(events(IdleClock.EVENT_TECHNICAL_PAUSE_ENDED)).hasSize(1);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void fortySecondsOfSilenceAbortsBetween39And41WithThePauseOnTheTransitionAndTheGapLeftOpen() {
        h.activate();
        h.link.touch(h.clock.now());

        h.advance(Duration.ofSeconds(39));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        h.advance(Duration.ofSeconds(2));

        ActivitySessionRow row = h.transitions.find(h.sessionId).orElseThrow();
        assertThat(row.status()).isEqualTo(SessionStatus.ABORTED);
        assertThat(row.endReason()).isEqualTo(EndReason.DISCONNECT);
        assertThat(h.gaps.closed()).as("복구하지 못한 구간은 열린 채").isEmpty();
        assertThat(h.transitions.extrasApplied()).filteredOn(applied -> applied.to() == SessionStatus.ABORTED)
                .singleElement().satisfies(applied -> assertThat(applied.extras().technicalPause()).isNotNull());
    }

    @Test
    void waitingChildAlsoAbortsAfterFortySeconds() {
        h.boot();
        h.ackAndReady();
        h.link.touch(h.clock.now());

        h.advance(Duration.ofSeconds(41));

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.ABORTED);
    }

    @Test
    void closingHasNoDisconnectTransitionAndAnUnconfirmedEndLeavesAnUnconfirmedGap() {
        h.activate();
        Instant closingAt = h.clock.now();
        CompletableFuture<SessionMailbox.EndOutcome> stop = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        h.drain();

        h.advance(Duration.ofSeconds(29));
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.CLOSING);
        h.advance(Duration.ofSeconds(2));

        ActivitySessionRow row = h.transitions.find(h.sessionId).orElseThrow();
        assertThat(row.status()).as("끊김 40초 전에 30초 확인이 닫는다, ABORTED 없음").isEqualTo(SessionStatus.ENDED_UNCONFIRMED);
        assertThat(row.endDetail()).isEqualTo("GUARDIAN_STOP");
        assertThat(h.gaps.opened()).filteredOn(gap -> gap.kind() == SessionGapRepository.Kind.UNCONFIRMED)
                .singleElement().satisfies(gap -> assertThat(gap.startedAt()).isEqualTo(closingAt));
        assertThat(h.gaps.closed()).anySatisfy(closed -> assertThat(closed.endedAt()).isEqualTo(row.endedAt()));
    }

    @Test
    void theJoinWindowStopsDuringThePauseAndResumesWithTheTimeThatWasLeft() {
        h.boot();
        h.ackAndReady();
        Instant start = h.clock.now();
        h.link.touch(start);

        // 10초에 일시정지(창은 10초 남음) → 30초에 기기가 다시 말한다(약 20초 멈춤)
        h.advance(Duration.ofSeconds(30));
        assertThat(h.executor.liveState().technicalPause()).isNotNull();
        h.link.touch(h.clock.now());
        h.advance(h.settings.timerTick());
        assertThat(h.executor.liveState().technicalPause()).isNull();

        h.advanceAlive(Duration.ofSeconds(8));
        assertThat(events(JoinFlow.EVENT_JOIN_RE_PROMPTED)).as("멈춘 시간은 창에 세지 않는다").isEmpty();
        h.advanceAlive(Duration.ofSeconds(3));
        assertThat(events(JoinFlow.EVENT_JOIN_RE_PROMPTED)).hasSize(1);
    }

    @Test
    void aClosedConnectionPausesAtOnceAndAClosingTransitionCarriesThePause() {
        h.activate();
        h.mailbox.putSafety(new SessionMailbox.ConnectionLost(h.link.connectionId()));
        h.drain();
        assertThat(h.executor.liveState().technicalPause()).as("연결이 닫히면 점선 시계를 기다리지 않는다").isNotNull();

        CompletableFuture<SessionMailbox.EndOutcome> stop = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        h.drain();

        assertThat(h.transitions.extrasApplied()).filteredOn(applied -> applied.to() == SessionStatus.CLOSING)
                .singleElement().satisfies(applied -> assertThat(applied.extras().technicalPause()).isNotNull());
    }

    @Test
    void anEndedSessionShowsNoLiveValues() {
        h.activate();
        h.link.touch(h.clock.now());
        h.advance(Duration.ofSeconds(41));

        assertThat(h.executor.liveState()).isEqualTo(new IdleClock.LiveState(null, null));
    }

    private List<SessionEvent> events(String type) {
        h.recorder.tick();
        return h.store.sessionEvents.stream().filter(event -> event.getEventType().equals(type)).toList();
    }
}
