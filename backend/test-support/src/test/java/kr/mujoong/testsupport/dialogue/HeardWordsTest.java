package kr.mujoong.testsupport.dialogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 하니스 참고 점검 도우미 시험(합성 문장, 실측에서 찾은 모양 · docs/practice-dialogue-standard.md 12.2.1). */
class HeardWordsTest {

    @Test
    void keyWordsDropOneTrailingParticleShortWordsAndCommonWords() {
        assertThat(HeardWords.keyWords("아, 네가 말한 건 초록색인 줄 알았어. 이걸로 그리면 돼?")).contains("초록색", "말한", "그리면")
                .doesNotContain("네가", "이걸", "아", "건");
        assertThat(HeardWords.keyWords("자, 여기 초록색!")).containsExactly("초록색");
        assertThat(HeardWords.keyWords("친구야, 크레용이 필요해?")).containsExactly("크레용", "필요해");
        assertThat(HeardWords.stem("사인")).as("떼고 두 글자 미만이면 떼지 않는다").isEqualTo("사인");
        assertThat(HeardWords.keyWords(null)).isEmpty();
    }

    @Test
    void theDescriptionThatAlreadyNamesTheCueWordIsFound() {
        // 실측 s6: 설명이 «…무중이가 초록색 크레파스를 건넸어»로 기회 문장의 단서를 미리 말했다
        String description = "무중이랑 그림을 그린다고 생각해 보자. 너는 파란색을 달라고 했는데 무중이가 초록색 크레파스를 건넸어.";
        assertThat(HeardWords.mentioned(HeardWords.keyWords("아, 네가 말한 건 초록색인 줄 알았어."), description)).containsExactly("초록색");
        assertThat(HeardWords.mentioned(HeardWords.keyWords("자, 여기 초록색!"), "너는 친구한테 파란색 크레용을 달라고 했어.")).isEmpty();
    }

    @Test
    void sameAndSharedKeyWordsCompareTheBaseAndVariantOpportunityLines() {
        assertThat(HeardWords.same("자, 여기 초록색!", "자 여기 초록색")).isTrue();
        assertThat(HeardWords.same("자, 여기 초록색!", "자, 초코 우유 여기!")).isFalse();
        assertThat(HeardWords.sharedKeyWords("자, 여기 초록색!", "자, 초코 우유 여기!")).as("흔한 말(자 · 여기)은 세지 않는다").isEmpty();
        assertThat(HeardWords.sharedKeyWords("자, 크레파스 여기 있어.", "다 쓰면 크레파스 돌려줘.")).as("실측 s10 · x4: 소재가 그대로인 변형")
                .containsExactly("크레파스");
    }

    @Test
    void blankedWordsAreWhatTheFrameLeftOutOfTheExample() {
        assertThat(HeardWords.blankedWords("아니, 나 파란색 달라고 했어.", "아니, 나 뭐뭐 달라고 했어.")).contains(List.of("파란색"));
        assertThat(HeardWords.blankedWords("형, 다 쓰고 나서 잠깐 빌려줄 수 있어?", "형, 다 쓰고 나서 뭐뭐 빌려줄 수 있어?")).contains(List.of("잠깐"));
        assertThat(HeardWords.blankedWords("나도 공을 써도 돼?", "나도 뭐뭐를 써도 돼?")).as("빈칸 뒤 조사는 받침 뒤 모양도 받는다")
                .contains(List.of("공"));
        assertThat(HeardWords.blankedWords("아니, 나 파란색 달라고 했어.", "그냥 다르게 말해 봐 뭐뭐")).isEqualTo(Optional.empty());
        assertThat(HeardWords.blankedWords("아니, 나 파란색 달라고 했어.", "빈칸 없음")).isEmpty();
    }

    @Test
    void symbolsAreEmojisBracketsAndSignsButNotQuotesOrSentencePunctuation() {
        assertThat(HeardWords.symbols("응, 여기 딸기 우유! 🥛")).containsExactly("🥛");
        assertThat(HeardWords.symbols("(웃으며) 좋아~ ★")).containsExactly("(", ")", "~", "★");
        assertThat(HeardWords.symbols("\"아니, 나 뭐뭐 달라고 했어\"처럼 말할 수 있어. 네 말로 해 볼래?")).isEmpty();
        assertThat(HeardWords.symbols("다르면 «아니, 나 ○○ 달라고 했어»… 어때?")).containsExactly("○");
        assertThat(HeardWords.symbols(null)).isEmpty();
    }

    @Test
    void instructionWordsAreFoundEvenWithSpacing() {
        assertThat(HeardWords.instructionWords("말틀은 \"응, 뭐뭐. 다 쓰면 돌려줘.\"야")).containsExactly("말틀");
        assertThat(HeardWords.instructionWords("이 빈칸 예문처럼 말해 봐")).containsExactly("빈칸예문");
        assertThat(HeardWords.instructionWords("아니, 나 뭐뭐 달라고 했어처럼 말할 수 있어.")).isEmpty();
    }
}
