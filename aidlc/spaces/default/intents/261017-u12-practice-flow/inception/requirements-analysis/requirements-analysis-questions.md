# Requirements Analysis 질문 — U12 새 연습 흐름

> 기준 문서: `docs/units/u12/brief.md`(정본 지시서), 원래 기록 `261015-doc-sync-code-update`의 요구사항 FR4.1 ~ FR4.8 · FR7.3 · NFR, 스토리 US1.1 ~ US1.6 · US1.9(AC 31개), 이 기록의 코드 스캔 `inception/reverse-engineering/developer-scan.md`와 공유 CodeKB(`aidlc/spaces/default/codekb/mujungi/`), 앞 볼트 `docs/units/u10 · u11 · u13`.
> 이 기록은 새 업무 요구사항을 만들지 않는다. 원래 요구사항을 U12 구현 단위로 다시 묶는다(project.md 교정). 그래서 정본끼리 부딪히거나 정본에 답이 없는 것만 개별 질문으로 묻고(5개), 코드 사실과 바로 맞는 나머지는 맨 아래 가정 묶음으로 확인받는다.
> 코드 스캔에서 지시서에 없던 사실이 나왔다: **realtime 내부 시작 API가 연습 세션을 거절한다**(`SessionInternalController` 164줄은 `FREE_TALK`만 허용, `StartBody`에 `practice` 필드 없음, 시작 처리기가 `practiceJson`에 null을 넘김). 이 구간을 여는 것은 U12의 일이 되며 가정 3에서 확인받는다. 이 사실은 아래 Q5의 전제에도 닿는다.

## Q1. 연습 시작 전에 휴식을 요청하면 어떻게 할까요? (AC1.1.2 대 U11-D6)

정본(AC1.1.2)은 시작 안내 뒤 휴식 요청이 오면 `D_REST_ACK_START`를 들려주고 연습을 시작하지 않고 기다리라고 합니다. 그런데 U11이 이미 구현한 동작(U11-D6, `docs/units/u11/design.md`)은 "참여 확인 중 휴식은 받아 주는 말 없이 재안내"입니다. 이유는 계약 편차 (U4b-a)의 "참여 전에는 응답이 들릴 수 없다"이고, `D_REST_ACK`는 음성 파일이 없는 승인 발화라 재생이 무음이 됩니다(U11-D1). 이 참여 확인(J1)은 연습과 자유대화가 같은 실행부 코드를 씁니다.

A. 정본 AC1.1.2를 U11 동작에 맞춰 개정하고 편차 `U12-D1`로 기록합니다. 시작 전 휴식에는 말 없이 재안내(U11-D6)를 그대로 둡니다. U11 단언은 바뀌지 않고, 이후 승인 발화 재생 길을 열어도 참여 전 응답은 계약 (U4b-a)대로 들리지 않는 상태가 유지됩니다. (추천)
B. AC1.1.2대로 연습 세션에서만 휴식에 `D_REST_ACK_START`를 들려줍니다. 계약 편차 (U4b-a)를 연습 세션에 한해 풀고, 실행부 J1 휴식 분기를 연습 여부로 나눠 U11 단언을 연습 쪽만 바꿔야 합니다. 자유대화 동작은 그대로입니다.
X. Other (please specify)

[Answer]: A

## Q2. 연습 통제 아래에서 친구 되묻기 · 코치 발화는 어떻게 들리게 할까요?

지금 연습이 출력을 쥔 동안(`controlsOutput()` = true) 승인 원문과 같지 않은 출력은 모두 `GATE_PATTERN`으로 버려지고(`SessionExecutor` 3004 ~ 3013줄), 그 앞에서 `outputHeld()` 보류도 걸립니다. 대본에는 되묻기 문장 필드가 없어서 "어느 색?" 같은 친구 되묻기와 코치 발화는 GPT-Live가 새로 만드는 발화일 수밖에 없고, 그대로면 **소리가 나지 않습니다**. 제브의 `PRACTICE_PEER` · `PRACTICE_COACH` 요청 경로는 이미 만들어져 있지만 운영에서 부르는 곳이 없습니다(`begin`은 `FREE_TALK` 한 곳). U5b 시험 27파일이 지금 폐기 규칙에 기대고 있습니다.

