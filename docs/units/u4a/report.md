# U4a 활동 세션 · Core 쪽 (u4a-activity-core) 보고

> 브랜치 `unit/u4a-activity-core`(U2가 합쳐진 main에서 시작). 설계는 `docs/units/u4a/design.md`(9절이 U4b 설계 8절 대조), 계약 편차는
> `contracts/deviations.md` "U4a 편차" 절. 엔진(`/aidlc`) · `scripts/verify.sh`는 쓰지 않았고 `aidlc/` 아래는 읽기만 했다. push하지 않았다.
> realtime 스키마 · `backend/realtime-activity` 코드는 건드리지 않았다(U3 · U4b 몫).

## 1. 커밋

| 커밋 | 내용 |
|---|---|
| `fix(u2)` 3차 리뷰 후속 9건 | 대기 바퀴 세션 정리 · 시험 로그인 경합 · JWKS 바닥값 · 비차단 받기 · 뒤로가기 가드 넘겨받기 · 잠금 시험 정규식 · 잠금 호출 통일 · 시험 연결 시한 500ms · U2 report 3차 표 |
| `docs(u4a)` 설계 | 스토리 · 엔터티 · API · 규칙 · 화면 · 시험 계획 · 가정 U4a-J1 ~ J19 |
| `feat(u4a)` Core | V3 마이그레이션 · 08 배경 · 계약 8절 포트로 세션 패키지 · 12 덧붙임 중계 · 지금 활동 · 기록 수신 · 26 · 27 조회 · 삭제 참가자 · 계약 경로 3 · 스키마 3 |
| `feat(u4a)` 화면 | 04 지금 활동 · 12 점선 · 공백 경고 · 복구 중 · 오프라인 · P2 · P3 · 17 사유 표 · 정리 중 · 08 실패 사유 |
| `feat(u4a)` U4b 맞춤 | 세션 승인 문구 6종 v2 시드 003 · 합성음 자리 6개 · WAITING_CHILD 끝내기 · 상태 사건 사유 시험 · 설계 9절 · J20 ~ J25 |
| `docs(u4a)` 보고 | 이 문서 |

## 2. 만든 것

### Core (core-api)

| 컴포넌트 | 만든 것 |
|---|---|
| ActivityCoordinator | `StartActivityService`가 계약 8절 포트로 바뀜: 아이 = `ChildProfileService.childContext`(없으면 403 CONSENT_MISSING), 동의 = `ConsentPolicyService.check`(U1 `ConsentGate` 삭제, U4a-J3). 사본 행과 08 배경(`core_activity_context`, 네 칸 중 하나라도 있을 때)을 **한 트랜잭션**에 쓴다(멱등 재생은 다시 쓰지 않음). `SessionPackageBuilder`는 포트 값으로 조립: 종료 표현 스냅숏(기본 '무중아 그만할래' 맨 앞 + 아이 추가 표현, AC2.3.7) · 동의 스냅숏 · 승인 문구(CLOSING · WAITING_FILLER 필수 + 자유대화 문구 5종 중 승인 · 음성 있는 것, `ApprovedContentCatalog.sessionContent`). 08 원문은 빌더에 넘길 인자가 없다(AC3.1.7). `SessionViewService`가 Realtime 진행 조회의 gaps · technicalPause · silenceWarning · practice를 계약 모양으로 걸러 중계(`LiveExtras`, 판정 label 등 모르는 필드는 버림). `CurrentSessionService` + `GET /api/sessions/current`({session}, 편차 (U4a-a)) · `/api/home` currentSession. `PracticeStart` 확장점(U5용 인터페이스만) |
| ActivityRecord(신설 `kr.mujoong.core.record`) | `SessionRecordCapturedHandler`(Inbox): 사본 없음 · 보호자/아이 불일치는 저장 안 함, `sha256:` + record 정규화 JSON 해시가 다르면 처리 실패(재시도 → DEAD), 같은 세션 같은 해시는 무동작, 다른 해시는 첫 기록 유지 + APP_ERROR `EventConflict`, DECLINED는 머리만, 새로 넣으면 `ActivityRecorded`(Spring 앱 이벤트, 구독자는 커밋 뒤). `GET /api/records?month=` · `?date=`(Asia/Seoul 경계, FAILED 숨김 · DECLINED linkable false, goalCount · interrupted 편차 (U4a-b)) · `GET /api/records/{id}`(판정값 · Cue · 원음 경로 · termination · 08 원문 없음, 목표는 이름 · 순서 · 상태만) |
| 삭제 참가자 | `ActivityRecordDeletionParticipant`(순서 90 `ORDER_ACTIVITY_RECORD`) → `ActivityDeletionParticipant`(100, 08 배경 + 사본 한 트랜잭션). 진행 세션 CONSENT_WITHDRAWN 끝내기 · 30초 대기는 U2 지휘 그대로(U4a-J24) |
| 마이그레이션 | `V3__u4a_activity_core.sql`: `core_activity_context`(세션당 하나, 칩 CHECK, 네 칸 중 하나 있음 CHECK) · `core_activity_record`(종료 상태 5종 · 해시 형식 · json 배열 CHECK, (guardian_id, listed_at) 색인). V1 · V2 그대로 |
| 참조 시드 | `infra/seed/003-u4a-reference.sql`: 세션 승인 문구 6종 승인 v2(음성 `infra/seed-audio/*-v2.wav`, `tools/seed/gen-session-wav.mjs` 합성음 자리), 앞 DRAFT v1 RETIRED(U4a-J20) |

