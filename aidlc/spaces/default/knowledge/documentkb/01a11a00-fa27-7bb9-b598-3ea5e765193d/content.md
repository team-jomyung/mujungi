① 개정: 실행 특성·상태 소유권에 따라 Core API Spring과 Realtime Activity Spring을 분리하는 방향과 모듈·쓰기·통신·자원·복구 경계를 신규 ADR로 정리했다. 문서 연결의 현재 완료 상태를 정리했다.
② 대체: 원본 단일 Spring의 실행 경계는 대체된 이전 선택으로 남기고 Monorepo·도메인/Repository 소유권·Port 원칙·한 Next.js 두 접점·Neo4j를 보존했다. 문서 반영을 아직 예정으로 적은 표현은 현재 상태로 대체했다.
③ 남은 TBD: Gradle/scan/scheduler·API/스키마/Flyway·내부/기기 인증·게이트/재생 보고·SDK·lease/fencing·Core 장애 제어/철회 경합·실연동과 처리 용량. 문서 연결 완료와 구분해 유지한다.

# ADR-009 실행 경계 — Core + Realtime

- 상태: **Proposed**. Core/Realtime 두 Spring 실행 앱은 audit가 적용하는 현행 방향이며 선택 자체를 미정으로 되돌리지 않는다. 구체 구현·운영/부하 시험 완료나 기존 ADR 전체의 Accepted 전환을 뜻하지 않는다.
- 관련: [ADR-001 저장소·Git](ADR-001-repository-git.md), [ADR-002 기술 기본값](ADR-002-technology-defaults.md), [ADR-004 음성·통신](ADR-004-communication-voice.md), [ADR-005 상태·복구](ADR-005-session-events-timeouts.md), [ADR-006 배포](ADR-006-demo-deployment.md), [ADR-007 모듈 경계](ADR-007-module-boundaries.md), [ADR-008 인증](ADR-008-auth-access-control.md), [ADR-010 영속 전달](ADR-010-durable-delivery.md), [시스템 아키텍처](architecture/system-architecture.md)
- 유일한 개정 기준: `ref/mujung-architecture-update-audit-v1.1.md` §2·§3·§5·§7~9. 보조 근거는 `ref/2-spring-architecture-v0.2.md` §§2~11과 `ref/2-spring-cross-validation-v0.2.md` CV-01~04·07~11·13~14다.
- 문서 범위: 실행 경계의 맥락·결정·이유·대안·결과. 상세 메시지·DB 모델·결과 생성·프로세스 배치/라우팅은 각 책임 문서가 구체화한다. 없는 필드·숫자·공급자 API 기능을 이 ADR에서 만들지 않는다.

## 1. Context

원본은 한 Spring Boot 안에 보호자 업무·활동 상태·음성 제어·전사·연습·판정·사후 결과 Worker를 모듈로 나누었다. 도메인 소유권과 공개 Port/Event 원칙은 있었지만 REST·긴 AI 대기·실시간 연결·시간 관리·백그라운드 작업의 실행/재시작 범위를 하나의 프로세스가 공유했다.

v0.2는 일반 업무와 실시간 활동을 두 실행 앱으로 구분했다. 최신 사용자 선택은 Realtime이 기기 음성과 GPT-Live API(`gpt-live-1`)를 주 WebSocket으로 중계하고, 출력 음성을 앱 구간 분할·전사 대응·검사/승인 전 메모리 버퍼에 보관하는 B안이다. 음성 입출력·버퍼/검사·재생 관측·취소 책임이 Realtime 실행 경계에 포함된다.

구조를 둘로 나누어도 활동/목표의 쓰기 소유권·Core 결과 Worker·현재 동의/콘텐츠 의존·같은 DB/호스트 자원 경쟁은 남는다. 이 ADR은 어떤 실행과 상태를 어느 앱이 소유하고 앱 사이를 어떻게 연결할지 정한다. 두 앱이라는 수를 처리량 향상·모든 기능의 독립 가용성 증거로 사용하지 않는다.

## 2. Decision

### 2.1 상시 Spring 실행 앱 두 개

**Core API Spring과 Realtime Activity Spring을 각각 실행·빌드 단위로 둔다. 분리 기준은 실행 특성·상태 소유권이며 부모/아동 또는 AI/비AI 구분이 아니다.**

