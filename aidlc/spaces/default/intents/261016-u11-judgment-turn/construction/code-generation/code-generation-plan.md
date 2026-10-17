# U11 판정 턴 기반 — Code Generation 계획 (범위 확장 개정판, 2026-10-17)

> 근거: `../functional-design/functional-spec.md`(2.1 · 2.2 상태 기계, W1 ~ W10, 편차 U11-D1 ~ D8, 계약 편차 (U11-a) ~ (U11-c) 계획) · `rules.md`(BR1.1 ~ BR12.5) · `entities.md`, `../../inception/requirements-analysis/requirements.md`(2026-10-17 재승인 판, FR1 ~ FR13, NFR1 ~ NFR9), 지시 `docs/units/u11/brief.md`, 코드 지식 베이스 `aidlc/spaces/default/codekb/mujungi/`, 실호출 보고서 `backend/realtime-activity/build/gpt-live/report.md`.
> 범위: refactor · 깊이 Standard · 시험 전략 Standard · 브라운필드. `backend/realtime-activity`를 주로 고치고, 키 통일 때문에 `backend/core-api`(설정 주석 · 시험 이름만, 동작 그대로) · `tools/fake-provider` · `backend/test-support` · `infra/` · `scripts/` · `contracts/`도 손댄다. 프런트 · 기기 ↔ 서버 음성 형식은 건드리지 않는다.
> 이 계획은 첫 판(17단계, 옛 설계 기준)을 대체한다. 첫 판의 Step 1 · 2(시험 실행 준비 · `gptLiveTest`)는 끝났고, Step 3(사람 정지점)은 실호출 세 번의 결과로 "범위 확장"으로 닫혔다(`code-generation-questions.md` Step 3-1 ~ 3-3).
> 기능 설계 승인 때 감수한 검토 지적은 해당 단계에서 다룬다: 첫 판 R-13 · R-14 · R-15는 Step 22 · 7, 이번 판 R-04(INSTRUCTING 중 확정 전사) · R-18 · R-19는 Step 20, R-20은 Step 12 · 28이다.
> **두 묶음(요구사항 Q12-A)**: **PR1 선 형식 정비 · 키 통일**(Part B, Step 4 ~ 14)을 먼저 `main`에 합치고, **PR2 판정 턴**(Part C, Step 15 ~ 29)은 PR1 병합 뒤 `main`에서 새로 딴 브랜치에서 한다(team.md: 작업 브랜치는 `main`과 같은 커밋 위에서 새로 딴다).
> 브랜치: PR1 = `unit/u11-wire-format`(지금 `unit/u11-judgment-turn`의 Part A 커밋 위에서 `git switch -c`로 따고, PR1 안에 Part A 커밋 5개 · `aidlc/` 기록이 함께 들어간다). PR2 = `unit/u11-judgment-turn`(PR1 병합 뒤 `main`에서 새로 딴다). 단계마다 `feat(u11): …`(시험만이면 `test(u11)`, 문서는 `docs(u11)`, 인프라는 `chore(u11)`)로 커밋하고, 커밋 전에 `git diff --cached --summary`로 실행 권한 변경을 확인한다(project.md 교정).
> **컴파일 규칙**: 응답 id 제거처럼 파급이 큰 변경은 소비 코드를 고치는 단계와 같은 커밋 계열에서 끝내고, 커밋마다 `./gradlew :realtime-activity:compileJava :realtime-activity:compileTestJava`가 통과해야 한다(깨진 채 커밋하지 않는다).

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

## 층 적용

| Testing Contract 층 | 이 단위에서 | 단계 |
|---|---|---|
| Project structure · production configuration | 키 이름 · Luna 스위치 · 기동 점검 설정(PR1), 새 패키지 `kr.mujoong.realtime.judgment` · `Judgment` 설정(PR2) | Step 4 · 17 |
| Data model / database behavior | realtime V5 `realtime_judgment_live`(PR2) | Step 15 |
| Repository / data access | 판정 기록 저장소 · 비동기 기록기 · 삭제 참여자(PR2) | Step 16 |
| Business logic | 선 형식 요청 · 해석기 · 출력 세대 · 직렬 송신 경로 · 보류(PR1), `JudgeClient` · 지시 템플릿 · 판정 턴 · 실행기 연결 · 확장점(PR2) | Step 5 ~ 8 · 18 ~ 25 |
| API / endpoint | 공급자 선 형식은 이 층의 "외부 연동"으로 보고 Step 5 · 12에서 시험한다. 새 HTTP · WS 경로는 없다 | — |
| Frontend behavior | 해당 없음(프런트 변경 없음, `frontend-components.md`) | — |
| Environment / build configuration | `gptLiveTest` 운영 형식 전환, 가짜 공급자 2곳, 비밀 이름 검사 목록 · CI env, 커버리지 범위 파일 | Step 4 · 5 · 10 · 27 |
| Documentation and traceability | 계약 · 편차(`contracts/`), RUNBOOK, `docs/units/u11/design.md` · `report.md` | Step 9 · 28 · 29 |

## 단계

### Part A — 끝남 (첫 판)

