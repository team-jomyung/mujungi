# U12 새 연습 흐름 — 기능 설계 (구현된 모습)

> 작업 기록 `261017-u12-practice-flow`의 승인된 기능 설계(`construction/functional-design/{functional-spec,rules,entities}.md`)에 Code Generation에서 정한 점을 합쳐 **코드에 들어간 모습**으로 옮긴 문서다. 승인 문서와 다르면 이 문서는 구현을 따른다. 흐름의 정본은 승인 문서이고 이 문서는 파생 보기다.
> 번호 체계: **설계 편차 `U12-D<n>`**(이 문서), **계약 문서에 더한 키 `(U12-a)`**(`contracts/deviations.md`). 규칙 번호 `BR`은 승인 `rules.md`의 것이다.
> 증거 구분(R-M11): 이 단위의 모든 합격은 **테스트 대역**(가짜 제브 · 가짜 공급자 · 가짜 판정)과 **합성 대화**다. 실제 AI API · 실제 제브 · 실제 기기에서 한 번도 돌지 않았다.

## 1. 범위

연습(PRACTICE) 세션을 새 연습 흐름으로 진행한다: 시작 요청이 연습 대본을 받고 → 참여 확인(실행부) 뒤 도입(무중의 설명 → 친구 첫 대사) → 친구 기회 문장 → 판정(J2 ~ J5) → 되묻기 · 도움 · 변형 → 친구 반응 → 정리 → 정산(`record.practice`)까지. 친구(PEER) · 코치(COACH) 역할 발화는 GPT-Live가 그 자리에서 만들고 **제브(연습 모드)가 `ALLOW`한 것만** 재생된다(제브 단계를 건너뛰는 dev · test 전용 OFF에서는 기본 게이트만 지난다, U12-D12). 장면 진행 말의 기준은 팀 기준 연습 대화다(`docs/practice-dialogue-standard.md`).

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
- **`sealAndGate`**: 열린 슬롯이 생성형이면 제브(`REAL` · `STUB`)를 `PRACTICE_PEER`/`PRACTICE_COACH`로 불러 `ALLOW`일 때만 재생한다. 시작이 실패하면 폐기한다(fail-closed). 제브 단계를 건너뛰는 `OFF`(dev · test 전용)에서는 기본 게이트와 송신 직전 재확인을 지난 생성형 발화를 그대로 재생한다(U12-D12, 편차 (U13-d)). 승인 발화 슬롯(`generated=false`)의 기존 길은 바뀌지 않았다(U5b 시험 무변경).
- **첫 막힘**은 같은 역할 지시문을 다시 보내 한 번 다시 검사한다. `discardForRetry`는 슬롯을 닫지 않으므로(코드로 확인, FA3) 재지시 동안 슬롯이 열려 있다. **두 번째 막힘**은 `GUARDRAIL_BLOCKED` 사건 다음 FAILED 사실이다.
- **슬롯 종료 사실은 정확히 한 번**: 재생 완료/끊김/실패(기존), 폐기(FAILED), 검사 중 아이가 말함(INTERRUPTED), 그리고 이번에 메운 두 길 — 무효로 닫힌 검사(`Env.practiceVoided` → INTERRUPTED)와 재지시가 소리로 이어지지 않은 경우(`finishAbandoned` → FAILED). 확장은 correlationId마다 첫 사건만 처리한다.
- **`announceRoleSwitch(toRole)`**: 시작 패키지 `approvedSpeech`의 `COACH_SWITCH`/`PEER_RETURN` 문장이 있으면 기존 승인 발화 `speak` 길로 낸다(원문 일치라 제브 호출 없음). 없으면 거짓이고 확장이 생성형 알림으로 낸다.
- 재생 증거 표지(`OutputKind` · 장면 id)는 실행부가 대본 장면을 모르므로 역할로만 정한다(코치 CUE, 친구 SCENARIO, 장면 id는 고정 값). 제브 요청 · 기록에 영향이 없다.

## 4. 기록 · 진행 표시 (BR7 · BR8)

