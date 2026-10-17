> 최신 정본: 아래 U10 갱신절(2026-10-17, HEAD eb34c68bf24e4520881b7ffebe1070564554dfc9). 이후 U14/U15/전체 분석 본문은 역사 자료이며, 이번에 검증한 범위는 timestamp의 analyzed 목록만이다.

## U10 집중 갱신 — 실제 호출 의존

- ScenarioService → ScenarioRepository, PracticeSessionContextPort, ScenarioRecordPort, PracticeAccountPort, PragmaticsKnowledge, TextAiGateway, clock, tx, limits.
- PracticeStartService → 기존 practice.PracticeGoalRepository, PragmaticsKnowledge, 준비/상황 저장소, ScenarioService, StartActivityService. 새 core_saved_goal을 직접 읽지 않는다.
- TextAiGateway → TextAiProvider, ForbiddenExpressionPolicy, 실행기, 시간/재시도 설정.
- LunaTextAiProvider → CoreLunaClient → sanitizer/schemas/transport/eventlogger/clock. 기존 어댑터는 legacy 모드라 안쪽 재시도가 없다.
- PinnedSavedGoalReader → GoalCandidateRepository, PracticeAccountPort, ConsentPolicyService, JDBC. U10은 제공 포트로 읽고 다른 단위 표를 임의 복제하지 않는다.
- ChildKnowledgeService → 원장·접근정책·graph port·VariantPlanner. variantPlan은 skillContext 재조회, 조회는 budget 기반 Future다.

LunaCallBudget는 총3시도와 기술1/내용1슬롯을 공유한다. 생성→검토→재생성→재검토 정상4호출에 그대로 적용하면 마지막 검토가 막힌다. U15 동작·시험을 바꾸지 않는 U10 예산 설계가 필요하다. 전체 무순환/최신의존 취약점을 이번 읽기 작업에서 재검증한 것은 아니다.

---

> 현재 범위의 정본은 바로 아래 U14 갱신절이다. 그 뒤 원문은 보존 이력이며 재검증 범위는 timestamp에 한정한다.

## U14 집중 갱신 — 실제 의존

SessionResultHandler → ResultTextGateway, OutputChecker, CandidateScreen, CandidateRegistry, 결과/장면 저장소. CandidateRegistry → PracticeGoalRepository, PracticeAccountPort, CandidateEvidencePort, PragmaticsKnowledge. ReportAssembler → SceneChipMapper. U14 소유 이전 시 기존 import·서비스 생성자·시험 대역을 깨지 않게 경계를 제공해야 한다.

U15 SeedGenerationService → ChildKnowledgeService + Optional<CoreLunaClient> + SeedContentValidator이며 기능 비활성·키 없음은 정상 NONE이다. 후보 확정 이후 별도 실패 경계에서 호출하고 결과 상태와 결합하지 않는다. 후보 참조·버전과 활동 id를 안정적으로 묶어 재처리 시 중복 생성되지 않아야 한다.

GOAL_CANDIDATES/REJUDGMENT/RESULT_TEXT의 JOB_ATTEMPT 정책은 작업 수준에서 재시도한다. 안쪽 재시도를 더하지 않는다. 이전 전체 의존 목록은 이번에 전체 재확인한 것이 아니다.

---

# 의존 관계 (Dependencies)

> 최신 확인: 2026-10-16, HEAD 7e30ee8, U15 집중 분석. 아래 최신 절이 U15 관련 과거 설명보다 우선한다. 이전 분석 본문은 이력으로 보존하며 이번 범위 밖의 검증 주장은 shallow로 낮췄다.

## U15 의존과 선택적 구성

core-api → contract-model(운영), test-support(시험). PragmaticsKnowledge → JDBC + clock + properties + optional graph. CoreLunaClient → sanitizer + schemas + transport + event logger + clock + JsonCodec. DeletionCommander → ordered participants + TransactionTemplate.

OPENAI_API_KEY 없는 기본 CI에서 CoreLunaClient는 미구성이다. 생성 서비스는 이 경로를 지원해야 한다. Neo4j 사용 시 기존 NEO4J_AUTH 전달 배선을 core에도 연결해야 하며 새 비밀 이름을 만들지 않는다. 전체 저장소 무순환을 검증한 것은 아니다.

## 이전 분석 기록 — 2026-10-15, 현재 범위 밖은 재검증하지 않음

> 기준: 커밋 `72d7034`, 2026-10-15 전체 재스캔. 판 번호는 [technology-stack.md](technology-stack.md), 엔드포인트는 [api-documentation.md](api-documentation.md)가 정본이다.

## 1. 내부 모듈 의존 (빌드)