- [ ] **Step 1. 시험 실행 준비 확인** — 끝남(첫 판).
- [ ] **Step 2. GPT-Live 격리 실호출 작업 `gptLiveTest`(와 Luna `lunaReachTest`)** — 끝남(커밋 62304b6 · d8cf862 · 1cdae3c · 06d9c65 · deebd36).
- [ ] **Step 3. 실호출 세 번과 계속 · 중단 결정** — 끝남. 결과: 서버 쪽 중계는 성립, 선 형식은 다름 → 사람이 범위 확장(B)을 고름(`code-generation-questions.md` Step 3-3). 아래 Part B가 그 결과다.

### Part B — PR1: 선 형식 정비 · 키 통일 (브랜치 `unit/u11-wire-format`)

- [x] **Step 4. 키 통일 · Luna 스위치 · 기동 점검** (FR13 · BR12.1 ~ BR12.5)
  - `PROVIDER_API_KEY` → `OPENAI_API_KEY`: realtime 운영 코드(`RealtimeStartupChecks` · `ProviderClient` · `application.yml` · `application-demo.yml` 주석), 실호출 도구, 시험. 키가 비면 연결 요청에 `Authorization` 헤더를 싣지 않는다(BR12.2).
  - 기동 점검: GPT-Live 키를 **demo 프로필에서만** 필수로 한다. CI · 검증 · 로컬은 키 없이 가짜 공급자에 연결한다. `infra/ci/gen-env.sh`는 realtime env에 자리값을 넣지 않고 `OPENAI_API_KEY=`(빈 값)만 둔다.
  - realtime Luna: `mujoong.luna.enabled`(기본 `false`)를 `application.yml` · 설정 레코드에 더하고(시간 값 아님), `LunaKeyPresentCondition`을 "키 있음 AND 스위치 true"로 바꾼다. `LUNA_STARTUP lunaState`에 `DISABLED_SWITCH_OFF`를 더한다. core-api Luna는 동작을 바꾸지 않는다(키만으로 켜짐).
  - 비밀 이름 검사 목록 전부(R-M9): `.github/workflows/ci.yml` 번들 검사, `infra/ci/image-checks.sh`, `infra/bin/infra-validate` · `render-env` · `log-hygiene-check`, `scripts/verify/`(`lib.sh` · `b-stack.sh`), `infra/ci/gen-env.sh`, `README.md`, `infra/RUNBOOK.md`(Parameter Store 항목), 불변식 표본 · `test-infra-validate.sh` · `test-host-tools.sh`. 값은 어디에도 쓰지 않는다(R-F3).
  - RUNBOOK 단계 4(BR12.4): R-F15 확인 대상을 "Luna 판정(core · realtime) · GPT-Live 음성 중계"로 넓히고, `OPENAI_API_KEY`를 넣기 전 · realtime `mujoong.luna.enabled=true`로 바꾸기 전에 확인 기록을 남기게 한다. Parameter Store 순서: PR1 배포 전에 `/mujoong/demo/OPENAI_API_KEY`에 GPT-Live 키가 있는지 확인, 롤백 대상이 PR1 이후 SHA뿐이게 될 때까지 `/mujoong/demo/PROVIDER_API_KEY`를 같은 값으로 함께 두고 그 뒤에 지움. 절차만 적고 실제 Parameter Store · 배포는 바꾸지 않는다.
  - `contracts/ai-io.md` 2절(키) · 10절(`lunaState` · 스위치)은 Step 9에서 함께 고친다.
  - 시험: 기동 점검(demo 키 없음 거부 · 다른 프로필 키 없음 기동 · 키 없으면 헤더 없음), 키 × 스위치 네 경우의 `lunaState`와 빈 등록, `infra-validate` · 이미지 점검 · 비밀 스캔 · 인프라 시험 통과, 저장소 검색으로 예외(RUNBOOK 이름 바꾸기 절차 · 편차 표 · 지난 기록) 밖 `PROVIDER_API_KEY` 0건. 커밋.
- [x] **Step 5. 새 선 형식 요청 · 해석기 · 가짜 공급자 세 곳** (FR12.1 ~ FR12.4 · FR12.8 · BR11.1 ~ BR11.3 · BR11.10)
  - `ProviderRequestFactory`: 세션 시작을 중첩 모양(`session{model, instructions, audio{format{type "audio/pcm", rate}}}`)으로, 송신 type은 `session.start` · `session.input_audio.append` · `session.instructions.append` · `response.create` · `session.close` 다섯뿐으로 줄인다(`childContext` · `delegation` 없음, 아이 정보는 instructions 문장). `session.instructions.append`의 본문 필드 이름은 실호출로 확인되지 않았으므로 Step 12에서 확정하고 그 전에는 키트에서 관측된 이름을 쓴다.
  - 해석기(`ProviderClient.Reader`) · `ProviderPort` 내부 레코드: 출력 음성은 `delta`에서 읽고, `error{error{type, code, param}}` → `Error(code)`, `session.usage.updated` → 사용량 초 지표, 모르는 type은 개수만. `session.output.done` · `session.error` · 응답 id 필드를 뺀다. 기존 이름(`OutputTranscriptDelta` · `InputTranscriptDelta` · `InputTranscriptDone` · `SpeechStopped`)은 응답 id만 빼고 유지한다(실호출 관측 전까지 가정 이름, Step 12에서 확정).
  - 가짜 공급자 세 곳을 새 형식으로: `backend/test-support`의 `FakeProviderWsServer`, 인메모리 `FakeProviderPort`, `tools/fake-provider`(Node). 응답 id · `output.done` 없이 `delta`로 음성, 응답 경계는 조각 사이 무음, 목록 밖 type에는 `error{invalid_request_error, invalid_value, param type}`.
  - 응답 id 제거로 컴파일이 깨지는 소비 코드(`SegmentBuffer` · `OutputGate` · `SessionExecutor` · `TranscriptWriter` · `LatencyRecorder` 등)는 이 단계에서 세대 0 고정의 임시 호환으로 이어 붙여 컴파일만 통과시키고, 실제 규칙은 Step 6 · 7이 채운다(호환 코드 주석에 Step 7에서 제거한다고 적음).
  - 시험: 팩토리(중첩 키 집합 · 송신 type 다섯뿐 · 금지 필드 없음), 해석기(`delta` 바이트 · `error` · `usage.updated` · 모르는 type 개수), `FakeProviderWsServer` 통합(새 형식 송수신 · 목록 밖 type 오류), `tools/fake-provider` Vitest(새 형식 · 오류 · 조각 사이 무음). 커밋.
