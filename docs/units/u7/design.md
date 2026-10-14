# U7 웹 푸시 설계

## 기준과 범위

- 기준 main: `7a22b67`, 지시서: `194cdaa`, 작업 브랜치: `unit/u7-web-push`.
- `docs/units/README.md`, `brief.md`와 인셉션의 FR7.10, US1.7, mockups §9-35, Notification, C6, `contracts/timing.md`, U4a `review-1.md` 4절을 읽었다.
- 화면 설계가 우선이다. 10/13 변경 기록의 Must · 사건 4종은 이전 Should · 3종을 대체한다. 종료 알림 진입은 U4a 리뷰와 U7 지시서에 따라 sessionId 개별 조회를 쓴다.
- Core Notification · 보호자 화면만 구현한다. U5b의 Realtime과 U6의 결과 생성은 변경하지 않는다. V6은 유지하고 실제 병합은 U6 V5 뒤에 사람이 한다.
- AI-DLC 엔진 없이 기존 코드 파악 → 이 설계 → 층별 실패 시험/구현 → 검증/보고 순으로 진행한다.

## 엔터티

정본은 `core/V6__u7_web_push.sql`이다. 기존 표에는 제약이나 열을 추가하지 않는다. 시각은 앱 시계에서 전달하며 SQL 현재 시각 기본값을 쓰지 않는다.

### core.core_push_subscription (Notification / U7)

| 열 | 타입 | 제약 · 의미 |
|---|---|---|
| id | uuid | PK, 앱 생성 |
| guardian_id | uuid | NOT NULL, core_guardian(id) FK, 삭제는 참가자가 수행 |
| endpoint | text | NOT NULL, UNIQUE, HTTPS push 주소 |
| p256dh | text | NOT NULL, base64url P-256 수신 공개 키 |
| auth | text | NOT NULL, base64url 16바이트 인증 비밀 |
| platform | varchar(40) | NOT NULL, 비어 있지 않은 클라이언트 구분 |
| created_at | timestamptz | NOT NULL |
| last_used_at | timestamptz | nullable, 전송 승인 시각 |
| revoked_at | timestamptz | nullable, 현재 기기 해제 시각 |

색인: PK, endpoint 유일, guardian_id의 활성 구독 부분 색인(`revoked_at IS NULL`). endpoint · 키는 문자열 표현과 로그에서 가린다. 플랫폼은 정본에 열거형이 없어 길이만 제한한다.

### core.core_push_dispatch (Notification / U7)

| 열 | 타입 | 제약 · 의미 |
|---|---|---|
| id | uuid | PK, 앱 생성 |
| guardian_id | uuid | NOT NULL, core_guardian(id) FK |
| session_id | uuid | NOT NULL, Core 활동의 논리 참조 |
| event_type | varchar(40) | NOT NULL, 아래 4종 CHECK |
| event_key | text | NOT NULL, UNIQUE, 세션과 논리 사건 식별자 |
| status | varchar(20) | NOT NULL, REQUESTED/SENT/FAILED/SKIPPED CHECK |
| attempt_count | integer | NOT NULL, 0 이상, 발송 라운드 수 |
| last_error_code | varchar(80) | nullable, 비밀 없는 코드만 |
| requested_at | timestamptz | NOT NULL |
| sent_at | timestamptz | nullable, push 서비스 승인 시각 |
| activity_type | varchar(20) | NOT NULL, FREE_TALK/PRACTICE CHECK |
| next_attempt_at | timestamptz | NOT NULL, 다음 라운드 가능 시각 |
| claim_id | uuid | nullable, 점유 세대 |
| lease_until | timestamptz | nullable, 점유 기한 |
| recipient_progress | jsonb | NOT NULL, 구독 UUID별 상태 · 횟수 · 안전한 오류 코드 |

