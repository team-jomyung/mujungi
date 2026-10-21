# U16 구현 검증 근거

## 기준과 실행 구분

변경 전 전체 기준선은 baseline-results.md에 기록했다. 아래는 구현 중 대역/로컬 PostgreSQL 검증이며 실제 모델·실기기·실아동·말소리 청취 합격이 아니다. 운영 notice-assets.json은 비어 있어 실제 안내 자원이 없으면 일반 대화를 차단한다. 기존 줄 80%·계약·ArchUnit 하한과 실호출 제외는 유지한다.

기능 범위는 backend/coverage-scope/service-guardrail.txt의 실제 변경 운영 Java 34개 전체(기존 대형 클래스 전체 포함)다. 기존 runner의 scopedClassPatterns가 모듈 경로·파일 존재를 검증하고 해당 클래스와 내부 클래스를 읽는다. service-guardrail.tests는 대응 시험 140개 클래스와 요구/회귀 근거를 기록한다(최초135개+V8 전체 회귀5개). includeTags=service-guardrail은 JUnit 태그로 실행 대상을 고른다. 시험 목록 파일 자체를 실행 선택기로 읽는 것은 아니다.

## 현재 소스 관련 회귀

2026-10-21 KST, backend 작업 디렉터리:

```sh
./gradlew --no-daemon :realtime-activity:test --tests '*DialogInstructionsTest' --tests '*OutputGateTest' --tests '*GenerationSendExecutorTest' --tests '*GuardrailExecutorTest' --tests '*SessionExecutor*Test' --tests '*SessionVoiceTest' --tests '*SessionSettleTest' --tests '*SessionLifecycle*IT' --tests '*NoticeDeliveryServiceGuardrailIT' --tests '*GuardrailTurnIT' --tests '*SessionRecordIT' --tests '*ParticipationServiceGuardrailTest' -Pmujoong.test.db=docker
```

종료 0, BUILD SUCCESSFUL 2m14s. JUnit XML 합계 183개, 실패/오류/skip 0. 실제 WS 안내 완료·원자적 참여, 모델 후보 검수/음성 대응, 거절 시 검증된 인사 상태와 미검증 무재생 강화 사례를 포함한다.

## 실패 수정과 취소 실행

기능 태그 확장 첫 실행과 중간 좁은 실행은 알려진 실패 후 현재 소스 수정에 앞서 정상 취소했다(종료 130). 이 실행들은 PASS 또는 최종 커버리지 근거가 아니다. 기존 안내 누락/조기 수락·반복 대안·재생 만료를 완료로 보는 전제는 승인된 FR2/BR2에 맞춰 실제 완료 후 수락, 직접 거절, 실패 보류로 강화했다. 자유대화/연습 지시 비교는 실제 모드를 명시한다. 안내가 추가된 기존 관측은 현재 session/playback/contentVersion과 안내 seq를 캡처해 준비 구간을 구분하고, 생성 후보의 검수·음성 대응·개수·폐기 단언은 보존했다.

아래는 구현 중 실행 기록이다. 최종 소스의 기능/전체 결과는 문서 뒤의 최종 결과와 구별해 읽는다.

## 기능 범위 검증

실제 git diff 및 untracked 운영 Java와 범위 목록을 대조했다: 실제 변경 34개/범위 34개, 누락/추가/없는 파일 0. 시험 목록 135개 전부 존재하며 service-guardrail 태그를 포함한다. 기존 태그를 제거하지 않았다.

backend에서 아래 승인 명령은 종료 0, BUILD SUCCESSFUL 27s였다. DTO 시험 8개, 실패/오류/skip 0. JaCoCo XML LINE covered=19, missed=0(100%).

```sh
./gradlew --no-daemon :contract-model:test :contract-model:jacocoTestCoverageVerification -PincludeTags=service-guardrail -PcoverageScope=service-guardrail
```

근거: backend/contract-model/build/test-results/test/ 및 backend/contract-model/build/reports/jacoco/test/jacocoTestReport.xml. 뒤의 전체 clean check는 이 보고서를 모듈 전체 보고서로 다시 생성하므로 위 수치는 해당 기능 명령 완료 직후 읽은 값이다.

Core 기능 명령은 종료 0, BUILD SUCCESSFUL 1m43s, 160개 실패/오류/skip 0. LINE covered=265, missed=12(95.67%)다.

```sh
./gradlew --no-daemon :core-api:test :core-api:jacocoTestCoverageVerification -PincludeTags=service-guardrail -PcoverageScope=service-guardrail -Pmujoong.test.db=docker
```

근거: backend/core-api/build/test-results/test/ 및 backend/core-api/build/reports/jacoco/test/jacocoTestReport.xml.

Realtime 기능 재실행은 기존 SessionVoiceIT 전사 집계, TechnicalPauseTest 2개 미완료 안내 전제, LiveContentRealtimeStartupChecksTest의 이전 판 기대값 실패를 확인한 뒤 취소 130했다. 실제 안내 완료를 준비하고 현재 안내 seq만 관측에서 분리하도록 수정했다. 종료 시 상태 버전과 안정된 사실/종료 참조도 같은 전이 트랜잭션으로 넘기도록 보완했으며 관련 좁은 시험으로 확인 중이다.

이후 SessionVoiceIT 4개·TechnicalPauseTest 8개·OutputRuntimeServiceGuardrailTest 8개·SessionSettleTest 7개는 통과했다. 함께 실행한 startup 6개 중 1개는 첫 Mockito self-attach 경고가 앱 stderr 캡처에 포함되어 실패(명령 종료1)했다. mock-maker를 앱 진단 캡처 전에 초기화하도록 준비 순서를 수정했으며 stderr empty 단언은 유지했다. startup 6개 재실행은 종료0, BUILD SUCCESSFUL 28s였다. 위 제품/종료 관련 27개는 이후 소스 변경이 없고 startup 준비만 수정했다.

다음 Realtime 기능 실행은 ProviderRequestWhitelistIT의 내부 공장 인자 reflection 1개 실패 후 취소130했다. 새 내부 ActivityType/PolicyBundle 인자가 허용되는 정확한 4개 메서드 서명을 추가로 검사했다. 기존 공급자 요청 레코드·실제 wire의 type/필드 whitelist·숨긴 값0건·자유 Map 금지·주소 제한 단언은 보존했다. 해당 IT 6개 재실행은 종료0, BUILD SUCCESSFUL 43s였다.

