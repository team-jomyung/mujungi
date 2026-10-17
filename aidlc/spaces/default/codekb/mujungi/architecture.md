> 현재 범위의 정본은 바로 아래 U14 갱신절이다. 그 뒤 원문은 보존 이력이며 재검증 범위는 timestamp에 한정한다.

## U14 Architecture Analysis — 현재 흐름

후보의 실제 저장 책임은 core.practice.CandidateRegistry → PracticeGoalRepository, 검증은 core.result.CandidateScreen에 나뉜다. SessionResultHandler는 외부 AI를 트랜잭션 밖에서 부르고 JobContext.commit으로 결과를 확정한 뒤 후보를 등록한다. JobRunner는 보호자 → 작업 → 리포트 잠금, 시도 번호·lease 재확인, 영속 pending_candidates 복구를 보존한다.

ReportAssembler는 SceneChipMapper.text를 그대로 사용해 표시와 집계 의미가 결합되어 있다. U14에서는 저장 시점·27 투영·리포트 문구의 경계를 나눌 필요가 있다. U15 ChildKnowledgeService 조회는 소유자·동의·삭제 상태를 검사하고 불가 시 빈 목록을 돌려준다. 생성 실패는 NONE이며 결과 확정을 되돌리면 안 된다.

## Interaction Diagrams — U14 변경 전 경계

```mermaid
sequenceDiagram
    participant Job as JobRunner
    participant Handler as SessionResultHandler
    participant AI as ResultTextGateway
    participant DB as 결과 저장소
    participant Goal as CandidateRegistry
    Job->>Handler: 결과 시도
    Handler->>AI: 구조화 및 문장·후보·재판정
    AI-->>Handler: 출력
    Handler->>DB: 잠금 재확인 후 결과 확정
    Handler->>Goal: 커밋 뒤 후보 등록
    Goal->>DB: 정의 버전 고정 및 후보 저장
```

텍스트 대체: AI 호출 뒤 결과를 확정하고 후보 등록은 커밋 뒤 수행한다. 현재는 행동 정의 기반 후보이며 U14의 자유 생성 후보·씨앗 연결은 아직 없다. 아래 기존 U15 미구현 설명은 과거 자료다.

---

# 아키텍처 (Architecture)

> 최신 확인: 2026-10-16, HEAD 7e30ee8, U15 집중 분석. 아래 최신 절이 U15 관련 과거 설명보다 우선한다. 이전 분석 본문은 이력으로 보존하며 이번 범위 밖의 검증 주장은 shallow로 낮췄다.

## U15 현재 구조와 확장 경계

PragmaticsKnowledge는 KnowledgeJdbcRepository, MujoongClock, KnowledgeProperties, 선택적 KnowledgeGraphLookup에 의존한다. 공개 행동키 조회만 존재한다. 그래프를 켰는데 어댑터가 없으면 초기화에 실패하며 꺼짐에서는 호출하지 않는다. Neo4j 어댑터·메모리 대역은 아직 없다. CoreLunaClient는 실제 존재하며 OPENAI_API_KEY가 있을 때만 구성된다. 아래 과거 기록의 텍스트 AI 미연결 설명을 현재 Luna 구현에 적용하지 않는다.

삭제는 DeletionCommander가 참가자를 순서대로 실행하고 예외를 재시도한다. 결과 삭제가 70이므로 제안된 graph 참가자 80 이전에 결과를 참조하는 restrictive FK가 삭제를 막지 않도록 설계해야 한다. 조회 실패의 빈 값과 삭제 실패의 재시도는 서로 다른 정책이다. 생성/삭제 경쟁은 GuardianWriteLock과 삭제 상태 재검증으로 해결할 설계 대상이다.

## Interaction Diagrams

```mermaid
sequenceDiagram
    participant Caller
    participant Knowledge as PragmaticsKnowledge
    participant DB as KnowledgeJdbcRepository
    participant Graph as KnowledgeGraphLookup
    Caller->>Knowledge: 행동 정의 / 관련 행동키 요청
    Knowledge->>DB: 권위 정의 조회
    DB-->>Knowledge: 정의와 버전
    opt 그래프 활성화
        Knowledge->>Graph: findRelatedBehaviorKeys
        Graph-->>Knowledge: 공개 행동키
    end
    Knowledge-->>Caller: 기존 지식 응답
```

