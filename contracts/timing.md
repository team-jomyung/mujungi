# 시간 계약 (timing.md)

> 계약 7절(T, WBS 1.7.6) 정본을 코드가 읽는 설정 키로 옮긴 표. 소유: U4(B), U1이 만든다. 숫자 시간값은 이 파일과
> 각 앱의 `application.yml` 한 곳에만 두고 코드에 박지 않는다(BR8.11 · NFR6.1). 값이 바뀌면 계약 7절 → 이 파일 →
> `application.yml` 순서로 고친다. 7절 밖 키는 이 파일에 **더하기만** 한다(BR8.11 · RD-24).

## 읽는 법

- **키**: Spring 설정 키. 계약 7절 키 `<그룹>.<이름>`은 `mujoong.timing.<그룹>.<이름>`으로 읽는다.
- **기본값**: `application.yml`에 쓰는 표기 그대로다(Spring `Duration` 표기 `500ms` · `5s` · `10m` · `24h` · `30d`, 목록은 `[...]`).
  `ConfigKeyContractTest`(계획 Step 36)가 이 열 ↔ `application.yml` ↔ 타입 있는 설정 레코드의 이름 · 기본값을 대조한다.
- **소유 앱**: 값을 읽는 컨테이너. `core-api` · `realtime` · `web`(Next.js, `/parent` · `/device`).
  web은 같은 키를 Spring 이완 바인딩 규칙의 환경 변수 이름(대문자, `.` → `_`, `-` 제거)으로 받고 서버 컴포넌트가 첫 응답에 싣는다
  (빌드 때 굽지 않음, LC-2 · J94). 예: `mujoong.timing.parent.poll-interval` → `MUJOONG_TIMING_PARENT_POLLINTERVAL`.
- **시험 값**: 시험이 줄이는 값의 유일한 출처는 각 앱의 `src/test/resources/application-test.yml`이다(계획 Step 5). 경계 시험은
  기본값 그대로 가변 Clock을 앞당겨도 확인한다(NFR6.2).
- 시각은 모두 앱 Clock(`MujoongClock`) 기준이다. SQL `now()`를 시한 판정에 쓰지 않는다(RD-2).

## 1. 계약 7절 키 — U1 · U3가 읽는 것

