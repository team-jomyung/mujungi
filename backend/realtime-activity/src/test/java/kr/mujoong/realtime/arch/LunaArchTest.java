package kr.mujoong.realtime.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import kr.mujoong.realtime.luna.RealtimeLunaClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Luna 호출부 경계 — realtime (U9 SD7 · BR6.3 · BR7.1 · BR7.3 · NFR3.8 · AC2.4.1).
 *
 * <ul>
 *   <li>{@code ..luna..}는 가드레일(제브) · 로깅 구현 · 상대 앱(core) · Jackson(J139)에 의존하지 않는다.
 *   <li>{@code ..luna..}는 GPT-Live 웹소켓({@code java.net.http.WebSocket}) · {@code voice} 패키지({@code ProviderClient})에 의존하지 않는다.
 *   <li>{@code ..luna..}는 {@code System.out} · {@code System.err}에 직접 쓰지 않는다.
 * </ul>
 */
@Tag("luna")
class LunaArchTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("kr.mujoong.realtime");

    static final ArchRule LUNA_FORBIDDEN_DEPENDENCIES = noClasses()
            .that().resideInAPackage("..luna..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..guardrail..", "org.slf4j..", "ch.qos.logback..", "java.util.logging..", "org.apache.logging..",
                    "kr.mujoong.core..", "tools.jackson..", "com.fasterxml.jackson..", "kr.mujoong.realtime.voice..")
            .allowEmptyShould(false)
            .because("Luna 경로는 제브를 부르지 않고(AC2.4.1), GPT-Live 연결과 섞이지 않으며(NFR3.8), 기록은 EventLogger로만(BR6.3)");

    static final ArchRule LUNA_NO_WEBSOCKET = noClasses()
            .that().resideInAPackage("..luna..")
            .should().dependOnClassesThat().haveNameMatching("java\\.net\\.http\\.WebSocket(\\$.*)?")
            .allowEmptyShould(false)
            .because("공급자 웹소켓 연결은 voice 패키지 하나가 맡는다(SD-20 · J113 ⑥)");

    static final ArchRule LUNA_NO_STANDARD_STREAMS = noClasses()
            .that().resideInAPackage("..luna..")
            .should().accessField(System.class, "out")
            .orShould().accessField(System.class, "err")
            .allowEmptyShould(false)
            .because("Luna 호출부는 표준 출력에 본문 · 키를 흘리지 않는다(BR6.2 · R-F14)");

    @Test
    void lunaPackagesKeepTheirBoundaries() {
        JavaClasses luna = PRODUCTION.that(DescribedPredicate.describe("luna",
                javaClass -> javaClass.getPackageName().contains(".luna")));
        assertThat(luna.size()).as("가져온 Luna 운영 클래스 수(빈 가져오기로 통과하지 않음)").isGreaterThan(12);
        assertThat(luna.contain(RealtimeLunaClient.class)).isTrue();

        LUNA_FORBIDDEN_DEPENDENCIES.check(PRODUCTION);
        LUNA_NO_WEBSOCKET.check(PRODUCTION);
        LUNA_NO_STANDARD_STREAMS.check(PRODUCTION);
    }

    @Test
    void theRulesFlagASampleThatUsesTheWebSocketAndStandardStreams() {
        JavaClasses sample = new ClassFileImporter().importClasses(kr.mujoong.realtime.luna.LunaArchViolationSample.class);

        assertThat(LUNA_NO_WEBSOCKET.evaluate(sample).hasViolation()).isTrue();
        assertThat(LUNA_NO_STANDARD_STREAMS.evaluate(sample).hasViolation()).isTrue();
        assertThat(LUNA_FORBIDDEN_DEPENDENCIES.evaluate(sample).getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains("kr.mujoong.realtime.voice"));
    }
}
