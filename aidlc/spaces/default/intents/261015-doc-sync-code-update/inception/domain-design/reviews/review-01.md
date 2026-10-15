## Review

**Verdict:** NOT-READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-15T15:41:36Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/domain-design/components.md > ResultPipeline (behaviour, responsibilities) 및 decisions.md > ADR-003 Consequences · Assumptions | Q3-A의 "27 표시 매핑에서만 접기"는 지금 코드 구조로는 따로 둘 자리가 없다. `SceneChipMapper.chip(value,timing)`(core/result)이 `SessionResultHandler`에서 결과를 저장할 때 호출되어 scene_chip으로 저장되고, `SceneChipMapper.text()`는 `ResultQueryService`(27 · 결과 API)와 `ReportAssembler`(리포트) 둘 다 쓴다. 그래서 매퍼에서 AFTER_REASK를 DONE_IMMEDIATE로 접으면 저장 칩과 리포트 문구도 함께 접힌다. 이는 ADR-003 Consequences의 '리포트(시점 구분 있음)'와 어긋나고, "저장 값 · API 결과 값 · 리포트 집계에는 AFTER_REASK를 그대로 둔다"는 결정을 지키지 못한다. 또 이 문서는 '리포트 · PDF가 같은 매퍼를 쓰는지는 Functional Design에서 확인'을 열린 가정으로 두었지만, 코드를 보면 이미 같은 매퍼를 쓴다. | 열린 가정을 코드 사실로 닫는다. 접기를 ResultPipeline 안의 어느 경계(저장 시 chip, 27/결과 API 응답 시 text, 리포트)에서 하는지 정하고, 27 전용 표시 매핑을 새로 두어 저장 칩 · 리포트 경로와 분리한다고 쓴다. 저장 칩 컬럼의 5종 제약을 건드리는지도 적는다. ADR-003 Consequences를 이 사실에 맞게 고친다. | New |
| R-02 | Major | aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/domain-design/components.md > KnowledgeGraph(삭제 참여) 및 entities 전체 | 삭제 · 동의 철회 참여가 KnowledgeGraph 하나만 적혀 있다. 새로 생기는 개인 자료 보유 엔티티 중 PracticeScene · HelpRecord(도움 내용 content), JudgmentLive(heldOutputTranscript, inputTranscriptRef), GuardrailEvent(realtime), Scenario · GoalCandidate(evidenceUtterance, displayEvidence) · SceneResult(core)에는 삭제 참여 책임이 없다. 코드에는 realtime(Practice · Session · Voice · Messaging)과 core(Practice · Result · Consent 등) 쪽에 이미 DeletionParticipant가 여럿 있고, 새 표는 이를 확장하지 않으면 아동 · 기록 삭제 뒤에도 전사가 남는다. PracticeGoal은 '동의 철회 · 삭제 자료는 근거로 쓰지 않는다'만 적었고, 철회 후 이미 저장된 후보 · 담은 목표의 처리는 없다. | 각 새 엔티티의 삭제 주인(기존 어느 DeletionParticipant가 맡는지 또는 새 참여자)을 컴포넌트별 responsibilities에 더하고, 동의 철회 때 후보 · 판정 기록 전사의 처리를 정한다. | New |
| R-03 | Major | aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/domain-design/components.md > ApprovedContentCatalog / PracticeConversation / OutputGate (depends_on) | 세션 고정 대본(Luna가 만든 장면 문장)이 ApprovedContentCatalog에 들어가는 경로가 선언돼 있지 않다. Catalog는 depends_on이 비어 있고, 대본은 PracticeConversation이 ScenarioGeneration에서 내부 HTTP로 가져오는데 PC에서 Catalog로 가는 간선은 '조회'뿐이다. 그런데 OutputGate가 제브를 생략하는 근거(FR6.6 원문 일치)가 이 대본 원문이고, Luna 생성물은 제브로 보내지 않는다(FR2.6). 대본 등록 주체 · 시점(세션 시작 vs 장면 시작)이 비어 있으면 승인 재생이 전부 '새 발화'로 분류되어 제브가 불필요하게 불리거나, 반대로 출처 불명 문장이 생략될 수 있다. SessionContentPlan.scriptVersion이 세션 시작 때 고정된다는 서술과도 시점이 맞지 않는다. | 대본을 Catalog에 등록하는 컴포넌트와 간선(depends_on · dependents), 등록 시점, 출처 확인 규칙을 명시한다. | New |
| R-04 | Minor | components.md > OutputGate behaviour (버린 전사는 판정 기록 쪽으로 넘긴다) / JudgmentLive.heldOutputTranscript | OutputGate가 버린 전사를 판정 기록에 넘긴다고 쓰지만 OutputGate → LiveJudgment(또는 SessionRuntime 경유) 간선이 없다. 누가 heldOutputTranscript를 JudgmentLive에 쓰는지 불명하다. | 전달 경로(SessionRuntime 경유 등)를 depends_on에 적는다. | New |
| R-05 | Minor | decisions.md > ADR-005 / components.md > SessionRuntime·PracticeConversation | ADR-005는 연습 흐름이 '다음 행동 지시를 돌려준다'고 쓰지만, 실제 `PracticeSessionExtension`은 반환 값이 아니라 `PracticeOutput` 능력 호출과 `Signal`(NONE/NEXT_GOAL/LAST_GOAL)로 출력하고 CLOSING·정산 창에서 거절하는 구조다. '기존 연습 확장점으로'라는 서술이 이 계약과 다르다. | 기존 능력 호출 모델을 유지할지 반환형 지시로 바꿀지(확장점 계약 변경 여부)를 ADR에 적는다. | New |
| R-06 | Minor | components.md > Seed.sourceCandidateRef ↔ GoalCandidate.skillRef, PracticeGoal ↔ KnowledgeGraph | PracticeGoal → KnowledgeGraph 호출 간선은 단방향이지만 엔티티 참조는 GoalCandidate → Seed, Seed → GoalCandidate로 서로를 가리킨다. 둘 다 ID 값이라 치명적이지 않으나 생성 순서(후보 먼저, 씨앗이 나중)와 삭제 순서가 정해져 있지 않다. | 생성 · 삭제 순서와 참조 무결성 책임을 한 줄로 정한다. | New |
| R-07 | Minor | components.md > ResultPipeline (differs_from_live) / LiveJudgment | 기록용 재판정이 진행 중 판정과 달라 differs_from_live를 남긴다는데, 진행 중 판정 값은 realtime의 JudgmentLive에 있고 core는 realtime 표를 읽을 수 없다(R-F5). 기록 캡처 이벤트의 어떤 필드로 넘어오는지(judgment_live 내용을 어디까지 넘기는지, FR3.6의 비노출과 충돌 없는지)가 없다. | SessionRecordCaptured 이벤트의 판정 관련 필드를 정하고 노출 범위를 적는다. | New |
| R-08 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/domain-design/traceability.json | upstream_ids가 US만 담고 FR/NFR 묶음 머리(FR1 등)나 컴포넌트가 아닌 항목(FR11 계약 · 설정 정비)의 N/A 행이 없다. 프로젝트 교정(traceability에 묶음 머리 ID를 N/A로 넣는다)과 어긋난다. | FR1~FR12 · NFR1~NFR7을 coverage에 대상 컴포넌트 또는 N/A로 추가한다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| 수동 대칭 점검(depends_on ↔ dependents) | 이상 없음 | 13개 컴포넌트의 간선이 양쪽에서 일치한다. 순환 의존도 없다. 단 R-03 · R-04의 선언되지 않은 간선이 있다. |
| 코드 사실 대조 | CandidateRegistry · CandidateScreen · SceneChipMapper · PracticeSessionExtension 모두 존재 | 위치 서술은 맞다. SceneChipMapper의 사용처(저장 · 27 · 리포트)와 확장점 계약은 서술과 어긋난다(R-01 · R-05). |

### Summary

컴포넌트 분해와 두 앱 경계는 건전하다(순환 없음, ADR 4항목 충실). 다만 Q3-A의 접기 위치가 코드 구조와 맞지 않고(R-01), 새 개인정보 보유 엔티티의 삭제 참여(R-02)와 대본 등록 경로(R-03)가 빠져 있어 Major 3건으로 NOT-READY다. 승인 전 위 세 건을 보강하는 것을 권한다.
