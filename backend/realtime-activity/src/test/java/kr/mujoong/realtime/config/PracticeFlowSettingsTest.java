package kr.mujoong.realtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.mujoong.realtime.practiceflow.PracticeFlowSettings;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 연습 흐름 설정 시험 (U12 FR7.1 · BR9.1 · BR9.2, C안 docs/practice-direct-ask.md 7.1). 설정 키 세 개의 기본값(retry-max 1 · reexplain-max 1 ·
 * flexible-dialogue false)이 application.yml · 설정 레코드 · {@code contracts/ai-io.md} 10.14 표에서 같고, 경로 스위치가 설정 객체까지 그대로
 * 가며, 시간 값은 {@code timing.conversation.*}에서만 읽히고, 음수 · 빠진 값은 키 이름과 함께 거부되는지 본다.
 */
@Tag("practice-flow")
class PracticeFlowSettingsTest {

    private static final Path REPO = Path.of(System.getProperty("mujoong.repo.root", "..")).toAbsolutePath().normalize();
    private static final Path APPLICATION_YML = REPO.resolve("backend/realtime-activity/src/main/resources/application.yml");
    private static final Path AI_IO_MD = REPO.resolve("contracts/ai-io.md");

    @Test
    void applicationYmlBindsTheCountDefaultsTheDirectAskSwitchAndTheTimeKeysOfTheConversationGroup() {
        RealtimeProperties properties = ConfigKeyContractTest.bind(APPLICATION_YML);

        PracticeFlowSettings settings = PracticeFlowSettings.from(properties);
        assertThat(settings.flexibleDialogue()).as("운영 기본은 C안(무중이 직접 묻기)").isFalse();
        assertThat(settings.directAsk()).isTrue();

        assertThat(settings.retryMax()).as("C안은 다시 해 보기를 장면마다 한 번 둔다(설계 3.3)").isEqualTo(1);
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
    void theFlexibleDialogueKeyStillSelectsTheFlexiblePathWhenItIsTurnedOn(@TempDir Path dir) throws IOException {
        Path overlay = dir.resolve("application-flexible.yml");
        Files.writeString(overlay, "mujoong:\n  practice-flow:\n    flexible-dialogue: true\n", StandardCharsets.UTF_8);

        PracticeFlowSettings settings = PracticeFlowSettings.from(ConfigKeyContractTest.bind(APPLICATION_YML, overlay));

        assertThat(settings.flexibleDialogue()).as("유연 대화는 설정 하나로 되돌릴 수 있다(설계 7.1)").isTrue();
        assertThat(settings.directAsk()).isFalse();
        assertThat(settings.retryMax()).as("횟수 키는 경로와 따로 읽힌다").isEqualTo(1);
    }

    @Test
    void theAiIoTableListsThePracticeFlowKeysWithTheSameDefaults() throws IOException {
        String doc = Files.readString(AI_IO_MD, StandardCharsets.UTF_8);

        assertThat(doc).contains("| `mujoong.practice-flow.retry-max` | `1` | realtime |");
        assertThat(doc).contains("| `mujoong.practice-flow.reexplain-max` | `1` | realtime |");
        assertThat(doc).contains("| `mujoong.practice-flow.flexible-dialogue` | `false` | realtime |");
        RealtimePracticeFlowProperties bound = ConfigKeyContractTest.bind(APPLICATION_YML).practiceFlow();
        assertThat(bound).isEqualTo(new RealtimePracticeFlowProperties(1, 1, false));

        // 새 키도 빠짐없이: 문서 10.14 표의 practice-flow 줄 = application.yml의 practice-flow 키 · 값 = 설정 레코드가 읽는 키
        Map<String, String> documented = new TreeMap<>();
        Matcher row = Pattern.compile("^\\| `(mujoong\\.practice-flow\\.[a-z-]+)` \\| `([^`]*)` \\| realtime \\|", Pattern.MULTILINE)
                .matcher(doc);
        while (row.find()) {
            documented.put(row.group(1), row.group(2));
        }
        Map<String, String> yml = new TreeMap<>();
        ConfigKeyContractTest.flatten(APPLICATION_YML).forEach((key, value) -> {
            if (key.startsWith("mujoong.practice-flow.")) {
                yml.put(key, String.valueOf(value));
            }
        });
        assertThat(documented).as("빈 대조로 통과하지 않음").hasSize(3);
        assertThat(yml).as("application.yml 기본값 = ai-io.md 10.14 표").isEqualTo(documented);
        assertThat(ConfigKeyContractTest.recordLeaves(RealtimePracticeFlowProperties.class, "mujoong.practice-flow").keySet())
                .as("설정 레코드가 읽는 키 = 표의 키").isEqualTo(documented.keySet());
    }

    @Test
    void negativeOrMissingCountsAreRejectedWithTheKeyName() {
        assertThatThrownBy(() -> new RealtimePracticeFlowProperties(-1, 1, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mujoong.practice-flow.retry-max");
        assertThatThrownBy(() -> new RealtimePracticeFlowProperties(1, -1, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mujoong.practice-flow.reexplain-max");
        assertThatThrownBy(() -> new RealtimePracticeFlowProperties(null, 1, false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new RealtimePracticeFlowProperties(0, 0, true)).as("0은 하지 않음이라 허용한다")
                .isEqualTo(new RealtimePracticeFlowProperties(0, 0, true));
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
        assertThat(new PracticeFlowSettings(ten, ten, ten, ten, 2, 1).flexibleDialogue())
                .as("경로를 고르지 않는 6인자 생성자는 지금처럼 유연 대화다").isTrue();
    }

    @Test
    void thePracticeFlowRootIsRequiredByTheMujoongTree() {
        assertThatThrownBy(() -> new RealtimeProperties(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
