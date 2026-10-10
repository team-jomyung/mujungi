package kr.mujoong.realtime.startup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * S2 이력 버전 확인 (U1 3.5 Step 26 · RD-12 S2 · BR8.2 · SD-13 · J116).
 *
 * <p>앱은 Flyway를 실행하지 않는다. 자기 이력 표 {@value #HISTORY_TABLE}에서 성공한 최고 버전을 읽어 앱이 들고 온 마이그레이션
 * ({@value #MIGRATION_PATTERN})의 최고 버전 이상인지 본다. 미적용 · 낮은 버전 · 읽기 실패면 기동을 거부한다(JPA {@code ddl-auto=validate}는
 * 같은 S2에서 JPA가 한다). 거부 줄에는 버전 숫자와 표 이름만 쓴다. Core에 같은 규칙의 점검이 따로 있다(LC-11).
 */
@Component
public class SchemaVersionCheck {

    /** 이력 표(J116). */
    public static final String HISTORY_TABLE = "flyway.realtime_schema_history";
    /** 앱 마이그레이션 위치. */
    public static final String MIGRATION_PATTERN = "classpath*:db/migration/realtime/V*__*.sql";

    private static final Pattern VERSION_FILE = Pattern.compile("^V([0-9]+(?:[._][0-9]+)*)__.+\\.sql$");

    private final JdbcTemplate jdbc;

    public SchemaVersionCheck(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 확인한다. 통과하면 적용된 최고 버전을 돌려준다. */
    public String verify() {
        String expected = expectedVersion();
        Optional<String> applied;
        try {
            applied = appliedVersions().stream().max(SchemaVersionCheck::compareVersions);
        } catch (DataAccessException e) {
            throw reject(HISTORY_TABLE + "를 읽지 못했다");
        }
        if (applied.isEmpty()) {
            throw reject(HISTORY_TABLE + "에 성공한 버전이 없다(기대 " + expected + ")");
        }
        if (compareVersions(applied.get(), expected) < 0) {
            throw reject(HISTORY_TABLE + " 버전 " + applied.get() + " < 기대 " + expected);
        }
        return applied.get();
    }

    /** 앱이 들고 온 마이그레이션의 최고 버전. */
    public static String expectedVersion() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(MIGRATION_PATTERN);
            String max = null;
            for (Resource resource : resources) {
                Matcher matcher = VERSION_FILE.matcher(Objects.requireNonNullElse(resource.getFilename(), ""));
                if (matcher.matches()) {
                    String version = matcher.group(1).replace('_', '.');
                    if (max == null || compareVersions(version, max) > 0) {
                        max = version;
                    }
                }
            }
            if (max == null) {
                throw new IllegalStateException("앱 마이그레이션 파일이 없다: " + MIGRATION_PATTERN);
            }
            return max;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> appliedVersions() {
        return jdbc.queryForList(
                "select version from " + HISTORY_TABLE + " where success and version is not null", String.class);
    }

    /** 점으로 나뉜 숫자 버전 비교(1 &lt; 1.1 &lt; 2 &lt; 10). */
    static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length ? Long.parseLong(a[i]) : 0L;
            long y = i < b.length ? Long.parseLong(b[i]) : 0L;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    private static RealtimeStartupChecks.StartupRejectedException reject(String reason) {
        StartupFailureLine.print("SchemaVersionRejected", reason);
        return new RealtimeStartupChecks.StartupRejectedException(reason);
    }
}
