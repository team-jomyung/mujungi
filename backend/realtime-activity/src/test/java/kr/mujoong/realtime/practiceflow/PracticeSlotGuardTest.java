package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.minimalScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.packageJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.realtime.practicelegacy.PracticePorts.Playback;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Harness;
import kr.mujoong.realtime.session.PracticeSessionExtension.Signal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 4회차 R4 — 슬롯 안전 시계(실측 B4: 코치 도움이 지시 뒤 14.5 ~ 18.5초에야 들려, 지시 때 건 20초 시계가 들리는 도중에 끝나 장면이 NONE이 됐다). 재생이
 * 시작되기 전에는 실행부의 대본 줄 음성 기다림 · 지시 미룸 · 새 말 기다림 · 한 번 다시 보냄 · 생성을 덮는 긴 시계를 걸고, 재생이 시작되면(STARTED) 재생
 * 길이를 덮는 시계로 다시 건다. 끝 사실이 끝내 오지 않으면 지금처럼 실패로 닫는다. 기록하는 가짜 출력 · 수동 시계 위의 상태 기계 시험이다(테스트 대역
 * 증거, 실제 AI · 실제 기기가 아니다, R-M11).
 */
@Tag("practice-flow")
class PracticeSlotGuardTest {

    private static final UUID GOAL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SCENARIO = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final Map<String, String> APPROVED = Map.of("COACH_SWITCH", "잠깐, 무중이 도와줄게", "PEER_RETURN", "다시 친구 역할로 돌아갈게");
    /** 운영 기본값으로 셈한 재생 시작 전 시계(2s + 2 × (3s + 2 × 10s) + 20s + 0.8s + 0.8s, PracticeFlowSettingsTest가 운영 설정에서 같은 값을 본다). */
    private static final Duration START_GUARD = Duration.ofMillis(69_600);
    private static final Duration PLAY_GUARD = Duration.ofSeconds(20);

    private static Harness harness() {
        PracticeFlowSettings settings = new PracticeFlowSettings(Duration.ofSeconds(10), Duration.ofSeconds(90), Duration.ofMinutes(10),
                PLAY_GUARD, 2, 1, START_GUARD);
        return new Harness(packageJson(GOAL, minimalScript(SCENARIO)), APPROVED, settings);
    }

    private static String guard(UUID slot) {
        return PracticeConversation.TIMER_SLOT_GUARD + slot;
    }

    @Test
    void aNewSlotGetsTheLongStartGuardAndPlaybackStartReArmsItWithThePlaybackGuard() {
        Harness h = harness();
        h.activate();
        UUID intro = h.out.lastSlot().correlationId();
        assertThat(h.out.scheduleLog).contains(guard(intro) + ":PT1M9.6S");

        h.advance(Duration.ofSeconds(30));
        h.end(Playback.STARTED);

        assertThat(h.out.scheduleLog).contains(guard(intro) + ":PT20S");
        assertThat(h.out.timers.get(guard(intro))).as("재생이 시작된 때부터 다시 잰다").isEqualTo(h.clock.now().plus(PLAY_GUARD));
    }

    @Test
    void theStartGuardWaitsOutADeferredAttemptAResendAndTheGenerationBeforeFailing() {
        Harness h = harness();
        h.activate();
        UUID intro = h.out.lastSlot().correlationId();

        assertThat(h.advance(START_GUARD.minusMillis(1))).isEqualTo(Signal.NONE);
        assertThat(h.out.cancelledSpeech).as("한 번 다시 보낸 지시의 새 말까지 기다리는 동안에는 끝내지 않는다").isEmpty();

        Signal end = h.advance(Duration.ofMillis(1));

        assertThat(h.out.cancelledSpeech).as("시작조차 안 한 슬롯은 시작 전 시계 끝에 실패로 닫는다").containsExactly(intro);
        assertThat(end).isEqualTo(Signal.LAST_GOAL);
    }

    @Test
    void aSlotThatIsPlayingDoesNotExpireWhileItPlaysAndItsEndFactIsHandledAsBefore() {
        Harness h = harness();
        h.activate();
        h.advance(Duration.ofSeconds(50));
        h.end(Playback.STARTED);

        h.advance(PLAY_GUARD.minusMillis(1));
        assertThat(h.out.cancelledSpeech).as("들리는 도중에는 만료하지 않는다").isEmpty();
        h.played();

        assertThat(h.out.lastSlot().text()).as("도입이 끝나 기회 문장으로 간다").isEqualTo("친구가 말했어. 그거 줘.");
        assertThat(h.recorder.list()).hasSize(1);
        assertThat(h.failures).isEmpty();
    }