- [x] **Step 6. 출력 세대 협력 객체 · 구간 · 게이트** (FR12.5 · BR11.5 ~ BR11.7 · BR11.13 · BR3.4)
  - 설계 2.1 그대로: 세대 번호 · RECEIVING / DISCARDING / GAP_CONFIRMED · `lastDeltaAt` · `bumpedAt` · `deltaSinceSpeechEnd` · `permitSeq` · `pendingSend` · `sendProcessed` · `gapWaitDeadline` · 교정은 세대당 한 번. 끊김 확인 기준 ① 마지막 조각 · 세대 올림 중 늦은 쪽에서 `segment-quiet` ② 말 끝 이후 조각이 있었거나 말 끝 + `auto-response-start`. 실행기 스레드 전용. 위치는 `kr.mujoong.realtime.gate`(ArchUnit 허용 범위 안).
  - `auto-response-start`는 새 시간 키(`mujoong.timing.provider.auto-response-start`)로 두 앱 중 realtime `application.yml` · `contracts/timing.md` · 설정 레코드 세 곳에 이 단계에서 먼저 넣는다. 값은 Step 12가 실호출 관측으로 정하기 전까지 `segment-quiet`와 같은 800ms로 둔다(R-M5, `ConfigKeyContractTest` · `TimeLiteralScanTest` 통과).
  - `SegmentBuffer` · `OutputGate`: 구간 열쇠를 응답 id에서 세대로, 완전 = 첫 조각부터 끊김 봉인, 세대 변경 = 불완전, `Sealed`에 마지막 조각 시각을 담는다. `outputHeld()`가 참이면 단계와 관계없이 버리고 `lastDeltaAt`만 갱신(BR11.13), 보류 해제 때 세대를 올린다.
  - 시험(단위 8개 이상): 2.1 표 전 행, 끊김 기준 ①②(말 끝 뒤 조각 없음일 때 자동 응답 시작 대기), 허가 뒤 조각 → 허가 무효, 송신 처리 전 · 뒤 조각, 보류 중 버림, 보류 해제 세대 올림, 세대 또 올림, 구간 완전 · 불완전. 커밋.
- [x] **Step 7. 실행기 연결 — 세대 · 직렬 송신 경로 · 취소 대체 · 상태 전이 공통 지점** (BR3.3 · BR3.7 · BR4.4 · BR4.5 · BR11.4 · BR11.5 · BR11.7, 설계 W8 · W9, 첫 판 검토 R-15, 설계 검토 R-04(허가 칸 해제) · R-16 · R-17)
  - `SessionExecutor`의 응답 id 의존(약 25곳: `cancelledResponses` · `correctedResponses` · `playbackResponses` · `refuseHeldResponse` · `beginOutput` · `stopOutputForIntent` · `onBargeIn` · `correctAfterDiscard` · `cancelResponses` · 연습 `cancelResponse`/`requestResponse` 등)을 세대로 바꾼다. `session.response.cancel` 송신을 모두 없애고 세대 올림 + 기기 정지 명령으로 대체한다. Step 5의 임시 호환 코드를 제거한다.
  - 직렬 송신 경로(W9): 일곱 경로(참여 뒤 첫 응답 · 확인 뒤 이어 가기 · 연습 `requestResponse` · 연습 `speak` · 게이트 교정 · 판정 뒤 지시 자리 · 되묻기 자리)를 한 세션 직렬 큐로 모은다(`enqueuePracticeProvider`와 같은 큐). 세대 올림 → 끊김 허가(`SendPermit`) → 송신 직전 현재 세대 · 허가 번호 대조 → `append`(있으면) → `InstructionSent` 표시를 일반 편지함에 먼저 넣고 → `response.create`. 대조 실패 · 끊김 대기 상한 만기 · 세션 종료 · 장애면 허가 칸을 거부로 채워 작업이 `SendAborted`로 끝나게 한다(큐를 영영 쥐지 않음, R-04 · R-16).
  - 상태 전이 공통 지점(첫 판 R-15): `status = …` 쓰기를 한 메서드(예: `changeStatus(next, now)`)로 모은다. 기존 동작은 바꾸지 않는다(모으기만). PR2의 SESSION_EXITED 처리를 여기에 붙인다.
  - 지연 기록: 공급자 출력 끝 = 구간 마지막 조각 시각, AI 행 확정 = 봉인 시각, 봉인 대기를 '게이트 대기'로 남긴다(설계 W8-5).
  - 시험(실행기, `SessionHarness`): 응답 id 없는 조각으로 자유대화 한 턴이 기기에 소리로 나감(무음 아님), 끼어들기 · 종료 의도 · 연습 폐기 뒤 버린 세대 조각 0건, 일곱 경로가 같은 큐, append → create 순서, 끊김 전 송신 0건, 허가 뒤 조각이면 `SendAborted` → 재허가, 상한 만기 뒤 다음 송신이 막히지 않음, `InstructionSent`가 `create` 직후 즉시 온 첫 조각보다 먼저 처리됨(`ManualLauncher`로 순서 고정), 출력 보류 중 조각 0건 · 해제 뒤 세대 올림. 커밋(컴파일 규칙에 따라 나눠도 됨).
