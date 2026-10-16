package kr.mujoong.realtime.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * U8 구조 규칙(NFR3.6 · SD4): 세션 콘텐츠 계획 · 원문 일치 컴포넌트가 있는 {@code kr.mujoong.realtime.content} 패키지는 로그를 쓰지 않는다 —
 * slf4j · 구조화 로그({@code kr.mujoong.contract.log}, EventLog) · JUL에 기대지 않고 {@code System.out} · {@code System.err}를 읽지 않는다.
 * 대본 문장 · 승인 발화 · 출력 전사가 로그로 새는 길을 구조로 막는다. 기존 {@code RealtimeArchTest}처럼 위반 표본을 실제로 잡는지 자기 시험을 둔다.
 */
@Tag("u8")
class U8LiveContentArchTest {

    static final String CONTENT_PACKAGE = "kr.mujoong.realtime.content..";

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("kr.mujoong.realtime");

    static final ArchRule CONTENT_HAS_NO_LOGGING_DEPENDENCY = noClasses().that().resideInAPackage(CONTENT_PACKAGE)
            .should().dependOnClassesThat().resideInAnyPackage("org.slf4j..", "kr.mujoong.contract.log..", "java.util.logging..",
                    "org.apache.logging..", "ch.qos.logback..")
            .allowEmptyShould(false)
            .because("LiveContentRegistry는 판 번호 · 결과 종류만 반환값으로 내고 로그를 쓰지 않는다(U8 NFR3.6 · SD4)");

    static final ArchRule CONTENT_NEVER_PRINTS = noClasses().that().resideInAPackage(CONTENT_PACKAGE)
            .should().accessField(System.class, "out").orShould().accessField(System.class, "err")
            .allowEmptyShould(false)
            .because("표준 출력 · 오류로 문장이 새지 않게 한다(U8 SD4)");

    @Test
    void theContentPackageExistsAndNeverLogsOrPrints() {
        JavaClasses content = PRODUCTION.that(com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage(CONTENT_PACKAGE));
        assertThat(content.size()).as("content 패키지 운영 클래스(빈 가져오기로 통과하지 않음)").isGreaterThanOrEqualTo(2);

        CONTENT_HAS_NO_LOGGING_DEPENDENCY.check(PRODUCTION);
        CONTENT_NEVER_PRINTS.check(PRODUCTION);
    }

    @Test
    void theRulesCatchALoggingOrPrintingSample() {
        JavaClasses samples = new ClassFileImporter().importClasses(
                kr.mujoong.realtime.content.U8ArchSamples.LogsWithEventLog.class,
                kr.mujoong.realtime.content.U8ArchSamples.PrintsToStderr.class);

        assertThat(CONTENT_HAS_NO_LOGGING_DEPENDENCY.evaluate(samples).hasViolation()).isTrue();
        assertThat(CONTENT_NEVER_PRINTS.evaluate(samples).hasViolation()).isTrue();
    }
}
