## Review

**Verdict:** READY
**Reviewer:** aidlc-product-lead-agent
**Date:** 2026-10-13T07:37:26Z
**Iteration:** 1

이 검토는 승인 게이트용 자문(advisory) 1회다. 사람이 승인 전에 따져 볼 것만 심각도 순으로 적는다.

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261013-u4b-activity-realtime/inception/requirements-analysis/requirements.md > FR9.1, FR7.1, FR8.1 (맞물리는 곳 3건) | `docs/units/u5b/brief.md`의 「U4b와 맞물리는 곳」 중 두 가지가 U4b 요구사항에 반영되지 않았다. (a) 항목 4: 연습 중 CHILD_STOP · SILENCE · DISCONNECT로 끝나면 남은 목표를 NOT_REACHED로 정산하는데, 이 정산이 FR7.1 정정 순서(공급자 닫기 → 기록기 비우기 → 스냅숏)의 어디에 들어가는지 FR9.1에도 열린 질문에도 없다. 확장점은 "ACTIVE 진입 위임"과 "목표 끝 신호" 둘뿐이라, 종료 시 정산 훅이 없으면 U5b가 SessionExecutor를 다시 고쳐야 한다. (b) U5b brief는 Realtime 연습 삭제 참가자가 "세션 행보다 먼저" 들어가도록 U4b 삭제 순서에 맞춘다고 했는데, FR8.1의 참가자 순서(음성 → 세션 → 메시징)에는 연습 참가자 자리나 등록 방식이 없다. | FR9.1에 "종료 정산 직전 연습 정산 훅(남은 목표 NOT_REACHED, record.practice 채움)"을 확장점 범위로 명시하거나, 최소한 열린 질문 · A5에 올려 Functional Design에서 U5b와 맞출 항목으로 못박는다. FR8.1에도 세션 참가자보다 앞에 연습 참가자를 끼울 수 있는 순서 · 등록 방식을 한 줄로 적는다. | New |
| R-02 | Major | aidlc/spaces/default/intents/261013-u4b-activity-realtime/inception/requirements-analysis/requirements.md > FR6.1, FR7.1, FR2.3 | 설계에 있으나 요구사항에 빠진 U4b 의무가 있어 구현자가 설계 문서를 따로 뒤져야 한다. ① 설계 7절: SessionStateChanged는 전이 시점에 일시정지 중이면 technicalPause를 싣는다(FR6.1은 진행 조회만 다룬다). ② 설계 6절: `realtime_activity_session.guardian_id` 색인(삭제 처리기 · 강제 정지 조회용)이 V3에 들어간다(FR7.1은 새 표만 적었다). ③ 설계 R3: 대화 지시문에 "참여가 확인되면 관심사나 일상 질문 하나로 시작한다"를 더한다(FR2.3에 없다). ④ 설계 J22: 새 시계 · 판정을 SessionEvent(JOIN_* · STOP_CONFIRM_* · SILENCE_* · TECHNICAL_PAUSE_* · RECORD_CAPTURED)로 남기고 payload에 원문을 넣지 않는다(NFR4는 원문 금지만 있고 사건 남기기 자체는 요구가 아니다). | 위 네 가지를 해당 FR에 한 줄씩 더하고 pass/fail 기준을 붙인다. 이미 설계 정본으로 충분하다고 판단하면 "설계 전체를 구현 범위로 본다"는 문장을 의도 분석에 두고 누락이 의도적임을 밝힌다. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261013-u4b-activity-realtime/inception/requirements-analysis/requirements.md > 의도 분석 · FR1.4, FR8.1 (출처 열) vs aidlc/spaces/default/intents/261008-mujoong-service/inception/requirements-analysis/requirements.md > FR1.6 | 원 FR1.6(계정 삭제)은 원래 기록에서 Should였는데 FR1.4와 FR8.1은 Must로 올렸다. Q3(A)로 사람이 7층 전부를 필수로 정했으니 모순은 아니지만, 우선순위가 원본과 달라졌다는 표시가 어디에도 없다. | FR1.4 · FR8.1 출처에 "원 FR1.6은 Should, Q3(A)로 Must 상향"을 적어 추적이 끊기지 않게 한다. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261013-u4b-activity-realtime/inception/requirements-analysis/requirements.md > FR4.1, 가정 | 원 NFR6의 "기기 불안정 판정 지연 1초 · 5분 안 끊김 2회"가 FR5.1 · FR4.1 어디에도 없고 범위 밖에도 적혀 있지 않다. 설계에도 없어 U4b 범위가 아닐 가능성이 크지만, 요구사항 문서가 NFR6을 인용하면서 이 부분의 귀속(다른 유닛 또는 U3)을 밝히지 않았다. | 범위 밖 또는 가정 표에 "원 NFR6 중 기기 불안정 판정은 U4b 몫이 아님(해당 유닛 명시)"을 한 줄 적는다. | New |
| R-05 | Minor | aidlc/spaces/default/intents/261013-u4b-activity-realtime/inception/requirements-analysis/requirements.md > FR9.1, FR7.1 (practice 필드) | FR7.1은 `practice: null`로 적고, U5b brief는 FREE_TALK · DECLINED이면 "null 또는 빈 배열"이라 한다. 계약 6절은 DECLINED에서 utterances · practice · audio가 빈 배열이라 쓴다. PRACTICE 세션이 U5b 전에 ACTIVE까지 간 경우 practice가 null인지 빈 값인지가 U6 입력 계약과 맞는지 한 가지로 정해지지 않았다. | FR7.1 완료 기준에 세션 종류 · 종료 경로별 practice 값(null 또는 빈 배열)을 표로 못박고 계약 스키마 검증으로 확인하게 한다. | New |
| R-06 | Minor | aidlc/spaces/default/intents/261013-u4b-activity-realtime/inception/requirements-analysis/requirements.md > FR11.5, 제약 | 설계 J24(PR #2가 열려 있는 동안 U4b를 푸시하지 않는다)는 이미 낡았고 요구사항은 제약 절에서 새 브랜치 정책을 적었다. 반면 FR11.5의 "design.md는 바뀐 곳만 고친다"에는 어느 곳이 바뀔지(J24 폐기, 확장점 이름 추가 등) 열거가 없어 검증할 수 없다. | FR11.5 완료 기준에 design.md에서 고칠 곳 목록(J24 갱신, 확장점 인터페이스 이름 기입, 변경된 J)을 report로 확인한다고 적는다. | New |

### Summary

U4b 요구사항은 설계 R1~R17과 J1~J26, 사람의 답변 Q1~Q6과 대체로 일치하고 완료 기준도 대부분 시험 가능한 pass/fail로 적혀 있어 구현이 가능한 수준이다. 새 업무 요구사항을 만든 곳은 찾지 못했다. 다만 U5b와 맞물리는 정산 훅 · 삭제 순서(R-01), 설계에는 있으나 요구사항에 빠진 의무 네 가지(R-02)는 승인 전에 판단하길 권한다. Major 2건은 모두 임시 대응이 가능해 READY로 판정한다.
