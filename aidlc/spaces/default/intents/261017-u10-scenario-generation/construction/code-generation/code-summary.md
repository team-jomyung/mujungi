# U10 코드 생성 결과

## 구현

기준 main은 `48a132b8ebe5a276e9a5d26ddbf35353ddf9fac6`, 브랜치는 `unit/u10-scenario-generation`이다. V10의 선택 컬럼 5개와 typed 장면, 별도 생성/검토, 서버 검사, 원래 15초 예산, 목표 종류별 고정 맥락, 개인정보 치환, 실제 노출 이력, U15 구조 맥락·변형 보존, 기존 C10/C8 응답을 연결했다. RT 운영 소스·프런트·V1~V9 SQL은 바꾸지 않았다.

- `ScenarioScriptGenerator`는 내용 수정1·전체 기술재시도1·BASE-only의 별도 검토를 관리한다. 최대6호출은 성공 보장이 아니며 남은 예산이 없으면 미발급이다. `CoreLunaClient.CONTRACT_SINGLE_CALL`은 스키마를 강제하고 안쪽 재시도를 하지 않는다. 기존 호출 모드와 다른 유닛의 `LunaCallBudget` 한도3은 유지했다.
- `ScenarioService`의 실제 `TextAiProvider`가 `ScenarioScriptProvider` 능력을 제공하면 새 흐름을 선택한다. 레거시 직접 어댑터·대역 시험은 그대로 보존했다. 준비의 최초 lease를 전달해 새15초를 주지 않는다. 최종 guardian 잠금 안에서 source/목표/아이·동의·마감·저장 lease를 다시 확인한다. AI·그래프 대기는 그 트랜잭션 밖이다.
- 내용 수정 소진은 같은 요청을 FAILED로 고정한다. 기술/마감/권한 실패와 기존 중복 지문 때문에 생긴 미발급은 선점을 해제해 재요청할 수 있다. READY 재사용 전에도 현재 동의를 확인한다. BASE-only 최종 검토 자체의 스키마/공급자 실패도 기존 SCENARIO_GEN_FAILED로 끝낸다.
- `SavedGoalReader`와 해당 소유 저장소의 preparationMetadata가 담은 목표를 고정한다. 후보는 `candidate:<candidateRef>` 운반 키와 typed 내용을 보존하며 행동 카탈로그를 읽지 않는다. 과거 생성자/JSON은 LEGACY_BEHAVIOR다.
- 같은 저장 순위는 종류와 무관하게 selectionId로 순서를 정한다. 같은 종류·순위 중복은 별도 Set으로 거절해 다른 종류가 사이에 있어도 놓치지 않는다. 별칭 정리는 목표의 의미 텍스트에만 적용하며 후보/기존 키·판·종류·skillRef는 내부 원본 그대로 보존한다. 별칭이 ID/키 일부와 같아도 치환하지 않는 대조를 추가했다.
- `ScenarioPrivacyContextAdapter`는 계정·동의·프로필 소유에서 이름을 얻고 누락/불일치를 빈 별칭으로 통과시키지 않는다. 공급자 goal/구조 맥락은 명시적 의미 필드만 투영한다. 후보·선택·세션·아이·보호자 ID와 판정 기준은 그 입력에 없다. 내부 정리 목표 원본과 비공개 별칭은 별도다.
- 이력은 같은 guardian/child의 최초 시각 이하 최근 EXPOSED3건이다. DB 전용 LOCAL timeout은 설정500ms와 전체 잔여의 작은 값이다. DB 실패/너무 긴 자료는 빈 이력/잘린 문장으로 바꾸지 않는다. NEW의 지정 ORIGINAL은 실제 노출과 별도다. 그래프 계획용 내부 좌표는 저장된 typed BASE/목표에서만 읽고 공급자에는 과거 설명·트리거만 보낸다.
- `PreparedPracticePackageMapper`는 첫 BASE의 기존 필수8필드와 선택 대본 필드를 C8 첫 목표 아래에 보낸다. 실제 Controller 응답, V10 재조회 및 전체 C8 스키마를 대조했다.

