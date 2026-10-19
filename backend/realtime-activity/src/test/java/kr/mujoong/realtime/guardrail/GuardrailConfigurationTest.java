package kr.mujoong.realtime.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.content.LiveContentRegistry;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.startup.RealtimeStartupChecks;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * 제브 게이트 빈 조립 시험 (U13 BR1.3 ~ BR1.6 · BR9.2). 기동 점검이 정한 모드로 포트 구현과 장치 묶음이 만들어지고, 비실검사(STUB · OFF)로 시작하면 기동
 * 로그에 사유 코드 한 줄이 남으며 REAL이면 남기지 않는다. 이 파일의 성공은 테스트 대역 증거다.
 */
@Tag("jev-guardrail")
class GuardrailConfigurationTest {

    private final LogCapture logs = LogCapture.detached();
    private final EventLogger events = TestEventLoggers.strict(new MutableClock(), logs, RealtimeMetrics.inMemory());
    private final GuardrailConfiguration configuration = new GuardrailConfiguration();

    private static RealtimeStartupChecks checksWith(GuardrailMode mode) {
        RealtimeStartupChecks checks = mock(RealtimeStartupChecks.class);
        when(checks.guardrailMode()).thenReturn(mode);
        return checks;
    }

    private static RealtimeProperties budget(Duration jevBudget) {
        RealtimeProperties properties = mock(RealtimeProperties.class, Mockito.RETURNS_DEEP_STUBS);
        when(properties.timing().guardrail().jevBudget()).thenReturn(jevBudget);
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<GuardrailEventRecorder> recorderProvider(GuardrailEventRecorder recorder) {
        ObjectProvider<GuardrailEventRecorder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(recorder);
        return provider;
    }

    private List<String> errorKinds() {
        return logs.events(EventCode.APP_ERROR).stream().map(event -> (String) LogCapture.attrs(event).get("errorKind")).toList();
    }

    private GuardrailClient client(GuardrailMode mode, MockEnvironment environment) {
        return configuration.guardrailClient(checksWith(mode), new GuardrailProperties("", "https://" + JevHosts.ALLOWED_HOSTS.iterator().next(), "jev-model-1", 0.5),
                environment, budget(Duration.ofMillis(800)), new LiveContentRegistry(LiveContentResources.loadDefault()), new MutableClock(),
                JsonCodec.shared());
    }

    @Test
    void theClientImplementationFollowsTheModeAndRealIsTheJevAdapterThatIsUnavailableWithoutAKey() {
        MockEnvironment withoutKey = new MockEnvironment();
        GuardrailClient stub = client(GuardrailMode.STUB, withoutKey);
        GuardrailClient real = client(GuardrailMode.REAL, withoutKey);
        GuardrailClient off = client(GuardrailMode.OFF, withoutKey);

        assertThat(stub).isInstanceOf(StubGuardrailClient.class);
        assertThat(stub.mode()).isEqualTo(GuardrailMode.STUB);
        assertThat(real).isInstanceOf(JevGuardrailClient.class);
        assertThat(real.mode()).isEqualTo(GuardrailMode.REAL);
        assertThat(real.available()).as("키가 없으면 어댑터는 막힘으로 끝난다(자동 ALLOW 아님)").isFalse();
        assertThat(off).isInstanceOf(UnavailableGuardrailClient.class);
        assertThat(off.mode()).isEqualTo(GuardrailMode.OFF);
    }

    @Test
    void theRealAdapterReadsTheKeyFromTheEnvironmentAtCallTimeAndTheQuestionComesFromThePinnedPolicy() {
        MockEnvironment environment = new MockEnvironment();
        GuardrailClient real = client(GuardrailMode.REAL, environment);
        assertThat(real.available()).isFalse();
        environment.setProperty(JevGuardrailClient.API_KEY_ENV, "fake-key-not-real");
        assertThat(real.available()).as("보관하지 않고 호출 때마다 환경에서 읽는다").isTrue();

        LiveContentRegistry content = new LiveContentRegistry(LiveContentResources.loadDefault());
        var policy = content.resources().guardrail();
        assertThat(GuardrailConfiguration.question(content, policy.version()))
                .hasValue(new JevQuestion(policy.instructions(), policy.criteria()));
        assertThat(GuardrailConfiguration.question(content, policy.version()).orElseThrow().labels())
                .containsExactlyElementsOf(policy.criteria().keySet());
        assertThat(GuardrailConfiguration.question(content, "other-version")).isEmpty();
    }

    @Test
    void aNonRealStartupLeavesExactlyOneReasonCodeLineAndRealLeavesNone() {
        assertThat(GuardrailConfiguration.startupNotice(GuardrailMode.REAL, events)).isNull();
        assertThat(errorKinds()).isEmpty();

        assertThat(GuardrailConfiguration.startupNotice(GuardrailMode.STUB, events)).isEqualTo("GuardrailModeStub");
        assertThat(errorKinds()).containsExactly("GuardrailModeStub");

        assertThat(GuardrailConfiguration.startupNotice(GuardrailMode.OFF, events)).isEqualTo("GuardrailModeOff");
        assertThat(errorKinds()).containsExactly("GuardrailModeStub", "GuardrailModeOff");
        assertThat(logs.events(EventCode.APP_ERROR)).allSatisfy(event -> assertThat(event.get("level")).isEqualTo("WARN"));
    }

    @Test
    void theSupportBeanCarriesTheModeTheRecorderAndTheConfiguredBudgetAndOffIsTheDisabledSupport() {
        GuardrailEventRecorder recorder = new GuardrailEventRecorder(record -> { }, TaskLauncher.inline(), events);
        Duration jevBudget = Duration.ofMillis(5);

        GuardrailSupport stub = configuration.guardrailSupport(checksWith(GuardrailMode.STUB), new StubGuardrailClient(), budget(jevBudget),
                recorderProvider(recorder), events);
        GuardrailSupport off = configuration.guardrailSupport(checksWith(GuardrailMode.OFF),
                new UnavailableGuardrailClient(GuardrailMode.OFF), budget(jevBudget), recorderProvider(recorder), events);

        assertThat(stub.mode()).isEqualTo(GuardrailMode.STUB);
        assertThat(stub.recorder()).isSameAs(recorder);
        assertThat(stub.budget()).as("시간 값은 설정 키에서만 읽는다").isEqualTo(jevBudget);
        assertThat(off).isSameAs(GuardrailSupport.DISABLED);
        assertThat(errorKinds()).containsExactly("GuardrailModeStub", "GuardrailModeOff");
    }

    @Test
    void theRequestFactoryBeanIsAPlainFactory() {
        assertThat(configuration.guardrailRequestFactory()).isNotNull();
    }
}
