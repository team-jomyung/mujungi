# Team-Level Rules

> This team's affirmed practices and corrections. Loaded after `org.md` as
> strict-additive guidance; contradictions with broader policy are rejected.
> Populated by the practices-discovery affirmation gate. Edit at the gate,
> not directly.

## Way of Working

- 우리는 `main` 하나를 트렁크로 두고, 짧게 사는 작업 브랜치(보통 당일 ~ 1일)에서 GitHub PR로 `main`에 합친다.
- **PR은 merge commit으로 합친다.** 작업 커밋을 `main` 이력에 그대로 남기고, squash로 바꾸지 않는다. 조직 기본값(squash)과 다른 팀 선택이다(Q1-A).
  Construction 작업 트리도 `--base main --target main --strategy merge`로 만들고 합친다.
- 작업 브랜치는 `main`과 같은 커밋 위에서 새로 딴다. 브랜치가 오래 살면 `main`을 브랜치로 merge해 따라잡는다.
- 브랜치 이름은 용도 접두어를 쓴다: `unit/<유닛>-<이름>`(유닛 구현) · `docs/<주제>`(지시서 · 인계) · `codex/<주제>` · `claude/<주제>`(에이전트 작업 · 통합) · `snapshot/<이름>-<날짜>`(고정 사본) · `ops/<주제>`(실행 준비) · `feat/<주제>`(기능 · 동기화 작업). 유닛 지시서가 브랜치 이름을 정하면 그것을 따른다.
- 여러 유닛을 한꺼번에 합칠 때는 일회성 통합 브랜치에서 정해진 순서로 합친 뒤 PR 하나로 `main`에 올린다.
- 유닛 사이 병합 순서는 DB 마이그레이션 버전 순서를 따른다(Flyway `outOfOrder` 꺼짐). 다음 번호는 core V8 · realtime V5다.
- 커밋 메시지는 `<종류>(<범위>): <한국어 요약>` 형식이다(`feat` · `fix` · `docs` · `test` · `chore` · `refactor` 등). 본문은 한국어로 쓴다.
- AI-DLC 작업 기록(`aidlc/`)도 함께 커밋한다. 담당자마다 자기 PC에서 작업 기록을 새로 만들고, 남의 기록은 이어 가지 않는다.
- 병합 관문: `main`에는 브랜치 보호가 있고 `ci-gate`가 필수 검사다(Q6-A). `ci-gate`가 통과해야 합친다.
  `ci-gate`는 경로 필터를 따르므로 `docs/` · `aidlc/` · 루트 Markdown만 바뀐 PR은 모든 작업이 건너뛰어진 채 통과한다. `contracts/` 또는 `.github/`가 바뀌면 전 작업이 돈다.
- **[확인 필요]** PR 병합에 리뷰 승인이 필요한지(필수 리뷰어 수)는 확인되지 않았다(Q6에서 선택하지 않음). 팀 문서(`docs/units/README.md`)는 "리뷰를 거쳐 main에 합친다"고 적는다.

## Walking Skeleton

- 끝까지 이어지는 가장 얇은 조각은 U1에서 이미 만들었다(두 앱 + PostgreSQL + Nginx + 웹, 기기 시뮬레이터와 가짜 공급자로 자유 대화 한 턴).
  우리는 새 스켈레톤을 만들지 않고, 이 끝-끝 경로가 계속 통과하는 것을 회귀 기준으로 삼는다(Q2-A).
- 회귀 기준 명령은 `bash scripts/verify.sh`다(A 정적 · 단위 · 통합 → B 기동 · 3종 호출 → C 기기 시뮬레이터 한 턴 → 사후 단언 P1 · P2 · P3 · T).
  CI `skeleton` 작업이 `main` 푸시 · 수동 실행마다 같은 명령(`--mode compose`)을 돈다. PR에서는 돌지 않는다.
