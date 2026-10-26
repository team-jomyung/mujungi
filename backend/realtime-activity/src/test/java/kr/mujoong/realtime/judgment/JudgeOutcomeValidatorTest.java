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

    // ---- 같은 걸음 묶음 (ai-io.md 10.10, 편차 FT-1024-f) ----

    /** C안 다시 해 보기(J5) 선택지와 그 단계의 묶음: 성공 둘, 장면 닫기 넷(다시 해 보기 한도 1). */
    private static final List<String> J5 = List.of("FULL", "PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT", "UNREADABLE", "COPIED");
    private static final List<List<String>> J5_GROUPS = List.of(List.of("FULL", "COPIED"),
            List.of("PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT"));

    private JudgeOutcome grouped(String first, double firstP, String second, double secondP, List<List<String>> groups) {
        return validator.validateRanked(first, firstP, second, secondP, J5, groups, LATENCY, "luna-test", "prompt-service-v4");
    }

    @Test
    void theFifthRunCaseIsConfirmedBecauseFullAndCopiedAreTheSameStepAndTheModelsFirstOptionStays() {
        // 5회차 S6(실제 API + 합성 아이 음성): FULL 0.38 · COPIED 0.29 — 둘 다 도움 뒤 성공이라 합 0.67이 하한 0.6을 넘는다
        JudgeOutcome outcome = grouped("FULL", 0.38, "COPIED", 0.29, J5_GROUPS);

        assertThat(outcome.isConfirmed()).isTrue();
        assertThat(outcome.sameStepSum()).as("1순위 혼자가 아니라 묶음 합으로 확정").isTrue();
        assertThat(outcome.firstOption()).as("1순위는 모델이 낸 그대로(기록이 FULL · COPIED를 구분)").isEqualTo("FULL");
        assertThat(outcome.firstProbability()).isEqualTo(0.38);
        assertThat(outcome.secondOption()).isEqualTo("COPIED");
        assertThat(outcome.secondProbability()).isEqualTo(0.29);
        assertThat(outcome.abstainReason()).isNull();
        assertThat(grouped("COPIED", 0.38, "FULL", 0.29, J5_GROUPS).firstOption()).as("묶음 안 순서도 모델 그대로").isEqualTo("COPIED");
        assertThat(outcome.toString()).doesNotContain("FULL").doesNotContain("0.38").contains("sameStepSum");
    }

    @Test
    void twoOptionsOfDifferentStepsAreStillAbstainedExactlyAsBefore() {
        // 5회차 S10 첫 대답: PARTIAL 0.44 · FULL 0.44 — 장면 닫기와 성공은 다른 걸음이다
        JudgeOutcome tie = grouped("PARTIAL", 0.44, "FULL", 0.44, J5_GROUPS);
        JudgeOutcome split = grouped("FULL", 0.38, "PARTIAL", 0.29, J5_GROUPS);

        assertThat(tie.abstainReason()).isEqualTo(AbstainReason.LOW_CONFIDENCE);
        assertThat(split.abstainReason()).isEqualTo(AbstainReason.LOW_CONFIDENCE);
        assertThat(split.sameStepSum()).isFalse();
        assertThat(split.firstProbability()).as("보류 기록용 확률은 그대로").isEqualTo(0.38);
        assertThat(grouped("FULL", 0.62, "PARTIAL", 0.5, J5_GROUPS).abstainReason()).as("다른 걸음이면 최소 차이도 그대로")
                .isEqualTo(AbstainReason.LOW_MARGIN);
    }

    @Test
    void withoutGroupsTheSamePairIsAbstainedLikeTodayAndTheOldOverloadIsUnchanged() {
        assertThat(grouped("FULL", 0.38, "COPIED", 0.29, List.of()).abstainReason()).isEqualTo(AbstainReason.LOW_CONFIDENCE);
        assertThat(grouped("FULL", 0.38, "COPIED", 0.29, null).abstainReason()).isEqualTo(AbstainReason.LOW_CONFIDENCE);
        assertThat(validator.validateRanked("FULL", 0.38, "COPIED", 0.29, J5, LATENCY, "luna-test", "p").abstainReason())
                .isEqualTo(AbstainReason.LOW_CONFIDENCE);
    }

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @CsvSource({
        "0.30, 0.25, ABSTAINED",
        "0.35, 0.25, CONFIRMED",
        "0.34, 0.25, ABSTAINED",
        "0.50, 0.45, CONFIRMED"
    })
    void inOneGroupTheFloorUsesTheSumWithTheSameEqualIsConfirmedBoundary(double firstP, double secondP, JudgmentOutcomeKind kind) {
        JudgeOutcome outcome = grouped("PARTIAL", firstP, "HELP", secondP, J5_GROUPS);

        assertThat(outcome.kind()).isEqualTo(kind);
        if (kind == JudgmentOutcomeKind.ABSTAINED) {
            assertThat(outcome.abstainReason()).as("합해도 하한 미만").isEqualTo(AbstainReason.LOW_CONFIDENCE);
            assertThat(outcome.sameStepSum()).isFalse();
        } else {
            assertThat(outcome.sameStepSum()).isTrue();
            assertThat(outcome.firstOption()).isEqualTo("PARTIAL");
        }
    }

    @Test
    void inOneGroupTheMarginBetweenTheTwoIsNotCheckedButAClearFirstOptionIsAnOrdinaryConfirmation() {
        // 확률 합이 1을 넘는 응답(공급자 반올림 등)에서도 두 선택지 사이의 차이는 보지 않는다
        JudgeOutcome closeInGroup = grouped("FULL", 0.62, "COPIED", 0.5, J5_GROUPS);
        assertThat(closeInGroup.isConfirmed()).isTrue();
        assertThat(closeInGroup.sameStepSum()).isTrue();
        assertThat(grouped("FULL", 0.62, "COPIED", 0.5, List.of()).abstainReason()).isEqualTo(AbstainReason.LOW_MARGIN);

        JudgeOutcome clear = grouped("FULL", 0.7, "COPIED", 0.2, J5_GROUPS);
        assertThat(clear.isConfirmed()).isTrue();
        assertThat(clear.sameStepSum()).as("묶음 없이도 확정이면 묶음 합 표지를 달지 않는다").isFalse();
    }

    @Test
    void theRangeSetAndDuplicateChecksStillComeBeforeTheGroupRule() {
        List<List<String>> wide = List.of(List.of("FULL", "COPIED", "NOT_A_CODE"));
        assertThat(grouped("FULL", 0.38, "NOT_A_CODE", 0.29, wide).abstainReason()).isEqualTo(AbstainReason.OPTION_OUT_OF_SET);
        assertThat(grouped("FULL", 0.38, "FULL", 0.29, J5_GROUPS).abstainReason()).isEqualTo(AbstainReason.DUPLICATE_OPTION);
        assertThat(grouped("FULL", 1.2, "COPIED", 0.29, J5_GROUPS).abstainReason()).isEqualTo(AbstainReason.PROBABILITY_OUT_OF_RANGE);
        assertThat(grouped(null, 0.38, "COPIED", 0.29, J5_GROUPS).abstainReason()).isEqualTo(AbstainReason.SCHEMA_INVALID);
    }

    // ---- 요청 하한 (ai-io.md 10.10, 편차 FT-1024-h) ----

    /** C안 기회(J2) 선택지와 그 단계의 묶음(다시 설명으로 가는 둘). */
    private static final List<String> J2 = List.of("FULL", "PARTIAL", "HELP", "NOT_UNDERSTOOD", "IRRELEVANT", "UNREADABLE");
    private static final List<List<String>> J2_GROUPS = List.of(List.of("NOT_UNDERSTOOD", "IRRELEVANT"));

    private JudgeOutcome floored(String first, double firstP, String second, double secondP, Double floor) {
        return validator.validateRanked(first, firstP, second, secondP, J2, J2_GROUPS, floor, LATENCY, "luna-test", "prompt-service-v4");
    }

    @ParameterizedTest(name = "PARTIAL {0} / FULL {1} under 0.45 -> {2} {3}")
    @CsvSource({
        "0.48, 0.28, CONFIRMED, ",
        "0.56, 0.20, CONFIRMED, ",
        "0.45, 0.30, CONFIRMED, ",
        "0.44, 0.20, ABSTAINED, LOW_CONFIDENCE",
        "0.46, 0.31, CONFIRMED, ",
        "0.50, 0.36, ABSTAINED, LOW_MARGIN",
        "0.59, 0.10, CONFIRMED, "
    })
    void theLoweredFloorConfirmsAFirstOptionBetweenItAndTheGlobalFloorButKeepsTheMargin(double firstP, double secondP,
            JudgmentOutcomeKind kind, AbstainReason reason) {
        // 5회차 J2(실제 API + 합성 아이 음성): 일부만 대답 PARTIAL 0.56 · 0.52 · 0.56 · 0.48, 2순위와 차이 0.20 이상. 경계는 같으면 확정이다
        JudgeOutcome outcome = floored("PARTIAL", firstP, "FULL", secondP, 0.45);

        assertThat(outcome.kind()).isEqualTo(kind);
        assertThat(outcome.abstainReason()).isEqualTo(reason);
        assertThat(outcome.firstOption()).isEqualTo("PARTIAL");
        assertThat(outcome.firstProbability()).as("확정 · 보류 모두 기록용 확률은 그대로").isEqualTo(firstP);
        assertThat(outcome.sameStepSum()).isFalse();
        assertThat(floored("PARTIAL", firstP, "FULL", secondP, null).abstainReason()).as("하한을 싣지 않으면 전역 0.6")
                .isIn(AbstainReason.LOW_CONFIDENCE, AbstainReason.LOW_MARGIN);
    }

    @Test
    void theFifthRunHelpAnswersAreConfirmedOnlyWhenTheyReachTheLoweredFloor() {
        // 5회차 J2 도움 요청 HELP 0.46 · 0.44(실제 API + 합성 아이 음성)
        assertThat(floored("HELP", 0.46, "NOT_UNDERSTOOD", 0.30, 0.45).isConfirmed()).isTrue();
        assertThat(floored("HELP", 0.44, "NOT_UNDERSTOOD", 0.28, 0.45).abstainReason()).isEqualTo(AbstainReason.LOW_CONFIDENCE);
        // 13.7의 J2 HELP 0.54 · NOT_UNDERSTOOD 0.30: 전역 하한에서는 다른 걸음이라 보류였고, 낮춘 하한에서는 1순위 혼자 확정이다
        assertThat(floored("HELP", 0.54, "NOT_UNDERSTOOD", 0.30, null).abstainReason()).isEqualTo(AbstainReason.LOW_CONFIDENCE);
        JudgeOutcome help = floored("HELP", 0.54, "NOT_UNDERSTOOD", 0.30, 0.45);
        assertThat(help.isConfirmed()).isTrue();
        assertThat(help.firstOption()).isEqualTo("HELP");
        assertThat(help.sameStepSum()).as("1순위 혼자 넘은 확정은 묶음 합 표지가 없다").isFalse();
    }

    @Test
    void aRequestedFloorOnlyLowersTheGlobalFloorAndValuesItCannotUseAreIgnored() {
        for (Double ignored : new Double[] {null, 0.6, 0.7, 1.0, Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.5}) {
            assertThat(floored("PARTIAL", 0.55, "FULL", 0.2, ignored).abstainReason()).as("하한 " + ignored)
                    .isEqualTo(AbstainReason.LOW_CONFIDENCE);
            assertThat(floored("PARTIAL", 0.6, "FULL", 0.2, ignored).isConfirmed()).as("하한 " + ignored).isTrue();
        }
        assertThat(floored("PARTIAL", 0.55, "FULL", 0.2, 0.55).isConfirmed()).isTrue();
        assertThat(floored("PARTIAL", 0.54, "FULL", 0.2, 0.55).abstainReason()).isEqualTo(AbstainReason.LOW_CONFIDENCE);
        assertThat(floored("PARTIAL", 0.55, "FULL", 0.45, 0.45).abstainReason()).as("최소 차이는 언제나 전역 0.15")
                .isEqualTo(AbstainReason.LOW_MARGIN);
    }

    @Test
    void theSameStepSumIsComparedWithTheSameLoweredFloor() {
        JudgeOutcome sum = floored("NOT_UNDERSTOOD", 0.25, "IRRELEVANT", 0.20, 0.45);
        assertThat(sum.isConfirmed()).as("합 0.45는 낮춘 하한과 같아 확정").isTrue();
        assertThat(sum.sameStepSum()).isTrue();
        assertThat(sum.firstOption()).isEqualTo("NOT_UNDERSTOOD");
        assertThat(floored("NOT_UNDERSTOOD", 0.25, "IRRELEVANT", 0.19, 0.45).abstainReason()).isEqualTo(AbstainReason.LOW_CONFIDENCE);
        assertThat(floored("NOT_UNDERSTOOD", 0.25, "IRRELEVANT", 0.20, null).abstainReason()).as("전역 하한이면 합 0.45는 보류")
                .isEqualTo(AbstainReason.LOW_CONFIDENCE);
    }

    @Test
    void theOverloadsWithoutARequestedFloorKeepTheGlobalFloor() {
        assertThat(validator.validateRanked("PARTIAL", 0.5, "FULL", 0.2, J2, LATENCY, "luna-test", "p").abstainReason())
                .isEqualTo(AbstainReason.LOW_CONFIDENCE);
        assertThat(validator.validateRanked("PARTIAL", 0.5, "FULL", 0.2, J2, J2_GROUPS, LATENCY, "luna-test", "p").abstainReason())
                .isEqualTo(AbstainReason.LOW_CONFIDENCE);
        assertThat(floored("PARTIAL", 0.5, "NOT_A_CODE", 0.2, 0.45).abstainReason()).as("범위 · 선택지 · 중복 검사가 먼저")
                .isEqualTo(AbstainReason.OPTION_OUT_OF_SET);
    }

    @Test
    void theSameStepMarkIsOnlyForConfirmations() {
        assertThatThrownBy(() -> new JudgeOutcome(JudgmentOutcomeKind.ABSTAINED, "FULL", 0.38, "COPIED", 0.29,
                AbstainReason.LOW_CONFIDENCE, LATENCY, null, "p", true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JudgeOutcome(JudgmentOutcomeKind.SUPERSEDED, null, null, null, null, null, LATENCY, null, "p",
                true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(JudgeOutcome.confirmed("FULL", 0.9, "COPIED", 0.05, LATENCY, null, "p").sameStepSum()).isFalse();
        assertThat(JudgeOutcome.abstained(AbstainReason.TIMEOUT, LATENCY, null, "p").sameStepSum()).isFalse();
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
