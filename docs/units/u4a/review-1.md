# U4a 리뷰 1차 (2026-10-13)

대상: `unit/u4a-activity-core` c34c833(main 갈래점 fbdb5a5부터 6커밋). 정적 리뷰다 — 리뷰 환경에서 Maven · Docker Hub에 닿지 않아 시험은 돌리지 못했다. 시험은 팀 저장소 CI(main 합친 PR)에서 확인한다.

## 0. main 합치기

`origin/main`(775bd8d)을 합치면 충돌은 `FlywayConfIT` 한 곳이다(README '겹치는 곳'에 적힌 그대로). 풀이 값:

- 표 수: core 16(V1 12 + U2 V2 2 + U4a V3 2) · realtime 10(V1 7 + U3 V2 3) = **26**
- 이력: core 3행 · realtime 3행(BASELINE 포함) → 앱 역할 조회 `core_schema_history` 3, `realtime_schema_history` 3

`SchemaPrivilegeIT` · `timing.md` · `deviations.md`는 자동 병합으로 양쪽 행이 다 남는다. V3는 CREATE TABLE · CREATE INDEX뿐이라 `migration-approvals.txt` 줄이 필요 없다. 계약 파일 편차 표기는 `ContractFilesTest` 규칙을 그대로 대조해 통과를 확인했다.

## 1. 병합 전에 고칠 것

| # | 위치 | 문제 | 고칠 방향 |
|---|---|---|---|
| R1 높음 | `record/SessionRecordCapturedHandler.java:72-87` | 계정 삭제가 접수된 보호자의 SessionRecordCaptured를 저장하고 ActivityRecorded까지 낸다. 사본이 이미 지워진 뒤 온 이벤트는 폐기 로그 없이 끝난다. AC1.6.8("삭제 접수 뒤 도착한 전사 · 결과 이벤트는 저장하지 않고 폐기 로그 1건")과 U2 design 18행의 넘김(U4 몫)에 어긋난다. U6 구독자가 붙으면 삭제 접수된 계정의 전사가 결과 AI로 간다 | 같은 트랜잭션에서 `GuardianWriteLock.statusForShare`로 보호자 상태를 보고 ACTIVE가 아니면 저장 · 발행 없이 폐기 로그. 사본 없음도 폐기 로그. 시험 두 건(삭제 접수 뒤 도착 · 사본 지워진 뒤 도착) |
| R2 중간 | `activity/web/SessionController.java:76` | `GET /api/sessions/{id}`가 `require`만 써서 삭제 접수된 보호자 세션에도 전사가 든 LiveView를 200으로 준다(로그인 응답 저장 틈, 8d0f009가 인정한 경우). contract-summary 2절 "`/api/me` 외 403"과 `AuthController`·`DeletionCommander` 주석의 전제가 이 경로에서 깨진다 | 다른 보호자 API와 같은 삭제 상태 확인을 거친다. `/api/sessions/{id}` · `/api/sessions/current` · `/api/records*` 403 시험 |
| R3 중간 | `frontend/src/parent/activity/livePresentation.ts:20-25` | 시작 전(PREPARING · DEVICE_ACK, startedAt 없음)에 Realtime이 재시작돼 FAILED(SERVER_RESTART)가 되면 08이 아니라 17로 가서 "끝났어요 · 기기 응답 없이 끝냈어요 · 기록을 정리하고 있어요"를 보인다. AC3.6.9는 '진행 중' 서버 재시작만 17이다 | `startedAt === null`이면 시작 실패(08)로 본다. 시험 추가 |
| R4 중간 | `backend/core-api/src/test/.../U4aActivityApiIT.java:432 · 444` | 삭제 참가자를 `subList(0, 2)` · `get(0)` 순번으로 집는다. main이 U6 = 70 · U7 = 60을 정해서 U6 · U7이 들어오면 이 시험이 깨진다 | 타입(`ActivityRecordDeletionParticipant` · `ActivityDeletionParticipant`)으로 집는다 |
| R5 중간 | `record/...` 해시 시험(`U4aRecordUnitTest` · `U4aActivityApiIT.java:504`) | 기대 해시를 운영 코드와 같은 `JsonCodec.sha256OfCanonical`로 만들어 자기 자신과만 비교한다. U4b가 다르게 정규화하면 모든 기록이 SnapshotHashMismatch → DEAD로 사라지는데 잡지 못한다 | contract-model에 고정 record → 고정 `sha256:` hex 골든 값 시험을 두고, U4b 설계 J16 문장에 `sha256:` 접두와 null 처리 규칙을 적는다 |

