# Functional Design — U1 u1-walking-skeleton — 질문 파일

> 무중(Mujoong) · 깊이 Standard · 2026-10-09 (클라우드 세션)
> 입력: `inception/units-generation/unit-of-work.md`(U1 정의), `unit-of-work-story-map.md`(U1이 닿는 스토리 US1.1 · US1.4 · US2.1 · US2.2 · US3.1 · US3.3 · US6.1 · US6.2, AC 83개), `inception/requirements-analysis/requirements.md`(FR2.1 · FR2.2 · FR2.3 · NFR6 · NFR9), `inception/domain-design/components.md`(DeviceRegistry · CoreMessaging · RealtimeMessaging · DeviceWeb, J1~J14), `inception/contract-design/contract-summary.md`(C2 · C7 · C9 · C11 · T, J20~J31), `inception/delivery-planning/bolt-plan.md`(B1, J32~J41), ERD v0.1 DDL.
> 규모: 질문 19개(Q1 ~ Q19) · 준하 제안(가정) 19건(J42 ~ J60) · 규칙 89개(`rules.md`의 BR1 ~ BR9). 질문과 J는 차례로 하나씩 대응한다(Q1 → J42, Q12 → J53, Q13 → J54, Q19 → J60).
>
> **누가 답했나.** 사람 지시(2026-10-09 "반복 순서는 unit-major 그대로 둬. U1의 3.1→3.4까지 진행하고 3.5 앞에서 멈춰. 이어갈 방식을 물으면 자동 진행으로")와 `docs/cloud_handoff.md` 2절에 따라 팀장(준하)의 대리가 채웠다. Q13 ~ Q19는 같은 날 Functional Design 검토에서 확정된 지적을 풀려고 더했고 같은 방식으로 채웠다. 같은 날 2차 검토 수정에서 `rules.md`(규칙 89개)의 문구가 바뀐 곳에 맞춰 답 · 근거를 다시 맞췄다(3절 모순 점검 (13)). 대리 판단은 **준하 제안(가정) Jn**(J42 ~ J60)으로 `functional-spec.md` 8절 표에 옮겼다. **Mode:** 대리 판단.

## 1. 이미 정해진 것 (확인용)

| 번호 | 정해진 것 | 근거 |
|---|---|---|
| K1 | 기기 등록 상태 4개(UNREGISTERED · REGISTERED · RELEASED · EXPIRED — EXPIRED는 J45가 더한다)와 연결 상태 5개(OFFLINE · CONNECTED · UNSTABLE · DISCONNECTED · UNRESPONSIVE)는 서로 다른 두 축이다. 등록은 Core DeviceRegistry, 연결은 Realtime DeviceGateway가 주인이고 Core는 보고받은 사본을 쓴다 | components.md 기기 상태 대응표 · J4 · J45 |
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

A. U1이 마이그레이션(V1)으로 만드는 테이블 = 완성 소유 테이블(core_device · core_outbox_event · core_inbox_event · realtime_outbox_event · realtime_inbox_event) + 스켈레톤 경로가 읽고 쓰는 테이블(core: guardian · consent · child · stop_expression(표만 만들고 U1은 비워 둔다, Q3) · approved_content(+version) · activity · spring_session(+attributes), realtime: device_connection · activity_session · session_event · session_stop_expression · transcript). 테이블 모양은 Domain Design 최종 컬럼(J12 반영)으로 한 번에 만들고, 소유 유닛이 뒤에 열 · 색인을 더하기만 한다(더하기만 안전)
B. 지금 쓰는 열만 만들고 뒤 유닛이 바꾼다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 열을 줄여 만들면 뒤 유닛이 깨는 변경(열 이름 변경 · 필수화)을 하게 되어 계약 10절의 '더하기만 안전'과 어긋난다. 테이블을 빈 채로 두는 비용은 작다. 연결 행(realtime_device_connection)은 DeviceGateway 소유라 '완성'이 아니라 스켈레톤이 지나가는 표다(Q1). **J43**.

### Q3. 시험 로그인과 시드

A. dev 프로필 전용 `DevSeedRunner`(멱등)가 보호자 1명 · 아이 1명 · 현재 정책 버전의 동의 4항목 · 승인 문구 시드 2건(마무리 인사 CLOSING + 게이트 대체 문구 WAITING_FILLER, 각각 텍스트 + 음성 파일; WAITING_FILLER는 Q11, 음성 파일은 임시 WAV 두 개로 Q19)만 넣고, `POST /api/dev/login`이 시드 보호자의 세션 쿠키(운영과 같은 Spring Session JDBC)를 연다. 기본 종료 표현 ‘무중아 그만할래’는 시드 목록에 없다 — 코드 상수이고, 세션 패키지의 stopExpressions(기본 표현 한 건, isDefault true)로 Realtime에 건네져 Realtime이 `realtime_session_stop_expression`에 한 행으로 저장하며, `core_stop_expression`은 U1이 비워 둔다(BR6.5). 두 빈은 프로필 dev이고 `mujoong.dev-login.enabled=true`(기본 false)일 때만 등록된다. demo · prod에서는 컨트롤러 빈을 등록하지 않아 경로 자체가 404이고, prod에 dev가 함께 켜지면 앱이 기동을 거부한다. demo는 U2의 구글 로그인을 쓴다(공개된 시연 서버에서 시험 로그인은 누구에게나 시드 보호자 세션을 주므로 열지 않는다). 시드 ID는 고정 UUID이며 비밀이 아니다
B. 로그인 없이 개발 중에만 인증을 끈다
C. dev · demo 프로필 모두에서 시험 로그인과 시드를 켠다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 세션 계층을 운영과 같게 두어야 U2가 구글 로그인으로 바꿀 때 뒤 경로가 그대로다. B는 인증 우회 코드가 남아 NFR9와 어긋난다. C는 인터넷에 열린 시연 서버에서 누구나 `curl` 한 번으로 보호자 세션을 얻어 시드 아이로 대화를 시작하거나 시연 기기를 자기 계정에 묶을 수 있다. 승인 문구가 둘인 까닭은 게이트가 구간을 폐기하면 대체 문구를 틀어야 하기 때문이다(Q11). 기본 종료 표현을 시드가 아닌 코드 상수로 둔 까닭은 시드가 돌지 않는 demo · prod에서도 모든 세션 패키지에 기본 표현이 항상 들어가게 하려는 것이고, 아이별 종료 표현(`core_stop_expression`)은 U2의 설정 화면이 채운다. BR6.5 · BR8.3 · BR8.4. **J44**.

