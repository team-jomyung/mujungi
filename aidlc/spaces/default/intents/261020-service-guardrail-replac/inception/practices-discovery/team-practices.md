# Team Practices

> Q1에서 기존 방식 유지와 현재 구성 설명 갱신을 선택했다. 인터뷰 통합본이며 아직 관례 확정 게이트 승인·memory 승격 전이다. 기존 2026-10-19 학습의 시험·이름·주석 대체 규칙을 현재 문장에 통합했다.

## Way of Working
- 우리는 `main` 하나를 트렁크로 두고, 짧게 사는 작업 브랜치(보통 당일 ~ 1일)에서 GitHub PR로 `main`에 합친다.
- **PR은 merge commit으로 합친다.** 작업 커밋을 `main` 이력에 그대로 남기고, squash로 바꾸지 않는다. 조직 기본값(squash)과 다른 팀 선택이다(Q1-A).
  Construction 작업 트리도 `--base main --target main --strategy merge`로 만들고 합친다.
- 작업 브랜치는 `main`과 같은 커밋 위에서 새로 딴다. 브랜치가 오래 살면 `main`을 브랜치로 merge해 따라잡는다.
- 브랜치 이름은 용도 접두어를 쓴다: `unit/<유닛>-<이름>`(유닛 구현) · `docs/<주제>`(지시서 · 인계) · `codex/<주제>` · `claude/<주제>`(에이전트 작업 · 통합) · `snapshot/<이름>-<날짜>`(고정 사본) · `ops/<주제>`(실행 준비) · `feat/<주제>`(기능 · 동기화 작업). 유닛 지시서가 브랜치 이름을 정하면 그것을 따른다.
- 여러 유닛을 한꺼번에 합칠 때는 일회성 통합 브랜치에서 정해진 순서로 합친 뒤 PR 하나로 `main`에 올린다.
- 유닛 사이 병합 순서는 DB 마이그레이션 버전 순서를 따른다(Flyway `outOfOrder` 꺼짐). 기존 마이그레이션 파일은 바꾸지 않고, 새 번호는 구현 전에 실제 파일 최댓값을 확인한다. 현재 CodeKB는 core V10 · realtime V7까지 기록한다.
- 커밋 메시지는 `<종류>(<범위>): <한국어 요약>` 형식이다(`feat` · `fix` · `docs` · `test` · `chore` · `refactor` 등). 본문은 한국어로 쓴다.
- AI-DLC 작업 기록(`aidlc/`)도 함께 커밋한다. 담당자마다 자기 PC에서 작업 기록을 새로 만들고, 남의 기록은 이어 가지 않는다.
- 병합 관문: `main`에는 브랜치 보호가 있고 `ci-gate`가 필수 검사다(Q6-A). `ci-gate`가 통과해야 합친다.
  `ci-gate`는 경로 필터를 따르므로 `docs/` · `aidlc/` · 루트 Markdown만 바뀐 PR은 모든 작업이 건너뛰어진 채 통과한다. `contracts/` 또는 `.github/`가 바뀌면 전 작업이 돈다.
- **[확인 필요]** PR 병합에 리뷰 승인이 필요한지(필수 리뷰어 수)는 확인되지 않았다(Q6에서 선택하지 않음). 팀 문서(`docs/units/README.md`)는 "리뷰를 거쳐 main에 합친다"고 적는다.

## Walking Skeleton
- 끝까지 이어지는 가장 얇은 조각은 U1에서 이미 만들었다(두 앱 + PostgreSQL + Nginx + 웹, 기기 시뮬레이터와 가짜 공급자로 자유 대화 한 턴).
  우리는 새 스켈레톤을 만들지 않고, 이 끝-끝 경로가 계속 통과하는 것을 회귀 기준으로 삼는다(Q2-A).
- 회귀 기준 명령은 `bash scripts/verify.sh`다(A 정적 · 단위 · 통합 → B 기동 · 3종 호출 → C 기기 시뮬레이터 한 턴 → 사후 단언 P1 · P2 · P3 · T).
  CI `skeleton` 작업이 `main` 푸시마다 같은 명령(`--mode compose`)을 돈다. PR에서는 돌지 않고, 현재 `ci-gate` 집계에서 제외된 임시 비차단 작업이다. 이 현황이 이번 전체 회귀 검증을 면제하지 않는다.
- `classic` 범위는 `skeleton: off`라서 스켈레톤 체크포인트 의식을 하지 않고, Construction에 들어갈 때 진행 방식(자동 진행 / 체크포인트마다 검토)을 고른다.
- 작업 단계마다의 검증(Q4 · Q4-1):
  - 작업 단계가 끝날 때마다 **바꾼 쪽**의 정적 검사 · 단위 · 통합 시험을 돌린다(백엔드: `cd backend && ./gradlew clean check -Pmujoong.test.db=docker`, 프런트: `eslint` · `vitest --coverage` · `next build`, tools: `vitest`).
  - 전체 `bash scripts/verify.sh`는 Build and Test에서 한 번 돌린다.
  - JDK 21은 백엔드 시험이나 `verify.sh`를 이 PC에서 돌려야 할 때 설치한다. `verify.sh`는 실행 방식(auto · compose · host)과 관계없이 처음에 JDK 21을 찾으므로, 전체 검증 전에는 반드시 설치해야 한다.

