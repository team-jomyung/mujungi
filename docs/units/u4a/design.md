# U4a 활동 세션 · Core 쪽 (u4a-activity-core) 설계

> 기준: 화면 설계 v1.5j(문구표 · 화면과 분기) > `unit-of-work.md` U4 절 · `stories.md` US3.1 ~ US3.6 · US6.3 · US6.6 · US1.4(지금 활동) >
> `components.md` ActivityCoordinator · ActivityRecord > `contract-summary.md` 3 · 5 · 6 · 8절 > `contracts/` > U1 · U2 구현.
> 엔진(`/aidlc`) 없이 직접 구현한다. 가정은 "준하 제안(가정) U4a-J<번호>"로 적는다(맨 아래 표).
> U4는 둘로 나눈다: **U4a(이번)** = Core(ActivityCoordinator 완성 · ActivityRecord) + 보호자 화면, **U4b(U3 병합 뒤)** = Realtime
> SessionLifecycle(상태 기계 · 시간 규칙 · 스냅숏 발행 · Realtime 쪽 삭제 지휘). 두 쪽 사이는 이미 정해진 계약(C8 · SessionStateChanged ·
> SessionRecordCaptured)만 쓴다. `backend/realtime-activity`와 realtime 스키마는 건드리지 않는다.

## 1. 포함 스토리와 범위

| 스토리 | 화면 | 이번에 하는 것(Core · 화면) | U4b · 다른 유닛 몫 |
|---|---|---|---|
| US3.1 자유대화 시작 | 08 | AC3.1.1 ~ 3.1.8의 Core 판정(거절 사유 · 멱등은 U1 위에), **08 네 칸 저장**(AC3.1.2, 대화 모델로 보내지 않음 AC3.1.7), 동의 게이트 포트 연결, 세션 패키지(아이 문맥 · 종료 표현 스냅숏 AC2.3.7 · 동의 스냅숏 · 승인 문구 버전) | AC3.1.9 시한(B5)은 U1 PREPARING 점검 그대로, Realtime 쪽은 U4b |
| US3.2 활동 진행 보기 | 12 | 2초 폴링 중계에 gaps · technicalPause · silenceWarning · practice를 그대로 싣기(AC3.2.17), 상태 줄 · 말풍선 · 끊김 점선(AC3.2.10) · 공백 경고(AC3.2.8 표시) · 상태 확인 불가(3.2.11) · 앱 오프라인(3.2.12) · 시작 직후 라벨(3.2.16) · 탭 유지(3.2.13) | 값 생산(전사 · 공백 시계 · 끊김 시계)은 U3 · U4b, 연습 머리(3.2.2)는 U5 |
| US3.3 끝내기 | P1 | U1 경로 유지(AC3.3.1 · 3.3.2 · 3.3.6 · 3.3.8 · 3.3.9) | 마무리 인사 · 30초 확인(3.3.3 ~ 3.3.5)은 U3 · U4b |
| US3.4 예상치 못한 종료 | P2 | 12에서 ABORTED · DISCONNECT를 보면 P2 → 「확인」 → 17(AC3.4.1 · 3.4.2), 17 미확인 표시(3.4.3) | 40초 시계는 U4b |
| US3.5 아이 무응답 | P3 | 12에서 DECLINED를 보면 P3 → 「확인」 → 04(AC3.5.1 · 3.5.2) | 20초 · 재안내는 U3 · U4b |
| US3.6 활동 끝 | 17 | 마무리 중 · 종료 사유 한 줄(AC3.6.9 표 전부) · 정리 중 기본(3.6.2 앞부분 · 3.6.10) · 탭 잠금(3.6.6 · 3.6.7) · 「홈으로」(3.6.5) · 「오늘 기록 보기」는 U6까지 감춤(U4a-J14) | 정리 끝 · 정리 실패 · 27 연결(3.6.2 뒷부분 · 3.6.3 · 3.6.4 · 3.6.12)은 U6 |
| US6.3 시작 안내와 참여 확인 | (음성) | 세션 패키지에 자유대화 시작 안내 · 재안내 · 대안 · 공백 경고 · 종료 확인 문구를 **승인된 것만** 고정해 싣기(AC6.3.7 문구 버전 고정) | 재생 · 판단은 U3 · U4b |
| US6.6 연결 끊김 대응 | 12 · 17 | 12 점선 · 복구 중 · 미확인 구간 표시, 17 연결 끊김 사유 | 끊김 판정 · 재개는 U3 · U4b |
| US1.4 홈(지금 활동, U2가 남긴 것) | 04 | AC1.4.7 · 1.4.8 · 1.4.10 · 1.4.13 · AC1.1.6(재로그인 뒤 진행 중 표시) | 푸시 진입(AC1.7.4)은 U7(재조회 로직은 04에 있음) |
| US1.6 계정 삭제(U4 참가자) | — | Core 참가자에 08 배경 · 활동 기록 · 세션 사본을 넣음, 진행 세션 CONSENT_WITHDRAWN 끝내기는 U2 지휘가 이미 Core → Realtime으로 보냄(확인만) | Realtime 처리 · DeletionCompleted 발행은 U4b |
| 26 · 27 데이터(US5.1 · US5.2 의존) | — | `GET /api/records`(달력 · 그날 목록) · `GET /api/records/{id}`(대화 기록 칸) | 26 · 27 화면은 U6 |

