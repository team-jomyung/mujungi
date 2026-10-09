# U1 u1-walking-skeleton — 업무 규칙 (Functional Design 3.1)

> Functional Design(3.1) 산출물 · U1 워킹 스켈레톤 · 깊이 Standard · 2026-10-09 (클라우드 세션, 팀장 대리 판단)
> 입력: `unit-of-work.md`(U1 정의), `unit-of-work-story-map.md`(US1.1 · US1.4 · US2.1 · US2.2 · US3.1 · US3.3 · US6.1 · US6.2), `requirements.md`(FR2.1 ~ FR2.3 · FR7.1 · NFR1 · NFR3 · NFR6 · NFR9), `components.md`(DeviceRegistry · CoreMessaging · RealtimeMessaging · DeviceWeb · J1 ~ J14), `contract-summary.md`(C2 · C7 · C8 · C9 · C11 · 시간 계약 · J20 ~ J31), `mockups.md` · `interaction-spec.md`(화면 문구 · 상태), 질문 파일 `functional-design-questions.md`(Q1 ~ Q19, J42 ~ J60). 엔터티는 `entities.md`, 순서 있는 동작과 상태 기계는 `functional-spec.md`.
> 규칙 번호는 `BR{묶음}.{순번}`이다. 숫자 시간값은 `contract-summary.md` 7절 시간 계약에 키가 있으면 그 한 곳에만 두고 여기서는 설정 키 이름(`mujoong.timing.*`)으로 부른다. 7절에 키가 없는 값(내부 HTTP 제한 · 폴러 · 재시도 간격 등)은 BR8.11대로 `mujoong.*` 키를 NFR Design(3.3)이 확정한다. **스켈레톤 슬라이스**로 표시한 규칙은 U1이 최소만 구현하고 소유 유닛이 완성한다(J42).

## 1. 규칙 묶음

| 묶음 | 주제 | 규칙 |
|---|---|---|
| BR1 | 기기 부트스트랩 · 등록 코드 | BR1.1 ~ BR1.7 |
| BR2 | 보호자 등록(05a) · 재등록 | BR2.1 ~ BR2.10 |
| BR3 | 기기 토큰 · 인증 · 연결 대체 | BR3.1 ~ BR3.9 |
| BR4 | 연결 상태 사본 · 시작 가능 판정 | BR4.1 ~ BR4.8 |
| BR5 | 영속 메시징(Outbox/Inbox) | BR5.1 ~ BR5.14 |
| BR6 | 세션 슬라이스(시작 · 끝내기 · 안전 시한) | BR6.1 ~ BR6.17 |
| BR7 | 음성 · 게이트 슬라이스 | BR7.1 ~ BR7.6 |
| BR8 | 실행 골격 · 공통 | BR8.1 ~ BR8.11 |
| BR9 | 아이 기기 웹 D0 · T19 최소 | BR9.1 ~ BR9.7 |

## 2. 규칙 (source of truth)

