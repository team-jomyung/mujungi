package kr.mujoong.realtime.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.realtime.config.RealtimeProperties;
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

/**
 * 제브 게이트 빈 조립 시험 (U13 BR1.3 ~ BR1.6 · BR9.2). 기동 점검이 정한 모드로 포트 구현과 장치 묶음이 만들어지고, 비실검사(STUB · OFF)로 시작하면 기동
 * 로그에 사유 코드 한 줄이 남으며 REAL이면 남기지 않는다. 이 파일의 성공은 테스트 대역 증거다.
 */
@Tag("u13")
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

    @Test
    void theClientImplementationFollowsTheModeAndARealWithoutAnAdapterIsABlockingPlaceholderNotAnAutoAllow() {
        GuardrailClient stub = configuration.guardrailClient(checksWith(GuardrailMode.STUB));
        GuardrailClient real = configuration.guardrailClient(checksWith(GuardrailMode.REAL));
        GuardrailClient off = configuration.guardrailClient(checksWith(GuardrailMode.OFF));

        assertThat(stub).isInstanceOf(StubGuardrailClient.class);
        assertThat(stub.mode()).isEqualTo(GuardrailMode.STUB);
        assertThat(real.mode()).isEqualTo(GuardrailMode.REAL);
        assertThat(real.available()).as("실제 어댑터가 붙기 전 자리는 막힘으로 끝난다").isFalse();
        assertThat(off.mode()).isEqualTo(GuardrailMode.OFF);
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
