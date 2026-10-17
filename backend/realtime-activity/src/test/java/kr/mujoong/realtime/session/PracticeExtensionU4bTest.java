package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.contract.ws.message.VoiceSessionClosed;
import kr.mujoong.realtime.session.PracticeSessionExtension.ExtensionEvent;
import kr.mujoong.realtime.session.PracticeSessionExtension.Signal;
import kr.mujoong.realtime.testing.InMemoryTransitions;
import kr.mujoong.realtime.testing.RecordingPracticeExtension;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * U4b 연습 확장점 시험 (FR9.1 · BR10.1 ~ BR10.6 · FD-Q1 · FD-Q6 · R-10).
 *
 * <p>기본 무동작 구현으로 PRACTICE가 자유대화와 같은 전이를 밟는지, 대역 확장점의 LAST_GOAL · NEXT_GOAL 신호, post → 사건 전달, 정산 창 사건의
 * 신호 무시와 정산 시점 1회 순서, CLOSING의 출력 능력 거절, 확장점 예외 처리, 출력 능력(재생 · 시계 · 출력 막음 · 지시문)을 본다.
 */
@Tag("u4b")
class PracticeExtensionU4bTest {

    private final SessionHarness h = new SessionHarness();
    private final RecordingPracticeExtension extension = new RecordingPracticeExtension();
    private final List<Object> captured = new ArrayList<>();

    private void practiceSession() {
        h.activityType = ActivityType.PRACTICE;
        h.practiceJson = "{\"goals\":[{\"order\":1}]}";
        h.practice = extension.factory;
        h.settlement = (sessionId, practice, cutoffAt) -> captured.add(practice == null ? "null" : practice);
    }