텍스트 대체: 호출자는 PragmaticsKnowledge를 사용한다. 정의는 PostgreSQL에서 조회하고 그래프가 활성화된 경우에만 선택적 공개 행동키 포트를 호출한다. 이 그림은 현재 동작이며 U15 아동별 생성·저장은 구현 전이다.

## 이전 분석 기록 — 2026-10-15, 현재 범위 밖은 재검증하지 않음

> 기준: 커밋 `72d7034`, 2026-10-15 전체 재스캔. 구성 요소 목록 · 책임은 [component-inventory.md](component-inventory.md), 엔드포인트 목록은 [api-documentation.md](api-documentation.md)가 정본이다. 이 문서는 구조 · 흐름 · 결정만 적는다.

## Architecture Analysis

### System Overview

두 개의 Spring Boot 앱과 한 개의 Next.js 웹 앱, 한 개의 PostgreSQL 인스턴스로 이뤄진 시스템이다.

- **core-api**(8080): 보호자 REST · 기기 부트스트랩 REST · 계정/동의/아이/기기 등록 · 활동 시작과 조회 · 연습 목표/상황 생성 · 결과 작업 · 리포트 PDF · 웹 푸시. 스키마 `core`.
- **realtime-activity**(8081): 아이 기기 WSS 게이트웨이(`/ws/device`) · 세션 수명 주기 · 음성 공급자 중계 · 출력 게이트 · 대화 기록 · 원음 녹음 · 연습 런타임. 스키마 `realtime`.
- **frontend**: 한 개의 Next.js App Router 앱이 `/parent`(보호자)와 `/device`(아이 기기) 두 화면 묶음을 제공한다.
- **contract-model**: 두 앱이 함께 쓰는 계약 타입 라이브러리(WS 메시지 · 이벤트 · 상태 enum · 오류 봉투 · 시계 · 로그). Spring 의존 없음.
- **PostgreSQL 16**: 한 인스턴스, 스키마 · 역할 분리(`core`/core_app, `realtime`/realtime_app). 마이그레이션은 앱이 아니라 별도 `migrate` 이미지(Flyway)가 실행한다.
- 앞단 **Nginx**(demo)가 TLS를 끝내고 프런트 · 두 앱으로 경로를 나눈다.

### Architectural Style

**분리된 두 서비스 + 공유 계약 라이브러리 + 단일 DB 스키마 분리**(작은 마이크로서비스 2개, 모듈형 내부). 근거:

- Gradle 모듈 사이에서 `core-api` ↛ `realtime-activity` 직접 의존이 없고, CI 불변식 샘플(`bad-core-depends-realtime.yml`)과 ArchUnit 시험(`core/arch` · `realtime/arch`)이 이를 막는다.
- 앱 사이 통신은 내부 HTTP(`/internal/*`, 내부 토큰)와 Outbox/Inbox 영속 이벤트(HTTP 전달)뿐이다.
- 각 앱 내부는 기능 패키지(core 23개 · realtime 17개)로 나뉜 모듈형 모놀리스다.
- 배포는 demo 단일 EC2 호스트의 Docker Compose(IaC 없음).

### Component Relationships

