# U12 새 연습 흐름 (u12-practice-flow) 구현 지시

realtime 줄의 **마지막 볼트(B8)**다. realtime 줄은 U11 → U13 → **U12** 순서로 한 담당자가 차례로 했다. 셋 다 `SessionExecutor` · `OutputGate`를 고치기 때문이다(`docs/units/README.md` 「U8 ~ U15 문서 동기화 볼트」). 이 줄에서 남은 유닛은 이것 하나다.

> 이 지시서는 U13이 `main`에 병합된 뒤(PR #32) 새로 썼다. 앞 볼트의 사실은 `docs/units/u11/{design,report,handoff}.md` · `docs/units/u13/{design,report}.md` · `docs/units/u10/{design,report}.md`가 정본이다. 아래 줄 번호는 `main`(`471e836`) 기준이다.

## 시작 전 상태
- U8 · U9 · U10 · U11 · U13 · U14 · U15가 `main`에 합쳐졌다. U12가 기다리던 U10(상황 생성)도 병합됐으므로 **대역 없이 실제 대본 필드로 개발**할 수 있다.
- 브랜치: `main`에서 `unit/u12-practice-flow`를 딴다(이미 `471e836` 위에 만들어 두었다).
- 마이그레이션: **realtime V7**. realtime은 지금 V6까지다(`V6__u13_jev_guardrail.sql`). core는 V10까지다. 병합 순서는 같은 앱 안에서 번호 순서다.
- 시간 값: 새 연습 흐름의 시간 키는 U8이 이미 `mujoong.timing.conversation.*`로 만들었다(`no-response-wait` 10s · `focus-max` 90s · `goal-active-limit` 10m, `application.yml` 125 ~ 128줄 · `contracts/timing.md` 13절). 계약 요약이 적은 `timing.practice.*` 이름이 아니다. 새 시간 값을 더하면 R-M5의 세 곳을 같은 커밋에서 고친다.
- 시간 값이 아닌 설정(다시 해 보기 2회 · 상황 다시 설명 1회)의 키는 **아직 없다**. 새로 만들고 `contracts/ai-io.md` 표와 `ConfigKeyContractTest` 대조에 넣는다(R-M10).
- 비밀 이름은 새로 만들지 않는다. 새 비밀을 만들면 R-M9의 목록 전부에 함께 넣는다.

## 첫 작업: 위험 확인과 설계 결정
설계를 흔들 수 있는 것을 먼저 코드로 확인하고 **사람이 정해야 하는 것은 Requirements Analysis의 개별 질문으로 올린다**(아래 「사람이 정할 것」 5개).
1. **연습 통제 아래에서 친구 되묻기 · 코치 발화가 들리는가.** 지금 연습이 출력을 쥔 동안(`controlsOutput()` = true) 승인 원문과 같지 않은 출력은 모두 `GATE_PATTERN`으로 버려진다(`SessionExecutor.java` 3004 ~ 3013줄, U13 `rules.md` BR7.6). 대본 스키마에 되묻기 문장 필드가 없으므로 "어느 색?"은 GPT-Live 새 발화일 수밖에 없고, 지금 코드에서는 소리가 나지 않는다. 제브의 `PRACTICE_PEER` · `PRACTICE_COACH` 요청 경로는 운영 호출자가 없다(`GuardrailCoordinator`는 받을 준비만 되어 있다).
2. **`announceRoleSwitch`는 빈 틀이다**(`SessionExecutor.java` 1999 ~ 2006줄: ACTIVE가 아니면 거절하고 그 밖에는 항상 `false`). 역할 전환 문구 `COACH_SWITCH` · `PEER_RETURN`은 음성 파일이 없는 `approvedSpeech`라서 실행부 plan에 들어가지 않는다. `playApproved("COACH_SWITCH")`는 **조용히 무음으로 끝난다**. 텍스트는 `LiveContentRegistry.plan(sessionId).approvedSpeech()`로만 얻는다.
3. **지시 선 형식은 `session.instructions.append` 하나**다. 요구사항 FR5.2와 계약 요약 C-DS15가 적은 `instructions.correct` + `response.create`는 낡았다(U11 U11-D9, `dialog/InstructionTemplates` 머리 주석). 지시문은 "먼저 '음,'이라고 짧게 말한 뒤"로 시작하고 "이번 한 번만 …"으로 범위를 닫는다.
4. **12 화면 진행 단계 표시가 새 단계 이름을 거부한다.** `contracts/schemas/live-view.schema.json`의 `Progress.stage`는 enum 6개(`SCENARIO_INTRO` · `FIRST_RESPONSE` · `HELP_OFFER` · `CUE` · `AFTER_CUE_RESPONSE` · `NEW_SCENARIO_RESPONSE`)에 `additionalProperties: false`이고, 프런트가 이 키에 없는 값이면 진행을 버려 "현재 연습 단계를 확인할 수 없어요."를 보인다(`frontend/src/parent/practice/practiceProgress.ts` · `PracticeHeader.tsx`). `REASK` · `FOCUS` 같은 값을 그대로 내보내면 화면이 깨진다.
5. **새 흐름과 레거시를 가르는 자리가 없다.** `PracticeSessionExtension.Factory.create(sessionId)`는 `practiceJson`을 받지 않고 `onActive(context)`가 처음으로 받는다. `Factory`는 `ConditionalOnMissingBean` 단일 빈이다(`PracticeRuntimeConfiguration.java` 56 ~ 64줄).

## 범위
### 이 볼트가 하는 것 (U12, FR4.1 ~ FR4.7 · FR7.3, US1.1 ~ US1.6 · US1.9)
1. **연습 상태 기계 `PracticeConversation`** (FR4.1 ~ FR4.5, AC1.1.x ~ AC1.6.x · AC1.9.x)
   - `SCENE_INTRO` → `OPPORTUNITY` → `REASK` → `HELP_OFFER` → `FOCUS` → `RETRY` → `SCENE_WRAP` → (`VARIANT`) → `NEXT_GOAL` / `CLOSING`. 도움 · 변형을 반드시 거치지 않는다.
   - 되묻기로 해결되면 집중 도움을 강제하지 않는다(AC1.2.3). 상황 이해 못함이면 교정 전에 상황 다시 설명 1회(AC1.3.x), 무응답은 [기본값] 10초 뒤 1회 재안내, 또 없으면 그 장면 미실시(AC1.9.x, 기술 일시 정지 중에는 재안내 없음).
   - 변형은 4조건(쓸 수 있는 변형 · 시간 · 참여 의사 · 목표당 ACTIVE 10분 전)이 모두 맞을 때만 제안하고, 아니면 생략 사유를 기록한다(AC1.6.x). 다시 해 보기 [기본값] 2회 · 집중 90초를 넘으면 변형 없이 정리한다(AC1.5.4).
   - `PracticeSessionExtension`을 구현한다. 실행부 → 확장점 한 방향만 둔다(ADR-005): 확장은 실행부를 부르지 않고 `PracticeOutput` 능력으로만 움직인다. U11 · U13의 선례(`judgment/` · `guardrail/` 패키지, 실행부와는 `Env` 인터페이스로만 닿고 ArchUnit으로 강제)를 따른다.
2. **친구 · 코치 역할별 출력 정책** (FR4.4, AC1.5.x)
   - 코치로 바꿨다고 알리고 시작한다(역할 전환 알림), 표현 예시는 동의 뒤에만 준다. 친구 역할은 정답을 미리 말하지 않는다. 복귀도 알린다. `CueProvider`의 전역 금지를 역할별 정책으로 나눈다.
   - 역할 전환 · 복귀 알림을 제브가 막으면 알림 없이 역할을 바꾸지 않는다(AC1.5.6): 1회 다시 지시하고, 그다음 장면을 정리한다. 두 번째 막힘은 `ExtensionEvent.Kind.GUARDRAIL_BLOCKED`(값 `GuardrailBlockedEvent`)로 확장에 전달된다(U13이 정의와 대역 시험까지 했고 **운영 호출자는 U12**다).
3. **기록 · V7** (FR4.7 · FR7.3, 계약 편차 (U8-d))
   - 역할(친구/코치)과 도움(종류 · 내용 · 예시 여부 · 동의 응답 · 시각), 변형 · 생략 사유를 realtime V7에 기록한다. 장면 노출 이력은 장면을 실제로 시작할 때 남긴다.
   - 기존 V4 표 제약(`practice_attempt`의 `UNIQUE(session_goal_id, attempt_type)` 등)은 목표당 BASE 1개 · VARIANT 여러 개 · 장면당 도움 여러 번을 담을 수 없으므로 **새 표**가 맞다(컴포넌트 설계의 `PracticeScene` · `HelpRecord` 후보). 도움 `content`가 개인정보일 수 있어 원문 보관 여부를 정한다(R-F14).
   - 새 표도 보호자 삭제에 참여한다(Domain Design 지적 R-02). 현재 realtime 삭제 순서 상수는 100 · 150 · 180(U11) · 185(U13) · 200(세션) · 900이고, 새 참여자는 200보다 앞이어야 한다(세션 FK `ON DELETE RESTRICT`). 고아 점검(`PracticeRecordRecovery`)이 `practice` 값을 다시 만들 수 있게 복구 경로를 설계한다.
   - `record.practice`에 `flowVersion: "practice-conversation-v1"`을 넣는 곳은 지금 운영 코드에 **없다**(grep 0건). 확장의 `practiceRecord()`가 정산 창 뒤 `SessionExecutor.practiceRecordForSettle`에서 한 번 불린다.
4. **레거시 연습 경로를 새 흐름에서 뗀다** (FR4.6, AC1.1.5, ADR-001)
   - 새 흐름은 `EvaluationProvider` · `ResponseEvaluator` · `CueProvider`가 연결되지 않아도 시작부터 마무리까지 멈추지 않는다.
   - **삭제하지 않는다.** 레거시는 과거 기록 읽기용으로 남긴다(V4 표 5개, `RealtimePracticeDeletionParticipant`, `PracticeRecordRecovery`). 레거시 클래스를 비우면 CI `u5b-scope` 작업(`.github/workflows/ci.yml` 143 ~ 170줄, 범위 `backend/coverage-scope/u5b.txt`)이 줄 80% 하한으로 막는다. `PracticeRuntimeConfigurationTest.realProvidersRemainUnavailableInsteadOfReturningFabricatedSuccess`도 기본 빈이 계속 실패해야 한다고 단언한다.

### 이 볼트가 하지 않는 것
- J1 · J6 판정과 판정 턴 보류(U11), 제브 검사(U13), 상황 생성(U10), 결과 조립 · 재판정(U14). 이 볼트는 이들이 만든 확장점과 계약을 쓰기만 한다.
- Luna 생성물 검사, GPT-Live 선 형식 변경.
- 실호출(GPT-Live · 제브 위의 실제 동작)과 실제 기기 확인(FR12): 사람 몫이다. 한국어 되묻기 품질 · 코치 표현의 자연스러움 · 아이가 역할 전환을 알아듣는지는 `[실호출-합성]` · `[실기기]` 확인표에서 따로 본다.

## 정본
- 요구사항 FR4.1 ~ FR4.8 · FR7.3 · NFR, 스토리 US1.1 ~ US1.6 · US1.9(AC 29개, 모두 `[자동-대역]`): `aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/requirements-analysis/requirements.md` · `inception/user-stories/stories.md`
- ADR-001(레거시는 읽기용으로 남김) · ADR-004 · ADR-005(상태 기계는 확장점 출력으로만) · ADR-007: `inception/domain-design/decisions.md`, 컴포넌트 `PracticeConversation`: `components.md`
- 계약 C-DS1(대본) · C-DS2 · 기록 캡처 이벤트 · C-DS9: `inception/contract-design/contract-summary.md`, 편차 `contracts/deviations.md`(U8-d · U10-a · U11 · U13)
- 볼트 계획 B8 · 순서 근거: `inception/delivery-planning/bolt-plan.md` · `risk-and-sequencing-rationale.md`
- 시안 · 기기 상태: `inception/refined-mockups/mockups.md`(새 상태 이름을 만들지 않는다, 판정 · 지시 · 제브 대기 중에는 '생각 중' 유지, AC1.10.4)
- 앞 볼트: `docs/units/u11/{design,report,handoff}.md`(출력 세대 · 구간 봉인 · 판정 턴 · 확장점 능력), `docs/units/u13/{design,report}.md`(제브 단계 · 연습 분기), `docs/units/u10/{design,report}.md`(새 대본 필드)

## 정해 둔 값
- 무응답 재안내 10초(`mujoong.timing.conversation.no-response-wait`), 집중 90초(`focus-max`), 목표 ACTIVE 10분(`goal-active-limit`). 다시 해 보기 [기본값] 2회 · 상황 다시 설명 1회는 설정 키로 둔다. 판정 보류 문턱 0.6 · 0.15와 판정 시한 4초는 U11의 값이다(U11-b). 숫자 시간 값을 코드에 쓰지 않는다(R-F7).
- 장면 식별은 대본의 `sceneKey`뿐이다(장면별 UUID 없음). `sceneRunId`는 realtime이 만든다. `record.practice.scenes[].scenarioRef`에는 대본 전체의 `scenarioId`를 쓴다.
- 시험 · CI · `verify.sh`는 가짜 공급자만 쓴다(R-F1). 결과는 테스트 대역 · 합성 대화 · 실제 API · 실제 기기를 따로 적는다(R-M11).

## 다른 단위와 맞물리는 곳 (코드 사실)
1. **U10(대본)**: 병합됐다. `practice-scenario.schema.json`에 `scriptVersion` · `scenes[]` · `selfReview` · `generation`이 이미 있다(편차 (U10-a)). **README가 적은 "U12 담당자가 선택 필드를 계약 PR로 먼저 넣는다"는 이미 끝났다.** 고칠 점이 생기면 U10 담당과 같은 날 함께 합친다.
   - `scenes[]` 필수는 `sceneKey` · `sceneKind` · `sceneDescription` · `opportunitySentence` · `reexplanation` · `partnerRole`이다. 코드의 `ScenarioScript.Scene`에는 `focusPoints` · `expressionExamples` · `relation` · `place` · `topic`도 있다(스키마 `required`에는 없음). 누락한 `focusPoints` · `expressionExamples`는 빈 배열로 정규화된다.
   - **첫 목표(order 1)에만** 시작 패키지에 `firstScenario`가 들어간다. 나머지 목표의 대본은 `POST /internal/practice/scenarios`(kind ORIGINAL)로 다시 받는다. 변형 장면은 같은 응답의 `scenes[]` 안 VARIANT라서 별도 호출이 필요 없다.
   - realtime은 `practice` JSON 원문 문자열을 `PracticeContext.practiceJson`에 그대로 넘긴다. 레거시 `PracticeScenarioClient.Scenario`는 새 필드를 읽지 않으므로 U12가 새 필드를 읽는 타입을 따로 만든다.
   - `requestJudgment`의 기준 `criteria = {coreFunction, judgmentCriteria}`에서 `coreFunction`을 어디서 만들지는 U12가 정한다(시작 패키지 목표에는 `judgmentCriteria` 문자열 하나뿐이다).
2. **U11(판정 턴)**: 확장점 능력 `requestJudgment`(운영 호출자 없음, J2 ~ J5는 확장이 요청), `setThinking`, `announceRoleSwitch`(빈 틀), 일반 지시 `instructProvider` · `speak`가 있다. `requestJudgment`는 ACTIVE가 아니거나 열린 턴이 있거나 J1/J6이거나 최근 확정 전사 4개 밖이거나 기준 모양이 틀리거나 공급자가 없으면 이유 없이 `false`다. `NextAction` 템플릿(`HOLD_REASK` · `REST_ACK_AND_RETURN` · `REST_ACK_ONLY`)은 `PracticeOutput`으로 보낼 능력이 없고 `HOLD_REASK`는 운영 호출처가 없다(`handoff.md` 52줄). `judgment_live.goal_ref` · `scene_ref` 칸은 지금 항상 null이다(`JudgmentCoordinator.java` 387줄).
3. **U13(제브)**: 연습 출력 통제 중에는 제브를 부르지 않고 원문 일치만 통과시킨다(`guardrail.onVerbatimSkipped(SOURCE_PRACTICE_SPEECH, …)`). 대본 등록 `LiveContentRegistry.registerScript`의 운영 호출자는 없다. 연습 모드 `GUARDRAIL_BLOCKED`의 소비가 U12 몫이다.
4. **U14(결과)**: 새 기록은 `record.practice`로만 받는다. U14는 `facts`(허용 15종만, 모르는 값은 무시) · `liveOutcome.timing` · `variantSkipReason` · `startedAt`을 읽는다. 장면 · 전사 대응은 장면 시작 시각 · 전사 번호 범위 없이 모델이 추정하는 구조다. `practice-record-v2.schema.json`의 `scenes` · `liveOutcome` 객체는 `additionalProperties: false`라 필드를 더하면 깨는 변경이고 U14와 같은 날 합친다.
5. **U5b(레거시)**: `controlsOutput()`을 쓰는 U5b 시험이 27파일이다(`@Tag("u5b")`). 레거시 `controlsOutput()` 의미를 바꾸면 `PracticeExecutorIntegrationTest` · `session/PracticeExtensionU4bTest`도 영향을 받는다.

## 사람이 정할 것 (Requirements Analysis 개별 질문 후보)
1. **J1 휴식 요청**: AC1.1.2는 `D_REST_ACK_START`를 들려주고 시작하지 않고 기다리라고 하지만, U11 설계 U11-D6은 "참여 확인 중 휴식은 받아 주는 말 없이 재안내"로 구현했다(J1은 실행부 소유, `D_REST_ACK`는 음성 파일 없음). U12가 U11 단언을 AC대로 바꾸는가, AC를 개정해 U11 동작을 인정하는가, 연습 세션에만 분기하는가.
2. **친구 되묻기 · 코치 발화를 연습 통제 아래에서 어떻게 들리게 하나**: 새 확장에 한해 분기를 열어 `guardrail.begin(PRACTICE_*)`을 호출하는가(레거시 u5b 분기는 그대로), 기존 분기를 모드 분기로 바꾸는가(u5b 시험 단언이 바뀐다), 되묻기를 대본 고정 문장으로 바꿔 계약에 선택 필드를 더하는가.
3. **FR4.7 사실을 기록에 어디까지 싣나**: V7 표에만 상세를 두고 이벤트는 현행 유지(U14 변경 없음)인가, 장면별 `endedAt`이나 전사 `seq` 범위만 선택 필드로 더하는가(스키마 편차 + U14 동시 병합), 도움 상세까지 이벤트에 더하는가.
4. **진행 단계(`Progress.stage`) 표시**: 새 단계를 기존 enum 6개로 매핑하는가(예: `REASK` → `FIRST_RESPONSE`), 계약과 프런트를 같은 날 확장하는가(깨는 변경), `stage`를 비우는가(스키마가 허용하는지 확인).
5. **새 흐름과 레거시를 가르는 기준**: `scriptVersion` 유무로 `onActive`에서 자동 분기하는가, 설정 스위치로 켜고 끄는가(되돌리기 쉽고 시험 조합이 늘어난다), 둘을 함께 쓰는가. `Factory` 시그니처를 넓힐지도 함께 정한다.

## AI-DLC로 진행하는 법
1. `unit/u12-practice-flow` 브랜치에서 아래를 입력한다.

   ```
   /aidlc --new-intent --scope refactor "U12 새 연습 흐름(u12-practice-flow) 구현. 지시 docs/units/u12/brief.md를 정본으로 한다. 인셉션 산출물은 261015-doc-sync-code-update 기록의 것을 그대로 쓴다."
   ```

2. 작업 기록 이름은 `u12-practice-flow`로 한다.
3. `/aidlc compose "deployment-pipeline과 deployment-execution을 빼줘"`를 입력하고 승인한다.
4. `/aidlc --depth Standard --test-strategy Standard`를 입력한다.
5. 단계: 기존 코드 파악 → Requirements Analysis → Functional Design → Code Generation → Build and Test.
   - 코드 스캔은 지금 체크아웃이 `main`과 같은 커밋인 브랜치에서 한다(팀 교정). 집중 스캔 범위: `backend/realtime-activity`(`session/` · `practice/` · `judgment/` · `guardrail/` · `dialog/` · `content/`), `contracts/`, `docs/units/u10 · u11 · u13`.
   - Requirements Analysis는 FR4 · FR7.3 · NFR · US1.1 ~ US1.6 · US1.9를 다시 묶는다('원 FR4.1'처럼 구분). 위 「사람이 정할 것」 5개가 개별 질문이다. 정본이 답을 주는 나머지는 가정 묶음으로 확인받는다.
   - Functional Design의 첫 작업은 위 「첫 작업」 5개를 코드로 확인하고 확장점 출력 능력 · 금지 능력 목록, 상태 전이표(J1 ~ J5 판정 결과 → 다음 상태), 기록 표 · 삭제 참여, `Factory` 분기, 진행 단계 표시를 정리하는 것이다. 결과는 `docs/units/u12/design.md`에도 옮긴다.
   - Code Generation 계획은 층별 순서(test-after)로 나누고 단계마다 `feat(u12): …`로 커밋한다. 권장 묶음:
     1. V7 + 새 표 · 삭제 참여자 · 설정 키 + `Factory` 분기 + 새 대본 타입
     2. 상태 기계(SCENE_INTRO ~ CLOSING)와 판정 요청 · 다음 행동
     3. 역할 정책 · 역할 전환 알림(승인 문구 재생) · 제브 막힘 처리
     4. 변형 · 생략 사유 · 기록 사실(`practiceRecord`) · 진행 단계 표시
   - 마이그레이션을 더하는 유닛은 단계마다 `:realtime-activity:check`와 함께 **`:core-api:check`도 돌린다**(U11 · U13에서 모듈 밖 회귀로 터졌다). `FlywayConfIT`(realtime 이력 목록 · 표 수 · 이력 행 수) · `SchemaPrivilegeIT`(realtime 최고 버전) · `RealtimeSchemaIT` · `RealtimeDb`(표 청소 목록) · `RealtimeDeletionParticipantOrderTest`를 V7 사실로만 바꾼다.
   - Build and Test: `bash scripts/verify.sh --mode compose` 전체와 이 볼트의 `u12` 범위 줄 80%(`backend/coverage-scope/u12.txt`, U13 `u13.txt` 머리 주석 형식), 인프라 · 이미지 점검을 돌린다. **연습을 지나는 끝-끝은 지금 없다**(C안은 자유대화 한 턴): 가짜 공급자 · 기기 시뮬레이터에 연습 시작과 되묻기 대본을 더할지 계획에서 정한다(AC1.2.3 시연 "그거 줘" → "어느 색?" → "파란색"). 실호출 · 실기기 확인은 사람 몫으로 열린 항목에 남긴다.
6. 자기 기록 폴더(`aidlc/spaces/default/intents/<날짜>-u12-practice-flow/`)는 브랜치에 함께 커밋한다.
7. 이 PC에 `jq` · `unzip` · `shellcheck`와 GNU `date`가 필요하다(`verify.sh` 전제). `verify.sh`가 도는 동안에는 저장소 안에 파일을 쓰지 않는다(T 단계가 작업 트리를 비교한다). 문서에 비밀 스캔 패턴과 겹치는 모양(URL 자격 증명 · 키 모양)의 리터럴을 그대로 적지 않는다.

## 주의 (실수하기 쉬운 곳)
- `SessionExecutor`는 **4,254줄**이다(당시 3,388줄). 호출 지점만 더하고 상태는 협력 객체에 둔다(NFR8). 레거시를 떼지 않고 공존시키며(ADR-001) 기존 U4b · U5b · U11 · U13 시험은 단언 변경 없이 통과해야 한다.
- 선 형식 · 모델 · 제브 문턱은 앞 볼트에서 확인된 값을 쓴다. 제브 문턱 0.5는 **임시 시험값**이고 모호한 사례 분포는 아직 모른다.
- 실제 아동 데이터의 외부 전송 결정은 R-F15 확인 기록 뒤다(`infra/RUNBOOK.md` 단계 4, 지금 비어 있다).
- `team.md`의 "다음 번호는 core V8 · realtime V5"는 낡았다. realtime 다음은 V7(이 볼트)이다.

## 끝나면
- `docs/units/u12/design.md` · `report.md`를 쓰고 `docs/units/README.md` 현황표를 고친다(U12 줄과 U8 ~ U15 볼트 표).
- 모든 유닛이 `main`에 모였으므로 **통합 점검(끝-끝 · 실호출 · 실기기)은 별도 작업 기록으로 연다**(팀 교정). 이 볼트는 자기 범위의 `verify.sh` 전체까지 책임진다.
