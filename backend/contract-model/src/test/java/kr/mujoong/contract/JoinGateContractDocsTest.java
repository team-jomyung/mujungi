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
 * 참여 관문 진단 로그 계약 대조 (R-M10 · 편차 JG). ai-io.md 10.15 표가 {@link EventCode#JOIN_REPLY} · {@link EventCode#JUDGMENT_TURN}의
 * 허용 속성 · 열거값과 같고, JG 편차마다 deviations.md 행 하나와 계약 문서 표시가 있는지 본다.
 */
@Tag("join-gate")
class JoinGateContractDocsTest {

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("mujoong.repo.root", "../..")).toAbsolutePath().normalize();
    private static final String HEADING = "### 10.15 참여 관문 진단 로그";

    @Test
    void theSection1015TableNamesExactlyTheJavaKeysOfBothEvents() throws IOException {
        String table = section(read("contracts/ai-io.md"), HEADING);

        assertThat(table).as("10.15 소절").isNotEmpty();
        for (EventCode code : List.of(EventCode.JOIN_REPLY, EventCode.JUDGMENT_TURN)) {
            assertThat(documentedKeys(table, code)).as(code + " 속성").isEqualTo(code.allowedKeys().stream().map(AttrKey::key)
                    .collect(Collectors.toCollection(TreeSet::new)));
            assertThat(code.isMergeable()).as(code + "는 줄마다 남긴다").isFalse();
            assertThat(code.isSecurityEvent()).isFalse();
            assertThat(code.defaultLevel()).isEqualTo(EventCode.Level.INFO);
        }
    }

    @Test
    void everyRegisteredEnumValueOfTheNewKeysIsDocumented() throws IOException {
        String table = section(read("contracts/ai-io.md"), HEADING);

        for (AttrKey key : List.of(AttrKey.JOIN_REPLY_KIND, AttrKey.JOIN_ACTION, AttrKey.JUDGMENT_POINT, AttrKey.JUDGMENT_OUTCOME,
                AttrKey.ABSTAIN_REASON, AttrKey.CLOSE_REASON, AttrKey.NEXT_ACTION)) {
            assertThat(key.valueType()).isEqualTo(AttrKey.ValueType.ENUM);
            for (String value : key.enumValues()) {
                if (key == AttrKey.JUDGMENT_POINT) {
                    continue;
                }
                assertThat(table).as(key.key() + " 값 " + value).contains("`" + value + "`");
            }
        }
        assertThat(table).contains("`J1` ~ `J6`");
        assertThat(AttrKey.JUDGMENT_POINT.enumValues()).containsExactlyInAnyOrder("J1", "J2", "J3", "J4", "J5", "J6");
        assertThat(AttrKey.FIRST_OPTION.valueType()).isEqualTo(AttrKey.ValueType.LABEL);
        assertThat(AttrKey.MAX_GAP_MS.valueType()).isEqualTo(AttrKey.ValueType.NUMBER);
        assertThat(AttrKey.DEVICE_PLAYING.valueType()).isEqualTo(AttrKey.ValueType.BOOLEAN);
    }

    @Test
    void everyJoinGateDeviationHasOneRowAndAMarkerInTheContracts() throws IOException {
        String deviations = read("contracts/deviations.md");
        String contracts = read("contracts/ai-io.md") + read("contracts/timing.md");
        Matcher row = Pattern.compile("^\\| \\(JG-([a-z])\\) \\|", Pattern.MULTILINE).matcher(deviations);
        List<String> symbols = new ArrayList<>();
        while (row.find()) {
            symbols.add(row.group(1));
        }

        assertThat(symbols).isNotEmpty().doesNotHaveDuplicates();
        for (String symbol : symbols) {
            assertThat(contracts).as("(JG-" + symbol + ") 표시").contains("[JG 편차 (JG-" + symbol + ")]");
        }
        assertThat(deviations).contains("## 참여 관문 안정화 편차");
    }

    private static Set<String> documentedKeys(String table, EventCode code) {
        Set<String> keys = new TreeSet<>();
        for (String line : table.lines().filter(line -> line.startsWith("| `" + code.name() + "`")).toList()) {
            Matcher matcher = Pattern.compile("`([a-zA-Z]+)`").matcher(line.split("\\|")[2]);
            while (matcher.find()) {
                keys.add(matcher.group(1));
            }
        }
        return keys;
    }

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
}
