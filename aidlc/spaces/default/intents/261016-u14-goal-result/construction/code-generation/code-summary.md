# U14 Code Generation Summary

## 구현

U15 기준 커밋 1a4ef7b 위의 unit/u14-goal-result에서 한 개발자가 순차 구현했다. 계획·요약 재승인은 생략했고 자동 재개 설정을 변경하지 않았다. Zero-Unit 기록이므로 합성 Unit/Bolt/source-manifest는 없다.

- c31dd31: V9, PracticeGoal 쓰기 소유권, C-DS10 SavedGoalReader. 기존 practice/result 클래스는 호환 façade다.
- 558d046: GOAL_CANDIDATES 입력 비식별화·검증·classify, durable pending과 커밋 후 U15 씨앗 연결.
- 347de28: practice-conversation-v1 기록, 실제 시작 장면·재판정, AFTER_REASK 보존과 27 칩 투영, 혼합 리포트, 총4회/5·15·30초 재시도.
- 마지막 묶음: 새 displayEvidence/timing REST와 프런트 파서, 타 아동 선택 차단, 기존 삭제 참여자의 새 표 FK 순서 정리, 회귀·추적성.

운영 Java 변경 26개는 backend/coverage-scope/u14.txt와 집합이 일치한다. 새 후보의 공개 recommendationId는 불변 버전 UUID이고 담기 순간 버전을 고정한다. 같은 아동·안정 후보의 활성 선택은 중복 생성하지 않는다. 선택 뒤 씨앗 연결은 새 버전만 게시한다.

씨앗 AI/graph 동안 DB 잠금을 유지하지 않는다. 결과 재생성의 늦은 pending은 최신 current_result_id 확인으로 거절하며 이전 후보를 무효화하고 이미 담은 스냅샷은 보존한다. 외부 씨앗 응답 뒤에는 동의·삭제·최신 결과를 다시 확인한다. 실패는 NONE과 DONE을 유지한다.

## c096283 기준 전체 검증 증거

각 층 구현 직후 해당 시험을 작성·실행했다. 같은 사례의 중복 실행은 합산하지 않았다.

| 범위 | 결과 |
|---|---|
| runner readiness | 기존 SceneChipMapperTest 6개 PASS |
| V9 PostgreSQL | 9개 PASS, V8 데이터 보존·구 앱 쓰기·제약 확대 |
| 마지막 U14 태그 | 18개 클래스, 147개 PASS, 실패/오류/skip 0 |
| U14 26개 Java 범위 | 812/912줄, 89.03%, 80% 관문 PASS |
| frontend U14 | 8개 PASS |
| frontend 전체 | 74파일/994개 PASS, 줄 3414/3464 = 98.55% |
| frontend lint/typecheck/build | 모두 종료코드0 |
| 전체 backend check | 최종 rc0 PASS, 3135개 중 실패0/오류0/LOCAL 대체 backend3 skip |
| 모듈 전체 줄 | core9602/9997=96.05%, RT10253/10988=93.31%, contract1528/1551=98.52%, 모두80% PASS |
| compose verify.sh/PR CI | Code Generation 후 부모의 독립 검토·Build and Test에서 수행 |

태그 실행 명령은 backend에서 다음과 같다.

```bash
./gradlew --init-script ../../u14-wsl-build.init.gradle :core-api:test :core-api:jacocoTestReport :core-api:jacocoTestCoverageVerification -PincludeTags=u14 -PcoverageScope=u14 -Pmujoong.test.db=docker
```

태그 147개와 범위 XML 집계는 verification/u14-tag-results.json에 따로 보존했다. 실행은 원본 작업 트리(347de28 + 네 번째 묶음의 미커밋 소스)였고 보고서는 /tmp/mujungi-u14-verification-build/core-api/build/reports/jacoco/test/jacocoTestReport.xml에 생성됐다. 이 경로는 다음 실행에서 덮이므로 태그 집계와 전체 결과를 구분한다.

이전의 넓은 관련 회귀는 practicegoal/result/report 전체 필터, 종료코드0, 88초였다. 그 실행의 XML을 덮기 전에 개수를 수집하지 못했으므로 개수는 미측정이다. 이후 HTTP 새7/기존8, 삭제8+reader13 등 개별 성공은 태그 147개와 겹치므로 합산하지 않는다.

