## Review

**Reviewer:** aidlc-architecture-reviewer-agent
**Iteration:** 1
**Verdict:** READY
**Date:** 2026-10-17T08:06:16Z

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioGraphContext.java > Session.valid, 58~66행; backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioScriptGenerator.java > generate/review | 실제 `variantPlan`은 생성과 별도 검토가 끝난 뒤 `validation.inspect`에서만 조회되고 지역 변수로 버려진다. 최초 입력에는 structureContext만 있으며, 보정 입력도 correction/baseToPreserve만 추가한다. 검토 입력에도 계획이 없다. 그런데 generation.v2는 유효한 구조 맥락·변형 계획이 있을 때만 VARIANT를 만들라고 지시한다. 따라서 계획을 따르는 공급자는 BASE만 반환할 수 있고, 변형을 반환하더라도 공급자가 전달받지 않은 변경 축·거리·좌표를 추측해야 한다. 기존 변형 시험은 계획을 알고 만든 응답을 주입하므로 이 연결 누락을 검출하지 못한다. 승인 설계 FR5.1·FR6/W3의 계획 기반 생성·보정이 실제 입력 경계까지 이어지지 않는다. | 실제 BASE를 얻은 뒤 확정한 계획을 허용된 생성/보정 및 별도 검토 입력에 전달하는 흐름을 구현한다. 기존 내용 수정1회·기술 재시도1회·최대6호출·15초/그래프 합계2초를 유지하고, 공급자가 받은 계획만으로 유효 변형을 만드는 서비스 통합 시험과 계획 없음/실패 대조를 추가한다. | Resolved (reviewer) |
| R-02 | Major | backend/core-api/src/main/java/kr/mujoong/core/practice/start/PracticeStartService.java > prepare catch/finally, 124~128행 | 새 생성기의 기술 재시도 소진·마감·개인정보/이력 일시 실패는 일반 SCENARIO_GEN_FAILED로 끝난다. ScenarioService는 이런 실패의 시나리오 claim을 재시도 가능하게 해제하지만, 시작 준비는 해당 오류 코드 전부를 `failed=true`로 넘겨 core_practice_preparation을 FAILED로 고정한다. 다음 동일 시작 요청은 82/93행에서 생성기를 부르기 전에 거절된다. 중복 지문 때문에 재시도 가능하게 해제한 ContentExhausted도 이 상위 경로에서 똑같이 굳는다. 공개 결과 문서의 '연속 내용 실패만 고정, 일시 실패·중복은 재요청 가능' 설명이 실제 준비→첫 대본 경로에서는 성립하지 않는다. | 공개 오류 코드를 유지하면서 내부에 내용 소진/재시도 가능 실패의 구분을 전달하고 준비 release에도 적용한다. 실제 준비 저장소를 사용해 기술 실패·마감·중복 후 같은 idempotencyKey 재요청이 복구되는지, 연속 내용 실패는 계속 고정되는지 시험한다. | Resolved (reviewer) |
| R-03 | Major | backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioService.java > 구조화 경로 최종 tx; backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioRepository.java > save | 최종 `budget.check()` 뒤 `clock.now()`를 한 번 읽어 save에 넘긴다. save는 그 고정 now로 claim/lease를 SELECT한 뒤 INSERT와 READY 갱신을 실행하며, DB 작업 뒤 또는 커밋 전 마감을 다시 확인하지 않는다. 이 구간의 SQL/트랜잭션에도 최초 마감의 잔여 시한을 적용하지 않는다. 공용 DB 설정은 statement 10초·lock 2초이므로, 예를 들어 14.9초에 검사한 뒤 저장 SQL이 지연되면 15초가 지난 뒤에도 READY를 커밋할 수 있다. ScenarioDeadline.call은 개별 AI/그래프/이력 조회만 감싸므로 이 저장 구간을 취소하지 않는다. 이는 FR2.3/NFR1/W5의 늦은 발급 금지와 전체15초 보장에 남은 빈틈이다. | 최종 저장 트랜잭션의 SQL 대기를 최초 잔여 마감으로 제한하고, SQL 수행 뒤 커밋을 확정하기 전에 현재 UTC/단조시계 마감을 확인해 초과 시 INSERT와 READY를 함께 롤백한다. 마감 직전 저장 진입 후 DB 지연이 발생하는 시험에서 발급0·READY0과 정각 만료를 확인한다. | Resolved (reviewer) |
| R-04 | Minor | backend/core-api/src/main/java/kr/mujoong/core/practice/Scenario.java > Generation, 20행; contracts/schemas/practice-scenario.schema.json > generation | U15 SkillContext.graphVersion은 조회되지만 Generation에는 model/promptVersion/graphContextUsed/calls만 있어 사용한 그래프 판이 저장·응답되지 않는다. generation 스키마도 additionalProperties=false라 kgVersion을 허용하지 않는다. 상위 C-DS1의 kgVersion, 컴포넌트 Scenario→KgVersion 관계와 승인 entities.md/BR5.2의 '실제로 쓴 판 기록'이 유실됐다. | 실제로 사용한 graphVersion을 generation.kgVersion으로 저장·재조회·첫 패키지까지 전달하고 계약을 맞춘다. 그래프를 쓰지 않은 최종 대본은 null, 사용한 대본은 해당 판인지 대조한다. | Resolved (reviewer) |
| R-05 | Minor | docs/units/u10/validation-notes.md > 장벽 보완 후에도 남은 실패 | RT 운영 코드 변경의 인과는 확인되지 않았지만, 두 전체 실행의 RT 실패 및 장벽 보완 후 클래스4개 중 파일 개수1실패는 아직 미해결이다. 이후 단일 메서드 통과는 전체/클래스 실패를 해소한 증거가 아니다. 문서는 이 한계를 정확히 밝히고 있으므로 허위 합격 지적은 하지 않는다. 다만 현재 근거로 전체 백엔드 회귀가 통과했다고 판단할 수 없다. | 원 단언·10초 한도·운영 코드를 임의로 완화하지 말고 실패 때의 실제 파일 목록과 종료/쓰기 순서 증거로 원인을 좁힌다. 허용 범위 안에서 원인을 해결하거나 사람이 판단할 구체적 잔여 위험을 제시하고, 후속 전체 검증 결과를 별도로 남긴다. | Accepted risk |
| R-06 | Major | backend/core-api/src/main/java/kr/mujoong/core/practice/start/PracticeStartService.java > prepare, 118~123행; backend/core-api/src/main/java/kr/mujoong/core/practice/start/PracticePreparationRepository.java > ready, 56~61행 | 첫 대본 저장의 마감 보호가 끝난 뒤 실행하는 별도 준비 READY 트랜잭션에는 잔여 SQL 한도와 커밋 전 마감 검사가 없다. `preparationContext(claim)` 다음 `ready(..., clock.now())`에 고정 시각을 넘기며 UPDATE는 `lease_until > :now`만 비교한다. 예컨대 14.9초에 유효성을 확인하고 UPDATE/커밋이 지연되면, 최초15초 뒤에도 준비 READY를 커밋하고 첫 패키지를 성공 반환할 수 있다. 앞 트랜잭션의 `TransactionSynchronization`은 이 새 트랜잭션을 보호하지 않는다. 승인 상세 설계 W1-5/6과 NFR1의 최초 준비 마감을 첫 전달까지 유지하지 못한다. | 첫 대본 생성에 사용한 동일 최초 마감을 준비 READY 확정까지 전달하고, 해당 트랜잭션의 SQL 대기를 잔여 시간으로 제한하며 SQL 뒤·커밋 전 현재 마감을 검사해 초과 시 READY를 롤백하고 패키지를 반환하지 않게 한다. 실제 준비 저장소를 사용해 마감 직전 READY UPDATE에 진입한 뒤 지연되는 경우와 커밋 직전 정각 만료에서 READY0·성공 패키지0을 검증한다. | Accepted risk |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Stage validation tools | None declared | 별도 필수 검증 도구가 없다. |
| Git diff d3ada85..bd666d0 및 원본 대조 | PASS | 시험에 수신 대기 9줄만 추가됐다. 해당 블록을 제거하면 이전 시험 파일과 일치한다. 기존 정확 단언·3000ms 기본 제한·운영 소스는 유지됐다. |
| buildTurn 바이트 경계 대조 | PASS | 첫 턴 clockMs=0, 확인 end_ms=300, FILLER_MS=700에서 예상·실제 합계는 모두 76800바이트다. 마지막 4800바이트가 없으면 새 대기는 완료되지 않고, 초과 바이트는 기존 정확 일치 단언이 거절한다. |
| Traceability 경로 확인 | PASS | upstream_ids 및 coverage는 각각 75행이며 OK 구현·시험 대상 경로가 모두 존재한다. |
| 시험 실행 증거 범위 검토 | PASS | 수리 후 파일 8건·tools 전체 131건 통과와 수리 전 백엔드·프런트 결과를 구분한다. 전체 verify.sh 및 최종 CI의 완료를 선취하지 않는다. 이 검토에서 전체 시험을 중복 실행하지 않았다. |

### Summary

turn-emitted는 송신 예약 종료이므로 클라이언트 수신 완료를 보장하지 않는다. 새 대기는 단일 연결·단일 첫 턴에서 기존 최종 바이트 단언과 같은 수신량을 기다려 이 경합을 보완하며, 기존 실패 조건을 약화하지 않는다. 한정 수리에서 새로운 결함을 확인하지 않았고, 전체 통합 검증과 CI는 후속 실제 실행으로 확인해야 한다.