연습 경로(연습 시작 · 단계 표시)는 U5가 붙인다. 이번에는 확장점(8절 J22)만 둔다.

## 2. 컴포넌트 · 패키지 (core-api)

| 컴포넌트 | 패키지 | 주요 클래스 |
|---|---|---|
| ActivityCoordinator | `kr.mujoong.core.activity` (U1 패키지 확장) | `StartActivityService`(동의 게이트 포트 · 아이 문맥 포트 · 08 배경 저장으로 바꿈) · `SessionPackageBuilder`(포트 값으로 조립 · 종료 표현 스냅숏 · 동의 스냅숏 · 승인 문구 고정) · `ActivityContextRepository`(08 배경) · `SessionViewService`(gaps · technicalPause · silenceWarning · practice 중계) · `CurrentSessionService`(04 · `/sessions/current`) · `PracticeStart`(U5 확장점, 구현 없음) · `web.SessionController`(`/sessions/current` 추가) |
| ActivityRecord | `kr.mujoong.core.record` (신설, `.web`) | `ActivityRecordRepository` · `SessionRecordCapturedHandler`(Inbox 처리기) · `ActivityRecorded`(앱 내부 사건) · `RecordQueryService`(26 · 27 응답, 뺄 것을 빼는 한 곳) · `RecordCalendar`(날짜 · 달 경계, Asia/Seoul) · `web.RecordController` |
| 삭제 참가자 | `kr.mujoong.core.activity` | U2가 만든 `ActivityDeletionParticipant`를 넘겨받아 08 배경 → 활동 기록 → 세션 사본 순서로 한 트랜잭션에서 지운다 |

U1 `ConsentGate`(아이 id 입력 · 정책 버전만 돌려줌)는 계약 8절 ConsentGate 포트(U2 `ConsentPolicyService.check(guardianId)` — 판정 · 항목별 스냅숏)로 바꾸고 지운다(J3).
HomeController(U1)는 `CurrentSessionService`로 `currentSession`을 채운다.

## 3. 엔터티와 마이그레이션 (`V3__u4a_activity_core.sql`, core 스키마만)

| 표 | 엔터티 | 열 | 제약 |
|---|---|---|---|
| `core_activity_context` | ActivityContext(08 배경) | id · session_id · guardian_id · child_id · known_content(≤200자) · situation_type · source_type · question(≤200자) · created_at | PK id, session_id 유일(세션당 하나), FK session_id → core_activity · guardian_id → core_guardian · child_id → core_child(RESTRICT), 칩 값 CHECK, 네 칸 중 하나는 있음 CHECK |
| `core_activity_record` | ActivityRecord | session_id · guardian_id · child_id · activity_type · final_status · end_reason · confirmed · started_at · ended_at · listed_at · record_from · record_to · transcript_json · gaps_json · practice_json · audio_refs_json · termination_json · source_snapshot_id · snapshot_version · snapshot_hash · recorded_at · created_at · updated_at | PK session_id, FK session_id → core_activity · guardian_id · child_id(RESTRICT), final_status는 종료 상태 5종, snapshot_hash 형식 CHECK, 색인 (guardian_id, listed_at) |

