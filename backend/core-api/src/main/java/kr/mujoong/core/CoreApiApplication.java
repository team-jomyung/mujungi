package kr.mujoong.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Core 앱 진입점 (U1 3.5 Step 3 · ADR-002).
 *
 * <p>보호자 REST · 기기 REST · 내부 API · Core Outbox/Inbox를 맡는다. 설정은
 * {@link kr.mujoong.core.config.CoreProperties}(mujoong.* 트리)로 묶어 기동 때 검증한다(RD-24).
 */
@SpringBootApplication
public class CoreApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreApiApplication.class, args);
    }
}
