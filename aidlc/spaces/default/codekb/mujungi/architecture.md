## Architecture Analysis

> 기준: 커밋 570a625. 컴포넌트 목록은 `component-inventory.md`, 외부 접점은 `api-documentation.md`가 정본이다.

### System Overview

한 저장소 안에 백엔드 두 앱(core-api, realtime-activity), 그들이 공유하는 계약 라이브러리(contract-model), 프런트 한 앱(Next.js, `/parent` · `/device`), 개발 도구(tools), 배포 구성(infra), 계약 문서(contracts)가 있다. 두 앱은 같은 PostgreSQL을 쓰되 서로의 코드나 표를 읽지 않고 내부 HTTP와 이벤트(발신함 · 수신함)로만 통신한다(R-F5, ArchUnit이 강제).

### Architectural Style

Hybrid: 모듈형 멀티 프로젝트(Gradle) 위의 **두 개의 배포 가능한 서비스**(core-api, realtime-activity) + 별도 웹 프런트 + Nginx 앞단. 근거: `backend/settings.gradle.kts`의 모듈 구성, 모듈별 Dockerfile, ArchUnit의 상호 참조 금지 규칙. 각 앱 안은 기능 패키지(`kr.mujoong.core.*`, `kr.mujoong.realtime.*`) 구조이고 경계 인터페이스는 `*Port`, 구현은 `*Adapter`로 둔다.

### Component Relationships

```mermaid
flowchart LR
  subgraph FE[frontend Next.js]
    P[/parent 보호자 웹/]
    D[/device 기기 웹/]
    S[shared]
  end
  NG[Nginx]
  CORE[core-api]
  RT[realtime-activity]
  CM[contract-model]
  DB[(PostgreSQL)]
  AI[외부 AI: GPT-Live, Luna, 제브, 지식 그래프]
  P -->|보호자 REST| NG
  D -->|기기 REST와 WebSocket| NG
  NG --> CORE
  NG --> RT
  CORE <-->|내부 HTTP, 이벤트| RT
  CORE --> CM
  RT --> CM
  CORE --> DB
  RT --> DB
  RT --> AI
  CORE --> AI
```

텍스트 대체: 보호자 웹은 core-api로, 기기 웹은 core-api(REST)와 realtime-activity(WebSocket)로 Nginx를 거쳐 간다. 두 앱은 내부 HTTP와 이벤트로만 통신하고 contract-model만 공유한다. 프런트의 `/parent` ↔ `/device` ↔ `shared`는 서로 import 금지다(R-F4, ESLint).

### Data Flow

- 보호자 요청은 core-api 컨트롤러(기능 패키지 `web`) → 서비스 → 저장소 순으로 흐르고, 오류는 `CoreApiException.of(ErrorCode)` → `ApiExceptionHandler` → `{code, message, traceId}` 봉투로 나간다.
- 세션 실행은 realtime-activity 한 곳에서 단일 스레드 메일박스 모델로 돈다. 사건(기기 메시지, 시계 만료, 공급자 응답, 내부 호출)이 `SessionExecutor`에 들어가 구역별 처리를 거친다. 구역 구성은 `code-structure.md`.
- 앱 사이 비동기 전달은 발신함 · 수신함(`messaging`, `contracts/events.asyncapi.yaml`)이다.

### Interaction Diagrams

**1) 기기 한 턴(자유 대화)**: 처리 순서는 현재 `SessionExecutor` 한 파일의 위아래 위치로만 드러난다(제안서 2절). 아래는 구역 순서를 따른 흐름이다.

```mermaid
sequenceDiagram
  participant Dev as 기기 웹 /device
  participant GW as realtime gateway
  participant EX as SessionExecutor
  participant JU as judgment (U11 판정 턴)
  participant PX as 연습 확장점 (practiceflow)
  participant GR as GuardrailCoordinator / gate
  participant VO as voice (GPT-Live)
  Dev->>GW: WebSocket 메시지(음성, 사건)
  GW->>EX: 사건 투입(메일박스)
  EX->>EX: 메시지 처리, 참여 확인(WAITING_CHILD)
  EX->>JU: 판정 턴 열기 (해당할 때)
  EX->>PX: 확장점 호출 (연습일 때, 실행부에서 확장점 한 방향)
  PX-->>EX: 출력 능력 / 지시
  EX->>VO: 공급자 지시
  VO-->>EX: 응답
  EX->>GR: 출력 검사 (게이트 봉인)
  GR-->>EX: 통과 또는 보류
  EX->>GW: 승인된 출력
  GW->>Dev: 재생할 음성 · 표식
```

**2) 보호자 활동 시작과 정리**

```mermaid
sequenceDiagram
  participant Par as /parent 웹
  participant Core as core-api
  participant RT as realtime-activity
  participant Dev as 기기
  Par->>Core: 활동 시작 요청
  Core->>RT: 내부 HTTP 시작(시작 패키지)
  RT-->>Core: 접수 결과
  Dev->>RT: WebSocket 대화
  RT-->>Core: 이벤트(발신함, 수신함)
  Par->>Core: 라이브 조회 / 종료
  Core-->>Par: 상태, 결과, 리포트
```

(1)은 핸드오프 구역 표, (2)는 `contracts/` 이름과 핸드오프 API 목록에서 정리한 흐름이다. 개별 엔드포인트 수와 core-api 내부는 이번 스캔에서 깊게 읽지 않았다.

### Key Design Decisions

- 실행부 → 확장점 한 방향만 둔다(`PracticeFlowArchTest`의 `EXECUTOR_KNOWS_NO_FLOW`). 확장점은 실행부를 부르지 않고 정해진 출력 능력만 낸다.
- 단일 스레드 메일박스 + 단일 인스턴스 가정 표식(`SingleInstanceAssumption`, 시험 표 SI-2 · SI-9가 `SessionExecutor`를 직접 가리킴).
- 시각은 `MujoongClock` 주입, 시간 값은 `mujoong.timing.*` 설정 키에서만 읽는다(R-F6, R-F7).
- 계약(`contracts/`)과 코드는 대조 시험이 묶는다(`ConfigKeyContractTest`, `ErrorCodeTest` 등).
- 외부 AI 호출부는 두 앱에 각자 두고 공용 계약 모듈에 두지 않는다.

### Improvement Opportunities

이번 의도의 후보이며 자세한 근거는 `code-quality-assessment.md`다.

- `SessionExecutor`를 역할별 협력 객체로 나누고 분배 순서를 한 곳에서 읽히게 한다.
- `realtime.practice`(레거시) / `realtime.practiceflow`(새 흐름)의 이름 혼동을 줄인다.
- 프런트 화면 번호 · 유닛 번호 이름을 기능 이름으로 바꾸고 요청 · 오류 · 이동 처리 위치를 통일한다.
