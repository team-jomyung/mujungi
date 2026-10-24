package kr.mujoong.realtime.dialog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** JIRA-270: 응답 끝의 미루는 말 사전 규칙. 2026-10-23 12:50 세션 관측 문장 모양을 합성해 본다(실제 전사 원문 아님). */
@Tag("realtime-live-fix")
class DeferralPhrasesTest {

    @Test
    void observedDeferralEndingsAreDetectedIgnoringSpacesPunctuationAndMarkers() {
        assertThat(DeferralPhrases.endsWithDeferral("잠깐 생각해볼게")).isTrue();
        assertThat(DeferralPhrases.endsWithDeferral("음, 좋은 질문이야. 정리해서 말해볼게.")).isTrue();
        assertThat(DeferralPhrases.endsWithDeferral("지금까지 생각한 걸로 먼저 얘기해볼게 [hum")).isTrue();
        assertThat(DeferralPhrases.endsWithDeferral("잠깐 생각해 볼게...")).isTrue();
        assertThat(DeferralPhrases.endsWithDeferral("음 잠깐만")).isTrue();
    }

    @Test
    void ordinaryAnswersAndMidSentenceMentionsAreNotDeferrals() {
        assertThat(DeferralPhrases.endsWithDeferral("아주 큰 돌이 떨어져서 날씨가 바뀌었대.")).isFalse();
        assertThat(DeferralPhrases.endsWithDeferral("생각해볼게라고 했는데, 답은 티라노야.")).isFalse();
        assertThat(DeferralPhrases.endsWithDeferral("응, 좋아!")).isFalse();
        assertThat(DeferralPhrases.endsWithDeferral("")).isFalse();
        assertThat(DeferralPhrases.endsWithDeferral(null)).isFalse();
    }
}