프런트는 DrvFS npm ci 8분 후 worker 시작 제한(60초, 0개 실행)이라는 환경 실패가 있었다. /home/pdypd/mujungi-u14-verify를 347de28로 맞추고 원본의 results.ts 및 tests/u14/results.test.ts를 파일 도구로 반영했다. 두 파일 모두 LF 정규화·끝 공백 제거 비교 True와 git diff --ignore-space-at-eol 차이 없음으로 확인했다. 바이트 SHA256 동일이라고 주장하지 않는다. ext4에서 기존 설정 그대로 8개 시험 및 전체 lint/typecheck/coverage/build가 통과했다.

## 실패 이력과 호환 해석

최초 U14 태그 141개는 모두 통과했으나 범위 커버리지77%로 관문이 실패했다. 옮긴 legacy 추천·선택의 등록/근거 조회/즐겨찾기/삭제/복원/재담기 호환 PostgreSQL 시험 6개를 더해 89.03%로 통과했다. 범위·하한·기존 시험을 줄이지 않았다.

전체 backend 첫 실행은 core1546개 중9실패(rc1,391초)였다. V9와 기존 스키마/기동 정본의 충돌을 보고한 후 부모 판단에 따라 공통 DB 계약 기준선만 정확히 갱신했다. CoreSchemaIT의 새3표/부분 인덱스/이력9, FlywayConfIT의 적용수9/표총61, SchemaPrivilegeIT의 최고판9, CoreStartupSequenceIT의 모든1~9 낮춤·앞선10/실패11, SchemaVersionCheckTest의 현재9·앞선10·이전8 거절이다. 정확 집합·권한·멱등성·체크섬·실패거절·커버리지/ArchUnit 강도는 유지했다. 이 좁은5파일 정합 예외는 implementation-notes.md에 기록했으며 신규 사용자 승인을 받았다고 주장하지 않는다. 지정5클래스56개는 실패/skip0으로 PASS(61초)했다.

첫 전체 실행의 RT 전체 check/80% 검증은 PASS했다. 두 번째 전체 실행은 core1547개 실패0으로 완주했으나 RT1235개 중1개가 RealtimeDeletionHandlerIT의 기존 PT10S 기기 메시지 대기 초과로 실패했다(rc1,386초). RT 소스/기한은 변경하지 않았다. 해당 클래스4개는 원설정으로 PASS(33초)했고, 이후 필터 없는 전체 check가 rc0 PASS(225초)했다. 성공한 core/contract/test-support는 동일 소스·입력의 Gradle UP-TO-DATE 증거를 재사용했고 RT 전체1235개는 다시 실행했다. 최종 모듈 집계는 verification/backend-check-results.json에 구분했다.

전체 명령은 backend에서 ./gradlew --init-script ../../u14-wsl-build.init.gradle check -Pmujoong.test.db=docker였다. core1547/RT1235/contract322/test-support31개이며, test-support의3 skip은 Docker 선택 시 별도 LOCAL backend 사례다. 계약 대조·ArchUnit·기존 각 모듈80% 관문이 유지됐다. 전체 XML/coverage는 /tmp/mujungi-u14-verification-build/<module>/build/test-results/test 및 reports/jacoco/test에 있다. 프런트 명령은 ext4 frontend에서 npm run lint, npm run typecheck, node node_modules/vitest/vitest.mjs run --coverage, npm run build였으며 모두 rc0다.

V9 기존 표의 8개 제약 변경은 실제 채팅 사용자가 승인했고 부모가 승인 목록을 기록한 후 infra migration 검사가 PASS했다. 승인자 이름을 생성하지 않았다. V1~V8과 승인 SQL 해시는 불변이다.

NFR5의 동기화는 기존 설정 소유권을 따른다. 결과 작업 키는 Core에만 존재한다. Core 기본값·contracts/timing.md·ai-io.md를 함께 갱신하고 U14TimingContractTest가 RT/웹에 해당 키가 없음을 확인한다. 쓰지 않는 설정을 RT/웹에 복제하거나 운영 로직/27 화면을 바꾸지 않았다.

