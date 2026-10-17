package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.realtime.testing.RecordingPracticeExtension;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 실행기의 출력 세대 연결 · 직렬 송신 경로 (U11 FR12.5 · BR3.3 · BR3.4 · BR11.4 ~ BR11.7 · BR11.13 · 기능 설계 W8 · W9). 응답 id 없는 조각으로
 * 자유대화 한 턴이 기기에 소리로 나가는지, 끼어들기 · 종료 의도 · 연습 폐기가 공급자 응답 취소 없이 세대로 조각을 버리는지, 서버 송신이 한 큐에서
 * 끊김 허가를 받아 append → 표시 → create 순서로 나가는지 본다. 공급자는 가짜 대역이고 시각은 수동 시계다(R-F1).
 */
@Tag("u11")
class GenerationSendExecutorTest {

    private static final Duration QUIET = Duration.ofMillis(800);

    private final SessionHarness h = new SessionHarness();

    // ------------------------------------------------------------------ 자유대화 한 턴

    @Test
    void afterTheJoinTheFirstResponseRequestWaitsForAGapAndThenGoesOutOnce() {
        h.activateUnsettled();

        assertThat(h.provider.last().requests(ProviderRequestFactory.ResponseCreate.class)).as("끊김 확인 전 송신 0건").isEmpty();
        assertThat(h.launcher.names()).anyMatch(name -> name.startsWith("provider-create-"));
        h.advance(QUIET);
        assertThat(h.launcher.runAll("provider-create-")).isEqualTo(1);
        h.drain();

        assertThat(h.provider.last().requests(ProviderRequestFactory.ResponseCreate.class)).hasSize(1);
    }

