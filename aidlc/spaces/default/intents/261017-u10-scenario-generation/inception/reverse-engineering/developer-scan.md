## Developer Code Scan Results

### Scan Coverage
- **Analyzed deeply**: 아래38개 실제 파일. snapshot bounds 안에서만 읽었다.
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
- **Skimmed only**: contracts/ai-io.md, timing.md, internal-api.yaml, knowledge-graph.md의 관련절 검색; core-api의 기존 Luna/u14/u15 시험 선언; backend/coverage-scope 목록; 이전 CodeKB가 기록한 나머지 영역(재검증하지 않음).
- **Left out**: backend/**/build/, backend/.gradle/, frontend/.next/, node_modules/, .git/, IDE캐시, AI-DLC 설치·도구 소스. realtime-activity/와 frontend/ 구현 본문은 이번 분석에서 열지 않았다.

### Packages Found
- core.practice — Java 서비스·모델·저장소·내부 HTTP: 요청 선점, 대본 생성·발급·노출.
- core.practice.start — Java 준비 유스케이스·모델·매퍼: 목표 순서 동결과 첫대본 C8 전달.
- core.textai / core.luna — Java 공급자 포트·어댑터·게이트웨이·검사: 요청 최소화와 제한호출.
- core.practicegoal — Java U14 서비스 경계: 버전 고정 목표 읽기.
- core.knowledge — Java U15 서비스·모델: child별 맥락과 한요소 변형.
- contracts — JSON Schema/Markdown/OpenAPI 계약 정본.

### Build System
- **Type**: Gradle Kotlin DSL 멀티모듈(기존 CodeKB); 이번 core-api/build.gradle.kts 확인.
- **Config Files**: core-api/build.gradle.kts; processResources는 contracts/schemas의 ai-* 및 common을 복사.
- **Build Dependencies**: core-api→contract-model(운영), test-support(시험), JSON Schema validator(운영). 버전 카탈로그는 재검증하지 않음.

### APIs Discovered
- POST /internal/practice/scenarios — ScenarioInternalController:22, 내부토큰보호, request4필드, 응답기존Scenario8필드.
- C-DS10 SavedGoalReader.find(childId,selectionId) — CANDIDATE/LEGACY_BEHAVIOR.
- C-DS11 실제제공자 ChildKnowledgeService.skillContext:158 / variantPlan:165. 기존 KnowledgeGraphLookup은 공개행동키전용.

### Frameworks & Libraries
- Java record·Spring 서비스/JDBC·TransactionTemplate·JDK Future/가상스레드·JsonCodec·MujoongClock.
- 정확한 판 번호는 이전 CodeKB의 역사 관찰을 보존, 이번 새 검증으로 주장하지 않음.

### Test Coverage
- **Test Directories**: core-api/src/test/java/kr/mujoong/core/{practice,luna,practicegoal,knowledge}, contract-model/src/test(이전 문서/검색수준).
- **Test Frameworks**: JUnit5·AssertJ·Mockito 및 실제PG용 DbTestSupport(ScenarioServiceTest 본문 확인).
- **Coverage Config**: 기존 u5a/u9/u14/u15 목록 존재, u10.txt 없음. U10 ≥80%는 지시서 요구이며 아직 구현·측정되지 않음.
- 이번 읽기 단계에서 테스트를 돌리지 않았으므로 U10 pass/coverage 수치는 없음.

### Code Quality Indicators
- **Linting**: 기존 CodeKB의 javac/ArchUnit·프런트ESLint 관찰을 보존, 구성을 새로 열지 않음.
- **CI/CD**: 기존 문서 보존; 실제CI결과는 별도 지휘자 증거. 분석 결과 자체가 빌드통과 증거는 아님.
- **Documentation**: docs/units/u10/brief.md가 정본, 계약·판·편차 단일정본 규칙 존재.
- 명명/권한·lease 재확인 양호; 기존 생성/준비 책임 결합과 압축서식은 보통. 무관한 리팩터링 제안 없음.

### Technical Debt Signals
1. ScenarioService:67은 여전히 behaviorKey/version 고정. SavedGoalReader 미연결.
2. PracticeStartService:90의 legacy repository.find와 FrozenGoal 필수behaviorKey/version 때문에 새 core_saved_goal 선택은 실제시작 입구에서 막힌다.
3. PreparedPracticePackageMapper:28은 기존8필드만 복사. 선택scenes 등을 Scenario에만 추가하면 시작C8 첫대본에서 유실된다.
4. TextAiGateway 기술재시도 + CoreLunaClient:128~132 CONTRACT 재시도를 중첩하면 요청예산 위반. LunaCallBudget:17은 총3이라 생성/검토/재생성/재검토4호출에 그대로 공유 불가. U15의 기존 제한/단언을 바꾸지 않는 설계가 필요하다.
5. ChildKnowledgeService:167 variantPlan은 skillContext 재조회. 각각2초를 쓰는 순차조회는 전체2초와 다르므로 전체마감/회수 전략을 명시해야 한다.
6. ScenarioContentPolicy는 관계/기회/trigger 문자열만 검사. 새장면 완전성·정답/조기단서/변형보존 독립검사는 아직 없다.
7. scenario/review AI스키마는 있으나 실제응답4선택필드·V10이 없다. V4의 behavior_key/version NOT NULL도 새목표 보존 방식과 맞춰야 한다(기존마이그레이션 수정금지).
8. LunaTextAiProvider는 interests를 안보내고 sanitizer가 금지한다. 지시대로 Functional Design 사람질문을 유지하며 임의결정하지 않는다.
9. ScenarioService:137 및 ScenarioServiceTest는 UNAVAILABLE/TIMEOUT→SERVICE_NOT_READY를 고정한다. U10 최종생성실패SCENARIO_GEN_FAILED와 레거시경로의 호환 해석을 문서화해야 한다. 기존단언 수정/약화 금지.

## Handoff Summary
- **Intent-relevant finding**: U10은 새대본 스키마만의 변경이 아니다. 새목표 준비입구→생성→별도검토→서버검사→영속→C8/C-DS1의 전달경로를 연결하되 U5a 선점/lease/권한/중복방지를 유지해야 한다.
- **Risks / follow-up**: 위9항목을 요구사항·설계에 반영. 관심사 정책은 Functional Design 사람결정, 검토reason enum은 J7정본과 함께확정. 가짜공급자시험과 실호출15초증거를 분리하고 실호출 전에 RUNBOOK R-F15확인을 별도로 받아야 한다. 유료호출·비밀읽기·동료메시지 없음.
- 후보9문서는 .aidlc-engine/codekb-stage-mujungi/, 비교범위초안은 inception/reverse-engineering/scope-draft-mujungi.md. 기존 외부본문 보존, STALE deep강등, 이번38파일 fingerprint mint 완료. 지휘자가 compare/check/publish와 lifecycle/link receipt를 소유한다.

