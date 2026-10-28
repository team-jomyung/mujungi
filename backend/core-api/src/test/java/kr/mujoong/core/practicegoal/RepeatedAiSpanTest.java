package kr.mujoong.core.practicegoal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** FT-1027-i: AI가 같은 말을 되풀이한 구간은 목표 후보 입력에서 빠진다. */
@Tag("goal-result")
class RepeatedAiSpanTest {
    private static List<Map<String, Object>> rows(String... speakerAndText) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < speakerAndText.length; i += 2) {
            rows.add(Map.of("seq", i / 2 + 1, "speaker", speakerAndText[i], "text", speakerAndText[i + 1]));
        }
        return rows;
    }

    private static List<Object> seqs(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> row.get("seq")).toList();
    }

    @Test
    void dropsFromTheFirstOfTheRepeatedLineUntilTheNextDifferentAiLine() {
        var transcript = rows(
                "AI", "고양이는 어떤 점이 좋아?",
                "CHILD", "해리포터를 봤어",
                "AI", "그 장면이 어떻게 이어지는지 헷갈릴 수 있지.",
                "CHILD", "해리포터가 사후세계에 갔어",
                "AI", "음, 그 장면이 어떻게 이어지는지 헷갈릴 수 있지",
                "CHILD", "너 왜 그 말만 반복해",
                "AI", "걱정하는 마음이 있구나.",
                "CHILD", "응");

        assertThat(seqs(RepeatedAiSpan.without(transcript))).containsExactly(1, 2, 7, 8);
    }

    @Test
    void repetitionAtTheEndDropsEverythingAfterItsFirstLine() {
        var transcript = rows(
                "AI", "몇 개나 넘었어?",
                "CHILD", "쉰 개",
                "AI", "그 대회가 어떻게 이어졌는지 궁금하네.",
                "CHILD", "이등 했어",
                "AI", "그 대회가 어떻게 이어졌는지 궁금하네.",
                "CHILD", "너 이상해. 나 레고 만들었어");

        assertThat(seqs(RepeatedAiSpan.without(transcript))).containsExactly(1, 2);
    }

    @Test
    void conversationWithoutRepetitionIsUnchanged() {
        var transcript = rows("AI", "무슨 이야기 할래?", "CHILD", "공룡", "AI", "어떤 공룡이 좋아?", "CHILD", "스피노사우루스");

        assertThat(RepeatedAiSpan.without(transcript)).isSameAs(transcript);
    }

    @Test
    void sameLineSeparatedByADifferentAiLineIsNotARepetition() {
        var transcript = rows("AI", "좋아!", "CHILD", "그림 그렸어", "AI", "무슨 그림이야?", "CHILD", "수영장", "AI", "좋아!");

        assertThat(RepeatedAiSpan.without(transcript)).isSameAs(transcript);
    }

    @Test
    void childRepeatingThemselvesIsNotTouched() {
        var transcript = rows("AI", "누구랑 갔어?", "CHILD", "몰라", "AI", "괜찮아, 무엇을 했어?", "CHILD", "몰라");

        assertThat(RepeatedAiSpan.without(transcript)).isSameAs(transcript);
    }

    @Test
    void emptyAiTextNeverCountsAsARepetition() {
        var transcript = rows("AI", " ", "AI", "", "CHILD", "응");

        assertThat(RepeatedAiSpan.without(transcript)).isSameAs(transcript);
    }
}