### Q4. 코드 발급 · 재사용 · 만료 정리

A. 코드는 SecureRandom으로 8글자를 뽑고 UNREGISTERED 행끼리 부분 유일 색인(충돌하면 다시 뽑되 첫 추첨을 포함해 총 5번까지 — 재추첨은 4번이고, 5번 다 겹치면 503 CODE_ISSUE_FAILED). 같은 bootstrap 쿠키가 오면 같은 코드(AC6.1.5), 쿠키가 없거나 모르는 값이거나 행이 EXPIRED · RELEASED이면 새 행 · 새 코드, 이미 등록된 행(REGISTERED, claim 전)의 쿠키면 409 ALREADY_REGISTERED(기기 웹은 이 응답을 받으면 claim으로 간다, BR9.1). 만료(발급 24시간)된 UNREGISTERED는 조회 때나 10분 정리 작업이 EXPIRED로 바꾸고, 코드는 남기고 UNREGISTERED 부분 유일 색인에서만 빠진다(코드 열은 NOT NULL이라 비우지 않으며, 같은 코드를 새 발급에서 다시 뽑아도 된다). EXPIRED 행은 7일이 지나면 삭제한다
B. 만료 행을 바로 삭제한다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. EXPIRED 행과 코드를 7일 남기면 "코드가 만료됐어요" 응답과 "없는 코드예요"를 구분해 로그에서 원인을 볼 수 있다(로그에는 코드 앞 4글자만 남긴다). 화면에는 둘 다 '없는 코드'로 같게 보인다(AC2.2.3). 추첨 횟수는 첫 추첨을 포함해 총 5번(재추첨 4번)이다. 코드 공간이 31^8이라 겹침이 거의 없고, 5번 연속 겹치면 이상 상황이므로 오래 돌지 않고 503으로 알려 기기 웹이 5초 뒤 다시 요청하게 한다. BR1.2 ~ BR1.4. **J45**.

### Q5. 재등록 허용 판정과 동시 등록

A. 계정당 활성 기기는 하나(부분 유일 색인). 새 코드를 넣을 때 옛 활성 기기가 있으면 연결 판정(BR4.3)을 그대로 쓴다: 옛 기기가 안정(CONNECTED이고 30초 안에 보고가 있음)이거나 UNSTABLE이면 409 DEVICE_ALREADY_ACTIVE. 그 밖은 모두 허용한다 — 마지막 보고 상태가 OFFLINE · DISCONNECTED · UNRESPONSIVE인 기기, 30초 넘게 보고가 없는 기기(UNKNOWN), 보고가 한 번도 없던 기기. 허용이면 한 트랜잭션에서 옛 기기를 먼저 RELEASED(사유 REREGISTERED) · 토큰 무효로 바꾸고 DeviceReleased를 Outbox에 쓴 뒤 새 기기를 REGISTERED로 바꾼다(보호자당 REGISTERED 하나의 부분 유일 색인이 문장마다 즉시 검사되므로 옛 기기 해제가 먼저이고, 새 기기 등록이 실패하면 옛 기기 해제까지 되돌린다). 같은 코드를 두 계정이 동시에 등록하면 조건부 UPDATE(`WHERE registration_status='UNREGISTERED'`) 한 건만 성공하고, 갱신 행 수가 0인 쪽은 행을 다시 읽어 답한다 — 같은 보호자의 REGISTERED 행이면(등록은 커밋됐는데 응답이 유실돼 같은 코드를 다시 보낸 경우) 409 대신 첫 성공과 같은 201 {deviceId, codePrefix, registeredAt}로 답하고 등록 실패 횟수(보호자당 10분 10번)에 세지 않으며, 다른 계정이 이미 등록한 코드나 RELEASED 행이면 409 CODE_ALREADY_REGISTERED, 그 밖은 404 CODE_NOT_FOUND다 (BR2.1 ② · BR2.3, J59(m))
B. 활성 기기가 있으면 언제나 거절한다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 이미 J13이 정한 방향을 판정 값까지 닫았다. UNKNOWN과 '보고가 한 번도 없던 기기'를 허용에 넣은 까닭은 Realtime이 멈췄거나 기기가 연결도 보고도 없이 사라진 상황에서 보호자가 영영 못 갈아타는 막다른 길을 피하려는 것이다(해제 단추도 없다, AC2.2.5) — 잘못 풀려도 아이 기기가 D0에서 다시 등록하면 되는 복구 가능한 쪽이다. 같은 보호자의 재시도를 201로 돌려주는 까닭은 응답이 유실된 뒤 보호자가 같은 코드를 다시 넣었을 때 등록은 성공했는데 화면은 '이미 등록된 코드예요' 빨간 카드를 보이는 어긋남과, 그 재시도가 실패 횟수 잠금(429)에 쌓이는 일을 막으려는 것이다. BR2.1 ~ BR2.5. **J46**.

### Q6. 연결 상태 사본과 05 · 04 판정

