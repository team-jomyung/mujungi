package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.gate.GateDecision.DiscardReason;
import kr.mujoong.realtime.gate.SegmentBuffer;
import kr.mujoong.realtime.guardrail.GuardrailMode;
import kr.mujoong.realtime.guardrail.GuardrailRequest;
import kr.mujoong.realtime.guardrail.GuardrailSupport;
import kr.mujoong.realtime.log.TranscriptWriter;
import kr.mujoong.realtime.testing.FakeGuardrailClient;
import kr.mujoong.realtime.testing.SessionHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 연습 확장점 알림 (U13 BR6.5 · W8): 연습 모드(PRACTICE_PEER · PRACTICE_COACH) 요청이 재지시 뒤에도 막히면 연습 확장점에 {@code GUARDRAIL_BLOCKED}
 * 사건 값 하나가 전달된다. 운영에서 연습 모드 검사를 시작하는 것은 U12라서 여기서는 시험이 연습 모드를 대역으로 주입한다. 제브는 시험이 손으로 푸는
 * 대역이다(실제 호출 없음, R-F1 · R-M11).
 */
@Tag("u13")
class GuardrailPracticeEventTest {

    private final SessionHarness h = new SessionHarness();
    private final FakeGuardrailClient guard = new FakeGuardrailClient();
    private final Env env = new Env();
    private final TranscriptWriter writer = new TranscriptWriter(h.sessionId, 0, h.recorder, h.latency);
    private final GuardrailCoordinator coordinator;
    private final UUID speechId = UUID.randomUUID();

    GuardrailPracticeEventTest() {
        GuardrailSupport support = new GuardrailSupport(GuardrailMode.REAL, guard, h.guardrailRecorder, Duration.ofMillis(800),
                FakeGuardrailClient.POLICY);
        SessionExecutor.Plan plan = new SessionExecutor.Plan(h.sessionId, h.deviceId, "0123456789abcdef0123456789abcdef",
                new ChildContext("하늘", 8, List.of("공룡")), List.of(), null, null, false, List.of(), null, null, null,
                h.guardianId);
        coordinator = new GuardrailCoordinator(support, plan, env);
    }

    @Test
    void aPeerModeRequestBlockedAgainAfterTheRetryNotifiesThePracticeExtensionOnce() {
        blockTwice(GuardrailRequest.Mode.PRACTICE_PEER);

        assertThat(env.blocked).singleElement().satisfies(event -> {
            assertThat(event.correlationId()).isEqualTo(speechId);
            assertThat(event.mode()).isEqualTo(GuardrailRequest.Mode.PRACTICE_PEER);
            assertThat(event.reasonCode()).isEqualTo("UNSAFE");
            assertThat(event.retried()).isTrue();
        });
        assertThat(env.abandoned).containsExactly(DiscardReason.GUARDRAIL_BLOCK);
    }

    @Test
    void aCoachModeRequestBlockedAgainAfterTheRetryNotifiesThePracticeExtensionOnce() {
        blockTwice(GuardrailRequest.Mode.PRACTICE_COACH);

        assertThat(env.blocked).singleElement().satisfies(event -> {
            assertThat(event.mode()).isEqualTo(GuardrailRequest.Mode.PRACTICE_COACH);
            assertThat(event.retried()).isTrue();
        });
    }

