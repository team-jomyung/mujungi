package kr.mujoong.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Core 앱 진입점 (U1 3.5 Step 3 · ADR-002).
 *
 * <p>보호자 REST · 기기 REST · 내부 API · Core Outbox/Inbox를 맡는다. 설정은
 * {@link kr.mujoong.core.config.CoreProperties}(mujoong.* 트리)로 묶어 기동 때 검증한다(RD-24).
 *
 * <p>Spring Security의 기본 메모리 사용자(기동 때 임시 비밀번호를 로그에 쓴다)는 만들지 않는다: 비밀번호 로그인 경로가 없고(BR8.4) 인증은
 * 보호자 세션 · 기기 쿠키 · 내부 토큰 셋뿐이다(Step 31 SecurityConfig, SD-4 · SD-15).
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class CoreApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreApiApplication.class, args);
    }
}