A. 사본은 최신 보고 시각이 이기고(같거나 옛 것은 무시), 이벤트(DeviceStatusChanged)도 같은 규칙(`occurredAt`이 보고 시각). 보고(PUT)는 `state`와 `reportedAt`이 필수이고(없으면 400 INVALID_ENVELOPE, J59(i)) 나머지 필드가 없으면 기존 값을 유지한다(COALESCE). 등록됐지만 한 번도 보고가 없는 기기는 UNRESPONSIVE(회색, 마지막 확인 없음), 보고가 있고 30초 넘게 새 보고가 없으면 UNKNOWN("기기 상태 확인 불가"). Realtime은 영속 연결 행 전부(OFFLINE 포함)를 10초마다 보고한다. 판정은 읽을 때 계산한다: CONNECTED → 안정(시작 가능), UNSTABLE → DEVICE_UNSTABLE, DISCONNECTED → DEVICE_DISCONNECTED, OFFLINE · UNRESPONSIVE → DEVICE_UNRESPONSIVE, UNKNOWN → DEVICE_STATUS_UNKNOWN, 등록 없음 → NO_DEVICE
B. 연결 상태를 Core가 직접 판정한다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 판정 원장은 DeviceGateway(J4)이고 Core는 사본만 읽는다. OFFLINE 행도 보고해야 "꺼 둔 기기"가 30초 뒤 '확인 불가'로 바뀌지 않는다. 시각은 두 경로가 같은 Realtime 시계에서 오므로 비교할 수 있다(보고의 reportedAt은 연결 행을 읽은 시각, 이벤트의 occurredAt은 상태를 쓴 시각, BR4.8). BR4.1 ~ BR4.3. **J47**.

### Q7. 시작 거절 사유 우선순위

A. 같은 `idempotencyKey`의 재요청은 검사에 앞서 첫 응답을 그대로 돌려준다(새 세션을 만들지 않는다). 첫 시도가 Core의 로컬 FAILED로 끝났다면 Activity에 저장한 `start_result_code`(202 / 423 / 409 / 503)를 그대로 재생한다(BR6.2). 새 키일 때만, 여러 사유가 겹치면 CONSENT_MISSING(403) → SESSION_IN_PROGRESS(409) → DEVICE_NOT_STABLE(423, 기기 없음 포함) 순으로 첫 번째만 돌려준다. 진행 중 세션은 Core `core_activity`의 부분 유일 색인(보호자당 종료 아닌 세션 1개)이 막고, 막는 사본이 REQUESTED가 아니면 거절하기 전에 Realtime 진행 조회로 한 번 대조한다 — 받은 status · statusVersion을 조건부 UPDATE로 사본에 반영한 뒤 다시 판정하고, Realtime이 모르는 세션이면 사본을 로컬 FAILED로 닫고 다시 판정하며, 조회가 실패하면 사본대로 409다(BR6.1 ②)
B. 사유를 모두 목록으로 돌려준다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 화면은 사유 문구를 하나만 보인다(AC3.1.6 "해당 사유 문구 하나만"). 동의가 없으면 기기 상태를 알려 줄 의미가 없고, 진행 중 세션이 있으면 기기는 대개 정상이다. 멱등 조회를 검사보다 먼저 두어야, 응답이 유실돼 같은 키를 다시 보냈을 때 방금 만든 세션이 '진행 중 세션'으로 거절되지 않는다(AC3.1.8). 로컬 FAILED의 같은 키 재요청은 저장해 둔 응답 코드를 그대로 재생해야 첫 응답과 어긋나지 않는다. 거절 전 Realtime 대조는 SessionStateChanged 이벤트가 FAILED · DEAD로 끝나 Core 사본이 영구히 낡았을 때 계정이 SESSION_IN_PROGRESS로 잠기는 일을 막는다(REQUESTED는 BR6.12 ① 10초 시한이 닫으므로 대조하지 않는다). BR6.1 · BR6.2. **J48**.

### Q8. 메시징(Outbox/Inbox) 운영 규칙

A. 발송 폴러는 500ms마다 `PENDING` 중 `available_at`이 지난 행을 만든 순서로 20개까지 직렬로 보낸다(재시도 대기 중인 행이 뒤 행을 막지 않는다 — 순서는 받는 쪽이 버전 · 시각으로 가린다). 200이면 PUBLISHED, 409(충돌) · 400(봉투 오류)이면 다시 보내도 결과가 같으므로 재시도 없이 FAILED, 401을 포함한 그 밖의 실패 · 시간 초과는 재시도 횟수를 올리고 1 · 2 · 4 · 8 · 16 · 30초 뒤로 미룬다(재시도 20회 뒤에도 실패하면 FAILED + 경고 로그). 수신은 envelope 검증 → 같은 eventId가 있으면 페이로드 해시가 같을 때 200(중복), 다르면 충돌 기록 + 409 → 새 행을 Inbox에 RECEIVED로 커밋 → 200 → 비동기로 처리기 호출. 처리기는 호출 전에 선점 UPDATE(`SET next_attempt_at = now + 30초 WHERE status = RECEIVED AND next_attempt_at <= now`)의 행 수가 1일 때만 부른다(첫 비동기 호출과 재호출 폴러의 중복 방지). 처리기가 실패하면 1 · 2 · 4 · 8 · 16초로 5회 다시 호출하고 그래도 실패하면 DEAD(경고 로그). 알 수 없는 eventType이나 지원하지 않는 상위 schemaVersion은 RECEIVED를 거치지 않고 처음부터 IGNORED(페이로드 비움)로 커밋하고 200이다(처리기 호출 없음, BR5.6 · BR5.9). 앱 시작 때 Outbox는 PENDING 중 available_at이 지난 행을 폴러가 평소대로 집고, Inbox는 HTTP 수신을 열기 전에 모든 RECEIVED 행의 next_attempt_at을 now로 당긴 뒤(retry_count는 그대로) 위 선점 경로로 한 번씩 처리기에 넘긴다(BR5.7 · BR5.12)
B. 이벤트 브로커(메시지 큐)를 쓴다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. ADR-010이 브로커를 쓰지 않기로 했다(EC2 1대). 충돌 · 봉투 오류에 재시도를 하지 않는 것은 다시 보내도 결과가 같기 때문이다. 401은 배포 중 두 앱의 비밀이 잠시 어긋난 일시 상태일 수 있고, FAILED는 자동 재발송이 없어 DeviceReleased · SessionStateChanged가 영구히 사라지므로 재시도한다. 모르는 type · 새 버전을 처음부터 IGNORED로 커밋하는 까닭은 부를 처리기가 없는 행이 RECEIVED로 남아 재호출 · DEAD 경로를 헛돌지 않게 하려는 것이다. 시작 때 RECEIVED의 시각을 당겨 선점 경로를 타게 하는 까닭은 200 직후 처리기를 부르기 전에 죽은 행과 선점 30초 안에 죽은 행을 30초를 기다리지 않고 구하면서도 처리기 중복 호출 방지를 그대로 쓰려는 것이다. BR5.3 · BR5.6 ~ BR5.9 · BR5.12. **J49**.

