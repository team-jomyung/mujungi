## Review

**Verdict:** READY
**Reviewer:** aidlc-product-lead-agent
**Date:** 2026-10-17T18:11:40Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261017-u12-practice-flow/inception/requirements-analysis/requirements.md > FR3.3 · NFR1 | 새 확장이 허용한 역할 발화가 제브 `ALLOW` 뒤에만 재생된다고 했지만, 두 가지가 비어 있다. (1) 코드 `SessionExecutor` 3004 ~ 3013줄은 연습 통제 중 `pendingPracticeSpeech`와 원문이 같지 않으면 모두 폐기하므로, '확장이 허용한 발화'를 실행부가 무엇으로 식별하는지(허용 창 · 발화 종류 표지)가 요구에 없다. (2) 같은 구간의 제브 분기는 `guardrail.enabled()`가 거짓이면 검사 없이 지나는 모양이어서, 제브가 꺼졌거나 호출이 실패할 때 새 확장 아래 역할 발화가 재생되는지 폐기되는지 정해져 있지 않다. NFR1의 '자동 통과 없음'이 이 경로에서 시험 불가능하다. | FR3.3에 새 확장 아래 역할 발화의 식별 조건과, 제브 비활성 · 오류 · 시간 초과일 때의 동작(검사 없이 재생하지 않고 폐기 또는 재지시로 닫힘)을 한 문장씩 더하고 각각의 합격 기준을 단다. | New |
| R-02 | Major | aidlc/spaces/default/intents/261017-u12-practice-flow/inception/requirements-analysis/requirements.md > FR4.2, 머리말 출처 순서, 편차 `U12-D1` | 정본 AC1.5.5는 도움의 '내용'을 기록하라고 하는데, Q3(A)에 따라 이 문서는 '분류 코드와 길이만' 저장한다. 사람이 정본과 다른 쪽을 고른 것이므로 project.md 교정대로 편차로 적어야 하나, 문서는 `U12-D1`(AC1.1.2)만 편차로 이름 붙이고 이 차이는 편차 번호 없이 FR4.2 본문에만 있다. 이대로면 뒤 단계가 AC1.5.5의 '내용'을 되살려 원문을 저장할 위험이 있다. | AC1.5.5의 '내용'을 코드 · 길이로 좁힌 것을 편차 `U12-D2`로 FR4.2에 명시하고 이후 traceability의 AC1.5.5 행에 표시하게 한다. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261017-u12-practice-flow/inception/requirements-analysis/requirements.md > FR2.7 | 변형 제안의 4조건을 '쓸 수 있는 변형 · 시간 · 참여 의사 · 목표당 ACTIVE 10분 전'이라 적었는데, 정본 AC1.6.1의 조건은 변형 장면 있음 · 거절 / 휴식 / 종료 요청 없음 · ACTIVE 10분 전 · 집중 연습이 AC1.5.4 한도 이내다. '시간'이 무엇인지 모호하고 집중 한도 조건이 4조건에서 빠져 있다(뒤 문장에서 따로 다룸). | 4조건을 정본 문구대로 다시 적고 '시간'을 지우거나 정의한다. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261017-u12-practice-flow/inception/requirements-analysis/requirements.md > 가정 A2 (그리고 지시서 `docs/units/u12/brief.md`의 '31개') | 'AC 31개'라고 하나 US1.1 ~ US1.6 · US1.9의 실제 AC는 29개다(5 + 5 + 2 + 4 + 6 + 4 + 3). 지시서의 숫자를 그대로 옮긴 것으로 보이며, 덮임 검사에서 2개가 빈 것처럼 보이게 한다. 29개 모두 FR2 ~ FR5에 덮이는 것은 확인했다. | 29개로 고치거나 31의 출처(포함된 다른 AC)를 밝힌다. | New |
| R-05 | Minor | aidlc/spaces/default/intents/261017-u12-practice-flow/inception/requirements-analysis/requirements.md > NFR2 | '도움 내용 원문 · 전사 · AI 요청 원문을 로그에 남기지 않는다'의 판정이 '기록 표 칸 시험, 로그 위생(P2)'이라고만 되어 있어 통과 기준이 없다. | 표식 문자열을 넣은 가짜 대화를 돌린 뒤 로그 · 기록 · 지표 어디에도 그 문자열이 없다는 식으로 단언을 적는다. | New |
| R-06 | Minor | aidlc/spaces/default/intents/261017-u12-practice-flow/inception/requirements-analysis/requirements.md > FR6.1 | 새 단계 10개 중 7개(`SCENE_INTRO` · `OPPORTUNITY` · `REASK` · `HELP_OFFER` · `FOCUS` · `RETRY` · `VARIANT`)의 접기 대응은 질문 Q4에만 있고, `SCENE_WRAP` · `NEXT_GOAL` · `CLOSING`의 대응은 어디에도 없다. 시험이 '모든 단계가 6값 중 하나'만 보므로 틀린 대응도 통과한다. | 단계별 대응표를 FR6.1에 두고, 표에 없는 세 단계의 접기를 정한다(또는 Functional Design에 넘긴다고 명시). | New |

### Summary

코드 사실(실행부 폐기 분기 3004 ~ 3013줄, `announceRoleSwitch` 빈 틀, 시작 API의 `FREE_TALK` 한정, `Progress.stage` 6값)과 시간 값(10초 · 90초 · 10분, `timing.md` 13절 · `application.yml`)은 정본과 맞고, AC 29개가 FR2 ~ FR5로 빠짐없이 덮이며 Q1 ~ Q5 답과의 모순은 없다. 열린 질문과 범위 밖도 실호출 · 실기기를 사실처럼 쓰지 않아 정직하다. 다만 새 확장의 출력 통제(FR3.3)가 제브 비활성 · 식별 방식을 정하지 않아 안전 요구 NFR1을 시험하기 어렵고(R-01), AC1.5.5 개정이 편차로 남지 않았다(R-02). Major 2개로 READY 기준 안이지만 사람이 승인 전에 이 둘을 반영하는 것을 권한다.