## 2. 병합 뒤 바로(다음 PR) 고쳐도 되는 것

- `activity/StartActivityService.java:188-192 · 235-242` — 삭제 상태 확인이 잠그지 않은 읽기이고 사본 + 08 배경 쓰기 트랜잭션이 `lockActive`를 잡지 않는다. 트랜잭션 첫 문장을 `lockActive`로.
- `record/ActivityRecordDeletionParticipant.java` — 기록(90)과 사본(100)이 따로 트랜잭션이라 사이에 기록 이벤트가 끼면 FK RESTRICT로 한 바퀴 실패한다(다음 바퀴 회복). R1을 고치면 사라진다. 설계 R10 · J23 문장도 코드에 맞춘다.
- `SessionRecordCapturedHandler.java:81-87` — 충돌 판정이 record 해시만 본다. 머리(finalStatus · endReason · confirmed)만 다른 두 번째 이벤트가 조용히 무시된다. 충돌 APP_ERROR에 sessionId · eventId가 없다.
- `U4aCoordinatorUnitTest.java:154` — `catch (RuntimeException expected) {}`가 예외를 삼켜 두 번째 start가 성공해도 통과한다. `assertThatThrownBy` + `SESSION_IN_PROGRESS` 단언.
- `U4aActivityApiIT.java:283 · 534` — `Thread.onSpinWait()` 바쁜 대기 10초. Awaitility 또는 짧은 sleep 백오프. `:150-151`의 `containsExactly("CLOSING","WAITING_FILLER")`는 공용 DB에 시드 003이 없다는 전제를 단언 · 주석으로 남긴다.
- `infra/seed/003-u4a-reference.sql:23-44` — 002가 없으면 0행을 넣고 조용히 끝난다. 6종이 안 들어가면 실패하게.
- 화면
  - `Live12.tsx:242-265` — WAITING_CHILD에서 P1을 연 채 DECLINED · ABORTED를 받으면 P1과 P2 · P3가 겹친다. outcome이 바뀌면 P1을 닫는다.
  - `Home04.tsx:83 · 97` — 04에 다시 들어올 때 재조회 전까지 캐시의 지금 활동을 보이고 탭이 끝난 세션 12로 간다. 12 · 17이 종료를 받으면 home 캐시를 무효화하고 `isFetching` 동안 탭 이동을 막는다. 'n분째'가 화면에 머무는 동안 늘지 않는다(1분 타이머).
  - `donePresentation.ts:227` — 17 첫 렌더(데이터 전)에 '마무리 중'이 잠깐 보인다(AC3.6.10). `:206-207` FAILED(SERVER_RESTART) 본문에 ENDED_UNCONFIRMED 글(C.S-17.58.5 '30초 동안…')이 나온다.
  - `livePresentation.ts:89-104` — 끝난 세션의 열린 끊김 구간도 "대화 연결을 다시 잇고 있어요"로 보인다. 종료 상태에서는 "끊김"으로.
  - `queries.ts:127-131` — `mergeLiveView`가 statusVersion을 비교하지 않아 늦게 온 폴링 응답이 409 뒤 읽은 종료 상태를 덮는다(팝업 깜빡임).
  - `shared/copy/activity.ts:62` — 17 미확인 줄은 임시 문구 대신 mockups 9-33 "종료 확인 안 됨 · 마지막 확인 hh:mm".
  - 접근성: `Home04.tsx:152-188` 지금 활동 칸에 `aria-live=polite`가 없다. `NoticeDialog.tsx:32-37` 배경에 inert가 없어 Tab으로 하단 탭에 갈 수 있고, 그 시험(`screens-u4a.test.tsx:242-256`)은 jsdom에서 늘 통과한다.
