# 코드 품질 평가 (Code Quality Assessment)

> 기준 커밋 `3748083`. 시험 · 린트 · CI · 문서 · 기술 부채의 단일 기록처다. 다른 산출물은 이 문서의 절을 참조한다. 시험을 실제로 돌려 본 결과는 아니다(정적 스캔).

## 1. 종합 판정

| 축 | 판정 | 요지 |
|---|---|---|
| 시험 | 좋음 | `@Test` 526개(realtime) + 140개(contract-model · test-support), 실행기 단위 하네스 · 계약 스키마 검증 · 구조 규칙 시험까지 갖춤 |
| 구조 강제 | 매우 좋음 | ArchUnit · `TimeLiteralScanTest` · `ConfigKeyContractTest` · `SingleInstanceMarkerTest` · `verifyBootJarHasNoTestClasses` |
| 린트 | 보통 | 전용 Java 린터 없음. `javac -Xlint:all`와 위 구조 시험이 대신함 |
| CI | 좋음 | 경로 필터 · 백엔드 check · 의존 취약점 · 비밀 검사 · 마이그레이션 expand 검사 · 이미지 |
| 문서 | 매우 좋음 | 거의 모든 클래스에 한국어 Javadoc + 설계 ID 추적, 계약 정본 분리 |
| 부채 | 주의 | 표식 부채(TODO 0건)는 적지만 U4 확장 지점과 시험 하드코딩이 U4b에 직접 걸림 |

## 2. 시험

### 규모와 분포
- `backend/realtime-activity/src/test/java/kr/mujoong/realtime/{arch,common,config,db,dialog,gate,gateway,log,messaging,observability,recording,session,startup,testing,voice,web}` — 시험 클래스 약 80개 + `testing/` 지원 14개, `@Test` 526개.
- 태그(`@Tag` 선언 수): `u1` 54 · `u3` 23 · `db` 28. **`u4b` 태그는 아직 없다.** contract-model: `u1` 18 · `u4a` 1(`SnapshotHashGoldenTest`) · `u5a` 1.
- 세션 관련: `TransitionTableTest` · `SessionTimersTest` · `SessionExecutorTest` · `SessionExecutorFaultTest` · `SessionExecutorU3Test` · `SessionExecutorReview{,2,3}U3Test` · `SessionLifecycleIT` · `SessionVoiceU3IT/Test` · `ActivitySessionRepositoryIT` · `SessionRecordsRepositoryIT` · `web/RealtimeInternalApiIT` · `session/web/SessionInternalControllerU3Test` · `dialog/StopIntentJudgeTest`.
- 실행기 대역(`testing/`): `SessionHarness` · `InMemoryTransitions` · `FakeDeviceLink` · `FakeProviderPort` · `FakeSessionGaps` · `FakeAudioRecordings` · `ManualTime` · `ManualLauncher` · `RealtimeItEnvironment` · `RealtimeTestApp` · `InternalApi`.

### 커버리지 설정
- JaCoCo 0.8.14, `check` → `jacocoTestCoverageVerification`, LINE COVEREDRATIO ≥ 0.80, 제외 목록 없음(`test-support`만 `coverageFloorApplies=false`).
- `-PcoverageScope=<이름>`이면 `backend/coverage-scope/<이름>.txt`에 적힌 이 모듈 운영 소스만 측정. 목록이 비거나 파일이 없으면 빌드 실패.
- 현재 범위 파일: `u1.txt`(301줄, realtime 119줄) · `u3.txt`(44줄, realtime 41줄) · `u2` · `u4a` · `u5a`(realtime 0줄). **`u4b.txt` 없음.**
- CI는 범위 없이 모듈 전체 80%로 `clean check verifyBootJarHasNoTestClasses -Pmujoong.test.db=docker`. U4b는 범위 80%와 모듈 전체 80%를 둘 다 지켜야 한다.

