# U4b 코드 생성 계획 — 활동 세션 Realtime 쪽

> Code Generation(3.5) · 작업 기록 `261013-u4b-activity-realtime` · 유닛 없는 단일 반복(stage-level)
> 저장소 루트 `/home/jdmea/finalProject` · 현재 브랜치 `master`
>
> 입력
> - 승인된 기능 설계: `construction/functional-design/functional-spec.md` · `rules.md` · `entities.md`
> - 요구사항: `inception/requirements-analysis/requirements.md` FR1 ~ FR11 · NFR1 ~ NFR9
> - 설계 정본: `docs/units/u4b/design.md` 9절(층 L1 ~ L7), 지시: `docs/units/u4b/brief.md`
> - codekb: `aidlc/spaces/default/codekb/mujungi/`
>
> 이 범위는 스토리 단계를 건너뛰어서, 계획 단계를 스토리 대신 요구사항(FR/NFR)과 업무 규칙(BR)에 연결한다.

## 1. 범위와 원칙

- **바꾸는 곳**
  - `backend/realtime-activity`의 운영 · 시험 코드
  - realtime V3 마이그레이션
  - `contracts/`: timing · deviations · ai-io · internal-api · 이벤트 문서
  - `backend/coverage-scope/u4b.txt`
  - `scripts/verify` 시드 순서
  - `docs/units/u4b/design.md` · `report.md`
  - core-api는 `FlywayConfIT` 한 파일만(FR11.1)
- **바꾸지 않는 곳**: core 스키마 · core-api 운영 코드 · `/parent` · `/device` · `README.md` · `contracts/README.md` · 다른 작업 기록
- **기존 패턴을 따른다**
  - 세션당 직렬 실행기와 편지함
  - 전이 승자만 부수 효과
  - 판단은 순수 클래스(StopIntentJudge 방식)
  - 시계는 `MujoongClock`과 세대 번호 시계
  - 시간 값은 설정 주입
  - 한국어 Javadoc에 설계 ID
- **품질 목표**: `u4b` 범위 줄 커버리지 80% 이상, 모듈 전체 80% 유지. 어떤 임계값도 낮추지 않는다. 모자라면 시험을 더하거나 차이를 보고한다.
- **설계 검토에서 감수한 지적 중 코드에서 반드시 막을 것**(Functional Design 승인 때 열린 채 남은 것)
  - R-03: SessionRecordCaptured 페이로드에 계약 스키마의 필수 `guardianId`를 세션 행 guardian_id로 채운다. 그 밖의 필수 필드도 스키마와 대조한다(Step 13).
  - R-10: 확장점 출력 능력은 세션이 ACTIVE일 때만 받는다. CLOSING · 정산 창 · 종료 상태의 호출은 거절하고 PRACTICE_EXTENSION_FAILED 사건{reason: NOT_ACTIVE}에 남긴다. CLOSING 중 확장점 사건은 전달하되 신호는 무시한다(Step 15).
  - R-11: 안내 재생 중에 온 아이 확정 전사는 JoinTracker(5354a32) 동작대로 처리한다. 창이 열리기 전(PROMPTING)의 대답은 `reply()`가 분류해 즉시 처리하고, 창 시작을 기다리지 않는다. 결정표는 같다(Step 8).
  - R-12: 삭제 재시도 때 앞 시도까지의 참가자별 건수를 그 deletionRequestId의 Inbox 처리 기록(처리 메모 칸)에 누적한다. DeletionCompleted.counts는 누적값을 쓴다(Step 17).

## 2. 영향 범위 (Blast Radius)

