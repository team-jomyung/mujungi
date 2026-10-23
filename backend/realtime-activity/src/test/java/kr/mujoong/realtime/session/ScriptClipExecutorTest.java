package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.OutputBegin;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.dialog.InstructionTemplates;
import kr.mujoong.realtime.gateway.OutputBundle;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.practicelegacy.PracticePorts.Playback;
import kr.mujoong.realtime.session.PracticeSessionExtension.SpeechLine;
import kr.mujoong.realtime.session.PracticeSessionExtension.SpeechVoice;
import kr.mujoong.realtime.speech.SpeechClipFailure;
import kr.mujoong.realtime.speech.SpeechClipVoice;
import kr.mujoong.realtime.testing.ControllingPracticeExtension;
import kr.mujoong.realtime.testing.FakeSpeechClipSynthesizer;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.PracticeInstruction;
import kr.mujoong.realtime.voice.PracticeSpeech;
import kr.mujoong.realtime.voice.ProviderRequestFactory.InstructionsAppend;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 3회차 V1: 승인 원문 대본 줄을 GPT-Live가 읽지 않고 미리 만든 음성으로 낸다(편차 (PDS-V1), contracts/ai-io.md 10.16). 실행부가 참여 확인 때 확장이 알려
 * 준 줄을 공유 장치에 맡기고, 낼 차례에 그 음성을 이미 승인된 구간처럼 게이트 → 재확인 → 같은 0x02 출력 묶음(OUTPUT_BEGIN source SCENARIO_SCRIPT)으로
 * 보내는지, 말풍선(AI 행) · 재생 사실 · 끼어들기 · 보류 규칙이 GPT-Live 원문 읽기와 같은지, 못 쓰면(꺼짐 · 실패 · 늦음 · 보류) 지금처럼 GPT-Live
 * 원문 읽기로 대체하고 그 사실을 남기는지, 그 줄을 다음 생성형 지시에 맥락으로 한 번만 싣는지 본다.
 *
 * <p>합성은 대역({@link FakeSpeechClipSynthesizer}, 글자마다 100ms 무음)이고 공급자 · 기기도 대역이다(R-F1). 이 시험의 성공은 테스트 대역 증거이며 실제
 * 합성 · 실제 GPT-Live · 실제 기기 재생 성공이 아니다(R-M11).
 */
@Tag("practice-flow")
class ScriptClipExecutorTest {

    private static final Duration QUIET = Duration.ofMillis(800);
    private static final String NARRATOR = "무중";
    private static final String PEER = "친구";
    private static final String DESCRIPTION = "미술 시간이야. 친구랑 같이 크레용으로 그림을 그리고 있어.";
    private static final String OPENER = "알았어, 잠깐만!";
    private static final String OPPORTUNITY = "자, 여기 초록색!";
    private static final String COACH_SWITCH = "잠깐, 무중이 도와줄게";
    private static final String GENERATED = "너는 아이와 같은 모둠 친구다. 아이에게 무엇을 줄지 열린 질문 하나로 묻는다.";

    private final SessionHarness h = new SessionHarness();
    private final ControllingPracticeExtension extension = new ControllingPracticeExtension();
    private FakeSpeechClipSynthesizer synth;

    // ------------------------------------------------------------------ 미리 만든 음성으로 낸다

    @Test
    void aPreparedScriptLinePlaysAsItsClipWithoutAnyGptLiveInstructionAndEndsLikeAGptLiveLine() {
        practiceWithClips(line(PEER, OPENER, SpeechVoice.PEER));
        PracticeSpeech opener = speech(PEER, OPENER);

        assertThat(synth.calls).singleElement().satisfies(call -> {
            assertThat(call.voice()).as("친구 대사는 친구 말투").isEqualTo(SpeechClipVoice.PEER);
            assertThat(call.text()).isEqualTo(OPENER);
        });
        assertThat(events(SessionExecutor.EVENT_SPEECH_CLIPS_PREPARED)).singleElement().satisfies(payload -> assertThat(payload)
                .contains("\"lines\":1", "\"cached\":1", "\"started\":0", "\"refused\":0", "\"failure\":null"));

        assertThat(extension.out.speak(opener)).isTrue();
        h.settleSend();

        assertThat(h.provider.last().requests(InstructionsAppend.class)).as("GPT-Live에 읽기 지시를 보내지 않는다").isEmpty();
        OutputBundle bundle = h.link.outputs().getFirst();
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(bundle.source()).as("기기 쪽은 같은 출력 묶음, 출처만 대본").isEqualTo(OutputBegin.Source.SCENARIO_SCRIPT);
        assertThat(bundle.totalMs()).isEqualTo(FakeSpeechClipSynthesizer.msFor(OPENER));
        assertThat(bundle.totalBytes()).isEqualTo(FakeSpeechClipSynthesizer.pcmFor(OPENER).length);
        assertThat(bundle.frames()).allSatisfy(frame -> assertThat(frame.length).isLessThanOrEqualTo(OutputBundle.FRAME_PAYLOAD_BYTES));
        assertThat(approvedAiLines()).as("말풍선 · 대화 기록은 승인 원문 그대로").containsExactly(OPENER);

        mark(bundle.playbackId(), PlaybackMark.Status.STARTED, null);
        mark(bundle.playbackId(), PlaybackMark.Status.COMPLETED, null);
        assertThat(extension.endFacts(opener.correlationId())).containsExactly(Playback.COMPLETED);
        assertThat(events(SessionExecutor.EVENT_PRACTICE_SPEECH_SOURCE)).singleElement().satisfies(payload -> assertThat(payload)
                .contains("\"seq\":1", "\"source\":\"TTS_CLIP\"", "\"reason\":null",
                        "\"clipMs\":" + FakeSpeechClipSynthesizer.msFor(OPENER), "\"waitedMs\":null", "\"sent\":true")
                .doesNotContain(OPENER, PEER));
    }

