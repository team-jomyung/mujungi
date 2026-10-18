# 구조 · 이름 리팩토링 — 기준선 (단계 0 안전망, Step 1 ~ 4)

> 작업 기록 `261018-structure-refactor` · 브랜치 `claude/refactor-structure` · 측정 시점 2026-10-18.
> 측정한 체크아웃: 원격 `main` 570a625 + `docs/aidlc` 기록 커밋(91c7bb7)만 더해진 상태다. 소스(`backend` · `frontend` · `tools` · `infra` · `scripts` · `contracts`)는 570a625와 같다. `verify.sh`가 보고한 `revision=91c7bb74511f`이다.
> 이 문서의 모든 시험 · 검증 결과는 **테스트 대역(가짜 공급자) · 합성 대화** 기준이다. 실제 API · 실제 기기 성공 증거는 이 문서에 없다(R-M11).
> 이 문서의 수치는 모두 이 PC에서 명령으로 측정한 값이다. 코드로 확인하지 못한 것은 "미확인"으로 적었다.

## 1. `main` 기준 검증 결과 (Step 1)

### 1.1 시험 실행기 확인

`unit-test-instructions.md`의 명령이 실제로 돈다(JDK 21.0.12 · Node 22.22.3 · Docker, `sg docker -c '…'`).

| 명령 | 결과 |
|---|---|
| `sg docker -c 'cd backend && ./gradlew clean check -Pmujoong.test.db=docker'` | `BUILD SUCCESSFUL in 4m 22s`, 종료 코드 0 |
| `sg docker -c 'cd backend && ./gradlew :realtime-activity:test --tests "kr.mujoong.realtime.session.*" -Pmujoong.test.db=docker'` | 통과, 56개 시험 클래스 · **503건**, 실패 0 (`BUILD SUCCESSFUL in 1m 30s`) |
| `sg docker -c 'bash scripts/verify.sh --mode compose'` | `RESULT PASS rc=0 total=386s` |

### 1.2 백엔드 `gradlew clean check` (시험 수)

| 모듈 | 시험 클래스 | 시험 수 | 실패 | 오류 | 건너뜀 |
|---|---|---|---|---|---|
| `contract-model` | 18 | 322 | 0 | 0 | 0 |
| `core-api` | 222 | 1722 | 0 | 0 | 0 |
| `realtime-activity` | 205 | 1862 | 0 | 0 | 0 |
| `test-support` | 5 | 31 | 0 | 0 | 3 (`MujoongPostgresTest`의 `backend = LOCAL` 매개변수 3건, 도커 모드라 시험 코드가 건너뜀) |
| **합계** | 450 | **3937** | 0 | 0 | 3 |

- 이 합계 3937건은 `verify.sh`의 `A1.tests PASS 시험 3937건 · 실패 0 · 건너뜀 3`과 같다(두 번 따로 돌려 같은 값). **단계 전후 시험 수 대조의 기준값이다.**
- 모듈 전체 JaCoCo 줄 80%(`check`)는 통과했다(`jacocoTestCoverageVerification` 통과).

### 1.3 `verify.sh --mode compose` 단계별 결과

| 단계 | 결과 | 소요 | 내용 |
|---|---|---|---|
| A1 backend | PASS | 263s | `clean check verifyBootJarHasNoTestClasses`, 시험 3937건 · 실패 0 · 건너뜀 3, bootJar 두 개 · 시험 클래스 0 |
| A2 frontend | PASS | 36s | `npm ci` · `eslint .` · `vitest run tests --coverage` · `next build`. 시험 파일 **74** · 시험 **994** 통과, 줄 커버리지 98.55% |
| A3 tools | PASS | 10s | 시험 파일 **12** · 시험 **133** 통과 |
| A4 secrets | PASS | 0s | 키 형식 9종 · 파일 3353개, 걸림 0 (gitleaks가 없어 이력 스캔은 CI 몫) |
| F 지문 | PASS | 0s | 소스 지문 기록 |
| B 기동 | PASS | 54s | 이미지 5종 빌드 · 병합 설정 · migrate · 시드 · 프로필 가드 · 기동 · 3종 호출(health 200 · `/device` 200 · WSS 101) |
| C 한 턴 | PASS | 21s | 기기 시뮬레이터 C1 ~ C12 통과(D0 → 시험 로그인 → 05a → 04 → 08 → 음성 1턴 → 게이트 경유 → P1 → ENDED), 가짜 공급자 |
| P1 / P2 / P3 | PASS | 1s / 0s / 0s | 교차 스키마 쓰기 42501 · 로그 위생 · 비밀 값 0건 |
| T 작업 트리 | PASS | 0s | 시작 · 끝 작업 트리 같음(감사 기록 조각 제외) |

