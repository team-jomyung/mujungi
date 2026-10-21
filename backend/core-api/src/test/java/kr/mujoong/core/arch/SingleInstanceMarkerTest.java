package kr.mujoong.core.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.core.activity.ApprovedContentCatalog;
import kr.mujoong.core.activity.PreparingTimeoutChecker;
import kr.mujoong.core.activity.SessionViewService;
import kr.mujoong.core.common.scaling.SingleInstanceAssumption;
import kr.mujoong.core.consent.PolicyTermsReader;
import kr.mujoong.core.device.DeviceExpiryCleanupJob;
import kr.mujoong.core.messaging.CoreInboxRepoller;
import kr.mujoong.core.messaging.CoreOutboxSender;
import kr.mujoong.core.messaging.MessagingCleanupJob;
import kr.mujoong.core.security.ratelimit.SlidingWindowRateLimiter;
import kr.mujoong.core.startup.CoreStartupSequence;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 단일 인스턴스 전제 표식 구조 시험 (U1 3.6 수정 · NFR7.4 · SCD-3 · SCD-4 · BT-NFR7.4 · J336 ~ J340).
 *
 * <p>scalability-design.md 2.2 표 2.2-A(SI-1 ~ SI-12)와 Core 운영 코드의 {@link SingleInstanceAssumption} 표식을 대조한다.
 *
 * <ul>
 *   <li>Core 몫 SI마다 표식 클래스가 있고, 그 상태를 실제로 가진 클래스에 그 번호가 달려 있다.
 *   <li>모든 표식의 번호가 표에 있고 Core 몫이다(Realtime 몫 번호를 Core 클래스에 달지 않는다). 사유가 비어 있지 않다.
 *   <li>표식 정의는 Core 공용 패키지에 있고 공통 계약 라이브러리에는 단일 인스턴스 표식이 없다(SCD-4).
 *   <li>Core 몫이 아닌 번호의 전제(SI-11 캐시 없음 · SI-12 첫 제공 로그 없음)가 코드와 맞다.
 *   <li>검색 점검 병행(SCD-4 점검 열 · 3.6 S13 검색식): 소스에서 '단일 인스턴스' · 'SingleInstance'를 말하는 파일은 표식을 단다.
 * </ul>
 *
 * <p>규칙마다 위반 표본(이 시험 소스의 중첩 클래스)을 실제로 잡는지 자기 시험을 둔다. 표본은 시험 소스 집합이라 운영 가져오기
 * ({@code DO_NOT_INCLUDE_TESTS})에 들어가지 않는다.
 */
