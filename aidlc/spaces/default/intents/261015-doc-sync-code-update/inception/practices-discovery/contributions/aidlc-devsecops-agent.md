**Collaborator:** aidlc-devsecops-agent

## Contribution

> 관점: 린트 · 서식, SAST/DAST, 비밀 처리 · 스캔, 의존성 스캔, 공급망 통제(액션 고정 · 이미지 출처 · 잠금 파일), 배포 자격 증명(OIDC/SSM).
> 기준 커밋 HEAD `72d7034`. 다른 지원 에이전트의 기여는 보지 않았다(블라인드).
> 표시는 리드 초안과 같다: **[증거]** 저장소 사실 · **[확인 필요]** 사람이 정할 것 · **[빈틈]** 통제가 없음을 확인한 것(팀 방식으로 적지 말고 알려진 빈틈으로 적을 것).

### 1. 이미 강제되는 보안 통제 (리드 초안의 Way of Working · Deployment · Code Style에 더할 사실)

**비밀 처리 · 스캔 [증거]**
- CI `secrets` 작업은 모든 실행(PR · main · 수동 · 주간 예약)에서 돈다. 세 단계다:
  ① 추적 파일에 `.env*` · `*.pem` · `*.key` · `*.p12` · `*.jks` · `*.pfx` 0건(`git ls-files`), ② 실행 때 만든 가짜 AWS 키를 gitleaks가 잡는지 **양성 대조**(못 잡으면 실패 — 스캐너가 죽어 있는 상태를 막음),
  ③ gitleaks로 **커밋 이력 전체** 스캔(`--redact=100`). 리드 초안은 "gitleaks 양성 대조 + 전체 이력"만 적었는데, ①과 "양성 대조가 실패하면 관문 실패"라는 점을 규칙 근거로 남길 만하다.
- `.gitleaks.toml`: 기본 규칙 전부(`useDefault = true`) + 허용 목록은 "가짜 값의 경로 · 줄 정규식 + 사유"만(현재 2건, `condition = "AND"`로 경로와 줄을 함께 묶음). 머리 주석: **허용 항목을 더하는 PR은 사람 리뷰 승인이 필요하다.**
- 비밀이 새어 나갈 수 있는 출구마다 이름 · 형식 검사가 따로 있다: 브라우저 번들(`frontend` 작업, 비밀 변수 이름 11개 + `scripts/verify/secret-patterns.txt`), 이미지 설정 · 파일(`infra/ci/image-checks.sh` ①),
  Compose 인라인 비밀 금지(`infra-validate` + 음성 샘플 `bad-inline-secret.yml`), 로그 위생(`infra/bin/log-hygiene-check`, `RealtimeLogHygieneIT`), 검증 명령 A4 · P3.
- 시험용 비밀은 실행 때 만들고 저장하지 않는다(J127, `TestSecrets` · `infra/ci/gen-env.sh` · `gen-selfsigned.sh`는 `umask 077`, CA 키는 서명 뒤 삭제, 인증서 유효 2일). 저장소 Actions 비밀 0개가 목표다(`ci.yml` 머리 주석).
- demo 비밀 값은 Parameter Store `/mujoong/demo/`에만 있고, EC2 인스턴스 역할은 그 경로 읽기만 허용한다(`infra/aws/iam-ec2-inline.json`).

**의존성 · 이미지 취약점 스캔 [증거]**
- `backend-deps`: 운영 의존성이 든 bootJar 두 개를 Trivy(`rootfs`, HIGH · CRITICAL, `--exit-code 1`)로 스캔. `frontend-deps`: `frontend` · `tools`의 `npm audit --json`을 `ci/security-gate.py`가 high 이상으로 판정.
  `images`: 6종 이미지를 Trivy로 스캔(이미지 점검 ③). 주간 예약 실행은 `backend-deps` · `frontend-deps` · `secrets`만 돈다.
