// 무중 백엔드 모듈 공통 규약 (U1 3.5 Step 3 · 5 · J105 · J106 · J107 · J132 · J137 · J139)
// Step 3: Java 21 툴체인(설치된 JDK, 자동 받기 없음 — backend/gradle.properties), Spring Boot BOM을 Gradle 플랫폼으로,
//         컴파일 옵션 · 재현 가능한 아카이브, JaCoCo 도구 판(gradle/libs.versions.toml 한 곳).
// Step 5: 시험 실행 설정(JUnit Platform 태그 · UTC · DB 백엔드 선택), JaCoCo 80% 줄 하한(제외 목록 없음),
//         유닛 측정 범위(-PcoverageScope), 운영 jar 시험 클래스 검사(verifyBootJarHasNoTestClasses).
import kr.mujoong.buildlogic.MujoongBuildExtension
import java.util.zip.ZipFile

plugins {
    java
    jacoco
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun catalogLibrary(alias: String) =
    libs.findLibrary(alias).orElseThrow { GradleException("gradle/libs.versions.toml에 라이브러리 '$alias'가 없다") }

val mujoongBuild = extensions.create<MujoongBuildExtension>("mujoongBuild")
mujoongBuild.coverageFloorApplies.convention(true)

group = "kr.mujoong"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

jacoco {
    toolVersion = libs.findVersion("jacoco").orElseThrow { GradleException("gradle/libs.versions.toml에 jacoco 판이 없다") }.requiredVersion
}

dependencies {
    val bom = platform(catalogLibrary("spring-boot-bom"))
    "implementation"(bom)
    "annotationProcessor"(bom)
    // Gradle 9는 런처를 자동으로 넣지 않고 8.14는 자동 주입이 폐기 예정이다. 판은 Boot BOM(Step 5)
    "testRuntimeOnly"(catalogLibrary("junit-platform-launcher"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial,-this-escape,-try"))
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// ---- 시험 실행 (Step 5 · J106 · J132) ----

/** 저장소 루트(backend/의 부모). 시험이 contracts/ · infra/를 읽을 때 시스템 속성 mujoong.repo.root로 받는다. */
val repositoryRoot: File = rootProject.projectDir.parentFile

fun csvGradleProperty(name: String): List<String> =
    providers.gradleProperty(name).orNull
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

val includeTagList = csvGradleProperty("includeTags")
val excludeTagList = csvGradleProperty("excludeTags")
val testDbBackend = providers.gradleProperty("mujoong.test.db").orElse("auto")

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        if (includeTagList.isNotEmpty()) includeTags(*includeTagList.toTypedArray())
        if (excludeTagList.isNotEmpty()) excludeTags(*excludeTagList.toTypedArray())
    }
    // 시각은 UTC(NFR6.4 · BR8.10). DB 시험 백엔드는 auto · docker · local(J132)
    jvmArgs("-Duser.timezone=UTC", "-Dfile.encoding=UTF-8")
    systemProperty("mujoong.test.db", testDbBackend.get())
    systemProperty("mujoong.repo.root", repositoryRoot.absolutePath)
    // 시험이 읽는 저장소 파일(계약 정본 · DB 초기화 · 시드)이 바뀌면 다시 돈다
    inputs.files(fileTree(repositoryRoot.resolve("contracts")))
        .withPropertyName("contractsFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(repositoryRoot.resolve("infra/postgres")), fileTree(repositoryRoot.resolve("infra/seed")))
        .withPropertyName("infraDbFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("includeTags", includeTagList.joinToString(","))
    inputs.property("excludeTags", excludeTagList.joinToString(","))
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    finalizedBy(tasks.named("jacocoTestReport"))
}

// ---- 커버리지 (Step 5 · J91 · J107 · J137) ----

val coverageScopeName: String? = providers.gradleProperty("coverageScope").orNull
val mainClassesDirs = the<SourceSetContainer>()["main"].output.classesDirs

/**
 * -PcoverageScope=<이름>이면 backend/coverage-scope/<이름>.txt에 적힌 이 모듈의 운영 소스 파일만 잰다(J137).
 * 목록 줄은 저장소 루트 기준 경로(예: backend/core-api/src/main/java/kr/mujoong/core/X.java)이고 # 줄은 주석이다.
 * 목록에 이 모듈 소스가 없거나 적힌 파일이 없으면 실패한다(빈 측정으로 통과하지 않음). 속성이 없으면 모듈 전체를 잰다.
 */
fun scopedClassPatterns(scopeName: String): List<String> {
    val listFile = rootProject.file("coverage-scope/$scopeName.txt")
    if (!listFile.isFile) {
        throw GradleException("커버리지 범위 목록이 없다: backend/coverage-scope/$scopeName.txt (J137)")
    }
    val modulePrefix = "backend/${project.name}/src/main/java/"
    val entries = listFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.startsWith(modulePrefix) && it.endsWith(".java") }
    if (entries.isEmpty()) {
        throw GradleException(
            "backend/coverage-scope/$scopeName.txt에 ${project.name} 운영 소스가 없다. " +
                "목록은 source-manifest의 유닛 소스로 채운다(J137).",
        )
    }
    val missing = entries.filterNot { repositoryRoot.resolve(it).isFile }
    if (missing.isNotEmpty()) {
        throw GradleException("backend/coverage-scope/$scopeName.txt에 없는 파일이 있다: ${missing.joinToString()}")
    }
    return entries.flatMap { entry ->
        val classPath = entry.removePrefix(modulePrefix).removeSuffix(".java")
        listOf("$classPath.class", "$classPath\$*.class")
    }
}

fun JacocoReportBase.limitToCoverageScope() {
    val scopeName = coverageScopeName ?: return
    if (!mujoongBuild.coverageFloorApplies.get()) return
    val patterns = scopedClassPatterns(scopeName)
    classDirectories.setFrom(files(mainClassesDirs).asFileTree.matching { include(patterns) })
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    limitToCoverageScope()
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    limitToCoverageScope()
}

// 하한 대상 모듈(contract-model · core-api · realtime-activity)만 80% 줄 규칙을 걸고 check가 의존한다.
// test-support는 시험 지원 코드라 하한 대상이 아니다(J107, 모듈 build 파일의 mujoongBuild 설정).
afterEvaluate {
    if (mujoongBuild.coverageFloorApplies.get()) {
        tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
            violationRules {
                rule {
                    limit {
                        counter = "LINE"
                        value = "COVEREDRATIO"
                        minimum = "0.80".toBigDecimal()
                    }
                }
            }
        }
        tasks.named("check") {
            dependsOn("jacocoTestCoverageVerification")
        }
    }
}

// ---- 운영 jar 시험 클래스 검사 (Step 5 · SD-25 · RD-23 · CD-7, Step 43 · 49가 실행) ----

plugins.withId("org.springframework.boot") {
    val bootJarFile = tasks.named<Jar>("bootJar").flatMap { it.archiveFile }
    val testClassesDirs = the<SourceSetContainer>()["test"].output.classesDirs
    tasks.register("verifyBootJarHasNoTestClasses") {
        group = "verification"
        description = "운영 bootJar에 시험 클래스 · 시험 설정 · 시험 라이브러리가 없는지 확인한다."
        inputs.file(bootJarFile).withPropertyName("bootJar")
        inputs.files(testClassesDirs).withPropertyName("testClasses")
        doLast {
            val testLibraryPrefixes = listOf(
                "test-support", "junit-", "mockito-", "assertj-", "testcontainers", "archunit", "spring-test-",
                "spring-boot-test-", "spring-boot-testcontainers", "hamcrest", "awaitility", "jsonassert",
                "xmlunit", "Java-WebSocket",
            )
            // 이 모듈 시험 소스 집합이 만든 클래스 경로 전부(정확 대조)
            val compiledTestClasses = mutableSetOf<String>()
            testClassesDirs.asFileTree.visit {
                if (!isDirectory) compiledTestClasses += relativePath.pathString
            }
            // 이름 규칙 대조: 바깥 클래스 단순 이름이 Test · Tests · IT · TestCase로 끝나면 시험 클래스로 본다
            val testClassSuffixes = listOf("Test", "Tests", "IT", "TestCase")
            val offenders = mutableListOf<String>()
            ZipFile(bootJarFile.get().asFile).use { zip ->
                for (entry in zip.entries()) {
                    val name = entry.name
                    val simpleName = name.substringAfterLast('/')
                    val outerClassName = simpleName.substringBefore('$').removeSuffix(".class")
                    val classPath = name.removePrefix("BOOT-INF/classes/")
                    val isTestEntry = when {
                        name.startsWith("BOOT-INF/classes/kr/mujoong/testsupport/") -> true
                        name.startsWith("BOOT-INF/classes/") && classPath in compiledTestClasses -> true
                        name.startsWith("BOOT-INF/classes/") && simpleName.endsWith(".class") &&
                            testClassSuffixes.any { outerClassName.endsWith(it) } -> true
                        name.startsWith("BOOT-INF/classes/application-test.") -> true
                        name.startsWith("BOOT-INF/classes/db/testmigration/") -> true
                        name.startsWith("BOOT-INF/lib/") && testLibraryPrefixes.any { simpleName.startsWith(it) } -> true
                        else -> false
                    }
                    if (isTestEntry) offenders += name
                }
            }
            if (offenders.isNotEmpty()) {
                throw GradleException("운영 jar에 시험 항목이 있다(${offenders.size}건): ${offenders.take(20).joinToString()}")
            }
            logger.lifecycle("verifyBootJarHasNoTestClasses: ${bootJarFile.get().asFile.name} 시험 항목 0건")
        }
    }
}