- `classic` 범위는 `skeleton: off`라서 스켈레톤 체크포인트 의식을 하지 않고, Construction에 들어갈 때 진행 방식(자동 진행 / 체크포인트마다 검토)을 고른다.
- 작업 단계마다의 검증(Q4 · Q4-1):
  - 작업 단계가 끝날 때마다 **바꾼 쪽**의 정적 검사 · 단위 · 통합 시험을 돌린다(백엔드: `cd backend && ./gradlew clean check -Pmujoong.test.db=docker`, 프런트: `eslint` · `vitest --coverage` · `next build`, tools: `vitest`).
  - 전체 `bash scripts/verify.sh`는 Build and Test에서 한 번 돌린다.
  - JDK 21은 백엔드 시험이나 `verify.sh`를 이 PC에서 돌려야 할 때 설치한다. `verify.sh`는 실행 방식(auto · compose · host)과 관계없이 처음에 JDK 21을 찾으므로, 전체 검증 전에는 반드시 설치해야 한다.

## Testing Posture

- **Methodology**: test-after
- **Ordering**: 시험할 수 있는 층을 하나 구현하면, 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 넘어간다.
- 계약에 닿는 변경도 같은 순서를 따른다(Q3-A). 다만 설계 문서 변경이 계약에 닿으면 같은 작업에서 `contracts/`도 함께 고친다(`discovered-rules.md` R-M10).
- 커버리지(줄 기준만, 분기 · 함수 하한 없음)는 **보고 전용**이다(JIRA-256). CI는 보고서를 만들되 하한으로 막지 않는다:
  - 모듈 전체 줄 커버리지를 CI가 보고한다(80%는 목표치, 막지 않음). 백엔드 JaCoCo는 `contract-model` · `core-api` · `realtime-activity`(`test-support`는 제외), 프런트 Vitest v8은 `src/**/*.{ts,tsx}`(`*.d.ts`만 제외).
  - 범위 파일(`backend/coverage-scope/<기능>.txt` · `frontend/coverage-scope/<기능>.json`)은 로컬 · 검토 근거다. CI는 범위를 따로 다시 돌리지 않는다.
  - `tools/`에는 커버리지 하한이 없다(시험 지원 코드, J107).
  - 범위 측정은 빈 목록 · 없는 파일이면 실패한다(J137). 범위 파일을 새로 쓰면 이 방식을 그대로 따른다.
  - 병합 전 CI 실행은 `classic` 범위와 같고, 줄 80%는 막는 하한이 아니라 보고 목표다.
- 대조 시험 · ArchUnit 시험은 시험을 통과시키려고 약하게 고치거나 끄거나 건너뛰게 하지 않는다. 마이그레이션 개수 · 표 합계 · 최고 버전 같은 숫자 고정 단언은 의미 있는 검사로 바꿀 수 있다(R-F2).
- CI 재시도: 실패한 시험은 CI(환경 변수 `CI`)에서만 최대 2회 더 돈다(백엔드 Gradle `org.gradle.test-retry` `maxRetries=2` · `maxFailures=10`, 프런트 · tools Vitest `retry`). 로컬은 재시도하지 않는다. 재시도 기록은 백엔드 JUnit XML(`flakyFailure`), 프런트 · tools `test-results/retries.json`(재시도 횟수 · 최초 실패, artifact) · 로그 경고 · 실행 요약에 남고(리포터 오류는 경고만, 판정 불변), 재시도 뒤에도 실패하면 `ci-gate`가 막는다. 모든 시험은 계속 `ci-gate`를 막는다.
- 시험 종류:
  - 단위: JUnit 5 · AssertJ · Mockito(백엔드), Vitest + Testing Library + jsdom(프런트), Vitest(tools).
  - 통합: Spring Boot 통합 시험 + Testcontainers PostgreSQL(`-Pmujoong.test.db=docker`), 상대 앱은 스텁.
  - 계약 대조: `ContractFilesTest` · `ErrorCodeTest` · `ConfigKeyContractTest` · `TimeLiteralScanTest` · `OpenApiContracts` · `frontend/tests/u1/shared/timing.test.ts` · `SeedDictionaryFileTest`가 `contracts/`와 코드를 대조한다.
  - 아키텍처: ArchUnit(`core/arch` · `realtime/arch`), ESLint `/parent` ↔ `/device` ↔ `shared` import 경계.
  - 끝-끝: `bash scripts/verify.sh` C안(기기 시뮬레이터 + 가짜 공급자).
  - 인프라: `infra-validate`(Compose 불변식 · `nginx -t` · shellcheck · 마이그레이션 expand 검사), 이미지 점검, 관문 음성 대조.
