# U3 음성 대화 — 설계 (클라우드 세션, 2026-10-12)

> 지시서 `brief-cloud.md`를 따른다. 정본은 화면 설계 v1.5j(D-19) → 승인 산출물(unit-of-work U3 절 · stories US6.2 ~ US6.6 · components · contract-summary 4.2 · 5 · 6절) → `contracts/` → U1 구현 · 규칙(BR3 · BR4 · BR6 · BR7 · BR9) 순이다.
> 가정은 "준하 제안(가정) U3-J<n>"으로 번호를 붙이고 끝의 목록에 모았다. 엔진(`/aidlc`)은 쓰지 않는다.

## 1. 범위와 경계

U1 워킹 스켈레톤이 만든 세션 경로(PREPARING → DEVICE_ACK → ACTIVE → CLOSING → ENDED, 실패 · 끊김 경로) 위에서 U3 컴포넌트 여섯을 완성한다. 세션 상태 기계 자체(WAITING_CHILD · DECLINED · CHILD_STOP · SILENCE · CONSENT_WITHDRAWN 전이, 08 · 12 · 17 화면)는 U4 몫이라 **새 전이를 더하지 않는다**(U3-J1). DialogPolicy는 components.md대로 "판단 결과만 돌려주고 상태를 바꾸지 않는다".

| 컴포넌트 | U1이 이미 한 것 | U3가 완성하는 것 |
|---|---|---|
| DeviceGateway | WSS 인증 · HELLO_OK 교체(4001) · HEARTBEAT · OFFLINE · DISCONNECTED(40초) · UNRESPONSIVE · 상태 보고 · DeviceReleased | **UNSTABLE 판정과 복귀**(지연 1초 · 5분 창 끊김 2회, 히스테리시스), 끊김 기록, HELLO `resume` → 진행 중 세션 다시 붙이기(HELLO_OK.session), ACK 못 받은 명령 재전송, DEVICE_STATE 최신값 보관, PLAYBACK_MARK PROGRESS 수용, 공급자 끊김 감지 |
| VoiceRelay | 공급자 WSS(J110 선 형식) · 입력 중계 · 지시문 템플릿 | 끼어들기 때 공급자 응답 취소, 폐기 뒤 지시 교정, **동의된 세션의 원음 파일 저장**(설정 키 위치), ACTIVE 뒤 공급자 끊김 → 기술 종료 |
| OutputGate | 구간 분할 · 전사 대응 · 시드 사전 · 세대 · 재확인 · WAITING_FILLER | 끼어들기 · 종료 의도 때 열린 구간 · 늦은 조각 폐기(응답 단위), 시드 사전 범주별 ≥ 1 유지(SCORE `비정상`), 폐기 뒤 지시 교정 호출 |
| ConversationLog | 전사 조립 · 순번 · 승인 표시 · 12 조회 | **불확실 표시**(review_needed), **끊김 구간**(gaps) 기록과 조회 |
| DialogPolicy | 없음(지시문은 ProviderRequestFactory 안의 고정 문장) | 대화 역할 지시문 조립(역할 지식 분리), 종료 의도 판정(종료 표현 스냅숏 + 명확 · 불명확 · 제외 규칙). 참여 판단 추적기(B17)는 리뷰 반영 10으로 이번 PR에서 빼고 U4가 시계 · 전이와 함께 들인다 |
| DeviceWeb T19 | 꺼짐 · 마이크 · 연결 중 · 대기 · 듣는 중 · 생각 중 · 말하는 중 · 마무리, 5초 고정 재접속 | **8상태 표정 · 라벨 전부**(연결 끊김 · 다시 연결 중 포함), 1 · 2 · 4초 백오프, HELLO resume, 끼어들기 로컬 감지(200ms → 0.5초 안 정지 + PLAYBACK_MARK STOPPED), PROGRESS 1초, DEVICE_STATE 송신, opId 중복 실행 방지 |

건드리지 않는 것: core 스키마 · `/parent` · `frontend/src/parent/` · `aidlc/` · `README.md` · `contracts/README.md`.

## 2. 포함 스토리와 수용 기준

