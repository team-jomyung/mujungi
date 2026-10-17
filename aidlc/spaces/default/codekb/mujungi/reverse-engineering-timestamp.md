# Reverse Engineering Timestamp

## 이번 실행 기록 (261017-jev-adapter-format)

| 항목 | 값 |
|---|---|
| 날짜 | 2026-10-18 |
| 커밋 | 421e1ade0cb9939b20c7a485bbc13c9131dbbf3e |
| 브랜치 | unit/u13-jev-guardrail (U13 구현이 끝난 상태, 아직 `main`에 병합 전. 로컬에는 `main` 브랜치가 없고 `origin/main`만 있다) |
| 인텐트 | 261017-jev-adapter-format |
| 범위 · 깊이 | scope bugfix, 깊이 Standard, 브라운필드, 단일 저장소(프로젝트 루트) |
| 스캔 방식 | 집중 스캔(Focused scan). 이전 store 상태는 STALE(이전 U13 스캔 뒤 `backend/realtime-activity` · `contract-model` · `test-support` · `contracts`가 바뀜) |
| 스냅샷 | `store_generation` sha256:0aff12c79a1a71821bbff09a149627298280aaabef7318a861e07acdb344e72d, `source_fingerprint` git:779adccc4fea7962c4f3e034b57e66f2e0c14e27 |
| 방법 | 개발자 집중 스캔(handoff `inception/reverse-engineering/developer-scan.md`) + 아키텍트 합성. 합성 때 `GuardrailCoordinator`의 검사 시작 · 결과 처리 구간과 `SessionExecutor`의 제브 연결 줄, `SessionMailbox`의 제브 메시지 두 개를 상호작용 그림을 그리려고 추가로 읽었다(얕게). 앱 · 시험 · 외부 공급자는 실행하지 않았다(R-F1 · R-F3 · R-M11). |

- **병합 방식**: 각 산출물 맨 위에 이 인텐트의 새 절(공급자 선 형식 집중 갱신)을 추가했다. 2026-10-17 U13 구현 전 분석 절은 "구현 전 이력"으로 제목을 바꿔 보존했고(구현으로 달라진 사실은 새 절이 우선한다), U14 · U15 · U11 및 2026-10-15 전체 재스캔 본문은 그대로 보존했다.
- **강등**: 이전 store의 `analyzed.paths`(`backend/realtime-activity` · `backend/contract-model` · `backend/test-support` · `contracts`)는 재검증하지 못해 shallow로 내렸다. 이번에 다시 깊게 읽은 `backend/realtime-activity` · `contracts`는 analyzed에 있으므로 shallow 목록에서 뺐다(중복 방지). 이전 `analyzed.components`(`u13.*` 7개)는 구현 전 코드 기준이라 이력 제목으로 바꾸고 이번 `analyzed.components`에서 뺐다.
- **정직성 노트(깊이)**: analyzed 경로는 스냅샷 paths 안(`backend/realtime-activity/`, `contracts/`, `docs/units/u13/`)이지만 실제로 깊게 읽은 것은 그 안의 일부다. 깊게 읽은 것: `realtime/guardrail/` 19개 파일 전체, `session/GuardrailCoordinator.java`(소비 지점 위주 + 검사 시작 · 결과 처리 구간), `PracticeSessionExtension.GuardrailBlockedEvent`, `content/LiveContentResources`의 guardrail 적재부, `observability/RealtimeMetrics`의 `mujoong.guardrail.*` 상수, `guardrail/policy-v1.json`, `application.yml`의 `mujoong.guardrail.jev` · `mujoong.timing.guardrail`, `V6__u13_jev_guardrail.sql`, `src/test/.../guardrail/` 전체와 `ConfigKeyContractTest` · `TimeLiteralScanTest` · `FakeGuardrailClient`의 제브 대목, `contracts/ai-io.md` 10.1 ~ 10.5 · 10.11, `contracts/deviations.md`의 `(U13-a)` `(U13-b)`, `guardrail-request/result.schema.json`, `contracts/timing.md` 13절 제브 행, `docs/units/u13/{brief,design,report}.md`의 형식 가정 · 열린 항목 부분. `SessionExecutor` · `SessionMailbox` · `RealtimeStartupChecks` · `session/Guardrail*Test`는 이름과 연결 지점 수준이고, 나머지 realtime 패키지(`gateway` · `recording` · `practice` · `dialog` · `messaging` · `voice` 대부분)는 읽지 않았다. 다음 실행은 이 경계를 넓게 믿지 말고 아래 `analyzed.components`만 신뢰한다.
- **외부 정본 표시**: 제브 공식 형식은 스캔 문서에 없는 외부 정본이며 `api-documentation.md` 외부 API 절에 "공식 문서 기준, 코드 미반영"으로 한 번만 적었다.
- **열린 항목(설계 결정이 아님)**: `code-quality-assessment.md`의 JEV-F1 ~ JEV-F12 참조.

