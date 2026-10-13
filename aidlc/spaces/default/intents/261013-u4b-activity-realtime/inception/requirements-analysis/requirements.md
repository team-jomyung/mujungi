# U4b 활동 세션 Realtime 쪽 — 요구사항 명세

> 작업 기록 `261013-u4b-activity-realtime` · Requirements Analysis(2.3) · 2026-10-13
>
> 이 문서는 원래 기록 `261008-mujoong-service`의 요구사항 중 U4b 몫을 추려 구현 단위로 다시 묶은 것이다. 새 업무 요구사항은 만들지 않았다. 출처가 서로 다르면 다음 순서로 따른다.
> 1. 화면 설계 v1.5j
> 2. 원래 기록의 승인 산출물: requirements · stories · contract-summary
> 3. `docs/units/u4b/design.md`(U4b 설계)
> 4. 이 단계의 답변
>
> 원래 기록의 요구사항 ID는 `원 FR7.1`처럼 앞에 "원"을 붙여 구분한다. 이 문서의 `FR{n}` · `NFR{n}`은 이 작업 기록 안에서만 쓰는 추적 키다.

## 의도 분석

- **목표**: U1(세션 경로)과 U3(음성 경로) 위에 활동 세션의 Realtime 쪽 생애를 완성한다. 아이가 참여를 확인해야 대화가 시작되고, 어떤 이유로 끝나든 같은 순서로 마무리되며, 끝난 세션의 기록이 Core 결과 처리로 한 번 넘어가고, 계정 삭제 때 Realtime 데이터가 모두 지워지게 한다.
- **왜 지금인가**: 뒤 유닛이 모두 이 결과에 기댄다.
  - U5b(연습 Realtime): 참여 확인 · 전이표 · 결과 스냅숏과 연습 경로 확장점
  - U6(결과와 리포트): 결과 입력 스냅숏
  - U7(웹 푸시): 상태 사건과 사유
- **유형 · 범위 · 복잡도**: 기존 코드 확장(brownfield). 범위는 `backend/realtime-activity` 한 앱과 `contracts/` 문서다. 복잡도는 높다. 시간 규칙 여러 개와 상태 전이가 세션당 직렬 실행기 하나에 모인다.
- **깊이**: Standard. 정본 설계가 이미 있어 질문은 정본끼리 부딪히는 곳 여섯 가지만 물었다(`requirements-analysis-questions.md`).

## 기능 요구사항

### FR1 세션 상태 기계와 종료 사유

**FR1.1 전이표** — Must · 출처 원 FR7.1, 설계 3.1 · U4b-R1
- Realtime 세션은 10개 상태를 쓴다: PREPARING · DEVICE_ACK · WAITING_CHILD · ACTIVE · CLOSING · ENDED · ENDED_UNCONFIRMED · DECLINED · ABORTED · FAILED. REQUESTED는 Core 사본에만 있다.
- 전이는 설계 3.1 전이표에 있는 것만 성립한다. 조건부 UPDATE의 승자 하나만 이기고, 전이마다 status_version + 1과 SessionStateChanged를 같은 트랜잭션에 쓴다. 종료 상태는 되돌아가지 않는다.
- 기기 준비와 공급자 시작이 모두 오면 세션은 ACTIVE가 아니라 WAITING_CHILD로 간다.
- 완료 기준
  - 전이표의 칸마다 성립하는 전이와 성립하지 않는 전이를 단위 시험으로 확인한다.
  - 표에 없는 전이 요청은 상태를 바꾸지 않고 SessionEvent로 남는다.

**FR1.2 사건이 정하는 종료 사유** — Must · 출처 원 FR7.1, 설계 3.1 · U4b-R1 · U4b-J7
- 종료 사유 8종(GUARDIAN_STOP · CHILD_STOP · SILENCE · DISCONNECT · CONSENT_WITHDRAWN · UNCONFIRMED · DECLINED · FAILED)은 도착 상태가 아니라 전이 사건이 정한다.
- CLOSING에 들어갈 때 end_reason에 마무리 사유를 쓴다. 그래서 SessionStateChanged(CLOSING)에 사유가 실린다.
- ENDED는 그 사유를 유지한다. ENDED_UNCONFIRMED는 사유를 UNCONFIRMED로 덮고 처음 사유를 end_detail에 남긴다.
- 완료 기준
  - 같은 CLOSING → ENDED 경로가 GUARDIAN_STOP · CHILD_STOP · SILENCE · CONSENT_WITHDRAWN을 각각 그대로 실어 끝나는 것을 시험으로 확인한다.
  - 미확인 종료는 UNCONFIRMED와 처음 사유가 함께 남는다.

