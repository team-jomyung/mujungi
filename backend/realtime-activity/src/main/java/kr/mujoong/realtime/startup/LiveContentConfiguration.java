package kr.mujoong.realtime.startup;

import kr.mujoong.realtime.content.LiveContentRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 세션 콘텐츠 계획 빈 (U8 functional-spec 8.3 · SD5). 기동 점검 S1b가 검증을 마친 판 리소스로 {@link LiveContentRegistry}를 만든다 — 판
 * 리소스 적재가 실패하면 점검 빈이 먼저 기동을 거부하므로, 이 빈은 늘 검증된 판만 받는다.
 */
@Configuration(proxyBeanMethods = false)
public class LiveContentConfiguration {

    @Bean
    LiveContentRegistry liveContentRegistry(RealtimeStartupChecks checks) {
        return new LiveContentRegistry(checks.liveContent());
    }
}
