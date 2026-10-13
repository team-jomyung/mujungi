# U4b 활동 세션 Realtime — 결과 보고 (2026-10-13)

> 지시서 `brief.md`, 설계 `design.md`. AI-DLC 작업 기록 `aidlc/spaces/default/intents/261013-u4b-activity-realtime/`(Code Generation 요약 `construction/code-generation/code-summary.md`).
> 아직 푸시하지 않았고 PR도 열지 않았다(13절 남은 일).

## 1. 결과

- 층 L1 ~ L7과 연습 확장점을 구현하고, 층마다 시험을 돌린 뒤 `feat(u4b): …`로 커밋했다.
- u4b 범위 시험 194건이 모두 통과했다. 범위 줄 커버리지는 80.75%로 하한 80%를 넘는다. realtime 모듈 전체 `check`는 1,020건이 통과했고 모듈 줄 커버리지는 92.8%다.
- U1 · U3 회귀는 기준선과 같은 건수로 통과했다. 다만 U1 지표 시험 1건이 한 번 시간 흔들림으로 실패했다(4절). 어떤 커버리지 하한이나 단언도 낮추지 않았다.
- 바꾼 곳은 realtime 스키마 V3(더하기만), realtime 운영 · 시험 코드, `contracts/`(timing · deviations · ai-io · internal-api · events), `tools/`, `scripts/verify`, `infra/compose.ci.yml`이다. core-api는 시험 파일 `FlywayConfIT` · `SchemaPrivilegeIT`의 숫자만 바꿨다. core 스키마 · core-api 운영 코드 · `/parent` · `/device` · `README.md` · `contracts/README.md`는 고치지 않았다.

| 커밋 | 내용 |
|---|---|
| e23c042 | L1 스키마 · 설정: realtime V3 결과 스냅숏 표 · 시간 키 8개 · JoinTracker 되살림 |
| bd2373c | 작업 기록 · L2 착수분(전이표 · 실행기 일부) |
| 8812555 | L2 전이표 · 끝내기: 사건이 정하는 사유 · 끝내기 응답표 · 동의 철회 |
| 64fd37a | L3 참여 확인: WAITING_CHILD · 시작 안내 · 참여 창 · DECLINED 인사 |
| 63ecbaa | L4 종료 의도 · 공백 · 끊김 · 진행 조회: 확인 질문 · 공백 시계 · 기술 일시정지 |
| 44662e0 | L5 정산 · 결과 스냅숏: 정산 창 · ResultSnapshotBuilder · SessionRecordCaptured |
| 42fcd7a | 연습 확장점: PracticeSessionExtension · 출력 능력 · 기본 무동작 |
| 4291be4 | L6 계정 삭제: 강제 정지 · 순서 값 참가자 · DeletionCompleted counts · 발행 뒤 정리 |
| 92035a1 | 흔들리는 시험: RealtimeInternalApiIT Core 취소 시험의 CLOSING 행을 단언 직전에 만든다 |
| 8de76ef | 개인정보 검사 시험: 참여 · 확인 질문 · 공백 · 정산 · 삭제 경로 |
| 1218647 | 검증 스크립트 시드 003 · 끝-끝 C 참여 응답 단계 |
| 59869d1 | 계약 문서 · 편차 (U4b-a) ~ (U4b-d) |
| d90ecaa | 범위 파일 마무리 · 내부 세션 API U4b 시험 |
| 760ad5d | 설계 문서 갱신: J24 · 확장점 이름과 능력 · FD-Q3 ~ Q6 · R-03 · R-10 ~ R-12 가정 |

## 2. 만든 것

