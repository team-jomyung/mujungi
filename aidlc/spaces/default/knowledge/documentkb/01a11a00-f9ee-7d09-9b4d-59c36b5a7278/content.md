① 개정: Core/Realtime 두 Spring 실행·빌드 단위, 앱별 스키마·쓰기 소유권, Google 로그인, GPT-Live 주 연결·릴레이·버퍼 게이트 기본값을 반영했다. 문서 연결의 현재 완료 상태를 정리했다.
② 대체: 단일 Spring·3개 소셜 로그인·기기 직접 음성 연결과 SDP/Sideband 검증은 대체된 이전 선택으로 남겼고 기존 Flyway·서비스 세션·테스트/Adapter 원칙을 보완했다. 문서 반영을 아직 예정으로 적은 표현은 현재 상태로 대체했다.
③ 남은 TBD: Java/Boot/Gradle·SDK 호환성, 스키마명·권한·Flyway 실행 상세, 기기/내부 인증, 출력 분할·전사 대응·버퍼/검사·재생 보고·부하 POC. 문서 연결 완료와 구분해 유지한다.

# ADR-002 기술 기본값

- 상태: **Proposed**
- 부분 확정: **Neo4j 채택 Accepted (2026-10-01 팀 결정)**; **GPT-Live 단일 전사·별도 STT 보류 Accepted (2026-10-01 팀 회의, ADR-004)**; **Google만 보호자 소셜 로그인·자체 ID/PW 제외·Spring Security OAuth2 Client·Spring Session JDBC**, **Core/Realtime 2-Spring·PostgreSQL 물리 1개/앱별 스키마·GPT-Live 릴레이 B안**은 audit의 최신 사용자 확정 기준을 적용한다. Java/Spring/Gradle 후보와 구현 검증 상태를 일괄 Accepted로 바꾸지 않는다.
- 관련: [ADR-001 저장소·Git](ADR-001-repository-git.md), [ADR-003 아동 기기 UI](ADR-003-child-device-ui.md), [ADR-004 통신·음성 연결](ADR-004-communication-voice.md), [ADR-005 세션 이벤트·종료·타임아웃](ADR-005-session-events-timeouts.md), [ADR-006 시연 서버·배포](ADR-006-demo-deployment.md), [ADR-007 Spring 모듈 경계](ADR-007-module-boundaries.md), [ADR-008 인증·접근 제어](ADR-008-auth-access-control.md)
- 개정 기준: `ref/mujung-architecture-update-audit-v1.1.md` §2·§4.5·§4.6·§5~8. 보조 v0.2의 실행·쓰기·복구 경계를 적용하되 최신 릴레이·Google 결정이 우선한다.
- 확정 전 확인: Spring Boot 및 필수 라이브러리 호환성, 각 앱의 실행·빌드·스키마 경계와 실연동 POC.

## Context

팀이 동시에 구현하는 프론트엔드, 백엔드, 데이터 저장, 시험, AI POC의 기본 기술을 통일해야 한다. 기술 선택이 기능별로 흩어지면 초기 통합과 CI 구성에 비용이 든다.

이 ADR은 **프로젝트 공통 기술 기본값**을 다룬다. 두 앱은 일반 업무와 실시간 활동의 실행 특성·상태 소유권을 기준으로 나눈다. 통신 흐름, 세션 정책, 배포 토폴로지, 모듈 의존성, 인증 정책과 GPT-Live의 지시문·평가 세부는 담당 문서가 관리한다. 기술 방향의 결정과 실제 구현 성공·성능 검증을 구분한다.

## Decision

### Frontend

```text
React
Next.js
TypeScript
ESLint
Prettier
Vitest
```

한 서비스 안의 아동 접점과 보호자 화면 역할 분리는 [ADR-003](ADR-003-child-device-ui.md)이 정한다. 이 역할 구분을 프론트 실행 앱 2개로 자동 확대하지 않는다. 프론트 2앱·Vite 전환과 추가 호스팅 조합은 별도 결정 대상이다.

`/parent`의 REST 상태·결과 조회와 Polling 구현에는 **TanStack Query v5를 권고**한다. `/device`의 WSS 음성·제어·재생 보고를 대체하는 도구로 사용하지 않는다. Polling 주기와 종료 조건은 [ADR-004](ADR-004-communication-voice.md)를 따른다. 라이브러리 적용은 구현 검증 전까지 Proposed다.

### Backend — Core API Spring + Realtime Activity Spring

