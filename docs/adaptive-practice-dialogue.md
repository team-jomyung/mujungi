# 상황 반응 조절형 연습대화 — 현재 구현

대조 기준: 2026-10-30 main `08eb7cb0`. 이 문서는 현재 분기를 설명하며 과거 검증 기록은 아래에 그대로 보존한다. 한 번에 선택하는 목표는 **1개**다. [시작 요청](../backend/core-api/src/main/java/kr/mujoong/core/practice/start/PracticeStartRequest.java)과 [시작·돌아보기](practice-goal-opening-and-recap.md)를 함께 본다.

## 진행 흐름

참여 확인 → 활동 범위 안내 → 새 상황 안내 → 상대의 여는 말 → 장면 안 유도 대화 또는 바로 목표 기회 → 아이 반응에 따른 역할 대화·도움 → 상대 역할 마무리 → 돌아보기·종료·기록 저장.

이전 대화 돌아보기 질문이나 요약 낭독은 현재 시작 흐름에 포함하지 않는다. [StructuredScenarioFlow](../backend/core-api/src/main/java/kr/mujoong/core/practice/StructuredScenarioFlow.java)는 원본 아이 발화 인용을 상황 생성에 전달하지 않고, [PracticeScriptReader](../backend/realtime-activity/src/main/java/kr/mujoong/realtime/practiceflow/PracticeScriptReader.java)는 옛 `reflectionPrompt`·`reflectionSummary`를 읽지 않는다. 이는 과거 목표·장면 이력 자체를 삭제한다는 뜻은 아니다.

## 여는 말과 유도 대화

[PracticeConversation](../backend/realtime-activity/src/main/java/kr/mujoong/realtime/practiceflow/PracticeConversation.java)의 `hasLeadIn`, `flexibleJudgment`, `leadInStep`이 실제 분기다.

- `openingLine`이 없으면 `opportunitySentence`로 시작한다. 두 문장이 같아도 별도 유도 구간을 만들지 않는다.
- 두 문장이 다르면 유도 구간을 시작한다. 일반 답변에서는 `LEAD_IN_ANSWERS = 2`에 도달하기 전 상대가 한 번 더 장면 대화를 잇고, 도달하면 기회 문장을 재생한다.
- 유도 중에도 목표 수행(`FULL`), 도움 요청(`HELP`), 진행 방법 질문, 판독 불가(`UNREADABLE`)는 별도 처리한다. ‘어떤 답이든 판정 없이 한 차례 뒤 넘어간다’는 설명은 현재 코드와 다르다.
- 이 2회는 유도 단계의 진행 기준이며 전체 대화를 2회로 제한하는 값이 아니다. 무응답·판정 불가에서도 별도 분기가 있으므로 반드시 아이 답변 2개가 있어야만 이동한다는 뜻도 아니다.

## 반응에 따른 도움과 마무리

| 반응 | 현재 처리 |
| --- | --- |
| 필요한 뜻 전달 | 상대 역할로 먼저 답하고 목표 수행과 대화 정리를 구분 |
| 일부 정보 부족 | 상대가 필요한 정보 하나를 확인. 반복되면 도움 제안 |
| 상황 이해 어려움 | 상황 재설명을 우선하고 한도 후 도움 제안 |
| 표현 방법·진행 방법 질문 | 도움 분기로 처리하며 목표 달성으로 바로 계산하지 않음 |
| 실제 전사 불명확 | 한 번 확인하고 반복 시 전사 실패 사실을 남기며 역할 대화 지속 |
| 판정 장애·보류 | 못 들었다고 단정하지 않고 현재 단계에 맞춰 지속 또는 정리 |
| 목표 수행 후 합의가 더 필요 | 정리 판정과 정리 답변 상한을 적용한 뒤 상대 마무리 답으로 닫음 |

상대의 확인 질문으로 해결된 경우와 코치 도움 후 수행한 경우를 기록에서 구분한다. 수행 여부와 장면 전체 완료 여부도 같지 않다. 종료·휴식 요청은 진행보다 우선한다. 시간값은 [PracticeFlowSettings](../backend/realtime-activity/src/main/java/kr/mujoong/realtime/practiceflow/PracticeFlowSettings.java)가 설정 트리에서 읽으므로 고정 운영 시간으로 단정하지 않는다.

