package kr.mujoong.realtime.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("skeleton")
class OutputDiscardDiagnosticsTest {
    private final OutputGate gate = new OutputGate(SeedDictionary.loadDefault());

    private SegmentBuffer.Sealed segment(String text, int bytes, boolean complete) {
        return new SegmentBuffer.Sealed(7, text, List.of(new byte[bytes]), bytes, 9,
                Instant.EPOCH, 1, Instant.EPOCH.plusSeconds(1), 2, false, complete);
    }

    @Test void emptyFailuresIdentifyTheExactPredicateAndKeepTheExistingSafetyDecision() {
        check(null, DiscardDetail.MISSING_SEGMENT);
        check(segment("synthetic text", 4, false), DiscardDetail.INCOMPLETE_SEGMENT);
        check(segment(" ", 4, true), DiscardDetail.EMPTY_TRANSCRIPT);
        check(segment("synthetic text", 0, true), DiscardDetail.MISSING_AUDIO);
        check(segment("synthetic text", 3, true), DiscardDetail.INVALID_PCM_LENGTH);
        // Preserve predicate precedence when multiple conditions fail.
        check(segment("", 0, false), DiscardDetail.INCOMPLETE_SEGMENT);
    }

    private void check(SegmentBuffer.Sealed segment, DiscardDetail detail) {
        assertThat(gate.inspectDetailed(segment).detail()).isEqualTo(detail);
        assertThat(gate.inspect(segment)).isEqualTo(GateDecision.discard(GateDecision.DiscardReason.GATE_EMPTY));
    }

    /**
     * FT-1023-e(2026-10-23 16:29 로컬 실키 연습 사례 · 사람 결정 '재생'): 단위별 경로의 구두점뿐인 단위(말끝 "?")는 버리지 않고 통과하며 '구두점뿐'으로
     * 표시된다. 원문이 빈 단위(공백 · 제로폭)와 음성 · PCM 오류는 여전히 버리고, 전체 구간 경로는 구두점뿐이어도 지금처럼 EMPTY_TRANSCRIPT다.
     */
    @Test void aPunctuationOnlyAlignedUnitPassesButEmptyUnitsAndWholeSegmentsStayFailClosed() {
        for (String punctuation : List.of("?", " ?", "…", "?!", "，", "\u200B?")) {
            OutputGate.Inspection unit = gate.inspectDetailed(segment(punctuation, 9600, true), true);
            assertThat(unit.decision().passed()).as(punctuation).isTrue();
            assertThat(unit.punctuationOnly()).isTrue();
            OutputGate.Inspection text = gate.inspectTextDetailed(segment(punctuation, 0, true));
            assertThat(text.decision().passed()).isTrue();
            assertThat(text.punctuationOnly()).isTrue();
            // 전체 구간(무음으로 봉인한 구간)은 지금 동작 그대로
            assertThat(gate.inspectDetailed(segment(punctuation, 9600, true)).detail()).isEqualTo(DiscardDetail.EMPTY_TRANSCRIPT);
        }
        for (String empty : java.util.Arrays.asList(null, "", " ", "\u200B", "\u3000 \uFEFF")) {
            assertThat(gate.inspectDetailed(segment(empty, 9600, true), true).detail()).isEqualTo(DiscardDetail.EMPTY_TRANSCRIPT);
            assertThat(gate.inspectTextDetailed(segment(empty, 0, true)).detail()).isEqualTo(DiscardDetail.EMPTY_TRANSCRIPT);
        }
        assertThat(gate.inspectDetailed(segment("?", 0, true), true).detail()).isEqualTo(DiscardDetail.MISSING_AUDIO);
        assertThat(gate.inspectDetailed(segment("?", 3, true), true).detail()).isEqualTo(DiscardDetail.INVALID_PCM_LENGTH);
        assertThat(gate.inspectDetailed(segment("?", 4, false), true).detail()).isEqualTo(DiscardDetail.INCOMPLETE_SEGMENT);
        // 글이 있는 단위는 구두점뿐이 아니고 사전 검사를 그대로 받는다
        assertThat(gate.inspectDetailed(segment("같이 놀자?", 4, true), true).punctuationOnly()).isFalse();
        assertThat(gate.inspectDetailed(segment("검사해 보니 비정상이에요?", 4, true), true).detail())
                .isEqualTo(DiscardDetail.FORBIDDEN_PATTERN);
    }

