# 유닛 시험 지시 — U1 u1-walking-skeleton

> 3.5 Code Generation Step 2 산출물 · 2026-10-10 · 대화 언어 한국어
>
> 짝 문서: `code-generation-plan.md`(Testing Contract · 단계 · 시험 클래스와 증명 ID). 시험 전략은 Standard(컴포넌트당 5 ~ 8개 + 핵심 경계 통합 시험), 방법론은 test-after(층마다 구현한 뒤 그 층의 시험을 쓰고 돌림)다. 이 파일의 모든 명령은 U1 시험만 돈다.

## 시험 프레임워크 설정

**백엔드(`backend/`, Gradle 멀티 프로젝트)**
- JUnit 5(Jupiter) · AssertJ · Mockito, Spring Boot 시험(`@SpringBootTest` · MockMvc · `@MockitoBean`), Testcontainers PostgreSQL 16(Compose와 같은 고정 태그) 또는 호스트 PostgreSQL 16(J132), ArchUnit, JSON Schema 2020-12 검증기, JaCoCo. 판은 Step 1 · 3이 `backend/gradle/libs.versions.toml`에 고정한다(Gradle 래퍼 8.14.3, J139).
- 규약 플러그인 `backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts`가 모든 모듈에 같은 설정을 건다(Step 5).
  - `useJUnitPlatform`에 Gradle 속성 `includeTags` · `excludeTags`(쉼표 구분)를 연결한다.
  - `testRuntimeOnly("org.junit.platform:junit-platform-launcher")`(Boot BOM 판). Gradle 9는 자동으로 넣지 않는다.
  - 시험 JVM 인자 `-Duser.timezone=UTC`, 시스템 속성 `mujoong.test.db`(Gradle `-Pmujoong.test.db=auto|docker|local`, 기본 auto, J132).
  - JaCoCo `jacocoTestCoverageVerification`: 규칙 `LINE` · `COVEREDRATIO` 최소 `0.80`, 제외 목록 없음, `check`가 의존한다. `-PcoverageScope=u1`이면 측정 대상을 `backend/coverage-scope/u1.txt`의 U1 파일로 한정한다(J137).
- 시험 대상 모듈: `contract-model` · `core-api` · `realtime-activity`. `test-support`는 시험 전용 지원 모듈(가변 Clock · 수동 타이머 · PostgreSQL 백엔드 · 대역)이라 하한 대상이 아니되 자기 시험을 둔다(`ManualTimerDriverTest` · `MutableClockTest` · `ContractStubServerTest`, J107).
- **태그 규칙.** 모든 U1 시험 클래스에 `@Tag("u1")`. PostgreSQL이 필요한 클래스에는 `@Tag("db")`를 더 붙인다(백엔드는 Docker 또는 호스트 PostgreSQL, J132). 태그가 없는 시험 클래스는 두지 않는다.
- 시험 프로필은 `test`(J94의 ci = test). 시험 로그인 시험만 `dev,test` + `mujoong.dev-login.enabled=true`(SD-14 조합 13)로 띄운다. 시간 키는 Step 5가 만드는 `src/test/resources/application-test.yml`에서 줄인 값(예: `session.device-ack-timeout` 2초, `device.disconnect` 4초, `gate.segment-quiet` 50ms, `provider.connect-timeout` 1초)을 쓰되, 경계 시험은 실제 값 그대로 가변 Clock을 앞당겨도 확인한다. `test` 프로필의 공급자 주소는 루프백만 허용된다(J141).

**프런트(`frontend/`)**
- Vitest · `@vitest/coverage-v8` · jsdom · `@testing-library/react` · `@testing-library/user-event` · `@testing-library/jest-dom`.
- `frontend/vitest.config.ts`: `environment: 'jsdom'`, `setupFiles: ['tests/u1/setup.ts']`, `coverage.provider: 'v8'`, `coverage.include: ['src/**/*.{ts,tsx}']`(환경 변수 `MUJOONG_COVERAGE_SCOPE=u1`이면 `frontend/coverage-scope/u1.json`의 U1 파일 목록, J137), `coverage.exclude`는 `*.d.ts`만, `coverage.thresholds.lines: 80`.
- U1 시험 파일은 `frontend/tests/u1/` 아래에만 둔다: `device/` · `parent/` · `shared/` · `lint/`.