| 층 | 새 운영 파일 | 고친 운영 파일 |
|---|---|---|
| L1 스키마 · 설정 | `V3__u4b_session_lifecycle.sql` · `ResultInputSnapshotRepository` · `JoinTracker`(5354a32에서 되살림) | `RealtimeProperties` · `application.yml` · `contracts/timing.md` |
| L2 전이표 · 끝내기 | — | `TransitionTable` · `TransitionService` · `EndRequestHandler` · `SessionStateEmitter` · `SessionTransitions` · `SessionChange` · `ClosingSequence` |
| L3 참여 확인 | `PracticeInstruction` | `SessionExecutor` · `SessionMailbox` · `StartSessionHandler` · `ProviderRequestFactory` · `ProviderPort` · `ProviderClient` · `DialogInstructions` · `TranscriptWriter` · `TranscriptRepository` |
| L4 종료 의도 · 공백 · 끊김 · 조회 | `StopConfirmFlow` · `SilenceClock` | `SessionExecutor` · `SessionTimers` · `SessionViewQuery` · `SessionInternalController` |
| L5 정산 · 스냅숏 | `ResultSnapshotBuilder` · `SessionSettlement` · `RecordCaptureService` | `SessionExecutor` · `OrphanSessionChecker` · `AsyncRecorder` · `RealtimeRuntimeConfig` |
| 연습 확장점 | `PracticeSessionExtension` · `NoOpPracticeSessionExtension` | `SessionExecutor` · `RealtimeRuntimeConfig` |
| L6 계정 삭제 | `DeletionRequestedHandler` · `RealtimeSessionDeletionParticipant` · `RealtimeDeletionParticipant` · `OutboxPublishedListener` | `RealtimeVoiceDeletionParticipant` · `RealtimeMessagingDeletionParticipant` · `RealtimeOutboxSender` · `SessionExecutor` · `SessionMailbox` |
| 도구 · 검증 | `tools/tests/u4b/fake-provider-u4b.test.mjs` | `tools/fake-provider/{scenarios,server}.mjs` · `tools/skeleton-e2e/steps.mjs` · `scripts/verify/b-{stack,host}.sh` · `infra/compose.ci.yml` |
| 계약 · 문서 | — | `contracts/{deviations,ai-io}.md` · `contracts/{internal-api,events.asyncapi}.yaml` · `docs/units/u4b/design.md` |

새 시험 클래스(모두 `@Tag("u4b")`, DB 시험은 `db`도): TransitionTableU4bTest · EndRequestU4bTest · SessionStateEmitterU4bTest · JoinTrackerU4bTest · SessionJoinU4bTest · SessionLifecycleU4bIT · StopConfirmFlowTest · SilenceClockTest · SessionStopU4bTest · SessionSilenceU4bTest · TechnicalPauseU4bTest · SessionViewU4bIT · ResultInputSnapshotRepositoryIT · ResultSnapshotBuilderTest · SessionSettleU4bTest · SessionRecordU4bIT · PracticeExtensionU4bTest · RealtimeDeletionParticipantOrderTest · RealtimeDeletionHandlerIT · PrivacyLogU4bTest · SessionInternalControllerU4bTest. contract-model `SnapshotHashGoldenTest`에 null 키를 남기는 해시 시험 하나를 더했다.

## 3. 시험 결과

DB 시험은 Docker 대신 로컬 PostgreSQL 16(`-Pmujoong.test.db=local`)으로 돌렸다.

| 항목 | 기준선(L1, e23c042) | 지금 |
|---|---|---|
| u4b 범위 시험 · 줄 커버리지 | 6건 · 15.9% | 194건 통과, 줄 80.75%(분기 60.9%), 범위 운영 파일 42개, 검증 규칙 통과 |
| realtime U1 회귀 | 544건 통과 | 544건 통과(4절의 1회 실패 제외) |
| realtime U3 회귀 | 306건 통과 | 306건 통과 |
| realtime 모듈 `check` | — | 1,020건 통과, 모듈 줄 커버리지 92.8%, 검증 규칙 통과 |
| contract-model U1 | 292건 | 292건 통과, ContractFilesTest 14건 통과 |
| core-api U1 | 622건 | 622건 통과 |
| test-support U1 | 27건(건너뜀 3) | 27건 통과(건너뜀 3) |
| 흔들리던 시험(FR11.2) | 부하에서 409 | `--rerun-tasks` 5회 통과 |
| tools vitest | verify-exit 7건 실패(환경) | 118건 통과, 같은 7건 실패(환경, U4b와 무관) |
| 끝-끝 `scripts/verify.sh` | — | Docker가 없어 돌리지 않음(13절) |

## 4. 흔들린 시험

