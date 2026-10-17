package kr.mujoong.realtime.guardrail;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 제브 게이트 빈 (U13). 설정 {@code mujoong.guardrail.jev.*}를 묶는다. 모드별 클라이언트 · 장치 빈은 포트와 대역이 생기는 단계에서 더한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GuardrailProperties.class)
public class GuardrailConfiguration {
}
