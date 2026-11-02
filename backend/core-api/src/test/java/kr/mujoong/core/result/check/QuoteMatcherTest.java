package kr.mujoong.core.result.check;
import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.*;
@Tag("result-report")
class QuoteMatcherTest {
    @Test void trimsOnlyEnds(){assertThat(QuoteMatcher.matches("  함께 가자  ","함께 가자")).isTrue();}
    @Test void whitespaceInsideMustMatch(){assertThat(QuoteMatcher.matches("함께  가자","함께 가자")).isFalse();}
    @Test void punctuationMustMatch(){assertThat(QuoteMatcher.matches("함께 가자!","함께 가자")).isFalse();}
    @Test void nullDoesNotMatch(){assertThat(QuoteMatcher.matches(null,null)).isFalse();}
    @Test void allQuotedTextMustBeVerified(){assertThat(QuoteMatcher.allVerified("“함께 가자”라고 말했어요",List.of("함께 가자"))).isTrue();assertThat(QuoteMatcher.allVerified("\"정말 좋아\"라고 말했어요",List.of("함께 가자"))).isFalse();}
    @Test void wordBoundedPartOfUtteranceIsVerified(){
        // FT-1102-d: 발화 안에서 낱말 경계로 끊긴 연속 일부 인용은 통과한다
        assertThat(QuoteMatcher.allVerified("\"우리 팀 이겨라\"라고 응원했다고 말했어요",List.of("우리 팀 이겨라 라고 응원했어"))).isTrue();
        assertThat(QuoteMatcher.allVerified("\"피자 먹었어\"라고 했어요",List.of("우리가 1등 해서 다 같이 피자 먹었어"))).isTrue();
    }
    @Test void partCutInsideAWordOrAlteredIsNotVerified(){
        assertThat(QuoteMatcher.allVerified("\"싫어\"라고 했어요",List.of("싫어하는 건 아니야"))).isFalse();
        assertThat(QuoteMatcher.allVerified("\"팀 이겨\"라고 했어요",List.of("우리 팀 이겨라"))).isFalse();
        assertThat(QuoteMatcher.allVerified("\"우리팀 이겨라\"라고 했어요",List.of("우리 팀 이겨라"))).isFalse();
    }
}
