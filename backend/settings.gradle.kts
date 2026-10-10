// 무중 백엔드 Gradle 멀티 프로젝트 (U1 3.5 Step 3 · ADR-002 · J105)
// - contract-model    : 두 앱이 같은 이름을 쓰는 계약 · 오류 · 시간 · 타입만 (LC-11 · J70)
// - core-api          : Core 앱 (DeviceRegistry · CoreMessaging · GuardianIdentity(dev) · ActivityCoordinator)
// - realtime-activity : Realtime 앱 (DeviceGateway · SessionLifecycle · VoiceRelay · OutputGate · ConversationLog · RealtimeMessaging)
// - test-support      : 시험 전용 지원 코드. testImplementation으로만 쓰이고 운영 jar에 들어가지 않는다 (J107 · J129 · SD-25)
pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "mujoong-backend"

include("contract-model", "core-api", "realtime-activity", "test-support")
