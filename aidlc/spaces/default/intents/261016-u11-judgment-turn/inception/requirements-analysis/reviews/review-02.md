## Review

**Verdict:** READY
**Reviewer:** aidlc-product-lead-agent
**Date:** 2026-10-17T00:25:13Z
**Iteration:** 1

이번 검토는 advisory 단일 패스다. 사람의 답 Q6 A, Q7 A, Q8 A, Q9 A, Q10 A, Q11 B, Q12 A는 FR12, FR13, FR1.4, FR3, NFR1, 제약, 범위 밖에 충실히 반영돼 있다. 판정 시한은 4초로 일관되고, "2초"는 편차를 설명하는 문장과 A5 가정에만 남아 있다. 아래 두 Major는 승인 전에 사람이 판단할 만한 항목이다.

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261016-u11-judgment-turn/inception/requirements-analysis/requirements.md > FR6 (FR6.1 ~ FR6.4), 범위 밖, 열린 질문 4 | 원 FR3.5의 "명시적인 도움 · 휴식 · 종료 요청은 서버가 바로 처리한다"와 AC1.7.4의 "종료 처리는 제브 결과와 관계없이 서버가 즉시 한다"가 FR로 다시 묶이지 않았고 범위 밖에도 없다. FR6은 종료 표현만 판정 없이 처리한다. 휴식 표현이 J6에 어떻게 들어가는지도 열린 질문 4로 미뤘다. FR6.3은 J6을 `StopIntentJudge` CONFIRM일 때만 여는데, 휴식 뜻 발화가 CONFIRM이 아니면 FR6.4(휴식 처리)에 닿지 않는다. | (a) 명시적 도움 · 휴식 요청의 즉시 처리를 이 단위가 하는지, U12 · U13 몫인지 FR 또는 범위 밖에 한 줄로 적는다. (b) AC1.7.4의 서버 즉시 종료(제브 결과와 무관)를 FR6.1에 완료 기준으로 더하거나 U13으로 넘긴다고 적는다. (c) 휴식 발화가 J6에 도달하는 경로를 열린 질문이 아니라 요구사항으로 정한다. 코드에 휴식 사전이 없으면 그 사실과 해결 방식을 적는다. | Accepted risk |
| R-02 | Major | aidlc/spaces/default/intents/261016-u11-judgment-turn/inception/requirements-analysis/requirements.md > FR4.1, FR5.2 | 10초 응답 준비의 시작 · 끝 · 적용 범위가 정해지지 않았다. "아이 말이 끝난 뒤"가 판정 턴에만 적용되는지, 판정 없이 GPT-Live가 자동 응답하는 일반 턴에도 적용되는지 알 수 없다. 응답 준비의 기준(첫 음성 청크, 지시 뒤 응답 완료, 게이트 통과)도 없다. 10초 초과로 보류 지시를 낸 뒤 원래 응답이 늦게 도착하면 어떻게 하는지 정하지 않았다. FR5.2의 늦은 결과 버리기는 판정과 전사만 말해서, 이 경우 같은 차례에 되묻기와 원래 응답이 겹쳐 들릴 수 있다. 완료 기준이 "보류 기록 1건과 지시 1건"뿐이라 QA가 이 경계를 시험할 수 없다. | 10초의 기준 시점과 "준비됨" 조건을 정하고, 적용 턴 범위를 적는다. 10초 초과 뒤 도착한 원래 응답을 버리는지 보류하는지 FR5.2에 더하고, 완료 기준에 "초과 뒤 도착한 응답은 재생되지 않는다"를 넣는다. | Accepted risk |
| R-03 | Minor | aidlc/spaces/default/intents/261016-u11-judgment-turn/inception/requirements-analysis/requirements.md > FR6.5, 열린 질문 2 | FR6.5의 완료 기준 "두 경로의 종료 기록이 서로 다른 근거 값을 갖는다"는 값이 어디에 담기는지 정하지 않아 시험할 수 없다. 이벤트나 계약 변경이 필요한지는 Functional Design 첫 작업으로 미뤘다. 이 결정은 `contracts/` 변경(R-M10)과 편차 `(U11-a)`를 일으킬 수 있는 숨은 결정이다. 코드 사실로는 `endReason` 값 집합이 `contracts/events.asyncapi.yaml`에 이미 있고, 사유 줄과 따옴표 문구를 나누는 필드는 확인되지 않았다. | Should 우선순위는 유지하되, 시험 가능한 위치(예: 세션 행의 `end_detail` 또는 이벤트 필드)를 후보로 적고, 계약 변경이 필요하면 FR11.2의 편차 항목과 연결한다. | Accepted risk |
| R-04 | Minor | aidlc/spaces/default/intents/261016-u11-judgment-turn/inception/requirements-analysis/requirements.md > 열린 질문 5, FR1.6 | 실제 아동 전사를 Luna로 보내는 것은 R-F15(NEVER) 대상이다. 그런데 "R-F15 확인 기록 전에는 키를 넣지 않는다"가 열린 질문 안에만 있고 FR이나 제약에 없다. FR1.6의 "키가 없으면 보류"는 안전한 기본값이지만, 키가 설정됐을 때 R-F15 확인이 끝났는지는 검사하지 않는다. | 제약 절에 "R-F15 확인 기록(RUNBOOK 단계 4)이 없으면 운영 키를 설정하지 않는다"를 규칙으로 옮기고, 단위 보고서(report.md)에 확인 여부를 적는다. | Accepted risk |
| R-05 | Minor | aidlc/spaces/default/intents/261016-u11-judgment-turn/inception/requirements-analysis/requirements.md > FR1.3 완료 기준 | 보류 시험 벡터 `(0.59, 0.10)` · `(0.70, 0.60)` · `(0.70, 0.40)`에서 괄호 안 두 숫자가 무엇인지 적혀 있지 않다. 두 번째 숫자가 2순위 확률인지 차이인지에 따라 결과가 달라진다. 2순위 확률로 읽어야 (0.70, 0.60)이 차이 0.10이라 보류가 되고, (0.70, 0.40)이 차이 0.30이라 확정이 된다. | "(1순위 확률, 2순위 확률)"로 표기를 밝히고, 경계 벡터로 (0.60, 0.45) 확정 · (0.60, 0.46) 보류를 하나 더한다. | Accepted risk |
| R-06 | Minor | aidlc/spaces/default/intents/261016-u11-judgment-turn/inception/requirements-analysis/requirements.md > FR9.1, FR9.2, A3 | Q1 B로 실호출 확인이 Code Generation 첫 단계로 밀려, 지시서의 중단 조건 위치가 바뀌었다. 사람이 결정했고 A3에 편차로 적혀 있어 문제는 아니다. 다만 FR9.2에서 중단이 나면 FR1 ~ FR8의 설계가 버려질 수 있다는 위험이 열린 질문 1에는 있어도 FR9에 없다. | FR9.2에 "중단 시 Functional Design 산출물은 보존하고 ADR-007 재설계 입력으로 쓴다"를 한 줄 더한다. 선택 사항이다. | Accepted risk |