| 파일 · 묶음 | 변경 | 의존하는 쪽 | 영향 |
|---|---|---|---|
| `session/TransitionTable` | Trigger 추가(WAITING_CHILD · DECLINED · 사유 사건), 사유를 사건이 결정 | SessionExecutor · TransitionService · OrphanSessionChecker · 시험 | 높음 |
| `session/SessionExecutor` | 참여 · 확인 질문 · 공백 · 일시정지 · 정산 · 확장점 연결 | 거의 모든 세션 시험(U1 · U3) | 높음 |
| `session/SessionTimers` | Kind 추가(JOIN_WINDOW · STOP_CONFIRM · SILENCE · GAP_DISPLAY · RECORD_SETTLE · EXTENSION) | SessionExecutor | 중 |
| `session/EndRequestHandler` · `SessionMailbox` · `web/SessionInternalController` | CONSENT_WITHDRAWN 경로, 응답표 | Core 계약 시험 · `RealtimeInternalApiIT` | 중 |
| `dialog/JoinTracker`(되살림) | 두 창 시간 | 신규 | 저 |
| `dialog`에 신설 순수 클래스 | StopConfirmFlow · SilenceClock · EndReasonPolicy | 신규 | 저 |
| `session`에 신설 | SettleCoordinator · ResultSnapshotBuilder · ResultInputSnapshotRepository · PracticeSessionExtension(+NoOp) · PracticeOutput | 신규 | 중 |
| `messaging` | RealtimeDeletionParticipant 인터페이스, DeletionRequestedHandler, InboxHandler 연결 | 기존 참가자 2개 | 중 |
| `config/RealtimeProperties` · `application.yml` · `application-test.yml` | 시간 키 8개 | ConfigKeyContractTest · 모든 시험 프로필 | 중 |
| `db/migration/realtime/V3__u4b_session_lifecycle.sql` | 새 표 + 색인 | RealtimeSchemaIT · FlywayConfIT · SchemaPrivilegeIT · SchemaVersionCheck | 중 |
| `contracts/*` | timing · deviations · ai-io · internal-api · events | ContractFilesTest · ConfigKeyContractTest | 중 |
| 시험 도우미 `testing/*` · `tools` | 활성화 경로(WAITING_CHILD) 반영 | U1 · U3 시험 | 중 |

## 3. Testing Contract

## Testing Contract

```json
{
  "version": 1,
  "methodology": "test-after",
  "source": "org",
  "ordering": "implement each applicable testable layer, then write and run that layer's tests.",
  "scope": "refactor",
  "test_strategy": "standard",
  "project_type": "brownfield",
  "applicable_notes": [
    {
      "layer": "org",
      "text": "We treat tests as a first-class deliverable in every Bolt. The specific\nmethodology (TDD, BDD, ATDD, or classic test-after) is affirmed at\npractices-discovery and recorded in `team.md` under this heading with explicit\n`Methodology` and `Ordering` fields; Code Generation resolves those fields\nindependently from coverage, tooling, and scope notes.\n\nWhen no posture has been affirmed, our default per scope is:\n- **Methodology**: test-after\n- **Ordering**: implement each applicable testable layer, then write and run\n  that layer's tests.\n- `mvp`, `enterprise`, `feature`, `infra`, `classic` add an 80% line-coverage\n  floor and CI execution before merge.\n- `bugfix`, `security-patch` add a targeted regression for the specific\n  bug/vulnerability and require the existing suite to remain green.\n- `express` uses the Minimal strategy: requirement-driven unit tests (one per\n  requirement, with a happy-path floor per component); existing tests remain\n  green.\n- `poc`, `refactor`, `workshop` add no extra new-test floor and require the\n  existing suite to remain green.\n\nThe active `Test Strategy` still applies in every scope and determines test\nvolume/types. Scope floors are additive; they never reduce or replace the\nselected strategy.\n\nBuild and Test verifies defined coverage floors and affirmed quality targets;\nthey may not be weakened to make a step pass.\n\nAffirm a stricter posture in `team.md` if the team commits to one."
    }
  ],
  "obligations": {
    "strategy": "standard",
    "strategy_volume": [
      "Five to eight tests per component.",
      "Unit tests plus integration tests for key boundaries.",
      "Add E2E, performance, or security tests when requirements demand them."
    ],
    "scope_floor": [
      "Keep the existing test suite green.",
      "This scope adds no extra new-test floor beyond the selected test strategy."
    ],
    "combination_rule": "Apply every selected-strategy obligation and every scope-floor obligation; neither replaces the other, and a targeted scope regression may add the narrowest necessary test type beyond the strategy default."
  },
  "plan_profile": {
    "methodology": "test-after",
    "runner_step": "Verify the existing test runner/configuration and record the exact unit-scoped command.",
    "runner_ready_before_first_test": true,
    "testable_layers": [
      "Data model / database behavior",
      "Repository / data access",
      "Business logic",
      "API / endpoint",
      "Frontend behavior"
    ],
    "steps": [
      "Project structure and production configuration skeleton.",
      "Verify the existing test runner/configuration and record the exact unit-scoped command.",
      "Data model / database behavior - implement.",
      "Data model / database behavior - write and run its tests after implementation.",
      "Repository / data access - implement.",
      "Repository / data access - write and run its tests after implementation.",
      "Business logic - implement.",
      "Business logic - write and run its tests after implementation.",
      "API / endpoint - implement.",
      "API / endpoint - write and run its tests after implementation.",
      "Frontend behavior - implement.",
      "Frontend behavior - write and run its tests after implementation.",
      "Environment/build configuration.",
      "Documentation and traceability."
    ]
  },
  "input_sha256": "sha256:322f3cce51ad835b556974215be0ae0a5aba55c673c92ff6b1b327e7eb2426e8",
  "contract_sha256": "sha256:84a32ec0d9dd634774d2a290b523e6e0d5e6f77259469f3112500519cb132392"
}
```

