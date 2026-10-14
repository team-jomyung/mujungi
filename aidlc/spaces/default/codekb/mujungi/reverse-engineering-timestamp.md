# 리버스 엔지니어링 시점 (Reverse Engineering Timestamp)

| 항목 | 값 |
|---|---|
| 수행 일시 (UTC) | 2026-10-14T00:25:08Z |
| 저장소 | `mujungi` (프로젝트 루트, 단일 저장소) |
| 브랜치 | `unit/u6-result-report` (= origin/main) |
| 커밋 | `7a22b67b455075d588f6dc11da8403d5cd6878c1` |
| 인텐트 | `261014-u6-result-report` |
| 스캔 종류 | 전체 재스캔(사람 결정, 이전 저장소 STALE) — 9개 산출물 통째 교체, 실제 깊게 본 범위가 저장소 일부라 `kind: partial` |
| 사전 스냅숏 | paths `./`, store_generation `sha256:5c9a44bb7c17e7eb218ed84bb7b52ae6c26f25c2dcd036b271fca10cca4beefe`, source_fingerprint `git:8f0d2f0400f6af6c01e00e0887beb74d61f861cb` |
| 수행 | 1단계 개발자 코드 스캔 → 2단계 아키텍트 합성 |

## 범위 설명

- 이전 저장소(인텐트 `261013-u4b-activity-realtime`, 커밋 `3748083`)는 방향 잡기로만 쓰였고, 이 산출물은 모두 이번 스캔 결과로 다시 썼다. 이전의 깊은 범위(`backend/realtime-activity/` 전체 등)는 이번에 다시 확인하지 않았으므로 깊은 범위로 옮기지 않았다.
- **깊게 분석**: U6 접점 — Gradle 빌드 설정 · build-logic, contract-model 이벤트, core-api의 `record` · `knowledge` · `practice` · `textai` · `common/deletion` · 설정 · 마이그레이션 · 금지 사전 · Dockerfile, realtime의 결과 스냅숏 · 연습 확장점 · 마이그레이션, 커버리지 범위, 계약 5개 파일, 프런트 26 스텁 · 시험 설정, infra 마이그레이션 · 승인 목록 · demo compose, CI, U6 지시서.
- **훑기만**: core-api 나머지 패키지 · 시험, realtime 나머지, 프런트 화면 · 시험, 나머지 계약 파일, infra 나머지, tools · scripts · ci · docs.
- 작업 트리: 소스 변경 없음(AI-DLC 기록 파일만 변경).

## Scope of Analysis

```yaml
scope_version: 1
kind: partial
intent: 261014-u6-result-report
fingerprint: 201ba2b6302a561312d2c531723adb423d5eaac7
analyzed:
  paths:
    - backend/settings.gradle.kts
    - backend/build.gradle.kts
    - backend/gradle/libs.versions.toml
    - backend/gradle.properties
    - backend/build-logic/
    - backend/contract-model/build.gradle.kts
    - backend/contract-model/src/main/java/kr/mujoong/contract/event/
    - backend/core-api/build.gradle.kts
    - backend/core-api/src/main/java/kr/mujoong/core/record/
    - backend/core-api/src/main/java/kr/mujoong/core/knowledge/
    - backend/core-api/src/main/java/kr/mujoong/core/practice/
    - backend/core-api/src/main/java/kr/mujoong/core/textai/
    - backend/core-api/src/main/java/kr/mujoong/core/common/deletion/
    - backend/core-api/src/main/resources/application.yml
    - backend/core-api/src/main/resources/db/migration/core/
    - backend/core-api/src/main/resources/textai/
    - backend/core-api/Dockerfile
    - backend/realtime-activity/build.gradle.kts
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/ResultSnapshotBuilder.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/PracticeSessionExtension.java
    - backend/realtime-activity/src/main/resources/db/migration/realtime/
    - backend/test-support/build.gradle.kts
    - backend/coverage-scope/
    - contracts/ai-io.md
    - contracts/ports.md
    - contracts/timing.md
    - contracts/deviations.md
    - contracts/parent-api.yaml
    - frontend/package.json
    - frontend/vitest.config.ts
    - frontend/coverage-scope/
    - frontend/src/app/parent/records/page.tsx
    - infra/migration-approvals.txt
    - infra/migrate/
    - infra/compose.demo.yml
    - .github/workflows/ci.yml
    - docs/units/u6/brief.md
  components:
    - build-logic
    - contract-model
    - core.record
    - core.practice
    - core.knowledge
    - core.textai
    - core.common.deletion
    - realtime.result-snapshot
    - contracts
    - infra.migrate
    - ci-workflow
    - frontend.parent.records
shallow:
  paths:
    - backend/core-api/src/main/java/kr/mujoong/core/account/
    - backend/core-api/src/main/java/kr/mujoong/core/activity/
    - backend/core-api/src/main/java/kr/mujoong/core/catalog/
    - backend/core-api/src/main/java/kr/mujoong/core/child/
    - backend/core-api/src/main/java/kr/mujoong/core/common/db/
    - backend/core-api/src/main/java/kr/mujoong/core/config/
    - backend/core-api/src/main/java/kr/mujoong/core/consent/
    - backend/core-api/src/main/java/kr/mujoong/core/device/
    - backend/core-api/src/main/java/kr/mujoong/core/health/
    - backend/core-api/src/main/java/kr/mujoong/core/identity/
    - backend/core-api/src/main/java/kr/mujoong/core/internalhttp/
    - backend/core-api/src/main/java/kr/mujoong/core/messaging/
    - backend/core-api/src/main/java/kr/mujoong/core/observability/
    - backend/core-api/src/main/java/kr/mujoong/core/security/
    - backend/core-api/src/main/java/kr/mujoong/core/startup/
    - backend/core-api/src/main/java/kr/mujoong/core/web/
    - backend/core-api/src/test/
    - backend/realtime-activity/src/main/java/
    - backend/realtime-activity/src/test/
    - backend/contract-model/src/test/
    - backend/test-support/src/
    - frontend/src/
    - frontend/tests/
    - frontend/eslint.config.mjs
    - contracts/schemas/
    - contracts/events.asyncapi.yaml
    - contracts/internal-api.yaml
    - contracts/device-api.yaml
    - contracts/device-ws.asyncapi.yaml
    - infra/bin/
    - infra/ci/
    - infra/nginx/
    - infra/postgres/
    - infra/seed/
    - infra/seed-audio/
    - infra/aws/
    - infra/host/
    - infra/backup/
    - infra/bundle/
    - tools/
    - scripts/
    - ci/
    - docs/
```
