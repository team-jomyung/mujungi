# 리버스 엔지니어링 시점 (Reverse Engineering Timestamp)

| 항목 | 값 |
|---|---|
| 수행 일시 (UTC) | 2026-10-13T06:58:04Z |
| 저장소 | `mujungi` (프로젝트 루트, 단일 저장소) |
| 브랜치 | `unit/u4b-activity-realtime` |
| 커밋 | `3748083f1b824234d4cc188baf4da631124008ee` |
| 인텐트 | `261013-u4b-activity-realtime` |
| 스캔 종류 | 집중 스캔(첫 스캔, 기존 저장소 없음 · NO_STORE) → `kind: partial` |
| 수행 | 1단계 개발자 코드 스캔 → 2단계 아키텍트 합성 |

## 범위 설명

- **깊게 분석**: `backend/realtime-activity/`(운영 · 시험 · 리소스 · 마이그레이션 · 빌드 · Dockerfile 머리), `backend/contract-model/`, `backend/test-support/`, `backend/build-logic/`, `contracts/`(Realtime 관련 계약 파일).
- **훑기만**: Gradle 루트 설정 · 버전 카탈로그 · wrapper, `backend/coverage-scope/`, `backend/core-api/`(Realtime 경계만), `.github/workflows/`, `frontend/`(판만), `infra/`(목록), `docs/units/`(방향 잡기용).
- 작업 트리에는 셸 스크립트 · `gradlew` 41개 파일의 모드 왕복(`MM`)이 있었으나 분석 대상 소스 내용에는 영향이 없다.

## Scope of Analysis

```yaml
scope_version: 1
kind: partial
intent: 261013-u4b-activity-realtime
fingerprint: f4b288abe404f3342de4c0b7330a969b64c4fe45
analyzed:
  paths:
    - backend/realtime-activity/
    - backend/contract-model/
    - backend/test-support/
    - backend/build-logic/
    - contracts/
  components:
    - contract-model
    - test-support
    - build-logic
    - contracts
    - realtime.session
    - realtime.dialog
    - realtime.gateway
    - realtime.voice
    - realtime.gate
    - realtime.log
    - realtime.messaging
    - realtime.recording
    - realtime.runtime
    - realtime.internalhttp
    - realtime.startup
    - realtime.web
    - realtime.config
    - realtime.observability
    - realtime.common
shallow:
  paths:
    - backend/settings.gradle.kts
    - backend/build.gradle.kts
    - backend/gradle/
    - backend/gradle.properties
    - backend/coverage-scope/
    - backend/core-api/
    - .github/workflows/
    - frontend/
    - infra/
    - docs/units/
```