    @Test
    void theRoleSwitchNoticeUsesTheClipTheExecutorPreparedForItsApprovedSentence() {
        h.approvedSpeech.put("COACH_SWITCH", COACH_SWITCH);
        practiceWithClips(line(NARRATOR, DESCRIPTION, SpeechVoice.NARRATOR));

        assertThat(synth.texts()).as("확장의 줄 뒤에 실행부가 내는 알림 문장을 맡긴다").containsExactly(DESCRIPTION, COACH_SWITCH);
        assertThat(synth.calls.get(1).voice()).as("알림은 무중의 말투").isEqualTo(SpeechClipVoice.NARRATOR);
        assertThat(extension.out.announceRoleSwitch("COACH")).isTrue();

        assertThat(h.link.outputs()).singleElement().satisfies(bundle -> {
            assertThat(bundle.source()).isEqualTo(OutputBegin.Source.SCENARIO_SCRIPT);
            assertThat(bundle.totalMs()).isEqualTo(FakeSpeechClipSynthesizer.msFor(COACH_SWITCH));
        });
        assertThat(approvedAiLines()).containsExactly(COACH_SWITCH);
    }

    @Test
    void aClipStillBeingMadeIsAwaitedWithTheOutputHeldAndPlaysWhenItArrives() {
        h.useSpeechClips().immediate = false;
        practiceWithClips(line(PEER, OPPORTUNITY, SpeechVoice.PEER));
        assertThat(events(SessionExecutor.EVENT_SPEECH_CLIPS_PREPARED)).singleElement()
                .satisfies(payload -> assertThat(payload).contains("\"started\":1", "\"cached\":0"));
        PracticeSpeech opportunity = speech(PEER, OPPORTUNITY);

        assertThat(extension.out.speak(opportunity)).isTrue();
        h.advance(Duration.ofMillis(500));
        assertThat(h.link.outputs()).as("아직 만드는 중").isEmpty();
        synth.complete(OPPORTUNITY);
        h.drain();
        h.settleSend();

        assertThat(h.link.outputs()).singleElement()
                .satisfies(bundle -> assertThat(bundle.source()).isEqualTo(OutputBegin.Source.SCENARIO_SCRIPT));
        assertThat(h.provider.last().requests(InstructionsAppend.class)).isEmpty();
        assertThat(events(SessionExecutor.EVENT_PRACTICE_SPEECH_SOURCE)).singleElement()
                .satisfies(payload -> assertThat(payload).contains("\"source\":\"TTS_CLIP\"", "\"waitedMs\":500"));
    }

    // ------------------------------------------------------------------ GPT-Live 원문 읽기로 대체