## 설계와 구현의 구체화·차이

U15의 계획기는 구조화된 BASE의 자리·화제·참여자·관계·상황을 요구하지만 저장 목표의 자유문에서 이를 추측하지 않는다. 따라서 구조 맥락을 먼저 조회하고, 실제 BASE를 받은 뒤 `variantPlan`으로 변형을 대조한다. 두 조회와 모든 내용 보정/BASE-only 검사에 같은 그래프 소비시간 예산2초를 공유한다. 모델 응답을 기다린 시간은 별도 그래프 작업 시간에 더하지 않지만 전체15초에서는 항상 차감한다. 예산을 다 쓴 뒤 새2초를 만들지 않는다. 지원하지 않는 좌표/계획은 변형을 발급하지 않으며 내용 보정 후에도 실패하면 따로 승인받은 BASE만 쓴다.

최초 구조 맥락 성공 뒤에도 variantPlan 조회가 실패·빈 값·합계 예산 소진이면 구조 맥락을 쓴 BASE를 false로 재표시하지 않는다. 남은 내용 수정1회 슬롯으로 structureContext/variantPlan/baseToPreserve 없는 새 BASE-only를 생성하고 따로 검토·검사한 최종 대본에만 false를 기록한다. 슬롯이 소진됐거나 전체15초가 부족하면 미발급이다. 그래프가 정상이고 변형 내용만 틀렸다면 기존 BASE-only 별도 검토/true 경로를 유지한다. 조회 실패와 내용 불일치를 구분한 U10ScenarioGraphFallbackTest7개가 이 경계를 대조한다.

V10 공통 시험 4파일의 기대값·fixture 변경은 사용자의 한정 허용에 따랐다(`docs/units/u10/migration-test-review.md`). 기존 거절·정확 비교·커버리지·ArchUnit은 유지한다. 별도로 R-F7 근거로 TimeLiteralScanTest에 정확한 `practice/ScenarioDeadline.java`와 `future.get(limit.toNanos(),TimeUnit.NANOSECONDS)` 조각의 단위 변환 근거만 등록했다. 검색식/단언/시간값/하한 변경은 없다. sleep은 기존 `CoreLunaClient.SYSTEM_SLEEPER`를 재사용한다. 이를 새 사용자 승인으로 기록하지 않는다.

기존 FrozenGoalOrderTest는 동률 거절의 `I5` 문구까지 요구한다. 새 같은 종류 동률 검사에도 해당 문구를 보존했으며 기존 시험은 수정하지 않았다. 닫힌 객체 대조가 지적한 자체 검토 스키마의 조건 객체는 닫힌 anyOf 분기로 표현해 승인+사유가 거절되는 의미를 유지했다.

## 1차 검토 전 실행한 시험

시험은 검증 복사본 `/home/pdypd/mujungi-u14-verify`에서 Docker PostgreSQL과 가짜 공급자만 사용했다. 실제 공급자 키/자료를 읽거나 실호출하지 않았다. 사용자 `mujoong-manual`은 접근하지 않았다.

최종 실행 전 변경된 소스·자원·시험·계약·범위64파일을 Windows 정본과 검증 복사본에서 바이트 대조해 불일치0을 확인했다. 파일 이름과 개별 SHA-256을 묶은 지문은 `089ba361356ab647cbdb58655e7f9fb43861e0cd8b751975a1a8b9a948343eb3`다. 범위/소스 누락은 성공으로 다루지 않는다. 비교 목록/집계 보조 도구는 work/에만 있으며 제품/커밋에서 제외한다.

