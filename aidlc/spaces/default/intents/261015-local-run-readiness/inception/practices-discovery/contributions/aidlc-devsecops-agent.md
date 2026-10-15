**Collaborator:** aidlc-devsecops-agent

## Contribution

> 관점: 보안 · 공급망 · 비밀 관리 · 로컬 현장 실행의 보안 영향. 기준 `ops/local-run-readiness`(origin/main `72d7034`), 읽기만 했다.
> 리드 초안(`team-practices.md` · `discovered-rules.md` · `evidence.md`)과 독립적으로 근거를 다시 확인했다.

### 1. 이미 강제되고 있는 보안 관문 (확인됨)

| 영역 | 실제 통제 | 근거 |
|---|---|---|
| 비밀 스캔 | gitleaks(기본 규칙 전부 + 허용 목록은 경로 · 줄 정규식으로 좁힘), **커밋 이력 전체**(`fetch-depth: 0`), 실행 때 심은 가짜 AWS 키를 잡는지 **양성 대조**로 스캐너 동작 자체를 확인. 모든 실행(주 1회 예약 포함)에서 돈다 | `ci.yml` 322 ~ 359행, `.gitleaks.toml` |
| 추적 금지 파일 | `.env*` · `*.pem/key/p12/jks/pfx`가 git에 있으면 실패 | `ci.yml` `secrets` 첫 단계 |
| 브라우저 번들 검사 | `.next/static`에서 비밀 변수 이름 목록 + `scripts/verify/secret-patterns.txt` 키 형식 0건 | `ci.yml` 246 ~ 255행 |
| 의존성 취약점 | 백엔드: 실행 jar를 Trivy `rootfs` HIGH/CRITICAL로 판정. 프런트 · tools: `npm audit` → `ci/security-gate.py`가 예외 목록(`ci/security-exceptions.yml`)으로 판정. 이미지: `image-checks.sh`가 Trivy로 점검. 주 1회 예약 실행 | `ci.yml` 197 ~ 298 · 429 ~ 443행 |
| 공급망 고정 | 액션 40자 SHA, gitleaks · trivy 다이제스트, 앱 베이스 이미지(temurin · node · postgres · golang) 다이제스트, OS 패치 `.deb` · Flyway jar는 BuildKit `ADD --checksum=sha256`(공식 `security.ubuntu.com` · Maven Central, 커밋 `ba31c08`), `npm ci` 잠금 파일 | 각 `Dockerfile`, `ci.yml` 12 ~ 13 · 38 ~ 39행 |
| CI 권한 | 기본 `contents: read`, `packages: write`는 images만, `id-token: write`는 배포 호출만, OIDC → SSM(장기 AWS 키 없음), `persist-credentials: false` | `ci.yml` 11행, `deploy.yml` 15 · 59 · 146 ~ 150행 |
| 시험 로그인 차단 | dev 프로필 **AND** `MUJOONG_DEVLOGIN_ENABLED=true`일 때만 빈 생성, 어떤 프로필 파일에도 true 금지, 기동 전 프로필 가드 + 요청 때 이중 확인 + 차단 기록, demo는 시험 로그인과 함께 기동 거부(SD-14) | `ProfileGuardEnvironmentPostProcessor.java`, `DevLoginController.java`, `application.yml` 7행 |
| 로컬 비밀 생성 | `gen-env.sh`가 `umask 077`, 디렉터리 0700 · 파일 0600, 값은 출력하지 않음. 자체 서명 인증서는 실행마다 새 CA, CA 키는 서명 뒤 삭제, 유효 2일 | `infra/ci/gen-env.sh` 21 ~ 33행, `infra/ci/gen-selfsigned.sh` 4 ~ 12행 |
| 노출 범위 | dev · 검증 스택 게시 포트는 `127.0.0.1`만(infra-validate가 dev 파일에 단언) | `compose.dev.yml` 4 ~ 5 · 39 · 79행, `compose.ci.yml` 31행 |
| 로그 위생 | 검증 C안 · `log-hygiene-check`가 비밀 이름 등록부(`VERIFY_SECRET_NAMES`)로 로그에 값이 새는지 본다 | `scripts/verify/lib.sh` 45 ~ 48행, `c-skeleton-turn.sh` 52행 |

리드 초안 discovered-rules의 「액션 SHA · 이미지 다이제스트 고정」 · 「시험 비밀 실행 때 생성」은 위 사실과 맞는다. 다만 아래 공백 때문에 범위를 정확히 적어야 한다.

