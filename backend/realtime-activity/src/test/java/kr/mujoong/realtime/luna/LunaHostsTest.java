package kr.mujoong.realtime.luna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Realtime Luna 주소 규칙 · 설정 레코드 · 실행 자원 스키마 복사 (U9 BR1.4 · BR1.5 · SD3 · SD12 · NFR3.4 · Q3-A). */
@Tag("luna")
class LunaHostsTest {

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("mujoong.repo.root", "../..")).toAbsolutePath().normalize();

    @Test
    void strictModeAcceptsOnlyHttpsToTheAllowedHost() {
        assertThat(LunaHosts.check(URI.create("https://api.openai.com/v1"), LunaHosts.Mode.STRICT)).isEmpty();
        assertThat(LunaHosts.check(URI.create("http://api.openai.com/v1"), LunaHosts.Mode.STRICT))
                .contains(LunaHosts.Problem.HOST_NOT_ALLOWED);
        assertThat(LunaHosts.check(URI.create("https://evil.invalid/v1"), LunaHosts.Mode.STRICT))
                .contains(LunaHosts.Problem.HOST_NOT_ALLOWED);
        assertThat(LunaHosts.check(URI.create("http://127.0.0.1:9/v1"), LunaHosts.Mode.STRICT))
                .contains(LunaHosts.Problem.HOST_NOT_ALLOWED);
    }

    @Test
    void devModeAcceptsTheStrictRuleOrLoopbackAndTestModeOnlyLoopback() {
        assertThat(LunaHosts.check(URI.create("https://api.openai.com/v1"), LunaHosts.Mode.DEV)).isEmpty();
        assertThat(LunaHosts.check(URI.create("http://127.0.0.1:8099/v1"), LunaHosts.Mode.DEV)).isEmpty();
        assertThat(LunaHosts.check(URI.create("https://other.invalid/v1"), LunaHosts.Mode.DEV))
                .contains(LunaHosts.Problem.HOST_NOT_ALLOWED);
        assertThat(LunaHosts.check(URI.create("http://localhost:9/v1"), LunaHosts.Mode.TEST)).isEmpty();
        assertThat(LunaHosts.check(URI.create("https://api.openai.com/v1"), LunaHosts.Mode.TEST))
                .as("test 프로필은 실공급자로 가지 않는다(R-F1)").contains(LunaHosts.Problem.NOT_LOOPBACK);
        assertThat(LunaHosts.isLoopback("127.1.2.3")).isTrue();
        assertThat(LunaHosts.isLoopback("127.0.0.256")).isFalse();
    }

    @Test
    void malformedOrCredentialBearingAddressesAreRejectedInEveryMode() {
        for (LunaHosts.Mode mode : LunaHosts.Mode.values()) {
            assertThat(LunaHosts.check(null, mode)).as(mode.name()).contains(LunaHosts.Problem.MISSING);
            assertThat(LunaHosts.check(URI.create("wss://api.openai.com/v1"), mode)).contains(LunaHosts.Problem.INVALID);
            assertThat(LunaHosts.check(URI.create("https://u@api.openai.com/v1"), mode)).contains(LunaHosts.Problem.INVALID);
            assertThat(LunaHosts.check(URI.create("https://api.openai.com/v1?k=v"), mode)).contains(LunaHosts.Problem.INVALID);
        }
    }

    @Test
    void profilesSelectTheModeAndTheStrictestRuleIsTheDefault() {
        assertThat(LunaHosts.modeFor()).isEqualTo(LunaHosts.Mode.STRICT);
        assertThat(LunaHosts.modeFor("prod")).isEqualTo(LunaHosts.Mode.STRICT);
        assertThat(LunaHosts.modeFor("unknown")).isEqualTo(LunaHosts.Mode.STRICT);
        assertThat(LunaHosts.modeFor("dev")).isEqualTo(LunaHosts.Mode.DEV);
        assertThat(LunaHosts.modeFor("test")).isEqualTo(LunaHosts.Mode.TEST);
        assertThat(LunaHosts.modeFor("test", "demo")).isEqualTo(LunaHosts.Mode.STRICT);
    }

    @Test
    void allowedHostConstantEqualsTheHostWrittenInAiIoSection10() throws IOException {
        String aiIo = Files.readString(REPO_ROOT.resolve("contracts/ai-io.md"));
        Matcher line = Pattern.compile("^- 허용 호스트: (.+)$", Pattern.MULTILINE).matcher(aiIo);

        assertThat(line.find()).as("ai-io.md 10.9 허용 호스트 줄").isTrue();
        Set<String> documented = Pattern.compile("`([^`]+)`").matcher(line.group(1)).results()
                .map(match -> match.group(1)).collect(Collectors.toSet());
        assertThat(LunaHosts.ALLOWED_HOSTS).isEqualTo(documented);
    }

    @Test
    void propertiesNormalizeABlankModelAndRejectInvalidValues() {
        URI base = URI.create("https://api.openai.com/v1");

        assertThat(new LunaProperties("", base, 10, false).model()).isEqualTo(LunaProperties.DEFAULT_MODEL);
        assertThat(new LunaProperties(" gpt-6-luna ", base, 10, true).model()).isEqualTo("gpt-6-luna");
        assertThatThrownBy(() -> new LunaProperties("모델", base, 10, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LunaProperties("m", base, -1, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LunaProperties("m", null, 1, false)).isInstanceOf(NullPointerException.class);
        assertThat(LunaPurpose.JUDGMENT_LIVE.requestSchema()).isEqualTo("ai-judgment-request.schema.json");
    }

    @Test
    void runtimeSchemaResourcesAreByteIdenticalToTheContractOriginals() throws IOException {
        List<Path> originals;
        try (Stream<Path> files = Files.list(REPO_ROOT.resolve("contracts/schemas"))) {
            originals = files.filter(file -> file.getFileName().toString().matches("ai-.*\\.schema\\.json|common\\.schema\\.json"))
                    .sorted().toList();
        }
        // 빈 대조로 통과하지 않게, realtime이 실제로 읽는 요청 스키마와 그 $ref 대상이 원본 목록에 있는지 본다
        assertThat(originals).as("contracts/schemas/ai-*.schema.json + common(빈 대조 아님)")
                .extracting(file -> file.getFileName().toString())
                .contains(LunaPurpose.JUDGMENT_LIVE.requestSchema(), "common.schema.json");
        for (Path original : originals) {
            String name = original.getFileName().toString();
            try (InputStream resource = getClass().getClassLoader().getResourceAsStream("luna/schemas/" + name)) {
                assertThat(resource).as("실행 자원 luna/schemas/" + name).isNotNull();
                assertThat(Arrays.equals(resource.readAllBytes(), Files.readAllBytes(original))).as(name).isTrue();
            }
        }
        assertThat(getClass().getClassLoader().getResource("luna/schemas/guardrail-request.schema.json"))
                .as("ai-* · common 밖 스키마는 복사하지 않는다").isNull();
    }
}
