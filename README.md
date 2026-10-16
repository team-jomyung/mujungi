# mujoong-aidlc

무중(6~12세 아이 화용 능력 지원 AI 음성 대화 서비스)을 AI-DLC(awslabs/aidlc-workflows 2.0)로 돌려 보는 저장소. 기준 문서는 화면 설계 v1.5j(2026-10-08).

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
스코프는 `classic`, 모드는 `Edit File`. 문서끼리 다르면 화면 설계가 맞다(개발전환_점검표 2절).

## 클라우드 세션(claude.ai/code)에서 이어가기
클라우드 세션은 플랜 한도보다 클라우드 세션 크레딧을 먼저 쓴다. 작업 기록은 전부 git에 있으므로 어느 기계에서든 이어진다.
1. claude.ai/code 에서 이 GitHub 레포(baikjunha/mujoong-aidlc)로 세션을 연다.
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
| `frontend/` | Next.js App Router 한 앱(`/parent` · `/device`, Node 22) |
| `contracts/` | 앱 사이 · 클라이언트 계약 정본(OpenAPI · AsyncAPI · JSON Schema) |
| `infra/` | Compose · Nginx · PostgreSQL 초기화 · 배포 스크립트 |
| `tools/` | 가짜 공급자 · 기기 시뮬레이터 · 끝-끝 시나리오(Node 22 ESM, 앱 이미지 밖) |
| `scripts/` | 검증 명령(`scripts/verify.sh`)과 단계 스크립트 |

전제: JDK 21, Node 22(`.nvmrc`), Docker(Compose v2.24.4 이상) 또는 호스트 PostgreSQL 16. 비밀 값(계정 · 비밀번호 · API 키 · 토큰 · 실제 도메인)은 저장소 어디에도 두지 않고 환경 변수 이름만 쓴다. `.env*` · 인증서 · 키 파일 · `build/`는 `.gitignore`가 막는다. 유닛 시험 명령은 각 유닛의 `code-generation/unit-test-instructions.md`에 있다.

### 로컬 기동(dev)

앱 셋은 IDE나 호스트 프로세스로 돌리고, DB만 Compose로 띄운다. 비밀 값은 실행 때 `build/dev/env/`에 생기고 저장소에는 없다(J127).

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

### 유닛 시험 명령(U1)

정확한 목록은 `aidlc/spaces/default/intents/261008-mujoong-service/construction/u1-walking-skeleton/code-generation/unit-test-instructions.md`가
정본이다. 자주 쓰는 것만 옮긴다(각 줄을 저장소 루트에서 따로 실행한다).

```bash
cd backend && ./gradlew --no-daemon :contract-model:test :core-api:test :realtime-activity:test :test-support:test -PincludeTags=u1
cd backend && ./gradlew --no-daemon :contract-model:test :core-api:test :realtime-activity:test \
  :contract-model:jacocoTestCoverageVerification :core-api:jacocoTestCoverageVerification \
  :realtime-activity:jacocoTestCoverageVerification -PincludeTags=u1 -PcoverageScope=u1
cd frontend && npm ci && MUJOONG_COVERAGE_SCOPE=u1 npx --no-install vitest run tests/u1 --coverage
cd tools && npm ci && npx --no-install vitest run tests/u1
```

- PostgreSQL이 필요한 시험(`@Tag("db")`)은 Docker(Testcontainers)나 호스트 PostgreSQL 16(`/usr/lib/postgresql/16/bin`)으로 돈다.
  `-Pmujoong.test.db=auto|docker|local`로 고르고 기본은 auto다(J132). root로 local을 쓰면 저장소 경로를 `postgres` 사용자가 지나갈 수 있어야 한다.
- 줄 커버리지 하한은 80%(백엔드 모듈별 JaCoCo · 프런트 `src/**` Vitest)이고 낮추지 않는다. `-PcoverageScope=u1` ·
  `MUJOONG_COVERAGE_SCOPE=u1`은 U1이 만든 파일(`backend/coverage-scope/u1.txt` · `frontend/coverage-scope/u1.json`)만 잰다(J137).

### 검증 명령(C안)

```bash
bash scripts/verify.sh                 # 전체: A(시험 · 커버리지 · 빌드 · 비밀 스캔) → B(스택 기동 · 3종 호출) → C(기기 한 턴) → 사후 단언
bash scripts/verify.sh --mode host     # Docker 없이(로컬 PostgreSQL 16 · java -jar · Next.js · Nginx 대역). NOT_VERIFIED(host) 줄이 남는다
bash scripts/verify.sh --engine        # 엔진 checkpoint용: 소스 지문이 같은 미리 만든 산출물로 B · C · 사후 단언만 110초 안에
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
  `INTERNAL_SERVICE_TOKEN` · `PROVIDER_API_KEY` · `OPENAI_API_KEY` · `JEV_API_KEY` · `MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET` ·
  `GOOGLE_OAUTH_CLIENT_SECRET` · `GHCR_PULL_TOKEN` · `NEO4J_AUTH`). `OPENAI_API_KEY`(Luna, core-api · realtime)와 `JEV_API_KEY`(제브, realtime)는
  선택이고, 검증 · CI에서는 빈 값이라 실제 공급자를 부르지 않는다(`contracts/ai-io.md` 10.7절).
- 서비스 도메인은 `SERVICE_HOST` 자리표시자다(J98). 시험 origin은 `https://mujoong.invalid`(단위 · 통합)와 `https://localhost:8443`(검증 스택)뿐이다(J108).
- 시험용 값은 실행 때 만든다: 검증 · CI는 `infra/ci/gen-env.sh`(`build/verify/env/`, umask 077, 실행 끝에 지움), Testcontainers 시험은
  `kr.mujoong.testsupport.secret.TestSecrets`(J127). demo 서버의 값은 AWS Parameter Store `/mujoong/demo/`에만 있다(`infra/RUNBOOK.md`).
- `docker compose config`는 env 파일 값을 펼쳐 출력하므로 `--quiet`로만 쓴다. 로그를 옮길 때는 사건 코드 · traceId · 건수만 옮긴다.
- 비밀 스캔은 검증 명령 A4(`scripts/verify/a-secrets.sh`)와 CI `secrets` 작업(gitleaks, `.gitleaks.toml`)이 함께 맡는다.
