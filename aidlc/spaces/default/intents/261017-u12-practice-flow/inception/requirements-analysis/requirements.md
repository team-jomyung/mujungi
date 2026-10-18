# U12 새 연습 흐름 — 요구사항 명세

> 작업 기록 `261017-u12-practice-flow` · Requirements Analysis(2.3) · 2026-10-18 · 범위 `refactor` · 깊이 Standard · 시험 전략 Standard
>
> 이 문서는 원래 기록 `261015-doc-sync-code-update`의 요구사항 · 스토리 · 계약 중 U12 몫을 구현 단위로 다시 묶은 것이다. 새 업무 요구사항은 만들지 않았다. 다만 FR1은 코드 스캔에서 드러난 **전제 조건**(realtime 시작 경로가 연습을 거절한다)을 구현 요구로 적은 것이며 원 FR에 대응이 없다. 출처가 서로 다르면 다음 순서로 따른다.
> 1. 화면 설계 v1.5j
> 2. 원래 기록의 승인 산출물: requirements · stories · contract-summary · components · decisions · unit-of-work · bolt-plan
> 3. 지시 `docs/units/u12/brief.md`, 계약 파일 `contracts/`, 앞 볼트 U8 · U10 · U11 · U13 · U14 설계와 편차(`contracts/deviations.md`)
> 4. 이 단계의 답(`requirements-analysis-questions.md` Q1 ~ Q5, 가정 확인, 요약 확인)
>
> 이 단계에서 사람이 명시적으로 고른 답은 위 출처와 다를 때 앞선다(project.md 교정). 실제로 Q1 답 A는 정본 AC1.1.2를 바꾼다(편차 `U12-D1`). 원래 기록의 요구사항 ID는 `원 FR4.1`처럼 "원"을 붙여 구분하고, 스토리 · 수용 기준 ID(US1.2 · AC1.2.3)는 원래 기록의 것을 그대로 쓴다. 이 문서의 `FR{n}` · `NFR{n}`은 이 작업 기록 안의 추적 키다. 코드 사실의 출처는 이 기록의 코드 스캔(`inception/reverse-engineering/developer-scan.md`)과 공유 CodeKB다.

## 의도 분석

- **목표:** 연습 세션이 레거시 고정 흐름(FIRST → HELP → CUE → NEW_SCENARIO)이 아니라 유연한 상태 기계로 진행된다. 아이가 되묻기로 해결하면 집중 연습을 강제하지 않고, 상황을 이해하지 못하면 교정 전에 다시 설명하고, 필요할 때만 코치 역할로 바꿔 도움을 준다. 도움 · 역할 전환 · 변형 · 생략 사유가 기록으로 남아 U14 결과 작업이 받는다.
- **사용자 가치:** 아이는 정해진 순서에 끌려가지 않고 자연스러운 대화로 연습한다(US1.1 ~ US1.6 · US1.9). 보호자는 어떤 도움이 언제 있었는지 기록으로 본다.
- **종류 · 범위:** 기존 realtime-activity 기능 확장과 레거시 분리(브라운필드). 새 연습 상태 기계 · 역할별 출력 정책 · realtime 시작 경로 · 기록(V7) · 설정 키. `SessionExecutor`(4,254줄)는 호출 지점만 더하고 상태는 협력 객체에 둔다.
- **복잡도:** 높음(볼트 크기 XL). 시작 경로, 출력 게이트, 제브 연습 분기, 승인 발화 재생, 판정 호출, 기록 · 삭제가 한꺼번에 얽힌다. 연습이 출력을 쥔 동안 새 발화가 버려지는 지금 구조와 `approvedSpeech`가 무음으로 끝나는 구조가 소리가 나는지를 정한다. 증거는 대부분 가짜 공급자 시험이며 GPT-Live 위의 품질은 실호출 · 실기기 몫이다.
- **한 묶음:** 이 단위는 한 PR로 낸다. 권장 코드 생성 묶음은 지시서에 있다(① V7 · 시작 경로 · 설정 ② 상태 기계 ③ 역할 정책 ④ 기록 · 진행 단계).

## 기능 요구사항

### FR1 연습 세션 시작 경로 (전제 · 원 FR4.1의 전제)