색인: PK, event_key 유일, guardian_id, REQUESTED의 `(next_attempt_at, requested_at)` 부분 색인. 기본 10열에 재시작·다중 기기 처리를 위한 5열을 더한다. 수신처 목록은 사건 생성 시 고정하며 endpoint나 키를 JSON에 복사하지 않는다. 신규 기기는 이미 생긴 사건을 받지 않는다.

## API

| 경로 | 요청 · 응답 | 규칙 |
|---|---|---|
| PUT /api/push-subscription | `{endpoint,keys:{p256dh,auth},platform}` → 204 | 같은 보호자 endpoint는 키 갱신/재활성화, 다른 보호자 소유 endpoint는 409 PUSH_SUBSCRIPTION_CONFLICT |
| DELETE /api/push-subscription | `{endpoint}` → 204 | 현재 보호자의 그 endpoint만 해제, 없거나 타인 것이어도 204 |
| GET /api/push-subscription/vapid-public-key | `{enabled,publicKey}` → 200 | 세션 필요, 비활성은 false/null, 비밀 키와 subject는 반환하지 않음 |

공통 세션 없음 401, 쓰기의 CSRF 없음 403, 삭제 접수 계정은 기존 계정 오류 규칙, 잘못된 입력 400. 기존 보안 필터와 no-store 응답을 사용한다. PUT은 저장 API이며 키 미설정이어도 유효한 구독을 저장할 수 있다. 프런트는 enabled=false일 때 켜기를 막되 해제는 허용한다.

endpoint는 사용자 정보/fragment 없는 절대 HTTPS 주소, 포트는 기본 또는 443만 허용한다. p256dh는 올바른 곡선 위의 65바이트 공개 점, auth는 16바이트를 검증한다. 오류에 원문 입력을 넣지 않는다. 전송 때는 DNS 결과까지 공인 IP인지 검증한다.

## 사건 규칙 (원 FR7.10 · AC1.7.1~1.7.8)

| 사건 | 생성 조건 | 유일 키 | 제외 |
|---|---|---|---|
| VOICE_PROBLEM | 최신 상태 반영 후 진행 중, technicalPause의 DEVICE/PROVIDER와 since 있음 | sessionId + VOICE_PROBLEM + kind + since | 오래된 버전, 종료 후 도착, 앱 연결만의 문제 |
| DEVICE_STATUS_UNKNOWN | 등록 기기 + 진행 중 사본 + reported_at이 StaleEvaluator의 30s 기준보다 엄격히 오래됨 | sessionId + STALE | 정확히 30s, 미등록 기기, 종료 세션 |
| ENDED_BY_CONNECTION | confirmed=true, ABORTED, end_reason=DISCONNECT | sessionId + ENDED_BY_CONNECTION | 종료 확인 전, 다른 종료 사유 |
| ACTIVITY_COMPLETED | started_at 있음, ENDED, confirmed=true, CHILD_STOP/GUARDIAN_STOP/SILENCE | sessionId + ACTIVITY_COMPLETED | CLOSING, ENDED_UNCONFIRMED, DECLINED, FAILED, ABORTED, 준비 취소 |

- 기존 SessionStateChangedHandler 한 곳에서 사본과 dispatch를 같은 Inbox 트랜잭션에 저장한다. 중복 처리기를 등록하거나 AFTER_COMMIT 메모리 사건에 기대지 않는다.
- 보호자 상태와 실제 세션 소유자를 확인한다. 잠금 순서는 guardian → activity → push 행이며 삭제 접수 뒤에는 생성/발송하지 않는다(AC1.7.5).
- 역순 ENDED 다음 ACTIVE가 오면 기존 started_at 보완 뒤 현재 사본으로 완료 자격을 다시 판정한다. 완료 키는 동일하므로 한 행만 남는다(AC1.7.8).
- 아직 보고된 적 없는 등록 기기는 활동 requested_at을 관찰 시작점으로 삼는다. 오래된 수신 시각으로 장치를 정상이라고 보지 않는다. 주기 점검은 UI 실행 여부와 무관하다.
- 종료 반영 시 대기 중 장애 알림을 SKIPPED로 바꾼다. 전송/재시도 직전에도 계정·세션을 읽어 종료 뒤 장애 알림을 차단한다. 기술 종료와 정상 완료는 동시에 생성하지 않는다.
- 구독이 없으면 사건은 SKIPPED로 기록한다. 이벤트 행 멱등과 외부 서비스의 전달 보장은 구별한다.