- 08 원문은 Core에만 둔다: 세션 패키지 · Realtime · 로그 · 응답 어디에도 내보내지 않는다(NFR3 · AC3.1.7). U6 결과 구조화가 읽는다.
- 활동 기록은 스냅숏을 그대로 옮겨 담는다(J6). 판정값(practice 안 label · cue)은 저장하지만 조회 응답에서는 뺀다(FR4.3 · A10).
- 열 삭제 · 이름 변경 · 필수화 없음. V1 · V2는 손대지 않는다.

## 4. API

`contracts/parent-api.yaml`에 경로를 더한다(계약 3절 경로 그대로). 모양이 계약 설명과 다른 곳은 편차 `(U4a-x)`.

| 연산 | 응답 | 비고 |
|---|---|---|
| `POST /api/activities/free`(U1) | 그대로 | context를 저장한다(모양 변화 없음). 거절 code는 U1 그대로(CONSENT_MISSING · SESSION_IN_PROGRESS · DEVICE_NOT_STABLE · REALTIME_UNAVAILABLE · VALIDATION_FAILED · IDEMPOTENCY_CONFLICT) |
| `GET /api/sessions/{id}`(U1) | LiveView | Realtime 값의 gaps · technicalPause · silenceWarning · practice를 그대로 싣는다(Realtime 실패 때는 U1처럼 사본 · 빈 값) |
| `GET /api/sessions/current` | 200 `{session: SessionSummary \| null}` | 진행 중(REQUESTED ~ CLOSING) 사본. 늘 `{session}`로 감싼다(편차 (U4a-a)) |
| `GET /api/home`(U1) | `currentSession` = SessionSummary \| null | 모양 그대로(U1 스키마가 이미 허용) |
| `GET /api/records?month=yyyy-MM` | `{days[{date, count}]}` | 종료 기록이 있는 날(DECLINED 포함 · FAILED 빼고, AC5.1.8 · B19) |
| `GET /api/records?date=yyyy-MM-dd` | `{items[{sessionId, activityType, startedAt, endedAt, finalStatus, endReason, linkable, goalCount, interrupted}]}` | DECLINED는 linkable false. goalCount · interrupted는 26 줄 글("목표 n개" · "중간에 끊김")에 필요해 더함(편차 (U4a-b)) |
| `GET /api/records/{sessionId}` | `{session: SessionSummary, goals[{displayName, order, status}], utterances: Bubble[], gaps[], recordScope{from,to} \| null, endConfirmed}` | DECLINED는 머리만(빈 배열). 판정값 · 구조화 결과 · 08 원문 · 원음 경로 없음. 남의 · 없는 · FAILED · 아직 기록 없음은 404 |

month · date는 둘 중 하나만(둘 다 · 없음 · 형식 오류는 400 VALIDATION_FAILED).

## 5. 업무 규칙

