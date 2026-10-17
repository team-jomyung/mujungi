package kr.mujoong.realtime.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 제브 주소 · 모델 규칙 시험 (U13 BR2.2 · BR2.3 · Q4 답 A). 프로필별 엄격도, 사용자 정보 · 질의 · 조각이 붙은 주소 거부, 루프백 판단, 허용 호스트 상수가
 * {@code contracts/ai-io.md} 10.11에 적힌 목록과 같음, 고정 모델 이름 규칙을 본다. 실제 공급자는 부르지 않는다(R-F1).
 */
@Tag("u13")
class JevHostsTest {

    private static final Path REPO_ROOT = Path.of(System.getProperty("mujoong.repo.root", "..")).toAbsolutePath().normalize();
    private static final String ALLOWED = "https://" + JevHosts.ALLOWED_HOSTS.iterator().next() + "/v1/check";

    @Test
    void profilesSelectTheModeAndTheStrictestRuleIsTheDefault() {
        assertThat(JevHosts.modeFor()).isEqualTo(JevHosts.Mode.STRICT);
        assertThat(JevHosts.modeFor("prod")).isEqualTo(JevHosts.Mode.STRICT);
        assertThat(JevHosts.modeFor("unknown")).isEqualTo(JevHosts.Mode.STRICT);
        assertThat(JevHosts.modeFor("dev")).isEqualTo(JevHosts.Mode.DEV);
        assertThat(JevHosts.modeFor("test")).isEqualTo(JevHosts.Mode.TEST);
        assertThat(JevHosts.modeFor("test", "demo")).isEqualTo(JevHosts.Mode.STRICT);
    }

    @Test
    void strictAcceptsOnlyHttpsOnAnAllowedHostAndDevAlsoAcceptsLoopbackAndTestOnlyLoopback() {
        assertThat(JevHosts.check(ALLOWED, JevHosts.Mode.STRICT)).isEmpty();
        assertThat(JevHosts.check(ALLOWED.toUpperCase().replace("HTTPS", "https"), JevHosts.Mode.STRICT))
                .as("호스트 대소문자는 가리지 않는다").isEmpty();
        assertThat(JevHosts.check(ALLOWED.replace("https://", "http://"), JevHosts.Mode.STRICT))
                .contains(JevHosts.Problem.HOST_NOT_ALLOWED);
        assertThat(JevHosts.check("https://example.invalid/x", JevHosts.Mode.STRICT)).contains(JevHosts.Problem.HOST_NOT_ALLOWED);
        assertThat(JevHosts.check("http://127.0.0.1:9/x", JevHosts.Mode.STRICT)).contains(JevHosts.Problem.HOST_NOT_ALLOWED);

        assertThat(JevHosts.check("http://127.0.0.1:9/x", JevHosts.Mode.DEV)).isEmpty();
        assertThat(JevHosts.check("http://localhost:9/x", JevHosts.Mode.DEV)).isEmpty();
        assertThat(JevHosts.check("http://[::1]:9/x", JevHosts.Mode.DEV)).isEmpty();
        assertThat(JevHosts.check(ALLOWED, JevHosts.Mode.DEV)).isEmpty();
        assertThat(JevHosts.check("https://example.invalid/x", JevHosts.Mode.DEV)).contains(JevHosts.Problem.HOST_NOT_ALLOWED);

        assertThat(JevHosts.check("http://127.0.0.1:9/x", JevHosts.Mode.TEST)).isEmpty();
        assertThat(JevHosts.check(ALLOWED, JevHosts.Mode.TEST)).as("test에서 실공급자 호스트는 거부(R-F1)")
                .contains(JevHosts.Problem.NOT_LOOPBACK);
    }

