## Developer Code Scan Results

> 기준: 브랜치 `feat/doc-sync-code-update` = origin/main `72d7034`(작업 트리의 앱 코드는 커밋과 같음). 전체 재스캔(스냅샷 `./`), 깊이 Standard.
> 이 스캔은 갱신된 설계 문서와 비교하지 않는다. 지금 코드가 무엇인지만 적어, 다음 단계가 차이를 계산하게 한다.
> 제외: `aidlc/`, `.claude/`, `.codex/`, `.agents/`, `node_modules`, 빌드 산출물.

### Scan Coverage
- **Analyzed deeply**:
  - `backend/settings.gradle.kts`
  - `backend/build.gradle.kts`
  - `backend/gradle/libs.versions.toml`
  - `backend/gradle.properties`
  - `backend/gradle/wrapper/gradle-wrapper.properties`
  - `backend/build-logic/`
  - `backend/contract-model/build.gradle.kts`
  - `backend/core-api/build.gradle.kts`
  - `backend/realtime-activity/build.gradle.kts`
  - `backend/test-support/build.gradle.kts`
  - `backend/contract-model/src/main/java/kr/mujoong/contract/ws/`
  - `backend/contract-model/src/main/java/kr/mujoong/contract/event/`
  - `backend/contract-model/src/main/java/kr/mujoong/contract/status/`
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderHosts.java`
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderRequestFactory.java`
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderClient.java`
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/DialogInstructions.java`
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeRuntimeConfiguration.java`
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/gateway/WebSocketConfig.java`
  - `backend/core-api/src/main/java/kr/mujoong/core/textai/TextAiConfiguration.java`
  - `backend/core-api/src/main/java/kr/mujoong/core/result/ResultTextConfiguration.java`
  - `backend/core-api/src/main/resources/application.yml`
  - `backend/realtime-activity/src/main/resources/application.yml`
  - `backend/realtime-activity/src/main/resources/application-prod.yml`
  - `backend/realtime-activity/src/main/resources/application-demo.yml`
  - `backend/core-api/src/main/resources/db/migration/core/`
  - `backend/realtime-activity/src/main/resources/db/migration/realtime/`
  - `frontend/package.json`
  - `frontend/next.config.ts`
  - `frontend/vitest.config.ts`
  - `frontend/eslint.config.mjs`
  - `frontend/src/app/`
  - `tools/package.json`
  - `.github/workflows/ci.yml`
  - `contracts/README.md`
  - `contracts/parent-api.yaml`
  - `contracts/device-api.yaml`
  - `contracts/internal-api.yaml`
  - `.prettierrc.json`
- **Skimmed only**:
  - `backend/core-api/src/main/java/` (패키지 · 클래스 목록, REST 매핑 줄만 읽음. 업무 로직 본문은 읽지 않음)
  - `backend/realtime-activity/src/main/java/` (위 깊이 읽은 파일 밖은 패키지 · 클래스 목록과 크기만)
  - `backend/contract-model/src/main/java/kr/mujoong/contract/` (ws · event · status 밖: audio · error · json · log · profile · secret · time · trace)
  - `backend/test-support/src/main/`
  - `backend/core-api/src/test/`, `backend/realtime-activity/src/test/`, `backend/contract-model/src/test/`, `backend/test-support/src/test/` (개수 · 태그만)
  - `backend/*/src/main/resources/` (위 밖의 프로필 yml · prompts · forbidden · gate · practice · report 자원은 이름만)
  - `backend/coverage-scope/`, `frontend/coverage-scope/`
  - `frontend/src/` (app 밖: device · parent · shared는 파일 목록과 일부 타입 선언만)
  - `frontend/tests/` (개수만)
  - `tools/` (fake-provider 선 형식 줄 확인, 나머지는 목록)
  - `infra/` (compose 서비스 목록 · RUNBOOK 머리말. bin · host · nginx · migrate · postgres · aws · backup · bundle · ci는 목록)
  - `scripts/` (verify.sh 인자 처리 · cloud-setup.sh 머리말)
  - `ci/`, `.github/workflows/deploy.yml` (단계 이름만)
  - `contracts/schemas/`, `contracts/*.asyncapi.yaml`, `contracts/deviations.md`, `contracts/ai-io.md`, `contracts/timing.md`, `contracts/errors.md`, `contracts/ports.md`
  - `docs/` (`docs/units/README.md` 현황 표만)

