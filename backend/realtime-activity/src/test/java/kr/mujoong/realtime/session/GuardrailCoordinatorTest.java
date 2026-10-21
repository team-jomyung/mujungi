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
import kr.mujoong.realtime.guardrail.GuardrailEventRecord;
import kr.mujoong.realtime.guardrail.GuardrailRequest;
import kr.mujoong.realtime.guardrail.GuardrailSupport;
import kr.mujoong.realtime.guardrail.GuardrailVerdict;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.log.TranscriptWriter;
import kr.mujoong.realtime.testing.FakeGuardrailClient;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 제브 게이트 협력 객체 시험 (U13 BR3.x ~ BR6.x): 실행기 없이 가짜 {@link GuardrailCoordinator.Env}로 검사 상태 기계(IDLE · CHECKING ·
 * RETRYING), 검사표 무효 여덟 경우, 대기열 1칸, 턴당 재지시 1회, 폐기 훅을 확인한다. 제브는 시험이 손으로 푸는 대역이다(실제 제브 아님, R-F1 · R-M11).
 */
@Tag("jev-guardrail")
@org.junit.jupiter.api.Tag("service-guardrail")
class GuardrailCoordinatorTest {

    private static final String POLICY = FakeGuardrailClient.POLICY;

    private final SessionHarness h = new SessionHarness();
    private final FakeGuardrailClient guard = new FakeGuardrailClient();
    private final Env env = new Env();
    private final GuardrailCoordinator coordinator;
    private final TranscriptWriter writer = new TranscriptWriter(h.sessionId, 0, h.recorder, h.latency);
    private int sealedCount;

    GuardrailCoordinatorTest() {
        GuardrailSupport support = new GuardrailSupport(kr.mujoong.realtime.guardrail.GuardrailMode.REAL, guard,
                h.guardrailRecorder, Duration.ofMillis(800), POLICY);
        SessionExecutor.Plan plan = new SessionExecutor.Plan(h.sessionId, h.deviceId, "0123456789abcdef0123456789abcdef",
                new ChildContext("하늘", 8, List.of("공룡")), List.of(), null, null, false, List.of(), null, null, null,
                h.guardianId);
        coordinator = new GuardrailCoordinator(support, plan, env);
    }

    // ---- 정상 ----

    @Test
    void aValidAllowSendsTheSegmentOnceApprovesTheHeldRowAndReturnsToIdle() {
        SegmentBuffer.Sealed sealed = seal("공룡을 좋아하는구나");

        coordinator.begin(sealed, GuardrailRequest.Mode.FREE_TALK, null);

        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.CHECKING);
        assertThat(coordinator.holdsThinking()).isTrue();
        assertThat(guard.count()).isEqualTo(1);
        assertThat(guard.last().request().candidate()).isEqualTo("공룡을 좋아하는구나");
        assertThat(env.sent).isEmpty();
        arrive(() -> guard.allow());

