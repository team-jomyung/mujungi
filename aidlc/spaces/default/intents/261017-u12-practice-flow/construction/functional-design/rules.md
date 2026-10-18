# U12 새 연습 흐름 — 업무 규칙

> 작업 기록 `261017-u12-practice-flow` · Functional Design · 2026-10-18 · 질문 답(Q1 ~ Q3 A)과 요약 확인 반영. 규칙 ID는 `BR{묶음}.{번호}`다. 출처 FR · NFR은 이 기록의 `requirements.md` 키다.

```yaml
rules:
  # ---- BR1 시작 경로와 분기
  - id: BR1.1
    statement: 내부 시작 API는 연습 세션 시작을 받는다.
    category: validation
    applies_to: 내부 시작 요청
    trigger: POST /internal/sessions
    logic: "IF activityType == PRACTICE THEN 선택 본문 practice를 허용하고 202로 받는다. IF FREE_TALK THEN 지금과 같다. IF practice 형식이 계약과 다르면 400."
    violation: 400 VALIDATION_FAILED(기존 오류 코드)
    source: FR1.1
  - id: BR1.2
    statement: practice 원문은 보존되어 확장의 시작 맥락까지 닿는다.
    category: constraint
    applies_to: 시작 처리기 · 실행 계획 · PracticeContext
    trigger: 세션 시작 처리, ACTIVE 진입
    logic: "IF 연습 시작이면 THEN practice 원문을 계획에 싣고 onActive 맥락의 practiceJson으로 넘긴다. 같은 맥락에 승인 발화(approvedSpeech) 코드 · 문장 지도도 싣는다."
    violation: practiceJson이 비면 확장은 레거시 분기를 타지 않고 시작을 '대본 없음'으로 실패 처리한다(BR1.4)
    source: FR1.2, FR3.2
  - id: BR1.3
    statement: U10 대본은 새 읽기 타입으로만 소비한다.
    category: validation
    applies_to: 대본 읽기
    trigger: 시작 패키지 firstScenario, 다음 목표 대본 요청
    logic: "IF 필수 6필드가 없으면 THEN 그 대본을 거절한다. IF 선택 필드(selfReview · generation · 변형 scenes)가 없으면 THEN 빈 값으로 정규화한다. IF scriptVersion이 없으면 THEN 새 흐름 대상이 아니다(BR1.4). 나머지 목표는 내부 호출(kind ORIGINAL)로, 변형 장면은 같은 응답의 VARIANT scenes[]로 얻는다. sceneRunId는 realtime이 만들고 scenarioRef는 대본 scenarioId다."
    violation: 거절된 대본의 목표는 '미실시'로 두고 다음으로 간다. 모든 목표가 그러면 연습을 정리 종료한다
    source: FR1.3
  - id: BR1.4
    statement: 새 흐름과 레거시는 대본의 scriptVersion으로 onActive에서 자동 분기한다.
    category: policy
    applies_to: 연습 확장 진입
    trigger: onActive
    logic: "IF scriptVersion == scenario-script.v1 THEN 새 흐름(PracticeConversation). IF scriptVersion 없음 THEN 레거시 런타임에 위임. Factory 시그니처는 바꾸지 않고 얇은 분기 확장(Router)이 세션당 하나 만들어진 뒤 onActive에서 한 쪽을 고른다. controlsOutput은 분기 전에도 참이다(실행부가 첫 응답 요청 전에 묻는다)."
    violation: 레거시 기본 포트는 실패를 돌려주므로 scriptVersion 없는 세션은 사실상 멈춘다(운영에서 연습이 돈 적 없다). 이 사실을 design.md · report.md에 적는다
    source: FR5.2
  # ---- BR2 시작 · 참여 확인(참여 확인은 실행부 소유)
  - id: BR2.1
    statement: 확장은 참여가 끝난 onActive에서 곧바로 장면을 시작한다.
    category: policy
    applies_to: 확장 진입
    trigger: onActive
    logic: "IF onActive에 닿았다 THEN 참여 확인(J1)이 끝난 것이므로 목표 0의 SCENE_INTRO를 시작한다. 확장에는 '시작 전' 단계가 없다. 도입 재생이 성공한 뒤 SceneRun 행을 만든다."
    violation: n/a
    source: FR2.1
  - id: BR2.2
    statement: 시작 전 거부 · 다른 선택지 제안 · 미참여 종료는 실행부가 하며 확장은 구현하지 않는다.
    category: constraint
    applies_to: 실행부 J1 처리(U4b · U11)
    trigger: 참여 확인 중 거부
    logic: "첫 거부 대안 제안 · 두 번째 거부 종료 · 승인 마무리 문구는 실행부 기존 동작이다. U12는 이 동작이 연습 세션에서도 그대로인지 회귀 시험으로만 확인한다."
    violation: 실행부 동작을 바꾸면 U11 시험 실패
    source: FR2.1, FR4.3
  - id: BR2.3
    statement: 무관 · 불명확한 시작 응답의 재질문은 실행부가 한다.
    category: constraint
    applies_to: 실행부 J1 처리
    trigger: 무관 · 불명확 판정
    logic: "교정이나 압박 없이 참여 의사 재질문하는 동작은 실행부 기존 동작이며 회귀 시험으로만 확인한다."
    violation: n/a
    source: FR2.1
  - id: BR2.4
    statement: 시작 전 휴식 요청은 받아 주는 말 없이 재안내한다(실행부, 변경 없음).
    category: constraint
    applies_to: 실행부 J1 처리
    trigger: 휴식 의사
    logic: "U11-D6 그대로이고 거부로 세지 않는다. 정본 AC1.1.2의 D_REST_ACK_START 재생은 개정한다(U12-D1). 회귀 시험으로만 확인한다."
    violation: n/a
    source: FR2.2
  - id: BR2.5
    statement: 새 흐름은 레거시 평가 · 단서 포트에 기대지 않는다.
    category: constraint
    applies_to: 새 흐름 전체
    trigger: 시작부터 마무리까지
    logic: "IF EvaluationProvider · ResponseEvaluator · CueProvider가 실패 기본 빈이어도 THEN 새 흐름은 호출하지 않고 끝까지 진행한다."
    violation: n/a
    source: FR5.1
  # ---- BR3 장면 진행
  - id: BR3.1
    statement: 단계는 정해진 집합이며 도움 · 변형은 반드시 거치지 않는다.
    category: constraint
    applies_to: 단계 전이
    trigger: 모든 전이
    logic: "단계는 SCENE_INTRO, OPPORTUNITY, REASK, HELP_OFFER, FOCUS, RETRY, SCENE_WRAP, VARIANT_OFFER, NEXT_GOAL, CLOSING뿐이다(변형 장면은 sceneKind=VARIANT로 같은 단계를 다시 돈다). SCENE_WRAP은 복귀 알림 → 변형 평가의 두 소단계를 가지며 알림이 실패해도 평가로 넘어간다. 전이는 functional-spec.md의 전이표에 있는 것만 허용한다. 표에 없는 입력은 상태를 바꾸지 않고 '이어 가기'로 처리한다."
    violation: 표 밖 전이 요청은 무시하고 앱 오류로 한 줄 남긴다(내용 없음)
    source: FR2.1
  - id: BR3.2
    statement: 기회 문장 뒤 '일부 전달'이면 친구 역할로 1회 되묻는다.
    category: policy
    applies_to: OPPORTUNITY
    trigger: 판정 '일부 전달'
    logic: "IF 일부 전달 THEN REASK(친구 역할 되묻기 1회, Jev PRACTICE_PEER 검사). IF 충분 THEN SCENE_WRAP(IMMEDIATE). IF 무관/이해 못함 THEN BR3.5."
    violation: n/a
    source: FR2.1, FR2.3
  - id: BR3.3
    statement: 되묻기로 해결되면 집중 도움 없이 정리하고 시점을 '되묻기 뒤'로 기록한다.
    category: policy
    applies_to: REASK
    trigger: 되묻기 뒤 판정 '충분'
    logic: "IF 충분 THEN SCENE_WRAP, 사실 REASK_RESOLVED, liveTiming AFTER_REASK, 집중 도움 0건."
    violation: n/a
    source: FR2.3
  - id: BR3.4
    statement: 되묻기 뒤에도 부족하면 도움을 제안하되 강제하지 않는다.
    category: policy
    applies_to: REASK
    trigger: 되묻기 뒤 판정 '여전히 부족'
    logic: "IF 여전히 부족 THEN HELP_OFFER. 아이가 거절하면 BR4.3."
    violation: n/a
    source: FR2.3
  - id: BR3.5
    statement: 상황을 이해 못했거나 무관 발화면 교정 전에 상황을 다시 설명한다.
    category: policy
    applies_to: OPPORTUNITY(J2), RETRY(J5) — J3에는 해당 선택지가 없다
    trigger: 판정 '상황 이해 못함' 또는 '무관'
    logic: "reexplainCount는 장면 전체에서 OPPORTUNITY와 RETRY가 공유한다. OPPORTUNITY에서 IF reexplainCount < reexplainMax THEN 대본 reexplainLine을 승인 발화로 들려주고 reexplainCount += 1, 기회 문장으로 돌아간다(사실 SITUATION_EXPLAINED). 교정 · 예시를 먼저 주지 않는다. 한도면 HELP_OFFER. RETRY에서 IF reexplainCount < reexplainMax AND retryCount < retryMax AND 집중 시계 남음 THEN 다시 설명 뒤 코치 발화(FOCUS 진입 ②)로 돌아가고, 아니면 SCENE_WRAP(FOCUS_LIMIT)."
    violation: n/a
    source: FR2.4
  - id: BR3.6
    statement: 입력을 기다리는 모든 단계에서 무발화는 1회 재안내하고 한 번 더 없으면 정해진 방식으로 닫는다.
    category: policy
    applies_to: OPPORTUNITY, REASK, HELP_OFFER, RETRY, VARIANT_OFFER
    trigger: 확장 발화 재생 완료 뒤 무응답 시계 만료
    logic: "IF 첫 만료 THEN 같은 역할로 재안내 1회, 시계를 다시 건다. IF 둘째 만료 THEN OPPORTUNITY · REASK는 장면 '미실시'(TIMEOUT, liveTiming NONE), HELP_OFFER · RETRY는 SCENE_WRAP(TIMEOUT, 변형 안 함 END_OR_REST, RETRY는 복귀 알림 포함), VARIANT_OFFER는 DECLINED. 시계는 확장점 시계(schedule)라 실행부 일시정지(기술 일시정지 · J6 · 종료 확인) 동안 멈추고 그 사이 재안내하지 않는다."
    violation: n/a
    source: FR2.5
  - id: BR3.7
    statement: 판정 호출과 다음 행동 지시의 모양.
    category: policy
    applies_to: J2 ~ J5, 지시문
    trigger: 아이 전사가 판정 지점에 해당할 때
    logic: "확장이 requestJudgment로 요청한다. 단계별 지점은 OPPORTUNITY=J2, REASK=J3, HELP_OFFER=J4, RETRY=J5, VARIANT_OFFER=J4(재사용, U12-D3)이다. 판정 기준 coreFunction은 목표의 judgmentCriteria 문자열에서 만들어 J2 · J3 · J5에만 싣는다(J4에는 싣지 않는다). 결과 사건(JUDGMENT_RESULT · JUDGMENT_ABSTAINED · HELD_OUTPUT_TRANSCRIPT)이 전이로 이어지고, 선택지 코드마다 전이는 functional-spec.md §3.3 표가 정한다. 다음 행동 발화는 GPT-Live가 만드는 것이면 speakGenerated 한 길(replayable 지시, 와이어 형식은 session.instructions.append 하나, U11-D9)로만 내고 instructProvider는 쓰지 않으며, 대본 문장이면 승인 발화 재생으로 낸다. 라벨 · 확률 · 기준은 지시문에 넣지 않는다. requestJudgment가 거짓이거나 JUDGMENT_ABSTAINED(보류 · 장애)이거나 UNREADABLE이면 교정 없이 같은 질문을 재안내한다(장면 · 단계당 1회, 두 번째면 정해진 방식으로 닫는다). 연습이 멈추지 않는다."
    violation: 지시문에 라벨 · 확률이 보이면 시험 실패(NFR1 · R-05)
    source: FR2.8
  - id: BR3.8
    statement: 판정이 열려 있는 동안의 시계.
    category: constraint
    applies_to: 연습 시계
    trigger: requestJudgment 호출 ~ 결과 사건
    logic: "열린 판정(J2 ~ J5) 중에는 확장이 무응답 시계를 취소한다(아이가 이미 말했다). 집중 90초 · 목표 ACTIVE 10분 시계는 판정 중에도 두어 판정 지연을 한도에 센다. 모든 시계는 확장점 시계(schedule)라 실행부 일시정지 구간은 한도에서 제외된다. 이 결정이 열린 질문 1의 답이다(Q2 A)."
    violation: n/a
    source: FR2.5, FR2.7
  # ---- BR4 도움
  - id: BR4.1
    statement: 도움 요청이면 어느 판정 지점에서든 바로 코치 전환한다.
    category: policy
    applies_to: 모든 장면 단계
    trigger: 판정 '도움 요청'
    logic: "IF 도움 요청 THEN FOCUS. 역할 전환은 BR5.1을 따른다."
    violation: n/a
    source: FR2.6
  - id: BR4.2
    statement: 도움 제안 뒤 수락이면 FOCUS로 간다.
    category: policy
    applies_to: HELP_OFFER
    trigger: 판정 '수락'
    logic: "IF 수락 THEN 사실 HELP_ACCEPTED, FOCUS, focusStartedAt 기록."
    violation: n/a
    source: FR2.6
  - id: BR4.3
    statement: 거절이면 원래 장면을 잇거나 정리한다.
    category: policy
    applies_to: HELP_OFFER
    trigger: 판정 '거절'
    logic: "IF 거절 THEN 사실 HELP_DECLINED. 원래 장면을 이을 수 있으면(기회 문장으로 복귀) 잇고, 아니면 SCENE_WRAP. 도움을 받지 않은 장면은 '미완료'로 표시하지 않는다."
    violation: n/a
    source: FR2.6
  - id: BR4.4
    statement: 불명확 · 보류는 집중 연습을 시작하지 않고 교정 없이 이어 간다.
    category: policy
    applies_to: HELP_OFFER
    trigger: 판정 '불명확' 또는 보류
    logic: "IF 불명확/보류 THEN 단계를 바꾸지 않고 이어 간다(교정 · 집중 연습 없음)."
    violation: n/a
    source: FR2.6
  # ---- BR5 역할 · 출력 정책
  - id: BR5.1
    statement: 코치 전환에는 첫 코치 발화 전에 역할 전환 알림이 있다.
    category: policy
    applies_to: FOCUS 진입
    trigger: BR4.1 · BR4.2
    logic: "IF FOCUS 진입 THEN 알림 먼저(announceRoleSwitch, 승인 문장 우선). 알림 재생이 PLAYED로 끝난 뒤에만 role=COACH로 바꾸고 코치 발화를 지시한다(진입 ① → 진입 ②). 알림이 FAILED · INTERRUPTED · 막힘이거나 낼 수 없으면 역할을 바꾸지 않고 도움 없이 SCENE_WRAP으로 정리한다."
    violation: 알림 없이 코치 발화를 지시하지 않는다
    source: FR3.1
  - id: BR5.2
    statement: 역할별 발화 정책.
    category: policy
    applies_to: 친구 · 코치 지시문
    trigger: 지시문 생성
    logic: "친구 역할은 정답 · 모범 문장을 미리 말하지 않는다. 코치는 아이가 동의한 뒤에만 표현 예시를 줄 수 있다. CueProvider의 전역 금지는 이 역할별 정책으로 나눈다(지시문 템플릿에 역할별 금지가 들어간다)."
    violation: 친구 출력에 정답 선공개가 나오면 시험 실패
    source: FR3.1
  - id: BR5.3
    statement: RETRY 뒤 판정이 복귀 조건이면 복귀 알림 뒤 원래 장면을 잇거나 정리한다.
    category: policy
    applies_to: RETRY
    trigger: 재시도 판정
    logic: "IF 복귀 조건 THEN 복귀 알림(announceRoleSwitch PEER) 뒤 role=PEER, 사실 COACH_RETURN · ORIGINAL_SCENE_RETURN, 원래 장면 잇기 또는 SCENE_WRAP."
    violation: n/a
    source: FR3.1
  - id: BR5.4
    statement: 역할 전환 · 복귀 알림은 승인 발화 원문을 우선 쓴다.
    category: policy
    applies_to: announceRoleSwitch
    trigger: BR5.1 · BR5.3
    logic: "IF 시작 패키지 approvedSpeech에 해당 코드(COACH_SWITCH · PEER_RETURN)가 있으면 THEN 그 문장을 기존 승인 발화 재생(speak) 길로 재생한다(원문 일치라 제브 호출 없음). announceRoleSwitch는 재생 요청이 나가면 참을 돌려준다. IF 코드가 없으면 THEN 해당 알림을 GPT-Live가 만드는 역할 발화로 지시하고 제브(PRACTICE_COACH) 검사를 받는다(BR5.5)."
    violation: ACTIVE가 아니면 거절하고 PRACTICE_EXTENSION_FAILED(NOT_ACTIVE)
    source: FR3.2
  - id: BR5.5
    statement: 알림을 제브가 막으면 알림 없이 역할을 바꾸지 않는다.
    category: policy
    applies_to: 생성형 알림
    trigger: 제브 BLOCK
    logic: "IF 알림 발화가 막히면 THEN 폐기 · 재지시 1회(U13). 두 번째 막힘(GUARDRAIL_BLOCKED)이면 역할을 바꾸지 않고 SCENE_WRAP."
    violation: n/a
    source: FR3.3
  - id: BR5.6
    statement: 연습이 출력을 쥐는 동안 GPT-Live 새 발화는 확장이 허용한 것만 제브를 거쳐 재생된다.
    category: policy
    applies_to: 구간 확정 단계(sealAndGate)
    trigger: 연습 통제 아래 구간 확정
    logic: "GPT-Live가 만드는 모든 역할 발화는 speakGenerated 하나로만 낸다(슬롯 열기 · 출력 보류 해제 · 세대 올림 · replayable 지시 송신 · 실패 알림). IF 구간이 승인 발화 원문과 같다 THEN 제브 없이 재생(기존). ELSE IF 열린 생성형 슬롯(PracticeSpeech의 generated 변형)이 있고 제브 단계가 켜져 있다 THEN 제브를 PRACTICE_PEER/COACH로 호출하고 ALLOW일 때만 재생한다. 첫 막힘은 같은 역할 지시문을 다시 보내 한 번 다시 검사하고 두 번째 막힘은 GUARDRAIL_BLOCKED로 확장에 알린다. ELSE 폐기한다(기존). 제브가 꺼져 있거나 실패하면 슬롯이 열려 있어도 폐기한다(fail-closed). 슬롯은 어떤 원인으로 닫혀도 확장에 종료 사실(PLAYED · FAILED · INTERRUPTED)을 정확히 한 번 보내고 확장은 correlationId마다 첫 종료 사건만 처리한다. 레거시 확장은 슬롯을 열 수 없으므로 기존 폐기 규칙이 그대로다."
    violation: 우회 송신 경로 없음. 슬롯 없이 나오는 발화는 폐기
    source: FR3.3, NFR1
  - id: BR5.7
    statement: 연습 발화의 두 번째 막힘은 장면을 정리한다.
    category: policy
    applies_to: GUARDRAIL_BLOCKED 사건
    trigger: 사건 수신
    logic: "IF 친구 되묻기 또는 코치 발화가 두 번째로 막히면 THEN 해당 장면을 SCENE_WRAP(실패 사실은 코드로만 남김)하고 다음으로 간다. 막힌 후보 문장은 어디에도 저장하지 않는다."
    violation: n/a
    source: FR3.3, AC1.2.2
  # ---- BR6 변형 · 한도
  - id: BR6.1
    statement: 변형은 네 조건이 모두 맞을 때만 제안한다.
    category: policy
    applies_to: SCENE_WRAP
    trigger: 장면 정리
    logic: "정본 AC1.6.1의 네 조건: (1) 쓸 수 있는 변형 장면이 있다(없으면 NO_VARIANT) (2) 종료 · 휴식 요청이 없다(있으면 END_OR_REST) (3) 목표 ACTIVE 10분 전이다(아니면 TIME_LIMIT) (0) 장면이 정상으로 수행되어 끝났다(무응답 미실시 · 판독 불가 · 판정 보류 · 막힘 · 재생 실패로 끝난 장면은 제안하지 않고 functional-spec.md §3.3a 표의 사유를 쓴다) (4) 집중 연습이 AC1.5.4 한도(다시 해 보기 2회 · 집중 90초, 판단은 시계 만료 표지) 안이다(아니면 FOCUS_LIMIT). 네 조건이 모두 참이면 변형을 제안하고 동의는 J4(수락 · 거절 · 불명확)를 재사용해 읽는다(U12-D3). 하나라도 거짓이면 변형 없이 다음으로 가고 해당 사유를 기록한다. 변형 장면(sceneKind=VARIANT)은 BASE와 같은 하위 흐름이고 그 정리 뒤에는 변형을 다시 제안하지 않는다."
    violation: n/a
    source: FR2.7
  - id: BR6.2
    statement: 변형을 생략하면 사유를 기록한다.
    category: policy
    applies_to: SCENE_WRAP 이후
    trigger: BR6.1 불충족 또는 변형 거절
    logic: "사유 NO_VARIANT(쓸 변형 없음) · DECLINED(거절) · TIME_LIMIT(시간 부족 또는 목표 10분) · FOCUS_LIMIT(다시 해 보기 한도 · 집중 90초) · END_OR_REST(종료 · 휴식 의사). 변형을 거절한 것도 DECLINED로 기록한다."
    violation: n/a
    source: FR2.7
  - id: BR6.3
    statement: 다시 해 보기 한도와 집중 한도.
    category: constraint
    applies_to: FOCUS, RETRY
    trigger: 재시도 횟수 · focus-max 시계 만료 표지
    logic: "IF retryCount >= retryMax(기본 2) OR focusLimitReached(focus-max 확장점 시계가 만료되어 세운 표지) THEN 장면을 SCENE_WRAP으로 정리하고 변형을 제안하지 않는다(사유 FOCUS_LIMIT). 한도는 Instant 차이가 아니라 확장점 시계 만료 표지로 판단한다(일시정지 구간 제외). goalActiveStartedAt · focusStartedAt은 기록용이다."
    violation: n/a
    source: FR2.7
  - id: BR6.4
    statement: 목표 ACTIVE 10분이 되면 어느 단계에서든 변형 없이 그 목표를 정리한다.
    category: constraint
    applies_to: 모든 단계
    trigger: 목표 시계 만료
    logic: "IF goalActive >= goalActiveLimit(기본 10분) THEN 현재 장면을 정리하고 사유 TIME_LIMIT로 NEXT_GOAL 또는 CLOSING으로 간다."
    violation: n/a
    source: FR2.7
  # ---- BR7 기록
  - id: BR7.1
    statement: 도움 · 역할 전환은 분류 코드와 길이만 저장한다.
    category: constraint
    applies_to: V7 표 · 이벤트 · 로그
    trigger: 기록 시점
    logic: "도움 종류 · 예시 여부 · 동의 응답 · 시각 · 내용 길이만 저장한다. 도움 발화 원문 · 아이 전사 · 대본 문장 · 판정 입력은 표 · 이벤트 · 로그 · 지표에 쓰지 않는다. 정본 AC1.5.5의 '도움 내용' 기록은 이 범위로 좁힌다(U12-D2)."
    violation: 시험이 표식 문자열(marker)을 대화에 넣어 어느 기록에도 나타나지 않음을 단언한다(R-05)
    source: FR4.2, NFR2
  - id: BR7.2
    statement: 기록 이벤트의 모양.
    category: constraint
    applies_to: record.practice
    trigger: 정산
    logic: "flowVersion='practice-conversation-v1', scenes[] 필수 4필드(sceneRunId · goalRef · sceneKind · startedAt), facts는 계약 15종만, liveOutcome.timing, variantSkipReason. goalRef는 goalSelectionId. practice-record-v2 스키마를 통과해야 한다. 계약 · 스키마는 바꾸지 않는다."
    violation: 스키마 불일치는 정산 시험 실패
    source: FR4.2
  - id: BR7.3
    statement: 장면 노출 이력은 장면의 도입 재생이 성공한 뒤에만 남긴다.
    category: constraint
    applies_to: SceneRun 생성
    trigger: SCENE_INTRO 재생 PLAYED
    logic: "IF 도입 재생이 성공(PLAYED)했다 THEN 행 생성. 시작 전 미참여(실행부 소유)와 도입 재생 실패는 행이 없다. 도입 뒤의 무응답 미실시(BR3.6)는 행이 있고 TIMEOUT 사실과 liveTiming NONE을 담는다."
    violation: n/a
    source: FR4.3
  - id: BR7.4
    statement: 새 표는 보호자 삭제 · 정산 복구에 참여한다.
    category: constraint
    applies_to: V7 표
    trigger: 보호자 삭제 요청, 정산 복구
    logic: "삭제 참여자는 세션 삭제(순서 200) 앞(순서 190)에 새 표를 비운다. DeletionCompleted.Counts는 바꾸지 않는다. 정산 복구는 새 표에서 practice 값을 다시 만든다. V4 레거시 표 제약은 건드리지 않는다."
    violation: n/a
    source: FR4.1
  # ---- BR8 진행 단계 표시
  - id: BR8.1
    statement: 새 단계는 진행 표시 6값으로 접어 보낸다.
    category: calculation
    applies_to: progress()
    trigger: 보호자 라이브 화면 갱신
    logic: "functional-spec.md의 매핑표대로 접는다. 접기는 진행 표시 매핑에만 있고 저장 값 · 이벤트 · 기록은 새 단계를 사실대로 둔다."
    violation: 6값 밖 값이 나가면 프런트가 '현재 연습 단계를 확인할 수 없어요.'를 보이므로 시험 실패
    source: FR6.1
  # ---- BR9 설정
  - id: BR9.1
    statement: 시간 값은 mujoong.timing.conversation.* 키에서만 읽는다.
    category: constraint
    applies_to: 무응답 대기 · 집중 한도 · 목표 ACTIVE 한도
    trigger: 시계 건 때
    logic: "no-response-wait(10s) · focus-max(90s) · goal-active-limit(10m)를 운영 코드가 읽는다. 코드에 숫자 시간 값이 없다."
    violation: TimeLiteralScanTest 실패
    source: FR7.1, NFR3
  - id: BR9.2
    statement: 횟수 한도는 시간 값이 아닌 새 설정 키다.
    category: constraint
    applies_to: 다시 해 보기 횟수 · 상황 다시 설명 횟수
    trigger: 한도 확인
    logic: "mujoong.practice-flow.retry-max(기본 2) · mujoong.practice-flow.reexplain-max(기본 1)를 두 앱 설정이 아니라 realtime-activity 설정의 새 키로 두고 contracts/ai-io.md 표에 더하며 ConfigKeyContractTest에 등록한다. 스키마 · 이벤트 모양은 바꾸지 않고 문서 표만 갱신한다(계약 파일이 닿으므로 R-M10에 따라 CI 전 작업이 돈다)."
    violation: n/a
    source: FR7.1
  # ---- BR10 레거시 보존 · 구조 경계
  - id: BR10.1
    statement: 레거시 연습 코드 · 표 · 하한은 그대로 둔다.
    category: constraint
    applies_to: practice/ 패키지, V4 표, u5b-scope
    trigger: 모든 변경
    logic: "레거시 클래스 · V4 표 5개 · 삭제 참여자 · 복구 경로를 삭제하지 않는다. u5b-scope 줄 80% 하한과 기본 빈 실패 단언을 낮추거나 끄지 않는다."
    violation: R-F2 위반
    source: FR5.3
  - id: BR10.2
    statement: 새 클래스는 새 패키지에 두고 실행부와는 능력으로만 닿는다.
    category: constraint
    applies_to: 새 패키지
    trigger: 구현
    logic: "새 패키지(practiceflow)는 레거시 practice/를 import하지 않고, 실행부와는 PracticeOutput 능력과 사건으로만 닿는다. 실행부에는 호출 지점만 더한다. ArchUnit으로 강제한다."
    violation: ArchUnit 실패
    source: NFR6
```

