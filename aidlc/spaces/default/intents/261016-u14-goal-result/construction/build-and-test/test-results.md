# U14 전체 시험 결과

## 실행 준비

운영 소스 d5d4d4f. /home/pdypd/mujungi-u14-verify의 Git clean 상태를 확인했다. 전체 compose 명령은 build-instructions.md, U14 필터 명령은 integration-test-instructions.md에 고정했다. 이전 c096283 전체 증거와 수정 후 태그161개/90.39% 증거를 새 전체 실행으로 오인하지 않는다.

## 결과 수집

첫 compose 실행은 A1에서 rc1, A1 377초/전체384초로 실패했다. core1553개 중1실패(U15Neo4jIT initializationError: Graph operation unresolved), RT1235개 중1실패(RealtimeDeletionHandlerIT.aFileThatCannotBeDeletedFailsTheAttemptAndTheRetryFinishesWithTheFullCount, PT10S), contract322개 실패0, test-support31개 실패0/LOCAL3skip였다. Neo4j 초기화가 실패하여 해당 클래스 실제9개 대신 초기화 오류1개로 집계되므로 성공 실행과 총수가 다를 수 있다. A2 이후 단계는 아직 실행되지 않았다.

두 클래스의 원 단언·기한을 유지하여 개별 재현하고 모듈 병렬 실행만 끈 환경으로 전체 재시도한다. 첫 개별 명령은 PowerShell의 따옴표 없는 dotted Gradle 속성이 분할돼 task-not-found로 rc1(실제 시험0)이었으며, 전체 속성 인수를 따옴표 처리하여 다시 실행한다. 시험/커버리지 기준이나 제품 소스는 변경하지 않았다.

개별 재실행은 --no-parallel --max-workers=2, 종료코드0/58초였다. U15Neo4jIT 9개와 RealtimeDeletionHandlerIT 4개 모두 실패/오류/skip0을 XML에서 확인했다. 이13개는 이후 전체 시험과 겹치므로 합산하지 않는다. GRADLE_OPTS=-Dorg.gradle.parallel=false -Dorg.gradle.workers.max=2로 전체 compose를 처음부터 재실행한다. 첫 실행의 XML 집계와 원인·종료코드는 이 문서에 보존했으며 verify.sh는 두 번째 실행에서 자신의 raw logs/report를 새로 만든다.

실제 명령 종료코드·시험 수·실패·skip·커버리지 및 전체 Target Verification Matrix를 이 파일과 build-and-test-summary.md에 기록한다. 0개 실행이나 환경 실패를 통과로 세지 않으며 중복 실행 수는 합산하지 않는다. 최종 종료 경로에서는 모든 Pending을 Met/Not Met/Unverified로 바꾼다.

## 로컬 최종 결과

전체 compose 재실행은 rc0,747초로 PASS했다(A1 544/A2 67/A3 12/A4 1/B99/C19/P1 2/P2 1/P3 0/T0초). 원본 결과는 /home/pdypd/mujungi-u14-verify/build/verify/report.txt이고 소스 지문은 sha256:54c2913128ff83b13386456a40a32c83bd69a872b6cec6f134ab00d9d465ec69다. 전체 소스 d5d4d4f375c3를 clean check하여 기존 성공의 단순 재표시가 아니다.

| 범위 | 실제 결과 |
|---|---|
| 전체 backend | core1561/RT1235/contract322/test-support31 =3149, 실패·오류0, LOCAL 대체3skip |
| 전체 모듈 줄 | core9622/10011=96.11%, RT10256/10988=93.34%, contract1527/1551=98.45% |
| frontend 전체 | 74파일994개 PASS, 줄98.55%, eslint/Next build PASS |
| tools | 11파일125개 PASS |
| Docker/끝-끝 | 이미지5종·V9 migrate·seed 멱등·기동·프로필 거부·접속·C1~C12 PASS |
| 보안/격리 | 교차 스키마 권한, A4/P2/P3 및 작업 트리 불변 PASS |
| 마지막 U14 태그 | 19클래스161개 실패/오류/skip0,47초 |
| U14 범위 |837/926줄=90.39%, 하한80% PASS,2초; 동일 태그 시험 UP-TO-DATE 재사용 |
| frontend U14 | 8개 실패0, 전체994개와 중복 합산하지 않음 |
| migration 검사 | infra-validate --only migrations rc0 PASS |
| 요구 추적 | FR/NFR17개 모두 OK, 대상 누락0; 전체 추적58행 |

전체 backend XML 집계는 필터 실행 전 verification/backend-full-results.json에 보존했다. 그 뒤 core XML은 태그161개로 바뀌었으므로 전체로 잘못 집계하지 않는다. compose/태그는 각각 verification/compose-results.json과 u14-final-results.json에 있다. test-support42.76%는 하한 대상이 아닌 지원 모듈이며 운영3모듈80%를 대체하지 않는다. 로컬 gitleaks 이력 도구는 없어 작업 트리 형식/비밀값 스캔만 PASS했고 이력 검사는 PR CI secrets의 결과로 확인한다.

## 외부 CI와 최종 판정

PR push와 CI 확인을 이어간다. 이 단계가 실행 중인 동안 CI 결과만 Pending이며 전체 목표표는 build-and-test-summary.md에 있다. 테스트 대역/실제 PostgreSQL/합성 기기 증거를 실AI/실기기 성공으로 표시하지 않는다. 배포·병합은 수행하지 않는다.
