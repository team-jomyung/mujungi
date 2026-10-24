package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.practicelegacy.PracticePorts;
import kr.mujoong.realtime.testing.FakeGuardrailClient;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * FT-1023-e (2026-10-23 16:29 로컬 실키 연습 세션 사례 · 사람 결정 '재생'): 단위별 경로에서 GPT-Live 말끝의 구두점뿐인 단위("?")는 버리지 않고
 * 제브 없이 재생하고, 이미 재생이 시작된 생성형 연습 발화는 꼬리 단위 하나가 버려져도 같은 지시를 다시 요청하지 않고 나간 부분까지 재생됨으로 닫는다.
 * 제브 · 공급자 · 기기는 모두 시험이 손으로 푸는 대역이다(실제 호출 없음, R-F1). 이 시험의 성공은 테스트 대역 · 합성 대화의 성공이며 실제 공급자 ·
 * 실제 기기 성공이 아니다(R-M11).
 */
@Tag("practice-flow")
@Tag("service-guardrail")
class PunctuationOnlyUnitTest {

    private static final String INSTRUCTION = "친구로서 지난번 얘기를 떠올려 보자고 물어보세요";

    private final SessionHarness h = new SessionHarness();
    private final Recording extension = new Recording();
    private FakeGuardrailClient guard;
    private long margin;

    // ------------------------------------------------------------------ 연습(단위별 · 생성형)

