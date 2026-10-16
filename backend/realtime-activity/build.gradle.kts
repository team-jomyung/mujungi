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
