# 팀 작업 방식 (초안 — 리드 초안, 인터뷰 · 확인 전)

> 근거: git 이력(origin/main `72d7034`), `.github/workflows/ci.yml` · `deploy.yml`, `scripts/verify.sh`, `infra/RUNBOOK.md`,
> `docs/units/README.md` · `integration-2026-10-14.md`, 앞선 작업 기록의 Code Generation · Build and Test 산출물,
> 역공학 산출물(`code-structure.md` · `technology-stack.md` · `dependencies.md` · `code-quality-assessment.md` · `architecture.md` · `business-overview.md`).
> 항목별 근거와 확실하지 않은 점은 `evidence.md`에 있다. **[확인 필요]** 표시는 인터뷰에서 정할 항목이다.

## Way of Working

- 우리는 `main` 하나를 트렁크로 두고, 짧게 사는 작업 브랜치에서 GitHub PR로 `main`에 합친다. 브랜치 이름은 용도 접두어를 쓴다:
  `unit/<유닛>-<이름>`(유닛 구현), `docs/<주제>`(지시서 · 인계 문서), `codex/<주제>` · `claude/<주제>`(에이전트 작업 · 통합),
  `snapshot/<이름>-<날짜>`(인수용 고정 사본), `ops/<주제>`(운영 · 실행 준비).
- 유닛 브랜치는 보통 하루 ~ 이틀 안에 합쳐졌다. 여러 유닛을 한꺼번에 합칠 때는 통합 브랜치(`codex/all-units-integration`)에서 정해진 순서로
  합치고, 그 결과를 PR 하나로 `main`에 올린다.
- **[확인 필요] 병합 방식**: 실제 이력은 squash가 아니라 **merge commit**이다(`main` first-parent 이력이 `Merge pull request #N` 커밋이고,
  유닛 커밋 이력이 `main`에 그대로 남는다). org 기본값(squash-merge)과 다르므로 어느 쪽을 팀 규칙으로 삼을지 정해야 한다.
- 유닛 사이 병합 순서는 DB 마이그레이션 버전 순서를 따른다(Flyway `outOfOrder` 꺼짐: core V3 → V4 → V5 → V6 → V7, realtime V3 → V4).
  병합 순서와 겹치는 파일은 `docs/units/README.md` 「병합 순서」에 적고 지킨다.
- 커밋 메시지는 `feat(u7): …` · `fix(ci): …` · `docs(u6): …` · `test: …` · `chore(aidlc): …`처럼 종류와 유닛 범위를 앞에 두고 본문은 한국어로 쓴다.
- 유닛마다 담당자가 자기 PC에서 AI-DLC 작업 기록을 새로 만들고, 남의 작업 기록은 이어 가지 않는다. 지시서(`docs/units/<유닛>/brief.md`)가
  기록 이름 · 브랜치 이름 · 뺄 단계를 정한다.
- **[확인 필요]** PR 리뷰 요건(필수 리뷰어 수 · 브랜치 보호)은 저장소 파일로 확인되지 않는다. 필수 검사는 `ci-gate` 하나다.

## Walking Skeleton

- 우리는 끝까지 이어지는 가장 얇은 조각을 먼저 만들었다. U1 워킹 스켈레톤이 두 앱(core-api · realtime-activity) + DB + Nginx + 웹을
  실제로 띄우고 기기 시뮬레이터로 자유대화 한 턴을 끝까지 돈다.
- 그 증명 명령이 검증 명령 C안 `bash scripts/verify.sh`(A 정적 · 단위 · 통합 → B 기동 · 3종 호출 → C 기기 시뮬레이터 한 턴 → 사후 단언 P1 · P2 · P3 · T)이고,
  CI `skeleton` 작업도 같은 명령(`--mode compose`)을 돈다. 종료 코드 0과 `build/verify/report.txt`를 근거로 사람이 승인한다.
- 새 유닛을 붙일 때도 이 스켈레톤 경로가 계속 통과해야 한다(회귀 기준).
- **[확인 필요]** 이번 작업(로컬 현장 실행 준비)에서는 새 스켈레톤을 만들지 않고, 기존 C안을 Construction 검증 명령으로 쓸지 정해야 한다.
  이 PC에는 JDK 21이 없어 지금은 C안이 전제 누락(종료 코드 2)으로 끝난다(`code-quality-assessment.md` M1).

## Testing Posture

- **Methodology**: test-after
- **Ordering**: 시험할 수 있는 층을 하나 구현하면, 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 넘어간다.
- 근거: U1 · U4b · U6 Code Generation 계획이 모두 test-after를 따랐다. 다만 이는 org 기본값을 그대로 쓴 것이고 팀이 따로 정한 적은 없다 **[확인 필요]**.
- 커버리지 하한: 모듈 전체 줄 커버리지 80%(백엔드 JaCoCo `COVEREDRATIO 0.80`, 프런트 Vitest coverage 80%), 유닛별 범위 파일
  (`backend/coverage-scope/<유닛>.txt` · `frontend/coverage-scope/<유닛>.json`)로 유닛이 새로 만들거나 바꾼 코드도 80% 이상. CI에서 막는다.
