package kr.mujoong.realtime.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.config.RealtimeSpeechClipProperties;
import kr.mujoong.realtime.config.RealtimeTimingProperties;
import kr.mujoong.realtime.runtime.TimerClocks;
import kr.mujoong.realtime.startup.RealtimeStartupChecks;
import kr.mujoong.testsupport.time.ManualTimerDriver;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

/**
 * 3회차 V1: 대본 줄 음성 빈(contracts/ai-io.md 10.16). 스위치 · 키가 없으면 꺼진 장치(모든 대본 줄이 GPT-Live 원문 읽기)이고, 둘 다 있으면 실제 합성기를
 * 쓰며, 합성 주소가 활성 프로필의 규칙(Luna와 같은 {@code LunaHosts})을 어기면 키 · 스위치와 관계없이 값을 내지 않고 기동을 거부하는지 본다. 키는 가짜
 * 문자열이고 어디로도 보내지 않는다(R-F1 · R-F3).
 */
@Tag("practice-flow")
class SpeechClipConfigurationTest {

    private static final String FAKE_KEY = "fake-openai-key-not-real-v1";
    private static final URI PRODUCTION = URI.create("https://api.openai.com/v1");
    private static final URI LOOPBACK = URI.create("http://127.0.0.1:9/v1");

    private final MutableClock clock = new MutableClock();
    private final TimerClocks clocks = new TimerClocks(new ManualTimerDriver(clock), new ManualTimerDriver(clock));

    @Test
    void theSwitchOffOrAMissingKeyGivesTheDisabledDevice() {
        assertThat(bean(false, PRODUCTION, environment(FAKE_KEY)).state())
                .isEqualTo(SpeechClipSupport.State.DISABLED_SWITCH_OFF);
        assertThat(bean(true, PRODUCTION, environment(null)).state()).isEqualTo(SpeechClipSupport.State.DISABLED_NO_KEY);
        assertThat(bean(true, PRODUCTION, environment(" ")).state()).as("빈 키도 없는 키").isEqualTo(SpeechClipSupport.State.DISABLED_NO_KEY);
    }

    @Test
    void theSwitchOnWithAKeyGivesTheRealSynthesizerWithTheConfiguredLimits() {
        SpeechClipSupport support = bean(true, PRODUCTION, environment(FAKE_KEY));

        assertThat(support.enabled()).isTrue();
        assertThat(support.readyWait()).isEqualTo(Duration.ofSeconds(2));
        assertThat(support.sessionMaxLines()).isEqualTo(48);
        assertThat(support.toString()).doesNotContain(FAKE_KEY);
        support.close();
    }

    @Test
    void anAddressOutsideTheProfileRuleRefusesStartupWithoutPrintingIt() {
        assertThatThrownBy(() -> bean(false, LOOPBACK, environment(null))).as("프로필 없음 = STRICT, 스위치가 꺼져도 확인한다")
                .isInstanceOf(RealtimeStartupChecks.StartupRejectedException.class).hasMessageContaining("mujoong.speech-clip.base-url")
                .hasMessageNotContaining("127.0.0.1");
        MockEnvironment test = environment(FAKE_KEY);
        test.setActiveProfiles("test");
        assertThatThrownBy(() -> bean(true, PRODUCTION, test)).as("test 프로필은 루프백만(실공급자로 가지 않는다)")
                .isInstanceOf(RealtimeStartupChecks.StartupRejectedException.class);
        assertThat(bean(true, LOOPBACK, test).enabled()).isTrue();
        MockEnvironment demo = environment(FAKE_KEY);
        demo.setActiveProfiles("demo");
        assertThatThrownBy(() -> bean(true, URI.create("https://evil.invalid/v1"), demo))
                .isInstanceOf(RealtimeStartupChecks.StartupRejectedException.class);
    }

    private SpeechClipSupport bean(boolean enabled, URI baseUrl, MockEnvironment environment) {
        RealtimeProperties properties = mock(RealtimeProperties.class, RETURNS_DEEP_STUBS);
        when(properties.speechClip()).thenReturn(new RealtimeSpeechClipProperties(enabled, baseUrl, "gpt-4o-mini-tts", "nova",
                "다정한 말투", "밝은 말투", 4, 200, DataSize.ofMegabytes(32), 48));
        when(properties.timing().speechClip()).thenReturn(new RealtimeTimingProperties.SpeechClipTiming(Duration.ofSeconds(10),
                Duration.ofSeconds(2)));
        return new SpeechClipConfiguration().speechClipSupport(properties, environment, clocks, clock, JsonCodec.shared());
    }

    private static MockEnvironment environment(String key) {
        MockEnvironment environment = new MockEnvironment();
        if (key != null) {
            environment.setProperty(OpenAiSpeechClipSynthesizer.API_KEY_ENV, key);
        }
        return environment;
    }
}
