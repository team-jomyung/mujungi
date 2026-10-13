# 무중 작업 유닛 (Units Generation 2.7)

> Units Generation(2.7) 산출물 · 깊이 Standard · 2026-10-09 (클라우드 세션, 팀장 대리 판단)
> 입력: `components.md`(컴포넌트 26개 · 의존 · 엔터티 소유, 승인), `decisions.md`(ADR-011~019), `requirements.md`(FR · NFR · 우선순위 Q6), `stories.md`(US1.1~US6.6), 질문 파일 `units-generation-questions.md`(Q1~Q8), 스코프 mujoong-mvp(skeleton: on), 70_WBS_v0.5 3.x.
> 이 문서는 유닛이 **무엇인지**만 적는다. 의존 그래프는 `unit-of-work-dependency.md`, 스토리 배정은 `unit-of-work-story-map.md`. 만드는 순서 · 볼트 · 담당 배정은 Delivery Planning(2.9)이 정한다.

## 유닛 목록

| Unit ID | Directory | 이름 | kind | 크기 | 배포 | 완성하는 컴포넌트 | 주 화면 | WBS · 담당 후보 |
|---|---|---|---|---|---|---|---|---|
| U1 | u1-walking-skeleton | 워킹 스켈레톤 | service | L | 공유 | CoreMessaging · RealtimeMessaging · DeviceRegistry (+ 실행 골격) | D0 · 05a · 04(기기 구역) · 08 · 12 · 17 최소 | 3.1 · 3.3 일부 · 2.2 골격 · 팀 전원(B 주도) |
| U2 | u2-guardian-account | 보호자 계정 | service | M | 공유 | GuardianIdentity · ConsentPolicy · ChildProfile · ApprovedContentCatalog · AccountDeletion(지휘 틀) | 01 · 02 · 02-1 · P5 · 03 · 04 · SET0 · P7 · 05 · SET-01 | 3.2(계정) · A |
| U3 | u3-voice-conversation | 음성 대화 | service | L | 공유 | DeviceGateway · VoiceRelay · OutputGate · ConversationLog · DialogPolicy · DeviceWeb | T19 | 3.3 연결 · 음성 · B · E |
| U4 | u4-activity-session | 활동 세션 | service | XL | 공유 | ActivityCoordinator · SessionLifecycle · ActivityRecord | 08 · 12 · P1 · P2 · P3 · 17 · 04(지금 활동) | 3.1 공통 활동 · B · C |
| U5 | u5-practice | 연습 | service | L | 공유 | PracticeGoal · PracticeProtocol · ResponseEvaluator · PragmaticsKnowledge · TextAiGateway | 18 · 12(연습) | 3.4 AI · 목표 · 연습 · C · D |
| U6 | u6-results-report | 결과와 리포트 | service | XL | 공유 | ResultPipeline · Report | 26 · 27 · 28 · 30 · R-PDF · 17(정리 상태) | 3.2(기록) · 3.4 결과 · D · E |
| U7 | u7-web-push | 웹 푸시 | service | S | 공유 | Notification | (알림 진입) | 신설 행(점검표 10) · A |

- **kind**: 모두 `service`다. 각 유닛이 화면(Next.js) · Core · Realtime을 함께 갖는 세로 조각이어서 Construction의 설계 산출물(기능 설계 · NFR · 인프라)이 모두 적용된다. `ui` · `spec` · `library`만으로 된 유닛은 없다.
- **배포(공유)**: 모든 유닛이 같은 세 실행 단위(Next.js 한 앱 · Core API Spring · Realtime Activity Spring)에 들어가 EC2 1대 Compose로 함께 올라간다(K2 · ADR-019). 유닛은 설계 · 개발 · 시험 단위이지 배포 단위가 아니다.
- **ParentWeb**은 한 컴포넌트지만 화면별로 유닛에 나뉜다(위 '주 화면' 열). 화면 틀은 그 화면의 주 스토리를 가진 유닛이 만든다(J18).

## 유닛별 정의

