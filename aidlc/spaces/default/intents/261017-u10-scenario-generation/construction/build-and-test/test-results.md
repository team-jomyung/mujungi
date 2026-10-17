# U10 검증 결과

## Execution status

Build and Test 실행 중이다. 최초 제품 커밋 `1e4db60d449153dd9848f527e0d31d5f3590912c`의 결과를 보존하고, main의 U11 병합(`683c669`)을 통합한 `6cd526fe4279aebb4d5eff6a9b1703a4c8f2c54f`에서 전체 검증을 실행한다. Code Generation의 부분 검증을 전체 합격으로 재사용하지 않는다.

| Command | Status | Evidence |
|---|---|---|
| 기존 ScenarioServiceTest 한정 명령 | PASS | 1e4db60 사본, BUILD SUCCESSFUL in 50s |
| u10 태그 명령 | PASS | 1e4db60 사본, BUILD SUCCESSFUL in 30s |
| 전체 verify.sh --mode compose | FAIL | 6cd526f, 758초, A1/A2 PASS·A3 FAIL, RESULT FAIL rc1 |
| infra-validate / 음성 대조 | PASS | 정적8건 및 실패 대조46건, 어긋남0 |
| 최종 PR CI | Unverified | 전체 명령 실패로 push/PR 전 수정 판단 필요 |

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

## Final results on this exit

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