```yaml
rules:
  # ═════════════ BR1 · 기기 부트스트랩 · 등록 코드 ═════════════
  - id: BR1.1
    statement: "등록 코드는 8글자이고 알파벳은 2~9와 A~Z 중 I · L · O를 뺀 31글자(대문자)다. 기기 화면은 4글자 · 4글자로 나눠 크게 보인다."
    category: "constraint"
    applies_to: "Device.registration_code, D0 화면"
    trigger: "코드를 발급할 때, 05a에서 코드를 받을 때"
    logic: "코드는 SecureRandom으로 31글자 중 8개를 뽑는다. 입력은 앞뒤 공백 · 가운데 공백 · 하이픈 · 가운뎃점을 지우고 대문자로 바꾼 뒤 비교한다. 화면 표시는 앞 4 + 뒤 4(예 7F3K · 92QD, C.D-00.30.3)다."
    violation: "발급 알파벳 밖의 글자를 가진 입력은 어떤 행과도 맞지 않아 BR2.1대로 CODE_NOT_FOUND가 된다."
    source: "FR2.1 · AC6.1.1 · AC6.1.5 · OQ13 · J22"
  - id: BR1.2
    statement: "발급 중(UNREGISTERED) 코드는 서로 겹치지 않는다."
    category: "constraint"
    applies_to: "Device"
    trigger: "새 코드를 발급할 때"
    logic: "UNREGISTERED 행끼리 부분 유일 색인이 있다. 뽑은 코드가 겹치면(유일 위반) 새로 뽑는다. 첫 추첨을 포함해 총 5번까지 시도한다."
    violation: "5번 모두 겹치면 503 CODE_ISSUE_FAILED(기기 웹은 5초 뒤 다시 요청한다)."
    source: "FR2.1 · J45 · J59(c)"
  - id: BR1.3
    statement: "같은 bootstrap 쿠키로 다시 요청하면 같은 코드를 돌려준다(새로고침해도 코드가 유지된다)."
    category: "policy"
    applies_to: "POST /api/device/bootstrap"
    trigger: "bootstrap 요청"
    logic: "쿠키의 비밀 해시로 행을 찾는다. 행이 UNREGISTERED이고 code_expires_at이 지나지 않았으면 같은 코드와 만료 시각을 돌려준다(201). 쿠키가 없거나 모르는 값이거나 행이 EXPIRED · RELEASED이면 새 행 · 새 코드와 새 쿠키를 만든다. 행이 이미 REGISTERED(등록됐지만 아직 claim 전)이면 409 ALREADY_REGISTERED로 답해 기기 웹이 claim으로 가게 한다."
    violation: "같은 쿠키에 코드가 바뀌면 보호자가 입력한 코드와 기기 화면이 어긋난다 — 허용하지 않는다."
    source: "AC6.1.5 · J45 · J59(a)"
  - id: BR1.4
    statement: "코드는 발급 뒤 24시간(bootstrap.code-ttl)만 유효하고, 만료된 UNREGISTERED 행은 EXPIRED로 바꿔 등록에 쓰이지 않게 한다."
    category: "constraint"
    applies_to: "Device.code_expires_at, Device.registration_status"
    trigger: "상태 조회 · 등록 요청이 만료된 행을 만날 때, 10분 정리 작업"
    logic: "now > code_expires_at 이고 UNREGISTERED이면 EXPIRED로 바꾼다(조회 때 또는 정리 작업). registration_code는 NOT NULL이라 값이 남지만 UNREGISTERED 부분 유일 색인에서 빠지므로 같은 코드를 새 발급에서 다시 뽑아도 된다. EXPIRED이면서 7일이 지난 행은 정리 작업이 삭제한다. 기기 웹은 EXPIRED · 401을 받으면 조용히 새 코드를 요청한다."
    violation: "만료 코드로 등록을 시도하면 CODE_NOT_FOUND(화면은 '없는 코드예요'와 같다)."
    source: "AC6.1.5 · OQ13 · J45"
  - id: BR1.5
    statement: "bootstrap 비밀은 서버가 만들고 기기에는 HttpOnly 쿠키로만 건네며 서버에는 SHA-256 해시만 저장한다."
    category: "constraint"
    applies_to: "Device.bootstrap_secret_hash, MUJOONG_DEVICE_BOOTSTRAP 쿠키"
    trigger: "코드 발급"
    logic: "비밀은 SecureRandom 128비트 이상 base64url이다. 쿠키 속성은 HttpOnly · Secure · SameSite=Strict · Path=/api/device · Max-Age=24시간이다. 비밀 원문은 응답 본문 · 로그 · DB에 두지 않는다."
    violation: "비밀이 본문 · 로그에 나가면 보안 결함으로 처리한다."
    source: "AC6.1.4 · NFR9 · J29"
  - id: BR1.6
    statement: "코드 발급은 IP당 1시간 20회(bootstrap.rate-limit)로 제한한다."
    category: "constraint"
    applies_to: "POST /api/device/bootstrap"
    trigger: "bootstrap 요청"
    logic: "IP는 Nginx가 `proxy_set_header X-Forwarded-For $remote_addr`로 덮어써 넘기는 값이다(클라이언트가 보낸 X-Forwarded-For는 이어 붙이지 않고 버린다, BR8.9). 앱은 Docker 내부망의 Nginx에서 온 요청에서만 이 헤더를 읽고, 없거나 주소 형식이 아니면 연결 원격 주소를 쓴다. IPv6는 앞 64비트로 묶어 센다. 창을 넘으면 429 RATE_LIMITED. 같은 쿠키의 반복 요청(BR1.3)은 횟수에 세지 않는다(코드 발급 남용만 막는다. 계약 7절 bootstrap.rate-limit의 적용 범위를 좁힌 것, J59(k)). 카운터는 앱 메모리이며 앱이 하나라 충분하고, 키 수에 상한을 두어 위조 키로 메모리가 차지 않게 한다."
    violation: "429. 아이 기기 화면에는 기술 문구를 보이지 않고 5초 뒤 다시 시도한다(BR9.4)."
    source: "NFR9 · J29 · J59(k)"
  - id: BR1.7
    statement: "등록 상태 조회는 상태와 만료 시각만 돌려주고 코드를 다시 보내지 않는다."
    category: "policy"
    applies_to: "GET /api/device/bootstrap/status"
    trigger: "기기 웹의 2초 폴링(bootstrap.status-poll)"
    logic: "쿠키 비밀로 행을 찾아 PENDING(UNREGISTERED) · REGISTERED · EXPIRED를 돌려준다. RELEASED 행(claim 전에 교체 등록으로 풀린 쿠키)도 EXPIRED로 돌려줘 기기 웹이 조용히 새 코드를 받게 한다. 쿠키가 없거나 모르는 값이면 401 BOOTSTRAP_NOT_FOUND. 쿠키는 HttpOnly라 기기 웹 JS는 있는지 볼 수 없으므로 항상 이 호출로 서버가 판정한다."
    violation: "401을 받은 기기 웹은 새 코드를 요청한다."
    source: "AC6.1.2 · J22"

  # ═════════════ BR2 · 보호자 등록(05a) · 재등록 ═════════════
  - id: BR2.1
    statement: "등록 요청의 코드는 정규화 뒤 영숫자 8글자여야 하고, 등록 가능한 행이 없으면 CODE_NOT_FOUND, 이미 등록된 코드면 CODE_ALREADY_REGISTERED다."
    category: "validation"
    applies_to: "POST /api/device/register"
    trigger: "05a 「등록」"
    logic: "정규화(BR1.1) 뒤 길이가 8이 아니거나 영숫자가 아니면 400 VALIDATION_FAILED(화면은 8칸이 차야 단추가 켜져 이 경우가 생기지 않는다). 형식이 맞으면 ① UNREGISTERED이고 code_expires_at이 지나지 않은 행을 코드로 찾는다(부분 유일 색인이라 최대 1행) — 있으면 BR2.5(또는 활성 기기가 없으면 BR2.3)로 진행한다. ② 없으면 같은 코드의 REGISTERED 또는 RELEASED 행이 있는지 본다 — REGISTERED이고 guardian_id가 요청한 보호자와 같으면(등록은 커밋됐는데 응답이 유실돼 같은 코드로 다시 보낸 경우) 첫 성공과 같은 201 {deviceId, codePrefix, registeredAt}로 답하고 BR2.6의 실패 횟수에 세지 않는다(J59(m)). 그 밖에 있으면 409 CODE_ALREADY_REGISTERED. ③ 그 밖(없는 코드 · 만료된 코드 · EXPIRED · 발급 알파벳 밖 글자(0 · 1 · I · L · O)를 포함한 코드)은 404 CODE_NOT_FOUND다."
    violation: "404이면 05a에 빨간 카드 '없는 코드예요', 409이면 빨간 카드 '이미 등록된 코드예요'(mockups 9-10)가 보이고 둘 다 다시 입력할 수 있다."
    source: "AC2.2.3 · FR2.1 · J59(d)(m)"
  - id: BR2.2
    statement: "등록하는 주체는 로그인한 보호자이고, 계정 삭제가 접수된 보호자는 등록할 수 없다."
    category: "authorization"
    applies_to: "POST /api/device/register"
    trigger: "등록 요청"
    logic: "세션의 guardianId로만 동작한다. 요청 본문에 보호자 ID를 받지 않는다. guardian.status가 DELETION_REQUESTED이면 403 ACCOUNT_DELETION_IN_PROGRESS. 이 확인은 빠른 거절용으로 트랜잭션 밖에서 먼저 하고, 등록 트랜잭션 처음에 보호자 행을 `SELECT ... FOR SHARE`로 다시 읽어 ACTIVE일 때만 진행한다(삭제 접수는 같은 행을 갱신하므로 접수와 등록 커밋이 겹쳐도 삭제된 계정에 REGISTERED 기기가 남지 않는다). 락 순서는 보호자 행 → 코드 행 → 옛 기기 행이다(BR2.5)."
    violation: "401(세션 없음) · 403."
    source: "NFR9 · AC1.1.8"
  - id: BR2.3
    statement: "한 코드는 한 계정만 등록할 수 있고, 동시에 두 계정이 같은 코드를 등록하면 한 건만 성공한다."
    category: "constraint"
    applies_to: "Device.registration_status"
    trigger: "등록 요청"
    logic: "한 트랜잭션에서 조건부 갱신 ‘UPDATE core_device SET guardian_id = :g, registration_status = REGISTERED, registered_at = now WHERE id = :id AND registration_status = UNREGISTERED AND code_expires_at > now’ 의 갱신 행 수가 1일 때만 성공이다. 0이면 행을 다시 읽어 BR2.1의 ② ③ 분기(같은 보호자의 REGISTERED이면 201, 그 밖의 REGISTERED · RELEASED이면 409 CODE_ALREADY_REGISTERED, 나머지는 404)로 답한다. 교체 경로에서 0행이면 BR2.5대로 옛 기기 해제까지 모두 되돌린다."
    violation: "진 쪽은 '이미 등록된 코드' 빨간 카드를 보고 다시 입력할 수 있다. Device 행은 하나다."
    source: "AC2.2.2 · AC2.2.3 · AC2.2.6"
  - id: BR2.4
    statement: "계정당 활성 기기는 하나이고, 활성 기기가 있어도 지금 연결돼 있지 않으면 새 코드 등록을 받는다."
    category: "constraint"
    applies_to: "Device.guardian_id"
    trigger: "등록 요청에서 같은 보호자의 REGISTERED 기기가 있을 때"
    logic: "교체 허용 여부는 BR4.3의 판정을 그대로 쓴다. 옛 기기 판정이 STABLE(CONNECTED이고 30초 안에 보고가 있음) 또는 UNSTABLE이면 409 DEVICE_ALREADY_ACTIVE로 거절한다. 그 밖의 모든 경우 — 한 번도 보고되지 않은 기기(사본 없음, BR4.3 ②의 UNRESPONSIVE) · OFFLINE · DISCONNECTED · UNRESPONSIVE · 30초 넘게 보고 없음(UNKNOWN) — 는 교체를 허용하고 BR2.5를 따른다. 보호자당 REGISTERED 하나의 부분 유일 색인이 마지막 방어선이며 그 위반(23505)과 옛 기기 조건부 해제의 0행은 409 DEVICE_ALREADY_ACTIVE로 번역한다."
    violation: "409 DEVICE_ALREADY_ACTIVE. 연결된 기기를 실수로 풀지 않는다."
    source: "AC2.2.7 · AC6.1.3 · B3 · J13 · J46"
  - id: BR2.5
    statement: "교체 등록은 새 기기 등록과 옛 기기 해제를 한 트랜잭션에서 하고 해제 사실을 Realtime에 알린다."
    category: "policy"
    applies_to: "Device, CoreOutboxEvent"
    trigger: "BR2.4가 교체를 허용했을 때"
    logic: "한 트랜잭션에서 순서대로 한다. (0) 보호자 행을 FOR SHARE로 읽어 ACTIVE인지 확인(BR2.2). (1) 코드의 UNREGISTERED 행을 FOR UPDATE로 잠그고 만료 전인지 본다. (2) 보호자의 옛 REGISTERED 행이 있으면 FOR UPDATE로 잠그고 BR2.4를 이 시점의 연결 상태 사본으로 다시 판정한다(연결돼 있으면 409 DEVICE_ALREADY_ACTIVE). (3) 옛 행을 조건부로 해제한다 — ‘UPDATE core_device SET registration_status = RELEASED, release_reason = REREGISTERED, released_at = now, device_token_hash = NULL WHERE id = :옛 AND registration_status = REGISTERED’, 갱신 행 수가 1이어야 하고 같은 트랜잭션에서 CoreOutboxEvent DeviceReleased(deviceId = 옛 기기, guardianId, reason = REREGISTERED)를 저장한다. (4) 마지막으로 새 행을 BR2.3의 조건부 UPDATE로 REGISTERED로 바꾼다. 보호자당 REGISTERED 하나의 부분 유일 색인은 문장마다 즉시 검사되고 지연시킬 수 없으므로 (3)이 반드시 (4)보다 먼저다. (4)가 0행이거나 어느 단계든 예외면 트랜잭션 전체를 되돌려 옛 기기는 풀리지 않는다."
    violation: "부분 적용은 없다 — 옛 토큰이 살아 있는 채로 새 기기가 REGISTERED가 되거나, 새 기기 등록이 실패했는데 옛 기기만 풀리는 일이 없다."
    source: "AC6.1.3 · J13 · J46"
  - id: BR2.6
    statement: "등록 실패가 보호자당 10분에 10번을 넘으면 그 보호자의 등록 요청을 잠시 거절한다."
    category: "constraint"
    applies_to: "POST /api/device/register"
    trigger: "등록 요청이 400 · 404 · 409로 끝날 때마다"
    logic: "(device.register-failure-limit) 실패 횟수를 보호자별 슬라이딩 창으로 센다. 창을 넘기면 429 TOO_MANY_ATTEMPTS. 성공하면 카운터를 지운다. 31^8 코드 공간 맞히기를 막는 장치다."
    violation: "429. 05a는 일반 오류 카드를 보인다."
    source: "NFR9 · J29"
  - id: BR2.7
    statement: "등록이 성공하면 기기 ID · 코드 앞 4글자 · 등록 시각을 돌려주고 보호자 화면은 05로 간다. 기기 쪽은 상태 폴링으로 안다."
    category: "policy"
    applies_to: "POST /api/device/register 응답"
    trigger: "등록 성공"
    logic: "201 {deviceId, codePrefix(코드 앞 4글자), registeredAt}. 05의 등록 줄은 '완료 (코드 앞 4자리)'다. 기기 쪽 「등록되었어요」(3초)는 기기 웹이 폴링으로 REGISTERED를 본 뒤 보인다(BR9.2)."
    violation: "—"
    source: "AC2.2.2 · AC2.1.2"
  - id: BR2.8
    statement: "05a 화면 규칙 — 8칸(4 + 4)이 다 차야 「등록」이 켜지고, 전송 중에는 꺼지며, 실패해도 다시 입력할 수 있고, 「취소」 · 「←」는 05로 돌아가며, 기기 검색 · 페어링 · 해제 단추는 없다."
    category: "policy"
    applies_to: "ParentWeb 05a (U1 최소 화면)"
    trigger: "05a 표시와 조작"
    logic: "칸마다 터치 영역 44px 이상. 8칸은 OTP식이다: 자동 대문자, 입력하면 다음 칸으로, 지우면 이전 칸으로, 8자 붙여넣기를 지원한다(BR1.1 정규화와 같다). 8칸 미만이면 「등록」 꺼짐. 요청 중 「등록」 꺼짐 + '등록하고 있어요'(mockups 9-9). 없는 코드(404) · 이미 등록된 코드(409, mockups 9-10) · 429는 빨간 카드로 알리고 입력을 지우지 않는다. 네트워크 오류 · 5xx는 토스트 + 「다시 시도」다. 기기 해제 · 페어링 단추를 두지 않는다. 문구표(C.S-05A.8.x)에 없는 문구는 mockups.md 9절의 제안 문구를 임시로 쓴다."
    violation: "—"
    source: "AC2.2.1 · AC2.2.3 · AC2.2.4 · AC2.2.5 · AC2.2.8"
  - id: BR2.9
    statement: "05a에는 등록되지 않은 보호자만 들어온다. 활성 기기가 있는 보호자의 화면에는 진입 경로가 없고, 서버는 BR2.4로 막는다."
    category: "policy"
    applies_to: "ParentWeb 04 · 05, POST /api/device/register"
    trigger: "「기기 등록」 단추 표시, 등록 요청"
    logic: "04 대화 기기 구역은 등록이 없을 때만 「기기 등록」을 보인다(AC1.4.4). 재등록 진입 단추는 화면 설계에 없다(OQ12, Should) — 서버 규칙(BR2.4 · BR2.5)만 준비한다."
    violation: "—"
    source: "AC2.2.7 · AC1.4.4 · B3 · OQ12"
  - id: BR2.10
    statement: "Device 계정 삭제 참가자는 기기를 해제하고 해제 사실을 알린 뒤 그 계정의 기기 행을 모두 지운다."
    category: "constraint"
    applies_to: "Device, CoreOutboxEvent (DeletionParticipant 계약 8절)"
    trigger: "계정 삭제 지휘(U2)가 deleteByGuardian(guardianId, deletionRequestId)을 부를 때"
    logic: "멱등이다. 한 트랜잭션에서 (1) REGISTERED 행이 있으면 RELEASED(release_reason ACCOUNT_DELETED, released_at, device_token_hash 비움)로 바꾸고 DeviceReleased(reason ACCOUNT_DELETED) Outbox를 쓴다. (2) guardian_id가 같은 core_device 행을 상태와 상관없이 모두 삭제한다(재등록으로 RELEASED가 된 옛 행 포함). 등록 전 EXPIRED · UNREGISTERED 행은 guardian_id가 없어 대상이 아니며 7일 정리 작업(BR1.4)이 지운다. DeviceReleased 처리에 필요한 deviceId는 Outbox payload가 가지므로 행이 없어도 Realtime은 연결을 닫는다. 두 번 불러도 같은 결과이고 반환은 {deleted: {device: n}}이다. 호출 순서: core_device를 가리키는 core_activity(U4 참가자)가 먼저 지워져야 하고 보호자 행 삭제(U2)는 맨 뒤다. core_device.guardian_id와 core_activity.device_id의 외래 키는 ON DELETE 없이(RESTRICT) 이 순서를 강제한다."
    violation: "순서를 어기면 외래 키 위반으로 삭제 지휘가 실패하고 다시 시도된다(멱등)."
    source: "AC1.6.6(U2 소관) · AC2.2.5 · 공통 완료 조건 · J19 · J58"

  # ═════════════ BR3 · 기기 토큰 · 인증 · 연결 대체 ═════════════
  - id: BR3.1
    statement: "등록된 기기는 claim으로 기기 토큰을 한 번 받고, 첫 연결 전에는 다시 받을 수 있다(응답 유실 대비). 토큰은 쿠키로만 간다."
    category: "policy"
    applies_to: "POST /api/device/bootstrap/claim"
    trigger: "기기 웹이 REGISTERED를 본 뒤"
    logic: "쿠키의 bootstrap 비밀이 REGISTERED 행과 맞고 first_connected_at이 비어 있어야 한다. 새 토큰(SecureRandom 256비트, base64url)을 만들어 SHA-256 해시를 저장하고(앞 토큰 해시는 덮어써 무효), Set-Cookie MUJOONG_DEVICE(HttpOnly · Secure · SameSite=Strict · Path=/ · Max-Age 400일)를 내려 보내며 bootstrap 쿠키는 지운다. 응답 본문은 {deviceId}뿐이다."
    violation: "PENDING이면 409 NOT_REGISTERED_YET, 쿠키가 없거나 모르는 비밀이면 401 BOOTSTRAP_NOT_FOUND(계약에 더함), 행이 EXPIRED · RELEASED이거나 first_connected_at이 있으면 410 BOOTSTRAP_CLOSED(BR3.2)."
    source: "AC6.1.4 · AC2.2.5 · J29 · J59(b)"
  - id: BR3.2
    statement: "첫 WSS 인증이 성공하면 claim은 닫힌다."
    category: "constraint"
    applies_to: "Device.first_connected_at"
    trigger: "verify-token 첫 성공, 그 뒤의 claim 요청"
    logic: "first_connected_at이 채워진 행, 그리고 EXPIRED · RELEASED 행에 claim이 오면 410 BOOTSTRAP_CLOSED. 기기 웹은 /device/me로 현재 토큰이 유효한지 확인한다(200이면 T19, 401이면 새 bootstrap)."
    violation: "410."
    source: "J29"
  - id: BR3.3
    statement: "기기 토큰에는 유효 기간을 두지 않고, RELEASED · 계정 삭제 · 첫 연결 전 claim 재호출(BR3.1의 회전)로만 무효가 된다. 서버는 해시만 저장하고 비교는 상수 시간으로 한다."
    category: "constraint"
    applies_to: "Device.device_token_hash"
    trigger: "토큰 확인 · 해제"
    logic: "해제하면 device_token_hash를 비우고, 회전은 device_token_hash를 새 값으로 덮어 앞 토큰을 무효로 만든다. 쿠키 Max-Age(400일)는 브라우저 쪽 수명이며 /device/me 성공 때마다 다시 늘린다(BR3.5). 서버는 토큰 원문을 저장하지 않는다."
    violation: "—"
    source: "AC2.2.5 · AC6.1.4 · B4"
  - id: BR3.4
    statement: "토큰 확인(내부)은 REGISTERED 행의 토큰과 맞을 때만 기기 · 보호자 · 아이를 돌려준다."
    category: "authorization"
    applies_to: "POST /internal/devices/verify-token"
    trigger: "Realtime이 WSS 접속에서 쿠키 토큰을 받았을 때"
    logic: "해시가 REGISTERED 행과 맞으면 200 {deviceId, guardianId, childId, registrationStatus: REGISTERED}를 돌려주고 first_connected_at이 비어 있으면 채운다. 맞는 행이 없거나 RELEASED이면 401 INVALID_DEVICE_TOKEN. 결과는 연결 하나에만 묶고 연결마다 다시 확인한다(캐시하지 않는다 — 계약 5절의 '결과 캐시' 문장 대신 연결마다 Core에 확인한다, J59(j)). 응답 제한 시간은 1초(J26)이고 설정 키 이름은 NFR Design(3.3)이 확정한다."
    violation: "Realtime은 업그레이드 뒤 4401로 닫는다(BR3.6)."
    source: "AC2.2.5 · AC6.2.8 · J29 · J59(j)"
  - id: BR3.5
    statement: "기기 웹은 시작할 때 /device/me로 자기 등록을 확인하고, 유효하면 쿠키 수명이 다시 400일로 늘어난다."
    category: "policy"
    applies_to: "GET /api/device/me"
    trigger: "기기 웹 시작, WSS 4401 뒤"
    logic: "유효한 토큰이면 200 {deviceId, registeredAt}과 MUJOONG_DEVICE 쿠키 재설정. 쿠키가 없거나 모르는 토큰 · RELEASED이면 401 DEVICE_NOT_REGISTERED → D0. 아이 정보는 돌려주지 않는다."
    violation: "401이면 D0."
    source: "AC6.2.8 · AC6.1.4"
  - id: BR3.6
    statement: "기기 WSS는 허용 Origin과 유효한 기기 쿠키를 가진 접속만 받고, 토큰 · 비밀을 URL · HELLO · 로그에 넣지 않는다."
    category: "authorization"
    applies_to: "WSS /ws/device (DeviceGateway 스켈레톤 슬라이스)"
    trigger: "Handshake"
    logic: "① Origin이 허용 목록(서비스 origin 하나, 환경 변수)에 없으면 403으로 업그레이드하지 않는다. ② 업그레이드를 받은 뒤 쿠키 토큰을 BR3.4로 확인한다. 실패하면 4401로 닫고, Core가 1초 안에 답하지 않으면 1013으로 닫는다. ③ 업그레이드 뒤 5초(device.hello-timeout) 안에 HELLO가 없으면 4408. ④ HELLO 형식이 틀리면 4400. 브라우저는 실패한 Handshake의 HTTP 상태를 읽지 못하므로 4401 · 1013으로 기기 웹이 '다시 등록'과 '네트워크 문제'를 가른다."
    violation: "위 닫기 코드. 닫기 코드 표는 contract-summary.md 4.2절."
    source: "AC2.2.5 · AC6.2.8 · NFR9 · J29"
  - id: BR3.7
    statement: "같은 기기의 새 연결이 HELLO를 수락받아 HELLO_OK를 받는 시점에 옛 연결을 닫고 새 연결로 바꾼다. 닫힌 쪽은 스스로 다시 접속하지 않는다."
    category: "policy"
    applies_to: "DeviceConnection.connection_id"
    trigger: "HELLO 수락(HELLO_OK)"
    logic: "HELLO_OK를 보내는 시점(BR4.8 ① CONNECTED와 같은 시점)에 한 트랜잭션에서 기기의 DeviceConnection 행을 `FOR UPDATE`로 잠그고(첫 접속이면 `INSERT ... ON CONFLICT (device_id) DO UPDATE`), 읽은 옛 connection_id가 있고 그 소켓이 살아 있으면 4001 REPLACED를 보내 닫은 뒤 connection_id · connection_state(CONNECTED) · connection_since를 새 값으로 덮고 DeviceStatusChanged를 같은 트랜잭션에 쓴다. 잠금을 나중에 얻은 연결이 이기며 기대값 비교는 하지 않는다. verify-token만 통과하고 HELLO가 없거나 틀려 4408 · 4400으로 닫히는 연결은 옛 연결을 건드리지 않는다. 4001을 받은 기기 웹은 꺼짐 상태로 두고 자동 재접속하지 않는다(두 창이 서로 밀어내는 반복 방지). 교체 이후의 DISCONNECTED · UNRESPONSIVE · OFFLINE 기록과 해당 DeviceStatusChanged는 ‘WHERE device_id = :d AND connection_id = :cid’ 조건부로 하고 행 수가 0이면 기록 없이 버린다 — 4001로 대체돼 닫힌 옛 연결과 HELLO_OK 전에 닫힌 연결(1013 · 4400 · 4401 · 4408)의 close 처리가 새 연결의 상태를 덮지 않는다(DeviceReleased 처리 BR3.8은 이 조건 대신 device_id만 조건으로 한다). 진행 중 세션에 새 연결을 다시 붙이는 이어가기(HELLO resume)는 U3가 완성한다. U1에서는 새 연결이 진행 중 세션에 자동으로 붙지 않으며 그 세션의 40초 시계는 BR6.12 ④를 따른다."
    violation: "—"
    source: "AC6.2.10 · OQ13 · J22 · J59(l)"
  - id: BR3.8
    statement: "기기가 해제되면 Realtime은 그 기기의 연결을 닫고 이후 접속을 받지 않는다."
    category: "policy"
    applies_to: "RealtimeInboxEvent DeviceReleased 처리기"
    trigger: "DeviceReleased 수신(재등록 · 계정 삭제)"
    logic: "처리기는 멱등이다. 해당 deviceId의 열린 연결에 4401을 보내 닫고 DeviceConnection을 OFFLINE으로 바꾸며(‘WHERE device_id = :d’만 조건으로 하고 connection_id를 비운다 — 재시작 뒤 DISCONNECTED 행도 포함, 상태 이벤트는 BR4.8), 이후 verify-token이 401이라 접속이 거부된다."
    violation: "—"
    source: "AC6.1.3 · AC2.2.5 · AC6.2.8 · J13"
  - id: BR3.9
    statement: "앱 사이 내부 HTTP는 공유 비밀 헤더로 확인하고 사용자 권한으로 쓰지 않으며 밖에서 닿지 않는다."
    category: "authorization"
    applies_to: "/internal/** (Core · Realtime 양쪽)"
    trigger: "내부 호출 수신"
    logic: "X-Internal-Token(환경 변수 공유 비밀)이 없거나 다르면 401. 이 헤더가 있어도 보호자 · 기기의 권한이 생기지 않는다(주체는 본문의 검증된 ID일 뿐 다시 대조한다). Nginx가 /internal/** 을 바깥에서 막고 Docker 내부망에서만 닿는다."
    violation: "401."
    source: "NFR9 · ADR-009 · J26"

  # ═════════════ BR4 · 연결 상태 사본 · 시작 가능 판정 ═════════════
  - id: BR4.1
    statement: "Core는 Realtime이 보고한 연결 상태의 최신 값만 사본으로 가진다. 늦거나 같은 시각의 보고는 무시한다."
    category: "constraint"
    applies_to: "Device.reported_connection_state 외 사본 열"
    trigger: "PUT /internal/devices/{id}/connection, DeviceStatusChanged 이벤트 처리"
    logic: "보고의 시각(보고는 reportedAt, 이벤트는 Inbox 행의 occurred_at)이 Device.reported_at보다 클 때만 덮어쓴다. 읽고 비교하고 쓰지 않고 한 문장의 조건부 UPDATE로 한다: ‘UPDATE core_device SET reported_connection_state = :state, rtt_ms = :rtt, recent_disconnect_count = :n, connection_since = :since, last_seen_at = :seen, reported_at = :t WHERE id = :id AND registration_status = REGISTERED AND (reported_at IS NULL OR reported_at < :t)’. 갱신 행 수가 0이면 버린다(204, 이벤트는 PROCESSED). 두 쓰기 경로(HTTP 보고와 이벤트 처리기)가 겹쳐도 reported_at이 줄지 않는다. 보고는 유실돼도 된다(다음 보고가 덮는다). 보고 필드 → 열: state → reported_connection_state, rttMs → rtt_ms, recentDisconnects → recent_disconnect_count, since → connection_since, lastSeenAt → last_seen_at. PUT 본문의 state와 reportedAt은 필수이고 없으면 400 INVALID_ENVELOPE다(J59(i)). rttMs · recentDisconnects · lastSeenAt이 없으면 SET절에서 COALESCE(:x, 기존 열)로 기존 값을 유지하고 NULL을 쓰지 않는다(recent_disconnect_count는 NOT NULL). 이벤트 경로(DeviceStatusChanged)에는 reportedAt이 없어 :t에 Inbox 행의 occurred_at을 쓴다."
    violation: "—"
    source: "AC2.1.10 · B6 · J47 · J59(g)(i)"
  - id: BR4.2
    statement: "등록되지 않았거나 해제된 기기의 보고는 반영하지 않는다."
    category: "constraint"
    applies_to: "Device"
    trigger: "연결 상태 보고 · 이벤트"
    logic: "registration_status가 REGISTERED가 아니면 무시한다(204). 늦은 보고가 해제된 기기를 되살리지 않는다."
    violation: "—"
    source: "J13 · J47"
  - id: BR4.3
    statement: "05 · 04의 5단계 표시와 시작 판정은 읽을 때 등록 축 + 사본 + 최신성으로 계산한다."
    category: "calculation"
    applies_to: "DeviceView, DeviceReadiness"
    trigger: "GET /api/device, GET /api/home, 시작 검사"
    logic: |
      계산 순서(첫 해당이 이긴다):
      ① 활성(REGISTERED) 기기가 없다 → network NONE · canStart false · reasonCode NO_DEVICE
      ② 사본이 없다(한 번도 보고되지 않음) → UNRESPONSIVE · DEVICE_UNRESPONSIVE (마지막 확인 없음)
      ③ now - reported_at 이 core.device-status-stale(30초)을 넘는다 → UNKNOWN · DEVICE_STATUS_UNKNOWN ("기기 상태 확인 불가" + 마지막 확인 시각)
      ④ CONNECTED → STABLE · canStart true
      ⑤ UNSTABLE → UNSTABLE · DEVICE_UNSTABLE
      ⑥ DISCONNECTED → DISCONNECTED · DEVICE_DISCONNECTED
      ⑦ UNRESPONSIVE 또는 OFFLINE(아이가 「끄기」로 끔) → UNRESPONSIVE · DEVICE_UNRESPONSIVE

    violation: "—"
    source: "AC1.4.3 · AC2.1.1 · AC2.1.3 ~ AC2.1.6 · AC2.1.10 · AC2.1.11 · B6 · B7 · 기기 상태 대응표(OQ11) · J47"
  - id: BR4.4
    statement: "시작 가능은 등록돼 있고 판정이 안정일 때만이다."
    category: "policy"
    applies_to: "DeviceReadiness.get, 자유대화 · 연습 탭"
    trigger: "시작 검사, 04 · 05 표시"
    logic: "canStart = (활성 기기 있음) ∧ (BR4.3 판정 STABLE). 불안정 → 안정 복귀 조건(최근 5분 창 끊김 2회 미만 ∧ 지연 1초 이하)은 연결 판정 주인(DeviceGateway, U3)이 STATE를 CONNECTED로 바꿀 때 적용되고 Core는 그 값만 본다."
    violation: "시작 요청은 BR6.1대로 DEVICE_NOT_STABLE(423)."
    source: "AC1.4.2 ~ AC1.4.4 · AC2.1.10 · AC3.1.6 · B7"
  - id: BR4.5
    statement: "04 · 05의 탭은 시작 가능일 때만 열리고, 잠기면 이유 토스트 뒤 05로 간다."
    category: "policy"
    applies_to: "ParentWeb 하단 탭(자유대화 · 연습)"
    trigger: "탭 표시와 누름"
    logic: "canStart가 true일 때만 탭이 열려 있고 자유대화는 08로 간다(연습은 U5). 잠기면 탭은 보조기기에도 '잠김'으로 읽히고 누르면 사유 한 줄 토스트(mockups 9-6)를 보인 뒤 05로 간다. 05는 U2가 화면을 만들기 전까지 /parent/device 스텁이 04로 되돌린다(J57)."
    violation: "—"
    source: "AC1.4.2 ~ AC1.4.4 · AC1.4.9 · AC1.4.11 · AC1.4.15 · J57"
  - id: BR4.6
    statement: "04 대화 기기 구역과 05 값은 등록 줄 · 네트워크 줄 · 판정으로 구성되고 색 + 상태명 + 사유를 함께 보인다."
    category: "policy"
    applies_to: "GET /api/home, GET /api/device 응답, 04 대화 기기 구역(U1 최소)"
    trigger: "04 · 05 표시"
    logic: |
      DeviceView = { registration{status NONE|REGISTERED, codePrefix, registeredAt}, network{state, rttMs, recentDisconnects, since, lastSeenAt, lastReportedAt}, verdict{canStart, reasonCode} } (contract-summary.md 3절).
      표시: 안정 = 초록 '안정' + 마지막 확인 hh:mm + 「기기 상태 보기 ›」, 불안정 = 노랑 + '안정될 때까지 시작할 수 없어요' + 「기기 상태 보기 ›」, 끊김 = 빨강 + '전원 · 와이파이를 확인해 주세요' + 「기기 상태 보기 ›」, 응답 없음 = 회색 + '기기를 다시 켜 보세요' + 마지막 hh:mm + 「기기 상태 보기 ›」, 확인 불가 = '기기 상태 확인 불가' + 마지막 확인 시각 + 「기기 상태 보기 ›」, 등록 없음 = '연결된 기기 없음' + '아이 기기에 뜬 코드로 등록해요.' + 「기기 등록」(→ 05a, 「기기 상태 보기」는 두지 않는다). 「기기 상태 보기 ›」는 등록된 기기라면 어느 상태든 05로 간다. 색만으로 구분하지 않는다.
      응답에 판정값 · 전사 · 비밀(해시 포함)을 넣지 않는다.

    violation: "—"
    source: "AC1.4.2 ~ AC1.4.5 · AC1.4.15 · AC2.1.1 ~ AC2.1.6 · AC2.1.14 · NFR9"
  - id: BR4.7
    statement: "진행 중 세션은 연결 상태 사본의 불안정 · 응답 없음만으로 중단되지 않는다. 세션을 움직이는 연결 사건은 마지막 기기 메시지 뒤 40초(DISCONNECT)뿐이고, 기기 「끄기」(close 1000)는 연결 상태만 OFFLINE으로 구분해 같은 40초 경로를 탄다."
    category: "constraint"
    applies_to: "SessionLifecycle, 연결 상태 사본"
    trigger: "연결 상태가 바뀔 때"
    logic: "Core 사본의 상태 변화는 세션 전이를 일으키지 않는다. 세션 전이는 Realtime이 BR6.12의 시한으로만 한다."
    violation: "—"
    source: "AC2.1.11 · B7 · B16 · J53"
  - id: BR4.8
    statement: "Realtime은 연결 상태가 바뀔 때 DeviceStatusChanged 이벤트를 쓰고, 영속 연결 행 전부를 OFFLINE까지 10초마다 Core에 보고한다."
    category: "policy"
    applies_to: "DeviceGateway 스켈레톤 슬라이스, RealtimeOutboxEvent"
    trigger: "상태 변경, 10초 주기(device.status-report-interval)"
    logic: "바뀔 때: 상태 변경과 같은 트랜잭션에서 RealtimeOutboxEvent DeviceStatusChanged. 주기: PUT /internal/devices/{id}/connection(제한 1초, 유실 허용). U1 슬라이스가 쓰는 상태는 OFFLINE · CONNECTED · DISCONNECTED와 UNRESPONSIVE다. ① CONNECTED: 인증된 연결이 HELLO를 보내 HELLO_OK를 받을 때(인증만 된 5초 동안은 아니다). ② 연결이 닫힐 때: 닫기 프레임을 받은 정상 닫기(기기 「끄기」 1000 DEVICE_OFF, 브라우저의 페이지 이탈 1001)는 즉시 OFFLINE, 닫기 프레임 없이 끊긴 연결(1006 등)과 소켓은 열려 있지만 40초 동안 메시지가 없는 연결은 마지막 기기 메시지 뒤 40초(device.disconnect)에 DISCONNECTED가 된다 — 기록은 ‘WHERE device_id = :d AND connection_id = :cid AND last_seen_at <= :점검시각 − 40초’ 조건부라 점검이 읽은 뒤 도착한 HEARTBEAT를 덮지 않는다. 서버가 보내는 1001(재시작 · 배포)은 해당하지 않는다. ③ UNRESPONSIVE: START_SESSION 뒤 20초 안에 ACK 또는 DEVICE_READY가 빠졌을 때(BR6.12 ②) 또는 CLOSING 뒤 30초 안에 VOICE_SESSION_CLOSED가 없을 때(③)만 쓴다 — 둘 다 도착했는데 공급자 쪽 문제로 실패한 경우에는 쓰지 않는다. UNRESPONSIVE 또는 DISCONNECTED인 연결이라도 소켓이 같은 connection_id로 살아 있어 다음 HEARTBEAT가 오면 CONNECTED로 되돌리고(‘WHERE connection_id = :cid’), 새 연결의 HELLO_OK(BR3.7)도 CONNECTED로 만든다(B7, J55). 기기 연결 상태의 40초 끊김 판정은 연결별 메모리 시계가 아니라 영속 last_seen_at을 기준으로 주기 점검한다(점검 주기는 설정 키). 세션의 40초 시계(BR6.12 ④)는 이와 별개로 Realtime 메모리의 세션별 값이다. 시각의 뜻: 보고의 reportedAt은 그 보고가 담은 연결 행을 읽은 시각(전송 시각 아님)이고 DeviceStatusChanged.occurred_at은 상태 UPDATE 직전에 Realtime 앱 Clock으로 읽은 시각(트랜잭션 시작 시각 now()가 아님)이며 둘은 같은 Realtime 시계를 쓴다. UNSTABLE 판정 · 5분 창 · 지연 1초 · 불안정 → 안정 복귀 조건(히스테리시스)은 U3가 완성한다."
    violation: "보고가 30초 넘게 끊기면 Core는 BR4.3 ③으로 '확인 불가'를 보인다."
    source: "AC2.1.10 · AC2.1.5 · AC2.1.6 · B6 · B10 · J47 · J55"

  # ═════════════ BR5 · 영속 메시징 (Outbox/Inbox) ═════════════
  - id: BR5.1
    statement: "이벤트는 업무 변경과 같은 로컬 트랜잭션에서 Outbox에 저장하고, 발송은 트랜잭션 밖에서 한다."
    category: "constraint"
    applies_to: "CoreMessaging, RealtimeMessaging"
    trigger: "이벤트를 만드는 업무 변경"
    logic: "업무 행 변경과 Outbox 행(status PENDING, available_at = now, payload_hash 계산)을 한 트랜잭션에 쓴다. 커밋 전에는 발송하지 않는다. 트랜잭션이 되돌려지면 이벤트도 사라진다."
    violation: "업무는 바뀌었는데 이벤트가 없는 상태를 만들지 않는다."
    source: "ADR-010 · C11"
  - id: BR5.2
    statement: "발송 폴러는 500ms마다 보낼 때가 된 PENDING 행을 만든 순서로 20개까지 직렬로 보낸다. 재시도 대기 중인 행이 뒤 행을 막지 않는다."
    category: "policy"
    applies_to: "Outbox 폴러"
    trigger: "500ms 주기"
    logic: "대상은 status PENDING 이고 available_at <= now. 한 건씩 상대 앱의 POST /internal/events(제한 3초)로 보낸다. 순서는 받는 쪽이 statusVersion · 시각으로 가린다(BR5.10)."
    violation: "—"
    source: "ADR-010 · J26 · J49"
  - id: BR5.3
    statement: "발송 결과에 따라 PUBLISHED · 재시도 · FAILED로 간다."
    category: "policy"
    applies_to: "Outbox 상태"
    trigger: "발송 응답"
    logic: "200 → PUBLISHED(published_at, BR5.11로 payload 비움). 409 EVENT_CONFLICT · 400 INVALID_ENVELOPE(재시도해도 같은 결과) → 즉시 FAILED + 경고 로그. 401을 포함한 시간 초과 · 연결 실패 · 나머지 4xx · 5xx → retry_count + 1, available_at = now + {1,2,4,8,16,30,30,…}초(401은 배포 중 두 앱의 비밀이 잠시 어긋난 일시 상태일 수 있다). retry_count가 20인 행이 또 실패하면 FAILED + 경고 로그(retry_count는 20으로 남는다)."
    violation: "FAILED는 사람이 로그로 확인한다(자동 재발송 없음)."
    source: "ADR-010 · J26 · J49"
  - id: BR5.4
    statement: "수신 쪽은 내부 인증과 봉투(envelope)를 확인한 뒤에만 받는다."
    category: "validation"
    applies_to: "POST /internal/events"
    trigger: "이벤트 수신"
    logic: "X-Internal-Token 확인(BR3.9). 본문은 2MB 이하 JSON이고 eventId(UUID) · eventType · schemaVersion(정수 >= 1) · occurredAt · aggregateType · aggregateId · payload가 있어야 한다. 모르는 필드는 무시한다."
    violation: "400 INVALID_ENVELOPE(재시도 대상이 아니다)."
    source: "C11 · 계약 10절"
  - id: BR5.5
    statement: "같은 eventId는 한 번만 업무에 반영한다. 같은 eventId에 다른 내용이 오면 충돌로 기록하고 반영하지 않는다."
    category: "constraint"
    applies_to: "Inbox"
    trigger: "이벤트 수신"
    logic: "eventId로 Inbox 행을 찾는다. 있고 payload_hash가 같으면 200(중복, 처리기 다시 부르지 않음). 있는데 해시가 다르면 기존 행은 그대로 두고 경고 로그(eventId · eventType)와 기존 행 last_error에 EVENT_CONFLICT를 남기고 409 EVENT_CONFLICT. 없으면 BR5.6."
    violation: "409."
    source: "ADR-010 §2.5 · 계약 6절"
  - id: BR5.6
    statement: "수신 ACK(200)는 Inbox 행이 영속 커밋된 뒤에만 보낸다. ACK는 업무 완료가 아니다."
    category: "constraint"
    applies_to: "Inbox"
    trigger: "새 eventId 수신"
    logic: "새 행을 status RECEIVED, next_attempt_at = received_at으로 커밋하고 나서 200을 돌려준다(단 처리기가 없거나 schemaVersion이 앱이 아는 최대값보다 크면 처음부터 IGNORED로 payload를 비워 커밋하고 200이다, BR5.9). 처리기는 응답 뒤 비동기로 호출한다(BR5.7). ACK 뒤 처리기가 실패해도 보낸 쪽은 다시 보내지 않는다 — 수신 쪽이 BR5.8로 책임진다."
    violation: "—"
    source: "ADR-010 · C11"
  - id: BR5.7
    statement: "Inbox 행은 eventType별 등록된 처리기를 호출해 반영한다. 처리기의 업무 변경과 PROCESSED 표시는 같은 로컬 트랜잭션이고, 처리기는 멱등이다."
    category: "policy"
    applies_to: "Inbox 처리기 호출"
    trigger: "새 Inbox 행, 재호출 폴러"
    logic: "호출 전에 선점 UPDATE(‘SET next_attempt_at = now + 30초 WHERE event_id = :id AND status = RECEIVED AND next_attempt_at <= now’)의 행 수가 1일 때만 호출한다 — 첫 비동기 호출과 재호출 폴러가 같은 행을 중복 호출하지 않는다(30초 값은 설정 키). 앱 시작 때는 HTTP 수신을 열기 전에 ‘UPDATE inbox SET next_attempt_at = now WHERE status = RECEIVED’ 한 문장으로 모든 RECEIVED 행의 시각을 당겨(retry_count는 바꾸지 않는다) 이 선점 경로를 한 번 타게 한다(BR5.12). 처리기를 직접 알지 않고 등록 표로 찾는다. 처리기 트랜잭션이 커밋되면 같은 트랜잭션에서 PROCESSED(processed_at, BR5.11로 payload 비움)가 된다(ADR-010 2.4). 같은 이벤트를 두 번 불러도 결과가 같아야 한다(두 번째 방어, 예: 사본 버전 비교)."
    violation: "처리기가 예외를 던지면 BR5.8."
    source: "ADR-010 · C11 · J49"
  - id: BR5.8
    statement: "처리기가 실패하면 1 · 2 · 4 · 8 · 16초 간격으로 5번까지 다시 부르고, 그래도 실패하면 DEAD로 두고 경고를 남긴다."
    category: "policy"
    applies_to: "Inbox 처리기 재호출"
    trigger: "처리기 예외"
    logic: "retry_count + 1, next_attempt_at 설정. 6번째 실패는 DEAD(last_error에 예외 종류만, 페이로드 없음). DEAD는 사람이 확인한다."
    violation: "—"
    source: "ADR-010 · J49"
  - id: BR5.9
    statement: "알 수 없는 eventType이나 지원하지 않는 상위 schemaVersion은 IGNORED로 기록하고 200으로 답한다. 알 수 없는 필드 · enum 값은 오류로 끊지 않는다."
    category: "policy"
    applies_to: "Inbox"
    trigger: "이벤트 수신"
    logic: "봉투가 맞는데 처리기가 없거나 schemaVersion이 앱이 아는 최대값보다 크면 행을 IGNORED로 커밋하고 200. 모르는 enum 값은 '알 수 없음'으로 처리한다(계약 10절)."
    violation: "—"
    source: "계약 10절 · J49"
  - id: BR5.10
    statement: "전역 순서를 가정하지 않는다. 세션 이벤트는 statusVersion으로, 기기 이벤트는 시각으로 최신만 반영한다."
    category: "constraint"
    applies_to: "처리기(ActivityCoordinator 사본, DeviceRegistry 사본)"
    trigger: "SessionStateChanged · DeviceStatusChanged 처리"
    logic: "SessionStateChanged: payload.statusVersion이 Activity.status_version보다 클 때만 사본을 덮어쓰고 같거나 작으면 버린다(PROCESSED). DeviceStatusChanged: BR4.1. 두 이벤트가 뒤바뀌어 와도 최종 값이 같다. 사본 갱신은 읽고 비교하고 쓰지 않고 한 문장의 조건부 UPDATE로 한다: Activity는 ‘UPDATE core_activity SET ... WHERE session_id = :id AND status_version < :v AND NOT (status = FAILED AND local_failed)’, Device는 BR4.1. 갱신 행 수가 0이면 행을 다시 읽는다. 사본이 local_failed이고 payload.status가 종료 상태가 아니면 버리지 않고 BR6.14대로 CORE_ABANDONED 취소를 다시 보내며(실패하면 예외 → BR5.8 재호출), 그 밖의 0행(낮거나 같은 statusVersion)만 버린다(PROCESSED). 동시에 도는 처리기가 서로의 값을 되돌리지 못한다."
    violation: "—"
    source: "ADR-010 · 계약 6절"
  - id: BR5.11
    statement: "전달이 끝난 Outbox/Inbox 행은 페이로드를 비우고 식별 · 해시만 남긴다. 실패로 남은 행은 7일 뒤 비운다."
    category: "policy"
    applies_to: "네 Outbox/Inbox 테이블"
    trigger: "PUBLISHED · PROCESSED · IGNORED로 바뀔 때, 정리 작업(하루 한 번)"
    logic: "payload_json을 {} 로 바꾸고 purged_at을 채운다(payload_hash · event_id · event_type · 상태는 남는다). FAILED · DEAD 행은 created_at(또는 received_at) 뒤 7일이 지나면 같은 방식으로 비운다. 사유: SessionRecordCaptured의 대화 원문이 보낼 때까지만 남도록."
    violation: "—"
    source: "NFR9 · ADR-010 (전사 스냅샷 · Outbox/Inbox도 보존 · 삭제 정책 대상) · J50"
  - id: BR5.12
    statement: "각 앱은 시작할 때 자기 스키마의 끝나지 않은 Outbox/Inbox 행만 다시 집는다. Realtime은 재시작 뒤 진행 중 음성 세션을 이어 붙이지 않는다."
    category: "policy"
    applies_to: "CoreMessaging, RealtimeMessaging"
    trigger: "앱 시작"
    logic: "시작할 때: PENDING 중 available_at이 지난 Outbox 행은 폴러가 평소대로 집는다. Inbox는 HTTP 수신을 열기 전에 ‘UPDATE inbox SET next_attempt_at = now WHERE status = RECEIVED’로 모든 RECEIVED 행의 다음 시도 시각을 당긴 뒤(retry_count는 바꾸지 않는다) 평소의 선점 경로(BR5.7)로 한 번씩 처리기에 넘긴다 — 200 직후 처리기 호출 전에 죽은 행과 선점 30초 안에 죽은 행도 여기서 구한다. 그 뒤 평소에는 RECEIVED 중 next_attempt_at이 지난 행만 폴러가 집는다. 상대 앱의 표는 건드리지 않는다. 세션 복구는 BR6.13."
    violation: "—"
    source: "ADR-010 · J53"
  - id: BR5.13
    statement: "메시징 로그에는 eventId · eventType · 상태 · 재시도 횟수 · 소요 시간만 남기고 페이로드 · 전사 · 토큰을 남기지 않는다."
    category: "constraint"
    applies_to: "CoreMessaging, RealtimeMessaging 로그"
    trigger: "모든 로그"
    logic: "last_error에도 상태 코드 · 예외 종류만 적는다. 페이로드를 로그 · 오류 메시지에 넣지 않는다."
    violation: "—"
    source: "NFR9 · 공통 완료 조건"
  - id: BR5.14
    statement: "계정 삭제 참가자는 앱마다 하나씩 둘이며, 각각 자기 스키마에서 전달이 끝난 Outbox/Inbox 행만 지우고, 아직 전달 · 처리 중인 제어 이벤트는 지우지 않으며, 대화 원문이 든 이벤트의 페이로드는 즉시 비운다."
    category: "constraint"
    applies_to: "Core 쪽 core_outbox_event · core_inbox_event(Core 참가자), Realtime 쪽 realtime_outbox_event · realtime_inbox_event(Realtime 참가자) (DeletionParticipant 계약 8절)"
    trigger: "Core 참가자는 계정 삭제 지휘(U2)가 deleteByGuardian을 부를 때, Realtime 참가자는 Realtime의 DeletionRequested 처리기가 U4의 호출 순서 안에서 부를 때"
    logic: "각 참가자는 자기 앱의 스키마에만 쓴다(BR8.1). 멱등이다. (a) Outbox는 PUBLISHED · FAILED, Inbox는 PROCESSED · IGNORED · DEAD 행 중 guardian_id가 같은 행을 삭제한다. (b) PENDING · RECEIVED 행은 건드리지 않는다 — DeviceReleased · DeletionRequested · DeletionCompleted가 전달 · 처리되기 전에 사라지면 해제 신호와 삭제 완료가 영영 오지 않는다. (c) 예외로 eventType이 SessionRecordCaptured(대화 원문 포함)인 행은 상태와 무관하게 payload_json을 {} 로 비우고 purged_at을 채우며, Outbox의 PENDING이면 FAILED(last_error ACCOUNT_DELETED)로, Inbox의 RECEIVED이면 IGNORED로 닫는다. (d) 호출 시점과 순서는 Core 참가자는 U2 삭제 지휘가, Realtime 참가자는 U4가 정하며, U1은 어느 시점에 불려도 결과가 같다. 지휘가 DeletionCompleted 처리 뒤 마지막 단계에 다시 부르면 (a)로 남은 행도 지워진다(Realtime 쪽 DeletionCompleted Outbox 행도 PUBLISHED가 된 뒤 U4가 Realtime 참가자를 다시 불러 지운다)."
    violation: "—"
    source: "AC1.6.6(U2 소관) · ADR-010 · 공통 완료 조건 · J19 · J50 · J58"

  # ═════════════ BR6 · 세션 슬라이스 (ActivityCoordinator · SessionLifecycle 최소) ═════════════
  - id: BR6.1
    statement: "자유대화 시작 요청은 같은 idempotencyKey를 먼저 확인한 뒤, 새 키일 때만 동의 → 진행 중 세션 → 기기 안정 순으로 검사하고 첫 사유 하나만 거절한다."
    category: "validation"
    applies_to: "POST /api/activities/free (스켈레톤 슬라이스, U4가 완성)"
    trigger: "08 「자유대화 시작」"
    logic: |
      ⓪ 로그인 필수(401), guardian.status가 DELETION_REQUESTED이면 403 ACCOUNT_DELETION_IN_PROGRESS.
      ⓪′ (guardianId, idempotencyKey)를 먼저 조회한다. 이미 있으면 ① ~ ③을 건너뛰고 BR6.2대로 처리한다. 새 키일 때만 아래를 적용한다.
      ① 현재 정책 버전의 4항목 동의가 GIVEN이 아니면 403 CONSENT_MISSING.
      ② 보호자에게 종료 아닌 세션(REQUESTED · PREPARING · DEVICE_ACK · WAITING_CHILD · ACTIVE · CLOSING)이 있으면 409 SESSION_IN_PROGRESS. 종료 상태(ENDED · ENDED_UNCONFIRMED · ABORTED · DECLINED · FAILED)는 막지 않는다. 막는 사본이 REQUESTED가 아니면 거절 전에 Realtime 진행 조회(BR6.15의 view, 제한 1초)로 한 번 대조한다 — 받은 status · statusVersion을 BR5.10의 조건부 UPDATE로 사본에 반영한 뒤 다시 판정하고, Realtime이 모르는 세션이면 사본을 로컬 FAILED(failure_code 비움)로 닫고 다시 판정한다(이벤트가 FAILED · DEAD로 끝나 사본이 영구히 낡은 경우의 계정 잠김 방지). 조회가 실패하면 사본대로 409다. REQUESTED는 BR6.12 ①이 닫는다.
      ③ 기기 판정(BR4.3)이 안정이 아니면(불안정 · 끊김 · 응답 없음 · 확인 불가 · 없음) 423 DEVICE_NOT_STABLE.
      화면은 08(또는 18)에 머물고 mockups 9-12의 해당 사유 문구 하나만 보인다.

    violation: "위 상태 코드와 오류 봉투 {code, message, traceId}. message에 전사 · 개인정보를 넣지 않는다."
    source: "AC3.1.6 · FR2.3 · mockups 9-12 · J24 · J48"
  - id: BR6.2
    statement: "시작은 idempotencyKey로 멱등이다. 같은 키 재요청은 같은 세션을 돌려주고 새 세션을 만들지 않는다."
    category: "constraint"
    applies_to: "Activity.start_idempotency_key"
    trigger: "시작 요청(BR6.1 ⓪ 직후, 동의 검사 이전)"
    logic: "(guardianId, idempotencyKey)가 이미 있으면: 요청 본문 해시(request_hash)가 같으면 첫 응답({sessionId, status})을 그대로 돌려주고, 다르면 409 IDEMPOTENCY_CONFLICT. 단 그 키의 행이 로컬 FAILED(local_failed)이면 저장된 start_result_code를 그대로 재생한다 — 503 REALTIME_UNAVAILABLE(BR6.7), 423 DEVICE_NOT_STABLE 또는 409 SESSION_IN_PROGRESS(BR6.6), 202 {sessionId, status: REQUESTED}(BR6.12 ① 시한 — 12가 폴링으로 FAILED를 받아 08로 돌아간다). 새 시작은 새 키로만 한다. 첫 시도가 BR6.1의 ① ~ ③에서 거절돼 행이 없으면 재요청은 처음부터 다시 검사한다. 키가 없으면 새 세션. 키는 클라이언트가 만든 UUID다. 같은 키가 동시에 겹쳐 INSERT가 (guardian_id, key) 유일 위반이면 승자 행을 다시 읽어 같은 응답을 돌려준다."
    violation: "409 IDEMPOTENCY_CONFLICT."
    source: "AC3.1.8 · 계약 2절"
  - id: BR6.3
    statement: "보호자당 종료 아닌 세션은 하나다. 두 번 탭 · 두 탭이 겹쳐도 세션은 하나만 생긴다."
    category: "constraint"
    applies_to: "Activity, ActivitySession"
    trigger: "서로 다른 키로 시작 요청 두 건이 겹칠 때"
    logic: "Core core_activity의 부분 유일 색인(보호자당 종료 아닌 상태 하나)이 먼저, Realtime realtime_activity_session의 같은 부분 유일 색인이 2차 방어다. 유일 위반이 나면 먼저 (guardianId, idempotencyKey)로 다시 읽어 같은 키 행이 있으면 BR6.2대로 재생하고, 없을 때만 409 SESSION_IN_PROGRESS로 번역한다. 두 번째 요청을 보낸 08은 BR6.1대로 사유 문구 하나만 보인다(같은 탭이 이미 첫 응답으로 12에 갔다면 이동하지 않는다). 첫 세션의 12는 첫 요청을 보낸 화면이 열고 있고, 다른 화면에서 진행 중 세션의 12로 들어가는 길(04 지금 활동 · 진행 중 탭, GET /api/sessions/current)은 U4가 완성한다."
    violation: "409 SESSION_IN_PROGRESS."
    source: "AC3.1.8 · FR2.3"
  - id: BR6.4
    statement: "08은 네 칸을 모두 비워도 시작할 수 있고, 두 서술 칸은 200자를 넘으면 막으며, 전송 중에는 「자유대화 시작」이 꺼진다."
    category: "validation"
    applies_to: "08 화면, POST /api/activities/free 본문 context"
    trigger: "08 입력 · 전송"
    logic: "context(knownContent · situationType · sourceType · question)는 모두 선택. 서술 칸 knownContent · question은 200자 초과를 서버도 400 VALIDATION_FAILED로 거절한다. 전송 중 단추 꺼짐. U1은 context를 받아 검증만 하고 저장 · 구조화 입력 사용은 U4(AC3.1.2)가 완성한다."
    violation: "400."
    source: "AC3.1.1 · AC3.1.3 · AC3.1.4 · J59(d)"
  - id: BR6.5
    statement: "세션 패키지는 Core가 만들어 Realtime에 한 번 건네고, 대화 모델에는 최소 문맥만 간다."
    category: "policy"
    applies_to: "POST /internal/sessions (C8)"
    trigger: "시작 검사 통과"
    logic: "sessionId는 Core가 발급한다(J11). 패키지: sessionId · idempotencyKey · guardianId · childId · deviceId · activityType(FREE_TALK) · consentSnapshot(정책 버전 + 4항목) · childContext(displayName · age · interests만) · stopExpressions(기본 종료 표현 ‘무중아 그만할래’ 한 건 — 코드 상수이고 isDefault true이며 Realtime이 realtime_session_stop_expression에 한 행으로 저장한다. core_stop_expression은 U1이 비워 둔다) · approvedContent(CLOSING · WAITING_FILLER의 contentVersionId · text · audioUrl — 세션에 고정). 08 입력 네 칸 원문은 패키지에 넣지 않는다(NFR3). 제한 시간 3초."
    violation: "지연 · 실패는 BR6.7."
    source: "AC3.1.7 · NFR3 · A5 · J11 · J26"
  - id: BR6.6
    statement: "Realtime은 시작 명령을 받으면 기기 연결을 확인하고 세션 행과 PREPARING 이벤트를 한 트랜잭션에서 만든다."
    category: "policy"
    applies_to: "SessionLifecycle (POST /internal/sessions 수신)"
    trigger: "Core의 시작 명령"
    logic: "① sessionId가 이미 있으면(같은 idempotencyKey) 현재 상태를 202로 돌려준다. ② 기기 연결이 CONNECTED가 아니면(DeviceConnection 행의 상태와, 살아 있는 소켓 레지스트리에 그 connection_id가 모두 있어야 한다) 423 DEVICE_NOT_CONNECTED. ③ 보호자 · 기기에 종료 아닌 세션이 있으면 409 SESSION_IN_PROGRESS(2차 방어). ④ 같은 트랜잭션에 ActivitySession(PREPARING, status_version 1, 패키지 고정) + SessionStopExpression + RealtimeOutboxEvent SessionStateChanged(PREPARING)를 쓴다. INSERT는 `ON CONFLICT (id) DO NOTHING`으로 하고, 충돌이거나 보호자 · 기기 부분 유일 색인 위반이면 sessionId로 다시 읽어 행이 있으면 ①처럼 현재 상태를 202로 돌려준다(없을 때만 409 SESSION_IN_PROGRESS) — 시간 초과 재시도가 첫 호출과 겹쳐도 재시도가 실패를 만들지 않는다. ⑤ 커밋 뒤 모델 세션 열기와 START_SESSION 전송을 시작한다(BR7.2). START_SESSION 전송이 소켓 없음 · 전송 예외로 실패하면 같은 처리 안에서 PREPARING → FAILED(DEVICE_NO_ACK)로 전이하고(BR6.8) 기기 연결 판정을 UNRESPONSIVE로 기록한다(BR4.8 ③). Core가 취소(BR6.7)한 sessionId의 늦은 시작은 409 SESSION_NOT_ACTIVE로 거절한다(10분 묘비)."
    violation: "202가 아닌 확정 응답(409 · 423)이면 Core는 같은 처리 안에서 방금 만든 사본을 ‘WHERE status = REQUESTED AND status_version = 0’ 조건부로 로컬 FAILED(failure_code 비움, start_result_code에 번역한 응답 코드)로 닫고 번역해 돌려준다(0행이면 Realtime 이벤트가 사본을 앞선 것이므로 202 {sessionId, status: REQUESTED}): 423 DEVICE_NOT_CONNECTED → 423 DEVICE_NOT_STABLE, 409 SESSION_IN_PROGRESS → 409 SESSION_IN_PROGRESS(Core가 모르는 진행 세션이 있다는 신호이므로 운영 경고 로그를 남긴다). 내부 코드는 보호자 응답에 내보내지 않는다. Realtime이 409 · 423을 돌려준 경우에는 Realtime에 세션이 없으므로 취소가 필요 없다(시간 초과 재시도 뒤의 409는 BR6.7처럼 취소를 보낸다)."
    source: "AC3.1.8 · FR2.3 · C8"
  - id: BR6.7
    statement: "Realtime을 부를 수 없으면 Core는 그 세션을 FAILED로 닫고 보호자에게 503을 돌려주며 Realtime에 정리를 부탁한다."
    category: "policy"
    applies_to: "ActivityCoordinator 스켈레톤 슬라이스"
    trigger: "Realtime 시작 호출 시간 초과 · 연결 실패 · 503"
    logic: "연결 거부 · 503 등 확정 실패는 바로, 시간 초과는 같은 sessionId로 1회 재시도(BR6.6 ①이 멱등)한 뒤에도 실패하면 Core 사본을 ‘WHERE status = REQUESTED AND status_version = 0’ 조건부로 FAILED(end_reason FAILED, failure_code 비움, status_version은 0 그대로, local_failed true, start_result_code 503)로 바꿔 계정 잠김을 막고 503 REALTIME_UNAVAILABLE을 돌려준다. 0행이면(호출이 사실은 성공해 Realtime 이벤트가 사본을 앞섬) 취소를 보내지 않고 202 {sessionId, status: REQUESTED}를 돌려준다. 이어 Realtime에 취소 `POST /internal/sessions/{sessionId}/end {requestId = sessionId, reason: CORE_ABANDONED}`(제한 2초, 멱등)를 보낸다. Realtime은 (가) 모르는 sessionId면 10분 묘비(Realtime 메모리, 재시작하면 사라지고 그때 늦게 만들어진 세션은 BR6.14의 재취소로 닫힌다)를 두고 202, (나) PREPARING · DEVICE_ACK · ACTIVE이면 FAILED(end_reason FAILED, failure_code 비움)로 닫고 공급자 세션을 닫으며 START_SESSION이 나갔고 연결이 살아 있으면 기기에 CLOSE_TRANSPORT(FAILED)를 보내고 SessionStateChanged를 쓴다, (다) CLOSING이면 그대로 두고 202, (라) 이미 종료 상태면 202 무동작이다. Core가 로컬 FAILED로 닫은 세션의 늦은 Realtime 이벤트는 사본에 반영하지 않는다(BR6.14)."
    violation: "—"
    source: "AC3.1.9 · J24 · J26 · J54 · J59(f)"
  - id: BR6.8
    statement: "세션 상태 전이는 허용된 것만, 조건부 UPDATE로 승자 하나만 성립시키고 종료 상태는 되돌아가지 않는다. U1 슬라이스의 전이는 PREPARING → DEVICE_ACK → ACTIVE → CLOSING → ENDED와 실패 · 중단 경로다."
    category: "constraint"
    applies_to: "ActivitySession.status"
    trigger: "전이 시도"
    logic: |
      허용 전이(Realtime, 전이마다 status_version + 1과 SessionStateChanged를 같은 트랜잭션에):
      PREPARING → DEVICE_ACK (START_SESSION에 대한 ACK — 계약 4.2절)
      DEVICE_ACK → ACTIVE (DEVICE_READY의 mic · player OK와 공급자 session.started가 둘 다 확인되면, 나중 것이 전이를 일으킨다; 이어 입력 음성 중계를 시작한다. prefetch는 OK · PARTIAL 모두 수락하고 빠진 음원은 재생 때 audioUrl로 받는다)
      DEVICE_ACK → FAILED (DEVICE_READY의 mic 또는 player가 UNAVAILABLE이면 20초를 기다리지 않고 즉시, failure_code DEVICE_NO_ACK)
      PREPARING · DEVICE_ACK → FAILED (BR6.12 ②의 20초 시한: DEVICE_NO_ACK 또는 PROVIDER_UNAVAILABLE, 공급자 연결 실패 PROVIDER_UNAVAILABLE, START_SESSION 전송 실패 DEVICE_NO_ACK)
      PREPARING · DEVICE_ACK · ACTIVE → FAILED (Core 취소 CORE_ABANDONED, BR6.7)
      PREPARING · DEVICE_ACK · ACTIVE · CLOSING → FAILED (Realtime 재시작 SERVER_RESTART, BR6.13)
      ACTIVE → CLOSING (끝내기 요청 GUARDIAN_STOP, BR6.9)
      CLOSING → ENDED (VOICE_SESSION_CLOSED 확인, confirmed true) · ENDED_UNCONFIRMED (30초 확인 없음, confirmed false)
      ACTIVE → ABORTED (DISCONNECT 40초)
      end_reason: ENDED = GUARDIAN_STOP, ENDED_UNCONFIRMED = UNCONFIRMED, ABORTED = DISCONNECT, FAILED = FAILED.
      모든 전이는 ‘UPDATE realtime_activity_session SET status = :to, status_version = status_version + 1, row_version = row_version + 1 WHERE id = :id AND status = :from’ 의 행 수가 1일 때만 성립하고, 그때만 SessionStateChanged와 STOP_OUTPUT · PLAY_APPROVED_CONTENT · CLOSE_TRANSPORT 같은 부수 효과를 낸다(승자 하나). 행 수 0이면 다른 전이가 이긴 것이므로 무시하고 SessionEvent에 기록한다(끝내기 요청이면 BR6.9대로 현재 상태에 맞게 답한다). 출발 상태가 둘 이상인 전이(→ FAILED의 시한 · 취소 · 재시작)는 세션 행을 `FOR UPDATE`로 읽어 현재 상태로 failure_code를 정한 뒤 ‘WHERE status = :읽은 상태’로 쓴다. 0행이면 한 번 다시 읽어 아직 출발 집합 안이면 다시 시도하고, 20초 시한이 만난 ACTIVE · 종료 상태는 이미 충족된 것으로 보고 무시한다.
      FAILED로 전이할 때 기기 WSS 연결이 살아 있고 START_SESSION이 이미 나갔으면 CLOSE_TRANSPORT(reason FAILED)를 보내고 SESSION_STATE{STANDBY}로 되돌린다. VOICE_SESSION_CLOSED는 기다리지 않고 오면 SessionEvent에 기록만 한다. ABORTED(DISCONNECT)와 재시작은 보낼 연결이 없으므로 제외한다.
      U1에 없는 전이(WAITING_CHILD · DECLINED · CHILD_STOP · SILENCE · CONSENT_WITHDRAWN)는 U4가 더한다. REQUESTED는 Core 사본에만 있다.

    violation: "허용되지 않은 전이는 무시하고 SessionEvent에 기록한다(오류 응답 아님)."
    source: "세션 상태 11개(K5) · AC3.3.4 · AC3.3.5 · B11 · J54"
  - id: BR6.9
    statement: "끝내기는 보호자가 한 번 확인한 뒤 요청하고, 종료 가능한 자기 세션에만, 멱등으로 처리한다."
    category: "authorization"
    applies_to: "POST /api/sessions/{id}/end (P1)"
    trigger: "P1 「네, 끝낼게요」"
    logic: "P1(제목 '정말 끝낼까요?')에서 「아니오」면 12 그대로. 요청은 세션 id + requestId(UUID). 남의 세션이면 404. Core는 사본이 종료 상태(ENDED · ENDED_UNCONFIRMED · ABORTED · FAILED)일 때만 Realtime을 부르지 않고 409 SESSION_NOT_ACTIVE로 답하고, 그 밖의 상태(사본이 늦어 PREPARING · DEVICE_ACK여도)는 Realtime에 end(제한 2초)를 보내 Realtime 응답을 최종으로 삼는다. Realtime end(reason GUARDIAN_STOP)의 응답: ACTIVE → 202 {CLOSING}(첫 수락, requestId 기록). CLOSING → 같은 requestId든 다르든 202 {CLOSING}, 재처리 없음(멱등: 종료 요청 1건 · 기기 종료 명령 1회 · 전이 1회). ENDED · ENDED_UNCONFIRMED · ABORTED · FAILED → 409 SESSION_NOT_ACTIVE. PREPARING · DEVICE_ACK → 409 SESSION_NOT_ACTIVE(WAITING_CHILD 추가는 U4). 12는 409를 받으면 세션 상태를 한 번 다시 읽어 종료 상태일 때만 17로 가고(실제 사유를 본다), PREPARING · DEVICE_ACK면 12에 머물고 토스트만 보인다. 끝내기 단추는 ACTIVE가 되기 전에는 꺼 둔다. '끝내기 요청을 보내지 못했어요. 인터넷을 확인해 주세요.'(mockups 9-18)는 Realtime에 닿지 않는 503 · 401뿐 아니라 보호자 폰의 네트워크 오류 · 시간 초과에도 보이며 종료 요청됨으로 표시하지 않는다(세션은 ACTIVE 유지). Realtime end는 reason이 CORE_ABANDONED일 때 BR6.7의 (가) ~ (라)를 따른다."
    violation: "404 · 409 · 503."
    source: "AC3.3.1 ~ AC3.3.3 · AC3.3.6 · AC3.3.8 · AC3.3.9 · J54"
  - id: BR6.10
    statement: "끝내기가 수락되면 일반 출력을 멈추고 승인된 마무리 인사를 한 번만 재생한 뒤 기기 멈춤을 확인하고 끝낸다."
    category: "policy"
    applies_to: "SessionLifecycle, OutputGate, DeviceGateway 스켈레톤 슬라이스"
    trigger: "CLOSING 진입"
    logic: |
      ① 서버 쪽 출력 버퍼를 취소하고 STOP_OUTPUT(reason GUARDIAN_STOP)과 SESSION_STATE{view: CLOSING}을 보낸다. 이후 게이트는 일반 출력을 통과시키지 않는다(늦은 출력 폐기).
      ② 세션에 고정된 CLOSING 승인 문구를 PLAY_APPROVED_CONTENT(새 playbackId)로 한 번만 보낸다(기기 재생 로그 count = 1, 콘텐츠 길이 3초 이내).
      ③ ②에서 보낸 playbackId와 같은 PLAYBACK_MARK가 COMPLETED · STOPPED · FAILED(승인 음성을 못 받은 경우 포함) 중 하나로 오면 CLOSE_TRANSPORT를 보낸다. 다른 playbackId의 마크(① STOP_OUTPUT으로 멈춘 이전 출력 등)는 근거로 쓰지 않는다. FAILED이면 마무리 인사 없이 종료로 가며 종료 확인은 ④로만 정한다.
      ④ 기기의 VOICE_SESSION_CLOSED(outputStoppedAt · micClosedAt)를 받으면 공급자 세션을 닫고 ENDED(confirmed true)로 간다. 기기가 확인한 시각 · 서버가 닫은 시각을 termination 필드에 남긴다. ENDED · ENDED_UNCONFIRMED 전이 뒤 연결이 살아 있으면 SESSION_STATE{view: STANDBY}를 보내 기기를 대기로 돌린다.
      종료 확정 뒤 이전 응답이 이어 재생되지 않는다. 종료와 기록 삭제는 별개다.

    violation: "확인이 30초 안에 없으면 BR6.12 ③."
    source: "AC3.3.3 ~ AC3.3.5 · AC3.3.7 · FR7.8 · FR3.3 · J60"
  - id: BR6.11
    statement: "종료가 확정된 세션의 늦은 메시지는 세션을 되살리거나 시계를 시작하지 못한다."
    category: "constraint"
    applies_to: "SessionLifecycle, DeviceGateway"
    trigger: "종료 상태 이후 기기 · 공급자 메시지"
    logic: "종료 상태 세션에 오는 PLAYBACK_MARK · HEARTBEAT 기반 판단 · 공급자 이벤트 · 옛 연결의 메시지는 기록 없이 버린다(ERROR 응답 없음)."
    violation: "—"
    source: "AC3.3.7 · protocol.md (늦은 보고 규칙)"
  - id: BR6.12
    statement: "스켈레톤에도 세션이 영원히 남지 않게 하는 네 시한을 둔다. 값은 설정(mujoong.timing.*)에서 읽는다."
    category: "constraint"
    applies_to: "ActivityCoordinator(①), SessionLifecycle(②~④)"
    trigger: "시계(t0)가 지날 때"
    logic: |
      ① session.preparing-timeout (10초): Core가 REQUESTED를 만든 때부터 PREPARING 이벤트를 못 받으면 Core 사본을 FAILED로 닫는다(BR6.7과 같다, ‘WHERE status = REQUESTED AND status_version = 0’ 일 때만 쓰고 local_failed true, start_result_code 202를 기록한다).
      ② session.device-ack-timeout (20초): START_SESSION 송신 시도 시각부터(송신이 일어나지 못하면 BR6.6 ⑤의 즉시 FAILED) ACK · DEVICE_READY(OK) · 공급자 session.started가 모두 확인돼 ACTIVE가 되지 않으면 FAILED(BR6.8의 다중 출발 전이). ACK나 DEVICE_READY가 빠졌으면 failure_code DEVICE_NO_ACK이고 기기 연결 판정을 UNRESPONSIVE로 쓴다(BR4.8 ③), 둘은 왔는데 session.started가 없으면 PROVIDER_UNAVAILABLE이다.
      ③ session.closing-confirm (30초): CLOSING 진입부터 VOICE_SESSION_CLOSED가 없으면 ENDED_UNCONFIRMED이고 기기 연결 판정을 UNRESPONSIVE로 쓴다(②와 같은 설정 · 해제). 이 시한이 끊김보다 우선한다(CLOSING 중 끊김은 DISCONNECT 전이가 없다).
      ④ device.disconnect (40초): ACTIVE에서 그 세션의 START_SESSION을 받은 소켓(ACK와 DEVICE_READY를 보낸 connection_id)의 마지막 기기 메시지(HEARTBEAT 포함) 수신 시각이 40초 지나면 ABORTED(DISCONNECT). 이 시계는 Realtime 메모리의 세션별 값이다 — 재시작하면 BR6.13이 비종료 세션을 모두 FAILED로 닫으므로 영속하지 않는다(영속 last_seen_at 기준은 기기 연결 상태 판정 BR4.8에만 쓴다). 그 소켓이 닫히거나 4001로 대체된 뒤 새 연결이 HELLO_OK를 받아도 새 연결의 메시지는 이 시계를 되돌리지 않는다(이어가기는 U3). 서버가 출력 · 공급자를 정리한다.
      참여 대기 · 공백 4 · 6분 · 점선 10초 · 기술적 일시 정지 · 경계값 19 / 21초 시험은 U4가 같은 키 · 같은 코드를 확장한다.

    violation: "—"
    source: "AC3.1.9 · AC3.3.5 · AC6.2.10 · AC6.2.11 · AC2.1.6 · B5 · B7 · B10 · B11 · J51 · J55 · J59(h)"
  - id: BR6.13
    statement: "Realtime이 재시작하면 종료 아닌 세션을 FAILED(SERVER_RESTART)로 바꾸고 음성 활동을 이어 붙이지 않는다."
    category: "policy"
    applies_to: "SessionLifecycle"
    trigger: "Realtime 시작"
    logic: "시작할 때 종료 아닌 realtime_activity_session을 모두 FAILED(failure_code SERVER_RESTART)로 바꾸고(BR6.8의 재시작 전이) SessionStateChanged를 Outbox에 쓴다. 이어 붙이는 복구는 없다. 또 시작할 때 CONNECTED · UNSTABLE인 realtime_device_connection 행은 connection_id를 비우고 DISCONNECTED로 바꾸며 DeviceStatusChanged를 같은 트랜잭션에 쓴다(죽은 소켓이 안정으로 보이지 않게). Core가 재시작하면 REQUESTED로 남은 세션을 BR6.12 ①로 정리한다."
    violation: "—"
    source: "ADR-010 · J53 · AC2.1.11"
  - id: BR6.14
    statement: "세션 사본은 Realtime의 SessionStateChanged 이벤트로만 진행하고 버전이 앞서는 값만 반영한다."
    category: "constraint"
    applies_to: "Activity (Core 처리기)"
    trigger: "SessionStateChanged 처리"
    logic: "BR5.10대로 payload.statusVersion > Activity.status_version 일 때만 status · end_reason · failure_code · confirmed를 덮어쓴다. 열 대응: started_at = status가 처음 ACTIVE로 바뀐 이벤트의 at, ended_at = 종료 상태(ENDED · ENDED_UNCONFIRMED · ABORTED · FAILED) 이벤트의 at, observed_at = Core가 사본을 마지막으로 갱신한 시각(이벤트를 반영한 처리 시각). 정확한 시각이 따로 필요해지면 startedAt · endedAt을 payload에 더하는 일은 U4가 한다(더하기만 안전). 낡은 이벤트(statusVersion이 크지 않아 버려지는 것)라도 status가 ACTIVE이고 사본 started_at이 비어 있으면 ‘UPDATE core_activity SET started_at = :at WHERE session_id = :id AND started_at IS NULL’만 따로 실행한다(status · status_version은 건드리지 않는다). 사본이 Core 로컬 FAILED(BR6.7)이면 반영하지 않는다. 단 이벤트의 status가 종료 상태가 아니면 처리기가 같은 취소(CORE_ABANDONED)를 Realtime에 다시 보내고, 호출이 실패하면 예외를 던져 BR5.8 재호출에 맡긴다."
    violation: "—"
    source: "계약 6절 · J5 · J10 · J54"
  - id: BR6.15
    statement: "12 진행 조회는 Core가 소유를 확인한 뒤 Realtime 진행 조회를 중계하고, 아이 전사와 승인된 AI 말만 내보낸다."
    category: "policy"
    applies_to: "GET /api/sessions/{id}?sinceSeq=n (U1 최소)"
    trigger: "2초 폴링(parent.poll-interval)"
    logic: "소유가 아니면 404. Core가 GET /internal/sessions/{id}/view?sinceSeq(제한 1초)를 부른다. 응답 모양은 계약 3절 LiveView 중 U1이 채우는 값이다: SessionSummary(sessionId · activityType · status · endReason · failureCode · confirmed · requestedAt · startedAt · endedAt) + statusVersion · serverTime · observedAt · stale · items(seq > sinceSeq인 말풍선: 아이 전사 · approved_for_display가 true이고 확정(FINAL)된 AI 말) · nextSeq. technicalPause · silenceWarning · practice는 null, gaps는 빈 배열로 두고 U3 · U4 · U5가 채운다. nextSeq는 응답 items의 최대 seq이고 items가 비면 요청의 sinceSeq를 그대로 돌려준다. 단 확정 전(PARTIAL) 행이 있으면 그중 가장 작은 seq − 1을 넘지 않는다(확정 전 조각은 같은 seq로 다시 온다, J23). 조회에 실패하면 Core 사본으로 답하고 items는 비우고 nextSeq는 요청의 sinceSeq로 둔다. observedAt은 Core가 마지막으로 Realtime에서 받은 시각(조회 성공 응답 수신과 사본 갱신 중 늦은 값, Core 메모리 허용)이고, now − observedAt이 core.device-status-stale(30초)을 넘으면 stale = true다(12가 '기기 상태를 확인할 수 없어요 · 마지막 확인 hh:mm', mockups 9-16 — 04 · 05의 BR4.3 ③과 같은 30초 기준이며 조회 한 번의 실패만으로는 stale이 아니다). 시작 직후 라벨은 mockups 9-13. 판정값 · 08 입력 원문 · 게이트가 폐기한 구간은 응답에 없다. 12는 받은 status로 functional-spec 4절의 이동 표를 따른다."
    violation: "—"
    source: "FR3.2 · AC3.1.9 · AC3.3.5 · AC3.2.1(U4 소관) · AC3.2.17(U4 소관) · A2 · J5 · J23"
  - id: BR6.16
    statement: "보호자가 로그아웃하거나 로그인이 만료돼도 진행 중 세션은 영향받지 않는다."
    category: "policy"
    applies_to: "세션 수명"
    trigger: "로그아웃 · 401"
    logic: "세션은 기기와 Realtime 사이에서 계속된다. 로그아웃은 세션 종료 요청이 아니다(앱 닫힘과 같음). 재로그인 뒤 04에서 지금 활동으로 다시 들어간다(04 지금 활동 구역은 U4)."
    violation: "—"
    source: "AC1.1.9 · B2"
  - id: BR6.17
    statement: "자유대화 세션 한 건의 핵심 기록(상태 · 사유 · 시각 · 기기 확인)은 Realtime이 갖고 Core는 사본만 읽는다."
    category: "constraint"
    applies_to: "Activity, ActivitySession"
    trigger: "모든 세션 읽기 · 쓰기"
    logic: "Core 사본 컬럼 중 status · status_version · end_reason · failure_code · confirmed · started_at · ended_at은 SessionStateChanged로만 바뀐다(예외: BR6.6 · BR6.7 · BR6.12 ①의 로컬 FAILED와, BR6.1 ②의 대조 결과 반영). Core 컴포넌트가 realtime 스키마를 읽거나 쓰지 않고 그 반대도 없다."
    violation: "—"
    source: "ADR-019 · J5"

  # ═════════════ BR7 · 음성 · 게이트 슬라이스 (VoiceRelay · OutputGate 최소) ═════════════
  - id: BR7.1
    statement: "아이 입력 음성은 세션이 ACTIVE가 된 뒤부터 CLOSE_TRANSPORT(또는 종료 · FAILED 전이) 전까지만 공급자에 중계하고, 그 전에 온 프레임은 버린다. 원음은 메모리에만 둔다."
    category: "constraint"
    applies_to: "DeviceGateway, VoiceRelay"
    trigger: "바이너리 0x01 프레임 수신"
    logic: "PCM16 · 24kHz · 모노 20ms 프레임을 순번 순으로 공급자 session.input_audio.append에 전달한다. 기기는 DEVICE_READY 뒤부터 프레임을 보낼 수 있다(계약 4.2절). 서버는 세션이 ACTIVE(공급자 session.started 확인)가 되기 전에 온 프레임, 세션 없음 · 종료 · FAILED 전이 뒤 프레임을 버리고 버퍼링하지 않는다. FAILED 전이도 CLOSE_TRANSPORT와 함께 입력 수락을 끝낸다. 원음은 디스크 · DB · Outbox · 로그에 쓰지 않는다. 동의된 세션의 원음 파일 저장은 U3(RAW_AUDIO)."
    violation: "—"
    source: "FR7.8 · NFR9 · ADR-004 · J27"
  - id: BR7.2
    statement: "공급자(GPT-Live) 연결은 서버가 서버 키로 열고, 세션 시작 응답을 확인한 뒤에만 세션 의존 명령을 보낸다."
    category: "policy"
    applies_to: "VoiceRelay"
    trigger: "세션 PREPARING"
    logic: "서버 환경 변수 키로 wss://api.openai.com/v1/live/sessions(모델 gpt-live-1)에 연결해 session.start를 보내고 session.started를 받으면 ACTIVE 전이 조건(BR6.8)을 채운다. 키는 기기 · 프런트 번들 · 응답 · 로그에 나가지 않는다. 연결 실패 또는 BR6.12 ②의 시한 초과는 FAILED(PROVIDER_UNAVAILABLE)."
    violation: "BR6.8의 FAILED 경로."
    source: "ADR-004 · GL-01 · 스켈레톤 J16"
  - id: BR7.3
    statement: "스켈레톤의 '출력 구간'은 공급자 출력 전사가 800ms 조용해지거나 아이 입력이 다시 시작될 때까지 모은 한 응답이다."
    category: "policy"
    applies_to: "OutputGate 스켈레톤 슬라이스"
    trigger: "session.output_audio.delta · session.output_transcript.delta 수신"
    logic: "출력 음성은 구간이 확정될 때까지 서버 버퍼에만 둔다(기기로 직행하지 않는다). 구간 확정 조건은 위 문장이며 800ms는 설정값이다. 정식 구간 분할 · 전사 대응 · 상태 · 권한 · 최신성 검사는 U3가 완성한다."
    violation: "—"
    source: "FR7.8 · NFR1 · ADR-004 · J52"
  - id: BR7.4
    statement: "게이트는 구간의 전사가 비었거나 시드 금지 패턴에 걸리면 폐기하고 승인 대체 문구를 재생한다. 통과한 구간만 기기에 보낸다."
    category: "policy"
    applies_to: "OutputGate 스켈레톤 슬라이스"
    trigger: "구간 확정"
    logic: "(가) 구간 전사가 비었거나 출력 음성과 대응이 안 되면 폐기. (나) 시드 금지 패턴 목록(진단 · 치료 · 점수 · 정상 · 비정상 류, 한 줄 사전)에 걸리면 폐기. 폐기하면 버퍼를 비우고 WAITING_FILLER 승인 문구를 PLAY_APPROVED_CONTENT로 재생한다. 통과하면 OUTPUT_BEGIN → 0x02 프레임 → OUTPUT_END로 보낸다. 이전 승인을 재사용하지 않으며 송신 직전에 세션 상태가 ACTIVE인지 다시 확인한다."
    violation: "폐기된 구간은 전사 행의 approved_for_display를 true로 만들지 않는다(BR7.5)."
    source: "FR7.8 · NFR1 · NFR2 · ADR-004 · J52"
  - id: BR7.5
    statement: "AI 말의 전사는 게이트를 통과한 뒤에만 보호자 화면에 나갈 수 있다."
    category: "policy"
    applies_to: "Transcript.approved_for_display"
    trigger: "구간 통과"
    logic: "아이 전사는 확정 즉시 approved_for_display true. AI 말은 해당 구간이 통과된 시점에 true. 폐기 구간은 false로 남고 보호자 응답(BR6.15)에서 빠진다. AI 행의 FINAL 전환과 approved_for_display 판정(통과 true / 폐기 false)은 같은 트랜잭션에서 커밋한다 — 승인이 늦어 앞선 seq가 뒤늦게 나타나는 일을 막는다. 전사 조립은 sequence_no를 세션 안에서 1부터 올리고 확정 전 조각은 같은 번호로 갱신한다."
    violation: "—"
    source: "AC3.2.15(U4 소관) · FR7.1 · J12"
  - id: BR7.6
    statement: "아이 기기는 서버가 승인해 보낸 소리 외에는 재생하지 않는다."
    category: "constraint"
    applies_to: "DeviceWeb (재생기)"
    trigger: "모든 오디오 재생"
    logic: "재생 대상은 ① PLAY_APPROVED_CONTENT가 가리키는 승인 음성 파일, ② OUTPUT_BEGIN으로 시작된 묶음의 0x02 프레임뿐이다. OUTPUT_BEGIN 없이 온 0x02 프레임 · 모르는 contentCode는 버린다. 모델 키 · 세션 키를 기기에 주지 않는다."
    violation: "—"
    source: "AC6.2.9 · FR7.8 · NFR1"

  # ═════════════ BR8 · 실행 골격 · 공통 ═════════════
  - id: BR8.1
    statement: "데이터베이스는 인스턴스 하나에 core · realtime 두 스키마를 두고 각 앱은 자기 스키마에만 쓴다."
    category: "constraint"
    applies_to: "PostgreSQL, core_app · realtime_app 역할"
    trigger: "마이그레이션, 모든 DB 접근"
    logic: "core_app은 core 스키마에만, realtime_app은 realtime 스키마에만 쓰기 권한이 있고 서로의 스키마에는 권한이 없다. 다른 스키마를 가리키는 열은 DB 외래 키가 없는 논리 참조(UUID)다. 앱은 Flyway를 validate만 하고 마이그레이션은 별도 역할로 배포 단계에서 실행한다."
    violation: "권한 오류. 시험이 상대 스키마 쓰기 실패를 확인한다."
    source: "ADR-009 · ADR-019 · 계약 8절"
  - id: BR8.2
    statement: "스키마 변경은 Flyway 마이그레이션 파일로만 하고, U1의 V1이 스켈레톤이 쓰는 모든 표를 만든다. 뒤 유닛은 열 · 색인을 더하기만 한다."
    category: "constraint"
    applies_to: "db/migration"
    trigger: "스키마 변경"
    logic: "V1__core_base.sql · V1__realtime_base.sql(U1). 소유 유닛이 새 열을 더하는 V2+ 파일은 더하기만 한다(삭제 · 이름 변경 · 필수화는 깨는 변경 — 계약 10절). 직접 DDL 금지."
    violation: "—"
    source: "J43 · 계약 10절"
  - id: BR8.3
    statement: "실행 프로필은 dev · demo · prod이고, 시험 로그인과 시드는 dev에서만 켜진다."
    category: "constraint"
    applies_to: "설정 프로필"
    trigger: "앱 시작"
    logic: "DevLoginController와 DevSeedRunner 빈은 프로필 dev AND mujoong.dev-login.enabled=true(기본 false)일 때만 등록된다. prod 프로필이 활성인데 dev가 함께 활성이거나 mujoong.dev-login.enabled=true이면 앱은 기동을 거부한다. demo · prod에서는 빈이 없어 /api/dev/login이 404다. demo는 U2의 구글 로그인을 쓴다(공개 서버의 시험 로그인은 누구에게나 시드 보호자 세션을 주므로 열지 않는다)."
    violation: "—"
    source: "AC1.1.7 · NFR9 · J44"
  - id: BR8.4
    statement: "시험 로그인은 시드 보호자의 세션 쿠키를 열 뿐이며 구글 외 로그인 · 회원가입 · 아이디 · 비밀번호 경로를 만들지 않는다."
    category: "authorization"
    applies_to: "POST /api/dev/login"
    trigger: "dev 프로필에서 호출"
    logic: "본문 없이 호출하면 시드 보호자(고정 UUID)에 대한 MUJOONG_SESSION 쿠키(운영과 같은 Spring Session JDBC)를 연다. 01 화면에는 이 경로로 가는 단추가 없다. 같이 켜지는 DevSeedRunner(dev 한정)는 시드 보호자 · 아이 한 명 · 현재 정책 버전 동의 4항목 GIVEN · 승인 문구 CLOSING과 WAITING_FILLER(임시 WAV 두 개, 정식 녹음 전까지, J60)를 넣는다."
    violation: "dev 밖에서는 404."
    source: "AC1.1.7 · J44 · J60"
  - id: BR8.5
    statement: "모든 보호자 API는 세션의 보호자 ID로만 자원을 찾고, 남의 자원은 존재 여부를 드러내지 않는 404로 답한다."
    category: "authorization"
    applies_to: "보호자 REST 전부"
    trigger: "자원 id가 들어온 요청"
    logic: "자원 조회는 항상 (자원 id AND guardian_id = 세션 보호자)로 한다. 없거나 남의 것이면 둘 다 404 NOT_FOUND이고 응답에 그 데이터가 없다. 세션이 없거나 만료면 401 UNAUTHENTICATED."
    violation: "404 · 401."
    source: "AC1.1.8 · AC1.1.9 · NFR9 · J21"
  - id: BR8.6
    statement: "모든 REST 오류는 {code, message, traceId} 봉투이고 화면 문구는 code로 고른다."
    category: "policy"
    applies_to: "공통 오류 처리"
    trigger: "오류 응답"
    logic: "코드 목록: UNAUTHENTICATED 401 · NOT_FOUND 404 · VALIDATION_FAILED 400 · CONSENT_MISSING 403 · ACCOUNT_DELETION_IN_PROGRESS 403 · CSRF_TOKEN_INVALID 403 · SESSION_IN_PROGRESS 409 · IDEMPOTENCY_CONFLICT 409 · SESSION_NOT_ACTIVE 409 · DEVICE_NOT_STABLE 423 · REALTIME_UNAVAILABLE 503, 기기 등록: CODE_NOT_FOUND 404 · CODE_ALREADY_REGISTERED 409 · DEVICE_ALREADY_ACTIVE 409 · TOO_MANY_ATTEMPTS 429 · RATE_LIMITED 429 · CODE_ISSUE_FAILED 503 · BOOTSTRAP_NOT_FOUND 401 · NOT_REGISTERED_YET 409 · BOOTSTRAP_CLOSED 410 · DEVICE_NOT_REGISTERED 401 · ALREADY_REGISTERED 409, 앱 사이(내부): INVALID_ENVELOPE 400 · EVENT_CONFLICT 409 · INVALID_DEVICE_TOKEN 401 · DEVICE_NOT_CONNECTED 423. 내부 코드(DEVICE_NOT_CONNECTED 등)는 보호자 응답에 내보내지 않고 번역한다(DEVICE_NOT_CONNECTED → DEVICE_NOT_STABLE). message에는 전사 원문 · 개인정보를 넣지 않는다. traceId는 요청마다 만들어 로그와 연결한다."
    violation: "—"
    source: "J24 · 계약 2절 · NFR9 · J59(a)(b)(c)(d)(e)"
  - id: BR8.7
    statement: "비밀과 개인 정보는 로그 · 응답 · 번들에 남기지 않는다."
    category: "constraint"
    applies_to: "모든 로그 · 응답 · 프런트 번들"
    trigger: "로그를 쓸 때, 응답을 만들 때"
    logic: "금지: 공급자 키 · 내부 토큰 · 세션 쿠키 · 기기 토큰 · bootstrap 비밀(해시도 응답 금지) · 전사 원문 · 원음 · 보호자 입력 원문. 등록 코드는 앞 4글자만 로그에 남긴다. Nginx 접근 로그는 쿼리 문자열과 쿠키를 남기지 않는다. 키 · 비밀은 환경 변수(.env는 저장소에 올리지 않음)로만 주입한다."
    violation: "보안 결함."
    source: "NFR9 · GL-01 · 공통 완료 조건"
  - id: BR8.8
    statement: "상태를 바꾸는 보호자 요청은 CSRF 토큰 헤더를 요구한다."
    category: "authorization"
    applies_to: "POST · PUT · PATCH · DELETE 보호자 REST (아래 면제 목록 제외)"
    trigger: "보호자 REST 호출"
    logic: "X-CSRF-TOKEN 헤더가 세션의 토큰과 맞아야 한다. 취득 경로 · 재취득 시점은 NFR Design(3.3)이 정한다. SameSite=Lax만으로는 충분하지 않다. 면제는 경로 접두어가 아니라 정확한 경로 목록으로만 한다: POST /api/dev/login(dev 한정), 구글 로그인 교환(U2), POST /api/device/bootstrap · POST /api/device/bootstrap/claim(기기 쿠키 인증, SameSite=Strict), /internal/**(X-Internal-Token). POST /api/device/register를 포함한 그 밖의 보호자 세션 변경 요청은 모두 CSRF 헤더가 필수이고, /api/device/** 전체를 면제하지 않는다."
    violation: "403 CSRF_TOKEN_INVALID."
    source: "J21 · ADR-008 · J59(e)"
  - id: BR8.9
    statement: "바깥에서 닿는 길은 Nginx 하나이고 /internal/** 은 막으며 WSS 업그레이드를 통과시킨다."
    category: "constraint"
    applies_to: "Nginx"
    trigger: "모든 외부 요청"
    logic: "HTTPS 종료, /ws/device WebSocket 업그레이드 통과(Realtime), /api/** Core, /device · /parent Next.js, /internal/** 403(바깥), 승인 음성 정적 경로는 토큰 없이 읽기만 가능한 개인정보 없는 파일. Nginx는 X-Forwarded-For를 `$remote_addr`로 덮어써 넘긴다(덧붙이지 않는다, BR1.6). 앞에 로드밸런서가 생기면 신뢰 프록시 설정을 3.4에서 바꾼다."
    violation: "—"
    source: "ADR-006 · J26"
  - id: BR8.10
    statement: "시각은 ISO 8601 UTC로 주고받고 날짜 질의는 한국 시간 달력 날짜다."
    category: "constraint"
    applies_to: "모든 API · 이벤트"
    trigger: "시각을 쓸 때"
    logic: "DB는 timestamp(UTC), 응답은 2026-10-15T09:00:00Z 꼴. 날짜 질의(date · month · from · to)는 Asia/Seoul 달력 날짜."
    violation: "—"
    source: "계약 2절"
  - id: BR8.11
    statement: "모든 시한과 주기는 설정 키(mujoong.timing.*)에서 읽고 코드에 숫자를 박지 않는다. 시험은 같은 키를 줄여 주입한다."
    category: "constraint"
    applies_to: "시간 계약 전체"
    trigger: "시한을 쓰는 모든 코드"
    logic: "contract-summary.md 7절에 키가 있는 값은 그 키를 따르고 값이 바뀌면 7절을 먼저 고친다. 7절에 키가 없는 값(내부 HTTP 제한 J26, Outbox 폴러 · 재시도 간격 · Inbox 재호출 J49, 처리기 선점 30초, 출력 구간 조용 시간 J52, 실패 페이로드 보관 기간 J50, 묘비 10분, 코드 재추첨 횟수 J45, D0 재시도 5초, 「등록되었어요」 3초, 연결 점검 주기)은 `mujoong.*` 설정 키로 읽는다. 키 이름과 값은 NFR Design(3.3)이 확정해 3.5의 contracts/timing.md에 더하기만 한다. 코드에 숫자를 박지 않고 시험은 같은 키를 줄여 주입한다."
    violation: "—"
    source: "NFR6 · 계약 7절 · J51"

  # ═════════════ BR9 · 아이 기기 웹 D0 · T19 최소 ═════════════
  - id: BR9.1
    statement: "아이 기기 웹은 시작할 때 me → status → bootstrap 순서로 자기 상태를 확인해 D0 또는 T19로 간다."
    category: "policy"
    applies_to: "DeviceWeb 시작"
    trigger: "/device 열기"
    logic: |
      ① GET /api/device/me → 200이면 T19(꺼짐, J56). 401이면 ②.
      ② GET /api/device/bootstrap/status를 부른다(bootstrap 쿠키는 HttpOnly라 기기 웹 JS가 유무를 볼 수 없으므로 서버가 판정한다): REGISTERED면 claim(BR3.1) 뒤 「등록되었어요」→ T19, PENDING이면 ③, EXPIRED · 401 BOOTSTRAP_NOT_FOUND면 ④.
      ③ POST /api/device/bootstrap(BR1.3)이 같은 코드를 돌려주고 D0 코드 표시 · 2초 폴링.
      ④ POST /api/device/bootstrap으로 새 코드를 받아 D0.
      ③ · ④에서 POST /api/device/bootstrap이 409 ALREADY_REGISTERED이면 claim(BR3.1)으로 간다(경쟁으로 등록이 끼어든 경우).
      claim 결과: 200 → 「등록되었어요」 3초 → T19, 401 → ④, 410 → /device/me로 확인(200이면 T19, 401이면 ④), 409 NOT_REGISTERED_YET → 2초 뒤 status를 다시 조회.
      WSS가 4401로 닫히면 ①부터 다시. 기기 웹은 bootstrap과 claim을 한 번에 하나만 보내고 응답이 올 때까지 같은 요청을 다시 보내지 않는다(단일 비행). claim은 서버에서 Device 행 잠금 아래 처리한다. 쿠키 유무를 클라이언트가 판단하지 않고 localStorage 등 클라이언트 표시값도 두지 않는다(BR1.5 · AC6.1.4).

    violation: "—"
    source: "AC6.1.1 · AC6.1.2 · AC6.1.5 · AC6.2.8 · OQ13 · J56"
  - id: BR9.2
    statement: "D0은 안내와 큰 코드를 보이고 등록되면 「등록되었어요」를 3초 보인 뒤 T19로 간다."
    category: "policy"
    applies_to: "D0 화면"
    trigger: "코드 표시, 등록 완료"
    logic: "안내 '보호자 앱에서 이 코드를 넣어 주세요' + 4글자 · 4글자 코드('7F3K · 92QD' 꼴, C.D-00.30.2~3). 상태: 코드 표시 / 등록 대기 / 등록되었어요(3초). 코드는 새로고침해도 유지(BR1.3). 만료되면 조용히 새 코드로 바뀐다(BR1.4). 등록 직후 기기는 T19 꺼짐으로 착지하고 「켜기」를 누른 뒤에야 대기가 된다(J56). 클릭 요소가 없다."
    violation: "—"
    source: "AC6.1.1 · AC6.1.2 · AC6.1.5 · J56"
  - id: BR9.3
    statement: "브라우저 데이터가 지워졌거나 다른 기기이면 D0으로 돌아가 새 코드를 받고, 보호자가 새 코드를 등록하는 순간 옛 기기는 해제된다."
    category: "policy"
    applies_to: "DeviceWeb, DeviceRegistry"
    trigger: "쿠키 없음 · 모르는 토큰"
    logic: "쿠키가 지워진 기기 웹은 BR9.1 ④로 새 코드를 받는다. 보호자가 그 코드를 등록하면 BR2.4 · BR2.5로 옛 기기가 RELEASED가 되고 옛 토큰의 WSS 접속은 거부된다. 그 전까지 04 · 05는 '응답 없음 · 마지막 hh:mm'이다(옛 기기가 정상 닫기로 끝난 경우. 닫기 프레임 없이 끊겼으면 40초 뒤 '끊김'이다, BR4.8). 보호자 쪽 재등록 진입 경로는 OQ12(Should)."
    violation: "—"
    source: "AC6.1.3 · AC6.2.8 · B3"
  - id: BR9.4
    statement: "아이 기기 화면에는 기술 문구를 보이지 않는다. D0에서 아직 코드를 받지 못한 동안만 '연결을 확인하는 중'을 보이고, 그 밖의 네트워크 실패는 화면을 유지하고 5초 뒤 다시 시도한다."
    category: "policy"
    applies_to: "D0 · T19"
    trigger: "요청 실패 · 429 · 503"
    logic: "D0에서 코드를 아직 받지 못한 상태(첫 로드 · 코드 요청 bootstrap 실패: 429 · 503 · 네트워크)는 '연결을 확인하는 중'(개발명세 D-00 오류 · interaction-spec 4절)을 보이고 5초 뒤 같은 요청을 다시 한다. 이미 코드가 보이는 중의 status 폴링 실패와 T19의 HTTP 실패는 마지막 화면을 그대로 두고 5초 뒤 다시 한다. 마이크 허용 거부(BR9.5)와 이 문구만 아이 기기 화면의 글이다. T19 '다시 연결하고 있어요'(mockups 9-31)는 U3 몫이다."
    violation: "—"
    source: "개발명세 D-00 · interaction-spec 4절 · NFR1"
  - id: BR9.5
    statement: "T19의 클릭 요소는 「켜기」와 「끄기」뿐이다. 「켜기」는 마이크 · 소리 허용을 받은 뒤 연결하고, 허용 거부면 연결하지 않는다."
    category: "policy"
    applies_to: "T19 (U1 최소)"
    trigger: "「켜기」 · 「끄기」"
    logic: "「켜기」: 마이크 · 소리 허용을 요청 → 허용되면 화면 켜짐 유지(Wake Lock)를 잡고 WSS 접속(BR3.6) → HELLO_OK 뒤 대기. 거부면 '마이크를 허용해 주세요' + '브라우저 설정에서 마이크와 소리를 켠 뒤 다시 「켜기」를 눌러요.' + 「켜기」를 보이고 연결하지 않는다. 「끄기」: 재생 · 마이크를 정리하고 close 1000(DEVICE_OFF)으로 닫아 꺼짐이 된다. 활동 중 「끄기」는 연결 끊김으로 처리돼 40초 경로로 간다(BR6.12 ④, 별도 즉시 종료 신호 없음). 「켜기」 · 「끄기」는 가장 큰 터치 영역이고 「끄기」는 캐릭터와 떨어져 있다."
    violation: "—"
    source: "AC6.2.1 ~ AC6.2.3 · AC6.2.9 · AC6.2.11 · AC6.2.13(U3) · B16"
  - id: BR9.6
    statement: "T19의 표시 상태는 서버의 SESSION_STATE와 기기의 실제 재생에서 정하고, ?debug=1이면 상태 로그를 보인다. U1 최소 표시는 꺼짐 · 대기 · 듣는 중 · 말하는 중 · 마무리 · 마이크 허용 안 됨(BR9.5)이다."
    category: "policy"
    applies_to: "T19"
    trigger: "SESSION_STATE 수신, 재생 시작 · 끝"
    logic: "대기(STANDBY) · 듣는 중(LISTENING) · 생각 중(THINKING) · 마무리(CLOSING)는 SESSION_STATE.view로 받고, '말하는 중'은 기기가 승인된 소리를 실제로 재생하는 동안만이다. 마무리(CLOSING)는 서버가 STOP_OUTPUT과 함께 보내는 SESSION_STATE{view: CLOSING}으로, 대기 복귀는 서버가 종료 전이 뒤 보내는 SESSION_STATE{view: STANDBY}로 받는다(BR6.10). 생각 중 · 연결 끊김 · 다시 연결 중 · 마이크 허용 안 됨 8가지 전부 주입 시험과 표정 · 문구는 U3가 완성한다. ?debug=1일 때 마지막 상태가 화면 표시와 같음을 보이는 로그를 낸다."
    violation: "—"
    source: "AC6.2.4(U3) · AC6.2.5 · AC6.2.9"
  - id: BR9.7
    statement: "토큰이 거부되면(RELEASED · 계정 삭제 · 쿠키 삭제) 기기 웹은 D0으로 가서 다시 등록한다."
    category: "policy"
    applies_to: "DeviceWeb"
    trigger: "WSS 4401, /device/me 401"
    logic: "4401을 받으면 /device/me로 확인하고 401이면 D0(BR9.1 ②~④). 4001(대체됨)은 D0이 아니라 꺼짐이다(BR3.7). 그 밖의 닫기 코드(1001 · 1011 · 1013 · 4400 · 4408)는 켜짐 상태일 때 BR9.4대로 마지막 화면을 유지하고 5초 간격으로 다시 접속한다(「끄기」 뒤에는 하지 않는다). 1 · 2 · 4초 백오프(device.reconnect-backoff) · HELLO resume · '다시 연결하고 있어요' 표시는 U3가 완성한다."
    violation: "—"
    source: "AC6.2.8 · J22"
```

