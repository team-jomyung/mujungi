package kr.mujoong.core.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
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
@Tag("parent-account")
class ChildRulesTest {
    private static final LocalDate REF = LocalDate.of(2026, 10, 20);
    private static Valid create(String name, Integer age, List<String> interests) {
        return ChildInput.forCreate(name, age == null ? null : REF.minusYears(age).toString(), interests, REF);
    }


    @Test
    void createTrimsAndAcceptsTheBoundaries() {
        Valid valid = create("  하늘  ", 6, List.of(" 공룡 ", "블록 놀이"));

        assertThat(valid.displayName()).isEqualTo("하늘");
        assertThat(BirthDateRules.ageOn(valid.birthDate(), REF)).isEqualTo(6);
        assertThat(valid.interests()).containsExactly("공룡", "블록 놀이");
        assertThat(create("가".repeat(10), 12, null).interests()).isEmpty();
        assertThat(valid.toString()).doesNotContain("하늘", "공룡");
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 13, -1})
    void ageOutsideSixToTwelveIsRejected(int age) {
        expectInvalid(() -> create("하늘", age, List.of()));
    }

    @Test
    void nameMustBeOneToTenCharactersAfterTrimming() {
        expectInvalid(() -> create("   ", 8, List.of()));
        expectInvalid(() -> create(null, 8, List.of()));
        expectInvalid(() -> create("가".repeat(11), 8, List.of()));
        expectInvalid(() -> create("하늘", null, List.of()));
    }

    @Test
    void interestsAreAtMostFiveDistinctAndTenCharactersEach() {
        expectInvalid(() -> create("하늘", 8, List.of("a", "b", "c", "d", "e", "f")));
        expectInvalid(() -> create("하늘", 8, List.of("공룡", " 공룡")));
        expectInvalid(() -> create("하늘", 8, List.of("가".repeat(11))));
        expectInvalid(() -> create("하늘", 8, Arrays.asList("공룡", null)));
        assertThat(create("하늘", 8, List.of("a", "b", "c", "d", "e")).interests()).hasSize(5);
    }

    @Test
    void updateKeepsTheFieldsThatWereNotSentAndRejectsAnEmptyPatch() {
        Valid current = new Valid("하늘", REF.minusYears(8), List.of("공룡"));
        assertThat(ChildInput.forUpdate(current, null, "2017-10-20", null, REF))
                .isEqualTo(new Valid("하늘", REF.minusYears(9), List.of("공룡")));
        assertThat(ChildInput.forUpdate(current, "바다", null, List.of(), REF))
                .isEqualTo(new Valid("바다", REF.minusYears(8), List.of()));
        expectInvalid(() -> ChildInput.forUpdate(current, null, null, null, REF));
        expectInvalid(() -> ChildInput.forUpdate(current, null, "2013-10-20", null, REF));
    }

    @Test void exactBirthdayBoundariesAndLeapDayAreValidated() {
        BirthDateRules.requireEligible(LocalDate.parse("2013-10-21"), REF);
        BirthDateRules.requireEligible(LocalDate.parse("2020-10-20"), REF);
        expectInvalid(() -> BirthDateRules.requireEligible(LocalDate.parse("2013-10-20"), REF));
        expectInvalid(() -> BirthDateRules.requireEligible(LocalDate.parse("2020-10-21"), REF));
        expectInvalid(() -> BirthDateRules.parse("2019-02-29"));
        expectInvalid(() -> BirthDateRules.parse("1900-02-29"));
        expectInvalid(() -> BirthDateRules.parse("2020-2-01"));
        assertThat(BirthDateRules.parse("2000-02-29")).isEqualTo(LocalDate.of(2000,2,29));
        assertThat(BirthDateRules.ageOn(LocalDate.of(2012,2,29), LocalDate.of(2025,2,28))).isEqualTo(12);
        assertThat(BirthDateRules.ageOn(LocalDate.of(2012,2,29), LocalDate.of(2025,3,1))).isEqualTo(13);
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

    /**
     * 화면과 같은 규칙(리뷰 8): 공백 = 유니코드 White_Space 속성, 글자 수 = NFC 뒤 코드 포인트. 같은 표본을
     * frontend/tests/u2/parent/stop-expressions.test.tsx '서버와 같은 규칙'이 쓴다.
     */
    @Test
    void whitespaceAndLengthFollowTheSameUnicodeRulesAsTheScreen() {
        assertThat(StopExpressionRules.normalize("이제　그만")).as("전각 공백").isEqualTo("이제그만");
        assertThat(StopExpressionRules.normalize("이제 그만")).as("줄바꿈 없는 공백").isEqualTo("이제그만");
        assertThat(StopExpressionRules.normalize("끝\u0085내")).as("NEL").isEqualTo("끝내");
        assertThat(StopExpressionRules.normalize("이제﻿그만")).as("BOM은 공백이 아니다").isEqualTo("이제﻿그만");
        expectInvalid(() -> StopExpressionRules.validate(List.of(" 끝 ")));
        assertThat(StopExpressionRules.validate(List.of(" 끝내 ")).get(0).text()).isEqualTo("끝내");
        String nfdTwenty = java.text.Normalizer.normalize("가".repeat(20), java.text.Normalizer.Form.NFD);
        assertThat(StopExpressionRules.validate(List.of(nfdTwenty))).as("NFC로 20자").hasSize(1);
        assertThat(StopExpressionRules.validate(List.of("😀".repeat(20)))).as("코드 포인트 20개").hasSize(1);
        expectInvalid(() -> StopExpressionRules.validate(List.of("😀".repeat(21))));
        expectInvalid(() -> StopExpressionRules.validate(List.of("이제　그만", "이제그만")));
    }

    private static void expectInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CoreApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