### 화면 (/parent)

| 화면 | 만든 것 |
|---|---|
| 04 | 지금 활동: 진행 중 "{아이} · 자유대화 n분째"(C.S-04.3.10) · "hh:mm 시작" · 「진행 화면 보기 ›」 → 12, CLOSING "… 종료 처리 중" → 17, 조회 실패 "지금 상태를 불러오지 못했어요" · 마지막 확인 · 「다시 확인」(mockups 9-5, 진행 중 · 없음 라벨 숨김), 진행 중이면 하단 자유대화 · 연습 탭이 그 활동 화면으로(AC1.4.10) |
| 08 | 12에서 돌아온 FAILED 사유를 실패 코드로(DEVICE_NO_ACK · 코드 없음 = U1 문구, PROVIDER_UNAVAILABLE · SCENARIO_GEN_FAILED는 따로) |
| 12 | 말풍선 사이 끊김 · 미확인 구간 점선("hh:mm ~ hh:mm 잠깐 끊김" · "… 확인되지 않은 구간", 진행 중이면 "대화 연결을 다시 잇고 있어요"), 기술 일시 정지 복구 중 줄, 공백 경고 경과 줄 "· n분째 말이 없어요" + 카드(C.S-12.51.3 ~ 6), 앱 오프라인 줄(9-17), 「이야기 끝내기」 WAITING_CHILD에서도 켜짐, ABORTED · DISCONNECT → P2 → 17, DECLINED → P3 → 04, FAILED는 SERVER_RESTART만 17 |
| P2 · P3 | 「확인」 하나짜리 알림 팝업(`NoticeDialog`, 바깥 누름 · Escape로 안 닫힘, 초점 가둠) |
| 17 | AC3.6.9 사유 표(보호자 끝냄 9-19 · 아이가 끝냄 C.S-17.72 · 자동 종료 C.S-17.53 + F1-a · 연결 끊김 C.S-17.71 · 기기 응답 없이 C.S-17.58), "기록을 정리하고 있어요"(C.S-17.16.4), 종료 확인 미확인 줄(AC3.4.3). 마무리 중은 U1 그대로(사유 줄 없음, U4a-J21), 「오늘 기록 보기」는 U6까지 감춤(U4a-J14) |

문구는 `frontend/src/shared/copy/activity.ts`에 문구표 원문 · mockups 제안 · 임시 세 묶음으로 둔다(6절).

## 3. 시험 결과