| 실행 앱 | 실행 특성·책임 | 수정 소유권 |
| --- | --- | --- |
| Core API Spring | 보호자 REST·Google OAuth/서비스 Session·CSRF, 관계/현재 동의·승인 콘텐츠/공통 지식 관리, 사후 텍스트 AI와 Core 내부 Result Worker | Guardian/Google 연결·Child/관계·Consent·framework Session·승인 원본/버전·ResultJob/결과/리포트·자신의 Outbox/Inbox |
| Realtime Activity Spring | 기기 대기/활동 WSS·음성/제어·GPT-Live 주 연결/릴레이, 구간 분할/전사 대응·버퍼/게이트·취소/재생 관측, 짧은 활동 처리/타이머·연습/판정 | 기기/현재 연결/할당·ActivitySession·전사/Turn·목표 정의/버전/선택·연습/상황/Cue/판정·자신의 Outbox/Inbox |

Core에도 사후 AI가 있고 Realtime에도 실시간 검사/판정 Adapter가 있다. 보호자 요청은 Core에서 검증하여 Realtime 명령/조회로 연결하므로 요청 주체 이름만으로 모든 기능의 실행 앱을 정하지 않는다. 각 앱 안에서는 도메인 모듈 경계와 공식 변경 진입점을 유지한다.

**Result Worker는 Core 내부이고 Outbox Sender·Inbox Processor는 각 소유 앱 내부 작업이다.** 세 번째 상시 Spring이나 공통 AI 서버를 자동 추가하지 않는다. 초기 Broker도 추가하지 않는다. Neo4j는 Core가 관리/조회하는 공통 화용 지식 저장소로 유지한다.

### 2.2 저장소·빌드·공통 코드

Monorepo·Git/PR·민감자료 규칙은 [ADR-001](ADR-001-repository-git.md)을 유지한다. 같은 저장소 안에 두 실행/빌드 단위를 두고 각 Boot의 **component/entity/repository scan과 scheduler 활성 범위**를 분리한다. 상대 도메인의 Entity/Repository/Service·스케줄러가 우연히 함께 활성화되는 광범위 스캔을 쓰지 않는다. 실제 디렉터리·Gradle 프로젝트/플러그인·패키지/스캔 설정·공통 계약 위치는 TBD다.

공통 라이브러리는 최소 계약 DTO·오류·시간·타입 등으로 제한한다. **Entity·Repository·도메인 Service·Boot 전체 설정을 공유하지 않는다.** 공급자 SDK 타입도 도메인/공통 계약으로 새어나가지 않도록 앱 소유 Adapter에서 변환한다. 호출자의 Port 소유/Adapter 구현 방향은 ADR-007을 따른다.

빌드·테스트·ArchUnit은 앱 경계와 모듈 소유권을 함께 확인한다. 기본 CI는 Fake/Mock 공급자 Adapter를 사용하고 유료 AI 호출을 제외한다. 실제 Java/Spring/Gradle 후보·SDK 지원 상태는 ADR-002의 Proposed/TBD를 유지하며 이 ADR에서 버전을 새로 확정하지 않는다.

프론트는 기존 **한 Next.js 앱의 `/parent`·`/device` 두 접점**을 유지한다. 화면 없는 기기의 대체 클라이언트와 운영자 사전 준비/아동 음성 사용은 ADR-003을 따른다. 두 백엔드 앱을 프론트 두 앱·Vite·CloudFront 전환의 근거로 확대하지 않는다.

### 2.3 PostgreSQL 물리 1개·앱별 스키마·쓰기 소유권

PostgreSQL은 물리 1개를 유지하며 Core/Realtime의 소유 스키마·DB 역할/쓰기 권한을 분리한다. Core는 Realtime의 ActivitySession·목표 테이블을 직접 수정하지 않으며 Realtime도 Core의 Job/결과/동의 테이블을 직접 수정하지 않는다. **상대 Repository 직접 접근이나 테이블 UPDATE 대신 공개 Query·명령·등록 계약**을 사용한다.

교차 조회는 공식 계약을 기본으로 한다. 읽기 전용 View 등 예외는 허용 필드·권한·스키마 결합을 별도 설계한다. 스키마명·계정/권한·물리 FK/제약·추천 산출물/근거의 앱별 분해는 TBD다. 물리 DB가 같다는 이유로 앱 간 업무를 하나의 분산 원자 커밋으로 가정하지 않는다.

Flyway는 기존 채택을 두 앱/스키마에 맞게 보완한다. 소유 스키마의 마이그레이션 작성·검토 책임과 **배포의 단일 적용 단계/실행 주체**, 각 앱의 호환 스키마 validate를 연결한다. JPA의 `ddl-auto=validate`는 유지한다. 실제 파일/이력/순서·실행 절차는 ADR-006/data-model의 TBD이며 양 앱 시작이 같은 변경을 경쟁 적용하지 않는다. 일회성 마이그레이션은 세 번째 상시 Spring이 아니다.

