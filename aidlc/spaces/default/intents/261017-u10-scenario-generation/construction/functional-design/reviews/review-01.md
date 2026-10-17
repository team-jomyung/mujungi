## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-17T02:43:30Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | docs/units/u10/design.md > 6. 관심사·개인정보; aidlc/spaces/default/intents/261017-u10-scenario-generation/construction/functional-design/functional-spec.md > W6 입력·판·관측 | 이름 별칭을 Core 내부에서 얻어 관심사와 목표 문자열을 치환한다고 했으나, 어느 소유 경계에서 얻어 생성·검토에 전달하는지 없다. 실제 PracticeAccountPort.AccountContext에는 guardianId·childId·ageYears·interests만 있고 U2PracticeAccountAdapter.requireActive는 ChildProfileService.ChildView.displayName을 전달하지 않는다. 현재 LunaInputSanitizer는 받은 별칭 표만 치환하므로 이 경로를 그대로 재사용하면 실제 아이 이름이 포함된 관심사나 목표 문자열을 지우지 못한다. | ChildProfileService 등 권한 확인된 아동 정보 소유 경계에서 별칭을 만드는 내부 읽기 경계와 생성·검토 요청까지의 전달을 명시한다. 별칭을 얻지 못한 경우의 안전한 처리, 새 필드의 비공개·비로그 성격, 알려진 이름이 관심사·목표·이전 상황에 들어간 경우의 대조 시험을 정한다. 공개 응답이나 AI 입력에 별칭 원본을 싣지 않는다. | New |
| R-02 | Major | aidlc/spaces/default/intents/261017-u10-scenario-generation/construction/functional-design/functional-spec.md > W2 상황 생성·검토·발급 2·4·5, W6; rules.md > BR1.3 | 의미상 반복 검토에 필요한 '최소 이전 상황'을 어디서 어떤 범위로 읽는지 없다. W2의 유일하게 명시된 읽기는 NEW_SCENARIO의 previousScenarioId 원본 한 건이고 ORIGINAL 요청에는 그 값이 없다. 현재 ScenarioService도 같은 구조이며 ScenarioRepository.seen은 지문 존재 여부만 반환한다. 따라서 이전 세션에서 이미 노출된 상황이 있는 ORIGINAL 요청에서도 의미 반복을 검토한다는 FR1.3·AC3.1.9를 구현자가 기존 경로만으로 충족할 수 없다. | 같은 아이의 권위 있는 실제 EXPOSED 이력에서 비교용 최소 본문을 얻는 읽기 경계, 선택 범위·안정적 정렬·건수/문자 상한, 빈 이력과 읽기 실패 처리, 전체 마감에 포함하는 위치를 명시한다. ORIGINAL과 NEW_SCENARIO 모두 적용하고 NEW의 지정 원본과 노출 이력을 구분한다. 미노출 ISSUED·다른 아이의 자료 제외 및 과거 세션의 의미 반복 검토 입력 전달 시험을 정한다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| PowerShell traceability JSON 및 BR 참조 대조 | PASS: upstream 57, coverage 57, BR 18, invalidTargets 0, uncovered 0, orphans 0 | 구조적 추적 누락은 없다. 의미 반복 입력 경계의 실질적 누락은 추적 행 존재만으로 검출되지 않는다. |
| 계정·개인정보 경계 소스 대조 | GAP: PracticeAccountPort.AccountContext 및 U2PracticeAccountAdapter에 별칭 전달 없음 | 첫 번째 보완 근거다. ChildProfileService.ChildView에는 displayName이 있으므로 기존 소유 서비스 내부를 이용하는 보완이 가능하다. |
| 기존 생성·저장 경계 소스 대조 | GAP: ScenarioService는 지정 previous ORIGINAL만 읽고 ScenarioRepository.seen은 boolean만 반환 | 두 번째 보완 근거다. 새 이력 조회를 구체화해야 과거 노출과의 의미 반복을 검토할 수 있다. |
| C8 및 기존 U5b 수신 경계 대조 | 확인: internal-session-start-request.schema.json의 behaviorKey는 문자열이고 RuntimePracticeExtension은 값을 SessionGoal로 전달한다 | candidate 운반 식별자를 행동 카탈로그에 임의 삽입하지 않는 설계는 현재 읽은 수신 경계와 모순되지 않는다. 실제 직렬화·왕복 시험은 구현 검증 대상이다. |
| 기존 서버 검사·레거시 투영 대조 | 확인: ScenarioContentPolicy.valid는 정규화된 본문에 기회 조건·트리거가 포함되어야 함; W4는 기존 목표의 이 규칙을 유지하도록 명시 | 첫 BASE 투영 때에도 이 조건을 실제로 만족시키고 validation을 서버 검사 결과로 만들도록 구현해야 한다. 현재 설계가 검사를 제거하거나 모델 플래그로 대체하지는 않는다. |

### Summary

Critical 0건, Major 2건으로 검토 기준상 READY다. 별도 검토와 BASE-only 재검토, 요청 전체 15초·그래프 합계 2초, 재시도 소유권, 선택 컬럼 V10, 목적별 관심사 허용 및 레거시 경계를 명시했으나, 이름 별칭 전달과 실제 노출 이력 입력의 두 읽기 경계는 구현 전에 구체화해야 한다. 이 판정은 구현·시험 통과나 실제 AI 품질·지연 측정의 증거가 아니다.
