# 의존 관계 (Dependencies)

> 기준(혼합): **이번 갱신** = 커밋 `9bcc706`(= main `7e30ee8` + U11 지시서), 2026-10-16 집중 스캔(인텐트 `261016-u11-judgment-turn`). 갱신 · 확장한 곳은 3 · 4절의 realtime 행, 새 6절(realtime 안 패키지 의존 · 구조 제약), 5절 변경 연쇄의 표시 행이다.
> **그 밖의 문장** = 커밋 `72d7034`(2026-10-15 전체 재스캔) 그대로다(STALE). 판 번호는 [technology-stack.md](technology-stack.md), 엔드포인트는 [api-documentation.md](api-documentation.md)가 정본이다.

## 1. 내부 모듈 의존 (빌드)

```mermaid
flowchart TD
    BL["build-logic<br/>mujoong.java-conventions"]
    CM["contract-model"]
    TS["test-support"]
    CA["core-api"]
    RA["realtime-activity"]
    CS["contracts/schemas<br/>ai-*.schema.json"]
    BL -.->|적용| CM
    BL -.->|적용| TS
    BL -.->|적용| CA
    BL -.->|적용| RA
    %% 점선은 규약 플러그인 적용, 실선은 Gradle 의존, 굵은 화살표는 자원 복사
    CA -->|implementation| CM
    RA -->|implementation| CM
    TS -->|api| CM
    CA -->|testImplementation| TS
    RA -->|testImplementation| TS
    CS ==>|"processResources 복사 luna/schemas"| RA
```

텍스트 대안: `core-api`와 `realtime-activity`는 `contract-model`을 implementation으로, `test-support`를 testImplementation으로 쓴다. `test-support`는 `contract-model`을 api로 노출한다. `build-logic` 규약 플러그인은 네 모듈 모두에 적용된다. `core-api`와 `realtime-activity` 사이에는 빌드 의존이 없다(ArchUnit · CI 불변식이 막음). (2026-10-16 갱신) `realtime-activity`의 `processResources`가 `contracts/schemas/`의 `ai-*.schema.json`과 `common.schema.json`을 실행 자원 `luna/schemas/`로 복사하므로, 판정 스키마를 고치면 realtime 실행 자원도 함께 바뀐다.

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
| 음성 AI 공급자 | realtime-activity | `voice.ProviderClient`, 호스트 고정 `api.openai.com` | 선 형식 자체 정의(TD-2), 응답 생성 확인 사건 없음(U11-R1) |
| (2026-10-16 갱신) Luna Responses API | realtime-activity | `luna.RealtimeLunaClient` → `LunaTransport`(JDK `HttpClient`) | 키 `OPENAI_API_KEY` 있을 때만 빈, demo · prod는 `api.openai.com`만(`LunaHosts`). 판정 턴 소비자 없음 |
| 텍스트 AI | core-api | `textai.TextAiProvider`, `result.ResultTextProvider` | 72d7034 기준 운영 어댑터 없음(TD-1). U9 `CoreLunaClient` 미재확인 |
| 연습 판정 · 단서 공급자(레거시) | realtime-activity | `EvaluationProvider`, `CueProvider` | 기본 미연결(TD-1, 2026-10-16 재확인) |
| Google OAuth | core-api | `identity.Google*` | |
| Web Push 서비스 | core-api | push2u, `notification.VapidPushSender` | 공개 DNS 정책 · 고정 HTTP 클라이언트 |
| Neo4j | core-api | `knowledge.KnowledgeGraphLookup` | 꺼짐, 어댑터 없음(TD-3) |

## 4. 외부 라이브러리 의존 (모듈별 주요)

| 모듈 | 주요 의존 |
|---|---|
| contract-model | Jackson 3(직접 import는 `kr.mujoong.contract.json` 한 곳). Spring · JPA 없음 |
| core-api | Spring Boot(webmvc · security · session-jdbc · data-jpa · jdbc · flyway · actuator · validation), PostgreSQL JDBC, Micrometer Prometheus, springdoc(dev · test), openhtmltopdf-pdfbox, push2u-core, HttpClient5 |
| realtime-activity | (2026-10-16 갱신) Spring Boot(webmvc · websocket · data-jpa · jdbc · flyway · actuator · validation), flyway-database-postgresql, PostgreSQL JDBC, Micrometer Prometheus, **json-schema-validator(실행 범위)**, JDK WebSocket · HttpClient. 시험: jackson-dataformat-yaml |
| test-support | Testcontainers PostgreSQL, Java-WebSocket, JUnit |
| 백엔드 시험 공통 | JUnit 5 · AssertJ · Mockito · ArchUnit · json-schema-validator · JaCoCo |
| frontend | Next.js · React · react-query, 시험 Vitest · Testing Library · jsdom, 린트 ESLint |
| tools | ws, Vitest |

보안 판 고정: `build-logic`이 Jackson 2/3 BOM과 Tomcat embed를 상향 고정한다. 취약점 예외는 `ci/security-exceptions.yml`(만료 검사 `ci/security-gate.py`).

## 5. 변경 연쇄 (함께 바뀌어야 하는 것)