Neo4j에는 공통 화용 지식을 두고 개인 전사·아동별 목표·결과는 PostgreSQL 운영 자료로 관리한다. 개인 목표를 공용 그래프에 자동 승격하지 않는다. 그래프 상세 모델·초기 데이터/버전·Adapter와 백업은 후속 검증이다.

### 2.4 두 종류의 앱 간 전달

| 계약 | 용도·보장 경계 |
| --- | --- |
| 인증된 내부 HTTP 즉시 명령/조회 | 활동 생성·종료·준비 취소·상태/허용 자료 조회·원격 목표 등록. 짧은 접수·같은 요청 재확인, STOP 우선 처리 |
| Transactional Outbox + 내부 HTTP Sender + 영속 Inbox/수신 멱등성 | 유실되면 안 되는 결과 요청/완료·정책 변경. 발신 업무/Outbox 로컬 커밋, 수신 영속 커밋 뒤 ACK, 업무 효과/Inbox 처리완료 원자 반영·미처리 복구 |

기기 할당·`PREPARING`·중복 확인·접수 기록을 짧게 커밋하면 시작 접수를 반환하고 준비/참여는 별도로 처리한다. 응답 유실은 같은 요청으로 재확인한다. STOP은 사후 Worker 대기열에 넣지 않으며 긴 AI/HTTP 대기를 상태 처리기에서 수행하지 않는다.

Outbox/Inbox 송신·수신은 각 앱 소유 로컬 트랜잭션이다. 같은 이벤트 재전달·ACK 유실을 허용하면서 같은 업무 효과를 반복하지 않는다. 영속 수신 ACK는 처리 책임의 수락이며 목표 등록/결과 준비·AI 성공·실제 재생 완료가 아니다. 단순 프로세스 내부 Spring 이벤트를 다른 JVM 전달 수단으로 사용하지 않는다. 상세 원자성·중복·순서·선점/lease·재전송/보존·Endpoint/DTO·인증은 [ADR-010](ADR-010-durable-delivery.md)·[protocol](architecture/protocol.md)의 TBD다.

Core Worker는 허용 근거로 목표 4요소/추천·결과를 생성/검사하고 Realtime `practice`에 내부 HTTP로 등록한다. 성공 뒤 응답 유실은 같은 후보/입력 버전/요청으로 동일 목표·버전을 재확인하며 성공 AI 단계를 재생성하지 않는다. Core 결과/Job 종료와 완료 Outbox를 함께 기록하고 Realtime이 기대 결과 run·입력·대상·현재 권한으로 참조를 반영한다.

실시간 작업의 `activityOpId`/기존 `opId`와 결과 실행의 `jobRunId`/`runId`는 논리 역할이 다르며 실제 필드명은 TBD다. 종료로 실시간 op가 무효화됐다는 이유만으로 정상 사후 결과를 모두 버리지 않는다. 이전 run이 현재 결과를 덮거나 종료/`PARTIAL` 활동을 `ACTIVE`로 되살리지 못한다. 유효 기록의 부분 결과 요구와 활동 `PARTIAL`/Core 결과 준비의 독립 축을 유지한다.

### 2.5 Realtime 미디어·게이트·재생 관측

채택 경로는 **기기↔Realtime `/ws` WSS(음성·제어·상태·재생 보고)**, **Realtime↔GPT-Live API(`gpt-live-1`) 서버 주 WebSocket**이다. Realtime 공급자 Adapter가 서버 키를 보관하며 주 연결의 `session.start → session.started`를 확인한다. 운영 모델/Java SDK·이벤트·취소/종료·저장 설정의 실제 지원은 TBD다.

Realtime 또는 그 미디어 Adapter가 출력 **앱 구간 분할 → 전사 대응 → 상태·권한·최신성 및 필요한 경량 의미 검사 → 동일 음성 승인 → 전달 직전 재확인 → 기기 송신**을 책임진다. 원음은 임시 메모리 버퍼에서 사용·폐기한다. 미승인/무효 음성·전사 누락/대응 불명확·검사 실패·시간초과는 전달하지 않고 폐기/대체 안내 경로로 처리한다. 알고리즘/라이브러리·전체 완료 기준·버퍼 시간/크기/포화·검사 모델·안내/재생성 한도는 TBD다.

검사는 전달 조건이며 전사/의미 검사 모델의 완전한 위험 발견을 보장하지 않는다. **무거운 교육 판정·KG/GraphRAG를 동기 게이트 경로에서 분리하는 것은 우리 설계 권고**다. 필수 안전 검사를 사후로 미루거나 게이트에서 모든 LLM을 금지하는 뜻이 아니다. 필수 근거가 없으면 해당 전달을 보류한다.

