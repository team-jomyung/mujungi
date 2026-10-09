# Functional Design — U1 u1-walking-skeleton — 질문 파일

> 무중(Mujoong) · 깊이 Standard · 2026-10-09 (클라우드 세션)
> 입력: `inception/units-generation/unit-of-work.md`(U1 정의), `unit-of-work-story-map.md`(U1이 닿는 스토리 US1.1 · US1.4 · US2.1 · US2.2 · US3.1 · US3.3 · US6.1 · US6.2, AC 83개), `inception/requirements-analysis/requirements.md`(FR2.1 · FR2.2 · FR2.3 · NFR6 · NFR9), `inception/domain-design/components.md`(DeviceRegistry · CoreMessaging · RealtimeMessaging · DeviceWeb, J1~J14), `inception/contract-design/contract-summary.md`(C2 · C7 · C9 · C11 · T, J20~J31), `inception/delivery-planning/bolt-plan.md`(B1, J32~J41), ERD v0.1 DDL.
>
> **누가 답했나.** 사람 지시(2026-10-09 "반복 순서는 unit-major 그대로 둬. U1의 3.1→3.4까지 진행하고 3.5 앞에서 멈춰. 이어갈 방식을 물으면 자동 진행으로")와 `docs/cloud_handoff.md` 2절에 따라 팀장(준하)의 대리가 채웠다. 대리 판단은 **준하 제안(가정) Jn**(J42부터)으로 `functional-spec.md` 끝 표에 옮겼다. **Mode:** 대리 판단.

## 1. 이미 정해진 것 (확인용)

| 번호 | 정해진 것 | 근거 |
|---|---|---|
| K1 | 기기 등록 상태 3개(UNREGISTERED · REGISTERED · RELEASED)와 연결 상태 5개(OFFLINE · CONNECTED · UNSTABLE · DISCONNECTED · UNRESPONSIVE)는 서로 다른 두 축이다. 등록은 Core DeviceRegistry, 연결은 Realtime DeviceGateway가 주인이고 Core는 보고받은 사본을 쓴다 | components.md 기기 상태 대응표 · J4 |
| K2 | 등록 코드는 8자(2~9와 I · L · O 뺀 A~Z, 31글자) · 24시간 · 2초 확인, 기기 비밀은 HttpOnly 쿠키(`MUJOONG_DEVICE_BOOTSTRAP` 24시간, `MUJOONG_DEVICE` 400일 갱신형), 서버는 해시만 저장 | contract-summary.md 4절 · J22 · J29 |
| K3 | 앱 사이 이벤트는 6종, 영속 전달은 Outbox → HTTP → Inbox, 재시도 1 · 2 · 4 · 8 · 16 · 30초 최대 20회, 수신은 Inbox 커밋 뒤에만 200 | contract-summary.md 6절 · J26 · ADR-010 |
| K4 | 스켈레톤 흐름: 시험 로그인 → D0 코드 → 05a 등록 → WSS → 04 안정 → 08 시작 → 음성 1턴(게이트 경유) → P1 끝내기 → ENDED | unit-of-work.md U1 · J16 |
| K5 | 상태 이름 11개 · 종료 사유 8개 · 시간값은 NFR6 · 시간 계약 그대로 | contract-summary.md 7절 |
| K6 | 사람 결정(2026-10-09): 반복 순서 unit-major 유지 · 이어갈 방식 자동 진행 · 스켈레톤 자세 off | 사용자 지시 · 게이트 선택 |

## 2. 정할 것

### Q1. U1이 '완성'하는 것과 '최소 경로'만 만드는 것의 경계

A. 완성 = DeviceRegistry · CoreMessaging · RealtimeMessaging · DeviceWeb D0 · 실행 골격(스키마 · 역할 · 마이그레이션 · 공통 오류 · 로그 위생). 나머지(GuardianIdentity · ParentWeb · ActivityCoordinator · SessionLifecycle · DeviceGateway · VoiceRelay · OutputGate · ConversationLog · ApprovedContentCatalog · DeviceWeb T19)는 스켈레톤이 지나갈 만큼만 만들고, 그 컴포넌트를 완성하는 유닛이 확장한다. 스토리 AC 중 주 유닛이 완성하는 몫은 추적 파일에서 `Deferred`(대상: 그 유닛)로, U1이 구현하는 몫은 `OK`(대상: 규칙 BRx.y)로 적는다
B. 최소 경로 컴포넌트도 U1이 전부 설계한다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 유닛 정의(`unit-of-work.md`)와 J17 · J18이 이미 '완성 유닛 하나, 나머지는 확장'을 정했다. 설계를 중복하면 U2 ~ U6의 기능 설계와 충돌한다. **J42**.