| 키 | 기본값 | 소유 앱 | 시계 시작점(t0) → 끝 | 근거 |
|---|---|---|---|---|
| `mujoong.timing.session.preparing-timeout` | `10s` | core-api | Core가 REQUESTED 사본을 만든 시각 → PREPARING 이벤트가 없으면 로컬 FAILED + CORE_ABANDONED 취소 | BR6.12 ① · B5 |
| `mujoong.timing.session.device-ack-timeout` | `20s` | realtime | START_SESSION 송신을 시도한 시각 → ACK · DEVICE_READY(OK) · 공급자 `session.started`가 모두 확인돼 ACTIVE가 되지 않으면 FAILED. ACK 또는 DEVICE_READY가 빠졌으면 DEVICE_NO_ACK, 둘은 왔는데 `session.started`가 없으면 PROVIDER_UNAVAILABLE [편차 (h)] | BR6.12 ② · NFR6 · B7 · J59(h) |
| `mujoong.timing.session.closing-confirm` | `30s` | realtime | CLOSING 전이를 이긴 커밋 시각 → VOICE_SESSION_CLOSED가 없으면 ENDED_UNCONFIRMED. 끊김보다 우선. U4b: DECLINED 뒤 마무리 인사 대기 상한으로도 쓴다 | BR6.12 ③ · B11 · U4b-J8 |
| `mujoong.timing.session.join-wait` | `20s` | realtime | 시작 안내(참여 질문) 재생 완료 보고를 서버가 받은 시각 → 응답 없으면 재안내(U4b). 기술 일시정지 동안 멈춘다 | 계약 7절 · U4b-J4 · U4b-J5 |
| `mujoong.timing.session.join-wait-after-reprompt` | `20s` | realtime | 재안내 · 되묻기 · 대안 재생 완료 보고 수신 → 응답 없으면 재안내(아직 안 썼으면) 또는 DECLINED(U4b) | 계약 7절 · U4b-J4 |
| `mujoong.timing.session.silence-warning` | `4m` | realtime | ACTIVE 공백 누적(기기 재생 · 서버 출력 대기 · 기술 일시정지 · 확인 질문 대기 동안 멈춤) → 12 경고 + SILENCE_WARNING 1회(U4b) | 계약 7절 · U4b-R6 · U4b-J10 · U4b-J11 |
| `mujoong.timing.session.silence-end` | `6m` | realtime | 같은 공백 누적 → CLOSING(SILENCE)(U4b) | 계약 7절 · U4b-R6 · B9 |
| `mujoong.timing.device.gap-display` | `10s` | realtime | WAITING_CHILD · ACTIVE에서 마지막 기기 메시지 → 기술 일시정지(technicalPause) · 끊김 구간(DISCONNECT) 시작, 12 점선(U4b) | 계약 7절 · U4b-R7 · U4b-J13 |
| `mujoong.timing.device.heartbeat-interval` | `5s` | realtime · web | 기기 HEARTBEAT 송신 주기. Realtime이 HELLO_OK.heartbeatIntervalMs로 내려준다 | ADR-005 · J27 |
| `mujoong.timing.device.hello-timeout` | `5s` | realtime | WSS 업그레이드 → HELLO 없으면 4408 | BR3.6 ③ · J29 |
| `mujoong.timing.device.disconnect` | `40s` | realtime | 마지막 기기 메시지 → 연결 DISCONNECTED(영속 last_seen_at 주기 점검), ACTIVE 세션은 ABORTED(DISCONNECT, 세션별 메모리 시계) | BR4.8 ② · BR6.12 ④ · B10 |
| `mujoong.timing.device.status-report-interval` | `10s` | realtime(core-api는 기동 검증 I-6에만 읽음) | Realtime → Core 연결 상태 주기 보고(OFFLINE 포함) | BR4.8 · J26 |
| `mujoong.timing.device.output-queue-max` | `20s` | realtime · web | 기기 재생 대기열 상한(초 분량) · 서버 출력 구간 상한 · 재생 중 승인 문구 표식의 만료 | J31 · J73 · PD |
| `mujoong.timing.device.unstable-rtt` | `1s` | realtime | HEARTBEAT 송신 → 응답 왕복이 이 값을 넘으면 그 연결은 UNSTABLE, 다음 정상 왕복에서 CONNECTED(U3) | BR4.8 ① · U3 design 5절 U3-R1 |
| `mujoong.timing.device.unstable-disconnect-window` | `5m` | realtime | 끊김 사건을 세는 슬라이딩 창. `mujoong.device.unstable-disconnect-count`(4절)와 짝이며 계약 7절 `unstable-disconnects` "5분 안 2회"를 창 · 횟수 두 키로 나눈 것(U3-J3) | BR4.8 ① · U3-J3 |
| `mujoong.timing.device.reconnect-backoff` | `[1s, 2s, 4s]` | web | 켜짐 상태에서 끊긴 뒤 재접속 간격(1 · 2 · 4초). 목록을 다 쓰면 `mujoong.device-web.retry-interval`(5초) 간격으로 계속(U3) | BR9.7 · J360 · U3 |
| `mujoong.timing.device.playback-mark-interval` | `1s` | web | 재생 중 PLAYBACK_MARK PROGRESS 송신 주기(끝 · 멈춤 표식은 즉시) | 계약 PlaybackMark · U3 |
| `mujoong.timing.gate.barge-in-stop` | `500ms` | web | 재생 중 아이 음성이 이 시간 이어지면 재생을 멈추고 STOPPED 표식을 보낸다(끼어들기, U3) | BR7 · U3-J20 |
| `mujoong.timing.gate.local-voice-min` | `200ms` | web | 끼어들기 판정에 넣는 최소 음성 길이(이보다 짧은 소리는 무시) | U3-J20 |
| `mujoong.timing.device.cookie-max-age` | `400d` | core-api | `GET /api/device/me` 성공마다 MUJOONG_DEVICE 쿠키 수명을 다시 이 값으로(서버 토큰은 만료 없음) | BR3.5 · B4 · J29 |
| `mujoong.timing.core.device-status-stale` | `30s` | core-api | Core가 마지막 보고를 받은 시각 → '기기 상태 확인 불가'(BR4.3 ③), 12 stale(BR6.15) | B6 · RD-6 |
| `mujoong.timing.parent.poll-interval` | `2s` | web | 12 진행 화면 폴링 주기(다른 화면은 진입 때 1회) | A2 · BR6.15 |
| `mujoong.timing.bootstrap.code-ttl` | `24h` | core-api | 코드 발급 → 만료면 EXPIRED, bootstrap 쿠키 Max-Age도 이 값 | BR1.4 · J22 |
| `mujoong.timing.bootstrap.status-poll` | `2s` | web | D0 등록 상태 확인 주기 | BR1.7 · J22 |
| `mujoong.timing.bootstrap.rate-limit.max` | `20` | core-api | `POST /api/device/bootstrap` IP당 창 안 최대 발급 수(같은 쿠키 반복은 세지 않음) | BR1.6 · J29 · [편차 (k)] |
| `mujoong.timing.bootstrap.rate-limit.window` | `1h` | core-api | 위 한도의 슬라이딩 창. 상향 600 · 1h는 `application-load-test.yml`에만 있다(dev + load-test, A6) | BR1.6 · A6 · SD-10 |
| `mujoong.timing.device.register-failure-limit.max` | `10` | core-api | 05a 등록 실패(400 · 404 · 409) 보호자당 창 안 최대 횟수 → 429 TOO_MANY_ATTEMPTS | BR2.6 · J29 |
| `mujoong.timing.device.register-failure-limit.window` | `10m` | core-api | 위 한도의 슬라이딩 창 | BR2.6 |
| `mujoong.timing.login.session-idle` | `30d` | core-api | 마지막 요청 → 보호자 세션 만료(갱신형, Spring Session JDBC) | A1 · J21 |

