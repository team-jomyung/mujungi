package kr.mujoong.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
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
 * 서버 유휴 만료는 타입 있는 설정 {@code mujoong.timing.login.session-idle}(30일)이다. 3.6 수정 분량: 그 30일이 실제 만료 판정을 정한다(만료
 * 직전은 읽히고 지난 세션은 읽을 때 지워진다), 쓰기는 save 때 한 번이고(ON_SAVE) 바꾼 속성만 쓴다(ON_SET_ATTRIBUTE — 같은 세션의 겹친
 * 요청이 읽기만 한 속성을 낡은 값으로 덮지 않는다).
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

    @Test
    void theConfiguredIdleDecidesExpiryAndExpiredSessionsAreRemovedOnRead() {
        runner(true).run(context -> {
            SessionRepository<Session> repository = sessions(context);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            // 세션 저장소는 프레임워크의 시스템 시각으로 만료를 판정한다(운영 코드 밖)
            Instant now = Instant.now();
            Session almost = repository.createSession();
            almost.setAttribute("guardianId", "00000000-0000-0000-0000-000000000021");
            almost.setLastAccessedTime(now.minus(SESSION_IDLE).plus(Duration.ofMinutes(10)));
            repository.save(almost);
            Session expired = repository.createSession();
            expired.setAttribute("guardianId", "00000000-0000-0000-0000-000000000022");
            expired.setLastAccessedTime(now.minus(SESSION_IDLE).minus(Duration.ofMinutes(10)));
            repository.save(expired);
            try {
                assertThat(repository.findById(almost.getId())).as("유휴 30일 10분 전(기본 30분이었다면 이미 만료)").isNotNull();
                assertThat(repository.findById(expired.getId())).as("유휴 30일을 넘긴 세션").isNull();
                assertThat(jdbc.queryForObject("select count(*) from core.core_spring_session where session_id = ?", Long.class,
                        expired.getId())).as("만료 세션은 읽을 때 지운다").isZero();
            } finally {
                repository.deleteById(almost.getId());
                repository.deleteById(expired.getId());
            }
        });
    }

    @Test
    void attributesAreWrittenOnSaveOnlyAndAStaleCopyDoesNotOverwriteWhatItOnlyRead() {
        runner(true).run(context -> {
            SessionRepository<Session> repository = sessions(context);
            Session created = repository.createSession();
            created.setAttribute("guardianId", "old");
            repository.save(created);
            String id = created.getId();
            try {
                Session first = repository.findById(id);
                Session second = repository.findById(id);
                assertThat(second.<String>getAttribute("guardianId")).isEqualTo("old");

                first.setAttribute("guardianId", "new");
                assertThat(repository.findById(id).<String>getAttribute("guardianId")).as("save 전에는 쓰지 않는다(ON_SAVE)")
                        .isEqualTo("old");
                repository.save(first);
                second.setAttribute("csrf", "token");
                repository.save(second);

                Session merged = repository.findById(id);
                assertThat(merged.<String>getAttribute("guardianId")).as("늦게 저장한 사본이 읽기만 한 속성을 덮지 않는다")
                        .isEqualTo("new");
                assertThat(merged.<String>getAttribute("csrf")).isEqualTo("token");
            } finally {
                repository.deleteById(id);
            }
        });
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