### Q2. 스켈레톤이 만드는 테이블 범위

A. U1이 마이그레이션(V1)으로 만드는 테이블 = 완성 소유 테이블(core_device · core_outbox_event · core_inbox_event · realtime_outbox_event · realtime_inbox_event · realtime_device_connection) + 스켈레톤 경로가 읽고 쓰는 테이블(core: guardian · consent · child · stop_expression · approved_content(+version) · activity · spring_session(+attributes), realtime: activity_session · session_event · session_stop_expression · transcript). 테이블 모양은 Domain Design 최종 컬럼(J12 반영)으로 한 번에 만들고, 소유 유닛이 뒤에 열 · 색인을 더하기만 한다(더하기만 안전)
B. 지금 쓰는 열만 만들고 뒤 유닛이 바꾼다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 열을 줄여 만들면 뒤 유닛이 깨는 변경(열 이름 변경 · 필수화)을 하게 되어 계약 10절의 '더하기만 안전'과 어긋난다. 테이블을 빈 채로 두는 비용은 작다. **J43**.

### Q3. 시험 로그인과 시드

A. dev · demo 프로필 전용 `DevSeedRunner`(멱등)가 보호자 1명 · 아이 1명 · 현재 정책 버전의 동의 4항목 · 기본 종료 표현 · 승인 문구 시드(마무리 인사 CLOSING 1건, 텍스트 + 음성 파일)를 넣고, `POST /api/dev/login`이 시드 보호자의 세션 쿠키(운영과 같은 Spring Session JDBC)를 연다. 운영 프로필에서는 컨트롤러 빈을 등록하지 않아 경로 자체가 404다. 시드 ID는 고정 UUID이며 비밀이 아니다
B. 로그인 없이 개발 중에만 인증을 끈다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 세션 계층을 운영과 같게 두어야 U2가 구글 로그인으로 바꿀 때 뒤 경로가 그대로다. B는 인증 우회 코드가 남아 NFR9와 어긋난다. **J44**.

### Q4. 코드 발급 · 재사용 · 만료 정리

A. 코드는 SecureRandom으로 8글자를 뽑고 UNREGISTERED 행끼리 유일 색인(충돌 시 최대 5회 다시 뽑기). 같은 bootstrap 쿠키가 오면 같은 코드(AC6.1.5), 쿠키가 없거나 모르는 값이면 새 행 · 새 코드. 만료(발급 24시간)된 UNREGISTERED는 조회 때나 10분 정리 작업이 EXPIRED로 바꿔 코드를 비우고, EXPIRED · 미사용 행은 7일 뒤 삭제한다
B. 만료 행을 바로 삭제한다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. EXPIRED를 하루 이틀 남기면 "코드가 만료됐어요" 응답과 "없는 코드예요"를 구분해 로그에서 원인을 볼 수 있다. 화면에는 둘 다 '없는 코드'로 같게 보인다(AC2.2.3). **J45**.

### Q5. 재등록 허용 판정과 동시 등록

A. 계정당 활성 기기는 하나(부분 유일 색인). 새 코드를 넣을 때 옛 활성 기기가 있으면: 마지막 보고 상태가 OFFLINE · DISCONNECTED · UNRESPONSIVE이거나 30초 넘게 보고가 없으면(UNKNOWN) 허용 — 새 기기를 REGISTERED로, 옛 기기를 같은 트랜잭션에서 RELEASED(사유 REREGISTERED) · 토큰 무효로 바꾸고 DeviceReleased를 Outbox에 쓴다. CONNECTED · UNSTABLE이면 409 DEVICE_ALREADY_ACTIVE. 같은 코드를 두 계정이 동시에 등록하면 조건부 UPDATE(`WHERE registration_status='UNREGISTERED'`) 한 건만 성공한다
B. 활성 기기가 있으면 언제나 거절한다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 이미 J13이 정한 방향을 판정 값까지 닫았다. UNKNOWN을 허용에 넣은 까닭은 Realtime이 멈춘 상황에서 보호자가 영영 못 갈아타는 막다른 길을 피하려는 것이다 — 잘못 풀려도 아이 기기가 D0에서 다시 등록하면 되는 복구 가능한 쪽이다. **J46**.

