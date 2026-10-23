package kr.mujoong.realtime.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.realtime.gate.SeedDictionary;
import kr.mujoong.realtime.startup.RealtimeStartupChecks.Inputs;
import kr.mujoong.realtime.startup.RealtimeStartupChecks.StartupRejectedException;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 기동 점검 S1b의 U8 판 리소스 적재(U8 functional-spec 8.3 · BR2.1 · NFR2.3 · SD5). 판 리소스가 없거나 형식이 틀리면 기동을 거부하고 거부 줄에
 * 사유만 남기며, 기존 U1 생성자는 classpath 판을 그대로 읽는다.
 */
@Tag("live-content")
@org.junit.jupiter.api.Tag("service-guardrail")
class LiveContentRealtimeStartupChecksTest {

    private static final URI LOOPBACK = URI.create("ws://127.0.0.1:9/fake-provider");

    private final Inputs inputs = new Inputs(TestSecrets.internalServiceToken(), TestSecrets.base64Url(24),
            TestSecrets.ownerHashSecret(), LOOPBACK, List.of("test"));
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream original;

    @BeforeEach
    void captureStderr() {
        // Mock maker startup belongs to test setup, before observing application startup diagnostics.
        org.mockito.Mockito.mock(kr.mujoong.realtime.config.RealtimeProperties.class);
        original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStderr() {
        System.setErr(original);
    }

    @Test
    void theShippedVersionResourcesPassAndAreExposedToTheRegistry() {
        RealtimeStartupChecks checks = new RealtimeStartupChecks(inputs, SeedDictionary::loadDefault,
                LiveContentResources::loadDefault);

        assertThat(checks.liveContent().judgment().version()).isEqualTo("judgment-service-v1");
        assertThat(checks.liveContent().guardrail().version()).isEqualTo("jev-guardrail-v3");
        assertThat(new LiveContentConfiguration().liveContentRegistry(checks).resources()).isSameAs(checks.liveContent());
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void theU1ConstructorStillLoadsTheClasspathVersionResources() {
        RealtimeStartupChecks checks = new RealtimeStartupChecks(inputs, SeedDictionary::loadDefault);

        assertThat(checks.dictionary().entries()).isNotEmpty();
        assertThat(checks.liveContent().judgment().points()).hasSize(6);
    }

    @Test
    void aBrokenVersionResourceRejectsStartupWithTheReasonOnly() {
        String guardrail = resource(LiveContentResources.GUARDRAIL_RESOURCE);
        String judgment = resource(LiveContentResources.JUDGMENT_RESOURCE);
        String sixCategories = guardrail.replace("\"PROFANITY\"\n  ]", "\"PROFANITY\", \"EXTRA\"\n  ]");

        assertThatThrownBy(() -> new RealtimeStartupChecks(inputs, SeedDictionary::loadDefault,
                () -> LiveContentResources.parse(judgment, sixCategories)))
                .isInstanceOf(StartupRejectedException.class)
                .hasMessageContaining("BAD_CATEGORIES")
                .hasMessageNotContaining("인용 부호");
        String line = err.toString(StandardCharsets.UTF_8);
        assertThat(line).contains("RealtimeStartupRejected").doesNotContain("인용 부호").doesNotContain("state.candidate");
    }

    @Test
    void secretProblemsAreStillReportedBeforeTheResourcesAreRead() {
        Inputs noToken = new Inputs(null, TestSecrets.base64Url(24), TestSecrets.ownerHashSecret(), LOOPBACK, List.of("test"));
        boolean[] loaded = {false};

        assertThatThrownBy(() -> new RealtimeStartupChecks(noToken, SeedDictionary::loadDefault, () -> {
            loaded[0] = true;
            return LiveContentResources.loadDefault();
        })).isInstanceOf(StartupRejectedException.class).hasMessageContaining(RealtimeStartupChecks.INTERNAL_TOKEN);
        assertThat(loaded[0]).isFalse();
    }

    @Test
    void theSpringConstructorReadsTheEnvironmentAndLoadsTheVersionResources() {
        org.springframework.mock.env.MockEnvironment environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty(RealtimeStartupChecks.INTERNAL_TOKEN, TestSecrets.internalServiceToken())
                .withProperty(RealtimeStartupChecks.OPENAI_API_KEY, TestSecrets.base64Url(24));
        environment.setActiveProfiles("test");
        kr.mujoong.realtime.config.RealtimeProperties properties = org.mockito.Mockito.mock(
                kr.mujoong.realtime.config.RealtimeProperties.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(properties.observability().ownerHashSecret()).thenReturn(TestSecrets.ownerHashSecret());
        org.mockito.Mockito.when(properties.provider().url()).thenReturn(LOOPBACK);

        RealtimeStartupChecks checks = new RealtimeStartupChecks(environment, properties);

        assertThat(checks.liveContent().guardrail().modes()).hasSize(3);
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void aClasspathWithoutTheVersionResourcesIsMissingAndOtherBrokenShapesAreNamed() {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(new java.net.URLClassLoader(new java.net.URL[0], null));
        try {
            assertThatThrownBy(LiveContentResources::loadDefault).isInstanceOf(LiveContentResources.LoadException.class)
                    .satisfies(e -> assertThat(((LiveContentResources.LoadException) e).problem())
                            .isEqualTo(LiveContentResources.Problem.MISSING));
        } finally {
            thread.setContextClassLoader(original);
        }
        String judgment = resource(LiveContentResources.JUDGMENT_RESOURCE);
        String guardrail = resource(LiveContentResources.GUARDRAIL_RESOURCE);
        assertProblem(judgment, "{broken", LiveContentResources.Problem.PARSE_FAILED);
        assertProblem(judgment, guardrail.replace("\"version\": \"jev-guardrail-v3\"", "\"version\": \" \""),
                LiveContentResources.Problem.BAD_VERSION);
        assertProblem(judgment, guardrail.replaceFirst(",\\s*\"modes\": \\[[^\\]]*\\]", ""), LiveContentResources.Problem.BAD_MODES);
        assertProblem("{\"version\":\"judgment-v1\",\"source\":\"x\"}", guardrail, LiveContentResources.Problem.BAD_JUDGMENT_POINTS);
        assertThat(LiveContentResources.parse(judgment, guardrail).guardrail().toString()).doesNotContain("인용 부호");
    }

    private static void assertProblem(String judgment, String guardrail, LiveContentResources.Problem problem) {
        assertThatThrownBy(() -> LiveContentResources.parse(judgment, guardrail))
                .isInstanceOf(LiveContentResources.LoadException.class)
                .satisfies(e -> assertThat(((LiveContentResources.LoadException) e).problem()).isEqualTo(problem));
    }

    private static String resource(String name) {
        try (var in = LiveContentRealtimeStartupChecksTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