**FR1.3 끝내기 요청 응답표** — Must · 출처 원 FR3.3, 설계 3.2 · U4b-R8
- `POST /internal/sessions/{id}/end`는 사유 × 상태에 따라 설계 3.2 표대로 답한다.
- WAITING_CHILD도 보호자 끝내기를 받으면 202 {status: CLOSING}이고 CLOSING(GUARDIAN_STOP)으로 간다.
- 같은 requestId든 다른 requestId든 멱등이다. 끝내기 요청 1건, 기기 종료 명령 1회, 전이 1회만 일어난다.
- 완료 기준: 응답표의 칸마다 HTTP 상태 · 응답 본문 · 결과 상태를 시험으로 확인한다. 응답 본문은 스키마 그대로다.

**FR1.4 동의 철회 끝내기** — Must · 출처 원 FR1.6, 설계 U4b-R9 · U4b-J9
- 시작 단계(PREPARING · DEVICE_ACK)는 FAILED(CONSENT_WITHDRAWN)이고 202 {}로 답한다.
- WAITING_CHILD · ACTIVE는 CLOSING(CONSENT_WITHDRAWN)이다. 마무리 인사와 기기 확인 30초는 그대로다.
- 철회를 받은 시각 뒤에 도착한 아이 전사와 원음 프레임은 저장하지 않는다.
- 지금은 이 요청이 400으로 거절된다. 이 경로를 연결한다.
- 완료 기준: 철회 시각 이후의 전사 · 원음 행이 0건인 것을 시험으로 확인한다.

**FR1.5 재시작 · 고아 세션** — Must · 출처 원 FR7.1, 설계 U4b-R10
- 서버 재시작 · 고아 점검 · 실행기 최상위 예외로 닫는 세션에 WAITING_CHILD를 포함한다. 결과는 FAILED(SERVER_RESTART)다.
- Core 취소(CORE_ABANDONED)도 WAITING_CHILD를 포함한다.
- 닫은 세션마다 결과 입력 스냅숏을 만든다(FR7).
- 완료 기준: WAITING_CHILD 세션이 재시작 뒤 FAILED가 되고 스냅숏 1건이 남는다.

### FR2 참여 확인 (WAITING_CHILD)

**FR2.1 시작 안내와 참여 창** — Must · 출처 원 FR3.5 · 원 NFR6, 설계 U4b-R2 · U4b-J4
- WAITING_CHILD에 들어가면 입력 중계를 연다. 이때 원음은 기록하지 않는다(U4b-J2).
- 활동 종류에 맞는 시작 안내(INTRO_FREE_TALK · INTRO_PRACTICE)를 승인 문구로 재생한다.
- 그 재생의 완료 보고(COMPLETED · STOPPED · FAILED)를 서버가 받은 시각부터 참여 창을 연다. 처음 창은 join-wait(20초), 안내 뒤 창은 join-wait-after-reprompt(20초)다.
- 창 안에 온 아이 확정 전사를 분류해 다음 할 일을 정한다.
  - 시작 → ACTIVE
  - 재안내 · 되묻기 → RE_PROMPT
  - 대안 → DECLINE_ALTERNATIVE
  - 거절 → DECLINED
- 안내는 처음 안내를 포함해 최대 3번이다. 무응답 2회 또는 거부 2회면 DECLINED다.
- 완료 기준
  - 19초 · 21초 경계에서 창이 정확히 닫히는 것을 가변 시계 시험으로 확인한다.
  - 안내 최대 3번, 불명확한 대답, 주제 거부를 각각 시험으로 확인한다.
  - 기술 일시정지 동안 창이 멈췄다가 남은 시간부터 이어지는 것을 시험으로 확인한다(U4b-J5).

