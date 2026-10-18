## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-18T03:59:07Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Minor | aidlc/spaces/default/intents/261017-u12-practice-flow/construction/code-generation/code-summary.md > 4. 계획 대비 편차 · 메모 (OutputKind 항목)와 docs/units/u12/design.md 38행 | 승인된 Functional Design(functional-spec 161행, 검토 R-06)은 슬롯의 scenarioId를 현재 장면의 scenarioRef로, OutputKind를 발화별(HELP_QUESTION 등)로 정했다. 구현은 `SessionExecutor.NOTICE_SCENARIO_ID = new UUID(0L, 0L)`과 역할별 표지(코치 CUE, 친구 SCENARIO)로 바꿨다. code-summary 4절은 "구현 중 새 설계 편차는 없다"고 적으면서 같은 절에서 이 차이를 인정하고, `contracts/deviations.md`의 U12 편차 표(U12-a, D1 ~ D3)에는 번호 있는 편차로 올라 있지 않다. design.md에만 있다. 제브 요청과 기록에 영향이 없다는 설명은 코드와 맞다. | 이 차이를 번호 있는 편차(U12-D4)로 deviations.md와 design.md 편차 표에 올리거나, code-summary의 "새 설계 편차 없음" 문장을 사실에 맞게 고친다. | New |
| R-02 | Minor | aidlc/spaces/default/intents/261017-u12-practice-flow/construction/code-generation/traceability.json > coverage FR8 (N/A)와 docs/units/u12/ | 요구사항 FR8.2는 `docs/units/u12/design.md` · `report.md`를 적도록 한다. 계획 개요도 후속 과제를 `report.md`에 적는다고 했다. `docs/units/u12/`에는 `brief.md`와 `design.md`만 있고 `report.md`가 없다. 기기 시뮬레이터와 `verify.sh` 단계 확장이라는 후속 과제는 code-summary와 traceability의 한 줄에만 있다. FR8.1의 "기기 시뮬레이터에 연습 시작과 되묻기 대본" 요구는 계획이 `PracticeFlowIT` 한 개로 줄였고(계획 개요에 근거와 합의가 있다) 이는 계획 승인 범위다. 줄인 부분을 사람이 찾을 수 있는 문서가 없다는 점이 지적이다. | `report.md`를 만들어 후속 과제(시뮬레이터 대본 확장, 낡은 team.md 마이그레이션 번호 알림)를 적거나, Build and Test 단계가 만든다고 traceability FR8 행에 명시한다. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261017-u12-practice-flow/construction/code-generation/traceability.json > coverage AC1.1.1, AC1.1.3, AC1.1.4 | 이 세 AC는 status가 OK인데 근거가 "실행부 기존 구현(U4b · U11), 기존 시험 무변경 통과"다. U12가 새로 낸 시험이 이 AC를 직접 단언하지 않는다. AC1.1.1의 target에 적힌 "PracticeFlowIT 시작 경로"는 참여 확인 단언이 아니라 시작 경로다. 존재하는 파일(SessionJoinU4bTest, JoinJudgmentExecutorTest)을 가리키므로 허위는 아니나 OK는 회귀 근거만 뜻한다. 같은 방식의 AC1.1.2는 편차 U12-D1을 명시해 더 정확하다. | 세 행에 "회귀 근거만, U12 신규 단언 없음"을 명시하거나 N/A로 둔다. 이 변경은 코드에 영향이 없다. | New |
| R-04 | Minor | backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionExecutor.java > `speakGenerated`, `closeGeneratedSlot`, `sealAndGate` 생성형 분기 (diff 약 +121줄) | NFR6의 "실행부에는 호출 지점만 더한다"에 비해 변경이 호출 지점만은 아니다. 제브 fail-closed 정책(`!guardrail.enabled()`이면 폐기, `begin` 실패 처리), 슬롯 종료 사실 한 번 보장, 재지시 카운터 초기화가 실행부 안에 들어갔다. 이 정책이 실행부에 있어야 하는 이유(슬롯, 세대, 검사표가 실행부 소유)는 타당하고 방향은 실행부에서 확장으로 한 방향이며 `PracticeFlowArchTest.EXECUTOR_KNOWS_NO_FLOW`가 `practiceflow` 의존 없음을 강제한다. 다만 `SessionExecutor`는 u12 커버리지 범위(`backend/coverage-scope/u12.txt`)에서 빠져 있고, 이 분기의 커버리지는 모듈 전체 80%로만 본다(범위 파일 머리말과 code-summary 3절에 정직하게 적혀 있다). | 조치는 필요 없다. 정보로 둔다. Build and Test에서 `PracticeGeneratedSpeechTest`가 닿는 생성형 분기의 줄 커버리지를 파일 단위로 한 번 확인하면 좋다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| source-manifest.json 대 `git diff --name-only 17ba094..HEAD -- . ':!aidlc'` 대조 스크립트 | 누락 0, 존재하지 않는 경로 0 | 적용 소스 경로(운영 · 시험 · 계약 · 문서) 전부가 목록에 있고, `practiceflow/` 디렉터리 항목이 그 아래 파일을 덮는다. |
| `:realtime-activity:test -PincludeTags=u12 -Pmujoong.test.db=docker` (가짜 공급자, 테스트 대역) | 201개, 실패 0, 오류 0, 건너뜀 0 | code-summary가 말한 클래스별 개수(PracticeGeneratedSpeechTest 10, PracticeFlowRecorderIT 4, PracticeFlowRecordTest 4, PracticeProgressMappingTest 2, PracticeFlowIT 2, PracticeFlowArchTest 4)와 일치한다. 이것은 테스트 대역 증거이고 실제 API와 실제 기기 증거가 아니다. |
| `-PincludeTags=u12 -PcoverageScope=u12` JaCoCo | 22개 파일, 미충족 48줄 · 충족 약 1,222줄 | code-summary의 96.2%(1,222 / 1,270)와 일치한다. `GoalSpec` 3 미충족 / 7 충족(70%)도 맞다. 80% 하한을 낮춘 흔적이 없다. |
| `arch.*`, `ConfigKeyContractTest`, `TimeLiteralScanTest`, `SessionJoinU4bTest`, `PracticeMigrationRegistrationTest` 지정 실행 | 통과 | 새 키 두 개와 숫자 시간 값 대조가 통과하고 레거시 U4b 시험이 무변경으로 통과한다. |
| 프로젝트 규칙 정적 검색 (`practiceflow/` 운영 코드) | 직접 시스템 시각 읽기 0, `LoggerFactory` 0, `test-support` 참조 0, `practice.` 패키지 import 0, 숫자 시간 리터럴 0 (`MAX_LENGTH = 500` · `CORE_FUNCTION_MAX = 500`은 글자 수, 순서 190은 삭제 참가자 순서) | R-F6, R-F7, R-F8, BR10.2와 맞다. 오류 기록은 `failures` 소비자를 거쳐 `PracticeRuntimeConfiguration`에서 `events.appError(...)`로 간다. 넓은 catch(`PracticeConversation` 363행, 828행)는 근거 주석이 있고 삼키지 않는다. |
| traceability 대상 파일 존재 확인 (샘플: FlowTransitionsTest, PracticeConversationTest, PracticeGeneratedSpeechTest, PracticeFlowIT, PracticeFlowRecorderIT, PracticeFlowSchemaIT, PracticeFlowArchTest 등) | 모두 존재하고 `@Tag("u12")` | OK로 적힌 AC의 근거 시험이 실제로 있다. 단 R-03의 회귀형 행은 신규 단언이 아니다. |

### Summary

계획 21단계가 코드와 맞고, source-manifest.json은 변경 전 경로를 빠짐없이 담았으며, code-summary의 시험 수와 u12 범위 커버리지(96.2%)는 직접 돌려 재현했다. 프로젝트 금지 규칙 위반, 순환 의존, 하한 약화는 찾지 못했고 `SessionExecutor`로 가는 의존도 한 방향이다. 남은 것은 설계 편차 기록(R-01), 후속 문서 `report.md`(R-02), 회귀형 추적 행 표기(R-03)로 모두 Minor다. 모든 합격은 테스트 대역과 합성 대화 증거다.
