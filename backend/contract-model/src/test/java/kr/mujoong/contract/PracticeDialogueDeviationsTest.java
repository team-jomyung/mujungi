package kr.mujoong.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 연습 대화 기준 3회차 · 4회차 편차 대조 (R-M10, 편차 (PDS-V1) · (PDS-V2) · (PDS-V3)). deviations.md의 PDS 절에 기호마다 행이 하나씩 있고, ai-io.md에 Markdown 표시
 * {@code [PDS 편차 (PDS-Vn)]}가, timing.md에 같은 편차를 가리키는 문장이 있는지 본다. {@code ContractFilesTest}는 한 · 두 글자 기호만 단언하므로
 * 이 기호는 여기서 따로 맞춘다.
 */
@Tag("practice-flow")
class PracticeDialogueDeviationsTest {

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("mujoong.repo.root", "../..")).toAbsolutePath().normalize();

    private static String read(String file) throws IOException {
        return Files.readString(REPO_ROOT.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    void everyPdsDeviationHasOneRowAndItsMarkersInTheContractFiles() throws IOException {
        String deviations = read("contracts/deviations.md");
        String aiIo = read("contracts/ai-io.md");
        String timing = read("contracts/timing.md");
        Matcher row = Pattern.compile("^\\| \\(PDS-V(\\d+)\\) \\|", Pattern.MULTILINE).matcher(deviations);
        List<String> symbols = new ArrayList<>();
        while (row.find()) {
            symbols.add(row.group(1));
        }

        assertThat(deviations).contains("## 연습 대화 기준 3회차 편차 (PDS)");
        assertThat(symbols).as("기호마다 행 하나").containsExactly("1", "2", "3");
        for (String symbol : symbols) {
            assertThat(aiIo).as("(PDS-V" + symbol + ") ai-io.md 표시").contains("[PDS 편차 (PDS-V" + symbol + ")]");
            assertThat(timing).as("(PDS-V" + symbol + ") timing.md 문장").contains("편차 (PDS-V" + symbol + ")");
        }
    }

    @Test
    void theV1RowStatesTheVoiceDifferenceAndTheSpecBasis() throws IOException {
        String v1 = read("contracts/deviations.md").lines().filter(line -> line.startsWith("| (PDS-V1) |")).findFirst()
                .orElseThrow();

        assertThat(v1).contains("GPT-Live 목소리와 다를 수 있다", "AI 동작 명세 v0.2 §4.2", "기술명세서 v0.3 §3.4", "R-F15");
        assertThat(read("contracts/ai-io.md")).contains("### 10.16 3회차 V1 연습 대본 줄 음성(미리 합성) [PDS 편차 (PDS-V1)]");
    }

    @Test
    void theV3RowStatesTheDeferralKeyAndTheOneResend() throws IOException {
        String v3 = read("contracts/deviations.md").lines().filter(line -> line.startsWith("| (PDS-V3) |")).findFirst()
                .orElseThrow();

        assertThat(v3).contains("mujoong.timing.provider.instruction-defer-max", "한 번 다시 보낸다", "설계 개정 A2");
        assertThat(read("contracts/timing.md")).contains("| `mujoong.timing.provider.instruction-defer-max` | `3s` | realtime |");
    }
}