### Packages Found
- `backend/contract-model` — Java 라이브러리(java-library) — Java 21 — 두 앱이 함께 쓰는 계약 타입: WS 메시지 17종(`WsMessageType`), 영속 이벤트 6종(`EventType`), 상태 enum(`SessionStatus` 12값 · `EndReason` · `FailureCode` · `ActivityType`), 오류 봉투, 시계 · 타이머, 구조화 로그. Spring · JPA 의존 없음(약 4.2천 줄).
- `backend/core-api` — Spring Boot 앱(`core-api.jar`, 포트 8080) — Java 21 — 보호자 REST · 계정(Google OAuth) · 아이 · 동의 · 기기 등록 · 활동 시작/조회 · 연습 목표/상황 생성 · 결과 작업 · 리포트(PDF) · 기록 · 웹 푸시 · Outbox/Inbox(약 23.6천 줄, 23개 하위 패키지. 큰 것: result 41 · practice 29 · notification 29 · identity 20 · activity 18 · device 17).
- `backend/realtime-activity` — Spring Boot 앱(`realtime-activity.jar`, 포트 8081) — Java 21 — 기기 WSS 게이트웨이 · 세션 수명 주기 · 음성 공급자 중계 · 출력 게이트 · 대화 기록 · 원음 녹음 · 연습 런타임(판정 · 단서) · Outbox/Inbox(약 25.9천 줄. 큰 것: session 34 · practice 29 · gateway 21 · messaging 19).
- `backend/test-support` — 시험 전용 라이브러리 — Java 21 — PostgreSQL(Testcontainers/로컬) · 가변 Clock · 계약 대역 HTTP · 가짜 공급자 WS · 기기 시뮬레이터 · 로그 수집. testImplementation으로만 쓰임.
- `backend/build-logic` — Gradle 포함 빌드(kotlin-dsl) — Kotlin — 규약 플러그인 `mujoong.java-conventions`(툴체인 · BOM · 보안 판 고정 · 시험 태그 · JaCoCo 80% · 유닛별 커버리지 범위).
- `frontend` — Next.js App Router 웹 앱 한 개(`mujoong-frontend`) — TypeScript/React — `/parent`(보호자 화면 17개 스크린 컴포넌트) · `/device`(아이 기기 화면 D0 · T19, 마이크 · 오디오 워크릿 · WS 클라이언트). 약 11.4천 줄.
- `tools` — Node ESM 시험 도구(`mujoong-tools`) — JavaScript — 가짜 공급자 서버 · 기기 시뮬레이터 · 스켈레톤 끝-끝 시나리오 · WAV 생성 · dev-edge(Nginx 대역).
- `contracts` — 계약 정본(문서 · 스키마) — OpenAPI 3.0.3 · AsyncAPI 2.6.0 · JSON Schema 2020-12 — 시험이 읽는 계약.
- `infra` — 배포 구성 — Docker Compose · Nginx 템플릿 · Bash · Flyway — demo(EC2 단일 호스트) 운영, IaC 없음(RUNBOOK 수동 순서).
- `scripts` — 검증 스크립트 — Bash(+Java 1파일) — `scripts/verify.sh`(A 점검 · B 스택 · C 스켈레톤 턴), `cloud-setup.sh`(AI-DLC 설치).
- `ci` — CI 보조 — Python — `security-gate.py`(Trivy/npm audit 예외 목록 만료 검사).

