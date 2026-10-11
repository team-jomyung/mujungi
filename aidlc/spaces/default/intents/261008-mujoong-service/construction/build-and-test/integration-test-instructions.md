# 통합 시험 지시 — U1 u1-walking-skeleton (3.6 Build and Test)

> 3.6 Build and Test Step 3 ~ 7 산출물(Standard: 핵심 경계 · 앱 사이 상호작용) · 2026-10-11 · 단독 실행(`--single`) · 대화 언어 한국어
>
> 입력: `code-generation-plan.md`(## Testing Contract · ## 시험 전략과 커버리지 · ## 검증 명령(C안) · J132 · J133 · J134 · J143 · J144), `unit-test-instructions.md`(## 시험 프레임워크 설정 · ## 모의 · 대역 지침 · ## 시험 데이터 관리), `code-summary.md`(## 시험 · 커버리지 · ## 검증 명령 결과 · ## 알려진 틈), `nfr-design/`(reliability-design · observability-design · logical-components · scalability-design의 `## 4`에서 단계가 Build and Test인 행), `infrastructure-design/cicd-pipeline.md`(2.2.3 · 2.3.2 · `## 4`), `infra/RUNBOOK.md` 17절.
> 이 파일은 명령 · 기대 · 증거 위치를 적는다. 실행은 Step 9, 결과는 `test-results.md`다. 보안 시험(SAST · 비밀 스캔 · 의존성 · 인증 · 주입)은 보안 담당의 `security-test-instructions.md`가 맡는다.

## 1. 범위와 원칙

- **대상.** 코드가 있는 U1만 시험한다. U2 ~ U7과 그 유닛 몫 동작(예: U2 삭제 지휘 통합, U3 ACTIVE 중 공급자 끊김)은 넣지 않는다.
- **경계 셋(계획 ## 시험 전략과 커버리지).** ① PostgreSQL(실제 `infra/postgres/init/`과 두 마이그레이션 위치) ② 앱 사이 HTTP(`contracts/schemas`로 요청 · 응답을 검사하는 계약 대역) ③ WSS(실제 Spring 서버 · 무작위 포트 핸드셰이크). 실제 두 앱 사이 HTTP · Outbox → Inbox는 Compose 검증 스택에서 끝-끝으로 본다.
- **스택 수준 시험.** 검증 명령 C안(`bash scripts/verify.sh`)과 손으로 띄운 같은 Compose 검증 스택에 대한 장애 주입(IT-06). 실공급자 · 실기기 · 실브라우저 · AWS · 실제 도메인은 쓰지 않는다(없음).
- **판정 규칙.** 각 절의 '기대'가 판정 기준이다. 기대를 바꾸거나 단언 · 하한 · 기한을 줄이지 않는다. 기존 도구로 실행할 수 없는 부분은 '실행 못 함'으로 남기고 대신할 판정을 지어내지 않는다(12절).
- **값 위생.** 비밀 · 쿠키 · 토큰 · 전사 원문을 로그 · 증거 파일 · 이 기록 디렉터리에 옮기지 않는다. 식별자(traceId · sessionId)와 건수 · 상태 코드만 옮긴다.

## 2. 시험 프레임워크와 설정

| 층 | 도구 · 위치 | 비고 |
|---|---|---|
| 백엔드 통합(IT) | JUnit 5 · Spring Boot 시험 · MockMvc, `@Tag("u1")` + PostgreSQL 필요 시 `@Tag("db")` | 유닛 시험과 같은 Gradle `test` 작업에서 돈다(태그 규칙 J106) |
| DB 백엔드 | `kr.mujoong.testsupport.db.MujoongPostgres` — `-Pmujoong.test.db=auto\|docker\|local`(J132) | 결과 보고에 `[MujoongPostgres] backend=<…>` 줄로 백엔드를 적는다(`build-instructions.md` 4.4) |
| 대역 | `ContractStubServer`(계약 스키마 검증) · `ReceiverDouble` · `FakeProviderPort` · `FakeProviderWsServer` · `DeviceSimulatorClient` · `MutableClock` · `ManualTimerDriver` | 시험 소스 집합에만 있다(SD-25 · J81) |
| 스택 끝-끝 | `scripts/verify.sh`(A → F → B → C → P1 · P2 · P3 → T), `scripts/verify/c-skeleton-turn.sh` → `tools/skeleton-e2e/run-c.mjs`, `tools/fake-provider/server.mjs` | compose 모드: Compose 프로젝트 `mujoong-verify` = `infra/compose.demo.yml` + `infra/compose.ci.yml`, 입구 nginx `127.0.0.1:8443` |
| 입구 탐침 | `infra/bin/probe-three`(health · device · wss 3종), `scripts/verify/lib.sh`의 `VERIFY_WS_PROBE`(WSS 101 → 닫기 코드) | TLS 검증 유지(자체 서명 CA `build/verify/certs/ca.crt`), 프록시 없음 |
| 인프라 정적 · 관문 | `infra/bin/infra-validate` · `infra/ci/test-infra-validate.sh` · `infra/ci/test-host-tools.sh` · `infra/ci/test-gate-negatives.sh` · `infra/ci/image-checks.sh` | 각 스크립트 머리 주석의 종료 코드 규칙(0 통과 · 1 실패 · 2 전제 누락)을 따른다 |

## 3. 시험 데이터와 환경

- **비밀은 실행 때 생성.** 검증 스택은 `infra/ci/gen-env.sh`(umask 077, `build/verify/env/`), Testcontainers는 `TestSecrets`(J127). 저장소에 값 파일이 없다.
- **시드.** 참조 시드 `infra/seed/001-reference.sql`(정책 1 · 승인 문구 2, 고정 UUID · `ON CONFLICT DO NOTHING`, 두 번 적용해도 같음, R4-15), dev 프로필의 DevSeedRunner(시드 보호자 · 아이 '하늘' · 동의 4항목, J117). 모두 합성 값이다(`mujoong_db.data_class = synthetic`).
- **origin · 도메인.** 검증 스택 `https://localhost:8443`(`SERVICE_HOST=localhost`), 단위 · 통합 `https://mujoong.invalid`(J108).
- **정리.** 스택 · 볼륨 · 생성 비밀 · CA는 시험이 끝나면 지운다(`verify.sh`는 스스로, IT-06은 IT-06k).

## 4. 실행 순서와 충돌 규칙

1. 유닛 · 통합 시험 명령(`unit-test-instructions.md`)과 그 증거 수집 → IT-01 판정.
2. IT-02 `bash scripts/verify.sh`(전체, compose) → IT-03 `bash scripts/verify.sh --engine`.
3. IT-04 인프라 정적 · 관문 음성 대조.
4. IT-05 이미지 7종 빌드 · 이미지 점검(검증 스택이 내려가 있을 때. 이미지 점검 ④의 `mujoong-imgchk`가 같은 서브넷을 쓴다).
5. IT-06 손으로 띄운 검증 스택 + 장애 주입(성능 지시의 PT-01 교차 확인도 이 스택이 떠 있을 때 한다) → IT-06k 정리.

규칙:
- `verify.sh`가 도는 동안 저장소의 어떤 파일도 쓰지 않는다(T 단계 SEC-18 · F 단계). 증거는 `build/b-and-t/`(무시 대상)에 둔다.
- IT-06은 `verify.sh`와 같은 Compose 프로젝트 이름 · 포트(8443)를 쓴다. 둘을 동시에 돌리지 않는다. `verify.sh`는 시작할 때 같은 프로젝트를 내린다.
- 각 절의 증거 파일은 `build/b-and-t/<ID>/`에 둔다. `test-results.md`에는 ID · 기대 · 실제 · 증거 경로를 옮긴다.

## 5. IT-01 핵심 경계 통합 시험(유닛 범위 명령 안)

**명령.** 별도 명령이 없다. `unit-test-instructions.md`의 백엔드 U1 전체 명령(`cd backend && ./gradlew --no-daemon :contract-model:test :core-api:test :realtime-activity:test :test-support:test -PincludeTags=u1`)이 아래 IT를 함께 돈다. Step 9는 그 명령을 한 번만 돌리고 결과 XML로 판정한다.

| 경계 | 클래스(모듈) |
|---|---|
| ① PostgreSQL(실제 init 스크립트 · 두 위치 · 역할별 DataSource) | core-api: `CoreSchemaIT` · `SchemaPrivilegeIT` · `CoreConstraintIT` · `SeedReferenceSqlIT` · `FlywayConfIT` · `DeviceJdbcRepositoryIT` · `CoreMessagingRepositoryIT` · `ActivityCopyRepositoryIT`, realtime-activity: `RealtimeSchemaIT` · `RealtimeConstraintIT` · `DeviceConnectionRepositoryIT` · `RealtimeMessagingRepositoryIT` · `ActivitySessionRepositoryIT` · `SessionRecordsRepositoryIT` · `TranscriptRepositoryIT`, test-support: `MujoongPostgresTest` |
| ② 앱 사이 HTTP(계약 대역 · 계약 스키마) | test-support: `ContractStubServerTest`, core-api: `ContractConformanceIT` · `CoreInternalApiIT` · `ActivityStartEndIT` · `SessionPackageIT` · `CoreMessagingFlowIT`, realtime-activity: `RealtimeInternalApiIT` · `RealtimeMessagingIT` |
| ③ WSS(실제 서버 · 무작위 포트) · 공급자 WS | realtime-activity: `DeviceGatewayIT` · `SessionLifecycleIT` · `ProviderClientIT` · `ProviderRequestWhitelistIT` |
| 보안 체인 · 오류 봉투 · 기동 | core-api: `SecurityChainIT` · `ErrorEnvelopeAndLimitsIT` · `DeviceBootstrapApiIT` · `DeviceRegisterApiIT` · `GuardianApiIT` · `HealthAndReadinessIT` · `CoreLogHygieneIT` · `ProfileGuardBootIT` · `CoreStartupSequenceIT`, realtime-activity: `RealtimeLogHygieneIT` · `ProfileGuardBootIT` · `RealtimeStartupRecoveryIT` |

**판정 명령**(유닛 명령 뒤, 검증 명령 전):

```bash
python3 -I - <<'PY' | tee build/b-and-t/IT-01/boundary-classes.txt
import glob, xml.etree.ElementTree as ET
want = """CoreSchemaIT SchemaPrivilegeIT CoreConstraintIT SeedReferenceSqlIT FlywayConfIT DeviceJdbcRepositoryIT
CoreMessagingRepositoryIT ActivityCopyRepositoryIT RealtimeSchemaIT RealtimeConstraintIT DeviceConnectionRepositoryIT
RealtimeMessagingRepositoryIT ActivitySessionRepositoryIT SessionRecordsRepositoryIT TranscriptRepositoryIT MujoongPostgresTest
ContractStubServerTest ContractConformanceIT CoreInternalApiIT ActivityStartEndIT SessionPackageIT CoreMessagingFlowIT
RealtimeInternalApiIT RealtimeMessagingIT DeviceGatewayIT SessionLifecycleIT ProviderClientIT ProviderRequestWhitelistIT
SecurityChainIT ErrorEnvelopeAndLimitsIT DeviceBootstrapApiIT DeviceRegisterApiIT GuardianApiIT HealthAndReadinessIT
CoreLogHygieneIT ProfileGuardBootIT CoreStartupSequenceIT RealtimeLogHygieneIT RealtimeStartupRecoveryIT""".split()
found = {}
for f in glob.glob('backend/*/build/test-results/test/TEST-*.xml'):
    s = ET.parse(f).getroot()
    bad = int(s.get('failures', 0)) + int(s.get('errors', 0))
    found.setdefault(s.get('name').rsplit('.', 1)[-1], []).append(f"{f.split('/')[1]}:tests={s.get('tests')},bad={bad},skipped={s.get('skipped')}")
missing = [w for w in want if w not in found]
for w in want:
    print(w, ' '.join(found.get(w, ['MISSING'])))
print('missing:', len(missing))
PY
```

(`mkdir -p build/b-and-t/IT-01`을 먼저 한다.)

**기대.** 목록의 모든 클래스가 `MISSING` 없이 있고 각각 `tests` ≥ 1 · `bad=0` · `skipped=0`. DB 백엔드 줄(`[MujoongPostgres] backend=…`)을 함께 적는다.

## 6. IT-02 검증 명령 C안 전체 (compose)

**명령.** `bash scripts/verify.sh` (저장소 루트, Docker가 있으므로 auto → compose. `--mode`를 바꾸지 않는다. host 모드는 Docker 데몬을 띄우지 못할 때만 사람 결정 11로 인정되며 그때도 `NOT_VERIFIED(host)` 줄을 보고에서 빼지 않는다.)

**증거.** 표준 출력 전체를 `build/b-and-t/IT-02/stdout.txt`로, 끝난 뒤 `cp build/verify/report.txt build/b-and-t/IT-02/report.txt`, 종료 코드를 `build/b-and-t/IT-02/rc.txt`에 남긴다. 단계 로그는 `build/verify/logs/`에 있고 다음 실행이 지우므로 필요한 줄(시험 수 · 커버리지 NOTE · 단언 줄)은 이 폴더로 복사한다.

**기대(종료 코드 0, `report.txt` 첫 줄 `mode=compose`, 마지막 줄 `RESULT PASS rc=0`).** 단계별:

| 단계 | 기대 | 걸리는 목표 |
|---|---|---|
| A1 | `gradle clean check verifyBootJarHasNoTestClasses` 통과(태그 필터 없는 전체 회귀, 세 모듈 JaCoCo LINE 0.80, ArchUnit · 설정 키 대조), `A1.tests` 실패 0, `A1.bootjar` 두 jar 시험 항목 0 | BT-TC-05 · BT-TC-07 · BT-NFR6.1 · BT-NFR6.4 |
| A2 | `npm ci` · `eslint .` · `vitest run tests/u1 --coverage`(src/** 줄 80%) · `next build` 통과, `A2.coverage` NOTE에 줄 비율 | BT-TC-06 · BT-NFR11.1 |
| A3 | tools U1 시험 통과 | BT-TC-09 |
| A4 | 비밀 스캔 ①(키 형식) · 무시 규칙 ② 걸림 0 | BT-NFR9.10(보안 담당과 공유) |
| F | A 동안 소스 지문 불변, `build/verify/fingerprint` 기록 | BT-VC-01 |
| B | 이미지 5종 빌드(B0) · 병합 설정 단언(B1: 게시 포트 nginx `127.0.0.1:8443` 하나 · 서비스별 비밀 이름 = 분배표 · certbot · backup 꺼짐 · 공급자 = 가짜) · postgres → migrate → 시드 두 번(정책 1 · 문구 2, B2) · 프로필 가드 이미지 기동 거부 4건(B5) · 전체 healthy(B3) · 입구 단언(B4: health 200 UP · `/device` 200 · WSS 101 → 4401 · `/internal/x` · `/actuator/health` 403 · 64KB+1 → 400 `VALIDATION_FAILED` · 위조 `X-Trace-Id` 미사용 · `/api/..;/internal/x` 400 · SNI · Host `other.invalid` 거절) | BT-NFR9.6 · BT-NFR9.9 · BT-NFR9.11 · BT-NFR4.13 · BT-NFR5.7 |
| C | C1 ~ C12 모두 PASS(D0 → 시험 로그인 → 05a(CSRF · Origin 거부 포함) → claim → 켜기(악성 Origin 403) → 04 STABLE → 08 → 시작 → 음성 1턴(입력 · 출력 해시 일치) → 게이트 경유 증명(금지 턴 OUTPUT_BEGIN 0 · 0x02 0바이트 · WAITING_FILLER 1 · `approved_for_display` false) → P1(STOP_OUTPUT 뒤 0x02 0바이트 · CLOSE_TRANSPORT 뒤 입력이 공급자에 닿지 않음) → ENDED confirmed · LATENCY_TURN · SESSION_ITEMS_SERVED 키 존재) | BT-VC-01 · BT-NFR1.1 · BT-NFR9.2 · BT-NFR9.3 · BT-NFR9.4 · BT-NFR5.3b · BT-NFR5.4 |
| P1 | 교차 스키마 INSERT · 앱 역할 CREATE TABLE이 SQLSTATE 정확히 42501(6건) | BT-NFR9.7(B1 ②) |
| P2 | STARTUP_RECOVERY · SESSION_TRANSITION(ENDED) · LATENCY_TURN · Nginx 접근 로그 줄 존재 + 비밀 목록 값이 로그 · 보고 · 표준 출력에 0건 | BT-NFR9.8(B1 ③) · BT-NFR9.15 |
| P3 | 생성 · 본 비밀 값이 추적 · 무시되지 않은 파일에 0건 | BT-NFR9.10 |
| T | 작업 트리가 시작과 같다 | BT-VC-01 |

C 단계의 측정 값(C6 04 STABLE 도달 시간, C9 · C10 · C11 소요)은 J131에 따라 기록만 하고 판정하지 않는다. 성능 지시 PT-02가 이 값을 기준선으로 옮긴다.

## 7. IT-03 엔진 검증 명령 (`--engine`)

**명령.** `bash scripts/verify.sh --engine` — IT-02가 종료 코드 0으로 끝난 뒤, 소스를 고치지 않은 상태에서 바로 돈다(엔진 체크포인트에 기록된 명령과 글자가 같다: `verification-command.txt`).

**기대.** 종료 코드 0, `report.txt`에 `engine=1` · `E0 PASS`(미리 만든 이미지 라벨 지문 = 지금 소스 지문) · B · C · P1 · P2 · P3 · T PASS, 마지막 `RESULT PASS`. 내부 기한 110초 안(엔진 제한 120초), 표준 출력 크기 1 MiB 미만(`wc -c`로 잰다). 증거: `build/b-and-t/IT-03/{stdout.txt,report.txt,rc.txt,elapsed.txt}`(`/usr/bin/time -f %e` 또는 `date +%s` 차이).

## 8. IT-04 인프라 정적 검사 · 관문 음성 대조

| ID | 명령 | 기대 |
|---|---|---|
| IT-04a | `bash infra/bin/infra-validate` | 8개 검사(compose-config · compose-invariants · demo-profile · secret-env-files · nginx · shellcheck · migrations · bundle) 모두 `PASS`, 종료 코드 0. `MISSING`이 있으면 종료 코드 2이며 통과가 아니다 |
| IT-04b | `bash infra/ci/test-infra-validate.sh` | 모든 사례 `ok`(위반 샘플은 실패, 정상 샘플은 통과), 종료 코드 0 |
| IT-04c | `bash infra/ci/test-host-tools.sh` | 모든 사례 `ok`(render-env 분배 · D3 프로필 확인 · check-since-last · log-hygiene-check · attrs 허용 표 대조), 종료 코드 0 |
| IT-04d | `bash infra/ci/test-gate-negatives.sh` | 관문 5종(secrets · compose · nginx · migrations · eslint) 모두 양성 대조 통과 · 음성 대조 실패 = `PASS`, 종료 코드 0 |

걸리는 목표: BT-CD-INV(Compose 불변식 1 ~ 12) · BT-CD-01(관문이 실제로 막음) · BT-NFR7.4(서비스당 1개, 불변식 9) · BT-NFR11.1(경계 lint 음성 대조). 취약 의존성 · 커버리지 하락 · `ci-gate` 경로 필터 관문은 GitHub Actions 실행이 필요해 여기서 돌지 않는다(12절).

## 9. IT-05 이미지 7종 빌드와 이미지 점검

**명령**(검증 스택이 내려가 있을 때):

```bash
bash infra/ci/build-images.sh --extra-ca /root/.ccr/ca-bundle.crt
bash infra/ci/image-checks.sh
```

**기대.** 빌드 종료 코드 0(7종, 라벨 지문 = 지금 소스 지문, `build-instructions.md` BV-4). 이미지 점검: ① 이미지 설정 · 우리 파일 · 브라우저 번들에 비밀 이름 · 키 형식 · 프록시 · CA 값 0 ② 비루트 · 고정 태그 ④ demo 프로필 더미 기동(postgres 999 · cap_drop · read_only → migrate → 시드 두 번 → 두 앱 readiness UP → backup 스케줄러 생존 + 더미 덤프 1회 OK → 프로필 가드 · 필수 비밀 누락 거부) ⑤ 번들 파일 목록 · 번들 불변식 ⑥ 앱 이미지 시험 클래스 0 ⑦ web 실제 옵션 기동 `127.0.0.1:3000/healthz` 200 ⑧ 업스트림 없는 구성에서 `/` · `/api/health` · `/ws/device` 상태 502 유지(본문 점검 페이지 · `UPSTREAM_UNAVAILABLE`) · 앱이 낸 503은 그대로 통과 — 모두 `PASS`. ③(Trivy)은 실행 파일이 없으면 `SKIP`으로 나오고 실패로 세지 않는다. ③의 판정은 보안 담당 파일(`security-test-instructions.md`)이 정한 방법으로 따로 한다.

걸리는 목표: BT-CD-IMG · BT-NFR4.13(⑧) · BT-NFR4.11a(④ 백업 스케줄러 · 더미 덤프, 부분) · BT-NFR9.10(①, 보안 담당과 공유) · BT-NFR9.11(④).

## 10. IT-06 손으로 띄운 검증 스택과 장애 주입

검증 명령은 끝에 스택과 자체 서명 CA를 지우므로, 장애 주입은 같은 구성의 스택을 손으로 띄워 CA를 둔 채 한다. 구성 · 순서는 `infra/compose.ci.yml` 머리 주석과 `scripts/verify/b-stack.sh` 그대로다(새 도구를 만들지 않는다). 모든 명령은 저장소 루트에서 한 셸로 이어서 돈다.

### IT-06a 준비 · 기동 · 한 턴

```bash
export NO_PROXY=localhost,127.0.0.1,::1 no_proxy=localhost,127.0.0.1,::1
P=mujoong-verify
DC="docker compose -p $P --env-file build/verify/env/compose.env -f infra/compose.demo.yml -f infra/compose.ci.yml"
VC="curl -sS --noproxy localhost,127.0.0.1 --cacert build/verify/certs/ca.crt --max-time 15"
B=https://localhost:8443; O=https://localhost:8443
OUT=build/b-and-t/IT-06; mkdir -p "$OUT" build/verify/logs
docker compose -p "$P" down -v --remove-orphans -t 3            # 남은 스택 정리
bash infra/ci/gen-env.sh --out build/verify/env --service-host localhost --origin "$O" --registry mujoong.local --tag verify
bash infra/ci/gen-selfsigned.sh --out build/verify/certs --host localhost
bash infra/bin/render-nginx --out build/verify/nginx --env-file build/verify/env/nginx.env
bash infra/bin/render-pgconf --out build/verify/postgres/postgresql.conf
chmod -R go+rX build/verify/nginx build/verify/postgres
$DC --profile migrate config --quiet                              # 출력은 내지 않는다(값 펼침 금지)
$DC up -d --wait --wait-timeout 120 postgres
$DC --profile migrate run --rm migrate
for i in 1 2; do
  docker run --rm -i --network "${P}_backend" --cap-drop ALL --security-opt no-new-privileges:true \
    --env-file build/verify/env/migrate.env -v "$PWD/infra/seed:/seed:ro" --entrypoint sh postgres:16.15 -c \
    'PGPASSWORD="$DB_OWNER_PASSWORD" exec psql -X -q -h postgres -U mujoong_owner -d mujoong -v ON_ERROR_STOP=1 --single-transaction -f /seed/001-reference.sql'
done
$DC up -d --wait --wait-timeout 240
$DC ps --format '{{.Service}} {{.State}} {{.Health}}' | tee "$OUT/ps-start.txt"
bash scripts/verify/c-skeleton-turn.sh --mode compose 2>&1 | tee "$OUT/c-turn.txt"
```

- 이미지는 IT-02 · IT-05가 만든 `mujoong.local/mujoong-*:verify`를 그대로 쓴다(`pull_policy: never`).
- **기대.** 모든 서비스 running/healthy, C1 ~ C12 PASS. 이 한 턴으로 등록된 기기 · 끝난 세션 · 앱 로그 · SQL 래퍼(`build/verify/c/sql.sh`, 비밀 없음)가 생긴다. 이후 절은 이 상태를 쓴다.
- 쿠키 파일은 `build/verify/c/`(0700) 안에만 두고 IT-06k에서 지운다.

### IT-06b 지표 노출 · 라벨 위생 (NFR4.15 · NFR7.8 · OD-5 · OD-11 · OD-12 · NFR5.5c)

```bash
for s in core-api realtime; do docker exec "$P-$s-1" curl -fsS --max-time 5 http://127.0.0.1:9090/actuator/prometheus >"$OUT/prom-$s.txt"; done
for n in mujoong_health_up mujoong_session_ended_total mujoong_session_activated_total mujoong_session_start_to_active_seconds \
  mujoong_timeout_fired_total mujoong_internal_http_requests_total mujoong_internal_http_seconds mujoong_internal_http_breaker_state \
  mujoong_device_connections_open mujoong_device_connection_state mujoong_outbox_pending mujoong_outbox_oldest_pending_seconds \
  mujoong_outbox_failed mujoong_inbox_dead mujoong_event_delivery_seconds mujoong_relay_in_seconds mujoong_relay_out_seconds \
  mujoong_turn_first_audio_seconds mujoong_view_item_ready_seconds mujoong_session_view_fallback_total mujoong_db_pool_wait_exceeded_total \
  mujoong_security_event_total mujoong_sessions_active mujoong_queue_depth jvm_memory_used_bytes jvm_gc_pause_seconds \
  jvm_threads_live_threads hikaricp_connections_active hikaricp_connections_pending http_server_requests_seconds; do
  printf '%s core=%s realtime=%s\n' "$n" "$(grep -c "^# TYPE $n " "$OUT/prom-core-api.txt")" "$(grep -c "^# TYPE $n " "$OUT/prom-realtime.txt")"
done | tee "$OUT/metric-names.txt"
grep -rhoE '"mujoong(\.[a-z_]+)+"' backend/core-api/src/main backend/realtime-activity/src/main | sort -u >"$OUT/metric-names-in-code.txt"
grep -h '^http_server_requests_seconds_count' "$OUT"/prom-*.txt | grep -oE 'uri="[^"]*"' | sort -u | tee "$OUT/uri-labels.txt"
grep -cE 'uri="[^"]*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-' "$OUT/uri-labels.txt"     # 0이어야 한다
for p in /actuator/prometheus /actuator/health /actuator/env /actuator/heapdump; do printf '%s %s\n' "$p" "$($VC -o /dev/null -w '%{http_code}' "$B$p")"; done | tee "$OUT/actuator-external.txt"
$VC -o "$OUT/health-body.json" -w '%{http_code}\n' "$B/api/health"; jq -c . "$OUT/health-body.json"
sleep 65    # 스냅샷 주기(60초) 한 번을 넘긴다
for s in core-api realtime; do printf '%s METRIC_SNAPSHOT=%s\n' "$s" "$(docker logs "$P-$s-1" 2>&1 | jq -Rc 'fromjson? | select(.event=="METRIC_SNAPSHOT")' | wc -l)"; done | tee "$OUT/metric-snapshot.txt"
```

- **기대.** observability-design 2.5 표(OD-5)와 OD-12 매핑의 이름이 발행 앱에 있다(`# TYPE` 줄). 스크랩에 없지만 코드 등록 이름 목록(`metric-names-in-code.txt`)에 있는 것은 '사건이 아직 없어 미등록'으로, 둘 다 없으면 '없음'으로 분류해 적는다(Micrometer는 태그 있는 지표를 처음 쓸 때 등록한다). `http_server_requests` uri 라벨은 경로 템플릿뿐(UUID 0건). 외부 `/actuator/*`는 모두 403, `/api/health`는 200이고 본문에 구성 요소 상세가 없다(`{"status":"UP"}` 꼴). METRIC_SNAPSHOT(OD-6)은 앱마다 60초에 한 줄 이상이고 attrs가 숫자 키뿐이다.

### IT-06c traceId 끝-끝 전파 (NFR5.7 · OD-7)

```bash
T=$(docker logs "$P-core-api-1" 2>&1 | jq -Rr 'fromjson? | select(.event=="SESSION_TRANSITION" and .attrs.to=="REQUESTED") | .traceId' | tail -n 1)
echo "trace=$T" | tee "$OUT/trace.txt"
docker logs "$P-core-api-1" 2>&1 | jq -Rr --arg t "$T" 'fromjson? | select(.traceId==$t) | .event' | sort | uniq -c | tee "$OUT/trace-core.txt"
docker logs "$P-realtime-1" 2>&1 | jq -Rr --arg t "$T" 'fromjson? | select(.traceId==$t) | .event' | sort | uniq -c | tee "$OUT/trace-realtime.txt"
docker logs "$P-nginx-1" 2>&1 | grep -c "requestId=$T " | tee "$OUT/trace-nginx.txt"
printf "SELECT event_type, status FROM realtime.realtime_outbox_event WHERE trace_id = '%s';\n" "$T" | bash build/verify/c/sql.sh realtime | tee "$OUT/trace-rt-outbox.txt"
printf "SELECT event_type, status FROM core.core_inbox_event WHERE trace_id = '%s';\n" "$T" | bash build/verify/c/sql.sh core | tee "$OUT/trace-core-inbox.txt"
```

- **기대.** C 단계 시작 요청(08)의 traceId `T`(32자 소문자 16진수)가 Nginx 접근 로그(`requestId=T`) · Core 로그(SESSION_TRANSITION · INTERNAL_HTTP 등) · Realtime 로그 · `realtime_outbox_event.trace_id` · `core_inbox_event.trace_id`에서 같다. 바깥에서 온 위조 값은 쓰지 않는다(IT-02 B4.trace). traceId는 식별자라 증거에 적어도 된다.

### IT-06d Realtime 정지 — 장애 격리 · 확인 불가 · 사본 응답 (NFR4.12 · NFR4.3 · RD-15 · LC-12 · NFR5.5c)

```bash
J=build/verify/c/it06.jar; (umask 077; : >"$J")
$VC -c "$J" -b "$J" -X POST -H "Origin: $O" -o /dev/null -w 'login %{http_code}\n' "$B/api/dev/login"
CSRF=$($VC -c "$J" -b "$J" "$B/api/session/csrf" | jq -r .token)        # 값은 변수에만
SID=$(printf 'SELECT session_id FROM core.core_activity ORDER BY requested_at DESC LIMIT 1;\n' | bash build/verify/c/sql.sh core)
docker exec "$P-core-api-1" curl -fsS http://127.0.0.1:9090/actuator/prometheus | grep -E '^mujoong_(session_view_fallback_total|internal_http_requests_total)' >"$OUT/prom-core-before-rt-stop.txt" || true
docker stop -t 40 "$P-realtime-1"; t_stop=$(date +%s)
$VC -b "$J" "$B/api/home" | jq -c '{net: .device.network.state, verdict: .device.verdict}' | tee "$OUT/home-rt-stop-0s.json"
$VC -b "$J" -o "$OUT/view-rt-stop.json" -w 'view %{http_code}\n' "$B/api/sessions/$SID?sinceSeq=0"
s=$(( 36 - ($(date +%s) - t_stop) )); [ "$s" -gt 0 ] && sleep "$s"
$VC -b "$J" "$B/api/home" | jq -c '{net: .device.network.state, verdict: .device.verdict}' | tee "$OUT/home-rt-stop-36s.json"
$VC -b "$J" -X POST -H "Origin: $O" -H "X-CSRF-TOKEN: $CSRF" -H 'Content-Type: application/json' \
  -d "{\"idempotencyKey\":\"$(cat /proc/sys/kernel/random/uuid)\",\"context\":{\"situationType\":\"FRIEND\",\"sourceType\":\"SEEN\"}}" \
  -o "$OUT/start-rt-stop.json" -w 'start %{http_code}\n' "$B/api/activities/free"; jq -c '{code}' "$OUT/start-rt-stop.json"
docker exec "$P-core-api-1" curl -fsS http://127.0.0.1:9090/actuator/prometheus | grep -E '^mujoong_(session_view_fallback_total|internal_http_requests_total)' >"$OUT/prom-core-after-rt-stop.txt" || true
docker start "$P-realtime-1"
timeout 120 bash -c "until [ \"\$(docker inspect -f '{{.State.Health.Status}}' $P-realtime-1)\" = healthy ]; do sleep 1; done"
```

- **기대.** Realtime이 멈춰도 Core 보호자 API가 답한다(시험 로그인 200, `/api/home` 200). 12 조회는 200으로 사본에서 답한다(Realtime 호출 실패 시 사본, BR6.15). 정지 30초가 지나면 `device.network.state`가 `UNKNOWN`, `verdict.canStart` false · `reasonCode` `DEVICE_STATUS_UNKNOWN`이고 새 시작은 423 `DEVICE_NOT_STABLE`. 정지 뒤 Core의 `mujoong_internal_http_requests_total`에서 실패 outcome(`conn_error` · `timeout`)이 늘고, 12 조회를 사본으로 답했다면 `mujoong_session_view_fallback_total`이 는다. 재기동 뒤 healthy.
- **한계.** C 한 턴이 끝나면 기기가 연결을 닫으므로 '정지 직후 30초 이내 시작은 503'(기기가 STABLE이어야 함)과 '끝내기 503'(진행 중 세션이 있어야 함)은 이 도구로 만들 수 없다(12절).

### IT-06e Core 정지 — 입구 502 · 보고 루프 실패 (LC-3 일부 · RD-16 일부)

```bash
docker stop -t 40 "$P-core-api-1"
$VC -o "$OUT/health-core-stop.json" -w 'health %{http_code}\n' "$B/api/health"; jq -c '{code}' "$OUT/health-core-stop.json"
bash -c '. scripts/verify/lib.sh; node --input-type=module -e "$VERIFY_WS_PROBE" wss://localhost:8443/ws/device https://localhost:8443 build/verify/certs/ca.crt "$PWD/tools/package.json"' | tee "$OUT/ws-core-stop.json"
sleep 25
docker exec "$P-realtime-1" curl -fsS http://127.0.0.1:9090/actuator/prometheus | grep -E '^mujoong_internal_http_requests_total' >"$OUT/prom-rt-core-stop.txt" || true
docker start "$P-core-api-1"
timeout 180 bash -c "until [ \"\$(docker inspect -f '{{.State.Health.Status}}' $P-core-api-1)\" = healthy ]; do sleep 1; done"
```

- **기대.** Core 컨테이너가 없으면 Nginx가 502와 `UPSTREAM_UNAVAILABLE` 봉투를 답한다(업스트림 접속 불가일 때만 502). Realtime은 계속 살아 WSS 업그레이드를 받는다(쿠키 없는 연결은 101 → 4401). Realtime의 `device_status` 보고 호출 실패 outcome이 는다. 재기동 뒤 healthy.
- **한계.** 기기 쿠키가 있는 연결이 1013으로 닫히는지(Core 토큰 확인 불가), 진행 중 세션 유지, Realtime Outbox 적체 · 재시도는 기기 · 세션을 이어 가는 드라이버가 없어 여기서 만들 수 없다(12절).

### IT-06f DB 정지 — 앱이 직접 503 · 복귀 (NFR4.13 · RD-16 · OD-11 · LC-5 일부)

```bash
docker exec "$P-realtime-1" curl -s -o /dev/null -w 'rt-internal-ready %{http_code}\n' -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8081/internal/events | tee "$OUT/internal-before-db-stop.txt"
docker stop -t 60 "$P-postgres-1"; t0=$(date +%s)
for i in $(seq 1 40); do c=$($VC -o "$OUT/health-db-stop.json" -D "$OUT/health-db-stop.h" -w '%{http_code}' "$B/api/health"); [ "$c" = 503 ] && break; sleep 1; done
echo "health=$c after=$(( $(date +%s) - t0 ))s" | tee "$OUT/health-db-stop.txt"; jq -c . "$OUT/health-db-stop.json"; grep -i '^retry-after:' "$OUT/health-db-stop.h"
docker exec "$P-realtime-1" curl -s -o /dev/null -w 'rt-internal-notready %{http_code}\n' -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8081/internal/events | tee -a "$OUT/health-db-stop.txt"
docker exec "$P-core-api-1" curl -s -o /dev/null -w 'core-internal-notready %{http_code}\n' -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8080/internal/events | tee -a "$OUT/health-db-stop.txt"
docker start "$P-postgres-1"; t1=$(date +%s)
for i in $(seq 1 90); do c=$($VC -o /dev/null -w '%{http_code}' "$B/api/health"); [ "$c" = 200 ] && break; sleep 1; done
echo "recover health=$c after=$(( $(date +%s) - t1 ))s" | tee -a "$OUT/health-db-stop.txt"
```

- **기대.** 실행 중 DB가 멈추면 DB 점검자(5초 × 연속 3회)가 readiness를 내리고 `/api/health`는 Nginx 502가 아니라 **앱이 직접 503**을 답한다(Nginx는 503을 만들지 않는다, `infra/nginx/snippets/error-json.conf`) — 본문에 상세 없음, `Retry-After` 5. 두 앱의 `/internal/events`도 503(READY일 때 토큰 없는 호출은 401이어야 대조가 된다). DB가 돌아오면 점검 1회 성공으로 READY → `/api/health` 200.

### IT-06g 강제 종료 자동 복구 3회 · 응답 불능(hang) 기록 (NFR4.9 · RD-17 · LC-12 일부 · MO-10 일부)

```bash
for svc in realtime core-api realtime; do
  c="$P-$svc-1"; rc0=$(docker inspect -f '{{.RestartCount}}' "$c")
  infra/bin/probe-three --host localhost --connect 127.0.0.1 --port 8443 --cacert build/verify/certs/ca.crt --interval 1 --duration 150 \
    --out "$OUT/probe-crash-$svc-$rc0.log" >/dev/null 2>&1 &
  pp=$!; sleep 3; t0=$(date +%s.%N)
  kill -9 "$(docker inspect -f '{{.State.Pid}}' "$c")"          # docker kill · stop은 수동 정지로 보아 재시작 정책이 돌지 않는다
  timeout 300 bash -c "until [ \"\$(docker inspect -f '{{.RestartCount}}' $c)\" -gt $rc0 ] && [ \"\$(docker inspect -f '{{.State.Health.Status}}' $c)\" = healthy ]; do sleep 0.5; done"
  rc=$?; t1=$(date +%s.%N)
  printf '%s rc=%s recoverSec=%s restartCount=%s->%s\n' "$svc" "$rc" "$(python3 -c "print(round($t1-$t0,1))")" "$rc0" "$(docker inspect -f '{{.RestartCount}}' "$c")" | tee -a "$OUT/crash-recovery.txt"
  wait "$pp"; infra/bin/probe-three --summarize "$OUT/probe-crash-$svc-$rc0.log" | tee -a "$OUT/crash-recovery.txt"
  docker logs --since 5m "$c" 2>&1 | jq -Rc 'fromjson? | select(.event=="STARTUP_RECOVERY") | .attrs' | tail -n 1 | tee -a "$OUT/crash-recovery.txt"
done
docker pause "$P-realtime-1"; sleep 45
docker inspect -f 'paused={{.State.Paused}} health={{.State.Health.Status}} restartCount={{.RestartCount}}' "$P-realtime-1" | tee "$OUT/hang.txt"
docker unpause "$P-realtime-1"
timeout 120 bash -c "until [ \"\$(docker inspect -f '{{.State.Health.Status}}' $P-realtime-1)\" = healthy ]; do sleep 1; done"
```

- `rc=0`이 아니면(300초 안에 healthy가 되지 않음) 그 회차는 기대 미달로 적는다.
- **기대.** 세 번 모두 `restart: unless-stopped`로 다시 올라와 healthy가 되기까지 **5분 이내(목표 1분)**. 재기동마다 HTTP 수신 전에 시작 복구가 돌아 STARTUP_RECOVERY 한 줄(recoveredSessions · reopenedInboxRows · resetConnections · rearmedControlEvents)이 남는다(NFR4.7의 스택 확인분). 앱이 죽어 있는 동안 입구 탐침은 해당 호출을 실패로 기록하고(`probe-three` 요약의 downtimeSec) 복구 뒤 OK로 돌아온다. hang(일시정지)은 health가 unhealthy가 돼도 재시작 수가 늘지 않는다 — 자동 복구가 없음을 기록한다(RD-17 · 설계상 한계, 판정 아님).

### IT-06h web 정지 격리 (LC-2 일부)

```bash
docker stop -t 10 "$P-web-1"
for p in /device /api/health; do printf '%s %s\n' "$p" "$($VC -o /dev/null -w '%{http_code}' "$B$p")"; done | tee "$OUT/web-stop.txt"
bash -c '. scripts/verify/lib.sh; node --input-type=module -e "$VERIFY_WS_PROBE" wss://localhost:8443/ws/device https://localhost:8443 build/verify/certs/ca.crt "$PWD/tools/package.json"' | tee -a "$OUT/web-stop.txt"
docker start "$P-web-1"
timeout 120 bash -c "until [ \"\$(docker inspect -f '{{.State.Health.Status}}' $P-web-1)\" = healthy ]; do sleep 1; done"
```

- **기대.** web이 멈추면 화면(`/device`)만 502(점검 페이지)이고 `/api/health` 200 · WSS 101 → 4401은 그대로다. '진행 중 세션 정상'은 세션 드라이버가 없어 보지 못한다.

### IT-06i 도달성 · 망 분리 (LC-5 · LC-13 · Compose 불변식 7)

```bash
for s in nginx web core-api realtime postgres fake-provider; do printf '%s %s\n' "$s" "$(docker inspect -f '{{json .NetworkSettings.Networks}}' "$P-$s-1" | jq -c 'keys')"; done | tee "$OUT/networks.txt"
PGIP=$(docker inspect -f "{{(index .NetworkSettings.Networks \"${P}_backend\").IPAddress}}" "$P-postgres-1")
docker exec "$P-web-1" node -e "const s=require('net').connect({host:'$PGIP',port:5432,timeout:3000});s.on('connect',()=>{console.log('OPEN');process.exit(0)});s.on('error',e=>{console.log('CLOSED',e.code);process.exit(0)});s.on('timeout',()=>{console.log('CLOSED timeout');process.exit(0)})" | tee "$OUT/web-to-postgres.txt"
docker exec "$P-nginx-1" sh -c '(getent hosts postgres || nslookup postgres) >/dev/null 2>&1 && echo "nginx resolves postgres" || echo "nginx cannot resolve postgres"' | tee "$OUT/nginx-to-postgres.txt"
for s in core-api realtime web nginx; do printf '%s %s\n' "$s" "$(docker exec "$P-$s-1" sh -c 'env | cut -d= -f1 | grep -cxE "DB_OWNER_PASSWORD|POSTGRES_PASSWORD"' || true)"; done | tee "$OUT/owner-secret-names.txt"
```

- **기대.** nginx · web은 `mujoong-verify_edge`에만, postgres는 `mujoong-verify_backend`에만 붙어 있다. web에서 postgres 주소로의 TCP 접속은 실패(`CLOSED`), nginx는 `postgres` 이름을 풀지 못한다. 소유자 · 슈퍼유저 비밀 변수 이름이 앱 · web · nginx 컨테이너에 0개다(값은 보지 않는다, 이름만 센다). 교차 스키마 쓰기 거부는 IT-02 P1이 본다.

### IT-06j SIGTERM 정상 종료 (RD-13 · LC-14 일부)

```bash
( for i in $(seq 1 250); do printf '%s %s\n' "$(date +%s.%N)" "$($VC -o /dev/null -w '%{http_code}' --max-time 2 "$B/api/health")"; sleep 0.2; done ) >"$OUT/health-during-sigterm.txt" &
pl=$!; sleep 2; t0=$(date +%s.%N)
docker stop -t 40 "$P-core-api-1"; t1=$(date +%s.%N)
docker inspect -f 'exit={{.State.ExitCode}} oom={{.State.OOMKilled}}' "$P-core-api-1" | tee "$OUT/sigterm.txt"
python3 -c "print('stopSec=%.1f' % ($t1-$t0))" | tee -a "$OUT/sigterm.txt"
wait "$pl"; awk '{print $2}' "$OUT/health-during-sigterm.txt" | uniq -c | tee -a "$OUT/sigterm.txt"
docker start "$P-core-api-1"
timeout 180 bash -c "until [ \"\$(docker inspect -f '{{.State.Health.Status}}' $P-core-api-1)\" = healthy ]; do sleep 1; done"
```

- **기대.** SIGTERM 뒤 readiness를 내린 동안 새 REST 요청은 **503**(SERVICE_NOT_READY + `Retry-After`, 접속 거절이 아님), 프로세스가 끝난 뒤에는 502다(상태 순서 200 → 503 → 502 → 복귀 후 200). 앱 정상 종료 유예 30초 안에 끝나 `docker stop` 소요가 Compose `stop_grace_period` 40초에 닿지 않고 종료 코드가 137(SIGKILL)이 아니다.
- **한계.** 새 WSS 1013 · 열린 WSS 1001 · 공급자 연결 닫힘 · 선점 행 해제는 기기 연결 · 세션을 붙든 드라이버가 없어 여기서 보지 못한다(12절).

### IT-06k 정리 (반드시)

```bash
$DC down -v --remove-orphans -t 3 || docker compose -p "$P" down -v --remove-orphans -t 3
rm -rf build/verify/env build/verify/c build/verify/certs build/verify/nginx build/verify/postgres
docker ps -a --filter "name=$P" --format '{{.Names}}' | wc -l        # 0
docker network ls --filter "name=$P" -q | wc -l                      # 0
docker volume ls --filter "name=$P" -q | wc -l                       # 0
```

- 정리 결과(셋 다 0)를 `$OUT/cleanup.txt`에 적는다. `build/verify/c/`에는 실행 중 본 토큰 · 쿠키 값이 든 비밀 목록과 쿠키 파일이 있으므로 남기지 않는다.

## 11. 커버리지 기대

- 통합 시험(IT)은 유닛 시험과 같은 Gradle `test` 작업에서 돌아 JaCoCo 수치에 함께 들어간다. 통합 시험만의 별도 하한은 없다.
- 하한은 `unit-test-instructions.md` ## 기대 커버리지 그대로다: `contract-model` · `core-api` · `realtime-activity` 각각 줄 80%(U1 범위 `-PcoverageScope=u1`과 모듈 전체 A1 둘 다), `frontend/src/**` 줄 80%(U1 범위와 A2). `test-support` · `tools`는 하한 대상이 아니다(J107).
- 스택 수준 시험(IT-02 · IT-06)은 커버리지를 재지 않는다.

## 12. 이 단계에서 돌리지 못하는 통합 시험과 소유

| 시험(설계 출처) | 왜 못 도는가 | 소유 · 다음 길 |
|---|---|---|
| 두 세션 동시 통합(한쪽 공급자 지연 주입, 다른 쪽 시한 · 전이 불변) — scalability-design SCD-5 · reliability-design RD-3 · NFR7.3 | 저장소에 해당 시험 클래스가 없다(시험 메서드 이름 검색 결과) | 3.6 설계 지정. 시험 코드가 없으므로 Step 9 판정에 반영하고 사다리 2단에서 코드 생성 몫으로 진단한다 |
| 이벤트 N건 · 두 앱 실제 전달 + 폴러 키 변경 반영(RD-8 · PD-10 통합) | 단일 앱 + 대역 시험(`CoreMessagingFlowIT` · `RealtimeMessagingIT`)과 스택 한 턴(C5)만 있고 '두 앱 + 키 변경' 시험은 없다 | 위와 같음 |
| A1 재무장 10분 시나리오(Realtime 정지 · DeviceReleased 20회 재시도 FAILED → 재기동 전달 · 옛 소켓 4401) — RD-10 · NFR4.7 | 기기 교체 등록 · 연결 유지 드라이버가 없고 481초 이상 걸린다. 재무장 함수 · 조건은 3.5 단위 시험(`RealtimeStartupRecoveryIT` 등)이 덮는다 | 위와 같음 |
| 진행 중 세션을 둔 장애 주입(Realtime 정지 직후 503 창 · 끝내기 503 · Core 정지 중 쿠키 연결 1013 · Outbox 적체 · SIGTERM WSS 1001) — RD-15 · LC-3 · RD-13 | 기기 연결 · 세션을 이어 가는 장애 주입 드라이버가 `tools/`에 없다(`run-c.mjs`는 한 턴 끝에 정리한다) | 위와 같음. IT-06d · e · j가 도구로 가능한 부분만 본다 |
| migrate 실패 주입 · 기동 순서(앱이 뜨지 않음, 구 앱 계속) — LC-6 · SD-13 · CD-5 | 깨진 마이그레이션을 넣으려면 마이그레이션 위치를 바꿔야 한다(애플리케이션 소스 수정 금지). 정상 순서는 IT-02 B2 · B3과 이미지 점검 ④가 본다 | 배포 리허설: 4.3 deployment-execution(CD-5) |
| WSS 75초 무음에도 Nginx가 먼저 끊지 않음(앱 40초 판정이 먼저) — LC-1 · SD-3 | 인증된 무음 연결을 유지하는 드라이버가 없다 | 보안 담당 `security-test-instructions.md`(SD-3) |
| 배포 · 롤백 리허설(중단 ≤ 60초, 기기 자동 재접속) — RD-19 · CD-4 · CD-6 · NFR4.10 | 저장소 안에 리허설 하네스가 없다(3.5 Step 47은 저장소 밖 하네스를 썼다) · 실제 SSM · GHCR 경로 없음 | 4.3 deployment-execution(`deploy.log`의 `downtimeSec`) |
| 백업 7일치 · 복원 리허설(RTO 60분) — RD-20 · LC-8 · NFR4.11 | 검증 스택은 backup을 끄고 7일 기록은 시간 조건이다. 이미지 점검 ④가 스케줄러 생존 · 더미 덤프 1회만 본다 | 4.2 environment-provisioning(`infra/RUNBOOK.md` 4절 단계 7 · 11절) |
| 외부 프로브 3종(호스트 밖) · 호스트 수집기 · 인증서 갱신 드라이런 — OD-14 · MO-2 · MO-3 · LC-7 | 실제 호스트 · 도메인 · 인증서가 없다 | 4.2 environment-provisioning · 4.3 deployment-execution |
| U2 삭제 지휘 통합(다섯 표 0건) — SD-21 · NFR12.1 | U2 미구현 | U2 3.5 뒤 다시 도는 Build and Test |
| 실기 브라우저 끝-끝(Chrome 2판 · Android Chrome · iOS Safari) — PD-16 · NFR10.1 ~ 10.3 · SD-2 | 이 샌드박스에 브라우저 · 실기기가 없다 | 실행 계획에 소유 단계 없음(10/29 통합시험은 계획 밖) — `build-and-test-summary.md` 참조 |
| 취약 의존성 · 커버리지 하락 · `ci-gate` 경로 필터 관문 — CD-1 일부 | GitHub Actions 실행이 필요하다 | 첫 Actions 실행. 3.7 ci-pipeline은 SKIP이라 실행 계획에 명시 소유 단계 없음 |

## 13. 결과 기록

- `test-results.md`의 통합 절에 ID별로 명령 · 종료 코드 · 기대 대비 실제 · 증거 경로(`build/b-and-t/<ID>/…`)를 적는다. 실행하지 못한 항목은 12절의 사유와 소유를 그대로 옮긴다.
- 증거 파일은 값 없는 형식(상태 코드 · 건수 · 식별자 · 단언 줄)만 담는다. 실패를 조사하려고 서비스 로그를 옮길 때도 사건 코드 · traceId · 건수만 옮긴다(`infra/RUNBOOK.md` 0절).
