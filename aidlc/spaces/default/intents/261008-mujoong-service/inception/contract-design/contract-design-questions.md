# Contract Design — 질문 파일

> 무중(Mujoong) · 깊이 Standard · 2026-10-09 (클라우드 세션)
> 입력: `../units-generation/unit-of-work.md` · `../units-generation/unit-of-work-dependency.md`(유닛 7개 · DAG · 통합 지점, 승인), `../domain-design/components.md`(J1~J14), `../requirements-analysis/requirements.md`(OQ3 · OQ4 · NFR5 · NFR6 · NFR9), `../user-stories/stories.md`(B4~B6 · B10 · OQ13 · OQ14), 20_개발명세_1008(API · 이벤트 · 상태 기계), 50_ADR_2S(protocol · ADR-004 · ADR-008 · ADR-010), 초안 `docs/aidlc_drafts/inception/contract-design/contract-summary.md`.
>
> **누가 답했나.** 사람 지시(2026-10-09, 2.7 승인 메시지) 원문: "이어서 2.8 Contract Design과 2.9 Delivery Planning을 docs/cloud_handoff.md 2절 규칙대로 네가 판단해 승인하고, 게이트마다 네 브랜치에 커밋·푸시해." 그래서 팀장(준하)의 대리가 2절 규칙으로 채웠다. 대리 판단은 **준하 제안(가정) Jn**(J20부터)으로 `contract-summary.md` 가정 표에 옮겼다. **Mode:** 대리 판단.

## 1. 이미 정해진 것 (확인용)

| 번호 | 정해진 것 | 근거 |
|---|---|---|
| K1 | 앱 사이 동기 접점은 셋(시작 · 끝내기 · 진행 조회 / 기기 토큰 확인 · 상태 보고 / 다음 목표 상황), 영속 이벤트는 6종(SessionStateChanged · SessionRecordCaptured · DeviceStatusChanged · DeletionCompleted · DeviceReleased · DeletionRequested) | ADR-015 · ADR-019 · J10 |
| K2 | 보호자 앱은 2초 폴링(SSE 아님), 세션 조회 응답에 아이 전사와 승인된 AI 말만, 판정값 · 구조화 결과 · 보호자 입력 원문은 없음 | FR3.2 · A2 · A10 · FR4.3 |
| K3 | 상태 이름 11개 · 종료 사유 8개 · 시간값은 NFR6과 stories B5 · B9~B11 · B15 그대로 | 규칙 2 · FR7.1 · NFR6 |
| K4 | 경로 이름은 개발 명세 1008의 API 표를 따르되 화면 기준 변경(폴링 · /api/child 단수 · bootstrap은 Core · 리포트 원문)을 반영한다. 개발 명세는 ADR_2S보다 앞선다 | requirements 제약 · 점검표 9 · J4 |
| K5 | 외부(시스템 밖) API는 보호자 웹 · 아이 기기 웹이 부르는 것뿐이다. 파트너 · 전문가 API는 없다 | FR6.5 · G22 |

## 2. 정할 것

### Q1. 계약 묶음과 형식

A. 다섯 묶음: (1) 보호자 REST(OpenAPI), (2) 아이 기기 REST + 기기 WSS(OpenAPI + 메시지 표 · AsyncAPI), (3) Core ↔ Realtime 내부 HTTP(OpenAPI), (4) Outbox → Inbox 이벤트 6종(AsyncAPI), (5) 앱 안 유닛 사이 포트(공유 스키마 · 인터페이스 표). 여기에 한 곳에 모은 시간 계약(WBS 1.7.6)과 AI 입출력 계약의 뼈대(WBS 1.7.3: 단계 enum · 판정 출력 · 상황 생성 출력)를 붙인다
B. 보호자 REST만 쓰고 나머지는 구현하며 맞춘다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 점검표 3절이 "어디에도 없다"고 한 REST 계약 · 기기 WSS 물리값 · 내부 이벤트 계약을 모두 이 단계가 채워야 2.9 이후 다섯 명이 병행할 수 있다. 판정 기준 본문 · 프롬프트는 D 담당 문서로 남기고 형식만 고정한다. **J20**.

