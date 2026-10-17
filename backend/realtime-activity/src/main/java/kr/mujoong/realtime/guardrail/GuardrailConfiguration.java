package kr.mujoong.realtime.guardrail;

import java.util.Optional;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.content.LiveContentRegistry;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.startup.RealtimeStartupChecks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 제브 게이트 빈 (U13 BR1.3 ~ BR1.6 · BR9.2). 설정 {@code mujoong.guardrail.jev.*}를 묶고, 기동 점검이 정한 모드(REAL · STUB · OFF)로 제브 포트
 * 구현과 세션 실행기용 장치 묶음({@link GuardrailSupport})을 만든다. 모드는 기동 때 한 번 정해지고 세션 안에서 바뀌지 않는다.
 *
 * <p>비실검사 모드(STUB · OFF)로 시작하면 그 사실이 기동 로그에 한 줄 남는다(사유 코드 {@value #REASON_MODE_STUB} · {@value #REASON_MODE_OFF}, 새
 * 사건 코드 없음 — 편차 U13-D2). 대역의 통과는 실제 검사 성공이 아니다(R-M11).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GuardrailProperties.class)
public class GuardrailConfiguration {

    /** 대역으로 시작했다는 기동 로그의 사유 코드. */
    static final String REASON_MODE_STUB = "GuardrailModeStub";
    /** 비활성으로 시작했다는 기동 로그의 사유 코드. */
    static final String REASON_MODE_OFF = "GuardrailModeOff";

    @Bean
    GuardrailRequestFactory guardrailRequestFactory() {
        return new GuardrailRequestFactory();
    }

    /**
     * 모드별 제브 포트. {@code REAL}은 실제 어댑터({@link JevGuardrailClient})다 — 키는 호출 때마다 환경에서 읽고(BR2.8) 시스템 지시는 세션에
     * 고정된 정책 리소스에서 가져온다. {@code OFF}의 자리는 막힘으로 끝나는 비활성 구현이다 — 자동 ALLOW가 아니다(BR1.2).
     */
    @Bean
    GuardrailClient guardrailClient(RealtimeStartupChecks startupChecks, GuardrailProperties properties, Environment environment,
            RealtimeProperties realtime, LiveContentRegistry content, MujoongClock clock, JsonCodec json) {
        return switch (startupChecks.guardrailMode()) {
            case STUB -> new StubGuardrailClient();
            case REAL -> new JevGuardrailClient(properties, JevHosts.modeFor(environment.getActiveProfiles()),
                    () -> environment.getProperty(JevGuardrailClient.API_KEY_ENV), policyVersion -> systemPrompt(content, policyVersion),
                    realtime.timing().guardrail().jevBudget(), clock, json);
            case OFF -> new UnavailableGuardrailClient(GuardrailMode.OFF);
        };
    }

    /** 정책 판 → 시스템 지시. 세션에 고정되는 판({@code guardrail/policy-v1.json})과 같은 판만 답한다. */
    static Optional<String> systemPrompt(LiveContentRegistry content, String policyVersion) {
        var policy = content.resources().guardrail();
        return policy.version().equals(policyVersion) ? Optional.of(policy.systemPrompt()) : Optional.empty();
    }

    @Bean
    GuardrailSupport guardrailSupport(RealtimeStartupChecks startupChecks, GuardrailClient client, RealtimeProperties properties,
            ObjectProvider<GuardrailEventRecorder> recorder, EventLogger events) {
        GuardrailMode mode = startupChecks.guardrailMode();
        startupNotice(mode, events);
        if (mode == GuardrailMode.OFF) {
            return GuardrailSupport.DISABLED;
        }
        return new GuardrailSupport(mode, client, recorder.getIfAvailable(), properties.timing().guardrail().jevBudget());
    }

    /** 비실검사 모드 기동 로그 한 줄(BR9.2). REAL이면 남기지 않는다. 남긴 사유 코드(없으면 null). */
    static String startupNotice(GuardrailMode mode, EventLogger events) {
        String reason = switch (mode) {
            case STUB -> REASON_MODE_STUB;
            case OFF -> REASON_MODE_OFF;
            case REAL -> null;
        };
        if (reason != null) {
            events.appError(EventLog.Component.OUTPUT_GATE, reason, EventCode.Level.WARN);
        }
        return reason;
    }
}
