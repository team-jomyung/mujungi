package kr.mujoong.core.notification.transport;

import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.config.CoreProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class PushTransportConfiguration {
    @Bean
    PushVapidSettings pushVapidSettings(Environment environment) {
        return PushVapidSettings.load(environment);
    }

    @Bean(destroyMethod = "close")
    PinnedPushHttpClient pushHttpClient(CoreProperties properties) {
        return new PinnedPushHttpClient(properties.timing().push().sendTimeout());
    }

    @Bean
    @ConditionalOnMissingBean(PushDeliverySender.class)
    PushDeliverySender pushDeliverySender(
            PushVapidSettings settings, PinnedPushHttpClient http, JsonCodec json) {
        return new VapidPushSender(settings, http, json);
    }
}