- **US6.2 상태 화면 · 켜기 끄기(주)**: AC6.2.4(8상태 라벨 · 표정 · 디버그 로그 일치) · AC6.2.6(연결 끊김 · 다시 연결 중) · AC6.2.7(탭 뒤로 · 연결 유지) · AC6.2.10(재연결 때 진행 중 세션에 다시 붙임 · 두 번째 연결이 대체) · AC6.2.12(끼어들기 0.5초 · 재생 위치 보고) · AC6.2.13(큰 터치 영역 · 표정 1차). AC6.2.1 ~ 6.2.3 · 6.2.5 · 6.2.8 · 6.2.9 · 6.2.11은 U1이 만든 것을 유지한다.
- **US6.4 자유대화 · 종료 의도 · 마무리(주)**: AC6.4.2(전언 사전 · canary 0건 — 지시문 조립 시험) · AC6.4.4 ~ 6.4.6(종료 의도 판정 · 불명확 확인 · 역할 대사 제외 — 판정기 시험, 전이는 U4) · AC6.4.7(전사 · 원음 저장 · 불확실 표시) · AC6.4.8(판정기 20회 세트 오인식 0 · 미인식 ≤ 1, 판정 기준 비전달).
- **지원**: US2.1 AC2.1.4 · 2.1.10 · 2.1.11(불안정 판정 · 복귀 · 세션 무영향), US3.2 AC3.2.10(b)(끊김 구간 기록), US6.3 AC6.3.3 · 6.3.7 · 6.3.8(참여 판단 추적기 — 리뷰 반영 10으로 U4에 넘김), US6.6 AC6.6.1 ~ 6.6.4 · 6.6.9(40초 안 재개 · 이전 출력 재재생 없음 · 공급자 끊김은 종료 · 복구 뒤 기록 보완은 gap).

## 3. 엔터티와 마이그레이션 (realtime `V2__u3_voice_conversation.sql`, 더하기만)

| 표 | 열 | 근거 |
|---|---|---|
| `realtime_device_disconnect_event` (신설) | id · device_id · connection_id · kind(CLOSED · SILENT) · close_code(CLOSED만) · occurred_at · created_at, 색인 (device_id, occurred_at) | 5분 창 끊김 횟수의 원천(AC2.1.4 · 2.1.10). 끊김 = HELLO_OK를 받은 연결이 닫기 프레임 없이 끊김(1006) · 전송 오류로 끊김(CLOSED)과 40초 무음 DISCONNECTED(SILENT). 의도된 닫기(1000 · 1001)와 서버 판단의 닫기(4001 · 4400 · 4401 · 1013 · 1011)는 끊김이 아니다(U3-J2) |
| `realtime_audio_recording` (신설, components AudioRecording) | id · session_id(FK RESTRICT) · speaker(CHILD · AI) · storage_path · format · byte_count · started_at · ended_at · deleted_at · created_at, (session_id, speaker) 유일 | 동의된 세션의 원음 메타(K6). 파일 위치는 `mujoong.recording.directory` |
| `realtime_session_gap` (신설) | id · session_id(FK RESTRICT) · kind(DISCONNECT · UNCONFIRMED) · started_at · ended_at(null = 복구 못 함) · created_at | 12의 "hh:mm ~ hh:mm 잠깐 끊김"(AC3.2.10(b)) · 미확인 구간(AC6.6.4). 계약 LiveView `gaps` 모양 그대로 |
| `realtime_transcript` | 열 추가 없음. `review_needed`를 U3가 쓴다 | 불확실 표시(AC6.4.7). Bubble.uncertain |
| `realtime_device_connection` | 열 추가 없음. `connection_state`에 UNSTABLE 값 사용, `recent_disconnects`를 창 안 횟수로 갱신 | V1 CHECK가 이미 UNSTABLE을 허용 |

`V1`은 손대지 않는다. 앱은 validate만 하므로 기동 스키마 점검(S2)이 최신 버전 2를 받아들이게 한다.

## 4. API · 메시지 (계약 경로 그대로, 더하기만)

