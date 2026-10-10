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

    testImplementation(project(":test-support"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.spring.boot.starter.jdbc.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.jackson.dataformat.yaml)
}

tasks.bootJar {
    archiveFileName.set("realtime-activity.jar")
}