끼어들기·쉬기·종료·철회별로 버퍼/기기 대기열·타이머·승인 효력을 취소하고 늦은 승인으로 재생을 재개하지 않는다. 폐기 음성도 모델 맥락에 남으므로 허용된 계속 대화의 회복에는 앱 교정 지시 `session.instructions.append`를 연결한다. 수락 ACK는 교정 완료·맥락 삭제·재생 승인이 아니고 새 출력도 다시 검사한다. 미승인 원문을 시스템 지시로 그대로 복사하지 않는다.

`playbackMark`는 실제 기기 플레이어가 관측한 진행/중단/완료 범위를 Realtime이 검증하는 앱 계약이다. 생성·승인·송신·ACK와 구분하며 실제 청취/이해나 자동 `ENDING`의 증거가 아니다. 활동·현재 연결·승인 음성/재생 묶음의 대응과 보고 누락·중복/역전·늦은 보고를 다룬다. 대상/필드/단위·송신 시점·미보고 표현·공급자 맥락 반영은 TBD다. 확인하지 못한 재생을 전체 성공으로 채우지 않는다.

GPT-Live 단일 전사·별도 STT 보류와 서버 최종 참여/의미 판정은 유지한다. 무응답 **실제 질문/안내 전체 재생 완료 후 10초**와 **Cue 최초 요청부터 실제 재생 시작까지 5초** 초기값은 보존한다. 전사/게이트 대기는 무응답으로 세지 않고 Cue 예산에는 포함한다. 실제 달성 여부는 POC다. delegation은 지원/역할을 확인할 항목이며 현재 실행 경계·게이트를 대체하는 채택 기술이 아니다.

### 2.6 역할·신뢰·현재 권한

Google 로그인과 Guardian/서비스 Session·CSRF·Logout·아동 관계/현재 동의는 Core 책임이다. 공급자 토큰과 서비스 Session을 구분하며 기기 고정 ID를 보호자 Session이나 운영 인증으로 쓰지 않는다.

기기는 고정 ID 사전 시드·대기 WSS·상태 보고로 준비하고 보호자가 상태 카드를 통해 시작한다. Realtime은 허용 ID·현재 연결·점유·원자 할당·현재 권한을 확인해 시작을 push한다. 고정 ID는 통제된 데모의 허용 대상 축약이다. Origin·중복/교체·운영 기기 인증·메시지/필드·표시 상태/문구는 TBD다. QR claim·운영용 페어링을 필수 범위로 추가하지 않는다.

내부 HTTP와 영속 수신 모두 서비스 인증을 적용한다. Core가 검증한 주체·행위·대상·기한을 전달하고 Realtime은 자신의 활동/기기 결합·현재 권한과 대조한다. 내부망·브라우저 주체 Header만으로 인증/인가를 충족하지 않는다. 내부 경로는 공개 진입을 차단하고 수신 인증도 유지한다. 구체 비밀값/회전·호출 범위·기한/크기·인증 방식은 TBD다.

대화 역할에 보호자 원문·전체 판정 기준·기존 평가·타 아동 자료를 전달하지 않는 원칙을 유지한다. 출력 게이트가 입력 제한을 대체하지 않는다. 실시간 원음은 DB·Outbox/Inbox·일반 로그·디스크에 우회 저장하지 않으며 승인 고정 안내 파일과 구분한다. 전사/Cue/근거·스냅샷·메시지·체크포인트도 보존/삭제 정책 대상이다.

Core의 동의 변경과 철회 Outbox를 함께 저장하여 우선 전달한다. 알림만으로 즉시 차단을 보장하지 않는다. Realtime/Worker는 새 처리·외부 요청·출력 승인/송신·저장·완료 반영에 현재 권한을 확인하고 확인 불가이면 새 이용을 보류/차단한다. STOP/안전 정리는 허용한다. 철회 효력 시각·확인/저장/송신 경합의 원자성·진행 중 외부 작업 취소(G-03)는 미해결 상세 계약이다.

### 2.7 상태·재시작·복구

아동 유효 종료 표현 확인 즉시 `ENDING` → 일반 출력/버퍼·기기 대기열 취소 → 고정 승인 마지막 인사 1회 예외 → 실제 정리 확인인 A안을 따른다. 보호자 STOP·철회·끼어들기·쉬기는 각각의 계약이며 아동 인사 예외를 자동 적용하지 않는다. 끼어들기를 자동 `ENDING`으로 해석하지 않는다.

