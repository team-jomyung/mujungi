package kr.mujoong.core.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import kr.mujoong.core.activity.ApprovedContentCatalog;
import kr.mujoong.core.catalog.ApprovedContentRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 한 곳에서만 하는 일(U2 · 리뷰 10).
 *
 * <ul>
 *   <li>승인 문구 · 정책 버전은 {@link ApprovedContentCatalog} 하나가 읽는다(저장소를 직접 쓰는 다른 운영 클래스 없음) — 02-1 약관과 시작
 *       게이트가 같은 정책 버전을 본다.
 *   <li>로그인 세션 표({@code core_spring_session})는 Spring Session 설정 한 곳만 이름을 안다. 세션 지우기는 {@code GuardianSessions}를 거친다.
 * </ul>
 */
@Tag("u2")
class U2SingleSourceTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("kr.mujoong.core");

    private static final Path CORE_MAIN_SOURCES = Path.of(System.getProperty("mujoong.repo.root", ".."))
            .toAbsolutePath().normalize().resolve("backend/core-api/src/main/java");

    static final ArchRule ONE_CATALOG_READER = noClasses()
            .that().doNotHaveFullyQualifiedName(ApprovedContentCatalog.class.getName())
            .and().resideOutsideOfPackage("kr.mujoong.core.catalog..")
            .should().dependOnClassesThat().areAssignableTo(ApprovedContentRepository.class)
            .because("승인 문구 카탈로그는 하나다(리뷰 10)");

    @Test
    void onlyTheU1CatalogReadsApprovedContent() {
        ONE_CATALOG_READER.check(PRODUCTION);
    }

    @Test
    void onlyTheSessionConfigNamesTheSessionTable() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(CORE_MAIN_SOURCES)) {
            offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(U2SingleSourceTest::namesSessionTableInCode)
                    .map(path -> CORE_MAIN_SOURCES.relativize(path).toString().replace('\\', '/'))
                    .toList();
        }
        assertThat(offenders).containsExactly("kr/mujoong/core/config/SessionJdbcConfig.java");
    }

    /** 주석 줄을 뺀 코드 줄에 표 이름이 있는가. */
    private static boolean namesSessionTableInCode(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> !line.startsWith("*") && !line.startsWith("//") && !line.startsWith("/*"))
                    .anyMatch(line -> line.contains("core_spring_session"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