## 2. 계약 7절 키 — U1이 읽지 않는 것(이름만 예약)

후속 유닛이 같은 규칙(`mujoong.timing.<키>`)으로 읽고 이 절의 행을 1절 또는 그 유닛의 절로 옮긴다. 값은 계약 7절 그대로다.
U5a가 옮긴 것: `practice.scenario-request-timeout`(9절).
U3가 옮긴 것: `device.reconnect-backoff` · `device.playback-mark-interval` · `device.unstable-rtt` · `gate.barge-in-stop` ·
`gate.local-voice-min`(1절) 그리고 `device.unstable-disconnects`를 `timing.device.unstable-disconnect-window`(1절) +
`device.unstable-disconnect-count`(4절)로 나눈 것(U3-J3) [편차 (ac)].
U4b가 옮긴 것: `session.join-wait` · `session.join-wait-after-reprompt` · `session.silence-warning` · `session.silence-end` ·
`device.gap-display`(1절, BR11.1).

| 키 | 계약 7절 값 | 읽을 유닛 |
|---|---|---|
| `mujoong.timing.practice.first-response-wait` | 20초 | U5 |
| `mujoong.timing.practice.help-consent-wait` | 10초 | U5 |
| `mujoong.timing.practice.cue-budget` | 5초 | U5 |
| `mujoong.timing.practice.judge-budget` | 5초 + 재시도 1회 | U5 |

## 3. 계약 7절 밖 U1 · U4b 시간 키 (`mujoong.timing.*` U1 4종 · U4b 2종, J80 · R3-17 · U4b-J21)

| 키 | 기본값 | 소유 앱 | 뜻 | 근거 |
|---|---|---|---|---|
| `mujoong.timing.parent.request-timeout` | `10s` | web | 보호자 웹의 REST 요청 제한 시간 | J80 · PD |
| `mujoong.timing.recorder.flush-interval` | `200ms` | realtime | 전사 기록기 플러시 주기 | J73 · PD-5 |
| `mujoong.timing.cache.approved-content-ttl` | `10m` | core-api | 승인 문구 캐시 수명(U1은 DB 직접 읽기로 대신, PD-7은 3.6) | J73 · PD |
| `mujoong.timing.cache.policy-version-ttl` | `1m` | core-api | 현재 정책 버전 캐시 수명 | J73 · PD · J114 |
| `mujoong.timing.session.stop-confirm-wait` | `20s` | realtime | 종료 확인 질문(STOP_CONFIRM) 재생 완료 보고 수신 → 대답 없으면 막은 출력을 풀고 대화를 잇는다(U4b). 기술 일시정지 동안 멈춘다 | U4b-R4 · U4b-J6 · U4b-J21 |
| `mujoong.timing.session.record-settle` | `2s` | realtime | 종료 전이 뒤 공급자를 닫은 시각 → 공급자 연결이 닫히거나 이 시간이 지나면 정산 창을 끝내고 결과 스냅숏을 쓴다(U4b) | U4b-R11 · U4b-J15 · U4b-J21 |