**FR2.2 참여 판정기 되살리기** — Must · 출처 설계 U4b-J4, brief 「먼저 할 것」 1
- U3 리뷰에서 지운 `JoinTracker`와 그 시험을 커밋 `5354a32`에서 되살린다. 공개 메서드(promptPlayed · reply · tick · classify · phase · prompts)는 그대로 둔다.
- 생성자를 넓혀 두 창 시간(처음 창, 안내 뒤 창)을 받게 한다.
- 완료 기준: 되살린 시험이 통과하고, 두 창 시간이 각각 적용되는 시험이 더해진다.

**FR2.3 참여 전 공급자 발언권** — Must · 출처 원 NFR2, 설계 U4b-R3 · U4b-J3
- WAITING_CHILD와 종료 확인 대기 동안 시작된 공급자 응답은 `session.response.cancel`로 취소한다. 그 출력은 기기로 보내지 않는다.
- 발언권을 모델에 돌려줄 때(ACTIVE 진입, 확인 뒤 계속) `session.response.create`를 한 번 보낸다.
- 완료 기준: 가짜 공급자 기록으로 취소와 생성 요청 횟수를 확인한다. 참여 전에는 승인 문구 외의 AI 음성이 기기로 0건 나간다.

**FR2.4 승인 문구 말풍선과 빠진 문구** — Must · 출처 원 FR7.2, 설계 U4b-R14 · U4b-R15 · U4b-J1 · U4b-J18
- 기기에 보낸 승인 문구는 보낸 순간 AI 전사 한 행으로 남긴다(FINAL, approved_for_display true, 문구 원문). 대상은 시작 안내 · 재안내 · 대안 · 확인 질문 · 공백 안내 · 마무리 인사다. 대기 문구(WAITING_FILLER)는 남기지 않는다.
- 참여 전 아이 전사는 분류에만 쓰고 저장하지 않는다. DECLINED면 그 세션의 전사 행을 모두 지운다.
- 시작 패키지에 CLOSING · WAITING_FILLER 말고 다른 문구가 없으면 그 재생만 건너뛴다(곧바로 재생이 끝난 것으로 본다). 시계와 횟수는 그대로 진행하고 SessionEvent `APPROVED_CONTENT_MISSING`{code}를 한 번 남긴다.
- 완료 기준: 문구가 빠진 패키지로 참여 창이 그대로 돌아 DECLINED까지 가는 것을 시험으로 지킨다.

### FR3 종료 의도와 마무리

**FR3.1 아이 종료 의도** — Must · 출처 원 FR7.1, 설계 U4b-R4 · U4b-J6
- ACTIVE에서 아이 확정 전사의 종료 의도를 판정한다. 기기가 재생 중일 때 들어온 전사는 판정하지 않는다.
- STOP이면 CLOSING(CHILD_STOP)으로 간다.
- CONFIRM이면 확인 질문(STOP_CONFIRM)을 재생하고 출력을 막는다. 재생이 끝난 뒤 stop-confirm-wait(20초) 안의 첫 확정 전사를 본다.
  - 그 전사가 STOP이거나 참여 분류 ACCEPT면 CHILD_STOP으로 확정한다.
  - 그 밖의 대답이거나 대답이 없으면 대화를 잇는다.
- U3의 "STOP이면 출력만 막고 ACTIVE 유지"와 해제 경로는 이 규칙에 맞춰 정리한다. 바뀐 뜻은 가정(U4b-J)으로 남긴다.
- 완료 기준: STOP · CONFIRM → 확정 · CONFIRM → 무응답 · CONFIRM → 다른 말의 네 경로를 시험으로 확인한다. 종료 확정 뒤에는 이전 응답이 재생되지 않는다.

**FR3.2 공통 마무리 순서** — Must · 출처 원 FR3.3, 설계 U4b-R5 · U4b-J8
- 마무리 사유와 관계없이 같은 순서로 끝낸다.
  1. STOP_OUTPUT
  2. CLOSING 승인 문구 1회
  3. 그 재생 표시 뒤 CLOSE_TRANSPORT(사유)
  4. VOICE_SESSION_CLOSED면 ENDED, 30초가 지나면 ENDED_UNCONFIRMED