- 시험 이름 · 태그: 단위 `*Test`, 통합 `*IT`, 백엔드 유닛 태그 `@Tag("u1")` ~ `@Tag("u7")`와 `@Tag("db")`, 프런트 · tools는 `tests/<유닛>/` 폴더.
- 관문의 한계: PR의 `ci-gate`는 정적 · 단위 · 통합(커버리지는 보고 전용) · 보안 · 인프라 · 이미지까지 본다. 끝-끝 회귀(`skeleton`)는 `main` 병합 뒤에 처음 돈다. 그래서 이번 작업은 Build and Test의 전체 `verify.sh`로 병합 전 끝-끝을 확인한다.
- 시험 · CI · 검증에서는 가짜 공급자만 쓴다(ADR-002).
- 결과 보고는 테스트 대역 · 합성 대화 · 실제 API · 실제 기기 성공을 따로 적고, 대역 성공을 실공급자 성공으로 표시하지 않는다(`discovered-rules.md` R-M11, Q10-A).
- 이번 작업 기록의 시험 전략은 Standard다(컴포넌트당 5 ~ 8개 + 핵심 경계 통합 시험).

- 프런트 · tools 시험은 소스 구조 폴더(`tests/parent/features/…` · `tests/shared/…`)에 두고, 백엔드 시험 태그는 유닛 번호 대신 기능 이름(`practice-legacy` · `web-push` · `skeleton` 등)을 쓴다. 앞의 `tests/<유닛>/`과 `@Tag("u1")` ~ `@Tag("u7")` 규칙을 대체한다. (learned 2026-10-19) <!-- cid:261018-structure-refactor:code-generation:a432bd7909595cbb209f549434d0d3846f7f7a0e8beb8d49b185e9c47f6d0f1d -->

- 커버리지 범위 파일은 유닛 번호 대신 기능 이름으로 짓는다(`backend/coverage-scope/<기능>.txt` · `frontend/coverage-scope/<기능>.json`과 시험 목록 `<기능>.tests`). 앞의 유닛 이름 범위 파일 규칙을 대체한다. (learned 2026-10-19) <!-- cid:261018-structure-refactor:code-generation:098665fc1a2767bd9d8e8451bf76fbbcdc4818372abf5efbd1ae2c842d6babe1 -->

- CI가 막는 커버리지 범위는 없다(JIRA-256). 프런트 `practice-legacy` · `web-push` 범위 재실행 단계는 중복 실행이라 `ci.yml`에서 뺐고, 모든 범위 파일은 로컬 · 검토 근거다. 앞의 "CI가 실제로 막는 범위는 U5b · U7뿐"(백엔드 `u5b` · `u5b-core` · `u7`)과 "프런트 `practice-legacy` · `web-push`뿐"을 대체한다. (learned 2026-10-19, revised 2026-10-22) <!-- cid:261018-structure-refactor:build-and-test:785d6e181cf556394ec87ff413be7d22caf29276e1eaa009185a48e3a034bf10 -->

## Guard Policy

<!-- Affirmed by the team. Mode: strict, relaxed, or off. Strict here holds for every intent and cannot be changed from chat. A section under the retired Change Control heading, written by an earlier release, is still read. -->

## Deployment

- 우리는 `main` 병합마다 `ci-gate`가 통과하면 **demo 환경**에 자동 배포하는 것을 팀 방식으로 둔다(Q5-A).
  경로: GitHub Actions → OIDC 역할 `mujoong-gha-deploy`(신뢰 `environment:demo`) → SSM 문서 `mujoong-deploy` → 호스트 `/opt/mujoong/bin/deploy.sh`.