| 실행 앱/작업 | 복구 소유권 |
| --- | --- |
| Core 재시작 | 자신의 미완료 Job/Outbox/Inbox를 복구. 정상 Realtime 활동을 일괄 오류 처리하지 않음. 현재 run/권한·성공 체크포인트·남은 한도 유지 |
| Realtime 재시작 | 소유한 진행 중 음성 활동에 ADR-005의 오류 정리 적용. 기존 실시간 음성 자동 재개 금지. 결과 대기/PARTIAL 참조·영속 전달 기록 보존 |
| 메모리 미디어·미확인 재생 | 버퍼를 복원된 것으로 보지 않으며 생성/승인/송신을 재생 성공으로 채우지 않음 |
| Worker/Sender/Inbox 재선점 | 자신이 소유했거나 lease가 만료된 작업 범위를 확인. generation/fencing·현재 run으로 오래된 실행의 반영을 차단. 실제 필드/시간/방식 TBD |
| 중복/역전·응답 유실 | 같은 요청/이벤트 재확인·수신 영속 ACK·업무 원자 반영. 미처리는 소유 앱이 복구. 다른 요청으로 같은 활동/목표/Job을 새로 만들지 않음 |

Core 복제본 기동 때 모든 `RUNNING` Job을 `PENDING`으로 돌리지 않는다. 네트워크 전달 재시도·LLM 기술 재시도·내용 수정·보호자 수동 run을 분리하고 기존 성공/실패 이력·체크포인트를 재시작으로 초기화하지 않는다. 외부 AI의 중복 호출 가능성까지 없앴다고 보장하지 않는다.

서비스 상태·연결 상태·재생 관측·사유/미실시·Core 결과 준비·화면 표시를 분리한다. 표시 요구로 ActivitySession Enum을 자동 확장하지 않는다. 늦은 결과는 허용 참조를 연결할 수 있어도 활동을 부활시키지 못한다.

### 2.8 자원·배포·남는 의존

EC2 한 대·Nginx·Docker Compose·한 Next.js·PostgreSQL/Neo4j·영속 볼륨/백업·DB 비공개 원칙은 기존 배포 방향을 유지한다. Next.js/Neo4j 포함 6개 상시 컨테이너는 v0.2 후보 수이며 실제 파일·이미지/자원·포트는 TBD다. CloudFront/S3·프론트 분리·도메인 방식을 임의 채택하지 않는다.

Core REST/Worker/Sender/Inbox와 Realtime WSS·상태/타이머·입출력 중계/분할/버퍼/검사·외부 AI 대기는 실행 한도를 구분한다. 두 앱의 DB pool·스레드·메모리·대기 작업·모델 호출 예산을 합산한다. DB 선점/조회/저장은 짧게, HTTP/AI 대기는 트랜잭션 밖에서 수행하며 포화 시 긴 작업을 WS/상태 처리기로 넘기지 않는다. 실제 한도·처리량·지연은 실측 전 TBD다.

Core는 콘텐츠/공통 지식 수정 소유자이며 Realtime은 활동 준비에 허용 승인 버전/필요 자료를 확보·고정한다. 확보 실패이면 시작/준비 실패를 처리하고 임의 안내를 생성하지 않는다. STOP·마지막 인사를 Core의 즉석 콘텐츠 조회에 종속시키지 않는다. 추가 KG 조회는 제한된 비동기 준비 경로다.

Core 장애 때 보호자의 새 제어는 Core 경유 의존이 남는다. 승인 자료/현재 권한을 확인하지 못한 새 진행을 계속 허용하지 않는다. **보호자 직접 Realtime 종료(G-02)는 미해결 대안**이며 릴레이/두 앱 선택으로 자동 채택되지 않는다. 같은 DB/호스트·외부 공급자의 장애/자원 경합도 남는다.

확장은 측정된 병목에 따라 별도 결정한다. Realtime 복제에는 활동/기기 연결 소유자·owner epoch·명령 전달/라우팅이 필요하며 일반 보호자 Sticky Cookie만으로 다른 기기 브라우저의 활동 소유자를 찾지 못한다. Broker가 연결 소유권을 대신 해결하지 않는다. 확장 순서·지원 동시 활동 수를 새로 만들지 않는다.

## 3. Reason

- 실시간 연결·상태/타이머·미디어 처리와 일반 업무/사후 Worker의 실행/재시작 범위를 구분할 수 있다.
- ActivitySession·목표는 Realtime, Guardian/동의·Job/결과는 Core의 공식 계약으로 변경한다.
- Realtime 안에서 음성 구간·전사·검사/승인·취소·실제 재생을 하나의 소유 경계로 연결할 수 있다.
- 두 앱 내부 Worker/Sender/Inbox와 기존 PostgreSQL/Flyway·Neo4j를 사용하며 초기 상시 서버/Broker를 늘리지 않는다.
- 공개 내부 계약·영속 재처리·앱별 복구로 다른 JVM의 응답 유실·미처리를 설명할 수 있다.
- 남는 권한/콘텐츠·공유 DB/호스트·Core 경유 제어 의존을 드러내고 실측/후속 게이트를 분리한다.