- 하한 · 기존 단언은 시험을 통과시키려고 낮추거나 끄지 않는다. 모자라면 시험을 더하거나 차이를 보고한다.
- 시험 종류:
  - 단위: JUnit 5(백엔드), Vitest(프런트 · tools).
  - 통합: Spring 통합 시험 + Testcontainers PostgreSQL(`-Pmujoong.test.db=docker`), 상대 앱은 스텁.
  - 아키텍처: ArchUnit(계층 · 단일 출처), ESLint 영역 경계(`/parent` ↔ `/device`).
  - 끝-끝: 검증 명령 C안(Node 기기 시뮬레이터 + 가짜 공급자, 자유대화 시나리오만).
  - 인프라: `infra-validate`(Compose 불변식 · `nginx -t` · shellcheck · 마이그레이션 expand 검사), 이미지 점검, 관문 음성 대조.
- 실제 유료 AI 공급자는 시험 · CI · 검증에서 부르지 않는다(가짜 공급자). 실제 기기 음성 · 브라우저 · 푸시 수신은 자동 시험으로 대체하지 않고 따로 확인한다.
- 시험 전략 Standard: 컴포넌트당 5 ~ 8개 + 핵심 경계 통합 시험.
- **[확인 필요]** 두 앱을 함께 띄우는 끝-끝 시험이 자유대화 하나뿐이다. 결과 · 리포트(U6), 연습(U5a · U5b), 웹 푸시(U7) 경로를
  끝-끝 시나리오에 더할지, 수동 점검표로 둘지는 이번 작업에서 정한다(`code-quality-assessment.md` 「통합 시험 공백」 G1 ~ G6).

## Deployment

- 우리는 `main` 병합마다 CI `ci-gate`가 통과하면 **demo 환경**(AWS EC2 1대, `ap-northeast-2`, Docker Compose)에 자동 배포한다.
  경로는 하나다: GitHub Actions → OIDC 역할 → SSM 문서 `mujoong-deploy` → 호스트 `deploy.sh`. 이미지는 커밋 SHA 태그로 GHCR에 올린다.
- 별도 staging / production 구분은 없다. demo가 유일한 원격 환경이다. 자동 배포는 저장소 변수 `AUTO_DEPLOY=false`로 멈춘다(시연 · 측정 창 · 롤백 중 동결).
- 배포 뒤 공개 도메인으로 3종 호출(health · `/device` · WSS 101)과 인증서 만료를 확인한다. 실패는 배포 실패로 표시하고 **자동 롤백은 없다**.
  롤백은 `deploy.yml`을 `mode=ROLLBACK`, 마지막 성공 SHA로 수동 실행한다(RUNBOOK 6.4).
- 로컬 실행 구성은 셋이다: dev(`infra/compose.dev.yml`, PostgreSQL만 컨테이너 · 앱은 호스트), 검증 스택(`compose.demo.yml` + `compose.ci.yml`,
  일회성 · 시험 로그인 · 가짜 공급자 · `127.0.0.1:8443`), host 모드(Docker 없이). 검증 스택은 demo 호스트에서 돌리지 않는다.
- **[확인 필요]** "로컬 현장"이 어느 구성을 뜻하는지(현장 PC 한 대에서 상시 기동? 아이 기기를 LAN으로 붙이는가? 실제 AI 공급자 키를 쓰는가?)는
  증거로 정할 수 없다. 지금 구성은 루프백 전용이고 상시 기동 절차가 없다(`code-quality-assessment.md` M7 · M11).
- **[확인 필요]** GitHub 환경 `demo`에 수동 승인 보호 규칙이 걸려 있는지는 저장소 밖 설정이라 확인하지 못했다.

## Code Style

- 공통: `.editorconfig`(UTF-8 · LF · 끝 줄바꿈 · 들여쓰기 2칸, Java · Kotlin · SQL 4칸).
- 프런트(TypeScript · Next.js): ESLint flat config(`eslint-config-next` core-web-vitals + typescript)에 영역 경계 · `react/no-danger` ·
  화면 문구 copy 모듈 · `getUserMedia` 한 곳 규칙을 더했다. `tsconfig` `strict: true`. CI가 `eslint .` 실패로 막는다.
- 백엔드(Java 21 · Spring Boot · Gradle): 공통 규칙은 `build-logic`의 `mujoong.java-conventions`(`-Xlint:all`), 경계는 ArchUnit 시험으로 강제.
- 셸: `infra-validate`가 shellcheck를 돈다.
- **[확인 필요]** 포매터가 없다(Prettier · Spotless · google-java-format 설정 없음). 포매터를 도입할지, editorconfig + 린터만으로 갈지 정한다.
- 이름은 언어 관례(TS camelCase, Java camelCase/PascalCase, SQL snake_case). 주석 · 문서 · 커밋 본문은 한국어, 식별자는 영어.
