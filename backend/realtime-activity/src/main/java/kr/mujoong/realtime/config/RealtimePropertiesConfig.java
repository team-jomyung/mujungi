package kr.mujoong.realtime.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * {@link RealtimeProperties}를 등록하고 표준 키 쪽 기동 검증을 한다 (U1 3.5 Step 3 · RD-24 · NFR7.6 · I-7).
 *
 * <p>DB 풀: 두 앱 합계 20 이하(I-7)를 앱마다 10 이하로 나눠 지킨다. 키가 없으면 Hikari 기본값(10)이다.
 * 이 빈은 웹 서버가 연결을 받기 전(컨텍스트 새로 고침의 단일 빈 생성 단계)에 검증하므로 위반이면 포트가 열리지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RealtimeProperties.class)
public class RealtimePropertiesConfig {

    static final String POOL_SIZE_KEY = "spring.datasource.hikari.maximum-pool-size";
    /** NFR7.6 · I-7: 앱당 풀 상한(두 앱 합계 20). */
    static final int MAX_POOL_SIZE_PER_APP = 10;

    public RealtimePropertiesConfig(Environment environment) {
        checkPoolSize(environment.getProperty(POOL_SIZE_KEY, Integer.class));
    }

    static void checkPoolSize(Integer configured) {
        int poolSize = configured == null ? MAX_POOL_SIZE_PER_APP : configured;
        if (poolSize < 1 || poolSize > MAX_POOL_SIZE_PER_APP) {
            throw new IllegalArgumentException(
                    "설정 키 " + POOL_SIZE_KEY + "는 1 이상 " + MAX_POOL_SIZE_PER_APP + " 이하여야 한다(두 앱 합계 20, I-7)");
        }
    }
}