**FR1.1 realtime 시작 API가 연습을 받는다** — Must · 출처 코드 스캔 U12-R1, 가정 3
- 내부 시작 API(`POST /internal/sessions`)가 `activityType == PRACTICE`와 선택 본문 `practice`(계약 `internal-session-start-request.schema.json`에 이미 선언됨)를 받는다. `FREE_TALK` 시작은 지금과 같다.
- 완료 기준: 연습 시작 요청이 202로 받아들여지고 시작 처리기가 `practice` 원문을 보존한다. 거절을 단언하던 시험 둘(`RealtimeInternalApiIT` · `SessionInternalControllerU4bTest`)은 새 사실로만 바꾸고 자유대화 시작 단언은 그대로 통과한다.

**FR1.2 `practiceJson`이 확장에 닿는다** — Must · 출처 가정 3, 지시서 「첫 작업」 5
- 시작 처리기가 `practice`를 `Plan`에 실어 `PracticeContext.practiceJson`으로 넘긴다. 지금 `StartSessionHandler.plan`이 null을 넘기는 곳을 고친다.
- 완료 기준: 시작한 세션의 확장이 `onActive`에서 `practiceJson`을 비어 있지 않게 받는다(시험).

**FR1.3 U10 대본을 읽는다** — Must · 출처 원 FR4.1, 가정 4
- 병합된 대본 필드(`scriptVersion` · `scenes[]` · `selfReview` · `generation`)를 읽는 타입을 새로 만든다(레거시 `PracticeScenarioClient.Scenario`는 건드리지 않는다). 첫 목표는 `firstScenario`, 나머지 목표는 `POST /internal/practice/scenarios`(kind ORIGINAL)로 받는다. 변형 장면은 같은 응답의 VARIANT `scenes[]`를 쓴다. `sceneRunId`는 realtime이 만들고 `scenarioRef`에는 대본의 `scenarioId`를 쓴다.
- 완료 기준: 필수 6필드 · 선택 필드 누락(빈 배열 정규화) · `scriptVersion` 없음을 각각 시험한다. 계약은 바꾸지 않는다.

### FR2 연습 상태 기계 (원 FR4.1 ~ FR4.3 · FR4.5)

**FR2.1 단계와 전이** — Must · 출처 원 FR4.1, AC1.1.1 · AC1.1.3 · AC1.1.4 · AC1.2.1 ~ AC1.2.5
- 단계는 `SCENE_INTRO` → `OPPORTUNITY` → `REASK` → `HELP_OFFER` → `FOCUS` → `RETRY` → `SCENE_WRAP` → (`VARIANT`) → `NEXT_GOAL` / `CLOSING`이다. 도움 · 변형을 반드시 거치지 않는다. 시작 안내 뒤 참여 표현이거나 판정이 '참여'이면 `SCENE_INTRO`를 시작하고, 거부는 다른 선택지를 1회 제안하며 다시 거부하면 '시작 전 미참여'로 끝낸다. 무관 · 불명확이면 참여 의사를 다시 묻되 교정이나 압박은 하지 않는다.
- 완료 기준: 위 AC 각각이 가짜 공급자로 시험된다(전이표를 시험 입력으로 쓴다).

**FR2.2 시작 전 휴식** — Must · 출처 Q1(A), AC1.1.2의 개정 `U12-D1`
- 시작 전(참여 확인 중) 휴식 요청에는 받아 주는 말 없이 재안내한다(U11-D6 그대로). 거부로 보지 않는다. 정본 AC1.1.2의 `D_REST_ACK_START` 재생은 개정한다(참여 전 응답은 계약 (U4b-a)대로 들리지 않는다).
- 완료 기준: U11의 J1 휴식 단언이 바뀌지 않고 통과한다. 편차 표 `U12-D1`에 개정 사유가 적힌다.

**FR2.3 되묻기로 해결되면 집중 도움을 강제하지 않는다** — Must · 출처 원 FR4.2, AC1.2.3
- 기회 문장 뒤 '일부 전달'이면 친구 역할로 1회 되묻기(`REASK`)를 하고, 되묻기 뒤 충분하면 집중 도움 없이 정리하며 시점을 '되묻기 뒤'로 기록한다. '여전히 부족'이면 `HELP_OFFER`로 가되 도움을 강제하지 않는다.
- 완료 기준: "그거 줘" → "어느 색?" → "파란색" 시나리오가 집중 도움 없이 `SCENE_WRAP`으로 끝나고 기록 시점이 `AFTER_REASK`이다(가짜 공급자, AC1.2.3).

