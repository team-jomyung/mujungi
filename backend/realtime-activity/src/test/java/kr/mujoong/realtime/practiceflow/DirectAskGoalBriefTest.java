package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.FIXED_SPEECH;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.UNREADABLE_GOAL_NAME;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_GOAL_BRIEF;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_INTRO;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.directAskScript;
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
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import kr.mujoong.realtime.voice.ProviderRequestFactory.InstructionsAppend;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * C안 목표 안내 승인 낭독을 실행부({@code SessionExecutor}) · 실제 출력 게이트 · C안 흐름({@link PracticeConversation})으로 본다(편차 FT-1024-g,
 * docs/practice-direct-ask.md 7.3). 서버가 정한 목표 안내 문장은 장면 안 말과 같은 승인 낭독 지시로 나가고, 원문 그대로 읽히면 제브 없이 재생되며, 그래도
 * 출력 게이트의 1차 검사(금지 표현 사전)는 그대로 지난다. 목표 이름을 승인 문장으로 다듬을 수 없으면 지금처럼 생성형(제브 필수)으로 낸다. 공급자 · 제브는
 * 시험이 손으로 푸는 가짜이고(R-F1), 이 시험의 성공은 테스트 대역 · 합성 대화의 성공이며 실제 공급자 · 실제 기기 성공이 아니다(R-M11).
 */
@Tag("practice-flow")
@Tag("service-guardrail")
class DirectAskGoalBriefTest {

    private static final UUID GOAL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SCENARIO = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

    private final SessionHarness h = new SessionHarness();
    private final FakeGuardrailClient guard = h.useGuardrail();
    private final PracticeFlowTestKit.MemoryRecorder recorder = new PracticeFlowTestKit.MemoryRecorder();
    private final List<RuntimeException> failures = new ArrayList<>();

    @Test
    void theGoalBriefIsSentAsAnApprovedReadingAndPlaysWithoutTheGuardrailWhenReadVerbatim() {
        start("부탁하기");

        String instruction = lastReading();
        assertThat(instruction).contains("<approved_speech>" + V2_GOAL_BRIEF + "</approved_speech>")
                .as("C안 낭독 지시(역할 · 따옴표 문구 없음, 설계 7.6)").doesNotContain("partnerRole", "역할극", "따옴표");
        assertThat(commentaries()).as("생성형 발화 요청이 없다").isEmpty();

        h.speak(V2_GOAL_BRIEF, 4800);
        h.advance(Duration.ofMillis(801));

        assertThat(h.link.outputs()).as("원문 그대로 읽힌 목표 안내는 바로 나간다").hasSize(1);
        assertThat(guard.count()).as("원문 일치라 제브를 부르지 않는다").isZero();
        playAll();
        assertThat(nextReading()).as("목표 안내가 끝나야 장면 도입").contains("<approved_speech>" + V2_INTRO + "</approved_speech>");
        assertThat(recorder.list()).as("목표 안내는 장면 밖 말이라 장면 행이 없다").isEmpty();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(failures).isEmpty();
    }

