// 빌드 규약 포함 빌드 (U1 3.5 Step 3 · J105)
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
    }
    // 규약 플러그인이 쓰는 Gradle 플러그인 판도 백엔드 판 목록 한 곳에서 읽는다
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