- **기기 WSS `/ws/device`**: 메시지 17종 그대로. 바뀌는 처리: ① HELLO `resume{sessionId}`를 읽고 HELLO_OK `session{sessionId, view, resumed}`를 채운다 ② DEVICE_STATE · PLAYBACK_MARK(PROGRESS)를 받아 쓴다(J123의 '무시' 해제) ③ 같은 기기의 새 연결이 HELLO_OK를 받으면 진행 중 세션의 실행기에 연결을 다시 묶고(`LinkReplaced`), ACK를 못 받은 opId 명령을 같은 opId로 다시 보낸다(계약 4.2 규칙) ④ resume 요청이 없는 새 연결(새로고침)에 진행 중 세션을 붙일 때는 START_SESSION을 같은 approvedAudio로 다시 보낸다(U3-J5). 끊기기 전 출력은 잇지 않는다(새 playbackId만). 이 다시 붙이기 규칙은 편차 (ab)로 적는다.
- **Core 내부 HTTP**: `POST /internal/devices/verify-token` · `PUT /internal/devices/{id}/connection` 그대로(state에 UNSTABLE · recentDisconnects 창 횟수). `GET /internal/sessions/{id}/view`는 `gaps`를 채운다(모양은 계약 그대로).
- **이벤트**: DeviceStatusChanged에 UNSTABLE 상태와 recentDisconnects가 실린다(모양 그대로).
- **공급자 선 형식(`contracts/ai-io.md` 3절, 가짜 공급자)**: 서버 → 공급자 `session.response.cancel {responseId?}`(끼어들기 · 종료 의도), `session.instructions.correct {text}`(폐기 뒤 교정, 고정 템플릿 — 미승인 원문 없음); 공급자 → 서버 `session.input_transcript.done`의 선택 필드 `confidence`(0 ~ 1, 불확실 표시 근거). 모두 모르는 type · 필드는 무시되므로 더하기만 안전. 편차 (aa)로 적는다.
- **설정 키(`contracts/timing.md` 2절 예약 → 1절로 옮김)**: `device.unstable-rtt 1s`(realtime), `device.unstable-disconnect-window 5m`(realtime) + 횟수 `mujoong.device.unstable-disconnect-count 2`(4절, U3-J3 — 편차 (ac)), `device.reconnect-backoff [1s, 2s, 4s]`(web, 그 뒤 `device-web.retry-interval` 5초), `device.playback-mark-interval 1s`(web), `gate.barge-in-stop 500ms` · `gate.local-voice-min 200ms`(web). `device.gap-display 10s`는 12 표시(U4)라 예약으로 남긴다. 시간이 아닌 새 키: `mujoong.recording.directory` · `mujoong.recording.max-bytes-per-session` · `mujoong.limits.recording-queue-frames` · `mujoong.transcript.uncertain-below-percent`.

## 5. 업무 규칙 ↔ AC

