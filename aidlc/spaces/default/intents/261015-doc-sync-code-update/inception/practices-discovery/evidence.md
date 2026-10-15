# Practices Discovery 근거

> 작업 기록 `261015-doc-sync-code-update` · 범위 `classic` · 깊이 Standard · 브라운필드.
> 기준 커밋: HEAD = `origin/main` = `72d70344495b41768feb34208c1aaca88d65296d`(브랜치 `feat/doc-sync-code-update`). 로컬 `main`은 `3748083`에 멈춰 있어 이력은 `origin/main`으로 봤다.
> 참여: 리드 `aidlc-pipeline-deploy-agent`, 지원 `aidlc-quality-agent` · `aidlc-developer-agent` · `aidlc-devsecops-agent`(`contributions/`), 사람 인터뷰(`practices-discovery-questions.md`).

## 1. 참여자별로 살펴본 것

### 리드 (aidlc-pipeline-deploy-agent)

| 대상 | 경로 · 명령 | 쓴 곳 |
|---|---|---|
| 역공학 산출물 | `aidlc/spaces/default/codekb/mujungi/` 의 `code-structure.md` · `technology-stack.md` · `dependencies.md` · `code-quality-assessment.md` · `architecture.md` · `business-overview.md` | 모듈 구성, 시험 도구 판, 변경 연쇄, TD-1 ~ TD-10, demo 단일 EC2, 앱 분리 결정 → 다섯 절 전체 |
| git 이력 | `git log --first-parent origin/main`, `git log --merges origin/main`, `git branch -a` | 병합 커밋 19개 · squash 0건, PR 브랜치 수명 0 ~ 1일, 브랜치 접두어, 커밋 메시지 형식(156개), 작성자 분포 |
| CI · 배포 | `.github/workflows/ci.yml`(549줄) · `deploy.yml`(243줄), `infra/RUNBOOK.md`, `infra/compose.*.yml`, `infra/migration-approvals.txt`, `docs/units/README.md` | `ci-gate` 집계, 경로 필터, `skeleton`은 main · 수동만, OIDC → SSM 배포, 수동 ROLLBACK, `SERVICE_HOST` · `AUTO_DEPLOY` 미설정 |
| 검증 · 시험 | `scripts/verify.sh`, 앞선 Code Generation 계획 4건(U1 · U4b · U5b · U6), `mujoong.java-conventions.gradle.kts`, `frontend/vitest.config.ts` | test-after 실행 사실, 줄 80% 하한, 검증 명령 C안 |
| 서식 · 린트 | `.editorconfig`, `.prettierrc.json`, `.prettierignore`, `frontend/eslint.config.mjs`, `.gitleaks.toml`, `package.json` 들 | Prettier 설정만 있고 실행 없음, Java 포매터 없음 |
| 이 PC 도구 | `java -version`, `node -v`, `docker -v` | `java` 없음, Node v22.23.3, Docker 29.8.1 |
| 같은 날 다른 기록 | `aidlc/spaces/default/intents/261015-local-run-readiness/inception/practices-discovery/` | 질문이 겹침 → Q9 |
| 사람이 더한 참고 문서 | `aidlc/spaces/default/knowledge/documents/mujungi_Luna_JEV_guardrail_implementation_handoff_v2_2026-10-15.md` 9절 「변경 관리·보안·운영 제한」, `scripts/verify/lib.sh`(`VERIFY_PROJECT=mujoong-verify`) | 기존 규칙에 없는 다섯 항목 → Q10 → R-M11 · R-F12 ~ R-F15 |

### 품질 담당 (aidlc-quality-agent)

