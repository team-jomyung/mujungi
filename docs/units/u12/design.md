# U12 새 연습 흐름 — 기능 설계 (구현된 모습)

> 작업 기록 `261017-u12-practice-flow`의 승인된 기능 설계(`construction/functional-design/{functional-spec,rules,entities}.md`)에 Code Generation에서 정한 점을 합쳐 **코드에 들어간 모습**으로 옮긴 문서다. 승인 문서와 다르면 이 문서는 구현을 따른다. 흐름의 정본은 승인 문서이고 이 문서는 파생 보기다.
> 번호 체계: **설계 편차 `U12-D<n>`**(이 문서), **계약 문서에 더한 키 `(U12-a)`**(`contracts/deviations.md`). 규칙 번호 `BR`은 승인 `rules.md`의 것이다.
> 증거 구분(R-M11): 이 단위의 모든 합격은 **테스트 대역**(가짜 제브 · 가짜 공급자 · 가짜 판정)과 **합성 대화**다. 실제 AI API · 실제 제브 · 실제 기기에서 한 번도 돌지 않았다.

## 1. 범위

연습(PRACTICE) 세션을 새 연습 흐름으로 진행한다: 시작 요청이 연습 대본을 받고 → 참여 확인(실행부) 뒤 도입 → 기회 문장 → 판정(J2 ~ J5) → 되묻기 · 도움 · 변형 → 정리 → 정산(`record.practice`)까지. 친구(PEER) · 코치(COACH) 역할 발화는 GPT-Live가 그 자리에서 만들고 **제브(연습 모드)가 `ALLOW`한 것만** 재생된다.

하지 않는 것: 프런트 · core-api 코드 변경(12 화면은 진행 6값으로 그대로 돈다), 기기 시뮬레이터 · `verify.sh` 단계 확장(후속), 레거시 연습 코드 · V4 표 삭제(BR10.1), 제브 실호출.

## 2. 구성 요소

| 구성 요소 | 위치 | 책임 |
|---|---|---|
| `PracticeFlowRouter` | `practiceflow/` | Factory가 세션마다 만드는 얇은 확장. `onActive`에서 대본 `scriptVersion`을 보고 새 흐름 또는 레거시 한 쪽에 위임한다. `controlsOutput()`은 분기 전에도 참이다(D1) |
| `PracticeConversation` | `practiceflow/` | 상태 기계. 확장점 시점 3개와 출력 능력으로만 실행부와 닿는다. 시계는 모두 확장점 시계(`schedule`) |
| `FlowTransitions` · `FlowState` | `practiceflow/` | 판정 지점 + 선택지 코드 기준 순수 전이표(J2 6 · J3 4 · J4 3 · J5 7 코드 전부)와 흐름 상태(시계 만료 표지 · 처리한 슬롯 · 열린 판정) |
| `RolePolicy` · `PracticeInstructions` | `practiceflow/` | 역할별 정책(친구는 정답 선공개 없음, 코치는 동의 뒤에만 예시)과 지시문 템플릿. 판정 라벨 · 확률 · 기준 · 아이 말은 지시문에 넣지 않는다 |
| `PracticeScript` · `PracticeScriptReader` · `PracticeStartPackage` · `GoalSpec` · `PracticeScriptRequester` | `practiceflow/` | U10 대본 읽기(필수 6필드, 선택 필드 정규화), 시작 패키지 해석, 다음 목표 대본의 비동기 요청(`POST /internal/practice/scenarios`, 안쪽 재시도 없음) |
| `FlowRecorder`(포트) · `PracticeFlowRecorder` | `practiceflow/` | 장면 · 사실 · 도움을 V7 표에 쓰고 정산 값을 같은 값에서 조립한다. 값은 먼저 메모리에 반영하고 표에 쓰므로 표 쓰기가 실패해도(앱 오류 한 줄) 정산 값은 비지 않는다 |
| `PracticeFlowRepository` · `PracticeFlowRecordAssembler` · `PracticeFlowRecordRecovery` · `PracticeFlowDeletionParticipant` | `practiceflow/` | JDBC 저장소, `record.practice` 순수 조립기, 정산 복구(V7 표 → 같은 값), 보호자 삭제 참여자(순서 190) |
| `PracticeFlowSettings` · `PracticeFlowConfiguration` | `practiceflow/` | 시계 · 횟수 설정 값(`mujoong.timing.conversation.*` + `mujoong.practice-flow.*`) |
| V7 `realtime_practice_scene_run` · `realtime_practice_help` | `db/migration/realtime/` | 새 표 두 개(expand 전용). 사실은 계약 15종 배열 + 발생 시각 배열, 원문 칸 없음 |
| `PracticeSpeech.generated` · `speakGenerated` · `announceRoleSwitch` | `voice/` · `session/` | 3절 |

