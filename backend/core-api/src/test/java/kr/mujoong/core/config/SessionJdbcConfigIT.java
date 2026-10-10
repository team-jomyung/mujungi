package kr.mujoong.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.session.autoconfigure.SessionAutoConfiguration;
import org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * {@link SessionJdbcConfig} 통합 시험(U1 3.5 Step 17 · SD-5 · NFR9.2 · J115). Boot 자동 설정(Spring Session JDBC)과 함께 실제 DB의
 * {@code core.core_spring_session}에 보호자 세션을 쓴다.
 *
 * <p>연결의 search_path를 일부러 public으로 바꿔도 세션이 core 스키마에 저장되는지로 표 이름 한정이 Boot 기본 설정 뒤에 적용됨을 증명한다.
 * 서버 유휴 만료는 타입 있는 설정 {@code mujoong.timing.login.session-idle}(30일)이다.
 */
@Tag("u1")
@Tag("db")
class SessionJdbcConfigIT {

    private static final Duration SESSION_IDLE = Duration.ofDays(30);

    private static MujoongPostgres postgres;

    @BeforeAll
    static void startDatabase() {
        postgres = MujoongPostgres.sharedOrAbort();
    }

    @Test
    void guardianSessionsAreStoredInTheCoreSchemaWithTheConfiguredIdleExpiry() {
        runner(true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(JdbcIndexedSessionRepository.class);
            SessionRepository<Session> repository = sessions(context);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("show search_path", String.class)).isEqualTo("public");

            Session session = repository.createSession();
            session.setAttribute("guardianId", "00000000-0000-0000-0000-000000000011");
            repository.save(session);
            try {
                Session loaded = repository.findById(session.getId());
                assertThat(loaded).isNotNull();
                assertThat(loaded.<String>getAttribute("guardianId")).isEqualTo("00000000-0000-0000-0000-000000000011");
                assertThat(loaded.getMaxInactiveInterval()).isEqualTo(SESSION_IDLE);

                List<Map<String, Object>> rows = jdbc.queryForList(
                        "select max_inactive_interval from core.core_spring_session where session_id = ?", session.getId());
                assertThat(rows).hasSize(1);
                assertThat(((Number) rows.get(0).get("max_inactive_interval")).longValue())
                        .isEqualTo(SESSION_IDLE.toSeconds());
                assertThat(jdbc.queryForObject("select count(*) from core.core_spring_session_attributes a"
                        + " join core.core_spring_session s on s.primary_id = a.session_primary_id where s.session_id = ?",
                        Long.class, session.getId())).isEqualTo(1L);
            } finally {
                repository.deleteById(session.getId());
            }
            assertThat(repository.findById(session.getId())).isNull();
            assertThat(jdbc.queryForObject("select count(*) from core.core_spring_session where session_id = ?", Long.class,
                    session.getId())).isZero();
        });
    }

    @Test
    void withoutTheSchemaQualifiedTableTheSameSetupCannotReachTheSessionTable() {
        // 대조군: Boot 기본 설정만(표 이름 core_spring_session, 스키마 한정 없음)이면 search_path가 바뀐 연결에서 표를 찾지 못한다
        runner(false).run(context -> {
            assertThat(context).hasNotFailed();
            SessionRepository<Session> repository = sessions(context);
            Session session = repository.createSession();
            session.setAttribute("guardianId", "x");
            assertThatThrownBy(() -> repository.save(session)).isInstanceOf(RuntimeException.class);
        });
    }

    @Test
    void customizerRequiresProperties() {
        assertThatThrownBy(() -> SessionJdbcConfig.customizer(null)).isInstanceOf(NullPointerException.class);
        assertThat(SessionJdbcConfig.TABLE_NAME).isEqualTo("core.core_spring_session");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SessionRepository<Session> sessions(ApplicationContext context) {
        return (SessionRepository) context.getBean(JdbcIndexedSessionRepository.class);
    }

    private static WebApplicationContextRunner runner(boolean withSessionJdbcConfig) {
        Class<?>[] userConfiguration = withSessionJdbcConfig
                ? new Class<?>[] {CorePropertiesConfig.class, SessionJdbcConfig.class}
                : new Class<?>[] {CorePropertiesConfig.class};
        return new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        DataSourceTransactionManagerAutoConfiguration.class,
                        TransactionAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class,
                        SessionAutoConfiguration.class,
                        JdbcSessionAutoConfiguration.class))
                .withUserConfiguration(userConfiguration)
                .withPropertyValues(
                        "spring.profiles.active=test",
                        "spring.datasource.url=" + postgres.jdbcUrl(),
                        "spring.datasource.username=" + postgres.username(Role.CORE_APP),
                        "spring.datasource.password=" + postgres.password(Role.CORE_APP),
                        "spring.datasource.hikari.connection-init-sql=select set_config('search_path', 'public', false)",
                        "spring.session.jdbc.cleanup-cron=-");
    }
}
