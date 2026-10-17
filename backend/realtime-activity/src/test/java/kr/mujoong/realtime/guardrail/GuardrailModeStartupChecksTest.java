package kr.mujoong.realtime.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.gate.SeedDictionary;
import kr.mujoong.realtime.startup.RealtimeStartupChecks;
import kr.mujoong.realtime.startup.RealtimeStartupChecks.Inputs;
import kr.mujoong.realtime.startup.RealtimeStartupChecks.StartupRejectedException;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 제브 기동 점검 시험 (U13 FR7.1 · BR9.1 · BR9.2 · BR2.2 · BR2.3 · W6 표). 모드 × 프로필 × 키 표의 모든 칸, 허용 호스트 · 고정 모델 규칙, 모르는 모드값, 거부
 * 문구에 값이 없음, 기존 생성 경로는 점검하지 않음을 본다. 키 · 주소 값은 실행 때 만든 합성 값이다(R-F3). 이 파일의 성공은 테스트 대역 증거다.
 */
@Tag("u13")
class GuardrailModeStartupChecksTest {

    private static final URI WSS_PROVIDER = URI.create("wss://api.openai.com/v1/live/sessions");
    private static final URI LOOPBACK_PROVIDER = URI.create("ws://127.0.0.1:9/fake-provider");
    private static final String ALLOWED_URL = "https://" + JevHosts.ALLOWED_HOSTS.iterator().next() + "/v1/check";
    private static final String LOOPBACK_URL = "http://127.0.0.1:9/fake-jev";
    private static final String MODEL = "jev-model-2026-10";

    private final String token = TestSecrets.internalServiceToken();
    private final String providerKey = TestSecrets.base64Url(24);
    private final String ownerSecret = TestSecrets.ownerHashSecret();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream original;

