**Collaborator:** aidlc-developer-agent

## Contribution

> 기준: 브랜치 `ops/local-run-readiness`(origin/main `72d7034`). 소스와 리드 초안은 고치지 않고 읽기만 했다.
> 관점: 이름 규칙 · 모듈/계층 경계와 그 강제 방식 · 오류 처리 · 파일 배치 · 코드 스타일, 그리고 로컬 현장 실행 준비에 걸리는 개발 쪽 사실.

### 1. 이름 규칙

- Java 패키지는 `kr.mujoong.<앱>.<기능>`이다(`kr.mujoong.core.result`, `kr.mujoong.realtime.session`, `kr.mujoong.contract.error`, `kr.mujoong.testsupport.*`). 클래스 이름은 역할 접미어를 쓴다: `*Service` · `*Repository`(`DeviceJdbcRepository`처럼 구현 방식을 붙이기도 함) · `*Handler` · `*Mapper` · `*Scheduler` · `*DeletionParticipant` · `*Exception`.
- 시험 클래스는 단위 `*Test`(196개), 통합 `*IT`(106개)로 나눈다. 이 접미어는 관례만이 아니라 빌드가 쓴다: `mujoong.java-conventions.gradle.kts`의 `verifyBootJarHasNoTestClasses`가 `Test` · `Tests` · `IT` · `TestCase`로 끝나는 클래스, `kr/mujoong/testsupport/`, `application-test.*`, `db/testmigration/`, 시험 라이브러리가 운영 jar에 들어가면 빌드를 실패시킨다(205 ~ 240행).
- 프런트는 컴포넌트 파일 PascalCase(`DeviceApp.tsx`, `ParentProviders.tsx`), 모듈 camelCase(`navigation.ts`, `codeToCopy.ts`), 영역 디렉터리 소문자(`device/mic`, `parent/push`). ArchUnit 시험 메서드 이름은 영어 문장형(`coreNeverReadsTheSystemClockDirectly`)이다.
- 리드 초안의 "식별자는 영어, 주석 · 문서 · 커밋 본문은 한국어"는 코드와 맞다. Javadoc · ESLint 메시지 · Gradle 예외 메시지까지 한국어이고, 주석에 설계 ID(`BR8.6`, `NFR11.1`, `PD-15`, `U1 3.5 Step 31`)를 달아 추적한다.

### 2. 모듈 · 계층 경계와 강제 방식

리드 초안은 "경계는 ArchUnit 시험으로 강제"라고 적었는데, **무엇을** 강제하는지 좁혀 적어야 한다. ArchUnit은 컨트롤러 → 서비스 → 저장소 같은 고전 계층을 검사하지 않는다(`layeredArchitecture` 사용 없음). 실제로 강제하는 것은 다음이다.

| 경계 | 강제 위치 |
|---|---|
| core-api ↛ realtime-activity 코드 · `realtime` 스키마, 그 반대도 금지 | `CoreArchTest.NO_REALTIME_REFERENCE` · SQL 리터럴 스캐너, `RealtimeArchTest.NO_CORE_REFERENCE` |
| 운영 코드 ↛ `test-support` | 두 앱의 `NO_TEST_CODE_IN_PRODUCTION` + 위 `verifyBootJarHasNoTestClasses` |
| 시스템 시계 직접 읽기 금지(주입한 시계만) | `NO_SYSTEM_TIME`(core · realtime), contract-model은 `SystemMujoongClock` 한 곳만 허용, `TimeLiteralScanTest`(시간 값 리터럴 금지) |
| 공급자 WebSocket은 realtime `voice` 패키지에서만, 요청은 팩토리에서만 | `RealtimeArchTest.PROVIDER_CLIENT_ONLY_IN_VOICE` · `PROVIDER_REQUESTS_ONLY_FROM_THE_FACTORY`, core는 `java.net.http.WebSocket` 금지 |
| 오디오 프레임 경로에서 JDBC · 파일 · outbox 금지 | `RealtimeArchTest.framePathNeverTouchesJdbcFilesOrTheOutbox` |
| 세션 사본 상태 변경은 허용된 호출자만(BR6.17), 단일 출처 | `CoreArchTest` 사본 규칙, `U2SingleSourceTest`, `SingleInstanceMarkerTest` |
| contract-model은 프레임워크 무의존(Spring 애너테이션 · Entity · Repository 이름 금지) | `ContractModelArchTest` |
| 프런트 `/parent` ↔ `/device` 상호 import 금지, `shared`는 둘 다 금지, `getUserMedia` 한 곳, 서비스 워커 한 곳, 화면 문구는 copy 모듈 | `frontend/eslint.config.mjs`(`no-restricted-imports` + `import/no-restricted-paths`, `react/jsx-no-literals`, `react/no-danger`) |

각 ArchUnit 규칙에는 "규칙이 표본을 실제로 잡는지" 확인하는 짝 시험이 붙어 있다(예: `timeRuleFlagsDirectReadsAndAcceptsTheInjectedClock`). 규칙이 공허하게 통과하지 않게 하는 이 짝 시험 관례도 팀 관행으로 볼 만하다.