```mermaid
flowchart LR
    subgraph Browser["브라우저"]
        P["frontend /parent<br/>보호자 화면"]
        D["frontend /device<br/>아이 기기 화면"]
    end
    N["Nginx<br/>TLS · 경로 분기"]
    subgraph Core["core-api :8080"]
        CW["web · security<br/>보호자 REST"]
        CA["activity · practice"]
        CR["result · report · record"]
        CN["notification<br/>웹 푸시"]
        CM["messaging<br/>Outbox/Inbox"]
        CT["textai · ResultTextProvider<br/>(기본 UNAVAILABLE)"]
    end
    subgraph RT["realtime-activity :8081"]
        RG["gateway<br/>/ws/device"]
        RS["session<br/>SessionExecutor"]
        RV["voice<br/>ProviderClient"]
        RGT["gate · dialog"]
        RP["practice<br/>(판정 · 단서 기본 미연결)"]
        RM["messaging<br/>Outbox/Inbox"]
        RL["log · recording"]
    end
    DB[("PostgreSQL 16<br/>schema core / realtime")]
    VP["외부 음성 AI<br/>wss api.openai.com"]
    G["Google OAuth"]
    WP["Web Push 서비스"]
    P --> N
    D --> N
    N --> CW
    N --> RG
    CW --> CA
    CA -- "내부 HTTP /internal/sessions" --> RS
    RS -- "내부 HTTP /internal/devices/verify-token 등" --> CW
    RP -- "내부 HTTP /internal/practice/scenarios" --> CA
    RG --> RS
    RS --> RV
    RS --> RGT
    RS --> RP
    RS --> RL
    RV --> VP
    CM <-->|"이벤트 /internal/events"| RM
    CR --> CT
    CA --> CT
    CR --> CN
    CN --> WP
    CW --> G
    Core --> DB
    RT --> DB
```

텍스트 대안: 브라우저의 보호자 화면과 기기 화면은 Nginx를 거쳐 각각 core-api REST와 realtime-activity WebSocket에 닿는다. core-api는 활동을 시작할 때 realtime-activity의 내부 HTTP를 부르고, realtime-activity는 기기 토큰 검증 · 연결 보고 · 연습 상황 조회를 core-api 내부 HTTP로 부른다. 두 앱은 Outbox/Inbox 이벤트를 서로의 `/internal/events`로 전달한다. realtime-activity만 외부 음성 AI와 WebSocket으로 연결하고, core-api만 Google OAuth · 웹 푸시 · 텍스트 AI 포트를 쓴다. 두 앱은 같은 PostgreSQL의 서로 다른 스키마를 쓴다.

### Data Flow

1. **활동 시작**: 보호자 REST → Core가 활동 · 세션 패키지를 만들고 → Realtime `POST /internal/sessions` → Realtime이 기기 WS로 `START_SESSION` → 기기 `ACK` · `DEVICE_READY` → 공급자 세션 시작.
2. **음성 턴**: 기기 마이크 PCM16LE 24kHz 모노 20ms 프레임(바이너리 0x01, 960바이트) → Realtime `InputRelay` → 공급자 `session.input_audio.append` → 공급자 출력 텍스트 · 음성 → `OutputGate`가 문장 단위로 승인/폐기 → 승인분만 기기 재생(`OUTPUT_BEGIN/END`, `PLAY_APPROVED_CONTENT`).
3. **상태 전파**: Realtime 세션 전이 → `SessionStateChanged` Outbox → Core Inbox → Core 세션 뷰 · 보호자 화면 폴링(react-query).
4. **결과**: 세션 종료 → `SessionRecordCaptured`(대화 기록 스냅샷) → Core 결과 작업(JobRunner) → 결과 수준 · AI 문장(텍스트 AI) · 금지 표현 검사 → 결과 상태 → 웹 푸시.
5. **삭제**: Core `DeletionRequested` → Realtime 삭제 참여자 → `DeletionCompleted`.

### Interaction Diagrams

#### 1) 자유 대화 시작 → 첫 턴

```mermaid
sequenceDiagram
    autonumber
    participant P as 보호자 화면
    participant C as core-api
    participant R as realtime-activity
    participant D as 아이 기기
    participant V as 음성 공급자
    P->>C: POST /api/activities/free
    C->>C: StartActivityService 활동 행 · 세션 패키지 생성
    C->>R: POST /internal/sessions
    R->>R: StartSessionHandler 상태 REQUESTED 에서 PREPARING
    R-->>C: 세션 시작 응답
    C-->>P: 활동 · 세션 id
    R->>D: WS START_SESSION
    D->>R: WS ACK
    R->>R: 상태 DEVICE_ACK
    D->>R: WS DEVICE_READY
    R->>V: session.start 모델 gpt-live-1
    V-->>R: session.started
    R->>R: 상태 WAITING_CHILD 이후 ACTIVE
    R->>D: WS SESSION_STATE
    D->>R: 바이너리 음성 프레임 0x01
    R->>V: session.input_audio.append
    V-->>R: session.output_transcript.delta 와 session.output_audio.delta
    R->>R: OutputGate 문장 승인 판단
    R->>D: WS OUTPUT_BEGIN, 승인 음성, OUTPUT_END
    R--)C: 이벤트 SessionStateChanged
    P->>C: GET /api/sessions/current 폴링
```