## 4. 메시징 · 정리 · 보존 · 세션 보조 키

| 키 | 기본값 | 소유 앱 | 뜻 | 근거 |
|---|---|---|---|---|
| `mujoong.outbox.poll-interval` | `500ms` | core-api · realtime | Outbox 발송 폴러 주기(고정 지연) | BR5.2 · J49 |
| `mujoong.outbox.batch-size` | `20` | core-api · realtime | 한 사이클에 직렬로 보낼 최대 행 수 | BR5.2 |
| `mujoong.outbox.retry-backoff` | `[1s, 2s, 4s, 8s, 16s, 30s]` | core-api · realtime | 발송 실패 뒤 대기(마지막 값 반복) | BR5.3 · J49 |
| `mujoong.outbox.max-retries` | `20` | core-api · realtime | retry_count가 이 값인 행이 또 실패하면 FAILED | BR5.3 |
| `mujoong.outbox.claim-lease` | `30s` | core-api · realtime | 발송 선점 임대 시간 | BR5.2 · RD-8 |
| `mujoong.outbox.control-event-policy` | `REARM_ON_START` | core-api · realtime | FAILED 제어 이벤트를 앱 시작 때 재무장. `RETRY_FOREVER`는 보류 옵션이라 기동 실패 | A1 · RD-10 · J124 |
| `mujoong.inbox.poll-interval` | `500ms` | core-api · realtime | Inbox 재호출 폴러 주기 | BR5.7 · J49 |
| `mujoong.inbox.retry-backoff` | `[1s, 2s, 4s, 8s, 16s]` | core-api · realtime | 처리기 실패 뒤 재호출 간격(5번, 6번째 실패는 DEAD) | BR5.8 |
| `mujoong.inbox.max-retries` | `5` | core-api · realtime | 처리기 재호출 횟수 | BR5.8 |
| `mujoong.inbox.claim-lease` | `30s` | core-api · realtime | 처리기 선점(next_attempt_at = now + 이 값) | BR5.7 |
| `mujoong.inbox.handler-threads` | `2` | core-api · realtime | 처리기 풀 크기(거절 시 제출 건너뜀) | RD-9 |
| `mujoong.inbox.batch-size` | `20` | core-api · realtime | 재호출 폴러가 한 번에 집는 행 수 | R3-15 |
| `mujoong.retention.failed-payload` | `7d` | core-api · realtime | FAILED · DEAD 행 payload를 비우기까지의 보관 | BR5.11 · J50 |
| `mujoong.cleanup.messaging-interval` | `24h` | core-api · realtime | 메시징 정리 작업 주기 | BR5.11 |
| `mujoong.cleanup.device-expiry-interval` | `10m` | core-api | 등록 코드 만료 · 7일 지난 EXPIRED 삭제 작업 주기 | BR1.4 |
| `mujoong.cleanup.expired-device-retention` | `7d` | core-api | EXPIRED 행 보관 | BR1.4 |
| `mujoong.cleanup.batch-size` | `1000` | core-api · realtime | 정리 한 번에 다루는 행 수 | SCD |
| `mujoong.session.tombstone-ttl` | `10m` | realtime | Core 취소(CORE_ABANDONED) 묘비 수명. 그 sessionId의 늦은 시작은 409 SESSION_NOT_ACTIVE | BR6.6 · BR6.7 · [편차 (v)] |
| `mujoong.session.orphan-check-interval` | `30s` | realtime | 고아 세션 점검 주기(FAILED SERVER_RESTART) | R3-6 · J126 |
| `mujoong.session.timer-tick` | `1s` | core-api · realtime | Core ① DB 점검 주기, Realtime 타이머 전이 실패 재시도 간격 | RD-1 · RD-3 |
| `mujoong.session.deletion-stop-wait` | `5s` | realtime | 계정 삭제 처리기가 그 보호자의 진행 세션 실행기를 강제 정지하고 기다리는 상한. 넘기면 처리 실패로 Inbox 재시도(U4b) | U4b-R16 · U4b-J19 · U4b-J21 |
| `mujoong.device.disconnect-check-interval` | `5s` | realtime | 연결 DISCONNECTED 주기 점검(영속 last_seen_at) | BR4.8 ② |
| `mujoong.device.unstable-disconnect-count` | `2` | realtime | 창(`mujoong.timing.device.unstable-disconnect-window`) 안 끊김 사건이 이 횟수 이상이면 복귀한 연결을 UNSTABLE로 둔다. 사건이 창 밖으로 빠지면 다음 HEARTBEAT에 CONNECTED(U3) | BR4.8 ① · U3-J3 |
| `mujoong.bootstrap.code-draw-attempts` | `5` | core-api | 등록 코드 추첨 시도 수(모두 겹치면 503 CODE_ISSUE_FAILED) | BR1.2 · J45 · [편차 (c)] |
| `mujoong.gate.segment-quiet` | `800ms` | realtime | 출력 구간 확정 조용 시간 | BR7.3 · J52 |