- 구현 전 main 기준선: backend3149개, 실패0/기존 LOCAL PostgreSQL 조건 skip3.
- 각 층 test-after: 모델8+V10호환1+저장소10, Luna13, 생성/검사/마감26, 개인정보14, 목표17, 그래프9·변형7·이력6·그래프 기본 경로7, 서비스/PG/Controller15, 패키지8, 실제 Spring 어댑터 설정2.
- 최신 `:contract-model:test :core-api:test -PincludeTags=u10 -Pmujoong.test.db=docker --no-parallel --max-workers=1`: **19클래스143개, 실패0/오류0/skip0**(29초). 실제 LunaAdapterConfiguration과 Spring 자동주입 경로의 성공/거절을 가짜 CoreLunaClient로 검증해 별도 생성/검토와 레거시 우회·발급0을 확인했다. 구성요소별 시험 명령은 같은 작업에 `--tests`로 해당 실제 클래스 경로를 제한했다. 개인정보 기존 sanitizer/client, ScenarioService/PracticeStart와 순서·시간 검색 회귀도 별도로 통과했다.
- 전체 backend `clean check`는 RT 기존 삭제 시험에서 실패했다. worker2 실행8분57초, worker1 실행8분35초 모두 계약322·Core1699 실패0, RT1235 중1실패였고 test-support는 실행하지 않았다. 첫 시도는 계약의 닫힌 객체 검사, 두 번째는 시간 조각 등록·I5 문구에서 실패했고 기존 시험을 끄지 않고 원인을 수정했다.
- 그 뒤 그래프 기본 경로·BASE 자체 보존·요청 상태 보완 때문에 시작했던 전체 실행3개는 이 작업이 소유한 Gradle wrapper/test worker만 종료해 취소했다. 해당 결과는 합격으로 합치지 않는다. 이어 전체 실행 두 번에서 관측한 RT 실패도 지우지 않는다.
- RT 두 오디오 사례의 입력 수신 장벽은 사람의 별도 한정 승인(rt-test-review.md)을 받아 적용했다. 기존 정확 단언/시한·운영 코드는 유지했다. 그러나 변경 후 RT4개 한정도 원음 파일 개수에서1실패여서 해결/전체합격으로 다루지 않는다. 실패 로그·진단 한계는 validation-notes.md에 보존했다.
- 부모의 실패 메서드1개 한정 임시 디렉터리 보존 진단은27초 통과해 누락 파일 증거를 얻지 못했다. 앞선 클래스/전체 실패는 열린 검증 우려로 유지하며 단독 통과로 덮지 않는다.
- 최신 고정 소스 `clean :contract-model:check :core-api:check :test-support:check -Pmujoong.test.db=docker --no-parallel --max-workers=1`: **5분43초 BUILD SUCCESSFUL**, 계약322·Core1705·test-support31=총2058개, 실패0/오류0/기존 LOCAL PostgreSQL 조건 skip3. RT는 이 분리 실행에 포함하지 않았으며 전체 backend 합격의 대체 근거가 아니다.
- 전체 Core 시험 XML 기준 변경 운영 Java27파일 **1315/1363줄=96.4784%**로 U10 범위80%를 통과했다. 모듈 줄은 Core10010/10411=96.1483%, 계약1527/1551=98.4526%, test-support558/1305=42.7586%다. 기존 각 모듈 check 관문을 그대로 통과했으며 태그 결과를 모듈 커버리지 근거로 쓰지 않았다. test-support의 기존 LOCAL skip3은 initScriptsCreateRolesSchemasAndSingleHbaAllowLine, twoMigrationSetsKeepSeparateHistoryTablesInFlywaySchema, connectionFromOutsideTheAllowedCidrIsRejected의 LOCAL 분기다.

## 증거 범위와 남은 작업

[자동-대역]은 입력 전달·거절·권한/마감·DB/계약을 검증한다. 실제 나이 적합성·의미 반복/보존 품질과 실제 Luna의15초 지연은 **미측정**이다. RUNBOOK 단계4/R-F15의 준비 확인 전 실호출하지 않는다. 실제 시간 초과가 많으면 예산을 늘리는 대신 미리 생성/캐시의 버전·권한·삭제 위험을 별도 검토한다. U12 소비 상태 기계·실기기·배포·U10 main 병합은 범위 밖이다. RT 파일 개수 실패는 미해결 검증 우려다. 전체 verify.sh, 독립 검토, push/PR/CI는 다음 검증 단계의 실제 결과를 기다린다.

