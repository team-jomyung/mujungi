# U12 새 연습 흐름 — 코드 생성 요약

> 작업 기록 `261017-u12-practice-flow` · Code Generation · 브랜치 `unit/u12-practice-flow` · 2026-10-18
> 계획 `code-generation-plan.md`(21단계, 묶음 A ~ D + 마무리), 시험 안내 `unit-test-instructions.md`. 모든 합격은 **테스트 대역**(가짜 공급자 · 가짜 제브 · 가짜 판정)과 **합성 대화** 증거다(R-M11). 실제 AI API · 실제 제브 · 실제 기기에서는 한 번도 돌지 않았다(R-F1 · R-F15).

## 1. 만든 · 고친 파일

### 운영 코드(`backend/realtime-activity/src/main/`)
- 새 패키지 `kr.mujoong.realtime.practiceflow`(21개): `PracticeFlowRouter` · `PracticeConversation` · `FlowTransitions` · `FlowState` · `RolePolicy` · `PracticeInstructions` · `PracticeScript` · `PracticeScriptReader` · `PracticeScriptException` · `PracticeStartPackage` · `GoalSpec` · `PracticeScriptRequester` · `FlowRecorder` · `PracticeFlowRecorder` · `PracticeFlowRepository` · `PracticeFlowRecordAssembler` · `PracticeFlowRecordRecovery` · `PracticeFlowDeletionParticipant` · `PracticeFlowSettings` · `PracticeFlowConfiguration` · `PracticeFlowTypes`.
- 마이그레이션 `V7__u12_practice_flow.sql`(새 표 `realtime_practice_scene_run` · `realtime_practice_help`, expand 전용, 원문 칸 없음).
- 고친 파일: `SessionExecutor`(호출 지점: `speakGenerated` · `announceRoleSwitch` · `sealAndGate` 생성형 분기 · 슬롯 닫기 도우미 · `Plan.approvedSpeech`) · `GuardrailCoordinator`(`Env.practiceVoided` 기본 메서드와 호출 한 곳) · `PracticeSessionExtension`(`PracticeContext` 승인 발화 지도 · `speakGenerated` · `PlaybackFact` 읽기 도우미) · `PracticeSpeech`(`generated` 성분) · `StartSessionHandler` · `SessionInternalController`(PRACTICE + 선택 본문 `practice`) · `PracticeRuntimeConfiguration`(라우터 + 운영 기록기 연결, Factory 시그니처 불변) · `PracticeRecoveryRouter` · `RealtimeProperties` · `application.yml`.
- 계약 문서: `contracts/ai-io.md` 10.14(횟수 키 두 개) · `contracts/deviations.md` U12 편차. 스키마 · 이벤트 모양은 바꾸지 않았다.
- 문서: `docs/units/u12/design.md`(새로 씀) · `docs/units/u12/brief.md`(AC 31 → 29) · `docs/units/README.md`.
- 범위 파일 `backend/coverage-scope/u12.txt`(22개 운영 파일).

### 시험(`backend/realtime-activity/src/test/`, 태그 `u12`)
- 묶음 A: `PracticeFlowSchemaIT` · `PracticeFlowRepositoryIT` · `PracticeFlowSettingsTest` · `PracticeScriptReaderTest` · `PracticeScriptRequesterTest` · `PracticeRecoveryRouterTest` · `PracticeStartPathIT` · `PracticeStartBodyU12Test` · `PracticeFlowRecordAssemblerTest`(+ 기존 `RealtimeSchemaIT` · `RealtimeInternalApiIT` · `SessionInternalControllerU4bTest` · core-api `FlywayConfIT` · `SchemaPrivilegeIT`를 V7 사실로만 갱신).
- 묶음 B: `FlowTransitionsTest` · `PracticeConversationTest` · `PracticeFlowRouterTest`(+ 도구 `PracticeFlowTestKit`).
- 묶음 C: `PracticeGeneratedSpeechTest`(10개: ALLOW 재생 · 코치 모드 · 첫 막힘 같은 지시문 재지시 + 두 번째 막힘 0바이트 · 제브 비활성 폐기 · 검사 중 끼어들기 INTERRUPTED 한 번 · 재지시 미시작 FAILED 한 번 · 슬롯 없는 발화 폐기 · 승인 알림 · 잘못된 역할 · 생성형 슬롯은 원문 일치 불가).
- 묶음 D: `PracticeFlowRecorderIT`(4) · `PracticeFlowRecordTest`(4: 표식 문자열 · 값 타입에 문자열 칸 없음 · 행 생성 조건) · `PracticeProgressMappingTest`(2) · `PracticeFlowIT`(2: AC1.2.3 한 장면 시작 → 도입 → 기회 → J2 일부 전달 → 제브 PRACTICE_PEER ALLOW → J3 충분 → 정리 → 정산 → `record.practice`, 두 번째 막힘 장면 정리) · `PracticeFlowArchTest`(4, 위반 표본 `PracticeFlowArchViolationSample`으로 규칙 자기 확인).
- 도구: `SessionHarness`에 `approvedSpeech` 필드(추가만).