### Q2. 보호자 인증 · 세션 쿠키 · CSRF

A. 개발 명세대로 `POST /api/auth/google {code}`(구글 인가 코드 교환은 Core가 서버에서) → Spring Session JDBC 세션 쿠키(HttpOnly · Secure · SameSite=Lax, 비활성 30일 갱신형 = A1). 상태를 바꾸는 요청은 CSRF 토큰 헤더(`X-CSRF-TOKEN`)를 요구한다. 만료 · 미인증은 401 → 01. 다른 보호자 자원은 404로 숨긴다
B. Spring Security 기본 리다이렉트 로그인(`/oauth2/authorization/google`)
C. JWT 액세스 · 리프레시 토큰
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 개발 명세(S-01 API)가 ADR보다 앞서고, 01 화면이 앱 안 단추 하나로 끝나므로 코드 교환형이 맞다. 세션 저장소는 ADR-008(Spring Session JDBC) 그대로. 404로 숨기는 것은 NFR9("404/403")의 보수적 선택. **J21**.

### Q3. 아이 기기 등록 코드와 기기 인증 (OQ13 · B4)

A. 코드: 8자리, 혼동 글자(0 · 1 · I · L · O)를 뺀 31자(2~9 · A~Z 중 I · L · O 제외), 대문자. 유효 24시간, 지나면 기기 웹이 조용히 새 코드를 받는다. 기기 웹은 2초 간격으로 등록 상태를 묻고 등록되면 기기 토큰을 **한 번만** 받는다. 기기 토큰은 만료 없음(B4), 서버는 해시만 저장. WSS는 `/ws/device`에 접속한 뒤 첫 메시지 HELLO로 토큰을 보낸다(URL 쿼리에 토큰을 두지 않아 프록시 로그에 남지 않게). 같은 토큰의 새 연결이 오면 옛 연결을 닫고 새 연결로 바꾼다(4001 REPLACED)
B. 토큰을 `?token=` 쿼리로 보내고 같은 토큰 두 번째 연결은 거부한다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 05a 그림이 "7F3K"처럼 영숫자이고 자동 대문자라서 혼동 글자를 빼야 입력 실패가 준다. 토큰 쿼리는 Nginx 접근 로그에 남아 NFR9(비밀 · 원문 로그 금지)와 어긋난다. 기기 브라우저가 새로고침하면 옛 연결이 40초 끊김 판정 전까지 살아 있으므로 거부하면 기기가 스스로 잠긴다 — 바꿔 끼우는 쪽이 안전하다. **J22**. (토큰을 어디에 두고 어떻게 보내는지는 4절을 쓰다 모순이 드러나 후속 질문 Q10에서 고쳤다 — HELLO에 토큰을 싣지 않는다.)

### Q4. 12 진행 조회의 since 계약 (OQ13)

A. `GET /api/sessions/{id}?sinceSeq=n` → 세션 상태 · 상태 버전 · 서버 시각 · 시작 시각 · 연습 머리(n/N · 목표 이름 · 단계 표시) · 공백 경고 · 끊김 구간 · `seq > n`인 말풍선 항목(아이 전사 · 승인된 AI 말, 각 seq · 화자 · 글 · 확정 여부 · 시각) · `nextSeq`. 확정 전 조각은 같은 seq로 다시 오며 확정되면 `final: true`. 폴링 간격 2초(A2)
B. 매번 전체 전사를 돌려준다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 개발 명세 SSE 이벤트(transcript.delta · done · state · gap · warning.silence · practice.goal · ended)를 응답 필드로 옮긴 것이다(점검표 9의 "폴링으로 가면 이벤트 이름은 응답 필드 이름으로"). **J23**.

