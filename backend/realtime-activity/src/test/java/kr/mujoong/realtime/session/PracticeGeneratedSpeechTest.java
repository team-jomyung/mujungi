package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.guardrail.GuardrailMode;
import kr.mujoong.realtime.guardrail.GuardrailRequest;
import kr.mujoong.realtime.guardrail.GuardrailSupport;
import kr.mujoong.realtime.practicelegacy.PracticePorts;
import kr.mujoong.realtime.testing.FakeGuardrailClient;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.PracticeSpeech;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 생성형 역할 발화 슬롯의 실행부 연결 (U12 기능 설계 3.5 · BR5.4 ~ BR5.7): {@code speakGenerated}로 연 슬롯의 새 발화는 제브(연습 모드, REAL ·
 * STUB)가 ALLOW한 것만 재생되고, 막힘은 0바이트이며, 슬롯은 어떤 원인으로 닫혀도 종료 사실이 한 번만 확장에 닿는다. 제브 단계를 건너뛰는 OFF에서는
 * 기본 게이트를 지난 발화가 그대로 재생된다(실측 F8 · 편차 U13-d). 제브 · 공급자는 모두
 * 시험이 손으로 푸는 대역이다(실제 호출 없음, R-F1). 이 시험의 성공은 테스트 대역 · 합성 대화의 성공이며 실제 공급자 · 실제 기기 성공이 아니다
 * (R-M11).
 */
@Tag("practice-flow")
class PracticeGeneratedSpeechTest {

    private static final String INSTRUCTION = "친구로서 방금 말을 한 번 더 물어보세요";
    private static final String SPOKEN = "그거 줘 하면 어느 색을 말하는 건지 잘 모르겠어";

    private final SessionHarness h = new SessionHarness();
    private final Recording extension = new Recording();
    private FakeGuardrailClient guard;

    private void start(boolean withGuardrail) {
        if (withGuardrail) {
            guard = h.useGuardrail();
        }
        h.activityType = ActivityType.PRACTICE;
        h.practice = ignored -> extension;
        h.activate();
    }

