package kr.mujoong.realtime.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.common.scaling.SingleInstanceAssumption;
import kr.mujoong.realtime.gate.SeedDictionary;
import kr.mujoong.realtime.gateway.DeviceConnection;
import kr.mujoong.realtime.gateway.DisconnectChecker;
import kr.mujoong.realtime.gateway.SocketRegistry;
import kr.mujoong.realtime.gateway.StatusReporter;
import kr.mujoong.realtime.guardrail.GuardrailEventRecorder;
import kr.mujoong.realtime.judgment.JudgmentLiveRecorder;
import kr.mujoong.realtime.log.AsyncRecorder;
import kr.mujoong.realtime.log.LatencyRecorder;
import kr.mujoong.realtime.messaging.MessagingCleanupJob;
import kr.mujoong.realtime.messaging.RealtimeInboxRepoller;
import kr.mujoong.realtime.messaging.RealtimeOutboxSender;
import kr.mujoong.realtime.runtime.TimerClocks;
import kr.mujoong.realtime.session.OrphanSessionChecker;
import kr.mujoong.realtime.session.SessionExecutor;
import kr.mujoong.realtime.session.SessionRegistry;
import kr.mujoong.realtime.session.SessionTimers;
import kr.mujoong.realtime.session.TombstoneMap;
import kr.mujoong.realtime.startup.RealtimeStartupSequence;
import kr.mujoong.realtime.voice.ProviderClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 단일 인스턴스 전제 표식 구조 시험 — Realtime 판 (U1 3.6 수정 · NFR7.4 · SCD-3 · SCD-4 · BT-NFR7.4 · J310 ~ J315, Core J336 ~ J340과 같은
 * 규칙).
 *
 * <p>scalability-design.md 2.2 표 2.2-A(SI-1 ~ SI-12)와 Realtime 운영 코드의 {@link SingleInstanceAssumption} 표식을 대조한다.
 *
 * <ul>
 *   <li>Realtime 몫 SI마다 표식 클래스가 있고, 그 상태를 실제로 가진 클래스에 그 번호가 달려 있다(아래 지도와 정확히 같다 — 표식을 더하거나
 *       빼면 이 지도를 함께 고친다, J319).
 *   <li>모든 표식의 번호가 표에 있고 Realtime 몫이다(Core 몫 번호를 Realtime 클래스에 달지 않는다). 사유가 비어 있지 않다.
 *   <li>표식 정의는 Realtime 공용 패키지에 있고 공통 계약 라이브러리에는 단일 인스턴스 표식이 없다(SCD-4).
 *   <li>Realtime 몫이 아닌 번호의 전제(SI-5 속도 제한 · SI-8 observedAt · SI-11 프로세스 캐시가 Realtime에 없다)가 코드와 맞다.
 *   <li>검색 점검 병행(SCD-4 점검 열 · 3.6 S13 검색식): 소스에서 '단일 인스턴스' · 'SingleInstance'를 말하는 파일은 표식을 단다.
 * </ul>
 *
 * <p>규칙마다 위반 표본(이 시험 소스의 중첩 클래스)을 실제로 잡는지 자기 시험을 둔다. 표본은 시험 소스 집합이라 운영 가져오기
 * ({@code DO_NOT_INCLUDE_TESTS})에 들어가지 않는다.
 */
