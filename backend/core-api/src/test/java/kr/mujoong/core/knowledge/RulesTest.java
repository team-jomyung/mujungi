package kr.mujoong.core.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import kr.mujoong.core.luna.LunaCallBudget;
import kr.mujoong.core.textai.ForbiddenExpressionPolicy;

@Tag("knowledge-graph")
class RulesTest {
    private final VariantPlanner planner = new VariantPlanner();
    private final KnowledgeSeed seed = GraphModelTest.seed("seed:S1", KnowledgeSeed.Origin.POC);
    private final VariantPlanner.Context base = new VariantPlanner.Context("그림", "교실", "아이와 친구", "CLASSMATE", "SCHOOL_GROUP");
    private final SeedContentValidator validator = new SeedContentValidator(new ForbiddenExpressionPolicy());

    static Map<String, Object> output(String sentence) {
        Map<String, Object> output = new HashMap<>();
        output.put("outcome", "GENERATED");
        output.put("skillName", "뜻 살피기");
        output.put("invariantSentence", sentence);
        output.put("family", "OTHER");
        output.put("preservationRule", "뜻을 살피는 과제를 남긴다");
        output.put("relationship", "CLASSMATE");
        output.put("situation", "SCHOOL_GROUP");
        output.put("misreadCost", "LOW");
        output.put("recoverable", true);
        output.put("signature", Map.of("target_skill", "RECOGNIZE", "required_inference", "표현의 의미 짚기",
                "perspective_structure", "상대의 의도 1층", "decidable_by_evidence", "YES", "direct_check_allowed", "YES"));
        return output;
    }

    @Test void sameContextChangesOnlyOneSurfaceField() {
        var plan = planner.plan(seed, base, List.of()).orElseThrow();
        assertThat(plan.grade()).isEqualTo("SAME_CONTEXT");
        assertThat(plan.context().relationship()).isEqualTo(base.relationship());
        assertThat(plan.context().situation()).isEqualTo(base.situation());
        assertThat(plan.signature()).isEqualTo(seed.signature());
        assertThat(plan.invariantSentence()).isEqualTo(seed.invariantSentence());
        assertThat(plan.preservationRule()).isEqualTo(seed.preservationRule());
        int changes = (plan.context().topic().equals(base.topic()) ? 0 : 1)
                + (plan.context().place().equals(base.place()) ? 0 : 1)
                + (plan.context().participants().equals(base.participants()) ? 0 : 1);
        assertThat(changes).isEqualTo(1);
    }
    // FT-1030-p: 같은 맥락 변형은 장소를 먼저 바꾸고, 그 장소가 이미 노출됐으면 활동, 참여자는 마지막이다(이름순으로 늘 참여자만 바뀌던 것을 대체)
    @Test void sameContextPrefersPlaceThenTopicOverParticipants() {
        var plan = planner.plan(seed, base, List.of()).orElseThrow();
        assertThat(plan.changedField()).isEqualTo("place");
        var places = java.util.stream.Stream.of("도서관", "운동장").map(place -> new VariantPlanner.Exposure(seed.seedId(),
                new VariantPlanner.Context(base.topic(), place, base.participants(), base.relationship(), base.situation()))).toList();
        assertThat(planner.plan(seed, base, places).orElseThrow().changedField()).isEqualTo("topic");
    }
    @Test void twoExposuresStaySameContext() {
        assertThat(planner.plan(seed, base, java.util.Collections.nCopies(2, new VariantPlanner.Exposure(seed.seedId(), base)))
                .orElseThrow().grade()).isEqualTo("SAME_CONTEXT");
    }
    @Test void thirdExposureUsesNearAndPreservesSurface() {
        var plan = planner.plan(seed, base, java.util.Collections.nCopies(3, new VariantPlanner.Exposure(seed.seedId(), base))).orElseThrow();
        assertThat(plan.grade()).isEqualTo("NEAR");
        assertThat(plan.context().topic()).isEqualTo(base.topic());
        assertThat(plan.context().place()).isEqualTo(base.place());
        assertThat(plan.context().participants()).isEqualTo(base.participants());
        assertThat(plan.misreadCost()).isEqualTo("LOW");
        assertThat(plan.recoverable()).isTrue();
    }
    @Test void otherSeedExposuresDoNotUnlockNear() {
        assertThat(planner.plan(seed, base, java.util.Collections.nCopies(3, new VariantPlanner.Exposure("seed:S2", base)))
                .orElseThrow().grade()).isEqualTo("SAME_CONTEXT");
    }
    @Test void recentVariantIsAvoided() {
        var first = planner.plan(seed, base, List.of()).orElseThrow();
        var second = planner.plan(seed, base, List.of(new VariantPlanner.Exposure(seed.seedId(), first.context()))).orElseThrow();
        assertThat(second.context()).isNotEqualTo(first.context());
    }
    @Test void unknownVocabularyIsRejected() {
        assertThat(planner.plan(seed, new VariantPlanner.Context("raw", "교실", "아이와 친구", "CLASSMATE", "SCHOOL_GROUP"), List.of())).isEmpty();
    }
    @Test void deterministicFallbackAfterAllRecent() {
        var exposures = new ArrayList<VariantPlanner.Exposure>();
        for (int i = 0; i < 30; i++) exposures.add(new VariantPlanner.Exposure(seed.seedId(), planner.plan(seed, base, exposures).orElseThrow().context()));
        assertThat(planner.plan(seed, base, exposures)).isEqualTo(planner.plan(seed, base, exposures));
    }

