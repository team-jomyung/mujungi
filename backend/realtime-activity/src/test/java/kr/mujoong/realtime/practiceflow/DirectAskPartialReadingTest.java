package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.FIXED_SPEECH;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_ASK;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_HELP;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_INTRO;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_OPPORTUNITY;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_REACTION;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.V2_RELAY;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.directAskScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.directAskScriptWithVariant;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.minimalScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.packageJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.practicelegacy.PracticePorts.Playback;
import kr.mujoong.realtime.practiceflow.FlowState.Phase;
import kr.mujoong.realtime.practiceflow.FlowState.Sub;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Harness;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Slot;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.session.PracticeSessionExtension.ExtensionEvent;
import kr.mujoong.realtime.session.PracticeSessionExtension.PlaybackFact;
import kr.mujoong.realtime.session.PracticeSessionExtension.Signal;
import kr.mujoong.realtime.voice.PracticeSpeech;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * C안 부분 재생 뒤 나머지 다시 읽기 (docs/practice-direct-ask.md 7.6). 조각 단위 승인 낭독(B1)이 원문 중간에서 끊겨 실행부가 '나간 부분까지만' 재생 끝으로
 * 닫으면(들린 위치를 담은 부분 재생 사실), C안은 끊긴 곳이 든 문장부터 끝까지를 새 승인 원문으로 낭독 하나에 한 번 다시 읽고, 다음 걸음(장면 행 · 판정 ·
 * 무응답 시계 · 다음 말)은 그 재생이 끝난 뒤에 간다. 다시 읽은 나머지도 끊기면 지금 있는 복구 길(같은 글 재시도 → '뜻 전하기' 복구 → 건너뛰기)을 그대로
 * 타고 횟수 상한은 늘지 않는다. 유연 대화는 부분 재생을 지금처럼 재생 끝으로 본다. 가짜 출력 · 가짜 판정 · 수동 시계 위의 합성 대화이다(테스트 대역
 * 증거, 실제 공급자 · 실제 기기 성공이 아니다).
 */
@Tag("practice-flow")
class DirectAskPartialReadingTest {

    private static final UUID GOAL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SCENARIO = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    /** 5회차 실측에서 첫 단위 "너는"만 들린 상황 안내와 같은 모양(첫 단위가 "너는"인 두 문장 안내). */
    private static final String YOU_INTRO = "너는 친구랑 공룡 그림을 같이 그리는 중이야. 친구한테 같이 그리자고 했어.";
    private static final String V2_SECOND = "너는 친구한테 같이 그리자고 했어.";
    private static final String REREAD = PracticeConversation.DIAG_REMAINDER_REREAD;
    private static final String CUT_AGAIN = PracticeConversation.DIAG_REMAINDER_CUT_AGAIN;

    private static Harness directAsk() {
        return new Harness(packageJson(GOAL, directAskScript(SCENARIO)), FIXED_SPEECH);
    }

    /** 둘째 문장 "너는 친구한테 …"의 중간(앞 두 글자 "너는"까지) 위치. */
    private static int insideSecondSentence() {
        return V2_INTRO.indexOf(V2_SECOND) + "너는".length();
    }

    // ---- ① · ② 나머지를 정하는 규칙 ----

