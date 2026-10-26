package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.FIXED_SPEECH;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_INTRO;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_OPPORTUNITY;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.directAskScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.minimalScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.packageJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.guardrail.GuardrailRequest;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.testing.FakeGuardrailClient;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderRequestFactory.InstructionsAppend;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 조각 단위 승인 낭독(B1)의 부분 재생을 실행부({@code SessionExecutor})와 실제 연습 흐름({@link PracticeConversation})으로 끝까지 본다(docs/practice-direct-ask.md
 * 7.7): 상황 안내의 앞 단위만 나가고 뒤가 원문과 다르거나(단위 불일치) 원문 끝 전에 응답이 끝나면(꼬리 없음), 실행부는 나간 음성까지만 재생한 뒤 들린
 * 위치를 담은 부분 재생 사실을 흐름에 알리고, C안은 끊긴 곳이 든 문장부터 새 승인 원문으로 다시 읽게 하며, 장면 행과 다음 말(전하기 + 묻기)은 그 재생이
 * 끝난 뒤에 온다. 유연 대화는 같은 사실을 지금처럼 재생 끝으로 받는다. 공급자 · 제브는 시험이 손으로 푸는 가짜이고(R-F1), 이 시험의 성공은 테스트 대역 ·
 * 합성 대화의 성공이며 실제 공급자 · 실제 기기 성공이 아니다(R-M11).
 */
@Tag("practice-flow")
class DirectAskPartialPlaybackTest {

    private static final UUID GOAL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SCENARIO = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final String FIRST_SENTENCE = "친구랑 공룡 그림을 같이 그리는 이야기야.";
    private static final String SECOND_SENTENCE = "너는 친구한테 같이 그리자고 했어.";
    /** 유연 대화가 옛 대본(minimalScript)에서 읽는 상대의 기회 문장. */
    private static final String FLEXIBLE_OPPORTUNITY = "친구가 말했어. 그거 줘.";

    private final SessionHarness h = new SessionHarness();
    private final FakeGuardrailClient guard = h.useGuardrail();
    private final PracticeFlowTestKit.MemoryRecorder recorder = new PracticeFlowTestKit.MemoryRecorder();
    private final List<RuntimeException> failures = new ArrayList<>();
    /** 지금 받는 응답의 공급자 오디오 시계 기준(ms)과 지금까지 흘린 단위 수. */
    private long base;
    private int units;
    /** 재생 끝까지 알린 기기 음성 묶음. */
    private final java.util.Set<UUID> marked = new java.util.HashSet<>();
    /** 상황 안내 전까지 기기로 나간 음성 묶음 수(목표 안내). */
    private int sentBefore;

    @Test
    void aSecondUnitThatDiffersRereadsTheIntroductionFromItsFirstSentenceAfterTheSentAudioPlayed() {
        start(PracticeFlowTestKit.defaults());
        String intro = ackApprovedReading();
        assertThat(intro).contains("<approved_speech>" + V2_INTRO + "</approved_speech>");

        unit("친구랑 ");
        assertThat(h.link.outputs()).as("원문 앞부분인 첫 단위는 바로 나간다").hasSize(sentBefore + 1);
        unit("공룡 그림이 싫어. ");
        assertThat(h.link.outputs()).as("원문과 다른 단위는 버린다").hasSize(sentBefore + 1);
        assertThat(recorder.list()).isEmpty();

        playAll();

        // 첫 문장 안에서 끊겼다: 처음부터 다시 읽는 지시가 나가고, 장면은 아직 시작되지 않았다
        String reread = ackApprovedReading();
        assertThat(reread).contains("<approved_speech>" + V2_INTRO + "</approved_speech>");
        assertThat(recorder.list()).as("상황 안내를 다 들려주기 전에는 장면 행이 없다").isEmpty();
        assertThat(appErrors()).contains(SessionExecutorReasons.UNIT_MISMATCH, SessionExecutorReasons.PARTIAL,
                PracticeConversation.DIAG_REMAINDER_REREAD);

        unit(FIRST_SENTENCE + " ");
        unit(SECOND_SENTENCE);
        h.advance(Duration.ofMillis(801));
        playAll();

        assertThat(recorder.list()).as("나머지까지 들린 뒤 장면 행").hasSize(1);
        assertThat(nextApprovedReading()).contains("<approved_speech>" + V2_OPPORTUNITY + "</approved_speech>");
        assertThat(guard.count()).as("장면 안 승인 낭독도 목표 안내 승인 낭독도 제브를 부르지 않는다(FT-1024-g)").isZero();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(failures).isEmpty();
        assertThat(h.logs.anyLineContains("그리자고")).as("진단에는 원문이 없다(R-F14)").isFalse();
        assertThat(h.logs.anyLineContains("그림이 싫어")).as("버린 단위의 글도 없다").isFalse();
    }

