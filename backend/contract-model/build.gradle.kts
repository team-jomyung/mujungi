// contract-model: 두 앱이 같은 이름을 쓰는 계약 · 오류 · 시간 · 타입만 둔다 (U1 3.5 Step 3 · LC-11 · J70 · J105)
// Spring · JPA 의존을 두지 않는다(ArchUnit ②, Step 36). Jackson 직접 import는 kr.mujoong.contract.json 한 곳(J139).
plugins {
    id("mujoong.java-conventions")
    `java-library`
}

dependencies {
    api(platform(libs.spring.boot.bom))
    api(libs.jackson.databind)
    api(libs.slf4j.api)
    // 구조화 로그 포매터가 Logback 이벤트 형식을 다룰 때만 컴파일에 쓴다(실행 때는 앱이 가진 Logback)
    compileOnly(libs.logback.classic)

    testImplementation(libs.bundles.unit.test)
    testImplementation(libs.logback.classic)
    testImplementation(libs.archunit.junit5)
    // contracts/ YAML · JSON 파싱과 JSON Schema 2020-12 메타 검증(ContractFilesTest, Step 13)
    testImplementation(libs.jackson.dataformat.yaml)
    testImplementation(libs.json.schema.validator)
}
