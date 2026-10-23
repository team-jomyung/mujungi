# Discovered Rules

> 인터뷰 통합본. 기존 사람이 승인한 R-M1~R-M11, R-F1~R-F15를 승계한다. Q1은 기존 방식 유지와 설명 갱신이며 새 강제 규칙을 추가하지 않았다. 원문의 금지문 문형은 기존 기록 그대로 보존했다. 관례 확정 게이트 승인·memory 승격 전이다.

## Mandated

- ALWAYS PR은 필수 검사 `ci-gate`가 통과한 뒤에만 `main`에 합친다. — R-M1 (affirmed 2026-10-15)

- ALWAYS Flyway 마이그레이션은 버전 순서대로 `main`에 들어가게 병합 순서를 정하고, 이미 `main`에 있는 마이그레이션 파일 내용은 바꾸지 않는다. — R-M2 (affirmed 2026-10-15)

- ALWAYS 기존 표에 제약을 더하는 마이그레이션 문장은 이전 앱이 깨지지 않는 근거를 `infra/migration-approvals.txt`에 한 줄로 적고 리뷰를 받는다. — R-M3 (affirmed 2026-10-15)

- ALWAYS GitHub Actions 액션은 40자 커밋 SHA로, CI 도구 컨테이너 이미지(gitleaks · trivy)는 다이제스트로, 운영(런타임) 베이스 이미지는 태그와 다이제스트를 함께 고정하고, 이미지 빌드 중 원격 파일은 `ADD --checksum=sha256:…`으로만 받는다. — R-M4 (affirmed 2026-10-15)

- ALWAYS 시간 값을 바꿀 때는 두 앱 `application.yml`의 `mujoong.timing.*`, `contracts/timing.md`, `frontend/src/shared/timing/timing-defaults.json` 세 곳을 같은 커밋에서 함께 고치고, 대조 시험(`ConfigKeyContractTest` · `TimeLiteralScanTest` · `timing.test.ts` 등)이 실패하면 값을 맞춘다. — R-M5 (affirmed 2026-10-15)

- ALWAYS 새 오류 코드는 `ErrorCode` enum과 `contracts/errors.md` 표에 같은 커밋에서 더하고, 오류 응답 message에는 입력값 · 예외 메시지 · 개인정보를 담지 않는다. — R-M6 (affirmed 2026-10-15)

- ALWAYS 취약점 예외는 `ci/security-exceptions.yml`에만 GHSA/CVE id · 사유 · 만료일과 함께 두고, 사람 리뷰 승인을 받아 `status: approved`가 된 뒤에만 유효한 예외로 본다. 승인되지 않은 항목은 만료 전이라도 지운다. — R-M7 (affirmed 2026-10-15)

- ALWAYS 배포 자격 증명은 GitHub OIDC로 환경 `demo`에 묶인 역할로만 얻고, 저장소 Actions 비밀은 0개로 두며, 워크플로 권한은 작업 단위 최소(기본 `contents: read`, `packages: write` · `id-token: write`는 그 권한이 필요한 작업에만)로 둔다. — R-M8 (affirmed 2026-10-15)

- ALWAYS 새 비밀 환경 변수를 만들면 같은 커밋에서 비밀 이름 검사 목록 전부(`ci.yml` 번들 검사, `infra/ci/image-checks.sh`, `infra/bin/infra-validate` · `render-env` · `log-hygiene-check`, `scripts/verify/`, `infra/ci/gen-env.sh`, `README.md`, `infra/RUNBOOK.md`의 Parameter Store 항목)에 함께 넣는다. — R-M9 (affirmed 2026-10-15)

- ALWAYS 설계 문서 변경이 API · 이벤트 · 오류 코드 · 시간 값 계약에 닿으면 같은 작업에서 `contracts/`도 함께 고쳐, CI 전 작업과 계약 대조 시험이 돌게 한다. — R-M10 (affirmed 2026-10-15)

- ALWAYS 시험 · 검증 결과는 테스트 대역(가짜 공급자) · 합성 대화 · 실제 API · 실제 기기 성공 여부를 각각 별도 증거로 나눠 보고하고, 테스트 대역이나 합성 대화의 성공을 실제 공급자 · 실제 기기 성공으로 표시하지 않는다. — R-M11 (affirmed 2026-10-15)

## Forbidden

- NEVER 시험 · CI · 검증 명령에서 실제 유료 AI 공급자를 부르지 않는다. 가짜 공급자를 쓴다. — R-F1 (affirmed 2026-10-15)