| 규칙 | 내용 | AC · 근거 |
|---|---|---|
| U3-R1 UNSTABLE | HEARTBEAT의 lastRttMs > `unstable-rtt`(1초)이면 CONNECTED → UNSTABLE. 새 연결의 HELLO_OK 때 창(5분) 안 끊김 ≥ 2이면 CONNECTED 대신 UNSTABLE. 복귀는 HEARTBEAT에서 rttMs ≤ 1초 **그리고** 창 안 끊김 < 2일 때만(히스테리시스). UNSTABLE도 40초 무음이면 DISCONNECTED, 명령 시한이면 UNRESPONSIVE(U1 집합에 이미 포함). 상태가 바뀔 때만 DeviceStatusChanged | AC2.1.4 · 2.1.10 · BR4.4 · BR4.8 |
| U3-R2 세션 무영향 | 원장 상태 변화는 세션 전이를 일으키지 않는다(BR4.7). 세션을 움직이는 것은 40초 시계뿐 | AC2.1.11 |
| U3-R3 다시 붙이기 | HELLO_OK 뒤 그 기기의 비종료 세션 실행기가 있으면 연결을 다시 묶는다. 40초 시계는 새 연결의 메시지로 다시 잰다(B18 · 복원 가능 조건 = 공급자 세션 생존). resume.sessionId가 그 세션이면 `resumed: true`이고 START_SESSION을 다시 보내지 않는다. ABORTED · 종료 뒤의 늦은 HELLO는 세션을 되살리지 않는다(BR6.11) | AC6.2.10 · AC6.6.2 · 6.6.8 · 6.6.9 |
| U3-R4 명령 재전송 | opId 명령 4종은 ACK가 올 때까지 실행기가 보관하고 연결이 바뀌면 같은 opId로 다시 보낸다. 기기는 같은 opId를 두 번 실행하지 않는다 | 계약 4.2 순서 · 중복 |
| U3-R5 끼어들기(서버) | PLAYBACK_MARK STOPPED(LOCAL_VOICE_ACTIVITY)가 묶인 연결에서 오면: 출력 세대 올림(남은 프레임 · 대기 묶음 폐기), 열린 구간 폐기 + 그 응답의 남은 조각 버림, 공급자 `session.response.cancel`, AI 행 FINAL · false, SESSION_STATE LISTENING, SessionEvent `BARGE_IN`(playbackId · playedToMs) | AC6.2.12 · J31 · PD-19 |
| U3-R6 끼어들기(기기) | 재생 중 마이크 프레임의 평균 절대 진폭이 임계값 이상인 상태가 `local-voice-min`(200ms) 이어지면 즉시 재생을 멈추고 대기열을 비운 뒤 PLAYBACK_MARK(STOPPED, LOCAL_VOICE_ACTIVITY, playedRange)를 보낸다. 목표 `barge-in-stop` 0.5초 | AC6.2.12 · TS-N04 |
| U3-R7 지시 교정 | 게이트 폐기가 ACTIVE에서 나면 응답당 한 번 `session.instructions.correct`를 보낸다. GATE_PATTERN은 걸린 범주의 문장, 공급자가 멈춰 미완으로 끝난 GATE_EMPTY와 SEGMENT_OVERSIZE는 '짧은 완결 답' 문장이다. 아이 입력 재개 · 다른 응답 도착으로 확정한 미완 구간은 대화가 이미 넘어가 교정하지 않는다. 본문은 고정 문장이고 전사 · 걸린 패턴을 넣지 않는다 | components OutputGate · SD-17 |
| U3-R8 공급자 끊김 | ACTIVE에서 공급자 연결 끊김(`session.closed` · WSS 닫힘 · 전송 오류) → 기존 전이 ACTIVE → ABORTED(DISCONNECT, end_detail PROVIDER_LOST)로 즉시 종료하고 연결이 살아 있으면 CLOSE_TRANSPORT(DISCONNECT) + STANDBY. 시작 뒤 `session.error`는 연결이 남아 있을 수 있어 끝내지 않는다(WARN 한 줄 · 미완 구간 폐기 → 대기 문구, U1 그대로). CLOSING 중에는 30초 확인 시한이 우선(U1 그대로) | AC6.6.3 · B18 · U3-J13 |
| U3-R9 원음 저장 | consentSnapshot.rawAudio가 true인 세션만 아이 입력 프레임(중계가 받은 순서)과 승인된 AI 출력을 세션별 파일로 쓴다. 쓰기는 전용 스레드 · 유계 대기열이고 가득 차면 버리고 센다(실행기 · 프레임 경로를 막지 않음). 동의가 false면 한 바이트도 디스크에 가지 않는다(BR7.1 유지). 메타는 `realtime_audio_recording` | AC6.4.7 · K6 · OQ5 |
| U3-R10 불확실 표시 | 공급자 확정 전사의 `confidence`(0 ~ 1)가 `uncertain-below-percent`(60)/100 미만이거나 말 끝 뒤 확정 전사가 비어 있으면 review_needed true. 전사를 고치지 않는다 | AC6.4.7 · components ConversationLog |
| U3-R11 끊김 구간 | ACTIVE 세션의 묶인 연결이 끊기면 gap(DISCONNECT, from = 끊김 시각) 시작, 다시 붙으면 to 기록, ABORTED면 to = null. 12 조회 `gaps`에 실린다 | AC3.2.10(b) · AC6.6.4 |
| U3-R12 종료 의도 판정 | 정규화한 확정 아이 전사에서 ① 세션 스냅숏(기본 + 추가 표현) 일치 또는 명확한 종료 표현 → STOP ② 인용 · 역할 대사 표지("~라고 했어/말했어") · 주제 거부("이거 말고") · 기기 재생 중 들어온 전사 → NONE ③ "그만" 단독 · "쉬고 싶어" 등 → CONFIRM(확정하지 않음). 판정은 실행기가 받아 STOP이면 R5와 같이 출력을 취소하고 SessionEvent `CHILD_STOP_INTENT`를 남긴다. CLOSING(CHILD_STOP) 전이와 STOP_CONFIRM 문구 재생은 U4가 같은 판정기로 잇는다 | AC6.4.4 ~ 6.4.6 · 6.4.8 · AC2.3.7 |
| U3-R13 참여 판단 (리뷰 반영 10: U4로 넘김 — 아래는 U4가 들일 규칙) | 추적기: 참여 질문 재생 완료 → 20초 무응답 → 재안내 1회 → 20초 → DECLINED. 명시적 거부 → 대안 1회 → 재거부 → DECLINED. 불명확 발화 = 되묻기(재안내 1회에 포함). 주제 거부는 종료가 아니다. 안내 최대 3회(B17). 시간은 인자로 받는다(키 소유 U4) | AC6.3.3 ~ 6.3.8 |
| U3-R14 역할 지식 분리 | 대화 역할 지시문에는 아이 문맥 세 필드만 들어가고 종료 표현 · 판정 기준 · 보호자 입력 · 이전 판정 문자열이 들어가지 않는다(구조 시험) | AC6.4.2 · 6.4.8 · NFR3 · SD-20 |
| U3-R15 T19 표시 | 라벨 · 표정은 대기 · 듣는 중 · 생각 중 · 말하는 중 · 마무리 · 연결 끊김(+ 꺼짐 · 마이크 허용 안 됨). '생각 중'은 아이 말 끝(speech_stopped · 전사 확정)부터 첫 승인 출력까지 서버가 SESSION_STATE(THINKING)로, 재생이 끝나면 LISTENING으로 보낸다(U3-J6). 한 번 연결됐던 기기의 재접속 주기는 '연결 끊김' + "다시 연결하고 있어요"(mockups 9-31), 처음 연결은 '연결 중'(BR9.4) | AC6.2.4 · 6.2.6 · D-19 |
| U3-R16 재접속 | 끊기면 재생 · 대기열을 버리고 1 · 2 · 4초 뒤, 그 뒤 5초마다 다시 접속한다. HELLO에 마지막 sessionId를 resume으로 싣고 HELLO_OK.session이 있으면 입력 창을 다시 연다(resumed true) 또는 START_SESSION을 기다린다(false). 4001은 꺼짐 · 4401은 D0 그대로 | AC6.2.6 · 6.2.7 · J31 |
| U3-R17 DEVICE_STATE | 탭 보임 · Wake Lock · 마이크 트랙 끝남 · 재생 오류가 바뀔 때 바뀐 필드만 보낸다. 서버는 연결 객체에 최신값을 두고 지표로 센다. mic LOST가 묶인 세션에서 오면 SessionEvent `DEVICE_MIC_LOST` | FR-F18 · AC6.2.7 |

