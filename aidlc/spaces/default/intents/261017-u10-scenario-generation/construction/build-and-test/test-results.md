# U10 검증 결과

## Execution status

source7125ee009525c314226ec181f61c4d87efe2f187의 전체 재검증은 2026-10-17T13:56:40Z에 시작해767초 RESULT PASS rc0으로 끝났다. source fingerprint는 sha256:b5bc79c2f52bc9b43e59d9718a73003996f83354e6fb2f345604489af9f08a0d다. 새 판의 확정 결과는 이 문서 마지막 절에 적었다. 아래 bd666d0 표와 본문은 이전 실패 근거로 보존한다. 명령 통과와 기존 R-06 미충족·실호출 미검증은 구분한다.

이전 전체 실행은 수신 대기 수리 커밋 `bd666d0b912f1a6db098eeaeaf3935cdde0c7938`에서 C9 실패로 끝났다. main U11 병합, 최초 제품과 중단·실패 이력은 아래에 보존한다. Code Generation의 부분 검증이나 중단 실행을 전체 합격으로 재사용하지 않는다.

| Command | Status | Evidence |
|---|---|---|
| 기존 ScenarioServiceTest 한정 명령 | PASS | bd666d0 사본, BUILD SUCCESSFUL in 17s |
| u10 태그 명령 | PASS | bd666d0 사본, BUILD SUCCESSFUL in 27s, 22클래스160건·실패/오류/skip0 |
| tools 개별8건 / 전체131건 명령 | PASS | 8건607ms, 전체12파일131건9.49초·실패/skip0 |
| 전체 verify.sh --mode compose | FAIL | bd666d0, 755초, A1~A4/F/B PASS·C9 FAIL, RESULT FAIL rc1 |
| infra-validate / 음성 대조 | PASS | bd666d0 사본, 정적8건 및 실패 대조46건, 어긋남0 |
| 최종 PR CI | Unverified | C9 시험 지원 정합성 보완 판단 전이며 push/PR 미실행 |

## Risks and evidence boundary

R-05와 R-06은 사용자가 위험으로 수용했다. 원 시험 단언·시한을 완화하지 않는다. 실호출·실기기는 미실행이며 대역 시험 성공과 구분한다. 단계 종료 시 모든 실제 결과·커버리지·목표 판정과 실패 경로를 확정한다.

## Pre-main evidence and integration

첫 전체 실행(1e4db60): A1 579초 PASS, backend 3310건·실패0·skip3, 운영 JAR 시험 클래스0. U10 160건·실패0·skip0, 범위1372/1418줄=96.7560%. A2 로그에는 lint·시험·Next 빌드 PASS와 줄98.55%가 있으나 상위 검증 보고서의 최종 RESULT가 없어 전체 명령은 Interrupted/Incomplete로 남긴다. 보존 자료는 검증 호스트 `/home/pdypd/mujungi-u10-pre-main-1e4db60.tar.gz`다.

첫 실행 시작 명령은 Windows→WSL 인자 전달에서 PATH 공백 때문에 Bash 파싱이 실패했다. 시험 시작 전 환경 전달만 수정해 실행했으며 시험·소스·하한은 바꾸지 않았다.

검증 중 main이 48a132b→683c669로 전진했다. main CI39008213105의 backend는 `RealtimeMetricsScrapeIT.aFullTurnMovesTheSessionRelayTurnAndItemReadyMetrics` 메시지10초 대기 실패1건이며 skeleton도 실패했다. U10과 분리한 기준선 실패다. U10 병합에서 RT 삭제 시험2곳은 승인된 입력 수신 대기를 유지하고 emitTurn의 U11 형식만 반영했다. 작업 목록은 양쪽 기존 항목을 보존했고, 충돌한 생성 지식 문서는 main 내용을 사용했다. 승인/시험 결과를 새로 만들어 기록하지 않았다.