- 살펴본 것: `ci.yml`(`changes` · `u5b-scope` · `frontend` · `skeleton`), `scripts/verify.sh`(`find_java21`), `mujoong.java-conventions.gradle.kts`, `backend/test-support/build.gradle.kts`, `frontend/vitest.config.ts`, `tools/vitest.config.mjs`, `backend/coverage-scope/` · `frontend/coverage-scope/`, 계약 대조 시험들.
- 사실 보정(반영함): 유닛 범위 커버리지를 CI가 막는 것은 U5b · U7뿐 / `test-support` 제외, `tools/` 하한 없음, 줄 지표만 / 빈 범위 측정은 실패(J137) / `skeleton`은 PR에서 돌지 않음 / 경로 필터로 문서만 바뀐 PR은 전 작업 건너뜀 / `verify.sh`는 모드와 관계없이 JDK 21 필요(없으면 종료 코드 2), 내부 기한 5400초, `--engine` 110초.
- 위험 지적: 문서만 바뀐 PR이 초록으로 보이는 착시, 바뀐 코드의 커버리지를 재는 수단 없음, 끝-끝 회귀가 병합 뒤에야 돎, 정본과 다른 선택 시 앞 유닛 시험 변경.
- 규칙 제안: R-F2 · R-M5에 대조 시험 문구 추가(반영), 새 규칙 "설계 문서 → `contracts/` 동시 수정"(→ R-M10).
- 입장: AGREE test-after 기록 · 80% 하한 사실 · R-F1 · R-F2 · R-M5 · 시험 종류 / OBJECT 유닛 범위 CI 과장 · "JDK 없이 compose" 선택지 · 끝-끝 회귀 시점 누락 — 셋 다 반영했다.

### 개발 담당 (aidlc-developer-agent)

- 살펴본 것: `.prettierignore`, `backend/**/*.java` · `frontend/src/**` 줄 길이 분포(`awk length>N`), `SessionResultHandler.java` 압축 서식, `backend/*/src/test/java/kr/mujoong/*/arch/` ArchUnit 시험, `TimeLiteralScanTest`, `ErrorCode.java` · `contracts/errors.md` · `ContractFilesTest`, `ApiExceptionHandler`, `EventLog` 사용처(75곳), 운영 코드 넓은 catch(103곳), 패키지 · 이름 접미어, 시험 이름 접미어, 프런트 화면 파일 이름.
- 측정: Java 115,761줄 중 100자 초과 10,186 · 150자 초과 572(그중 83%가 `core/result` 333 · `core/report` 144), TS 150자 초과 41, TSX 70.
- 정정(반영함): U6 Java 압축 서식은 `.prettierignore`가 `backend/`를 빼므로 Prettier와 무관하고 Java 포매터 · 줄 길이 검사 부재가 원인 / `-Xlint:all,-processing,-serial,-this-escape,-try`, `-Werror` 없음.
- 보강(반영함): ArchUnit이 강제하는 경계 목록, 오류 처리 · 로그 · 시각 주입 관례, 기능별 패키지 · 이름 접미어 · 시험 접미어 · 화면 번호 파일 이름.
- 규칙 제안: 시스템 시각 직접 읽기 금지 · 숫자 시간 값 금지 · 오류 코드 단일 출처 · 운영 → 시험 코드 참조 금지(→ R-F6 · R-F7 · R-M6 · R-F8), R-F5에 "상대 앱 DB 스키마 · 표" 추가(반영).
- 입장: AGREE Code Style 설정 기술 · R-F4 · R-F5 · R-M5 / OBJECT Prettier 인과 · 포매터 질문을 둘로만 둔 것 · 오류 처리 관례 누락 — 셋 다 반영했다(포매터 질문은 Q7 네 선택지로 나눔).

### 보안 담당 (aidlc-devsecops-agent, 블라인드 검토)