적용 방식: test-after다. 층마다 구현한 뒤 그 층의 시험을 쓰고 돌린다. 프런트엔드 층은 이 범위에 없다(`/parent` · `/device`를 바꾸지 않음). 층마다 기존 시험이 모두 통과하는 상태로 `feat(u4b): …` 커밋을 한다.

## 4. 단계

### 준비

- [x] **Step 1. 구조 · 설정 뼈대** — `backend/coverage-scope/u4b.txt`를 U1 형식으로 만든다(처음엔 되살린 JoinTracker부터 넣고, 층마다 새 운영 파일을 더한다). 시험 태그 `u4b`를 쓴다. 작업 트리의 의도하지 않은 파일 권한 변경이 스테이지에 없는지 확인한다(project 규칙). — NFR6
- [x] **Step 2. 시험 실행기 확인과 기준선** — 지금 main 상태에서 아래를 돌려 기준선(건수 · 통과 · 실패 · 커버리지)을 `code-summary.md`용으로 기록한다.
  - `cd backend && ./gradlew --no-daemon :realtime-activity:test -PincludeTags=u1`
  - `cd backend && ./gradlew --no-daemon :realtime-activity:test -PincludeTags=u3`
  - u4b 범위 명령이 실행되는지 확인한다(`unit-test-instructions.md` 1절).
  - 기존 실패가 있으면 U4b와 무관함을 적고 진행한다. — NFR7

### L1 데이터 · 설정 (Data model / database behavior)

- [x] **Step 3. realtime V3 마이그레이션** — `V3__u4b_session_lifecycle.sql`(더하기만)
  - 새 표 `realtime_result_input_snapshot`(entities.md 열 · 유일 (session_id, snapshot_version) · FK RESTRICT · hash 형식 CHECK)
  - `realtime_activity_session(guardian_id)` 일반 색인
  - 새 표에 앱 역할 권한(V1 · V2와 같은 방식)
  - 기존 표 제약은 바꾸지 않으므로 마이그레이션 승인 목록은 필요 없다. 필요해지면 `infra/migration-approvals.txt`에 근거와 함께 한 줄을 더한다.
  - — FR7.1 · BR8.5 · 설계 6절
- [x] **Step 4. 시간 키 8개** — 한 커밋에 묶는다.
  - `contracts/timing.md`: 2절 예약 키 5개를 1절로 옮긴다. 3절에 `mujoong.timing.session.stop-confirm-wait`(20s) · `mujoong.timing.session.record-settle`(2s), 4절에 `mujoong.session.deletion-stop-wait`(5s)를 더한다.
  - `application.yml`, `application-test.yml`(U4b-J26: 시험 기본값은 넉넉히, U4b 통합 시험만 짧게 덮어씀)
  - `RealtimeProperties.SessionTiming` · 장치 · 세션 묶음
  - `ConfigKeyContractTest`의 "비소유" 기대값에서 옮긴 키를 빼고 소유로 단언한다.
  - — FR10.1 · BR11.1 · NFR1