### Q9. Outbox/Inbox 안의 민감 페이로드

A. 전달이 끝난 행(Outbox PUBLISHED, Inbox PROCESSED · IGNORED)은 — 특히 전사 원문이 든 이벤트(SessionRecordCaptured)는 — 그 즉시 `payload_json`을 `{}`로 비우고 eventId · eventType · 해시만 남긴다. 실패로 남은 행(FAILED · DEAD)은 7일 뒤(하루 한 번 도는 정리 작업) 비운다. 삭제 참가자가 계정 단위로 찾아 지울 수 있게(Q17) Outbox/Inbox 네 테이블에 `guardian_id`(널 허용)를 더한다 — ERD v0.2 변경. 로그에는 eventId · eventType · 상태만 남기고 페이로드를 찍지 않는다
B. 페이로드를 그대로 영구 보관한다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. `unit-of-work.md` 공통 완료 조건은 'Outbox 페이로드에 전사 원문을 남기지 않는다'인데 `contract-summary.md` 6절의 SessionRecordCaptured는 대화 원문을 싣는다. 보낼 때까지만 담고 끝난 뒤 비우면 두 문장이 모두 지켜진다. ADR-010 "전사 스냅샷 · Outbox/Inbox · 로그도 동의 · 보존 · 삭제 정책의 대상" 문장과도 맞다. **J50**.

### Q10. 스켈레톤 세션의 안전 시한 (J16 보정)

A. J16은 '시간 규칙은 뒤 유닛'이라 했지만, 시한이 하나도 없으면 시작이 멈추거나 기기가 사라졌을 때 세션이 영원히 남아 계정당 1개 규칙이 그 보호자를 잠근다. 그래서 U1도 아래 네 시한만 넣는다(설정 키 `mujoong.timing.*`, 값은 시간 계약 7절): ① REQUESTED → PREPARING 10초 → Core 사본을 `WHERE status = REQUESTED AND status_version = 0` 조건부로 FAILED(local_failed, start_result_code 202)로 닫고 Realtime에 정리를 요청한다(Q13), ② START_SESSION 송신을 시도한 시각부터 20초 안에 ACK · DEVICE_READY(OK) · 공급자 session.started가 모두 확인돼 ACTIVE가 되지 않으면 FAILED(ACK나 DEVICE_READY가 빠졌으면 DEVICE_NO_ACK이고 기기 연결 판정을 UNRESPONSIVE로 쓴다, Q14; 둘은 왔는데 session.started가 없으면 PROVIDER_UNAVAILABLE). 송신 자체가 일어나지 못하면(소켓 없음 · 전송 예외) 20초를 기다리지 않고 즉시 PREPARING → FAILED(DEVICE_NO_ACK)이고 UNRESPONSIVE로 쓴다, ③ CLOSING → 기기 멈춤 확인 30초 → ENDED_UNCONFIRMED(기기 연결 판정을 UNRESPONSIVE로 쓴다), ④ ACTIVE에서 그 세션의 START_SESSION을 받은 소켓의 마지막 기기 메시지(HEARTBEAT 포함) 수신 뒤 40초 → ABORTED(DISCONNECT). 이 ④의 시계는 Realtime 메모리의 세션별 값이고 영속 열이 없다(Realtime이 재시작하면 비종료 세션을 모두 FAILED로 닫으므로 이어 붙일 시계가 없다, Q12). 영속 `last_seen_at` 기준은 기기 연결 상태의 DISCONNECTED 판정(Q14)에만 쓴다. 전이는 DEVICE_ACK = START_SESSION의 ACK만(계약 4.2절), ACTIVE = DEVICE_READY(mic · player OK)와 공급자 session.started 둘 다(나중 것이 전이를 일으킨다)이고, DEVICE_READY의 mic · player가 UNAVAILABLE이면 20초를 기다리지 않고 DEVICE_ACK → FAILED(DEVICE_NO_ACK)다. 출발 상태가 둘 이상인 FAILED 전이는 세션 행을 `FOR UPDATE`로 읽어 failure_code를 정하고 `WHERE status = :읽은 상태`로 써서 승자 하나만 성립시키며, 0행이면 한 번 다시 읽어 다시 시도하고 이미 ACTIVE · 종료 상태면 시한이 충족된 것으로 보고 무시한다. 참여 대기 · 공백 4 · 6분 · 10초 점선 · 기술적 일시 정지 · 경계값 시험은 U4
B. 시한을 U4까지 모두 미룬다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 시한은 '무엇이 언제 끝나는가'의 안전 최소이며 U4가 같은 키 · 같은 코드를 확장한다. 공급자 응답 대기(DEVICE_ACK → ACTIVE)도 ②의 20초 안에 있어야 한다 — 시한이 없으면 공급자가 응답하지 않을 때 DEVICE_ACK에 영원히 머물러 계정이 잠긴다. DEVICE_ACK를 ACK만으로 정하고 ACTIVE를 DEVICE_READY와 session.started의 합으로 정한 것은 계약 4.2절의 메시지 의미를 그대로 따르고, 어느 쪽이 먼저 와도 나중 것이 전이를 일으키게 하려는 것이다. 영속 세션 시계를 두지 않는 까닭은 재시작 뒤 이어 붙이지 않는 세션(ADR-010)에 열을 더 쓸 이유가 없기 때문이다. BR6.8 · BR6.12 · BR6.13. **J51**.

### Q11. 게이트 최소 규칙과 음성 1턴