- 예외는 `ci/security-exceptions.yml` 한 곳이다. 항목마다 GHSA/CVE id · 사유 · 만료일 필수, **만료 항목이 하나라도 있으면 CI 실패**(`security-gate.py exceptions`). 예외를 더하거나 늘리는 PR은 사람 리뷰 승인 필요(머리 주석).
  현재 1건: `GHSA-vfj7-8cjw-p6xm`(braces, 개발 의존성만), `expires: 2026-11-30`, `status: candidate`.
- 보안 판 상향 고정: `build-logic`이 Jackson 2/3 BOM · Tomcat embed를 올려 고정(`dependencies.md`). 베이스 이미지 OS 패치(openssl)는 Ubuntu 공식 보안 저장소의 `.deb`를 판 + sha256으로 고정해 받는다(`ba31c08`, core-api · realtime-activity · migrate 셋을 함께 바꾼다).

**공급망 통제 [증거]** — 리드의 R-M4(액션 SHA · 도구 이미지 다이제스트)보다 범위가 넓다
- GitHub Actions: 모든 `uses:`가 40자 SHA + `# <태그>` 주석. 모든 `actions/checkout`이 `persist-credentials: false`.
- CI 도구 이미지: gitleaks · trivy 다이제스트 고정(`env.GITLEAKS_IMAGE` · `TRIVY_IMAGE`).
- 런타임 베이스 이미지: **태그 + 다이제스트** 함께 고정(`JRE_IMAGE` · `NODE_IMAGE` · `PG_IMAGE` · `GO_IMAGE`, Dockerfile 주석 J350). 이미지 점검 ②가 `latest` · 태그 없는 `FROM`을 막는다.
- 원격 파일: BuildKit `ADD --checksum=sha256:…`(Flyway jar · OS 패치 .deb). Gradle 래퍼 `distributionSha256Sum` 고정. npm은 잠금 파일 + `npm ci` + `npx --no-install`.
- 이미지 안에서 빌드 · 다운로드하지 않는다(R-03: 호스트에서 만든 bootJar · standalone을 COPY, RUN 단계 네트워크 없음). 비루트 실행(uid 10001), 시험 클래스 0건(`verifyBootJarHasNoTestClasses` · 점검 ⑥).
- 출처 표시: 이미지 라벨 `org.opencontainers.image.revision`(커밋 SHA) + `kr.mujoong.source-fingerprint`. GHCR 푸시는 main 병합만, **이미 있는 태그는 덮어쓰지 않고 실패**.
- Python 도구는 `python3 -I`(격리 모드)로 부른다.

**배포 자격 증명 [증거]**
- 장기 키 없음: OIDC → `mujoong-gha-deploy` 역할(1시간), Actions 비밀 0개, 저장소 **변수**만(`AWS_DEPLOY_ROLE_ARN` 등 — 형식 정규식으로 검사).
- 신뢰 정책(`infra/aws/iam-gha-deploy-trust.json`): `sub = repo:<저장소>:environment:demo`, `aud = sts.amazonaws.com`. 즉 **GitHub 환경 `demo`를 쓰는 작업만** 역할을 맡을 수 있다.
- 권한 정책(`iam-gha-deploy.json`): `ssm:SendCommand`는 문서 `mujoong-deploy` + 태그 `Project=mujoong` · `Environment=demo` 인스턴스로 한정. 셸 접근 · 임의 문서 실행 없음.
- 워크플로 권한: 기본 `contents: read`, `packages: write`는 `images`만, `id-token: write`는 배포 작업만. 입력값은 `env`로만 넘기고 `run`에 `${{ }}`를 끼워 넣지 않는다(`deploy.yml` 머리 주석, 실제로 지켜짐).
- 배포할 수 있는 것은 GHCR에 이미 있는 SHA 태그뿐이다(PR은 푸시하지 않음) → 임의 SHA 입력으로 검사 안 거친 코드를 올릴 수 없다.

