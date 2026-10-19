package kr.mujoong.realtime.arch;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import kr.mujoong.realtime.practiceflow.PracticeConversation;
import kr.mujoong.realtime.practiceflow.PracticeFlowArchViolationSample;
import kr.mujoong.realtime.session.PracticeSessionExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 새 연습 흐름 구조 경계 (U12 BR10.2 · NFR6 · BR3.7 · R-F5 · R-F8). 새 패키지({@code practiceflow})는 레거시 {@code practicelegacy}를 참조하지 않고,
 * 실행부와는 확장점 인터페이스({@code PracticeSessionExtension}) 능력으로만 닿으며, 금지된 출력 능력(자유 지시 · 응답 요청 · 승인 문구 재생 ·
 * 응답 취소)을 부르지 않고, 판정 라벨 · 확률을 나르는 판정 클래스에 기대지 않는다. 반대 방향으로 실행부({@code session})는 새 패키지를 모른다
 * (실행부는 호출 지점만 더한다). 규칙이 실제로 위반을 잡는지는 위반 표본으로 확인한다.
 */
@Tag("practice-flow")
class PracticeFlowArchTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("kr.mujoong.realtime");

    private static final DescribedPredicate<JavaClass> EXECUTION_INTERNALS = DescribedPredicate.describe(
            "실행부 내부(확장점 인터페이스 밖의 session 클래스)",
            javaClass -> javaClass.getName().startsWith("kr.mujoong.realtime.session.")
                    && !javaClass.getName().startsWith("kr.mujoong.realtime.session.PracticeSessionExtension"));

    private static final DescribedPredicate<JavaClass> JUDGMENT_PAYLOADS = DescribedPredicate.describe(
            "판정 지점 밖의 judgment 클래스(라벨 · 확률을 나르는 값)",
            javaClass -> javaClass.getPackageName().startsWith("kr.mujoong.realtime.judgment")
                    && !javaClass.getName().equals("kr.mujoong.realtime.judgment.JudgmentPoint"));

    static final ArchRule NO_LEGACY_PRACTICE = noClasses()
            .that().resideInAPackage("..practiceflow..")
            .should().dependOnClassesThat(resideInAPackage("kr.mujoong.realtime.practicelegacy.."))
            .allowEmptyShould(false)
            .because("새 흐름은 레거시 practicelegacy 패키지를 import하지 않는다(BR10.2)");

    static final ArchRule NO_EXECUTION_INTERNALS = noClasses()
            .that().resideInAPackage("..practiceflow..")
            .should().dependOnClassesThat(EXECUTION_INTERNALS)
            .allowEmptyShould(false)
            .because("실행부와는 PracticeOutput 능력과 사건으로만 닿는다(NFR6 · BR10.2)");

    static final ArchRule NO_FORBIDDEN_CAPABILITIES = noClasses()
            .that().resideInAPackage("..practiceflow..")
            .should().callMethod(PracticeSessionExtension.PracticeOutput.class, "instructProvider", String.class)
            .orShould().callMethod(PracticeSessionExtension.PracticeOutput.class, "requestResponse")
            .orShould().callMethod(PracticeSessionExtension.PracticeOutput.class, "playApproved", String.class)
            .orShould().callMethod(PracticeSessionExtension.PracticeOutput.class, "cancelResponse")
            .allowEmptyShould(false)
            .because("역할 발화는 speakGenerated 한 길, 대본 문장은 speak 한 길로만 낸다(D6 · BR5.6)");

    static final ArchRule NO_LABEL_OR_PROBABILITY_PAYLOADS = noClasses()
            .that().resideInAPackage("..practiceflow..")
            .should().dependOnClassesThat(JUDGMENT_PAYLOADS)
            .allowEmptyShould(false)
            .because("판정 라벨 · 확률 · 기준을 나르는 값에 기대지 않아 지시문에 섞일 길이 없다(BR3.7 · NFR1)");

    static final ArchRule NO_TEST_OR_PEER_APP = noClasses()
            .that().resideInAPackage("..practiceflow..")
            .should().dependOnClassesThat(resideInAPackage("kr.mujoong.testsupport.."))
            .orShould().dependOnClassesThat(resideInAPackage("kr.mujoong.core.."))
            .allowEmptyShould(false)
            .because("운영 코드는 시험 지원 모듈과 상대 앱을 참조하지 않는다(R-F8 · R-F5)");

    static final ArchRule EXECUTOR_KNOWS_NO_FLOW = noClasses()
            .that().resideInAPackage("kr.mujoong.realtime.session..")
            .should().dependOnClassesThat(resideInAPackage("..practiceflow.."))
            .allowEmptyShould(false)
            .because("실행부 → 확장점 한 방향만 둔다: 실행부는 새 흐름의 클래스를 모르고 호출 지점만 가진다(NFR6)");

    @Test
    void theNewFlowPackageKeepsItsBoundaries() {
        JavaClasses flow = PRODUCTION.that(DescribedPredicate.describe("practiceflow",
                javaClass -> javaClass.getPackageName().startsWith("kr.mujoong.realtime.practiceflow")));
        assertThat(flow.size()).as("가져온 새 흐름 운영 클래스 수(빈 가져오기로 통과하지 않음)").isGreaterThan(15);
        assertThat(flow.contain(PracticeConversation.class)).isTrue();

        NO_LEGACY_PRACTICE.check(PRODUCTION);
        NO_EXECUTION_INTERNALS.check(PRODUCTION);
        NO_FORBIDDEN_CAPABILITIES.check(PRODUCTION);
        NO_LABEL_OR_PROBABILITY_PAYLOADS.check(PRODUCTION);
        NO_TEST_OR_PEER_APP.check(PRODUCTION);
    }

    @Test
    void theExecutorSideStaysOneWayAndKnowsNoFlowClass() {
        EXECUTOR_KNOWS_NO_FLOW.check(PRODUCTION);
    }

    @Test
    void theRulesFlagASampleThatUsesTheLegacyPackageTheExecutorAndTheForbiddenCapabilities() {
        JavaClasses sample = new ClassFileImporter().importClasses(PracticeFlowArchViolationSample.class);

        assertThat(NO_LEGACY_PRACTICE.evaluate(sample).hasViolation()).isTrue();
        assertThat(NO_EXECUTION_INTERNALS.evaluate(sample).hasViolation()).isTrue();
        assertThat(NO_FORBIDDEN_CAPABILITIES.evaluate(sample).getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains("instructProvider"))
                .anyMatch(detail -> detail.contains("requestResponse"))
                .anyMatch(detail -> detail.contains("playApproved"))
                .anyMatch(detail -> detail.contains("cancelResponse"));
    }

    @Test
    void theJudgmentPayloadRuleIsNotVacuousBecauseTheNewFlowReallyUsesTheJudgmentPoint() {
        JavaClasses flow = PRODUCTION.that(DescribedPredicate.describe("practiceflow",
                javaClass -> javaClass.getPackageName().startsWith("kr.mujoong.realtime.practiceflow")));

        assertThat(flow.stream().flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
                .map(dependency -> dependency.getTargetClass().getName()))
                .contains("kr.mujoong.realtime.judgment.JudgmentPoint")
                .noneMatch(name -> name.startsWith("kr.mujoong.realtime.judgment.Judge"));
    }
}
