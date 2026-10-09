# Domain Design — 질문 파일

> 무중(Mujoong) · 깊이 Standard · 2026-10-09 (클라우드 세션)
> 입력: `../requirements-analysis/requirements.md`(승인), `../user-stories/stories.md`(승인), `../refined-mockups/`(승인), 50_ADR_2S · 60_ERD_v0.1 · 20_개발명세_1008 · 80_점검표_1008, 초안 `docs/aidlc_drafts/inception/domain-design/`.
>
> **누가 답했나.** 이 파일의 `[Answer]:`는 팀장(준하)의 대리가 채웠다. 사람 지시(2026-10-09 클라우드 세션) 원문: "사람에게 묻지 말고 문서의 중재 규칙으로 판단하고, 게이트마다 커밋·푸시해." · "handoff 문서의 중재 규칙대로 진행해." 중재 규칙은 `docs/cloud_handoff.md` 2절(이하 "규칙 n")이다. 원래 방식은 Edit File(사람이 `[Answer]:`를 채우고 done)이며 이번 단계만 대리가 그 자리를 채웠다. **Mode:** 대리 판단.
>
> **뒤집는 법.** 대리가 스스로 판단한 것은 모두 **준하 제안(가정) Jn**으로 번호를 달아 `components.md` · `decisions.md`의 가정 표에 옮겼다. 팀이 바꾸려면 Jn을 지목해 Request Changes 하면 된다.

## 1. 문서와 인수인계 규칙으로 이미 정해진 것 (확인용)

| 번호 | 정해진 것 | 근거 |
|---|---|---|
| K1 | 실행 구조는 바꾸지 않는다: Next.js 한 앱 두 접점(/parent · /device), Core API Spring + Realtime Activity Spring, PostgreSQL 1대(core · realtime 스키마, 앱별 쓰기 권한), Neo4j(화용 지식), GPT-Live 중계 B안 + 출력 게이트, 내부 HTTP + Outbox/Inbox, EC2 1대 Docker Compose + Nginx | requirements.md 제약 · 규칙 7 |
| K2 | 계정 = 아이 = 기기 1:1. core_child.guardian_id는 UNIQUE, 기기도 계정당 하나 | D-24 · 규칙 2 · FR1.4 · FR2.1 |
| K3 | 기기 등록은 서버 발급 8자리 코드(D0) + 보호자 05a 입력. 운영자 시드 · 페어링 · 해제 기능 없음. ERD에 Device 테이블이 새로 필요 | 05 · 05a · D0 · 점검표 1 · 규칙 2 |
| K4 | 기기 상태 5단계와 시간값은 NFR6 그대로(참여 대기 20+20초, 시작 기기 응답 없음 20초, 공백 4분 경고 · 6분 SILENCE, 끊김 40초, 마무리 확인 30초, 불안정 = 지연 1초 · 5분 안 끊김 2회) | NFR6 · FR2.2 · 규칙 2 |
| K5 | 세션 상태 11개 · 종료 사유 8개는 화면 이름 그대로. ADR-005 · session-state의 PAUSED · ENDING 등은 쓰지 않는다 | FR7.1 · 점검표 3 · 4 · 8 |
| K6 | 원음은 항상 저장(필수 동의 4항목 중 원음 보관). ADR의 '원음 미저장' 문장은 화면 기준으로 고친다. 저장 위치는 3.4 | FR7.3 · 점검표 2 |
| K7 | 연습 = 2안 + 제브 판정. 목표 여러 개(12 머리 n/N), 세션–목표 연결 realtime_session_goal. 12 말풍선은 승인된 AI 말 + 아이 전사만 | 규칙 4 · FR4.2 · FR7.8 |
| K8 | 리포트는 AI 없이 원문 PDF, "이 문서는 진단 결과가 아닙니다" 문장 포함, 생활 팁 없음, 판정값은 보호자 화면 · PDF 어디에도 없다 | 규칙 5 · FR6.4 · FR6.5 · FR4.3 |
| K9 | 웹 푸시 3종은 Should, 계정 삭제는 접수형(즉시 차단 → 백그라운드 영구 삭제), 생성형 AI 고지는 02-1 약관 본문 | 규칙 6 · FR7.10 · FR1.6 · FR1.3 |
| K10 | 보호자 앱의 12 실시간 말풍선은 1~2초 폴링(SSE 아님), 세션 조회 응답에 전사가 들어간다 | FR3.2 · 점검표 9 · A2 |
| K11 | 이 단계는 배포 단위(유닛) · 기술 세부 · NFR 패턴을 정하지 않는다. 유닛은 2.7, 계약 물리값은 2.8, 저장 위치 · HTTPS는 3.4 | 스테이지 정의 · OQ3 · OQ5 |