**FR2.4 상황 이해 못함** — Must · 출처 원 FR4.3, AC1.3.1 · AC1.3.2
- '상황 이해 못함' 또는 '무관 발화'이면 대본의 '상황 다시 설명'을 먼저 들려주고 기회 문장으로 돌아간다. 교정이나 예시를 먼저 주지 않는다. 다시 설명은 [기본값] 1회뿐이고 또 이해 못하면 도움 제안으로 간다.
- 완료 기준: 1회 · 2회째 분기를 각각 시험한다. 횟수는 설정 키(FR7)에서 읽는다.

**FR2.5 무응답 재안내** — Must · 출처 원 FR4.3, AC1.9.1 ~ AC1.9.3
- 기회 문장이나 되묻기 재생 뒤 [기본값] 10초 무발화이면 1회 재안내하고, 10초 더 없으면 그 장면을 '미실시'로 한다. 기술적 일시 정지 중에는 재안내하지 않는다.
- 완료 기준: 수동 시계로 경계(9초 · 10초)와 일시 정지 중 비재안내를 시험한다. 시간 값은 `mujoong.timing.conversation.no-response-wait`에서만 읽는다(R-F7).

**FR2.6 도움 요청 · 수락 · 거절** — Must · 출처 AC1.4.1 ~ AC1.4.4
- 어느 판정 지점에서든 '도움 요청'이면 바로 코치 전환(`FOCUS`)한다. 도움 제안 뒤 '수락'이면 `FOCUS`, '거절'이면 원래 장면을 잇거나 정리하고, '불명확' · 보류이면 집중 연습을 시작하지 않고 교정 없이 이어 간다. 도움을 받지 않고 끝난 장면은 '미완료'로 표시하지 않는다.
- 완료 기준: 네 분기를 각각 시험한다.

**FR2.7 변형 · 생략 · 시간 한도** — Must · 출처 원 FR4.5, AC1.5.4 · AC1.6.1 ~ AC1.6.4
- `SCENE_WRAP`에서 4조건(쓸 수 있는 변형 · 시간 · 참여 의사 · 목표당 ACTIVE 10분 전)이 모두 맞을 때만 변형을 제안한다. 하나라도 틀리면 변형 없이 다음으로 가고 생략 사유(`NO_VARIANT` · `DECLINED` · `TIME_LIMIT` · `FOCUS_LIMIT` · `END_OR_REST`)를 기록한다. 변형 거절도 기록한다. 다시 해 보기 [기본값] 2회이거나 집중 90초를 넘으면 장면을 정리하고 변형을 제안하지 않는다. 목표 ACTIVE 10분이 되면 어느 단계에서든 변형 없이 그 목표를 정리한다.
- 완료 기준: 4조건 각각의 거짓 · 10분 경계 · 90초 경계 · 2회 경계를 시험한다.

**FR2.8 판정 호출과 지시** — Must · 출처 원 FR4.1(판정 지점 J1 ~ J5), 가정 6, AC1.2.5
- J2 ~ J5는 확장이 `requestJudgment`로 요청하고, 결과 사건(`JUDGMENT_RESULT` · `JUDGMENT_ABSTAINED` · `HELD_OUTPUT_TRANSCRIPT`)을 상태 전이로 연결한다. 판정 기준 `coreFunction`은 목표 정보에서 만든다. 다음 행동 지시문에는 판정 라벨 · 확률 · 기준이 들어가지 않는다. 지시는 `session.instructions.append` 하나로 보낸다(U11-D9).
- 완료 기준: 지시문 본문에 라벨 · 확률이 없음을 시험하고, `requestJudgment`의 거절(`false`) 경로에서 연습이 멈추지 않는다.

### FR3 역할별 출력 정책과 역할 전환 (원 FR4.4)

**FR3.1 코치 전환 · 복귀** — Must · 출처 원 FR4.4, AC1.5.1 ~ AC1.5.3
- `FOCUS`의 첫 코치 발화에 역할 전환 알림이 있다. 코치는 동의 뒤 표현 예시를 줄 수 있고 친구 역할은 정답을 미리 말하지 않는다(역할별로 따로 검증). `RETRY` 뒤 판정이 복귀 조건이면 복귀 알림 뒤 원래 장면을 잇거나 정리한다. `CueProvider`의 전역 금지는 역할별 정책으로 나눈다.
- 완료 기준: 역할별 정책을 각각 시험하고 친구 역할 출력에 정답 선공개가 없음을 단언한다.

