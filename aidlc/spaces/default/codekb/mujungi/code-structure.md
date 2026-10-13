# 코드 구조 (Code Structure)

> 기준 커밋 `3748083`. 깊게 본 곳: `backend/realtime-activity/` · `backend/contract-model/` · `backend/test-support/` · `backend/build-logic/` · `contracts/`. 패키지별 책임 · 주요 클래스 · 의존은 `component-inventory.md`에 한 번만 적었고, 여기서는 배치 · 분류 · 코드 패턴을 다룬다.

## 1. 저장소 최상위 배치

| 경로 | 내용 | 이번 스캔 |
|---|---|---|
| `backend/` | Gradle 멀티 프로젝트 `mujoong-backend` | 아래 표 |
| `contracts/` | 계약 정본: `internal-api.yaml` · `device-api.yaml` · `parent-api.yaml`(OpenAPI), `device-ws.asyncapi.yaml` · `events.asyncapi.yaml`(AsyncAPI), `schemas/*.schema.json` 51개, `timing.md` · `errors.md` · `deviations.md` · `ports.md` · `ai-io.md` · `README.md` | 깊게(Realtime 관련 파일) |
| `frontend/` | Next.js 앱(`/parent` · `/device`) | `package.json` 판만 |
| `infra/` | Compose · Postgres · migrate(`migrate/flyway-realtime.conf`) · seed · `RUNBOOK.md` · `migration-approvals.txt` · `bin/infra-validate` | 목록만 |
| `.github/workflows/` | `ci.yml` · `deploy.yml` | 작업 이름 · 명령만 |
| `docs/units/<unit>/` | 유닛별 `brief.md` · `design.md` · `report.md` | 방향 잡기용 |
| `aidlc/` | AI-DLC 작업 기록 · codekb | 해당 없음 |

## 2. backend 모듈

```
backend/
├── settings.gradle.kts          # contract-model · core-api · realtime-activity · test-support, includeBuild(build-logic)
├── build.gradle.kts             # 루트는 base 플러그인만
├── gradle/libs.versions.toml    # 버전 카탈로그
├── coverage-scope/              # u1.txt · u3.txt · u2 · u4a · u5a (u4b.txt 없음)
├── build-logic/                 # 포함 빌드: mujoong.java-conventions + MujoongBuildExtension
├── contract-model/              # java-library, 공유 계약 타입
├── test-support/                # java-library, 시험 전용 대역
├── core-api/                    # Core 앱 (경계만 훑음)
└── realtime-activity/           # Realtime 앱
```

## 3. realtime-activity 내부 배치

```
backend/realtime-activity/
├── Dockerfile                                  # 호스트가 만든 bootJar를 COPY (이미지 안 Gradle 없음)
├── build.gradle.kts
└── src/
    ├── main/java/kr/mujoong/realtime/
    │   ├── session/        (24) + session/web/ (1)
    │   ├── dialog/         (2)
    │   ├── gateway/        (21)
    │   ├── voice/          (8)
    │   ├── gate/           (6)
    │   ├── log/            (9)
    │   ├── messaging/      (16) + messaging/web/ (1)
    │   ├── recording/      (3)
    │   ├── runtime/        (4)
    │   ├── internalhttp/   (2)
    │   ├── startup/        (7)
    │   ├── web/            (6)
    │   ├── observability/  (6) + observability/snapshot/ (2)
    │   ├── health/ (2) · config/ (2)
    │   └── common/{db (6), collect (1), scaling}
    ├── main/resources/
    │   ├── application.yml · application-{dev,demo,prod}.yml
    │   ├── logback-spring.xml
    │   ├── gate/seed-dictionary.json
    │   ├── META-INF/
    │   └── db/migration/realtime/ V1__realtime_base.sql · V2__u3_voice_conversation.sql · afterMigrate.sql
    └── test/
        ├── java/kr/mujoong/realtime/{arch,common,config,db,dialog,gate,gateway,log,messaging,observability,recording,session,startup,testing,voice,web}
        └── resources/application-test.yml
```

규모: 운영 소스 125개 파일 · 약 20,360줄, 시험 92개 파일(`@Test` 526개). 괄호 숫자는 패키지별 운영 파일 수.

### DB 스키마 (`realtime`, V1 · V2)

| 표 | 마이그레이션 | 용도 |
|---|---|---|
| `realtime_activity_session` | V1 | 세션 행(29열). 상태 체크 제약 `realtime_activity_session_status_ck`가 이미 WAITING_CHILD · DECLINED 허용, 진행 중 유일 색인도 WAITING_CHILD 포함 |
| `realtime_session_event` | V1 | 세션 사건 기록 |
| `realtime_session_stop_expression` | V1 | 세션별 종료 표현 |
| `realtime_transcript` | V1 | 전사 |
| `realtime_device_connection` | V1 | 기기 연결 상태 |
| `realtime_outbox_event` | V1 | Outbox |
| `realtime_inbox_event` | V1 | Inbox |
| `realtime_device_disconnect_event` | V2 (U3) | 끊김 사건 |
| `realtime_audio_recording` | V2 (U3) | 녹음 파일 메타 |
| `realtime_session_gap` | V2 (U3) | 끊김 · 미확인 구간 |