    @Test
    void aStartedSlotWhoseEndFactNeverArrivesIsStillClosedAsAFailure() {
        Harness h = harness();
        h.activate();
        UUID intro = h.out.lastSlot().correlationId();
        h.end(Playback.STARTED);

        Signal end = h.advance(PLAY_GUARD);

        assertThat(h.out.cancelledSpeech).containsExactly(intro);
        assertThat(end).as("도입이 끝내 끝나지 않아도 연습은 멈추지 않고 정리한다").isEqualTo(Signal.LAST_GOAL);
    }

    @Test
    void theApprovedNoticeSlotAlsoWaitsLongBeforeItStartsAndShortWhileItPlays() {
        Harness h = harness();
        h.toOpportunity();
        h.child("어떻게 말해?");
        h.result("J2", "HELP");
        assertThat(h.out.announces).containsExactly("COACH");
        assertThat(h.out.scheduleLog).contains(PracticeConversation.TIMER_NOTICE_GUARD + ":PT1M9.6S");

        h.announcedNotice(Playback.STARTED);

        assertThat(h.out.timers.get(PracticeConversation.TIMER_NOTICE_GUARD)).isEqualTo(h.clock.now().plus(PLAY_GUARD));
        h.announcedNotice(Playback.COMPLETED);
        assertThat(h.out.lastSlot().role()).as("알림이 끝나면 코치 도움").isEqualTo("COACH");
        assertThat(h.out.scheduleLog).contains(guard(h.out.lastSlot().correlationId()) + ":PT1M9.6S");
    }

    /** 4회차 R4(실측 B4): 첫 코치 도움은 짧은 요점 · 대본 빈칸 예문 글자 그대로 · '네 말로 해 볼래?'만, 다른 말 없이 짧게 말하게 한다. */
    @Test
    void theFirstCoachHelpAsksForAFewShortPartsOnlyAndKeepsTheScriptFrameVerbatim() {
        PracticeScript.Scene scene = PracticeScriptReader.read(PracticeScriptFixtures.v7Script(SCENARIO)).baseScene();
        PracticeScript.Scene withoutFocus = new PracticeScript.Scene(scene.sceneKey(), scene.sceneKind(), scene.sceneDescription(),
                scene.partnerOpener(), scene.opportunitySentence(), scene.reexplanation(), scene.partnerRole(), java.util.List.of(),
                scene.expressionExamples(), scene.expressionFrames(), scene.relation(), scene.place(), scene.topic());

        String help = PracticeInstructions.coachHelp(scene, RolePolicy.Example.FRAME);
        String shorter = PracticeInstructions.coachHelp(withoutFocus, RolePolicy.Example.FRAME);

        assertThat(help).contains("세 마디만 짧게 말하고 덧붙이지 않는다", "먼저 요점을 쉬운 한 문장으로: ",
                "이어서 빈칸 예문을 '뭐뭐'까지 그대로 말하고 '처럼 말할 수 있어'를 붙인다: " + PracticeScriptFixtures.V7_FRAME,
                "끝으로 '네 말로 해 볼래?'라고 묻는다").doesNotContain("첫째", "둘째", "셋째", "말틀");
        assertThat(shorter).contains("두 마디만 짧게", "이어서 빈칸 예문").doesNotContain("먼저 요점");
        assertThat(help.codePointCount(0, help.length())).isLessThanOrEqualTo(PracticeInstructions.MAX_LENGTH);
        assertThat(help).endsWith("따뜻하게 말한다.");
    }

    @Test
    void aLateStartedFactOfAClosedSlotDoesNotReArmAnything() {
        Harness h = harness();
        h.activate();
        PracticeFlowTestKit.Slot intro = h.out.lastSlot();
        h.played();
        int scheduled = h.out.scheduleLog.size();

        h.slotEvent(intro.correlationId(), Playback.STARTED, intro.speech(), intro.text());

        assertThat(h.out.scheduleLog).as("이미 닫힌 슬롯의 늦은 시작 사실은 무시한다").hasSize(scheduled);
        assertThat(h.out.timers).doesNotContainKey(guard(intro.correlationId()));
    }
}
