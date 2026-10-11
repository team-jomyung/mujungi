# 빌드 · 시험 결과 — U1 u1-walking-skeleton (3.6 Build and Test Step 9)

> 3.6 Build and Test Step 9 산출물 · 2026-10-11 · 단독 실행(`--single`, 메인 워크플로 위치를 바꾸지 않음) · 대화 언어 한국어 · 시험 전략 Standard
>
> 실행 시각 2026-10-11T03:01:31Z ~ 04:01:21Z. 저장소 HEAD `39b33a6858e7`, 소스 지문 `sha256:b42ff98a22676bfe140521ce2b846723bd93dd2a320b9f6e09e95999748c502b`(시작 · 끝 같음).
> 근거는 이번에 실제로 돌린 명령과 그 출력뿐이다. 3.5 기록 수치는 비교용 참고로만 적었다. 원시 출력은 저장소 루트의 `build/b-and-t/`(`.gitignore` 대상, 이 PC에만 있음)에 있고, 판정에 쓴 값은 이 문서에 옮겼다. 비밀 · 쿠키 · 토큰 · 전사 원문은 이 문서와 증거 파일 어디에도 옮기지 않았다.
> 짝 문서: `build-and-test-summary.md`(같은 판정표 · 준비도), `cross-unit-traceability.md`(Step 10).

## 1. 결론

> **재시험 결과는 15절.** 수정 라운드(커밋 `cf0a58e` ~ `b9a53a5`) 뒤 재시험(HEAD `b094c0d`, 2026-10-11T17:11Z ~ 22:32Z)에서 실패한 명령 0 · **Met 69 · Not Met 0 · Unverified 51**. 아래 1차 기록은 지우지 않았고 10절 매트릭스는 재시험 값으로 갱신했다(1차 값은 같은 칸에 '1차: …').

**이 단계의 결과는 실패다.** 단계 정의의 실패 조건 세 가지에 모두 걸린다.

1. **실패한 명령 2개.** ST-19 Trivy 백엔드 스캔 rc 1(운영 bootJar에 HIGH · CRITICAL 21건), ST-20 이미지 점검 ③ rc 1(이미지 5종 모두 고칠 판이 있는 HIGH 이상). 빌드 · 단위 · 통합 시험과 검증 명령(전체 · `--engine`)은 모두 통과했다.
2. **Not Met 11개.** 공급망 취약점, 앱 컨테이너 정지 때 입구 504(설계는 502), SIGTERM 유예 중 503 대신 접속 거절, 허용 목록 밖 시간 상수, 단일 인스턴스 표식 부족, 시험 분량 미달 26클래스, 지표 · 스냅샷 · `turn-metrics` 미구현.
3. **Unverified 51개.** 6개는 실행 계획의 4.2 · 4.3이, 2개는 U2가 맡는다. 43개는 실행 계획에 맡을 단계가 없다(11/2 측정 창 · 운영 7일 창 · 실기 · GitHub Actions · 시험 · 도구 부재).

| 묶음 | 목표 수 | Met | Not Met | Unverified |
|---|---|---|---|---|
| Step 1 목표(105) | 105 | 56 | 10 | 39 |
| Step 9 보정(인벤토리 누락) | 15 | 2 | 1 | 12 |
| 합계 | 120 | 58 | 11 | 51 |

- **통과한 것.** 빌드 전부, 단위 시험 1,800건(백엔드 1,196 · 프런트 499 · tools 105) 실패 0, 커버리지 하한(세 백엔드 모듈 · 프런트, U1 범위와 모듈 전체 모두 80% 이상), 핵심 경계 통합 시험 39클래스, 검증 명령 C안 전체(compose, 289초) · 엔진 명령(59초), 인프라 정적 검사 · 관문 음성 대조, 이미지 점검 ③ 밖 7개, 비밀 스캔(gitleaks 이력 포함), TLS · 헤더 · 80 · `limit_req` · 관리 포트 · 로그 위생, 백업 데이터 등급 거부, 대부분의 장애 주입.
- **사다리.** 1단(이 단계 몫 고침) 3건 + 측정 보조 1건을 했고 2단(원인 분류 · 해결 후보 · 영향 추정)을 11절에 적었다. 단독 실행이라 3 · 4단(코드 생성으로 되돌아가는 점프 · 승인 게이트 대신 질문)은 하지 않았다.
- **교차 유닛 추적(Step 10).** 실패. 열거 335개 중 덮인 것은 AC 56개(`cross-unit-traceability.md`).

## 2. 실행 환경과 순서

| 항목 | 값(이번 실행에서 확인) |
|---|---|
| 도구 | Docker 29.8.2(데몬은 이미 떠 있었음, 다시 띄우지 않음) · Compose v5.6.0 · OpenJDK 21.0.12.1 · Gradle 래퍼 8.14.3 · Node v22.22.0 · npm 10.9.4 · shellcheck 0.11.0 · PostgreSQL 16 바이너리 |
| 자원 | CPU 4 · 메모리 15GiB · 디스크 여유 9.3GB |
| 프록시 · TLS | 바깥 HTTPS는 세션 프록시. TLS 검증은 끄지 않았다(`-k` 등 미사용) |
| 증거 위치 | `build/b-and-t/logs/<ID>.log`(명령마다), `build/b-and-t/logs/rc.tsv`(종료 코드 · 시각 · 소요), 묶음별 `build/b-and-t/{unit,IT-01..IT-06,PT-01,PT-02,security,S13}/` |

순서는 `build-and-test-summary.md` 7절(S0 ~ S14)과 보안 지침 14절을 따랐고, 검증 명령이 도는 동안 저장소 파일을 쓰지 않았다. 순서에서 바뀐 점: ST-04 · ST-17은 IT-02 바로 뒤(A2 산출물 사용), ST-05 ~ ST-14는 IT-03 뒤 · IT-04 앞(보안 지침 순서), 보안 명령 중 ST-15 · ST-20은 이미지 7종 빌드(B-04) 뒤에 돌렸다.

| ID | 명령(요지) | 종료 코드 | 소요 | 결과 |
|---|---|---|---|---|
| S0 | `docker info` · `source-fingerprint.sh` · `git status` · 판 확인 | 0 | 1초 | Docker up, 지문 `b42ff98a…`, 작업 트리 변경은 감사 조각 1개 + 새 `build-and-test/`뿐 |
| U-1 · U-2 | `npm ci`(frontend · tools) | 0 · 0 | 27 · 1초 | 459 · 44개 설치, 잠금 파일 불변 |
| U-4 | Gradle `dependencies --configuration testRuntimeClasspath`(4모듈) | 0 | 25초 | `FAILED` 0줄 |
| B-01 | `testClasses :core-api:bootJar :realtime-activity:bootJar verifyBootJarHasNoTestClasses` | 0 | 13초 | 두 jar 시험 항목 0건 |
| B-02 | `eslint .` · `tsc --noEmit` | 0 | 9초 | 경고 · 오류 없음 |
| B-03 | `next build` + standalone 정적 자원 복사 | 0 | 4초 | 라우트 13개 |
| BV-1 · 2 · 3 · 5 | jar · 로그 · standalone · `git status` 확인 | 0 | — | 기대대로 |
| U-8 · U-9 · U-10 | 백엔드 부분 명령(db 제외 · device · SessionTimersTest) | 0 · 0 · 0 | 46 · 31 · 14초 | 861 · 88 · 11건 통과(합계 제외) |
| U-12 · U-13 | 프런트 영역 명령 | 0 · 0 | 11 · 12초 | 344 · 175건 통과(합계 제외) |
| U-3 | 백엔드 U1 전체 | 0 | 128초 | 1,196건 실패 0 |
| U-7 | 백엔드 U1 + JaCoCo 하한(`-PcoverageScope=u1`) | 0 | 12초 | 세 모듈 하한 통과 |
| U-5 · U-11 | 프런트 U1 전체 · 커버리지(`MUJOONG_COVERAGE_SCOPE=u1`) | 0 · 0 | 20 · 24초 | 499건 통과, 줄 98.82% |
| U-6 · U-14 | tools U1 | 0 · 0 | 13 · 11초 | 105건 통과 |
| TC10 보조 | `vitest run tests/u1 --reporter=json`(frontend · tools) | 0 · 0 | 20 · 12초 | 파일별 시험 수(11.1 ④) |
| PT-01 | 번들 예산 정적 산정 | 0 | 0.1초 | 7절 |
| IT-02 · ST-02 | `bash scripts/verify.sh` | 0 | 291초 | `mode=compose`, RESULT PASS |
| ST-04 | 프런트 13파일 · tools 2파일 보안 시험 | 0 · 0 | 10 · 2초 | 192 · 29건 통과 |
| ST-17 | 추적 env · 키 파일, 번들 grep | 0 | — | 0 · 0 |
| IT-03 | `bash scripts/verify.sh --engine` | 0 | 60초 | E0 PASS, 출력 581바이트 |
| ST-05 | `KEEP=1 bash scripts/verify.sh --engine` | 0 | 58초 | 스택 남김 |
| ST-06 ~ ST-13 | 앵커 · TLS · 헤더 · 80 · `limit_req` · 관리 포트 · 로그 위생 · postgres 재기동 | 0 | 11초 | 기대대로(8절) |
| ST-14 | 검증 스택 정리 | 0 | 2초 | 컨테이너 0 |
| IT-04a ~ d · ST-21 | `infra-validate` · `test-infra-validate.sh` · `test-host-tools.sh` · `test-gate-negatives.sh` | 0 · 0 · 0 · 0 | 15 · 37 · 5 · 8초 | 8검사 PASS · 32건 · 23건 · 관문 5종 |
| B-04 | `build-images.sh --extra-ca …` | 0 | 6초 | 7종, 라벨 지문 = 지금 지문 |
| BV-4 | 라벨 지문 대조 | 0 | — | 7종 불일치 0 |
| IT-05 | `image-checks.sh`(Trivy 없음) | 0 | 54초 | ① ② ④ ~ ⑧ PASS, ③ SKIP |
| ST-15 | 버림 PostgreSQL 백업 거부 | 0 | 3초 | REFUSED rc 3 / OK rc 0 |
| ST-20 | 지문 확인 → `image-checks.sh`(Trivy 감싸개) | **1** | 58초 → 82초 | 첫 실행은 감싸개 결함(11.1 ①), 고친 뒤 ③ **FAIL**(실제 취약점) |
| ST-19 | Trivy `rootfs`(운영 bootJar 2개) | **1** | 2초 | HIGH 15 · CRITICAL 6 |
| ST-01 · ST-16 | 전제 확인 · gitleaks 양성 대조 · 이력 | 0 · 7 · 0 | 9초 | 다이제스트 = `ci.yml`, 양성 rc 7, 이력 no leaks |
| ST-18 | 예외 목록 · `npm audit` · 관문(+`--omit=dev` 보충) | 0 · 0 | 3초 | 관문 위반 0 |
| ST-22 ~ ST-25 | actionlint · 정적 검토 · 문구 · 문서 | 0 | 2초 | 기대대로 |
| IT-06a ~ k | 손으로 띄운 스택 · 장애 주입 · 정리 | 0 | 약 28분 | 6절 |
| S13 | 코드 검색 · 설정 확인 · `mj --scale` | 0 · 64 | — | 7절 · 9절 |

ST-20 · ST-19는 감싸는 스크립트 종료 코드가 아니라 안쪽 명령(`image-checks.sh` · `trivy`)의 종료 코드를 적었다.

## 3. 빌드 결과

| ID | 기대 | 실제 | 판정 |
|---|---|---|---|
| U-1 · U-2 · U-4 | 종료 0, 실행기 존재, 잠금 파일 불변, `FAILED` 0줄 | 모두 rc 0, `next` · `vitest` · `ws` 있음, `git status` 불변, `FAILED` 0 | 성공 |
| B-01 | 컴파일 · bootJar 2개 · 시험 항목 0건 | rc 0, `verifyBootJarHasNoTestClasses: … 시험 항목 0건` 2줄 | 성공 |
| B-02 | eslint · tsc 통과 | rc 0, 출력 없음 | 성공 |
| B-03 | next build · standalone | rc 0, 라우트 13개, standalone 정적 자원 복사 | 성공 |
| B-04 | 이미지 7종 · 라벨 지문 | rc 0, `완료 7종 tag=verify revision=39b33a6858e7` | 성공 |
| BV-1 | jar 2개 | core-api.jar 67,402,291B · realtime-activity.jar 60,055,795B | 기대대로 |
| BV-2 | 2 | 2 | 기대대로 |
| BV-3 | 3파일 | `server.js` · `BUILD_ID` · `.next/static` | 기대대로 |
| BV-4 | 7종 라벨 = 지금 지문 | 7종 모두 `sha256:b42ff98a…502b`(이전에 낡았던 backup도 새로 빌드됨) | 기대대로 |
| BV-5 | 빌드 전과 같음 | 감사 조각 1개 + 새 `build-and-test/`뿐 | 기대대로 |

검증 명령의 A1(`gradle clean check`, 141초) · A2(`npm ci` · eslint · vitest · next build, 48초) · A3(13초)도 깨끗한 상태에서 다시 빌드해 통과했다. **빌드 상태: 성공.**

## 4. 단위 시험 결과

중복 없는 정규 명령만 합계에 넣었다. 부분 명령(U-8 · U-9 · U-10 · U-12 · U-13)은 같은 시험의 부분집합이라 따로 적고 합계에 넣지 않았다.

| 층 | 명령 | 총 | 통과 | 실패 | 건너뜀 |
|---|---|---|---|---|---|
| 백엔드(4모듈) | U-3 | 1,196 | 1,196 | 0 | 0 |
| 프런트 | U-11(U-5 같은 결과) | 499 | 499 | 0 | 0 |
| tools | U-14(U-6 같은 결과) | 105 | 105 | 0 | 0 |
| 합계 | — | 1,800 | 1,800 | 0 | 0 |

| 백엔드 모듈 | 클래스 | 시험 | 실패 · 오류 · 건너뜀 |
|---|---|---|---|
| contract-model | 14 | 276 | 0 · 0 · 0 |
| core-api | 61 | 506 | 0 · 0 · 0 |
| realtime-activity | 39 | 387 | 0 · 0 · 0 |
| test-support | 4 | 27 | 0 · 0 · 0 |

- **DB 백엔드.** auto → docker(Testcontainers `postgres:16.15`). `MujoongPostgresTest`는 local 백엔드(호스트 PostgreSQL 16)도 돌린다. XML의 `[MujoongPostgres]` 줄: docker 9 · local 3.
- **부분 명령.** U-8(db 제외) 71클래스 861건 · U-9(core device) 7클래스 88건 · U-10(SessionTimersTest) 11건 · U-12 23파일 344건 · U-13 12파일 175건, 모두 실패 0.
- **검증 명령 안 회귀.** A1(태그 필터 없음) 118클래스 1,196건 실패 0. 집합이 U-3과 같다.
- **3.5 기록과 비교(참고).** 시험 수(1,196 · 499 · 105)가 3.5 마지막 기록과 같다.
- **분량(BT-TC-10).** 백엔드 118클래스 중 26개가 5건 미만이다 — 2건 4개(ApprovedContentRepositoryIT · CoreStartupSequenceIT · DbSessionLimitsIT · RealtimeDataAccessWiringIT), 3건 14개(SystemMujoongClockTest · SessionPackageIT · CoreDataAccessWiringIT · SessionJdbcConfigIT · FlywayConfIT · DevSeedRunnerIT · IdentityRepositoriesIT · ContractConformanceIT · SeedDictionaryFileTest · MessagingValueTypesTest · EventLoggerMergeTest · RealtimeStartupRecoveryIT · ProviderRequestWhitelistIT · RealtimeLogHygieneIT), 4건 8개(ClockParamsTest · SeedReferenceSqlIT · DeletionParticipantsCoreIT · CoreLogHygieneIT · HealthAndReadinessIT · WebErrorMappingTest · DbSessionLimitsTest · SessionRecordEntitiesTest). 프런트 34파일 · tools 8파일은 모두 5건 이상이다. 3.5는 4개만 미달로 적었다.

**실패한 단위 · 통합 시험은 없다.** 그래서 시험 이름 · 단언 · 스택 추적으로 적을 실패 상세가 없다. 실패 상세는 스캔 명령과 목표 판정에서 나왔다(9절).

## 5. 커버리지

| 모듈 | U1 범위(U-7 · U-11) | 모듈 전체(A1 · A2) | 하한 | 판정 |
|---|---|---|---|---|
| contract-model | 98.53%(1478/1500) | 98.47%(1477/1500) | 줄 80% | 통과 |
| core-api | 95.80%(3673/3834) | 95.80%(3673/3834) | 줄 80% | 통과 |
| realtime-activity | 89.78%(5044/5618) | 89.96%(5054/5618) | 줄 80% | 통과 |
| frontend `src/**` | 98.82%(1848/1870) | 98.82%(A2 NOTE) | 줄 80% | 통과 |
| test-support | 51.69%(565/1093) | 51.69% | 대상 아님(J107) | — |
| tools | 재지 않음 | — | 대상 아님(J107) | — |

- 참고(분기): contract-model 89.36% · core-api 81.24% · realtime-activity 72.32% · 프런트 96.2%(분기 하한은 없다).
- U1 범위와 모듈 전체의 작은 차이(contract-model 1줄, realtime 10줄)는 실행마다 다른 타이밍 경로 때문이며 두 값 모두 하한 위다.
- 하한 설정(`jacocoTestCoverageVerification` LINE 0.80 · `thresholds.lines: 80`)은 바꾸지 않았다.

## 6. 통합 시험 결과

| ID | 기대 | 실제 | 결과 |
|---|---|---|---|
| IT-01 | 경계 클래스 39개 · tests ≥ 1 · bad 0 · skipped 0 | 39개 모두 있음(MISSING 0), 모두 bad=0 · skipped=0 | 통과 |
| IT-02 | rc 0 · `mode=compose` · `RESULT PASS` | rc 0, `mode=compose`, A1 141s · A2 48s · A3 13s · A4 0s · F 1s · B 69s · C 14s · P1 1s · P2 0s · P3 1s · T 0s, `RESULT PASS rc=0 total=289s` | 통과 |
| IT-03 | rc 0 · E0 PASS · 110초 안 · 1MiB 미만 | rc 0, E0 1s · B 40s · C 15s, total 59s(벽시계 60.0초), 581바이트 | 통과 |
| IT-04a | 8검사 PASS · rc 0 | compose-config · compose-invariants · demo-profile · secret-env-files · nginx · shellcheck · migrations · bundle PASS | 통과 |
| IT-04b | 모든 사례 ok | 32건 중 어긋남 0 | 통과 |
| IT-04c | 모든 사례 ok | 23건 중 어긋남 0 | 통과 |
| IT-04d | 관문 5종 PASS | secrets · compose · nginx · migrations · eslint 모두 양성 통과 · 음성 거부 | 통과 |
| IT-05 | 7종 빌드 · ① ② ④ ~ ⑧ PASS | 빌드 rc 0, ① ② ④ ⑤ ⑥ ⑦ ⑧ PASS, ③ SKIP(Trivy 실행 파일 없음 — 8절 ST-20에서 따로) | 통과 |
| IT-06a | 전체 healthy · C1 ~ C12 | 6서비스 running/healthy, C1 ~ C12 PASS(14.2초) | 통과 |
| IT-06b | OD-5 · OD-12 이름 노출, uri 템플릿만, 외부 403, health 본문 상세 없음, METRIC_SNAPSHOT 60초 1줄 이상 | 6.1 표 · uri 라벨 19종 UUID 0 · 외부 `/actuator/*` 4경로 403 · `/api/health` 200 `{"status":"UP"}` · **METRIC_SNAPSHOT 0줄(두 앱, 65초)** | 일부 미달 |
| IT-06c | traceId가 Nginx · Core · Realtime · Outbox · Inbox에서 같음 | 한 traceId가 Nginx 1줄 · Core 7줄 · Realtime 15줄 · `realtime_outbox_event` 5행(PUBLISHED) · `core_inbox_event` 5행(PROCESSED)에서 같음 | 통과 |
| IT-06d | Realtime 정지: Core 답함, 12 사본, 30초 뒤 UNKNOWN · 423, 실패 · 폴백 카운터 증가 | 로그인 200 · 정지 직후 home은 network UNRESPONSIVE · canStart false(판정 대상 아님) · 12 조회 200(사본, items 0) · 36초 뒤 UNKNOWN · canStart false · DEVICE_STATUS_UNKNOWN · 시작 423 DEVICE_NOT_STABLE · `conn_error` +1 · `session_view_fallback_total` 0 → 1 · 재기동 healthy | 통과 |
| IT-06e | Core 정지: 입구 502 + UPSTREAM_UNAVAILABLE, WSS 101 → 4401, 보고 실패 증가 | **/api/health 504** UPSTREAM_UNAVAILABLE(Nginx 오류 로그: `upstream timed out (110) while connecting`, rt 3.004초) · WSS 101 → 4401 · Realtime `device_status` conn_error 0 → 3 · 재기동 healthy | 미달(504) |
| IT-06f | DB 정지: 앱 직접 503 · Retry-After · 두 앱 `/internal/events` 503, 복귀 200 | 19초 뒤 `/api/health` 503 SERVICE_NOT_READY(상세 없음) · `Retry-After: 5` · Core `/internal/events` 503 · Realtime은 그 순간 401(한 번 조회) → 재실행(1초 간격 · 같은 40초 기한)에서 Core 18초 · Realtime 19초에 503 · 복귀 4 ~ 5초 뒤 200 | 통과(재실행) |
| IT-06g | kill -9 3회 healthy 5분 안(목표 1분), 그동안 502 뒤 복귀, STARTUP_RECOVERY, hang 기록 | realtime 7.3초 · core-api 9.1초 · realtime 6.8초, probe downtimeSec 7 · 9 · 7(그동안 health 또는 wss 502), 매번 STARTUP_RECOVERY 한 줄, pause 45초 → unhealthy · 재시작 0회(자동 복구 없음 기록) | 통과 |
| IT-06h | web 정지: `/device`만 502, health 200, WSS 101 → 4401 | **/device 504** · `/api/health` 200 · WSS 101 → 4401 · 재기동 healthy | 미달(504) |
| IT-06i | 망 분리 · postgres 도달 불가 · 소유자 비밀 이름 0 | nginx · web은 edge만 · postgres · fake-provider는 backend만 · web → postgres CLOSED timeout · nginx는 postgres를 못 풂 · 소유자 · 슈퍼유저 비밀 이름 core-api · realtime · web · nginx 모두 0 | 통과 |
| IT-06j | SIGTERM: 유예 중 503(접속 거절 아님) → 종료 뒤 502, 30초 안 종료, 137 아님 | exit 143 · 종료 0.6초 · OOM 아님. 표본 200×10 뒤 502 · 000(시간 초과) 반복, **503 0건**. 재실행(20ms 표본): SIGTERM 49ms 뒤부터 유예 0.63초 동안 502×19 · 504×6, 정지 뒤 504×141, 503 · Retry-After 0건 | 미달 |
| IT-06k | 컨테이너 · 망 · 볼륨 0, 생성 비밀 지움 | 0 · 0 · 0, `build/verify/{env,c,certs,nginx,postgres}` 0개 | 통과 |

### 6.1 지표 노출(IT-06b)

| 지표 | core | realtime | 분류 |
|---|---|---|---|
| `mujoong_health_up` · `mujoong_internal_http_requests_total` · `mujoong_internal_http_seconds` · `mujoong_security_event_total` | 있음 | 있음 | — |
| `mujoong_internal_http_breaker_state` · `mujoong_event_delivery_seconds` | 있음 | — | 받는 쪽 · 호출 쪽만 |
| `mujoong_session_ended_total` · `mujoong_relay_in_seconds` · `mujoong_sessions_active` · `mujoong_ws_connections_open` | — | 있음 | Realtime 소유 |
| `mujoong_timeout_fired_total` · `mujoong_session_view_fallback_total` | 없음 | 없음 | 코드 등록 있음 · 사건이 없어 미등록(폴백은 IT-06d에서 1로 등록됨) |
| `mujoong_device_connections_open` | 없음 | 없음 | 같은 뜻의 게이지가 `mujoong_ws_connections_open` 이름으로 노출(설계 이름과 다름) |
| `mujoong_device_connection_state` · `mujoong_outbox_pending` · `mujoong_outbox_oldest_pending_seconds` · `mujoong_outbox_failed` · `mujoong_inbox_dead` · `mujoong_db_pool_wait_exceeded_total` · `mujoong_queue_depth` · `mujoong_session_activated_total` · `mujoong_session_start_to_active_seconds` · `mujoong_relay_out_seconds` · `mujoong_turn_first_audio_seconds` · `mujoong_view_item_ready_seconds` | 없음 | 없음 | 코드에도 없음(3.5 알려진 틈 2) |
| `jvm_memory_used_bytes` · `jvm_gc_pause_seconds` · `jvm_threads_live_threads` · `hikaricp_connections_active` · `hikaricp_connections_pending` · `http_server_requests_seconds` | 있음 | 있음 | 프레임워크 기본 |

