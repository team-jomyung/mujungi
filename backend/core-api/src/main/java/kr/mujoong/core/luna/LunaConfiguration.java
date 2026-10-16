package kr.mujoong.core.luna;

import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.startup.StartupFailureLine;
import kr.mujoong.core.textai.TextAiProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Core Luna 빈 (U9 W1 · BR1.1 ~ BR1.4 · SD11 · SD12).
 *
 * <ul>
 *   <li>언제나: {@link LunaProperties} 바인딩, 스키마 짝 적재(실패면 기동 거부), 기동 확인 · {@code LUNA_STARTUP}.
 *   <li>env {@code OPENAI_API_KEY}가 있을 때만({@link LunaKeyPresentCondition}): {@link LunaTransport} · {@link CoreLunaClient}. 기존
 *       인터페이스 어댑터는 {@code kr.mujoong.core.luna.adapter.LunaAdapterConfiguration}이 같은 조건으로 등록한다.
 * </ul>
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
            throw new IllegalStateException(reason, e);
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
    CoreLunaClient coreLunaClient(LunaProperties properties, TextAiProperties timing, LunaSchemas schemas,
            LunaTransport transport, EventLogger events, MujoongClock clock, JsonCodec json) {
        return new CoreLunaClient(properties, timing, schemas, new LunaInputSanitizer(json), transport, events, clock, json,
                CoreLunaClient.SYSTEM_SLEEPER);
    }
}
