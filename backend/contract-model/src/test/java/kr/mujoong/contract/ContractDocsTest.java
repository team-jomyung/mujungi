package kr.mujoong.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * U9 계약 문서 대조 (R-M10 · SD6 · SD10 · 편차 U9-a ~ U9-c). ai-io.md 10.8 감사 로그 표가 {@link EventCode} 정본과 같고, U9 편차마다
 * deviations.md 행 하나와 ai-io.md 표시가 있으며, 실패 사유 표가 사유 12개를 모두 다루는지 본다.
 */
@Tag("luna")
class ContractDocsTest {

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("mujoong.repo.root", "../..")).toAbsolutePath().normalize();

    private static String read(String file) throws IOException {
        return Files.readString(REPO_ROOT.resolve(file), StandardCharsets.UTF_8);
    }

    private static String section(String markdown, String heading) {
        int start = markdown.indexOf(heading);
        if (start < 0) {
            return "";
        }
        int next = markdown.indexOf("\n### ", start + heading.length());
        int top = markdown.indexOf("\n## ", start + heading.length());
        int end = next < 0 ? top : (top < 0 ? next : Math.min(next, top));
        return end < 0 ? markdown.substring(start) : markdown.substring(start, end);
    }

    @Test
    void theAuditTableInSection108MatchesTheJavaEventCodes() throws IOException {
        String table = section(read("contracts/ai-io.md"), "### 10.8 U9 Luna 호출 감사 로그");
        Set<String> documented = new TreeSet<>();
        for (String line : table.lines().filter(line -> line.startsWith("| `LUNA_CALL`")).toList()) {
            Matcher keys = Pattern.compile("`([a-zA-Z]+)`").matcher(line.split("\\|")[2]);
            while (keys.find()) {
                documented.add(keys.group(1));
            }
        }

        assertThat(documented).isEqualTo(EventCode.LUNA_CALL.allowedKeys().stream().map(AttrKey::key)
                .collect(Collectors.toCollection(TreeSet::new)));
        assertThat(table).contains("| `LUNA_STARTUP` | `lunaState` | ENUM |");
        for (String outcome : AttrKey.LUNA_OUTCOME.enumValues()) {
            assertThat(table).as("lunaOutcome 값").contains("`" + outcome + "`");
        }
        for (String purpose : AttrKey.LUNA_PURPOSE.enumValues()) {
            assertThat(table).as("lunaPurpose 값").contains("`" + purpose + "`");
        }
    }

    @Test
    void everyU9DeviationHasOneRowAndAMarkerInAiIo() throws IOException {
        String deviations = read("contracts/deviations.md");
        String aiIo = read("contracts/ai-io.md");
        Matcher row = Pattern.compile("^\\| \\(U9-([a-z])\\) \\|", Pattern.MULTILINE).matcher(deviations);
        List<String> symbols = new ArrayList<>();
        while (row.find()) {
            symbols.add(row.group(1));
        }

        assertThat(symbols).containsExactly("a", "b", "c");
        for (String symbol : symbols) {
            assertThat(aiIo).as("(U9-" + symbol + ") 표시").contains("[U9 편차 (U9-" + symbol + ")]");
        }
        assertThat(deviations).contains("## U9 편차");
    }

    @Test
    void theFailureTableCoversEveryReasonAndTheRunbookCarriesTheRf15Record() throws IOException {
        String failures = section(read("contracts/ai-io.md"), "### 10.10 U9 호출 방식");
        List<String> reasons = failures.lines().filter(line -> line.matches("^\\| `[A-Z_]+` \\|.*"))
                .map(line -> line.substring(3, line.indexOf('`', 3))).toList();

        assertThat(reasons).containsExactlyInAnyOrderElementsOf(AttrKey.LUNA_OUTCOME.enumValues().stream()
                .filter(value -> !value.equals("OK")).toList());
        String runbook = read("infra/RUNBOOK.md");
        assertThat(runbook).contains("`OPENAI_API_KEY`를 넣기 전 R-F15 확인", "| 확인자 | 확인 날짜(UTC) |", "./gradlew lunaLiveTest",
                "SKIPPED_NO_KEY");
        assertThat(runbook.indexOf("R-F15 확인 — 기록 없이는 넣지 않는다"))
                .as("확인 항목은 OPENAI_API_KEY 입력 명령보다 앞이다")
                .isLessThan(runbook.indexOf("read -r -s -p 'OPENAI_API_KEY: '"));
    }
}