| 번호 | 규칙 | AC · 근거 |
|---|---|---|
| R1 | 시작 판정 순서는 U1 그대로(⓪ 보호자 · 멱등 재생 → ① 동의 → ② 진행 중 → ③ 기기). ①은 ConsentGate 포트(`ConsentPolicyService.check`)가 ok일 때만 통과, 아이가 없으면(ChildContext 빈 값) CONSENT_MISSING | AC3.1.6 · U2 6절 |
| R2 | 세션 패키지: childContext = 포트의 displayName · age · interests(계약 형식 밖이면 시작 실패, U1 규칙), stopExpressions = 기본 '무중아 그만할래' + 아이 추가 표현(포트 순서), consentSnapshot = 게이트 스냅숏, approvedContent = CLOSING · WAITING_FILLER(필수) + 자유대화 문구 5종 중 **승인 · 음성 있는 것만** | AC2.3.7 · AC6.3.7 · 계약 5 · 8절 |
| R3 | 08 네 칸 중 하나라도 있으면 사본 행과 **한 트랜잭션**에 배경 한 행을 쓴다. 멱등 재생은 다시 쓰지 않는다. 배경은 패키지 빌더에 넘길 길이 없다(인자 없음) | AC3.1.2 · AC3.1.7 · AC3.1.8 |
| R4 | 12 중계: Realtime view의 gaps · technicalPause · silenceWarning · practice를 그대로 싣는다(형식은 계약 스키마로 걸러 모르는 필드를 버림). 실패하면 U1처럼 사본 + 빈 목록 · null | AC3.2.17 · BR6.15 |
| R5 | SessionRecordCaptured 수신: ① 사본이 없거나 보호자가 다르면 저장하지 않고 처리 끝 ② `snapshotHash` = `sha256:` + record 정규화 JSON의 SHA-256이 아니면 처리 실패(재시도 → DEAD) ③ 같은 세션에 같은 해시가 이미 있으면 아무것도 안 함(멱등, ActivityRecorded 다시 안 냄) ④ 다른 해시면 첫 기록을 두고 충돌 로그 ⑤ 새 기록이면 저장 + ActivityRecorded(커밋 뒤 전달) | 계약 6절 · ADR-010 §2.5 · components ActivityRecord |
| R6 | DECLINED 기록은 머리 정보만 저장한다(utterances · gaps · practice · audio는 받아도 빈 값) | FR3.5 · AC3.5.1 |
| R7 | 26 목록: ENDED · ENDED_UNCONFIRMED · ABORTED는 linkable, DECLINED는 linkable false, FAILED는 보이지 않음. 날짜 · 달은 Asia/Seoul, 줄 시각은 startedAt(없으면 요청 시각) | AC5.1.8 · B19 |
| R8 | 27 응답에서 뺄 것: practice의 판정(label) · Cue · 상황 · 응답 묶음, 원음 경로, termination, 08 원문, 구조화 결과. 목표는 이름 · 순서 · 상태만 | FR4.3 · A10 · AC5.2.1 |
| R9 | 지금 활동 = 진행 중(REQUESTED ~ CLOSING) 사본 하나(보호자당 하나, V1 부분 유일 색인) | AC1.4.13 |
| R10 | 삭제 참가자: 08 배경 → 활동 기록 → 세션 사본, 한 트랜잭션 · 멱등 · 다른 보호자 행 무관. Inbox의 SessionRecordCaptured payload는 U1 메시징 참가자가 비운다 | J19 · BR5.14 |
| R11 | 진행 세션 CONSENT_WITHDRAWN 끝내기는 U2 `DeletionCommander`가 `RealtimeSessionClient.endForConsentWithdrawal`로 보낸다(요청당 한 번). U4a는 바꾸지 않고 시험으로 확인한다 | AC1.6.3 · R11(U2) |

## 6. 화면과 상태 (/parent)

