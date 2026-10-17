## Review

**Verdict:** READY
**Reviewer:** aidlc-product-lead-agent
**Date:** 2026-10-16T06:56:47Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261016-u11-judgment-turn/inception/requirements-analysis/requirements.md > FR6 (FR6.1 ~ FR6.4), 범위 밖, 열린 질문 4 | 원 FR3.5의 "명시적인 도움 · 휴식 · 종료 요청은 서버가 바로 처리한다"와 AC1.7.4의 "종료 처리는 제브 결과와 관계없이 서버가 즉시 한다"가 FR로 다시 묶이지 않았고 범위 밖에도 없다. FR6은 종료 표현만 판정 없이 처리한다. 휴식 표현이 J6에 어떻게 들어가는지도 열린 질문 4로 미뤘다. FR6.3은 J6을 `StopIntentJudge` CONFIRM일 때만 여는데, 휴식 뜻 발화가 CONFIRM이 아니면 FR6.4(휴식 처리)에 닿지 않는다. | (a) 명시적 도움 · 휴식 요청의 즉시 처리를 이 단위가 하는지, U12 · U13 몫인지 FR 또는 범위 밖에 한 줄로 적는다. (b) AC1.7.4의 서버 즉시 종료(제브 결과와 무관)를 FR6.1에 완료 기준으로 더하거나 U13으로 넘긴다고 적는다. (c) 휴식 발화가 J6에 도달하는 경로를 열린 질문이 아니라 요구사항으로 정한다. 코드에 휴식 사전이 없으면 그 사실과 해결 방식을 적는다. | New |
| R-02 | Major | aidlc/spaces/default/intents/261016-u11-judgment-turn/inception/requirements-analysis/requirements.md > FR4.1, FR5.2 | 10초 응답 준비의 시작 · 끝 · 적용 범위가 정해지지 않았다. "아이 말이 끝난 뒤"가 판정 턴에만 적용되는지, 판정 없이 GPT-Live가 자동 응답하는 일반 턴에도 적용되는지 알 수 없다. 응답 준비의 기준(첫 음성 청크, 지시 뒤 응답 완료, 게이트 통과)도 없다. 10초 초과로 보류 지시를 낸 뒤 원래 응답이 늦게 도착하면 어떻게 하는지 정하지 않았다. FR5.2의 늦은 결과 버리기는 판정과 전사만 말해서, 이 경우 같은 차례에 되묻기와 원래 응답이 겹쳐 들릴 수 있다. 완료 기준이 "보류 기록 1건과 지시 1건"뿐이라 QA가 이 경계를 시험할 수 없다. | 10초의 기준 시점과 "준비됨" 조건을 정하고, 적용 턴 범위를 적는다. 10초 초과 뒤 도착한 원래 응답을 버리는지 보류하는지 FR5.2에 더하고, 완료 기준에 "초과 뒤 도착한 응답은 재생되지 않는다"를 넣는다. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261016-u11-judgment-turn/inception/requirements-analysis/requirements.md > FR6.5, 열린 질문 2 | FR6.5의 완료 기준 "두 경로의 종료 기록이 서로 다른 근거 값을 갖는다"는 값이 어디에 담기는지 정하지 않아 시험할 수 없다. 이벤트나 계약 변경이 필요한지는 Functional Design 첫 작업으로 미뤘다. 이 결정은 `contracts/` 변경(R-M10)과 편차 `(U11-a)`를 일으킬 수 있는 숨은 결정이다. 코드 사실로는 `endReason` 값 집합이 `contracts/events.asyncapi.yaml`에 이미 있고, 사유 줄과 따옴표 문구를 나누는 필드는 확인되지 않았다. | Should 우선순위는 유지하되, 시험 가능한 위치(예: 세션 행의 `end_detail` 또는 이벤트 필드)를 후보로 적고, 계약 변경이 필요하면 FR11.2의 편차 항목과 연결한다. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261016-u11-judgment-turn/inception/requirements-analysis/requirements.md > 열린 질문 5, FR1.6 | 실제 아동 전사를 Luna로 보내는 것은 R-F15(NEVER) 대상이다. 그런데 "R-F15 확인 기록 전에는 키를 넣지 않는다"가 열린 질문 안에만 있고 FR이나 제약에 없다. FR1.6의 "키가 없으면 보류"는 안전한 기본값이지만, 키가 설정됐을 때 R-F15 확인이 끝났는지는 검사하지 않는다. | 제약 절에 "R-F15 확인 기록(RUNBOOK 단계 4)이 없으면 운영 키를 설정하지 않는다"를 규칙으로 옮기고, 단위 보고서(report.md)에 확인 여부를 적는다. | New |
| R-05 | Minor | aidlc/spaces/default/intents/261016-u11-judgment-turn/inception/requirements-analysis/requirements.md > FR1.3 완료 기준 | 보류 시험 벡터 `(0.59, 0.10)` · `(0.70, 0.60)` · `(0.70, 0.40)`에서 괄호 안 두 숫자가 무엇인지 적혀 있지 않다. 두 번째 숫자가 2순위 확률인지 차이인지에 따라 결과가 달라진다. 2순위 확률로 읽어야 (0.70, 0.60)이 차이 0.10이라 보류가 되고, (0.70, 0.40)이 차이 0.30이라 확정이 된다. | "(1순위 확률, 2순위 확률)"로 표기를 밝히고, 경계 벡터로 (0.60, 0.45) 확정 · (0.60, 0.46) 보류를 하나 더한다. | New |
| R-06 | Minor | aidlc/spaces/default/intents/261016-u11-judgment-turn/inception/requirements-analysis/requirements.md > FR9.1, FR9.2, A3 | Q1 B로 실호출 확인이 Code Generation 첫 단계로 밀려, 지시서의 중단 조건 위치가 바뀌었다. 사람이 결정했고 A3에 편차로 적혀 있어 문제는 아니다. 다만 FR9.2에서 중단이 나면 FR1 ~ FR8의 설계가 버려질 수 있다는 위험이 열린 질문 1에는 있어도 FR9에 없다. | FR9.2에 "중단 시 Functional Design 산출물은 보존하고 ADR-007 재설계 입력으로 쓴다"를 한 줄 더한다. 선택 사항이다. | New |

### Summary

원래 기록의 U11 몫(FR3.1 ~ 3.7, FR4.8, FR5.1 ~ 5.4, AC1.8.x · AC1.10.x · AC2.5.1)은 대부분 다시 묶였고, 사람 답(Q1 ~ Q5-1과 가정 12건)과 어긋나는 곳, 팀 규칙(R-F1 · R-F2 · R-F5 · R-F7 · R-F14 · R-M5 · R-M10 · R-M11)과 부딪히는 곳은 확인하지 못했다. 보강할 것은 두 가지다. 하나는 AC1.7.4와 원 FR3.5의 도움 · 휴식 · 종료 즉시 처리가 FR이나 범위 밖에서 빠진 것(R-01)이고, 다른 하나는 10초 응답 준비의 경계와 초과 뒤 늦게 오는 응답의 처리가 비어 있는 것(R-02)이다. 둘 다 승인 전에 요구사항에 적을 수 있고 Functional Design으로 미뤄도 큰 재작업은 생기지 않는다.