텍스트 대안: 보호자가 자유 대화를 시작하면 Core가 활동을 만들고 Realtime 내부 API로 세션을 연다. Realtime은 기기에 START_SESSION을 보내고, 기기의 ACK · DEVICE_READY를 받은 뒤 공급자 세션을 연다. 아이 음성은 바이너리 프레임으로 들어와 공급자로 중계되고, 공급자 출력은 출력 게이트를 통과한 것만 기기로 재생된다. 상태 변화는 이벤트로 Core에 전달되고 보호자 화면은 폴링으로 본다.

#### 2) 종료 → 결과 생성

```mermaid
sequenceDiagram
    autonumber
    participant P as 보호자 화면
    participant C as core-api
    participant R as realtime-activity
    participant D as 아이 기기
    participant V as 음성 공급자
    participant T as 텍스트 AI 포트
    alt 보호자 종료
        P->>C: POST /api/sessions/ID/end
        C->>R: POST /internal/sessions/ID/end
    else 침묵 또는 아이 중단 의도
        R->>R: SilenceClock 또는 StopIntentJudge
    end
    R->>R: 상태 CLOSING
    R->>D: WS STOP_OUTPUT 와 CLOSE_TRANSPORT
    R->>V: session.close
    R->>R: 상태 ENDED 또는 다른 끝 상태
    R--)C: 이벤트 SessionRecordCaptured
    C->>C: SessionResultHandler 결과 작업 등록
    C->>C: ResultLevelCalculator 1단계
    C->>T: 결과 문장 요청
    T-->>C: 기본 빈은 PROVIDER_UNAVAILABLE
    C->>C: 결과 상태 저장, 재시도 가능
    P->>C: GET /api/sessions/ID/result/status
```

텍스트 대안: 보호자 종료는 Core를 거쳐 Realtime 내부 종료 API로, 침묵 · 아이 중단 의도는 Realtime 안에서 바로 종료를 시작한다. Realtime은 CLOSING에서 기기 출력과 전송을 닫고 공급자 세션을 닫은 뒤 끝 상태로 간다. 대화 기록 스냅샷 이벤트를 받은 Core는 결과 작업을 돌리는데, 지금 기본 텍스트 AI 빈은 항상 실패하므로 운영 환경에서는 AI 결과 문장이 만들어지지 않는다.

#### 3) 연습 활동 준비

```mermaid
sequenceDiagram
    autonumber
    participant P as 보호자 화면
    participant C as core-api
    participant T as 텍스트 AI 포트
    participant R as realtime-activity
    P->>C: GET 또는 POST /api/goal-selections
    P->>C: POST /api/activities/practice
    C->>C: PracticeStartService 목표 고정
    C->>T: ScenarioPrompt 상황 생성
    T-->>C: 기본 빈은 UNAVAILABLE
    C->>R: POST /internal/sessions 연습 패키지
    R->>C: POST /internal/practice/scenarios
    R->>R: 연습 런타임 판정 · 단서, 기본 미연결
```

텍스트 대안: 연습 시작 시 Core가 목표를 고정하고 텍스트 AI로 상황을 만들어 Realtime에 넘기며, Realtime은 필요할 때 Core 내부 API로 상황을 다시 조회한다. 상황 생성 · 판정 · 단서의 기본 공급자는 모두 미연결이라 생성 실패 시 `SCENARIO_GEN_FAILED` 경로를 탄다(실패 처리 본문은 얕게 봄).

### Key Design Decisions