### U1 · u1-walking-skeleton — 워킹 스켈레톤

- **무엇**: 끝까지 실제로 도는 가장 얇은 조각. 개발용 시험 로그인(시드된 보호자 · 동의 · 아이) → 아이 기기 웹 D0가 서버 발급 8자리 코드를 받음 → 보호자 05a 코드 등록 → 기기 토큰으로 WSS 접속 → 04 대화 기기 구역 '안정' → 08 「자유대화 시작」 → 음성 1턴(아이 한 마디 → GPT-Live → 출력 게이트 → 기기 재생 · 말풍선) → P1 끝내기 → 승인 마무리 인사 → ENDED(17 최소).
- **완성**: CoreMessaging · RealtimeMessaging(Outbox/Inbox 양쪽, ADR-010 규칙 전부), DeviceRegistry(코드 발급 · 등록 · 토큰 · 토큰 확인 내부 HTTP · 연결 상태 사본 · 재등록 J13), DeviceWeb의 D0, 실행 골격(저장소 구조 · Compose · Nginx HTTPS/WSS · Flyway core · realtime 스키마 · 앱별 DB 권한 · 공통 오류 · 로그에 전사 원문 남기지 않기).
- **최소 경로만**(뒤 유닛이 완성): GuardianIdentity(시험 로그인, dev 프로필 한정 → U2), ParentWeb(04 기기 구역 · 05a · 08 단추 · 12 상태 줄 · 끝내기 · 17 종료 → U2 · U4 · U6), ActivityCoordinator(자유대화 시작 · 끝내기 · 진행 조회 중계 → U4), SessionLifecycle(REQUESTED → PREPARING → DEVICE_ACK → ACTIVE → CLOSING → ENDED, 사유 GUARDIAN_STOP만 → U4), DeviceGateway(토큰 접속 · heartbeat · 연결/끊김 · 명령 → U3), VoiceRelay(GPT-Live 1턴 중계 → U3), OutputGate(승인 경로 · 금지 표현 최소 규칙 → U3), ConversationLog(전사 저장 · since 조회 → U3), DeviceWeb T19(켜기 · 재생 → U3), ApprovedContentCatalog(마무리 인사 1건 시드 → U2).
- **책임**: 세 실행 단위가 실제로 붙어 한 번 끝까지 돈다는 증명과, 모든 유닛이 기댈 골격 · 메시징 · 기기 등록.
- **구현 메모 · 제약**: 시험 로그인은 운영 프로필에서 꺼져 있어야 한다(NFR9). GPT-Live 키는 서버 환경 변수로만(기기 · 저장소에 없음). 검증 명령은 Delivery Planning에서 정하고 Code Generation 전에 사람이 승인한다(실제 GPT-Live 호출 포함 여부 포함). 원음 저장 · 참여 확인 · 시간 규칙 · 공백은 넣지 않는다.

### U2 · u2-guardian-account — 보호자 계정

- **무엇**: 처음 한 번(01 → 02 → 03 → 04)과 홈 · 설정 · 기기 화면 · 종료 표현, 계정 삭제 접수.
- **완성**: GuardianIdentity(구글 OAuth · Spring Session JDBC · CSRF · 만료 401 → 01 · 접근 범위 · DELETION_REQUESTED 차단), ConsentPolicy(약관 버전 · 4항목 · OUTDATED · 시작 게이트 응답), ChildProfile(1:1 · 검증 · 종료 표현 2~20자 · 5개), ApprovedContentCatalog(약관 본문 · 시작 안내 · 재안내 · 공백 경고 · 기회 트리거 · 마무리 인사 버전 관리와 시드), AccountDeletion의 지휘 틀(접수 · 멱등 · 즉시 차단 · 로그아웃 · 삭제 참가자 등록 · U1 · U2 데이터 삭제 · Realtime DeletionRequested/Completed).
- **화면**: 01 · 02 · 02-1 · P5 · 03 · 04(아이 · 대화 기기 구역, 탭 잠금) · SET0 · P7 · 05(두 줄 + 판정 카드) · SET-01.
- **확장(다른 유닛 컴포넌트)**: DeviceRegistry의 05 · 04 표시 합산 응답(U1 완성분 위에).
- **구현 메모 · 제약**: 04 '지금 활동' 구역은 틀만 두고 내용은 U4(J18). 05 네트워크 줄의 판정 값은 U3가 보고하는 값을 그대로 보인다. 계정 삭제 완료 조건은 뒤 유닛 참가자가 다 붙어야 전 데이터 0건이 된다(J19).