### Q5. 오류 형식과 거절 사유 코드

A. 모든 REST 오류는 `{code, message, traceId}`. 시작 거절 5종은 stories AC3.1.6 · OQ12와 mockups 9-12의 이름 그대로(개발 명세 API 표의 409 SESSION_EXISTS · 423 DEVICE_OFFLINE은 상위 문서 이름으로 바꾼다): SESSION_IN_PROGRESS(409) · DEVICE_NOT_STABLE(423) · CONSENT_MISSING(403) · DEVICE_NO_ACK(세션 FAILED 사유로 조회 응답에) · SCENARIO_GEN_FAILED(422 또는 세션 FAILED 사유). 그 밖에 UNAUTHENTICATED(401) · NOT_FOUND(404) · VALIDATION_FAILED(400) · ACCOUNT_DELETION_IN_PROGRESS(403) · REALTIME_UNAVAILABLE(503). 화면 문구는 코드로 고르고 서버 문구를 그대로 보이지 않는다
B. HTTP 상태만 쓴다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 거절 사유마다 08 · 18 · 05 문구가 다르다(mockups 9-12). 이름이 두 벌이면 규칙 1(stories · mockups가 개발 명세보다 앞)에 따른다. **J24**.

### Q6. 버전 · 바뀜 규칙

A. 경로에 버전을 넣지 않는다(/api/...). 11/5 제출까지는 더하기만 허용(새 필드 · 새 값은 받는 쪽이 무시). 필드 삭제 · 의미 변경은 제공 유닛과 소비 유닛 담당이 함께 계약 파일을 고치고 같은 날 양쪽을 머지한다. 이벤트는 봉투에 `schemaVersion`(정수)을 둔다
B. /api/v1 접두어로 버전을 나눈다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 7일 일정 · 한 저장소 · 트렁크 기반(org.md)이라 경로 버전은 짐만 된다. **J25**.

### Q7. 앱 사이 호출의 시간 제한 · 재시도 · 내부 인증

A. 내부 HTTP는 Docker 내부망에서만 열고 Nginx가 `/internal/**`을 밖에서 막는다. 앱 사이 요청은 공유 비밀 헤더(`X-Internal-Token`, 환경 변수)로 확인하되 사용자 권한으로 쓰지 않는다(ADR-009 · ADR-010). 시간 제한: 시작 명령 3초, 끝내기 2초, 진행 조회 1초, 토큰 확인 1초, 상태 보고 1초, 다음 목표 상황 15초(생성 + 확인 + 재생성 1회 포함). 시작 · 끝내기는 같은 멱등 키로 1회만 재시도. 이벤트 전달은 같은 eventId로 1 · 2 · 4 · 8 · 16 · 30초(최대 30초 간격) 재시도, 20회 넘으면 FAILED로 표시하고 서버 로그 경고. 수신 쪽은 Inbox 커밋 뒤에만 200
B. 상호 TLS
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. EC2 1대 · Compose 내부망(ADR-006)에서 상호 TLS는 비용 대비 이득이 적다. 다음 목표 상황 15초는 LLM 생성 · 확인 두 번을 담는 상한이며 그동안 아이는 승인된 대기 문구를 듣는다(OQ14, 3.1에서 문구 확정). **J26**.

### Q8. 음성 형식과 기기 WSS 메시지

A. 기기 ↔ Realtime 오디오는 PCM16 · 모노 · 24kHz 리틀엔디언, 바이너리 프레임(앞 1바이트 종류 + 4바이트 순번 + 본문), 업로드 20ms 단위. 제어 메시지는 JSON 텍스트 프레임(봉투: type · opId · sessionId · seq · sentAt). heartbeat는 5초마다 기기가 보내고 서버가 같은 seq로 답해 왕복 지연을 잰다. 마지막 기기 메시지 뒤 10초면 끊김 표시 시작, 40초면 끊김(B10)
B. Opus 압축 · base64 JSON
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 형식 값은 GPT-Live 실시간 음성 입출력 형식에 맞춰 변환 없이 중계하려는 것이다(ADR-004 B안 · 중계 증가분 0.5초 NFR5). 문서에서 값이 정해진 곳과 비어 있던 곳은 `contract-summary.md` 4절 표에 출처와 함께 적었다. **J27**.

