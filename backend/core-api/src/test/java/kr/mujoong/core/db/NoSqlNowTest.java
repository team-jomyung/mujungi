package kr.mujoong.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * DB 시각 함수 금지 시험(U1 3.5 Step 14 · R2 · NFR6.4 · RD-2). 시각은 앱 Clock 값을 {@code :now}로 바인딩하고 DB가 시각을 만들지 않는다.
 *
 * <p>대상: 두 앱의 마이그레이션 SQL(운영 · 시험 위치), {@code infra/seed/} 참조 시드, 두 앱 운영 Java 소스의 문자열 리터럴(질의 문자열).
 * Java 주석 · 코드(예: {@code clock.now()})는 질의가 아니므로 리터럴만 본다. 파일은 읽기만 한다(컴파일 의존 없음).
 */
@Tag("skeleton")
class NoSqlNowTest {

    /** PostgreSQL이 현재 시각을 만드는 함수 · 키워드. */
    static final Pattern SQL_CLOCK = Pattern.compile(
            "(?i)(\\bnow\\s*\\(|\\bclock_timestamp\\s*\\(|\\bstatement_timestamp\\s*\\(|\\btransaction_timestamp\\s*\\("
                    + "|\\btimeofday\\s*\\(|\\bcurrent_timestamp\\b|\\bcurrent_date\\b|\\bcurrent_time\\b|\\blocaltimestamp\\b)");

    private static final Path REPO_ROOT = Path.of(System.getProperty("mujoong.repo.root", "..")).toAbsolutePath().normalize();

    // ------------------------------------------------------------------ 대상 파일

