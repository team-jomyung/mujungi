package kr.mujoong.core.arch;

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

/**
 * 시간 숫자 상수 0건 자동 검사 (U1 3.6 수정 · NFR6.1 · BR8.11 · RD-2 · RD-24 · J113 나머지 · BT-NFR6.1 · J327).
 *
 * <p>시간 값은 설정 키(contracts/timing.md · application.yml · 설정 레코드)에서만 온다. 3.6 Build and Test의 S13 검색식
 * ({@code Duration.of…(숫자} · {@code TimeUnit.} · {@code Thread.sleep(} · {@code .plus…(숫자})과 같은 규칙에 숫자 시각 이동(minus ·
 * ChronoUnit)과 이름 붙은 숫자 시간 상수({@code *_MILLIS = 50} 등)를 더해 Core가 jar에 담는 운영 소스(core-api · contract-model)를 훑는다. 걸린
 * 줄은 아래 허용 목록(파일 · 줄 조각 · 근거)에 있어야 하고, 허용 목록의 줄은 실제로 있어야 한다(낡은 허용 금지). 주석은 보지 않는다.
 */
@Tag("skeleton")
class TimeLiteralScanTest {

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("mujoong.repo.root", "..")).toAbsolutePath().normalize();

    /** Core가 운영 jar에 담는 소스. */
    static final List<Path> SOURCE_ROOTS = List.of(
            REPO_ROOT.resolve("backend/core-api/src/main/java"),
            REPO_ROOT.resolve("backend/contract-model/src/main/java"));

    /** 검사 규칙(S13 검색식 + 더한 둘). */
    static final List<Rule> RULES = List.of(
            new Rule("숫자 Duration", Pattern.compile("Duration\\.of(Nanos|Millis|Seconds|Minutes|Hours|Days)\\(\\s*-?[0-9]")),
            new Rule("TimeUnit", Pattern.compile("TimeUnit\\.[A-Z]+")),
            new Rule("Thread.sleep", Pattern.compile("Thread\\.sleep\\(")),
            new Rule("숫자 시각 이동", Pattern.compile("\\.(plus|minus)(Nanos|Millis|Seconds|Minutes|Hours|Days)\\(\\s*[0-9]")),
            new Rule("ChronoUnit 숫자", Pattern.compile("\\.(plus|minus|of)\\(\\s*[0-9]+L?\\s*,\\s*ChronoUnit\\.")),
            new Rule("이름 붙은 숫자 시간 상수",
                    Pattern.compile("\\b[A-Z][A-Z0-9_]*_(NANOS|MILLIS|MS|SECONDS|SECS|MINUTES|HOURS|DAYS)\\s*=\\s*-?[0-9]")));

    private static final String CORE = "backend/core-api/src/main/java/kr/mujoong/core/";
    private static final String CONTRACT = "backend/contract-model/src/main/java/kr/mujoong/contract/";

    /** 허용 목록. 시간 값이 아니거나(단위 표기 · 단위 변환 · 고정 풀 keepAlive) 계약 · 결정이 값을 고정한 상수뿐이다. */
    static final List<Allowed> ALLOWED = List.of(
            new Allowed(CORE + "practice/ScenarioDeadline.java", "future.get(limit.toNanos(),TimeUnit.NANOSECONDS)",
                    "U10 R-F7: 최초 scenario-request-timeout의 잔여와 호출 설정 Duration 중 작은 값을 단위 변환한다. 숫자 시한 허용 없음"),
            new Allowed(CORE + "knowledge/ChildKnowledgeService.java", "future.get(budget.toNanos(), TimeUnit.NANOSECONDS)",
                    "U15: knowledge.graph-budget 설정 Duration의 나노초 단위 변환이다. 신규 시간 상수 없음"),
            new Allowed(CORE + "result/JobRunnerLoop.java",
                    "scheduler.scheduleWithFixedDelay(this::tick,0,tickMillis,TimeUnit.MILLISECONDS)",
                    "단위 표기: 반복 주기는 result.job-tick 설정 Duration이다. 최초 지연 0은 즉시 시작을 뜻한다(U6)"),
            new Allowed(CORE + "result/ResultTextGateway.java",
                    "future.get(limit.toNanos(),TimeUnit.NANOSECONDS)",
                    "단위 표기: result.request-timeout 설정과 작업 마감까지 남은 Duration 중 작은 한도다(U6)"),
            new Allowed(CORE + "notification/transport/PinnedPushHttpClient.java",
                    "attempt.get(timeout.toNanos(), TimeUnit.NANOSECONDS)",
                    "U7: 설정 send-timeout의 나노초 단위 변환이다. 대기 시간 상수는 허용하지 않는다"),
            new Allowed(CORE + "startup/CoreStartupSequence.java", "delay.toMillis(), delay.toMillis(), TimeUnit.MILLISECONDS",
                    "단위 표기: 값은 설정 키 Duration을 밀리초로 넘긴 것이다(scheduleWithFixedDelay에 Duration 판이 없다)"),
            new Allowed(CORE + "startup/CoreStartupSequence.java",
                    "executor.awaitTermination(remaining(grace, started).toMillis(), TimeUnit.MILLISECONDS)",
                    "단위 표기: T4 대기 한도(발행 제한 + 문장 제한, 설정 키)의 남은 시간을 밀리초로 넘긴다(awaitTermination에 Duration 판이 없다)"),
            new Allowed(CORE + "messaging/CoreInboxDispatcher.java", "0L, TimeUnit.MILLISECONDS",
                    "고정 크기 풀(core = max = mujoong.inbox.handler-threads)의 keepAlive 0은 시간 값이 아니라 '유휴 스레드를 줄이지 않음'이다."
                            + " core = max라 쓰이지 않는 ThreadPoolExecutor 생성자 필수 인자다(J327)"),
            new Allowed(CORE + "messaging/CoreInboxDispatcher.java", "pool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)",
                    "단위 표기: 호출자가 준 Duration(설정 키에서 계산)을 밀리초로 넘긴다"),
            new Allowed(CORE + "textai/TextAiGateway.java",
                    "TimeUnit.NANOSECONDS.sleep(properties.retry().backoff().toNanos());",
                    "단위 표기: mujoong.text-ai.retry.backoff 설정 Duration을 나노초로 넘긴다(U5a-J7)"),
            new Allowed(CORE + "textai/TextAiGateway.java",
                    "future.get(limit.toNanos(), TimeUnit.NANOSECONDS)",
                    "단위 표기: 호출 한도는 mujoong.text-ai.request-timeout과 C10 전체 설정 예산의 남은 시간 중 작은 Duration이다(U5a-J7)"),
            new Allowed(CORE + "common/db/DbRetry.java", "MAX_JITTER_MILLIS = 50",
                    "DB 재시도 지터 상한 0 ~ 50ms는 J124가 허용한 코드 상수다(3.6 S13 허용 목록)"),
            new Allowed(CORE + "common/db/DbRetry.java", "Thread.sleep(duration)",
                    "지터 대기 장치: 길이는 위 지터(J124)이고 시험은 대기 대역을 넣는다"),
            new Allowed(CORE + "observability/CoreMetrics.java", "REST_FAST = Duration.ofMillis(300)",
                    "METRIC_SNAPSHOT 키 이름 restLe300Ms가 값을 고정한 계약 상수다(OD-6 · NFR5.5, J324)"),
            new Allowed(CORE + "observability/CoreMetrics.java", "VIEW_FAST = Duration.ofMillis(500)",
                    "METRIC_SNAPSHOT 키 이름 viewLe500Ms가 값을 고정한 계약 상수다(OD-6 · NFR5.5, J324)"),
            new Allowed(CONTRACT + "time/DelayQueueTimerDriver.java", "unit.convert(nanos, TimeUnit.NANOSECONDS)",
                    "Delayed 계약의 단위 변환(값은 남은 나노초)이다"),
            new Allowed(CONTRACT + "time/DelayQueueTimerDriver.java", "getDelay(TimeUnit.NANOSECONDS)",
                    "Delayed 계약의 단위 변환으로 두 지연을 비교한다"),
            new Allowed(CORE + "record/RecordCalendar.java", "day.plusDays(1)",
                    "26 달력의 '그날'은 하루 단위 날짜 경계다(다음 날 0시, U4a-J8) — 설정할 시간 값이 아니다"),
            new Allowed(CORE + "luna/LunaTransport.java", ".orTimeout(limit.toNanos(), TimeUnit.NANOSECONDS)",
                    "단위 표기: 시도 시한은 호출자 마감과 text-ai 설정 키 중 작은 Duration이다(U9 BR5.1 · SD3, orTimeout에 Duration 판이 없다)"),
            new Allowed(CORE + "luna/CoreLunaClient.java", "TimeUnit.NANOSECONDS.sleep(duration.toNanos())",
                    "단위 표기: 재시도 대기는 mujoong.text-ai.retry.backoff 설정 또는 공급자 Retry-After Duration이다(U9 BR3.6 · SD5)"));

    @Test
    void everyTimeLiteralInCoreProductionSourcesIsOnTheAllowList() {
        List<Finding> findings = scanSources();

        assertThat(findings).as("훑은 운영 소스에서 걸린 줄(빈 훑기로 통과하지 않음)").isNotEmpty();
        assertThat(findings.stream().filter(finding -> !isAllowed(finding)).map(Finding::toString).toList())
                .as("허용 목록 밖 시간 숫자 상수(설정 키로 옮기거나 근거와 함께 허용 목록에 둔다)")
                .isEmpty();
    }

    @Test
    void everyAllowListEntryStillMatchesASourceLine() {
        List<Finding> findings = scanSources();

        List<String> stale = ALLOWED.stream()
                .filter(allowed -> findings.stream().noneMatch(allowed::covers))
                .map(Allowed::toString)
                .toList();

        assertThat(stale).as("코드에서 사라진 허용 목록 줄(목록에서도 지운다)").isEmpty();
    }

    @Test
    void allowListEntriesNameARealFileAndCarryAReason() {
        assertThat(ALLOWED).isNotEmpty().allSatisfy(allowed -> {
            assertThat(REPO_ROOT.resolve(allowed.file())).as(allowed.file()).isRegularFile();
            assertThat(allowed.fragment()).isNotBlank();
            assertThat(allowed.reason()).as("허용 근거").hasSizeGreaterThan(10);
        });
    }

    @Test
    void scannerFlagsEachRuleOnceInASample() {
        String sample = String.join("\n",
                "class Sample {",
                "  Duration a = Duration.ofSeconds(10);",
                "  void b() throws Exception { lock.await(1, TimeUnit.SECONDS); }",
                "  void c() throws Exception { Thread.sleep(5); }",
                "  Instant d(Instant t) { return t.plusMillis(20); }",
                "  Instant e(Instant t) { return t.minus(3, ChronoUnit.SECONDS); }",
                "  static final long POLL_MILLIS = 250;",
                "}");

        List<Finding> found = scan("Sample.java", sample);

        assertThat(found).extracting(Finding::rule).containsExactly("숫자 Duration", "TimeUnit", "Thread.sleep",
                "숫자 시각 이동", "ChronoUnit 숫자", "이름 붙은 숫자 시간 상수");
        assertThat(found).extracting(Finding::line).containsExactly(2, 3, 4, 5, 6, 7);
    }

    @Test
    void scannerIgnoresCommentsAndDurationsThatComeFromConfiguration() {
        String sample = String.join("\n",
                "/** 예전에는 {@code Thread.sleep(5)} · TimeUnit.SECONDS를 썼다. */",
                "class Clean {",
                "  // Duration.ofSeconds(10) 대신 설정 키를 읽는다",
                "  /* t.plusMillis(20) */ Duration a = Duration.ofMillis(properties.pollMillis());",
                "  Duration b = config.timeout().plus(other.timeout());",
                "  long c = timeout.toMillis();",
                "  String d = \"// 주석처럼 보이는 문자열\"; Duration e = Duration.ofSeconds(9);",
                "}");

        List<Finding> found = scan("Clean.java", sample);

        assertThat(found).as("주석 · 설정에서 온 값은 걸리지 않고 문자열 뒤의 진짜 상수는 걸린다").singleElement()
                .satisfies(finding -> {
                    assertThat(finding.line()).isEqualTo(7);
                    assertThat(finding.rule()).isEqualTo("숫자 Duration");
                });
    }

    @Test
    void allowListMatchesOnlyItsOwnFileAndFragment() {
        Allowed keepAlive = ALLOWED.stream().filter(allowed -> allowed.fragment().equals("0L, TimeUnit.MILLISECONDS"))
                .findFirst().orElseThrow();
        Finding sameFile = new Finding(keepAlive.file(), 104, "TimeUnit", "new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS,");
        Finding otherLine = new Finding(keepAlive.file(), 120, "TimeUnit", "lock.await(1, TimeUnit.SECONDS);");
        Finding otherFile = new Finding(CORE + "web/Other.java", 3, "TimeUnit", "x(0L, TimeUnit.MILLISECONDS,");

        assertThat(keepAlive.covers(sameFile)).isTrue();
        assertThat(keepAlive.covers(otherLine)).isFalse();
        assertThat(keepAlive.covers(otherFile)).isFalse();
    }

    @Test
    void textAiUnitConversionsDoNotAllowHardcodedWaitsOrOtherFiles() {
        String file = CORE + "textai/TextAiGateway.java";
        assertThat(isAllowed(new Finding(file, 1, "TimeUnit",
                "TimeUnit.NANOSECONDS.sleep(properties.retry().backoff().toNanos());"))).isTrue();
        assertThat(isAllowed(new Finding(file, 2, "TimeUnit",
                "return future.get(limit.toNanos(), TimeUnit.NANOSECONDS);"))).isTrue();
        assertThat(isAllowed(new Finding(file, 3, "TimeUnit",
                "TimeUnit.NANOSECONDS.sleep(250000000);"))).isFalse();
        assertThat(isAllowed(new Finding(file, 4, "TimeUnit",
                "return future.get(3000000000L, TimeUnit.NANOSECONDS);"))).isFalse();
        assertThat(isAllowed(new Finding(CORE + "textai/Other.java", 5, "TimeUnit",
                "return future.get(limit.toNanos(), TimeUnit.NANOSECONDS);"))).isFalse();
    }

    // ------------------------------------------------------------------ 훑기

    private static List<Finding> scanSources() {
        List<Finding> findings = new ArrayList<>();
        int files = 0;
        for (Path root : SOURCE_ROOTS) {
            for (Path file : javaFiles(root)) {
                files++;
                findings.addAll(scan(REPO_ROOT.relativize(file).toString().replace('\\', '/'), read(file)));
            }
        }
        assertThat(files).as("훑은 운영 소스 파일 수").isGreaterThan(150);
        return findings;
    }

    static List<Finding> scan(String file, String source) {
        List<Finding> findings = new ArrayList<>();
        String[] lines = withoutComments(source).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            for (Rule rule : RULES) {
                if (rule.pattern().matcher(lines[i]).find()) {
                    findings.add(new Finding(file, i + 1, rule.name(), lines[i].strip()));
                }
            }
        }
        return findings;
    }

    /** 주석을 지운다(줄 수는 그대로, 문자열 · 문자 · 텍스트 블록 안의 {@code //} · {@code /*}는 주석이 아니다). */
    static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                int stop = end < 0 ? source.length() : end + 2;
                source.substring(i, stop).chars().filter(ch -> ch == '\n').forEach(ch -> out.append('\n'));
                i = stop;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                int stop = end < 0 ? source.length() : end + 3;
                out.append(source, i, stop);
                i = stop;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < source.length() && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                int stop = Math.min(source.length(), j + 1);
                out.append(source, i, stop);
                i = stop;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isAllowed(Finding finding) {
        return ALLOWED.stream().anyMatch(allowed -> allowed.covers(finding));
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 검사 규칙 하나. */
    record Rule(String name, Pattern pattern) {
    }

    /** 걸린 줄 하나. */
    record Finding(String file, int line, String rule, String text) {

        @Override
        public String toString() {
            return file + ":" + line + " [" + rule + "] " + text;
        }
    }

    /** 허용 목록 줄: 이 파일에서 이 조각을 담은 줄만 허용한다. */
    record Allowed(String file, String fragment, String reason) {

        boolean covers(Finding finding) {
            return finding.file().equals(file) && finding.text().contains(fragment);
        }
    }
}
