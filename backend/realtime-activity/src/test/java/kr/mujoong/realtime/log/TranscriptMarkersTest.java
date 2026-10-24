package kr.mujoong.realtime.log;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** JIRA-262: 전사 비언어 표지 · 앞머리 잡음 정리. 정상 문장은 한 글자도 바뀌지 않는다. */
@Tag("realtime-live-fix")
class TranscriptMarkersTest {

    @Test
    void aiThinkingMarkersAreRemovedWhetherClosedOrNot() {
        assertThat(TranscriptMarkers.cleanAi("응, 그렇구나! [hum")).isEqualTo("응, 그렇구나!");
        assertThat(TranscriptMarkers.cleanAi("[hum] 좋아, 같이 해 보자.")).isEqualTo("좋아, 같이 해 보자.");
        assertThat(TranscriptMarkers.cleanAi("좋아 [hum] .")).isEqualTo("좋아.");
        assertThat(TranscriptMarkers.cleanAi("그렇구나 [laughs softly] 재밌다")).isEqualTo("그렇구나 재밌다");
        assertThat(TranscriptMarkers.cleanAi("그래 [")).isEqualTo("그래");
    }

    @Test
    void childMarkersStrayClosersAndLeadingPunctuationNoiseAreRemoved() {
        assertThat(TranscriptMarkers.cleanChild(".. 나 공룡 좋아")).isEqualTo("나 공룡 좋아");
        assertThat(TranscriptMarkers.cleanChild(". 응")).isEqualTo("응");
        assertThat(TranscriptMarkers.cleanChild("엄마가 ] 혼냈어")).isEqualTo("엄마가 혼냈어");
        assertThat(TranscriptMarkers.cleanChild("[cry")).isEmpty();
        assertThat(TranscriptMarkers.cleanChild("[cry] 싫어")).isEqualTo("싫어");
        assertThat(TranscriptMarkers.cleanChild("..")).isEmpty();
    }

    @Test
    void plainSentencesAndKoreanBracketsStayUnchanged() {
        for (String plain : new String[] {"응, 진짜 좋아!", "  공룡  두 마리  ", "음... 그래서 갔어", "왜?", "1. 사과"}) {
            assertThat(TranscriptMarkers.cleanAi(plain)).isSameAs(plain);
        }
        assertThat(TranscriptMarkers.cleanChild("응, 진짜 좋아!")).isEqualTo("응, 진짜 좋아!");
        assertThat(TranscriptMarkers.cleanChild("음... 그래서 갔어")).isEqualTo("음... 그래서 갔어");
        assertThat(TranscriptMarkers.cleanChild("[공룡] 책 읽었어")).isEqualTo("[공룡] 책 읽었어");
        assertThat(TranscriptMarkers.cleanAi("...그래서 그랬구나")).isEqualTo("...그래서 그랬구나");
        assertThat(TranscriptMarkers.cleanChild(null)).isNull();
        assertThat(TranscriptMarkers.cleanAi("")).isEmpty();
    }

    @Test
    void cleaningIsIdempotent() {
        for (String raw : new String[] {"응, 그렇구나! [hum", ".. 나 공룡 좋아 [cry", "엄마가 ] 혼냈어"}) {
            String once = TranscriptMarkers.cleanChild(raw);
            assertThat(TranscriptMarkers.cleanChild(once)).isEqualTo(once);
            String ai = TranscriptMarkers.cleanAi(raw);
            assertThat(TranscriptMarkers.cleanAi(ai)).isEqualTo(ai);
        }
    }
}