- DECLINED는 먼저 종료 상태로 바꾼 뒤 같은 인사 순서를 closing-confirm 상한 안에서 돌리고 CLOSE_TRANSPORT(DECLINED)로 끝낸다.
- 완료 기준: GUARDIAN_STOP · CHILD_STOP · SILENCE · CONSENT_WITHDRAWN · DECLINED 다섯 경로에서 마무리 인사가 정확히 1회 나가는 것을 확인한다.

### FR4 공백 시계와 자동 종료

**FR4.1 공백 경고와 6분 종료** — Must · 출처 원 FR7.9 · 원 NFR6, 설계 U4b-R6 · U4b-J10 · U4b-J11
- 공백은 ACTIVE에서만 잰다. 기준은 ACTIVE 진입 또는 아이의 빈 아닌 확정 전사다.
- 다음 동안은 세지 않고 리셋도 하지 않는다: 기기 재생 중, 서버 출력 대기, 기술 일시정지, 확인 질문 대기.
- 누적 4분이면 silenceWarning{silentSince, endsAt}을 켜고, 패키지에 SILENCE_WARNING이 있으면 한 번 재생한다. 안내 재생은 시계를 리셋하지 않는다.
- 누적 6분이면 CLOSING(SILENCE)이다. 아이 확정 전사가 오면 리셋하고 경고를 끈다.
- 완료 기준: 3분 59초 · 4분 1초 · 5분 59초 · 6분 1초 경계와 멈춤 조건을 가변 시계 시험으로 확인한다. 장애 시간을 공백으로 세어 자동 종료하는 경우가 0건이다.

### FR5 연결 끊김과 기술 일시정지

**FR5.1 점선 · 끊김 구간 · 40초 종료** — Must · 출처 원 FR3.5 · 원 NFR4, 설계 U4b-R7 · U4b-J12 · U4b-J13
- 끊김 시계는 묶인 연결의 마지막 기기 메시지 수신 시각 하나다.
- WAITING_CHILD · ACTIVE에서 다음 경우 technicalPause{kind: DEVICE, since}를 켜고 끊김 구간(DISCONNECT)을 연다. U3가 여는 구간과 같은 행이다.
  - 연결이 닫히면 바로
  - 연결이 열려 있어도 gap-display(10초) 동안 메시지가 없으면
- 메시지가 다시 오거나 연결이 다시 묶이면 구간을 닫고 일시정지를 끈다.
- 40초가 지나면 ABORTED(DISCONNECT)다. 구간은 열린 채 남는다. 지금은 ACTIVE에서만 끊김을 보는데, 이를 WAITING_CHILD까지 넓힌다. CLOSING에서는 끊김 전이가 없다.
- 완료 기준: 9초 · 11초, 39초 · 41초 경계를 가변 시계 시험으로 확인한다. 미확인 종료는 CLOSING 진입부터 ended_at까지를 UNCONFIRMED 구간으로 남긴다(U4b-J14).

### FR6 진행 조회

**FR6.1 일시정지 · 공백 경고 값** — Must · 출처 원 FR3.2, 설계 U4b-R13
- 내부 진행 조회는 실행기의 살아 있는 값 두 개(technicalPause · silenceWarning)를 돌려준다. 지금은 늘 null이다.
- 실행기 스레드가 불변 값 하나로 바꿔 쓰고, 조회는 그 값을 읽기만 한다. 종료된 세션은 둘 다 null이다.
- gaps에는 미확인 구간이 더해진다.
- 완료 기준: 일시정지 · 경고 중과 해제 뒤의 조회 응답을 통합 시험으로 확인하고, 응답이 스키마에 맞는다.

### FR7 결과 입력 스냅숏과 SessionRecordCaptured

**FR7.1 정산과 스냅숏 1회** — Must · 출처 원 FR7.3 · 원 FR7.4(입력), 계약 6절, 설계 U4b-R11 · U4b-J15 ~ J17
- 종료 전이 뒤 다음 순서로 정산한다.
  1. 공급자를 닫는다.
  2. 연결이 닫히거나 record-settle(2초)가 지나면 그 세션의 전사 기록기를 비운다.
  3. 스냅숏 행과 SessionRecordCaptured Outbox를 한 트랜잭션에 쓴다.