`SessionExecutor`에는 호출 지점과 얇은 분기만 더했다(`speakGenerated` · `announceRoleSwitch` 구현, `sealAndGate` 생성형 슬롯 분기, 슬롯 닫기 도우미 하나). 실행부(`session`)는 `practiceflow`의 클래스를 모른다 — 확장점 인터페이스 한 방향이다(`PracticeFlowArchTest`).

## 3. 출력 · 슬롯 · 제브 (BR5.4 ~ BR5.7)

- **생성형 슬롯**: `PracticeSpeech`에 `generated` 성분을 더했다(기존 6인자 생성자 유지). 생성형이면 `partnerRole`은 `PEER`/`COACH`, `text`는 서버가 만든 지시문 템플릿 문장이고 `matches()`는 언제나 거짓이다 — 원문 일치로 제브를 건너뛸 수 없다.
- **`speakGenerated(correlationId, role, 지시문)` 하나**로 GPT-Live 역할 발화를 낸다: 슬롯 열기 → 연습 출력 보류 해제 → 출력 세대 올림 → 지시를 `replayable=true`로 송신 → 송신 실패 · 새 말 미시작이면 기존 `u5b-voice-failed` 길. 발화마다 제브 재지시 수를 새로 센다(`onTurnBoundary`). `instructProvider`는 새 흐름이 부르지 않는다.
- **`sealAndGate`**: 열린 슬롯이 생성형이면 제브를 `PRACTICE_PEER`/`PRACTICE_COACH`로 불러 `ALLOW`일 때만 재생한다. 제브가 꺼져 있거나 시작이 실패하면 폐기한다(fail-closed). 승인 발화 슬롯(`generated=false`)의 기존 길은 바뀌지 않았다(U5b 시험 무변경).
- **첫 막힘**은 같은 역할 지시문을 다시 보내 한 번 다시 검사한다. `discardForRetry`는 슬롯을 닫지 않으므로(코드로 확인, FA3) 재지시 동안 슬롯이 열려 있다. **두 번째 막힘**은 `GUARDRAIL_BLOCKED` 사건 다음 FAILED 사실이다.
- **슬롯 종료 사실은 정확히 한 번**: 재생 완료/끊김/실패(기존), 폐기(FAILED), 검사 중 아이가 말함(INTERRUPTED), 그리고 이번에 메운 두 길 — 무효로 닫힌 검사(`Env.practiceVoided` → INTERRUPTED)와 재지시가 소리로 이어지지 않은 경우(`finishAbandoned` → FAILED). 확장은 correlationId마다 첫 사건만 처리한다.
- **`announceRoleSwitch(toRole)`**: 시작 패키지 `approvedSpeech`의 `COACH_SWITCH`/`PEER_RETURN` 문장이 있으면 기존 승인 발화 `speak` 길로 낸다(원문 일치라 제브 호출 없음). 없으면 거짓이고 확장이 생성형 알림으로 낸다.
- 재생 증거 표지(`OutputKind` · 장면 id)는 실행부가 대본 장면을 모르므로 역할로만 정한다(코치 CUE, 친구 SCENARIO, 장면 id는 고정 값). 제브 요청 · 기록에 영향이 없다.

## 4. 기록 · 진행 표시 (BR7 · BR8)

- 장면 행은 도입 재생이 성공한 뒤에만 만든다(도입 실패 · 시작 전 미참여는 행 없음). 사실 15종, 장면 결과(끝 시각 · 진행 중 판정 시점 · 변형 생략 사유 · 횟수), 도움 기록(종류 · 역할 · 예시 여부 · 동의 응답 · 길이)이 남는다.
- `record.practice`는 `flowVersion=practice-conversation-v1`, `scenes[]` 필수 4필드, `liveOutcome.timing`, `variantSkipReason`을 가지며 `practice-record-v2` 스키마를 통과한다(U14 읽기 코드 무변경). 판정 라벨 · 확률 · 전사 · 도움 내용은 어디에도 없다 — 값 타입에 문자열 칸이 없고 표식 문자열 시험이 로그 · 기록 · 이벤트 · 지시문을 훑는다.
- 진행 표시는 새 단계를 6값(SCENARIO_INTRO · FIRST_RESPONSE · HELP_OFFER · CUE · AFTER_CUE_RESPONSE · NEW_SCENARIO_RESPONSE)으로 접어 보낸다. 접기는 표시에만 있고 저장 값은 새 단계를 사실대로 둔다.

## 5. 편차