6cd526f의 07:11 UTC 실행은 Ubuntu 재시작으로 프로세스가 종료됐다(exit1, RESULT 없음). journal의 부팅 ID는 f23b27f2→a1ab9c17로 바뀌었고 16:12:53 KST부터 새 부팅이다. 이전 커널 OOM 기록은 없으므로 메모리 부족을 확정 원인으로 쓰지 않는다. Windows 여유 물리 메모리 약1.5GiB, WSL 한도6GB를 확인했다. 시험/하한 변경 없이 검증 프로세스의 Gradle JVM 상한만 기존1GiB→768MiB로 제한해 전체 명령을 재실행한다. 사용자 WSL 설정이나 수동 Docker 스택은 수정하지 않았다.

07:14:10 UTC 실행은 시험 전21초에 A1 FAIL, 전체24초 RESULT FAIL rc1이었다. Gradle이 build-logic executionHistory 손상과 transforms/ea7cef415505ce30a0cbb49bcfe31522/metadata.bin 읽기 실패를 보고했다. 이 실행은 시험 실패가 아니라 빌드 구성 실패다. 해당 변환 캐시 디렉터리 하나를 .u10-quarantine 이름으로 보존해 재생성하고 Gradle help로 구성 복구를 먼저 확인한다. 소스나 공유 캐시 전체를 삭제하지 않는다. 원 보고서/로그는 `/home/pdypd/mujungi-u10-cache-failure-071410/`에 보존했다.

캐시 복구 시도1의 help는 다른 kotlin-dsl/accessors metadata.bin 손상으로 실패했다(12초). 손상 범위가 여러 생성 캐시임을 확인해 시도2에서는 Gradle 8.14.3 버전 생성 캐시 디렉터리를 `8.14.3.u10-quarantine-0716`으로 보존했다. 다운로드 의존 캐시 modules-2나 저장소 소스는 유지했다. 구성 확인 help가44초에 성공한 뒤 같은 커밋·원 단언으로 전체 검증을 재실행한다. 소스 수리를 위한 Code Generation loop-back은 실행하지 않았다.

사람이 Docker 컨테이너를 전부 삭제했다고 알렸다. 확인 시 Docker29.8.2는 정상이며 현재 실행의 Testcontainers가 새 Ryuk와 postgres:16.15를 생성했다. Core DB 시험은 새 PostgreSQL에서 시작했고, 삭제된 컨테이너의 자료나 건강 상태를 최종 검증 근거로 재사용하지 않는다. 이후 Compose 서비스도 검증 도구가 합성 설정으로 생성하도록 유지한다. 수동 서비스·실제 키를 임의로 복원하지 않는다.

## First full verification results (6cd526f)

6cd526f 전체 명령은 backend678초 PASS(3416건·실패0·오류0·skip3), frontend67초 PASS(74파일994건·실패0·줄98.55%·lint/Next빌드 성공), tools10초 FAIL(12파일131건 중130통과/1실패), 전체758초 RESULT FAIL rc1이다. B/C/P1/P2/P3/T는 A3 실패 뒤 실행하지 않았다.

| Backend module | Tests | Failures/errors | Skipped | LINE |
|---|---:|---:|---:|---:|
| contract-model | 322 | 0/0 | 0 | 98.4526% |
| core-api | 1722 | 0/0 | 0 | 96.1781% |
| realtime-activity | 1341 | 0/0 | 0 | 93.4167% |
| test-support | 31 | 0/0 | 3 | 39.6871% (기존 하한 제외) |

U10은22클래스160건·실패0·오류0·skip0, 범위27파일1372/1418줄=96.7560%다. skip3은 기존 MujoongPostgresTest의 LOCAL 백엔드 조건 분기이며 Docker 시험을 제외한 것이 아니다.

tools 실패는 U11의 ‘지시를 받은 뒤의 새 말은 확인 end_ms 뒤에서 시작하고 전사가 음성보다 앞서며 앞에 낡은 꼬리 채움 음성이 있다’ 사례, 210행 정확 바이트 단언이다. expected48000/actual43200, stack은 fake-provider-wire.test.mjs:210:43이다. 서버의 turn-emitted는 마지막 전송 예약 직후 기록되므로 클라이언트 수신 완료보다 앞설 수 있다. 차이는 정확히 한 조각4800바이트다. 원본 파일 단독8건은 통과했으나 원인은 해결되지 않아 전체 FAIL을 유지한다.