> R-01 Reviewer note: 키 이름을 통일하면 지금까지 Luna용으로만 R-F15 확인을 거친 `OPENAI_API_KEY`가 실제 아동 음성 · 전사를 GPT-Live로 보내는 키도 겸하게 된다. `infra/RUNBOOK.md`의 (U9) 단계는 Luna 어댑터만 켜는 것으로 R-F15 확인 기록을 요구한다. 그런데 요구사항에는 "GPT-Live 실제 어댑터를 켜려면 GPT-Live 몫의 R-F15 확인이 따로 필요하다"는 통과/실패 기준이 없고, 열린 질문 7에 서술만 있다. 이미 Luna용으로 키를 넣은 환경은 배포만 해도 GPT-Live가 실제로 켜질 수 있다.; Required action: FR13.1에 완료 기준을 추가한다. 예를 들어 "GPT-Live 실제 어댑터는 R-F15 확인 기록(RUNBOOK 단계 4)이 GPT-Live 음성 중계까지 적은 뒤에만 켜진다. 기록이 없는 환경에서는 같은 키가 있어도 GPT-Live는 가짜로 남는다" 중 하나를 정하고, 어느 방식인지 확인하는 시험을 적는다. 이 구분이 설계로 넘어간다면 그 사실을 열린 질문에 명시한다.

