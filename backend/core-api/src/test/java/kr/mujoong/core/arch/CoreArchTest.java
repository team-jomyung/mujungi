package kr.mujoong.core.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.activity.PreparingTimeoutChecker;
import kr.mujoong.core.activity.StartActivityService;
import kr.mujoong.core.messaging.handler.SessionStateChangedHandler;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Core 구조 규칙(U1 3.5 Step 36 · J113 ① ③ ⑤ · BR6.17 · BR8.1 · LC-11 · SD-25 · RD-2 R1 · NFR6.4 · NFR9.7).
 *
 * <ul>
 *   <li>① Core 운영 코드는 {@code kr.mujoong.realtime} 패키지를 참조하지 않는다(앱 사이는 HTTP · 이벤트 계약뿐, BR8.1 · ADR-019).
 *       공급자 클라이언트({@code java.net.http.WebSocket})에도 기대지 않는다(⑥ · SD-20: 공급자 연결은 Realtime voice 하나).
 *   <li>③ 운영 코드 → {@code kr.mujoong.testsupport} · 시험 라이브러리 의존 0(SD-25 · RD-23).
 *   <li>⑤ 시스템 시각 직접 읽기 0(시간 어댑터는 contract-model의 SystemMujoongClock 한 곳, RD-2 R1).
 *   <li>BR6.17 Core는 세션 사본만 갖는다: 사본 상태를 바꾸는 저장소 메서드는 BR6.17이 허용한 자리(SessionStateChanged 반영 ·
 *       BR6.6 · BR6.7 · BR6.12 ① 로컬 FAILED · BR6.1 ② 대조 반영)에서만 부르고, 질의 문자열은 realtime 스키마 · 세션 원본 표를
 *       가리키지 않으며 {@code core_activity}를 쓰는 문장은 사본 저장소 한 곳에만 있다.
 * </ul>
 *
 * <p>규칙마다 위반 표본(이 시험 소스의 중첩 클래스)을 실제로 잡는지 자기 시험을 둔다. 표본은 시험 소스 집합이라 운영 가져오기
 * ({@code DO_NOT_INCLUDE_TESTS})에 들어가지 않는다.
 */
