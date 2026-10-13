package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.dialog.DialogInstructions;
import kr.mujoong.realtime.testing.FakeDeviceLink;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PR #2 2차 리뷰 반영 시험(docs/units/u3/report.md 12절). 건마다 먼저 실패하던 재현 시험이다.
 *
 * <ul>
 *   <li>2 생각 중 멈춤: 종료 의도를 푼 발화에는 '생각 중'을 보내지 않는다(그 발화의 응답은 이미 취소됐다).
 *   <li>4 재생 추적 한 칸: ACK 없는 재생 명령은 마지막 하나만 다시 보내고 추적한다.
 *   <li>5 핸드오버 경주: 다른 세션에 묶인 연결은 대기로 돌려보내지 않는다 · 정리는 다른 세션의 묶음을 풀지 않는다.
 *   <li>6 해제 조건: 종료 의도는 적극적인 계속 표현으로만 풀린다.
 * </ul>
 */
@Tag("u3")
class SessionExecutorReview2U3Test {

    private final SessionHarness h = new SessionHarness();

    // ------------------------------------------------------------------ 2 생각 중 멈춤

    /** U4b(FR11.3 · FD-Q3): 종료 의도는 곧바로 CLOSING이고 해제 경로가 없다 — '생각 중'을 남기지 않고 마무리 표시로 간다. */
    @Test
    void liftingTheStopIntentDoesNotLeaveTheDeviceThinking() {
        h.activate();
        done("i1", "그만할래");
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("r1", "그래 더 하자"));
        h.provider.last().emit(new ProviderEvent.SpeechStopped());
        h.drain();

        done("i2", "아니 더 얘기하자");
        h.provider.last().emit(new ProviderEvent.OutputDone("r1"));
        h.drain();