### Build System
- **Type**: Gradle 8.14.3 멀티 프로젝트(Kotlin DSL, 버전 카탈로그, 포함 빌드 build-logic) + npm(Node 22, frontend · tools 각각 package-lock) + Docker 이미지(6종 + 가짜 공급자).
- **Config Files**: `backend/settings.gradle.kts`, `backend/build.gradle.kts`, `backend/*/build.gradle.kts`, `backend/gradle/libs.versions.toml`, `backend/gradle.properties`, `backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts`, `backend/coverage-scope/*.txt`, `frontend/package.json`, `frontend/next.config.ts`(standalone 출력), `frontend/tsconfig.json`, `frontend/vitest.config.ts`, `frontend/eslint.config.mjs`, `frontend/coverage-scope/*.json`, `tools/package.json`, `tools/vitest.config.mjs`, `backend/core-api/Dockerfile`, `backend/realtime-activity/Dockerfile`, `frontend/Dockerfile`, `tools/fake-provider/Dockerfile`, `infra/{migrate,backup,bundle}/Dockerfile`, `infra/compose.{dev,ci,demo}.yml`, `.nvmrc`(22), `.editorconfig`.
- **Build Dependencies**:
  - `core-api` → `contract-model` (implementation), `test-support` (testImplementation)
  - `realtime-activity` → `contract-model` (implementation), `test-support` (testImplementation)
  - `test-support` → `contract-model` (api)
  - `core-api` ↛ `realtime-activity` (앱끼리 직접 의존 없음. 내부 HTTP `/internal/*`와 Outbox/Inbox 이벤트로만 통신, CI 불변식 샘플 `bad-core-depends-realtime.yml`이 막음)
  - 데이터베이스: PostgreSQL 16 한 인스턴스, 스키마 `core`(역할 core_app) · `realtime`(역할 realtime_app) 분리. 마이그레이션은 앱이 아니라 별도 `migrate` 이미지(Flyway, `infra/migrate/flyway-{core,realtime}.conf`)가 실행.