- [x] **Step 8. 기존 시험 단언 갱신** (FR12.9 · BR11.11)
  - 조사로 찾은 형식 단언 파일(설계 10절 목록: U1 `OutputGateTest` · `SegmentBufferTest` · `ProviderClientIT` · `ProviderRequestWhitelistIT` · `ProviderResponseIdTest`(세대 시험으로 교체) · `RealtimeLogHygieneIT`, U3 `SessionVoiceU3Test` · `SessionExecutorReviewU3Test` · `Review2U3` · `Review3U3` · `ProviderClientU3IT` · `SessionVoiceU3IT`, U4b `SessionStopU4bTest` · `SessionJoinU4bTest` · `PracticeExtensionU4bTest` · `SessionLifecycleU4bIT`, `SessionExecutorTest` · `PracticeExecutorIntegrationTest` · `RealtimeArchTest`, tools `tests/u1` · `u3` · `u4b`, `tools/skeleton-e2e/steps.mjs`)에서 같은 행동(순서 · 횟수 · 버림)을 세대 · 새 type으로 다시 단언한다.
  - 느슨하게 하거나 지우거나 건너뛰게 하지 않는다. 바꾼 단언 목록(파일 · 전 · 후)을 `code-summary.md`에 남긴다. 커버리지 하한 · `ContractFilesTest` · ArchUnit은 고치지 않는다.
  - 시험: `./gradlew :realtime-activity:test`(전체 realtime), `cd tools && npx vitest run`. 커밋(`test(u11)`).
- [x] **Step 9. 계약 · 편차 정비** (FR12.10 · BR11.12 · R-M10)
  - `contracts/ai-io.md` 2 · 3 · 8절을 실제 형식으로 고친다(메시지 · 필드마다 출처: 실호출 · ADR · 기술명세, 관측되지 않은 필드는 '미확인'). 2절 키 이름 · 10절 `lunaState` / 스위치도 함께(Step 4 결과).
  - `contracts/deviations.md`: 편차 (z) · (aa) · (U4b-a)를 지우지 않고 "(U11-a)로 대체"로 표시하고, 새 `(U11-a)`(선 형식) · `(U11-c)`(키 통일 · Luna 스위치)를 적는다. `(U11-b)`(판정 시한 2s → 4s)는 값이 바뀌는 PR2 Step 17에서 적는다. 번호 체계: 계약 `(U11-a)`~, 설계 `U11-Dn`.
  - 시험: `ContractFilesTest` · `ErrorCodeTest` 통과. 커밋.
- [x] **Step 10. `gptLiveTest` 운영 형식 전환 + TTS 실호출 보고서** (FR9.1 · FR12.7 · BR9.1)
  - `GptLiveProbe` / `GptLiveIsolationTest`: kit 비교 모드를 지우고 새 운영 클래스(팩토리 · 해석기)로 시도하는 모드 하나만 남긴다. 입력은 사람이 준 합성 말소리 원시 PCM(`GPT_LIVE_INPUT_PCM` · `GPT_LIVE_INPUT_RATE`), 시도할 표본율 옵션 `GPT_LIVE_RATE`(기본 24000)를 더한다.
  - 보고서 항목(설계 W7 ① ~ ⑨): 24kHz 수락, 출력 전사 사건, 입력 전사 조각 · 완료 사건, 말 끝 사건, 응답 끝 · 응답 경계 사건, `instructions.append` 누적, `append` + `create` 뒤 응답 · 목록 밖 type 0건, 말 끝 → 자동 응답 첫 조각 시간(여러 발화, P95), 자동 응답 안 쉼 길이 · `session.update`로 자동 응답을 끌 수 있는지. 전사 원문 · 음성 · 키는 보고서에 담지 않는다(R-F14). 사용량 초 기록.
  - 시험: 격리 규칙 시험(`check` → `gptLiveTest` 의존 없음), 키 없음 SKIPPED, 보고서 형식(가짜 공급자 루프백으로 항목 채움). 커밋.
- [ ] **Step 11. ⏸ 사람 정지점 — TTS 실호출 실행** (BR9.2 · BR9.3, FR12.6 · FR12.7) — 개발 위임은 여기서 멈추고 돌아온다.
  - 사람이 R-F15 확인 기록(넓힌 대상, Step 4)을 남기고 키 · 합성 말소리 PCM을 준비해 `./gradlew :realtime-activity:gptLiveTest`를 돌린다. 키트 `cache/`의 Piper 음성을 쓸 수 있다.
  - **출력 전사 사건이 오지 않으면 구현을 멈추고 사람과 다시 정한다.** 그 밖의 결과별 갈래는 설계 W7 표를 따른다.
  - 결과표를 단위 보고서(`docs/units/u11/report.md`) 초안에 `[실호출-합성]`으로 남긴다. 이 결과가 보고서에 있어야 PR1을 합친다(BR9.3).
