package kr.mujoong.realtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import kr.mujoong.realtime.config.RealtimeProperties.ConversationTiming;
import kr.mujoong.realtime.config.RealtimeProperties.GuardrailTiming;
import kr.mujoong.realtime.config.RealtimeProperties.JudgmentTiming;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * U8 시간 키 바인딩(timing.md 13절 · BR3.1 · functional-spec 8.2). application.yml의 새 키 여섯이 {@link RealtimeProperties.Timing}의
 * 판정 · 가드레일 · 새 연습 흐름 묶음에 계약 기본값으로 묶이고, 0 · 빈 값은 기동을 거부하는지 본다. 키 이름 · 기본값의 timing.md 대조는
 * {@link ConfigKeyContractTest}가 계속 맡는다.
 */
@Tag("u8")
class U8TimingPropertiesTest {

    private static final Path APPLICATION_YML = Path.of(System.getProperty("mujoong.repo.root", ".."))
            .toAbsolutePath().normalize().resolve("backend/realtime-activity/src/main/resources/application.yml");

    @Test
    void applicationYmlBindsTheSixU8RealtimeTimingDefaults() {
        RealtimeProperties.Timing timing = ConfigKeyContractTest.bind(APPLICATION_YML).timing();

        assertThat(timing.judgment().liveBudget()).isEqualTo(Duration.parse("PT2S"));
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
        RealtimeProperties.Timing bound = ConfigKeyContractTest.bind(APPLICATION_YML).timing();

        assertThatThrownBy(() -> new RealtimeProperties.Timing(bound.session(), bound.device(), bound.recorder(),
                null, bound.guardrail(), bound.conversation(), bound.provider()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mujoong.timing.judgment");
        assertThatThrownBy(() -> new RealtimeProperties.Timing(bound.session(), bound.device(), bound.recorder(),
                bound.judgment(), bound.guardrail(), null, bound.provider()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mujoong.timing.conversation");
        assertThatThrownBy(() -> new RealtimeProperties.Timing(bound.session(), bound.device(), bound.recorder(),
                bound.judgment(), bound.guardrail(), bound.conversation(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mujoong.timing.provider");
    }
}
