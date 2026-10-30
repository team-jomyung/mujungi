# 무중 (Mujoong)

6~12세 아이의 화용 능력을 지원하는 AI 음성 대화 서비스의 애플리케이션·계약·개발 기록 저장소입니다.

현재 구현을 이해하려면 [문서 읽는 순서](docs/README.md)와 [프로젝트 설명](docs/project-description.md)부터 확인하세요. 2026-10-08 화면 설계와 AI-DLC 산출물은 설계 당시의 근거이며, 이후 변경은 현재 코드·계약 및 [편차 기록](contracts/deviations.md)을 함께 봅니다.

- `docs/project-description.md` : `/aidlc` 시작용 설명 한 장
- `docs/aidlc_drafts/` : AI-DLC 산출물 이름에 맞춘 초안(Edit File 모드에서 붙여넣기용)
- `aidlc/spaces/default/knowledge/documents/` : 참고 문서 8묶음(화면 설계 · 개발 명세 · 요구사항 v1.1 · 기능명세 · ADR_2S · ERD v0.1 · WBS v0.5 · 점검표). 읽는 순서는 그 안의 00_읽는순서.md

## 시작
```
aidlc doctor
```
Claude Code를 이 폴더에서 열고:
```
/aidlc knowledge onboard aidlc/spaces/default/knowledge/documents
/aidlc Read ./docs/project-description.md and build what it describes
```
초기 AI-DLC 작업은 `classic` · `Edit File`을 사용했다. 아래는 해당 작업 환경의 안내다. 기존 화면 설계와 현재 구현이 다르면 [편차 기록](contracts/deviations.md)과 관련 PR을 먼저 확인하고, 근거 없이 과거 설계를 현재 요구사항으로 덮어쓰지 않는다.

## 클라우드 세션(claude.ai/code)에서 이어가기
클라우드 세션은 플랜 한도보다 클라우드 세션 크레딧을 먼저 쓴다. 작업 기록은 전부 git에 있으므로 어느 기계에서든 이어진다.
1. claude.ai/code 에서 이 GitHub 레포(team-jomyung/mujungi)로 세션을 연다.
2. 세션 안에서 `bash scripts/cloud-setup.sh` 를 실행한다(AI-DLC 설치 → `aidlc config --harness claude` → `aidlc doctor`).
3. Claude Code를 재시작하고 `/aidlc` 를 입력하면 저장된 단계에서 이어진다. 게이트마다 "Approve" 또는 고칠 내용을 적는다.
4. 끝나면 `git add aidlc/ && git commit && git push` 로 기록을 올린다. 로컬에서 이어 받을 때는 먼저 `git pull`.
한 시점에 한 곳(로컬 또는 클라우드)에서만 돌려야 기록이 갈라지지 않는다.

## Windows + Git Bash 주의
Claude Code 훅은 bash로 실행되는데 설치된 명령은 `aidlc.cmd`라 bash가 못 찾는다(`aidlc: command not found`로 훅이 전부 실패하고 `/aidlc`가 제대로 돌지 않는다). `%LOCALAPPDATA%\aidlc\bin\` 안에 확장자 없는 `aidlc` 셸 스크립트를 하나 두면 된다.

```sh
#!/bin/sh
exec powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "C:\Users\<사용자>\AppData\Local\aidlc\aidlc-shim.ps1" "$@"
```

`cmd.exe //c ...` 로 감싸는 방법은 Claude Code 훅 환경(MSYS_NO_PATHCONV=1)에서 `//c`가 변환되지 않아 cmd 배너만 찍고 끝나므로 쓰지 않는다. 래퍼를 둔 뒤 Claude Code를 완전히 다시 시작한다(`/clear`로는 안 된다). Git Bash에서 `claude -p "/aidlc ..."`처럼 슬래시 명령을 넘길 때는 `MSYS_NO_PATHCONV=1`을 붙여야 `/aidlc`가 경로로 바뀌지 않는다.

## 개발 · 검증

애플리케이션 코드는 저장소 루트의 실행 단위 디렉터리에 둔다(U1 3.5 J97). 유닛 구분은 컴포넌트 패키지 이름과 유닛별 `source-manifest.json`으로 한다.

| 디렉터리 | 내용 |
|---|---|
| `backend/` | Gradle 멀티 프로젝트(Java 21 · Spring Boot): `contract-model` · `core-api` · `realtime-activity` · `test-support`(시험 전용) |
| `frontend/` | Next.js App Router 한 앱(`/parent` · `/device` · `/admin`, Node 22) |
| `contracts/` | 앱 사이 · 클라이언트 계약 정본(OpenAPI · AsyncAPI · JSON Schema) |
| `infra/` | Compose · Nginx · PostgreSQL 초기화 · 배포 스크립트 |
| `tools/` | 가짜 공급자 · 기기 시뮬레이터 · 끝-끝 시나리오(Node 22 ESM, 앱 이미지 밖) |
| `scripts/` | 검증 명령(`scripts/verify.sh`)과 단계 스크립트 |