## 2. 정할 것

### Q1. 결과 · 연습 목표 · 상황의 소유는 어디로 하나 (OQ1, 점검표 16)

ERD v0.1은 목표 후보 · 담은 목표 · 상황 · 생활 팁 · 결과를 전부 core 스키마에 두고, ADR_2S(result-pipeline · protocol)는 목표 정의 · 버전 · 선택을 Realtime practice 소유로 둔다. 두 앱이 같은 사실을 나눠 가지면 쓰기 주인이 둘이 된다.

A. Core로 통일 — core_goal_recommendation · core_practice_goal_selection · core_scenario · core_activity_context · core_support_item · 결과 문서 · 리포트 · 사후 텍스트 AI 호출은 Core. Realtime은 진행 중 세션 · 기기 WSS · GPT-Live 중계 · 제브 판정 프로토콜 · 세션–목표 스냅숏만 갖고, 세션이 끝나면 Outbox → Inbox로 결과를 Core에 넘긴다
B. Realtime practice가 목표 정의 · 버전 · 선택 · 상황을 갖고 Core는 산출물 참조만 (ADR_2S 그대로)
C. 목표는 Core, 상황 · 노출은 Realtime으로 나눈다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 규칙 3("결과·목표 소유는 Core로 통일(준하 제안)")을 그대로 따랐다. ERD v0.1(Core)과 ADR-002 Backend 표("결과 · 리포트와 사후 텍스트 AI 호출은 Core")가 같은 쪽이고, 보호자 화면(18 · 27 · 28 · 30)이 모두 Core API를 부르므로 화면 기준 원칙과도 맞는다. 준하 제안이므로 **J1**로 기록.

### Q2. 목표 정의(화용 행동) 카탈로그는 어디에 두고 Realtime은 무엇을 받나

목표 후보는 4요소(행동 정의 · 기회 조건 · 상황 생성 조건 · 판정 기준)를 갖춘 것만 쓴다(FR4.4 · FR7.4). ERD에는 behavior_key 문자열만 있고 정의 테이블이 없다. 판정 기준이 세션 중에 바뀌면 같은 세션 안 판정이 흔들린다.

A. Core 참조 테이블 core_behavior_definition(behavior_key, version, 4요소)을 두고, Realtime은 세션 시작 때 behavior_key + version과 판정에 필요한 정의를 realtime_session_goal에 스냅숏한다. Neo4j는 화용 지식(행동 · 상황 · 관계 · 단서 · 지원) 조회용이고 정의의 원장은 아니다
B. Neo4j 그래프 노드를 원장으로 쓰고 버전 없이 조회한다
C. Realtime이 정의 · 버전을 소유한다 (ADR_2S)
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 규칙 3("목표 정의(behavior) 카탈로그는 Core 참조 테이블에 version 열, Realtime은 세션 시작 때 key+version으로 스냅숏")과 FR7.2(세션 중 버전 고정)의 원칙. 판정 기준 본문 · 프롬프트는 AI 입출력 계약(WBS 1.7.3)에서 채운다(OQ4). **J2**.

### Q3. 결과 생성 작업(ResultJob) 모델을 어떻게 통일하나

ERD는 core_result_job(+attempt), 개발 명세는 REQUESTED → RUNNING → READY/FAILED, ADR_2S result-pipeline은 Realtime 쪽 상태를 따로 둔다.

A. Core 하나: REQUESTED → RUNNING → DONE / FAILED, 시도는 core_result_job_attempt(최대 3회), 서버 재시작 때 RUNNING이던 작업은 FAILED로 닫고 보호자 「다시 시도」로만 다시 시작한다. 작업 종류는 SESSION_RESULT(27 생활 팁 · 목표 후보)와 REPORT(28 PDF) 둘. 17 표시는 세션 상태 × 결과 작업 상태 표 그대로
B. Realtime과 Core가 각자 작업 상태를 둔다
C. 리포트는 작업 없이 요청 안에서 동기로 만든다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 규칙 3("ResultJob은 Core 하나(REQUESTED→RUNNING→DONE/FAILED, 서버 재시작 시 FAILED)") · NFR8(60초 · 3회 재시도) · FR7.7(다시 시도해도 결과 한 번만). 개발 명세의 READY 이름은 DONE으로 맞춘다. 작업 종류에 REPORT를 넣은 것은 개발 명세 API 표("생성은 core_result_job(job_type=REPORT)")를 따른 대리 판단이다. **J3**.

### Q4. 기기 등록과 기기 연결 상태는 누가 갖나 (OQ11, 점검표 1 · 7)