@Tag("skeleton")
@org.junit.jupiter.api.Tag("service-guardrail")
class SingleInstanceMarkerTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("kr.mujoong.core");

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("mujoong.repo.root", "..")).toAbsolutePath().normalize();

    private static final Path CORE_MAIN_SOURCES = REPO_ROOT.resolve("backend/core-api/src/main/java");

    /** 표 2.2-A 번호 형식(SI-1 ~ SI-12). */
    static final Pattern TABLE_NUMBER = Pattern.compile("^SI-([1-9]|1[0-2])$");

    /**
     * Core 몫 SI → 그 상태를 가진 운영 클래스(표 2.2-A를 실제 코드로 확인, J336 ~ J338).
     *
     * <ul>
     *   <li>SI-5 Core 속도 제한 카운터(BR1.6 · BR2.6): 한도기의 키별 시각 기록.
     *   <li>SI-6 Outbox 발송 · Inbox 재호출 폴러(BR5.2 · BR5.5): 두 폴러와, 선점을 모두 걷는 기동 복구 S3c · S3f(reliability-design S3f가
     *       '단일 인스턴스라 다른 폴러가 선점 중인 행을 되돌릴 일이 없어 안전'이라고 적는다, J337).
     *   <li>SI-7 정리 · 점검 작업(BR1.4 · BR5.11): 10분 등록 코드 정리 · 하루 1회 메시징 정리와 maintenance 스레드의 1초 PREPARING 점검(J338).
     *       '연결 점검 5초 · 상태 보고 10초'(BR4.8)는 Realtime 작업이다.
     *   <li>SI-8 Core observedAt(BR6.15): 12 진행 조회의 조회 성공 시각.
     * </ul>
     */
    static final Map<String, Set<Class<?>>> CORE_SHARE = Map.of(
            "SI-5", Set.of(SlidingWindowRateLimiter.class),
            "SI-6", Set.of(CoreOutboxSender.class, CoreInboxRepoller.class, CoreStartupSequence.class),
            "SI-7", Set.of(DeviceExpiryCleanupJob.class, MessagingCleanupJob.class, PreparingTimeoutChecker.class),
            "SI-8", Set.of(SessionViewService.class));

    /**
     * Core 몫이 아닌 번호와 근거(J336).
     *
     * <ul>
     *   <li>SI-1 · SI-2 · SI-3 · SI-4 · SI-9 · SI-10: Realtime의 소켓 · 세션 레지스트리, 세션 시계 · 시한 예약, 묘비, 공급자 연결, 기록기 칸.
     *   <li>SI-11 프로세스 캐시: U1 Core는 승인 문구 · 정책 버전을 매번 DB에서 읽는다(PD-7 캐시는 B1 이후, contracts/timing.md
     *       {@code mujoong.timing.cache.*} 행). 캐시가 생기면 그 클래스에 표식을 달고 이 번호를 {@link #CORE_SHARE}로 옮긴다.
     *   <li>SI-12 '이미 내보낸 행 번호' 집합: 12 조회의 첫 제공 로그(SESSION_ITEMS_SERVED)와 그 집합은 Realtime(LatencyRecorder)에 있다. Core는
     *       items를 중계만 한다.
     * </ul>
     */
    static final Set<String> NOT_CORE = Set.of("SI-1", "SI-2", "SI-3", "SI-4", "SI-9", "SI-10", "SI-11", "SI-12");

    /** SI-11 전제를 지는 클래스: 승인 문구 · 현재 정책 버전 읽기. */
    // U4a: 시작 동의 판정이 U1 ConsentGate에서 계약 8절 포트(ConsentPolicyService)로 옮겼다. 그 포트가 현재 정책 버전을 읽는 곳은
    // PolicyTermsReader(→ 카탈로그)라 그 클래스가 전제를 진다
    static final List<Class<?>> SI_11_PREMISE = List.of(ApprovedContentCatalog.class, PolicyTermsReader.class);

    // ------------------------------------------------------------------ 표와 표식 대조

    @Test
    void everyCoreShareNumberIsMarkedOnTheClassesHoldingThatState() {
        assertThat(PRODUCTION.size()).as("가져온 Core 운영 클래스 수(빈 가져오기로 통과하지 않음)").isGreaterThan(100);
        Set<String> accounted = new TreeSet<>(CORE_SHARE.keySet());
        accounted.addAll(NOT_CORE);
        assertThat(accounted).as("표 2.2-A의 12곳이 Core 몫이거나 근거 있는 몫 밖이다")
                .isEqualTo(IntStream.rangeClosed(1, 12).mapToObj(n -> "SI-" + n).collect(Collectors.toCollection(TreeSet::new)));
        assertThat(CORE_SHARE.keySet()).doesNotContainAnyElementsOf(NOT_CORE);

        Map<String, Set<String>> marked = markedClassesByNumber(PRODUCTION);

        assertThat(missingShareNumbers(PRODUCTION)).as("표식 클래스가 없는 Core 몫 번호").isEmpty();
        CORE_SHARE.forEach((number, holders) -> assertThat(marked.getOrDefault(number, Set.of()))
                .as("%s 상태를 가진 클래스", number)
                .containsAll(holders.stream().map(Class::getName).toList()));
    }

    @Test
    void everyMarkerNamesOnlyCoreShareNumbersOfTheTableWithAReason() {
        assertThat(markedClassesByNumber(PRODUCTION)).as("표식이 하나 이상 있다").isNotEmpty();

        assertThat(markerViolations(PRODUCTION)).as("표 밖 · Core 몫 밖 번호, 빈 번호 · 사유, 중복 번호").isEmpty();
    }

    @Test
    void markerRuleFlagsOutOfTableNonCoreDuplicateAndBlankMarkers() {
        JavaClasses offenders = new ClassFileImporter().importClasses(SampleOutOfTable.class, SampleRealtimeNumber.class,
                SampleDuplicateNumberAndBlankReason.class, SampleNoNumber.class);
        JavaClasses clean = new ClassFileImporter().importClasses(SampleCoreMarker.class);

        List<String> violations = markerViolations(offenders);

        assertThat(violations)
                .anyMatch(line -> line.contains(SampleOutOfTable.class.getSimpleName()) && line.contains("SI-13"))
                .anyMatch(line -> line.contains(SampleOutOfTable.class.getSimpleName()) && line.contains("SI5"))
                .anyMatch(line -> line.contains(SampleRealtimeNumber.class.getSimpleName()) && line.contains("SI-1"))
                .anyMatch(line -> line.contains(SampleDuplicateNumberAndBlankReason.class.getSimpleName()) && line.contains("중복"))
                .anyMatch(line -> line.contains(SampleDuplicateNumberAndBlankReason.class.getSimpleName()) && line.contains("사유"))
                .anyMatch(line -> line.contains(SampleNoNumber.class.getSimpleName()) && line.contains("번호가 없다"));
        assertThat(markerViolations(clean)).isEmpty();
    }

    @Test
    void coverageRuleReportsEveryShareNumberWithoutAMarkedClass() {
        JavaClasses onlySi8 = new ClassFileImporter().importClasses(SampleCoreMarker.class);
        JavaClasses none = new ClassFileImporter().importClasses(SampleUnmarked.class);

        assertThat(missingShareNumbers(onlySi8)).containsExactly("SI-5", "SI-6", "SI-7");
        assertThat(missingShareNumbers(none)).containsExactly("SI-5", "SI-6", "SI-7", "SI-8");
    }

    // ------------------------------------------------------------------ SCD-4 정의 위치

    @Test
    void markerIsDefinedInTheCoreCommonPackageAndNotInTheContractLibrary() {
        assertThat(SingleInstanceAssumption.class.getPackageName()).startsWith("kr.mujoong.core.common.");
        assertThat(SingleInstanceAssumption.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(SingleInstanceAssumption.class.getAnnotation(Target.class).value()).containsExactly(ElementType.TYPE);

        JavaClasses contract = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("kr.mujoong.contract");
        assertThat(contract.size()).as("가져온 계약 라이브러리 클래스 수").isGreaterThan(20);
        assertThat(contract.stream().map(JavaClass::getName).filter(name -> name.contains("SingleInstance")).toList())
                .as("공통 계약 라이브러리의 단일 인스턴스 표식 정의").isEmpty();
        assertThat(contract.stream().filter(type -> type.isAnnotatedWith(SingleInstanceAssumption.class))
                .map(JavaClass::getName).toList()).as("계약 라이브러리 클래스에 단 Core 표식").isEmpty();
    }

    // ------------------------------------------------------------------ Core 몫 밖 번호의 전제

    @Test
    void premisesOfTheNumbersOutsideTheCoreShareHoldInCoreCode() {
        // SI-11: 승인 문구 · 정책 버전 읽기는 협력 객체 참조만 갖는다(캐시 상태 없음)
        for (Class<?> premise : SI_11_PREMISE) {
            assertThat(cacheLikeFields(PRODUCTION.get(premise)))
                    .as("%s에 캐시 상태가 생겼다 — SI-11 표식을 달고 CORE_SHARE로 옮긴다", premise.getSimpleName()).isEmpty();
        }
        assertThat(cacheLikeFields(new ClassFileImporter().importClasses(SampleCachingCatalog.class)
                .get(SampleCachingCatalog.class))).as("캐시 필드 표본은 잡힌다").containsExactlyInAnyOrder("cache", "loadedAt");

        // SI-12: 첫 제공 로그(와 그 중복 제거 집합)는 Core에 없다
        assertThat(itemsServedLoggers(PRODUCTION)).as("SESSION_ITEMS_SERVED를 쓰는 Core 클래스 — SI-12 표식이 필요해진다")
                .isEmpty();
        assertThat(itemsServedLoggers(new ClassFileImporter().importClasses(SampleItemsServedLogger.class)))
                .as("표본은 잡힌다").containsExactly(SampleItemsServedLogger.class.getName());
    }

    // ------------------------------------------------------------------ 검색 점검 병행

    @Test
    void sourcesThatMentionTheSingleInstancePremiseCarryTheAnnotation() {
        List<Path> sources = javaFiles(CORE_MAIN_SOURCES);
        assertThat(sources).as("Core 운영 소스").hasSizeGreaterThan(100);
        String definition = SingleInstanceAssumption.class.getSimpleName() + ".java";

        List<String> mentioning = new ArrayList<>();
        List<String> textOnly = new ArrayList<>();
        for (Path file : sources) {
            String source = read(file);
            if (file.getFileName().toString().equals(definition)
                    || !(source.contains("단일 인스턴스") || source.contains("SingleInstance"))) {
                continue;
            }
            mentioning.add(file.getFileName().toString());
            if (!source.contains("@SingleInstanceAssumption(")) {
                textOnly.add(REPO_ROOT.relativize(file).toString());
            }
        }

        assertThat(textOnly).as("주석 · 문자열로만 단일 인스턴스 전제를 말하는 파일(표식으로 바꾼다)").isEmpty();
        Set<String> markedFiles = PRODUCTION.stream()
                .filter(type -> type.isAnnotatedWith(SingleInstanceAssumption.class))
                .map(type -> type.getSimpleName() + ".java")
                .collect(Collectors.toSet());
        assertThat(new HashSet<>(mentioning)).as("3.6 S13 검색이 세는 파일 = 표식 클래스 파일").isEqualTo(markedFiles);
    }

    // ------------------------------------------------------------------ 검사기

    /** 표식 번호 → 그 번호를 단 클래스 이름. */
    static Map<String, Set<String>> markedClassesByNumber(JavaClasses classes) {
        Map<String, Set<String>> marked = new TreeMap<>();
        for (JavaClass type : classes) {
            type.tryGetAnnotationOfType(SingleInstanceAssumption.class).ifPresent(marker -> {
                for (String number : marker.si()) {
                    marked.computeIfAbsent(number, key -> new TreeSet<>()).add(type.getName());
                }
            });
        }
        return marked;
    }

    /** 표식 클래스가 하나도 없는 Core 몫 번호(정렬). */
    static List<String> missingShareNumbers(JavaClasses classes) {
        Map<String, Set<String>> marked = markedClassesByNumber(classes);
        return CORE_SHARE.keySet().stream().filter(number -> !marked.containsKey(number)).sorted().toList();
    }

    /** 표식 규칙 위반(클래스 이름 + 사유). */
    static List<String> markerViolations(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass type : classes) {
            type.tryGetAnnotationOfType(SingleInstanceAssumption.class).ifPresent(marker -> {
                String name = type.getName();
                if (marker.si().length == 0) {
                    violations.add(name + ": SI 번호가 없다");
                }
                Set<String> seen = new HashSet<>();
                for (String number : marker.si()) {
                    if (!seen.add(number)) {
                        violations.add(name + ": 중복 번호 " + number);
                    }
                    if (!TABLE_NUMBER.matcher(number).matches()) {
                        violations.add(name + ": 표 2.2-A에 없는 번호 " + number);
                    } else if (!CORE_SHARE.containsKey(number)) {
                        violations.add(name + ": Core 몫이 아닌 번호 " + number);
                    }
                }
                if (marker.reason() == null || marker.reason().isBlank()) {
                    violations.add(name + ": 사유가 비었다");
                }
            });
        }
        return violations;
    }

    /** 협력 객체 참조가 아닌 인스턴스 필드(final이 아니거나 Core · 계약 타입이 아닌 필드) 이름. */
    static List<String> cacheLikeFields(JavaClass type) {
        List<String> fields = new ArrayList<>();
        for (JavaField field : type.getFields()) {
            if (field.getModifiers().contains(JavaModifier.STATIC) || field.getModifiers().contains(JavaModifier.SYNTHETIC)) {
                continue;
            }
            boolean collaborator = field.getModifiers().contains(JavaModifier.FINAL)
                    && field.getRawType().getPackageName().startsWith("kr.mujoong.");
            if (!collaborator) {
                fields.add(field.getName());
            }
        }
        return fields;
    }

    /** {@code EventCode.SESSION_ITEMS_SERVED}를 읽는 클래스 이름(정렬). */
    static List<String> itemsServedLoggers(JavaClasses classes) {
        return classes.stream()
                .filter(type -> type.getFieldAccessesFromSelf().stream().anyMatch(SingleInstanceMarkerTest::readsItemsServed))
                .map(JavaClass::getName)
                .sorted()
                .toList();
    }

    private static boolean readsItemsServed(JavaFieldAccess access) {
        return access.getTarget().getOwner().isEquivalentTo(EventCode.class)
                && access.getTarget().getName().equals(EventCode.SESSION_ITEMS_SERVED.name());
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).sorted().toList();
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

    // ------------------------------------------------------------------ 위반 · 통과 표본(시험 소스 전용)

    @SingleInstanceAssumption(si = {"SI-13", "SI5"}, reason = "표에 없는 번호")
    static final class SampleOutOfTable {
    }

    @SingleInstanceAssumption(si = "SI-1", reason = "Realtime 몫 번호를 Core에 달았다")
    static final class SampleRealtimeNumber {
    }

    @SingleInstanceAssumption(si = {"SI-5", "SI-5"}, reason = " ")
    static final class SampleDuplicateNumberAndBlankReason {
    }

    @SingleInstanceAssumption(si = {}, reason = "번호 없음")
    static final class SampleNoNumber {
    }

    @SingleInstanceAssumption(si = "SI-8", reason = "조회 성공 시각이 인스턴스마다 다르다")
    static final class SampleCoreMarker {
    }

    static final class SampleUnmarked {
    }

    static final class SampleCachingCatalog {
        private final ApprovedContentCatalog delegate;
        private final Map<String, String> cache = new ConcurrentHashMap<>();
        private long loadedAt;

        SampleCachingCatalog(ApprovedContentCatalog delegate) {
            this.delegate = delegate;
        }

        String text(String code) {
            loadedAt++;
            return cache.computeIfAbsent(code, key -> String.valueOf(delegate));
        }
    }

    static final class SampleItemsServedLogger {
        Object code() {
            return EventCode.SESSION_ITEMS_SERVED;
        }
    }
}
