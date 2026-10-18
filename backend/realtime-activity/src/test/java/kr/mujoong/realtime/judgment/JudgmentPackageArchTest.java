package kr.mujoong.realtime.judgment;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 판정 패키지 경계 (U11 Step 17). {@code luna} · {@code gate} · {@code voice}는 판정 패키지를 모르고, 판정 패키지는 세션 실행기 · 기기 게이트웨이 ·
 * 출력 게이트 · 공급자 음성 계층에 기대지 않는다(실행기가 판정 협력 객체를 부르고 그 반대는 없다). 새 규칙이라 기존 구조 시험은 건드리지 않는다.
 */
@Tag("judgment-turn")
class JudgmentPackageArchTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("kr.mujoong.realtime");

    @Test
    void lunaGateAndVoiceNeverDependOnTheJudgmentPackage() {
        noClasses().that().resideInAnyPackage("kr.mujoong.realtime.luna..", "kr.mujoong.realtime.gate..", "kr.mujoong.realtime.voice..")
                .should().dependOnClassesThat().resideInAPackage("kr.mujoong.realtime.judgment..")
                .allowEmptyShould(false).check(PRODUCTION);
    }

    @Test
    void theJudgmentPackageDoesNotDependOnTheExecutorGatewayGateOrVoice() {
        noClasses().that().resideInAPackage("kr.mujoong.realtime.judgment..")
                .should().dependOnClassesThat().resideInAnyPackage("kr.mujoong.realtime.session..", "kr.mujoong.realtime.gateway..",
                        "kr.mujoong.realtime.gate..", "kr.mujoong.realtime.voice..")
                .allowEmptyShould(false).check(PRODUCTION);
    }
}