- 장면 행은 도입 재생이 성공한 뒤에만 만든다(도입 실패 · 시작 전 미참여는 행 없음). 판 v6 대본의 변형 장면은 제안에서 무중이 설명을 들려줬으므로 수락할 때 행을 만든다(U12-D7). 사실 15종, 장면 결과(끝 시각 · 진행 중 판정 시점 · 변형 생략 사유 · 횟수), 도움 기록(종류 · 역할 · 예시 여부 · 동의 응답 · 길이)이 남는다.
- `record.practice`는 `flowVersion=practice-conversation-v1`, `scenes[]` 필수 4필드, `liveOutcome.timing`, `variantSkipReason`을 가지며 `practice-record-v2` 스키마를 통과한다(U14 읽기 코드 무변경). 판정 라벨 · 확률 · 전사 · 도움 내용은 어디에도 없다 — 값 타입에 문자열 칸이 없고 표식 문자열 시험이 로그 · 기록 · 이벤트 · 지시문을 훑는다.
- 진행 표시는 새 단계를 6값(SCENARIO_INTRO · FIRST_RESPONSE · HELP_OFFER · CUE · AFTER_CUE_RESPONSE · NEW_SCENARIO_RESPONSE)으로 접어 보낸다. 접기는 표시에만 있고 저장 값은 새 단계를 사실대로 둔다.

## 5. 편차