- 계약
  - `record-detail-response.schema.json` recordScope null 허용, `/records` 400 · `/records/{id}` 404는 계약 3절보다 넓다 → (U4a-c) '해석 명시' 행과 표시.
  - `ContractFilesTest`가 유닛 접두 기호((U2-*) · (U4a-*))를 대조하지 않는다. U4b도 같은 방식을 쓰니 시험에 넣는다.
- `docs/units/u4a/report.md:144` "전체 표 수(지금 23)"는 U3 병합 뒤 26이다. 3절 시험 건수는 병합 전 기준임을 적는다.

## 3. CI에서 지켜볼 것

main이 CI를 `vitest run tests --coverage`(scope 없이 전체)로 바꿔서, 이 PR에서 처음으로 U1 · U2 · U3 · U4a 프런트 시험이 한 프로세스로 돈다. U4a report 3절에 적힌 `tests/u2/parent/child-03.test.tsx` 부하 시간 초과가 CI에서 다시 날 수 있다. 실패하면 그 시험의 시간 한도를 늘리거나 `--pool=forks` 등으로 나눈다(단언은 낮추지 않는다).

## 4. 뒤 유닛에 넘기는 것

| 받는 유닛 | 내용 |
|---|---|
| U4b | Core는 받은 record를 Map → `JsonCodec.write` → 정규화 → sha256으로 확인한다. Realtime도 같은 직렬화(null 처리 포함)여야 한다(R5 골든 값으로 대조) |
| U6 | ActivityRecorded는 메모리 이벤트(AFTER_COMMIT, 처음 저장할 때 한 번, FAILED · DECLINED 포함)라 앱이 죽으면 다시 오지 않는다 — 기록은 있는데 결과 작업이 없는 세션을 찾는 보정 장치가 필요하다. record.practice는 `practice_json`(jsonb)에 통째 저장되어 scenarioId · label · utteranceSeq · cues가 그대로 남고, `transcript_json`의 seq와 인용이 맞물린다. 27 끝난 이유 칸의 "hh:mm 종료 확인됨"과 9-33 "마지막 확인 hh:mm"에 쓸 시각이 `/records/{id}` 응답에 없다(termination은 저장만) — U6이 선택 필드로 더한다(편차). 26 줄의 시각 근거(listed_at)가 응답에 없어 DECLINED · ACTIVE 전 ABORTED 줄은 '시작하지 못함 · 시각'을 같은 기준으로 못 낸다 — U6이 `listedAt` 선택 필드를 더한다 |
| U7 | 알림 진입 재조회가 `/api/sessions/current`만 쓰는데 이 경로는 종료 세션이면 null이라 완료 푸시(AC1.7.7 → 17)를 누르면 04 '없음'이 된다. 푸시의 sessionId로 `/api/sessions/{id}`를 묻는 경로가 필요하다(U7 몫). `CoreInboxDispatcher`는 이벤트 종류당 처리기 하나라 U7은 SessionStateChanged 처리기를 따로 못 둔다 — 기존 처리기를 넓히거나 앱 내부 사건을 받는다. ACTIVE 도달 여부는 사본 `started_at`(순서 역전 시 null일 수 있음)보다 `core_activity_record`의 started_at · confirmed · end_reason · final_status가 확실하다 |
| U6 · U7 | `core_activity_record`를 가리키는 새 표는 삭제 순서 90보다, `core_activity`를 가리키는 표는 100보다 작게(README의 U6 70 · U7 60 그대로 맞다) |

## 5. 확인한 것(문제없음)

시작 멱등 재생 · 사본과 08 배경 한 트랜잭션 · 해시 검증 · 중복 무동작 · 충돌 시 첫 기록 유지 · ActivityRecorded AFTER_COMMIT · 남의 자원 404 · statusVersion 역행 무시 · 진행 중 6상태 · Asia/Seoul 날짜 경계와 (guardian_id, listed_at) 색인 · V3 더하기만 · 새 스키마 3개(2020-12 메타 스키마와 예시 통과, 금지 필드 거부) · 커버리지 범위 파일 12줄 · CoreSchemaIT 열 수 · 화면 문구(9-5 · 9-12 · 9-13 · 9-15 · 9-17 · 9-19, P2 · P3, C.S-17.53 F1-a)와 금지 표현 없음.