## Prior Run Record (U13 구현 전 분석, historical)

- Date: 2026-10-17
- Commit: f1b0980291aa33f171eb1484feaa9eb532b3239f
- Branch: unit/u13-jev-guardrail (main `6a78536` + 지시서 문서 커밋 `f1b0980`, 코드는 main과 같다)
- Intent: 261017-u13-jev-guardrail
- Breadth: focused, Standard, scope refactor; prior store STALE (built by 261016-u14-goal-result, paths changed since).
- Method: developer 집중 스캔 + architect 합성. 앱 · 시험 · 외부 공급자는 실행하지 않았다(R-F1 · R-F3).
- Merge: 새 U13 절을 각 산출물 맨 위에 추가하고 U14 · U15 · U11 및 2026-10-15 전체 재스캔 본문은 이력으로 보존했다. 이전 `analyzed.paths`(U14 deep 파일 26개)는 shallow로 강등했다.
- Honesty note: 게시 명령이 snapshot 경로(`backend/realtime-activity`, `backend/contract-model`, `backend/test-support`, `contracts`) 밖의 하위 경로를 analyzed로 받지 않아 analyzed.paths를 이 네 경로로 적었다. 실제로 깊게 읽은 범위는 그보다 좁았다(realtime gate · `SessionExecutor` 해당 구간 · `JudgmentCoordinator` · judgment · luna · content · startup · log/TranscriptWriter · observability 2개 · `PracticeSpeech` · `RealtimeRuntimeConfig` · `RealtimeProperties` · application*.yml · policy-v1.json · V5 SQL · contracts ai-io · timing · deviations · guardrail 스키마 2종). `contract-model`은 위치 확인, `test-support`는 존재 확인 수준이었다.
- Open (사람 결정): G3 · G4 · G5 · G6 · G9 · G11, 확인 필요: G8. 이 항목들은 구현 뒤 해소된 것이 있을 수 있으며, 구현 후 상태는 `docs/units/u13/report.md`가 정본이다.

## Prior Run Record (U14, historical)

- Date: 2026-10-17
- Commit: eb34c68bf24e4520881b7ffebe1070564554dfc9
- Intent: 261017-u10-scenario-generation
- Breadth: focused, Standard; prior store STALE.
- Method: 개발자 단독 읽기·합성, 앱 변경·시험 실행 없음.
- 이전8문서의 범위 밖 본문을 그대로 보존했다. 이전 deep 경로를 shallow로 내리고 이번 실제 읽은 파일만 analyzed로 기록한다. 원격 main 병합 상황은 이 로컬 소스 기준과 별개다.

## Prior Run Record (historical)

## Run Record

- Date: 2026-10-16
- Commit: 1a4ef7b04f5cb4d6490e40c3b3d1a70c1d2b9014
- Intent: 261016-u14-goal-result
- Breadth: focused, Standard; prior store STALE.
- Method: single developer scan and synthesis; no application edits or tests executed.
- Prior prose outside this focus is preserved in all eight topic artifacts; previous verified deep coverage is demoted into shallow. Deep coverage below lists actual files read, not the broader snapshot bounds.

## Prior Run Record (historical)

### U15 Run Record

| 항목 | 값 |
|---|---|
| 날짜 | 2026-10-16 |
| 저장소 | mujungi |
| 커밋 | 7e30ee8b2ad6d157a95d749ab774f3b7c0b9385d |
| 브랜치 | unit/u15-knowledge-graph |
| 인텐트 | 261016-u15-knowledge-graph |
| 범위 | Standard, U15 focused merge, 이전 STALE |
| 수행 | 개발자 handoff 확인 후 사용자 승인으로 이 채팅에서 합성 |