- [x] **Step 5. L1 시험 작성 · 실행**
  - `RealtimeSchemaIT` 확장: 새 표 · 색인 · 권한. 기존 판 · 표 수 기대값은 FR11.1에 따라 숫자만 갱신한다.
  - `ResultInputSnapshotRepositoryIT`: 쓰기 · 유일 위반 · FK · hash CHECK. 5개 이상
  - `ConfigKeyContractTest` 실행
  - core-api `FlywayConfIT`: realtime 이력 · 실행 수 · 표 합계 숫자만 갱신
  - `SchemaPrivilegeIT`: 숫자만 갱신
  - 바꾼 줄 목록을 남긴다(report용).
  - — FR11.1 · NFR7

### L2 전이표 · 끝내기 (Business logic + API)

- [x] **Step 6. 전이표 · 사유 결정 · 끝내기 응답** — 구현
  - `TransitionTable`: Trigger를 더한다(READY → WAITING_CHILD, JOIN_ACCEPTED, JOIN_DECLINED, CHILD_STOP_CONFIRMED, PRACTICE_COMPLETED, SILENCE_TIMEOUT, CONSENT_WITHDRAWN(진행 · 시작), DISCONNECT/CORE_ABANDONED/SERVER_RESTART 출발 집합에 WAITING_CHILD 추가). `END_REASONS`를 도착 상태 기준에서 Trigger별 사유 · end_detail 결정으로 바꾼다. CLOSING 진입 때 사유를 쓰고, ENDED_UNCONFIRMED는 UNCONFIRMED와 end_detail을 쓴다. 표 밖 전이는 TRANSITION_IGNORED.
  - `EndRequestHandler` · `SessionMailbox` · `SessionInternalController`: CONSENT_WITHDRAWN을 받고 응답표대로 답한다. 시작 단계는 202 {} + FAILED(CONSENT_WITHDRAWN), WAITING_CHILD · ACTIVE는 202 {CLOSING}, Core 취소는 PREPARING ~ ACTIVE만 FAILED. 반복 요청은 멱등이다. 철회 뒤 아이 전사 · 원음 저장을 차단한다.
  - `OrphanSessionChecker` · 기동 점검: WAITING_CHILD를 포함한다.
  - `SessionStateEmitter`: CLOSING에 endReason, 일시정지 중이면 technicalPause를 싣는다.
  - — FR1.1 ~ FR1.5 · BR1.1 ~ BR1.9 · BR7.2
- [x] **Step 7. L2 시험 작성 · 실행**
  - `TransitionTableU4bTest`: 전이표 칸마다 성립 · 불성립, 사유 · end_detail. 8개 이상
  - `EndRequestU4bTest`: 응답표 칸마다(사유 3 × 상태 5), 멱등, 400 사유 오류
  - `SessionStateEmitterU4bTest`: CLOSING endReason · technicalPause
  - 기존 `TransitionTableTest` 등 U1 기대값이 사건별 사유로 바뀌면 숫자 · 값만 고치고 목록을 남긴다(FR11.3).
  - — NFR3

### L3 참여 확인 (Business logic)

- [x] **Step 8. 참여 흐름** — 구현
  - `git show 5354a32:…/dialog/JoinTracker.java`와 `JoinTrackerTest.java`를 되살린다. 공개 메서드는 유지하고, 생성자를 `(Duration firstWindow, Duration afterPromptWindow)`로 넓힌다.
  - 재생 중(PROMPTING) 대답도 `reply()`로 즉시 처리한다(R-11).
  - 실행기 연결
    - 준비 완료 → WAITING_CHILD
    - 입력 중계(원음 없음)
    - 시작 안내 재생과 승인 문구 말풍선 행
    - 재생 완료 보고 → 창 시계(JOIN_WINDOW, 일시정지 중 멈춤)
    - 결정표 실행: RE_PROMPT · DECLINE_ALTERNATIVE · START · DECLINE
    - 참여 전 아이 전사 미저장
    - 공급자 응답 `session.response.cancel`
    - ACTIVE 진입 때 `session.response.create` 1회 · started_at · 원음 기록 시작
    - DECLINED: 전사 행 삭제 → 인사 → CLOSE_TRANSPORT(DECLINED) → 정산
  - 빠진 승인 문구는 건너뛰고 APPROVED_CONTENT_MISSING 1회 남긴다.
  - 지시문에 참여 확인 뒤 첫 질문 문장을 더한다.
  - `ProviderRequestFactory`에 `session.response.create`를 더하고, 가짜 공급자가 이를 기록하게 한다.
  - `SessionExecutor.Plan`에 activityType과 선택 승인 문구 코드(INTRO_FREE_TALK · INTRO_PRACTICE · RE_PROMPT · DECLINE_ALTERNATIVE · STOP_CONFIRM · SILENCE_WARNING)를 싣는다.
  - — FR2.1 ~ FR2.4 · BR2.1 ~ BR2.7 · BR3.1 ~ BR3.3 · BR4.7 · NFR5