| 화면 | 상태 | 표시 · 이동 |
|---|---|---|
| 04 지금 활동 | 진행 중(REQUESTED ~ ACTIVE) | "{아이} · 자유대화 n분째"(C.S-04.3.10, 시작 전이면 "준비하고 있어요") + "hh:mm 시작"(3.12) + 「진행 화면 보기 ›」(3.11) → 12 |
| | CLOSING | "{아이} · 자유대화 종료 처리 중"(mockups S-04 B1) + 「진행 화면 보기 ›」 → 17 |
| | 없음 · 잠김 | U2 그대로 |
| | 조회 실패 | "지금 상태를 불러오지 못했어요" · "마지막 확인 hh:mm" · 「다시 확인」(mockups 9-5). 진행 중 · 없음 라벨은 보이지 않음 |
| | 하단 「자유대화」 · 「연습」 | 진행 중이면 12(CLOSING이면 17)로(AC1.4.10) |
| 08 | 거절 | 사유 하나(mockups 9-12). 12에서 FAILED로 돌아오면 failureCode에 맞는 사유(DEVICE_NO_ACK, 그 밖은 U1과 같은 문구) |
| 12 | 공통 | U1 위에: 끊김 점선 · "hh:mm ~ hh:mm 잠깐 끊김"(C.S-12.12.7), 끊김 진행 중 · technicalPause "대화 연결을 다시 잇고 있어요"(9-15), 미확인 구간 "hh:mm ~ hh:mm 확인되지 않은 구간"(9-25), 공백 경고 "hh:mm 시작 · n분째 · 4분째 말이 없어요" + 카드 "2분 더 말이 없으면 활동이 끝나요" / "아이가 다시 말하면 그대로 이어져요."(C.S-12.51.3 ~ 6), 앱 오프라인 "실시간 상태를 불러오지 못하고 있어요 · 마지막 확인 hh:mm"(9-17) |
| | ABORTED · DISCONNECT | P2 "대화가 예상치 못하게 끝났어요" 「확인」 → 17 |
| | DECLINED | P3 "아이가 대답하지 않아 시작하지 못했어요" 「확인」 → 04 |
| | 그 밖의 종료 · CLOSING | 17. FAILED는 시작 전이면 08(사유), 시작 뒤면 17 |
| 17 | 마무리 중(CLOSING) | U1 그대로(C.S-17.73, 사유 줄 없음 — U4a-J21), 자유대화 · 연습 탭 잠김 「끝나는 중이에요」 |
| | 종료 | 제목 "오늘 자유대화가 끝났어요" + 시간 줄 + 사유 카드(AC3.6.9 표: GUARDIAN_STOP 9-19 · CHILD_STOP C.S-17.72.4 ~ 5 · SILENCE C.S-17.53.4 + F1-a · DISCONNECT C.S-17.71 · UNCONFIRMED · FAILED C.S-17.58) + "기록을 정리하고 있어요"(C.S-17.16.4, 정리 상태는 U6) + 미확인 줄(confirmed false · 기기 확인 없는 종료만) + 「홈으로」(「오늘 기록 보기」는 U6) |

## 7. 시험 계획 (태그 u4a)

- **단위**: 패키지 조립(종료 표현 스냅숏 · 동의 스냅숏 · 승인 문구 고정 · 배경을 넣을 길 없음), 기록 해시 · 멱등 · 충돌 · DECLINED · 사본 없음, 27 응답에서 판정값 · 08 원문이 빠짐, 달력 경계(Asia/Seoul 자정 · 달 끝), 쿼리 인자 검사.
- **통합(Testcontainers · 무작위 포트 · Realtime 계약 대역)**: 시작 → 배경 행 + 패키지 본문(대역이 받은 JSON), 멱등 재생에 배경 한 행, 12 중계 gaps 등 전달, `/sessions/current` · `/home` 지금 활동 11개 상태, SessionRecordCaptured 수신 → 저장 · ActivityRecorded 1회 · 재전송 0회, 26 · 27 응답과 계약 대조(OpenApiContracts), 계정 삭제 참가자(세 표 0건 · 다른 보호자 무관), CONSENT_WITHDRAWN 끝내기 확인, V3 스키마(열 · 제약).
- **화면(vitest)**: 04 지금 활동 상태별 · 조회 실패 · 탭 이동, 12 점선 · 복구 중 · 미확인 구간 · 공백 경고 · 오프라인 · P2 · P3, 17 사유 표 전부 · 정리 중 · 미확인 줄, 08 실패 사유.
- 커버리지 범위: `backend/coverage-scope/u4a.txt`(U4a가 새로 만든 운영 소스), `frontend/coverage-scope/u4a.json`. U4a가 고친 U1 · U2 파일은 그 유닛 범위로 잰다. 줄 커버리지 80% 이상.

## 8. 준하 제안(가정)

