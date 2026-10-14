# Requirements Analysis 질문 — U4b 활동 세션 Realtime 쪽

> U4b 요구사항은 대부분 이미 정해져 있다.
> - 원래 기록 `261008-mujoong-service`의 requirements · stories
> - U4b 설계 `docs/units/u4b/design.md`, 지시 `docs/units/u4b/brief.md`
>
> 아래 질문은 정본끼리 부딪히거나 정본에 답이 없는 것만 모았다.
> 각 질문의 `[Answer]:` 뒤에 고른 글자를 적는다(X면 내용도 함께).

## Q1. 스키마 판 기대값 시험의 수정 허용 범위

realtime V3(결과 스냅숏 표)를 넣으면, 스키마 판을 정확한 값으로 확인하는 기존 시험 3개가 실패한다.
- `RealtimeSchemaIT`(U1 시험): 표 10개, 최고 판 "2"
- core-api `FlywayConfIT`: realtime 이력 2건, 표 합계 33
- `SchemaPrivilegeIT`

그런데 U4b 지시서는 "U1 · U3 시험의 단언"과 `backend/core-api`를 건드리지 말라고 한다. 참고로 U3도 V2를 넣을 때 이 시험들의 기대값을 함께 고쳤다(커밋 47db8f2).

A. 스키마 판 · 표 수 · 이력 행 같은 **기대 숫자만** 새 값으로 고치는 것을 예외로 허용한다. 고친 시험과 줄은 report에 목록으로 남긴다. core-api는 이 시험 파일만 고친다.
B. A와 같되, core-api 시험은 건드리지 않는다. 대신 그 시험이 판 번호를 직접 세지 않게 바꾸는 방법을 찾는다.
C. 기존 시험은 그대로 두고, V3를 다른 방식으로 넣는다(예: 별도 스키마). 설계 변경이 필요하다.
X. Other (please specify)

[Answer]: A

## Q2. 흔들리는 U1 시험(RealtimeInternalApiIT) 고치는 방법

`RealtimeInternalApiIT`의 "Core 취소가 진행 중 세션을 닫고 모르는 세션에 묘비를 남긴다" 시험은 부하가 크면 202 대신 409를 받는다. 실행기 없는 CLOSING 행이 단언 전에 시험용 짧은 시한(마무리 확인 3초 · 고아 점검 1초)으로 먼저 닫히기 때문이다. 이 시험은 U4b 몫으로 지정돼 있다.

A. 그 행을 단언 바로 직전에 만들도록 시험 순서만 바꾼다. 단언 내용은 그대로 둔다.
B. 이 시험 클래스에만 시한을 길게 덮어쓴다(예: 마무리 확인 30초). 다른 시험은 그대로다.
C. A와 B를 둘 다 한다.
D. 이번에는 고치지 않고 남긴다(report의 남은 일로 넘긴다).
X. Other (please specify)

[Answer]: A

## Q3. 이번 작업 기록에서 반드시 끝낼 범위

설계는 일곱 층으로 나뉜다.
- L1 스키마 · 설정
- L2 전이표 · 끝내기
- L3 참여 확인
- L4 종료 의도 · 공백 · 끊김
- L5 결과 스냅숏 · SessionRecordCaptured
- L6 삭제 처리기
- L7 문서 · 검증

지시서는 시간이 모자랄 때 L2 · L3 · L5 · L6을 먼저 하고 L4 · L7을 뒤로 미루라고 한다. U5b가 L2 · L3 · L5에 기대고, U6이 L5의 스냅숏에 기댄다.

A. 일곱 층 전부를 이번 작업의 필수(Must)로 한다.
B. L1 · L2 · L3 · L5 · L6을 필수로, L4 · L7은 권장(Should)으로 둔다. 남으면 다음 PR로 넘긴다.
C. L1 · L2 · L3 · L5만 필수로 하고, 나머지는 다음 작업으로 미룬다.
X. Other (please specify)

[Answer]: A

## Q4. U5b가 붙을 연습 경로 확장점을 누가 언제 정하나

U5b(연습 Realtime)는 U4b의 세션 진행에 연습 경로를 붙인다. U5b 지시서는 처음 이틀 안에 두 사람이 이 확장점을 맞추라고 한다. 설계 U4b-R17은 "PRACTICE의 ACTIVE는 U5b 전까지 자유대화처럼 동작한다"까지만 정했다.