## 4. Alternatives와 대체된 이전 선택

### 대체된 이전 선택. 단일 Spring Boot Modular Monolith

원본은 한 실행 앱 안에 도메인 모듈·Worker·Port/Event를 두었다. 배포/같은 프로세스 호출은 단순했지만 실행기·스캔·재시작·실시간/사후 작업의 자원 범위를 공유했다. 현재 Core/Realtime의 두 실행 앱으로 대체하며 Monorepo·모듈 불변식·Repository 소유권·Port 원칙은 유지한다. 기존 문서의 같은 프로세스 결과 요청/완료는 원격 HTTP·Outbox/Inbox 경계로 보완한다.

### 대체된 이전 선택. v0.2의 직접 음성 연결·3개 로그인

v0.2의 Core/Realtime 방향·쓰기/복구·내부 HTTP/영속 전달은 유지하지만 브라우저 직접 WebRTC·SDP/Data Channel·Sideband와 카카오·구글·네이버 3개 로그인은 최신 릴레이 B안·Google만으로 대체했다. 이 항목들은 이력이며 현행 브라우저 공급자 권한 경로·시험으로 사용하지 않는다.

### Alternative A. 부모/아동 또는 AI/비AI별 앱 — 미채택

화면 역할이나 모델 사용 여부만으로 실행 앱을 나누면 Core의 사후 AI/현재 인가와 Realtime의 판정/상태 소유권을 충분히 설명하지 못한다. 채택 경계는 실행 특성·상태 소유권을 기준으로 한다.

### Alternative B. Result Worker/검사/AI를 별도 Spring으로 분리 — 미채택

초기 상시 프로세스·라우팅·인증·복구가 늘어난다. Worker는 Core 내부, 출력 게이트/검사 조정은 Realtime 내부이며 무거운 교육 판정은 해당 앱의 분리된 실행 경로로 둔다. 병목/독립 운영 요구가 실제 확인되면 별도 결정으로 검토한다.

### Alternative C. 공통 Entity/Repository·상대 스키마 직접 수정 — 미채택

같은 DB·Monorepo는 공유 변경의 이유가 아니다. 도메인 불변식·이전 실행 차단·현재 권한·복구 책임을 우회하므로 공식 계약/Adapter로 연결한다.

### Alternative D. 초기 Broker 또는 별도 PostgreSQL 여러 개 — 미채택

현재는 기존 물리 PostgreSQL 1개·앱별 스키마·HTTP/Outbox/Inbox를 사용한다. Broker는 전달 인프라 선택이며 Outbox 원자 기록·중복/정책 검사와 동급 대안이 아니다. 물리 DB/전달 인프라 확대는 측정/운영 요구에 따른 후속 결정이다.

## 5. Consequences

### 장점

- 각 앱의 상태/쓰기·실행·재시작·영속 미처리 복구 책임이 명확하다.
- Realtime의 릴레이/게이트·실제 재생과 Core 사후 결과/목표 등록을 구분한다.
- 기존 저장소·모듈 원칙·프론트 접점·도구/DB 선택을 유지하며 단계적으로 전환할 수 있다.
- 권한/콘텐츠·Core 제어·공유 DB/호스트의 남는 의존을 구체적으로 시험할 수 있다.

### 단점

- 내부 HTTP·서비스 인증·영속 전달/멱등·선점/복구·계약/배포 호환성을 구현해야 한다.
- 양 앱의 pool/실행기/모델 예산·같은 호스트/DB 부하를 합산·관측해야 한다.
- 원격 등록·결과 전달은 앱 간 단일 커밋이 아니므로 부분 성공·응답 유실·후처리 대기가 남는다.
- 분리만으로 독립 가용성/처리량이 확보되지 않으며 Core 장애 제어·철회 경합 게이트가 남는다.

## 6. Risks

- **배치만 두 앱:** 광범위 스캔·공통 Entity/Service·상대 Repository 수정으로 상태 소유권이 실제 분리되지 않을 수 있다. 빌드/스캔/쓰기 권한·ArchUnit과 통합 시험이 필요하다.
- **ACK를 완료로 해석:** 영속 수신·업무 결과·기기 실제 재생의 의미가 다르다. 전달 적체/크래시 후처리와 미확인 재생을 별도로 드러내야 한다.
- **실시간 처리 포화:** 음성/전사/검사·버퍼와 긴 AI/HTTP 대기가 상태/STOP을 막을 수 있다. 짧은 상태 처리·분리된 제한 실행·폐기/실패 정책을 실측해야 한다.
- **복구/복제 경합:** 모든 RUNNING 초기화·이전 Worker 반영·오래된 run 완료·구 연결의 보고가 현재 상태를 덮을 수 있다. 소유/lease/fencing·현재 run/권한·실제 관측 검증이 필요하다.
- **독립 지속 과장:** Core 경유 제어·현재 동의/승인 버전·공유 DB/호스트가 남는다. G-02/G-03을 미해결로 표시하고 새 진행의 확인 불가를 허용으로 바꾸지 않는다.
- **개인 자료 우회 저장:** 버퍼·Outbox/Inbox·스냅샷/로그·백업에 원음/불필요 원문이 남거나 재전송이 삭제 자료를 복원할 수 있다. 최소 참조·허용 저장/삭제·폐기 관측을 함께 검증한다.

