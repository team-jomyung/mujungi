## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-20T08:47:11Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/functional-design/functional-spec.md > WF5 단계 4–5, WF6 단계 3 및 Lifecycle State Transitions의 확인ASKING 행 | ASKING은 확인 질문의 유효 재생 완료 때만 WAITING으로 이동하며 그 후에야 20초 창을 연다. 확인 질문의 생성·검수·송신 실패, 재생 만료·중단 때 ASKING을 해제하는 전이가 없다. WF6은 실패 후보를 보류/폐기하므로 질문을 듣지 못한 상태에서 일반 출력 보류만 남을 수 있고, 확인 질문의 공백 시계 제외까지 유지하면 대기에서 벗어날 경로를 구현자가 결정해야 한다. | 확인 질문을 전달하지 못한 각 실패·만료·중단에서 ASKING과 현재 질문 귀속을 해제하고 기존 일반 흐름 재개 또는 기술 정리 중 무엇을 적용할지 결정표에 명시한다. 질문을 반복하거나 미전달 질문의 답을 추정하지 않는 조건과 대역 실패 시험을 함께 추가한다. | New |
| R-02 | Minor | aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/functional-design/functional-spec.md > WF7 단계 4 | WF7은 CLOSING에서 PLAYING을 해제한다고 명시하지만 WF5 단계 7은 승인 인사 STARTED를 확인한 후 PLAYING을 내보내도록 한다. 상위 계약의 goodbye 의미와 RP-4는 실제 인사가 재생 중인 마무리 구간에서만 해당 표시를 허용하므로, CLOSING 진입 시 무조건 숨기는지 현재 인사를 관측하는지 두 해석이 생긴다. | CLOSING 진입 시 이전 재생 사실을 해제하는 것과 현재 승인 인사 STARTED 뒤의 PLAYING을 구별한다. 현재 인사의 완료·중단·실패·단절·만료 또는 최종 종료에서 해제하도록 WF5/WF7의 설명과 조회 시험을 일치시킨다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Python/PyYAML 엔티티·규칙 구조 검사 | PASS: 엔티티 5개·값 객체 2개 이름 고유, 엔티티 식별 속성/관계/속성 참조 해소, 규칙 25개 ID 고유 및 필수 항목 존재 | 구조상 소유·참조 누락은 없다. 상태 전이 실패 경로와 문장 간 의미 충돌은 별도 Findings에 기록했다. |
| Python AC/BR 추적 대조 | PASS: AC 40개가 선언된 규칙으로 연결되고 규칙 25개 모두 연결됨 | U16의 협력 책임까지 추적되며 U17 표시 결과 또는 구현 합격을 대신하지 않는다. |
| 상위 요구·Unit 경계·공통 계약 수동 대조 | 확인: 실제 말소리 자원 검증, 조기 입력 시작 귀속, 원자적 참여/사실 기록, 후보 현재성 재확인, 기존 사건 수명/삭제와 fallback/UNKNOWN 반영 | 기존 톤/무음은 실제 안내 완료로 승격하지 않으며 실제 자원 미준비를 명시했다. 종료 로컬/J6 실제 요청 시험은 구체적 후속 검증으로 남아 있고 수행 완료로 주장하지 않는다. |

### Summary

단일 상태 소유와 실제 안내·참여·출력·사실 수명의 설계는 상위 계약에 부합한다. 확인 질문의 실패 후 전이를 보완하고 CLOSING의 실제 인사 관측 의미를 통일할 필요가 있으며, 현재 열린 사항은 Major 1개와 Minor 1개다.