05는 서로 독립인 두 줄(기기 등록 · 기기 네트워크 현황)과 둘을 합친 판정 카드를 보여 준다(D-18). 개발 명세 API 표는 "Core(등록) + Realtime(상태)"로 적었지만 bootstrap 경로를 Realtime에 두어 한 테이블에 쓰는 앱이 둘이 될 수 있다.

A. 나눈다: Core DeviceRegistry가 core_device(코드 발급 · 기기 비밀 · 등록 · 기기 토큰 · 해제)를 소유하고 bootstrap · register API도 Core가 받는다. Realtime DeviceGateway는 WSS · heartbeat · 연결 상태(안정 · 불안정 · 끊김 · 응답 없음 · 꺼짐) 판정과 realtime_device_connection을 소유하고, WSS 접속 때 토큰을 Core 내부 HTTP로 확인한다. 연결 상태는 Realtime → Core로 보고되어 Core가 05 두 줄과 판정 카드를 합산한다
B. Realtime이 기기의 등록 · 토큰 · 상태를 모두 소유하고 Core는 register 요청을 Realtime에 넘기기만 한다
C. Core가 heartbeat까지 받는다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 05의 두 줄이 서로 독립이라는 화면 규칙(D-18)을 소유 경계로 옮겼다: 등록 줄 = Core, 네트워크 줄 = Realtime. 계정 1:1 제약과 계정 삭제 때 해제는 계정 쪽(Core)에서 지키는 것이 자연스럽고, WSS는 규칙 3대로 Realtime이 갖는다. 개발 명세 API 표의 "bootstrap = Realtime"은 경로 제안이었으므로 Core로 옮긴다. 서버 상태 7개 ↔ 보호자 5단계 대응은 components.md에 표로 둔다(OQ11 답). **J4**.

### Q5. 진행 중 세션(12 · 04 지금 활동)은 보호자 앱이 어디서 읽나

12는 1~2초 폴링으로 상태와 전사를 받는다(K10). 보호자 인증(구글 세션)은 Core에만 있다.

A. Core ActivityCoordinator가 보호자 · 세션 소유를 확인한 뒤 Realtime 내부 조회(상태 · 전사 since 순번 · 끊김 구간 · 연습 n/N)로 넘겨 받는다(동기 내부 HTTP). 04 · 시작 검사용 세션 상태는 Core가 SessionStateChanged 이벤트로 사본(core_activity)을 유지한다
B. Core가 TranscriptAppended 이벤트마다 전사를 복제해 두고 자기 사본만 응답한다
C. Realtime이 보호자 API를 직접 열고 보호자 세션을 함께 확인한다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. NFR5(말풍선 1.5초)를 Outbox 지연 없이 맞추고, 보호자 인증을 Core 한 곳에 둔다(ADR-008 방향). Realtime이 멈추면 12는 "기기 상태 확인 불가 + 마지막 확인 시각"으로 보인다(FR3.2 · B6). **J5**.

### Q6. 끝난 활동의 기록(26 · 27 대화 기록 · 28 · 30 PDF)은 어디서 읽나

ERD는 전사 · 연습 기록을 realtime 스키마에 두고, 화면↔테이블 표는 26 · 27 · 30이 realtime_transcript를 읽는다고 적었다. 규칙 3은 "세션이 끝나면 Outbox→Inbox로 결과를 Core에 넘긴다"이다.

A. 세션이 끝나면 Realtime이 결과 입력 스냅숏(realtime_result_input_snapshot: 아이 전사 · 승인된 AI 말 · 불확실 표시 · 끊김/미확인 구간 · 연습 목표와 단계 기록 · 판정값 · 원음 메타)을 만들어 Outbox로 넘기고, Core ActivityRecord가 그것을 활동 기록(core_activity_record)으로 보관한다. 26 · 27 · 28 · 30 · PDF와 ResultJob은 Core 데이터만 읽는다. Realtime 원본은 계정 삭제 때까지 남지만 보호자 조회 경로에는 쓰이지 않는다
B. Core가 조회 때마다 Realtime 내부 API로 전사를 읽는다
C. Core가 realtime 스키마를 읽기 전용 뷰로 직접 읽는다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 규칙 3을 그대로 따르면서 ERD의 realtime_result_input_snapshot(이미 "Core 전달용 결과 입력 Snapshot")을 인계 수단으로 쓴다. 미참여(DECLINED)는 머리 정보(종료 사실 · 시각)만 넘긴다(FR3.5). 판정값은 스냅숏과 Core 기록에 저장되지만 조회 응답 · PDF 직렬화에서 빠진다(FR4.3). **J6**.

### Q7. 연습 상황 생성 · 판정 · Cue는 누가 하나