## Testing Posture
- **Methodology**: test-after
- **Ordering**: 시험할 수 있는 층을 하나 구현하면, 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 넘어간다.
- 계약에 닿는 변경도 같은 순서를 따른다(Q3-A). 다만 설계 문서 변경이 계약에 닿으면 같은 작업에서 `contracts/`도 함께 고친다(`discovered-rules.md` R-M10).
- 커버리지 하한(줄 기준만, 분기 · 함수 하한 없음):
  - 모듈 전체 줄 80%를 CI가 막는다. 백엔드 JaCoCo는 `contract-model` · `core-api` · `realtime-activity`(`test-support`는 제외), 프런트 Vitest v8은 `src/**/*.{ts,tsx}`(`*.d.ts`만 제외).
  - 범위 파일이 있는 기능에는 기능 범위 줄 80%를 적용한다(`backend/coverage-scope/<기능>.txt` · `frontend/coverage-scope/<기능>.json`, 시험 목록 `<기능>.tests`). 현재 CI가 막는 범위는 프런트 `practice-legacy` · `web-push`이며, 백엔드 범위 파일은 로컬 · 검토 근거다.
  - `tools/`에는 커버리지 하한이 없다(시험 지원 코드, J107).
  - 범위 측정은 빈 목록 · 없는 파일이면 실패한다(J137). 범위 파일을 새로 쓰면 이 방식을 그대로 따른다.
  - `classic` 범위의 줄 80% 하한 · 병합 전 CI 실행과 같다.
- 하한 · 기존 단언 · 대조 시험 · ArchUnit 시험은 시험을 통과시키려고 낮추거나 끄거나 건너뛰게 하지 않는다. 모자라면 시험을 더하거나 차이를 보고한다.
- 시험 종류:
  - 단위: JUnit 5 · AssertJ · Mockito(백엔드), Vitest + Testing Library + jsdom(프런트), Vitest(tools).
  - 통합: Spring Boot 통합 시험 + Testcontainers PostgreSQL(`-Pmujoong.test.db=docker`), 상대 앱은 스텁.
  - 계약 대조: `ContractFilesTest` · `ErrorCodeTest` · `ConfigKeyContractTest` · `TimeLiteralScanTest` · `OpenApiContracts` · `frontend/tests/u1/shared/timing.test.ts` · `SeedDictionaryFileTest`가 `contracts/`와 코드를 대조한다.
  - 아키텍처: ArchUnit(`core/arch` · `realtime/arch`), ESLint `/parent` ↔ `/device` ↔ `shared` import 경계.
  - 끝-끝: `bash scripts/verify.sh` C안(기기 시뮬레이터 + 가짜 공급자).
  - 인프라: `infra-validate`(Compose 불변식 · `nginx -t` · shellcheck · 마이그레이션 expand 검사), 이미지 점검, 관문 음성 대조.
- 시험 클래스 이름은 단위 `*Test`, 통합 `*IT`를 기본으로 한다. 백엔드 태그는 기능 이름(`practice-legacy` · `web-push` · `skeleton` 등), DB 시험은 `db`를 쓴다. 프런트·tools 시험은 소스 구조 폴더(`tests/parent/features/…` · `tests/shared/…`)에 둔다.
- 관문의 한계: PR `ci-gate`는 changes · backend · backend-deps · frontend · frontend-deps · tools · secrets · infra-validate를 집계한다. 정적·단위·통합·적용 범위 커버리지·보안·인프라는 경로 조건에 따라 실행된다. 이미지는 관문 통과 뒤 main에서 실행되며, 끝-끝 `skeleton`은 main 전용 임시 비차단이다. 이번 작업은 Build and Test의 전체 `verify.sh`로 병합 전 끝-끝을 확인한다.
- 시험 · CI · 검증에서는 가짜 공급자만 쓴다(ADR-002).
- 결과 보고는 테스트 대역 · 합성 대화 · 실제 API · 실제 기기 성공을 따로 적고, 대역 성공을 실공급자 성공으로 표시하지 않는다(`discovered-rules.md` R-M11, Q10-A).
- 이번 작업 기록의 시험 전략은 Standard다(컴포넌트당 5 ~ 8개 + 핵심 경계 통합 시험).

