# 보안 시험 지침 — U1 u1-walking-skeleton (3.6 Build and Test)

> 3.6 Build and Test Step 3 ~ 7 산출물(보안) · 2026-10-11 · 단독 실행(`--single`) · 대화 언어 한국어 · 시험 전략 Standard · 작성: 보안 담당(DevSecOps, 지원)
>
> 입력: `nfr-requirements/security-requirements.md`(NFR1.1 ~ NFR12.4 24개 · ## 4 시험 방법), `nfr-design/security-design.md`(SD-1 ~ SD-25 · ## 3 수치 · ## 4 시험 설계), `code-generation-plan.md`(## Testing Contract · ## 시험 전략과 커버리지의 3.6 넘김 목록 · ## 검증 명령(C안)), `unit-test-instructions.md`(시험 틀 · 태그 · 대역 · 시험 데이터), `code-summary.md`(## 알려진 틈 · ## 사람이 정할 것 · J274 · J277), `traceability.json`(NFR1.3 · NFR2.1 · NFR9.13이 Deferred → 3.6), `source-manifest.json`, `infrastructure-design/cicd-pipeline.md`(CD-1 ~ CD-11), 3.5 개발 보고(`report-g3-i5.md` 검증 명령 결과 · `report-g3-i6.md` 요약 · 추적 · `report-g3-i1.md` · `report-g3-i3.md` · `report-g3-i4.md` 이미지 · CI · 검증 스크립트), 짝 문서 `build-instructions.md`.
>
> 이 파일은 명령 · 기대값 · 판정 규칙만 적는다. 명령은 Step 9가 실행하고 결과는 `test-results.md`와 `build-and-test-summary.md`의 목표 판정표에 적는다. '작성 중 확인'이라고 적은 것은 이 문서를 쓰며 실제로 돌린 실행 가능성 확인(도구 · 이미지 · 접속 · 시험 방법)의 출력이며 보안 시험 결과가 아니다(16절). 명령 ID는 `ST-nn`이다(코드에 이미 있는 `SEC-nn` 단언 이름과 겹치지 않게).

## 1. 범위 · 기준 · 판정 규칙

**범위.** 코드가 있는 유닛은 U1뿐이다(U2 ~ U7은 3.1 ~ 3.5를 하지 않았다, 사람 지시). 보안 목표는 `security-requirements.md` §1의 24개 NFR(측정 기준이 둘 이상의 독립 부분이면 하위 목표로 나눔, 3절)과 `security-design.md` ## 4의 시험 설계 행(SD-n, 4절)이다. NFR9.15(로그 필드)는 observability 문서 소유라 이 파일의 목표가 아니지만 NFR9.8 · NFR9.14와 증거를 공유한다.

**Standard 전략에서 이 파일을 만드는 이유.** 단계 정의는 보안 시험 지침을 Comprehensive의 기본 생성물로 두지만, U1에 보안 NFR이 있고 Testing Contract의 `strategy_volume`("Add E2E, performance, or security tests when requirements demand them")과 3.5 계획의 3.6 넘김 목록(SD-2 브라우저 CSP · SD-3 중 B1 밖 Nginx 시험 · SD-16 의존성 관문 실측 · SD-19 문구 · SD-21 삭제 지휘)이 Build and Test에 보안 시험을 요구한다.

**지키는 규칙.**
- 애플리케이션 소스(`backend` · `frontend` · `tools` · `infra` · `scripts` · `contracts` · `.github` · `ci`), 3.5 산출물(`code-generation/` 아래), `aidlc-state.md`, `.claude/`, `knowledge/documents/`는 고치지 않는다. 보안 시험을 위해 만드는 도움 파일은 저장소 밖(scratchpad) 또는 무시 대상 `build/b-and-t/security/`에만 둔다.
- 측정 목표를 낮추거나 끄지 않는다: 예외 목록 · `.gitleaks.toml` 허용 목록에 항목을 더하지 않고, Trivy에 `--ignore-unfixed`를 더하지 않으며(백엔드 관문은 원래 없음, J274), 검증 명령의 단계를 건너뛰거나 기한을 늘리지 않는다.
- 비밀 값을 어떤 파일에도 쓰지 않는다. 출력은 건수 · 경로 · 상태 코드 · 권고 id만 남긴다(gitleaks `--redact=100`, 비밀 목록 대조는 일치 건수만).
- 실행 결과를 지어내지 않는다. 목표 판정의 근거는 Step 9에서 실제로 돈 명령과 그 출력뿐이다.

**판정 규칙(목표마다 하나).**
- `Met`: 그 목표의 증거 열에 적은 명령 · 시험이 이번 실행에서 모두 돌았고 통과했으며 기대값이 관측됐다.
- `Not Met`: 증거 중 하나라도 돌아서 실패했거나 관측값이 기대값과 다르다.
- `Unverified`: 증거 중 하나라도 이 환경에서 돌릴 수 없었다(도구 · 환경 · 시험 부재). 사유와 맡을 뒤 단계를 적는다. 뒤 단계로 넘긴 목표도 이 단계에서는 `Unverified`이고 성공 결과에 보태지 않는다. 실행 계획(`aidlc-state.md`)에 그 검사를 맡을 단계가 없으면 '소유 단계 없음'으로 적는다.
- '부분 Met'은 없다. 그래서 측정 기준이 여러 부분으로 된 NFR은 3절에서 하위 목표(`NFR9.1-a` 등)로 나눴다.

**실패 처리(`--single`).** 사다리 1단(이 단계 안의 시험 설정 · 스크립트 · 환경 고침, 최대 2회)과 2단(원인이 생성 소스 · 시험 · 3.5의 선택에 있으면 분류 + 해결 후보의 영향 추정)까지만 한다. 코드 생성으로 되돌아가는 점프(3 · 4단)는 하지 않는다. 알려진 틈의 2단 사전 분석은 15절에 있다.

## 2. 시험 틀과 환경 준비

### 2.1 도구

| 도구 | 상태(작성 중 확인, 2026-10-11) | 쓰는 곳 |
|---|---|---|
| JDK 21 · Gradle 래퍼 8.14.3 · Node 22 · npm 10 | 있음(`build-instructions.md` 2절) | 백엔드 · 프런트 · tools 시험, npm audit |
| Docker 29.8.2 · Compose v5.6.0 | 데몬 기동 상태 | 검증 스택, 이미지 점검, gitleaks · Trivy · actionlint 이미지 |
| `zricethezav/gitleaks:v8.30.1` | 로컬 이미지, RepoDigest `sha256:c00b6bd0…bbb7f` = `ci.yml`의 `GITLEAKS_IMAGE`와 같음, `version` 종료 0 | ST-16 |
| `aquasec/trivy:0.74.0` | 로컬 이미지, RepoDigest `sha256:62b1e65e…1969` = `ci.yml`의 `TRIVY_IMAGE`와 같음. 취약점 DB · Java DB를 프록시 경유로 받아 둠(캐시 `<SP>/dso-trivy-cache`, 2.9G) | ST-19 · ST-20 |
| `rhysd/actionlint:1.7.12` | 로컬 이미지(3.5 Step 50이 같은 이미지로 rc 0 기록) | ST-22 |
| shellcheck 0.11.0 · jq · openssl 3.0.13 · curl 8.5.0 · python3 | 있음 | infra-validate, 보충 점검, 관문 스크립트 |
| 실행 파일 `gitleaks` · `trivy` · `semgrep` · OWASP ZAP · nmap · testssl | 없음 | gitleaks · Trivy는 위 이미지로 대신한다. SAST(taint) · DAST 스캐너는 10 · 11절 |
| 실기 브라우저 · 실제 기기 · AWS · 도메인 · Let's Encrypt · 실공급자 키 | 없음 | 13절(뒤로 넘기는 것) |

### 2.2 공통 변수 (ST-06 이하가 쓴다)

```bash
cd /home/user/mujoong-aidlc
SP=/tmp/claude-0/-home-user-mujoong-aidlc/39fd3430-12f4-5241-8ae6-2725dd7b4530/scratchpad
OUT=build/b-and-t/security          # .gitignore의 build/ 아래(검증 명령 T 단계에 걸리지 않음)
mkdir -p "$OUT"
B=https://localhost:8443             # 검증 스택 Nginx(compose.ci.yml: 127.0.0.1:8443 → 443, SERVICE_HOST=localhost)
cid() { docker ps -q --filter label=com.docker.compose.project=mujoong-verify --filter "label=com.docker.compose.service=$1" | head -n 1; }
```

### 2.3 순서 · 충돌 규칙

- 검증 스택(`mujoong-verify`)과 이미지 점검 ④(`mujoong-imgchk`)는 같은 서브넷(172.30.10.0/24 · 172.30.20.0/24)을 쓴다. ST-20은 ST-14(검증 스택 정리) 뒤에만 돈다.
- `bash scripts/verify.sh` 두 개를 동시에 돌리지 않는다(시작할 때 서로의 스택을 지운다). Gradle도 같은 저장소에서 동시에 돌리지 않는다.
- 검증 명령이 도는 동안 저장소 파일을 쓰지 않는다(T 단계 · F 단계, `build-instructions.md` 3.7). `test-results.md`는 검증 명령이 끝난 뒤 쓴다.
- ST-19는 ST-02의 A1이 만든 bootJar를, ST-17은 A2가 만든 `frontend/.next/static`을 본다. 그래서 ST-02 뒤에 돈다.

### 2.4 시험 데이터 · 비밀 취급

- 시험 비밀은 실행 때 만든다: Testcontainers는 `kr.mujoong.testsupport.secret.TestSecrets`, 검증 스택은 `infra/ci/gen-env.sh` → `build/verify/env/`(umask 077, 끝에 지움). `KEEP=1`이어도 env · 비밀 목록 · 인증서는 지워진다(스택만 남음).
- origin: 단위 · 통합 시험 `https://mujoong.invalid`, 검증 스택 `https://localhost:8443`, 거부 대조 `https://evil.invalid`(J108). 시드 보호자 · 아이 · 승인 문구는 합성 값이고 DB 표지는 `mujoong_db.data_class = synthetic`이다.
- ST-15의 버림 PostgreSQL 비밀번호는 하위 셸 변수에만 두고 `docker run -e 이름`(값 없이 이름만)으로 넘긴다. ST-10의 bootstrap 쿠키는 curl 한 번의 메모리 쿠키 엔진(`-b ''`)에만 있고 파일에 쓰지 않는다.
- 결과 원본은 `build/b-and-t/security/`에 둔다. 헤더 · 상태 코드 · 건수 · 권고 id만 들어간다.

### 2.5 프록시 · TLS

- TLS 검증을 끄지 않는다(`-k` · `--insecure` 금지). 루프백 요청은 `--noproxy '*'`로 프록시를 거치지 않게 한다.
- 컨테이너 안 Trivy는 세션 프록시(127.0.0.1)에 닿도록 `--network host`로 돌리고, `-e HTTPS_PROXY`와 `SSL_CERT_FILE=/ccr/ca-bundle.crt`(호스트 `/root/.ccr/ca-bundle.crt`를 읽기 전용으로 붙임)를 준다(작성 중 확인: 이 방식으로 `mirror.gcr.io`의 DB 두 개를 검증 켠 채 받음).
- 검증 스택의 자체 서명 CA(`build/verify/certs/ca.crt`)는 `KEEP=1` 실행 끝에도 지워진다. 그때는 ST-06이 서버가 내놓은 인증서를 그대로 신뢰 앵커로 쓴다(curl의 부분 체인 허용). 검증은 켜진 채이고, 다른 인증서를 앵커로 주면 curl이 (60)으로 실패하는 것을 작성 중 로컬 대역으로 확인했다(16절).

## 3. 보안 목표 목록(NFR) — 기대값 · 증거 · 실행 가능 여부

증거 열의 `ST-nn`은 14절 명령이고, 시험 클래스 이름은 ST-03(백엔드) · ST-04(프런트 · tools)가 결과를 모은다. '이 환경'은 이번 Build and Test에서 돌릴 수 있는지다.

| 목표 ID | 출처 | 기대값 | 증거 | 이 환경 | 뒤 단계 · 비고 |
|---|---|---|---|---|---|
| NFR1.1 | §1 · SD-17 | 게이트를 거치지 않은 소리 재생 0, 폐기 구간 `approved_for_display` false · 12 미노출, OUTPUT_BEGIN 없는 0x02 폐기, 취소 · CLOSING 뒤 늦은 출력 0 | ST-03(`OutputGateTest` · `SessionLifecycleIT`), ST-04(`player.test.ts` · `device-session.test.ts`), ST-02(C9 · C10 · C11) | 가능 | — |
| NFR1.2 | §1 · SD-18 | 계약 9절 category 7개 각 1항목 이상(비거나 파일이 깨지면 Realtime 기동 거부), 항목 × (원형 · 조사 · 띄어쓰기)마다 폐기 + WAITING_FILLER, 일상 문장 통과 | ST-03(`SeedDictionaryTest` · `SeedDictionaryFileTest` · `RealtimeStartupChecksTest` · `OutputGateTest` · `SessionLifecycleIT`), ST-02(C10) | 가능 | SCORE 시드는 사람 결정 12로 `비정상`(`점수` · `정상이에요` · `정상입니다` 미포함을 `SeedDictionaryFileTest`가 고정) — 결함 아님 |
| NFR1.3 | §1 · SD-19 | U1 화면 8곳(D0 · T19 · 04 · 05a · 08 · 12 · P1 · 17) · 오류 code 문구에 진단 · 점수 · 의도 · 감정 단정 0, 화면 글 출처는 문구표 · mockups 9절 · `TEMP_COPY`뿐 | ST-24(어휘 grep + 체크리스트), ST-04(`copy.test.ts`: `codeToCopy` ↔ `contracts/errors.md`, '(문구 미정)' 0) | 가능(수동 검토) | 3.5 Deferred → 3.6 |
| NFR2.1 | §1 · SD-19 | 재촉 · 만류 · 보상 · 완료율 문구 0, 아이 기기 실패(429 · 503 · 닫기 코드 · 시한 FAILED) 때 화면 유지 + 조용한 5초 재시도, 승인 문구 2건에 만류 · 재촉 없음 | ST-24, ST-04(`device-machine` · `device-app` · `d0-view` · `t19-view`) | 가능 | 3.5 Deferred → 3.6 |
| NFR3.1 | §1 · SD-20 | 공급자 요청 = 지시문 + childContext(displayName · age · interests) + 음성 프레임, 08 원문 · 이메일 · id 0 | ST-03(`ProviderRequestWhitelistIT` · `SessionPackageIT` · `SessionPackageBuilderTest` · `ContractConformanceIT`) | 가능 | — |
| NFR3.2 | §1 · SD-20 | 요청 타입에 판정 기준 · 보호자 입력 · 이전 판정 필드 없음, 자유 `Map` 입구 없음, 팩토리 한 곳에서만 생성 | ST-03(`ProviderRequestWhitelistIT` · `RealtimeArchTest`) | 가능 | U5가 같은 시험 확장 |
| NFR9.1-a | NFR9.1 · SD-1 | 443은 TLS 1.2 · 1.3만 협상, 1.0 · 1.1은 서버가 거절 | ST-07, ST-21(infra-validate `ssl_protocols TLSv1.2 TLSv1.3;`) | 가능(검증 스택 Nginx = 운영 템플릿) | 공개 도메인 재확인은 4.3 |
| NFR9.1-b | NFR9.1 · SD-1 · SD ## 3 | `Strict-Transport-Security: max-age=15552000`(includeSubDomains · preload 없음)이 오류를 포함한 모든 응답에 정확히 하나 | ST-08 | 가능 | — |
| NFR9.1-c | NFR9.1 · SD-1 · SD-3 | 80: 서비스 Host는 ACME 경로만 응답하고 나머지는 `301 https://<고정 호스트>$request_uri`, 모르는 Host는 444. 443: 모르는 SNI는 핸드셰이크 거절, 모르는 Host는 처리하지 않음(2xx · 3xx 아님, J257) | ST-09, ST-02(B4.sni · B4.host), ST-21(`return 444;` · `ssl_reject_handshake on;`) | 가능 | 검증 스택은 80을 호스트에 게시하지 않아 nginx 컨테이너의 망 안에서 본다 |
| NFR9.1-d | NFR9.1 | 인증서 자동 갱신, 만료 30일 전 갱신 로그 | `certbot renew --dry-run`과 갱신 로그(실도메인) | 불가(도메인 · Let's Encrypt 없음) | 4.3 deployment-execution(J98 도메인 결정 뒤). 이 단계 `Unverified` |
| NFR9.1-e | NFR9.1 · SD-1 · 2.1 L0 | 공개 IP 바깥 스캔에서 80 · 443만 열림, 80은 301만 | 실서버 바깥 스캔, `deploy.yml` S5b, `infra/bin/smoke` 8-3 | 불가(EC2 없음) | 4.2 environment-provisioning(보안 그룹) · 4.3. `Unverified`. 로컬 보조 근거: ST-02 B1.ports, ST-21 불변식 1 |
| NFR9.2 | §1 · SD-5 | MUJOONG_SESSION HttpOnly · Secure · SameSite=Lax · Path=/ · Domain 없음 · Max-Age ≤ 유휴 30일(넘으면 기동 실패), 만료 세션 401, 시험 로그인 때 세션 ID 교체 | ST-03(`CookieAttributesTest` · `GuardianApiIT` · `SessionJdbcConfigIT` · `CorePropertiesValidationTest`), ST-02(C2) | 가능 | 로그아웃 경로는 U2(U1에 엔드포인트 없음) — 측정 기준 밖 |
| NFR9.3 | §1 · SD-4 · SD-6 | CSRF 면제는 정확한 경로뿐(U1은 4개: `POST /api/dev/login` · `POST /api/device/bootstrap` · `POST /api/device/bootstrap/claim` · `/internal/**`), 그 밖 변경 요청은 토큰 없음 · 틀림 → 403 CSRF_TOKEN_INVALID(register 포함), `/api/device/**` 통째 면제 없음, 허용 밖 Origin 403, 익명 변경 요청은 CSRF 전에 401 | ST-03(`SecurityChainIT`), ST-02(C3), ST-04(`api-client.test.ts` · `dev-login.test.tsx`) | 가능 | 구글 교환 면제는 U2 |
| NFR9.4 | §1 · SD-7 | bootstrap 비밀 128비트 이상 · 기기 토큰 256비트, DB에는 SHA-256 해시만, 상수 시간 비교, bootstrap 쿠키 Strict · Path=/api/device · 24시간, 기기 쿠키 Strict · Path=/ · 400일, WSS 허용 밖 Origin(없음 · null 포함) 403, 미등록 · 해제 · 회전 토큰 4401, Core 무응답 1013, 첫 WSS 인증 뒤 claim 410 | ST-03(`DeviceSecretsTest` · `SecretCompareTest` · `CookieAttributesTest` · `DeviceBootstrapApiIT` · `CoreInternalApiIT` · `DeviceGatewayIT` · `RealtimeMessagingIT`), ST-02(B4.ws · C1 · C4 · C5) | 가능 | 기기 토큰 서버 만료 없음은 사람 결정(S-13 · H-3) |
| NFR9.5 | §1 · SD-9 | 다른 보호자의 id는 없는 id와 같은 404(본문 · 헤더 동일), 세션 없음 401 | ST-03(`GuardianApiIT` · `ContractConformanceIT`), ST-23(보호자 경로가 `findOwned`만 쓰는지) | 가능 | SD-9의 ArchUnit 규칙은 미구현 — 이 목표의 측정 기준 밖이라 SD-9-b로 따로 판정(4절 · 15절 G1) |
| NFR9.6 | §1 · SD-3 · SD-8 | 바깥 `/internal/**` 403(원시 URI 우회 `..;` · `%2e` · `%2f` · `%5c` 400), 내부 토큰 없음 · 틀림 · 복수 401(두 앱), 토큰이 있어도 보호자 · 기기 권한 없음, 발신 인터셉터가 들어온 헤더를 옮기지 않음 | ST-03(`CoreInternalApiIT` · `RealtimeInternalApiIT` · `SecurityChainIT` · `InternalAuthInterceptorTest`), ST-02(B4.forbidden/internal/x · B4.rawuri), ST-08(인코딩 변형), ST-21 | 가능 | — |
| NFR9.7 | §1 · SD-13 | `core_app` ↔ realtime 스키마, `realtime_app` ↔ core 스키마의 읽기 · 쓰기, 앱 역할의 DDL · TRUNCATE, public 스키마 사용이 모두 권한 오류(42501), 이후 마이그레이션 표에도 기본 권한, 앱은 자기 이력 표만 읽음 | ST-03(`SchemaPrivilegeIT` · `CoreArchTest` · `RealtimeArchTest` · `FlywayConfIT`), ST-02(P1 6건) | 가능 | — |
| NFR9.8 | §1 · SD-11 · SD-15 | 앱 로그(attrs 포함) · 오류 봉투 · 전달 뒤 Outbox/Inbox · 지표 라벨 · Nginx 접근 로그 · PostgreSQL 로그에 전사 · 원음 · 토큰 · 쿠키 · 등록 코드 · 비밀 0, 예외는 errorKind만 | ST-03(`CoreLogHygieneIT` · `RealtimeLogHygieneIT` · `ErrorEnvelopeAndLimitsIT` · `StructuredLogFormatTest` · `EventLoggerTest` · `OwnerHasherTest` · `InternalAuthInterceptorTest` · `DeviceSecretsTest`), ST-02(P2.secrets: 비밀 목록 × 대상 파일 일치 0), ST-11(지표 라벨), ST-12 | 가능 | 등록 코드는 앞 4자도 쓰지 않는다(BR8.7보다 엄격, `code-summary.md`) |
| NFR9.9 | §1 · SD-3 · SD-10 · SD ## 3 | bootstrap IP당 1시간 20회 → 21번째 429 RATE_LIMITED(같은 쿠키 반복 미집계, IPv6 /64 묶음), 등록 실패 10분 10회 → 11번째 429 TOO_MANY_ATTEMPTS · 성공 시 초기화, 카운터 키 10,000 · LRU, REST 본문 64KB 초과 400 VALIDATION_FAILED(Content-Length 유무 무관), WSS 텍스트 8KB · 입력 오디오 965바이트 밖 4400, 업그레이드 뒤 5초 HELLO 없음 4408, 내부 본문 2MB, demo · prod 설정에 상향 값 없음, Nginx `limit_req` 앞 방어선 429 봉투 | ST-03(`SlidingWindowRateLimiterTest` · `DeviceBootstrapApiIT` · `DeviceRegisterApiIT` · `ErrorEnvelopeAndLimitsIT` · `WsFrameGuardTest` · `DeviceGatewayIT` · `CoreInternalApiIT` · `RealtimeInternalApiIT` · 두 앱 `ConfigKeyContractTest`), ST-02(B4.body64k), ST-10, ST-23(demo · prod 상향 값) | 가능 | 같은 NAT 뒤 21대 이상은 사람 운영 주의(H-6) |
| NFR9.10-a | NFR9.10 · SD-16 | 커밋 이력 · 작업 트리에 비밀 0(가짜 값은 `.gitleaks.toml`의 줄 단위 허용 2건만), 양성 대조가 심은 가짜 키를 잡음, 추적 env · 키 파일 0, 무시 규칙 유효 | ST-16, ST-02(A4.patterns · A4.ignore · P3.values), ST-17 | 가능(CI와 같은 고정 이미지 · 설정) | CI `secrets` 작업의 첫 실제 실행은 4.1 |
| NFR9.10-b | NFR9.10 · SD-12 · SD-16 | 브라우저 번들(`.next/static`) · 이미지 설정 · 기록 · 우리가 넣은 파일에 키 형식 문자열 · 비밀 변수 이름 0 | ST-17, ST-20(image-checks ①) | 가능 | — |
| NFR9.10-c | NFR9.10 · SD-12 | 비밀은 서비스별 env 파일(파일 0600 · 디렉터리 0700)로만, 컨테이너마다 분배표대로, 코드 · 설정에 기본 비밀 없음, 누락이면 이름만 남기고 기동 거부 | ST-02(B1.secrets), ST-21(infra-validate `secret-env-files` · `test-host-tools.sh` render-env 0600/0700), ST-03(`CorePropertiesValidationTest` · `RealtimeStartupChecksTest` · `FlywayConfIT`) | 가능 | 실서버 `/opt/mujoong/env` 권한 확인은 4.2 · 4.3(배포 D3의 infra-validate) — 이 목표 판정에는 넣지 않음 |
| NFR9.11 | §1 · SD-14 · SD-25 | SD-14 13조합: demo · prod에 dev · load-test · test 프로필 또는 `enabled=true`가 함께면 웹 서버 전 기동 거부(충돌 이름만 한 줄), dev(false) · demo 단독 · prod 단독은 `/api/dev/login` 404 + 차단 사건, dev + enabled만 시드 세션 | ST-03(`ProfileGuardRulesTest` · 두 앱 `ProfileGuardEnvironmentPostProcessorTest` · 두 앱 `ProfileGuardBootIT` · `DevSeedRunnerIT` · `GuardianApiIT` · `CoreLogHygieneIT`), ST-02(B5 4건), ST-20(image-checks ④), ST-21(`demo-profile` · `bad-devlogin-on` · `bad-profile-demo-dev`), ST-04(`routes.test.tsx`) | 가능 | 배포 D3 재확인은 4.3 |
| NFR9.12-a | NFR9.12 · SD-2 | Next.js HTML은 앱이 낸 요청별 nonce CSP 하나(`'nonce-…' 'strict-dynamic'`, `connect-src 'self' wss://<호스트>`, `frame-ancestors 'none'`, 운영 빌드에 `'unsafe-eval'` 없음), API · 정적 음성 · Nginx 오류는 `default-src 'none'; frame-ancestors 'none'` 하나, 모든 응답에 nosniff · `Referrer-Policy: strict-origin-when-cross-origin` · Permissions-Policy, Server 판 숨김 · X-Powered-By 없음 | ST-04(`csp.test.ts` · `routes.test.tsx` · `dev-edge.test.mjs`), ST-21(infra-validate nginx 지시어), ST-08 | 가능 | 실브라우저 CSP 콘솔은 SD-2-b(소유 단계 없음) |
| NFR9.12-b | NFR9.12 · SD-11 | 경계 입력 Bean Validation → 400 VALIDATION_FAILED, JSON 아닌 본문 400, 거부된 값 미반사, 500은 고정 문구 · 스택 없음, 변형 URL 400 봉투 | ST-03(`ErrorEnvelopeAndLimitsIT` · `DeviceRegisterApiIT` · `ContractConformanceIT` · `RealtimeInternalApiIT` · `RealtimeLogHygieneIT` · `SecurityChainIT` · `WebErrorMappingTest`) | 가능 | — |
| NFR9.12-c | NFR9.12 · SD-11 | DB 접근은 매개변수화만, 이어 붙인 SQL 0(같은 클래스의 문자열 상수와 형식이 정해진 수치만 허용) | ST-23(SQL 연결 grep, 알려진 2건 분류) | 가능(수동 분류) | 자동 규칙 없음(설계도 'grep 규칙 + 리뷰'), 15절 G6 |
| NFR9.13 | §1 · SD-16 | 운영 bootJar 의존성(Trivy) · npm audit(frontend · tools)의 high 이상 0, 또는 사유 · 만료일 있는 예외만(만료 지난 예외는 실패), 이미지 high 이상 0(고칠 판 있는 것) | ST-18, ST-19, ST-20(image-checks ③) | 가능(작성 중 확인: DB 받기 · npm audit 실행) | braces 예외는 `status: candidate`(J277 사람 승인 대기), 관문은 status를 보지 않음 — 15절 G7 |
| NFR9.14 | §1 · SD-15 | 사건 5종(SECURITY_AUTH_FAILED · _RATE_LIMITED · _ORIGIN_DENIED · _DEV_LOGIN_BLOCKED · _INTERNAL_TOKEN_FAILED)마다 한 줄 로그 + `mujoong_security_event_total{type}`, attrs는 허용 키 표 안, 분당 상한 60을 넘으면 카운터만, 병합 창 안 반복은 repeat 한 줄 | ST-03(`CoreLogHygieneIT` · `EventLoggerTest` · `EventLoggerMergeTest` · `DeviceGatewayIT` · `RealtimeInternalApiIT` · `RealtimeLogHygieneIT` · `StructuredLogFormatTest`), ST-11(카운터 계열 존재), ST-12 | 가능 | NFR9.15는 observability 소유 |
| NFR12.1-a | NFR12.1 · SD-21 | 삭제 참가자 셋(Device · Core 메시징 · Realtime 메시징) 멱등 · 자기 스키마만 · 다른 보호자 보존 · 전달 중 제어 이벤트 유지(FAILED 제어 이벤트는 재무장), 모든 Outbox/Inbox 새 행에 guardian_id(DeviceStatusChanged는 payload.guardianId) | ST-03(`DeletionParticipantsCoreIT` · `CoreMessagingRepositoryIT` · `RealtimeMessagingIT` · `RealtimeMessagingRepositoryIT`) | 가능 | — |
| NFR12.1-b | NFR12.1 · SD-21 | 삭제 지휘(U2) 뒤 다섯 표에서 `guardian_id = :g` 0건, 지휘의 마지막 재호출 | U2 통합 시험 | 불가(U2 코드 없음) | U2 Construction(사람 지시로 이번에 시작하지 않음). `Unverified` |
| NFR12.2 | §1 · SD-22 | ACTIVE 전 · 종료 · FAILED 뒤 프레임 폐기, 프레임 경로의 DB · 파일 · Outbox 접근 0, 세션 뒤 DB · 로그에 표식 바이트 0, 자동 힙 덤프 끔 · 코어 덤프 0 · OOM 즉시 종료 · heapdump 비노출 | ST-03(`InputRelayTest` · `RealtimeLogHygieneIT`(표식 `SD22-MARKER-` · 프레임 경로 연결 0) · `RealtimeArchTest`), ST-11, ST-21(불변식 11 `ulimits core 0`), ST-23(realtime 스키마 바이너리 열 0) | 가능 | 호스트 스왑 없음은 SD-22 설계 항목으로 4.2 몫 |
| NFR12.3 | §1 · SD-23 | PUBLISHED · PROCESSED · IGNORED가 되는 트랜잭션에서 payload `{}` · purged_at, FAILED · DEAD는 7일 뒤 비움(하루 한 번), 불변식 점검이 남은 페이로드를 찾음 | ST-03(`CoreMessagingRepositoryIT` · `RealtimeMessagingRepositoryIT` · `MessagingJobsTest` · `CoreLogHygieneIT`) | 가능 | — |
| NFR12.4 | §1 · SD-24 | 실제 데이터 전 승인 항목(D-14)에 ① 백업 충돌 해소 ② 정식 사전(U3) 전 실제 아동 데이터 금지가 있다, 백업 대상 DB는 합성(`synthetic`)이고 합성이 아니면 백업이 거부된다 | ST-25(문서 · 출처), ST-15(rc 3 REFUSED), ST-03(`SchemaPrivilegeIT` · `RealtimeStartupChecksTest`) | 가능 | 안 A ~ D 결정 자체는 사람(H-1 · H-2). D-14 번호 충돌 주의(15절 G10) |

## 4. 설계 시험 이행표 (`security-design.md` ## 4의 SD-1 ~ SD-25)

설계가 '3.5 구현'으로 둔 행은 3.5가 만든 시험이 증거이고, 'Build and Test'로 둔 행과 3.5가 3.6으로 넘긴 행은 이 단계 명령이 증거다. 한 SD에 행이 둘이면 ## 4의 순서대로 `-a` · `-b`를 붙였다.

| SD 시험 행 | 설계 단계 | 이행(저장소 시험 · 명령) | Step 9 증거 | 비고 |
|---|---|---|---|---|
| SD-1 | B&T | 80 · 443 · HSTS · Host/SNI · 갱신 | ST-07 · ST-08 · ST-09 · ST-02(B4.sni · B4.host) | 갱신 드라이런 · 바깥 스캔은 4.3 · 4.2(NFR9.1-d · -e). OCSP stapling은 3.4 D-3이 '끔'으로 바꿈 → 기대값 `ssl_stapling off` |
| SD-2-a | 3.5 | 앱: `csp.test.ts` · `routes.test.tsx`. Nginx: infra-validate 정적 지시어. 실행 중 Nginx의 location별 헤더 자동 시험은 없음 | ST-04 · ST-21 · ST-08 | ST-08이 실행 중 헤더를 대신 확인(15절 G6) |
| SD-2-b | B&T | 실브라우저 두 종 콘솔 CSP 위반 0, /device AudioWorklet 적재 | 없음 | 브라우저 없음 → `Unverified`, 소유 단계 없음(사람이 4.3 실기 점검에 넣을지 결정). 되돌림 장치 `STRICT_DYNAMIC_EXCLUDED_PATH_PREFIXES`(J227) |
| SD-3-a | 3.5 | `nginx -t` · /internal · /actuator 403 · `..;` 400 · 64KB 400 · 위조 XFF · X-Trace-Id · 게시 포트(검증 명령 B 4단계 · B1 · infra-validate · `DeviceBootstrapApiIT#forwardedForFromAnUntrustedSourceIsIgnored`). `limit_req` 429 봉투 · `%2f` · 역슬래시 변형 · 접근 로그(쿼리 · 쿠키 없음, 폴링 2xx 제외)의 실행 확인은 3.6으로 넘어옴 | ST-02 · ST-21 · ST-08 · ST-10 · ST-12 | — |
| SD-3-b | B&T | 실제 Nginx 경유 WSS가 60초 넘게 무음이어도 Nginx가 먼저 끊지 않고 앱의 40초 판정이 먼저 | 정적: infra-validate(`/ws/device` 75초 · `proxy_buffering off`), 앱: `DeviceGatewayIT#fortySecondsOfSilenceDisconnectsTheLedgerAndTheNextHeartbeatRecovers` | 실시간 경유 시험 도구 없음 → 돌리지 않으면 `Unverified`(15절 G4) |
| SD-4 | 3.5 | `SecurityChainIT`(9) · `HealthAndReadinessIT` · `RealtimeInternalApiIT#notReadyAndDrainingAnswer503WithRetryAfterBeforeAuthentication` · `CoreInternalApiIT` | ST-03 | — |
| SD-5 | 3.5 | `CookieAttributesTest` · `GuardianApiIT` · `SessionJdbcConfigIT` · `CorePropertiesValidationTest#cookieLongerThanSessionIdleFails` | ST-03 · ST-02(C2) | 로그아웃은 U2 |
| SD-6 | 3.5 | `SecurityChainIT` · `GuardianApiIT#csrfTokenNeedsASessionIsNoStoreAndRefreshesTheSessionCookie` · `api-client.test.ts` · `dev-login.test.tsx` | ST-03 · ST-04 · ST-02(C3) | — |
| SD-7 | 3.5 | `DeviceGatewayIT`(13, 교체 순서 · 1011 포함) · `WsFrameGuardTest` · `DeviceSecretsTest` · `DeviceBootstrapApiIT` · `RealtimeMessagingIT` | ST-03 · ST-02(B4.ws · C5) | — |
| SD-8 | 3.5 | `CoreInternalApiIT` · `RealtimeInternalApiIT` · `InternalAuthInterceptorTest` · `SecurityChainIT#credentialsOfOneChainGrantNothingOnAnother` | ST-03 · ST-02(B4.forbidden/internal/x) | — |
| SD-9-a | 3.5 | `GuardianApiIT#endIs202ClosingAndAnotherGuardiansIdsLookExactlyLikeUnknownIds` · `ContractConformanceIT` | ST-03 | 소유권 행동 시험 |
| SD-9-b | 3.5 | ArchUnit '보호자 소유 자원 조회 메서드가 보호자 id를 요구' — 미구현(3.5가 J113 나머지 묶음으로 3.6 이후로 넘김) | ST-23(정적 검토, 임시 근거) | 자동 규칙이 없어 `Unverified`(15절 G1) |
| SD-10 | 3.5 | `SlidingWindowRateLimiterTest`(14) · `DeviceBootstrapApiIT` · `DeviceRegisterApiIT` · `ErrorEnvelopeAndLimitsIT` · `WsFrameGuardTest` · `DeviceGatewayIT` · `ConfigKeyContractTest` | ST-03 · ST-23 | — |
| SD-11 | 3.5 | `ErrorEnvelopeAndLimitsIT` · `WebErrorMappingTest` · `SecurityChainIT#firewallAndContainerRejectionsAreA400Envelope` · `eslint-boundary.test.ts`(react/no-danger) · `RealtimeLogHygieneIT#rejectedInputsAreNeverEchoedIntoResponsesOrLogs` | ST-03 · ST-04 · ST-23(SQL 연결) | SQL 연결 정적 검사는 자동 시험이 아님 |
| SD-12 | B&T | B1.secrets · `test-host-tools.sh` · image-checks ① · 번들 grep · Actuator env · heapdump 응답 | ST-02 · ST-21 · ST-20 · ST-17 · ST-11 | — |
| SD-13-a | 3.5 | `SchemaPrivilegeIT`(9, 기본 권한 탐침 V2 포함) · `FlywayConfIT` | ST-03 · ST-02(P1) | — |
| SD-13-b | B&T | 순서: B2 · B3(postgres → migrate → 시드 → 앱), image-checks ④. migrate 실패 시 앱 기동 거부: `SchemaVersionCheckTest`(빈 이력 · 기준선만이면 거부) · `CoreStartupSequenceIT` · `RealtimeStartupRecoveryIT#aStartupCheckRejectionStopsBeforeTheWebServerAndReadinessNeverComesUp`. 기존 볼륨 init 건너뛰기 | ST-02 · ST-20 · ST-03 · ST-13 | Compose의 `depends_on … required: false` 때문에 Compose만으로는 막지 않는다. 막는 것은 앱 S2와 배포 D4(실호스트 확인은 4.3) — 15절 G14 |
| SD-14 | 3.5 | 3절 NFR9.11의 시험 전부 + `RealtimeStartupChecksTest`(공급자 호스트 허용 목록 · 필수 비밀) | ST-03 · ST-02(B5) · ST-20(④) | — |
| SD-15-a | 3.5 | `CoreLogHygieneIT`(4) · `RealtimeLogHygieneIT`(3) · `EventLoggerTest` · `EventLoggerMergeTest` · `StructuredLogFormatTest` | ST-03 | — |
| SD-15-b | B&T | 통합 환경의 Nginx 접근 로그 · PostgreSQL 로그에 시드 값 0 | ST-02(P2: `compose.log`에 nginx · postgres 포함) · ST-12 | — |
| SD-16 | B&T | CI 관문의 로컬 동등물 | ST-16 · ST-17 · ST-18 · ST-19 · ST-20 · ST-22 | — |
| SD-17 | 3.5 | `OutputGateTest`(9) · `SessionLifecycleIT` · `player.test.ts` · `device-session.test.ts` | ST-03 · ST-04 · ST-02(C10 · C11) | — |
| SD-18-a | 3.5 | `SeedDictionaryTest`(44) · `SeedDictionaryFileTest` · `RealtimeStartupChecksTest#aBrokenSeedDictionaryStopsTheStartupWithoutPrintingPatterns` · `RealtimeStartupRecoveryIT` | ST-03 · ST-02(C10) | — |
| SD-18-b | 11/2 측정 | 리허설 폐기 비율로 패턴 조정 | — | 기대값이 없는 조정 활동이라 측정 목표가 아니고 이 단계 밖이다. 목록에 사유만 남긴다 |
| SD-19-a | 3.5 | 문자열 리터럴 금지 ESLint 규칙의 위반 샘플: `eslint-boundary.test.ts`. 문구 금지 어휘 단위 시험: 미구현(3.6 넘김) | ST-04 · ST-24(grep, 임시 근거) | 어휘 단위 시험이 없어 `Unverified`(15절 G2) |
| SD-19-b | B&T | 체크리스트(화면 8곳 × 열 + 오류 code + 승인 문구 2건), 아이 기기 실패 주입 뒤 화면 유지 | ST-24 · ST-04 | — |
| SD-20 | 3.5 | `ProviderRequestWhitelistIT`(3) · `SessionPackageIT` · `SessionPackageBuilderTest` · `ContractConformanceIT#freeTalkContextIsValidatedButNeverStoredOrForwarded` · `RealtimeArchTest` · `ProviderClientIT` | ST-03 | — |
| SD-21-a | 3.5 | 3절 NFR12.1-a의 시험 | ST-03 | — |
| SD-21-b | B&T | U2 삭제 지휘 통합 | — | U2 → `Unverified` |
| SD-22-a | 3.5 | `InputRelayTest` · `RealtimeArchTest#framePathNeverTouchesJdbcFilesOrTheOutbox` · 설정 점검 | ST-03 · ST-11 · ST-21 | — |
| SD-22-b | B&T | 세션 뒤 DB 덤프 · 로그 · 데이터 볼륨에 표식 바이트 0 | ST-03(`RealtimeLogHygieneIT`: 실제 PostgreSQL에서 표식 프레임으로 한 세션 뒤 표 · 로그 0, 프레임 경로 DB 연결 0 실측) · ST-02(P2) · ST-23(realtime 스키마에 bytea 없음) | 파일 시스템 수준 스캔은 하지 않는다: 앱 컨테이너는 `read_only` + tmpfs이고 프레임 경로가 DB에 닿지 않으므로 DB 볼륨에 들어갈 길이 없다는 근거를 판정에 적는다 |
| SD-23 | 3.5 | `CoreMessagingRepositoryIT` · `RealtimeMessagingRepositoryIT` · `MessagingJobsTest` | ST-03 | — |
| SD-24 | B&T | `backup.sh` 표지 거부 경로의 자동 시험은 없음(image-checks ④는 합성 성공 경로만) | ST-15 · ST-03 · ST-25 | 15절 G5 |
| SD-25 | 3.5 | `verifyBootJarHasNoTestClasses`(A1) · image-checks ⑥ · 두 앱 ArchTest `NO_TEST_CODE_IN_PRODUCTION` · `ContractConformanceIT#coreHandlesExactlyTheOperationsTheContractAssignsToIt`(Core 엔드포인트 = 계약) · `RealtimeStartupChecksTest#publicEnvironmentsAcceptOnlyAllowListedProviderHosts` · `ProviderClientIT#aCoreCancelIsHandledWithinTwoSecondsWhileTheProviderConnectHangs` | ST-02 · ST-20 · ST-03 · ST-23(Realtime 매핑) | Realtime 엔드포인트 집합 자동 시험은 없음(15절 G3) |

## 5. 자동 시험 — 백엔드 · 프런트 · 도구

### 5.1 백엔드 보안 시험 클래스와 결과 추출 (ST-03)

백엔드 시험은 ST-02(검증 명령 A1 `gradle clean check`, 태그 필터 없음)와 `unit-test-instructions.md`의 U1 명령이 이미 돌린다. 모든 시험 클래스가 `@Tag("u1")`이라 두 명령의 시험 집합은 같다. 보안 증거를 위해 다시 돌리지 않고 JUnit XML에서 클래스별 결과를 모은다.

| 영역 | 시험 클래스 |
|---|---|
| 인증 체인 · 쿠키 · CSRF · Origin | `SecurityChainIT` · `CookieAttributesTest` · `GuardianApiIT` · `SessionJdbcConfigIT` · `CorePropertiesValidationTest` |
| 기기 인증 · WSS | `DeviceBootstrapApiIT` · `DeviceRegisterApiIT` · `DeviceSecretsTest` · `SecretCompareTest` · `CodeGeneratorTest` · `RegistrationCodeTest` · `DeviceGatewayIT` · `WsFrameGuardTest` · `RealtimeMessagingIT` |
| 내부 호출 | `CoreInternalApiIT` · `RealtimeInternalApiIT` · `InternalAuthInterceptorTest` |
| 남용 방지 · 입력 · 오류 봉투 | `SlidingWindowRateLimiterTest` · `ErrorEnvelopeAndLimitsIT` · `WebErrorMappingTest` · `HealthAndReadinessIT` · `ContractConformanceIT` · 두 앱 `ConfigKeyContractTest` |
| 프로필 가드 · 기동 검사 | `ProfileGuardRulesTest` · 두 앱 `ProfileGuardEnvironmentPostProcessorTest` · 두 앱 `ProfileGuardBootIT` · `DevSeedRunnerIT` · `RealtimeStartupChecksTest` · `RealtimeStartupRecoveryIT` · `SchemaVersionCheckTest` · `CoreStartupSequenceIT` |
| DB 역할 · 구조 규칙 | `SchemaPrivilegeIT` · `FlywayConfIT` · `CoreArchTest` · `RealtimeArchTest` · `ContractModelArchTest` |
| 로그 위생 · 보안 사건 | `CoreLogHygieneIT` · `RealtimeLogHygieneIT` · `EventLoggerTest` · `EventLoggerMergeTest` · `StructuredLogFormatTest` · `OwnerHasherTest` |
| 게이트 · 공급자 최소화 · 원음 | `OutputGateTest` · `SeedDictionaryTest` · `SeedDictionaryFileTest` · `SessionLifecycleIT` · `ProviderRequestWhitelistIT` · `ProviderClientIT` · `InputRelayTest` · `SessionPackageIT` · `SessionPackageBuilderTest` · `ApprovedContentEntitiesTest` |
| 삭제 · 페이로드 수명 | `DeletionParticipantsCoreIT` · `CoreMessagingRepositoryIT` · `RealtimeMessagingRepositoryIT` · `MessagingJobsTest` |

```bash
# ST-03 — ST-02(또는 U1 시험 명령) 뒤에 저장소 루트에서
classes='contract.secret.SecretCompareTest contract.profile.ProfileGuardRulesTest contract.log.StructuredLogFormatTest
contract.log.OwnerHasherTest contract.arch.ContractModelArchTest
core.web.SecurityChainIT core.web.CookieAttributesTest core.web.DeviceBootstrapApiIT core.web.DeviceRegisterApiIT
core.web.GuardianApiIT core.web.ErrorEnvelopeAndLimitsIT core.web.HealthAndReadinessIT core.web.CoreInternalApiIT
core.web.ContractConformanceIT core.web.CoreLogHygieneIT core.web.WebErrorMappingTest
core.security.ratelimit.SlidingWindowRateLimiterTest core.startup.ProfileGuardBootIT
core.startup.ProfileGuardEnvironmentPostProcessorTest core.startup.CorePropertiesValidationTest
core.startup.SchemaVersionCheckTest core.startup.CoreStartupSequenceIT core.db.SchemaPrivilegeIT core.db.FlywayConfIT
core.device.DeviceSecretsTest core.device.CodeGeneratorTest core.device.RegistrationCodeTest
core.internalhttp.InternalAuthInterceptorTest core.observability.EventLoggerTest core.identity.DevSeedRunnerIT
core.activity.SessionPackageIT core.activity.SessionPackageBuilderTest core.messaging.DeletionParticipantsCoreIT
core.messaging.CoreMessagingRepositoryIT core.messaging.MessagingJobsTest core.config.SessionJdbcConfigIT
core.config.ConfigKeyContractTest core.catalog.ApprovedContentEntitiesTest core.arch.CoreArchTest
realtime.web.DeviceGatewayIT realtime.web.RealtimeInternalApiIT realtime.web.RealtimeLogHygieneIT
realtime.gateway.WsFrameGuardTest realtime.voice.ProviderRequestWhitelistIT realtime.voice.ProviderClientIT
realtime.voice.InputRelayTest realtime.gate.OutputGateTest realtime.gate.SeedDictionaryTest
realtime.gate.SeedDictionaryFileTest realtime.startup.ProfileGuardBootIT
realtime.startup.ProfileGuardEnvironmentPostProcessorTest realtime.startup.RealtimeStartupChecksTest realtime.startup.RealtimeStartupRecoveryIT
realtime.observability.EventLoggerMergeTest realtime.messaging.RealtimeMessagingIT
realtime.messaging.RealtimeMessagingRepositoryIT realtime.session.SessionLifecycleIT realtime.arch.RealtimeArchTest
realtime.config.ConfigKeyContractTest'
n=0 tot=0 bad=0
for c in $classes; do
  n=$((n + 1))
  x=$(ls backend/*/build/test-results/test/TEST-kr.mujoong."$c".xml 2>/dev/null | head -n 1)
  if [ -z "$x" ]; then echo "MISSING $c"; bad=$((bad + 1)); continue; fi
  line=$(grep -o -m1 '<testsuite [^>]*' "$x")
  get() { sed -E "s/.* $1=\"([^\"]+)\".*/\1/" <<<"$line"; }
  t=$(get tests) s=$(get skipped) f=$(get failures) e=$(get errors) ts=$(get timestamp)
  echo "$c tests=$t skipped=$s failures=$f errors=$e ts=$ts"
  tot=$((tot + t))
  [ $((s + f + e)) -eq 0 ] || bad=$((bad + 1))
done | tee build/b-and-t/security/st03-backend-security-tests.txt
```

기대: 마지막 줄 `SUMMARY`는 이 블록이 따로 계산하지 않으므로 다음 한 줄로 합계를 낸다 — `awk '{for(i=2;i<=NF;i++){split($i,a,"=");if(a[1]=="tests")t+=a[2];if(a[1]~/skipped|failures|errors/)b+=a[2]}}END{print "classes="NR" tests="t" problems="b}' build/b-and-t/security/st03-backend-security-tests.txt`. 기대값은 `classes=59 tests=557 problems=0`이고 `MISSING` 줄이 없으며 모든 `ts`가 Step 9 시작 시각 뒤다. 59 · 557은 3.5 마지막 실행(2026-10-10T18:4xZ)의 XML에 같은 스크립트를 돌린 수이며(작성 중 확인), 3.6은 소스를 고치지 않으므로 수가 달라지면 원인을 조사한다.

### 5.2 프런트 · 도구 보안 시험 (ST-04)

전체 프런트 · tools 시험은 ST-02(A2 · A3)와 U1 명령이 돌린다. 보안 증거를 파일 단위로 남기려고 아래 부분 실행을 한 번 더 한다(수 초).

```bash
# ST-04
(cd frontend && npx --no-install vitest run \
  tests/u1/shared/csp.test.ts tests/u1/shared/routes.test.tsx tests/u1/shared/copy.test.ts \
  tests/u1/lint/eslint-boundary.test.ts tests/u1/parent/api-client.test.ts tests/u1/parent/dev-login.test.tsx \
  tests/u1/device/no-service-worker.test.ts tests/u1/device/player.test.ts tests/u1/device/device-session.test.ts \
  tests/u1/device/device-machine.test.ts tests/u1/device/device-app.test.tsx tests/u1/device/d0-view.test.tsx \
  tests/u1/device/t19-view.test.tsx --reporter=verbose) 2>&1 | tee build/b-and-t/security/st04-frontend.txt
(cd tools && npx --no-install vitest run tests/u1/dev-edge.test.mjs tests/u1/device-simulator.test.mjs --reporter=verbose) \
  2>&1 | tee build/b-and-t/security/st04-tools.txt
```

기대: 두 실행 모두 종료 0, 실패 0 · 건너뜀 0. 파일별로 보는 것: `csp.test.ts`(요청별 128비트 nonce · CSP 헤더 하나 · 운영 빌드에 unsafe-eval 없음 · script-src에 unsafe-inline 없음 · frame-ancestors 'none'), `routes.test.tsx`(`/parent/dev-login`은 `MUJOONG_DEVLOGIN_ENABLED`가 정확히 `true`일 때만, UUID 아닌 세션 경로는 그리지 않음, worklet 라우트 nosniff), `eslint-boundary.test.ts`(react/no-danger · getUserMedia 한정 · 서비스 워커 금지 · jsx-no-literals · /parent ↔ /device import 금지), `api-client.test.ts`(CSRF 헤더 · 403 한 번 재취득 · 토큰은 메모리에만), `no-service-worker.test.ts`, `player.test.ts`(OUTPUT_BEGIN 없는 0x02 · 목록 밖 contentCode 폐기, 같은 origin 승인 음성만), `dev-edge.test.mjs`(host 모드 Nginx 대역의 보안 헤더 · 모르는 SNI/Host 거절), `device-simulator.test.mjs`(허용 밖 Origin 403 처리).

### 5.3 다시 돌려야 할 때(사다리 1단 뒤)

보안 시험만 좁혀 다시 돌릴 때(`unit-test-instructions.md` 규칙: `--tests`는 바로 앞 test 작업에만 걸린다):

```bash
cd backend && ./gradlew --no-daemon --console=plain \
  :contract-model:test --tests 'kr.mujoong.contract.secret.*' --tests 'kr.mujoong.contract.profile.*' --tests 'kr.mujoong.contract.log.*' --tests 'kr.mujoong.contract.arch.*' \
  :core-api:test --tests 'kr.mujoong.core.web.*' --tests 'kr.mujoong.core.security.*' --tests 'kr.mujoong.core.startup.*' --tests 'kr.mujoong.core.db.SchemaPrivilegeIT' --tests 'kr.mujoong.core.db.FlywayConfIT' --tests 'kr.mujoong.core.device.*' --tests 'kr.mujoong.core.internalhttp.*' --tests 'kr.mujoong.core.observability.*' --tests 'kr.mujoong.core.arch.*' --tests 'kr.mujoong.core.messaging.*' --tests 'kr.mujoong.core.identity.DevSeedRunnerIT' --tests 'kr.mujoong.core.activity.SessionPackage*' --tests 'kr.mujoong.core.config.*' \
  :realtime-activity:test --tests 'kr.mujoong.realtime.web.*' --tests 'kr.mujoong.realtime.gate.*' --tests 'kr.mujoong.realtime.gateway.WsFrameGuardTest' --tests 'kr.mujoong.realtime.voice.*' --tests 'kr.mujoong.realtime.startup.*' --tests 'kr.mujoong.realtime.arch.*' --tests 'kr.mujoong.realtime.observability.*' --tests 'kr.mujoong.realtime.messaging.*' --tests 'kr.mujoong.realtime.session.SessionLifecycleIT' --tests 'kr.mujoong.realtime.config.*' \
  -PincludeTags=u1
```

이 명령은 정상 경로에서는 돌리지 않는다(ST-02와 시험 집합이 겹친다). 하한 판정 명령이 아니다.

## 6. 검증 명령(C안)의 보안 단언 — ST-02 · ST-05

- **ST-02**: `bash scripts/verify.sh`(사람 결정 C안 전체). 리드가 빌드 · 통합 증거로 돌리는 실행과 같은 한 번을 공유하고 두 번 돌리지 않는다. 끝나면 `build/verify/report.txt`를 `build/b-and-t/verify-<회차>-report.txt`로 복사한다(`build-instructions.md` 3.8).
- **ST-05**: `KEEP=1 bash scripts/verify.sh --engine`(사람 결정 엔진 명령, 약 70초). 보충 점검(7절)을 위해 스택만 남긴다. `KEEP=1`은 끝의 스택 정리만 건너뛰고 단언은 같다. 엔진 체크포인트 검증이 아니라 손 실행이다.
- 모드: Docker가 있으므로 compose 모드로 돈다(`mode=compose`). host 모드로 떨어지면 `NOT_VERIFIED(host)` 5줄(이미지 · Compose 병합 단언 B1 · Nginx 렌더 설정 · 망 격리 · 이미지의 프로필 가드 기동 거부)에 걸린 근거는 쓸 수 없고, 그 근거에 기대는 목표는 `Unverified`(CI `images` · `skeleton` 작업, 4.1 첫 실행)로 적는다.

`report.txt`에서 읽는 보안 줄과 목표:

| 줄 ID | 무엇을 단언하나 | 목표 |
|---|---|---|
| `A1.bootjar` · `A1.tests` | bootJar 두 개에 시험 클래스 0, 시험 실패 0 | SD-25 · ST-03의 바탕 |
| `A4.patterns` · `A4.ignore` · `A4.gitleaks` | 작업 트리 키 형식 9종 걸림 0, 비밀 경로 무시 규칙. gitleaks 실행 파일이 없어 `A4.gitleaks`는 NOTE(이력 스캔은 ST-16이 대신) | NFR9.10-a |
| `B1.ports` · `B1.secrets` · `B1.disabled` · `B1.provider` | 게시 포트 nginx 127.0.0.1:8443 하나, 서비스별 비밀 이름 = 분배표, certbot · backup 꺼짐, 공급자 주소 = 가짜 공급자 | NFR9.1-e(보조) · NFR9.10-c · SD-12 · SD-25 |
| `B5.core-api.*` · `B5.realtime.*` | demo + `MUJOONG_DEVLOGIN_ENABLED=true`, demo,dev에서 이미지가 비0 + `MUJOONG_STARTUP_REJECTED`로 기동 거부(4건) | NFR9.11 |
| `B4.health` · `B4.device` · `B4.ws` | 공개 health는 `{"status":"UP"}`만, `/ws/device` 허용 Origin · 쿠키 없음 → 101 뒤 4401 | NFR9.4 · SD-3 |
| `B4.forbidden/internal/x` · `B4.forbidden/actuator/health` · `B4.rawuri` | 바깥 /internal · /actuator 403, `/api/..;/internal/x` 400 | NFR9.6 · SD-3 |
| `B4.body64k` · `B4.trace` | 64KB+1 본문 400 VALIDATION_FAILED 봉투, 위조 X-Trace-Id가 응답 · 봉투에 없음 | NFR9.9 · SD-3 |
| `B4.sni` · `B4.host` | SNI other.invalid TLS 거절(localhost 검증 대조 통과), Host other.invalid 미처리 | NFR9.1-c |
| `C1` · `C2` · `C4` | bootstrap · 세션 · 기기 쿠키의 HttpOnly · Secure · SameSite · Path 정확, Domain 없음 | NFR9.4 · NFR9.2 |
| `C3` | X-CSRF-TOKEN 없는 register 403 CSRF_TOKEN_INVALID, `Origin: https://evil.invalid` register 403 | NFR9.3 |
| `C5` | `Origin: https://evil.invalid` WSS 업그레이드 403 | NFR9.4 |
| `C9` · `C10` · `C11` | 통과 출력 무변환, 금지 문장 턴의 OUTPUT_BEGIN 0 · 0x02 0바이트 · WAITING_FILLER 1 · approved false, STOP 뒤 0x02 0 · CLOSE_TRANSPORT 뒤 입력 중계 0 | NFR1.1 · NFR1.2 · NFR12.2 |
| `P1.*`(6줄) | 교차 스키마 INSERT · 앱 역할 CREATE TABLE이 SQLSTATE 42501 | NFR9.7 |
| `P2.startup.*` · `P2.ended` · `P2.latency` · `P2.access` · `P2.secrets` | 빈 수집이 아님 + 비밀 목록 값이 core-api · realtime · web · nginx · postgres 로그 · 보고 · 표준 출력에 0건 | NFR9.8 · SD-15-b |
| `P3.values` | 생성 · 관찰한 비밀 값이 추적 · 무시되지 않은 파일에 0건 | NFR9.10-a |
| `T` | 작업 트리가 시작과 같음 | 실행 무결성 |

어느 줄이든 FAIL이면 연결된 목표는 `Not Met`이고, 사다리 1단으로 원인이 이 단계의 환경 · 스크립트인지 먼저 본다(예: 데몬을 막 띄운 직후 B 기한 초과는 `build-instructions.md` 3.3).

## 7. 보충 실행 점검 — 검증 스택 위 (ST-06 ~ ST-14)

저장소에 자동 시험이 없는 실행 중 Nginx · TLS · 관리 포트 항목을 ST-05가 남긴 스택에서 본다. 2.2의 변수를 먼저 설정한다. 이 절의 명령은 작성 중에 실행하지 않았다(검증 스택을 공유하므로). 문법은 `bash -n` · shellcheck로, TLS 판 판정 방법과 인증서 앵커 방식은 로컬 대역으로 확인했다(16절). Step 9에서 명령 자체의 오류가 나면 사다리 1단 대상이다.

### ST-06 신뢰 앵커

```bash
if [ -r build/verify/certs/ca.crt ]; then
  ANCHOR=build/verify/certs/ca.crt
else   # KEEP=1 실행 끝에 CA가 지워졌으면 서버가 내놓은 인증서를 그대로 앵커로 쓴다(검증은 켜진 채)
  openssl s_client -connect 127.0.0.1:8443 -servername localhost </dev/null 2>/dev/null | openssl x509 >"$OUT/leaf.pem"
  ANCHOR="$OUT/leaf.pem"
fi
c() { curl -sS --noproxy '*' --cacert "$ANCHOR" --max-time 10 "$@"; }
c -o /dev/null -w 'anchor http=%{http_code}\n' "$B/api/health"     # 기대: anchor http=200
```

### ST-07 TLS 판 (NFR9.1-a)

```bash
for v in tls1 tls1_1; do   # 클라이언트가 실제로 옛 판을 제안하도록 시스템 설정을 끄고 SECLEVEL=0
  printf '%s: ' "$v"
  OPENSSL_CONF=/dev/null openssl s_client -connect 127.0.0.1:8443 -servername localhost "-$v" -cipher 'DEFAULT@SECLEVEL=0' </dev/null 2>&1 |
    grep -E -o 'alert protocol version|no protocols available|wrong version number|unsupported protocol' | head -n 1
done
for v in tls1_2 tls1_3; do
  printf '%s: ' "$v"
  openssl s_client -connect 127.0.0.1:8443 -servername localhost "-$v" </dev/null 2>/dev/null | grep -E -o '^New, TLSv1\.[0-9]' | head -n 1
done
```

기대: `tls1: alert protocol version`, `tls1_1: alert protocol version`, `tls1_2: New, TLSv1.2`, `tls1_3: New, TLSv1.3`. `no protocols available`은 서버가 아니라 클라이언트가 스스로 거절한 것이라 판정 근거가 아니다(시스템 설정 그대로면 이렇게 나와 거짓 통과가 된다 — 작성 중 대역 확인) → `OPENSSL_CONF=/dev/null`이 적용됐는지 보고 다시 돈다.

### ST-08 보안 헤더 · CSP · nonce · 인코딩 변형 (NFR9.1-b · NFR9.12-a · NFR9.6 · SD-2-a · SD-3-a)

```bash
hdr_check() { # $1 경로, $2 closed|next
  local path="$1" mode="$2" f csp n
  f="$OUT/hdr$(printf '%s' "$path" | tr -c 'A-Za-z0-9' '_').txt"
  if ! c --path-as-is -o /dev/null -D "$f.raw" "$B$path"; then echo "FAIL $path 요청 실패"; return; fi
  tr -d '\r' <"$f.raw" >"$f" && rm -f "$f.raw"
  printf '%s %s\n' "$path" "$(head -n 1 "$f")"
  n=$(grep -ci '^strict-transport-security: max-age=15552000$' "$f"); [ "$n" = 1 ] && echo "  PASS HSTS" || echo "  FAIL HSTS($n)"
  n=$(grep -ci '^x-content-type-options: nosniff$' "$f"); [ "$n" = 1 ] && echo "  PASS nosniff" || echo "  FAIL nosniff($n)"
  n=$(grep -ci '^referrer-policy: strict-origin-when-cross-origin$' "$f"); [ "$n" = 1 ] && echo "  PASS Referrer-Policy" || echo "  FAIL Referrer-Policy($n)"
  n=$(grep -ci '^permissions-policy: microphone=(self), camera=(), geolocation=()$' "$f"); [ "$n" = 1 ] && echo "  PASS Permissions-Policy" || echo "  FAIL Permissions-Policy($n)"
  n=$(grep -ci '^content-security-policy:' "$f"); [ "$n" = 1 ] && echo "  PASS CSP 1개" || echo "  FAIL CSP 개수($n)"
  csp=$(grep -i '^content-security-policy:' "$f" | head -n 1 | cut -d: -f2- | sed 's/^ //')
  if [ "$mode" = closed ]; then
    [ "$csp" = "default-src 'none'; frame-ancestors 'none'" ] && echo "  PASS CSP 닫힘" || echo "  FAIL CSP 값"
  else
    case "$csp" in *"frame-ancestors 'none'"*) echo "  PASS frame-ancestors" ;; *) echo "  FAIL frame-ancestors" ;; esac
    case "$csp" in *"'nonce-"*"'strict-dynamic'"*) echo "  PASS nonce · strict-dynamic" ;; *) echo "  FAIL nonce · strict-dynamic" ;; esac
    case "$csp" in *"connect-src 'self' wss://localhost:8443"*) echo "  PASS connect-src" ;; *) echo "  FAIL connect-src" ;; esac
    case "$csp" in *"'unsafe-eval'"*) echo "  FAIL unsafe-eval 있음" ;; *) echo "  PASS unsafe-eval 없음" ;; esac
  fi
  n=$(grep -ciE '^server: .*[0-9]' "$f"); [ "$n" = 0 ] && echo "  PASS Server 판 숨김" || echo "  FAIL Server 판 노출"
  n=$(grep -ci '^x-powered-by:' "$f"); [ "$n" = 0 ] && echo "  PASS X-Powered-By 없음" || echo "  FAIL X-Powered-By"
}
{
  hdr_check /api/health closed                       # 200
  hdr_check /api/device/me closed                    # 401(앱)
  hdr_check /internal/x closed                       # 403(Nginx)
  hdr_check /media/approved/closing-v1.wav closed    # 200(정적 승인 음성)
  hdr_check '/api/..;/x' closed                      # 400(원시 URI 검사 → 오류 봉투)
  hdr_check /parent next                             # 200(Next.js)
  hdr_check /device next                             # 200(Next.js)
  n1=$(c -o /dev/null -D - "$B/parent" | grep -io "'nonce-[^']*'" | head -n 1)
  n2=$(c -o /dev/null -D - "$B/parent" | grep -io "'nonce-[^']*'" | head -n 1)
  [ -n "$n1" ] && [ "$n1" != "$n2" ] && echo "PASS nonce가 요청마다 다르다" || echo "FAIL nonce 재사용 또는 없음"
  for p in '/api/%2e%2e/internal/x' '/api/x%2fy' '/api/x%5cy' '/internal%2fx'; do
    printf 'encoded %s → %s\n' "$p" "$(c --path-as-is -o /dev/null -w '%{http_code}' "$B$p")"   # 기대: 모두 400
  done
} 2>&1 | tee "$OUT/st08-headers.txt"
```

기대: FAIL 줄 0, 첫 줄 상태가 주석과 같음, 인코딩 변형 4개 모두 400. 3.4 D-3에 따라 OCSP stapling은 꺼져 있는 것이 기대값이다(헤더 시험 대상 아님).

### ST-09 HTTP 80 · ACME · 모르는 Host (NFR9.1-c)

검증 스택은 80을 호스트에 게시하지 않으므로 nginx 컨테이너의 망 이름공간에 curl을 붙인다(curl은 core-api 이미지의 것을 빌린다).

```bash
NGX=$(cid nginx)
p80() { docker run --rm --network "container:$NGX" --entrypoint curl mujoong.local/mujoong-core-api:verify -sS -o /dev/null -D - --max-time 5 "$@" 2>&1 | tr -d '\r'; }
{
  p80 -H 'Host: localhost' http://127.0.0.1/x | grep -iE '^(HTTP/|location:)'                    # 기대: HTTP/1.1 301 · location: https://localhost/x
  p80 -H 'Host: localhost' http://127.0.0.1/.well-known/acme-challenge/probe | head -n 1          # 기대: HTTP/1.1 404(301 아님 = ACME 경로 예외)
  p80 -H 'Host: other.invalid' http://127.0.0.1/x | grep -E -o 'Empty reply from server' || echo "FAIL 444 아님"   # 기대: Empty reply(444)
} | tee "$OUT/st09-http80.txt"
```

### ST-10 Nginx `limit_req` 앞 방어선 (NFR9.9 · SD-3-a)

같은 bootstrap 쿠키를 반복하면 앱 한도는 소비되지 않으므로(편차 (k)) 걸리는 것은 Nginx의 `rate=30r/m burst=20 nodelay`뿐이다. 쿠키는 curl 한 번의 메모리 쿠키 엔진에만 있다.

```bash
args=(); for _ in $(seq 1 31); do args+=(-o /dev/null "$B/api/device/bootstrap"); done
c -b '' -X POST -w '%{http_code}\n' "${args[@]}" | sort | uniq -c | tee "$OUT/st10-limit-req.txt"
args=(); for _ in $(seq 1 31); do args+=("$B/api/device/bootstrap"); done
printf 'RATE_LIMITED 봉투 %s건\n' "$(c -b '' -X POST "${args[@]}" | grep -o '"code":"RATE_LIMITED"' | wc -l)" | tee -a "$OUT/st10-limit-req.txt"
```

기대: 첫 블록에 `201`(같은 코드 반복)과 `429`가 함께 나오고 429가 1건 이상, 둘째 블록 `RATE_LIMITED 봉투` 1건 이상. 본문의 다른 필드(등록 코드)는 출력하지 않는다(`grep -o`가 RATE_LIMITED만 고른다). 이 점검은 새 코드를 두 번만 발급하므로 앱 IP 한도(시간당 20회)에 닿지 않는다.

### ST-11 관리 포트 노출 · 덤프 설정 · 지표 라벨 (SD-12 · SD-22 · NFR12.2 · NFR9.8 · NFR9.14)

```bash
for s in core-api realtime; do
  id=$(cid "$s")
  for ep in env heapdump configprops beans threaddump loggers mappings shutdown health prometheus; do
    printf '%s %s %s\n' "$s" "$ep" "$(docker exec "$id" curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:9090/actuator/$ep")"
  done
  docker exec "$id" sh -c 'echo "ulimit_core=$(ulimit -c)"; case " $JAVA_TOOL_OPTIONS " in *" -XX:+ExitOnOutOfMemoryError "*) echo exit_on_oom=yes ;; *) echo exit_on_oom=no ;; esac; case " $JAVA_TOOL_OPTIONS " in *" -XX:-HeapDumpOnOutOfMemoryError "*) echo heapdump_off=yes ;; *) echo heapdump_off=no ;; esac'
  docker exec "$id" curl -s http://127.0.0.1:9090/actuator/prometheus >"$OUT/prom-$s.txt"
  printf '%s uuid_labels=%s security_event_series=%s\n' "$s" \
    "$(grep '^mujoong_' "$OUT/prom-$s.txt" | grep -c -E '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')" \
    "$(grep -c '^mujoong_security_event_total' "$OUT/prom-$s.txt")"
done 2>&1 | tee "$OUT/st11-mgmt.txt"
```

기대(두 앱 각각): env · heapdump · configprops · beans · threaddump · loggers · mappings · shutdown 모두 `404`, health · prometheus `200`, `ulimit_core=0`, `exit_on_oom=yes`, `heapdump_off=yes`, `uuid_labels=0`, `security_event_series` 1 이상(C 단계의 CSRF · Origin 거부와 B4.ws의 4401이 카운터를 만든다). `JAVA_TOOL_OPTIONS`는 JVM 옵션만 담고 비밀이 없지만 값 전체를 출력하지 않고 두 옵션의 유무만 찍는다.

### ST-12 로그 위생 점검 도구를 검증 스택에 (NFR9.8 · NFR9.14 · SD-15-b · SD-3-a)

```bash
mkdir -p "$OUT/nolog"
MUJOONG_PROJECT=mujoong-verify MUJOONG_LOG_DIR="$OUT/nolog" \
  bash infra/bin/log-hygiene-check --since "$(date -u -d '-3 hours' +%Y-%m-%dT%H:%M:%SZ)" | tee "$OUT/st12-log-hygiene.txt"
NGX=$(cid nginx)
docker logs "$NGX" 2>&1 | awk '/requestId=/{a++} /uri=\/api\/device\/bootstrap\/status status=(2[0-9][0-9]|304)/{p++} /uri=\/api\/sessions\/[0-9a-fA-F-]+ status=(2[0-9][0-9]|304)/{p++} END{printf "access_lines=%d polling_2xx=%d\n", a, p}' | tee -a "$OUT/st12-log-hygiene.txt"
```

기대: `CHECK format · attrs · names · secrets · nginx · postgres`가 모두 `violations=0`, `SUMMARY violations=0`(`secrets`는 env 파일이 지워져 대조 값이 없으므로 P2가 그 역할을 맡는다), `access_lines` 1 이상 · `polling_2xx=0`(반복 폴링 2xx · 304는 접근 로그 조건으로 제외, SD-3).

### ST-13 기존 볼륨 재기동 때 init 건너뛰기 (SD-13-b, 선택)

postgres는 `-c config_file=/etc/postgresql/mujoong.conf`로 뜨고 그 파일은 `build/verify/postgres/postgresql.conf`의 읽기 전용 바인드다. `KEEP=1` 실행도 끝에 이 디렉터리를 지우므로(`scripts/verify.sh`의 `remove_secrets`) 그대로 재기동하면 바인드 원본이 없어 postgres가 설정을 읽지 못한다(시험 절차 탓의 실패). 그래서 재기동 전에 b-stack.sh와 같은 방법으로 비밀이 없는 설정 파일만 다시 렌더한다(ST-14가 다시 지운다).

```bash
PG=$(cid postgres)
bash infra/bin/render-pgconf --out build/verify/postgres/postgresql.conf && chmod -R go+rX build/verify/postgres
docker restart "$PG" >/dev/null
timeout 120 bash -c "until [ \"\$(docker inspect -f '{{.State.Health.Status}}' $PG)\" = healthy ]; do :; done" && echo "postgres healthy"
printf 'skipping_init=%s\n' "$(docker logs "$PG" 2>&1 | grep -c 'Skipping initialization')" | tee "$OUT/st13-pg-restart.txt"
```

기대: `postgres healthy`, `skipping_init=1` 이상(같은 볼륨에서 init 스크립트가 다시 돌지 않음). 돌리지 않으면 SD-13-b의 이 부분은 `Unverified`다.

### ST-14 검증 스택 정리

```bash
docker compose -p mujoong-verify down -v --remove-orphans -t 3
rm -rf build/verify/env build/verify/c build/verify/certs build/verify/postgres   # verify.sh가 이미 지웠으면 아무것도 없음
docker ps -q --filter label=com.docker.compose.project=mujoong-verify | wc -l        # 기대: 0
```

### ST-15 백업 데이터 등급 거부 — 버림 PostgreSQL (NFR12.4 · SD-24)

검증 스택과 무관한 버림 망 · DB로 `backup.sh`의 B2 단계(합성이 아니면 REFUSED · 종료 코드 3)를 본다. 비밀번호는 하위 셸 변수에만 있다.

```bash
(
  set -u
  docker network create dso-bk >/dev/null
  export POSTGRES_PASSWORD DB_BACKUP_PASSWORD
  POSTGRES_PASSWORD="$(openssl rand -hex 24)"
  DB_BACKUP_PASSWORD="$(openssl rand -hex 24)"
  docker run -d --name dso-bk-pg --network dso-bk -e POSTGRES_PASSWORD postgres:16.15 >/dev/null
  timeout 90 bash -c 'until docker exec dso-bk-pg pg_isready -h 127.0.0.1 -U postgres -q; do :; done'
  docker exec -i dso-bk-pg psql -q -U postgres -v ON_ERROR_STOP=1 <<SQL
CREATE ROLE mujoong_backup LOGIN PASSWORD '$DB_BACKUP_PASSWORD';
CREATE DATABASE mujoong;
ALTER DATABASE mujoong SET mujoong_db.data_class = 'real';
SQL
  bk() {
    docker run --rm --network dso-bk --entrypoint /opt/mujoong/backup/backup.sh \
      -v "$PWD/infra/backup:/opt/mujoong/backup:ro" \
      --tmpfs /backups:uid=999,gid=999,mode=0700 --tmpfs /var/log/mujoong:uid=999,gid=999,mode=0750 \
      -e PGHOST=dso-bk-pg -e DB_BACKUP_PASSWORD mujoong.local/mujoong-backup:verify --trigger manual
    echo "rc=$?"
  }
  bk                                                                     # 판정
  docker exec dso-bk-pg psql -q -U postgres -c "ALTER DATABASE mujoong SET mujoong_db.data_class = 'synthetic'"
  bk                                                                     # 대조
  docker rm -f dso-bk-pg >/dev/null
  docker network rm dso-bk >/dev/null
) 2>&1 | tee "$OUT/st15-backup-refusal.txt"
```

기대: 첫 실행 `… result=REFUSED file=none bytes=0 … reason=data_class`와 `rc=3`. 대조 실행은 B2를 통과해야 한다(`reason`이 `data_class`가 아님, 바라는 결과는 `result=OK`와 `rc=0`). 대조가 다른 이유로 실패해도 B2 통과가 보이면 판정에는 영향이 없고 사유를 기록한다. 백업 이미지 지문이 지금 소스와 다르면(16절: 작성 중 `backup`만 낡음) ST-20의 다시 빌드를 먼저 한다.

## 8. 비밀 스캔 — ST-16 · ST-17 (+ ST-02의 A4 · P3)

비밀 스캔은 세 겹이다: ① `scripts/verify/a-secrets.sh`(ST-02 A4: 추적 + 무시되지 않은 미추적 파일의 키 형식 9종, 무시 규칙. P3: 실행 중 생성 · 관찰한 값 대조) ② gitleaks 커밋 이력 스캔(CI `secrets` 작업과 같은 고정 이미지 · `.gitleaks.toml` · 인자, 여기서 ST-16으로 돌린다) ③ 산출물 grep(브라우저 번들 ST-17, 이미지 ST-20의 ①). 3.5는 gitleaks 실행 파일이 없어 이력 스캔을 CI 몫으로 넘겼지만, 같은 다이제스트의 이미지가 로컬에 있으므로 이번에는 여기서 돌리고 CI 실행은 4.1에서 다시 확인한다.

```bash
# ST-16
GL=zricethezav/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f
planted="$(mktemp -d)"; chmod 0755 "$planted"
# 양성 대조: 실행 때 만든 AWS 키 모양의 무작위 값(실제 키가 아니다, 값은 출력하지 않는다) — ci.yml secrets 작업과 같다
printf 'aws_access_key_id = AKIA%s\n' "$(head -c 400 /dev/urandom | tr -dc 'A-Z2-7' | head -c 16)" >"$planted/planted.txt"
chmod 0644 "$planted/planted.txt"
rc=0
docker run --rm -v "$planted:/scan:ro" -v "$PWD/.gitleaks.toml:/cfg/gitleaks.toml:ro" "$GL" \
  dir /scan --no-banner --redact=100 --config /cfg/gitleaks.toml --exit-code 7 >/dev/null 2>&1 || rc=$?
rm -rf "$planted"
echo "gitleaks 양성 대조 rc=$rc" | tee "$OUT/st16-gitleaks.txt"                       # 기대: rc=7
docker run --rm -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0='*' \
  -v "$PWD:/repo:ro" "$GL" git /repo --no-banner --redact=100 --config /repo/.gitleaks.toml 2>&1 | tee -a "$OUT/st16-gitleaks.txt"
echo "gitleaks 이력 rc=${PIPESTATUS[0]}" | tee -a "$OUT/st16-gitleaks.txt"             # 기대: rc=0, 'no leaks found'
```

```bash
# ST-17
{
  git ls-files | grep -E '(^|/)\.env($|\.)|\.(pem|key|p12|jks|pfx)$' || echo "추적 env · 키 파일 0건"
  names='POSTGRES_PASSWORD|DB_(OWNER|CORE|REALTIME|BACKUP)_PASSWORD|INTERNAL_SERVICE_TOKEN|PROVIDER_API_KEY|MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET|GOOGLE_OAUTH_CLIENT_SECRET|GHCR_PULL_TOKEN|NEO4J_AUTH|SPRING_DATASOURCE_PASSWORD'
  hits=$({ grep -rlE "$names" frontend/.next/static || true; grep -rlE -f <(grep -vE '^(#|$)' scripts/verify/secret-patterns.txt) frontend/.next/static || true; } | sort -u)
  [ -z "$hits" ] && echo "브라우저 번들 걸림 0" || printf '번들 걸림 파일:\n%s\n' "$hits"
} | tee "$OUT/st17-tracked-and-bundle.txt"
```

기대: `추적 env · 키 파일 0건`, `브라우저 번들 걸림 0`(CI `frontend` 작업의 번들 단계와 같은 규칙).

판정 메모:
- `.gitleaks.toml`의 허용 목록은 두 건이고 둘 다 경로 + 줄 정규식으로 묶인 시험 · 점검 파일의 비밀 아닌 값이다(`ApprovedContentEntitiesTest`의 파일 이름 목록, `image-checks.sh`의 RFC 6455 예시 nonce). 이번 단계는 허용 목록에 아무것도 더하지 않는다.
- gitleaks가 무엇이든 찾으면 값은 가려진 채(`--redact=100`) 규칙 id · 파일 · 커밋만 기록하고 `Not Met`으로 둔다. 진짜 비밀이면 회전 · 이력 정리는 사람 결정이다.
- `frontend/.next/standalone/.next/prerender-manifest.json`(web 이미지에 들어감)에는 빌드 때 생기는 Next.js 미리보기(draft mode) 서명 · 암호화 키가 있다. 3.5 Step 50의 gitleaks `dir` 스캔이 `frontend/.next`에서 이것을 찾았다. 저장소 · 이력에는 없고(무시 대상), U1은 `draftMode`를 쓰지 않으며(작성 중 확인: `frontend/src`에 사용 0) 키 형식 · 비밀 이름 규칙에도 걸리지 않으므로 NFR9.10의 비밀 네 종 밖이다. GHCR 공개 여부(J99)를 정할 때 함께 본다(15절 G11).

## 9. 의존성 · 이미지 취약점 검사(공급망) — ST-18 · ST-19 · ST-20 · ST-22

**도구(3.5 확정).** 설계 SD-16은 'Gradle 의존성 검사 도구(OWASP Dependency-Check류)'라고 적었고 3.5는 운영 bootJar 두 개를 Trivy `rootfs`로 스캔하는 방식으로 정했다(CI `backend-deps`). 프런트 · tools는 `npm audit --json` 결과를 `ci/security-gate.py npm-audit`이 예외 목록과 대조한다(CI `frontend-deps`). 이미지는 `infra/ci/image-checks.sh` ③이 Trivy `image`로 본다(CI `images`). 예외는 `ci/security-exceptions.yml` 하나이고 항목마다 id · 사유 · 만료일이 필수이며 만료가 지나면 관문이 실패한다.

```bash
# ST-18 예외 목록 · npm audit · 관문
{
  python3 -I ci/security-gate.py exceptions ci/security-exceptions.yml; echo "exceptions rc=$?"
  for d in frontend tools; do (cd "$d" && npm audit --json >"../$OUT/audit-$d.json") || true; done
  python3 -I ci/security-gate.py npm-audit ci/security-exceptions.yml "$OUT/audit-frontend.json" "$OUT/audit-tools.json"; echo "npm 관문 rc=$?"
  grep -n -E '^\s+(id|expires|status):' ci/security-exceptions.yml
} 2>&1 | tee "$OUT/st18-npm.txt"
```

기대: `exceptions rc=0`, `npm 관문 rc=0`. 작성 중 확인에서는 frontend high 5건이 모두 braces 한 권고(GHSA-vfj7-8cjw-p6xm, 개발 의존성 eslint-config-next 경로)이고 예외로 허용됐으며 tools는 0건이었다. 권고 DB가 바뀌면 Step 9 수는 다를 수 있다.

```bash
# ST-19 백엔드 의존성(운영 bootJar 두 개) — CI backend-deps와 같은 이미지 · 인자(--ignore-unfixed 없음)
TRIVY_IMG=aquasec/trivy@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969
export TRIVY_CACHE="$SP/dso-trivy-cache"        # 작성 중 받아 둔 DB · Java DB(없거나 낡으면 아래 실행이 프록시로 다시 받는다)
python3 -I ci/security-gate.py trivyignore ci/security-exceptions.yml >"$OUT/trivyignore"; echo "trivyignore rc=$?"
docker run --rm --network host -e HTTPS_PROXY -e SSL_CERT_FILE=/ccr/ca-bundle.crt \
  -v /root/.ccr/ca-bundle.crt:/ccr/ca-bundle.crt:ro -v "$TRIVY_CACHE:/root/.cache" \
  -v "$PWD/backend/core-api/build/libs:/scan/core-api:ro" -v "$PWD/backend/realtime-activity/build/libs:/scan/realtime:ro" \
  -v "$PWD/$OUT/trivyignore:/gate/trivyignore:ro" "$TRIVY_IMG" \
  rootfs --quiet --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --ignorefile /gate/trivyignore /scan >"$OUT/trivy-backend.txt" 2>&1
echo "trivy 백엔드 rc=$?" | tee -a "$OUT/trivy-backend.txt"
```

기대: `trivy 백엔드 rc=0`(HIGH · CRITICAL 0, 예외 목록 밖). rc=1이면 표의 취약점 id · 패키지 · 고친 판을 `test-results.md`에 옮기고 `Not Met`으로 둔다. 고칠 판이 없는 항목도 실패다(J274: 백엔드 관문은 `--ignore-unfixed`를 쓰지 않는다). 예외로 덮지 않는다.

```bash
# ST-20 이미지 지문 확인 → 낡은 것만 다시 빌드 → 이미지 점검 ① ~ ⑧(Trivy 감싸개로 ③까지)
fp=$(bash infra/ci/source-fingerprint.sh | tail -n 1)
stale=$(for n in core-api realtime web migrate backup infra fake-provider; do
  printf '%s %s\n' "$n" "$(docker image inspect "mujoong.local/mujoong-$n:verify" --format '{{index .Config.Labels "kr.mujoong.source-fingerprint"}}' 2>/dev/null)"
done | awk -v fp="$fp" '$2 != fp { printf "%s%s", sep, $1; sep="," }')
echo "낡은 이미지: ${stale:-없음}"
[ -n "$stale" ] && bash infra/ci/build-images.sh --only "$stale" --extra-ca /root/.ccr/ca-bundle.crt
mkdir -p "$SP/dso-bin"
cat >"$SP/dso-bin/trivy" <<'SH'
#!/usr/bin/env bash
exec docker run --rm --network host -e HTTPS_PROXY -e SSL_CERT_FILE=/ccr/ca-bundle.crt \
  -v /root/.ccr/ca-bundle.crt:/ccr/ca-bundle.crt:ro -v /var/run/docker.sock:/var/run/docker.sock \
  -v /tmp:/tmp:ro -v "${TRIVY_CACHE:?}:/root/.cache" \
  aquasec/trivy@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969 "$@"
SH
chmod 0755 "$SP/dso-bin/trivy"
export TRIVY_CACHE="$SP/dso-trivy-cache"
PATH="$SP/dso-bin:$PATH" bash infra/ci/image-checks.sh 2>&1 | tee "$OUT/st20-image-checks.txt"
echo "image-checks rc=${PIPESTATUS[0]}" | tee -a "$OUT/st20-image-checks.txt"
```

기대: `PASS ①` ~ `PASS ⑧`(SKIP 없음), `image-checks rc=0`. 점검 내용: ① 이미지 설정 · 기록 · 우리가 넣은 파일 · 브라우저 번들에 비밀 이름 · 키 형식 · 프록시 · CA 값 0 ② 비루트 실행 · 베이스 태그 고정 ③ Trivy high 이상(이미지 점검은 `--ignore-unfixed`, 예외 목록) ④ demo 프로필 더미 기동과 프로필 가드(demo + dev, demo + 시험 로그인) · 필수 비밀 누락 기동 거부 ⑤ 번들에 환경 파일 · `compose.ci.yml` · `compose.dev.yml` · `ci/` 0 ⑥ 앱 이미지에 시험 클래스 · 설정 · 라이브러리 0 ⑦ web 실제 옵션(read_only · cap_drop ALL) healthz ⑧ 업스트림 없음 502 유지 · 봉투. 작성 중 확인에서 `backup` 이미지만 지문이 낡았다. ST-14 뒤(검증 스택이 없을 때)에만 돈다(2.3).

```bash
# ST-22 CI 워크플로 공급망
{
  docker run --rm -v "$PWD:/repo:ro" -w /repo rhysd/actionlint:1.7.12; echo "actionlint rc=$?"
  grep -nE '^\s*-?\s*uses:\s' .github/workflows/*.yml | grep -vE 'uses:\s+\./' | grep -vE '@[0-9a-f]{40}(\s|$)' || echo "SHA 고정 안 된 uses 0건"
  grep -n -E '^\s*(permissions|contents|packages|id-token):' .github/workflows/*.yml
} 2>&1 | tee "$OUT/st22-workflows.txt"
```

기대: `actionlint rc=0`, `SHA 고정 안 된 uses 0건`(작성 중 확인: `uses` 25개 모두 40자 SHA), 권한은 최상위 `contents: read`이고 `packages: write`는 `images` 작업에만, `id-token: write`는 배포 호출 작업에만 있다. 도구 이미지(gitleaks · trivy)는 다이제스트로 고정돼 있다(`ci.yml` env).

## 10. SAST 범위와 정적 검토 — ST-21 · ST-23

**있는 것(정적 보안 검사).** 아래가 U1의 정적 분석 전체이며 모두 이 환경에서 돈다.

| 검사 | 도구 · 위치 | 막는 것 |
|---|---|---|
| 구조 보안 규칙 | ArchUnit(`CoreArchTest` · `RealtimeArchTest` · `ContractModelArchTest`): 앱 사이 코드 · 스키마 참조 0, 운영 코드 → 시험 지원 의존 0, 시각 직접 호출 0, 공급자 클라이언트는 voice 패키지 · 요청 레코드는 팩토리만, 프레임 경로의 JDBC · 파일 · Outbox 의존 0, 사본 상태 변경 호출자 제한 | 경계 위반 · 시험 훅 운영 유입 · 원음 영속 · 목적 밖 전송 |
| 프런트 보안 lint | ESLint(`eslint-boundary.test.ts`가 위반 샘플로 규칙이 살아 있음을 증명): react/no-danger, jsx-no-literals, getUserMedia 한정, 서비스 워커 금지, 영역 import 경계 | XSS 입구 · 문구 우회 · 권한 남용 |
| 비밀 | `a-secrets.sh` · gitleaks · 번들 · 이미지 grep | 8절 |
| SCA | Trivy(bootJar · 이미지) · npm audit | 9절 |
| IaC · 설정 | `infra/bin/infra-validate`(Compose 불변식 12 · demo 프로필 · 비밀 분배 · `nginx -t` + 방어 지시어 · shellcheck · 마이그레이션 금지 문장 · 번들 목록), `test-infra-validate.sh`(위반 샘플 거부), `test-gate-negatives.sh`(관문 5종 음성 대조), `test-host-tools.sh`(render-env 0600 · D3 프로필 · 로그 점검 도구) | 노출 포트 · 권한 · 비밀 분배 · 프로필 · 파괴적 마이그레이션 |
| CI | actionlint · SHA 고정 · 권한(ST-22) | 공급망 |
| 수동 grep 규칙 | ST-23 | SQL 연결 · 엔드포인트 집합 · 소유권 우회 · 한도 상향 |

**없는 것.** taint 분석 SAST(Semgrep · CodeQL · SonarQube류)는 U1 설계 범위(SD-16)에 없고 CI에도 없다. 측정 목표가 아니므로 판정에 넣지 않고 권고로만 남긴다(15절 G13).

```bash
# ST-21 인프라 정적 검사 · 관문 음성 대조
for cmd in "bash infra/bin/infra-validate" "bash infra/ci/test-infra-validate.sh" "bash infra/ci/test-gate-negatives.sh" "bash infra/ci/test-host-tools.sh"; do
  echo "== $cmd"; $cmd; echo "rc=$?"
done 2>&1 | tee "$OUT/st21-infra.txt"
```

기대(3.5 기록과 같은 수): infra-validate 8개 검사 PASS · rc 0, test-infra-validate 32건 기대대로 · rc 0, test-gate-negatives 관문 5종(가짜 비밀 · 게시 포트 추가 · 틀린 Nginx · 파괴적 마이그레이션 · 경계를 넘는 import) 차단 · rc 0, test-host-tools 23건 어긋남 0 · rc 0.

```bash
# ST-23 정적 검토(수동 분류가 필요한 grep)
{
  echo "== (1) 이어 붙인 SQL — 소문자로 시작하는 식별자 · 호출이 SQL 리터럴에 붙은 곳"
  grep -rn -i -E '\b(select|insert into|update|delete from|where|values)\b' --include='*.java' backend/*/src/main/java |
    grep -E '"\s*\+\s*[a-z][A-Za-z0-9_.]*|[a-z][A-Za-z0-9_]*(\(\))?\s*\+\s*"'
  echo "== (2) Realtime HTTP · WSS 엔드포인트 집합"
  grep -rn -E '@(Get|Post|Put|Patch|Delete|Request)Mapping|addHandler\(' backend/realtime-activity/src/main/java
  echo "== (3) 보호자 소유 사본을 보호자 id 없이 읽는 호출자"
  grep -rn -E 'activities\.findById\(' backend/core-api/src/main/java
  echo "== (4) demo · prod 설정에 한도 상향 · 시험 로그인 켬 없음"
  grep -n -E '^\s+(max|enabled):' backend/*/src/main/resources/application-demo.yml backend/*/src/main/resources/application-prod.yml
  echo "== (5) realtime 스키마의 바이너리 열"
  grep -n -i -c 'bytea' backend/realtime-activity/src/main/resources/db/migration/realtime/V1__realtime_base.sql
} 2>&1 | tee "$OUT/st23-static-review.txt"
```

기대와 분류 규칙:
1. 걸림은 두 앱의 `DbSessionLimits.java:44` 두 줄뿐이다(작성 중 확인). `lockTimeout.toMillis()` · `statementTimeout.toMillis()`는 설정에서 바인딩해 양수 검사를 거친 `Duration`의 `long` 값이고, Hikari 연결 초기화 문장이라 바인딩 매개변수를 쓸 수 없으며 사용자 입력이 닿는 길이 없다 → 허용. 그 밖의 SQL 연결(`COLUMNS` · `HISTORY_TABLE` · `SETTING` · `IN_PROGRESS_SQL` · `CHANGE_SET`)은 같은 클래스의 `static final` 문자열 상수라 대문자 이름이어서 이 grep에 걸리지 않는다. 새 걸림이 있으면 메서드 인자 · 요청 값이 SQL에 붙는지 읽고, 그렇다면 NFR9.12-c `Not Met`.
2. `/internal/sessions`(POST) · `/internal/sessions/{sessionId}/end`(POST) · `/internal/sessions/{sessionId}/view`(GET) · `/internal/events`(POST)와 WSS `addHandler`(`/ws/device`)만 나와야 한다. 시간 · 세션 · 토큰 · 한도 · 사전 · 게이트 · 설정을 바꾸는 엔드포인트가 있으면 SD-25 `Not Met`. Core 쪽은 `ContractConformanceIT`가 '처리하는 경로 = 계약'을 자동으로 단언한다.
3. 두 줄이다: `StartActivityService.java`(방금 넣은 사본을 다시 읽음 — id는 서버가 만든 값)와 `SessionStateChangedHandler.java`(내부 이벤트 처리). 보호자 요청 경로(`SessionViewService` · `EndSessionService` · `*Controller`)에 나오면 NFR9.5 · SD-9 위반 후보다. 이 검토는 SD-9-b의 임시 근거일 뿐 자동 규칙을 대신하지 않는다.
4. `enabled: false`(dev-login)만 나오고 `max:` 줄이 없어야 한다(상향 600은 `application-load-test.yml`에만, `SlidingWindowRateLimiterTest#loadTestRaiseTo600PerHourBindsOnlyWithLoadTestProfile`).
5. `0`(Core의 `bytea`는 Spring Session 속성 표 하나로 원음과 무관).

## 11. DAST 범위

- **표적형 DAST(있음).** 실행 중 스택에 보내는 공격형 요청이 검증 명령과 보충 점검에 이미 들어 있다: 인증 없는 · 틀린 쿠키 · 토큰(B4.ws · C1 · DeviceGatewayIT), CSRF 토큰 없음 · 다른 origin(C3), 위조 Origin WSS(C5), 내부 경로 · 관리 경로 · 원시 URI · 인코딩 우회(B4 · ST-08), 큰 본문(B4.body64k), 위조 X-Trace-Id · X-Forwarded-For(B4.trace · `DeviceBootstrapApiIT`), 모르는 SNI · Host(B4.sni · B4.host · ST-09), 옛 TLS 판(ST-07), 속도 제한(ST-10), 금지 표현 응답(C10). 이것들이 이번 단계의 DAST 증거다.
- **범용 스캐너(없음).** OWASP ZAP 기준선 스캔 같은 크롤링 스캐너는 도구가 없고 설계 목표도 아니다. 배포 뒤(4.3) demo 서버에 ZAP 기준선을 돌리는 것을 권고한다(측정 목표 아님, 15절 G13). 운영 서버에는 돌리지 않는다.

## 12. AI 안전 · 화면 문구 점검 — ST-24 (+ 게이트 시험)

**게이트 · 사전(NFR1.1 · NFR1.2).** 자동 증거는 ST-03(`OutputGateTest` · `SeedDictionaryTest` · `SeedDictionaryFileTest` · `SessionLifecycleIT` · `RealtimeStartupChecksTest`)과 ST-02(C9 · C10 · C11), ST-04(`player.test.ts` · `device-session.test.ts`)다. 시드 사전은 7범주 대표 패턴뿐이므로 정식 사전(U3) 전에는 실제 아동 데이터로 시연하지 않는다(NFR12.4 · D-14) — 이것은 판정 대상이 아니라 결과 보고에 남길 주의다.

**화면 문구(NFR1.3 · NFR2.1).** 문구 금지 어휘 단위 시험(SD-19-a)이 없으므로 grep으로 후보를 찾고 체크리스트로 판정한다. U1 화면 글의 단일 출처는 `frontend/src/shared/copy/ko.ts`(문구표 · `TEMP_COPY`, J118)이고 ESLint jsx-no-literals가 컴포넌트의 직접 문자열을 막는다.

```bash
# ST-24 (1) 어휘 후보 — 걸린 줄을 화면 글 · 주석 · 설명 열로 나눈다
grep -n -E '진단|치료|교정|점수|정상|비정상|등급|향상|원인|일부러|화가 났|속상|꼭 해|얼른|그만두면|연속|완료율|보상|스티커|도장|칭찬|빨리|서둘러|포기' \
  frontend/src/shared/copy/ko.ts contracts/errors.md infra/nginx/html/maintenance.html infra/seed/001-reference.sql | tee "$OUT/st24-vocab.txt"
```

작성 중 확인에서는 두 줄이 걸렸고 둘 다 화면 글이 아니다: `ko.ts:134`의 주석('연속 3회 실패')과 `contracts/errors.md:47`의 설명 열('연속 실패 · 정상 종료 중'). Step 9는 걸린 줄마다 분류를 적는다. 화면에 보이는 글이 걸리면 그 문구를 체크리스트 해당 칸에 '1'로 적는다.

```text
ST-24 (2) 체크리스트 — 결과는 test-results.md에 표로 옮긴다. 칸 값은 0(없음) 또는 걸린 문구 키.
| 화면 · 문구 묶음                       | 진단 | 점수 | 의도 | 감정 단정 | 재촉 | 만류 | 보상 | 완료율 | 출처(키) |
| D0(기기 코드 · 확인 중)                 |      |      |      |           |      |      |      |        |          |
| T19(대기 · 켜기 · 마이크 안내 · 연결 중) |      |      |      |           |      |      |      |        |          |
| 04(제목 · 대화 기기 구역 · 탭)          |      |      |      |           |      |      |      |        |          |
| 05a(등록 · 오류)                       |      |      |      |           |      |      |      |        |          |
| 08(자유대화 시작 · 사유 카드)          |      |      |      |           |      |      |      |        |          |
| 12(진행 · 말풍선 · 실패 안내)          |      |      |      |           |      |      |      |        |          |
| P1(끝내기 확인)                        |      |      |      |           |      |      |      |        |          |
| 17(끝 · 사유 · 시각)                   |      |      |      |           |      |      |      |        |          |
| 오류 code 문구 29(TEMP_COPY.errors = contracts/errors.md) |  |  |  |  |  |  |  |  |  |
| 승인 문구 CLOSING '오늘 이야기 즐거웠어. 다음에 또 만나!' |  |  |  |  |  |  |  |  | infra/seed/001-reference.sql |
| 승인 문구 WAITING_FILLER '음, 잠깐만 생각해 볼게.'         |  |  |  |  |  |  |  |  | infra/seed/001-reference.sql |
| Nginx 점검 페이지(maintenance.html)    |      |      |      |           |      |      |      |        |          |
```

판정: 모든 칸이 0이고 ST-04의 `copy.test.ts` · `d0-view.test.tsx` · `t19-view.test.tsx` · `device-machine.test.ts` · `device-app.test.tsx`(429 · 503 · 닫기 코드 1001 · 1006 · 1011 · 1013 · 4400 · 4408 뒤 마지막 화면 유지 + 정확히 5초 뒤 재시도, 기술 문구 없음)가 통과하면 NFR1.3 · NFR2.1 `Met`. 승인 음성 두 파일(`infra/seed-audio/*.wav`)은 합성 톤이라(J102, `tools/seed/gen-approved-wav.mjs`) 말소리 검토 대상이 없고 문구는 위 두 행으로 본다. 체크리스트는 사람이 다시 보도록 결과 보고에 '에이전트 검토'로 표시한다.

## 13. 이 환경에서 돌릴 수 있는 것과 뒤로 넘기는 것

**이 환경에서 돌린다(14절 ST-01 ~ ST-25 전부).** 3.5가 CI 몫으로 넘겼던 gitleaks 이력 스캔 · Trivy(bootJar · 이미지 ③) · npm audit도 같은 고정 이미지 · 설정으로 여기서 돌린다. 단계 정의상 배포 환경이 필요한 검사만 뒤로 넘길 수 있고, 이 셋은 배포 환경이 필요 없기 때문이다. GitHub Actions의 실제 실행은 4.1 deployment-pipeline에서 같은 검사를 다시 확인하는 것이지 3.6 판정의 조건이 아니다.

**뒤로 넘기는 것(이 단계 판정은 모두 `Unverified`).**

| 목표 | 이유 | 맡을 단계 | 기대 증거 |
|---|---|---|---|
| NFR9.1-d 인증서 자동 갱신 · 30일 전 로그 | 실도메인 · Let's Encrypt 없음 | 4.3 deployment-execution | `certbot renew --dry-run` 출력, 갱신 로그, `deploy.yml` S5b의 만료 20일 이상 |
| NFR9.1-e 공개 IP 바깥 포트 스캔 | EC2 · 보안 그룹 없음 | 4.2 environment-provisioning · 4.3 | 보안 그룹 80 · 443만, 바깥 스캔, `infra/bin/smoke` 8-3(80 → 301 · /actuator · /internal 403) |
| SD-2-b 실브라우저 CSP 콘솔 · AudioWorklet | 브라우저 · 실기 없음 | 소유 단계 없음(실행 계획에 실기 · 성능 검증 단계 없음, performance-validation SKIP) | 사람이 4.3 실기 점검에 넣을지 결정 |
| NFR12.1-b · SD-21-b 삭제 지휘 통합 | U2 코드 없음 | U2 Construction(이번 실행 밖) | 다섯 표 `guardian_id = :g` 0건 |
| SD-3-b 실시간 WSS 무음 60초+ Nginx 경유 | 저장소에 도구 없음(배포 환경은 필요 없음) | 15절 G4 해결 뒤 이 단계 재실행 | 앱 40초 판정이 Nginx 75초보다 먼저 |

**배포 뒤 함께 볼 것(3.6 목표의 판정에는 넣지 않음).** 배포 D3의 `infra-validate --only compose-invariants,demo-profile,secret-env-files`(실서버 env 파일 · demo 프로필), 실호스트 `/opt/mujoong/env` 0600, 호스트 스왑 없음 · `sysctl` 기준선, EBS 암호화, IAM 최소 권한(`infra/aws/iam-*.json`), 실서버 `log-hygiene-check` · `check-since-last`, ZAP 기준선(권고).

## 14. Step 9 실행 순서와 명령 목록

리드의 빌드 · 통합 실행과 같은 검증 명령 실행을 공유한다. 저장소 루트에서 이 순서로 돈다(대략 20 ~ 25분).

| 순서 | ID | 명령(절) | 기대 결과 | 목표 |
|---|---|---|---|---|
| 1 | ST-01 | `docker info --format '{{.ServerVersion}}'`(꺼져 있으면 `build-instructions.md` 3.3) · `docker image inspect zricethezav/gitleaks:v8.30.1 aquasec/trivy:0.74.0 rhysd/actionlint:1.7.12 --format '{{.RepoDigests}}'` · `df -h /` | 29.8.2, 세 이미지 있음(gitleaks · trivy 다이제스트 = `ci.yml`), 여유 공간 | 전제 |
| 2 | ST-02 | `bash scripts/verify.sh`(6절) | 종료 0, `mode=compose`, 6절 표의 줄 모두 PASS | 6절 |
| 3 | ST-03 | JUnit 추출(5.1) | `classes=59 tests=557 problems=0`, MISSING 0, ts가 Step 9 뒤 | 3절 · 4절 |
| 4 | ST-04 | 프런트 · tools 보안 시험 부분 실행(5.2) | 두 실행 종료 0 | NFR1.x · 2.1 · 9.3 · 9.11 · 9.12-a |
| 5 | ST-17 | 추적 env · 키 파일 · 브라우저 번들 grep(8절) | 둘 다 0 | NFR9.10-a · -b |
| 6 | ST-05 | `KEEP=1 bash scripts/verify.sh --engine`(6절) | 종료 0, 스택 남음 | 7절의 전제 |
| 7 | ST-06 ~ ST-12 | 앵커 · TLS 판 · 헤더 · 80 · limit_req · 관리 포트 · 로그 위생(7절) | 각 절의 기대값 | NFR9.1-a · -b · -c, 9.6, 9.8, 9.9, 9.12-a, 9.14, 12.2, SD-2-a · 3-a · 12 · 15-b |
| 8 | ST-13 | postgres 재기동(7절, 선택) | `skipping_init` 1 이상 | SD-13-b |
| 9 | ST-14 | 검증 스택 정리(7절) | 남은 컨테이너 0 | — |
| 10 | ST-15 | 백업 데이터 등급 거부(7절) — `backup` 이미지가 낡았으면 ST-20의 다시 빌드를 먼저 | REFUSED · rc=3, 대조는 B2 통과 | NFR12.4 · SD-24 |
| 11 | ST-20 | 이미지 지문 · 다시 빌드 · image-checks ① ~ ⑧(9절) | ① ~ ⑧ PASS, rc 0 | NFR9.10-b · 9.11 · 9.13 · SD-25 |
| 12 | ST-16 | gitleaks 양성 대조 · 이력(8절) | rc=7 · rc=0 | NFR9.10-a |
| 13 | ST-18 | 예외 목록 · npm audit · 관문(9절) | 두 rc 0 | NFR9.13 |
| 14 | ST-19 | Trivy 백엔드 bootJar(9절) | rc=0 | NFR9.13 |
| 15 | ST-21 | infra-validate · test-infra-validate · test-gate-negatives · test-host-tools(10절) | 네 rc 0 | NFR9.6 · 9.10-c · 9.11 · 12.2 |
| 16 | ST-22 | actionlint · SHA 고정 · 권한(9절) | rc 0 · 고정 안 된 것 0 | SD-16 |
| 17 | ST-23 | 정적 검토 grep 다섯 개(10절) | 10절의 기대와 분류 | NFR9.5 · 9.9 · 9.12-c · 12.2 · SD-9-b · SD-25 |
| 18 | ST-24 | 문구 어휘 grep + 체크리스트(12절) | 화면 글 걸림 0, 체크리스트 전부 0 | NFR1.3 · NFR2.1 |
| 19 | ST-25 | 문서 · 출처 점검(아래) | 아래 기대 | NFR12.4 |

```bash
# ST-25 문서 · 출처 점검
{
  grep -n '실제 아동 데이터 운영 전 승인(D-14)' docs/cloud_run_report.md
  grep -n "mujoong_db.data_class = 'synthetic'" infra/postgres/init/01-roles.sql
  grep -n -E '^\| 운영 전 \|' aidlc/spaces/default/intents/261008-mujoong-service/construction/u1-walking-skeleton/code-generation/code-summary.md
  grep -n -E 'synthetic|합성' infra/seed/001-reference.sql | head -n 3
} | tee "$OUT/st25-docs.txt"
```

ST-25 기대: `docs/cloud_run_report.md` 4절 9항에 '백업 충돌 해소(Q-2), 정식 금지 표현 사전 완성, 공급자 보존 조건(S-11), 기기 토큰 서버 만료 없음(S-13)'이 있다(① · ②를 덮음), init이 표지를 `synthetic`으로 둔다, `code-summary.md` ## 사람이 정할 것의 '운영 전' 행에 정식 사전 v1 · 백업 · 삭제 충돌이 있다. 승인 항목 자체는 사람이 정할 일로 남아 있다는 것을 결과에 함께 적는다.

**결과 기록(Step 9가 `test-results.md`에).** 명령 ID마다 종료 코드, 기대와 다른 줄, 원본 파일 경로(`build/b-and-t/security/st*.txt`)를 적고, 3절 목표마다 판정 하나와 근거 ID를 적는다. 값(쿠키 · 토큰 · 코드 · 전사 · 비밀번호)은 옮기지 않는다.

## 15. 알려진 틈 · 관찰 · 사다리 2단 사전 분석

Step 9에서 아래 항목이 `Unverified` · `Not Met`을 만들면 사다리 2단(분류 + 영향 추정)을 이 표로 시작한다. 모두 `--single`이라 코드 생성으로 점프하지 않고 `test-results.md`에 진단과 후보만 남긴다. 노력은 에이전트 기준 추정이고 금전 비용은 모두 0(도구 · 실행 모두 로컬 · 무료)이다.

| 번호 | 내용 | 영향 목표 | 원인 위치 | 해결 후보 · 영향 추정 |
|---|---|---|---|---|
| G1 | SD-9의 ArchUnit 규칙(보호자 소유 자원 조회가 보호자 id를 요구) 미구현. 지금은 행동 시험(`GuardianApiIT`)과 ST-23 수동 검토뿐 | SD-9-b | 생성 시험(3.5 J113 나머지 묶음) | `CoreArchTest`에 '보호자 요청 경로 클래스는 `ActivityCopyRepository.findById` 등 보호자 id 없는 조회를 부르지 않는다' 규칙 추가: 0.5 ~ 1시간, 위험 낮음(오탐이면 허용 목록) |
| G2 | 문구 금지 어휘 단위 시험 미구현(3.6 넘김) | SD-19-a | 생성 시험 | `frontend/tests/u1/shared`에 어휘 목록 파일 + Vitest 시험(ko.ts 값 · `TEMP_COPY.errors` · 승인 문구): 1 ~ 2시간, 위험은 일상어 오탐(허용 목록 필요) |
| G3 | Realtime 엔드포인트 집합 자동 시험 없음(Core는 `ContractConformanceIT`) | SD-25(Realtime 쪽 증거가 ST-23 수동) | 생성 시험 | `RealtimeInternalApiIT`에 처리기 매핑 = 계약 대조 추가: 0.5 ~ 1시간 |
| G4 | 실시간 WSS 무음 60초+ Nginx 경유 시험 도구 없음 | SD-3-b | 생성 도구 | `tools/skeleton-e2e`에 무음 유지 시나리오 추가: 1 ~ 2시간, 실행 약 80초 추가(엔진 110초 예산 밖이므로 전체 실행에만) |
| G5 | `backup.sh` 데이터 등급 거부 경로 자동 시험 없음 | SD-24(지금은 ST-15 손 절차) | 생성 점검 | `infra/ci/image-checks.sh` ④에 `data_class=real` 사례 추가: 0.5 ~ 1시간 |
| G6 | 실행 중 Nginx 단언(헤더 · TLS 판 · 80 · limit_req · 인코딩 변형)과 SQL 연결 규칙이 자동화돼 있지 않음 | 회귀 방지(ST-07 ~ ST-10 · ST-23이 통과하면 이번 판정에는 영향 없음) | 생성 스크립트 | `scripts/verify/lib.sh` 입구 단언에 추가: 1 ~ 2시간, 검증 명령 +5초 이내 추정(엔진 실행은 지금 57 ~ 70초라 110초 안). SQL 규칙은 ArchUnit 리터럴 스캐너에 더하기 0.5시간 |
| G7 | `ci/security-gate.py`와 image-checks ③의 예외 해석이 `status`를 보지 않는다 — `candidate`(사람 승인 전) braces 예외도 허용된다 | NFR9.13(측정 기준 '예외 사유가 기록'은 충족) | 생성 스크립트 · 정책 | 사람 결정(J277). 승인하면 `status: approved`로 바꾸고, 거절하면 항목을 지워 관문이 실패하게 둔다(예외 파일 머리 주석의 규칙). 관문이 `candidate`를 거부하게 고치는 것은 0.5시간이지만 승인 전 CI가 실패하므로 결정 뒤에 한다 |
| G8 | 백엔드 Trivy는 고칠 판 없는 취약점도 실패, 이미지 점검 ③은 `--ignore-unfixed`(J274) | NFR9.13 | 정책 | 사람 결정. 이번 단계는 둘 다 저장소 설정 그대로 돌리고 완화하지 않는다 |
| G9 | `DeviceWebSocketHandler.handleTransportError`가 상대가 끊은 EOFException도 ERROR로 남김 | 관측(A-7 오탐). 보안 판정 영향 없음 | 생성 코드 | 3.5 '알려진 틈 5' 그대로 보고 |
| G10 | 'D-14'가 두 뜻으로 쓰인다: `docs/cloud_run_report.md`의 '실제 아동 데이터 운영 전 승인(D-14)'과 `knowledge/documents/20_개발명세_1008/…결정기록…`의 화면 결정 D-14(기록 달력) | NFR12.4 문서 점검의 혼동 | 문서 | 사람이 승인 항목에 다른 번호를 주거나 출처를 함께 적기로 정한다(원본 문서는 이 단계에서 고치지 않음) |
| G11 | web 이미지의 `.next/standalone/.next/prerender-manifest.json`에 빌드 때 생기는 Next.js 미리보기 키가 들어간다 | 정보(U1은 draftMode 미사용) | 빌드 산출물 | GHCR 공개(J99) 결정 때 함께 보고, 필요하면 빌드마다 바뀌는 값임을 확인하거나 실행 때 주입하는 방식을 검토 |
| G12 | host 모드 Nginx 대역(`tools/dev-edge`)의 Referrer-Policy는 `no-referrer`, 운영 Nginx는 `strict-origin-when-cross-origin` | 정보(대역이 더 엄격, compose 모드에는 영향 없음) | 시험 도구 | 보고만 |
| G13 | taint 분석 SAST와 범용 DAST(ZAP)가 없다 | 측정 목표 아님 | CI 설계 | 권고: CI에 Semgrep 또는 CodeQL(PR마다, high 차단), 배포 뒤 demo에 ZAP 기준선. 맡을 곳은 4.1 deployment-pipeline 또는 사람 결정. 3.7 ci-pipeline은 이번 범위에서 SKIP |
| G14 | Compose의 core-api `depends_on: migrate … required: false` — migrate가 실패해도 Compose만으로는 앱 기동을 막지 않는다. 막는 것은 앱의 S2(`SchemaVersionCheck`, 낡은 스키마면 기동 거부)와 배포 D4(실패하면 앱을 건드리지 않음) | SD-13-b 근거 해석 | 설계 · 배포 | 정보. SD-13-b 판정 근거에 이 해석을 적는다. D4 실호스트 확인은 4.3 |
| G15 | OCSP stapling: SD-1은 '켬', 3.4 D-3이 '끔'으로 바꿈(Let's Encrypt OCSP 종료) | SD-1 기대값 | 설계 변경 | 정보. 기대값은 `ssl_stapling off` |

## 16. 작성 중 확인 기록

이 문서를 쓰며 실제로 돌린 확인이다(2026-10-11, 저장소 · 기록 파일은 고치지 않음). 보안 시험 결과가 아니므로 목표 판정에 쓰지 않고, Step 9가 같은 명령을 다시 돌려 증거를 만든다.

| 확인 | 명령(요지) | 출력(요지) |
|---|---|---|
| 도구 이미지 = CI 고정값 | `docker image inspect zricethezav/gitleaks:v8.30.1 aquasec/trivy:0.74.0 --format '{{.RepoDigests}}'` | `sha256:c00b6bd0aeb3…bbb7f` · `sha256:62b1e65e8869…1969`(`ci.yml` env와 같음) |
| gitleaks 실행 | `docker run --rm zricethezav/gitleaks:v8.30.1 version` | `v8.30.1`, rc 0 |
| Trivy DB 받기(프록시 · 검증 켬) | `docker run --rm --network host -e HTTPS_PROXY=… -e SSL_CERT_FILE=/ccr/ca-bundle.crt -v /root/.ccr/ca-bundle.crt:… -v <SP>/dso-trivy-cache:/root/.cache aquasec/trivy:0.74.0 image --download-db-only`, 같은 형태로 `--download-java-db-only` | 둘 다 rc 0(`mirror.gcr.io/aquasec/trivy-db:2` · `mirror.gcr.io/aquasec/trivy-java-db:1`), 캐시 2.9G |
| npm audit · 관문 | `(cd frontend && npm audit --json)` · `(cd tools && npm audit --json)` → `python3 -I ci/security-gate.py npm-audit …`, `… exceptions …` | frontend high 5(braces GHSA-vfj7-8cjw-p6xm 한 권고) · tools 0, 관문 '예외로 허용 … 위반 0건' rc 0, 예외 1건 유효 rc 0 |
| TLS 판 판정 방법 | 로컬 대역 `openssl s_server -no_tls1 -no_tls1_1`(버림 인증서, 루프백) | 시스템 설정 그대로 `-tls1_1` → `no protocols available`(클라이언트 거절 = 거짓 통과 위험), `OPENSSL_CONF=/dev/null … -cipher 'DEFAULT@SECLEVEL=0'` → tls1 · tls1_1 모두 `alert protocol version`, `-tls1_2` · `-tls1_3` → `New, TLSv1.2` · `New, TLSv1.3` |
| 인증서 앵커 방식 | 같은 대역에서 내놓은 인증서를 `curl --cacert`로, 대조로 다른 인증서. 검증 스택처럼 CA가 발급한 리프(fullchain에 서버 인증서만)로도 한 번 더: 버림 CA · localhost 리프 대역에서 받은 리프를 앵커로, 대조로 다른 호스트 이름 | 자체 서명: 200 / `curl: (60) SSL certificate problem`. CA 발급 리프: CA 앵커 200 · 리프 앵커 200 / 다른 이름 `curl: (60) … no alternative certificate subject name matches` — 검증이 켜진 채 동작 |
| `KEEP=1` 뒤 남는 것 | `scripts/verify.sh`의 `on_exit` · `remove_secrets` 읽기(실행 아님) | 스택은 남고 `build/verify/{env,c,certs,postgres}` · pgpass는 지워짐 → ST-06은 리프 앵커, ST-13은 재기동 전 `render-pgconf`로 설정만 다시 렌더 |
| ST-03 스크립트 | 5.1 블록을 3.5 마지막 실행 XML에 | 59클래스 · 557건 · 문제 0(ts 2026-10-10T18:4xZ) |
| 이미지 지문 | `infra/ci/source-fingerprint.sh`와 7개 이미지 라벨 | 현재 `sha256:b42ff98a…502b`, `backup`만 `sha256:980c4b06…`(낡음) |
| SQL 연결 grep | 10절 (1) | 두 앱 `DbSessionLimits.java:44` 두 줄 |
| Realtime 매핑 · findById 호출자 | 10절 (2) · (3) | 매핑 4개 + `addHandler` 1개 / `StartActivityService.java:268` · `SessionStateChangedHandler.java:70` |
| demo · prod 상향 값 | `grep` rate-limit · max · dev-login | 상향 값 없음(주석 · `dev-login.enabled false`만) |
| 워크플로 SHA 고정 · 권한 | 9절 ST-22의 grep 두 개 | `uses` 25개 모두 40자 SHA, 권한은 9절 기대와 같음 |
| 문구 어휘 grep | 12절 (1) | `ko.ts:134` 주석, `contracts/errors.md:47` 설명 열 — 화면 글 0 |
| 명령 블록 문법 | 이 문서의 `bash` 블록 24개를 뽑아 `bash -n` · `shellcheck -S warning`(블록 사이 변수 · `cd` 경고 SC2154 · SC2034 · SC2164 제외) | 24개 모두 통과 |