## 5. 내부 HTTP · 복원력 · 헬스 키

| 키 | 기본값 | 소유 앱 | 뜻 | 근거 |
|---|---|---|---|---|
| `mujoong.internal-http.connect-timeout` | `500ms` | core-api · realtime | 내부 호출 연결 제한 | J74 · RD-14 |
| `mujoong.internal-http.session-start-timeout` | `3s` | core-api | `POST /internal/sessions` 제한 | BR6.5 · J26 |
| `mujoong.internal-http.session-start-retries` | `1` | core-api | 시작 호출 시간 초과에 한한 재시도 수(같은 sessionId) | BR6.7 |
| `mujoong.internal-http.session-end-timeout` | `2s` | core-api | `POST /internal/sessions/{id}/end` 제한(차단기 없음) | BR6.9 · R3-2 |
| `mujoong.internal-http.session-view-timeout` | `1s` | core-api | `GET /internal/sessions/{id}/view` 제한 | BR6.15 |
| `mujoong.internal-http.event-publish-timeout` | `3s` | core-api · realtime | `POST /internal/events` 제한 | BR5.2 |
| `mujoong.internal-http.verify-token-timeout` | `1s` | realtime | `POST /internal/devices/verify-token` 제한(넘으면 1013) | BR3.4 · BR3.6 |
| `mujoong.internal-http.device-status-timeout` | `1s` | realtime | `PUT /internal/devices/{id}/connection` 제한 | BR4.8 |
| `mujoong.internal-http.breaker-failure-threshold` | `3` | core-api | 시작 호출 회로 차단기 연속 실패 수(session_start에만) | J74 · R3-2 |
| `mujoong.internal-http.breaker-open-duration` | `5s` | core-api | 차단기 열림 시간(뒤 반열림 1건) | J74 · R3-2 |
| `mujoong.internal-http.status-round-skip-threshold` | `3` | realtime | 상태 보고 라운드 연속 실패 수(넘으면 그 라운드 건너뜀) | R3-2 |
| `mujoong.provider.connect-timeout` | `10s` | realtime | 공급자 연결 시한(`session.device-ack-timeout` 이하여야 하며 넘으면 기동 실패) | R3-1 · RD-5 |
| `mujoong.health.db-check-interval` | `5s` | core-api · realtime | DB 점검 주기 = `Retry-After` 값 | RD-16 · J124 · [편차 (n)] |
| `mujoong.health.db-failure-threshold` | `3` | core-api · realtime | 연속 실패 수 → NOT_READY | RD-16 |

## 6. 기기 웹 시간 키 (web, 5초 · 3초 고정값의 자리)

| 키 | 기본값 | 소유 앱 | 환경 변수 | 뜻 | 근거 |
|---|---|---|---|---|---|
| `mujoong.device-web.retry-interval` | `5s` | web | `MUJOONG_DEVICEWEB_RETRYINTERVAL` | D0 코드 요청 실패 · 폴링 실패 · 켜짐 상태 재접속 간격(U1 고정, 백오프는 U3) | BR9.4 · BR9.7 |
| `mujoong.device-web.registered-display` | `3s` | web | `MUJOONG_DEVICEWEB_REGISTEREDDISPLAY` | 「등록되었어요」 표시 시간 → T19 꺼짐 | BR9.2 · J56 |

