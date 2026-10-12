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
| DialogPolicy(L4) | 역할 지식을 분리한 대화 지시문, 종료 의도 판정기(STOP · CONFIRM · NONE, 20건 세트 오인식 0 · 미인식 0), 참여 판단 추적기(20초 · 재안내 · 대안 1회 · 최대 3회). 실행기는 STOP이면 출력을 취소하고 `CHILD_STOP_INTENT`를, CONFIRM이면 `CHILD_STOP_UNCLEAR`만 남긴다 | `realtime/dialog/` |
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

- **U4**: 종료 의도 확정 전이 CLOSING(CHILD_STOP)과 마무리 인사 · 확인 질문(STOP_CONFIRM · RE_PROMPT), 참여 판단 추적기를 시계와 함께 실행기에 연결, WAITING_CHILD · DECLINED · SILENCE 전이, UNCONFIRMED 끊김 구간, 12 화면의 끊김 구간 표시(`device.gap-display` 10초).
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