**FR3.2 승인 발화가 확장에 닿는다** — Must · 출처 가정 5, 코드 스캔 U12-R3
- `approvedSpeech`(`COACH_SWITCH` · `PEER_RETURN`) 텍스트가 확장에 닿는 길을 만들고 `announceRoleSwitch`를 구현해 `true`를 돌려준다. 음성 파일이 없는 승인 발화가 무음으로 끝나는 지금 동작(`playApproved`)은 새 흐름에서 쓰지 않는다.
- 완료 기준: 역할 전환 알림이 재생 요청으로 나가고, 재생 요청이 `ACTIVE`가 아니면 거절된다(`PRACTICE_EXTENSION_FAILED`).

**FR3.3 연습 통제 아래 새 발화 · 제브 분기** — Must · 출처 Q2(A), AC1.2.2 · AC1.5.6, 코드 스캔 U12-R2
- 새 확장(`PracticeConversation`)이 출력을 쥐는 동안에는 폐기 규칙을 바꾼다. 확장이 허용한 역할 발화(친구 되묻기 · 코치)는 제브 `PRACTICE_PEER` / `PRACTICE_COACH`로 검사해 `ALLOW`이면 재생하고, 막히면 폐기 · 재지시 1회(U13)로 가며 두 번째 막힘은 `GUARDRAIL_BLOCKED`로 확장에 전달된다. 레거시 확장의 폐기 규칙과 U5b 시험 단언은 그대로 둔다.
- 역할 전환 · 복귀 알림을 제브가 막으면 알림 없이 역할을 바꾸지 않는다: 1회 다시 지시 후 장면을 정리한다(AC1.5.6).
- 완료 기준: 새 확장 아래 허용된 역할 발화가 제브 `ALLOW` 뒤 재생되고 `BLOCK`이면 0바이트다. 레거시 확장 아래 폐기 단언 시험이 단언 변경 없이 통과한다. 승인되지 않은 출력 · 연습 중 제브 우회 송신 경로는 없다.

### FR4 기록과 V7 (원 FR4.7 · FR7.3)

**FR4.1 realtime V7 · 삭제 · 복구** — Must · 출처 원 FR4.7, 가정 8, Domain Design R-02
- `V7__u12_practice_flow.sql`로 새 표 두 개(장면 실행 · 도움 · 역할 전환 기록)를 만든다. V4 레거시 표 제약은 건드리지 않는다. 새 표도 보호자 삭제에 참여하고(세션 200 앞) `DeletionCompleted.Counts`는 바꾸지 않는다. 정산 복구는 새 표에서도 `practice` 값을 다시 만들 수 있다.
- 완료 기준: 표 · 제약 · 색인 시험, 삭제 순서 시험, 복구 시험이 통과하고 `FlywayConfIT` · `SchemaPrivilegeIT`(core-api) · `RealtimeSchemaIT` · `RealtimeDb`가 V7 사실로만 갱신되어 `:core-api:check`가 통과한다.

**FR4.2 기록 사실** — Must · 출처 Q3(A), 원 FR7.3, 가정 9
- 역할(친구/코치)과 도움(종류 · 예시 여부 · 동의 응답 · 시각)을 V7 표에 남긴다. 도움 내용은 **원문이 아니라 분류 코드와 길이만** 저장한다(R-F14). 이벤트 `record.practice`는 현행 형식(`flowVersion = "practice-conversation-v1"`, `scenes[]` 필수 4필드, `facts` 계약 15종, `liveOutcome.timing`, `variantSkipReason`)으로 채운다. `goalRef`는 보호자 `goalSelectionId`다.
- 완료 기준: 이벤트 본문이 `practice-record-v2.schema.json`을 통과하고 U14 쪽 `PracticeRecordV2` 읽기 시험이 새 형식을 받는다(U14 코드 변경 없음). 계약 · 스키마 변경 없음.

**FR4.3 장면 노출 이력** — Must · 출처 원 FR4.7
- 장면 노출 이력은 장면을 실제로 시작할 때 남긴다.
- 완료 기준: 시작 전 미참여 · 미실시 장면에는 노출 이력이 남지 않는다.