### U3 · u3-voice-conversation — 음성 대화

- **무엇**: 기기 연결과 음성 경로 전체. 기기 WSS · 연결 5단계 판정 · 명령, GPT-Live 중계 · 원음 저장, 출력 게이트, 전사 조립, 대화 역할 지시 · 참여 · 종료 의도 판단, 아이 기기 T19.
- **완성**: DeviceGateway(안정 · 불안정 · 끊김 · 응답 없음 · 꺼짐 판정, 상태 보고, 명령 ACK · playbackMark, 같은 토큰 동시 연결 처리), VoiceRelay(GPT-Live 세션 · 지시 · 끼어들기 취소 · 원음 파일 저장 · 공급자 끊김 감지), OutputGate(구간 분할 · 전사 대응 · 금지 표현 · 역할 규칙 · 상태 · 최신성 · 폐기 · 대체 안내 · 지시 교정), ConversationLog(조립 · 순번 · 불확실 표시 · 승인된 AI 말 표시), DialogPolicy(역할 지식 분리 지시문 · 참여 판단 · 종료 의도 · 공백 경고 문구 결정), DeviceWeb(T19 상태 8가지 · 켜기 · 끄기 · Wake Lock · 재연결 · ?debug=1).
- **책임**: 아이에게 나가는 모든 소리가 승인된 것만이라는 보장(FR7.8 · NFR1)과 연결 상태의 원장.
- **구현 메모 · 제약**: 원음은 동의된 세션만 저장, 저장 위치는 3.4(OQ5). 금지 표현 사전 v1은 AI 입출력 계약 산출물(OQ15). 끼어들기 재생 중단 0.5초는 기기 쪽 로컬 정지(NFR5). 자기 데이터 삭제 참가자(전사 · 원음 파일 · 연결 기록)를 완료 조건에 넣는다.

### U4 · u4-activity-session — 활동 세션

- **무엇**: 자유대화 · 연습 공통 세션의 시작부터 끝, 그리고 끝난 기록을 Core로 넘기기까지.
- **완성**: ActivityCoordinator(시작 조건 · 거절 사유 5종 · 08 배경 · 세션 패키지 · 끝내기 전달 · 진행 조회 중계 · 세션 사본), SessionLifecycle(상태 11 · 사유 8 · 시간 규칙 NFR6 · B5 · B9~B11 · 마무리 인사 · 재시작 시 FAILED · 결과 입력 스냅숏 · SessionRecordCaptured), ActivityRecord(스냅숏 수신 · 저장 · ActivityRecorded · 26/27 대화 기록 조회 API).
- **화면**: 08 · 12(자유대화 · 공통 상태 · 말풍선 · 끊김 점선) · P1 · P2 · P3 · 17(마무리 중 · 종료 사유 · 정리 중 기본) · 04 지금 활동 구역.
- **구현 메모 · 제약**: 연습 경로(연습 시작 · 연습 단계 표시)는 U5가 이 컴포넌트들을 확장해 붙인다(J17). 17의 정리 끝 · 정리 실패와 27 화면은 U6. 자기 데이터 삭제 참가자(세션 · 세션 사건 · 스냅숏 · 활동 기록 · 08 배경)와 진행 세션 CONSENT_WITHDRAWN 종료를 완료 조건에 넣는다.

### U5 · u5-practice — 연습

