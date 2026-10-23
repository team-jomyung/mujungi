package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.OutputBegin;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.gateway.OutputBundle;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.practiceflow.PracticeScript.Scene;
import kr.mujoong.realtime.practiceflow.StandardPracticeDialogue.ChildTurn;
import kr.mujoong.realtime.session.NoOpPracticeSessionExtension;
import kr.mujoong.realtime.session.SessionEvent;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.testing.FakeJudgeClient;
import kr.mujoong.realtime.testing.FakeSpeechClipSynthesizer;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.PracticeInstruction;
import kr.mujoong.realtime.voice.ProviderRequestFactory.InstructionsAppend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 3회차 V1: 기준 연습 대화 16차례(docs/practice-dialogue-standard.md)를 실행부({@code SessionExecutor}) · 라우터 · 상태 기계와 대본 줄 음성 대역으로 끝까지
 * 돌린다. 승인 원문 7줄(무중의 설명 · 친구 첫 대사 · 기회 문장 · 변형 제안 · 변형 기회 문장 · 전환 · 복귀 알림)은 참여 확인 때 미리 만든 음성으로 GPT-Live
 * 지시 없이 나가고, GPT-Live에는 생성형 4줄(되묻기 · 반응 · 코치 도움 · 반응)의 지시만 가며 그 지시 앞에 방금 들린 대본 줄이 실리는지, 판정 5번과 장면
 * 결과는 GPT-Live 원문 읽기 때와 같은지 본다.
 *
 * <p>합성 · 공급자 · 판정은 시험이 손으로 푸는 대역이고 생성형 줄의 말은 시험이 정한 합성 문장이다(R-F1). 이 시험의 성공은 테스트 대역 · 합성 대화의
 * 성공이며 실제 합성 · 실제 GPT-Live · 실제 기기 성공이 아니다(R-M11).
 */
@Tag("practice-flow")
class PracticeFlowScriptClipTest {

    /** 운영 시드와 같은 승인 발화(infra/seed/004-u8-reference.sql). */
    private static final Map<String, String> APPROVED = Map.of("HELP_OFFER", "무중이가 조금 도와줄까?", "COACH_SWITCH", "잠깐, 무중이 도와줄게",
            "PEER_RETURN", "다시 친구 역할로 돌아갈게");
    /** 생성형 줄로 공급자가 말하는 합성 문장(기준 대화의 친구 · 코치 대사). */
    private static final String REASK = "응? 이거 아니야? 그럼 뭐 줄까?";
    private static final String REACTION_ONE = "아, 파란색이었구나! 내가 잘못 들었네. 여기 있어.";
    private static final String COACH = "달라고 한 거랑 받은 게 같은지 생각해 봐. 아니, 나 뭐뭐 달라고 했어처럼 말할 수 있어. 네 말로 해 볼래?";
    private static final String REACTION_TWO = "아, 딸기 우유! 미안, 여기 있어.";

    private final SessionHarness h = new SessionHarness();
    private final FakeJudgeClient judge = h.useJudge();
    private final FakeSpeechClipSynthesizer synth = h.useSpeechClips();
    private final PracticeFlowTestKit.MemoryRecorder recorder = new PracticeFlowTestKit.MemoryRecorder();
    private final List<RuntimeException> failures = new ArrayList<>();
    private final StandardPracticeDialogue standard = StandardPracticeDialogue.load();
    private final List<String> heard = new ArrayList<>();
    private int item;

    @BeforeEach
    void setUp() {
        h.activityType = ActivityType.PRACTICE;
        h.practiceJson = standard.practiceJson();
        h.approvedSpeech.putAll(APPROVED);
        PracticeScriptRequester requester = new PracticeScriptRequester(mock(InternalHttpClient.class), Runnable::run,
                Duration.ofSeconds(1));
        h.practice = session -> new PracticeFlowRouter(NoOpPracticeSessionExtension.FACTORY.create(session),
                () -> new PracticeConversation(session, h.guardianId, h.clock, PracticeFlowTestKit.defaults(), requester, recorder,
                        failures::add));
    }

