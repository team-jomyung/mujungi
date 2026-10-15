# 근거 (리드 초안 — aidlc-pipeline-deploy-agent)

> 기준: 브랜치 `ops/local-run-readiness`, origin/main `72d7034`(2026-10-15 확인). 소스 코드는 고치지 않았고 읽기만 했다.
> 지원 에이전트 검토 · 인터뷰 결정은 아직 없다(Step 3 · 4 뒤에 통합 단계에서 채운다).

## 살펴본 것

| 출처 | 경로 · 커밋 |
|---|---|
| git 이력 | `git log origin/main`(커밋 173개, 병합 커밋 19개, 2026-10-08 ~ 10-14), `--first-parent`, 원격 브랜치 목록 |
| CI · 배포 | `.github/workflows/ci.yml`, `.github/workflows/deploy.yml` |
| 검증 명령 | `scripts/verify.sh`, `scripts/verify/*` |
| 운영 문서 | `infra/RUNBOOK.md`(1 · 2 · 6 · 17절), `infra/compose.dev.yml` · `compose.demo.yml` · `compose.ci.yml` 머리 주석 |
| 팀 문서 | `README.md`, `docs/units/README.md`, `docs/units/integration-2026-10-14.md` |
| 코드 스타일 설정 | `.editorconfig`, `frontend/eslint.config.mjs`, `frontend/tsconfig.json`, `frontend/package.json`, `backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts`, ArchUnit 시험(`backend/core-api/src/test/java/kr/mujoong/core/arch/CoreArchTest.java` 등) |
| 커버리지 범위 | `backend/coverage-scope/u1.txt` ~ `u7.txt`, `frontend/coverage-scope/u1.json` ~ `u7.json` |
| 앞선 작업 기록 | `aidlc/spaces/default/intents/261008-mujoong-service/construction/u1-walking-skeleton/code-generation/code-generation-plan.md` · `unit-test-instructions.md`, `.../261008-mujoong-service/construction/build-and-test/build-and-test-summary.md`, `.../261014-u6-result-report/construction/code-generation/code-generation-plan.md`, `.../261014-u6-result-report/construction/build-and-test/build-and-test-summary.md`, `.../261008-mujoong-service/inception/delivery-planning/bolt-plan.md` |
| 역공학 산출물 | `aidlc/spaces/default/codekb/mujungi/` 아래 `code-structure.md` · `technology-stack.md` · `dependencies.md` · `code-quality-assessment.md` · `architecture.md` · `business-overview.md` |

## 역공학 산출물을 어떻게 썼나

- code-structure: 실행 단위 디렉터리(`backend/` · `frontend/` · `tools/` · `infra/` · `contracts/`)와 유닛 구분 방식을 Way of Working · Code Style 근거로 썼다.
- technology-stack: Java 21 · Spring Boot · Gradle, Next.js · React · TypeScript, Vitest · JUnit · Testcontainers · ArchUnit을 Testing Posture · Code Style 근거로 썼다. 「이 PC에서 확인된 도구」(JDK 21 없음)는 Walking Skeleton의 확인 필요 항목 근거다.
- dependencies: 「외부 의존 분류 (로컬 현장 기준)」 · 「환경 변수 · 비밀」을 Deployment(로컬 구성 · 가짜 공급자) 근거로 썼다.
- code-quality-assessment: 「시험 체계」 · 「통합 시험 공백」(G1 ~ G6) · 「실행 문서 · 구성 불일치」(M1 ~ M12)를 Testing Posture · Deployment의 확인 필요 항목 근거로 썼다.
- architecture: 「배포 · 실행 뷰」의 실행 경로 세 가지와 Interaction Diagrams IX-1 ~ IX-4(끝-끝 단언이 있는 경로는 IX-1뿐)를 썼다.
- business-overview: 「로컬 현장 실행 관점에서 실제로 끝까지 도는 업무」를 Walking Skeleton · Testing Posture의 범위 판단에 썼다.

## 섹션별 추론

### Way of Working