전제: JDK 21, Node 22(`.nvmrc`), Docker(Compose v2.24.4 이상) 또는 호스트 PostgreSQL 16. 비밀 값(계정 · 비밀번호 · API 키 · 토큰 · 실제 도메인)은 저장소 어디에도 두지 않고 환경 변수 이름만 쓴다. `.env*` · 인증서 · 키 파일 · `build/`는 `.gitignore`가 막는다. 유닛 시험 명령은 각 유닛의 `code-generation/unit-test-instructions.md`에 있다.

### 로컬 기동(dev)

보호자 웹은 큰 화면에서 4px 스마트폰 테두리를 기본 표시한다. 별도 설정은 필요 없으며, 숨기려면 웹 서버 기동 환경에 `MUJOONG_PHONE_PREVIEW=false`를 주고 재시작한다. 모바일·작은 창에서는 기존 전체 화면을 유지한다. [화면 디자인·프레임 안내](docs/ui/screen-spec-v1.5.md)를 참고한다.

Neo4j는 전체 실행에서 기본 활성화되어 있다. [Neo4j 실행·검증 안내](docs/neo4j-runtime.md)를 먼저 확인한다.
호스트에서 실행하는 Core에는 생성된 `NEO4J_AUTH`와 `MUJOONG_KNOWLEDGE_NEO4J_ENDPOINT=http://127.0.0.1:7474`를 전달한다.
CI와 그래프를 사용하지 않는 자동 시험만 명시적으로 비활성화한다.

앱 셋은 IDE나 호스트 프로세스로 돌리고, PostgreSQL·Neo4j를 Compose로 띄운다. 비밀 값은 실행 때 `build/dev/env/`에 생기고 저장소에는 없다(J127).

```bash
bash infra/ci/gen-env.sh --out build/dev/env --origin http://localhost:3000 --keep-existing
bash infra/bin/render-pgconf --out build/dev/postgres/postgresql.conf
docker compose --env-file build/dev/env/compose.env -f infra/compose.dev.yml up -d     # postgres 127.0.0.1:5432
(cd backend && ./gradlew --no-daemon :core-api:bootJar :realtime-activity:bootJar)
(cd frontend && npm ci && npx --no-install next build)
```

- 마이그레이션은 `infra/migrate/flyway-{core,realtime}.conf` 순서(core → realtime)로 `mujoong_owner`가 적용하고, 참조 시드는
  `infra/seed/001-reference.sql`이다(검증 명령 B 단계가 같은 순서로 한다).
- 두 앱은 `SPRING_PROFILES_ACTIVE=dev`로 띄우고, 호스트에서 함께 돌릴 때는 `SPRING_DATASOURCE_URL`(127.0.0.1) ·
  `MUJOONG_INTERNALHTTP_COREBASEURL` · `MUJOONG_INTERNALHTTP_REALTIMEBASEURL` · `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`를 바꾸고
  관리 포트(`MANAGEMENT_SERVER_PORT`)를 앱마다 다르게 준다. 필요한 값의 이름과 순서는 `infra/compose.dev.yml` 머리 주석과
  `scripts/verify/b-host.sh`가 정본이다. 비밀은 `build/dev/env/<서비스>.env`의 값을 환경 변수로 넘긴다.
- 시험 로그인(`/parent/dev-login`)은 dev 프로필 + `MUJOONG_DEVLOGIN_ENABLED=true`일 때만 열린다. demo · prod와 함께 주면 앱이
  기동을 거부한다(SD-14 · A2).
- 실공급자 주소(`MUJOONG_PROVIDER_URL`)는 dev에 기본값이 없다. 로컬에서는 가짜 공급자 `node tools/fake-provider/server.mjs`(8090)를 쓴다.

### 실키 전체 기동(팀원 수동 시험)

실제 OpenAI(GPT-Live · Luna)와 제브 키로 전체 스택을 내 PC에 띄워 브라우저에서 직접 시험한다. **개발자 PC 로컬 전용**이며, 시험 · CI · `verify.sh`는 계속 가짜 공급자만 쓴다. 자세한 설명과 문제 해결 · 시험 기록은 [`docs/live-run.md`](docs/live-run.md).

**준비물**: Docker(Compose v2.24.4 이상, 이 저장소의 `sg docker` 환경이면 `sg docker -c '…'`로 감싼다), `openssl`, OpenAI API 키, 제브 API 키. `--local` 방식은 JDK 21 · Node 22도 필요하다.