A. 새 확장(`PracticeConversation`)이 출력을 쥘 때만 폐기 규칙을 바꿉니다. 확장이 허용한 역할 발화(친구 되묻기 · 코치)는 제브 `PRACTICE_PEER` / `PRACTICE_COACH`로 검사해 `ALLOW`이면 재생하고, 막히면 폐기 · 재지시 1회(U13 규칙)로 가며 두 번째 막힘은 `GUARDRAIL_BLOCKED`로 확장에 알립니다. 레거시 `RuntimePracticeExtension`의 폐기 규칙과 U5b 시험 단언은 그대로 둡니다. (추천)
B. 기존 연습 통제 분기를 모든 연습에 대해 같은 모드 분기로 바꿉니다. 레거시 확장도 새 규칙을 따르므로 U5b 시험 단언이 바뀝니다.
C. 되묻기 · 코치 발화를 GPT-Live가 만들지 않고 대본의 고정 문장으로 바꿉니다(계약 선택 필드 추가). 제브 호출이 필요 없지만 친구 역할이 자연스러운 말이 아니라 정해진 문장이 되고, 대본을 만드는 U10 쪽(생성 · 검토 · 스키마)도 함께 바뀝니다.
X. Other (please specify)

[Answer]: A

## Q3. 기록에 도움 · 역할 사실을 어디까지 실을까요? (FR4.7)

FR4.7은 역할(친구/코치)과 도움(종류 · 내용 · 예시 여부 · 동의 응답 · 시각)을 기록하라고 합니다. 그런데 realtime에서 U14 core로 가는 기록 이벤트(`practice-record-v2`)는 `facts` 15종 enum과 장면 `startedAt` 등만 받고, `scenes`와 `liveOutcome` 객체가 `additionalProperties: false`라서 필드를 더하면 깨는 변경이고 U14와 같은 날 합쳐야 합니다. 또 U14 재판정은 장면별 전사 범위를 받지 못해 장면과 전사의 대응을 모델이 추정합니다(`RejudgmentGateway`). 도움 `content`는 개인정보일 수 있습니다.

A. 상세(역할 · 도움 종류 · 내용 · 동의 · 시각)는 realtime V7 표에만 두고 이벤트는 현행 형식(`facts` 15종 + `startedAt` + `liveOutcome.timing` + `variantSkipReason`)을 유지합니다. U14는 바뀌지 않습니다. 장면 · 전사 대응 개선은 이번에 하지 않습니다. 도움 `content`는 원문이 아니라 분류 코드와 길이만 저장합니다. (추천)
B. A에 더해 장면별 종료 시각이나 전사 번호 범위만 선택 필드로 이벤트에 더합니다. 스키마 편차 + U14 동시 병합이 필요하지만 재판정의 장면-전사 대응이 정확해집니다.
C. 도움 상세까지 이벤트에 더합니다. U14가 모두 받도록 바꿔야 하고 개인정보 범위가 넓어집니다.
X. Other (please specify)

[Answer]: A

## Q4. 12 화면의 연습 진행 단계 표시는 어떻게 할까요?

새 상태 기계는 단계가 늘어납니다(`REASK` · `FOCUS` · `RETRY` · `VARIANT` 등). 그런데 계약 `live-view.schema.json`의 `Progress.stage`는 6개 값(`SCENARIO_INTRO` · `FIRST_RESPONSE` · `HELP_OFFER` · `CUE` · `AFTER_CUE_RESPONSE` · `NEW_SCENARIO_RESPONSE`)에 `additionalProperties: false`입니다. core는 모르는 값을 비우고, 프런트는 stage가 비거나 6개 밖이면 "현재 연습 단계를 확인할 수 없어요."를 보입니다(계약은 stage를 선택 필드로 두지만 프런트는 필수로 읽음). 시안(`refined-mockups`)은 새 상태 이름을 만들지 않는다고 합니다.

A. 새 단계를 기존 6값으로 매핑합니다(예: `SCENE_INTRO` → `SCENARIO_INTRO`, `OPPORTUNITY` · `REASK` → `FIRST_RESPONSE`, `HELP_OFFER` → `HELP_OFFER`, `FOCUS` → `CUE`, `RETRY` → `AFTER_CUE_RESPONSE`, `VARIANT` → `NEW_SCENARIO_RESPONSE`). 화면 표시용 접기이므로 접기는 `progress()` 매핑에만 두고 저장 값 · 이벤트 · 기록은 새 단계를 사실대로 둡니다. 계약과 프런트는 바뀌지 않습니다. (추천)
B. 계약과 프런트를 같은 날 확장합니다(`stage` enum에 새 값 추가, 프런트가 stage를 선택으로 읽게 수정). 깨는 변경이며 U14와 프런트 모두 영향이 있고 화면 문구(copy)도 새로 정해야 합니다.
X. Other (please specify)

[Answer]: A