## 6. 화면과 상태 (T19, D-19)

| 상태 | data-state | 라벨 | 들어오는 길 | 나가는 길 |
|---|---|---|---|---|
| 꺼짐 | off | 「켜기」 | 시작 · 「끄기」 · 4001 | 「켜기」 → 마이크 요청 |
| 마이크 허용 안 됨 | mic-denied | 마이크를 허용해 주세요 + 본문 + 「켜기」 | 허용 거부 | 「켜기」 |
| 연결 중 | connecting | (빈 라벨) | 허용 뒤 첫 접속, 한 번도 연결되지 않은 기기의 재접속 | HELLO_OK → 대기 |
| 대기 | standby | 대기 | HELLO_OK · STANDBY · 종료 | START_SESSION/SESSION_STATE |
| 듣는 중 | listening | 듣는 중 | LISTENING | 말 끝 → 생각 중, 재생 → 말하는 중 |
| 생각 중 | thinking | 생각 중 | THINKING | 재생 시작 → 말하는 중 |
| 말하는 중 | speaking | 말하는 중 | 실제 재생 중(기기 판정) | 재생 끝 · 끼어들기 → 서버 LISTENING |
| 마무리 | closing | 마무리 | CLOSING | STANDBY(자동) |
| 연결 끊김 · 다시 연결 중 | reconnecting | 연결 끊김 / 다시 연결하고 있어요 | 한 번 연결된 뒤 1001 · 1006 · 1011 · 1013 · 4400 · 4408 | HELLO_OK → 대기(또는 진행 중 view) |

`?debug=1`의 상태 로그 마지막 줄 = 지금 data-state(AC6.2.4). 클릭 요소는 「켜기」 · 「끄기」뿐. 상태 ↔ 표정은 CSS `data-expression`(색 + 눈 · 입 모양, 자산 전 도형).

## 7. 구현 층과 시험 계획 (태그 `u3`, 커버리지 범위 `backend/coverage-scope/u3.txt` · `frontend/coverage-scope/u3.json` 줄 80% 이상)

