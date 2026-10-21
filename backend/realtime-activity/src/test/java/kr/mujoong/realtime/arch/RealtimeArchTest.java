package kr.mujoong.realtime.arch;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameStartingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
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
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.gateway.ConnectionSender;
import kr.mujoong.realtime.gateway.DeviceConnection;
import kr.mujoong.realtime.gateway.DeviceLink;
import kr.mujoong.realtime.gateway.DeviceWebSocketHandler;
import kr.mujoong.realtime.gateway.OutputBundle;
import kr.mujoong.realtime.gateway.SocketRegistry;
import kr.mujoong.realtime.gateway.WsFrameGuard;
import kr.mujoong.realtime.messaging.RealtimeOutboxWriter;
import kr.mujoong.realtime.voice.ProviderClient;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import kr.mujoong.testsupport.time.ManualTimerDriver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Realtime 구조 규칙(U1 3.5 Step 36 · J113 ① ③ ④ ⑤ ⑥ · BR8.1 · SD-20 · SD-22 · SD-25 · RD-2 R1 · NFR6.4 · NFR9.7 · NFR12.2).
 *
 * <ul>
 *   <li>① Realtime 운영 코드는 {@code kr.mujoong.core}를 참조하지 않고, 질의 문자열이 core 스키마 · 표를 가리키지 않는다(BR8.1).
 *   <li>③ 운영 코드 → {@code kr.mujoong.testsupport} · 시험 라이브러리 의존 0(SD-25 · RD-23).
 *   <li>④ 프레임 경로(기기 WSS 수신 · 송신 · 프레임 검사 · 연결 레지스트리, voice 입력 중계 · 공급자 클라이언트, gate 출력 구간)는
 *       JDBC · 트랜잭션 · 파일 · Outbox/Inbox · 저장소에 직접 기대지 않는다(SD-22 · NFR12.2, 클래스 단위 직접 의존 규칙).
 *       HELLO 인계 · 닫기 상태 기록 · HEARTBEAT의 last_seen_at처럼 연결당 한 번이거나 주기 보고인 제어 경로 클래스
 *       (ConnectionHandover · ConnectionStateWriter · HeartbeatHandler)는 대상이 아니다(J125).
 *   <li>⑤ 시스템 시각 직접 읽기 0(시간 어댑터는 contract-model의 SystemMujoongClock 한 곳, RD-2 R1).
 *   <li>⑥ 공급자 클라이언트({@code voice.ProviderClient} · {@code java.net.http.WebSocket})에 기대는 클래스는
 *       {@code kr.mujoong.realtime.voice}에만 있고, 공급자 요청 레코드는 {@link ProviderRequestFactory}만 만든다(SD-20).
 * </ul>
 *
 * <p>규칙마다 위반 표본(이 시험 소스의 중첩 클래스)을 실제로 잡는지 자기 시험을 둔다. 표본은 시험 소스 집합이라 운영 가져오기
 * ({@code DO_NOT_INCLUDE_TESTS})에 들어가지 않는다.
 */