## Q5. 새 흐름과 레거시 연습 런타임을 가르는 기준은 무엇일까요?

`PracticeSessionExtension.Factory.create(sessionId)`는 `practiceJson`을 받지 않고, `practiceJson`은 `onActive`에서 처음 보입니다. 운영 `Factory`는 `@ConditionalOnMissingBean` 하나입니다. 그리고 코드 스캔으로 확인된 사실: 지금 realtime은 연습 세션 시작을 거절하므로(위 머리말) **레거시 연습 런타임도 운영에서 한 번도 돌지 않았고**, 레거시 기본 `EvaluationProvider` · `CueProvider` 빈은 실패를 돌려주도록 되어 있어 그대로 연결하면 레거시 세션은 멈춥니다. 한편 ADR-001은 "새 흐름을 끄면 레거시가 그대로 남아 되돌리기 쉽다"고 하고, 레거시 클래스를 비우면 CI `u5b-scope` 줄 80% 하한이 막습니다. 대본에 `scriptVersion`이 없는 경우는 core가 실제 Luna 구성이 아닐 때(레거시 8필드뿐)입니다.

A. 대본의 `scriptVersion`(`scenario-script.v1`) 유무로 `onActive`에서 자동 분기합니다. 있으면 새 흐름, 없으면 레거시 런타임으로 갑니다. 대본 형식이 곧 기준이라 설정이 늘지 않지만, 없을 때의 레거시 경로는 기본 포트가 실패를 돌려주므로 사실상 멈춘 채로 끝납니다. (추천)
B. 설정 스위치(예: `mujoong.practice.flow` = `NEW` · `LEGACY`, 기본 `NEW`)로 정합니다. 되돌리기는 쉽지만 `LEGACY`로 두어도 위 이유로 레거시 세션이 멈추고, 시험 조합이 늘어납니다.
C. 새 흐름만 운영에 연결합니다(`Factory`가 항상 새 확장을 만든다). 레거시 클래스는 삭제하지 않고(CI 하한 · 기본 빈 실패 단언 유지) 과거 기록 읽기 · 복구 · 시험용으로만 남깁니다. 대본에 `scenes`가 없으면 새 흐름이 연습을 시작하지 않고 `PRACTICE_EXTENSION_FAILED`로 끝납니다.
X. Other (please specify)

[Answer]: A

## Assumption Confirmation

아래는 정본 문장이나 코드 사실과 바로 맞는다고 보고 정한 가정입니다. 한 묶음으로 확인해 주세요.