    @Test
    void anIntroductionCutAfterItsFirstWordIsReadAgainFromTheFirstSentenceBeforeTheSceneStarts() {
        Harness h = new Harness(packageJson(GOAL, directAskScript(SCENARIO).replace(V2_INTRO, YOU_INTRO)), FIXED_SPEECH);
        h.toIntroduction();
        Slot cut = h.out.lastSlot();
        assertThat(cut.text()).isEqualTo(YOU_INTRO);

        // 첫 단위 "너는"만 나가고 다음 단위가 원문과 달라 실행부가 나간 부분까지만 닫았다
        assertThat(h.partial("너는".length())).isEqualTo(Signal.NONE);

        Slot reread = h.out.lastSlot();
        assertThat(reread.correlationId()).isNotEqualTo(cut.correlationId());
        assertThat(reread.generated()).as("나머지도 승인 원문 낭독").isFalse();
        assertThat(reread.text()).as("끊긴 곳이 첫 문장 안이라 처음부터 전체").isEqualTo(YOU_INTRO);
        assertThat(reread.speech().readingCue()).isEqualTo(PracticeSpeech.ReadingCue.SCENE);
        assertThat(h.ext.state().phase()).isEqualTo(Phase.SCENE_INTRO);
        assertThat(h.recorder.list()).as("상황 안내가 다 들리기 전이라 장면 행을 만들지 않는다").isEmpty();
        assertThat(h.out.diagnostics).containsExactly(REREAD);

        h.played();

        assertThat(h.recorder.list()).hasSize(1);
        assertThat(h.recorder.factKinds(h.recorder.only())).isEmpty();
        assertThat(h.out.lastSlot().text()).as("나머지까지 들린 뒤 전하기 + 묻기").isEqualTo(V2_OPPORTUNITY);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void anIntroductionCutInsideItsSecondSentenceIsReadAgainFromThatSentence() {
        Harness h = directAsk();
        h.toIntroduction();

        h.partial(insideSecondSentence());

        assertThat(h.out.lastSlot().text()).isEqualTo(V2_SECOND);
        assertThat(h.out.lastSlot().generated()).isFalse();
        h.played();
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_OPPORTUNITY);
        assertThat(h.recorder.list()).hasSize(1);
    }

    @Test
    void aCutRightAfterASentenceEndRereadsOnlyTheSentencesNotYetHeard() {
        Harness h = directAsk();
        h.toIntroduction();
        int afterFirstPeriod = V2_INTRO.indexOf(". ") + 1;

        h.partial(afterFirstPeriod);

        assertThat(h.out.lastSlot().text()).isEqualTo(V2_SECOND);
    }

    @Test
    void aPartialPlaybackMissingOnlyTheFinalPunctuationCountsAsHeardAndMovesOn() {
        Harness h = directAsk();
        h.toIntroduction();

        // 말은 모두 나갔고 말끝 마침표만 전사에 없었다: 다시 읽을 말이 없다
        h.partial(V2_INTRO.length() - 1);

        assertThat(h.out.lastSlot().text()).isEqualTo(V2_OPPORTUNITY);
        assertThat(h.recorder.list()).hasSize(1);
        assertThat(h.out.diagnostics).containsExactly(PracticeConversation.DIAG_REMAINDER_WITHOUT_WORDS);
    }

    // ---- ④ 다시 읽기도 끊기면 기존 복구 길 ----

    @Test
    void aRereadThatIsCutAgainTakesTheSameTextRetryThenTheRecoveryWithoutRaisingTheLimits() {
        Harness h = directAsk();
        h.toIntroduction();
        h.partial(insideSecondSentence());
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_SECOND);

        // 다시 읽은 나머지도 끊겼다: 나머지를 또 셈하지 않고 같은 글(다시 읽던 나머지)을 한 번 더 읽힌다
        h.partial("너는".length());
        Slot retry = h.out.lastSlot();
        assertThat(retry.generated()).isFalse();
        assertThat(retry.text()).isEqualTo(V2_SECOND);

        // 같은 글 재시도도 끊겼다: '뜻 전하기' 생성형 복구 한 번
        h.partial("너는".length());
        Slot recovery = h.out.lastSlot();
        assertThat(recovery.generated()).isTrue();
        assertThat(recovery.text()).isEqualTo(PracticeInstructions.recoverApprovedNarration(V2_SECOND));
        assertThat(h.out.diagnostics).containsExactly(REREAD, CUT_AGAIN, CUT_AGAIN);
        assertThat(h.recorder.list()).as("복구가 끝나기 전까지 다음으로 가지 않는다").isEmpty();

        h.played();