web이 읽는 1 · 3절 키의 환경 변수: `MUJOONG_TIMING_PARENT_POLLINTERVAL` · `MUJOONG_TIMING_BOOTSTRAP_STATUSPOLL` ·
`MUJOONG_TIMING_DEVICE_HEARTBEATINTERVAL` · `MUJOONG_TIMING_DEVICE_OUTPUTQUEUEMAX` · `MUJOONG_TIMING_PARENT_REQUESTTIMEOUT` ·
(U3) `MUJOONG_TIMING_DEVICE_RECONNECTBACKOFF`(쉼표로 나눈 목록) · `MUJOONG_TIMING_DEVICE_PLAYBACKMARKINTERVAL` ·
`MUJOONG_TIMING_GATE_BARGEINSTOP` · `MUJOONG_TIMING_GATE_LOCALVOICEMIN`.

## 7. 시간이 아닌 관련 한도(참고 — 정본은 reliability-design.md ## 3)

본문 64KB(`mujoong.limits.rest-body-max`, 넘으면 400 VALIDATION_FAILED [편차 (r)]) · 내부 본문 2MB(`mujoong.limits.internal-event-body-max`) ·
WSS 텍스트 프레임 8KB(`mujoong.limits.ws-text-frame-max`, 넘으면 4400 [편차 (s)]) · 입력 대기열 50프레임(`mujoong.limits.session-input-queue-frames`).
나머지 비 timing 키(`limits.*` · `security.*` · `metrics.*` · `observability.*`)는 이 파일에 두지 않는다(RD-24).

## 8. U2 시간 키 (계정 삭제 지휘 · 구글 서명 열쇠 캐시, 계약 7절 밖 — 더하기만)

| 키 | 기본값 | 소유 앱 | 뜻 | 근거 |
|---|---|---|---|---|
| `mujoong.timing.account.deletion-tick` | `5s` | core-api | 계정 삭제 지휘 한 바퀴 주기(REQUESTED 요청을 이어 처리) | FR1.6 · U2-J18 |
| `mujoong.timing.account.deletion-session-wait` | `30s` | core-api | 삭제 접수 뒤 진행 세션 CONSENT_WITHDRAWN 끝내기를 보낸 시각 → 사본이 종료되지 않아도 이 시간이 지나면 데이터 삭제 시작 | AC1.6.3 · U2-J18 |
| `mujoong.timing.account.google-keys-min-age` | `1m` | core-api | 구글 서명 열쇠 캐시 바닥 — 응답 max-age가 이보다 짧으면(max-age 없음 · no-store · no-cache = 0 포함) 이만큼은 둔다(로그인 한 번에 두 번 받지 않게). `google-keys-max-age`보다 길면 기동 거부 | U2 3차 리뷰 3 · U2-J2 |
| `mujoong.timing.account.google-keys-max-age` | `6h` | core-api | 구글 id_token 서명 열쇠(JWKS) 캐시 상한 — 응답 `Cache-Control: max-age`만큼 두되 이 값을 넘지 않는다(바닥은 `google-keys-min-age`). 모르는 kid면 상한 안이어도 다시 받는다 | U2 2차 리뷰 4 · U2-J2 |

## 9. U5a 텍스트 AI 시간

| 키 | 기본값 | 소유 앱 | 뜻 | 근거 |
|---|---|---|---|---|
| `mujoong.text-ai.request-timeout` | `3s` | core-api | 호출 한 번의 상한 | U5a-J7 |
| `mujoong.text-ai.retry.max-retries` | `1` | core-api | 일시적 오류의 재시도 횟수 | U5a-J7 |
| `mujoong.text-ai.retry.backoff` | `250ms` | core-api | 기술 재시도 사이 간격 | U5a-J7 |
| `mujoong.timing.practice.scenario-request-timeout` | `15s` | core-api | C10 전체 생성/재생성/재시도 예산과 요청 선점 시한 | U5a-J18 |
| `mujoong.timing.goal.restore-window` | `5s` | core-api | 삭제 요청 처리 뒤 서버 복구 시한 | C4 · U5a-J3 |
| `mujoong.timing.parent.loading-notice-delay` | `5s` | web | 화면 18 최초 조회가 이 시간을 넘으면 안내 토스트 | 화면 18 interaction-spec · U5a-J23 |