1. **ID 다시 묶기**: 이 기록의 요구사항은 새 번호(FR1 ~, NFR1 ~)를 쓰고 원래 ID를 '원 FR4.1'처럼 함께 적습니다. 스토리 · 수용 기준(US1.1 ~ US1.6 · US1.9, AC1.1.1 ~ AC1.9.3)은 원래 기록의 ID를 그대로 씁니다. 원 기록의 승인 문서는 고치지 않고 이 기록의 편차 표(`U12-Dn`)에 적습니다.
2. **범위**: 지시서 「이 볼트가 하는 것」 4가지(연습 상태 기계 · 역할별 출력 정책 · 기록과 V7 · 레거시 분리)만 합니다. J1 · J6 판정(U11), 제브 검사(U13), 상황 생성(U10), 결과 조립(U14), 실호출 · 실기기 확인(FR12)은 하지 않습니다. AC31개는 모두 `[자동-대역]`(가짜 공급자로 CI 합격 기준)이고 모델 판단 품질은 `[실호출-합성]` · `[실기기]` 확인표로 따로 봅니다.
3. **realtime 시작 경로 열기**: 내부 시작 API가 `activityType == PRACTICE`와 선택 본문 `practice`(계약 `internal-session-start-request.schema.json`에 이미 선언됨)를 받게 하고, 시작 처리기가 `practiceJson`을 `Plan`에 실어 `PracticeContext`로 넘깁니다. 지금 이 거절을 단언하는 시험 둘(`RealtimeInternalApiIT` · `SessionInternalControllerU4bTest`)은 새 사실로만 바꿉니다(자유대화 시작 단언은 그대로). 후보 목표 필드(`goalKind` · `candidateRef` · `candidateVersion` · `goalContext`)는 realtime 타입에서 지금 버려지므로 이 단위가 읽는 범위를 설계에서 정합니다.
4. **U10 대본 소비**: 병합된 대본 필드(`scenes[]` 6필수 필드 + `focusPoints` · `expressionExamples` · `relation` · `place` · `topic`, `scriptVersion` · `selfReview` · `generation`)를 읽는 타입을 새로 만들고 레거시 `PracticeScenarioClient.Scenario`는 그대로 둡니다. 첫 목표(order 1)는 시작 패키지의 `firstScenario`를, 나머지 목표는 `POST /internal/practice/scenarios`(kind ORIGINAL)를 씁니다. 변형 장면은 같은 응답의 VARIANT `scenes[]`를 쓰므로 별도 호출이 없습니다. `sceneRunId`는 realtime이 만들고 `scenarioRef`에는 대본 전체의 `scenarioId`를 씁니다. 정본 계약은 바꾸지 않습니다(고칠 점이 생기면 U10 담당과 같은 날 합침).
5. **승인 발화 재생과 역할 전환**: `approvedSpeech`(`COACH_SWITCH` · `PEER_RETURN` · `D_REST_ACK*`) 텍스트가 확장점에 닿는 길을 만들고, `announceRoleSwitch`를 실제로 구현해 `true`를 돌려줍니다. 알림이 제브에 막히면 알림 없이 역할을 바꾸지 않습니다(AC1.5.6): 1회 다시 지시 후 장면을 정리합니다. 음성 파일이 없는 승인 발화가 무음으로 끝나는 지금 동작은 새 흐름에서 쓰지 않습니다.
6. **판정 호출**: J2 ~ J5는 확장이 `requestJudgment`로 요청하고, 판정 기준 `coreFunction`은 목표의 표시 이름 또는 목표 표현에서 만듭니다(정확한 필드는 Functional Design에서 정함). 결과 사건(`JUDGMENT_RESULT` · `JUDGMENT_ABSTAINED` · `HELD_OUTPUT_TRANSCRIPT`)을 상태 전이로 연결합니다. 다음 행동 지시문에는 판정 라벨 · 확률 · 기준이 들어가지 않습니다(AC1.2.5). 지시 선 형식은 `session.instructions.append` 하나입니다(U11-D9). 판정 기록 `judgment_live.goal_ref` / `scene_ref` 칸을 채울지는 설계에서 정합니다.
7. **시간과 설정**: 무응답 10초 · 집중 90초 · 목표 ACTIVE 10분은 U8이 만든 `mujoong.timing.conversation.*`를 이제 운영 코드가 읽습니다(새 시간 키는 만들지 않음). 다시 해 보기 2회와 상황 다시 설명 1회는 시간 값이 아닌 새 설정 키로 두고 `contracts/ai-io.md` 표와 `ConfigKeyContractTest` 대조에 넣습니다(R-M10). 연습 시계는 J6 휴식에서만 멈추는 현재 동작(`practiceProgressPaused`)을 유지하고, J2 ~ J5가 열려 있는 동안의 시계는 Functional Design에서 의도를 확인합니다.
8. **realtime V7 · 삭제**: 새 표 두 개(장면 실행 · 도움 · 역할 전환 기록, 이름은 설계에서 정함)를 `V7__u12_practice_flow.sql`로 만들고 V4 레거시 표 제약은 건드리지 않습니다(`infra/migration-approvals.txt` 대상 아님). 새 삭제 참여자는 세션(200) 앞에 두고 `DeletionCompleted.Counts`는 바꾸지 않습니다. 정산 복구(`PracticeRecordRecovery`)는 새 표에서도 `practice` 값을 다시 만들 수 있게 합니다.
9. **기록 이벤트**: 새 흐름은 `record.practice.flowVersion = "practice-conversation-v1"`과 `scenes[]` 필수 4필드(`sceneRunId` · `goalRef` · `sceneKind` · `startedAt`)를 `practiceRecord()`로 채웁니다. `goalRef`는 보호자 `goalSelectionId`입니다(realtime 세션 목표 UUID가 아님). `facts`는 계약 15종만 쓰고 `liveOutcome.timing` · `variantSkipReason` 값을 계약 enum 안에서 채웁니다(Q3 답에 따라 범위 결정).
10. **레거시 보존**: 레거시 `PracticeProtocol` · `EvaluationProvider` · `ResponseEvaluator` · `CueProvider` · `StageOutputPolicy` · V4 표 5개 · `RealtimePracticeDeletionParticipant` · `PracticeRecordRecovery`는 삭제하지 않습니다. `u5b-scope` 줄 80% 하한과 `PracticeRuntimeConfigurationTest`의 기본 빈 실패 단언을 낮추거나 끄지 않습니다(R-F2). 새 흐름은 이 포트에 기대지 않고 멈추지 않습니다(AC1.1.5).
11. **구조**: 새 클래스는 레거시 `practice/`와 섞이지 않는 새 패키지(예: `practice/conversation`)에 두고, 실행부와는 `Env` 인터페이스 · `PracticeOutput` 능력으로만 닿습니다(ADR-005, U11 · U13 선례). 금지 능력(상태 전이 직접 호출 · DB 쓰기 · 기기 원시 명령 · 제브 우회 송신 · 라벨 · 확률을 지시문에 넣기)을 ArchUnit으로 강제합니다. `SessionExecutor`(4,254줄)에는 호출 지점만 더하고 상태는 협력 객체에 둡니다(NFR8).
12. **시험과 끝-끝**: test-after, 태그 `u12`, 범위 파일 `backend/coverage-scope/u12.txt`(범위 줄 80%), 시험 전략 Standard입니다. 마이그레이션을 더하므로 단계마다 `:realtime-activity:check`와 `:core-api:check`를 함께 돌립니다(core-api `FlywayConfIT` · `SchemaPrivilegeIT` 갱신 포함). 연습을 지나는 끝-끝은 지금 없으므로, 가짜 공급자 · 기기 시뮬레이터에 연습 시작과 되묻기 대본을 더하는 최소 범위를 Code Generation 계획에서 정합니다(AC1.2.3 "그거 줘" → "어느 색?" → "파란색" 시연이 목표).
13. **증거 구분**: 테스트 대역 · 합성 대화 · 실제 API · 실제 기기 결과를 따로 적고 대역 성공을 실제 성공으로 표시하지 않습니다(R-M11). GPT-Live 위의 되묻기 품질 · 코치 표현 · 아이가 역할 전환을 알아듣는지는 `[실호출-합성]` · `[실기기]`로 사람이 따로 확인합니다.
14. **브랜치와 병합**: `unit/u12-practice-flow`에서 작업하고 PR은 merge commit으로 합칩니다. `aidlc/` 기록은 함께 커밋합니다. 배포 단계는 이 기록에서 뺐습니다(Q5-A).