```mermaid
flowchart TD
    BL["build-logic<br/>mujoong.java-conventions"]
    CM["contract-model"]
    TS["test-support"]
    CA["core-api"]
    RA["realtime-activity"]
    BL -.->|적용| CM
    BL -.->|적용| TS
    BL -.->|적용| CA
    BL -.->|적용| RA
    %% 점선은 규약 플러그인 적용, 실선은 Gradle 의존
    CA -->|implementation| CM
    RA -->|implementation| CM
    TS -->|api| CM
    CA -->|testImplementation| TS
    RA -->|testImplementation| TS
```

텍스트 대안: `core-api`와 `realtime-activity`는 `contract-model`을 implementation으로, `test-support`를 testImplementation으로 쓴다. `test-support`는 `contract-model`을 api로 노출한다. `build-logic` 규약 플러그인은 네 모듈 모두에 적용된다. `core-api`와 `realtime-activity` 사이에는 빌드 의존이 없다(ArchUnit · CI 불변식이 막음).

## 2. 런타임 의존 (앱 사이)

| 방향 | 수단 | 내용 |
|---|---|---|
| core-api → realtime-activity | 내부 HTTP | 세션 시작 · 종료 · 뷰 조회 |
| realtime-activity → core-api | 내부 HTTP | 기기 토큰 검증 · 연결 보고 · 연습 상황 조회 |
| 양방향 | Outbox/Inbox(HTTP `/internal/events`) | 이벤트 6종 |
| 두 앱 → PostgreSQL | JDBC | 같은 인스턴스, 스키마 · 역할 분리(`core`/core_app, `realtime`/realtime_app) |
| migrate 이미지 → PostgreSQL | Flyway | `infra/migrate/flyway-{core,realtime}.conf` |
| frontend → 두 앱 | HTTPS(Nginx 경유) | `/parent` → core REST, `/device` → core 기기 REST + realtime WS |

## 3. 외부 의존 (런타임 서비스)

| 의존 | 사용 앱 | 접점 | 비고 |
|---|---|---|---|
| 음성 AI 공급자 | realtime-activity | `voice.ProviderClient`, 호스트 고정 `api.openai.com` | 선 형식 자체 정의(TD-2) |
| 텍스트 AI | core-api | `textai.TextAiProvider`, `result.ResultTextProvider` | 운영 어댑터 없음(TD-1) |
| 연습 판정 · 단서 공급자 | realtime-activity | `EvaluationProvider`, `CueProvider` | 기본 미연결(TD-1) |
| Google OAuth | core-api | `identity.Google*` | |
| Web Push 서비스 | core-api | push2u, `notification.VapidPushSender` | 공개 DNS 정책 · 고정 HTTP 클라이언트 |
| Neo4j | core-api | `knowledge.KnowledgeGraphLookup` | 꺼짐, 어댑터 없음(TD-3) |

## 4. 외부 라이브러리 의존 (모듈별 주요)

| 모듈 | 주요 의존 |
|---|---|
| contract-model | Jackson 3(직접 import는 `kr.mujoong.contract.json` 한 곳). Spring · JPA 없음 |
| core-api | Spring Boot(webmvc · security · session-jdbc · data-jpa · jdbc · flyway · actuator · validation), PostgreSQL JDBC, Micrometer Prometheus, springdoc(dev · test), openhtmltopdf-pdfbox, push2u-core, HttpClient5 |
| realtime-activity | Spring Boot(webmvc · websocket · data-jpa · jdbc · flyway · actuator · validation), PostgreSQL JDBC, Micrometer Prometheus, JDK WebSocket |
| test-support | Testcontainers PostgreSQL, Java-WebSocket, JUnit |
| 백엔드 시험 공통 | JUnit 5 · AssertJ · Mockito · ArchUnit · json-schema-validator · JaCoCo |
| frontend | Next.js · React · react-query, 시험 Vitest · Testing Library · jsdom, 린트 ESLint |
| tools | ws, Vitest |

보안 판 고정: `build-logic`이 Jackson 2/3 BOM과 Tomcat embed를 상향 고정한다. 취약점 예외는 `ci/security-exceptions.yml`(만료 검사 `ci/security-gate.py`).

## 5. 변경 연쇄 (함께 바뀌어야 하는 것)

| 바꾸는 것 | 함께 바꿀 것 |
|---|---|
| `contracts/` 계약 | `contract-model` 타입 · `contracts/deviations.md` · 계약 시험(ContractFilesTest 등) · 프런트 `shared/protocol` 파서 |
| 음성 공급자 선 형식 | `realtime/voice/*` · `tools/fake-provider/` · `backend/test-support/.../provider/` |
| 시간 값 | 두 앱 `application.yml` · `contracts/timing.md` · `frontend/src/shared/timing/timing-defaults.json` |
| DB 스키마 | 다음 번호(core V8 · realtime V5) · 제약 강화 시 `infra/migration-approvals.txt` |
| 파일 추가 · 이동 | `backend/coverage-scope/*.txt` · `frontend/coverage-scope/*.json` · 유닛 시험 태그 |