@Tag("skeleton")
@org.junit.jupiter.api.Tag("service-guardrail")
class RealtimeArchTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("kr.mujoong.realtime");

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("mujoong.repo.root", "..")).toAbsolutePath().normalize();

    private static final Path REALTIME_MAIN_SOURCES = REPO_ROOT.resolve("backend/realtime-activity/src/main/java");

    // ------------------------------------------------------------------ ① 앱 사이 참조 0

    static ArchRule noDependencyOnPackages(String because, String... packages) {
        return noClasses().should().dependOnClassesThat().resideInAnyPackage(packages)
                .allowEmptyShould(false)
                .because(because);
    }

    static final ArchRule NO_CORE_REFERENCE = noDependencyOnPackages(
            "Realtime과 Core는 서로의 코드 · 스키마를 쓰지 않고 HTTP · 이벤트 계약으로만 만난다(BR8.1 · J113 ①)",
            "kr.mujoong.core..");

    /** core 스키마 한정 이름(앞이 단어 · 점이 아님) 또는 Core 표 이름. */
    static final Pattern CORE_SCHEMA_SQL = Pattern.compile(
            "(?i)((?<![\\w.])core\\s*\\.\\s*[a-z_]+|\\bcore_(guardian|spring_session|spring_session_attributes|consent|child"
                    + "|stop_expression|approved_content|approved_content_version|device|activity|outbox_event"
                    + "|inbox_event|schema_history)\\b)");

    @Test
    void realtimeNeverReferencesCoreCodeOrTheCoreSchema() {
        assertThat(PRODUCTION.size()).as("가져온 Realtime 운영 클래스 수(빈 가져오기로 통과하지 않음)").isGreaterThan(100);
        NO_CORE_REFERENCE.check(PRODUCTION);

        List<Path> sources = javaFiles(REALTIME_MAIN_SOURCES);
        assertThat(sources).as("Realtime 운영 소스").hasSizeGreaterThan(100);
        List<String> coreSql = new ArrayList<>();
        int realtimeSqlLiterals = 0;
        for (Path file : sources) {
            for (Literal literal : stringLiterals(readString(file))) {
                if (CORE_SCHEMA_SQL.matcher(literal.text()).find()) {
                    coreSql.add(REPO_ROOT.relativize(file) + ":" + literal.line());
                }
                if (literal.text().contains("realtime.realtime_")) {
                    realtimeSqlLiterals++;
                }
            }
        }
        assertThat(realtimeSqlLiterals).as("자기 스키마 질의 문자열(스캐너가 질의를 실제로 읽음)").isPositive();
        assertThat(coreSql).as("core 스키마 · 표를 가리키는 Realtime 질의 문자열").isEmpty();
    }

    @Test
    void schemaScannerAndPackageRuleCatchTheirSamples() {
        String source = String.join("\n",
                "class Repo {",
                "  // select * from core.core_device — 주석",
                "  String a = \"select guardian_id from core.core_device where id = :id\";",
                "  String b = \"kr.mujoong.core.device\";",
                "  String c = \"mujoong.internal-http.core-base-url\";",
                "  String d = \"update realtime.realtime_device_connection set state = :state\";",
                "}");
        assertThat(stringLiterals(source)).filteredOn(literal -> CORE_SCHEMA_SQL.matcher(literal.text()).find())
                .extracting(Literal::line).containsExactly(3);

        JavaClasses sample = new ClassFileImporter().importClasses(SampleUsesInjectedClock.class);
        assertThat(noDependencyOnPackages("자기 시험", "kr.mujoong.contract.time..").evaluate(sample).hasViolation())
                .isTrue();
    }

    // ------------------------------------------------------------------ ③ 운영 → 시험 코드 0

    static final String[] TEST_PACKAGES = {
        "kr.mujoong.testsupport..", "org.junit..", "org.mockito..", "org.assertj..", "org.testcontainers..",
        "com.tngtech.archunit..", "org.springframework.test..", "org.springframework.boot.test..",
        "org.java_websocket..",
    };

    static final ArchRule NO_TEST_CODE_IN_PRODUCTION = noDependencyOnPackages(
            "운영 코드는 시험 지원 · 시험 라이브러리에 기대지 않는다(SD-25 · RD-23 · J113 ③)", TEST_PACKAGES);

    @Test
    void productionCodeNeverDependsOnTestSupport() {
        NO_TEST_CODE_IN_PRODUCTION.check(PRODUCTION);
    }

    @Test
    void testCodeRuleFlagsAClassThatUsesTheManualTimerDriver() {
        JavaClasses sample = new ClassFileImporter().importClasses(SampleUsesManualTimer.class);

        assertThat(NO_TEST_CODE_IN_PRODUCTION.evaluate(sample).getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains(ManualTimerDriver.class.getName()));
    }

    // ------------------------------------------------------------------ ④ 프레임 경로 → JDBC · 파일 · Outbox 0

    /** 기기 프레임 경로의 gateway 클래스(수신 · 프레임 검사 · 송신 레인 · 연결 레지스트리 · 출력 묶음). */
    static final List<Class<?>> GATEWAY_FRAME_PATH = List.of(
            DeviceWebSocketHandler.class, WsFrameGuard.class, ConnectionSender.class, DeviceConnection.class,
            DeviceLink.class, SocketRegistry.class, OutputBundle.class);

    static final DescribedPredicate<JavaClass> FRAME_PATH = DescribedPredicate.describe(
            "frame path (gateway frame classes, voice, gate)",
            javaClass -> javaClass.getPackageName().startsWith("kr.mujoong.realtime.voice")
                    || javaClass.getPackageName().startsWith("kr.mujoong.realtime.gate")
                            && !javaClass.getPackageName().startsWith("kr.mujoong.realtime.gateway")
                    || GATEWAY_FRAME_PATH.stream().anyMatch(type -> javaClass.getName().equals(type.getName())
                            || javaClass.getName().startsWith(type.getName() + "$")));

    static final Set<String> FILE_TYPES = Set.of(
            "java.io.File", "java.io.FileInputStream", "java.io.FileOutputStream", "java.io.FileReader",
            "java.io.FileWriter", "java.io.RandomAccessFile", "java.nio.channels.FileChannel");

    /** 영속 API: JDBC · 트랜잭션 · JPA · 파일 · Outbox/Inbox(messaging) · DB 도구(common.db) · 저장소(…Repository). */
    static final DescribedPredicate<JavaClass> PERSISTENCE_API = resideInAnyPackage(
            "java.sql..", "javax.sql..", "org.springframework.jdbc..", "org.springframework.transaction..",
            "org.springframework.dao..", "org.springframework.data..", "jakarta.persistence..", "org.hibernate..",
            "org.postgresql..", "com.zaxxer.hikari..", "java.nio.file..",
            "kr.mujoong.realtime.messaging..", "kr.mujoong.realtime.common.db..")
            .or(DescribedPredicate.describe("file I/O types " + FILE_TYPES,
                    javaClass -> FILE_TYPES.contains(javaClass.getName())))
            .or(DescribedPredicate.describe("Realtime repositories",
                    javaClass -> javaClass.getPackageName().startsWith("kr.mujoong.realtime")
                            && javaClass.getSimpleName().endsWith("Repository")));

    static ArchRule framePathRule(DescribedPredicate<JavaClass> framePath) {
        return noClasses().that(framePath)
                .should().dependOnClassesThat(PERSISTENCE_API)
                .allowEmptyShould(false)
                .because("원음 · 프레임 경로는 메모리 대기열만 쓰고 영속 API에 기대지 않는다(SD-22 · NFR12.2 · J113 ④)");
    }

    @Test
    void framePathNeverTouchesJdbcFilesOrTheOutbox() {
        for (Class<?> type : GATEWAY_FRAME_PATH) {
            assertThat(FRAME_PATH.test(PRODUCTION.get(type))).as(type.getSimpleName() + "는 프레임 경로로 잡힌다").isTrue();
        }
        assertThat(PRODUCTION.stream().filter(FRAME_PATH::test).map(JavaClass::getPackageName).distinct())
                .contains("kr.mujoong.realtime.gateway", "kr.mujoong.realtime.voice", "kr.mujoong.realtime.gate");

        framePathRule(FRAME_PATH).check(PRODUCTION);
    }

    @Test
    void framePathRuleFlagsFileJdbcAndOutboxButNotInMemoryWork() {
        DescribedPredicate<JavaClass> samples = simpleNameStartingWith("FramePathSample");
        JavaClasses imported = new ClassFileImporter().importClasses(FramePathSampleWritesFile.class,
                FramePathSampleUsesJdbc.class, FramePathSampleWritesOutbox.class, FramePathSampleInMemory.class);

        EvaluationResult result = framePathRule(samples).evaluate(imported);

        assertThat(result.getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains(FramePathSampleWritesFile.class.getName()))
                .anyMatch(detail -> detail.contains(FramePathSampleUsesJdbc.class.getName()))
                .anyMatch(detail -> detail.contains(FramePathSampleWritesOutbox.class.getName()))
                .noneMatch(detail -> detail.contains(FramePathSampleInMemory.class.getName()));
    }

    // ------------------------------------------------------------------ ⑤ 시각 직접 읽기 0

    static final ArchRule NO_SYSTEM_TIME = noClasses()
            .should(readTheSystemClockDirectly())
            .allowEmptyShould(false)
            .because("모든 시각은 주입한 MujoongClock 하나로 읽는다(RD-2 R1 · NFR6.4 · J113 ⑤)");

    @Test
    void realtimeNeverReadsTheSystemClockDirectly() {
        NO_SYSTEM_TIME.check(PRODUCTION);
    }

    @Test
    void timeRuleFlagsDirectReadsAndAcceptsTheInjectedClock() {
        JavaClasses offenders = new ClassFileImporter()
                .importClasses(SampleNanoTimeReference.class, SampleZonedNow.class, SampleInstantNowSupplier.class);
        JavaClasses clean = new ClassFileImporter().importClasses(SampleUsesInjectedClock.class);

        assertThat(NO_SYSTEM_TIME.evaluate(offenders).getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains("System.nanoTime"))
                .anyMatch(detail -> detail.contains("ZonedDateTime.now"))
                .anyMatch(detail -> detail.contains("Instant.now"));
        assertThat(NO_SYSTEM_TIME.evaluate(clean).hasViolation()).isFalse();
    }

    // ------------------------------------------------------------------ ⑥ 공급자 클라이언트 의존은 voice 하나(SD-20)

    static final String VOICE_PACKAGE = "kr.mujoong.realtime.voice..";

    static final DescribedPredicate<JavaClass> PROVIDER_CLIENT = DescribedPredicate.describe(
            "the provider client (voice.ProviderClient, java.net.http.WebSocket)",
            javaClass -> javaClass.getName().equals(ProviderClient.class.getName())
                    || javaClass.getName().startsWith(ProviderClient.class.getName() + "$")
                    || javaClass.getName().equals(WebSocket.class.getName())
                    || javaClass.getName().startsWith(WebSocket.class.getName() + "$"));

    static final ArchRule PROVIDER_CLIENT_ONLY_IN_VOICE = noClasses()
            .that().resideOutsideOfPackage(VOICE_PACKAGE)
            .should().dependOnClassesThat(PROVIDER_CLIENT)
            .allowEmptyShould(false)
            .because("공급자로 가는 연결은 voice 어댑터 하나가 맡는다(SD-20 · NFR3.1 · J113 ⑥)");

    static final ArchRule PROVIDER_CLIENT_REALLY_USES_WEBSOCKET = classes()
            .that().haveFullyQualifiedName(ProviderClient.class.getName())
            .should().dependOnClassesThat().haveFullyQualifiedName(WebSocket.class.getName())
            .allowEmptyShould(false)
            .because("규칙 ⑥이 빈 대상을 보지 않게, 공급자 클라이언트가 실제로 JDK WebSocket을 쓰는지 확인한다");

    /** 공급자 요청 레코드(SD-20: 지시문 · 음성 프레임뿐, 아이 정보는 지시문 문장 안에만). */
    static final Set<String> PROVIDER_REQUEST_TYPES = Set.of(
            ProviderRequestFactory.SessionStart.class.getName(),
            ProviderRequestFactory.SessionBody.class.getName(),
            ProviderRequestFactory.AudioSpec.class.getName(),
            ProviderRequestFactory.AudioFormat.class.getName(),
            ProviderRequestFactory.InputAudioAppend.class.getName(),
            ProviderRequestFactory.SessionClose.class.getName(),
            // U11: 지시 추가(편차 (U11-a), 설계 개정 A1)도 공장만 만든다(응답 생성 요청 · 응답 취소 · 지시 교정 레코드는 없어졌다)
            ProviderRequestFactory.InstructionsAppend.class.getName());

    static final ArchRule PROVIDER_REQUESTS_ONLY_FROM_THE_FACTORY = noClasses()
            .that().doNotHaveFullyQualifiedName(ProviderRequestFactory.class.getName())
            .should(constructAProviderRequest())
            .allowEmptyShould(false)
            .because("공급자로 가는 모든 요청은 ProviderRequestFactory 하나에서만 만든다(SD-20 · NFR3.1 · NFR3.2)");

    @Test
    void onlyTheVoicePackageTalksToTheProviderAndOnlyTheFactoryBuildsRequests() {
        PROVIDER_CLIENT_ONLY_IN_VOICE.check(PRODUCTION);
        PROVIDER_CLIENT_REALLY_USES_WEBSOCKET.check(PRODUCTION);
        PROVIDER_REQUESTS_ONLY_FROM_THE_FACTORY.check(PRODUCTION);
    }

    @Test
    void providerRulesFlagAWebSocketUserOutsideVoiceAndAStrayRequestBuilder() {
        JavaClasses imported = new ClassFileImporter()
                .importClasses(SampleOpensWebSocket.class, SampleBuildsProviderRequest.class);

        assertThat(PROVIDER_CLIENT_ONLY_IN_VOICE.evaluate(imported).getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains(SampleOpensWebSocket.class.getName()))
                .noneMatch(detail -> detail.contains(SampleBuildsProviderRequest.class.getName()));
        assertThat(PROVIDER_REQUESTS_ONLY_FROM_THE_FACTORY.evaluate(imported).getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains(SampleBuildsProviderRequest.class.getName())
                        && detail.contains("SessionClose"));
    }

    // ------------------------------------------------------------------ 규칙 조건
    // 조건은 막을 행동을 '했다'로 기록한다(satisfied). noClasses().should(...)가 그 기록을 위반으로 뒤집는다.

    static ArchCondition<JavaClass> constructAProviderRequest() {
        return new ArchCondition<>("construct a provider request record") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (item.getName().startsWith(ProviderRequestFactory.class.getName() + "$")) {
                    return; // 레코드 자신(팩토리 안의 중첩 타입)
                }
                for (JavaCodeUnitAccess<?> access : item.getCodeUnitAccessesFromSelf()) {
                    if (access.getTarget().getName().equals("<init>")
                            && PROVIDER_REQUEST_TYPES.contains(access.getTarget().getOwner().getName())) {
                        events.add(SimpleConditionEvent.satisfied(access, access.getDescription()));
                    }
                }
            }
        };
    }

    /** 시스템 시각을 직접 만드는 API(소유 타입 → 메서드 이름). contract-model · Core 시험과 같은 정의. */
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

    static final class SampleUsesInjectedClock {
        Instant at(MujoongClock clock) {
            return clock.now();
        }
    }

    static final class SampleUsesManualTimer {
        void advance(ManualTimerDriver timers) {
            timers.advance(java.time.Duration.ofMillis(1));
        }
    }

    static final class SampleNanoTimeReference {
        LongSupplier ticker() {
            return System::nanoTime;
        }
    }

    static final class SampleZonedNow {
        Object at() {
            return ZonedDateTime.now();
        }
    }

    static final class SampleInstantNowSupplier {
        Supplier<Instant> clock() {
            return Instant::now;
        }
    }

    static final class FramePathSampleWritesFile {
        void dump(byte[] pcm) throws IOException {
            Files.write(Path.of("frame.pcm"), pcm);
        }
    }

    static final class FramePathSampleUsesJdbc {
        void touch(JdbcTemplate jdbc) {
            jdbc.update("select 1");
        }
    }

    static final class FramePathSampleWritesOutbox {
        UUID publish(RealtimeOutboxWriter outbox, UUID guardianId, Instant now) {
            return outbox.write(null, guardianId, now);
        }
    }

    static final class FramePathSampleInMemory {
        private final java.util.ArrayDeque<byte[]> queue = new java.util.ArrayDeque<>();

        void offer(byte[] pcm) {
            queue.addLast(pcm.clone());
        }
    }

    static final class SampleOpensWebSocket {
        boolean isOpen(WebSocket socket) {
            return !socket.isInputClosed();
        }
    }

    static final class SampleBuildsProviderRequest {
        Object close() {
            return new ProviderRequestFactory.SessionClose("session.close");
        }
    }
}