A. Accept assumptions
B. Convert to follow-up questions

[Answer]: A. Accept assumptions

## Consolidated Summary Confirmation

- Q1 시작 전 휴식: 정본 AC1.1.2를 U11 동작(말 없이 재안내, U11-D6)에 맞춰 개정하고 편차 `U12-D1`로 기록한다. U11 단언은 바뀌지 않는다.
- Q2 연습 통제 아래 새 발화: 새 확장(`PracticeConversation`)이 출력을 쥘 때만 폐기 규칙을 바꾼다. 확장이 허용한 역할 발화(친구 되묻기 · 코치)는 제브 `PRACTICE_PEER` / `PRACTICE_COACH`로 검사해 `ALLOW`이면 재생하고, 막히면 폐기 · 재지시 1회, 두 번째 막힘은 `GUARDRAIL_BLOCKED`로 확장에 알린다. 레거시 확장과 U5b 시험 단언은 그대로 둔다.
- Q3 기록: 도움 · 역할 상세는 realtime V7 표에만 두고 기록 이벤트는 현행 형식을 유지한다(U14 변경 없음). 도움 내용은 원문이 아니라 분류 코드와 길이만 저장한다.
- Q4 진행 단계: 새 단계를 기존 6값으로 `progress()` 매핑에서만 접는다. 저장 값 · 이벤트 · 기록은 새 단계를 사실대로 둔다. 계약과 프런트는 바뀌지 않는다.
- Q5 분기 기준: 대본의 `scriptVersion` 유무로 `onActive`에서 자동 분기한다(있으면 새 흐름, 없으면 레거시 런타임). 지금 realtime이 연습 세션 시작을 거절하므로 레거시도 운영에서 돈 적이 없고 레거시 기본 포트는 실패를 돌려준다는 사실을 기록한다.
- 가정 14개 수용: realtime 시작 경로를 연다(`PRACTICE`와 선택 본문 `practice`, 거절 단언 시험 두 개는 새 사실로만 변경), U10 대본 소비 타입 신설, `approvedSpeech`가 확장에 닿게 하고 `announceRoleSwitch` 구현(제브가 막으면 역할 유지), 판정 호출 · 설정 키 · V7 새 표 2개와 삭제 참여자(200 앞), 기록 이벤트 `flowVersion`, 레거시 삭제 금지 · `u5b-scope` 하한 유지, 새 패키지와 ArchUnit, `u12` 시험 · 범위 80% · `:core-api:check` 함께, 증거 구분, 브랜치와 병합.

Does this all look correct before I generate the requirements artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