    @Test
    void aFailedSynthesisFallsBackToTheGptLiveVerbatimReadingAndRecordsTheReason() {
        h.useSpeechClips().failures.put(OPENER, SpeechClipFailure.PROVIDER_ERROR);
        practiceWithClips(line(PEER, OPENER, SpeechVoice.PEER));
        PracticeSpeech opener = speech(PEER, OPENER);

        assertThat(extension.out.speak(opener)).isTrue();
        h.settleSend();

        assertThat(h.provider.last().requests(InstructionsAppend.class)).singleElement()
                .satisfies(append -> assertThat(append.content())
                        .isEqualTo(InstructionTemplates.scoped(PracticeInstruction.forSpeech(opener).text())));
        h.speak(OPENER, 4_800);
        h.advance(QUIET);
        OutputBundle bundle = h.link.outputs().getFirst();
        assertThat(bundle.source()).isEqualTo(OutputBegin.Source.GPT_LIVE);
        mark(bundle.playbackId(), PlaybackMark.Status.STARTED, null);
        mark(bundle.playbackId(), PlaybackMark.Status.COMPLETED, null);
        assertThat(extension.endFacts(opener.correlationId())).containsExactly(Playback.COMPLETED);
        assertThat(events(SessionExecutor.EVENT_PRACTICE_SPEECH_SOURCE)).singleElement().satisfies(payload -> assertThat(payload)
                .contains("\"source\":\"GPT_LIVE_FALLBACK\"", "\"reason\":\"SYNTHESIS_PROVIDER_ERROR\"", "\"sent\":true"));
    }

    @Test
    void withTheDeviceSwitchedOffNothingIsPreparedAndEveryScriptLineIsReadByGptLiveAsBefore() {
        extension.speechLines.add(line(PEER, OPENER, SpeechVoice.PEER));
        practice();

        assertThat(extension.speechLinesAsked).as("꺼진 장치면 줄을 묻지도 않는다").isZero();
        assertThat(events(SessionExecutor.EVENT_SPEECH_CLIPS_PREPARED)).isEmpty();
        assertThat(extension.out.speak(speech(PEER, OPENER))).isTrue();
        h.settleSend();

        assertThat(h.provider.last().requests(InstructionsAppend.class)).hasSize(1);
        assertThat(events(SessionExecutor.EVENT_PRACTICE_SPEECH_SOURCE)).singleElement().satisfies(payload -> assertThat(payload)
                .contains("\"source\":\"GPT_LIVE_FALLBACK\"", "\"reason\":\"DISABLED\""));
    }