- 살펴본 것: CI `secrets` 3단계(추적 금지 파일 · gitleaks 양성 대조 · 전체 이력), `.gitleaks.toml`, 비밀 출구별 검사(번들 · 이미지 · Compose · 로그 · 검증 A4 · P3), 시험용 비밀 생성(`TestSecrets` · `gen-env.sh` · `gen-selfsigned.sh`), `infra/aws/iam-*.json`, `backend-deps` · `frontend-deps` · `images` Trivy, `ci/security-exceptions.yml` · `ci/security-gate.py`, Dockerfile 베이스 이미지 고정(J350) · `ADD --checksum` · 비루트 실행, GHCR 태그 불변, 워크플로 권한 · `persist-credentials: false`, Nginx TLS · 보안 헤더.
- 정정 · 보강(반영함): R-M4 범위를 런타임 베이스 이미지 태그 + 다이제스트 · `ADD --checksum`까지 확대 / 예외 `status`는 도구가 판정에 쓰지 않음 / `demo` 환경 배포 브랜치 제한이 배포 역할의 실제 경계 / 비밀 이름 목록 변경 연쇄(`MUJOONG_TEXT_AI_API_KEY`가 어느 목록에도 없음).
- 규칙 제안: 예외 승인 · OIDC 최소 권한 · 비밀 이름 목록 · gitleaks 허용 목록 · 태그 불변 · `run:` 입력 금지(→ R-M7 · R-M8 · R-M9 · R-F9 · R-F10 · R-F11).
- 입장: AGREE R-M4 · R-F3 · R-F1 · R-M1 / OBJECT `demo` 보호를 운영 질문으로만 둔 것(→ Q6 C 선택지로 물음) · 보안 정적 점검 부재 미기록(→ 7절) · 예외 `status` 미강제 미기록(→ 6절 F-1 · F-2).
- 범위 밖 기록: 운영 텍스트 AI 어댑터를 만들면 외부 HTTPS 송신(아동 발화 데이터 · 키 보관 · 응답 신뢰)에 대한 위협 모델 검토가 Construction NFR 단계에 필요하다.

## 2. 추론한 것 (인터뷰 전)

| 영역 | 추론 | 확신 | 근거 |
|---|---|---|---|
| 브랜치 전략 | 트렁크 기반에 가까운 GitHub Flow: `main` 하나, 짧은 PR 브랜치, 통합 브랜치는 일회성 | 높음 | git 이력 |
| 병합 방식 | merge commit(squash 아님). `org.md` 기본값과 다름 | 사실은 높음, 의도는 인터뷰로 확정 | 병합 커밋 · first-parent 이력 |
| 배포 | `main` 병합 = demo 자동 배포 설계, 변수 미설정으로 미배포, 환경 demo 하나, 수동 롤백 | 높음 | `ci.yml` · `deploy.yml` · `docs/units/README.md` |
| 시험 방식 | test-after(조직 기본값을 따른 실행 사실) | 실행은 높음, 의도는 인터뷰로 확정 | Code Generation 계획 4건 |
| 커버리지 | 모듈 줄 80% CI 강제, 유닛 범위는 U5b · U7만 CI 강제 | 높음 | build-logic · vitest.config · ci.yml |
| 스켈레톤 | U1 스켈레톤 + `scripts/verify.sh`가 회귀 기준, `classic`은 `skeleton: off` | 높음 | U1 기록 · `.claude/scopes/aidlc-classic.md` |
| 코드 스타일 | editorconfig + ESLint + `-Xlint` + ArchUnit + shellcheck, 포매터 강제 없음 | 높음 | 설정 파일 · CI |

## 3. 인터뷰 결정

| 질문 | 답 | 반영 |
|---|---|---|
| Q1 병합 방식 | A. merge commit 유지 | Way of Working. 조직 기본값(squash)과 다른 팀 선택. Construction 작업 트리 `--strategy merge` |
| Q2 Walking Skeleton | A. 새로 만들지 않고 기존 끝-끝 검증을 회귀 기준으로 | Walking Skeleton |
| Q3 시험 순서 | A. 구현 뒤 시험 | Testing Posture `Methodology: test-after`, 층별 Ordering |
| Q4 검증 명령 | X. 필요 시 JDK 21 설치 | Walking Skeleton 검증 항목 |
| Q4-1 검증 범위 | A. 단계마다 바꾼 쪽 정적 · 단위 · 통합, 전체 `verify.sh`는 Build and Test에서 한 번 | Walking Skeleton 검증 항목 |
| Q5 배포 | A. `main` 병합 시 demo 자동 배포, 이번 작업은 배포를 다루지 않음 | Deployment |
| Q6 GitHub 설정 | A만: `main` 브랜치 보호 + `ci-gate` 필수 검사. B(리뷰 승인) · C(`demo` 브랜치 제한) 미선택 | Way of Working · Deployment, B · C는 8절 미확인 |
| Q7 코드 서식 | A. 손대는 파일만 정리 | Code Style |
| Q8 영구 규칙 | X. 장단점을 보고 직접 결정 → Q8-1 | — |
| Q8-1 영구 규칙 | A, B, C, D 모두 확정(C의 단점을 보고도 확정) | `discovered-rules.md` R-M1 ~ R-M10 · R-F1 ~ R-F11, 후속 조치 6절 |
| Q9 다른 기록과의 겹침 | A. 이번 답을 팀 기준으로, `261015-local-run-readiness`는 나중에 이 내용으로 다시 확인 | 머리말 |
| Q10 인계서 9절 운영 제한 | A. 다섯 가지 모두 영구 규칙에 더한다 | `discovered-rules.md` R-M11 · R-F12 ~ R-F15, Testing Posture 증거 구분 한 줄 |

