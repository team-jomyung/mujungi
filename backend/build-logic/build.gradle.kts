// 규약 플러그인 mujoong.java-conventions를 만드는 빌드 (U1 3.5 Step 3 · J105)
plugins {
    `kotlin-dsl`
}

dependencies {
    // CI 전용 시험 재시도(org.gradle.test-retry)
    implementation(libs.gradle.test.retry.plugin)
}