### 3. 오류 처리

- 백엔드: 업무 오류는 `CoreApiException(ErrorCode)`로 던지고, `@RestControllerAdvice` `ApiExceptionHandler`가 contract-model의 `ErrorEnvelope`로 바꾼다. `ErrorCode`는 contract-model 한 곳이 출처다. realtime-activity도 자기 `web/ApiExceptionHandler`를 둔다.
- 알 수 없는 예외는 500 `INTERNAL_ERROR`로, 응답에 스택 · 예외 메시지 · 입력값을 싣지 않고 로그도 `APP_ERROR` 한 줄에 예외 클래스 단순 이름(`errorKind`)만 남긴다(DB 오류 DETAIL에 키 값이 섞일 수 있어서). 형식 오류는 400 `VALIDATION_FAILED`, 없는 경로는 404(허용 메서드 비공개), 준비 전은 `SERVICE_NOT_READY` + `Retry-After`.
- 생성자 의존성은 `Objects.requireNonNull(x, "x")`로 바로 검사한다(fail fast).
- 운영 코드의 넓은 `catch (Exception | RuntimeException | Throwable)`은 83곳이다. 거칠게 세면 대부분(약 75곳)이 두 줄 안에서 이벤트 로그 · 재던지기 · 기록을 한다. 나머지 몇 곳이 조용히 삼키는지는 이번에 하나씩 보지 않았다 — 로컬 현장 실행에서 원인 모를 침묵 실패가 나면 먼저 볼 곳이다.
- 프런트: 서버 오류 코드를 화면 문구로 바꾸는 곳은 `src/shared/errors/codeToCopy.ts` 한 곳이다.
- 억제 표시: 프런트 `eslint-disable` 0건, 백엔드 운영 코드 `@SuppressWarnings` 9건, TODO · FIXME · HACK 0건.

### 4. 파일 배치

- 백엔드 Gradle 다중 모듈: `contract-model`(공용 계약 · 오류 · 시간 · 로그 모델), `core-api`, `realtime-activity`, `test-support`(시험 전용), 공통 빌드 규칙은 `build-logic`(`mujoong.java-conventions`).
- 앱 안은 **기능별 패키지**(package-by-feature)이고 기능 안은 평평하다. HTTP 진입점만 `<기능>/web` 하위 패키지로 뺀다(`activity/web`, `notification/web`). 앱 공통 웹 필터 · 오류 처리기는 최상위 `web`, 공통 기반은 `common/{db,error,deletion,scaling}`.
- 데이터 접근은 JPA가 아니라 JDBC(`JdbcClient` · `JdbcTemplate`) 기반 `*Repository`이고, 스키마는 Flyway가 앱별로 소유한다(core / realtime 스키마 분리 — 위 ArchUnit SQL 스캐너가 지킴).
- 프런트: `src/app`(Next 라우트, `parent` · `device` · `healthz`), `src/parent`, `src/device`, `src/shared`, `src/proxy.ts`. 시험은 소스 옆이 아니라 `frontend/tests/<유닛>/`(u1 ~ u7)에 유닛별로 모으고, 커버리지 범위도 `frontend/coverage-scope/<유닛>.json`으로 유닛별이다. 백엔드 시험은 모듈별 `src/test`, 유닛 범위는 `backend/coverage-scope/<유닛>.txt`.

### 5. 코드 스타일 — 리드 초안 정정

- **포매터 "없음"은 사실과 다르다.** 저장소 루트에 `.prettierrc.json`(printWidth 100 · 세미콜론 · 큰따옴표 · trailingComma all · LF)과 `.prettierignore`(`backend/` · `docs/` · `aidlc/` 등 제외)가 U1 첫 커밋 `a3a104a`(3.5 Step 2)부터 있다. 다만 `prettier`는 어느 `package.json`의 의존성에도 없고(`npx --no-install prettier`가 실패), CI · `scripts/verify.sh`에 `prettier --check` 단계가 없다. 즉 **설정은 있으나 설치 · 강제가 없다**. Java 쪽 포매터(Spotless · google-java-format · Checkstyle)는 정말 없고, `.prettierignore`가 `backend/`를 뺀다.
- 그러므로 인터뷰 질문 8은 "포매터를 도입할까"가 아니라 "이미 정한 Prettier 설정을 devDependency + CI 검사로 실제로 강제할까, 아니면 설정 파일을 지울까 / 백엔드 포매터는 둘까"로 바꾸는 편이 정확하다.
- 나머지는 리드 초안과 같다: `.editorconfig`, ESLint flat config(CI `npx --no-install eslint .`), `tsconfig` `strict: true`, `-Xlint:all,-processing,-serial,-this-escape,-try`(경고만, `-Werror` 없음). 타입 검사는 CI에서 별도 `tsc` 단계 없이 `next build`가 맡는다(`package.json`에 `typecheck` 스크립트는 있음).

### 6. 로컬 현장 실행 준비에 걸리는 개발 쪽 사실