### FR5 레거시 분리 · 분기 (원 FR4.6)

**FR5.1 새 흐름은 레거시 포트에 기대지 않는다** — Must · 출처 원 FR4.6, AC1.1.5
- 새 흐름은 `EvaluationProvider` · `ResponseEvaluator` · `CueProvider`가 연결되지 않아도 시작부터 마무리까지 멈추지 않는다.
- 완료 기준: 레거시 기본 빈이 실패를 돌려주는 구성에서 새 흐름 한 세션이 끝까지 간다(시험).

**FR5.2 분기 기준** — Must · 출처 Q5(A), 가정 3
- 대본의 `scriptVersion`(`scenario-script.v1`)이 있으면 새 흐름, 없으면 레거시 런타임으로 `onActive`에서 자동 분기한다. 지금 realtime이 연습 시작을 거절하므로 레거시도 운영에서 돈 적이 없고, 레거시 기본 포트는 실패를 돌려주므로 `scriptVersion`이 없는 세션은 사실상 멈춘다. 이 사실을 문서(`design.md` · `report.md`)에 적는다. `Factory.create`가 `practiceJson`을 받지 않는 구조에 맞는 방법은 Functional Design이 정한다.
- 완료 기준: `scriptVersion` 있음 · 없음 두 분기 시험.

**FR5.3 레거시 보존** — Must · 출처 원 FR4.6, ADR-001, R-F2
- 레거시 클래스 · V4 표 5개 · 삭제 참여자 · 복구 경로는 삭제하지 않는다. `u5b-scope` 줄 80% 하한과 `PracticeRuntimeConfigurationTest`의 기본 빈 실패 단언을 낮추거나 끄지 않는다.
- 완료 기준: U5b 시험이 단언 변경 없이 통과하고 `u5b` 범위 줄 80% 이상이다.

### FR6 진행 단계 표시 (Q4)

**FR6.1 6값으로 접어 표시** — Must · 출처 Q4(A), 코드 스캔 U12-R5
- 새 단계를 기존 `Progress.stage` 6값(`SCENARIO_INTRO` · `FIRST_RESPONSE` · `HELP_OFFER` · `CUE` · `AFTER_CUE_RESPONSE` · `NEW_SCENARIO_RESPONSE`)으로 `progress()` 매핑에서만 접는다. 저장 값 · 이벤트 · 기록은 새 단계를 사실대로 둔다. 계약 · core · 프런트는 바뀌지 않는다.
- 완료 기준: 모든 새 단계가 6값 중 하나로 매핑됨을 시험하고 12 화면이 "현재 연습 단계를 확인할 수 없어요."를 보이지 않는다(프런트 시험 변경 없음).

### FR7 설정 키

**FR7.1 시간 키 읽기 · 비시간 키 신설** — Must · 출처 가정 7, 코드 스캔 U12-R6, R-M10
- `mujoong.timing.conversation.*`(무응답 10초 · 집중 90초 · 목표 ACTIVE 10분)를 운영 코드가 읽는다. 다시 해 보기 2회 · 상황 다시 설명 1회는 시간 값이 아닌 새 설정 키로 두고 `contracts/ai-io.md` 표에 더하며 `ConfigKeyContractTest` 레코드 모음에 등록한다.
- 완료 기준: 키 대조 시험이 통과하고 코드에 숫자 시간 값이 없다(`TimeLiteralScanTest`).

### FR8 시험 · 문서 · 계약 정비

**FR8.1 시험 정비** — Must · 출처 가정 12
- 태그 `u12`, 범위 파일 `backend/coverage-scope/u12.txt`(줄 80%), 연습을 지나는 끝-끝의 최소 범위(가짜 공급자 · 기기 시뮬레이터에 연습 시작과 되묻기 대본, AC1.2.3 시연)는 Code Generation 계획이 정한다.
- 완료 기준: 계획 단계에서 정한다(요구 자체는 작업 방식 요구라 BR 없이 추적).

**FR8.2 문서 · 계약 정비** — Must · 출처 R-M10
- `docs/units/u12/design.md` · `report.md`, `docs/units/README.md`, 설정 키의 `contracts/ai-io.md` 표와 `deviations.md`(`U12-D1` 등), 낡은 `team.md` 마이그레이션 번호 알림을 적는다. 원 기록의 승인 문서는 고치지 않는다.
- 완료 기준: 계약 대조 시험이 통과한다.