### APIs Discovered
- REST(보호자) — `backend/core-api/src/main/java/kr/mujoong/core/**/web/*Controller.java` — 계약 `contracts/parent-api.yaml`의 34경로와 일치. 구현 엔드포인트: dev 로그인 `POST /api/dev/login`, `GET /api/session/csrf`, `GET /api/health`, `GET /api/home`, `GET /api/device`, `POST /api/device/register`, `POST /api/activities/free`, `POST /api/activities/practice`, `GET /api/sessions/{id}` · `GET /api/sessions/current` · `POST /api/sessions/{id}/end`, `POST /api/auth/google` · `POST /api/auth/logout`, `GET|DELETE /api/me`, `GET /api/policies/current`, `GET|PUT /api/consents`, `GET|POST|PATCH /api/child`, `GET|PUT /api/child/stop-expressions`, `GET /api/records` · `GET /api/records/{sessionId}`, 목표 `GET|POST /api/goal-selections` · `PATCH|DELETE /api/goal-selections/{id}` · `POST .../restore`, 결과 `GET /api/sessions/{id}/result` · `.../result/status` · `POST .../result/retry`, 리포트 `GET /api/reports/preview` · `POST /api/reports` · `GET /api/reports/{id}` · `GET .../pdf` · `POST .../saved` · `POST .../discard`, 푸시 `PUT|DELETE /api/push-subscription` · `GET /api/push-subscription/vapid-public-key`.
- REST(아이 기기) — `backend/core-api/.../device/web/DeviceBootstrapController.java` — 4경로(`/api/device/bootstrap` · `/bootstrap/status` · `/bootstrap/claim` · `/me`), 계약 `contracts/device-api.yaml`.
- REST(앱 사이 내부) — Core: `/internal/devices/verify-token` · `/internal/devices/{id}/connection` · `/internal/events` · `/internal/practice/scenarios`; Realtime: `POST /internal/sessions` · `POST /internal/sessions/{id}/end` · `GET /internal/sessions/{id}/view` · `POST /internal/events` — 계약 `contracts/internal-api.yaml` 7경로. Realtime은 Spring Security 없이 `InternalTokenFilter`로 보호.
- WebSocket(기기) — `backend/realtime-activity/.../gateway/WebSocketConfig.java` `/ws/device` — 제어 메시지 17종(HELLO · HELLO_OK · HEARTBEAT(_ACK) · START_SESSION · ACK · DEVICE_READY · SESSION_STATE · OUTPUT_BEGIN/END · PLAY_APPROVED_CONTENT · PLAYBACK_MARK · STOP_OUTPUT · CLOSE_TRANSPORT · VOICE_SESSION_CLOSED · DEVICE_STATE · ERROR) + 바이너리 음성 프레임(입력 0x01, 960바이트 = 20ms PCM16LE 24kHz 모노) · 닫기 코드. 계약 `contracts/device-ws.asyncapi.yaml`. 프런트 대응: `frontend/src/shared/protocol/`.
- 영속 이벤트(Outbox/Inbox, HTTP 전달) — `contract-model/.../event/EventType.java` — 6종: Realtime→Core `SessionStateChanged` · `SessionRecordCaptured` · `DeviceStatusChanged` · `DeletionCompleted`, Core→Realtime `DeviceReleased` · `DeletionRequested`. 계약 `contracts/events.asyncapi.yaml`.
- 외부 음성 AI 공급자(나가는 WS) — `backend/realtime-activity/.../voice/` — 모델 상수 `gpt-live-1`, 주소 `wss://api.openai.com/v1/live/sessions`(demo · prod yml), 허용 호스트 `api.openai.com` 고정. 보내는 type 6종(`session.start` · `session.input_audio.append` · `session.close` · `session.response.cancel` · `session.instructions.correct` · `session.response.create`), 받는 type 9종(`session.started` · `session.input_audio.speech_stopped` · `session.input_transcript.delta/done` · `session.output_transcript.delta` · `session.output_audio.delta` · `session.output.done` · `session.error` · `session.closed`). 음성 형식 `pcm16le/24000/1` 입출력.
- 외부 텍스트 AI(나가는 호출) — `core-api/.../textai/TextAiProvider`(U5a 상황 생성) · `core-api/.../result/ResultTextProvider`(U6 결과 · 리포트) — 인터페이스만 있고 운영 어댑터 없음(아래 기술 부채 참고).
- 외부 기타 — Google OAuth(token · JWKS, `core/identity/google/`), Web Push VAPID(push2u, `core/notification/transport/`), Neo4j 지식 그래프(`core/knowledge/KnowledgeGraphLookup`, 기본 꺼짐 · 어댑터 없음).

### Frameworks & Libraries
- Java — 21 (툴체인, `options.release=21`) — 백엔드 언어
- Spring Boot — 4.1.1 — 두 앱(webmvc · websocket(realtime) · security + session-jdbc(core) · data-jpa · jdbc · flyway · actuator · validation)
- Jackson 3 (`tools.jackson`) — BOM 3.1.7(보안 상향) — JSON(직접 import는 `kr.mujoong.contract.json` 한 곳)
- Jackson 2 — BOM 2.22.3(보안 상향) — 전이 의존
- Tomcat embed — 11.0.26(보안 제약 상향) — 서블릿 컨테이너
- Flyway — Boot BOM — 마이그레이션(앱은 실행하지 않음, migrate 이미지가 실행)
- PostgreSQL JDBC / PostgreSQL 서버 — Boot BOM / 16.15 — DB
- Micrometer Prometheus — Boot BOM — 지표
- springdoc-openapi — 3.1.1 — OpenAPI 문서(dev · test만)
- openhtmltopdf-pdfbox — 1.0.10 — 리포트 텍스트 PDF(core)
- push2u-core — 0.3.0 — Web Push 전송(core)
- Apache HttpClient5 — Boot BOM — Core 외부 HTTP
- JDK `java.net.http.WebSocket` — JDK 21 — 공급자 연결(SDK 없음)
- JUnit 5 · AssertJ · Mockito — Boot BOM — 단위 시험
- Testcontainers PostgreSQL — Boot BOM — DB 통합 시험
- ArchUnit — 1.5.1 — 계층 · 의존 규칙 시험
- networknt json-schema-validator — 3.0.6 — 계약 스키마 검증 시험
- Java-WebSocket — 1.6.0 — 시험용 가짜 공급자 WS 서버
- JaCoCo — 0.8.14 — 커버리지
- Next.js — 16.4.0 — 웹 앱(App Router, standalone)
- React / React DOM — 19.3.0 — UI
- @tanstack/react-query — 5.104.0 — 보호자 화면 데이터 조회 · 폴링
- TypeScript — 5.9.3 — 프런트 언어
- Vitest / @vitest/coverage-v8 — 4.1.11 — 프런트 · tools 시험
- Testing Library(react 16.3.3 · dom 10.4.2 · user-event 14.6.7 · jest-dom 6.10.0) + jsdom 29.1.1 — 컴포넌트 시험
- ESLint 9.39.5 + eslint-config-next 16.4.0 — 린트
- ws — 8.21.3 — tools WS(가짜 공급자 · 시뮬레이터)
- Nginx 1.30.5-alpine · certbot v5.8.0 · neo4j 5.26.31-community(프로필 knowledge) — 인프라 이미지

