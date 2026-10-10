package kr.mujoong.core.config;

import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.SystemMujoongClock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Core 실행 공통 빈 (U1 3.5 Step 21 · RD-2 · NFR6.4 · J139).
 *
 * <ul>
 *   <li>{@link MujoongClock}: 모든 시각 읽기의 유일한 원천. 운영은 시스템 UTC이고 시험 문맥은 가변 Clock 빈으로 바꿔 끼운다(빈이 이미
 *       있으면 만들지 않는다).
 *   <li>{@link JsonCodec}: 계약 JSON 읽기 · 쓰기 규칙(모르는 필드 무시 · 모르는 enum '알 수 없음' · ISO 8601 UTC). 앱 코드의 Jackson
 *       직접 사용은 이 코덱 한 곳으로 모은다.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class CoreRuntimeConfig {

    @Bean
    @ConditionalOnMissingBean(MujoongClock.class)
    MujoongClock mujoongClock() {
        return new SystemMujoongClock();
    }

    @Bean
    @ConditionalOnMissingBean(JsonCodec.class)
    JsonCodec jsonCodec() {
        return JsonCodec.shared();
    }
}