| 층 | 만드는 것 | 시험 |
|---|---|---|
| L1 스키마 · 설정 | V2 마이그레이션, 저장소 3개(끊김 · 원음 메타 · gap), 설정 키 · timing.md · application.yml · test yml | `*RepositoryIT`(u3 · db), `RealtimeSchemaIT` 확장, `ConfigKeyContractTest`(u1 유지) |
| L2 DeviceGateway | UNSTABLE 정책 · 끊김 창 · HELLO resume · HELLO_OK.session · LinkReplaced · 명령 재전송 · DEVICE_STATE · PROGRESS · UNSTABLE 지표 라벨 | `DeviceStatusPolicyTest` 확장, `UnstableJudgeTest`, `DeviceGatewayU3IT`(지연 1초 → UNSTABLE → 복귀, 끊김 2회 → UNSTABLE, resume → session, 재전송) |
| L3 VoiceRelay · Gate | 공급자 cancel · correct, 끼어들기 처리, 공급자 끊김 → ABORTED, 원음 기록기(`recording` 패키지), 지시 교정 | `SessionExecutorU3Test`(세대 · 취소 · ABORTED · 재묶기 · 재전송), `RawAudioRecorderTest`, `FakeProviderWsServer` 확장, `SessionLifecycleU3IT`(재접속 이어가기 끝-끝, 끼어들기) |
| L4 ConversationLog · DialogPolicy | review_needed · gap 기록 · view gaps, `dialog` 패키지(지시문 · 종료 의도) | `StopIntentJudgeTest`(합성 20건: 오인식 0 · 미인식 ≤ 1), `DialogInstructionsTest`(canary · 판정 기준 0건), `TranscriptWriterTest` 확장. 참여 추적기와 그 시험은 리뷰 반영 10으로 U4에 넘긴다 |
| L5 DeviceWeb | 상태 기계(백오프 · 끊김 라벨), wsClient(resume · session · opId 중복), deviceSession(resume · PROGRESS · 끼어들기), player(playedRange), `BargeInDetector`, runtime(DEVICE_STATE · 마지막 세션), T19View(연결 끊김 · 부제), timing 키 · 문구 | `frontend/tests/u3/**`(상태 8종 주입 · 디버그 로그 일치 · 백오프 1 · 2 · 4 · 5 · resume · 끼어들기 200ms/0.5초 · PROGRESS 1초 · DEVICE_STATE) + U1 시험 갱신(J360 라벨 → 화면 설계) |
| L6 도구 · 문서 | fake-provider(`session.response.cancel` · `session.instructions.correct` 처리와 기록, `--confidence` 옵션 — L5에서 함께), deviations (aa) ~ (ac)와 계약 문서 갱신, realtime 시험 프로필 `internal-http.connect-timeout` 50ms → 500ms(3.6 재시험 흔들림 원인), report.md. device-simulator(tools)의 resume · PROGRESS 옵션은 남은 일(백엔드 IT의 `DeviceSimulatorClient`가 그 경로를 덮는다) | `tools/tests/u3`, `ContractFilesTest`(두 글자 편차 기호) |

실행: U1 회귀(`-PincludeTags=u1`, `tests/u1`, tools), U3 전체(`-PincludeTags=u3 -PcoverageScope=u3`, `MUJOONG_COVERAGE_SCOPE=u3 … tests/u3 --coverage`), eslint · tsc, 끝에 깨끗한 트리에서 `bash scripts/verify.sh`(compose) 한 번.

## 8. 가정 목록 — 준하 제안(가정)