독립 정적 검사 infra-validate8건·음성 대조46건은 모두 통과했다. A4 작업 트리 키 형식9종/3069파일은 걸림0, 무시 규칙 PASS다. gitleaks 이력 스캔은 도구가 없어 CI 소유이며 아직 미검증이다.

## Failure classification and estimated impact

새 실패의 원인은 U11에서 작성한 시험의 수신 동기화이며 생성 소스/시험 수리 분류다. 최소 제안은 기존3000ms 안에서 클라이언트의 전체 음성 수신을 기다린 뒤 원 정확 단언을 유지하는 것이다. docs/units/u10/tools-test-review.md에 적용 전 코드를 적었다. 예상30~50분(한정 수리·검토/전체 재검증/CI), 유료 API 비용0, 낮은 시험 변경 위험이며 운영 코드·단언·제한은 유지한다. 아직 수리를 적용하거나 코드 생성으로 돌아가지 않았고 루프백 사용은0/3이다. R-05/R-06 기존 수용은 유지한다.

## Loop-Back Log

### Loop-back 1 — 2026-10-17T07:36:13Z

- Diagnosis: U11 도구 시험은 서버 송신 예약 종료를 클라이언트 수신 완료로 취급해 마지막4800바이트 조각을 검사 전에 놓칠 수 있다. 전체131건 중1건 실패/단독8건 통과와 서버 emitSteps의 기록 순서가 근거다.
- Root-cause stage: Code Generation의 시험 코드. U10 업무 구현·기존 R-05/R-06 판단은 유지한다.
- Planned fix: docs/units/u10/tools-test-review.md의 단일 파일 수신 대기만 추가하고 원 단언·3000ms·운영 코드를 유지한다. 관련 파일8건/전체 tools131건/전체 verify.sh/PR CI를 다시 확인한다. 나머지 산출물은 Keep, 필요한 시험·기록만 Modify한다.
- Estimated impact: 약30~50분, 유료 API 비용0, 낮은 시험 동기화 변경 위험. 환경/CI 대기에 따라 늘 수 있다.
- Human choice: Retry with fix — 수신 대기 보완 후 계속 (추천). engine log answer로 기록했다. 루프백1/3이며 변경 범위 승인은 이 시험 보완에 한정된다.

### Loop-back 2 — 2026-10-17T11:14:16Z

- Diagnosis: 일반 가짜 공급자 채움700ms와 서버 계약600ms의 차이100ms가 C9 초과4800바이트와 일치한다.
- Root-cause stage: Code Generation의 시험 지원 코드. 기존 요구사항·설계와 R-05/R-06 수용 판단은 유지한다.
- Planned fix: docs/units/u10/c9-test-review.md의 세 파일만 보완한다. 일반 기본값600ms, 기존700ms 악조건 명시 보존, 정확 바이트·해시 검사 및 운영 코드·시한 유지. 기존 산출물은 Keep, 관련 시험 지원·기록은 Modify한다.
- Estimated impact: 45~75분, 사람·절전·외부 대기 별도. 유료 API 호출0, 낮은 시험 지원 변경 위험.
- Human choice: Retry with fix — 보완 후 검증·push·CI 계속 (45~75분·API 비용0·위험 낮음, 추천). engine log answer 기록 완료. 소스 loop-back2/3이다.

## Approved repair replay

수신 완료 대기9줄만 추가한 커밋 `bd666d0b912f1a6db098eeaeaf3935cdde0c7938`에서 해당 파일8건(912ms)·전체 tools131건/12파일(9.64초)이 각1회 통과했다. 원 단언·3000ms·운영 코드는 유지됐다. 재검토에서 새 지적이 없었고 사용자가 구현 완료를 승인했다. Code Generation의 기존 요구사항·설계·Testing Contract와 수용 위험은 유지한다.

