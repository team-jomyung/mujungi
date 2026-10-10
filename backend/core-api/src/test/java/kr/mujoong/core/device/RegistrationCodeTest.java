package kr.mujoong.core.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.mujoong.core.device.RegistrationCode.Problem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 등록 코드 정규화 · 알파벳 · 표시 (BR1.1 · 편차 (d) · BR2.7 · 3.3 로그 위생). */
@Tag("u1")
class RegistrationCodeTest {

    @Test
    void alphabetIsThirtyOneCharactersWithoutLookAlikes() {
        assertThat(RegistrationCode.ALPHABET).hasSize(31);
        assertThat(RegistrationCode.ALPHABET).doesNotContain("0", "1", "I", "L", "O");
        assertThat(RegistrationCode.ALPHABET.chars().distinct().count()).isEqualTo(31);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" → {1}")
    @CsvSource(delimiter = '|', value = {
        "ABCDEFGH|ABCDEFGH",
        "abcd efgh|ABCDEFGH",
        "ABCD-EFGH|ABCDEFGH",
        "ABCD · EFGH|ABCDEFGH",
        "  a b c d‐e f g h  |ABCDEFGH",
        "abcd　efgh|ABCDEFGH",
        "AB・CD‧EF•GH|ABCDEFGH",
        "7f3k–92qd|7F3K92QD"
    })
    void normalizesWhitespaceDashesDotsAndCase(String raw, String expected) {
        RegistrationCode.Parsed parsed = RegistrationCode.parse(raw);

        assertThat(parsed.problem()).isEmpty();
        assertThat(parsed.code()).get().extracting(RegistrationCode::value).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABCDEFG", "ABCDEFGHJ", "ABCD!FGH", "ABCD_FGH", "가나다라마바사아", "ABCDÉFGH", ""})
    void nonAlphanumericOrWrongLengthIsInvalidFormat(String raw) {
        assertThat(RegistrationCode.parse(raw).problem()).contains(Problem.INVALID_FORMAT);
        assertThat(RegistrationCode.parse(null).problem()).contains(Problem.INVALID_FORMAT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABCDEFG0", "1BCDEFGH", "ABCIEFGH", "ABCLEFGH", "OBCDEFGH", "abcdefgo"})
    void alphanumericOutsideIssuedAlphabetIsOutsideAlphabet(String raw) {
        assertThat(RegistrationCode.parse(raw).problem()).contains(Problem.OUTSIDE_ALPHABET);
    }

    @Test
    void displaysFourAndFourAndPrefixIsFirstFour() {
        RegistrationCode code = RegistrationCode.issued("7F3K92QD");

        assertThat(code.display()).isEqualTo("7F3K · 92QD");
        assertThat(code.prefix()).isEqualTo("7F3K");
        assertThat(code).isEqualTo(RegistrationCode.parse("7f3k 92qd").code().orElseThrow());
    }

    @Test
    void toStringNeverShowsTheCode() {
        RegistrationCode code = RegistrationCode.issued("7F3K92QD");

        assertThat(code.toString()).doesNotContain("7F3K").doesNotContain("92QD");
    }

    @Test
    void issuedRejectsUnnormalizedOrForeignValues() {
        assertThatThrownBy(() -> RegistrationCode.issued("7f3k92qd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegistrationCode.issued("0F3K92QD")).isInstanceOf(IllegalArgumentException.class);
    }
}
