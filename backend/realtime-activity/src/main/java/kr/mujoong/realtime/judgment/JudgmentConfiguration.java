package kr.mujoong.realtime.judgment;

import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.config.RealtimeProperties;
import java.util.Set;
import kr.mujoong.realtime.content.LiveContentRegistry;
import kr.mujoong.realtime.guardrail.GuardrailMode;
import kr.mujoong.realtime.guardrail.GuardrailProperties;
import kr.mujoong.realtime.guardrail.JevGuardrailClient;
import kr.mujoong.realtime.guardrail.JevHosts;
import kr.mujoong.realtime.luna.RealtimeLunaClient;
import kr.mujoong.realtime.startup.RealtimeStartupChecks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 판정 빈 (U11 BR1.6). Luna 호출부 빈이 있으면(키와 스위치 {@code mujoong.luna.enabled}가 모두 있을 때만 등록된다, U9 · U11-c) Luna 구현을,
 * 없으면 공급자를 부르지 않는 보류 구현을 만든다. 어느 쪽이든 앱은 정상 기동한다. 제브가 실제 모드(REAL)이면 {@link #JEV_POINTS}의 판정 지점은
 * 제브 구현으로 보내고(쓸 수 없으면 Luna로), 나머지 지점은 Luna가 맡는다.
 */
@Configuration(proxyBeanMethods = false)
public class JudgmentConfiguration {

    /** 제브로 판정하는 지점. 시작 의사(J1)만 먼저 옮겼고, 나머지 지점은 같은 방법으로 더하면 된다. */
    static final Set<JudgmentPoint> JEV_POINTS = Set.of(JudgmentPoint.J1);

    @Bean
    JudgmentPrompt judgmentPrompt(LiveContentRegistry content) {
        return JudgmentPrompt.load(content.resources().judgment());
    }

    @Bean
    JudgeOutcomeValidator judgeOutcomeValidator(RealtimeProperties properties, JsonCodec json) {
        return new JudgeOutcomeValidator(properties.judgment().minConfidence(), properties.judgment().minMargin(), json);
    }

    @Bean
    JudgeClient judgeClient(ObjectProvider<RealtimeLunaClient> luna, JudgmentPrompt prompt, JudgeOutcomeValidator validator,
            MujoongClock clock, GuardrailProperties jevProperties, Environment environment, RealtimeStartupChecks startup,
            LiveContentRegistry content, RealtimeProperties properties, JsonCodec json) {
        RealtimeLunaClient client = luna.getIfAvailable();
        JudgeClient lunaJudge = client == null ? new UnavailableJudgeClient(prompt.version())
                : new LunaJudgeClient(client::call, prompt, validator, clock);
        // 제브 판정은 제브가 실제 모드일 때만 쓴다(대역 · 꺼짐 · 시험 모드에서는 실제 공급자를 부르지 않는다, R-F1)
        if (startup.guardrailMode() != GuardrailMode.REAL) {
            return lunaJudge;
        }
        JudgeClient jev = new JevJudgeClient(jevProperties, JevHosts.modeFor(environment.getActiveProfiles()),
                () -> environment.getProperty(JevGuardrailClient.API_KEY_ENV), content.resources().judgment(), validator,
                prompt.version(), properties.timing().judgment().liveBudget(), clock, json);
        return new RoutedJudgeClient(jev, lunaJudge, JEV_POINTS);
    }
}
