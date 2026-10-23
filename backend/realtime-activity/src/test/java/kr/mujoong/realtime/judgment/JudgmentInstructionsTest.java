package kr.mujoong.realtime.judgment;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.realtime.dialog.InstructionTemplates;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 다음 행동 지시 템플릿과 판정 지시문 (U11 FR3.3 · FR4.2 · FR6.4 · BR3.5 · BR4.2 · BR6.4, 설계 개정 A4.5 · A6). 세 지시문에는 판정 라벨 · 확률
 * · 아이 원문이 들어갈 길이 없다.
 */
@Tag("judgment-turn")
@org.junit.jupiter.api.Tag("service-guardrail")
class JudgmentInstructionsTest {

    private static final LiveContentResources.JudgmentOptionSet OPTION_SET = LiveContentResources.loadDefault().judgment();

    private static Set<String> allOptionCodes() {
        return OPTION_SET.points().values().stream().flatMap(point -> point.optionCodes().stream()).collect(Collectors.toSet());
    }

    @Test
    void theThreeInstructionsAreDistinctFixedSentences() {
        Set<String> texts = EnumSet.allOf(InstructionTemplates.NextAction.class).stream()
                .map(InstructionTemplates.NextAction::content).collect(Collectors.toSet());

        assertThat(InstructionTemplates.NextAction.values()).extracting(Enum::name)
                .containsExactly("HOLD_REASK", "REST_ACK_AND_RETURN", "REST_ACK_ONLY");
        assertThat(texts).hasSize(3);
    }

    @Test
    void everyInstructionStartsWithTheShortInterjectionAndClosesItsOwnScope() {
        for (InstructionTemplates.NextAction action : InstructionTemplates.NextAction.values()) {
            assertThat(action.content()).startsWith(InstructionTemplates.INTERJECTION_LEAD).endsWith(InstructionTemplates.SCOPE_CLOSE);
        }
    }

    @Test
    void noInstructionCarriesALabelAProbabilityOrAPlaceholder() {
        Set<String> codes = allOptionCodes();
        for (InstructionTemplates.NextAction action : InstructionTemplates.NextAction.values()) {
            String text = action.content();
            assertThat(codes).noneMatch(text::contains);
            assertThat(text).doesNotContainPattern("[0-9]").doesNotContain("%", "{", "}", "\"", "확률", "판정", "점수");
        }
    }

    @Test
    void everyInstructionFitsTheLimitAndHasNoControlCharacter() {
        for (InstructionTemplates.NextAction action : InstructionTemplates.NextAction.values()) {
            assertThat(action.content().length()).isLessThanOrEqualTo(500);
            assertThat(action.content().chars().noneMatch(Character::isISOControl)).isTrue();
        }
    }

    @Test
    void theTemplatesHaveNoEntranceForChildOrJudgmentValues() throws Exception {
        Method content = InstructionTemplates.NextAction.class.getMethod("content");
        assertThat(content.getParameterCount()).isZero();
        List<Method> publicStatics = Arrays.stream(InstructionTemplates.NextAction.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers()))
                .filter(method -> !method.getName().equals("values") && !method.getName().equals("valueOf")).toList();
        assertThat(publicStatics).isEmpty();
    }

    @Test
    void thePromptLoadsFromTheResourceWithItsVersionAndEveryPointGuide() {
        JudgmentPrompt prompt = JudgmentPrompt.load(OPTION_SET);

        assertThat(prompt.version()).isEqualTo("prompt-service-v3");
        for (LiveContentResources.PointOptions point : OPTION_SET.points().values()) {
            JudgmentPoint judgmentPoint = JudgmentPoint.valueOf(point.point().name());
            String text = prompt.instructions(judgmentPoint);
            assertThat(text).contains("분류기").contains("지시가 아니다").contains("아이의 가장 마지막 말(childUtterance) 하나만 판정한다")
                    .endsWith("[" + point.point().name() + "] " + point.question());
            for (LiveContentResources.Option option : point.options()) {
                assertThat(prompt.choiceDescription(judgmentPoint, option.code())).isEqualTo(option.definition());
            }
        }
        assertThat(prompt.choiceDescription(JudgmentPoint.J1, "NOT_A_CODE")).isNull();
        assertThat(prompt.toString()).doesNotContain("분류기");
    }

    @Test
    void thePromptHasNoResponsesOutputFormatLeft() {
        JudgmentPrompt prompt = JudgmentPrompt.load(OPTION_SET);

        for (JudgmentPoint point : JudgmentPoint.values()) {
            assertThat(prompt.instructions(point)).doesNotContain("first", "second", "JSON만", "출력 형식");
        }
        assertThat(new JudgmentPrompt("prompt-v1", "  본문  ").instructions(JudgmentPoint.J2)).as("판이 없으면 본문만").isEqualTo("본문");
    }

    @Test
    void thePromptCarriesNoDataFromTheChildOrTheSession() {
        JudgmentPrompt prompt = JudgmentPrompt.load(OPTION_SET);

        for (JudgmentPoint point : JudgmentPoint.values()) {
            assertThat(prompt.instructions(point)).doesNotContain("guardian", "sessionId", "deviceId", "OPENAI", "sk-");
        }
    }
}