**전송 · 엣지 [증거]**: Nginx `server_tokens off`, TLS 1.2/1.3 + ECDHE AEAD만, `ssl_session_tickets off`, `ssl_reject_handshake`(기본 서버), 공통 `security-headers.conf`(HSTS · nosniff 등, `add_header` 상속 끊김 방지). 배포 뒤 인증서 만료 20일 이상 확인.

### 2. 리드 초안 정정 · 보강

1. **Code Style의 린트 범위**: 백엔드 `-Xlint:all`에서 `-processing,-serial,-this-escape,-try`를 끈다(`mujoong.java-conventions.gradle.kts` 59행). "`-Xlint:all`"만 적으면 사실보다 넓다.
   프런트 ESLint에 보안 규칙은 `react/no-danger` 하나뿐이고 보안 전용 플러그인은 없다. 셸은 shellcheck(`infra-validate`). 이 세 가지가 사실상의 "정적 보안 점검" 전부다.
2. **R-M4 범위 확대 제안**: 액션 SHA · CI 도구 이미지 다이제스트에 더해 "런타임 베이스 이미지 태그 + 다이제스트, 원격 다운로드 `ADD --checksum`, `latest` 금지"까지 한 규칙으로 묶는 편이 증거와 맞다.
   예외 하나: `tools/fake-provider/Dockerfile`의 `FROM node:22.22.0-bookworm-slim`은 다이제스트가 없다(시험 전용 · GHCR에 푸시하지 않음). 규칙을 확정하면 이것도 맞추거나 "시험 전용 이미지는 태그 고정까지"라고 범위를 적어야 한다.
3. **예외 `status`는 도구가 강제하지 않는다**: `security-gate.py`는 `status`를 읽기만 하고 판정에 쓰지 않는다. `candidate`(사람 승인 전) 항목도 만료일까지 관문을 통과시킨다.
   현재 유일한 예외가 `candidate`다. "승인되지 않으면 지운다"는 사람 절차뿐이므로, 규칙으로 확정할지([확인 필요]) 이번 작업에서 승인/삭제할지 정해야 한다.
4. **Deployment의 `demo` 환경 보호 [확인 필요]는 보안상 핵심 질문이다**: OIDC 신뢰가 `environment:demo` 하나로 묶여 있으므로, `demo` 환경의 **배포 브랜치 제한(main만)** 이 없으면
   쓰기 권한자가 아무 브랜치에서 `deploy.yml`을 수동 실행해 배포 역할을 얻는다. 이때 `deploy` 작업은 그 브랜치의 `infra/bin`을 체크아웃해 `probe-three`를 **자격 증명이 있는 상태로** 실행한다.
   역할 권한이 좁고(정해진 문서 · 인스턴스) 배포 대상은 GHCR의 main 이미지뿐이라 피해는 제한되지만, 브랜치 제한은 저장소 밖 설정이라 확인되지 않았다.
   인터뷰 질문에 "필수 리뷰어 여부"와 함께 "`demo` 환경 배포 브랜치를 `main`으로 제한했는가"를 넣을 것을 제안한다.
5. **비밀 변수 이름 목록의 변경 연쇄(이번 작업과 직결)**: 비밀 변수 이름은 여러 곳에 하드코딩돼 있다 — `ci.yml` 번들 검사 `names`, `infra/ci/image-checks.sh` ①, `infra/bin/infra-validate` · `render-env` · `log-hygiene-check`,
   `scripts/verify/lib.sh` · `b-stack.sh`, `infra/ci/gen-env.sh`, `README.md` 「비밀 · 도메인 규칙」, `infra/RUNBOOK.md`. 그런데 TD-1의 예약 이름 `MUJOONG_TEXT_AI_API_KEY`는 **이 목록 어디에도 없다**(`application.yml` 주석 · `contracts/ai-io.md`에만 있음).
   문서 갱신(`15_기술명세_AI동작_1015`)으로 운영 텍스트 AI 어댑터를 만들면 새 비밀이 생기고, 목록에 넣지 않으면 번들 · 이미지 · 로그 검사가 그 이름을 놓친다. 아래 R-M8 후보로 올린다.
   같은 이유로 운영 어댑터는 외부 HTTPS 송신을 새로 만든다 — 위협 모델(공급자로 가는 아동 발화 데이터, 키 보관, 응답 신뢰) 검토가 Construction NFR 단계에 필요하다(이번 단계 범위 밖, 기록만).

