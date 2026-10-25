package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.fullScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.minimalScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.packageJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.realtime.judgment.JudgmentPoint;
import kr.mujoong.realtime.practicelegacy.PracticePorts.Playback;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Harness;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Slot;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.ConsentAnswer;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FlowRole;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.VariantSkipReason;
import kr.mujoong.realtime.session.PracticeSessionExtension.ExtensionEvent;
import kr.mujoong.realtime.session.PracticeSessionExtension.GuardrailBlockedEvent;
import kr.mujoong.realtime.session.PracticeSessionExtension.Signal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 유연 대화 경로의 역할 전환 · 코치 시험 (U12 BR5.1 ~ BR5.5 · BR6.3, 설정 {@code flexible-dialogue=true}로 되돌린 경로). 대본형 경로가 C안(역할
 * 없음)으로 바뀌어, 역할 전환 알림 · 코치 도움 · 복귀 알림 · 다시 해 보기 두 번은 이제 유연 대화에서만 돈다. 예전 대본형 시험이 지키던 이 장치를 같은
 * 갈래의 유연 대화 시험으로 옮겨 지킨다(대본형 쪽 C안 단언은 {@code PracticeConversationTest} · {@code DirectAskConversationTest}). 가짜 출력 ·
 * 가짜 판정 · 수동 시계 위의 합성 대화이다(테스트 대역 증거).
 */
@Tag("practice-flow")
class FlexibleRoleNoticeTest {

    private static final UUID GOAL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SCENARIO = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final String CRITERIA = "원하는 것을 말로 요청한다";
    private static final Map<String, String> NOTICES = Map.of("COACH_SWITCH", "이제 코치가 도와줄게.", "PEER_RETURN", "다시 친구로 돌아왔어.");

    private static Harness minimal() {
        return new Harness(packageJson(GOAL, minimalScript(SCENARIO)), NOTICES, PracticeFlowTestKit.flexible());
    }

    private static Harness full() {
        return new Harness(packageJson(GOAL, fullScript(SCENARIO)), NOTICES, PracticeFlowTestKit.flexible());
    }

    /** 무응답 · 슬롯 안전 시계를 멀리 두고 집중 · 목표 시계만 보는 유연 대화 설정. */
    private static Harness quiet(Duration focusMax) {
        Duration hour = Duration.ofHours(1);
        return new Harness(packageJson(GOAL, minimalScript(SCENARIO)), NOTICES,
                new PracticeFlowSettings(hour, focusMax, Duration.ofHours(2), hour, 2, 1, true));
    }

    /** 아이가 도움을 청해 코치 전환 알림이 열린 상태. */
    private static void askForHelp(Harness h) {
        h.toOpportunity();
        h.child("어떻게 말해?");
        h.result("J2", "HELP");
    }

    private static List<Slot> coachSlots(Harness h) {
        return h.out.generatedSlots().stream().filter(slot -> slot.role().equals("COACH")).toList();
    }

    // ---- 역할 전환 알림 ----

    @ParameterizedTest
    @EnumSource(value = Playback.class, names = {"FAILED", "INTERRUPTED"})
    void aRoleSwitchNoticeThatDoesNotPlayIsSkippedAndTheCoachStillHelps(Playback status) {
        // FT-1023-g: 알림을 못 들려줘도 도움 단계를 닫지 않는다(BR5.1 · BR5.5의 '도움 없이 정리'를 대체)
        Harness h = minimal();
        askForHelp(h);
        assertThat(h.out.announces).containsExactly("COACH");

        Signal end = h.announcedNotice(status);

        assertThat(end).isEqualTo(Signal.NONE);
        assertThat(coachSlots(h)).as("코치 발화").hasSize(1);
        assertThat(h.recorder.helps).extracting(HelpRecord::kind).contains(HelpKind.ROLE_SWITCH, HelpKind.FOCUS_HELP);
        assertThat(h.ext.state().role()).isEqualTo(FlowRole.COACH);
        assertThat(h.recorder.factKinds(h.recorder.only())).containsSubsequence(FactKind.COACH_SWITCH, FactKind.FOCUS_HELP);
        assertThat(h.recorder.factKinds(h.recorder.only()).contains(FactKind.PLAYBACK_FAILED)).isEqualTo(status == Playback.FAILED);
    }

