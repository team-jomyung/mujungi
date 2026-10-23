package kr.mujoong.realtime.practiceflow;

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
 * 분기 확장 시험 (U12 D1 · BR1.4 · AC1.1.5). scriptVersion이 있으면 새 흐름, 없으면 레거시로 가고 {@code controlsOutput}은 분기 전에도 참이며
 * 분기 뒤 모든 시점이 고른 쪽으로 위임된다. 레거시 확장이 무엇을 부르든 실패하도록 만든 대역(레거시 평가 · 단서 포트가 실패를 돌려주는 상황)
 * 위에서도 새 흐름이 끝까지 가는지 본다.
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
        assertThat(router.onExtensionEvent(event, output)).isEqualTo(Signal.NONE);
        assertThat(router.progress()).isNull();
        assertThat(router.practiceRecord()).isNull();
        assertThat(router.settlementDelay(Instant.EPOCH)).isEqualTo(Duration.ZERO);
        router.onStopping();
        router.onSettle(null);
        verifyNoInteractions(legacy, flow);

        PracticeContext context = context(packageJson(GOAL, minimalScript(SCENARIO)));
        when(flow.onActive(context, output)).thenReturn(Signal.NONE);
        when(flow.onChildUtterance(utterance, output)).thenReturn(Signal.LAST_GOAL);
        when(flow.onExtensionEvent(event, output)).thenReturn(Signal.NEXT_GOAL);
        PracticeSessionExtension.Progress progress = new PracticeSessionExtension.Progress(1, 1, "목표", "FIRST_RESPONSE");
        when(flow.progress()).thenReturn(progress);
        when(flow.practiceRecord()).thenReturn(Map.of("flowVersion", "practice-conversation-v1"));
        when(flow.settlementDelay(Instant.EPOCH)).thenReturn(Duration.ofSeconds(3));
        router.onActive(context, output);

        assertThat(router.onChildUtterance(utterance, output)).isEqualTo(Signal.LAST_GOAL);
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
    void aChildSpeechStartIsHarmlessBeforeTheChoiceAndDelegatedToTheChosenSideAfterIt() {
        assertThat(router.onChildSpeechStarted(Instant.EPOCH, output)).isEqualTo(Signal.NONE);
        verifyNoInteractions(legacy, flow);

        PracticeContext context = context(packageJson(GOAL, minimalScript(SCENARIO)));
        when(flow.onActive(context, output)).thenReturn(Signal.NONE);
        when(flow.onChildSpeechStarted(Instant.EPOCH, output)).thenReturn(Signal.NEXT_GOAL);
        router.onActive(context, output);

        assertThat(router.onChildSpeechStarted(Instant.EPOCH, output)).isEqualTo(Signal.NEXT_GOAL);
        verify(flow).onChildSpeechStarted(Instant.EPOCH, output);
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
        Harness h = new Harness(packageJson(GOAL, minimalScript(SCENARIO)), Map.of());
        PracticeFlowRouter routed = new PracticeFlowRouter(failingLegacy, () -> h.ext);

        assertThat(routed.controlsOutput()).isTrue();
        assertThat(routed.onActive(h.context, h.out)).isEqualTo(Signal.NONE);
        routed.onExtensionEvent(playedEvent(h), h.out);
        routed.onExtensionEvent(playedEvent(h), h.out);
        routed.onChildUtterance(new Utterance(1, Instant.EPOCH, "그거 줘", false, false), h.out);
        routed.onExtensionEvent(new ExtensionEvent(ExtensionEvent.Kind.JUDGMENT_RESULT, "judgment", h.out.lastJudgment().correlationId(),
                Map.of("point", "J2", "firstOption", "FULL", "secondOption", "NONE")), h.out);

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