- [ ] **Step 12. 실호출 결과 반영** (FR12.6 · FR12.7 · BR11.4 · BR11.6 ~ BR11.9, 검토 R-20)
  - 관측된 전사 · 말 끝 · 응답 경계 · `append` 필드 이름을 해석기 · 팩토리 · 가짜 공급자 · 계약 표에 더한다(관측되지 않은 이름은 코드에 넣지 않음).
  - 24kHz 수락이면 변환 없음. 거절이면 `ProviderClient` 한 곳에서 24k ↔ 16k 변환, 공급자 쪽 SHA 일치 단언을 변환 결과 길이 · 표본 수 단언으로 바꾸고, 다음 빈 `(U11-?)`로 J131 편차를 적는다.
  - `mujoong.timing.provider.auto-response-start` 값을 관측 P95 + 여유로 정한다(값 + `segment-quiet`가 `response-ready`의 절반 5초 안). 시간 값 세 곳을 같은 커밋에서 고친다(R-M5). 입력 전사 완료 사건이 없으면 서버 확정과 그 시간 키 하나를 더한다(BR11.9). 새 시간 키 수 문구는 `BR11.6`을 "하나, 단 BR11.9 갈래에서 하나 더"로 맞춘다(설계 검토 R-20).
  - 응답 끝 · 경계 사건이 관측되면 끊김 대신 그 사건으로 봉인 · 새 응답 시작으로 바꾼다. `append`가 누적되면 지시문 끝 단발 문장을 붙인다(그래도 남으면 중단).
  - 시험: 관측 사건별 해석기 · 세대 · 게이트 시험, 변환 경로(해당 시), 시간 키 대조 시험, 가짜 공급자 · 계약 표 갱신. 커밋.
- [ ] **Step 13. PR1 모듈 확인 · 병합 준비** (NFR5 · NFR6 · NFR9)
  - `./gradlew :realtime-activity:check verifyBootJarHasNoTestClasses -Pmujoong.test.db=docker`(정적 · 단위 · 통합 · ArchUnit · 계약 대조 · 모듈 줄 80%). `contracts/` · `core-api` · `test-support`를 바꿨으므로 `./gradlew clean check -Pmujoong.test.db=docker`도 돌린다. `cd tools && npx vitest run`, `infra-validate`, 이미지 점검. 실패하면 고치고 하한 · 단언을 낮추지 않는다. 전체 `verify.sh`는 Build and Test에서 돈다(team.md).
  - 증거를 테스트 대역 · 합성 대화 · 실제 API(`[실호출-합성]`) · 실제 기기로 나눠 `report.md` 초안에 정리한다(R-M11).
- [ ] **Step 14. ⏸ PR1 열기 · 병합 · PR2 브랜치** — 푸시와 `gh pr create`는 사람이 승인한 뒤 연다(외부에 보이는 작업). `ci-gate` 통과 뒤 사람이 병합하고(merge commit, team.md), 병합 뒤 `main` skeleton 결과를 확인한다. 그 다음 `main`에서 `unit/u11-judgment-turn`을 새로 딴다. 개발 위임은 여기서 멈춘다.

### Part C — PR2: 판정 턴 (PR1 병합 뒤, 새 `unit/u11-judgment-turn`)