## 발송 · 장애 복구

1. 폴러가 기한이 된 REQUESTED를 claim_id/lease로 점유한다. 응답 기록은 그 claim_id가 여전히 일치할 때만 갱신한다.
2. 수신처별로 현재 활성 구독과 계정·세션을 확인한다. guardian/activity 공유 잠금 아래 제한 시간 내 한 번 전송하여 삭제/종료 변경과 순서를 정한다. 점유는 수신처마다 갱신한다.
3. 서버 본문은 `{type,activityType,sessionId,sentAt}`만 직렬화한다. sentAt은 앱 시계다. 전송 승인마다 해당 수신처 결과를 즉시 영속화하여 다른 기기의 재시도 때문에 성공한 기기에 다시 보내지 않는다.
4. 2xx는 SENT(서비스 승인), 410/404는 해당 구독 삭제 및 EXPIRED, 그 밖 오류는 재시도한다. 최초 + `[5s,30s,2m]` 재시도 3회 후 FAILED다. 응답 본문/헤더/URL은 기록하지 않는다.
5. 모든 수신처가 끝났을 때 실패가 있으면 FAILED, 실패 없이 승인된 기기가 있으면 SENT, 전부 해제/만료/불필요이면 SKIPPED다. recipient_progress가 부분 성공을 보존한다.
6. HTTP 승인 후 DB 기록 전 프로세스 장애는 외부 발송을 정확히 한 번으로 만들 수 없다. 사건 키의 해시로 Web Push Topic과 워커 tag를 안정화해 중복 표시를 완화한다. 기술 알림 TTL=0으로 오프라인 지연 보관을 막는다. 실제 폰 표시 여부/시점은 서버가 보장하지 않는다.

