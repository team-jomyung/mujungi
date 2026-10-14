# 컴포넌트 목록 (Component Inventory)

> 기준 커밋 `7a22b67`. `###` 제목은 `reverse-engineering-timestamp.md` Scope of Analysis의 `analyzed.components`와 글자 그대로 맞춘다.
> 상태 등급: healthy(건강) · at-risk(주의) · degraded(손상). 깊게 본 컴포넌트만 등급을 매긴다.

## 깊게 본 컴포넌트

### build-logic
- **위치**: `backend/build-logic/` (convention plugin `mujoong.java-conventions`)
- **책임**: Java 21 툴체인 · Boot BOM · 보안 판 제약(Jackson · Tomcat) · 시험 태그 필터(`-PincludeTags/-PexcludeTags`) · JaCoCo LINE 80% 하한(test-support 제외) · `-PcoverageScope=<u>` · `verifyBootJarHasNoTestClasses`.
- **U6 관련**: `backend/coverage-scope/u6.txt`가 없으면 `-PcoverageScope=u6`이 실패한다. 기존 u1 ~ u5a 목록은 건드리지 않는다.
- **상태**: healthy

### contract-model
- **위치**: `backend/contract-model/` (깊게 본 곳: `build.gradle.kts`, `src/main/java/kr/mujoong/contract/event/`)
- **책임**: 두 앱 공용 계약 — 이벤트 봉투 · payload · `EventType` 6종 · `AggregateType`, 그 밖에 오류 코드 · 상태 열거 · WS 메시지 · 시계 · `JsonCodec`. Spring · JPA 없음.
- **의존**: 없음(라이브러리 루트). core-api · realtime-activity가 implementation, test-support가 api로 씀.
- **상태**: healthy

### core.record
- **위치**: `backend/core-api/src/main/java/kr/mujoong/core/record/`
- **책임**: 활동 기록 원장(`core.core_activity_record`, V3) 저장 · 조회. `SessionRecordCapturedHandler`(보호자 행 FOR SHARE → 사본 대조 → `snapshotHash` 재계산 → DECLINED 본문 비움 → `insertIfAbsent` 처음일 때만 `ActivityRecorded` 발행, 다른 해시는 APP_ERROR `EventConflict`). `RecordQueryService` · `RecordCalendar` · `web/RecordController`(26 · 27 재료). `ActivityRecordDeletionParticipant`(90).
- **데이터**: PK `session_id`(→ `core_activity` RESTRICT), guardian · child FK RESTRICT, `final_status`, `confirmed`, `transcript_json`(seq · speaker CHILD|AI · text · uncertain · startedAt · endedAt) · `gaps_json` · `practice_json`(nullable) · `audio_refs_json` · `termination_json` · `source_snapshot_id` · `snapshot_version` · `snapshot_hash`. `StoredRecord`는 audio · termination을 읽지 않는다. 목록은 FAILED 제외(`LISTED_STATUSES`).
- **U6 관련**: `ActivityRecorded` 운영 구독자 0 — ResultPipeline이 첫 구독자. 재발행이 없으므로 누락 복구 경로 필요.
- **상태**: at-risk (발행된 사건을 받는 쪽이 없고, 놓친 사건을 다시 낼 방법이 없음)

### core.practice
- **위치**: `backend/core-api/src/main/java/kr/mujoong/core/practice/`
- **책임**: 연습 목표 선택(`GoalSelectionService`, `/api/goal-selections`), 상황 생성(`ScenarioService` · `ScenarioRepository` · `ScenarioContentPolicy`), 목표 후보 등록(`CandidateRegistry.register` · `viewForSession`), 삭제 참가자(150).
- **확장점(기본 빈, `@ConditionalOnMissingBean`)**: `PracticePortConfiguration` — `CandidateEvidencePort` 늘 false, `GoalMetadataPort` null 메타. `ScenarioConfiguration` — `PracticeSessionContextPort` · `ScenarioRecordPort` SERVICE_NOT_READY.
- **U6 관련**: `CandidateEvidencePort` 실제 어댑터, `ScenarioLookup`(없음, 재료 `ScenarioRepository.find(UUID)` → `Stored` = Scenario + 소유 id), `ScenarioService.reconcileExposures(sessionId)`(운영 호출자 0, `ScenarioService.java:138`). `core_goal_recommendation.structured_experience_id uuid`(FK 없음, V4)는 U6 구조화 결과 자리로 보임.
- **상태**: at-risk (자리표시 근거 포트로 후보 등록이 늘 실패)

