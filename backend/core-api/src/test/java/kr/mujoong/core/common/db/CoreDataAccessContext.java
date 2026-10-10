package kr.mujoong.core.common.db;

import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.catalog.ApprovedContentRepository;
import kr.mujoong.core.config.CorePropertiesConfig;
import kr.mujoong.core.device.DeviceJdbcRepository;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.messaging.CoreInboxRepository;
import kr.mujoong.core.messaging.CoreOutboxRepository;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Step 15 데이터 접근 빈만 올린 Spring 문맥(시험 소스 전용). 운영과 같은 {@code application.yml} + {@code test} 프로필을 읽고 core_app으로
 * 공용 DB에 붙는다. 다른 Step의 빈(웹 · 보안 · 업무 서비스)에 묶이지 않게 데이터 접근 빈과 설정 검증만 가져온다.
 */
public final class CoreDataAccessContext {

    private CoreDataAccessContext() {
    }

    /** 데이터 접근 빈 문맥 실행기. */
    public static ApplicationContextRunner runner(MujoongPostgres postgres) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class,
                        TransactionAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class))
                .withUserConfiguration(DataAccessBeans.class)
                .withPropertyValues(
                        "spring.profiles.active=test",
                        "spring.datasource.url=" + postgres.jdbcUrl(),
                        "spring.datasource.username=" + postgres.username(Role.CORE_APP),
                        "spring.datasource.password=" + postgres.password(Role.CORE_APP));
    }

    /** Step 15가 만든 빈과 JPA 저장소 · 엔터티. */
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackages = "kr.mujoong.core")
    @EnableJpaRepositories(basePackageClasses = {GuardianRepository.class, ApprovedContentRepository.class})
    @Import({
        CorePropertiesConfig.class,
        DbRetry.class,
        DbSessionLimits.class,
        DeviceJdbcRepository.class,
        ActivityCopyRepository.class,
        CoreOutboxRepository.class,
        CoreInboxRepository.class
    })
    static class DataAccessBeans {
    }
}