## Deployment
- 우리는 main 병합 뒤 ci-gate와 이미지 빌드·검사가 성공하면 demo에 자동 배포하는 팀 방식을 유지한다. **이번 작업에서는 배포를 실행하지 않는다.**
- 현재 저장소 구성은 GitHub Actions → OIDC 역할 → ECR 커밋 SHA 이미지 6종 → ECS(EC2·bridge) demo다. 태스크 정의 뼈대는 Terraform이 관리한다고 deploy.yml에 명시되어 있으며, 배포 워크플로는 최신 태스크 정의의 이미지를 교체한다. 기존 GHCR·SSM·호스트 Compose 설명은 이 현재 구성 설명으로 갱신한다.
- 이미지는 main 관문 통과 뒤 빌드·검사·게시하며 기존 SHA 태그를 덮어쓰지 않는다. PR에서는 images·deploy가 실행되지 않는다. ECR에 게시된 SHA 묶음으로 배포한다.
- demo를 대상으로 하며 이번 작업에서 staging·production을 새로 만들지 않는다. 파일만으로 실제 원격 환경·정책·가동 상태를 인증하지 않는다.
- SERVICE_HOST 없음, 자동 실행의 AUTO_DEPLOY=false, ECS 인스턴스 미연결이면 배포를 건너뛴다. 현재 저장소 변수 값과 실제 활성 여부는 조회하지 않았다. 수동 실행은 자동 배포 동결과 구분된다.
- DEPLOY는 migrate 종료 코드 0 확인 뒤 core-api·realtime → web → nginx 순서다. ROLLBACK은 지정 SHA 이미지로 돌아가며 migrate를 생략하고 DB·설정을 복원하지 않는다. 자동 복구는 서비스별 ECS 회로 차단기 수준이며 전체 배포를 원자적으로 되돌리지 않는다.
- 파일 주석상 ECR은 최근 5개를 보관하므로 롤백 SHA는 보존 범위에 제한된다. 실제 원격 보존 정책은 조회하지 않았다.
- 사후 확인은 /healthz·/parent의 200 및 /api/health의 200 + status=UP이다. 현재 WSS·인증서 검사는 없으며, HTTP 성공을 음성 대화 성공으로 보고하지 않는다.
- 로컬은 dev(infra/compose.dev.yml), 별도 일회성 검증 스택(compose.demo.yml + compose.ci.yml, 127.0.0.1:8443), host 모드로 구분한다. mujoong-manual과 볼륨·실행 설정은 변경·재시작·삭제하지 않는다.
- 새 비밀이 생기면 R-M9의 비밀 이름 검사 목록을 함께 갱신한다. OIDC·최소 권한·불변 태그 등 기존 강제 제약을 유지한다.
- 예약 의존성·비밀 스캔은 기존 승인 관례로 유지하되 이번에는 예약 파일·실제 실행 상태를 재검증하지 않았다.
- 원격 필수 리뷰 수·demo 환경 제한·현재 배포 동시성 설정은 이 조사에서 인증하지 않았다.

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
  - 프런트 화면 파일은 번호 접미 없이 `<기능>Screen.tsx`로 이름 짓고 `parent/features/<기능>/` · `device/features/<기능>/`에 둔다. 화면 번호는 `docs/screen-map.md`에서 찾는다(2026-10-19 승인 학습).
  - 이름은 언어 관례(TS camelCase, Java camelCase/PascalCase, SQL snake_case)를 따른다. 주석 · 문서 · 커밋 본문은 한국어, 식별자는 영어.
  - 코드 주석은 무엇을 왜 하는지 쓰고, 결정 ID는 필요할 때 한 줄 문서 링크로 가리킨다(2026-10-19 승인 학습). TODO·FIXME 대신 남은 일을 문서로 관리한다.
- 오류 처리 · 로그 · 시각(기준선 관례):
  - 오류 코드는 `backend/contract-model/.../error/ErrorCode.java` enum과 `contracts/errors.md` 표 한 곳에서만 정하고, `ContractFilesTest`가 대조한다. 받는 쪽은 모르는 code를 일반 오류로 처리한다.
  - 업무 거절은 `CoreApiException.of(ErrorCode)`로 던지고, 앱마다 하나인 `ApiExceptionHandler`가 봉투 `{code, message, traceId}`로 바꾼다. message는 code별 고정 문장이며 입력값 · 전사 · 예외 메시지 · 스택을 담지 않는다. 보호자에게는 `ErrorCode#forGuardian()`으로 번역해 내보낸다.
  - 화면 문구는 code로 고른다(`frontend/src/shared/errors/codeToCopy.ts` + `shared/copy/*`).
  - 운영 코드의 오류 기록은 `EventLog`(`events.appError(EventLog.Component.…, e)`)로 하고, 운영 앱 코드에서 slf4j `LoggerFactory`를 직접 쓰지 않는다.
  - 넓은 catch는 ① `events.appError` 후 계속(근거 주석 동반) ② 고정 code 결과로 번역 ③ 의미 있는 예외로 다시 던짐 중 하나로 쓰고, 주석 없이 삼키지 않는다.
  - 시각은 `kr.mujoong.contract.time.MujoongClock` 주입으로만 읽고, 시간 값은 `mujoong.timing.*` 설정 키에서만 읽는다.
- 새 저장소 코드의 JDBC/JPA 선택을 팀 전체 관례로 확정하지 않았다. 이번에 해당 변경이 필요할 때 설계에서 판단한다.