A. 스켈레톤의 한 '출력 구간' = 공급자 출력 전사가 800ms 조용해지거나 아이 입력이 다시 시작될 때까지 모은 한 응답. 검사는 (가) 전사가 비었거나 출력 음성과 대응이 안 되면 폐기, (나) 시드 금지 패턴 목록(진단 · 치료 · 점수 · 정상 · 비정상 류 한 줄 사전)에 걸리면 폐기, 폐기하면 승인 대체 문구(WAITING_FILLER 시드 1건, 음성은 임시 WAV로 Q19)를 재생한다. 통과한 구간만 `OUTPUT_BEGIN` → 0x02 프레임 → `OUTPUT_END`로 기기에 보낸다. 기기는 승인 경로 말고는 소리를 내지 않는다. 정식 구간 분할 · 사전 v1 · 역할 규칙은 U3
B. 스켈레톤은 게이트 없이 직접 중계한다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 게이트를 건너뛰면 스켈레톤이 "가장 위험한 연결(공급자 → 게이트 → 기기)"을 증명하지 못한다. 800ms · 시드 사전은 U3가 바꿀 수 있는 시작값이다. **J52**.

### Q12. 기기 「끄기」와 재시작

A. 활동 중 아이의 「끄기」는 연결 끊김으로 처리해 40초 경로(DISCONNECT)로 간다 — 스토리 B16이 이미 정했고 계약 11절의 열린 질문을 닫는다(close 1000 DEVICE_OFF는 연결 상태만 OFFLINE으로 구분). Realtime이 재시작하면 종료 아닌 세션을 FAILED(SERVER_RESTART)로 바꾸고 음성 활동을 이어 붙이지 않는다(ADR-010). Core가 재시작하면 REQUESTED로 남은 세션을 시한 규칙으로 정리한다
B. 「끄기」를 즉시 종료 신호로 쓴다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. B16이 '별도 즉시 종료 신호는 두지 않는다'고 못박았다. **J53**.

### Q13. Core가 포기한 세션의 Realtime 쪽 정리

A. Core가 시작 호출 실패 · 시간 초과(같은 sessionId로 1회 재시도한 뒤) · 10초 시한(Q10 ①)으로 사본을 로컬 FAILED로 닫으면 — `WHERE status = REQUESTED AND status_version = 0` 조건부이고 0행이면 Realtime 이벤트가 사본을 앞선 것이므로 취소 없이 202 {sessionId, status: REQUESTED}를 돌려준다 — Realtime에 취소 `POST /internal/sessions/{sessionId}/end {requestId = sessionId, reason: CORE_ABANDONED}`(제한 2초, 멱등)를 보내 정리한다. CORE_ABANDONED는 내부 end reason이고 세션의 종료 사유(end_reason)는 FAILED다. Realtime의 ActivitySession INSERT는 `ON CONFLICT (id) DO NOTHING` 뒤 sessionId로 다시 읽어, 시간 초과 재시도가 첫 호출과 겹쳐도 재시도가 실패를 만들지 않는다(BR6.6). Realtime end 응답표(reason CORE_ABANDONED): 모르는 세션이면 10분 묘비를 두고 202, PREPARING · DEVICE_ACK · ACTIVE면 FAILED(failure_code 비움)로 닫고 공급자 세션을 닫으며 START_SESSION이 나갔고 연결이 살아 있으면 기기에 CLOSE_TRANSPORT(FAILED)를 보내고 SessionStateChanged를 쓴 뒤 202, CLOSING이면 그대로 두고 202, 이미 종료 상태면 202 무동작. 보호자 끝내기(reason GUARDIAN_STOP)의 응답은 ACTIVE → 202 CLOSING, CLOSING → 202 CLOSING(멱등: 종료 요청 1건 · 기기 종료 명령 1회 · 전이 1회), 종료 상태 · PREPARING · DEVICE_ACK → 409 SESSION_NOT_ACTIVE. 로컬 FAILED 세션의 Realtime 이벤트는 사본에 반영하지 않되, 사본 덮어쓰기(`... AND NOT (status = FAILED AND local_failed)`)가 0행이면 행을 다시 읽어 local_failed이고 이벤트의 status가 종료 상태가 아니면 버리지 않고 처리기가 같은 취소를 다시 보내며, 호출이 실패하면 예외를 던져 Inbox 재호출(Q8)에 맡긴다(BR5.10 · BR6.14)
B. 취소를 best-effort로 한 번만 보내고 무시돼도 둔다(Realtime의 끝내기는 ACTIVE에서만 받는다)
C. Core가 로컬 FAILED를 만들지 않고 Realtime의 확정 응답이 올 때까지 REQUESTED로 기다린다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. B는 Realtime이 이미 세션을 만든 채 응답만 유실됐을 때, 보호자 화면은 '시작 실패'인데 아이 기기에서는 음성 세션이 이어지고 두 쪽의 보호자당 진행 세션 부분 유일 색인 때문에 새 시작도 영영 SESSION_IN_PROGRESS로 막힌다. C는 REQUESTED를 10초 시한으로 닫는 안전 최소(Q10 ①)를 없애 계정 잠김을 되살린다. A는 계정 잠김과 고아 세션을 둘 다 막는다. 재취소 경로(BR5.10의 0행 재읽기 → BR6.14)를 둔 까닭은 10분 묘비가 Realtime 메모리라 재시작하면 사라지고, 첫 취소가 유실되거나 묘비 뒤 늦게 만들어진 세션은 Realtime 쪽 이벤트가 비종료 상태로 계속 오기 때문이며 그때마다 같은 취소로 닫는다. BR5.10 · BR6.7 ~ BR6.9 · BR6.14. **J54**.

### Q14. 기기 연결 판정 UNRESPONSIVE는 누가 쓰나 (J47 보정)