### 2. 공백 (보안 관점, 우선순위 순)

1. **비밀 이름 등록부가 두 곳에 따로 있고 둘 다 VAPID · TEXT_AI 키가 빠졌다 (높음)**
   - `scripts/verify/lib.sh` `VERIFY_SECRET_NAMES`와 `ci.yml` 249행 `names=`가 같은 목록을 손으로 따로 들고 있다(단일 출처 아님).
   - 둘 다 `MUJOONG_PUSH_VAPID_PRIVATE_KEY`(`PushVapidSettings.java` 22행이 읽음)와 `MUJOONG_TEXT_AI_API_KEY`(`application.yml` 223행 주석에 이름이 예약됨)가 없다. 그래서 (a) 번들 검사, (b) 검증 C안 로그 위생 단언, (c) 아마 `log-hygiene-check`에서 이 두 키는 사각이다(`code-quality-assessment.md` M10과 같은 결론).
   - VAPID 개인 키는 base64url 32바이트라 `secret-patterns.txt`의 어떤 형식 패턴에도 걸리지 않는다. 즉 이름 검사가 유일한 방어선인데 그 이름이 없다.
   - 로컬 현장에서 웹 푸시나 실제 텍스트 AI를 켜는 순간 이 사각이 실제 위험이 된다. 이번 작업에서 등록부를 한 파일로 모으고(예: `scripts/verify/secret-names.txt`를 `lib.sh` · `ci.yml` · `log-hygiene-check`가 함께 읽음) 두 이름을 더할 것을 권한다.
2. **VAPID 키 생성 · 분배 경로가 없다 (중간)**: `gen-env.sh` · `render-env` · RUNBOOK 4절 Parameter Store 목록에 없다. 켤 경우 생성 도구가 `gen-env.sh`와 같은 권한 규칙(0600 · 값 미출력)을 따르게 하고, 개인 키는 `NEXT_PUBLIC_*`나 web 서비스 env 파일에 들어가지 않게(core-api 전용) 분배해야 한다.
3. **SAST가 없다 (중간)**: CodeQL · Semgrep · SpotBugs/Find Security Bugs 같은 정적 보안 분석이 CI에 없다. 지금은 ESLint(`react/no-danger` 등) · ArchUnit · `-Xlint:all`이 구조를 지킬 뿐 보안 결함 패턴(주입 · 경로 조작 · 역직렬화)을 보지 않는다. 이번 작업 범위(로컬 실행 준비)에 새 관문을 넣을지는 사람이 정할 일이므로 **팀 규칙이 아니라 공백으로 기록**만 하자고 제안한다.
4. **DAST가 없다 (낮음 ~ 중간)**: 검증 스택이 `127.0.0.1:8443`으로 실제 Nginx + 두 앱을 띄우므로 OWASP ZAP baseline 같은 수동 · 선택 점검을 붙일 자리는 있다. 로컬 현장에서 LAN에 게시한다면 그 전에 한 번은 권한다.
5. **의존성 갱신 자동화가 없다 (중간)**: `.github/dependabot.yml` · `renovate.json` 없음. 탐지(주 1회 스캔)는 있지만 갱신 PR은 사람이 만든다. 다이제스트 · SHA 고정과 짝을 이루는 갱신 경로가 없으면 고정이 곧 노후가 된다.
6. **고정 예외 (중간)**: `tools/fake-provider/Dockerfile` 8행 `node:22.22.0-bookworm-slim`, `compose.demo.yml`의 `nginx:1.30.5-alpine` · `postgres:16.15` · `certbot/certbot:v5.8.0` · `neo4j:5.26.31-community`, `compose.dev.yml`의 postgres · nginx는 **태그만** 있고 다이제스트가 없다. 앱 이미지 Dockerfile의 `ARG ..._IMAGE`는 다이제스트 고정이므로 "모든 이미지 다이제스트 고정"은 사실이 아니다. 리드 초안 Mandated 문장은 범위를 "GitHub Actions 액션과 CI 도구 이미지 · 앱 이미지 베이스"로 좁혀야 사실과 맞는다(Positions 참고).
7. **SBOM이 없다 (낮음)**: Trivy가 이미 있으므로 `trivy image --format cyclonedx`로 만들 수 있다. 현장 배포 묶음에 남길지 선택 사항.

