package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.realtime.content.LiveContentRegistry;
import kr.mujoong.realtime.content.LiveContentRegistry.SpeechText;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.realtime.guardrail.ApprovedVerbatimPort;
import kr.mujoong.realtime.guardrail.GuardrailEventRecord;
import kr.mujoong.realtime.testing.FakeGuardrailClient;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 승인 원문 생략 (U13 BR7.1 ~ BR7.5): 승인 발화 · 등록 대본과 원문이 같은 출력은 제브를 부르지 않고(호출 0) 검사 기록 한 행만 남기며, 한 단어라도 바뀐
 * 출력 · 일치를 확인할 수 없는(UNKNOWN) 출력은 새 발화로 검사한다(호출 1). 제브 · 공급자는 모두 대역이다(실제 호출 없음, R-F1 · R-M11).
 */
@Tag("jev-guardrail")
@org.junit.jupiter.api.Tag("service-guardrail")
class GuardrailApprovedSkipTest {

    private static final byte[] AUDIO = new byte[4800];
    private static final String APPROVED = "오늘은 공룡 이야기를 같이 해 보자";
    private static final String SCRIPT = "공룡이 큰 소리로 안녕 하고 인사해요";

    private final SessionHarness h = new SessionHarness();
    private final FakeGuardrailClient guard = h.useGuardrail();
    private final LiveContentRegistry registry = new LiveContentRegistry(LiveContentResources.loadDefault());

    private void pinContent() {
        registry.pin(h.sessionId, Map.of(), Map.of("D_OPENER", new SpeechText(UUID.randomUUID(), APPROVED)));
        registry.registerScript(h.sessionId, UUID.randomUUID(), "script-v1", Map.of("scene", SCRIPT));
        h.guardrail = h.guardrail.withApprovedVerbatim(ApprovedVerbatimPort.of(registry));
    }

    @Test
    void anApprovedSpeechPlayedVerbatimSkipsTheCheckerAndLeavesOneSkipRecord() {
        pinContent();
        h.activate();

        // ASCII 공백만 다른 원문이다(정규화는 ASCII 공백 제거뿐, BR7.3)
        say(APPROVED.replace(" ", "  "));

        assertThat(guard.count()).isZero();
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(records()).singleElement().satisfies(event -> {
            assertThat(event.sourceKind()).isEqualTo(GuardrailEventRecord.SourceKind.APPROVED_VERBATIM_SKIPPED);
            assertThat(event.decision()).isEqualTo(GuardrailEventRecord.Decision.ALLOW);
            assertThat(event.reasonCode()).isEqualTo("APPROVED_SPEECH");
            assertThat(event.latencyMs()).isZero();
        });
        assertThat(h.metrics.count("mujoong.guardrail.calls", "mode", "real", "source", "approved_verbatim_skipped"))
                .isEqualTo(1.0);
        assertThat(h.metrics.count("mujoong.guardrail.calls", "mode", "real", "source", "new_utterance")).isZero();
    }

    @Test
    void aRegisteredScriptSentencePlayedVerbatimIsSkippedToo() {
        pinContent();
        h.activate();

        say(SCRIPT);

        assertThat(guard.count()).isZero();
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(records()).singleElement().satisfies(event -> assertThat(event.reasonCode()).isEqualTo("SESSION_SCRIPT"));
    }

    @Test
    void aSentenceWithOneWordChangedIsANewUtteranceAndCallsTheCheckerOnce() {
        pinContent();
        h.activate();

        say(APPROVED + " 그리고 티라노도");

        assertThat(guard.count()).isEqualTo(1);
        assertThat(h.link.outputs()).isEmpty();
        guard.allow();
        h.drain();
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(records()).singleElement().satisfies(event -> {
            assertThat(event.sourceKind()).isEqualTo(GuardrailEventRecord.SourceKind.NEW_UTTERANCE);
            assertThat(event.decision()).isEqualTo(GuardrailEventRecord.Decision.ALLOW);
        });
        assertThat(h.metrics.count("mujoong.guardrail.calls", "mode", "real", "source", "new_utterance")).isEqualTo(1.0);
    }

    @Test
    void aSessionWithNoPinnedContentIsUnknownSoEvenTheSameSentenceIsChecked() {
        // 계획을 고정하지 않았다: matchApproved가 UNKNOWN이므로 생략하지 않는다(BR7.2, 안전한 쪽)
        h.guardrail = h.guardrail.withApprovedVerbatim(ApprovedVerbatimPort.of(registry));
        h.activate();

        say(APPROVED);

        assertThat(guard.count()).isEqualTo(1);
        assertThat(h.link.outputs()).isEmpty();
    }

    @Test
    void aNonAsciiSpaceBreaksTheVerbatimMatchAndTheSegmentIsChecked() {
        pinContent();
        h.activate();

        say(APPROVED.replace(" 해 ", " 해 "));

        assertThat(guard.count()).isEqualTo(1);
    }

    @Test
    void aVerbatimSegmentSealedWhileAnotherCheckIsPendingWaitsInOrderAndIsCheckedNotSkipped() {
        pinContent();
        h.activate();
        say("공룡을 좋아하는구나");
        FakeGuardrailClient.Call first = guard.last();

        say(APPROVED);
        assertThat(guard.count()).isEqualTo(1);

        guard.allow(first);
        h.drain();

        // 순서를 지키려고 대기 구간은 승인 원문이어도 새 발화로 검사한다(안전한 쪽, 호출 수 지표가 그 사실을 보여 준다)
        assertThat(guard.count()).isEqualTo(2);
        guard.allow();
        h.drain();
        assertThat(h.link.outputs()).hasSize(2);
        assertThat(h.metrics.count("mujoong.guardrail.calls", "mode", "real", "source", "approved_verbatim_skipped")).isZero();
    }

    @Test
    void aRetryAnswerThatIsAnApprovedVerbatimReleasesTheRetryStateAndIsSkipped() {
        pinContent();
        h.activate();
        h.childSays("child-1", "공룡이 좋아");
        h.settleSend();
        say("공룡을 좋아하는구나");
        guard.block();
        h.drain();
        h.settleSend();
        assertThat(h.executor.guardrail().state()).isEqualTo(GuardrailCoordinator.State.RETRYING);

        say(APPROVED);

        assertThat(guard.count()).isEqualTo(1);
        assertThat(h.executor.guardrail().state()).isEqualTo(GuardrailCoordinator.State.IDLE);
        assertThat(h.link.outputs()).hasSize(1);
    }

    /** 공급자가 말하고(전사 + 음성 두 조각) 조용 시간을 지나 구간이 봉인되게 한다. */
    private void say(String text) {
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta(text));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.drain();
        h.advance(Duration.ofMillis(801));
    }

    private List<GuardrailEventRecord> records() {
        h.guardrailRecorder.tick();
        return List.copyOf(h.store.guardrails);
    }
}