    @Test
    void whenTheOutputCannotAnnounceTheSwitchTheNoticeIsGeneratedAndTheCoachFollowsOnlyAfterItPlayed() {
        Harness h = minimal();
        h.out.announceResult = false;
        askForHelp(h);
        assertThat(h.out.announces).containsExactly("COACH");
        Slot notice = h.out.lastSlot();
        assertThat(notice.generated()).isTrue();
        assertThat(notice.role()).isEqualTo("COACH");
        assertThat(coachSlots(h)).hasSize(1);

        h.played();

        assertThat(coachSlots(h)).as("알림이 끝난 뒤에만 코치 발화").hasSize(2);
        assertThat(h.ext.state().role()).isEqualTo(FlowRole.COACH);
    }

    @Test
    void withoutApprovedNoticeSentencesInTheStartPackageTheNoticeIsGeneratedWithoutCallingTheAnnouncer() {
        // core는 C안부터 COACH_SWITCH · PEER_RETURN을 보내지 않는다(ai-io.md 10.18). 유연 대화는 그때 생성형 알림으로 간다(BR5.4)
        Harness h = new Harness(packageJson(GOAL, minimalScript(SCENARIO)), Map.of(), PracticeFlowTestKit.flexible());
        askForHelp(h);

        assertThat(h.out.announces).isEmpty();
        assertThat(h.out.lastSlot().generated()).isTrue();
        assertThat(h.out.lastSlot().role()).isEqualTo("COACH");
        h.played();
        assertThat(h.ext.state().role()).isEqualTo(FlowRole.COACH);
        assertThat(h.recorder.factKinds(h.recorder.only())).contains(FactKind.COACH_SWITCH, FactKind.FOCUS_HELP);
    }

    @Test
    void aGeneratedNoticeThatIsBlockedTwiceIsNeverReplayedAndTheCoachFollows() {
        // FT-1023-g: 막힌 알림은 다시 내지 않고 코치 도움으로 간다
        Harness h = minimal();
        h.out.announceResult = false;
        askForHelp(h);
        Slot notice = h.out.lastSlot();

        Signal end = h.event(ExtensionEvent.Kind.GUARDRAIL_BLOCKED, "guardrail-blocked", notice.correlationId(),
                new GuardrailBlockedEvent(notice.correlationId(), kr.mujoong.realtime.guardrail.GuardrailRequest.Mode.PRACTICE_COACH,
                        "BLOCK", true));

        assertThat(end).isEqualTo(Signal.NONE);
        assertThat(h.ext.state().role()).isEqualTo(FlowRole.COACH);
        assertThat(coachSlots(h)).as("막힌 알림 + 코치 도움").hasSize(2);
        assertThat(coachSlots(h).get(1).text()).isNotEqualTo(notice.text());
        assertThat(h.recorder.factKinds(h.recorder.only())).as("막힘은 재생 실패 사실이 아니다").doesNotContain(FactKind.PLAYBACK_FAILED);
    }

    @Test
    void anAnnouncedNoticeThatNeverEndsIsSkippedWhenItsSafetyClockExpiresAndTheCoachFollows() {
        // FT-1023-g: 끝나지 않은 알림은 시한에 건너뛰고(재생 실패 사실) 코치 도움으로 간다
        Harness h = minimal();
        askForHelp(h);
        assertThat(h.out.scheduleLog).contains("slot-guard-notice:PT20S");

        assertThat(h.advance(Duration.ofSeconds(20))).isEqualTo(Signal.NONE);

        assertThat(h.recorder.factKinds(h.recorder.only())).containsExactly(FactKind.COACH_SWITCH, FactKind.PLAYBACK_FAILED,
                FactKind.FOCUS_HELP);
        assertThat(h.ext.state().role()).isEqualTo(FlowRole.COACH);
        assertThat(coachSlots(h)).hasSize(1);
    }