## 비기능 요구사항

| ID | 요구 | 판정 방법 | 출처 |
|---|---|---|---|
| NFR1 | **안전 경로 유지**: 연습에서도 기기로 나가는 GPT-Live 새 발화는 제브 `ALLOW` 뒤뿐이다(승인 원문 일치 제외). 우회 송신 · 자동 통과 없음 | FR3.3 시험, 제브 호출 0건 규칙(승인 원문), 기존 U13 시험 | 원 NFR2 · U13 |
| NFR2 | **개인정보 최소화**: 도움 내용 원문 · 전사 · AI 요청 원문을 로그 · 기록 · 지표에 남기지 않는다. 식별자 없는 분류 코드 · 길이 · 시각만 | 기록 표 칸 시험, 로그 위생(P2), R-F14 | 원 NFR3 · R-F14 |
| NFR3 | **시간 값은 설정으로**: 모든 연습 시간 값은 `mujoong.timing.conversation.*`에서 읽는다 | `TimeLiteralScanTest` · `ConfigKeyContractTest` | R-F7 · R-M5 |
| NFR4 | **회귀 없음**: U1 ~ U13 시험이 단언 변경 없이 통과한다. 바꾸는 기존 단언은 시작 거절 단언 둘, V7 사실(표 목록 · 이력 · 삭제 순서)뿐이며 느슨해지지 않는다 | `:realtime-activity:check` · `:core-api:check` · `verify.sh` | 원 NFR6 · R-F2 |
| NFR5 | **시험 · 하한**: test-after, Standard 전략, 모듈 줄 80% · `u12` 범위 줄 80%, U5b 범위 하한 유지. 시험 · CI · 검증은 가짜 공급자만 쓴다 | `check` · 범위 측정 · `verify.sh` PASS | 원 NFR5 · R-F1 |
| NFR6 | **구조 경계**: 새 클래스는 레거시 `practice/`와 섞이지 않는 새 패키지에 두고 실행부와는 `Env` · `PracticeOutput` 능력으로만 닿는다. 금지 능력을 ArchUnit으로 강제한다. 실행부에는 호출 지점만 더한다 | ArchUnit 패키지 규칙, `SessionExecutor` diff 크기 | 원 NFR8 · ADR-005 |
| NFR7 | **증거 구분**: 결과는 테스트 대역 · 합성 대화 · 실제 API · 실제 기기로 나눠 보고하고 대역 성공을 실제 성공으로 적지 않는다. 한국어 되묻기 품질 · 코치 표현 · 역할 전환 이해는 사람 몫 | 보고서 증거 표 | 원 NFR9 · R-M11 |
| NFR8 | **운영 격리**: 사용자의 수동 스택 `mujoong-manual`을 건드리지 않고 검증은 `mujoong-verify`로 한다 | 검증 명령의 Compose 프로젝트명 | R-F12 |

## 제약

- 시험 · CI · 검증 명령에서 실제 유료 AI 공급자를 부르지 않는다(R-F1). 키 · 토큰 값 · 실제 서비스 도메인을 코드 · 설정 · 시험 · 문서 · 커밋 · 로그에 쓰지 않는다(R-F3). 비밀 스캔 패턴과 겹치는 모양의 리터럴을 문서에도 그대로 적지 않는다.
- `core-api`와 `realtime-activity`는 서로 코드를 참조하지 않는다(R-F5). 운영 코드는 시스템 시각을 직접 읽지 않는다(R-F6).
- 마이그레이션은 realtime V7이다(V6은 U13). 이미 `main`에 있는 마이그레이션 파일은 바꾸지 않는다(R-M2). 마이그레이션을 더하므로 단계마다 `:realtime-activity:check`와 `:core-api:check`를 함께 돌린다.
- 새 비밀 환경 변수는 없다. 새로 만들면 R-M9 목록 전부에 같은 커밋에서 넣는다.
- 실제 아동 데이터의 외부 전송 결정은 R-F15 확인 기록(`infra/RUNBOOK.md` 단계 4, 지금 비어 있다) 뒤다. 이 단위가 제브 연습 모드 호출을 처음 운영에 연결하므로 R-F15 전에는 실제 키를 넣지 않는다.
- 단계 산출물은 파일 쓰기 도구로 저장한다(project.md 교정). `verify.sh`가 도는 동안에는 저장소 안에 파일을 쓰지 않는다.

