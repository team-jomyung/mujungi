package kr.mujoong.core.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import kr.mujoong.core.activity.ApprovedContentCatalog;
import kr.mujoong.core.catalog.ApprovedContentRepository;
import kr.mujoong.core.identity.GuardianWriteLock;
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
@Tag("parent-account")
class SingleSourceTest {

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
                    .filter(SingleSourceTest::namesSessionTableInCode)
                    .map(path -> CORE_MAIN_SOURCES.relativize(path).toString().replace('\\', '/'))
                    .toList();
        }
        assertThat(offenders).containsExactly("kr/mujoong/core/config/SessionJdbcConfig.java");
    }

    @Test
    void onlyOneClassLocksTheGuardianRow() throws IOException {
        // 2차 리뷰 5: 보호자 행 잠금 · 상태 확인은 한 곳(락 순서 원칙, GuardianRepository 주석). 파일 단위로 줄을 이어 본다(3차 리뷰 6)
        List<String> offenders;
        try (Stream<Path> files = Files.walk(CORE_MAIN_SOURCES)) {
            offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> locksGuardianRow(String.join("\n", codeLines(path).toList())))
                    .map(path -> CORE_MAIN_SOURCES.relativize(path).toString().replace('\\', '/'))
                    .toList();
        }
        assertThat(offenders).containsExactly("kr/mujoong/core/identity/GuardianWriteLock.java");
    }

    @Test
    void guardianLockCallsHaveOneShape() {
        // 3차 리뷰 7: statusForShare만 정적이라 호출 모양이 둘이었다. 공개 메서드는 모두 인스턴스 메서드다
        assertThat(Arrays.stream(GuardianWriteLock.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers()))
                .map(Method::getName))
                .isEmpty();
    }

    @Test
    void theGuardianLockCheckSeesSqlSplitOverLines() {
        // 3차 리뷰 6: 예전 검사는 줄 하나씩 봐서 텍스트 블록 · 문자열 이어 붙이기로 줄을 나눈 SQL을 놓쳤다
        assertThat(locksGuardianRow("jdbc.queryForList(\"\"\"\n    select status\n    from core.core_guardian\n"
                + "    where id = :id\n    FOR UPDATE\n    \"\"\", params);")).as("텍스트 블록").isTrue();
        assertThat(locksGuardianRow("String sql = \"select status from core.core_guardian \"\n"
                + "        + \"where id = :id for share\";")).as("이어 붙이기").isTrue();
        assertThat(locksGuardianRow("select * from core.core_guardian g where g.id = :id for no key update"))
                .as("for no key update").isTrue();
        assertThat(locksGuardianRow("jdbc.update(\"delete from core.core_guardian where id = :id\", p);\n"
                + "jdbc.queryForList(\"select id from core.core_device where id = :d for update\", p);"))
                .as("다른 문장의 FOR UPDATE는 보호자 행 잠금이 아니다").isFalse();
    }

    /**
     * 보호자 행을 FOR UPDATE · FOR SHARE(NO KEY · KEY 포함)로 읽는 SQL — 대소문자 · 줄바꿈과 관계없이, 같은 자바 문장(';' 전까지) 안에서
     * 찾는다(3차 리뷰 6).
     */
    private static final Pattern GUARDIAN_LOCK = Pattern.compile(
            "core_guardian\\b[^;]*?\\bfor\\s+(no\\s+key\\s+update|update|key\\s+share|share)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    static boolean locksGuardianRow(String code) {
        return GUARDIAN_LOCK.matcher(code).find();
    }

    /** 주석 줄을 뺀 코드 줄에 표 이름이 있는가. */
    private static boolean namesSessionTableInCode(Path file) {
        return codeLines(file).anyMatch(line -> line.contains("core_spring_session"));
    }

    /** 주석 줄을 뺀 코드 줄. */
    private static Stream<String> codeLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> !line.startsWith("*") && !line.startsWith("//") && !line.startsWith("/*"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
