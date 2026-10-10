// test-support: 시험 전용 지원(가변 Clock · 수동 타이머 · PostgreSQL 백엔드 · 계약 대역 · 가짜 공급자 · 기기 시뮬레이터)
// (U1 3.5 Step 3 · J105 · J107 · J129 · J132 · SD-25)
// 앱 모듈은 testImplementation으로만 의존한다. 운영 코드 → kr.mujoong.testsupport 의존은 ArchUnit ③이 막는다.
plugins {
    id("mujoong.java-conventions")
    `java-library`
}

// 시험 지원 코드는 JaCoCo 80% 하한 대상이 아니다. 자기 시험은 둔다(J107)
mujoongBuild {
    coverageFloorApplies.set(false)
}

dependencies {
    api(platform(libs.spring.boot.bom))
    api(project(":contract-model"))

    // PostgreSQL 16 백엔드(docker = Testcontainers, local = 호스트 바이너리)와 두 위치 Flyway 적용, 역할별 DataSource (J132)
    api(libs.testcontainers.postgresql)
    api(libs.postgresql)
    api(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.hikaricp)
    // 계약 대역 서버의 contracts/schemas 요청 검증(Step 28)
    api(libs.json.schema.validator)
    implementation(libs.jackson.dataformat.yaml)
    // 가짜 공급자 시험용 WS 서버(J110 선 형식, Step 30)
    implementation(libs.java.websocket)
    // MUJOONG_EVENT 로그 줄 수집(LogCapture, Step 12)
    implementation(libs.logback.classic)

    // JUnit 확장 · Spring 시험 보조는 쓰는 앱의 시험 클래스패스에 이미 있다
    compileOnly(libs.junit.jupiter.api)
    compileOnly(libs.spring.boot.test)
    // 가짜 공급자 포트는 Realtime 앱 안 이벤트 형식(voice.ProviderPort)을 구현한다(J129, Step 30).
    // compileOnly라 Core 시험 실행 클래스패스에는 Realtime 클래스가 들어가지 않는다.
    compileOnly(project(":realtime-activity"))

    testImplementation(libs.bundles.unit.test)
}
