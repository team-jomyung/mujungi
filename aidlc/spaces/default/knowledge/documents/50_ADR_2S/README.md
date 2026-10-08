① 개정: audit 기준의 현행 결정·문서 역할·20개 문서 목록·1~8단계 적용 순서와 미검증/TBD 추적 경계를 정리했다.
② 대체: 직접 WebRTC·SDP/Sideband, 3개 로그인, 인사 후 ENDING, 단일 재시작·Spring, 기기 단기 Cookie는 대체된 이전 선택으로 연결했다.
③ 남은 TBD: 구현 버전·필드·인증/배포/복구 상세, 출력 분할·검사·재생 계약의 물리화, G-02/G-03, 실제 지연·안전·장애 POC와 동료 명세 경로/버전.

# Architecture Decision Records

이 폴더는 MVP의 설계 결정을 기록한다. ADR은 결정과 근거·대안·남은 판단을 소유하고, `architecture/*`는 흐름·계약·모델·시험 계획을 상세화한다. [결정 로그](decision-log.md)는 현행 결정과 대체 관계·미결정 값의 추적 목록이다. 목록에 적힌 사실만으로 구현·시험이나 팀 승인이 완료되지는 않는다.

## 개정 기준과 현행 방향

이번 개정의 유일한 기준은 [아키텍처 업데이트 audit v1.1](../ref/mujung-architecture-update-audit-v1.1.md)이다. 요청에서 사용한 `v1_1` 표기와 달리 실제 파일명은 `v1.1`이다. [v0.2 개요](../ref/2-spring-architecture-v0.2.md)와 [v0.2 교차검토](../ref/2-spring-cross-validation-v0.2.md)는 보조 근거다. **최신 사용자 확정 → v0.2 수정 → 기존 문서의 비충돌 내용 보존** 순서로 적용한다. audit의 원문 행 번호는 ADR_ONE.zip 기준이며, 이 개정본은 내용으로 대응한다.