### 3. 린트 · 포매터 사실 정정

- 저장소 루트에 **`.prettierrc.json`이 있다**(printWidth 100 · LF 등). 그러나 `frontend/package.json` 스크립트와 `ci.yml` 어디에서도 `prettier --check`를 돌리지 않는다. 즉 "포매터 없음"(리드 초안)도, "Prettier 있음"(`code-quality-assessment.md` 54행)도 반만 맞다. **설정은 있으나 강제되지 않음**이 정확하다. 인터뷰 질문 8은 "도입할까"가 아니라 "이미 있는 설정을 CI에서 강제할까"로 바꾸는 편이 맞다.
- 백엔드는 `-Xlint:all`만 있고 `-Werror`가 없어 경고가 빌드를 막지 않는다. 보안 영향은 작다.
- 셸은 shellcheck가 `infra-validate`에서 돈다. 다만 `infra-validate` 작업은 `infra` 경로가 바뀔 때만 돈다(`if: needs.changes.outputs.infra`) — `scripts/` 아래 셸이 대상에 드는지 확인 필요.

### 4. 로컬 현장 실행의 보안 영향 (이번 작업의 핵심)

현재 구성은 **"한 PC 안, 루프백, 일회성, 가짜 공급자"** 전제에서 안전하게 설계돼 있다. 현장 요구가 이 전제를 넘는 순간 아래가 바뀐다. 리드 인터뷰 질문 3에 보안 하위 질문으로 붙이길 권한다.

| 현장 선택 | 보안 영향 | 필요한 통제 |
|---|---|---|
| 아이 태블릿을 **LAN으로** 붙인다 | 입구를 `0.0.0.0` 또는 LAN IP에 게시해야 한다(M7). 같은 망의 다른 기기도 접근 가능. 시험 로그인이 켜진 검증 스택을 그대로 LAN에 열면 **누구나 부모 계정으로 들어간다** | 시험 로그인을 끈 별도 현장 구성(구글 OAuth 또는 그에 준하는 실제 인증), LAN 게시는 Nginx 443만, 호스트 방화벽으로 현장 망 대역만 허용, DB 5432 · 관리 포트 9090/9091 · 가짜 공급자 8090은 절대 게시하지 않음, `MUJOONG_SECURITY_ALLOWED_ORIGIN`을 현장 이름으로 정확히 |
| 자체 서명 인증서 | 태블릿에 CA를 신뢰시켜야 마이크(`getUserMedia`) · WSS가 동작. 유효 2일 · 실행마다 새 CA는 상시 기동과 맞지 않는다 | 현장 전용 CA를 한 번 만들어 보관(키는 0600, 저장소 밖), 서버 인증서는 `--host <현장 이름>` · 짧은 유효 기간으로 갱신, 기기에 설치한 CA는 현장 종료 때 제거하는 절차. **CA 키를 태블릿 · 공유 폴더에 두지 않는다** |
| 시험 로그인 | 현재 가드(dev AND 환경 변수)는 견고하나, 검증 스택은 의도적으로 켠다 | 현장 상시 기동은 검증 스택(`compose.ci.yml`)을 재사용하지 말 것 — 리드 Forbidden 「검증 스택을 demo에서 금지」를 **현장 기기 접속이 있는 환경 전체**로 넓히는 것을 제안 |
| 실제 AI 공급자 키 | 키가 현장 PC 디스크(`env` 파일)에 놓인다. M13대로 dev에서는 실공급자로 갈 수 없음 | 키는 `gen-env.sh`식 0600 파일 또는 OS 비밀 저장소, 현장 종료 때 회수 · 회전. 그 전에 공백 1(이름 등록부)을 먼저 막는다 |
| **아이 음성 데이터** | realtime이 세션 · 화자별 원음 PCM을 `MUJOONG_RECORDING_DIRECTORY`(기본 tmpfs `/tmp/mujoong-recordings`, 세션당 64MB)에 쓴다. 볼륨으로 바꾸면 **아동 음성이 현장 PC 디스크에 평문으로 남는다**. 전사 · 결과도 PostgreSQL 볼륨에 남는다 | 현장 PC 디스크 암호화(BitLocker/LUKS), 녹음 디렉터리는 tmpfs 유지 또는 보존 기간 · 삭제 절차 명시, 현장 종료 시 볼륨 삭제(`docker compose down -v`) 또는 백업 반출 절차, 보호자 동의 범위(정책 약관 시드)가 실제 현장 사용에 맞는지 compliance 확인. 가짜 공급자 시연이라도 마이크 입력은 실제 아이 목소리다 |
| 오프라인 현장 | 빌드 때 Docker Hub · Ubuntu 보안 저장소 · Maven Central이 필요(`dependencies.md` 44행) | 이미지는 CI가 만든 커밋 SHA 태그(GHCR)를 미리 받아 `docker save` 반입, 반입 뒤 다이제스트 대조. 현장에서 다시 빌드하지 않는다 |