    @Test
    void anApprovedGoalBriefStillPassesTheFirstPassDictionaryWhichDiscardsAProfaneGoalName() {
        // 목표 이름은 AI 목표 후보에서 온다: 승인 낭독은 제브를 건너뛰므로 출력 게이트 1차 사전(PROFANITY)이 마지막 그물이다
        start("씨발 부탁하기");
        String brief = DirectAskLines.goalBrief("씨발 부탁하기", 1, 1);
        assertThat(lastReading()).contains("<approved_speech>" + brief + "</approved_speech>");

        h.speak(brief, 4800);
        h.advance(Duration.ofMillis(801));

        assertThat(h.link.outputs()).as("원문과 같아도 금지 표현은 0바이트").isEmpty();
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "gate_pattern")).isEqualTo(1.0);
        assertThat(guard.count()).isZero();
        // 못 들려준 목표 안내는 다시 내지 않고 장면 도입으로 간다(FT-1023-g)
        assertThat(nextReading()).contains("<approved_speech>" + V2_INTRO + "</approved_speech>");
        assertThat(h.provider.last().requests(InstructionsAppend.class)).filteredOn(request -> request.content().contains(brief))
                .as("같은 목표 안내를 다시 요청하지 않는다").hasSize(1);
        assertThat(h.logs.anyLineContains("씨발")).as("로그에 목표 이름 원문이 없다(R-F14)").isFalse();
        assertThat(failures).isEmpty();
    }

    @Test
    void aGoalNameTheApprovedSentenceCannotHoldFallsBackToTheGeneratedBriefThroughTheGuardrail() {
        start(UNREADABLE_GOAL_NAME);

        assertThat(h.provider.last().requests(InstructionsAppend.class)).as("승인 낭독 지시가 없다")
                .noneMatch(request -> request.content().contains("<approved_speech>"));
        assertThat(commentaries()).singleElement()
                .satisfies(request -> assertThat(request.content()).contains("오늘 연습할 목표는 ‘" + UNREADABLE_GOAL_NAME + "’야."));
        assertThat(appErrors()).containsOnlyOnce(PracticeConversation.DIAG_GOAL_BRIEF_GENERATED);

        h.speak("오늘 연습할 목표는 3단계 부탁하기야.", 4800);
        h.advance(Duration.ofMillis(801));
        assertThat(guard.last().request().mode()).as("생성형 목표 안내는 지금처럼 제브(연습 모드)를 거친다")
                .isEqualTo(GuardrailRequest.Mode.PRACTICE_PEER);
        assertThat(h.link.outputs()).isEmpty();
        guard.allow();
        h.drain();
        assertThat(h.link.outputs()).hasSize(1);
        playAll();

        assertThat(nextReading()).contains("<approved_speech>" + V2_INTRO + "</approved_speech>");
        assertThat(failures).isEmpty();
    }

    // ---- 도구 ----

    private void start(String goalName) {
        h.activityType = ActivityType.PRACTICE;
        h.practiceJson = packageJson(GOAL, directAskScript(SCENARIO), goalName);
        h.approvedSpeech.putAll(FIXED_SPEECH);
        PracticeScriptRequester requester = new PracticeScriptRequester(mock(InternalHttpClient.class), Runnable::run,
                Duration.ofSeconds(1));
        h.practice = session -> new PracticeConversation(session, h.guardianId, h.clock, PracticeFlowTestKit.defaults(), requester,
                recorder, failures::add);
        h.activate();
        h.settleSend();
    }

    /** 공급자에 마지막으로 보낸 지시 본문. */
    private String lastReading() {
        return h.provider.last().requests(InstructionsAppend.class).getLast().content();
    }

    /** 앞 말의 꼬리가 조용해지기를 기다린 뒤 흐름이 새로 보낸 승인 낭독 지시. */
    private String nextReading() {
        int before = h.provider.last().requests(InstructionsAppend.class).size();
        h.advance(Duration.ofMillis(801));
        h.settleSend();
        List<InstructionsAppend> sent = h.provider.last().requests(InstructionsAppend.class);
        assertThat(sent).as("흐름이 다음 승인 낭독을 새로 요청했다").hasSizeGreaterThan(before);
        return sent.getLast().content();
    }

    private final java.util.Set<UUID> marked = new java.util.HashSet<>();

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

    /** 생성형 발화 요청({@code session.commentary.append}). */
    private List<InstructionsAppend> commentaries() {
        return h.provider.last().requests(InstructionsAppend.class).stream()
                .filter(request -> ProviderRequestFactory.TYPE_COMMENTARY_APPEND.equals(request.type())).toList();
    }

    private List<String> appErrors() {
        return h.logs.events(EventCode.APP_ERROR).stream().map(event -> String.valueOf(LogCapture.attrs(event).get("errorKind")))
                .toList();
    }
}
