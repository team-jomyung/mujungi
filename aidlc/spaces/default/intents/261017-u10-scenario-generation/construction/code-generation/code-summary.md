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

## 실행한 시험

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