    @Test void acceptsCleanGeneratedOutput() {
        assertThat(validator.generated("seed:G0001", output("뜻을 살펴본다"), List.of("합성 전사"), List.of("가상아이")).seedId()).isEqualTo("seed:G0001");
    }
    @Test void overlapBoundaryFourVersusFive() {
        assertThat(SeedContentValidator.overlaps("가나다라", "가나다라")).isFalse();
        assertThat(SeedContentValidator.overlaps("가나다라마", "앞가나다라마뒤")).isTrue();
        assertThatThrownBy(() -> validator.generated("seed:G0001", output("가 나 다 라 마"), List.of("가나다라마"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unicodeLengthBoundary() {
        assertThat(validator.generated("seed:G0001", output("𠀀".repeat(60)), List.of(), List.of()).invariantSentence().codePointCount(0, 120)).isEqualTo(60);
        assertThatThrownBy(() -> validator.generated("seed:G0001", output("𠀀".repeat(61)), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsControlsAndMultipleSentences() {
        for (String invalid : List.of("뜻\n확인", "뜻\u200b확인", "하나. 둘.", "1. 목록")) {
            assertThatThrownBy(() -> validator.generated("seed:G0001", output(invalid), List.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void checksEveryFreeTextFieldForNamesAndContacts() {
        for (String key : List.of("skillName", "invariantSentence", "preservationRule")) {
            var values = output("뜻을 살펴본다");
            values.put(key, "가상아이의 표현");
            assertThatThrownBy(() -> validator.generated("seed:G0001", values, List.of(), List.of("가상아이")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> validator.generated("seed:G0001", output("010-1234-5678"), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsUnsafeContextAndLegacyResponse() {
        var values = output("뜻을 살펴본다");
        values.put("recoverable", false);
        assertThatThrownBy(() -> validator.generated("seed:G0001", values, List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.generated("seed:G0001", Map.of("skillName", "호환", "invariantSentence", "호환"), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void normalizesCompatibilityCharactersBeforeOverlap() {
        assertThat(SeedContentValidator.compact("Ａ Ｂ Ｃ Ｄ Ｅ")).isEqualTo("ABCDE");
        assertThatThrownBy(() -> validator.generated("seed:G0001", output("ＡＢＣＤＥ"), List.of("ABCDE"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void retryBudgetSharesCorrectionAndThreeAttempts() {
        var budget = new LunaCallBudget();
        assertThat(budget.takeAttempt()).isTrue();
        assertThat(budget.takeCorrection()).isTrue();
        assertThat(budget.takeCorrection()).isFalse();
        assertThat(budget.takeAttempt()).isTrue();
        assertThat(budget.takeTechnicalRetry()).isTrue();
        assertThat(budget.takeTechnicalRetry()).isFalse();
        assertThat(budget.takeAttempt()).isTrue();
        assertThat(budget.takeAttempt()).isFalse();
        assertThat(budget.attempts()).isEqualTo(3);
    }
    @Test void retryDeadlineCannotBeReset() {
        var budget = new LunaCallBudget();
        var deadline = java.time.Instant.parse("2030-01-01T00:00:00Z");
        budget.bindDeadline(deadline);
        budget.bindDeadline(deadline);
        assertThatThrownBy(() -> budget.bindDeadline(deadline.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
