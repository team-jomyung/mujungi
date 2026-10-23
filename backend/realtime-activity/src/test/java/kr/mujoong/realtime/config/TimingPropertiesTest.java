package kr.mujoong.realtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import kr.mujoong.realtime.config.RealtimeTimingProperties.ConversationTiming;
import kr.mujoong.realtime.config.RealtimeTimingProperties.GuardrailTiming;
import kr.mujoong.realtime.config.RealtimeTimingProperties.JudgmentTiming;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * U8 시간 키 바인딩(timing.md 13절 · BR3.1 · functional-spec 8.2). application.yml의 새 키 여섯이 {@link RealtimeTimingProperties}의
 * 판정 · 가드레일 · 새 연습 흐름 묶음에 계약 기본값으로 묶이고, 0 · 빈 값은 기동을 거부하는지 본다. 키 이름 · 기본값의 timing.md 대조는
 * {@link ConfigKeyContractTest}가 계속 맡는다.
 */
@Tag("live-content")
class TimingPropertiesTest {

    private static final Path APPLICATION_YML = Path.of(System.getProperty("mujoong.repo.root", ".."))
            .toAbsolutePath().normalize().resolve("backend/realtime-activity/src/main/resources/application.yml");

    @Test
    void applicationYmlBindsTheSixU8RealtimeTimingDefaults() {
        RealtimeTimingProperties timing =ConfigKeyContractTest.bind(APPLICATION_YML).timing();

        assertThat(timing.judgment().liveBudget()).isEqualTo(Duration.parse("PT4S"));
        assertThat(timing.judgment().responseReady()).isEqualTo(Duration.parse("PT10S"));
        assertThat(timing.guardrail().jevBudget()).isEqualTo(Duration.parse("PT0.8S"));
        assertThat(timing.conversation().noResponseWait()).isEqualTo(Duration.parse("PT10S"));
        assertThat(timing.conversation().focusMax()).isEqualTo(Duration.parse("PT1M30S"));
        assertThat(timing.conversation().goalActiveLimit()).isEqualTo(Duration.parse("PT10M"));
    }

    @Test
    void zeroOrMissingU8TimingValuesAreRejectedWithTheKeyName() {
        Duration one = Duration.parse("PT1S");

        assertThatThrownBy(() -> new JudgmentTiming(Duration.ZERO, one))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mujoong.timing.judgment.live-budget");
        assertThatThrownBy(() -> new JudgmentTiming(one, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mujoong.timing.judgment.response-ready");
        assertThatThrownBy(() -> new GuardrailTiming(one.negated()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mujoong.timing.guardrail.jev-budget");
        assertThatThrownBy(() -> new ConversationTiming(one, one, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mujoong.timing.conversation.goal-active-limit");
    }

    @Test
    void timingTreeRequiresTheThreeU8Groups() {
        RealtimeTimingProperties bound =ConfigKeyContractTest.bind(APPLICATION_YML).timing();

        assertThatThrownBy(() -> new RealtimeTimingProperties(bound.session(), bound.device(), bound.recorder(),
                null, bound.guardrail(), bound.conversation(), bound.provider(), bound.speechClip()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mujoong.timing.judgment");
        assertThatThrownBy(() -> new RealtimeTimingProperties(bound.session(), bound.device(), bound.recorder(),
                bound.judgment(), bound.guardrail(), null, bound.provider(), bound.speechClip()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mujoong.timing.conversation");
        assertThatThrownBy(() -> new RealtimeTimingProperties(bound.session(), bound.device(), bound.recorder(),
                bound.judgment(), bound.guardrail(), bound.conversation(), null, bound.speechClip()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mujoong.timing.provider");
        // 3회차 V1: 대본 줄 음성 시간 묶음도 빠지면 기동을 거부한다
        assertThatThrownBy(() -> new RealtimeTimingProperties(bound.session(), bound.device(), bound.recorder(),
                bound.judgment(), bound.guardrail(), bound.conversation(), bound.provider(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mujoong.timing.speech-clip");
    }

    @Test
    void applicationYmlBindsTheSpeechClipTimingDefaultsAndRejectsZero() {
        RealtimeTimingProperties.SpeechClipTiming clip = ConfigKeyContractTest.bind(APPLICATION_YML).timing().speechClip();

        assertThat(clip.synthesisTimeout()).isEqualTo(Duration.parse("PT10S"));
        assertThat(clip.readyWait()).isEqualTo(Duration.parse("PT2S"));
        Duration one = Duration.parse("PT1S");
        assertThatThrownBy(() -> new RealtimeTimingProperties.SpeechClipTiming(Duration.ZERO, one))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mujoong.timing.speech-clip.synthesis-timeout");
        assertThatThrownBy(() -> new RealtimeTimingProperties.SpeechClipTiming(one, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mujoong.timing.speech-clip.ready-wait");
    }
}
