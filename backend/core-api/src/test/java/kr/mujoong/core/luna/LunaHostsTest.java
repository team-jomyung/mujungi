package kr.mujoong.core.luna;

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

/** Core Luna 주소 규칙 · 설정 레코드 · 실행 자원 스키마 복사 (U9 BR1.4 · BR1.5 · SD3 · SD12 · NFR3.4 · Q3-A). */
@Tag("u9")
class LunaHostsTest {

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("mujoong.repo.root", "../..")).toAbsolutePath().normalize();

    @Test
    void strictModeAcceptsOnlyHttpsToTheAllowedHost() {
        assertThat(LunaHosts.check(URI.create("https://api.openai.com/v1"), LunaHosts.Mode.STRICT)).isEmpty();
        assertThat(LunaHosts.check(URI.create("https://API.OPENAI.COM/v1"), LunaHosts.Mode.STRICT)).isEmpty();
        assertThat(LunaHosts.check(URI.create("http://api.openai.com/v1"), LunaHosts.Mode.STRICT))
                .contains(LunaHosts.Problem.HOST_NOT_ALLOWED);
        assertThat(LunaHosts.check(URI.create("https://api.openai.com.evil.invalid/v1"), LunaHosts.Mode.STRICT))
                .contains(LunaHosts.Problem.HOST_NOT_ALLOWED);
        assertThat(LunaHosts.check(URI.create("http://127.0.0.1:9/v1"), LunaHosts.Mode.STRICT))
                .contains(LunaHosts.Problem.HOST_NOT_ALLOWED);
    }

    @Test
    void devModeAcceptsTheStrictRuleOrLoopbackAndTestModeOnlyLoopback() {
        assertThat(LunaHosts.check(URI.create("https://api.openai.com/v1"), LunaHosts.Mode.DEV)).isEmpty();
        assertThat(LunaHosts.check(URI.create("http://localhost:8099/v1"), LunaHosts.Mode.DEV)).isEmpty();
        assertThat(LunaHosts.check(URI.create("http://[::1]:8099/v1"), LunaHosts.Mode.DEV)).isEmpty();
        assertThat(LunaHosts.check(URI.create("https://other.invalid/v1"), LunaHosts.Mode.DEV))
                .contains(LunaHosts.Problem.HOST_NOT_ALLOWED);

        assertThat(LunaHosts.check(URI.create("http://127.0.0.1:9/v1"), LunaHosts.Mode.TEST)).isEmpty();
        assertThat(LunaHosts.check(URI.create("https://api.openai.com/v1"), LunaHosts.Mode.TEST))
                .as("test 프로필은 실공급자로 가지 않는다(R-F1)").contains(LunaHosts.Problem.NOT_LOOPBACK);
        assertThat(LunaHosts.check(URI.create("http://128.0.0.1/v1"), LunaHosts.Mode.TEST))
                .contains(LunaHosts.Problem.NOT_LOOPBACK);
        assertThat(LunaHosts.check(URI.create("http://127.0.0.256/v1"), LunaHosts.Mode.TEST)).as("주소가 아닌 호스트").isPresent();
        assertThat(LunaHosts.isLoopback("127.0.0.256")).isFalse();
        assertThat(LunaHosts.isLoopback("127.0.0")).isFalse();
    }

    @Test
    void malformedOrCredentialBearingAddressesAreRejectedInEveryMode() throws Exception {
        // 사용자 정보가 든 주소는 부품으로 조립한다. 문자열 그대로 쓰면 verify.sh 비밀 형식 검사(A4)에 걸린다.
        URI withUserInfo = new URI("https", "user:pw", "api.openai.com", -1, "/v1", null, null);
        for (LunaHosts.Mode mode : LunaHosts.Mode.values()) {
            assertThat(LunaHosts.check(null, mode)).as(mode.name()).contains(LunaHosts.Problem.MISSING);
            assertThat(LunaHosts.check(URI.create("wss://api.openai.com/v1"), mode)).contains(LunaHosts.Problem.INVALID);
            assertThat(LunaHosts.check(withUserInfo, mode))
                    .contains(LunaHosts.Problem.INVALID);
            assertThat(LunaHosts.check(URI.create("https://api.openai.com/v1?x=1"), mode)).contains(LunaHosts.Problem.INVALID);
            assertThat(LunaHosts.check(URI.create("https://api.openai.com/v1#f"), mode)).contains(LunaHosts.Problem.INVALID);
            assertThat(LunaHosts.check(URI.create("v1/responses"), mode)).contains(LunaHosts.Problem.INVALID);
        }
    }

    @Test
    void profilesSelectTheModeAndTheStrictestRuleIsTheDefault() {
        assertThat(LunaHosts.modeFor()).isEqualTo(LunaHosts.Mode.STRICT);
        assertThat(LunaHosts.modeFor("demo")).isEqualTo(LunaHosts.Mode.STRICT);
        assertThat(LunaHosts.modeFor("prod")).isEqualTo(LunaHosts.Mode.STRICT);
        assertThat(LunaHosts.modeFor("load-test")).isEqualTo(LunaHosts.Mode.STRICT);
        assertThat(LunaHosts.modeFor("demo", "dev")).isEqualTo(LunaHosts.Mode.STRICT);
        assertThat(LunaHosts.modeFor("dev")).isEqualTo(LunaHosts.Mode.DEV);
        assertThat(LunaHosts.modeFor("test")).isEqualTo(LunaHosts.Mode.TEST);
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
        LunaProperties.PurposeOptions options = new LunaProperties.PurposeOptions(4000, "medium");
        LunaProperties.Purposes purposes = new LunaProperties.Purposes(options, options, options, options, options, options);
        URI base = URI.create("https://api.openai.com/v1");

        assertThat(new LunaProperties(" ", base, 1, purposes).model()).isEqualTo(LunaProperties.DEFAULT_MODEL);
        assertThat(new LunaProperties(null, base, 1, purposes).model()).isEqualTo("gpt-6-luna");
        assertThat(new LunaProperties("gpt-6-luna-mini", base, 1, purposes).options(LunaPurpose.REJUDGMENT)).isSameAs(options);
        assertThatThrownBy(() -> new LunaProperties("bad model", base, 1, purposes)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LunaProperties("m", null, 1, purposes)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LunaProperties("m", base, 0, purposes)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LunaProperties.PurposeOptions(0, "low")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LunaProperties.PurposeOptions(1, "extreme")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LunaProperties.Purposes(options, options, options, options, options, null))
                .isInstanceOf(NullPointerException.class);
        for (LunaPurpose purpose : LunaPurpose.values()) {
            assertThat(purposes.of(purpose)).as(purpose.name()).isSameAs(options);
        }
    }

    @Test
    void runtimeSchemaResourcesAreByteIdenticalToTheContractOriginals() throws IOException {
        List<Path> originals;
        try (Stream<Path> files = Files.list(REPO_ROOT.resolve("contracts/schemas"))) {
            originals = files.filter(file -> file.getFileName().toString().matches("ai-.*\\.schema\\.json|common\\.schema\\.json"))
                    .sorted().toList();
        }
        assertThat(originals).as("contracts/schemas/ai-*.schema.json + common(빈 대조 아님)").hasSizeGreaterThanOrEqualTo(8);
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