A. 명령 시한이 지났을 때 U1이 그 기기의 연결 판정을 UNRESPONSIVE로 기록한다(DeviceStatusChanged와 10초 보고로 Core 사본에 닿는다). 쓰는 경우는 둘뿐이다: ① START_SESSION 뒤 20초 안에 ACK 또는 DEVICE_READY가 빠졌을 때(START_SESSION 전송 실패 포함, Q10 ②), ② CLOSING 뒤 30초 안에 VOICE_SESSION_CLOSED가 없을 때(Q10 ③). ACK와 DEVICE_READY가 모두 도착했는데 공급자 쪽 문제(session.started 없음 · 공급자 연결 실패)로 실패한 경우는 기기 탓이 아니므로 쓰지 않는다. UNRESPONSIVE 또는 DISCONNECTED인 연결이라도 소켓이 같은 connection_id로 살아 있어 다음 HEARTBEAT가 오면 CONNECTED로 되돌리고(`WHERE connection_id = :cid`), 새 연결의 HELLO_OK도 CONNECTED로 만든다. 같은 기기의 새 연결은 인증 때가 아니라 HELLO_OK 시점에 옛 연결을 대체한다 — DeviceConnection 행을 `FOR UPDATE`로 잠그고(첫 접속은 `INSERT ... ON CONFLICT`) 옛 소켓에 4001을 보낸 뒤 connection_id · CONNECTED · since를 덮으며, 잠금을 나중에 얻은 쪽이 이긴다. 교체 이후의 DISCONNECTED · UNRESPONSIVE · OFFLINE 기록은 connection_id 조건부라 옛 연결의 닫기가 새 연결을 덮지 못한다(기기 해제 처리는 device_id만 조건, BR3.7 · BR3.8). 정상 닫기의 OFFLINE은 그대로다. 닫기 프레임 없이 끊긴 연결과, 소켓은 열려 있지만 40초 동안 메시지가 없는 연결은 마지막 기기 메시지 뒤 40초에 DISCONNECTED가 된다(영속 `last_seen_at` 기준 주기 점검이고, 기록은 `connection_id`와 `last_seen_at <= 점검시각 − 40초` 조건부라 점검이 읽은 뒤 도착한 HEARTBEAT를 덮지 않는다). UNSTABLE 판정 · 5분 창 · 지연 1초 · 불안정 → 안정 복귀 조건(히스테리시스)은 U3가 완성한다
B. UNRESPONSIVE 판정을 모두 U3에 둔다(U1은 FAILED(DEVICE_NO_ACK) 같은 세션 결과만 기록한다)
C. U1이 UNRESPONSIVE를 쓰되 새 연결 인증으로만 푼다(HEARTBEAT 해제는 U3)
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. B는 DEVICE_NO_ACK 뒤에도 04가 계속 '안정'이고 탭이 열려 있어 보호자가 같은 실패를 되풀이한다(AC2.1.6). C는 아이가 「끄기」 → 「켜기」로 다시 접속하기 전에는 UNRESPONSIVE에 갇혀 시작할 수 없다. 응답 없음의 소유를 U1로 정해 연결 상태 기계(SM2)와 시작 판정(BR4.3)을 맞춘다(UNRESPONSIVE는 연결 상태라 SM2이고, 세션 상태 기계는 SM3이다). 조건을 ‘ACK 또는 DEVICE_READY 빠짐’과 ‘종료 확인 없음’으로 좁힌 까닭은 시한 초과를 모두 UNRESPONSIVE로 쓰면 공급자만 느린 경우에도 멀쩡한 기기가 '기기를 다시 켜 보세요'로 보이기 때문이다. 같은 connection_id의 HEARTBEAT로도 되돌리는 까닭은 소켓이 살아 있는 기기가 DISCONNECTED · UNRESPONSIVE에 갇히지 않게 하려는 것이다. BR3.7 · BR4.3 · BR4.8 ③ · BR6.12 ② ③. **J55**.

### Q15. 등록 직후 아이 기기의 도착 화면

A. 「등록되었어요」를 3초 보인 뒤 T19 '꺼짐'에 착지하고, 아이가 「켜기」를 눌러 마이크 · 소리 허용을 받은 뒤에야 WSS에 연결해 '대기'가 된다. 자동으로 연결하지 않는다
B. 등록 직후 자동으로 마이크 허용을 요청하고 WSS에 연결해 곧바로 '대기'로 간다(화면 설계 D-00 분기를 글자 그대로)
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 마이크 · 소리 허용은 사용자 동작(탭)이 있어야 받을 수 있어, B는 허용 창이 아이에게 갑자기 뜨거나 브라우저가 막는다. 화면 설계 D-00의 'T19 대기'는 'T19 화면에 도착하고 연결은 「켜기」 뒤'로 읽은 해석이다. 그래서 04의 '안정'은 기기가 「켜기」로 연결된 뒤에 보이고, 스켈레톤 시험에는 등록과 04 안정 사이에 「켜기」를 누르는 단계가 들어간다. AC6.1.2 · AC6.2.1 ~ AC6.2.3 켜기 · 끄기 규칙. BR9.1 · BR9.2. **J56**.

### Q16. 05(우리 집 기기)는 U1에서 어디까지 두나

A. U1에서 05는 U2가 화면을 만들기 전까지 `/parent/device` 라우트가 04로 되돌리는 스텁이다. 05a 성공 · 「취소」 · 「←」, 04의 「기기 상태 보기 ›」, 잠긴 탭 토스트 뒤의 "05로 간다"는 규칙(BR2.7 · BR2.8 · BR4.5)에 화면 설계대로 남기고, U1에서는 이 스텁을 거쳐 04에 닿는다. U2가 스텁을 05 화면으로 교체해도 규칙은 바뀌지 않는다
B. U1이 05의 최소 화면(등록 줄 · 네트워크 줄 · 판정 카드)을 직접 만든다
C. 규칙을 '04로 간다'로 바꿔 U1에서 05를 건너뛴다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 05는 U2의 주 화면(U1/U2 주 화면 분담)이라 B는 U2와 겹쳐 설계가 충돌한다. C는 U2가 05를 넣을 때 U1 규칙 · 시험을 다시 고쳐야 하고 화면 설계와도 다르다. 라우트 스텁은 U2가 교체하기 쉽고 B1 완료 조건 ①(05a 등록 → 04 안정)을 막지 않는다. **J57**.

