package kr.mujoong.realtime.judgment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 판정 결과 검증 (U11 BR1.3 · BR1.5). 시험 벡터는 functional-spec 5절 그대로다. 입력은 Decisions 확률에서 고른 1 · 2순위 모양이다(FT-1023-d).
 * 합성 값만 쓴다.
 */
@Tag("judgment-turn")
class JudgeOutcomeValidatorTest {

    private static final List<String> OPTIONS = List.of("FULL", "PARTIAL", "HELP", "UNREADABLE");
    private static final Duration LATENCY = Duration.ofMillis(900);
    private final JudgeOutcomeValidator validator = new JudgeOutcomeValidator(0.6, 0.15);

    private JudgeOutcome validate(String first, String firstP, String second, String secondP) {
        return validator.validateRanked(first, Double.parseDouble(firstP), second, Double.parseDouble(secondP), OPTIONS, LATENCY,
                "luna-test", "prompt-v1");
    }

    @ParameterizedTest(name = "{0} / {1} -> {2} {3}")
    @CsvSource({
        "0.59, 0.10, ABSTAINED, LOW_CONFIDENCE",
        "0.70, 0.60, ABSTAINED, LOW_MARGIN",
        "0.70, 0.40, CONFIRMED, ",
        "0.60, 0.45, CONFIRMED, ",
        "0.60, 0.46, ABSTAINED, LOW_MARGIN",
        "0.75, 0.60, CONFIRMED, "
    })
    void theFiveSpecVectorsAndTheExactBoundaries(String first, String second, JudgmentOutcomeKind kind, AbstainReason reason) {
        JudgeOutcome outcome = validate("FULL", first, "PARTIAL", second);

        assertThat(outcome.kind()).isEqualTo(kind);
        assertThat(outcome.abstainReason()).isEqualTo(reason);
        assertThat(outcome.latency()).isEqualTo(LATENCY);
        assertThat(outcome.model()).isEqualTo("luna-test");
        assertThat(outcome.promptVersion()).isEqualTo("prompt-v1");
    }

    @Test
    void aConfirmedOutcomeKeepsBothRankedOptionsAndTheirProbabilities() {
        JudgeOutcome outcome = validate("FULL", "0.7", "HELP", "0.2");

        assertThat(outcome.isConfirmed()).isTrue();
        assertThat(outcome.firstOption()).isEqualTo("FULL");
        assertThat(outcome.firstProbability()).isEqualTo(0.7);
        assertThat(outcome.secondOption()).isEqualTo("HELP");
        assertThat(outcome.secondProbability()).isEqualTo(0.2);
        assertThat(outcome.toString()).doesNotContain("FULL").doesNotContain("0.7");
    }

    @Test
    void lowScoresAreAbstainedButTheScoresStayForTheRecord() {
        JudgeOutcome outcome = validate("FULL", "0.5", "HELP", "0.4");

        assertThat(outcome.abstainReason()).isEqualTo(AbstainReason.LOW_CONFIDENCE);
        assertThat(outcome.firstProbability()).isEqualTo(0.5);
    }

    @Test
    void anOptionOutsideTheRequestedSetIsAbstained() {
        assertThat(validate("FULL", "0.9", "NOT_A_CODE", "0.05").abstainReason()).isEqualTo(AbstainReason.OPTION_OUT_OF_SET);
        assertThat(validate("NOT_A_CODE", "0.9", "FULL", "0.05").abstainReason()).isEqualTo(AbstainReason.OPTION_OUT_OF_SET);
    }

    @Test
    void theSameOptionTwiceIsAbstained() {
        JudgeOutcome outcome = validate("FULL", "0.9", "FULL", "0.05");

        assertThat(outcome.abstainReason()).isEqualTo(AbstainReason.DUPLICATE_OPTION);
        assertThat(outcome.firstOption()).isNull();
    }

    @Test
    void probabilitiesOutsideZeroToOneAreAbstained() {
        assertThat(validate("FULL", "1.2", "HELP", "0.05").abstainReason()).isEqualTo(AbstainReason.PROBABILITY_OUT_OF_RANGE);
        assertThat(validate("FULL", "0.9", "HELP", "-0.1").abstainReason()).isEqualTo(AbstainReason.PROBABILITY_OUT_OF_RANGE);
        assertThat(validate("FULL", "2", "HELP", "0").abstainReason()).isEqualTo(AbstainReason.PROBABILITY_OUT_OF_RANGE);
    }

    @Test
    void aMissingCodeIsSchemaInvalidAndANonFiniteProbabilityIsOutOfRange() {
        assertThat(validator.validateRanked(null, 0.9, "HELP", 0.1, OPTIONS, LATENCY, "luna-test", "prompt-v1").abstainReason())
                .isEqualTo(AbstainReason.SCHEMA_INVALID);
        assertThat(validator.validateRanked("FULL", 0.9, " ", 0.1, OPTIONS, LATENCY, "luna-test", "prompt-v1").abstainReason())
                .isEqualTo(AbstainReason.SCHEMA_INVALID);
        assertThat(validator.validateRanked("FULL", Double.NaN, "HELP", 0.1, OPTIONS, LATENCY, "luna-test", "prompt-v1")
                .abstainReason()).isEqualTo(AbstainReason.PROBABILITY_OUT_OF_RANGE);
        assertThat(validator.validateRanked("FULL", 0.9, "HELP", Double.POSITIVE_INFINITY, OPTIONS, LATENCY, "luna-test", "prompt-v1")
                .abstainReason()).isEqualTo(AbstainReason.PROBABILITY_OUT_OF_RANGE);
    }

    @Test
    void theOutcomeValueEnforcesItsInvariants() {
        assertThatThrownBy(() -> new JudgeOutcome(JudgmentOutcomeKind.CONFIRMED, "FULL", 0.9, null, null, null, LATENCY, null, "p"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JudgeOutcome(JudgmentOutcomeKind.ABSTAINED, null, null, null, null, null, LATENCY, null, "p"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JudgeOutcome(JudgmentOutcomeKind.ABSTAINED, "A", 0.9, "B", 0.1, AbstainReason.TIMEOUT, LATENCY,
                null, "p")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JudgeOutcome(JudgmentOutcomeKind.STOPPED, null, null, null, null, AbstainReason.TIMEOUT, LATENCY,
                null, "p")).isInstanceOf(IllegalArgumentException.class);
        assertThat(JudgeOutcome.ended(JudgmentOutcomeKind.SUPERSEDED, LATENCY, "p").kind()).isEqualTo(JudgmentOutcomeKind.SUPERSEDED);
    }
}