## 1차 NOT-READY 검토 뒤 수리

review-01의 R-01~04를 승인 범위에서 보완했다. 기존 요구·설계·15초/그래프2초·내용1/기술1/최대6호출은 바꾸지 않았다.

- R-01: 최초 생성은 BASE-only다. 그 실제 BASE 좌표로 U15 계획을 정하고, 사유/변경 축/거리/목표 좌표/보존 규칙의 의미 필드만 생성기 입력으로 투영한다. 남은 내용 수정1회로 원 BASE와 계획을 전달해 변형을 생성하고 별도 검토에도 같은 계획을 보낸다. 계획을 받지 않은 생성 응답의 변형은 발급하지 않는다. 같은 BASE의 계획은 요청 안에서 캐시하며 다른 BASE의 조회는 기존 합계2초 잔여를 쓴다. 계획 실패·빈 값·합계 초과는 기존 구조 없는 새 BASE 경로를 유지한다. U10PlannedGenerationIT 공급자는 **실제 전달된 입력**에 계획이 있을 때만 해당 계획으로 변형을 만들므로 미리 알고 만든 응답 주입에 기대지 않는다.
- R-02: 공개 SCENARIO_GEN_FAILED는 유지하고 내부 GenerationFailure.permanent로 재요청 가능 여부를 준비 소유자까지 전달한다. 실제 PracticePreparationRepository→ScenarioService 경로에서 기술재시도 소진·마감·중복 후 같은 idempotencyKey가 복구되고 연속 내용 반려는 FAILED로 남는지 확인했다. 레거시 일반 SCENARIO_GEN_FAILED의 영구 내용 실패 처리는 유지했다.
- R-03: 최종 저장 tx의 statement/lock timeout은 pg_settings의 현재 유한 한도와 최초 마감 잔여 중 작은 값만 LOCAL 적용한다. 기존 한도를 늘리지 않고0=무제한은 잔여 양수 한도로 바꾸며1ms 미만은 실패한다. 각 저장 SQL 사이와 INSERT/READY 뒤 현재 시계를 다시 읽고 beforeCommit에도 UTC/단조시계 마감을 검사한다. 실제 pg_sleep 지연·INSERT 뒤 정각·READY 뒤 정각·커밋 직전 만료는 발급0/READY0 및 함께 롤백으로 대조했다.
- R-04: 선택 generation.kgVersion은 실제 사용한 SkillContext.graphVersion이며 구조 없는 최종 대본은 null이다. 기존4인자 생성자와 과거 JSON은 유지하고 계약/DB 왕복/첫 패키지를 대조했다.

자체 점검 중 첫 BASE의 내용 REJECT 뒤 보정 BASE 좌표가 달라지는 경계를 추가 보완했다. 실제 BASE에서 다시 구한 계획의 의미 투영이 생성에 전달한 계획과 다르면 검토 입력을 몰래 새 계획으로 교체하지 않고 해당 VARIANT도 승인하지 않는다. 남은 추가 생성 없이 기존 별도 BASE-only 검토로만 끝낸다. 공급자가 실제 입력 계획으로 변형을 만들되 BASE 좌표를 바꾸는 대조에서 원 계획 유지·변형 미발급을 확인했다. 또한 내용 슬롯이 이미 소진된 뒤 그래프 조회 장애/시간초과로 기본 재생성이 불가능한 경우는 영구 내용 소진으로 분류하지 않고 준비 재요청 가능 실패로 전달한다. 실제 준비 저장소 대조로 PREPARING 유지와 동일 키 복구를 확인했다.

수리 집중26개는20초 통과했다. 첫 작성 실행의3실패는 Mockito 기존 answer를 재설정하는 시험 문제였고 doReturn/doThrow로 고쳤다. 이어 U10 전체22클래스156개가33초 통과했으며 마지막 DB 한도 경계 추가 뒤 해당 클래스7개도16초 통과했다. 전체 최종 고정 소스의 Core/계약/test-support 결과와 커버리지는 아래에 확정한다. RT 재실행은 하지 않았다.