- 이미지 6종은 커밋 SHA 태그로 GHCR에 `main` 병합 때만 올리고, 이미 있는 태그는 덮어쓰지 않는다. 배포할 수 있는 것은 GHCR에 이미 있는 SHA 태그뿐이다.
- demo는 EC2 1대(`ap-northeast-2`, Docker Compose, IaC 없음)이고 유일한 원격 환경이다. staging / production 구분은 없어서, 조직 기본값의 "staging 자동 · production 수동 승인" 구조 대신 demo 하나에 자동 배포한다.
- 지금은 저장소 변수 `SERVICE_HOST` · `AUTO_DEPLOY`가 없어 배포 단계가 건너뛰어진다. `AUTO_DEPLOY=false`는 시연 · 측정 창 · 롤백 중 동결 스위치이고, 수동 실행은 동결과 무관하다.
- 배포 뒤 러너가 공개 도메인으로 3종 호출(health · `/device` · WSS 101)과 인증서 만료 20일 이상을 확인한다. 실패는 배포 실패로 표시하고 자동 롤백은 없다.
  롤백은 `deploy.yml`을 `mode=ROLLBACK`, `deploy.log`의 마지막 `result=OK` SHA로 수동 실행한다.
- 동시성: 배포 그룹 하나(`mujoong-deploy-demo`), 진행 중 배포를 취소하지 않고 대기열에는 최신 하나만 남긴다.
- 로컬 실행 구성은 셋이다: dev(`infra/compose.dev.yml`), 검증 스택(`compose.demo.yml` + `compose.ci.yml`, 일회성 · `127.0.0.1:8443`), host 모드(Docker 없이).
- 예약 실행은 매주 월요일 03:23 KST에 의존성 취약점 · 비밀 스캔만 돈다(이미지 재스캔은 하지 않음).
- **이번 작업은 배포를 다루지 않는다**(Q5-A). 운영 AI 어댑터 등으로 새 비밀이 생기면 비밀 이름 목록을 함께 고친다(R-M9).
- **[확인 필요]** GitHub 환경 `demo`를 `main` 브랜치에서만 배포하도록 제한했는지는 확인되지 않았다(Q6에서 선택하지 않음). OIDC 신뢰가 `environment:demo`에 묶여 있으므로 이 제한이 배포 권한의 실제 경계다.

## Code Style

- 서식(Q7-A): **이번 작업에서 손대는 파일만** 다른 유닛 관례(한 줄 한 문장 · 연산자 주위 공백 · Java 4칸 들여쓰기)로 읽기 좋게 정리한다. 저장소 전체 포매터 도입 · 전면 재서식은 하지 않는다.
  - 원인 정리: `.prettierignore`가 `backend/`를 통째로 빼므로 U6 Java 압축 서식은 Prettier와 무관하고, **Java 포매터 · 줄 길이 검사가 처음부터 없어서** 들어왔다. Prettier는 설정(`printWidth 100`)만 있고 어느 `package.json`에도 없어 실행되지 않는다. 이것이 놓친 것은 프런트 쪽 긴 줄뿐이다.
  - `printWidth 100`은 실제 관례가 아니다(Java 100자 초과 10,186줄). 손대는 파일을 정리할 때 이 값을 강제 기준으로 쓰지 않는다.
- 공통: `.editorconfig`(UTF-8 · LF · 끝 줄바꿈 · 끝 공백 제거 · 2칸, Java · Kotlin · SQL 4칸, Markdown은 끝 공백 유지).
- 프런트(TypeScript 5.9 · Next.js 16 · React 19): ESLint flat config(`eslint-config-next` core-web-vitals + typescript)에 영역 import 경계(`no-restricted-imports` + `import/no-restricted-paths`, 라우트 `src/app/{parent,device}` 포함) · `react/no-danger` · `react/jsx-no-literals`(화면 문구는 copy 모듈만) · `getUserMedia` 사용처 한 곳 · 서비스 워커 금지(U7 보호자 SW 예외). CI가 `eslint .` 실패로 막는다.
- 백엔드(Java 21 · Spring Boot 4.1 · Gradle 8.14): `build-logic`의 `mujoong.java-conventions`가 `-Xlint:all,-processing,-serial,-this-escape,-try`를 켜고 `-Werror`는 없다(경고가 빌드를 막지 않음). 계층 · 의존 경계는 ArchUnit 시험으로 강제한다.
- 셸: `infra-validate`가 shellcheck를 돈다.
- 구성 · 이름:
  - 백엔드는 기능별 패키지(`kr.mujoong.core.{account, practice, result, report, …}`, `kr.mujoong.realtime.{session, voice, dialog, gateway, …}`)이고, 컨트롤러는 기능 패키지 아래 `web`에 둔다.
  - 이름 접미어: `*Service` · `*Repository` · `*Port`(기능 사이 경계) · `*Adapter`(구현) · `*Configuration` · `*Participant`.
  - 시험 클래스는 `Test` · `Tests` · `IT` · `TestCase` 접미어를 지키고, 운영 클래스에는 이 접미어를 쓰지 않는다(`verifyBootJarHasNoTestClasses`).
  - 프런트 화면 파일은 화면 설계 번호를 이름 끝에 붙인다(`Login01.tsx` · `Report30.tsx` · `Register05a.tsx`).
  - 이름은 언어 관례(TS camelCase, Java camelCase/PascalCase, SQL snake_case)를 따른다. 주석 · 문서 · 커밋 본문은 한국어, 식별자는 영어.
  - 코드 주석은 결정 ID(J · BR · U*-J · 편차)를 인용하고, TODO · FIXME 대신 남은 일을 문서로 관리한다.
