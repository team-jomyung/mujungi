package kr.mujoong.contract.arch;

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
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.SystemMujoongClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * contract-model 구조 규칙(U1 3.5 Step 36 · J113 ② ⑤ · LC-11 · RD-2 R1 · NFR6.4).
 *
 * <ul>
 *   <li>② 공유 라이브러리에 Entity · Repository · Service · Spring(Boot) 설정이 없다. Spring · JPA · JDBC 의존 0, 그 애너테이션 0,
 *       이름 규칙(…Repository · …Service · …Entity · …Configuration · …Application) 0, Boot 설정 자원 0.
 *   <li>⑤ 시스템 시각 직접 읽기(System.currentTimeMillis · nanoTime, java.time *.now, Clock.system*, new Date() 등)는 시간 어댑터
 *       {@link SystemMujoongClock} 한 곳뿐이다(메서드 참조 {@code Instant::now}도 읽기로 본다).
 * </ul>
 *
 * <p>규칙이 빈 대상으로 통과하지 않게 {@code allowEmptyShould(false)}를 걸고, 규칙마다 위반 표본(이 시험 소스의 중첩 클래스)을
 * 실제로 잡는지 자기 시험을 둔다. 표본은 시험 소스 집합이라 운영 가져오기({@code DO_NOT_INCLUDE_TESTS})에 들어가지 않는다.
 */
@Tag("u1")
class ContractModelArchTest {