- **무엇**: 18 내 연습 목표와 연습 세션(2안 + 제브 판정), 그리고 연습이 기대는 AI 기반(행동 정의 카탈로그 · 화용 지식 · 텍스트 AI 출입구).
- **완성**: PragmaticsKnowledge(행동 정의 버전 · 생활 팁 목록 · Neo4j 조회 · 초기 시드), TextAiGateway(최소 정보 · 금지 표현 검사 · 버전 기록 · 재시도), PracticeGoal(담은 목표 · 즐겨찾기 · 순서 · 소프트 삭제/복구 · 후보 · 근거 저장 · 상황 생성 · 확인 · 재생성 1회 · 노출 이력), PracticeProtocol(가중 랜덤 순서 · 단계 · 도움 동의 · Cue 요청 · 반응 분리 저장 · 다음 목표 상황 요청), ResponseEvaluator(제브 판정 · 5초 + 재시도 1회 · uncertain).
- **화면**: 18 · 12 연습 머리(n/N · 단계 표시).
- **확장**: ActivityCoordinator(연습 시작 · SCENARIO_GEN_FAILED), SessionLifecycle(연습 경로 · 목표 완료 → 다음 목표 · 마지막 → 종료), OutputGate(단계별 출력 규칙), VoiceRelay(상황 역할 · Cue 지시).
- **구현 메모 · 제약**: 판정 기준 본문 · 프롬프트 · 단계 enum은 AI 입출력 계약(WBS 1.7.3, OQ4 · OQ14). 데모 시드에 아이별 담은 목표 2~3개(B22)를 넣어 U6 없이도 연습을 시험한다. 결과 입력 스냅숏의 record.practice에는 반응 · 도움마다 scenarioId를 싣는다(U6 기록용 재판정의 입력, 계약 6절). 자기 데이터 삭제 참가자(후보 · 선택 · 상황 · 세션 목표 · 노출 · 시도 · 반응 · Cue · 판정)를 완료 조건에 넣는다.

### U6 · u6-results-report — 결과와 리포트

- **무엇**: 끝난 활동의 정리(경험 구조화 · 기록용 재판정 · 목표 후보 · 생활 팁 · 결과 문장 · 결과 수준)와 기록 화면, 저장 기록으로 만든 10개 항목 상담용 리포트와 PDF.
- **완성**: ResultPipeline(작업 실행기 SESSION_RESULT · REPORT · 3회 · 재시작 FAILED · 구조화 · 기록용 재판정(장면 판정 저장 · 진행 중 판정과 차이 표시) · 후보 생성 · 의미 검토 · 생활 팁 매칭 · 결과 문장 · 결과 수준 · 결과 버전 · 다시 시도), Report(28 미리보기 · 생성 · 취소 · 10개 항목 조립(집계 · 인용 대조 · 결측 · 고정 템플릿) · 30 확인 · 저장 확인 시트 값 · HTML → PDF · 한글 글꼴 · 고지 · 저장/버림).
- **화면**: 26 · 27(끝난 이유 칸 · 연습 목표별 장면 결과 칩 · 결과 문장 · 제공한 도움 · 세 칸 · 정리 중/실패/자료 부족 · 후보 담기) · 28 · 30(리포트 확인 · 저장 확인 시트 · 저장했어요) · R-PDF · 17 정리 끝/실패.
- **AI 작업**: `contracts/ai-io.md` 6절(공통 지시 · 경험 구조화 · 기록용 재판정 · 결과 문장 · 리포트 항목 · 금지 표현 2단계). 모든 인용은 저장 전사와 글자 단위로 대조한다.
- **구현 메모 · 제약**: 진행 중 판정값 · 재판정 라벨 · 근거 문장 · 보호자 입력 원문은 27 · 30 · PDF 응답에 넣지 않고, 장면 결과는 칩 5종으로만 내보낸다(FR4.3 · A10). 연습의 재판정 입력은 결과 입력 스냅숏의 record.practice(U4b · U5b가 채움)와 PracticeGoal의 상황 대본 · 목표 정의 버전이다. U5b가 붙기 전에는 계약 6절 예시 값으로 만든 대역으로 시험한다. 리포트 60초 · 3회(NFR8). 마이그레이션은 core V5. 자기 데이터 삭제 참가자(작업 · 결과 · 구조화 · 장면 판정 · 리포트 · 항목 · 메타)를 완료 조건에 넣는다.