| 바꾸는 것 | 함께 바꿀 것 |
|---|---|
| `contracts/` 계약 | `contract-model` 타입 · `contracts/deviations.md` · 계약 시험(ContractFilesTest 등) · 프런트 `shared/protocol` 파서 |
| 음성 공급자 선 형식 | `realtime/voice/*` · `tools/fake-provider/` · `backend/test-support/.../provider/` · realtime 시험 `testing/FakeProviderPort` |
| 시간 값 | 두 앱 `application.yml` · `contracts/timing.md` · `frontend/src/shared/timing/timing-defaults.json`. (2026-10-16 갱신) 서버 전용 키(timing.md 13절 `judgment.*` 등)는 timing.md · 소유 앱 `application.yml` · 설정 레코드(`RealtimeProperties`) 세 곳(편차 U8-c) |
| DB 스키마 | 다음 번호(core V8 · realtime V5) · 제약 강화 시 `infra/migration-approvals.txt`. (2026-10-16 갱신) realtime V5는 `RealtimeSchemaIT`의 표 목록 · 최신 판 "4" 단언과 `PracticeMigrationRegistrationTest`의 `expectedVersion()` == "4" 단언에 닿는다(U11-R4) |
| 파일 추가 · 이동 | `backend/coverage-scope/*.txt` · `frontend/coverage-scope/*.json` · 유닛 시험 태그. (2026-10-16 확인) `u11.txt` · `@Tag("u11")`은 아직 없다 |
| (2026-10-16 갱신) 판정 스키마 `contracts/schemas/ai-judgment-*.schema.json` | realtime 실행 자원(빌드 복사) · `LunaSchemas` 검증 · 계약 시험 |
| (2026-10-16 갱신) `mujoong.*` 새 설정 키 | 같은 앱 설정 레코드(루트 `RealtimeProperties`에 한 칸) · `ConfigKeyContractTest` 대조. 별도 설정 클래스로 두면 시험 바인딩 수정이 필요해 R-F2와 부딪힐 수 있음(U11-R8) |
| (2026-10-16 갱신) 확장점 포트 `PracticeSessionExtension` | 구현 3곳(`NoOpPracticeSessionExtension` · `RuntimePracticeExtension` · 시험 `RecordingPracticeExtension`) — 능력은 `default` 메서드로 더하면 연쇄가 없다 |
| (2026-10-16 갱신) 새 realtime 표 | `RealtimeDeletionParticipant` 구현과 `order()` 위치(세션 200 앞), `DeletionCompleted.counts` 포함 여부(열린 질문) |

## 6. (2026-10-16 갱신) realtime-activity 안 패키지 의존 · 구조 제약

```mermaid
flowchart LR
    S["session<br/>SessionExecutor · 확장점 포트"]
    V["voice<br/>프레임 경로"]
    GT["gate<br/>프레임 경로"]
    DL["dialog<br/>상태 없는 판정기"]
    CT["content<br/>판 리소스"]
    LG["log<br/>대화 기록"]
    PR["practice<br/>U5b 런타임"]
    LU["luna<br/>Luna 호출부"]
    MS["messaging<br/>Outbox · 삭제 참여자"]
    RT["runtime · config"]
    NEW["새 판정 패키지 후보<br/>JudgeClient · judgment_live"]
    S --> V
    S --> GT
    S --> DL
    S --> CT
    S --> LG
    S --> MS
    RT --> S
    PR -->|"포트 구현"| S
    NEW -.->|"후보 의존"| LU
    NEW -.->|"후보 의존"| CT
    S -.->|"후보 의존"| NEW
```

텍스트 대안: 세션 실행기(`session`)가 공급자(`voice`) · 게이트(`gate`) · 대화 판정기(`dialog`) · 판 리소스(`content`) · 대화 기록(`log`) · 메시징(`messaging`)을 쓰고, 실행 배선(`runtime` · `config`)이 실행기를 만든다. 레거시 연습 런타임(`practice`)은 실행기의 확장점 포트를 구현한다. Luna 호출부(`luna`)는 지금 아무 패키지도 부르지 않는다. 점선은 U11이 만들 수 있는 새 판정 패키지의 후보 의존이며 설계 결정이 아니다.

구조 제약(ArchUnit, 바꾸지 않음 — R-F2):

| 규칙 | 위치 | U11 영향 |
|---|---|---|
| `luna`는 `voice` · 제브 · 로그 프레임워크 · Jackson 참조 금지 | `arch/LunaArchTest` L31 ~ L37 | 판정 조립 · 결과 해석은 `luna` 밖에 둔다 |
| `gate` · `voice`는 프레임 경로 — JDBC · 저장소 · Outbox 참조 금지 | `arch/RealtimeArchTest` L174 ~ L180 | `judgment_live` 저장소 · `JudgeClient` 구현은 `gate` · `voice` 밖에 둔다 |
| 시스템 시각 직접 읽기 금지(`NO_SYSTEM_TIME`) | `arch/RealtimeArchTest` | 판정 시각은 `MujoongClock` 주입 |
| 운영 코드가 시험 코드 참조 금지(`NO_TEST_CODE_IN_PRODUCTION`) | `arch/RealtimeArchTest` | 판정 대역은 시험 소스에 |
| 숫자 시간 리터럴 허용 목록(쓰이지 않는 항목도 실패) | `arch/TimeLiteralScanTest` | 응답 준비 대기 · 시한은 `timing().judgment()`에서만 |
| 단일 인스턴스 가정 표시 | `arch/SingleInstanceMarkerTest` | 판정 상태가 메모리에만 있으면 표시 대상인지 확인 |
| U8 판 리소스 경계 | `arch/U8LiveContentArchTest` | 판 리소스는 `content`를 거쳐 읽는다(본문 얕게 봄) |