## 3. 요약

| 묶음 | 규칙 수 | 한 줄 |
|---|---|---|
| BR1 부트스트랩 · 코드 | 7 | 8글자 31알파벳 코드를 24시간 발급하고 같은 쿠키엔 같은 코드, 비밀은 해시로 |
| BR2 보호자 등록 | 10 | 코드 조회 404 · 409 구분, 조건부 UPDATE 한 건만 성공, 연결돼 있지 않으면 교체(옛 기기 먼저 RELEASED + DeviceReleased 같은 트랜잭션), 계정 삭제 참가자 |
| BR3 토큰 · 인증 | 9 | claim은 첫 연결 전 회전 가능, WSS는 Origin + 쿠키, 새 연결이 옛 연결 대체, 내부 호출은 공유 비밀 |
| BR4 연결 상태 사본 | 8 | 최신 보고만 반영, 판정은 읽을 때 계산, 30초 stale = 확인 불가, 시작 가능 = 안정만 |
| BR5 영속 메시징 | 14 | 같은 트랜잭션 저장, 직렬 발송과 백오프, 커밋 뒤 ACK, 해시로 중복 · 충돌, 처리기 선점, 끝나면 페이로드 비움, 계정 삭제 참가자 |
| BR6 세션 슬라이스 | 17 | 멱등 먼저 + 시작 거절 순서 · 하나만, 전이표(조건부 승자 하나), 끝내기 + 마무리 인사 1회, Core 포기 세션 정리, 네 안전 시한, 재시작 FAILED |
| BR7 음성 · 게이트 | 6 | ACTIVE 뒤에만 입력 중계, 서버 키로 공급자 연결, 800ms 출력 구간, 폐기 → 대체 문구, 승인된 소리만 재생 |
| BR8 실행 골격 · 공통 | 11 | 스키마 · 역할 분리, Flyway V1, 프로필별 시험 로그인, 404 숨김, 오류 봉투, 로그 위생, CSRF, Nginx |
| BR9 기기 웹 | 7 | me → status → bootstrap 순서, D0 3초, 쿠키 삭제 → D0, 기술 문구 없음, 켜기 · 끄기만 |

스토리 AC와 규칙의 연결은 `traceability.json`, 시간값은 `contract-summary.md` 7절, 유닛 사이 넘기는 몫은 `functional-spec.md` 7절에 있다.