A. U4b가 확장점 인터페이스(연습 진행 위임, 목표 끝 → 다음 목표 · 마지막이면 CLOSING 신호)를 먼저 만든다. 기본 구현은 아무것도 하지 않는다. U5b는 그 인터페이스를 구현한다.
B. U5b 담당이 인터페이스 초안을 내면 U4b가 받아서 연결한다.
C. U4b는 확장점을 만들지 않는다. U5b가 U4b 병합 뒤 직접 SessionExecutor를 고친다.
X. Other (please specify)

[Answer]: A

## Q5. 새 시간 키를 계약 문서에 넣는 방식

U4b는 시간 키 8개를 연결해야 한다. 예약만 돼 있던 키 5개(참여 대기 · 재안내 뒤 대기 · 공백 경고 · 공백 종료 · 점선 표시)와, 설계에서 새로 만든 키 3개(확인 질문 대기 · 기록 정산 · 삭제 정지 대기)다.

키마다 `contracts/timing.md` · `application.yml` · 시험 yml · `RealtimeProperties`를 함께 고쳐야 자동 검사(ConfigKeyContractTest)가 통과한다. `contracts/timing.md`는 U5b도 연습 시간 키를 넣으려고 고치는 파일이라 병합 충돌 지점이다.

A. U4b가 8개를 설계 4절대로 한 커밋에 넣는다. U5b는 U4b 병합 뒤 main을 받아 자기 키를 덧붙인다.
B. A와 같되, U4b가 시간 키 커밋을 먼저 따로 main에 합친다(작은 PR). 그러면 U5b가 일찍 받아 갈 수 있다.
C. 새로 만든 키 3개는 코드 상수로 두고 계약 문서에는 넣지 않는다. 자동 검사(TimeLiteralScanTest)에 걸려 예외 처리가 필요하다.
X. Other (please specify)

[Answer]: A

## Q6. 승인 문구가 아직 시드에 없을 때의 시연 기준

참여 확인 · 확인 질문 · 공백 안내는 Core 승인 문구가 시작 패키지에 실려야 소리가 난다. 필요한 문구는 INTRO_FREE_TALK · RE_PROMPT · DECLINE_ALTERNATIVE · STOP_CONFIRM · SILENCE_WARNING이다. U4a가 이 문구들을 `infra/seed/003-u4a-reference.sql`에 넣었지만 음성은 합성음 자리다. 또 시드 003은 아직 검증 스크립트와 RUNBOOK의 시드 순서에 들어 있지 않다(U4a 남은 일). 설계 U4b-R15는 "문구가 없으면 재생만 건너뛰고 시계는 그대로 돈다"고 정했다.

A. 시험과 시연은 시드 003(합성음 자리) 기준으로 한다. U4b가 검증 스크립트의 시드 순서에 003을 넣는다. 문구가 빠진 경로는 단위 시험으로만 지킨다.
B. A에 더해, 개발용 시드에 문구가 없는 프로필도 하나 두고 끝-끝 시연을 한 번 더 돌린다.
C. 실제 녹음 승인 전까지는 참여 확인 소리 없이(R15 경로로) 시연해도 된다.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Q1 스키마 판 시험: 판 · 표 수 · 이력 행 같은 기대 숫자만 새 값으로 고치는 것을 예외로 허용한다. 고친 시험은 report에 목록으로 남기고, core-api는 그 시험 파일만 고친다(A).
- Q2 흔들리는 RealtimeInternalApiIT: 실행기 없는 CLOSING 행을 단언 직전에 만들도록 시험 순서만 바꾼다. 단언 내용은 그대로 둔다(A).
- Q3 필수 범위: 설계 7층(L1 ~ L7) 전부를 이번 작업의 필수로 한다(A).
- Q4 연습 확장점: U4b가 연습 진행 위임 · 목표 끝 신호 인터페이스와 아무것도 하지 않는 기본 구현을 먼저 만든다. U5b는 그것을 구현한다(A).
- Q5 시간 키: 8개를 설계 4절대로 한 커밋에 넣는다. U5b는 U4b 병합 뒤 자기 키를 덧붙인다(A).
- Q6 시연 기준: 시드 003(합성음 자리) 기준으로 시험 · 시연하고, U4b가 검증 스크립트 시드 순서에 003을 넣는다. 문구가 빠진 경로는 단위 시험으로 지킨다(A).

Does this all look correct before I generate the requirements artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
