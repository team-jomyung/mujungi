package kr.mujoong.realtime.usage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AiUsageProperties.class, AiUsageTimingProperties.class, AiUsageLimitsProperties.class})
public class AiUsageConfiguration { }