| 범위 | 결과 |
|---|---|
| backend U4a(`-PincludeTags=u4a`, core-api) | **25건 통과**(U4aActivityApiIT 10 · U4aCoordinatorUnitTest 6 · U4aRecordUnitTest 6 · SeedU4aReferenceSqlIT 3), U4a 범위 줄 커버리지 **95.1%**(389/409, 파일 12), `jacocoTestCoverageVerification -PcoverageScope=u4a` 통과 |
| backend U1 회귀 core-api(`-PincludeTags=u1`) | **611건 통과**(U4a 전 609 + CoreSchemaIT 새 표 열 수 2), `jacocoTestCoverageVerification -PcoverageScope=u1` 통과 — 마지막 커밋 뒤 실행 |
| backend U2(`-PincludeTags=u2`, core-api) | **97건 통과**(3차 리뷰 전 90), U2 범위 줄 **96.6%**(989/1024), U2 커버리지 검증 통과 — 마지막 커밋 뒤 실행 |
| backend U1 회귀 contract-model · realtime-activity · test-support | contract-model U1 + U1 커버리지 검증 통과, realtime-activity · test-support U1 BUILD SUCCESSFUL(백엔드 커밋 뒤 실행, 그 뒤 계약 · realtime 쪽 파일은 바꾸지 않음) |
| frontend U4a(`vitest run tests/u4a --coverage`, scope u4a) | **58건 통과**, 줄 커버리지 **99.26%**(135/136) |
| frontend U1 회귀(scope u1) | 514건 통과, U1 범위 줄 98.17%(하한 80% 유지) |
| frontend U2(scope u2) | 124건 통과, 줄 97.4% |
| `tsc --noEmit` · `eslint .` | 오류 0 |
| tools(`vitest run tests/u4a tests/u1/seed-audio.test.mjs`) | 21건 통과(새 생성기 · U1 음성 시험 함께) |

DB 시험은 Testcontainers, 포트는 모두 무작위다. 시드 003 시험은 공용 DB에 승인 문구를 커밋하면 U1 패키지 시험(문구 둘)과 부딪쳐 따로 띄운 DB에서 한다.
흔들림 한 번: 프런트 U1 + U2를 한 번에 돌렸을 때 `child-03.test.tsx` '…기기 뒤로가기도 아무 일 없다' 1건이 2.57초로 실패했다. 단독 · U2 묶음 두 번
· 마지막 scope u2 실행에서 모두 통과해 부하에 따른 시간 초과로 본다(코드 변경 없음).

### U1 · U2 시험을 고친 곳(단언 · 하한은 낮추지 않음)

| 시험 | 고친 이유 |
|---|---|
| StartActivityPolicyTest · SessionPackageBuilderTest · RealtimeSessionClientTest | 시작 서비스 · 빌더가 계약 8절 포트를 받게 바뀌어 부르는 모양만 바꿈. 빌더의 '문자열 매개변수 1개(정책 버전)'는 정책 버전이 스냅숏 안으로 들어가 '0개'로 좁힘 |
| CoreBusinessContext | U1 업무 문맥이 child · consent · record 패키지와 `GuardianWriteLock`을 함께 띄움(시작 서비스가 포트를 씀) |
| SingleInstanceMarkerTest · TimeLiteralScanTest | SI-11 전제 클래스를 지운 ConsentGate → `PolicyTermsReader`(같은 정책 버전 읽기), 달력 '다음 날'(`day.plusDays(1)`)을 근거와 함께 허용 목록에 |
| CoreSchemaIT · FlywayConfIT · SchemaPrivilegeIT · SchemaVersionCheckTest · CoreStartupSequenceIT | core V3가 생겨 표 수 · 이력 · 최고 버전("3") · 열 수(새 표 둘)를 새 값으로 |
| DbTestSupport | U4a 표 정리 추가 · 따로 띄운 DB용 `coreApp(postgres)` |
| `backend/coverage-scope/u1.txt` | 지운 `activity/ConsentGate.java` 한 줄을 뺌(파일 머리글은 '뒤 유닛은 고치지 않는다'지만 없는 파일은 목록에 둘 수 없어 빌드가 멈춘다). core-api 104 → 103 |
| frontend `live-12.test.tsx` | DECLINED는 17이 아니라 P3 → 04(AC3.5.1), WAITING_CHILD에서 「이야기 끝내기」 켜짐(AC3.3.8 · C.S-12.11.5 — 화면 설계가 앞선다) |
| frontend `done-17.test.tsx` | 'ENDED인데 사유 카드 없이 제목만'의 예시를 SILENCE → 사유 없는 ENDED로(SILENCE는 AC3.6.9 사유 카드가 생김) |

## 4. 계약 편차 (`contracts/deviations.md` "U4a 편차")

| 기호 | 내용 |
|---|---|
| (U4a-a) | `GET /api/sessions/current`는 늘 `{session: SessionSummary \| null}` |
| (U4a-b) | `GET /api/records?date=` 줄에 `goalCount` · `interrupted` |

계약 3절의 세 경로(`/sessions/current` · `/records` · `/records/{sessionId}`)와 응답 스키마 셋(`session-current-response` · `records-response` ·
`record-detail-response`)을 parent-api.yaml · schemas에 더했다(편차 아님). 기존 연산 · 필드 삭제 · 의미 변경 없음.

## 5. 가정 (준하 제안(가정), 상세는 design.md 8절)