    @Test
    void emptyMalformedOrDecoratedAddressesAreRejectedInEveryMode() {
        for (JevHosts.Mode mode : JevHosts.Mode.values()) {
            assertThat(JevHosts.check((String) null, mode)).as(mode + " null").contains(JevHosts.Problem.MISSING);
            assertThat(JevHosts.check("  ", mode)).as(mode + " 공백").contains(JevHosts.Problem.MISSING);
            assertThat(JevHosts.check((URI) null, mode)).contains(JevHosts.Problem.MISSING);
            assertThat(JevHosts.check("not a url", mode)).as(mode + " 모양 아님").contains(JevHosts.Problem.INVALID);
            assertThat(JevHosts.check("ftp://127.0.0.1/x", mode)).contains(JevHosts.Problem.INVALID);
            assertThat(JevHosts.check("https:///no-host", mode)).contains(JevHosts.Problem.INVALID);
            // 사용자 정보가 든 주소 리터럴은 비밀 스캔(A4)의 URL 자격 증명 형식과 겹치므로 조각으로 조립한다
            String withUserInfo = "https://" + "user" + ':' + "pw" + "@127.0.0.1/x";
            assertThat(JevHosts.check(withUserInfo, mode)).as(mode + " 사용자 정보").contains(JevHosts.Problem.INVALID);
            assertThat(JevHosts.check("http://127.0.0.1/x?k=v", mode)).as(mode + " 질의").contains(JevHosts.Problem.INVALID);
            assertThat(JevHosts.check("http://127.0.0.1/x#frag", mode)).as(mode + " 조각").contains(JevHosts.Problem.INVALID);
        }
    }

    @Test
    void loopbackIsDecidedByTheLettersAloneWithoutResolvingNames() {
        assertThat(JevHosts.isLoopback("127.0.0.1")).isTrue();
        assertThat(JevHosts.isLoopback("127.255.255.254")).isTrue();
        assertThat(JevHosts.isLoopback("localhost")).isTrue();
        assertThat(JevHosts.isLoopback("::1")).isTrue();
        assertThat(JevHosts.isLoopback("[::1]")).isTrue();
        assertThat(JevHosts.isLoopback("128.0.0.1")).isFalse();
        assertThat(JevHosts.isLoopback("127.0.0.256")).isFalse();
        assertThat(JevHosts.isLoopback("127.0.0")).isFalse();
        assertThat(JevHosts.isLoopback("localhost.example.invalid")).isFalse();
        assertThat(JevHosts.isLoopback("127.0.0.x")).isFalse();
    }

    @Test
    void allowedHostConstantEqualsTheHostsWrittenInAiIoSection10() throws IOException {
        String aiIo = Files.readString(REPO_ROOT.resolve("contracts/ai-io.md"));
        Matcher line = Pattern.compile("^- 제브 허용 호스트: (.+)$", Pattern.MULTILINE).matcher(aiIo);

        assertThat(line.find()).as("ai-io.md 10.11 제브 허용 호스트 줄").isTrue();
        Set<String> documented = Pattern.compile("`([^`]+)`").matcher(line.group(1)).results()
                .map(match -> match.group(1)).collect(Collectors.toSet());
        assertThat(JevHosts.ALLOWED_HOSTS).isEqualTo(documented);
    }

    @Test
    void theModelMustBeAFixedVersionAndMovingAliasesAreRefused() {
        assertThat(JevModels.problem("jev-model-2026-10")).isEmpty();
        assertThat(JevModels.problem("  jev-model-2026-10  ")).as("앞뒤 공백은 무시").isEmpty();
        assertThat(JevModels.problem(null)).contains(JevModels.Problem.MISSING);
        assertThat(JevModels.problem("")).contains(JevModels.Problem.MISSING);
        assertThat(JevModels.problem("jev-latest")).contains(JevModels.Problem.MOVING_ALIAS);
        assertThat(JevModels.problem("Jev-Latest")).contains(JevModels.Problem.MOVING_ALIAS);
        assertThat(JevModels.problem("latest")).contains(JevModels.Problem.MOVING_ALIAS);
        assertThat(JevModels.problem("jev_latest")).contains(JevModels.Problem.MOVING_ALIAS);
        assertThat(JevModels.problem("jev-latest-2026-10")).as("끝이 별칭이 아니면 고정 버전 이름").isEmpty();
        assertThat(JevModels.problem("모델")).contains(JevModels.Problem.INVALID_FORMAT);
        assertThat(JevModels.problem("-jev")).contains(JevModels.Problem.INVALID_FORMAT);
        assertThat(JevModels.problem("a".repeat(65))).contains(JevModels.Problem.INVALID_FORMAT);
    }
}