그 뒤 기능 전체는 소스를 편집하지 않고 종료까지 받았다: 1,067개 중 실패5개, 종료1, 5m12s. DeviceGatewayVoiceSessionIT 재전송1개는 첫 INTRO와 CLOSING opId를 비교하던 관측 문제였고, RealtimeInternalApiIT 2개·RealtimeLogHygieneIT 2개는 공유 HTTP 시작 fixture의 검증 안내 누락이었다. 합성 자원 메타데이터와 일치하는 HTTP fixture 및 현재 CLOSING 선택으로 함께 수정했다. API sinceSeq=0 조회는 현재 안내1행+기존 대화2행의 3개, nextSeq=3, 첫 제공 로그3개 및 partial seq4 경계로 강화했다. 기존 후보 검수·금지/민감 표식0건·실제 wire·시간 단언은 유지했다.

후반 세 클래스 전체 재실행은 종료0, BUILD SUCCESSFUL 1m22s, 23개 실패/오류/skip0이었다. 공유 시작 명령 경로12개를 추가로 읽기 점검했고, 활성화하는 fixture와 시작 검증/실행기 없는 DB fixture를 구별했다.

## 기능 최초 통과 결과 — 135개 시험 클래스

Realtime 승인 기능 명령은 현재 소스로 종료0, BUILD SUCCESSFUL 4m32s였다. JUnit XML 1,067개, 실패/오류/skip0. 그중 신규 *ServiceGuardrail* 시험140개(단위/실제 요청/DB/WS 통합 포함)다. JaCoCo XML LINE covered=4,122, missed=316, 92.88%로 기존80% 하한을 통과했다. 큰 기존 클래스 전체를 포함한 범위다.

```sh
./gradlew --no-daemon :realtime-activity:test :realtime-activity:jacocoTestCoverageVerification -PincludeTags=service-guardrail -PcoverageScope=service-guardrail -Pmujoong.test.db=docker
```

근거: backend/realtime-activity/build/test-results/test/ 및 backend/realtime-activity/build/reports/jacoco/test/jacocoTestReport.xml. 세 모듈 기능 합계1,235개, 모두 실패/오류/skip0. 신규 기능 시험은 Realtime140개+Core9개+contract-model8개=157개다. Core의 기존 관련 회귀151개, Realtime의 기존 관련 회귀927개도 포함한다.

이후 저장소 루트에서 aidlc-state.md의 Construction Verification Command를 그대로 시작했다. 완료 전에는 최종 전체 통과로 취급하지 않는다.

```sh
(cd backend && ./gradlew --no-daemon clean check -Pmujoong.test.db=docker) && (cd frontend && npm run lint && npx vitest run tests --coverage && npm run build)
```

## 전체 명령 첫 실행과 후속 확인

전체 첫 실행은 종료1, BUILD FAILED 8m49s. contract-model330개 실패0(모듈 LINE1,546/1,570=98.47%), Realtime2,012개 중5실패, Core1,734개 중3실패였다. frontend는 backend 뒤 && 조건이므로 미실행이다. 8건 중7건은 기존 V7 고정 기대값으로 승인된 V8를 거부한 migration 회귀였다. 기존 V1–V7 이력·65개 표·앱별 이력 읽기/쓰기 금지·기존 부분 유일 인덱스2개·재적용0건·기존 연습 세션 보존을 유지하고 V8 이력/현재판/적용수 및 새 비유일 사실 부분 인덱스를 추가로 검증하도록 고쳤다. 관련5개 클래스를 기존 태그와 함께 기능 목록에 더했다.

나머지 GraphWorkflowIT는 initializeChild=true 단언 실패였다. 변경/관련성을 단정하지 않고 해당 클래스만 같은 코드·2초 예산·기존 단언으로 실행했다. 결과는 종료0, BUILD SUCCESSFUL 59s, 1개 통과다. 원인은 미확정이며 첫 실패를 제거하거나 PASS로 바꾸지 않는다. 최종 전체 재실행으로 다시 확인한다.

V8 관련 좁은 확인은 RealtimeSchemaIT/PracticeMigrationIT/PracticeMigrationRegistrationTest 전체28개 종료0(1m16s), Core FlywayConfIT/SchemaPrivilegeIT 전체14개 종료0(1m9s)였다. 실패/오류/skip0. 운영 Java34개 범위는 그대로이며 시험 목록140개 전부 존재·태그 포함, 실제 변경 운영 Java와 누락/추가0을 재확인했다. 추가된42개 기존 DB 회귀를 포함해 Realtime/Core 기능 명령을 다시 실행한다. contract-model은 변경이 없어 이미 기록한 기능 PASS 증거를 유지한다.

## 기능 확대 검증 — 140개 시험 클래스

Core 기능 명령 재실행은 종료0, BUILD SUCCESSFUL 2m16s, 174개 실패/오류/skip0. LINE covered=265, missed=12, 95.67%로 하한 통과다.

Realtime 기능 명령 재실행도 종료0, BUILD SUCCESSFUL 5m24s, 1,095개 실패/오류/skip0. LINE covered=4,122, missed=316, 92.88%다. 기존34개 운영 Java 전체 범위·80%를 유지했다. contract-model8개/19줄100% 증거와 합치면 기능1,277개 모두 통과다. 신규157개와 기존 영향 회귀1,120개를 구별한다. 최종 전체 Construction Verification Command를 동일하게 재시작했다.

## 전체 두 번째 실행과 별도 Graph 기술 회귀

전체 두 번째 명령도 종료1, BUILD FAILED 8m23s였다. Realtime2,012개는 모두 통과하고 모듈 커버리지 관문도 통과했다. contract-model 전체도 통과했다. Core1,734개 중 GraphWorkflowIT 초기화1개만 다시 실패했다. frontend는 && 조건 때문에 미실행이다. 같은 전체 명령을 반복하지 않고 원인을 진단했다.

시험 전용 투명 HTTP 관측과 실제 ledger 호출 관측을 추가했다. 원문·URI·인증·자녀 식별자는 기록하지 않고 메서드/HTTP 상태/예외 class/경과시간/허용 Neo4j 이유코드만 기록한다. 실제 Workflow 단독 실행에서 초기화 false, 전체2,152ms, clockAdvanced=false, JDBC 풀 active0/idle1/awaiting0이었다. 기존 replaceBase 세 HTTP 요청은 각각1,453/294/269ms에 성공했지만 다음 ledger.successful이 TransactionTimedOutException을 던졌다. HTTP 개별 시간 초과나 풀 대기가 아니라 기존 JDBC 2초 트랜잭션 예산 안의 세 왕복 누적이 원인이었다. 이 수정은 원래 U16 정책 교체와 별개인 전체 검증 기술 회귀 해소다.