- record 모양은 계약 6절이다.
  - utterances: 확정 아이 전사 + 게이트를 통과한 AI 말 + 승인 문구. seq 순이고, uncertain은 review_needed를 따른다.
  - gaps
  - recordScope{from: started_at(ACTIVE 전에 끝났으면 생성 시각), to: ended_at}
  - practice: null. U5b가 채운다.
  - audio: 원음 메타, 저장 경로는 상대 경로
- DECLINED와 ACTIVE 전에 끝난 세션은 utterances · audio가 빈 배열이다.
- snapshotHash는 record를 키 정렬 · 공백 없는 JSON으로 직렬화한 UTF-8 바이트의 sha256이다. 세션당 한 번(snapshotVersion 1)만 만든다.
- 새 표 `realtime_result_input_snapshot`은 realtime V3 마이그레이션(`V3__u4b_session_lifecycle.sql`, 더하기만)으로 만든다.
- 완료 기준
  - 종료 다섯 경로와 재시작에서 스냅숏 1건 · 이벤트 1건이 남는다.
  - 이벤트 페이로드가 계약 스키마 검증을 통과한다.
  - 같은 record에 대해 해시가 결정적이다.

**FR7.2 늦은 사건** — Must · 출처 설계 U4b-R12
- 종료 전이 뒤 정산이 끝날 때까지 도착한 아이 확정 전사는, 발화 시작 시각이 ended_at보다 이르면 저장하고 아니면 버린다.
- 정산 뒤 들어온 것은 전이 0회이고 기록하지 않는다: 늦은 전사, 종료 세션의 기기 메시지, 늦은 heartbeat, 중복 ACK.
- 완료 기준: 정산 창 안팎의 늦은 전사가 각각 저장 · 폐기되는 것을 시험으로 확인한다.

### FR8 계정 삭제 처리기

**FR8.1 DeletionRequested 처리** — Must · 출처 원 FR1.6 · 원 NFR12, 설계 U4b-R16 · U4b-J19 · U4b-J20
- DeletionRequested를 받으면 다음 순서로 처리한다.
  1. 그 보호자의 종료되지 않은 세션 실행기를 강제 정지한다. 입력 중계 · 원음 기록 · 타이머 · 공급자를 닫고, 연결이 살아 있으면 CLOSE_TRANSPORT(CONSENT_WITHDRAWN)를 보낸다. DB 전이 · 이벤트 · 스냅숏은 만들지 않는다. 대기 상한은 deletion-stop-wait(5초)다.
  2. 참가자를 순서대로 부른다: 음성 참가자(U3) → 세션 참가자(U4b, 스냅숏 · 세션 사건 · 종료 표현 · 전사 · 원음 행 · 끊김 구간 · 세션 행을 FK 순서로 실제 삭제) → 메시징 참가자(U1).
  3. 마지막 트랜잭션에 DeletionCompleted{counts} Outbox를 쓴다. practiceRecords는 U5b 전까지 0이다.
  4. 그 행이 PUBLISHED가 되면 메시징 참가자를 한 번 더 부른다.
- 같은 deletionRequestId의 DeletionCompleted가 이미 있으면 다시 쓰지 않는다. 파일 삭제가 남으면 예외로 Inbox 재시도에 맡긴다.
- 완료 기준: 진행 세션이 있을 때와 없을 때, 재시도 멱등, 파일 삭제 실패, 늦은 사건을 통합 시험으로 확인한다. 삭제 뒤 그 보호자의 Realtime 행과 파일이 0건이다.

### FR9 연습 경로 확장점

**FR9.1 U5b용 확장점** — Must · 출처 설계 U4b-R17 · U4b-J25, 질문 Q4(A)
- PRACTICE 세션도 INTRO_PRACTICE와 참여 확인까지는 자유대화와 같다.
- U4b가 연습 경로 확장점 인터페이스를 먼저 만든다.
  - ACTIVE 뒤 연습 진행을 위임하는 진입점
  - 목표 하나가 끝났을 때 다음 목표로, 마지막 목표 뒤에는 CLOSING으로 가는 신호