| 번호 | 요지 |
|---|---|
| U4a-J1 | 08 배경은 네 칸 중 하나라도 있을 때 세션당 한 행, 상태 열 없음 |
| U4a-J2 | 패키지의 자유대화 문구 5종은 승인 · 음성 있는 것만 |
| U4a-J3 | U1 ConsentGate를 지우고 계약 8절 ConsentGate 포트 하나로 |
| U4a-J4 | `/sessions/current`는 늘 {session} |
| U4a-J5 | 04 'n분째'는 화면 시계 |
| U4a-J6 · J22 | 기록 해시 = `sha256:` + contract-model `JsonCodec.canonicalize` JSON의 SHA-256(U4b-J16과 같은 정의), 불일치는 처리 실패 |
| U4a-J7 | 사본 없음 · 남의 기록은 저장 안 함, 다른 해시는 첫 기록 유지 |
| U4a-J8 | 26 경계 Asia/Seoul, 줄 시각 = startedAt 또는 요청 시각 |
| U4a-J9 | 27 상세 404: 남의 · 없음 · 아직 없음 · FAILED |
| U4a-J10 | 26 줄 goalCount · interrupted |
| U4a-J11 | 27 응답에 08 배경 원문을 넣지 않음(지시 우선, 보일지는 U6) |
| U4a-J12 | 12에서 ABORTED · DISCONNECT → P2, DECLINED → P3 |
| U4a-J13 | 17 CHILD_STOP 둘째 줄은 기본 표현 |
| U4a-J14 | 17 종료면 '정리 중' 줄, 「오늘 기록 보기」는 U6까지 감춤 |
| U4a-J15 | 17 미확인 줄 임시 문구 |
| U4a-J16 | FAILED는 SERVER_RESTART만 17, 그 밖은 08 |
| U4a-J17 | 12 오프라인 줄 = 마지막 폴링이 응답 없음 |
| U4a-J18 | ActivityRecorded = Spring 앱 이벤트(커밋 뒤) |
| U4a-J19 | PracticeStart 확장점은 인터페이스만 |
| U4a-J20 | 세션 승인 문구 6종 v2 시드 003 + 합성음 자리 |
| U4a-J21 | 17 마무리 중에는 사유 줄 없음(화면 설계 C.S-17.73 우선) |
| U4a-J23 | 기록 참가자 순서 90, U5a ORDER_PRACTICE는 다른 값 |
| U4a-J24 | 끝내기 전달 · 삭제 CONSENT_WITHDRAWN은 U1 · U2 경로 그대로 |
| U4a-J25 | `/sessions/current`는 Core 사본만 |

### 임시 · 제안 문구 (`frontend/src/shared/copy/activity.ts`)

- mockups 9절 · S-04 제안(`ACTIVITY_PROPOSED_COPY`): 04 '종료 처리 중' · '지금 상태를 불러오지 못했어요' · '마지막 확인' · 「다시 확인」, 12 '대화 연결을 다시 잇고 있어요' · '실시간 상태를 불러오지 못하고 있어요' · '확인되지 않은 구간', 08 SCENARIO_GEN_FAILED 사유.
- 임시(`ACTIVITY_TEMP_COPY`): 17 '기기 종료 확인 · 확인 안 됨'(U4a-J15), 이름을 모를 때 '아이', 줄 조각(분 · 째 · 구분자).
- 시드 003 문구(세션 승인 문구 6종)는 사람 승인 전 임시 글이다(U4a-J20).

## 6. U4b 설계 8절과의 대조 (조정자 지시)

`git fetch`는 이 세션에서 승인이 필요해 실행하지 못했고, 이미 받아 둔 `origin/claude/friendly-shannon-i89iyx`의 `docs/units/u4b/design.md`를 읽었다.
상세 표는 design.md 9절이다.