@Tag("u1")
class CoreArchTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("kr.mujoong.core");

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("mujoong.repo.root", "..")).toAbsolutePath().normalize();

    private static final Path CORE_MAIN_SOURCES = REPO_ROOT.resolve("backend/core-api/src/main/java");

    // ------------------------------------------------------------------ ① 앱 사이 패키지 참조 0

    static ArchRule noDependencyOnPackages(String because, String... packages) {
        return noClasses().should().dependOnClassesThat().resideInAnyPackage(packages)
                .allowEmptyShould(false)
                .because(because);
    }

    static final ArchRule NO_REALTIME_REFERENCE = noDependencyOnPackages(
            "Core와 Realtime은 서로의 코드 · 스키마를 쓰지 않고 HTTP · 이벤트 계약으로만 만난다(BR8.1 · BR6.17 · J113 ①)",
            "kr.mujoong.realtime..");

    static final ArchRule NO_PROVIDER_CLIENT = noClasses()
            .should().dependOnClassesThat().haveNameMatching("java\\.net\\.http\\.WebSocket(\\$.*)?")
            .allowEmptyShould(false)
            .because("공급자 연결은 Realtime voice 패키지 하나가 맡는다(SD-20 · J113 ⑥)");

    @Test
    void coreNeverReferencesRealtimeCodeOrTheProviderClient() {
        assertThat(PRODUCTION.size()).as("가져온 Core 운영 클래스 수(빈 가져오기로 통과하지 않음)").isGreaterThan(100);
        assertThat(PRODUCTION.contain(ActivityCopyRepository.class)).isTrue();

        NO_REALTIME_REFERENCE.check(PRODUCTION);
        NO_PROVIDER_CLIENT.check(PRODUCTION);
    }

    @Test
    void packageRuleFlagsADependencyOnTheForbiddenPackage() {
        // 같은 규칙 생성기로 금지 패키지를 바꿔 끼워 실제 의존을 잡는지 본다(Core 시험 클래스패스에는 realtime 클래스가 없다)
        JavaClasses sample = new ClassFileImporter().importClasses(SampleUsesInjectedClock.class);

        assertThat(noDependencyOnPackages("자기 시험", "kr.mujoong.contract.time..").evaluate(sample).hasViolation())
                .isTrue();
        assertThat(noDependencyOnPackages("자기 시험", "kr.mujoong.contract.timex..").evaluate(sample).hasViolation())
                .as("패키지 접두가 비슷할 뿐인 경우는 잡지 않는다").isFalse();
    }

    // ------------------------------------------------------------------ ③ 운영 → 시험 코드 0

    static final String[] TEST_PACKAGES = {
        "kr.mujoong.testsupport..", "org.junit..", "org.mockito..", "org.assertj..", "org.testcontainers..",
        "com.tngtech.archunit..", "org.springframework.test..", "org.springframework.boot.test..",
    };

    static final ArchRule NO_TEST_CODE_IN_PRODUCTION = noDependencyOnPackages(
            "운영 코드는 시험 지원 · 시험 라이브러리에 기대지 않는다(SD-25 · RD-23 · J113 ③)", TEST_PACKAGES);

    @Test
    void productionCodeNeverDependsOnTestSupport() {
        NO_TEST_CODE_IN_PRODUCTION.check(PRODUCTION);
    }

    @Test
    void testCodeRuleFlagsAProductionLookingClassThatUsesTestSupport() {
        JavaClasses sample = new ClassFileImporter().importClasses(SampleUsesMutableClock.class);

        EvaluationResult result = NO_TEST_CODE_IN_PRODUCTION.evaluate(sample);

        assertThat(result.getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains(MutableClock.class.getName()));
    }

    // ------------------------------------------------------------------ ⑤ 시각 직접 읽기 0

    static final ArchRule NO_SYSTEM_TIME = noClasses()
            .should(readTheSystemClockDirectly())
            .allowEmptyShould(false)
            .because("모든 시각은 주입한 MujoongClock 하나로 읽는다(RD-2 R1 · NFR6.4 · J113 ⑤)");

    @Test
    void coreNeverReadsTheSystemClockDirectly() {
        NO_SYSTEM_TIME.check(PRODUCTION);
    }

    @Test
    void timeRuleFlagsDirectReadsAndAcceptsTheInjectedClock() {
        JavaClasses offenders = new ClassFileImporter()
                .importClasses(SampleInstantNow.class, SampleCurrentTimeMillisReference.class,
                        SampleOffsetDateTimeNow.class);
        JavaClasses clean = new ClassFileImporter().importClasses(SampleUsesInjectedClock.class);

        assertThat(NO_SYSTEM_TIME.evaluate(offenders).getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains("Instant.now"))
                .anyMatch(detail -> detail.contains("System.currentTimeMillis"))
                .anyMatch(detail -> detail.contains("OffsetDateTime.now"));
        assertThat(NO_SYSTEM_TIME.evaluate(clean).hasViolation()).isFalse();
    }

    // ------------------------------------------------------------------ BR6.17 Core는 사본 · 조회만

    /**
     * 사본 상태를 바꾸는 저장소 메서드 → 부를 수 있는 클래스(BR6.17 logic의 예외 목록 그대로).
     *
     * <ul>
     *   <li>{@code insertRequested}: 시작 요청 사본 생성(BR6.1 · BR6.2) — StartActivityService.
     *   <li>{@code applyStateChange}: SessionStateChanged 반영(BR5.10 · BR6.14) · BR6.1 ② 대조 결과 반영.
     *   <li>{@code fillStartedAtIfMissing}: SessionStateChanged 반영(BR6.14).
     *   <li>{@code closeLocalFailed}: BR6.6 · BR6.7 · BR6.1 ② 로컬 FAILED(시작 서비스) · BR6.12 ① PREPARING 시한(점검기).
     * </ul>
     */
    static final Map<String, Set<String>> COPY_MUTATORS = Map.of(
            "insertRequested", Set.of(StartActivityService.class.getName()),
            "applyStateChange", Set.of(SessionStateChangedHandler.class.getName(), StartActivityService.class.getName()),
            "fillStartedAtIfMissing", Set.of(SessionStateChangedHandler.class.getName()),
            "closeLocalFailed", Set.of(StartActivityService.class.getName(), PreparingTimeoutChecker.class.getName()));

    static final ArchRule COPY_STATE_CHANGES_ONLY_AT_BR6_17_EXCEPTIONS = noClasses()
            .should(changeTheSessionCopyOutsideItsAllowedCallers())
            .allowEmptyShould(false)
            .because("Core 사본 상태는 SessionStateChanged와 BR6.6 · BR6.7 · BR6.12 ① · BR6.1 ②의 예외로만 바뀐다(BR6.17)");

    @Test
    void sessionCopyStateChangesOnlyWhereBr617AllowsIt() {
        COPY_STATE_CHANGES_ONLY_AT_BR6_17_EXCEPTIONS.check(PRODUCTION);
    }

    @Test
    void everyAllowedCopyMutatorIsReallyUsedByItsAllowedCaller() {
        // 허용 목록이 실제 호출과 맞는지 본다(호출이 사라지거나 옮겨 가면 목록도 고쳐야 한다)
        for (Map.Entry<String, Set<String>> entry : COPY_MUTATORS.entrySet()) {
            for (String caller : entry.getValue()) {
                JavaClass callerClass = PRODUCTION.get(caller);
                boolean calls = callerClass.getCodeUnitAccessesFromSelf().stream()
                        .anyMatch(access -> isCopyRepository(access.getTarget().getOwner())
                                && access.getTarget().getName().equals(entry.getKey()));
                assertThat(calls).as(caller + " → " + entry.getKey()).isTrue();
            }
        }
    }

    @Test
    void copyRuleFlagsAStrayStateChangeButNotReads() {
        JavaClasses stray = new ClassFileImporter().importClasses(SampleStrayCopyWriter.class);
        JavaClasses reader = new ClassFileImporter().importClasses(SampleCopyReader.class);

        assertThat(COPY_STATE_CHANGES_ONLY_AT_BR6_17_EXCEPTIONS.evaluate(stray).getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains("applyStateChange"))
                .anyMatch(detail -> detail.contains("closeLocalFailed"));
        assertThat(COPY_STATE_CHANGES_ONLY_AT_BR6_17_EXCEPTIONS.evaluate(reader).hasViolation()).isFalse();
    }

    /** realtime 스키마 한정 이름(앞이 단어 · 점이 아님, 패키지 이름 kr.mujoong.realtime.x는 제외) 또는 Realtime 표 이름. */
    static final Pattern REALTIME_SCHEMA_SQL = Pattern.compile(
            "(?i)((?<![\\w.])realtime\\s*\\.\\s*[a-z_]+|\\brealtime_(activity_session|session_event|session_stop_expression"
                    + "|transcript|device_connection|outbox_event|inbox_event|schema_history)\\b)");

    /** core_activity를 바꾸는 문장(세션 사본 쓰기). */
    static final Pattern CORE_ACTIVITY_WRITE = Pattern.compile(
            "(?i)\\b(update|insert\\s+into|delete\\s+from|merge\\s+into)\\s+(core\\s*\\.\\s*)?core_activity\\b");

    @Test
    void coreQueriesNeverTouchTheRealtimeSchemaAndOnlyTheCopyRepositoryWritesCoreActivity() {
        List<Path> sources = javaFiles(CORE_MAIN_SOURCES);
        assertThat(sources).as("Core 운영 소스").hasSizeGreaterThan(100);

        List<String> realtimeSql = new ArrayList<>();
        List<String> copyWriters = new ArrayList<>();
        for (Path file : sources) {
            for (Literal literal : stringLiterals(readString(file))) {
                String where = REPO_ROOT.relativize(file) + ":" + literal.line();
                if (REALTIME_SCHEMA_SQL.matcher(literal.text()).find()) {
                    realtimeSql.add(where);
                }
                if (CORE_ACTIVITY_WRITE.matcher(literal.text()).find()) {
                    copyWriters.add(file.getFileName().toString());
                }
            }
        }
        assertThat(realtimeSql).as("realtime 스키마 · 세션 원본 표를 가리키는 Core 질의 문자열").isEmpty();
        assertThat(copyWriters).as("core_activity를 쓰는 문장이 있는 파일").isNotEmpty()
                .containsOnly("ActivityCopyRepository.java");
    }

    @Test
    void sqlLiteralScannerFindsSchemaNamesOnlyInsideStringLiterals() {
        String source = String.join("\n",
                "class Repo {",
                "  // update realtime.realtime_activity_session — 주석은 질의가 아니다",
                "  void cancel() { realtime.cancel(id); }",
                "  String a = \"select status from realtime.realtime_activity_session where id = :id\";",
                "  String b = \"\"\"",
                "      update core.core_activity set status = :status",
                "      \"\"\";",
                "  String c = \"kr.mujoong.realtime.session\";",
                "  String d = \"mujoong.internal-http.realtime-base-url\";",
                "}");
        List<Literal> literals = stringLiterals(source);

        assertThat(literals).filteredOn(literal -> REALTIME_SCHEMA_SQL.matcher(literal.text()).find())
                .extracting(Literal::line).containsExactly(4);
        assertThat(literals).filteredOn(literal -> CORE_ACTIVITY_WRITE.matcher(literal.text()).find())
                .extracting(Literal::line).containsExactly(5);
    }

    // ------------------------------------------------------------------ 규칙 조건
    // 조건은 막을 행동을 '했다'로 기록한다(satisfied). noClasses().should(...)가 그 기록을 위반으로 뒤집는다.

    static boolean isCopyRepository(JavaClass owner) {
        return owner.getName().equals(ActivityCopyRepository.class.getName());
    }

    static ArchCondition<JavaClass> changeTheSessionCopyOutsideItsAllowedCallers() {
        return new ArchCondition<>("change the session copy outside the BR6.17 callers") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String caller = item.getName().contains("$")
                        ? item.getName().substring(0, item.getName().indexOf('$'))
                        : item.getName();
                for (JavaCodeUnitAccess<?> access : item.getCodeUnitAccessesFromSelf()) {
                    Set<String> allowed = COPY_MUTATORS.get(access.getTarget().getName());
                    if (allowed != null && isCopyRepository(access.getTarget().getOwner())
                            && !allowed.contains(caller) && !caller.equals(ActivityCopyRepository.class.getName())) {
                        events.add(SimpleConditionEvent.satisfied(access, access.getDescription()));
                    }
                }
            }
        };
    }

    /** 시스템 시각을 직접 만드는 API(소유 타입 → 메서드 이름). contract-model · Realtime 시험과 같은 정의. */
    static final Map<String, Set<String>> SYSTEM_TIME_METHODS = Map.ofEntries(
            Map.entry("java.lang.System", Set.of("currentTimeMillis", "nanoTime")),
            Map.entry("java.time.Instant", Set.of("now")),
            Map.entry("java.time.LocalDateTime", Set.of("now")),
            Map.entry("java.time.LocalDate", Set.of("now")),
            Map.entry("java.time.LocalTime", Set.of("now")),
            Map.entry("java.time.OffsetDateTime", Set.of("now")),
            Map.entry("java.time.OffsetTime", Set.of("now")),
            Map.entry("java.time.ZonedDateTime", Set.of("now")),
            Map.entry("java.time.Year", Set.of("now")),
            Map.entry("java.time.YearMonth", Set.of("now")),
            Map.entry("java.time.MonthDay", Set.of("now")),
            Map.entry("java.time.Clock",
                    Set.of("systemUTC", "systemDefaultZone", "system", "tickMillis", "tickSeconds", "tickMinutes")),
            Map.entry("java.time.InstantSource", Set.of("system")),
            Map.entry("java.util.Calendar", Set.of("getInstance")));

    static boolean readsSystemClock(AccessTarget target) {
        String owner = target.getOwner().getName();
        if (owner.equals("java.util.Date") && target.getName().equals("<init>")
                && target instanceof AccessTarget.CodeUnitAccessTarget codeUnit) {
            return codeUnit.getRawParameterTypes().isEmpty();
        }
        return SYSTEM_TIME_METHODS.getOrDefault(owner, Set.of()).contains(target.getName());
    }

    static ArchCondition<JavaClass> readTheSystemClockDirectly() {
        return new ArchCondition<>("read the system clock directly") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaCodeUnitAccess<?> access : item.getCodeUnitAccessesFromSelf()) {
                    if (readsSystemClock(access.getTarget())) {
                        events.add(SimpleConditionEvent.satisfied(access, access.getDescription()));
                    }
                }
            }
        };
    }

    // ------------------------------------------------------------------ 소스 문자열 리터럴(질의 문자열) 읽기

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
            } else if (source.startsWith("//", i)) {
                while (i < length && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? length : end + 2;
                line += newlines(source, i, end);
                i = end;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? length : end;
                literals.add(new Literal(line, source.substring(i + 3, end)));
                line += newlines(source, i, end);
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

    private static int newlines(String text, int from, int to) {
        int total = 0;
        for (int k = from; k < to; k++) {
            if (text.charAt(k) == '\n') {
                total++;
            }
        }
        return total;
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ 위반 · 통과 표본(시험 소스 전용)

    static final class SampleUsesMutableClock {
        Instant at(MutableClock clock) {
            return clock.now();
        }
    }

    static final class SampleUsesInjectedClock {
        Instant later(MujoongClock clock) {
            return clock.now().plus(Duration.ofSeconds(1));
        }
    }

    static final class SampleInstantNow {
        Instant at() {
            return Instant.now();
        }
    }

    static final class SampleCurrentTimeMillisReference {
        LongSupplier millis() {
            return System::currentTimeMillis;
        }
    }

    static final class SampleOffsetDateTimeNow {
        Object at() {
            return OffsetDateTime.now();
        }
    }

    static final class SampleStrayCopyWriter {
        void sneak(ActivityCopyRepository copies, UUID sessionId, Instant now) {
            copies.applyStateChange(null, now);
            copies.closeLocalFailed(sessionId, null, 0, 202, now);
        }
    }

    static final class SampleCopyReader {
        Object read(ActivityCopyRepository copies, UUID sessionId, UUID guardianId) {
            return copies.findOwned(sessionId, guardianId);
        }
    }
}