### 3. 확인한 빈틈 [빈틈] — 팀 방식이 아니라 "알려진 빈틈"으로 기록할 것

| 영역 | 상태 | 근거 |
|---|---|---|
| SAST | 없음(CodeQL · Semgrep · SonarQube 등 미설정). ESLint `react/no-danger` · ArchUnit 경계 · shellcheck만 | `.github/` 아래 워크플로 2개뿐, 설정 grep 0건 |
| DAST | 없음. 배포 뒤 3종 호출 · 인증서 확인은 가용성 점검이지 보안 시험이 아님 | `deploy.yml` S5b |
| 의존성 자동 갱신 | Dependabot · Renovate 없음. 주간 예약 스캔이 발견만 하고 갱신은 사람 몫 | `.github/dependabot.yml` 없음 |
| Gradle 의존성 무결성 | `gradle/verification-metadata.xml` · 의존성 잠금 없음(래퍼 sha256만). npm은 잠금 파일로 고정 | `backend/gradle/`에 `libs.versions.toml` · `wrapper`만 |
| 주간 이미지 재스캔 | 예약 실행에서 `images`는 꺼짐 → 새로 알려진 베이스 이미지 · OS CVE는 이미지가 바뀔 때까지 안 잡힘 | `ci.yml` `changes` 의 `schedule` 분기 |
| 빌드 도구 의존성 | `backend-deps`는 bootJar(운영 의존성)만 스캔. Gradle 플러그인 · 빌드 classpath는 대상 아님 | `backend-deps` 단계 |
| SBOM · 서명 · 증명 | SBOM 생성 · cosign 서명 · 빌드 증명(attestation) 없음. 출처는 라벨(SHA · 소스 지문)과 태그 불변 검사뿐 | `images` 작업 |
| 커밋 전 비밀 차단 | pre-commit 훅 없음. 로컬은 `scripts/verify/a-secrets.sh`(gitleaks가 있을 때만), 막는 곳은 CI | `scripts/verify/` |
| 포매터 | 리드 초안과 같음(Prettier 설정만 있고 미실행, Java 포매터 없음). 보안 영향은 낮고 리뷰 가독성(TD-5) 문제 | — |

이 빈틈을 이번 작업에서 메울지는 [확인 필요]다. 이번 작업 범위(문서 갱신에 맞춘 코드 수정)에 비해 크므로, 기본 제안은 "빈틈으로만 기록하고 메우지 않는다"이다.
다만 Gradle 의존성을 새로 더하게 되면(예: AI 공급자 SDK) 잠금 · 검증 부재가 바로 위험이 되므로, 그때는 "새 의존성은 판 고정 + `backend-deps` 통과 + 사람 리뷰"를 최소 조건으로 둘 것을 권한다.

### 4. discovered-rules 추가 후보 (사람 확인 전)

