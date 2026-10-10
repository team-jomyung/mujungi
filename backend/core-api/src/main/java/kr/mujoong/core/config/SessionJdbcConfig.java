package kr.mujoong.core.config;

import java.util.Objects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.FlushMode;
import org.springframework.session.SaveMode;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * 보호자 세션 저장소 Spring Session JDBC 설정 (U1 3.5 Step 15 · SD-5 · NFR9.2 · J115).
 *
 * <p>표는 Core V1이 만든 {@code core.core_spring_session(_attributes)}이고 앱은 표를 만들지 않는다
 * ({@code spring.session.jdbc.initialize-schema=never}). 이 설정은 Boot 기본 설정(속성 적용) 뒤에 한 번 더 적용되어
 *
 * <ul>
 *   <li>표 이름을 스키마로 한정한다(core_app의 search_path가 바뀌어도 다른 스키마의 같은 이름 표를 쓰지 않는다),
 *   <li>서버 유휴 만료를 타입 있는 설정 {@code mujoong.timing.login.session-idle}(30일)에서 정하고(RD-24 · BR8.11),
 *   <li>요청 끝에 한 번만 쓰고 바뀐 속성만 쓴다(ON_SAVE · ON_SET_ATTRIBUTE — 세션에는 보호자 id · 권한 · CSRF 토큰만 둔다, SD-5).
 * </ul>
 *
 * <p>세션 만료 정리는 프레임워크 정리 주기가 한다(DELETE만 필요, SD-13 기본 권한 DML).
 */
@Configuration(proxyBeanMethods = false)
public class SessionJdbcConfig {

    /** Spring Session 본 표(스키마 한정). 속성 표는 {@code <이 이름>_attributes}다. */
    public static final String TABLE_NAME = "core.core_spring_session";

    @Bean
    SessionRepositoryCustomizer<JdbcIndexedSessionRepository> mujoongSessionRepositoryCustomizer(CoreProperties properties) {
        return customizer(properties);
    }

    /** 저장소에 무중 규칙을 건다(시험이 직접 부른다). */
    public static SessionRepositoryCustomizer<JdbcIndexedSessionRepository> customizer(CoreProperties properties) {
        Objects.requireNonNull(properties, "properties");
        return repository -> {
            repository.setTableName(TABLE_NAME);
            repository.setDefaultMaxInactiveInterval(properties.timing().login().sessionIdle());
            repository.setFlushMode(FlushMode.ON_SAVE);
            repository.setSaveMode(SaveMode.ON_SET_ATTRIBUTE);
        };
    }
}