- **트렁크 + 짧은 브랜치**: `main` first-parent 이력이 PR 병합(#2 ~ #14)과 초기(10/08 ~ 10/12) 직접 커밋으로 이뤄진다. 원격 브랜치 접두어 `unit/` · `docs/` · `codex/` · `claude/` · `snapshot/`. 유닛 브랜치는 대개 하루 ~ 이틀(U4b 10/13 → PR #8 10/14).
- **병합 방식 = merge commit(squash 아님)**: `72d7034 Merge pull request #14`, `a09df59 Merge pull request #13`, `7a22b67 Merge pull request #8` 등. 브랜치 사이 동기화도 merge(`bdf47ec Merge remote-tracking branch 'origin/main' into unit/u5b-practice`, `2f916d9 merge: integrate U5b after U6 and U7`). org.md 기본값 squash와 어긋난다 → 인터뷰.
- **통합 브랜치 경유**: U6 → U7 → U5b를 `codex/all-units-integration`에서 차례로 합친 뒤 PR #13, 사후 보완은 `codex/integration-verification` PR #14(`docs/units/integration-2026-10-14.md`).
- **병합 순서 규칙**: `docs/units/README.md` 「병합 순서 (꼭 지킨다)」 — Flyway `outOfOrder` 꺼짐.
- **커밋 메시지**: `feat` 48 · `docs` 50 · `fix` 28 · `chore` 15 · `test` 3 · `aidlc` 4 · `wip` 3(접두어 집계). 유닛 범위 `(u7)` 등, 본문 한국어.
- **작성자**: 여러 사람과 에이전트(baik 82 · Claude 49 · SungMin Yang 32 등). 담당자별 작업 기록 운용은 `docs/units/README.md` 머리말.
- 브랜치 보호 · 필수 리뷰어 수는 저장소 파일에 없다(GitHub 설정). 리뷰 기록은 `docs/units/u4a/review-1.md` 등 문서로 남는다.

### Walking Skeleton

- U1 워킹 스켈레톤이 AI-DLC로 끝까지 만들어졌고(`docs/units/README.md`), 앞선 기록의 스코프가 `skeleton: on`이었다(`bolt-plan.md` 머리말 「스코프 `mujoong-mvp`(skeleton: on)」).
- 검증 명령 C안: `scripts/verify.sh` 머리 주석(A → F → B → C → P1 · P2 · P3 → T, 종료 코드 0/1/2/64), CI `skeleton` 작업 `bash scripts/verify.sh --mode compose`(`ci.yml` 465 ~ 512행). 사람이 `report.txt`로 승인(RUNBOOK 17.2).
- 이 PC는 JDK 21 없음 → 지금 C안은 종료 2(`code-quality-assessment.md` M1).

### Testing Posture

- **test-after**: U1 `code-generation-plan.md`(「방법론. Testing Contract의 test-after」), U6 `code-generation-plan.md` 60 · 118행, U6 `build-and-test-summary.md` 7행. 모두 org.md 기본값을 그대로 쓴 것(`team.md`가 비어 있음).
- **80% 하한**: `mujoong.java-conventions.gradle.kts` 182 ~ 183행(`COVEREDRATIO` `0.80`), `ci.yml` frontend 「Vitest + 줄 커버리지 80%」, 유닛 범위 단계(`u5b-scope` · U7 단계, `-PcoverageScope=<유닛>`).
- **하한을 낮추지 않음**: `integration-2026-10-14.md` 「기존 시험 단언과 커버리지 하한은 낮추지 않았다」, U6 계획 32행 「어떤 임계값도 낮추지 않는다」.
- **시험 종류**: `ci.yml` backend 단계 이름 「단위 · 통합 Testcontainers · ArchUnit · 설정 키 대조 · JaCoCo 80%」, tools 단계 「가짜 공급자 · 기기 시뮬레이터 · C안 시나리오」, infra-validate · images · 관문 음성 대조.
- **가짜 공급자**: `ci.yml` 머리 주석 「실제 유료 AI API는 부르지 않는다(가짜 공급자, ADR-002)」, `verify.sh` 머리 주석.
- **끝-끝 공백**: `code-quality-assessment.md` 「통합 시험 공백」 — C안 시나리오는 자유대화만, U6 결과 · U5 연습 · U7 푸시 · 실제 브라우저 기기는 끝-끝 단언 없음.

### Deployment

- `ci.yml` `deploy` 작업: `push` + `refs/heads/main` + `ci-gate` 성공일 때 `deploy.yml` 호출(automatic=true).
- `deploy.yml` 머리 주석: OIDC → SSM `mujoong-deploy` → `/opt/mujoong/bin/deploy.sh`, `AUTO_DEPLOY` 동결, 배포 뒤 S5b 3종 호출, 자동 롤백 없음, 수동 `ROLLBACK`. GitHub 환경 이름 `demo`(146 ~ 147행), 동시성 그룹 `mujoong-deploy-demo`.
- RUNBOOK 2.1: EC2 `mujoong-demo` 1대, Compose 상시 컨테이너 7개. 17절: 검증 스택은 개발 PC · CI 전용, demo에서 돌리지 않음.
- 로컬 구성: `compose.dev.yml`(PostgreSQL만 · 앱 호스트 기동), `compose.ci.yml`(검증 스택 덧붙임, 127.0.0.1:8443).
- staging / production 분리 없음 — org.md 기본값(staging 자동 · production 수동 승인)과 다르다.

### Code Style

- `.editorconfig` 전문, `eslint.config.mjs` 머리 주석(영역 경계 · no-danger · jsx-no-literals · getUserMedia · serviceWorker), `tsconfig.json` `strict: true`, `package.json` 스크립트 `lint` · `typecheck`.
- 백엔드: `-Xlint:all,...`(59행). `-Werror` · Spotless · Checkstyle 설정 없음. Prettier도 없음.
- shellcheck: `ci.yml` infra-validate 단계 이름.

## discovered-rules 근거

| 후보 | 근거 |
|---|---|
| `ci-gate` 통과 후 병합 | `ci.yml` 머리 주석 「필수 검사는 항상 도는 집계 작업 `ci-gate` 하나다(CD-1)」 |
| 마이그레이션 순서 · 기존 파일 불변 | `docs/units/README.md` 「병합 순서 (꼭 지킨다)」, `integration-2026-10-14.md` 「기존 마이그레이션 내용은 변경하지 않았다」 |
| 제약 강화 승인 목록 | `docs/units/README.md` 「CI (10/13 정리)」 1번, `infra/migration-approvals.txt` |
| 액션 SHA · 이미지 다이제스트 고정 | `ci.yml` 머리 주석, `env.GITLEAKS_IMAGE` · `TRIVY_IMAGE` |
| 시험 비밀 실행 때 생성 · 저장 안 함 | `ci.yml` 머리 주석(J127), `compose.dev.yml` 머리 주석, `secrets` 작업 |
| 실제 유료 AI 금지 | `ci.yml` · `verify.sh` 머리 주석 |
| 하한 · 단언 낮추지 않음 | org.md Testing Posture, `integration-2026-10-14.md`, U6 계획 |
| 검증 스택을 demo에서 금지 | `infra/RUNBOOK.md` 17절 머리 |
| `/parent` ↔ `/device` import 금지 | `frontend/eslint.config.mjs`(NFR11.1 · PD-15), CI `eslint .` |

## 확실하지 않은 점

1. 병합 방식이 org.md(squash)와 다르다. 실제는 merge commit. 이것이 팀의 의도인지, 유닛 이력 보존 때문에 임시로 그런 것인지 모른다.
2. org.md 배포 기본값(staging 자동 + production 수동 승인)과 달리 demo 하나뿐이다. 이번 작업의 "로컬 현장"이 네 번째 실행 구성인지 알 수 없다.
3. test-after는 기본값 상속일 뿐 팀이 고른 적이 없다.
4. 포매터가 없다. 의도된 결정인지 빠진 것인지 모른다.
5. GitHub 브랜치 보호 · 환경 보호 규칙은 저장소 밖 설정이라 보지 못했다.

## 인터뷰에서 물어야 할 것

1. **병합 방식**: 지금처럼 merge commit으로 유닛 이력을 `main`에 남길까, org 기본값대로 squash로 갈까?
2. **이번 작업의 브랜치 · 병합**: `ops/local-run-readiness`를 PR 하나로 `main`에 합치면 될까? 리뷰어 · 필수 승인 수는?
3. **로컬 현장의 모습**: 현장 PC 한 대에서 상시로 띄우는가, 시연 때만 띄우는가? 아이 기기(태블릿)를 LAN으로 붙이는가? 실제 AI 공급자 키를 쓰는가, 가짜 공급자로 시연하는가? 웹 푸시(VAPID)를 켜는가?
4. **로컬 현장을 "배포 환경"으로 볼까**: demo(EC2)와 별개로 로컬 현장 구성을 두고 거기에도 롤백 · 점검 절차를 둘까?
5. **얇은 끝-끝 조각부터 확인할까**: 끝-끝 조각(walking skeleton, 처음부터 끝까지 한 번 도는 가장 작은 실행 경로)으로 기존 `bash scripts/verify.sh`를 이번 작업의 검증 명령으로 쓸까? 이 PC에 JDK 21을 설치해도 될까?
6. **시험 방식**: 계속 test-after로 갈까(층을 구현한 뒤 그 층 시험), 아니면 이번에 더할 통합 시험은 시나리오를 먼저 쓰는 방식으로 할까?
7. **통합 시험 범위**: 끝-끝 시나리오에 결과 · 리포트(U6), 연습(U5a · U5b), 웹 푸시(U7)를 더할까, 수동 점검표로 둘까? 실제 브라우저 기기 시험 도구를 들일까?
8. **포매터**: Prettier · Spotless 같은 포매터를 도입할까, editorconfig + 린터로 충분한가?
9. **discovered-rules 후보 9개**: 각각 팀의 강한 규칙으로 확정할지.