    @Test
    void aResponseThatEndsBeforeTheOriginalEndsRereadsOnlyTheSentenceNotYetHeard() {
        start(PracticeFlowTestKit.defaults());
        ackApprovedReading();

        unit(FIRST_SENTENCE + " ");
        h.advance(Duration.ofMillis(801));
        assertThat(h.link.outputs()).hasSize(sentBefore + 1);
        playAll();

        assertThat(ackApprovedReading()).as("꼬리 없음도 같다: 들리지 않은 둘째 문장만 다시 읽는다")
                .contains("<approved_speech>" + SECOND_SENTENCE + "</approved_speech>");
        assertThat(appErrors()).contains(SessionExecutorReasons.TAIL_MISSING, SessionExecutorReasons.PARTIAL,
                PracticeConversation.DIAG_REMAINDER_REREAD);
        assertThat(recorder.list()).isEmpty();

        unit(SECOND_SENTENCE);
        h.advance(Duration.ofMillis(801));
        playAll();

        assertThat(recorder.list()).hasSize(1);
        assertThat(nextApprovedReading()).contains("<approved_speech>" + V2_OPPORTUNITY + "</approved_speech>");
        assertThat(failures).isEmpty();
    }

    @Test
    void theFlexiblePathReceivesTheSamePartialPlaybackAsPlayedAndMovesOn() {
        start(PracticeFlowTestKit.flexible(), packageJson(GOAL, minimalScript(SCENARIO)));
        String intro = ackApprovedReading();
        String spoken = PracticeInstructions.spokenIntroduction(PracticeStartPackage.parse(h.practiceJson).firstScenario().baseScene());
        assertThat(intro).contains("<approved_speech>" + spoken + "</approved_speech>");

        unit("상상 속 ");
        unit("놀이터 이야기야. ");
        playAll();

        // 유연 대화는 부분 재생을 재생 끝으로 받는다: 다시 읽지 않고 장면을 시작해 다음 말(상대의 기회 문장)로 간다
        assertThat(recorder.list()).hasSize(1);
        assertThat(nextApprovedReading()).doesNotContain("<approved_speech>" + spoken + "</approved_speech>")
                .contains("<approved_speech>" + FLEXIBLE_OPPORTUNITY + "</approved_speech>");
        assertThat(appErrors()).contains(SessionExecutorReasons.PARTIAL).doesNotContain(PracticeConversation.DIAG_REMAINDER_REREAD);
        assertThat(failures).isEmpty();
    }

    // ---- 도구 ----

    private void start(PracticeFlowSettings settings) {
        start(settings, packageJson(GOAL, directAskScript(SCENARIO)));
    }