- [ ] **Step 15. realtime V5 마이그레이션** (FR2.2 · BR2.3) — `V5__u11_judgment_turn.sql`로 `realtime.realtime_judgment_live`를 만든다. 열은 `entities.md` `JudgmentLiveRecord` 그대로(V4 관례: `guardian_id uuid NOT NULL` + 색인, `session_id` 외래 키, 값 검사 CHECK, 필요하면 `data jsonb` 객체 검사). 기존 표에 제약을 더하지 않는다. 깨지는 단언 3곳(`RealtimeSchemaIT` 표 목록 · 표 수 · 최신 판 "5" · 이력 1 ~ 5, `PracticeMigrationRegistrationTest` 최신 판, `PracticeMigrationIT` L49)을 새 사실로만 갱신하고 커밋 본문에 까닭(U11-D5)을 적는다. 시험(통합 `@Tag("db")` · `@Tag("u11")`): 표 · 열 · 제약 · 외래 키, `infra-validate` 마이그레이션 expand 검사. 커밋.
- [ ] **Step 16. 판정 기록 저장소 · 비동기 기록기 · 삭제 참여자** (FR2.1 · FR2.3 · BR2.1 · BR2.2 · BR2.4 · BR2.5 · NFR8) — id(= turnId) 덮어쓰기 저장소, 판정 기록 전용 칸이 있는 비동기 기록기(일시 실패 다음 틱 재시도 · 제약 위반 폐기 · 실행기 비대기), `RealtimeDeletionParticipant` `order()` 180 · `guardian_id` 기준. 외부 읽기 경로 없음. `SessionHarness` `MemoryStore`에 메모리 구현. 시험: 저장소 통합, 기록기 단위, 삭제 통합(세션 200보다 먼저, 삭제 뒤 0건). 커밋.
- [ ] **Step 17. 판정 설정 · 4초 시한 · 패키지 뼈대** (FR1.3 · FR1.4 · NFR1 · BR1.4, 설계 U11-b) — `RealtimeProperties` 루트에 `Judgment(minConfidence 0.6, minMargin 0.15)`와 `application.yml` 키 `mujoong.judgment.min-confidence` · `min-margin`(0 ~ 1 검사). **`mujoong.timing.judgment.live-budget`을 2s → 4s로** `contracts/timing.md` 13절 · realtime `application.yml` · 설정 레코드 세 곳을 같은 커밋에서 고치고(R-M5) `contracts/deviations.md`에 `(U11-b)`를 적는다. 새 패키지 `kr.mujoong.realtime.judgment`(ArchUnit: `luna` · `gate` · `voice` 밖). 시험: 설정 바인딩 · 범위 검사, 시간 키 대조 시험 통과. 커밋.
- [ ] **Step 18. `JudgeClient` · Luna 구현 · 보류 구현 · 결과 검증** (FR1.1 ~ FR1.6 · BR1.1 ~ BR1.7 · BR4.3 · NFR3) — `available()` · `judge(JudgeRequest, turnId)`(예외 없이 `JudgeOutcome`). Luna 구현은 `RealtimeLunaClient.call`(시한 min(마감, live-budget 4s), 재시도 없음, 별칭 표 전달). 빈이 없으면(키 없음 · 스위치 꺼짐) 보류 구현. 요청은 세션 고정 판의 지점 선택지(판 리소스 순서), 확정 전사 · 최근 3턴, J2 · J3 · J5만 기준. 판독 불가는 공급자 없이 UNREADABLE_INPUT. 결과 검증 + 0.6 · 0.15 보류. 시험(단위 8개 이상): 5절 벡터 5개, 선택지 밖 · 중복 · 확률 이상, 4.000초 만기 = 보류 · 3.999초 = 확정, 시간 초과 · 공급자 오류 · 스키마 오류(호출 1회), Luna 없음, 요청에 식별자 · 원음 없음. Luna는 루프백 대역만. 커밋.
- [ ] **Step 19. 다음 행동 지시 템플릿** (FR3.3 · FR4.2 · FR6.4 · BR3.5 · BR4.2 · BR6.4) — `DialogInstructions`에 HOLD_REASK · REST_ACK_AND_RETURN · REST_ACK_ONLY 고정 문장(500자 · 제어 문자 금지)을 더하고, 판정 라벨 · 확률 · 기준 · 아이 원문이 들어갈 길이 없게 값 타입으로 만든다. 판정 지시문 · `promptVersion`은 자원 파일(`judgment/prompt-v1.txt`). `append`가 누적되는 것으로 관측됐다면(Step 12) 단발 문장 부가를 템플릿 계층에서 한다. 시험: 세 지시문에 판의 모든 선택지 코드 · 숫자 확률 없음, 한도. 커밋.
- [ ] **Step 20. 판정 턴 협력 객체** (FR3 ~ FR5 · BR1.8 · BR3.1 · BR3.7 · BR4.1 · BR4.5 · BR5.1 ~ BR5.4, 설계 검토 R-04(이번 판) · R-18 · R-19) — 설계 2.2 그대로. 시계 `JUDGMENT_LIVE` · `JUDGMENT_READY` · `JUDGMENT_INSTRUCTION`(`SessionTimers.Kind`에 더함), INSTRUCTING 진입 때 READY · LIVE 해제. **INSTRUCTING 중 확정 전사**(R-04): STOP은 BR6.2(STOPPED), 그 밖은 SUPERSEDED로 닫고 새 전사를 처음부터 처리(BR5.3을 INSTRUCTING까지 넓힘). **`SendAborted`**(R-18): 세대 변경이면 닫음(SEND_ABORTED), 같은 세대 허가 번호 상승이면 재허가 · 재큐, 사유 `GAP_TIMEOUT` → `OUTPUT_GAP_NOT_OBSERVED`. **낡은 허가 번호 `InstructionSent`**(R-19): 표시를 받아들이고 `pendingSend`를 풀되 이미 버린 조각은 되살리지 않고 다음 조각부터 받는다. 필드는 `{turnId?, generation, permitSeq}`로 통일하고 `entities.md`의 `sendProcessed` 설명을 '송신 표시 처리됨'으로 맞춘다. 시험(단위 10개 이상): 열기 · 결과 · 4초 · 10초 · 지시 응답 시한 · 각 닫힘 결과 · 늦은 turnId · INSTRUCTING 중 전사 · `SendAborted` 두 갈래 · 낡은 허가 `InstructionSent` · 닫힐 때 시계 모두 해제. 커밋.
- [ ] **Step 21. 실행기 연결 1 — 판정 턴 출력 · 지시 · 보기** (FR3.1 ~ FR3.4 · BR3.1 ~ BR3.7 · BR4.4, 설계 W3) — 판정 턴 열기는 출력 세대를 올리고, 판정 턴 중 조각은 음성 버림 · 전사만 모음(문장 버퍼 · 대화 기록기 앞에서 가름), 지시는 Part B의 직렬 송신 경로로(`InstructionSent` 표시 뒤 첫 조각부터 지시 응답). ACTIVE에서 THINKING, WAITING_CHILD 그대로, `listeningAgain()` 호출처는 열린 턴 중 건너뜀, 닫힐 때 복원, `changeStatus` 훅에서 SESSION_EXITED. Luna 결과 · `InstructionSent`는 일반 편지함. 시험(실행기): 판정 중 출력 기기 0 · 대화 기록 0 · 전사 모임, THINKING 유지 · 복원, append → create 순서, 지시 전 자동 응답 조각 버림, 지시 응답 시한 만기, 상태 전이마다 SESSION_EXITED. 커밋.
- [ ] **Step 22. 실행기 연결 2 — J1 · J6 · 휴식 · 종료 근거** (FR6 · FR7 · BR6.1 ~ BR6.5 · BR7.1 · BR7.2, 설계 W1 · W2, 첫 판 검토 R-13 · R-14) — W1(J1): UNCLEAR만 J1, 결과를 답으로 바꿔 `join.reply(답, 원래 확정 시각)` 한 번, `JOIN_WINDOW` 만기 미룸, 휴식은 재안내(U11-D6). W2(J6): CONFIRM이면 연습 시계 멈춤 · 연습 발화 끊음 · 출력 정리 뒤 `onJudgment` 없이 J6, 결과 표 다섯 줄(END_CLEAR → `CHILD_STOP_INTENT{basis: LUNA_J6}`). **R-13**: 출력권 연습에서 J6가 NOT_END · FAULT_CANCELLED이면 가려 둔 확정 전사를 `practice.onChildUtterance`에 넘긴다. **R-14**: J6 SUPERSEDED에서 새 전사가 다시 CONFIRM이면 연습 시계 멈춤 유지, 아니면 재개(`pausePracticeTimers` 멱등). 휴식(BR6.4)은 ACTIVE에서만 REST_ACK_AND_RETURN 또는 REST_ACK_ONLY + `REST_REQUESTED`. 자유대화는 J1 · J6뿐. 시험(실행기): J1 세 줄 · 재안내 1회 · 창 만기 미룸, J6 다섯 줄 · 사건 표, R-13 · R-14, 판정 중 STOP, 등록 표현 Luna 호출 0, `LUNA_J6`. 커밋.
- [ ] **Step 23. 확장점 능력 · 사건** (FR8 · BR8.1 ~ BR8.4) — `PracticeOutput`에 `requestJudgment(JudgmentPoint, long, UUID, Map)` · `announceRoleSwitch(String)` · `setThinking(boolean)` 기본 `false`, `ExtensionEvent.Kind`에 `JUDGMENT_RESULT` · `JUDGMENT_ABSTAINED` · `HELD_OUTPUT_TRANSCRIPT` · `GUARDRAIL_BLOCKED` · `REST_REQUESTED`. 실행기 `PracticeOutputPort`가 W4대로 처리(최근 확정 전사 4개 메모리, 거절 조건, 확장 소유 턴, 확률 없는 결과 사건). 정산 창 · CLOSING 거절. 시험: 받음 · 거절 4종 · 결과 · 보류 · 모은 전사 · 장애 사건, 기존 확장 · `NoOp` · 시험 대역 무수정 컴파일, ArchUnit 확장점 → 실행부 의존 없음. 커밋.
- [ ] **Step 24. 장애 · 세션 이탈 회복** (FR5.1 · FR5.2 · BR5.1 ~ BR5.4) — `providerLost` · `abortProviderLost` · `startPause`에서 FAULT_CANCELLED(J1 참여 창 재무장, J6 연습 시계 + R-13 전달, 확장 소유 장애 사건), 공급자 `error` 사건 처리(사유 코드만). 시험: 판정 중 공급자 끊김 · `error` · 일시 정지 · 보호자 종료 · 동의 철회 · 참여 거절, 늦은 결과 폐기. 커밋.
- [ ] **Step 25. Luna 없음 회귀 확인** (FR1.6 · BR1.6 · NFR6) — 보류 판정기(키 없음 · 스위치 꺼짐)로 J1 UNCLEAR · J6 CONFIRM의 보기 · 세션 사건 순서가 지금과 같음을 실행기 시험으로 단언한다. 기존 U3 · U4b · U5b 실행기 시험과 realtime 전체 시험이 단언 변경 없이 통과함을 확인한다(바꾼 단언은 Step 8과 Step 15뿐). 클래스 목록을 `code-summary.md`에 적는다. 커밋(`test(u11)`).
- [ ] **Step 26. 편차 번호 · 계약 정리** (BR11.12) — `contracts/deviations.md`에 `(U11-b)` 값 반영을 확인하고, 설계 편차 U11-D1 ~ D8과 계약 편차 `(U11-a)` ~ `(U11-c)`의 번호 체계를 문서 두 곳에서 구분해 적는다. 계약 대조 시험 통과. 커밋(Step 28과 합쳐도 됨).
- [ ] **Step 27. 커버리지 범위 파일** (FR11.1 · NFR5) — `backend/coverage-scope/u11.txt`에 U11이 만들거나 고친 realtime 운영 Java 파일(형식 · 세대 · 판정 파일과 `SessionExecutor`, u1 · u3 · u4b 범위 파일이 `SessionExecutor`를 넣는 관례)을 적는다(머리 주석은 `u9.txt` 형식, 빈 목록이면 실패 J137). `-PcoverageScope=u11`로 범위 줄 80% 이상 확인, 모자라면 시험을 더하고 하한은 낮추지 않는다. 커밋.
- [ ] **Step 28. 문서** (FR11.2) — `docs/units/u11/design.md`에 기능 설계(상태 기계 · W1 ~ W10 · 규칙 요약 · 편차 U11-D1 ~ D8 · 내부 설정 키와 기본값 · 실호출 결과 요약)를 옮긴다. 코드 주석은 결정 ID(BR · U11-D)를 인용하고 TODO · FIXME를 쓰지 않는다. 기능 설계 승인 때 감수한 문서 사이 불일치(R-18 · R-19 · R-20)는 `design.md`에 옮기면서 한쪽으로 맞춘다. 커밋.
- [ ] **Step 29. PR2 모듈 전체 확인** — `./gradlew :realtime-activity:check verifyBootJarHasNoTestClasses -Pmujoong.test.db=docker`, `contracts/` · 공용 모듈을 바꿨으면 `./gradlew clean check -Pmujoong.test.db=docker`. 실패하면 고치고 하한 · 단언을 낮추지 않는다. 전체 `verify.sh`는 Build and Test에서 한 번 돈다(team.md). PR2 열기 · 병합은 사람이 승인한다.