처음 시도한 OPTIONAL MATCH/collect/FOREACH 단일 Cypher는 관련19개 중18개 통과했으나 Workflow의 새 Neo4j 첫 replaceBase 요청 자체가2,003ms에서 HttpTimeoutException이었다(전체2,121ms). runtime=slotted 지정도 동일하게2,003ms에서 실패했다(전체2,191ms). 워밍업·시간 증가·단언 완화는 사용하지 않았다. 두 실행은 각각 BUILD FAILED 1m16s/1m17s이며 완료/통과 근거가 아니다.

후속 구현은 단순한 기존 계열 세 문장을 같은 명시적 트랜잭션의 단일 HTTP statements 배열로 묶는다. 첫 문장은 version/digest 확인이고, 두 쓰기는 각각 version+digest MATCH를 통과해야 실행한다. 따라서 충돌 뒤 호출자가 커밋해도 기존 씨앗에 부분 쓰기가 없다. 자녀 범위·POC 전용 교체·generated 보존·파라미터 분리·재시도 없음·rollback/CAS 및 기존2초 예산을 유지한다. 새 HTTP 회귀8개와 기존 실제 Neo4j10개/Workflow1개로 확인 중이다. 변경 운영 Java 범위는35개 전체, 태그 시험 목록은143개로 확장했다. 이전 기능 통과 수치를 이 확장 범위의 통과로 재사용하지 않는다.

단일 HTTP 배치 수정 후 관련19개는 종료0, BUILD SUCCESSFUL 1m25s, 실패/오류/skip0이었다. 새 Neo4j의 실제 Workflow 초기화 true, replaceBase HTTP200/1,914ms, commit HTTP200/32ms, ledger 예외0, 시계 변경/풀 대기0을 확인했다. 전체 초기화 벽시계는2,222ms였다. 기존 개별 HTTP/JDBC 설정2초를 유지했다는 사실과 전체 벽시계2초 보장은 구별하며 후자는 주장하지 않는다. 워밍업·시간/단언 완화 없이 통과했다. 실제 Neo4j 시험은 충돌 후 명시적 commit에도 기존 씨앗 보존, rollback, 다른 자녀와 generated 씨앗 보존을 확인했다. 현재 소스로 Core 기능 전체/35개 범위 중 Core 해당 클래스 전체의 줄80%를 확인한다.

확장 Core 기능193개 실행은 종료1, BUILD FAILED 2m39s, 실패1개였다. Workflow만 첫 replaceBase 요청2,001ms에서 HttpTimeoutException으로 실패했다(전체2,054ms, ledger 예외0/시계 변경false/풀 대기0). 커버리지 관문에 도달하지 않았으며 생성된 보고서를 범위 통과로 해석하지 않는다. 단독19개 PASS로 이 실패를 덮지 않았다.

이후 중복 version MATCH와 첫 실행 문장 비용을 줄이기 위해 첫 MERGE에 digest 조건의 OPTIONAL MATCH/POC 삭제를 합쳐 같은 명시적 트랜잭션의 단일 HTTP 두 문장으로 단순화했다. 첫 문장은 digest가 다르면 삭제 대상이 null인 채 기존 digest를 반환하고, 재적재 문장도 version+digest MATCH를 통과해야 한다. 충돌 뒤 commit·rollback·다른 자녀/generated 보존·파라미터 경계와 현재 소스 기능 전체를 다시 확인한다. 시간 설정·단언·워밍업은 변경하지 않는다.

두 문장 단순화의 관련19개도 실패1개로 종료1, BUILD FAILED 1m36s였다. 첫 replaceBase HTTP2,001ms timeout/전체2,146ms였으므로 채택하지 않았다. 운영 prepareSchema에 두 문장의 EXPLAIN만 준비하는 시도도 첫 계획 준비 자체가 기존 HTTP2초에서 실패했다(종료1/1m30s). 이 두 문장/거대 Cypher/런타임 힌트는 최종 코드에 남기지 않았다.

후속 후보는 원래 계열의 단순 세 쿼리 상수를 운영 prepareSchema에서 제약 commit 후 EXPLAIN으로 각각 준비하고, 실제 교체는 같은 세 쿼리를 단일 HTTP로 실행한다. 운영 KnowledgeConfiguration도 같은 prepareSchema를 호출한다. 합성 파라미터만 사용하고 실제 사용자 데이터 쓰기·자동 재시도는 없다. 계획 준비 실패는 시작 실패로 전파하며 모든 준비가 성공해야 schemaReady=true다. 실제 Neo4j5.26.31에서 합성 child가 비고 합성 version digest가 고정되지 않음을 검사한다. wire에는 정확히 같은 쿼리의 EXPLAIN만 실행·준비1회·실패 후 ready 미개방/자동 재시도0을 추가했다. 새 시험 도우미 제네릭 컴파일 오류1개(종료1/16s)는 명시적 타입 인자로 수정했다. 현재 소스의22개 관련 시험을 실행 중이다.