| 시험 | 내용 | 조치 |
|---|---|---|
| `RealtimeInternalApiIT` Core 취소 시험 | 실행기 없는 CLOSING 행을 일찍 만들면 부하에서 실행기 점검과 겹쳐 409가 났다 | 행 생성 순서만 바꿔 단언 바로 앞에서 만든다. 단언 줄은 그대로다(FR11.2). `--rerun-tasks` 5회 통과 |
| `RealtimeMetricsScrapeIT.outboxAndInboxRows…`(U1) | 최종 U1 회귀 한 번에서 가장 오래된 PENDING 나이가 117초로 120초 기대에 못 미쳐 실패 | 같은 클래스 `--rerun-tasks` 3회와 모듈 check에서는 통과했다. U4b가 고치지 않은 U1 지표 경로의 시간 흔들림으로 보고 고치지 않았다. CI에서 지켜본다 |
| `DeviceGatewayU3IT.aReconnectWithResume…`(U3, U4b의 `join` 경유) | PR #6 첫 CI(868ffab)에서 세션이 DEVICE_ACK에 머문 채 10초 안에 WAITING_CHILD가 되지 않아 실패했다. 그 10초 동안 공급자 실패 · 상태 전이 기록이 없고 CPU는 거의 쉬었다 | 로컬 재현 실패: 클래스 단독 3회, 4코어 모듈 전체 3회, 2코어 게이트웨이 시험 6회 모두 이 시험은 통과했다(4코어 1회는 위 지표 시험이, 2코어 1회는 U1 `DeviceGatewayIT` 1013 시험이 흔들렸다). 원인을 확정하지 못해 제품 코드는 바꾸지 않았다. 대신 `RealtimeItEnvironment.join`이 시한을 넘기면 세션 상태 · 가짜 공급자가 받은 type · 앱 스레드(가상 스레드 포함) 스택을 표준 출력에 남긴다. 다시 실패하면 CI `backend-reports`의 이 시험 보고서에서 실행기가 어디서 멈췄는지 본다 |

## 5. 계약 편차(`contracts/deviations.md`)

| 기호 | 내용 | 표시 위치 |
|---|---|---|
| (U4b-a) | 서버 → 공급자 `session.response.create`: ACTIVE 진입 때 한 번, 확인 질문 해제 때 다시 | ai-io.md |
| (U4b-b) | 끝내기 응답표: 사유 3 × 상태 5. 시작 단계 동의 철회는 202 {} + FAILED(사유 CONSENT_WITHDRAWN), WAITING_CHILD · ACTIVE는 202 {CLOSING}, 반복 요청은 멱등 | internal-api.yaml |
| (U4b-c) | CLOSING 상태 알림의 endReason, SessionRecordCaptured record 모양 | events.asyncapi.yaml |
| (U4b-d) | record.practice는 객체 또는 null(utterances · audio만 빈 배열) | events.asyncapi.yaml |

편차가 아닌 설명 보강: 동의 철회로 시작 단계가 끝나면 failureCode 열거에 값이 없어 사유(endReason) CONSENT_WITHDRAWN으로 적었다. 연습 마지막 목표 끝의 PRACTICE_COMPLETED는 세션 행 end_detail에만 남고 이벤트에는 실리지 않는다(U4b-J30).

## 6. 가정과 설계에서 달라진 곳

`design.md` 8절의 U4b-J24 · J27 ~ J34에 적었다.

- **U4b-J24**: U3 PR이 합쳐졌으므로 main에서 시작한 브랜치에서 층마다 커밋하고 PR로 합친다.
- **U4b-J27(FD-Q3)**: STOP은 곧바로 CLOSING(CHILD_STOP)이다. U3의 '출력만 막고 유지 → 해제' 경로를 없앴다. CONFIRM은 StopConfirmFlow 확인 질문으로 처리한다.
- **U4b-J28(FD-Q4)**: record.practice는 객체 또는 null이다.
- **U4b-J29(FD-Q5)**: 삭제 참가자 공통 인터페이스와 순서 값(음성 100 · 연습 150 자리 · 세션 200 · 메시징 900).
- **U4b-J30(FD-Q6)**: 연습 마지막 목표 끝은 CHILD_STOP과 end_detail PRACTICE_COMPLETED.
- **U4b-J31(R-03)**: SessionRecordCaptured의 필수 칸(guardianId · childId · termination)을 세션 행에서 채운다.
- **U4b-J32(R-10)**: 확장점 출력 능력은 ACTIVE에서만 받고, 그 밖에는 거절하고 PRACTICE_EXTENSION_FAILED{reason: NOT_ACTIVE}를 남긴다.
- **U4b-J33(R-11)**: 안내 재생 중 온 아이 확정 전사도 JoinTracker `reply()`로 곧바로 분류한다.
- **U4b-J34(R-12, 계획과 다름)**: 모든 DB 삭제가 Inbox 처리 한 트랜잭션 안이라 실패한 시도는 남는 것이 없다. 그래서 앞 시도의 건수를 누적하지 않고 재시도가 처음부터 다시 센다. audioFiles는 원음 메타 행 수로 센다.

그 밖에 구현하면서 정한 것:

- **정산**: 종료 뒤 정산 창(공급자 닫힘 알림 또는 record-settle)이 끝나면 스냅숏과 SessionRecordCaptured를 한 트랜잭션에 쓴다. 놓친 스냅숏은 고아 점검이 다시 쓴다.
- **스냅숏 해시**: null 값은 키를 남기고 JSON null로 쓴다(골든 시험 두 개로 고정).
- **연습 공급자 지시**: 새 type 없이 `session.instructions.correct`를 검증 문장(`PracticeInstruction`)으로 다시 쓴다. 그래서 `ProviderRequestWhitelistIT` 허용 목록을 넓혔다.
- **발행 뒤 메시징 정리(BR9.5)**: 그 요청의 DeletionRequested Inbox 행이 있을 때만 정리한다. 요청 없이 넣은 U1 시험용 완료 행이 발행되며 다른 Outbox 행을 지워 `RealtimeStartupRecoveryIT`가 실패한 것을 보고 좁혔다.
- **끝-끝 C 기대값**: 참여 응답 턴이 앞에 붙어 정상 · 금지 턴 번호가 1 · 2에서 2 · 3이 됐다. 금지 턴 뒤 AI 말 수는 시작 안내를 빼고 센다. 가짜 공급자 턴 계획은 검증 스택만 `join,normal,forbidden`으로 바꾸고 `Dockerfile` 기본값은 그대로 뒀다.
- **범위 파일**: 층마다 고친 U1 · U3 운영 파일 9개와 삭제 인터페이스 2개를 더하자 범위 커버리지가 79.23%로 내려갔다. 하한은 낮추지 않고 `SessionInternalControllerU4bTest`(7건)를 더해 80.75%로 올렸다.

## 7. FR11.1 · FR11.3에서 고친 기존 시험 기대값

단언은 줄이지 않았고 숫자 · 값 · 활성화 경로만 바꿨다.

- **L1**: 스키마 판 2 → 3(FlywayConfIT · RealtimeSchemaIT · SchemaPrivilegeIT · RealtimeDb), 시간 키 목록 · 시험 프로필 키.
- **L2**: TransitionTableTest(ENDED 기본 사유 → null, GUARDIAN_STOP multiOrigin → true). SessionExecutorTest · RealtimeInternalApiIT의 거절 입력을 CONSENT_WITHDRAWN → UNRECOGNIZED로 바꿨다(단언은 그대로, 메서드 이름만 바뀜).
- **L3**: TransitionTableTest(DEVICE_ACK → WAITING_CHILD, READY), SessionExecutorTest · SessionExecutorFaultTest 활성화 기대 ACTIVE → WAITING_CHILD, SessionExecutorU3Test `h.join()`, 통합 시험 11곳에서 deviceReady 뒤 `env.join`, ProviderRequestWhitelistIT(response.create · ResponseCreate · PracticeInstruction), RealtimeArchTest(ResponseCreate), SessionLifecycleIT AI 행 2 → 3.
- **L4(FD-Q3)**: SessionExecutorReviewU3Test · Review2U3Test(2) · Review3U3Test · SessionVoiceU3Test(2)의 해제 경로 기대 → CLOSING(CHILD_STOP) · 확인 질문 흐름. SessionHarness `advanceAlive` · `activate()` 참여 응답 · Settings Lifecycle 인자.
- **FR11.2**: RealtimeInternalApiIT Core 취소 시험의 CLOSING 행 생성 순서만.

## 8. 남은 일

- **브랜치 · PR**: 커밋이 로컬 `master`에 있다. U4b-J24대로 main에서 `unit/u4b-activity-realtime` 브랜치로 옮긴 뒤 PR을 열고, CI가 초록이면 합친다.
- **검증 스크립트**: Docker가 있는 리눅스(또는 PR CI)에서 `bash scripts/verify.sh`를 돌려 B2.seed003과 C8 참여 응답 단계를 확인한다. infra-validate · 이미지 관문 · 스켈레톤 검사도 같은 곳에서 확인한다.
- **지켜볼 것**
  - `RealtimeMetricsScrapeIT`의 시간 흔들림(4절)
  - 메시징 정리 뒤 같은 삭제 요청이 새 eventId로 다시 오면 DeletionCompleted가 두 번 나갈 수 있다
  - u4b 범위 분기 커버리지 60.9%
- **다른 유닛**
  - U5b: PRACTICE 시작 열기, 연습 삭제 참가자(순서 150), 확장점 구현
  - 실공급자 어댑터: `session.response.create` 형식 맞추기
  - U4a(Core): CLOSING endReason과 SessionRecordCaptured 소비, CONSENT_WITHDRAWN 끝내기 호출