## 규칙 요약

| 묶음 | 규칙 | 한 줄 |
|---|---|---|
| BR1 | 1.1 ~ 1.4 | 시작 API 연다 · practice 원문 전달 · 새 대본 읽기 · scriptVersion 분기 |
| BR2 | 2.1 ~ 2.5 | 참여 · 거부 1회 제안 · 재질문 · 시작 전 휴식 재안내(U12-D1) · 레거시 포트 비의존 |
| BR3 | 3.1 ~ 3.8 | 단계 집합 · 되묻기 · 재설명 · 무응답 · 판정 지시 형식 · 판정 중 시계 |
| BR4 | 4.1 ~ 4.4 | 도움 요청 · 수락 · 거절 · 불명확 |
| BR5 | 5.1 ~ 5.7 | 역할 전환 알림 · 역할별 금지 · 복귀 · 승인 원문 우선 · 제브 연습 모드 호출 · 막힘 처리 |
| BR6 | 6.1 ~ 6.4 | 변형 4조건 · 생략 사유 · 재시도 · 집중 · 목표 10분 한도 |
| BR7 | 7.1 ~ 7.4 | 원문 금지 기록 · 이벤트 모양 · 노출 이력 · 삭제 · 복구 |
| BR8 | 8.1 | 진행 6값 접기 |
| BR9 | 9.1 ~ 9.2 | 시간 키 읽기 · 횟수 키 신설 |
| BR10 | 10.1 ~ 10.2 | 레거시 보존 · 구조 경계 |