| # | 결과 |
|---|---|
| 1 승인 문구 | **어긋남 → 고침.** 시작 서비스는 다섯 코드를 실을 준비가 돼 있었지만 시드가 DRAFT라 하나도 실리지 않았다. 시드 003(승인 v2 + 합성음)로 실제 시작 패키지에 CLOSING · WAITING_FILLER · INTRO_FREE_TALK · RE_PROMPT · DECLINE_ALTERNATIVE · SILENCE_WARNING · STOP_CONFIRM이 실림을 시험했다. 시작 안내는 AC6.3.1 순서 + C.S-12.11.4, NFR2 시드 사전 0건. INTRO_PRACTICE는 시드만(연습 패키지는 U5) |
| 2 끝내기 | Core 전달은 맞음(WAITING_CHILD도 Realtime으로 넘기고 202 {CLOSING}을 그대로). **12 화면은 ACTIVE만 켜져 있어 고침**(WAITING_CHILD도 켬). CONSENT_WITHDRAWN 응답(202 {CLOSING} · 202 {} · 409)과 관계없이 U2 지휘가 30초 뒤 진행 |
| 3 상태 사건 | 맞음. CLOSING 사유 · UNCONFIRMED · DECLINED가 사본 · 지금 활동에 반영됨을 시험. 17 마무리 중 사유 줄은 화면 설계대로 내지 않음(U4a-J21 — U4b 3.1 끝 문장과 다름, 화면 설계가 앞선다) |
| 4 기록 이벤트 | 맞음(멱등 · 해시 · DECLINED). 해시 정의 일치를 J22로 적음 — Realtime도 contract-model `JsonCodec.canonicalize`를 써야 한다 |
| 5 진행 조회 | 맞음(LiveExtras 중계, 12 점선 · 복구 중 · 공백 경고) |
| 6 삭제 | 맞음(진행 사본 CONSENT_WITHDRAWN 1회 → 30초 → 참가자 → DeletionRequested) |

## 7. 남은 일과 막힌 것

| 항목 | 상태 · 넘길 곳 |
|---|---|
| 시드 `infra/seed/003-u4a-reference.sql`을 검증 스크립트 · RUNBOOK 순서(001 → 002 → 003)에 넣기 | 조정자(`scripts/verify.sh` · RUNBOOK을 고치거나 돌리지 않았다). 음성 6개는 `infra/seed-audio/`에 있어 RUNBOOK의 `seed-audio/*.wav` 설치에 함께 들어간다 |
| dev 프로필(시험 로그인 스택)의 세션 문구 | `DevSeedRunner`는 CLOSING · WAITING_FILLER만 넣는다. dev에서도 참여 확인 소리가 나려면 시드 003을 적용하거나 dev 시드를 넓혀야 한다(조정자 결정) |
| 세션 문구 6종 실제 녹음 · 사람 승인 | D 담당 · 팀장. 승인되면 v3 행을 더하고 v2를 RETIRED(U2-J17). 지금 음성은 합성음 자리 |
| Realtime 쪽(상태 기계 · 시간 규칙 · 스냅숏 발행 · DeletionCompleted) | U4b. 그 전까지 계정 삭제 요청은 REQUESTED에 머문다(U2와 같음) |
| 연습 시작 · 연습 패키지(INTRO_PRACTICE 싣기) · 12 연습 머리 | U5(`PracticeStart` 확장점) |
| 17 정리 끝 · 정리 실패 · 「오늘 기록 보기」, 26 · 27 화면, 27 08 배경 표시 | U6(U4a-J11 · J14) |
| 알림 진입 재조회 | U7이 `fetchCurrentSession`(`/api/sessions/current`)을 쓴다 |
| U5a ORDER_PRACTICE | 90 · 100 · 200 · 250 · 300 · 900과 다른 값(U4a-J23) |
| 병합 충돌 위험 | U3(realtime V2): FlywayConfIT의 realtime 이력 · 전체 표 수(지금 23), `timing.md` · `deviations.md` 끝. U5a(core V4): SchemaVersionCheckTest · CoreSchemaIT · FlywayConfIT · SchemaPrivilegeIT의 최고 버전 · 표 수, `DeletionParticipant` 상수, `DbTestSupport.cleanUp` |
| 3차 리뷰 재현 시험의 RED | 고치기 전 코드로 새 시험을 돌려 보지 못했다(파일을 잠시 되돌리는 명령에 권한이 없었다). U2 report 3차 표에 적었다 |
| `.playwright-mcp/` | 3차 리뷰 5번 브라우저 확인 때 생긴 스냅숏 폴더. 지우는 명령이 막혀 남겨 두었고 커밋하지 않았다(지워도 된다) |
| 같은 작업 트리의 다른 세션 | 작업 중 다른 세션(mujoong-w4-16)이 같은 트리에서 프런트(U4a 화면 · tests/u4a/parent · coverage-scope/u4a.json · U1 프런트 시험 두 파일)를 마저 만들고 끝났다. 그 결과를 이 세션이 다시 돌려 확인하고(3절) 커밋했다 |
