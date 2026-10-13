# Functional Design 질문 — U4b 활동 세션 Realtime 쪽

> 전이표 · 시간 규칙 · 가정 U4b-J1 ~ J26은 설계(`docs/units/u4b/design.md`)에서 이미 정했다. 아래는 요구사항 단계의 열린 질문과 요구사항 검토에서 나온 지적(R-01 · R-05) 중, 설계로 정해야 하는 것만 모았다.

## Q1. U5b가 붙을 연습 경로 확장점의 모양

요구사항 FR9.1에서 확장점은 U4b가 먼저 만들기로 했다. 검토에서는 다음 지적이 나왔다(R-01).
- 연습 중 중간 종료(CHILD_STOP · SILENCE · DISCONNECT) 때 남은 목표를 정산하는 자리가 없다.
- 결과 스냅숏의 `record.practice`를 채울 자리가 없다.
- 이대로면 U5b가 실행기를 다시 고쳐야 한다.

A. 확장점 하나에 세션 생애의 네 시점을 둔다. 기본 구현은 넷 다 아무것도 하지 않는다(PRACTICE도 자유대화처럼 동작).
   - ACTIVE 진입 때 연습 시작
   - ACTIVE 중 아이 확정 전사 전달
   - 종료 전이 직후 정산(남은 목표 NOT_REACHED)
   - 스냅숏 직전 `record.practice` 값 제공
   - 확장점은 실행기에 "목표 끝 → 다음 목표 / 마지막이면 CLOSING" 신호를 돌려준다.
B. 요구사항에 적힌 두 시점만 둔다(ACTIVE 위임, 목표 끝 신호). 정산과 `record.practice`는 U5b가 실행기를 고쳐 붙인다.
C. A와 같되 시점을 더 잘게 나눈다(재생 완료 · 일시정지 시작/끝까지 전달). U5b 쪽 자유도는 크지만 인터페이스가 커진다.
X. Other (please specify)

[Answer]: A

## Q2. 커진 실행기(SessionExecutor, 1,832줄)를 나누는 방식

U4b 기능을 더하면 실행기가 2,500줄을 넘을 수 있다(codekb D6). 기존 코드는 판정을 순수 클래스(StopIntentJudge · TransitionTable)로 빼고 실행기는 연결만 하는 방식을 쓴다.

A. 기존 방식을 따른다. 새 판정 · 시계는 순수 클래스로 빼고, 실행기는 메시지 수신 · 타이머 · 부수 효과 연결만 맡는다.
   - 새 순수 클래스: 참여 흐름(JoinTracker 확장), 확인 질문 흐름, 공백 시계, 사건별 사유 결정, 결과 스냅숏 조립
   - 각 클래스는 가변 시계 단위 시험으로 검증한다.
B. A에 더해, 실행기를 "세션 진행"과 "마무리 · 정산" 두 클래스로 쪼갠다. 변경 범위가 커지고 U1 · U3 시험 도우미도 많이 고쳐야 한다.
C. 쪼개지 않고 실행기 안에 더한다. 변경은 적지만 파일이 계속 커진다.
X. Other (please specify)

[Answer]: A

## Q3. U3 종료 의도 동작을 새 규칙에 맞추는 방법

지금(U3)은 다음처럼 동작한다(codekb D8).
- STOP이면 출력만 막고 ACTIVE를 유지한다.
- 아이가 적극적으로 계속하겠다고 하면 막은 것을 푼다(CHILD_STOP_LIFTED).
- CONFIRM이면 사건만 남긴다.

U4b 설계(U4b-R4)는 STOP을 곧바로 CLOSING(CHILD_STOP)으로, CONFIRM을 확인 질문 흐름으로 바꾼다.

A. STOP이면 곧바로 CLOSING(CHILD_STOP)으로 간다. 그래서 "출력만 막고 유지 → 해제" 경로는 없어진다. "적극적 계속이면 대화를 잇는다"는 판단은 확인 질문 대기 안에서만 쓴다(확인 질문에 계속하겠다고 답하면 대화를 잇는다). U3 시험 중 해제 경로를 확인하던 기대값은 새 경로로 바꾸고 report에 목록을 남긴다(요구사항 FR11.3).
B. STOP은 CLOSING으로 바꾸되, 기기 재생 중 들어온 STOP은 U3처럼 출력만 막고 재생이 끝난 뒤 다시 판정한다.
C. U3 동작을 그대로 두고, CONFIRM만 확인 질문 흐름으로 바꾼다. 설계 U4b-R4와 어긋난다.
X. Other (please specify)

[Answer]: A

## Q4. 결과 스냅숏의 `record.practice` 값