### Test Coverage
- **Test Directories**: `backend/contract-model/src/test/`(15 클래스 · @Test 120), `backend/core-api/src/test/`(157 클래스 · @Test 996), `backend/realtime-activity/src/test/`(126 클래스 · @Test 819), `backend/test-support/src/test/`(4 클래스 · @Test 21), `frontend/tests/u1 … u7/`(시험 파일 73), `tools/tests/u1 · u3 · u4a · u4b/`(11). 백엔드 태그: db 107 · u1 142 · u2 15 · u3 24 · u4a 5 · u4b 24 · u5a 11 · u5b 34 · u6 35 · u7 17. ArchUnit 시험 `core/arch` · `realtime/arch`.
- **Test Frameworks**: JUnit 5 + AssertJ + Mockito + Spring Boot Test + Testcontainers + ArchUnit + JSON Schema 검증(백엔드), Vitest + Testing Library + jsdom(프런트), Vitest(tools), Bash 음성 대조 시험(`infra/ci/test-*.sh`), 끝-끝 스켈레톤(`tools/skeleton-e2e/`, `scripts/verify/c-skeleton-turn.sh`).
- **Coverage Config**: present — 백엔드 JaCoCo 줄 80% 하한(모듈별, test-support 제외), `-PcoverageScope=<유닛>`로 `backend/coverage-scope/*.txt` 범위 측정. 프런트 v8 줄 80% 하한, `MUJOONG_COVERAGE_SCOPE`로 `frontend/coverage-scope/*.json` 범위 측정. CI가 전체 + U5b · U7 범위를 따로 검증.

### Code Quality Indicators
- **Linting**: 프런트 ESLint flat config(`frontend/eslint.config.mjs`) — next core-web-vitals · typescript, `/parent` ↔ `/device` ↔ `shared` import 경계, `react/no-danger`, `react/jsx-no-literals`(화면 문구는 copy 모듈만), `getUserMedia` 사용처 1곳 제한, 서비스 워커 금지(U7 부모 SW 예외). 백엔드는 javac `-Xlint:all`(경고 오류화 아님)과 ArchUnit 규칙. Java 포매터(Spotless · Checkstyle) 없음. `.prettierrc.json`(printWidth 100)은 있으나 CI에 Prettier 검사 단계 없음. `.editorconfig` 있음.
- **CI/CD**: `.github/workflows/ci.yml` — 작업 changes · backend(`gradle clean check verifyBootJarHasNoTestClasses`) · u5b-scope · (U7 범위) · backend-deps(Trivy) · frontend(ESLint · Vitest 커버리지 · next build · 번들 비밀 문자열 0건) · frontend-deps(npm audit) · tools · secrets(gitleaks) · infra-validate(Compose 불변식 12 · nginx -t · shellcheck · 마이그레이션 expand 검사) · images(6종 빌드 · Trivy · 이미지 점검 · GHCR 푸시) · skeleton. 액션은 커밋 SHA 고정. `.github/workflows/deploy.yml` — 수동/자동 DEPLOY · ROLLBACK, OIDC → SSM `mujoong-deploy`, demo 환경. `docs/units/README.md`에 따르면 `SERVICE_HOST` · `AUTO_DEPLOY` 변수 미설정이라 실제 배포 단계는 건너뜀.
- **Documentation**: 루트 `README.md` · `AGENTS.md`, `infra/RUNBOOK.md`(demo 운영 정본), `contracts/README.md`(소유 · 변경 규칙) + `deviations.md`(편차 (a)~(y) 등 67행), `docs/units/<u>/brief · design · report.md`, `docs/erd/ERD_v0.2.md`, `docs/change-history.md`. 코드 주석은 한국어 Javadoc이 촘촘하고 결정 ID(J·BR·U*-J·편차)를 인용함.

