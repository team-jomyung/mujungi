package kr.mujoong.core.practice;

import java.time.Duration;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:u5a-practice.properties")
public class ScenarioConfiguration {
    @Bean @ConditionalOnMissingBean(PracticeSessionContextPort.class)
    PracticeSessionContextPort practiceSessionContextPort() {
        return id -> { throw CoreApiException.of(ErrorCode.SERVICE_NOT_READY); };
    }
    @Bean @ConditionalOnMissingBean(ScenarioRecordPort.class)
    ScenarioRecordPort scenarioRecordPort() {
        return id -> { throw CoreApiException.of(ErrorCode.SERVICE_NOT_READY); };
    }
    @Bean
    ScenarioLimits scenarioLimits(Environment environment) {
        Duration timeout = Binder.get(environment).bind("mujoong.timing.practice.scenario-request-timeout", Duration.class)
                .orElseThrow(() -> new IllegalStateException("Scenario timeout is missing"));
        return new ScenarioLimits(timeout);
    }
    public record ScenarioLimits(Duration requestTimeout) {
        public ScenarioLimits {
            if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
                throw new IllegalArgumentException("scenario request timeout must be positive");
            }
        }
    }
}
