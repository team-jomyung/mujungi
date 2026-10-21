## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-20T09:01:44Z
**Iteration:** 2

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/functional-design/functional-spec.md > WF5 단계 4–5, WF6 단계 3 및 Lifecycle State Transitions의 확인ASKING 행 | ASKING은 확인 질문의 유효 재생 완료 때만 WAITING으로 이동하며 그 후에야 20초 창을 연다. 확인 질문의 생성·검수·송신 실패, 재생 만료·중단 때 ASKING을 해제하는 전이가 없다. WF6은 실패 후보를 보류/폐기하므로 질문을 듣지 못한 상태에서 일반 출력 보류만 남을 수 있고, 확인 질문의 공백 시계 제외까지 유지하면 대기에서 벗어날 경로를 구현자가 결정해야 한다. | 확인 질문을 전달하지 못한 각 실패·만료·중단에서 ASKING과 현재 질문 귀속을 해제하고 기존 일반 흐름 재개 또는 기술 정리 중 무엇을 적용할지 결정표에 명시한다. 질문을 반복하거나 미전달 질문의 답을 추정하지 않는 조건과 대역 실패 시험을 함께 추가한다. | Resolved (reviewer) |
| R-02 | Minor | aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u16-service-guardrail/functional-design/functional-spec.md > WF7 단계 4 | WF7은 CLOSING에서 PLAYING을 해제한다고 명시하지만 WF5 단계 7은 승인 인사 STARTED를 확인한 후 PLAYING을 내보내도록 한다. 상위 계약의 goodbye 의미와 RP-4는 실제 인사가 재생 중인 마무리 구간에서만 해당 표시를 허용하므로, CLOSING 진입 시 무조건 숨기는지 현재 인사를 관측하는지 두 해석이 생긴다. | CLOSING 진입 시 이전 재생 사실을 해제하는 것과 현재 승인 인사 STARTED 뒤의 PLAYING을 구별한다. 현재 인사의 완료·중단·실패·단절·만료 또는 최종 종료에서 해제하도록 WF5/WF7의 설명과 조회 시험을 일치시킨다. | Resolved (reviewer) |

> R-01 Reviewer note: functional-spec.md WF5의 실패 결정표와 Lifecycle State Transitions, rules.md BR4.2에 질문 자원/생성·검수·송신/재생 실패·만료·중단의 귀속 해제가 추가됐다. Q1-A대로 정상 ACTIVE에서는 자동 발화 없이 듣기로 복귀하고 지속 장애는 기존 기술 일시정지/정리로 연결한다. 다른 종료·휴식 보류를 해제하지 않으며 늦은 완료/답 무효화와 실패 원인별 대역 시험도 명시했다. 설계 누락이 보완됐다는 판정이며 구현 합격은 아니다.

> R-02 Reviewer note: WF7 단계 4와 BR5.4가 CLOSING 진입의 이전 재생 해제와 현재 승인 인사 STARTED 이후의 PLAYING을 구별한다. 현재 인사의 완료/중단/실패·단절/만료 또는 최종 종료 때 해제하도록 상태표와 조회 시험 인계도 일치시켰다.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Python/PyYAML 모델·규칙 검사 | PASS: 엔티티 5개/값 객체 2개, 식별 속성/관계/참조 해소, 규칙 25개 고유 | 이번 보완으로 모델 참조가 깨지지 않았고 BR4.2/BR5.4의 판단 의미가 수정된 동작 명세와 일치한다. |
| Python AC/BR 추적 대조 | PASS: AC 40개가 선언 규칙에 연결되고 규칙 orphan 없음 | 기존 추적이 유지된다. 추적 OK는 코드·실제 자원·실제 공급자 동작의 검증 결과가 아니다. |
| Q1-A·공통 계약·수정된 실패/인사 상태표 수동 대조 | PASS | 정상 듣기 복귀와 지속 장애 정리가 사람의 선택에 맞고, 종료/타 보류의 권위와 현재 인사의 실제 재생 관측을 보존한다. |

### Summary

이전 두 finding은 이번 기능 설계에서 보완됐으며 신규 finding은 없다. 실제 안내 음성 확보와 User Stories에서 인계한 로컬/J6 요청 경계 시험·코드/실기기 검증은 여전히 후속 작업이다.