| 번호 | 준하 제안(가정) | 근거 |
|---|---|---|
| U4a-J1 | 08 배경은 네 칸 중 하나라도 있을 때만 세션당 한 행. 상태 열은 두지 않는다(수명 = 세션 사본과 같음). 시작이 로컬 FAILED로 끝나도 행은 남고 계정 삭제 때 지운다 | AC3.1.2 · components ActivityContext |
| U4a-J2 | 세션 패키지에 싣는 자유대화 문구는 INTRO_FREE_TALK · RE_PROMPT · DECLINE_ALTERNATIVE · SILENCE_WARNING · STOP_CONFIRM 중 승인(APPROVED) · 음성 있는 것만. 지금 시드는 DRAFT라 CLOSING · WAITING_FILLER만 실린다 | 계약 5절(approvedContent 필수 둘 + 추가 키 허용) · U2 시드 |
| U4a-J3 | U1 `ConsentGate`를 지우고 시작 판정은 계약 8절 포트(`ConsentPolicyService.check`) 하나로 한다. 판정 규칙은 같은 `ConsentStatusRule`이다 | 계약 8절 · U2 report 6절 |
| U4a-J4 | `GET /api/sessions/current`는 늘 `{session: …}`로 감싼다(계약 설명 'SessionSummary 또는 {session: null}'의 두 모양을 하나로) | 편차 (U4a-a) |
| U4a-J5 | 04 경과 'n분째'는 화면 시계로 센다(홈 응답에 서버 시각 없음). 시작 전(startedAt 없음)이면 "준비하고 있어요" | AC1.4.7 · mockups 9-13 |
| U4a-J6 | 기록 해시는 `sha256:` + record 객체의 정규화 JSON(JsonCodec.sha256OfCanonical) SHA-256. 불일치는 처리 실패로 두어 DEAD까지 간다(조용히 버리지 않음) | 계약 6절 snapshotHash 설명 |
| U4a-J7 | 사본이 없거나 보호자가 다른 기록 이벤트는 저장하지 않고 PROCESSED(지워진 계정 · 잘못된 이벤트). 같은 세션 다른 해시는 첫 기록을 두고 APP_ERROR 한 줄(EventConflict) | ADR-010 §2.5 |
| U4a-J8 | 26 날짜 · 달 경계는 Asia/Seoul(화면 시각 표시와 같음). 줄 시각 = startedAt, 없으면(DECLINED) 사본 요청 시각 | 화면 timeText.ts |
| U4a-J9 | 27 상세의 404: 남의 세션 · 없음 · 기록 아직 없음 · FAILED. 기록이 아직 없을 때 정리 중 표시는 U6 결과 상태 API 몫 | AC5.1.8 |
| U4a-J10 | 26 줄에 goalCount(연습 목표 수, 자유대화는 0) · interrupted(gaps가 있음)를 더한다 | 편차 (U4a-b) · AC5.1.2 |
| U4a-J11 | 27 대화 기록 응답에 08 배경(보호자 입력 원문)을 넣지 않는다 — 지시(보호자 입력 원문은 응답에서 뺌)를 AC5.2.2 '보호자 입력 배경' 표시보다 앞에 둔다. 27에 배경을 보일지는 U6가 결과 API와 함께 정한다 | 지시 · FR4.3 · A10 |
| U4a-J12 | 12는 ABORTED · DISCONNECT를 보면 P2, DECLINED를 보면 P3를 띄운다. 17로 바로 들어온 ABORTED는 P2 없이 사유 카드만 | AC3.4.1 · AC3.5.1 |
| U4a-J13 | 17 CHILD_STOP 둘째 줄의 표현은 기본 표현 '무중아 그만할래'로 쓴다(LiveView에 인식된 표현이 없다) + 종료 시각 | C.S-17.72.5 |
| U4a-J14 | 17은 종료 상태면 늘 "기록을 정리하고 있어요"를 보인다. 「오늘 기록 보기」는 정리 상태 · 27이 생길 때(U6)까지 감춘다(U1 J120 단언 유지 — 처음 계획한 '보이되 꺼 둠'에서 바꿈) | AC3.6.10 · J18 · J120 |
| U4a-J15 | 17 종료 확인 미확인 줄(confirmed false · DECLINED 아님)은 임시 문구 "기기 종료 확인 · 확인 안 됨" | AC3.4.3 |
| U4a-J16 | 12 · 17에서 FAILED: failureCode가 SERVER_RESTART(진행 중 서버 재시작, U4b 3.1 표)면 17 '기기 응답 없이 끝냈어요', 그 밖 · 코드 없음(Core 로컬 FAILED)은 시작 실패라 08로 돌아가 사유를 보인다. DEVICE_NO_ACK · 코드 없음은 U1과 같은 `?failed=1`, PROVIDER_UNAVAILABLE · SCENARIO_GEN_FAILED만 `&reason=코드`(처음 계획한 startedAt 기준에서 바꿈 — U4b 3.1 표와 U1 단언에 맞춤) | AC3.6.9 · AC3.2.4 · AC3.2.5 · U4b 3.1 |
| U4a-J17 | 12 앱 오프라인 줄은 마지막 폴링이 응답 없음(네트워크 · 시간 초과)으로 실패했을 때 보이고 마지막 성공 시각을 쓴다 | AC3.2.12 |
| U4a-J18 | ActivityRecorded는 Spring 앱 이벤트로, 기록 저장 트랜잭션 안에서 내고 구독자는 커밋 뒤(@TransactionalEventListener AFTER_COMMIT) 받는다. U4a에는 구독자가 없다(U5 · U6) | 계약 8절 in-app-events |
| U4a-J19 | 연습 확장점은 계약 8절 PracticeStart 포트 인터페이스만 둔다(구현 · 빈 없음). 시작 서비스 · 패키지는 자유대화만 만든다 | J17 · 지시 |
| U4a-J20 | 세션 승인 문구 6종(INTRO_FREE_TALK · INTRO_PRACTICE · RE_PROMPT · DECLINE_ALTERNATIVE · SILENCE_WARNING · STOP_CONFIRM)의 승인 버전 v2를 참조 시드 `infra/seed/003-u4a-reference.sql`로 넣는다(앞 DRAFT v1은 RETIRED). 음성은 U1 J102와 같은 합성음 자리(`tools/seed/gen-session-wav.mjs`)이고 실제 녹음 · 사람 승인은 D 담당이 v3로 더한다. 시작 안내는 AC6.3.1 순서(AI라는 사실 · 오늘 활동 · 글 기록과 보호자 열람 · "무중아 그만할래")를 지킨 뒤 C.S-12.11.4 인사 · 질문으로, 대안 제안은 AC6.3.4대로 다른 선택지를 한 번 묻는 글로 바꿨다(002 v1 글은 마무리 인사에 가까웠다). NFR2 검사는 지금 있는 시드 금지 표현 사전(Realtime gate/seed-dictionary.json)으로 한다 | U4b 설계 8절 1 · U4b-R15 · AC6.3.1 · AC6.3.4 · AC6.3.9 · U2-J17 |
| U4a-J21 | 17 마무리 중(CLOSING)은 SessionStateChanged에 실려 오는 마무리 사유(U4b-J7)를 사본에 두지만 화면에는 사유 줄을 내지 않는다 — 화면 설계 C.S-17.73(마무리 중 그림)에 사유 줄이 없고, 화면 설계가 U4b 설계 3.1 끝 문장('17 마무리 중이 사유 줄을 낼 수 있다')보다 앞선다. 종료 뒤(ENDED 등) 사유 카드는 AC3.6.9 표대로 낸다 | 기준 순서 1 · AC3.6.1 · C.S-17.73 |
| U4a-J22 | 기록 해시는 두 앱이 같은 contract-model `JsonCodec.canonicalize`(키 정렬 · 공백 없음 · UTF-8)로 정규화한 record의 SHA-256 소문자 16진에 `sha256:`을 붙인 값이다. U4b-J16(키 정렬 · 공백 없는 JSON의 UTF-8 sha256)과 같은 정의이고, Realtime도 같은 함수를 써야 숫자 · 문자 표기가 어긋나지 않는다 | 계약 6절 · U4b-J16 · U4a-J6 |
| U4a-J23 | 활동 기록 삭제 참가자 순서는 90(`ORDER_ACTIVITY_RECORD`, 사본 100보다 앞). U5a가 더할 `ORDER_PRACTICE`는 90 · 100 · 200 · 250 · 300 · 900과 다른 값을 쓴다(같은 값은 순서가 정해지지 않는다 — DeletionParticipant 주석) | J58 · 조정자 지시 |
| U4a-J24 | 끝내기 전달은 사본이 종료 상태일 때만 Core가 409로 끊고, 그 밖(WAITING_CHILD 포함)은 Realtime 응답을 그대로 쓴다(U1 J54). 계정 삭제의 CONSENT_WITHDRAWN 끝내기는 U2 지휘가 보내고 응답(202 {CLOSING} · 202 {} · 409)과 관계없이 세션 대기 시한(30초) 뒤 데이터 삭제 · DeletionRequested로 넘어간다 — U4b 3.2 표 · U4b-J19(강제 정지)와 맞다 | U4b 설계 8절 2 · 6 · AC1.6.3 |
| U4a-J25 | `/api/sessions/current`는 지금 Core 사본만 읽는다(Realtime에 묻지 않음). U4b가 CLOSING에 사유를 실어 보내므로 04 '종료 처리 중'과 17이 같은 사본을 본다 | U4b 설계 8절 3 · R9 |

