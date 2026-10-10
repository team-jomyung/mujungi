package kr.mujoong.core.support;

import java.net.URI;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.catalog.ApprovedContentRepository;
import kr.mujoong.core.common.db.DbRetry;
import kr.mujoong.core.common.db.DbSessionLimits;
import kr.mujoong.core.config.CorePropertiesConfig;
import kr.mujoong.core.config.CoreRuntimeConfig;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.startup.RequiredSecretsCheck;
import kr.mujoong.core.startup.SchemaVersionCheck;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Core 업무 로직 빈(기기 · 활동 · 메시징 · 내부 HTTP · 준비 상태 · 관측)을 실제 DB와 함께 올린 Spring 문맥(시험 소스 전용, Step 28). 웹 ·
 * 보안 · Spring Session · 기동 순서(SmartLifecycle)는 올리지 않는다 — 스케줄러 없이 시험이 각 단계를 직접 부른다. 시각은 시험이 준
 * 가변 Clock 빈이고, 상대 앱은 {@code realtimeBaseUrl}(계약 대역)이다. 내부 토큰은 실행마다 만든 값이다.
 */
public final class CoreBusinessContext {

    private CoreBusinessContext() {
    }

    /** 업무 빈 문맥 실행기. */
    public static ApplicationContextRunner runner(
            MujoongPostgres postgres, MujoongClock clock, URI realtimeBaseUrl, String internalToken) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class,
                        TransactionAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class))
                .withBean(MujoongClock.class, () -> clock)
                .withUserConfiguration(BusinessBeans.class)
                .withPropertyValues(
                        "spring.profiles.active=test",
                        "spring.datasource.url=" + postgres.jdbcUrl(),
                        "spring.datasource.username=" + postgres.username(Role.CORE_APP),
                        "spring.datasource.password=" + postgres.password(Role.CORE_APP),
                        RequiredSecretsCheck.INTERNAL_TOKEN + "=" + internalToken,
                        "mujoong.internal-http.realtime-base-url=" + realtimeBaseUrl);
    }

    /**
     * 업무 패키지 스캔(기동 순서 · 웹 계층 제외: 하위 {@code *.web} 패키지의 컨트롤러는 Step 31 · 32 보안 · 쿠키 빈이 있어야 해 뺀다). {@code @TestConfiguration}이라 뒤 단계의 {@code @SpringBootTest}가 앱 패키지를 스캔해도
     * 이 설정을 집어 가지 않는다(TestTypeExcludeFilter).
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EntityScan(basePackages = "kr.mujoong.core")
    @EnableJpaRepositories(basePackageClasses = {GuardianRepository.class, ApprovedContentRepository.class})
    @ComponentScan(basePackages = {
        "kr.mujoong.core.device",
        "kr.mujoong.core.activity",
        "kr.mujoong.core.messaging",
        "kr.mujoong.core.internalhttp",
        "kr.mujoong.core.health",
        "kr.mujoong.core.observability",
        "kr.mujoong.core.security.ratelimit"
    }, excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "kr\\.mujoong\\.core\\..*\\.web\\..*"))
    @Import({
        CorePropertiesConfig.class,
        CoreRuntimeConfig.class,
        DbRetry.class,
        DbSessionLimits.class,
        RequiredSecretsCheck.class,
        SchemaVersionCheck.class
    })
    static class BusinessBeans {
    }
}