### Q17. 계정 삭제 참가자

A. U1이 참가자 셋을 구현한다. 각 참가자는 자기 앱의 스키마에만 쓰고(BR8.1) 멱등이다. ① Device 참가자(BR2.10, Core): `deleteByGuardian(guardianId, deletionRequestId)`가 한 트랜잭션에서 REGISTERED 행을 RELEASED(사유 ACCOUNT_DELETED, 토큰 해시 비움)로 바꾸고 DeviceReleased를 Outbox에 쓴 뒤, 그 보호자의 core_device 행을 상태와 상관없이 모두 지운다. `{deleted: {device: n}}`을 돌려준다. 호출 순서는 core_activity(U4 참가자) → core_device(U1) → 보호자 행(U2)이다. ② 메시징 Core 쪽 참가자(BR5.14, core_outbox_event · core_inbox_event)와 ③ 메시징 Realtime 쪽 참가자(BR5.14, realtime_outbox_event · realtime_inbox_event)는 같은 규칙을 자기 스키마에 적용한다: guardian_id가 같은 행 중 Outbox PUBLISHED · FAILED, Inbox PROCESSED · IGNORED · DEAD만 지우고 PENDING · RECEIVED는 건드리지 않는다(DeviceReleased · DeletionRequested · DeletionCompleted가 전달 · 처리되기 전에 사라지면 해제 신호와 삭제 완료가 영영 오지 않는다). 단 SessionRecordCaptured(대화 원문)는 상태와 무관하게 페이로드를 즉시 비우고 Outbox PENDING은 FAILED(ACCOUNT_DELETED), Inbox RECEIVED는 IGNORED로 닫는다. 호출 주체는 Core 쪽(① ②)은 U2 삭제 지휘이고, Realtime 쪽(③)은 Realtime의 DeletionRequested 처리기가 U4가 정한 호출 순서 안에서 부른다. 지휘가 DeletionCompleted 처리 뒤 마지막 단계에 다시 부르면 남은 행도 지워진다(Realtime 쪽은 DeletionCompleted Outbox 행이 PUBLISHED가 된 뒤 U4가 Realtime 참가자를 다시 불러 지운다)
B. 참가자를 만들지 않고 U2 삭제 지휘가 U1의 표를 직접 지운다
C. Device 참가자만 구현하고 Outbox/Inbox는 7일 정리 작업(Q9)에 맡긴다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 공통 완료 조건은 모든 유닛이 자기 데이터의 계정 삭제 참가자를 구현하는 것이다(DeletionParticipant 계약, contract-summary 8절 · J19). B는 컴포넌트가 자기 데이터를 지운다는 그 계약과 어긋난다. C는 실패로 남은 행의 대화 원문 페이로드가 정리 작업 전까지 최대 7일 남아 계정 삭제 약속(AC1.6.6)과 맞지 않는다. 진행 중인 제어 이벤트를 남기는 것은 A가 해제 신호와 삭제 완료 이벤트를 지우지 않으려는 것이다. 참가자가 셋인 까닭은 Core와 Realtime이 스키마를 나눠 서로의 표에 쓸 수 없고(BR8.1) 대화 원문(SessionRecordCaptured)은 Realtime의 Outbox에서 만들어지므로, Core 쪽 하나만 두면 Realtime 표에 남은 원문이 계정 삭제 약속(AC1.6.6)을 깨기 때문이다. BR2.10 · BR5.14 · BR8.1. **J58**.

### Q18. 구현이 승인된 계약보다 넓어지는 곳

A. 승인된 `contract-summary.md`는 고치지 않고, 3.5 Code Generation에서 계약 스펙 파일(10절의 `contracts/`)에 아래 열세 가지(a ~ m)를 '더하기만 안전' 변경과 편차로 더한다: (a) `POST /device/bootstrap` 409 ALREADY_REGISTERED(Q4), (b) `POST /device/bootstrap/claim` 401 BOOTSTRAP_NOT_FOUND, (c) 503 CODE_ISSUE_FAILED(Q4), (d) 400 VALIDATION_FAILED(등록 · `POST /api/activities/free`), (e) 403 CSRF_TOKEN_INVALID, (f) 내부 end reason CORE_ABANDONED(Q13), (g) 계약 6절 순서 문장의 `since`를 `occurredAt`으로(기기 이벤트는 occurredAt으로 최신만 반영), (h) `session.device-ack-timeout` 20초를 ACK · DEVICE_READY가 와도 session.started가 없는 PROVIDER_UNAVAILABLE에도 확장(Q10), (i) PUT 연결 보고의 `state` · `reportedAt` 필수화(없으면 400 INVALID_ENVELOPE, Q6), (j) verify-token 결과를 캐시하지 않고 연결마다 Core에 확인(계약 5절의 결과 캐시 문장 대신), (k) `bootstrap.rate-limit`은 같은 쿠키의 반복 요청을 세지 않음(코드 발급 남용만 막도록 적용 범위를 좁힘), (l) 기기 연결 대체 시점을 인증이 아니라 HELLO_OK로 구체화(Q14), (m) 등록(`POST /api/device/register`)에서 같은 보호자의 재시도는 409 대신 첫 성공과 같은 201(Q5)
B. 승인된 `contract-summary.md`를 지금 고쳐 다시 승인받는다
C. 구현이 알아서 맞추고 기록하지 않는다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. B는 승인 완료 문서를 되돌려 다른 유닛의 일정을 흔든다. C는 계약대로만 대역을 두고 먼저 만드는 소비 유닛(U2 ~ U6)의 코드가 구현과 어긋난다. A는 구현이 계약보다 좁지 않게 하면서 승인본을 그대로 둔다. (i) ~ (m)은 2차 수정에서 규칙이 구현을 계약보다 구체화하거나 좁힌 곳이다: 필수 필드를 더 요구하고(BR4.1), 캐시 대신 연결마다 확인하고(BR3.4), 속도 제한 적용 범위를 줄이고(BR1.6), 연결 대체 시점을 HELLO_OK로 구체화하고(BR3.7), 같은 보호자의 재시도에 첫 성공과 같은 응답을 돌려준다(BR2.1). 기존 입력의 의미를 바꾸는 (a) · (g)는 소비자가 U1 한 곳이다. BR1.2 ~ BR1.3 · BR1.6 · BR2.1 · BR3.1 · BR3.4 · BR3.7 · BR4.1 · BR6.7 · BR6.12 ② · BR8.8. **J59**.

