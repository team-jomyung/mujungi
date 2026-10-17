## Review

**Verdict:** NOT-READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Iteration:** 1
**Date:** 2026-10-17T05:43:22Z

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioGraphContext.java > Session.valid, 58~66행; backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioScriptGenerator.java > generate/review | 실제 `variantPlan`은 생성과 별도 검토가 끝난 뒤 `validation.inspect`에서만 조회되고 지역 변수로 버려진다. 최초 입력에는 structureContext만 있으며, 보정 입력도 correction/baseToPreserve만 추가한다. 검토 입력에도 계획이 없다. 그런데 generation.v2는 유효한 구조 맥락·변형 계획이 있을 때만 VARIANT를 만들라고 지시한다. 따라서 계획을 따르는 공급자는 BASE만 반환할 수 있고, 변형을 반환하더라도 공급자가 전달받지 않은 변경 축·거리·좌표를 추측해야 한다. 기존 변형 시험은 계획을 알고 만든 응답을 주입하므로 이 연결 누락을 검출하지 못한다. 승인 설계 FR5.1·FR6/W3의 계획 기반 생성·보정이 실제 입력 경계까지 이어지지 않는다. | 실제 BASE를 얻은 뒤 확정한 계획을 허용된 생성/보정 및 별도 검토 입력에 전달하는 흐름을 구현한다. 기존 내용 수정1회·기술 재시도1회·최대6호출·15초/그래프 합계2초를 유지하고, 공급자가 받은 계획만으로 유효 변형을 만드는 서비스 통합 시험과 계획 없음/실패 대조를 추가한다. | New |
| R-02 | Major | backend/core-api/src/main/java/kr/mujoong/core/practice/start/PracticeStartService.java > prepare catch/finally, 124~128행 | 새 생성기의 기술 재시도 소진·마감·개인정보/이력 일시 실패는 일반 SCENARIO_GEN_FAILED로 끝난다. ScenarioService는 이런 실패의 시나리오 claim을 재시도 가능하게 해제하지만, 시작 준비는 해당 오류 코드 전부를 `failed=true`로 넘겨 core_practice_preparation을 FAILED로 고정한다. 다음 동일 시작 요청은 82/93행에서 생성기를 부르기 전에 거절된다. 중복 지문 때문에 재시도 가능하게 해제한 ContentExhausted도 이 상위 경로에서 똑같이 굳는다. 공개 결과 문서의 '연속 내용 실패만 고정, 일시 실패·중복은 재요청 가능' 설명이 실제 준비→첫 대본 경로에서는 성립하지 않는다. | 공개 오류 코드를 유지하면서 내부에 내용 소진/재시도 가능 실패의 구분을 전달하고 준비 release에도 적용한다. 실제 준비 저장소를 사용해 기술 실패·마감·중복 후 같은 idempotencyKey 재요청이 복구되는지, 연속 내용 실패는 계속 고정되는지 시험한다. | New |
| R-03 | Major | backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioService.java > 구조화 경로 최종 tx; backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioRepository.java > save | 최종 `budget.check()` 뒤 `clock.now()`를 한 번 읽어 save에 넘긴다. save는 그 고정 now로 claim/lease를 SELECT한 뒤 INSERT와 READY 갱신을 실행하며, DB 작업 뒤 또는 커밋 전 마감을 다시 확인하지 않는다. 이 구간의 SQL/트랜잭션에도 최초 마감의 잔여 시한을 적용하지 않는다. 공용 DB 설정은 statement 10초·lock 2초이므로, 예를 들어 14.9초에 검사한 뒤 저장 SQL이 지연되면 15초가 지난 뒤에도 READY를 커밋할 수 있다. ScenarioDeadline.call은 개별 AI/그래프/이력 조회만 감싸므로 이 저장 구간을 취소하지 않는다. 이는 FR2.3/NFR1/W5의 늦은 발급 금지와 전체15초 보장에 남은 빈틈이다. | 최종 저장 트랜잭션의 SQL 대기를 최초 잔여 마감으로 제한하고, SQL 수행 뒤 커밋을 확정하기 전에 현재 UTC/단조시계 마감을 확인해 초과 시 INSERT와 READY를 함께 롤백한다. 마감 직전 저장 진입 후 DB 지연이 발생하는 시험에서 발급0·READY0과 정각 만료를 확인한다. | New |
| R-04 | Minor | backend/core-api/src/main/java/kr/mujoong/core/practice/Scenario.java > Generation, 20행; contracts/schemas/practice-scenario.schema.json > generation | U15 SkillContext.graphVersion은 조회되지만 Generation에는 model/promptVersion/graphContextUsed/calls만 있어 사용한 그래프 판이 저장·응답되지 않는다. generation 스키마도 additionalProperties=false라 kgVersion을 허용하지 않는다. 상위 C-DS1의 kgVersion, 컴포넌트 Scenario→KgVersion 관계와 승인 entities.md/BR5.2의 '실제로 쓴 판 기록'이 유실됐다. | 실제로 사용한 graphVersion을 generation.kgVersion으로 저장·재조회·첫 패키지까지 전달하고 계약을 맞춘다. 그래프를 쓰지 않은 최종 대본은 null, 사용한 대본은 해당 판인지 대조한다. | New |
| R-05 | Minor | docs/units/u10/validation-notes.md > 장벽 보완 후에도 남은 실패 | RT 운영 코드 변경의 인과는 확인되지 않았지만, 두 전체 실행의 RT 실패 및 장벽 보완 후 클래스4개 중 파일 개수1실패는 아직 미해결이다. 이후 단일 메서드 통과는 전체/클래스 실패를 해소한 증거가 아니다. 문서는 이 한계를 정확히 밝히고 있으므로 허위 합격 지적은 하지 않는다. 다만 현재 근거로 전체 백엔드 회귀가 통과했다고 판단할 수 없다. | 원 단언·10초 한도·운영 코드를 임의로 완화하지 말고 실패 때의 실제 파일 목록과 종료/쓰기 순서 증거로 원인을 좁힌다. 허용 범위 안에서 원인을 해결하거나 사람이 판단할 구체적 잔여 위험을 제시하고, 후속 전체 검증 결과를 별도로 남긴다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| stage 정의 확인 | 별도 validation_tools 선언 없음 | 필수 공개 산출물·계약·소스 및 시험을 직접 대조했다. lifecycle/routing 명령은 실행하지 않았다. |
| traceability JSON 읽기 및 경로 검사 | upstream75, coverage75, 고유 coverage75, 없는 OK target0 | 구조적 추적 누락은 찾지 못했다. OK 표시는 위 동작 결함의 반증을 대신하지 않는다. |
| 범위 및 차분 대조 | U10 운영 Java27파일이 scope에 열거됨; V10은 nullable5컬럼 추가 | RT 운영 소스·기존 V1~V9 SQL 변경 없이 새 저장 모양을 추가했다. 공통 시험4파일/RT 입력 장벽의 좁은 사람 예외를 적용해 판단했다. |
| 기존 고정 소스 시험 증거 | U10 19클래스143개 실패0/오류0/skip0; 계약322+Core1705+test-support31=2058개 실패0/오류0/기존 LOCAL skip3; U10 1315/1363줄=96.4784% | 부모가 실제 XML/JaCoCo를 재집계한 근거와 공개 문서를 대조했다. 중복 전체 Gradle 실행은 하지 않았다. RT 전체 합격을 뜻하지 않는다. |
| Spring/호환 경계 읽기 | LunaTextAiProvider의 ScenarioScriptProvider 능력에 따른 새 흐름 연결, 별도 생성/검토 목적·스키마, C8 선택 필드 전달 및 후보 운반 키 확인 | 기본 구성 연결·레거시 응답 보존에서 추가 확정 결함은 찾지 못했다. 이름 별칭/최소 투영·동의 재확인·U15 읽기 경계와 기존 호출의 안쪽 재시도 보존도 확인했다. |
| aidlc engine now | PowerShell 직접 호출은 명령 없음; 제공된 bridge의 읽기 전용 now 호출 성공 | 위 UTC 날짜는 실제 명령 결과다. |

### Summary

계획 기반 변형 입력의 연결, 시작 준비의 실패 분류, 최종 저장의 마감 보장에서 Major3건을 확인해 NOT-READY다. 개인정보 최소화·기존 후보/행동 호환·스키마 분리와 넓은 자동 시험 증거는 확인했으나 위 경계의 빠진 동작까지 입증하지는 않는다. 실제 Luna 지연/판단 품질과 실기기는 승인 NFR5에 따라 미측정으로 유지하며 이를 결함이나 성공으로 바꾸지 않았다.