### Q9. 연습 단계 enum과 판정 출력 형식 (OQ14 · OQ4의 형식 부분)

A. 연습 단계 6개: SCENARIO_INTRO(상황 · 역할 안내) · FIRST_RESPONSE(첫 반응, Cue 금지) · HELP_OFFER(도움 동의 질문) · CUE(도움) · AFTER_CUE_RESPONSE(도움 후 반응) · NEW_SCENARIO_RESPONSE(새 상황 반응, Cue 금지). 반응 종류 FIRST · AFTER_CUE · NEW_SCENARIO, 판정 라벨 functional · partial · none · uncertain + 무반응 NOT_PERFORMED. 판정 출력은 `{label, reason, evidenceSpan, reviewNeeded}` JSON. 판정 기준 본문 · 프롬프트 · 대기 문구는 D 담당 AI 입출력 계약 문서(WBS 1.7.3)가 채운다
B. 단계 이름을 정하지 않고 3.1로 미룬다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 12 화면이 "실제 현재 단계"를 보여야 하고(FR4.2) 저장 컬럼(attempt_type · response_type · label)이 이 이름을 쓴다. 이름은 D-05 흐름 순서 그대로다. **J28**.

## 3. 답 분석

- **모호함:** 없음. 시간 제한 숫자(Q7)는 11/2 측정 뒤 조정할 수 있는 시작값이다(NFR5 · NFR7처럼).
- **모순 점검:** Q3 답 A의 "HELLO에 토큰"은 기기 웹 JS가 토큰을 읽을 수 있어야 해서 localStorage 같은 JS 저장소가 필요한데, ADR-008은 기기 장기 인증을 localStorage/sessionStorage에 두지 않는다고 했고 개발 명세 1008은 `?token=` 쿼리를 썼다 → Q10. Q8의 메시지 이름은 ADR protocol 후보와 겹치고 비는 곳이 있다 → Q11. 끼어들기 감지 주체(GPT-Live 기능 명세 L-D03)와 끊김 때 기기 동작이 정해져 있지 않다 → Q12. Q4는 ADR protocol의 "상태 응답에 전사 원문 없음"과 다르지만 점검표 9 · K2가 화면 기준으로 이미 정했다. Q5의 거절 코드 이름은 개발 명세(SESSION_EXISTS · DEVICE_OFFLINE)와 stories · mockups(SESSION_IN_PROGRESS · DEVICE_NOT_STABLE)가 달라 규칙 1로 stories · mockups를 따랐다.
- **후속 질문:** Q10 ~ Q12(아래). 같은 대리 판단으로 답했다.

### Q10. 기기 비밀(bootstrap 비밀 · 기기 토큰)을 어디에 두고 WSS에 어떻게 보내나

A. HttpOnly 쿠키만 쓴다. 코드를 받을 때 `MUJOONG_DEVICE_BOOTSTRAP`(24시간), claim 때 `MUJOONG_DEVICE`(Secure · SameSite=Strict · 400일, `GET /api/device/me` 때마다 다시 400일). WSS는 Handshake에서 쿠키 + 허용 Origin을 확인한다. 토큰이 무효면 업그레이드 뒤 바로 4401로 닫아 기기 웹이 "다시 등록"을 알게 한다. claim은 첫 연결 전까지 다시 부를 수 있고 부를 때마다 새 토큰으로 바꾼다. 코드 발급 · 05a 등록에 횟수 제한을 둔다
B. localStorage에 토큰을 두고 HELLO 메시지로 보낸다(Q3 답 그대로)
C. 개발 명세대로 `?token=` 쿼리로 보낸다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 화면 설계 D0 · T19의 "브라우저 데이터 삭제 → 새 코드"는 쿠키로도 그대로 성립한다. ADR-008의 원칙(장기 인증을 JS가 읽는 저장소에 두지 않음)을 지키면서 XSS로 토큰이 새는 길을 막는다. C는 Nginx 접근 로그에 토큰이 남아 NFR9와 어긋난다. 같은 origin이라 쿠키가 Handshake에 실리고, SameSite=Strict와 Origin 확인으로 다른 사이트가 기기 연결을 여는 길(CSWSH)을 막는다. **J29**.