## 스토리 · 요구사항 → 단계 추적

| 요구 · 스토리 | 단계 |
|---|---|
| FR1.1 ~ FR1.6 · AC1.8.1 · AC1.8.2 (US1.8) | Step 17 · 18 |
| FR2.1 ~ FR2.3 · AC1.8.6 (US1.8) | Step 15 · 16 |
| FR3.1 ~ FR3.4 · AC1.10.1 · AC1.10.2 · AC1.10.4 (US1.10) | Step 20 · 21 |
| FR4.1 ~ FR4.3 · AC1.8.3 ~ AC1.8.5 (US1.8) | Step 18 ~ 21 |
| FR5.1 · FR5.2 · AC1.10.3 (US1.10) | Step 20 · 24 |
| FR6.1 ~ FR6.5 · AC1.7.1 ~ AC1.7.3 · AC1.7.5 · AC1.7.6 (US1.7) | Step 19 · 22 |
| FR7.1 · AC2.5.1 (US2.5) | Step 22 |
| FR8.1 ~ FR8.3 | Step 23 |
| FR9.1 · FR9.2 | Step 2 · 3(끝남) · 10 · 11 |
| FR10.1 | Step 25(기존 기록 이벤트 시험 그대로) |
| FR11.1 · FR11.2 | Step 27 · 28 |
| FR12.1 · FR12.10 | Step 5 · 9 · 12 |
| FR12.2 ~ FR12.4 | Step 5 · 12 |
| FR12.5 | Step 6 · 7 |
| FR12.6 · FR12.7 | Step 10 ~ 12 |
| FR12.8 | Step 5 · 12 |
| FR12.9 | Step 8 |
| FR13.1 · FR13.2 | Step 4 |
| NFR1 · NFR2 · NFR3 · NFR4 · NFR8 | Step 5 ~ 7 · 12 · 17 ~ 21 |
| NFR5 · NFR6 · NFR9 | Step 8 · 13 · 25 · 27 · 29 |
| NFR7 | Build and Test(`verify.sh`는 `mujoong-verify`) |