### Q6. 연결 상태 사본과 05 · 04 판정

A. 사본은 최신 보고 시각이 이기고(같거나 옛 것은 무시), 이벤트(DeviceStatusChanged)도 같은 규칙(`occurredAt`이 보고 시각). 등록됐지만 한 번도 보고가 없는 기기는 UNRESPONSIVE(회색, 마지막 확인 없음), 보고가 있고 30초 넘게 새 보고가 없으면 UNKNOWN("기기 상태 확인 불가"). Realtime은 영속 연결 행 전부(OFFLINE 포함)를 10초마다 보고한다. 판정은 읽을 때 계산한다: CONNECTED → 안정(시작 가능), UNSTABLE → DEVICE_UNSTABLE, DISCONNECTED → DEVICE_DISCONNECTED, OFFLINE · UNRESPONSIVE → DEVICE_UNRESPONSIVE, UNKNOWN → DEVICE_STATUS_UNKNOWN, 등록 없음 → NO_DEVICE
B. 연결 상태를 Core가 직접 판정한다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 판정 원장은 DeviceGateway(J4)이고 Core는 사본만 읽는다. OFFLINE 행도 보고해야 "꺼 둔 기기"가 30초 뒤 '확인 불가'로 바뀌지 않는다. **J47**.

### Q7. 시작 거절 사유 우선순위

A. 여러 사유가 겹치면 CONSENT_MISSING(403) → SESSION_IN_PROGRESS(409) → DEVICE_NOT_STABLE(423, 기기 없음 포함) 순으로 첫 번째만 돌려준다. 시작은 `idempotencyKey`로 멱등이고, 진행 중 세션은 Core `core_activity`의 부분 유일 색인(보호자당 종료 아닌 세션 1개)이 막는다
B. 사유를 모두 목록으로 돌려준다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 화면은 사유 문구를 하나만 보인다(AC3.1.6 "해당 사유 문구 하나만"). 동의가 없으면 기기 상태를 알려 줄 의미가 없고, 진행 중 세션이 있으면 기기는 대개 정상이다. **J48**.

### Q8. 메시징(Outbox/Inbox) 운영 규칙

A. 발송 폴러는 500ms마다 `PENDING` 중 `available_at`이 지난 행을 만든 순서로 20개까지 직렬로 보낸다(재시도 대기 중인 행이 뒤 행을 막지 않는다 — 순서는 받는 쪽이 버전 · 시각으로 가린다). 200이면 PUBLISHED, 409(충돌)면 재시도 없이 FAILED, 그 밖의 실패 · 시간 초과는 재시도 횟수를 올리고 1 · 2 · 4 · 8 · 16 · 30초 뒤로 미룬다(20회 넘으면 FAILED + 경고 로그). 수신은 envelope 검증 → 같은 eventId가 있으면 페이로드 해시가 같을 때 200(중복), 다르면 충돌 기록 + 409 → 새 행을 Inbox에 커밋 → 200 → 비동기로 처리기 호출. 처리기가 실패하면 1 · 2 · 4 · 8 · 16초로 5회 다시 호출하고 그래도 실패하면 DEAD(경고 로그). 알 수 없는 eventType이나 지원하지 않는 상위 schemaVersion은 IGNORED로 기록하고 200. 시작 때 자기 Outbox/Inbox의 미완료 행을 다시 집는다
B. 이벤트 브로커(메시지 큐)를 쓴다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. ADR-010이 브로커를 쓰지 않기로 했다(EC2 1대). 충돌에 재시도를 하지 않는 것은 다시 보내도 결과가 같기 때문이다. **J49**.

### Q9. Outbox/Inbox 안의 민감 페이로드

A. 전사 원문이 든 이벤트(SessionRecordCaptured)는 PUBLISHED · PROCESSED가 되는 즉시 `payload_json`을 `{}`로 비우고 eventId · eventType · 해시만 남긴다. 실패로 남은 행은 7일 뒤 비운다. 삭제 참가자가 계정 단위로 찾아 지울 수 있게 Outbox/Inbox 네 테이블에 `guardian_id`(널 허용)를 더한다 — ERD v0.2 변경. 로그에는 eventId · eventType · 상태만 남기고 페이로드를 찍지 않는다
B. 페이로드를 그대로 영구 보관한다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. `unit-of-work.md` 공통 완료 조건은 'Outbox 페이로드에 전사 원문을 남기지 않는다'인데 `contract-summary.md` 6절의 SessionRecordCaptured는 대화 원문을 싣는다. 보낼 때까지만 담고 끝난 뒤 비우면 두 문장이 모두 지켜진다. ADR-010 "전사 스냅샷 · Outbox/Inbox · 로그도 동의 · 보존 · 삭제 정책의 대상" 문장과도 맞다. **J50**.