- NEVER 시험을 통과시키려고 커버리지 하한(모듈 · 유닛 범위 줄 80%)이나 기존 시험 단언을 낮추지 않고, 계약 대조 시험 · ArchUnit 시험을 고치거나 `@Disabled` · `it.skip` · `-PexcludeTags` 등으로 건너뛰게 하지 않는다. — R-F2 (affirmed 2026-10-15)

- NEVER 계정 · 비밀번호 · API 키 · 토큰 값 · 실제 도메인을 코드 · 설정 · 시험 · 문서 · 커밋 · 로그에 쓰지 않는다. 저장소에는 환경 변수 · 매개변수 이름만 둔다. — R-F3 (affirmed 2026-10-15)

- NEVER 프런트 `/parent`와 `/device` 영역이 서로를 import하게 하지 않고, 공용 계층(`shared`)이 둘 중 어느 쪽도 import하게 하지 않는다. — R-F4 (affirmed 2026-10-15)

- NEVER core-api와 realtime-activity가 서로의 코드를 직접 참조하거나 상대 앱의 DB 스키마 · 표를 읽게 하지 않는다. 두 앱은 내부 HTTP · 이벤트 계약으로만 통신한다. — R-F5 (affirmed 2026-10-15)

- NEVER 운영 코드에서 시스템 시각을 직접 읽지 않는다(`Instant.now()` · `System.currentTimeMillis()` 등). `MujoongClock`을 주입받는다. — R-F6 (affirmed 2026-10-15)

- NEVER 코드에 숫자 시간 값을 쓰지 않는다. 시간 값은 `mujoong.timing.*` 설정 키에서만 읽고, 예외는 `TimeLiteralScanTest` 허용 목록에 근거와 함께 둔다. — R-F7 (affirmed 2026-10-15)

- NEVER 운영 코드가 시험 코드나 시험 지원 모듈(`test-support`)을 참조하게 하지 않는다. — R-F8 (affirmed 2026-10-15)

- NEVER `.gitleaks.toml` 허용 목록으로 실제 비밀을 덮지 않는다. 허용 항목은 가짜 값의 경로 + 줄 정규식 + 사유로만 더하고 사람 리뷰를 받는다. — R-F9 (affirmed 2026-10-15)

- NEVER GHCR에 이미 있는 이미지 태그를 덮어쓰지 않고, `latest`나 태그 없는 베이스 이미지를 쓰지 않는다. — R-F10 (affirmed 2026-10-15)

- NEVER 워크플로 `run:`에 `${{ inputs.* }}` · `${{ github.event.* }}` 같은 외부 입력을 직접 끼워 넣지 않는다. `env:`로 넘긴다. — R-F11 (affirmed 2026-10-15)

- NEVER 사용자의 수동 테스트 스택 `mujoong-manual`과 그 Docker 볼륨 · 실행 설정을 변경 · 재시작 · 삭제하지 않는다. 새 검증은 별도 Compose 프로젝트명 · 포트 · 볼륨으로 돌린다(`scripts/verify.sh`는 `mujoong-verify`를 쓴다). — R-F12 (affirmed 2026-10-15)

- NEVER 사용자에게 모델 · 키 · 엔드포인트 값을 채팅에 붙여 넣으라고 요청하지 않는다. 이 값은 승인된 환경 변수 · 비밀 저장소로만 관리한다. — R-F13 (affirmed 2026-10-15)

- NEVER 로그에 민감한 전사 · 음성 · 개인정보 · AI 요청 원문을 남기지 않는다. 식별할 수 없는 이유 코드 · 정책 버전 · 시간 · 개수만 기록한다. — R-F14 (affirmed 2026-10-15)

- NEVER 공급자 약관 · 개인정보 처리 조건과 아동 데이터 전달 허용 범위(보호자 동의 포함)를 확인하기 전에는 아동 데이터를 외부 모델에 보내지 않는다. — R-F15 (affirmed 2026-10-15)

## 이번 갱신의 경계

- R-F10의 GHCR 문구는 기존 승인 제약으로 보존한다. 현재 배포가 ECR이라는 관측을 이유로 제약을 삭제하거나 공급자·스택 보호를 완화하지 않는다. 이미지 태그 덮어쓰기 금지 관례를 유지한다.
- ZIP의 대화 정책·서비스 계약은 제품 요구사항 입력이다. 에이전트 지시·새 개발 관례·배포 권한으로 승격하지 않는다.