| 주제 | 현재 적용하는 결정 | 책임 문서 |
| --- | --- | --- |
| 실행·소유권 | Core API Spring + Realtime Activity Spring 두 실행·빌드 단위. 실행 특성·상태 소유권 기준. Result Worker는 Core 내부 | [ADR-009](ADR-009-execution-boundary.md), [ADR-007](ADR-007-module-boundaries.md) |
| 음성 | 기기↔Realtime WSS의 음성·제어·재생 보고, Realtime↔GPT-Live API(`gpt-live-1`) 주 WebSocket 릴레이 B안 | [ADR-004](ADR-004-communication-voice.md) |
| 출력 전달 | 앱의 구간 분할 → 전사 대응 → 상태·권한·최신성 및 필요한 경량 의미 검사 → 승인된 동일 음성만 전달. 누락/불명확/실패/시간초과는 폐기·대체 안내. 송신 직전 재확인 | [ADR-004](ADR-004-communication-voice.md), [protocol](architecture/protocol.md) |
| 차단·재생 | 폐기 음성도 모델 맥락에 남으므로 앱의 교정 정책을 적용. 교정 ACK는 재생 승인이 아니며 새 출력은 다시 검사. playbackMark는 기기 관측 범위이며 실제 청취·이해의 증거가 아님 | [protocol](architecture/protocol.md), [session-state](architecture/session-state.md) |
| 종료·시간 | 유효한 아동 종료 표현 확인 즉시 ENDING A안, 일반 출력 차단/버퍼 폐기 후 승인 마무리 인사 1회 예외. 끼어들기는 자동 종료가 아님. 무응답 10초·Cue 5초는 기존 미측정 초기값/예산 유지 | [ADR-005](ADR-005-session-events-timeouts.md) |
| 복구·결과 | 각 앱이 소유한 활동/작업/전달을 복구. 음성 버퍼 비복원·미확인 재생 성공 보정 금지. 유효 기록의 PARTIAL과 결과 준비 상태 분리, 늦은 결과로 활동 부활 금지 | [ADR-005](ADR-005-session-events-timeouts.md), [ADR-010](ADR-010-durable-delivery.md) |
| 보호자 인증 | Google만 활성화, 자체 ID/PW 제외 유지. Core의 Spring Security OAuth2 Client·Spring Session JDBC·Guardian·CSRF·현재 동의 유지. 공급자 토큰과 서비스 세션 구분 | [ADR-008](ADR-008-auth-access-control.md#guardian-social-login) |
| 기기 | 고정 ID 사전 시드 → 대기 WSS/상태 보고 → 보호자 상태 카드/시작 → 검증·원자 할당 후 push. 고정 ID는 운영용 인증의 증거가 아님 | [ADR-003](ADR-003-child-device-ui.md), [auth](architecture/auth.md) |
| 앱 간 전달 | 즉시 명령은 내부 HTTP. 유실되면 안 되는 이벤트는 Outbox/Inbox의 영속 수신·멱등·복구. 두 경로 모두 내부 서비스 인증, Broker 없음 | [ADR-010](ADR-010-durable-delivery.md), [protocol](architecture/protocol.md) |
| 데이터 | PostgreSQL 물리 1개·앱별 소유 스키마/쓰기 권한, 기존 Flyway의 앱별 작성 책임·단일 배포 적용/각 앱 validate. 상대 Repository 직접 수정 금지. 개인 데이터와 공통 Neo4j 분리·원음 미저장 | [data-model](architecture/data-model.md), [ADR-007](ADR-007-module-boundaries.md) |
| 기존 제품·기술 경계 | Next.js 한 앱·두 접점, Neo4j, 단일 GPT-Live 전사·별도 STT 보류, EC2/볼륨/백업/DB 비공개 원칙 유지 | [ADR-002](ADR-002-technology-defaults.md), [ADR-006](ADR-006-demo-deployment.md) |

검사 수행은 전달 조건이다. 검사 모델이 모든 위험을 발견하거나 전사가 정확하다는 보장은 아니다. 무거운 교육 판정·KG 조회를 동기 게이트에서 분리하는 것은 **우리 설계 권고**다. Realtime 앱 이름과 공급자의 API 종류를 구분하며, 공급자 이벤트·시작·말차례는 audit §2.1/§5.1과 ADR-004를 따른다.

프론트 2앱·Vite·CloudFront·QR claim·UI 표시 상태의 DB Enum 확장·delegation을 이번에 채택하지 않는다. 실제 운영 모델/SDK 지원, 출력 분할·완료 기준·검사기, playbackMark 물리 필드/단위, 동시 활동 수와 버퍼/시간 한도는 TBD다. 제품 시연의 실제/Mock 범위(Q-DEVICE-01/G-01), Core 장애 중 보호자 제어(G-02), 철회 효력 시각 및 확인-송신/저장 경합(G-03)도 남아 있다.

## 상태와 확인 범위

문서의 **방향 확정**, **Proposed 구현안**, **값 TBD**, **실제 검증 대기**를 구분한다. B안·Google·두 앱 등 확정 방향을 POC 미실시 때문에 다시 Pending으로 되돌리지 않는다. 기존 ADR 전체의 Proposed 상태나 Java/Spring/Gradle·Git/PR 협업 규칙을 일괄 Accepted로 올리지도 않는다.

| ADR | 주제·문서 역할 | 상태와 확인 범위 |
| --- | --- | --- |
| [ADR-001](ADR-001-repository-git.md) | Monorepo·Git/PR, 두 앱 저장소·빌드 영향 | 기존 Proposed 유지. 실제 프로젝트/Artifact·CI 경계 미정 |
| [ADR-002](ADR-002-technology-defaults.md) | 기술 기본값·앱별 스키마·Adapter/시험 원칙 | Neo4j/단일 전사·기존 인증 기반 보존, 최신 Google/2-Spring/B안 적용. 버전/호환성 미검증 |
| [ADR-003](ADR-003-child-device-ui.md) | 화면 없는 기기의 대체 클라이언트·상태/재생 보고 | 고정 ID 데모와 서버의 참여·의미 최종 판정 적용. UI 세부/운영 인증 미정 |
| [ADR-004](ADR-004-communication-voice.md) | GPT-Live 주 연결·릴레이·게이트·차단 회복 | B안 방향 적용, 단일 전사 Accepted 유지. SDK·매핑·안전/지연 POC 미실시 |
| [ADR-005](ADR-005-session-events-timeouts.md) | 상태·사유·미실시, 종료/취소·타이머·앱별 복구 | 종료 A안/유효 부분 결과 반영. 시간 초기값·복구 구현은 미검증 |
| [ADR-006](ADR-006-demo-deployment.md) | 두 앱 실행·라우팅·비밀·마이그레이션·부하 | 기존 인프라 원칙 보존. Compose/포트/자원·TLS/백업 구현·시연 시험 대기 |
| [ADR-007](ADR-007-module-boundaries.md) | Core/Realtime 모듈·상태/쓰기 소유권·Port/Adapter | 새 경계와 동적 목표/Cue 책임 반영. 실제 패키지/원격 계약·ArchUnit 검증 대기 |
| [ADR-008](ADR-008-auth-access-control.md) | Google·서비스 세션·동의/권한·내부 인증·기기 범위 | 선택과 원칙 적용. 등록값·만료/CSRF·운영 인증·G-02/G-03 상세/시험 대기 |
| [ADR-009](ADR-009-execution-boundary.md) | 단일 Spring을 대체하는 두 앱 실행 경계·남는 의존 | 6단계 신규. 방향 적용과 실제 장애 격리/부하 검증 구분 |
| [ADR-010](ADR-010-durable-delivery.md) | 앱 간 Outbox/Inbox·멱등·영속 ACK·run/복구 | 5단계 신규. 전달/LLM/수동 재시도 분리, 물리 설계·장애 시험 대기 |

추가 문서 10개는 다음과 같다. 전체 목록은 ADR 10개 + architecture 8개 + README/decision-log의 **20개 Markdown**이다.

| 문서 | 역할 |
| --- | --- |
| [system-architecture](architecture/system-architecture.md) | 논리·배포 그림, 실행/역할/신뢰 경계 |
| [protocol](architecture/protocol.md) | 외부 REST, 기기 WSS, GPT-Live, 내부 HTTP/영속 이벤트 계약과 ACK/재생 구분 |
| [sequences](architecture/sequences.md) | 시작·참여·게이트·회복·끼어들기·종료·두 앱 복구/결과 전달 시퀀스 |
| [session-state](architecture/session-state.md) | 상태·취소·늦은 결과·타이머·Cue·실제 재생 관측의 관계 |
| [auth](architecture/auth.md) | 서비스 세션·기기·내부 서비스의 인증/인가 상세와 현재 동의 |
| [data-model](architecture/data-model.md) | 앱별 데이터/쓰기 소유권·논리 참조·목표/Cue/실제 제공 기록 |
| [result-pipeline](architecture/result-pipeline.md) | Core Worker·근거 기반 목표·원격 등록·부분 결과·성공 체크포인트 |
| [first-bolt](architecture/first-bolt.md) | 작은 합성·성인 수직 흐름, 지연/취소/회복 POC와 실패 증거 수집 |
| [decision-log](decision-log.md) | 결정 대체 관계·기존 DL 항목·남은 값/검증 추적 |
| [README](README.md) | 기준·문서 목록·역할·적용 순서의 입구 |

2026-10-01의 Neo4j 채택 및 GPT-Live 단일 전사·별도 STT 보류는 유지한다. 전사의 목적·한계·재검토 조건은 [ADR-004의 전사 결정](ADR-004-communication-voice.md#mvp-transcription)을 따른다. 이는 전사 정확도나 연결 성공을 보장하지 않는다. 당시 보호자 인증의 Spring Security/OAuth2 Client·Session JDBC·자체 ID/PW 제외는 보존하고, 공급자 활성 범위만 최신 Google 결정으로 좁혔다.

### 대체된 이전 선택

원본의 **직접 WebRTC + SDP/Sideband**, **카카오·구글·네이버 3개 로그인**, **인사 재생 후 ENDING**, **단일 서버가 활동/Job을 함께 정리**, **기기 단기 Cookie·운영자 일회용 발급 코드**, **단일 Spring 실행**은 현행 기본값으로 사용하지 않는다. 이전 이유·대안은 담당 ADR의 이력과 [결정 로그](decision-log.md)에 연결했다. ADR-002 v10/v11은 이전 상세 초안의 이관 근거이며 최신 audit 결정을 뒤집는 기준이 아니다.

<a id="goal-cue-update"></a>
## 2026-10-02 연습 목표·Cue 동기화

기존 동적 연습 목표 생성·실시간 Cue 정책 중 audit와 충돌하지 않는 내용을 보존한다. 당시 이관 근거는 `요구사항 명세서 v1.0.xlsx`의 FR-C02·E07~E09·F05~F10·F14~F15 및 변경 이력, `무중_기능명세서_최종.md`, 당시 구현 합의였다. 두 원본 요구사항 파일은 현재 작업 폴더에 없어 링크를 만들지 않았으며 이번 개정에서 다시 확인한 자료로 취급하지 않는다. 다른 잔여 기능까지 구현·검증 완료했다고 확대하지 않는다.

| 구분 | 현재 상태와 적용 |
| --- | --- |
| 동적 목표 정책 | 근거와 행동 정의·기회 조건·상황 생성 조건·판정 기준을 갖춘 행동을 아동별 목록에 추가. 고정 승인 목록 제한·매번 사람 사전 승인 요구로 되돌리지 않음 |
| 목표 구현 | Core 내부 Worker/AI 생성, 등록 전 형식·참조/의미 검사, Realtime 원격 목표 등록·정의 버전 고정. 생성물/프롬프트·멱등/원자성 시험 대기 |
| Cue 정책 | GPT-Live 생성·첫 반응 보호·아동 동의·정답 직접 제시 금지·실제 제공 기록·도움 후 아동 반응 1회 유지 |
| Cue 구현/시간 | Realtime의 단계·정보 통제와 제한된 CueBrief. 최초 요청부터 실제 첫 재생까지 구간 분할/전사/검사 대기를 포함한 5초 초기 예산·조건부 추가 재요청 1회 유지. 재요청으로 최초 시각을 초기화하지 않음. 판정 LLM/Worker 재시도와 구분 |
| 검증 상태 | 정책 반영과 코드·음성/AI 품질·성능 확인을 구분. 선택이 끝난 정책을 다시 미결로 열지 않음 |

상세 진입점: [목표 생성 계약](architecture/result-pipeline.md#goal-generation-contract), [목표 정의·버전](architecture/data-model.md#goal-definition-model), [Cue 입출력](architecture/protocol.md#cue-contract), [Cue 단계/실패](architecture/session-state.md#cue-lifecycle), [Cue 제공 기록](architecture/data-model.md#cue-delivery-model).

## Source of Truth

| 주제 | 결정 책임 | 상세 설계 |
| --- | --- | --- |
| 저장소·Git·두 빌드 단위 | ADR-001, 실행 경계 ADR-009 | ADR-007·ADR-006 |
| 언어·프레임워크·도구, `/parent` REST 조회 도구/OpenAPI → TypeScript | ADR-002, Polling ADR-004 | protocol |
| 아동 클라이언트 책임·운영자 사전 준비 | ADR-003 | system-architecture·auth |
| 연결/할당·음성 게이트·단일 전사·회복 | ADR-004 | protocol·sequences |
| 활동·사유·종료·재생 기반 타이머·부분 결과 | ADR-005 | session-state·result-pipeline |
| 실행/신뢰/쓰기 경계·복구 | ADR-009·ADR-007 | system-architecture·data-model |
| EC2·Compose·HTTPS·키·마이그레이션·DB 비공개 | ADR-006 | system-architecture |
| Google·서비스 세션·CSRF·기기·내부 인증/인가 | [ADR-008](ADR-008-auth-access-control.md#guardian-social-login) | auth·protocol |
| 영속 수신·멱등·ACK·Worker/run·앱별 복구 | ADR-010, 활동 상태 ADR-005 | result-pipeline·data-model·sequences |
| 목표 생성/등록·4요소·버전·근거 | [ADR-007](ADR-007-module-boundaries.md#goal-and-cue-ownership) | [result-pipeline](architecture/result-pipeline.md#goal-generation-contract)·[data-model](architecture/data-model.md#goal-definition-model) |
| Cue의 정보 제한·제공 조건·실제 기록 | [ADR-004](ADR-004-communication-voice.md#live-cue-responsibility)·[ADR-005](ADR-005-session-events-timeouts.md#cue-timeout)·ADR-007 | protocol·session-state·data-model의 Cue 절 |
| GPT-Live 지시문·호출어·표현·대화 정책·행동별 평가 기준 | 동료 GPT-Live/AI 상세 명세 | 이 폴더는 입력·검사·상태/저장 계약 소유. 프롬프트/대본/판정표 복제 금지 |

책임 문서 사이의 충돌은 먼저 audit의 우선순위와 사용자 확정을 적용해 해소한다. 그 범위 안에서 담당 ADR의 결정을 상세 문서에 전파한다. 상세 정책은 일반 기술 목록보다 담당 ADR을 확인한다. `openai-java`·TanStack Query·OpenAPI → TypeScript 생성은 제안/검증 항목이고 Redis AI Cache는 MVP 보류 권고다. 이를 확정 기술로 올리지 않는다.

동료 명세의 실제 경로·버전과 D-03/D-06 등 세부 대응은 TBD다. 없는 명세 내용을 작성하거나 가짜 링크를 만들지 않는다. 확인 가능한 근거가 들어오면 해당 책임 문서와 결정 로그에 함께 연결한다.

## 변경 순서

audit §10에 따른 아래 순서는 **문서 개정 의존 순서**다. 기능 전체 개발 일정이나 완료 판정이 아니다. 실제 단계별 변경·검증·남은 연결은 [revision-progress](../ref/revision-progress.md)에 기록한다.

| 단계 | 적용 문서 | 목적 |
| --- | --- | --- |
| 1 | ADR-004 | GPT-Live/B안·버퍼·구간/전사/검사·교정 |
| 2 | ADR-005·session-state | 종료 A안·취소·재생·앱별 복구·시간 |
| 3 | protocol·sequences | 기기/공급자/앱 간 계약과 실제 순서 |
| 4 | ADR-002·ADR-008·auth 및 사용자 지정 ADR-003 | 기술·Google·기기/내부 인증·클라이언트 |
| 5 | data-model·result-pipeline 및 신규 ADR-010 | 쓰기/스키마·영속 전달·Worker/결과 |
| 6 | ADR-006·ADR-007·system-architecture·ADR-001 영향 조항 및 신규 ADR-009 | 실행·빌드·배포·자원·책임/그림 |
| 7 | first-bolt | 작은 흐름·POC 여섯 항목·미검증 구분 |
| 8 | decision-log·README 및 전체 읽기 점검 | 결정 대체 관계·TBD·목록·연결/키워드/링크 |

이후 변경도 ① 담당 ADR에 근거·결정/Proposed/TBD/검증 상태 기록 → ② 영향받는 상세 설계 반영 → ③ 결정 로그·목록·진행 기록 연결 순서를 따른다. 이번 단계의 수정 범위 밖 불일치는 진행 기록의 미해결 목록에 남긴다.

1~7단계의 다음 문서 반영 요청은 현재 책임 문서와 위 목록으로 연결됐다. 일부 기존 문서의 `예정/후속 개정`은 해당 단계 당시의 기록으로 읽는다. ADR-007의 복수 공급자 계정 연결 Pending 표현은 현행 Google 범위와 이력의 구분이 더 필요하고, ADR-005에는 현재 폴더에 없는 과거 요구사항 파일 링크가 남아 있다. 해당 원문 수정은 이번 범위 밖이며 [8단계 진행 기록](../ref/revision-progress.md#8단계)에 위치·미해결 처리를 기록한다. 실제 POC·상세 계약 완료로 해석하지 않는다.

첫 연결은 두 앱·Google·고정 ID·주 연결·게이트·재생/회복을 작은 합성·성인 흐름에서 확인한다. POC는 **구간 분할 / 음성·전사 대응 / 단계별 승인 후 첫 재생 / 취소·늦은 승인 / 차단 후 대화 / 무응답·Cue 예산**을 포함한다. 10초는 앱이 정한 질문/안내 전체의 실제 재생 완료 이후이며, 게이트 대기를 아동 무응답으로 세지 않는다. 5초 달성 여부는 아직 측정하지 않았다.

첫 2주 계획은 제안이다. 전체 결과/목표/연습·Cue 구현과 전사 수정/검수·운영 도구는 후속이며, **미검증 / 첫 구현 미구현 / 제품 기능 제외**를 구분한다. 문서 정적 검증을 Java/DB/WSS·GPT-Live 실연동, 장애/보안/품질·부하 시험으로 보고하지 않는다. 미정 값·정책은 담당 문서와 [결정 로그](decision-log.md)에 유지하고, 1~7단계에서 넘어온 실제 시험·G-02/G-03·물리 계약은 완료로 채우지 않는다.