## 2. 핵심 구현 결정

1. **생성형 슬롯은 `PracticeSpeech`의 변형**(`generated`)으로 기존 승인 발화 칸(`pendingPracticeSpeech`)에 넣는다. `matches()`가 항상 거짓이라 제브를 건너뛸 수 없고, 레거시 승인 발화(`generated=false`)의 기존 경로와 U5b 시험은 무변경이다.
2. **`discardForRetry`는 슬롯을 닫지 않는다**(FA3를 코드로 확인). 따라서 재지시 때 슬롯을 다시 열 필요가 없다. 대신 설계가 짚지 않은 두 길에서 슬롯이 남는 것을 찾아 닫았다: 무효로 닫힌 검사(`voided`)와 재지시가 소리로 이어지지 않은 경우(`retryNotHeard` → `finishAbandoned`). `Env.practiceVoided`(기본 메서드)와 `closeGeneratedSlot` 한 곳으로 종료 사실이 한 번만 가게 했다.
3. **발화마다 제브 재지시 수를 새로 센다**(`guardrail.onTurnBoundary()`를 `speakGenerated` 안에서 호출). 아이 말 없이 이어지는 연속 생성형 발화(알림 → 코치 발화)에서 앞 발화의 재지시가 뒤 발화의 첫 막힘을 곧바로 두 번째 막힘으로 만들지 않게 한다.
4. **`announceRoleSwitch`**는 `plan.approvedSpeech()`의 `COACH_SWITCH`/`PEER_RETURN` 문장을 기존 `speak` 길로 낸다. 문장이 없으면 거짓이고 확장이 생성형 알림으로 낸다(BR5.4).
5. **운영 기록기는 메모리 먼저, 표 나중**(`PracticeFlowRecorder`). 표 쓰기가 실패해도 정산 값이 비지 않는다. 쓰기는 호출마다 짧은 트랜잭션 하나다.
6. **진행 6값 접기**는 `PracticeConversation.progress()` 안에 이미 있어 새 클래스가 없다. 이번에 변형 · 다음 목표 경로까지 시험으로 고정했다.
7. 새 비밀 환경 변수 없음(R-M9 해당 없음). `.gitleaks.toml` 무변경. 비밀 스캔 `bash scripts/verify/a-secrets.sh`: 키 형식 9종 · 파일 3,312개 걸림 0(gitleaks 이력 스캔은 이 PC에 없어 CI 몫).

## 3. 시험 결과 (테스트 대역 · 합성 대화)