- **R-M6** ALWAYS 취약점 예외는 `ci/security-exceptions.yml`에만 두고, GHSA/CVE id · 사유 · 만료일을 적으며, 사람 리뷰 승인을 받은 뒤 `status: approved`로 둔다. 승인되지 않은 후보는 만료 전이라도 지운다. (근거: 예외 파일 머리 주석, `security-gate.py`)
- **R-M7** ALWAYS 배포 자격 증명은 GitHub OIDC → 환경 `demo`에 묶인 역할로만 얻고, 저장소 Actions 비밀은 두지 않으며, 워크플로 권한은 작업 단위 최소(`contents: read` 기본, `packages: write` · `id-token: write`는 해당 작업만)로 둔다. (근거: `deploy.yml` · `ci.yml` 머리 주석, `infra/aws/iam-gha-deploy*.json`)
- **R-M8** ALWAYS 새 비밀 환경 변수를 만들면 같은 커밋에서 비밀 이름 목록(`ci.yml` 번들 검사, `infra/ci/image-checks.sh`, `infra/bin/infra-validate` · `render-env` · `log-hygiene-check`, `scripts/verify/`, `infra/ci/gen-env.sh`, `README.md`, `infra/RUNBOOK.md`의 Parameter Store 항목)에 함께 넣는다. (근거: 위 2절 5, R-M5와 같은 "변경 연쇄" 유형)
- **R-F6** NEVER `.gitleaks.toml` 허용 목록으로 실제 비밀을 덮지 않는다. 허용 항목은 가짜 값의 경로 + 줄 정규식 + 사유로만 더하고 사람 리뷰를 받는다. (근거: `.gitleaks.toml` 머리 주석)
- **R-F7** NEVER 이미 GHCR에 있는 이미지 태그를 덮어쓰거나 `latest` · 다이제스트 없는 운영 베이스 이미지를 쓰지 않는다. (근거: `images` 푸시 단계, 이미지 점검 ②, Dockerfile J350) — R-M4를 넓히면 R-M4에 합쳐도 된다.
- **R-F8** NEVER 워크플로 `run:`에 `${{ inputs.* }}` · `${{ github.event.* }}` 같은 외부 입력을 직접 끼워 넣지 않고 `env:`로 넘긴다. (근거: `deploy.yml` 머리 주석, 현행 두 워크플로가 모두 지킴)

## Positions

- AGREE: R-M4(액션 SHA · 도구 이미지 다이제스트 고정) — 모든 `uses:`와 두 도구 이미지가 실제로 고정돼 있다. 다만 런타임 베이스 이미지 · `ADD --checksum`까지 범위를 넓히고 fake-provider 예외를 정리할 것(2절 2).
- AGREE: R-F3(비밀 · 실제 도메인 금지) — `README.md` 규칙과 CI `secrets` · 번들 · 이미지 · 로그 검사가 함께 강제한다. R-M8(비밀 이름 목록 연쇄)과 짝으로 확정하는 편이 좋다.
- AGREE: R-F1(시험 · CI · 검증에서 실 공급자 금지) — 실 키가 CI에 들어오지 않는 것 자체가 비밀 노출 면을 없애는 보안 통제이기도 하다.
- AGREE: R-M1(`ci-gate` 통과 후 병합) — 보안 관문(`secrets` · `*-deps` · 이미지 점검)이 모두 `ci-gate` 집계에 묶여 있어 이 규칙이 보안 관문의 실효성을 정한다.
- OBJECT: Deployment 절의 "`demo` 환경 수동 승인 보호 여부 [확인 필요]"를 운영 방식 질문으로만 두는 것 — OIDC 신뢰가 `environment:demo`에 묶여 있어 환경의 배포 브랜치 제한이 배포 역할의 실제 경계다. 인터뷰에서 브랜치 제한 여부를 별도로 물어야 한다(2절 4).
- OBJECT: Code Style 절이 보안 정적 점검 부재를 다루지 않는 것 — SAST · DAST · SBOM · 의존성 자동 갱신 · Gradle 의존성 검증이 없다는 사실이 근거 파일에 빠져 있어, 기록이 없으면 이후 단계가 "점검된다"고 오해할 수 있다. `evidence.md` 남은 불확실성에 3절 표를 빈틈으로 더할 것.
- OBJECT: 예외 목록을 사실로만 적고 `status` 미강제를 다루지 않은 것 — 유일한 예외가 `candidate` 상태로 관문을 통과하고 있어, R-M6 확정 여부를 인터뷰에 넣어야 한다(2절 3).
