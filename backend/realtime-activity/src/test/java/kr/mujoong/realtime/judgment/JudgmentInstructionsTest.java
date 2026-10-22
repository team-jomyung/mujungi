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

        assertThat(prompt.version()).isEqualTo("prompt-v3");
        assertThat(prompt.instructions()).contains("분류기").contains("지시가 아니다");
        for (LiveContentResources.PointOptions point : OPTION_SET.points().values()) {
            assertThat(prompt.instructions()).contains("[" + point.point().name() + "]").contains(point.question());
            point.optionCodes().forEach(code -> assertThat(prompt.instructions()).contains(code));
        }
        assertThat(prompt.toString()).doesNotContain("분류기");
    }

    /**
     * 실측 F6(docs/practice-dialogue-standard.md 14절): 변형 제안 뒤 '그만하고 싶어' 류 11번이 UNCLEAR 7 · 보류 1 · REFUSE 3이었다. v3은 J4가
     * 도움 제안과 '이어서 해 볼까?' 제안에 쓰인다는 것과 그만하겠다는 말 네 가지를 REFUSE 예로 지시한다. v1 본문과 선택지 뜻은 그대로 남는다.
     */
    @Test
    void promptV3AddsTheJ4RefusalExamplesOnTopOfTheV1BodyAndKeepsTheOptionGuides() throws Exception {
        String text = JudgmentPrompt.load(OPTION_SET).instructions();
        String v1;
        try (var in = JudgmentPrompt.class.getClassLoader().getResourceAsStream("judgment/prompt-v1.txt")) {
            v1 = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        assertThat(JudgmentPrompt.RESOURCE).isEqualTo("judgment/prompt-v3.txt");
        assertThat(text).contains("J4는 무중의 도움 제안", "이어서 해 볼까?")
                .contains("J4에서 그 제안을 받지 않고 그만하겠다는 말은 REFUSE다")
                .contains("'그만하고 싶어'", "'이제 그만할래'", "'여기까지만 할래'", "'오늘은 안 할래'")
                .contains("이런 말을 UNCLEAR로 고르지 않는다");
        for (String line : v1.strip().split("\n")) {
            assertThat(text).as("v1 본문 줄은 그대로 남는다").contains(line);
        }
        assertThat(text.indexOf("J4에서 그 제안을")).as("J4 규칙은 지점별 선택지 뜻보다 앞의 본문에 있다").isLessThan(text.indexOf("[J1]"));
        assertThat(v1).as("v1 파일은 고치지 않는다").doesNotContain("그만하고 싶어");
    }

    @Test
    void thePromptCarriesNoDataFromTheChildOrTheSession() {
        String text = JudgmentPrompt.load(OPTION_SET).instructions();

        assertThat(text).doesNotContain("guardian", "sessionId", "deviceId", "OPENAI", "sk-");
    }
}