| 항목 | 결과 |
|---|---|
| 묶음 C `:realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker` | **1,846개** 통과, 실패 0 · 건너뜀 0 |
| 묶음 D `:realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker` | **1,862개**(클래스 205) 통과, 실패 0 · 건너뜀 0 (JaCoCo 모듈 하한 통과) |
| `:core-api:check -Pmujoong.test.db=docker`(묶음 C · D) | **1,722개** 통과, 실패 0 · 건너뜀 0 |
| `:contract-model:check` (deviations.md 변경 뒤) | 통과 |
| U5b 범위(`-PincludeTags=u5b -PcoverageScope=u5b`) | 통과(하한 80% 유지, 레거시 U5b 시험 무변경) |
| `u12` 범위 줄 커버리지(`-PincludeTags=u12 -PcoverageScope=u12`) | **96.2%**(1,222 / 1,270줄, 22개 운영 파일), 하한 80% 이상. 가장 낮은 파일 `GoalSpec` 70% · `PracticeFlowTypes` 91% · `PracticeInstructions` 93% · `PracticeConversation` 95% |
| 비밀 스캔 | 걸림 0 |
| 합성 대화 · 실제 API · 실제 기기 | 합성 대화(`PracticeFlowIT` 등)만 있다. 실제 API · 실제 제브 · 실제 기기는 없음(Build and Test · 사람 몫) |

`SessionExecutor` · `GuardrailCoordinator`는 `u12` 범위 파일에 넣지 않았다(범위 파일 머리말의 이유: 앞 유닛 코드가 대부분이라 u12 태그 시험만으로 재면 분모가 커진다). 그 변경은 `PracticeGeneratedSpeechTest` · `PracticeFlowIT`(u12 태그)가 실행하고, 파일 전체는 모듈 전체 80%로 본다.

## 4. 계획 대비 편차 · 메모

- **설계 편차 U12-D1 ~ D3**는 승인 설계 그대로다(`contracts/deviations.md` U12 편차, `docs/units/u12/design.md` 5절). 구현 중 새 설계 편차는 없다.
- `speakGenerated`가 만드는 슬롯의 재생 증거 표지(`OutputKind`)는 역할로만 정했다(코치 CUE, 친구 SCENARIO). 설계 3.5절은 발화별(도움 제안 HELP_QUESTION 등)로 적었으나 능력 인자에 종류가 없고 표지는 제브 요청 · 기록에 영향이 없다. 장면 id는 실행부가 대본 장면을 몰라 고정 값이다.
- `PracticeFlowIT`는 기기 시뮬레이터 · `verify.sh`를 쓰지 않고 `SessionHarness`(실행부) + 라우터 + 상태 기계 + 운영 기록기(실제 DB V7)로 한 장면을 닫는다(계획 열린 질문 6의 합의). 시뮬레이터 · `verify.sh` 단계 확장은 후속 과제다.
- U14 읽기 코드는 바꾸지 않았다. `record.practice`가 `practice-record-v2` 스키마를 통과함은 realtime 쪽 시험이 보이고, core-api의 기존 `PracticeRecordV2` 시험이 같은 모양(필수 4필드 · `liveOutcome` · `facts` · `variantSkipReason`)을 이미 받는다. 두 앱을 잇는 실제 확인은 Build and Test의 `verify.sh`에서 한다(R-F5).
- 커밋 작성자: 이 PC에 git 사용자 설정이 없어 앞 커밋과 같은 이름 · 메일을 `git -c`로만 지정했다(설정 파일은 바꾸지 않음).
- `team.md`의 낡은 마이그레이션 번호 알림(다음 번호 realtime V5)은 학습 절차로만 고친다(이번 단계에서 하지 않음). 다음 번호는 realtime V8이다.

## 5. 남은 위험

- 제브 연습 모드(`PRACTICE_PEER` · `PRACTICE_COACH`)가 **운영에서 처음 연결**됐다. 실제 제브의 응답 · 지연 · 분류 품질은 모른다(`jevLiveTest` 방식 확인은 사람 몫, R-F15 확인 기록이 먼저).
- 한국어 되묻기 · 코치 표현의 품질, 역할 전환 이해, J4 재사용 변형 동의 분류 품질은 `[실호출-합성]` · `[실기기]` 확인 대상이다.
- 기록 쓰기는 실행기 스레드에서 동기 JDBC다(호출마다 짧은 트랜잭션). DB가 느려지면 그만큼 세션 처리가 늦는다. 실패는 앱 오류 한 줄로 남기고 연습은 잇는다.
- 전체 `bash scripts/verify.sh`(기기 시뮬레이터 끝-끝)는 Build and Test에서 한 번 돈다. 연습 시작이 시뮬레이터 대본에 아직 없어 이 연습 흐름은 거기서 돌지 않는다(후속).
