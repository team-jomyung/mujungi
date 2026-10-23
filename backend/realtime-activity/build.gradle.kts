// realtime-activity: Realtime 앱 (U1 3.5 Step 3 · ADR-002 · J105)
// DB 역할 realtime_app. 보안 체인은 내부 토큰 필터(C-INT)와 WSS 핸드셰이크 둘뿐이라 Spring Security를 두지 않는다(Step 33).
// 공급자 연결은 SDK 없이 JDK java.net.http.WebSocket을 쓴다(J110).
plugins {
    id("mujoong.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":contract-model"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.postgresql)
    // U9 Q3-A: Luna 응답 · 판정 요청을 계약 스키마로 검증한다(시험 범위 → 실행 범위, 판은 libs.versions.toml 그대로, J139 문자열 입력)
    implementation(libs.json.schema.validator)

    testImplementation(project(":test-support"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.spring.boot.starter.jdbc.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.jackson.dataformat.yaml)
}

// U9 Q3-A · SD12: 계약 스키마 정본 contracts/schemas/ai-*.schema.json을 실행 자원 luna/schemas/로 복사한다(정본 한 곳, R-F5).
// 다른 파일을 가리키는 $ref(common.schema.json)를 풀 수 있게 common.schema.json도 함께 복사한다(U9 Code Generation 편차)
tasks.processResources {
    from(rootProject.projectDir.parentFile.resolve("contracts/schemas")) {
        include("ai-*.schema.json", "common.schema.json")
        into("luna/schemas")
    }
}

tasks.bootJar {
    archiveFileName.set("realtime-activity.jar")
}

// ---- GPT-Live 격리 실호출 (U11 FR9.1 · BR9.1 · 기능 설계 W7, lunaLiveTest와 같은 방식) ----
// @Tag("gpt-live") 시험만 돈다. 사람이 env OPENAI_API_KEY를 넣고 ./gradlew :realtime-activity:gptLiveTest로 직접 돌린다.
// 기본 test는 build-logic 규약이 gpt-live를 고정 제외하고 키를 지운다. 이 작업은 check · build에 물리지 않고,
// 루트 verifyGptLiveIsolation(check가 의존)이 check 작업 그래프에 이 작업이 없음을 확인한다.
val gptLiveTag = "gpt-live"
val liveTestSourceSet = sourceSets["test"]
tasks.register<Test>("gptLiveTest") {
    group = "verification"
    description = "GPT-Live 격리 실호출(@Tag(\"gpt-live\"), 사람이 env OPENAI_API_KEY를 넣고 돌림). 키가 없으면 SKIPPED_NO_KEY로 끝난다."
    testClassesDirs = liveTestSourceSet.output.classesDirs
    classpath = liveTestSourceSet.runtimeClasspath
    options {
        (this as JUnitPlatformOptions).setIncludeTags(setOf(gptLiveTag))
    }
    inputs.property("fixedIncludeTags", gptLiveTag)
    // 결과 보고서(항목 · 관측 · 통과 여부만, 전사 원문 · 음성 · 키 없음)
    val report = layout.buildDirectory.file("gpt-live/report.md")
    outputs.file(report).withPropertyName("gptLiveReport")
    systemProperty("mujoong.gpt.live.report", report.get().asFile.absolutePath)
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
    // 진행 상황(관측 요약 한 줄)을 보이게 한다
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
    shouldRunAfter(tasks.named("test"))
}

// ---- Luna 응답 여부 · 지연 확인 (U11 Step 2 · 3 조사, lunaLiveTest · gptLiveTest와 같은 방식) ----
// @Tag("luna-reach") 시험만 돈다. 사람이 env OPENAI_API_KEY를 넣고 ./gradlew :realtime-activity:lunaReachTest로 직접 돌린다.
// 운영 Luna 호출부를 그대로 쓰되 시도 시한만 넉넉히(env LUNA_REACH_TIMEOUT_SECONDS) 준다. 기본 test는 build-logic 규약이
// luna-reach를 고정 제외하고 키를 지운다. 이 작업은 check · build에 물리지 않고 루트 verifyLunaReachIsolation이 확인한다.
val lunaReachTag = "luna-reach"
tasks.register<Test>("lunaReachTest") {
    group = "verification"
    description = "Luna 응답 여부 · 지연 확인(@Tag(\"luna-reach\"), 사람이 env OPENAI_API_KEY를 넣고 돌림). 키가 없으면 SKIPPED_NO_KEY로 끝난다."
    testClassesDirs = liveTestSourceSet.output.classesDirs
    classpath = liveTestSourceSet.runtimeClasspath
    options {
        (this as JUnitPlatformOptions).setIncludeTags(setOf(lunaReachTag))
    }
    inputs.property("fixedIncludeTags", lunaReachTag)
    // 결과 보고서(회차 · 사유 · 응답 모델 · 선택지 코드 · 지연만, 요청 · 응답 원문 · 키 · 주소 없음)
    val report = layout.buildDirectory.file("luna-reach/report.md")
    outputs.file(report).withPropertyName("lunaReachReport")
    systemProperty("mujoong.luna.reach.report", report.get().asFile.absolutePath)
    doFirst {
        report.get().asFile.delete()
    }
    outputs.upToDateWhen { false }
    setFinalizedBy(emptyList<Any>())
    extensions.configure<JacocoTaskExtension> {
        isEnabled = false
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
    shouldRunAfter(tasks.named("test"))
}

// ---- 실음성 연습 대화 하니스 (실측 하니스, gptLiveTest와 같은 방식, docs/practice-dialogue-standard.md 11절) ----
// @Tag("gpt-live-practice") 시험만 돈다. 사람이 env OPENAI_API_KEY와 MUJOONG_LIVE_EVAL_OUT(· 상한 env)을 넣고
// ./gradlew :realtime-activity:gptLivePracticeTest로 직접 돌린다. 운영 실시간 세션 엔진에 실제 GPT-Live 음성 · Luna 판정 · TTS(합성 아이 음성)를
// 붙여 대화 기록을 파일로 쓴다. 기본 test는 build-logic 규약이 gpt-live-practice를 고정 제외하고 키를 지운다. 이 작업은 check · build에 물리지
// 않고, 루트 verifyGptLivePracticeIsolation(check가 의존)이 check 작업 그래프에 이 작업이 없음을 확인한다.
val gptLivePracticeTag = "gpt-live-practice"
tasks.register<Test>("gptLivePracticeTest") {
    group = "verification"
    description = "실음성 연습 대화 하니스(@Tag(\"gpt-live-practice\"), 사람이 env OPENAI_API_KEY를 넣고 돌림). 키가 없으면 SKIPPED_NO_KEY로 끝난다."
    testClassesDirs = liveTestSourceSet.output.classesDirs
    classpath = liveTestSourceSet.runtimeClasspath
    options {
        (this as JUnitPlatformOptions).setIncludeTags(setOf(gptLivePracticeTag))
    }
    inputs.property("fixedIncludeTags", gptLivePracticeTag)
    // 실호출은 매번 새로 돈다(캐시된 '통과'를 다시 보고하지 않음)
    outputs.upToDateWhen { false }
    // 공통 블록의 커버리지 보고(jacocoTestReport → test 의존)를 끈다: 실호출 작업이 기본 시험을 끌고 오지 않게
    setFinalizedBy(emptyList<Any>())
    extensions.configure<JacocoTaskExtension> {
        isEnabled = false
    }
    // 진행 상황(개수 · 파일 위치 한 줄)을 보이게 한다. 대화 원문은 파일에만 쓴다(R-F14)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
    shouldRunAfter(tasks.named("test"))
}

// ---- 제브 실호출 (U13 Step 15b, lunaReachTest · gptLiveTest와 같은 방식) ----
// @Tag("jev-live") 시험만 돈다. 사람이 그 PC의 셸 env JEV_API_KEY · JEV_BASE_URL · JEV_MODEL을 넣고 ./gradlew :realtime-activity:jevLiveTest로
// 직접 돌린다(키는 채팅 · 파일 · 저장소에 두지 않는다, R-F3 · R-F13). 기본 test는 build-logic 규약이 jev-live를 고정 제외하고 키를 지운다.
// 이 작업은 check · build에 물리지 않고 루트 verifyJevLiveIsolation(check가 의존)이 확인한다.
val jevLiveTag = "jev-live"
tasks.register<Test>("jevLiveTest") {
    group = "verification"
    description = "제브 실호출(@Tag(\"jev-live\"), 사람이 env JEV_API_KEY · JEV_BASE_URL · JEV_MODEL을 넣고 돌림). 키가 없으면 SKIPPED_NO_KEY로 끝난다."
    testClassesDirs = liveTestSourceSet.output.classesDirs
    classpath = liveTestSourceSet.runtimeClasspath
    options {
        (this as JUnitPlatformOptions).setIncludeTags(setOf(jevLiveTag))
    }
    inputs.property("fixedIncludeTags", jevLiveTag)
    // 결과 보고서(표본 이름 · 기대 · 판정 · 실패 종류 · 걸린 분류 · 사유 코드 · 지연만, 후보 문장 원문 · 키 · 주소 없음, R-F14)
    val report = layout.buildDirectory.file("jev-live/report.txt")
    outputs.file(report).withPropertyName("jevLiveReport")
    systemProperty("mujoong.jev.live.report", report.get().asFile.absolutePath)
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
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
    shouldRunAfter(tasks.named("test"))
}
