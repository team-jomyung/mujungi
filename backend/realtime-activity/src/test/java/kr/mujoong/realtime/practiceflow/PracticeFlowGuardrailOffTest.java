package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.guardrail.GuardrailMode;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.practiceflow.PracticeScript.Scene;
import kr.mujoong.realtime.practiceflow.StandardPracticeDialogue.ChildTurn;
import kr.mujoong.realtime.session.NoOpPracticeSessionExtension;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.testing.FakeJudgeClient;
import kr.mujoong.realtime.testing.SessionHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 제브 없이(모드 OFF) 기준 연습 대화를 실행부({@code SessionExecutor}) · 라우터 · 상태 기계로 끝까지 돌린다(실측 F8 · 편차 U13-d). 팀장 결정
 * (2026-10-22)으로 OFF에서는 기본 게이트를 지난 생성형 연습 발화(되묻기 · 친구 반응 · 코치 도움)가 검사 없이 재생된다. 이전에는 생성형 슬롯을
 * 폐기해 첫 되묻기에서 장면이 재생 실패로 닫혔다.
 *
 * <p>공급자 · 판정은 시험이 손으로 푸는 가짜이고 생성형 줄의 말은 시험이 정한 합성 문장이다(R-F1). 이 시험의 성공은 테스트 대역 · 합성 대화의 성공이며
 * 실제 API · 실제 제브 · 실제 기기 성공이 아니다(R-M11). OFF는 공개 프로필(demo · prod)에서 기동이 거부되는 dev · test 전용 모드다.
 */
@Tag("practice-flow")
class PracticeFlowGuardrailOffTest {

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
    private final PracticeFlowTestKit.MemoryRecorder recorder = new PracticeFlowTestKit.MemoryRecorder();
    private final List<RuntimeException> failures = new ArrayList<>();
    private final StandardPracticeDialogue standard = StandardPracticeDialogue.load();
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
    void withTheGuardrailOffTheStandardDialoguePlaysEveryGeneratedLineAndEndsLikeTheStandard() {
        assertThat(h.guardrail.mode()).as("기본 장치는 제브 단계를 건너뛰는 OFF").isEqualTo(GuardrailMode.OFF);
        Scene base = standard.script().baseScene();
        Scene variant = standard.script().variantScene().orElseThrow();
        List<ChildTurn> child = standard.childTurns();
        h.activate();

        // [장면 1] 무중의 설명 → 친구 첫 대사 → 친구 기회 문장(승인 원문)
        play(base.sceneDescription());
        play(base.partnerOpener());
        play(base.opportunitySentence());
        // 아이: 어… 이거 아닌데. → J2 일부 전달 → 친구 되묻기(생성형, OFF라 검사 없이 재생)
        answer(child.get(0), "FULL");
        play(REASK);
        // 아이: 파란색! … → J3 전달됨 → 친구 반응(생성형)
        answer(child.get(1), "STILL_SHORT");
        play(REACTION_ONE);

        // [장면 2] 무중의 변형 제안(승인) → 수락 → 친구 기회 문장
        play(variant.sceneDescription() + " " + PracticeInstructions.VARIANT_OFFER_QUESTION);
        answer(child.get(2), "UNCLEAR");
        play(variant.opportunitySentence());
        // 아이: 음… 몰라. 뭐라고 해? → J2 도움 요청 → 전환 알림(승인) → 코치 도움(생성형)
        answer(child.get(3), "PARTIAL");
        play(APPROVED.get("COACH_SWITCH"));
        play(COACH);
        // 아이: 아니, 나 딸기 우유 달라고 했어. → J5 → 복귀 알림(승인) → 친구 반응(생성형)
        answer(child.get(4), "PARTIAL");
        play(APPROVED.get("PEER_RETURN"));
        play(REACTION_TWO);

        assertThat(h.link.outputs()).as("승인 원문 7줄 + 생성형 4줄이 모두 기기로 갔다").hasSize(11);
        assertThat(h.store.guardrails).as("OFF는 제브를 부르지 않고 검사 기록도 없다").isEmpty();
        assertThat(h.executor.status()).as("기준 대화가 끝나면 연습을 마친다").isEqualTo(SessionStatus.CLOSING);
        assertThat(h.transitions.find(h.sessionId).orElseThrow().endDetail()).isEqualTo("PRACTICE_COMPLETED");
        List<SceneRunRecord> rows = List.copyOf(recorder.rows.values());
        assertThat(rows).extracting(SceneRunRecord::sceneKind).containsExactly(SceneKind.BASE, SceneKind.VARIANT);
        assertThat(rows).extracting(SceneRunRecord::liveTiming).as("기준 대화와 같은 결과: 되묻기 뒤 전달 · 도움 뒤 전달")
                .containsExactly(LiveTiming.AFTER_REASK, LiveTiming.AFTER_HELP);
        assertThat(rows).allSatisfy(row -> assertThat(row.facts()).extracting(PracticeFlowTypes.Fact::kind)
                .doesNotContain(PracticeFlowTypes.FactKind.PLAYBACK_FAILED));
        assertThat(failures).isEmpty();
    }

    /** 승인 원문이든 생성형이든 공급자가 그 말을 하면 기기로 가고 재생이 끝난다. 생성형은 지시문 대신 시험이 정한 합성 문장을 말한다. */
    private void play(String text) {
        int before = h.link.outputs().size();
        h.settleSend();
        h.speak(text, 4800);
        h.advance(Duration.ofMillis(801));
        assertThat(h.link.outputs()).as("들린다: %s", text).hasSize(before + 1);
        UUID playback = h.link.outputs().getLast().playbackId();
        for (PlaybackMark.Status status : List.of(PlaybackMark.Status.STARTED, PlaybackMark.Status.COMPLETED)) {
            h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                    new PlaybackMark(playback, status, null, h.clock.now(), null, null), h.clock.now()));
            h.drain();
        }
    }

    /** 아이 말을 넣고 기준 대화의 기대 판정(지점 · 첫 선택지)으로 가짜 판정을 푼다. */
    private void answer(ChildTurn turn, String second) {
        h.childSays("child-" + (++item), turn.text());
        assertThat(judge.last().request().point().name()).as("'%s'의 판정 지점", turn.text()).isEqualTo(turn.point());
        judge.confirm(turn.option(), 0.95, second, 0.02);
        h.drain();
    }
}