        assertThat(h.recorder.list()).hasSize(1);
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_OPPORTUNITY);
        assertThat(h.out.slots.stream().filter(slot -> !slot.generated()).filter(slot -> slot.text().equals(V2_INTRO)
                || slot.text().equals(V2_SECOND))).as("승인 낭독 3번(원문 · 나머지 · 같은 글 재시도)").hasSize(3);
        assertThat(h.out.generatedSlots()).as("목표 안내 + 복구 한 번").hasSize(2);
    }

    @Test
    void aRereadTheOutputRefusesBeforePlayingRetriesOnceThenRecoversAndAnUnheardRecoveryIsSkipped() {
        Harness h = directAsk();
        h.toIntroduction();
        h.partial(insideSecondSentence());

        h.mismatched();
        assertThat(h.out.lastSlot().text()).as("원문 불일치는 지금처럼 같은 글 재시도").isEqualTo(V2_SECOND);
        h.mismatched();
        assertThat(h.out.lastSlot().generated()).isTrue();

        // 복구도 못 들려줬다: 다시 내지 않고 건너뛴다(FT-1023-g, 장면 행 + 재생 실패 사실)
        h.end(Playback.FAILED);

        assertThat(h.out.lastSlot().text()).isEqualTo(V2_OPPORTUNITY);
        assertThat(h.recorder.factKinds(h.recorder.only())).containsExactly(FactKind.PLAYBACK_FAILED);
        assertThat(h.out.diagnostics).containsExactly(REREAD);
    }

    @Test
    void aReadingThatAlreadyUsedItsRetryStillGetsOneRereadButThenGoesStraightToTheRecovery() {
        Harness h = directAsk();
        h.toIntroduction();
        h.mismatched();
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_INTRO);

        h.partial(insideSecondSentence());
        assertThat(h.out.lastSlot().text()).as("재시도가 부분 재생되면 나머지 다시 읽기 한 번").isEqualTo(V2_SECOND);
        h.partial("너는".length());

        assertThat(h.out.lastSlot().generated()).as("같은 글 재시도는 이미 썼다: 곧바로 복구").isTrue();
        assertThat(h.out.slots.stream().filter(slot -> !slot.generated())).hasSize(3);
    }

    @Test
    void eachReadingGetsItsOwnRereadAndALatePartialOfAClosedSlotIsIgnored() {
        Harness h = directAsk();
        h.toIntroduction();
        Slot original = h.out.lastSlot();
        h.partial(insideSecondSentence());
        int slots = h.out.slots.size();

        // 이미 닫은(다시 읽기로 넘긴) 슬롯의 늦은 사실은 무시한다
        h.ext.onExtensionEvent(new ExtensionEvent(ExtensionEvent.Kind.PLAYBACK_FACT, "u5b-playback", original.correlationId(),
                new PlaybackFact(original.speech(), Playback.COMPLETED, original.text(), null, Duration.ZERO, 1)), h.out);
        assertThat(h.out.slots).hasSize(slots);

        h.played();
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_OPPORTUNITY);
        h.partial("그런데".length());

        assertThat(h.out.lastSlot().text()).as("다음 낭독은 제 다시 읽기를 한 번 가진다").isEqualTo(V2_OPPORTUNITY);
        assertThat(h.out.diagnostics).containsExactly(REREAD, REREAD);
    }

    // ---- ⑤ 판정 · 다음 말은 나머지 재생 뒤 ----

    @Test
    void anAnswerGivenWhileTheRemainderIsReadIsJudgedOnlyAfterTheRemainderHasPlayed() {
        Harness h = directAsk();
        h.toIntroduction();
        h.played();
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_OPPORTUNITY);

        h.partial("그런데".length());
        assertThat(h.out.lastSlot().text()).as("전하기 문장 안에서 끊겨 전하기 + 묻기 전체").isEqualTo(V2_OPPORTUNITY);
        h.child("손 괜찮아?");
        assertThat(h.out.judgments).as("묻기를 다 들려주기 전에는 판정하지 않는다").isEmpty();

        h.played();

        assertThat(h.out.judgments).singleElement().satisfies(judgment -> assertThat(judgment.point()).isEqualTo(JudgmentPoint.J2));
    }

    @Test
    void theNoResponseClockStartsOnlyAfterTheRereadAskHasPlayed() {
        Harness h = directAsk();
        h.toIntroduction();
        h.played();

        h.partial(V2_RELAY.length() + 1 + "너라면".length());
        assertThat(h.out.lastSlot().text()).as("묻는 문장 안에서 끊겨 묻기부터").isEqualTo(V2_ASK);
        assertThat(h.out.timers).doesNotContainKey(PracticeConversation.TIMER_NO_RESPONSE);

        h.played();

        assertThat(h.out.timers).containsKey(PracticeConversation.TIMER_NO_RESPONSE);
        assertThat(h.ext.state().phase()).isEqualTo(Phase.OPPORTUNITY);
    }

    @Test
    void aCutHelpIsReadAgainBeforeTheRetryWaitsForTheChild() {
        Harness h = directAsk();
        h.toIntroduction();
        h.played();
        h.played();
        h.child("뭐라고 말해야 돼?");
        h.result("J2", "HELP");
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_HELP);

        h.partial("친구가 힘들".length());
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_HELP);
        assertThat(h.ext.state().phase()).isEqualTo(Phase.FOCUS);

        h.played();

        assertThat(h.ext.state().phase()).isEqualTo(Phase.RETRY);
        assertThat(h.out.timers).containsKey(PracticeConversation.TIMER_NO_RESPONSE);
        assertThat(h.recorder.factKinds(h.recorder.only())).as("도움은 한 번만 기록한다")
                .containsOnlyOnce(FactKind.FOCUS_HELP, FactKind.EXPRESSION_EXAMPLE);
    }

    @Test
    void aCutFriendReactionIsReadAgainBeforeTheVariantIsOffered() {
        Harness h = new Harness(packageJson(GOAL, directAskScriptWithVariant(SCENARIO)), FIXED_SPEECH);
        h.toOpportunity();
        h.child("손 괜찮아? 그럼 다음에 같이 그리자");
        h.result("J2", "FULL");
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_REACTION);

        h.partial("그렇게".length());
        assertThat(h.out.lastSlot().text()).isEqualTo(V2_REACTION);
        assertThat(h.ext.state().sub()).isEqualTo(Sub.FRIEND_REACTION);

        h.played();

        assertThat(h.out.lastSlot().text()).isEqualTo(FIXED_SPEECH.get("VARIANT_OFFER"));
        assertThat(h.recorder.only().liveTiming()).isEqualTo(PracticeFlowTypes.LiveTiming.IMMEDIATE);
    }

    // ---- ⑥ 유연 대화는 지금처럼 · ⑦ 아무것도 안 나간 불일치는 지금처럼 ----

    @Test
    void theFlexiblePathStillTreatsAPartialPlaybackAsPlayed() {
        Harness partial = new Harness(packageJson(GOAL, minimalScript(SCENARIO)), Map.of(), PracticeFlowTestKit.flexible());
        Harness played = new Harness(packageJson(GOAL, minimalScript(SCENARIO)), Map.of(), PracticeFlowTestKit.flexible());
        partial.toIntroduction();
        played.toIntroduction();

        partial.partial(2);
        played.played();

        assertThat(texts(partial)).as("부분 재생도 재생 끝과 같은 다음 말").isEqualTo(texts(played));
        assertThat(partial.ext.state().phase()).isEqualTo(played.ext.state().phase());
        assertThat(partial.ext.state().sub()).isEqualTo(played.ext.state().sub());
        assertThat(partial.recorder.list()).hasSize(1);
        assertThat(partial.out.diagnostics).isEmpty();
    }

    @Test
    void aMismatchBeforeAnythingWasHeardStillAsksForTheWholeTextAgainWithoutAReread() {
        Harness h = directAsk();
        h.toIntroduction();

        h.mismatched();

        assertThat(h.out.lastSlot().text()).isEqualTo(V2_INTRO);
        assertThat(h.out.lastSlot().generated()).isFalse();
        assertThat(h.out.diagnostics).isEmpty();
        h.mismatched();
        assertThat(h.out.lastSlot().generated()).as("그다음은 지금처럼 '뜻 전하기' 복구").isTrue();
    }

    private static List<String> texts(Harness h) {
        return h.out.slots.stream().map(Slot::text).toList();
    }
}