### Q10. 스켈레톤 세션의 안전 시한 (J16 보정)

A. J16은 '시간 규칙은 뒤 유닛'이라 했지만, 시한이 하나도 없으면 시작이 멈추거나 기기가 사라졌을 때 세션이 영원히 남아 계정당 1개 규칙이 그 보호자를 잠근다. 그래서 U1도 아래 네 시한만 넣는다(설정 키 `mujoong.timing.*`, 값은 시간 계약 7절): ① REQUESTED → PREPARING 10초 → FAILED, ② 시작 명령 송신 → DEVICE_ACK · DEVICE_READY 20초 → FAILED(DEVICE_NO_ACK), ③ CLOSING → 기기 멈춤 확인 30초 → ENDED_UNCONFIRMED, ④ 마지막 기기 메시지 40초 → 진행 중이면 ABORTED(DISCONNECT). 참여 대기 · 공백 4 · 6분 · 10초 점선 · 기술적 일시 정지 · 경계값 시험은 U4
B. 시한을 U4까지 모두 미룬다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 시한은 '무엇이 언제 끝나는가'의 안전 최소이며 U4가 같은 키 · 같은 코드를 확장한다. **J51**.

### Q11. 게이트 최소 규칙과 음성 1턴

A. 스켈레톤의 한 '출력 구간' = GPT-Live 출력 전사가 800ms 조용해지거나 아이 입력이 다시 시작될 때까지 모은 한 응답. 검사는 (가) 전사가 비었거나 출력 음성과 대응이 안 되면 폐기, (나) 시드 금지 패턴 목록(진단 · 치료 · 점수 · 정상 · 비정상 류 한 줄 사전)에 걸리면 폐기, 폐기하면 승인 대체 문구(WAITING_FILLER 시드 1건)를 재생한다. 통과한 구간만 `OUTPUT_BEGIN` → 0x02 프레임 → `OUTPUT_END`로 기기에 보낸다. 기기는 승인 경로 말고는 소리를 내지 않는다. 정식 구간 분할 · 사전 v1 · 역할 규칙은 U3
B. 스켈레톤은 게이트 없이 직접 중계한다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 게이트를 건너뛰면 스켈레톤이 "가장 위험한 연결(공급자 → 게이트 → 기기)"을 증명하지 못한다. 800ms · 시드 사전은 U3가 바꿀 수 있는 시작값이다. **J52**.

### Q12. 기기 「끄기」와 재시작

A. 활동 중 아이의 「끄기」는 연결 끊김으로 처리해 40초 경로(DISCONNECT)로 간다 — 스토리 B16이 이미 정했고 계약 11절의 열린 질문을 닫는다(close 1000 DEVICE_OFF는 연결 상태만 OFFLINE으로 구분). Realtime이 재시작하면 종료 아닌 세션을 FAILED(SERVER_RESTART)로 바꾸고 음성 활동을 이어 붙이지 않는다(ADR-010). Core가 재시작하면 REQUESTED로 남은 세션을 시한 규칙으로 정리한다
B. 「끄기」를 즉시 종료 신호로 쓴다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. B16이 '별도 즉시 종료 신호는 두지 않는다'고 못박았다. **J53**.

## 3. 답 분석

- **모호함:** 없음. 숫자(500ms 폴링 · 800ms 구간 · 5회 재시도 · 7일 보관 · 기기 로그인 시도 제한)는 11/2 측정 뒤 바꿀 수 있는 시작값이다.
- **모순 점검:** (1) 공통 완료 조건의 'Outbox 페이로드에 전사 원문 없음'과 계약 6절 SessionRecordCaptured의 대화 원문 → Q9로 풀었다(전달이 끝나면 비움). (2) J16의 '시간 규칙은 뒤 유닛'과 '계정당 1개' 잠금 방지 → Q10으로 최소 시한 4개만 앞당겼다. (3) 계약 11절의 '진행 중 「끄기」' 열린 질문 → Q12(B16)로 닫았다. (4) ERD v0.1 Outbox/Inbox에 계정 식별 열이 없어 삭제 참가자가 찾을 수 없음 → Q9에서 `guardian_id` 추가.
- **후속 질문:** 없음.
