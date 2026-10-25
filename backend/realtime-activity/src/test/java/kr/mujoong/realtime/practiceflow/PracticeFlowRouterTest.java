package kr.mujoong.realtime.practiceflow;

import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.FIXED_SPEECH;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.directAskScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.minimalScript;
import static kr.mujoong.realtime.practiceflow.PracticeScriptFixtures.packageJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.mujoong.realtime.practicelegacy.PracticePorts.Playback;
import kr.mujoong.realtime.practiceflow.PracticeFlowTestKit.Harness;
import kr.mujoong.realtime.session.PracticeSessionExtension;
import kr.mujoong.realtime.session.PracticeSessionExtension.ExtensionEvent;
import kr.mujoong.realtime.session.PracticeSessionExtension.PracticeContext;
import kr.mujoong.realtime.session.PracticeSessionExtension.PracticeOutput;
import kr.mujoong.realtime.session.PracticeSessionExtension.Signal;
import kr.mujoong.realtime.session.PracticeSessionExtension.Utterance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 분기 확장 시험 (U12 D1 · BR1.4 · AC1.1.5). 새 흐름이 읽는 scriptVersion(v1 · C안 v2)이면 새 흐름, 없으면 레거시로 가고
 * {@code controlsOutput}은 분기 전에도 참이며 분기 뒤 모든 시점(아이 말 시작 알림 · 무중 화자 여부 포함)이 고른 쪽으로 위임된다. 레거시 확장이
 * 무엇을 부르든 실패하도록 만든 대역(레거시 평가 · 단서 포트가 실패를 돌려주는 상황) 위에서도 새 흐름이 끝까지 가는지 본다.
 */
@Tag("practice-flow")
class PracticeFlowRouterTest {

    private static final UUID GOAL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SCENARIO = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

    private static PracticeContext context(String practiceJson) {
        return new PracticeContext(UUID.randomUUID(), practiceJson, Set.of(), false, Instant.parse("2026-10-18T09:00:00Z"));
    }

    private final PracticeSessionExtension legacy = mock(PracticeSessionExtension.class);
    private final PracticeSessionExtension flow = mock(PracticeSessionExtension.class);
    private final PracticeOutput output = mock(PracticeOutput.class);
    private final AtomicInteger flowCreated = new AtomicInteger();
    private final PracticeFlowRouter router = new PracticeFlowRouter(legacy, () -> {
        flowCreated.incrementAndGet();
        return flow;
    });

    @Test
    void aPackageWhoseFirstScriptHasTheNewScriptVersionGoesToTheNewFlowAndLegacyIsNeverTouched() {
        PracticeContext context = context(packageJson(GOAL, minimalScript(SCENARIO)));
        when(flow.onActive(context, output)).thenReturn(Signal.NEXT_GOAL);

        Signal signal = router.onActive(context, output);

        assertThat(signal).isEqualTo(Signal.NEXT_GOAL);
        assertThat(flowCreated).hasValue(1);
        verifyNoInteractions(legacy);
        assertThat(router.toString()).contains("flow");
    }

    @Test
    void aPackageWhoseFirstScriptIsTheDirectAskVersionTwoAlsoGoesToTheNewFlow() {
        PracticeContext context = context(packageJson(GOAL, directAskScript(SCENARIO)));
        when(flow.onActive(context, output)).thenReturn(Signal.NONE);

        router.onActive(context, output);

        assertThat(flowCreated).hasValue(1);
        verifyNoInteractions(legacy);
        assertThat(router.toString()).contains("flow");
    }

    @Test
    void anUnknownScriptVersionIsNotTakenByTheNewFlow() {
        String future = packageJson(GOAL, minimalScript(SCENARIO).replace("scenario-script.v1", "scenario-script.v9"));
        PracticeSessionExtension oldOne = mock(PracticeSessionExtension.class);
        PracticeContext context = context(future);
        PracticeFlowRouter old = new PracticeFlowRouter(oldOne, () -> {
            throw new AssertionError("새 흐름을 만들면 안 된다");
        });

        old.onActive(context, output);

        verify(oldOne).onActive(context, output);
    }