### core.knowledge
- **위치**: `backend/core-api/src/main/java/kr/mujoong/core/knowledge/`
- **책임**: 화용 지식 — `PragmaticsKnowledge.requireVersion(key, version)`(DRAFT · 미완성만 거절, RETIRED 허용) · `pinVersion`(FOR KEY SHARE + `used_at` 최초 기록) · `supportItems(behaviorKey)`(APPROVED 지원 문구 — 생활 팁 매칭 재료) · `relatedBehaviorKeys`(Neo4j 꺼짐이면 빈 목록). `BehaviorDefinition`에 `judgmentCriteria` · `behaviorDefinitionText`.
- **U6 관련**: `BehaviorDefinitionLookup` 없음 — U6가 공개 서비스 위에 읽기 포트로 더한다(다른 패키지가 저장소를 직접 읽지 않음).
- **상태**: healthy

### core.textai
- **위치**: `backend/core-api/src/main/java/kr/mujoong/core/textai/`
- **책임**: 텍스트 AI 게이트웨이(`TextAiGateway.generateScenario`), 공급자 SPI(`TextAiProvider.generateScenario` 한 메서드), 기본 UNAVAILABLE(`TextAiConfiguration`), 설정 `mujoong.text-ai.*`(`TextAiProperties`), 금지 표현 정책(`ForbiddenExpressionPolicy`, 사전 `resources/textai/u5a-forbidden-patterns.txt` — `//` 범주 주석 + 줄 패턴 18줄, `appliesTo` 없음).
- **U6 관련**: 구조화 · 재판정 · 결과 문장 · 리포트 · 금지 2차 분류 입출력 없음. ai-io.md 6.6 사전(JSON · `appliesTo` RESULT_TEXT · PDF · 7범주)과 형식 불일치. ai-io.md 6.1의 `mujoong.ai.*` 표기와 코드 키 불일치. `CandidateRegistry`가 같은 정책을 쓰므로 패턴 추가는 U5a 동작도 바꾼다.
- **상태**: at-risk (U6 요구 대비 표면이 좁고 사전 · 키 이름이 계약과 어긋남)

### core.common.deletion
- **위치**: `backend/core-api/src/main/java/kr/mujoong/core/common/deletion/DeletionParticipant.java`
- **책임**: 계정 삭제 참가자 순서 상수 — ACTIVITY_RECORD 90 · ACTIVITY 100 · PRACTICE 150 · DEVICE 200 · CONSENT 250 · CHILD_PROFILE 300 · MESSAGING 900. 참가자는 `@Order` 빈 · 멱등 · 한 트랜잭션 · `DbRetry IDEMPOTENT`.
- **U6 관련**: `ORDER_RESULT = 70` 없음. 상수를 더할 때 Javadoc "쓰는 값" 목록과 `DeletionCommander` 주석 순서 설명도 같이 고쳐야 한다.
- **상태**: healthy

### realtime.result-snapshot
- **위치**: `backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/ResultSnapshotBuilder.java` · `PracticeSessionExtension.java`, `db/migration/realtime/`
- **책임**: 종료 시 결과 입력 스냅숏 생성(utterances: FINAL · deleted_at 없음 · 아이 전사 + 승인 AI 말, gaps, recordScope, practice, audio) → `realtime.realtime_result_input_snapshot`(V3) → `SessionRecordCaptured`. DECLINED · ACTIVE 전 종료는 utterances · audio 빈 목록.
- **U6 관련**: 연습 확장점이 `NoOpPracticeSessionExtension`이라 PRACTICE의 `practice`는 U5b 전까지 null(편차 U4b-d). U6는 realtime 스키마를 바꾸지 않는다.
- **상태**: healthy (U4b 설계대로; practice null은 의도된 공백)

### contracts
- **위치**: `contracts/` (깊게 본 곳: `ai-io.md` · `ports.md` · `timing.md` · `deviations.md` · `parent-api.yaml`)
- **책임**: 계약 정본. `timing.md`는 `ConfigKeyContractTest`가 `application.yml` · `CoreProperties`와 대조한다.
- **U6 관련**: `parent-api.yaml`에 C5 없음. `timing.md` 2절 예약표에만 `mujoong.timing.result.job-attempts` · `mujoong.timing.report.ready-target`('읽을 유닛 U6')이 있고 yml · `CoreProperties.Timing`에는 없다. `record.practice` 내부 모양은 파일에 없다. 편차는 `deviations.md`에 `(U6-a)`부터.
- **상태**: at-risk (U6 계약 공백)

