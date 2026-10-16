package kr.mujoong.core.luna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.luna.adapter.LunaAdapterConfiguration;
import kr.mujoong.core.luna.adapter.LunaResultTextProvider;
import kr.mujoong.core.luna.adapter.LunaTextAiProvider;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.result.ResultAiException;
import kr.mujoong.core.result.ResultTextConfiguration;
import kr.mujoong.core.result.ResultTextProvider;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.core.textai.TextAiConfiguration;
import kr.mujoong.core.textai.TextAiException;
import kr.mujoong.core.textai.TextAiProvider;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Core Luna 빈 조건 · 기동 확인 (U9 BR1.1 ~ BR1.4 · SD11 · AC2.3.3 · NFR2.2). 키는 가짜 문자열만 쓴다(R-F3). */
@Tag("u9")
class LunaConfigurationTest {

    private final MutableClock clock = new MutableClock();
    private final TestEvents events = new TestEvents(clock);

    /** application.yml과 같은 모양의 값(이 시험은 yml을 읽지 않으므로 직접 준다). */
    static String[] lunaProperties(String baseUrl) {
        List<String> values = new ArrayList<>(List.of(
                "mujoong.text-ai.request-timeout=3s", "mujoong.text-ai.retry.max-retries=1",
                "mujoong.text-ai.retry.backoff=250ms", "mujoong.text-ai.result.request-timeout=10s",
                "mujoong.text-ai.result.temperature-judgment=0", "mujoong.text-ai.result.temperature-generation=0.2",
                "mujoong.text-ai.result.prompt-version=v1", "mujoong.knowledge.neo4j.enabled=false",
                "mujoong.luna.model=", "mujoong.luna.base-url=" + baseUrl, "mujoong.luna.max-response-bytes=1000000"));
        for (String purpose : List.of("scenario-generation", "scenario-review", "seed-generation", "result-text",
                "goal-candidates", "rejudgment")) {
            values.add("mujoong.luna.purpose." + purpose + ".max-output-tokens=4000");
            values.add("mujoong.luna.purpose." + purpose + ".reasoning-effort=medium");
        }
        return values.toArray(String[]::new);
    }

    ApplicationContextRunner runner(String baseUrl) {
        return new ApplicationContextRunner()
                .withUserConfiguration(TextAiConfiguration.class, LunaConfiguration.class)
                .withBean(JsonCodec.class, JsonCodec::shared)
                .withBean(MujoongClock.class, () -> clock)
                .withBean(EventLogger.class, events::logger)
                .withPropertyValues(lunaProperties(baseUrl));
    }

    private List<Map<String, Object>> startupLines() {
        return events.capture().events(EventCode.LUNA_STARTUP);
    }

