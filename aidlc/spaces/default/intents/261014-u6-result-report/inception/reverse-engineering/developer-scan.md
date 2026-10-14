# 개발자 코드 스캔 — U6 결과와 리포트 (261014-u6-result-report)

- 스캔 시점: 2026-10-14, 브랜치 `unit/u6-result-report`, HEAD `7a22b67b455075d588f6dc11da8403d5cd6878c1`(origin/main, U4b PR #8 병합 뒤)
- 종류: 저장소 전체 재스캔(스냅숏 경로 `./`). 깊이 Minimal — 항목마다 한 번만 적는다.
- 기존 codekb(`aidlc/spaces/default/codekb/mujungi/`)는 U4b 브랜치 커밋 `3748083`에서 만든 partial 스캔이다. 방향 잡기로만 읽었고 아래 사실은 모두 현재 작업 트리에서 다시 확인했다.
- 작업 트리: 소스 변경 없음. 바뀐 것은 AI-DLC 기록(`aidlc/spaces/default/intents/…` 감사 조각 · `intents.json` · 새 기록 폴더)뿐이다.

## Developer Code Scan Results

### Scan Coverage
- **Analyzed deeply**:
  - backend/settings.gradle.kts
  - backend/build.gradle.kts
  - backend/gradle/libs.versions.toml
  - backend/gradle.properties
  - backend/build-logic/
  - backend/contract-model/build.gradle.kts
  - backend/contract-model/src/main/java/kr/mujoong/contract/event/
  - backend/core-api/build.gradle.kts
  - backend/core-api/src/main/java/kr/mujoong/core/record/
  - backend/core-api/src/main/java/kr/mujoong/core/knowledge/
  - backend/core-api/src/main/java/kr/mujoong/core/practice/
  - backend/core-api/src/main/java/kr/mujoong/core/textai/
  - backend/core-api/src/main/java/kr/mujoong/core/common/deletion/
  - backend/core-api/src/main/resources/application.yml
  - backend/core-api/src/main/resources/db/migration/core/
  - backend/core-api/src/main/resources/textai/
  - backend/core-api/Dockerfile
  - backend/realtime-activity/build.gradle.kts
  - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/ResultSnapshotBuilder.java
  - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/PracticeSessionExtension.java
  - backend/realtime-activity/src/main/resources/db/migration/realtime/
  - backend/test-support/build.gradle.kts
  - backend/coverage-scope/
  - contracts/ai-io.md
  - contracts/ports.md
  - contracts/timing.md
  - contracts/deviations.md
  - contracts/parent-api.yaml
  - frontend/package.json
  - frontend/vitest.config.ts
  - frontend/coverage-scope/
  - frontend/src/app/parent/records/page.tsx
  - infra/migration-approvals.txt
  - infra/migrate/
  - infra/compose.demo.yml
  - .github/workflows/ci.yml
  - docs/units/u6/brief.md
- **Skimmed only**:
  - backend/core-api/src/main/java/kr/mujoong/core/(account · activity · catalog · child · common/db · config · consent · device · health · identity · internalhttp · messaging · observability · security · startup · web)/ — 파일 목록 · 필요한 서명만
  - backend/core-api/src/test/ — 디렉터리 · 태그 · ArchUnit(CoreArchTest) · ConfigKeyContractTest 머리만
  - backend/realtime-activity/src/main/java/ — 패키지 목록(U4b에서 깊게 본 영역, 이번에는 결과 스냅숏 · 연습 확장점만 다시 읽음)
  - backend/realtime-activity/src/test/, backend/contract-model/src/test/, backend/test-support/src/
  - frontend/src/ (화면 · api · shared 목록, Done17 머리 주석), frontend/tests/, frontend/eslint.config.mjs
  - contracts/schemas/, contracts/events.asyncapi.yaml, contracts/internal-api.yaml, contracts/device-*.yaml
  - infra/ (bin · ci · nginx · postgres · seed · seed-audio · aws · host · backup · bundle 목록)
  - tools/, scripts/, ci/, docs/ (units 정본 지시서 외)

### Packages Found
- `backend` (Gradle 루트 `mujoong-backend`) — 멀티 프로젝트 — Java 21 — 두 앱 + 공용 모듈 묶음, 루트는 `base` 수명 주기만
- `backend/build-logic` — 포함 빌드(convention plugin `mujoong.java-conventions`) — Kotlin DSL — 툴체인 · BOM · 보안 판 제약 · 시험 태그 · JaCoCo 80% · `-PcoverageScope` · `verifyBootJarHasNoTestClasses`
- `backend/contract-model` — java-library — Java — 두 앱 공용 계약(이벤트 payload · 오류 코드 · 상태 열거 · WS 메시지 · 시계 · JSON 코덱 · 로그). Spring · JPA 없음
- `backend/core-api` — Spring Boot 앱(`core-api.jar`, :8080) — Java — Core(보호자 API · 기기 등록 · 활동 사본 · 활동 기록 · 연습 Core · 계정 삭제 지휘 · Core 메시징). 운영 소스 약 19.4천 줄 · 178개 파일
- `backend/realtime-activity` — Spring Boot 앱(`realtime-activity.jar`, :8081) — Java — Realtime(기기 WS · 세션 실행기 · 음성 중계 · 출력 게이트 · 전사 · 원음 · 결과 입력 스냅숏). 운영 소스 약 24천 줄. Spring Security 없음
- `backend/test-support` — java-library(testImplementation 전용) — Java — PostgreSQL 백엔드(Testcontainers/local) · 가변 시계 · 계약 대역 · 가짜 공급자 WS · 로그 수집. 80% 하한 대상 아님
- `frontend` (`mujoong-frontend`) — Next.js App Router 한 앱 — TypeScript/React — `/parent`(보호자) · `/device`(아이 기기) 두 영역, 영역 경계는 ESLint로 강제
- `tools` (`mujoong-tools`) — npm(ESM) — JS — 가짜 공급자 · 기기 시뮬레이터 · skeleton-e2e · dev-edge · seed(앱 이미지 밖 시험 도구)
- `contracts` — 계약 정본 파일 — YAML/JSON Schema/Markdown — parent-api · internal-api · device-api · device-ws(AsyncAPI) · events(AsyncAPI) · 스키마 46개 · ai-io · ports · timing · errors · deviations
- `infra` — Docker Compose · Nginx · Flyway migrate 이미지 · 호스트 도구(bash) · AWS IAM/SSM — 배포 · 검증
- `scripts` — bash — `verify.sh`(검증 명령 C안: 정적 · 단위 · 통합 → 기동 → 기기 시뮬레이터 한 턴) · `cloud-setup.sh`
- `ci` — Python — `security-gate.py` · 취약점 예외 목록
- `docs` — Markdown — 유닛 지시서 · 설계 · 보고서(`docs/units/u2 ~ u6`), ERD, 변경 이력

### Build System
- **Type**: Gradle 8.14.3(wrapper) + Kotlin DSL 버전 카탈로그(백엔드), npm(프런트 · tools, Node 22 — `.nvmrc`), Docker 다단계 빌드(이미지 6종 + 가짜 공급자)
- **Config Files**: `backend/settings.gradle.kts`, `backend/build.gradle.kts`, `backend/gradle/libs.versions.toml`, `backend/gradle.properties`, `backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts`, `backend/*/build.gradle.kts`, `backend/coverage-scope/u{1,2,3,4a,4b,5a}.txt`, `frontend/package.json`, `frontend/vitest.config.ts`, `frontend/next.config.ts`, `frontend/tsconfig.json`, `frontend/coverage-scope/u{1,2,3,4a,5a}.json`, `tools/package.json`, `backend/core-api/Dockerfile`, `backend/realtime-activity/Dockerfile`, `frontend/Dockerfile`, `infra/migrate/Dockerfile` · `flyway-core.conf` · `flyway-realtime.conf`
- **Build Dependencies**:
  - `core-api` → `contract-model`(implementation), `test-support`(testImplementation)
  - `realtime-activity` → `contract-model`(implementation), `test-support`(testImplementation)
  - `test-support` → `contract-model`(api)
  - 모든 모듈 → `build-logic`(plugin), Spring Boot BOM 플랫폼
  - core-api ↔ realtime-activity: 코드 의존 없음(ArchUnit ①이 `kr.mujoong.realtime` 참조를 막음). 앱 사이는 내부 HTTP · Outbox/Inbox 이벤트뿐
  - 시험 입력: 백엔드 시험 태스크가 `contracts/` · `infra/postgres` · `infra/seed` · `infra/migrate` · `infra/seed-audio` · 두 앱의 `db/` 리소스를 입력으로 잡는다(바뀌면 다시 돈다)

### APIs Discovered
- REST(보호자, `/api` 접두) — `contracts/parent-api.yaml` — 경로 22개: dev/login · session/csrf · health · home · device · device/register · activities/free · sessions/{id} · sessions/{id}/end · auth/google · auth/logout · me · policies/current · consents · child · child/stop-expressions · sessions/current · records · records/{sessionId} · goal-selections · goal-selections/{id} · goal-selections/{id}/restore. **C5(결과 · 리포트) 경로는 아직 없다.**
- REST(26 · 27 기록, U4a) — `core-api/.../record/web/RecordController.java` — `GET /api/records?month=yyyy-MM`(날짜별 수) · `GET /api/records?date=yyyy-MM-dd`(줄: linkable · goalCount · interrupted) · `GET /api/records/{sessionId}`(session · goals(이름 · 순서 · 상태) · utterances · gaps · recordScope · endConfirmed). FAILED는 404, DECLINED는 머리만.
- REST(연습 목표, U5a) — `core-api/.../practice/web/GoalSelectionController.java` — `GET/POST /api/goal-selections`, `PATCH/DELETE /api/goal-selections/{id}`, `POST …/{id}/restore`(27의 후보 「담기」가 쓸 POST)
- 내부 HTTP(C-INT, 내부 토큰) — `contracts/internal-api.yaml` — 7개: `/internal/sessions`(시작) · `/{id}/end` · `/{id}/view` · `/internal/devices/verify-token` · `/internal/devices/{id}/connection` · `/internal/events`(Outbox 수신) · `/internal/practice/scenarios`(C10, Core 소유)
- 이벤트(앱 사이 Outbox/Inbox) — `contract-model/.../event/EventType.java` · `contracts/events.asyncapi.yaml` — 6종: SessionStateChanged · SessionRecordCaptured · DeviceStatusChanged · DeletionCompleted(Realtime → Core), DeviceReleased · DeletionRequested(Core → Realtime)
- 앱 내부 사건(Spring) — `core-api/.../record/ActivityRecorded.java` — `ActivityRecorded(sessionId, guardianId, childId, activityType, finalStatus, recordedAt)`, 기록 첫 저장 트랜잭션에서 발행, 구독은 `@TransactionalEventListener(AFTER_COMMIT)` 전제. **운영 구독자 0개**(시험 `U4aActivityApiIT.RecordedEvents`만 받음)
- 기기 WebSocket — `contracts/device-ws.asyncapi.yaml` · `contract-model/.../ws/message/`(18종) — Realtime 소유(U6 무관)
- Java 포트(앱 내부, U5a 소비자 소유) — `contracts/ports.md` 4절 — CandidateEvidencePort · GoalMetadataPort · PracticeSessionContextPort · ScenarioRecordPort · KnowledgeGraphLookup · TextAiProvider, 제공 포트 CandidateRegistry.register/viewForSession
- AI 입출력 — `contracts/ai-io.md` — 6절(U6 분석 AI: 6.1 공통 지시 · 6.2 기록용 재판정 출력 JSON · 장면 칩 표 · 6.3 결과 문장 · 6.4 결과 수준 · 6.5 리포트 10항목 · 6.6 금지 표현 2단계), 7절(U5a TextAiGateway)

### Frameworks & Libraries
- Java — 21(toolchain, `options.release=21`) — 언어
- Spring Boot — 4.1.1 — 두 앱(webmvc · websocket(realtime) · security(core) · session-jdbc(core) · data-jpa · jdbc · flyway · actuator · validation)
- Jackson — 3.1.7(`tools.jackson`, 보안 판으로 BOM 덮음) · 2.22.3(com.fasterxml, 전이) — JSON(`contract-model` JsonCodec 한 곳)
- Tomcat embed — 11.0.26(보안 판 제약) — 서블릿 컨테이너
- Flyway — Boot BOM 판 + `flyway-database-postgresql` — 마이그레이션(앱은 실행하지 않고 migrate 컨테이너가 적용)
- PostgreSQL JDBC · HikariCP — BOM 판 — DB 16
- Micrometer Prometheus — BOM 판 — 지표
- springdoc-openapi — 3.1.1 — core dev · test 프로필 전용
- JUnit Jupiter · AssertJ · Mockito — BOM 판 — 단위 시험
- Testcontainers PostgreSQL — BOM 판 — DB 통합 시험(`-Pmujoong.test.db=docker|local|auto`)
- ArchUnit — 1.5.1 — 구조 규칙
- networknt json-schema-validator — 3.0.6 — contracts/schemas 2020-12 검증
- Java-WebSocket — 1.6.0 — test-support 가짜 공급자 서버
- JaCoCo — 0.8.14 — 커버리지
- Next.js — 16.4.0 · React 19.3.0 · @tanstack/react-query 5.104.0 — 프런트
- TypeScript 5.9.3 · ESLint 9.39.5(eslint-config-next 16.4.0) · Vitest 4.1.11(@vitest/coverage-v8) · jsdom 29.1.1 · Testing Library — 프런트 개발 · 시험
- ws 8.21.3 — tools 가짜 공급자 · 시뮬레이터
- 기반 이미지 — `eclipse-temurin:21.0.12_8-jre-noble`(digest 고정) — 앱 이미지
- **PDF 생성 · 한글 글꼴 라이브러리 — 없음**(카탈로그 · Dockerfile 어디에도 없다. OQ6 미결)

### Test Coverage
- **Test Directories**: `backend/core-api/src/test/java/kr/mujoong/core/**`(113개), `backend/realtime-activity/src/test/java/**`(115개), `backend/contract-model/src/test/java/**`(15개), `backend/test-support/src/test/java/**`(4개), `backend/core-api/src/test/resources/db/testmigration/core/V90__test_default_privilege_probe.sql`, `frontend/tests/{u1,u2,u3,u4a,u5a}/**`(56개 `*.test.ts(x)`), `tools/tests/`
- **Test Frameworks**: JUnit 5 + AssertJ + Mockito, Spring Boot Test(webmvc · security · data-jpa · jdbc), Testcontainers, ArchUnit, json-schema-validator(계약 대조), Vitest + Testing Library + jsdom(프런트 · tools)
- **Coverage Config**: present
  - 백엔드: build-logic이 모듈마다 JaCoCo LINE 80% 하한을 `check`에 건다(test-support 제외). `-PcoverageScope=<u>`이면 `backend/coverage-scope/<u>.txt`의 파일만 재고, 목록이 비거나 없는 파일이 있으면 실패. 현재 목록: u1 · u2 · u3 · u4a · u4b · u5a(**u6 없음**)
  - 프런트: `vitest.config.ts` v8 줄 80%, `MUJOONG_COVERAGE_SCOPE=<u>` → `frontend/coverage-scope/<u>.json`. 현재: u1 · u2 · u3 · u4a · u5a(**u6 없음**)
  - 태그(`@Tag`): db 75 · u1 142 · u2 15 · u3 24 · u4a 5 · u4b 24 · u5a 11(**u6 없음**). `-PincludeTags` · `-PexcludeTags`로 고른다
  - CI는 범위 없이 `./gradlew clean check`(모듈 전체 80%)와 `vitest run tests --coverage`(전체 80%)를 돈다 → U6 코드는 유닛 범위 80%와 함께 core-api 모듈 전체 80%도 지켜야 한다

### Code Quality Indicators
- **Linting**: 프런트 ESLint flat config(`frontend/eslint.config.mjs` — /parent ↔ /device import 경계, `react/no-danger`, 화면 문구는 copy 모듈만(`react/jsx-no-literals`), getUserMedia · 서비스 워커 제한), Prettier(`.prettierrc.json` printWidth 100), `.editorconfig`. 백엔드는 포매터 · 정적 분석 플러그인 없음(spotless · checkstyle · errorprone 0) — 대신 `javac -Xlint:all` + ArchUnit(`CoreArchTest`: realtime 참조 0 · 시험 코드 의존 0 · 시스템 시각 직접 읽기 0 · 세션 사본 쓰기 위치 제한) · `TimeLiteralScanTest` · `NoSqlNowTest` · `ConfigKeyContractTest`(timing.md ↔ application.yml ↔ CoreProperties 키 · 기본값 일치) · `ContractConformanceIT`(응답 ↔ contracts/schemas)
- **CI/CD**: `.github/workflows/ci.yml` — changes · backend(check + bootJar 시험 클래스 0) · backend-deps(Trivy HIGH+) · frontend(eslint · vitest 80% · next build · 번들 비밀 검사) · frontend-deps(npm audit) · tools · secrets(gitleaks) · infra-validate(Compose · 불변식 · nginx -t · shellcheck · **마이그레이션 파괴 문장 검사**) · images(6종 빌드 · trivy · 이미지 점검 · 관문 음성 대조) · skeleton(`scripts/verify.sh --mode compose`) · ci-gate · deploy. `.github/workflows/deploy.yml` 별도
- **Documentation**: 루트 `README.md`, `contracts/README.md`, `infra/RUNBOOK.md`, 유닛별 `docs/units/<u>/{brief,design,report}.md`. 운영 코드는 한국어 Javadoc이 촘촘하고 근거 ID(FR · BR · J · AC)를 단다. 예외: U5a 코드(`knowledge/` · `practice/` · `textai/`)는 영어 한 줄 주석 · 한 줄에 여러 문장을 몰아 쓴 압축 형식이다

### Technical Debt Signals
- **ActivityRecorded 구독자 없음** — `core-api/.../record/ActivityRecorded.java` 주석은 U5 · U6 구독을 전제하나 운영 리스너가 0개다. `ScenarioService.reconcileExposures(sessionId)`(`practice/ScenarioService.java:138`)도 운영 호출자가 0개다(ports.md 4절 "활동 기록 뒤 reconcileExposures로 반영").
- **U6 자리표시 기본 빈** — `practice/PracticePortConfiguration.java`: `CandidateEvidencePort`는 늘 `false`(모든 후보 등록 거절), `GoalMetadataPort`는 null 메타. `practice/ScenarioConfiguration.java`: `PracticeSessionContextPort` · `ScenarioRecordPort` 기본이 SERVICE_NOT_READY. 모두 `@ConditionalOnMissingBean`이라 U6가 같은 타입 빈을 두면 교체된다.
- **ScenarioLookup · BehaviorDefinitionLookup 없음** — `practice/` · `knowledge/`에 두 포트 타입이 없다. 재료는 있다: `ScenarioRepository.find(UUID)`(`Stored` = Scenario + 소유 id), `PragmaticsKnowledge.requireVersion(key, version)`(DRAFT · 미완성만 거절, RETIRED 허용).
- **TextAiProvider가 상황 생성 전용** — `textai/TextAiProvider.java`는 `generateScenario` 한 메서드, `TextAiGateway`도 `generateScenario`만 있다. 구조화 · 재판정 · 결과 문장 · 리포트 · 금지 2차 분류 입출력 타입이 없다. 기본 공급자는 UNAVAILABLE(`TextAiConfiguration`).
- **금지 표현 사전 형식 불일치** — `core-api/src/main/resources/textai/u5a-forbidden-patterns.txt`는 `//` 범주 주석 + 줄 패턴 18줄(appliesTo 없음). ai-io.md 6.6은 4절 JSON 형식의 `appliesTo`(RESULT_TEXT · PDF) 항목과 7범주 초안을 요구한다. Realtime 게이트 사전(`gate/seed-dictionary.json`)과도 별개 파일이다.
- **설정 키 이름 불일치** — ai-io.md 6.1은 모델 · 온도를 `mujoong.ai.*`로 부르지만 코드는 `mujoong.text-ai.*`(`TextAiProperties`)다. `mujoong.timing.result.job-attempts` · `mujoong.timing.report.ready-target`은 timing.md 2절(예약표, '읽을 유닛 U6')에만 있고 application.yml · `CoreProperties.Timing`에는 없다.
- **26 화면 스텁** — `frontend/src/app/parent/records/page.tsx`는 `/parent`로 redirect만 한다. 프런트에 `/api/records` 클라이언트 · 타입이 없다. `Done17.tsx`는 「오늘 기록 보기」를 감춘다(문구 `shared/copy/activity.ts:34 viewRecord`는 있음).
- **PDF 저장 자리 없음** — compose.demo.yml의 core-api는 `read_only: true` 루트 · `/tmp` tmpfs 64m · `mem_limit 1.6g`이고 영속 볼륨이 없다. core-api 이미지에 글꼴 패키지 없음.
- **V4의 자리표시 열** — `core_goal_recommendation.structured_experience_id uuid`(FK 없음, `V4__u5a_practice_core.sql`)는 U6 구조화 결과를 가리킬 자리로 보인다.
- **Javadoc 상수 목록 갱신 필요** — `common/deletion/DeletionParticipant.java` 주석의 "쓰는 값" 목록(90 · 100 · 150 · 200 · 250 · 300 · 900)과 `DeletionCommander` 주석 순서 설명은 새 상수가 생길 때 함께 고쳐야 한다.
- **큰 파일** — `realtime/session/SessionExecutor.java` 3167줄(U6 무관), `test-support/db/MujoongPostgres.java` 971줄.
- 표지: 운영 소스 TODO · FIXME · HACK 0건, `@SuppressWarnings` 6건(unchecked 캐스트), `eslint-disable` 0건.

### U6 관련 코드 위치 (상세)
- **활동 기록 원장(U4a)** — `core.core_activity_record`(V3): PK `session_id`(→ `core_activity` RESTRICT), guardian · child FK RESTRICT, `final_status ∈ {ENDED, ENDED_UNCONFIRMED, ABORTED, DECLINED, FAILED}`, `confirmed`, `transcript_json`(배열: seq · speaker(CHILD|AI) · text · uncertain · startedAt · endedAt) · `gaps_json` · `practice_json`(nullable 객체) · `audio_refs_json` · `termination_json` · `source_snapshot_id` · `snapshot_version` · `snapshot_hash`. `ActivityRecordRepository.StoredRecord`는 audio · termination을 읽지 않는다. 26 목록은 FAILED 제외(`LISTED_STATUSES`).
- **SessionRecordCaptured 처리(U4a)** — `record/SessionRecordCapturedHandler.java`: 보호자 행 FOR SHARE 잠금 → 사본 대조 → `snapshotHash` 재계산(`sha256:` + 정규화 JSON) 불일치는 예외(Inbox 재시도 → DEAD) → DECLINED는 본문 비움 → `insertIfAbsent` 처음일 때만 `ActivityRecorded` 발행, 같은 해시 재수신은 무시, 다른 해시는 APP_ERROR `EventConflict`.
- **결과 입력 스냅숏(U4b)** — `realtime/session/ResultSnapshotBuilder.java`: record = { utterances(FINAL · deleted_at 없음 · 아이 전사 + 승인 AI 말), gaps, recordScope{from,to}, practice(확장점 값 또는 null), audio }, DECLINED · ACTIVE 전 종료는 utterances · audio 빈 목록. 표 `realtime.realtime_result_input_snapshot`(realtime V3). 현재 연습 확장점은 `NoOpPracticeSessionExtension`이라 **PRACTICE 기록의 practice는 U5b 전까지 null**이다(편차 (U4b-d)). `record.practice`의 내부 모양(goals[].responses[] · cues[] · scenarioId)은 `contracts/` 파일에 없고 정본 contract-summary 6절에만 있다.
- **연습 Core(U5a)** — `CandidateRegistry.register(sessionId, childId, candidates≤100)`: 근거 확인(포트, 트랜잭션 밖) → 보호자 잠금 → 정의 pin(ACTIVE만 허용) → `core_goal_recommendation` · `core_recommendation_evidence` 저장. evidence.kind ∈ {GUARDIAN_INPUT, CHILD_RECALL}, reasonText ≤2000자 · 금지 사전 통과. `viewForSession`이 27 후보 목록 재료.
- **PragmaticsKnowledge(U5a)** — `requireVersion`(읽기, RETIRED 허용) · `pinVersion`(FOR KEY SHARE + used_at 최초 기록) · `supportItems(behaviorKey)`(APPROVED 지원 문구 — 생활 팁 매칭 후보 재료) · `relatedBehaviorKeys`(Neo4j 꺼짐이면 빈 목록). `BehaviorDefinition`에 `judgmentCriteria` · `behaviorDefinitionText`가 있다.
- **삭제 순서** — `DeletionParticipant` 상수: ACTIVITY_RECORD 90 · ACTIVITY 100 · PRACTICE 150 · DEVICE 200 · CONSENT 250 · CHILD_PROFILE 300 · MESSAGING 900. 참가자는 `@Order` 빈 · 멱등 · 한 트랜잭션 · `DbRetry IDEMPOTENT` 패턴(`ActivityRecordDeletionParticipant`가 본보기). `ORDER_RESULT = 70` 없음.
- **ActivityCoordinator** — 단일 클래스가 아니라 `activity/` 패키지(StartActivityService · EndSessionService · SessionViewService · ActivityCopyRepository · CurrentSessionService · PreparingTimeoutChecker …)와 로그 컴포넌트 이름 `EventLog.Component.ACTIVITY_COORDINATOR`다. `ActivityCopy` 레코드에 'ACTIVE 도달' 칸은 없고 `startedAt`이 그 대용이다.
- **마이그레이션 현재 버전** — core: V1(base) · V2(U2) · V3(U4a) · V4(U5a) + `afterMigrate.sql`, 테스트 전용 V90. realtime: V1 · V2(U3) · V3(U4b) + `afterMigrate.sql`. 이력 표 `flyway.core_schema_history` · `flyway.realtime_schema_history`, `baselineOnMigrate=true` · `baselineVersion=0` · `validateMigrationNaming=true`, `outOfOrder` 설정 없음(기본 false). 앱은 Flyway를 실행하지 않고 `SchemaVersionCheck`가 클래스패스 최고 버전 이상인지 기동 때 확인한다.

## Handoff Summary
- **Intent-relevant finding**: U6가 붙을 입력 · 출력 자리는 모두 main에 있으나 U6 쪽 구현은 0이다. ① 입력: `ActivityRecorded`는 `SessionRecordCapturedHandler`(`backend/core-api/src/main/java/kr/mujoong/core/record/SessionRecordCapturedHandler.java`, `publisher.publishEvent(new ActivityRecorded(...))`)가 기록 첫 저장 때 내지만 운영 구독자가 없다 — ResultPipeline이 첫 구독자가 된다. 원장은 `core.core_activity_record`(V3)의 transcript · gaps · practice JSON이고, PRACTICE의 `practice_json`은 U5b 전까지 null이다(`realtime/session/NoOpPracticeSessionExtension` · 편차 (U4b-d)). ② 출력: 후보는 `CandidateRegistry.register`(`practice/CandidateRegistry.java:173`)로 넣되, 기본 `CandidateEvidencePort`가 늘 false(`practice/PracticePortConfiguration.java:12`)라 U6 어댑터 없이는 모든 등록이 VALIDATION_FAILED다. ③ 확장: `ScenarioLookup` · `BehaviorDefinitionLookup` 타입이 없고, 재료는 `ScenarioRepository.find`(`practice/ScenarioRepository.java:62`)와 `PragmaticsKnowledge.requireVersion`(`knowledge/PragmaticsKnowledge.java`)이다. ④ AI: `TextAiProvider` · `TextAiGateway`가 상황 생성 한 메서드뿐이라 ai-io.md 6절 작업(구조화 · 재판정 · 결과 문장 · 리포트 · 2차 금지 분류)을 위한 확장이 필요하다. ⑤ 데이터 · 삭제: core 최신 V4, `DeletionParticipant`에 70 없음. ⑥ 프런트: 26은 redirect 스텁, `/api/records` 클라이언트 없음, 17은 「오늘 기록 보기」 감춤(`frontend/tests/u1/parent/done-17.test.tsx:109` J120 단언).
- **Risks / follow-up**:
  - `ConfigKeyContractTest`가 timing.md ↔ application.yml ↔ `CoreProperties` 키 · 기본값 일치를 강제한다. `result.job-attempts` · `report.ready-target`을 쓰려면 timing.md 2절 예약표의 행을 core-api 소유 절로 옮기고 yml · 레코드를 함께 더해야 한다. ai-io.md의 `mujoong.ai.*` 표기와 코드의 `mujoong.text-ai.*`도 설계에서 하나로 정해야 한다.
  - `infra-validate`(CI)가 기존 표 제약 강화 · 널 불허 추가 등 파괴적 문장을 막는다. V5에서 `core_goal_recommendation.structured_experience_id`에 FK를 걸거나 기존 표를 바꾸면 `infra/migration-approvals.txt`에 리뷰어 승인 줄이 필요하다. 새 표만 더하면 해당 없음.
  - `SchemaVersionCheck`는 클래스패스 최고 버전을 기대값으로 삼으므로 V5 파일과 migrate 이미지가 같이 배포돼야 한다(U7 V6 · U5b V7+ 순서는 지시서대로).
  - CI는 범위 없는 모듈 전체 80%(core-api · frontend)도 돈다. 새 `backend/coverage-scope/u6.txt` · `frontend/coverage-scope/u6.json` · `@Tag("u6")`를 만들 때 기존 u1 ~ u5a 목록은 고치지 않는다.
  - core-api 컨테이너는 읽기 전용 루트 · `/tmp` 64m · 메모리 1.6g · 영속 볼륨 없음이다. PDF 파일 저장(삭제 참가자 대상 포함)과 한글 글꼴 이미지 포함은 compose · Dockerfile · `infra-validate` 불변식 변경을 부른다(OQ6).
  - 금지 표현 사전은 U5a txt(appliesTo 없음)뿐이다. ai-io.md 6.6의 RESULT_TEXT · PDF 대상 사전을 같은 파일로 넓힐지 새 파일로 둘지 정해야 하며, `CandidateRegistry`가 같은 `ForbiddenExpressionPolicy`를 쓰므로 패턴을 더하면 U5a 동작도 바뀐다.
  - 보호자 응답 노출 금지(FR4.3 · A10)는 `RecordQueryService` · `RecordController`가 응답 모양에서 판정 · 구조화 · 08 원문을 빼는 방식으로 지킨다. C5 응답도 같은 방식(+ `ContractConformanceIT` 스키마 대조)이 기존 관례다.
  - `ActivityRecorded`는 같은 기록 재수신 때 다시 나지 않는다(멱등). 앱 재시작 · 리스너 실패로 놓친 결과 작업을 다시 잡을 경로가 지금은 없다 — JobRunner 설계에서 접수 누락 복구를 다뤄야 한다.
  - 27 결과 화면의 PRACTICE 경로(장면 칩 · 재판정)는 U5b 전까지 실제 `practice` 값이 없어 계약 예시 대역으로만 시험할 수 있다.