**tools(`tools/`, Node 22 ESM)**
- Vitest(node 환경), `tools/vitest.config.mjs`. U1 시험 파일은 `tools/tests/u1/`에만 둔다. tools는 시험 지원 코드라 커버리지 하한 대상이 아니다(J107).

## 첫 시험 전에 돌릴 수 있는 명령

실행기 부트스트랩(계획 Step 5 · 6) 직후, 시험 파일이 하나도 없을 때 실행기가 서는지 확인하는 명령이다. 저장소 루트에서 실행한다.

**전제(프런트 · tools).** 프런트 · tools 명령 전에 잠금 파일대로 한 번 설치한다. 설치 없이는 `npx --no-install`이 실행기를 찾지 못해 실패한다.
```bash
cd frontend && npm ci
```
```bash
cd tools && npm ci
```

```bash
cd backend && ./gradlew --no-daemon :contract-model:test :core-api:test :realtime-activity:test :test-support:test -PincludeTags=u1
```
- 기대: 시험 소스가 없으므로 각 `test` 작업이 NO-SOURCE(시험 0건)로 끝나고 종료 코드 0. 래퍼 · JDK 21 툴체인이 이 명령으로 확인된다. NO-SOURCE는 시험 의존성을 해석하지 않으므로 아래 명령으로 따로 확인한다.

```bash
cd backend && ./gradlew --no-daemon :contract-model:dependencies :core-api:dependencies :realtime-activity:dependencies :test-support:dependencies --configuration testRuntimeClasspath
```
- 기대: 종료 코드 0이고 출력에 `FAILED` 0줄(시험 의존성과 junit-platform-launcher 해석 확인).

```bash
cd frontend && npx --no-install vitest run tests/u1 --passWithNoTests
```
```bash
cd tools && npx --no-install vitest run tests/u1 --passWithNoTests
```
- 기대: 시험 0건, 종료 코드 0. `--passWithNoTests`는 이 준비 확인에서만 쓰고 아래 정규 명령에는 붙이지 않는다.

## 이 유닛 시험만 돌리는 명령

모든 명령은 저장소 루트에서 실행한다. 맨 `npm test` · `gradle test` · `./gradlew test` · `npx vitest run`(경로 없음) 같은 전체 명령은 쓰지 않는다. 프런트 · tools 명령은 위 전제(`npm ci`)가 끝난 상태에서 돈다.

**백엔드 — U1 전체(단위 + 통합, PostgreSQL 필요: Docker 또는 호스트 PostgreSQL 16, J132)**
```bash
cd backend && ./gradlew --no-daemon :contract-model:test :core-api:test :realtime-activity:test :test-support:test -PincludeTags=u1
```
- 결과 보고에 DB 백엔드(`docker` · `local`)를 적는다.

**백엔드 — U1 전체 + 커버리지 하한 판정(80% 줄, 모듈별, U1 파일만 측정 J137)**
```bash
cd backend && ./gradlew --no-daemon :contract-model:test :core-api:test :realtime-activity:test :contract-model:jacocoTestCoverageVerification :core-api:jacocoTestCoverageVerification :realtime-activity:jacocoTestCoverageVerification -PincludeTags=u1 -PcoverageScope=u1
```

**백엔드 — PostgreSQL 없이 도는 U1 시험만(단위 · ArchUnit · 설정 대조)**
```bash
cd backend && ./gradlew --no-daemon :contract-model:test :core-api:test :realtime-activity:test :test-support:test -PincludeTags=u1 -PexcludeTags=db
```
- 이 명령은 하한 판정 명령이 아니다. DB 시험이 빠지므로 결과는 '부분'으로 보고하고 건너뛴 클래스를 적는다.

**백엔드 — 층 · 모듈 하나만(계획 단계의 완료 확인용)**
```bash
cd backend && ./gradlew --no-daemon :core-api:test --tests 'kr.mujoong.core.device.*' -PincludeTags=u1
```
```bash
cd backend && ./gradlew --no-daemon :realtime-activity:test --tests 'kr.mujoong.realtime.session.SessionTimersTest' -PincludeTags=u1
```
- `--tests`로 좁혀도 `-PincludeTags=u1`을 함께 준다. Gradle의 `--tests`는 바로 앞 test 작업 하나에만 걸리므로, 여러 모듈을 좁힐 때는 `:a:test --tests 'X' :b:test --tests 'X'`처럼 작업마다 붙인다(필터에 맞는 시험이 없는 모듈에는 붙이지 않는다).