| 번호 | 가정 | 근거 · 영향 |
|---|---|---|
| U3-J1 | 새 세션 전이(CHILD_STOP · WAITING_CHILD · DECLINED · SILENCE)는 만들지 않는다. DialogPolicy는 판정 · 추적기만 내고 실행기는 STOP 판정에 출력 취소 · 공급자 취소 · 기록만 한다. CLOSING(CHILD_STOP) · 승인 문구(STOP_CONFIRM · RE_PROMPT 등)는 U4 | 지시서 '세션 상태 기계는 U4', components DialogPolicy '상태를 바꾸지 않는다'. AC6.4.4의 마무리 인사 재생은 U4에서 완성 |
| U3-J2 | '끊김 1회' = HELLO_OK를 받은 연결이 닫기 프레임 없이 끊김(1006)이나 전송 오류(TCP 끊김 · EOF — 서버가 그 뒤 1011로 닫아도)로 끊긴 사건(kind CLOSED), 또는 40초 무음으로 DISCONNECTED가 된 사건(kind SILENT). 의도된 닫기(「끄기」 1000 · 페이지 이탈 · 서버 재시작 1001)와 서버 판단의 닫기(4001 대체 · 4400 규약 위반 · 4401 해제 · 4408 · 1013 준비 안 됨 · 대기열 넘침 1011)는 네트워크 불안정이 아니라 세지 않는다. L2에서 좁혔다(초안은 1001 · 4400 · 4408도 셌다) | AC2.1.4의 '끊김'은 의도치 않은 네트워크 단절. 아이가 껐다 켜는 것 · 서버 쪽 사유를 불안정으로 보지 않는다 |
| U3-J3 | 예약 키 `device.unstable-disconnects`("5분 안 2회")는 Duration 하나로 담을 수 없어 `timing.device.unstable-disconnect-window 5m`과 `device.unstable-disconnect-count 2` 두 키로 나눈다 | 계약 7절 값은 그대로. timing.md에 두 행으로 적고 예약 행을 지운다 |
| U3-J4 | 다시 붙이기는 HELLO_OK 뒤 그 기기의 비종료 세션 실행기가 있을 때 하고, 세션은 보호자 · 기기 유일 색인으로 하나뿐이다 | BR6.3 · BR3.7. 끊김 40초 안이라는 조건은 실행기가 살아 있음(ABORTED 전)으로 보장 |
| U3-J5 | resume 요청이 없는 새 연결(새로고침 · 다른 창)에 진행 중 세션을 붙일 때 START_SESSION을 같은 approvedAudio로 다시 보내 승인 음성을 받게 하고 DEVICE_READY로 입력 창을 열게 한다. ACTIVE에서 늦은 DEVICE_READY는 무시(U1 그대로) | 기기가 승인 음성 캐시를 잃었을 수 있다. HELLO_OK.session.resumed = false |
| U3-J6 | '생각 중'은 공급자 speech_stopped 또는 입력 전사 확정부터 첫 OUTPUT_BEGIN(또는 WAITING_FILLER)까지 서버가 SESSION_STATE(THINKING)로 보낸다. 재생이 끝나면(COMPLETED · STOPPED · FAILED) LISTENING | AC6.2.4의 대기 → 듣는 중 → 생각 중 → 말하는 중. U1은 THINKING을 보내지 않았다 |
| U3-J7 | ACK 미수신 opId 명령은 실행기 메모리에 보관(최대 16건)하고 연결 교체 때 같은 opId로 재전송한다. 종료 · 세대 변화로 뜻이 사라진 STOP_OUTPUT은 재전송하지 않는다 | 계약 4.2 '같은 opId로 새 연결에 다시 보낸다' |
| U3-J8 | 기기는 세션별로 실행한 opId를 기억해(최근 64건) 같은 opId를 두 번 실행하지 않고 ACK만 다시 보낸다 | 계약 4.2 |
| U3-J9 | DEVICE_STATE는 영속하지 않고 연결 객체 최신값 + 지표(`mujoong_device_state_total{field,value}`)로 둔다. 묶인 세션의 mic LOST만 SessionEvent로 남긴다 | performance-design 표 'DEVICE_STATE: 메모리 최신값(U3가 판정에 사용)' |
| U3-J10 | PROGRESS의 playedRange는 playbackId별 메모리 최신값으로 두고 STOPPED 때 SessionEvent `BARGE_IN` payload(playbackId · playedToMs)에 남긴다. 로그 attrs 허용 표는 바꾸지 않는다 | 사건 코드 17종 · 허용 키를 바꾸면 U1 형식 시험이 넓어진다. DB 기록으로 충분 |
| U3-J11 | 끼어들기의 공급자 쪽 취소는 `session.response.cancel`(새 type)이고 가짜 공급자는 받으면 그 응답의 남은 조각을 보내지 않는다. 실공급자 어댑터는 11/2에 맞춘다(V-2) | ai-io 3절 J110 가정과 같은 성격 |
| U3-J12 | 폐기 뒤 지시 교정 문장은 범주별 고정 템플릿(진단 · 치료 · 점수 · 원인 · 일반화 · 감정 단정 · 재촉 금지 재확인)이고 응답당 1회 | SD-17 '미승인 원문을 지시에 복사하지 않는다' |
| U3-J13 | ACTIVE 중 공급자 끊김은 기존 전이 ACTIVE → ABORTED(DISCONNECT)를 즉시 쓰고 end_detail에 PROVIDER_LOST를 적는다. 새 상태 · 사유 값은 없다 | AC6.6.3 · B18 '음성 모델 연결이 끊기면 기술 종료'. 열린 질문(reliability-design)의 U3 몫 |
| U3-J14 | 원음 파일은 `mujoong.recording.directory`(기본 `/tmp/mujoong-recordings` — 컨테이너 rootfs가 읽기 전용이라 tmpfs `/tmp` 아래, 시험은 임시 디렉터리) 아래 `<sessionId>/child.pcm` · `ai.pcm`(PCM16LE 24kHz 모노 원시)이다. 운영 위치 · 백업은 3.4(OQ5) 몫 | 지시서 '기본값은 개발용 로컬 디렉터리'. 컨테이너 rootfs는 읽기 전용 |
| U3-J15 | 원음 기록기는 세션당 상한 `recording.max-bytes-per-session`(기본 64MB ≈ 22분)을 넘기면 그 세션 기록을 멈추고 ended_at을 쓴다. 대기열 상한을 넘으면 프레임을 버리고 센다 | 디스크 · 메모리 보호. 실행기를 막지 않음(PD-1) |
| U3-J16 | 삭제 참가자 `RealtimeVoiceDeletionParticipant`(전사 deleted_at · 원음 파일 + 메타 · gap · 끊김 기록 · 연결 행)를 만들어 두고 호출 시점은 U4 삭제 지휘가 정한다 | unit-of-work U3 메모 · S-9 |
| U3-J17 | 불확실 기준 `mujoong.transcript.uncertain-below-percent` 60(공급자 confidence 0 ~ 1을 100배한 값과 비교하는 정수 키 — Duration이 아니라 timing.md에 넣지 않는다). 가짜 공급자는 옵션으로 confidence를 낸다. 값이 없으면 불확실로 보지 않는다 | 공급자 형식 미확정(C14 열린 질문) |
| U3-J18 | gap은 세션 실행기가 쓴다(연결 끊김 · 재묶기 · ABORTED). UNCONFIRMED gap(ENDED_UNCONFIRMED)은 자리만 두고 U4가 쓴다 | AC6.6.4 · AC3.4.3 |
| U3-J19 | 재접속 주기의 라벨은 U1(J360 '마지막 view 라벨')에서 화면 설계의 '연결 끊김'으로 바꾼다. 이에 걸리는 U1 프런트 시험 기대값은 화면 설계대로 고친다(단언 수는 줄이지 않는다) | 기준 1(화면 설계 v1.5j) > U1 결정 |
| U3-J20 | 기기 끼어들기 임계값(PCM16 평균 절대 진폭)은 서버 음성 활동 판정과 같은 값(500)을 코드 상수로 둔다. 에코 제거가 켜진 마이크 전제 | InputRelay.VOICE_ENERGY_THRESHOLD(J124) · 계약 열린 질문(에코 POC는 실기에서) |
| U3-J21 | 편차 기호가 (z)까지 찼으므로 (aa) · (ab) … 두 글자를 쓰고 `ContractFilesTest`의 정규식을 넓힌다 | deviations.md 규칙 유지 |
| U3-J22 | CI · verify.sh의 프런트 시험 명령을 `tests/u1`에서 `tests`(전 유닛)로 넓힌다(커버리지 80% 하한은 그대로). tools도 같다. 백엔드 `check`는 이미 전 태그를 돈다 — L5에서 적용(`.github/workflows/ci.yml` · `scripts/verify/a-checks.sh`) | 유닛 시험이 CI에서 돌지 않으면 회귀를 못 잡는다 |
| U3-J23 | 세션 실행기의 view(STANDBY · LISTENING · THINKING · CLOSING)를 실행기 상태로 두고 HELLO_OK.session.view에 쓴다 | 계약 HELLO_OK.session.view |
| U3-J24 | ACTIVE의 확정 아이 전사가 STOP으로 판정되면 실행기는 만들던 · 재생 중이던 응답을 공급자에서 취소하고 그 뒤 새 응답도 첫 조각에서 취소해 기기로 보내지 않는다(세션은 ACTIVE 그대로, `CHILD_STOP_INTENT {basis}` 기록). CONFIRM은 `CHILD_STOP_UNCLEAR`만 남긴다. 기기 재생 중 확정된 전사는 판정하지 않는다. 스냅숏 표현 · 명사꼴 종료 표현은 낱말 경계로 맞추고 물음꼴이면 CONFIRM | AC6.4.4 '대기 응답을 취소하고 … 멈춘다' · AC6.4.8 '종료 확정 뒤 이전 응답이 이어서 재생되지 않는다'. CLOSING(CHILD_STOP) · 마무리 인사 · 확인 질문은 U4(U3-J1) |
| U3-J25 | 원음 파일은 이 인스턴스의 로컬 디렉터리(`mujoong.recording.directory`)에 쓴다. 여러 인스턴스로 늘리면 공유 볼륨이나 객체 저장소로 옮겨야 삭제 참가자가 모든 파일을 찾는다. U1 단일 인스턴스 표(SI-1 ~ SI-12)는 `aidlc/` 원본이라 고치지 않고 보고서에 남긴다 | scalability-design 2.2 · 지시서 '`aidlc/` 수정 금지' |