    @Test
    void aPackageWithoutAScriptVersionOrWithoutAFirstScriptOrUnreadableGoesToTheLegacyExtension() {
        for (String json : new String[] {packageJson(GOAL, null), "{\"goals\":[]}", "not json"}) {
            PracticeSessionExtension oldOne = mock(PracticeSessionExtension.class);
            PracticeContext context = context(json);
            when(oldOne.onActive(context, output)).thenReturn(Signal.NONE);
            PracticeFlowRouter old = new PracticeFlowRouter(oldOne, () -> {
                throw new AssertionError("새 흐름을 만들면 안 된다");
            });

            assertThat(old.onActive(context, output)).as(json).isEqualTo(Signal.NONE);

            verify(oldOne).onActive(context, output);
            assertThat(old.toString()).contains("legacy");
        }
    }

    @Test
    void anEmptyPracticeFragmentFailsTheStartInsteadOfFallingBackToLegacy() {
        assertThatThrownBy(() -> router.onActive(context(null), output)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> router.onActive(context("  "), output)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(legacy);
        assertThat(flowCreated).hasValue(0);
    }

    @Test
    void controlsOutputIsTrueBeforeAndAfterTheChoiceSoTheExecutorMayAskBeforeOnActive() {
        assertThat(router.controlsOutput()).isTrue();
        assertThat(router.toString()).contains("none");

        router.onActive(context(packageJson(GOAL, null)), output);

        assertThat(router.controlsOutput()).isTrue();
        verify(legacy, never()).controlsOutput();
    }

    @Test
    void beforeTheChoiceEveryMomentIsHarmlessAndAfterwardEveryMomentIsDelegatedToTheChosenSide() {
        Utterance utterance = new Utterance(1, Instant.EPOCH, "말", false, false);
        ExtensionEvent event = new ExtensionEvent(ExtensionEvent.Kind.TIMER_FIRED, "no-response", null, null);
        assertThat(router.onChildUtterance(utterance, output)).isEqualTo(Signal.NONE);
        assertThat(router.onChildSpeechStarted(Instant.EPOCH, output)).isEqualTo(Signal.NONE);
        assertThat(router.onExtensionEvent(event, output)).isEqualTo(Signal.NONE);
        assertThat(router.speaksAsMujoong()).as("고르기 전에는 역할 대화 화자").isFalse();
        assertThat(router.progress()).isNull();
        assertThat(router.practiceRecord()).isNull();
        assertThat(router.settlementDelay(Instant.EPOCH)).isEqualTo(Duration.ZERO);
        router.onStopping();
        router.onSettle(null);
        verifyNoInteractions(legacy, flow);

        PracticeContext context = context(packageJson(GOAL, minimalScript(SCENARIO)));
        when(flow.onActive(context, output)).thenReturn(Signal.NONE);
        when(flow.onChildUtterance(utterance, output)).thenReturn(Signal.LAST_GOAL);
        when(flow.onChildSpeechStarted(Instant.EPOCH, output)).thenReturn(Signal.NEXT_GOAL);
        when(flow.speaksAsMujoong()).thenReturn(true);
        when(flow.onExtensionEvent(event, output)).thenReturn(Signal.NEXT_GOAL);
        PracticeSessionExtension.Progress progress = new PracticeSessionExtension.Progress(1, 1, "목표", "FIRST_RESPONSE");
        when(flow.progress()).thenReturn(progress);
        when(flow.practiceRecord()).thenReturn(Map.of("flowVersion", "practice-conversation-v1"));
        when(flow.settlementDelay(Instant.EPOCH)).thenReturn(Duration.ofSeconds(3));
        router.onActive(context, output);

        assertThat(router.onChildUtterance(utterance, output)).isEqualTo(Signal.LAST_GOAL);
        assertThat(router.onChildSpeechStarted(Instant.EPOCH, output)).isEqualTo(Signal.NEXT_GOAL);
        assertThat(router.speaksAsMujoong()).isTrue();
        assertThat(router.onExtensionEvent(event, output)).isEqualTo(Signal.NEXT_GOAL);
        assertThat(router.progress()).isEqualTo(progress);
        assertThat(router.practiceRecord()).isEqualTo(Map.of("flowVersion", "practice-conversation-v1"));
        assertThat(router.settlementDelay(Instant.EPOCH)).isEqualTo(Duration.ofSeconds(3));
        router.onStopping();
        router.onSettle(null);
        verify(flow).onStopping();
        verify(flow).onSettle(null);
        verifyNoInteractions(legacy);
    }

    @Test
    void theChoiceIsMadeOnceEvenIfOnActiveIsCalledAgain() {
        PracticeContext context = context(packageJson(GOAL, minimalScript(SCENARIO)));
        when(flow.onActive(any(), any())).thenReturn(Signal.NONE);

        router.onActive(context, output);
        router.onActive(context, output);

        assertThat(flowCreated).hasValue(1);
    }

    @Test
    void theNewFlowRunsToTheEndEvenWhenEveryLegacyPortWouldFail() {
        // 레거시 평가 · 단서 · 시나리오 포트가 모두 실패를 돌려주는 상황: 레거시 확장은 무엇이 불리든 실패하는 대역이다(AC1.1.5, BR2.5)
        PracticeSessionExtension failingLegacy = mock(PracticeSessionExtension.class, withSettings().defaultAnswer(invocation -> {
            throw new AssertionError("레거시 확장을 부르면 안 된다: " + invocation.getMethod().getName());
        }));
        Harness h = new Harness(packageJson(GOAL, directAskScript(SCENARIO)), FIXED_SPEECH);
        PracticeFlowRouter routed = new PracticeFlowRouter(failingLegacy, () -> h.ext);

        assertThat(routed.controlsOutput()).isTrue();
        assertThat(routed.onActive(h.context, h.out)).isEqualTo(Signal.NONE);
        assertThat(routed.speaksAsMujoong()).as("C안은 장면 안 말을 모두 무중이 한다").isTrue();
        routed.onExtensionEvent(playedEvent(h), h.out); // 목표 안내
        routed.onExtensionEvent(playedEvent(h), h.out); // 상황 안내
        routed.onExtensionEvent(playedEvent(h), h.out); // 친구 말 전하기 + 묻기
        routed.onChildSpeechStarted(Instant.EPOCH, h.out);
        routed.onChildUtterance(new Utterance(1, Instant.EPOCH, "손 괜찮아?", false, false), h.out);
        routed.onExtensionEvent(new ExtensionEvent(ExtensionEvent.Kind.JUDGMENT_RESULT, "judgment", h.out.lastJudgment().correlationId(),
                Map.of("point", "J2", "firstOption", "FULL", "secondOption", "NONE")), h.out);
        assertThat(h.recorder.only().endedAt()).as("친구 반응을 재생하기 전").isNull();
        assertThat(routed.onExtensionEvent(playedEvent(h), h.out)).as("친구 반응 뒤 목표 회고").isEqualTo(Signal.NONE);
        assertThat(routed.onExtensionEvent(playedEvent(h), h.out)).isEqualTo(Signal.LAST_GOAL);

        assertThat(routed.progress()).isNotNull();
        assertThat(routed.practiceRecord()).isNotNull();
        assertThat(h.recorder.only().liveTiming()).isNotNull();
        assertThat(h.failures).isEmpty();
    }

    private static ExtensionEvent playedEvent(Harness h) {
        PracticeFlowTestKit.Slot slot = h.out.lastSlot();
        return new ExtensionEvent(ExtensionEvent.Kind.PLAYBACK_FACT, "u5b-playback", slot.correlationId(),
                new PracticeSessionExtension.PlaybackFact(slot.speech(), Playback.COMPLETED, slot.text()));
    }
}