**프런트 — U1 전체 + 커버리지 하한 판정(80% 줄, U1 파일만 측정 J137)**
```bash
cd frontend && MUJOONG_COVERAGE_SCOPE=u1 npx --no-install vitest run tests/u1 --coverage
```

**프런트 — 영역 하나만**
```bash
cd frontend && npx --no-install vitest run tests/u1/device tests/u1/shared
```
```bash
cd frontend && npx --no-install vitest run tests/u1/parent tests/u1/lint tests/u1/shared/test-ids.test.tsx
```

**tools — U1 시험**
```bash
cd tools && npx --no-install vitest run tests/u1
```

## 참고: 검증 명령(유닛 시험 명령 아님)

끝-끝 검증 명령(C안)은 저장소 전체를 도는 명령이라 이 파일의 유닛 시험 명령이 아니고, Build and Test가 유닛마다 돌리는 명령도 아니다. 구성 · 종료 코드 · 엔진 기록 방식은 `code-generation-plan.md`의 ## 검증 명령(C안)과 J134를 본다.

## 기대 커버리지

- **하한(낮추지 않음).** 줄 커버리지 80% 이상을 `contract-model` · `core-api` · `realtime-activity` 각각(JaCoCo LINE `0.80`)과 `frontend/src/**`(Vitest `thresholds.lines` 80)에 따로 적용한다(J107 · J91 · cicd 2.2). 하한 미달이면 시험을 더한다. 설정의 임계값을 낮추거나 제외 목록을 늘리지 않으며, 제외가 꼭 필요하면 사람 승인을 받는다(cicd ## 5 열림 2). jsdom에서 실행할 수 없는 줄의 제외 후보는 Plan Approval 질문으로 올리고 승인 전에는 제외하지 않는다(J138).
- **적용 범위(J137).** 위 U1 하한 판정 명령은 U1이 만든 파일 목록(`backend/coverage-scope/u1.txt` · `frontend/coverage-scope/u1.json`, Step 5 · 6이 자리를 만들고 Step 53이 source-manifest로 채워 대조)만 잰다. 뒤 유닛이 같은 모듈 · `src/**`에 코드를 더해도 U1 명령이 다른 유닛 코드 때문에 실패하지 않게 하려는 것이다. 모듈 전체 80%는 태그 필터 없는 전체 시험으로 CI backend · frontend 작업과 검증 명령 A가 판정한다. 하한 80%와 빈 제외 목록은 그대로다.
- **분량(Standard).** 컴포넌트(시험 클래스가 겨냥하는 단위)마다 시험 5 ~ 8개, 각 클래스는 정상 경로 1개와 오류 · 경계 2개 이상을 둔다. `assert true`처럼 구현과 무관하게 통과하는 시험은 두지 않는다.
- **핵심 경계 통합 시험.** PostgreSQL(Testcontainers, 실제 `infra/postgres/init/`과 두 마이그레이션 위치), 앱 사이 HTTP(계약 대역이 `contracts/schemas`로 요청 · 응답 검사), WSS(실제 서버 · 무작위 포트에 시험 클라이언트로 핸드셰이크).
- **보고.** JaCoCo HTML · XML(`backend/*/build/reports/jacoco/`)과 Vitest 커버리지(`frontend/coverage/`)의 줄 비율을 모듈별로 code-summary.md에 적는다.

## 모의 · 대역 지침

- **Clock.** 모든 시각은 `MujoongClock` 주입으로 읽는다. 시험은 `kr.mujoong.testsupport.time.MutableClock`(설정 · 앞당기기, 앞당기면 단조 값도 같은 양만큼)과 `ManualTimerDriver.advance(d)`(Clock을 앞당기고 도래한 타이머를 마감 순서대로 실행)를 쓴다. `Thread.sleep`으로 실제 시간을 기다리지 않는다(NFR6.2). 시한 경계는 '한도 − 1틱 / 한도 + 1틱' 두 점을 본다. 예외는 운영 구동기 자신의 시험(`DelayQueueTimerDriverTest` · `SystemMujoongClockTest`)뿐이고, 수십 ms 시한을 상한 있는 래치 대기로만 확인한다(J138).
- **가짜 공급자.** 백엔드 시험은 `kr.mujoong.testsupport.provider.FakeProviderPort`(앱 안 이벤트 형식)와 `FakeProviderWsServer`(시험용 WS 서버, J110 선 형식, 받은 메시지 기록)를 쓴다. 시나리오: 정상(전사 · 출력 길이 지정) · 금지 패턴 응답 · 연결 거부 · `session.started` 없음 · 지연 · 연결 단계 무응답. C안은 `tools/fake-provider/`(별도 프로세스)를 쓴다. 실공급자는 어떤 시험에서도 부르지 않는다(`test` 프로필은 루프백 공급자 주소만 허용, J141). 공급자로 간 요청은 허용 필드 목록과 대조한다(`ProviderRequestWhitelistIT`, SD-20).
- **기기 시뮬레이터.** 백엔드 시험은 `kr.mujoong.testsupport.device.DeviceSimulatorClient`(JDK WebSocket, Cookie · Origin 헤더, HEARTBEAT 송신 직전 봉투 `sentAt`과 시계 오프셋 주입, 받은 0x02 바이트 기록), C안은 `tools/device-simulator/`를 쓴다. 시나리오: HELLO · HEARTBEAT 중단 · 열린 채 무음 · TCP 끊기 · ACK 안 보냄 · 늦은 ACK · 늦은 VOICE_SESSION_CLOSED · 재접속 · 두 연결 경합 · PLAYBACK_MARK · DEVICE_STATE.
- **PostgreSQL(J132 · J135).** `kr.mujoong.testsupport.db.MujoongPostgres`가 PostgreSQL 16을 띄운다. 백엔드는 `-Pmujoong.test.db=auto|docker|local`로 고르고(기본 auto: Docker가 없거나 고정 태그 pull이 실패하면 호스트 `/usr/lib/postgresql/16/bin`을 `runuser -u postgres`로 띄움), 어느 쪽이든 같은 `infra/postgres/init/` 스크립트(`PG_HBA_ALLOWED_CIDR`에 시험 출발지: docker는 브리지 게이트웨이 /32, local은 127.0.0.1/32)와 두 위치(`db/migration/core` · `db/migration/realtime`)를 적용한 뒤 역할별(`mujoong_owner` · `core_app` · `realtime_app` · `mujoong_backup`) DataSource를 준다. 앱 시험은 자기 앱 역할로 접속한다. 클래스마다 표를 비우거나 인스턴스를 재사용하되 시험끼리 행이 섞이지 않게 한다.
- **상대 앱 · 수신기 대역.** `kr.mujoong.testsupport.http.ContractStubServer`(JDK HttpServer, 계약 스키마로 요청 검증)와 `ReceiverDouble`(200 · 401 · 409 · 400 · 503 · 지연 · 시간 초과를 스크립트로 응답)을 쓴다.
- **`@MockitoBean`.** Spring 문맥 시험에서 협력 빈(예: `RealtimeSessionClient` · `VerifyTokenClient`)을 바꿀 때만 쓴다. 저장소 · DB 동작은 모킹하지 않고 Testcontainers로 시험한다.
- **프런트.** `fetch`는 `vi.stubGlobal`로, WebSocket은 시험용 가짜 소켓으로, `navigator.mediaDevices.getUserMedia` · Wake Lock · AudioContext는 `vi.fn` 대역으로 바꾼다. 시간 값은 TimingProvider에 줄인 값을 주입하고 `vi.useFakeTimers`로 진행한다. 라우팅은 `next/navigation` 대역.
- **장애 주입 훅은 시험 소스 집합과 `tools/`에만** 둔다. 운영 코드에 시험용 분기 · 원격 제어면을 두지 않는다(SD-25 · J81).

## 시험 데이터 관리

- **실행 때 생성.** DB 비밀번호 · 내부 토큰 · ownerHash 비밀 · 기기 토큰 · bootstrap 비밀은 `kr.mujoong.testsupport.secret.TestSecrets`가 실행마다 SecureRandom으로 만든다. C안의 값은 `infra/ci/gen-env.sh`가 `build/verify/env/`(umask 077)에 만들고 실행 끝에 지운다(J127).
- **비밀 없음.** 저장소의 시험 · 픽스처 · 스냅샷에 계정 · 비밀번호 · API 키 · 토큰 값 · 실제 도메인을 두지 않는다. origin은 `https://mujoong.invalid`(단위 · 통합) · `https://localhost:8443`(C안)만 쓴다(J108).
- **시드.** 시드 보호자 · 아이 · 동의 · 승인 문구는 코드 상수 고정 UUID와 `ON CONFLICT DO NOTHING`으로 멱등이다(J117). 시드 아이 '하늘' · 8세, 동의 4항목 GIVEN, 정책 버전 '1.0'(J114), 승인 문구 CLOSING · WAITING_FILLER, 임시 WAV 2개(J102). 모두 합성 값이고 개인정보가 없다(`mujoong_db.data_class = synthetic`).
- **전사 · 음성 픽스처.** 시험 전사 문장과 PCM 프레임은 합성 값으로 시험 코드 안에서 만든다. 로그 위생 시험은 이 문장 · 토큰 · 쿠키 · 전체 코드가 로그 · 오류 봉투 · 지표 · 전달 뒤 Outbox에 0건임을 단언한다.
- **격리.** 각 시험은 자기 데이터를 만들고 끝에 정리한다. 시각은 가변 Clock으로 고정해 결과가 실행 시각에 기대지 않게 한다.
- **산출물 위치.** `build/` · `coverage/` · `build/verify/`는 `.gitignore` 대상이다. 비밀 목록 파일(`build/verify/c/secrets.list`, 600)은 검증 명령의 로그 위생 단언에만 쓰고 지운다. 목록에는 실행 중 본 토큰 · 쿠키 · CSRF 토큰과 그 SHA-256, 등록 코드(8자 · 4 · 4 표시형), 아이 전사 · 통과한 AI 말 · 폐기된 금지 문장, 생성한 내부 토큰 · 공급자 키 자리값 · DB 비밀번호 5종 · ownerHash 비밀이 들어간다(계획 ## 검증 명령 B1 ③).
- **생성기 시험.** WAV 생성기 같은 생성기 시험은 임시 디렉터리에 만들어 커밋된 파일과 바이트만 비교하고, 저장소 파일을 다시 쓰지 않는다(검증 명령의 작업 트리 비교).

## 시험 클래스 지도(요약)

계획의 각 시험 단계에 클래스별 증명 ID가 있다. 여기서는 위치만 요약한다.

| 층 | 백엔드(`src/test/java/kr/mujoong/...`) | 프런트 · tools |
|---|---|---|
| 시험 지원(Step 12 · 28) | `testsupport/time/{ManualTimerDriverTest,MutableClockTest}` · `testsupport/http/ContractStubServerTest`(하한 대상 아님, J107) | — |
| 데이터 모델(Step 13 · 14) | `contract/**/*Test`(`time/DelayQueueTimerDriverTest` · `time/SystemMujoongClockTest` · `json/JsonCodecTest` 포함) · `ContractFilesTest` · `core/db/{CoreSchemaIT,SchemaPrivilegeIT,CoreConstraintIT,NoSqlNowTest,SeedReferenceSqlIT}` · `realtime/db/{RealtimeSchemaIT,RealtimeConstraintIT}` | `tools/tests/u1/seed-audio.test.mjs` |
| 데이터 접근(Step 17 · 18) | `core/{device,activity,messaging}/*RepositoryIT` · `core/common/db/DbRetryTest` · `realtime/{session,gateway,log,messaging}/*RepositoryIT` | — |
| 업무 로직(Step 27 ~ 30) | `core/{device,activity,messaging,startup,security}/*Test` · `core/**/*FlowIT` · `ActivityStartEndIT` · `SessionPackageIT` · `core/startup/ProfileGuardBootIT` · `DeletionParticipantsCoreIT` · `DevSeedRunnerIT` · `realtime/{session,gate,voice,log,gateway,startup}/*Test`(`startup/ProfileGuardEnvironmentPostProcessorTest` 포함) · `SessionLifecycleIT` · `ProviderClientIT` · `ProviderRequestWhitelistIT` · `realtime/startup/ProfileGuardBootIT` · `RealtimeMessagingIT` · `RealtimeStartupRecoveryIT` | — |
| API(Step 34 ~ 36) | `core/web/*IT` · `CookieAttributesTest` · `realtime/web/{RealtimeInternalApiIT,DeviceGatewayIT,RealtimeLogHygieneIT}` · `*/arch/*ArchTest` · `*/config/ConfigKeyContractTest` | — |
| 프런트(Step 41 · 42) | — | `frontend/tests/u1/{device,shared,parent,lint}/*.test.ts(x)`(`device/{resample,capture}.test.ts` · `shared/{csp.test.ts,routes.test.tsx}` 포함) |
| 시험 도구(Step 46 · 49) | — | `tools/tests/u1/*.test.mjs`(`dev-edge.test.mjs` · `verify-exit.test.mjs` 포함) |