기존 9문서의 관련 없는 본문을 이력으로 보존했다. 이전 deep 경로는 shallow로 내렸으며 이번 분석 범위만 아래 analyzed에 기록한다. source zip 실제 내용, frontend/realtime 구현, CI yaml은 새로 검증하지 않았다. 초기 기준 테스트는 CRLF 초기화 실패였다. Linux 스크립트를 LF로 정규화했고 현재 재시험 중이다. 제품 소스의 Git 내용은 변경되지 않았음을 재확인했다. 개발자 handoff는 현재 인텐트 inception/reverse-engineering/developer-scan.md에 보존한다.



## Scope of Analysis

```yaml
scope_version: 1
kind: partial
intent: 261017-jev-adapter-format
fingerprint: 7cd8d189c45a034a782e406243d1499b8d17f029
analyzed:
  paths:
    - backend/realtime-activity
    - contracts
    - docs/units/u13
    - backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioService.java
    - backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioContentPolicy.java
    - backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioRepository.java
    - backend/core-api/src/main/java/kr/mujoong/core/practice/Scenario.java
    - backend/core-api/src/main/java/kr/mujoong/core/practice/PracticeSessionContextPort.java
    - backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioConfiguration.java
    - backend/core-api/src/main/java/kr/mujoong/core/practice/web/ScenarioInternalController.java
    - backend/core-api/src/main/java/kr/mujoong/core/practice/start/PracticeStartService.java
    - backend/core-api/src/main/java/kr/mujoong/core/practice/start/PreparedPracticePackageMapper.java
    - backend/core-api/src/main/java/kr/mujoong/core/practice/start/FrozenGoal.java
    - backend/core-api/src/main/java/kr/mujoong/core/textai/TextAiGateway.java
    - backend/core-api/src/main/java/kr/mujoong/core/textai/GeneratedScenario.java
    - backend/core-api/src/main/java/kr/mujoong/core/textai/ScenarioPrompt.java
    - backend/core-api/src/main/java/kr/mujoong/core/textai/ForbiddenExpressionPolicy.java
    - backend/core-api/src/main/java/kr/mujoong/core/luna/adapter/LunaTextAiProvider.java
    - backend/core-api/src/main/java/kr/mujoong/core/luna/CoreLunaClient.java
    - backend/core-api/src/main/java/kr/mujoong/core/luna/LunaInputSanitizer.java
    - backend/core-api/src/main/java/kr/mujoong/core/luna/LunaCallBudget.java
    - backend/core-api/src/main/java/kr/mujoong/core/luna/LunaPurpose.java
    - backend/core-api/src/main/java/kr/mujoong/core/luna/LunaRequest.java
    - backend/core-api/src/main/java/kr/mujoong/core/practicegoal/SavedGoalReader.java
    - backend/core-api/src/main/java/kr/mujoong/core/practicegoal/PinnedSavedGoalReader.java
    - backend/core-api/src/main/java/kr/mujoong/core/practicegoal/FourElements.java
    - backend/core-api/src/main/java/kr/mujoong/core/practicegoal/PracticeGoalRepository.java
    - backend/core-api/src/main/java/kr/mujoong/core/knowledge/ChildKnowledgeService.java
    - backend/core-api/src/main/java/kr/mujoong/core/knowledge/VariantPlanner.java
    - backend/core-api/src/main/java/kr/mujoong/core/knowledge/KnowledgeGraphLookup.java
    - backend/core-api/src/main/java/kr/mujoong/core/knowledge/KnowledgeSeed.java
    - backend/core-api/src/main/resources/db/migration/core/V4__u5a_practice_core.sql
    - backend/core-api/src/main/resources/db/migration/core/V9__u14_goal_result.sql
    - backend/core-api/build.gradle.kts
    - backend/core-api/src/test/java/kr/mujoong/core/practice/ScenarioServiceTest.java
    - docs/units/u10/brief.md
  components:
    - u13.jev-adapter-wire-format
    - u13.guardrail-request-factory
    - u13.verdict-conversion-and-consumers
    - u13.policy-resource-and-contract-schemas
    - u13.guardrail-config-and-live-probe
    - u13.adapter-bound-tests
    - u10.scenario-orchestration
    - u10.preparation-compatibility
    - u10.luna-boundary
    - u10.saved-goal-context
    - u10.graph-context
    - u10.contract-persistence
shallow:
  paths:
    - backend/contract-model/
    - backend/test-support/
    - backend/build.gradle.kts
    - infra/ci/image-checks.sh
    - infra/ci/gen-env.sh
    - infra/RUNBOOK.md
    - aidlc/spaces/default/knowledge/documents/
    - backend/core-api/src/main/java/kr/mujoong/core/practice/CandidateRegistry.java
    - backend/core-api/src/main/java/kr/mujoong/core/practice/PracticeGoalRepository.java
    - backend/core-api/src/main/java/kr/mujoong/core/practice/PracticeDeletionParticipant.java
    - backend/core-api/src/main/java/kr/mujoong/core/result/SessionResultHandler.java
    - backend/core-api/src/main/java/kr/mujoong/core/result/ResultAiSchema.java
    - backend/core-api/src/main/java/kr/mujoong/core/result/ResultData.java
    - backend/core-api/src/main/java/kr/mujoong/core/result/JobRunner.java
    - backend/core-api/src/main/java/kr/mujoong/core/result/ResultQueryService.java
    - backend/core-api/src/main/java/kr/mujoong/core/result/SceneChipMapper.java
    - backend/core-api/src/main/java/kr/mujoong/core/result/SceneJudgmentRepository.java
    - backend/core-api/src/main/java/kr/mujoong/core/result/CandidateScreen.java
    - backend/core-api/src/main/java/kr/mujoong/core/result/ResultDeletionParticipant.java
    - backend/core-api/src/main/java/kr/mujoong/core/report/ReportAssembler.java
    - backend/core-api/src/main/java/kr/mujoong/core/knowledge/SeedGenerationService.java
    - backend/core-api/src/main/java/kr/mujoong/core/knowledge/StructureSignature.java
    - backend/core-api/src/main/resources/db/migration/core/V5__u6_results_report.sql
    - frontend/src/parent/api/results.ts
    - frontend/src/shared/timing/timing-defaults.json
    - infra/migration-approvals.txt
    - backend/core-api/src/main/java/kr/mujoong/core/knowledge/
    - backend/core-api/src/main/java/kr/mujoong/core/common/deletion/
    - backend/core-api/src/main/java/kr/mujoong/core/account/DeletionCommander.java
    - backend/core-api/src/main/java/kr/mujoong/core/identity/GuardianWriteLock.java
    - backend/core-api/src/main/java/kr/mujoong/core/result/check/ForbiddenDictionary.java
    - backend/core-api/src/main/java/kr/mujoong/core/luna/LunaResult.java
    - backend/core-api/src/main/java/kr/mujoong/core/luna/LunaConfiguration.java
    - backend/core-api/src/main/resources/db/migration/core/V7__u5b_practice_preparation.sql
    - backend/core-api/src/main/resources/db/migration/core/afterMigrate.sql
    - backend/core-api/src/test/java/kr/mujoong/core/knowledge/PragmaticsKnowledgeTest.java
    - backend/core-api/src/test/java/kr/mujoong/core/arch/LunaArchTest.java
    - backend/contract-model/build.gradle.kts
    - backend/contract-model/src/main/java/kr/mujoong/contract/event/payload/DeletionCompleted.java
    - backend/gradle/libs.versions.toml
    - backend/gradle/wrapper/gradle-wrapper.properties
    - backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts
    - scripts/verify/a-checks.sh
    - backend/settings.gradle.kts
    - backend/gradle.properties
    - backend/build-logic/
    - backend/core-api/src/main/java/kr/mujoong/core/textai/TextAiConfiguration.java
    - backend/core-api/src/main/java/kr/mujoong/core/result/ResultTextConfiguration.java
    - backend/core-api/src/main/resources/application.yml
    - backend/core-api/src/main/resources/db/migration/core/
    - frontend/package.json
    - frontend/next.config.ts
    - frontend/vitest.config.ts
    - frontend/eslint.config.mjs
    - frontend/src/app/
    - tools/package.json
    - .github/workflows/ci.yml
    - .prettierrc.json
    - backend/core-api/src/main/java/
    - backend/core-api/src/test/
    - backend/core-api/src/main/resources/
    - backend/coverage-scope/
    - frontend/coverage-scope/
    - frontend/src/
    - frontend/tests/
    - tools/
    - infra/
    - scripts/
    - ci/
    - .github/workflows/deploy.yml
    - docs/
    - backend/coverage-scope/u11.txt
    - backend/core-api/src/test/java/kr/mujoong/core/luna/
    - backend/core-api/src/test/java/kr/mujoong/core/knowledge/
    - backend/core-api/src/test/java/kr/mujoong/core/practicegoal/
```