1. **JDK 21이 꼭 있어야 하고 Gradle이 대신 받아 주지 않는다.** `build-logic`이 툴체인 `languageVersion 21`을 요구하는데 `settings.gradle.kts`에 foojay 툴체인 해석기가 없어 자동 내려받기가 안 된다. Gradle 래퍼(8.14.3)를 돌릴 JDK도 필요하다. 이 PC는 `java`가 PATH에 없다. 현장 PC 준비 목록 첫 줄에 "JDK 21 설치 + `JAVA_HOME`"을 둬야 한다(M1과 같은 뿌리).
2. **Next.js standalone 정적 자원 복사.** `npm run start`는 `.next/standalone/server.js`를 띄우지만 standalone 출력은 `.next/static`을 복사하지 않는다. `scripts/verify/a-checks.sh`와 `frontend/Dockerfile`은 복사를 하지만, 사람이 손으로 `next build` → `npm run start`를 하면 정적 자원 없이 뜬다. 현장 실행 절차는 이미지(Compose) 경로를 쓰거나 복사 단계를 명시해야 한다.
3. 시간 설정(`mujoong.timing.*`)은 빌드 때 굽지 않고 기동 환경 변수에서 읽는다(`next.config.ts` 주석). 현장 구성의 환경 변수 목록에 넣어야 한다.
4. 운영 jar에 시험 로그인 · 시험 마이그레이션이 들어가지 않는 것은 빌드가 막는다(§1). 따라서 "현장에서 시험 로그인을 쓰고 싶다"는 요구가 나오면 운영 jar가 아니라 검증 스택(dev 프로필) 쪽 이야기가 되므로, 현장 구성이 어느 쪽인지(리드 인터뷰 질문 3 · 4)가 먼저 정해져야 한다.

### 7. discovered-rules에 더할 후보 (코드가 이미 강제하는 것)

- NEVER 운영 코드에서 시스템 시계를 직접 읽지 않는다. 주입한 시계만 쓴다. (ArchUnit `NO_SYSTEM_TIME`, contract-model 어댑터 한 곳만 예외)
- NEVER core-api와 realtime-activity가 서로의 코드 · DB 스키마를 참조하지 않는다. 앱 사이는 계약(contract-model · 내부 HTTP · 이벤트)으로만 잇는다. (ArchUnit 두 앱)
- NEVER 운영 코드 · 운영 jar에 `test-support` · 시험 클래스 · 시험 마이그레이션을 넣지 않는다. (ArchUnit + `verifyBootJarHasNoTestClasses`)
- ALWAYS 500 응답과 오류 로그에는 스택 · 예외 메시지 · 입력값을 싣지 않고 오류 코드와 예외 종류만 남긴다. (`ApiExceptionHandler`)
- ALWAYS 화면 문구는 copy 모듈에서만 가져온다. (ESLint `react/jsx-no-literals`)

## Positions

- AGREE: Code Style의 editorconfig · ESLint flat config · `strict: true` · `-Xlint:all` · shellcheck 서술 — 설정 파일과 CI 단계로 그대로 확인했다.
- OBJECT: Code Style "포매터가 없다(Prettier 설정 없음)" — 루트에 `.prettierrc.json` · `.prettierignore`가 U1부터 있고, 빠진 것은 설치와 CI 강제다. 확인 필요 항목과 인터뷰 질문 8을 그에 맞게 고쳐야 한다.
- OBJECT: Code Style "경계는 ArchUnit 시험으로 강제" — 고전 계층(컨트롤러 · 서비스 · 저장소)이 아니라 앱 격리 · 시험 코드 격리 · 시계 · 공급자 접근 · 단일 출처를 강제하므로, 강제 대상을 구체적으로 적어야 팀 규칙이 과장되지 않는다.
- AGREE: Forbidden "`/parent`와 `/device` 상호 import 금지, 공용 계층은 둘 다 금지" — `eslint.config.mjs`가 문자열 import와 해석된 경로 둘 다로 막고 CI `eslint .`가 실패시킨다.
- AGREE: Testing Posture의 test-after · 80% 하한(모듈 + 유닛 범위) · 하한을 낮추지 않음 — JaCoCo `COVEREDRATIO 0.80`, Vitest coverage, `coverage-scope/<유닛>` 파일로 확인했다. 단 test-after는 상속된 기본값이라 확인 필요 표시가 맞다.
- AGREE: Walking Skeleton "이 PC에 JDK 21 없음" 확인 필요 — 더해서 foojay 툴체인 해석기가 없어 Gradle이 JDK를 받아 주지 않으므로 현장 PC에도 사람이 직접 설치해야 한다.
- AGREE: discovered-rules의 나머지 후보(ci-gate · 마이그레이션 순서 · SHA 고정 · 비밀 · 가짜 공급자 · 검증 스택 demo 금지) — 개발 관점에서 반대 근거를 찾지 못했다. 다만 §7의 코드 강제 규칙 5개를 함께 후보로 올리기를 제안한다.
- AGREE: Way of Working 병합 방식(merge commit vs squash) 확인 필요 — 코드 쪽 근거로는 결정할 수 없고 사람 판단이 필요하다.