        assertThat(eventTypes()).doesNotContain(SessionExecutor.EVENT_CHILD_STOP_LIFTED);
        assertThat(views()).as("기다릴 응답이 없으니 '생각 중'을 보내지 않는다").doesNotContain(View.THINKING);
        assertThat(h.executor.view()).isEqualTo(View.CLOSING);
    }

    // ------------------------------------------------------------------ 6 해제 조건

    @Test
    void reportedSpeechTopicRefusalOrOrdinaryTalkAfterAStopIntentDoesNotLiftIt() {
        h.activate();
        done("i1", "이제 그만할래");
        done("i2", "엄마가 그만하라고 했어");
        done("i3", "공룡 얘기 그만");
        done("i4", "공룡이 좋아");

        assertThat(eventTypes()).doesNotContain(SessionExecutor.EVENT_CHILD_STOP_LIFTED);
        emitComplete("r1", "응 그래");
        h.advance(Duration.ofMillis(800));
        assertThat(h.link.outputs()).isEmpty();

        // U4b(FR11.3 · FD-Q3): 첫 종료 의도에서 이미 CLOSING이라 계속 표현도 출력을 다시 열지 않는다
        done("i5", "그만 안 할래 계속할래");
        assertThat(eventTypes()).doesNotContain(SessionExecutor.EVENT_CHILD_STOP_LIFTED);
        assertThat(h.executor.status()).isEqualTo(kr.mujoong.contract.status.SessionStatus.CLOSING);
    }

    // ------------------------------------------------------------------ 4 재생 추적 한 칸

    @Test
    void onlyTheLatestUnackedPlaybackIsResentAndItsEndClearsTheTracking() {
        h.activate();
        emitComplete("r1", "진단해 보면 이래");
        h.advance(Duration.ofMillis(800));
        // 첫 대기 문구가 20초 만기로 잊힌 뒤 둘째 대기 문구 — 둘 다 ACK가 없다
        h.advance(Duration.ofSeconds(21));
        h.link.touch(h.clock.now());
        emitComplete("r2", "진단 결과야");
        h.advance(Duration.ofMillis(800));
        List<PlayApprovedContent> fillers = h.link.sent(PlayApprovedContent.class);
        assertThat(fillers).hasSize(2);

        FakeDeviceLink fresh = relink(true);

        assertThat(fresh.sent(PlayApprovedContent.class)).extracting(PlayApprovedContent::playbackId)
                .containsExactly(fillers.get(1).playbackId());
        assertThat(h.executor.pendingCommandCount()).as("앞 재생 명령은 미확인 목록에서 빠진다").isEqualTo(1);

        // 다시 보낸 재생의 끝이 추적을 지워 다음 폐기에 대기 문구가 나간다
        mark(fresh, fillers.get(1).playbackId(), PlaybackMark.Status.COMPLETED);
        emitComplete("r3", "진단 결과야");
        h.advance(Duration.ofMillis(800));
        assertThat(fresh.sent(PlayApprovedContent.class)).hasSize(2);
    }

    // ------------------------------------------------------------------ 5 핸드오버 경주

    @Test
    void standDownLeavesALinkAlreadyBoundToAnotherSessionAlone() {
        FakeDeviceLink taken = new FakeDeviceLink(h.deviceId, h.clock.now());
        taken.bindSession(UUID.randomUUID());

        SessionExecutor.standDown(taken, h.sessionId, SessionStatus.ABORTED, true);

        assertThat(taken.sent()).isEmpty();
    }

    @Test
    void theEndOfASessionDoesNotUnbindALinkThatANewSessionAlreadyTook() {
        h.activate();
        UUID next = UUID.randomUUID();
        // 끝내기 직전, 같은 기기의 새 세션이 이 연결을 묶었다
        h.link.bindSession(next);
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.CORE_ABANDONED,
                new CompletableFuture<>()));
        h.drain();

        assertThat(h.executor.isTerminated()).isTrue();
        assertThat(h.link.boundSession()).isEqualTo(next);
    }

    // ------------------------------------------------------------------ 9 조사 중 찾음: 교정 범주

    /**
     * 응답의 전사와 음성이 따로 와서 조용 시간이 그 사이에 끝나면 전사만 든 구간이 먼저 '미완'으로 폐기된다. 그 구간에 금지 패턴이 있으면 응답당
     * 한 번뿐인 교정은 '짧게 완결' 대신 그 범주의 문장이어야 한다(SessionVoiceU3IT 흔들림의 원인).
     */
    @Test
    void aDiscardedSegmentHoldingAForbiddenPatternGetsThatCategorysCorrectionWhateverTheDiscardReason() {
        h.activate();
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("r1", "진단 결과는 이래"));
        h.drain();
        h.advance(Duration.ofMillis(800));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta("r1", new byte[4_800]));
        h.provider.last().emit(new ProviderEvent.OutputDone("r1"));
        h.drain();
        h.advance(Duration.ofMillis(800));

        assertThat(h.link.outputs()).isEmpty();
        h.launcher.runAll("provider-correct-");
        assertThat(h.provider.last().requests(ProviderRequestFactory.InstructionsCorrect.class))
                .extracting(ProviderRequestFactory.InstructionsCorrect::text)
                .containsExactly(DialogInstructions.Correction.DIAGNOSIS.text());
    }

    // ------------------------------------------------------------------ 보조

    private FakeDeviceLink relink(boolean resumed) {
        FakeDeviceLink fresh = new FakeDeviceLink(h.deviceId, h.clock.now());
        h.mailbox.putSafety(new SessionMailbox.ConnectionLost(h.link.connectionId()));
        h.mailbox.putSafety(new SessionMailbox.LinkReplaced(fresh, resumed, h.clock.now()));
        h.drain();
        return fresh;
    }

    private void mark(FakeDeviceLink link, UUID playbackId, PlaybackMark.Status status) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(link.connectionId(),
                new PlaybackMark(playbackId, status, null, h.clock.now(), null, null), h.clock.now()));
        h.drain();
    }

    private void emitComplete(String responseId, String text) {
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta(responseId, text));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(responseId, new byte[4_800]));
        h.provider.last().emit(new ProviderEvent.OutputDone(responseId));
        h.drain();
    }

    private void done(String itemId, String transcript) {
        h.provider.last().emit(new ProviderEvent.InputTranscriptDone(itemId, transcript, 0.95));
        h.drain();
    }

    private List<View> views() {
        return h.link.sent(SessionState.class).stream().map(SessionState::view).toList();
    }

    private List<String> eventTypes() {
        h.recorder.tick();
        return h.store.sessionEvents.stream().map(SessionEvent::getEventType).toList();
    }
}