- 오류 처리 · 로그 · 시각(기준선 관례):
  - 오류 코드는 `backend/contract-model/.../error/ErrorCode.java` enum과 `contracts/errors.md` 표 한 곳에서만 정하고, `ContractFilesTest`가 대조한다. 받는 쪽은 모르는 code를 일반 오류로 처리한다.
  - 업무 거절은 `CoreApiException.of(ErrorCode)`로 던지고, 앱마다 하나인 `ApiExceptionHandler`가 봉투 `{code, message, traceId}`로 바꾼다. message는 code별 고정 문장이며 입력값 · 전사 · 예외 메시지 · 스택을 담지 않는다. 보호자에게는 `ErrorCode#forGuardian()`으로 번역해 내보낸다.
  - 화면 문구는 code로 고른다(`frontend/src/shared/errors/codeToCopy.ts` + `shared/copy/*`).
  - 운영 코드의 오류 기록은 `EventLog`(`events.appError(EventLog.Component.…, e)`)로 하고, 운영 앱 코드에서 slf4j `LoggerFactory`를 직접 쓰지 않는다.
  - 넓은 catch는 ① `events.appError` 후 계속(근거 주석 동반) ② 고정 code 결과로 번역 ③ 의미 있는 예외로 다시 던짐 중 하나로 쓰고, 주석 없이 삼키지 않는다.
  - 시각은 `kr.mujoong.contract.time.MujoongClock` 주입으로만 읽고, 시간 값은 `mujoong.timing.*` 설정 키에서만 읽는다.
- **[확인 필요]** 새 저장소 코드를 JDBC(JdbcClient/JdbcTemplate)로 쓰는 것을 관례로 둘지(실제 55파일 대 JPA 7파일), 화면 번호가 바뀌거나 화면이 나뉠 때 파일 · 시험 이름을 같이 바꿀지는 인터뷰에서 묻지 않았다.
- 프런트 화면 파일은 화면 설계 번호 접미 없이 `<기능>Screen.tsx`로 이름 짓고 `parent/features/<기능>/` · `device/features/<기능>/`에 둔다. 화면 번호는 `docs/screen-map.md`에서 찾는다. 앞의 "화면 파일 이름 끝에 화면 설계 번호를 붙인다"(`Login01.tsx` 등)를 대체한다. (learned 2026-10-19) <!-- cid:261018-structure-refactor:code-generation:6e0a4e4816383adc83ce9b2ba298dcbfdc2669a24d1d2bc6cc1fb8ea885f09dc -->

- 코드 주석은 "무엇을 왜"만 쓰고, 결정 ID(J · BR · U*-J · 편차)는 필요할 때 한 줄 문서 링크로 가리킨다. 앞의 "코드 주석은 결정 ID를 인용한다"를 대체한다. (learned 2026-10-19) <!-- cid:261018-structure-refactor:code-generation:79e0fd466d4479a5a00526ede28fc6a4b2bc9da9b7b35008a479977e8cb08149 -->

## Forbidden

<!-- Team-specific forbidden patterns -->

## Mandated

<!-- Team-specific mandates -->

## Corrections

<!-- Self-learning loop appends here. -->
