# 빌드 지시 — U1 u1-walking-skeleton (3.6 Build and Test)

> 3.6 Build and Test Step 2 산출물 · 2026-10-11 · 단독 실행(`--single`) · 대화 언어 한국어 · 시험 전략 Standard
>
> 입력: `code-generation-plan.md`(## 저장소 구조 · ## 검증 명령(C안) · J132 · J133 · J145 · J146), `unit-test-instructions.md`(## 첫 시험 전에 돌릴 수 있는 명령 · ## 이 유닛 시험만 돌리는 명령), `code-summary.md`(## Step 1 판 표 · ## 검증 명령 결과), 저장소 `README.md`(개발 · 검증), `infra/RUNBOOK.md` 17절.
> 이 파일은 명령과 확인 기준만 적는다. 명령은 Step 9가 실행하고 결과는 `test-results.md`에 적는다. 이 파일의 '이 세션에서 확인' 표시는 2026-10-11 이 문서를 쓰면서 실제로 돌린 확인 명령(판 · 존재 확인)의 출력이며 빌드 · 시험 결과가 아니다.

## 1. 범위와 빌드 산출물

코드가 있는 유닛은 U1뿐이다(U2 ~ U7은 3.1 ~ 3.5 미시작). 빌드 단위는 저장소 루트의 실행 단위 디렉터리다(J97).

| 빌드 단위 | 내용 | 산출물(저장소 루트 기준) |
|---|---|---|
| backend | Gradle 멀티 프로젝트 `contract-model` · `core-api` · `realtime-activity` · `test-support`(시험 전용), 래퍼 8.14.3, Java 21 · Spring Boot 4.1.1 | `backend/core-api/build/libs/core-api.jar`, `backend/realtime-activity/build/libs/realtime-activity.jar`, 시험 결과 `backend/*/build/test-results/test/*.xml`, JaCoCo `backend/*/build/reports/jacoco/test/{html,jacocoTestReport.xml}` |
| frontend | Next.js 16.4.0 App Router 한 앱(`/parent` · `/device`), Node 22 | `frontend/.next/standalone/server.js` · `frontend/.next/BUILD_ID` · `frontend/.next/static/`, 커버리지 `frontend/coverage/coverage-summary.json` |
| tools | Node 22 ESM(가짜 공급자 · 기기 시뮬레이터 · C안 시나리오 · dev-edge). 빌드 단계 없음 | `tools/node_modules/` |
| 이미지 | `infra/ci/build-images.sh`가 호스트 산출물을 COPY해 만드는 7종(core-api · realtime · web · migrate · backup · infra · fake-provider) | 로컬 태그 `mujoong.local/mujoong-<이름>:verify`, 라벨 `org.opencontainers.image.revision` · `kr.mujoong.source-fingerprint` |

이미지 안에서는 Gradle · npm을 돌리지 않는다(샌드박스 빌드 규칙 R-03). 그래서 이미지 빌드는 반드시 backend · frontend 빌드 뒤에 하고, 산출물이 소스보다 오래되면 `build-images.sh`가 실패한다.

## 2. 전제 도구와 판

| 도구 | 요구 | 이 샌드박스(이 세션에서 확인) | 확인 명령 |
|---|---|---|---|
| JDK | 21 | OpenJDK 21.0.12.1. `JAVA_TOOL_OPTIONS`가 프록시 · 신뢰 저장소를 잡는다(첫 줄 `Picked up JAVA_TOOL_OPTIONS`는 정상) | `java -version` |
| Gradle | 래퍼 8.14.3(9.x 전환은 사람 결정, J139) | 래퍼 배포판 캐시 `~/.gradle/wrapper/dists/gradle-8.14.3-bin/`이 있다 | `cd backend && ./gradlew --no-daemon --version` |
| Node · npm | Node 22(`.nvmrc`) | v22.22.0 · npm 10.9.4 | `node --version && npm --version` |
| Docker · Compose | Engine + Compose v2.24.4 이상(`!override`, J136) | Engine 29.8.2 · Compose v5.6.0, 데몬 기동 상태 | `docker version --format '{{.Server.Version}}'`, `docker compose version`, `docker info >/dev/null && echo up` |
| PostgreSQL 16 바이너리 | DB 시험 `local` 백엔드(J132) · 검증 명령 host 모드(J133) | `/usr/lib/postgresql/16/bin`(`initdb` · `pg_ctl` · `postgres` · `psql` 등) | `ls /usr/lib/postgresql/16/bin/{initdb,pg_ctl,postgres,psql}` |
| shellcheck | 0.11.0(`infra-validate`의 shellcheck 검사) | `/usr/local/bin/shellcheck` 0.11.0 | `shellcheck --version` |
| 기타 | `jq` · `openssl` · `curl` · `python3` · `unzip` · `runuser` · `timeout` · `setsid` · `sha256sum` | 모두 있음 | `command -v jq openssl curl python3 unzip runuser timeout setsid sha256sum` |
| 고정 태그 기본 이미지 | Docker Hub 429 대비(J271) | 로컬에 `postgres:16.15` · `nginx:1.30.5-alpine` · `eclipse-temurin:21.0.12_8-jre-noble` · `node:22.22.3-alpine3.23` · `flyway/flyway:12.4.0` · `certbot/certbot:v5.8.0` · `node:22.22.0-bookworm-slim` · `testcontainers/ryuk:0.14.0`이 있다 | `docker images --format '{{.Repository}}:{{.Tag}}'` |

없는 것(이 세션에서 확인): `trivy` · `gitleaks` · `actionlint` 실행 파일(이미지 `aquasec/trivy:0.74.0` · `zricethezav/gitleaks:v8.30.1` · `rhysd/actionlint:1.7.12`는 로컬에 있다 — 쓰는 방법은 보안 담당의 `security-test-instructions.md`), 실기 브라우저(Chromium · Chrome) · Lighthouse · k6. 이 셋이 필요한 목표는 이 단계에서 돌릴 수 없다(`build-and-test-summary.md`의 목표 목록).

## 3. 환경 설정 — 이 샌드박스의 주의점

### 3.1 프록시 · TLS

- 바깥 HTTPS는 세션 프록시(`HTTPS_PROXY`, `127.0.0.1:<포트>`)를 지난다. CA 묶음은 `/root/.ccr/ca-bundle.crt`이고 시스템 신뢰 저장소 · JVM 신뢰 저장소(`JAVA_TOOL_OPTIONS`)에 이미 들어 있다.
- **TLS 검증을 끄지 않는다.** `curl -k` · `--insecure` · `NODE_TLS_REJECT_UNAUTHORIZED=0` · `-Dcom.sun.net.ssl.checkRevocation=false` 같은 우회를 쓰지 않는다. 인증서 오류가 나면 `/root/.ccr/README.md`의 절차를 따르고 `curl -sS "$HTTPS_PROXY/__agentproxy/status"`로 상태를 본다. 403 · 407은 출구 정책 거부이므로 다시 시도하거나 돌아가지 않고 보고한다.
- 루프백(검증 스택 `https://localhost:8443`)은 프록시로 보내지 않는다: `scripts/verify/lib.sh`가 `NO_PROXY=localhost,127.0.0.1,::1`을 두고 `curl`에 `--noproxy`와 자체 서명 CA(`build/verify/certs/ca.crt`)만 준다. 손으로 부를 때도 같은 형태(`curl --noproxy localhost,127.0.0.1 --cacert build/verify/certs/ca.crt …`)로 부른다.

### 3.2 Gradle (J145 · J146, 저장소 밖 설정)

- `downloads.gradle.org`는 출구 정책 403이라 래퍼 배포판을 받을 수 없다. 래퍼 캐시 `~/.gradle/wrapper/dists/gradle-8.14.3-bin/<해시>/gradle-8.14.3`이 이미 있어 `./gradlew`가 받지 않고 쓴다(J145). 캐시가 없어지면 설치판 8.14.3으로 같은 자리를 채운다. 저장소의 `backend/gradle/wrapper/gradle-wrapper.properties`(`distributionUrl` · `distributionSha256Sum`)는 고치지 않는다.
- `repo.maven.apache.org`가 공유 출구 IP에 429를 주므로 `~/.gradle/init.d/mujoong-central-mirror.gradle`이 Maven Central 요청을 Google의 공식 Maven Central 미러(`https://maven-central.storage-download.googleapis.com/maven2/`)로 돌린다(J146). 빌드 파일은 `mavenCentral()` 그대로이고 CI · 다른 PC에는 영향이 없다.
- Gradle은 항상 `--no-daemon`으로 돌린다(`unit-test-instructions.md` 규칙). 로그를 남길 때는 `--console=plain`을 붙인다.

### 3.3 Docker 데몬

- 확인: `docker info >/dev/null 2>&1 && echo up || echo down`.
- 꺼져 있으면: `nohup dockerd >/tmp/claude-0/-home-user-mujoong-aidlc/39fd3430-12f4-5241-8ae6-2725dd7b4530/scratchpad/dockerd.log 2>&1 &` 뒤 `docker info`가 성공할 때까지 기다린다(기한 60초, 0.5초 간격).
- 데몬을 막 띄운 직후 첫 검증 스택 기동은 느릴 수 있다. 3.5 기록(scratchpad `engine-dry.log`)에서 데몬 기동 직후 첫 `--engine` 실행이 B 단계 기한(94초)을 넘겨 실패했고 바로 다음 실행은 B 41초로 통과했다. 그래서 Step 9는 전체 실행(`bash scripts/verify.sh`)을 `--engine`보다 먼저 돌린다(전체 실행이 이미지 · 산출물도 다시 만든다). 기한을 늘리거나 단계를 건너뛰는 옵션은 없고 쓰지 않는다.
- 이전 실행이 남긴 스택 · 망은 `docker compose -p mujoong-verify down -v --remove-orphans -t 3`으로 지운다(`verify.sh`는 시작할 때 스스로 한다).

### 3.4 컨테이너 안 네트워크와 이미지 빌드

- 컨테이너 안 프로세스는 세션 프록시에 닿지 못하고 CA를 모른다. 앱 이미지는 호스트 산출물(jar · standalone)을 COPY하므로 빌드 중 네트워크가 필요 없다.
- 이 샌드박스에서는 `build-images.sh --extra-ca /root/.ccr/ca-bundle.crt`로 CA · 프록시 환경을 BuildKit 비밀로만 넘긴다(빌드 인자 · ENV 금지, R-03). `scripts/verify/b-stack.sh`는 CI가 아니고 이 파일이 있으면 자동으로 붙인다.
- 검증 스택(`mujoong-verify`)과 이미지 점검 ④(`mujoong-imgchk`)는 같은 서브넷(172.30.10.0/24 · 172.30.20.0/24)을 쓴다. 둘을 동시에 띄우지 않는다.

### 3.5 DB 시험 백엔드 (J132)

- `-Pmujoong.test.db=auto|docker|local`(기본 auto). Docker가 있고 `postgres:16.15`를 쓸 수 있으면 docker(Testcontainers), 아니면 local(호스트 PostgreSQL 16을 `runuser -u postgres`로 띄움).
- 어느 백엔드로 돌았는지는 시험 표준 출력의 `[MujoongPostgres] backend=<docker|local>` 줄로 확인한다(4.3의 수집 명령).
- root로 local 백엔드를 쓰면 저장소 경로의 상위 디렉터리를 `postgres` 사용자가 지나갈 수 있어야 한다. 저장소를 0700 디렉터리 아래로 복사해 돌리면 `MujoongPostgresTest`(local)가 실패한다(3.5 Step 49 기록).

### 3.6 비밀 값과 생성 파일

- 저장소 · 이 기록 디렉터리 · 로그 어디에도 비밀 값을 쓰지 않는다. 검증 · 시험 값은 실행 때 만든다: `infra/ci/gen-env.sh` → `build/verify/env/<서비스>.env`(umask 077, `verify.sh`가 끝에 지움), Testcontainers 값은 `kr.mujoong.testsupport.secret.TestSecrets`(J127).
- `docker compose … config`는 env 파일 값을 펼치므로 `--quiet`로만 쓴다. 펼친 결과가 필요하면 umask 077 임시 파일로만 받고 바로 지운다(SEC-02).
- 손으로 스택을 띄운 경우(`integration-test-instructions.md` IT-06)에는 끝에 `build/verify/env` · `build/verify/c` · `build/verify/certs` · `build/verify/nginx` · `build/verify/postgres`를 반드시 지운다(`build/verify/c/secrets.list`에 본 토큰 · 쿠키 값이 들어 있다).

### 3.7 작업 트리 불변 (SEC-18)

- `bash scripts/verify.sh`는 시작과 끝의 `git status --porcelain=v1 -uall` · `git diff --stat` · 내용 해시를 비교하고(T 단계), 실행 중 소스 지문이 바뀌면 F 단계가 실패한다. 감사 기록(`aidlc/**/audit/`)만 비교에서 빠진다.
- 그래서 **검증 명령이 도는 동안 저장소의 어떤 파일도 쓰지 않는다** — 이 기록 디렉터리(`construction/build-and-test/`)도 포함이다. `test-results.md`는 검증 명령이 끝난 뒤 쓴다.
- `build/`는 `.gitignore` 대상(`git check-ignore`로 확인)이라 결과 보관에 써도 T 단계에 걸리지 않는다.

### 3.8 결과 보관 위치

- Step 9의 원시 출력은 `build/b-and-t/`(무시 대상)에 둔다: `build/b-and-t/logs/<명령 ID>.log`, 검증 명령 보고 사본 `build/b-and-t/verify-<회차>-report.txt`, 측정 값 `build/b-and-t/measure/`.
- 비밀 값 · 전사 원문 · 쿠키 · 토큰은 이 폴더에도 옮기지 않는다. 검증 명령의 `report.txt` · 단계 줄은 값이 없는 형식이므로 그대로 복사해도 된다.

## 4. 의존성 설치 · 빌드 명령

모든 명령은 저장소 루트(`/home/user/mujoong-aidlc`)에서 시작한다. `cd`가 든 명령은 하위 셸로 돌리거나 끝난 뒤 루트로 돌아온다.

### 4.1 의존성 설치

| ID | 명령 | 확인 기준 |
|---|---|---|
| B-00a | `cd frontend && npm ci` | 종료 코드 0, `frontend/node_modules/.bin/next` · `vitest` 존재. `package-lock.json`이 바뀌지 않음(`git status`) |
| B-00b | `cd tools && npm ci` | 종료 코드 0, `tools/node_modules/ws/package.json` 존재 |
| B-00c | `cd backend && ./gradlew --no-daemon :contract-model:dependencies :core-api:dependencies :realtime-activity:dependencies :test-support:dependencies --configuration testRuntimeClasspath` | 종료 코드 0, 출력에 `FAILED` 0줄(시험 의존성 · `junit-platform-launcher` 해석) |

B-00a · B-00b · B-00c는 `unit-test-instructions.md`의 전제 명령과 글자가 같다. Step 9는 같은 명령을 한 번만 돈다.

### 4.2 빌드

| ID | 명령 | 하는 일 |
|---|---|---|
| B-01 | `cd backend && ./gradlew --no-daemon --console=plain testClasses :core-api:bootJar :realtime-activity:bootJar verifyBootJarHasNoTestClasses` | 네 모듈 운영 · 시험 소스 컴파일, bootJar 두 개, 운영 jar에 시험 클래스 · 시험 설정 · 시험 라이브러리 0건 확인(SD-25 · RD-23) |
| B-02 | `cd frontend && npx --no-install eslint . && npx --no-install tsc --noEmit` | 경계 lint(NFR11.1 · SD-19) · 타입 검사 |
| B-03 | `cd frontend && NEXT_TELEMETRY_DISABLED=1 npx --no-install next build && rm -rf .next/standalone/.next/static && cp -a .next/static .next/standalone/.next/static` | 프로덕션 빌드(Turbopack) + standalone에 정적 자원 복사(`scripts/verify/a-checks.sh frontend`와 같은 처리. `frontend/public`이 생기면 같은 방식으로 복사) |
| B-04 | `bash infra/ci/build-images.sh --extra-ca /root/.ccr/ca-bundle.crt` | 이미지 7종을 `mujoong.local/mujoong-*:verify`로 빌드, 라벨에 HEAD · 소스 지문. 빌드 앞뒤 지문이 같을 때만 성공 |

- B-04는 B-01 · B-03(또는 검증 명령 A 단계)이 산출물을 만든 뒤에만 돈다. 검증 스택이 떠 있지 않을 때 돌린다.
- 검증 명령 전체 실행(`integration-test-instructions.md` IT-02)이 A1 · A2에서 B-01 · B-02 · B-03에 해당하는 일(+ 시험 · 커버리지)과 검증 스택 이미지 5종 빌드를 다시 한다. B-01 ~ B-03은 빌드 자체의 성공 · 실패를 따로 기록하려고 먼저 한 번 돈다.

### 4.3 빌드 확인

| ID | 명령 | 기대 |
|---|---|---|
| BV-1 | `ls -l backend/core-api/build/libs/core-api.jar backend/realtime-activity/build/libs/realtime-activity.jar` | 두 파일이 있다 |
| BV-2 | B-01 로그에서 `grep -c 'verifyBootJarHasNoTestClasses: .* 시험 항목 0건' build/b-and-t/logs/B-01.log` | 2(앱 두 개) |
| BV-3 | `ls -l frontend/.next/standalone/server.js frontend/.next/BUILD_ID frontend/.next/standalone/.next/static` | 셋 다 있다 |
| BV-4 | `bash infra/ci/source-fingerprint.sh` 와 `for n in core-api realtime web migrate backup infra fake-provider; do docker image inspect mujoong.local/mujoong-$n:verify --format "$n {{index .Config.Labels \"kr.mujoong.source-fingerprint\"}}"; done` | 이미지 7종의 라벨 값이 지금 소스 지문과 모두 같다 |
| BV-5 | `git status --porcelain=v1` | 빌드 전과 같다(감사 기록 조각만 바뀔 수 있음). 빌드가 추적 파일을 고치지 않는다 |

### 4.4 시험 결과 수집 명령 (Step 9가 유닛 명령 뒤에 쓴다)

백엔드 JUnit XML 합계 · 클래스별 시험 수(Standard 분량 확인용) · DB 백엔드:

```bash
python3 -I - <<'PY'
import glob, xml.etree.ElementTree as ET
tot = {}
small = []
for f in sorted(glob.glob('backend/*/build/test-results/test/*.xml')):
    mod = f.split('/')[1]
    s = ET.parse(f).getroot()
    t, fl, er, sk = (int(s.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped'))
    a = tot.setdefault(mod, [0, 0, 0, 0, 0]); a[0] += t; a[1] += fl; a[2] += er; a[3] += sk; a[4] += 1
    if t < 5:
        small.append((mod, s.get('name'), t))
for m, (t, fl, er, sk, n) in sorted(tot.items()):
    print(f'{m}: classes={n} tests={t} failures={fl} errors={er} skipped={sk}')
print('classes_with_fewer_than_5_tests:', len(small))
for m, n, t in small:
    print(f'  {m} {n} {t}')
PY
grep -ho '\[MujoongPostgres\] backend=[a-z]*' backend/*/build/test-results/test/*.xml | sort | uniq -c
```

JaCoCo 줄 비율(모듈별, 보고서 최상위 LINE 카운터):

```bash
python3 -I - <<'PY'
import xml.etree.ElementTree as ET
for m in ('contract-model', 'core-api', 'realtime-activity', 'test-support'):
    p = f'backend/{m}/build/reports/jacoco/test/jacocoTestReport.xml'
    try:
        r = ET.parse(p).getroot()
    except FileNotFoundError:
        print(m, 'no report'); continue
    c = {e.get('type'): e for e in r.findall('counter')}
    l = c['LINE']; mi, co = int(l.get('missed')), int(l.get('covered'))
    print(f'{m}: LINE {co}/{mi + co} = {100 * co / (mi + co):.2f}%')
PY
```

프런트 커버리지: `jq '.total.lines' frontend/coverage/coverage-summary.json`

- 수집 명령은 측정만 한다. JaCoCo · Vitest의 하한 판정은 각 도구 설정(`jacocoTestCoverageVerification` LINE `0.80` · `thresholds.lines: 80`)이 하고, 그 설정은 바꾸지 않는다.
- Gradle 부분 명령(`--tests` · `-PexcludeTags=db`)은 같은 `test-results` 디렉터리를 덮어쓴다. 합계는 전체 명령 뒤에 수집하고, 부분 명령은 전체 명령보다 먼저 돈다(`build-and-test-summary.md` ## Step 9 실행 순서). 검증 명령 A1(`clean check`)도 이 디렉터리를 지우므로 유닛 명령의 증거는 검증 명령 전에 `build/b-and-t/`로 복사한다.

## 5. 문제 해결

| 증상 | 원인 · 조치 |
|---|---|
| `./gradlew`가 배포판을 받으려다 403 | 래퍼 캐시가 없다(J145). 3.2의 캐시 자리를 확인한다. `distributionUrl`은 고치지 않는다 |
| 의존성 해석 429 · 시간 초과 | `~/.gradle/init.d/mujoong-central-mirror.gradle`이 있는지 확인(J146). 프록시 상태를 `curl -sS "$HTTPS_PROXY/__agentproxy/status"`로 본다 |
| PKIX · `certificate verify failed` | 도구가 CA 설정을 읽지 않는다. `/root/.ccr/README.md`의 CA 지정 방법을 따른다. 검증을 끄지 않는다 |
| `npm ci` 실패 · 잠금 파일 변경 | Node 22 · npm 10인지 확인(`npm ci`는 npm 10으로 확인된 잠금 파일, J155). `npm install`로 바꾸지 않는다 |
| `docker info` 실패 | 3.3대로 데몬을 띄운다. 띄운 직후 첫 스택 기동은 느릴 수 있다 |
| `docker pull` 429 | 고정 태그가 로컬에 있으면 그대로 쓴다(auto 모드는 로컬 이미지를 pull 성공으로 본다). 다른 태그로 바꾸지 않는다 |
| `build-images.sh` 종료 코드 1 '낡은 산출물' | jar · standalone이 소스보다 오래됐다. B-01 · B-03을 다시 돈 뒤 빌드한다 |
| `E0 FAIL`(`--engine`) | 미리 만든 이미지 · 산출물의 소스 지문이 다르다. 전체 실행을 먼저 돈다. 바뀐 파일은 `bash infra/ci/source-fingerprint.sh --manifest`로 찾는다 |
| `F FAIL` 또는 `T FAIL`만 | 실행 중 누군가 저장소를 고쳤다(3.7). 저장소가 조용할 때 다시 돈다 |
| A1의 `MujoongPostgresTest`(local)만 실패 | 3.5 참조. 저장소를 0700 디렉터리 아래에서 돌리지 않는다 |
| 8443 포트 사용 중 · `Pool overlaps` | 남은 스택 · 망이 있다. `docker compose -p mujoong-verify down -v --remove-orphans -t 3`, 이미지 점검 스택이면 `docker compose -p mujoong-imgchk down -v --remove-orphans` |
| `B4.host`가 000(host 모드) | dev-edge가 모르는 Host · SNI를 연결 끊기로 거절한다. 통과로 센다(`infra/RUNBOOK.md` 17.3) |
