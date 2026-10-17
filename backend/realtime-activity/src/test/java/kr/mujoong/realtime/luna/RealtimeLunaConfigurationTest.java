package kr.mujoong.realtime.luna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Realtime Luna 빈 조건 · 기동 확인 (U9 BR1.1 ~ BR1.4 · SD11 · AC2.3.3). 키는 가짜 문자열만 쓴다(R-F3). */
@Tag("u9")
class RealtimeLunaConfigurationTest {

    private final MutableClock clock = new MutableClock();
    private final LogCapture capture = LogCapture.detached();
    private final EventLogger events = TestEventLoggers.strict(clock, capture, RealtimeMetrics.inMemory());

    private ApplicationContextRunner runner(String baseUrl) {
        RealtimeProperties realtime = mock(RealtimeProperties.class, RETURNS_DEEP_STUBS);
        when(realtime.timing().judgment().liveBudget()).thenReturn(Duration.ofSeconds(4));
        return new ApplicationContextRunner()
                .withUserConfiguration(LunaConfiguration.class)
                .withBean(JsonCodec.class, JsonCodec::shared)
                .withBean(MujoongClock.class, () -> clock)
                .withBean(EventLogger.class, () -> events)
                .withBean(RealtimeProperties.class, () -> realtime)
                .withPropertyValues("mujoong.luna.model=${OPENAI_JUDGE_MODEL:gpt-6-luna}", "mujoong.luna.base-url=" + baseUrl,
                        "mujoong.luna.max-response-bytes=1000000", "mujoong.luna.purpose.judgment-live.max-output-tokens=4000",
                        "mujoong.luna.purpose.judgment-live.reasoning-effort=medium");
    }

    private String startupState() {
        List<java.util.Map<String, Object>> lines = capture.events(EventCode.LUNA_STARTUP);
        assertThat(lines).hasSize(1);
        return (String) LogCapture.attrs(lines.getFirst()).get("lunaState");
    }

    @Test
    void withoutAKeyTheClientIsAbsentAndStartupSaysDisabled() {
        runner("https://api.openai.com/v1").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(RealtimeLunaClient.class).doesNotHaveBean(LunaTransport.class);
            assertThat(context.getBean(LunaStartup.class).enabled()).isFalse();
            assertThat(context.getBean(LunaProperties.class).model()).isEqualTo("gpt-6-luna");
        });
        assertThat(startupState()).isEqualTo("DISABLED_NO_KEY");
    }

    @Test
    void aBlankKeyIsNoKeyAndARealLookingKeyEnablesTheClient() {
        runner("https://api.openai.com/v1").withPropertyValues("OPENAI_API_KEY= ")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(RealtimeLunaClient.class));
        assertThat(startupState()).isEqualTo("DISABLED_NO_KEY");
        capture.clear();

        runner("https://api.openai.com/v1")
                .withPropertyValues("OPENAI_API_KEY=" + RealtimeLunaTestFixtures.FAKE_KEY, "mujoong.luna.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RealtimeLunaClient.class).hasSingleBean(LunaTransport.class);
                    assertThat(context.getBean(LunaStartup.class).mode()).isEqualTo(LunaHosts.Mode.STRICT);
                });
        assertThat(startupState()).isEqualTo("ENABLED");
        assertThat(capture.anyLineContains(RealtimeLunaTestFixtures.FAKE_KEY)).isFalse();
    }

    @Test
    void closingTheContextClosesTheSharedHttpClient() {
        List<LunaTransport> seen = new java.util.ArrayList<>();
        runner("https://api.openai.com/v1")
                .withPropertyValues("OPENAI_API_KEY=" + RealtimeLunaTestFixtures.FAKE_KEY, "mujoong.luna.enabled=true")
                .run(context -> {
                    LunaTransport transport = context.getBean(LunaTransport.class);
                    assertThat(transport.isClosed()).isFalse();
                    seen.add(transport);
                });
        assertThat(seen).singleElement().satisfies(transport -> assertThat(transport.isClosed())
                .as("검토 R-06: 컨텍스트가 닫히면 destroyMethod로 닫힌다").isTrue());
    }

    @Test
    void addressesOutsideTheProfileRuleRefuseStartup() {
        runner("http://127.0.0.1:9/v1").run(context -> assertThat(context).as("프로필 없음 = STRICT").hasFailed());
        runner("https://api.openai.com/v1")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .run(context -> assertThat(context).hasFailed());
        runner("http://127.0.0.1:9/v1")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .run(context -> assertThat(context).hasNotFailed());
        runner("https://evil.invalid/v1")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("demo"))
                .run(context -> assertThat(context).hasFailed());
    }
}