| ID | 대상 | 정본 | 이 구현 | 까닭 |
|---|---|---|---|---|
| U12-D1 | AC1.1.2 | 시작 전 휴식에 `D_REST_ACK_START` 재생 | 받아 주는 말 없이 재안내(U11-D6, 실행부 소유, 변경 없음) | 참여 전 응답은 계약 (U4b-a)상 들리지 않는다 |
| U12-D2 | AC1.5.5 | 도움 내용 기록 | 도움 종류 · 예시 여부 · 동의 응답 · 시각 · 내용 **길이**만 저장 | R-F14, RA 검토 R-02 |
| U12-D3 | AC1.5.4 | 변형 제안 동의 판정 지점 미정 | J4(수락 · 거절 · 불명확) 재사용 | 새 판정 지점은 계약을 바꾼다. 분류 품질은 실호출 확인 대상 |
| U12-D4 | functional-spec §3.3 OPPORTUNITY(J2) 보류 행 | 보류는 재안내 1회, 두 번째면 장면 정리(ABSTAINED) | 확신 · 차이 문턱 미달 보류(LOW_CONFIDENCE · LOW_MARGIN)는 확정 안 된 1순위가 HELP이면 곧바로, 아니면 재안내 뒤 두 번째에 HELP_OFFER(J4 동의 확인)로 간다. 판정 쪽 실패(시간 초과 · 장애 · 거절)와 다른 단계는 그대로 | 실제 판정 모의에서 머뭇거리는 아이("음, 잘 모르겠어.")가 1순위 HELP 0.48 ~ 0.52 보류 두 번으로 도움 제안 없이 끝났다(docs/practice-live-fixes.md LIVE-FIX-A5 ~ A7) |

## 6. 레거시 사실 기록

- 레거시 연습 확장(`RuntimePracticeExtension`)의 기본 포트(`EvaluationProvider` · `CueProvider`)는 실패를 돌려주므로 **운영에서 연습이 실제로 돈 적이 없다**(BR1.4). `scriptVersion`이 없는 세션은 레거시가 받아 이전과 같은 방식으로 멈춘다. 새 흐름은 이 포트를 호출하지 않는다.
- 레거시 클래스 · V4 표 5개 · 삭제 참여자 · 복구 경로 · `u5b` 줄 80% 하한은 그대로다. 정산 복구는 새 표를 먼저 보고 없으면 레거시로 넘긴다(`PracticeRecoveryRouter`).
- 시작 API는 PRACTICE + 선택 본문 `practice`를 받아 202로 응답한다. 본문이 없으면 확장이 `practiceJson` 없이 시작해 '대본 없음'으로 실패시킨다.
- `team.md`의 낡은 마이그레이션 번호 알림(다음 번호 realtime V5)은 학습 절차로만 고친다. 다음 번호는 realtime **V8**이다.

## 7. 설정

| 키 | 기본값 | 문서 |
|---|---|---|
| `mujoong.timing.conversation.no-response-wait` · `focus-max` · `goal-active-limit` | 10s · 90s · 10m | `contracts/timing.md` 13절 (U8, 읽기만) |
| `mujoong.timing.device.output-queue-max` (슬롯 안전 시계 `slot-guard-<correlationId>`) | 기존 값 | 실행부 재생 만료와 같은 키를 읽는다 (코드에 숫자 시간 값 없음) |
| `mujoong.practice-flow.retry-max` · `reexplain-max` | 2 · 1 | `contracts/ai-io.md` 10.14 · `ConfigKeyContractTest` |

새 비밀 환경 변수는 없다(R-M9 해당 없음).

## 8. 시험 요약

| 층 | 시험 |
|---|---|
| V7 · 저장소 · 기록기 | `PracticeFlowSchemaIT` · `PracticeFlowRepositoryIT` · `PracticeFlowRecorderIT`(실제 DB) |
| 시작 경로 · 대본 읽기 | `PracticeStartPathIT` · `PracticeStartBodyU12Test` · `PracticeScriptReaderTest` |
| 전이표 · 상태 기계 · 라우터 | `FlowTransitionsTest` · `PracticeConversationTest` · `PracticeFlowRouterTest` |
| 실행부 슬롯 · 제브 연결 | `PracticeGeneratedSpeechTest`(ALLOW · BLOCK 0바이트 · 제브 비활성 폐기 · 같은 지시문 재지시 · 종료 사실 한 번) |
| 기록 · 진행 | `PracticeFlowRecordTest`(표식 문자열 · 값 타입 · 행 생성 조건) · `PracticeProgressMappingTest` |
| 끝-끝(대역) | `PracticeFlowIT`: 시작 → 도입 → 기회 → J2 일부 전달 → 제브 PRACTICE_PEER ALLOW → J3 충분 → 정리 → 정산 → `record.practice` |
| 구조 | `PracticeFlowArchTest` |

한국어 되묻기 · 코치 표현의 품질, 역할 전환 이해, J4 재사용 변형 동의 분류 품질, 제브 연습 모드의 실제 판정은 `[실호출-합성]` · `[실기기]`로 사람이 확인한다(R-F15 확인 기록 전에는 실제 제브 키를 넣지 않는다).