2026-10-17T08:25:06Z부터 `/home/pdypd/mujungi-u10-final-verify`의 깨끗한 bd666d0 사본에서 전체 `verify.sh --mode compose`를 다시 실행 중이다. Gradle768MiB·Node22·별도 검증 네트워크를 유지했다. 이전6cd526f 전체 실패 자료는 `/home/pdypd/mujungi-u10-6cd526f-failed-verification.tar.gz`에 보존했다. 새 실행의 최종 결과·push·PR CI는 아직 미확정이다.

이 실행은 A1 613초 PASS(3416건·실패0·skip3), A2 67초 PASS, A3 12초 PASS, A4/F PASS 후 B 기동 중 최종 RESULT 없이 종료됐다. B.log에는 nginx가 exited(0)이 된 뒤 B3.up 실패가 남았고, 같은 시점에 실행 도구의 연결 상태도 소실됐다. Compose에는 앱·PostgreSQL 기동 성공이 남지만 nginx 자체 결함이나 외부 종료 원인은 확정하지 않는다. 전체 명령은 Interrupted/Incomplete이며 통과로 표시하지 않는다. 자료는 `/home/pdypd/mujungi-u10-bd666d0-incomplete-0825.tar.gz`, 해당 전체 backend XML/JaCoCo는 `/home/pdypd/mujungi-u10-bd666d0-full-backend-evidence.tar.gz`에 보존했다.

Build and Test 안의 실행 환경 복구 시도1로, 소스·설정·단언·시한을 바꾸지 않고 지속 실행 세션에서 동일 전체 명령을 재시작했다. 이 재시도는 시험 소스 loop-back을 추가하지 않는다. U10 160건 및 범위1372/1418줄=96.7560%, Core96.1877%·RT93.4966%·계약98.5171%는 위 중단 실행의 실제 완료된 A1 근거이며, 재시작 실행의 결과와 구분한다.

08:41:17Z 재실행은 A1 5651초 FAIL, 전체5654초 RESULT FAIL rc1이다. Core1722건 중 `U15Neo4jIT.concurrentCommitAndTerminationCannotResurrectAfterCleanup` 1건이 동시성 경합 전 초기 생성 seed 저장에서 Graph operation unresolved로 실패했다(사례2.032초). 같은 클래스는9건 중 나머지8건이 통과했다. 원인은 IOException 계열로 번역돼 세부 유형을 확정할 수 없다. 자료는 `/home/pdypd/mujungi-u10-bd666d0-sleep-failure-0841.tar.gz`에 보존했다.

Windows Power-Troubleshooter 사건은 SleepTime=2026-10-17T08:43:49.2510302Z, WakeTime=2026-10-17T10:12:04.1528341Z를 기록했다(약88분 절전). 해당 클래스 시작은10:12:27Z로 복귀 직후다. 절전이 긴 실행시간을 설명하며 2초 HTTP 예산 실패에 영향을 줬을 가능성은 있으나 직접 원인으로 확정하지 않는다. Awake 상태에서 동일 소스·2초 제한으로 해당 클래스1회 진단 실행은28초 성공,9건·실패/오류/skip0이었다. 이 단독 결과로 전체 실패를 합격 처리하지 않는다. 첫 진단 호출은 PowerShell의 -P 인자 분할로 시험 시작 전 실패했고, 인자를 따옴표로 전달한 뒤 실행했다.

실행 환경 복구 시도2는 지속 실행 세션과 실행 중에만 유지되는 Windows SYSTEM_REQUIRED 요청으로 자동 절전을 방지하며 같은 전체 명령을 수행한다. 전원 정책·WSL 설정·사용자 수동 스택·소스·시한·단언은 변경하지 않는다. 전원 요청은 finally에서 해제하고 수동 절전은 막지 않는다. 이 뒤에도 원인을 해결하지 못한 실패가 발생하면 반복 실행으로 녹색만 고르지 않고 새 근거와 영향 추정을 정리한다.

## Latest finalized exit — bd666d0, 2026-10-17T10:19:17Z

이 절은 앞선 판의 최종 결과다. 현재 판7125ee0의 새 전체 실행 결과는 뒤의 재실행 절에 별도로 확정한다.

