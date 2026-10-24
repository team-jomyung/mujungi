package kr.mujoong.realtime.judgment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.content.LiveContentRegistry;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.realtime.luna.LunaConfiguration;
import kr.mujoong.realtime.luna.LunaProperties;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.realtime.usage.AiUsageCollector;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 판정 포트 빈 선택 (U11 BR1.6 · U11-c · FT-1023-d): 키와 스위치가 모두 있을 때만 Decisions 구현(끝점을 고르는 설정은 없다), 아니면 보류 구현.
 * 키는 가짜 문자열만 쓴다(R-F3).
 */
@Tag("judgment-turn")
@org.junit.jupiter.api.Tag("service-guardrail")
class JudgmentConfigurationTest {

    private final MutableClock clock = new MutableClock();

    private ApplicationContextRunner runner() {
        RealtimeProperties realtime = mock(RealtimeProperties.class, RETURNS_DEEP_STUBS);
        when(realtime.timing().judgment().liveBudget()).thenReturn(Duration.ofSeconds(4));
        when(realtime.judgment().minConfidence()).thenReturn(0.6);
        when(realtime.judgment().minMargin()).thenReturn(0.15);
        EventLogger events = TestEventLoggers.strict(clock, LogCapture.detached(), RealtimeMetrics.inMemory());
        return new ApplicationContextRunner()
                .withUserConfiguration(LunaConfiguration.class, JudgmentConfiguration.class)
                .withBean(JsonCodec.class, JsonCodec::shared)
                .withBean(MujoongClock.class, () -> clock)
                .withBean(EventLogger.class, () -> events)
                .withBean(AiUsageCollector.class, () -> AiUsageCollector.NOOP)
                .withBean(RealtimeProperties.class, () -> realtime)
                .withBean(LiveContentRegistry.class, () -> new LiveContentRegistry(LiveContentResources.loadDefault()))
                .withPropertyValues("mujoong.luna.model=gpt-6-luna", "mujoong.luna.base-url=https://api.openai.com/v1",
                        "mujoong.luna.max-response-bytes=1000000");
    }

    @Test
    void withoutAKeyTheUnavailableClientIsUsedAndTheAppStarts() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            JudgeClient client = context.getBean(JudgeClient.class);
            assertThat(client).isInstanceOf(UnavailableJudgeClient.class);
            assertThat(client.available()).isFalse();
            assertThat(context.getBean(JudgmentPrompt.class).version()).isEqualTo("prompt-service-v3");
        });
    }

    @Test
    void aKeyWithoutTheSwitchStillMeansNoProvider() {
        runner().withPropertyValues("OPENAI_API_KEY=test-key-not-real-u11")
                .run(context -> assertThat(context.getBean(JudgeClient.class)).isInstanceOf(UnavailableJudgeClient.class));
    }

    @Test
    void aKeyAndTheSwitchTogetherAlwaysSelectTheDecisionsClient() {
        runner().withPropertyValues("OPENAI_API_KEY=test-key-not-real-u11", "mujoong.luna.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            JudgeClient client = context.getBean(JudgeClient.class);
            assertThat(client).isInstanceOf(DecisionsJudgeClient.class);
            assertThat(client.available()).isTrue();
        });
    }

    @Test
    void theRemovedEndpointSwitchNoLongerChangesTheClient() {
        // 2026-10-23 결정: 진행 중 판정은 Decisions 한 길뿐이다. 남은 옛 설정 값이 있어도 다른 구현으로 가지 않고 기동도 막지 않는다
        for (String leftover : new String[] {"responses", "decisions", "chat"}) {
            runner().withPropertyValues("OPENAI_API_KEY=test-key-not-real-u11", "mujoong.luna.enabled=true",
                    "mujoong.luna.judge-endpoint=" + leftover).run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(JudgeClient.class)).as(leftover).isInstanceOf(DecisionsJudgeClient.class);
                    });
        }
        assertThat(java.util.Arrays.stream(LunaProperties.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("model", "baseUrl", "maxResponseBytes", "enabled");
    }

    @Test
    void theSwitchWithoutAKeyStillMeansNoProvider() {
        runner().withPropertyValues("mujoong.luna.enabled=true")
                .run(context -> assertThat(context.getBean(JudgeClient.class)).isInstanceOf(UnavailableJudgeClient.class));
    }
}