## 생성·판정·출력의 구분

- 상황 생성과 독립 검토: [LunaTextAiProvider](../backend/core-api/src/main/java/kr/mujoong/core/luna/adapter/LunaTextAiProvider.java)가 `generation.v5.txt`와 `review.v4.txt`를 읽는다. [생성기](../backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioScriptGenerator.java)는 해당 버전과 그래프 사용 여부를 저장한다.
- 실시간 대화 지시: [practice-system.v1](../backend/realtime-activity/src/main/resources/practice/practice-system.v1.txt)과 [PracticeInstructions](../backend/realtime-activity/src/main/java/kr/mujoong/realtime/practiceflow/PracticeInstructions.java). 상대는 최신 말의 뜻에 반응하고 이미 해결된 질문·정답 강요를 반복하지 않도록 지시받는다.
- 진행 판정: [DecisionsJudgeClient](../backend/realtime-activity/src/main/java/kr/mujoong/realtime/judgment/DecisionsJudgeClient.java)와 서버 상태 전이. 생성 프롬프트 버전과 판정 기준은 같은 파일이 아니다.
- 출력 검사: 생성 발화의 Jev 검사와 승인 대본 낭독의 원문 검증은 별도 경로다. 요청 수락만으로 실제 재생 완료를 확정하지 않는다. [AI 입출력 계약](../contracts/ai-io.md)을 함께 확인한다.

발화 폐기·판정 보류를 곧바로 아이의 실패로 기록하지 않는다. 복구·건너뛰기·종료 여부는 단계와 실패 종류에 따라 상태 기계가 결정한다. 따라서 ‘오류가 나면 언제나 종료’ 또는 ‘모든 오류에서 반드시 계속 진행’으로 단순화하지 않는다.

## 다음 회차와 지식 참조

상황 생성은 목표와 이전 장면의 도움·상황 정보를 참고한다. 이전 대화 원문을 아이에게 읽어 주는 기능과 구분한다. 그래프 참조를 요청했다는 것만으로 실제 사용을 보장하지 않으며 저장된 `graphContextUsed`와 근거 연결을 확인한다. [Neo4j 안내](neo4j-runtime.md)를 따른다.

## 검증

- 아래는 2026-10-22 검증 기록이다(돌아보기 질문 방식 기준, FT-1023-g 전). FT-1023-g의 검증은 PR · JIRA-275에 따로 적는다.
- 관련 자동 시험: Core 137개 + 실시간 서버 588개 = 725개.
- 실제 AI 판정: 짧은 색 답변의 기능 전달, 상황 혼란, 표현 도움 요청, 돌아보기 거절 4개 사례 모두 기대 판정 확인.
- 실제 AI 생성: 돌아보기 질문·자연스러운 새 상황 안내·상대 대사 생성과 독립 검토 승인, 합성 자료 시험 약 7.1초.
- 실제 음성 생성: 돌아보기 선택 안내 음성 생성, Jev `ALLOW` 확인.
- 시험의 음성·대화 자료는 합성 자료다. 사용자의 브라우저·실제 마이크·스피커를 포함한 전체 시험과 구분한다.

## 현재 흐름을 직접 확인할 항목

1. 목표 1개 선택 → 참여 확인 → 목표명을 읽는 대신 상상놀이 범위 안내 → 새 상황으로 이어지는지 확인한다.
2. 여는 말이 기회 문장과 다른 장면에서 유도 대화와 기회 문장 전환을 확인한다. 목표 수행·도움 요청의 조기 분기도 별도 시험한다.
3. 부분 답변, 상황 혼란, 표현 도움 요청, 전사 불확실성을 구분해 처리하는지 확인한다.
4. 목표 수행 후 상대 답변·정리·돌아보기·종료 인사와 저장된 도움·종료 사유를 대조한다.

위 목록은 수동 회귀 점검 안내다. 이번 문서 최신화에서 실제 마이크·스피커·AI 공급자 시험을 실행한 결과가 아니다.