- [x] **Step 9. L3 시험 작성 · 실행**
  - `JoinTrackerTest`: 되살린 시험 + 두 창
  - `SessionJoinU4bTest`: 19 · 21초 경계, 결정표 칸마다, 안내 최대 3번, 불명확 · 주제 거부, 재생 중 대답, 일시정지 중 멈춤, 빠진 문구로 DECLINED까지
  - `SessionLifecycleU4bIT`: 끝-끝 WAITING_CHILD → ACTIVE → 대화
  - tools 시험: 가짜 공급자 참여 턴과 response.create 기록
  - U1 · U3 시험 도우미(`SessionHarness` · `RealtimeItEnvironment` 등)를 새 활성화 경로(참여 응답을 한 번 보내 ACTIVE로)에 맞춘다. status_version · 전사 행 수 기대값만 고치고 목록을 남긴다(FR11.3).
  - U1 · U3 회귀를 돌린다.

### L4 종료 의도 · 공백 · 끊김 · 진행 조회 (Business logic + API)

- [x] **Step 10. 종료 의도 · 공백 · 일시정지** — 구현
  - **StopConfirmFlow**(순수): STOP → 곧바로 CHILD_STOP. CONFIRM → STOP_CONFIRM 재생 · 출력 차단 · stop-confirm-wait 창, STOP · 참여 → 확정, 그 밖 · 만료 → 해제 + response.create. U3의 '막고 유지 → 해제'(CHILD_STOP_LIFTED) 경로를 없앤다.
  - **SilenceClock**(순수): 누적 · 멈춤 4조건 · 빈 아닌 전사 리셋, 4분 경고(SILENCE_WARNING 1회) · 6분 SILENCE
  - **기술 일시정지**: 끊김 시계를 WAITING_CHILD까지 넓힌다. gap-display(10초)에 technicalPause와 DISCONNECT 구간, 복구 시 닫기, 40초 ABORTED. CLOSING에서는 끊김 전이가 없다. ENDED_UNCONFIRMED는 UNCONFIRMED 구간을 쓴다.
  - **진행 조회**: 실행기가 불변 값으로 바꿔 쓰고 `SessionInternalController`가 technicalPause · silenceWarning을 채운다.
  - 공통 마무리 순서의 STOP_OUTPUT · CLOSE_TRANSPORT 사유 매핑, output_stopped_at · transport_closed_at 기록
  - — FR3.1 · FR3.2 · FR4.1 · FR5.1 · FR6.1 · BR4.1 ~ BR4.6 · BR5.1 ~ BR5.4 · BR6.1 ~ BR6.4 · BR7.1
- [x] **Step 11. L4 시험 작성 · 실행**
  - `StopConfirmFlowTest`: 네 경로 · 19 · 21초
  - `SessionStopU4bTest`: CHILD_STOP 마무리, 종료 확정 뒤 이전 응답 미재생
  - `SilenceClockTest`: 3:59 · 4:01 · 5:59 · 6:01, 멈춤 조건 4종, 빈 전사 미리셋, 안내 재생 미리셋
  - `TechnicalPauseU4bTest`: 9 · 11초, 39 · 41초, WAITING_CHILD 끊김, CLOSING 끊김 없음, UNCONFIRMED 구간
  - `SessionViewU4bIT`: 조회 두 칸, 종료 세션 null
  - U3 종료 의도 시험 중 해제 경로 기대값을 새 경로로 바꾸고 목록을 남긴다(FR11.3).