    @Test
    void aFreeTalkTurnWithoutAnyResponseIdReachesTheDeviceAsSound() {
        settleFirstResponse();

        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("우와, 공룡 그림을 그렸구나!"));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[4_800]));
        h.drain();
        h.advance(QUIET);

        assertThat(h.link.outputs()).as("무음이 아니라 소리").hasSize(1);
        assertThat(h.link.outputs().get(0).totalMs()).isEqualTo(100L);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void sendsNeverIncludeAnyCancelAndOnlyTheFiveObservedRecordTypes() {
        settleFirstResponse();
        UUID first = respond("공룡 이야기");
        mark(first, PlaybackMark.Status.STARTED, null);
        mark(first, PlaybackMark.Status.STOPPED, PlaybackMark.StopCause.LOCAL_VOICE_ACTIVITY);
        h.advance(QUIET);

        assertThat(h.launcher.names()).noneMatch(name -> name.contains("cancel"));
        assertThat(h.provider.last().requests()).allSatisfy(request -> assertThat(request.getClass().getSimpleName())
                .isIn("SessionStart", "InputAudioAppend", "InstructionsAppend", "ResponseCreate", "SessionClose"));
    }

    // ------------------------------------------------------------------ 세대로 버리기

    @Test
    void aBargeInDropsTheTailOfTheOldResponseAndAcceptsAFreshOneAfterAGap() {
        settleFirstResponse();
        UUID first = respond("공룡은 아주 오래전에 살았어");
        mark(first, PlaybackMark.Status.STARTED, null);
        mark(first, PlaybackMark.Status.STOPPED, PlaybackMark.StopCause.LOCAL_VOICE_ACTIVITY);

        // 공급자는 취소를 못 받아 옛 응답의 꼬리가 이어진다 — 끊길 때까지 모두 버린다
        emitOutput("그리고 말이야", 4_800);
        h.advance(Duration.ofMillis(500));
        emitOutput("티라노는 힘이 세", 4_800);
        h.advance(Duration.ofMillis(500));
        emitOutput("아주 컸대", 4_800);
        h.advance(QUIET);
        assertThat(h.link.outputs()).as("버린 세대 조각 0건").hasSize(1);

        // 끊긴 뒤 처음 온 조각부터 새 응답
        emitOutput("다시 이야기해 줄래", 4_800);
        h.advance(QUIET);
        assertThat(h.link.outputs()).hasSize(2);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void aStopIntentDropsTheChunksOfTheResponseBeingMade() {
        settleFirstResponse();
        long deviceGeneration = h.executor.outputGeneration();

        h.provider.last().emit(new ProviderEvent.InputTranscriptDone("i1", "무중아 그만할래", 0.95));
        h.drain();
        emitOutput("그래 조금 더 해 볼까", 4_800);
        h.advance(QUIET);

        assertThat(h.executor.outputGeneration()).isGreaterThan(deviceGeneration);
        assertThat(h.link.outputs()).isEmpty();
    }

    @Test
    void chunksThatArriveWhileOutputIsHeldNeverOpenASegment() {
        h.boot();
        h.ackAndReady();
        // 참여 확인 중(WAITING_CHILD): 공급자가 자동으로 시작한 응답은 버린다
        emitOutput("안녕", 4_800);
        h.advance(QUIET);

        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
    }

    @Test
    void releasingTheHoldRaisesTheGenerationSoTheTailOfAnAutoResponseIsDropped() {
        h.boot();
        h.ackAndReady();
        emitOutput("안녕", 4_800);
        h.join();

        // 보류가 풀린 직후 같은 자동 응답의 꼬리가 와도 기기로 가지 않는다(세대가 올랐다)
        emitOutput("이어지는 말", 4_800);
        h.advance(Duration.ofMillis(300));
        assertThat(h.link.outputs()).isEmpty();
        h.advance(QUIET);
        h.launcher.runAll("provider-create-");
        h.drain();
        assertThat(h.link.outputs()).isEmpty();
    }

    // ------------------------------------------------------------------ 직렬 송신 경로

    @Test
    void serverSendsShareOneQueueSoOnlyOneSendTaskRunsAndAnAbandonedWaiterNeverHoldsIt() {
        RecordingPracticeExtension extension = new RecordingPracticeExtension();
        h.activityType = ActivityType.PRACTICE;
        h.practiceJson = "{\"goals\":[{\"order\":1}]}";
        h.practice = extension.factory;
        h.activateUnsettled();
        // 참여 직후 응답 요청 하나가 끊김을 기다리는 중이다
        assertThat(extension.output.instructProvider("목표 문장을 한 번 더 안내한다.")).isTrue();
        assertThat(extension.output.requestResponse()).isTrue();

        assertThat(h.launcher.names().stream().filter(name -> name.startsWith("provider-")).toList())
                .as("큐 작업은 하나만 띄운다").hasSize(1);
        h.advance(QUIET);
        h.launcher.runAll("provider-");
        h.drain();

        // 세대가 바뀐 앞 요청은 보내지 못하고 큐를 놓았다. 지시문 추가는 순서대로, 새 요청은 허가를 받고 나갔다
        assertThat(h.provider.last().requests().stream()
                .filter(request -> request instanceof ProviderRequestFactory.InstructionsAppend
                        || request instanceof ProviderRequestFactory.ResponseCreate)
                .map(request -> request.getClass().getSimpleName()).toList())
                .containsExactly("InstructionsAppend", "ResponseCreate");
    }

    @Test
    void anInstructionIsAppendedBeforeTheResponseIsCreatedAndNothingGoesOutBeforeTheGap() {
        RecordingPracticeExtension extension = new RecordingPracticeExtension();
        h.activityType = ActivityType.PRACTICE;
        h.practiceJson = "{\"goals\":[{\"order\":1}]}";
        h.practice = extension.factory;
        h.activateUnsettled();
        h.advance(QUIET);
        h.launcher.runAll("provider-create-");
        h.drain();
        int sentBefore = h.provider.last().requests().size();

        kr.mujoong.realtime.voice.PracticeSpeech speech = new kr.mujoong.realtime.voice.PracticeSpeech(UUID.randomUUID(),
                UUID.randomUUID(), 1, kr.mujoong.realtime.practice.PracticePorts.OutputKind.SCENARIO, "친구",
                "방금 말을 다시 들려줄게");
        assertThat(extension.output.speak(speech)).isTrue();
        h.drain();
        assertThat(h.provider.last().requests()).as("끊김 전 송신 0건").hasSize(sentBefore);

        h.advance(QUIET);
        h.launcher.runAll("provider-practice-speech-");
        h.drain();

        List<Object> sent = h.provider.last().requests().subList(sentBefore, h.provider.last().requests().size());
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0)).isInstanceOf(ProviderRequestFactory.InstructionsAppend.class);
        assertThat(sent.get(1)).isInstanceOf(ProviderRequestFactory.ResponseCreate.class);
    }

    @Test
    void aChunkAfterThePermitButBeforeTheSendVoidsThePermitAndTheSendWaitsForANewGap() {
        h.activateUnsettled();
        h.advance(QUIET);
        // 허가가 났지만 큐 작업이 돌기 전에 같은 응답의 꼬리 조각이 왔다
        emitOutput("꼬리", 4_800);

        assertThat(h.launcher.runAll("provider-create-")).isEqualTo(1);
        h.drain();
        assertThat(h.provider.last().requests(ProviderRequestFactory.ResponseCreate.class)).as("허가 무효 → 보내지 않음").isEmpty();
        assertThat(h.launcher.names()).as("같은 세대에서 새 끊김을 기다리도록 다시 큐에 넣었다")
                .anyMatch(name -> name.startsWith("provider-create-"));

        h.advance(QUIET);
        h.launcher.runAll("provider-create-");
        h.drain();
        assertThat(h.provider.last().requests(ProviderRequestFactory.ResponseCreate.class)).hasSize(1);
        assertThat(h.link.outputs()).as("버린 꼬리는 되살리지 않는다").isEmpty();
    }

    @Test
    void theGapWaitLimitStopsTheSendAndTheNextSendIsNotBlocked() {
        RecordingPracticeExtension extension = new RecordingPracticeExtension();
        h.activityType = ActivityType.PRACTICE;
        h.practiceJson = "{\"goals\":[{\"order\":1}]}";
        h.practice = extension.factory;
        h.activateUnsettled();
        // 자동 응답이 끊기지 않는다: 400ms마다 조각이 계속 와서 끊김을 못 본다
        for (int i = 0; i < 27; i++) {
            emitOutput("끝나지 않는 말" + i, 960);
            h.link.touch(h.clock.now());
            h.advance(Duration.ofMillis(400));
        }

        assertThat(h.launcher.runAll("provider-create-")).isEqualTo(1);
        h.drain();
        assertThat(h.provider.last().requests(ProviderRequestFactory.ResponseCreate.class)).as("끊김을 못 봤으니 보내지 않는다").isEmpty();
        assertThat(h.logs.events(EventCode.APP_ERROR)).anyMatch(event ->
                "OutputGapNotObserved".equals(LogCapture.attrs(event).get("errorKind")));

        // 큐를 영영 쥐지 않았다: 다음 송신은 끊긴 뒤 허가를 받고 나간다
        assertThat(extension.output.requestResponse()).isTrue();
        h.link.touch(h.clock.now());
        h.advance(QUIET);
        h.launcher.runAll("provider-create-");
        h.drain();
        assertThat(h.provider.last().requests(ProviderRequestFactory.ResponseCreate.class)).hasSize(1);
    }

    @Test
    void theSentMarkIsProcessedBeforeTheFirstChunkRightAfterTheCreate() {
        settleFirstResponseWithoutDraining();

        // create 직후 첫 조각이 곧바로 온다 — 편지함에는 '보내는 중' 표시가 그 조각보다 앞에 있다
        assertThat(h.mailbox.generalSize()).as("표시가 create보다 먼저 들어가 있다").isEqualTo(1);
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("바로 온 첫 말"));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[4_800]));
        h.drain();
        h.advance(QUIET);

        assertThat(h.link.outputs()).as("표시를 먼저 처리했으니 첫 조각부터 지시 응답 구간이다").hasSize(1);
    }

    @Test
    void sessionEndGivesUpTheWaitingSendSoTheQueueIsNeverHeld() {
        h.activateUnsettled();
        java.util.concurrent.CompletableFuture<SessionMailbox.EndOutcome> stop = new java.util.concurrent.CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(),
                kr.mujoong.contract.status.EndRequestReason.CORE_ABANDONED, stop));
        h.drain();

        // 세션이 끝났다: 허가 칸이 거부로 채워져 기다리던 작업이 바로 끝난다(시험 장치가 5초 안에 못 끝나면 실패시킨다)
        assertThat(h.executor.isTerminated() || h.executor.status().isTerminal()).isTrue();
        h.launcher.runAll("provider-create-");
        assertThat(h.provider.last().requests(ProviderRequestFactory.ResponseCreate.class)).isEmpty();
    }

    // ------------------------------------------------------------------ 도움

    /** 참여 뒤 첫 응답 요청이 끊김을 확인하고 나가게 한 다음 '보내는 중' 표시까지 처리한다. */
    private void settleFirstResponse() {
        h.activateUnsettled();
        h.advance(QUIET);
        assertThat(h.launcher.runAll("provider-create-")).isEqualTo(1);
        h.drain();
        assertThat(h.provider.last().requests(ProviderRequestFactory.ResponseCreate.class)).hasSize(1);
    }

    /** 위와 같되 '보내는 중' 표시를 편지함에 둔 채 처리하지 않는다. */
    private void settleFirstResponseWithoutDraining() {
        h.activateUnsettled();
        h.advance(QUIET);
        assertThat(h.launcher.runAll("provider-create-")).isEqualTo(1);
    }

    private void emitOutput(String text, int bytes) {
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta(text));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[bytes]));
        h.drain();
    }

    /** 응답 한 개를 흘리고 끊김으로 봉인해 기기로 보낸다. 재생 id를 돌려준다. */
    private UUID respond(String text) {
        int before = h.link.outputs().size();
        emitOutput(text, 48_000);
        h.advance(QUIET);
        assertThat(h.link.outputs()).hasSize(before + 1);
        return h.link.outputs().get(before).playbackId();
    }

    private void mark(UUID playbackId, PlaybackMark.Status status, PlaybackMark.StopCause cause) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(playbackId, status, null, h.clock.now(), cause, null), h.clock.now()));
        h.drain();
    }
}