GOAL_CANDIDATES의 provider 책임은 별도 중복 adapter 대신 기존 CoreLunaClient + GoalCandidateGateway로 구현하고 해당 gateway 시험에 호출·schema·입력 검증을 모았다. v2 기록의 PostgreSQL 저장은 U14ResultPipelineIT, 목표 REST는 U14ResultApiIT에 함께 검증했다. 계획상의 책임은 유지하며 시험 파일을 책임별로 통합했다.

strict 공개 계약은 legacy reasonText와 새 displayEvidence를 분리하고 선택 요청 형태를 유지한다. 생산부·스키마·프런트 파서는 같은 릴리스로 배포해야 한다(contracts/deviations.md U14-a/U14-b). 직접적인 실제 키·유료 AI·실기기는 사용하지 않았다. PostgreSQL은 실제 컨테이너, AI/U15 외부 동작은 대역·합성 자료 증거다. 배포/병합은 수행하지 않았다.

## 독립 검토 1차 이후 경계 보완

위 전체 backend3135개/3skip와 frontend994개/98.55%는 c096283 기준 증거다. 아래 수정 후 전체 backend/compose/CI를 새로 통과했다고 주장하지 않으며 부모의 Build and Test에서 새 커밋으로 실행한다. 프런트/RT 소스와 계약·마이그레이션은 이번 보완에서 변경하지 않았다.

- R-01: 현대 후보가 빈 목록이어도 즉시 handler와 durable replay가 PracticeGoal의 정리를 실행한다. 빈 정리는 AI/seed를 부르지 않으며 동의 철회 후 INSUFFICIENT도 완료한다. active guardian/child/현재 DONE 결과·원본 기록 확인은 유지한다. 담기 보호자 잠금 안에서 후보 출처 결과와 현재 결과의 일치를 추가 확인해 커밋 후 callback 전 구간도 막았다. 기존 선택 스냅샷 읽기는 유지한다.
- R-02: GOAL_CANDIDATES 입력의 모든 깊이에 있는 저장 UUID를 호출별 임시 참조로 치환한다. 같은 UUID 관계는 호출 안에서 유지하고 다음 호출에는 새 참조를 사용한다. seed:S1 등 별도 skillRef 허용 목록 ID는 변하지 않는다. 실제 연습 scene 모양·중첩 배열/객체와 seed 연결을 요청 capture로 검증했다.
- R-03: modern 후보 요청과 근거 허용 목록에서 uncertain=true 전사를 제외했다. 혼합 기록에서 불확실 발화만 근거인 후보는 버리고 유효 발화 후보는 유지한다. legacy ResultData.childQuotes는 수정하지 않았다.

각 경계 구현 뒤 바로 해당 시험을 작성·실행했다. R-01 첫24개 실행은 새 실제 기록 fixture에 부모 activity가 없어 FK1개 실패했고 fixture를 고쳐24개 모두 통과했다(33초). R-02 gateway10개 PASS(12초), R-03 gateway/validator22개 PASS(10초)다. 이후 관련 전체214개 PASS(72초), U14 태그19클래스161개 PASS·실패/오류/skip0(46초), 줄837/926=90.39%로 기존80% 관문을 통과했다. 겹치는 실행 수는 합산하지 않는다.

관련214개 명령은 backend에서 ./gradlew --init-script ../../u14-wsl-build.init.gradle :core-api:test --tests 'kr.mujoong.core.practicegoal.*' --tests kr.mujoong.core.result.SessionResultHandlerIT --tests kr.mujoong.core.result.ResultReconcilerTest --tests kr.mujoong.core.result.U14ResultPipelineIT --tests 'kr.mujoong.core.practice.*' -Pmujoong.test.db=docker였다. 태그/범위 명령은 앞의 동일 명령을 새 소스로 다시 실행했다. 현재 XML은 /tmp/mujungi-u14-verification-build/core-api/build 아래이며 별도 verification/review-repair-results.json에 집계를 보존했다. 기존 두 검증 집계 JSON은 덮지 않았다.

추적성58행 대상은 모두 존재하며 NFR3을 새 빈 목록 재생 PG 시험으로 연결했다. U15 기준 이후 변경 운영 Java26개와 scope 목록의 집합은 계속 정확히 같다. 기존 다른 유닛의 동작 단언/하한·리뷰 파일·엔진 상태는 이번 수정에서 변경하지 않았다.