전체755초 RESULT FAIL rc1이다. A1 584초 PASS, A2 66초 PASS(74파일994건·줄98.55%), A3 12초 PASS(131건), A4/F PASS, B 75초 PASS, C 11초 FAIL이다. B는 이미지5종·역할/마이그레이션·멱등 시드·프로필 거절·6개 서비스 healthy 및 HTTP/WSS/Origin/경로 거절을 포함한다. C1~C8은 통과했고 C9에서 중단해 C10~C12와 P1/P2/P3/T는 미실행이다.

| Backend module | Tests | Failures/errors | Skipped | LINE |
|---|---:|---:|---:|---:|
| contract-model | 322 | 0/0 | 0 | 98.4526% |
| core-api | 1722 | 0/0 | 0 | 96.1781% |
| realtime-activity | 1341 | 0/0 | 0 | 93.4345% |
| test-support | 31 | 0/0 | 3 | 39.6871% (기존 하한 제외) |

합계3416건·실패/오류0·기존 LOCAL 조건 skip3이며 운영 JAR의 시험 클래스0이다. U10은22클래스160건·실패/오류/skip0, 실제 운영27파일1372/1418줄=96.7560%다. Neo4j 클래스9건도 이 전체 실행에서 통과했다. 전체 backend XML/JaCoCo는 `/home/pdypd/mujungi-u10-bd666d0-awake-full-backend-evidence.tar.gz`, 실행 보고/로그는 `/home/pdypd/mujungi-u10-bd666d0-c9-failure-1019.tar.gz`에 보존했다. 후속 한정 시험이 만든 보고서를 전체 모듈 커버리지로 사용하지 않는다.

C9는 `tools/skeleton-e2e/steps.mjs:804`의 원 정확 바이트 단언에서33600/28800으로 실패했다. 가짜 공급자 새 전사 시작300ms, 전환 계약600ms, 합계900ms에서 서버가 받기 시작하지만 가짜 공급자는300+700=1000ms의 채움 음성을 보낸다. 차이100ms×48=4800바이트가 실제 초과분과 일치한다. 원 단언·해시는 고치지 않는다.

이는 시험 지원 소스 정합성 보완 후보이며 환경 복구 반복으로 해결하지 않는다. 후보는 일반 통합 기본 채움만600ms 계약에 맞추고 기존700ms 악조건은 명시 설정으로 보존하는 것이다. 변경 대상3파일·추가 기본값 대조·기존 조건 보존과 근거는 docs/units/u10/c9-test-review.md에 적었다. 예상45~75분(검토/전체 재검증/CI 포함, 사람·절전 대기 별도), 유료 AI 호출0, 낮은 시험 지원 변경 위험이다. 아직 적용·jump·push하지 않았고 source loop-back 사용은1/3이다. 모든 목표의 현재 판정은 build-and-test-summary.md에 확정했다.

## Loop-back 2 후 최신 main 통합판 재실행

기존600ms 기준의 시험 지원 수리는44c8fa3에 보존됐고 wire12건·tools135건이 통과했다. 후속 main6a785는 운영 계약과 설정을700ms로 맞춰 기존 fixture700을 유지했으며 main CI39026892852의 실제 skeleton을 포함한 작업이 성공했다. 이 main을876eaaf로 통합하고 중복600ms 기본값/선택 API를 제거했다. 최종 scenarios/server는 main과 같고 기존 wire8건·수신 장벽·700ms 자료·정확 바이트/해시·3000ms는 보존한다. 계약/기본 음성 회귀1건을 추가해 최종 wire9건, main 추가1건을 포함한 tools133건이 통과했다. FlywayConfIT는 CoreV10 이력10건과 RTV5 이력6건을 함께 보존해 실제 PG5건이 통과했다.

독립 검토는 새 지적 없이 끝났고 wire9건도 독립 실행에서 통과했다. 사용자는 추가 지침 없음과 구현 완료 승인을 각각 답했다. 원 승인 요구사항·설계·수용된 R-05/R-06을 유지하고 Build and Test를 Modify로 재개한다. 기존 실패/수리 기록은 삭제하지 않는다. 이 부분 검증과 상류 CI를 U10 전체 검증이나 PR CI 성공으로 바꾸지 않는다.