**1) API 키 넣기** — 키는 저장소 밖 `~/.config/mujoong/live.env`(권한 0600)에만 저장되어 git에 올라가지 않는다.

```bash
bash scripts/live/keys.sh          # OPENAI_API_KEY · JEV_API_KEY는 화면에 보이지 않게 입력
bash scripts/live/keys.sh --show   # 값은 숨기고 설정됨 / 비어 있음만 확인
```

- 제브 주소 · 모델은 기본값(`https://api.typesafe.ai`, `jev-1.13.0`)이 채워져 있어 Enter로 넘기면 된다. 제브 키를 비우면 제브는 가짜 대역으로 돈다.
- 키를 채팅 · 이슈 · PR · 커밋에 붙여 넣지 않는다. 저장 위치가 저장소 안이면 스크립트가 거절한다.
- 제브 단독 확인(스택 없이): `bash scripts/live/jev-probe.sh`.

**2) Docker로 기동**

```bash
bash scripts/live/up.sh --tag <40자 커밋 SHA>   # main 병합 때 CI가 GHCR에 올린 이미지를 당겨 쓴다 (코드 빌드 불필요)
bash scripts/live/up.sh --local                  # 내 작업 트리에서 이미지를 빌드해 쓴다 (병합 전 코드 시험, 첫 실행은 몇 분)
```

- `--tag`가 당기는 이미지는 `ghcr.io/team-jomyung/mujoong-{core-api,realtime,web,migrate}:<SHA>`이다. 패키지가 비공개이면 한 번 `docker login ghcr.io -u <GitHub 사용자>`(읽기 권한 토큰)를 한다.
- 끝나면 접속 주소가 출력된다. 포트는 기본 9443(`--port`로 변경)이고 `127.0.0.1`에만 열린다.

**3) 브라우저에서 시험** — 자체 서명 인증서라 경고가 나온다. "계속"을 누르거나 `build/live/certs/ca.crt`를 신뢰 목록에 넣는다.

- 보호자: `https://localhost:9443/parent/dev-login`(시험 로그인) · 기기: `https://localhost:9443/device`(마이크 허용 필요).
- 아이 음성 대신 합성 · 팀원 음성만 쓴다. 제브 연결 전 R-F15 확인 기록(`infra/RUNBOOK.md` 단계 4)을 먼저 남긴다.

**4) 끄기 · 보기**

```bash
bash scripts/live/down.sh            # 내리기 (DB 유지)
bash scripts/live/down.sh --purge    # DB와 생성 비밀(build/live/)까지 삭제 — 키 파일은 지우지 않는다
docker compose -p mujoong-live logs -f realtime   # 로그 (비밀 · 전사 없음)
```

이 스택은 프로젝트 이름 `mujoong-live`와 별도 망 대역을 쓰므로 `mujoong-manual` · `mujoong-verify`와 같이 떠 있어도 겹치지 않는다.

### 유닛 시험 명령(U1)

정확한 목록은 `aidlc/spaces/default/intents/261008-mujoong-service/construction/u1-walking-skeleton/code-generation/unit-test-instructions.md`가
정본이다. 자주 쓰는 것만 옮긴다(각 줄을 저장소 루트에서 따로 실행한다).

```bash
cd backend && ./gradlew --no-daemon :contract-model:test :core-api:test :realtime-activity:test :test-support:test -PincludeTags=skeleton
cd backend && ./gradlew --no-daemon :contract-model:test :core-api:test :realtime-activity:test \
  :contract-model:jacocoTestCoverageVerification :core-api:jacocoTestCoverageVerification \
  :realtime-activity:jacocoTestCoverageVerification -PincludeTags=skeleton -PcoverageScope=skeleton
cd frontend && npm ci && MUJOONG_COVERAGE_SCOPE=skeleton npx --no-install vitest run tests --coverage
cd tools && npm ci && npx --no-install vitest run tests
```

- PostgreSQL이 필요한 시험(`@Tag("db")`)은 Docker(Testcontainers)나 호스트 PostgreSQL 16(`/usr/lib/postgresql/16/bin`)으로 돈다.
  `-Pmujoong.test.db=auto|docker|local`로 고르고 기본은 auto다(J132). root로 local을 쓰면 저장소 경로를 `postgres` 사용자가 지나갈 수 있어야 한다.
- 줄 커버리지는 보고 전용이다(백엔드 모듈별 JaCoCo 보고서 · 프런트 `src/**` Vitest 보고서). CI는 하한으로 실패시키지 않는다.
  80% 줄 규칙은 `jacocoTestCoverageVerification`을 직접 부를 때만 돈다(로컬 · 검토 근거). `-PcoverageScope=skeleton` ·
  `MUJOONG_COVERAGE_SCOPE=skeleton`은 U1이 만든 파일(`backend/coverage-scope/skeleton.txt` · `frontend/coverage-scope/skeleton.json`)만 잰다(J137).

