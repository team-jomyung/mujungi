# 개발자 코드 스캔 — 261015-local-run-readiness

- 스캔 기준: 브랜치 `ops/local-run-readiness`(origin/main `72d7034`에서 새로 딴 체크아웃, 브랜치 전환 없음) · 2026-10-15
- 범위: 저장소 루트 전체 재스캔(`./`). `aidlc/` · `.claude/` · `.codex/` · `.agents/` · `node_modules/` · `build/` · `.next/` 산출물은 코드 분석에서 뺐다.
- 소스 파일은 고치지 않았다. 아래 사실은 파일 경로를 근거로 적었다.

## Developer Code Scan Results

### Scan Coverage
- **Analyzed deeply**:
  - `infra/compose.demo.yml` · `infra/compose.ci.yml` · `infra/compose.dev.yml`
  - `infra/ci/gen-env.sh` · `infra/ci/gen-selfsigned.sh`(머리) · `infra/ci/build-images.sh`(머리)
  - `infra/migrate/`(flyway-core.conf · flyway-realtime.conf · migrate.sh) · `infra/migration-approvals.txt` · `infra/release.env.tmpl`
  - `infra/seed/`(001 · 002 · 003 머리) · `infra/seed-audio/`(파일 목록)
  - `infra/nginx/upstreams/` · `infra/nginx/conf.d/https443.conf.tmpl`(location 목록)
  - `infra/RUNBOOK.md`(4절 단계 4 · 5-b, 13 ~ 17절)
  - `scripts/verify.sh` · `scripts/verify/b-host.sh` · `scripts/verify/b-stack.sh`(시드 부분) · `scripts/verify/c-skeleton-turn.sh` · `scripts/verify/lib.sh`(Java · PG 탐색, 비밀 이름 목록)
  - `tools/skeleton-e2e/steps.mjs` · `tools/skeleton-e2e/run-c.mjs`(머리) · `tools/fake-provider/scenarios.mjs` · `tools/dev-edge/edge.mjs`(머리) · `tools/package.json`
  - `.github/workflows/ci.yml` · `.github/workflows/deploy.yml`(머리)
  - `backend/settings.gradle.kts` · `backend/gradle/libs.versions.toml` · `backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts`(태그 · 커버리지 · DB 백엔드 부분) · `backend/core-api/build.gradle.kts` · `backend/realtime-activity/build.gradle.kts`
  - `backend/core-api/src/main/resources/application*.yml` · `backend/realtime-activity/src/main/resources/application*.yml`
  - `backend/core-api/src/main/java/kr/mujoong/core/textai/` · `.../result/ResultTextConfiguration.java` · `.../notification/transport/PushVapidSettings.java` · `.../notification/transport/PushTransportConfiguration.java` · `.../practice/PracticePortConfiguration.java` · `.../identity/DevSeedRunner.java`(머리)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeRuntimeConfiguration.java` · `.../voice/ProviderHosts.java`
  - `frontend/package.json` · `frontend/next.config.ts` · `frontend/vitest.config.ts` · `frontend/Dockerfile` · `frontend/src/proxy.ts`
  - `contracts/README.md` · `contracts/ports.md` · `contracts/ai-io.md`(1 ~ 3절)
  - `docs/units/README.md` · `docs/units/integration-2026-10-14.md` · `README.md`
- **Skimmed only**:
  - `backend/core-api/src/main/java/`(패키지 목록 · 엔드포인트 매핑 추출) · `backend/realtime-activity/src/main/java/`(패키지 목록 · 매핑 추출)
  - `backend/*/src/test/`(IT 클래스 목록 · 개수) · `backend/contract-model/` · `backend/test-support/`(클래스 목록)
  - `frontend/src/`(디렉터리 구조 · API 경로 · 환경 변수 사용) · `frontend/tests/`(유닛별 파일 개수)
  - `infra/bin/` · `infra/host/` · `infra/aws/` · `infra/backup/` · `infra/bundle/` · `infra/postgres/` · `infra/ci/`(나머지 스크립트 · invariant-samples)
  - `contracts/*.yaml` · `contracts/schemas/` · `contracts/timing.md` · `contracts/errors.md` · `contracts/deviations.md`
  - `docs/units/u*/`(report · design 일부 grep) · `docs/cloud_handoff.md` · `docs/cloud_run_report.md` · `docs/change-history.md` · `docs/erd/` · `docs/aidlc_drafts/`
  - `ci/security-gate.py` · `.gitleaks.toml` · `.editorconfig` · `.prettierrc.json` · `.gitignore`
  - `.aidlc-unit7-diagnostic/`(무엇인지만 확인 — 아래 기술 부채 참고)

### Packages Found
- `backend/contract-model` — Gradle 라이브러리 — Java 21 — 두 앱이 함께 쓰는 계약 · 오류 · 시간 · JSON 타입(Spring · JPA 없음). main 55 · test 15 파일
- `backend/core-api` — Spring Boot 앱(실행 jar `core-api.jar`, 8080 · 관리 9090) — Java 21 — 보호자 REST · 기기 등록 · 계정(U2) · 활동 세션 Core(U4a) · 연습 Core(U5a · U5b 시작 준비) · 결과 · 리포트 · PDF(U6) · 웹 푸시(U7) · 내부 HTTP · outbox/inbox 메시징. main 281 · test 177(IT 65) 파일
- `backend/realtime-activity` — Spring Boot 앱(`realtime-activity.jar`, 8081 · 관리 9090) — Java 21 — 기기 WSS(`/ws/device`) · 세션 수명 · 음성 중계(공급자 WSS) · 출력 게이트 · 대화 기록 · 원음 기록(U3) · 세션 수명 U4b · 연습 Realtime(U5b). main 176 · test 146(IT 41) 파일
- `backend/test-support` — Gradle 라이브러리(testImplementation 전용) — Java 21 — `MujoongPostgres`(Testcontainers/로컬 PG) · `FakeProviderWsServer` · `ContractStubServer` · `DeviceSimulatorClient` · `TestSecrets` 등 9 클래스
- `backend/build-logic` — Gradle 포함 빌드 — Kotlin DSL — `mujoong.java-conventions`(Java 21 툴체인 · JaCoCo 80% · `-PincludeTags` · `-PcoverageScope` · `-Pmujoong.test.db` · `verifyBootJarHasNoTestClasses`)
- `frontend` — Next.js App Router 앱(standalone, 3000) — TypeScript/React — `/parent`(보호자) · `/device`(아이 기기, 마이크 워클릿) · `/healthz`. src 134 파일, 시험 73 파일(u1 34 · u2 10 · u3 8 · u4a 2 · u5a 2 · u5b 3 · u6 8 · u7 6)
- `tools` — Node 22 ESM 시험 도구(앱 이미지 밖) — JavaScript — 가짜 공급자(`fake-provider/server.mjs`, 8090) · 기기 시뮬레이터 · C안 끝-끝 실행기(`skeleton-e2e/`) · host 모드 Nginx 대역(`dev-edge/edge.mjs`) · 승인 음성 WAV 생성기(`seed/`). 시험 11 파일(u1 8 · u3 1 · u4a 1 · u4b 1)
- `contracts` — 계약 정본 — OpenAPI 3.0.3 · AsyncAPI 2.6.0 · JSON Schema 2020-12(61개) · Markdown — parent-api · device-api · device-ws · internal-api · events · errors · timing · ports · ai-io · deviations
- `infra` — 배포 · 실행 구성 — Compose · Nginx 템플릿 · Bash · Dockerfile — demo 스택(EC2 1대) · 검증 스택 덧붙임 · dev DB 스택 · 마이그레이션 이미지 · 백업 · 호스트 기준선 · AWS IAM/SSM
- `scripts` — 검증 명령 C안(`verify.sh` + `verify/*.sh` · `HostMigrate.java`) · `cloud-setup.sh`(AI-DLC 설치용)
- `ci` — `security-gate.py`(Trivy · npm audit 예외 목록 관문)
- `docs` — 유닛별 지시서 · 설계 · 보고(`docs/units/u2 ~ u7`) · 통합 기록 · ERD · 클라우드 인계 · AI-DLC 초안

### Build System
- **Type**: Gradle 8.14.3(wrapper, 멀티 프로젝트 + `build-logic` 포함 빌드, 버전 카탈로그) · npm(frontend · tools 각자 `package-lock.json`) · Docker BuildKit(이미지 7종) · Docker Compose(v2.24.4 이상 필요, `!override` 사용)
- **Config Files**: `backend/settings.gradle.kts` · `backend/build.gradle.kts` · `backend/gradle/libs.versions.toml` · `backend/gradle.properties` · `backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts` · `backend/coverage-scope/u*.txt`(10개) · `frontend/package.json` · `frontend/next.config.ts` · `frontend/tsconfig.json` · `frontend/eslint.config.mjs` · `frontend/vitest.config.ts` · `frontend/coverage-scope/u*.json`(8개) · `tools/package.json` · `tools/vitest.config.mjs` · `.nvmrc`(22) · `backend/core-api/Dockerfile` · `backend/realtime-activity/Dockerfile` · `frontend/Dockerfile` · `infra/migrate/Dockerfile` · `infra/backup/Dockerfile` · `infra/bundle/Dockerfile` · `tools/fake-provider/Dockerfile` · `infra/compose.{demo,ci,dev}.yml`
- **Build Dependencies**:
  - `core-api` → `contract-model`(implementation), `test-support`(testImplementation)
  - `realtime-activity` → `contract-model`(implementation), `test-support`(testImplementation)
  - `core-api` ↔ `realtime-activity`: 컴파일 의존 없음. 런타임에 내부 HTTP(`http://realtime:8081` ↔ `http://core:8080`)와 공유 PostgreSQL(스키마 `core` · `realtime` 분리, 역할 `core_app` · `realtime_app`)로만 연결
  - 이미지는 호스트 산출물을 COPY만 한다(`infra/ci/build-images.sh`, R-03): `core-api.jar` · `realtime-activity.jar` · `frontend/.next/standalone` · 두 앱의 `db/migration` + Flyway CLI jar · tools
  - JDK 툴체인 자동 받기(foojay 플러그인)는 없다. `./gradlew` 실행에 로컬 JDK 21이 있어야 한다

### APIs Discovered
- REST(보호자 · 기기) — `backend/core-api`(Nginx `^~ /api/` → 8080) — 매핑 약 50개. 주요: `/api/me` · `/api/auth/google` · `/api/auth/logout` · `/api/session/csrf` · `/api/policies/current` · `/api/consents` · `/api/child` · `/api/child/stop-expressions` · `/api/home` · `/api/device` · `/api/device/register` · `/api/device/bootstrap`(+`/status` · `/claim` · `/me`) · `/api/activities/free` · `/api/activities/practice` · `/api/sessions/current` · `/api/sessions/{id}` · `/api/sessions/{id}/end` · `/api/sessions/{id}/result`(+`/status` · `/retry`) · `/api/records` · `/api/records/{id}` · `/api/reports/*`(`preview` · `{id}` · `{id}/pdf` · `saved` · `discard`) · `/api/goal-selections`(+`restore`) · `/api/push-subscription`(+`vapid-public-key`) · `/api/health` · dev 전용 `/parent/dev-login` 뒤 로그인 경로
- 내부 HTTP(Core 제공) — `backend/core-api` `/internal/**`(Nginx는 403) — `/internal/devices/*`(verify-token · connection) · `/internal/events` · `/internal/practice/scenarios`
- 내부 HTTP(Realtime 제공) — `backend/realtime-activity` `/internal/**` — `/internal/sessions`(시작) · `/{id}/end` · `/{id}/view` · `/internal/events`
- WebSocket(기기) — `backend/realtime-activity` `= /ws/device` — 제어 메시지 17종 + 바이너리 음성(`contracts/device-ws.asyncapi.yaml`)
- WebSocket(공급자, 나가는 방향) — `realtime` `voice/` — 자체 선 형식(`contracts/ai-io.md` 3절, `session.start` 등 서버→공급자 6종 · 공급자→서버 9종). **J110 가정**이며 실공급자 형식은 11/2에 맞춘다고 적혀 있다
- 정적 승인 음성 — Nginx `^~ /media/approved/` → `/srv/mujoong/approved-audio`(`infra/seed-audio/` 8개 WAV)
- 영속 이벤트 — outbox/inbox 6종(`contracts/events.asyncapi.yaml`), 앱 사이 `/internal/events`로 전달
- Actuator — 각 앱 관리 포트 9090(127.0.0.1만) `health,prometheus`. 호스트에서 두 앱을 함께 돌리면 `MANAGEMENT_SERVER_PORT`를 앱마다 달리 줘야 한다

### Frameworks & Libraries
- Java — 21(Gradle 툴체인) — 백엔드 언어
- Spring Boot — 4.1.1 — 두 앱(webmvc · websocket · security · session-jdbc · data-jpa · jdbc · flyway · actuator · validation). 가상 스레드 켬
- Jackson — 3.1.7(`tools.jackson`) · 2.22.3(보안 패치 BOM 덮어쓰기) — JSON
- Tomcat — 11.0.26(보안 패치 판 제약) — 서블릿
- Flyway — Boot BOM 판(앱에서는 `enabled: false`, 적용은 migrate 이미지) — 마이그레이션
- PostgreSQL — `postgres:16.15` 이미지 · JDBC 드라이버(BOM) — 데이터베이스
- springdoc-openapi — 3.1.1 — dev/test에서만 API 문서
- push2u-core — 0.3.0 — 웹 푸시 VAPID 서명(U7)
- openhtmltopdf-pdfbox — 1.0.10(카탈로그 밖 `core-api/build.gradle.kts`에 직접 판 기재) — 리포트 PDF(U6), Noto Sans KR 서브셋 글꼴 포함
- httpclient5 · HikariCP · micrometer-prometheus — BOM 판 — HTTP · 풀 · 지표
- 시험(백엔드): JUnit Jupiter · AssertJ · Mockito · Testcontainers(postgresql · junit-jupiter) · ArchUnit 1.5.1 · json-schema-validator 3.0.6 · Java-WebSocket 1.6.0 · JaCoCo 0.8.14
- Next.js — 16.4.0 — 웹(standalone, proxy 파일로 요청별 CSP nonce)
- React / react-dom — 19.3.0 — UI
- @tanstack/react-query — 5.104.0 — 데이터 조회
- TypeScript — 5.9.3 · ESLint 9.39.5(eslint-config-next 16.4.0) — 정적 검사
- Vitest — 4.1.11 · @vitest/coverage-v8 · jsdom 29.1.1 · Testing Library — 프런트 · tools 시험
- ws — 8.21.3 — tools WebSocket
- Node — 22(`.nvmrc`, 이미지 `node:22.23.3-alpine3.23`) — 런타임
- Nginx — `nginx:1.30.5-alpine` — 입구
- certbot — `certbot/certbot:v5.8.0` — demo 인증서(검증 스택에서는 끔)
- Neo4j — `neo4j:5.26.31-community`(프로필 `knowledge`, 꺼짐) — 미사용
- CI 도구: gitleaks v8.30.1 · trivy 0.74.0(다이제스트 고정) · shellcheck · jq

### Test Coverage
- **Test Directories**: `backend/contract-model/src/test` · `backend/core-api/src/test` · `backend/realtime-activity/src/test` · `backend/test-support/src/test` · `frontend/tests/u1 ~ u7` · `tools/tests/u1 · u3 · u4a · u4b` · `infra/ci/test-*.sh`(infra 검사기 음성 샘플 · 호스트 도구 · 관문 음성 대조) · `scripts/verify*`(검증 명령 C안 = 스택 기동 끝-끝)
- **Test Frameworks**: JUnit 5 + Spring Boot Test + Testcontainers(PostgreSQL, `-Pmujoong.test.db=auto|docker|local`, 기본 auto) · ArchUnit · JSON Schema 계약 대조(`ContractConformanceIT`) · Vitest(jsdom) · Testing Library · Node 기반 끝-끝 실행기(`tools/skeleton-e2e/run-c.mjs`). 브라우저 E2E(Playwright · Cypress)는 없다
- **Coverage Config**: 있음. 백엔드 JaCoCo 모듈별 줄 80%(`jacocoTestCoverageVerification`, `-PcoverageScope=<유닛>`이면 `backend/coverage-scope/<유닛>.txt`만), 프런트 Vitest v8 줄 80%(`MUJOONG_COVERAGE_SCOPE`이면 `frontend/coverage-scope/<유닛>.json`만). 마지막 기록(U6 report): 백엔드 2,385건 중 2,382 통과 · 3 skip, 줄 core 96.21% · contract 98.47% · realtime 92.84%

### Code Quality Indicators
- **Linting**: 프런트 ESLint(`frontend/eslint.config.mjs`, `/parent` ↔ `/device` import 경계 NFR11.1 포함) · `tsc --noEmit` 스크립트 · Prettier(`.prettierrc.json`, 루트) · `.editorconfig` · 셸 shellcheck(infra-validate) · gitleaks(`.gitleaks.toml`). 백엔드 Java 포매터 · 정적 분석(Spotless · Checkstyle · PMD · Error Prone)은 없고 ArchUnit 규칙과 설정 키 대조 시험(`ConfigKeyContractTest`)이 구조를 지킨다
- **CI/CD**: `.github/workflows/ci.yml`(작업: `changes` · `backend` · `u5b-scope`(U5b · U7 범위 커버리지 포함) · `backend-deps` · `frontend`(U5b · U7 범위 포함) · `frontend-deps` · `tools` · `secrets` · `infra-validate` · `images` · `skeleton`(main 병합 · 수동만, `bash scripts/verify.sh --mode compose`) · 집계 `ci-gate` · `deploy`) · `.github/workflows/deploy.yml`(OIDC → SSM 문서 → 호스트 `deploy.sh`, 저장소 변수 `SERVICE_HOST`가 없으면 건너뜀). 액션은 커밋 SHA 고정
- **Documentation**: 루트 `README.md`(dev 기동 · 유닛 시험 · 검증 명령 · 비밀 규칙) · `infra/RUNBOOK.md`(demo 운영 619줄, 17절 검증 스택) · `contracts/README.md` · `docs/units/README.md` · 유닛별 brief/design/report. 거의 모든 파일 머리 주석에 근거 ID(J · SD · BR 등)가 있다. 문서 언어는 한국어

### Technical Debt Signals
- 실공급자 어댑터가 없다. Realtime 공급자 선 형식은 "J110 가정"이고(`contracts/ai-io.md` 5행 · 36행), 허용 호스트는 `api.openai.com` 하나(`backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderHosts.java:25`), demo · prod 기본 주소는 `wss://api.openai.com/v1/live/sessions`(`application-demo.yml` · `application-prod.yml`). 이 형식이 실제 공급자 API와 맞는다는 확인은 저장소에 없다(U3 report 9절 · U4b report 8절의 "실공급자 어댑터 11/2" 남은 일)
- 텍스트 · 판정 AI 포트의 운영 기본값이 모두 "사용 불가"다: `TextAiProvider`(`core/textai/TextAiConfiguration.java`, U5a 상황 생성) · `ResultTextProvider`(`core/result/ResultTextConfiguration.java`, U6) · `EvaluationProvider` · `CueProvider`(`realtime/practice/PracticeRuntimeConfiguration.java:46-51`, U5b). 실제 어댑터 빈이 어디에도 없다. `application.yml`이 말하는 `MUJOONG_TEXT_AI_ENDPOINT` · `MUJOONG_TEXT_AI_MODEL` · `MUJOONG_TEXT_AI_API_KEY`를 읽는 코드도 없다
- `GoalMetadataPort`는 구현 빈이 없어 기본값(메타 null)만 쓴다(`core/practice/PracticePortConfiguration.java:10-11`). `CandidateEvidencePort`는 U6 `ResultEvidenceAdapter`가 대체한다
- `HELP_OFFER` 승인 문구는 시드 002의 DRAFT · 음성 없음 상태로 남아 있다(`infra/seed/003-u4a-reference.sql:17` "HELP_OFFER는 U5 몫"). `infra/seed-audio/`에도 해당 WAV가 없다. 연습 도움 제안(U5b `PracticeProtocol.help()`)이 승인 음성 없이 돈다
- 세션 승인 음성 6종 · CLOSING · WAITING_FILLER는 합성음 자리다(`003-u4a-reference.sql` 머리, `docs/units/README.md` 남은 일)
- `SessionExecutor.java` 3,388줄(`backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/`) — 한 클래스에 세션 실행 책임이 몰려 있다
- 유닛 사이 코드 형식이 다르다. U5a · U6 코드 일부는 공백을 거의 쓰지 않는 압축 형식이다(예: `core/result/ResultLevelCalculator.java`, `JobRunner.java`). Java 포매터가 없어 막지 못한다
- 라이브러리 판을 카탈로그 밖에 직접 적었다: `openhtmltopdf-pdfbox:1.0.10`(`backend/core-api/build.gradle.kts`) — 카탈로그 머리 주석의 "운영 · 시험 의존성을 여기서 모두 선언" 규칙과 다르다
- 알려진 흔들리는 시험: `RealtimeInternalApiIT`(부하 시 202 대신 409) · `SessionLifecycleIT`(DEVICE_ACK 2000ms 경계) · `RealtimeDeletionHandlerIT`(원음 파일 생성 대기) — `docs/units/README.md` · `docs/units/u5b/report.md:51`
- TODO/FIXME/HACK 주석 0건(검색 결과는 `mktemp` 템플릿뿐), `@Disabled` 0건, 프런트 `eslint-disable` 0건, 운영 코드 `@SuppressWarnings` 9건
- `.aidlc-unit7-diagnostic/`은 추적되지 않는 AI-DLC 진단 묶음(2026-10-14, 하네스 판 차이 · runtime-graph 없음 경고)이다. `.gitignore`에 없어 `git status`에 계속 뜬다. 앱 코드와 무관하다

## 로컬 현장 실행 · 통합 시험 관점 결과 (이 의도 전용)

### L1. 실행 경로 세 가지(저장소에 있는 것)

| 경로 | 무엇을 띄우나 | 입구 · 포트 | 공급자 | 근거 |
|---|---|---|---|---|
| A. dev(개발자 PC) | Compose로 postgres만(+선택 nginx). 앱 셋은 IDE · 호스트 프로세스 | postgres `127.0.0.1:5432`, 선택 nginx `127.0.0.1:8443`, web `3000` · core `8080` · realtime `8081` | 가짜 공급자를 손으로 띄움(`node tools/fake-provider/server.mjs`, 8090) | `infra/compose.dev.yml` 머리 주석, `README.md` 「로컬 기동(dev)」 |
| B. 검증 스택 compose 모드 | `compose.demo.yml` + `compose.ci.yml`, 로컬 이미지 `mujoong.local/mujoong-*:verify`, dev 프로필 + 시험 로그인, `fake-provider` 서비스 | nginx 443 → `127.0.0.1:8443`만 | 가짜 공급자(시나리오 `ok`, 턴 `join,normal,forbidden` 고정) | `infra/compose.ci.yml`, `scripts/verify.sh`, `infra/RUNBOOK.md` 17절 |
| C. 검증 스택 host 모드 | 로컬 PostgreSQL 16 클러스터(`/tmp/mujoong-verify-pg.*`) + `java -jar` 둘 + `node .next/standalone/server.js` + `tools/dev-edge` | dev-edge `127.0.0.1:8443` | `node tools/fake-provider/server.mjs` | `scripts/verify/b-host.sh` |

- 가장 완결된 "한 번에 띄우기"는 B다: `KEEP=1 bash scripts/verify.sh --mode compose`(A 정적 · 시험 · 빌드 → B 기동 → C 기기 한 턴 → 사후 단언, 실측 약 6분 · `infra/RUNBOOK.md:595`). `KEEP=1`이면 끝에 스택을 남긴다. 다만 생성 비밀(`build/verify/env/`)은 끝에 지운다(`scripts/verify.sh` 머리 · `remove_secrets`). 남은 스택을 다시 `docker compose ... up` 하려면 env를 다시 만들어야 하고, 새 비밀번호는 이미 만든 DB 볼륨의 역할 비밀번호와 맞지 않는다(검증 스택은 시작 때 `down -v`로 볼륨을 지우는 전제)
- 기동 순서(compose): `gen-env.sh` → `gen-selfsigned.sh` → `render-nginx` → `render-pgconf` → 산출물 빌드(`gradlew bootJar` · `next build` · `tools npm ci`) → `build-images.sh` → `migrate`(프로필 migrate, core → realtime) → 시드 001 → 002 → 003(owner 자격, 두 번 적용해 멱등 확인) → `up -d --wait`(postgres → core-api · realtime → web → nginx, `depends_on` healthy). 근거: `infra/compose.ci.yml` 머리 주석, `scripts/verify/b-stack.sh:144-166`
- 마이그레이션: Flyway 두 위치(core V1 ~ V7, realtime V1 ~ V4), `mujoong_owner`, `outOfOrder` 꺼짐, `baselineOnMigrate=true · baselineVersion=0`, `cleanDisabled=true`(`infra/migrate/flyway-*.conf`). 앱은 Flyway를 돌리지 않고 이력 버전만 읽는다(`spring.flyway.enabled: false`). 앱 역할 · 스키마는 `infra/postgres/init/01-roles.*`가 빈 데이터 디렉터리 첫 기동에만 만든다
- `infra/migration-approvals.txt`: U2 core V2의 7문장(expand 검사 예외)을 사람 승인으로 넘긴다. 로컬 실행 자체에는 영향이 없고 `infra-validate`(CI `infra-validate` 작업)에서만 쓰인다

### L2. 환경 변수 · 비밀(이름만)

| 서비스 | 필수 | 선택 · 기능 스위치 | 만드는 곳 |
|---|---|---|---|
| postgres | `POSTGRES_PASSWORD` · `DB_OWNER_PASSWORD` · `DB_CORE_PASSWORD` · `DB_REALTIME_PASSWORD` · `DB_BACKUP_PASSWORD`, `PG_HBA_ALLOWED_CIDR`(compose environment) | — | `infra/ci/gen-env.sh` |
| migrate | `DB_OWNER_PASSWORD` | `MUJOONG_MIGRATE_JDBC_URL` | gen-env |
| core-api | `SPRING_DATASOURCE_PASSWORD` · `INTERNAL_SERVICE_TOKEN` · `MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET` · `MUJOONG_SECURITY_ALLOWED_ORIGIN` · `SPRING_PROFILES_ACTIVE` | `MUJOONG_DEVLOGIN_ENABLED`(dev만) · `GOOGLE_OAUTH_CLIENT_ID` · `GOOGLE_OAUTH_CLIENT_SECRET` · `GOOGLE_OAUTH_REDIRECT_URI`(비면 구글 로그인 401) · `MUJOONG_PUSH_VAPID_PUBLIC_KEY` · `MUJOONG_PUSH_VAPID_PRIVATE_KEY` · `MUJOONG_PUSH_VAPID_SUBJECT`(셋 모두 있어야 푸시 발송, 일부만 있으면 기동 실패 — `PushVapidSettings.java:21-42`) · 호스트 실행 시 `SPRING_DATASOURCE_URL` · `MUJOONG_INTERNALHTTP_REALTIMEBASEURL` · `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` · `MANAGEMENT_SERVER_PORT` | gen-env(VAPID는 만들지 않음) |
| realtime | `SPRING_DATASOURCE_PASSWORD` · `INTERNAL_SERVICE_TOKEN` · `PROVIDER_API_KEY` · `MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET` · `MUJOONG_SECURITY_ALLOWED_ORIGIN` · `MUJOONG_PROVIDER_URL`(dev에서 없으면 기동 거부, J141) | `MUJOONG_RECORDING_DIRECTORY` · 호스트 실행 시 `SPRING_DATASOURCE_URL` · `MUJOONG_INTERNALHTTP_COREBASEURL` · `MANAGEMENT_SERVER_PORT` | gen-env(공급자 키는 가짜 자리값) |
| web | `MUJOONG_SECURITY_ALLOWED_ORIGIN` · `HOSTNAME=0.0.0.0` · `PORT=3000` | `MUJOONG_DEVLOGIN_ENABLED` · `GOOGLE_OAUTH_CLIENT_ID` · `GOOGLE_OAUTH_REDIRECT_URI` · `MUJOONG_TIMING_*` | gen-env |
| nginx | `SERVICE_HOST` · `MUJOONG_SECURITY_ALLOWED_ORIGIN` | — | gen-env |

- 저장소에는 `.env.example`류가 없다(비밀 규칙상 의도). 이름의 정본은 `gen-env.sh` 머리 주석 · `infra/compose.dev.yml` 머리 · `scripts/verify/b-host.sh`다
- Redis는 쓰지 않는다(세션 · 큐 모두 PostgreSQL: `spring-session-jdbc`, outbox/inbox 표)

### L3. 외부 의존: 필수 · 가짜 가능 · 미연결

| 외부 의존 | 로컬에서 | 상태 |
|---|---|---|
| 실시간 음성 AI(Realtime 공급자) | 가짜 공급자만 동작 | 실공급자 어댑터 미검증(J110 가정 형식, `api.openai.com` 허용 목록). dev · 검증 스택은 가짜 공급자 고정 |
| 텍스트 AI(U5a 상황 생성) | 미연결 → 연습 시작이 상황 생성 실패(422 `SCENARIO_GEN_FAILED`)로 끝나는 설계(`docs/units/u5b/design.md:13`) | 어댑터 없음 |
| 판정 · Cue AI(U5b) | 미연결("판정 공급자 미연결" 실패) | 어댑터 없음 |
| 결과 · 리포트 AI(U6) | 미연결 → AI가 필요한 결과는 3회 시도 뒤 `FAILED`, `INSUFFICIENT`는 AI 없이 `DONE`(`docs/units/u6/report.md:59`) | 어댑터 없음 |
| 구글 OAuth(U2) | 빈 값이면 로그인 401. 대신 dev 프로필 + `MUJOONG_DEVLOGIN_ENABLED=true`의 `/parent/dev-login`과 `DevSeedRunner`(보호자 1 · 아이 '하늘' · 동의 4 · 정책 · 문구 2) | 선택 |
| 웹 푸시(U7) | VAPID 세 값이 없으면 발송 꺼짐. 켜면 공개 푸시 서비스로 나가는 인터넷이 필요(`PublicPushEndpointPolicy` · `PublicDnsResolver`) | 키 생성 · 분배 도구 없음 |
| Neo4j | 꺼짐(`mujoong.knowledge.neo4j.enabled=false`, 프로필 knowledge) | 미사용 |
| Let's Encrypt(certbot) | 검증 스택은 자체 서명(`gen-selfsigned.sh`, 유효 2일)으로 대체 | 대체됨 |
| Docker Hub · Ubuntu 보안 저장소 · Flyway jar | 이미지 빌드 때 받는다(sha256 고정) | 오프라인 현장이면 미리 받아 둬야 함 |

### L4. 기존 통합 · 끝-끝 시험과 실행 방법

| 층 | 무엇 | 실행 | CI 작업 |
|---|---|---|---|
| 앱 하나 + 실제 DB | 각 앱의 IT(core 65 · realtime 41 클래스). 상대 앱은 `ContractStubServer`(34개 시험 파일이 사용), 공급자는 `FakeProviderWsServer`, 기기는 `DeviceSimulatorClient` | `cd backend && ./gradlew --no-daemon clean check -Pmujoong.test.db=docker` | `backend` |
| 유닛 범위 | `-PincludeTags=<유닛> -PcoverageScope=<유닛>` | 위와 같음 | `u5b-scope`(U5b · U7) |
| 계약 대조 | `ContractConformanceIT`(JSON Schema) · `FlywayConfIT` · `SeedReferenceSqlIT` · `SeedU4aReferenceSqlIT` · 스키마 · 권한 IT | `check`에 포함 | `backend` |
| 두 앱 + DB + Nginx + 웹 | 검증 명령 C안 B · C(C1 ~ C12): D0 → 시험 로그인 → 05a 등록 → claim → 기기 연결 → 04 안정 → 08 자유대화 시작 → 참여 응답(U4b) → 정상 턴 → 금지 문장 게이트 → P1 끝내기 → ENDED. 사후 단언 P1 스키마 격리 · P2 로그 위생 · P3 비밀 값 · T 작업 트리 | `bash scripts/verify.sh [--mode compose|host]` | `skeleton`(main 병합 · 수동만) |
| 인프라 | Compose 불변식 12 · demo 프로필 · nginx -t · 마이그레이션 expand 검사 · 음성 샘플 | `bash infra/bin/infra-validate` · `infra/ci/test-*.sh` | `infra-validate` · `images` |
| 프런트 | Vitest(jsdom) 컴포넌트 · API 클라이언트 시험. 브라우저 끝-끝 없음 | `cd frontend && npx --no-install vitest run tests --coverage` | `frontend` |

### L5. 유닛 경계에서 끝-끝으로 덮이지 않는 곳(통합 시험 후보)

두 앱을 실제로 함께 띄워 확인하는 시험은 C안 하나이고, 그 시나리오는 **자유대화(U1 · U3 · U4a · U4b)만** 다룬다(`tools/skeleton-e2e/steps.mjs:41-110`). 아래 이음새는 각 앱 IT에서 상대 앱을 스텁으로 두고만 확인됐다.

1. U5a ↔ U5b 연습 시작: `POST /api/activities/practice` → Core 준비(V7 `core_practice_preparation`) → Realtime 시작 → Realtime → Core `/internal/practice/scenarios` 호출 → 연습 단계 진행 · 노출 정산(`ScenarioRecordPort` · `reconcileExposures`). 실 AI가 없으면 422로 끝나므로, 끝-끝으로 보려면 시험용 텍스트 AI · 판정 · Cue 대역을 실제 기동 스택에 꽂는 경로가 필요하다(지금은 시험 소스 안에서만 주입)
2. U4b/U5b → U6 결과: Realtime 세션 종료 → `SessionRecordCaptured`/`ActivityRecorded` 이벤트(outbox → `/internal/events` → Core inbox) → U6 결과 작업 접수(`AFTER_COMMIT` → `REQUIRES_NEW`) → 결과 · 리포트 → `GET /api/sessions/{id}/result`. 통합 기록이 이 경로에서 연결 풀 고갈을 한 번 겪었다(`docs/units/integration-2026-10-14.md` 「통합 중 보완」 6번째 항목: 시험 문맥 풀 3개로 해소, 운영 풀 10개). 실제 두 앱 사이에서 C안 끝의 결과 상태(`FAILED` 또는 `DONE`)를 단언하는 단계는 없다
3. U6 → U7 완료 푸시: 세션 종료 → 푸시 발송(VAPID) → 클릭 → 17 → 27 결과 폴링. VAPID 값이 어떤 스택에도 분배되지 않아 실행 스택에서는 발송 경로가 한 번도 돌지 않는다
4. U2 계정 삭제 ↔ Realtime 삭제 처리: 삭제 참가자 순서(PUSH 60 → RESULT 70 → 기록 90 → 활동 100 → 연습 150 → … → MESSAGING 900)와 Realtime `RealtimeDeletionHandler` · 원음 파일 삭제. 앱별 IT만 있다
5. U6 리포트 PDF: openhtmltopdf + 내장 글꼴. 단위 렌더 시험(270ms)만 있고 실행 이미지(read_only 루트 · tmpfs)에서 PDF 내려받기를 확인하는 단계는 없다
6. 실제 브라우저 기기(`/device`): 마이크 워클릿 · Secure Context · 오디오 재생. 끝-끝은 Node 기기 시뮬레이터만 쓴다
7. 시드 순서: C안 B 단계는 001 → 002 → 003을 적용하지만 앱 기동 뒤 승인 문구 6종이 시작 패키지에 실리는지는 C8(시작 안내 · 참여 응답)로만 간접 확인한다

### L6. 로컬 실행에서 어긋나거나 빠진 것(사실만, 고치지 않음)

1. **이 PC에 JDK 21이 없다**: `java` 명령 없음, `/usr/lib/jvm` 없음, `~/.gradle/jdks` 없음. `scripts/verify.sh`는 전제 누락(종료 코드 2), `./gradlew`도 돌지 않는다. Node 22.23.3 · Docker 29.8.1 · Compose v5.5.1은 있다
2. **host 모드 전제 불일치**: 이 PC의 PostgreSQL은 `/usr/lib/postgresql/18`뿐이고 스크립트 기본은 `/usr/lib/postgresql/16/bin`(`scripts/verify/lib.sh:43`, `MUJOONG_PG_BIN`으로 바꿀 수 있음). 18로 돌려도 되는지는 확인되지 않았다(스택 이미지는 16.15)
3. **README dev 절이 낡았다**: 참조 시드를 `infra/seed/001-reference.sql`만 적는다(`README.md` 「로컬 기동(dev)」). 실제로는 002 · 003까지 필요하다(003이 없으면 Realtime이 참여 확인 · 공백 안내를 소리 없이 진행, `003-u4a-reference.sql` 머리 U4b-R15)
4. **RUNBOOK 시드 절도 001만 적용한다**: `infra/RUNBOOK.md:290-300`(단계 5-b). `docs/units/README.md` 「알려진 흔들림 · 남은 일」이 "시드 003을 RUNBOOK 시드 순서에 넣는다"를 남은 일로 적었고, 검증 스크립트(`b-stack.sh:150` · `b-host.sh:155`)에는 반영됐지만 RUNBOOK은 아직이다
5. **dev 경로에 마이그레이션 명령이 없다**: README는 "flyway-{core,realtime}.conf 순서로 mujoong_owner가 적용"이라고만 쓴다. 그 conf의 `flyway.locations=filesystem:/flyway/sql/...`는 migrate 이미지 안 경로라 호스트에서 바로 쓸 수 없다. 호스트용은 `scripts/verify/HostMigrate.java`(b-host.sh가 Flyway lib 경로와 함께 부름)뿐이고 사용법 문서가 없다
6. **dev 경로(`http://localhost:3000`)는 같은 출처 `/api` · `/ws`가 없다**: 프런트는 `/api/*` · `/ws/device`를 같은 출처로 부르고(`frontend/src/parent/api/*.ts`), `next.config.ts`에 rewrites가 없다. 브라우저로 보려면 dev nginx 프로필(호스트 upstream `host.docker.internal:3000/8080/8081`) 또는 `tools/dev-edge`가 있어야 하고, 이때 origin은 `https://localhost:8443`이다. README의 `gen-env --origin http://localhost:3000` 안내만 따르면 브라우저 흐름이 닫히지 않는다
7. **입구가 루프백에만 열린다**: 검증 스택 nginx `127.0.0.1:8443`(`compose.ci.yml`), dev nginx `127.0.0.1:8443`(`compose.dev.yml`). 현장에서 아이 기기(태블릿 등)를 같은 망의 다른 장치로 붙이려면 LAN 게시 · `SERVICE_HOST`/origin 변경 · 그 호스트용 인증서와 기기의 CA 신뢰가 필요하다. 마이크(`getUserMedia`)는 Secure Context가 있어야 하고 세션 쿠키는 `secure: true`다(`core-api application.yml`). `gen-selfsigned.sh --host`로 다른 이름의 인증서를 만들 수는 있다
8. **가짜 공급자는 대본 고정이다**: compose.ci의 `fake-provider`는 `--scenario ok --turns join,normal,forbidden`으로 시작하고 입력 프레임 25장마다 다음 대본 턴을 낸다(`tools/fake-provider/scenarios.mjs`). 사람이 실제로 말하는 현장 시연에서는 아이 말과 무관한 합성 문장 · 톤만 돌아온다
9. **연습(U5a · U5b) · 결과 문장(U6)은 로컬에서 끝까지 갈 수 없다**: L3의 AI 포트가 모두 "사용 불가" 기본값이라, 연습 시작은 상황 생성 실패, 결과는 3회 시도 뒤 `FAILED`(또는 `INSUFFICIENT`/`DONE`)다. 실행 스택에 시험 대역을 꽂는 프로필 · 설정은 없다
10. **웹 푸시(U7)를 켤 방법이 실행 스크립트에 없다**: `gen-env.sh` · `infra/bin/render-env` · RUNBOOK 4절 Parameter Store 목록 어디에도 `MUJOONG_PUSH_VAPID_*`가 없다. 비밀 이름 목록(`scripts/verify/lib.sh:46-48` `VERIFY_SECRET_NAMES`)과 CI 프런트 번들 비밀 이름 검사(`ci.yml` frontend 작업)에도 `MUJOONG_PUSH_VAPID_PRIVATE_KEY` · `MUJOONG_TEXT_AI_API_KEY`가 없어 로그 위생 · 번들 검사 대상에서 빠져 있다
11. **`KEEP=1`로 남긴 스택은 비밀 파일이 지워진 상태다**: 재기동 · `docker compose exec` 시 `env_file` 경로가 없어 실패한다. 현장 상시 기동용으로는 별도 env 디렉터리(`gen-env.sh --out ... --keep-existing`)와 볼륨 유지 절차가 필요하다
12. **`compose.loadtest.yml` · 시드 도구 · `host-sample`은 아직 없다**(`infra/RUNBOOK.md` 13절이 "3.6 이후 산출물"로 적음). 로컬 실행과는 무관하지만 문서가 가리키는 파일이 저장소에 없다
13. **실공급자 키를 넣어도 dev에서 실공급자로 갈 수 없다**: dev 프로필에는 공급자 기본 주소가 없고, 허용 호스트 검사(`ProviderHosts`)는 demo · prod에서 `wss` + `api.openai.com`만 허용한다. demo 프로필은 시험 로그인과 함께 켜면 기동 거부(SD-14)라 구글 OAuth 없이 로그인할 길이 없다

## Handoff Summary
- **Intent-relevant finding**: 저장소가 제공하는 "로컬에서 전체 스택을 띄우는" 유일한 완결 경로는 검증 스택(`compose.demo.yml` + `compose.ci.yml`, `bash scripts/verify.sh --mode compose`, `KEEP=1`)이고, 그 스택은 dev 프로필 · 시험 로그인 · **대본 고정 가짜 공급자** · `127.0.0.1:8443` 전용 입구다(`infra/compose.ci.yml`, `infra/RUNBOOK.md` 17절). 실공급자 어댑터(J110 가정 형식, `contracts/ai-io.md` 3절)와 텍스트 · 판정 · 결과 AI 어댑터(`TextAiConfiguration` · `ResultTextConfiguration` · `PracticeRuntimeConfiguration`의 UNAVAILABLE 기본값)가 없어서, 로컬 현장에서 실제로 끝까지 도는 기능은 자유대화(가짜 응답)뿐이고 연습 · 결과 문장 · 푸시는 막히거나 꺼진다. 유닛 경계 끝-끝 시험도 자유대화 한 경로(C1 ~ C12)만 있다.
- **Risks / follow-up**:
  - 이 PC에 JDK 21이 없어 빌드 · 검증 명령이 전제 누락으로 끝난다. host 모드는 PostgreSQL 16 대신 18만 있다
  - "현장"이 별도 아이 기기를 쓰는 뜻이면 LAN 게시 · 인증서 · CA 신뢰 · origin 변경이 필요하다(지금 구성은 모두 루프백)
  - 문서 불일치: README dev 절과 RUNBOOK 5-b가 시드 001만 적고, dev 경로에 호스트 마이그레이션 명령이 없으며, dev origin `http://localhost:3000`은 `/api` 같은 출처 경로가 없다
  - VAPID · 텍스트 AI 키는 생성 · 분배 · 비밀 검사 목록 어디에도 없다
  - 통합 시험 후보(L5): 연습 시작 왕복(U5a ↔ U5b), 세션 종료 → 결과 작업(U4b/U5b → U6, 연결 풀 경합 이력), 완료 푸시(U6 → U7), 계정 삭제 두 앱 연쇄(U2 ↔ Realtime), 실행 이미지에서의 PDF, 실제 브라우저 기기. 앞의 셋은 실행 스택에 AI · 푸시 대역을 꽂는 방법부터 정해야 한다
  - 흔들리는 기존 시험 3건(`RealtimeInternalApiIT` · `SessionLifecycleIT` · `RealtimeDeletionHandlerIT`)이 기준선 측정을 흔들 수 있다
  - `HELP_OFFER` 승인 문구 · 음성이 없고 나머지 승인 음성은 합성음 자리다
