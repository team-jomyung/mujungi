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

/**
 * realtime Luna 켜짐 조건 = 키 있음 AND 스위치 {@code mujoong.luna.enabled} (U11 BR12.3 · 계약 편차 U11-c). 키 × 스위치 네 경우의
 * {@code lunaState}와 빈 등록을 본다. 키는 가짜 문자열만 쓴다(R-F3).
 */
@Tag("u11")
class LunaActivationTest {

    private final MutableClock clock = new MutableClock();
    private final LogCapture capture = LogCapture.detached();
    private final EventLogger events = TestEventLoggers.strict(clock, capture, RealtimeMetrics.inMemory());

    private ApplicationContextRunner runner(boolean key, Boolean switchValue) {
        RealtimeProperties realtime = mock(RealtimeProperties.class, RETURNS_DEEP_STUBS);
        when(realtime.timing().judgment().liveBudget()).thenReturn(Duration.ofSeconds(4));
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(LunaConfiguration.class)
                .withBean(JsonCodec.class, JsonCodec::shared)
                .withBean(MujoongClock.class, () -> clock)
                .withBean(EventLogger.class, () -> events)
                .withBean(RealtimeProperties.class, () -> realtime)
                .withPropertyValues("mujoong.luna.model=gpt-6-luna", "mujoong.luna.base-url=https://api.openai.com/v1",
                        "mujoong.luna.max-response-bytes=1000000", "mujoong.luna.purpose.judgment-live.max-output-tokens=4000",
                        "mujoong.luna.purpose.judgment-live.reasoning-effort=medium");
        if (key) {
            runner = runner.withPropertyValues("OPENAI_API_KEY=" + RealtimeLunaTestFixtures.FAKE_KEY);
        }
        if (switchValue != null) {
            runner = runner.withPropertyValues("mujoong.luna.enabled=" + switchValue);
        }
        return runner;
    }

    private String startupState() {
        List<java.util.Map<String, Object>> lines = capture.events(EventCode.LUNA_STARTUP);
        assertThat(lines).hasSize(1);
        return (String) LogCapture.attrs(lines.getFirst()).get("lunaState");
    }

    @Test
    void keyAndSwitchOnEnableTheClient() {
        runner(true, true).run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(RealtimeLunaClient.class).hasSingleBean(LunaTransport.class));
        assertThat(startupState()).isEqualTo("ENABLED");
    }

    @Test
    void keyWithTheSwitchOffOrUnsetStaysDisabledAndSaysSwitchOff() {
        runner(true, false).run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(RealtimeLunaClient.class).doesNotHaveBean(LunaTransport.class));
        assertThat(startupState()).isEqualTo("DISABLED_SWITCH_OFF");
        capture.clear();

        runner(true, null).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(RealtimeLunaClient.class);
            assertThat(context.getBean(LunaProperties.class).enabled()).as("스위치 기본값은 꺼짐").isFalse();
        });
        assertThat(startupState()).isEqualTo("DISABLED_SWITCH_OFF");
    }

    @Test
    void noKeyStaysDisabledWhateverTheSwitchSays() {
        runner(false, true).run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(RealtimeLunaClient.class).doesNotHaveBean(LunaTransport.class));
        assertThat(startupState()).isEqualTo("DISABLED_NO_KEY");
        capture.clear();

        runner(false, false).run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(RealtimeLunaClient.class));
        assertThat(startupState()).isEqualTo("DISABLED_NO_KEY");
    }

    @Test
    void theStartupLineNeverCarriesTheKey() {
        runner(true, true).run(context -> assertThat(context).hasNotFailed());
        assertThat(capture.anyLineContains(RealtimeLunaTestFixtures.FAKE_KEY)).isFalse();
    }
}
