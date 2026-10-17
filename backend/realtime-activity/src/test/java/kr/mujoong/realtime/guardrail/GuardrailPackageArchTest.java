package kr.mujoong.realtime.guardrail;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 제브 패키지 경계 (U13 BR1.7 · NFR8 · AC2.4.1). 새 패키지 {@code kr.mujoong.realtime.guardrail}은 {@code luna} · {@code voice} · {@code judgment}를
 * 가져오지 않는다(제브는 Luna 호출부 · GPT-Live 연결 · 판정 포트와 섞이지 않는다). 반대 방향({@code luna} → {@code guardrail} 금지)은 기존
 * {@code LunaArchTest}가 이미 지키며, 이 패키지가 생겨 그 규칙이 비로소 의미를 갖는다. 새 규칙이라 기존 구조 시험은 건드리지 않는다
 * ({@code allowEmptyShould(false)}로 빈 가져오기에 통과하지 않는다).
 */
@Tag("u13")
class GuardrailPackageArchTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("kr.mujoong.realtime");

    static final ArchRule GUARDRAIL_FORBIDDEN_DEPENDENCIES = noClasses()
            .that().resideInAPackage("kr.mujoong.realtime.guardrail..")
            .should().dependOnClassesThat().resideInAnyPackage("kr.mujoong.realtime.luna..", "kr.mujoong.realtime.voice..",
                    "kr.mujoong.realtime.judgment..")
            .allowEmptyShould(false)
            .because("제브는 Luna 호출부 · GPT-Live 연결 · 판정 포트와 섞이지 않는다(ADR-006 · BR1.7)");

    static final ArchRule GUARDRAIL_NO_STANDARD_STREAMS = noClasses()
            .that().resideInAPackage("kr.mujoong.realtime.guardrail..")
            .should().accessField(System.class, "out")
            .orShould().accessField(System.class, "err")
            .allowEmptyShould(false)
            .because("제브 경로는 표준 출력에 후보 문장 · 키를 흘리지 않는다(R-F14 · BR2.8)");

    @Test
    void theGuardrailPackageExistsAndKeepsItsBoundaries() {
        JavaClasses guardrail = PRODUCTION.that(DescribedPredicate.describe("guardrail",
                javaClass -> javaClass.getPackageName().equals("kr.mujoong.realtime.guardrail")));
        assertThat(guardrail.size()).as("가져온 제브 운영 클래스 수(빈 가져오기로 통과하지 않음)").isGreaterThan(12);
        assertThat(guardrail.contain(GuardrailClient.class)).isTrue();

        GUARDRAIL_FORBIDDEN_DEPENDENCIES.check(PRODUCTION);
        GUARDRAIL_NO_STANDARD_STREAMS.check(PRODUCTION);
    }

    @Test
    void lunaDoesNotDependOnTheGuardrailPackageAndTheDirectionIsOneWay() {
        // luna → guardrail 금지는 LunaArchTest가 지킨다. 여기서는 그 규칙이 가리킬 대상이 실제로 있고 아직 한 방향뿐임을 확인한다
        JavaClasses luna = PRODUCTION.that(DescribedPredicate.describe("luna",
                javaClass -> javaClass.getPackageName().contains(".luna")));
        assertThat(luna.size()).isGreaterThan(12);

        noClasses().that().resideInAPackage("kr.mujoong.realtime.luna..")
                .should().dependOnClassesThat().resideInAPackage("kr.mujoong.realtime.guardrail..")
                .allowEmptyShould(false).check(PRODUCTION);
    }

    @Test
    void theRulesFlagASampleThatImportsLunaVoiceAndJudgment() {
        JavaClasses sample = new ClassFileImporter().importClasses(GuardrailArchViolationSample.class);

        assertThat(GUARDRAIL_FORBIDDEN_DEPENDENCIES.evaluate(sample).hasViolation()).isTrue();
        assertThat(GUARDRAIL_FORBIDDEN_DEPENDENCIES.evaluate(sample).getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains("kr.mujoong.realtime.luna"))
                .anyMatch(detail -> detail.contains("kr.mujoong.realtime.voice"))
                .anyMatch(detail -> detail.contains("kr.mujoong.realtime.judgment"));
    }
}