- 기본 구현은 아무것도 하지 않는다. U5b 전까지 PRACTICE의 ACTIVE는 자유대화처럼 동작한다.
- 인터페이스 이름과 메서드는 U4b 설계 문서에 적고 U5b 담당에게 넘긴다(`docs/units/u5b/brief.md` 「U4b와 맞물리는 곳」).
- 완료 기준: 기본 구현으로 PRACTICE 세션이 자유대화와 같은 전이를 밟는 것을 시험으로 확인한다. 시험 대역 구현이 목표 끝 신호로 CLOSING을 일으키는 것을 시험으로 확인한다.

### FR10 시간 키 연결

**FR10.1 시간 키 8개** — Must · 출처 원 NFR6, 설계 4절 · U4b-J21, 질문 Q5(A)
- 예약만 된 키 5개(join-wait · join-wait-after-reprompt · silence-warning · silence-end · device.gap-display)를 `contracts/timing.md` 2절에서 1절로 옮긴다.
- 새 키 3개(session.stop-confirm-wait 20초 · session.record-settle 2초 · `mujoong.session.deletion-stop-wait` 5초)를 3 · 4절에 더한다.
- 키마다 `application.yml` · 시험 yml · `RealtimeProperties`를 한 커밋에서 함께 고친다. 운영 코드에는 시간 숫자 상수를 두지 않는다.
- U5b는 U4b가 병합된 뒤 main을 받아 자기 키를 덧붙인다.
- 완료 기준: ConfigKeyContractTest와 TimeLiteralScanTest가 통과한다. 모든 시간 값은 시험에서 줄여 주입할 수 있다.

### FR11 기존 시험과 검증 정비

**FR11.1 스키마 판 기대값 갱신** — Must · 출처 질문 Q1(A), codekb D1
- realtime V3 때문에 바뀌는 기대 숫자만 새 값으로 고친다: 스키마 판 · 표 수 · 이력 행. 대상은 `RealtimeSchemaIT` · core-api `FlywayConfIT` · `SchemaPrivilegeIT`다.
- core-api는 이 시험 파일만 고친다. 그 밖의 단언은 줄이거나 바꾸지 않는다. 고친 파일과 줄은 report에 목록으로 남긴다.
- 완료 기준: 바뀐 줄이 기대 숫자뿐인 것을 diff로 확인할 수 있다.

**FR11.2 흔들리는 시험 정리** — Must · 출처 질문 Q2(A), codekb D5, brief 「먼저 할 것」 2
- `RealtimeInternalApiIT`의 Core 취소 시험에서 실행기 없는 CLOSING 행을 단언 바로 직전에 만들도록 순서만 바꾼다. 단언 내용은 그대로 둔다.
- 완료 기준: 단언 줄이 diff에 나오지 않는다. 반복 실행에서 202가 안정적으로 나온다.

**FR11.3 활성화 경로 변경에 따른 기대값** — Must · 출처 설계 9절
- WAITING_CHILD가 ACTIVE 앞에 들어오면서 U4b가 뜻대로 바꾸는 동작이 있다: 활성화 경로, status_version 기대값, 승인 문구 말풍선으로 늘어난 전사 행 수. 이것에 한해 U1 · U3 시험 도우미와 기대값을 새 경로로 맞춘다.
- 단언을 줄이지 않는다. 고친 목록은 report에 남긴다.
- 완료 기준: U1 · U3 회귀 시험이 모두 통과하고 report에 고친 목록이 있다.

**FR11.4 시드 003과 끝-끝 검증** — Must · 출처 질문 Q6(A), U4a report 남은 일
- 검증 스크립트의 시드 순서에 `infra/seed/003-u4a-reference.sql`을 넣는다(001 → 002 → 003). 이 시드에 INTRO_FREE_TALK · RE_PROMPT · DECLINE_ALTERNATIVE · STOP_CONFIRM · SILENCE_WARNING이 있다.
- 시험과 시연은 이 시드(합성음 자리) 기준으로 한다. 문구가 빠진 경로는 단위 시험으로만 지킨다(FR2.4).
- 완료 기준: 시드 003을 넣은 끝-끝 검증이 통과한다. 그 검증 흐름은 참여 확인 → ACTIVE → 음성 1턴 → 종료 → 결과 스냅숏이다.