### 구조 · 규약 시험
`RealtimeArchTest`(core 참조 금지 · test-support 의존 금지 · 프레임 경로 JDBC/파일/Outbox 금지 · 시스템 시계 금지 · 공급자 클라이언트 voice 한정 · 요청은 팩토리만) · `TimeLiteralScanTest`(운영 소스 시간 숫자 상수 0건) · `SingleInstanceMarkerTest` · `ConfigKeyContractTest`(`contracts/timing.md` ↔ `application.yml` ↔ 설정 레코드 이름 · 기본값 대조).

## 3. 린트 · 정적 검사
- 백엔드 전용 린터(Checkstyle · Spotless · PMD · SpotBugs · Error Prone) **없음**.
- 대체 관문: `-Xlint:all,-processing,-serial,-this-escape,-try`, 위 구조 시험, `verifyBootJarHasNoTestClasses`. 저장소 루트 `.editorconfig`.
- 운영 소스 `@SuppressWarnings` 2건, TODO/FIXME/HACK 0건.
- 프런트: `frontend/eslint.config.mjs`(ESLint 9, 훑기).

## 4. CI/CD
- `.github/workflows/ci.yml` 작업: `changes`(경로 필터) · `backend`(gradle check + bootJar 시험 클래스 0) · `backend-deps`(Trivy HIGH 이상, 예외 `ci/security-exceptions.yml`) · `frontend`(ESLint · Vitest 80% · next build · 번들 비밀 검사) · `frontend-deps` · `tools` · `secrets`(gitleaks) · `infra-validate`(마이그레이션 expand 검사, 승인 목록 `infra/migration-approvals.txt`) · `images`.
- `.github/workflows/deploy.yml`: OIDC · SSM 배포. 저장소 변수 `SERVICE_HOST` · `AUTO_DEPLOY`가 없어 실제 배포는 건너뜀.
- `infra-validate`는 기존 표에 제약을 더하는 마이그레이션 문장을 막는다. 파일 · 문장 번호 · 문장 해시가 맞는 승인 줄이 있어야 통과. 새 표만 더하는 마이그레이션은 해당 없음.

## 5. 문서
- 클래스 Javadoc이 한국어로 거의 전부 있고 BR · J · U3-R · U3-J · SD · RD · PD 등 설계 ID로 추적된다.
- 계약 정본 `contracts/`(README · timing · errors · deviations · ports · ai-io · OpenAPI/AsyncAPI · JSON Schema 51개), 계약 자체를 `ContractFilesTest`가 메타 검증.
- 유닛별 `docs/units/<unit>/{brief,design,report}.md`, 운영 런북 `infra/RUNBOOK.md`.

## 6. 기술 부채 · U4b 위험 (이 절이 단일 기록처)

