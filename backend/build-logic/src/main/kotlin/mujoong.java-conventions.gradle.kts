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

    // 보안 패치 판(3.6 수정 라운드 · CI backend-deps): Boot 4.1.1 BOM보다 새 판으로 올린다. Gradle은 판이 충돌하면
    // 높은 판을 고르므로 아래 BOM · 제약이 이긴다. 판은 gradle/libs.versions.toml의 *-security 한 곳이다.
    "implementation"(platform(catalogLibrary("jackson3-bom")))
    "implementation"(platform(catalogLibrary("jackson2-bom")))
    val tomcatSecurityVersion = libs.findVersion("tomcat-security")
        .orElseThrow { GradleException("gradle/libs.versions.toml에 tomcat-security 판이 없다") }.requiredVersion
    constraints {
        listOf("tomcat-embed-core", "tomcat-embed-el", "tomcat-embed-websocket").forEach { artifact ->
            "implementation"("org.apache.tomcat.embed:$artifact:$tomcatSecurityVersion") {
                because("Boot 4.1.1 BOM의 Tomcat 11.0.24 CRITICAL(CVE-2026-65182 · CVE-2026-65905 · CVE-2026-68525)")
            }
        }
    }
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
    // 통합 시험이 앱을 여러 번 띄우므로 Gradle 시험 JVM 기본 힙(512MB)으로는 모자라 RealtimeLogHygieneIT가 OutOfMemoryError로 실패했다
    maxHeapSize = "1g"
    systemProperty("mujoong.test.db", testDbBackend.get())
    systemProperty("mujoong.repo.root", repositoryRoot.absolutePath)
    // 시험이 읽는 저장소 파일(계약 정본 · DB 초기화 · 시드)이 바뀌면 다시 돈다
    inputs.files(fileTree(repositoryRoot.resolve("contracts")))
        .withPropertyName("contractsFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(repositoryRoot.resolve("infra/postgres")), fileTree(repositoryRoot.resolve("infra/seed")))
        .withPropertyName("infraDbFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // DB 시험(MujoongPostgres)은 두 앱의 마이그레이션 위치를 모두 적용하고 migrate 설정 · 승인 음성을 읽는다(Step 14).
    // 상대 앱 · infra 파일이 바뀌어도 시험이 UP-TO-DATE로 건너뛰지 않게 입력으로 잡는다
    inputs.files(
        fileTree(repositoryRoot.resolve("infra/migrate")),
        fileTree(repositoryRoot.resolve("infra/seed-audio")),
        fileTree(rootProject.projectDir) { include("*/src/main/resources/db/**", "*/src/test/resources/db/**") },
    )
        .withPropertyName("dbMigrationFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("includeTags", includeTagList.joinToString(","))
    inputs.property("excludeTags", excludeTagList.joinToString(","))
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    finalizedBy(tasks.named("jacocoTestReport"))
}

// ---- Luna 실호출 시험 분리 (U9 SD8 · BR9.3 · NFR5.1 · 편차 U9-c) ----
// 실공급자를 부르는 시험(@Tag("luna-live"))은 기본 test 작업에서 언제나 빠지고, 사람이 키를 넣고 돌리는 lunaLiveTest만 포함한다.
// 고정 제외는 공통 withType<Test> 블록이 아니라 기본 test 작업에만 건다(lunaLiveTest의 포함 필터와 부딪히지 않게).
// lunaLiveTest는 check · build에 물리지 않는다. 확인은 루트 verifyLunaLiveIsolation(check가 의존)이 한다.

/** 실호출 시험 태그. */
val lunaLiveTag = "luna-live"

tasks.named<Test>("test") {
    options {
        (this as JUnitPlatformOptions).excludeTags(lunaLiveTag)
    }
    // 고정 제외 값도 -PexcludeTags와 같이 작업 입력(캐시 키)에 넣는다
    inputs.property("fixedExcludeTags", lunaLiveTag)
    // 기본 시험은 실제 키를 보지 않는다(R-F1 · R-F3). 개발 PC env에 키가 있어도 시험 JVM에는 넘기지 않는다
    environment.remove("OPENAI_API_KEY")
}

val testSourceSet = the<SourceSetContainer>()["test"]
tasks.register<Test>("lunaLiveTest") {
    group = "verification"
    description = "Luna 실호출 시험(@Tag(\"luna-live\"), 사람이 env OPENAI_API_KEY를 넣고 돌림). 키가 없으면 SKIPPED_NO_KEY로 끝난다."
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    options {
        (this as JUnitPlatformOptions).setIncludeTags(setOf(lunaLiveTag))
    }
    inputs.property("fixedIncludeTags", lunaLiveTag)
    // 결과 보고서(원문 없음, 목적 · 응답 모델 · 지연 · 사유만)
    val report = layout.buildDirectory.file("luna-live/report.txt")
    outputs.file(report).withPropertyName("lunaLiveReport")
    systemProperty("mujoong.luna.live.report", report.get().asFile.absolutePath)
    // 보고서는 실행마다 새로 쓴다(지난 실행 결과가 섞이지 않게)
    doFirst {
        report.get().asFile.delete()
    }
    // 실호출은 매번 새로 돈다(캐시된 '통과'를 다시 보고하지 않음)
    outputs.upToDateWhen { false }
    // 공통 블록의 커버리지 보고(jacocoTestReport → test 의존)를 끈다: 실호출 작업이 기본 시험을 끌고 오지 않게
    setFinalizedBy(emptyList<Any>())
    extensions.configure<JacocoTaskExtension> {
        isEnabled = false
    }
    shouldRunAfter(tasks.named("test"))
}

// ---- GPT-Live 실호출 시험 분리 (U11 FR9.1 · BR9.1 · 기능 설계 W7, U9-c와 같은 방식) ----
// 실제 GPT-Live를 부르는 시험(@Tag("gpt-live"))은 모든 모듈의 기본 test 작업에서 언제나 빠진다. 사람이 키를 넣고 돌리는
// gptLiveTest 작업은 realtime-activity 빌드 파일에만 있고 check · build에 물리지 않는다. 확인은 루트 verifyGptLiveIsolation이 한다.

/** GPT-Live 실호출 시험 태그. */
val gptLiveTag = "gpt-live"

tasks.named<Test>("test") {
    options {
        (this as JUnitPlatformOptions).excludeTags(gptLiveTag)
    }
    inputs.property("fixedExcludeTagsGptLive", gptLiveTag)
    // 기본 시험은 GPT-Live 키를 보지 않는다(R-F1 · R-F3). 개발 PC env에 키가 있어도 시험 JVM에는 넘기지 않는다
    environment.remove("OPENAI_API_KEY")
    // 기본 test JVM이라는 표시(GptLiveIsolationTest가 이 JVM에서 키가 지워졌는지 본다)
    systemProperty("mujoong.test.liveIsolated", "true")
}

// ---- Luna 응답 여부 확인 시험 분리 (U11 Step 2 · 3 조사, lunaLiveTest · gptLiveTest와 같은 방식) ----
// 실제 Luna를 넉넉한 시한으로 부르는 시험(@Tag("luna-reach"))은 모든 모듈의 기본 test 작업에서 언제나 빠진다. OPENAI_API_KEY는 위
// luna-live 블록이 이미 지운다. lunaReachTest 작업은 realtime-activity 빌드 파일에만 있고 확인은 루트 verifyLunaReachIsolation이 한다.

/** Luna 응답 여부 확인 시험 태그. */
val lunaReachTag = "luna-reach"

tasks.named<Test>("test") {
    options {
        (this as JUnitPlatformOptions).excludeTags(lunaReachTag)
    }
    inputs.property("fixedExcludeTagsLunaReach", lunaReachTag)
}

// ---- 제브 실호출 시험 분리 (U13 Step 15b, lunaLiveTest · lunaReachTest와 같은 방식) ----
// 실제 제브를 부르는 시험(@Tag("jev-live"))은 모든 모듈의 기본 test 작업에서 언제나 빠지고, 시험 JVM에는 제브 키가 넘어가지 않는다(R-F1 · R-F3).
// jevLiveTest 작업은 realtime-activity 빌드 파일에만 있고 check · build에 물리지 않는다. 확인은 루트 verifyJevLiveIsolation이 한다.

/** 제브 실호출 시험 태그. */
val jevLiveTag = "jev-live"

tasks.named<Test>("test") {
    options {
        (this as JUnitPlatformOptions).excludeTags(jevLiveTag)
    }
    inputs.property("fixedExcludeTagsJevLive", jevLiveTag)
    // 기본 시험은 제브 키를 보지 않는다. 개발 PC env에 키가 있어도 시험 JVM에는 넘기지 않는다
    environment.remove("JEV_API_KEY")
}

// ---- 실음성 연습 대화 하니스 분리 (실측 하니스, gptLiveTest · jevLiveTest와 같은 방식) ----
// 실제 GPT-Live 음성 · Luna 판정 · TTS를 부르는 연습 대화 하니스(@Tag("gpt-live-practice"))는 모든 모듈의 기본 test 작업에서 언제나 빠지고,
// 시험 JVM에는 키가 넘어가지 않는다(R-F1 · R-F3). 태그는 이름이 정확히 같아야 걸리므로 gpt-live 제외와 따로 둔다. gptLivePracticeTest 작업은
// realtime-activity 빌드 파일에만 있고 check · build에 물리지 않는다. 확인은 루트 verifyGptLivePracticeIsolation이 한다.

/** 실음성 연습 대화 하니스 태그. */
val gptLivePracticeTag = "gpt-live-practice"

tasks.named<Test>("test") {
    options {
        (this as JUnitPlatformOptions).excludeTags(gptLivePracticeTag)
    }
    inputs.property("fixedExcludeTagsGptLivePractice", gptLivePracticeTag)
    // 기본 시험은 키를 보지 않는다. 개발 PC env에 키가 있어도 시험 JVM에는 넘기지 않는다
    environment.remove("OPENAI_API_KEY")
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
    // 실행 자원의 원본 ZIP을 클래스 아카이브로 분석하지 않는다. 모든 운영 클래스는 그대로 포함한다.
    classDirectories.setFrom(mainClassesDirs)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    limitToCoverageScope()
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    classDirectories.setFrom(mainClassesDirs)
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
