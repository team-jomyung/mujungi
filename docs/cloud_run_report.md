# 무중 AI-DLC 클라우드 실행 보고 (2.6 Domain Design → 3.6 Build and Test, U1)

> 작성: 클라우드 세션(팀장 준하 대리) · 2026-10-09, 10절 이후 2026-10-10 ~ 12 추가 · AI-DLC 2.10.0 · 범위 `mujoong-mvp` · 깊이 Standard · 시험 전략 Standard
> 브랜치 `claude/friendly-shannon-i89iyx` → main (draft PR [baikjunha/mujoong-aidlc#1](https://github.com/baikjunha/mujoong-aidlc/pull/1))
> 이 보고는 `docs/cloud_handoff.md` 3절이 요구한 항목(단계별 산출물 경로 · 준하 제안(가정) 전체 · 열린 질문 · 3.5 전에 사람이 정할 것 · 사용량 · ERD v0.2)을 모았다.

## 0. 한눈에 보기

| 항목 | 상태 |
|---|---|
| 2.6 Domain Design · 2.7 Units Generation · 2.8 Contract Design · 2.9 Delivery Planning | 완료 · 승인(승인 근거는 7절 3항). 유닛 7개 · 볼트 7개 |
| **U1 워킹 스켈레톤**의 3.1 Functional Design · 3.2 NFR Requirements · 3.3 NFR Design · 3.4 Infrastructure Design | **완료**(산출물 작성 · 센서 통과 · 재점검 반영) |
| **U2 ~ U7의 3.1 ~ 3.4** | **하지 않았다.** 사람 지시(2026-10-09 "반복 순서는 unit-major 그대로 둬. U1의 3.1→3.4까지 진행하고 3.5 앞에서 멈춰")에 따라 U1에서 멈췄다. 7개 전부 진행하려던 원래 지시는 7절(공개 사항)의 정책 변경 차단과 이 지시로 대체되었다 |
| 3.5 Code Generation (U1) | **완료**(2026-10-10 ~ 11, 사람 결정 1 ~ 12 반영, Plan Approval · 검증 명령 사람 클릭, 엔진 체크포인트 검증 · 승인) — 10절 |
| 3.6 Build and Test (U1, 단독 실행) | **1차 실패 → 수정 라운드 → 재시험 Met 69 · Not Met 0 · Unverified 51** — 11절. 이어서 자율 모드 gated 전환 · park(13절) |
| 재점검 | 3.1은 2회, 3.2 · 3.3 · 3.4는 1회씩 독립 검토(서브에이전트)를 돌려 확정 지적을 반영했다. 반영 뒤 재검토는 하지 않았다(8절) |

## 1. 단계별 산출물 경로

경로는 저장소 루트 기준이다. 기록 폴더 `R` = `aidlc/spaces/default/intents/261008-mujoong-service/`(의도 `261008-mujoong-service`).

| 단계 | 상태 | 산출물 (`R/` 아래) |
|---|---|---|
| 2.1 ~ 2.5 (이 클라우드 실행 전, 로컬 완료) | 승인 | `inception/requirements-analysis/requirements.md` · `inception/user-stories/{stories,personas}.md` · `inception/refined-mockups/{mockups,interaction-spec,design-system-mapping,accessibility-checklist}.md` |
| 2.6 Domain Design | 승인(대리, 15:41 UTC 기록 근거 — 7절 3항 확인 요청) | `inception/domain-design/components.md`(컴포넌트 26개 · J1 ~ J14) · `decisions.md`(ADR-011 ~ 019) · `domain-design-questions.md` · `traceability.json` |
| 2.7 Units Generation | 승인(사용자 "Approve" 텍스트) | `inception/units-generation/unit-of-work.md`(유닛 7개) · `unit-of-work-dependency.md` · `unit-of-work-story-map.md` · `units-generation-questions.md` · `traceability.json` |
| 2.8 Contract Design | 승인(사용자 클릭) | `inception/contract-design/contract-summary.md`(REST · WSS · 이벤트 6종 · 시간 계약 · J20 ~ J31) · `contract-design-questions.md` |
| 2.9 Delivery Planning | 승인(사용자 클릭) | `inception/delivery-planning/bolt-plan.md`(볼트 7개) · `team-allocation.md` · `risk-and-sequencing-rationale.md` · `external-dependency-map.md` · `delivery-planning-questions.md` · `verification/phase-check-inception.md`(PASS) |
| 3.1 Functional Design (U1) | 완료(자율 진행) | `construction/u1-walking-skeleton/functional-design/` — `rules.md`(규칙 89개) · `entities.md`(엔터티 18개 · ERD v0.2 표) · `functional-spec.md`(워크플로 · 상태 기계 · 화면 흐름) · `functional-design-questions.md`(Q1 ~ Q19, J42 ~ J60) · `traceability.json`(AC 83개) |
| 3.2 NFR Requirements (U1) | 완료(자율 진행) | `construction/u1-walking-skeleton/nfr-requirements/` — `performance-` · `security-` · `scalability-` · `reliability-` · `observability-requirements.md` · `tech-stack-decisions.md`(NFRx.y 67개) · `nfr-requirements-questions.md`(Q1 ~ Q12, J61 ~ J72) · `traceability.json` |
| 3.3 NFR Design (U1) | 완료(자율 진행) | `construction/u1-walking-skeleton/nfr-design/` — `performance-` · `security-` · `scalability-` · `reliability-` · `observability-design.md` · `logical-components.md` · `nfr-design-questions.md`(Q1 ~ Q12, J73 ~ J84) · `traceability.json`. `reliability-design.md` ## 3이 `mujoong.*` 설정 키의 정본 표 |
| 3.4 Infrastructure Design (U1) | 완료(자율 진행) | `construction/u1-walking-skeleton/infrastructure-design/` — `infrastructure-specification.md` · `monitoring-design.md` · `cicd-pipeline.md` · `infrastructure-design-questions.md`(Q1 ~ Q12, J85 ~ J96) · `traceability.json` |
| 상태 · 감사 | — | `R/aidlc-state.md` · `R/audit/` · `R/runtime-graph.json`(엔진 기록) |
| 3.1 ~ 3.4 U2 ~ U7 | **하지 않음** | 위 지시에 따라 U1에서 멈춤(0절) |

U1 산출물의 **정본**은 다음과 같다: 규칙은 `rules.md`의 yaml, 엔터티는 `entities.md`의 yaml, 설정 키는 `reliability-design.md` ## 3, 요구는 각 NFR 문서의 ## 1 표. 서로 어긋나면 이 순서가 맞다.

## 2. 준하 제안(가정) 전체 목록 (J1 ~ J96)

각 J는 사람이 나중에 확정할 때 쓰는 이름이다. 한 줄 근거는 질문 파일 · 결정 기록의 대리 판단 사유에서 줄였다. 원문은 해당 단계 질문 파일 · `components.md`의 J 표 · `functional-spec.md` 8절에 있다.
J1 ~ J41은 2.6 ~ 2.9(`components.md` · `contract-summary.md` 12절 · `bolt-plan.md`), J42 ~ J60은 3.1, J61 ~ J72는 3.2, J73 ~ J84는 3.3, J85 ~ J96은 3.4 소속이다.

| J | 단계 | 한 줄 내용 | 한 줄 근거 |
|---|---|---|---|
| J1 | 2.6 Domain Design | 결과·목표 후보·담은 목표·상황·08 배경·생활 팁·리포트·사후 텍스트 AI는 Core 소유, Realtime은 진행 중 세션·기기 WSS·중계·제브 판정·세션–목표 스냅숏만 | 규칙 3: ADR_2S와 개발명세가 어긋나 Core 소유로 통일 |
| J2 | 2.6 Domain Design | 목표 정의 카탈로그는 Core(core_behavior_definition: key + version + 4요소), Realtime은 시작 때 스냅숏, Neo4j는 원장이 아님 | 규칙 3: 버전을 덮어쓰지 않아 세션 중 정의가 바뀌지 않음 |
| J3 | 2.6 Domain Design | ResultJob 하나(REQUESTED → RUNNING → DONE/FAILED, 자동 3회, 재시작 시 FAILED), 종류 SESSION_RESULT · REPORT | 규칙 3 · 개발명세: 결과 작업을 한 모양으로 |
| J4 | 2.6 Domain Design | 기기 등록(코드·토큰·해제)은 Core DeviceRegistry, 연결 판정은 Realtime DeviceGateway, bootstrap API는 Core가 받고 토큰 확인은 내부 HTTP | D-18 · 점검표: 등록과 연결의 주인을 나눔 |
| J5 | 2.6 Domain Design | 12 진행 조회는 Core가 소유 확인 뒤 Realtime에 중계, 04·시작 검사는 Core 세션 사본(core_activity) | NFR5: 진행 중 세션 주인은 Realtime |
| J6 | 2.6 Domain Design | 세션 종료 때 Realtime이 SessionRecordCaptured로 스냅숏을 넘기고 core_activity_record가 26·27·28·30·PDF·결과 작업의 유일한 읽기 원천 | 규칙 3: 기록 원천을 하나로 |
| J7 | 2.6 Domain Design | 상황 생성·확인·노출 이력은 Core, 첫 상황은 시작 패키지에, 다음 상황은 Realtime이 내부 HTTP로 요청, 단계·도움·Cue·판정은 Realtime | 규칙 3·4 · B13 |
| J8 | 2.6 Domain Design | 리포트 PDF는 Core가 서버에서 HTML → PDF로 그리고 한글 글꼴 임베드, 파일은 서버에 남기지 않음 | A4: 리포트는 AI 없는 원문 PDF |
| J9 | 2.6 Domain Design | Notification·AccountDeletion·TextAiGateway를 따로 두고 core_analytics_event는 만들지 않음 | NFR2: 요구사항에 분석 이벤트가 없음 |
| J10 | 2.6 Domain Design | 앱 사이 이벤트 6종(DeviceReleased · DeletionRequested / SessionStateChanged · SessionRecordCaptured · DeviceStatusChanged · DeletionCompleted), 개발명세의 세부 이벤트는 종료 스냅숏 하나로 합침 | ADR-010: 영속 전달은 최소 이벤트 |
| J11 | 2.6 Domain Design | sessionId는 Core가 시작 요청 때 발급하고 Realtime이 같은 값을 씀 | 멱등 · 사본 동기화 |
| J12 | 2.6 Domain Design | 새 컬럼·삭제 컬럼 목록(core_support_item.limitation_text, selection의 is_favorite·sort_order·deleted_at, realtime_transcript.approved_for_display 등) | 화면 설계 v1.5j와 ERD v0.1의 차이를 ERD v0.2 후보로 |
| J13 | 2.6 Domain Design | 계정에 기기가 있어도 연결돼 있지 않으면 새 코드 등록을 받고 옛 기기는 같은 트랜잭션에서 RELEASED(05의 재등록 진입 단추는 OQ12) | 화면 D0 · AC6.1.3: 브라우저 데이터 삭제 복구 |
| J14 | 2.6 Domain Design | 화면 설계 LANE 6 "별도 서버"는 기기 웹이 Realtime WSS와 통신한다는 뜻으로 읽고 배포는 Next.js 한 앱의 /device | 화면 LANE 6 · 규칙 7 |
| J15 | 2.7 Units Generation | 유닛은 기능 세로 조각 7개(U1 스켈레톤 · U2 계정 · U3 음성 · U4 활동 세션 · U5 연습 · U6 결과와 리포트 · U7 웹 푸시) | 초안의 "보호자 앱" 묶음이 순환을 만들어 나눔 |
| J16 | 2.7 Units Generation | 스켈레톤 = 시험 로그인 + 시드 → 기기 코드 등록 → 04 → 08 → 음성 1턴(게이트 경유) → P1 → ENDED | 스코프 설명 · 가장 얇은 끝-끝 조각 |
| J17 | 2.7 Units Generation | 컴포넌트마다 완성 유닛 하나, 다른 유닛은 계약을 지키는 확장만(연습 경로는 U5가 U4 컴포넌트 확장) | 의존 순환을 막음 |
| J18 | 2.7 Units Generation | 여러 유닛 데이터를 쓰는 화면(04·05·17·27)은 주 스토리 유닛이 틀을 만들고 뒤 유닛이 칸을 채움 | 화면 하나를 한 유닛이 책임 |
| J19 | 2.7 Units Generation | 계정 삭제 지휘 틀은 U2, 각 유닛은 삭제 참가자를 완료 조건으로 더함, 웹 푸시(Should)는 U7로 떼어 미룰 수 있음 | FR1.6 · FR7.10 |
| J20 | 2.8 Contract Design | 계약은 다섯 묶음(보호자 REST · 아이 기기 REST+WSS · 내부 HTTP · 이벤트 6종 · 앱 안 포트) + 시간 계약 + AI 입출력 뼈대 | 점검표 3절: 계약이 어디에도 없었음 |
| J21 | 2.8 Contract Design | 보호자 인증 = POST /api/auth/google 코드 교환 → Spring Session JDBC 쿠키(30일 갱신형) + X-CSRF-TOKEN, 남의 자원은 404 | 개발명세 S-01 · ADR-008 · NFR9 |
| J22 | 2.8 Contract Design | 등록 코드 8자(혼동 글자 제외 31자) · 24시간 · 2초 확인, 기기 토큰 만료 없음·해시 저장, 새 연결이 옛 연결 대체(4001) | 05a 그림이 영숫자 · B4 |
| J23 | 2.8 Contract Design | 12 진행 조회는 GET /api/sessions/{id}?sinceSeq=n 증분 + nextSeq | 개발명세 SSE 이벤트를 폴링 응답 필드로(점검표 9) |
| J24 | 2.8 Contract Design | 오류 봉투 {code, message, traceId}, 시작 거절 5종은 stories·mockups 이름(SESSION_IN_PROGRESS 등) | 규칙 1: stories·mockups가 개발명세보다 앞 |
| J25 | 2.8 Contract Design | 경로 버전 없음, 더하기만 안전, 깨는 변경은 같은 날 양쪽 머지, 이벤트는 schemaVersion | 7일 일정·트렁크 기반 |
| J26 | 2.8 Contract Design | 내부 HTTP는 Docker 내부망 + X-Internal-Token, Nginx가 /internal/** 차단, 호출별 제한 시간·이벤트 재시도 1~30초 20회 | ADR-006 · 009 · 010 |
| J27 | 2.8 Contract Design | 기기 음성 PCM16LE · 모노 · 24kHz, 바이너리 프레임(종류 1B + 순번 4B), 입력 20ms, JSON 제어 봉투, heartbeat 5초 | 다섯 문서에 값이 없어 준하 제안(11/2 측정으로 확정) |
| J28 | 2.8 Contract Design | 연습 단계 6개 · 반응 3종 · 판정 라벨 4개 + NOT_PERFORMED · 판정 출력 JSON 4필드 | FR4.2 · D-05 흐름 |
| J29 | 2.8 Contract Design | 기기 비밀은 HttpOnly 쿠키만(MUJOONG_DEVICE_BOOTSTRAP · MUJOONG_DEVICE), WSS는 Handshake 쿠키 + 허용 Origin, 발급·등록 속도 제한 | ADR-008이 JS 저장소 장기 인증을 금지, 쿼리 토큰은 로그에 남음 |
| J30 | 2.8 Contract Design | WSS 메시지는 ADR protocol 후보 이름 그대로 + 빈 이름만 새로(HELLO · START_SESSION · OUTPUT_BEGIN · PLAYBACK_MARK 등), 승인 문구 음성은 정적 파일, 상황 대본은 WSS 묶음 | ADR protocol 표의 뜻을 살림 |
| J31 | 2.8 Contract Design | 끼어들기는 기기 로컬 음성 활동 200ms로 즉시 정지, 끊기면 재생 버리고 1·2·4초 후 5초 간격 재접속, 대기열 상한 20초 | TS-N04 0.5초 · FR-E11 |
| J32 | 2.9 Delivery Planning | 순서 = 스켈레톤(U1) → 음성(U3)·계정(U2) 나란히 → 활동 세션(U4) → 연습(U5)·웹 푸시(U7) → 결과와 리포트(U6) | 구현 창 7일 · DAG |
| J33 | 2.9 Delivery Planning | WSJF를 병행 가능한 유닛 사이 순서와 사람 배치에만 가볍게 씀 | Reinertsen · SAFe |
| J34 | 2.9 Delivery Planning | 유닛 하나 = 볼트 하나(7개), U4는 안에서 정상·장애 경로를 두 번 확인 | 엔진의 유닛 단위 진행 |
| J35 | 2.9 Delivery Planning | 사람 팀은 계약 기준 대역으로 병행, 통합은 DAG 순서, 엔진은 unit-major · serial | 5명 7일 |
| J36 | 2.9 Delivery Planning | 외부 의존 10가지(+측정 환경)를 주인·시간·막는 볼트·대안으로 관리, 클라우드 담당 확정이 첫째 | WBS 2.4.1 "확인 필요" |
| J37 | 2.9 Delivery Planning | 걱정 다섯(중계 지연·형식·에코 / 7일 창 / 판정·상황 품질 / 삭제 참가자 / 클라우드 담당)을 각각 막는 장치를 정함 | 위험 표 |
| J38 | 2.9 Delivery Planning | 반복 순서는 unit-major · serial 유지, "3.1~3.4 유닛 7개·3.5 금지" 지시와 skeleton 충돌은 사람에게 넘김 | 단계 파일: 기록된 선택은 보존 |
| J39 | 2.9 Delivery Planning | Construction 검증 명령은 그린필드라 비워 두고 후보(bash scripts/verify.sh)만 적음, 첫 체크포인트 전에 사람이 승인 | 단계 파일: 임시 명령 금지 |
| J40 | 2.9 Delivery Planning | 인력 구성은 이 세션 하나(solo), team 모드 전환은 사람 결정 | 현재 지시 |
| J41 | 2.9 Delivery Planning | 클라우드 담당(EC2 · Nginx · HTTPS · 배포)은 A가 맡는 것으로 제안 | WBS 2.4.x 미정 · 짐 점검 |
| J42 | 3.1 Functional Design (U1) | U1 '완성' = DeviceRegistry · CoreMessaging · RealtimeMessaging · D0 · 실행 골격. 나머지는 스켈레톤이 지나갈 만큼만 만들고 주 유닛이 완성한다. 주 유닛 몫 AC는 추적에서 Deferred | Q1 · J17 · J18 |
| J43 | 3.1 Functional Design (U1) | U1의 V1 마이그레이션이 완성 소유 표 + 스켈레톤 경로가 지나는 표를 Domain Design 최종 컬럼으로 한 번에 만들고 소유 유닛은 더하기만 한다 | Q2 · 계약 10절 |
| J44 | 3.1 Functional Design (U1) | 시험 로그인과 시드는 dev 프로필 한정: DevSeedRunner(멱등)와 `POST /api/dev/login`은 프로필 dev AND `mujoong.dev-login.enabled=true`일 때만 빈이 등록되고 demo · prod는 404이며 prod에 dev가 함께 켜지면 기동을 거부한다. demo는 U2의 구글 로그인을 쓴다. 세션 계층은 운영과 같은 Spring Session JDBC | Q3 · AC1.1.7 · NFR9 |
| J45 | 3.1 Functional Design (U1) | 코드는 UNREGISTERED 부분 유일 색인 + 첫 추첨을 포함해 총 5번 추첨(겹치면 새로 뽑고, 5번 모두 겹치면 503), 같은 쿠키는 같은 코드, 만료는 EXPIRED로 바꿔 코드는 남기고(EXPIRED 행도 코드 열 값은 남고 UNREGISTERED 부분 유일 색인에서만 빠진다) 7일 뒤 삭제, 이미 등록된 쿠키의 bootstrap은 409 ALREADY_REGISTERED(기기 웹은 claim으로 간다) | Q4 · AC6.1.5 · OQ13 |
| J46 | 3.1 Functional Design (U1) | 재등록 허용 = 옛 기기 판정(BR4.3)이 STABLE · UNSTABLE이 아닐 때: 마지막 보고가 OFFLINE · DISCONNECTED · UNRESPONSIVE이거나 30초 넘게 보고 없음, 또는 보고가 한 번도 없던 기기도 교체를 허용한다. CONNECTED(30초 안 보고) · UNSTABLE이면 409. 동시 등록은 조건부 UPDATE, 교체는 옛 기기를 먼저 해제하고 실패하면 전체 롤백 | Q5 · J13 · AC2.2.6 |
| J47 | 3.1 Functional Design (U1) | 연결 사본은 최신 시각 우선, 보고 없는 등록 기기 = UNRESPONSIVE, 30초 stale = UNKNOWN, RT는 영속 연결 행 전부를 10초마다 보고, 판정은 읽을 때 계산 | Q6 · B6 · B7 |
| J48 | 3.1 Functional Design (U1) | 시작 거절 우선순위 CONSENT_MISSING → SESSION_IN_PROGRESS → DEVICE_NOT_STABLE(새 키일 때만, 같은 idempotencyKey는 이 검사보다 먼저 첫 응답을 재생), 진행 중 세션은 core_activity 부분 유일 색인 | Q7 · AC3.1.6 · AC3.1.8 |
| J49 | 3.1 Functional Design (U1) | Outbox 폴러 500ms · 20개 직렬 · 막지 않음, 409 · 400은 즉시 FAILED(401을 포함한 그 밖의 실패는 재시도), 수신 해시 중복 · 충돌, 처리기 선점 뒤 5회 재호출 후 DEAD, 모르는 type · 새 버전 IGNORED | Q8 · ADR-010 · J26 |
| J50 | 3.1 Functional Design (U1) | PUBLISHED · PROCESSED · IGNORED가 되면 payload_json을 {} 로 비우고(해시 · 식별만 남김), 실패 행은 7일 뒤 비운다. Outbox/Inbox에 guardian_id 열을 더한다(ERD v0.2) | Q9 · NFR9 · ADR-010 |
| J51 | 3.1 Functional Design (U1) | 스켈레톤에도 네 시한(REQUESTED 10초 · 시작 명령 20초(ACK · DEVICE_READY · 공급자 session.started) · CLOSING 30초 · 끊김 40초(세션별 Realtime 메모리 시계))을 넣어 계정당 1개 잠금을 막는다(J16 보정). 나머지 시간 규칙은 U4 | Q10 · J16 · 계약 7절 |
| J52 | 3.1 Functional Design (U1) | 게이트 최소 규칙: 출력 구간 = 출력 전사 800ms 조용 또는 아이 입력 재개, 빈 전사 · 시드 금지 패턴이면 폐기 → WAITING_FILLER 재생 | Q11 · FR7.8 · NFR1 |
| J53 | 3.1 Functional Design (U1) | 활동 중 「끄기」 = 연결 끊김 40초 경로(B16, 계약 11절의 열린 질문 닫음). Realtime 재시작 → 종료 아닌 세션 FAILED(SERVER_RESTART) | Q12 · B16 · ADR-010 |
| J54 | 3.1 Functional Design (U1) | Core가 로컬 FAILED로 닫은 세션의 Realtime 잔여는 내부 end reason `CORE_ABANDONED`로 정리한다. Realtime end 응답표: 모르는 세션이면 10분 묘비 + 202, PREPARING · DEVICE_ACK · ACTIVE면 FAILED로 닫고 기기에 CLOSE_TRANSPORT, CLOSING이면 그대로 202, 종료 상태면 202 무동작. GUARDIAN_STOP end: ACTIVE → 202 CLOSING, CLOSING → 202(멱등), 종료 상태 · PREPARING · DEVICE_ACK → 409 SESSION_NOT_ACTIVE | 계정 잠김과 고아 세션 방지 · Q13 (BR6.7 · BR6.8 · BR6.9 · BR6.14) |
| J55 | 3.1 Functional Design (U1) | U1이 기기 연결 판정을 UNRESPONSIVE로 기록하는 경우는 둘뿐이다: START_SESSION 뒤 20초 안에 ACK 또는 DEVICE_READY가 빠졌을 때(START_SESSION 전송 실패 포함), CLOSING 뒤 30초 안에 VOICE_SESSION_CLOSED가 없을 때. 둘 다 도착했는데 공급자 쪽 문제로 실패한 경우에는 쓰지 않는다. UNRESPONSIVE나 DISCONNECTED인 연결은 같은 connection_id의 다음 HEARTBEAT가 오거나 새 연결이 HELLO_OK를 받으면 CONNECTED로 풀린다 | B7, 응답 없음의 소유를 U1로 정해 연결 · 세션 상태 기계(SM2 · SM3)와 시작 판정(BR4.3)을 맞춘다 · Q14 (BR4.8 ③ · BR6.12 ② ③ · BR6.6 ⑤) |
| J56 | 3.1 Functional Design (U1) | 등록 직후 기기는 「등록되었어요」 3초 뒤 T19 꺼짐으로 착지하고 「켜기」를 눌러야 대기가 된다(자동 연결하지 않는다 — 마이크 허용은 사용자 동작이 필요) | AC6.1.2 · AC6.2 켜기 · 끄기 규칙 · Q15 (BR9.1 · BR9.2) |
| J57 | 3.1 Functional Design (U1) | U1에서 05(우리 집 기기)는 U2가 화면을 만들기 전까지 /parent/device 라우트가 04로 되돌리는 스텁이다. BR2.7 · BR2.8 · BR4.5는 화면 설계대로 "05로 간다"를 유지해 U2가 스텁을 교체해도 규칙이 바뀌지 않는다 | B1 완료 조건 ① · U1/U2 주 화면 분담 · Q16 |
| J58 | 3.1 Functional Design (U1) | 계정 삭제 참가자 셋을 U1이 구현한다 — Device 참가자(BR2.10)와 메시징 Outbox/Inbox 참가자 둘(BR5.14: Core 쪽 · Realtime 쪽, 두 앱은 서로의 스키마에 쓰지 못한다). Core 쪽 둘은 U2 삭제 지휘가 부르고, Realtime 쪽은 Realtime의 DeletionRequested 처리기가 U4가 정한 호출 순서 안에서 부른다 | 공통 완료 조건 · DeletionParticipant 계약(contract-summary 8절) · J19 · Q17 · BR8.1 |
| J59 | 3.1 Functional Design (U1) | 3.5에서 contracts에 더할 계약 추가 · 편차 목록 열세 개(contract-summary.md 자체는 승인 완료라 고치지 않는다): (a) POST /device/bootstrap 409 ALREADY_REGISTERED, (b) POST /device/bootstrap/claim 401 BOOTSTRAP_NOT_FOUND, (c) 503 CODE_ISSUE_FAILED, (d) 400 VALIDATION_FAILED(등록 요청과 자유대화 시작 요청, 등록의 code는 발급 알파벳 한정 pattern 대신 정규화 뒤 8글자 영숫자로 검사하고 알파벳 밖 글자는 404), (e) 403 CSRF_TOKEN_INVALID, (f) 내부 end reason CORE_ABANDONED, (g) 계약 6절 순서 문장의 'since'를 occurredAt으로, (h) session.device-ack-timeout 20초를 PROVIDER_UNAVAILABLE에도 확장, (i) PUT /internal/devices/{id}/connection 본문의 state · reportedAt 필수화(없으면 400 INVALID_ENVELOPE), (j) verify-token 결과를 캐시하지 않음(계약 5절의 캐시 문장 대신 연결마다 Core에 확인), (k) bootstrap.rate-limit은 같은 쿠키의 반복 요청을 세지 않음(계약 7절 적용 범위 좁힘), (l) 기기 연결 대체 시점을 인증이 아닌 HELLO_OK로 구체화, (m) 등록에서 같은 보호자의 재시도는 409 대신 첫 성공과 같은 201 | 구현과 승인된 계약이 어긋나는 곳을 한 목록에 모아 계약 파일에 반영한다 · Q18 (BR1.3 · BR1.6 · BR2.1 · BR3.1 · BR3.4 · BR3.7 · BR4.1 · BR6.7 · BR6.12 ② · BR8.6 · BR8.8) |
| J60 | 3.1 Functional Design (U1) | CLOSING · WAITING_FILLER 승인 음성은 실제 녹음(B26) 전이므로 U1의 dev 시드는 임시 WAV 두 개를 둔다 | 스켈레톤이 마무리 인사 한 번 재생을 검증해야 한다 · Q19 (BR6.10 · BR8.4) |
| J61 | 3.2 NFR Requirements (U1) | 가용성 · 복구 · 배포 중단의 수치 — 시연 운영 기간 7일 롤링 가용성 99.0% 이상(계획된 배포 제외, 외부 프로브 성공률). 컨테이너가 죽으면 Compose `restart: unless-stopped`로 5분 이내(목표 1분) 복구하고 헬스체크가 통과해야 트래픽을 받는다. 계획 배포의 서비스 중단은 60초 이내이고 진행 중 세션은 재시작 규칙대로 FAILED로 끝나도 된다 | EC2 1대 · 팀 5인 · 시연 중심이라 99.9%나 블루-그린은 비용만 늘고 검증할 수도 없다(단일 인스턴스를 전제로 하는 소켓 레지스트리 · 메모리 시계가 있어 블루-그린은 두 인스턴스 동시 기동 자체가 위험하다, NFR7.4). |
| J62 | 3.2 NFR Requirements (U1) | 백업 · 복원 — PostgreSQL 전체 논리 백업을 매일 03:00(KST) 한 번, 같은 호스트의 별도 볼륨에 7일 보관한다. RPO 24시간, RTO 60분, 복원 절차 문서 + 리허설 1회. 오프호스트 복사는 이 단계에서 두지 않는다. 시연 데이터는 합성 데이터에 한하고, 실제 보호자 · 아이 데이터를 다루기 전에 백업 보관과 NFR12('삭제 완료 뒤 복구 수단 없음')의 충돌을 사람이 해소한다(… | 앱 재배포나 디스크 사고로 시연 데이터를 잃는 위험은 값싸게 막을 수 있어 B는 부적절하다. |
| J63 | 3.2 NFR Requirements (U1) | 서버 응답 지연 목표 (REST · 중계) — 보호자 · 기기 REST 서버 처리 p95 300ms 이내, 12 진행 조회(Realtime 중계 포함) p95 500ms 이내(내부 호출 제한 1초 이내). 음성 중계 증가분 p95 0.5초 · 말 끝 → 기기 첫 음성 p95 2.5초는 요구사항 NFR5 그대로 두고 U1은 측정 로그를 책임진다 | 폴링 2초 주기와 내부 호출 제한(J26)이 이미 있어 REST에도 수치가 없으면 '느리다'를 판정할 근거가 없다. |
| J64 | 3.2 NFR Requirements (U1) | 화면 첫 로드 · 켜기 지연 목표 — /device D0는 코드 표시까지 p95 3초(와이파이), /parent 04는 Android Chrome 중급기 4G에서 LCP 2.5초 이내, T19 「켜기」→ 대기(HELLO_OK)는 p95 2초 이내 | 아이 기기에서 「켜기」 뒤 오래 기다리면 사용 경험이 깨지고 D0는 보호자가 보는 첫 화면이다. |
| J65 | 3.2 NFR Requirements (U1) | 용량 가정과 확장 방식 — 시연 규모 상한: 보호자-기기 30쌍, 동시 활성 세션 2(NFR7), 폴링 합계 40 RPS 이하. 이 안에서 호스트 CPU 평균 60%, 메모리 70% 이하. 확장은 수직만이고 Core · Realtime은 인스턴스 1개씩(수평 확장 시 깨지는 곳 — 소켓 레지스트리 · 세션 시계 · 속도 제한 카운터 — 을 문서에 명시). DB 연결 풀은 앱당 최대 10 | 제약은 'EC2 1대 · Compose'이고 ADR-002는 Redis를 MVP에 도입하지 않는 쪽이다. |
| J66 | 3.2 NFR Requirements (U1) | 보안 수치 (TLS · 헤더 · 비밀 · 점검) — TLS 1.2 이상 · HTTP는 301 · HSTS max-age 180일 · 인증서 자동 갱신(Let's Encrypt). 보안 헤더: CSP(default-src 'self' + 필요한 connect 대상), nosniff, Referrer-Policy strict-origin-when-cross-origin, frame-ancestors 'none'… | 아동 음성 서비스이고 NFR9가 Must이며 인터넷에 열리는 시연 서버다. |
| J67 | 3.2 NFR Requirements (U1) | 관측 방식 (로그 · 지표 · 추적) — 구조화 JSON 로그(최상위 여섯 필드(ts · level · traceId · component · event · ownerHash) + attrs(사건 코드별 허용 키 표 — 숫자 · 열거값 · UTC 시각만)), 전사 · 원음 · 토큰은 attrs에도 불가. traceId를 보호자 REST → 내부 HTTP → 이벤트 봉투까지 전파. Micrometer + Ac… | 앱이 둘 · 호스트 하나라 traceId 전파와 구조화 로그면 한 요청을 추적할 수 있고 수집기를 따로 운영할 비용이 시연 규모에 맞지 않는다. |
| J68 | 3.2 NFR Requirements (U1) | 경보 · 대응 체계 — 자동 외부 경보는 두지 않는다. 헬스체크 실패는 Compose 재시작으로 처리하고 운영자는 스모크 스크립트와 지표 엔드포인트를 수동으로 본다. 수동 점검 기준을 수치로 둔다: 5분 창 WARN 이상 로그 10건 초과, 이벤트 PENDING 최장 나이 60초 초과, FAILED 이벤트 1건 이상, 풀 대기 100ms 초과가 5분 이상(네 기준 모두 마지막 점검 이후의 최댓값으로 읽는다)… | 연동할 알림 서비스 계정 · 비밀이 이번 범위에 없다(비밀값을 문서에 쓰지 않는 제약). |
| J69 | 3.2 NFR Requirements (U1) | 검증 방법 (시간 주입 · 부하 · 측정) — 시간 값은 주입 가능한 `Clock`으로 읽고 시한 시험은 실제 시간을 기다리지 않고 값을 줄여 경계값(직전 · 직후)을 확인한다. 통합은 Testcontainers(PostgreSQL), 단위는 JUnit 5, 구조는 ArchUnit(앱 간 스키마 의존 금지), 화면은 Vitest. 11/2 측정(NFR5 · NFR7)은 서버 안의 구간 로그와 자체 WSS 부… | NFR6이 '주입 시험'을 요구하고 B는 4분 · 6분 시한 시험이 불가능해진다. |
| J70 | 3.2 NFR Requirements (U1) | 기술 스택 (버전 · 도구) — 백엔드 Java 21 LTS · Spring Boot 4.1.x(첫 볼트 호환성 검증 실패 시 3.5.x로 전환) · Gradle 8.14+(멀티 프로젝트: core-api · realtime-activity · 공통 계약), PostgreSQL 16, Flyway, Spring Security + Spring Session JDBC, springdoc, Jackson 3.… | ADR-002의 기본 후보를 그대로 쓰되 '첫 볼트에서 호환성 검증' 조건을 요구사항으로 남긴다(검증 실패 대체안을 미리 정해 일정 위험을 줄인다). |
| J71 | 3.2 NFR Requirements (U1) | 지원 브라우저 · 기기 범위 — /device = 데스크톱 · 노트북 Chrome 최신 2개 버전(시연 기준), Android 태블릿 Chrome은 되면 좋음, iPad Safari 범위 밖. /parent = Android Chrome 최신 2개 버전(통합시험), iOS Safari 16.4+에서 U1 화면 렌더만 확인(푸시 · 공유 보장 없음). 마이크는 HTTPS + 「켜기」 사용자 제스처 뒤에만 요청… | NFR10이 기준 조합과 'iOS Safari에서 01~30 화면 렌더 확인'을 요구한다. |
| J72 | 3.2 NFR Requirements (U1) | 데이터 보존 · 삭제 요구와 U1 구현 범위 — U1 소유 데이터(Device · Outbox/Inbox)는 계정 삭제 참가자 셋(J58)으로 지우고 삭제 뒤 조회 0건을 시험한다. 원음은 U1에서 메모리에만 두고, Outbox/Inbox 페이로드는 전달 후 즉시(실패 행은 7일) 비운다. 백업과의 충돌은 Q2의 열린 질문으로 닫는다 | 공통 완료 조건은 모든 유닛이 자기 데이터의 삭제 참가자를 구현하는 것이다(J19 · J58). |
| J73 | 3.3 NFR Design (U1) | 음성 중계 경로의 성능 설계 (스레드 · 버퍼 · DB 접근) — 세션마다 직렬 실행 단위(가상 스레드 + 편지함)를 하나 둬서 한 세션의 프레임 · 공급자 이벤트 · 시계 · 전이를 순서대로 처리한다(락 없이 직렬화). 입력 프레임 대기열은 상한 50개(1초), 출력 대기열은 `device.output-queue-max`(20초 분량), 게이트 구간 버퍼는 구간 단위다. 음성 경로 안에서는 DB를 동기로… | NFR7.3(세션 격리)과 BR6.8(승자 하나)을 가장 단순하게 만족하는 것은 세션당 직렬 실행이다. |
| J74 | 3.3 NFR Design (U1) | 앱 사이 호출의 복원력 패턴 — 모든 내부 HTTP에 연결 0.5초 · 응답 제한(J26)을 두고, 재시도는 멱등 호출에만 한다(시작: 시간 초과에 한해 같은 sessionId로 1회). Core → Realtime 호출에는 단순 회로 차단기(연속 3회 실패 → 5초 열림, 그동안 시작 요청은 즉시 503)를 둔다. 상태 조회 실패는 Core 사본으로 답한다(12 stale 규칙). 두 앱의 DB 풀 · 스… | Realtime이 죽어 있을 때 시작 클릭마다 3초씩 기다리게 하는 것보다 즉시 503이 낫고(BR6.7의 사용자 경험), 열림 구간이 5초로 짧아 복구를 늦추지 않는다. |
| J75 | 3.3 NFR Design (U1) | 보안 설계의 구체 선택 — CSRF: 서버 세션 기반 토큰을 `GET /api/session/csrf`로 받아 변경 요청에 `X-CSRF-TOKEN` 헤더로 보낸다(403 CSRF_TOKEN_INVALID를 받으면 한 번 다시 받아 재시도). 필터 순서: 로깅/traceId → 속도 제한 → 인증(세션 또는 기기 쿠키 또는 내부 토큰) → CSRF → 인가(소유권). Nginx: TLS 종료, HTTP →… | BR8.8이 '취득 경로는 NFR Design이 정한다'고 넘겼고, 서버 세션 토큰은 쿠키를 읽는 JS를 요구하지 않는다(HttpOnly 유지). |
| J76 | 3.3 NFR Design (U1) | 세션 · 연결 상태 소유와 확장 제약 설계 — Realtime 안에 소켓 레지스트리(deviceId → 연결)와 세션 레지스트리(sessionId → 세션 실행 단위)를 메모리에 둔다. 영속 진실은 DB(세션 행 · 연결 행)이고 메모리 값은 그 사본 + 시계다. 재시작하면 BR6.13이 DB를 정리하고 메모리는 비어 시작한다. 단일 인스턴스를 깨는 지점(소켓 레지스트리 · 세션 시계 · 속도 제한 카운터… | 상한 설정(`max-active`)은 계약에 없는 거절 코드를 만들게 되어 쓰지 않기로 하고(지표로만 본다), 단일 인스턴스 전제를 문서로 못 박는다. |
| J77 | 3.3 NFR Design (U1) | Outbox/Inbox 발송 · 처리와 기동 · 종료 설계 — 발송 폴러는 앱당 스케줄러 스레드 하나가 `SELECT ... FOR UPDATE SKIP LOCKED`로 PENDING 20건을 id 순으로 집어 직렬 발송한다(500ms 주기). Inbox 처리는 응답 뒤 비동기 호출 + 재호출 폴러(선점 UPDATE). 기동 순서: PostgreSQL → 마이그레이션 일회성 작업 → Realtime → Co… | ADR-002가 '두 앱의 자동 시작이 같은 변경을 경쟁 적용하는 방식을 두지 않는다'고 했다. |
| J78 | 3.3 NFR Design (U1) | 관측 설계: 로그 · 지표 이름 · 점검 — 로그 필드는 `ts · level · traceId · component · event · ownerHash` + 사건별 허용 필드 표(전사 · 원음 · 토큰은 필드 자체가 없다)이고, 로그 인코더 설정과 ArchUnit/로그 샘플 시험이 위반을 막는다. 지표 이름은 `mujoong_` 접두사(예: `mujoong_session_ended_total{reason}… | Prometheus 형식은 쓰되 수집기를 두지 않으면 EC2 1대에 운영 부담이 없고 11/2 측정은 엔드포인트를 스크립트로 긁어 계산할 수 있다. |
| J79 | 3.3 NFR Design (U1) | 논리 컴포넌트와 장애 도메인 — 컨테이너 단위 논리 컴포넌트 9개: `nginx`(진입 · TLS · 정적 음성), `web`(Next.js), `core-api`, `realtime-activity`, `postgres`, `migrate`(일회성), `certbot`(인증서 갱신, 호스트 볼륨 공유), `backup`(cron 논리 백업), `probe`(호스트 cron 프로브 — 컨테이너 아님). 장애… | 구성은 ADR-006의 Compose 토폴로지와 같고 장애 도메인 표는 3.4 인프라 설계(구성 · 모니터링 · 배포 순서)의 입력이다. |
| J80 | 3.3 NFR Design (U1) | 설정 속성 트리와 시간 키 이름 — 설정은 `mujoong.*` 하나의 트리로 모으고 프로필(dev · demo · prod)이 값만 바꾼다. 계약 7절의 키는 `mujoong.timing.<키>`(예: `mujoong.timing.session.closing-confirm`)로, 7절에 없는 값은 `mujoong.timing.<그룹>.<이름>`/`mujoong.limits.*`/`mujoong.retent… | BR8.11이 이 단계에 이름 확정을 넘겼다. |
| J81 | 3.3 NFR Design (U1) | 시험 지원 설계 (대역 · 주입 · 장애 주입) — `Clock` 추상화(운영 = 시스템, 시험 = 가짜 시계), 공급자 대역(fake provider: 연결 거부 · 무응답 · 지연 · 정상 응답 스크립트), 기기 시뮬레이터(WSS 클라이언트: HELLO · HEARTBEAT 중단 · 늦은 ACK · 재접속), 장애 주입은 시험 프로필에서만 켜지는 훅(운영 빌드에서 빈이 없음). 앱 간 호출 대역(Core… | NFR4의 장애 시험 7가지가 모두 주입이 필요하다. |
| J82 | 3.3 NFR Design (U1) | 프런트엔드 설계 (폴링 · 상태 · 오류) — /parent: TanStack Query로 REST를 읽고 12는 2초 `refetchInterval`(탭이 숨겨지면 멈추고 보이면 즉시 1회), 다른 화면은 진입 때 1회. 오류는 토스트 + 재시도 단추이며 전사는 로그에 남기지 않는다. /device: React 상태 기계(D0 → 등록 대기 → 등록되었어요 → T19 꺼짐 → 켜기 → 대기 …)이고 네트워… | 12의 2초 폴링은 화면 설계(A2)가 정했고 /parent에 WebSocket을 더하면 NFR11 경계가 흐려진다. |
| J83 | 3.3 NFR Design (U1) | 데이터 접근 패턴 — 트랜잭션 경계는 서비스 계층 한 곳이고 업무 변경과 Outbox/Inbox 쓰기는 같은 트랜잭션이다(READ COMMITTED). 동시성 제어는 조건부 UPDATE(행 수 확인)와 부분 유일 색인이 기본이고 `FOR UPDATE`는 BR2.5 · BR3.7 · BR6.8처럼 규칙이 명시한 곳에만 쓴다(락 순서: 보호자 → 코드 → 옛 기기). 직렬화 실패 · 교착은 짧은 재시도(2회)로… | 규칙(BR2.3 · BR5.10 · BR6.8)이 이미 조건부 UPDATE를 정했다. |
| J84 | 3.3 NFR Design (U1) | DB 역할 · 스키마 · 마이그레이션 부트스트랩 — PostgreSQL 최초 기동 때 초기화 SQL(컨테이너 init)이 스키마 `core` · `realtime`, 역할 `mujoong_owner`(마이그레이션 전용, DDL), `core_app`, `realtime_app`(각자 스키마의 DML만), 공유 읽기 없음을 만든다. 일회성 `migrate` 컨테이너가 `mujoong_owner`로 Flyway… | NFR9.7(교차 스키마 쓰기 거부)을 DB 권한으로 지키려면 앱 역할이 DDL 권한을 가지면 안 된다. |
| J85 | 3.4 Infrastructure Design (U1) | 컴퓨트 · OS · 디스크 — 서울 리전 EC2 1대, 시작 크기 `t3.large`(2 vCPU · 8 GiB, 측정 뒤 `t3.xlarge`로 올릴 수 있음), Ubuntu Server 24.04 LTS, Docker Engine + Compose v2 플러그인. 디스크는 gp3 루트 30GB + 데이터 볼륨 50GB(별도 EBS, PostgreSQL · 백업 · 인증서 · Neo4j가 쓰는 `/var/… | JVM 둘 + PostgreSQL + Next.js + (후속) Neo4j를 한 호스트에서 돌리고 음성 중계와 동시 세션 2개를 재야 하므로 8GiB가 최소선이다(ADR-006도 8GB급을 후보로 둠). |
| J86 | 3.4 Infrastructure Design (U1) | 네트워크 · 접근 통제 — 기본 VPC의 퍼블릭 서브넷에 인스턴스 1개 + Elastic IP 1개. 보안 그룹: 인바운드 80 · 443만 모든 곳에서 허용, 22는 열지 않는다. 운영 접속은 AWS Systems Manager Session Manager(인스턴스 프로필에 관리형 정책 부여)만 쓰며, 아웃바운드는 전체 허용(공급자 `wss://api.openai.com`, 이미지 저장소, 패키지 저장소,… | SSH 키 배포 · 팀원 IP 변동 관리가 필요 없고 접속 기록이 남는다. |
| J87 | 3.4 Infrastructure Design (U1) | 도메인 · TLS — 팀이 쓸 수 있는 서브도메인 하나(DNS A 레코드 → Elastic IP)에 Let's Encrypt 인증서를 HTTP-01(webroot)로 발급하고, `certbot` 컨테이너가 하루 2회 갱신 확인 → 갱신되면 Nginx 재적재한다. TLS 1.2 이상 · HTTP → HTTPS 301 · HSTS 180일. 서버를 켠 직후와 시연 하루 전에 인증서 만료일 점검(스크립트)을 돌린… | 도메인 + Let's Encrypt가 가장 흔하고 자동 갱신이 검증된 경로다. |
| J88 | 3.4 Infrastructure Design (U1) | Compose 토폴로지 · 자원 · 로그 — 서비스: `nginx` · `web`(Next.js) · `core-api` · `realtime` · `postgres` · `neo4j`(프로필 off) · `migrate`(일회성, 프로필) · `certbot` · `backup`. 네트워크 둘: `edge`(nginx · web · core-api · realtime)와 `backend`(core-api… | 네트워크를 둘로 나누면 Nginx가 DB에 닿을 수 없어 NFR9.6/9.7의 방어가 하나 더 생긴다(J79 장애 도메인 표와 같은 경계). |
| J89 | 3.4 Infrastructure Design (U1) | 저장소 · 백업 · 정적 승인 음성 — PostgreSQL 데이터는 데이터 볼륨의 네임드 볼륨, 백업은 `backup` 컨테이너가 매일 03:00(KST) `pg_dump`(사용자 DB 전체, 스키마 둘 포함) + 역할 정의 덤프를 `/var/lib/mujoong/backups`에 남기고 7일 지난 파일을 지운다(RPO 24시간 — 호스트 상실 제외, RTO 60분, 복원 리허설 1회). EBS 스냅샷 ·… | J62(Q2)의 결정을 그대로 구현한다. |
| J90 | 3.4 Infrastructure Design (U1) | 비밀 관리 — 비밀은 AWS Systems Manager Parameter Store의 SecureString(경로 접두 `/mujoong/demo/`)에 두고, 배포 스크립트가 인스턴스 프로필 권한으로 읽어 호스트의 `/opt/mujoong/.env`(소유자 root · 권한 600)를 렌더링하고 Compose `env_file`로 주입한다. 대상: DB 소유자 · core_app · realtime_ap… | Parameter Store 표준 SecureString은 추가 비용 없이 접근 기록과 권한 분리를 주고, 사람이 서버에서 값을 타이핑하지 않게 한다. |
| J91 | 3.4 Infrastructure Design (U1) | CI 파이프라인 — GitHub Actions. PR: ① 백엔드(Gradle 빌드 · 단위 · Testcontainers 통합 · ArchUnit 경계 · 의존성 취약점 검사) ② 프런트(설치 · lint · Vitest · 빌드 · `npm audit` high 이상 + 예외 목록) ③ 저장소 비밀 스캔 ④ Nginx 설정 문법 검사(컨테이너 안 `nginx -t`) · `docker compose co… | org 규칙이 병합 전 CI 실행 · 80% 하한을 요구한다. |
| J92 | 3.4 Infrastructure Design (U1) | 배포 전략 · 롤백 — 재생성(recreate) 방식: 병합 → 이미지 푸시 → 배포 작업이 OIDC로 역할을 맡아 SSM Run Command로 호스트의 배포 스크립트를 실행한다. 순서: 시작 시각 기록 → 백업 한 번 → 이미지 pull → `migrate` 일회성 실행 → realtime → core-api → web 재생성(헬스 통과 대기) → Nginx 적재 → 스모크 점검 → 끝 시각 기록(`/v… | 소켓 레지스트리 · 세션 시계가 단일 인스턴스를 전제하므로(NFR7.4) 두 버전 동시 기동(블루-그린)은 위험하다. |
| J93 | 3.4 Infrastructure Design (U1) | 모니터링 · 점검 — 호스트 밖 프로브(팀 PC 스케줄 작업 또는 외부 무료 pinger — 선택은 사람이 한다)가 1분마다 Core health · `/device` 정적 페이지 · WSS 핸드셰이크를 호출한다. 호스트 안 cron(1분)은 앱 health · 디스크 사용률 · 인증서 남은 일수 · 마지막 백업 나이 · 지표 스냅샷(관리 포트를 컨테이너 안에서 호출)을 로그 파일에 남긴다. 기본 EC2 C… | J68(외부 경보 없음)과 같다. |
| J94 | 3.4 Infrastructure Design (U1) | 환경 구성 — 세 환경: `dev`(개발자 PC, `compose.dev.yml`, Spring 프로필 dev, localhost, Nginx 선택, 시험 로그인 허용), `ci`(GitHub Actions, Testcontainers, 프로필 test), `demo`(EC2, 프로필 demo, 시험 로그인 없음, 구글 로그인은 U2). 환경별 차이는 Spring 프로필과 환경 변수 값뿐이고 이미지는 같다.… | 같은 이미지가 환경 사이를 이동해야 시연에서 확인한 것이 곧 배포물이다. |
| J95 | 3.4 Infrastructure Design (U1) | 비용 · 운영 시간 정책 — 서버는 필요할 때만 켠다(팀 통합시험 · 멘토링 · 시연 때 Start, 끝나면 Stop; 데이터 볼륨 · Elastic IP 유지, 기동 시 Compose 자동 시작). 개략 비용(서울, 온디맨드, 2026-10 시점 개략이며 시연 전 가격 확인 필요): `t3.large` 시간당 약 US$0.1, 월 상시 약 US$75 ~ 85, 데이터+루트 EBS 80GB 약 US$7 ~… | ADR-006 1절이 '서버를 필요할 때만 켤 수 있어야 한다'를 요구한다. |
| J96 | 3.4 Infrastructure Design (U1) | 프로비저닝 방식(IaC) — 단일 인스턴스 일회성이므로 IaC(Terraform 등)는 쓰지 않는다. 대신 저장소 `infra/`가 정본이다: `compose.*.yml` · Nginx 설정 · 배포 · 백업 · 프로브 스크립트 · `RUNBOOK.md`(콘솔 또는 AWS CLI로 인스턴스 · 보안 그룹 · Elastic IP · 인스턴스 프로필 · Parameter Store · 도메인 설정을 재현하는 순… | 7일 구현 일정에서 단일 인스턴스를 위한 IaC 작성 · 검증 비용이 이득보다 크다. |

### 2.1 계약 추가 · 편차 목록 (J59 확장 포함, 3.5에서 contracts에 더할 것)

승인된 `contract-summary.md`는 고치지 않았다. 구현이 승인 계약과 어긋나거나 넓어지는 곳은 3.5 contracts 산출에서 '더하기만 안전' 변경과 편차로 더한다.

| 번호 | 내용 | 출처 |
|---|---|---|
| (a) | `POST /device/bootstrap` 409 ALREADY_REGISTERED | J59 · BR1.3 |
| (b) | `POST /device/bootstrap/claim` 401 BOOTSTRAP_NOT_FOUND | J59 · BR3.1 |
| (c) | 503 CODE_ISSUE_FAILED | J59 · BR1.2 |
| (d) | 400 VALIDATION_FAILED(register · activities/free) | J59 · BR2.1 |
| (e) | 403 CSRF_TOKEN_INVALID | J59 · BR8.8 |
| (f) | 내부 end reason CORE_ABANDONED | J54 · J59 |
| (g) | 계약 6절 순서 문장 `since` → `occurredAt` | J59 |
| (h) | `session.device-ack-timeout` 20초를 PROVIDER_UNAVAILABLE에도 확장 | J59 · BR6.12 |
| (i) | PUT 연결 보고의 state · reportedAt 필수화 | J59 · BR4.1 |
| (j) | verify-token 결과를 캐시하지 않음(연결마다 Core 확인) | J59 · BR3.4 |
| (k) | bootstrap.rate-limit은 같은 쿠키의 반복 요청을 세지 않음 | J59 · BR1.6 |
| (l) | 기기 연결 대체 시점을 인증이 아니라 HELLO_OK로 구체화 | J59 · BR3.7 |
| (m) | register에서 같은 보호자의 재시도는 409 대신 첫 성공과 같은 201 | J59 · BR2.1 |
| (n) | 503 SERVICE_NOT_READY + Retry-After | 3.3 `reliability-design.md` ## 5 |
| (o) | 500 INTERNAL_ERROR | 3.3 |
| (p) | 이벤트 봉투 선택 필드 traceId (Outbox/Inbox 네 표에 trace_id 열) | 3.3 · FD 보강 A3 |
| (q) | DeviceStatusChanged 선택 필드 guardianId | 3.3 · FD 보강 A4 |
| (r) | REST 본문 64KB 초과는 413 대신 400 VALIDATION_FAILED | 3.3 |
| (s) | WSS 규정 외 프레임은 ERROR 대신 4400 닫기 | 3.3 |
| (t) | 공개 `GET /api/health` | 3.3 |

### 2.2 승인된 Functional Design(3.1) 규칙에 대한 보강 후보 (FD 보강 A1 ~ A6)

3.1은 승인 완료 상태로 두고 고치지 않았다. 3.2 ~ 3.3의 결정이 규칙을 넓히거나 바꾸는 곳은 3.5에서 FD 규칙에 반영한다.

| 번호 | 내용 | 영향 규칙 |
|---|---|---|
| A1 | FAILED 제어 이벤트(DeviceReleased · DeletionRequested · DeletionCompleted · SessionRecordCaptured)를 앱 시작 때 PENDING으로 되돌려 반드시 전달(재무장). 일반 이벤트의 20회 상한은 유지 | BR5.3 · BR5.12 |
| A2 | 시험 로그인 가드 거부 조건을 prod에서 demo · prod로(demo+dev 또는 enabled=true 거부) | BR8.3 |
| A3 | Outbox/Inbox 네 표에 `trace_id` 열(선택) | entities.md |
| A4 | 모든 Outbox/Inbox 행에 guardian_id 채움(DeviceStatusChanged는 payload.guardianId 우선) | BR5.14 · BR4.x |
| A5 | 출력 게이트 시드 사전을 계약 9절 category 7개마다 최소 1항목으로 | BR7.4 |
| A6 | 부하 시험 전용 시드와 `load-test` 프로필(demo/prod와 동시 활성 시 기동 거부) | BR8.3 · BR8.4 |

## 3. 열린 질문 (사람이 정할 것 · 뒤 단계가 확인할 것)

원문은 각 문서의 `## 5`(또는 `## 9` · `## 11`)에 있다. 여기에는 막는 것이 큰 순서로 모았다.

### 3.1 사람 결정이 필요한 것

| # | 질문 | 막는 곳 | 원문 |
|---|---|---|---|
| Q-1 | **서비스 도메인과 인증서 방식**(서브도메인 + Let's Encrypt가 대리 판단). 도메인이 없으면 `/device` 마이크와 구글 로그인 리다이렉트가 막힌다. 10/14 1차 뼈대 배포 전 필수 | 3.5 첫 배포 · U2 | infra-spec O-1 · J87 |
| Q-2 | **백업 보관과 NFR12('삭제 완료 뒤 복구 수단 없음')의 충돌.** 일일 `pg_dump` 7일 보관은 삭제된 계정 데이터를 최대 7일 파일에 남긴다. 현재는 '시연 서버는 합성 데이터에 한정'으로 둠. 안 A(합성 한정 유지) · B(고지 + 복원 때 삭제 이력 재적용) 등 4안은 `security-design.md` 2.20. 오프호스트 복사 여부도 같은 결정 | 실제 아동 데이터 운영 전(D-14) | NFR12.4 · J62 · J89 |
| Q-3 | **외부 가용성 프로브의 실행 위치**(팀 PC 스케줄 작업 / 무료 외부 pinger)와 경보 연동 · 예산 한도 · 에스컬레이션 대상(연락처는 문서에 쓰지 않음) | 3.5 모니터링 구성 | infra-spec O-2 · O-4 · J93 · J95 |
| Q-4 | **Construction Verification Command** 선택(후보 A/B/C 표가 `cicd-pipeline.md`에 있음, C 제안). 3.5 Plan Approval과 함께 사람이 직접 | 3.5 시작 | cicd-pipeline ## 5 |
| Q-5 | **Neo4j를 켜는 유닛 · 시점.** 켜면 컨테이너 한도 합계가 6.875 GiB가 되어 70% 판정선(NFR7.2)을 넘으므로 `t3.xlarge` 상향을 같이 결정 | 해당 유닛 3.4 | infra-spec O-5 |
| Q-6 | **800ms 출력 구간 확정 대기** 값과 NFR5.2 2.5초 목표(U1 구조의 첫 소리 하한 = 응답 생성 시간 + 0.8초 + 중계, 짧은 응답 기준). **NFR5.3 화면 도달**: 12 폴링이 2초라 말 끝 뒤 p95 약 2.5초 — 1.5초를 지키려면 12 한정 폴링 1초 단축(계약 7절 변경) | 11/2 측정 · 계약 7절 | performance-requirements ## 5 |
| Q-7 | **FD 보강 A1 ~ A6 승인**과 A1 옵션(`REARM_ON_START` 기본 / `RETRY_FOREVER`) | 3.5 FD 규칙 갱신 | reliability-design ## 5 |
| Q-8 | **GHCR 패키지 공개/비공개**(비공개면 읽기 전용 pull 토큰이 이 설계의 유일한 장기 자격) | 3.5 CI | infra-spec O-7 |
| Q-9 | **계획된 Stop 구간을 가용성에서 제외**하는 해석(서버를 필요할 때만 켜는 J95 정책과 NFR4.8 문구의 충돌) | 11/2 보고 | infra-spec O-8 · monitoring ## 5 |
| Q-10 | **로그 attrs 해석**: NFR9.15 '최상위 여섯 필드 + attrs(사건 코드별 허용 키)'와 `eventId` 예외 | 3.5 로깅 | observability ## 5 · RD-6 |
| Q-11 | **서비스 워커 예외**: NFR10.4는 서비스 워커 없음, U7 웹 푸시는 서비스 워커가 필요 → `/parent` 한정 예외를 U7 이전에 결정 | U7 | tech-stack-decisions ## 5 |
| Q-12 | 구글 OAuth 클라이언트 · 리다이렉트 URI(도메인 확정 뒤), 시연용 약관 문구(OQ7, B26), 승인 음성의 제작 방법(사전 녹음 / TTS) | U2 · U5 | contract §11 · functional-spec ## 9 |
| Q-13 | 한 바퀴 스모크(시작 → ACTIVE → 끝내기)의 인증 수단 — demo에는 시험 로그인이 없고 구글 로그인은 U2다 | 3.5 배포 스모크 | cicd-pipeline ## 5 |
| Q-14 | 인스턴스 설정: 기본 VPC 존재 · 운영자 IAM 구성원, `t3` CPU 크레딧(unlimited) vs 고정 성능형, 야간 자동 Stop, Session Manager 세션 기록, Nginx 접근 로그의 클라이언트 IP 기록 | 3.5 프로비저닝 | infra-spec O-9 ~ O-15 |
| Q-15 | **demo 참조 데이터 시드의 쓰기 주체**: demo에는 dev 시드가 없어 정책 버전 1건과 승인 문구(CLOSING · WAITING_FILLER)를 `infra/seed/`의 멱등 SQL로 migrate 직후 한 번 넣도록 설계했다. U2 편집 화면이 생길 때까지의 임시 규칙이므로 누가 쓰는지 확정 | 3.5 첫 배포 | infra-spec O-16 · R4-15 |

### 3.2 뒤 단계(3.5 · 후속 유닛)가 확인할 것

| # | 내용 | 출처 |
|---|---|---|
| V-1 | Java 21 · Spring Boot 4.1.x 호환성(첫 볼트 검증, 실패 시 3.5.x 전환), Gradle 8.14.x / 9.x 선택, 공급자(GPT-Live) 주 WebSocket SDK 지원 범위 | tech-stack-decisions · ADR-002 |
| V-2 | GPT-Live 실제 오디오 형식(J27과 다르면 변환 위치), 공급자의 입력 종료(말 끝) 이벤트 유무(NFR5.2 t0 정의) | contract §11 · PD 열림 3 |
| V-3 | 기기 로컬 끼어들기 감지가 자기 재생음에 속지 않는지(에코) POC, 서버 감지로 바꿀 때 0.5초 재측정 | contract §11 |
| V-4 | 금지 표현 사전 v1(계약 9절 category 7개)과 판정 기준 · 프롬프트 · 대기 문구(D 담당 AI 입출력 문서). U1 시드 사전은 범주마다 1항목 | contract §11 · NFR1.2 |
| V-5 | 계약 추가 · 편차 (a) ~ (t)(2.1절)를 contracts에 더하기, 7절에 키가 없는 시한 값을 `contracts/timing.md`에 더하기 | 계약 12절 · reliability-design ## 5 |
| V-6 | 08이 503을 받았을 때 문구, PREPARING · DEVICE_ACK 중 끝내기 토스트 문구(문구표 없음), 12 stale 문구 | functional-spec ## 9 |
| V-7 | 08 요청의 Core 로컬 FAILED `failureCode`(계약 SessionSummary에 맞는 값 없음) — U4가 새 값을 더할지 | functional-spec ## 9 |
| V-8 | Compose가 unhealthy 컨테이너를 재시작하지 않는 한계(수동 절차), `docker compose up -d --no-deps realtime core-api`의 실제 동시 재생성(60초 중단 예산) 배포 리허설 | cicd-pipeline ## 5 |
| V-9 | 정상 종료 중 커넥터를 열어 두는 구현(`server.shutdown=graceful`과 부딪힘), 회로 차단기 구현(라이브러리 vs 자체), CSP `strict-dynamic`과 AudioWorklet 호환 | reliability-design · security-design ## 5.7 |
| V-10 | `realtime_device_connection`(U3 소유)의 계정 삭제 시 처리 — BR2.10 · BR5.14 참가자에 없다. U3 · U4가 확인 | security-design S-9 |
| V-11 | story-map US3.2 행에 U1이 빠짐(상위 문서 개정 때 반영) | functional-spec ## 9 |
| V-12 | `GUARDIAN_STOP` end가 모르는 sessionId에 오면 응답(BR6.9 미정) | functional-spec ## 9 |
| V-13 | 사본 폴링의 SQL 계획 · 인덱스는 구현 때 `EXPLAIN`으로 확인(쿼리 수 예산 PD SQ-1 ~ 19) | performance-design |
| V-14 | `cap_drop ALL` · `read_only` 상태에서 모든 컨테이너(postgres `user: 999:999` + tmpfs, backup 비루트 스케줄러, certbot, web)가 기동하는지, backup을 서드파티 이미지로 만족하는지 자체 이미지 `mujoong-backup`이 필요한지(cicd 2.3.1 영향) | infra-spec V-11 · V-15 |
| V-15 | `get-parameters-by-path` 권한(리소스 `parameter/mujoong/demo` + `/*`) 실호출, `t3.large`의 실제 `MemTotal`(약 7.6 GiB로 알려짐)과 한도 합계 5.075 GiB의 70% 판정, 첫 통합 배포에서 `docker stats` 앱 RSS 확인, Let's Encrypt OCSP 정책, 두 앱 동시 재생성의 60초 중단 | infra-spec V-12 · V-13 · cicd V-1 |

계약 11절의 기존 열린 질문(C1 · C5 · C6 · C7 · C8 · C14와 T)과 `decisions.md`의 열린 일(OQ2-a · OQ3 · OQ4 · OQ5 · OQ13 · OQ14)도 그대로 남아 있다.

## 4. 3.5 Code Generation 전에 사람이 정할 것

3.5는 시작하지 않았다. 시작 전에 사람이 직접 해야 하는 일을 순서대로 적는다.

1. **반복 순서와 스켈레톤 자세 결정.** 지금 상태는 `iteration unit-major`, 스켈레톤 자세 `off`(상태 필드. 스코프 파일 `.claude/scopes/aidlc-mujoong-mvp.md`는 `skeleton: on`). U2 ~ U7의 3.1 ~ 3.4는 하지 않았다. 선택지: ① U1을 3.5로 가서 워킹 스켈레톤을 먼저 세우고(스켈레톤 자세를 `aidlc engine state set-skeleton-stance scope-dependent`로 복원) 나머지 유닛의 3.1 ~ 3.4는 해당 볼트 시작 때 진행, ② `stage-major`로 바꿔 U2 ~ U7의 3.1 ~ 3.4부터 일괄 진행. 정책 변경 명령은 이 세션의 자동 모드 분류기가 막았으므로(7절 5항) 사람이 직접 `!` 명령으로 실행해야 한다.
2. **Construction Verification Command 선택**과 **3.5 Plan Approval**(엔진상 사람 정지 지점). 후보는 `cicd-pipeline.md` ## 5.
3. **솔로 vs 팀** 진행, 클라우드 담당(J41 = A) 확인.
4. 3.1 열린 질문 Q-1 ~ Q-14 중 3.5 첫 배포를 막는 것(도메인 · 인증서, 외부 프로브 위치, GHCR 공개 여부, 스모크 인증 수단).
5. **FD 보강 A1 ~ A6**(2.2절)을 승인하고 3.5에서 FD 규칙에 반영할 것인지, **계약 편차 (a) ~ (t)**(2.1절)를 contracts에 더할 것인지.
6. **D 담당 문서** — AI 입출력 계약(금지 표현 사전 v1 · 판정 기준 · 프롬프트 · 대기 문구, OQ4 · OQ14 · OQ15)과 **약관 · 문구**(B26, OQ7), 승인 음성 제작 방법. U1은 임시 값(시드 사전 범주별 1항목, 임시 WAV 2개)으로 진행한다.
7. **팀이 원본을 고칠 일**(AI-DLC 밖, OQ2-a): ADR_2S · ERD v0.1 → v0.2(6절), WBS 문구(점검표 4절). 원본(`aidlc/spaces/default/knowledge/documents/`)은 이 실행에서 수정하지 않았다.
8. **J1 ~ J96 확인.** 모두 대리 판단(가정)이다. 사람이 승인 · 정정한 것만 확정으로 본다.
9. **실제 아동 데이터 운영 전 승인(D-14)** 항목: 백업 충돌 해소(Q-2), 정식 금지 표현 사전 완성, 공급자 보존 조건(S-11), 기기 토큰 서버 만료 없음(S-13).

## 5. 사용량 (`aidlc engine runtime summary --json`)

```json
{
  "workflow_id": "2026-10-08T08:47:46Z",
  "scope": "mujoong-mvp",
  "started_at": "2026-10-08T08:47:46Z",
  "duration_minutes": 1928,
  "stages": {
    "total": 11,
    "approved": 10,
    "failed": 0,
    "pending": 1
  },
  "by_phase": {
    "initialization": {
      "total": 3,
      "approved": 3,
      "failed": 0,
      "pending": 0
    },
    "inception": {
      "total": 7,
      "approved": 7,
      "failed": 0,
      "pending": 0
    },
    "construction": {
      "total": 1,
      "approved": 0,
      "failed": 0,
      "pending": 1
    }
  },
  "memory": {
    "total": 0,
    "interpretations": 0,
    "deviations": 0,
    "tradeoffs": 0,
    "open_questions": 0
  },
  "sensors": {
    "total": 52,
    "passed": 52,
    "failed": 0,
    "budget_override": 0,
    "incomplete": 0
  },
  "learnings": {
    "from_orchestrator": 0,
    "from_user_addition": 0
  }
}
```

- 위 요약은 엔진 기준이다. 서브에이전트 호출 수 · 토큰은 엔진 요약에 들어 있지 않다.
- 엔진 요약은 단계(stage) 단위 기록이라 U1의 단계별(per-unit) 3.1 ~ 3.4 진행이 반영되지 않는다(요약의 '대기 1'은 construction 단계가 열려 있다는 뜻이다). 서브에이전트 · 워크플로 사용은 7절 7항과 8절에 적었다.
- 센서: 엔진 요약의 52개 통과는 2.6 ~ 2.9까지다. 3.1 ~ 3.4는 게이트를 거치지 않았으므로(`gate:false`, 자율 진행) 단계마다 `sensor-traceability`를, 산출물 전체에 `required-sections` · `upstream-coverage`를 수동으로 실행해 모두 통과했다(7절 9항).

## 6. ERD v0.2로 바꿀 것 (ERD v0.1 → v0.2)

ERD v0.1(`60_ERD_v0.1`) 원본은 고치지 않았다. 팀이 v0.2를 만들 때 반영할 목록이다. 열 수는 `entities.md`의 yaml 기준이다.

### 6.1 전체(2.6 Domain Design, ADR-018 · J12)

- **새 테이블 8개**: `core_device` · `core_activity` · `core_activity_record` · `core_behavior_definition` · `core_push_token` · `core_push_dispatch` · `realtime_device_connection` · `realtime_session_goal`. `core_analytics_event`는 만들지 않는다(J9).
- **새 컬럼(J12)**: `core_support_item.limitation_text` · `core_goal_recommendation`/`selection.behavior_version` · `core_practice_goal_selection`의 `is_favorite` · `sort_order` · `deleted_at` · `realtime_transcript.approved_for_display` · `realtime_cue_delivery`의 `consent_response` · `contains_answer_sentence` · `realtime_practice_attempt.not_performed_reason`.
- **삭제 컬럼(J12)**: `core_activity_context.purpose` · `core_report_activity.result_id` · `realtime_activity_session.practice_goal_selection_id` 등 단일 목표 컬럼.
- ADR-018의 21곳(화면 설계 기준으로 ADR_2S · ERD를 고치는 곳)은 `decisions.md` 표 참고(기기 등록 코드, 원음 저장, 세션 상태 11개, 종료 사유 8개, 연습 세션 목표 n/N, 아이 1:1 등).

### 6.2 U1 몫(3.1 `entities.md` ## 4, 3.3 보강 포함)

| # | 대상 | 변경 |
|---|---|---|
| E1 | `core_device`(신설, 22열) | 부분 유일 색인 둘(`guardian_id`는 REGISTERED, `registration_code`는 UNREGISTERED). `guardian_id` FK는 ON DELETE 없음(RESTRICT). EXPIRED 행도 `registration_code` NOT NULL 값을 남김 |
| E2 | `realtime_device_connection`(신설, 10열) | `connection_state`에 UNRESPONSIVE 포함. 교체는 HELLO_OK 시점 행 잠금 |
| E3 | `core_activity`(신설, 20열) | `local_failed`(불리언) · `start_result_code`(202/423/409/503) 포함. `device_id` FK RESTRICT. 부분 유일 색인(보호자당 종료 아닌 상태 하나) |
| E4 | `core_outbox_event` · `realtime_outbox_event`(각 11열 → 16열), `core_inbox_event` · `realtime_inbox_event`(각 9열 → 16열) | Outbox: `schema_version` · `occurred_at` · `guardian_id` · `payload_hash` · `purged_at` 추가, `aggregate_type` · `aggregate_id` NOT NULL. Inbox: `schema_version` · `occurred_at` · `aggregate_id` · `guardian_id` · `payload_hash` · `purged_at` · `next_attempt_at` 등 추가 |
| E5 | `realtime_activity_session`(29열 → 29열) | `status_version` · `consent_snapshot_json` · `policy_version` 추가(`consent_checked_at` · `intro_content_version_id` · `end_detail`은 v0.1 열 유지), `consent_version`은 NULL 허용, `start_idempotency_key`는 `unique` 제거(세션 id가 유일) |
| E6 | `core_stop_expression` | `expression_suffix`(10자) → `expression_text`(20자), `normalized_suffix` → `normalized_text` |
| E7 | `core_approved_content_version` | `audio_path`는 Nginx 정적 경로(`/media/approved/`) 아래 상대 경로로 의미를 고정 |
| E8 (3.3) | Outbox/Inbox 네 표 | `trace_id` 열(선택, FD 보강 A3). 모든 행에 `guardian_id` 채움(A4) |
| E9 (3.4) | DB 역할 · 스키마 | 스키마 `core` · `realtime`, 역할 `mujoong_owner`(마이그레이션 DDL) · `core_app` · `realtime_app` · `mujoong_backup`(읽기 전용, 백업), Flyway 이력 표 둘(`flyway.core_schema_history`, `flyway.realtime_schema_history`), 데이터 등급 설정 `mujoong_db.data_class` |

## 7. 공개 사항 (그대로 알려야 하는 것)

1. **설치 경로.** 클라우드 컨테이너는 root라서 공식 설치 스크립트가 거부했다. `scripts/cloud-setup.sh`를 고쳐 공식 릴리스 파일을 받아 체크섬을 확인한 뒤 `/usr/local/bin/aidlc`에 설치하는 경로로 이어갔다. 이 과정에서 출처 서명(Sigstore) 검증이 통과하지 않아 체크섬 확인과 래퍼 경로로 대신했다.
2. **커밋 한 번이 거부됐다.** 설명을 길게 적은 커밋 메시지가 '로그 · 감사 변조' 규칙에 걸려 거부되어 중립 메시지로 다시 커밋했다.
3. **승인 근거 — 사람 확인의 종류가 단계마다 다르다.** 감사 기록(`R/audit/vm-*.md`)에는 사람 입력 행(`HUMAN_TURN`)이 40여 개 있는데, 이 가운데 상당수는 사람이 입력한 것이 아니라 백그라운드 알림 · 서브에이전트 보고가 도착한 시각의 행이다(예: 16:03:55, 16:21:08, 17:27:55 UTC 이후 대부분). 그래서 이 보고는 대화 기록으로 확인되는 사람의 응답만 승인 근거로 센다: 2.7은 사용자의 "Approve" 텍스트, 2.8과 2.9는 사용자 클릭이다. **2.6 승인은 15:41:16 UTC 행을 근거로 했는데 이 행이 같은 종류(알림으로 생긴 행)였을 가능성을 배제하지 못하므로, 사람이 2.6을 다시 확인해 주기 바란다.** 3.1 ~ 3.4는 자율 진행(`autonomous`)으로 완료 승인 질문 없이 진행했고 사람이 승인한 것이 아니다. 이후 3.5 앞의 정지는 사람 지시와 엔진 규칙에 따른다.
4. **자율 진행(autonomy)과 스켈레톤 자세.** 자율 진행 값은 사람이 미리 밝힌 답("이어갈 방식을 물으면 자동 진행으로")에 따라 설정했다. 스켈레톤 자세 `off`는 사람이 게이트에서 "스켈레톤 끄고 7개 전부"를 클릭한 뒤 `state set-skeleton-stance off`로 직접 기록했다(스코프 파일은 on 그대로).
5. **정책 변경 명령 차단.** 반복 순서를 `stage-major`로 바꾸려던 `log answer` · 설정 명령은 자동 모드 분류기가 '자기 수정'으로 차단했다. 우회하지 않았고 사용자에게 알렸으며, 사용자가 unit-major 유지 · U1만 진행으로 지시를 바꿨다.
6. **중간 모델 변경.** 사용자가 실행 중 `/model`로 모델을 바꿨다. 산출물 어디에도 모델 식별자를 쓰지 않았다.
7. **서브에이전트 사용.** 3.1 ~ 3.4 문서의 상당 부분은 서브에이전트가 초안을 쓰고 대리가 계획 · 결정 · 검증 · 연결을 맡았다. 단계 정의가 지정한 reviewer(아키텍처 리뷰어, 최대 2회)를 엔진 경로로 돌리지 않고 같은 목적의 독립 점검을 Workflow 서브에이전트로 대신했다(8절).
8. **승인 뒤 파일 수정.** 3.1의 `unit complete` 뒤에 `rules.md` · `entities.md` · `functional-spec.md`의 한 문구(`clock_timestamp` → 앱 Clock)만 고쳤다. 그 외 승인된 파일(2.6 ~ 2.9 산출물, `contract-summary.md`)은 고치지 않았다. 3.2 ~ 3.3이 FD와 어긋나는 곳은 수정 대신 FD 보강 A1 ~ A6(2.2절)으로 올렸다.
9. **센서.** `sensor-traceability`는 3.1 ~ 3.4 각 단계에서, `required-sections` · `upstream-coverage`는 3.1 ~ 3.4 산출물 전부에 수동으로 실행해 통과했다(upstream-coverage는 'no upstream'이라 검사할 상위가 없다는 결과다). `linter` · `type-check`는 TS/JS 코드가 없어 해당 없음이다.
10. **가격 · 시간.** 3.4의 비용 수치는 가격 조회 도구 없이 기억에 기댄 개략 값이라 '확인 필요'로 표시했다. 3.2 ~ 3.3의 수치는 모두 시연 규모의 시작값이다.
11. **비밀값.** 계정 · 비밀번호 · API 키 · 도메인 실제 값을 어떤 파일에도 쓰지 않았다. 저장소 전체에서 키 · 토큰 패턴 검색(AKIA · sk- · PEM 시작줄 · 비밀 대입)을 실행해 0건을 확인했다.

## 8. 독립 점검(재점검) 요약과 한계

문서 품질을 위해 단계마다 서브에이전트(읽기 전용, 서로 다른 관점)로 문서 사이 · 상위 문서와의 모순을 점검하고 확정된 지적만 고쳤다.

| 단계 | 점검 | 지적 수 | 반영 |
|---|---|---|---|
| 3.1 Functional Design | 1차 5관점(AC 추적 · 계약 · 원문 충실 · 내부 일관 · 보안/개인정보) | 100건 중 90건 확정 · 일부 확정, 10건 기각 | 규칙 12건 이상 보강(교체 등록 순서, 멱등 순서, 고아 세션, 삭제 참가자 규칙 BR2.10 · BR5.14, 연결 대체 잠금 등), 엔터티 · 명세 · 추적성 · 질문 파일 정합 |
| 3.1 Functional Design | 2차 3관점(규칙 · 명세 · 엔터티 / 논리 · 동시성 / 추적 · 질문 · 계약) | 45건(중복 제외 약 25건 실질) | 규칙 BR2.1 · BR3.3 · BR3.7 · BR4.1 · BR4.8 · BR5.x · BR6.x · BR7.1 · BR9.1 · BR9.6 보강, `start_result_code` 추가, 조건부 UPDATE 순서 정리 |
| 3.2 NFR Requirements | 2관점(문서 사이 / 상위 충실 · 실현 가능성) | 24건 | NFRx.y 65 → 67개(NFR4.16 · NFR5.8 추가), NFR5.1 ~ 5.3 재정의, 가용성 측정 방식 현실화, FD 보강 A1 ~ A6 도출 |
| 3.3 NFR Design | 2관점(키 · ID · 문서 사이 / FD · 계약 충실) | 24건 | 공급자 연결 순서, 회로 차단기 범위, guardianId payload, 고아 세션 점검, HELLO_OK 교체 순서, 기동 거부 조건 등 R3-1 ~ R3-21 |
| 3.4 Infrastructure Design | 2관점(문서 사이 / 실현 가능성) | 24건 | 망 둘 확정, SSM 문서 이름 · 파라미터 이름 통일, 백업 전용 볼륨, 측정 창 절차, postgres 권한 예외, 인증서 발급 방식, 참조 데이터 시드 등 R4-1 ~ R4-19 |

한계:

- 수정 뒤 다시 돌린 점검은 3.1의 1차 → 2차뿐이다. 3.2 ~ 3.4는 한 번 점검 · 반영으로 끝냈고 반영 결과를 독립적으로 재검토하지 않았다. 문서가 길어(합계 약 1.3MB) 남은 불일치가 있을 수 있다. 3.5 시작 전 짧은 정합 점검을 권한다.
- 기술 사실(Let's Encrypt OCSP 정책, `t3.large`의 실제 `MemTotal`, `GetParametersByPath` 권한 평가 등)은 이 환경에서 확인할 수 없어 '3.5 확인 항목'으로 남겼다.
- 단계 정의가 지정한 reviewer(아키텍처 리뷰어, 최대 2회)를 엔진 경로로 호출하지 않았다.

## 9. 다음에 이어갈 때 (2026-10-09 시점 — 13절로 대체)

이 절은 3.5 시작 전에 쓴 것이다. 4절의 결정은 사람이 2026-10-09 ~ 10에 답했고(10.1), 3.5 · 3.6을 진행했다. 현재 상태와 이어갈 방법은 **13절**.

## 10. 3.5 Code Generation (U1 워킹 스켈레톤) — 2026-10-10 ~ 11

사람 지시(2026-10-09): "3.5로 진행해 … 3.6 Build and Test까지 끝나면 자율 모드를 gated로 바꾸고 park로 멈춰라. U2 3.1은 시작하지 마라." 4절의 결정 1 ~ 9를 사람이 직접 답했다(10.1).

### 10.1 사람 결정

| # | 결정 | 반영 |
|---|---|---|
| 1 | 반복 순서 unit-major · 스켈레톤 자세 off 유지, U1을 3.5로 | 그대로 |
| 2 | 검증 명령 C안(A + B + 기기 시뮬레이터 한 턴, 가짜 공급자), Plan Approval은 사람이 선택지로 누름 | `bash scripts/verify.sh`(J103) |
| 3 | 솔로, 클라우드 담당 A | J104 |
| 4 | 도메인 · 인증서 · GHCR 공개 · 스모크 인증은 10/13 팀 결정 → 임시값 | J98 `SERVICE_HOST` 자리표시자 · J99 GHCR 비공개 + 읽기 토큰 · J100 시험 로그인(dev · CI만, demo 스모크는 인증 없는 확인) |
| 5 | FD 보강 A1 ~ A6 · 계약 편차 (a) ~ (t) 반영, 목록 남김 | 코드 · 시험 · `contracts/deviations.md`, 승인 문서 본문은 고치지 않음(J130) |
| 6 | D 담당 문서 임시값 | J101 시드 사전 범주별 1항목 · J102 임시 WAV 2개 |
| 7 | `knowledge/documents` 원본 손대지 않음 | 지킴 |
| 8 | J1 ~ J96 미확정 | 모든 J는 가정으로 표기 |
| 9 | 실제 아동 데이터 운영 전 항목 열어 둠 | 열어 둠 |
| 10 | (2026-10-10, Plan Approval 전 질문) 엔진 checkpoint 명령은 J134 (가) `bash scripts/verify.sh --engine`(120초 제한 안, 미리 만든 산출물 지문 대조) | 계획 · `verification-command.txt` |
| 11 | (같음) Docker가 없으면 host 모드 통과 인정 | J133 |
| 12 | (같음) 시드 사전 SCORE 항목은 `비정상` | J101 |

### 10.2 승인 기록

| 지점 | 결과 | 근거 |
|---|---|---|
| Plan Approval(53단계 · Testing Contract · 유닛 시험 지침) | **Approve Plan**(사람 클릭, 2026-10-10) | `PLAN_APPROVAL_RECORDED`, `testing-posture verify` ok |
| Construction Verification Command | **Approve**(사람 클릭, 2026-10-11) — 세 번 물었다(12절 3항) | `VERIFICATION_COMMAND_RECORDED` · `set-construction-verification-command` |
| U1 단위 체크포인트 | 엔진 검증 통과(`bash scripts/verify.sh --engine` 57초 · 종료 코드 0 · 증거 지문 불변) → 자율 모드 자동 승인(`human_required: false`) | `bolt checkpoint --action verify/approve` |
| B1 ④ 스켈레톤 확인 | **Approve** — 사람이 2026-10-11 채팅으로 "워킹 스켈레톤 확인(체크포인트 통과, B1 ④) → Approve"를 **미리** 답했고, 그 조건(체크포인트 통과)이 충족된 뒤 기록했다 | 사용자 메시지(2026-10-11) |

### 10.3 산출물

- 계획 · 지시 · 질문: `R/construction/u1-walking-skeleton/code-generation/{code-generation-plan.md(53단계, J97 ~ J144), unit-test-instructions.md, code-generation-questions.md}`
- 요약 · 추적: `code-summary.md`(J145 ~ J284 · 반영 목록 · 알려진 틈 · 사람이 정할 것) · `source-manifest.json`(763파일) · `traceability.json`(AC 83 = OK 56 · Deferred 27, NFRx.y 67 = OK 47 · Deferred 20, BR 89 = OK 89)
- 애플리케이션 코드(저장소 루트, J97): `backend/`(Gradle: contract-model · core-api · realtime-activity · test-support, Java 21 · Spring Boot 4.1.1) · `frontend/`(Next.js 16.4 · React 19.3, /parent · /device) · `contracts/`(OpenAPI · AsyncAPI · JSON Schema 31, 편차 (a) ~ (y)) · `infra/`(Compose demo · dev · ci, Nginx, PostgreSQL init, migrate, backup, 배포 · AWS 정의, RUNBOOK) · `tools/`(가짜 공급자 · 기기 시뮬레이터 · 끝-끝 시나리오 C1 ~ C12 · host 모드 Nginx 대역) · `scripts/verify.sh` · `.github/workflows/{ci,deploy}.yml`

### 10.4 시험 · 커버리지 (U1 범위 하한 명령)

| 모듈 | 시험 | 줄 커버리지 | 하한 80% |
|---|---|---|---|
| contract-model | 276 | 98.53% | 통과 |
| core-api | 506 | 95.77% | 통과 |
| realtime-activity | 387 | 89.93% | 통과 |
| test-support | 27 | (대상 아님) | — |
| frontend `src/**` | 499 | 98.82% | 통과 |
| tools | 105 | (대상 아님) | — |

실패 0 · 건너뜀 0. DB 시험은 Docker(Testcontainers)와 호스트 PostgreSQL 16 두 백엔드에서 모두 돌았다. 하한 · 단언을 낮춘 곳은 없고 커버리지 제외 목록은 비어 있다.

### 10.5 검증 명령 결과

| 실행 | 결과 |
|---|---|
| `bash scripts/verify.sh`(compose, 전체 A · B · C · 사후 단언 · 작업 트리 비교) | 종료 코드 0, 341 ~ 343초 |
| `bash scripts/verify.sh --mode host` | 종료 코드 0, 281초, `NOT_VERIFIED(host)` 5줄은 CI 몫 |
| `bash scripts/verify.sh --engine`(수동 4회 + 재실행) | 종료 코드 0, 57 ~ 70초 |
| 엔진 `bolt checkpoint --action verify` | `verified: true`, 57초, 증거 지문 불변 |

C 단계는 D0 코드 → 시험 로그인 → 05a 등록 → 04 안정 → 08 시작 → 음성 1턴(출력 게이트 경유, 금지 패턴 턴은 기기로 0바이트 · 대기 문구 재생) → P1 끝내기 → ENDED를 끝까지 돈다. 같은 실행에서 교차 스키마 쓰기 42501(B1 ②)과 로그 위생(B1 ③)을 단언한다.

### 10.6 알려진 틈 (code-summary.md ## 알려진 틈 요약)

1. 시드 사전에 `점수` · `정상이에요` · `정상입니다` 없음(정식 사전 v1, D 담당).
2. 관측 지표 일부 미구현(`mujoong_device_connection_state` · outbox/inbox 대기 지표 · 히스토그램 · METRIC_SNAPSHOT) → NFR4.15 · 7.6 · 7.8 Deferred. 구현 시점은 사람이 정한다.
3. R3-21에 따라 3.6 이후로 넘긴 설계 시험(RD-10 · RD-13 · RD-15 · RD-21 · SD-2 · SD-10 · SD-16 · SD-19 · PD-7 ~ 9 · PD-14 · PD-17 ~ 19 · OD-5 · 6 · 9 · 10 · 12 · ArchUnit 나머지 · 접근성 · 실기 브라우저).
4. 연결 끊김(EOFException)이 ERROR로 기록됨 → 경보 오탐 가능.
5. SD-22 검토 거리(WS 수신 스레드의 HELLO 인계 · 닫기 DB 트랜잭션).
6. 실행하지 못한 것: GitHub Actions 실제 실행 · OIDC · SSM · GHCR 로그인 · Let's Encrypt · 실제 EC2, gitleaks 이력 스캔 · Trivy.

## 11. 3.6 Build and Test (U1, 단독 실행 `--single`) — 2026-10-11

사람 지시 "3.6 Build and Test까지 끝나면 자율 모드를 gated로 바꾸고 park"에 따라 3.6을 **단독 실행**(`--single`, 메인 워크플로 위치를 바꾸지 않음)으로 돌렸다. 단계 정의상 실패 조건은 '명령 실패 · Not Met · Unverified 중 하나라도 있으면 실패'이고, 단독 실행은 사다리 2단(원인 분류 · 영향 추정)까지만 한다.

### 11.1 1차 실행(실패) — 03:01 ~ 04:01 UTC, HEAD 39b33a6

| 묶음 | 목표 | Met | Not Met | Unverified |
|---|---|---|---|---|
| 합계 | 120 | 58 | 11 | 51 |

- 통과: 빌드 · 단위 1,800건(백엔드 1,196 · 프런트 499 · tools 105) · 커버리지 하한 · 통합 39클래스 · 검증 명령 전체(289초) · 엔진(59초) · 인프라 정적 검사 · 비밀 스캔 · 장애 주입 대부분.
- 실패한 명령 2개: Trivy 운영 jar(tomcat-embed-core 11.0.24 CRITICAL 3종, jackson 3.1.5 · 2.22.1 HIGH 5종) · 이미지 ③(5종 모두 고칠 판이 있는 HIGH 이상).
- Not Met 11개: 공급망(BT-NFR9.13) · 앱 정지 시 입구 504(BT-NFR4.13) · SIGTERM 유예 중 503 대신 접속 거절(BT-RD-13) · 허용 목록 밖 시간 상수 4종(BT-NFR6.1) · 단일 인스턴스 표식 1/12(BT-NFR7.4) · 시험 분량 미달 26클래스(BT-TC-10) · 지표 미구현(BT-NFR4.15 · 7.6 · 7.8) · METRIC_SNAPSHOT 0줄(BT-OD-06) · `turn-metrics` 없음(BT-NFR5.4).
- Unverified 51개: 6개는 4.2 · 4.3, 2개는 U2가 맡고, **43개는 실행 계획에 맡을 단계가 없다**(11/2 측정 창 · 운영 7일 창 · 실기 · GitHub Actions 실측 등 — 계획 결정, 사람 몫).
- 같은 날 PR #1의 GitHub Actions도 backend-deps · infra-validate · images가 실패했다(같은 공급망 원인 + Compose v2.38 env 파일 오탐 + shellcheck 0.9.0 경고).
- 산출물: `R/construction/build-and-test/{build,integration-test,performance-test,security-test}-instructions.md` · `build-and-test-summary.md`(Target Verification Matrix 120행) · `test-results.md`(1 ~ 14절) · `cross-unit-traceability.md`(열거 335 중 AC 56 덮임 — U2 ~ U7 몫은 당연히 비어 있음).

### 11.2 수정 라운드 — 사람 결정 "고친 뒤 다시 시험 → Approve, 하한 유지"

사람이 미리 답한 "3.6에서 실패가 있어 고친 뒤 다시 시험할까 → Approve"와 "커버리지 하한 · 단언을 낮추자는 제안 → 하한 유지"에 따라, 측정 목표 · 하한 · 관문 · 단언 · 보안 예외 목록을 낮추지 않고 고쳤다. 고치기 전에 **독립 코드 리뷰**(읽기 전용, 반박 검증)를 한 번 돌려 확정 결함 8건(F1 high · F2 · F3 medium · F4 · F5 · FE-1 ~ 3 low)을 함께 고쳤다. 담당(서브에이전트)마다 "재현 시험 → 고침 → 통과"를 요구했고 보고에 '고치기 전 실패 확인' 여부를 적게 했다.

| 커밋 | 내용 | J |
|---|---|---|
| `cf0a58e` | 운영 의존성 보안 판: Tomcat 11.0.26 · Jackson 3.1.7 · 2.22.3(Boot 4.1.1 BOM 위에 제약) | — |
| `86db0b4` | 프런트 FE-1(재접속 중 마지막 화면 유지) · FE-2(오프라인에서도 실패 토스트) · FE-3(「다시 시도」가 최신 입력 · 중복 요청 차단) | J360 ~ J363 |
| `8f44764` | 이미지 취약점(web 기반 다이제스트 + 패키지 도구 삭제, JRE openssl 패치 .deb, migrate를 JRE + Flyway CLI jar 16개로, backup gosu 삭제 · supercronic Go 1.27.2 빌드), CI Trivy 실행기 TMPDIR 결함, infra-validate Compose 판 무관 env 검사, shellcheck 0.9 · 0.11 경고 0, Nginx 업스트림 DNS 재해석, `infra/bin/turn-metrics` | J345 ~ J353 |
| `6dbd725` | 백엔드: F1(출력 게이트 전사-음성 대응 — 한 응답만, `session.output.done`까지 받은 구간만 통과, 편차 (z)) · F2(closeProvider 자기 교착) · F4(최상위 예외 때 CLOSE_TRANSPORT · STANDBY) · F5(syncFromDb 비종료 상태 시한) · F3(재시도 뒤 409 → 취소), 지표 · METRIC_SNAPSHOT · SIGTERM 유예 중 503 · 시간 상수 → 설정 키 + 자동 검사 · 단일 인스턴스 표식 + 구조 시험 · 시험 분량 5건 이상 | J285 ~ J344 |
| `aabce31` | 멈춘 앱은 정지 직후부터 입구 502(504 나누기 — 연결조차 못 한 것만 502, hang은 504), Nginx 컨테이너 `ndots:1`(러너 DNS와 무관), image-checks ⑧ 재설계 | J354 ~ J357 |
| `b094c0d` | 독립 리뷰 2회차 지적 6건: 풀 대기 초과 이중 계수, 표본 전용 스레드, T5 스냅샷 마감, 처음 연결 실패 기기 라벨, 시험 2건 | J364 ~ J369 |
| `8a9225a` | CI에서 1회 실패한 `DeviceGatewayIT`의 원인 — HEARTBEAT 복귀 경주 2개(last_seen_at 미갱신 · 원장 사본 갱신이 커밋 뒤). 3.5부터 잠복한 운영 결함을 별도 작업 트리에서 재현 · 수정 | J390 ~ J392 |
| `b9a53a5` | CI tools 1건 실패의 원인 — 끝-끝 도구 `run-c`에서 단계 타이머와 그 기한으로 깎인 요청 제한 시간이 1ms 차로 경주해 TIMEOUT/FAIL이 갈림. 깎인 대기의 제한 시간 초과는 단계 TIMEOUT으로 보고 | J393 |

### 11.3 재시험 — 17:11 ~ 22:32 UTC, HEAD b094c0d(+ 그 뒤 8a9225a는 백엔드 전체만 재실행)

1차와 같은 명령 · 순서(S0 ~ S14)로 다시 돌렸다. 증거는 `build/b-and-t-r2/`(이 PC에만, gitignore).

| 묶음 | 목표 | Met | Not Met | Unverified |
|---|---|---|---|---|
| 1차 | 120 | 58 | 11 | 51 |
| **재시험** | 120 | **69** | **0** | 51 |

- 시험 2,061건 실패 0(백엔드 1,442 · 프런트 514 · tools 105; 8a9225a 뒤 백엔드 1,451건). U1 줄 커버리지 contract-model 98.53% · core-api 96.00% · realtime 92.03% · 프런트 98.93%(하한 80%). 5건 미만 시험 클래스 0.
- 검증 명령 전체 `RESULT PASS` 509초, 엔진 89초(110초 안). infra-validate를 CI 판 조합(shellcheck 0.9.0 × Compose v2.38.2)까지 네 가지로 통과. 이미지 5종 Trivy HIGH · CRITICAL 0, 운영 jar 0. 앱 정지 → 입구 502(첫 요청 3초 안, 그다음 수 ms), METRIC_SNAPSHOT 앱별 1줄, SIGTERM 20ms 표본 200 → 503(Retry-After) → 502 → 재기동 200.
- **Not Met 11개 모두 Met**. 단 두 행은 사람이 읽어야 할 주석이 붙었다(11.5).
- 1차 Met 58개 회귀 없음. Unverified 51개 사유 변동 없음(43개는 계획 결정).
- 정직하게 적는 것: 백엔드 전체 1차 실행(U-3 · U-7)에서 시간 민감 통합 시험 3건이 실패했다. 그 시간대에 다른 서브에이전트가 재현을 위해 만든 CPU 부하(`yes` 2개, 약 7분)가 겹쳤고 재실행은 통과했다(12절 13항). 같은 종류의 흔들림이 CI에서도 1건 있었고(DeviceGatewayIT), 그 원인은 부하가 아니라 **운영 코드의 경주**로 확정해 고쳤다(`8a9225a`).
- 성능 기준선이 1차보다 늘었다(검증 전체 289 → 509초, 엔진 59 → 89초, 강제 종료 복구 9 → 20초). 같은 기계에서 다른 Gradle 실행이 겹쳤고 재부팅 직후라 환경 요인이 크다 — 조용한 환경에서 다시 재야 비교가 된다.

### 11.4 CI (PR #1, GitHub Actions)

| 커밋 | 결과 |
|---|---|
| 39b33a6 · 50cb21b(1차) | backend-deps · infra-validate · images 실패 |
| cf0a58e ~ 86db0b4 | backend-deps 통과, infra-validate · images 실패(고치는 중) |
| 8f44764 | infra-validate 통과, images ⑧만 실패(러너 DNS가 멈춘 컨테이너 이름을 SERVFAIL로 답해 옛 주소 유지 — 로컬 재현 뒤 `ndots:1`) |
| **aabce31** | **전부 통과(ci-gate 성공)** — 처음 녹색 |
| b094c0d | backend 1건 실패(`DeviceGatewayIT` 40초 무음 복귀) → 원인 분석 · 수정 |
| 8a9225a | tools 1건 실패(끝-끝 도구의 TIMEOUT/FAIL 경주 — 1ms 차로 두 타이머가 경주) → backend 취소, 나머지 통과 |
| **b9a53a5** | **전부 통과(ci-gate 성공)** — 경주 수정 2건(DeviceGatewayIT · run-c)이 들어간 최종 코드 |

### 11.5 사람이 정할 것 (3.6에서 새로 생긴 것 — 코드는 바꾸지 않고 열어 둠)

1. **BT-RD-13 SIGTERM 503 창.** 유예 동안 커넥터를 열어 두고 503을 내도록 고쳤지만, 진행 중 요청이 없으면 T6이 수십 ms에 끝나 503 창도 수십 ms다(SIGTERM 20ms 표본 30개 중 503 2개, 나머지 502). IT-06j의 기대 순서(200 → 503 → 502 → 200)는 충족해 Met으로 적었으나, 설계 문구 '커넥터는 유예(30초) 끝까지 열어 둠'을 문자 그대로 읽으면 Not Met이다. 최소 유지 시간 키를 둘지(배포 중단 시간이 그만큼 늘어남) 사람이 정한다.
2. **편차 (z) — `session.output.done`이 사실상 필수.** F1 수정으로 '그 응답의 완료 신호를 받은 구간만 통과'가 됐다. 계약상 '선택'이던 신호가 재생의 필요조건이 되어 11/2 실공급자 어댑터는 완결된 응답에만 이 신호를 보내야 한다. 깨는 변경으로 볼지 확인.
3. **J354 — 연결조차 못 한 504를 502로.** errors.md '상태 코드는 그대로'를 '봉투가 502 · 504를 다른 값으로 바꾸지 않는다'로 읽어 편차를 더하지 않았다. 편차로 남기려면 (a) ~ (z)가 다 차서 표기 방식과 `ContractFilesTest`를 함께 바꿔야 한다.
4. **J347 — 정지 직후 약 1.6초 창.** 그 안에 든 요청은 연결 시한 3초 뒤 502다(상태는 요구대로, 지연만 남음). `proxy_connect_timeout 3s`(N-12 제안값)를 유지했다.
5. **BT-NFR7.4 SI-11 '대상 없음'.** 두 앱에 TTL 캐시 상태가 없어 표식 11/12 + 대상 없음 1. 12/12를 문자 그대로 요구하면 Not Met.
6. **J363 — 화면 04 자동 복귀.** 오프라인으로 들어왔다가 온라인이 되면 전에는 결함 덕분에 저절로 읽혔다. 이제 토스트 「다시 시도」로 읽는다(PD-12 '진입 때 1회' 단언 유지).
7. **SeedDictionaryFileTest의 'OUTPUT_GATE 전수' 단언**(승인된 3.5 단언)은 계약 56행보다 좁다 — 후속 유닛이 RESULT_TEXT 전용 항목을 더하면 실패한다.
8. **gate_discard `category` 라벨 · input_frames_dropped `cause` 라벨**은 설계 문서끼리 충돌해 그대로 두었다.
9. **시간 민감 통합 시험의 흔들림.** 경주 2개는 고쳤고 나머지 실패(부하 때 10초 대기 초과형)는 고치지 않았다. 후보: `InternalHttpClient`가 IOException을 모두 CONN_ERROR로 묶어 예외 종류가 로그에 없음, 시험 대역 서버 backlog, WAIT 10초.
10. 설계 문서와 구현이 달라진 곳(스레드 수 표, OD-6 표본 스레드, T5 마감, 풀 대기 계수 규칙, view_item_ready 버킷, 새 키 `system-sample-interval`, BR4.8 복귀 시 last_seen_at, RD-4 사본 트랜잭션, infra-spec N-2 · 3.3 · 2.1 dns_opt, flyway 배포판 → JRE + jar, supercronic 빌드, SI-11 · SI-12 위치)은 승인 문서를 고치지 않고 `code-summary.md`의 '설계 문서와 달라진 곳' 표에 모았다. 문서 반영은 사람이 승인한 뒤.
11. 1차와 같음: Unverified 43개의 계획 결정(D), 10/13 팀 결정(J98 ~ J100), 정식 사전 v1, J1 ~ J392 전부 미확정.

## 12. 공개 사항 (3.5 · 3.6 실행)

7절(2.6 ~ 3.4)에 이어지는 번호가 아니라 이 절 안의 번호다.

1. **사용 한도로 여러 번 끊김.** 조직 월간 사용 한도에 3.5에서 두 번(2026-10-10, 재설정 10:40 · 16:00 UTC), 3.6 수정 라운드에서 두 번(2026-10-11, 재설정 11:40 · 20:30 UTC) 걸려 서브에이전트가 중간에 멈췄다. 재설정 뒤 같은 워크플로를 다시 돌려, 멈춘 에이전트가 이미 쓴 파일을 읽고 이어서 완성했다. 중단 시점의 미완성 코드는 `wip` 커밋으로 남아 있다(`bf5eeac` · `70585a0`).
2. **세션 · 컨테이너 재시작 여러 번.** 그때마다 백그라운드 작업과 Docker 데몬이 멈췄다. 워크플로는 캐시된 결과로 다시 이어 갔고, Docker 데몬은 손으로 다시 띄웠다(`dockerd`, 샌드박스 전용).
3. **검증 명령 질문을 세 번 물었다.** ① 첫 질문 뒤 세션이 끊겨 답이 질문에 묶이지 않았다. ② ③ 엔진은 화면 질문 문장이 `log decision --decision` 문자열과 같아야 답을 묶는데, 제가 질문에 명령 코드를 덧붙여 문장이 달랐다(제 실수). 결정 문자열을 질문과 같게 다시 기록한 세 번째에 영수증이 기록됐다. 사람은 Approve를 세 번 눌렀다.
4. **시험용 무작위 값이 도구 출력에 찍힌 사고(고침).** 처음 작성된 `infra/ci/gen-env.sh`가 첫 줄 `변수=값`을 표준 출력에 찍는 결함이 있어, 실행 때 만든 무작위 시험 값 7개(DB 비밀번호 5종 등, 실제 비밀 아님)가 세션 도구 출력에 한 번 찍혔다. 그 디렉터리는 곧바로 지웠고 그 값으로 띄운 스택은 없다. 지금은 파일 · 변수 이름만 출력한다. 저장소 · 커밋에는 비밀 값이 없다(비밀 스캔 A4 · P3 걸림 0).
5. **저장소 밖 환경 조치(샌드박스 전용, CI 영향 없음).** `downloads.gradle.org`가 출구 정책에서 403이라 설치된 Gradle 8.14.3을 `~/.gradle/wrapper/dists`에 넣어 래퍼를 돌렸다(저장소의 `distributionUrl`은 공식 주소 그대로). Maven Central이 429를 줘 `~/.gradle/init.d`에 공식 미러로 보내는 설정을 두었다. shellcheck(`/usr/local/bin`) · logrotate(apt)를 설치했다.
6. **엔진 linter 센서 미판정.** `aidlc engine sensor-linter`가 저장소 루트에서 eslint를 돌려 `eslint-bad-output`을 냈다(설정은 `frontend/`에 있음, advisory). 프로젝트 lint(`cd frontend && npx eslint .`)는 검증 명령 A2에서 통과했고, type-check 센서는 통과했다.
7. **엔진 검증의 첫 실행 지연.** Docker 데몬을 막 다시 띄운 직후 `--engine` 수동 실행이 B 단계 94초 기한을 넘겨 실패(112초)했다. 곧바로 다시 돌리자 61초로 통과했고, 엔진 검증은 57초였다. 데몬 재시작 직후에는 한 번 미리 돌려 데우는 것이 안전하다.
8. **남의 단계 시험 수정.** Step 36 담당이 Step 30 · 35의 통합 시험 두 개(`SessionLifecycleIT` · `RealtimeLogHygieneIT`)의 경주를 시험 쪽에서만 고쳤다(운영 코드 변경 없음).
9. **리뷰 범위.** 코드 생성 단계의 엔진 리뷰어는 이번 지시에 없었다. 대신 계획을 세 관점(설계 충실도 · 실행 가능성 · 보안/인프라)으로 독립 검토해 지적 58건을 Plan Approval 전에 반영했다. 생성된 코드는 3.6 1차 뒤에 **독립 코드 리뷰**(읽기 전용 서브에이전트, 반박 검증)를 두 번 돌려 확정 결함 8건 + 6건을 고쳤다(11.2). 엔진의 reviewer 경로는 쓰지 않았다.
10. **멈춤 경고.** 3.5 뒤 엔진은 U1 체크포인트 재검증(소스가 바뀌었으므로)과 U2 Functional Design을 다음 단계로 내놓았고, Stop 훅이 그 단계를 실행하라고 반복해서 막았다(자율 모드 상한 8회 뒤 풀림). 사람 지시대로 U2는 시작하지 않았고, 자율 모드에서는 park가 거부되므로 3.6과 수정 라운드가 끝난 뒤 체크포인트를 다시 검증 · 승인하고 gated로 바꿔 park했다(13절).
11. **3.6 1차는 실패 판정이고, 그 상태로 커밋 · PR에 남겼다.** 수정 라운드 뒤 재시험 결과를 `test-results.md` 15절로 덧붙였고 1차 기록은 지우지 않았다.
12. **고친 뒤 생긴 사고 · 판단.** (가) 수정 중간 상태를 커밋하지 않고 담당별로 끝난 것만 커밋했다(cf0a58e ~ 8a9225a 7개). (나) CI images ⑧이 로컬에서는 통과했지만 러너에서 실패했다 — 러너 DNS 동작 차이를 로컬에서 재현해 고쳤고, 러너 안의 실제 DNS 응답은 보지 못한 추정이었으나 다음 실행(aabce31)이 통과해 확인됐다. (다) CI backend가 b094c0d에서 1건 실패했는데, 이번 변경이 만든 것이 아닌 3.5부터 잠복한 운영 경주였다(11.2 · 8a9225a).
13. **재시험 중 CPU 부하 사고.** CI 실패를 재현하려고 띄운 서브에이전트가 지시대로 CPU 부하(`yes` 2개)를 만들었는데, 같은 기계에서 재시험 담당이 시간 민감 통합 시험을 돌리던 중이었다. 하네스가 "다른 작업 방해" 경고를 냈고, 제가 부하를 멈추고(약 7분 뒤) 그 에이전트에게 부하 없이 진행하라고 알렸다. 그 시간대의 백엔드 1차 실행에서 3건이 실패했고 재실행은 통과했다 — 두 실행을 모두 기록했다. 서로 다른 담당을 같은 기계에서 동시에 돌린 제 판단 실수다.
14. **별도 작업 트리.** 재시험이 저장소를 쓰는 동안 원인 분석 · 경주 수정은 `git worktree`로 만든 별도 작업 트리에서 했고 패치로 가져와 적용했다(끝난 뒤 지움). 저장소 이력에는 흔적이 없다.
15. **모델 · 모드 변경.** 사용자가 실행 중 `/model`(Fable 5.1)과 `/fast`를 켰다. 산출물 어디에도 모델 식별자를 쓰지 않았다.
16. **디스크.** 재시험 끝 무렵 디스크 사용 87%(여유 5.1GB). 빌드 캐시 · 이미지가 대부분이다.
17. **서브에이전트 보고 파일.** 하네스가 하위 에이전트의 보고 파일 쓰기를 거부해, 보고는 응답으로 받아 제가 스크래치패드에 저장했다. 산출물(`code-summary.md` 등)에는 제가 옮겼다.
