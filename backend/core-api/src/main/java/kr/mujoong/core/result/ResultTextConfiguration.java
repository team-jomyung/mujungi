package kr.mujoong.core.result;

import kr.mujoong.core.luna.LunaKeyAbsentCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** 결과 문장 공급자 기본 빈(U6). U9: env {@code OPENAI_API_KEY}가 없을 때만 실패 기본 빈을 둔다(BR1.2 · SD11). */
@Configuration(proxyBeanMethods = false)
public class ResultTextConfiguration {

    @Bean
    @Conditional(LunaKeyAbsentCondition.class)
    @ConditionalOnMissingBean(ResultTextProvider.class)
    ResultTextProvider unavailableResultProvider() {
        return (request, timeout) -> {
            throw new ResultAiException("PROVIDER_UNAVAILABLE");
        };
    }
}