    // ---- 코치 도움 · 다시 해 보기 ----

    @Test
    void twoRetriesAreAllowedAndTheThirdPartialClosesAsFocusLimitWithTheExampleOnlyInTheSecondCoachRound() {
        Harness h = full();
        askForHelp(h);
        h.announcedNotice(Playback.COMPLETED);
        h.played();
        h.child("음");
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J5);
        h.result("J5", "PARTIAL");
        h.played();
        h.child("음음");
        h.result("J5", "PARTIAL");

        List<Slot> coach = coachSlots(h);
        assertThat(coach).hasSize(2);
        assertThat(coach.get(0).text()).doesNotContain("파란 상자를 줘");
        assertThat(coach.get(1).text()).as("도움을 청한 아이에게 둘째 코치 발화는 대본의 표현 예시를 쓴다").contains("파란 상자를 줘");
        assertThat(h.out.announces).as("둘째 한도에서 정리하며 복귀 알림").containsExactly("COACH", "PEER");
        h.announcedNotice(Playback.COMPLETED);
        SceneRunRecord row = h.recorder.only();
        assertThat(row.retryCount()).isEqualTo(2);
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.FOCUS_LIMIT);
        assertThat(h.recorder.factKinds(row)).contains(FactKind.EXPRESSION_EXAMPLE, FactKind.COACH_RETURN)
                .doesNotContain(FactKind.ORIGINAL_SCENE_RETURN);
    }

    @Test
    void theFocusClockFiresAtNinetySecondsNotAtEightyNineAndClosesTheSceneWithAReturnNotice() {
        Harness h = quiet(Duration.ofSeconds(90));
        askForHelp(h);
        assertThat(h.out.announces).containsExactly("COACH");
        h.announcedNotice(Playback.COMPLETED);
        assertThat(h.out.lastSlot().role()).isEqualTo("COACH");
        assertThat(h.out.scheduleLog).contains("focus-max:PT1M30S");
        h.played();

        h.advance(Duration.ofSeconds(89));
        assertThat(h.out.announces).as("89초에는 정리하지 않는다").containsExactly("COACH");

        h.advance(Duration.ofSeconds(1));
        assertThat(h.out.announces).as("90초에 장면을 정리하며 복귀 알림을 낸다").containsExactly("COACH", "PEER");
        assertThat(h.announcedNotice(Playback.COMPLETED)).isEqualTo(Signal.NONE);
        assertThat(h.played()).as("목표 회고 뒤 끝").isEqualTo(Signal.LAST_GOAL);

        SceneRunRecord row = h.recorder.only();
        assertThat(h.recorder.factKinds(row)).containsExactly(FactKind.COACH_SWITCH, FactKind.FOCUS_HELP, FactKind.COACH_RETURN,
                FactKind.VARIANT_SKIPPED);
        assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.FOCUS_LIMIT);
        assertThat(row.liveTiming()).isEqualTo(LiveTiming.NONE);
        assertThat(row.retryCount()).isEqualTo(1);
        assertThat(h.recorder.helps).extracting(HelpRecord::kind).containsExactly(HelpKind.ROLE_SWITCH, HelpKind.FOCUS_HELP,
                HelpKind.ROLE_RETURN);
        assertThat(h.recorder.helps.getFirst().consentAnswer()).as("유연 대화는 아이가 청한 도움을 REQUESTED로 남긴다")
                .isEqualTo(ConsentAnswer.REQUESTED);
        assertThat(h.recorder.helps.get(1).exampleGiven()).as("첫 코치 발화는 예시 없이 단서만").isFalse();
        assertThat(h.failures).isEmpty();
    }

    @Test
    void theFocusClockCancelsTheSpeakingCoachAndIgnoresItsLateEnd() {
        Harness h = quiet(Duration.ofSeconds(90));
        askForHelp(h);
        h.announcedNotice(Playback.COMPLETED);
        Slot coach = h.out.lastSlot();

        h.advance(Duration.ofSeconds(90));

        assertThat(h.out.cancelledSpeech).containsExactly(coach.correlationId());
        assertThat(h.slotEvent(coach.correlationId(), Playback.INTERRUPTED, null, coach.text())).isEqualTo(Signal.NONE);
        assertThat(h.announcedNotice(Playback.COMPLETED)).isEqualTo(Signal.NONE);
        assertThat(h.played()).isEqualTo(Signal.LAST_GOAL);
        assertThat(h.recorder.only().variantSkipReason()).isEqualTo(VariantSkipReason.FOCUS_LIMIT);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void aChildAnswerGivenWhileTheCoachSpeaksIsJudgedAsTheRetryAnswerOnceTheCoachWasInterrupted() {
        Harness h = minimal();
        askForHelp(h);
        h.announcedNotice(Playback.COMPLETED);
        int judgments = h.out.judgments.size();

        h.child("파란 상자");
        assertThat(h.out.judgments).as("코치 발화 중에는 판정하지 않는다").hasSize(judgments);
        h.end(Playback.INTERRUPTED);

        assertThat(h.out.judgments).hasSize(judgments + 1);
        assertThat(h.out.lastJudgment().point()).isEqualTo(JudgmentPoint.J5);
        assertThat(h.ext.state().retryCount()).isEqualTo(1);
    }

    @Test
    void theHelpOfferAndRetryReguidesAreGeneratedByTheirOwnRoleWithoutLabelsCriteriaOrChildWords() {
        Harness h = minimal();
        String marker = "표식문장매우특이함";
        h.toOpportunity();
        h.child(marker + "1");
        h.result("J2", "PARTIAL");
        h.played();
        h.child(marker + "2");
        h.result("J2", "PARTIAL");
        assertThat(h.ext.state().phase()).as("정보가 두 번 모자라면 도움 제안").isEqualTo(FlowState.Phase.HELP_OFFER);
        h.played();
        int slots = h.out.slots.size();
        h.advance(Duration.ofSeconds(10));
        assertThat(h.out.slots).as("도움 제안 단계 재안내").hasSize(slots + 1);
        assertThat(h.out.lastSlot().role()).isEqualTo("PEER");
        assertThat(h.out.lastSlot().text()).contains("같은 말로 한 번 더");
        h.played();
        h.child(marker + "3");
        h.result("J4", "ACCEPT");
        h.announcedNotice(Playback.COMPLETED);
        h.played();
        slots = h.out.slots.size();
        h.advance(Duration.ofSeconds(10));
        assertThat(h.out.slots).as("다시 해 보기 단계 재안내는 코치가 한다").hasSize(slots + 1);
        assertThat(h.out.lastSlot().role()).isEqualTo("COACH");
        assertThat(h.out.lastSlot().text()).contains("자기 말로 다시 한 번 해 보자고");

        List<String> forbidden = List.of("FULL", "PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT", "UNREADABLE", "DELIVERED",
                "STILL_SHORT", "ACCEPT", "REFUSE", "UNCLEAR", "COPIED", "%", "확률", CRITERIA, marker, "판정", "J2", "J3", "J4", "J5");
        assertThat(h.out.generatedSlots()).hasSizeGreaterThanOrEqualTo(5).allSatisfy(slot -> {
            assertThat(slot.text()).as(slot.role()).isNotBlank().doesNotContain(forbidden.toArray(new String[0]));
            assertThat(slot.text().codePointCount(0, slot.text().length())).isLessThanOrEqualTo(500);
            assertThat(slot.text().chars().noneMatch(Character::isISOControl)).isTrue();
        });
        assertThat(h.recorder.rows.toString() + h.recorder.helps + h.ext).doesNotContain(marker);
    }
}