기본 후보는 **Java 21 LTS, Spring Boot 4.1.x, Gradle 8.14+ 또는 9.x**이다. Java 및 Spring Boot 버전은 부트캠프 환경과 필수 라이브러리의 호환성을 확인한 뒤 최종 확정한다. Gradle의 두 후보 중 사용할 버전도 빌드 환경에서 확정한다.

**상시 실행 Spring Boot 애플리케이션은 Core API Spring과 Realtime Activity Spring 두 개다.** 각 앱은 별도 실행·빌드 단위를 갖고 component/entity/repository scan과 scheduler 활성 범위를 분리한다. 두 실행/빌드 단위·공통 계약의 문서 경계는 [ADR-001](ADR-001-repository-git.md)·[ADR-007](ADR-007-module-boundaries.md)에 반영했다. 실제 디렉터리·Gradle 프로젝트 이름·공통 계약 배치는 TBD다. 두 앱 안에서는 모듈 경계를 유지하고 필요 없는 서비스 분할을 늘리지 않는다.

| 실행 앱 | 기본 책임·쓰기 소유권 |
|---|---|
| Core API Spring | 보호자 OAuth/서비스 세션·Guardian·아동·동의, 승인 콘텐츠·공통 지식 관리, ResultJob·결과·리포트와 사후 텍스트 AI 호출 |
| Realtime Activity Spring | 기기 WSS·활동 상태/할당·GPT-Live 주 연결·음성 릴레이·출력 분할/게이트·전사·재생 관측, 목표 등록/버전·연습·Cue·실시간 판정 |

Result Worker, Outbox Sender, Inbox Processor는 해당 앱 내부 작업이다. Result Worker를 세 번째 Spring 서버로 배포하지 않는다. Core는 ActivitySession 또는 Realtime 목표 테이블을 직접 수정하지 않고 공개 계약을 사용한다. 공통 라이브러리는 최소 계약·오류·시간·타입으로 제한하며 Entity·Repository·도메인 Service·Boot 전체 설정을 공유하지 않는다. 내부 모듈 경계의 소유 문서는 [ADR-007](ADR-007-module-boundaries.md)이다.

Spring Boot 4.1.x 채택 시 다음 전제를 검증한다.

```text
Java 21
Jackson 3 기준
@MockBean 대신 @MockitoBean 사용
Spring Boot 3.x 예제 적용 전 4.1 호환성 확인
```

첫 볼트에서 두 앱의 빌드·스캔·기동과 Spring Security·Spring Session JDBC, springdoc, Testcontainers, Flyway, JPA/Hibernate를 확인한다. `openai-java` 등 공급자 Java 연동의 GPT-Live 주 WebSocket 지원 범위·운영 버전은 미확정이다. SDK가 지원한다고 가정하지 않고 Adapter 안에서 실제 연결·이벤트·취소/교정 계약을 검증한다. 큰 호환성 문제가 발견되면 Spring Boot 3.x를 대안으로 검토한다. 이 문단은 4.1.x 채택 완료 선언이 아니다.

### 보호자 로그인·세션 — Google만

**보호자 가입·로그인은 Google OAuth/OIDC만 활성 범위로 둔다. 자체 아이디·비밀번호 가입·로그인은 제공하지 않는 기존 정책을 유지한다.** Core의 **Spring Security OAuth2 Client**가 Google 로그인·콜백·사용자 확인을 처리하고 서비스 로그인 상태는 **Spring Session JDBC + PostgreSQL + Session ID Cookie**로 유지한다. Google의 실제 등록값·속성 매핑·콜백·검수·시험은 [ADR-008](ADR-008-auth-access-control.md)·[auth.md](architecture/auth.md)에서 관리한다.

