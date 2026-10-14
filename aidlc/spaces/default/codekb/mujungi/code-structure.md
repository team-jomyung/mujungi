# 코드 구조 (Code Structure)

> 기준 커밋 `7a22b67`. 컴포넌트 책임은 `component-inventory.md`, 빌드 의존은 `dependencies.md`에 있다.

## 최상위 배치

| 경로 | 종류 | 내용 |
|---|---|---|
| `backend/` | Gradle 멀티 프로젝트 `mujoong-backend` | `build-logic`(포함 빌드) · `contract-model` · `core-api` · `realtime-activity` · `test-support` · `coverage-scope/` |
| `frontend/` | npm `mujoong-frontend` (Next.js App Router) | `src/app` · `src/parent` · `src/device` · `src/shared` · `src/proxy.ts`, `tests/{u1,u2,u3,u4a,u5a}`, `coverage-scope/` |
| `tools/` | npm `mujoong-tools` (ESM) | 가짜 공급자 · 기기 시뮬레이터 · skeleton-e2e · dev-edge · seed |
| `contracts/` | 계약 정본 | `parent-api.yaml` · `internal-api.yaml` · `device-api.yaml` · `device-ws.asyncapi.yaml` · `events.asyncapi.yaml` · `schemas/`(46개) · `ai-io.md` · `ports.md` · `timing.md` · `errors.md` · `deviations.md` |
| `infra/` | 배포 · 검증 | `compose.{demo,dev,ci}.yml` · `migrate/` · `nginx/` · `postgres/` · `seed/` · `seed-audio/` · `bin/` · `host/` · `aws/` · `backup/` · `bundle/` · `ci/` · `migration-approvals.txt` · `RUNBOOK.md` |
| `scripts/` | bash | `verify.sh`(정적 · 단위 · 통합 → 기동 → 기기 시뮬레이터 한 턴) · `cloud-setup.sh` |
| `ci/` | Python | `security-gate.py` · 취약점 예외 목록 |
| `docs/` | Markdown | `units/u2 ~ u6/{brief,design,report}.md`, ERD, 변경 이력 |
| `.github/workflows/` | CI | `ci.yml` · `deploy.yml` |

## core-api 패키지 (`kr.mujoong.core`)

운영 소스 약 19.4천 줄 · 178개 파일. **굵게** 표시한 것이 이번에 깊게 본 패키지다.

| 패키지 | 주요 파일 |
|---|---|
| **`record`** (U4a) | `ActivityRecorded` · `ActivityRecordRepository` · `RecordCalendar` · `RecordQueryService` · `SessionRecordCapturedHandler` · `ActivityRecordDeletionParticipant` · `web/RecordController` |
| **`practice`** (U5a) | `CandidateRegistry` · `CandidateEvidencePort` · `GoalMetadataPort` · `PracticeSessionContextPort` · `ScenarioRecordPort` · `PracticePortConfiguration` · `ScenarioConfiguration` · `ScenarioService` · `ScenarioRepository` · `Scenario` · `ScenarioContentPolicy` · `GoalSelection(Service)` · `PracticeGoalRepository` · `PracticeAccountPort` · `U2PracticeAccountAdapter` · `PracticeDeletionParticipant` · `web/GoalSelectionController` |
| **`knowledge`** (U5a) | `PragmaticsKnowledge` · `BehaviorDefinition` · `SupportItem` · `KnowledgeGraphLookup` · `KnowledgeJdbcRepository` · `KnowledgeProperties` |
| **`textai`** (U5a) | `TextAiGateway` · `TextAiProvider` · `TextAiConfiguration` · `TextAiProperties` · `TextAiException` · `ForbiddenExpressionPolicy` · `ScenarioPrompt` · `GeneratedScenario` |
| **`common/deletion`** | `DeletionParticipant`(순서 상수) |
| `messaging` | `CoreOutboxWriter/Sender/Repository` · `CoreInboxReceiver/Dispatcher/Repoller/Repository` · `ControlEventRearm` · `MessagingCleanupJob` · `CoreMessagingDeletionParticipant` · `handler/` · `web/` |
| `activity` | `StartActivityService` · `EndSessionService` · `SessionViewService` · `ActivityCopyRepository` · `CurrentSessionService` · `PreparingTimeoutChecker` … ("ActivityCoordinator"는 클래스가 아니라 이 패키지와 로그 컴포넌트 이름) |
| `account` · `catalog` · `child` · `consent` · `device` · `identity` · `security` · `internalhttp` · `health` · `observability` · `startup` · `config` · `web` · `common/db` | 훑기만 함(파일 목록 · 필요한 서명) |

리소스: `application.yml`, `db/migration/core/`(V1 base · V2 U2 · V3 U4a · V4 U5a · `afterMigrate.sql`), `textai/u5a-forbidden-patterns.txt`. 시험 전용 `src/test/resources/db/testmigration/core/V90__test_default_privilege_probe.sql`.

## realtime-activity (`kr.mujoong.realtime`)

운영 소스 약 24천 줄. 이번에는 `session/ResultSnapshotBuilder.java` · `session/PracticeSessionExtension.java`(+ `NoOpPracticeSessionExtension`)와 `db/migration/realtime/`(V1 · V2 U3 · V3 U4b · `afterMigrate.sql`)만 깊게 봤다. 나머지 패키지는 목록만 훑었다(U6 무관). `session/SessionExecutor.java` 3167줄.

## contract-model (`kr.mujoong.contract`)

`event/`(`EventType` · `EventEnvelope` · `EventPayload` · `AggregateType` · `payload/`)를 깊게 봤다. 그 밖에 오류 코드 · 상태 열거 · `ws/message/`(18종) · 시계 · `JsonCodec` · 로그.

## frontend

- `src/app/parent/records/page.tsx` — `/parent`로 redirect만 하는 스텁(26).
- `/api/records` 클라이언트 · 타입 없음. `Done17.tsx`는 「오늘 기록 보기」를 감춤(문구 `shared/copy/activity.ts:34 viewRecord`는 있음).
- 화면 문구는 copy 모듈에만(`react/jsx-no-literals`), `/parent` ↔ `/device` import 금지(ESLint).

## 코드 패턴

- **포트와 교체 가능한 기본 빈**: 소비 유닛이 포트를 정의하고 `@ConditionalOnMissingBean` 기본 구현(거절 · null · SERVICE_NOT_READY · UNAVAILABLE)을 둔다.
- **Spring 앱 이벤트 + 커밋 뒤 전달**: `ActivityRecorded`, 구독은 `@TransactionalEventListener(AFTER_COMMIT)` 전제.
- **Outbox/Inbox**: 앱 사이 이벤트는 같은 트랜잭션에서 Outbox 적재 → 내부 HTTP 전달 → Inbox 멱등 처리 · 재시도 · DEAD.
- **삭제 참가자**: `@Order` 빈 · 멱등 · 한 트랜잭션 · `DbRetry IDEMPOTENT`(본보기 `ActivityRecordDeletionParticipant`).
- **응답 모양으로 노출 금지**: 서비스가 내부 필드를 응답 레코드에 싣지 않음 + `ContractConformanceIT` 스키마 대조.
- **주석 형식**: 대부분 한국어 Javadoc에 근거 ID(FR · BR · J · AC). U5a 코드(`knowledge` · `practice` · `textai`)는 영어 한 줄 압축 주석.

## Sources

- 개발자 스캔 `.../developer-scan.md`(Packages Found · Build System · U6 관련 코드 위치)
- 패키지 파일 목록 재확인(`ls`, 커밋 `7a22b67`)