## 4. 결정에 따른 조직 기본값과의 차이

- **병합 방식**: `org.md`는 squash-merge(Bolt 하나 = `main` 커밋 하나)를 기본으로 둔다. 팀은 실제 이력(19건 모두 merge commit)대로 merge commit을 확정했다. 감사 기록과 `main` 이력이 1:1로 대응하지 않는 대신 작업 커밋이 `main`에 그대로 남는다.
- **배포 환경**: `org.md`의 "staging 자동 · production 수동 승인" 대신 유일한 원격 환경 demo에 자동 배포한다. production 환경이 생기면 그때 수동 승인 관문을 다시 정해야 한다.

## 5. 규칙별 근거와 번호 대응

| 새 번호 | 묶음 | 담당 기여의 옛 번호 | 근거 | 강제 수단 |
|---|---|---|---|---|
| R-M1 | A | 리드 R-M1 | `ci.yml` 머리 주석(CD-1), `docs/units/README.md`, Q6-A(브랜치 보호 · 필수 검사) | 브랜치 보호 + `ci-gate`. 한계: 경로 필터로 문서만 바뀐 PR은 아무 작업도 보지 않음 |
| R-M2 | A | 리드 R-M2 | `docs/units/README.md` 「병합 순서」, Flyway `outOfOrder` 꺼짐(TD-7) | Flyway 기동 실패 |
| R-M3 | A | 리드 R-M3 | `infra/migration-approvals.txt`, `infra-validate` 마이그레이션 expand 검사 | `infra-validate` |
| R-M4 | A(보안 담당 확대 반영) | 리드 R-M4 + 보안 R-F7 일부 | 모든 `uses:` SHA, `GITLEAKS_IMAGE` · `TRIVY_IMAGE`, Dockerfile J350, `ADD --checksum` | 리뷰 + 이미지 점검 ②(`latest` · 태그 없는 `FROM` 차단). fake-provider 예외는 F-6 |
| R-M5 | A(품질 · 개발 보강 반영) | 리드 R-M5 | TD-6, `dependencies.md` 「변경 연쇄」 | `ConfigKeyContractTest` · `TimeLiteralScanTest` · `timing.test.ts` |
| R-M6 | B | 개발 R-M6 | `ContractFilesTest`, BR8.6 · BR8.7 · SD-11 | `ContractFilesTest` · `ErrorCodeTest` |
| R-M7 | C | 보안 R-M6 | `ci/security-exceptions.yml` 머리 주석, `security-gate.py` | 만료일만 도구가 강제, `status`는 미강제(F-1 · F-2) |
| R-M8 | C | 보안 R-M7 | `deploy.yml` · `ci.yml` 머리 주석, `infra/aws/iam-gha-deploy*.json` | IAM 신뢰 정책(`environment:demo`). 작업별 최소 권한은 리뷰만(F-4) |
| R-M9 | C | 보안 R-M8 | 비밀 이름이 하드코딩된 목록들, `MUJOONG_TEXT_AI_API_KEY` 누락 | 목록 사이 일치 검사 없음(F-3) |
| R-M10 | D | 품질 R-M6 | `ci.yml` `changes`(`contracts/` 변경 → 전 작업), 계약 대조 시험 목록 | 리뷰 + 대조 시험(문서 쪽 변경은 도구가 감지하지 못함) |
| R-F1 | A | 리드 R-F1 | `ci.yml` 머리 주석(ADR-002), `scripts/verify.sh`, `README.md` | Actions 비밀 0개, 가짜 공급자 |
| R-F2 | A(품질 보강 반영) | 리드 R-F2 | `org.md` Testing Posture, 앞선 유닛 보고 | JaCoCo · Vitest 하한, 리뷰 |
| R-F3 | A | 리드 R-F3 | `README.md` 「비밀 · 도메인 규칙」 | CI `secrets` · 번들 · 이미지 · 로그 검사, 검증 A4 · P3 |
| R-F4 | A | 리드 R-F4 | `frontend/eslint.config.mjs` | ESLint 두 규칙 |
| R-F5 | A(개발 보강 반영) | 리드 R-F5 | `architecture.md` 「Key Design Decisions」 | `CoreArchTest` ① · `RealtimeArchTest.realtimeNeverReferencesCoreCodeOrTheCoreSchema` |
| R-F6 | B | 개발 R-F6 | `SystemMujoongClock` 한 곳만 `Instant.now()` | `CoreArchTest` ⑤ · `RealtimeArchTest` ⑤ |
| R-F7 | B | 개발 R-F7 | 시간 값 설정 키 원칙 | `TimeLiteralScanTest` |
| R-F8 | B | 개발 R-F8 | 운영 bootJar 시험 클래스 0 | ArchUnit ③, `verifyBootJarHasNoTestClasses` |
| R-F9 | C | 보안 R-F6 | `.gitleaks.toml` 머리 주석 | 사람 리뷰만(F-5) |
| R-F10 | C | 보안 R-F7 | `images` 푸시 단계 태그 불변 검사, 이미지 점검 ② | 태그 덮어쓰기는 CI가 실패시킴. fake-provider 예외는 F-6 |
| R-F11 | C | 보안 R-F8 | `deploy.yml` 머리 주석, 현행 두 워크플로가 지킴 | 리뷰만(F-4) |
| R-M11 | Q10 | — | 인계서 9절 「테스트 더블, 합성 대화, 실제 API, 실제 기기 성공 여부를 각각 별도 증거로 보고」, 8절 합성 대화 보고서 취급 | 보고 리뷰만. R-F1(시험에서 실제 유료 공급자 금지)과 겹치지 않음: R-F1은 무엇을 부를지, R-M11은 결과를 어떻게 보고할지 |
| R-F12 | Q10 | — | 인계서 9절 「기존 실행 스택 금지」 · 「별도 Compose 프로젝트명·포트·볼륨」, `scripts/verify/lib.sh` `VERIFY_PROJECT=mujoong-verify` | `verify.sh`가 별도 프로젝트명을 씀. 수동 스택 보호는 작업자 준수만 |
| R-F13 | Q10 | — | 인계서 9절 「사용자에게 키 본문을 채팅으로 요청하거나 저장소에 기록하지 않는다」 | 작업자 준수만. 저장소 기록 금지 부분은 R-F3이 이미 다뤄 R-F13은 채팅 요청 금지 · 관리 경로만 더함 |
| R-F14 | Q10 | — | 인계서 9절 「로그에는 민감한 전사, 음성, 개인정보, API 요청 원문을 남기지 않는다」 | `log-hygiene-check`는 비밀 이름 위주라 전사 · 개인정보는 리뷰만. R-F3(비밀 값) · R-M6(오류 응답 message)과 대상이 다름 |
| R-F15 | Q10 | — | 인계서 9절 「실제 공급자 약관·개인정보 처리 및 아동 데이터 전달 허용 범위 확인 전에는 아동 데이터를 외부 모델에 임의로 보내지 않는다」 | 도구 강제 없음. 8절 6번(운영 AI 어댑터 위협 모델)과 이어짐 |