### Q11. 기기 WSS 메시지 이름과 승인 음성 전달

A. ADR protocol의 후보 이름(STOP_OUTPUT · PLAY_APPROVED_CONTENT · CLOSE_TRANSPORT · SESSION_STATE · ACK · HEARTBEAT · DEVICE_READY · VOICE_SESSION_CLOSED)을 그대로 쓰고 비어 있던 것만 새로 붙인다(HELLO · HELLO_OK · START_SESSION · OUTPUT_BEGIN · OUTPUT_END · PLAYBACK_MARK · DEVICE_STATE · HEARTBEAT_ACK · ERROR). 승인 문구 음성은 개인정보 없는 정적 파일(audioUrl)로 START_SESSION 때 미리 받아 PLAY_APPROVED_CONTENT로 틀고, 상황 대본(기회 트리거)은 개인 정보가 들 수 있어 WSS 음성 묶음(OUTPUT_BEGIN source=SCENARIO_SCRIPT)으로만 보낸다. T19의 '말하는 중'은 기기가 실제 재생으로 정하고 대기 · 듣는 중 · 생각 중 · 마무리는 SESSION_STATE로 받는다
B. 이름을 모두 새로 짓고 승인 음성도 모두 WSS로 흘려보낸다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. ADR protocol 표의 뜻(ACK는 재생 · 중단 · 참여의 증거가 아님, playbackMark는 실제 재생 범위만)을 그대로 살리면 개발 명세 · ADR을 읽은 팀원이 헷갈리지 않는다. 승인 문구를 미리 받아 두면 마무리 인사 · 재안내가 생성 지연 없이 나간다. **J30**.

### Q12. 끼어들기 감지 위치와 끊김 때 기기 동작

A. 끼어들기는 기기가 재생 중 로컬 음성 활동(에코 제거 켠 마이크의 소리 크기, 200ms 이상)을 보면 바로 재생을 멈추고 대기열을 비운 뒤 PLAYBACK_MARK(STOPPED, LOCAL_VOICE_ACTIVITY)로 알린다 — 뜻 판정이 아니며 남은 출력 처리는 서버가 한다. 연결이 끊기면 기기는 재생 · 대기열을 바로 버리고 1 · 2 · 4초 뒤 5초 간격으로 다시 접속해 HELLO resume으로 이어가기를 청한다(40초 안, 끊기기 전 출력은 잇지 않음). 대기 중에는 음성을 보내지 않는다. 4001 REPLACED면 자동 재접속하지 않는다. 「끄기」는 close 1000 DEVICE_OFF로 끊김과 구분한다
B. 끼어들기를 서버(GPT-Live 발화 시작 이벤트)가 감지해 STOP_OUTPUT을 보낸다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 0.5초(TS-N04 · FR7.8)는 서버 왕복과 공급자 감지 지연을 넣으면 넘기 쉽다. 기기의 소리 크기 감지는 뜻 판정이 아니어서 "기기는 발화 의미를 판정하지 않는다"(ADR-004)와 맞는다. 다만 크롬 에코 제거가 자기 재생음을 충분히 지우는지는 POC로 확인하고, 안 되면 B로 바꾼다(`contract-summary.md` 11절). 끊김 때 동작은 FR-E11 그대로다. **J31**.