수리 후 정본↔검증 복사본 소스/시험/자원/계약67파일 바이트 불일치0, 묶음 SHA-256은 `8662190eb4220282c1a2af6649074bfada001a3840b015d02d7c44ffb8a9077e`다. scope는 여전히 실제 변경 운영 Java27파일이다.

위 두 경계 보완 전 고정판의3모듈 clean check는5분32초 성공(계약322/Core1720/test-support31, 실패0/기존skip3, U10 22클래스158개, scope1366/1412=96.7422%)했다. 이를 뒤에 바뀐 소스의 최종 근거로 재사용하지 않는다. 추가 두 경계 후 집중2클래스10개는19초 통과했다. 최종 고정67파일 묶음은 `6a536637b10d0d755509dba4a549b18b2575a167ec81531eca0949c8eb83ea52`이고 정본/복사본 바이트 불일치0이다. 이 소스로3모듈 clean check를 다시 실행해 아래 결과를 확정한다.

### 수리 후 최종 고정판 검증

`clean :contract-model:check :core-api:check :test-support:check -Pmujoong.test.db=docker --no-parallel --max-workers=1`은 **5분31초 BUILD SUCCESSFUL**이다. 계약322/Core1722/test-support31=총2075개, 실패0/오류0/기존 LOCAL 조건 skip3. 그 안의 U10은 **22클래스160개 실패0/오류0/skip0**다. 수리 신규 대조는 계획 입력5·실제 준비 실패분류5·DB마감7=17개다. 기존 운영 Java27파일 범위는 **1372/1418줄=96.7560%**, Core10067/10466=96.1877%, 계약1527/1551=98.4526%, test-support558/1305=42.7586%이며 기존 check/커버리지 관문을 유지했다.

RT는 이 수리 검증에 포함하지 않았다. 과거 실패와 부모의 후속 클래스 진단 통과는 validation-notes.md에 함께 남았으며 RT 포함 전체합격은 미확인이다. R-01~04 수리의 독립 재검토·Build and Test·전체 verify.sh·push/PR/CI는 부모 담당의 다음 단계다. engine 소유 리뷰/감사·CodeKB·머신 manifest·기존 실행 비트·work 보조 자료는 이 수리 커밋에 섞지 않는다.

## main U11 병합 뒤 한정 수신 대기 보완

사용자는 docs/units/u10/tools-test-review.md의 제안에 대해 `Retry with fix — 수신 대기 보완 후 계속 (추천)`을 선택했다. 기존 U10 구현은 9d9b77d·12e59e8·8599948·dd09baa·1e4db60에 보존됐고, main 683c669는 6cd526f로 병합됐다. 앞선 계획25단계의 완료 표시는 이 커밋과 기존 실행 증거에서 복원했으며 계획 본문·Testing Contract는 바꾸지 않았다. 새 제품 요구사항·설계 변경이나 운영 코드 수정은 없다.

수리 대상은 tools/tests/u11/fake-provider-wire.test.mjs의 마지막 사례 하나다. `turn-emitted`는 서버의 마지막 송신 예약 뒤에 기록되므로 클라이언트 수신 완료를 뜻하지 않는다. 그 대기 뒤에 전사와 turn 기록의 존재를 확인하고 누적 수신 음성이 `turn.outputBytes + (speech.message.start_ms + FILLER_MS) * 48` 이상일 때까지 기존 waitUntil로 기다리는9줄을 추가했다. 원래 전사/음성 순서·start_ms·정확한 바이트 수 단언과3000ms 제한은 그대로다. 과다 음성은 기존 정확 일치 단언이 계속 거절한다.

