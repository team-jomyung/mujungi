# 코드 요약 — U1 u1-walking-skeleton

> 3.5 Code Generation Step 5(생성 뒤) 산출물 · 2026-10-10 · 대화 언어 한국어
>
> 짝 파일: `code-generation-plan.md`(승인된 53단계 계획) · `unit-test-instructions.md`(유닛 시험 명령) · `source-manifest.json`(이 유닛이 만들거나
> 고친 애플리케이션 경로 전부) · `traceability.json`(AC 83 · NFRx.y 67 · BR 89). J1 ~ J144는 계획, J145부터는 이 문서가 처음 적는 가정이다.
> 모든 J번호는 확정이 아닌 가정이다(사람 결정 8). 사람이 승인 · 정정한 것만 확정으로 본다.

## 개요

- **결과.** 계획 Step 1 ~ 53을 모두 끝냈다. 백엔드 U1 시험 1,196건(contract-model 276 · core-api 506 · realtime-activity 387 · test-support 27),
  프런트 499건(34파일), tools 105건(8파일)이 실패 0 · 건너뜀 0으로 통과했고, 80% 줄 하한은 세 백엔드 모듈과 프런트 `src/**` 모두에서 넘었다
  (U1 범위 판정 명령 기준, 아래 ## 시험 · 커버리지).
- **검증 명령 C안.** `bash scripts/verify.sh`가 compose 모드로 종료 코드 0(339초), `--mode host`도 종료 코드 0(279초, `NOT_VERIFIED(host)` 5줄 유지),
  엔진용 `bash scripts/verify.sh --engine`이 세 번 모두 종료 코드 0 · 67 ~ 70초였다(사람 결정 10의 110초 안). Step 53을 마친 뒤 전체 실행(343초)과
  `--engine`(70초)을 다시 돌려 둘 다 종료 코드 0이다(지금 소스 지문 기준, ## 검증 명령 결과). 같은 실행에서 교차 스키마 쓰기 거부(B1 ②)와
  로그 위생(B1 ③)을 단언했다. B1 ④(스켈레톤 확인 승인)는 사람 몫이다.
- **코드 위치(J97).** 애플리케이션 코드는 저장소 루트의 `backend/` · `frontend/` · `contracts/` · `infra/` · `tools/` · `scripts/` · `.github/` · `ci/`와
  루트 설정 파일에 있다. 기록 디렉터리에는 계획 · 지시 · 질문과 이 문서 · `source-manifest.json` · `traceability.json`만 있다.
- **방법론.** Testing Contract의 test-after를 따랐다(층마다 구현한 뒤 그 층의 시험을 쓰고 돌림). 하한 · 단언을 낮추거나 끈 곳은 없고 커버리지 제외
  목록은 비어 있다. jsdom에서 실행할 수 없는 줄이 남지 않아 J138 제외 후보도 없다.
- **사람에게 넘길 것.** 엔진 검증 명령의 verification-command 체크포인트 Approve(J134), B1 ④, 지표 틈(OD-5 · OD-6 · OD-12)의 처리 시점, 10/13 팀 결정
  (J98 · J99 · J100) 등은 ## 사람이 정할 것에 모았다.

## 만든 · 고친 파일

경로는 저장소 루트 기준이고 계획 승인 커밋(`8d98e74`) 이후 이 유닛이 만들거나 고친 파일은 763개다. 그중 고친 기존 파일은 `.gitignore`(AI-DLC 블록은 그대로,
비밀 · 산출물 줄만 더함)와 `README.md`('개발 · 검증' 절만 더함) 둘뿐이고 지운 파일은 없다. `source-manifest.json`은 같은 목록을 760개 항목으로 적는다:
생성기가 만든 트리 `backend/gradle/wrapper/`(gradle wrapper) · `infra/seed-audio/`(`tools/seed/gen-approved-wav.mjs`)와, 경로에 `[` `]`가 들어
엔진이 파일 단위 claim을 받지 않는 Next.js 동적 라우트 디렉터리는 `frontend/src/app/parent/sessions/` 접두 claim 하나로 적었다(J282).

| 영역 | 파일 수 | 내용 |
|---|---|---|
| 루트 | 7 | `.editorconfig` · `.gitignore`(고침) · `.gitleaks.toml` · `.nvmrc` · `.prettierignore` · `.prettierrc.json` · `README.md`(고침) |
| `backend/` | 464 | Gradle 멀티 프로젝트(래퍼 8.14.3 · build-logic), 운영 Java 280(contract-model 55 · core-api 104 · realtime-activity 112 · test-support 9), 자원 · 마이그레이션 · Dockerfile, 시험 소스 143파일(시험 클래스 · 지원) + 시험 자원 |
| `frontend/` | 125 | Next.js 16 App Router 한 앱: `src/` 71(ts · tsx 66, css 4, json 1), 시험 `tests/u1/` 44(시험 34 · 지원 9 · setup 1), 설정 · Dockerfile · `coverage-scope/u1.json` 10 |
| `contracts/` | 42 | OpenAPI 3 · AsyncAPI 2 · JSON Schema 31 · errors · timing · ports · ai-io · deviations · README |
| `infra/` | 82 | Compose 3종 · Nginx 템플릿 · PostgreSQL init · migrate · backup · bundle · seed · seed-audio · 배포 · 호스트 · AWS 정의 · `ci/` 점검 도구 · `RUNBOOK.md` |
| `tools/` | 28 | 가짜 공급자 · 기기 시뮬레이터 · C안 시나리오 · dev-edge · WAV 생성기 · 공용 lib, 시험 8파일 |
| `scripts/` | 11 | `verify.sh` · `verify/*`(A · B · C · 사후 단언, `HostMigrate.java`) |
| `.github/` · `ci/` | 4 | `workflows/ci.yml` · `workflows/deploy.yml`, `ci/security-exceptions.yml` · `ci/security-gate.py` |

Step 52 · 53이 이번에 더하거나 채운 것: `README.md`(로컬 기동 · 유닛 시험 · 검증 명령 · 비밀 규칙 하위 절), `contracts/README.md`(구현 대조 절),
`infra/RUNBOOK.md`(17절 검증 스택), `backend/coverage-scope/u1.txt`(운영 Java 280줄) · `frontend/coverage-scope/u1.json`(66파일). 공개 계약 타입 ·
설정 레코드는 이미 모두 Javadoc을 갖고 있어(contract-model 55 · 설정 레코드 두 개와 중첩 레코드 전부) 고치지 않았다.

디렉터리별 전체 목록:

### (루트) (7개)

- 저장소 루트 (7): .editorconfig · .gitignore · .gitleaks.toml · .nvmrc · .prettierignore · .prettierrc.json · README.md

### backend (464개)

- `backend/` (6): .dockerignore · build.gradle.kts · gradle.properties · gradlew · gradlew.bat · settings.gradle.kts
- `backend/build-logic/` (2): build.gradle.kts · settings.gradle.kts
- `backend/build-logic/src/main/kotlin/` (1): mujoong.java-conventions.gradle.kts
- `backend/build-logic/src/main/kotlin/kr/mujoong/buildlogic/` (1): MujoongBuildExtension.kt
- `backend/contract-model/` (1): build.gradle.kts
- `backend/contract-model/src/main/java/kr/mujoong/contract/audio/` (1): AudioFrameCodec.java
- `backend/contract-model/src/main/java/kr/mujoong/contract/error/` (2): ErrorCode.java · ErrorEnvelope.java
- `backend/contract-model/src/main/java/kr/mujoong/contract/event/` (4): AggregateType.java · EventEnvelope.java · EventPayload.java · EventType.java
- `backend/contract-model/src/main/java/kr/mujoong/contract/event/payload/` (6): DeletionCompleted.java · DeletionRequested.java · DeviceReleased.java · DeviceStatusChanged.java · SessionRecordCaptured.java · SessionStateChanged.java
- `backend/contract-model/src/main/java/kr/mujoong/contract/json/` (1): JsonCodec.java
- `backend/contract-model/src/main/java/kr/mujoong/contract/log/` (5): AttrKey.java · EventCode.java · EventLog.java · OwnerHasher.java · StructuredLogFormatter.java
- `backend/contract-model/src/main/java/kr/mujoong/contract/profile/` (1): ProfileGuardRules.java
- `backend/contract-model/src/main/java/kr/mujoong/contract/secret/` (1): SecretCompare.java
- `backend/contract-model/src/main/java/kr/mujoong/contract/status/` (7): ActivityType.java · ConnectionState.java · EndReason.java · EndRequestReason.java · FailureCode.java · RegistrationStatus.java · SessionStatus.java
- `backend/contract-model/src/main/java/kr/mujoong/contract/time/` (4): DelayQueueTimerDriver.java · MujoongClock.java · SystemMujoongClock.java · TimerDriver.java
- `backend/contract-model/src/main/java/kr/mujoong/contract/trace/` (1): TraceIds.java
- `backend/contract-model/src/main/java/kr/mujoong/contract/ws/` (4): CloseCode.java · WsEnvelope.java · WsMessageType.java · WsPayload.java
- `backend/contract-model/src/main/java/kr/mujoong/contract/ws/message/` (18): Ack.java · CloseTransport.java · DeviceReady.java · DeviceState.java · Heartbeat.java · HeartbeatAck.java · Hello.java · HelloOk.java · OutputBegin.java · OutputEnd.java · PlayApprovedContent.java · PlaybackMark.java · SessionState.java · StartSession.java · StopOutput.java · View.java · VoiceSessionClosed.java · WsError.java
- `backend/contract-model/src/test/java/kr/mujoong/contract/` (1): ContractFilesTest.java
- `backend/contract-model/src/test/java/kr/mujoong/contract/arch/` (1): ContractModelArchTest.java
- `backend/contract-model/src/test/java/kr/mujoong/contract/audio/` (1): AudioFrameCodecTest.java
- `backend/contract-model/src/test/java/kr/mujoong/contract/error/` (1): ErrorCodeTest.java
- `backend/contract-model/src/test/java/kr/mujoong/contract/event/` (1): EventEnvelopeValidationTest.java
- `backend/contract-model/src/test/java/kr/mujoong/contract/json/` (1): JsonCodecTest.java
- `backend/contract-model/src/test/java/kr/mujoong/contract/log/` (2): OwnerHasherTest.java · StructuredLogFormatTest.java
- `backend/contract-model/src/test/java/kr/mujoong/contract/profile/` (1): ProfileGuardRulesTest.java
- `backend/contract-model/src/test/java/kr/mujoong/contract/secret/` (1): SecretCompareTest.java
- `backend/contract-model/src/test/java/kr/mujoong/contract/time/` (2): DelayQueueTimerDriverTest.java · SystemMujoongClockTest.java
- `backend/contract-model/src/test/java/kr/mujoong/contract/trace/` (1): TraceIdsTest.java
- `backend/contract-model/src/test/java/kr/mujoong/contract/ws/` (1): WsEnvelopeCodecTest.java
- `backend/core-api/` (2): Dockerfile · build.gradle.kts
- `backend/core-api/src/main/java/kr/mujoong/core/` (1): CoreApiApplication.java
- `backend/core-api/src/main/java/kr/mujoong/core/activity/` (11): ActivityCopyRepository.java · ApprovedContentCatalog.java · ConsentGate.java · EndSessionService.java · PreparingTimeoutChecker.java · RealtimeSessionClient.java · SessionPackageBuilder.java · SessionStartBreaker.java · SessionViewService.java · StaleEvaluator.java · StartActivityService.java
- `backend/core-api/src/main/java/kr/mujoong/core/activity/web/` (2): ActivityController.java · SessionController.java
- `backend/core-api/src/main/java/kr/mujoong/core/catalog/` (3): ApprovedContent.java · ApprovedContentRepository.java · ApprovedContentVersion.java
- `backend/core-api/src/main/java/kr/mujoong/core/common/db/` (4): ClockParams.java · DbRetry.java · DbSessionLimits.java · SqlErrors.java
- `backend/core-api/src/main/java/kr/mujoong/core/common/deletion/` (1): DeletionParticipant.java
- `backend/core-api/src/main/java/kr/mujoong/core/common/error/` (1): CoreApiException.java
- `backend/core-api/src/main/java/kr/mujoong/core/config/` (4): CoreProperties.java · CorePropertiesConfig.java · CoreRuntimeConfig.java · SessionJdbcConfig.java
- `backend/core-api/src/main/java/kr/mujoong/core/device/` (13): BootstrapService.java · CodeGenerator.java · ConnectionCopyWriter.java · DeviceClaimService.java · DeviceDeletionParticipant.java · DeviceExpiryCleanupJob.java · DeviceJdbcRepository.java · DeviceSecrets.java · DeviceTokenVerifier.java · DeviceVerdict.java · DeviceViewAssembler.java · RegistrationCode.java · RegistrationService.java
- `backend/core-api/src/main/java/kr/mujoong/core/device/web/` (4): DeviceBootstrapController.java · DeviceInternalController.java · DeviceRegistrationController.java · DeviceViewController.java
- `backend/core-api/src/main/java/kr/mujoong/core/health/` (2): DbHealthChecker.java · ReadinessState.java
- `backend/core-api/src/main/java/kr/mujoong/core/identity/` (9): Child.java · ChildRepository.java · Consent.java · ConsentRepository.java · DevLoginController.java · DevSeedRunner.java · Guardian.java · GuardianRepository.java · SeedIds.java
- `backend/core-api/src/main/java/kr/mujoong/core/internalhttp/` (2): InternalAuthInterceptor.java · InternalHttpClient.java
- `backend/core-api/src/main/java/kr/mujoong/core/messaging/` (10): ControlEventRearm.java · CoreInboxDispatcher.java · CoreInboxReceiver.java · CoreInboxRepoller.java · CoreInboxRepository.java · CoreMessagingDeletionParticipant.java · CoreOutboxRepository.java · CoreOutboxSender.java · CoreOutboxWriter.java · MessagingCleanupJob.java
- `backend/core-api/src/main/java/kr/mujoong/core/messaging/handler/` (3): DeviceStatusChangedHandler.java · InboxHandler.java · SessionStateChangedHandler.java
- `backend/core-api/src/main/java/kr/mujoong/core/messaging/web/` (1): CoreInboxController.java
- `backend/core-api/src/main/java/kr/mujoong/core/observability/` (5): CoreMetrics.java · EventLogger.java · LogMergeWindow.java · OwnerHashConfig.java · SecurityEventCap.java
- `backend/core-api/src/main/java/kr/mujoong/core/security/` (8): DevLoginBlockedRecorder.java · DeviceCookieAuthFilter.java · DeviceCookies.java · GuardianPrincipal.java · InternalTokenFilter.java · OriginCheckFilter.java · SecurityConfig.java · SessionCookieConfig.java
- `backend/core-api/src/main/java/kr/mujoong/core/security/ratelimit/` (2): RateLimiterConfig.java · SlidingWindowRateLimiter.java
- `backend/core-api/src/main/java/kr/mujoong/core/startup/` (6): CoreStartupSequence.java · DrainingLifecycle.java · ProfileGuardEnvironmentPostProcessor.java · RequiredSecretsCheck.java · SchemaVersionCheck.java · StartupFailureLine.java
- `backend/core-api/src/main/java/kr/mujoong/core/web/` (12): ApiErrorController.java · ApiExceptionHandler.java · ApiResponses.java · BootstrapIpLimitFilter.java · CsrfController.java · ErrorEnvelopeValve.java · HealthController.java · HomeController.java · JsonOnlyFilter.java · ReadinessGateFilter.java · RestBodyLimitFilter.java · TraceIdFilter.java
- `backend/core-api/src/main/resources/` (6): application-demo.yml · application-dev.yml · application-load-test.yml · application-prod.yml · application.yml · logback-spring.xml
- `backend/core-api/src/main/resources/META-INF/` (1): spring.factories
- `backend/core-api/src/main/resources/db/migration/core/` (2): V1__core_base.sql · afterMigrate.sql
- `backend/core-api/src/test/java/kr/mujoong/core/activity/` (10): ActivityCopies.java · ActivityCopyRepositoryIT.java · ActivityStartEndIT.java · PreparingTimeoutCheckerTest.java · RealtimeSessionClientTest.java · SessionPackageBuilderTest.java · SessionPackageIT.java · SessionViewServiceTest.java · StaleEvaluatorTest.java · StartActivityPolicyTest.java
- `backend/core-api/src/test/java/kr/mujoong/core/arch/` (1): CoreArchTest.java
- `backend/core-api/src/test/java/kr/mujoong/core/catalog/` (2): ApprovedContentEntitiesTest.java · ApprovedContentRepositoryIT.java
- `backend/core-api/src/test/java/kr/mujoong/core/common/db/` (6): ClockParamsTest.java · CoreDataAccessContext.java · DbRetryTest.java · DbSessionLimitsIT.java · DbTestSupport.java · SqlErrorsTest.java
- `backend/core-api/src/test/java/kr/mujoong/core/config/` (3): ConfigKeyContractTest.java · CoreDataAccessWiringIT.java · SessionJdbcConfigIT.java
- `backend/core-api/src/test/java/kr/mujoong/core/db/` (7): CoreConstraintIT.java · CoreDbFixtures.java · CoreSchemaIT.java · FlywayConfIT.java · NoSqlNowTest.java · SchemaPrivilegeIT.java · SeedReferenceSqlIT.java
- `backend/core-api/src/test/java/kr/mujoong/core/device/` (8): CodeGeneratorTest.java · DeviceJdbcRepositoryIT.java · DeviceRecords.java · DeviceRegistrationFlowIT.java · DeviceSecretsTest.java · DeviceVerdictTest.java · RegistrationCodeTest.java · RegistrationDecisionTest.java
- `backend/core-api/src/test/java/kr/mujoong/core/health/` (1): ReadinessStateTest.java
- `backend/core-api/src/test/java/kr/mujoong/core/identity/` (3): DevSeedRunnerIT.java · IdentityEntitiesTest.java · IdentityRepositoriesIT.java
- `backend/core-api/src/test/java/kr/mujoong/core/internalhttp/` (1): InternalAuthInterceptorTest.java
- `backend/core-api/src/test/java/kr/mujoong/core/messaging/` (5): CoreMessagingFlowIT.java · CoreMessagingRepositoryIT.java · CoreOutboxSenderTest.java · DeletionParticipantsCoreIT.java · MessagingJobsTest.java
- `backend/core-api/src/test/java/kr/mujoong/core/observability/` (1): EventLoggerTest.java
- `backend/core-api/src/test/java/kr/mujoong/core/security/ratelimit/` (1): SlidingWindowRateLimiterTest.java
- `backend/core-api/src/test/java/kr/mujoong/core/startup/` (6): CorePropertiesValidationTest.java · CoreStartupSequenceIT.java · CoreStartupSequenceTest.java · ProfileGuardBootIT.java · ProfileGuardEnvironmentPostProcessorTest.java · SchemaVersionCheckTest.java
- `backend/core-api/src/test/java/kr/mujoong/core/support/` (4): BusinessFixtures.java · CoreBusinessContext.java · NoTransactions.java · TestEvents.java
- `backend/core-api/src/test/java/kr/mujoong/core/web/` (11): ContractConformanceIT.java · CookieAttributesTest.java · CoreInternalApiIT.java · CoreLogHygieneIT.java · DeviceBootstrapApiIT.java · DeviceRegisterApiIT.java · ErrorEnvelopeAndLimitsIT.java · GuardianApiIT.java · HealthAndReadinessIT.java · SecurityChainIT.java · WebErrorMappingTest.java
- `backend/core-api/src/test/java/kr/mujoong/core/web/support/` (4): AppOutputCapture.java · CoreWebApp.java · OpenApiContracts.java · WebFixtures.java
- `backend/core-api/src/test/resources/` (1): application-test.yml
- `backend/core-api/src/test/resources/db/testmigration/core/` (1): V2__test_default_privilege_probe.sql
- `backend/coverage-scope/` (1): u1.txt
- `backend/gradle/` (1): libs.versions.toml
- `backend/gradle/wrapper/` (2): gradle-wrapper.jar · gradle-wrapper.properties
- `backend/realtime-activity/` (2): Dockerfile · build.gradle.kts
- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/` (1): RealtimeApplication.java
- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/common/db/` (5): ClockParams.java · DbRetry.java · DbSessionLimits.java · SqlErrors.java · Transactions.java
- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/config/` (2): RealtimeProperties.java · RealtimePropertiesConfig.java
- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/gate/` (6): ApprovedPlayback.java · ClosingSequence.java · GateDecision.java · OutputGate.java · SeedDictionary.java · SegmentBuffer.java
- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/gateway/` (20): ConnectionHandover.java · ConnectionSender.java · ConnectionStateWriter.java · DeviceConnection.java · DeviceConnectionRepository.java · DeviceConnectionRow.java · DeviceHandshakeInterceptor.java · DeviceLink.java · DeviceReleasedHandler.java · DeviceStatusPolicy.java · DeviceStatusPort.java · DeviceWebSocketHandler.java · DisconnectChecker.java · HeartbeatHandler.java · OutputBundle.java · SocketRegistry.java · StatusReporter.java · VerifyTokenClient.java · WebSocketConfig.java · WsFrameGuard.java
- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/health/` (2): DbHealthChecker.java · ReadinessState.java
- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/internalhttp/` (2): InternalAuthInterceptor.java · InternalHttpClient.java
- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/log/` (6): AsyncRecorder.java · LatencyRecorder.java · TranscriptEntry.java · TranscriptRepository.java · TranscriptRow.java · TranscriptWriter.java
- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/messaging/` (16): ControlEventRearm.java · InboxEventRow.java · InboxHandler.java · LastError.java · MessagingCleanupJob.java · NewInboxEvent.java · NewOutboxEvent.java · OutboxEventRow.java · RealtimeInboxDispatcher.java · RealtimeInboxReceiver.java · RealtimeInboxRepoller.java · RealtimeInboxRepository.java · RealtimeMessagingDeletionParticipant.java · RealtimeOutboxRepository.java · RealtimeOutboxSender.java · RealtimeOutboxWriter.java
- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/messaging/web/` (1): RealtimeInboxController.java
- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/observability/` (5): EventLogger.java · LogMergeWindow.java · OwnerHashConfig.java · RealtimeMetrics.java · SecurityEventCap.java
- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/runtime/` (4): PeriodicJobs.java · RealtimeRuntimeConfig.java · TaskLauncher.java · TimerClocks.java
- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/` (23): ActivitySessionRepository.java · ActivitySessionRow.java · ChildContext.java · ConsentSnapshot.java · EndRequestHandler.java · NewActivitySession.java · OrphanSessionChecker.java · SessionChange.java · SessionEvent.java · SessionEventRepository.java · SessionExecutor.java · SessionMailbox.java · SessionRegistry.java · SessionStateEmitter.java · SessionStopExpression.java · SessionStopExpressionRepository.java · SessionTimers.java · SessionTransitions.java · SessionViewQuery.java · StartSessionHandler.java · TombstoneMap.java · TransitionService.java · TransitionTable.java
- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/web/` (1): SessionInternalController.java
- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/startup/` (7): DataClassCheck.java · DrainingLifecycle.java · ProfileGuardEnvironmentPostProcessor.java · RealtimeStartupChecks.java · RealtimeStartupSequence.java · SchemaVersionCheck.java · StartupFailureLine.java
- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/` (6): InputRelay.java · ProviderClient.java · ProviderConnector.java · ProviderHosts.java · ProviderPort.java · ProviderRequestFactory.java
- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/web/` (5): ApiExceptionHandler.java · InternalBody.java · InternalTokenFilter.java · ReadinessGateFilter.java · TraceIdFilter.java
- `backend/realtime-activity/src/main/resources/` (5): application-demo.yml · application-dev.yml · application-prod.yml · application.yml · logback-spring.xml
- `backend/realtime-activity/src/main/resources/META-INF/` (1): spring.factories
- `backend/realtime-activity/src/main/resources/db/migration/realtime/` (2): V1__realtime_base.sql · afterMigrate.sql
- `backend/realtime-activity/src/main/resources/gate/` (1): seed-dictionary.json
- `backend/realtime-activity/src/test/java/kr/mujoong/realtime/arch/` (1): RealtimeArchTest.java
- `backend/realtime-activity/src/test/java/kr/mujoong/realtime/common/db/` (4): DbRetryTest.java · DbSessionLimitsIT.java · DbSessionLimitsTest.java · RealtimeDataAccessWiringIT.java
- `backend/realtime-activity/src/test/java/kr/mujoong/realtime/config/` (1): ConfigKeyContractTest.java
- `backend/realtime-activity/src/test/java/kr/mujoong/realtime/db/` (3): RealtimeConstraintIT.java · RealtimeDbFixtures.java · RealtimeSchemaIT.java
- `backend/realtime-activity/src/test/java/kr/mujoong/realtime/gate/` (3): OutputGateTest.java · SeedDictionaryFileTest.java · SeedDictionaryTest.java
- `backend/realtime-activity/src/test/java/kr/mujoong/realtime/gateway/` (3): DeviceConnectionRepositoryIT.java · DeviceStatusPolicyTest.java · WsFrameGuardTest.java
- `backend/realtime-activity/src/test/java/kr/mujoong/realtime/log/` (3): AsyncRecorderTest.java · LatencyRecorderTest.java · TranscriptRepositoryIT.java
- `backend/realtime-activity/src/test/java/kr/mujoong/realtime/messaging/` (3): MessagingValueTypesTest.java · RealtimeMessagingIT.java · RealtimeMessagingRepositoryIT.java
- `backend/realtime-activity/src/test/java/kr/mujoong/realtime/observability/` (1): EventLoggerMergeTest.java
- `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/` (8): ActivitySessionRepositoryIT.java · SessionExecutorTest.java · SessionLifecycleIT.java · SessionRecordEntitiesTest.java · SessionRecordsRepositoryIT.java · SessionTimersTest.java · SessionValueTypesTest.java · TransitionTableTest.java
- `backend/realtime-activity/src/test/java/kr/mujoong/realtime/startup/` (4): ProfileGuardBootIT.java · ProfileGuardEnvironmentPostProcessorTest.java · RealtimeStartupChecksTest.java · RealtimeStartupRecoveryIT.java
- `backend/realtime-activity/src/test/java/kr/mujoong/realtime/testing/` (11): FakeDeviceLink.java · FakeProviderPort.java · InMemoryTransitions.java · InternalApi.java · ItTypeExcludeFilter.java · ManualLauncher.java · ManualTime.java · RealtimeDb.java · RealtimeItEnvironment.java · RealtimeTestApp.java · SessionHarness.java
- `backend/realtime-activity/src/test/java/kr/mujoong/realtime/voice/` (3): InputRelayTest.java · ProviderClientIT.java · ProviderRequestWhitelistIT.java
- `backend/realtime-activity/src/test/java/kr/mujoong/realtime/web/` (3): DeviceGatewayIT.java · RealtimeInternalApiIT.java · RealtimeLogHygieneIT.java
- `backend/realtime-activity/src/test/resources/` (1): application-test.yml
- `backend/test-support/` (1): build.gradle.kts
- `backend/test-support/src/main/java/kr/mujoong/testsupport/db/` (1): MujoongPostgres.java
- `backend/test-support/src/main/java/kr/mujoong/testsupport/device/` (1): DeviceSimulatorClient.java
- `backend/test-support/src/main/java/kr/mujoong/testsupport/http/` (2): ContractStubServer.java · ReceiverDouble.java
- `backend/test-support/src/main/java/kr/mujoong/testsupport/log/` (1): LogCapture.java
- `backend/test-support/src/main/java/kr/mujoong/testsupport/provider/` (1): FakeProviderWsServer.java
- `backend/test-support/src/main/java/kr/mujoong/testsupport/secret/` (1): TestSecrets.java
- `backend/test-support/src/main/java/kr/mujoong/testsupport/time/` (2): ManualTimerDriver.java · MutableClock.java
- `backend/test-support/src/test/java/kr/mujoong/testsupport/db/` (1): MujoongPostgresTest.java
- `backend/test-support/src/test/java/kr/mujoong/testsupport/http/` (1): ContractStubServerTest.java
- `backend/test-support/src/test/java/kr/mujoong/testsupport/time/` (2): ManualTimerDriverTest.java · MutableClockTest.java

### frontend (125개)

- `frontend/` (9): .dockerignore · Dockerfile · eslint.config.mjs · next-env.d.ts · next.config.ts · package-lock.json · package.json · tsconfig.json · vitest.config.ts
- `frontend/coverage-scope/` (1): u1.json
- `frontend/src/` (1): proxy.ts
- `frontend/src/app/` (1): layout.tsx
- `frontend/src/app/device/` (1): page.tsx
- `frontend/src/app/device/pcm-capture-worklet/` (1): route.ts
- `frontend/src/app/healthz/` (1): route.ts
- `frontend/src/app/parent/` (2): layout.tsx · page.tsx
- `frontend/src/app/parent/dev-login/` (1): page.tsx
- `frontend/src/app/parent/device/` (1): page.tsx
- `frontend/src/app/parent/device/register/` (1): page.tsx
- `frontend/src/app/parent/free/` (1): page.tsx
- `frontend/src/app/parent/practice/` (1): page.tsx
- `frontend/src/app/parent/records/` (1): page.tsx
- `frontend/src/app/parent/sessions/[sessionId]/` (1): page.tsx
- `frontend/src/app/parent/sessions/[sessionId]/done/` (1): page.tsx
- `frontend/src/device/` (2): DeviceApp.tsx · device.css
- `frontend/src/device/api/` (1): deviceApi.ts
- `frontend/src/device/audio/` (5): audioContext.ts · capture.ts · pcm-capture.worklet.ts · player.ts · resample.ts
- `frontend/src/device/components/` (3): D0View.tsx · DebugLog.tsx · T19View.tsx
- `frontend/src/device/machine/` (1): deviceMachine.ts
- `frontend/src/device/mic/` (1): micPermission.ts
- `frontend/src/device/power/` (1): wakeLock.ts
- `frontend/src/device/runtime/` (1): deviceRuntime.ts
- `frontend/src/device/ws/` (2): deviceSession.ts · wsClient.ts
- `frontend/src/parent/` (3): ParentProviders.tsx · navigation.ts · parent.css
- `frontend/src/parent/api/` (4): apiClient.ts · csrf.ts · queries.ts · types.ts
- `frontend/src/parent/components/` (8): BottomTabBar.tsx · ChipGroup.tsx · CodeInput.tsx · ConfirmDialog.tsx · DeviceCard.tsx · StatusLine.tsx · TextArea200.tsx · TranscriptList.tsx
- `frontend/src/parent/hooks/` (1): useLivePolling.ts
- `frontend/src/parent/screens/` (6): DevLogin.tsx · Done17.tsx · Free08.tsx · Home04.tsx · Live12.tsx · Register05a.tsx
- `frontend/src/shared/copy/` (3): ko.ts · particle.ts · timeText.ts
- `frontend/src/shared/errors/` (1): codeToCopy.ts
- `frontend/src/shared/protocol/` (4): closeCodes.ts · frameCodec.ts · messages.ts · wsEnvelope.ts
- `frontend/src/shared/security/` (1): csp.ts
- `frontend/src/shared/styles/` (1): tokens.css
- `frontend/src/shared/timing/` (4): TimingProvider.tsx · readTimingFromEnv.ts · timing-defaults.json · timing.ts
- `frontend/src/shared/toast/` (3): Toast.tsx · ToastProvider.tsx · toast.css
- `frontend/tests/u1/` (1): setup.ts
- `frontend/tests/u1/device/` (14): capture.test.ts · d0-view.test.tsx · device-api.test.ts · device-app.test.tsx · device-machine.test.ts · device-runtime.test.ts · device-session.test.ts · mic-permission.test.ts · no-service-worker.test.ts · pcm-capture-worklet.test.ts · player.test.ts · resample.test.ts · t19-view.test.tsx · ws-client.test.ts
- `frontend/tests/u1/lint/` (1): eslint-boundary.test.ts
- `frontend/tests/u1/parent/` (10): api-client.test.ts · api-types.test.ts · code-input.test.tsx · dev-login.test.tsx · done-17.test.tsx · free-08.test.tsx · home-04.test.tsx · live-12.test.tsx · p1-dialog.test.tsx · register-05a.test.tsx
- `frontend/tests/u1/shared/` (9): copy.test.ts · csp.test.ts · frame-codec.test.ts · particle.test.ts · routes.test.tsx · test-ids.test.tsx · timing.test.ts · toast.test.tsx · ws-protocol.test.ts
- `frontend/tests/u1/support/` (9): deviceHarness.tsx · fakeAudio.ts · fakeSocket.ts · fixtures.ts · nextNavigation.ts · parentHarness.tsx · runtimeMock.ts · sourceScan.ts · timingWrapper.tsx

### contracts (42개)

- `contracts/` (11): README.md · ai-io.md · deviations.md · device-api.yaml · device-ws.asyncapi.yaml · errors.md · events.asyncapi.yaml · internal-api.yaml · parent-api.yaml · ports.md · timing.md
- `contracts/schemas/` (31): activity-free-request.schema.json · activity-free-response.schema.json · common.schema.json · csrf-response.schema.json · dev-login-response.schema.json · device-bootstrap-response.schema.json · device-bootstrap-status-response.schema.json · device-claim-response.schema.json · device-me-response.schema.json · device-register-request.schema.json · device-register-response.schema.json · device-view.schema.json · empty-object.schema.json · error-envelope.schema.json · event-envelope.schema.json · event-payloads.schema.json · health-response.schema.json · home-response.schema.json · internal-device-connection-report.schema.json · internal-session-end-request.schema.json · internal-session-end-response.schema.json · internal-session-start-request.schema.json · internal-session-start-response.schema.json · internal-session-view.schema.json · internal-verify-token-request.schema.json · internal-verify-token-response.schema.json · live-view.schema.json · session-end-request.schema.json · session-end-response.schema.json · ws-envelope.schema.json · ws-messages.schema.json

### infra (82개)

- `infra/` (5): RUNBOOK.md · compose.ci.yml · compose.demo.yml · compose.dev.yml · release.env.tmpl
- `infra/aws/` (4): iam-ec2-inline.json · iam-gha-deploy-trust.json · iam-gha-deploy.json · ssm-mujoong-deploy.yml
- `infra/backup/` (3): Dockerfile · backup.sh · crontab
- `infra/bin/` (14): backup-trigger · check-since-last · deploy-apply · deploy-stage1 · host-collect.sh · infra-validate · log-hygiene-check · mj · power-mark · probe-three · render-env · render-nginx · render-pgconf · smoke
- `infra/bin/lib/` (2): host.sh · render.sh
- `infra/bundle/` (2): Dockerfile · Dockerfile.dockerignore
- `infra/ci/` (8): build-images.sh · gen-env.sh · gen-selfsigned.sh · image-checks.sh · source-fingerprint.sh · test-gate-negatives.sh · test-host-tools.sh · test-infra-validate.sh
- `infra/ci/invariant-samples/` (12): bad-cap-add.yml · bad-core-depends-realtime.yml · bad-dev-public-port.yml · bad-devlogin-on.yml · bad-extra-port.yml · bad-inline-secret.yml · bad-latest-tag.yml · bad-postgres-no-user.yml · bad-profile-demo-dev.yml · bad-three-networks.yml · bad-unreceived-secret.yml · ok-dev-loopback.yml
- `infra/host/` (1): daemon.json
- `infra/host/cron.d/` (1): mujoong
- `infra/host/docker.service.d/` (1): mujoong-mounts.conf
- `infra/host/entry/` (3): deploy.sh · host-collect.sh · mj
- `infra/host/logrotate.d/` (1): mujoong
- `infra/host/sysctl.d/` (1): 90-mujoong.conf
- `infra/host/systemd/` (2): mujoong-compose.service · mujoong-power-mark.service
- `infra/migrate/` (4): Dockerfile · flyway-core.conf · flyway-realtime.conf · migrate.sh
- `infra/nginx/` (2): nginx.conf.tmpl · reload-watch.sh
- `infra/nginx/conf.d/` (3): http80-bootstrap.conf.tmpl · http80.conf.tmpl · https443.conf.tmpl
- `infra/nginx/html/` (1): maintenance.html
- `infra/nginx/snippets/` (3): error-json.conf · proxy-common.conf · security-headers.conf
- `infra/nginx/upstreams/` (2): compose.conf · host.conf
- `infra/postgres/` (1): postgresql.conf.tmpl
- `infra/postgres/init/` (3): 01-roles.sh · 01-roles.sql · 02-hba.sh
- `infra/seed-audio/` (2): closing-v1.wav · waiting-filler-v1.wav
- `infra/seed/` (1): 001-reference.sql

### tools (28개)

- `tools/` (4): .dockerignore · package-lock.json · package.json · vitest.config.mjs
- `tools/dev-edge/` (1): edge.mjs
- `tools/device-simulator/` (1): simulator.mjs
- `tools/fake-provider/` (3): Dockerfile · scenarios.mjs · server.mjs
- `tools/lib/` (6): cookieJar.mjs · envelope.mjs · frameCodec.mjs · httpClient.mjs · jsonlTail.mjs · log.mjs
- `tools/seed/` (1): gen-approved-wav.mjs
- `tools/skeleton-e2e/` (4): assertions.mjs · deadlines.mjs · run-c.mjs · steps.mjs
- `tools/tests/u1/` (8): cookie-jar.test.mjs · dev-edge.test.mjs · device-simulator.test.mjs · fake-provider.test.mjs · frame-codec.test.mjs · scenario-plan.test.mjs · seed-audio.test.mjs · verify-exit.test.mjs

### scripts (11개)

- `scripts/` (1): verify.sh
- `scripts/verify/` (10): HostMigrate.java · a-checks.sh · a-secrets.sh · assert-log-hygiene.sh · assert-schema-isolation.sh · b-host.sh · b-stack.sh · c-skeleton-turn.sh · lib.sh · secret-patterns.txt

### .github (2개)

- `.github/workflows/` (2): ci.yml · deploy.yml

### ci (2개)

- `ci/` (2): security-exceptions.yml · security-gate.py

## 주요 구현 결정

계획의 J97 ~ J144를 그대로 구현했고, 아래는 코드에서 굳어진 핵심 결정이다(근거 ID는 코드 주석에도 있다).

**구조 · 경계**
- 백엔드는 `contract-model`(계약 · 오류 · 시간 · 로그 타입만, Spring · JPA 없음) · `core-api` · `realtime-activity` · `test-support`(시험 전용)이고 두 앱은
  서로 의존하지 않는다(LC-11 · J105). ArchUnit 규칙 6개(J113)와 그 밖의 싼 규칙(J220)이 이를 강제한다.
- 시각은 주입한 `MujoongClock` 하나로 읽는다. 직접 시각 호출은 `SystemMujoongClock` 한 곳뿐이고 SQL에는 `now()`류가 0건이다(RD-2 · R2 · NFR6.4).
  저장소는 Clock을 갖지 않고 서비스가 읽은 `now`를 인자로 받는다(J171).
- 시한 · 주기 · 한도는 모두 `mujoong.*` 설정 키이고 `contracts/timing.md` ↔ `application.yml` ↔ 타입 있는 설정 레코드가 시험으로 묶인다(BR8.11 · J223).
- 프런트는 한 앱에 `/parent` · `/device`를 두고 ESLint 경계(`import/no-restricted-paths`)로 서로 import하지 않는다(NFR11.1 · J156).

**데이터 · 메시징**
- PostgreSQL 한 인스턴스에 `core` · `realtime` · `flyway` 스키마, 역할 4개(SD-13). 앱 역할은 자기 스키마 DML만 갖고 교차 쓰기는 42501이다(B1 ②).
  표 소유는 `mujoong_owner`이고 계약 C13의 `owner_role`은 '쓰는 역할'로 읽는다(편차 (x)).
- Flyway 이력 표 `flyway.core_schema_history` · `flyway.realtime_schema_history`(J116), 두 위치가 `flyway` 스키마를 함께 쓰므로 기준선 설정을 더했다(J157).
- Outbox/Inbox는 앱마다 따로다. 조건부 UPDATE 한 문장 + 행 수 확인, `FOR UPDATE SKIP LOCKED` 선점(CTE 한 문장, J175), 전달 뒤 payload `{}` · `purged_at`,
  A1 제어 이벤트 재무장, A3 `trace_id`, A4 `guardian_id`를 구현했다.
- 멱등 · 유일 충돌은 `ON CONFLICT DO NOTHING` 뒤 다시 읽어 판정한다(트랜잭션을 깨지 않음, J174 · J182).

**보안 · 실행 골격**
- 프로필 가드(SD-14 13조합 · A2 · A6)는 웹 서버보다 먼저 환경 후처리기로 돈다. 등록은 Boot 4 키로 `META-INF/spring.factories`에 했다(J188).
- Core 필터 순서 F1 traceId → F1b 준비 상태 → F2 IP 한도 → F3 인증 → F3b 익명 401 → F4 Origin · CSRF → F5 소유권, 체인 C-INT · C-DEV · C-PUB · C-GRD,
  맞는 체인이 없으면 404(J208 · J209). 내부 토큰은 SHA-256 상수 시간 비교(SD-8).
- 로그는 JSON 한 줄, 최상위 여섯 필드 + 사건 코드별 허용 attrs(OD-1 ~ OD-4). 반복 병합 키에 로그 수준을 넣어 INFO 뒤 WARN이 묻히지 않게 했다(J206).
- 비밀은 환경 변수 이름만 저장소에 있고, 시험 값은 실행 때 만든다(J127). 필수 비밀이 없으면 값 없이 이름만 남기고 기동을 거부한다.

**실시간 · 음성**
- 세션마다 가상 스레드 실행기와 우편함(L0H 우선 · 세대), 네 시한, 전이 표(BR6.8)를 Realtime이 갖고, Core는 사본 · 조회만 한다(BR6.17).
- 공급자 요청은 `ProviderRequestFactory` 하나에서만 만들고 레코드 필드는 지시문 · childContext 3필드 · 음성 프레임뿐이다(SD-20 · NFR3.1 · NFR3.2). 연결은
  JDK `java.net.http.WebSocket`(J110)이고 허용 호스트 목록 밖이면 열지 않는다(J141).
- 출력 게이트는 800ms 조용 구간 → 시드 사전(리터럴 + `#`, 정규식 없음, NFKC 정규화) → 송신 직전 재확인 → OUTPUT_BEGIN · 0x02 · OUTPUT_END이고, 출력
  세대 번호로 늦은 출력을 버린다(SD-17 · SD-18). 출력 0x02는 4,800B로 다시 나눠 보낸다(J194).
- 기기 웹 오디오는 「켜기」 클릭 안에서 24kHz 문맥을 만들고, 48 → 24kHz 보간과 480표본 조립은 메인 스레드 순수 함수가 맡는다(J121 · J226).

**검증 명령 · 배포**
- `scripts/verify.sh`는 엄격 모드 · trap · 단계 기한 · 시작 정리 · 작업 트리 비교를 갖고, 단계 건너뛰기 옵션이 없다(J144). `--engine`은 내용 기준 소스
  지문(J239)이 같은 미리 만든 산출물이 있을 때만 B · C · 사후 단언을 110초 안에 돈다(사람 결정 10 · J134).
- 배포는 SSM 경유 2단계(D3a stage1 · D3b apply, J270), Compose 파일은 `compose.demo.yml`만, D3에서 프로필 가드(CD-7)를 다시 확인한다. 실호출은 3.6 · 첫 배포 몫이다.

## 시험 · 커버리지

**유닛 범위 하한 판정(J137) — Step 53 실행, 2026-10-10**

```bash
cd backend && ./gradlew --no-daemon :contract-model:test :core-api:test :realtime-activity:test \
  :contract-model:jacocoTestCoverageVerification :core-api:jacocoTestCoverageVerification \
  :realtime-activity:jacocoTestCoverageVerification -PincludeTags=u1 -PcoverageScope=u1     # + jacocoTestReport(수치 읽기용)
cd frontend && MUJOONG_COVERAGE_SCOPE=u1 npx --no-install vitest run tests/u1 --coverage
cd tools && npx --no-install vitest run tests/u1
```

| 모듈 | 시험 수(실패 · 건너뜀) | 줄 커버리지(U1 범위) | 분기 | 하한 | 비고 |
|---|---|---|---|---|---|
| contract-model | 276 (0 · 0) | **98.53%** (1478/1500) | 89.36% | 80% 통과 | 시험 클래스 14 |
| core-api | 506 (0 · 0) | **95.77%** (3672/3834) | 81.17% | 80% 통과 | 시험 클래스 61, DB 시험 백엔드 docker |
| realtime-activity | 387 (0 · 0) | **89.93%** (5052/5618) | 72.61% | 80% 통과 | 시험 클래스 39, DB 시험 백엔드 docker |
| test-support | 27 (0 · 0) | 51.69%(Step 36 실행) | — | 대상 아님(J107) | 자기 시험(시계 · 타이머 · PostgreSQL 백엔드 · 계약 대역) |
| frontend `src/**` | 499 (0 · 0), 34파일 | **98.82%** (1848/1870) | 96.20% | 80% 통과 | 문장 98.53% · 함수 97.29% |
| tools | 105 (0 · 0), 8파일 | — | — | 대상 아님(J107) | verify-exit 10건 포함 |

- 백엔드 합계 1,196건. `-PcoverageScope=u1` · `MUJOONG_COVERAGE_SCOPE=u1` 목록(`backend/coverage-scope/u1.txt` 280줄 · `frontend/coverage-scope/u1.json` 66파일)은
  `source-manifest.json`의 `backend/*/src/main/java/**/*.java` · `frontend/src/**/*.{ts,tsx}`와 하나도 다르지 않음을 대조했다. 지금 세 모듈과 `src/**`의
  운영 소스가 모두 U1 것이라 U1 범위 수치와 모듈 전체 수치가 같다.
- DB 시험(`@Tag("db")`)은 docker(Testcontainers)와 local(호스트 PostgreSQL 16) 두 백엔드에서 모두 같은 수로 통과한 기록이 있다(Step 14 · 15 · 18 · 28).
  host 모드 검증 명령의 A1은 local로 1,196건 통과했다(Step 51).
- 셸 · 인프라 자체 시험: `infra/ci/test-infra-validate.sh` 32건, `infra/ci/test-host-tools.sh` 23건, `infra/ci/test-gate-negatives.sh` 관문 5종(CD-1)
  모두 기대대로다(Step 47 · 48 · 49). `infra/bin/infra-validate` 8개 검사 PASS, actionlint 1.7.12 rc 0.
- 분량(Standard 5 ~ 8개/컴포넌트)에 못 미친 클래스: JPA 저장소 시험 두 클래스(2 · 3건, 합 5건 · 해당 줄 100%), `SessionPackageIT` · `DevSeedRunnerIT`(3건씩,
  같은 컴포넌트를 단위 시험이 함께 덮음). ## 알려진 틈에 남긴다.
- 줄 커버리지가 낮은 클래스(모듈 합계는 하한 위): Realtime `MessagingCleanupJob` 45% · `SecurityEventCap` 33% · `EndRequestHandler` 64% · `TraceIdFilter` 70%.
  프런트 미커버 22줄은 모두 실행 가능한 방어 분기다(Step 42 보고).

**시험 위치 요약**

| 층 | 대표 시험 |
|---|---|
| 데이터 모델 | `contract/**/*Test` · `ContractFilesTest` · `core/db/{CoreSchemaIT,SchemaPrivilegeIT,CoreConstraintIT,NoSqlNowTest,SeedReferenceSqlIT,FlywayConfIT}` · `realtime/db/*IT` · `tools/tests/u1/seed-audio.test.mjs` |
| 데이터 접근 | `core/{device,activity,messaging}/*RepositoryIT` · `DbRetryTest` · `realtime/{session,gateway,log,messaging}/*RepositoryIT` |
| 업무 로직 | `core/{device,activity,messaging,startup,security}/*Test` · `*FlowIT` · `ActivityStartEndIT` · `SessionPackageIT` · `realtime/{session,gate,voice,log,gateway,startup}/*Test` · `SessionLifecycleIT` · `ProviderClientIT` · `ProviderRequestWhitelistIT` · `RealtimeStartupRecoveryIT` |
| API | `core/web/*IT` · `CookieAttributesTest` · `realtime/web/{RealtimeInternalApiIT,DeviceGatewayIT,RealtimeLogHygieneIT}` · `*/arch/*ArchTest` · `*/config/ConfigKeyContractTest` |
| 프런트 | `frontend/tests/u1/{device,shared,parent,lint}/*.test.ts(x)` |
| 도구 · 검증 | `tools/tests/u1/*.test.mjs` · `infra/ci/test-*.sh` · `scripts/verify.sh` |

## 계획과 다르게 한 것 — 준하 제안(가정)

각 Step 보고의 J 후보를 모아 J145부터 번호를 붙였다. 모두 확정이 아닌 가정이고, '확인'은 누가 언제 정하는지다. 동작을 바꾸는 결정은 사람 확인 뒤에만 확정이다.
환경 대응(J145 · J146)은 저장소 밖 설정이라 CI · 다른 PC에는 영향이 없다.

| 번호 | 내용 | 근거(Step · 보고) | 확인 |
|---|---|---|---|
| J145 | Gradle 배포판 받기(`downloads.gradle.org`)가 출구 정책 403이라 저장소 밖 래퍼 캐시에 설치판 8.14.3을 넣었다. 저장소의 `distributionUrl` · sha256은 공식 값 그대로 | Step 3 | 환경 |
| J146 | Maven Central 429를 저장소 밖 init 스크립트(`~/.gradle/init.d/`)로 공식 미러에 돌렸다. 빌드 파일은 `mavenCentral()` 그대로 | Step 3 | 환경 |
| J147 | build-logic 패키지를 `kr.mujoong.buildlogic`으로 했다(`kr.mujoong.build`는 `.gitignore`의 `build/`에 걸림). 뒤 단계도 `build`라는 소스 디렉터리를 만들면 안 된다 | Step 3 | Plan 범위 |
| J148 | 계획 밖 파일: `MujoongBuildExtension.kt`(test-support만 80% 하한을 걸지 않는 스위치, J107), `CorePropertiesConfig` · `RealtimePropertiesConfig`(설정 등록 · 풀 앱당 10 · 합계 20 기동 검증) | Step 3 | Plan 범위 |
| J149 | Core가 realtime 소유 키 `mujoong.timing.device.status-report-interval`을 불변식 I-6 기동 검증용으로 같은 값(10초)으로 읽는다 | Step 3 | 3.6 |
| J150 | 이름 바인딩이 안 되는 환경 변수 `MUJOONG_SECURITY_ALLOWED_ORIGIN` · `MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET`을 yml 자리표시로 연결했다. 허용 origin은 https만, 루프백일 때만 http | Step 3 | Plan 범위 |
| J151 | 기본값을 '아무도 믿지 않음'으로 둔다: `server.tomcat.remoteip.internal-proxies` 기본 `"^$"`(Compose · host 모드가 환경 변수로 줌). DB · 내부 주소 기본값은 Compose 망 이름(호스트 실행은 `SPRING_DATASOURCE_URL` · `MUJOONG_INTERNALHTTP_*`로 바꿈) | Step 3 | Plan 범위 |
| J152 | test-support 시험 전용 의존성: Java-WebSocket 1.6.0(가짜 공급자 WS 서버) · HikariCP · Logback · networknt json-schema-validator 3.0.6(Jackson 3.1 계열) | Step 3 · 12 | Plan 범위 |
| J153 | springdoc은 core에만(dev · test에서 켬, 맞는 보안 체인이 없어 경로는 404). realtime은 Spring Security 없이 서블릿 필터만 쓴다 | Step 3 · 31 | 사람(dev 문서 필요 여부) |
| J154 | `application-test.yml`은 시간 키만 줄이고(건수 · 한도 · 수명 · 보존은 실제 값) 풀 2 · `management.server.port: 0` · 공급자 루프백 `ws://127.0.0.1:9/…`를 둔다 | Step 5 | Plan 범위 |
| J155 | 프런트 판: TypeScript 5.9.3(typescript-eslint 범위) · ESLint 9.39.5(eslint-plugin-react 범위) · Vitest 4.1.11 · vite 7.3.6 직접 고정(npm 10 'edgesOut' 회피) · npm@11.19.1로 설치(잠금 파일은 npm 10 `npm ci` 확인) · TanStack Query 5.104.0 미리 설치 · browserslist Safari 16.4 | Step 4 · 6 | Plan 범위 |
| J156 | ESLint 규칙을 더했다: 경로를 실제로 풀어 보는 `import/no-restricted-paths`, `serviceWorker` 사용 금지(NFR10.4) | Step 4 | Plan 범위 |
| J157 | Flyway 기준선(J116 보완): 두 위치가 `flyway` 스키마를 함께 써 두 번째 migrate가 "non-empty schema"로 실패하므로 `schemas=flyway,<앱>` · `baselineOnMigrate=true` · `baselineVersion=0`. 두 번째 위치 이력 표에 버전 0 BASELINE 행이 남는다(S2는 `type='SQL'`만 읽음) | Step 9 · 10 · 12 | Plan 범위 |
| J158 | 계획 밖 시험 `MujoongPostgresTest`(`u1` · `db`): Step 9 스크립트와 J132 · J135 두 백엔드, 허용 대역 밖 28000 | Step 12 | Plan 범위 |
| J159 | docker-java DEBUG 로그가 컨테이너 env(DB 비밀번호)를 시험 출력에 남겨 그 로거만 INFO로 올렸다(NFR9.10) | Step 12 | Plan 범위 |
| J160 | local DB 백엔드 세부: 유닉스 소켓 끔(TCP 127.0.0.1만), `build/tmp/pg-<id>`, `initdb --locale=C --encoding=UTF8`, pg_hba 재적용은 `pg_conf_load_time()` 변화로 확인 | Step 12 | Plan 범위 |
| J161 | contract-model 추가 타입(`EndRequestReason` · `ActivityType` · `EventPayload` · `WsPayload` · `View` · `AppErrorLayout`). 모르는 값은 `UNRECOGNIZED`, JSON 시각은 밀리초로 자르고 null 필드는 쓰지 않는다. 환경 프로필이 없는 조합(예: `test`만)은 허용하되 시험 로그인은 닫는다 | Step 8 | Plan 범위 |
| J162 | 계약 표기 · init 세부: 스키마 `$id`는 `https://mujoong.invalid/...`, OpenAPI 3.0.3이 2020-12 스키마를 `$ref`, `01-roles.sql` 직접 실행 방지, pg_hba는 `local` 접속 거부(수동 작업은 backend 망 컨테이너에서), `log_min_error_statement=panic` | Step 7 · 9 | Plan 범위 |
| J163 | migrate 이미지 경로 `/flyway/conf/flyway-*.conf` · `/flyway/sql/{core,realtime}`, 접속 정보는 `FLYWAY_URL` · `FLYWAY_USER` · `FLYWAY_PASSWORD` 환경 변수 | Step 10 | Plan 범위 |
| J164 | 두 Flyway conf에 `validateMigrationNaming=true`를 더했다(`FlywayConfIT`가 MujoongPostgres 설정과 대조) | Step 10 | Plan 범위 |
| J165 | 시드 세부: 고정 UUID는 계약 예시 값(content `…031` · `…032` · `…033`, version `…041` ~ `…043`), CONSENT_POLICY `content_type`은 `POLICY_VERSION`, `audio_path`는 파일 이름만(URL은 앱이 `/media/approved/`를 붙임), 고정 시각 2026-10-10Z, `psql --single-transaction` | Step 11 | U2(OQ7) · D 담당 |
| J166 | JPA 엔터티: 상태 · 종류는 String(뒤 유닛 값 추가에 견딤), JSON 열은 String + `@JdbcTypeCode(JSON)`, 외래 키는 UUID 필드 | Step 10 · 11 | Plan 범위 |
| J167 | 같은 스키마 FK는 entities.md에 참조가 적힌 열만, 모두 `ON DELETE RESTRICT`(realtime event · stop_expression · transcript → 세션 포함) | Step 10 · 11 | ERD v0.2 |
| J168 | CHECK 제약 추가: 상태 · 종류 값 범위, Outbox `retry_count` ≤ 20 · Inbox ≤ 5, PUBLISHED면 payload 비움. 여러 조건은 `IS TRUE`로 감쌈 | Step 10 · 11 | ERD v0.2 |
| J169 | JPA validate는 앱 전체 문맥 대신 `ApplicationContextRunner`(DataSource + Hibernate + `@EntityScan`)로 시험 | Step 14 | Plan 범위 |
| J170 | `NoSqlNowTest`가 두 앱 운영 소스 리터럴 · 두 마이그레이션 위치 · `infra/seed`를 모두 본다 | Step 14 | Plan 범위 |
| J171 | 저장소는 Clock을 갖지 않고 서비스가 읽은 `Instant now`를 인자로 받는다(`ClockParams`가 바인딩) | Step 15 · 16 | Plan 범위 |
| J172 | 계획 밖 `DbSessionLimits`(Hikari 연결마다 `lock_timeout` 2초 · `statement_timeout` 10초를 설정 키에서, `connection-init-sql`이 따로 있으면 기동 거부)와 `SqlErrors`를 두 앱에 따로 둔다(LC-11) | Step 15 · 16 | Plan 범위 |
| J173 | 보호자 FOR SHARE는 `DeviceJdbcRepository.lockGuardianStatusForShare` 한 곳(락 순서 보호자 → 코드 → 옛 기기). 값 형식은 저장소 안 중첩 record이고 코드 · 해시 · payload를 `toString`에 내지 않는다 | Step 15 | Plan 범위 |
| J174 | 유일 충돌(BR1.2 · BR6.2 · BR6.3)은 `ON CONFLICT DO NOTHING` → false로 받아 트랜잭션을 깨지 않는다. register의 활성 기기 색인 23505는 `ActiveDeviceConflictException` | Step 15 | Plan 범위 |
| J175 | Outbox 선점은 `FOR UPDATE SKIP LOCKED`와 `available_at` 갱신을 CTE 한 문장으로 합치고 `created_at` · `event_id` 순으로 Java에서 다시 정렬 | Step 15 · 16 | Plan 범위 |
| J176 | Outbox 기록의 MANDATORY는 AOP가 아니라 활성 트랜잭션을 직접 확인한다. Inbox `guardian_id`는 상대 앱 데이터라 NULL 허용(Outbox는 필수, A4) | Step 15 · 16 | Plan 범위 |
| J177 | 만료 경계: 유효는 `code_expires_at > now`(같은 순간은 만료, BR1.4 문구의 틈 제거), 7일 삭제는 `code_expires_at <= now − 7d` | Step 15 | 사람(BR1.4 문구) |
| J178 | 사본 반영: 모르는 `EndReason` · `FailureCode`는 NULL, `ended_at`은 DECLINED 포함 모든 종료, 로컬 FAILED는 `observed_at` 두고 `ended_at = now`, `fillStartedAtIfMissing`은 로컬 FAILED 제외 | Step 15 | Plan 범위 |
| J179 | Inbox IGNORED 삽입은 `processed_at = purged_at = received_at`. BR5.14 (c)는 Core Inbox만(Core Outbox에는 SessionRecordCaptured가 없음) | Step 15 | Plan 범위 |
| J180 | `SessionJdbcConfig`가 표 이름을 `core.core_spring_session`으로 스키마 한정하고 유휴 만료를 `session-idle`에서 읽는다(ON_SAVE · ON_SET_ATTRIBUTE) | Step 15 | Plan 범위 |
| J181 | `ConsentRepository.hasAllGiven`은 4항목 각각 효력 있는 행 중 `consent_version` 최고 행이 GIVEN이어야 참 | Step 15 | U2 |
| J182 | Realtime 세션 INSERT는 충돌 대상 없는 `ON CONFLICT DO NOTHING`, 0행이면 id로 다시 읽어 202(행 있음) · 409(없음)를 가린다(BR6.6 ④와 결과 같음) | Step 16 | Plan 범위 |
| J183 | `SessionEvent` · `SessionStopExpression` 저장소는 JDBC(세션 행 · Outbox와 같은 트랜잭션, 기록기 배치). JPA 엔터티는 validate · 값 타입으로만 | Step 16 | Plan 범위 |
| J184 | 연결 원장 해석: 40초 끊김 대상 CONNECTED · UNSTABLE · UNRESPONSIVE(cid 유지), UNRESPONSIVE 출발은 CONNECTED · UNSTABLE, HEARTBEAT 복귀 출발은 UNRESPONSIVE · DISCONNECTED, OFFLINE은 cid 비움, HELLO_OK 교체 때 `last_seen_at = now`(J125 보완), 시각은 뒤로 가지 않음 | Step 16 | 사람(J125) |
| J185 | 전사 덮어쓰기는 같은 화자 PARTIAL만, FINAL은 닫힘, 폐기된 AI 말은 나중에 승인될 수 없음 | Step 16 | Plan 범위 |
| J186 | Realtime 저장소 안전장치: 종료 · REQUESTED 출발 전이 거부, 실패 코드는 `end_detail`, `last_error`는 공백 없는 코드 토큰만(BR5.13), Outbox는 Realtime → Core 방향만, 재시도 기록은 선점 때 retry_count 조건 | Step 16 | Plan 범위 |
| J187 | 계획 밖 Core 운영 파일 7개: `CoreApiException` · `DeletionParticipant` · `CoreRuntimeConfig`(Clock · JsonCodec 빈) · `InboxHandler` · `CoreMetrics` · `RateLimiterConfig` · `StartupFailureLine`(기동 거부 사유를 이름만 stderr에) | Step 19 ~ 22 | Plan 범위 |
| J188 | 환경 후처리기 등록은 계획의 `META-INF/spring/*`가 아니라 Boot 4 키로 `META-INF/spring.factories`(두 앱). `ProfileGuardBootIT` 17건이 실제 기동으로 확인 | Step 21 · 26 | Plan 범위 |
| J189 | AC3.3.8 '같은 requestId 2회 → RT 1회': BR6.9대로 Core는 매 요청을 넘기고 두 번 모두 202 CLOSING. 전이 1회는 Realtime 실행기가 보장 | Step 28 | 사람 |
| J190 | Core 세부: 아이 행이 없으면 403 CONSENT_MISSING, 503 시작의 취소(CORE_ABANDONED)는 응답 뒤 가상 스레드, BR6.14 재취소는 커밋 뒤 트랜잭션 밖, Outbox `last_error`는 코드 문자열, ownerHash 비밀은 demo · prod만 필수, 차단기 실패 1건 = 시간 초과 재시도 포함 논리 호출 하나 | Step 19 ~ 22 | Plan 범위 |
| J191 | 계획 밖 Realtime 교체 지점: `TaskLauncher` · `DeviceLink` · `SessionTransitions` · `TransitionTable` · `OutputBundle` · `DeviceStatusPolicy` · `DeviceStatusPort` · `RealtimeRuntimeConfig`(실제 스레드 없이 결정적으로 시험) | Step 23 ~ 26 | Plan 범위 |
| J192 | `FakeProviderPort`는 test-support가 아니라 realtime `src/test/.../testing/`(test-support가 realtime에 의존하지 않게) | Step 30 | Plan 범위 |
| J193 | 공급자 `input_transcript.delta`를 받으면 아이 입력 재개로 보고 열린 출력 구간을 확정한다(BR7.3) | Step 25 | 11/2(V-2) |
| J194 | 출력 0x02 프레임을 4,800B(100ms)로 다시 나눠 보낸다 | Step 25 | 3.6 실기 |
| J195 | READY 뒤 `sentAt`만 빠졌거나 틀린 봉투는 받고 시계 오프셋만 비워 `CLOCK_OFFSET_MISSING`(OD-8 · R3-4) | Step 24 | Plan 범위 |
| J196 | `ConnectionSender`가 계약상 opId 필수 type에만 opId를 싣는다(없으면 만듦) | Step 24 | Plan 범위 |
| J197 | U1이 받지 않는 값: RT `activityType: PRACTICE`(U5) · end `CONSENT_WITHDRAWN`(U4)은 400 VALIDATION_FAILED. 편차 기호 없이 `contracts/README.md` 'U1 구현 범위 메모'에 적음 | Step 23 · 33 | 사람(J112 확장 여부) |
| J198 | 새 설정 키 없이 재사용: 끝내기 명령 대기 상한 = `limits.db-lock-timeout`(2초), 승인 문구 재생 표시 만료 = `output-queue-max`(20초) | Step 23 · 25 | 3.6 |
| J199 | 전사 저장: AI 확정 전 칸은 텍스트를 비움(게이트 전 말을 DB에 쓰지 않음), 판정 때 FINAL, 폐기 구간은 텍스트를 남기고 `approved_for_display` false, 세션 종료 때 열린 아이 칸 FINAL · true, AI 칸 FINAL · false | Step 25 | 사람(BR7.5) |
| J200 | 기록기 상한은 세션별 200행(PD-5 '2행씩 100턴'), 빈 세션 칸은 지운다 | Step 25 | Plan 범위 |
| J201 | DB 장애 때 시한뿐 아니라 모든 메시지를 `timer-tick` 뒤 다시 처리 | Step 23 | Plan 범위 |
| J202 | 앱 종료 신호(ServerShutdown) 때 공급자 연결도 닫는다(T3) | Step 26 | 3.6(RD-13) |
| J203 | Realtime 통합 시험만 `verify-token-timeout` 1초 · `hello-timeout` 2초로 늘림(첫 요청 스키마 검증 지연, I-3 유지). `DeviceGatewayIT`는 운영 값 | Step 30 · 35 | Plan 범위 |
| J204 | DEVICE_STATE는 받고 버린다(최신값 메모리 보관은 쓰는 곳인 U3) | Step 24 | U3 |
| J205 | 계획 밖 Core 웹 파일 4개: `ApiResponses` · `ApiErrorController`(`/error`도 봉투) · `GuardianPrincipal` · `ErrorEnvelopeValve`(Tomcat이 앱 전에 거절한 요청도 봉투, 서비스 포트만) | Step 31 | Plan 범위 |
| J206 | `EventLogger` 반복 병합 키에 로그 수준을 넣었다(INFO 뒤 WARN이 repeat로 묻히던 결함, SD-15). 두 앱 모두 | Step 31 · 33 | Plan 범위 |
| J207 | 코드 발급 IP 한도는 `BootstrapIpLimitFilter`가 확인만, 소비는 `BootstrapService` 한 곳(새 발급 때만, 편차 (k)) | Step 19 · 31 | Plan 범위 |
| J208 | Spring CSRF 필터를 인가 뒤로 옮겨 익명 변경 요청은 401이 먼저, CSRF 토큰은 헤더에서만(XOR 마스킹) | Step 31 | Plan 범위 |
| J209 | 맞는 체인이 없으면 404, 준비 상태 게이트는 `/actuator/`를 건너뜀, 프레임워크 보안 헤더는 끄고(Nginx 담당) `Cache-Control: no-store`만 | Step 31 | Plan 범위 |
| J210 | 오류 code 대응: 405 → 404 NOT_FOUND, UUID 아닌 세션 경로 404, UUID 아닌 연결 보고 기기 id 400 INVALID_ENVELOPE. 같은 이름 쿠키가 둘 이상이면 없는 것으로 봄. 계정 삭제 접수 403은 홈 · 기기 보기 · 끝내기에만 | Step 31 · 32 | 사람 |
| J211 | `UserDetailsServiceAutoConfiguration`을 빼 자동 생성 비밀번호가 로그에 찍히지 않게 함 | Step 31 | Plan 범위 |
| J212 | Core API 시험은 MockMvc 대신 실제 Tomcat(무작위 포트)에 앱 전체를 띄움. CSRF 면제 2번(구글 교환)은 U2 경로라 나머지 4경로만 시험 | Step 34 | Plan 범위 |
| J213 | 계획 밖 Realtime 파일 `InternalBody`(2MB 상한 본문) · `SessionViewQuery`(BR6.15 조립) | Step 33 | Plan 범위 |
| J214 | WS 컨테이너 버퍼는 `ServletContextAware`로 `ServerContainer` 기본값을 건다(크기 = `ws-text-frame-max` × 2 = 16KB, 새 키 없음, J123) | Step 33 | Plan 범위 |
| J215 | Realtime 오류 매핑: DB 장애 · 끝내기 명령 시간 초과 → 503 SERVICE_NOT_READY + Retry-After, 2MB 초과는 `/internal/events` 400 INVALID_ENVELOPE · 그 밖 400 VALIDATION_FAILED, 경로 · 메서드 없음 404 | Step 33 | 사람 |
| J216 | 진행 조회 세부값: `requestedAt` = 행 `created_at`, `uncertain` = `review_needed`, `startedAt`이 비면 `created_at`, null 텍스트는 `""`, `nextSeq`는 `sinceSeq` 아래로 내려가지 않음, SESSION_ITEMS_SERVED는 FINAL 행만 | Step 33 | U4 |
| J217 | 내부 토큰 실패 사건의 컴포넌트는 `/internal/events`면 RealtimeMessaging, 그 밖은 SessionLifecycle. `TraceIdFilter`가 빠져나온 예외를 500 INTERNAL_ERROR 봉투로 닫는 최후 방어선 | Step 33 | Plan 범위 |
| J218 | SD-22 '프레임 경로 DB 접근 0'은 DataSource를 감싸 연결을 연 호출 스택을 검사해 실측(60프레임 세션에서 0건) | Step 35 | Plan 범위 |
| J219 | ArchUnit ④ 프레임 경로에 출력 경로 `gate` 패키지를 더하고, gateway는 프레임 클래스 7개만 대상(`ConnectionHandover` · `ConnectionStateWriter` · `HeartbeatHandler` 제어 경로 제외, J125) | Step 36 | 3.6(SD-22 묶음) |
| J220 | J113 밖 규칙: Core의 `java.net.http.WebSocket` 의존 0, 공급자 요청 레코드는 `ProviderRequestFactory`만 생성, contract-model에 Spring · JPA 애너테이션 · 계층 이름 · Boot 설정 자원 0, ③에 시험 라이브러리 포함, Realtime 질의 문자열에 core 스키마 0 | Step 36 | Plan 범위 |
| J221 | BR6.17은 사본 변경 메서드 호출자 제한(허용 세 클래스) + 소스 리터럴 검사(realtime 스키마 질의 0, `core_activity` 쓰기는 `ActivityCopyRepository`만) | Step 36 | Plan 범위 |
| J222 | 규칙 정의(시각 API 목록 · 리터럴 스캐너)를 세 ArchTest에 복제(공용 시험 모듈 없음, test-support는 대역만) | Step 36 | Plan 범위 |
| J223 | `ConfigKeyContractTest`는 timing.md가 정본인 묶음만 timing.md와 대조하고 limits · security · metrics · observability · dev-login · load-test는 yml ↔ 레코드만. 주소 키 ↔ ports.md, `application-test.yml` 키 ⊆ 레코드 키(오타 감지) | Step 36 | Plan 범위 |
| J224 | 남의 Step 시험 경주 수정(운영 코드 그대로): `SessionLifecycleIT`는 TIMEOUT_FIRED 로그를 기다림, `RealtimeLogHygieneIT`는 20프레임씩 나눠 보냄(L1 50프레임 대기열) | Step 36 | Plan 범위 |
| J225 | 웹 시간 기본값은 `frontend/src/shared/timing/timing-defaults.json` 한 곳, 환경 변수 형식이 틀리면 키 이름만 담아 500. 토스트 시간 키는 계약 밖이라 환경 변수로 바꾸지 않음 | Step 37 | Plan 범위 |
| J226 | AudioWorklet은 받은 표본만 넘기고 비율 변환 · 480표본 조립은 메인 스레드 순수 함수(`resample.ts`), worklet 코드는 같은 origin 라우트 `/device/pcm-capture-worklet`(J121 · J138 구체화) | Step 38 | 3.6 실기(X-6) |
| J227 | `'strict-dynamic'`을 모든 경로에 유지하고, 실기에서 worklet 적재가 막히면 `STRICT_DYNAMIC_EXCLUDED_PATH_PREFIXES`에 `/device`만 더하는 되돌림 장치 | Step 37 | 3.6 실기(X-6) |
| J228 | 기기 REST에는 클라이언트 시간 제한이 없다(계약 키 없음). `mujoong.device-web.request-timeout` 같은 키를 더할 후보 | Step 38 | 사람(계약) |
| J229 | 목록에 없는 contentCode의 PLAY_APPROVED_CONTENT는 재생하지 않고 PLAYBACK_MARK FAILED(FETCH_FAILED)를 보낸다(서버가 30초 기다리지 않게, BR7.6 해석) | Step 38 | 사람 |
| J230 | 17은 종료 상태를 받을 때까지 같은 주기로 다시 읽는다(표 2.9-A '진입 때 1회'와 다름, '마무리 중 → 종료' 전환) | Step 40 | 사람 |
| J231 | 화면 범위: 04는 제목 · 대화 기기 구역 · 탭만, 12는 아이 이름을 캐시된 홈 응답에서만 읽음, 12에서 탭을 누르면 머묾, P1 끝내기 401은 9-18 토스트 뒤 시험 로그인 | Step 40 | U2 · U4 |
| J232 | 08 idempotencyKey는 표 2.9-C 조건에 더해 본문이 같을 때만 재사용(입력을 고치면 새 키) | Step 40 | Plan 범위 |
| J233 | 계획 밖 프런트 파일 13개: `DeviceApp.tsx` · `runtime/deviceRuntime.ts` · `ws/deviceSession.ts` · `audio/audioContext.ts` · `power/wakeLock.ts` · worklet 라우트 · `ParentProviders.tsx` · `navigation.ts` · `copy/timeText.ts` · `timing-defaults.json` · CSS 3개 | Step 37 ~ 40 | Plan 범위 |
| J234 | 입력 프레임 순번은 세션마다 1부터(계약에 범위 없음) | Step 38 | Plan 범위 |
| J235 | `particle.ts`의 '와/과' 짝 순서 결함('민준와')을 고침. 지금 화면은 '이/가'만 써 보이는 영향 없음 | Step 41 | Plan 범위 |
| J236 | 계획 밖 프런트 시험 8파일 · `tests/u1/support/` 9파일. `capture.test.ts` · `mic-permission.test.ts`는 계획 이름(.ts) 유지, `eslint-boundary.test.ts`만 node 환경, `timing-defaults.json` · `TEMP_COPY.errors` 29개를 계약 파일과 자동 대조 | Step 41 · 42 | Plan 범위 |
| J237 | 5초 재접속 뒤 T19는 '대기'에서 시작(세션 이어가기는 U3) | Step 42 | U3 |
| J238 | 계획 밖 `infra/ci/build-images.sh`: 6종 빌드 · 라벨 한곳, 산출물이 소스보다 오래되면 실패, 지문을 빌드 앞뒤로 재서 다르면 실패 | Step 43 | Plan 범위 |
| J239 | 소스 지문은 실행 단위 디렉터리 6곳(backend · frontend · infra · tools · contracts · scripts)의 내용 기준이고 HEAD를 넣지 않는다(J134 문구 'HEAD + diff + 미추적'과 다름). 감사 기록 · 문서만 바뀌면 지문이 그대로 | Step 43 · 49 | 사람(J134) |
| J240 | backup 이미지의 supercronic은 BuildKit `ADD --checksum`으로 받아 RUN 단계 네트워크 0(R-03). `--extra-ca` 비밀 마운트 경로는 만들어 두었으나 지금은 안 씀 | Step 43 | Plan 범위 |
| J241 | Compose 경로 변수: demo env 파일 `${MUJOONG_ENV_DIR:-/opt/mujoong/env}`, ci 산출물 `${MUJOONG_VERIFY_DIR:-../build/verify}`(상대 경로는 `infra/` 기준) | Step 44 | Plan 범위 |
| J242 | postgres 헬스체크는 127.0.0.1이 아니라 자기 backend 주소로 `pg_isready`(127.0.0.1은 pg_hba 거부 로그가 5초마다 쌓임, infra-spec 2.3과 다름) | Step 44 | 사람(infra-spec) |
| J243 | `infra-validate`에 `demo-profile`(CD-7 D3 재사용) · `secret-env-files`(비밀은 env 파일로만, 서비스마다 자기 파일) 검사와 샘플을 더함 | Step 48 | Plan 범위 |
| J244 | neo4j 자리 값 `neo4j:5.26.31-community` · user 7474 · `env_file required: false`(켤 때 확정, LC-10) | Step 44 | 켤 때 |
| J245 | 이미지 점검 ④는 demo + ci + 실행 때 만든 덧붙임으로 demo 프로필 기동, ⑧은 시험 망 172.30.99.0/24 + Nginx 대역 컨테이너 | Step 48 | Plan 범위 |
| J246 | `render-nginx --src`, 접근 로그 메서드는 원래 요청 줄에서(오류 페이지 내부 이동 때 POST가 GET으로 찍히던 문제), backup Dockerfile이 `/backups` · `/var/log/mujoong`을 uid 999로 미리 만듦 | Step 44 · 43 | Plan 범위 |
| J247 | 계획 밖 시험 `tools/tests/u1/device-simulator.test.mjs`(J143 HEARTBEAT `sentAt` · 즉시 ACK · DEVICE_READY · 재생 표식) | Step 46 | Plan 범위 |
| J248 | 보조 모듈 `tools/lib/jsonlTail.mjs`(기록 따라 읽기) · `tools/skeleton-e2e/deadlines.mjs`(application.yml 시간 키로 기한 계산: REGISTERED 30 · STABLE 90 · ACTIVE 30 · 턴 30 · ENDED 60초) | Step 45 | Plan 범위 |
| J249 | `run-c.mjs` 필수 인자 `--provider-record` · `--realtime-log` · `--sql-cmd`(core · realtime, 출력은 `psql -At`에 세로 막대 구분자 꼴) | Step 45 | Plan 범위 |
| J250 | dev-edge는 모르는 SNI · Host를 연결 끊기로 거절(Nginx는 `unrecognized_name` · 404). '거절' 판정은 같다 | Step 45 | Plan 범위 |
| J251 | 가짜 공급자 이미지 베이스 `node:22.22.0-bookworm-slim`(web은 `node:22.22.3-alpine3.23`) | Step 45 | Plan 범위 |
| J252 | 계획 밖 `scripts/verify/HostMigrate.java`: host 모드에 Flyway CLI가 없어 core-api bootJar 안 flyway-core 12.4.0으로 `infra/migrate/flyway-*.conf`를 그대로 적용(SQL 경로만 바꿈) | Step 49 | Plan 범위 |
| J253 | 작업 트리 비교에서 감사 기록 조각 `aidlc/spaces/*/intents/*/audit/**`를 빼고, 대신 diff 내용 해시 · 미추적 파일 내용 해시를 더해 계획보다 엄격하게 | Step 49 | Plan 범위 |
| J254 | P3(값 대조)에서 `tools/fake-provider/scenarios.mjs`의 합성 대본 문장 4개를 뺀다(정의 파일에 있는 것은 누출이 아님). P2 로그 위생에서는 그대로 찾음 | Step 49 | Plan 범위 |
| J255 | a-secrets의 키 형식 grep(①)은 줄 끝 `gitleaks:allow` 표지를 따른다(③ 값 대조에는 예외 없음) | Step 49 | Plan 범위 |
| J256 | B5 프로필 가드 이미지 기동은 migrate 뒤 · `up` 전, core-api · realtime 병렬(`compose run`이 고정 IP를 써 실행 중 서비스와 겹침) | Step 49 | Plan 범위 |
| J257 | SNI 거절은 `openssl s_client`로 보고 localhost CA 검증 통과 대조를 함께 둔다. `Host: other.invalid`는 2xx · 3xx가 아니면 통과 | Step 49 | Plan 범위 |
| J258 | auto 모드는 postgres 고정 태그가 로컬에 있으면 pull 성공과 같이 본다(엔진 실행이 네트워크에 기대지 않게) | Step 49 | Plan 범위 |
| J259 | `--deadline <초>` 추가(기본 전체 5400초 · `--engine` 110초, 기한만 줄이고 건너뛰기 아님), 인자 오류 64 | Step 49 | Plan 범위 |
| J260 | host 모드 세부: PG 클러스터 `/tmp/mujoong-verify-pg.*`(postgres 사용자가 지나갈 수 있는 경로), `MUJOONG_PG_BIN`, 프로세스는 환경 표지 `MUJOONG_VERIFY_SPAWN`으로 정리(Next.js가 프로세스 제목을 바꿈) | Step 49 | Plan 범위 |
| J261 | `build/verify/fingerprint`에 소스 지문과 bootJar 두 개 · `server.js` · `BUILD_ID`의 sha256을 적는다(두 모드 모두 F 단계) | Step 49 | Plan 범위 |
| J262 | verify가 만드는 이미지는 스택 5종(core-api · realtime · web · migrate · fake-provider). backup은 스택에서 꺼져 있고 번들은 CI images 작업 | Step 49 | Plan 범위 |
| J263 | A2가 `next build` 뒤 `.next/static`을 standalone에 복사(정적 자원 404 방지) | Step 49 | Plan 범위 |
| J264 | `gen-selfsigned.sh`는 root가 아닐 때 키를 0644로 둔다(GitHub 러너 nginx 마스터가 0600 키를 못 읽음, 버림 키 · 끝에 지움) | Step 49 | Plan 범위 |
| J265 | P2 대조 대상을 compose.log · `report.txt` · 표준 출력 · B · C · P1 단계 로그 · run-c 출력으로 넓힘 | Step 49 | Plan 범위 |
| J266 | run-c의 SQL 래퍼는 `docker exec --env-file`(P1은 계획대로 일회성 컨테이너) | Step 49 | Plan 범위 |
| J267 | 다른 단계 파일 `frontend/tests/u1/shared/csp.test.ts` 한 줄(자격 든 origin 거부 시험 값)에 `// gitleaks:allow`를 붙임 | Step 49 | Plan 범위 |
| J268 | 호스트 기준선 파일을 인프라 번들에 싣는다(`infra/bundle/Dockerfile`의 `COPY host/`, dockerignore `!host`, infra-validate bundle 검사). D9가 진입점 갱신 · 기준선 차이 경고 | Step 47 | 클라우드 담당 A |
| J269 | 계획 밖 `infra/bin/lib/host.sh`(공용 함수) · `ci/security-gate.py`(예외 목록 검사 · trivyignore 생성 · npm audit 판정) | Step 47 · 50 | Plan 범위 |
| J270 | 배포 D3을 둘로: stage1이 D3a(GHCR 로그인 · 번들 받기 · 풀기), apply가 D3b(대상 릴리스 도구로 render-env · validate · pull · 동기화) | Step 47 | 클라우드 담당 A |
| J271 | 배포 pull은 `--policy missing`(SHA 태그는 불변, Docker Hub 429 실측) | Step 47 | 클라우드 담당 A |
| J272 | `probe-three`의 WSS는 101 뒤 가린 닫기 프레임(1000) 하나를 보내고 끊는다(TCP만 끊으면 realtime이 프로브마다 EOFException ERROR, A-7 · MO-2 오탐) | Step 47 | 3.6 |
| J273 | D6에서 backup · certbot을 다시 만든 뒤 healthy까지 최대 100초 대기(D8 8-5가 `starting`을 만나 실패한 리허설 결과) | Step 47 | 3.6 |
| J274 | CI backend-deps의 trivy에는 `--ignore-unfixed`를 넣지 않았다(image-checks ③에는 있음, 더 엄격). 고칠 판이 없는 항목은 예외 목록에 올려야 통과 | Step 50 | 사람 |
| J275 | CI 실행 범위: secrets 작업은 경로 필터와 상관없이 항상, frontend-deps가 `tools/` npm audit도, PR 아닌 실행의 concurrency 그룹은 실행마다 따로(main 푸시끼리 취소 안 함) | Step 50 | 클라우드 담당 A |
| J276 | 액션 SHA는 `gh api`가 세션 정책 403이라 git으로 직접 받아 커밋 객체 · ls-remote 태그와 대조. 나온 지 2주가 안 된 판(setup-node v7.1.0 · upload-artifact v7.0.2)은 뺌 | Step 50 | Plan 범위 |
| J277 | braces 예외(GHSA-vfj7-8cjw-p6xm, dev 의존성 high 5건의 출처)를 `ci/security-exceptions.yml`에 `status: candidate` · 만료 2026-11-30으로 둠 | Step 50 | 사람(승인) |
| J278 | RUNBOOK의 팀 연락 목록 위치는 '미정'(담당 J104의 A · 팀장) | Step 47 | 팀장 |
| J279 | 리허설 · 시험용 경로 환경 변수 재정의(`MUJOONG_OPT` · `MUJOONG_LOG_DIR` 등). 기본값은 계획의 경로 | Step 47 | Plan 범위 |
| J280 | `.gitleaks.toml` 허용 목록 2건은 시험 픽스처 줄만 줄 단위로 허용 | Step 50 | Plan 범위 |
| J281 | `contracts/README.md`에 '구현 대조' 표와 'U1 구현 범위 메모'를 더하고, U1이 아직 받지 않는 값의 거절 · 계약이 비워 둔 세부(J197 · J210 · J215)는 편차 기호를 늘리지 않고 메모로 남겼다 | Step 52 | 사람(J112 확장 여부) |
| J282 | `source-manifest.json`은 생성기 트리 `backend/gradle/wrapper/` · `infra/seed-audio/`를 접두 claim으로, 경로에 `[` `]`가 있어 엔진이 파일 claim을 거부하는 Next.js 동적 라우트는 `frontend/src/app/parent/sessions/` 접두 claim으로 적었다(760항목) | Step 53 | Plan 범위 |
| J283 | `coverage-scope` 내용 규칙: backend는 `src/main/java/**/*.java`(test-support 9줄 포함, 하한 판정에는 쓰이지 않음), frontend는 `src/**/*.{ts,tsx}`(`*.d.ts` 제외). 자원 · CSS · JSON · build-logic은 클래스 · 실행 줄이 없어 넣지 않음 | Step 53 | Plan 범위 |
| J284 | `traceability.json`의 NFR 중 U1이 일부만 구현한 NFR4.15 · NFR7.6 · NFR7.8은 OK가 아니라 Deferred로 두고 빠진 지표를 사유에 적었다(## 알려진 틈 지표 틈). 측정이 11/2 · 3.6 몫인 성능 · 실기 NFR도 Deferred | Step 53 | 사람 |

## FD 보강 A1 ~ A6과 3.3 추가 동작 반영 목록

승인 문서(`rules.md` · `entities.md` · `contract-summary.md`) 본문은 고치지 않고 코드 · 시험 · 이 목록으로 반영했다(J130). 경로 약어: `M/` =
`backend/contract-model/src/main/java/kr/mujoong/contract/`, `C/` = `backend/core-api/src/main/java/kr/mujoong/core/`, `R/` =
`backend/realtime-activity/src/main/java/kr/mujoong/realtime/`. 시험은 클래스 이름이고 위치는 ## 시험 · 커버리지의 시험 위치 요약을 따른다.

| 항목 | 반영 위치(파일) | 시험 | 남은 것 |
|---|---|---|---|
| A1 FAILED 제어 이벤트 재무장(`REARM_ON_START`, 일반 이벤트 20회 상한 유지, `RETRY_FOREVER`는 기동 실패 J124) | `C/messaging/ControlEventRearm.java` · `C/startup/CoreStartupSequence.java`(S3d) · `R/messaging/ControlEventRearm.java` · `R/startup/RealtimeStartupSequence.java` · 두 앱 `application.yml` `mujoong.outbox.control-event-policy` | `CoreOutboxSenderTest` · `CoreMessagingFlowIT` · `DeletionParticipantsCoreIT` · `RealtimeMessagingIT` | 8분 재시도 시나리오(RD-10)는 3.6 |
| A2 시험 로그인 거부를 demo · prod로(SD-14 13조합) | `M/profile/ProfileGuardRules.java` · `C/startup/ProfileGuardEnvironmentPostProcessor.java` · `R/startup/ProfileGuardEnvironmentPostProcessor.java` · `C/identity/DevLoginController.java` · `C/security/DevLoginBlockedRecorder.java` · 두 앱 `META-INF/spring.factories` | `ProfileGuardRulesTest` · 두 앱 `ProfileGuardEnvironmentPostProcessorTest` · 두 앱 `ProfileGuardBootIT` · `RealtimeStartupChecksTest` · `GuardianApiIT` · `DevSeedRunnerIT` · 검증 명령 B5(이미지 기동 거부) | — |
| A3 Outbox/Inbox 네 표 `trace_id` | `V1__core_base.sql` · `V1__realtime_base.sql` · `C/messaging/CoreOutboxWriter.java` · `C/messaging/CoreInboxReceiver.java` · `R/messaging/RealtimeOutboxWriter.java` · `R/messaging/RealtimeInboxReceiver.java` · `M/event/EventEnvelope.java` | `CoreSchemaIT` · `RealtimeSchemaIT` · `CoreConstraintIT` · `CoreMessagingRepositoryIT` · `CoreMessagingFlowIT` | — |
| A4 모든 Outbox/Inbox 행 `guardian_id`(DeviceStatusChanged는 payload.guardianId 우선) | 두 앱 `*OutboxWriter`(인자 필수) · `*InboxReceiver` · `C/messaging/handler/DeviceStatusChangedHandler.java` · `R/gateway/ConnectionStateWriter.java` · 삭제 참가자 | `CoreMessagingRepositoryIT` · `RealtimeMessagingIT` · `DeletionParticipantsCoreIT` | — |
| A5 시드 사전 7범주 각 1항목 이상, 비면 기동 거부 | `backend/realtime-activity/src/main/resources/gate/seed-dictionary.json` · `R/gate/SeedDictionary.java` · `R/startup/RealtimeStartupChecks.java` | `SeedDictionaryTest` · `SeedDictionaryFileTest` · `RealtimeStartupChecksTest` · `OutputGateTest` · `SessionLifecycleIT` | 정식 사전 v1(OQ15 · D 담당), ## 알려진 틈 1 |
| A6 부하 시험 전용 시드 · `load-test` 프로필(demo · prod와 함께면 거부) | `M/profile/ProfileGuardRules.java`(조합 9 · 12 · 13) · `C/security/ratelimit/SlidingWindowRateLimiter.java` · `backend/core-api/src/main/resources/application-load-test.yml`(demo · prod 파일에는 상향 값 없음) | `ProfileGuardRulesTest` · `SlidingWindowRateLimiterTest` · core `ConfigKeyContractTest` | 부하 시드 도구 · L1 ~ L7 · PD-18은 3.6 · 11/2 전(R3-21) |

**3.3 설계가 더한 동작과 A1 ~ A6 밖 후보**

| 항목 | 반영 위치 | 시험 |
|---|---|---|
| Outbox `claim-lease` 30초 선점과 S3f 선점 해제(BR5.2 · BR5.12 보강) | `C/messaging/CoreOutboxRepository.java` · `R/messaging/RealtimeOutboxRepository.java` · 두 앱 `*StartupSequence` | `CoreMessagingRepositoryIT` · `CoreStartupSequenceIT` · `RealtimeStartupRecoveryIT` |
| BR5.14 (a)의 예외: FAILED 제어 이벤트는 지우지 않고 재무장 | `C/messaging/CoreMessagingDeletionParticipant.java` · `R/messaging/RealtimeMessagingDeletionParticipant.java` | `DeletionParticipantsCoreIT` · `RealtimeMessagingIT` |
| SERVER_RESTART를 고아 세션 점검 · 실행기 예외에도 사용(J126) | `R/session/OrphanSessionChecker.java` · `R/session/SessionExecutor.java` | `SessionExecutorTest` · `RealtimeStartupRecoveryIT` |
| BR8.7보다 엄격: 등록 코드는 앞 4자도 로그에 쓰지 않음 | `C/observability/EventLogger.java`(허용 attrs에 codePrefix 없음) | `CoreLogHygieneIT` |

## 계약 편차 (a) ~ (y) 반영 목록

모두 '더하기만'이고 `contracts/deviations.md`에 한 행씩, 각 스펙 파일에 `x-deviation` 또는 `[편차 (기호)]`로 표시했다. `ContractFilesTest`가 25개 모두를
단언한다. U1 구현 범위 메모(편차 아님)는 `contracts/README.md` 끝 절에 있다(J281).

| 편차 | 반영 위치(파일) | 시험 |
|---|---|---|
| (a) bootstrap 409 ALREADY_REGISTERED | `contracts/device-api.yaml` · `C/device/BootstrapService.java` · `C/device/web/DeviceBootstrapController.java` | `DeviceBootstrapApiIT` · `device-machine.test.ts` |
| (b) claim 401 BOOTSTRAP_NOT_FOUND | `contracts/device-api.yaml` · `C/device/DeviceClaimService.java` | `DeviceBootstrapApiIT` · `device-machine.test.ts` |
| (c) 503 CODE_ISSUE_FAILED | `contracts/device-api.yaml` · `C/device/CodeGenerator.java` | `CodeGeneratorTest` · `DeviceBootstrapApiIT` |
| (d) 400 VALIDATION_FAILED, 정규화 뒤 영숫자 8자 · 알파벳 밖 404 | `contracts/parent-api.yaml` · `C/device/RegistrationCode.java` · `C/activity/web/ActivityController.java` | `RegistrationCodeTest` · `DeviceRegisterApiIT` · `GuardianApiIT` |
| (e) 403 CSRF_TOKEN_INVALID | `contracts/parent-api.yaml` · `contracts/errors.md` · `C/security/SecurityConfig.java` | `SecurityChainIT` · `api-client.test.ts` |
| (f) 내부 end reason CORE_ABANDONED | `contracts/internal-api.yaml` · `C/activity/RealtimeSessionClient.java` · `R/session/EndRequestHandler.java` | `ActivityStartEndIT` · `RealtimeInternalApiIT` · `SessionLifecycleIT` |
| (g) 6절 순서 문장 since → occurredAt | `contracts/events.asyncapi.yaml` · `C/messaging/handler/DeviceStatusChangedHandler.java` | `CoreMessagingFlowIT` |
| (h) device-ack-timeout 20초를 PROVIDER_UNAVAILABLE에도 | `contracts/timing.md` · `contracts/device-ws.asyncapi.yaml` · `R/session/SessionTimers.java` | `SessionTimersTest` |
| (i) connection PUT의 state · reportedAt 필수 | `contracts/internal-api.yaml` · `C/device/web/DeviceInternalController.java` | `CoreInternalApiIT` |
| (j) verify-token 결과 캐시 없음 | `contracts/internal-api.yaml` · `contracts/device-ws.asyncapi.yaml` · `R/gateway/VerifyTokenClient.java` | `CoreInternalApiIT` · `DeviceGatewayIT` |
| (k) rate-limit은 같은 쿠키 반복을 세지 않음 | `contracts/device-api.yaml` · `C/web/BootstrapIpLimitFilter.java` · `C/device/BootstrapService.java` | `SlidingWindowRateLimiterTest` · `DeviceBootstrapApiIT` |
| (l) 연결 대체 시점 = HELLO_OK | `contracts/device-ws.asyncapi.yaml` · `R/gateway/ConnectionHandover.java` | `DeviceGatewayIT` |
| (m) 같은 보호자 등록 재시도는 첫 성공과 같은 201 | `contracts/parent-api.yaml` · `C/device/RegistrationService.java` | `RegistrationDecisionTest` · `DeviceRegisterApiIT` |
| (n) 503 SERVICE_NOT_READY + Retry-After | 모든 OpenAPI · `C/web/ReadinessGateFilter.java` · `R/web/ReadinessGateFilter.java` | `HealthAndReadinessIT` · `RealtimeInternalApiIT` |
| (o) 500 INTERNAL_ERROR | `contracts/errors.md` · `C/web/ApiExceptionHandler.java` · `R/web/ApiExceptionHandler.java` | `ErrorEnvelopeAndLimitsIT` |
| (p) 이벤트 봉투 선택 필드 traceId · 네 표 trace_id | `contracts/events.asyncapi.yaml` · `contracts/schemas/event-envelope.schema.json` · V1 SQL 두 개 | `EventEnvelopeValidationTest` · `CoreMessagingFlowIT` |
| (q) DeviceStatusChanged 선택 필드 guardianId | `contracts/events.asyncapi.yaml` · `R/gateway/ConnectionStateWriter.java` | `RealtimeMessagingIT` |
| (r) 본문 64KB 초과는 400 VALIDATION_FAILED | `contracts/parent-api.yaml` · `C/web/RestBodyLimitFilter.java` · `infra/nginx/conf.d/https443.conf.tmpl`(`error_page 413 =400`) | `ErrorEnvelopeAndLimitsIT` · 검증 명령 B4.body64k · `infra-validate` |
| (s) WSS 규정 외 프레임은 4400 닫기(범위 J123) | `contracts/device-ws.asyncapi.yaml` · `R/gateway/WsFrameGuard.java` | `WsFrameGuardTest` · `DeviceGatewayIT` |
| (t) 공개 `GET /api/health` | `contracts/parent-api.yaml` · `C/web/HealthController.java` · `infra/nginx/conf.d/https443.conf.tmpl`(`location = /api/health`) | `HealthAndReadinessIT` · 검증 명령 B4.health |
| (u) `GET /api/session/csrf` → `{token, headerName}`, no-store, 익명 401 | `contracts/parent-api.yaml` · `C/web/CsrfController.java` | `GuardianApiIT` · `SecurityChainIT` |
| (v) RT `POST /internal/sessions` 409 SESSION_NOT_ACTIVE(10분 묘비) | `contracts/internal-api.yaml` · `R/session/StartSessionHandler.java` · `R/session/TombstoneMap.java` | `RealtimeInternalApiIT` |
| (w) 모르는 sessionId의 GUARDIAN_STOP end → 409 SESSION_NOT_ACTIVE(J111) | `contracts/internal-api.yaml` · `R/session/EndRequestHandler.java` | `RealtimeInternalApiIT` · `p1-dialog.test.tsx` |
| (x) C13 `owner_role: core_app`은 쓰는 역할, 표 소유는 `mujoong_owner` | `contracts/README.md` · `infra/postgres/init/01-roles.sql` | `SchemaPrivilegeIT` · 검증 명령 P1 |
| (y) Nginx 502 · 504 JSON 봉투 code `UPSTREAM_UNAVAILABLE`(상태 유지) | `contracts/errors.md` · `infra/nginx/snippets/error-json.conf` | `infra-validate`(정적) · `infra/ci/image-checks.sh` 점검 ⑧(업스트림 정지 시 502 유지, Step 48 실행) |

## Step 1 판 표

확인 결과는 승인 문서 tech-stack-decisions.md 2.2절이 아니라 이 표에 둔다(J140 · J130). tech-stack 2.2 갱신은 사람이 지시할 때 한다.

| 항목 | 판 · 결과 | 근거 |
|---|---|---|
| JDK | OpenJDK 21.0.12.1, 툴체인 `/usr/lib/jvm/java-21-openjdk-amd64`(자동 받기 끔) | Step 1 · 3 |
| Gradle | 래퍼 8.14.3(설치판 · 공식 sha256), 9.x 전환은 사람이 정함 | J139 · J145 |
| Spring Boot | **4.1.1 유지**. J139 판정: ① 두 앱 runtime · testRuntime 해석 FAILED 0 ② Session JDBC 4.1.1 · Flyway 12.4.0 + flyway-database-postgresql 12.4.0 · springdoc 3.1.1 · Testcontainers 2.0.5 · ArchUnit 1.5.1 · JaCoCo 0.8.14 해석 ③ bootJar 성공. 확정 조건(기동 · Security · Session JDBC · JPA 동작)은 Step 28 · 34 시험과 Step 51 검증 명령(이미지 기동 · 시험 로그인 · Session JDBC · CSRF · Origin · JPA validate 끝-끝)에서 확인 | J139 |
| Spring Boot 3.5.x OSS 지원 종료일 | 미확인(api.spring.io 응답이 JSON이 아님). 전환할 일이 없어 묻지 않음 | Step 1 |
| 그 밖 백엔드 | JUnit Platform launcher 6.0.3(Boot BOM), Jackson 3.1.5(`tools.jackson`, 직접 import는 `M/json/JsonCodec.java` 한 곳), networknt json-schema-validator 3.0.6, Java-WebSocket 1.6.0(시험 전용), Micrometer Prometheus(Boot BOM) | J139 · J152 |
| 공급자 Java SDK | 미확인. SDK 없이 JDK `java.net.http.WebSocket`으로 시작(J110), 실제 형식 차이는 11/2 한 턴에서 어댑터만 고침(V-2) | J110 |
| Node · npm | Node 22.22.0 · npm 10.9.4(설치는 npx npm@11.19.1, `npm ci`는 npm 10 확인) | J155 |
| 프런트 | Next.js 16.4.0(미들웨어 파일은 `frontend/src/proxy.ts`, J122) · React 19.3.0 · TanStack Query 5.104.0 · TypeScript 5.9.3 · ESLint 9.39.5 · eslint-config-next 16.4.0 · Vitest 4.1.11 · @vitest/coverage-v8 4.1.11 · jsdom 29.1.1 · @testing-library/react 16.3.3 · jest-dom 6.10.0 · user-event 14.6.7 · vite 7.3.6, browserslist Safari 16.4 | J155 |
| tools | Node 22 ESM, ws 8.21.3, Vitest 4.1.11 | J129 |
| Docker · Compose | Engine 29.8.2(데몬은 손으로 기동) · Compose v5.6.0(`!override`, J136의 v2.24.4 이상 충족), BuildKit · buildx | Step 1 · 43 |
| 기본 이미지(고정 태그 · 다이제스트 앞부분) | `postgres:16.15`(ca0bd484…) · `nginx:1.30.5-alpine`(0985e772…) · `certbot/certbot:v5.8.0`(f70ad0ad…) · `eclipse-temurin:21.0.12_8-jre-noble`(7739f0ff…) · `node:22.22.3-alpine3.23`(968df39a…) · `flyway/flyway:12.4.0`(5be18367…), 가짜 공급자 `node:22.22.0-bookworm-slim`, neo4j 자리 `5.26.31-community`(꺼짐) | Step 43 · J244 · J251 |
| PostgreSQL 호스트 | 클라이언트 · 서버 16.15(`/usr/lib/postgresql/16/bin`), J132 local 백엔드 · J133 host 모드에 사용 | Step 1 |
| 정적 검사 도구 | shellcheck 0.11.0(저장소 밖 설치), actionlint 1.7.12(이미지), logrotate(apt, 리허설용) | Step 44 · 47 · 50 |
| 레지스트리 접근 | Maven Central(429 → 공식 미러, J146) · plugins.gradle.org · registry.npmjs.org 200, `downloads.gradle.org` 403(J145), Docker Hub 이미지 pull 성공(가끔 429, J271) | Step 1 · 3 |

## 검증 명령 결과

명령 · 단계 · 종료 코드 규칙은 계획 ## 검증 명령(C안)과 `bash scripts/verify.sh --help`, 검증 스택 운영은 `infra/RUNBOOK.md` 17절에 있다. 아래 실행은 사람
확인용 수동 실행이고 엔진 checkpoint 증거가 아니다. 엔진 검증은 verification-command 체크포인트에서 Approve된 명령으로만 한다(J134).

**Step 51 실행(2026-10-10, 사람 결정 10 · 11)**

| 실행 | 모드 | 종료 코드 | 시간 | 단계별(초) |
|---|---|---|---|---|
| `bash scripts/verify.sh` | compose(auto) | 0 | 내부 339초 · 실측 341초 | A1 180 · A2 58 · A3 14 · A4 0 · F 1 · B 66 · C 16 · P1 1 · P2 0 · P3 0 · T 1 |
| `bash scripts/verify.sh --engine` 1 | compose | 0 | 67.8초(내부 65) | E0 0 · B 47 · C 15 · P1 1 · P2 1 · P3 0 · T 0 |
| `bash scripts/verify.sh --engine` 2 | compose | 0 | 69.5초(내부 68) | B 49 · C 15 |
| `bash scripts/verify.sh --engine` 3 | compose | 0 | 67.4초(내부 65) | B 46 · C 15 |
| `bash scripts/verify.sh --mode host` | host | 0 | 내부 279초 · 실측 281초 | A1 166(DB 백엔드 local) · A2 58 · A3 15 · A4 0 · F 1 · B 23 · C 15 · P1 0 · P2 0 · P3 0 · T 0 |
| `bash scripts/verify.sh --engine` 4(host 실행 뒤) | compose | 0 | 62.8초 | — |

- 소스 지문(Step 51 시작): `sha256:3b7f672de7cfd81ccafc7a5e96bbc3be14493401568abeb7a729adb1b490e338`. `--engine` 출력은 회마다 605바이트(엔진 1 MiB 제한과 거리가 멂).
- compose 전체 실행의 단계 결과: A1 시험 1,196건 · 실패 0 · JaCoCo 80% · bootJar 두 개에 시험 클래스 0 / A2 eslint · vitest(줄 98.82%) · next build /
  A4 키 형식 9종 · 파일 1,334개 걸림 0(gitleaks 이력 스캔은 CI 몫 NOTE) / B 이미지 5종 · 병합 설정 단언 5 · postgres · migrate · 시드 멱등(정책 1 · 문구 2) ·
  프로필 가드 이미지 기동 거부 4건 · 3종 호출 · SD-3 10건 / C C1 ~ C12(C9 2.2초 · C10 5.0초 · C11 4.5초) / P1 42501 6건 / P2 STARTUP_RECOVERY · ENDED ·
  LATENCY_TURN · Nginx 접근 로그 27줄, 비밀 값 28개 × 대상 7개 파일 일치 0 / P3 걸림 0 / T 작업 트리 시작과 같음.
- host 실행의 `report.txt`에 남은 줄(그대로 둔다, J133): `mode=host`, `NOT_VERIFIED(host)` 5줄 — 이미지 6종 빌드 · 라벨 · 비루트 · 시험 클래스 0, Compose 병합
  설정 단언 B1, Nginx 렌더 설정 · `nginx -t` · 접근 로그 줄, 망 격리, 프로필 가드의 이미지 기동 거부(host 모드는 같은 bootJar를 `java -jar`로 확인).
  `B4.host`는 dev-edge가 연결을 끊어 상태 000으로 거절(J250).
- 매 실행 뒤 컨테이너 · 망 · 볼륨 · 생성 비밀 · host 프로세스 · `/tmp/mujoong-verify-pg.*`는 0건이었다.
- Step 49의 앞선 네 번은 1회 F · 3회 T 단언이 다른 에이전트의 동시 쓰기를 잡았고(설계대로), 2회는 scratchpad 사본 경로(0700)를 postgres 사용자가
  지나가지 못해 A1 local 3건이 실패했다(실행 위치 문제). 4회째 종료 코드 0(362초).

**Step 53 뒤 재실행(2026-10-10T18:46Z ~).** Step 52 · 53이 `contracts/` · `infra/` · `backend/coverage-scope/` · `frontend/coverage-scope/`를 고쳐 소스
지문이 바뀌었으므로 전체 실행으로 이미지 라벨 · `build/verify/fingerprint`를 다시 만들고 엔진 명령을 한 번 더 돌렸다. 이 뒤로 소스 6곳을 고치지 않았다.

| 실행 | 모드 | 종료 코드 | 시간 | 단계별(초) |
|---|---|---|---|---|
| `bash scripts/verify.sh` | compose(auto) | 0 | 내부 341초 · 실측 343초 | A1 177(1,196건 · 실패 0) · A2 62(줄 98.82%) · A3 14 · A4 0(파일 1,337개 걸림 0) · F 1 · B 69 · C 14 · P1 2 · P2 0 · P3 0 · T 0 |
| `bash scripts/verify.sh --engine` | compose | 0 | 70.1초(내부 68) | E0 1 · B 48 · C 16 · P1 1 · P2 0 · P3 1 · T 0, 출력 581바이트 |

- 소스 지문(이 실행): `sha256:b42ff98a22676bfe140521ce2b846723bd93dd2a320b9f6e09e95999748c502b`. 지금 로컬 이미지 라벨과 `build/verify/fingerprint`가 이 값이다.
  backend · frontend · infra · tools · contracts · scripts 중 무엇이든 바뀌면 `--engine`은 E0에서 종료 코드 1이므로 전체 실행을 먼저 다시 돌린다.
- C1 ~ C12 모두 PASS(C9 2.2초 · C10 5.0초 · C11 4.5초), B4 SD-3 단언 10건 · B5 프로필 가드 4건 PASS. 실행 뒤 남은 컨테이너 0.

## 임시 문구 목록(J118)

문구표 · mockups 9절에 글이 없어 3.5가 둔 문구는 `frontend/src/shared/copy/ko.ts`의 `TEMP_COPY` 한 곳과 `contracts/errors.md`에만 있다. 화면에
'(문구 미정)'은 보이지 않는다. 문구표가 개정되면 이 묶음만 바꾼다.

| 키 | 문구 | 쓰는 곳 |
|---|---|---|
| `lockToastStatusUnknown` | 기기 상태를 확인할 수 없어 시작할 수 없어요 | 04 잠긴 탭 토스트(DEVICE_STATUS_UNKNOWN) |
| `registerTooManyAttempts` | 잠시 뒤 다시 시도해 주세요 | 05a 429 |
| `registerDeviceAlreadyActive` | 이미 연결된 기기가 있어요 | 05a DEVICE_ALREADY_ACTIVE |
| `freeRealtimeUnavailable` | 지금은 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요. | 08 503 |
| `livePreparingEndToast` | 아직 시작하는 중이에요. 시작된 뒤 다시 눌러 주세요. | 12 PREPARING 중 끝내기 |
| `back` · `codeGroupLabel` | 뒤로 · 등록 코드 8자리 | 보조기기 이름(화면 글 아님) |
| `debugLogTitle` | 상태 로그 | `/device?debug=1`(BR9.6, 디버그 전용) |
| `devLogin.*` | 시험 로그인 · 개발 · 시험 환경에서만 쓰는 로그인이에요. · 시험 로그인을 쓸 수 없어요 | `/parent/dev-login`(J119, dev 전용) |
| `errors.*` (29) | `contracts/errors.md`의 code별 고정 message와 같은 문장 | 오류 봉투 code → 화면(BR8.6), 시험이 계약 표와 자동 대조(J236) |
| `networkError` | 잠시 문제가 생겼어요. 다시 시도해 주세요. | 응답 없음 · 시간 초과 · 모르는 code |

Nginx 점검 페이지 `infra/nginx/html/maintenance.html`도 `UPSTREAM_UNAVAILABLE`과 같은 임시 문장을 쓴다.

## 알려진 틈

1. **시드 금지 표현 사전(J101).** 7범주 각 1항목이고 SCORE는 `비정상`(사람 결정 12)이다. `점수` · `정상이에요` · `정상입니다`는 들어 있지 않다. 정식 사전 v1
   (OQ15 · D 담당, 실제 아동 데이터 운영 전 승인 항목)에서 더한다.
2. **지표 틈(OD-5 · OD-6 · OD-12).** 관측 설계 지표 표 중 다음을 구현하지 않았다: `mujoong_device_connection_state`(NFR4.15) · `mujoong_outbox_pending` ·
   `mujoong_outbox_oldest_pending_seconds` · `mujoong_outbox_failed` · `mujoong_inbox_dead` · `mujoong_db_pool_wait_exceeded_total`(NFR7.6) · `mujoong_queue_depth` ·
   `mujoong_session_activated_total` · `mujoong_session_start_to_active_seconds` · `mujoong_relay_out_seconds` · `mujoong_turn_first_audio_seconds` ·
   `mujoong_view_item_ready_seconds`, 그리고 METRIC_SNAPSHOT 60초 로그(사건 코드 · attrs 정의만 있음). 구현된 것은 session_ended · timeout_fired ·
   security_event · internal_http(카운터 · 타이머 · 차단기 게이지) · session_view_fallback · health_up · ws_connections_open · sessions_active · gate_discard ·
   input_frames_dropped · recorder_rejected · rate_limiter · log_invalid_attr · event_delivery · relay_in과 프레임워크 기본 지표(jvm · hikaricp · http_server_requests)다.
   계획의 3.6 넘김 목록은 이 묶음의 '이름 표 · 스냅샷 시험'만 적었으므로 구현 시점은 사람이 정한다(## 사람이 정할 것). `traceability.json`에서 NFR4.15 ·
   NFR7.6 · NFR7.8은 Deferred(J284). → 3.6 수정 라운드에서 해소(## 3.6 수정 라운드 '무엇을 고쳤나' BT-NFR4.15 · 7.6 · 7.8 · BT-OD-06, J295 ~ J303 · J322 ~ J328; `ws_connections_open`은 설계 이름으로 바꿈 J295, traceability NFR4.15 · 7.6 · 7.8 OK).
3. **3.6 · 그 뒤로 넘긴 시험(계획 ## 시험 전략과 커버리지, R3-21).** RD-10 8분 재시도, RD-13 SIGTERM · 정지 주입 리허설, RD-15 · LC-12 장애 격리 주입,
   RD-21(NFR4.16 폰 오프라인), SD-2 브라우저 CSP 콘솔, SD-3 중 B1 필수 밖 Nginx 시험, SD-10 한도기 전체, SD-16 의존성 관문 실측, SD-19(NFR1.3 · NFR2.1),
   SD-21 삭제 지휘 통합(U2), PD-7 캐시, PD-8 · PD-9 EXPLAIN · 질의 수, PD-14 번들 예산, PD-17 · SCD-6 표본 판정, PD-18 부하 시드 도구 · L1 ~ L7, PD-19
   PLAYBACK_MARK_STOPPED, OD-5 · OD-6 · OD-9 · OD-10 · OD-12 이름 표 · 스냅샷, ArchUnit 묶음 나머지(SD-9 · SD-22 전체 · SD-25 구조 · SCD-4 · PD-1 · PD-11 ·
   시간 숫자 상수 검색), 접근성 axe · Lighthouse · 실기 브라우저(NFR10.1 ~ 10.3). → 3.6 수정 라운드에서 일부 해소(## 3.6 수정 라운드: RD-13 SIGTERM `GracefulDrainIT` · IT-06j, SD-16 로컬 실측 ST-19 · ST-20, SCD-4 · 시간 숫자 상수 구조 시험 2종, OD-5 · OD-6 · OD-12 이름 표 · 스냅샷; 나머지는 그대로).
4. **다른 유닛으로 넘긴 동작.** U3: ACTIVE 중 공급자 끊김 감지(지금은 WARN 로그만, 전이 없음) · HELLO resume · 재접속 백오프(U1은 5초 고정) · UNSTABLE 판정 ·
   T19 8상태 · 끼어들기 0.5초 · DEVICE_STATE 최신값 보관. U4: WAITING_CHILD · 참여 확인 · 공백 · CHILD_STOP · `GET /api/sessions/current` · CONSENT_WITHDRAWN 전이 ·
   04 지금 활동 구역 · 12 '(말하는 중)' 표시. U2: 구글 OAuth · 01 · 02 · 03 · 05 화면 · 동의 · 삭제 지휘(CSRF 면제 2번 경로 포함). U5: PRACTICE. AC 27개는
   `traceability.json`에 Deferred와 대상 유닛으로 있다.
5. **연결 끊김 ERROR 기록.** `R/gateway/DeviceWebSocketHandler.java`의 `handleTransportError`가 상대가 그냥 끊은 경우(EOFException)도 `APP_ERROR(ERROR)`로 남긴다.
   실제 기기가 네트워크를 잃을 때마다 A-7 경보 오탐이 생길 수 있다(배포 프로브는 J272로 피함). Step 33 담당 범위로 3.6에서 판단한다.
6. **SD-22 검토 거리.** HELLO 뒤 `ConnectionHandover`(연결당 한 번)와 닫기 때 `ConnectionStateWriter`가 WS 수신 스레드에서 DB 트랜잭션을 돈다. 클래스 단위
   규칙 ④에는 걸리지 않지만 핸들러 주석 '동기 DB 접근 없음'과 어긋난다. `HeartbeatHandler`는 `DeviceConnectionRepository`에 직접 의존한다(J219).
7. **헬스체크 `start_interval`.** backup · certbot 헬스체크에 `start_interval`이 없어 Docker 25 미만에서는 배포 D6 대기가 최대 약 90초가 된다(Docker 29에서 약 15초).
8. **실행하지 못한 것.** GitHub Actions 실제 실행 · OIDC · SSM SendCommand · GHCR 토큰 로그인 · Let's Encrypt 발급 · 실제 EC2 systemd · cron, gitleaks 이력
   스캔 · Trivy(도구 없음, image-checks ③ 미실행). 배포는 샌드박스 리허설(로컬 레지스트리 · 가짜 aws)로만 확인했다: 첫 설치 · 배포 45초(중단 14초) ·
   롤백 42초(17초) · 재배포 44초(15초). → 3.6 수정 라운드에서 일부 해소(## 3.6 수정 라운드: Trivy를 로컬 감싸개로 실행해 image-checks ③ · bootJar 스캔 통과, J353; Actions 실제 실행 · OIDC · SSM · 인증서 · EC2는 그대로).
9. **Standard 분량 미달.** JPA 저장소 시험 두 클래스(2 · 3건), `SessionPackageIT` · `DevSeedRunnerIT`(3건씩). 같은 컴포넌트를 다른 시험이 함께 덮고 줄
   커버리지는 하한 위다. → 3.6 수정 라운드에서 해소(## 3.6 수정 라운드 BT-TC-10: 5건 미만 클래스 0, J318 · J341).
10. **기타.** Flyway 두 번째 위치 이력에 버전 0 BASELINE 행(J157). Realtime `TraceIdFilter` 최후 방어선 분기 미시험(70%). Spring Boot 3.5.x 지원 종료일 ·
    공급자 SDK 범위 미확인. 실시간 통합 시험은 부하가 걸리면 다른 경주가 더 드러날 수 있다(Step 36에서 두 건 수정, J224). → 3.6 수정 라운드에서 일부 해소(## 3.6 수정 라운드: HEARTBEAT 복귀 경주 2개 J390 ~ J392, run-c TIMEOUT 경주 J393; 부하형 흔들림은 '사람이 정할 것'에 남음).
11. **사고(고침).** 처음 물려받은 `infra/ci/gen-env.sh`가 첫 줄 `변수=값`을 표준 출력에 찍는 결함이 있어 첫 실행 때 scratch 디렉터리의 무작위 시험 값 7개가
    세션 도구 출력에 찍혔다. 그 디렉터리는 곧바로 지웠고 그 값으로 띄운 스택은 없다. 지금은 파일 · 변수 이름만 출력한다(Step 44).

## 사람이 정할 것

| 항목 | 내용 | 관련 |
|---|---|---|
| 엔진 검증 명령 | `bash scripts/verify.sh --engine`을 verification-command 체크포인트에서 Approve. 기록 전 `bolt checkpoint --action verify` 금지. 소스 6곳이 바뀌면 전체 실행을 먼저 | J134 · J239 |
| B1 ④ 스켈레톤 확인 | 종료 코드 0과 `build/verify/report.txt`를 근거로 승인(스켈레톤 자세 off라 3.6 뒤 gated 전환 정지 지점에서 질문) | 계획 ## 검증 명령 |
| 지표 틈 | ## 알려진 틈 2의 지표 · METRIC_SNAPSHOT을 3.6 전에 U1에서 보완할지, 3.6 · 뒤 유닛으로 넘길지 | OD-5 · OD-6 · OD-12 · J284 → 3.6 수정 라운드에서 해소(## 3.6 수정 라운드, 사람 결정 '고친 뒤 다시 시험'으로 U1에서 보완) |
| 10/13 팀 결정 | 도메인 · 인증서(J98, 저장소 변수 `SERVICE_HOST`가 비면 배포 건너뜀), GHCR 공개 여부(J99), 스모크 인증 수단(J100), 외부 프로브 위치(O-2) | J98 ~ J100 |
| 자동 배포 | 저장소 변수 `AUTO_DEPLOY`는 없거나 `true`면 켜짐. 처음 동결하려면 `false` | Step 50 |
| 보안 예외 · 정책 | braces 예외 승인(J277), backend-deps trivy `--ignore-unfixed` 차이(J274) | NFR9.13 |
| 계약 · 해석 | U1 구현 범위 메모를 편차로 올릴지(J197 · J281), 오류 매핑(J210 · J215), 기기 REST 시간 제한 키(J228), 목록 밖 contentCode(J229), 17 폴링(J230), BR1.4 경계(J177) | J112 |
| 팀장 확인 | 등록 직후 T19 꺼짐 착지(J56), last_seen_at 기록 시점(J125 · J184), SERVER_RESTART 범위(J126), V-12(J111), 임시 문구(J118) | 계획 열린 질문 |
| 운영 전 | 정식 금지 표현 사전 v1(OQ15), 백업 · 삭제 충돌(Q-2), 기기 토큰 서버 만료 없음(S-13), 연결 행 계정 삭제(V-10), 로컬 FAILED failureCode(V-7) | D-14 · 사람 결정 9 |
| 판 · 문서 | tech-stack 2.2 갱신 지시(J140), Gradle 9.x 전환, 연락 목록 위치(J278) | J139 · J140 |
| 연결 끊김 로그 | `handleTransportError` EOFException ERROR를 낮출지(## 알려진 틈 5) | A-7 · MO-2 |

## 추적 요약

`traceability.json`(traceability 센서 통과)은 upstream_ids 239개를 모두 열거한다. OK의 target은 실제로 있는 저장소 상대 시험(일부는 구현) 파일 하나다.

| 묶음 | 수 | OK | Deferred | 비고 |
|---|---|---|---|---|
| AC(U1 스토리) | 83 | 56 | 27 | Deferred는 U2 14 · U4 8 · U3 5(functional-design 추적과 같음) |
| NFRx.y | 67 | 47 | 20 | 11/2 측정 8(NFR5.1 · 5.2 · 5.3 · 5.5 · 7.1 · 7.2 · 7.5, NFR4.8 운영 측정 포함), 3.6 넘김 8(NFR1.3 · 2.1 · 4.16 · 5.6 · 9.13 · 10.1 ~ 10.3), B1 이후 · U3 1(NFR5.8), 지표 틈 3(NFR4.15 · 7.6 · 7.8) |
| BR | 89 | 89 | 0 | U1 규칙 전부 |

## 3.6 수정 라운드 (2026-10-11)

> 3.6 Build and Test 단독 실행(HEAD `39b33a6`)이 실패로 끝난 뒤 사람 결정 "고친 뒤 다시 시험 → Approve"(하한 · 단언 · 관문 · 예외 목록 유지, 도메인 · 키 · 비밀값 임시값 유지)에 따라 고친 내용의 기록이다. 커밋
> `cf0a58e`(의존성 패치 판) · `86db0b4`(프런트 FE-1 ~ FE-3) · `8f44764`(인프라 · CI) · `6dbd725`(백엔드 F1 ~ F5 · 지표 · METRIC_SNAPSHOT · SIGTERM · 시간 상수 · 표식 · 분량) · `aabce31`(Nginx 정지 직후 502) ·
> `b094c0d`(독립 리뷰 지적 6건) · `8a9225a`(HEARTBEAT 복귀 경주 2개) · `b9a53a5`(끝-끝 도구 run-c TIMEOUT 경주). 재시험(`test-results.md` 15절)은 `b094c0d`에서 돌았고, 그 뒤 두 커밋은 모듈 전체 명령으로만 확인했다.
> 기존 본문은 고치지 않았다. ## 알려진 틈 · ## 사람이 정할 것의 항목 끝에 붙인 '→ 3.6 수정 라운드에서 해소(…)' 한 줄만 이 절이 더한 것이다. 모든 J번호는 준하 제안(가정)이다. 담당 보고의 원문은 scratchpad `cg/report-fix-*.md`.

### 왜

1. **3.6 실패(Not Met 11).** BT-TC-10(백엔드 26클래스 5건 미만) · BT-NFR9.13(bootJar HIGH · CRITICAL 21건, 이미지 5종) · BT-NFR4.13(앱 컨테이너 정지 때 입구 504) · BT-RD-13(SIGTERM 유예 중 503 0건) · BT-NFR6.1(허용 밖 시간 상수 4종) ·
   BT-NFR7.4(단일 인스턴스 표식 1/12) · BT-NFR4.15 · 7.6 · 7.8 · BT-OD-06(지표 · METRIC_SNAPSHOT 미구현) · BT-NFR5.4(`turn-metrics` 없음).
2. **CI 실패(PR #1, head `39b33a6`).** backend-deps · images ③(Trivy HIGH · CRITICAL), infra-validate · images ⑤(Compose v2.38.2의 `secret-env-files` 오탐), infra-validate(shellcheck 0.9.0 SC2317 · SC2015), 그리고 CI 실행기가 `/tmp`를 읽기 전용으로
   붙여 Trivy가 FATAL로 끝나는 거짓 실패(새로 찾음).
3. **독립 코드 리뷰 확인 결함 8건.** F1(high) 출력 게이트 전사-음성 대응 검사 없음, F2 `closeProvider` 자기 교착, F3 재시도 뒤 409에 취소 없음, F4 최상위 예외 때 기기 명령 없음, F5 `syncFromDb`가 받아들인 비종료 상태의 시한 · 부수 효과 없음, FE-1 재접속 주기에 T19 라벨 비움,
   FE-2 오프라인에서 실패 토스트 · 재시도 안 뜸, FE-3 「다시 시도」가 옛 클로저로 옛 입력 전송.
4. **수정 뒤 독립 리뷰 2회차 지적 6건과 CI 흔들림 2건.** 풀 대기 초과 이중 계수, `SeedDictionaryFileTest` appliesTo 범위, `DbSessionLimitsIT` 중복 2건, 처음 연결 실패 기기 라벨, 표본 스레드가 maintenance를 막음, T5 마지막 스냅샷 마감 밖 — 그리고 CI
   `DeviceGatewayIT` 1건(HEARTBEAT 복귀 경주 2개, 잠복 결함)과 CI tools `scenario-plan.test.mjs` 1건(run-c 단계 타이머 · 요청 제한 시간 경주).

### 무엇을 고쳤나

**리뷰 결함 F1 ~ F5 · FE-1 ~ FE-3(모두 고치기 전 실패하는 시험을 먼저 확인)**

| 결함 | 고친 곳 | 요지 | 회귀 시험 |
|---|---|---|---|
| F1 | `realtime/gate/SegmentBuffer.java` · `OutputGate.java` · `session/SessionExecutor.java` | 한 구간에 한 응답만, `session.output.done`을 그 응답의 전사 완료 신호로 봄, `Sealed.complete`(처음 보는 응답 · 끝 신호 받음 · 신호 뒤 조각 없음)가 아니거나 정규화 뒤 빈 전사면 GATE_EMPTY(fail-closed). 계약 `contracts/ai-io.md` 45 ~ 47행, 편차 (z) | `OutputGateTest` 7건, `SegmentBufferTest`(새) 6건 |
| F2 | `SessionExecutor.java` `processing` 필드 · `putFromProvider` | 편지함을 처리 중인 스레드에서 오는 공급자 알림은 기다리지 않고 넣고 가득 차면 버림(다른 스레드는 배압 유지) | `SessionExecutorFaultTest`(새) 3건 |
| F3 | `core/activity/RealtimeSessionClient.java` `StartResult.afterTimeoutRetry` · `StartActivityService.java` | 시간 초과 재시도 뒤 409면 로컬 FAILED(409) + CORE_ABANDONED 취소(BR6.6 · BR6.7, W5 6단계) | `StartActivityPolicyTest.conflictAfterTimeoutRetryClosesTheCopyLocallyAndCancelsTheSession` |
| F4 | `SessionExecutor.java` `afterFailed()` | 최상위 예외로 FAILED 전이를 이기면 CLOSE_TRANSPORT(FAILED) + STANDBY, 못 이기면 명령 없이 정리(BR6.8) | `SessionExecutorFaultTest` 3건 |
| F5 | `SessionExecutor.java` `syncFromDb(endRequestId)` · `adopt()` · `enterActive()` · `enterClosing()` | DB가 앞선 비종료 상태(DEVICE_ACK · ACTIVE · CLOSING)를 받아들일 때 그 상태의 시한 · 부수 효과를 한 번 걸고, 끊김 시계는 전이를 이긴 뒤에만 공급자를 닫음(BR6.12 ③) | `SessionExecutorFaultTest` 6건 |
| FE-1 | `frontend/src/device/machine/deviceMachine.ts` · `DeviceApp.tsx` | 재접속 시도를 새 상태 `reconnecting`으로 나눠 화면은 재접속 대기와 같게(표정 `reconnecting` + 마지막 view 라벨, BR9.7). 2회차: 한 번도 연결되지 않은 기기는 `connecting` · 빈 라벨(BR9.4, `connectedOnce`) | `device-machine.test.ts` · `device-app.test.tsx` 각 2 + 2건 |
| FE-2 | `frontend/src/parent/api/queries.ts` | 보호자 QueryClient 조회 · mutation `networkMode: "always"`(PD-12 · NFR4.16) | `api-types` · `live-12` · `done-17` · `home-04` 4건 |
| FE-3 | `Register05a.tsx` · `Free08.tsx` | 「다시 시도」= 지금 폼 `requestSubmit()`, 진행 중 여부는 `inFlightRef`(BR2.8 · BR6.4 · J232) | `register-05a` · `free-08` 각 2건 |

**Not Met 11개**

| 목표 | 고친 곳 | 요지 |
|---|---|---|
| BT-TC-10 | 백엔드 시험 26클래스 + contract-model `SystemMujoongClockTest` | 클래스마다 5건 이상(정상 1 + 오류 · 경계 2 이상), 이름 · 위치 불변, 늘 참인 단언 없음. 5건 미만 클래스 0 |
| BT-NFR9.13 | `backend/gradle/libs.versions.toml` · `build-logic` · Dockerfile 5종(`core-api` · `realtime-activity` · `frontend` · `infra/migrate` · `infra/backup`) · `infra/migrate/migrate.sh` · `.github/workflows/ci.yml` | Tomcat 11.0.26 · Jackson 3.1.7 · 2.22.3. JRE 기반 다이제스트 고정 + openssl 3.0.13-0ubuntu3.16 .deb(sha256), web은 node 22.23.3-alpine3.23 다이제스트 + 패키지 관리 도구 삭제, migrate는 JRE + 최소 Flyway CLI jar 16개(sha256), backup은 gosu 삭제 · supercronic 소스 빌드(Go 1.27.2). CI Trivy 실행기에 쓰기 가능한 TMPDIR, image-checks ③은 취약점과 실행 오류를 구분. 예외 목록 불변(1건) |
| BT-NFR4.13 | `infra/nginx/upstreams/compose.conf` · `snippets/error-json.conf` · `conf.d/https443.conf.tmpl` · `infra/compose.demo.yml` · `infra/bin/infra-validate` · `infra/ci/image-checks.sh` | 업스트림을 Docker DNS 이름 + `resolve`(valid=1s)로, Nginx 컨테이너 `dns_opt: ["ndots:1"]`, 연결조차 못 한 504(`$upstream_connect_time` "-")는 502 봉투 · 점검 페이지(hang은 504 유지). 정지 직후 첫 요청도 502(3초 안), 그다음 수 ms |
| BT-RD-13 | 두 앱 `startup/DrainingLifecycle.java` · `web/InFlightRequests.java`(새) · `web/ReadinessGateFilter.java` · Realtime `SessionMailbox` · `PeriodicJobs` · `RealtimeStartupSequence` | T1 ~ T6을 한 마감(`spring.lifecycle.timeout-per-shutdown-phase` 30초) 안에서 남은 시간만 쓰고, T6은 커넥터를 연 채 받아들인 요청이 끝날 때까지 기다림. 유예 중 새 REST 503 SERVICE_NOT_READY + Retry-After, `/internal/events` 503, 새 WSS 1013 · 열린 WSS 1001. 2회차: T5 마지막 스냅샷 DB 읽기도 공유 마감 안(`finalSnapshot(limit)`) |
| BT-NFR6.1 | Realtime `AsyncRecorder` · `ConnectionSender` · `InputRelay` · `EventLogger`(→ 시험 소스 `TestEventLoggers`), 두 앱 `InboxDispatcher`(주석), 두 앱 `arch/TimeLiteralScanTest`(새) | 5ms 되풀이 확인 → 끝 신호 + 공유 남은 시간, 1초 재확인 → 시한 없는 대기(모든 변경이 잠금 안 `signalAll`), keepAlive 0L은 허용 목록(근거), 시험용 생성자는 시험 소스로. 자동 검사가 S13 규칙 + 3규칙으로 허용 목록(core 10 · realtime 16줄)과 대조 |
| BT-NFR4.15 · 7.6 · 7.8 | Realtime `observability/RealtimeMetrics.java` · `QueueDepthGauges.java`(새) · `common/db/DbPoolWaitMonitor.java`(새), Core `observability/CoreMetrics.java` · `common/db/DbPoolWaitMonitor.java`(새) · `web/RestTimingFilter.java`(새), 두 앱 `application.yml`(히스토그램 버킷 `slo`) | 설계 표 2.5 이름 · 라벨로 `mujoong_device_connection_state` · `mujoong_outbox_pending` · `_oldest_pending_seconds` · `_failed` · `mujoong_inbox_dead` · `mujoong_db_pool_wait_exceeded_total` · `mujoong_queue_depth` · `session_activated_total` · `session_start_to_active_seconds` · `relay_out_seconds` · `turn_first_audio_seconds` · `view_item_ready_seconds` · `mujoong_event_delivery_seconds` 버킷. `mujoong_ws_connections_open` → 설계 이름 `mujoong_device_connections_open`(별칭 없음). liveness 게이지 NaN(약한 참조) 고침 |
| BT-OD-06 | 두 앱 `observability/snapshot/MetricSnapshotJob.java` · `SystemLoad.java`(새), `CoreProperties` · `RealtimeProperties`, `application.yml` 새 키 `mujoong.observability.system-sample-interval: 5s` | 5초 표본 → 앱 Clock 60초 경계에 숫자 키만 한 줄(Core 16키 · Realtime 12키), 밀린 줄 몰아 쓰지 않음, DB 장애면 적체 4키 제외 + APP_ERROR, T5 마지막 줄. 2회차: 표본은 전용 스레드 `metric-sample` |
| BT-NFR5.4 | `infra/bin/turn-metrics`(새) · `infra/ci/test-host-tools.sh`(사례 9) · `infra/RUNBOOK.md` | LATENCY_TURN · SESSION_ITEMS_SERVED만으로 NFR5.1 · 5.2 · 5.3(서버 몫) p95 · 표본 규칙 · A-13 경보선 · 미관측 사유별 건수(`check-since-last`와 같은 형식) |
| BT-NFR7.4 | 두 앱 `common/scaling/SingleInstanceAssumption.java`(새), Core 표식 8곳 · Realtime 17곳, 두 앱 `arch/SingleInstanceMarkerTest`(새) | `@SingleInstanceAssumption(si, reason)`을 표 2.2-A의 상태를 실제로 가진 클래스에. SI-1 ~ 10 · 12 표식, SI-11은 두 앱에 그 상태(TTL 캐시)가 없어 대상 0(구조 시험이 전제 검사) |

**CI 실패 4건 · 그 밖 인프라**: Trivy(위 BT-NFR9.13), `infra/bin/infra-validate` `mark_config`(서비스마다 이름표 한 줄만 든 임시 env 디렉터리로 config를 뽑아 Compose 판과 무관하게 env 파일 · 정의 키를 구분, `secret-env-files` · `demo-profile` · `nginx-dns` · 504 나누기 단언), shellcheck 0.9.0 · 0.11.0 두 판 경고 0(`scripts/verify.sh` · `infra/bin/deploy-stage1` · `infra/nginx/reload-watch.sh` SC2317 disable + 근거, SC2015 세 곳 `if`), image-checks ⑧ 재설계(연결 거부 · 세 업스트림 동시 정지 × 5 · hang, curl 실패 때 SIGPIPE로 ⑧이 소리 없이 끝나던 잠복 결함 2건 고침), `infra/ci/test-infra-validate.sh` 32 → 39건 · `test-host-tools.sh` 23 → 32건.

**독립 리뷰 2회차 6건(`b094c0d`)**: 두 앱 `DbPoolWaitMonitor` 같은 빌림의 획득 기록이 이미 셌으면 시간 초과를 다시 세지 않음(J364), `SeedDictionaryFileTest` appliesTo 허용 값을 `contracts/ai-io.md`에서 읽음(J365), `DbSessionLimitsIT` 중복 2건 → 실제 교착 40P01 · 상한 안 잠금 대기(J366), 프런트 `connectedOnce`(J367), 표본 전용 스레드 `metric-sample`(J368), T5 `finalSnapshot(limit)` · `readBacklogWithin`(J369).

**CI 흔들림 2건(`8a9225a` · `b9a53a5`)**: (A) 복귀 UPDATE가 `last_seen_at`을 올리지 않아 끊김 점검자가 복귀 직후 다시 DISCONNECTED로 되돌림 → 복귀 전용 SQL `MARK_CONNECTED_AGAIN`(`last_seen_at = greatest(coalesce(last_seen_at, :seenAt), :seenAt)`, J390). (B) `ConnectionStateWriter.change()`가 커밋 뒤 `mirror(row)`로 사본을 갱신해 그 사이 HEARTBEAT가 복귀를 못 띄움 → 사본 갱신을 트랜잭션 안으로, 복귀 0행이면 `resyncKnownState`(J391). 회귀 시험 `DeviceGatewayIT` 3건 · `ConnectionStateWriterIT`(새) 5건 · `DeviceConnectionRepositoryIT` 1건. tools `tools/skeleton-e2e/run-c.mjs`: 단계 타이머(1000ms)와 그 기한으로 깎인 요청 제한 시간(999ms)이 거의 같은 순간에 끝나 먼저 오는 쪽에 따라 TIMEOUT · FAIL이 갈림 → `ctx.limit`이 기한을 깎았는지 기억하고 깎인 대기의 `*TimeoutError`는 단계(또는 전체) 기한 초과 TIMEOUT으로 보고(J393), `scenario-plan.test.mjs` 3사례(깎임 → TIMEOUT, 전체 기한 → 그 이름, 깎이지 않음 → FAIL).

### 결정 목록 (J285 ~ J393, 모두 준하 제안(가정))

담당 보고의 J번호를 번호순으로 옮겼다(요지만, 원문 · 파일:줄은 보고). J329 ~ J334 · J358 · J359는 담당에게 배정됐지만 쓰이지 않은 번호다. J380 ~ J389는 이 문서 담당(아래 끝).

| J | 내용 | 근거 |
|---|---|---|
| J285 | 그 응답의 전사 완료 신호는 `session.output.done`. 첫 조각부터 이 신호까지 다 담은 구간만 대응 근거로 보고 아니면 GATE_EMPTY. 편차 (z) | 공급자 선 형식의 응답 단위 완료 신호가 이것 하나, 두 가짜 공급자가 모두 보냄, SD-17 '대응 불명은 폐기' |
| J286 | 다른 응답의 조각이 오면 열린 구간을 봉인해 게이트에 넣음(섞지 않음). 버퍼도 다른 응답 조각을 거절하고 그 id를 '본 것'으로 적음 | BR7.3 '한 응답', SD-17 2단계, 판정은 게이트 한 곳. 무조건 폐기 안은 완결 응답까지 잃어 버림 |
| J287 | 사전과 같은 정규화(NFKC · 공백 · 제로폭 · 구두점 제거) 뒤 빈 전사는 GATE_EMPTY | 사전 검사와 같은 기준 |
| J288 | 본 응답 id는 세션 동안 기억(상한 없음, 재설정에도 유지). responseId 없는 조각은 버림 | 크기는 응답 수에 비례, 상한을 두면 뒷부분 구간이 통과해 fail-closed에 어긋남 |
| J289 | 편지함을 처리 중인 스레드에서 오는 공급자 알림은 기다리지 않고 넣고 가득 차면 버림 | 비우는 쪽이 실행기 하나라 기다리면 교착. 그 스레드에서 오는 알림은 스스로 닫은 공급자의 Closed뿐 |
| J290 | 최상위 예외 때 FAILED를 이기면 BR6.8 기기 명령, 못 이기면 명령 없이 정리 | RD-1 규약 5(승자 하나) |
| J291 | 실행기가 살아 있는 동안 행은 그 실행기만 전이하므로 DB가 앞선 비종료 상태면 응답을 잃은 자기 전이로 보고 그 승자 몫을 한 번 검 | RD-1 · BR6.12 |
| J292 | 끊김 시계는 전이를 이긴 뒤에만 공급자를 닫음 | CLOSING이 끊김보다 우선(BR6.12 ③), 공급자는 VOICE_SESSION_CLOSED · 30초 시한까지(BR6.10 ④) |
| J293 | CLOSING을 받아들일 때 END_REQUESTED를 한 번 기록(끝내기 처리 중이면 그 requestId, 다른 경로면 opId 없이, 시각은 이긴 커밋 시각) | 이벤트 중복 없음 |
| J294 | 전사 조각도 구간에 들어갔을 때만 조용 시계를 다시 검 | 음성 조각과 같은 규칙 |
| J295 | 열린 기기 연결 게이지는 설계 이름 `mujoong_device_connections_open` 하나만(옛 이름 별칭 없음) | 표 2.5 · NFR7.8, `host-collect.sh`가 이미 이 이름, 옛 이름 참조 0 |
| J296 | 히스토그램 버킷은 YML `management.metrics.distribution.slo`에, 값은 표 2.5. `view_item_ready`는 표에 없어 50ms · 100ms · 250ms · 500ms · 1s · 1.5s · 2s · 5s | NFR6.1(시간 숫자를 코드에 두지 않음), NFR5.3 0.5초 · 1.5초 경계 |
| J297 | `session_activated_total`은 ACTIVE 전이 커밋 뒤 한 번, `start_to_active`는 created_at → started_at(없으면 카운터만) | OD-9 전이 승자만, OD-17, OD-8 미관측 시각을 만들지 않음 |
| J298 | `queue_depth`는 앱 전체 합, 긁을 때 메모리에서 읽음(session_mailbox · output_audio · output_control · recorder) | 표 2.5, PD-2 · PD-4 · PD-5, id를 라벨에 쓰지 않음(NFR9.8) |
| J299 | `device_connection_state`는 원장의 상태별 행 수를 표본 주기마다 읽음. DB 장애면 마지막 값 유지, UNSTABLE 라벨은 U3 전까지 없음 | NFR4.15, 표 2.5 |
| J300 | `view_item_ready`는 행 확정 → 승인된 FINAL 커밋 시각. 시작점은 CHILD = 입력 전사 done, AI = 그 응답의 output.done 처리 시각(AI 행 `tTranscriptFinal` 새로 기록). 폐기 행은 세지 않음 | OD-8, BR7.5 |
| J301 | `relay_out` · `turn_first_audio`는 로그 표본률과 무관하게 모든 턴. `turn_first_audio = (tDevicePlayStart − clockOffsetMs) − tSpeechEnd`, 미관측 값이 있으면 넣지 않음 | NFR5.4, OD-8 |
| J302 | METRIC_SNAPSHOT(Realtime): 새 키 `mujoong.observability.system-sample-interval`(5s)마다 표본, 앱 Clock 60초 경계에 한 줄, 밀린 줄 몰아 쓰지 않음, T5 마지막 줄, DB 실패면 적체 키 제외 | OD-6 |
| J303 | 풀 대기는 DataSource 감싸개 대신 Hikari 지표 추적기 자리에서 잼(획득 시간 초과도 셈). Boot Micrometer 추적기를 품어 `hikaricp_*` 유지 | 빈 타입을 Hikari로 유지해야 DbSessionLimits · hikaricp 지표 그대로(Core J323과 같음) |
| J304 | 정상 종료 T1 ~ T6을 한 마감 안에서 남은 시간만. T3 알림 확인 뒤 T4, T4는 예산 공유, T6은 커넥터를 연 채 받아들인 요청 대기, 막힌 단계는 APP_ERROR | reliability-design 2.8(Core J321과 같은 방식) |
| J305 | 기록기 drain의 5ms 되풀이 확인을 끝 신호 + 공유 남은 시간으로 | NFR6.1 |
| J306 | 송신기 · 입력 중계의 1초 재확인을 시한 없는 대기로, 모든 변경이 잠금 안 `signalAll` | 놓치는 깨움이 없어 상수 불필요 |
| J307 | 고정 풀 keepAlive `0L`은 허용 목록 | core = max라 쓰이지 않는 필수 인자, 시간 값 아님(Core J327과 같음) |
| J308 | `EventLogger.forTest`를 시험 소스로, `TimeLiteralScanTest`(S13 규칙 + minus · ChronoUnit · 이름 붙은 숫자 상수, 허용 16줄, 사라진 허용 줄 금지) | BT-NFR6.1(J113 나머지), Core와 같은 식 |
| J309 | liveness 게이지의 상태 객체를 필드로 붙잡음 | 약한 참조로 3.6 긁기에서 NaN |
| J310 | Realtime `kr.mujoong.realtime.common.scaling.SingleInstanceAssumption(String[] si, String reason)`, RUNTIME · TYPE, Core J335와 같은 모양 | SCD-4(앱 공용 패키지, 계약 라이브러리에 넣지 않음) |
| J311 | Realtime 몫 지도(SI-1 · 2 · 3 · 4 · 6 · 7 · 9 · 10 · 12 → 17개 클래스). 표식은 상태의 소유자에만 | 표 2.2-A '상태를 가진 클래스' |
| J312 | SI-12는 Realtime 몫(`LatencyRecorder`의 served 집합). 작업 지시(Core 몫)와 다름 | 실제 코드 위치, Core 담당(J336)과 같은 판단 |
| J313 | `RealtimeStartupSequence`에 SI-1 · SI-2 · SI-6 | S3a · S3b · S3c · S3f가 DB 행을 모두 자기 것으로 봄(scalability 2.1, R3-5) |
| J314 | `OrphanSessionChecker`에 SI-2 · SI-7 | 30초 점검이면서 '레지스트리에 없으면 고아' 전제 |
| J315 | SI-5 · SI-8 · SI-11은 Realtime 몫 밖. SI-11은 TTL 캐시가 없어 대상 없음, 전제를 구조 시험이 지킴 | performance-design 표 2.5-A, A5 |
| J316 | S2b 거부 시험은 접속 URL `options=-c mujoong_db.data_class=real`로 앱 연결에서만 'real'로 보이게 하고 먼저 단언 | 공용 DB를 건드리지 않고 조용히 통과하지 않게 |
| J317 | 경과 규칙은 고정 읽기로 시험, 운영 Clock이 그 메서드를 고쳐 쓰지 않음을 리플렉션으로 확인, 실제 시간은 30ms 래치 하나 | 단조 값은 조절 불가, J138 |
| J318 | 분량을 매개변수 사례로 채우지 않고 기존 시험과 겹치지 않는 부분만 더함 | 작업 지시(중복 금지) |
| J319 | Realtime 구조 시험은 지도와 '정확히 같음'(Core 판은 containsAll) | 근거 없이 표식이 늘거나 빠지는 것을 막음 |
| J320 | 재시도 뒤 409는 로컬 FAILED(409) + CORE_ABANDONED 취소, 응답 코드는 BR6.6 번역대로 409 | BR6.6 violation 끝 문장, functional-spec W5 6단계 |
| J321 | T6을 Core 최고 phase SmartLifecycle 안에서. F1b가 센 진행 중 요청을 기다리고 한도는 `spring.lifecycle.timeout-per-shutdown-phase` 30초 − 지난 시간. T4는 한 기한 13초 | reliability-design 2.8, 3.5 `server.shutdown` 행 |
| J322 | 적체 게이지는 5초 표본마다 갱신(최대 5초 낡음), 사건 없는 카운터 미리 등록, 이벤트 전달 버킷은 yml SLO | 이름이 늘 보이게 |
| J323 | Core 풀 대기도 Hikari 추적기 자리에서(J303과 같음) | DataSource 빈 타입 · `hikaricp_*` 유지 |
| J324 | restTotal에서 12 조회를 뺌. 300ms · 500ms 경계는 키 이름이 고정한 계약 상수라 설정 키 대신 허용 목록 | 두 목표가 다름 |
| J325 | breakerOpenSec은 OPEN이었던 시간만(열림마다 최대 5초), 구간 값은 반올림한 누계의 차 | OD-6 값 규칙 |
| J326 | 새 키 `mujoong.observability.system-sample-interval`(5s, 시험 500ms), 스냅샷 주기 이하여야 함. 줄은 표본 틱에서 앱 Clock으로 판정, 적체를 못 읽으면 그 4키를 뺌 | OD-6 |
| J327 | 시간 숫자 상수 검사 규칙과 허용 목록 10줄(Core). keepAlive 0은 시간 값이 아니라 허용 | BT-NFR6.1 |
| J328 | Core 히스토그램은 표 2.5의 Core 행만(event_delivery · internal_http · http_server_requests). 세션 시작 → ACTIVE · view_item_ready는 Realtime 몫 | 표 2.5 발행 앱 |
| J335 | Core `kr.mujoong.core.common.scaling.SingleInstanceAssumption(si[], reason)`, RUNTIME · TYPE. 기존 Javadoc 표식은 어노테이션으로 | 이름에 SingleInstance를 넣어 S13 검색이 그대로 셈 |
| J336 | Core 몫은 SI-5 · 6 · 7 · 8. SI-11(U1에 캐시 없음) · SI-12(Realtime)는 몫 밖, 전제가 깨지면 시험 실패 | 코드 확인, `contracts/timing.md` cache 키 'U1은 DB 직접 읽기' |
| J337 | `CoreStartupSequence`에도 SI-6 | reliability-design S3f '단일 인스턴스라 안전' |
| J338 | `PreparingTimeoutChecker`를 SI-7에. DbHealthChecker · MetricSnapshotJob · 로그 병합 flush는 인스턴스마다 도는 것이 맞아 표식 안 함 | 표 2.2-A |
| J339 | 표에 번호 없는 인스턴스별 상태(StaleEvaluator 기기 래치 · SecurityEventCap · LogMergeWindow · SessionStartBreaker · CoreMetrics · DbPoolWaitMonitor · InFlightRequests)는 표식 안 함 | 표에 없는 번호를 만들지 않음(scalability 2.2 추가 여부는 사람) |
| J340 | Core 구조 시험: 몫 목록 · 근거는 시험 안, 번호는 정규식 SI-1 ~ 12, 계약 라이브러리에 표식 없음 확인, 전제 가드 · 검색 병행, 규칙마다 위반 표본 자기 시험 | SCD-4 |
| J341 | 분량 원칙: 클래스 이름 · 위치 불변, 미시험 동작만 더함, 늘 참인 단언은 지움 | 작업 지시 |
| J342 | 이력 표 시험: S2 시험은 별도 PostgreSQL 인스턴스에서 바꾸고 되돌림, `FlywayConfIT` 새 시험은 공용 인스턴스에 검증만 하고 이력 표 불변 단언 | 공용 DB 보호 |
| J343 | `SessionPackageIT` 경계 시험은 그 시험이 만든 아이 행만 바꾸고, 계약 밖 값이면 사본 INSERT · Realtime 호출 전에 멈춤을 단언 | 격리 |
| J344 | `ClockParamsTest`는 JDBC ResultSet을 Mockito 대역으로 | 변환 함수 단위 시험, 기존 JdbcTemplate 모킹과 같은 수준 |
| J345 | trap · 간접 호출 함수의 SC2317은 0.9.0 오판(0.10+는 SC2329)이라 함수 정의 줄에 둘을 함께 disable + 근거. SC2015는 같은 동작의 `if` | 브리핑 A-3 |
| J346 | env 파일 검사는 '이름표 env 디렉터리'로 해석한 config로(Compose 판 무관). 보간 전 config는 demo에서만 | 브리핑 A-2, v2.38 재현 |
| J347 | Compose 업스트림을 Docker DNS 이름 + `resolve`(valid=1s)로, 고정 IP(N-2)는 edge 유지. 정지 직후 재해석 전 창(실측 0.4 ~ 1.8초)은 남음 | 작업 지시 4, 상태 코드 계약 (y) 유지 |
| J348 | `turn-metrics`를 `infra/bin`에. p95는 가장 가까운 순위, NFR5.1의 10 ~ 99 비율은 턴별 합, 경보선은 판정한 지표만, CLOCK_OFFSET_MISSING은 추정값으로 계산하되 미관측 사유에 셈, 미달 · 경보선 초과면 종료 코드 1 | MO-4 · OD-8 · 2.3 표본 규칙 |
| J349 | web 기반을 node 22.23.3-alpine3.23 다이제스트로 고정, 실행 단계에서 패키지 관리 도구 삭제 | 브리핑 A-1 |
| J350 | JRE 기반 다이제스트 고정, openssl .deb를 Ubuntu 스냅숏에서 sha256으로 받아 bind 마운트로 설치. 기반 다이제스트가 그 판 이상을 담으면 이 단계를 지움 | 같은 태그 새 다이제스트 없음, R-03 · 재현성 |
| J351 | migrate는 JRE 기반 + 최소 Flyway CLI jar 16개(sha256 고정). 두 위치 · 기준선 · 이력 표 · afterMigrate · `FLYWAY_*` 불변 | 브리핑 A-1, 실제 PostgreSQL에서 migrate · info · validate 확인 |
| J352 | backup은 gosu 삭제, supercronic을 고정 소스 + 고정 Go 1.27.2로 빌드 | 새 배포판 없음 |
| J353 | CI · 샌드박스 Trivy 실행기에 쓰기 가능한 TMPDIR. ③은 취약점과 실행 오류를 나눠 보고 | 3.6에서 같은 거짓 실패 기록 |
| J354 | 504 나누기: 연결조차 못 한 504(`$upstream_connect_time` "-")는 502 봉투 · 점검 페이지, 연결한 뒤 시한 초과만 504. errors.md '상태 코드는 그대로'를 '봉투가 502 · 504를 다른 값으로 바꾸지 않는다'로 읽어 편차를 더하지 않음 — 사람 확인 필요 | RD-16 요구, 기각 대안(연결 시한 1초 · backup 우회 · 위험으로 두기) |
| J355 | Nginx 컨테이너 `dns_opt: ["ndots:1"]` — 내장 DNS가 모르는 단일 이름을 바깥으로 넘기지 않고 빈 답 → 업스트림 비움 | 러너 DNS SERVFAIL 추정 재현 |
| J356 | `proxy_connect_timeout 3s` 유지(N-12 제안값) | 바꿀 근거 없음 |
| J357 | image-checks ⑧ 재설계(연결 거부 · 세 업스트림 동시 정지 × 5회 · hang), Nginx DNS 옵션은 compose.demo.yml에서 읽음, 정지 직후 첫 요청도 502 요구(더 엄격) | 브리핑 C, CI 재현 |
| J360 | 재접속 시도를 새 상태 `reconnecting`으로(전이는 처음 연결과 같고 화면은 재접속 대기와 같음). 처음 연결은 `connecting` + 빈 라벨 | BR9.7 · NFR4.1 · performance-design ReconnectWait |
| J361 | 보호자 QueryClient 조회 · mutation 모두 `networkMode: "always"` | PD-12 표 2.9-A, NFR4.16 |
| J362 | 토스트 「다시 시도」= 지금 폼 `requestSubmit`, 요청 중 여부는 ref로 막음. 화면을 떠난 뒤 누르면 아무것도 안 함 | BR2.8 · BR6.4 · J232 · 표 2.9-C |
| J363 | 온라인 사건만으로 자동 다시 읽기는 더하지 않음(`refetchOnReconnect: false` 유지). 복귀는 12 · 17 폴링, 04는 「다시 시도」 | 승인된 단언 `api-types.test.ts:157`(PD-12 진입 때 1회) 유지 |
| J364 | 두 앱 `DbPoolWaitMonitor`: 같은 스레드의 바로 앞 추적기 기록이 기준을 넘겨 이미 센 대기면 시간 초과를 다시 세지 않음 | HikariCP 7.0.2가 같은 빌림에 acquired(≈ 시한) + timeout을 모두 부름 |
| J365 | `SeedDictionaryFileTest` appliesTo 허용 값을 `contracts/ai-io.md` '금지 표현 사전 항목' 행에서 읽음 | 계약 18 · 56행, 로더 동작과 맞춤 |
| J366 | `DbSessionLimitsIT` 중복 2건 → 실제 교착 40P01(감지 시한 1초 < 락 대기 상한 2초, pg_settings 선단언) · 상한 안 잠금 대기 | 실제 DB에서만 보이는 동작 |
| J367 | 기기 `connectedOnce`: 연결된 적 없으면 `connecting` · 빈 라벨, 있으면 `reconnecting` · 마지막 view 라벨 | BR9.4 · BR9.7, 기존 단언 불변 |
| J368 | 표본 스레드 분리 `metric-sample`(Realtime 6 → 7, Core 3 → 4 스레드). OD-6 'maintenance 스케줄러'와 다름 | RD-16 NOT_READY 5초 × 3회가 표본에 막히지 않게 |
| J369 | T5 마지막 스냅샷: tick DB 읽기를 잠금 밖으로, `finalSnapshot(Duration limit)` · `readBacklogWithin`(가상 스레드 + join), 못 읽으면 APP_ERROR SnapshotReadTimeout + 적체 4키 제외 | 공유 마감 30초 |
| J370 | 1차 실행에서 시간 민감 IT 3건이 실패한 U-3 · U-7은 한 번만 다시 돌리고 두 실행을 모두 적음. 판정은 재실행으로 하되 부하 사정을 함께 | 공통 지침 9 |
| J371 | Docker 재기동 직후 첫 `--engine`은 IT-03-warm으로 따로 기록, 판정은 다음 실행 | 3.6 공개 사항 7 |
| J372 | BT-NFR4.13 · IT-06e · h는 J354 · J357 기준으로 재고 첫 요청의 응답 시간을 함께 적음(기대 불변) | 작업 지시 |
| J373 | BT-RD-13은 IT-06j 기대의 상태 순서 · Retry-After · 30초 안 종료로 Met을 매기되, 503 창 실측(수십 ms)과 '커넥터를 유예 끝까지' 설계 문구 기준이면 Not Met임을 같은 행에 적어 사람이 정하게 | 리뷰 지적 1 |
| J374 | BT-NFR7.4는 '상태를 가진 클래스에 표식'으로 읽어 SI-11을 '대상 없음'으로 두고 Met. 12/12 문자 그대로면 11/12 | J336 · J315 |
| J375 | BT-OD-06은 65초 대기 뒤 앱별 1줄 + attrs 숫자만으로 Met. '재시작 뒤 이전 줄 보존'은 따로 재지 않음 | IT-06b 기대 문구 |
| J376 | BT-NFR9.13 로컬 실행분(ST-19 · ST-20 ③ · npm 관문) 통과로 Met. CI 관문 실측(Actions)은 뒤 단계 | 1차 Owning Stage |
| J377 | ST-20 ③의 PASS 한 줄에 이미지별 `trivy image` 표를 보충 증거로(판정은 image-checks) | 증거 가독성 |
| J378 | IT-06i '해석됨' 결과는 측정 스크립트 결함(busybox nslookup rc 0)으로 분류, `getent` · `nc` · 실제 IP 접속으로 LC-5 Met 유지. 스크립트는 고치지 않음 | 범위(실행 · 기록만) |
| J379 | Step 10 계산기(`xunit_trace.py`)를 그대로 쓰고 결과 차이만 적음 | 작업 지시 |
| J390 | 복귀 전용 SQL `MARK_CONNECTED_AGAIN`(`last_seen_at = greatest(coalesce(last_seen_at, :seenAt), :seenAt)`), `HeartbeatHandler`가 receivedAt을 넘김, UNRESPONSIVE 복귀도 같은 경로 | 경주 (A): 끊김 점검자 `last_seen_at <= cutoff`가 복귀 직후 되돌림 |
| J391 | `ConnectionStateWriter` 사본(`knownState`) 갱신을 트랜잭션 안(UPDATE · 이벤트 직후)으로, 복귀 0행이면 `resyncKnownState`(DbRetry IDEMPOTENT, 행이 아직 이 연결의 것일 때만) | 경주 (B): 커밋 ~ mirror 사이 HEARTBEAT가 복귀를 못 띄움 |
| J392 | `handover()`의 커밋 뒤 knownState(CONNECTED)는 그대로 둠 | 같은 노출이 없음(가정) |
| J393 | `run-c.mjs` `ctx.limit`이 기한을 깎았는지 기억하고, 깎인 대기의 `*TimeoutError`는 단계(또는 전체) 기한 초과 TIMEOUT으로 보고 | 단계 타이머 1000ms와 깎인 요청 제한 999ms가 같은 순간에 끝나 먼저 오는 쪽에 따라 상태가 갈림(CI tools 1건) |
| J380 | 이 절의 결정 표는 담당 보고의 J번호를 번호순으로 요지만 옮기고(파일:줄은 보고), 배정됐지만 쓰이지 않은 번호(J329 ~ J334 · J358 · J359)를 명시 | 작업 지시 1 |
| J381 | `source-manifest.json`은 `version` 1 유지, 새 파일 38개 경로만 더함(지운 파일 없음, `EventLogger.forTest` 이동은 파일 삭제가 아님). `scripts/cloud-setup.sh`는 3.5 이전 커밋의 세션 도구라 제외 | 형식 · 필드 불변 지시, `git diff 50cb21b HEAD --name-status` |
| J382 | `traceability.json` Deferred → OK 4건(NFR4.15 · 7.6 · 7.8 · 9.13)은 실제 코드 + 통과한 시험이 있는 것만. NFR9.13은 로컬 스캔(ST-19 · ST-20 ③) 통과 + 패치 판 코드로 OK, target `infra/ci/image-checks.sh`; CI 관문 실측은 Matrix Owning Stage에 남김 | 작업 지시 '실제 코드 + 통과한 시험' |
| J383 | OK 항목의 target 교체 6건(NFR6.1 → `TimeLiteralScanTest`, NFR7.4 → `SingleInstanceMarkerTest`, NFR5.4 → `test-host-tools.sh`, BR6.6 → `StartActivityPolicyTest`, BR6.8 → `SessionExecutorFaultTest`, BR9.7 → `device-machine.test.ts`)은 이번 라운드의 회귀 · 구조 시험으로 바꿈. NFR4.16은 Deferred 유지 + target 보강 | 근거 갱신 |
| J384 | Matrix 칸 서식은 '재시험: … / 1차: …'. Not Met 11행과 값이 바뀐 Met 행(BT-TC-01 ~ 09 · BT-VC-02 · BT-NFR4.9)만 고치고 나머지 Met · Unverified 행은 1차 그대로(재시험 보고 '회귀 없음 · 사유 변동 없음') | 작업 지시 4 |
| J385 | 백엔드 시험 수는 HEAD(`8a9225a` 뒤) 1,451건을 정본으로 적고 재시험 당시 `b094c0d` 1,442건을 함께. 1,451의 근거는 저장소 `backend/*/build/test-results/test/TEST-*.xml` 직접 집계(22:42 ~ 22:45Z, 실패 0 · 5건 미만 0) | 오케스트레이터 추가 지시 |
| J386 | BT-RD-13 · BT-NFR7.4의 Met에는 재시험 J373 · J374의 사람 결정 주석을 같은 칸에 반드시 동반 | 재시험 보고 (5) 2 · 3 |
| J387 | `cross-unit-traceability.md`는 같은 스크립트로 다시 센다. 판정은 1차와 같고(FR 0 · NFR 0 · AC 56) 4절 참고 열(NFR4 · 7 · 9)과 1절 OK 항목 수만 바뀌므로 그 값과 재계산 주석만 고침 | 작업 지시 5 |
| J388 | 준비도: build-ready 충족, test-ready 충족(실패한 명령 0 · Pending 0), deployment-ready 미충족 유지(Unverified 51) | 단계 정의 성공 조건 |
| J389 | 단계 판정 문구: 1차 '실패'는 지우지 않고 15절에 재시험 결과(실패 조건 1 · 2 해소, 3 Unverified 51은 사람 결정 D)를 적어 최종 판정은 사람 몫으로 | 작업 지시 3 |

### 새 계약 편차

- **(z)** `contracts/deviations.md`(머리말 한 줄 + (z) 행), `contracts/ai-io.md` 45 ~ 47행: 공급자 `session.output.done`을 그 응답의 전사 완료 신호로 보고, 첫 조각부터 이 신호까지 다 담은 구간만 재생 대응 근거로 본다(호환 분류: 규칙 구체화, J285). `ContractFilesTest`의 편차 글자 범위 a ~ y → a ~ z. **사람 확인 필요**: 원래 '선택'이던 신호가 사실상 재생의 필요조건이 된다(머리말 '더하기만 안전 · 필수화 없음'과의 관계, 11/2 실공급자 어댑터 V-2는 완결된 응답에만 이 신호를 보내야 함). 편차 글자가 다 차서 다음 편차는 표기 방식과 `ContractFilesTest`를 함께 바꿔야 한다(J354의 504 → 502 봉투 해석을 편차로 올릴 때 걸림).
- 그 밖 계약 변경 없음(`contracts/timing.md` 불변 — 새 키 `mujoong.observability.system-sample-interval`은 observability 묶음이라 RD-24에 따라 timing.md 대상 아님).

### 바뀐 시험 수 · 커버리지

| 층 | 3.5(1차) | 수정 라운드 끝(HEAD `b9a53a5`) | 비고 |
|---|---|---|---|
| contract-model | 276 (14클래스) | **279** (14) | `SystemMujoongClockTest` +3 |
| core-api | 506 (61) | **607** (70) | 새 클래스 9(`TimeLiteralScanTest` · `SingleInstanceMarkerTest` · `DbPoolWaitMonitorTest` · `CoreMetricsTest` · `MetricSnapshotJobTest` · `MetricSnapshotIT` · `GracefulDrainIT` · `InFlightRequestsTest` · `RestTimingFilterTest`), 기존 15클래스 분량 +31 |
| realtime-activity | 387 (39) | **538** (54) | 새 클래스 15(`SegmentBufferTest` · `SessionExecutorFaultTest` · `TimeLiteralScanTest` · `SingleInstanceMarkerTest` · `DbPoolWaitMonitorTest` · `RealtimeMetricsTest` · `RealtimeMetricsScrapeIT` · `MetricSnapshotJobTest` · `SessionStateEmitterTest` · `ConnectionSenderTest` · `ConnectionStateWriterIT` · `DrainingLifecycleTest` · `RealtimeStartupSequenceTest` · `GracefulDrainIT` · `InFlightRequestsTest`), 재시험 당시(`b094c0d`) 529 |
| test-support | 27 (4) | 27 (4) | 불변 |
| **백엔드 합계** | **1,196** | **1,451** | 재시험 당시 1,442. 실패 0 · 건너뜀 0, 5건 미만 클래스 0 |
| frontend | 499 (34파일) | **514** (34) | FE-1 ~ FE-3 +12, J367 +3 |
| tools | 105 (8) | **106** (8) | J393 시험 추가(사례 3개, 재시험 당시 105) |
| **전체** | **1,800** | **2,071** | |

| 모듈 | 3.5 줄 커버리지(U1 범위) | 지금 | 하한 |
|---|---|---|---|
| contract-model | 98.53% | 98.53% (1478/1500) | 80% 통과 |
| core-api | 95.77% | **96.00%** (4006/4173, 재시험 95.90%) | 통과 |
| realtime-activity | 89.93% | **92.03%** (5650/6139, 재시험 5632/6120) | 통과 |
| frontend `src/**` | 98.82% | **98.93%** (1863/1883) | 통과 |

새 운영 소스 13개(Core 6 · Realtime 6 · `infra/bin/turn-metrics`)는 `backend/coverage-scope/u1.txt`(J137)에 들어 있다(머리 주석의 모듈별 줄 수 core-api 104 → 110 · realtime 112 → 117은 낡음 — 통합 담당). 하한 · 단언 · 제외 목록을 낮추거나 넓힌 곳 없음, `@Disabled` · 건너뜀 0.

### 설계 문서와 달라진 곳(문서 반영 대기)

승인된 설계 문서(`nfr-design/*.md` · `infrastructure-design/*.md` · `functional-design/rules.md`)는 이 라운드에서 고치지 않았다. 담당 보고가 '문서 담당에게' 넘긴 설계-구현 차이를 모았다. 반영 여부는 사람이 정한다.

| 문서 · 위치 | 설계 문구 | 구현(지금) | J |
|---|---|---|---|
| reliability-design 2.2 스레드 수 표 | Realtime 6 · Core 3 | 표본 전용 스레드 `metric-sample` 추가 → Realtime 7 · Core 4 | J368 |
| observability-design OD-6 | '앱 안 5초 표본은 maintenance 스케줄러' | 표본은 `metric-sample` 전용 단일 스레드(DB 점검자 · 끊김 점검과 분리) | J368 |
| reliability-design 2.8 T5 | 마지막 METRIC_SNAPSHOT | DB 읽기를 공유 마감 안에서만, 못 읽으면 APP_ERROR SnapshotReadTimeout + 적체 4키 제외 | J369 |
| observability-design 2.5 · 2.6 `mujoong_db_pool_wait_exceeded_total` | 'DataSource 감싸개'로 100ms 초과 계수 | Hikari 지표 추적기 자리에서 계수, 획득 시간 초과도 1회, 같은 빌림 이중 계수 없음 | J303 · J323 · J364 |
| observability-design 표 2.5 `view_item_ready_seconds` | 버킷 값 없음 | 50ms · 100ms · 250ms · 500ms · 1s · 1.5s · 2s · 5s | J296 |
| reliability-design ## 3 레지스트리 · observability-design ## 3 | — | 새 키 `mujoong.observability.system-sample-interval`(두 앱 5s, 시험 500ms, 스냅샷 주기 이하) | J302 · J326 |
| observability-design 2.5 · 2.6(Core) | — | Core 히스토그램은 Core 행만, REST 300ms · 500ms 상수는 허용 목록, breakerOpenSec은 OPEN만 | J324 · J325 · J328 |
| observability-design 표 2.5 vs security-design 5.5 · performance-design | `gate_discard{reason, category}` · `input_frames_dropped` 라벨 없음 | `gate_discard{reason}`만, `input_frames_dropped{cause}` — 문서끼리 충돌, 그대로 둠 | rt2 (5) 2 |
| functional-design rules.md BR4.8 | HEARTBEAT 복귀 규칙 | 복귀 때 `last_seen_at`도 그 수신 시각으로 올린다(낮추지 않음) | J390 |
| reliability-design RD-4 | 연결 상태 사본 | 사본(`knownState`)은 UPDATE와 같은 트랜잭션에서 맞추고, 복귀 0행이면 원장에서 재동기화 | J391 |
| reliability-design RD-13 · 2.8 DRAINING | '커넥터는 유예(30초) 끝까지 열어 둠' | 커넥터는 T6(받아들인 요청 완료)까지 열림 — 진행 중 요청이 없으면 503 창은 수십 ms, 그 뒤 T7 접속 거절(입구 502) | J304 · J321(사람 결정) |
| infra-spec N-2 | 업스트림 고정 IP | Compose 업스트림은 Docker DNS 이름 + `resolve`(valid=1s) + `zone`, 고정 IP는 edge만 | J347 |
| infra-spec 3.3 오류 응답 | 상태 코드 그대로 | 연결조차 못 한 504 → 502 봉투 · 점검 페이지(hang 504 유지) | J354 |
| infra-spec 2.1 Nginx 서비스 | — | `dns_opt: ["ndots:1"]` | J355 |
| infra-spec migrate 이미지 | `flyway/flyway:12.4.0` 배포판 | JRE 기반 + 최소 Flyway CLI jar 16개(sha256), 978MB → 424MB | J351 |
| infra-spec backup 이미지 | supercronic 배포 바이너리 · gosu | supercronic 고정 소스 + Go 1.27.2 빌드, gosu 삭제 | J352 |
| infra-spec 앱 이미지 | 기반 태그 | 기반 다이제스트 고정 + openssl .deb 스냅숏 설치 단계 | J349 · J350 |
| scalability-design 2.2 SCD-3 표 | 12곳 | SI-11은 U1에 그 상태 없음(대상 0), SI-12 위치는 Realtime `LatencyRecorder`, 표 밖 인스턴스별 상태(J339 후보) 추가 여부 | J312 · J315 · J336 · J339 |
| cicd-pipeline CD-1 images ⑧ | 약 10초 | 약 50초(정지 × 5회 · hang 사례) | J357 |
| build-and-test/integration-test-instructions.md 10절 IT-06i | `getent hosts postgres \|\| nslookup postgres` | busybox `nslookup`이 빈 응답에도 rc 0 → `getent`만 쓰도록 고칠 것(측정 스크립트 결함, 판정 영향 없음) | J378 |

### 사람이 정할 것(이 라운드가 새로 올린 것)

| 항목 | 내용 | 관련 |
|---|---|---|
| BT-RD-13 503 창 | 진행 중 요청이 없으면 SIGTERM 뒤 503 창이 수십 ms(재시험 20ms 표본 30개 중 503 2)이고 그 뒤는 T7 접속 거절(입구 502). 설계 문구 '커넥터는 유예 끝까지'대로 하려면 최소 유지 시간 키(배포 중단 증가)가 필요 — Met 판정은 상태 순서 · Retry-After 기준(J373) | J321 · J304 · J373, 리뷰 지적 1 |
| 편차 (z) 필수화 해석 | 선택이던 `session.output.done`이 사실상 필수. 머리말 '필수화 없음'과의 관계, 실공급자 어댑터(V-2)에 요구 | J285 |
| J354 504 → 502 봉투 | errors.md '상태 코드는 그대로'를 '봉투가 502 · 504를 다른 값으로 바꾸지 않는다'로 읽어 편차 미기재. 편차로 올리려면 글자 (a) ~ (z) 소진으로 표기 방식 · `ContractFilesTest` 변경 필요 | J354 |
| SI-11 '대상 없음' | BT-NFR7.4 12곳 중 11곳 표식 + SI-11 대상 0. '대상 없음'을 인정할지(J374), scalability 2.2에 적을지 | J315 · J336 · J374 |
| J363 04 자동 복귀 | 오프라인인 채 04에 들어오면 전에는 결함의 부수 효과로 온라인 때 저절로 읽혔다. 지금은 토스트 + 「다시 시도」. 자동 복귀를 원하면 PD-12 '진입 때 1회'와 기존 단언 변경 | J363 |
| `SeedDictionaryFileTest` OUTPUT_GATE 전수 단언 | `everyCategoryHasAtLeastOneSeedEntryForTheOutputGate`가 모든 항목에 OUTPUT_GATE를 요구(계약 56행보다 좁음). 승인된 3.5 단언이라 불변 — 후속 유닛이 다른 appliesTo 항목을 더하면 바꿔야 함 | J365 |
| J347 정지 직후 창 | 정지 직후 약 1.6초 안에 든 요청은 3초 뒤 502(상태는 요구대로, 응답 시간만 늦음). 창까지 없애려면 backup 피어 등 다른 방식(hang 504가 502로 바뀌는 부작용) | J347 · J354 |
| 시간 민감 IT의 흔들림 | 재시험 1차 실행 3건(`DeviceRegisterApiIT` · `RealtimeLogHygieneIT` · `SessionLifecycleIT`, CPU 부하 겹침, 재실행 통과) · CI 2건. CI 2건은 경주로 확정해 고쳤다(`DeviceGatewayIT` J390 ~ J392, tools `scenario-plan` J393). 나머지 3건은 부하형 — `InternalHttpClient.java:178`(IOException을 모두 CONN_ERROR로 묶어 종류가 로그에 없음) · `ContractStubServer.java:78-80`(backlog 0) · `RealtimeItEnvironment.java:37`(WAIT 10초)이 후보 | 재시험 (5) 1, rootcause 보고 |
| 구조 시험 규칙 차이 | Core `SingleInstanceMarkerTest`는 containsAll, Realtime은 '정확히 같음' — 맞출지 | J319 · J340 |
| `coverage-scope/u1.txt` 머리 주석 | 모듈별 줄 수(core-api 104 → 110 · realtime 112 → 117) 낡음 | J137 |
| F4 잔여 | 고아 세션 점검은 CLOSE_TRANSPORT · STANDBY를 보내지 않음(reliability 2.1 4번). 종료 상태 전이의 커밋 응답 유실 때 기기 명령 재발행 없음 | rt1 (5) |
| 성능 기준선 | 재시험 값이 1차보다 10% 이상 늘어난 항목(검증 명령 509초/289, 엔진 89초/59, 강제 종료 복구 core-api 20.3초/9.1, 이벤트 전달 612ms/460)은 환경 요인(다른 Gradle 실행 · 재부팅 직후)이 커서 조용한 환경에서 다시 재야 비교 가능 | 재시험 (5) 4 |

### 남은 틈

- **Unverified 51(변동 없음).** 43개는 실행 계획에 맡을 단계가 없다(11/2 측정 창 · 운영 7일 창 · 실기 브라우저 · GitHub Actions 실행 · 측정 도구 일부) — 사람 결정 D로 이 라운드에서 다루지 않았다. 6개는 4.2 · 4.3, 2개는 U2 몫. BT-CD-01(Actions 관문 3종)은 로컬에서 돌리지 않았다(`aabce31`에서 CI 전부 녹색, `b094c0d` backend · `8a9225a` tools 각 1건 흔들림은 위 J390 ~ J393으로 고침 — 그 뒤 CI 결과는 다음 실행에서 확인).
- **Not Met 0.** 재시험(`b094c0d`)에서 11개 모두 Met. 단 BT-RD-13 · BT-NFR7.4의 Met에는 사람 결정 주석이 붙어 있다(위 표).
- **그 밖 남은 위험.** `turn-metrics`는 실공급자 · 11/2 측정 로그로 돌리지 않았다(표본 1이라 판정 모두 '보류'). 시험 프로필의 조용 시간 50ms라 부하로 output.done 처리가 50ms 넘게 늦으면 fail-closed 폐기로 통합 시험이 실패할 수 있다(rt1). `closeProvider`의 공급자 네트워크 I/O는 여전히 실행기 스레드. `SystemLoad`는 JDK `jdk.management` 모듈에 기댄다(최소 JRE로 바꾸면 기동 실패). 러너 DNS 동작(J355)은 추정 — 다음 CI ⑧로 확인. Docker 재기동 직후 `--engine`은 한 번 데워야 한다(IT-03-warm B TIMEOUT 106초, 3.6 공개 사항 7). 디스크 87%.