    @Test
    void aLineTheExtensionDidNotPrepareIsReadByGptLive() {
        practiceWithClips(line(PEER, OPENER, SpeechVoice.PEER));

        assertThat(extension.out.speak(speech(PEER, OPPORTUNITY))).isTrue();
        assertThat(extension.out.speak(speech(NARRATOR, OPENER))).as("말하는 사람 표시가 다르면 다른 줄이다").isTrue();
        h.settleSend();

        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.provider.last().requests(InstructionsAppend.class)).hasSize(2);
        assertThat(events(SessionExecutor.EVENT_PRACTICE_SPEECH_SOURCE)).hasSize(2)
                .allSatisfy(payload -> assertThat(payload).contains("\"reason\":\"NOT_PREPARED\""));
    }

    @Test
    void aClipNotReadyWithinTheReadyWaitFallsBackToGptLiveOnceAndTheLateClipIsNotPlayed() {
        h.useSpeechClips().immediate = false;
        practiceWithClips(line(PEER, OPPORTUNITY, SpeechVoice.PEER));
        PracticeSpeech opportunity = speech(PEER, OPPORTUNITY);

        assertThat(extension.out.speak(opportunity)).isTrue();
        h.advance(SessionHarness.CLIP_READY_WAIT);
        h.settleSend();
        assertThat(h.provider.last().requests(InstructionsAppend.class)).as("기다린 시간이 다 돼 원문 읽기 지시").singleElement();
        synth.complete(OPPORTUNITY);
        h.drain();
        h.speak(OPPORTUNITY, 4_800);
        h.advance(QUIET);

        assertThat(h.link.outputs()).singleElement().satisfies(bundle -> assertThat(bundle.source()).isEqualTo(OutputBegin.Source.GPT_LIVE));
        assertThat(events(SessionExecutor.EVENT_PRACTICE_SPEECH_SOURCE)).singleElement().satisfies(payload -> assertThat(payload)
                .contains("\"source\":\"GPT_LIVE_FALLBACK\"", "\"reason\":\"CLIP_NOT_READY\"", "\"waitedMs\":2000"));
    }

    @Test
    void anotherSpeechWhileWaitingAbandonsTheWaitAndTheAbandonedClipNeverPlays() {
        h.useSpeechClips().immediate = false;
        practiceWithClips(line(PEER, OPPORTUNITY, SpeechVoice.PEER));

        assertThat(extension.out.speak(speech(PEER, OPPORTUNITY))).isTrue();
        assertThat(extension.out.speak(speech(NARRATOR, DESCRIPTION))).isTrue();
        synth.complete(OPPORTUNITY);
        h.drain();
        h.advance(SessionHarness.CLIP_READY_WAIT);

        assertThat(h.link.outputs()).as("버린 기다림의 음성은 늦게 와도 내지 않는다").isEmpty();
        assertThat(events(SessionExecutor.EVENT_PRACTICE_SPEECH_SOURCE)).containsExactly(
                "{\"seq\":1,\"source\":\"NONE\",\"reason\":\"WAIT_ABANDONED\",\"clipMs\":null,\"waitedMs\":0,\"heard\":null,\"sent\":false}",
                "{\"seq\":2,\"source\":\"GPT_LIVE_FALLBACK\",\"reason\":\"NOT_PREPARED\",\"clipMs\":null,\"waitedMs\":null,"
                        + "\"heard\":null,\"sent\":true}");
    }

    @Test
    void whileAJudgmentTurnHoldsTheOutputAScriptLineIsReadByGptLiveUnderTheUnchangedHoldRules() {
        h.useJudge();
        practiceWithClips(line(PEER, OPENER, SpeechVoice.PEER));
        h.childSays("child-1", "응, 할래.");
        int seq = extension.utterances.getLast().seq();
        assertThat(extension.out.requestJudgment(JudgmentPoint.J4, seq, UUID.randomUUID(), null)).isTrue();

        assertThat(extension.out.speak(speech(PEER, OPENER))).isTrue();
        h.settleSend();

        assertThat(h.link.outputs()).as("판정 중 보류는 대본 줄 음성도 지나가지 않는다").isEmpty();
        assertThat(events(SessionExecutor.EVENT_PRACTICE_SPEECH_SOURCE)).singleElement().satisfies(payload -> assertThat(payload)
                .contains("\"source\":\"GPT_LIVE_FALLBACK\"", "\"reason\":\"HELD\""));
    }

    @Test
    void anExtensionThatCannotListItsLinesStillPractisesAndOnlyTheNoticesArePrepared() {
        h.approvedSpeech.put("COACH_SWITCH", COACH_SWITCH);
        extension.speechLinesFail = true;
        practiceWithClips();

        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(synth.texts()).containsExactly(COACH_SWITCH);
        assertThat(events(SessionExecutor.EVENT_SPEECH_CLIPS_PREPARED)).singleElement()
                .satisfies(payload -> assertThat(payload).contains("\"lines\":1", "\"failure\":\"SPEECH_LINES_FAILED\""));
    }

    // ------------------------------------------------------------------ 게이트 · 끼어들기

    @Test
    void aChildBargingIntoAClipInterruptsItExactlyLikeAGptLiveLine() {
        practiceWithClips(line(NARRATOR, DESCRIPTION, SpeechVoice.NARRATOR));
        PracticeSpeech description = speech(NARRATOR, DESCRIPTION);
        assertThat(extension.out.speak(description)).isTrue();
        UUID playback = h.link.outputs().getFirst().playbackId();

        mark(playback, PlaybackMark.Status.STARTED, null);
        mark(playback, PlaybackMark.Status.STOPPED, PlaybackMark.StopCause.LOCAL_VOICE_ACTIVITY);

        assertThat(extension.endFacts(description.correlationId())).containsExactly(Playback.INTERRUPTED);
        assertThat(events(EndingFlow.EVENT_BARGE_IN)).singleElement()
                .satisfies(payload -> assertThat(payload).contains(playback.toString()));
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void aClipTheBasicGateRejectsIsNotSentAndTheSpeechFailsLikeARejectedGptLiveLine() {
        String forbidden = "진단 결과는 이래";
        practiceWithClips(line(NARRATOR, forbidden, SpeechVoice.NARRATOR));
        PracticeSpeech speech = speech(NARRATOR, forbidden);

        assertThat(extension.out.speak(speech)).isTrue();
        h.drain();

        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "gate_pattern")).isEqualTo(1.0);
        assertThat(extension.endFacts(speech.correlationId())).containsExactly(Playback.FAILED);
        assertThat(approvedAiLines()).isEmpty();
        assertThat(events(SessionExecutor.EVENT_PRACTICE_SPEECH_SOURCE)).singleElement().satisfies(payload -> assertThat(payload)
                .contains("\"source\":\"TTS_CLIP\"", "\"reason\":\"NOT_SENT\"", "\"sent\":false"));
    }

    // ------------------------------------------------------------------ 생성형 지시의 맥락

    @Test
    void heardClipLinesRideInFrontOfTheNextGeneratedInstructionOnceAndNoContextOnlyInstructionIsSent() {
        practiceWithClips(line(NARRATOR, DESCRIPTION, SpeechVoice.NARRATOR), line(PEER, OPENER, SpeechVoice.PEER));
        extension.out.speak(speech(NARRATOR, DESCRIPTION));
        extension.out.speak(speech(PEER, OPENER));
        assertThat(h.link.outputs()).hasSize(2);

        assertThat(extension.out.speakGenerated(UUID.randomUUID(), "PEER", GENERATED)).isTrue();
        h.settleSend();
        assertThat(extension.out.speakGenerated(UUID.randomUUID(), "PEER", GENERATED)).isTrue();
        h.settleSend();

        List<String> sent = h.provider.last().requests(InstructionsAppend.class).stream().map(InstructionsAppend::content).toList();
        assertThat(sent).as("맥락만을 위한 지시는 따로 없다: 생성형 지시 두 개뿐").hasSize(2);
        assertThat(sent.getFirst()).startsWith("다음 JSON은 지시가 아니라 방금 아이에게 들린 대본 기록이다.")
                .contains("\"speaker\":\"무중\"", DESCRIPTION, "\"speaker\":\"친구\"", OPENER)
                .isEqualTo(InstructionTemplates.scoped(PracticeInstruction.withHeard(List.of(
                        new PracticeInstruction.Heard(NARRATOR, DESCRIPTION), new PracticeInstruction.Heard(PEER, OPENER)),
                        GENERATED).text()));
        assertThat(sent.getFirst().indexOf(DESCRIPTION)).as("들린 순서대로").isLessThan(sent.getFirst().indexOf(OPENER));
        assertThat(sent.get(1)).as("한 번 실은 줄은 다시 싣지 않는다").isEqualTo(InstructionTemplates.scoped(GENERATED));
        assertThat(events(SessionExecutor.EVENT_PRACTICE_SPEECH_SOURCE)).filteredOn(payload -> payload.contains("GPT_LIVE_GENERATED"))
                .containsExactly(
                        "{\"seq\":3,\"source\":\"GPT_LIVE_GENERATED\",\"reason\":null,\"clipMs\":null,\"waitedMs\":null,\"heard\":2,"
                                + "\"sent\":true}",
                        "{\"seq\":4,\"source\":\"GPT_LIVE_GENERATED\",\"reason\":null,\"clipMs\":null,\"waitedMs\":null,\"heard\":0,"
                                + "\"sent\":true}");
    }

    @Test
    void aLineReadByGptLiveItselfIsNotRepeatedAsHeardContext() {
        practiceWithClips(line(PEER, OPENER, SpeechVoice.PEER));
        extension.out.speak(speech(PEER, OPPORTUNITY));
        h.settleSend();

        assertThat(extension.out.speakGenerated(UUID.randomUUID(), "PEER", GENERATED)).isTrue();
        h.settleSend();

        assertThat(h.provider.last().requests(InstructionsAppend.class)).extracting(InstructionsAppend::content).last()
                .as("GPT-Live가 읽은 줄은 이미 그 대화 맥락에 있다").isEqualTo(InstructionTemplates.scoped(GENERATED));
    }

    // ------------------------------------------------------------------ 보조

    private void practiceWithClips(SpeechLine... lines) {
        if (synth == null) {
            synth = h.synth != null ? h.synth : h.useSpeechClips();
        }
        extension.speechLines.addAll(List.of(lines));
        practice();
    }

    private void practice() {
        synth = h.synth;
        h.activityType = ActivityType.PRACTICE;
        h.practice = extension.factory;
        h.activate();
    }

    private static SpeechLine line(String speaker, String text, SpeechVoice voice) {
        return new SpeechLine(speaker, text, voice);
    }

    private static PracticeSpeech speech(String speaker, String text) {
        return PracticeSpeech.scenario(UUID.randomUUID(), UUID.randomUUID(), 1, speaker, text);
    }

    /** 보호자 화면에 승인된 AI 확정 행의 글(전사 순서대로). */
    private List<String> approvedAiLines() {
        h.recorder.tick();
        return h.store.transcripts.stream().filter(entry -> entry.speaker() == TranscriptEntry.Speaker.AI)
                .filter(entry -> entry.assemblyStatus() == TranscriptEntry.AssemblyStatus.FINAL)
                .filter(TranscriptEntry::approvedForDisplay).map(TranscriptEntry::text).toList();
    }

    /** 그 종류 세션 사건의 payload(기록기를 비운 뒤, 순서대로). */
    private List<String> events(String type) {
        h.recorder.tick();
        return h.store.sessionEvents.stream().filter(event -> type.equals(event.getEventType()))
                .map(SessionEvent::getPayloadJson).toList();
    }

    private void mark(UUID playbackId, PlaybackMark.Status status, PlaybackMark.StopCause cause) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(playbackId, status, null, h.clock.now(), cause, null), h.clock.now()));
        h.drain();
    }
}
