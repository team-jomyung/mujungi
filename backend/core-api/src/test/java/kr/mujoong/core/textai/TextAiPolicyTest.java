package kr.mujoong.core.textai;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import kr.mujoong.core.knowledge.KnowledgeProperties;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("u5a")
class TextAiPolicyTest {
    private final ForbiddenExpressionPolicy policy = new ForbiddenExpressionPolicy();

    @Test void dictionaryCoversSevenCategoriesAndIgnoresSpacingPunctuationAndWidth() {
        for (String text : List.of("진 단", "치.료", "비정상", "핵심 원인", "학교에서도 그럴",
                "화가났군요", "꼭해야해요", "점 수", "정상이에요", "정상입니다")) {
            assertThat(policy.isAllowed(text)).as(text).isFalse();
        }
        assertThat(ForbiddenExpressionPolicy.normalize("Ａ\u200B - １２％")).isEqualTo("a12%");
        assertThat(policy.isAllowed("친구가 공놀이를 하고 있어.")).isTrue();
        assertThat(policy.isAllowed(null)).isFalse();
        assertThat(policy.isAllowed(" ! ")).isFalse();
    }
    @Test void allFieldsMustBePresentAndAllowed() {
        policy.requireAllowed("친구", "놀이");
        assertThatThrownBy(() -> policy.requireAllowed("좋은 문장", "진단"))
                .hasMessageContaining("FORBIDDEN_OUTPUT");
        assertThatThrownBy(() -> policy.requireAllowed((String) null)).hasMessageContaining("INVALID_OUTPUT");
        assertThatThrownBy(() -> policy.requireAllowed(" ")).hasMessageContaining("INVALID_OUTPUT");
    }
    @Test void requestIsImmutableAndRejectsIncompleteOrUnsupportedContext() {
        var interests = new ArrayList<>(List.of("놀이"));
        var prompt = new ScenarioPrompt("목표", "행동", "기회", "상황", "친구", 8, interests, null);
        interests.clear();
        assertThat(prompt.interests()).containsExactly("놀이");
        for (int age : new int[]{5, 13}) {
            assertThatThrownBy(() -> new ScenarioPrompt("목표","행동","기회","상황","친구",age,List.of(),null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ScenarioPrompt(" ","행동","기회","상황","친구",8,List.of(),null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScenarioPrompt(null,"행동","기회","상황","친구",8,List.of(),null))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidTimeoutOrRetryConfigurationFailsEarly() {
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofMillis(-1))) {
            assertThatThrownBy(() -> new TextAiProperties(timeout, new TextAiProperties.Retry(0,Duration.ZERO)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (int retry : new int[]{-1, 2}) {
            assertThatThrownBy(() -> new TextAiProperties.Retry(retry,Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new TextAiProperties.Retry(1,Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void configurationBindsTheYmlKeysDisablesGraphAndProviderAndHasNoCodeDefaults() {
        // 값은 application.yml에만 있다(U5a 리뷰 5). 이 시험은 yml을 읽지 않으므로 같은 값을 직접 준다
        new ApplicationContextRunner().withUserConfiguration(TextAiConfiguration.class)
                .withPropertyValues("mujoong.text-ai.request-timeout=2s", "mujoong.text-ai.retry.max-retries=1",
                        "mujoong.text-ai.retry.backoff=250ms", "mujoong.knowledge.neo4j.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(KnowledgeProperties.class).enabled()).isFalse();
                    assertThat(context.getBean(TextAiProperties.class).requestTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(context.getBean(TextAiProperties.class).retry().maxRetries()).isEqualTo(1);
                    assertThat(context.getBean(TextAiProperties.class).retry().backoff()).isEqualTo(Duration.ofMillis(250));
                    assertThatThrownBy(() -> context.getBean(TextAiProvider.class).generateScenario(null,Duration.ZERO))
                            .hasMessageContaining("UNAVAILABLE");
                });
        // 키가 빠지면 코드 기본값으로 조용히 돌지 않고 기동이 실패한다
        new ApplicationContextRunner().withUserConfiguration(TextAiConfiguration.class)
                .run(context -> assertThat(context).hasFailed());
    }
}