    @Test
    void migrationAndSeedSqlNeverAskTheDatabaseForTheTime() {
        List<Path> sqlFiles = new ArrayList<>();
        sqlFiles.addAll(files("backend", ".sql", "/src/main/resources/db/", "/src/test/resources/db/"));
        sqlFiles.addAll(files("infra/seed", ".sql"));
        // 빈 탐색으로 통과하지 않는다: V1 둘 · afterMigrate 둘 · 참조 시드는 반드시 있다
        assertThat(sqlFiles.stream().map(path -> path.getFileName().toString()))
                .contains("V1__core_base.sql", "V1__realtime_base.sql", "afterMigrate.sql", "001-reference.sql");

        List<String> offenders = new ArrayList<>();
        for (Path file : sqlFiles) {
            List<String> lines = readLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (SQL_CLOCK.matcher(lines.get(i)).find()) {
                    offenders.add(REPO_ROOT.relativize(file) + ":" + (i + 1));
                }
            }
        }
        assertThat(offenders).as("DB 시각 함수를 쓰는 SQL 줄").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"backend/core-api/src/main/java", "backend/realtime-activity/src/main/java"})
    void queryStringsInApplicationSourcesNeverAskTheDatabaseForTheTime(String sourceRoot) {
        List<Path> javaFiles = files(sourceRoot, ".java");
        assertThat(javaFiles).as(sourceRoot + " 운영 소스").isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles) {
            for (Literal literal : stringLiterals(readString(file))) {
                if (SQL_CLOCK.matcher(literal.text()).find()) {
                    offenders.add(REPO_ROOT.relativize(file) + ":" + literal.line());
                }
            }
        }
        assertThat(offenders).as("DB 시각 함수를 담은 질의 문자열").isEmpty();
    }

    @Test
    void sqlPathSelectionHandlesBothSeparatorsAndExcludesBuildCopies() {
        String[] fragments = {"/src/main/resources/db/", "/src/test/resources/db/"};
        for (String separator : List.of("/", "\\")) {
            assertThat(selectsPath("backend/core-api/src/main/resources/db/migration/core/V1__core_base.sql"
                    .replace("/", separator), ".sql", fragments)).isTrue();
            assertThat(selectsPath("backend/realtime-activity/src/main/resources/db/migration/realtime/afterMigrate.sql"
                    .replace("/", separator), ".sql", fragments)).isTrue();
            assertThat(selectsPath("backend/core-api/src/test/resources/db/testmigration/core/probe.sql"
                    .replace("/", separator), ".sql", fragments)).isTrue();
            assertThat(selectsPath("backend/core-api/build/copy/src/main/resources/db/duplicate.sql"
                    .replace("/", separator), ".sql", fragments)).isFalse();
            assertThat(selectsPath("backend/node_modules/copy/src/main/resources/db/duplicate.sql"
                    .replace("/", separator), ".sql", fragments)).isFalse();
            assertThat(selectsPath("backend/core-api/src/main/resources/unrelated/example.sql"
                    .replace("/", separator), ".sql", fragments)).isFalse();
        }
    }

    // ------------------------------------------------------------------ 검출기 자기 시험

    @Test
    void detectorFlagsClockFunctionsInsideStringLiteralsAndTextBlocks() {
        String source = String.join("\n",
                "class Repo {",
                "  String a = \"update core.core_device set updated_at = now() where id = :id\";",
                "  String b = \"\"\"",
                "      insert into core.core_outbox_event (created_at) values (CURRENT_TIMESTAMP)",
                "      \"\"\";",
                "  String c = \"select clock_timestamp ()\";",
                "}");
        List<Literal> flagged = stringLiterals(source).stream()
                .filter(literal -> SQL_CLOCK.matcher(literal.text()).find())
                .toList();
        assertThat(flagged).extracting(Literal::line).containsExactly(2, 3, 6);
    }

    @Test
    void detectorIgnoresCommentsCodeAndBoundParameters() {
        String source = String.join("\n",
                "class Repo {",
                "  // DB now() 금지 — 주석은 질의가 아니다",
                "  /* current_timestamp도 주석 */",
                "  Instant at = clock.now();",
                "  String sql = \"update core.core_device set updated_at = :now, row_version = row_version + 1\";",
                "  String col = \"select now_ms, known_at from t\";",
                "  char quote = '\"';",
                "  String escaped = \"say \\\"hi\\\" at :now\";",
                "}");
        List<Literal> literals = stringLiterals(source);
        assertThat(literals).extracting(Literal::text)
                .contains("update core.core_device set updated_at = :now, row_version = row_version + 1");
        assertThat(literals).noneMatch(literal -> SQL_CLOCK.matcher(literal.text()).find());
    }

    @Test
    void sqlPatternCoversEveryPostgresClockSpellingButNotLookAlikes() {
        for (String clock : List.of("now()", "NOW ()", "clock_timestamp()", "statement_timestamp()",
                "transaction_timestamp()", "timeofday()", "current_timestamp", "CURRENT_DATE", "current_time",
                "localtimestamp")) {
            assertThat(SQL_CLOCK.matcher("select " + clock).find()).as(clock).isTrue();
        }
        for (String lookAlike : List.of(":now", "known_at", "snow(", "current_timestamps_seen", "updated_at")) {
            assertThat(SQL_CLOCK.matcher("select " + lookAlike).find()).as(lookAlike).isFalse();
        }
    }

    // ------------------------------------------------------------------ 도구

    record Literal(int line, String text) {
    }

    /** Java 소스의 문자열 리터럴 · 텍스트 블록 내용(시작 줄 번호와 함께). 주석 · 문자 리터럴은 건너뛴다. */
    static List<Literal> stringLiterals(String source) {
        List<Literal> literals = new ArrayList<>();
        int line = 1;
        int i = 0;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                while (i < length && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? length : end + 2;
                line += count(source, i, end, '\n');
                i = end;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? length : end;
                literals.add(new Literal(line, source.substring(i + 3, end)));
                line += count(source, i, end, '\n');
                i = Math.min(length, end + 3);
            } else if (c == '"' || c == '\'') {
                StringBuilder text = new StringBuilder();
                int j = i + 1;
                while (j < length && source.charAt(j) != c && source.charAt(j) != '\n') {
                    if (source.charAt(j) == '\\' && j + 1 < length) {
                        text.append(source.charAt(j + 1));
                        j += 2;
                    } else {
                        text.append(source.charAt(j));
                        j++;
                    }
                }
                if (c == '"') {
                    literals.add(new Literal(line, text.toString()));
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        return literals;
    }

    private static int count(String text, int from, int to, char target) {
        int total = 0;
        for (int k = from; k < to; k++) {
            if (text.charAt(k) == target) {
                total++;
            }
        }
        return total;
    }

    private static List<Path> files(String relativeRoot, String extension, String... pathFragments) {
        Path root = REPO_ROOT.resolve(relativeRoot);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            // 경로 조각은 '/'로 적었으므로 OS 구분자(Windows '\')를 '/'로 맞춰 비교한다(U2: Windows 개발 PC에서 0건 탐색 방지)
            return walk.filter(Files::isRegularFile)
                    .filter(path -> selectsPath(path.toString(), extension, pathFragments))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Windows 구분자('\')를 '/'로 바꾼 뒤 저장소 상대 경로 조각과 맞춘다. */
    static boolean selectsPath(String path, String extension, String... pathFragments) {
        String portable = path.replace('\\', '/');
        return portable.endsWith(extension)
                && !portable.contains("/build/") && !portable.contains("/node_modules/")
                && (pathFragments.length == 0
                        || Stream.of(pathFragments).anyMatch(portable::contains));
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> readLines(Path file) {
        return readString(file).lines().toList();
    }
}
