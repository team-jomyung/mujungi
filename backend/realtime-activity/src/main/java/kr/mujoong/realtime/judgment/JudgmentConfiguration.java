package kr.mujoong.realtime.judgment;

import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.content.LiveContentRegistry;
import kr.mujoong.realtime.luna.RealtimeLunaClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 판정 빈 (U11 BR1.6). Luna 호출부 빈이 있으면(키와 스위치 {@code mujoong.luna.enabled}가 모두 있을 때만 등록된다, U9 · U11-c) Luna 구현을,
 * 없으면 공급자를 부르지 않는 보류 구현을 만든다. 어느 쪽이든 앱은 정상 기동한다.
 */
@Configuration(proxyBeanMethods = false)
public class JudgmentConfiguration {

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
            MujoongClock clock) {
        RealtimeLunaClient client = luna.getIfAvailable();
        if (client == null) {
            return new UnavailableJudgeClient(prompt.version());
        }
        return new LunaJudgeClient(client::call, prompt, validator, clock);
    }
}
