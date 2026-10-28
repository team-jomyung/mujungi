package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 연습 확장점이 읽는 '아이가 말하는 중' 질의 — 입력 전사 묶음이 열린 동안만 참이다. 무응답 시계가 아이가 말하는 도중 재촉 발화를 내던 문제
 * (2026-10-27 실키)를 확장점이 피하려고 쓴다. 증거 종류: 테스트 대역.
 */
@Tag("practice-flow")
class PracticeChildSpeakingTest {

    @Test
    void childSpeakingIsTrueOnlyWhileAnInputTranscriptGroupIsOpen() {
        JudgmentExecutorSupport t = new JudgmentExecutorSupport();
        t.practice();
        assertThat(t.extension.output.childSpeaking()).isFalse();

        t.h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("in-1", "그 이야기가"));
        t.h.drain();
        assertThat(t.extension.output.childSpeaking()).as("말이 들어오는 중").isTrue();

        t.h.childSays("in-1", "그 이야기가 뭔데");
        assertThat(t.extension.output.childSpeaking()).as("확정되면 닫힌다").isFalse();
    }
}