### 6.2 이 단계에서 돌리지 못한 통합 시험

`integration-test-instructions.md` 12절 그대로다: 두 세션 동시 통합 · '두 앱 + 키 변경' 전달 · A1 10분 재무장 · 진행 중 세션을 둔 장애 주입(정지 직후 503 창 · 끝내기 503 · 쿠키 연결 1013 · Outbox 적체 · SIGTERM WSS 1001) — 시험 · 드라이버 부재. migrate 실패 주입 — 소스 수정 필요(금지), 4.3 몫. WSS 75초 무음 — 도구 없음. 배포 · 롤백 리허설 · 백업 7일 · 복원 리허설 · 외부 프로브 — 4.2 · 4.3. U2 삭제 지휘 — U2. 실기 브라우저 — 소유 단계 없음. Actions 관문 — 소유 단계 없음.

## 7. 성능 측정 결과

### 7.1 PT-01 번들 예산(판정)

| 대상 | gzip 레벨 5 정적 산정 | Nginx 전송(교차 확인) | 예산 | 판정 |
|---|---|---|---|---|
| /device 초기 JS(6파일) | 143,827B(140.5KiB) | 142,970B(−0.6%), HTML 스크립트 목록 = 정적 집합 | 150,000B | Met |
| /device 지연 청크(2파일) | 6,420B(6.3KiB) | — | 80,000B | Met |
| /parent 첫 로드 JS(8파일) | 156,351B(152.7KiB) | 155,433B(−0.6%), 목록 = 정적 집합 | 200,000B | Met |

`polyfillFiles`(nomodule) 1개는 빼고 따로 적었다(`static/chunks/0cz1d0mv5g_q7.js`). B-03 빌드와 A2 빌드의 산정 결과가 바이트까지 같았다.

### 7.2 PT-02 · PT-03 · PT-04 · PT-05(기록, 판정 아님)

가짜 공급자 · 표본 10 미만이라 판정하지 않는다(RD-13 표본 규칙). 다음 실행과 비교할 기준선이다.

| 값 | IT-02 | IT-03 |
|---|---|---|
| relayInMs · relayOutMs(정상 턴) | 1 · 8 | 2 · 8 |
| providerResponseMs · quietWaitMs | 104 · 801 | 104 · 801 |
| t1 − t0 · t3 − t0 · t3 − t2 | 0.121초 · 1.038초 · 2ms | 0.121초 · 1.040초 · 4ms |
| clockOffsetMs · rttMs · tSpeechEndSource | −1 · 2 · PROVIDER_EVENT | −3 · 3 · PROVIDER_EVENT |
| 금지 문장 턴 | unobservedReason GATE_DISCARD | GATE_DISCARD |
| 서버 몫(CHILD, tViewItemReady − tTranscriptFinal) | 9 · 4ms | 8 · 3ms |
| 폴링 몫(tViewItemFirstServed − tViewItemReady, CHILD) | 1.657 · 4.328초 | 1.650 · 4.331초 |
| C6(04 STABLE) · C9 · C10 · C11 · C12 | 0.6 · 2.2 · 5.0 · 4.5 · 0.2초 | 0.6 · 2.2 · 5.0 · 4.5 · 0.8초 |
| 이벤트 전달(PROCESSED durationMs, 7건) | 최소 103 · 중앙 371 · 최대 460 | 174 · 392 · 497 |
| FAILED · DEAD | 0 | 0 |

- PT-04: `http_server_requests_seconds_bucket`에 `le` 0.1 · 0.3 · 0.5 · 1.0 버킷이 있고 uri 라벨은 경로 템플릿 19종뿐이다. 로컬 `le=0.3` 비율 core 32/32 · realtime 8/8(기록만).
- PT-05: `maximum-pool-size` core-api 10 · realtime-activity 10(합 20), `CorePropertiesValidationTest` 통과. 풀 대기 주입 시험과 경고 카운터는 없다(BT-NFR7.6 Not Met).
- 측정 도구 존재(`git ls-files`): `turn-metrics` 0 · `sli-report` 0 · `probe-daily` 0 · `host-sample` 0 · `compose.loadtest` 0.

### 7.3 실행하지 못한 성능 목표

11/2 측정 창이 필요한 BT-NFR5.1 · 5.2 · 5.3a · 5.5a · 5.5b · 5.6a ~ c · 7.1 · 7.2 · 7.5a · BT-PD-20(+ 보정 BT-PD-6 · PD-18 · OD-16)은 실공급자 · 시연 EC2 · 실기 · 부하 도구가 없어 `Unverified`이고, 맡을 4.6 performance-validation은 이번 실행 계획에서 SKIP이다(뒤 단계 미예약).

## 8. 보안 시험 결과

### 8.1 명령별

| ID | 기대 | 실제 | 결과 |
|---|---|---|---|
| ST-01 | 29.8.2, 세 도구 이미지 · `ci.yml` 다이제스트 | 29.8.2, gitleaks `c00b6bd0…` · trivy `62b1e65e…` = `ci.yml` env, actionlint 있음, 여유 9.3GB | 통과 |
| ST-02 | IT-02와 같은 실행 | 6절 IT-02. 보안 줄 A1.bootjar · A4.patterns · A4.ignore · B1.* · B4.* · B5.* · C1 ~ C5 · C9 ~ C11 · P1.* · P2.* · P3 · T 모두 PASS(A4.gitleaks는 NOTE — ST-16이 대신) | 통과 |
| ST-03 | `classes=59 tests=557 problems=0`, MISSING 0, ts가 Step 9 뒤 | `classes=59 tests=557 problems=0`, MISSING 0, ts 03:12:26Z ~ 03:14:17Z(A1 XML) | 통과 |
| ST-04 | 두 실행 rc 0 | 프런트 13파일 192건 · tools 2파일 29건 통과 | 통과 |
| ST-05 | rc 0 · 스택 남음 | rc 0(57초), 6서비스 healthy 유지 | 통과 |
| ST-06 | `anchor http=200` | CA가 지워져 리프 앵커 사용, `anchor http=200` | 통과 |
| ST-07 | tls1 · tls1_1 거절, tls1_2 · tls1_3 협상 | `alert protocol version` 2개, `New, TLSv1.2` · `New, TLSv1.3` | 통과 |
| ST-08 | FAIL 0, 인코딩 변형 400 | 7경로 모든 검사 PASS(HSTS · nosniff · Referrer-Policy · Permissions-Policy · CSP 1개 · 닫힘/nonce · strict-dynamic · connect-src · unsafe-eval 없음 · Server 판 숨김 · X-Powered-By 없음), nonce 요청마다 다름, `%2e` · `%2f` · `%5c` 변형 4개 모두 400 | 통과 |
| ST-09 | 301 · ACME 404 · 444 | `HTTP/1.1 301` · `Location: https://localhost/x`, `HTTP/1.1 404`, `Empty reply from server` | 통과 |
| ST-10 | 201과 429 공존 · RATE_LIMITED 1건 이상 | 201×21 · 429×10, RATE_LIMITED 봉투 31건 | 통과 |
| ST-11 | 관리 엔드포인트 404 · health · prometheus 200 · 덤프 설정 · UUID 라벨 0 · 보안 사건 계열 ≥ 1 | 두 앱 env · heapdump · configprops · beans · threaddump · loggers · mappings · shutdown 404, health · prometheus 200, `ulimit_core=0` · `exit_on_oom=yes` · `heapdump_off=yes`, `uuid_labels=0`, `security_event_series=2` | 통과 |
| ST-12 | 위반 0 · access ≥ 1 · polling 2xx 0 | CHECK 6종 violations=0 · `SUMMARY violations=0`, `access_lines=104 polling_2xx=0` | 통과 |
| ST-13 | postgres healthy · skipping_init ≥ 1 | `postgres healthy`, `skipping_init=1` | 통과 |
| ST-14 | 남은 컨테이너 0 | 0(망 · 볼륨도 0) | 통과 |
| ST-15 | real → REFUSED rc 3, synthetic → B2 통과 | `result=REFUSED file=none bytes=0 … reason=data_class` `rc=3`, 대조 `result=OK … bytes=1066` `rc=0`, 버림 망 · 컨테이너 정리 | 통과 |
| ST-16 | 양성 rc 7 · 이력 rc 0 | 양성 대조 rc 7, 이력 37커밋(약 19.6MB) `no leaks found` rc 0 | 통과 |
| ST-17 | 0 · 0 | `추적 env · 키 파일 0건` · `브라우저 번들 걸림 0` | 통과 |
| ST-18 | 두 rc 0 | 예외 1건 유효 rc 0, npm 관문 'high 이상 1건(권고 1개), 위반 0건' rc 0 — frontend high 5 = braces GHSA-vfj7-8cjw-p6xm(개발 의존성, 예외 `status: candidate`), tools 0. 보충 `npm audit --omit=dev`: 두 쪽 모두 0 | 통과 |
| ST-19 | rc 0 | **rc 1, Total 21(HIGH 15 · CRITICAL 6)** — 8.2 | **실패** |
| ST-20 | ① ~ ⑧ PASS · rc 0 | ① ② ④ ⑤ ⑥ ⑦ ⑧ PASS, **③ FAIL**(core-api · realtime · web · migrate · backup 모두 HIGH 이상) · rc 1 — 8.3. 낡은 이미지 없음(B-04가 7종을 새로 빌드) | **실패** |
| ST-21 | 네 rc 0 | IT-04a ~ d와 같은 실행(rc 0 · 0 · 0 · 0) | 통과 |
| ST-22 | actionlint rc 0 · 미고정 uses 0 · 권한 | actionlint rc 0, 바깥 `uses` 24개 모두 40자 SHA, 최상위 `contents: read`, `packages: write`는 ci.yml `images` 작업만, `id-token: write`는 ci.yml · deploy.yml `deploy` 작업만 | 통과 |
| ST-23 | 10절 기대 · 분류 | (1) 두 앱 `DbSessionLimits.java:44`뿐(허용) (2) `/internal/events` · `/internal/sessions` POST · `/{sessionId}/end` · `/{sessionId}/view` + `addHandler` 1개 (3) StartActivityService:268 · SessionStateChangedHandler:70 (4) `enabled: false` 4줄, `max:` 0 (5) bytea 0 | 통과 |
| ST-24 | 화면 글 걸림 0 · 체크리스트 0 | 걸림 2줄 모두 화면 글 아님(8.6) | 통과 |
| ST-25 | 각 1건 이상 | `cloud_run_report.md:239` · `01-roles.sql:63` · `code-summary.md:702` · `001-reference.sql:7` | 통과 |

### 8.2 ST-19 운영 bootJar 취약점(Trivy `rootfs`, `--ignore-unfixed` 없음, 예외 1건 적용)

| 심각도 | CVE | 패키지 | 설치 판 | 고친 판 | jar |
|---|---|---|---|---|---|
| CRITICAL | CVE-2026-65182 · CVE-2026-65905 · CVE-2026-68525 | org.apache.tomcat.embed:tomcat-embed-core | 11.0.24 | 11.0.25(10.1.58 · 9.0.121) | core-api · realtime-activity |
| HIGH | CVE-2026-68497 | tools.jackson.core:jackson-databind | 3.1.5 | 3.1.6 · 3.2.2 | 둘 다 |
| HIGH | CVE-2026-91776 · CVE-2026-91777 | tools.jackson.core:jackson-databind | 3.1.5 | 3.1.7 · 3.2.3 | 둘 다 |
| HIGH | CVE-2026-89407 · CVE-2026-89425 | tools.jackson.core:jackson-core | 3.1.5 | 3.1.7 · 3.2.2/3.2.3 | 둘 다 |
| HIGH | CVE-2026-68497 · CVE-2026-91776 · CVE-2026-91777 | com.fasterxml.jackson.core:jackson-databind | 2.22.1 | 2.22.2 · 2.22.3 | core-api |
| HIGH | CVE-2026-89407 · CVE-2026-89425 | com.fasterxml.jackson.core:jackson-core | 2.22.1 | 2.22.3 | core-api |

고친 판은 Maven Central 미러에서 있음을 확인했다(tomcat-embed-core 11.0.25 · 11.0.26, tools.jackson 3.1.7, com.fasterxml 2.22.3). Spring Boot 4.1.x는 4.1.1이 최신이라 BOM 판 올림만으로는 아직 안 된다(11절 A).

### 8.3 ST-20 이미지 ③(Trivy `image`, `--ignore-unfixed`, 예외 1건 적용)

| 이미지 | 고유 CVE(CRITICAL · HIGH) | 주된 출처 |
|---|---|---|
| core-api | 9(3 · 6) | 앱 jar(tomcat · jackson, 8.2와 같음) + 베이스 ubuntu 24.04 openssl 3.0.13-0ubuntu3.15 → 3.16 |
| realtime | 9(3 · 6) | 같음(com.fasterxml 2.x 없음) |
| web | 14(1 · 13) | 베이스 `node:22.22.3-alpine3.23`에 든 npm의 tar · brace-expansion · picomatch · sigstore · ip-address · pacote, alpine libssl3/libcrypto3 3.5.6 → 3.5.7 · 3.5.8 |
| migrate | 32(1 · 31) | 베이스 `flyway/flyway:12.4.0`의 드라이버(couchbase · databricks · aws · mssql · postgresql 42.7.2) · netty · jackson, ubuntu openssl |
| backup | 25(1 · 24) | gosu(Go stdlib v1.24.6) · supercronic(Go stdlib v1.26.6) |

판정과 상세 목록: `build/b-and-t/security/st20-image-checks.txt`, `st20-trivy-<이미지>.json`, `st20-trivy-summary.txt`.

### 8.4 보안 지침 하위 목표 판정(보안 지침 3절 · 4절 기준)

| 목표 | 판정 | 근거 · BT 매트릭스와의 관계 |
|---|---|---|
| NFR1.1 · 1.2 · 1.3 · 2.1 · 3.1 · 3.2 | Met | 8.1 · 8.6, BT 같은 ID |
| NFR9.1-a · 9.1-b · 9.1-c | Met | ST-07 · ST-08 · ST-09 · B4 → BT-NFR9.1a |
| NFR9.1-d · 9.1-e | Unverified | 4.3 · 4.2 → BT-NFR9.1b |
| NFR9.2 | Met(보안 지침 기대에는 로그아웃 없음) | BT-NFR9.2는 Expected에 '로그아웃 뒤 401'이 있어 Unverified(U2) |
| NFR9.3 ~ 9.11 · 9.10-a · 9.10-b · 9.10-c | Met | 8.1, BT 같은 ID |
| NFR9.12-a · 9.12-b · 9.12-c | Met | 헤더 · 입력 · SQL. 브라우저 콘솔은 SD-2-b로 분리 → BT-NFR9.12는 Unverified |
| NFR9.13 | Not Met | ST-19 · ST-20 ③ → BT-NFR9.13 |
| NFR9.14 · 12.1-a · 12.2 · 12.3 · 12.4 | Met | 8.1 |
| NFR12.1-b | Unverified | U2 → BT-NFR12.1b |
| SD-1 · SD-2-a · SD-3-a · SD-4 ~ SD-8 · SD-9-a · SD-10 ~ SD-12 · SD-13-a · SD-13-b · SD-14 · SD-15-a · SD-15-b · SD-17 · SD-18-a · SD-19-b · SD-20 · SD-21-a · SD-22-a · SD-22-b · SD-23 · SD-24 · SD-25 | Met | 8.1 · ST-03. SD-13-b는 Compose `required: false` 해석(G14)을 그대로 두고 앱 S2 · ST-13으로 판정, SD-25 Realtime 쪽은 ST-23 수동 근거(자동 시험 없음, G3) |
| SD-16 | Not Met | 로컬 동등물 ST-19 · ST-20 실패 |
| SD-2-b · SD-3-b · SD-9-b · SD-19-a · SD-21-b | Unverified | BT-NFR9.12 · BT-SD-3b · BT-SD-9b · BT-SD-19a · BT-NFR12.1b |
| SD-18-b | 해당 없음 | 기대값이 없는 조정 활동(보안 지침 4절) |

### 8.5 보안 관찰(판정 밖)

- G7: `ci/security-gate.py`와 이미지 ③이 예외의 `status`를 보지 않는다 — 사람 승인 전(`candidate`) braces 예외로 npm 관문이 통과했다. 사람 결정(J277).
- G9: `handleTransportError` EOFException ERROR 기록은 그대로다. IT-06에서 앱 강제 종료 · 정지 때 `APP_ERROR`가 남았고 P2 · ST-12 위반은 아니다.
- G11: web 이미지의 `prerender-manifest.json` 미리보기 키(정보). G12: dev-edge Referrer-Policy `no-referrer`(정보).
- G13: taint SAST · 범용 DAST 없음(권고, 측정 목표 아님).
- `EventLogger.forTest`(운영 소스 안의 시험용 생성자)가 있다 — 운영 경로에서 쓰이지 않지만 SD-25(운영 코드의 시험 훅 0) 관점에서 검토 거리다.

### 8.6 ST-24 화면 문구 체크리스트(에이전트 검토 — 사람 재검토 권장)

어휘 grep 걸림 2줄: `frontend/src/shared/copy/ko.ts:134`(주석 '연속 3회 실패', 화면 글 아님), `contracts/errors.md:47`(설명 열 'DB 점검 연속 실패 · 정상 종료 중', 화면 글은 '잠시 뒤 다시 시도해 주세요.'). 아래는 `ko.ts`의 COPY · PROPOSED_COPY · TEMP_COPY와 승인 문구 · 점검 페이지를 한 줄씩 읽은 결과다. 칸 값 0 = 없음.

| 화면 · 문구 묶음 | 진단 | 점수 | 의도 | 감정 단정 | 재촉 | 만류 | 보상 | 완료율 | 출처(키) |
|---|---|---|---|---|---|---|---|---|---|
| D0(기기 코드 · 확인 중) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | COPY.device.d0* · PROPOSED_COPY.device.d0Checking |
| T19(대기 · 켜기 · 마이크 안내 · 연결 중) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | COPY.device.t19* |
| 04(제목 · 대화 기기 구역 · 탭) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | COPY.home · COPY.tabs · PROPOSED_COPY.home · lockToast · TEMP_COPY.lockToastStatusUnknown |
| 05a(등록 · 오류) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | COPY.register · PROPOSED_COPY.register · TEMP_COPY.register* |
| 08(자유대화 시작 · 사유 카드) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | COPY.free · PROPOSED_COPY.free · TEMP_COPY.freeRealtimeUnavailable |
| 12(진행 · 말풍선 · 실패 안내) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | COPY.live · PROPOSED_COPY.live · liveTalkingLine · liveWaitingChildLine · liveElapsedLine('n분째'는 경과 시간, 완료율 아님) |
| P1(끝내기 확인) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | COPY.endDialog('정말 끝낼까요?'는 확인만 묻고 권하지 않음) · PROPOSED_COPY.endSendFailed |
| 17(끝 · 사유 · 시각) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | COPY.done · PROPOSED_COPY.done |
| 오류 code 문구 29(TEMP_COPY.errors = contracts/errors.md) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | TEMP_COPY.errors · networkError |
| 승인 문구 CLOSING '오늘 이야기 즐거웠어. 다음에 또 만나!' | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | infra/seed/001-reference.sql(무중 자신의 소감 · 작별 인사, 아이 감정 단정 · 재촉 아님) |
| 승인 문구 WAITING_FILLER '음, 잠깐만 생각해 볼게.' | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | infra/seed/001-reference.sql |
| Nginx 점검 페이지(maintenance.html) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | '지금은 연결할 수 없어요. 잠시 뒤 다시 시도해 주세요.' |

판정: 모든 칸 0이고 ST-04의 `copy.test.ts` · `d0-view` · `t19-view` · `device-machine` · `device-app` 시험(429 · 503 · 닫기 코드 뒤 마지막 화면 유지 + 정확히 5초 재시도)이 통과해 NFR1.3 · NFR2.1은 Met이다. 승인 음성 두 파일은 합성 톤이라 말소리 검토 대상이 없다.

## 9. 실패 상세

### 9.1 실패한 명령

| 명령 | 출력(요지) | 연결 목표 |
|---|---|---|
| ST-19 `trivy rootfs --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --ignorefile /gate/trivyignore /scan` | `Total: 21 (HIGH: 15, CRITICAL: 6)`, `trivy 백엔드 rc=1` — 8.2 | BT-NFR9.13 · SD-16 |
| ST-20 `image-checks.sh`(Trivy 감싸개) | `FAIL ③: core-api high 이상 취약점 … realtime … web … migrate … backup …`, `image-checks: FAIL 1건`, rc 1 — 8.3 | BT-NFR9.13 · SD-16 |
| (ST-20 첫 실행 — 고침) | 감싸개가 `/tmp`를 읽기 전용으로 붙여 Trivy가 `FATAL … mkdir /tmp/trivy-…: read-only file system`으로 끝났고 image-checks가 이를 '취약점'으로 셌다 | 11.1 ① |

### 9.2 Not Met 목표

| 목표 | 기대 | 관측 | 증거 |
|---|---|---|---|
| BT-TC-10 | 시험 클래스마다 5 ~ 8건, 정상 1 + 오류 · 경계 2 이상 | 백엔드 26클래스 5건 미만(2건 4 · 3건 14 · 4건 8) | `unit/U-3-junit.txt` |
| BT-NFR9.13 | 의존성 · 이미지 high 이상 0(사유 · 만료일 있는 예외만) | bootJar 21건(고유 8), 이미지 5종 FAIL | 9.1 |
| BT-NFR4.13 | 앱 컨테이너 정지 시 Nginx 502 | `docker stop`한 core-api · web 뒤 504 UPSTREAM_UNAVAILABLE(`upstream timed out (110: Operation timed out) while connecting to upstream`, 3.004초). 강제 종료(재시작 중)는 502 | `IT-06/health-core-stop.json` · `web-stop.txt` · `logs/IT-06e.log` |
| BT-RD-13(보정) | SIGTERM 유예 중 새 REST 503 SERVICE_NOT_READY + Retry-After(접속 거절 아님) | 유예 0.63초 동안 20ms 표본 25개: 502×19 · 504×6, 503 0 · Retry-After 0 | `IT-06/sigterm-r1/sigterm.txt` |
| BT-NFR6.1 | 시간 숫자 상수 0(허용 목록 제외) | `AsyncRecorder.java:57` `Duration.ofMillis(5)` · `ConnectionSender.java:293` · `InputRelay.java:238` `await(1, TimeUnit.SECONDS)` · 두 앱 InboxDispatcher keepAlive `0L` · `EventLogger.forTest` `Duration.ofSeconds(10)` | `S13/searches.txt` |
| BT-NFR7.4 | 단일 인스턴스 전제 클래스에 표식 | 표식 있는 파일 1개(SlidingWindowRateLimiter) vs SCD-3 12곳 | `S13/searches.txt` |
| BT-NFR4.15 | 연결 상태 분포 · 풀 대기 초과 등 내부 지표 존재 | 두 지표 없음, 열린 연결 게이지 이름이 설계와 다름 | `IT-06/metric-names.txt` |
| BT-NFR7.6 | 풀 대기 100ms 초과 경고 카운터 | 지표 없음(코드 · 스크랩) | 같음 |
| BT-NFR7.8 | Outbox 미전달 · 가장 오래된 PENDING 나이 지표 | 없음 | 같음 |
| BT-OD-06 | METRIC_SNAPSHOT 앱마다 60초 한 줄 | 65초 동안 0줄(두 앱) | `IT-06/metric-snapshot.txt` |
| BT-NFR5.4 | 한 턴 로그만으로 NFR5.1 · 5.2 · 5.3을 계산하는 스크립트가 돈다 | `turn-metrics` 없음 | `S13/searches.txt` |