| 결정 | 근거 · 위치 | 영향 |
|---|---|---|
| 앱 2개 분리, 직접 의존 금지 | `settings.gradle.kts`, ArchUnit, CI 불변식 | 음성 실시간 부하와 보호자 REST를 따로 배포 · 확장. 대신 내부 HTTP · 이벤트 계약 관리 비용 |
| 계약 정본은 `contracts/`, 코드 타입은 `contract-model` | `contracts/README.md` 소유 · 깨는 변경 규칙 | 계약 변경 시 정본 · `deviations.md` · 계약 시험 · 프런트 파서를 함께 바꿔야 함 |
| Outbox/Inbox + HTTP 전달 | `core.messaging` · `realtime.messaging`, 재시도 backoff 설정 | 브로커 없이 최소 한 번 전달. 수신 측 멱등 처리 필요 |
| 한 DB, 스키마 · 역할 분리, 마이그레이션은 별도 이미지 | `infra/migrate/flyway-{core,realtime}.conf`, 앱 `spring.flyway.enabled=false` | 앱이 스키마를 바꾸지 않음. 번호 순서 · expand 검사 · 승인 목록 필요(TD-7) |
| 음성 공급자 SDK 없이 JDK WebSocket, 허용 호스트 고정 | `ProviderHosts`(`api.openai.com`), `ProviderClient` | 의존 최소화. 선 형식을 직접 소유(TD-2) |
| 출력 게이트로 공급자 출력을 문장 단위 승인 | `realtime.gate.OutputGate` · `SeedDictionary` | 아동 안전. 지연 증가 |
| AI 포트는 인터페이스 + 기본 실패 빈 | `TextAiConfiguration`, `ResultTextConfiguration`, `PracticeRuntimeConfiguration:46-51` | 비밀 없이도 기동 · 시험 가능(fail-closed). 운영 어댑터가 없음(TD-1) |
| 시간 값은 설정 키로, 계약 문서와 대조 | `mujoong.timing.*` ↔ `contracts/timing.md` ↔ 프런트 `timing-defaults.json` | 값 변경 시 세 곳 동시 수정(TD-6) |
| 한 Next.js 앱에 `/parent` · `/device` 공존, ESLint로 경계 | `frontend/eslint.config.mjs` | 배포 단순. import 경계는 린트 규칙에 의존 |

### Improvement Opportunities

1. **AI 공급자 어댑터 추가**(TD-1): 텍스트 AI · 결과 문장 · 판정 · 단서의 운영 어댑터를 포트 뒤에 붙이고, 기본 실패 빈은 시험 · 비밀 없는 기동용으로만 남긴다. 갱신 문서(`15_기술명세_AI동작_1015` · `40_기능명세_GPT-Live`)와 맞출 때 첫 후보 경계.
2. **음성 공급자 선 형식 격리**(TD-2): `ProviderRequestFactory` · `ProviderClient`의 type 문자열을 한 어댑터로 모으고, 가짜 공급자(`tools/fake-provider/`, `test-support/.../provider/`)와 같은 정의를 공유하면 실공급자 형식 변경 비용이 줄어든다.
3. **`SessionExecutor` 분해**(TD-4): 3,388줄에 상태 · 타이머 · 출력 · 연습 확장이 섞여 있다. 문서 차이로 세션 동작을 바꿀 때 변경 범위가 커진다.
4. **시간 값 단일 정본화**(TD-6): 설정 · 계약 문서 · 프런트 JSON 세 곳을 생성 또는 시험으로 묶는다(현재는 시험 대조).
5. **서식 검사 CI 추가**(TD-5): Java 포매터 · Prettier 검사 부재로 압축 서식이 막히지 않는다.

## 보안 · 준수 관점 요약

- 보호자 REST: Spring Security + JDBC 세션, CSRF(`/api/session/csrf`), Origin 검사, 속도 제한(`SlidingWindowRateLimiter`). 기기: 쿠키 인증 필터 · 기기 토큰(Core가 검증).
- 내부 API: 양쪽 `InternalTokenFilter`(Realtime은 Spring Security 없이 이 필터만).
- 외부 호스트 고정: 음성 공급자 `api.openai.com`, 웹 푸시는 공개 DNS · 고정 HTTP 클라이언트(`PinnedPushHttpClient` · `PublicPushEndpointPolicy`).
- 오류 응답에서 메시지 · 스택 숨김(`server.error.*`), 관리 포트 9090은 127.0.0.1 바인딩.
- 아동 데이터 삭제는 삭제 참여자 패턴(양쪽 `*DeletionParticipant`)과 삭제 이벤트로 처리.