    @Test
    void withoutAKeyNoLunaBeanExistsAndStartupSaysDisabled() {
        runner("https://api.openai.com/v1").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CoreLunaClient.class).doesNotHaveBean(LunaTransport.class);
            assertThat(context.getBean(LunaStartup.class).enabled()).isFalse();
            assertThat(context.getBean(LunaProperties.class).model()).as("env 모델이 비면 기본 이름(BR1.5)").isEqualTo("gpt-6-luna");
            assertThat(context).hasSingleBean(LunaSchemas.class);
        });
        assertThat(startupLines()).singleElement()
                .satisfies(line -> assertThat(LogCapture.attrs(line)).containsExactly(Map.entry("lunaState", "DISABLED_NO_KEY")));
    }

    @Test
    void aBlankKeyCountsAsNoKey() {
        runner("https://api.openai.com/v1").withPropertyValues("OPENAI_API_KEY=   ").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(CoreLunaClient.class);
        });
        assertThat(LogCapture.attrs(startupLines().getFirst())).containsEntry("lunaState", "DISABLED_NO_KEY");
    }

    @Test
    void aKeyEnablesTheClientAndTheStartupLineCarriesNoKeyValue() {
        runner("https://api.openai.com/v1").withPropertyValues("OPENAI_API_KEY=" + LunaTestFixtures.FAKE_KEY).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CoreLunaClient.class).hasSingleBean(LunaTransport.class);
            assertThat(context.getBean(LunaStartup.class).mode()).isEqualTo(LunaHosts.Mode.STRICT);
            assertThat(context.getBean(LunaTransport.class).endpoint().toString()).isEqualTo("https://api.openai.com/v1/responses");
        });
        assertThat(LogCapture.attrs(startupLines().getFirst())).containsEntry("lunaState", "ENABLED");
        assertThat(events.capture().anyLineContains(LunaTestFixtures.FAKE_KEY)).isFalse();
    }

    // ------------------------------------------------------------------ 기존 인터페이스 빈 (Step 14 · SD11 · AC2.3.3 · NFR2.2)

    ApplicationContextRunner adapters(String... extra) {
        return runner("https://api.openai.com/v1")
                .withUserConfiguration(ResultTextConfiguration.class, LunaAdapterConfiguration.class)
                .withPropertyValues(extra);
    }

    @Test
    void withAKeyEachExistingInterfaceHasExactlyOneBeanAndItIsTheLunaAdapter() {
        adapters("OPENAI_API_KEY=" + LunaTestFixtures.FAKE_KEY).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(TextAiProvider.class).values()).singleElement()
                    .isInstanceOf(LunaTextAiProvider.class);
            assertThat(context.getBeansOfType(ResultTextProvider.class).values()).singleElement()
                    .isInstanceOf(LunaResultTextProvider.class);
        });
    }

    @Test
    void withoutOrWithABlankKeyTheExistingFailureBeansStayAndBehaveAsBefore() {
        for (String key : new String[] {"OPENAI_API_KEY=", "OPENAI_API_KEY=  "}) {
            adapters(key).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansOfType(TextAiProvider.class)).hasSize(1);
                assertThat(context.getBeansOfType(ResultTextProvider.class)).hasSize(1);
                assertThat(context.getBean(TextAiProvider.class)).isNotInstanceOf(LunaTextAiProvider.class);
                assertThatThrownBy(() -> context.getBean(TextAiProvider.class).generateScenario(null, Duration.ZERO))
                        .isInstanceOfSatisfying(TextAiException.class,
                                e -> assertThat(e.reason()).isEqualTo(TextAiException.Reason.UNAVAILABLE));
                assertThatThrownBy(() -> context.getBean(ResultTextProvider.class).generate(null, Duration.ZERO))
                        .isInstanceOfSatisfying(ResultAiException.class, e -> assertThat(e.code()).isEqualTo("PROVIDER_UNAVAILABLE"));
            });
        }
    }

    @Test
    void aTestDoubleBeanWinsOverTheLunaAdapterWithOrWithoutAKey() {
        TextAiProvider textDouble = (prompt, timeout) -> null;
        ResultTextProvider resultDouble = (request, timeout) -> "{}";
        for (String key : new String[] {"OPENAI_API_KEY=" + LunaTestFixtures.FAKE_KEY, "OPENAI_API_KEY="}) {
            new ApplicationContextRunner()
                    .withBean(TextAiProvider.class, () -> textDouble)
                    .withBean(ResultTextProvider.class, () -> resultDouble)
                    .withUserConfiguration(TextAiConfiguration.class, ResultTextConfiguration.class, LunaConfiguration.class,
                            LunaAdapterConfiguration.class)
                    .withBean(JsonCodec.class, JsonCodec::shared)
                    .withBean(MujoongClock.class, () -> clock)
                    .withBean(EventLogger.class, events::logger)
                    .withPropertyValues(lunaProperties("https://api.openai.com/v1"))
                    .withPropertyValues(key)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBeansOfType(TextAiProvider.class).values()).containsExactly(textDouble);
                        assertThat(context.getBeansOfType(ResultTextProvider.class).values()).containsExactly(resultDouble);
                    });
        }
    }

    @Test
    void anAddressOutsideTheProfileRuleRefusesStartupEvenWithoutAKey() {
        runner("http://127.0.0.1:9/v1").run(context -> assertThat(context).as("프로필 없음 = STRICT").hasFailed());
        runner("https://api.openai.com/v1")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .run(context -> assertThat(context).as("test 프로필은 루프백만(R-F1)").hasFailed());
        runner("http://127.0.0.1:9/v1")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LunaStartup.class).mode()).isEqualTo(LunaHosts.Mode.TEST);
                });
        runner("http://localhost:8099/v1")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .run(context -> assertThat(context).hasNotFailed());
    }
}
