# Functional Design 질문 — U12 새 연습 흐름

> 기준: 이 기록의 `requirements.md`(열린 질문 1 · 2 · 3 · 4 · 5), RA 검토 R-01 ~ R-06, 이 단계에서 읽은 실행부 · 제브 코드. 사람 판단이 필요한 세 가지만 개별 질문으로 묻고, 코드 사실로 정해지는 나머지는 맨 아래 가정 묶음으로 확인받는다.

## Q1. 역할 전환(코치로 바뀜) · 복귀 알림은 어떻게 들려줄까요?

시작 패키지에는 음성 없는 승인 발화 `COACH_SWITCH` · `PEER_RETURN` 문장이 실립니다. 이 문장을 그대로 읽어 주면 승인 원문 일치라 제브를 부르지 않습니다. GPT-Live가 알림을 새로 만들면 제브 검사를 받고, 막히면 알림이 안 나가 역할을 못 바꿉니다(AC1.5.6).

A. 승인 문장 우선. 승인 문장이 있으면 그대로 재생하고, 없을 때만 GPT-Live가 만든 알림을 제브 검사 뒤 재생합니다. 그 알림이 막히면 역할을 바꾸지 않고 장면을 정리합니다. (추천)
B. 항상 GPT-Live가 알림을 만들고 제브로 검사합니다.
X. Other (please specify)

[Answer]: A

## Q2. 판정(J2 ~ J5)이 열려 있는 동안 연습 시계는 어떻게 할까요?

A. 무응답 재안내 시계만 멈춥니다. 아이가 이미 말했기 때문입니다. 집중 90초와 목표 ACTIVE 10분 한도는 벽시계 상한이라 계속 흐릅니다. (추천)
B. 판정이 열린 동안 세 시계를 모두 멈춥니다. 한도가 판정 지연만큼 늘어나고 일시정지 규칙이 복잡해집니다.
X. Other (please specify)

[Answer]: A

## Q3. 세션당 연습 확장은 어떻게 분기할까요? (새 흐름 대 레거시)

`Factory.create(sessionId)`는 `practiceJson`을 받지 않고, 실행부는 `onActive` 전에 `controlsOutput()`을 묻습니다.

A. 얇은 분기 확장. 시그니처는 그대로 두고 세션당 하나인 분기 확장이 `onActive`에서 `scriptVersion`을 보고 한쪽에 위임합니다. 기존 시험 약 10개가 바뀌지 않습니다. (추천)
B. `Factory.create(sessionId, practiceJson)`으로 바꿉니다. 시험 약 10개와 `RealtimeRuntimeConfig`를 고쳐야 합니다.
X. Other (please specify)

[Answer]: A

## Assumption Confirmation

코드 사실로 정해지거나 앞 결정을 따르는 다음 항목은 한 묶음으로 확인합니다.

1. `coreFunction`은 목표의 `judgmentCriteria` 문자열로 만들고, 후보 목표 필드(`goalKind` · `candidateRef` · `candidateVersion` · `goalContext`)는 읽지 않습니다(U14가 쓰지 않음).
2. `judgment_live.goal_ref` · `scene_ref`는 계속 null로 둡니다(`requestJudgment` 시그니처를 늘리지 않음, 후속 개선).
3. 확장이 허용한 GPT-Live 역할 발화는 새 출력 능력 `speakGenerated`로 슬롯을 열고, 슬롯이 열려 있고 제브가 켜져 있을 때만 제브(PRACTICE_PEER/COACH)로 보냅니다. 제브가 꺼져 있거나 실패하면 폐기합니다(fail-closed).
4. `PracticeContext`에 승인 발화 문장 지도를 더합니다(기존 생성자 유지).
5. 진행 표시 매핑은 `functional-spec.md` §4 표를 따릅니다(CLOSING은 직전 값 유지).
6. 변형 생략 사유 · 4조건은 AC1.6.1 그대로이며 `variantSkipReason` 계약 값 5개만 씁니다.
7. 도움 · 역할 전환은 분류 코드와 길이만 저장하는 것으로 AC1.5.5를 좁히고 편차 `U12-D2`로 적습니다.
8. 장면당 변형은 대본의 VARIANT `scenes[]` 첫 항목 하나를 씁니다.
9. 기록은 V7 새 표 두 개(장면 실행 · 사실, 도움 · 역할 전환), 삭제 참여자 순서 190(세션 200 앞)입니다.
10. AC는 29개이며 요구사항의 '31개'는 오기입니다. 지시서 정정은 문서 정비 단계에서 합니다.

A. Accept assumptions
B. Convert to follow-up questions

[Answer]: A. Accept assumptions

## Consolidated Summary Confirmation

- Q1 역할 알림: 승인 문장(COACH_SWITCH · PEER_RETURN)을 우선 재생하고, 없을 때만 GPT-Live 알림을 제브 검사 뒤 재생한다. 막히면 역할을 바꾸지 않고 장면을 정리한다.
- Q2 판정 중 시계: 무응답 시계만 멈추고 집중 90초 · 목표 10분은 계속 흐른다.
- Q3 분기: `Factory` 시그니처는 그대로 두고 얇은 분기 확장이 `onActive`에서 `scriptVersion`으로 새 흐름 또는 레거시에 위임한다.
- 가정 10개 수용: `coreFunction` 출처, `goal_ref` null 유지, 생성형 슬롯 + 제브 fail-closed, 승인 발화 지도, 진행 매핑표, 변형 4조건, 편차 `U12-D2`, 변형 첫 항목, V7 표 · 삭제 순서 190, AC 29개.

Does this all look correct before I generate the functional design artifacts?

- Looks correct
- Request changes

[Answer]: Looks correct