| ID | 대상 | 정본 | 이 구현 | 까닭 |
|---|---|---|---|---|
| U12-D1 | AC1.1.2 | 시작 전 휴식에 `D_REST_ACK_START` 재생 | 받아 주는 말 없이 재안내(U11-D6, 실행부 소유, 변경 없음) | 참여 전 응답은 계약 (U4b-a)상 들리지 않는다 |
| U12-D2 | AC1.5.5 | 도움 내용 기록 | 도움 종류 · 예시 여부 · 동의 응답 · 시각 · 내용 **길이**만 저장 | R-F14, RA 검토 R-02 |
| U12-D3 | AC1.5.4 | 변형 제안 동의 판정 지점 미정 | J4(수락 · 거절 · 불명확) 재사용 | 새 판정 지점은 계약을 바꾼다. 분류 품질은 실호출 확인 대상 |
| U12-D4 | functional-spec §3.3 OPPORTUNITY(J2) 보류 행 | 보류는 재안내 1회, 두 번째면 장면 정리(ABSTAINED) | 확신 · 차이 문턱 미달 보류(LOW_CONFIDENCE · LOW_MARGIN)는 확정 안 된 1순위가 HELP이면 곧바로, 아니면 재안내 뒤 두 번째에 HELP_OFFER(J4 동의 확인)로 간다. 판정 쪽 실패(시간 초과 · 장애 · 거절)와 다른 단계는 그대로 | 실제 판정 모의에서 머뭇거리는 아이("음, 잘 모르겠어.")가 1순위 HELP 0.48 ~ 0.52 보류 두 번으로 도움 제안 없이 끝났다(docs/practice-live-fixes.md LIVE-FIX-A5 ~ A8) |
| U12-D5 | functional-spec D6 · §3.3 SCENE_WRAP 행("장면 마무리 별도 발화는 없다") | 정리 진입 뒤 곧바로 변형 평가(코치 역할이면 복귀 알림 뒤) | 판정한 아이 대답이 장면을 닫으면(J2 FULL · J3 DELIVERED · J5 결과) 친구 반응 한 번(SCENE_WRAP 소단계 PEER_REACTION, 생성형 친구 슬롯)을 낸 뒤 변형 평가로 간다. 코치 역할이면 복귀 알림이 들린 뒤다. 시계 · 판독 불가 · 보류 · 막힘 · 도움 거절로 닫힌 장면은 반응 없이 평가한다. 반응 중의 아이 말 · 무응답은 보지 않는다 | 팀 기준 연습 대화 조건 4(칭찬 · 채점 대신 역할 안 반응) · AI 동작 명세 §3.1 `D_CONTINUE` · `D_RESUME` · §5.3 |
| U12-D6 | 도움 제안 발화(이전 구현: 친구 생성형) | 친구가 도와줘도 되는지 묻는다 | 무중이 역할 밖에서 묻는다: 승인 발화 `HELP_OFFER`(«무중이가 조금 도와줄까?») 원문을 말하는 사람 `무중`으로 재생하고, 없으면 코치 슬롯 생성형 | AI 동작 명세 §3.1 `D_OFFER_HELP`("친구 역할에서 잠깐 나와") · 기준 연습 대화 조건 3 |
| U12-D7 | functional-spec §3.3 SCENE_WRAP(변형 평가) 행("변형 제안 발화(`speakGenerated` PEER)") · VARIANT 도입 | 친구 생성형 제안(칭찬 '잘 마쳤다' 포함) → 수락 뒤 변형 설명부터 재생 | 판 v6 대본: VARIANT 설명 + «이어서 해 볼까?»를 무중 승인 발화로 묻고, 수락하면 설명을 다시 읽지 않고 행을 만든 뒤 첫 대사(있으면) → 기회 문장. 판 v5까지: 무중 생성형 질문(코치 슬롯, 칭찬 없음) → 수락 뒤 지금처럼 설명 → 기회 문장 | AI 동작 명세 §3.1 `D_VARIANT_OFFER` · `D_VARIANT_START`, 기준 연습 대화 조건 4 · 5. 판 v5 변형 설명은 기회 문장을 담아 제안에 넣으면 단서를 미리 듣는다 |
| U12-D8 | BR5.2 · 코치 발화 예시 단계 | 동의 뒤에만 예시(첫 코치 발화는 단서만, 다시 해 보기 뒤 예시) | 동의 · 요청 뒤 첫 도움은 요점 + 핵심 낱말을 비운 빈칸 예문(판 v7 대본은 대본의 빈칸 예문 그대로, U12-D11), 다시 해 보기 뒤는 표현 예시 하나. 빈칸 예문도 `exampleGiven` · `EXPRESSION_EXAMPLE`로 남기고, 대본에 예시가 없으면 단서만 준다 | 기준 연습 대화 `D_FOCUS`("다르면 '아니, 나 ○○ 달라고 했어'처럼 말하면 돼"). AI 동작 명세 §8.2 예는 첫 도움에 예시 전체를 들지만 팀장 기준을 따른다 |
| U12-D9 | functional-spec §3.3 종료 · 휴식(CLOSING) 행 · 정산 직전 종료(onStopping) | 진행 중이던 장면을 END_REQUEST로 닫는다 | 이미 정리된 장면(친구 반응 · 복귀 알림 · 변형 제안 중)에 온 종료 · 휴식 요청과 세션 종료는 정리 때 정한 `liveOutcome.timing`을 지우지 않고, 원 장면이면 변형 평가가 그 순간 냈을 `variantSkipReason`만 더한다(종료 · 휴식을 반영한 `decideVariant`: 변형 장면이 없으면 NO_VARIANT, 집중 한도로 닫혔으면 FOCUS_LIMIT, 그 밖 END_OR_REST) | 기록 손실 방지: 이전에는 변형 제안 중 휴식 요청이 끝난 장면의 '바로 전달' · '되묻기 뒤 전달'을 NONE으로 덮었고, 복귀 알림 중 요청 · 세션 종료는 생략 사유를 비워 두었다 |
| U12-D10 | 상황 다시 설명 뒤 OPPORTUNITY 재진입(이전 구현: 기회 문장만 다시 재생) | 다시 설명 → 기회 문장 | 장면에 친구 첫 대사가 있으면 다시 설명 → 첫 대사 → 기회 문장(OPPORTUNITY 소단계 PARTNER_OPENER, 그동안의 아이 말 · 판정 · 무응답 시계는 보지 않음, 첫 대사가 끊기면 기회 문장, 실패 · 막힘은 재생 실패로 정리). 무응답 재안내는 지금처럼 기회 문장만 | 실측 F3(x4: 부탁 단서가 첫 대사에 있어 다시 설명 뒤에 부탁 없이 기회 문장만 들렸다). 생성 v7이 단서를 기회 문장에 두게 했지만 옛 대본 · 지시를 어긴 대본에도 상황이 다시 서게 한다 |
| U12-D11 | 되묻기 · 코치 첫 도움 · 생성형 지시문 문구 | 되묻기는 더 알고 싶은 것을 한 번 되묻기, 첫 도움은 GPT-Live가 예시에서 낱말을 비운 말틀 | 되묻기: 아이가 원하는 것 · 할 말의 낱말을 설명에 나와 있어도 먼저 말하거나 확인하지 않는 열린 질문 하나, 장면 밖 새 질문 금지. 첫 도움: 판 v7 대본이면 요점 → 대본의 빈칸 예문 글자 그대로 + '처럼 말할 수 있어' → '네 말로 해 볼래?'를 빠짐없이(빈칸 예문이 칸 상한 80자를 넘거나 `뭐뭐`가 없으면 옛 길). 모든 생성형 지시문에 이모지 · 기호 · 괄호 금지. 지시문 낱말 '말틀'을 없애고 '빈칸 예문'이라는 말도 소리 내지 않게 한다. 가장 긴 칸에서도 끝 금지 규칙이 500자 안에 남는다 | 실측 F4(«파란색 크레용이 필요해?» · «너는 무슨 그림 그리고 있어?» · «🥛») · F5(«말틀은 …야» · 빈 도움 «네가 떠올린 말을 먼저 들려줘.» · 꾸밈말만 비운 말틀) · AI 동작 명세 §3.1 `D_REASK` · `D_FOCUS` |
| U12-D12 | 기능 설계 3.5(생성형 슬롯은 제브 ALLOW만 재생, 제브가 꺼져 있으면 폐기) | OFF에서 생성형 슬롯 폐기 | OFF에서는 기본 게이트 · 송신 직전 재확인을 지난 생성형 발화를 재생하고 슬롯을 재생 완료로 닫는다. REAL · STUB은 그대로 | 팀장 결정(2026-10-22, 실측 F8, 편차 (U13-d)): 제브 없이 기준 대화를 듣고 측정하기 위해서다. 공개 프로필은 OFF로 기동하지 않는다(기동 규칙 무변경). 성민 확인 필요 |

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
| 실행부 슬롯 · 제브 연결 | `PracticeGeneratedSpeechTest`(ALLOW · BLOCK 0바이트 · OFF 재생과 기본 게이트 폐기 · STUB 검사 대기 · 같은 지시문 재지시 · 종료 사실 한 번) · `PracticeFlowGuardrailOffTest`(OFF에서 기준 대화를 실행부로 끝까지) |
| 기록 · 진행 | `PracticeFlowRecordTest`(표식 문자열 · 값 타입 · 행 생성 조건) · `PracticeProgressMappingTest` |
| 끝-끝(대역) | `PracticeFlowIT`: 시작 → 도입 → 기회 → J2 일부 전달 → 제브 PRACTICE_PEER ALLOW → J3 충분 → 친구 반응(제브 PRACTICE_PEER ALLOW) → 정리 → 정산 → `record.practice` |
| 기준 연습 대화(대역) | `StandardPracticeDialogueTest`: 고정 대본(크레용 · 우유, 판 v7 빈칸 예문 포함, `src/test/resources/practiceflow/standard-practice-dialogue.json`)으로 기준 대화 차례 16개를 순서 · 말하는 사람까지 재현, 기록 장면 1 AFTER_REASK · 장면 2 AFTER_HELP, 코치 첫 도움이 대본의 빈칸 예문을 그대로 담는지 |
| 구조 | `PracticeFlowArchTest` |

한국어 되묻기 · 코치 표현의 품질, 역할 전환 이해, J4 재사용 변형 동의 분류 품질, 제브 연습 모드의 실제 판정은 `[실호출-합성]` · `[실기기]`로 사람이 확인한다(R-F15 확인 기록 전에는 실제 제브 키를 넣지 않는다).