### Technical Debt Signals
- AI 공급자 어댑터 부재 — 텍스트 AI는 기본 빈이 항상 실패: `core/textai/TextAiConfiguration.java`(`unavailableTextAiProvider` → `TextAiException.Reason.UNAVAILABLE`), `core/result/ResultTextConfiguration.java`(`PROVIDER_UNAVAILABLE`). 연습 판정 · 단서도 기본이 미연결: `realtime/practice/PracticeRuntimeConfiguration.java:46-51`(`EvaluationProvider` 실패 future, `CueProvider.UNAVAILABLE`). 실제 구현은 시험 대역(`FakeResultTextProvider` 등)뿐. 환경 변수 이름(`MUJOONG_TEXT_AI_ENDPOINT/MODEL/API_KEY`)만 `application.yml` 주석에 있음.
- 음성 공급자 선 형식이 자체 정의(J110): 모델 `gpt-live-1`, `session.*` type 체계, URL `/v1/live/sessions` — `realtime/voice/ProviderRequestFactory.java:27-39`, `ProviderClient.java:190-205`, `application-{demo,prod}.yml`. 가짜 공급자(`tools/fake-provider/server.mjs`)가 같은 형식을 흉내 내므로 실공급자 형식이 바뀌면 둘 다 바뀌어야 함.
- 지식 그래프(Neo4j) 읽기 어댑터 없음: `core/knowledge/KnowledgeGraphLookup`은 인터페이스만, `mujoong.knowledge.neo4j.enabled=false`; compose demo에 `neo4j` 서비스(프로필 knowledge)는 있음.
- 거대 클래스: `realtime/session/SessionExecutor.java` 3,388줄(세션 상태 · 타이머 · 출력 · 연습 확장 실행이 한 곳). 그다음 `FileAudioRecorder` 619 · `CoreProperties` 576 · `StopIntentJudge` 565.
- 압축된 코드 서식(한 줄에 메서드 전체): U6 계열 `core/report/web/ReportController.java`, `core/report/ReportRepository.java`, `core/result/SessionResultHandler.java`, `core/result/ResultAiSchema.java`, `core/result/ResultTextConfiguration.java`, 프런트 `parent/screens/Report30.tsx` · `RecordDetail27.tsx` · `ReportRange28.tsx` · `parent/api/results.ts`(250자 넘는 줄 다수). Java 포매터 · Prettier 검사가 CI에 없어 막히지 않음.
- 유닛 혼합 이력: U2~U5a는 엔진 없이 병렬 구현, U5b · U6 · U7은 통합 브랜치로 반영(`docs/units/README.md`). 시험 · 커버리지 범위가 유닛 태그(u1~u7)와 `coverage-scope/` 목록으로 나뉘어 있어, 파일을 옮기거나 새로 만들면 범위 목록을 함께 고쳐야 함(목록에 없는 파일이면 측정이 실패).
- 알려진 흔들림 · 남은 일(`docs/units/README.md`): `RealtimeInternalApiIT` 부하 시 409, 세션 문구 6종 음성이 합성음 자리(`infra/seed-audio/*-v1|v2.wav`), 시드 003을 검증 스크립트 · RUNBOOK 시드 순서에 넣는 일.
- 설정 키 다수가 `application.yml`과 `contracts/timing.md`에 이중으로 있음(시험이 대조). 시간 값 변경 시 두 곳 + 프런트 `shared/timing/timing-defaults.json`을 함께 바꿔야 함.
- TODO · FIXME 표시는 앱 소스에 0건(남은 일은 문서로 관리).