    private UUID startGenerated() {
        guard = h.useGuardrail();
        h.activityType = ActivityType.PRACTICE;
        h.practice = ignored -> extension;
        h.activate();
        UUID slot = UUID.randomUUID();
        extension.out.speakGenerated(slot, "PEER", INSTRUCTION);
        h.settleSend();
        h.provider.last().newSpeechMargin(null);
        var request = h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class).getLast();
        h.provider.last().emit(new ProviderEvent.InstructionsAppended(request.eventId(), 0, 0));
        margin = h.settings.newSpeechMargin().toMillis();
        return slot;
    }

    @Test
    void theLiveCaseTailQuestionMarkPlaysWithoutJevAndTheSlotClosesPlayedWithoutARetry() {
        extension.recovery = true;
        UUID slot = startGenerated();
        // 실제 사례: 앞 단위가 제브 검사 중일 때 말끝 "?" 단위(0.2초)가 도착한다
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta(", 혹시 지난번 얘기부터 잠깐 떠올려볼까", 0, margin + 100));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[(int) margin * 48]));
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("?", margin + 100, margin + 300));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[14_400]));
        h.drain();
        assertThat(h.logs.events(EventCode.OUTPUT_DISCARDED)).as("구두점 단위는 버리지 않는다").isEmpty();
        assertThat(guard.count()).isEqualTo(1);

        guard.allow();
        h.drain();
        assertThat(h.link.outputs()).as("앞 단위 다음에 순서대로 '?' 음성이 나간다").hasSize(2);
        assertThat(h.link.outputs().getLast().totalMs()).isEqualTo(200L);
        assertThat(guard.count()).as("구두점 단위는 제브를 부르지 않는다").isEqualTo(1);
        assertThat(h.store.guardrails).anySatisfy(row -> assertThat(row.reasonCode())
                .isEqualTo(GuardrailCoordinator.SOURCE_PUNCTUATION_ONLY));
        assertThat(appErrors()).contains(GuardrailCoordinator.REASON_PUNCTUATION_ONLY_SKIPPED);

        mark(h.link.outputs().getFirst().playbackId(), PlaybackMark.Status.STARTED);
        assertThat(extension.startedFor(slot)).as("단위별 발화의 STARTED도 확장에 닿는다").isTrue();
        mark(h.link.outputs().getFirst().playbackId(), PlaybackMark.Status.COMPLETED);
        mark(h.link.outputs().getLast().playbackId(), PlaybackMark.Status.COMPLETED);
        h.advance(Duration.ofMillis(801));

        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.COMPLETED);
        assertThat(extension.incomplete).as("재요청 길로 가지 않는다").isEmpty();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void aTailQuestionMarkArrivingWhileJevIsIdleIsReleasedDirectly() {
        UUID slot = startGenerated();
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("지난번 얘기 떠올려볼까", 0, margin + 100));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[(int) margin * 48]));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[4_800]));
        h.drain();
        guard.allow();
        h.drain();
        assertThat(h.link.outputs()).hasSize(1);

        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("?", margin + 100, margin + 200));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[4_800]));
        h.drain();
        assertThat(h.link.outputs()).hasSize(2);
        assertThat(guard.count()).isEqualTo(1);

        h.link.outputs().forEach(output -> mark(output.playbackId(), PlaybackMark.Status.COMPLETED));
        h.advance(Duration.ofMillis(801));
        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.COMPLETED);
    }

    @Test
    void anEmptyTailUnitAfterPlaybackStartedIsDroppedAndTheSlotClosesPlayedWithoutARetry() {
        extension.recovery = true;
        UUID slot = startGenerated();
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("지난번 얘기 떠올려볼까", 0, margin + 100));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[(int) margin * 48]));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[4_800]));
        h.drain();
        guard.allow();
        h.drain();
        assertThat(h.link.outputs()).hasSize(1);

        // 원문이 빈 단위(제로폭만): 구두점이 아니라 지금처럼 EMPTY_TRANSCRIPT로 버린다
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("​", margin + 100, margin + 200));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[4_800]));
        h.drain();
        assertThat(LogCapture.attrs(h.logs.last(EventCode.OUTPUT_DISCARDED).orElseThrow()))
                .containsEntry("discardDetail", "EMPTY_TRANSCRIPT");
        assertThat(appErrors()).contains(SessionExecutor.REASON_TAIL_UNIT_DROPPED);
        assertThat(h.link.outputs()).as("버린 단위의 음성은 나가지 않는다").hasSize(1);
        assertThat(extension.endFacts(slot)).as("보낸 음성이 끝나기 전에는 닫지 않는다").isEmpty();

        mark(h.link.outputs().getFirst().playbackId(), PlaybackMark.Status.COMPLETED);
        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.COMPLETED);
        assertThat(extension.incomplete).isEmpty();
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void anEmptyUnitBeforeAnyPlaybackStillGoesToTheExistingRetryPath() {
        extension.recovery = true;
        UUID slot = startGenerated();
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("​", 0, margin + 100));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[(int) margin * 48]));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[4_800]));
        h.drain();

        assertThat(h.link.outputs()).isEmpty();
        assertThat(extension.incomplete).as("재생 전 실패는 지금처럼 확장이 재요청 1회 → 실패를 정한다").containsExactly(slot);
        assertThat(appErrors()).doesNotContain(SessionExecutor.REASON_TAIL_UNIT_DROPPED);
    }

    // ------------------------------------------------------------------ 자유대화(전사 먼저 방출)

    @Test
    void inFreeTalkATrailingQuestionMarkIsPlayedAtTheEndOfTheResponseWithoutCuttingIt() {
        var free = ServiceGuardrailFixtures.harness();
        free.useJudge();
        var freeGuard = free.useGuardrail();
        free.boot();
        free.ackAndReady();
        var play = free.link.sent(kr.mujoong.contract.ws.message.PlayApprovedContent.class).getLast();
        free.mailbox.putSafety(new SessionMailbox.PlaybackMarkReceived(free.link.connectionId(),
                new PlaybackMark(play.playbackId(), PlaybackMark.Status.COMPLETED, null, free.clock.now(), null, null),
                free.clock.now()));
        free.drain();
        free.childSays("fresh-accept", "안녕, 무중아");
        if (free.executor.status() == SessionStatus.WAITING_CHILD && free.judge != null && !free.judge.calls().isEmpty()) {
            free.judge.confirm("PARTICIPATE", 0.9, "UNCLEAR", 0.05);
            free.drain();
        }
        free.settleSend();
        long freeMargin = free.settings.newSpeechMargin().toMillis();
        free.provider.last().newSpeechMargin(null);
        String id = free.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class).getLast().eventId();
        free.provider.last().emit(new ProviderEvent.InstructionsAppended(id, 0, 0));
        free.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("그래, 무슨 이야기 할까", 0, 200 + freeMargin));
        free.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("?", 200 + freeMargin, 300 + freeMargin));
        free.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[(int) freeMargin * 48]));
        free.provider.last().emit(new ProviderEvent.OutputAudioDelta(RealtimeItEnvironment.audiblePcm(14_400)));
        free.drain();
        assertThat(freeGuard.count()).isEqualTo(1);
        freeGuard.allow();
        free.drain();
        assertThat(free.link.outputs().stream().mapToLong(output -> output.totalMs()).sum()).isEqualTo(200L);

        free.advanceAlive(Duration.ofSeconds(1));

        assertThat(free.link.outputs().stream().mapToLong(output -> output.totalMs()).sum())
                .as("말끝 '?' 음성까지 나간다(출력 세대를 올려 끊지 않는다)").isEqualTo(300L);
        assertThat(freeGuard.count()).as("구두점 단위는 제브를 부르지 않는다").isEqualTo(1);
        assertThat(free.logs.events(EventCode.OUTPUT_DISCARDED)).isEmpty();
        assertThat(free.store.guardrails).anySatisfy(row -> assertThat(row.reasonCode())
                .isEqualTo(GuardrailCoordinator.SOURCE_PUNCTUATION_ONLY));
    }

    // ------------------------------------------------------------------ 도움

    private List<Object> appErrors() {
        return h.logs.events(EventCode.APP_ERROR).stream().map(event -> LogCapture.attrs(event).get("errorKind")).toList();
    }

    private void mark(UUID id, PlaybackMark.Status status) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(id, status, null, h.clock.now(), null, null), h.clock.now()));
        h.drain();
    }

    /** 출력을 쥐고 받은 사건을 기록하는 최소 연습 확장점. */
    private static final class Recording implements PracticeSessionExtension {
        PracticeOutput out;
        boolean recovery;
        final List<PlaybackFact> facts = new ArrayList<>();
        final List<UUID> incomplete = new ArrayList<>();

        List<PracticePorts.Playback> endFacts(UUID correlationId) {
            return facts.stream().filter(fact -> !fact.started())
                    .filter(fact -> fact.speech().correlationId().equals(correlationId)).map(PlaybackFact::status).toList();
        }

        boolean startedFor(UUID correlationId) {
            return facts.stream().anyMatch(fact -> fact.started() && fact.speech().correlationId().equals(correlationId));
        }

        @Override public boolean controlsOutput() { return true; }
        @Override public boolean handlesAudioRecovery() { return recovery; }

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
            if ("practice-audio-incomplete".equals(event.name())) incomplete.add(event.correlationId());
            if (event.value() instanceof PlaybackFact fact) facts.add(fact);
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