@Tag("u1")
class SingleInstanceMarkerTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("kr.mujoong.realtime");

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("mujoong.repo.root", "..")).toAbsolutePath().normalize();

    private static final Path REALTIME_MAIN_SOURCES = REPO_ROOT.resolve("backend/realtime-activity/src/main/java");

    /** 표 2.2-A 번호 형식(SI-1 ~ SI-12). */
    static final Pattern TABLE_NUMBER = Pattern.compile("^SI-([1-9]|1[0-2])$");

    /**
     * Realtime 몫 SI → 그 상태를 가진 운영 클래스(표 2.2-A를 실제 코드로 확인, J311 ~ J314).
     *
     * <ul>
     *   <li>SI-1 소켓 레지스트리(BR6.6 · BR3.7 · BR3.8): 기기 → 현재 연결 지도와 연결 객체(마지막 메시지 시각 · 환산 입력 · HELLO 시계).
     *   <li>SI-2 세션 레지스트리 · 실행기(BR6.8 · BR6.12): 실행기 지도와 실행기. 고아 세션 점검은 '이 인스턴스 레지스트리에 없으면 고아'로
     *       닫으므로 같은 전제다(J314).
     *   <li>SI-3 세션 시계 · 안전 시한 예약(BR6.12 ④): 세션별 예약과 그 예약을 쥔 타이머 스레드 둘.
     *   <li>SI-4 묘비(BR6.7): 10분 묘비 지도.
     *   <li>SI-6 Outbox 발송 · Inbox 재호출 폴러(BR5.2 · BR5.5).
     *   <li>SI-7 정리 · 점검 작업(BR4.8 · BR5.11): 연결 점검 5초 · 상태 보고 10초 · 하루 1회 메시징 정리 · 30초 고아 세션 점검. '10분'
     *       등록 코드 정리는 Core 작업이다.
     *   <li>SI-9 공급자 연결(BR7.2): 세션마다 연결을 여는 클라이언트와 그 연결을 쥔 실행기.
     *   <li>SI-10 기록기 칸(BR7.5): 세션별 미커밋 칸 목록. U11 판정 기록기({@code JudgmentLiveRecorder})와 U13 제브 검사 기록기({@code GuardrailEventRecorder})도 같은 칸 모양의 메모리 대기열이다.
     *   <li>SI-12 12 조회의 '이미 내보낸 행 번호' 집합(NFR5.3): 첫 제공 줄 SESSION_ITEMS_SERVED와 그 집합은 Realtime 지연 기록기에 있다
     *       (Core는 items를 중계만 한다, J312 — Core 시험의 몫 밖 근거와 같다).
     *   <li>기동 복구(BR6.13 · R3-5)는 DB의 비종료 세션 · 연결 행 · 선점 행을 모두 자기 것으로 보고 정리하므로 SI-1 · SI-2 · SI-6에 함께
     *       든다(scalability-design 2.1 '이 구조는 단일 인스턴스를 전제한다', J313).
     * </ul>
     */
    static final Map<String, Set<Class<?>>> REALTIME_SHARE = Map.of(
            "SI-1", Set.of(SocketRegistry.class, DeviceConnection.class, RealtimeStartupSequence.class),
            "SI-2", Set.of(SessionRegistry.class, SessionExecutor.class, OrphanSessionChecker.class,
                    RealtimeStartupSequence.class),
            "SI-3", Set.of(SessionTimers.class, TimerClocks.class),
            "SI-4", Set.of(TombstoneMap.class),
            "SI-6", Set.of(RealtimeOutboxSender.class, RealtimeInboxRepoller.class, RealtimeStartupSequence.class),
            "SI-7", Set.of(DisconnectChecker.class, StatusReporter.class, MessagingCleanupJob.class,
                    OrphanSessionChecker.class),
            "SI-9", Set.of(ProviderClient.class, SessionExecutor.class),
            "SI-10", Set.of(AsyncRecorder.class, JudgmentLiveRecorder.class, GuardrailEventRecorder.class),
            "SI-12", Set.of(LatencyRecorder.class));

    /**
     * Realtime 몫이 아닌 번호와 근거(J311 · J315).
     *
     * <ul>
     *   <li>SI-5 속도 제한 카운터 · SI-8 observedAt: Core의 상태다(BR1.6 · BR2.6 · BR6.15, 12의 observedAt · stale은 Core가 채운다).
     *   <li>SI-11 프로세스 캐시(승인 문구 · 정책 버전): Core의 PD-7 캐시 자리다. Realtime은 승인 문구 · 정책 판을 세션 시작 패키지로 받아 세션에
     *       고정하고, 시드 금지 표현 사전은 기동 때 한 번 읽는 정적 자원이라 TTL이 없다(performance-design 2.5 표 2.5-A). 캐시가 생기면 그
     *       클래스에 표식을 달고 이 번호를 {@link #REALTIME_SHARE}로 옮긴다.
     * </ul>
     */
    static final Set<String> NOT_REALTIME = Set.of("SI-5", "SI-8", "SI-11");

    /** 프로세스 캐시 라이브러리 패키지(SI-11 전제 점검). */
    static final List<String> CACHE_PACKAGES = List.of("org.springframework.cache", "com.github.benmanes.caffeine",
            "javax.cache", "jakarta.cache");

    // ------------------------------------------------------------------ 표와 표식 대조

    @Test
    void everyRealtimeShareNumberIsMarkedOnExactlyTheClassesHoldingThatState() {
        assertThat(PRODUCTION.size()).as("가져온 Realtime 운영 클래스 수(빈 가져오기로 통과하지 않음)").isGreaterThan(100);
        Set<String> accounted = new TreeSet<>(REALTIME_SHARE.keySet());
        accounted.addAll(NOT_REALTIME);
        assertThat(accounted).as("표 2.2-A의 12곳이 Realtime 몫이거나 근거 있는 몫 밖이다")
                .isEqualTo(IntStream.rangeClosed(1, 12).mapToObj(n -> "SI-" + n).collect(Collectors.toCollection(TreeSet::new)));
        assertThat(REALTIME_SHARE.keySet()).doesNotContainAnyElementsOf(NOT_REALTIME);

        Map<String, Set<String>> marked = markedClassesByNumber(PRODUCTION);

        assertThat(missingShareNumbers(PRODUCTION)).as("표식 클래스가 없는 Realtime 몫 번호").isEmpty();
        assertThat(marked.keySet()).as("표식에 쓰인 번호").isEqualTo(REALTIME_SHARE.keySet());
        REALTIME_SHARE.forEach((number, holders) -> assertThat(marked.get(number))
                .as("%s 상태를 가진 클래스", number)
                .isEqualTo(holders.stream().map(Class::getName).collect(Collectors.toCollection(TreeSet::new))));
    }

    @Test
    void everyMarkerNamesOnlyRealtimeShareNumbersOfTheTableWithAReason() {
        assertThat(markedClassesByNumber(PRODUCTION)).as("표식이 하나 이상 있다").isNotEmpty();

        assertThat(markerViolations(PRODUCTION)).as("표 밖 · Realtime 몫 밖 번호, 빈 번호 · 사유, 중복 번호").isEmpty();
    }

    @Test
    void markerRuleFlagsOutOfTableCoreShareDuplicateAndBlankMarkers() {
        JavaClasses offenders = new ClassFileImporter().importClasses(SampleOutOfTable.class, SampleCoreNumber.class,
                SampleDuplicateNumberAndBlankReason.class, SampleNoNumber.class);
        JavaClasses clean = new ClassFileImporter().importClasses(SampleRealtimeMarker.class);

        List<String> violations = markerViolations(offenders);

        assertThat(violations)
                .anyMatch(line -> line.contains(SampleOutOfTable.class.getSimpleName()) && line.contains("SI-0"))
                .anyMatch(line -> line.contains(SampleOutOfTable.class.getSimpleName()) && line.contains("SI-13"))
                .anyMatch(line -> line.contains(SampleCoreNumber.class.getSimpleName()) && line.contains("SI-5"))
                .anyMatch(line -> line.contains(SampleCoreNumber.class.getSimpleName()) && line.contains("SI-8"))
                .anyMatch(line -> line.contains(SampleDuplicateNumberAndBlankReason.class.getSimpleName())
                        && line.contains("중복"))
                .anyMatch(line -> line.contains(SampleDuplicateNumberAndBlankReason.class.getSimpleName())
                        && line.contains("사유"))
                .anyMatch(line -> line.contains(SampleNoNumber.class.getSimpleName()) && line.contains("번호가 없다"));
        assertThat(markerViolations(clean)).isEmpty();
    }

    @Test
    void coverageRuleReportsEveryShareNumberWithoutAMarkedClass() {
        JavaClasses onlySi4 = new ClassFileImporter().importClasses(SampleRealtimeMarker.class);
        JavaClasses none = new ClassFileImporter().importClasses(SampleUnmarked.class);

        assertThat(missingShareNumbers(onlySi4))
                .containsExactly("SI-1", "SI-10", "SI-12", "SI-2", "SI-3", "SI-6", "SI-7", "SI-9");
        assertThat(missingShareNumbers(none)).hasSize(REALTIME_SHARE.size()).contains("SI-4");
    }

    // ------------------------------------------------------------------ SCD-4 정의 위치

    @Test
    void markerIsDefinedInTheRealtimeCommonPackageAndNotInTheContractLibrary() {
        assertThat(SingleInstanceAssumption.class.getPackageName()).startsWith("kr.mujoong.realtime.common.");
        assertThat(SingleInstanceAssumption.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(SingleInstanceAssumption.class.getAnnotation(Target.class).value()).containsExactly(ElementType.TYPE);

        JavaClasses contract = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("kr.mujoong.contract");
        assertThat(contract.size()).as("가져온 계약 라이브러리 클래스 수").isGreaterThan(20);
        assertThat(contract.stream().map(JavaClass::getName).filter(name -> name.contains("SingleInstance")).toList())
                .as("공통 계약 라이브러리의 단일 인스턴스 표식 정의").isEmpty();
        assertThat(contract.stream().filter(type -> type.isAnnotatedWith(SingleInstanceAssumption.class))
                .map(JavaClass::getName).toList()).as("계약 라이브러리 클래스에 단 Realtime 표식").isEmpty();
    }

    // ------------------------------------------------------------------ Realtime 몫 밖 번호의 전제

    @Test
    void premisesOfTheNumbersOutsideTheRealtimeShareHoldInRealtimeCode() {
        // SI-5: Realtime에는 요청 속도 제한기가 없다
        assertThat(rateLimiterClasses(PRODUCTION)).as("속도 제한기가 생겼다 — SI-5 표식과 몫을 다시 본다").isEmpty();
        assertThat(rateLimiterClasses(new ClassFileImporter().importClasses(SampleRateLimiter.class)))
                .as("표본은 잡힌다").containsExactly(SampleRateLimiter.class.getName());

        // SI-8: observedAt · stale 상태를 Realtime 클래스가 들지 않는다(Core가 채운다)
        assertThat(observedAtFields(PRODUCTION)).as("observedAt · stale 상태 — SI-8 표식이 필요해진다").isEmpty();
        assertThat(observedAtFields(new ClassFileImporter().importClasses(SampleObservedAtHolder.class)))
                .as("표본은 잡힌다").containsExactlyInAnyOrder("SampleObservedAtHolder.observedAt",
                        "SampleObservedAtHolder.stale");

        // SI-11: 캐시 라이브러리 의존이 없고, 시드 사전은 기동 때 한 번 읽은 불변 값이다(TTL · 시계 없음)
        assertThat(cacheDependents(PRODUCTION)).as("프로세스 캐시 의존 — SI-11 표식을 달고 REALTIME_SHARE로 옮긴다").isEmpty();
        assertThat(cacheDependents(new ClassFileImporter().importClasses(SampleSpringCacheUser.class)))
                .as("표본은 잡힌다").containsExactly(SampleSpringCacheUser.class.getName());
        assertThat(ttlStateFields(PRODUCTION.get(SeedDictionary.class))).as("시드 사전의 가변 · TTL 상태").isEmpty();
        assertThat(ttlStateFields(new ClassFileImporter().importClasses(SampleTtlCache.class).get(SampleTtlCache.class)))
                .as("TTL 캐시 표본은 잡힌다").containsExactlyInAnyOrder("clock", "ttl", "loadedAt");
    }

    // ------------------------------------------------------------------ 검색 점검 병행

    @Test
    void sourcesThatMentionTheSingleInstancePremiseCarryTheAnnotation() {
        List<Path> sources = javaFiles(REALTIME_MAIN_SOURCES);
        assertThat(sources).as("Realtime 운영 소스").hasSizeGreaterThan(100);
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

    /** 표식 클래스가 하나도 없는 Realtime 몫 번호(정렬). */
    static List<String> missingShareNumbers(JavaClasses classes) {
        Map<String, Set<String>> marked = markedClassesByNumber(classes);
        return REALTIME_SHARE.keySet().stream().filter(number -> !marked.containsKey(number)).sorted().toList();
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
                    } else if (!REALTIME_SHARE.containsKey(number)) {
                        violations.add(name + ": Realtime 몫이 아닌 번호 " + number);
                    }
                }
                if (marker.reason() == null || marker.reason().isBlank()) {
                    violations.add(name + ": 사유가 비었다");
                }
            });
        }
        return violations;
    }

    /** 이름이 속도 제한기인 클래스(정렬). */
    static List<String> rateLimiterClasses(JavaClasses classes) {
        return classes.stream()
                .filter(type -> {
                    String simple = type.getSimpleName().toLowerCase(Locale.ROOT);
                    return simple.contains("ratelimit") || simple.endsWith("limiter");
                })
                .map(JavaClass::getName)
                .sorted()
                .toList();
    }

    /** observedAt · stale 상태 필드(클래스 단순 이름.필드). */
    static List<String> observedAtFields(JavaClasses classes) {
        List<String> fields = new ArrayList<>();
        for (JavaClass type : classes) {
            for (JavaField field : type.getFields()) {
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (!field.getModifiers().contains(JavaModifier.STATIC)
                        && (name.equals("observedat") || name.equals("stale"))) {
                    fields.add(type.getSimpleName() + "." + field.getName());
                }
            }
        }
        return fields;
    }

    /** 캐시 라이브러리 패키지에 직접 기대는 클래스(정렬). */
    static List<String> cacheDependents(JavaClasses classes) {
        return classes.stream()
                .filter(type -> type.getDirectDependenciesFromSelf().stream().anyMatch(SingleInstanceMarkerTest::isCache))
                .map(JavaClass::getName)
                .sorted()
                .toList();
    }

    private static boolean isCache(Dependency dependency) {
        String target = dependency.getTargetClass().getPackageName();
        return CACHE_PACKAGES.stream().anyMatch(prefix -> target.equals(prefix) || target.startsWith(prefix + "."));
    }

    /** 불변 값이 아닌 인스턴스 필드(final이 아님) 또는 TTL 재료(시계 · 길이 · 시각) 필드 이름. */
    static List<String> ttlStateFields(JavaClass type) {
        Set<String> ttlTypes = Set.of(MujoongClock.class.getName(), Duration.class.getName(), Instant.class.getName());
        List<String> fields = new ArrayList<>();
        for (JavaField field : type.getFields()) {
            if (field.getModifiers().contains(JavaModifier.STATIC) || field.getModifiers().contains(JavaModifier.SYNTHETIC)) {
                continue;
            }
            if (!field.getModifiers().contains(JavaModifier.FINAL) || ttlTypes.contains(field.getRawType().getName())) {
                fields.add(field.getName());
            }
        }
        return fields;
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

    @SingleInstanceAssumption(si = {"SI-0", "SI-13"}, reason = "표에 없는 번호")
    static final class SampleOutOfTable {
    }

    @SingleInstanceAssumption(si = {"SI-5", "SI-8"}, reason = "Core 몫 번호를 Realtime에 달았다")
    static final class SampleCoreNumber {
    }

    @SingleInstanceAssumption(si = {"SI-4", "SI-4"}, reason = " ")
    static final class SampleDuplicateNumberAndBlankReason {
    }

    @SingleInstanceAssumption(si = {}, reason = "번호 없음")
    static final class SampleNoNumber {
    }

    @SingleInstanceAssumption(si = "SI-4", reason = "묘비가 인스턴스마다 따로다")
    static final class SampleRealtimeMarker {
    }

    static final class SampleUnmarked {
    }

    static final class SampleRateLimiter {
    }

    static final class SampleObservedAtHolder {
        private Instant observedAt;
        private boolean stale;

        boolean isStale(Instant now) {
            return stale || observedAt == null || observedAt.isBefore(now);
        }
    }

    static final class SampleSpringCacheUser {
        private final org.springframework.cache.Cache cache;

        SampleSpringCacheUser(org.springframework.cache.Cache cache) {
            this.cache = cache;
        }

        Object get(Object key) {
            return cache.get(key);
        }
    }

    static final class SampleTtlCache {
        private final MujoongClock clock;
        private final Duration ttl;
        private final Map<String, String> values = new HashMap<>();
        private Instant loadedAt;

        SampleTtlCache(MujoongClock clock, Duration ttl) {
            this.clock = clock;
            this.ttl = ttl;
        }

        String get(String key) {
            if (loadedAt == null || clock.now().isAfter(loadedAt.plus(ttl))) {
                values.clear();
                loadedAt = clock.now();
            }
            return values.get(key);
        }
    }
}
