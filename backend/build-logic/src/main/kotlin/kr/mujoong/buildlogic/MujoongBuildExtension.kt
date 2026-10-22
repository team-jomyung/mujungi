package kr.mujoong.buildlogic

import org.gradle.api.provider.Property

/**
 * 모듈별 빌드 규약 스위치 (U1 3.5 Step 5 · J107).
 *
 * coverageFloorApplies: JaCoCo 80% 줄 하한 규칙(보고 전용, jacocoTestCoverageVerification을 직접 부를 때만)을 이 모듈에 거는지. 기본 true.
 * 시험 지원 모듈(test-support)만 false로 둔다(J107: 시험 소스와 같은 지위, 하한 대상 아님).
 */
abstract class MujoongBuildExtension {
    abstract val coverageFloorApplies: Property<Boolean>
}