core-api 키는 모두 application.yml에 둔다. 텍스트 AI 세 키는 TextAiProperties, 두 `mujoong.timing.*` 키는 CoreProperties(timing.practice · timing.goal)가 묶는다. 상황 생성 예산은 요청 선점 시한과 같고(ScenarioLimits), 복구 시한은 GoalSelectionService가 읽는다. 프런트 삭제는 기존 조작 토스트 설정(최소 3초)을 따른다.

web의 loading-notice-delay는 MUJOONG_TIMING_PARENT_LOADINGNOTICEDELAY 환경 변수로 주입하고 parentLoadingNoticeDelayMs로 읽는다.

## 10. U6 결과 · 리포트 시간

| 키 | 기본값 | 소유 앱 | 뜻 | 근거 |
|---|---|---|---|---|
| `mujoong.timing.result.job-attempts` | `4` | core-api | 최초 1회 + 추가 최대 3회. realtime/web 비소유 키 | FR7.6 · U14 |
| `mujoong.timing.result.job-retry-backoff` | `[5s, 15s, 30s]` | core-api | 실패 뒤 다음 시도 간격. realtime/web 비소유 키 | FR7.6 · U14 |
| `mujoong.timing.result.job-tick` | `1s` | core-api | 실행기 주기 | FD-Q2 |
| `mujoong.timing.result.attempt-timeout` | `30s` | core-api | 세션 결과 시도 시한 | FD-Q2 |
| `mujoong.timing.result.reconcile-interval` | `5m` | core-api | 누락 · 후보 등록 · 리포트 정리 점검 | FD-Q2 |
| `mujoong.timing.result.undecided-limit` | `1h` | core-api | 기록 저장 뒤 UNDECIDED 판정 유예 상한 | R-12 |
| `mujoong.timing.report.ready-target` | `60s` | core-api · web | 리포트 접수부터 절대 마감 | BR9.4 · U6-e |
| `mujoong.timing.report.unsaved-retention` | `24h` | core-api | 저장 안 된 READY · FAILED 보존 | FD-Q2 |
| `mujoong.text-ai.result.request-timeout` | `10s` | core-api | AI 한 번의 최대 시한(시도 잔여 시간 이하) | FD-Q2 |
| `mujoong.text-ai.result.temperature-judgment` | `0` | core-api | 재판정 온도 | ai-io 6.1 |
| `mujoong.text-ai.result.temperature-generation` | `0.2` | core-api | 생성 온도 | ai-io 6.1 |
| `mujoong.text-ai.result.prompt-version` | `v1` | core-api | 프롬프트 리소스 버전 | U6-J |

생성 온도 `mujoong.text-ai.result.temperature-generation=0.2`, 재판정 온도 `temperature-judgment=0`, 프롬프트 버전 `prompt-version=v1`, `mujoong.limits.result-workers=2`는 시간이 아닌 설정이다. 실행 스레드 하나는 REPORT 전용이다. 17 · 27 · 28도 정리/생성 중에만 기존 `parent.poll-interval`을 사용한다(U6-h).
## 11. U7 웹 푸시 시간

| 키 | 기본값 | 소유 앱 | 뜻 | 근거 |
|---|---|---|---|---|
| `mujoong.timing.push.dispatch-poll` | `1s` | core-api | 발송 요청 점검 주기 | AC1.7.5 · U7-J6 |
| `mujoong.timing.push.retry-backoff` | `[5s, 30s, 2m]` | core-api | 최초 발송 뒤 재시도 간격 | AC1.7.5 · U7-J6 |
| `mujoong.timing.push.send-timeout` | `5s` | core-api | 수신처 한 곳의 HTTP 발송 상한 | FR7.10 · U7-J11 |
| `mujoong.timing.push.device-status-check` | `10s` | core-api | 등록 기기의 보고 신선도 점검 주기 | AC1.7.2 · U7-J7 |

## 12. U5b 연습 런타임

2절의 U5 이름 공간은 아래 별도 타입 `PracticeProperties`가 사용한다. 기본 파일은 `backend/realtime-activity/src/main/resources/practice/u5b-runtime.yml`이며 환경 설정이 우선한다. 기존 U1 `RealtimeProperties`/application.yml의 키 집합을 바꾸지 않고 U5b 설정 시험으로 별도 대조한다. 실제 운영 소유는 Realtime의 연습 모듈이다.