검증은 소스 정본의 해당 파일만 `/home/pdypd/mujungi-u10-final-verify`의 clean 6cd526f 복사본에 반영한 뒤 Node22.23.3·Vitest4.1.11로 순차 실행했다. 해당 파일 명령 `node ./node_modules/vitest/vitest.mjs run tests/u11/fake-provider-wire.test.mjs`는 **1파일8건 전부 통과**, 실행기 표시912ms였다. 이어 `node ./node_modules/vitest/vitest.mjs run`은 **12파일131건 전부 통과**, 표시9.64초였다. 각각 한 번만 실행했고 실패0·skip0이다. 가짜 공급자만 사용했고 실제 API 호출·사용자 수동 스택 접근은 없다.

앞선 전체 검증의 tools 1건 실패와 build-and-test/test-results.md의 Loop-back 1은 삭제하지 않는다. 위 두 통과는 한정 수리 검증이며 전체 verify.sh 성공을 대신하지 않는다. 수리 전 main 병합판의 백엔드3416건(실패0·기존LOCAL skip3), U10 160건·범위96.7560%, 프런트994건 통과는 이전 판의 증거로 구분한다. 수리 후 전체 검증·push·PR CI는 다음 검증 단계에서 실제 실행 결과로 확정한다. 기존 수용된 잔여 위험은 이번 한정 수리로 해결됐다고 표시하지 않는다.

## 기존600ms 계약판의 C9 채움 자료 보완

사용자는 docs/units/u10/c9-test-review.md에 대해 `Retry with fix — 보완 후 검증·push·CI 계속 (45~75분·API 비용0·위험 낮음, 추천)`을 선택했다. 기존 main683c669를 포함한 bd666d0의 C9 실패33600/기대28800바이트는 채움700ms와 서버600ms의100ms 차이4800바이트에 해당한다. 이 수리는 그600ms 계약판의 시험 지원3파일에 한정한다. 새로운 main의 시간 계약 변경은 이 결과의 검증 대상이 아니다.

- scenarios.mjs의 일반 지시 채움 기본값을600ms로 두고 buildTurn의 선택 옵션으로 받는다. 기존 FILLER_MS=700은 유지한다. server.mjs는 같은 기본값과 명시 옵션을 전달하고 잘못된 음수·소수·NaN은 기동 전에 거절한다.
- wire 시험의 기존 마지막 사례는700ms를 명시한다. 기존 수신 장벽·정확한 바이트 단언·3000ms 제한을 보존했다. 추가 회귀는 contracts/timing.md와 realtime application.yml의 실제600ms를 읽어 기본값과 비교하고, 직접 턴 및 실제 WS의 채움+PCM 전체와 서버 수신 경계 뒤 정확한 바이트/해시를 대조한다. 기존 C9와 운영 코드·설정·계약은 수정하지 않았다.
- 기존 제품 커밋과 산출물을 확인했으며 실제 계획25단계는 이미[x]여서 수정하지 않았다. Testing Contract·업무 요구사항·설계·추적표와 수용된 R-05/R-06은 그대로다.

Node22.23.3·Vitest4.1.11의 ext4 검증 복사본에서 순차 실행했다. 초기 복사본의 CRLF shebang 때문에 Vitest가 server.mjs의 변환 코드를 읽지 못해0건 로딩 실패했으며, 상세 로더 진단도 같은 실패를 확인했다. Node --check는3파일 모두 정상으로 로직 문법 오류와 구분했다. 검증 복사본에서만 Git input 정규화와 인덱스 재물질화로 LF를 맞췄다. 정본/복사본의 LF 정규화 소스3파일은 전부 바이트 일치한다. 실패를 성공 건수에 합치지 않는다.

그 뒤 정확한 wire 명령은 **1파일12건 통과**, 시험662ms·실행기884ms였고, 별도 전체 tools 회귀는 **12파일135건 통과**, 실행기9.60초였다. 실패0·skip0이며 기존8건에 기본 경로1건과 잘못된 선택 값3건을 더했다. 실제 API 호출은0이며 수동 스택에 접근하지 않았다. 이 증거는600ms 계약판의 한정/도구 검증이고, 전체 verify.sh·최신 main 통합·push·CI 성공을 선취하지 않는다.