`project.md` `## Corrections`에 이미 있는 규칙(커밋 전 mode change 확인, `main`과 같은 커밋에서 작업 브랜치 따기, 산출물은 파일 쓰기 도구로 저장, 유닛 지시서 따르기 등)은 다시 적지 않았다.

## 6. 확정 규칙의 후속 조치 (해결되지 않음)

사람은 Q8-1에서 C 묶음의 단점(현재 예외가 승인 전 상태 · 일부 규칙의 도구 강제 부재)을 보고도 확정했다. 아래는 이 기록이 해결하지 않은 채 남기는 후속 조치다.

| ID | 관련 규칙 | 현재 상태 | 필요한 조치 |
|---|---|---|---|
| F-1 | R-M7 | 유일한 예외 `GHSA-vfj7-8cjw-p6xm`(braces, 개발 의존성만, `expires: 2026-11-30`)이 `status: candidate`다. 규칙 확정과 동시에 저장소가 R-M7과 어긋난다 | 사람이 리뷰해 `status: approved`로 바꾸거나 항목을 지운다(의존성 갱신으로 해소 포함) |
| F-2 | R-M7 | `ci/security-gate.py`는 `status`를 읽기만 하고 판정에 쓰지 않아 `candidate`도 만료일까지 관문을 통과한다 | `approved`가 아닌 항목을 실패로 판정하도록 도구를 고친다(이번 작업 범위 밖, 별도 작업) |
| F-3 | R-M9 | 비밀 이름 목록끼리 일치를 검사하는 도구가 없고, TD-1 예약 이름 `MUJOONG_TEXT_AI_API_KEY`가 어느 목록에도 없다 | 이번 작업에서 운영 텍스트 AI 어댑터를 만들면 같은 커밋에서 모든 목록에 넣는다. 목록 일치 검사 도입은 별도 작업 |
| F-4 | R-M8 · R-F11 | 작업별 최소 권한과 `run:` 입력 금지는 리뷰로만 지켜진다(actionlint · zizmor 같은 워크플로 정적 검사 없음) | 워크플로 정적 검사 도입 여부를 별도로 정한다 |
| F-5 | R-F9 · R-M7 | 허용 목록 · 예외 추가에 "사람 리뷰 승인"이 필요하다고 적혀 있지만, PR 리뷰 승인 요건이 브랜치 보호에 설정됐는지 확인되지 않았다(Q6 B 미선택) | GitHub 설정에서 필수 리뷰(또는 CODEOWNERS)를 확인 · 설정한다 |
| F-6 | R-M4 · R-F10 | `tools/fake-provider/Dockerfile`의 `FROM node:22.22.0-bookworm-slim`은 다이제스트가 없다(시험 전용, GHCR에 올리지 않음) | 다이제스트를 고정하거나, 시험 전용 이미지는 태그 고정까지라고 규칙 범위를 정한다 |