    @Test
    void aFirstBlockOnlyRetriesAndNotifiesNothing() {
        coordinator.begin(seal("첫 문장"), GuardrailRequest.Mode.PRACTICE_PEER, speechId);
        env.practiceId = speechId;
        guard.block();
        arrive();

        assertThat(env.blocked).isEmpty();
        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.RETRYING);
    }

    @Test
    void aFreeTalkRequestBlockedTwiceNotifiesNoPracticeExtension() {
        blockTwice(GuardrailRequest.Mode.FREE_TALK);

        assertThat(env.blocked).isEmpty();
        assertThat(env.abandoned).containsExactly(DiscardReason.GUARDRAIL_BLOCK);
    }

    @Test
    void theEventValueRejectsFreeTalkAndCarriesNoTranscript() {
        assertThatThrownBy(() -> new PracticeSessionExtension.GuardrailBlockedEvent(speechId, GuardrailRequest.Mode.FREE_TALK,
                "UNSAFE", true)).isInstanceOf(IllegalArgumentException.class);

        PracticeSessionExtension.GuardrailBlockedEvent event = new PracticeSessionExtension.GuardrailBlockedEvent(speechId,
                GuardrailRequest.Mode.PRACTICE_PEER, "UNSAFE", true);
        assertThat(event.toString()).doesNotContain("문장");
    }

    private void blockTwice(GuardrailRequest.Mode mode) {
        env.practiceId = mode == GuardrailRequest.Mode.FREE_TALK ? null : speechId;
        UUID id = mode == GuardrailRequest.Mode.FREE_TALK ? null : speechId;
        coordinator.begin(seal("첫 문장"), mode, id);
        guard.block();
        arrive();
        coordinator.begin(seal("다시 한 문장"), mode, id);
        guard.block();
        arrive();
    }

    private SegmentBuffer.Sealed seal(String text) {
        writer.aiStart(h.clock.now());
        return new SegmentBuffer.Sealed(env.providerGen, text, List.of(new byte[4800], new byte[4800]), 9600, env.deviceGen,
                h.clock.now(), 0L, h.clock.now(), 0L, false, true);
    }

    private void arrive() {
        SessionMailbox.GuardrailArrived arrived = (SessionMailbox.GuardrailArrived) env.posted.removeLast();
        coordinator.onArrived(arrived);
    }

    /** 가짜 바깥 세계: 연습 알림만 기록하고 나머지는 최소로 받는다. */
    private final class Env implements GuardrailCoordinator.Env {
        UUID connection = UUID.randomUUID();
        long deviceGen = 5;
        long providerGen = 7;
        UUID practiceId;
        final List<SessionMailbox.Message> posted = new ArrayList<>();
        final List<DiscardReason> abandoned = new ArrayList<>();
        final List<PracticeSessionExtension.GuardrailBlockedEvent> blocked = new ArrayList<>();

        @Override
        public SessionStatus status() {
            return SessionStatus.ACTIVE;
        }

        @Override
        public boolean terminated() {
            return false;
        }

        @Override
        public Instant now() {
            return h.clock.now();
        }

        @Override
        public UUID connectionId() {
            return connection;
        }

        @Override
        public long deviceGeneration() {
            return deviceGen;
        }

        @Override
        public long providerGeneration() {
            return providerGen;
        }

        @Override
        public boolean outputHeld() {
            return false;
        }

        @Override
        public long inputGroupOpenCount() {
            return 0;
        }

        @Override
        public UUID practiceSpeechId() {
            return practiceId;
        }

        @Override
        public TranscriptWriter.Held holdAiRow() {
            return writer.aiHold();
        }

        @Override
        public boolean recheckAndSend(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held, boolean ownsLatency) {
            writer.aiFinal(held, sealed.transcript(), true, h.clock.now());
            return true;
        }

        @Override
        public void discardForRetry(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held, DiscardReason reason,
                boolean ownsLatency) {
            writer.aiFinal(held, sealed.transcript(), false, h.clock.now());
        }

        @Override
        public void abandon(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held, DiscardReason reason) {
            abandoned.add(reason);
            writer.aiFinal(held, sealed.transcript(), false, h.clock.now());
        }

        @Override
        public void discardSilent(SegmentBuffer.Sealed sealed, TranscriptWriter.Held held, DiscardReason reasonOrNull) {
            writer.aiFinal(held, sealed.transcript(), false, h.clock.now());
        }

        @Override
        public void finishAbandoned() {
        }

        @Override
        public void listeningAgain() {
        }

        @Override
        public boolean sendReplay(GuardrailCoordinator.ReplayableInstruction instruction, UUID retryId) {
            return true;
        }

        @Override
        public boolean sendRetryFreeTurn(UUID retryId) {
            return true;
        }

        @Override
        public boolean postFromProvider(SessionMailbox.Message message) {
            posted.add(message);
            return true;
        }

        @Override
        public void practiceBlocked(PracticeSessionExtension.GuardrailBlockedEvent event) {
            blocked.add(event);
        }

        @Override
        public void appError(String reasonCode, EventCode.Level level) {
        }

        @Override
        public void appError(RuntimeException error) {
        }
    }
}