    private static final String CONTRACT_PACKAGE = "kr.mujoong.contract";

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(CONTRACT_PACKAGE);

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("mujoong.repo.root", "..")).toAbsolutePath().normalize();

    // ------------------------------------------------------------------ ② Entity · Repository · Spring 설정 0

    /** 계약 모듈이 기대면 안 되는 프레임워크 패키지(Spring · Boot · JPA · Hibernate · JDBC · 서블릿). */
    static final String[] FRAMEWORK_PACKAGES = {
        "org.springframework..", "jakarta.persistence..", "javax.persistence..", "org.hibernate..",
        "jakarta.servlet..", "java.sql..", "javax.sql..", "jakarta.transaction..",
    };

    /** 이름으로 막는 애너테이션(시험 클래스패스에 없어도 이름으로 본다). */
    static final List<String> FRAMEWORK_ANNOTATIONS = List.of(
            "jakarta.persistence.Entity", "jakarta.persistence.Embeddable", "jakarta.persistence.MappedSuperclass",
            "jakarta.persistence.Table", "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Service", "org.springframework.stereotype.Component",
            "org.springframework.context.annotation.Configuration",
            "org.springframework.boot.autoconfigure.AutoConfiguration",
            "org.springframework.boot.autoconfigure.SpringBootApplication",
            "org.springframework.boot.context.properties.ConfigurationProperties");

    static final ArchRule NO_FRAMEWORK_DEPENDENCY = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORK_PACKAGES)
            .allowEmptyShould(false)
            .because("contract-model은 계약 · 오류 · 시간 · 타입만 두고 Entity · Repository · Boot 설정을 담지 않는다(LC-11 · J113 ②)");

    static final ArchRule NO_FRAMEWORK_ANNOTATION = noClasses()
            .should(beAnnotatedWithAnyOf(FRAMEWORK_ANNOTATIONS))
            .allowEmptyShould(false)
            .because("공유 라이브러리에 Entity · 저장소 · 서비스 · 설정 빈을 두지 않는다(LC-11 · J113 ②)");

    static final ArchRule NO_LAYER_NAMED_CLASSES = noClasses()
            .should().haveSimpleNameEndingWith("Repository")
            .orShould().haveSimpleNameEndingWith("Service")
            .orShould().haveSimpleNameEndingWith("Entity")
            .orShould().haveSimpleNameEndingWith("Configuration")
            .orShould().haveSimpleNameEndingWith("Application")
            .allowEmptyShould(false)
            .because("저장소 · 서비스 · 엔터티 · 설정 계층은 각 앱이 따로 갖는다(LC-11 · J113 ②)");

    @Test
    void contractModelHasNoEntityRepositoryOrSpringConfiguration() {
        assertThat(PRODUCTION.size()).as("가져온 운영 클래스 수(빈 가져오기로 통과하지 않음)").isGreaterThan(50);

        NO_FRAMEWORK_DEPENDENCY.check(PRODUCTION);
        NO_FRAMEWORK_ANNOTATION.check(PRODUCTION);
        NO_LAYER_NAMED_CLASSES.check(PRODUCTION);
    }

    @Test
    void contractModelShipsNoBootConfigurationResources() throws IOException {
        Path resources = REPO_ROOT.resolve("backend/contract-model/src/main/resources");
        if (!Files.isDirectory(resources)) {
            return; // 자원 디렉터리 자체가 없으면 Boot 설정 자원도 없다
        }
        try (Stream<Path> files = Files.walk(resources)) {
            List<String> offenders = files.filter(Files::isRegularFile)
                    .map(path -> resources.relativize(path).toString().replace('\\', '/'))
                    .filter(name -> name.startsWith("META-INF/spring")
                            || name.equals("META-INF/spring.factories")
                            || name.matches("application.*\\.(ya?ml|properties)"))
                    .toList();
            assertThat(offenders).as("contract-model의 Boot 설정 자원").isEmpty();
        }
    }

    @Test
    void frameworkRulesFlagASpringRepositoryLikeSample() {
        JavaClasses sample = new ClassFileImporter().importClasses(SampleUsesJdbc.class, SampleOrderRepository.class);

        assertThat(NO_FRAMEWORK_DEPENDENCY.evaluate(sample).hasViolation()).as("java.sql 의존 표본").isTrue();
        assertThat(NO_LAYER_NAMED_CLASSES.evaluate(sample).hasViolation()).as("…Repository 이름 표본").isTrue();
    }

    @Test
    void annotationConditionFlagsOnlyTheListedAnnotation() {
        JavaClasses sample = new ClassFileImporter().importClasses(SampleMarked.class, SampleOrderRepository.class);
        ArchRule rule = noClasses().should(beAnnotatedWithAnyOf(List.of(SampleMarker.class.getName())));

        EvaluationResult result = rule.evaluate(sample);

        assertThat(result.getFailureReport().getDetails())
                .hasSize(1)
                .allMatch(detail -> detail.contains(SampleMarked.class.getName()));
    }

    // ------------------------------------------------------------------ ⑤ 시각 직접 읽기는 SystemMujoongClock만

    static final ArchRule SYSTEM_TIME_ONLY_IN_ADAPTER = noClasses()
            .that().doNotHaveFullyQualifiedName(SystemMujoongClock.class.getName())
            .should(readTheSystemClockDirectly())
            .allowEmptyShould(false)
            .because("모든 시각은 주입한 MujoongClock 하나로 읽는다. 시스템 시계를 감싸는 곳은 SystemMujoongClock뿐이다"
                    + "(RD-2 R1 · NFR6.4 · J113 ⑤)");

    @Test
    void onlySystemMujoongClockReadsTheSystemClock() {
        SYSTEM_TIME_ONLY_IN_ADAPTER.check(PRODUCTION);
    }

    @Test
    void allowListIsNotVacuousBecauseTheAdapterReallyReadsTheSystemClock() {
        // 허용 목록에서 빼면 어댑터 자신이 걸려야 한다(규칙이 아무것도 보지 않는 상태가 아님)
        ArchRule withoutAllowList = noClasses().should(readTheSystemClockDirectly());

        EvaluationResult result = withoutAllowList.evaluate(PRODUCTION);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .allMatch(detail -> detail.contains(SystemMujoongClock.class.getName()));
    }

    @Test
    void timeRuleFlagsDirectCallsMethodReferencesAndNoArgDate() {
        JavaClasses samples = new ClassFileImporter()
                .importClasses(SampleInstantNow.class, SampleNanoTimeReference.class, SampleNewDate.class,
                        SampleLocalDateTimeNow.class);

        EvaluationResult result = SYSTEM_TIME_ONLY_IN_ADAPTER.evaluate(samples);

        assertThat(result.getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains(SampleInstantNow.class.getName()) && detail.contains("Instant.now"))
                .anyMatch(detail -> detail.contains(SampleNanoTimeReference.class.getName())
                        && detail.contains("System.nanoTime"))
                .anyMatch(detail -> detail.contains(SampleNewDate.class.getName()) && detail.contains("Date.<init>"))
                .anyMatch(detail -> detail.contains(SampleLocalDateTimeNow.class.getName())
                        && detail.contains("LocalDateTime.now"));
    }

    @Test
    void timeRuleAcceptsInjectedClockAndPureConversions() {
        JavaClasses samples = new ClassFileImporter().importClasses(SampleInjectedClock.class);

        assertThat(SYSTEM_TIME_ONLY_IN_ADAPTER.evaluate(samples).hasViolation()).isFalse();
    }

    // ------------------------------------------------------------------ 규칙 조건(Core · Realtime 시험과 같은 정의)
    // 조건은 막을 행동을 '했다'로 기록한다(satisfied). noClasses().should(...)가 그 기록을 위반으로 뒤집는다.

    /** 시스템 시각을 직접 만드는 API(소유 타입 → 메서드 이름). 생성자는 무인자 {@code new Date()}만 막는다. */
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

    static ArchCondition<JavaClass> beAnnotatedWithAnyOf(List<String> annotationNames) {
        return new ArchCondition<>("be annotated with any of " + annotationNames) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (String name : annotationNames) {
                    if (item.isAnnotatedWith(name)) {
                        events.add(SimpleConditionEvent.satisfied(item, item.getName() + " is annotated with " + name));
                    }
                }
            }
        };
    }

    // ------------------------------------------------------------------ 위반 · 통과 표본(시험 소스 전용)

    @Retention(RetentionPolicy.RUNTIME)
    @interface SampleMarker {
    }

    @SampleMarker
    static final class SampleMarked {
    }

    static final class SampleUsesJdbc {
        java.sql.Timestamp toTimestamp(Instant at) {
            return java.sql.Timestamp.from(at);
        }
    }

    static final class SampleOrderRepository {
    }

    static final class SampleInstantNow {
        Instant at() {
            return Instant.now();
        }
    }

    static final class SampleNanoTimeReference {
        LongSupplier ticker() {
            return System::nanoTime;
        }
    }

    static final class SampleNewDate {
        Object stamp() {
            return new Date();
        }
    }

    static final class SampleLocalDateTimeNow {
        Object today() {
            return LocalDateTime.now();
        }
    }

    static final class SampleInjectedClock {
        Instant at(MujoongClock clock) {
            return clock.now();
        }

        Instant parsed() {
            return Instant.parse("2026-10-10T00:00:00Z").plusMillis(1);
        }

        Object fixedDate() {
            return new Date(0L);
        }
    }
}