## Handoff Summary
- **Intent-relevant finding**: 갱신 문서(특히 `15_기술명세_AI동작_1015`, `40_기능명세_GPT-Live`)와 맞춰야 할 AI 동작 코드는 네 곳에 모여 있다. ① 실시간 음성: `backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderRequestFactory.java:27-39`(모델 `gpt-live-1` · `session.*` 선 형식 6종 송신) · `ProviderClient.java:190-205`(수신 9종) · `realtime/dialog/DialogInstructions.java:42-75`(역할 지시문 · 첫 질문 문장) · `dialog/StopIntentJudge.java` · `dialog/SilenceClock.java` · `gate/OutputGate.java` · `session/SessionExecutor.java`. ② 텍스트 AI(상황 생성 · 결과 · 리포트): `core/textai/` · `core/result/` + 프롬프트 `core-api/src/main/resources/prompts/result/*.v1.txt` · 금지 사전 `forbidden/result-v1.json` · `textai/u5a-forbidden-patterns.txt` — 운영 공급자 어댑터가 없다(기본 빈이 UNAVAILABLE). ③ 연습 판정 · 단서: `realtime/practice/`(`PracticeRuntimeConfiguration.java:46-51` 기본 미연결) + `resources/practice/u5b-*.yml`. ④ 시간 · 상태 값: `contracts/timing.md` ↔ 두 앱 `application.yml` ↔ `frontend/src/shared/timing/timing-defaults.json`, 상태 enum `contract-model/.../status/SessionStatus.java`. 화면은 보호자 01 · 02 · 03 · 04 · 05 · 05a · 08 · 12 · 17 · 18 · 26 · 27 · 28 · 30 · 중단 표현 01, 기기 D0 · T19가 구현돼 있다(`frontend/src/parent/screens/`, `frontend/src/device/components/`).
- **Risks / follow-up**:
  - 계약을 바꾸면 `contracts/` 정본 · `contracts/deviations.md` · 계약 시험(ContractFilesTest 등)과 프런트 파서를 같은 날 함께 고쳐야 한다(`contracts/README.md` 깨는 변경 규칙). 공급자 선 형식을 바꾸면 `tools/fake-provider/` · `backend/test-support/.../provider/` 대역도 같이 바뀌어야 한다.
  - DB 변경은 다음 번호부터(core V8 · realtime V5), Flyway `outOfOrder` 꺼짐, 기존 표 제약 강화는 `infra/migration-approvals.txt` 승인 행이 있어야 CI expand 검사를 통과한다.
  - 새 · 이동 파일은 `backend/coverage-scope/*.txt` · `frontend/coverage-scope/*.json` 범위 목록과 유닛 태그를 맞춰야 CI 범위 커버리지 단계가 깨지지 않는다. org 기본 `classic` 범위는 줄 80% 하한이 이미 CI에 걸려 있다.
  - 이 스캔은 패키지 · 경계 수준이다. 업무 로직 본문(core result · practice · activity, realtime session · gateway, frontend parent · device)은 얕게 봤으므로, 문서 차이가 특정 기능에 걸리면 그 경로를 집중 스캔해야 한다.
  - 작업 트리에 `aidlc/spaces/default/codekb/mujungi/*` 9개 파일의 커밋되지 않은 수정이 있다(git status). CodeKB를 게시하기 전에 이 수정이 어느 기록에서 온 것인지 확인해야 한다.
