package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 도입이 기회 문장을 담는가(정규화 비교). 설명을 자르지 않으므로 판정만 본다. 합성 문장만 쓴다. */
@Tag("practice-flow")
class SceneIntroTest {

    private static final String OPPORTUNITY = "이제 그만할래.";

    @Test
    void aDescriptionEndingWithTheOpportunitySentenceCarriesIt() {
        assertThat(SceneIntro.carriesOpportunity("블록 놀이 중이야. 친구가 멈추려 해. 이제 그만할래.", OPPORTUNITY)).isTrue();
    }

    @Test
    void anOpportunitySentenceInTheMiddleOrInsideQuotesIsCarried() {
        assertThat(SceneIntro.carriesOpportunity("친구가 '이제 그만할래.'라고 말하며 블록을 내려놓았어.", OPPORTUNITY)).isTrue();
        assertThat(SceneIntro.carriesOpportunity("친구가 말했어. “그거 줘.” 친구는 손을 내밀었어.", "그거 줘.")).isTrue();
    }

    @Test
    void spacingPunctuationCaseAndFullWidthFormsDoNotMatter() {
        assertThat(SceneIntro.carriesOpportunity("친구가 말했어 그거줘!", "친구가 말했어. 그거 줘.")).isTrue();
        assertThat(SceneIntro.carriesOpportunity("ＯＫ 하고 친구가 웃었어", "ok")).as("NFKC · 소문자").isTrue();
    }

    @Test
    void aDescriptionWithoutTheOpportunitySentenceDoesNotCarryIt() {
        assertThat(SceneIntro.carriesOpportunity("친구가 상자 두 개를 들고 있어.", "친구가 말했어. 그거 줘.")).isFalse();
        assertThat(SceneIntro.carriesOpportunity("이제 그만 놀래.", OPPORTUNITY)).as("글자가 다르면 아니다").isFalse();
    }

    @Test
    void aDescriptionThatIsOnlyTheOpportunitySentenceCarriesItSoItIsHeardOnce() {
        assertThat(SceneIntro.carriesOpportunity(OPPORTUNITY, OPPORTUNITY)).isTrue();
    }

    @Test
    void anEmptyOrPunctuationOnlyOpportunityOrMissingTextNeverCountsAsCarried() {
        assertThat(SceneIntro.carriesOpportunity("놀이 중이야.", "…!?")).isFalse();
        assertThat(SceneIntro.carriesOpportunity("놀이 중이야.", "")).isFalse();
        assertThat(SceneIntro.carriesOpportunity(null, OPPORTUNITY)).isFalse();
        assertThat(SceneIntro.carriesOpportunity("놀이 중이야.", null)).isFalse();
    }

    @Test
    void normalizationKeepsOnlyLettersAndDigitsInLowerCase() {
        assertThat(SceneIntro.normalize(" 이제, 그만-할래 2번! ABC ")).isEqualTo("이제그만할래2번abc");
    }
}