## Assumptions & Open Questions

### 가정

| 번호 | 가정 | 근거 |
|---|---|---|
| A1 | ID 다시 묶기: 새 FR · NFR 번호를 쓰고 원 ID는 '원 FR4.1'처럼 적는다. 스토리 · AC는 원 ID를 그대로 쓴다. 원 승인 문서는 고치지 않고 편차 표(`U12-Dn`)에 적는다 | 가정 확인 A, project.md 교정 |
| A2 | 범위는 지시서의 4가지. J1 · J6(U11), 제브(U13), 상황 생성(U10), 결과 조립(U14), 실호출 · 실기기는 하지 않는다. AC 31개는 모두 `[자동-대역]` | 가정 확인 A |
| A3 | realtime 시작 경로를 연다(`PRACTICE` + 선택 본문 `practice`, 거절 단언 시험 둘은 새 사실로만 변경) | 가정 확인 A, 코드 스캔 U12-R1 |
| A4 | U10 대본 소비 타입을 새로 만든다. 첫 목표 `firstScenario`, 나머지는 내부 HTTP, 변형은 같은 응답의 VARIANT. `sceneRunId`는 realtime이 만든다 | 가정 확인 A |
| A5 | `approvedSpeech`가 확장에 닿게 하고 `announceRoleSwitch`를 구현한다. 제브가 막으면 역할을 바꾸지 않는다 | 가정 확인 A |
| A6 | J2 ~ J5는 확장이 `requestJudgment`로 요청. `coreFunction`은 목표 정보에서 만든다. 지시는 `append` 하나, 라벨 · 확률 없음 | 가정 확인 A, U11-D9 |
| A7 | 시간 키는 `conversation.*`를 이제 읽는다. 비시간 키(다시 해 보기 2회 · 재설명 1회)는 새로 만든다 | 가정 확인 A |
| A8 | V7 새 표 두 개, 삭제 참여자는 200 앞, `DeletionCompleted.Counts` 불변, 복구 경로 포함 | 가정 확인 A |
| A9 | 기록 이벤트는 `flowVersion = "practice-conversation-v1"`과 필수 4필드, `facts` 15종만 쓴다. `goalRef`는 `goalSelectionId` | 가정 확인 A, Q3(A) |
| A10 | 레거시는 삭제하지 않고 `u5b-scope` 하한 · 기본 빈 실패 단언을 유지한다 | 가정 확인 A |
| A11 | 새 패키지 + ArchUnit, 실행부에는 호출 지점만 더한다 | 가정 확인 A, ADR-005 |
| A12 | test-after, 태그 `u12`, 범위 파일 `u12.txt`, `:core-api:check` 병행, 연습 끝-끝 최소 범위는 계획에서 정한다 | 가정 확인 A |
| A13 | 증거는 4종으로 나눠 적고 대역 성공을 실제 성공으로 표시하지 않는다 | 가정 확인 A, R-M11 |
| A14 | 브랜치 `unit/u12-practice-flow`, PR은 merge commit, `aidlc/` 기록 함께 커밋, 배포 단계는 뺐다 | 가정 확인 A |

### 열린 질문

1. **J2 ~ J5가 열려 있는 동안 연습 시계:** 지금은 J6만 일시정지 조건이다(`practiceProgressPaused`). J2 ~ J5 판정 중에도 무응답 · 집중 시계가 흐르는 것이 의도인지 Functional Design에서 정한다(코드 스캔 사실, 의도 미확인).
2. **`coreFunction`의 출처 필드:** 시작 패키지 목표에는 `judgmentCriteria` 문자열 하나뿐이다. 표시 이름 · 목표 표현 중 어느 것으로 만들지 Functional Design에서 정한다.
3. **후보 목표 필드(`goalKind` · `candidateRef` · `candidateVersion` · `goalContext`)를 realtime이 읽을 범위:** 지금 버려진다. 읽을지 말지와 읽는 필드를 Functional Design에서 정한다.
4. **`Factory` 시그니처 · 분기 위치:** `Factory.create(sessionId)`가 `practiceJson`을 받지 않으므로 `onActive` 분기 또는 시그니처 확장 중 정한다(시험 약 10개와 `RealtimeRuntimeConfig`가 영향).
5. **`judgment_live.goal_ref` · `scene_ref` 칸:** 지금 항상 null이다. `requestJudgment`에 목표 · 장면 참조를 넣을지 정한다(능력 시그니처 확장).
6. **연습 끝-끝 최소 범위:** 가짜 공급자 · 시뮬레이터에 더할 범위와 `verify.sh` 단계 추가 여부를 Code Generation 계획에서 정한다.
7. **한국어 되묻기 · 코치 표현 품질, 역할 전환을 아이가 알아듣는지:** 실호출 · 실기기로만 안다(`[실호출-합성]` · `[실기기]`, 사람 몫).
8. **`SessionExecutor` 읽지 않은 약 2,000줄 · `OutputGeneration`:** 연습 지시 발화 설계 전에 Functional Design에서 읽는다(코드 스캔이 깊게 읽지 않은 구간).
9. **`team.md`의 낡은 번호("다음 번호는 core V8 · realtime V5")**: 실제 realtime 다음은 V7이다. 기억 파일은 학습 절차로만 고친다.

