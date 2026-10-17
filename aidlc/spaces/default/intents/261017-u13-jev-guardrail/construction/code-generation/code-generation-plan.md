# U13 제브 게이트 — 코드 생성 계획

> 작업 기록 `261017-u13-jev-guardrail` · Code Generation(3.5) · 브랜치 `unit/u13-jev-guardrail` · 2026-10-17
> 근거: `../functional-design/{functional-spec,rules,entities}.md` · `traceability.json`, `../../inception/requirements-analysis/requirements.md`, 코드 스캔 `../../inception/reverse-engineering/developer-scan.md`, 지시 `docs/units/u13/brief.md`. 이 기록은 유닛 나누기를 건너뛰어 산출물을 유닛 폴더 없이 `construction/code-generation/`에 쓴다.
> 시험 전략 Standard(컴포넌트당 5 ~ 8개 + 핵심 경계 통합 시험), 방법 test-after(층 하나 구현 → 그 층 시험 작성 · 실행 → 다음 층). 모든 시험은 가짜 제브 · 가짜 공급자만 쓴다(R-F1). 합격 증거는 테스트 대역이다(R-M11).

## Testing Contract

```json
{
  "version": 1,
  "methodology": "test-after",
  "source": "team",
  "ordering": "시험할 수 있는 층을 하나 구현하면, 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 넘어간다.",
  "scope": "refactor",
  "test_strategy": "standard",
  "project_type": "brownfield",
  "applicable_notes": [
    {
      "layer": "org",
      "text": "We treat tests as a first-class deliverable in every Bolt. The specific\nmethodology (TDD, BDD, ATDD, or classic test-after) is affirmed at\npractices-discovery and recorded in `team.md` under this heading with explicit\n`Methodology` and `Ordering` fields; Code Generation resolves those fields\nindependently from coverage, tooling, and scope notes.\n\nWhen no posture has been affirmed, our default per scope is:\n- **Methodology**: test-after\n- **Ordering**: implement each applicable testable layer, then write and run\n  that layer's tests.\n- `mvp`, `enterprise`, `feature`, `infra`, `classic` add an 80% line-coverage\n  floor and CI execution before merge.\n- `bugfix`, `security-patch` add a targeted regression for the specific\n  bug/vulnerability and require the existing suite to remain green.\n- `express` uses the Minimal strategy: requirement-driven unit tests (one per\n  requirement, with a happy-path floor per component); existing tests remain\n  green.\n- `poc`, `refactor`, `workshop` add no extra new-test floor and require the\n  existing suite to remain green.\n\nThe active `Test Strategy` still applies in every scope and determines test\nvolume/types. Scope floors are additive; they never reduce or replace the\nselected strategy.\n\nBuild and Test verifies defined coverage floors and affirmed quality targets;\nthey may not be weakened to make a step pass.\n\nAffirm a stricter posture in `team.md` if the team commits to one."
    },
    {
      "layer": "team",
      "text": "- **Methodology**: test-after\n- **Ordering**: 시험할 수 있는 층을 하나 구현하면, 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 넘어간다.\n- 계약에 닿는 변경도 같은 순서를 따른다(Q3-A). 다만 설계 문서 변경이 계약에 닿으면 같은 작업에서 `contracts/`도 함께 고친다(`discovered-rules.md` R-M10).\n- 커버리지 하한(줄 기준만, 분기 · 함수 하한 없음):\n  - 모듈 전체 줄 80%를 CI가 막는다. 백엔드 JaCoCo는 `contract-model` · `core-api` · `realtime-activity`(`test-support`는 제외), 프런트 Vitest v8은 `src/**/*.{ts,tsx}`(`*.d.ts`만 제외).\n  - 유닛 범위 줄 80%는 범위 파일(`backend/coverage-scope/<유닛>.txt` · `frontend/coverage-scope/<유닛>.json`)이 있는 작업에 적용한다. **CI가 실제로 막는 범위는 U5b · U7뿐이다**(백엔드 `u5b` · `u5b-core` · `u7`, 프런트 `u5b` · `u7`). 나머지 범위 파일은 로컬 · 검토 근거다.\n  - `tools/`에는 커버리지 하한이 없다(시험 지원 코드, J107).\n  - 범위 측정은 빈 목록 · 없는 파일이면 실패한다(J137). 범위 파일을 새로 쓰면 이 방식을 그대로 따른다.\n  - `classic` 범위의 줄 80% 하한 · 병합 전 CI 실행과 같다.\n- 하한 · 기존 단언 · 대조 시험 · ArchUnit 시험은 시험을 통과시키려고 낮추거나 끄거나 건너뛰게 하지 않는다. 모자라면 시험을 더하거나 차이를 보고한다.\n- 시험 종류:\n  - 단위: JUnit 5 · AssertJ · Mockito(백엔드), Vitest + Testing Library + jsdom(프런트), Vitest(tools).\n  - 통합: Spring Boot 통합 시험 + Testcontainers PostgreSQL(`-Pmujoong.test.db=docker`), 상대 앱은 스텁.\n  - 계약 대조: `ContractFilesTest` · `ErrorCodeTest` · `ConfigKeyContractTest` · `TimeLiteralScanTest` · `OpenApiContracts` · `frontend/tests/u1/shared/timing.test.ts` · `SeedDictionaryFileTest`가 `contracts/`와 코드를 대조한다.\n  - 아키텍처: ArchUnit(`core/arch` · `realtime/arch`), ESLint `/parent` ↔ `/device` ↔ `shared` import 경계.\n  - 끝-끝: `bash scripts/verify.sh` C안(기기 시뮬레이터 + 가짜 공급자).\n  - 인프라: `infra-validate`(Compose 불변식 · `nginx -t` · shellcheck · 마이그레이션 expand 검사), 이미지 점검, 관문 음성 대조.\n- 시험 이름 · 태그: 단위 `*Test`, 통합 `*IT`, 백엔드 유닛 태그 `@Tag(\"u1\")` ~ `@Tag(\"u7\")`와 `@Tag(\"db\")`, 프런트 · tools는 `tests/<유닛>/` 폴더.\n- 관문의 한계: PR의 `ci-gate`는 정적 · 단위 · 통합 · 범위 커버리지 · 보안 · 인프라 · 이미지까지 본다. 끝-끝 회귀(`skeleton`)는 `main` 병합 뒤에 처음 돈다. 그래서 이번 작업은 Build and Test의 전체 `verify.sh`로 병합 전 끝-끝을 확인한다.\n- 시험 · CI · 검증에서는 가짜 공급자만 쓴다(ADR-002).\n- 결과 보고는 테스트 대역 · 합성 대화 · 실제 API · 실제 기기 성공을 따로 적고, 대역 성공을 실공급자 성공으로 표시하지 않는다(`discovered-rules.md` R-M11, Q10-A).\n- 이번 작업 기록의 시험 전략은 Standard다(컴포넌트당 5 ~ 8개 + 핵심 경계 통합 시험)."
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
  "input_sha256": "sha256:cf30d2355aca9923bc50fde838dea7e43f427b9bf61958052caae840662d7c40",
  "contract_sha256": "sha256:5d6b9d7096340e0b040d134b020e781b77463b2dd951572f4b7f4829522c435e"
}
```

## 층별 적용 (Testing Contract 대응)

| Testing Contract 층 | 이 단위에서 | 단계 |
|---|---|---|
| Project structure · production configuration | 새 패키지 `kr.mujoong.realtime.guardrail`, 모드 키 `mujoong.guardrail.jev.mode` · 주소 · 모델 설정, 계약 정정(`ai-io.md` · 스키마 예시 판 이름 · `deviations.md`) | Step 1 |
| 시험 실행 준비 | 기존 `./gradlew :realtime-activity:test -PincludeTags=u13` 확인 | Step 2 |
| Data model / database behavior | realtime V6 `guardrail_event` | Step 3 · 4 |
| Repository / data access | 저장소 · 비동기 기록기 · 삭제 참여자 | Step 5 · 6 |
| Business logic | 포트 · 모델 · 요청 구성 · 대역 · 기동 점검 · 어댑터 · 협력 객체 · 실행기 연결 · 승인 원문 생략 · 계측 | Step 7 ~ 14 |
| API / endpoint | 해당 없음(새 HTTP · WS 경로 없음). 공급자 선 형식은 '외부 연동'으로 Step 9 · 10에서 시험 | — |
| Frontend behavior | 해당 없음(프런트 변경 없음, `functional-design/frontend-components.md`) | — |
| Environment/build configuration | 비밀 이름 검사 목록 · 인프라 정렬 · 범위 파일 · ArchUnit · 표식 지도 | Step 15 |
| Documentation and traceability | `docs/units/u13/design.md` · `report.md` · 편차 · README | Step 16 |

## 단계

단계마다 `feat(u13): …`로 커밋한다(문서 단계는 `docs(u13): …`). 각 단계는 끝나면 그 층의 시험을 돌린 결과를 `code-summary.md`에 적고, 계획 체크박스를 표시한다. 규칙 ID는 `../functional-design/rules.md`, 요구사항 ID는 `../../inception/requirements-analysis/requirements.md`다.

### 묶음 1 — 기반 (V6 · 포트 · 대역 · 모드 · 기동 점검)

- [x] **Step 1. 구조 · 설정 뼈대 · 계약 정정** (FR1.3 · FR2.1 · FR2.3 · FR8.2, BR1.3 · BR2.7)
  - 새 패키지 `kr.mujoong.realtime.guardrail`(`package-info` 없이 클래스로 시작). 설정 레코드(`RealtimeProperties`)에 `guardrail.jev`(mode · 주소 · 모델 이름, 기본값 규칙은 BR1.3 · BR9.1)를 더한다. 시간 값은 이미 있는 `mujoong.timing.guardrail.jev-budget`(800ms)를 쓰고 새 시간 키를 만들지 않는다(R-F7).
  - `application.yml`에 모드 · 주소 · 모델 기본값을 둔다. 주소 · 모델은 환경 변수 이름만 쓰고 실제 호스트 값은 허용 호스트 상수 한 곳에만 둔다(R-F3, Q4 답 A). `application-test.yml`은 `segment-quiet` 50ms를 그대로 두고, 이 단위의 통합 시험만 운영값 800ms로 올린다.
  - 계약 정정: `contracts/ai-io.md` 10절에 모드 키와 허용값, 재시도 분류 표의 제브 행("재시도 없음, 시간 초과는 막힘"), 스키마 예시의 판 이름 `jev-guardrail-v1` 정정, 허용 호스트 대조 한 줄. `contracts/deviations.md`에 `(U13-a)`(모드 키) · `(U13-b)`(재시도 표 행 · 스키마 예시 정정)를 적는다(R-M10).
  - `ConfigKeyContractTest`가 새 키를 `ai-io.md`와 대조하도록 맞춘다(약화하지 않고 키를 더한다).
- [x] **Step 2. 시험 실행 준비** (Testing Contract 러너 단계)
  - `./gradlew :realtime-activity:test -PincludeTags=u13 -Pmujoong.test.db=docker`가 빈 태그에서도 도는지 확인하고 정확한 명령을 `unit-test-instructions.md`에 적는다. `backend/coverage-scope/u13.txt`(새 범위 파일, 줄마다 이 단위가 만들거나 고친 운영 소스 경로, 앞 주석에 정직하게 적음, `SessionExecutor`는 목록에 넣지 않고 사유를 적음)를 만들 자리를 잡는다(Step 15에서 채움).
- [x] **Step 3. 데이터 구현: realtime V6** (FR6.1, BR8.1)
  - `V6__u13_jev_guardrail.sql`: 표 `guardrail_event`(칸은 `entities.md` `GuardrailEvent`와 같다. 전사 원문 칸 없음, CHECK 제약으로 mode · source_kind · decision 고정, 세션 FK `ON DELETE RESTRICT`, `guardian_id` 색인, 시각은 앱 `MujoongClock` 바인딩, DB 기본값 없음). 기존 표 제약은 건드리지 않아 `infra/migration-approvals.txt` 대상이 아니다(R-M3).
- [x] **Step 4. 데이터 시험** (BR8.1)
  - 새 `GuardrailEventSchemaIT`(`@Tag("u13")` `@Tag("db")`): 표 · 제약 · 색인 · FK. 기존 단언을 V6 사실로만 갱신(느슨하게 하지 않음): `RealtimeSchemaIT`(표 목록 · 이력 버전 · 최대 버전 "6"), `PracticeMigrationIT` · `PracticeMigrationRegistrationTest`(`V6__u13_jev_guardrail.sql` 존재, 기대 판 "6"), `RealtimeDb`(비우는 표 목록에 `guardrail_event`를 세션보다 먼저).
- [x] **Step 5. 저장소 구현** (FR6.1 · FR6.2, BR8.2 · BR8.3)
  - `GuardrailEventRepository`(`Transactions.requireActive` 안에서만 쓰는 upsert, id 충돌 덮어쓰기), `GuardrailEventRecorder`(U11 `JudgmentLiveRecorder`와 같은 모양의 새 클래스: 비동기 칸 · 일시 실패 재시도 · 23503 · 23514 폐기 · 삭제 중 보호자 거절, `@SingleInstanceAssumption(si = "SI-10")`), `GuardrailEventDeletionParticipant`(순서 185).
- [x] **Step 6. 저장소 시험** (BR8.2 · BR8.3)
  - `GuardrailEventRepositoryIT`, `GuardrailEventRecorderTest`, `GuardrailEventDeletionParticipantIT`: 덮어쓰기, FK 위반 폐기, 일시 실패 재시도, 실행기 비대기, 삭제 뒤 0건. 기존 `SingleInstanceMarkerTest`(SI-10 지도) · `RealtimeDeletionParticipantOrderTest`를 새 사실로 갱신.
- [x] **Step 7. 비즈니스 로직 구현 1: 포트 · 모델 · 요청 구성 · 대역 · 기동 점검** (FR1.1 ~ FR1.3 · FR2.2 · FR7.1, BR1.1 ~ BR1.7 · BR2.4 · BR2.5 · BR9.1 · BR9.2)
  - `GuardrailClient`(포트), `GuardrailRequest` · `GuardrailVerdict`(값 객체), `GuardrailRequestFactory`(후보 + 익명 맥락 3턴, 이름 치환, 길이 상한, 지시문은 데이터 필드에만), `StubGuardrailClient`, 비활성 구현, `GuardrailSupport`(`DISABLED` 기본값으로 `SessionExecutor.Services` 오버로드를 늘리지 않음), 모드 선택 설정 + `RealtimeStartupChecks.problems`의 모드 × 프로필 × 키 규칙(`Inputs`에 필드를 더하되 기존 생성자 오버로드 유지), 비실검사 기동 로그(`GuardrailModeOff` · `GuardrailModeStub`).
  - ArchUnit: `guardrail` 패키지가 `luna` · `voice` · `judgment`를 가져오지 않는 새 규칙(`allowEmptyShould(false)`), 기존 `LunaArchTest` 규칙은 약화하지 않는다.
- [x] **Step 8. 비즈니스 로직 시험 1** (BR1.x · BR2.4 · BR2.5 · BR9.1 · BR9.2)
  - `GuardrailModeStartupChecksTest`(W6 표의 모든 칸), `StubGuardrailClientTest`, `GuardrailRequestFactoryTest`(이름 치환 · 식별자 필드 없음 · 맥락 3턴 · 지시문 격리 · 길이 상한 · 판 이름), `GuardrailPackageArchTest`. 기존 U8 · U11 기동 점검 시험은 단언 변경 없이 통과해야 한다.

### 묶음 2 — 실제 어댑터

- [x] **Step 9. 비즈니스 로직 구현 2: 실제 어댑터** (FR2.1 · FR1.2, BR1.2 · BR2.1 ~ BR2.3 · BR2.6 · BR2.8)
  - `JevGuardrailClient`(HTTPS POST 한 번, 재시도 없음, `jev-budget` 상한, 키는 호출 때마다 환경에서 읽음, 리다이렉트 · 프록시 없음, 모든 실패를 `GuardrailVerdict.FAILED`로 완료), `JevHosts`(허용 호스트 상수 + 기동 · 직전 확인), 응답 → 내부 형식 변환 한 곳, 정책 판 불일치 확인, 이동 별칭 모델 거부.
- [x] **Step 10. 비즈니스 로직 시험 2** (BR2.x)
  - `JevGuardrailClientTest`(가짜 제브 서버: ALLOW · BLOCK · REVIEW 변환, 시간 초과 · 전송 오류 · 잘못된 JSON · 판 불일치, 호출 1회 · 재시도 없음, 800ms 경계는 수동 시계, 키 값이 본문 · 로그에 없음), `JevHostsTest`. 실제 제브는 부르지 않는다(R-F1).

### 묶음 3 — 게이트 단계 (실행기 연결)

- [x] **Step 11. 비즈니스 로직 구현 3: 협력 객체 · 실행기 연결** (FR3.1 ~ FR3.3 · FR4.1 ~ FR4.3, BR3.1 ~ BR3.4 · BR4.1 ~ BR4.5 · BR5.1 ~ BR5.6 · BR6.1 ~ BR6.6)
  - `GuardrailCoordinator`(+ `Env`): 검사 상태 기계(IDLE · CHECKING · RETRYING), 검사표(요청 id · `Sealed` · 두 세대 · 연결 id · 입력 묶음 열림 횟수 · 보관 손잡이), 대기열 1칸, 턴 상태, 무효 판정 8경우, 재지시.
  - `SessionMailbox.GuardrailArrived`(일반 편지함 `putFromProvider`로 보냄, 안전 편지함 아님). `SessionExecutor` 호출 지점(설계 4절 ① ~ ⑧): `sealAndGate`의 inspect · 연습 확인 뒤 `begin`(전용 try/catch), `GuardrailArrived` 사례, 아이 확정 전사 → `onNewTurn`, `teardown` → `onExit`, `listeningAgain`의 THINKING 유지 조건, `sendInstruction` 호출 지점 두 곳(판정 뒤 다음 행동 지시 · `requestResponse`)에서 마지막 서버 지시 기억과 다른 종류에서 지움, `groupInputDelta`의 입력 묶음 열림 횟수 카운터, `discard()` 맨 앞의 `RETRYING` 풀기 훅(사유가 `GUARDRAIL_*`가 아닐 때).
  - `TranscriptWriter.aiHold()` / `aiFinal(손잡이, …)` / `closeAll`이 보관 행도 닫음. `GateDecision.DiscardReason`에 `GUARDRAIL_*` 5개 더하기와 `RealtimeMetrics.gateDiscard` 태그 갱신. `InstructionTemplates`에 `RETRY_FREE_TURN`, `OutputGeneration.BumpReason`에 `GUARDRAIL_RETRY`.
  - 설계 후속 메모(승인된 설계 문서는 고치지 않고 이 계획과 `code-summary.md`에 적는다): ① 아키텍처 검토 R-17 — "`discard`의 마무리가 `listeningAgain`까지 한다 / 기기가 THINKING에서 풀린다"는 표현은 연습 폐기 가지(`return`)와 대기 문구 재생 가지에서 그대로 참이 아니다. 구현은 'discard의 기존 마무리(대기 문구 재생 또는 `listeningAgain`, 연습 폐기 가지는 연습 쪽이 마무리)가 이어지고, 협력 객체는 어느 쪽이든 IDLE'로 읽는다. ② `LatencyRecorder`의 겹치는 턴(BR5.6)과 `recheck`의 출력 레인 예약 시점은 이 단계에서 코드로 확인하고 결정을 `code-summary.md`에 적는다.
- [x] **Step 12. 비즈니스 로직 시험 3** (BR3.x · BR4.x · BR5.x · BR6.x)
  - `GuardrailCoordinatorTest`(14개 이상: 정상 ALLOW, 막힘 일곱 가지, 재지시 → 재막힘 포기, 재지시 불발 포기, 재지시 응답이 `GATE_PATTERN` · inspect 실패 · 오버사이즈 · `ABANDON_NO_AUDIO` · 공급자 오류 · `begin` 예외로 폐기되어도 `RETRYING`에서 빠져나와 기기가 THINKING에 갇히지 않음, `sealed == null`은 조기 포기하지 않음, 무효 8경우, 꼬리 조각 vs 새 묶음, 대기열 보관 · 꺼냄 · 앞 검사가 ALLOW가 아니면 세대와 무관하게 폐기 · 가득 참), `GuardrailExecutorTest`(10개 이상: 위치, 승인 시점이 ALLOW 뒤, THINKING 유지, 연습 통제 중 호출 0, 원문 일치 생략, teardown 취소), `TranscriptWriterHoldTest`(5개), `GuardrailPracticeEventTest`(3개). 제브 대역은 시험이 손으로 푸는 `CompletableFuture`다. 기존 U3 · U4b · U5b · U11 실행기 시험은 단언 변경 없이 통과해야 한다(BR1.6).

### 묶음 4 — 승인 원문 · 보호자 화면 · 계측 · 통합

- [x] **Step 13. 비즈니스 로직 구현 4: 승인 원문 생략 · 계측 · 확장점 이벤트** (FR5.1 ~ FR5.2 · FR6.3 · FR6.4 · FR3.2, BR6.5 · BR7.1 ~ BR7.6 · BR8.4 ~ BR8.6)
  - `LiveContentRegistry`(좁은 포트)를 `GuardrailSupport`로 `Services`에 연결, 분류 순서(연습 `pendingPracticeSpeech.matches` → 레지스트리 `MATCHED` → 새 발화), `APPROVED_VERBATIM_SKIPPED` 기록. 연습 확장점 이벤트 `GuardrailBlocked`(정의만, 운영 호출자는 U12). `RealtimeMetrics`에 `mujoong.guardrail.*`(호출 수 · 지연 히스토그램 · 차단 · 시간 초과 · 체감 대기)와 `application.yml` 히스토그램 버킷. 새 사건 코드는 만들지 않는다(`appError` 사유 코드만, 편차 U13-D2). 묶음 3 보고의 남은 위험: 제브 클라이언트가 규약을 어기고 영영 완료하지 않으면 협력 객체가 CHECKING에 머문다. 실행기 쪽 안전 타이머(`SessionTimers.Kind` 추가)가 필요한지 이 단계에서 결정하고 근거를 `code-summary.md`에 적는다.
- [x] **Step 14. 비즈니스 로직 시험 4 · 통합** (BR7.x · BR8.4 ~ BR8.6)
  - `GuardrailApprovedSkipTest`(원문 일치 → 호출 0, 변경된 문장 · UNKNOWN → 호출 1), `GuardrailMetricsTest`, `GuardrailTurnIT`(가짜 공급자 + 수동 완료 가짜 제브, `segment-quiet` 운영값 800ms: ALLOW 한 턴이 기기로 나감, BLOCK이면 0바이트 + 재지시 + 보호자 화면 12 · 27에 승인된 행으로 안 나옴, 늦은 ALLOW 무효, 대기열). 기존 `RealtimeMetricsScrapeIT` · `SessionVoiceU3IT` 등은 단언 변경 없이 통과.

### 묶음 5 — 환경 · 문서

- [x] **Step 15. 환경 · 빌드 설정** (FR7.2 · FR8.1, BR9.3)
  - 인프라 정렬: `infra/bin/render-env`의 realtime `JEV_API_KEY`를 demo 필수로, `infra/ci/image-checks.sh` ④의 demo 더미 기동에 실행 때 만든 가짜 키와 가짜 호스트 사용(`.gitleaks.toml` 허용 항목은 가짜 값의 경로 + 줄 정규식 + 사유로만, R-F9), `infra-validate` · `test-infra-validate.sh` · `test-host-tools.sh` 표본, `infra/RUNBOOK.md`(키를 먼저 Parameter Store에 넣는 순서, 키 없는 demo에 배포하면 realtime이 기동을 거부한다는 사실), `backend/coverage-scope/u13.txt` 채우기, `@Tag("u13")` 확인. 새 비밀 이름은 없다(R-M9 목록은 U8이 이미 반영).
- [x] **Step 15b. 제브 실호출 준비** (FR8.2 · R-F1 · R-F3 · R-F13 · R-F15, 계획 재승인으로 추가)
  - `jevLiveTest` Gradle 작업(`:realtime-activity`, `lunaLiveTest`와 같은 방식: 기본 `test` · `check` · CI · `verify.sh`에는 들어가지 않고, 키는 그 PC의 셸 env `JEV_API_KEY`로만 읽음, 없으면 건너뛰지 않고 명확히 실패하지 않는 안내 종료). 합성 문장만 보내고 결과(ALLOW · BLOCK · REVIEW · 실패 종류, 지연)를 `build/jev-live/report.txt`에 남기며 후보 문장 원문은 보고서에 쓰지 않는다(R-F14). 실호출 결과는 `[실호출-합성]`으로 따로 보고한다(R-M11).
  - `infra/RUNBOOK.md`에 `jevLiveTest` 절(8.5절 Luna와 같은 틀): R-F15 확인 기록을 먼저 남긴 뒤에만 키를 넣는다, 키는 `read -r -s -p … && export JEV_API_KEY`로만 넣고 끝나면 `unset`, 채팅 · 파일 · 저장소에 두지 않는다. 호스트 경로(Parameter Store `/mujoong/demo/JEV_API_KEY` → `render-env` → `realtime.env`)와 `JEV_BASE_URL` · `JEV_MODEL` 전달(비밀 아님)도 같은 절에서 설명한다.
  - 기본 빌드가 이 작업을 부르지 않는다는 것을 확인하는 점검(`check`의 작업 목록에 없음)을 시험 하나로 못 박는다. 실제 제브는 이 단계에서도 부르지 않는다(키는 사람이 넣는다).
- [x] **Step 16. 문서 · 추적** (FR8.2)
  - `docs/units/u13/design.md`(Functional Design 결과, 지표 이름 · 종류 포함), `docs/units/u13/report.md`(증거를 테스트 대역 · 합성 대화 · 실제 API · 실제 기기로 나눠 보고, 제브 실호출은 사람 몫인 열린 항목), `docs/units/README.md` 현황, `code-summary.md`, `source-manifest.json`, `traceability.json`.

## 규칙 → 단계 추적

| 규칙 | 단계 | 규칙 | 단계 |
|---|---|---|---|
| BR1.1 ~ BR1.2 | 7 · 9 | BR6.1 ~ BR6.4 | 11 · 12 |
| BR1.3 | 1 · 7 | BR6.5 | 13 · 12 |
| BR1.4 ~ BR1.5 | 7 | BR6.6 | 11 · 12 |
| BR1.6 | 7 · 12 | BR7.1 ~ BR7.4 | 13 · 14 |
| BR1.7 | 7 | BR7.5 | 13 · 14 |
| BR2.1 ~ BR2.3 | 9 · 10 | BR7.6 | 11 · 12 |
| BR2.4 ~ BR2.5 | 7 · 8 | BR8.1 | 3 · 4 |
| BR2.6 | 9 · 10 | BR8.2 ~ BR8.3 | 5 · 6 |
| BR2.7 | 1 | BR8.4 | 11 · 14 |
| BR2.8 | 9 · 10 | BR8.5 ~ BR8.6 | 13 · 14 |
| BR3.1 ~ BR3.4 | 11 · 12 | BR9.1 ~ BR9.2 | 7 · 8 |
| BR4.1 ~ BR4.5 | 11 · 12 | BR9.3 | 15 |
| BR5.1 ~ BR5.6 | 11 · 12 · 14 | | |

## 알려진 위험

- `SessionExecutor`(약 3,400줄)는 U12가 이어서 고친다. 호출 지점만 더하고 상태 필드는 협력 객체에 둔다(NFR8). 입력 묶음 열림 횟수 카운터만 실행기에 둔다.
- 기존 시험이 `TranscriptWriter` · `SessionExecutor.Services` 생성자 모양에 기대므로 오버로드를 유지한다.
- 실제 제브 응답 형식은 모른다. 어댑터 변환의 형식 가정을 시험 하나로 못 박고, 실호출은 사람이 키를 넣은 뒤 한다(R-F13 · R-F15).
- 이 PC에서 `verify.sh` 전제 도구(`jq` · `unzip` · `shellcheck`)와 `sg docker -c '…'`를 쓴다(Build and Test).

## Sources

- `../functional-design/functional-spec.md` · `rules.md`(BR1.1 ~ BR9.3, 51개) · `entities.md` · `traceability.json`
- `../../inception/requirements-analysis/requirements.md`(FR1 ~ FR8, NFR1 ~ NFR9)
- 코드 스캔 `../../inception/reverse-engineering/developer-scan.md`, 코드 지식 베이스 `aidlc/spaces/default/codekb/mujungi/`
- 아키텍처 검토 `../functional-design/reviews/review-0N.md`(R-17 포함)
- 지시 `docs/units/u13/brief.md`, 앞 볼트 `docs/units/u11/design.md` · `report.md`
- 팀 규칙 `team.md` · `project.md`(R-F1 · R-F2 · R-F3 · R-F5 · R-F6 · R-F7 · R-F8 · R-F9 · R-F14 · R-F15 · R-M2 · R-M3 · R-M5 · R-M9 · R-M10 · R-M11)
