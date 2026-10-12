package kr.mujoong.core.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.child.ChildInput.Valid;
import kr.mujoong.core.child.StopExpressionRules.Expression;
import kr.mujoong.core.common.error.CoreApiException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 03 아이 입력 · SET-01 종료 표현 규칙(U2 · AC1.3.5 · AC1.3.8 · AC2.3.5 · AC2.3.6 · R8 · R9 · U2-J11). */
@Tag("u2")
class ChildRulesTest {

    @Test
    void createTrimsAndAcceptsTheBoundaries() {
        Valid valid = ChildInput.forCreate("  하늘  ", 6, List.of(" 공룡 ", "블록 놀이"));

        assertThat(valid.displayName()).isEqualTo("하늘");
        assertThat(valid.age()).isEqualTo(6);
        assertThat(valid.interests()).containsExactly("공룡", "블록 놀이");
        assertThat(ChildInput.forCreate("가".repeat(10), 12, null).interests()).isEmpty();
        assertThat(valid.toString()).doesNotContain("하늘", "공룡");
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 13, -1})
    void ageOutsideSixToTwelveIsRejected(int age) {
        expectInvalid(() -> ChildInput.forCreate("하늘", age, List.of()));
    }

    @Test
    void nameMustBeOneToTenCharactersAfterTrimming() {
        expectInvalid(() -> ChildInput.forCreate("   ", 8, List.of()));
        expectInvalid(() -> ChildInput.forCreate(null, 8, List.of()));
        expectInvalid(() -> ChildInput.forCreate("가".repeat(11), 8, List.of()));
        expectInvalid(() -> ChildInput.forCreate("하늘", null, List.of()));
    }

    @Test
    void interestsAreAtMostFiveDistinctAndTenCharactersEach() {
        expectInvalid(() -> ChildInput.forCreate("하늘", 8, List.of("a", "b", "c", "d", "e", "f")));
        expectInvalid(() -> ChildInput.forCreate("하늘", 8, List.of("공룡", " 공룡")));
        expectInvalid(() -> ChildInput.forCreate("하늘", 8, List.of("가".repeat(11))));
        expectInvalid(() -> ChildInput.forCreate("하늘", 8, Arrays.asList("공룡", null)));
        assertThat(ChildInput.forCreate("하늘", 8, List.of("a", "b", "c", "d", "e")).interests()).hasSize(5);
    }

    @Test
    void updateKeepsTheFieldsThatWereNotSentAndRejectsAnEmptyPatch() {
        Valid current = new Valid("하늘", 8, List.of("공룡"));

        assertThat(ChildInput.forUpdate(current, null, 9, null)).isEqualTo(new Valid("하늘", 9, List.of("공룡")));
        assertThat(ChildInput.forUpdate(current, "바다", null, List.of())).isEqualTo(new Valid("바다", 8, List.of()));
        expectInvalid(() -> ChildInput.forUpdate(current, null, null, null));
        expectInvalid(() -> ChildInput.forUpdate(current, null, 13, null));
    }

    @Test
    void stopExpressionsAreTwoToTwentyCharactersAndAtMostFive() {
        List<Expression> valid = StopExpressionRules.validate(List.of(" 이제 그만 ", "끝낼래"));

        assertThat(valid).extracting(Expression::text).containsExactly("이제 그만", "끝낼래");
        assertThat(valid).extracting(Expression::normalized).containsExactly("이제그만", "끝낼래");
        assertThat(StopExpressionRules.validate(List.of())).isEmpty();
        assertThat(StopExpressionRules.validate(List.of("가".repeat(20)))).hasSize(1);
        expectInvalid(() -> StopExpressionRules.validate(List.of("가")));
        expectInvalid(() -> StopExpressionRules.validate(List.of("가".repeat(21))));
        expectInvalid(() -> StopExpressionRules.validate(List.of("하나", "둘둘", "셋셋", "넷넷", "다섯", "여섯")));
        expectInvalid(() -> StopExpressionRules.validate(null));
        expectInvalid(() -> StopExpressionRules.validate(Arrays.asList("끝낼래", null)));
        assertThat(valid.get(0).toString()).doesNotContain("그만");
    }

    @Test
    void duplicatesAreFoundAfterRemovingSpacesAndCaseAndTheDefaultCannotBeAddedAgain() {
        expectInvalid(() -> StopExpressionRules.validate(List.of("이제 그만", "이제그만")));
        expectInvalid(() -> StopExpressionRules.validate(List.of("Stop it", "stopit")));
        expectInvalid(() -> StopExpressionRules.validate(List.of("무중아  그만할래")));
        assertThat(StopExpressionRules.DEFAULT_EXPRESSION).isEqualTo("무중아 그만할래");
        assertThat(StopExpressionRules.normalize(null)).isEmpty();
    }

    private static void expectInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CoreApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