## 범위 밖

- J1 · J6 판정 · 판정 턴 보류 · 출력 세대(U11), 제브 단계 자체(U13), 상황 생성 · 대본 계약(U10), 결과 조립 · 재판정(U14), 지식 그래프(U15)
- 레거시 연습 클래스 · V4 표 · 삭제 참여자 · 복구 경로의 삭제(ADR-001)
- `practice-record-v2` · `live-view` 계약 · U14 코드 · 프런트 변경(Q3 · Q4 답 A)
- 제안 B · C의 선택 필드 추가(Q3), `stage` enum 확장(Q4)
- GPT-Live · 제브 실호출과 실제 기기 확인, 한국어 품질 확정, 제브 문턱 확정(사람 몫)
- 배포 파이프라인 · 운영 배포(이 작업 기록에서 뺐다), 통합 점검(별도 작업 기록)

## Sources

- 이 단계의 질문과 답: `requirements-analysis-questions.md` Q1(A) · Q2(A) · Q3(A) · Q4(A) · Q5(A), 가정 확인 A. Accept assumptions, 요약 확인 Looks correct
- 초기 설명(project-description.json): "U12 새 연습 흐름(u12-practice-flow) 구현. 지시 docs/units/u12/brief.md를 정본으로 한다. 인셉션 산출물은 261015-doc-sync-code-update 기록의 것을 그대로 쓴다."
- 지시: `docs/units/u12/brief.md`(범위 · 정본 · 정해 둔 값 · 맞물리는 곳 · 사람이 정할 것 · 진행 방법)
- 원래 기록 `aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/`
  - `requirements-analysis/requirements.md`: 원 FR4.1 ~ FR4.8 · 원 FR7.3 · 원 NFR
  - `user-stories/stories.md`: US1.1 ~ US1.6 · US1.9(AC1.1.1 ~ AC1.9.3), '의존과 순서'
  - `units-generation/unit-of-work.md` U12, `delivery-planning/bolt-plan.md` B8 · `risk-and-sequencing-rationale.md`
  - `domain-design/components.md`(PracticeConversation) · `decisions.md`(ADR-001 · ADR-004 · ADR-005 · ADR-007), `contract-design/contract-summary.md`(C-DS1 · 기록 캡처 이벤트 · 받아들인 위험), `refined-mockups/mockups.md`
- 코드 사실: 이 기록의 `inception/reverse-engineering/developer-scan.md`(Handoff Summary 1 ~ 9, 위험 요약)와 공유 CodeKB `aidlc/spaces/default/codekb/mujungi/` 9개 파일(U12-R1 ~ U12-R8)
- 계약 파일: `contracts/schemas/{practice-scenario, practice-record-v2, live-view, internal-session-start-request, internal-practice-scenario-request}.schema.json`, `contracts/deviations.md`(U8-d · U10-a · U11 · U13), `contracts/ai-io.md`, `contracts/timing.md`
- 앞 볼트: `docs/units/u10 · u11 · u13 · u14`(design · report · handoff · brief)
- 팀 규칙: `aidlc/spaces/default/memory/team.md` · `project.md`(R-F1 · R-F2 · R-F3 · R-F5 · R-F6 · R-F7 · R-F12 · R-F14 · R-F15 · R-M2 · R-M5 · R-M9 · R-M10 · R-M11, 교정)