    @BeforeEach
    void captureStderr() {
        original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStderr() {
        System.setErr(original);
    }

    private Inputs inputs(String profile, String mode, boolean key, String jevUrl, String model) {
        List<String> profiles = profile == null ? List.of() : List.of(profile);
        boolean pub = "demo".equals(profile) || "prod".equals(profile);
        return new Inputs(token, providerKey, ownerSecret, pub ? WSS_PROVIDER : LOOPBACK_PROVIDER, profiles,
                new GuardrailStartupRules.Inputs(mode, key, jevUrl, model));
    }

    private List<String> problems(String profile, String mode, boolean key, String jevUrl, String model) {
        return RealtimeStartupChecks.problems(inputs(profile, mode, key, jevUrl, model));
    }

    // ------------------------------------------------------------------ W6 표: 공개 프로필(demo · prod)

    @Test
    void publicProfilesAcceptOnlyRealWithAKeyAnAllowedHostAndAFixedModel() {
        for (String profile : List.of("demo", "prod")) {
            assertThat(problems(profile, "REAL", true, ALLOWED_URL, MODEL)).as(profile + " REAL + 키").isEmpty();
            assertThat(problems(profile, "real", true, ALLOWED_URL, MODEL)).as(profile + " 대소문자 무시").isEmpty();
            assertThat(problems(profile, "", true, ALLOWED_URL, MODEL)).as(profile + " 모드가 비면 기본값 REAL").isEmpty();
            assertThat(problems(profile, "REAL", false, ALLOWED_URL, MODEL)).as(profile + " REAL, 키 없음")
                    .containsExactly(RealtimeStartupChecks.JEV_API_KEY);
            assertThat(problems(profile, null, false, ALLOWED_URL, MODEL)).as(profile + " 모드 없음 + 키 없음")
                    .containsExactly(RealtimeStartupChecks.JEV_API_KEY);
        }
    }

    @Test
    void publicProfilesRejectStubAndOffEvenWhenAKeyIsPresent() {
        for (String profile : List.of("demo", "prod")) {
            for (String mode : List.of("STUB", "OFF")) {
                assertThat(problems(profile, mode, true, ALLOWED_URL, MODEL)).as(profile + " " + mode)
                        .containsExactly(GuardrailMode.KEY + "(PUBLIC_REQUIRES_REAL)");
            }
        }
    }

    // ------------------------------------------------------------------ W6 표: dev · test · 활성 프로필 없음

    @Test
    void nonPublicProfilesAllowTheDefaultStubAndExplicitStubOrOffWithoutAKey() {
        for (String profile : java.util.Arrays.asList("dev", "test", null)) {
            assertThat(problems(profile, "", false, "", "")).as(profile + " 기본(STUB)").isEmpty();
            assertThat(problems(profile, "STUB", false, "", "")).as(profile + " STUB").isEmpty();
            assertThat(problems(profile, "OFF", false, "", "")).as(profile + " OFF").isEmpty();
            assertThat(problems(profile, "STUB", true, ALLOWED_URL, MODEL)).as(profile + " STUB + 키").isEmpty();
        }
    }

    @Test
    void nonPublicRealNeedsAKeyAndAnAddressThatFitsTheProfileRule() {
        assertThat(problems("dev", "REAL", true, LOOPBACK_URL, MODEL)).as("dev: 로컬 가짜 제브").isEmpty();
        assertThat(problems("dev", "REAL", true, ALLOWED_URL, MODEL)).as("dev: 허용 호스트도 된다").isEmpty();
        assertThat(problems("test", "REAL", true, LOOPBACK_URL, MODEL)).as("test: 루프백만").isEmpty();
        assertThat(problems(null, "REAL", true, ALLOWED_URL, MODEL)).as("프로필 없음: 엄격 규칙").isEmpty();

        for (String profile : java.util.Arrays.asList("dev", "test", null)) {
            assertThat(problems(profile, "REAL", false, profile == null ? ALLOWED_URL : LOOPBACK_URL, MODEL))
                    .as(profile + " REAL, 키 없음(AC2.3.3)").containsExactly(RealtimeStartupChecks.JEV_API_KEY);
        }
        assertThat(problems("test", "REAL", true, ALLOWED_URL, MODEL)).as("test: 실공급자 호스트는 R-F1로 거부")
                .containsExactly(GuardrailProperties.BASE_URL_KEY + "(NOT_LOOPBACK)");
        assertThat(problems(null, "REAL", true, LOOPBACK_URL, MODEL)).as("프로필 없음: 루프백은 거부")
                .containsExactly(GuardrailProperties.BASE_URL_KEY + "(HOST_NOT_ALLOWED)");
    }

    // ------------------------------------------------------------------ 허용 호스트 · 고정 모델

    @Test
    void realNeedsAnAllowedHttpsHostAndAFixedModelVersionInEveryProfile() {
        assertThat(problems("demo", "REAL", true, "https://example.invalid/v1", MODEL))
                .containsExactly(GuardrailProperties.BASE_URL_KEY + "(HOST_NOT_ALLOWED)");
        assertThat(problems("demo", "REAL", true, ALLOWED_URL.replace("https://", "http://"), MODEL))
                .as("http는 공개 프로필에서 거부").containsExactly(GuardrailProperties.BASE_URL_KEY + "(HOST_NOT_ALLOWED)");
        assertThat(problems("demo", "REAL", true, "", MODEL)).containsExactly(GuardrailProperties.BASE_URL_KEY + "(MISSING)");
        assertThat(problems("demo", "REAL", true, "not a url", MODEL)).containsExactly(GuardrailProperties.BASE_URL_KEY + "(INVALID)");
        assertThat(problems("demo", "REAL", true, ALLOWED_URL + "?k=v", MODEL)).as("질의가 붙은 주소")
                .containsExactly(GuardrailProperties.BASE_URL_KEY + "(INVALID)");

        assertThat(problems("prod", "REAL", true, ALLOWED_URL, "")).containsExactly(GuardrailProperties.MODEL_KEY + "(MISSING)");
        assertThat(problems("prod", "REAL", true, ALLOWED_URL, "jev-latest"))
                .as("이동 별칭").containsExactly(GuardrailProperties.MODEL_KEY + "(MOVING_ALIAS)");
        assertThat(problems("dev", "REAL", true, LOOPBACK_URL, "LATEST")).containsExactly(GuardrailProperties.MODEL_KEY + "(MOVING_ALIAS)");
        assertThat(problems("prod", "REAL", true, ALLOWED_URL, "모델 이름")).containsExactly(GuardrailProperties.MODEL_KEY + "(INVALID_FORMAT)");
        assertThat(problems("prod", "REAL", false, "", "")).as("문제는 한꺼번에 모아 보고한다")
                .containsExactly(RealtimeStartupChecks.JEV_API_KEY, GuardrailProperties.BASE_URL_KEY + "(MISSING)",
                        GuardrailProperties.MODEL_KEY + "(MISSING)");
    }

    @Test
    void anUnknownModeValueIsRejectedInEveryProfileAndTheProblemsNeverCarryValues() {
        String secretLooking = "jev-" + TestSecrets.base64Url(12);
        for (String profile : java.util.Arrays.asList("demo", "dev", "test", null)) {
            List<String> problems = problems(profile, "MAYBE", true, secretLooking, secretLooking);

            assertThat(problems).as(profile).contains(GuardrailMode.KEY + "(INVALID)");
            assertThat(String.join(",", problems)).as("값 미출력").doesNotContain("MAYBE").doesNotContain(secretLooking);
        }
        assertThat(new GuardrailStartupRules.Inputs("REAL", true, "https://" + secretLooking, secretLooking).toString())
                .doesNotContain(secretLooking);
    }

    // ------------------------------------------------------------------ 기동 점검 생성자

    @Test
    void theLegacyInputsShapeSkipsTheGuardrailCheckAndMeansNoCheck() {
        Inputs legacy = new Inputs(token, providerKey, ownerSecret, WSS_PROVIDER, List.of("demo"));

        assertThat(RealtimeStartupChecks.problems(legacy)).as("제브 점검 입력이 없으면 기존 U1 ~ U11 동작").isEmpty();
        assertThat(new RealtimeStartupChecks(legacy, SeedDictionary::loadDefault).guardrailMode()).isEqualTo(GuardrailMode.OFF);
    }

    @Test
    void startupExposesTheResolvedModeAndRejectsWithReasonNamesOnly() {
        assertThat(new RealtimeStartupChecks(inputs("dev", "", false, "", ""), SeedDictionary::loadDefault).guardrailMode())
                .isEqualTo(GuardrailMode.STUB);
        assertThat(new RealtimeStartupChecks(inputs("dev", "OFF", false, "", ""), SeedDictionary::loadDefault).guardrailMode())
                .isEqualTo(GuardrailMode.OFF);
        assertThat(new RealtimeStartupChecks(inputs("demo", "", true, ALLOWED_URL, MODEL), SeedDictionary::loadDefault).guardrailMode())
                .isEqualTo(GuardrailMode.REAL);
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();

        assertThatThrownBy(() -> new RealtimeStartupChecks(inputs("prod", "STUB", true, ALLOWED_URL, MODEL), SeedDictionary::loadDefault))
                .isInstanceOf(StartupRejectedException.class).hasMessageContaining(GuardrailMode.KEY + "(PUBLIC_REQUIRES_REAL)")
                .hasMessageNotContaining(ALLOWED_URL).hasMessageNotContaining(MODEL);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("RealtimeStartupRejected").doesNotContain(ALLOWED_URL);
    }

    @Test
    void theSpringConstructorReadsTheModeKeyAddressAndModelFromTheEnvironmentWithoutKeepingTheKeyValue() {
        String jevKey = TestSecrets.base64Url(24);
        MockEnvironment demo = baseEnvironment("demo")
                .withProperty(GuardrailMode.KEY, "REAL")
                .withProperty(RealtimeStartupChecks.JEV_API_KEY, jevKey)
                .withProperty(GuardrailProperties.BASE_URL_KEY, ALLOWED_URL)
                .withProperty(GuardrailProperties.MODEL_KEY, MODEL);

        RealtimeStartupChecks ok = new RealtimeStartupChecks(demo, properties(WSS_PROVIDER));

        assertThat(ok.guardrailMode()).isEqualTo(GuardrailMode.REAL);

        MockEnvironment noKey = baseEnvironment("demo")
                .withProperty(GuardrailProperties.BASE_URL_KEY, ALLOWED_URL)
                .withProperty(GuardrailProperties.MODEL_KEY, MODEL);
        assertThatThrownBy(() -> new RealtimeStartupChecks(noKey, properties(WSS_PROVIDER)))
                .isInstanceOf(StartupRejectedException.class).hasMessageContaining(RealtimeStartupChecks.JEV_API_KEY);

        MockEnvironment testProfile = baseEnvironment("test");
        assertThat(new RealtimeStartupChecks(testProfile, properties(LOOPBACK_PROVIDER)).guardrailMode())
                .as("test 프로필 기본값은 대역").isEqualTo(GuardrailMode.STUB);
        assertThat(err.toString(StandardCharsets.UTF_8)).doesNotContain(jevKey);
    }

    private MockEnvironment baseEnvironment(String profile) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(RealtimeStartupChecks.INTERNAL_TOKEN, token)
                .withProperty(RealtimeStartupChecks.OPENAI_API_KEY, providerKey);
        environment.setActiveProfiles(profile);
        return environment;
    }