    private void start(PracticeFlowSettings settings, String practiceJson) {
        h.activityType = ActivityType.PRACTICE;
        h.practiceJson = practiceJson;
        h.approvedSpeech.putAll(FIXED_SPEECH);
        PracticeScriptRequester requester = new PracticeScriptRequester(mock(InternalHttpClient.class), Runnable::run,
                Duration.ofSeconds(1));
        h.practice = session -> new PracticeConversation(session, h.guardianId, h.clock, settings, requester, recorder, failures::add);
        h.activate();
        h.settleSend();
        if (settings.directAsk()) {
            // C안 목표 안내는 서버가 정한 문장의 승인 낭독이다(FT-1024-g): 원문 그대로 읽히면 제브 없이 나간다(시간 정보 없는 응답)
            h.speak(PracticeScriptFixtures.V2_GOAL_BRIEF, 4800);
            h.advance(Duration.ofMillis(801));
        } else {
            // 유연 대화의 목표 안내는 생성형이라 제브를 거친다(시간 정보 없는 응답)
            h.speak("오늘 연습할 목표는 부탁하기야.", 4800);
            h.advance(Duration.ofMillis(801));
            assertThat(guard.last().request().mode()).isEqualTo(GuardrailRequest.Mode.PRACTICE_PEER);
            guard.allow();
            h.drain();
        }
        assertThat(h.link.outputs()).as("목표 안내가 기기로 나갔다").hasSize(1);
        playAll();
        sentBefore = h.link.outputs().size();
    }

    /**
     * 흐름이 낸 승인 낭독 지시를 공급자로 보내고 확인 사건을 돌려준다. 이어지는 응답은 시간 정보가 있는 단위로 흘린다(가짜 공급자의 자동 확인은 끈다).
     *
     * @return 보낸 지시 본문
     */
    private String ackApprovedReading() {
        String content = nextApprovedReading();
        InstructionsAppend request = h.provider.last().requests(InstructionsAppend.class).getLast();
        h.provider.last().newSpeechMargin(null);
        base = h.provider.last().audioClockMs();
        units = 0;
        h.provider.last().emit(new ProviderEvent.InstructionsAppended(request.eventId(), base, base));
        h.drain();
        return content;
    }

    /** 앞 말의 꼬리가 조용해지기를 기다린 뒤 흐름의 다음 승인 낭독 지시를 내보낸다(새 지시가 꼭 하나 나가야 한다). */
    private String nextApprovedReading() {
        int before = h.provider.last().requests(InstructionsAppend.class).size();
        h.advance(Duration.ofMillis(801));
        h.settleSend();
        List<InstructionsAppend> sent = h.provider.last().requests(InstructionsAppend.class);
        assertThat(sent).as("흐름이 다음 승인 낭독을 새로 요청했다").hasSize(before + 1);
        assertThat(sent.getLast().content()).contains("<approved_speech>");
        return sent.getLast().content();
    }

    /** 시간 정보가 있는 출력 단위 하나(전사 100ms + 대응 음성). 첫 단위 앞에는 새 말 여유만큼 낡은 꼬리 음성이 붙는다. */
    private void unit(String text) {
        long margin = h.settings.newSpeechMargin().toMillis();
        long start = units == 0 ? base : base + margin + units * 100L;
        long end = base + margin + (units + 1) * 100L;
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta(text, start, end));
        if (units == 0) {
            h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[(int) margin * 48]));
        }
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[4800]));
        h.drain();
        units++;
    }

    /** 기기에 나간 음성 묶음 가운데 아직 알리지 않은 것을 모두 재생 시작 · 끝으로 알린다. */
    private void playAll() {
        for (var output : List.copyOf(h.link.outputs())) {
            if (!marked.add(output.playbackId())) {
                continue;
            }
            for (PlaybackMark.Status status : List.of(PlaybackMark.Status.STARTED, PlaybackMark.Status.COMPLETED)) {
                h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                        new PlaybackMark(output.playbackId(), status, null, h.clock.now(), null, null), h.clock.now()));
                h.drain();
            }
        }
    }

    private List<String> appErrors() {
        return h.logs.events(EventCode.APP_ERROR).stream().map(event -> String.valueOf(LogCapture.attrs(event).get("errorKind")))
                .toList();
    }

    /** 실행부 사유 코드(같은 패키지가 아니라 값으로 둔다, SessionExecutor 상수와 같다). */
    private static final class SessionExecutorReasons {
        static final String UNIT_MISMATCH = "PracticeApprovedUnitMismatchAfterPlayback";
        static final String TAIL_MISSING = "PracticeApprovedTailMissingAfterPlayback";
        static final String PARTIAL = "PracticeApprovedPartialPlayback";
    }
}
