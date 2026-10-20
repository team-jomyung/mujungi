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
}