## 지킬 규칙

- 시험 · CI · `verify.sh`는 가짜 공급자 · 가짜 판정기만(R-F1). 실호출은 `gptLiveTest` 격리 작업만, 사람이 R-F15 확인 기록 뒤 실행.
- 시간 값은 `mujoong.timing.*`에서만, 시각은 `MujoongClock`으로만(R-F6 · R-F7). `auto-response-start` 신설 · `live-budget` 4s처럼 시간 값을 바꾸면 realtime `application.yml` · `contracts/timing.md` · 설정 레코드를 같은 커밋에서(R-M5).
- 새 비밀은 없다. `PROVIDER_API_KEY` → `OPENAI_API_KEY`는 비밀 이름 검사 목록 전부를 같은 커밋에서(R-M9). 값은 어디에도 쓰지 않는다(R-F3).
- 로그에 전사 · 버린 출력 · 지시문 원문 · 키를 남기지 않는다(R-F14). 사유 코드 · 개수 · 시간만.
- core-api와 코드 · DB를 섞지 않는다(R-F5). core-api는 키 이름 주석 · 시험 이름만 고치고 동작은 그대로. `contracts/`를 바꾸면 R-M10.
- 하한 · 기존 단언 · 대조 시험 · ArchUnit을 낮추거나 끄지 않는다(R-F2). 예외는 판 단언 3곳(Step 15)과 선 형식 단언(Step 8)의 "행동을 남긴 채 새 형식으로 다시 단언"뿐이다.
- 사용자의 `mujoong-manual` 스택을 건드리지 않는다(R-F12).
- PR은 `ci-gate` 통과 뒤에만 `main`에 합치고(R-M1), 병합 순서는 마이그레이션 버전을 따른다(R-M2, V5는 PR2). 푸시 · PR 열기 · 병합은 사람이 승인한다.
- 손대는 파일만 다른 유닛 관례대로 정리한다(team.md Code Style Q7-A).