    @Test void safeAndForbiddenContentKeepTheirExistingOutcomes() {
        assertThat(gate.inspectDetailed(segment("같이 놀자", 4, true)).decision().passed()).isTrue();
        assertThat(gate.inspectDetailed(segment("검사해 보니 비정상이에요", 4, true)).detail())
                .isEqualTo(DiscardDetail.FORBIDDEN_PATTERN);
    }

    @Test void childOverlapIsTraceableToTheDiscardedTranscriptWithoutLoggingItsContent() {
        SessionHarness h = new SessionHarness(); h.activate(); h.logs.clear();
        h.childSays("input", "응");
        h.speak("합성 비공개 발화", 4800);
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("next", "근데")); h.drain();
        Map<String,Object> event = h.logs.last(EventCode.OUTPUT_DISCARDED).orElseThrow();
        Map<String,Object> attrs = LogCapture.attrs(event);
        assertThat(attrs).containsEntry("discardReason", "GATE_EMPTY")
                .containsEntry("discardDetail", "INCOMPLETE_SEGMENT").containsEntry("segmentComplete", false)
                .containsEntry("audioBytes", 4800);
        assertThat(((Number) attrs.get("seq")).intValue()).isPositive();
        assertThat(event.get("ownerHash")).isNotNull();
        assertThat(event.get("traceId")).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(h.logs.lines()).noneMatch(line -> line.contains("합성 비공개 발화") || line.contains("근데"));
        assertThat(h.link.outputs()).isEmpty();
    }

    @Test void missingAudioIsNotReportedAsAnIncompleteSegment() {
        SessionHarness h = new SessionHarness(); h.activate(); h.logs.clear();
        h.childSays("input", "응");
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("합성 비공개 발화")); h.drain();
        h.advance(Duration.ofMillis(801));
        assertThat(LogCapture.attrs(h.logs.last(EventCode.OUTPUT_DISCARDED).orElseThrow()))
                .containsEntry("discardDetail", "MISSING_AUDIO").containsEntry("segmentComplete", true)
                .containsEntry("audioBytes", 0);
    }

    @Test void repeatedDiscardsAreNotMergedAndKeepDifferentTranscriptSequences() {
        SessionHarness h = new SessionHarness(); h.activate(); h.logs.clear();
        for (int i=0;i<2;i++) {
            h.childSays("input-"+i,"응"); h.settleSend();
            h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[4800])); h.drain();
            h.advance(Duration.ofMillis(801)); h.settleSend();
        }
        var events = h.logs.events(EventCode.OUTPUT_DISCARDED);
        assertThat(events).hasSize(2);
        assertThat(events.stream().map(e -> LogCapture.attrs(e).get("seq")).distinct()).hasSize(2);
        assertThat(events).allSatisfy(e -> assertThat(LogCapture.attrs(e))
                .containsEntry("discardDetail", "EMPTY_TRANSCRIPT"));
    }
    @Test void aFullAudioQueueHasItsOwnReasonAndStillSendsNoAudio() {
        SessionHarness h = new SessionHarness(); h.activate(); h.logs.clear();
        h.childSays("input", "응"); h.link.setAudioBudget(0);
        h.speak("합성 비공개 발화", 4800); h.advance(Duration.ofMillis(801));
        assertThat(LogCapture.attrs(h.logs.last(EventCode.OUTPUT_DISCARDED).orElseThrow()))
                .containsEntry("discardReason", "OUTBOUND_FULL").containsEntry("discardDetail", "AUDIO_QUEUE_FULL");
        assertThat(h.link.outputs()).isEmpty();
    }

}
