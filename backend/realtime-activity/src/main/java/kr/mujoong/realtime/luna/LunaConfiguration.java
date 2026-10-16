package kr.mujoong.realtime.luna;

import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.startup.RealtimeStartupChecks;
import kr.mujoong.realtime.startup.StartupFailureLine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Realtime Luna 빈 (U9 W1 · BR1.1 ~ BR1.4 · SD11 · SD12). 언제나 설정 바인딩 · 스키마 적재(실패면 기동 거부) · 기동 확인을 하고, env
 * {@code OPENAI_API_KEY}가 있을 때만 {@link LunaTransport} · {@link RealtimeLunaClient}를 등록한다. {@code JudgeClient} 포트 · 구현은 U11
 * 몫이라 여기에 없다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LunaProperties.class)
public class LunaConfiguration {

    @Bean
    LunaSchemas lunaSchemas(JsonCodec json) {
        try {
            return LunaSchemas.loadDefault(json);
        } catch (LunaSchemas.LoadException e) {
            // 메시지는 사유 코드와 파일 이름뿐이다(스키마 원문 없음). 스키마 없는 호출을 막으려고 기동을 거부한다(W1 4번)
            String reason = "Luna 스키마 적재 거부: " + e.getMessage();
            StartupFailureLine.print(LunaStartup.ERROR_KIND, reason);
            throw new RealtimeStartupChecks.StartupRejectedException(reason);
        }
    }

    @Bean
    LunaStartup lunaStartup(LunaProperties properties, Environment environment, EventLogger events) {
        return new LunaStartup(properties, LunaKeyPresentCondition.isPresent(environment), events,
                environment.getActiveProfiles());
    }

    @Bean
    @Conditional(LunaKeyPresentCondition.class)
    LunaTransport lunaTransport(LunaProperties properties, LunaStartup startup, Environment environment) {
        return new LunaTransport(properties.baseUrl(), startup.mode(),
                () -> environment.getProperty(LunaTransport.API_KEY_ENV), properties.maxResponseBytes());
    }

    @Bean
    @Conditional(LunaKeyPresentCondition.class)
    RealtimeLunaClient realtimeLunaClient(LunaProperties properties, RealtimeProperties realtime, LunaSchemas schemas,
            LunaTransport transport, EventLogger events, MujoongClock clock, JsonCodec json) {
        return new RealtimeLunaClient(properties, realtime.timing().judgment().liveBudget(), schemas,
                new LunaInputSanitizer(json), transport, events, clock, json);
    }
}