라이브러리는 `com.the13haven:push2u-core:0.3.0`을 사용한다([공식 태그](https://github.com/the13haven/push2u/tree/v0.3.0)). Java 21, JDK EC 암호, RFC 8291/8292와 교체 가능한 HTTP SPI가 맞는다. starter는 가져오지 않는다. 신생 프로젝트이므로 실제 JWT 서명과 암호문을 독립 검증하는 시험으로 보완한다. HTTP 어댑터는 검증한 공인 DNS 결과에 연결을 고정하고 원래 hostname의 TLS/SNI 검증을 유지한다. 리디렉션과 라이브러리 자동 재시도는 끈다.

VAPID는 `MUJOONG_PUSH_VAPID_PUBLIC_KEY`, `MUJOONG_PUSH_VAPID_PRIVATE_KEY`, `MUJOONG_PUSH_VAPID_SUBJECT`만 읽는다. 모두 없으면 푸시만 비활성화한다. 일부 누락/잘못된 키/키 쌍 불일치/mailto 형식 오류는 값과 원인 예외를 숨긴 기동 실패다. 시험은 실행할 때 EC 키 쌍을 만든다. 라이브러리 내부 JWT 만료 시계와 앱의 사건/재시도 시계를 구별한다.

시간 설정 4개는 application.yml 기본값과 CoreProperties 검증, contracts/timing.md를 함께 갱신한다: dispatch-poll=1s, retry-backoff=[5s,30s,2m], send-timeout=5s, device-status-check=10s.

## 계정 삭제

DeletionParticipant.ORDER_PUSH=60 참가자를 등록한다. guardian 잠금 뒤 dispatch → subscription 순으로 삭제하며 다른 보호자 행은 남긴다. U6의 70 및 활동 기록의 90보다 앞선다. 계정 삭제 재시도에도 같은 결과다.

## 화면 · 서비스 워커

- SET0에 알림 켜기/끄기 한 줄을 둔다. **사용자 확인: 끄기는 현재 기기만** 해제한다.
- 시트 열기는 지원/권한/기존 구독만 조회한다. 권한 요청은 켜기 클릭에서만 한다. 브라우저 구독이 있어도 현재 계정 PUT 성공 전에는 서버 등록을 성공으로 추정하지 않는다. 충돌이면 재등록 안내를 표시한다.
- 끄기는 현재 endpoint DELETE 성공 뒤 브라우저 구독을 해제한다. 불명확한 응답은 동일 endpoint로 재시도할 수 있다. 비활성 키·권한 거절·미지원·실패 상태를 구별한다.
- 워커는 Next의 `/parent/push-worker` JavaScript 라우트, scope `/parent/`, no-cache로 제공한다. 기존 AudioWorklet 라우트 방식을 따른다. `/parent`는 scope 밖이므로 serviceWorker.ready에 무기한 기대지 않고 반환 registration의 활성 상태를 확인한다.
- ko.ts의 네 문구를 워커 코드에 넣는다. 완료 제목은 활동 유형으로 선택한다. 그 밖 서버 필드는 버리고 손상 본문은 표시하지 않는다.
- 클릭은 고정된 `/parent?notificationSessionId=…`로 이동한다. 같은 origin의 보호자 창만 focus/navigate하고 없으면 새 창을 연다. 기기 창을 바꾸지 않는다.
- 보호자 진입은 해당 sessionId의 GET /api/sessions/{id}를 재조회한다. REQUESTED~ACTIVE는 12, CLOSING·종료 상태는 17, 실패/UNKNOWN은 04 미확인과 다시 확인, 401은 기존 로그인 만료 흐름이다. 일반 홈 조회가 알림 조회 실패를 덮지 않는다(AC1.7.4 · AC1.4.13).
- fetch 이벤트·오프라인 캐시·PWA 매니페스트는 만들지 않는다. standalone 빌드로 워커 경로가 실제 JavaScript를 응답하는지 검증한다.

## 시험과 층별 계획

각 층은 먼저 시험을 추가하여 실패를 확인하고 구현 후 다시 실행한다. Docker 엔진이 현재 연결되지 않아 기존 환경의 재시작/초기화는 하지 않는다. 로컬 DB 시험의 실행 여부와 CI 실행 결과를 구별해서 보고한다.

1. V6/구독/삭제: 구조·endpoint 유일·자기 기기 해제·타인 소유 충돌·입력 검증·401/403/204·삭제 순서/재실행·다른 보호자 보존. 새 스키마에 맞춰 Flyway 표 수와 버전 기대값을 정확히 올린다.
2. 사건/dispatch: 네 유형과 모든 제외 상태, 정확한 30s 경계, 중복/재시작/동시 처리, 역순 ACTIVE 보완, 트랜잭션 롤백, 삭제 중·오래된 장애 차단.
3. 발송: 실행 시 생성 키, JWT 검증/독립 복호화로 네 필드만 확인, ContractStubServer를 통한 성공/404/410/실패/timeout, 부분 성공 재시도, 만료 claim, 삭제·종료 경합, 비공인 DNS/redirect 차단, 설정 누락/유효성.
4. 화면: 네 문구·손상 payload·클릭 창 선택·최신 세션별 분기·현재 endpoint 해제·권한/설정/실패/연타/시트 닫힘·standalone 워커 응답.

U7 backend/frontend 범위 파일을 만들고 줄 커버리지 80%를 확인한다. 기존 유닛 태그와 전체 lint/typecheck/계약/아키텍처 회귀를 확인한다. PR CI와 별도 workflow_dispatch skeleton 결과를 report.md에 쓴다. 실제 Android Chrome의 화면 꺼짐 수신/클릭은 수행 여부를 별도로 적으며 자동 시험으로 대체 완료 처리하지 않는다.

## 판단 기록

| 번호 | 준하 제안(가정) · 근거 |
|---|---|
| U7-J1 | 한 Inbox 처리기 안에서 guardian 잠금 → 사본 → dispatch. BR5.7과 U4a 리뷰의 단일 처리기 등록 규칙을 따른다. |
| U7-J2 | 같은 일시 정지의 반복 상태 보고는 kind+since 키로 합친다. 정상/기술 종료는 세션별 키로 멱등 처리한다. |
| U7-J3 | DELETE 본문의 endpoint로 현재 기기만 해제한다. 사용자 답변으로 확정했다. |
| U7-J4 | 다른 보호자의 endpoint는 409 PUSH_SUBSCRIPTION_CONFLICT. 주소를 안다는 이유로 소유자를 바꾸지 않는다. |
| U7-J5 | VAPID GET은 enabled/publicKey를 반환한다. 모두 미설정은 꺼짐, 일부 설정은 안전한 기동 오류다. |
| U7-J6 | dispatch에 5열과 수신처 진행 상태를 추가하여 부분 성공과 재시작을 복구한다. 실제 전달은 보장하지 않는다. |
| U7-J7 | 최초 보고가 없는 등록 기기는 requested_at 기준으로 stale을 판정한다. 미등록 기기는 제외한다. |
| U7-J8 | V6은 V1~V4와 함께 깨끗한 시험 DB에 적용한다. V5 부재는 현재 개발 기준이며 운영 병합은 U6 뒤다. |
| U7-J9 | 기존 전역 SW 금지 규칙은 U7 보호자 워커/등록 모듈만 좁게 허용한다. 기기 등록 0회·PWA 금지 단언은 유지한다. |
| U7-J10 | Flyway/SchemaVersionCheck의 고정 숫자는 추가된 두 표와 V6에 맞춘다. 기존 검증 의도와 하한은 유지한다. |
| U7-J11 | 기술 알림은 전송 직전 재확인과 TTL=0, Topic/tag를 쓴다. 승인 뒤 실제 표시 시점까지 절대 보장할 수는 없다. |
| U7-J12 | U6 완료 화면의 내용을 구현하지 않는다. U7은 기존 화면 17 진입만 책임진다. |
| U7-J13 | endpoint는 ASCII 최대 2048자, HTTPS 기본 포트/443으로 제한한다. UNIQUE 색인의 과도한 키 길이와 임의 포트 접근을 막는다. |
| U7-J14 | 워커 tag는 sessionId+type이다. 서버 payload 네 필드를 지키므로 사건 키를 추가하지 않으며 같은 세션의 같은 종류는 최신 표시로 바꾼다. |
| U7-J15 | 최신 상태가 기술 정지 해제/교체를 나타내면 이전 대기 VOICE 요청도 같은 사본 트랜잭션에서 SKIPPED로 바꾼다. 전송의 activity 공유 잠금과 순서를 맞춘다. |
| U7-J16 | 네 유형 모두 Web Push TTL=0을 사용한다. 오프라인 단말을 위한 서비스 보관은 지원하지 않는다. 앱 닫힘/화면 꺼짐과 단말의 네트워크 단절은 구별하며 Android 실기 검증은 별도로 남긴다. |
| U7-J17 | 로컬 Docker가 연결되지 않아 CI의 기존 전체 검증 뒤 U7 태그/범위 검증을 추가한다. 기존 전체 시험과 하한은 유지하고 보고서도 따로 보존한다. |
| U7-J18 | 알림 진입도 기존 계정 동의/아이 등록/삭제 조건을 먼저 확인한다. 해당 진입 뒤 완료한 세션 재조회만 이동 근거로 쓰고, 이전 Query 캐시는 이동에 쓰지 않는다. 계정 확인 실패도 미확인으로 남긴다. |

계약 보강은 parent-api.yaml, errors.md, deviations.md(U7-a 및 필요한 후속 항목), timing.md, ERD N5/N6에 함께 반영한다. 저장소 루트 README, contracts/README, aidlc와 Realtime 코드는 변경하지 않는다.