OAuth 2.0/OIDC는 외부 사용자 확인 방식이고 Spring Session은 내부 로그인 유지 방식이다. Google Access Token이나 ID Token을 우리 REST 인증 수단으로 그대로 사용하지 않는다. 내부 `Guardian` 생성·조회·소셜 계정 연결과 서비스 동의·아동 접근 권한은 별도로 처리한다. 로그인 공급자를 하나로 좁힌 것이 내부 서비스 계정 생성을 제거하는 뜻은 아니다. 로그아웃·Session 무효화·Cookie·CSRF와 현재 동의 검사는 유지한다. JWT + HttpOnly Cookie, 외부 Auth0와 별도 Keycloak 서버는 현재 미채택 대안이다. 선택 이유·대안·남은 구현값의 Source of Truth는 [ADR-008 §3.2](ADR-008-auth-access-control.md#guardian-social-login)다.

원본의 Spring Boot 4.1.x 기준 OAuth2 Client Starter 후보는 `spring-boot-starter-security-oauth2-client`다. 실제 Java/Boot 조합과 의존성 이름은 첫 볼트에서 확인하며 다른 버전 예제의 호환성을 가정하지 않는다. 원본이 확인한 [공식 Starter 목록](https://docs.spring.io/spring-boot/reference/using/build-systems.html) (2026-10-01)은 검증 참고 이력이다. 이 개정은 Boot 버전 선택이나 실제 Google 로그인 시험 완료를 뜻하지 않는다.

### 고정 ID 기기와 인증 경계

데모의 허용 기기 ID를 사전 시드한다. 화면 없는 기기의 대체 클라이언트는 운영자가 사전 준비한다. 기기는 Realtime에 대기 WSS로 연결하여 준비·연결 상태와 실제 재생 관측을 보고하고, 점유·활동 할당은 Realtime이 관리한다. 보호자는 상태 카드로 대상 기기 상태를 확인하고 시작을 요청하며, Realtime이 허용 기기·현재 연결·점유·원자 할당·현재 권한을 검사한 뒤 기기에 시작을 push한다. 상태 카드의 상세 표시 후보를 새 DB Enum이나 전체 UI 진행 규칙으로 확정하지 않는다.

고정 ID는 허용 대상을 고르는 데모 축약이며 운영용 인증의 증거가 아니다. 보호자 Cookie를 기기 자격으로 그대로 사용하지 않는다. 실제 기기 인증·접속 검증·연결 교체/만료·메시지의 활동 결합 상세는 TBD이고 [ADR-008](ADR-008-auth-access-control.md)·[auth.md](architecture/auth.md)·[protocol.md](architecture/protocol.md)를 따른다. 통제된 시연 조건과 실제/모의 제품 통신 범위는 별도 확인 대상이다. QR claim·실제품 페어링을 새로 채택하지 않는다.

내부 HTTP와 Outbox/Inbox 수신 모두 앱 간 서비스 인증을 적용한다. 내부망 자체를 인증으로 보지 않으며 Core가 확인한 주체·허용 행위·대상·기한을 Realtime이 활동·기기·현재 권한과 대조한다. 브라우저가 넣은 주체 헤더를 내부 권한으로 승격하지 않는다. 서비스 인증 방식·비밀값 교체·Endpoint/DTO는 TBD이며 인증과 사용자 인가를 분리한다.

### Backend 개발·DB 관리 도구

JUnit 5, Testcontainers, springdoc, Flyway, ArchUnit을 기본 도구로 사용한다. JPA의 스키마 설정은 `ddl-auto=validate`로 두고 DB 스키마 변경은 Flyway Migration으로 관리한다. **Flyway는 원본의 기존 채택이며 이번 개정에서 신규 도입한 도구가 아니다.** 두 앱의 소유 스키마에 맞게 마이그레이션 작성·검토·적용 책임을 보완한다. ArchUnit으로 검증할 앱·모듈 규칙은 [ADR-007](ADR-007-module-boundaries.md)을 따른다.

마이그레이션 적용은 배포의 단일 단계/실행 주체를 정해 관리하고 각 앱은 자신이 사용하는 호환 스키마를 validate한다. 두 앱의 자동 시작이 같은 변경을 경쟁 적용하는 방식으로 두지 않는다. 앱별 스키마명·DB 역할/권한·Migration 위치·적용 순서·이력 설정·호환성 절차는 TBD이며 [data-model.md](architecture/data-model.md)·[ADR-006](ADR-006-demo-deployment.md)에서 상세화한다. 일회성 Migration 실행은 세 번째 상시 Spring 서버가 아니다.

REST 계약에는 **OpenAPI → TypeScript 타입·클라이언트 생성**을 단계적으로 도입하도록 권고한다. 실제 Spring Boot 조합에서 `/v3/api-docs`, 인증·오류 스키마와 공개/내부 HTTP 계약 표현을 검증한다. API 계약이 안정된 뒤 생성 도구와 CI 계약 검사를 채택한다. `openapi-typescript`·`openapi-fetch`·`openapi-react-query`는 검토 후보이며 버전과 생성 범위는 TBD다. 기기 WSS·공급자 이벤트는 별도 계약을 유지하고 Cookie 인증과 CSRF는 [ADR-008](ADR-008-auth-access-control.md)을 따른다.

### Data — PostgreSQL 물리 1개·앱별 스키마

주 운영 데이터베이스는 **PostgreSQL 물리 1개**를 유지하고 Core와 Realtime의 소유 스키마·DB 역할·쓰기 권한을 분리한다.

```text
ID = UUID
DB 시간 = UTC
사용자 화면 = KST
```

Core는 계정·아동·동의·서비스 세션·Job·결과·리포트, Realtime은 활동·전사·목표/버전·연습·판정과 음성/재생 관측 참조의 쓰기를 소유한다. 각 앱의 Outbox/Inbox는 해당 앱이 소유한다. 물리 DB가 같다는 이유로 상대 Repository 또는 테이블을 직접 수정하지 않는다. 교차 조회는 공개 Query 계약을 기본으로 하고 읽기 전용 View 등 예외의 허용 필드·권한·결합은 별도 설계 대상이다. 정확한 스키마명·컬럼·권한은 TBD다.

전사·결과·재생 참조와 Outbox/Inbox의 최소 데이터는 동의·보존·삭제 정책을 따른다. 중계용 실시간 원음은 임시 메모리에만 두고 폐기하며 Outbox·Inbox·DB·일반 로그·디스크에 우회 저장하지 않는다. 기존 승인 안내 음성 파일과 실시간 원음의 보존 범위를 무조건 같게 묶지 않는다. 메시지 재전송으로 삭제/철회 자료를 복원하지 않도록 현재 이용 가능성을 검사하고, 철회 효력 시각·저장/송신 경합의 원자성 상세는 TBD로 둔다.

**Neo4j를 화용 지식 그래프·온톨로지 저장소로 채택한다. 2026-10-01 팀 결정으로 확정(Accepted)했다.** Core의 공통 지식 관리/조회 경계를 유지한다. Realtime은 준비된 지식 버전을 사용하고 필요한 추가 조회는 제한된 비동기 준비 경로에 둔다. STOP·heartbeat·재생 보고가 매번 Neo4j 조회를 기다리지 않는다. 그래프 모델과 연동의 상세 설계·검증은 [data-model.md](architecture/data-model.md)에서 다룬다.

**Redis AI Cache는 MVP에 도입하지 않는 방향을 권고**한다. 개인화 요청의 캐시 적중률이 낮을 수 있다는 판단은 측정 전 예상이며, Redis 운영 비용과 아동 데이터의 보존·삭제 정책을 함께 고려해야 한다. 반복 호출량·적중률·지연을 실측해 필요성이 확인되면 별도 결정으로 재검토한다.

### 앱 간 즉시 명령과 영속 전달

활동 시작·종료·준비 취소·상태/허용 자료 조회·목표 등록은 인증된 내부 HTTP 계약을 사용한다. 시작 접수의 짧은 트랜잭션에서 중복 확인·기기 할당·`PREPARING`과 접수 기록을 커밋하고, 미디어 준비와 아동 참여를 기다리지 않고 접수 응답을 반환한다. 응답 유실은 같은 요청 식별자로 재확인한다. STOP은 사후 Worker 대기열 뒤에 넣지 않는다.

유실되면 안 되는 작업 요청·완료·정책 변경은 **PostgreSQL Transactional Outbox + 내부 HTTP + 수신 멱등성/Inbox**로 전달한다. 초기 Broker는 추가하지 않는다. 발신 업무 변경과 Outbox를 로컬 트랜잭션에 함께 기록하고, 수신자는 업무 반영 또는 Inbox 영속 저장 뒤 수신 ACK를 보낸다. Inbox 후처리의 업무 변경과 처리완료 표시는 원자 반영하며 미처리 자료를 복구한다. ACK 유실·중복을 허용하되 같은 업무 효과를 반복하지 않는다. 즉시 명령 접수·영속 수신 ACK·업무 완료·실제 기기 재생은 서로 다른 사실이다.

요청·영속 이벤트·실시간 작업·결과 run의 논리 식별자는 목적에 맞게 분리한다. 종료/쉬기로 실시간 작업을 무효화해도 허용된 정상 사후 결과를 자동 폐기하지 않으며 기대한 결과 run·입력 버전·현재 권한을 확인한다. 계약의 상세 필드·재전송·선점/lease·보존은 [protocol.md](architecture/protocol.md)·[result-pipeline.md](architecture/result-pipeline.md)에서 다룬다. 외부 AI의 정확히 한 번 실행이나 영구 장애의 자동 성공을 보장하지 않는다.

유효 기록이 있는 중도 종료의 부분 결과 요구를 유지한다. Realtime의 활동 `PARTIAL`과 Core의 결과 준비/실패는 별도 축이며 늦은 결과로 활동을 되살리지 않는다. 첫 볼트의 결과 기능 미구현을 제품 범위에서의 제외로 바꾸지 않는다. 상세 데이터/API는 후속 결과 문서에서 정한다.

### Python과 외부 서비스·Adapter 경계

`ai/`는 기본적으로 운영 서버가 아니다. AI POC, 평가 실험, 회귀 시험, 말뭉치·온톨로지 데이터 가공, 모델 관련 실험에 사용한다. 운영 API 호출은 기본적으로 Spring에서 수행한다. 사후 텍스트 AI 호출은 Core, GPT-Live 연결과 실시간 처리/검사 경계는 Realtime에 둔다.

변경 가능성이 높은 외부 기술에는 서비스 도메인이 직접 의존하지 않도록 Adapter 경계를 둔다. `LiveVoiceAdapter`, `LlmClient`, `KnowledgeAdapter`는 **후보 이름**이며 구체 인터페이스와 의존 방향은 [ADR-007](ADR-007-module-boundaries.md)이 정한다. 공급자 Java SDK 직접 사용을 검토·권고하되 GPT-Live 주 연결을 실제로 지원하는 SDK 버전·전송 구현은 First Bolt에서 확인한다. SDK 타입과 호출은 공급자 Adapter 안에 격리한다. 라이브러리의 이름만으로 연결·이벤트 지원을 확정하지 않는다.

모델 입력의 역할별 정보 제한은 출력 게이트와 함께 유지한다. 보호자 원문·채점 기준·타 아동 자료를 GPT-Live 대화 역할에 보내지 않으며 출력 검사를 이유로 입력 제한을 완화하지 않는다. 승인 콘텐츠/지식 버전은 활동 전에 확보하고 마지막 인사·STOP이 Core의 즉석 조회를 기다리지 않게 한다.

### GPT-Live 주 연결·릴레이 B안·출력 버퍼 게이트

대상 공급자는 **GPT-Live API(`gpt-live-1`)**다. Realtime Activity Spring은 우리 앱 이름이며 공급자 제품 이름과 구분한다. 기기↔Realtime의 WSS는 음성·제어·기기 재생 보고를 전달하고, Realtime↔GPT-Live는 주 WebSocket으로 음성을 중계한다. 서버가 공급자 키와 연결을 소유하고 기기에 공급자 직접 연결 권한을 주지 않는다. 기기 제어용 대기 연결과 유료 모델 연결의 수명을 구분한다.

audit §2.1·§5.1의 주 연결 기준은 `wss://api.openai.com/v1/live/sessions` 접속 → `session.start` → `session.started` 확인이다. 모델/설정은 시작 메시지에 둔다. 입력/출력 음성·전사 이벤트와 기기 메시지 계약은 [ADR-004](ADR-004-communication-voice.md)·[protocol.md](architecture/protocol.md)을 따른다. 공급자의 발화별 완료·재생 시각 필드를 가정하지 않고 공급자 종료·취소·교정의 실제 SDK 계약은 검증한다.

출력은 Realtime 또는 그 미디어 Adapter가 **앱 구간 분할 → 출력 전사 대응 → 상태·권한·작업 최신성 및 필요한 경량 의미 검사 → 동일 음성 승인 → 전달 직전 재확인 → 기기 전달** 순서로 처리한다. 승인 전 음성은 임시 메모리 버퍼에 두고 전사/대응 불명확·검사 실패·누락·시간초과 때 폐기와 대체 안내 경로로 간다. 검사 수행은 전달 조건이며 검사가 모든 위험을 발견한다는 보장은 아니다. 분할 알고리즘/라이브러리·전체 질문/안내 완료 기준·검사 기준(모델은 제브 가드레일, 기술명세서 v0.3 §7.7)·버퍼 한도·대체 안내/재생성 조건은 TBD다.

끼어들기·쉬기·종료·철회를 구분하여 서버 버퍼와 기기 대기열을 취소하고 늦은 승인으로 재생을 재개하지 않는다. 폐기 음성도 모델 맥락에 남으므로 회복 경로는 앱이 작성한 교정 지시를 `session.instructions.append`로 보내는 계약에 연결한다. 교정 ACK는 실제 교정 성공·재생 승인이 아니며 새 출력도 다시 게이트를 통과한다. 미승인 원문을 시스템 지시로 그대로 복사하지 않는다.

상태·권한·최신성과 필요한 경량 의미 검사는 게이트에서 수행한다. 무거운 교육적 판정·KG/GraphRAG를 동기 게이트 경로에서 분리하는 것은 **우리 설계 권고**이며 필수 안전 검사를 사후로 미루는 뜻이 아니다. delegation은 공급자 지원·역할 경계를 확인할 항목으로만 남기며 현재 채택 기술이나 게이트 대체 기능으로 두지 않는다.

`playbackMark`는 기기 플레이어가 관측한 실제 재생 범위를 Realtime이 수신·검증하는 앱 계약이다. 생성·승인·송신·실제 재생 범위를 분리하며 실제 청취·이해를 증명하지 않는다. 활동·현재 연결·승인 음성/재생 묶음에 결합하고 보고가 없으면 마지막 신뢰 관측과 확인 불가를 유지한다. 필드·단위·송신 시점·늦은 보고와 공급자 맥락 반영은 TBD이며 [protocol.md](architecture/protocol.md)의 계약을 따른다.

### MVP 음성·전사 공급원 — Accepted

**2026-10-01 팀 회의에서 GPT-Live만으로 실시간 대화와 전사를 처리하고 별도 STT는 보류하기로 확정했다.** 새 STT의 조사·선정·연동·검증을 수행할 일정이 부족하므로 현재 운영 서비스와 First Bolt에 추가하지 않는다. 별도 STT의 서비스 내 비교·시험 목적, 보류 이유, 단일 전사의 한계와 후속 재검토 조건은 [ADR-004 §2.14](ADR-004-communication-voice.md#mvp-transcription)이 소유한다. 이 결정은 비실시간 AI 모델 배치를 모두 GPT-Live 하나로 통합한다는 뜻이 아니다.

### CI·CD와 First Bolt 검증

GitHub Actions에서 두 앱의 build, test, lint, ArchUnit 검사를 실행한다. CI는 실제 유료 AI API를 기본적으로 호출하지 않고 Fake/Mock Adapter를 사용한다. OpenAPI 기반 TypeScript 생성 방식이 확정되면 스키마와 생성 결과의 불일치 검사도 CI에 추가한다.

첫 볼트는 Core→Realtime 활동 접수·기기 준비·참여·종료·상태 조회, Google 로그인·서비스 세션/CSRF, 고정 기기 대기 WSS, 앱별 스키마/Flyway·쓰기 경계와 개별 재시작을 검증한다. 빈 Boot 두 개의 기동만으로 완료라고 하지 않는다. Fake와 실공급자 Adapter를 구분하여 응답 유실·중복·영속 수신 후 크래시·늦은 결과를 확인한다. 출력 구간/전사 대응·게이트 전달/취소·차단 후 교정·실제 재생 보고와 지연 POC는 [first-bolt.md](architecture/first-bolt.md)에서 구체화한다. 합성·성인 시험부터 진행하며 이 문서 개정은 시험 수행 증거가 아니다.

Core는 자신의 Job/Outbox/Inbox를, Realtime은 자신의 활동/연결/버퍼와 영속 수신을 복구한다. Core 재시작이 정상 Realtime 활동을 일괄 변경하지 않는다. Realtime 메모리 버퍼는 복원하지 않고 미확인 재생을 성공으로 채우지 않으며 종료된 음성 활동을 자동 재개하지 않는다. Job 복구는 소유권/lease와 이전 실행의 반영 차단을 확인한다. 세부 상태는 [ADR-005](ADR-005-session-events-timeouts.md)를 따른다.

자동 CD는 MVP 필수 범위가 아니다. `deploy.sh` 기반 수동 배포를 기본값으로 한다. 두 앱·DB·Neo4j의 배포/마이그레이션·비밀값·복구 절차는 [ADR-006](ADR-006-demo-deployment.md)이 정한다. CloudFront 등 새 호스팅 조합을 이 개정에서 추가하지 않는다.

## Alternatives

### 대체된 이전 선택: 단일 Spring·브라우저 직접 음성·3개 소셜 로그인

원본은 단일 Spring Boot Modular Monolith를 선택했고 브라우저↔GPT-Live 직접 WebRTC·SDP Relay·브라우저 Data Channel·Sideband와 Java Sideband 구현을 기본 검증 범위로 두었다. 현재는 Core/Realtime 두 실행 앱과 Realtime 주 WebSocket 음성 릴레이·버퍼 게이트로 대체했다. 브라우저 공급자 임시 키·Ephemeral 권한 경로도 현행 기본값으로 사용하지 않는다. 이 문단은 비교 이력이며 현재 구현 지시가 아니다.

원본의 카카오·구글·네이버 3개 로그인 선택은 Google만 활성화하는 최신 결정으로 대체했다. 제외한 공급자의 등록값·속성 매핑·콜백·검수·시험을 현행 작업 범위에 넣지 않는다. 자체 ID/PW 제외·Guardian 계정·Spring Session JDBC·로그아웃·CSRF는 계속 유지한다.

구형 기기 단기 Cookie·운영자 일회용 발급 코드와 전역 기기 수 기반 시작 판단은 고정 ID 사전 시드·대기 WSS·상태 카드·보호자 시작 push·대상 기기의 현재 연결/점유/원자 할당 검사로 대체한 이력이다. 고정 ID만으로 운영 인증을 완료했다는 근거로 사용하지 않는다.

### Spring Boot 3.x

Spring Boot 4.1.x와 필수 라이브러리의 호환성에 큰 문제가 있으면 선택할 대안이다. 첫 볼트 검증 이전에는 4.1.x 채택 여부를 확정하지 않는다.

### PostgreSQL Table로 지식 관계 관리 — 미채택 대안

온톨로지 관계를 PostgreSQL Table에 저장하여 운영 저장소를 단순하게 유지하는 대안을 검토했다. 2026-10-01 팀 결정으로 지식 관계 저장소는 Neo4j를 채택했다. PostgreSQL Table 대안은 비교 이력으로 남기며 현재 구현의 선택 후보로 두지 않는다. PostgreSQL 물리 1개라는 결정으로 Neo4j를 삭제하지 않는다.

### `ai/`를 별도 운영 API 서버로 배포

현재 MVP의 기본값으로 선택하지 않았다. 운영 API 호출은 두 Spring 앱의 소유 책임에 두고 AI 실험 코드는 분리한다. 별도 서버가 필요한 요구가 생기면 새 결정으로 기록한다.

### MVP에서 자동 CD 구축

시연 배포의 필수 조건으로 두지 않았다. 수동 배포와 CI가 현재 기본값이며 자세한 운영 판단은 [ADR-006](ADR-006-demo-deployment.md)에 둔다.

### Spring AI 추상화와 Redis AI Cache

GPT-Live 주 연결·이벤트를 직접 제어할 수 있는 공급자 Adapter를 유지하고 `openai-java` 직접 사용의 지원 범위를 확인한다. Spring AI 자체의 지원 여부를 부정하는 선택은 아니며 필요한 기능이 확인되면 별도로 비교한다. Redis AI Cache는 반복 요청과 적중률이 실측되기 전에는 운영 구성에 추가하지 않는다.

## Consequences

- 공통 도구와 앱별 빌드·검사 경로를 맞출 수 있다. CI가 서비스 코드와 앱/모듈 경계를 함께 확인한다.
- Java·Spring Boot·Gradle과 관련 라이브러리 조합은 첫 볼트에서 검증해야 한다. 문제가 크면 버전과 예제 적용 방식을 바꿔야 한다.
- 두 앱은 일반 업무/실시간 활동의 실행·상태 소유권을 구분하지만 내부 HTTP·공유 DB/호스트·현재 권한·콘텐츠 의존은 남는다. 분리만으로 처리량·독립 가용성이 입증되지는 않는다. Core 장애 중 보호자의 직접 Realtime 종료 경로는 미확정이다.
- Google만 활성화해 공급자 구현 범위를 좁히되 Guardian 계정·세션·CSRF·관계/동의와 로그아웃 검증을 유지해야 한다.
- 기존 Flyway Migration과 `ddl-auto=validate`를 앱별 스키마/권한에 맞춰 관리해야 한다. Migration 실행 주체·호환성·순서를 검토해야 한다.
- Outbox/Inbox는 재전송·수신 복구·중복 효과 방지를 위한 작업과 보존 정책을 추가한다. 전송 재시도와 새 AI 생성 시도를 구분해야 한다.
- Neo4j가 공통 지식을, PostgreSQL이 서비스 운영 데이터를 담당한다. 버전·연동·배포·초기화·백업을 관리해야 한다.
- Realtime이 입력/출력 음성·메모리 버퍼·분할/검사·전사·재생 보고를 맡아 부하와 지연을 측정해야 한다. 두 앱의 DB pool·스레드·메모리·외부 모델 예산을 합산하고 지원 동시 활동 수를 실측한다.
- 버퍼 게이트는 검사 후 승인 음성만 전달하지만 전사 정확성·위험 발견·지연 달성을 보장하지 않는다. 차단 후 모델 맥락과 실제 제공 사실의 차이를 회복 경로에서 다뤄야 한다.
- 기기 재생 보고는 실제 출력 관측이며 청취·이해의 보증이 아니다. 미보고와 늦은 보고를 성공으로 보정하지 않아야 한다.
- 수동 배포는 배포 실행과 확인 절차를 사람이 수행해야 한다. TanStack Query는 Polling의 중단·갱신을 모으지만 Observer·백그라운드 동작은 실제 화면에서 확인해야 한다.
- 공급자 SDK 변경은 Adapter에서 관리한다. OpenAPI 생성은 안정된 REST 스키마와 생성·검사 절차가 필요하다.
- Redis AI Cache를 제외해 운영 구성은 단순해지며 성능 개선이 필요하면 실측 근거로 다시 판단해야 한다.

## Pending

- **기술 검증 후 팀 결정:** Java 21·Spring Boot 4.1.x 조합의 최종 채택 또는 큰 호환성 문제 시 Spring Boot 3.x 전환. Gradle 8.14+와 9.x 중 버전 확정.
- **두 실행/빌드 경계:** 실제 프로젝트·공통 계약 배치, 각 Boot의 scan/scheduler 범위, CI와 배포. Core/Realtime 방향 자체를 다시 미정으로 두지 않는다.
- **DB 관리 보완:** 앱별 스키마명·DB 권한·교차 조회 예외·Flyway 위치/이력·적용 주체/순서·호환성·복구. 기존 Flyway 채택을 신규 선택으로 다시 묻지 않는다.
- **상세 설계·구현 검증:** Neo4j 그래프 모델·지식 버전·Adapter 연동과 운영 검증.
- **인증 구현:** Google 가입/로그인·Guardian 연결·Spring Session JDBC·로그아웃·CSRF·현재 동의, 기기/내부 서비스 인증과 연결/활동 결합. 보호자 로그인·서비스 세션의 기본 선택은 다시 Pending으로 두지 않는다.
- **공급자·게이트 POC:** GPT-Live 운영 모델/Java SDK·주 WSS·입출력/전사 이벤트·종료/취소/교정, 출력 분할·음성/전사 대응·질문/안내 전체 완료, 검사 기준(모델은 제브 가드레일, 기술명세서 v0.3 §7.7)·버퍼 한도·대체 안내/재생성 조건·playbackMark 필드/단위/송신·늦은 보고·공급자 맥락 반영.
- **영속 전달·복구:** 내부 Endpoint/DTO·인증·Outbox/Inbox의 수신/업무 원자성·중복·선점/lease·보존과 결과 run 분리. 원음은 영속 메시지에 넣지 않는다.
- **미해결 권한/가용성:** Core 장애 중 보호자 직접 Realtime 종료(G-02), 철회 효력 시각·저장/송신 경합(G-03), 고정 ID 데모의 실제/모의 통신 범위.
- **프런트엔드 적용:** `/parent` TanStack Query·상태별 Polling 중단·재시도 후 무효화·백그라운드 동작. 원본 1~2초 간격은 ADR-004의 초기 후보이며 확정값이 아니다.
- **REST 계약 생성:** Boot/springdoc 호환성·`/v3/api-docs` 인증/오류·공개/내부 HTTP 계약을 확인한 뒤 TypeScript 생성 도구·범위·CI 검사를 결정한다.
- **성능 검증:** 중계량·버퍼/검사·두 앱의 합산 자원, 무응답 10초와 Cue 5초 초기 예산의 실제 달성 여부. 출력 구간/전사/게이트 대기를 무응답으로 세지 않으며 Cue 예산에는 포함한다. 수치와 지원 사용자 수를 새로 만들지 않는다.
- **캐시 재검토:** AI 요청량·반복률·적중률·지연이 측정되기 전에는 Redis를 추가하지 않는다. delegation은 확인 항목이며 미채택이다.

세부 계약의 Source of Truth: 음성 연결은 [ADR-004](ADR-004-communication-voice.md), 세션 정책은 [ADR-005](ADR-005-session-events-timeouts.md), 배포는 [ADR-006](ADR-006-demo-deployment.md), 모듈 경계는 [ADR-007](ADR-007-module-boundaries.md), 인증·접근 제어는 [ADR-008](ADR-008-auth-access-control.md)이다. API·WSS 메시지·ERD 등 구현 상세는 `architecture/`에서 다루며 GPT-Live의 지시문·대본·명령어·키워드·평가 기준은 담당 상세 명세를 참조한다. 저장소/모듈/배포·데이터/결과·시험 계획 문서의 현행 결정 연결은 반영했다. 실제 구현·물리 계약·실연동/측정의 남은 TBD는 담당 문서와 진행 기록에서 관리한다.
