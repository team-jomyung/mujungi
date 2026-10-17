## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-17T06:22:53Z
**Iteration:** 2

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioGraphContext.java > Session.valid, 58~66행; backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioScriptGenerator.java > generate/review | 실제 `variantPlan`은 생성과 별도 검토가 끝난 뒤 `validation.inspect`에서만 조회되고 지역 변수로 버려진다. 최초 입력에는 structureContext만 있으며, 보정 입력도 correction/baseToPreserve만 추가한다. 검토 입력에도 계획이 없다. 그런데 generation.v2는 유효한 구조 맥락·변형 계획이 있을 때만 VARIANT를 만들라고 지시한다. 따라서 계획을 따르는 공급자는 BASE만 반환할 수 있고, 변형을 반환하더라도 공급자가 전달받지 않은 변경 축·거리·좌표를 추측해야 한다. 기존 변형 시험은 계획을 알고 만든 응답을 주입하므로 이 연결 누락을 검출하지 못한다. 승인 설계 FR5.1·FR6/W3의 계획 기반 생성·보정이 실제 입력 경계까지 이어지지 않는다. | 실제 BASE를 얻은 뒤 확정한 계획을 허용된 생성/보정 및 별도 검토 입력에 전달하는 흐름을 구현한다. 기존 내용 수정1회·기술 재시도1회·최대6호출·15초/그래프 합계2초를 유지하고, 공급자가 받은 계획만으로 유효 변형을 만드는 서비스 통합 시험과 계획 없음/실패 대조를 추가한다. | Resolved (reviewer) |
| R-02 | Major | backend/core-api/src/main/java/kr/mujoong/core/practice/start/PracticeStartService.java > prepare catch/finally, 124~128행 | 새 생성기의 기술 재시도 소진·마감·개인정보/이력 일시 실패는 일반 SCENARIO_GEN_FAILED로 끝난다. ScenarioService는 이런 실패의 시나리오 claim을 재시도 가능하게 해제하지만, 시작 준비는 해당 오류 코드 전부를 `failed=true`로 넘겨 core_practice_preparation을 FAILED로 고정한다. 다음 동일 시작 요청은 82/93행에서 생성기를 부르기 전에 거절된다. 중복 지문 때문에 재시도 가능하게 해제한 ContentExhausted도 이 상위 경로에서 똑같이 굳는다. 공개 결과 문서의 '연속 내용 실패만 고정, 일시 실패·중복은 재요청 가능' 설명이 실제 준비→첫 대본 경로에서는 성립하지 않는다. | 공개 오류 코드를 유지하면서 내부에 내용 소진/재시도 가능 실패의 구분을 전달하고 준비 release에도 적용한다. 실제 준비 저장소를 사용해 기술 실패·마감·중복 후 같은 idempotencyKey 재요청이 복구되는지, 연속 내용 실패는 계속 고정되는지 시험한다. | Resolved (reviewer) |
| R-03 | Major | backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioService.java > 구조화 경로 최종 tx; backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioRepository.java > save | 최종 `budget.check()` 뒤 `clock.now()`를 한 번 읽어 save에 넘긴다. save는 그 고정 now로 claim/lease를 SELECT한 뒤 INSERT와 READY 갱신을 실행하며, DB 작업 뒤 또는 커밋 전 마감을 다시 확인하지 않는다. 이 구간의 SQL/트랜잭션에도 최초 마감의 잔여 시한을 적용하지 않는다. 공용 DB 설정은 statement 10초·lock 2초이므로, 예를 들어 14.9초에 검사한 뒤 저장 SQL이 지연되면 15초가 지난 뒤에도 READY를 커밋할 수 있다. ScenarioDeadline.call은 개별 AI/그래프/이력 조회만 감싸므로 이 저장 구간을 취소하지 않는다. 이는 FR2.3/NFR1/W5의 늦은 발급 금지와 전체15초 보장에 남은 빈틈이다. | 최종 저장 트랜잭션의 SQL 대기를 최초 잔여 마감으로 제한하고, SQL 수행 뒤 커밋을 확정하기 전에 현재 UTC/단조시계 마감을 확인해 초과 시 INSERT와 READY를 함께 롤백한다. 마감 직전 저장 진입 후 DB 지연이 발생하는 시험에서 발급0·READY0과 정각 만료를 확인한다. | Resolved (reviewer) |
| R-04 | Minor | backend/core-api/src/main/java/kr/mujoong/core/practice/Scenario.java > Generation, 20행; contracts/schemas/practice-scenario.schema.json > generation | U15 SkillContext.graphVersion은 조회되지만 Generation에는 model/promptVersion/graphContextUsed/calls만 있어 사용한 그래프 판이 저장·응답되지 않는다. generation 스키마도 additionalProperties=false라 kgVersion을 허용하지 않는다. 상위 C-DS1의 kgVersion, 컴포넌트 Scenario→KgVersion 관계와 승인 entities.md/BR5.2의 '실제로 쓴 판 기록'이 유실됐다. | 실제로 사용한 graphVersion을 generation.kgVersion으로 저장·재조회·첫 패키지까지 전달하고 계약을 맞춘다. 그래프를 쓰지 않은 최종 대본은 null, 사용한 대본은 해당 판인지 대조한다. | Resolved (reviewer) |
| R-05 | Minor | docs/units/u10/validation-notes.md > 장벽 보완 후에도 남은 실패 | RT 운영 코드 변경의 인과는 확인되지 않았지만, 두 전체 실행의 RT 실패 및 장벽 보완 후 클래스4개 중 파일 개수1실패는 아직 미해결이다. 이후 단일 메서드 통과는 전체/클래스 실패를 해소한 증거가 아니다. 문서는 이 한계를 정확히 밝히고 있으므로 허위 합격 지적은 하지 않는다. 다만 현재 근거로 전체 백엔드 회귀가 통과했다고 판단할 수 없다. | 원 단언·10초 한도·운영 코드를 임의로 완화하지 말고 실패 때의 실제 파일 목록과 종료/쓰기 순서 증거로 원인을 좁힌다. 허용 범위 안에서 원인을 해결하거나 사람이 판단할 구체적 잔여 위험을 제시하고, 후속 전체 검증 결과를 별도로 남긴다. | Unresolved |
| R-06 | Major | backend/core-api/src/main/java/kr/mujoong/core/practice/start/PracticeStartService.java > prepare, 118~123행; backend/core-api/src/main/java/kr/mujoong/core/practice/start/PracticePreparationRepository.java > ready, 56~61행 | 첫 대본 저장의 마감 보호가 끝난 뒤 실행하는 별도 준비 READY 트랜잭션에는 잔여 SQL 한도와 커밋 전 마감 검사가 없다. `preparationContext(claim)` 다음 `ready(..., clock.now())`에 고정 시각을 넘기며 UPDATE는 `lease_until > :now`만 비교한다. 예컨대 14.9초에 유효성을 확인하고 UPDATE/커밋이 지연되면, 최초15초 뒤에도 준비 READY를 커밋하고 첫 패키지를 성공 반환할 수 있다. 앞 트랜잭션의 `TransactionSynchronization`은 이 새 트랜잭션을 보호하지 않는다. 승인 상세 설계 W1-5/6과 NFR1의 최초 준비 마감을 첫 전달까지 유지하지 못한다. | 첫 대본 생성에 사용한 동일 최초 마감을 준비 READY 확정까지 전달하고, 해당 트랜잭션의 SQL 대기를 잔여 시간으로 제한하며 SQL 뒤·커밋 전 현재 마감을 검사해 초과 시 READY를 롤백하고 패키지를 반환하지 않게 한다. 실제 준비 저장소를 사용해 마감 직전 READY UPDATE에 진입한 뒤 지연되는 경우와 커밋 직전 정각 만료에서 READY0·성공 패키지0을 검증한다. | New |