    @Test
    void anAllowedGeneratedUtterancePlaysAndTheEndFactArrivesExactlyOnce() {
        start(true);
        UUID slot = UUID.randomUUID();

        assertThat(extension.out.speakGenerated(slot, "PEER", INSTRUCTION)).isTrue();
        h.settleSend();
        say(SPOKEN);

        assertThat(guard.count()).isEqualTo(1);
        assertThat(guard.last().request().mode()).isEqualTo(GuardrailRequest.Mode.PRACTICE_PEER);
        assertThat(h.link.outputs()).isEmpty();
        guard.allow();
        h.drain();
        assertThat(h.link.outputs()).hasSize(1);

        UUID playback = h.link.outputs().getFirst().playbackId();
        mark(playback, PlaybackMark.Status.STARTED);
        mark(playback, PlaybackMark.Status.COMPLETED);
        mark(playback, PlaybackMark.Status.COMPLETED);

        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.COMPLETED);
        assertThat(extension.blocked).isEmpty();
    }

    @Test
    void aCoachSlotIsCheckedInTheCoachPracticeMode() {
        start(true);

        assertThat(extension.out.speakGenerated(UUID.randomUUID(), "COACH", INSTRUCTION)).isTrue();
        h.settleSend();
        say(SPOKEN);

        assertThat(guard.last().request().mode()).isEqualTo(GuardrailRequest.Mode.PRACTICE_COACH);
    }

    @Test
    void aBlockedUtteranceIsRetriedWithTheSameRoleInstructionAndASecondBlockSendsZeroBytesAndOneFailureFact() {
        start(true);
        UUID slot = UUID.randomUUID();
        extension.out.speakGenerated(slot, "PEER", INSTRUCTION);
        h.settleSend();
        say(SPOKEN);

        guard.block();
        h.drain();
        h.settleSend();

        List<String> contents = appended();
        assertThat(contents).hasSizeGreaterThanOrEqualTo(2);
        assertThat(contents.getLast()).isEqualTo(contents.get(contents.size() - 2)).contains(INSTRUCTION);
        assertThat(extension.endFacts(slot)).isEmpty();

        say("다시 한 말");
        assertThat(guard.count()).isEqualTo(2);
        guard.block();
        h.drain();

        assertThat(h.link.outputs()).isEmpty();
        assertThat(extension.blocked).singleElement().satisfies(event -> {
            assertThat(event.correlationId()).isEqualTo(slot);
            assertThat(event.mode()).isEqualTo(GuardrailRequest.Mode.PRACTICE_PEER);
        });
        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.FAILED);
    }

    /**
     * 제브 단계를 건너뛰는 모드(OFF, dev · test 전용 — 공개 프로필은 OFF로 기동하지 않는다)에서는 기본 게이트를 지난 생성형 발화가 검사 없이 재생되고
     * 슬롯은 재생 완료로 닫힌다(팀장 결정 2026-10-22, 실측 F8 · 편차 U13-d). 이전에는 폐기해 되묻기 · 친구 반응 · 코치 도움이 모두 들리지 않았다.
     */
    @Test
    void withTheGuardrailOffAGeneratedUtterancePassesTheBasicGateAndPlaysWithoutACheck() {
        start(false);
        UUID slot = UUID.randomUUID();

        assertThat(extension.out.speakGenerated(slot, "PEER", INSTRUCTION)).isTrue();
        h.settleSend();
        say(SPOKEN);

        assertThat(h.link.outputs()).hasSize(1);
        UUID playback = h.link.outputs().getFirst().playbackId();
        mark(playback, PlaybackMark.Status.STARTED);
        mark(playback, PlaybackMark.Status.COMPLETED);
        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.COMPLETED);
        assertThat(h.store.guardrails).as("OFF는 검사 기록을 남기지 않는다").isEmpty();
        assertThat(extension.blocked).isEmpty();
    }

    @Test
    void withTheGuardrailOffAGeneratedUtteranceWithAForbiddenPatternIsStillDiscardedByTheBasicGate() {
        start(false);
        UUID slot = UUID.randomUUID();

        extension.out.speakGenerated(slot, "COACH", INSTRUCTION);
        h.settleSend();
        say("친구가 그렇게 웃는 건 비정상이야");

        assertThat(h.link.outputs()).as("기본 게이트(금지 패턴)는 OFF에서도 돈다").isEmpty();
        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.FAILED);
    }

    @Test
    void inTheStubModeAGeneratedUtteranceStillWaitsForTheStubCheckAsBefore() {
        guard = new FakeGuardrailClient(GuardrailMode.STUB);
        h.guardrail = new GuardrailSupport(GuardrailMode.STUB, guard, h.guardrailRecorder, Duration.ofMillis(800),
                FakeGuardrailClient.POLICY);
        start(false);
        UUID slot = UUID.randomUUID();

        extension.out.speakGenerated(slot, "PEER", INSTRUCTION);
        h.settleSend();
        say(SPOKEN);

        assertThat(guard.count()).isEqualTo(1);
        assertThat(guard.last().request().mode()).isEqualTo(GuardrailRequest.Mode.PRACTICE_PEER);
        assertThat(h.link.outputs()).as("STUB은 지금처럼 검사 결과를 기다린다").isEmpty();
        guard.allow();
        h.drain();
        assertThat(h.link.outputs()).hasSize(1);
    }

    @Test
    void aChildWhoSpeaksDuringTheCheckClosesTheSlotAsInterruptedOnceAndALateAllowPlaysNothing() {
        start(true);
        UUID slot = UUID.randomUUID();
        extension.out.speakGenerated(slot, "PEER", INSTRUCTION);
        h.settleSend();
        say(SPOKEN);

        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("item-9", "근데"));
        h.drain();
        guard.allow();
        h.drain();

        assertThat(h.link.outputs()).isEmpty();
        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.INTERRUPTED);
    }

    @Test
    void aRetryWhoseNewSpeechNeverStartsClosesTheSlotAsFailedOnce() {
        start(true);
        UUID slot = UUID.randomUUID();
        extension.out.speakGenerated(slot, "PEER", INSTRUCTION);
        h.settleSend();
        say(SPOKEN);
        guard.block();
        h.drain();

        h.advanceAlive(Duration.ofSeconds(11));
        h.advanceAlive(Duration.ofSeconds(11));

        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.FAILED);
        assertThat(h.link.outputs()).isEmpty();
    }

    @Test
    void anUtteranceWithoutAnOpenSlotIsStillDiscardedAndNeverReachesTheChecker() {
        start(true);

        say(SPOKEN);

        assertThat(guard.count()).isZero();
        assertThat(h.link.outputs()).isEmpty();
    }

    @Test
    void aRoleSwitchNoticeUsesTheApprovedSentenceThroughTheOldPathWithoutCallingTheChecker() {
        h.approvedSpeech.put("COACH_SWITCH", "이제 내가 도와줄게.");
        start(true);

        assertThat(extension.out.announceRoleSwitch("COACH")).isTrue();
        h.settleSend();
        say("이제 내가 도와줄게.");

        assertThat(guard.count()).isZero();
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(extension.out.announceRoleSwitch("PEER")).isFalse();
    }

    @Test
    void invalidRolesAndLongInstructionsAreRefusedWithoutOpeningASlot() {
        start(true);

        assertThat(extension.out.speakGenerated(UUID.randomUUID(), "TEACHER", INSTRUCTION)).isFalse();
        assertThat(extension.out.speakGenerated(UUID.randomUUID(), "PEER", "가".repeat(501))).isFalse();
        say(SPOKEN);

        assertThat(guard.count()).isZero();
        assertThat(h.link.outputs()).isEmpty();
    }

    @Test
    void aGeneratedSlotNeverMatchesTheOriginalTextSoTheCheckerCannotBeSkipped() {
        PracticeSpeech slot = PracticeSpeech.generatedSlot(UUID.randomUUID(), UUID.randomUUID(), 1, "PEER", INSTRUCTION);

        assertThat(slot.generated()).isTrue();
        assertThat(slot.matches(INSTRUCTION)).isFalse();
        assertThatThrownBy(() -> PracticeSpeech.generatedSlot(UUID.randomUUID(), UUID.randomUUID(), 1, "ANY", INSTRUCTION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(slot.toString()).doesNotContain("친구로서");
    }

    private void say(String text) {
        h.speak(text, 4800);
        h.advance(Duration.ofMillis(801));
    }

    private void mark(UUID id, PlaybackMark.Status status) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(id, status, null, h.clock.now(), null, null), h.clock.now()));
        h.drain();
    }

    private List<String> appended() {
        return h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class).stream()
                .map(ProviderRequestFactory.InstructionsAppend::content).toList();
    }

    /** 출력을 쥐고 받은 사건을 기록하는 최소 연습 확장점. */
    private static final class Recording implements PracticeSessionExtension {
        PracticeOutput out;
        final List<PlaybackFact> facts = new ArrayList<>();
        final List<GuardrailBlockedEvent> blocked = new ArrayList<>();

        List<PracticePorts.Playback> endFacts(UUID correlationId) {
            return facts.stream().filter(fact -> !fact.started())
                    .filter(fact -> fact.speech().correlationId().equals(correlationId)).map(PlaybackFact::status).toList();
        }

        @Override
        public boolean controlsOutput() {
            return true;
        }

        @Override
        public Signal onActive(PracticeContext context, PracticeOutput output) {
            out = output;
            return Signal.NONE;
        }

        @Override
        public Signal onChildUtterance(Utterance utterance, PracticeOutput output) {
            return Signal.NONE;
        }

        @Override
        public Signal onExtensionEvent(ExtensionEvent event, PracticeOutput output) {
            if (event.value() instanceof PlaybackFact fact) {
                facts.add(fact);
            }
            if (event.value() instanceof GuardrailBlockedEvent block) {
                blocked.add(block);
            }
            return Signal.NONE;
        }

        @Override
        public void onSettle(EndContext end) {
            // 정리할 것이 없다
        }

        @Override
        public Object practiceRecord() {
            return null;
        }
    }
}
