package kr.mujoong.contract.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 공통 욕설 사전 시험(JIRA-266 · FT-1023-b): 대표 어휘 · 변형 차단, 일반 낱말 오탐 없음, 자원 형식. */
class ProfanityLexiconTest {

    private static final ProfanityLexicon LEXICON = ProfanityLexicon.shared();

    @Test
    void theBundledResourceLoadsWithAVersionAndEntries() {
        assertThat(LEXICON.version()).isEqualTo("profanity-seed-1");
        assertThat(LEXICON.blockedCount()).isGreaterThan(30);
    }

    @ParameterizedTest
    @ValueSource(strings = {"씨발", "시발", "씨팔", "ㅅㅂ", "ㅆㅂ", "병신", "븅신", "ㅂㅅ", "개새끼", "개색기", "좆", "존나",
        "지랄", "미친놈", "또라이", "tlqkf", "FUCK", "씨.발", "씨1발", "씨~발", "씨 발", "개 새끼", "병​신", "ｓｈｉｔ"})
    void representativeWordsAndCommonVariantsAreBlockedAsNames(String name) {
        assertThat(LEXICON.containsInName(name)).as(name).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {".씨발아, 불렀어", "야 씨발 진짜", "이 병신아", "개새끼야", "존나 좋아", "씨 발 아 불렀어", "ㅅ ㅂ"})
    void sentencesWithProfanityAreBlocked(String text) {
        assertThat(LEXICON.containsInText(text)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"시발점에서 출발했어", "수박씨 발라 먹었어", "수박씨발라 먹자", "아저씨 발이 커요", "다시 팔을 들어 봐",
        "다시발표하자", "날씨 발표를 봤어", "불이 꺼져", "엄마를 졸라서 샀어", "등신대 사진", "미친 듯이 재밌어", "이 씨 발라 먹어",
        "꼭꼭 씹어 먹자", "새끼 고양이", "병 따개", "하늘아, 불렀어"})
    void ordinarySentencesAreNotBlocked(String text) {
        assertThat(LEXICON.containsInText(text)).as(text).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"하늘", "시우", "지호", "병준", "신병", "아가씨", "솜씨", "John", "김 하늘", "시발점"})
    void ordinaryNamesAreNotBlocked(String name) {
        assertThat(LEXICON.containsInName(name)).as(name).isFalse();
    }

    @Test
    void nullAndEmptyAreNotProfanity() {
        assertThat(LEXICON.containsInName(null)).isFalse();
        assertThat(LEXICON.containsInName("")).isFalse();
        assertThat(LEXICON.containsInText(null)).isFalse();
        assertThat(LEXICON.containsInText("   ")).isFalse();
    }

    @Test
    void normalizeKeepsLettersAndSingleSpacesOnly() {
        assertThat(ProfanityLexicon.normalize("  A.b-1 가 나!  ")).isEqualTo("ab 가 나");
    }

    @Test
    void parseRejectsMissingVersionEmptyBlockAndWordsOutsideASectionWithoutEchoingThem() {
        assertThatThrownBy(() -> ProfanityLexicon.parse("[block]\n나쁜말\n"))
                .isInstanceOf(ProfanityLexicon.LoadException.class);
        assertThatThrownBy(() -> ProfanityLexicon.parse("@version t\n[allow]\n좋은말\n"))
                .isInstanceOf(ProfanityLexicon.LoadException.class);
        assertThatThrownBy(() -> ProfanityLexicon.parse("@version t\n비밀낱말\n[block]\n나쁜말\n"))
                .isInstanceOf(ProfanityLexicon.LoadException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("비밀낱말"));
        assertThatThrownBy(() -> ProfanityLexicon.parse("@version t\n[block]\n...\n"))
                .isInstanceOf(ProfanityLexicon.LoadException.class);
    }

    @Test
    void allowWordsMaskOnlyThemselves() {
        ProfanityLexicon lexicon = ProfanityLexicon.parse("@version t\n[block]\n나쁜\n[allow]\n안나쁜\n");

        assertThat(lexicon.containsInText("안나쁜 말")).isFalse();
        assertThat(lexicon.containsInText("안나쁜 나쁜 말")).isTrue();
        assertThat(lexicon.containsInName("나쁜")).isTrue();
    }
}