**FR11.5 계약 문서와 편차** — Must · 출처 설계 7절 · U4b-J23
- 바뀐 계약을 문서에 반영한다.
  - `contracts/deviations.md`에 (U4b-a) ~ (U4b-c)를 적는다.
  - `ai-io.md`에 `session.response.create`를 더한다.
  - `internal-api.yaml`에 끝내기 응답표 구체화를 반영한다.
  - 이벤트 문서에 CLOSING의 endReason과 record 모양을 반영한다.
- `docs/units/u4b/design.md`는 바뀐 곳만 고치고, `docs/units/u4b/report.md`를 새로 쓴다.
- 완료 기준: ContractFilesTest가 통과한다. report에 만든 것 · 시험 결과 · 계약 편차 · 가정 · 남은 일이 있다.

## 비기능 요구사항

| ID | 요구사항 | 기준(통과/실패) | 출처 |
|---|---|---|---|
| NFR1 | **시간 값은 한 곳**: 모든 시간 값은 `contracts/timing.md`가 정본이고 설정으로 주입한다 | ConfigKeyContractTest · TimeLiteralScanTest 통과. 운영 소스의 시간 숫자 상수 0건 | 원 NFR6 · 설계 4절 |
| NFR2 | **경계값 정확성**: 시간 규칙은 경계 ±1초에서 정확히 갈린다 | 19/21초 · 3:59/4:01 · 5:59/6:01 · 9/11초 · 39/41초 가변 시계 시험 전부 통과 | 원 NFR6 · 설계 4절 |
| NFR3 | **전이 승자 하나 · 멱등**: 동시 사건과 중복 요청이 와도 전이는 1회이고 종료 상태는 되돌아가지 않는다 | 중복 끝내기 · 늦은 사건 · 중복 ACK 시험에서 전이 1회 · 기기 종료 명령 1회 | 원 FR7.1 · AC3.3.8 |
| NFR4 | **개인정보 비노출**: 로그 · 오류 메시지 · Outbox 페이로드 · SessionEvent payload에 전사 원문과 원음을 넣지 않는다. 결과 스냅숏 record는 예외다(계약 6절) | 로그 · 이벤트 검사 시험에서 원문 0건 | 원 NFR9 · 유닛 공통 완료 조건 · 설계 U4b-J22 |
| NFR5 | **참여 압박 금지**: 참여 전 · 확인 대기 · 공백 안내에서 승인 문구 외의 생성 발화가 나가지 않는다 | FR2.3 시험에서 생성 발화 0건 | 원 NFR2 |
| NFR6 | **시험 커버리지**: `u4b` 태그와 `backend/coverage-scope/u4b.txt`(U1 형식, realtime 운영 소스)를 만든다 | 범위 줄 커버리지 80% 이상, 모듈 전체 줄 커버리지 80% 이상 유지 | org Testing Posture · brief · codekb 2절 |
| NFR7 | **회귀 없음**: U1 · U3 · U4a · U5a 시험이 모두 통과한다. FR11.1 · FR11.3 범위 밖의 단언은 줄이거나 바꾸지 않는다 | 회귀 명령(`-PincludeTags=u1` · `u3`)과 CI 전체 check 통과 | brief · 설계 9절 |
| NFR8 | **계약 적합성**: 내부 HTTP 응답 · 이벤트 페이로드는 `contracts/` 스키마를 따른다 | 스키마 검증 시험 통과, ContractFilesTest 통과 | 원 FR7.1 · ADR-019 |
| NFR9 | **삭제 완결성**: 계정 삭제 뒤 그 보호자의 Realtime 행과 원음 파일이 남지 않는다 | FR8.1 통합 시험에서 0건 | 원 FR1.6 · 원 NFR12 |

## 제약

- 건드리지 않는 것
  - core 스키마, `backend/core-api`. FR11.1의 시험 파일 하나는 예외다.
  - `/parent` · `frontend/src/parent/` · 기기 웹 `/device`
  - `README.md` · `contracts/README.md`
  - 다른 작업 기록(`261008-mujoong-service`)
