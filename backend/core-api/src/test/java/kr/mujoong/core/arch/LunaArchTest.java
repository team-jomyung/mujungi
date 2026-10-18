package kr.mujoong.core.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import kr.mujoong.core.luna.CoreLunaClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Luna 호출부 경계 — core (U9 SD7 · BR6.3 · BR7.1 · BR7.3 · NFR3.8 · AC2.4.1).
 *
 * <ul>
 *   <li>{@code ..luna..}는 가드레일(제브) · 로깅 구현(slf4j · logback · JUL · log4j) · 상대 앱(realtime) · Jackson({@code tools.jackson},
 *       JsonCodec 밖 사용 금지 J139)에 의존하지 않는다. 기록은 앱 {@code EventLogger}로만 한다.
 *   <li>{@code ..luna..}는 {@code System.out} · {@code System.err}에 직접 쓰지 않는다.
 * </ul>
 *
 * <p>규칙마다 위반 표본(시험 소스의 {@code kr.mujoong.core.luna} 패키지 표본)을 실제로 잡는지 자기 시험을 둔다.
 */
@Tag("luna")
class LunaArchTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("kr.mujoong.core");

    static final ArchRule LUNA_FORBIDDEN_DEPENDENCIES = noClasses()
            .that().resideInAPackage("..luna..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..guardrail..", "org.slf4j..", "ch.qos.logback..", "java.util.logging..", "org.apache.logging..",
                    "kr.mujoong.realtime..", "tools.jackson..", "com.fasterxml.jackson..")
            .allowEmptyShould(false)
            .because("Luna 경로는 제브를 부르지 않고(AC2.4.1), 기록은 EventLogger로만, 앱 사이 코드 공유 없이(R-F5), Jackson은 JsonCodec 한 곳(J139)");

    static final ArchRule LUNA_NO_STANDARD_STREAMS = noClasses()
            .that().resideInAPackage("..luna..")
            .should().accessField(System.class, "out")
            .orShould().accessField(System.class, "err")
            .allowEmptyShould(false)
            .because("Luna 호출부는 표준 출력에 본문 · 키를 흘리지 않는다(BR6.2 · R-F14)");

    @Test
    void lunaPackagesKeepTheirBoundaries() {
        JavaClasses luna = PRODUCTION.that(com.tngtech.archunit.base.DescribedPredicate.describe("luna",
                javaClass -> javaClass.getPackageName().contains(".luna")));
        assertThat(luna.size()).as("가져온 Luna 운영 클래스 수(빈 가져오기로 통과하지 않음)").isGreaterThan(15);
        assertThat(luna.contain(CoreLunaClient.class)).isTrue();

        LUNA_FORBIDDEN_DEPENDENCIES.check(PRODUCTION);
        LUNA_NO_STANDARD_STREAMS.check(PRODUCTION);
    }

    @Test
    void theRulesFlagASampleThatLogsDirectlyAndWritesToStandardStreams() {
        JavaClasses sample = new ClassFileImporter().importClasses(kr.mujoong.core.luna.LunaArchViolationSample.class);

        assertThat(LUNA_FORBIDDEN_DEPENDENCIES.evaluate(sample).getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains("org.slf4j"));
        assertThat(LUNA_NO_STANDARD_STREAMS.evaluate(sample).hasViolation()).isTrue();
    }
}
