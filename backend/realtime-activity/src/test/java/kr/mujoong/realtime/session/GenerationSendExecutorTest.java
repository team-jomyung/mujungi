package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.dialog.DialogInstructions;
import kr.mujoong.realtime.dialog.InstructionTemplates;
import kr.mujoong.realtime.testing.RecordingPracticeExtension;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import kr.mujoong.realtime.voice.ProviderRequestFactory.InstructionsAppend;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 실행기의 출력 세대 연결 · 지시 송신 경로 (U11 FR12.5 · BR3.3 · BR3.4 · BR11.5 ~ BR11.7 · BR11.13 · 기능 설계 W8 · W9, <b>설계 개정 A2 ~ A5</b>).
 * 응답 id 없는 조각으로 자유대화 한 턴이 기기에 소리로 나가는지, 끼어들기 · 종료 의도 · 연습 폐기가 공급자 응답 취소 없이 세대로 조각을 버리는지, 서버
 * 발화가 모두 지시({@code append}) 하나로 시작해 한 직렬 큐를 지나고 새 말의 시작이 확인 사건 · 전사 시간선 · 오디오 시계 여유로 정해지는지 본다.
 * 공급자는 가짜 대역이고 시각은 수동 시계다(R-F1). 오디오 조각 4,800바이트 = 100ms, 시계 여유 600ms.
 */
@Tag("judgment-turn")
class GenerationSendExecutorTest {

    private static final Duration QUIET = Duration.ofMillis(800);
    private static final Duration RESPONSE_READY = Duration.ofSeconds(10);
    private static final long MARGIN_MS = 600L;

    private final SessionHarness h = new SessionHarness();

    // ------------------------------------------------------------------ 자유대화 한 턴

    @Test
    void afterTheJoinTheFirstResponseIsOneInstructionSentThroughTheSerialQueue() {
        h.activateUnsettled();

        assertThat(h.provider.last().requests(InstructionsAppend.class)).as("큐에 넣었을 뿐 아직 나가지 않았다").isEmpty();
        assertThat(h.launcher.names()).anyMatch(name -> name.startsWith("provider-respond-"));
        assertThat(h.launcher.runAll("provider-respond-")).isEqualTo(1);

        List<InstructionsAppend> sent = h.provider.last().requests(InstructionsAppend.class);
        assertThat(sent).singleElement().satisfies(append -> {
            assertThat(append.type()).isEqualTo("session.instructions.append");
            assertThat(append.eventId()).isEqualTo("mj-1");
            assertThat(append.delegationId()).isNull();
            assertThat(append.content()).isEqualTo(InstructionTemplates.ResponseStart.FIRST.content());
        });
    }