- realtime 마이그레이션은 V3 하나만 더한다(더하기만). V1 · V2는 손대지 않는다. Flyway `outOfOrder`가 꺼져 있어 U5b(V4)보다 먼저 main에 들어가야 한다.
- 기존 표에 제약을 더해야 하면 CI 마이그레이션 검사에 걸린다. 근거를 적어 `infra/migration-approvals.txt`에 한 줄을 더하고 리뷰를 받는다.
- 기기 프로토콜에 새 type이나 값을 만들지 않는다. 필요한 값은 이미 있다.
- 구현은 `contracts/`만 믿고 하고, U4a 코드에 기대지 않는다.
- Realtime은 단일 인스턴스 · 세션당 직렬 실행기 구조를 유지한다.
- 실제 음성 공급자는 연결하지 않는다. `session.response.create`는 가짜 공급자로 검증한다.
- 브랜치는 `unit/u4b-activity-realtime`이다. 층마다 시험을 돌리고 `feat(u4b): …`로 커밋한다. main으로 PR을 열고 CI가 통과한 뒤 합친다.

## 가정

| 번호 | 가정 | 근거 |
|---|---|---|
| A1 | U4b 설계의 가정 U4b-J1 ~ J26은 그대로 유효하다. 구현하면서 달라지면 설계 문서를 고치고 report에 남긴다 | 설계 10절 · brief |
| A2 | U3 리뷰 수정(report 11 ~ 14절)이 바꾼 종료 의도 판정과 실행기 동작을 기준으로 삼는다. 설계가 그보다 먼저 쓰인 곳은 Functional Design에서 맞춘다 | brief 「정본」 |
| A3 | 시드 003의 합성음 자리 음성으로 시험 · 시연해도 기능 완료로 본다. 실제 녹음 승인은 U4b 범위 밖이다 | 질문 Q6(A) |
| A4 | 실행기가 커지는 문제는 판정을 순수 클래스로 빼는 기존 패턴으로 다룬다. 확정은 Functional Design에서 한다 | codekb D6 |
| A5 | 연습 확장점의 정확한 이름 · 메서드는 Functional Design에서 정한다. U5b 담당과 처음 이틀 안에 맞춘다 | 질문 Q4(A) · U5b brief |

## 범위 밖

- 연습 진행(PracticeProtocol) · 판정(ResponseEvaluator) · `record.practice` 채우기 · realtime V4 → U5b
- Core 쪽: 활동 시작 · 사본 · 화면 08 · 12 · 17 · P1 ~ P3, 승인 문구 내용과 시드 작성 → U4a(완료)
- 결과 처리 · 리포트 · 웹 푸시 → U6 · U7
- 실제 음성 공급자 어댑터와 세션 문구 실제 녹음
- 배포 파이프라인 · 운영 배포. 이 작업 기록에서 뺐다.

## 열린 질문

- 연습 확장점의 인터페이스 이름 · 메서드: Functional Design에서 정하고 U5b 담당과 맞춘다.
- 실행기 분리 범위: 어떤 판정을 순수 클래스로 뺄지 Functional Design에서 정한다(codekb D6).
- U3 종료 의도의 해제 경로를 CHILD_STOP 전이와 맞추는 구체 규칙: Functional Design에서 정하고 U4b-J로 남긴다(codekb D8).

## Sources

- 이 단계의 질문과 답: `requirements-analysis-questions.md` Q1 ~ Q6, 요약 확인 Looks correct
- 초기 설명(project-description.json): U4b 설계 · 지시를 정본으로 U1 · U3 위에 SessionLifecycle을 완성한다
- 원래 기록 요구사항: `aidlc/spaces/default/intents/261008-mujoong-service/inception/requirements-analysis/requirements.md`(FR1.6 · FR3.2 ~ FR3.5 · FR7.1 ~ FR7.3 · FR7.9, NFR2 · NFR4 · NFR6 · NFR9 · NFR12)
- 설계 · 지시: `docs/units/u4b/design.md`(1 ~ 10절), `docs/units/u4b/brief.md`, `docs/units/u5b/brief.md`(맞물리는 곳)
- 기존 코드 파악: `aidlc/spaces/default/codekb/mujungi/business-overview.md`(4절 완성도) · `architecture.md`(구조 · 흐름) · `code-structure.md`(배치) · `code-quality-assessment.md`(D1 ~ D14)