### L5 정산 · 결과 스냅숏 (Repository + Business logic)

- [x] **Step 12. 스냅숏 저장소 · 조립** — 구현
  - `ResultInputSnapshotRepository`(JDBC, Outbox와 같은 트랜잭션)
  - **ResultSnapshotBuilder**(순수)
    - utterances는 deleted_at이 빈 확정 전사 + 통과 AI 말 + 승인 문구, sequence 순
    - gaps · recordScope
    - practice는 확장점 값 또는 null
    - audio는 deleted_at이 빈 메타, 상대 경로
    - DECLINED · ACTIVE 전 종료는 빈 목록
    - 정규화 JSON sha256(`JsonCodec`)
  - — FR7.1 · BR8.2 ~ BR8.4
- [x] **Step 13. 정산 조정 · 이벤트** — 구현
  - **SettleCoordinator**: 종료 전이 뒤 공급자 close · provider_closed_at → 정산 창(연결 닫힘 또는 record-settle) → 확장점 정산 → 기록기 flush → practice 값 → 스냅숏 + SessionRecordCaptured Outbox 한 트랜잭션 → RECORD_CAPTURED
    - 세션당 1회
    - 정산 창 안 늦은 전사는 started_at < ended_at이면 저장
    - 정산 뒤 사건은 무시
  - DECLINED는 인사 순서 뒤에 정산한다. 시작 단계 FAILED · Core 취소 · 재시작에도 빈 기록 스냅숏을 만든다(기동 점검 포함).
  - 페이로드는 BR8.8 표 + **guardianId**(R-03)이다. 계약 스키마 `event-payloads.schema.json`의 required를 모두 채운다.
  - — FR7.1 · FR7.2 · BR8.1 · BR8.5 ~ BR8.8 · NFR8
- [x] **Step 14. L5 시험 작성 · 실행**
  - `ResultSnapshotBuilderTest`: 해시 결정성(골든), DECLINED 빈 기록, deleted_at 제외, practice null/객체, recordScope. 6개 이상
  - `SessionRecordU4bIT`: 종료 다섯 경로 + 시작 실패 + 재시작마다 스냅숏 1 · 이벤트 1, 이벤트 스키마 검증(guardianId 포함), Outbox 재무장, 늦은 전사 정산 창 안팎
  - contract-model `SnapshotHashGoldenTest`에 골든을 더한다(필요 시).

### 연습 확장점 (Business logic)

- [x] **Step 15. PracticeSessionExtension** — 구현
  - 인터페이스 다섯 시점 · Signal, PracticeContext · Utterance · ExtensionEvent · EndContext, PracticeOutput 능력
    - playApproved · instructProvider · requestResponse · cancelResponse · holdOutput · schedule/cancel(EXTENSION 시계, 일시정지 중 멈춤) · post
  - 기본 `NoOpPracticeSessionExtension` 빈. U5b가 빈을 등록하면 그것을 쓴다.
  - 실행기 연결
    - PRACTICE 세션에서만 부른다.
    - 신호: NEXT_GOAL은 계속, LAST_GOAL은 CLOSING(CHILD_STOP, end_detail PRACTICE_COMPLETED)
    - 정산 창 안 사건은 전달하되 신호 무시
    - ACTIVE가 아닐 때 출력 능력 호출은 거절하고 PRACTICE_EXTENSION_FAILED{reason: NOT_ACTIVE}(R-10)
    - 예외는 기록하고 자유대화처럼 계속(정산 건너뜀 · practice null)
  - — FR9.1 · BR10.1 ~ BR10.6
- [x] **Step 16. 확장점 시험 작성 · 실행**
  - `PracticeExtensionU4bTest`
    - 기본 무동작으로 PRACTICE가 자유대화와 같은 전이를 밟는다.
    - 대역 확장점: LAST_GOAL → CHILD_STOP · PRACTICE_COMPLETED, NEXT_GOAL 계속
    - post → onExtensionEvent
    - 정산 창 사건 신호 무시, onSettle 1회 순서
    - CLOSING 중 playApproved 거절(R-10)
    - 예외 처리
    - 7개 이상