### infra.migrate
- **위치**: `infra/migrate/`(Dockerfile · `flyway-core.conf` · `flyway-realtime.conf` · `migrate.sh`), `infra/migration-approvals.txt`, `infra/compose.demo.yml`
- **책임**: Flyway 적용 컨테이너(`baselineOnMigrate=true` · `baselineVersion=0` · `validateMigrationNaming=true` · `outOfOrder` 기본 false). 앱은 `SchemaVersionCheck`로 클래스패스 최고 버전 이상인지 확인만. 파괴 문장은 CI `infra-validate`가 막고 승인 목록 줄(위치 · 문장 번호 · 해시 12자 · 승인자 · 사유)로만 통과.
- **U6 관련**: V5는 새 표만 더하면 승인 불필요. 기존 표 FK · 제약 강화는 승인 줄 필요. V5 파일과 migrate 이미지가 함께 배포돼야 함. compose.demo의 core-api는 `read_only: true` · `/tmp` tmpfs 64m · `mem_limit 1.6g` · 영속 볼륨 없음 → PDF 저장 자리 없음.
- **상태**: healthy

### ci-workflow
- **위치**: `.github/workflows/ci.yml`
- **책임**: changes · backend(`./gradlew clean check` + bootJar 시험 클래스 0) · backend-deps(Trivy HIGH+) · frontend(eslint · vitest 80% · next build · 번들 비밀 검사) · frontend-deps(npm audit) · tools · secrets(gitleaks) · infra-validate(Compose · 불변식 · `nginx -t` · shellcheck · 마이그레이션 파괴 문장) · images(6종 빌드 · trivy · 점검 · 관문 음성 대조) · skeleton(`scripts/verify.sh --mode compose`) · ci-gate · deploy.
- **U6 관련**: CI는 범위 없는 모듈 전체 80%도 돈다 → U6 코드는 u6 범위 80%와 core-api · frontend 전체 80%를 함께 지켜야 한다.
- **상태**: healthy

### frontend.parent.records
- **위치**: `frontend/src/app/parent/records/page.tsx` (+ `frontend/package.json` · `vitest.config.ts` · `coverage-scope/`)
- **책임**: 26 기록 화면 자리 — 현재 `/parent`로 redirect만. `/api/records` 클라이언트 · 타입 없음. `Done17.tsx`가 「오늘 기록 보기」 감춤(`tests/u1/parent/done-17.test.tsx:109` J120 단언).
- **U6 관련**: 26 · 27 · 28 · 30 · R-PDF와 17 정리 상태를 새로 만든다. `frontend/coverage-scope/u6.json` 없음(현재 u1 · u2 · u3 · u4a · u5a).
- **상태**: degraded (화면 없음 · 스텁)

## 훑기만 한 컴포넌트 (등급 없음)

| 컴포넌트 | 위치 | 한 줄 |
|---|---|---|
| core.messaging | `core/messaging/` | Core Outbox/Inbox(적재 · 전송 · 수신 · 재폴링 · 정리), 삭제 참가자 900 |
| core.activity | `core/activity/` | 활동 시작 · 종료 · 보기 · 사본 · 준비 시한("ActivityCoordinator" 로그 컴포넌트). `ActivityCopy`에 ACTIVE 도달 칸 없음, `startedAt`이 대용 |
| core 기타 | `core/{account,catalog,child,consent,device,identity,security,internalhttp,health,observability,startup,config,web,common/db}/` | U1 · U2 · U3 기능 · 공통 |
| realtime 기타 | `backend/realtime-activity/src/main/java/` | 세션 실행기 · 대화 · 게이트웨이 · 음성 · 게이트 · 전사 · 원음 · 메시징 등(U4b 영역) |
| test-support | `backend/test-support/` | PostgreSQL 시험 백엔드 · 가변 시계 · 계약 대역 · 가짜 공급자 WS · 로그 수집 |
| tools | `tools/` | 가짜 공급자 · 기기 시뮬레이터 · skeleton-e2e · seed |
| 알림(U7) | — | 코드 없음 |

## Sources

- 개발자 스캔 `.../developer-scan.md`(Packages Found · Technical Debt Signals · U6 관련 코드 위치 · Risks)
