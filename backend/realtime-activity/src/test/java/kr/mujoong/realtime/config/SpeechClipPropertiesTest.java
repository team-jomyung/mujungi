package kr.mujoong.realtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * 3회차 V1: 대본 줄 음성 설정 {@code mujoong.speech-clip.*}(contracts/ai-io.md 10.16). application.yml 기본값(켜짐 · {@code gpt-4o-mini-tts} ·
 * {@code nova} · 운영 주소)으로 묶이고 ai-io.md 10.16 표의 기본값과 같으며, 형식이 틀린 값은 키 이름만 대고 기동을 거부하고, 말투 지시 원문은 문자열
 * 표현에 나오지 않는지 본다. 키 이름의 계약 대조는 {@link ConfigKeyContractTest}가 맡는다.
 */
@Tag("practice-flow")
class SpeechClipPropertiesTest {

    private static final Path REPO = Path.of(System.getProperty("mujoong.repo.root", "..")).toAbsolutePath().normalize();
    private static final Path APPLICATION_YML = REPO.resolve("backend/realtime-activity/src/main/resources/application.yml");
    private static final URI BASE = URI.create("https://api.openai.com/v1");

    @Test
    void theAiIoTableListsEveryKeyWithTheApplicationYmlDefaults() throws IOException {
        String doc = Files.readString(REPO.resolve("contracts/ai-io.md"), StandardCharsets.UTF_8);
        RealtimeSpeechClipProperties clip = ConfigKeyContractTest.bind(APPLICATION_YML).speechClip();

        assertThat(doc).contains(
                "| `mujoong.speech-clip.enabled` | `" + clip.enabled() + "` | realtime |",
                "| `mujoong.speech-clip.base-url` | `" + clip.baseUrl() + "` | realtime |",
                "| `mujoong.speech-clip.model` | `" + clip.model() + "` | realtime |",
                "| `mujoong.speech-clip.voice` | `" + clip.voice() + "` | realtime |",
                "| `mujoong.speech-clip.narrator-style` | 무중 말투 문장 | realtime |",
                "| `mujoong.speech-clip.peer-style` | 친구 말투 문장 | realtime |",
                "| `mujoong.speech-clip.max-concurrency` | `" + clip.maxConcurrency() + "` | realtime |",
                "| `mujoong.speech-clip.max-pending` | `" + clip.maxPending() + "` | realtime |",
                "| `mujoong.speech-clip.cache-max-bytes` | `" + clip.cacheMaxBytes().toMegabytes() + "MB` | realtime |",
                "| `mujoong.speech-clip.session-max-lines` | `" + clip.sessionMaxLines() + "` | realtime |");
    }

    @Test
    void applicationYmlBindsTheProductionDefaults() {
        RealtimeSpeechClipProperties clip = ConfigKeyContractTest.bind(APPLICATION_YML).speechClip();

        assertThat(clip.enabled()).as("운영 기본은 켜짐(키가 없으면 장치가 꺼진다)").isTrue();
        assertThat(clip.baseUrl()).isEqualTo(BASE);
        assertThat(clip.model()).isEqualTo("gpt-4o-mini-tts");
        assertThat(clip.voice()).isEqualTo("nova");
        assertThat(clip.narratorStyle()).isNotBlank();
        assertThat(clip.peerStyle()).isNotBlank();
        assertThat(clip.maxConcurrency()).isEqualTo(4);
        assertThat(clip.maxPending()).isEqualTo(200);
        assertThat(clip.cacheMaxBytes()).isEqualTo(DataSize.ofMegabytes(32));
        assertThat(clip.sessionMaxLines()).isEqualTo(48);
    }

    @Test
    void malformedValuesAreRejectedNamingOnlyTheKey() {
        assertThatThrownBy(() -> props(null, BASE, "gpt-4o-mini-tts", "nova", "", 4))
                .hasMessageContaining("mujoong.speech-clip.enabled");
        assertThatThrownBy(() -> props(true, URI.create("ftp://api.openai.com/v1"), "gpt-4o-mini-tts", "nova", "", 4))
                .hasMessageContaining("mujoong.speech-clip.base-url");
        assertThatThrownBy(() -> props(true, BASE, "gpt 4o; rm", "nova", "", 4)).hasMessageContaining("mujoong.speech-clip.model")
                .hasMessageNotContaining("rm");
        assertThatThrownBy(() -> props(true, BASE, "gpt-4o-mini-tts", "", "", 4)).hasMessageContaining("mujoong.speech-clip.voice");
        assertThatThrownBy(() -> props(true, BASE, "gpt-4o-mini-tts", "nova", "가".repeat(RealtimeSpeechClipProperties.STYLE_MAX + 1), 4))
                .hasMessageContaining("mujoong.speech-clip.narrator-style");
        assertThatThrownBy(() -> props(true, BASE, "gpt-4o-mini-tts", "nova", "", 0))
                .hasMessageContaining("mujoong.speech-clip.max-concurrency");
    }

    @Test
    void theStyleTextNeverShowsInTheStringFormAndNamesAreStripped() {
        RealtimeSpeechClipProperties clip = props(true, BASE, " gpt-4o-mini-tts ", " nova ", "  비밀 아닌 말투 지시  ", 4);

        assertThat(clip.model()).isEqualTo("gpt-4o-mini-tts");
        assertThat(clip.voice()).isEqualTo("nova");
        assertThat(clip.narratorStyle()).isEqualTo("비밀 아닌 말투 지시");
        assertThat(clip.toString()).doesNotContain("말투 지시").contains("gpt-4o-mini-tts", "nova");
    }

    private static RealtimeSpeechClipProperties props(Boolean enabled, URI base, String model, String voice, String style,
            Integer concurrency) {
        return new RealtimeSpeechClipProperties(enabled, base, model, voice, style, "", concurrency, 200, DataSize.ofMegabytes(32),
                48);
    }
}