| ID | 항목 | 근거 위치 | U4b 영향 | 심각도 |
|---|---|---|---|---|
| D1 | **스키마 판 기대값이 시험에 하드코딩** — realtime V3 추가 시 깨짐 | `realtime-activity/src/test/java/kr/mujoong/realtime/db/RealtimeSchemaIT.java:47-98`(표 10개 · 이력 0/1/2 · 최고 판 "2" `containsExactly`/`hasSize(10)`, `u1` 태그), `backend/core-api/src/test/java/kr/mujoong/core/db/FlywayConfIT.java:87,99,102`(realtime 이력 · `migrationsExecuted == 2` · 표 합계 33), `SchemaPrivilegeIT` | brief는 'U1 · U3 시험 단언'과 `backend/core-api`를 건드리지 말라고 하나, U3는 V2 때(커밋 47db8f2) 이 셋과 `RealtimeDb`를 함께 고쳤다. **요구사항 단계에서 '스키마 판 기대값 갱신은 예외로 허용'을 명시적으로 결정해야 한다.** U5b(V4)와 병합 순서(V3 → V4)도 맞춰야 함 | 높음 |
| D2 | **`JoinTracker`가 main에 없음** | 커밋 `5354a32`(및 `867f0a0`)의 `dialog/JoinTracker.java` · `JoinTrackerTest.java`. 공개 API `JoinTracker(Duration noReply)` 단일 창 · `promptPlayed` · `reply` · `tick` · `classify` · `phase` · `prompts` · `isFinished` · `MAX_PROMPTS = 3` | 되살린 뒤 U4b-J4대로 두 창(join-wait · join-wait-after-reprompt)으로 넓혀야 함. 범위 파일 `u4b.txt`에 포함 | 높음 |
| D3 | **`TransitionTable.END_REASONS`가 도착 상태로 사유를 고정** | `session/TransitionTable.java:93-97`(ENDED → GUARDIAN_STOP 등), `Trigger` 12종(24 ~ 60행)에 WAITING_CHILD · DECLINED 도착 · CHILD_STOP · SILENCE · CONSENT_WITHDRAWN 없음, `ACTIVATED`는 DEVICE_ACK → ACTIVE | 설계는 '사건이 사유를 정함'. 같은 CLOSING → ENDED가 여러 사유를 가지므로 사유를 Trigger(또는 CLOSING 진입 사건)에 실어야 함. `ACTIVATED` 도착을 WAITING_CHILD로. DB 제약은 이미 WAITING_CHILD · DECLINED를 허용(V1 54 ~ 56행 · 75 ~ 80행) | 높음 |
| D4 | **시간 키 미배선 + 3중 동기화 제약** | `contracts/timing.md` 2절(58 ~ 62행) `join-wait` · `join-wait-after-reprompt` · `silence-warning` · `silence-end` · `device.gap-display`는 이름만 예약. `config/RealtimeProperties.java:90` `SessionTiming`은 `deviceAckTimeout` · `closingConfirm`뿐, yml에도 없음. 설계 4절 신설 3키(`stop-confirm-wait` · `record-settle` · `mujoong.session.deletion-stop-wait`)는 계약에 없음 | 새 키마다 `contracts/timing.md`(2절 → 1절 이동 · 신설) · `application.yml` · `application-test.yml` · `RealtimeProperties`를 함께 고쳐야 `ConfigKeyContractTest` 통과. 코드에 숫자 시간 상수를 두면 `TimeLiteralScanTest` 실패. `timing.md`는 U5b와 병합 충돌 지점 | 높음 |
| D5 | **흔들리는 U1 시험** | `web/RealtimeInternalApiIT.java:164` `coreAbandonedClosesNonTerminalSessionsAndLeavesATombstoneForUnknownIds`. 191 ~ 193행 `dbOnlySession(CLOSING)`의 실행기 없는 행이 `application-test.yml`의 `closing-confirm: 3s` · `orphan-check-interval: 1s` 때문에 단언 전에 FAILED가 될 수 있음(부하 시 202 대신 409) | U4b 몫으로 지정됨(docs/units/README.md · U3 report 9절 · brief 「먼저 할 것」 2). CLOSING 동작을 바꾸면 더 흔들릴 수 있음. 행 생성을 단언 직전으로 옮기거나 이 클래스 시한만 늘리되, '단언 의미 불변' 수정임을 기록 | 중 |
| D6 | **`SessionExecutor` 비대화** | `session/SessionExecutor.java` 1,832줄(메시지 처리 · 시계 · 게이트 · 종료 의도 · 재연결 · 정리). 그 밖 500줄 초과: `dialog/StopIntentJudge.java`(565) · `log/AsyncRecorder.java`(531) · `recording/FileAudioRecorder.java`(619) | 세션 단일 스레드 설계상 의도된 집중이나 U4b 기능을 더하면 2,500줄을 넘을 수 있음. 판정을 순수 클래스로 빼는 기존 패턴 유지 여부를 functional-design에서 결정 | 중 |
| D7 | **세션 패키지 `Plan`에 `activityType` · 추가 승인 문구 키 없음** | `contracts/schemas/internal-session-start-request.schema.json:66-80`(`approvedContent` 필수 CLOSING · WAITING_FILLER, 나머지는 `additionalProperties`), `SessionExecutor.Plan`(190행)은 `closing` · `waitingFiller`만 | 시작 안내(INTRO_FREE_TALK · INTRO_PRACTICE) · 확인 질문 · 재안내를 고르려면 Plan 확장 필요. 문구가 없을 때 소리 없이 시계만 도는 경로(U4b-R15)를 시험으로 지켜야 함. U5b brief 87행도 확인 항목으로 둠 | 중 |
| D8 | 종료 의도가 출력만 막고 ACTIVE 유지 | `session/SessionExecutor.java:226-270 · 803-838`(`stopIntent` 플래그, CONFIRM은 `CHILD_STOP_UNCLEAR` 이벤트만, `CHILD_STOP_LIFTED` 해제) | STOP → CLOSING(CHILD_STOP)이 되면 해제 경로의 뜻이 바뀜. U3 리뷰 수정(report 11 ~ 14절) 동작과 맞춰 정리 | 중 |
| D9 | `CONSENT_WITHDRAWN` 끝내기가 400 | `session/EndRequestHandler.java:29` · `SessionMailbox.java:124` · `web/SessionInternalController.java:47` | U4b가 CLOSING(CONSENT_WITHDRAWN) 경로를 연결 | 중 |
| D10 | `DeletionRequested` 처리기 없음 · 삭제 참가자 미연결 · 세션 행 삭제 참가자 없음 | `messaging/InboxHandler.java:14`, `messaging/RealtimeMessagingDeletionParticipant.java:31`, `log/RealtimeVoiceDeletionParticipant.java:34,45,169` | U4b 처리기 신설 → 진행 세션 강제 정지 → 참가자 순서 → `DeletionCompleted` | 중 |
| D11 | 결과 입력 스냅숏 저장소 · `SessionRecordCaptured` 생산 코드 없음 | realtime V1 · V2에 표 없음, `contract-model/.../payload/SessionRecordCaptured.java`는 `record` · `termination`을 Map으로 둠 | V3 표 + 정산 + 1회 발행. `snapshotHash` null 키 규칙을 계약 6절에 적고 `SnapshotHashGoldenTest`에 골든 추가 | 중 |
| D12 | 진행 조회 필드 미구현 · 자리만 있는 값 | `web/SessionInternalController.java:223-224`(`technicalPause` · `silenceWarning` 늘 null), `session/NewActivitySession.java:14`(`intro_content_version_id` 늘 null), `log/SessionGapRepository.java:20`(UNCONFIRMED 구간 자리만) | U4b가 채움 | 저 |
| D13 | 끊김 시계가 ACTIVE에서만 유효 · 시계 Kind 부족 | `SessionTimers.Kind`(26 ~ 38행)에 참여 대기 · 공백 · 점선 · 확인 질문 대기 · 정산 없음, `onDisconnectCheck`(`SessionExecutor.java:1480`)는 `status != ACTIVE`면 무시 | WAITING_CHILD 확장과 Kind 추가 | 중 |
| D14 | 작업 트리 파일 모드 왕복(스캔 범위 밖, 참고) | 셸 스크립트 · `backend/gradlew` 41개 파일이 `MM` 상태(100755 ↔ 100644) | 첫 커밋 전에 정리하지 않으면 실행 비트가 빠진 스크립트가 커밋될 위험 | 저 |

코드 안 `U4` 언급은 19곳이며 위 D3 · D8 ~ D13이 대표 지점이다.

## 7. 권고 (다음 단계 입력)
1. 요구사항 단계에서 D1(스키마 판 기대값 갱신 허용 범위)과 D5(흔들리는 시험 수정의 '단언 의미 불변' 기준)를 명시적 결정으로 남긴다.
2. `u4b` 태그와 `backend/coverage-scope/u4b.txt`(U1 형식, realtime 운영 소스만)를 먼저 만든다.
3. 시간 키는 계약 · yml · 레코드 세 곳을 한 커밋에서 고친다(D4).
4. 실행기 비대화(D6)는 판정 순수 클래스 분리 패턴을 기본값으로 두고 functional-design에서 확정한다.

## Sources
- 개발자 스캔 `developer-scan.md` (Test Coverage · Code Quality Indicators · Technical Debt Signals · Handoff Risks)
- `TransitionTable.java` 20 ~ 100행 직접 확인(D3)