## 7. 알려진 빈틈 (팀 방식이 아님)

보안 담당이 통제가 없음을 확인한 것이다. 팀 방식으로 적지 않으며, 이번 작업에서 메우지 않는다. 이후 단계가 "점검된다"고 오해하지 않도록 기록한다.

| 영역 | 상태 | 근거 |
|---|---|---|
| SAST | 없음(CodeQL · Semgrep · SonarQube 미설정). ESLint `react/no-danger` · ArchUnit 경계 · shellcheck뿐 | `.github/` 워크플로 2개뿐 |
| DAST | 없음. 배포 뒤 3종 호출 · 인증서 확인은 가용성 점검 | `deploy.yml` |
| 의존성 자동 갱신 | Dependabot · Renovate 없음. 주간 스캔은 발견만 함 | `.github/dependabot.yml` 없음 |
| Gradle 의존성 무결성 | `gradle/verification-metadata.xml` · 의존성 잠금 없음(래퍼 sha256만). npm은 잠금 파일로 고정 | `backend/gradle/` |
| SBOM · 서명 · 증명 | SBOM · cosign 서명 · 빌드 증명 없음. 출처는 라벨(SHA · 소스 지문)과 태그 불변 검사뿐 | `images` 작업 |
| 커밋 전 비밀 차단 | pre-commit 훅 없음. 막는 곳은 CI | `scripts/verify/` |
| 주간 이미지 재스캔 | 예약 실행에서 `images`가 꺼져 새 베이스 이미지 · OS CVE를 이미지가 바뀔 때까지 못 잡음 | `ci.yml` `changes` `schedule` 분기 |
| 빌드 도구 의존성 스캔 | `backend-deps`는 운영 bootJar만 스캔. Gradle 플러그인 · 빌드 classpath는 대상 아님 | `backend-deps` |
| 바뀐 코드 커버리지 | 이번 작업은 유닛이 아니라 범위 파일이 없고 diff 커버리지 도구도 없어, 모듈 80%가 새 코드의 낮은 커버리지를 가릴 수 있다(품질 담당) | `ci.yml` · coverage-scope |