    @Test
    void aFreeTalkTurnWithoutAnyResponseIdReachesTheDeviceAsSound() {
        h.activate();

        h.speak("우와, 공룡 그림을 그렸구나!", 4_800);
        h.advance(QUIET);

        assertThat(h.link.outputs()).as("무음이 아니라 소리").hasSize(1);
        assertThat(h.link.outputs().get(0).totalMs()).isEqualTo(100L);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void sendsNeverIncludeAnyCancelOrResponseRequestAndOnlyTheFourObservedRecordTypes() {
        h.activate();
        UUID first = respond("공룡 이야기");
        mark(first, PlaybackMark.Status.STARTED, null);
        mark(first, PlaybackMark.Status.STOPPED, PlaybackMark.StopCause.LOCAL_VOICE_ACTIVITY);
        h.advance(QUIET);

        assertThat(h.launcher.names()).noneMatch(name -> name.contains("cancel") || name.contains("create"));
        assertThat(h.provider.last().requests()).allSatisfy(request -> assertThat(request.getClass().getSimpleName())
                .isIn("SessionStart", "InputAudioAppend", "InstructionsAppend", "SessionClose"));
        assertThat(java.util.Arrays.stream(kr.mujoong.realtime.voice.ProviderPort.ProviderSession.class.getMethods())
                .map(java.lang.reflect.Method::getName)).doesNotContain("createResponse");
    }

    // ------------------------------------------------------------------ 지시 뒤 새 말의 시작 (A4)

    @Test
    void afterAnInstructionStaleChunksAreDroppedUntilTheAckAndANewSpeechTranscriptAreSeen() {
        activateWhileTheProviderSpeaks();

        // 낡은 응답의 꼬리: 확인 사건 전의 음성 · 전사는 모두 버린다
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[9_600]));
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("낡은 말", 0L, 150L));
        h.drain();
        h.advance(QUIET);
        assertThat(h.link.outputs()).isEmpty();

        // 확인 사건이 와도 end_ms 앞 전사는 낡은 것이다
        h.provider.last().emit(new ProviderEvent.InstructionsAppended("mj-1", 100L, 300L));
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("그 꼬리", 250L, 290L));
        h.drain();
        h.advance(QUIET);
        assertThat(h.link.outputs()).isEmpty();

        // 확인 end_ms 이후 start_ms 전사 = 새 말의 시작(음성은 아직 낡은 꼬리여도 전사는 받는다). 오디오 시계가 시작 + 여유에 닿아야 음성을 받는다
        long clock = h.provider.last().audioClockMs();
        long newStart = Math.max(300L, clock);
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("새 말이야", newStart, newStart + 100L));
        long wait = newStart + MARGIN_MS - clock;
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[(int) (wait * 48L) - 960]));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[960]));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[4_800]));
        h.drain();
        h.advance(QUIET);

        assertThat(h.link.outputs()).as("여유 안의 음성(첫 낱말)은 잘리고 새 말의 나머지만 나간다").hasSize(1);
        assertThat(h.link.outputs().get(0).totalMs()).isEqualTo(100L);
    }

    @Test
    void theFirstChunkWhoseClockReachesNewStartPlusMarginStartsReceivingAndNotOneEarlier() {
        activateWhileTheProviderSpeaks();
        h.provider.last().emit(new ProviderEvent.InstructionsAppended("mj-1", 0L, 100L));
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("새 말", 100L, 400L));
        // 시계 0에서 700까지 채운다(시작 100 + 여유 600 = 700): 마지막 채움 조각의 시작은 680 < 700이라 버린다
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[(int) (700L * 48L) - 960]));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[960]));
        h.drain();
        h.advance(QUIET);
        assertThat(h.link.outputs()).as("음성이 여유 시각에 닿지 못한 채 끝났다 — 전사뿐인 구간은 폐기한다").isEmpty();
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "gate_empty")).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void aSecondInstructionRestartsTheWaitSoTheFirstOnesAckIsIgnored() {
        h.activate();
        h.provider.last().newSpeechMargin(null);
        RecordingPracticeExtension extension = new RecordingPracticeExtension();
        // 같은 세션에서 지시가 연달아 나가는 경우: 게이트 교정 대신 가장 단순한 두 번째 지시 — 연습 확장점 없이 활동 유형으로는 못 만드니 아래 시험이 맡는다
        assertThat(extension.calls).isEmpty();
    }

    // ------------------------------------------------------------------ 조용할 때 보낸 지시 (A4-b, 3회차 V2)

    @Test
    void aQuietSendKeepsTheWholeSentenceAlthoughItsFirstChunkStartsBeforeTheAckEnd() {
        h.activate();
        h.provider.last().newSpeechMargin(null);
        kr.mujoong.realtime.testing.FakeProviderPort.FakeSession provider = h.provider.last();

        // 실음성 실측 12.3의 시간표: 유휴 중 받은 지시에 공급자가 곧바로(+40ms) 말을 시작하고, 확인 사건은 그 뒤에 첫 조각보다 늦은 end_ms로 온다
        provider.emit(new ProviderEvent.OutputTranscriptDelta("공룡 그림이", 40L, 380L));
        provider.emit(new ProviderEvent.OutputAudioDelta(new byte[4_800]));
        provider.emit(new ProviderEvent.InstructionsAppended("mj-1", 20L, 700L));
        provider.emit(new ProviderEvent.OutputTranscriptDelta(" 점점 커지고 있네.", 420L, 1_400L));
        provider.emit(new ProviderEvent.OutputAudioDelta(new byte[4_800]));
        provider.emit(new ProviderEvent.OutputAudioDelta(new byte[4_800]));
        h.drain();
        h.advance(QUIET);

        assertThat(h.link.outputs()).singleElement()
                .satisfies(bundle -> assertThat(bundle.totalMs()).as("첫 음성 조각부터 여유 없이 받는다").isEqualTo(300L));
        assertThat(approvedAiLines()).as("첫머리(«공룡 그림이»)가 잘리지 않는다").containsExactly("공룡 그림이 점점 커지고 있네.");
        assertThat(newSpeechEvents()).singleElement().satisfies(payload -> assertThat(payload).contains("\"eventId\":\"mj-1\"",
                "\"start\":\"QUIET\"", "\"ackStartMs\":20", "\"ackEndMs\":700", "\"firstTranscriptStartMs\":40",
                "\"discardedTranscriptChunks\":0", "\"discardedAudioMs\":0", "\"outcome\":\"RECEIVING\"")
                .doesNotContain("공룡"));
    }

    @Test
    void aSpeakingSendStillDropsTheStaleTailBeforeTheAckEndAndTheAudioInsideTheMargin() {
        activateWhileTheProviderSpeaks();
        kr.mujoong.realtime.testing.FakeProviderPort.FakeSession provider = h.provider.last();

        // 말하는 중 받은 지시: 낡은 말이 1초쯤 더 이어진 뒤(확인 사건 end_ms 앞) 새 말이 쉼 없이 이어진다
        provider.emit(new ProviderEvent.OutputTranscriptDelta("그럼 연습 시작해", 0L, 300L));
        provider.emit(new ProviderEvent.OutputAudioDelta(new byte[14_400]));
        provider.emit(new ProviderEvent.InstructionsAppended("mj-1", 200L, 400L));
        provider.emit(new ProviderEvent.OutputTranscriptDelta(" 볼까?", 300L, 390L));
        provider.emit(new ProviderEvent.OutputTranscriptDelta("공룡 그림이 점점 커지고 있네.", 400L, 1_400L));
        // 오디오 시계 300에서 새 말 시작 400 + 여유 600 = 1000까지는 버린다
        provider.emit(new ProviderEvent.OutputAudioDelta(new byte[(int) ((1_000L - provider.audioClockMs()) * 48L)]));
        provider.emit(new ProviderEvent.OutputAudioDelta(new byte[4_800]));
        h.drain();
        h.advance(QUIET);

        assertThat(h.link.outputs()).singleElement().satisfies(bundle -> assertThat(bundle.totalMs()).isEqualTo(100L));
        assertThat(approvedAiLines()).as("낡은 꼬리는 새지 않는다").containsExactly("공룡 그림이 점점 커지고 있네.");
        assertThat(newSpeechEvents()).singleElement().satisfies(payload -> assertThat(payload).contains("\"start\":\"SPEAKING\"",
                "\"discardedTranscriptChunks\":2", "\"discardedTranscriptChars\":13", "\"discardedAudioMs\":1000",
                "\"audioFromClockMs\":1000", "\"outcome\":\"RECEIVING\""));
    }

    @Test
    void aPracticeLineSentWhileQuietIsHeardVerbatimInsteadOfBeingDiscardedAsAMismatch() {
        kr.mujoong.realtime.testing.ControllingPracticeExtension extension = new kr.mujoong.realtime.testing.ControllingPracticeExtension();
        h.activityType = ActivityType.PRACTICE;
        h.practice = extension.factory;
        h.activate();
        kr.mujoong.realtime.voice.PracticeSpeech opener = kr.mujoong.realtime.voice.PracticeSpeech.scenario(UUID.randomUUID(),
                UUID.randomUUID(), 1, "친구", "공룡 그림이 점점 커지고 있네.");

        assertThat(extension.out.speak(opener)).isTrue();
        h.settleSend();
        kr.mujoong.realtime.testing.FakeProviderPort.FakeSession provider = h.provider.last();
        provider.newSpeechMargin(null);
        provider.emit(new ProviderEvent.OutputTranscriptDelta("공룡 그림이", 40L, 380L));
        provider.emit(new ProviderEvent.OutputAudioDelta(new byte[4_800]));
        provider.emit(new ProviderEvent.InstructionsAppended("mj-1", 20L, 700L));
        provider.emit(new ProviderEvent.OutputTranscriptDelta(" 점점 커지고 있네.", 420L, 1_400L));
        provider.emit(new ProviderEvent.OutputAudioDelta(new byte[4_800]));
        h.drain();
        h.advance(QUIET);

        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "gate_pattern")).as("원문 불일치 폐기 없음").isZero();
        assertThat(h.link.outputs()).singleElement();
        UUID playback = h.link.outputs().getFirst().playbackId();
        mark(playback, PlaybackMark.Status.STARTED, null);
        mark(playback, PlaybackMark.Status.COMPLETED, null);
        assertThat(extension.endFacts(opener.correlationId()))
                .containsExactly(kr.mujoong.realtime.practicelegacy.PracticePorts.Playback.COMPLETED);
    }

    // ------------------------------------------------------------------ 확인 사건 누락 · 새 말 전사 누락 (4.6)

    @Test
    void withoutAnAckWithinResponseReadyAckMissingIsLoggedAndTheNextTranscriptAfterTheSendClockStartsTheNewSpeech() {
        activateWhileTheProviderSpeaks();

        h.advanceAlive(RESPONSE_READY);

        assertThat(h.logs.events(EventCode.APP_ERROR)).anyMatch(event ->
                "OutputAckMissing".equals(LogCapture.attrs(event).get("errorKind")));
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("새 말", 0L, 100L));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[(int) (MARGIN_MS * 48L)]));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[4_800]));
        h.drain();
        h.advance(QUIET);

        assertThat(h.link.outputs()).hasSize(1);
        assertThat(h.link.outputs().get(0).totalMs()).isEqualTo(100L);
    }

    @Test
    void ifNoNewSpeechShowsUpAfterTheAckMissingWaitNewSpeechNotObservedIsLoggedAndNothingIsHeard() {
        h.activate();
        h.provider.last().newSpeechMargin(null);

        h.advanceAlive(RESPONSE_READY);
        h.advanceAlive(RESPONSE_READY);

        List<String> kinds = h.logs.events(EventCode.APP_ERROR).stream().map(event -> (String) LogCapture.attrs(event).get("errorKind")).toList();
        assertThat(kinds).contains("OutputAckMissing", "OutputNewSpeechNotObserved");
        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.executor.status()).as("세션은 계속된다(아이가 아무 소리도 못 들을 뿐)").isEqualTo(SessionStatus.ACTIVE);
        // 이후 아이 말에 대한 자동 응답은 조용한 틈 뒤 다시 받는다
        h.advanceAlive(QUIET);
        h.speak("다음 자동 응답", 4_800);
        h.advance(QUIET);
        assertThat(h.link.outputs()).hasSize(1);
    }

    @Test
    void withAnAckButNoNewSpeechTheFirstResponseReadyExpiryAlreadyLogsNewSpeechNotObserved() {
        h.activate();
        h.provider.last().newSpeechMargin(null);
        h.provider.last().emit(new ProviderEvent.InstructionsAppended("mj-1", 0L, 100L));
        h.drain();

        h.advanceAlive(RESPONSE_READY);

        List<String> kinds = h.logs.events(EventCode.APP_ERROR).stream().map(event -> (String) LogCapture.attrs(event).get("errorKind")).toList();
        assertThat(kinds).containsExactly("OutputNewSpeechNotObserved");
    }

    // ------------------------------------------------------------------ 세대로 버리기 (A5)

    @Test
    void aBargeInDropsTheTailOfTheOldResponseAndAcceptsAFreshOneAfterTheQuietGap() {
        h.activate();
        UUID first = respond("공룡은 아주 오래전에 살았어");
        mark(first, PlaybackMark.Status.STARTED, null);
        mark(first, PlaybackMark.Status.STOPPED, PlaybackMark.StopCause.LOCAL_VOICE_ACTIVITY);

        // 공급자는 취소를 못 받아 옛 응답의 꼬리가 이어진다 — 800ms 조용할 때까지 모두 버린다
        h.speak("그리고 말이야", 4_800);
        h.advance(Duration.ofMillis(500));
        h.speak("티라노는 힘이 세", 4_800);
        h.advance(Duration.ofMillis(500));
        h.speak("아주 컸대", 4_800);
        h.advance(QUIET);
        assertThat(h.link.outputs()).as("버린 세대 조각 0건").hasSize(1);

        // 조용한 틈 뒤 처음 온 조각부터 새 응답
        h.speak("다시 이야기해 줄래", 4_800);
        h.advance(QUIET);
        assertThat(h.link.outputs()).hasSize(2);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void aStopIntentDropsTheChunksOfTheResponseBeingMade() {
        h.activate();
        long deviceGeneration = h.executor.outputGeneration();

        h.provider.last().emit(new ProviderEvent.InputTranscriptDone("i1", "무중아 그만할래", 0.95));
        h.drain();
        h.speak("그래 조금 더 해 볼까", 4_800);
        h.advance(QUIET);

        assertThat(h.executor.outputGeneration()).isGreaterThan(deviceGeneration);
        assertThat(h.link.outputs()).isEmpty();
    }

    @Test
    void chunksThatArriveWhileOutputIsHeldNeverOpenASegment() {
        h.boot();
        h.ackAndReady();
        // 참여 확인 중(WAITING_CHILD): 공급자가 자동으로 시작한 응답은 버린다
        h.speak("안녕", 4_800);
        h.advance(QUIET);

        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
    }

    @Test
    void joiningRaisesTheGenerationWithTheFirstInstructionSoTheTailOfAnAutoResponseIsDropped() {
        h.boot();
        h.ackAndReady();
        h.speak("안녕", 4_800);
        h.provider.last().newSpeechMargin(null);
        h.join();

        // 참여 뒤 첫 응답 지시가 세대를 올렸다: 같은 자동 응답의 꼬리가 와도 확인 사건 · 새 말 전사 전이라 기기로 가지 않는다
        h.speak("이어지는 말", 4_800);
        h.advance(Duration.ofMillis(300));
        assertThat(h.link.outputs()).isEmpty();
        h.advance(QUIET);
        h.launcher.runAll("provider-respond-");
        h.drain();
        assertThat(h.link.outputs()).isEmpty();
    }

    // ------------------------------------------------------------------ 직렬 송신 경로 (A2)

    @Test
    void serverInstructionsShareOneQueueInOrderAndOnlyOneSendTaskRuns() {
        RecordingPracticeExtension extension = new RecordingPracticeExtension();
        h.activityType = ActivityType.PRACTICE;
        h.practiceJson = "{\"goals\":[{\"order\":1}]}";
        h.practice = extension.factory;
        h.activateUnsettled();

        assertThat(extension.output.instructProvider("목표 문장을 한 번 더 안내한다.")).isTrue();
        assertThat(extension.output.requestResponse()).isTrue();

        assertThat(h.launcher.names().stream().filter(name -> name.startsWith("provider-")).toList())
                .as("큐 작업은 하나만 띄운다").hasSize(1);
        h.launcher.runAll("provider-");
        h.drain();

        List<InstructionsAppend> sent = h.provider.last().requests(InstructionsAppend.class);
        assertThat(sent).extracting(InstructionsAppend::content).containsExactly(
                InstructionTemplates.ResponseStart.FIRST.content(),
                "목표 문장을 한 번 더 안내한다. " + InstructionTemplates.SCOPE_CLOSE,
                InstructionTemplates.ResponseStart.CONTINUE.content());
        assertThat(sent).extracting(InstructionsAppend::eventId).containsExactly("mj-1", "mj-2", "mj-3");
        assertThat(sent).allSatisfy(append -> assertThat(append.delegationId()).isNull());
    }

    @Test
    void aPracticeSpeechIsOneInstructionThatClosesItsScopeAndNothingGoesOutBeforeTheNewSpeech() {
        RecordingPracticeExtension extension = new RecordingPracticeExtension();
        h.activityType = ActivityType.PRACTICE;
        h.practiceJson = "{\"goals\":[{\"order\":1}]}";
        h.practice = extension.factory;
        h.activate();
        kr.mujoong.realtime.voice.PracticeSpeech speech = new kr.mujoong.realtime.voice.PracticeSpeech(UUID.randomUUID(),
                UUID.randomUUID(), 1, kr.mujoong.realtime.practicelegacy.PracticePorts.OutputKind.SCENARIO, "친구",
                "방금 말을 다시 들려줄게");
        int before = h.provider.last().requests(InstructionsAppend.class).size();

        assertThat(extension.output.speak(speech)).isTrue();
        h.drain();
        h.launcher.runAll("provider-practice-speech-");
        h.drain();

        List<InstructionsAppend> sent = h.provider.last().requests(InstructionsAppend.class);
        assertThat(sent).hasSize(before + 1);
        assertThat(sent.get(before).content()).contains("방금 말을 다시 들려줄게").endsWith(InstructionTemplates.SCOPE_CLOSE);
        assertThat(h.link.outputs()).isEmpty();
    }

    @Test
    void sessionEndDropsTheQueuedInstructionsSoTheQueueIsNeverHeld() {
        h.activateUnsettled();
        java.util.concurrent.CompletableFuture<SessionMailbox.EndOutcome> stop = new java.util.concurrent.CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(),
                kr.mujoong.contract.status.EndRequestReason.CORE_ABANDONED, stop));
        h.drain();

        assertThat(h.executor.isTerminated() || h.executor.status().isTerminal()).isTrue();
        h.launcher.runAll("provider-respond-");
        assertThat(h.provider.last().requests(InstructionsAppend.class)).isEmpty();
    }

    // ------------------------------------------------------------------ 교정 연쇄 방지

    @Test
    void atMostTwoCorrectionsAreSentInARowWithoutAPassedSegmentBecauseACorrectionStartsNewSpeech() {
        h.activate();

        h.speak("진단 결과는 이래", 4_800);
        h.advance(QUIET);
        assertThat(h.link.outputs()).isEmpty();
        h.launcher.runAll("provider-correct-");
        h.drain();
        List<InstructionsAppend> afterFirst = h.provider.last().requests(InstructionsAppend.class);
        assertThat(afterFirst).hasSize(2);
        assertThat(afterFirst.get(1).content()).isEqualTo(InstructionTemplates.correctionContent(DialogInstructions.Correction.DIAGNOSIS));

        // 교정 지시에 대한 새 말도 금지 표현이다 — 폐기하고 한 번 더 교정한다
        h.speak("치료가 필요해요", 4_800);
        h.advance(QUIET);
        h.launcher.runAll("provider-correct-");
        h.drain();
        assertThat(h.provider.last().requests(InstructionsAppend.class)).hasSize(3);

        // 그 새 말도 또 금지 표현이다 — 폐기하되 교정은 연달아 두 번까지다(끝없는 연쇄 방지)
        h.speak("그건 비정상이야", 4_800);
        h.advance(QUIET);
        h.launcher.runAll("provider-correct-");
        h.drain();

        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.provider.last().requests(InstructionsAppend.class)).as("교정 연쇄 상한").hasSize(3);
        // 통과한 구간이 있으면 다시 센다
        h.speak("공룡 좋아하는구나", 4_800);
        h.advance(QUIET);
        assertThat(h.link.outputs()).hasSize(1);
    }

    // ------------------------------------------------------------------ 입력 전사 묶음 (A7)

    @Test
    void inputTranscriptDeltasAreGroupedAndConfirmedOnceAfterTheInputTranscriptQuietTime() {
        h.activate();
        long quietMs = h.settings.inputTranscriptQuiet().toMillis();

        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta(null, "공룡이"));
        h.drain();
        h.advance(Duration.ofMillis(quietMs - 100));
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta(null, " 좋아"));
        h.drain();
        h.advance(Duration.ofMillis(quietMs - 100));
        assertThat(childFinals()).as("마지막 조각 뒤 아직 조용 시간 전이다").isEmpty();

        h.advance(Duration.ofMillis(100));

        assertThat(childFinals()).singleElement().isEqualTo("공룡이 좋아");
    }

    @Test
    void aLongerPauseSplitsTwoUtterancesAndAnExplicitDoneConsumesTheGroupWithoutADuplicate() {
        h.activate();
        long quietMs = h.settings.inputTranscriptQuiet().toMillis();

        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta(null, "하나"));
        h.drain();
        h.advance(Duration.ofMillis(quietMs));
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta(null, "둘"));
        h.drain();
        h.advance(Duration.ofMillis(quietMs));
        assertThat(childFinals()).containsExactly("하나", "둘");

        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("x1", "셋"));
        h.provider.last().emit(new ProviderEvent.InputTranscriptDone("x1", "셋", null));
        h.drain();
        h.advance(Duration.ofMillis(quietMs * 2));
        assertThat(childFinals()).as("대신 보낸 확정이 묶음을 소비해 한 번만 확정된다").containsExactly("하나", "둘", "셋");
    }

    @Test
    void theJoinReplyComesFromGroupedDeltasWhileWaitingForTheChild() {
        h.boot();
        h.ackAndReady();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);

        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta(null, "응"));
        h.drain();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.WAITING_CHILD);
        h.advance(h.settings.inputTranscriptQuiet());

        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void aStopIntentSpokenAsDeltasIsJudgedFromTheGroupedTranscript() {
        h.activate();

        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta(null, "무중아 "));
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta(null, "그만할래"));
        h.drain();
        h.advance(h.settings.inputTranscriptQuiet());

        assertThat(h.executor.status()).isEqualTo(SessionStatus.CLOSING);
    }

    // ------------------------------------------------------------------ 도움

    /**
     * 말하는 중 보낸 지시(설계 개정 A4 그대로)로 ACTIVE까지 간다: 참여 대답에 공급자가 자동으로 응답하던 중(WAITING_CHILD라 보류로 버린 전사 조각)
     * 참여 뒤 첫 응답 지시가 나간다. 조용할 때 보낸 지시는 A4-b가 맡는다(3회차 V2). 시험이 시간표를 직접 만들도록 가짜 공급자의 응답 흉내는 끈다.
     */
    private void activateWhileTheProviderSpeaks() {
        h.boot();
        h.ackAndReady();
        h.provider.last().newSpeechMargin(null);
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("좋아, 그럼", 0L, 0L));
        h.drain();
        h.join();
        h.settleSend();
    }

    /** 보호자 화면에 승인된 AI 확정 행의 글(전사 순서대로). */
    private List<String> approvedAiLines() {
        return h.store.transcripts.stream()
                .filter(entry -> entry.speaker() == kr.mujoong.realtime.log.TranscriptEntry.Speaker.AI)
                .filter(entry -> entry.assemblyStatus() == kr.mujoong.realtime.log.TranscriptEntry.AssemblyStatus.FINAL)
                .filter(kr.mujoong.realtime.log.TranscriptEntry::approvedForDisplay)
                .map(kr.mujoong.realtime.log.TranscriptEntry::text).toList();
    }

    /** 세션 사건 OUTPUT_NEW_SPEECH의 payload(기록기를 비운 뒤). */
    private List<String> newSpeechEvents() {
        h.recorder.tick();
        return h.store.sessionEvents.stream().filter(event -> SessionExecutor.EVENT_OUTPUT_NEW_SPEECH.equals(event.getEventType()))
                .map(SessionEvent::getPayloadJson).toList();
    }

    private List<String> childFinals() {
        return h.store.transcripts.stream()
                .filter(entry -> entry.speaker() == kr.mujoong.realtime.log.TranscriptEntry.Speaker.CHILD)
                .filter(entry -> entry.assemblyStatus() == kr.mujoong.realtime.log.TranscriptEntry.AssemblyStatus.FINAL)
                .map(kr.mujoong.realtime.log.TranscriptEntry::text).toList();
    }

    /** 응답 한 개를 흘리고 조용함으로 봉인해 기기로 보낸다. 재생 id를 돌려준다. */
    private UUID respond(String text) {
        int before = h.link.outputs().size();
        h.speak(text, 48_000);
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