### L6 계정 삭제 (Repository + Business logic + API)

- [x] **Step 17. 삭제 처리기** — 구현
  - `RealtimeDeletionParticipant` 인터페이스(`order()` + 기존 `deleteByGuardian` 모양 · Map 반환). 기존 음성(100) · 메시징(900)에 구현을 붙인다.
  - 세션 참가자(200) 신설: 스냅숏 → 세션 사건 → 종료 표현 → 전사 → 원음 메타 → 세션 행 순 실제 삭제
  - **DeletionRequestedHandler**(InboxHandler)
    - ① 그 보호자의 비종료 세션 실행기 강제 정지(deletion-stop-wait, DB 전이 없음, 연결이 살아 있으면 CLOSE_TRANSPORT(CONSENT_WITHDRAWN))
    - ② 순서대로 참가자 호출(150 빈 없으면 건너뜀)
    - ③ 마지막 트랜잭션에 DeletionCompleted{counts}(같은 요청 ID면 쓰지 않음)
    - ④ 발행 뒤 메시징 참가자 1회 더
  - 재시도 건수는 Inbox 처리 기록에 누적해 합산한다(R-12).
  - 실패는 예외로 Inbox 재시도에 맡긴다.
  - — FR8.1 · BR9.1 ~ BR9.5 · NFR9
- [x] **Step 18. L6 시험 작성 · 실행**
  - `RealtimeDeletionHandlerIT`
    - 진행 세션 있음 · 없음
    - 재시도 멱등과 counts 누적(R-12)
    - 파일 삭제 실패 → 재시도
    - 정지 상한 초과
    - 늦은 사건
    - 삭제 뒤 행 · 파일 0건
    - 순서 값 호출 순서
  - `RealtimeDeletionParticipantOrderTest`

### L7 흔들리는 시험 · 개인정보 · 검증 스크립트 · 문서

- [x] **Step 19. 흔들리는 시험** — `RealtimeInternalApiIT`의 Core 취소 시험에서 실행기 없는 CLOSING 행을 단언 바로 직전에 만들도록 순서만 바꾼다. 단언 줄은 바꾸지 않는다. 반복 실행(예: 5회)으로 202를 확인한다. — FR11.2
- [x] **Step 20. 개인정보 검사 시험** — `PrivacyLogU4bTest`: 참여 · 확인 질문 · 공백 · 정산 · 삭제 경로에서 로그(`LogCapture`) · SessionEvent payload · Outbox(스냅숏 record 제외)에 전사 원문 · 원음 경로가 0건인지 확인한다. — NFR4 · BR12.1
- [x] **Step 21. 검증 스크립트 시드 003** — `scripts/verify` 시드 순서에 `infra/seed/003-u4a-reference.sql`을 넣는다(001 → 002 → 003). 끝-끝 C 시나리오에 참여 응답 단계를 더한다. 실행은 Build and Test에서 한다(리눅스 · Docker 필요). — FR11.4
- [x] **Step 22. 계약 문서 · 편차** — FR11.5 · NFR8
  - `contracts/deviations.md`: (U4b-a) `session.response.create`, (U4b-b) 끝내기 응답표, (U4b-c) CLOSING endReason · record 모양, (U4b-d) practice는 객체/null
  - `ai-io.md` 3절, `internal-api.yaml`, `events.asyncapi.yaml` 설명
  - 사유 설명에 PRACTICE_COMPLETED 사용
  - ContractFilesTest를 실행한다.
- [x] **Step 23. 설계 · 보고 문서** — FR11.5
  - `docs/units/u4b/design.md`: J24 갱신, 확장점 이름 · 능력(5.1), Q3 ~ Q6으로 바뀐 가정, R-03 · R-10 ~ R-12 처리
  - `docs/units/u4b/report.md` 신규: 만든 것, 시험 결과(건수 · 커버리지), 계약 편차, 가정, FR11.1 · FR11.3에서 고친 기대값 목록, 남은 일
