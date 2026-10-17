package kr.mujoong.realtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.mujoong.realtime.config.RealtimeProperties.Judgment;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 판정 설정과 4초 시한 시험 (U11 FR1.3 · FR1.4 · NFR1 · BR1.3 · BR1.4, 편차 (U11-b)). 기본값 0.6 · 0.15, 0 ~ 1 밖은 키 이름과 함께 기동 거부,
 * 시한 키 {@code mujoong.timing.judgment.live-budget} = 4s가 application.yml · 설정 레코드 바인딩 · timing.md 13절 세 곳에서 같다(R-M5).
 */
@Tag("u11")
class JudgmentSettingsTest {

    private static final Path REPO = Path.of(System.getProperty("mujoong.repo.root", "..")).toAbsolutePath().normalize();
    private static final Path APPLICATION_YML = REPO.resolve("backend/realtime-activity/src/main/resources/application.yml");
    private static final Path TIMING_MD = REPO.resolve("contracts/timing.md");

    @Test
    void applicationYmlBindsTheJudgmentDefaults() {
        RealtimeProperties properties = ConfigKeyContractTest.bind(APPLICATION_YML);

        assertThat(properties.judgment().minConfidence()).isEqualTo(0.6);
        assertThat(properties.judgment().minMargin()).isEqualTo(0.15);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.01, 1.01, Double.NaN, 2.0})
    void valuesOutsideZeroToOneAreRejectedWithTheKeyName(double value) {
        assertThatThrownBy(() -> new Judgment(value, 0.15)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mujoong.judgment.min-confidence");
        assertThatThrownBy(() -> new Judgment(0.6, value)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mujoong.judgment.min-margin");
    }

    @Test
    void theBoundariesZeroAndOneAreAllowedAndAMissingValueIsRejected() {
        assertThat(new Judgment(0.0, 1.0)).isEqualTo(new Judgment(0.0, 1.0));
        assertThatThrownBy(() -> new Judgment(null, 0.15)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mujoong.judgment.min-confidence");
        assertThatThrownBy(() -> new Judgment(0.6, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mujoong.judgment.min-margin");
    }

    @Test
    void liveBudgetIsFourSecondsInTheYmlTheSettingsRecordAndTimingMd() throws IOException {
        RealtimeProperties properties = ConfigKeyContractTest.bind(APPLICATION_YML);
        String timing = Files.readString(TIMING_MD, StandardCharsets.UTF_8);
        Matcher row = Pattern.compile("\\| `mujoong\\.timing\\.judgment\\.live-budget` \\| `([^`]+)` \\|").matcher(timing);

        assertThat(properties.timing().judgment().liveBudget()).isEqualTo(Duration.ofSeconds(4));
        assertThat(row.find()).isTrue();
        assertThat(row.group(1)).isEqualTo("4s");
        assertThat(properties.timing().judgment().responseReady()).as("응답 준비 시계는 그대로").isEqualTo(Duration.ofSeconds(10));
        assertThat(Files.readString(REPO.resolve("contracts/deviations.md"), StandardCharsets.UTF_8))
                .contains("| (U11-b) |", "2s → 4s");
    }

    @Test
    void theJudgmentRootIsRequired() {
        assertThatThrownBy(() -> new RealtimeProperties(null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