    @Test
    void theSixteenStandardTurnsPlaySevenScriptLinesFromClipsAndSendOnlyTheFourGeneratedLinesToGptLive() {
        Scene base = standard.script().baseScene();
        Scene variant = standard.script().variantScene().orElseThrow();
        String variantOffer = variant.sceneDescription() + " " + PracticeInstructions.VARIANT_OFFER_QUESTION;
        List<ChildTurn> child = standard.childTurns();
        h.activate();
        assertThat(synth.texts()).as("참여 확인 때 첫 대본 줄 8개 + 실행부의 알림 2개를 맡겼다").containsExactly(base.sceneDescription(),
                base.partnerOpener(), base.opportunitySentence(), base.reexplanation(), APPROVED.get("HELP_OFFER"), variantOffer,
                variant.opportunitySentence(), variant.reexplanation(), APPROVED.get("COACH_SWITCH"), APPROVED.get("PEER_RETURN"));

        // [장면 1] 무중의 설명 → 친구 첫 대사 → 친구 기회 문장(미리 만든 음성)
        clip(base.sceneDescription());
        clip(base.partnerOpener());
        clip(base.opportunitySentence());
        // 아이: 어… 이거 아닌데. → J2 일부 전달 → 친구 되묻기(생성형)
        answer(child.get(0), "FULL");
        generated(REASK);
        // 아이: 파란색! … → J3 전달됨 → 친구 반응(생성형)
        answer(child.get(1), "STILL_SHORT");
        generated(REACTION_ONE);
        // [장면 2] 무중의 변형 제안(미리 만든 음성) → 수락 → 친구 기회 문장
        clip(variantOffer);
        answer(child.get(2), "UNCLEAR");
        clip(variant.opportunitySentence());
        // 아이: 음… 몰라. 뭐라고 해? → J2 도움 요청 → 전환 알림(미리 만든 음성) → 코치 도움(생성형)
        answer(child.get(3), "PARTIAL");
        clip(APPROVED.get("COACH_SWITCH"));
        generated(COACH);
        // 아이: 아니, 나 딸기 우유 달라고 했어. → J5 → 복귀 알림(미리 만든 음성) → 친구 반응(생성형)
        answer(child.get(4), "PARTIAL");
        clip(APPROVED.get("PEER_RETURN"));
        generated(REACTION_TWO);

        assertThat(heard).as("16차례 = 승인 원문 7줄 + 생성형 4줄 + 판정 5번").hasSize(16);
        assertThat(h.link.outputs()).extracting(OutputBundle::source).containsExactly(OutputBegin.Source.SCENARIO_SCRIPT,
                OutputBegin.Source.SCENARIO_SCRIPT, OutputBegin.Source.SCENARIO_SCRIPT, OutputBegin.Source.GPT_LIVE,
                OutputBegin.Source.GPT_LIVE, OutputBegin.Source.SCENARIO_SCRIPT, OutputBegin.Source.SCENARIO_SCRIPT,
                OutputBegin.Source.SCENARIO_SCRIPT, OutputBegin.Source.GPT_LIVE, OutputBegin.Source.SCENARIO_SCRIPT,
                OutputBegin.Source.GPT_LIVE);
        List<String> instructions = h.provider.last().requests(InstructionsAppend.class).stream().map(InstructionsAppend::content)
                .toList();
        assertThat(instructions).as("GPT-Live에는 생성형 지시 4개만 간다(대본 읽기 · 맥락만의 지시 없음)").hasSize(4)
                .noneMatch(content -> content.contains("speech만 그대로 읽고"));
        // 들린 대본 줄은 지시문 상한(500자) 안에서 최근 줄부터 싣는다: 기준 대본의 지시문 길이로는 되묻기 1줄 · 코치 2줄 · 반응 1줄이다
        String friend = RolePolicy.peerName(base.partnerRole());
        int reaskHeard = PracticeInstruction.heardThatFits(List.of(new PracticeInstruction.Heard("무중", base.sceneDescription()),
                new PracticeInstruction.Heard(friend, base.partnerOpener()), new PracticeInstruction.Heard(friend, base.opportunitySentence())),
                PracticeInstructions.peerReask(base, false));
        int coachHeard = PracticeInstruction.heardThatFits(List.of(new PracticeInstruction.Heard("무중", variantOffer),
                new PracticeInstruction.Heard(friend, variant.opportunitySentence()),
                new PracticeInstruction.Heard("코치", APPROVED.get("COACH_SWITCH"))), PracticeInstructions.coachHelp(variant, RolePolicy.Example.FRAME));
        int reactionHeard = PracticeInstruction.heardThatFits(List.of(new PracticeInstruction.Heard("친구", APPROVED.get("PEER_RETURN"))),
                PracticeInstructions.peerReaction(variant));
        assertThat(List.of(reaskHeard, coachHeard, reactionHeard)).containsExactly(1, 2, 1);
        assertThat(instructions.getFirst()).as("되묻기 지시 앞에 방금 들린 친구 기회 문장").startsWith(PracticeInstruction.HEARD_LEAD)
                .contains(base.opportunitySentence());
        assertThat(instructions.get(1)).as("생성형 줄 뒤에는 실을 들린 줄이 없다").doesNotContain(PracticeInstruction.HEARD_LEAD);
        assertThat(instructions.get(2)).as("코치 지시 앞에 변형 기회 문장 · 전환 알림").startsWith(PracticeInstruction.HEARD_LEAD)
                .contains(variant.opportunitySentence(), APPROVED.get("COACH_SWITCH"));
        assertThat(instructions.get(3)).as("반응 지시 앞에 복귀 알림").contains(APPROVED.get("PEER_RETURN"));
        assertThat(sourceEvents()).extracting(payload -> payload.replaceAll(".*\"source\":\"([A-Z_]+)\".*\"heard\":([0-9a-z]+).*", "$1/$2"))
                .containsExactly("TTS_CLIP/null", "TTS_CLIP/null", "TTS_CLIP/null", "GPT_LIVE_GENERATED/" + reaskHeard,
                        "GPT_LIVE_GENERATED/0", "TTS_CLIP/null", "TTS_CLIP/null", "TTS_CLIP/null", "GPT_LIVE_GENERATED/" + coachHeard,
                        "TTS_CLIP/null", "GPT_LIVE_GENERATED/" + reactionHeard);
        assertThat(approvedAiLines()).as("말풍선 · 대화 기록은 들린 순서 그대로").containsExactly(base.sceneDescription(),
                base.partnerOpener(), base.opportunitySentence(), REASK, REACTION_ONE, variantOffer, variant.opportunitySentence(),
                APPROVED.get("COACH_SWITCH"), COACH, APPROVED.get("PEER_RETURN"), REACTION_TWO);

        assertThat(h.executor.status()).as("기준 대화가 끝나면 연습을 마친다").isEqualTo(SessionStatus.CLOSING);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().endDetail()).isEqualTo("PRACTICE_COMPLETED");
        List<SceneRunRecord> rows = List.copyOf(recorder.rows.values());
        assertThat(rows).extracting(SceneRunRecord::liveTiming).as("GPT-Live 원문 읽기 때와 같은 결과: 되묻기 뒤 전달 · 도움 뒤 전달")
                .containsExactly(LiveTiming.AFTER_REASK, LiveTiming.AFTER_HELP);
        assertThat(rows).allSatisfy(row -> assertThat(row.facts()).extracting(PracticeFlowTypes.Fact::kind)
                .doesNotContain(PracticeFlowTypes.FactKind.PLAYBACK_FAILED));
        assertThat(failures).isEmpty();
    }

    /** 승인 원문 줄: 앞 차례가 끝나는 순간 미리 만든 음성이 이미 기기로 갔다(지시를 기다리지 않는다). 재생을 끝까지 들려준다. */
    private void clip(String text) {
        assertThat(h.link.outputs()).as("나갔다: %s", text).hasSize(played() + 1);
        OutputBundle bundle = h.link.outputs().getLast();
        assertThat(bundle.source()).isEqualTo(OutputBegin.Source.SCENARIO_SCRIPT);
        assertThat(bundle.totalMs()).as("그 줄의 음성: %s", text).isEqualTo(FakeSpeechClipSynthesizer.msFor(text));
        finish(bundle, text);
    }

    /** 생성형 줄: 지시를 보내면 공급자가 시험이 정한 합성 문장을 말하고 기기로 간다. */
    private void generated(String text) {
        h.settleSend();
        h.speak(text, 4800);
        h.advance(Duration.ofMillis(801));
        assertThat(h.link.outputs()).as("들린다: %s", text).hasSize(played() + 1);
        OutputBundle bundle = h.link.outputs().getLast();
        assertThat(bundle.source()).isEqualTo(OutputBegin.Source.GPT_LIVE);
        finish(bundle, text);
    }

    private void finish(OutputBundle bundle, String text) {
        heard.add(text);
        for (PlaybackMark.Status status : List.of(PlaybackMark.Status.STARTED, PlaybackMark.Status.COMPLETED)) {
            h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                    new PlaybackMark(bundle.playbackId(), status, null, h.clock.now(), null, null), h.clock.now()));
            h.drain();
        }
    }

    private int played() {
        return (int) heard.stream().filter(text -> !text.startsWith("판정 ")).count();
    }

    /** 아이 말을 넣고 기준 대화의 기대 판정(지점 · 첫 선택지)으로 가짜 판정을 푼다. */
    private void answer(ChildTurn turn, String second) {
        h.childSays("child-" + (++item), turn.text());
        assertThat(judge.last().request().point().name()).as("'%s'의 판정 지점", turn.text()).isEqualTo(turn.point());
        judge.confirm(turn.option(), 0.95, second, 0.02);
        h.drain();
        heard.add("판정 " + turn.point());
    }

    private List<String> sourceEvents() {
        h.recorder.tick();
        return h.store.sessionEvents.stream().filter(event -> "PRACTICE_SPEECH_SOURCE".equals(event.getEventType()))
                .map(SessionEvent::getPayloadJson).toList();
    }

    private List<String> approvedAiLines() {
        h.recorder.tick();
        return h.store.transcripts.stream().filter(entry -> entry.speaker() == TranscriptEntry.Speaker.AI)
                .filter(entry -> entry.assemblyStatus() == TranscriptEntry.AssemblyStatus.FINAL)
                .filter(TranscriptEntry::approvedForDisplay).map(TranscriptEntry::text).toList();
    }
}
