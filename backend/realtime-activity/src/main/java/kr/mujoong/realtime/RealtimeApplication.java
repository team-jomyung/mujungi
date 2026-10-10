package kr.mujoong.realtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Realtime 앱 진입점 (U1 3.5 Step 3 · ADR-002).
 *
 * <p>기기 WSS · 세션 상태 기계 · 공급자 중계 · 출력 게이트 · 전사 기록 · Realtime Outbox/Inbox를 맡는다.
 * 설정은 {@link kr.mujoong.realtime.config.RealtimeProperties}(mujoong.* 트리)로 묶어 기동 때 검증한다(RD-24).
 */
@SpringBootApplication
public class RealtimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealtimeApplication.class, args);
    }
}