### Q19. 마무리 인사 · 대체 문구의 승인 음성 파일

A. 실제 녹음(B26)이 오기 전이므로 U1의 dev 시드는 임시 WAV 두 개(CLOSING은 3초 이내, WAITING_FILLER)와 임시 문구를 둔다. DevSeedRunner가 개인정보 없는 정적 파일의 상대 경로(Nginx 정적 경로 아래 `audio_path`)로 시드하고, 문구는 NFR1 · NFR2(진단 · 재촉 · 점수 표현 없음)를 지킨다. 정식 문구와 제작 방법(사전 녹음 · TTS)은 U2(마무리 인사)와 D 담당 문서(대기 문구)가 정한다
B. U1이 승인 음성의 제작 방법(TTS 등)까지 정해 정식 음성을 만든다
C. 음성 파일 없이 텍스트만 시드하고 스켈레톤에서 마무리 인사 재생을 뺀다
X. Other (please specify)

[Answer]: A — **Mode:** 대리 판단. 스켈레톤이 마무리 인사 한 번 재생을 검증해야 하므로 C는 B1 완료 조건 ①을 채우지 못한다. B는 제작 방법을 U2 · U5와 D 문서가 정할 일로 남겨 둔 계약 11절의 열린 질문을 U1이 앞서 닫는다. 시드가 임시 WAV 둘이면 게이트 폐기 때 대체 문구도 재생할 수 있다(Q11). BR6.10 · BR7.4 · BR8.3 · BR8.4. **J60**.

## 3. 답 분석

- **모호함:** 없음. 숫자(500ms 폴링 · 800ms 구간 · 5회 재시도 · 7일 보관 · 기기 로그인 시도 제한)는 11/2 측정 뒤 바꿀 수 있는 시작값이다. 승인 음성의 정식 제작 방법(사전 녹음 · TTS)은 이 단계에서 정하지 않고 `functional-spec.md` 9절 열린 질문으로 U2 · U5에 남긴다(Q19는 임시 WAV로 진행).
- **모순 점검:** (1) 공통 완료 조건의 'Outbox 페이로드에 전사 원문 없음'과 계약 6절 SessionRecordCaptured의 대화 원문 → Q9로 풀었다(전달이 끝나면 비움). (2) J16의 '시간 규칙은 뒤 유닛'과 '계정당 1개' 잠금 방지 → Q10으로 최소 시한 4개만 앞당겼다. (3) 계약 11절의 '진행 중 「끄기」' 열린 질문 → Q12(B16)로 닫았다. (4) ERD v0.1 Outbox/Inbox에 계정 식별 열이 없어 삭제 참가자가 찾을 수 없음 → Q9에서 `guardian_id` 추가. (5) 계약 7절 `session.device-ack-timeout`은 ACK · DEVICE_READY만 덮고 공급자 session.started 대기 구간에는 시한이 없음 → Q10에서 20초가 ACTIVE까지 덮게 하고 계약에는 Q18 (h)로 더한다. (6) Core의 로컬 FAILED와 Realtime에 남은 세션(고아 세션 · 계정 잠김) → Q13. (7) 응답 없음(UNRESPONSIVE)의 소유가 U1과 U3로 갈림 → Q14로 U1이 명령 시한 설정 · 해제를 맡되, 쓰는 조건은 ACK · DEVICE_READY 빠짐과 종료 확인 없음으로 좁히고 같은 connection_id의 HEARTBEAT로도 푼다(연결 상태 기계는 SM2, 세션은 SM3). (8) 화면 설계 D-00의 'T19 대기'와 마이크 허용에 필요한 사용자 동작 → Q15. (9) 05는 U2 화면인데 U1 규칙은 05로 보냄 → Q16(스텁). (10) 공통 완료 조건의 계정 삭제 참가자(Device · 메시징 Core 쪽 · 메시징 Realtime 쪽 셋)와 아직 전달 중인 제어 이벤트의 보존 → Q17. (11) 구현이 승인된 계약보다 넓거나 구체화한 곳(오류 코드 · 순서 문장 · 시한 범위 · 연결 보고 필수 필드 · 토큰 결과 캐시 · 속도 제한 범위 · 연결 대체 시점 · 같은 보호자 재시도 응답, (a) ~ (m) 열세 가지) → Q18. (12) 시드가 승인 문구 둘을 요구하는데 음성 파일이 아직 없음 → Q3 · Q19(기본 종료 표현은 시드가 아니라 코드 상수). (13) **2차 수정 요지**: `rules.md`(89규칙)의 최종 문구에 맞춰 질문 답 · 근거를 고쳤다 — 코드 추첨 첫 추첨 포함 총 5번(Q4), 같은 보호자의 등록 재시도 201(Q5), 로컬 FAILED의 start_result_code 재생과 거절 전 Realtime 대조(Q7), IGNORED 처음부터 커밋과 시작 때 RECEIVED 당김(Q8), DEVICE_ACK = ACK만 · ACTIVE = DEVICE_READY + session.started · 세션 40초 시계는 Realtime 메모리(Q10), 취소 재전송(BR5.10 0행)(Q13), UNRESPONSIVE 조건 좁힘 · HEARTBEAT 복귀 · HELLO_OK 연결 대체(Q14), 계정 삭제 참가자 셋(Q17), J59 열세 가지(Q18), 기본 종료 표현을 시드에서 뺌(Q3). 기기 등록 상태는 4개(EXPIRED 포함)로 K1을 고쳤다.
- **후속 질문:** 없음.