    @Test
    void theDefaultNoOpMakesAPracticeSessionWalkTheSameTransitionsAsFreeTalk() {
        SessionHarness free = new SessionHarness();
        free.activate();
        endConfirmed(free);
        h.activityType = ActivityType.PRACTICE;
        h.activate();
        endConfirmed(h);

        assertThat(h.transitions.applied()).extracting(InMemoryTransitions.Applied::to)
                .isEqualTo(free.transitions.applied().stream().map(InMemoryTransitions.Applied::to).toList());
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.ENDED);
        assertThat(NoOpPracticeSessionExtension.INSTANCE.practiceRecord()).isNull();
    }

    @Test
    void theLastGoalSignalClosesWithChildStopAndPracticeCompleted() {
        practiceSession();
        extension.onUtterance = (utterance, out) -> Signal.LAST_GOAL;
        h.activate();
        assertThat(extension.calls).containsExactly("onActive");
        assertThat(extension.context.practiceJson()).contains("goals");

        h.childSays("i-1", "이렇게 말했어");

        ActivitySessionRow row = h.transitions.find(h.sessionId).orElseThrow();
        assertThat(row.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(row.endReason()).isEqualTo(EndReason.CHILD_STOP);
        assertThat(row.endDetail()).isEqualTo(TransitionTable.END_DETAIL_PRACTICE_COMPLETED);
        assertThat(h.link.sent(StopOutput.class)).extracting(StopOutput::reason)
                .containsExactly(StopOutput.Reason.CHILD_STOP);
    }

    @Test
    void theNextGoalSignalKeepsTheSessionGoing() {
        practiceSession();
        extension.onUtterance = (utterance, out) -> Signal.NEXT_GOAL;
        h.activate();

        h.childSays("i-1", "티라노가 제일 커");

        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(extension.utterances).singleElement().satisfies(utterance -> {
            assertThat(utterance.text()).isEqualTo("티라노가 제일 커");
            assertThat(utterance.seq()).isPositive();
        });
    }

    @Test
    void aPostedAsyncResultComesBackOnTheExecutorAndItsSignalIsHandled() throws Exception {
        practiceSession();
        extension.onEvent = (event, out) -> "judged".equals(event.name()) ? Signal.LAST_GOAL : Signal.NONE;
        h.activate();

        Thread worker = new Thread(() -> extension.output.post("judged", Map.of("result", "REACHED")));
        worker.start();
        worker.join();
        h.drain();

        assertThat(extension.events).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo(ExtensionEvent.Kind.ASYNC_RESULT);
            assertThat(event.value()).isEqualTo(Map.of("result", "REACHED"));
        });
        assertThat(h.transitions.find(h.sessionId).orElseThrow().endDetail())
                .isEqualTo(TransitionTable.END_DETAIL_PRACTICE_COMPLETED);
    }

    @Test
    void settleWindowEventsAreDeliveredWithSignalsIgnoredThenSettleRunsOnceBeforeTheRecord() {
        practiceSession();
        extension.onEvent = (event, out) -> Signal.LAST_GOAL;
        extension.record = Map.of("goals", List.of());
        h.activate();
        endConfirmed(h);
        int transitions = h.transitions.applied().size();

        extension.output.post("late-judgement", "UNCERTAIN");
        h.drain();
        assertThat(extension.calls).contains("onExtensionEvent").doesNotContain("onSettle");
        h.advance(SessionHarness.LIFECYCLE.recordSettle());

        assertThat(h.transitions.applied()).as("정산 창의 신호는 무시한다").hasSize(transitions);
        assertThat(extension.calls).endsWith("onExtensionEvent", "onSettle", "practiceRecord");
        assertThat(extension.end.eventsInSettleWindow()).isEqualTo(1);
        assertThat(extension.end.finalStatus()).isEqualTo(SessionStatus.ENDED);
        assertThat(captured).containsExactly(Map.of("goals", List.of()));
    }

    @Test
    void outputCapabilitiesAreRefusedOutsideActiveAndClosingEventsIgnoreTheirSignal() {
        practiceSession();
        extension.onEvent = (event, out) -> {
            out.playApproved(PlayApprovedContent.CLOSING);
            return Signal.LAST_GOAL;
        };
        h.activate();
        CompletableFuture<SessionMailbox.EndOutcome> stop = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        h.drain();
        int plays = h.link.sent(PlayApprovedContent.class).size();

        assertThat(extension.output.playApproved(PlayApprovedContent.CLOSING)).isFalse();
        assertThat(extension.output.requestResponse()).isFalse();
        assertThat(extension.output.holdOutput(false)).isFalse();
        assertThat(extension.output.instructProvider("다음 상황으로 넘어간다.")).isFalse();
        extension.output.post("in-closing", null);
        h.drain();

        assertThat(h.link.sent(PlayApprovedContent.class)).as("CLOSING 뒤 소리를 다시 내지 않는다(R-10)").hasSize(plays);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().endReason()).isEqualTo(EndReason.GUARDIAN_STOP);
        assertThat(events(SessionExecutor.EVENT_PRACTICE_EXTENSION_FAILED)).extracting(SessionEvent::getPayloadJson)
                .anyMatch(payload -> payload.contains("NOT_ACTIVE"));
    }

    @Test
    void anExtensionExceptionIsRecordedAndTheSessionContinuesLikeFreeTalkWithoutSettleOrPractice() {
        practiceSession();
        extension.throwOnUtterance = new IllegalStateException("시험용 확장점 예외");
        extension.record = Map.of("goals", List.of());
        h.activate();

        h.childSays("i-1", "공룡이 좋아");
        h.childSays("i-2", "무중아 그만할래");

        assertThat(h.executor.status()).as("자유대화처럼 종료 의도 판정이 이어진다").isEqualTo(SessionStatus.CLOSING);
        assertThat(extension.utterances).as("예외 뒤로는 부르지 않는다").hasSize(1);
        assertThat(events(SessionExecutor.EVENT_PRACTICE_EXTENSION_FAILED)).extracting(SessionEvent::getPayloadJson)
                .containsExactly("{\"reason\":\"EXCEPTION\"}");
        h.mailbox.offerGeneral(new SessionMailbox.VoiceClosed(h.link.connectionId(),
                new VoiceSessionClosed(h.clock.now(), h.clock.now()), h.clock.now()));
        h.drain();
        h.advance(SessionHarness.LIFECYCLE.recordSettle());
        assertThat(extension.calls).doesNotContain("onSettle");
        assertThat(captured).containsExactly("null");
    }

    @Test
    void capabilitiesPlayApprovedContentRunTimersThatPauseAndHoldOrInstructTheProvider() {
        practiceSession();
        h.extraApproved.put("PRACTICE_CUE", "이번엔 친구에게 인사해 볼까?");
        h.activate();
        h.link.touch(h.clock.now());

        assertThat(extension.output.playApproved("PRACTICE_CUE")).isTrue();
        PlayApprovedContent cue = h.link.sent(PlayApprovedContent.class).getLast();
        assertThat(h.store.transcripts).anySatisfy(row -> assertThat(row.text()).isEqualTo("이번엔 친구에게 인사해 볼까?"));
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(cue.playbackId(), PlaybackMark.Status.COMPLETED, null, h.clock.now(), null, null),
                h.clock.now()));
        h.drain();
        assertThat(extension.events).extracting(ExtensionEvent::kind).containsExactly(ExtensionEvent.Kind.PLAYBACK_DONE);

        assertThat(extension.output.schedule("first-response-wait", Duration.ofSeconds(20))).isTrue();
        // 10초 뒤 기기가 조용해져 일시정지 — 멈춘 동안 시계가 가지 않는다
        h.advance(Duration.ofSeconds(25));
        assertThat(h.executor.liveState().technicalPause()).isNotNull();
        assertThat(extension.events).hasSize(1);
        h.link.touch(h.clock.now());
        h.advanceAlive(Duration.ofSeconds(12));
        assertThat(extension.events).extracting(ExtensionEvent::name).contains("first-response-wait");

        assertThat(extension.output.holdOutput(true)).isTrue();
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("막힌 응답"));
        h.drain();
        assertThat(extension.output.instructProvider("목표 문장을 한 번 더 안내한다.")).isTrue();
        assertThat(extension.output.instructProvider(" ")).isFalse();
        h.launcher.runAll("provider-");
        // U11 Step 5: 응답 취소 레코드가 없어졌다(공급자 지원 목록 밖) — Step 8에서 출력 세대 올림 단언으로 바꾼다 [CANCEL-ASSERT]
        assertThat(h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class))
                .extracting(ProviderRequestFactory.InstructionsAppend::text).contains("목표 문장을 한 번 더 안내한다.");
        assertThat(extension.output.cancel("nothing")).isTrue();
        assertThat(extension.output.sessionId()).isEqualTo(h.sessionId);
    }

    @Test
    void aFreeTalkSessionNeverCreatesOrCallsTheExtension() {
        h.practice = extension.factory;
        h.activate();
        h.childSays("i-1", "공룡 좋아");

        assertThat(extension.created).isZero();
        assertThat(extension.calls).isEmpty();
    }

    private void endConfirmed(SessionHarness harness) {
        CompletableFuture<SessionMailbox.EndOutcome> stop = new CompletableFuture<>();
        harness.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP,
                stop));
        harness.drain();
        harness.mailbox.offerGeneral(new SessionMailbox.VoiceClosed(harness.link.connectionId(),
                new VoiceSessionClosed(harness.clock.now(), harness.clock.now()), harness.clock.now()));
        harness.drain();
    }

    private List<SessionEvent> events(String type) {
        h.recorder.tick();
        return h.store.sessionEvents.stream().filter(event -> event.getEventType().equals(type)).toList();
    }
}