근거 문서: [Neo4j5 쿼리 튜닝](https://neo4j.com/docs/cypher-manual/5/planning-and-tuning/query-tuning/), [실행 계획/EXPLAIN](https://neo4j.com/docs/cypher-manual/25/planning-and-tuning/execution-plans/). 문서 설명을 실제5.26.31 성공으로 대체하지 않고 위 실제 시험 결과로 채택 여부를 판단한다.

운영 EXPLAIN 준비/단일 HTTP 세 문장 후보의 관련22개는 종료0, BUILD SUCCESSFUL 1m21s, 실패/오류/skip0이었다. 실제5.26.31 cold 계획 준비 HTTP200은 각각1,215/406/299ms로 기존 개별2초 안에 완료됐다. 그 뒤 초기화 true, 전체763ms, replaceBase HTTP200/582ms, commit HTTP200/30ms였고 ledger 예외0·풀 대기0·시계 변경false였다. 동일 쿼리 계획 준비가 실제 경로의 첫 실행 비용을 줄였음을 관측했다. 실제 synthetic child 조회0행과 synthetic catalog의 다른 digest 적용/rollback 성공으로 준비가 씨앗을 쓰거나 version을 고정하지 않았음을 검증했다. wire는 운영 준비의 CREATE CONSTRAINT/EXPLAIN 외 데이터 쓰기0·정확한 세 쿼리 일치·중복 준비0·실패 후 ready 미개방/자동 재시도0을 확인했다. 기존 충돌 후 commit·rollback·타 자녀·generated 보존과 기존2초/단언을 유지했다. 이 제품 준비 경로를 포함한 현재 소스로 Core 기능 전체를 다시 실행한다.

## 현재 최종 기능 결과 — 35개 운영 클래스/143개 시험 클래스

Core 확장 기능 명령은 종료0, BUILD SUCCESSFUL 2m14s,196개 실패/오류/skip0이었다. JaCoCo XML LINE covered351/missed18=95.12%로 기존80%를 통과했다. 확대 실행의 cold 계획 준비1,199/487/287ms, 실제 초기화 true/전체562ms, replaceBase HTTP200/491ms, commit HTTP200/26ms, ledger 예외0·풀 대기0·시계 변경false였다.

Realtime 변경이 없는 최종 기능1,095개/LINE4,122 covered·316 missed=92.88%, contract-model8개/19 covered·0 missed=100%와 합쳐 기능1,299개 실패/오류/skip0이다. 신규 U16 정책/조회 시험157개와 별도 기술 회귀 신규11개(HTTP10개·실제 무쓰기 준비1개), 기존 관련 회귀1,131개를 구별한다. 실제 변경 운영 Java35개와 범위35개가 일치하고 시험 목록143개 전부 존재·태그 포함이다. 전체 명령 직전 시험 관측 도우미 설명 주석만 한국어로 맞췄으며 동작 변경은 없다. 이후 앱 소스를 동결하고 같은 Construction Verification Command 전체를 시작했다.

## 최종 전체 결과

동일한 Construction Verification Command는 종료0이었다. backend clean check는 BUILD SUCCESSFUL 8m19s로 전체 모듈80%·계약·ArchUnit을 통과했다. contract-model330개/LINE1,546 covered·24 missed=98.47%, Core1,745개/LINE10,100 covered·396 missed=96.23%, Realtime2,012개/LINE14,879 covered·988 missed=93.77%였다. 운영3모듈4,087개는 실패/오류/skip0이다. test-support31개 실패/오류0, 기존 LOCAL 전용3개 조건부 skip은 Docker 선택에 따른 것이며 새 skip은 추가하지 않았다.

Graph의 최종 전체 관측은 초기화 true/전체672ms, cold 계획 HTTP200 각1,264/493/300ms, replaceBase HTTP200/588ms, commit HTTP200/49ms였다. ledger 예외0, clockAdvanced=false, JDBC 풀 active0/idle3/awaiting0으로 실제 확대 경로에서도 기존2초 설정을 유지하며 완료했다.

frontend는 lint PASS, Vitest75개 파일998개 PASS(61.06s), LINE3,455/3,506=98.54%로 관문 통과, Next.js production build PASS였다. 신규 유료 API/실기기·실아동 호출은 없으며 해당 성공을 주장하지 않는다. 앱203개 파일의 경로/내용 SHA256 지문은 최종 명령 시작/종료 모두 b1d55ec3255b220c0dc563cba69e8f3676ffc4ffacc3f402e6ba2d24a03229cf로 일치했다. 이후 앱 변경 없이 Step5 기록만 작성했다. git diff --check -- backend contracts infra도 종료0이다.


## Loop-back 1 — 기준선, 실패와 층별 회귀

기존 절들의 최초 구현 결과는 보존한다. 이번 작업은 승인된 Repair1–8이며 부모가 Build and Test에서 관측한 ParticipationLateItemServiceGuardrailTest 2개 실패(B-R01)를 입력으로 삼았다. 이를 이번 작업에서 수정 전 새로 재현했다고 표시하지 않는다. 실제 wire는 null itemId와 start_ms/end_ms이며, 초기 2개는 legacy 내부 ID 대역이다.

참여 층에는 기존2개를 그대로 유지하고 null-ID 지연/이미 quiet 된 입력/미관측 지연/시간·식별 근거 없음/정상 새 발화/재안내를 보강했다. 같은8개 안에서 안전 편지함의 완료 표식이 일반 전사를 앞지르는 경우 및 완료 후 연결 교체도 검사한다. 현재 수락 뒤 ACTIVE와 원음 시작1회, 잘못된 수락/첫 응답0회 경계를 확인한다.

RG1 8개는 실제 실행기·기기 링크 대역을 사용한다. 현재 완료→첫 실제 요청1회, 미완료/STARTED/실패/다른 연결, 재시작 인사/자기소개·등록 대본/마지막 참여 질문 반복의 음성0, JEV ALLOW/원문 생략 우회 불가, 정상 실질 답·인용·명시적 이름/인사 요청, 실제 프레임 없는 완료/취소/실패/만료 뒤 늦은 완료, 종료·기기 교체 뒤 늦은 ALLOW, 실제 현재 재생 완료 이후/연습의 분리를 포함한다. 단순 프롬프트 문자열 검사만으로 합격을 판단하지 않는다.

### 시도 기록

| 실행 | 관측/처리 |
|---|---|
| 참여16개 최초 보강 실행 | 1개 실패. 내부 합성 Done의 같은 join ID 재사용을 과거 delta ID와 혼동한 구현을 보정했다. 기존 단언은 유지했다. |
| 참여16개 재실행 | 종료0, 27초. 이후 현재 연결 검사 보강 재실행도 종료0, 31초. |
| RG1+기존 지시/요청23개 최초 실행 | 5개 실패. 합성 복합 참여 답의 기존 J1 경로와 생성 전 주입해야 하는 대역 구성을 바로잡았다. 기존 업무 분류를 넓혀 통과시키지 않았다. |
| RG1+기존 지시/요청 재실행 | 종료0, 23초. |
| localhost 요청 IT 포함24개 | 종료0, 27초. |
| FAILED/STOPPED/만료 수명 보강24개 | 종료0, 45초. |
| 전체 clean check 최초 sandbox 실행 | Gradle 캐시 쓰기 권한에서 종료1. 앱 시험이 시작되지 않은 환경 제약이다. 정상 권한 재실행 뒤 수명 검사를 보강하기 위해 시험 전 종료130으로 중단했다. |
| 다음 전체 clean check | 구소스의 구간 분리 인사 fixture1개, nullable-ID 무시간 대역1개 및 정상0ms 첫 입력을 보류한 통합 사례가 관측됐다. 전체가 끝나기 전 종료130으로 중단했다. 이 시도를 PASS/최신소스 결과로 표시하지 않는다. |
| 최신 전체 check 최초 sandbox 실행 | 같은 Gradle 캐시 쓰기 제약으로 종료1. 정상 권한으로 다시 실행했다. |

기존 OutputGate 구간 분리 사례는 후보만 ‘안녕/반가워’에서 ‘그래/좋아’로 바꾸었다. 출력2개/승인행2개/빈폐기0·800ms 단언은 보존했다. GenerationSendExecutorTest의 참여 null-ID 조각에는 실제 wire의 시간선1/1을 보충했으며 ‘quiet 전 WAITING_CHILD→quiet 뒤 ACTIVE’ 단언은 그대로다. 시간·식별이 모두 없는 입력은 새로운 부정 사례에서 계속 fail-closed다. 정상 시간선0에서는 과거 입력이 없는 시작과 이미 관측한 과거0ms 끝을 분리한다. 기존 네 요청 type/예산/80%/대조·ArchUnit/실호출 격리를 바꾸지 않았다.

### 최신 관련층 묶음

backend에서 다음 정확한 명령을 실행했다.

```bash
./gradlew --no-daemon :realtime-activity:test --tests kr.mujoong.realtime.session.ParticipationLateItemServiceGuardrailTest --tests kr.mujoong.realtime.session.ParticipationServiceGuardrailTest --tests kr.mujoong.realtime.session.GreetingHandoffServiceGuardrailTest --tests kr.mujoong.realtime.voice.ProviderRequestServiceGuardrailIT --tests kr.mujoong.realtime.session.GenerationSendExecutorTest --tests kr.mujoong.realtime.gate.OutputGateTest --tests kr.mujoong.realtime.observability.RealtimeMetricsScrapeIT --tests kr.mujoong.realtime.session.GuardrailTurnIT -Pmujoong.test.db=docker
```

종료0, BUILD SUCCESSFUL 1m26s. 8클래스75개, 실패/오류/skip0이다: 참여16, RG1 8, localhost ProviderClient wire IT2, Generation21, OutputGate17, 지표통합7, JEV통합4. localhost wire IT에는 실제 디코딩된 null ID/시간선의 늦은 과거 입력→WAITING_CHILD 및 새 입력→ACTIVE/원음 시작1회, 실제 FIRST wire의 녹음 완료/재시작 금지 전달을 포함했다. 각 구간은 합성 대역이며 실제 모델·기기·아동 성공이 아니다.

이 결과 이후 앱207개 claim을 고정했다. 경로를 사전식 정렬하여 각 줄을 ‘경로 + NUL + 파일 UTF-8가 아닌 실제 바이트 SHA256 + LF’로 만든 listing의 SHA256은 `73a4eec55addd2185c8284d0938f16ea662e7f0f9ed2c5b94e8d69adf17af184`다. scope37개·시험목록145개·claim207개는 모두 실제 파일이며 기존35/143/203 분모를 보존한다. 전체/기능 최종 결과는 다음 절에서 이 지문과 함께 기록한다.

### 전체 회귀 실패와 정확 필터 재현

최신 전체 `./gradlew --no-daemon check -Pmujoong.test.db=docker`는 PASS가 아니다. Realtime은2,029개 중5개 실패로 test task가 끝났고, Core는 Neo4jIT/GraphWorkflowIT 준비 실패가 관측된 채 계속 실행 중이었다. 이미 기존 인사 fixture4개가 변경돼 최신 소스의 최종 증거로 쓸 수 없고 알려진 실패 재현을 우선하기 위해9m33s 시점 본인 Gradle 세션만 종료130으로 중단했다. Core의 현재 전체 시험 수/커버리지는 확정하지 않는다. contract-model330개는 실패/오류/skip0이었지만 이를 전체 backend 합격으로 표시하지 않는다.

Realtime 실패5개는 다음과 같다. 원음 동의/지연 시험4개는 새 RG1이 의도대로 첫 인사 후보를 차단한 fixture 충돌이며 후보만 교체하고 기존 단언을 보존했다. Lifecycle의 DB 정리 실패는 같은 제품 결함으로 단정하지 않는다.

| 정확 메서드 | 최초 전체 관측 | 후속 |
|---|---|---|
| SessionLifecycleIT.aMissingAckFailsWithDeviceNoAckAfterTheDeviceAckWindow | close→RealtimeDb.wipe의 session 삭제가 result_input_snapshot FK에 막힘 | 동일 메서드 단독은 PASS. 간헐 정리 실패이며 해결 확정 아님 |
| SessionVoiceIT.rawAudioIsWrittenOnlyForAConsentedSessionAndItsMetadataIsFinishedAtTheEnd | 첫 후보 ‘반가워 하늘아’ 차단 뒤 OUTPUT_BEGIN 대기 실패 | 일반 응답 후보로 교체; PCM/byte수·동의/종료 단언 보존 |
| SessionVoiceTest.withoutRawAudioConsentNothingIsRecorded | 첫 후보 ‘안녕 반가워’ 차단 | 후보만 교체; 무동의 기록0 단언 보존 |
| SessionVoiceTest.withConsentChildFramesAndOnlyApprovedAiAudioAreRecordedUntilTheSessionEnds | 같은 첫 후보 차단 | 후보만 교체; 동의/전달/종료·byte수 단언 보존 |
| DeviceGatewayIT.theHeartbeatSentAtGivesAClockOffsetWithinHalfTheRoundTripAndWithoutItTheTurnIsMarkedMissing | 같은 첫 후보 차단 뒤 OUTPUT_BEGIN 대기 실패 | 후보만 교체; offset/RTT/CLOCK_OFFSET_MISSING 단언 보존 |

위5개 메서드와 GreetingHandoffServiceGuardrailTest8개를 정확 필터로 함께 재현한 최신 결과는13개 실패/오류/skip0, 종료0, BUILD SUCCESSFUL 1m35s다. 교체된 실제 기기 목적지의 늦은 ALLOW 출력/프레임0도 추가로 단언했다. session/gate/web/observability의 첫 합성 인사 출력에 한정해 대조했으며, 순수 버퍼/스냅샷 자료·참여 전 차단·등록 종료 대본·RG1 예외 사례는 보존했다.

Core 단독 재현 명령은 다음과 같았다.

```bash
./gradlew --no-daemon :core-api:test --tests kr.mujoong.core.knowledge.Neo4jIT --tests kr.mujoong.core.practicegoal.GraphWorkflowIT -Pmujoong.test.db=docker
```

2개 모두 실패, 종료1/2m13s다. Neo4jIT는 BeforeAll 실패로 initializationError1건만 보고되므로 그 클래스의 나머지 시험을 통과로 세지 않는다. 운영 request()는 원래 IOException/IllegalArgumentException 원인 chain을 요약 예외로 바꾸므로 ‘Graph operation unresolved’만으로 HTTP 원인을 단정하지 않았다.

기존 GraphHttpDiagnostics는 연산명/상태/오류 종류/경과/허용 Neo4j 오류 코드만 보관하며 URI·헤더·본문·예외 메시지를 내보내지 않는다. GraphWorkflowIT의 prepareSchema 실패에서도 이 기존 관측기를 출력한 뒤 같은 예외를 다시 던지도록 시험 진단만 보강했다. 다음 단독 재현도1개 실패, 종료1/1m41s였다.

```bash
./gradlew --no-daemon :core-api:test --tests kr.mujoong.core.practicegoal.GraphWorkflowIT -Pmujoong.test.db=docker
```

| 실제 시작 준비 연산 | HTTP 상태 | 경과ms | 오류 |
|---|---:|---:|---|
| begin | 201 | 657 | 없음 |
| 첫 제약 준비 | 200 | 1544 | 없음 |
| 두 번째 제약 준비 | 200 | 180 | 없음 |
| commit | 200 | 107 | 없음 |
| 계획 준비 begin | 201 | 26 | 없음 |
| 첫 EXPLAIN BASE_VERSION (운영53행) | 0(응답 없음) | 2015 | HttpTimeoutException |
| 계획 준비 terminate | 200 | 310 | 없음 |
| 이미 끝난 트랜잭션 terminate | 404 | 35 | Neo.ClientError.Transaction.TransactionNotFound |

인증/초기 연결 실패나 Neo4j 업무 오류 응답이 아니라 cold 계획 준비의 기존2초 HTTP 예산 초과가 확인됐다. 마지막404는 정리 중 이미 끝난 트랜잭션이고 원래 EXPLAIN 실패의 원인이 아니다. 단독에서도 재현돼 전체 병렬 부하만의 문제로 표시하지 않는다. 운영 Graph/2초 예산/CAS/시간·하한·단언은 이번 Repair에서 변경하지 않았다. 같은 예산 아래 계획 비용/실제 시작 준비 경로의 복구 조사·구현은30–60분+단독/전체 회귀, 초기화·원자성에 중간 위험, 추가 유료 비용0으로 추정하며 환경 정리만으로 해결된다고 약속하지 않는다. 부모가 다음 복구 경로를 처리한다.

추가 인사 fixture4개·교체 목적지0프레임 단언·안전 Graph 실패 진단을 포함한 최신207claim listing SHA256은 `2133a7be2d2ef072e40af13ad78fa069a87007a5cb140ae2adfdb6a0ca7fccae`다. 운영 제품 로직은 위75개 통과 이후 변경하지 않았고, 이 새 지문은 시험 파일 변경도 포함한다. 전체 검증/호환 회귀의 NFR3/NFR3.1/NFR6/NFR6.1은 GAP이며 독립적인 복구·재검증이 필요하다.

### 최신 동결 소스의 기능 범위 검증

Realtime 기능 명령은 다음과 같다.

```bash
./gradlew --no-daemon :realtime-activity:test :realtime-activity:jacocoTestCoverageVerification -PincludeTags=service-guardrail -PcoverageScope=service-guardrail -Pmujoong.test.db=docker
```

종료0, BUILD SUCCESSFUL 7m48s. 실제 새 JUnit XML120개에서1,112개·실패/오류/skip0을 확인했다. JaCoCo report 최상위 LINE은 covered4,229/missed328, 92.80%이며 기능80% verification task도 통과했다. 위75개·13개 정확 필터와 겹치는 사례이므로 합산하지 않는다. 이 기능 실행은 전체 backend/ArchUnit/호환 관문을 대체하지 않는다.

contract-model 명령도 종료0, BUILD SUCCESSFUL 42s였다. JUnit XML1개/시험8개·실패/오류/skip0, LINE covered19/missed0=100%, 기존 기능80% 관문 PASS다.

```bash
./gradlew --no-daemon :contract-model:test :contract-model:jacocoTestCoverageVerification -PincludeTags=service-guardrail -PcoverageScope=service-guardrail
```

Core 기능 명령은 종료1, BUILD FAILED 4m10s였다.

```bash
./gradlew --no-daemon :core-api:test :core-api:jacocoTestCoverageVerification -PincludeTags=service-guardrail -PcoverageScope=service-guardrail -Pmujoong.test.db=docker
```

JUnit XML24개/시험186개 중184개 통과·실패2개·오류/skip0이다. 실패는 Neo4jIT.initializationError와 GraphWorkflowIT.realGraphSeedToU14CandidateToU10ApprovedVariantAndScopedDeletion이다. Neo4jIT의 기존11개 메서드는 BeforeAll 실패로 실행되지 않고 initializationError1건으로 대체됐으므로 원래196개 분모의 통과로 세지 않는다. Core 기능 coverage verification/report task는 test 실패 때문에 실행되지 않았고 최신 XML report도 없다. 이전95.12%를 이번 결과로 재사용하지 않는다.

최신 Graph 안전 관측에서도 begin201/566ms, 제약200/1,843ms 및200/159ms, commit200/100ms, 계획 begin201/20ms 다음 첫 prepareReplacementPlan이 status0/HttpTimeoutException/2,008ms로 실패했다. 후속 terminate200/309ms와 이미 종료된 트랜잭션의404/37ms는 정리 관측이다. 일반 요청2초와 운영 Graph 구현은 이 지문에서 변경하지 않았다.

| 변경 전 최종 기능 결과 | 보고 시험 | 실패/오류/skip | 기능 LINE | 관문 |
|---|---:|---|---|---|
| realtime-activity | 1,112 | 0/0/0 | 4,229 covered / 328 missed (92.80%) | PASS |
| contract-model | 8 | 0/0/0 | 19 covered / 0 missed (100%) | PASS |
| core-api | 186 (원래196개 중 BeforeAll 미실행 포함) | 2/0/0 | 최신 report 없음 | FAIL, coverage 미실행 |

207claim/37scope/145시험목록은 고유하고 전부 실파일이며 scope/시험목록 전체가 claim에 포함된다. traceability123행과 upstream ID 집합 일치,117 OK의 단일 target 존재,4 GAP/2 Deferred를 검증했다. 앱/시험 최종 지문은 앞의 `2133a7be2d2ef072e40af13ad78fa069a87007a5cb140ae2adfdb6a0ca7fccae`로 유지됐다. `git diff --check -- backend contracts infra` 및 현재 Step5 자료 범위도 종료0이다. Repair1–7 기록과 Step5 자료는 인계하되 Repair8 전체 관문은 미완료다. 전체 backend/호환/ArchUnit 또는 실제 음성·브라우저 통과를 주장하지 않는다.

이 결과 뒤 사용자가 ‘일반 요청2초 유지, 시작 초기화에 별도 제한 시간 적용’을 선택했다. 이 절은 그 변경 전 증거로 동결하며, 새 brief/Testing Contract를 부모가 발행하기 전에 운영 Graph를 수정하거나 전체 검증을 다시 실행하지 않는다. 다음 수정의 성공으로 현재 실패 이력을 덮어쓰지 않는다.

## Startup Initialization Budget — Repair9–13

2026-10-21 사용자가 초기화 전용 제한 시간 분리를 선택한 뒤 부모가 현재 계획/지시서와 tool brief를 갱신했다. Testing Contract는 기존 `sha256:231770ff8fc8e342a5163711626da88820010bbc0b413c96ec9fc94bb2241ce4`, test-after다. 기본10초는 초기 구현값이며 사용자가 숫자10을 선택했다고 해석하지 않는다. 앞 절의 실패/취소/지문2133… 이력은 변경 전 증거로 보존한다.

설정 층을 먼저 구현하고 TimingPropertiesTest/ConfigKeyContractTest/CorePropertiesValidationTest 명령을 실행했다. 종료0/41s,3클래스27개·실패/오류/skip0이었다. 일반2초와 초기화10초 기본/override, 초기화 null/0/음수 거부와 실제 Spring 빈값/잘못된 형식 기동 실패, 시간표/키/레코드 대조를 포함했다. 서버 전용 키라 프런트 시간 값에는 복제하지 않았다.

다음 업무 층에서 운영 구성에 초기화 budget/기존 MujoongClock을 주입했다. prepareSchema의 제약 begin/2제약/commit·계획 begin/3 EXPLAIN/계획 종료에 하나의 deadline을 사용하고 각 HTTP timeout은 남은 시간이다. 모든 단계와 최종 마감 검사가 성공한 뒤에만 schemaReady를 연다. 정상 plan 종료도 이 마감 안에 포함한다. 실패 시 알려진 미종료 트랜잭션 하나만 기존 일반 graph-budget(기본2초)의 별도 전체 정리 한도 안에서 정리한다. 원래 실패를 유지하고 정리 실패는 suppressed로 붙인다. 중단 플래그는 유한 정리 동안만 해제하고 복구한다. 성공한 제약 commit 뒤 닫힌 핸들을 다시 정리하지 않는다. 일반 업무 요청·연결 timeout/JDBC2초, 3문장 교체·digest/CAS·자녀 범위·무쓰기 EXPLAIN·자동 재시도0은 유지했다.

첫 업무 좁은 묶음은24개 중1개 실패였다. 새 빈 조회 대역이 begin 응답의 commit 필드까지 제거한 fixture 오류였으며 제품 deadline 실패로 표시하지 않는다. 빈 결과에서도 commit 필드를 유지하도록 fixture만 고치고 일반 요청10회/모두2초 단언을 보존했다.

이후 설정/업무/기존 HTTP/구성/시간 숫자 대조를 함께 실행했다.

```bash
./gradlew --no-daemon :core-api:test --tests kr.mujoong.core.knowledge.GraphInitializationBudgetServiceGuardrailTest --tests kr.mujoong.core.knowledge.GraphInitializationTechnicalRegressionServiceGuardrailTest --tests kr.mujoong.core.knowledge.ConfigurationTest --tests kr.mujoong.core.config.TimingPropertiesTest --tests kr.mujoong.core.config.ConfigKeyContractTest --tests kr.mujoong.core.startup.CorePropertiesValidationTest --tests kr.mujoong.core.arch.TimeLiteralScanTest -Pmujoong.test.db=docker
```

종료0/45s,7클래스58개·실패/오류/skip0이다. 새 업무8개, 기존 HTTP10개, 구성6개, 설정/계약/기동27개, 시간 숫자 대조7개다. 새8개는 실제localhost에서 의도적으로2.3초 지연한 첫 EXPLAIN의 성공과 이후 일반 begin의 실제2초 timeout, 가변 시계/HTTP 대역의 요청별 잔여10→9→…→2초, 누적 마감·정확 마감 뒤 ready 미개방, transport timeout·중단/복구/정리2초, 정리 실패가 원래 실패를 덮지 않음, 일반 읽기/쓰기/begin/commit/terminate 전부2초를 검사한다. 무쓰기/정확한 세 EXPLAIN·중복 준비0·실패 후 자동 재시도0·동일 요청/CAS는 기존 HTTP10개를 유지했다. 대역 성공은 실제 Neo4j/모델 성공과 구별한다.

### 실제 cold Neo4j 회귀

```bash
./gradlew --no-daemon :core-api:test --tests kr.mujoong.core.knowledge.GraphInitializationTechnicalRegressionServiceGuardrailTest --tests kr.mujoong.core.knowledge.Neo4jIT --tests kr.mujoong.core.practicegoal.GraphWorkflowIT -Pmujoong.test.db=docker
```

종료0/1m33s,3클래스22개·실패/오류/skip0이다. 기존 실패한 Neo4jIT는 이번에는 BeforeAll을 통과해11개 메서드가 모두 실행됐고 GraphWorkflowIT도 통과했다. 실제 Neo4j5.26.31의 무쓰기 준비·synthetic child0행·synthetic version 미고정·충돌 후 caller commit/CAS·타아이/생성 seed·rollback/삭제·후속14+1 seed→후보→연습2장면 합성 연결을 유지했다. AI는 가짜 공급자다.

GraphWorkflow 안전 관측에서 제약 HTTP200=702/82ms, cold EXPLAIN HTTP200=1,155/422/295ms였다. 업무 initializeChild는 true/609ms, replaceBase HTTP200/494ms, commit HTTP200/24ms로 기존 일반/JDBC2초를 유지했다. ledger 실패0·풀 대기0·시계 변경false다. 개별 네트워크 시간의 합계를 전체 시작 wall-time으로 표시하지 않는다. 앞의 localhost2.3초 지연 시험과 실제 cold DB 관측을 별도로 보존한다.

cold 검증 뒤 앱/시험을 동결했다. 기존207claim을 보존해216claim, 기존37운영scope를 보존해39개(새 CoreTimingProperties/KnowledgeConfiguration), 기존145시험목록을 보존해150개다. 추가9claim에는 Core 설정/운영 구성·application.yml·설정/구성/기동/초기화 시험·timing 계약이 포함된다. 전부 실파일이며 scope/시험목록의 모든 경로가 claim에 포함된다. 정렬한 경로+NUL+각파일바이트SHA256+LF listing의 새 SHA256은 `7a6aaae11d59cf87f32f711dd1df70dbb20c3a48b93df8f592a8c1d71abf41c4`다.

후속 지문 읽기 명령1회가 AI-DLC PreToolUse의 directive kind ask/approval target 불일치로 차단됐다. 부모가 정상 next→continue 경로에서 approved run-stage를 복구한 뒤 같은 읽기 명령이 성공했다. 훅/프레임워크 파일을 바꾸거나 우회하지 않았다. 진행 중 cold 결과는 별도로 종료0을 보존했다.

### 동결 소스의 최종 기능 결과

Core 기능 명령은 종료0/2m23s,29클래스237개·실패/오류/skip0이었다. JaCoCo 최상위 LINE covered484/missed16=96.80%, 기존 기능80% verification task PASS다. 원래 실패한 Neo4jIT11개도 모두 실제 실행됐다.

```bash
./gradlew --no-daemon :core-api:test :core-api:jacocoTestCoverageVerification -PincludeTags=service-guardrail -PcoverageScope=service-guardrail -Pmujoong.test.db=docker
```

확대 기능의 cold EXPLAIN은 HTTP200/1,177·490·329ms, 업무 initializeChild true/655ms, replaceBase200/579ms, commit200/30ms였다. ledger 실패0·풀 대기0·시계 변경false다. 추가 초기화 시험이 실제 준비 경로를 없애거나 일반2초를 늘리지 않았다.

| 기능 범위 | 시험 | 실패/오류/skip | LINE covered/missed | 결과 |
|---|---:|---|---|---|
| Core(새 소스·확대 scope) | 237 | 0/0/0 | 484/16 (96.80%) | PASS |
| Realtime(동일 소스의 직전 확정 실행) | 1,112 | 0/0/0 | 4,229/328 (92.80%) | PASS |
| contract-model(동일 소스의 직전 확정 실행) | 8 | 0/0/0 | 19/0 (100%) | PASS |

기능 합계1,357개다. RT/contract의 운영/시험/scope는 앞의 성공 실행 이후 그대로이므로 해당 증거만 재사용했다. 좁은58개/cold22개/이전75개·13개를 겹쳐 합산하지 않는다. 기능 report는 이후 whole clean check로 덮어써지므로 이 표는 Core 기능 종료 직후 XML에서 보존했다. 소스 지문7a6a…를 유지한 채 다음 최신 backend 전체 명령1회를 시작했다.

```bash
./gradlew --no-daemon clean check -Pmujoong.test.db=docker
```

### 최신 backend 전체 결과와 최종 동결

위 최신 clean check는 종료0, BUILD SUCCESSFUL 8m26s였다. 기존 줄80%/계약/ArchUnit/실호출 격리 검사를 그대로 통과했다. 모듈 최상위 JaCoCo LINE과 JUnit XML을 종료 직후 확인했다.

| 모듈 전체 | XML 클래스 | 시험 | 실패/오류/skip | LINE covered/missed | 결과 |
|---|---:|---:|---|---|---|
| contract-model | 19 | 330 | 0/0/0 | 1,547/23 (98.54%) | PASS |
| core-api | 227 | 1,759 | 0/0/0 | 10,134/393 (96.27%) | PASS |
| realtime-activity | 228 | 2,029 | 0/0/0 | 14,984/1,002 (93.73%) | PASS |
| test-support(커버리지 하한 대상 아님) | 5 | 31 | 0/0/3 | 참고용이며 관문 수치로 사용하지 않음 | 기존LOCAL3개 조건부skip |

운영3모듈 합계4,118개는 실패/오류/skip0이다. test-support3개는 기존 Docker 선택의 LOCAL 전용 skip으로 새로 추가하지 않았다. 이전 인사 fixture4개 및 Lifecycle teardown FK 실패는 이번 전체에서 재현되지 않았다. 후자는 영구 정리 원인을 수정했다고 표현하지 않는다. 변경 전 전체실패/취소와 단독cold2015/2008ms timeout은 위 이력에 그대로 보존했다.

최신 전체의 Graph 안전 관측은 제약 HTTP200/761·75ms, cold EXPLAIN HTTP200/1,217·410·225ms, 업무 initializeChild true/543ms, replaceBase200/476ms, commit200/24ms였다. ledger 실패0·풀 대기0·시계 변경false다. 일반 업무2초와 초기화 전용10초의 차이, 별도 실패 정리2초는 앞의 계약/대역 경계로 검증했고, 실제DB 수치는 별도로 기록했다. 임의 sleep/워밍업/자동 재시도·마감 해제·단언/분모 축소로 통과시키지 않았다.

최종 source-manifest216개 지문은 whole 시작/종료 모두 `7a6aaae11d59cf87f32f711dd1df70dbb20c3a48b93df8f592a8c1d71abf41c4`로 일치했다. 운영scope39/시험목록150은 전부 고유·실파일·claim 포함이다. traceability123행과 upstream ID 집합 일치 및 모든 OK target의 존재를 확인했다. 최신 결과에 따라 NFR3.1/NFR6.1/NFR6은 OK로 복구했다. 상위NFR3·NFR3.2(전체verify)와 NFR4.3(실제음성)은 Deferred로 유지하여120 OK/0 GAP/3 Deferred다.

Repair8/9–13은 허용된 구현·검증·Step5 자료를 마무리해 체크한다. 이는 전체verify.sh·U17 frontend/실브라우저 통합 또는 실제음성 제작/청취/등록을 완료했다는 뜻이 아니다. 이 후속 항목과 독립검토/생명주기는 부모가 정상 경로로 처리한다. 이후 앱과 검토대상 문서를 동결하며 검토/terminal/commit/push 명령은 실행하지 않았다.