### 검증 명령(C안)

```bash
bash scripts/verify.sh                 # 전체: A(시험 · 커버리지 · 빌드 · 비밀 스캔) → B(스택 기동 · 3종 호출) → C(기기 한 턴) → 사후 단언
bash scripts/verify.sh --mode host     # Docker 없이(로컬 PostgreSQL 16 · java -jar · Next.js · Nginx 대역). NOT_VERIFIED(host) 줄이 남는다
bash scripts/verify.sh --engine        # 엔진 checkpoint용: 소스 지문이 같은 미리 만든 산출물로 B · C · 사후 단언만 110초 안에
bash scripts/verify.sh --skip-a        # 스켈레톤 경량(CI skeleton 기본): A1 ~ A4 회귀를 건너뛰고 산출물(A0) → B · C · 사후 단언. NOT_VERIFIED(skip-a) 줄이 남는다
bash scripts/verify.sh --help          # 단계 · 모드 · 종료 코드
```

- 종료 코드: 0 통과, 1 단언 실패(기한 초과 포함), 2 전제 누락(통과로 보고하지 않음), 64 인자 오류. 결과는 `build/verify/report.txt`,
  도구 출력은 `build/verify/logs/`에 남는다. 비밀 값은 둘 다에 쓰지 않는다.
- `--engine`은 backend · frontend · infra · tools · contracts · scripts 중 무엇이든 바뀌면 E0에서 1로 끝난다. 그때는 전체 실행을 먼저 한 번 돌린다.
- 실행 중에는 저장소를 고치지 않는다. 시작과 끝의 작업 트리가 다르면 T 단계가 1로 끝난다(SEC-18).
- 검증 스택의 구성 · 문제 해결은 `infra/RUNBOOK.md` 17절에 있다. 실공급자는 부르지 않는다(실공급자 한 턴은 11/2 측정).

### 비밀 · 도메인 규칙

- 계정 · 비밀번호 · API 키 · 토큰 값 · 실제 도메인은 코드 · 설정 · 시험 · 문서 · 커밋 · 로그 어디에도 쓰지 않는다. 저장소에는 환경 변수 ·
  매개변수 **이름**만 둔다(`POSTGRES_PASSWORD` · `DB_OWNER_PASSWORD` · `DB_CORE_PASSWORD` · `DB_REALTIME_PASSWORD` · `DB_BACKUP_PASSWORD` ·
  `INTERNAL_SERVICE_TOKEN` · `OPENAI_API_KEY` · `JEV_API_KEY` · `MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET` ·
  `GOOGLE_OAUTH_CLIENT_SECRET` · `GHCR_PULL_TOKEN` · `NEO4J_AUTH`). `OPENAI_API_KEY`(GPT-Live 음성 중계와 Luna 판정, core-api · realtime)는 demo 프로필의 realtime에서만 필수이고 `JEV_API_KEY`(제브, realtime)도 demo에서 필수다(U13: 키 · 주소 `JEV_BASE_URL` ·
  모델 `JEV_MODEL`이 없으면 demo realtime이 기동을 거부하므로 배포 전에 Parameter Store에 먼저 넣는다, `infra/RUNBOOK.md` 단계 4). 검증 · CI에서는 모두 빈 값(제브는 dev 프로필 기본 대역)이라 실제 공급자를 부르지 않는다(`contracts/ai-io.md` 10.7절).
- 서비스 도메인은 `SERVICE_HOST` 자리표시자다(J98). 시험 origin은 `https://mujoong.invalid`(단위 · 통합)와 `https://localhost:8443`(검증 스택)뿐이다(J108).
- 시험용 값은 실행 때 만든다: 검증 · CI는 `infra/ci/gen-env.sh`(`build/verify/env/`, umask 077, 실행 끝에 지움), Testcontainers 시험은
  `kr.mujoong.testsupport.secret.TestSecrets`(J127). demo 서버의 값은 AWS Parameter Store `/mujoong/demo/`에만 있다(`infra/RUNBOOK.md`).
- `docker compose config`는 env 파일 값을 펼쳐 출력하므로 `--quiet`로만 쓴다. 로그를 옮길 때는 사건 코드 · traceId · 건수만 옮긴다.
- 비밀 스캔은 검증 명령 A4(`scripts/verify/a-secrets.sh`)와 CI `secrets` 작업(gitleaks, `.gitleaks.toml`)이 함께 맡는다.