> R-01 Reviewer note: `StructuredScenarioFlow`는 최초 BASE-only 입력을 만들고, `ScenarioScriptGenerator.generate`는 실제 BASE로 구한 계획을 다음 생성과 별도 검토에 전달한다. `ScenarioGraphContext.Session.plan`은 BASE 좌표별 계획을 캐시하며 그래프 잔여 예산을 공유한다. 계획을 받지 않은 변형과 보정 BASE가 바뀌어 전달 계획과 달라진 변형은 발급하지 않는다. `U10PlannedGenerationIT`의 입력 의존 공급자 5개 대조에서 계획 전달·없음·장애·변경 BASE 경계를 확인했다. 내용1·기술1·최대6호출·최초15초·그래프 합계2초는 유지한다.

> R-02 Reviewer note: `ScenarioService.GenerationFailure.permanent`가 구조화 경로의 실패 성격을 `PracticeStartService.prepare`까지 전달한다. 기술 실패·마감·중복은 선점을 해제하고 연속 내용 실패만 FAILED로 고정한다. 내용 슬롯 소진 뒤 그래프 장애도 재요청 가능 실패로 전달한다. `U10PreparationRecoveryIT`는 실제 준비 저장소로 같은 키 복구와 영구 내용 실패를 각각 검증한다. 공개 오류 코드는 유지한다.