- **증거 구분**: B ~ C는 가짜 공급자와 합성 대화로 돈 것이다. 실제 AI 공급자 · 실제 기기 성공을 뜻하지 않는다.
- **`main` CI `skeleton` 실패 이력과의 관계**: 이 PC의 `verify.sh --mode compose`(CI `skeleton`이 `main` 푸시마다 도는 것과 같은 명령)는 이 체크아웃에서 **전 단계 통과**했다. CI의 `skeleton` 실패(#90 · #92) 원인은 이 기록에서 조사하지 않았고, 이 결과로 CI 실패 원인을 추정하지도 않는다. 이 PC에서 통과한다는 사실만 기록한다.
- 시험 수를 단계 전후로 대조할 기준: 백엔드 3937(건너뜀 3), 프런트 74파일 · 994건, tools 12파일 · 133건.

## 2. 실행기 7구간 ↔ 시험 대응표 (Step 2)

`SessionExecutor.java`는 4,361줄이다. 구역 제목(`// ---- 제목`) 기준으로 나눈 줄 범위와, 그 구역을 직접 고정하는 기존 시험이다. 시험 수는 `build/test-results`의 시험 건수(매개변수 시험은 펼친 수)이고 `SessionHarness` 또는 `SessionExecutor`를 직접 부르는 시험 파일은 35개다(`SessionHarness`를 쓰는 파일 35, 그중 `session` 패키지 안은 26).

줄 커버리지는 JaCoCo 줄 기준 `SessionExecutor.java` 구간별 값이다. 둘째 값은 Step 2의 검증 명령(`session.*` 시험만)으로 돌렸을 때다.

| 7구간 | 구역(줄 범위) | 고정하는 기존 시험(시험 수) | 줄 커버리지 전체 / `session.*`만 | 판정 |
|---|---|---|---|---|
| 1. 메시지 수신 | 실행 620~694 · 외부 입구 695~780 · 메시지 처리 781~1016 | `SessionExecutorTest`(22: 시작 순서 · ACK/READY 활성화 · 편지함 우선순위 · 오래된 시계 세대 · DB 장애 재전달 · 종료 뒤 늦은 메시지 · 공급자 실패) · `SessionExecutorU3Test`(10: 연결 교체 · 미확인 명령 재전달 · 끊김 구간) · `SessionExecutorReviewU3Test`(14) · `SessionExecutorReview2U3Test`(6) · `SessionExecutorReview3U3Test`(2) · `SessionExecutorFaultTest`(12: 최상위 예외 · 잃은 응답) · `SessionVoiceU3Test`(18: 끼어들기 · 세대 · 패턴 폐기) · `SessionVoiceU3IT`(4) · `GenerationSendExecutorTest`(21) · `SessionLifecycleIT`(8) | 입구 94.7% · 메시지 91.3% · 실행 86.0% / 84.2% · 91.3% · 86.0% | 고정 시험 있음 |
| 2. 참여 확인 | 참여 확인 1017~1094 | `SessionJoinU4bTest`(16: 19초 · 21초 · 거절 · 주제 거절 · 모호 · 안내 3회 상한 · 재생 중 답) · `JoinJudgmentExecutorTest`(8) · `SessionLifecycleU4bIT`(4) · `PrivacyLogU4bTest`(6, 참여 경로) · `JudgmentNoProviderRegressionTest`(4) | 93.2% / 93.2% | 고정 시험 있음 |
| 3. 판정 | "U11 판정 턴" 1095~1354 (아래 2.1 참고: 안에 참여 결과 · 마무리 인사 · 정산이 섞여 있음) | `JudgmentTurnExecutorTest`(9) · `JudgmentFaultExecutorTest`(7) · `JudgmentExtensionPortTest`(9) · `StopJudgmentExecutorTest`(11) · `JoinJudgmentExecutorTest`(8) · `JudgmentNoProviderRegressionTest`(4) / 마무리 인사: `SessionJoinU4bTest` / 정산: `SessionSettleU4bTest`(7) · `SessionRecordU4bIT`(6) | 90.6% / 85.5% | 고정 시험 있음 |
| 4. 연습 확장점 | 연습 확장점 1355~2808 (`JudgmentEnv` · `GuardrailEnv` · `PracticeOutputPort` 내부 클래스 포함) | `PracticeExtensionU4bTest`(9) · `PracticeGeneratedSpeechTest`(10) · `PracticeExecutorIntegrationTest`(7) · `PracticeRuntimeRaceTest`(2) · `PracticeStopConfirmRaceTest`(4) · `PracticeFlowIT`(2) · `PracticeStartPathIT`(3) · `StopJudgmentExecutorTest`(11, 연습 보류) | 91.1% / 87.5% | 고정 시험 있음 |
| 5. 게이트 | 게이트 3061~3334 | `GuardrailExecutorTest`(20) · `GuardrailCoordinatorTest`(39) · `GuardrailApprovedSkipTest`(7) · `GuardrailSafetyTimerTest`(4) · `GuardrailPracticeEventTest`(5) · `GuardrailMetricsTest`(6) · `GuardrailTurnIT`(4) · `OutputGateTest`(17, `gate` 패키지) · `PracticeGeneratedSpeechTest`(10) | 91.6% / 91.0% | 고정 시험 있음 |
| 6. 끝내기 | 기기 표식 · 끝내기 3335~3838 | `EndRequestU4bTest`(21) · `SessionStopU4bTest`(6) · `SessionSettleU4bTest`(7) · `SessionRecordU4bIT`(6) · `SessionExecutorFaultTest`(12, 닫기 순서) · `SessionLifecycleIT`(8) · `SessionExecutorU3Test`(10) · `SessionExecutorReviewU3Test`(14) · `TransitionTableU4bTest`(24) · `RealtimeDeletionParticipantOrderTest`(10) · `RealtimeDeletionHandlerIT`(4) | 92.3% / 92.3% | 고정 시험 있음 |
| 7. 시계 | 시계 3839~4022 (+ 재전달 4023~4079 · 공백 시계 2809~3060은 아래 별도 행) | `SessionTimersTest`(11: ACK · READY · 접속 끊김 · 닫기 확인 · 재무장) · `SessionExecutorTest`(오래된 시계 세대 · 재전달) · `SessionExecutorFaultTest`(닫기 확인 · 끊김 점검) · `SessionLifecycleIT`(ACK 없음 · 끊김) · `SessionSettleU4bTest`(RECORD_SETTLE) · `RealtimeDeletionHandlerIT`(`onDeletionStop`) | 시계 88.7% · 재전달 78.1% / 88.7% · 78.1% | 고정 시험 있음 |
| (별도) 공백 시계 · 기술 일시정지 · 진행 조회 | 공백 시계 2809~3060 (계획 3.3 `IdleClock`) | `SessionSilenceU4bTest`(4) · `TechnicalPauseU4bTest`(8) · `SessionViewU4bIT`(2) · `SessionStopU4bTest`(6) · `StopJudgmentExecutorTest`(11) | 93.8% / 90.3% | 고정 시험 있음 |

**결론: 7구간 모두 기존 시험이 고정하고 있어 새 시험을 더하지 않았다**(BR5.3 "이미 있는 시험이 덮으면 구간 ↔ 시험 대응표만 만든다"). 새 시험 파일 0개이므로 `test(refactor)` 커밋도 없다.

### 2.1 발견: "U11 판정 턴"(1095~1354) 구역의 실제 내용

구역 제목은 "U11 판정 턴"이지만 260줄 중 판정 턴(J1)은 앞부분뿐이고, 뒤는 참여 결과 · 마무리 인사 · 정산이다. 계획 3.4는 이 구역 전체를 `JudgmentCoordinator`로 흡수한다고 적었으므로 Step 16 시작 전에 사람이 확인해야 한다.

| 메서드(시작 줄) | 하는 일 | 성격 |
|---|---|---|
| `openJoinJudgment` 1101 · `afterJoinJudgment` 1114 · `applyJoinAction` 1138 | 불명확한 참여 대답에 J1 판정 턴을 열고 결과를 참여 행동으로 옮김 | 판정 (약 66줄) |
| `acceptJoin` 1161 · `afterActiveWon` 1177 · `declineJoin` 1195 | 참여 수락 → ACTIVE, 거절 마무리 | 참여 확인의 결과 |
| `startFarewell` 1213 · `finishFarewell` 1236 · `deviceStillOurs` 1247 | DECLINED 마무리 인사 | 끝내기 |
| `afterTerminal` 1261 · `finishSettle` 1300 · `practiceRecordForSettle` 1328 · `closingReasonOrRow` 1347 | 종료 뒤 정산 창 · 기록 | 끝내기 정산 (약 94줄) |

- 줄 수 계산(260줄이 실행기에서 빠진다)은 어느 협력 객체로 가든 같다. 다만 "`JudgmentCoordinator`로 흡수"는 앞 66줄에만 맞다. 나머지 194줄의 목적지(`JoinFlow` / `EndingFlow`)를 Step 16 전에 정해야 한다. 이 문서는 설계 문서를 고치지 않고 사실만 적는다.
- 이 구역이 쓰는 필드 `settling` · `sessionEndedAt` · `providerClosedSignal` · `practiceEventsInSettle`이 연습 확장점 구역에서도 읽히므로 3절 표에서 `SessionContext`로 배정했다.

### 2.2 고정 시험이 약한 가지(참고, 새 시험은 더하지 않음)

구간 단위로는 고정 시험이 있으므로 시험을 더하지 않았다. 호출자가 원하면 추출 전에 더할 수 있도록, 전체 시험으로도 한 번도 지나가지 않는 줄을 구간별로 적는다(JaCoCo, 전체 `check` 기준).

| 구간 | 지나가지 않는 줄 | 내용 |
|---|---|---|
| 시계 | 3868 · 3879 ~ 3880 · 3921 ~ 3927 · 3935 ~ 3936 · 3949 ~ 3955 · 3965 ~ 3966 · 3983 | `JUDGMENT_READY` 만기 전달, `PLAYBACK_EXPIRY`의 종료 확인 재생 가지, ACK 대기 · 닫기 확인의 지연 재무장(`timers.arm(…, threshold.minus(elapsed))`), DB 장애 가지의 `syncFromDb` |
| 연습 확장점 | `PracticeOutputPort.cancelSpeech`(9줄 전부) · `cancelResponse`(4줄 전부) 외 | 연습 출력 능력 중 말 취소 · 응답 취소 |
| 게이트 | 3078 · 3107 ~ 3108 · 3138 ~ 3142 · 3171 ~ 3172 · 3204 ~ 3207 | `sealAndGate` · `send`의 조기 반환 · `RuntimeException` 가지(3107 · 3138), 보류 송신(3171), 출력 큐 가득 찬 폐기 계측(3204) |
| 끝내기 | 3469 ~ 3470 · 3494 ~ 3499 · 3597 · 3608 · 3611 · 3618 · 3627 ~ 3628 · 3668 · 3691 ~ 3692 · 3710 · 3722 · 3787 · 3800 · 3821 | 연결 교체의 `standDown` 호출(3469), 참여 안내 재생 번호 정리(3494), `closeReasonFor`의 `DECLINED` · `stopReasonFor` · `closeReasonForClosing`의 `GUARDIAN_STOP` 가지(3597 · 3787 · 3800), 동의 철회 중 DB 동기화 · 아이 녹음 닫기(3691 · 3710) 등 |
| 재전달 | 4027 · 4039 · 4043 · 4047 ~ 4049 · 4053 | 재전달 시계의 대기열 추가(4027) · 반환 가지(4039) · `DataAccessException` 가지(4047) · 재무장(4053) |

줄 번호는 `SessionExecutor.java` 기준이고 내용은 그 줄을 직접 읽어 적었다. 위험이 가장 큰 곳은 시계의 지연 재무장(Step 14 `SessionClocks`)과 연습 출력 능력의 취소 둘이다. 이 둘은 이동 · 추출 전에 특성화 시험이 필요한지 사람이 정할 일이다.

## 3. `SessionExecutor` 필드 소유자 표 (Step 3)

### 3.1 읽는 법과 확인 방법

- 대상: 머리 1~619줄(import · 클래스 설명 · 내부 타입 · 상수 · 필드 · 생성자 · `liveState()`)과 끝 "전이 보조" 4080~4361줄.
- 읽기 · 쓰기 구역은 `SessionExecutor.java` 전체에서 필드 이름을 단어 단위로 찾아 구역(1절 구역 제목)별로 센 값이다. 같은 이름의 지역 변수 · 다른 객체의 접근자 호출이 섞일 수 있어 횟수는 쓰지 않고 구역 이름만 적는다. 쓰기는 `=` · `++` · 컬렉션의 `add/put/clear/remove` 등이 걸린 줄이다.
- 구역 약어: 입구=외부 입구, 메시지=메시지 처리, 참여=참여 확인, 정산=판정 턴 구역 중 참여 결과 · 인사 · 정산, 판정=판정 턴 구역 중 J1, 연습=연습 확장점, 공백=공백 시계, 게이트, 끝내기, 시계, 재전달, 전이=전이 보조, 머리=머리 필드 선언 · 생성자.
- 소유자 기준: ① 한 구역(또는 한 협력 객체가 받는 구역)만 읽고 쓰면 그 협력 객체, ② 둘 이상의 구역(특히 실행기에 남는 구역과 떠나는 구역)이 읽거나 쓰면 `SessionContext`, ③ 실행기에 남는 구역(메시지 · 연습 · 게이트 · 재전달)만 쓰면 남는 실행기.
- `SessionClocks`(시계 구역)는 **소유하는 필드가 없다.** 시계 구역이 읽는 `startAttemptAt` · `ackReceived` · `deviceReady` · `startSent` · `closingAt` · `closingReason` · `closingDetail` · `joinPromptPlaybackId` · `stopConfirmPlaybackId`는 모두 다른 구역이 쓰므로 `SessionContext`를 통해 읽는다.
- 휘발성(`volatile`) 필드 `status` · `view` · `terminated` · `processing`과 `AtomicReference live`는 다른 스레드(HTTP · 조회)가 읽으므로 옮길 때 `volatile` · 원자성을 그대로 유지해야 한다.

### 3.2 내부 타입 · 공개 상수

| 대상 | 줄 | 외부 참조(`SessionExecutor.` 이름으로) | 소유자 | 비고 |
|---|---|---|---|---|
| `Settings`(+`Lifecycle`) | 131~206 | 운영 2파일 · 시험 4파일 | 남는 실행기 | 공개 타입. 옮기면 참조 갱신이 늘어 이번 분리에서 옮기지 않는다 |
| `Services` | 209~296 | 운영 3 · 시험 3 | 남는 실행기 | `RealtimeRuntimeConfig`가 생성 |
| `ApprovedItem` | 302~312 | 운영 1 · 시험 1 | 남는 실행기 | |
| 공개 상수 `INTRO_FREE_TALK` `INTRO_PRACTICE` `RE_PROMPT` `DECLINE_ALTERNATIVE` `STOP_CONFIRM` `SILENCE_WARNING` | 315~320 | 시험 5 ~ 13파일, 운영 1 ~ 3파일 | 남는 실행기 | 운영 코드가 읽으므로 이름 · 위치 유지 |
| `Plan` | 327~402 | 운영 3 · 시험 3 | 남는 실행기 | 불변 값. 모든 협력 객체가 `SessionContext`로 받음 |
| `PracticePause` 레코드 | 560 | — | 남는 실행기(연습) | 연습 일시정지 구간 |
| `LiveState` 레코드 + `liveState()` | 607~613 · 616~618 | 운영 2 · 시험 2 | `IdleClock` | `live` 필드와 함께 이동. 이름 참조 갱신 필요 |
| `PracticeTimer` 레코드 | 1494 | — | 남는 실행기(연습) | |
| `JudgmentEnv`(1652) · `GuardrailEnv`(1720) · `PracticeOutputPort`(1909) | 연습 확장점 구역 안 | — | 남는 실행기 | 연습 구역과 함께 남는다. 계획 3.7 판단 때 같이 본다 |

### 3.3 필드 소유자 (머리 1~619줄)

**① `SessionContext`로 옮길 공유 상태**

| 필드 | 읽는 구역 | 쓰는 구역 | 비고 |
|---|---|---|---|
| `plan` | 거의 전 구역 | 생성자 | 불변 |
| `services` | 전 구역 | 생성자 | 불변 |
| `mailbox` | 실행 · 입구 · 메시지 · 연습 · 끝내기 · 전이 | 생성자 | |
| `status`(volatile) | 전 구역 | 전이(`changeStatus`) | |
| `view`(volatile) | 입구 · 메시지 · 연습 · 공백 · 끝내기 · 전이 | 전이 | |
| `terminated`(volatile) | 실행 · 입구 · 정산 · 연습 · 공백 · 재전달 · 전이 | 전이(`teardown`) | |
| `link` | 입구 · 메시지 · 판정 · 연습 · 공백 · 게이트 · 끝내기 · 시계 · 전이 | 메시지 · 끝내기 | |
| `timers` | 머리 · 입구 · 메시지 · 참여 · 판정 · 연습 · 공백 · 게이트 · 끝내기 · 시계 · 재전달 · 전이 | 생성자 | |
| `relay` | 입구 · 메시지 · 판정 · 끝내기 · 시계 · 전이 | 생성자 · 입구 · 메시지 · 판정 · 끝내기 · 시계 | |
| `segment` | 판정 · 연습 · 공백 · 게이트 · 끝내기 · 전이 | 판정 · 연습 · 끝내기 | |
| `playback` | 참여 · 판정 · 연습 · 공백 · 게이트 · 끝내기 · 시계 · 전이 | 끝내기 · 시계 | |
| `closing`(ClosingSequence) | 판정 · 게이트 · 끝내기 · 시계 | — (상태 객체 내부 변경) | |
| `transcripts` | 판정 · 연습 · 공백 · 게이트 · 끝내기 · 전이 | 생성자 | |
| `outputGeneration`(AtomicLong) | 입구 · 연습 · 게이트 · 전이 | 전이(`bumpGeneration`) | |
| `outputGen` | 실행 · 연습 · 게이트 | 생성자 | |
| `sendQueue` | 연습 · 전이 | 생성자 | |
| `pendingCommands` | 입구 · 메시지 · 끝내기 · 전이 | 메시지 · 전이(`remember`) | |
| `devicePlaying` | 연습 · 공백 · 끝내기 | 끝내기 | |
| `openGapId` | 입구 · 공백 · 끝내기 | 공백 · 끝내기 | 두 협력 객체(`IdleClock` · `EndingFlow`)가 같이 씀 |
| `joinPromptPlaybackId` | 메시지 · 참여 · 판정 · 끝내기 · 시계 | 메시지 · 참여 · 판정 · 끝내기 · 시계 | `JoinFlow` · `EndingFlow` · `SessionClocks`가 같이 씀 |
| `stopConfirmPlaybackId` | 연습 · 끝내기 · 시계 | 연습 · 끝내기 · 시계 | |
| `stopConfirm`(StopConfirmFlow) | 연습 · 공백 · 끝내기 | 생성자 | `IdleClock`의 정지 확인 만기와 연습 구역이 같이 씀 |
| `closingReason` · `closingDetail` | 정산 · 끝내기 · 시계 · 전이 | 끝내기 · 전이 | |
| `closingAt` | 끝내기 · 시계 | 끝내기 | |
| `consentWithdrawnAt` | 메시지 · 연습 · 끝내기 | 끝내기 | |
| `recording` | 메시지 · 게이트 · 끝내기 · 전이 | 메시지 · 끝내기 | |
| `startOpId` · `startAttemptAt` · `startSent` · `ackReceived` · `deviceReady` · `deviceUnavailable` | 메시지 · 공백 · 끝내기 · 시계 · 전이 | 메시지 · 전이(`ackReceived`) | 시작 확인 진행 상태. 시계 구역(`SessionClocks`)이 읽기만 함 |
| `providerStarted` · `providerSessionId` · `providerSession` · `providerAttempt` | 메시지 · 판정 · 연습 · 전이 | 메시지 · 연습 | 공급자 연결 상태 |
| `providerClosing` | 연습 · 전이 | 전이 | |
| `providerClosedSignal` | 정산 · 연습 | 연습 | 정산 창에서 읽음 |
| `settling` · `sessionEndedAt` | 정산 · 연습 | 정산 | |
| `START_PHASE` 상수 | 메시지 · 연습 · 끝내기 · 시계 | — | |

**② `EndingFlow`(끝내기 3335~3838)가 받을 것**: `lastMic` · `lastPlayer` · `progressPlaybackId` · `progressPlayedToMs`(읽기 · 쓰기 모두 끝내기 구역뿐), 상수 `EVENT_LINK_REPLACED` · `EVENT_BARGE_IN` · `EVENT_MIC_LOST` · `EVENT_MIC_OK` · `EVENT_PLAYER_ERROR`(끝내기 구역에서만 사용, 외부 참조는 시험 1파일).

**③ `IdleClock`(공백 시계 2809~3060)이 받을 것**: `silence`(SilenceClock) · `technicalPause` · `pauseStartedAt` · `live`(AtomicReference) · `liveState()` · `LiveState` 레코드, 상수 `EVENT_SILENCE_WARNED` · `EVENT_SILENCE_RESET` · `EVENT_TECHNICAL_PAUSE_STARTED` · `EVENT_TECHNICAL_PAUSE_ENDED`(공백 구역에서만 사용). `technicalPause`는 참여 · 판정 · 연습 · 끝내기도 읽으므로 접근자가 필요하다.

**④ `JoinFlow`(참여 확인 1017~1094 + 참여 결과)가 받을 것**: `join`(JoinTracker; 쓰기는 메시지 구역 `enterWaitingChild` 1곳(986~998줄, 993줄에서 대입), 읽기는 참여 · 판정(J1/수락/거절) · 공백), 상수 `EVENT_JOIN_PROMPTED`(메시지 구역 994줄에서 사용) · `EVENT_JOIN_RE_PROMPTED` · `EVENT_JOIN_ALTERNATIVE_OFFERED` · `EVENT_JOIN_ACCEPTED` · `EVENT_JOIN_DECLINED`(판정 구역 `applyJoinAction`에서 사용). `join`의 쓰기가 메시지 구역(실행기에 남음)에 있으므로 `JoinFlow`에 진입 메서드(`enterWaitingChild`)도 함께 주거나 `SessionContext`를 거쳐 설정해야 한다.

**⑤ 남는 실행기에 남을 것**: 아래 필드는 실행기에 남는 구역(메시지 · 연습 · 게이트 · 재전달)에서만 쓰거나, 협력 객체 참조라서 실행기가 쥔다.

| 필드 | 읽는 구역 | 쓰는 구역 | 비고 |
|---|---|---|---|
| `thread` · `processing`(volatile) | 실행 · 입구 · 메시지 | 실행 | 실행기 스레드 |
| `onTerminated` | 머리 · 전이 | 생성자 | 종료 콜백 |
| `judgment`(JudgmentCoordinator) · `guardrail`(GuardrailCoordinator) | 메시지 · 참여 · 판정 · 연습 · 공백 · 게이트 · 시계 · 전이 | 생성자 | 협력 객체 참조. `JoinFlow`가 `judgment`를 받는다 |
| `stopJudge` · `supersedeVerdict` | 연습 | 연습 | |
| `practice` | 판정 · 연습 · 공백 · 게이트 | 생성자 · 연습 | 연습 확장점 |
| `practiceFailed` · `practiceHold` · `practiceTimers` · `practiceTimerGeneration` · `practicePlaybacks` · `practiceAudio` · `pendingPracticeSpeech`(volatile) · `pausedPracticeEvents` · `practicePauseStartedAt` · `practicePauses` · `practiceEventsInSettle` · `practiceOutput` · `practiceThinking` | 연습(+게이트 · 정산 · 끝내기 · 공백에서 일부 읽음) | 연습(+게이트가 `practiceHold` · `pendingPracticeSpeech` · `practiceAudio` 씀, 끝내기가 `practicePlaybacks` · `practiceAudio` 씀) | 연습 구역이 실행기에 남는 동안 같이 남는다. 게이트 · 끝내기가 쓰는 곳은 접근자가 필요하다 |
| `inputGroup` · `inputGroupId` · `inputGroupSeq` · `inputGroupOpens` · `currentTranscriptSeq` | 판정(`inputGroupId`) · 연습 | 연습 | 입력 묶음 |
| `instructionSeq` · `instructionNotHeard` · `correctionsInARow` · `lastHeld` · `pendingOutputChunks` | 연습 · 게이트 · 메시지 | 연습 · 게이트 · 메시지 | 출력 보류 · 교정 |
| `pendingRetry` | 재전달 | 재전달 | |
| `droppedAfterConsent` · `droppedLate` · `missingReported` | 연습 | 연습 | |
| 상수 `MAX_TRACKED`(사용처 없음) · `MAX_PERCENT` · `MAX_CORRECTIONS_IN_A_ROW` · `NOTICE_SCENARIO_ID` · `EVENT_CHILD_STOP_*` · `EVENT_APPROVED_CONTENT_MISSING` · `EVENT_STOP_CONFIRM_*` · `EVENT_PRACTICE_EXTENSION_FAILED` · `END_DETAIL_PROVIDER_LOST` · `MAX_PENDING_COMMANDS` | 연습 · 게이트 · 머리(`Settings`) · 전이 | — | `MAX_PENDING_COMMANDS`는 전이 보조(`remember`)에서만 쓰므로 `SessionContext`와 함께 갈 수 있다. `EVENT_*` 상수는 시험이 `SessionExecutor.EVENT_*`로 참조한다(1 ~ 6파일) |

### 3.4 전이 보조 4080~4361(282줄) 메서드 소유자

| 메서드(시작 줄) | 줄 수 | 쓰는 필드 | 소유자 |
|---|---|---|---|
| `transition` 4082 · `multiOriginFail` 4091 | 22 | `status` · 전이 서비스 | `SessionContext` |
| `afterFailed` 4104 · `afterFailed(reason)` 4112 | 19 | `link` · `closingReason` · 기기 명령 | `EndingFlow` |
| `syncFromDb` 4123 · `syncFromDb(UUID)` 4133 · `adopt` 4155 · `annotateQuietly` 4181 | 66 | `status` · `closingReason` · `closingDetail` · `ackReceived` | `SessionContext` |
| `markUnresponsive` 4189 · `fromBoundConnection` 4196 | 12 | 연결 | `SessionContext` |
| `sendControl` 4201 · `remember` 4222 · `putEvent` 4231 | 34 | `link` · `pendingCommands` | `SessionContext` |
| `closingDevice` 4235 | 24 | `link` · `status` | `EndingFlow` |
| `bumpGeneration` 4259 · `changeStatus` 4271 | 17 | `outputGeneration` · `status` · `view` | `SessionContext` |
| `closeProvider` 4276 · `teardown` 4293 · `unbind` 4342 · `quietly` 4349 | 82 | `providerSession` · `terminated` · `recording` · `link` | `EndingFlow` |
| `toString` 4358 | 4 | — | 남는 실행기 |

`SessionContext`가 받는 전이 보조는 약 151줄, `EndingFlow`가 받는 것은 약 125줄, 남는 것은 `toString`과 닫는 괄호 약 5줄이다.

### 3.5 AC-4 "3.5 뒤 줄 수 상한" 숫자 제안 (설계 문서는 고치지 않는다)

`functional-spec.md` 5절 AC-4의 3.5 뒤 상한 칸에 쓸 숫자 제안이다. 설계 문서는 이 단계에서 직접 고치지 않았다.

계산(모두 위 표에서 센 값):

| 항목 | 줄 |
|---|---|
| 시작(분리 전) | 4,361 |
| 3.1 `EndingFlow` 끝내기 구역 | −504 |
| 3.2 `SessionClocks` 시계 구역 | −184 |
| 3.3 `IdleClock` 공백 시계 구역 | −252 |
| 3.4 참여 확인 −78, 판정 턴 구역 −260 (구역 전체가 실행기를 떠난다고 볼 때) | −338 |
| 설계 문서의 3.4 뒤 값 (머리 · 전이 보조는 그대로라고 가정) | **3,083** |
| 머리에서 협력 객체 · `SessionContext`로 옮겨 가는 필드 · 상수 선언(공유 상태 약 56 + `EndingFlow` 10 + `IdleClock` 13 + `JoinFlow` 8) | −87 |
| 전이 보조에서 `SessionContext` · `EndingFlow`로 가는 메서드(`toString` 제외) | −277 |
| 3.5 뒤 예상 | **약 2,719** |
| 호출 치환 · 필드 선언(`SessionContext` · 협력 객체 참조) 여유 | +약 80 |

- **제안: AC-4의 3.5 뒤 상한 = 2,800줄 이하.** 3.4 뒤 상한 3,100줄은 그대로 둔다(예상 3,083은 필드 87줄이 3.0에서 먼저 빠져 실제로는 약 2,996이 된다).
- 실행기에 남는 구역(실행 75 + 입구 86 + 메시지 236 + 연습 1,454 + 게이트 274 + 재전달 57 = 2,182줄)과 남는 머리(import · 클래스 설명 · `Settings` · `Services` · `Plan` · 남는 필드 · 생성자 약 532줄)가 하한이다. 연습 확장점(1,454줄)과 게이트(274줄)가 남는 한 2,719줄 밑으로는 내려가지 않는다. "분배만 하는 실행기"는 이번 합격 기준이 아니다(BR3.3).
- 중첩 공개 타입(`Settings` `Services` `Plan` `ApprovedItem`, 약 275줄)을 별도 파일로 빼면 상한을 2,450줄 안팎으로 더 낮출 수 있으나 외부 참조 운영 8파일 · 시험 약 10파일을 고쳐야 해서 제안하지 않는다.
- 위 숫자는 구역 줄 수와 필드 선언 줄을 센 것이고, 구역 사이 공유 헬퍼 메서드가 있으면 달라질 수 있다. 3.5를 닫을 때 `wc -l`로 실제 값을 확인해 상한을 다시 맞춘다.

## 4. 합격 기준 AC-1 ~ AC-9의 시작 전 값 (Step 4-c)

`functional-spec.md` 5절 명령 그대로 이 체크아웃에서 돌린 값이다.

| ID | 명령 | 시작 전 값 (실측) | 기대값 |
|---|---|---|---|
| AC-1 | `git ls-files frontend/src \| grep -E '[A-Za-z][0-9]{2}[a-z]?\.tsx$\|/D0View\|/T19View' \| wc -l` | **17** (`parent/screens` 15개 + `D0View` · `T19View`) | 0 |
| AC-1 | 유닛 번호 소스 이름 `(^\|/)u[0-9]+[ab]?[A-Z]\|[a-z]u[0-9]+[ab]?[A-Z]` | **1** (`parent/practice/u5bCopy.ts`) | 0 |
| AC-1 | `parent/screens` 폴더 | **있음**(파일 17개) | 없음 |
| AC-2 ① | 경로 · 파일 이름(Flyway 제외) | **230** | 0 |
| AC-2 ② | `grep -rlE '@Tag\("u[0-9]' backend --include=*.java \| wc -l` | **450** | 0 |
| AC-2 ③ | `grep -cE 'u[0-9]+[ab]?(-core\|-scope)?\b' .github/workflows/ci.yml` | **17** | 0 |
| AC-4 | `wc -l SessionExecutor.java` | **4,361줄** | 3.4 뒤 3,100 이하 · 3.5 뒤 제안 2,800 이하 |
| AC-4 | 구역 표식 `// -+ .*(끝내기\|시계\|참여 확인\|판정 턴)` | **5개**(1017 · 1095 · 2809 · 3335 · 3839줄) | 분리한 구역 0개 |
| AC-5 | 바꾼 쪽 검사 + `verify.sh` | 1절 결과 전부 통과(백엔드 3937 · 프런트 74/994 · tools 12/133) | 통과, 시험 수 같음 |
| AC-6 | ESLint 경계 · ArchUnit | `verify.sh` A1(ArchUnit 포함) · A2(`eslint .`) 통과 | 통과, 규칙이 비지 않음 |
| AC-7 | 결정 ID 주석(아래 4.2) | 줄 **2,463**, 출현 **3,743** | 단계 6에서 감소 |
| AC-8 | `vitest run tests/u5b` | **3개 파일 · 32건** 통과 | 같은 수 |
| AC-8 | `vitest run tests/u7` | **6개 파일 · 89건** 통과 | 같은 수 |
| AC-8 | `coverage-scope/<이름>.tests` | **없음**(Step 20이 만든다) | 비어 있지 않고 없는 경로 없음 |
| AC-9 | `hashFiles` 조건 단계 | 해당 없음(옛 이름에서 실행됨, `gh run view`는 이름 변경 뒤 첫 CI에서만 쓴다) | `success` |

- AC-8 파일별 시험 수: `tests/u5b`의 `practice-progress` 8 · `practice-screens` 16 · `practice-start` 8, `tests/u7/parent`의 `browser-push` 9 · `notification-entry` 32 · `push-api` 8 · `push-settings` 17 · `push-worker` 18 · `settings-account` 5.

### 4.1 지정 화면 9개의 호출 수 (AC-3 시작 전 값)

줄 수 기준(`grep -c`, 한 줄에 여러 개여도 1)이다. "AC-3 명령"은 5절 명령(`router\.|useRouter|catch|await |/api"`), "옛 패턴"은 5절 표의 `router.` `useRouter` `fetch` `endSession` `catch` 합이다. 옛 패턴 값은 5절 표(Live12 21 · Home04 16 · Done17 13 · Consent02 12 · Device05 9 · Child03 8 · StopExpressions01 8 · Register05a 6 · Free08 5)와 모두 같다.

| 화면 | 줄 수 | `router.` | `useRouter` | `catch` | `await` | AC-3 명령 | 옛 패턴 |
|---|---|---|---|---|---|---|---|
| `Live12` | 274 | 9 | 2 | 2 | 3 | 16 | 21 |
| `Home04` | 237 | 10 | 2 | 0 | 0 | 12 | 16 |
| `Done17` | 138 | 6 | 2 | 0 | 0 | 8 | 13 |
| `Device05` | 239 | 4 | 2 | 0 | 0 | 6 | 9 |
| `Child03` | 292 | 2 | 2 | 1 | 4 | 9 | 8 |
| `Consent02` | 211 | 2 | 2 | 3 | 3 | 9 | 12 |
| `StopExpressions01` | 256 | 2 | 2 | 1 | 2 | 7 | 8 |
| `Free08` | 222 | 2 | 2 | 1 | 1 | 6 | 5 |
| `Register05a` | 150 | 3 | 2 | 1 | 2 | 8 | 6 |

- `useRouter`가 2인 것은 import 한 줄과 호출 한 줄이다. `/api"` import는 9개 화면 모두 0건이다(화면이 요청 모듈을 `@/parent/api/...`로 부르지 않는 줄은 이 패턴에 걸리지 않으므로, Step 9 이후 AC-3은 이 패턴과 함께 `fetch*` · `endSession` 같은 호출 이름도 본다).

### 4.2 결정 ID 주석 수 (AC-7 시작 전 값)

정규식 `\b(J[0-9]+|BR[0-9.]+|FR[0-9.]+|NFR[0-9.]+|R-[0-9]+|U[0-9]+[a-z]?-[A-Za-z0-9]+)\b`, 이진 파일 제외(`grep -I`), 건드릴 파일 범위 `frontend/src` + `backend/*/src/main`.

| 범위 | 파일 수 | 걸린 파일 | 걸린 줄 | 출현 수 |
|---|---|---|---|---|
| `frontend/src` | 134 | 89 | 246 | 378 |
| `backend/*/src/main` | 726 | 535 | 2,217 | 3,365 |
| **합계** | 860 | 624 | **2,463** | **3,743** |

- 하위 범위(줄 수): `frontend/src/parent` 87 · `device` 83 · `shared` 61 · `app` 14, `backend/realtime-activity/src/main` 1,326 · `core-api` 761 · `contract-model` 85, `realtime/session` 패키지 469(그중 `SessionExecutor.java` 214줄).
- 화면별(`parent/screens`, 줄): `Free08` 11 · `Live12` 7 · `Home04` 6 · `Done17` 4 · `Register05a` 4 · `Child03` 1 · `Consent02` 1 · `DevLogin` 1 · `Login01` 1 · `LoginCallback` 1 · `StopExpressions01` 1, 나머지 0.
- AC-7은 단계 6에서 **건드린 파일만** 기준선과 비교한다. 단계 6이 시작될 때 건드린 파일 목록에 이 정규식을 다시 돌려 기준선(위 값을 파일별로 나눈 값)과 대조한다.

## 4.3 사람이 승인한 결정 (2026-10-18, Step 1 ~ 4 보고에 대한 답)

- **AC-4 상한**: 3.5 뒤 `SessionExecutor`는 **2,800줄 이하**(3.5절 제안 그대로 수용). 설계 `functional-spec.md` 5절 AC-4의 해당 칸은 설계 문서를 직접 고치지 않고 이 결정을 단계 3의 판정 기준으로 쓴다. 연습 확장점(1,454줄)과 게이트(274줄)가 실행기에 남는 한 약 2,719줄 밑으로는 내려가지 않는다.
- **"판정 턴" 구역 나머지 194줄의 목적지**(2.1절): 참여 수락 · 거절(`acceptJoin` `declineJoin` 등)은 **`JoinFlow`**, 마무리 인사(`startFarewell` 등)와 종료 뒤 정산(`afterTerminal` `finishSettle` 등)은 **`EndingFlow`**로 보낸다. 실제 판정(J1) 66줄만 `JudgmentCoordinator`로 흡수한다. 실행기에서 빠지는 줄 수는 같다.
- **Step 5 ~ 8(프런트 이동) 시작을 승인**했다.

## 5. 이 문서가 다른 Step에 넘기는 것

- Step 5 ~ 8: `reference-checklist.md`의 프런트 시험 목록(실측 50파일)과 `src/app` import 20곳.
- Step 12: 3.3 · 3.4 표(`SessionContext`가 받을 필드와 전이 보조 메서드).
- Step 16: 2.1의 "판정 턴 구역 실제 내용"(목적지 확인 필요).
- Step 14 · 17: 2.2의 약한 가지(시계 지연 재무장 · 연습 출력 취소)에 특성화 시험을 더할지 결정.
- Step 17: 3.5의 상한 제안과 `functional-spec.md` 5절 AC-4 반영(사람 승인).

- 메모(계획 외 추가, 사람 승인): Step 11e 기능끼리 import 정리. 두 기능 이상이 쓰는 파일 10건을 `parent/common/`으로 옮겼다. 내용은 `cross-feature-imports.md`. 계획 파일은 건드리지 않았다.
