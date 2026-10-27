package kr.mujoong.core.usage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AiUsageProperties.class, AiUsageProperties.Timing.class, AiUsageProperties.Limits.class})
public class AiUsageConfiguration {
}