상황은 생성 뒤 자동 확인하고 실패하면 1회 재생성, 두 번 실패하면 연습을 시작하지 않는다(FR7.5). 다음 목표의 상황은 앞 목표가 끝날 때 만든다(B13). 판정은 5초 예산 + 재시도 1회(FR7.6), Cue는 GPT-Live 실시간 5초 예산(FR5.5).

A. 상황 생성 · 확인 · 노출 이력 확인은 Core PracticeGoal(core_scenario, 텍스트 AI). 첫 목표 상황은 시작 요청 때 Core가 만들어 세션 패키지에 넣고, 다음 목표는 Realtime PracticeProtocol이 Core 내부 HTTP로 요청한다. 단계 진행 · 도움 동의 · Cue(GPT-Live) · 제브 판정(판정 LLM)은 Realtime PracticeProtocol
B. 상황 생성도 Realtime이 한다
C. 시작 때 모든 목표의 상황을 미리 만든다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 규칙 3(core_scenario는 Core, 제브 판정 프로토콜은 Realtime)과 B13(다음 목표는 앞 목표가 끝날 때 생성). C는 목표 N개 × 생성 시간이 시작 지연(20초 기기 응답 규칙)과 겹쳐 버렸다. 노출 이력(FR7.5 중복 방지)은 Core가 결과 스냅숏으로 받은 노출 기록으로 판단한다. **J7**.

### Q8. 리포트 PDF는 어디서 어떻게 만드나 (OQ6)

내용은 아이 정보 + 날짜별 대화 원문뿐이고 AI가 없다(K8). 30에서 저장 · 공유(Web Share API)하려면 파일이 있어야 한다.

A. Core Report가 서버에서 HTML 템플릿 → PDF로 렌더링한다(Java 라이브러리, 한글 글꼴 파일을 이미지에 넣어 임베드). 파일은 30에서 저장 · 공유할 때 내려 주고 서버에는 리포트 메타만 남긴다(A4)
B. Next.js 서버에서 헤드리스 Chromium으로 인쇄한다
C. 보호자 브라우저 인쇄 기능을 쓴다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 컨테이너가 하나 덜 들고(EC2 1대), 서버가 저장된 전사로 만든다는 FR6.4 문장과 맞다. C는 Android Chrome에서 파일 공유가 안 된다. 라이브러리 이름(예: OpenHTMLtoPDF)과 글꼴은 3.3 NFR Design · 3.4에서 확정. **J8**.

### Q9. 보호자 화면의 다른 서버 기능(알림 · 계정 삭제 · 텍스트 AI)을 어떻게 나누나

A. Core 안에 따로 둔다: Notification(웹 푸시 토큰 · 발송 요청 · 중복 방지, Should), AccountDeletion(접수 · 즉시 차단 · 모듈별 삭제 지휘 · Realtime 삭제 완료 수신), TextAiGateway(사후 텍스트 AI 호출의 한 출입구: 최소 정보 전송 · 금지 표현 검사 · 모델/프롬프트 버전 기록). 분석 이벤트 테이블(core_analytics_event)은 요구사항이 없어 이번엔 만들지 않는다
B. 각 기능 모듈 안에 흩어 둔다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 계정 삭제는 거의 모든 모듈의 데이터를 지우므로 지휘 역할을 한 곳에 모으고(FR1.6 · NFR12), 텍스트 AI 출입구를 하나로 두면 NFR1 · NFR3 검사를 한 번만 구현한다. 알림은 Should라 따로 떼어야 일정에서 미룰 수 있다(FR7.10). core_analytics_event 제외는 NFR2(완료율로 효용 판단 금지)와도 맞다. **J9**.

## 3. 답 분석

- **모호함:** 없음. 모든 답이 규칙 3 · 화면 규칙 · 요구사항 문장 하나 이상에 기대며, 대리 판단 부분은 J1~J9로 표시했다.
- **모순 점검:** Q5(진행 중 = Realtime 조회)와 Q6(끝난 뒤 = Core 기록)은 시점으로 나뉘어 겹치지 않는다. Q4의 연결 상태 사본(core_device의 마지막 보고값)은 표시용이며 판정 원장은 Realtime이다. Q1(Core 통일)과 Q7(판정 · Cue는 Realtime)은 규칙 3이 Realtime에 남긴 "제브 판정 프로토콜"과 일치한다. Q3의 REPORT 작업과 Q8의 서버 렌더링은 같은 작업 실행기를 쓴다.
- **후속 질문:** 없음. 계약 물리값(경로 · 필드 · 시간 계약 위치)은 Contract Design(2.8), 판정 기준 본문은 AI 입출력 계약(OQ4)으로 넘긴다.