### U7 · u7-web-push — 웹 푸시

- **무엇**: 음성 대화 장애 · 기기 상태 수신 중단 · 기술 문제 종료 세 사건과 활동 완료의 웹 푸시, 알림 진입 재조회.
- **완성**: Notification(구독 · 문구 4종 · 완료 푸시 자격 판정 · 중복 방지 · 발송 요청 기록 · 서비스 워커).
- **구현 메모 · 제약**: 완료 푸시는 ACTIVE를 거친 세션이 CHILD_STOP · GUARDIAN_STOP · SILENCE로 ENDED(confirmed)가 됐을 때만 한 번 보낸다. 종료 확인 전 · ENDED_UNCONFIRMED · 참여 전 종료 · 기술 문제 종료에는 보내지 않는다(기술 문제 종료는 장애 푸시만). 판정 재료는 SessionStateChanged(상태 · endReason · confirmed · statusVersion)와 ActivityCoordinator 세션 사본의 ACTIVE 도달 여부다. Android Chrome 기준, iOS 보장 없음(NFR10). 알림 진입의 재조회 로직(04 → 12/17/04)은 U4 화면에 이미 있어야 한다(푸시 없이도 재진입에서 동작). 마이그레이션은 core V6. 자기 데이터 삭제 참가자(구독 · 발송 기록).

## 공통 완료 조건 (모든 유닛)

- 자기 컴포넌트가 소유한 엔터티에 대해 **계정 삭제 참가자**(guardianId로 전부 삭제, 파일 포함)를 구현한다(J19).
- 로그 · 오류 메시지 · Outbox 페이로드에 전사 원문 · 원음을 남기지 않는다(NFR9).
- 진행 중 판정값 · 점수 · 진단 표현을 보호자 응답에 넣지 않는다. 기록용 재판정은 27 · 리포트의 장면 결과 칩으로만 드러난다(NFR1 · FR4.3).
- 앱 사이 접점은 Contract Design(2.8)의 계약대로만 쓰고 상대 스키마를 직접 읽거나 쓰지 않는다(ADR-019).

## 준하 제안(가정)

| 번호 | 준하 제안(가정) | 근거 | 확정 단계 |
|---|---|---|---|
| J15 | 유닛은 기능 세로 조각 7개(U1 스켈레톤 · U2 보호자 계정 · U3 음성 대화 · U4 활동 세션 · U5 연습 · U6 결과와 리포트 · U7 웹 푸시), 모두 kind service · 공유 배포. 초안의 '보호자 앱'(A · C · E 묶음)은 순환이 생겨 계정(U2)과 기록 · 리포트(U6)로 나눴다 | Q1 · Q2 · Q7 | Delivery Planning |
| J16 | 스켈레톤 = 시험 로그인 + 시드 동의 · 아이 → 실제 기기 코드 등록 → 04 → 08 → 음성 1턴(게이트 경유) → P1 → ENDED. 구글 로그인 · 참여 확인 · 시간 규칙 · 원음 저장은 뒤 유닛 | Q3 · 스코프 설명 | Delivery Planning(검증 명령) · Code Generation |
| J17 | 컴포넌트마다 완성 유닛 하나, 다른 유닛은 계약을 지키는 확장만(연습 경로는 U5가 ActivityCoordinator · SessionLifecycle을 확장) | Q4 | Contract Design(확장 지점) |
| J18 | 여러 유닛 데이터를 쓰는 화면(04 · 05 · 17 · 27)은 주 스토리 유닛이 틀을 만들고 뒤 유닛이 칸을 채운다 | Q5 | Functional Design |
| J19 | 계정 삭제 지휘 틀은 U2, 각 유닛은 자기 데이터 삭제 참가자를 완료 조건으로 더한다. 웹 푸시는 U7로 뗀다 | Q6 · FR1.6 · FR7.10 | Delivery Planning |
