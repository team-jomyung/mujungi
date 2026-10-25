package kr.mujoong.realtime.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 켜기·끄기만 별도 기능 키로 관리한다. 시간·한도는 표준 이름 공간을 사용한다. */
@ConfigurationProperties("mujoong.ai-usage")
public record AiUsageProperties(boolean enabled) { }