정본끼리 표현이 다르다(검토 R-05).
- 요구사항 FR7.1: practice는 null
- 계약 6절: "DECLINED면 utterances · practice · audio가 빈 배열"
- 이벤트 스키마: record를 객체로만 정하고 practice 칸 모양은 정하지 않는다.

A. practice는 언제나 객체 또는 null로 둔다. 자유대화 · DECLINED · ACTIVE 전에 끝난 세션 · U5b 전의 PRACTICE 세션은 모두 null이다. 계약 6절의 "빈 배열"은 utterances · audio에만 해당한다고 보고 편차로 적는다.
B. DECLINED와 ACTIVE 전에 끝난 세션은 practice를 빈 객체 `{goals: []}`로 두고, 자유대화는 null로 둔다.
C. 계약 문장 그대로 DECLINED의 practice를 빈 배열 `[]`로 둔다. practice가 객체인 다른 경우와 모양이 달라진다.
X. Other (please specify)

[Answer]: A

## Q5. Realtime 삭제 참가자의 순서를 정하는 방식

지금 Realtime 삭제 참가자 둘(메시징 U1 · 음성 U3)은 공통 인터페이스 없는 개별 클래스다. U4b가 처리기에서 순서대로 불러야 한다. U5b는 연습 데이터 참가자를 세션 행보다 먼저 끼워야 한다(검토 R-01).

A. Realtime에도 공통 참가자 인터페이스와 순서 값을 둔다. 처리기는 순서대로 부르고, U5b는 순서 값만 정해 빈을 등록한다. Core와 같은 방식이다.
   - 음성 U3: 100
   - 연습 U5b: 150(자리만 비워 둔다)
   - 세션 U4b: 200
   - 메시징 U1: 900
B. 처리기가 세 참가자를 코드 안에 순서대로 직접 부른다. U5b는 처리기를 고쳐 자기 참가자를 끼운다.
X. Other (please specify)

[Answer]: A

## Q6. 연습을 끝까지 마친 정상 종료의 사유 (검토 뒤 추가 질문)

설계 검토에서 지적이 나왔다(R-02): 연습 마지막 목표 끝에 쓸 종료 사유가 없다. 계약의 사유는 8개로 닫혀 있고, 웹 푸시 '활동 완료' 알림은 CHILD_STOP · GUARDIAN_STOP · SILENCE로 끝난 경우에만 나간다.

A. CHILD_STOP을 다시 쓴다. 아이가 활동을 마친 것으로 보며, 계약을 바꾸지 않고 완료 알림도 나간다. end_detail에 PRACTICE_COMPLETED를 남겨 '아이가 그만하자고 함'과 구별한다.
B. PRACTICE_COMPLETED 같은 새 사유를 계약에 더한다. Core · U6 · U7 · 기기 종료 명령까지 바뀐다.
C. U4b는 기존 8개 중 하나를 확장점이 고르게만 하고, 값은 U5b 설계에서 정한다.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Q1 연습 확장점: 확장점 하나에 네 시점을 둔다. 기본 구현은 아무것도 하지 않는다(A).
  - ACTIVE 진입
  - 아이 확정 전사 전달
  - 종료 직후 정산(남은 목표 NOT_REACHED)
  - 스냅숏 직전 practice 값 제공
  - 확장점은 "목표 끝 → 다음 / 마지막이면 CLOSING" 신호를 돌려준다.
- Q2 실행기 분리: 새 판정 · 시계는 순수 클래스로 빼고 실행기는 연결만 맡는다(A).
  - 새 순수 클래스: 참여 흐름, 확인 질문 흐름, 공백 시계, 사건별 사유 결정, 스냅숏 조립
- Q3 종료 의도: STOP이면 곧바로 CLOSING(CHILD_STOP)으로 가고, "막고 유지 → 해제" 경로는 없앤다. "계속하겠다"는 확인 질문 대기 안에서만 쓴다. 바뀐 U3 시험 기대값은 report에 목록으로 남긴다(A).
- Q4 record.practice: 객체 또는 null만 쓴다. 자유대화 · DECLINED · 시작 전 종료 · U5b 전 PRACTICE는 null이다. 계약의 "빈 배열"은 utterances · audio에만 해당한다고 보고 편차로 적는다(A).
- Q5 삭제 순서: Realtime에 공통 참가자 인터페이스와 순서 값을 둔다(A).
  - 순서: 음성 100 · 연습 150(자리만) · 세션 200 · 메시징 900
- Q6 연습 정상 종료 사유: 마지막 목표를 마치면 CHILD_STOP으로 끝내고, end_detail에 PRACTICE_COMPLETED를 남긴다. 계약은 바꾸지 않고 완료 알림도 나간다(A).

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