        assertThat(env.sent).containsExactly("공룡을 좋아하는구나");
        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.IDLE);
        assertThat(coordinator.holdsThinking()).isFalse();
        assertThat(row(1).approvedForDisplay()).isTrue();
        assertThat(events()).singleElement().satisfies(event -> {
            assertThat(event.decision()).isEqualTo(GuardrailEventRecord.Decision.ALLOW);
            assertThat(event.sourceKind()).isEqualTo(GuardrailEventRecord.SourceKind.NEW_UTTERANCE);
            assertThat(event.policyVersion()).isEqualTo(POLICY);
        });
    }

    @Test
    void theRequestCarriesTheAnonymizedContextOfTheLastTurnsOnly() {
        coordinator.onNewTurn("하늘이는 공룡이 좋아");
        SegmentBuffer.Sealed first = seal("공룡을 좋아하는구나");
        coordinator.begin(first, GuardrailRequest.Mode.FREE_TALK, null);
        arrive(() -> guard.allow());
        coordinator.onNewTurn("티라노가 제일 좋아");

        coordinator.begin(seal("티라노는 힘이 세지"), GuardrailRequest.Mode.FREE_TALK, null);

        GuardrailRequest request = guard.last().request();
        assertThat(request.recentContext()).extracting(GuardrailRequest.ContextTurn::speaker)
                .containsExactly(GuardrailRequest.Speaker.CHILD, GuardrailRequest.Speaker.MUJOONG, GuardrailRequest.Speaker.CHILD);
        assertThat(request.recentContext().getFirst().text()).isEqualTo("아이이는 공룡이 좋아");
    }

    // ---- 막힘 ----

    @ParameterizedTest
    @EnumSource(value = GuardrailVerdict.Failure.class)
    void everyFailureKindIsABlockThatSendsNothingAndAsksForOneRetry(GuardrailVerdict.Failure failure) {
        coordinator.begin(seal("공룡을 좋아하는구나"), GuardrailRequest.Mode.FREE_TALK, null);

        arrive(() -> guard.fail(failure));

        assertThat(env.sent).isEmpty();
        assertThat(env.retried).hasSize(1);
        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.RETRYING);
        assertThat(row(1).approvedForDisplay()).isFalse();
        assertThat(row(1).assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        DiscardReason expected = failure == GuardrailVerdict.Failure.TIMEOUT ? DiscardReason.GUARDRAIL_TIMEOUT
                : DiscardReason.GUARDRAIL_ERROR;
        assertThat(env.discardReasons).containsExactly(expected);
    }

    @Test
    void blockAndReviewAreBlocksToo() {
        coordinator.begin(seal("첫 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        arrive(() -> guard.block());
        assertThat(env.discardReasons).containsExactly(DiscardReason.GUARDRAIL_BLOCK);
        coordinator.onNewTurn("다시 말해 줘");

        coordinator.begin(seal("둘째 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        arrive(() -> guard.review());

        assertThat(env.discardReasons).containsExactly(DiscardReason.GUARDRAIL_BLOCK, DiscardReason.GUARDRAIL_REVIEW);
        assertThat(events()).extracting(GuardrailEventRecord::decision)
                .containsExactlyInAnyOrder(GuardrailEventRecord.Decision.BLOCK, GuardrailEventRecord.Decision.REVIEW);
        assertThat(env.sent).isEmpty();
    }

    @Test
    void theRetryResponseGoesThroughTheSameCheckAndAnAllowThenSendsIt() {
        coordinator.begin(seal("첫 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        arrive(() -> guard.block());
        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.RETRYING);
        assertThat(coordinator.holdsThinking()).isTrue();

        coordinator.begin(seal("다른 말로 한 문장"), GuardrailRequest.Mode.FREE_TALK, null);

        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.CHECKING);
        assertThat(guard.count()).isEqualTo(2);
        arrive(() -> guard.allow());
        assertThat(env.sent).containsExactly("다른 말로 한 문장");
        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.IDLE);
    }

    @Test
    void aSecondBlockInTheSameTurnAbandonsTheTurnWithoutAnotherInstruction() {
        coordinator.begin(seal("첫 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        arrive(() -> guard.block());
        coordinator.begin(seal("둘째 문장"), GuardrailRequest.Mode.FREE_TALK, null);

        arrive(() -> guard.block());

        assertThat(env.retried).hasSize(1);
        assertThat(env.abandoned).containsExactly(DiscardReason.GUARDRAIL_BLOCK);
        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.IDLE);
        assertThat(coordinator.holdsThinking()).isFalse();
        assertThat(env.sent).isEmpty();
    }

    @Test
    void aRetryThatCannotBeSentAbandonsAtOnceAndReleasesTheDevice() {
        env.retrySends = false;
        coordinator.begin(seal("첫 문장"), GuardrailRequest.Mode.FREE_TALK, null);

        arrive(() -> guard.block());

        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.IDLE);
        assertThat(env.finished).isEqualTo(1);
        assertThat(env.appErrors).contains(GuardrailCoordinator.REASON_RETRY_NOT_HEARD);
        assertThat(coordinator.retries()).isEqualTo(1);
    }

    @Test
    void aRetryThatIsNeverHeardAbandonsTheTurnOnTheNotHeardMessageAndOnlyOnce() {
        coordinator.begin(seal("첫 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        arrive(() -> guard.block());
        UUID retryId = env.retryIds.getLast();

        coordinator.onRetryNotHeard(new SessionMailbox.GuardrailRetryNotHeard(UUID.randomUUID()));
        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.RETRYING);
        coordinator.onRetryNotHeard(new SessionMailbox.GuardrailRetryNotHeard(retryId));
        coordinator.onRetryNotHeard(new SessionMailbox.GuardrailRetryNotHeard(retryId));

        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.IDLE);
        assertThat(env.finished).isEqualTo(1);
        assertThat(env.retried).hasSize(1);
    }

    @Test
    void theLastServerInstructionIsReplayedWhenThereIsOneAndTheFreeTurnTemplateOtherwise() {
        GuardrailCoordinator.ReplayableInstruction replay = new GuardrailCoordinator.ReplayableInstruction("provider-respond-",
                id -> new ProviderRequestFactory().responseStart(id, kr.mujoong.realtime.dialog.InstructionTemplates.ResponseStart.FIRST));
        coordinator.onServerInstruction(replay);
        coordinator.begin(seal("첫 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        arrive(() -> guard.block());
        assertThat(env.replayed).containsExactly(replay);
        assertThat(env.freeTurns).isZero();

        coordinator.onNewTurn("그런데 말이야");
        coordinator.begin(seal("둘째 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        arrive(() -> guard.block());

        assertThat(env.replayed).hasSize(1);
        assertThat(env.freeTurns).isEqualTo(1);
    }

    @Test
    void aNewTurnAfterAnAbandonedOneGetsItsOwnRetry() {
        coordinator.begin(seal("첫 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        arrive(() -> guard.block());
        coordinator.begin(seal("둘째 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        arrive(() -> guard.block());
        assertThat(env.retried).hasSize(1);

        coordinator.onNewTurn("다음 이야기");
        coordinator.begin(seal("셋째 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        arrive(() -> guard.block());

        assertThat(env.retried).hasSize(2);
        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.RETRYING);
    }

    @Test
    void aRequestThatCannotBeBuiltIsABlockWithoutCallingTheChecker() {
        coordinator.begin(seal("가".repeat(GuardrailRequest.CANDIDATE_MAX + 1)), GuardrailRequest.Mode.FREE_TALK, null);

        assertThat(guard.count()).isZero();
        assertThat(env.discardReasons).containsExactly(DiscardReason.GUARDRAIL_ERROR);
        assertThat(env.retried).hasSize(1);
        assertThat(events()).singleElement().satisfies(event -> {
            assertThat(event.decision()).isEqualTo(GuardrailEventRecord.Decision.ERROR);
            assertThat(event.reasonCode()).isEqualTo("INVALID_REQUEST");
        });
    }

    // ---- 무효 (늦은 ALLOW) ----

    enum Stale {
        SESSION_NOT_ACTIVE, SESSION_TERMINATED, CONNECTION_REPLACED, CONNECTION_CLOSED, PROVIDER_GENERATION, DEVICE_GENERATION,
        OUTPUT_HELD, NEW_INPUT_GROUP, PRACTICE_CHANGED
    }

    @ParameterizedTest
    @EnumSource(Stale.class)
    void aLateAllowIsVoidedWhenAnyOfTheTicketConditionsChanged(Stale stale) {
        UUID practice = stale == Stale.PRACTICE_CHANGED ? UUID.randomUUID() : null;
        coordinator.begin(seal("공룡을 좋아하는구나"), GuardrailRequest.Mode.FREE_TALK, practice);
        switch (stale) {
            case SESSION_NOT_ACTIVE -> env.status = SessionStatus.CLOSING;
            case SESSION_TERMINATED -> env.terminated = true;
            case CONNECTION_REPLACED -> env.connection = UUID.randomUUID();
            case CONNECTION_CLOSED -> env.connection = null;
            case PROVIDER_GENERATION -> env.providerGen++;
            case DEVICE_GENERATION -> env.deviceGen++;
            case OUTPUT_HELD -> env.held = true;
            case NEW_INPUT_GROUP -> env.inputOpens++;
            case PRACTICE_CHANGED -> env.practiceId = null;
        }

        arrive(() -> guard.allow());

        assertThat(env.sent).isEmpty();
        assertThat(env.retried).isEmpty();
        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.IDLE);
        assertThat(env.listening).isEqualTo(1);
        assertThat(row(1).approvedForDisplay()).isFalse();
        assertThat(events()).singleElement().satisfies(event -> {
            assertThat(event.decision()).isEqualTo(GuardrailEventRecord.Decision.VOIDED);
            assertThat(event.reasonCode()).isEqualTo(switch (stale) {
                case SESSION_NOT_ACTIVE, SESSION_TERMINATED -> "SESSION_ENDED";
                case CONNECTION_REPLACED, CONNECTION_CLOSED -> "CONNECTION_CHANGED";
                case PROVIDER_GENERATION, DEVICE_GENERATION -> "GENERATION_CHANGED";
                case OUTPUT_HELD -> "HELD";
                case NEW_INPUT_GROUP -> "CHILD_SPOKE";
                case PRACTICE_CHANGED -> "PRACTICE_CHANGED";
            });
        });
    }

    @Test
    void aResultWithAnotherRequestIdIsDroppedAndTheRealOneStillWorks() {
        coordinator.begin(seal("공룡을 좋아하는구나"), GuardrailRequest.Mode.FREE_TALK, null);

        coordinator.onArrived(new SessionMailbox.GuardrailArrived(UUID.randomUUID(),
                GuardrailVerdict.allow("OK", POLICY, 1L, List.of(), List.of())));

        assertThat(env.sent).isEmpty();
        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.CHECKING);
        assertThat(env.appErrors).contains(GuardrailCoordinator.REASON_LATE_DROPPED);
        arrive(() -> guard.allow());
        assertThat(env.sent).hasSize(1);
    }

    @Test
    void aStaleBlockIsRecordedAsABlockButNeverRetried() {
        coordinator.begin(seal("공룡을 좋아하는구나"), GuardrailRequest.Mode.FREE_TALK, null);
        env.providerGen++;

        arrive(() -> guard.block());

        assertThat(env.retried).isEmpty();
        assertThat(env.sent).isEmpty();
        assertThat(events()).singleElement().extracting(GuardrailEventRecord::decision)
                .isEqualTo(GuardrailEventRecord.Decision.BLOCK);
    }

    @Test
    void aTailPieceOfTheSameInputGroupIsNotANewUtteranceButANewGroupIs() {
        env.inputOpens = 3;
        coordinator.begin(seal("첫 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        // 같은 묶음의 꼬리 조각은 열림 횟수를 올리지 않는다
        arrive(() -> guard.allow());
        assertThat(env.sent).hasSize(1);

        coordinator.begin(seal("둘째 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        env.inputOpens = 4;
        arrive(() -> guard.allow());

        assertThat(env.sent).hasSize(1);
        assertThat(events()).extracting(GuardrailEventRecord::reasonCode).contains("CHILD_SPOKE");
    }

    // ---- 대기열 1칸 ----

    @Test
    void aSegmentSealedDuringACheckWaitsInTheSingleSlotAndIsCheckedAfterTheFirstAllowIsSent() {
        coordinator.begin(seal("첫 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        coordinator.begin(seal("둘째 문장"), GuardrailRequest.Mode.FREE_TALK, null);

        assertThat(coordinator.hasQueued()).isTrue();
        assertThat(guard.count()).isEqualTo(1);
        assertThat(writer.heldCount()).isEqualTo(2);
        FakeGuardrailClient.Call first = guard.last();
        arrive(() -> guard.allow(first));

        assertThat(env.sent).containsExactly("첫 문장");
        assertThat(guard.count()).isEqualTo(2);
        assertThat(guard.last().request().candidate()).isEqualTo("둘째 문장");
        assertThat(coordinator.hasQueued()).isFalse();
        arrive(() -> guard.allow());
        assertThat(env.sent).containsExactly("첫 문장", "둘째 문장");
        assertThat(writer.heldCount()).isZero();
        assertThat(row(1).approvedForDisplay()).isTrue();
        assertThat(row(2).approvedForDisplay()).isTrue();
    }

    @Test
    void aThirdSegmentIsDiscardedWithQueueFullAndTheCheckInFlightIsUntouched() {
        coordinator.begin(seal("첫 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        coordinator.begin(seal("둘째 문장"), GuardrailRequest.Mode.FREE_TALK, null);

        coordinator.begin(seal("셋째 문장"), GuardrailRequest.Mode.FREE_TALK, null);

        assertThat(env.silent).containsExactly(DiscardReason.GUARDRAIL_QUEUE_FULL);
        assertThat(row(3).approvedForDisplay()).isFalse();
        assertThat(row(3).assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.CHECKING);
        assertThat(events()).extracting(GuardrailEventRecord::decision).contains(GuardrailEventRecord.Decision.QUEUE_FULL);
    }

    @Test
    void theQueuedSegmentIsDiscardedWheneverThePreviousCheckDidNotEndInASentAllowWhateverTheGeneration() {
        coordinator.begin(seal("첫 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        coordinator.begin(seal("둘째 문장"), GuardrailRequest.Mode.FREE_TALK, null);

        arrive(() -> guard.block());

        assertThat(coordinator.hasQueued()).isFalse();
        assertThat(guard.count()).isEqualTo(1);
        assertThat(row(2).approvedForDisplay()).isFalse();
        assertThat(row(2).assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        assertThat(events()).extracting(GuardrailEventRecord::reasonCode).contains(GuardrailCoordinator.REASON_QUEUE_DISCARDED);
        assertThat(writer.heldCount()).isZero();
    }

    @Test
    void theQueuedSegmentIsDiscardedWhenThePreviousCheckWasVoidedOrTheStateChangedAfterIt() {
        coordinator.begin(seal("첫 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        coordinator.begin(seal("둘째 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        env.inputOpens++;

        arrive(() -> guard.allow());

        assertThat(env.sent).isEmpty();
        assertThat(guard.count()).isEqualTo(1);
        assertThat(row(2).approvedForDisplay()).isFalse();
        assertThat(coordinator.hasQueued()).isFalse();
        assertThat(events()).extracting(GuardrailEventRecord::reasonCode).contains(GuardrailCoordinator.REASON_QUEUE_DISCARDED);
    }

    // ---- 훅 · 예외 · 이탈 ----

    @Test
    void theDiscardHookReleasesRetryingOnceAndNeverFinishesTheDiscardItself() {
        coordinator.begin(seal("첫 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        arrive(() -> guard.block());
        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.RETRYING);
        int finishedBefore = env.finished;
        int listeningBefore = env.listening;

        coordinator.onDiscardedBeforeCheck();
        coordinator.onDiscardedBeforeCheck();

        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.IDLE);
        assertThat(env.finished).isEqualTo(finishedBefore);
        assertThat(env.listening).isEqualTo(listeningBefore);
        assertThat(coordinator.retries()).isEqualTo(1);
    }

    @Test
    void theHookDoesNothingWhileCheckingAndARetryNoLongerWaitingForItsGenerationIsReleasedLazily() {
        coordinator.begin(seal("첫 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        coordinator.onDiscardedBeforeCheck();
        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.CHECKING);
        arrive(() -> guard.block());
        assertThat(coordinator.holdsThinking()).isTrue();

        env.providerGen++;

        assertThat(coordinator.holdsThinking()).isFalse();
        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.IDLE);
    }

    @Test
    void aBeginFailureClosesTheHeldRowDropsTheTicketAndAbandonsWithGuardrailError() {
        guard.throwOnCheck(new IllegalStateException("합성 어댑터 결함"));
        SegmentBuffer.Sealed sealed = seal("첫 문장");

        RuntimeException thrown = null;
        try {
            coordinator.begin(sealed, GuardrailRequest.Mode.FREE_TALK, null);
        } catch (RuntimeException e) {
            thrown = e;
            coordinator.onBeginFailed(sealed, e);
        }

        assertThat(thrown).isNotNull();
        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.IDLE);
        assertThat(env.abandoned).containsExactly(DiscardReason.GUARDRAIL_ERROR);
        assertThat(row(1).approvedForDisplay()).isFalse();
        assertThat(row(1).assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        assertThat(writer.heldCount()).isZero();
        assertThat(coordinator.holdsThinking()).isFalse();
    }

    @Test
    void aBeginFailureWhileQueuingOnlyDropsTheQueuedSegment() {
        coordinator.begin(seal("첫 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        env.failHold = true;
        SegmentBuffer.Sealed second = seal("둘째 문장");

        try {
            coordinator.begin(second, GuardrailRequest.Mode.FREE_TALK, null);
        } catch (RuntimeException e) {
            coordinator.onBeginFailed(second, e);
        }

        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.CHECKING);
        assertThat(env.silent).containsExactly(DiscardReason.GUARDRAIL_ERROR);
        assertThat(env.abandoned).isEmpty();
        env.failHold = false;
        arrive(() -> guard.allow());
        assertThat(env.sent).containsExactly("첫 문장");
    }

    @Test
    void exitCancelsTheRequestForgetsEverythingAndLeavesHeldRowsToCloseAll() {
        coordinator.begin(seal("첫 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        coordinator.begin(seal("둘째 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        FakeGuardrailClient.Call call = guard.last();

        coordinator.onExit();

        assertThat(call.future().isCancelled()).isTrue();
        assertThat(coordinator.state()).isEqualTo(GuardrailCoordinator.State.IDLE);
        assertThat(coordinator.hasQueued()).isFalse();
        assertThat(writer.heldCount()).isEqualTo(2);
        writer.closeAll(h.clock.now());
        assertThat(writer.heldCount()).isZero();
        assertThat(row(1).approvedForDisplay()).isFalse();
        assertThat(row(2).approvedForDisplay()).isFalse();
    }

    @Test
    void anOffSupportNeverStartsAChecker() {
        GuardrailCoordinator off = new GuardrailCoordinator(GuardrailSupport.DISABLED,
                new SessionExecutor.Plan(h.sessionId, h.deviceId, null, new ChildContext("하늘", 8, List.of()), List.of(), null,
                        null, false, List.of()),
                env);

        assertThat(off.enabled()).isFalse();
        assertThatThrownBy(() -> off.begin(seal("문장"), GuardrailRequest.Mode.FREE_TALK, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(guard.count()).isZero();
    }

    @Test
    void recordedEventsNeverCarryTheTranscript() {
        coordinator.begin(seal("아주 민감한 후보 문장"), GuardrailRequest.Mode.FREE_TALK, null);
        arrive(() -> guard.block());

        assertThat(events().toString()).doesNotContain("민감한");
        assertThat(coordinator.toString()).doesNotContain("민감한");
    }

    // ---- 도우미 ----

    private SegmentBuffer.Sealed seal(String text) {
        sealedCount++;
        writer.aiStart(h.clock.now());
        return new SegmentBuffer.Sealed(env.providerGen, text, List.of(new byte[4800], new byte[4800]), 9600, env.deviceGen,
                h.clock.now(), 0L, h.clock.now(), 0L, false, true);
    }

    /** 대역을 푼 뒤 편지함으로 넘어온 결과를 실행기 스레드처럼 협력 객체에 넘긴다. */
    private void arrive(Runnable resolve) {
        resolve.run();
        SessionMailbox.GuardrailArrived arrived = (SessionMailbox.GuardrailArrived) env.posted.removeLast();
        coordinator.onArrived(arrived);
    }

    private List<GuardrailEventRecord> events() {
        h.guardrailRecorder.tick();
        return List.copyOf(h.store.guardrails);
    }

    private TranscriptEntry row(int seq) {
        h.recorder.tick();
        return h.store.row(seq).orElseThrow();
    }

    /** 가짜 바깥 세계: 호출을 기록하고 값은 시험이 바꾼다. */
    private final class Env implements GuardrailCoordinator.Env {
        SessionStatus status = SessionStatus.ACTIVE;
        boolean terminated;
        UUID connection = UUID.randomUUID();
        long deviceGen = 5;
        long providerGen = 7;
        boolean held;
        long inputOpens;
        UUID practiceId;
        boolean retrySends = true;
        boolean failHold;
        final List<SessionMailbox.Message> posted = new ArrayList<>();
        final List<String> sent = new ArrayList<>();
        final List<DiscardReason> discardReasons = new ArrayList<>();
        final List<DiscardReason> abandoned = new ArrayList<>();
        final List<DiscardReason> silent = new ArrayList<>();
        final List<UUID> retried = new ArrayList<>();
        final List<UUID> retryIds = new ArrayList<>();
        final List<GuardrailCoordinator.ReplayableInstruction> replayed = new ArrayList<>();
        final List<String> appErrors = new ArrayList<>();
        int freeTurns;
        int finished;
        int listening;

        @Override
        public SessionStatus status() {
            return status;
        }

        @Override
        public boolean terminated() {
            return terminated;
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
            return held;
        }

        @Override
        public long inputGroupOpenCount() {
            return inputOpens;
        }

        @Override
        public UUID practiceSpeechId() {
            return practiceId;
        }

        @Override
        public TranscriptWriter.Held holdAiRow() {
            if (failHold) {
                throw new IllegalStateException("합성 보관 실패");
            }
            return writer.aiHold();
        }

        @Override
        public boolean recheckAndSend(SegmentBuffer.Sealed sealed, TranscriptWriter.Held heldRow, boolean ownsLatency) {
            sent.add(sealed.transcript());
            writer.aiFinal(heldRow, sealed.transcript(), true, h.clock.now());
            return true;
        }

        @Override
        public void discardForRetry(SegmentBuffer.Sealed sealed, TranscriptWriter.Held heldRow, DiscardReason reason,
                boolean ownsLatency) {
            discardReasons.add(reason);
            writer.aiFinal(heldRow, sealed.transcript(), false, h.clock.now());
        }

        @Override
        public void abandon(SegmentBuffer.Sealed sealed, TranscriptWriter.Held heldRow, DiscardReason reason) {
            abandoned.add(reason);
            if (heldRow != null) {
                writer.aiFinal(heldRow, sealed.transcript(), false, h.clock.now());
            }
        }

        @Override
        public void discardSilent(SegmentBuffer.Sealed sealed, TranscriptWriter.Held heldRow, DiscardReason reasonOrNull) {
            if (reasonOrNull != null) {
                silent.add(reasonOrNull);
            }
            if (heldRow != null) {
                writer.aiFinal(heldRow, sealed.transcript(), false, h.clock.now());
            } else {
                writer.aiFinal(sealed.transcript(), false, h.clock.now());
            }
        }

        @Override
        public void finishAbandoned() {
            finished++;
        }

        @Override
        public void listeningAgain() {
            listening++;
        }

        @Override
        public boolean sendReplay(GuardrailCoordinator.ReplayableInstruction instruction, UUID retryId) {
            replayed.add(instruction);
            retryIds.add(retryId);
            if (retrySends) {
                retried.add(retryId);
            }
            return retrySends;
        }

        @Override
        public boolean sendRetryFreeTurn(UUID retryId) {
            freeTurns++;
            retryIds.add(retryId);
            if (retrySends) {
                retried.add(retryId);
            }
            return retrySends;
        }

        @Override
        public boolean postFromProvider(SessionMailbox.Message message) {
            posted.add(message);
            return true;
        }

        @Override
        public void appError(String reasonCode, EventCode.Level level) {
            appErrors.add(reasonCode);
        }

        @Override
        public void appError(RuntimeException error) {
            appErrors.add(error.getClass().getSimpleName());
        }
    }
}