전체 명령은 source7125ee0, 2026-10-17T13:56:40Z, fingerprint b5bc79c2f52bc9b43e59d9718a73003996f83354e6fb2f345604489af9f08a0d에서 실행 중이다. Node22·JDK21·Gradle768MiB·별도 네트워크와 실행 중에만 유지하는 SYSTEM_REQUIRED를 사용한다. 영구 전원 설정·실제 키·수동 스택은 변경하지 않는다. 전체 실행 종료 뒤 모든 목표와 실제 결과를 다시 확정한다.

## Finalized local verification — 7125ee0

전체 `bash scripts/verify.sh --mode compose`는 **RESULT PASS rc0 total767s**다. A1 563초, A2 70초, A3 13초, A4/F 각1초, B91초, C21초, P1 2초, P2 1초, P3/T 각0초이며 모두 PASS다. C1~C12 전부 통과했고 C9 정상 음성2.4초·C10 금지 문장 출력0/WAITING_FILLER1회·C11 종료 뒤 중계0·C12 ENDED/STANDBY를 확인했다. 검증 도구의 건너뛰기 옵션은 사용하지 않았다.

| Backend module | Tests | Failures/errors | Skipped | LINE |
|---|---:|---:|---:|---:|
| contract-model | 322 | 0/0 | 0 | 98.4526% |
| core-api | 1722 | 0/0 | 0 | 96.1877% |
| realtime-activity | 1471 | 0/0 | 0 | 93.3393% |
| test-support | 31 | 0/0 | 3 | 39.6871% (기존 하한 제외) |

합계3546건 중3543통과·실패/오류0·skip3이다. skip은 test-support의 LOCAL PostgreSQL 파라미터3개이며 Docker 경로는 실행했다. 운영 JAR2개 시험 클래스0, frontend74파일994건·줄98.55%, tools12파일133건 통과다. U10 전체 XML은22클래스160건·실패/오류/skip0이며 운영27파일1372/1418줄=96.7560%, 누락 경로0이다.

전체 backend XML/JaCoCo는 한정 시험 전에 `/home/pdypd/mujungi-u10-7125ee0-full-backend-135640.tar.gz`에 보존했다. 전체 보고/로그는 `/home/pdypd/mujungi-u10-7125ee0-full-verification-135640.tar.gz`에 있다. 원본과 검증 사본의 Git 소스7125ee0는 동일하고 검증 사본 작업 트리는 전체·한정 실행 뒤 깨끗하다. 자료의 이전 판 수치로 새 결과를 채우지 않았다.

| Distinct command after archive | Result |
|---|---|
| ScenarioServiceTest | 21건·실패/오류/skip0, BUILD SUCCESSFUL19초 |
| contract-model/core-api includeTags=u10 | Core160건·실패/오류/skip0, 계약 태그 대상0건, BUILD SUCCESSFUL28초 |
| FlywayConfIT | 5건·실패/오류/skip0, BUILD SUCCESSFUL18초 |
| fake-provider-wire.test.mjs | 9건·실패/skip0, 전체936ms·사례698ms |
| infra/bin/infra-validate | 8건 모두 PASS |
| infra/ci/test-infra-validate.sh | 46건 어긋남0 |

각 한정 명령은 이번 검증판에서1회씩 실행했다. 전체 tools는 A3의133건으로 충족해 중복 실행하지 않았다. 한정 실행의 XML/JaCoCo는 전체 모듈 커버리지 근거로 사용하지 않는다. P1 교차 스키마/CREATE 거절42501, P2 민감 값0, P3 추적/비무시 파일 비밀 값0, T 시작/끝 불변 모두 통과다. 로컬 gitleaks 이력 검사는 도구 미설치로 미실행이며 CI secrets가 담당한다.

현재 남은 증거는 U10 push·PR의 필수 GitHub CI다. R-06의 준비 READY 마감 경계는 기존 미충족/위험 수용 그대로이고 실제 공급자15초·판단 품질은 미검증이다. 자동 명령 통과로 이 두 목표를 Met로 올리거나 완전한 배포 준비 상태라고 표시하지 않는다.
