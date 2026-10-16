package kr.mujoong.core.luna.adapter;

import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.luna.CoreLunaClient;
import kr.mujoong.core.luna.LunaKeyPresentCondition;
import kr.mujoong.core.result.ResultTextProvider;
import kr.mujoong.core.textai.TextAiProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * 기존 인터페이스 어댑터 빈 (U9 FR1.3 · BR1.1 · BR1.2 · SD11 · AC2.3.3). env {@code OPENAI_API_KEY}가 있을 때만 등록되고, 기존 실패 기본
 * 빈({@code TextAiConfiguration} · {@code ResultTextConfiguration})은 반대 조건({@code LunaKeyAbsentCondition})일 때만 등록되므로 빈
 * 등록 순서에 기대지 않는다. {@code @ConditionalOnMissingBean}은 시험이 대역 빈을 넣는 경로를 위해 함께 둔다.
 */
@Configuration(proxyBeanMethods = false)
public class LunaAdapterConfiguration {

    @Bean
    @Conditional(LunaKeyPresentCondition.class)
    @ConditionalOnMissingBean(TextAiProvider.class)
    LunaTextAiProvider lunaTextAiProvider(CoreLunaClient client, MujoongClock clock, JsonCodec json) {
        return new LunaTextAiProvider(client, clock, json);
    }

    @Bean
    @Conditional(LunaKeyPresentCondition.class)
    @ConditionalOnMissingBean(ResultTextProvider.class)
    LunaResultTextProvider lunaResultTextProvider(CoreLunaClient client, MujoongClock clock) {
        return new LunaResultTextProvider(client, clock);
    }
}