## 10. 목표 판정표 (Target Verification Matrix)

`build-and-test-summary.md`의 매트릭스와 같은 생성기로 만든 같은 표다. Source 경로는 `construction/u1-walking-skeleton/` 기준, Evidence 경로는 저장소 루트 기준이다. '뒤 단계 미예약'은 이번 실행 계획에 그 검사를 맡을 단계가 없다는 뜻이다(4.4 observability-setup · 4.6 performance-validation · 3.7 ci-pipeline은 SKIP). **재시험 갱신(2026-10-11, J384):** Not Met 11행과 값이 바뀐 Met 행(BT-TC-01 ~ 09 · BT-VC-02 · BT-NFR4.9)의 Actual · Evidence · Verdict를 재시험(HEAD `b094c0d`, 증거 `build/b-and-t-r2/`) 값으로 바꾸고 1차 값을 같은 칸에 '1차: …'로 남겼다. 나머지 행은 재시험에서 회귀 · 사유 변동이 없어 1차 그대로다. 집계: 재시험 **Met 69 · Not Met 0 · Unverified 51**(1차 58 · 11 · 51). 상세는 15절.

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| BT-TC-01 | code-generation/code-generation-plan.md ## Testing Contract · ## 시험 전략과 커버리지; code-generation/unit-test-instructions.md ## 기대 커버리지(J107 · J137) | `contract-model` 줄 커버리지 ≥ 80%(U1 범위, JaCoCo LINE 0.80, 제외 목록 없음) | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): U1 범위 LINE 1478/1500 = 98.53%, U-7-r1 rc 0 / 1차: U1 범위 LINE 1478/1500 = 98.53%, U-7 rc 0(jacocoTestCoverageVerification 통과) | build/b-and-t-r2/logs/U-7-r1.log · build/b-and-t-r2/unit/U-7-jacoco-u1.txt / 1차: build/b-and-t/logs/U-7.log · build/b-and-t/unit/U-7-jacoco-u1.txt | 3.6 | Met |
| BT-TC-02 | 같음 | `core-api` 줄 커버리지 ≥ 80%(U1 범위) | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): U1 범위 LINE 4002/4173 = 95.90%, U-7-r1 rc 0(HEAD 8a9225a 뒤 모듈 전체 명령 96.00%) / 1차: U1 범위 LINE 3673/3834 = 95.80%, U-7 rc 0 | build/b-and-t-r2/logs/U-7-r1.log · build/b-and-t-r2/unit/U-7-jacoco-u1.txt / 1차: build/b-and-t/logs/U-7.log · build/b-and-t/unit/U-7-jacoco-u1.txt | 3.6 | Met |
| BT-TC-03 | 같음 | `realtime-activity` 줄 커버리지 ≥ 80%(U1 범위) | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): U1 범위 LINE 5632/6120 = 92.03%, U-7-r1 rc 0(HEAD 8a9225a 뒤 92.03%) / 1차: U1 범위 LINE 5044/5618 = 89.78%, U-7 rc 0 | build/b-and-t-r2/logs/U-7-r1.log · build/b-and-t-r2/unit/U-7-jacoco-u1.txt / 1차: build/b-and-t/logs/U-7.log · build/b-and-t/unit/U-7-jacoco-u1.txt | 3.6 | Met |
| BT-TC-04 | 같음 | `frontend/src/**` 줄 커버리지 ≥ 80%(U1 범위, Vitest `thresholds.lines` 80) | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): U1 범위 줄 1863/1883 = 98.93%, U-11 rc 0 / 1차: U1 범위 줄 1848/1870 = 98.82%, U-11 rc 0(thresholds.lines 80 통과) | build/b-and-t-r2/logs/U-11.log · build/b-and-t-r2/unit/U-11-coverage-summary.json / 1차: build/b-and-t/logs/U-11.log · build/b-and-t/unit/U-11-coverage-summary.json | 3.6 | Met |
| BT-TC-05 | code-generation/code-generation-plan.md ## 시험 전략과 커버리지(J137 모듈 전체) · ## 검증 명령(C안) A1 | 세 백엔드 모듈 각각 모듈 전체 줄 ≥ 80%(태그 필터 없는 `clean check`) | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): A1 PASS. 모듈 전체 LINE: contract-model 98.53% · core-api 95.93% · realtime-activity 92.03% / 1차: A1 PASS(clean check, JaCoCo check 통과). 모듈 전체 LINE: contract-model 98.47% · core-api 95.80% · realtime-activity 89.96% | build/b-and-t-r2/IT-02/report.txt(A1) · build/b-and-t-r2/IT-02/jacoco-A1 / 1차: build/b-and-t/IT-02/report.txt(A1) · build/b-and-t/IT-02/A1-jacoco-module.txt | 3.6 | Met |
| BT-TC-06 | 같음 A2 | `frontend/src/**` 전체 줄 ≥ 80%(A2 `vitest run tests/u1 --coverage`) | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): A2 PASS, A2.coverage NOTE 줄 98.93%(src/**) / 1차: A2 PASS, A2.coverage NOTE 줄 98.82%(src/**) | build/b-and-t-r2/IT-02/report.txt(A2) / 1차: build/b-and-t/IT-02/report.txt(A2) | 3.6 | Met |
| BT-TC-07 | code-generation/code-generation-plan.md ## Testing Contract(obligations.scope_floor) · code-generation/unit-test-instructions.md | 백엔드 U1 시험(4모듈) 실패 0 · 오류 0 · 건너뜀 0, DB 백엔드 기록 | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): U-3-r1: 141클래스 · 1,442건, 실패 0 · 오류 0 · 건너뜀 0(DB 백엔드 docker 10 · local 3). A1 회귀 1,442건 실패 0. 1차 실행(U-3)은 시간 민감 IT 2건 실패(CPU 부하 겹침) → 공통 지침 9대로 한 번 재실행(15절). HEAD 8a9225a 뒤 모듈 전체 명령 1,451건 실패 0(contract-model 279 · core-api 607 · realtime-activity 538 · test-support 27) / 1차: U-3: 118클래스 · 1,196건, 실패 0 · 오류 0 · 건너뜀 0. DB 백엔드 docker(Testcontainers, MujoongPostgresTest는 local도 실행). A1 회귀도 1,196건 실패 0 | build/b-and-t-r2/unit/U-3-junit.txt · build/b-and-t-r2/unit/U-3-junit-run1.txt · build/b-and-t-r2/unit/failures-run1/ · build/b-and-t-r2/unit/U-3-dbbackend.txt · build/b-and-t-r2/IT-02/junit-A1 / 1차: build/b-and-t/unit/U-3-junit.txt · build/b-and-t/unit/U-3-dbbackend.txt · build/b-and-t/IT-02/A1-junit.txt | 3.6 | Met |
| BT-TC-08 | 같음 | 프런트 U1 시험 실패 0 | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): U-11 · U-5: 34파일 514건 통과, 실패 0(부분 U-12 351건 · U-13 183건) / 1차: U-11 · U-5: 34파일 499건 통과, 실패 0(부분 U-12 344건 · U-13 175건 통과) | build/b-and-t-r2/logs/U-5.log · U-11.log · U-12.log · U-13.log · build/b-and-t-r2/unit/vitest-frontend-u1.json / 1차: build/b-and-t/logs/U-5.log · U-11.log · U-12.log · U-13.log | 3.6 | Met |
| BT-TC-09 | 같음 | tools U1 시험 실패 0 | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): U-14 · U-6: 8파일 105건 통과, 실패 0(HEAD b9a53a5 뒤 106건, J393) / 1차: U-14 · U-6: 8파일 105건 통과, 실패 0 | build/b-and-t-r2/logs/U-14.log · U-6.log · build/b-and-t-r2/unit/vitest-tools-u1.json / 1차: build/b-and-t/logs/U-14.log · U-6.log | 3.6 | Met |
| BT-TC-10 | code-generation/code-generation-plan.md ## Testing Contract(obligations.strategy_volume) · code-generation/unit-test-instructions.md ## 기대 커버리지(분량) | 컴포넌트(시험 클래스 · 파일)마다 시험 5 ~ 8개, 정상 1 + 오류 · 경계 2 이상 | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): 백엔드 141클래스 1,442건 중 5건 미만 **0**(1차 26클래스 → 5 ~ 7건). 프런트 34파일 · tools 8파일 5건 미만 0. 참고: 상한 8건은 1차처럼 판정에 쓰지 않음(최대 65건 클래스 있음) / 1차: 백엔드 118클래스 중 26개가 5건 미만(2건 4개: ApprovedContentRepositoryIT · CoreStartupSequenceIT · DbSessionLimitsIT · RealtimeDataAccessWiringIT, 3건 14개, 4건 8개 — 2건 클래스는 정상 1 + 오류 · 경계 2를 담을 수 없음). 프런트 34파일 · tools 8파일은 모두 5건 이상 | build/b-and-t-r2/unit/U-3-junit.txt · build/b-and-t-r2/unit/TC10-backend-per-class.txt · build/b-and-t-r2/unit/TC10-frontend-per-file.txt · build/b-and-t-r2/unit/TC10-tools-per-file.txt / 1차: build/b-and-t/unit/U-3-junit.txt · build/b-and-t/unit/TC10-frontend-per-file.txt · build/b-and-t/unit/TC10-tools-per-file.txt | 3.6 | Met (1차: Not Met) |
| BT-TC-11 | code-generation/code-generation-plan.md ## Testing Contract(strategy_volume) · ## 시험 전략과 커버리지(경계 ① ② ③) | 핵심 경계 통합 시험 3종(PostgreSQL · 앱 사이 HTTP 계약 대역 · WSS) 클래스가 있고 통과 | IT-01: 경계 클래스 39개 모두 있음(MISSING 0), 모두 bad=0 · skipped=0 | build/b-and-t/IT-01/boundary-classes.txt | 3.6 | Met |
| BT-VC-01 | code-generation/code-generation-plan.md ## 검증 명령(C안) · 사람 결정 2 · 11 · J103 · J133 · J144; inception/delivery-planning/bolt-plan.md 3절 B1 완료 조건 ① | `bash scripts/verify.sh` 종료 코드 0, `mode=compose`, A · F · B · C1 ~ C12 · P1 · P2 · P3 · T 모두 PASS(`RESULT PASS`) | rc 0, mode=compose, A1 · A2 · A3 · A4 · F · B · C1 ~ C12 · P1 · P2 · P3 · T 모두 PASS, RESULT PASS total=289s | build/b-and-t/IT-02/report.txt · stdout.txt · rc.txt | 3.6 | Met |
| BT-VC-02 | code-generation/code-generation-plan.md 사람 결정 10 · J134; verification-command.txt | `bash scripts/verify.sh --engine` 종료 코드 0, E0 PASS, 내부 기한 110초 안, 출력 < 1 MiB | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): rc 0, engine=1, E0 PASS, RESULT PASS total=89s(벽시계 91.8초, 기한 110초 안), 표준 출력 581바이트. Docker 재기동 직후 데움 1회(IT-03-warm B TIMEOUT 106초, 판정 아님 J371). 1차보다 30초 느림 — 다른 Gradle 실행 겹침 / 1차: rc 0, engine=1, E0 PASS, RESULT PASS total=59s(벽시계 60.0초), 표준 출력 581바이트 | build/b-and-t-r2/IT-03/report.txt · report-warm.txt · stdout.txt · rc.txt · elapsed.txt / 1차: build/b-and-t/IT-03/report.txt · stdout.txt · rc.txt · elapsed.txt | 3.6 | Met |
| BT-NFR1.1 | nfr-requirements/security-requirements.md ## 1 · ## 4; nfr-design/security-design.md SD-17 | 미승인 구간 주입 시 기기 재생 0 · `approved_for_display` false · 12 응답에 없음, 기기로 나가는 소리는 게이트 통과 구간 · 승인 문구뿐 | OutputGateTest · SessionLifecycleIT 통과, C10(출력 0 · WAITING_FILLER 1) · C11 PASS(IT-02 · IT-03 · IT-06a), 기기 player · device-session 시험 통과 | build/b-and-t/security/st03-backend-security-tests.txt · build/b-and-t/security/st04-frontend.txt · build/b-and-t/IT-02/report.txt | 3.6 | Met |
| BT-NFR1.2 | security-requirements.md ## 1 · ## 4; security-design.md SD-18 | 시드 사전 category 7개 각각 ≥ 1항목, 항목별 폐기 + 대체 문구 재생 자동 시험 통과 | SeedDictionaryTest · SeedDictionaryFileTest · RealtimeStartupChecksTest · OutputGateTest 통과, C10 PASS(7범주 대표 패턴, SCORE는 사람 결정 12) | build/b-and-t/security/st03-backend-security-tests.txt · build/b-and-t/IT-02/report.txt | 3.6 | Met |
| BT-NFR1.3 | security-requirements.md ## 1 · ## 4(Build and Test); security-design.md SD-19 | U1 화면 8곳 · 오류 code별 문구에 진단 · 점수 · 의도 · 감정 단정 표현 0건 | ST-24 어휘 grep 걸림 2줄 모두 화면 글 아님(ko.ts:134 주석, errors.md:47 설명 열). 체크리스트 12묶음 × 8열 모두 0(에이전트 검토, 사람 재검토 권장). copy.test.ts 통과 | build/b-and-t/security/st24-vocab.txt · test-results.md 8.6 · build/b-and-t/security/st04-frontend.txt | 3.6 | Met |
| BT-NFR2.1 | security-requirements.md ## 1 · ## 4(Build and Test); SD-19 | 재촉 · 만류 · 보상 · 완료율 문구 0건, 아이 기기 실패 주입 뒤 화면 유지 | 체크리스트 재촉 · 만류 · 보상 · 완료율 열 0. 기기 클라이언트 실패 주입(429 · 503 · 네트워크 · 닫기 1001 · 1006 · 1011 · 1013 · 4400 · 4408 · CLOSE_TRANSPORT) 뒤 마지막 화면 유지 + 정확히 5초 재시도 시험 통과(jsdom). 승인 문구 2건 0 | build/b-and-t/security/st04-frontend.txt · build/b-and-t/security/st24-vocab.txt · test-results.md 8.6 | 3.6 | Met |
| BT-NFR3.1 | security-requirements.md ## 1 · ## 4; SD-20 | 공급자로 가는 정보 = 지시문 + childContext(displayName · age · interests)뿐(화이트리스트 위반 0) | ProviderRequestWhitelistIT · SessionPackageIT · SessionPackageBuilderTest · ContractConformanceIT 통과 | build/b-and-t/security/st03-backend-security-tests.txt | 3.6 | Met |
| BT-NFR3.2 | security-requirements.md ## 1 · ## 4; SD-20 | 대화 역할 요청에 판정 기준 · 보호자 입력 원문 · 이전 판정 필드가 구조적으로 없음(표식 0건) | ProviderRequestWhitelistIT · RealtimeArchTest 통과 | build/b-and-t/security/st03-backend-security-tests.txt | 3.6 | Met |
| BT-NFR9.1a | security-requirements.md ## 1 · ## 4(Build and Test); SD-1; nfr-design/logical-components.md LC-1 | (로컬 스택) 443은 TLS 1.2 이상만(1.1 이하 거부), HSTS `max-age=15552000`, HTTP → HTTPS 301, 다른 Host · SNI 거절 | ST-07 tls1 · tls1_1 alert protocol version, tls1_2 · tls1_3 협상. ST-08 7경로 모두 HSTS max-age=15552000 정확히 1개. ST-09 80 → 301 https://localhost/x · ACME 404 · 모르는 Host 444. B4.sni · B4.host PASS | build/b-and-t/security/st07-tls.txt · st08-headers.txt · st09-http80.txt · build/b-and-t/IT-02/report.txt | 3.6 | Met |
| BT-NFR9.1b | security-requirements.md ## 1(측정 · 완료 기준); SD-1 · LC-7 | (실제 도메인) 외부 80은 301만 · 443 TLS 1.2+, 인증서 자동 갱신(만료 30일 전 갱신 로그 · 드라이런) | 실행 못 함(실도메인 · Let's Encrypt · EC2 없음) | 기대 증거: operation/environment-provisioning/validation-report.md · operation/deployment-execution/health-check-report.md | 4.2 environment-provisioning · 4.3 deployment-execution | Unverified |
| BT-NFR9.2 | security-requirements.md ## 1 · ## 4; SD-5 | MUJOONG_SESSION HttpOnly · Secure · SameSite=Lax(Path / · Domain 없음), 유휴 30일 갱신형, 만료 · 로그아웃 뒤 401 | U1 측정 기준 통과: CookieAttributesTest · SessionJdbcConfigIT · GuardianApiIT · CorePropertiesValidationTest, C2 PASS(쿠키 속성 · 만료 세션 401 · 세션 ID 교체). 로그아웃 뒤 401은 U1에 로그아웃 엔드포인트가 없어 관측 못 함(U1 요구 문서: 로그아웃은 U2) | build/b-and-t/security/st03-backend-security-tests.txt · build/b-and-t/IT-02/report.txt | 3.6(U1 부분 통과) · U2 Construction 뒤 Build and Test(로그아웃 부분, 사람 지시로 이번 실행 밖) | Unverified |
| BT-NFR9.3 | security-requirements.md ## 1 · ## 4; SD-6 | 면제 5경로 밖 변경 요청 토큰 없음 · 틀림 → 403 `CSRF_TOKEN_INVALID`, `/api/device/**` 통째 면제 없음 | SecurityChainIT 통과, C3 PASS(토큰 없는 register 403 CSRF_TOKEN_INVALID · evil Origin 403), api-client · dev-login 시험 통과 | build/b-and-t/security/st03-backend-security-tests.txt · build/b-and-t/security/st04-frontend.txt · build/b-and-t/IT-02/report.txt | 3.6 | Met |
| BT-NFR9.4 | security-requirements.md ## 1 · ## 4; SD-7 | 미등록 · 만료 · 해제 · 회전 토큰 WSS 4401, 허용 밖 Origin 403, bootstrap 비밀 ≥ 128비트 · 기기 토큰 256비트, DB에는 SHA-256 해시만 | DeviceSecretsTest · SecretCompareTest · DeviceGatewayIT · DeviceBootstrapApiIT 통과, C1 · C4 · C5(evil Origin WSS 403) · B4.ws(101 → 4401) PASS | build/b-and-t/security/st03-backend-security-tests.txt · build/b-and-t/IT-02/report.txt | 3.6 | Met |
| BT-NFR9.5 | security-requirements.md ## 1 · ## 4; SD-9 | 다른 계정 id 조회 · 끝내기 · 상태 → 404(없는 id와 구분 불가), 세션 없음 401 | GuardianApiIT · ContractConformanceIT 통과. ST-23(3) 보호자 id 없는 findById 호출자는 StartActivityService:268 · SessionStateChangedHandler:70뿐(보호자 요청 경로 아님) | build/b-and-t/security/st03-backend-security-tests.txt · build/b-and-t/security/st23-static-review.txt | 3.6 | Met |
| BT-NFR9.6 | security-requirements.md ## 1 · ## 4; SD-8 · LC-13 | 외부 `/internal/**` 403, 토큰 없음 · 틀림 내부 호출 401, 올바른 토큰으로도 보호자 API 불가 | CoreInternalApiIT · RealtimeInternalApiIT · InternalAuthInterceptorTest 통과, B4.forbidden(/internal/x 403) · B4.rawuri 400, ST-08 인코딩 변형 4개 모두 400 | build/b-and-t/security/st03-backend-security-tests.txt · build/b-and-t/security/st08-headers.txt · build/b-and-t/IT-02/report.txt | 3.6 | Met |
| BT-NFR9.7 | security-requirements.md ## 1 · ## 4; SD-13; inception/delivery-planning/bolt-plan.md B1 ② | 교차 스키마 쓰기 · 앱 역할 DDL이 DB에서 거부(SQLSTATE 42501) | SchemaPrivilegeIT 통과, P1 6건 모두 SQLSTATE 42501(IT-02 · IT-03 · ST-05) | build/b-and-t/security/st03-backend-security-tests.txt · build/b-and-t/IT-02/report.txt | 3.6 | Met |
| BT-NFR9.8 | security-requirements.md ## 1 · ## 4; SD-15; bolt-plan.md B1 ③ | 로그(attrs 포함) · 오류 봉투 · 전달 뒤 Outbox · 지표 라벨 · Nginx · PostgreSQL 로그에 전사 · 원음 · 토큰 · 쿠키 · 등록 코드 전체 · 비밀 0건 | 로그 위생 시험 통과, P2.secrets 비밀 값 28개 × 7파일 일치 0, ST-12 format · attrs · names · secrets · nginx · postgres 위반 0, 지표 UUID 라벨 0(ST-11) | build/b-and-t/security/st03-backend-security-tests.txt · build/b-and-t/IT-02/report.txt · build/b-and-t/security/st12-log-hygiene.txt · build/b-and-t/security/st11-mgmt.txt | 3.6 | Met |
| BT-NFR9.9 | security-requirements.md ## 1 · ## 3 · ## 4; SD-10 | bootstrap IP당 1시간 20회 초과 429 · 등록 실패 10분 10회 초과 429(성공 시 초기화) · REST 본문 64KB · WSS 텍스트 8KB · 입력 오디오 965B만 · 업그레이드 뒤 5초 HELLO 없음 4408 · 형식 오류 4400 · prod 한도 20회 그대로 | 한도 시험 통과(SlidingWindowRateLimiterTest · DeviceBootstrapApiIT · DeviceRegisterApiIT · ErrorEnvelopeAndLimitsIT · WsFrameGuardTest · DeviceGatewayIT · ConfigKeyContractTest), B4.body64k 400, ST-10 201×21 → 429×10 · RATE_LIMITED 봉투 31건, ST-23(4) demo · prod 상향 값 0 | build/b-and-t/security/st03-backend-security-tests.txt · build/b-and-t/security/st10-limit-req.txt · build/b-and-t/security/st23-static-review.txt · build/b-and-t/IT-02/report.txt | 3.6 | Met |
| BT-NFR9.10 | security-requirements.md ## 1 · ## 4(Build and Test); SD-12 · SD-16; infrastructure-design/cicd-pipeline.md CD-8 | 비밀이 저장소(이력 포함) · 이미지 · 프런트 번들 · 로그 · 문서에 0건, 환경 파일 권한 600 | A4 걸림 0(1,343파일) · P3 0, IT-05 ① PASS, ST-16 gitleaks 양성 대조 rc 7 · 이력 37커밋 no leaks rc 0, ST-17 추적 env · 키 파일 0 · 번들 걸림 0, env 파일 0600(IT-04c · infra-validate secret-env-files) | build/b-and-t/IT-02/report.txt · build/b-and-t/IT-05/image-checks.log · build/b-and-t/security/st16-gitleaks.txt · build/b-and-t/security/st17-tracked-and-bundle.txt · build/b-and-t/IT-04/IT-04c.log | 3.6 | Met |
| BT-NFR9.11 | security-requirements.md ## 1 · ## 4; SD-14 | demo · prod + dev 또는 `mujoong.dev-login.enabled=true` 조합 기동 거부, dev(false) · demo 단독 · prod 단독 `/api/dev/login` 404, dev + true만 발급 | ProfileGuardBootIT(두 앱 17건씩) · ProfileGuardEnvironmentPostProcessorTest 통과, B5 4건 PASS, IT-05 ④ PASS | build/b-and-t/security/st03-backend-security-tests.txt · build/b-and-t/IT-02/report.txt · build/b-and-t/IT-05/image-checks.log | 3.6 | Met |
| BT-NFR9.12 | security-requirements.md ## 1 · ## 4; SD-2 · SD-11 | CSP(default-src 'self', connect 최소) · nosniff · Referrer-Policy strict-origin-when-cross-origin · frame-ancestors 'none', 잘못된 입력 400 `VALIDATION_FAILED`, 이어 붙인 SQL 0, 브라우저 콘솔 CSP 위반 0 | 통과: ST-08 7경로 헤더 FAIL 0 · nonce 요청마다 다름, csp.test · ErrorEnvelopeAndLimitsIT · NoSqlNowTest, ST-23(1) SQL 연결 허용 2줄뿐. 실행 못 함: 브라우저 콘솔 CSP 위반 0(실기 브라우저 없음, SD-2-b) | build/b-and-t/security/st08-headers.txt · build/b-and-t/security/st04-frontend.txt · build/b-and-t/security/st23-static-review.txt | 3.6(헤더 · 입력 · SQL 통과) · 뒤 단계 미예약(실기 브라우저 콘솔 — 4.3 실기 점검에 넣을지 사람 결정) | Unverified |
| BT-NFR9.13 | security-requirements.md ## 1 · ## 4(Build and Test); SD-16 | 의존성 취약점(Gradle 의존성 검사 · npm audit) high 이상 0건, 예외는 사유 · 만료일 있는 목록만 | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): ST-19 `trivy rootfs`(운영 bootJar 2개) HIGH · CRITICAL **0** rc 0(Tomcat 11.0.26 · Jackson 3.1.7 · 2.22.3). ST-20 image-checks ③ PASS — 이미지 5종 각각 `trivy image --severity HIGH,CRITICAL --ignore-unfixed` 0(보충 표). npm 관문 rc 0(braces 예외 1건 candidate), omit-dev 0. 예외 목록 1건 불변. 로컬 실행분이며 CI 관문 실측은 뒤 단계 그대로(J376) / 1차: ST-19 Trivy(운영 bootJar 2개) rc 1: HIGH · CRITICAL 21건(고유 CVE 8 — tomcat-embed-core 11.0.24 CRITICAL 3종 → 11.0.25, jackson-core · jackson-databind 3.1.5 · 2.22.1 HIGH 5종 → 3.1.7 · 2.22.3). ST-20 이미지 ③ FAIL(5종 모두, 고유 CVE core-api 9 · realtime 9 · web 14 · migrate 32 · backup 25, 고칠 판 있음). npm 관문 rc 0(frontend high 5 = braces 예외 candidate, tools 0) | build/b-and-t-r2/security/trivy-backend.txt · build/b-and-t-r2/security/st20-image-checks.txt · build/b-and-t-r2/security/st20-trivy-*.txt · build/b-and-t-r2/security/st18-npm.txt / 1차: build/b-and-t/security/trivy-backend.txt · build/b-and-t/security/trivy-backend-findings.txt · build/b-and-t/security/st20-image-checks.txt · build/b-and-t/security/st20-trivy-summary.txt · build/b-and-t/security/st18-npm.txt | 3.6(로컬 실행분) · 뒤 단계 미예약(CI 관문 실측, 3.7 ci-pipeline SKIP) | Met (1차: Not Met) |
| BT-NFR9.14 | security-requirements.md ## 1 · ## 4; nfr-design/observability-design.md OD-13 | 보안 사건 5종이 사건 코드 한 줄 + `mujoong_security_event_total{type}`, 토큰 · 코드 전체 없음, attrs 허용 표 안 | CoreLogHygieneIT · EventLoggerTest · EventLoggerMergeTest 통과, mujoong_security_event_total 두 앱 노출(core auth_failed 2 · origin_denied 1, realtime origin_denied 1), ST-12 attrs 위반 0 | build/b-and-t/security/st03-backend-security-tests.txt · build/b-and-t/security/st11-mgmt.txt · build/b-and-t/IT-06/metric-names.txt | 3.6 | Met |
| BT-NFR12.1a | security-requirements.md ## 1 · ## 4; SD-21; nfr-design/reliability-design.md RD-11 | U1이 저장하는 모든 Outbox/Inbox 새 행의 `guardian_id` 채움(DeviceStatusChanged 포함), 삭제 참가자 셋 멱등 | DeletionParticipantsCoreIT · CoreMessagingRepositoryIT · CoreMessagingFlowIT · RealtimeMessagingIT · RealtimeMessagingRepositoryIT 통과 | build/b-and-t/security/st03-backend-security-tests.txt · build/b-and-t/unit/U-3-junit.txt | 3.6 | Met |
| BT-NFR12.1b | security-requirements.md ## 4(Build and Test, U2 통합); SD-21 | 삭제 지휘 완료 뒤 다섯 표에서 그 보호자 행 0건(guardian_id 또는 삭제된 deviceId), 재호출 멱등 | 실행 못 함(U2 코드 없음) | 기대 증거: U2 3.5 뒤 다시 도는 3.6의 test-results.md | 뒤 Build and Test(U2 3.5 이후, 사람 지시로 이번 실행 밖) | Unverified |
| BT-NFR12.2 | security-requirements.md ## 1 · ## 4(Build and Test); SD-22 | 원음이 디스크 · DB · Outbox · 로그에 0바이트(세션 뒤 표식 바이트열 0건), ACTIVE 전 프레임 폐기, 자동 덤프 설정 없음 | InputRelayTest · RealtimeLogHygieneIT(표식 바이트 0 · 프레임 경로 DB 연결 0) · RealtimeArchTest 통과, ST-11 두 앱 ulimit_core=0 · ExitOnOutOfMemoryError 있음 · HeapDumpOnOutOfMemoryError 끔 · heapdump 404, ST-23(5) realtime bytea 0 | build/b-and-t/security/st03-backend-security-tests.txt · build/b-and-t/security/st11-mgmt.txt · build/b-and-t/security/st23-static-review.txt | 3.6 | Met |
| BT-NFR12.3 | security-requirements.md ## 1 · ## 4; SD-23 | PUBLISHED · PROCESSED 행 payload `{}` · `purged_at`, FAILED · DEAD는 7일 뒤 비움 | CoreMessagingRepositoryIT · RealtimeMessagingRepositoryIT · MessagingJobsTest 통과 | build/b-and-t/security/st03-backend-security-tests.txt | 3.6 | Met |
| BT-NFR12.4 | security-requirements.md ## 1 · ## 4(Build and Test); SD-24 | 백업은 합성 데이터만(`data_class` synthetic 아니면 거부), D-14 승인 항목에 백업 충돌 해소 · 정식 사전 전 실제 아동 데이터 시연 금지 | ST-15 data_class=real → result=REFUSED reason=data_class rc 3, synthetic 대조 → result=OK rc 0. IT-05 ④ 더미 덤프 OK. ST-25 D-14 항목(cloud_run_report.md:239) · init synthetic(01-roles.sql:63) · '운영 전' 행(code-summary.md:702) · 시드 합성 표지 확인. 승인 자체는 사람 결정으로 남음 | build/b-and-t/security/st15-backup-refusal.txt · build/b-and-t/security/st25-docs.txt · build/b-and-t/IT-05/image-checks.log | 3.6 | Met |
| BT-NFR4.1 | nfr-requirements/reliability-requirements.md ## 1 · ## 4; reliability-design.md RD-4 | 마지막 기기 메시지 뒤 40초(직전 변화 없음 · 직후 1회) DISCONNECTED, ACTIVE는 ABORTED(DISCONNECT), CLOSING 중엔 아님 | DeviceGatewayIT(40초 무음 판정 · 회복) · SessionTimersTest 통과 | build/b-and-t/unit/U-3-junit.txt · build/b-and-t/logs/U-10.log | 3.6 | Met |
| BT-NFR4.2 | reliability-requirements.md ## 1 · ## 4; RD-5 | 공급자 연결 거부 · 무응답 → FAILED(PROVIDER_UNAVAILABLE) 1회 · CLOSE_TRANSPORT(FAILED) · 새 키 시작 가능 · UNRESPONSIVE 아님 | ProviderClientIT · SessionExecutorTest 통과 | build/b-and-t/unit/U-3-junit.txt | 3.6 | Met |
| BT-NFR4.3 | reliability-requirements.md ## 1 · ## 4; RD-6 | 보고 30초 직전 STABLE · 직후 UNKNOWN · canStart false, 조회 한 번 실패는 stale 아님 | StaleEvaluatorTest 통과, IT-06d Realtime 정지 36초 뒤 network UNKNOWN · canStart false · DEVICE_STATUS_UNKNOWN | build/b-and-t/unit/U-3-junit.txt · build/b-and-t/IT-06/home-rt-stop-36s.json | 3.6 | Met |
| BT-NFR4.4 | reliability-requirements.md ## 1 · ## 4; RD-3 | CLOSING 뒤 30초 직전 ENDED · 직후 ENDED_UNCONFIRMED + UNRESPONSIVE, 늦은 확인이 전이를 못 되돌림 | SessionTimersTest 통과 | build/b-and-t/logs/U-10.log · build/b-and-t/unit/U-3-junit.txt | 3.6 | Met |
| BT-NFR4.5 | reliability-requirements.md ## 1 · ## 4; RD-7 | 같은 키 · 같은 eventId 반복에 세션 1 · 처리기 호출 1, 다른 본문 409 두 종류 | CoreMessagingFlowIT · ActivityStartEndIT 통과 | build/b-and-t/IT-01/boundary-classes.txt | 3.6 | Met |
| BT-NFR4.6 | reliability-requirements.md ## 1 · ## 4; RD-7 | 이벤트 순서 뒤바꿈에도 최종 값 같음, 과거 응답 재생 0, 로컬 FAILED 사본 취소 재전송 | CoreMessagingFlowIT 통과 | build/b-and-t/IT-01/boundary-classes.txt | 3.6 | Met |
| BT-NFR4.7 | reliability-requirements.md ## 1 · ## 4(Build and Test 2행); RD-1 · RD-10 · RD-12 | 재시작 시 비종료 세션 FAILED(SERVER_RESTART) · CONNECTED 행 DISCONNECTED · Outbox/Inbox 이어 처리 · FAILED 제어 이벤트 4종 재무장(10분 정지 뒤 DeviceReleased 전달 · 옛 소켓 4401), 일반 이벤트 20회 상한 유지 | RealtimeStartupRecoveryIT · CoreStartupSequenceIT 통과, IT-06g 강제 종료 3회마다 STARTUP_RECOVERY 한 줄. 진행 중 세션 강제 종료 · A1 10분 재무장(RD-10)은 드라이버가 없어 실행 못 함 | build/b-and-t/IT-01/boundary-classes.txt · build/b-and-t/IT-06/crash-recovery.txt | 뒤 단계 미예약(시험 · 도구 부재 — 사다리 2단: 코드 생성 몫 후보) | Unverified |
| BT-NFR4.8 | reliability-requirements.md ## 1 · ## 3.3 · ## 4(11/2); RD-18 · OD-14 | 호스트 밖 프로브 3종 1분 간격, 계획 배포 제외 누적 가용성 ≥ 99.0%(11/2 시점 최소 4일) | 실행 못 함(배포 · 외부 프로브 4일 이상 · probe-daily 없음) | build/b-and-t/S13/searches.txt(probe-daily 0) | 뒤 단계 미예약(운영 측정 · 11/2 보고, 4.4 observability-setup SKIP) | Unverified |
| BT-NFR4.9 | reliability-requirements.md ## 1 · ## 3.3 · ## 4(Build and Test); RD-17 · LC-12 | 컨테이너 강제 종료 뒤 헬스 통과 5분 이내(목표 1분) 3회, 앱만 죽은 동안 502 뒤 정상 복귀 | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): IT-06g kill -9 3회: realtime 9.5초 · core-api **20.3**초 · realtime 9.4초에 healthy(5분 · 1분 안), 입구 502 뒤 OK 복귀(probe downtime 9 · 18 · 8), STARTUP_RECOVERY 매번 1줄, pause 45초 → unhealthy · 재시작 0. core-api 20.3초는 1차 9.1초 대비 10% 이상 증가 → 위험 목록(환경 요인: 재부팅 직후 · 다른 Gradle 실행) / 1차: IT-06g kill -9 3회: realtime 7.3초 · core-api 9.1초 · realtime 6.8초에 healthy(5분 · 1분 안), 그동안 입구 502(probe downtimeSec 7 · 9 · 7) 뒤 OK 복귀. hang(pause 45초)은 unhealthy인데 재시작 0회 — 자동 복구 없음 기록 | build/b-and-t-r2/IT-06/crash-recovery.txt · probe-crash-*.log · build/b-and-t-r2/logs/IT-06g.log / 1차: build/b-and-t/IT-06/crash-recovery.txt · build/b-and-t/IT-06/probe-crash-*.log · build/b-and-t/IT-06/hang.txt | 3.6 | Met |
| BT-NFR4.10 | reliability-requirements.md ## 1 · ## 4(Build and Test); RD-19; cicd-pipeline.md CD-4 | 계획 배포 서비스 중단 ≤ 60초(1초 간격 health), 기기 웹 자동 재접속 | 실행 못 함(리허설 하네스 · 실배포 경로 없음) | 기대 증거: operation/deployment-execution/deployment-log.md, 호스트 /var/log/mujoong/deploy.log | 4.3 deployment-execution | Unverified |
| BT-NFR4.11a | reliability-requirements.md ## 1 · ## 3.3 · ## 4(Build and Test); RD-20 · LC-8 | 매일 03:00 KST 논리 백업 · 7일 보관 · 7일치 생성 로그(겹침 방지 · 실패 시 partial 삭제), RPO 24시간(호스트 상실 제외) | IT-05 ④ backup 스케줄러 생존 · 더미 덤프 1회 OK. 7일치 기록은 실호스트 · 시간 조건이라 실행 못 함 | build/b-and-t/IT-05/image-checks.log | 4.2 environment-provisioning(7일 기록은 운영) | Unverified |
| BT-NFR4.11b | reliability-requirements.md ## 1 · ## 4; RD-20 | 빈 DB로 복원 리허설 1회 R1 ~ R8 합계 ≤ RTO 60분 | 실행 못 함(복원 리허설은 호스트 절차, 검증 스택은 backup 끔) | 기대 증거: operation/environment-provisioning/validation-report.md | 4.2 environment-provisioning | Unverified |
| BT-NFR4.12 | reliability-requirements.md ## 1 · ## 4; RD-15 · LC-3 · LC-12 | Realtime 장애 시 정지 30초 이내 시작 503 · 30초 뒤 423 `DEVICE_NOT_STABLE` · 끝내기 503 · 04/05 30초 뒤 '확인 불가' · 12 사본 응답, Core 정지 시 새 WSS 1013 · 세션 유지 · Outbox 재시도 | IT-06d: Realtime 정지 중 Core 답함(로그인 200), 36초 뒤 UNKNOWN · 시작 423 DEVICE_NOT_STABLE, 12 조회 200(사본, fallback 1 · conn_error 1). IT-06e: Core 정지 중 WSS 101 → 4401, Realtime 보고 conn_error 0 → 3. 30초 이내 시작 503 · 끝내기 503 · Core 정지 중 새 WSS 1013 · 세션 유지 · Outbox 재시도는 드라이버가 없어 실행 못 함 | build/b-and-t/IT-06/home-rt-stop-*.json · start-rt-stop.json · prom-core-after-rt-stop.txt · ws-core-stop.json · prom-rt-core-stop.txt | 뒤 단계 미예약(장애 주입 드라이버 부재 — 사다리 2단: 코드 생성 몫 후보) | Unverified |
| BT-NFR4.13 | reliability-requirements.md ## 1 · ## 4(Build and Test); RD-16 · OD-11 · LC-14 | 앱별 liveness · readiness, 실행 중 DB 정지 시 앱이 직접 503(`/internal/events` 503 · Retry-After), DB 복귀 뒤 READY, 앱 컨테이너 정지 시 Nginx 502, DB → migrate → 앱 → web → Nginx 기동 순서 | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): 통과: DB 정지 → 앱 직접 503 SERVICE_NOT_READY · Retry-After 5(20초), 두 앱 /internal/events 503(재실행 17 · 22초), 복귀 4 · 11초 뒤 200, 기동 순서, HealthAndReadinessIT. 앱 컨테이너 정지: core-api `docker stop` 직후 첫 /api/health **502** UPSTREAM_UNAVAILABLE 3.008초(연결 시한 3초 안), 이후 502 4 ~ 13ms; web 정지 /device 첫 요청 502 점검 페이지 3.007초, 이후 5 ~ 6ms; 다시 띄운 뒤 200 · 200. hang(docker pause)은 설계 J354대로 504 유지(IT-05 ⑧ 사례 3 PASS). 정지 직후 약 1.6초 안 요청은 3초 뒤 502(J347 · J354, 상태는 요구대로) / 1차: 통과: DB 정지 → /api/health 앱 직접 503 SERVICE_NOT_READY · Retry-After 5(19초), 두 앱 /internal/events 503(재실행 18 · 19초), 복귀 4 ~ 5초 뒤 200, 기동 순서(B2 · B3 · IT-05 ④), HealthAndReadinessIT. 미달: 앱 컨테이너 정지(docker stop) 때 입구가 502가 아니라 504(core-api 정지 /api/health 504 UPSTREAM_UNAVAILABLE rt 3.004초 'upstream timed out while connecting', web 정지 /device 504, SIGTERM 뒤 정지 상태 504). 강제 종료(재시작 중)에는 502 | build/b-and-t-r2/IT-06/health-core-stop-timing.txt · health-core-stop-*.json · web-stop.txt · health-db-stop.txt · internal-db-stop-r1.txt · build/b-and-t-r2/IT-05/image-checks.log / 1차: build/b-and-t/IT-06/health-db-stop.txt · internal-db-stop-r1.txt · health-core-stop.json · web-stop.txt · sigterm-r1/sigterm.txt · build/b-and-t/logs/IT-06e.log | 3.6 | Met (1차: Not Met) |
| BT-NFR4.16 | reliability-requirements.md ## 1 · ## 4; RD-21 | 폰 네트워크 오류 · 시간 초과 시 '끝내기 요청을 보내지 못했어요' 문구, 종료 요청됨 표시 0, 서버 end 호출 0, 세션 ACTIVE 유지(+ 실기 1회) | p1-dialog.test.tsx 통과(끝내기 실패 문구 · 종료 요청됨 0 · end 호출 0 · ACTIVE 유지). 실기 1회는 실행 못 함 | build/b-and-t/logs/U-13.log | 3.6(화면 시험 통과) · 뒤 단계 미예약(실기 1회) | Unverified |
| BT-NFR6.1 | reliability-requirements.md ## 1 · ## 4; RD-24 | 시간 숫자 상수 0건(허용 목록 제외), 코드가 읽는 `mujoong.*` 키 = 레지스트리 · `contracts/timing.md` | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): ConfigKeyContractTest(두 앱) 통과. S13 검색: 1차 허용 밖 4종(AsyncRecorder 5ms · ConnectionSender · InputRelay 1초 · EventLogger.forTest) 모두 없음(J305 ~ J308). 남은 걸림은 단위 · 설정값 변환과 허용 목록 줄(CoreMetrics REST_FAST 300ms · VIEW_FAST 500ms J324, InboxDispatcher keepAlive 0L J307 · J327)뿐. 자동 검사 `TimeLiteralScanTest` core 6 · realtime 7건 통과(허용 목록 core 10 · realtime 16줄, 사라진 허용 줄 금지), `forTest` 운영 소스 0 / 1차: ConfigKeyContractTest(두 앱) 통과. S13 시간 리터럴 검색에서 허용 목록(J124 DB 재시도 지터 · 프레임 20ms) 밖 상수 발견: AsyncRecorder DRAIN_POLL Duration.ofMillis(5), ConnectionSender:293 · InputRelay:238 await(1, TimeUnit.SECONDS), 두 앱 InboxDispatcher keepAlive 0L(MILLISECONDS), EventLogger.forTest(운영 소스 안 시험용 생성자) ofSeconds(10). 시간 숫자 상수 자동 검사(ArchUnit)는 없음 | build/b-and-t-r2/S13/searches.txt · build/b-and-t-r2/unit/regression-F1-F5.txt / 1차: build/b-and-t/S13/searches.txt | 3.6 | Met (1차: Not Met) |
| BT-NFR6.2 | reliability-requirements.md ## 1 · ## 4; RD-2 | 네 안전 시한(10 · 20 · 30 · 40초)과 30초 stale 직전 · 직후 경계 주입 시험 통과, 실제 시간 대기 없음 | SessionTimersTest · PreparingTimeoutCheckerTest · StaleEvaluatorTest · DeviceGatewayIT 통과 | build/b-and-t/unit/U-3-junit.txt | 3.6 | Met |
| BT-NFR6.3 | reliability-requirements.md ## 1 · ## 4; RD-3 | 시한 vs ACK 경합에서 전이 1회, 늦은 신호 무시 | ActivitySessionRepositoryIT · SessionExecutorTest 통과 | build/b-and-t/IT-01/boundary-classes.txt · build/b-and-t/unit/U-3-junit.txt | 3.6 | Met |
| BT-NFR6.4 | reliability-requirements.md ## 1 · ## 4; RD-2 | 시각은 Clock 빈으로만(시스템 시각 · SQL now 직접 호출 0) | RealtimeArchTest · CoreArchTest(시각 직접 호출 0 규칙) · NoSqlNowTest 통과 | build/b-and-t/security/st03-backend-security-tests.txt · build/b-and-t/unit/U-3-junit.txt | 3.6 | Met |
| BT-NFR4.14 | nfr-requirements/observability-requirements.md ## 1 · ## 4; observability-design.md OD-9 | 종료 상태 4개 + Core 로컬 FAILED마다 `session_ended` 라벨과 SESSION_TRANSITION, CORE_ABANDONED 이중 집계 없음 | 종료 상태 라벨 시험(PreparingTimeoutCheckerTest 등) 통과, realtime mujoong_session_ended_total 노출(Core 카운터는 코드 등록 · 로컬 FAILED가 없어 미등록) | build/b-and-t/unit/U-3-junit.txt · build/b-and-t/IT-06/metric-names.txt | 3.6 | Met |
| BT-NFR4.15 | observability-requirements.md ## 1 · ## 3.3 · ## 4(Build and Test); OD-5 · OD-11 · OD-14 | 내부 지표(앱 헬스 · DB 연결 · 열린 기기 연결 수 · 연결 상태 분포 · 내부 HTTP 실패율 · 사본 폴백 · 풀 대기 초과 · 보안 사건) 존재, 외부 `/actuator` 403(health 한 경로만 200), Realtime 정지 시 internal_http 실패 증가 | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): IT-06b 스크랩: `mujoong_device_connection_state` realtime 1, `mujoong_db_pool_wait_exceeded_total` core 1 · realtime 1, `mujoong_device_connections_open` realtime 1(설계 이름, `ws_connections_open` 없음, J295), health_up(NaN 아님, J309) · internal_http(IT-06d 사본 폴백 0 → 1 · conn_error, IT-06e conn_error 3) · security_event 있음, 외부 /actuator 4경로 403 · /api/health 200 / 1차: 있음: health_up · hikaricp(DB 연결) · internal_http(실패 증가 IT-06d conn_error +1 · IT-06e +3) · session_view_fallback(IT-06d 1) · security_event, 외부 /actuator 4경로 403 · /api/health 200. 없음: 연결 상태 분포(mujoong_device_connection_state) · 풀 대기 초과(mujoong_db_pool_wait_exceeded_total). 열린 기기 연결 수는 mujoong_ws_connections_open 이름으로 노출(설계 이름 mujoong_device_connections_open) | build/b-and-t-r2/IT-06/metric-names.txt · prom-*.txt · actuator-external.txt · prom-core-after-rt-stop.txt / 1차: build/b-and-t/IT-06/metric-names.txt · metric-missing-classification.txt · actuator-external.txt · prom-core-after-rt-stop.txt · prom-rt-core-stop.txt | 3.6 | Met (1차: Not Met) |
| BT-NFR5.4 | observability-requirements.md ## 1 · ## 3.4 · ## 4(Build and Test); OD-8 | 한 턴 로그에 LATENCY_TURN · SESSION_ITEMS_SERVED 시각 키 전부, 한 턴 로그만으로 NFR5.1 · 5.2 · 5.3(서버 몫)을 계산하는 스크립트가 돈다(미관측 사유 표시) | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): `git ls-files` turn-metrics 1. `bash infra/bin/turn-metrics --file latency-it02.jsonl` rc 0: NFR5.1 20ms(보류, 표본 1) · NFR5.2 1044ms ± 2ms(보류) · NFR5.3 서버 몫 13ms(보류) · 미관측 1/2턴 GATE_DISCARD. it03도 rc 0. C12 PASS · LATENCY_TURN · SESSION_ITEMS_SERVED 키 있음. 도구 시험 `test-host-tools.sh` 사례 9(두 Compose 판 32/32) / 1차: LatencyRecorderTest 통과, C12 PASS, LATENCY_TURN · SESSION_ITEMS_SERVED 시각 키 확인(PT-02). 한 턴 로그만으로 NFR5.1 · 5.2 · 5.3을 계산하는 스크립트(turn-metrics)가 저장소에 없음(git ls-files 0) | build/b-and-t-r2/PT-05/turn-metrics.txt · build/b-and-t-r2/PT-02/latency-it0*.jsonl · build/b-and-t-r2/IT-04/IT-04c.log · IT-04c-c238.log / 1차: build/b-and-t/PT-02/latency-it02.jsonl · build/b-and-t/S13/searches.txt | 3.6 | Met (1차: Not Met) |
| BT-NFR6.5 | observability-requirements.md ## 1 · ## 4; OD-10 | 시한 5종 발동마다 `timeout_fired{type}` 증가 + TIMEOUT_FIRED(개인정보 없음), stale은 첫 판정만 | StaleEvaluatorTest · SessionTimersTest 통과(스택에서는 시한 발동이 없어 timeout_fired 미등록, 코드 등록 확인) | build/b-and-t/unit/U-3-junit.txt · build/b-and-t/IT-06/metric-missing-classification.txt | 3.6 | Met |
| BT-NFR9.15 | observability-requirements.md ## 1 · ## 4; OD-1 · OD-2 · OD-3 | 로그 JSON 최상위 여섯 필드 + attrs(사건 코드별 허용 키, 숫자 · 열거 · UTC 시각만, eventId만 UUID) | StructuredLogFormatTest 통과, P2 PASS, ST-12 format · attrs 위반 0 | build/b-and-t/security/st03-backend-security-tests.txt · build/b-and-t/security/st12-log-hygiene.txt | 3.6 | Met |
| BT-OD-06 | nfr-design/observability-design.md OD-6 · ## 4; observability-requirements.md RD-14 | METRIC_SNAPSHOT이 앱마다 60초에 한 줄(숫자 키만), 재시작 뒤에도 이전 줄 보존 | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): 65초 대기 뒤 METRIC_SNAPSHOT core-api 1줄(16키) · realtime 1줄(12키), attrs 숫자 아닌 값 0(J302 · J326). 재시작 뒤 이전 줄 보존은 컨테이너 로그 드라이버 몫 — 따로 관측하지 않음(J375) / 1차: IT-06b 65초 대기 뒤 METRIC_SNAPSHOT core-api 0줄 · realtime 0줄(60초 발행 코드 없음) | build/b-and-t-r2/IT-06/metric-snapshot.txt · metric-snapshot-detail.txt · metric-snapshot-*.jsonl / 1차: build/b-and-t/IT-06/metric-snapshot.txt | 3.6 | Met (1차: Not Met) |
| BT-SLO-01 | observability-requirements.md ## 3.1(SLO 표) | 시작 성공률(ACTIVE ÷ REQUESTED) ≥ 95%(7일 롤링, 표본 20 미만이면 건수) | 실행 못 함(운영 7일 창 · sli-report 없음) | build/b-and-t/S13/searches.txt(sli-report 0) | 뒤 단계 미예약(운영 7일 창 · 11/2, 4.4 observability-setup SKIP) | Unverified |
| BT-SLO-02 | observability-requirements.md ## 3.1 | 시작 → ACTIVE(PREPARING → ACTIVE) p95 ≤ 10초(7일 롤링) | 실행 못 함(운영 7일 창 · sli-report 없음) | build/b-and-t/S13/searches.txt | 뒤 단계 미예약(운영 7일 창 · 11/2, 4.4 observability-setup SKIP) | Unverified |
| BT-SLO-03 | observability-requirements.md ## 3.1 | (FAILED + ABORTED) ÷ 종료 세션 ≤ 10%(CORE_ABANDONED 중복 제외, 7일) | 실행 못 함(운영 7일 창 · sli-report 없음) | build/b-and-t/S13/searches.txt | 뒤 단계 미예약(운영 7일 창 · 11/2, 4.4 observability-setup SKIP) | Unverified |
| BT-SLO-04 | observability-requirements.md ## 3.1 | 종료 확인률 ENDED ÷ (ENDED + ENDED_UNCONFIRMED) ≥ 95%(7일) | 실행 못 함(운영 7일 창 · sli-report 없음) | build/b-and-t/S13/searches.txt | 뒤 단계 미예약(운영 7일 창 · 11/2, 4.4 observability-setup SKIP) | Unverified |
| BT-SLO-05 | observability-requirements.md ## 3.1 | 이벤트 최종 실패(Outbox FAILED + Inbox DEAD) 0건(7일, 장애 중 발생은 따로 집계) | 실행 못 함(운영 7일 창). 참고(판정 아님): IT-02 · IT-03 이벤트 전달 FAILED · DEAD 0건 | build/b-and-t/PT-02/event-delivery-it02.jsonl · event-delivery-it03.jsonl | 뒤 단계 미예약(운영 7일 창 · 11/2, 4.4 observability-setup SKIP) | Unverified |
| BT-NFR5.1 | nfr-requirements/performance-requirements.md ## 1 · ## 3 · ## 4.2; performance-design.md PD-20 | relayIn p95 + relayOut p95 ≤ 0.5초(실공급자, 판정 표본 RD-13) | 실행 못 함(실공급자 · 11/2). 기록(판정 아님, 가짜 공급자): relayInMs 1 · 2, relayOutMs 8 · 8 | build/b-and-t/PT-02/latency-it02.jsonl · latency-it03.jsonl | 뒤 단계 미예약(11/2 측정 창, 4.6 performance-validation SKIP) | Unverified |
| BT-NFR5.2 | performance-requirements.md ## 1 · ## 3 · ## 4.2 | 말 끝 t0 → 기기 첫 음성 t3 p95 ≤ 2.5초(짧은 응답 기준, 미달은 조정 대상) | 실행 못 함(실공급자 · 11/2). 기록(판정 아님): 정상 턴 t3 − t0 약 1.04초(가짜 공급자, quietWaitMs 801) | build/b-and-t/PT-02/latency-it02.jsonl | 뒤 단계 미예약(11/2 측정 창, 4.6 performance-validation SKIP) | Unverified |
| BT-NFR5.3a | performance-requirements.md ## 1 · ## 4.2; 재점검 RD-4 | 서버 몫(전사 확정 → 12 응답 포함 가능, CHILD 행) p95 ≤ 0.5초 | 실행 못 함(실공급자 · 빠른 폴러 없음) | build/b-and-t/PT-02/latency-it02.jsonl | 뒤 단계 미예약(11/2 측정 창, 4.6 performance-validation SKIP) | Unverified |
| BT-NFR5.3b | performance-requirements.md ## 4.1(Build and Test) | 가짜 공급자 통합: 전사 확정 시각과 처음 12 응답에 실린 시각(SESSION_ITEMS_SERVED)이 로그에 남고 12 items에 같은 행 | C9 · C12 PASS, SESSION_ITEMS_SERVED CHILD 행에 tTranscriptFinal · tViewItemReady · tViewItemFirstServed 기록(서버 몫 IT-02 9 · 4ms, IT-03 8 · 3ms — 기록만), RealtimeInternalApiIT 통과 | build/b-and-t/PT-02/latency-it02.jsonl · latency-it03.jsonl · build/b-and-t/IT-02/report.txt | 3.6 | Met |
| BT-NFR5.5a | performance-requirements.md ## 1 · ## 3 · ## 4.2 | 보호자 · 기기 REST 서버 처리 p95 ≤ 300ms(30쌍 · 세션 2 · 40 RPS, 내부 HTTP 제한 초과는 실패) | 실행 못 함(부하 도구 · EC2 없음). 기록(판정 아님): 로컬 le=0.3 비율 core 32/32 · realtime 8/8 | build/b-and-t/IT-06/pt04-buckets.txt | 뒤 단계 미예약(11/2 측정 창, 4.6 performance-validation SKIP) | Unverified |
| BT-NFR5.5b | performance-requirements.md ## 1 · ## 3 · ## 4.2 | 12 진행 조회(Realtime 중계 포함) p95 ≤ 500ms | 실행 못 함(부하 도구 · EC2 없음) | — | 뒤 단계 미예약(11/2 측정 창, 4.6 performance-validation SKIP) | Unverified |
| BT-NFR5.5c | performance-requirements.md ## 4.1(Build and Test) | 요청 타이머가 경로 템플릿 라벨만(id 라벨 0), Realtime 응답 실패 시 12가 사본으로 답하고 폴백 · 실패 카운터 증가 | uri 라벨 19종 모두 경로 템플릿(UUID 0). Realtime 정지 중 12 조회 200(사본) · session_view_fallback_total 0 → 1 · internal_http conn_error 1, SessionViewServiceTest 통과. 설계 문구의 '1.2초 지연'은 지연 주입 도구가 없어 정지로 대신함 | build/b-and-t/IT-06/uri-labels.txt · prom-core-before-rt-stop.txt · prom-core-after-rt-stop.txt · view-rt-stop-code.txt | 3.6 | Met |
| BT-NFR5.6a | performance-requirements.md ## 1 · ## 3 · ## 4.2; PD-14 · PD-17 | /device D0 코드 표시 p95 ≤ 3초(와이파이, 표본 100 미만이면 10회 중 9회) | 실행 못 함(실기 없음) | — | 뒤 단계 미예약(11/2 측정 창 · 실기, 4.6 performance-validation SKIP) | Unverified |
| BT-NFR5.6b | 같음 | /parent 04 LCP ≤ 2.5초(Android Chrome 중급기 4G) | 실행 못 함(Lighthouse · 실기 없음) | — | 뒤 단계 미예약(11/2 측정 창 · 실기, 4.6 performance-validation SKIP) | Unverified |
| BT-NFR5.6c | 같음 | T19 「켜기」 → HELLO_OK p95 ≤ 2초 | 실행 못 함(실기 없음) | — | 뒤 단계 미예약(11/2 측정 창 · 실기, 4.6 performance-validation SKIP) | Unverified |
| BT-NFR5.7 | performance-requirements.md ## 1 · ## 4.1(Build and Test); OD-7 | traceId가 보호자 REST → Core → 내부 HTTP → Realtime → Outbox · Inbox `trace_id` → 처리기 로그까지 같고, 바깥 이상한 값은 새로 만든다 | CoreMessagingFlowIT 통과, B4.trace PASS(위조 값 미사용), IT-06c traceId 하나(32자 16진수)가 Nginx 접근 로그 1줄 · Core 로그(EVENT_DELIVERY 5 · INTERNAL_HTTP 1 · SESSION_TRANSITION 1) · Realtime 로그(EVENT_DELIVERY 5 · LATENCY_TURN 2 · SESSION_ITEMS_SERVED 3 · SESSION_TRANSITION 5) · realtime_outbox_event 5행 · core_inbox_event 5행에서 같음 | build/b-and-t/IT-06/trace.txt · trace-core.txt · trace-realtime.txt · trace-nginx.txt · trace-rt-outbox.txt · trace-core-inbox.txt | 3.6 | Met |
| BT-NFR5.8 | performance-requirements.md ## 1 · ## 4.1; performance-design.md PD-19 | (U1 몫) PLAYBACK_MARK(STOPPED, LOCAL_VOICE_ACTIVITY)의 observedAt · 원인이 세션 단위 로그(PLAYBACK_MARK_STOPPED)에 남음 | 실행 못 함: 기록 코드(SessionExecutor.logStopped)는 있으나 PLAYBACK_MARK(STOPPED, LOCAL_VOICE_ACTIVITY)를 만드는 시험 · 드라이버가 없음(스택 한 턴에서 PLAYBACK_MARK_STOPPED 0줄, traceability Deferred) | — | 뒤 단계 미예약(기록 경로 시험 부재) · 0.5초 판정은 U3 | Unverified |
| BT-PD-08 | nfr-design/performance-design.md PD-8 · ## 4(Build and Test) | 10만 행 Outbox/Inbox + PENDING 200행에서 SQ-15 · SQ-16 EXPLAIN 색인 접근, SQ-6 · SQ-7 부분 색인 사용, 후보 정렬 · 배치 상한 | 실행 못 함(EXPLAIN 시험 없음) | — | 뒤 단계 미예약(시험 · 도구 부재 — 사다리 2단: 코드 생성 몫 후보) | Unverified |
| BT-PD-09 | performance-design.md PD-9 · ## 4 | 질의 수 예산: D0 상태 확인 1질의, 12 진행 조회 Core 1 + Realtime 2, N+1 없음 | 실행 못 함(질의 수 단언 시험 없음) | — | 뒤 단계 미예약(시험 · 도구 부재 — 사다리 2단: 코드 생성 몫 후보) | Unverified |
| BT-PD-14a | performance-design.md PD-14 · ## 3 | /device 초기 JS(gzip 레벨 5) ≤ 150KB(150,000바이트) | 143,827바이트(140.5KiB, 6파일) ≤ 150,000. 교차 확인: HTML 스크립트 = 정적 집합, Nginx 전송 142,970바이트(−0.6%) | build/b-and-t/PT-01/static.json · build/b-and-t/PT-01/wire.txt | 3.6 | Met |
| BT-PD-14b | performance-design.md PD-14 · ## 3(제안) | /device 지연 청크(「켜기」 뒤, gzip) ≤ 80KB(80,000바이트) | 6,420바이트(6.3KiB, 2파일) ≤ 80,000 | build/b-and-t/PT-01/static.json | 3.6 | Met |
| BT-PD-14c | performance-design.md PD-14 · ## 3(제안) | /parent 첫 로드 JS(gzip) ≤ 200KB(200,000바이트) | 156,351바이트(152.7KiB, 8파일) ≤ 200,000. 교차 확인: HTML 스크립트 = 정적 집합, Nginx 전송 155,433바이트(−0.6%) | build/b-and-t/PT-01/static.json · build/b-and-t/PT-01/wire.txt | 3.6 | Met |
| BT-PD-20 | performance-design.md PD-20 · 2.8 · ## 4(11/2) | 설계 예산 relayIn p95 ≤ 0.15초 · relayOut p95 ≤ 0.25초(합 0.40초) | 실행 못 함(실공급자 · 11/2). 기록(판정 아님): relayIn 1 · 2ms, relayOut 8ms(가짜 공급자) | build/b-and-t/PT-02/latency-it02.jsonl | 뒤 단계 미예약(11/2 측정 창, 4.6 performance-validation SKIP) | Unverified |
| BT-NFR7.1 | nfr-requirements/scalability-requirements.md ## 1 · ## 3 · ## 4(11/2); scalability-design.md SCD-6 | 세션 2개 동시 시 NFR5.1 · t3−t2 증가 ≤ 20%(표본 100 미만: 동시 턴 90% 이상이 단독 p95의 1.2배 이내) | 실행 못 함(실공급자 · 시드 · 표본 판정 스크립트 없음) | — | 뒤 단계 미예약(11/2 측정 창, 4.6 performance-validation SKIP) | Unverified |
| BT-NFR7.2 | scalability-requirements.md ## 1 · ## 3 · ## 4; SCD-7 · SCD-8 | 30쌍 · 세션 2 · 40 RPS에서 호스트 CPU 시나리오 평균 ≤ 60% · 메모리 최댓값 ≤ 70% | 실행 못 함(부하 도구 · host-sample · EC2 없음) | build/b-and-t/S13/searches.txt(host-sample 0) | 뒤 단계 미예약(11/2 측정 창, 4.6 performance-validation SKIP) | Unverified |
| BT-NFR7.3 | scalability-requirements.md ## 1 · ## 4(Build and Test); SCD-5 · RD-3 | 한 세션의 공급자 지연 · 실패가 다른 세션의 시한 · 전이에 영향 없음(전역 락 없음) | SessionExecutorTest 통과. 두 세션 동시 통합 시험(SCD-5 · RD-3)이 저장소에 없어 실행 못 함 | build/b-and-t/unit/U-3-junit.txt | 뒤 단계 미예약(시험 · 도구 부재 — 사다리 2단: 코드 생성 몫 후보) | Unverified |
| BT-NFR7.4 | scalability-requirements.md ## 1 · ## 4; SCD-3 · SCD-4 | 수직 확장만: 단일 인스턴스 전제 목록이 설계 문서에 있고 해당 클래스에 표식, Compose 서비스당 1개 · `--scale` 거부 | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): 표식 파일 27(Core 정의 1 + 8, Realtime 정의 1 + 17), 표식 번호 SI-1 ~ 10 · 12 모두 있음, **SI-11은 두 앱에 그 상태(TTL 캐시)가 없어 표식 대상 0**(두 구조 시험이 전제 검사). `SingleInstanceMarkerTest` core 7 · realtime 7건 통과, Compose 불변식(IT-04a) PASS, `mj --scale` rc 64. **사람 결정**: 12곳 중 11곳 표식 + 1곳 대상 없음 — '대상 없음'을 인정하는지(J374) / 1차: 있음: SCD-3 표 12곳(scalability-design 2.2), Compose 불변식 9(IT-04a), mj --scale 거부 rc 64. 미달: 단일 인스턴스 표식이 있는 클래스 1개(SlidingWindowRateLimiter) — 12곳 대비 부족, 표식 구조 시험(SCD-4) 없음 | build/b-and-t-r2/S13/searches.txt · build/b-and-t-r2/S13/mj-scale.txt · build/b-and-t-r2/IT-04/IT-04a.log · build/b-and-t-r2/unit/regression-F1-F5.txt / 1차: build/b-and-t/S13/searches.txt · build/b-and-t/S13/mj-scale.txt · build/b-and-t/IT-04/IT-04a.log | 3.6 | Met (1차: Not Met) |
| BT-NFR7.5a | scalability-requirements.md ## 1 · ## 3 · ## 4(11/2); PD-10 | Outbox → Inbox 전달 지연 p95 ≤ 2초(정상 상태) | 실행 못 함(시뮬레이터 30 · 부하 도구 없음). 기록(판정 아님): PROCESSED durationMs 103 ~ 497 | build/b-and-t/PT-02/event-delivery-it02.jsonl · event-delivery-it03.jsonl | 뒤 단계 미예약(11/2 측정 창, 4.6 performance-validation SKIP) | Unverified |
| BT-NFR7.5b | scalability-requirements.md ## 4(Build and Test); RD-8 · PD-10 | 이벤트 N건이 모두 PROCESSED, 폴러 주기 · 배치 크기 설정 키 반영, 전달 지연 타이머가 남음 | ConfigKeyContractTest · CoreMessagingFlowIT · RealtimeMessagingIT 통과, 스택 이벤트 IT-02 7건 · IT-03 7건 모두 PROCESSED(FAILED · DEAD 0), Core mujoong_event_delivery_seconds 노출. '두 앱 + 키 변경 반영' 통합 시험이 없어 실행 못 함 | build/b-and-t/PT-02/event-delivery-it0*.jsonl · build/b-and-t/IT-06/metric-names.txt | 뒤 단계 미예약(시험 · 도구 부재 — 사다리 2단: 코드 생성 몫 후보) | Unverified |
| BT-NFR7.6 | scalability-requirements.md ## 1 · ## 3 · ## 4(Build and Test); PD-11 · SCD-5 · SCD-7 | 풀 앱당 최대 10 · 합계 ≤ 20, 풀 대기 100ms 초과 시 경고 카운터 증가 | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): 풀 10 · 10(PT-05), `mujoong_db_pool_wait_exceeded_total` 두 앱 노출, `DbPoolWaitMonitorTest` 두 앱 9건(Hikari 실제 호출 순서 · 이중 계수 없음 J364) · `MetricSnapshotIT`(실제 풀 대기 1회 → +1) 통과 / 1차: 풀 크기 두 앱 10 · 10(합 20), CorePropertiesValidationTest 통과. mujoong_db_pool_wait_exceeded_total 지표가 코드에도 스크랩에도 없음 | build/b-and-t-r2/S13/searches.txt · build/b-and-t-r2/IT-06/metric-names.txt · build/b-and-t-r2/unit/regression-F1-F5.txt / 1차: build/b-and-t/S13/searches.txt · build/b-and-t/IT-06/metric-names.txt · metric-missing-classification.txt | 3.6 | Met (1차: Not Met) |
| BT-NFR7.7 | scalability-requirements.md ## 1 · ## 4(Build and Test); SCD-9 · SCD-10 | 정리 작업(7일 지난 EXPIRED 삭제 · FAILED/DEAD payload 비움) 통과, 시연 뒤 표별 행 수 < 10만(점검 기준 5만) | MessagingJobsTest 통과. 시연 뒤 표별 행 수는 운영 시점이라 실행 못 함 | build/b-and-t/security/st03-backend-security-tests.txt | 3.6(정리 시험 통과) · 뒤 단계 미예약(시연 뒤 행 수) | Unverified |
| BT-NFR7.8 | scalability-requirements.md ## 1 · ## 4(Build and Test); OD-12 | 용량 지표 7종(JVM 힙 · GC · 스레드 · 열린 WSS · 풀 활성/대기 · Outbox 미전달 · 가장 오래된 PENDING 나이) 존재, 외부 403 | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): 7종 모두 노출: jvm_memory · gc · threads, `mujoong_device_connections_open`, hikaricp active · pending, `mujoong_outbox_pending` · `mujoong_outbox_oldest_pending_seconds`(두 앱, direction 라벨), 외부 403 / 1차: 있음: jvm_memory_used · jvm_gc_pause · jvm_threads_live · hikaricp active · pending, 열린 WSS(mujoong_ws_connections_open, 설계 이름과 다름), 외부 403. 없음: Outbox 미전달(mujoong_outbox_pending) · 가장 오래된 PENDING 나이(mujoong_outbox_oldest_pending_seconds) | build/b-and-t-r2/IT-06/metric-names.txt · actuator-external.txt / 1차: build/b-and-t/IT-06/metric-names.txt · metric-missing-classification.txt · actuator-external.txt | 3.6 | Met (1차: Not Met) |
| BT-NFR10.1 | nfr-requirements/tech-stack-decisions.md ## 1 · ## 4(Build and Test); PD-16 · SD-2 | 데스크톱 Chrome 최신 2개 판에서 D0 → 등록 → T19 켜기 → 한 턴 → 끝내기(실제 판 번호 기록) | 실행 못 함(데스크톱 Chrome 없음) | — | 뒤 단계 미예약(실기 브라우저 — 설계상 3.6 지정이나 이 환경 불가) | Unverified |
| BT-NFR10.2 | tech-stack-decisions.md ## 1 · ## 4(Build and Test) | Android Chrome 최신 2개 판에서 04 · 05a · 08 · 12 · 17 · P1 | 실행 못 함(실기 없음) | — | 없음(10/29 통합시험, 실행 계획 밖) | Unverified |
| BT-NFR10.3 | tech-stack-decisions.md ## 1 · ## 4(Build and Test) | iOS Safari 16.4 이상에서 U1 /parent 화면 렌더 | 실행 못 함(실기 · 시뮬레이터 없음) | — | 뒤 단계 미예약(실기 브라우저 — 설계상 3.6 지정이나 이 환경 불가) | Unverified |
| BT-NFR10.4 | tech-stack-decisions.md ## 1 · ## 4; PD-16 | 「켜기」 전 getUserMedia 0건, 거부 시 BR9.5 화면 · WSS 0건, 서비스 워커 · PWA 매니페스트 없음 | mic-permission.test · no-service-worker.test 통과, src · 번들에서 serviceWorker.register · manifest 0건(S13) | build/b-and-t/security/st04-frontend.txt · build/b-and-t/S13/searches.txt | 3.6 | Met |
| BT-NFR11.1 | tech-stack-decisions.md ## 1 · ## 4; PD-15 | `/parent` → `/device` import 0(반대도), 위반 샘플이 lint 실패 | eslint-boundary.test 통과, B-02 eslint rc 0, IT-04d eslint 관문 양성 통과 · 음성(기기 → 보호자 import) 거부 | build/b-and-t/logs/B-02.log · build/b-and-t/logs/U-13.log · build/b-and-t/IT-04/IT-04d.log | 3.6 | Met |
| BT-CD-INV | infrastructure-design/cicd-pipeline.md 2.2.3 | Compose 불변식 1 ~ 12(게시 포트 80 · 443뿐, stop_grace 40초 > 30초, 메모리 한도 합 5.075GiB ≤ MemTotal 70%, restart · 헬스체크, 비밀 분배, 로그 한도, 망 둘, 고정 태그, 서비스당 1, neo4j 프로필, cap_drop · read_only, core-api depends_on) | IT-04a 8검사 PASS(compose-config · compose-invariants 포함) rc 0, IT-04b 32건 어긋남 0 | build/b-and-t/IT-04/IT-04a.log · build/b-and-t/IT-04/IT-04b.log | 3.6 | Met |
| BT-CD-IMG | infrastructure-design/cicd-pipeline.md 2.3.2 · CD-2 | 이미지 점검 ① ② ④ ⑤ ⑥ ⑦ ⑧ PASS(③ Trivy는 보안 담당) | IT-05 ① ② ④ ⑤ ⑥ ⑦ ⑧ PASS(③ Trivy는 실행 파일이 없어 SKIP — BT-NFR9.13에서 따로 판정) | build/b-and-t/IT-05/image-checks.log · build/b-and-t/IT-05/build-images.log | 3.6 | Met |
| BT-CD-01 | infrastructure-design/cicd-pipeline.md ## 4 CD-1 | 관문이 실제로 막음: 가짜 비밀 · 게시 포트 추가 · 틀린 Nginx 설정 · 파괴적 마이그레이션 · 경계 import(양성 대조 통과 · 음성 대조 실패), 취약 의존성 · 커버리지 하락 · `ci-gate` 경로 필터 | IT-04d 관문 5종(가짜 비밀 · 게시 포트 · Nginx · 파괴적 마이그레이션 · 경계 import) 양성 통과 · 음성 거부. 취약 의존성 · 커버리지 하락 · ci-gate 경로 필터 관문은 GitHub Actions 실행이 필요해 실행 못 함 | build/b-and-t/IT-04/IT-04d.log | 3.6(관문 5종 통과) · 뒤 단계 미예약(Actions 관문 3종, 3.7 ci-pipeline SKIP — 4.1은 CD 구성 단계라 이 검사를 명시하지 않음) | Unverified |
| BT-CD-06 | infrastructure-design/cicd-pipeline.md ## 3 · ## 4 CD-6 | 롤백 리허설: 직전 SHA 복귀 · D2 · D4 생략 · DB 유지 · 중단 ≤ 60초 | 실행 못 함(롤백 리허설 하네스 · 실배포 경로 없음) | 기대 증거: operation/deployment-execution/deployment-log.md | 4.3 deployment-execution | Unverified |
| BT-RD-13 | nfr-design/reliability-design.md RD-13 · 2.8 · ## 4(Build and Test) — Step 9 보정 | SIGTERM: 새 WSS 1013 · 열린 WSS 1001 · 공급자 연결 닫힘 · 선점 행 해제 · 진행 중 처리기 완료 · 30초 유예 안 종료, 재기동 뒤 비종료 세션 SERVER_RESTART, 유예 중 새 REST 요청은 503 SERVICE_NOT_READY + Retry-After(접속 거절 아님) · /internal/events 503 | 재시험(b094c0d, 2026-10-11T17:11Z ~ 22:32Z): IT-06j-r1(20ms 표본): SIGTERM 뒤 상태 순서 **200 → 503(Retry-After: 5, 2개, 0.040 · 0.066초) → 502 → 재기동 뒤 200**, stopSec 0.79 · exit 143(137 아님). 유예 안 표본 30개 중 503 2 · 502 27 · 200 1 — 진행 중 요청이 없어 T6이 약 70ms에 끝나고 그 뒤(T7, 프로세스 종료까지 약 0.7초)는 접속 거절 → 입구 502. `/internal/events` 503 · 새 WSS 1013 · 열린 WSS 1001 · 공급자 닫힘 · 유예 소진은 `GracefulDrainIT`(core 5 · realtime 6건) 통과. **사람 결정**: 503 창은 수십 ms(리뷰 지적 1)이고 설계 문구 '커넥터는 유예(30초) 끝까지 열어 둠' 기준이면 Not Met — 최소 유지 시간 키(배포 중단 증가) 여부(J373) / 1차: IT-06j와 재실행(20ms 표본): exit 143 · 종료 0.6초(30초 안), 재기동 healthy. 미달: SIGTERM 49ms 뒤부터 유예 0.63초 동안 표본 19개가 모두 입구 502(접속 거절), 503 0건 · Retry-After 0건. WSS 1013 · 1001 · 공급자 닫힘 · 선점 행은 드라이버가 없어 관측 못 함 | build/b-and-t-r2/IT-06/sigterm-r1/sigterm.txt · samples.txt · build/b-and-t-r2/IT-06/sigterm.txt · build/b-and-t-r2/unit/regression-F1-F5.txt / 1차: build/b-and-t/IT-06/sigterm.txt · build/b-and-t/IT-06/sigterm-r1/sigterm.txt · build/b-and-t/IT-06/health-during-sigterm.txt | 3.6 | Met (1차: Not Met) |
| BT-LC-2 | nfr-design/logical-components.md LC-2 · ## 4(Build and Test) — Step 9 보정 | web 정지에도 API · WSS · 진행 중 세션 정상, web → core-api · realtime 연결 0건, 이미지 하나로 환경 변수만 바꿔 시간 설정 반영 | IT-06h web 정지 중 /api/health 200 · WSS 101 → 4401, web → 앱 연결 0(/proc/net/tcp). 진행 중 세션 유지 · 환경 변수 시간 설정 반영은 드라이버가 없어 실행 못 함 | build/b-and-t/IT-06/web-stop.txt · build/b-and-t/IT-06/lc2-web-remote.txt | 뒤 단계 미예약(시험 · 도구 부재 — 사다리 2단: 코드 생성 몫 후보) | Unverified |
| BT-LC-5 | nfr-design/logical-components.md LC-5 · ## 4(Build and Test) — Step 9 보정 | nginx · web에서 postgres 접속 불가, 앱 역할 교차 스키마 쓰기 DB 거부, 소유자 역할이 앱 컨테이너에 없음, postgres 정지 시 두 앱이 직접 503 | IT-06i web → postgres TCP CLOSED(timeout) · nginx는 postgres 이름을 못 풂 · 망 분리(nginx · web은 edge만, postgres는 backend만) · 소유자 · 슈퍼유저 비밀 이름 앱 · web · nginx에 0, P1 42501 6건, IT-06f 재실행 두 앱 /internal/events 503 | build/b-and-t/IT-06/networks.txt · web-to-postgres.txt · nginx-to-postgres.txt · owner-secret-names.txt · internal-db-stop-r1.txt | 3.6 | Met |
| BT-LC-6 | nfr-design/logical-components.md LC-6 · ## 4(Build and Test) — Step 9 보정 | migrate 실패 시 앱이 뜨지 않고 도는 서비스는 계속, 앱 두 개가 같은 마이그레이션을 경쟁 적용하지 않음 | 실행 못 함(깨진 마이그레이션 주입은 소스 수정이 필요 — 금지). 정상 순서(B2 · B3 · IT-05 ④)와 SchemaVersionCheckTest · CoreStartupSequenceIT는 통과 | 기대 증거: 4.3 배포 리허설(CD-5) | 4.3 deployment-execution | Unverified |
| BT-LC-9 | nfr-design/logical-components.md LC-9 · ## 4(11/2 측정) — Step 9 보정 | 호스트 점검 5종 항목 로그 · 프로브 일일 집계 스크립트 동작, Realtime만 정지해도 WSS 프로브가 실패한 분으로 잡힘 | 실행 못 함(실호스트 · probe-daily 없음). 참고: IT-06g Realtime 강제 종료 중 probe-three wss=502 FAIL 7회 관측 | build/b-and-t/IT-06/probe-crash-realtime-0.log · build/b-and-t/S13/searches.txt | 뒤 단계 미예약(11/2, 4.4 · 4.6 SKIP) | Unverified |
| BT-LC-10 | nfr-design/logical-components.md LC-10 · ## 4(Build and Test) — Step 9 보정 | neo4j 프로필이 꺼진 채 Compose 구성 유효, U1 시험이 neo4j 없이 통과(켜면 메모리 재측정) | IT-04a compose-config · compose-invariants(neo4j 프로필 불변식) PASS, U1 시험 1,196 · 499 · 105건이 neo4j 없이 통과. 켜는 경우는 해당 없음(꺼져 있음) | build/b-and-t/IT-04/IT-04a.log · build/b-and-t/unit/U-3-junit.txt | 3.6 | Met |
| BT-OD-16 | nfr-design/observability-design.md OD-16 · ## 4(11/2 측정) — Step 9 보정 | 시연 규모 부하로 하루 로그량을 추정해 2.15절 입력과 비교, 접근 로그에서 폴링 2xx 제외 | 부하 실행 못 함(부하 도구 없음). 참고: ST-12 polling_2xx=0(access_lines 104) | build/b-and-t/security/st12-log-hygiene.txt | 뒤 단계 미예약(11/2 측정 창, 4.6 performance-validation SKIP) | Unverified |
| BT-OD-17 | nfr-design/observability-design.md OD-17 · ## 4(11/2 측정) — Step 9 보정 | sli-report가 모든 SLI 행을 채우고 표본 규칙 · 경보선 비교를 적용 | 실행 못 함: sli-report가 저장소에 없음(git ls-files 0) | build/b-and-t/S13/searches.txt | 뒤 단계 미예약(11/2, 4.4 · 4.6 SKIP) | Unverified |
| BT-PD-1 | nfr-design/performance-design.md PD-1 · ## 4(Build and Test 행) — Step 9 보정 | 공급자 연결에 5초 지연을 주입해도 시작 응답 202가 지연되지 않음, 실행기 안 synchronized 블로킹 없음 · 핀 고정 진단 무탈 | 실행 못 함(지연 주입 시험 · 핀 고정 진단 없음). 관련 3.5 시험 ProviderClientIT(연결 걸린 동안 취소 2초 안) 통과 | build/b-and-t/IT-01/boundary-classes.txt | 뒤 단계 미예약(시험 · 도구 부재 — 사다리 2단: 코드 생성 몫 후보) | Unverified |
| BT-PD-5 | nfr-design/performance-design.md PD-5 · ## 4(Build and Test 행) — Step 9 보정 | 기록기 순서 · FINAL 즉시 플러시 · 조각 덮어쓰기 · 커밋 실패 재시도 · 종료 플러시, 기록기 DB 쓰기 지연 주입에도 relayIn · relayOut 불변 | AsyncRecorderTest(순서 · FINAL 즉시 플러시 · 지연 상태 · 마지막 플러시) 통과. 커밋 실패 재시도 · 조각 덮어쓰기 · DB 쓰기 지연 주입 독립성 시험이 없어 실행 못 함 | build/b-and-t/unit/U-3-junit.txt | 뒤 단계 미예약(시험 · 도구 부재 — 사다리 2단: 코드 생성 몫 후보) | Unverified |
| BT-PD-6 | nfr-design/performance-design.md PD-6 · ## 4(11/2 행) — Step 9 보정 | 실공급자 세션의 한 턴 로그만으로 relayIn · relayOut · t0 ~ t3 · 서버 몫을 계산하는 스크립트가 돈다 | 실행 못 함(실공급자 · turn-metrics 없음) | build/b-and-t/S13/searches.txt | 뒤 단계 미예약(11/2 측정 창, 4.6 performance-validation SKIP) | Unverified |
| BT-PD-18 | nfr-design/performance-design.md PD-18 · ## 4(3.5 · 11/2 행) — Step 9 보정 | 시드 도구 시험(멱등 · 정리 · 토큰 600 · realtime 스키마 미기록), 설정 시험(시드 빈 없음 · demo · prod 20회 · load-test 조합 기동 거부), L1 ~ L7 실행 · 판정과 LT- 행 0 | 설정 시험 일부 통과(600 상향은 load-test 프로필만 · load-test 조합 거부 규칙 시험). 부하 시드 도구 · L1 ~ L7은 저장소에 없어 실행 못 함 | build/b-and-t/security/st03-backend-security-tests.txt · build/b-and-t/S13/searches.txt | 뒤 단계 미예약(11/2 측정 창, 4.6 performance-validation SKIP) | Unverified |
| BT-SD-3b | nfr-design/security-design.md SD-3 · ## 4(Build and Test 행), security-test-instructions.md 4절 · 13절 — Step 9 보정 | 실제 Nginx 경유 WSS가 60초 넘게 무음이어도 Nginx가 먼저 끊지 않고 앱의 40초 판정이 먼저 | 실행 못 함(인증된 무음 연결 유지 도구 없음). 정적 근거: infra-validate(/ws/device 75초 · proxy_buffering off) PASS, DeviceGatewayIT 40초 판정 통과 | build/b-and-t/IT-04/IT-04a.log | 뒤 단계 미예약(시험 · 도구 부재 — 사다리 2단: 코드 생성 몫 후보) | Unverified |
| BT-SD-9b | nfr-design/security-design.md SD-9 · ## 4(3.5 행, J113 나머지로 넘김), security-test-instructions.md 4절 — Step 9 보정 | ArchUnit: 보호자 소유 자원 조회 메서드가 보호자 id를 요구 | 자동 규칙 없음 → 실행 못 함. 임시 근거 ST-23(3): 보호자 id 없는 findById 호출자 2곳 모두 보호자 요청 경로 아님 | build/b-and-t/security/st23-static-review.txt | 뒤 단계 미예약(시험 · 도구 부재 — 사다리 2단: 코드 생성 몫 후보) | Unverified |
| BT-SD-19a | nfr-design/security-design.md SD-19 · ## 4(3.5 행, 3.6 넘김), security-test-instructions.md 4절 — Step 9 보정 | 문구 금지 어휘 단위 시험(문구표 · TEMP_COPY.errors · 승인 문구) | 단위 시험 없음 → 실행 못 함. 임시 근거: ST-24 grep 화면 글 걸림 0, jsx-no-literals 위반 샘플 시험 통과 | build/b-and-t/security/st24-vocab.txt · build/b-and-t/security/st04-frontend.txt | 뒤 단계 미예약(시험 · 도구 부재 — 사다리 2단: 코드 생성 몫 후보) | Unverified |

## 11. 실패 사다리 기록

### 11.1 1단 — 이 단계 몫의 고침(각 2회 이내, 앱 소스 무수정)

| 번호 | 대상 | 원인 | 고친 것 | 다시 돈 결과 |
|---|---|---|---|---|
| ① | ST-20 Trivy 감싸개(scratchpad `dso-bin/trivy`, 보안 지침 9절) | 호스트 `/tmp`를 읽기 전용으로 붙여 Trivy가 docker 이미지를 내보낼 임시 디렉터리를 못 만듦 → FATAL → 종료 코드 1을 image-checks가 '취약점'으로 셈(거짓 실패) | `/tmp`는 읽기 전용 그대로 두고 `TMPDIR`만 scratchpad의 쓰기 가능한 디렉터리로 줌(1회). 심각도 · `--ignore-unfixed` · 예외 목록은 image-checks.sh가 주는 그대로 | ③이 실제로 실행됨 → 진짜 취약점으로 FAIL(2단으로) |
| ② | IT-06f Realtime `/internal/events` | 지침이 Core가 503이 된 순간 Realtime을 한 번만 불러, 1초 늦게 내려가는 Realtime을 놓침(시험 절차 타이밍) | 같은 기대 · 같은 40초 기한으로 두 앱을 1초마다 다시 확인(1회) | Core 18초 · Realtime 19초에 503 → 통과 |
| ③ | IT-06j SIGTERM 표본 | 0.2초 간격 표본이라 0.6초 유예 중 503 창을 놓쳤을 수 있음 | 20ms 간격 병렬 표본으로 다시 잼(1회) | 유예 중 503 0건 확인 → 실제 미달(2단으로) |
| ④ | BT-TC-10 측정 | Vitest 기본 리포터가 비대화형에서 파일별 시험 수를 찍지 않아 지침의 grep이 0줄 | 같은 시험을 `--reporter=json`으로 한 번 더 돌려 파일별 수를 셈(측정 보조) | 프런트 34파일 · tools 8파일 모두 5건 이상 |
| ⑤ | IT-06d 증거 위생 | 지침 명령이 12 조회 본문(전사 · AI 말이 들어갈 수 있음)을 증거 폴더에 저장 | 본문은 0700 `build/verify/c/`에만 두고(IT-06k가 지움) 증거에는 상태 코드 · 키 · items 수만 남김 | 판정 영향 없음 |

판정 목표 · 기한 · 관문 설정 · 예외 목록 · `.gitleaks.toml`은 하나도 바꾸지 않았다.

### 11.2 2단 — 원인 분류와 해결 후보 · 영향 추정

노력은 에이전트 기준 추정이고 '재검증'은 전체 검증 명령(약 5분) · 이미지 재빌드 · 해당 스캔 재실행을 포함한다. 금전 비용은 따로 적지 않은 한 0(로컬 도구 · 무료 이미지)이다. 어느 후보도 목표 값을 낮추지 않는다.

| 묶음 | 걸린 목표 | 원인 분류 | 해결 후보(바꿀 수 있는 차원) | 영향 추정(노력 · 비용 · 위험) |
|---|---|---|---|---|
| A 공급망 | BT-NFR9.13 · SD-16 (ST-19 · ST-20) | 3.5 선택: Spring Boot 4.1.1 BOM이 끌어온 tomcat-embed-core 11.0.24 · jackson 3.1.5, springdoc 등이 끌어온 jackson 2.22.1, 베이스 이미지 판(eclipse-temurin 21.0.12_8-jre-noble · node 22.22.3-alpine3.23 · flyway 12.4.0 · gosu · supercronic) | ① `libs.versions.toml`/플랫폼 제약으로 tomcat 11.0.25 이상 · tools.jackson 3.1.7 · com.fasterxml 2.22.3 강제(Boot 4.1.2가 나오면 BOM 올림으로 대체) ② 앱 · migrate 베이스 이미지를 openssl 3.0.13-0ubuntu3.16이 든 최신 패치 태그로, web은 런타임에 쓰지 않는 번들 npm 제거 또는 alpine 패치 태그, migrate는 쓰지 않는 Flyway 드라이버(couchbase · databricks · aws · mssql) 제거 또는 최신 flyway 판, backup은 최신 gosu · supercronic(또는 gosu 제거) ③ 고칠 판이 없는 항목만 사유 · 만료일 있는 예외(사람 승인) | ① 1 ~ 2시간 + 재검증, 위험 낮음 ~ 중간(패치 판, 회귀 1,196건으로 확인) ② 2 ~ 4시간 + 재검증, 위험 중간(베이스 교체는 이미지 점검 ① ~ ⑧ 재확인, Docker Hub 429 시 고정 태그 미보유 위험) ③ 0.5시간, 위험은 사람 판단 |
| B 입구 504 | BT-NFR4.13(+ IT-06h) | 3.4 · 3.5 설계/설정: 고정 IP 업스트림(N-2) + `proxy_connect_timeout 3s` — 멈춘 컨테이너의 IP는 응답이 없어 연결 시간 초과(504) | ① 업스트림을 Docker DNS 재해석(`resolver 127.0.0.11` + 변수)으로 바꿔 멈춘 서비스 이름이 풀리지 않을 때 즉시 502 ② 고정 IP는 두고 `backup` 서버(Nginx 내부 502 응답)로 실패 응답을 502로 고정 ③ 설계 · 모니터링 문서가 504를 '업스트림 접속 불가'로 함께 보도록 고침(목표 변경 — 사람 결정) | ① 1 ~ 2시간 + 재검증, 위험 중간(keepalive 업스트림 · N-2 근거와 충돌, 배포 60초 예산 재확인) ② 1시간, 위험 낮음 ~ 중간(504 지연 3초는 남음) ③ 0.5시간, 위험 낮음 — 단 목표를 바꾸므로 사람만 정할 수 있음 |
| C SIGTERM 유예 503 | BT-RD-13 | 3.5 코드/설정: SIGTERM 즉시 웹 커넥터가 새 연결을 받지 않아 DRAINING 필터(F1b)의 503이 나갈 틈이 없음 | ① 종료 첫 단계에서 readiness를 내리고 커넥터는 유지한 채 짧은 유예(설정 키, 예: 수 초) 동안 503 + Retry-After를 답한 뒤 웹 서버를 멈추는 SmartLifecycle 순서 추가 + 통합 시험 ② WSS 1013 · 1001 · 선점 행 해제를 볼 SIGTERM 드라이버(tools) 추가 | ① 2 ~ 4시간 + 재검증, 위험 중간(종료 순서 · 30초 유예 · 배포 중단 60초 예산과 상호작용) ② 2 ~ 3시간, 위험 낮음 |
| D 지표 · 스냅샷 | BT-NFR4.15 · 7.6 · 7.8 · BT-OD-06 | 3.5 미구현(알려진 틈 2, 계획은 이름 표 · 스냅샷 시험만 3.6으로 넘김) | 연결 상태 분포 · Outbox 미전달 · 가장 오래된 PENDING 나이 · 실패 · Inbox DEAD · 풀 대기 초과(Hikari 지표 추적기) · 큐 깊이 지표와 METRIC_SNAPSHOT 60초 발행 구현 + 이름 표 시험, 열린 연결 게이지 이름을 설계에 맞춤(또는 설계 문서 고침 — 사람 결정) | 4 ~ 8시간 + 재검증, 위험 낮음(관측 경로만), 비용 0 |
| E 측정 도구 | BT-NFR5.4 · BT-OD-17 · BT-LC-9 · BT-PD-6 · BT-PD-18 · BT-OD-16 | 3.5 · 11/2 준비물 미구현 | `turn-metrics`(한 턴 계산) 먼저, 그다음 `sli-report` · `probe-daily` · 부하 시드 도구 · 부하 스크립트 · `host-sample` · `compose.loadtest.yml` | `turn-metrics` 1 ~ 2시간(위험 낮음), 11/2 준비물 전체 1 ~ 3일 |
| F 시험 부재 | BT-TC-10 · BT-NFR7.3 · 7.5b · BT-PD-08 · 09 · BT-NFR5.8 · BT-NFR4.7 · 4.12 · BT-PD-1 · 5 · BT-LC-2 · BT-SD-3b · 9b · 19a | 3.5 생성 시험의 분량 · 범위 부족, 3.6 이후로 넘긴 시험 | 분량 미달 26클래스 보강(클래스당 오류 · 경계 사례), 두 세션 동시 통합 · 두 앱 키 변경 · EXPLAIN(10만 행) · 질의 수 단언 · PLAYBACK_MARK_STOPPED 경로 · 공급자 지연 주입 · 기록기 지연 주입 · ArchUnit 소유권 규칙 · 문구 어휘 단위 시험 · 진행 중 세션 장애 주입 드라이버 · WSS 무음 시나리오 | 분량 보강 3 ~ 5시간, 나머지 항목당 0.5 ~ 3시간(합계 2 ~ 3일), 위험 낮음(시험만), 검증 명령 시간 증가는 전체 실행에만(엔진 110초 예산 밖으로 두기) |
| G 시간 상수 | BT-NFR6.1 | 3.5 코드 | 상수 4종을 `mujoong.*` 설정 키로 옮기거나 허용 목록(J124)에 넣기(사람 승인), 시간 숫자 상수 ArchUnit 규칙 추가 | 0.5 ~ 1시간 + ArchUnit 0.5시간, 위험 낮음 |
| H 단일 인스턴스 표식 | BT-NFR7.4 | 3.5 코드(SCD-4 넘김) | SCD-3 12곳의 클래스에 표식 + 표식 수 = 12 구조 시험 | 1 ~ 2시간, 위험 낮음 |
| I 11/2 · 운영 창 | 성능 · 확장 · SLO · BT-NFR4.8과 보정 11/2 행(BT-LC-9 · OD-16 · OD-17 · PD-6 · PD-18), 23개 | 실행 계획: 4.6 performance-validation · 4.4 observability-setup SKIP | ① 실행 계획에 4.6(과 4.4)을 넣음(사람 결정) ② 11/2 측정을 실행 계획 밖 일정으로 두고 그때 판정하기로 사람이 정함(이 단계는 계속 Unverified) | ① E의 준비물 1 ~ 3일 + 측정 창 운영 반나절, 실공급자 사용료(소액) · 측정 동안 EC2 시간(계획된 비용 안), 일정 위험 ② 노력 0, 위험: 시연 전 성능 미확인 |
| J 실기 · 브라우저 | BT-NFR10.1 ~ 10.3 · 5.6a ~ c · 9.12(콘솔) · 4.16(실기) · SD-2-b | 이 환경에 브라우저 · 실기 없음, 소유 단계 없음 | 4.3 배포 뒤 실기 점검(Chrome 2판 · Android Chrome · iOS Safari, 콘솔 CSP, 비행기 모드 1회)을 넣거나 10/29 통합시험 결과를 증거로 받기(사람 결정) | 2 ~ 4시간 수동, 비용 0(기기 보유 전제), 위험 낮음 |
| K 배포 단계 몫 | BT-NFR9.1b · 4.10 · 4.11a · 4.11b · BT-CD-06 · BT-LC-6 | 실행 계획의 4.2 · 4.3이 맡음 | 해당 단계의 기대 증거(매트릭스 Evidence 열) | 그 단계 작업에 포함 |
| L U2 몫 | BT-NFR12.1b · BT-NFR9.2(로그아웃) | U2 미구현(사람 지시) | U2 Construction 뒤 Build and Test | U2 일정에 포함 |
| M Actions 관문 | BT-CD-01(취약 의존성 · 커버리지 하락 · `ci-gate` 경로 필터) | 3.7 ci-pipeline SKIP, 실제 Actions 실행 없음 | 첫 Actions 실행을 4.1 작업 범위로 명시하거나 3.7을 실행 계획에 넣음(사람 결정) | 1 ~ 2시간(실행 · 결과 기록), 비용 0(공개 러너), 위험 낮음 |

### 11.3 3 · 4단

단독 실행(`--single`)이라 하지 않았다. 코드 생성으로 되돌아가는 점프와 Loop-Back 기록은 없다. 위 후보 중 무엇을 할지(또는 실패를 받아들일지)는 사람이 정한다.

## 12. 준비도 평가

| 준비도 | 판정 | 근거 |
|---|---|---|
| build-ready | **충족** | 모든 빌드 명령 rc 0, BV 5종 기대대로, 검증 명령 A1 · A2 · A3 통과 |
| test-ready | **미충족** | 실행 · 판정은 끝났다(`Pending` 0, 단위 · 통합 실패 0). 그러나 보안 스캔 명령 2개가 실패했다(ST-19 · ST-20 ③) |
| deployment-ready | **미충족** | Met 58 · Not Met 11 · Unverified 51(120개). 공급망 CRITICAL(tomcat)이 남아 있고, 11/2 측정 · 운영 창 · 실기 목표의 소유 단계가 없다 |

## 13. 회귀 기준선

`performance-test-instructions.md` 9절의 기록 규칙대로 남긴다. 다음 실행에서 같은 조건의 값과 나란히 적어 10% 이상 늘어난 항목을 위험으로 올린다.

| 항목 | 이번 값 |
|---|---|
| /device 초기 JS · 지연 청크 · /parent 첫 로드(gzip 5) | 143,827 · 6,420 · 156,351바이트 |
| 정상 턴 relayIn · relayOut · providerResponse · quietWait(가짜 공급자) | 1 ~ 2 · 8 · 104 · 801ms |
| 정상 턴 t3 − t0(가짜 공급자) | 1.038 · 1.040초 |
| 이벤트 전달 PROCESSED(최대) | 460 · 497ms |
| 검증 명령 전체 · 엔진 | 289초 · 59초 |
| 강제 종료 복구(realtime · core-api · realtime) | 7.3 · 9.1 · 6.8초 |

## 14. 정리와 지킨 규칙

- **정리.** IT-06k와 ST-14 뒤 `mujoong-verify` 컨테이너 · 망 · 볼륨 0, `build/verify/{env,c,certs,nginx,postgres}` 0개(본 토큰 · 쿠키가 든 비밀 목록 포함), ST-15 버림 망 · 컨테이너 0(버림 PostgreSQL이 남긴 익명 데이터 볼륨 1개는 끝에 지움), 이미지 점검 스택(`mujoong-imgchk`)은 스크립트가 스스로 정리. 끝에 남은 컨테이너 0, 볼륨 수는 시작과 같음(11개, 모두 이 단계 전부터 있던 것).
- **작업 트리.** 끝의 `git status --porcelain=v1`은 시작과 같다(감사 조각 1개 + `build-and-test/`). 소스 지문 `b42ff98a…` 그대로. 검증 명령 세 번 모두 T 단계 PASS.
- **고치지 않은 것.** 애플리케이션 소스(backend · frontend · tools · infra · scripts · contracts), 3.5 산출물(`code-generation/` 아래), `aidlc-state.md`, `.claude/`, `knowledge/documents/`, 보안 지침 파일. 측정 목표 · 하한 · 기한 · 관문 · 예외 목록을 낮추거나 끄지 않았다.
- **새로 만든 것.** 이 기록 디렉터리의 `test-results.md` · `cross-unit-traceability.md`와 `build-and-test-summary.md` 판정 갱신, 증거 `build/b-and-t/`(무시 대상), 도움 스크립트 · 고친 Trivy 감싸개(scratchpad). 비밀 값은 어떤 파일에도 쓰지 않았고 commit · push를 하지 않았다.
- **지침과 다르게 한 것.** 11.1의 다섯 가지와, 오케스트레이터 요청에 따라 `npm audit --omit=dev`를 보충으로 돌린 것(판정은 보안 지침의 관문 결과), IT-06e에서 Realtime 지표를 정지 전에도 한 번 더 긁어 '증가'를 비교한 것.

## 15. 수정 라운드와 재시험 (2026-10-11)

> 1절 ~ 14절은 1차 실행(HEAD `39b33a6`, 2026-10-11T03:01Z ~ 04:01Z)의 기록 그대로다. 이 절은 수정 라운드 뒤 재시험(HEAD `b094c0d`, 소스 지문 `sha256:41d95b389c2aa3669e1d5219a81c4245deeead77049c6ba4ba8d7157235bd990`, 2026-10-11T17:11:50Z ~ 22:32:19Z, 증거 `build/b-and-t-r2/`(`.gitignore` 대상, 이 PC에만 있음))과 그 뒤 두 커밋(`8a9225a` HEARTBEAT 복귀 경주, `b9a53a5` run-c TIMEOUT 경주)의 모듈 전체 명령 결과를 적는다. 재시험 담당 보고(scratchpad `cg/report-fix-retest.md`, J370 ~ J379)의 표를 그대로 옮기고 HEAD 수치만 더했다(J385). 10절의 매트릭스는 재시험 값으로 갱신했다(1차 값은 같은 칸에 '1차: …'로 남김, J384). 비밀 · 쿠키 · 토큰 · 전사 원문은 옮기지 않았다.

### 15.1 사람 결정

- "3.6에서 실패가 있어 고친 뒤 다시 시험할까" → **Approve**. "커버리지 하한 · 단언을 낮추자는 제안" → **하한 유지**. "도메인 · 키 · 비밀값" → **임시값 유지**. 따라서 측정 목표 · 하한 · 관문 · 단언 · 보안 예외 목록(1건)은 하나도 낮추거나 넓히지 않았고, 실패는 원인을 고쳤다.
- Unverified 51개 중 43개는 실행 계획에 맡을 단계가 없다(11/2 측정 창 · 운영 7일 창 · 실기 브라우저 · GitHub Actions 실행 · 측정 도구 일부). 이것은 계획 결정(D)이라 이 라운드에서 다루지 않았다.
- 단계 판정: 1절의 실패 조건 세 가지 중 ① 실패한 명령(ST-19 · ST-20 ③)과 ② Not Met 11개는 재시험으로 해소됐고, ③ Unverified 51개는 그대로다. 단계 정의상 Unverified가 실패 판정(failure predicate)에 들어가므로 최종 판정은 사람 몫이다(J389). 1절의 1차 결론은 지우지 않았다.

### 15.2 고친 것 요약

커밋 `cf0a58e` · `86db0b4` · `8f44764` · `6dbd725` · `aabce31` · `b094c0d`(재시험 대상) · `8a9225a` · `b9a53a5`. 항목별 상세 · 결정(J285 ~ J393)은 `code-summary.md` ## 3.6 수정 라운드.

| 묶음 | 고친 것 |
|---|---|
| 코드(백엔드) | 리뷰 결함 F1 ~ F5(출력 게이트 전사-음성 대응 fail-closed · 편차 (z), closeProvider 자기 교착, 재시도 뒤 409 취소, 최상위 예외 때 기기 명령, syncFromDb 비종료 상태 시한). 지표 12종 · METRIC_SNAPSHOT 60초 · `mujoong_device_connections_open` 이름 · liveness NaN. SIGTERM T1 ~ T6 공유 마감 · 커넥터를 T6까지 열어 503 + Retry-After. 시간 숫자 상수 4종 제거 + `TimeLiteralScanTest`. 단일 인스턴스 표식 25곳 + `SingleInstanceMarkerTest`. 5건 미만 26클래스 보강. 2회차: 풀 대기 이중 계수 · appliesTo · DbSessionLimitsIT · 표본 스레드 분리 · T5 마감. HEARTBEAT 복귀 경주 2개(J390 ~ J392). 의존성 Tomcat 11.0.26 · Jackson 3.1.7 · 2.22.3 |
| 코드(프런트 · tools) | FE-1 `reconnecting` 상태 + `connectedOnce`, FE-2 `networkMode: "always"`, FE-3 `requestSubmit` + `inFlightRef`. tools `run-c.mjs` 깎인 대기의 TimeoutError를 TIMEOUT으로(J393) |
| 인프라 | Dockerfile 5종(기반 다이제스트 · openssl .deb · npm 삭제 · 최소 Flyway CLI · supercronic 소스 빌드 · gosu 삭제), Nginx 업스트림 DNS 재해석 + `ndots:1` + 504 나누기, `turn-metrics`(새), infra-validate `mark_config` · 단언 추가, shellcheck 두 판 경고 0, image-checks ③ 오류 구분 · ⑧ 재설계, RUNBOOK |
| CI | `.github/workflows/ci.yml` Trivy 실행기에 쓰기 가능한 TMPDIR(실행기 `/tmp` 읽기 전용 거짓 실패). 관문 · 예외 목록 · 경로 필터 불변 |
| 계약 · 문서 | `contracts/ai-io.md` 45 ~ 47행, `contracts/deviations.md` (z). 설계 문서는 고치지 않음(차이는 `code-summary.md` '설계 문서와 달라진 곳') |

### 15.3 재시험 실행 — 명령 ID별 1차 → 재시험(rc · 소요 초, 바뀐 것은 굵게)

1차와 같은 명령 · 순서 · DB 백엔드(docker). 명령마다 rc · 시작 · 끝 · 소요는 `build/b-and-t-r2/logs/rc.tsv`, 출력은 `logs/<ID>.log`, 전체 표는 `logs/rc-compare.md`. 1차에 없던 항목: IT-03 데움 1회, U-3 · U-7 재실행, CI 판 조합(IT-04a-ci · -sc090 · -c238, IT-04b-c238 · IT-04c-c238), ST-17 단독, ST-20 이미지별 Trivy 보충, IT-06b 스냅샷 숫자 키 검사, `turn-metrics` 실행, `mj --scale`.

환경 사정(결과에 그대로 적음): ① 17:15 ~ 17:32 다른 담당의 CPU 부하(`yes` 2개)가 U-8 ~ U-7 1차 실행과 겹쳤다. ② IT-02 뒤 사용 한도로 끊겼고 기계가 재부팅돼(uptime 8분, Docker 29.8.2 재기동) 이어서 돌렸다 — 끝난 단계는 다시 돌리지 않았다. ③ 21:58부터 다른 작업 트리의 Gradle 시험 실행(java 약 300% CPU, load 5.5)이 IT-03 · ST-05와 겹쳤다. ④ 디스크 87%(여유 5.1GB).

| 명령 ID | 1차 rc(소요 s) | 재시험 rc(소요 s) | 바뀜 · 요지 |
|---|---|---|---|
| S0-docker · fingerprint · gitstatus · tools | 0 | 0 | Docker 29.8.2, 지문 `41d95b38…`, 작업 트리 감사 조각 1개 |
| U-1 · U-2 · U-4 | 0 · 0 · 0 | 0(39.6) · 0(1.7) · 0(19.1) | 잠금 파일 불변, `FAILED` 0줄 |
| B-01 · B-02 · B-03 | 0 · 0 · 0 | 0(19.4) · 0(13.2) · 0(15.0) | 두 jar 시험 항목 0건, eslint · tsc 통과 |
| BV-1 · 2 · 3 · 5 | 0 | 0 | jar 67,477,363B · 60,134,532B, `시험 항목 0건` 2줄, standalone 3파일, 트리 불변 |
| U-8 · U-9 · U-10 | 0(45.7) · 0(30.6) · 0(13.5) | 0(169.1) · 0(76.2) · 0(52.0) | 부분 명령 통과(CPU 부하 겹침으로 소요 증가) |
| U-12 · U-13 | 0 · 0 | 0(30.3) · 0(41.5) | 351 · 183건 통과(1차 344 · 175) |
| **U-3** | 0(127.9) | **1(499.2)** → U-3-r1 **0(284.0)** | 1차 실행 1,442건 중 2건 실패(`DeviceRegisterApiIT.aConnectedDeviceBlocksReRegistrationAndAnOfflineOneIsReplacedWithDeviceReleased` — DeviceReleased가 Realtime에 가지 않음, `RealtimeLogHygieneIT.aWholeSessionLeavesNoSecretsTranscriptsOrAudioInLogsPayloadsOrTheDatabase` — '입력 음성 중계 20' 미도달; 둘 다 시간 민감 IT · CPU 부하 겹침) → 공통 지침 9대로 한 번 재실행 1,442건 실패 0 · 오류 0 · 건너뜀 0. 1차 실패 XML `unit/failures-run1/`, 합계 `unit/U-3-junit-run1.txt`. 고치지 않음(J370) |
| **U-7** | 0(11.7) | **1(569.1)** → U-7-r1 **0(22.8)** | 1차 실행 `SessionLifecycleIT.aFullTurnThenGuardianStopEndsConfirmedWithTheClosingSequenceOnce` 1건 실패(기기 시뮬레이터 송신 실패 — `INTERNAL_HTTP VERIFY_TOKEN CONN_ERROR 54ms attempt 1` → 기기 연결 1013 → `Output closed`, 그 순간 METRIC_SNAPSHOT `sysCpuAvgPct` 97 ~ 100) → 재실행 세 모듈 JaCoCo 하한 통과 |
| U-5 · U-11 | 0 · 0 | 0(77.9) · 0(71.1) | 34파일 514건 통과(1차 499), 줄 98.93%(1차 98.82%) |
| U-6 · U-14 | 0 · 0 | 0(15.7) · 0(16.2) | 8파일 105건 통과(같음; HEAD `b9a53a5` 뒤 106건) |
| TC10-fe-json · tools-json | 0 · 0 | 0 · 0 | 파일별 5 미만 0 · 0 |
| PT-01 | 0 | 0 | 143,891 · 6,420 · 156,359B(1차 143,827 · 6,420 · 156,351) |
| IT-02 | 0(290.9) | 0(511.9) | `RESULT PASS rc=0 total=509s`, A1 302s · A2 82s · B 86s · C 15s(모든 단계 PASS, A1 1,442건 실패 0) |
| ST-04-frontend · tools | 0 · 0 | 0 · 0 | 13파일 199건(1차 192) · 2파일 29건 통과 |
| **IT-03-warm** | — | **1(115.4)** 새 항목 | Docker 재기동 직후 데움: B TIMEOUT 106초(판정 아님, 3.6 공개 사항 7, J371) |
| IT-03 | 0(60.0) | 0(91.8) | E0 PASS, `RESULT PASS total=89s`, 581바이트(기한 110초 안, 1차보다 30초 느림 — 다른 Gradle 실행 겹침) |
| ST-05 | 0(57.5) | 0(76.0) | KEEP=1, 6서비스 healthy 유지 |
| ST-06-12 · ST-13 · ST-14 | 0 · 0 · 0 | 0(6.4) · 0(5.8) · 0(4.5) | 8절 그대로(15.8) |
| IT-04a | 0(14.9) | 0(22.8) | 8검사 PASS |
| IT-04a-ci · -sc090 · -c238 | — | 0 · 0 · 0 새 항목 | shellcheck 0.9.0 × Compose v2.38.2 8검사 PASS, 0.9.0만 PASS, v2.38.2만 8검사 PASS |
| IT-04b · IT-04b-c238 | 0(36.7) · — | 0(65.1) · 0(72.3) | 39건 어긋남 0(두 Compose 판, 1차 32건) |
| IT-04c · IT-04c-c238 | 0(5.2) · — | 0(31.0) · 0(35.7) | 32건 어긋남 0(두 판, 1차 23건 — turn-metrics 사례 9 추가) |
| IT-04d | 0 | 0(12.4) | 관문 5종 양성 통과 · 음성 거부 |
| B-04 · BV-4 | 0 · 0 | 0(17.0) · 0 | 7종 빌드, 라벨 지문 7종 = `41d95b38…` |
| IT-05 | 0(54.2) | 0(109.1) | ① ② ④ ⑤ ⑥ ⑦ ⑧ PASS, ③ SKIP(Trivy 없음, ST-20에서) — ⑧은 새 설계(정지 → 502) 포함 |
| ST-15 | 0 | 0(3.8) | real → REFUSED rc 3, synthetic → OK rc 0 |
| **ST-20** | **1**(③ FAIL) | **0(229.1)** | `PATH=S/bin:$PATH image-checks.sh` ① ~ ⑧ 모두 PASS(③ Trivy 포함), rc 0 |
| ST-20-trivy-per-image | — | 0 새 항목 | 이미지 5종 각각 `trivy image --severity HIGH,CRITICAL --ignore-unfixed` 취약점 0(J377) |
| **ST-19** | **1**(21건) | **0(31.2)** | `trivy rootfs` 운영 bootJar 2개 HIGH · CRITICAL 0, `trivy 백엔드 rc=0` |
| ST-01-16 | 0 · 7 · 0 | 0 · 7 · 0 | 다이제스트 = ci.yml, gitleaks 양성 rc 7, 이력 `no leaks found` rc 0 |
| ST-17 | (1차 ST-01-16에 포함) | 0 | 추적 env · 키 파일 0, 번들 걸림 0 |
| ST-18 | 0 · 0 | 0 · 0 | 예외 1건 유효, npm 관문 'high 이상 1건(braces 예외 candidate) · 위반 0', omit-dev 두 쪽 0 |
| ST-22-25 | 0 | 0 | actionlint rc 0, uses 24개 SHA 고정, 권한 그대로, 정적 검토 · 문구 · 문서 1차와 같음 |
| IT-06a | 0(50.1) | 0(54.5) | 6서비스 healthy, C1 ~ C12 PASS(14.3초) |
| IT-06b · IT-06b-snap | 0(65.5) · — | 0(65.7) · 0 | **METRIC_SNAPSHOT core 1 · realtime 1줄(1차 0 · 0), 숫자 아닌 키 0**, 지표 이름 15.4 |
| PT-01x-PT-04 | 0 | 0 | 전송 143,036 · 155,441B, HTML 목록 = 정적 집합, le 0.1 · 0.3 · 0.5 · 1.0 버킷 있음 |
| IT-06c | 0 | 0 | traceId 하나가 Nginx 1 · Core 7 · Realtime 15 · outbox 5 · inbox 5에서 같음 |
| IT-06d | 0(44.6) | 0(47.2) | 정지 직후 UNRESPONSIVE → 36초 뒤 UNKNOWN · 423 DEVICE_NOT_STABLE, 12 조회 200(사본), fallback 0 → 1 |
| **IT-06e** | 0(미달 504) | 0(43.8) | **첫 요청 502 UPSTREAM_UNAVAILABLE 3.008초(연결 시한 3초 안), 그다음 4요청 502 4 ~ 13ms**, WSS 101 → 4401, conn_error 0 → 3, 재기동 뒤 200 |
| IT-06f · IT-06f-r1 | 0 · 0 | 0 · 0 | 20초 뒤 503 SERVICE_NOT_READY · Retry-After 5, Core /internal 503 · Realtime 그 순간 401 → 재실행 17 · 22초에 두 앱 503, 복귀 4 · 11초 뒤 200(1차와 같은 양상) |
| IT-06g | 0(506.9) | 0(507.4) | kill -9 3회 healthy 9.5 · **20.3** · 9.4초(1차 7.3 · 9.1 · 6.8), probe downtime 9 · 18 · 8, STARTUP_RECOVERY 매번 1줄, pause 45초 → unhealthy · 재시작 0 |
| **IT-06hij** | 0(430.5, h 미달 504) | 0(83.3) | **h: /device 첫 요청 502 점검 페이지 3.007초, 그다음 502 5 ~ 6ms**, /api/health 200, WSS 101 → 4401, 재기동 뒤 200. i: 망 분리 · web → postgres CLOSED timeout · 소유자 비밀 이름 0(15.8 nginx 해석 주석). j: exit 143 · stopSec 0.7 |
| **IT-06j-r1** | 0(미달: 503 0건) | 0(17.7) | 20ms 표본: SIGTERM 뒤 0.012s 200 → **0.040s · 0.066s 503(Retry-After: 5) 2개** → 0.094s부터 502 ×27(유예 안) · 정지 뒤 502 ×134, stopSec 0.79 · exit 143 |
| IT-06k | 0 | 0 | 컨테이너 · 망 · 볼륨 0, 생성 비밀 디렉터리 0 |
| PT-turn-metrics | — | 0 새 항목 | `turn-metrics --file …latency-it02.jsonl` rc 0(15.4 BT-NFR5.4) |
| S13-mj-scale | 64 | 64 | `--scale은 쓸 수 없다` rc 64 |
| S14-end | — | 0 | 지문 · HEAD 같음, 컨테이너 0 · mujoong 망 0, 비밀 디렉터리 0 |

ST-20-fix1(1차 감싸개 고침 재실행)은 재시험에 해당 항목이 없다(감싸개가 처음부터 TMPDIR을 가짐).

### 15.4 Not Met 11개 재판정(판정 기준은 Matrix Target 열 그대로)

| ID | 1차 | 재시험 실측값 | 증거(`build/b-and-t-r2/` 아래) | 새 판정 |
|---|---|---|---|---|
| BT-TC-10 | Not Met(26클래스 5건 미만) | 백엔드 141클래스 1,442건, 5건 미만 **0**(1차 26클래스 → 5 ~ 7건, 목록 `TC10-backend-per-class.txt`). 프런트 34파일 · tools 8파일 5건 미만 0 | `unit/U-3-junit.txt` · `unit/TC10-backend-per-class.txt` · `unit/TC10-{frontend,tools}-per-file.txt` | **Met**(참고: 상한 8건은 1차처럼 판정에 쓰지 않음, 최대 65건 클래스 있음) |
| BT-NFR9.13 | Not Met(bootJar 21건 · 이미지 5종) | ST-19 `trivy rootfs` HIGH · CRITICAL **0** rc 0. ST-20 ③ PASS(이미지 5종 각각 0, 보충 `st20-trivy-summary.txt`). npm 관문 rc 0(braces 예외 1건 candidate), omit-dev 0 | `security/trivy-backend.txt` · `st20-image-checks.txt` · `st20-trivy-*.txt` · `st18-npm.txt` | **Met**(로컬 실행분. CI 관문 실측은 여전히 뒤 단계, J376) |
| BT-NFR4.13 | Not Met(정지 시 504) | core-api `docker stop` 직후 첫 `/api/health` **502** UPSTREAM_UNAVAILABLE 3.008초(연결 시한 3초 안), 이후 502 4 ~ 13ms; web 정지 `/device` 첫 요청 502 점검 페이지 3.007초, 이후 5 ~ 6ms; 다시 띄운 뒤 200 · 200. DB 정지 503 + Retry-After · 두 앱 /internal 503 · 복귀 200 그대로 | `IT-06/health-core-stop-timing.txt` · `health-core-stop-*.json` · `web-stop.txt` · `health-db-stop.txt` · `internal-db-stop-r1.txt` | **Met**(hang은 설계 J354대로 504 유지 — IT-05 ⑧ 사례 3 PASS, J372) |
| BT-NFR6.1 | Not Met(허용 밖 4종) | S13 검색: 1차 허용 밖 4종(AsyncRecorder 5ms · ConnectionSender · InputRelay 1초 · EventLogger.forTest) 모두 없음. 남은 걸림은 단위 변환 · 설정값 변환과 허용 목록 줄(CoreMetrics REST_FAST 300ms · VIEW_FAST 500ms(J324), InboxDispatcher keepAlive 0L(J327 · J307))뿐. 자동 검사 `TimeLiteralScanTest` core 6 · realtime 7건 통과, `forTest` 운영 소스 0 | `S13/searches.txt` · `unit/regression-F1-F5.txt` | **Met** |
| BT-NFR4.15 | Not Met(연결 상태 분포 · 풀 대기 없음, 이름 다름) | IT-06b 스크랩: `mujoong_device_connection_state` realtime 1, `mujoong_db_pool_wait_exceeded_total` core 1 · realtime 1, `mujoong_device_connections_open` realtime 1(설계 이름, `ws_connections_open` 없음), health_up · internal_http(IT-06d 사본 폴백 0 → 1 · conn_error, IT-06e conn_error 3) · security_event 있음, 외부 /actuator 4경로 403 · /api/health 200 | `IT-06/metric-names.txt` · `prom-*.txt` · `actuator-external.txt` · `prom-core-after-rt-stop.txt` | **Met** |
| BT-NFR7.6 | Not Met(카운터 없음) | 풀 10 · 10(PT-05), `mujoong_db_pool_wait_exceeded_total` 두 앱 노출, `DbPoolWaitMonitorTest` 두 앱 9건 · `MetricSnapshotIT`(실제 풀 대기) 통과 | `S13/searches.txt` · `IT-06/metric-names.txt` · `unit/regression-F1-F5.txt` | **Met** |
| BT-NFR7.8 | Not Met(Outbox 2종 없음) | 7종 모두 노출: jvm_memory · gc · threads, `mujoong_device_connections_open`, hikaricp active · pending, `mujoong_outbox_pending` · `mujoong_outbox_oldest_pending_seconds`(두 앱), 외부 403 | `IT-06/metric-names.txt` · `actuator-external.txt` | **Met** |
| BT-OD-06 | Not Met(65초 0줄) | 65초 대기 뒤 METRIC_SNAPSHOT core-api 1줄(16키) · realtime 1줄(12키), attrs 숫자 아닌 값 0 | `IT-06/metric-snapshot.txt` · `metric-snapshot-detail.txt` · `metric-snapshot-*.jsonl` | **Met**(재시작 뒤 이전 줄 보존은 컨테이너 로그 드라이버 몫 — 따로 관측하지 않음, J375) |
| BT-NFR5.4 | Not Met(turn-metrics 없음) | `git ls-files` turn-metrics 1. `bash infra/bin/turn-metrics --file latency-it02.jsonl` rc 0: NFR5.1 20ms(보류, 표본 1) · NFR5.2 1044ms ± 2ms(보류) · NFR5.3 서버 몫 13ms(보류) · 미관측 1/2턴 GATE_DISCARD. it03도 rc 0. C12 PASS · LATENCY_TURN · SESSION_ITEMS_SERVED 키 있음 | `PT-05/turn-metrics.txt` · `PT-02/latency-it0*.jsonl` | **Met** |
| BT-NFR7.4 | Not Met(표식 1/12) | 표식 파일 27(Core 정의 1 + 8, Realtime 정의 1 + 17), 표식 번호 SI-1 ~ 10 · 12 모두 있음, **SI-11은 두 앱에 그 상태(TTL 캐시)가 없어 표식 대상 0**(두 구조 시험이 전제 검사). `SingleInstanceMarkerTest` core 7 · realtime 7건 통과, Compose 불변식(IT-04a) PASS, `mj --scale` rc 64 | `S13/searches.txt` · `S13/mj-scale.txt` · `IT-04/IT-04a.log` | **Met**(12곳 중 11곳 표식 + 1곳 대상 없음 — '대상 없음'을 인정하는지는 사람 몫, J374) |
| BT-RD-13 | Not Met(503 0건) | 20ms 표본: SIGTERM 뒤 상태 순서 **200 → 503(Retry-After: 5, 2개, 0.040 · 0.066초) → 502 → 재기동 뒤 200**, stopSec 0.79 · exit 143(137 아님). 유예 안 표본 30개 중 503 2 · 502 27 · 200 1 — 진행 중 요청이 없어 T6이 약 70ms에 끝나고 그 뒤(T7, 프로세스 종료까지 약 0.7초)는 접속 거절 → 입구 502. `/internal/events` 503 · 새 WSS 1013 · 열린 WSS 1001 · 공급자 닫힘 · 유예 소진은 `GracefulDrainIT`(core 5 · realtime 6건) 통과 | `IT-06/sigterm-r1/sigterm.txt` · `samples.txt` · `IT-06/sigterm.txt` · `unit/regression-F1-F5.txt` | **Met** — IT-06j 기대의 상태 순서(200 → 503 → 502 → 200) · Retry-After · 30초 안 종료를 모두 관측. **단** 503 창은 수십 ms(리뷰 지적 1 그대로)이고, 설계 문구 '커넥터는 유예(30초) 끝까지 열어 둠' 기준으로 읽으면 Not Met이다(최소 유지 시간 = 사람 결정, J373) |

### 15.5 회귀 확인

**리뷰 결함 8건(모두 이번 실행에서 통과)**

| 결함 | 회귀 시험(담당 보고의 이름) | 있음 · 결과 |
|---|---|---|
| F1 | `OutputGateTest` 7건(audioThatCameBeforeItsTranscript… · aResponseCutByTheQuietTime… · aQuietExpiryThatOvertakes… · fragmentsOfAnotherResponse… · aCompleteResponseIsGatedAlone… · aTranscriptThatNormalizesToNothing… · aWholeResponseWithItsDoneSignalPasses…) + `SegmentBufferTest` 6건 | 16 · 6건 모두 있음, bad 0 |
| F2 · F4 · F5 | `SessionExecutorFaultTest` 12건(F2 3 · F4 3 · F5 6) | 12건 있음, bad 0 |
| F3 | `StartActivityPolicyTest.conflictAfterTimeoutRetryClosesTheCopyLocallyAndCancelsTheSession` | 있음, 16건 bad 0 |
| FE-1(+J367) | device-machine '재접속 주기 … reconnecting …(BR9.7 · J360)' · '한 번도 연결되지 않은 기기 … connecting(BR9.4 · J367)' · '끄기 뒤 다시 켜기 …(J367)', device-app '재접속 주기 내내 라벨과 표정 …(J360)' · '처음 연결에 실패한 기기 …(J367)' | 모두 passed(vitest JSON) |
| FE-2 | api-types 'networkMode always … J361', live-12 '오프라인이어도 … J361', done-17 · home-04 '오프라인 … J361' | 모두 passed |
| FE-3 | register-05a '칸을 고치고 다시 시도 … J362' · '진행 중이면 두 번째 요청 없음(J362)', free-08 같은 2건 | 모두 passed |

보조: 수정 라운드의 다른 새 시험 클래스도 모두 통과 — 두 앱 `GracefulDrainIT`(5 · 6) · `TimeLiteralScanTest`(6 · 7) · `SingleInstanceMarkerTest`(7 · 7) · `MetricSnapshotJobTest`(12 · 9) · `MetricSnapshotIT`(6) · `CoreMetricsTest`(6) · `RealtimeMetricsTest`(5) · `RealtimeMetricsScrapeIT`(7) · `DbPoolWaitMonitorTest`(9 · 9) · `RealtimeStartupSequenceTest`(6) · `CoreStartupSequenceTest`(11). 목록 `unit/regression-F1-F5.txt`. 재시험 뒤 커밋의 회귀 시험(고치기 전 실패 확인됨): `DeviceGatewayIT` `recoveryRaisesLastSeenAtSoTheDisconnectCheckDoesNotFlipItBack` · `aHeartbeatWhoseRecoveryUpdatesNothingResyncsTheCopyFromTheLedger` · `repeatedSilenceRoundsRecoverWithASingleHeartbeatAndNeverFlipBack`, `ConnectionStateWriterIT`(새, 5건), `DeviceConnectionRepositoryIT.recoveryRaisesLastSeenAtToTheHeartbeatTimeButNeverLowersIt`(J390 ~ J392), tools `scenario-plan.test.mjs` 3사례(J393) — 모듈 전체 명령에서 실패 0.

**1차 Met 중 회귀 · Unverified 중 판정 변경**

- 회귀한 Met: **없음**(58개 모두 Met 유지). 눈여겨볼 값: BT-NFR4.9 강제 종료 복구 core-api 20.3초(1차 9.1초, 목표 1분 · 5분 안 — Met 유지, 기준선 대비 10% 이상 증가로 위험 목록), BT-VC-02 엔진 89초(1차 59초, 110초 안), BT-LC-5는 IT-06i 스크립트 출력이 바뀌었지만 측정 스크립트 결함으로 확인(15.8, J378).
- Unverified 중 판정이 바뀐 것: **없음**(51개 사유 그대로). 사유 보강: BT-PD-6(실공급자 한 턴 계산)은 도구 `turn-metrics`가 생겼지만 실공급자 로그가 없어 그대로. BT-RD-13의 WSS 1013 · 1001 · 공급자 닫힘은 이제 `GracefulDrainIT`로 덮임. BT-CD-01 Actions 관문 3종은 재시험이 돌리지 않았으므로 그대로(`aabce31`에서 CI 전부 녹색, `b094c0d` backend 작업 `DeviceGatewayIT` 1건 · `8a9225a` tools 작업 `scenario-plan` 1건 흔들림 → 15.9).

### 15.6 합계(120개)

| 묶음 | 목표 수 | Met | Not Met | Unverified |
|---|---|---|---|---|
| 1차 | 120 | 58 | 11 | 51 |
| **재시험** | **120** | **69** | **0** | **51** |

### 15.7 시험 수와 커버리지

| 층 | 명령 | 총 | 통과 | 실패 | 건너뜀 | 1차 |
|---|---|---|---|---|---|---|
| 백엔드(4모듈), 재시험 `b094c0d` | U-3-r1 | 1,442 | 1,442 | 0 | 0 | 1,196 |
| └ contract-model · core-api · realtime-activity · test-support | | 279 · 607 · 529 · 27 (14 · 70 · 53 · 4클래스) | | | | 276 · 506 · 387 · 27 |
| **백엔드(4모듈), HEAD `8a9225a` 뒤 모듈 전체 명령** | `./gradlew --no-daemon :contract-model:test :core-api:test :realtime-activity:test :test-support:test :…:jacocoTestCoverageVerification -PincludeTags=u1 -PcoverageScope=u1` | **1,451** | 1,451 | 0 | 0 | — |
| └ contract-model · core-api · realtime-activity · test-support | | 279 · 607 · **538** · 27 (14 · 70 · **54** · 4클래스), 5건 미만 클래스 0 | | | | |
| 프런트 | U-11(U-5 같음) | 514 | 514 | 0 | 0 | 499 |
| tools, 재시험 | U-14(U-6 같음) | 105 | 105 | 0 | 0 | 105 |
| tools, HEAD `b9a53a5` | `npx --no-install vitest run tests/u1` | **106** | 106 | 0 | 0 | — |
| 합계(재시험 · HEAD) | | 2,061 · **2,071** | | 0 | 0 | 1,800 |

DB 백엔드 docker 10 · local 3(`[MujoongPostgres]`). IT-01 경계 39클래스 모두 있음 · bad 0 · skipped 0. ST-03 59클래스 598건(1차 557) MISSING 0. 1차 실행(U-3 · U-7, 부하 겹침)의 실패 2 + 1건은 15.3. HEAD 1,451의 근거는 저장소 `backend/*/build/test-results/test/TEST-*.xml`(2026-10-11T22:42 ~ 22:45Z) 직접 집계(J385).

| 모듈 | U1 범위(U-7-r1, `b094c0d`) | 모듈 전체(A1) | HEAD `8a9225a` 뒤(JaCoCo XML) | 하한 | 1차(U1 · 전체) |
|---|---|---|---|---|---|
| contract-model | 98.53%(1478/1500) | 98.53% | 98.53%(1478/1500) | 80% 통과 | 98.53 · 98.47 |
| core-api | 95.90%(4002/4173) | 95.93%(4003/4173) | **96.00%**(4006/4173) | 통과 | 95.80 · 95.80 |
| realtime-activity | 92.03%(5632/6120) | 92.03% | **92.03%**(5650/6139) | 통과 | 89.78 · 89.96 |
| frontend `src/**` | 98.93%(1863/1883) | 98.93%(A2 NOTE) | 98.93% | 통과 | 98.82 · 98.82 |
| test-support | 51.69% | 51.69% | — | 대상 아님 | 같음 |

분기(참고): contract-model 89.36% · core-api 81.31% · realtime 75.73%(HEAD 75.82%) · 프런트 96.38%.

### 15.8 그 밖의 측정 · 기록(판정 아님)

- 소스 지문 시작 · 끝 `sha256:41d95b389c2aa3669e1d5219a81c4245deeead77049c6ba4ba8d7157235bd990`, HEAD `b094c0def8de7658bedfe28550110bfad8f6ee23`. 작업 트리 변경은 감사 조각 1개뿐(시작 · 끝 같음).
- PT-02 · PT-03 기준선(가짜 공급자, 표본 10 미만): IT-02 정상 턴 relayIn 7 · relayOut 13 · providerResponse 106 · quietWait 803ms, t1−t0 0.121s · t3−t0 1.045s · t3−t2 1ms, clockOffset 1 · rtt 4, 금지 턴 GATE_DISCARD; CHILD 서버 몫 13 · 5ms, 폴링 몫 1.681 · 4.346s; **AI 행에 tTranscriptFinal이 새로 기록**(J300, 서버 몫 813ms = 조용 대기 포함). IT-03: relayIn 6 · relayOut 19, t3−t0 1.029s. 이벤트 전달 PROCESSED 7건 64 · 356 · 612ms(1차 103 · 371 · 460), FAILED · DEAD 0. `turn-metrics` it03 구간에 '재생 시작 −6ms'(시계 보정 반올림, 참고).
- PT-04: le 0.1 · 0.25 · 0.3 · 0.5 · 1.0 … 버킷, uri 라벨 19종 + UNKNOWN(1차와 같은 집합, UUID 0), le0.3 비율 core 28/29 · realtime 8/8.
- 보안 8절: ST-06 anchor 200, ST-07 tls1 · 1_1 거절 · 1_2 · 1_3 협상, ST-08 7경로 FAIL 0 · nonce 다름 · 인코딩 변형 4개 400, ST-09 301 · 404 · 444, ST-10 201×21 · 429×10 · RATE_LIMITED 31, ST-11 관리 404 20개 · health · prometheus 200 · ulimit_core 0 · exit_on_oom · heapdump_off · uuid_labels 0 · security_event_series 2, ST-12 위반 0 · access 104 · polling_2xx 0, ST-13 skipping_init 1, ST-15 REFUSED rc 3 / OK rc 0, ST-16 양성 7 · 이력 0, ST-22 ~ 25 1차와 같음.
- IT-06i 주석: 스크립트의 `getent hosts postgres || nslookup postgres`가 재시험에서 "nginx resolves postgres"를 찍었다. 확인 결과 `getent` rc 2(없음)이고 busybox `nslookup`이 **빈 응답에도 rc 0**을 내서 생긴 측정 스크립트 결함이다(`ndots:1`로 바깥 DNS에 안 넘겨 NXDOMAIN 대신 빈 답). `nc postgres` → bad address, 실제 IP 172.30.20.2:5432 → 시간 초과(도달 불가). LC-5 판정 영향 없음(`IT-06/nginx-to-postgres-detail.txt`). `integration-test-instructions.md` 10절 IT-06i 명령은 `getent`만 쓰도록 고칠 것(이 라운드는 그 문서를 고치지 않았다).
- Step 10(교차 유닛 추적): 재시험 시점(추적 파일 갱신 전) 1차와 같은 값. 추적 파일 갱신 뒤 다시 센 결과는 `cross-unit-traceability.md`(판정 변동 없음 — FR 0 · NFR 0 · AC 56, OK 항목 192 → 196).
- 정리: 끝에 컨테이너 0 · mujoong 망 0 · `build/verify/{env,c,certs,nginx,postgres}` 0. 볼륨 13개는 이 단계 전부터 있던 것.

### 15.9 아직 남은 실패와 사다리 2단 기록

재시험에서 실패한 명령 0 · Not Met 0이다. 남은 것은 (가) 재시험 1차 실행의 시간 민감 IT 실패 3건(재실행 통과)과 CI 흔들림 2건, (나) Unverified 51개다. (나)는 11.2 I ~ M 묶음과 사람 결정 D 그대로.

| 묶음 | 걸린 것 | 원인 분류 | 해결 후보 | 영향 추정 · 상태 |
|---|---|---|---|---|
| N 경주(확정 · 고침) | CI `b094c0d` backend `DeviceGatewayIT.fortySecondsOfSilence…` 1건 | 3.5 운영 코드의 잠복 경주 2개(두 판 동일, gateway/ 변경 0줄): (A) 복귀 UPDATE가 `last_seen_at`을 올리지 않아 끊김 점검자(`CONNECTED and last_seen_at <= cutoff`)가 복귀 직후 되돌림(탐침 40라운드 중 16회 재현); (B) `ConnectionStateWriter.change()`가 커밋 뒤 `mirror(row)`로 사본을 갱신해 그 사이 HEARTBEAT의 `heartbeatRecovers(knownState)`가 false(탐침 1회 관측). 러너 상태(recorder-tick 지연 · test 풀 2연결)에서 발현 | **고침(`8a9225a`, J390 ~ J392)**: 복귀 전용 SQL `MARK_CONNECTED_AGAIN`(`last_seen_at = greatest(…)`), 사본 갱신을 트랜잭션 안으로 + 0행이면 `resyncKnownState`. 회귀 시험 9건(고치기 전 실패 확인) | 적용 뒤 모듈 전체 1,451건 실패 0. 남은 가정: `handover()` 커밋 뒤 knownState(J392). CI 산출물의 DEVICE_CONNECTION 줄로 (A)/(B) 판별은 사람이 확인 가능(프록시 403으로 못 받음) |
| N′ 경주(확정 · 고침) | CI `8a9225a` tools `scenario-plan.test.mjs` '응답이 오지 않으면 전체 기한 안에 C1 TIMEOUT' 1건(받은 값 'C1 FAIL … HttpTimeoutError 999ms') | 3.5 도구 코드: 단계 타이머(1000ms)와 그 기한으로 깎인 요청 제한 시간(999ms)이 거의 같은 순간에 끝나 먼저 오는 쪽에 따라 상태가 갈림 | **고침(`b9a53a5`, J393)**: `run-c.mjs` `ctx.limit`이 기한을 깎았는지 기억하고, 깎인 대기의 `*TimeoutError`는 단계(또는 전체) 기한 초과 TIMEOUT으로 보고. 시험 3사례 | tools 106건 3회 통과(이 문서 작성 중 1회 더 106건 통과) |
| O 부하형 흔들림(남음) | 재시험 1차 실행 `DeviceRegisterApiIT.aConnectedDeviceBlocksReRegistration…` · `RealtimeLogHygieneIT.aWholeSessionLeavesNoSecrets…`(U-3) · `SessionLifecycleIT.aFullTurnThenGuardianStop…`(U-7); rt2 보고의 `SessionLifecycleIT.aMissingAck…` 1회 | 시험 환경: '일어나는 일을 10초 안에 기다림' 형 IT가 CPU 97 ~ 100%(다른 담당의 `yes` 2개 · 동시 vitest)에서 기한 초과. 공통 서명 `INTERNAL_HTTP VERIFY_TOKEN CONN_ERROR 54 ~ 72ms attempt 1` → 기기 연결 1013. 원인 미확정(경주 (A) · (B)와 종류가 다름) | ① `realtime/internalhttp/InternalHttpClient.java:178` — IOException을 모두 CONN_ERROR로 묶어 예외 종류가 로그에 없음 → 종류를 attrs에 남겨 원인 추적 ② `test-support/.../http/ContractStubServer.java:78-80` — `HttpServer` backlog 0 · 가상 스레드 실행기 ③ `realtime/testing/RealtimeItEnvironment.java:37` — WAIT 10초(기한을 늘려 숨기지 않는다는 규칙과 상충, 근거 있을 때만) ④ CI · 로컬 실행을 다른 부하와 겹치지 않게 | ① 0.5 ~ 1시간, 위험 낮음(로그만) ② 0.5시간 + 재실행 반복으로 확인, 위험 낮음 ③ 사람 결정 ④ 운영 규칙. 이번 라운드는 고치지 않았다(공통 지침 9: 한 번 재실행 · 결과 그대로 기록) |
| P 성능 기준선 10% 이상 증가(위험, 판정 아님) | 검증 명령 전체 509초(1차 289), 엔진 89초(59), 강제 종료 복구 core-api 20.3초(9.1), 이벤트 전달 PROCESSED 최대 612ms(460) | 환경: 다른 Gradle 실행(21:58 ~) · 1차 U-3 시간대 CPU 부하 · 재부팅 직후 콜드 캐시. 코드 원인 증거 없음 | 조용한 환경에서 같은 명령을 다시 재어 13절 기준선과 비교 | 반나절, 위험 낮음. 재지 않으면 다음 실행 때 비교 불가 |
| Q 측정 보조(남음) | Docker 재기동 직후 `--engine` 데움(IT-03-warm B TIMEOUT 106초), IT-06i 스크립트 `nslookup` rc 0, BT-OD-06 '재시작 뒤 이전 줄 보존' 미관측, 디스크 87% | 3.6 공개 사항 7 · 측정 스크립트 결함 · 측정 범위 | `integration-test-instructions.md` 10절 IT-06i를 `getent`만 쓰도록, 엔진 호출 전 데움을 절차에, 다음 실행 전 `docker system prune` | 각 0.5시간 이내. 판정 영향 없음 |

### 15.10 회귀 기준선(재시험 값)

13절의 1차 값 옆에 둔다. 10% 이상 늘어난 항목은 15.9 P로 올렸다(환경 요인이 커서 조용한 환경 재측정 뒤 비교).

| 항목 | 1차 값 | 재시험 값 |
|---|---|---|
| /device 초기 JS · 지연 청크 · /parent 첫 로드(gzip 5) | 143,827 · 6,420 · 156,351바이트 | 143,891 · 6,420 · 156,359바이트 |
| 정상 턴 relayIn · relayOut · providerResponse · quietWait(가짜 공급자) | 1 ~ 2 · 8 · 104 · 801ms | 7 · 13 · 106 · 803ms(IT-02), 6 · 19(IT-03) |
| 정상 턴 t3 − t0(가짜 공급자) | 1.038 · 1.040초 | 1.045 · 1.029초 |
| 이벤트 전달 PROCESSED(최대) | 460 · 497ms | 612ms |
| 검증 명령 전체 · 엔진 | 289초 · 59초 | 509초 · 89초 |
| 강제 종료 복구(realtime · core-api · realtime) | 7.3 · 9.1 · 6.8초 | 9.5 · 20.3 · 9.4초 |

### 15.11 준비도 재평가

| 준비도 | 재시험 판정 | 근거 |
|---|---|---|
| build-ready | **충족** | 모든 빌드 명령 rc 0, BV 5종 기대대로, A1 · A2 · A3 통과 |
| test-ready | **충족**(1차 미충족) | 실행 · 판정 끝(`Pending` 0), 단위 · 통합 실패 0(재실행 뒤), 실패한 명령 0(ST-19 rc 0 · ST-20 ③ PASS) |
| deployment-ready | **미충족**(유지) | Met 69 · Not Met 0 · Unverified 51. 공급망 HIGH · CRITICAL 0. Unverified 51개 중 6개는 4.2 · 4.3, 2개는 U2, 43개는 실행 계획에 소유 단계 없음(사람 결정 D). BT-RD-13 · BT-NFR7.4의 Met에는 사람 결정 주석 동반(J373 · J374) |

### 15.12 지킨 규칙

재시험 담당은 저장소 파일을 고치지 않았다(감사 조각 1개만). 측정 목표 · 하한 · 기한 · 관문 · 예외 목록 · `.gitleaks.toml`은 수정 라운드 전체에서 하나도 낮추거나 끄지 않았다(보안 예외 1건 그대로). 시간 민감 시험의 실패는 기한을 늘리지 않고 한 번 재실행해 두 결과를 모두 적었다(J370). 이 절과 10절 매트릭스 · `build-and-test-summary.md` · `cross-unit-traceability.md` · `code-summary.md`(## 3.6 수정 라운드 절 · 틈 항목 표시) · `source-manifest.json` · `traceability.json`만 문서 담당이 고쳤다(J380 ~ J389). `aidlc-state.md` · 감사 · 메모리 · 설계 문서 · 시험 지시 문서는 고치지 않았다.