## 7. First Bolt·후속 검증

아래는 **미실시 시험 계획**이다. 빈 Boot 두 개의 기동만으로 완료하지 않는다. 합성·성인 역할극으로 작은 수직 연결부터 진행하며 기술 POC와 전체 결과/부분 결과의 구현 순서를 구분한다.

- [ ] Monorepo 두 앱의 빌드/기동·공통 계약·scan/scheduler 격리·Repository/서비스 소유권을 확인한다.
- [ ] Google/Core 서비스 Session·CSRF·현재 동의·대상 인가와 내부 서비스 인증/공개 내부 경로 차단을 확인한다.
- [ ] 고정 ID 대기 WSS·상태 보고/카드·보호자 시작·Realtime 원자 할당/push·짧은 접수/준비/참여 분리를 확인한다.
- [ ] 서버 키로 GPT-Live 주 연결·입출력/전사·구간 분할·메모리 버퍼/검사·동일 음성 송신·차단 후 회복을 확인한다.
- [ ] 끼어들기·쉬기·보호자/아동 종료·철회와 늦은 승인·기기 대기열·playbackMark 부분/미확인/늦은 보고를 확인한다.
- [ ] 아동 즉시 ENDING A안·일반 출력/버퍼 취소·승인 마지막 인사 예외와 실제 정리 관측을 순서대로 확인한다.
- [ ] 무응답 10초 기준에 게이트 대기가 들어가지 않으며 Cue 5초 예산에 포함되는지 실제 지연을 측정한다.
- [ ] Flyway 단일 실행·앱별 validate/쓰기 권한·Spring Session 스키마·DB/Neo4j 비공개·볼륨/복원/백업을 확인한다.
- [ ] 개별 앱 재시작·lease 재선점·오래된 Worker/run 완료를 재현하고 정상 상대 활동/결과 참조가 손상되지 않는지 확인한다. 버퍼 비복원·미확인 재생을 보존한다.
- [ ] 즉시 HTTP 응답 유실·Outbox/Inbox 커밋 전후 크래시·ACK 유실/중복/역전·업무/처리완료 원자성을 확인한다.
- [ ] 후속 원격 목표 등록/체크포인트·현재 결과 run·유효 PARTIAL/결과 준비 분리·전달/LLM/수동 재시도를 확인한다. 작은 첫 구현의 미구현을 제품 기능 제외로 쓰지 않는다.
- [ ] Core 장애 제어(G-02)·권한 확인 불가·철회/삭제/송신·저장 경합(G-03)과 금지된 늦은 반영을 검증한다.
- [ ] 양 앱·DB/호스트·입출력 중계/버퍼/검사·Sender/Worker 합산 부하·포화·지연/실제 재생 단계·외부 비용을 측정한다. 목표 부하·합격 수치는 TBD다.
- [ ] 원음·키·불필요 원문이 DB/Outbox/Inbox·로그·디스크/백업에 남지 않고 역할별 모델 입력 제한을 유지하는지 확인한다.

문서 정합성·그림 수정은 Java/DB/HTTP·GPT-Live·보안·품질·부하 실검증 증거가 아니다.

## 8. Pending

- [ ] Gradle 프로젝트/폴더·패키지/scan/scheduler·공통 계약/Port/Adapter·ArchUnit/CI·빌드/배포 호환성
- [ ] Java/Boot/Gradle·Security/Session·Flyway·GPT-Live Java SDK 실제 지원·모델/설정·취소/교정/종료
- [ ] 앱별 스키마/쓰기 역할·교차 조회 예외·물리 FK/제약·단일 Flyway 적용/이력/순서·각 앱 validate
- [ ] 내부 서비스 인증·비밀 주입/회전·Endpoint/DTO·주체/대상/기한·즉시 접수/재조회·ACK/완료/오류
- [ ] Outbox/Inbox/Worker 선점·lease/fencing·순서/중복·송신 한도/backoff·미처리/장기 장애·보존/삭제
- [ ] 고정 ID 시드/형식·Origin·연결 교체/중복/할당·운영 인증·WSS 오디오/메시지·상태 카드의 표시 상세
- [ ] 출력 구간·전사 대응/전체 완료·검사 모델/근거·버퍼 한도/포화·대체 안내/재생성/교정·playbackMark 상세
- [ ] 유효 부분 결과/수동 run·추천 근거 물리 분해·등록 멱등성/체크포인트·실제 제공 기록/API
- [ ] G-02 Core 장애 보호자 제어·G-03 철회 효력 시각/송신·저장 경합 원자성과 진행 중 취소
- [ ] 공유 호스트/DB 합산 예산·목표 부하/실측·Realtime 복제 소유자/owner epoch·명령 라우팅
- [ ] 제품 실제/모의 시연 범위(Q-DEVICE-01)·Neo4j/콘텐츠/AI 명세 상세·백업/복원·실제 운영 시험