### 5. 통합 시험 관점 보안 항목 (리드 질문 7에 덧붙임)

이번 작업에서 통합 시험을 더한다면 보안상 값어치가 큰 순서:
1. **LAN 게시 구성에서 시험 로그인이 꺼져 있음을 단언**(`/api/dev/login`류가 404 · 거절, 차단 기록 남음).
2. **게시 포트 단언**: 현장 구성에서 443 외 포트가 호스트 밖에 열리지 않음(`infra-validate`의 dev 단언을 현장 파일로 확장).
3. **비밀 이름 등록부 확장 후** 번들 · 로그 위생 검사에 VAPID 개인 키 · TEXT_AI 키 양성 대조(심은 가짜 값이 잡히는지) — gitleaks 양성 대조와 같은 방식.
4. 녹음 디렉터리가 지정한 경로(또는 tmpfs)에만 쓰이고 재기동 뒤 남지 않음(선택).

## Positions

- AGREE: 「GitHub Actions 액션 40자 SHA 고정」 Mandated — `ci.yml` 모든 `uses:`가 SHA 고정이며 머리 주석이 규칙으로 명시한다.
- OBJECT: 「도구 컨테이너 이미지는 다이제스트로 고정한다」의 현재 표현 — 앱 베이스 이미지까지는 맞지만 `fake-provider` 베이스와 Compose의 nginx · postgres · certbot · neo4j는 태그만이라, "모든 이미지 다이제스트"로 읽히지 않게 범위를 정확히 적거나, 남은 이미지 고정을 이번 작업 할 일로 따로 기록해야 한다.
- AGREE: 「시험용 비밀은 실행할 때 만들고 끝나면 지우며 저장소에 남기지 않는다」 — `gen-env.sh`(0600 · 값 미출력) · CI 추적 파일 검사 · gitleaks 이력 스캔이 실제로 강제한다.
- AGREE: 「시험 · CI · 검증에서 실제 유료 AI 공급자 금지」 — 비용뿐 아니라 실제 키가 CI에 들어오지 않게 하는 비밀 노출 방지 효과도 있다.
- OBJECT: 「검증 스택을 demo 호스트에서 돌리지 않는다」의 범위 — 이번 작업의 위험은 demo가 아니라 로컬 현장이다. 시험 로그인이 켜진 검증 스택을 아이 기기가 접속하는 어떤 환경(LAN 게시 현장 포함)에서도 상시 기동하지 않는다로 넓혀야 한다.
- OBJECT: Code Style 「포매터가 없다」 — 루트에 `.prettierrc.json`이 있으나 CI에서 강제되지 않는 상태이므로 사실을 "설정은 있으나 미강제"로 고치고 인터뷰 질문도 그에 맞춰야 한다.
- AGREE: 「모든 PR은 `ci-gate` 통과 뒤에만 병합」 — `ci-gate`가 `secrets` · `backend-deps` · `frontend-deps` · `images`를 모두 needs로 묶어 보안 관문의 집계점이다(`ci.yml` 517행). 다만 브랜치 보호에서 필수 검사로 걸렸는지는 저장소 밖이라 확인 필요라는 리드 표시에 동의한다.
- OBJECT: 발견 규칙 후보에 비밀 이름 등록부 규칙이 없다 — "ALWAYS 새 비밀 환경 변수를 추가할 때 비밀 이름 등록부(현재 `lib.sh` `VERIFY_SECRET_NAMES`와 `ci.yml` 번들 검사 `names`)에 같이 등록한다"를 후보로 더하길 제안한다. VAPID · TEXT_AI 키 누락이 바로 이 규칙 부재에서 생겼다.
- AGREE: 「`/parent` ↔ `/device` import 금지」 — 아이 기기 번들에 부모 영역 코드(계정 · 리포트 API 호출부)가 섞이지 않게 하는 경계로서 보안 가치도 있다.