> R-02 Reviewer note: FR13.1은 "저장소(작업 기록 · 지난 기록 제외)에 `PROVIDER_API_KEY`가 0건"을 기준으로 삼는다. 그러나 FR13.2는 RUNBOOK에 Parameter Store 이름 변경 절차를 적게 하는데, 그 절차는 옛 이름을 언급해야 한다. 저장소에는 `infra/ci/invariant-samples/bad-inline-secret.yml`, `contracts/ai-io.md`(편차 표) 등 옛 이름이 남을 수 있는 파일도 있다. 두 기준이 서로 충돌하고, "지난 기록"의 범위(`docs/units/*/report.md` 포함 여부)도 불분명하다. 또 PR1을 `main`에 합치고 배포하면 호스트의 `/mujoong/demo/PROVIDER_API_KEY`와 새 이름이 어긋난다. 이름 변경과 배포의 선후 요구가 없다.; Required action: 0건 기준에 예외를 명시한다. 예를 들어 "RUNBOOK 이름 변경 절차의 옛 이름 언급과 편차 표의 대체 표시는 허용한다". 제외 경로도 정한다. 또 "호스트 Parameter Store 이름 변경은 PR1 배포 전에 끝나야 한다" 같은 선후 조건, 또는 옛 이름 호환 여부를 FR13.2나 제약에 한 줄로 적는다.

> R-03 Reviewer note: 남은 중단 조건("출력 전사 사건이 오지 않으면 멈춘다")은 FR12.5, FR12.8, FR3.1의 전제다. 그런데 PR1이 FR12.7의 합성 말소리 실호출 결과가 나오기 전에 `main`에 합쳐져도 되는지가 적혀 있지 않다.; Required action: 제약이나 FR12.7에 "FR12.7 실호출 결과(전사 · 말 끝 · 24kHz)가 단위 보고서에 남은 뒤에 PR1을 합친다"를 한 줄 더한다. 또는 합쳐도 되는 이유를 적는다.

> R-04 Reviewer note: 4초 경계가 3.999초(확정)까지만 정해져 있고 정확히 4.000초는 정해져 있지 않다. 판정 4초와 응답 준비 10초의 관계(판정 시간이 10초 안에 포함되는지)도 적혀 있지 않다. FR1.3은 경계값을 명시하므로 일관성이 떨어진다.; Required action: 4.000초가 보류인지 확정인지 한 문장으로 정한다. FR4.1의 10초가 판정 시간을 포함하는지도 적는다.

> R-05 Reviewer note: 변환 경로가 선택될 때의 편차 번호가 `(U11-x)` 자리표시자로 남아 있고, FR12.10은 `(U11-a)`부터라고만 적는다. 설계 문서의 `U11-Dn` 편차와 계약 편차 `(U11-a)` 계열이 별개 체계라는 설명도 없다.; Required action: `(U11-x)`를 "다음 빈 `(U11-?)` 번호"로 바꾸고, 두 편차 체계(설계 `U11-Dn`, 계약 `(U11-a)`)를 한 줄로 구분한다.

### Summary

사람의 답 일곱 건은 FR1.4, FR3, FR12, FR13, NFR1, 제약, 범위 밖에 충실히 반영됐고, 시한 4초와 원 ID 추적도 일관돼 구현을 시작할 수 있는 수준이다. 다만 키 통일에 따른 GPT-Live 실제 켜짐의 R-F15 경계(R-01)와 `PROVIDER_API_KEY` 0건 기준의 자기모순 및 배포 선후(R-02)는 승인 전에 확인하기를 권한다. Critical은 없고 Major가 2건이라 판정은 READY다.
