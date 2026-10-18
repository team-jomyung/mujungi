package kr.mujoong.realtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import kr.mujoong.realtime.config.RealtimeProperties.PracticeFlow;
import kr.mujoong.realtime.practiceflow.PracticeFlowSettings;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 새 연습 흐름 설정 시험 (U12 FR7.1 · BR9.1 · BR9.2). 횟수 키 두 개의 기본값(2 · 1)이 application.yml · 설정 레코드 · {@code contracts/ai-io.md}
 * 10.14 표에서 같고, 시간 값은 {@code timing.conversation.*}에서만 읽히며, 음수 · 빠진 값은 키 이름과 함께 거부되는지 본다.
 */
@Tag("u12")
class PracticeFlowSettingsTest {

    private static final Path REPO = Path.of(System.getProperty("mujoong.repo.root", "..")).toAbsolutePath().normalize();
    private static final Path APPLICATION_YML = REPO.resolve("backend/realtime-activity/src/main/resources/application.yml");
    private static final Path AI_IO_MD = REPO.resolve("contracts/ai-io.md");

    @Test
    void applicationYmlBindsTheCountDefaultsAndTheTimeKeysOfTheConversationGroup() {
        RealtimeProperties properties = ConfigKeyContractTest.bind(APPLICATION_YML);

        PracticeFlowSettings settings = PracticeFlowSettings.from(properties);

        assertThat(settings.retryMax()).isEqualTo(2);
        assertThat(settings.reexplainMax()).isEqualTo(1);
        assertThat(settings.noResponseWait()).isEqualTo(properties.timing().conversation().noResponseWait());
        assertThat(settings.focusMax()).isEqualTo(properties.timing().conversation().focusMax());
        assertThat(settings.goalActiveLimit()).isEqualTo(properties.timing().conversation().goalActiveLimit());
        assertThat(settings.slotGuard()).as("슬롯 안전 시계는 실행부 재생 만료와 같은 키").isEqualTo(properties.timing().device().outputQueueMax());
        assertThat(settings.noResponseWait()).as("timing.md 13절 기본값").isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.focusMax()).isEqualTo(Duration.ofSeconds(90));
        assertThat(settings.goalActiveLimit()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void theAiIoTableListsBothCountKeysWithTheSameDefaults() throws IOException {
        String doc = Files.readString(AI_IO_MD, StandardCharsets.UTF_8);

        assertThat(doc).contains("| `mujoong.practice-flow.retry-max` | `2` | realtime |");
        assertThat(doc).contains("| `mujoong.practice-flow.reexplain-max` | `1` | realtime |");
        PracticeFlow bound = ConfigKeyContractTest.bind(APPLICATION_YML).practiceFlow();
        assertThat(bound).isEqualTo(new PracticeFlow(2, 1));
    }

    @Test
    void negativeOrMissingCountsAreRejectedWithTheKeyName() {
        assertThatThrownBy(() -> new PracticeFlow(-1, 1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mujoong.practice-flow.retry-max");
        assertThatThrownBy(() -> new PracticeFlow(2, -1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mujoong.practice-flow.reexplain-max");
        assertThatThrownBy(() -> new PracticeFlow(null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new PracticeFlow(0, 0)).as("0은 하지 않음이라 허용한다").isEqualTo(new PracticeFlow(0, 0));
    }

    @Test
    void theSettingsObjectRejectsNonPositiveTimesAndNegativeCounts() {
        Duration ten = Duration.ofSeconds(10);
        assertThatThrownBy(() -> new PracticeFlowSettings(Duration.ZERO, ten, ten, ten, 2, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mujoong.timing.conversation.no-response-wait");
        assertThatThrownBy(() -> new PracticeFlowSettings(ten, ten.negated(), ten, ten, 2, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("focus-max");
        assertThatThrownBy(() -> new PracticeFlowSettings(ten, ten, null, ten, 2, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("goal-active-limit");
        assertThatThrownBy(() -> new PracticeFlowSettings(ten, ten, ten, Duration.ZERO, 2, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("output-queue-max");
        assertThatThrownBy(() -> new PracticeFlowSettings(ten, ten, ten, ten, -1, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retry-max");
        assertThatThrownBy(() -> new PracticeFlowSettings(ten, ten, ten, ten, 2, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reexplain-max");
    }

    @Test
    void thePracticeFlowRootIsRequiredByTheMujoongTree() {
        assertThatThrownBy(() -> new RealtimeProperties(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