보안 담당 권고(확정 규칙 아님): Gradle 의존성을 새로 더하게 되면(예: AI 공급자 SDK) 판 고정 + `backend-deps` 통과 + 사람 리뷰를 최소 조건으로 둔다.

## 8. 남은 불확실성

1. **PR 리뷰 승인 요건**: 필수 리뷰어 수는 확인되지 않았다(Q6 B 미선택). F-5와 이어진다.
2. **`demo` 환경 배포 브랜치 제한**: `main`만 배포할 수 있게 제한했는지 확인되지 않았다(Q6 C 미선택). 제한이 없으면 쓰기 권한자가 아무 브랜치에서 `deploy.yml`을 수동 실행해 배포 역할을 얻고, 그 브랜치의 `infra/bin/probe-three`가 자격 증명이 있는 상태로 돈다(역할 권한이 좁아 피해는 제한적).
3. **새 시험의 자리 · 태그**: 유닛이 아닌 이번 작업의 새 시험을 고친 코드가 속한 유닛 폴더 · 태그에 둘지, 새 태그를 만들지 정하지 않았다(품질 담당 추천: 기존 유닛 폴더 · 태그). Code Generation 계획에서 정한다.
4. **바뀐 코드 커버리지 확인**: 이번 작업용 범위 파일을 만들어 로컬로 80%를 확인할지 묻지 않았다(품질 담당 추천: 범위 파일로 로컬 확인).
5. **저장소 코드 방식 · 화면 파일 이름**: JDBC를 새 저장소 코드의 관례로 둘지, 화면 번호가 바뀌거나 나뉠 때 파일 · 시험 이름을 같이 바꿀지 묻지 않았다(개발 담당).
6. **운영 AI 어댑터의 위협 모델**: 이번 작업이 TD-1 운영 텍스트 AI 어댑터를 만들면 외부 송신에 대한 검토가 Construction NFR 단계에 필요하다(보안 담당, 범위 밖 기록).
7. **다른 작업 기록**: `261015-local-run-readiness`의 practices-discovery는 답이 비어 있다. 그 기록이 나중에 다른 답으로 승격하면 `team.md` 다섯 절을 덮어쓰므로, 이어갈 때 이번 내용을 기본값으로 다시 확인해야 한다(Q9-A).

## 9. Requirements Analysis로 넘기는 입력

사람이 더한 Luna·JEV 인계서 개정 v2(`aidlc/spaces/default/knowledge/documents/mujungi_Luna_JEV_guardrail_implementation_handoff_v2_2026-10-15.md`)가 이 작업 기록 Requirements Analysis의 주 입력이다. 인계서는 JEV를 GPT-Live가 새로 생성한 아동 대상 발화에만 쓰고, Luna 출력(U5a 상황)은 Luna 별도 자체 검토 + 서버 독립 검사로 검증하며, 고정 판정 대신 유연한 연습대화 흐름, Neo4j 지식그래프 어댑터, P0 ~ P5 작업 우선순위를 정한다. 이 단계는 그중 9절 운영 제한만 팀 규칙으로 옮겼고, 요구사항은 여기서 쓰지 않는다.