- [x] **Step 24. 범위 커버리지 · 회귀 · 추적** — NFR6 · NFR7
  - `u4b.txt`에 새 · 바뀐 운영 파일을 모두 넣는다.
  - u4b 범위 커버리지 80% 이상, U1 · U3 회귀, 모듈 전체 check를 돌린다.
  - `source-manifest.json` · `traceability.json` · `code-summary.md`를 쓴다.

## 5. 시험 수량 요약 (Standard: 구성요소마다 5 ~ 8개 + 경계 통합 시험)

| 구성요소 | 시험 클래스 | 종류 |
|---|---|---|
| 스키마 · 저장소 | RealtimeSchemaIT(확장) · ResultInputSnapshotRepositoryIT | 통합 |
| 시간 키 | ConfigKeyContractTest(기대값) | 단위 |
| 전이표 · 끝내기 | TransitionTableU4bTest · EndRequestU4bTest · SessionStateEmitterU4bTest | 단위 |
| 참여 흐름 | JoinTrackerTest · SessionJoinU4bTest · SessionLifecycleU4bIT | 단위 · 통합 |
| 종료 의도 · 공백 · 일시정지 · 조회 | StopConfirmFlowTest · SessionStopU4bTest · SilenceClockTest · TechnicalPauseU4bTest · SessionViewU4bIT | 단위 · 통합 |
| 정산 · 스냅숏 | ResultSnapshotBuilderTest · SessionRecordU4bIT | 단위 · 통합 |
| 연습 확장점 | PracticeExtensionU4bTest | 단위 |
| 삭제 | RealtimeDeletionHandlerIT · RealtimeDeletionParticipantOrderTest | 통합 · 단위 |
| 개인정보 | PrivacyLogU4bTest | 단위 |

## 6. 요구사항 · 규칙 ↔ 단계

| 요구사항 | 규칙 | 단계 |
|---|---|---|
| FR1.1 ~ FR1.5 | BR1.1 ~ BR1.9 · BR7.2 | 6 · 7 |
| FR2.1 ~ FR2.4 | BR2.1 ~ BR2.7 · BR3.1 ~ BR3.3 | 8 · 9 |
| FR3.1 · FR3.2 | BR4.1 ~ BR4.7 | 8 · 10 · 11 |
| FR4.1 | BR5.1 ~ BR5.4 | 10 · 11 |
| FR5.1 | BR6.1 ~ BR6.4 | 10 · 11 |
| FR6.1 | BR7.1 | 10 · 11 |
| FR7.1 · FR7.2 | BR8.1 ~ BR8.8 | 3 · 12 · 13 · 14 |
| FR8.1 | BR9.1 ~ BR9.5 | 17 · 18 |
| FR9.1 | BR10.1 ~ BR10.6 | 15 · 16 |
| FR10.1 | BR11.1 | 4 · 5 |
| FR11.1 ~ FR11.5 | — | 5 · 7 · 9 · 11 · 19 · 21 · 22 · 23 |
| NFR1 · NFR2 | BR11.1 | 4 · 9 · 11 |
| NFR3 | BR1.2 · BR1.6 · BR8.5 · BR8.7 | 7 · 14 |
| NFR4 | BR12.1 | 20 |
| NFR5 | BR2.6 | 9 |
| NFR6 · NFR7 | — | 1 · 2 · 24 |
| NFR8 | BR8.8 | 13 · 14 · 22 |
| NFR9 | BR9.3 | 18 |

## 7. 위험과 대응

- **실행기 변경 폭(D6)**: 판정은 순수 클래스로 빼고, 실행기는 연결만 늘린다. 층마다 U1 · U3 회귀를 돌린다.
- **U1 · U3 시험 기대값 변경**: 활성화 경로 · status_version · 전사 행 수 · 해제 경로만 바꾼다. 단언을 줄이지 않고, 바꾼 목록을 report에 남긴다.
- **리눅스 전용 검사**: infra-validate · 이미지 관문 · 스켈레톤 검증은 이 PC(WSL)에서 돌 수 있으면 Build and Test에서 돌리고, 안 되면 PR의 CI로 확인한다.
- **U5b 병합 충돌**: `contracts/timing.md` · `deviations.md`. U4b가 먼저 합쳐진다.