## 9. U4b 설계 8절('U4a · Core와 맞출 것')과의 대조

U4b 설계(`origin/claude/friendly-shannon-i89iyx:docs/units/u4b/design.md`, 2026-10-12)의 8절 여섯 가지와 이 구현을 맞췄다. 어긋남이 있던 곳만 고쳤다.

| # | U4b 기대 | U4a 구현 | 처리 |
|---|---|---|---|
| 1 | 시작 패키지 approvedContent에 INTRO_FREE_TALK · RE_PROMPT · DECLINE_ALTERNATIVE · STOP_CONFIRM · SILENCE_WARNING(연습이면 INTRO_PRACTICE) | 시작 서비스는 이 다섯을 '승인 · 음성 있는 것만' 싣는데(U4a-J2) 시드가 DRAFT라 하나도 실리지 않았다 | **고침**: 시드 003 + 합성음 6개(U4a-J20). 실제 시작 패키지에 일곱 키가 실리는 것을 시험(SeedU4aReferenceSqlIT). INTRO_PRACTICE는 시드만(연습 패키지는 U5) |
| 2 | WAITING_CHILD도 GUARDIAN_STOP에 202 {CLOSING}, CONSENT_WITHDRAWN은 3.2 표 | Core 전달은 이미 맞음(U4a-J24). 12 「이야기 끝내기」는 ACTIVE만 켜졌다 | **고침**: 12가 WAITING_CHILD에서도 켬. Core 시험 추가(U4aActivityApiIT WAITING_CHILD 끝내기) |
| 3 | CLOSING에 endReason, DECLINED = DECLINED, ENDED_UNCONFIRMED = UNCONFIRMED | U1 처리기가 상태와 관계없이 사유를 사본에 쓴다. 17 사유 표는 상태 · 사유로 고른다 | 맞음. 시험 추가(상태 사건 → 사본 사유 · 지금 활동). 마무리 중 사유 줄은 화면 설계대로 내지 않음(U4a-J21) |
| 4 | SessionRecordCaptured는 세션당 한 번, 정산 뒤, record 모양은 계약 6절 | 처리기가 멱등 · 해시 확인 · 충돌 로그 · DECLINED 머리만 | 맞음. 해시 정의를 U4b-J16과 같게 적음(U4a-J22) |
| 5 | 진행 조회에 technicalPause · silenceWarning · gaps(DISCONNECT · UNCONFIRMED) | Core가 계약 모양으로 걸러 중계(LiveExtras), 12가 점선 · 복구 중 · 공백 경고에 씀 | 맞음 |
| 6 | Core가 끝내기를 보내고 30초 기다린 뒤 DeletionRequested, Realtime은 강제 정지 뒤 삭제 | U2 지휘가 진행 사본(WAITING_CHILD 포함)에 CONSENT_WITHDRAWN을 한 번 보내고 30초 대기 뒤 참가자(기록 90 → 배경 · 사본 100 → …) → DeletionRequested | 맞음(U4a-J24 · J23) |