| 설정 키 | U5b 기본 | 시작·끝 |
|---|---|---|
| `mujoong.timing.practice.first-response-wait` | `20s` | 원 상황 실제 재생 완료 → 첫 반응 |
| `mujoong.timing.practice.help-consent-wait` | `10s` | 도움 질문 실제 재생 완료 → 동의 |
| `mujoong.timing.practice.cue-budget` | `5s` | 도움 요청 → 실제 재생 시작 |
| `mujoong.timing.practice.after-cue-response-wait` | `20s` | 도움 재생 완료 → 반응 |
| `mujoong.timing.practice.new-scenario-response-wait` | `20s` | 새 상황 재생 완료 → 반응 |
| `mujoong.timing.practice.scenario-budget` | `15s` | C10 준비 시작 → 결과 |
| `mujoong.timing.practice.judge-budget` | `5s` | 판정 한 호출 상한, 최대 두 시도 |
| `mujoong.timing.practice.judge-total-budget` | `12s` | 반응 수락 → 최종 DB 커밋, 종료 뒤에도 원래 시한 유지 |
| `mujoong.timing.practice.judge-finalization-reserve` | `2s` | 전체 예산 중 최종 저장용 확보, 부하 검증 전 가정 |
| `mujoong.timing.practice.judge-retry-delay` | `0s` | 남은 예산 안에서 재시도 예약 |
| `mujoong.timing.practice.judge-budget-version` | `u5b-q6-r2` | 반응마다 고정하는 예산 판 |

반응·도움 시계는 기술 일시정지 동안 멈춘다. 판정의 전체 예산은 일시정지나 재시작으로 연장하지 않는다. 프로세스/DB 장애 뒤 시한을 넘겨 복구된 결과는 `PracticeEvaluationRecoveredAfterDeadline` 오류로 관측하고 12초 성공 건수로 보고하지 않는다.

레거시 `practice.judge-*` 키는 U12가 새 연습 흐름(13절 `conversation.*` · `judgment.*`)으로 바꾼 뒤에도 과거(U5b) 경로용으로 남는다 [U8 편차 (U8-c)].

## 13. U8 새 연습 흐름 · 판정 · 가드레일 · 그래프

U8(계약 · 버전 리소스)이 더한 키다. 새 연습 흐름 키는 12절 U5b의 `mujoong.timing.practice`(`practice/u5b-runtime.yml` 바인딩)와 섞이지 않게 `mujoong.timing.conversation` 아래에 둔다. Realtime 키는 `RealtimeProperties.timing`의 `judgment` · `guardrail` · `conversation` 묶음, Core 키는 `CoreProperties.timing.knowledge`가 읽는다. 화면이 읽지 않는 서버 키라 `frontend/src/shared/timing/timing-defaults.json`에는 넣지 않는다 — 서버 키에서 R-M5의 '세 곳'은 이 표 · 소유 앱 `application.yml` · 설정 레코드다(BR3.1) [U8 편차 (U8-c)]. 결과 재시도 횟수 · 간격은 10절 키가 정본이고 새 키를 만들지 않는다(BR3.2).

| 키 | 기본값 | 소유 앱 | 뜻 | 근거 |
|---|---|---|---|---|
| `mujoong.timing.judgment.live-budget` | `2s` | realtime | 진행 중 판정(J1 ~ J6) Luna 호출 한 번의 상한. 넘기면 재시도 없이 판정 보류(U11) | FR3.4 |
| `mujoong.timing.judgment.response-ready` | `10s` | realtime | 아이 말이 끝난 뒤 이 시간 안에 응답이 준비되지 않으면 보류 되묻기(`D_HOLD`, U11) | FR5.3 |
| `mujoong.timing.guardrail.jev-budget` | `800ms` | realtime | 제브 가드레일 검사 한 번의 상한. 넘기면 막힘(자동 ALLOW 없음, U13) | FR6.2 |
| `mujoong.timing.conversation.no-response-wait` | `10s` | realtime | 새 연습 흐름의 무응답 재안내 대기(U12) | AC1.9.1 |
| `mujoong.timing.conversation.focus-max` | `90s` | realtime | 집중 연습 한 번의 상한(U12) | AC1.5.4 |
| `mujoong.timing.conversation.goal-active-limit` | `10m` | realtime | 목표 하나의 진행 상한(U12) | FR4.5 |
| `mujoong.timing.knowledge.graph-budget` | `2s` | core-api | 지식 그래프 조회 한 번의 상한. 넘기면 빈 응답(U15) | FR9.5 |
