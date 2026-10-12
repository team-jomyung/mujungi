# U3 음성 대화 — 결과 보고 (클라우드 세션, 2026-10-12)

> 지시서 `brief-cloud.md`, 설계 `design.md`. 엔진(`/aidlc`)은 쓰지 않았고 워크플로 상태는 park 그대로다.
> PR: [baikjunha/mujoong-aidlc#2](https://github.com/baikjunha/mujoong-aidlc/pull/2) (main으로 가는 초안)

## 1. 결과

- 여섯 컴포넌트(DeviceGateway · VoiceRelay · OutputGate · ConversationLog · DialogPolicy · DeviceWeb T19)를 층마다 구현하고 시험했다.
- 깨끗한 트리(12c52bf)에서 `bash scripts/verify.sh`(compose) 전체가 통과했다. RESULT PASS, 425초이고 끝-끝 한 턴 C1 ~ C12를 포함한다.
- U1 회귀 시험은 모두 통과했다. U1 시험의 커버리지 하한과 단언은 낮추지 않았다(7절).
- 3.6 재시험에서 흔들린 시험 3건 중 U3 경로와 겹치는 2건은 원인을 찾아 고쳤다. Core 안에서 끝나는 1건은 추정 원인과 고칠 방법을 남겼다(4절).
- realtime 스키마 V2만 더했다. core 스키마, `/parent`, `frontend/src/parent/`, `aidlc/`, `README.md`, `contracts/README.md`는 고치지 않았다.

| 커밋 | 내용 |
|---|---|
| 9b2cec1 | docs(u3): 설계 초안 |
| 47db8f2 | L1 realtime V2 스키마 · 저장소 3개 · 설정 키 |
| 079411e | L2 기기 게이트웨이: 불안정 판정 · 끊김 기록 · 이어가기 · 명령 재전송 |
| a858913 | L3 음성 중계 · 게이트 · 대화 기록: 끼어들기 취소 · 지시 교정 · 공급자 끊김 · 원음 기록 |
| 867f0a0 | L4 대화 정책: 종료 의도 판정 · 참여 추적기 · 음성 기록 삭제 참가자 |
| 04c2a2b | L5 기기 웹 T19: 재접속 1 · 2 · 4초 · 이어가기 · 끼어들기 · 재생 진행 · 상태 보고 |
| 12c52bf | L6 계약 편차 (aa) ~ (ac) · 문서 갱신 · 시험 프로필 연결 시한 수정 |
| bb5f801 | fix: 음성을 보내는 U1 시험 두 개가 LISTENING을 기다린 뒤 보내게 |

시작할 때와 중간에 main을 받았다(마지막 병합 8514b24, main b9040da).

## 2. 만든 것

| 컴포넌트 | 만든 것 | 주요 위치 |
|---|---|---|
| 스키마 · 설정(L1) | realtime `V2__u3_voice_conversation.sql`: 끊김 사건 · 원음 메타 · 끊김 구간 표 3개. 예약 시간 키 5개를 timing.md 1절로 옮기고 불안정 키를 창 · 횟수로 나눔. 시간이 아닌 키 `mujoong.recording.*` · `mujoong.limits.recording-queue-frames` · `mujoong.transcript.uncertain-below-percent` | `backend/realtime-activity/src/main/resources/` · `contracts/timing.md` |
| DeviceGateway(L2) | 지연 1초 · 5분 창 끊김 2회 불안정 판정과 히스테리시스 복귀, 끊김 사건 기록(CLOSED · SILENT), HELLO resume로 진행 중 세션에 다시 붙이기와 HELLO_OK.session, ACK 못 받은 명령을 같은 opId로 재전송, DEVICE_STATE 최신값과 지표, PLAYBACK_MARK PROGRESS 수용 | `realtime/gateway/`(DeviceStatusPolicy · ConnectionStateWriter · DeviceDisconnectRepository · ConnectionHandover · HeartbeatHandler · WsFrameGuard) |
| VoiceRelay(L3) | 공급자 `session.response.cancel` · `session.instructions.correct`, 확정 전사 `confidence`, ACTIVE 중 공급자 끊김은 ABORTED(DISCONNECT, PROVIDER_LOST), 동의된 세션의 원음 파일(전용 스레드 · 유계 대기열 · 세션당 64MB · 디렉터리 700 · 파일 600) | `realtime/voice/` · `realtime/recording/` · `session/SessionExecutor.java` |
| OutputGate(L3) | 끼어들기 · 종료 의도 때 열린 구간과 그 응답의 늦은 조각 폐기, 폐기 뒤 응답당 한 번 지시 교정(범주별 고정 문장), 대화가 넘어간 미완 구간은 교정하지 않음 | `realtime/gate/OutputGate.java` · `SessionExecutor` |
| ConversationLog(L3 · L4) | 불확실 표시(review_needed), 끊김 구간 기록과 12 조회의 `gaps`, 계정 삭제 참가자(전사 지움 · 원음 파일과 메타 · 끊김 구간 · 끊김 사건 · 연결 행) | `realtime/log/` |
| DialogPolicy(L4) | 역할 지식을 분리한 대화 지시문, 종료 의도 판정기(STOP · CONFIRM · NONE, 20건 세트 오인식 0 · 미인식 0), 참여 판단 추적기(20초 · 재안내 · 대안 1회 · 최대 3회 — 리뷰 반영 10으로 뺐다, 11절). 실행기는 STOP이면 출력을 취소하고 `CHILD_STOP_INTENT`를, CONFIRM이면 `CHILD_STOP_UNCLEAR`만 남긴다 | `realtime/dialog/` |
| DeviceWeb T19(L5) | 재접속 1 · 2 · 4초 뒤 5초, '연결 끊김'과 부제 '다시 연결하고 있어요', HELLO resume와 이어가기(끊긴 재생은 STOPPED DISCONNECTED), opId 중복 실행 방지, 재생 중 아이 음성 200ms면 즉시 멈춤(LOCAL_VOICE_ACTIVITY), 1초 PROGRESS와 STOPPED playedRange, 정상값과 다를 때만 DEVICE_STATE, Wake Lock 해제 감지 | `frontend/src/device/` · `frontend/src/shared/` |
| 도구 · CI(L5 · L6) | 가짜 공급자의 취소 · 교정 처리와 기록, `--confidence`, CI와 verify.sh가 프런트 · tools 시험 폴더 전부를 돌림 | `tools/fake-provider/` · `.github/workflows/ci.yml` · `scripts/verify/a-checks.sh` |

## 3. 시험 결과

| 항목 | 명령 | 결과 |
|---|---|---|
| 검증 명령 전체 | `bash scripts/verify.sh`(compose, 12c52bf, 깨끗한 트리) | RESULT PASS rc=0, 425초. A1 ~ A4 · F · B · C1 ~ C12 · P1 ~ P3 · T 모두 PASS |
| 백엔드 전체 | verify A1 `./gradlew clean check` | 1,610건 실패 0 · 건너뜀 0(contract-model 279 · core-api 607 · realtime-activity 697 · test-support 27), 모듈 JaCoCo 80% 통과 |
| 백엔드 U1 회귀 | `-PincludeTags=u1`, 네 모듈 | 1,455건 실패 0(그중 realtime-activity 542) |
| 백엔드 U3 | `-PincludeTags=u3 :realtime-activity:jacocoTestCoverageVerification -PcoverageScope=u3` | 17개 클래스 155건 통과, U3 범위 줄 커버리지 80.54% |
| 프런트 전체 | verify A2 `vitest run tests --coverage` · eslint · next build | 42개 파일 562건 통과, 줄 98.93%, eslint 0, tsc 0 |
| 프런트 U3 | `MUJOONG_COVERAGE_SCOPE=u3 npx --no-install vitest run tests/u3 --coverage` | 8개 파일 48건 통과, U3 범위 줄 커버리지 84.46% |
| 프런트 U1 | `vitest run tests/u1 --coverage` | 통과, U1 범위 줄 97.47% |
| tools | verify A3 `vitest run tests` | 9개 파일 111건 통과(그중 U3 5건) |
| 흔들림 수정 뒤 | `--tests '*RealtimeLogHygieneIT' --tests '*ProviderRequestWhitelistIT'` | 11건 통과. bb5f801은 verify 뒤 커밋이라 이 두 클래스와 PR CI로 확인한다 |

U3 범위에서 줄 커버리지가 낮은 파일은 `SessionInternalController`(29.6%)와 `WsFrameGuard`(54%)다. 둘 다 U3가 조금 고친 U1 파일이라 범위에 넣었고, 나머지 줄은 U1 시험이 덮는다.

## 4. 3.6 재시험에서 흔들린 시험

| 시험 | 원인 | 근거 | 조치 |
|---|---|---|---|
| `SessionLifecycleIT.aFullTurnThenGuardianStop…`(VERIFY_TOKEN CONN_ERROR, 기기 연결 1013) | realtime 시험 프로필의 `mujoong.internal-http.connect-timeout` 50ms. CPU가 97 ~ 100%면 루프백 TCP 연결도 50ms를 넘겨 연결 시한 예외가 나고, CONN_ERROR를 거쳐 기기 연결이 1013으로 닫힌다 | 기록된 실패 줄이 `CONN_ERROR 54 ~ 72ms attempt 1`이다. 50ms 연결 시한이 터진 뒤 스케줄 지연이 붙은 값과 맞는다. 시험 환경은 verify-token 응답 시한만 1초로 늘렸고 연결 시한은 50ms 그대로였다 | `application-test.yml`의 연결 시한을 운영 기본값과 같은 500ms로 바꿨다. 응답 시한은 그대로다. U3의 기기 연결 경로다 |
| `RealtimeLogHygieneIT.aWholeSessionLeavesNoSecrets…`('입력 음성 중계 20' 미도달) | 경주. 실행기는 DB에 ACTIVE를 쓴 다음 `enterActive()`에서 입력 중계를 연다. 기기 웹소켓 처리기는 프레임을 중계기에 바로 넣는다. 시험은 DB의 ACTIVE만 보고 음성을 보내므로 그 사이에 온 프레임이 NOT_ACCEPTING으로 버려져 20건에 닿지 못한다 | `SessionExecutor.tryActivate()`의 순서와 `InputRelay.offer()`의 수락 검사. U1이 `RealtimeMetricsScrapeIT`에서 같은 경주를 LISTENING 대기로 고친 적이 있다 | 입력 수락을 연 뒤에 나가는 LISTENING을 기다린 뒤 보내게 고쳤다. 같은 모양인 `ProviderRequestWhitelistIT`도 고쳤다. 제품 동작은 그대로다(R3-8). U3의 음성 중계 경로다 |
| `DeviceRegisterApiIT.aConnectedDeviceBlocksReRegistration…`(DeviceReleased 미전달) | 추정. Core 시험 프로필에도 같은 연결 시한 50ms가 있다. 포화가 이어지면 Outbox 전송이 매번 연결 시한으로 실패하고, 시험 재시도 표(10ms ~ 300ms)와 최대 20회로 약 6초 만에 FAILED가 된다. 시험은 10초를 기다리므로 사건이 오지 않는다 | 실패 로그가 남아 있지 않아 재현으로 확인하지는 못했다. 이 시험은 Core와 Realtime 대역만 지나므로 U3 코드를 거치지 않는다 | Core는 U2 · U5a 영역이라 고치지 않았다. 제안: `backend/core-api/src/test/resources/application-test.yml`의 `mujoong.internal-http.connect-timeout`을 500ms로 |

## 5. 계약 편차(`contracts/deviations.md`)

모두 더하기만 안전하다. `ContractFilesTest`는 두 글자 기호를 받게 넓혔다. (a) ~ (z)와 (aa) ~ (ac) 각각이 표에 한 행, 표시 위치 파일에 한 번 이상 있음을 단언한다(U3-J21).

| 기호 | 내용 | 표시 위치 |
|---|---|---|
| (aa) | 공급자 선 형식: 서버 → 공급자 `session.response.cancel {responseId?}` · `session.instructions.correct {text}`, 공급자 → 서버 확정 전사의 선택 필드 `confidence`(0 ~ 1) | ai-io.md |
| (ab) | 다시 붙이기 구체화: HELLO_OK 뒤 진행 중 세션에 새 연결을 묶고 HELLO_OK.session을 채운다. resume이 그 세션이면 resumed true, 아니면 같은 approvedAudio로 START_SESSION을 다시 보낸다. ACK 못 받은 opId 명령은 재전송한다 | device-ws.asyncapi.yaml |
| (ac) | 예약 키 "5분 안 2회"를 `timing.device.unstable-disconnect-window`와 `device.unstable-disconnect-count`로 나눔 | timing.md |

편차 기호 없이 설명만 고친 곳도 있다. device-ws.asyncapi.yaml과 ws-messages.schema.json의 DEVICE_STATE · PLAYBACK_MARK(PROGRESS) 설명이다. 메시지 모양은 승인 계약 그대로이고, 'U1은 받아 무시'(J123)가 'U3부터 사용'으로 바뀌었을 뿐이다.

## 6. 지시서 · 설계와 다르게 판단한 것

1. **끊김 횟수 범위를 좁혔다(U3-J2).** 초안은 1001 · 4400 · 4408도 끊김으로 셌다. 구현은 닫기 프레임 없는 끊김(1006)과 전송 오류, 40초 무음만 센다. 의도된 닫기와 서버 판단의 닫기까지 세면 서버 재배포 한 번에 모든 기기가 불안정으로 보이기 때문이다.
2. **T19 재접속 라벨을 화면 설계대로 바꿨다(U3-J19).** U1은 재접속 중에 마지막 view 라벨을 보였다(J360). 화면 설계 C.D-19.31.7은 '연결 끊김'이라 이를 따랐다. 부제 '다시 연결하고 있어요'는 목업 9-31의 제안 문구다. 걸린 U1 프런트 시험 기대값을 바꿨다.
3. **CI와 verify.sh가 유닛 시험 폴더 전부를 돌리게 넓혔다(U3-J22).** `tests/u1`만 돌면 U2 · U3 시험이 CI에서 빠진다. 커버리지 하한은 그대로다.
4. **불확실 기준을 정수 퍼센트 키로 뒀다(U3-J17).** `mujoong.transcript.uncertain-below-percent` 60이다. 다른 비시간 키와 같은 정수 모양이고, 공급자 confidence 0 ~ 1을 100배해 비교한다.
5. **종료 의도 STOP이면 세션은 ACTIVE로 두고 출력만 막았다(U3-J24).** CLOSING(CHILD_STOP) 전이는 세션 상태 기계(U4) 몫이라 새 전이를 만들지 않았다. 대신 AC6.4.4 · 6.4.8의 '대기 응답 취소'와 '이전 응답이 이어 재생되지 않음'은 U3에서 지킨다.
6. **시험 프로필 연결 시한과 U1 시험 두 개를 고쳤다(4절).** 단언은 줄이지 않았다.

## 7. U1 시험과 공용 파일에서 바꾼 것

U1 동작이 U3에서 뜻대로 바뀐 곳만 기대값을 바꿨고 단언 수는 줄이지 않았다.

| 파일 | 바꾼 것 | 이유 |
|---|---|---|
| core-api `FlywayConfIT` · `SchemaPrivilegeIT` | realtime 마이그레이션 수 1 → 2, 이력 행 2 → 3, 최신 버전 "1" → "2", 표 수 19 → 22 | realtime V2. core 스키마는 그대로다 |
| `RealtimeSchemaIT` | V2 표 3개의 열 · 색인 · 제약 단언 추가 | L1 |
| `RealtimeArchTest` · `ProviderRequestWhitelistIT` | 공급자 요청 공장 목록과 허용 키에 응답 취소 · 지시 교정 추가, LISTENING 대기 | 편차 (aa) · 4절 |
| `WsFrameGuardTest` | DEVICE_STATE · PROGRESS를 '무시'에서 '처리 대상'으로, 두 번째 HELLO 무시 단언 추가 | U3-J9 · J10 |
| `RealtimeMetricsTest` · `RealtimeMetricsScrapeIT` · `MetricSnapshotJobTest` | 'UNSTABLE 라벨 없음'을 'UNSTABLE 라벨이 원천 값을 따름'으로 | U3-R1 |
| `SessionExecutorFaultTest` | 공급자 끊김 뒤 ACTIVE 유지를 ABORTED(DISCONNECT, PROVIDER_LOST)로 | U3-R8 · J13 |
| `DeviceGatewayIT` | 40초 무음 반복의 둘째 라운드부터 복귀 상태가 UNSTABLE | U3-R1 |
| `RealtimeLogHygieneIT` | LISTENING 대기 | 4절 |
| `ConnectionStateWriterIT` · `DbRetryTest` · `RealtimeDataAccessWiringIT` · `SessionTimersTest` | 생성자 인자 추가만(단언 변화 없음) | 새 설정 값 |
| `ContractFilesTest` | 두 글자 편차 기호 | U3-J21 |
| 프런트 `tests/u1` 네 파일 | 새 시간 키 · 문구 단언 추가, 재접속 지연 1 · 2초와 '연결 끊김' 라벨 | U3-R16 · J19 |
| `.github/workflows/ci.yml` · `scripts/verify.sh` · `scripts/verify/a-checks.sh` | 프런트 · tools 시험 경로 `tests/u1` → `tests` | U3-J22 |
| `frontend/src/shared/`(timing-defaults.json · timing.ts · messages.ts · ko.ts) | 시간 키 4개, HELLO resume · HELLO_OK.session · DEVICE_STATE 도우미, 문구 2개 | 기기 웹 |
| realtime 시험 지원(`testing/`) | 가짜 공급자 · 전이 대역 · 시험 환경에 U3 기능 지원 | U3 시험 |

## 8. 가정 목록(근거는 design.md 8절)

| 번호 | 가정 |
|---|---|
| U3-J1 | 새 세션 전이는 만들지 않는다. DialogPolicy는 판정 · 추적기만 낸다. CLOSING(CHILD_STOP)과 승인 문구는 U4 |
| U3-J2 | 끊김 1회는 1006 · 전송 오류로 끊긴 사건 또는 40초 무음. 의도된 닫기와 서버 판단 닫기는 세지 않는다 |
| U3-J3 | 예약 키 "5분 안 2회"를 창 · 횟수 두 키로 나눈다(편차 (ac)) |
| U3-J4 | 다시 붙이기는 그 기기의 비종료 세션 실행기가 있을 때 한다. 세션은 보호자 · 기기당 하나다 |
| U3-J5 | resume 없는 새 연결에는 START_SESSION을 같은 approvedAudio로 다시 보낸다(resumed false) |
| U3-J6 | '생각 중'은 아이 말 끝부터 첫 출력까지 서버가 THINKING으로 보낸다 |
| U3-J7 | ACK 못 받은 opId 명령은 실행기 메모리(최대 16건)에 두고 연결이 바뀌면 재전송한다 |
| U3-J8 | 기기는 세션별 최근 64건 opId를 기억해 두 번 실행하지 않는다 |
| U3-J9 | DEVICE_STATE는 영속하지 않고 연결 최신값과 지표로 둔다. 묶인 세션의 mic LOST만 사건으로 남긴다 |
| U3-J10 | PROGRESS의 playedRange는 메모리 최신값으로 두고 STOPPED 때 `BARGE_IN` 사건에 남긴다 |
| U3-J11 | 끼어들기의 공급자 취소는 새 type `session.response.cancel`이다. 실공급자 어댑터는 11/2에 맞춘다 |
| U3-J12 | 지시 교정은 범주별 고정 템플릿이고 응답당 1회다 |
| U3-J13 | ACTIVE 중 공급자 끊김은 ACTIVE → ABORTED(DISCONNECT)이고 end_detail은 PROVIDER_LOST다 |
| U3-J14 | 원음 파일은 `mujoong.recording.directory`(기본 `/tmp/mujoong-recordings`) 아래 `<sessionId>/child.pcm` · `ai.pcm`이다 |
| U3-J15 | 세션당 64MB를 넘기면 그 세션 기록을 멈추고, 대기열이 차면 프레임을 버리고 센다 |
| U3-J16 | 삭제 참가자는 만들어 두고 호출은 U4가 한다(DeletionRequested 처리 뒤 DeletionCompleted 발행) |
| U3-J17 | 불확실 기준은 60(confidence × 100)이다. 값이 없으면 불확실로 보지 않는다 |
| U3-J18 | 끊김 구간은 실행기가 쓴다. UNCONFIRMED 구간은 자리만 두고 U4가 쓴다 |
| U3-J19 | 재접속 라벨을 화면 설계의 '연결 끊김'으로 바꾼다(U1 J360 대체) |
| U3-J20 | 기기 끼어들기 임계값은 서버 음성 판정과 같은 평균 절대 진폭 500이다. 에코 제거 마이크를 전제한다 |
| U3-J21 | 편차 기호는 (aa)부터 두 글자를 쓴다 |
| U3-J22 | CI · verify.sh의 프런트 · tools 시험을 전 유닛 폴더로 넓힌다 |
| U3-J23 | 실행기 view(STANDBY · LISTENING · THINKING · CLOSING)를 HELLO_OK.session.view에 쓴다 |
| U3-J24 | 종료 의도 STOP이면 만들던 · 재생 중 응답을 취소하고 새 응답도 첫 조각에서 취소한다. 세션은 ACTIVE 그대로다 |
| U3-J25 | 원음 파일은 인스턴스 로컬 디렉터리에 쓴다. 여러 인스턴스면 공유 저장소로 옮겨야 한다 |

## 9. 남은 일과 막힌 것

막혀서 못 한 일은 없다. 아래는 다른 유닛 몫이거나 실기 · 운영에서만 확인할 수 있는 일이다.

- **U4**: 종료 의도 확정 전이 CLOSING(CHILD_STOP)과 마무리 인사 · 확인 질문(STOP_CONFIRM · RE_PROMPT), 참여 판단 추적기를 시계와 함께 들여 실행기에 연결(리뷰 반영 10으로 이번 PR에서 뺐다 — 커밋 867f0a0의 `dialog/JoinTracker`를 되살려 쓸 수 있다), WAITING_CHILD · DECLINED · SILENCE 전이, UNCONFIRMED 끊김 구간, 12 화면의 끊김 구간 표시(`device.gap-display` 10초).
- **삭제 연결(U4)**: Realtime이 `DeletionRequested`를 받아 `RealtimeVoiceDeletionParticipant.deleteByGuardian`을 돌리고 `DeletionCompleted`를 내는 처리기.
- **실공급자 어댑터(11/2, V-2)**: 응답 취소 · 지시 교정 · confidence를 실제 공급자 형식에 맞춰야 한다.
- **실기 확인**: 에코 제거가 켜진 마이크에서 끼어들기 임계값 500이 맞는지, Wake Lock 해제와 탭 숨김 보고가 기기마다 맞는지. 클라우드 환경이라 확인하지 못했다.
- **원음 저장 운영**: 위치와 백업(OQ5, 3.4 몫), 여러 인스턴스로 늘릴 때의 공유 저장소. U1 단일 인스턴스 표(SI-1 ~ SI-12)는 `aidlc/` 원본이라 고치지 않았다.
- **Core 시험 프로필 연결 시한**: 4절의 제안(U2 · U5a 영역).
- **tools device-simulator**: resume · PROGRESS 옵션은 넣지 않았다. 백엔드 IT의 `DeviceSimulatorClient`가 그 경로를 덮는다.

## 10. 합칠 때 주의

- U2가 core V2를 더하면 core-api `FlywayConfIT`의 표 수 기대값(지금 22 = core 12 + realtime V1 7 + realtime V2 3)과 충돌한다. 두 쪽이 더한 표 수를 합치면 된다.
- `frontend/src/shared/timing/timing-defaults.json`과 `frontend/src/shared/copy/ko.ts`는 U2 · U5a도 고칠 수 있다. U3는 키와 문구를 더하기만 했으니 양쪽 줄을 모두 살리면 된다.
- CI가 `tests` 전체를 돌게 바뀌어서 U2의 `tests/u2`도 CI에서 돈다.
- 리뷰 반영으로 `OutputBundle`에 아홉째 구성 요소(`onFrameSent`)가 생겼다. 8인자 생성자를 남겨 두어 다른 유닛 코드는 그대로 컴파일된다. `SessionMailbox.closeAndRelease`는 이제 남은 메시지 목록을 돌려준다(반환값을 쓰지 않는 호출은 그대로).

## 11. 리뷰 반영 (PR #2 리뷰 10건, 2026-10-12, 브랜치 `u3-fix`)

> 원래 작업자(클라우드 세션)가 한도에 걸려 리뷰 반영은 로컬 작업자가 이어 했다. Realtime 앱만 고쳤고 `frontend/src/device/`는 고칠 필요가 없었다(아래 4 · 5의 기기 동작은 이미 있는 CLOSE_TRANSPORT · SESSION_STATE 처리로 맞다). Core · `/parent`는 건드리지 않았다.
> 건마다 재현 시험을 먼저 쓰고 실패를 확인한 뒤(새 시험 22건 실패) 고쳐 통과시켰다. 기존 시험의 단언은 줄이지 않았다.

| 번호 | 지적 | 처리 | 재현 · 확인 시험 |
|---|---|---|---|
| 1 | 아이 소리 유실: 이어가기가 아닌 재연결 뒤 입력 순번이 옛 값 아래라 버려짐 | **고침.** `InputRelay`가 순번을 연결마다 다시 센다(`offer(source, …)`, 연결이 바뀌면 `lastSequence`를 되돌림). 실행기 `offerAudio`가 연결 id를 넘긴다. 이어가는 기기는 순번을 이어 보내므로 그대로 받는다 | `SessionExecutorReviewU3Test.aFreshLinkThatRestartsItsFrameSequenceIsRelayedFromTheFirstFrame` · `aResumedLinkThatContinuesItsSequenceKeepsRelaying` |
| 2 | 오판 끊김: 1006 · 전송 오류 CLOSED 뒤 40초 무음 점검이 같은 연결에 SILENT를 또 적어 두 번으로 셈 | **고침.** `markDisconnected`는 같은 connection_id에 CLOSED가 있으면 SILENT를 적지 않는다(`DeviceDisconnectRepository.exists`). 반대 순서(무음으로 이미 DISCONNECTED인 연결이 복귀 없이 늦게 닫힘)도 CLOSED를 적지 않는다. 복귀(HEARTBEAT) 뒤 다시 끊긴 것은 새 끊김이라 센다(기존 시험 그대로 통과) | `ConnectionStateWriterU3IT.aConnectionAlreadyCountedAsClosedIsNotCountedAgainWhenTheSilenceCheckMarksItDisconnected` · `aLateCloseOfAConnectionAlreadyCountedAsSilentIsNotCountedAgain` · `aSilenceAfterAnotherConnectionsCloseStillCountsAsItsOwnDisconnect` |
| 3 | 대화 멈춤: 청유형 인용("엄마가 그만하자고 했어")이 STOP이 되고 다시 풀리지 않음 | **고침.** ① 인용 표지에 청유형 인용(`자고했` · `자고해` · `자고하` · `자고말` · `자고그랬` · `자고그러` · `자더라` · `자던데` · `자대`)과 낱말 끝 준말 "~재"(`하재` · `내재` · `두재` · `자재`)를 더했다. "재미없어" · "천재"처럼 '재'로 시작하거나 다른 말은 걸리지 않는다. "자고" 단독은 아이 자신의 강조("이제 그만하자고!")일 수 있어 넣지 않았다. ② 복구 경로: STOP 뒤(세션은 ACTIVE, CLOSING(CHILD_STOP)은 U4) 아이가 종료가 아닌 확실한 말(판정 NONE · 불확실 아님 · 기기 재생 중 아님 · 빈 말 아님)로 이어 가면 막힘을 풀고 `CHILD_STOP_LIFTED`를 남긴다. 이미 취소한 응답의 늦은 조각은 여전히 버린다(AC6.4.8). 합성 20건 세트는 그대로 오인식 0 · 미인식 0 | `StopIntentJudgeTest.hortativeReportedSpeechIsNeverAStop`(10건) · `theHortativeQuoteMarkersDoNotSwallowOrdinaryStopsOrWordsWithJae` · `SessionExecutorReviewU3Test.aChildWhoKeepsTalkingAfterAStopIntentLiftsTheOutputBlockForNewResponsesOnly` · `anUnclearOrEmptyUtteranceAfterAStopIntentDoesNotLiftIt` |
| 4 | 재접속 멈춤: HELLO_OK.session 뒤 LinkReplaced 처리 전에 실행기가 끝나면 새 연결에 아무것도 가지 않음 | **고침.** 세 갈래를 모두 막았다. ① 실행기가 끝난 상태로 LinkReplaced를 처리하면 ② 정리(teardown) 때 편지함에 남은 LinkReplaced가 있으면(`SessionMailbox.closeAndRelease`가 남은 메시지를 돌려준다) ③ 인계가 이미 닫힌 편지함을 만나면(`putSafety` 거짓) — 모두 `SessionExecutor.standDown`으로 새 연결을 대기로 돌려보낸다. 이어간 기기(입력 창을 이미 다시 염)는 CLOSE_TRANSPORT(끝 상태에 맞는 사유: ABORTED → DISCONNECT, FAILED → FAILED, ENDED · ENDED_UNCONFIRMED → ENDED) + SESSION_STATE{STANDBY}, 이어가지 않은 기기는 STANDBY만. 그 ACK · VOICE_SESSION_CLOSED는 세션이 없어 버려진다 | `SessionExecutorReviewU3Test.aRelinkQueuedBehindTheSessionsEndSendsTheResumedDeviceBackToStandby` · `aNonResumedRelinkQueuedBehindTheEndOnlyGetsStandby` · `theStandDownHelperSendsCloseTransportOnlyToAResumedDeviceAndMapsTheEndingToAReason` · `ConnectionHandoverU3Test`(2건) |
| 5 | 끝나 가는 세션: CLOSING에서 resumed=false면 START_SESSION을 다시 보내 마이크가 다시 열림 | **고침.** CLOSING에 세션을 모르는 기기가 붙으면 START_SESSION 대신 SESSION_STATE{CLOSING} + CLOSE_TRANSPORT(GUARDIAN_STOP)를 보낸다(`closeForUnknownDevice`). 마무리 문구 차례였으면 그 기기가 재생할 수 없으므로 건너뛰고 지금 닫으며(`ClosingSequence.closeNow`) 입력 중계를 멈추고 transport_closed_at을 적는다(R3-8). 옛 연결의 마무리 문구 · 닫기 명령은 다시 보내지 않는다. 기기의 VOICE_SESSION_CLOSED로 30초 시한 전에 ENDED가 된다. 이어가는 기기(resumed)는 기존대로 ACK 못 받은 마무리 문구를 같은 opId로 받는다 | `SessionExecutorReviewU3Test.aFreshDeviceThatJoinsAfterCloseTransportWasAckedGetsCloseTransportNotStartSession` · `aFreshDeviceThatJoinsWhileTheClosingPhraseIsPendingSkipsItAndClosesTheTransportAtOnce` · `aResumedDeviceInClosingStillGetsTheUnackedClosingPhraseAndNoExtraClose` |
| 6 | 원음 범위: 게이트 통과 순간 기록해 기기로 못 나간 · 잘린 음성까지 남음 | **고침.** `OutputBundle`에 프레임 송신 콜백 `onFrameSent`를 더하고(8인자 생성자는 그대로 둠) `ConnectionSender`가 소켓에 쓴 직후에만 부른다. 실행기는 동의된 세션이면 이 콜백을 원음 기록 `ai`로 잇는다. OUTBOUND_FULL · 세대 변화로 잘린 나머지 · 쓰기 실패 프레임은 기록하지 않는다. 기존 `SessionVoiceU3Test`의 원음 시험은 묶음을 실제로 내보낸 뒤 같은 4,800바이트를 단언하게 고쳤다(단언은 그대로) | `SessionExecutorReviewU3Test.onlyAiFramesActuallyWrittenToTheDeviceAreRecorded` · `aiAudioCutByAGenerationChangeOrNeverWrittenIsNotRecorded` · `ConnectionSenderU3Test`(2건) |
| 7 | 자원 누수: 정리 앞 단계가 던지면 `recording.close`가 안 불림 | **고침.** 정리 단계마다 예외를 따로 받는다(`quietly`, 앱 오류 한 줄 뒤 다음 단계). 공급자 닫기가 던져도 원음 파일 핸들 · 메타 ended_at이 닫히고 편지함 정리 · 레지스트리 제거까지 간다 | `SessionExecutorReviewU3Test.theRecordingIsClosedEvenWhenAnEarlierTeardownStepThrows` |
| 8 | 삭제 참가자: 진행 중 세션의 원음 · 전사를 막지 않고 지워 되살아남 | **고침(최소 범위: 진행 중 세션의 기록 차단).** 참가자가 지우기 전에 그 보호자의 진행 중 세션(`ActivitySessionRepository.findInProgressIds`)마다 ① `FileAudioRecorder.block` — 새 메타 · 파일 · 조각을 받지 않고(파일 만들기와 차단 등록은 한 잠금이라 차단이 돌아온 뒤 새 메타가 생기지 않는다) 열린 파일을 닫은 뒤 돌아온다 ② `AsyncRecorder.discardTranscripts` — 대기 칸을 버리고 새 칸을 받지 않으며 진행 중 플러시가 끝나기를 기다린다(상한은 DB 문장 제한). 그다음 기존 순서로 지운다. 멱등 재호출에 기대지 않는다. 상한을 넘기면 `RecordingBlockTimeout` 앱 오류 한 줄만 남기고 진행한다(차단은 걸려 있다). 차단 목록은 메모리에 최대 1만 건(오래된 것부터 잊음) | `RealtimeVoiceDeletionParticipantIT.anInProgressSessionsRecordingIsBlockedBeforeItsRecordsAreErased` |
| 9 | 재생 추적: 재연결 때 지운 재생 추적을 다시 보낸 PLAY_APPROVED_CONTENT에 다시 걸지 않음 | **고침.** ACK 못 받은 PLAY_APPROVED_CONTENT를 같은 playbackId로 다시 보내면 `playback.started`로 다시 추적하고 20초 만기 시계를 건다. 그 재생이 도는 동안 또 폐기돼도 대기 문구를 겹쳐 보내지 않고(R3-19), 끝 표식이 추적을 지운다 | `SessionExecutorReviewU3Test.aResentWaitingFillerIsTrackedSoASecondFillerDoesNotOverlapIt` |
| 10 | 죽은 코드: `dialog/JoinTracker`를 main에서 부르지 않음 | **뺐다.** `JoinTracker.java` · `JoinTrackerTest.java`를 지우고 `backend/coverage-scope/u3.txt`에서 줄을 뺐다. design.md의 U3-R13(참여 판단)은 U4가 시계 · 전이와 함께 들일 규칙으로 표시했다. 지운 코드는 커밋 867f0a0에 남아 있어 U4가 되살려 쓸 수 있다 | (해당 없음 — 범위 파일에서도 빠짐) |

### 11.1 판단 · 남긴 위험

- **3의 복구 규칙**: STOP 뒤 아이가 종료가 아닌 말을 하면 대화를 다시 잇는다. 진짜 종료 의도 뒤 아이가 "응" 같은 짧은 말을 해도 풀린다. 지금은 U4가 없어 STOP 뒤 세션이 ACTIVE로 남아 AI가 끝까지 말하지 않는 것(지적의 '대화 멈춤')이 더 큰 문제라 이쪽을 골랐다. U4가 CLOSING(CHILD_STOP)과 확인 질문을 이으면 이 복구는 CLOSING 전까지만 뜻이 있다.
- **8의 남은 몫(U4)**: 진행 중 세션 자체를 끝내는 전이, 막 끝나 아직 기록기 칸이 남은 세션(진행 중 목록에 없음)의 차단, 삭제 중 새 세션 시작 막기는 U4 삭제 지휘가 순서를 정해야 한다. 차단 목록은 인스턴스 메모리라 여러 인스턴스로 늘리면 공유 표시가 필요하다(U3-J25와 같은 성격).
- **8의 Windows 차이**: 운영(Linux)은 열린 파일도 지울 수 있지만, 차단이 파일을 먼저 닫으므로 Windows에서도 시험이 통과한다.
- **4의 사유 매핑**: 끝 상태별 CLOSE_TRANSPORT 사유는 계약 Reason 값 안에서 골랐다(새 값 없음). 계약 편차는 더하지 않았다(메시지 · 모양 그대로).

### 11.2 Core 쪽 참고(고치지 않음)

- 4절의 `DeviceRegisterApiIT` 50ms 연결 시한 수정안(`backend/core-api/src/test/resources/application-test.yml`의 `mujoong.internal-http.connect-timeout` → 500ms)은 U4a 작업자에게 넘긴다.

### 11.3 시험 결과(리뷰 반영 뒤)

이 PC(Windows 11 · Git Bash · Docker Desktop, 데모 스택이 함께 돎)에서 돌렸다. `scripts/verify.sh`는 돌리지 않았다(조정자 몫).

| 항목 | 명령 | 결과 |
|---|---|---|
| 재현(고치기 전) | 새 시험 클래스 5개 `--tests` | 67건 중 22건 실패 — 건마다 지적한 증상으로 실패함을 확인 |
| 백엔드 U3 + 커버리지 | `:realtime-activity:test -PincludeTags=u3 :realtime-activity:jacocoTestCoverageVerification -PcoverageScope=u3` | 19개 클래스 165건 통과(실패 0), U3 범위 줄 커버리지 **81.2%**(3,157/3,889, 전 80.54%) |
| 백엔드 U3(다른 모듈) | `:contract-model:test :core-api:test :test-support:test -PincludeTags=u3` | 통과(u3 태그 시험 없음) |
| 백엔드 U1 회귀 | `:contract-model:test :core-api:test :realtime-activity:test :test-support:test -PincludeTags=u1` | realtime 542건 중 3건 · core 607건 중 4건 실패 → 아래 처리 |
| 프런트 U3 | `MUJOONG_COVERAGE_SCOPE=u3 npx --no-install vitest run tests/u3 --coverage` | 8개 파일 48건 통과, U3 범위 줄 84.46% |
| 프런트 U1 | `npx --no-install vitest run tests/u1` | 34개 파일 514건 통과 |
| 타입 · 린트 | `tsc --noEmit` · `eslint .` | 둘 다 0건 |

U1 회귀 실패 7건의 처리:

- `TimeLiteralScanTest`(realtime): **리뷰 반영 코드의 결함이었다.** 차단 대기에 `TimeUnit.MILLISECONDS`를 써서 U1의 '시간 숫자 상수 금지' 규칙에 걸렸다. `Condition.awaitNanos`로 바꿔 고쳤고 다시 돌려 통과했다.
- `RealtimeMetricsScrapeIT`(HTTP 요청 시한) · `RealtimeInternalApiIT`(기다린 조건 미도달) — realtime, `ActivityStartEndIT`(REALTIME_UNAVAILABLE) · `GracefulDrainIT`(DB 연결 실패로 entityManagerFactory 생성 실패) — core: 두 모듈 IT가 데모 스택 옆에서 동시에 돌 때의 부하 실패로 판단했다. 그 클래스만 다시 돌리니 모두 통과했다(realtime 6개 클래스 48건 · core 2개 클래스 14건). core-api는 이번에 고치지 않았다.
- `NoSqlNowTest.migrationAndSeedSqlNeverAskTheDatabaseForTheTime`(core): **이 PC에서 늘 실패하는 기존 문제다.** `Files.walk`로 찾은 경로를 `/src/main/resources/db/` 문자열로 거르는데 Windows 경로 구분자(`\`)와 맞지 않아 시드 파일 하나만 찾는다. 이번 변경과 무관하고 Linux CI에서는 통과한다. Core(U1 시험)라 고치지 않았다 — 고칠 때는 경로를 `/`로 바꿔 비교하면 된다.
- 프런트 `tests/u1/lint/eslint-boundary.test.ts` 1건이 첫 전체 실행에서 시한에 걸렸다(eslint를 띄우는 시험). 단독 실행 13건 통과, 전체 재실행 514건 모두 통과.

## 12. 리뷰 반영 2차 (조정자 2차 리뷰 9건, 2026-10-12)

> 1차 반영(11절)을 고치면서 새로 생긴 것과 덜 닿은 지점이다. 방법은 1차와 같다 — 재현 시험을 먼저 쓰고 실패를 확인했다(새 시험 12건 실패, 9번 조사 중 찾은 1건은 따로 재현). Realtime 앱만 고쳤고 `frontend/src/device/`는 고칠 필요가 없었다.

| 번호 | 지적 | 처리 | 재현 · 확인 시험 |
|---|---|---|---|
| 1 | 종료 의도 놓침: 인용 표지 '자대' · '자더라' · '자던데' · '자고하'가 공백을 지운 글자열에서 낱말 경계를 넘어 걸림("나 혼자 대화하는 거 그만할래" → NONE) | **고침.** 청유형 인용을 부분 일치 목록에서 뺐다. 낱말 끝 꼴(`자더라` · `자더라고` · `자던데` · `자대` · `자대요`, 1차의 `하재` 등과 같은 목록)은 낱말 끝에서만 보고, "~자고"는 같은 낱말 안 바로 뒤나 다음 낱말 첫머리가 말하기 동사(`했` · `해` · `하` · `말` · `그랬` · `그러`)일 때만 인용으로 본다. "혼자 대화" · "피자 대신" · "혼자 더라도" · "의자 던데"는 이제 STOP이다. 합성 20건 세트는 그대로 오인식 0 · 미인식 0 | `StopIntentJudgeTest.hortativeQuoteMarkersDoNotCrossWordBoundaries`(6건) · `hortativeQuotesAreStillFoundWithOrWithoutASpaceBeforeTheVerb`(6건) · 1차의 `hortativeReportedSpeechIsNeverAStop`(10건) |
| 2 | 생각 중 멈춤: 해제 직후 THINKING을 보내지만 그 발화의 응답은 이미 취소돼 끝 신호가 오지 않음 | **고침(THINKING을 보내지 않는 쪽).** 해제를 일으킨 확정 전사에는 `thinking()`을 부르지 않는다. `response.create`를 새로 보내는 길은 공급자 선 형식에 새 type을 더하는 일이라(계약 편차) 고르지 않았다. 그 발화의 응답이 아직 오지 않았다면 그대로 받아 말하기로 간다 | `SessionExecutorReview2U3Test.liftingTheStopIntentDoesNotLeaveTheDeviceThinking` |
| 3 | 끊김 이중 계수 비대칭: 무음으로 센 옛 연결이 기기의 새 연결 복귀 뒤 늦게 1006으로 닫히면 두 번 셈 | **고침.** 원장 행(같은 연결 + DISCONNECTED)을 보던 것을 사건 표로 바꿨다. 닫힘은 그 연결의 SILENT가 '그 연결에서 마지막으로 들은 시각' 이후에 있으면 같은 끊김이라 적지 않는다(`DeviceDisconnectRepository.existsSince`, 기기 게이트웨이가 연결의 마지막 수신 시각을 넘긴다). 지적대로 `exists(SILENT)`만 보면 같은 연결이 무음 뒤 HEARTBEAT로 복귀했다가 다시 1006으로 끊긴 새 끊김까지 버리므로(아래 둘째 시험이 지키는 경우) 시각 조건을 더했다. 시각을 모르는 호출은 `exists(SILENT)`와 같다 | `ConnectionStateWriterU3IT.aLateCloseOfASilentConnectionAfterTheDeviceCameBackOnANewConnectionIsNotCountedAgain` · `aCloseAfterTheSilentConnectionRecoveredIsANewDisconnect` |
| 4 | 재생 추적 한 칸: ACK 없는 재생 명령이 둘 이상이면 마지막 것만 추적 | **고침(하나로 줄임).** 새 연결에 다시 보내기 전에 ACK 없는 PLAY_APPROVED_CONTENT 중 가장 최근 것만 남기고 앞 것은 미확인 목록에서 뺀다. CLOSING의 마무리 문구는 늘 가장 최근 재생 명령이라 남는다. 끊기기 전 출력은 잇지 않는다는 편차 (ab)와 같은 쪽이다 | `SessionExecutorReview2U3Test.onlyTheLatestUnackedPlaybackIsResentAndItsEndClearsTheTracking` · 1차의 `aResumedDeviceInClosingStillGetsTheUnackedClosingPhraseAndNoExtraClose` |
| 5 | 핸드오버 경주: 뒤늦은 STANDBY가 같은 연결에 새로 묶인 세션의 표시를 덮음 | **고침.** `standDown`은 그 연결이 다른 세션에 묶여 있으면 아무것도 보내지 않는다(`DeviceLink.boundSessionId`를 인터페이스에 올림). 인계 · 정리 · 실행기 세 경로가 모두 이 함수를 지난다. 같은 이유로 실행기 정리와 연결 교체도 **그 연결이 이 세션에 묶여 있을 때만** 묶음을 푼다 — 9번을 조사하다 찾았는데, 끝난 세션의 정리가 같은 연결을 막 묶은 새 세션의 묶음을 풀면 새 세션의 입력 프레임이 버려진다. 확인과 송신 사이의 아주 짧은 틈은 남는다(잠금 없이 연결 객체 하나의 묶음 값을 본다) | `SessionExecutorReview2U3Test.standDownLeavesALinkAlreadyBoundToAnotherSessionAlone` · `theEndOfASessionDoesNotUnbindALinkThatANewSessionAlreadyTook` · `ConnectionHandoverU3Test.aConnectionAlreadyBoundToANewSessionIsNotSentBackToStandby` |
| 6 | 해제 조건 과함: 남의 말 · 주제 거부로 NONE이 된 발화도 종료 의도를 풂 | **고침.** 해제는 적극적 계속 표현(판정기의 CONTINUE 목록: "더 얘기하자" · "계속할래" · "그만 안 할래" 등)일 때만이다(`StopIntentJudge.continues`). 1차 시험의 해제 문장도 계속 표현("더 하자")으로 바꿨다(단언은 그대로) | `SessionExecutorReview2U3Test.reportedSpeechTopicRefusalOrOrdinaryTalkAfterAStopIntentDoesNotLiftIt` · `StopIntentJudgeTest.onlyAnActiveContinuationCountsAsContinuing` |
| 7 | 기록기 동시성: writer가 null이면 삭제 스레드가 기록기 전용 파일 맵을 만짐 | **고침.** 부른 쪽 스레드가 파일 맵을 다뤄도 되는 때를 '띄우는 호출이 돌아왔는데 스레드가 없다(작업을 돌리지 않는 시험 장치)'로 좁혔다. 기록기가 아직 시작 전(첫 open 전)이면 열린 파일이 없으므로 차단 표시만 하고 돌아오고, 띄우는 호출이 아직 안 돌아온 사이면 기록기 스레드가 처리하기를 기다린다. `drainNow`도 같은 조건을 쓴다 | `FileAudioRecorderIT.aBlockWhileTheWriterIsStillStartingIsHandledOnTheWriterThread` |
| 8 | 정리: 같은 상한 LinkedHashMap 집합이 세 곳 | **했다.** `realtime.common.collect.BoundedCollections`(`map` · `set` · `synchronizedSet`)로 모으고 실행기 · 전사 기록기 · 원음 기록기가 쓴다. U3 커버리지 범위에 더했다 | `BoundedCollectionsTest`(3건) |
| 9 | 흔들림: `SessionVoiceU3IT.rawAudio…`가 '제한 PT10S'로 한 번 실패 | **고침(원인 둘).** ① 이 클래스는 무음 판정만 운영값으로 두고 START 확인 2초 · 마무리 확인 3초 · HELLO 0.5초 · verify-token 0.1초 · 공급자 연결 1초는 시험 프로필 값을 썼다. 기계가 바쁘면 서버가 먼저 FAILED · ENDED_UNCONFIRMED · 1013으로 끝내 기기가 기다리던 LISTENING · CLOSE_TRANSPORT · HELLO_OK가 영영 오지 않는다 — 기다림 시한 10초는 증상일 뿐이다. 이 흐름이 지나는 서버 시한을 모두 운영값으로 두고, 이 클래스의 기기 · 공급자 기다림 상한은 30초로 넉넉히 했다(통과하는 실행은 메시지가 오면 바로 돌아온다). ② 고친 뒤 3회 연속 실행에서 같은 클래스의 `aBargeIn…`이 한 번 실패했다: 가짜 공급자가 AI 전사와 음성을 따로 보내는데 시험 프로필의 조용 시간 50ms가 그 사이에 끝나면 전사만 든 구간이 먼저 '미완'(GATE_EMPTY)으로 폐기되고, 응답당 한 번뿐인 교정이 '짧게 완결' 문장으로 나가 DIAGNOSIS 교정이 나가지 않는다. 운영(조용 시간 800ms)에서도 공급자가 늦으면 날 수 있는 제품 쪽 약점이라 실행기에서 고쳤다 — 미완 · 너무 김으로 폐기한 구간도 금지 패턴이 들어 있으면 그 범주의 교정 문장을 보낸다 | `SessionExecutorReview2U3Test.aDiscardedSegmentHoldingAForbiddenPatternGetsThatCategorysCorrectionWhateverTheDiscardReason`, `SessionVoiceU3IT` 3회 연속 |

### 12.1 판단 · 남긴 위험

- **2의 선택**: 해제를 일으킨 발화의 응답이 이미 취소됐으면 아이는 그 말에 대한 답을 못 듣고 다음 발화부터 대화가 이어진다. 공급자에게 응답을 다시 청하려면 선 형식에 `session.response.create` 같은 새 type이 필요하다(편차 (aa)에 더하기만 안전한 변경이지만 실공급자 어댑터 11/2 확정 전이라 넣지 않았다). U4가 CLOSING(CHILD_STOP) · 확인 질문을 이으면 이 해제 경로 자체가 짧아진다.
- **5의 남은 틈**: 묶음 확인과 송신 사이에 새 세션이 그 연결을 묶는 경우는 막지 못한다. 막으려면 연결 객체에 비교 후 송신을 한 잠금으로 묶는 API가 필요한데, 송신 레인 구조(U1)를 바꾸는 일이라 하지 않았다. 창은 같은 기기의 새 세션 시작이 이전 세션의 마지막 정리와 마이크로초 단위로 겹칠 때뿐이다.
- **9의 시험 프로필**: 시험 프로필(`application-test.yml`)의 줄인 시한은 그대로 두고 이 클래스만 운영값으로 덮었다. 같은 모양(정상 흐름을 끝까지 따라가는 IT)이 다른 클래스에도 있으면 같은 흔들림이 날 수 있다 — U1 IT는 손대지 않았다.

### 12.2 시험 결과(2차 반영 뒤)

| 항목 | 명령 | 결과 |
|---|---|---|
| 재현(고치기 전) | 새 · 고친 시험 클래스 6개 `--tests` | 93건 중 12건 실패 — 건마다 지적한 증상으로 실패함을 확인. 9번의 교정 범주 건은 따로 재현(1건 실패) |
| 백엔드 U3 + 커버리지 | `:realtime-activity:test -PincludeTags=u3 :realtime-activity:jacocoTestCoverageVerification -PcoverageScope=u3` | 21개 클래스 191건 통과(실패 0), U3 범위 줄 커버리지 **81.6%**(3,206/3,929) |
| 9번 흔들림 | `--tests '*SessionVoiceU3IT' --rerun` 3회 연속 | 고치기 전(①만 고친 상태) 3회 중 2회 실패(셋째 실행: `aBargeIn…`의 교정 범주. 둘째 실행의 실패 2건은 결과 파일이 덮여 내용을 남기지 못했다). ② 뒤 **3회 연속 4건씩 모두 통과** |
| 백엔드 U1 회귀(realtime 전체) | `:realtime-activity:test -PincludeTags=u1` | 542건 모두 통과 |
| 프런트 U3 | `MUJOONG_COVERAGE_SCOPE=u3 npx --no-install vitest run tests/u3 --coverage` | 8개 파일 48건 통과, U3 범위 줄 84.46% |
| 프런트 U1 | `npx --no-install vitest run tests/u1` | 34개 파일 514건 통과(단독 실행). tsc · eslint와 동시에 돌린 첫 실행에서는 1차 때와 같은 `tests/u1/lint/eslint-boundary.test.ts`의 한 건이 시한에 걸렸다 — 아래 |
| 타입 · 린트 | `tsc --noEmit` · `eslint .` | 둘 다 0건 |

- 프런트 `eslint-boundary.test.ts`의 '보호자 영역이 기기 영역을 import하면 lint가 실패한다' 한 건은 기계가 바쁠 때(이번엔 tsc · eslint를 같이 돌림) 시한에 걸리고 단독 · 재실행에서는 통과한다. 1 · 2차 모두 같은 양상이다. 이번 작업은 프런트를 고치지 않았고 U1 시험이라 손대지 않았다 — eslint 프로세스를 띄우는 시험이라 시한을 넉넉히 두거나 CI에서 따로 돌리는 것을 조정자에게 제안한다.
- Core는 이번에도 고치지 않았다. 1차의 `NoSqlNowTest`(Windows 경로 구분자) · Core 시험 프로필 연결 시한 제안은 11절 그대로다.

## 13. 3차 리뷰 반영 (조정자 3차 확인, 2026-10-12)

> 2차 반영(12절)에서 생긴 회귀 2건(1 · 2)과 덜 닫힌 곳 4건(3 ~ 6), 그리고 main(U2 병합본) 합치기다. 방법은 같다 — 재현 시험을 먼저 두고 실패를 확인했다(백엔드 21건 · 프런트 1건). 이번에는 지적 6건이 모두 맞다고 보았다.

| 번호 | 지적 | 처리 | 재현 · 확인 시험 |
|---|---|---|---|
| 1 | 회귀: 인용 표지를 낱말 끝에서만 보게 한 뒤 어미가 더 붙은 인용("그만하자더라고요" · "그만하자던데요" · "그만하자대서" · "그만하자더라구")이 CLEAR_STOP '그만하자'로 STOP | **고침.** 낱말 끝 목록을 없애고 '한 낱말 안에서 동사 줄기 끝(하 · 내 · 두 · 가 · 놀) + 청유 자 바로 뒤의 인용 어미(더라 · 던데 · 대 · 더래)' 또는 준말 '~재'(하 · 내 · 두 + 재)를 정규식으로 본다. 뒤에 요 · 고요 · 서 · 잖아 · 구가 붙어도 걸리고, "혼자 대화" · "피자 대신"은 여전히 걸리지 않는다. "~자고" 뒤 말하기 동사 첫머리에 '그래'를 더했다("그만하자고 그래") | `StopIntentJudgeTest.hortativeQuotesWithFurtherEndingsAreStillReportedSpeech`(10건) · 2차의 경계 시험 12건 · 합성 20건 세트 |
| 2 | 덜 닫힘: 해제가 CONTINUE 부분 일치라 (a) 부정형이 풀고 (b) 남의 말 속 계속 표현이 풀고 (c) 흔한 계속 표현은 못 풂 | **고침.** 해제 전용 목록 `RELEASE`를 판정용 CONTINUE와 따로 두었다(흔한 표현 "계속해" · "더 해 줘" · "또 하자" · "다시 하자" · "더 놀자" · "끝내지 마" 더함). 풀지 않는 조건: 남의 말(인용 표지 · 청유형 인용), 표현 바로 뒤가 안 · 않 · 싫 · 말(지않 · 지마 · 기싫 포함)인 부정형, '다음에 · 나중에 · 내일'로 미루는 말, 그 말 자체를 판정하면 STOP · CONFIRM인 말. 목록은 화면 설계 · 스토리에 근거가 없어 가정 U3-J26으로 design.md에 적었다. 1차 시험의 입력(SessionExecutorReviewU3Test "더 하자")은 그대로 둔다 — 이제 "더 해 줘"도 풀린다(3차 시험이 확인) | `StopIntentJudgeTest.commonContinuationsLiftTheStopIntent`(7건) · `negatedReportedOrStoppingContinuationsDoNotLift`(11건) · `SessionExecutorReview3U3Test.negatedOrReportedContinuationsKeepTheBlockAndACommonOneLiftsIt` |
| 3 | 흔들림: SessionVoiceU3IT의 segment-quiet가 시험값 50ms라 전사 · 음성 사이가 길면 첫 턴이 통째로 GATE_EMPTY | **고침(시험).** 이 클래스에서 `mujoong.gate.segment-quiet`도 운영값 800ms로 덮었다. 조정자 실패 기록의 turnSeq 1이 `quietWaitMs 60 · unobservedReason GATE_DISCARD`로 이 경우임을 확인했다. 제품 동작은 바꾸지 않았다(아래 남은 위험) | `SessionVoiceU3IT` 3회 연속(13.2) |
| 4 | 5번 남은 틈: `unbind` · `standDown`이 확인 뒤 행동 | **고침.** `DeviceLink.unbindSessionIf(expected)`를 두고 `DeviceConnection`은 참조 원자값의 값 비교 CAS로 구현했다(같은 값 · 다른 객체도 맞게). 실행기 `unbind`가 이것을 쓴다. `standDown` 쪽은 기기에서 닫았다 — `deviceSession.ts`가 지금 세션이 아닌 세션의 STANDBY를 무시한다(세션이 없을 때 · 지금 세션의 STANDBY는 그대로 따름). U1 기기 시험은 깨지지 않았다 | `SessionExecutorReview3U3Test.aNewSessionThatBindsTheLinkRightAfterTheEndChecksTheBindingKeepsIt`(가짜 연결이 확인 직후 · 비교 직전에 새 세션을 묶어 경주를 재현) · `DeviceConnectionU3Test` · 프런트 `device-session-resume.test.ts` '다른 세션의 대기 표시' |
| 5 | 7번 덜 닫힘: `destroy()`가 writer == null이면 부른 스레드가 파일 맵을 비움 | **고침.** 띄우는 호출이 아직 안 돌아왔으면 스레드를 받을 때까지(앱 종료 대기 상한) 기다리고, 부른 스레드에서 쓰는 것은 `writesOnCaller()`(작업을 돌리지 않는 장치)일 때뿐이다. 상한까지 스레드를 못 받으면 기록기 스레드가 멈춤 신호를 보고 스스로 마무리한다 | `FileAudioRecorderIT.aShutdownWhileTheWriterIsStillStartingLeavesTheFilesToTheWriterThread` |
| 6 | 문서 | **고침.** `contracts/device-ws.asyncapi.yaml` 다시 붙이기 설명과 `deviations.md` (ab)를 지금 동작으로(STOP_OUTPUT 제외 · PLAY_APPROVED_CONTENT는 가장 최근 하나 · CLOSING의 resume 없는 기기에는 CLOSE_TRANSPORT · 끝난 세션의 STANDBY와 기기의 다른 세션 STANDBY 무시). design.md U3-J6에 '종료 의도가 걸린 동안의 말 끝과 해제 발화에는 THINKING 없음'을 더했다 | — |

### 13.1 남은 위험

- **조용 시간보다 긴 공급자 지연(3번 조사)**: 게이트는 한 응답의 첫 조각부터 끝 신호까지를 다 담은 구간만 통과시킨다(J285). 그래서 운영에서도 공급자가 한 응답의 조각 사이를 `segment-quiet`(800ms)보다 길게 비우면 그 응답의 구간이 모두 GATE_EMPTY로 버려지고 아이는 대기 문구만 듣는다(교정은 '짧게 완결' 또는 금지 패턴 범주). 조용히 사라지지는 않지만 그 턴의 답은 없다. 이번에 동작은 바꾸지 않았다 — 실공급자 조각 간격(11/2 어댑터)을 본 뒤 조용 시간을 정하거나 '끝 신호 전 봉인'을 미루는 쪽을 U4 · 운영 조율에서 정할 일이다.
- **2번의 목록**: 해제 표현은 가정(U3-J26)이다. 목록 밖의 계속 표현("이야기 들려줘")은 풀지 못하고, 다음 확실한 계속 표현까지 AI가 말하지 않는다.
- **4번의 기기 쪽**: 서버가 다른 세션 id로 STANDBY를 보내는 길은 `standDown`뿐이라 기기 무시 규칙과 겹치는 다른 흐름은 없다(U1 기기 시험 그대로 통과).

### 13.2 시험 결과(3차 반영 뒤, main 병합 전 — 4ef0c54 + 아래 시험 수정)

| 항목 | 명령 | 결과 |
|---|---|---|
| 재현(고치기 전) | 새 · 고친 시험 `--tests` | 백엔드 94건 중 21건 · 프런트 12건 중 1건 실패 — 건마다 지적한 증상으로 실패함을 확인 |
| 백엔드 전체(태그 없음) | `:contract-model:test :realtime-activity:test :test-support:test` | contract-model 279 · realtime-activity 765 · test-support 27건 모두 통과 |
| 백엔드 U3 + 커버리지 | `:realtime-activity:test -PincludeTags=u3 :realtime-activity:jacocoTestCoverageVerification -PcoverageScope=u3` | 23개 클래스 223건 통과, U3 범위 줄 커버리지 **81.7%**(3,236/3,961) |
| SessionVoiceU3IT | `--tests '*SessionVoiceU3IT' --rerun` 3회 연속 | **3회 모두 4건 통과** |
| 프런트 | `tsc --noEmit` · `eslint .` · `vitest run tests/u3 --coverage` · `vitest run tests/u1` | tsc 0 · eslint 0, U3 8개 파일 49건(줄 84.82%), U1 34개 파일 514건. 하나씩 차례로 돌려 이번에는 시한 초과가 없었다. `tests/u2`는 main 병합 뒤에 생긴다 |
| core-api | — | 병합 뒤에 돌린다(아래 13.4) |

- 설계 의도 대조 8번(고정 시간 대기 금지)에 비춰 3차에서 넣은 `FileAudioRecorderIT.aShutdownWhileTheWriterIsStillStarting…`의 `Thread.sleep(200)`을 '종료 스레드가 기다리는 상태(TIMED_WAITING)가 될 때까지 기다림'으로 바꿨다. 다시 돌려 10건 통과.

### 13.3 설계 의도 대조(원작성자 인계 기준 — design.md 5절 U3-R1 ~ R17 · 8절 U3-J1 ~ J25)

| 번호 | 원작성자 의도 | 1 ~ 3차 수정 뒤 지금 | 판단 |
|---|---|---|---|
| 1 | 끊김 한 건 = HELLO_OK 뒤 1006 · 전송 오류 닫힘(CLOSED) 또는 40초 무음(SILENT). 한 연결에 CLOSED와 SILENT가 둘 다 기록되면 안 됨. 1000 · 4001 · 서버 판단 닫기는 세지 않음(U3-J2) | 세지 않는 코드는 그대로다(`DeviceStatusPolicy.countsAsDisconnect`). 같은 끊김의 이중 기록은 양쪽에서 막는다 — 무음 점검은 그 연결의 CLOSED가 있으면 SILENT를 적지 않고(1차 2), 닫힘은 그 연결에서 마지막으로 들은 시각 뒤에 SILENT가 있으면 CLOSED를 적지 않는다(2차 3) | **한 군데 다르다(의도적).** 같은 연결이 무음(SILENT) 뒤 HEARTBEAT로 복귀했다가 나중에 1006으로 끊기면 SILENT와 CLOSED가 둘 다 남는다. 두 번의 서로 다른 끊김이기 때문이다. 원작성자의 시험 `ConnectionStateWriterU3IT.aSilentDisconnectIsRecorded…SecondRecoveryInsideTheWindowGoesToUnstable`도 한 연결의 무음 → 복귀 → 무음을 두 건으로 센다. 그래서 '한 연결에 한 건'이 아니라 '한 끊김에 한 건'으로 읽었다. 복귀 없는 같은 끊김에는 둘 다 남지 않는다(1차 · 2차 시험) |
| 2 | 인용 · 역할 대사 · 주제 거부는 NONE, 스냅숏 · 명확한 종료 표현만 STOP, 물음꼴 · "그만" 단독은 CONFIRM, 합성 20건 오인식 0 · 미인식 ≤ 1. STOP이면 출력만 막고 세션은 ACTIVE(U3-R12 · J24) | 판정 순서와 분류는 그대로다. 인용 표지만 보강했다(청유형 인용, 1 · 2 · 3차). 합성 20건은 오인식 0 · 미인식 0. STOP이면 출력만 막고 세션은 ACTIVE다 | **맞다. 하나를 더했다.** STOP 뒤 적극적 계속 표현이면 막힘을 푸는 해제(`CHILD_STOP_LIFTED`, 1차 3 지시)를 더했다. U4b의 CLOSING(CHILD_STOP) 전이가 들어오기 전까지 STOP 오판이 세션 끝까지 AI를 막는 것을 풀기 위해서다. 목록은 가정 U3-J26 |
| 3a | resume이 진행 중 세션이면 resumed true, 명령을 다시 보내지 않음 | resumed true면 START_SESSION을 다시 보내지 않는다. ACK 못 받은 PLAY_APPROVED_CONTENT(가장 최근 하나) · CLOSE_TRANSPORT는 같은 opId로 다시 보낸다 | **맞다(읽기 정리).** 원작성자의 코드와 시험(`unackedCommandsAreResentOnTheNewLinkWithTheirOpIdsButAStaleStopOutputIsNot`, resumed true)이 미확인 명령을 다시 보내므로 '다시 보내지 않는 명령'은 START_SESSION이다. (ab) · asyncapi에 그렇게 적었다 |
| 3b | resume이 없으면 resumed false, 같은 approvedAudio로 START_SESSION 재전송 | 그대로다(ACTIVE · 시작 단계) | 맞다 |
| 3c | CLOSING에서는 START_SESSION을 다시 보내지 않고, ACK 못 받은 CLOSE_TRANSPORT · PLAY_APPROVED_CONTENT만 같은 opId로 | resumed true면 그대로다. resumed false(세션을 모르는 기기)면 SESSION_STATE{CLOSING} + **새 opId의** CLOSE_TRANSPORT를 보내고 마무리 문구는 다시 보내지 않는다(1차 5). 재생 명령은 가장 최근 하나만(2차 4) | **resume 없는 경우만 다르다(의도적).** 세션을 모르는 기기는 그 세션의 PLAY_APPROVED_CONTENT를 재생하지 않으므로(기기 `isCurrent` 거름) 마무리 문구를 다시 보내면 30초 시한까지 기다리다 ENDED_UNCONFIRMED가 된다. 또 지적 사례(CLOSE_TRANSPORT를 ACK한 뒤 세션을 잊은 기기)에는 미확인 CLOSE_TRANSPORT가 아예 없어 같은 opId로 다시 보낼 것이 없다. '가장 최근 재생 하나만'은 (ab)에 맞췄다(3차 6) |
| 3d | 재연결 때 InputRelay의 마지막 순번을 이어 받아 아이 소리를 버리지 않음 | 순번을 연결마다 다시 센다(1차 1). 이어가는 기기는 자기 순번을 이어 보내므로 그대로 받고, 새 페이지(1부터)도 받는다 | **목적은 맞고 방법이 다르다.** 서버 쪽 마지막 순번을 이어 받으면 새로 켠 페이지(1부터 보냄)의 프레임이 옛 순번을 넘을 때까지 모두 버려진다(1차 지적 1). 연결마다 다시 세면 두 경우 모두 버리지 않는다 |
| 4 | ACTIVE에서 session.closed · WSS 닫힘 → ABORTED(DISCONNECT, PROVIDER_LOST), session.error는 WARN만, 시작 단계 끊김은 FAILED(PROVIDER_UNAVAILABLE)(U3-R8 · J13) | 바꾸지 않았다 | 맞다 |
| 5 | 교정은 게이트 폐기 뒤 응답당 한 번, 범주별 고정 문장. 아이 입력 재개 · 다른 응답 도착으로 확정된 미완 구간은 교정 안 함. 전사 · 걸린 패턴은 본문에 안 넣음(U3-R7 · J12) | 2차 9번은 '어느 문장을 고르는가'만 바꿨다(폐기 구간에 금지 패턴이 있으면 사유가 미완 · 너무 김이어도 그 범주 문장). 교정을 할지 말지는 그대로 `sealAndGate`의 `!movedOn \|\| GATE_PATTERN`이 정한다 — 대화가 넘어가 확정된 미완 구간(GATE_EMPTY)은 여전히 교정하지 않는다. 문장은 여전히 `DialogInstructions.Correction` 고정 템플릿이다 | **맞다.** 응답당 한 번(`correctedResponses`)도 그대로다 |
| 6 | 동의된 세션만 · 아이 입력과 승인된 AI 출력만, 전용 스레드 · 유계 대기열 · 세션당 64MB, 끝나면 기록기를 닫고 ended_at, 삭제는 진행 중 세션 기록을 먼저 멈춘 뒤 파일 삭제(U3-R9 · J14 · J15) | 동의 · 스레드 · 대기열 · 상한은 그대로다. AI 출력은 '실제로 기기로 보낸 프레임'만으로 좁혔다(1차 6). 정리는 단계마다 예외를 따로 받아 원음이 늘 닫힌다(1차 7). 삭제 참가자는 진행 중 세션의 원음 · 전사 기록을 먼저 막고 지운다(1차 8). 차단 · 종료가 기록기 스레드 전용 맵을 다른 스레드에서 만지지 않게 했다(2차 7 · 3차 5) | 맞다(더 좁혔다) |
| 7 | JoinTracker는 되살리지 않음(U4b가 867f0a0에서 되살림). StopIntentJudge의 공개 메서드 이름 · 의미 유지 | JoinTracker는 없다. StopIntentJudge 공개 API(생성자 · `judge` · `Verdict` · `Basis` · `Judgment`)는 867f0a0과 같고 `continues`만 더했다. 인계에 적힌 `classify` · `phase` · `prompts`는 JoinTracker의 것(867f0a0의 `JoinTracker.classify` · `Phase` · `MAX_PROMPTS` · `prompts`)이라 이번 변경과 무관하다 | 맞다 |
| 8 | U1 단언 · 커버리지 하한을 낮추지 않음, 시간에 민감한 시험은 상태 · 메시지 대기, 시험 프로필 internal-http.connect-timeout 500ms 유지 | U1 시험 단언은 바꾸지 않았다(1차에서 원음 시험이 묶음을 실제로 내보내게 한 것은 같은 단언을 더 엄격한 길로 지킨 것). connect-timeout은 500ms 그대로다. `SessionVoiceU3IT`는 서버 시한을 운영값으로 두고 메시지 도착을 기다린다. 3차에서 넣은 고정 대기 하나를 상태 대기로 바꿨다(13.2) | 맞다 |

### 13.4 main 합치기 — 이 세션에서 하지 못함

- 인계대로 `git fetch origin && git merge origin/claude/friendly-shannon-i89iyx` → `git merge main` 순서로 합치려 했다. 그러나 이 작업 세션의 권한 설정에서 `git fetch` · `git merge` · `git merge-tree`가 모두 '승인 필요'로 막혀 실행하지 못했다(우회하는 저수준 명령은 쓰지 않았다).
- 그래서 병합 커밋, 병합 뒤 core-api(NoSqlNowTest 포함) · 백엔드 전체 · 프런트 `tests/u2` 확인은 아직이다. 충돌 푸는 법(인계 1절: FlywayConfIT 표 수 24, deviations.md 양쪽 행 유지, SchemaPrivilegeIT · timing.md · ko.ts 양쪽 내용 확인)은 그대로 따르면 된다. 병합 권한이 열리면 이어서 하겠다.
