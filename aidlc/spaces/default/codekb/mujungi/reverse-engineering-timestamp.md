# Reverse Engineering Timestamp

## Run Record

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
intent: 261017-u10-scenario-generation
fingerprint: 74accc119e7b2b5f884803ccda963c4a3e21f666
analyzed:
  paths:
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
    - contracts/README.md
    - contracts/schemas/ai-scenario-output.schema.json
    - contracts/schemas/ai-scenario-review-output.schema.json
    - contracts/schemas/practice-scenario.schema.json
    - contracts/schemas/internal-practice-scenario-request.schema.json
    - docs/units/u10/brief.md
  components:
    - u10.scenario-orchestration
    - u10.preparation-compatibility
    - u10.luna-boundary
    - u10.saved-goal-context
    - u10.graph-context
    - u10.contract-persistence
shallow:
  paths:
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
    - contracts/schemas/ai-goal-candidate-output.schema.json
    - contracts/schemas/ai-rejudgment-output.schema.json
    - contracts/schemas/practice-record-v2.schema.json
    - contracts/schemas/session-result-response.schema.json
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
    - contracts/schemas/ai-seed-output.schema.json
    - scripts/verify/a-checks.sh
    - backend/settings.gradle.kts
    - backend/build.gradle.kts
    - backend/gradle.properties
    - backend/build-logic/
    - backend/realtime-activity/build.gradle.kts
    - backend/test-support/build.gradle.kts
    - backend/contract-model/src/main/java/kr/mujoong/contract/ws/
    - backend/contract-model/src/main/java/kr/mujoong/contract/event/
    - backend/contract-model/src/main/java/kr/mujoong/contract/status/
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderHosts.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderRequestFactory.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderClient.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/DialogInstructions.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeRuntimeConfiguration.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/gateway/WebSocketConfig.java
    - backend/core-api/src/main/java/kr/mujoong/core/textai/TextAiConfiguration.java
    - backend/core-api/src/main/java/kr/mujoong/core/result/ResultTextConfiguration.java
    - backend/core-api/src/main/resources/application.yml
    - backend/realtime-activity/src/main/resources/application.yml
    - backend/realtime-activity/src/main/resources/application-prod.yml
    - backend/realtime-activity/src/main/resources/application-demo.yml
    - backend/core-api/src/main/resources/db/migration/core/
    - backend/realtime-activity/src/main/resources/db/migration/realtime/
    - frontend/package.json
    - frontend/next.config.ts
    - frontend/vitest.config.ts
    - frontend/eslint.config.mjs
    - frontend/src/app/
    - tools/package.json
    - .github/workflows/ci.yml
    - contracts/parent-api.yaml
    - contracts/device-api.yaml
    - contracts/internal-api.yaml
    - .prettierrc.json
    - backend/core-api/src/main/java/
    - backend/realtime-activity/src/main/java/
    - backend/contract-model/src/main/java/kr/mujoong/contract/
    - backend/test-support/src/main/
    - backend/core-api/src/test/
    - backend/realtime-activity/src/test/
    - backend/contract-model/src/test/
    - backend/test-support/src/test/
    - backend/core-api/src/main/resources/
    - backend/realtime-activity/src/main/resources/
    - backend/coverage-scope/
    - frontend/coverage-scope/
    - frontend/src/
    - frontend/tests/
    - tools/
    - infra/
    - scripts/
    - ci/
    - .github/workflows/deploy.yml
    - contracts/schemas/
    - contracts/device-ws.asyncapi.yaml
    - contracts/events.asyncapi.yaml
    - contracts/deviations.md
    - contracts/ai-io.md
    - contracts/timing.md
    - contracts/errors.md
    - contracts/ports.md
    - docs/
    - backend/core-api/src/test/java/kr/mujoong/core/luna/
    - backend/core-api/src/test/java/kr/mujoong/core/knowledge/
    - backend/core-api/src/test/java/kr/mujoong/core/practicegoal/
    - backend/contract-model/
    - contracts/knowledge-graph.md
```

