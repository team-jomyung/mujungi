package kr.mujoong.testsupport.db;

import com.github.dockerjava.api.model.Network;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.sql.DataSource;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 시험용 PostgreSQL 16(J132 · J135 · SD-13 · SD-25). 운영과 같은 {@code infra/postgres/init/} 스크립트와 설정 템플릿으로 DB를 만들고,
 * 두 위치(core · realtime)에 Flyway를 적용하고, 역할별 DataSource를 준다.
 *
 * <p>백엔드(시스템 속성 {@value #BACKEND_PROPERTY}, Gradle {@code -Pmujoong.test.db=...}):
 *
 * <ul>
 *   <li>{@code docker}: 고정 태그 {@value #IMAGE} 컨테이너(Testcontainers). 공식 entrypoint가 init 스크립트를 돌린다. pg_hba 허용 대역은
 *       컨테이너가 보는 시험 출발지인 브리지 게이트웨이 /32다(J135).
 *   <li>{@code local}: 호스트 {@value #DEFAULT_PG_BIN}의 initdb · pg_ctl로 임시 클러스터를 만든다. root이면 {@code runuser -u postgres}로
 *       돌린다. 허용 대역은 127.0.0.1/32다. JVM 종료 훅(또는 {@link #close()})에서 멈추고 지운다.
 *   <li>{@code auto}(기본): Docker가 되면 docker, 안 되면 local. 둘 다 없으면 {@link #sharedOrAbort()}가 시험을 건너뛴다(Step 14).
 * </ul>
 *
 * <p>비밀번호는 실행마다 {@link TestSecrets}로 만들고 파일 · 로그 · 예외 메시지에 남기지 않는다(J127 · NFR9.10).
 */
public final class MujoongPostgres implements AutoCloseable {

    /** docker 백엔드 이미지(고정 태그, tech-stack). */
    public static final String IMAGE = "postgres:16.15";
    /** 앱 데이터베이스 이름(01-roles.sql). */
    public static final String DATABASE = "mujoong";
    /** 백엔드 선택 시스템 속성(auto · docker · local). */
    public static final String BACKEND_PROPERTY = "mujoong.test.db";
    /** 저장소 루트 시스템 속성(java-conventions가 넘긴다). */
    public static final String REPO_ROOT_PROPERTY = "mujoong.repo.root";
    /** local 백엔드 바이너리 위치 시스템 속성. */
    public static final String PG_BIN_PROPERTY = "mujoong.test.pg-bin";
    /** local 백엔드 기본 바이너리 위치. */
    public static final String DEFAULT_PG_BIN = "/usr/lib/postgresql/16/bin";
    /** 실제로 쓴 백엔드 이름을 남기는 시스템 속성(시험 결과 보고용). */
    public static final String USED_BACKEND_PROPERTY = "mujoong.test.db.used";
    /** Flyway 이력 표 스키마(J116). */
    public static final String FLYWAY_SCHEMA = "flyway";

    private static final String CONTAINER_CONF = "/etc/postgresql/mujoong.conf";
    private static final String CONTAINER_PGDATA = "/var/lib/postgresql/data";
    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(2);

    private static MujoongPostgres shared;

    /** 쓴 백엔드. */
    public enum Backend {
        DOCKER("docker"),
        LOCAL("local");

        private final String wireName;

        Backend(String wireName) {
            this.wireName = wireName;
        }

        /** 보고용 이름(docker · local). */
        public String wireName() {
            return wireName;
        }
    }

    /** 백엔드 선택. */
    public enum Selection {
        AUTO,
        DOCKER,
        LOCAL;

        /** 시스템 속성 {@value #BACKEND_PROPERTY}를 읽는다(없으면 AUTO). */
        public static Selection fromSystemProperty() {
            String value = System.getProperty(BACKEND_PROPERTY, "auto").trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "", "auto" -> AUTO;
                case "docker" -> DOCKER;
                case "local" -> LOCAL;
                default -> throw new IllegalArgumentException(
                        BACKEND_PROPERTY + "는 auto · docker · local 중 하나여야 한다: " + value);
            };
        }
    }

    /** DB 역할(SD-13 · 01-roles.sql). */
    public enum Role {
        SUPERUSER("postgres"),
        OWNER("mujoong_owner"),
        CORE_APP("core_app"),
        REALTIME_APP("realtime_app"),
        BACKUP("mujoong_backup");

        private final String roleName;

        Role(String roleName) {
            this.roleName = roleName;
        }

        /** PostgreSQL 역할 이름. */
        public String roleName() {
            return roleName;
        }
    }

    /** 마이그레이션 위치(J116). 이력 표는 {@code flyway.<앱>_schema_history}다. */
    public enum MigrationSet {
        CORE("backend/core-api/src/main/resources/db/migration/core", "core", "core_schema_history"),
        REALTIME("backend/realtime-activity/src/main/resources/db/migration/realtime", "realtime", "realtime_schema_history");

        private final String relativeLocation;
        private final String appSchema;
        private final String historyTable;

        MigrationSet(String relativeLocation, String appSchema, String historyTable) {
            this.relativeLocation = relativeLocation;
            this.appSchema = appSchema;
            this.historyTable = historyTable;
        }

        /** 이 위치가 관리하는 앱 스키마(core · realtime). */
        public String appSchema() {
            return appSchema;
        }

        /** 저장소 루트 기준 위치. */
        public String relativeLocation() {
            return relativeLocation;
        }

        /** 이력 표 이름(스키마 {@value #FLYWAY_SCHEMA}). */
        public String historyTable() {
            return historyTable;
        }
    }

    /** 요청한 백엔드를 쓸 수 없을 때. */
    public static final class BackendUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BackendUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Backend backend;
    private final Server server;
    private final TestSecrets.DatabasePasswords passwords;
    private final String hbaAllowedCidr;
    private final Path repoRoot;
    private final Map<Role, HikariDataSource> dataSources = new EnumMap<>(Role.class);
    private boolean closed;

    private MujoongPostgres(
            Backend backend, Server server, TestSecrets.DatabasePasswords passwords, String hbaAllowedCidr, Path repoRoot) {
        this.backend = backend;
        this.server = server;
        this.passwords = passwords;
        this.hbaAllowedCidr = hbaAllowedCidr;
        this.repoRoot = repoRoot;
    }

    /** JVM 하나에 하나 띄우고 두 위치 마이그레이션까지 적용한 공용 인스턴스. JVM 종료 때 멈춘다. */
    public static synchronized MujoongPostgres shared() {
        if (shared == null || shared.closed) {
            MujoongPostgres started = builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(started::close, "mujoong-postgres-shutdown"));
            shared = started;
        }
        return shared;
    }

    /**
     * {@link #shared()}와 같다. 다만 선택이 auto이고 Docker · 호스트 PostgreSQL이 둘 다 없으면 시험을 건너뛴다(JUnit 가정 실패). 명시한
     * 백엔드를 못 쓰면 건너뛰지 않고 실패한다.
     */
    public static MujoongPostgres sharedOrAbort() {
        if (Selection.fromSystemProperty() == Selection.AUTO && availableBackend().isEmpty()) {
            Assumptions.abort("DB 시험 건너뜀: Docker와 호스트 PostgreSQL(" + pgBin() + ")이 둘 다 없다(J132)");
        }
        return shared();
    }

    /** 지금 쓸 수 있는 백엔드(auto 순서: docker → local). */
    public static Optional<Backend> availableBackend() {
        if (isDockerAvailable()) {
            return Optional.of(Backend.DOCKER);
        }
        if (isLocalAvailable()) {
            return Optional.of(Backend.LOCAL);
        }
        return Optional.empty();
    }

    /** Docker 데몬에 닿는가. */
    public static boolean isDockerAvailable() {
        quietDockerClientLogging();
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 호스트 PostgreSQL 16 바이너리(와 root이면 runuser · postgres 사용자)가 있는가. */
    public static boolean isLocalAvailable() {
        Path bin = pgBin();
        for (String tool : List.of("initdb", "pg_ctl", "postgres", "psql")) {
            if (!Files.isExecutable(bin.resolve(tool))) {
                return false;
            }
        }
        if (!isRoot()) {
            return true;
        }
        if (runuser().isEmpty()) {
            return false;
        }
        try {
            FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName("postgres");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 새 인스턴스 작성기(공용이 아닌 별도 인스턴스가 필요한 시험용, 예: J135 거부 확인). */
    public static Builder builder() {
        return new Builder();
    }

    /** 작성기. */
    public static final class Builder {
        private Selection selection = Selection.fromSystemProperty();
        private String hbaAllowedCidr;
        private boolean migrate = true;

        private Builder() {
        }

        /** 백엔드 선택(기본: 시스템 속성). */
        public Builder selection(Selection value) {
            this.selection = Objects.requireNonNull(value, "selection");
            return this;
        }

        /** pg_hba 허용 대역(기본: 백엔드의 시험 출발지 /32). 거부 시험은 출발지가 아닌 /32를 준다(J135). */
        public Builder hbaAllowedCidr(String value) {
            this.hbaAllowedCidr = Objects.requireNonNull(value, "hbaAllowedCidr");
            return this;
        }

        /** 두 위치 마이그레이션을 적용할지(기본 true). */
        public Builder migrate(boolean value) {
            this.migrate = value;
            return this;
        }

        /** 띄운다. 비밀번호는 매번 새로 만든다. */
        public MujoongPostgres start() {
            Path repoRoot = locateRepoRoot();
            TestSecrets.DatabasePasswords passwords = TestSecrets.DatabasePasswords.generate();
            MujoongPostgres postgres = switch (selection) {
                case DOCKER -> startDocker(repoRoot, passwords, hbaAllowedCidr);
                case LOCAL -> startLocal(repoRoot, passwords, hbaAllowedCidr);
                case AUTO -> startAuto(repoRoot, passwords, hbaAllowedCidr);
            };
            System.setProperty(USED_BACKEND_PROPERTY, postgres.backend().wireName());
            System.out.println("[MujoongPostgres] backend=" + postgres.backend().wireName() + " port=" + postgres.port()
                    + " hba=" + postgres.hbaAllowedCidr() + (postgres.backend() == Backend.DOCKER ? " image=" + IMAGE : ""));
            if (migrate) {
                try {
                    postgres.applyMigrations();
                } catch (RuntimeException e) {
                    postgres.close();
                    throw e;
                }
            }
            return postgres;
        }

        private static MujoongPostgres startAuto(
                Path repoRoot, TestSecrets.DatabasePasswords passwords, String hbaAllowedCidr) {
            RuntimeException dockerFailure = null;
            if (isDockerAvailable()) {
                try {
                    return startDocker(repoRoot, passwords, hbaAllowedCidr);
                } catch (RuntimeException e) {
                    dockerFailure = e;
                    System.err.println("[MujoongPostgres] docker 백엔드 실패, local로 넘어간다: " + e.getClass().getSimpleName());
                }
            }
            if (isLocalAvailable()) {
                return startLocal(repoRoot, passwords, hbaAllowedCidr);
            }
            throw new BackendUnavailableException(
                    "DB 시험 백엔드가 없다: Docker " + (dockerFailure == null ? "없음" : "실패") + ", 호스트 PostgreSQL(" + pgBin()
                            + ") 없음(J132)",
                    dockerFailure);
        }
    }

    /** 쓴 백엔드. */
    public Backend backend() {
        return backend;
    }

    /** 쓴 백엔드 이름(docker · local). */
    public String backendName() {
        return backend.wireName();
    }

    /** 접속 호스트. */
    public String host() {
        return server.host();
    }

    /** 접속 포트. */
    public int port() {
        return server.port();
    }

    /** pg_hba 허용 대역(02-hba.sh에 넘긴 값). */
    public String hbaAllowedCidr() {
        return hbaAllowedCidr;
    }

    /** {@value #DATABASE} 데이터베이스 JDBC URL. */
    public String jdbcUrl() {
        return jdbcUrl(DATABASE);
    }

    /** 다른 데이터베이스(예: postgres)의 JDBC URL. */
    public String jdbcUrl(String database) {
        return "jdbc:postgresql://" + host() + ":" + port() + "/" + database;
    }

    /** 역할 이름. */
    public String username(Role role) {
        return role.roleName();
    }

    /** 역할 비밀번호(이번 실행에서 만든 값). 로그 · 단언 메시지에 넣지 않는다. */
    public String password(Role role) {
        return switch (role) {
            case SUPERUSER -> passwords.superuser();
            case OWNER -> passwords.owner();
            case CORE_APP -> passwords.core();
            case REALTIME_APP -> passwords.realtime();
            case BACKUP -> passwords.backup();
        };
    }

    /** 역할별 DataSource(작은 Hikari 풀, 인스턴스와 함께 닫힌다). */
    public synchronized DataSource dataSource(Role role) {
        ensureOpen();
        return dataSources.computeIfAbsent(role, r -> {
            HikariConfig config = new HikariConfig();
            config.setPoolName("mujoong-test-" + r.roleName());
            config.setJdbcUrl(jdbcUrl());
            config.setUsername(r.roleName());
            config.setPassword(password(r));
            config.setMaximumPoolSize(4);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(Duration.ofSeconds(10).toMillis());
            return new HikariDataSource(config);
        });
    }

    /** 풀 없이 새 연결(호출자가 닫는다). */
    public Connection connect(Role role) throws SQLException {
        return connect(role, DATABASE);
    }

    /** 지정한 데이터베이스로 새 연결(호출자가 닫는다). */
    public Connection connect(Role role, String database) throws SQLException {
        ensureOpen();
        return DriverManager.getConnection(jdbcUrl(database), role.roleName(), password(role));
    }

    /** 02-hba.sh가 쓴 {@code $PGDATA/pg_hba.conf} 내용. */
    public String pgHbaConf() {
        ensureOpen();
        return server.pgHbaConf();
    }

    /** 저장소 루트. */
    public Path repoRoot() {
        return repoRoot;
    }

    /**
     * 한 위치의 Flyway(J116: 이력 표 {@code flyway.<앱>_schema_history}, defaultSchema {@value #FLYWAY_SCHEMA}, createSchemas false,
     * cleanDisabled true, mujoong_owner로 적용). 관리 스키마는 flyway와 앱 스키마이고, 이력 표 없는 비어 있지 않은 스키마는 버전 0
     * 기준선으로 시작한다(두 위치가 flyway 스키마를 함께 쓰기 때문). {@code extraLocations}는 시험 전용 위치(예: Step 14의 V2)다.
     */
    public Flyway flyway(MigrationSet set, String... extraLocations) {
        ensureOpen();
        List<String> locations = new ArrayList<>();
        locations.add("filesystem:" + repoRoot.resolve(set.relativeLocation()));
        locations.addAll(List.of(extraLocations));
        return Flyway.configure()
                .dataSource(jdbcUrl(), Role.OWNER.roleName(), password(Role.OWNER))
                .locations(locations.toArray(String[]::new))
                .defaultSchema(FLYWAY_SCHEMA)
                .schemas(FLYWAY_SCHEMA, set.appSchema())
                .table(set.historyTable())
                .createSchemas(false)
                .cleanDisabled(true)
                // 두 이력 표가 flyway 스키마 하나를 함께 쓰므로 두 번째 위치는 "비어 있지 않은 스키마 + 이력 표 없음"이 된다.
                // 버전 0 기준선만 남기고 V1부터 그대로 적용한다(J116 보완, J 후보: infra/migrate 설정도 같아야 한다)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    /** core → realtime 순서로 두 위치 마이그레이션을 적용한다. 위치가 아직 없으면 Flyway가 경고만 하고 넘어간다. */
    public void applyMigrations() {
        for (MigrationSet set : MigrationSet.values()) {
            flyway(set).migrate();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (HikariDataSource dataSource : dataSources.values()) {
            dataSource.close();
        }
        dataSources.clear();
        server.stop();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("이미 닫힌 MujoongPostgres다");
        }
    }

    // ------------------------------------------------------------------ 공통

    private interface Server {
        String host();

        int port();

        String pgHbaConf();

        void stop();
    }

    private static Path locateRepoRoot() {
        String property = System.getProperty(REPO_ROOT_PROPERTY);
        if (property != null && !property.isBlank()) {
            Path root = Path.of(property).toAbsolutePath().normalize();
            if (Files.isRegularFile(root.resolve("infra/postgres/init/01-roles.sh"))) {
                return root;
            }
            throw new IllegalStateException(REPO_ROOT_PROPERTY + "에 infra/postgres/init/01-roles.sh가 없다: " + root);
        }
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("infra/postgres/init/01-roles.sh"))) {
                return dir;
            }
        }
        throw new IllegalStateException("저장소 루트를 찾지 못했다(" + REPO_ROOT_PROPERTY + " 미설정, infra/postgres/init 없음)");
    }

    private static Path pgBin() {
        return Path.of(System.getProperty(PG_BIN_PROPERTY, DEFAULT_PG_BIN));
    }

    private static boolean isRoot() {
        return "root".equals(System.getProperty("user.name"));
    }

    private static Optional<Path> runuser() {
        return Stream.of("/usr/sbin/runuser", "/sbin/runuser", "/usr/bin/runuser", "/bin/runuser")
                .map(Path::of)
                .filter(Files::isExecutable)
                .findFirst();
    }

    /** postgresql.conf.tmpl의 유일한 변수 {@code ${PG_LISTEN_ADDRESSES}}를 채운다(Step 9). */
    static String renderConf(Path repoRoot, String listenAddresses) {
        try {
            String template = Files.readString(repoRoot.resolve("infra/postgres/postgresql.conf.tmpl"), StandardCharsets.UTF_8);
            if (!template.contains("${PG_LISTEN_ADDRESSES}")) {
                throw new IllegalStateException("postgresql.conf.tmpl에 ${PG_LISTEN_ADDRESSES}가 없다");
            }
            return template.replace("${PG_LISTEN_ADDRESSES}", listenAddresses);
        } catch (IOException e) {
            throw new UncheckedIOException("postgresql.conf.tmpl을 읽지 못했다", e);
        }
    }

    private static String masked(String text, TestSecrets.DatabasePasswords passwords) {
        String result = text;
        for (String secret : List.of(
                passwords.superuser(), passwords.owner(), passwords.core(), passwords.realtime(), passwords.backup())) {
            result = result.replace(secret, "***");
        }
        return result;
    }

    // ------------------------------------------------------------------ docker 백엔드

    private static MujoongPostgres startDocker(
            Path repoRoot, TestSecrets.DatabasePasswords passwords, String requestedCidr) {
        if (!isDockerAvailable()) {
            throw new BackendUnavailableException("docker 백엔드를 골랐지만 Docker 데몬에 닿지 않는다(J132)", null);
        }
        quietDockerClientLogging();
        String cidr = requestedCidr != null ? requestedCidr : bridgeGatewayCidr();
        Path init = repoRoot.resolve("infra/postgres/init");
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withExposedPorts(5432)
                .withEnv("POSTGRES_PASSWORD", passwords.superuser())
                .withEnv("DB_OWNER_PASSWORD", passwords.owner())
                .withEnv("DB_CORE_PASSWORD", passwords.core())
                .withEnv("DB_REALTIME_PASSWORD", passwords.realtime())
                .withEnv("DB_BACKUP_PASSWORD", passwords.backup())
                .withEnv("PG_HBA_ALLOWED_CIDR", cidr)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(init.resolve("01-roles.sh"), 0755), "/docker-entrypoint-initdb.d/01-roles.sh")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(init.resolve("01-roles.sql"), 0644), "/docker-entrypoint-initdb.d/01-roles.sql")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(init.resolve("02-hba.sh"), 0755), "/docker-entrypoint-initdb.d/02-hba.sh")
                .withCopyToContainer(
                        Transferable.of(renderConf(repoRoot, "*").getBytes(StandardCharsets.UTF_8), 0644), CONTAINER_CONF)
                .withCommand("postgres", "-c", "config_file=" + CONTAINER_CONF)
                // 초기화용 임시 서버 한 번 + 본 서버 한 번
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        try {
            container.start();
        } catch (RuntimeException e) {
            String logs = "";
            try {
                logs = masked(container.getLogs(), passwords);
            } catch (RuntimeException ignored) {
                // 컨테이너가 만들어지지 않았으면 로그도 없다
            }
            container.stop();
            throw new IllegalStateException("docker 백엔드 기동 실패(" + IMAGE + ")" + (logs.isEmpty() ? "" : ":\n" + tail(logs)), e);
        }
        Server server = new Server() {
            @Override
            public String host() {
                return container.getHost();
            }

            @Override
            public int port() {
                return container.getMappedPort(5432);
            }

            @Override
            public String pgHbaConf() {
                return container.copyFileFromContainer(
                        CONTAINER_PGDATA + "/pg_hba.conf",
                        (InputStream in) -> new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }

            @Override
            public void stop() {
                container.stop();
            }
        };
        return new MujoongPostgres(Backend.DOCKER, server, passwords, cidr, repoRoot);
    }

    /**
     * Docker 클라이언트의 DEBUG 로그는 컨테이너 생성 요청 · inspect 응답을 그대로 찍어 환경 변수(DB 비밀번호)가 시험 출력에 남는다.
     * 수준을 따로 정하지 않은 이 로거들만 INFO로 올린다(NFR9.10 · SD-15). Logback이 아니면 아무것도 하지 않는다.
     */
    private static void quietDockerClientLogging() {
        if (!(org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext context)) {
            return;
        }
        for (String name : List.of(
                "com.github.dockerjava", "org.testcontainers.shaded.com.github.dockerjava", "org.testcontainers", "tc")) {
            ch.qos.logback.classic.Logger logger = context.getLogger(name);
            if (logger.getLevel() == null && logger.isDebugEnabled()) {
                logger.setLevel(ch.qos.logback.classic.Level.INFO);
            }
        }
    }

    /** 컨테이너가 보는 시험 출발지: 기본 bridge 망 게이트웨이 /32(J135). */
    private static String bridgeGatewayCidr() {
        Network bridge = DockerClientFactory.instance().client().inspectNetworkCmd().withNetworkId("bridge").exec();
        if (bridge.getIpam() != null && bridge.getIpam().getConfig() != null) {
            for (Network.Ipam.Config config : bridge.getIpam().getConfig()) {
                String gateway = config.getGateway();
                if (gateway != null && gateway.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                    return gateway + "/32";
                }
            }
        }
        throw new IllegalStateException("Docker bridge 망의 IPv4 게이트웨이를 찾지 못했다(J135)");
    }

    // ------------------------------------------------------------------ local 백엔드

    private static MujoongPostgres startLocal(
            Path repoRoot, TestSecrets.DatabasePasswords passwords, String requestedCidr) {
        if (!isLocalAvailable()) {
            throw new BackendUnavailableException(
                    "local 백엔드를 골랐지만 호스트 PostgreSQL 16(" + pgBin() + ")" + (isRoot() ? " · runuser · postgres 사용자" : "")
                            + "가 없다(J132)",
                    null);
        }
        String cidr = requestedCidr != null ? requestedCidr : "127.0.0.1/32";
        LocalCluster cluster = new LocalCluster(repoRoot, passwords, cidr);
        Thread hook = new Thread(cluster::stop, "mujoong-postgres-local-cleanup");
        Runtime.getRuntime().addShutdownHook(hook);
        cluster.shutdownHook = hook;
        try {
            cluster.start();
        } catch (RuntimeException e) {
            cluster.stop();
            throw e;
        }
        return new MujoongPostgres(Backend.LOCAL, cluster, passwords, cidr, repoRoot);
    }

    private static String tail(String text) {
        String[] lines = text.split("\n");
        int from = Math.max(0, lines.length - 40);
        return String.join("\n", List.of(lines).subList(from, lines.length));
    }

    /** 호스트 바이너리로 띄우는 임시 클러스터. */
    private static final class LocalCluster implements Server {
        private final Path repoRoot;
        private final TestSecrets.DatabasePasswords passwords;
        private final String cidr;
        private final Path bin = pgBin();
        private final boolean root = isRoot();
        private Path runDir;
        private Path dataDir;
        private int port;
        private boolean started;
        private boolean stopped;
        private Thread shutdownHook;

        LocalCluster(Path repoRoot, TestSecrets.DatabasePasswords passwords, String cidr) {
            this.repoRoot = repoRoot;
            this.passwords = passwords;
            this.cidr = cidr;
        }

        @Override
        public String host() {
            return "127.0.0.1";
        }

        @Override
        public int port() {
            return port;
        }

        void start() {
            try {
                runDir = Files.createDirectories(baseDir()).resolve("pg-" + UUID.randomUUID().toString().substring(0, 8));
                Files.createDirectory(runDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                dataDir = runDir.resolve("data");
                Path pwFile = runDir.resolve("pwfile");
                Files.writeString(pwFile, passwords.superuser() + "\n", StandardCharsets.UTF_8);
                Files.setPosixFilePermissions(pwFile, PosixFilePermissions.fromString("rw-------"));
                Path conf = runDir.resolve("postgresql.conf");
                Files.writeString(conf, renderConf(repoRoot, "127.0.0.1"), StandardCharsets.UTF_8);
                chownToPostgres(runDir, pwFile, conf);

                runAsPostgres(List.of(
                        bin.resolve("initdb").toString(),
                        "-D", dataDir.toString(),
                        "-U", "postgres",
                        "--auth=scram-sha-256",
                        "--pwfile=" + pwFile,
                        "--encoding=UTF8",
                        "--locale=C",
                        "--no-sync"), Map.of(), null);
                Files.deleteIfExists(pwFile);

                startServer(conf);
                started = true;
                runInitRoles();
                runWriteHba();
                reloadAndWait();
            } catch (IOException e) {
                throw new UncheckedIOException("local 백엔드 준비 실패", e);
            }
        }

        private Path baseDir() {
            Path build = Path.of("build").toAbsolutePath();
            return Files.isDirectory(build) ? build.resolve("tmp") : Path.of(System.getProperty("java.io.tmpdir"));
        }

        private void startServer(Path conf) {
            IllegalStateException last = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                port = freePort();
                // 유닉스 소켓은 쓰지 않는다(TCP 127.0.0.1만, pg_ctl -w는 postmaster.pid 상태로 기다린다)
                String options = "-c config_file='" + conf + "' -c port=" + port
                        + " -c listen_addresses=127.0.0.1 -c unix_socket_directories=''";
                try {
                    runAsPostgres(List.of(
                            bin.resolve("pg_ctl").toString(),
                            "-D", dataDir.toString(),
                            "-l", runDir.resolve("server.log").toString(),
                            "-w", "-t", "60",
                            "-o", options,
                            "start"), Map.of(), null);
                    return;
                } catch (IllegalStateException e) {
                    last = e;
                }
            }
            throw new IllegalStateException("local 백엔드 pg_ctl start 실패(포트 3회 시도): " + serverLogTail(), last);
        }

        private void runInitRoles() {
            Map<String, String> env = new LinkedHashMap<>();
            env.put("PATH", bin + ":" + System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
            env.put("PGHOST", "127.0.0.1");
            env.put("PGPORT", Integer.toString(port));
            env.put("PGPASSWORD", passwords.superuser());
            env.put("POSTGRES_USER", "postgres");
            env.put("DB_OWNER_PASSWORD", passwords.owner());
            env.put("DB_CORE_PASSWORD", passwords.core());
            env.put("DB_REALTIME_PASSWORD", passwords.realtime());
            env.put("DB_BACKUP_PASSWORD", passwords.backup());
            run(List.of("bash", repoRoot.resolve("infra/postgres/init/01-roles.sh").toString()), env, null);
        }

        private void runWriteHba() throws IOException {
            String script = Files.readString(repoRoot.resolve("infra/postgres/init/02-hba.sh"), StandardCharsets.UTF_8);
            // PGDATA는 postgres 소유(700)이므로 postgres로 쓴다. 스크립트는 표준 입력으로 넘긴다(저장소 경로 권한과 무관)
            runAsPostgres(List.of("bash", "-s"), Map.of("PGDATA", dataDir.toString(), "PG_HBA_ALLOWED_CIDR", cidr), script);
        }

        /**
         * pg_hba.conf를 다시 읽게 하고 적용될 때까지 기다린다(상한 10초). 새 허용 대역이 127.0.0.1이 아닐 수 있으므로(J135 거부 시험)
         * reload 전에 연 세션 하나로 {@code pg_conf_load_time()}이 바뀌는지 본다. 열린 세션은 SIGHUP을 받으면 이 값을 갱신하고,
         * postmaster는 자기 설정 · pg_hba를 다시 읽은 뒤 자식에게 신호를 넘긴다.
         */
        private void reloadAndWait() {
            String url = "jdbc:postgresql://127.0.0.1:" + port + "/postgres";
            try (Connection connection = DriverManager.getConnection(url, "postgres", passwords.superuser())) {
                Timestamp before = confLoadTime(connection);
                runAsPostgres(List.of(bin.resolve("pg_ctl").toString(), "-D", dataDir.toString(), "reload"), Map.of(), null);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline) {
                    if (confLoadTime(connection).after(before)) {
                        return;
                    }
                    TimeUnit.MILLISECONDS.sleep(20);
                }
                throw new IllegalStateException("pg_hba 재적재를 10초 안에 확인하지 못했다");
            } catch (SQLException e) {
                throw new IllegalStateException("local 백엔드 접속 실패(SQLSTATE " + e.getSQLState() + ")", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("pg_hba 재적재 대기 중 인터럽트", e);
            }
        }

        private static Timestamp confLoadTime(Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("select pg_conf_load_time()")) {
                rs.next();
                return rs.getTimestamp(1);
            }
        }

        @Override
        public String pgHbaConf() {
            try {
                return Files.readString(dataDir.resolve("pg_hba.conf"), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("pg_hba.conf를 읽지 못했다", e);
            }
        }

        @Override
        public synchronized void stop() {
            if (stopped) {
                return;
            }
            stopped = true;
            try {
                if (started) {
                    runAsPostgres(List.of(
                            bin.resolve("pg_ctl").toString(), "-D", dataDir.toString(), "-m", "fast", "-w", "stop"),
                            Map.of(), null);
                }
            } catch (RuntimeException e) {
                System.err.println("[MujoongPostgres] local 백엔드 정지 실패: " + e.getMessage());
            } finally {
                deleteRecursively(runDir);
                if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
                    try {
                        Runtime.getRuntime().removeShutdownHook(shutdownHook);
                    } catch (IllegalStateException ignored) {
                        // 이미 종료 중이면 훅을 뗄 수 없다
                    }
                }
            }
        }

        private String serverLogTail() {
            try {
                Path log = runDir.resolve("server.log");
                return Files.exists(log) ? tail(masked(Files.readString(log, StandardCharsets.UTF_8), passwords)) : "";
            } catch (IOException e) {
                return "";
            }
        }

        private void chownToPostgres(Path... paths) throws IOException {
            if (!root) {
                return;
            }
            UserPrincipalLookupService lookup = FileSystems.getDefault().getUserPrincipalLookupService();
            UserPrincipal user = lookup.lookupPrincipalByName("postgres");
            GroupPrincipal group = lookup.lookupPrincipalByGroupName("postgres");
            for (Path path : paths) {
                PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
                view.setOwner(user);
                view.setGroup(group);
            }
        }

        private String runAsPostgres(List<String> command, Map<String, String> env, String stdin) {
            if (!root) {
                return run(command, env, stdin);
            }
            List<String> wrapped = new ArrayList<>();
            wrapped.add(runuser().orElseThrow().toString());
            wrapped.add("-u");
            wrapped.add("postgres");
            wrapped.add("--");
            wrapped.add("env");
            env.forEach((key, value) -> wrapped.add(key + "=" + value));
            wrapped.addAll(command);
            return run(wrapped, Map.of(), stdin);
        }

        private String run(List<String> command, Map<String, String> env, String stdin) {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.directory(runDir.toFile());
            builder.environment().putAll(env);
            String name = label(command);
            try {
                Process process = builder.start();
                if (stdin != null) {
                    process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
                }
                process.getOutputStream().close();
                byte[] output = process.getInputStream().readAllBytes();
                if (!process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("local 백엔드 명령 시간 초과: " + name);
                }
                String text = masked(new String(output, StandardCharsets.UTF_8), passwords);
                if (process.exitValue() != 0) {
                    throw new IllegalStateException(
                            "local 백엔드 명령 실패: " + name + " (종료 코드 " + process.exitValue() + ")\n" + tail(text));
                }
                return text;
            } catch (IOException e) {
                throw new UncheckedIOException("local 백엔드 명령 실행 실패: " + name, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("local 백엔드 명령 대기 중 인터럽트: " + name, e);
            }
        }

        /** 오류 메시지용 명령 이름(실행 파일 이름과 마지막 인자만, 환경 값 없음). */
        private static String label(List<String> command) {
            int start = 0;
            int separator = command.indexOf("--");
            if (separator >= 0) {
                start = separator + 1;
                if (start < command.size() && command.get(start).equals("env")) {
                    start++;
                    while (start < command.size() && command.get(start).contains("=")) {
                        start++;
                    }
                }
            }
            String executable = Path.of(command.get(Math.min(start, command.size() - 1))).getFileName().toString();
            String last = command.get(command.size() - 1);
            return executable.equals(last) || last.contains("=") ? executable : executable + " ... " + Path.of(last).getFileName();
        }

        private static int freePort() {
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                socket.setReuseAddress(true);
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new UncheckedIOException("빈 포트를 찾지 못했다", e);
            }
        }

        private static void deleteRecursively(Path dir) {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        // 남은 파일은 build/tmp 아래에 남고 다음 clean이 지운다
                    }
                });
            } catch (IOException e) {
                System.err.println("[MujoongPostgres] local 백엔드 디렉터리 정리 실패: " + dir);
            }
        }
    }
}
