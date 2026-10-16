// core-api: Core 앱 (U1 3.5 Step 3 · ADR-002 · J105)
// DB 역할 core_app, 앱은 Flyway를 실행하지 않고 이력 버전만 읽는다(spring.flyway.enabled=false, BR8.1 · BR8.2).
plugins {
    id("mujoong.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":contract-model"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.session.jdbc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.postgresql)
    // U6 BR10.6: 파일 저장 없는 텍스트 PDF(LGPL-2.1-or-later).
    implementation("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")
    implementation(libs.push2u.core)
    implementation(libs.httpclient5)
    // OpenAPI 문서는 dev · test 프로필에서만 켠다(application.yml 기본 꺼짐, tech-stack 2.2)
    implementation(libs.springdoc.openapi.webmvc.api)
    // U9 Q3-A: Luna 응답을 계약 스키마로 검증한다(시험 범위 → 실행 범위, 판은 libs.versions.toml 그대로). 문자열 입력으로만 부른다(J139)
    implementation(libs.json.schema.validator)

    // test-support는 시험 소스 집합에서만 쓴다(운영 jar 미포함, J105 · SD-25)
    testImplementation(project(":test-support"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.spring.boot.starter.jdbc.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.jackson.dataformat.yaml)
}

// U9 Q3-A · SD12: 계약 스키마 정본 contracts/schemas/ai-*.schema.json을 실행 자원 luna/schemas/로 복사한다(정본 한 곳, R-F5).
// ai-rejudgment-output이 common.schema.json#/$defs/Uuid를 가리키므로 common.schema.json도 함께 복사한다(U9 Code Generation 편차)
tasks.processResources {
    from(rootProject.projectDir.parentFile.resolve("contracts/schemas")) {
        include("ai-*.schema.json", "common.schema.json")
        into("luna/schemas")
    }
}

tasks.bootJar {
    archiveFileName.set("core-api.jar")
}
