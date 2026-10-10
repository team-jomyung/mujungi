package kr.mujoong.core.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import kr.mujoong.contract.profile.ProfileGuardRules;
import kr.mujoong.contract.profile.ProfileGuardRules.Combination;
import kr.mujoong.contract.profile.ProfileGuardRules.Startup;
import kr.mujoong.core.CoreApiApplication;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 실제 기동 경로의 프로필 가드 (U1 3.5 Step 28 · SD-14 표 5 ~ 12 · CD-7 · NFR9.11 · BR8.3). {@link SpringApplicationBuilder}로 Core 앱을 띄우면
 * 거부 조합은 환경 준비 단계(문맥 · 웹 서버 생성 전)에서 끝나고(스위치는 운영의 환경 변수처럼 설정 파일보다 우선하는 명령줄 인자로 준다), 표준 오류에는 충돌한 프로필 · 키 이름만 있고 값(비밀 · 스위치 값)은 없다.
 * 웹 서버를 띄우지 않았다는 것은 무작위로 준 포트에 아무도 듣지 않는 것으로 확인한다.
 */
@Tag("u1")
class ProfileGuardBootIT {

    static Stream<Combination> rejected() {
        return ProfileGuardRules.TABLE.stream().filter(c -> c.startup() == Startup.REJECT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void rejectedCombinationFailsBeforeTheWebServerStarts(Combination combination) throws Exception {
        int port = freePort();
        String token = TestSecrets.internalServiceToken();
        String ownerSecret = TestSecrets.ownerHashSecret();
        List<String> properties = new ArrayList<>(List.of(
                "server.port=" + port,
                "management.server.port=0",
                RequiredSecretsCheck.INTERNAL_TOKEN + "=" + token,
                RequiredSecretsCheck.OWNER_HASH_SECRET + "=" + ownerSecret,
                "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/never",
                ProfileGuardRules.DEV_LOGIN_ENABLED_KEY + "=" + combination.devLoginEnabled(),
                ProfileGuardRules.LOAD_TEST_ENABLED_KEY + "=" + combination.loadTestEnabled()));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            assertThatThrownBy(() -> {
                try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(CoreApiApplication.class)
                        .profiles(combination.profiles().toArray(String[]::new))
                        .logStartupInfo(false)
                        .run(properties.stream().map(p -> "--" + p).toArray(String[]::new))) {
                    throw new AssertionError("기동되면 안 된다: " + combination);
                }
            }).isInstanceOf(ProfileGuardEnvironmentPostProcessor.ProfileGuardRejectedException.class)
                    .hasMessageContaining("표 " + combination.row());
        } finally {
            System.setErr(original);
        }
        String printed = err.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains(StartupFailureLine.PREFIX).contains("ProfileGuardRejected")
                .doesNotContain(token).doesNotContain(ownerSecret).doesNotContain("=true").doesNotContain("=false");
        assertThat(listening(port)).isFalse();
    }

    @Test
    void rejectionLineNamesOnlyTheConflictingProfilesAndKeys() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            assertThatThrownBy(() -> new SpringApplicationBuilder(CoreApiApplication.class)
                    .profiles("prod")
                    .logStartupInfo(false)
                    .run("--" + ProfileGuardRules.DEV_LOGIN_ENABLED_KEY + "=true", "--server.port=0"))
                    .isInstanceOf(ProfileGuardEnvironmentPostProcessor.ProfileGuardRejectedException.class);
        } finally {
            System.setErr(original);
        }
        String reasonLine = err.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.startsWith(StartupFailureLine.PREFIX)).findFirst().orElseThrow();
        assertThat(reasonLine).contains("prod").contains(ProfileGuardRules.DEV_LOGIN_ENABLED_KEY).contains("표 8")
                .doesNotContain("true");
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean listening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