결과 입력 스냅숏 표는 없다(U4b V3 몫). 표 10개라는 숫자는 여러 시험에 박혀 있다(`code-quality-assessment.md` 참고).

## 4. contract-model 배치

`kr.mujoong.contract.*` — 운영 54개 파일 · 약 4,178줄.

| 하위 패키지 | 내용 |
|---|---|
| `status` | `SessionStatus` · `EndReason` · `FailureCode` · `EndRequestReason` · `ActivityType` · `ConnectionState` · `RegistrationStatus` |
| `event` (+ `payload`) | `EventType`(6종, `CURRENT_SCHEMA_VERSION = 1`) · `EventEnvelope` · 페이로드 레코드 6종 |
| `ws` (+ `message`) | `WsMessageType`(17종) · `WsEnvelope` · `CloseCode` · 메시지 레코드 |
| `json` | `JsonCodec`(정규화 JSON · `sha256OfCanonical`) — Jackson 직접 import 유일 위치 |
| `time` | `MujoongClock` · `TimerDriver` · `DelayQueueTimerDriver` |
| `log` | `EventCode` · `AttrKey` · `EventLog` · `OwnerHasher` · `StructuredLogFormatter` |
| `error` · `audio` · `profile` · `secret` · `trace` | 오류 코드 · `AudioFrameCodec` · `ProfileGuardRules` · 비밀 · 추적 ID |

## 5. 파일 분류

| 분류 | 위치 · 예 |
|---|---|
| 진입점 · 어댑터(인바운드) | `session/web/SessionInternalController` · `messaging/web/RealtimeInboxController` · `gateway/DeviceWebSocketHandler` · `gateway/WebSocketConfig` |
| 업무 핵심(상태 · 판정) | `session/SessionExecutor` · `TransitionTable` · `TransitionService` · `SessionTimers` · `dialog/StopIntentJudge` · `gate/OutputGate` · `gate/ClosingSequence` |
| 어댑터(아웃바운드) | `voice/ProviderClient` · `internalhttp/InternalHttpClient` · `gateway/VerifyTokenClient` · `gateway/StatusReporter` · `messaging/RealtimeOutboxSender` |
| 저장소 | `*Repository` (JDBC/JPA) — `ActivitySessionRepository` · `TranscriptRepository` · `RealtimeOutboxRepository` 등 |
| 인프라 · 런타임 | `runtime/*` · `startup/*` · `common/db/*` · `web/*` 필터 · `observability/*` · `health/*` · `config/*` |
| 설정 | `application*.yml` · `logback-spring.xml` · `gate/seed-dictionary.json` |
| 마이그레이션 | `db/migration/realtime/*.sql` |
| 시험 · 대역 | `src/test/java/**` · `testing/`(실행기 하네스) · `backend/test-support` |

## 6. 코드 패턴

| 패턴 | 어디서 | 규칙 |
|---|---|---|
| 세션당 액터(직렬 실행기 + 편지함) | `SessionExecutor` · `SessionMailbox` · `SessionRegistry` | 세션 상태는 실행기 스레드만 바꾼다. 안전 큐(L0H) 무제한 · 일반 큐(L0N) 1,000 |
| 전이 표 | `TransitionTable.Trigger` (출발 집합 · 도착 · 실패 코드) | 다중 출발 전이는 `FOR UPDATE`로 읽은 상태를 조건으로 쓴다(BR6.8) |
| 전이 승자만 부수 효과 | `SessionExecutor` · `TransitionService` | 갱신 행 수로 승자를 가린다 |
| Transactional Outbox / 멱등 Inbox | `messaging/*` | Outbox 쓰기는 활성 트랜잭션 필수, 송신은 `SKIP LOCKED`, Inbox 배분은 선점 UPDATE |
| 세대 번호 시계 | `SessionTimers`(Kind 6종) | 늦게 도착한 옛 시계 사건은 세대가 달라 무시 |
| 순수 판정 클래스 | `StopIntentJudge`(상태를 바꾸지 않음), (예정) `JoinTracker` | 판정 · 할 일만 돌려주고 실행기가 배선 |
| 포트 · 어댑터 | `ProviderPort`/`ProviderClient`, `DeviceStatusPort`, `SessionGapPort` | 시험에서는 `FakeProviderPort` · `FakeDeviceLink` 등으로 교체 |
| 단일 생성 경로 | `ProviderRequestFactory`, `RealtimeMetrics`(지표 이름 한 곳), `JsonCodec` | ArchUnit이 다른 경로를 막음 |
| 시계 주입 | `MujoongClock` · `TimerClocks` | 시스템 시계 직접 읽기 금지 |
| 기동 순서 · 배수(draining) | `startup/RealtimeStartupSequence`(S1 ~ S6) · `DrainingLifecycle`(T1 ~ T6) · `ReadinessGateFilter` | 준비 전 요청은 게이트에서 거절 |
| 설계 추적 Javadoc | 거의 모든 클래스 | 한국어 Javadoc + BR · J · U3-R 등 ID |

## Sources
- 개발자 스캔 `developer-scan.md` (Scan Coverage · Packages Found · Build System · Test Coverage)
- `ls` 및 `grep "CREATE TABLE"`로 `db/migration/realtime/*.sql` · `src/main/resources` 직접 확인