## 9. Source of Truth와 다음 연결 지점

| 문서 | 책임·연결 |
| --- | --- |
| ADR-009 | 두 실행 앱 선택·실행 특성/상태 소유권·남는 의존과 대안 |
| ADR-001 | Monorepo·두 실행/빌드 영향·공유 계약·Git/PR/민감자료 규칙 |
| ADR-002/004/005/008 | 기술 후보/부분 확정, GPT-Live 게이트·상태/종료/복구·Google/현재 인가/내부 인증 |
| ADR-006/007 + system-architecture | 배포/라우팅/마이그레이션·합산 자원, 모듈/Port/scan 경계와 전체 그림 |
| ADR-010 + protocol | 즉시 HTTP/영속 Outbox/Inbox·멱등/커밋 ACK·원자성/재처리·메시지 상세 |
| data-model + result-pipeline | 소유 스키마/참조·Core Worker·원격 목표 등록·현재 결과 run·부분 결과·실제 제공 근거 |
| first-bolt | 작은 첫 연결·게이트/관측·앱별 복구·영속 전달·정책/부하의 시험 계획 반영 완료. 실제 시험·측정과 증거 수집은 미실시 |
| decision-log + README | 8단계: 신규 ADR-009/010의 목록·현행/대체 관계·TBD·검증 상태 요약 |

이번 단계 대상 밖의 기존 문서는 변경하지 않는다. 앞 단계의 논리 계약·필드 미정을 구현 상세로 자동 확정하지 않으며 이후 실제 API/데이터/정책 설계에서 함께 맞춘다.

## 10. Decision Summary

```text
Apps: Core API Spring + Realtime Activity Spring, 각각 실행·빌드
Criterion: 실행 특성·상태 소유권; 부모/아동·AI/비AI 분리 아님
Core: Google/Guardian/동의·서비스 Session·content/knowledge·Job/결과/리포트
Realtime: 기기/할당·ActivitySession·주 WS 릴레이/게이트·전사/재생·목표/연습/판정
Workers: Result Worker=Core 내부; Sender/Inbox Processor=각 앱 내부
Code: Monorepo·최소 공통 계약, Entity/Repository/Service/Boot 전체 설정 공유 금지
Isolation: component/entity/repository scan·scheduler 활성 범위 분리
Data: PostgreSQL 물리 1개·앱별 소유 스키마/쓰기 권한; Neo4j 공통 지식 유지
Migration: 기존 Flyway 보완·단일 배포 실행·앱별 validate, 상세 TBD
Immediate: 인증된 내부 HTTP 명령/조회/목표 등록·STOP 우선·짧은 접수/재확인
Durable: 업무/Outbox 커밋→내부 HTTP→영속 Inbox 커밋 ACK→원자 후처리/복구
Broker: 초기 도입 없음
Voice: 기기 /ws WSS↔Realtime↔GPT-Live API(gpt-live-1) 주 WebSocket·서버 키
Output: 메모리 구간/전사 대응→상태/권한/최신성·경량 검사→동일 음성 승인/재확인
Playback: 기기 관측 범위 검증, 생성/승인/송신/ACK·실제 청취와 구분
Result: Core 내부 Worker→Realtime 원격 등록·현재 run 완료 참조, 유효 PARTIAL 유지
Recovery: 각 앱 소유 미처리/lease·현재 run, 음성 자동 재개/버퍼 복원 없음
Privacy: 원음 영속 저장 없음, 최소 참조·현재 권한/삭제·역할별 모델 입력 제한
Deployment: 한 Next.js 두 접점·기존 EC2/볼륨/백업/DB 비공개 방향 유지
Limits: Core 제어/권한·콘텐츠/공유 DB/호스트 의존, G-02/G-03·실제 용량 TBD
Status: 방향 적용; 구현·독립 가용성·처리량/실연동 시험 미검증
```