    private RealtimeProperties properties(URI providerUrl) {
        RealtimeProperties properties = mock(RealtimeProperties.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(properties.observability().ownerHashSecret()).thenReturn(ownerSecret);
        when(properties.provider().url()).thenReturn(providerUrl);
        return properties;
    }

    // ------------------------------------------------------------------ 모드 값 · 공개 프로필 기준

    @Test
    void modeParsingIgnoresCaseAndBlanksAndTheDefaultsFollowThePublicProfileRule() {
        assertThat(GuardrailMode.parse(" real ")).contains(GuardrailMode.REAL);
        assertThat(GuardrailMode.parse("Stub")).contains(GuardrailMode.STUB);
        assertThat(GuardrailMode.parse("OFF")).contains(GuardrailMode.OFF);
        assertThat(GuardrailMode.parse("")).isEmpty();
        assertThat(GuardrailMode.parse(null)).isEmpty();
        assertThat(GuardrailMode.parse("AUTO")).isEmpty();
        assertThat(GuardrailMode.defaultFor(true)).isEqualTo(GuardrailMode.REAL);
        assertThat(GuardrailMode.defaultFor(false)).isEqualTo(GuardrailMode.STUB);
        assertThat(GuardrailMode.resolve("OFF", true)).isEqualTo(GuardrailMode.OFF);
        assertThat(GuardrailMode.resolve("nonsense", false)).as("모르는 값은 기동 점검이 거부하므로 여기서는 기본값").isEqualTo(GuardrailMode.STUB);

        assertThat(RealtimeStartupChecks.isPublicProfile(List.of("demo"))).isTrue();
        assertThat(RealtimeStartupChecks.isPublicProfile(List.of("test", "prod"))).isTrue();
        assertThat(RealtimeStartupChecks.isPublicProfile(List.of("dev", "test"))).isFalse();
        assertThat(RealtimeStartupChecks.isPublicProfile(List.of())).isFalse();
    }
}