> R-03 Reviewer note: 지적 대상이었던 `ScenarioService`의 최종 대본 저장 트랜잭션에는 `beginDeadline`과 SQL 사이 `limitSql`, 저장 뒤 검사 및 `beforeCommit` 검사가 추가됐다. 현재 유한 DB 한도와 실제 잔여의 작은 값만 LOCAL 적용하고 0=무제한·1ms 미만을 구분한다. `U10FinalSaveDeadlineIT` 7개는 실제 SQL 대기, INSERT/READY 뒤 정각, 커밋 직전 만료의 대본·요청 READY 롤백과 DB 한도 보존을 대조한다. 아래 신규 지적은 그 트랜잭션이 종료된 뒤의 별도 준비 READY 경계다.

> R-04 Reviewer note: `Scenario.Generation.kgVersion`은 실제 `SkillContext.graphVersion`을 담고, 스키마는 선택 string/null을 허용한다. 기존 생성자·과거 JSON 호환을 유지한다. `U10PlannedGenerationIT`는 그래프 판의 저장·재조회·첫 패키지·계약 왕복과 구조 없는 최종 대본의 false/null을 대조한다.

> R-05 Reviewer note: `docs/units/u10/validation-notes.md`는 전체 RT1235개 실행 두 번의 각1실패 및 입력 장벽 뒤 클래스4개 중 파일 개수1실패를 보존한다. 후속 메서드1개·클래스4개 진단 통과에서는 실패 파일 증거를 얻지 못해 원인이 확정되지 않았다. 최신3모듈 clean check에는 RT가 없으며 전체 회귀 통과 근거가 아니다. 원 단언·10초·운영 코드를 유지한 상태에서 실패 파일 목록과 쓰기/종료 순서를 확보하거나 구체적 잔여 위험을 판단하고, 후속 전체 검증 결과를 따로 남겨야 한다.

### 검증 근거와 범위

- 검토 소스는 `1e4db60d449153dd9848f527e0d31d5f3590912c`다. 수리 diff `dd09baa..HEAD`와 허용된 U10 운영·시험·계약 변경, 승인 계획·시험 지침·추적·상위 요구/설계 및 관련 계약을 대조했다. 소스나 산출물을 수정하지 않았으며 중복 전체 시험은 실행하지 않았다.
- 단계 전용 `validation_tools` 선언은 없다. 읽기 전용 추적 점검은 upstream75개/coverage75개, 누락 ID0·구현/시험 참조 경로 누락0이었다. U10 운영 범위27파일도 모두 존재한다.
- 제공된 고정 소스 검증 근거는 계약322/Core1722/test-support31=2075개, 실패0/오류0/기존 LOCAL 조건 skip3, U10 22클래스160개 실패0/오류0/skip0이다. 범위1372/1418줄=96.7560%, Core96.1877%, 계약98.4526%다. test-support42.7586%는 기존 하한 제외 대상이다. 이는3모듈 결과이며 RT·전체 `verify.sh`·CI 합격으로 확대하지 않는다.
- RT 변경 diff는 승인된 입력 수신 장벽2줄뿐이며 원 정확 단언·10초 한도는 유지한다. 공통 V10 기대값/fixture4파일의 한정 허용과 R-F7의 기존 단위 변환 허용 목록 근거를 구분했다. 운영 RT 변경이나 새 하한 완화는 확인되지 않았다.
- 실공급자의 지연·나이 적합성·의미 보존 품질은 미측정이며, 자동 대역 결과와 구분한다. 유료/실제 공급자 호출은 수행하지 않았다.

### 판정 요약

기존 R-01~04의 수리는 확인됐다. 열린 지적은 새 Major1건과 기존 Minor1건으로, Critical0·Major2개 이하라는 리뷰어 판정 기준에 따라 READY다. 준비 READY의 마감 빈틈과 RT 검증 위험은 남아 있으며, 이 판정은 전체 백엔드 회귀나 Build and Test 완료를 뜻하지 않는다.
