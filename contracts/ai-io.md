# AI 입출력 계약 뼈대 (ai-io.md)

> 계약 9절(C14, WBS 1.7.3) + U1이 정하는 공급자 연결 · 선 형식 · 시드 사전 형식 · 승인 음성 형식. 소유: U5(D), 공급자 선 형식은 U1(B).
> 1 ~ 5절은 형식만 고정한다. 판정 기준 본문 · 프롬프트 · 금지 표현 사전 v1 · 대기 문구 본문은 D 담당 AI 입출력 계약 문서가 채운다(OQ4 · OQ14 · OQ15). 6절은 U6 분석 AI 작업의 입력 · 출력 · 지시 요점 · 검사 규칙까지 정한다.
> 공급자 선 형식(2 · 3절)은 J110의 가정이고 11/2 실공급자 한 턴에서 어댑터만 고친다(V-2).

## 1. 계약 9절 형식 (그대로)

| 항목 | 형식 | 쓰는 곳 |
|---|---|---|
| 연습 단계 enum | `SCENARIO_INTRO` · `FIRST_RESPONSE` · `HELP_OFFER` · `CUE` · `AFTER_CUE_RESPONSE` · `NEW_SCENARIO_RESPONSE` | realtime_session_goal.current_stage · 12 연습 머리(U5) |
| 시도 · 반응 종류 | attempt_type: `ORIGINAL` · `NEW_SCENARIO` / response_type: `FIRST` · `AFTER_CUE` · `NEW_SCENARIO` | realtime_practice_attempt · response(U5) |
| 판정 출력 | `{ "label": "functional"\|"partial"\|"none"\|"uncertain", "reason": "...", "evidenceSpan": {"utteranceSeq": n}, "reviewNeeded": bool }`, 무반응은 판정 없이 `NOT_PERFORMED` | ResponseEvaluator → realtime_evaluation(U5) |
| Cue 브리프(Realtime 안) | `{ "situationSummary", "partnerUtterance", "focus" }` — 정답 문장 · 판정 기준 · 보호자 원문 없음 | PracticeProtocol → VoiceRelay 지시(U5) |
| 상황 생성 출력 | `internal-api` 5절 `Scenario` 스키마 + `validation` | PracticeGoal(TextAiGateway, U5) |
| 목표 후보 출력 | `{ behaviorKey, behaviorVersion, reasonText, evidence[{utteranceSeq, kind: GUARDIAN_INPUT\|CHILD_RECALL}] }` — 근거 없는 후보 금지 | ResultPipeline → CandidateRegistry(U6) |
| 경험 구조화 출력 | `{ characters[], situations[], partnerUtterances[], childReactions[], explanations[], each{text, source: GUARDIAN_INPUT\|CHILD_TRANSCRIPT, ref} }` — 의도 · 감정 필드 없음 | ResultPipeline → core_structured_experience(U6) |
| 기록용 재판정 출력 | 6.2절 | ResultPipeline → core_scene_judgment(U6) |
| 결과 문장 출력 | 6.3절 | ResultPipeline → core_result(U6) |
| 리포트 항목 출력 | 6.5절 | Report → core_report_section(U6) |
| 금지 표현 2차 분류 출력 | 6.6절 | TextAiGateway(U6) |
| 금지 표현 사전 항목 | `{ id, pattern, category: DIAGNOSIS\|TREATMENT\|SCORE\|CAUSE\|GENERALIZATION\|EMOTION_CLAIM\|PRESSURE, appliesTo: [OUTPUT_GATE, RESULT_TEXT, PDF, APPROVED_CONTENT] }` | OutputGate(U1 시드 · U3 정식) · TextAiGateway · Report |

## 2. 공급자 연결 (Realtime VoiceRelay, BR7.2 · J109 · J110 · J141 · SD-20 · SD-25)

| 항목 | 값 |
|---|---|
| 설정 키 | `mujoong.provider.url`(환경 변수 `MUJOONG_PROVIDER_URL`). 실제 공급자 기본값 `wss://api.openai.com/v1/live/sessions`(모델 `gpt-live-1`, BR7.2)은 realtime의 `application-demo.yml` · `application-prod.yml`에만 있고 `application.yml`에는 없다. 비어 있으면 기동 거부(S1b, J141) |
| 허용 호스트 | 코드 상수(설정으로 바꿀 수 없음). demo · prod에서 목록 밖 호스트면 기동 거부. `test` 프로필은 루프백 호스트만 허용한다(J141). 검증 스택(compose.ci)은 `MUJOONG_PROVIDER_URL`을 가짜 공급자로 명시한다 |
| 키 | 환경 변수 `PROVIDER_API_KEY`(realtime만 받음, 값은 어디에도 쓰지 않음). 연결 요청의 `Authorization: Bearer <키>` 헤더로만 보낸다. 기기 · 프런트 번들 · 응답 · 로그에 나가지 않는다(BR7.2 · BR8.7) |
| 연결 | 세션마다 별도 WSS, 별도 가상 스레드가 연다(R3-1). 연결 시한 `mujoong.provider.connect-timeout`(10초, 20초 시한 이하). 실패 · 시한 초과는 FAILED(PROVIDER_UNAVAILABLE) |
| 오디오 | 입출력 PCM16LE · 24kHz · 모노(J27). 기기 프레임 본문을 변환 없이 중계한다(J131 무변환 확인) |
| 전송 최소화 | 공급자로 가는 요청은 `ProviderRequestFactory` 한 곳에서만 만들고 요청 레코드는 지시문 · childContext 세 필드(displayName · age · interests) · 음성 프레임만 갖는다. 08 입력 네 칸 · 보호자 · 아이 · 기기 · 세션 id · 이메일 · 동의 · 판정 기준은 보내지 않는다(SD-20 · NFR3.1 · NFR3.2) |
| 로그 | 공급자로 가는 요청 본문 · 오는 이벤트 본문 · 전사를 로그에 쓰지 않는다(SD-15 · BR8.7) |

## 3. 공급자 선 형식 (J110 가정 — SDK 없이 JDK `java.net.http.WebSocket`, 가짜 공급자도 같은 형식)

텍스트 프레임 JSON 한 개 = 메시지 한 개, 공통 필드 `type`. 음성은 base64로 인코딩한 PCM16LE 바이트다. 모르는 type은 무시한다.

| 방향 | type | 필드 | 쓰임 |
|---|---|---|---|
| 서버 → 공급자 | `session.start` | `model`(코드 상수) · `instructions`(서버 템플릿 지시문, childContext는 따옴표로 묶은 데이터로만) · `childContext{displayName, age, interests}` · `audio{input: "pcm16le/24000/1", output: "pcm16le/24000/1"}` | 연결 직후 한 번. 응답 `session.started`가 ACTIVE 전이 조건의 하나다(BR6.8) |
| 서버 → 공급자 | `session.input_audio.append` | `audio`(base64, 기기 0x01 프레임 본문 960바이트 그대로) | ACTIVE 뒤 ~ CLOSE_TRANSPORT 전 기기 프레임을 순번 순으로(BR7.1 · R3-8). 그 밖의 프레임은 버린다. U4b: 참여 확인(WAITING_CHILD)부터 중계하고 원음 기록은 ACTIVE부터다(U4b-J2) [U4b 편차 (U4b-a)] |
| 서버 → 공급자 | `session.close` | — | 종료 · FAILED 전이 뒤 공급자 세션 정리(BR6.10 ④) |
| 서버 → 공급자 | `session.response.cancel` | `responseId`(선택 — 없으면 만들던 응답 전부) | 끼어들기(PLAYBACK_MARK STOPPED · LOCAL_VOICE_ACTIVITY)와 아이 종료 의도 판정 때 만들던 · 재생하던 응답을 취소한다. 공급자는 그 응답의 남은 조각을 보내지 않는다(U3-R5 · R12 · U3-J11 · J24) [편차 (aa)] |
| 서버 → 공급자 | `session.instructions.correct` | `text`(서버 지시문) | 게이트 폐기(GATE_PATTERN · 미완 GATE_EMPTY · SEGMENT_OVERSIZE) 뒤에는 범주별 고정 템플릿으로 응답당 한 번 교정하고 전사 · 걸린 패턴 · 미승인 원문을 넣지 않는다(U3-R7 · U3-J12 · SD-17) [편차 (aa)]. U5b의 승인된 연습 발화는 같은 type을 쓰되 8절의 타입 있는 최소 데이터 경로로 만든다. |
| 서버 → 공급자 | `session.response.create` | — | 자유대화는 참여 확인(WAITING_CHILD → ACTIVE) 뒤 첫 응답을 한 번 만든다. 참여 전에는 입력 중계만 열어 참여 대답 전사를 받는다(U4b-J3 · BR2.1). `controlsOutput()`인 U5b 연습은 일반 첫 응답을 요청하지 않고, 8절의 발화 지시를 보낸 뒤 해당 응답만 요청한다. 기존 `PracticeInstruction` 경로도 유지한다(BR10.4) [U4b 편차 (U4b-a)] |
| 공급자 → 서버 | `session.started` | `providerSessionId`(선택) | 세션 시작 확인 |
| 공급자 → 서버 | `session.input_audio.speech_stopped` | — | 아이 말 끝(tSpeechEnd 출처 PROVIDER_EVENT, OD-8) |
| 공급자 → 서버 | `session.input_transcript.delta` | `itemId` · `delta` | 아이 전사 조립(PARTIAL, BR7.5) |
| 공급자 → 서버 | `session.input_transcript.done` | `itemId` · `transcript` · `confidence`(선택, 0 ~ 1) | 아이 전사 확정(FINAL, tTranscriptFinal). `confidence`가 있고 `mujoong.transcript.uncertain-below-percent`(60)/100보다 낮으면 그 행을 review_needed(12 '불확실' 표시)로 적는다. 없으면 불확실로 보지 않는다(U3-R10 · U3-J17) [편차 (aa)] |
| 공급자 → 서버 | `session.output_transcript.delta` | `responseId` · `delta` | AI 말 전사 조각. 게이트 구간 버퍼에만 쌓는다(BR7.3). 한 구간에는 한 응답의 조각만 담고 `responseId`가 없으면 버린다 [편차 (z)] |
| 공급자 → 서버 | `session.output_audio.delta` | `responseId` · `audio`(base64) | AI 말 음성 조각. 구간이 확정 · 통과될 때까지 기기로 보내지 않는다(BR7.3 · BR7.4). 한 구간에는 한 응답의 조각만 담고 `responseId`가 없으면 버린다 [편차 (z)] |
| 공급자 → 서버 | `session.output.done` | `responseId` | 응답 끝 알림 = 그 응답의 전사 완료 신호. 게이트는 그 응답의 첫 조각부터 이 신호까지를 모두 담은 구간만 통과시킬 수 있다 — 신호가 없으면 그 응답은 GATE_EMPTY로 폐기된다(SD-17 3단계 fail-closed). 보내는 쪽은 완결된 응답에만 보낸다. 구간 확정은 800ms 조용 · 아이 입력 재개 · 다른 응답의 조각 도착으로 한다(BR7.3) [편차 (z)] |
| 공급자 → 서버 | `session.error` | `code` | `session.started` 전이면 PROVIDER_UNAVAILABLE |
| 공급자 → 서버 | `session.closed` | — | 공급자가 세션을 닫음 |

가짜 공급자 시나리오(백엔드 시험 `FakeProviderWsServer` · C안 `tools/fake-provider/`): 정상(전사 · 출력 길이 지정) · 금지 패턴 응답 ·
연결 거부 · `session.started` 없음 · 지연 · 연결 단계 무응답. 받은 입력 음성 바이트 수 · 해시와 보낸 출력 음성 해시를 기록한다(J131).
U3: `session.response.cancel`을 받으면 그 응답의 남은 조각을 보내지 않고 받은 사실만 기록하며, `session.instructions.correct`는 본문 길이 · 해시만
기록한다(원문 저장 없음). `--confidence` 옵션은 확정 전사에 `confidence` 값을 싣는다(기본은 싣지 않음).
U4b: `session.response.create`는 받은 사실만 기록한다. 턴 종류 `join`은 참여 확인 대답("응") 전사만 보내고 AI 출력은 없다(`--turns join,normal,forbidden`) [U4b 편차 (U4b-a)].

## 4. 출력 게이트 시드 사전 형식 (SD-18 · A5 · J101)

파일: `backend/realtime-activity/src/main/resources/gate/seed-dictionary.json`(계획 Step 11). 후속 유닛은 같은 파일에 항목을 더한다.

```json
{
  "version": "seed-1",
  "level": "SEED",
  "entries": [
    { "id": "SEED-DIAGNOSIS-1", "pattern": "진단", "category": "DIAGNOSIS", "appliesTo": ["OUTPUT_GATE"] }
  ]
}
```

| 항목 | 규칙 |
|---|---|
| 패턴 문법 | 리터럴과 숫자 자리표시 `#`(숫자 한 자 이상)만. 정규식을 쓰지 않는다(선형 시간, ReDoS 없음) |
| 정규화 | 전사를 NFKC → 공백 · 제로폭 문자 · 구두점(`%`는 남김) 제거 → 라틴 소문자로 바꾼 뒤 부분 문자열로 찾는다 |
| 로딩 거부 | 파일 없음 · 파싱 실패 · 중복 id · category 7개 중 빈 것 · 정규화 뒤 형태가 아닌 패턴(공백 · 구두점 · 대문자 라틴 · 정규식 메타 문자 포함) → Realtime 기동 거부(S1b, 종료 코드 1). 로딩 완료가 readiness 조건이다(S6) |
| U1 시드 항목(J101 · 사람 결정 12) | DIAGNOSIS `진단` · TREATMENT `치료` · SCORE `비정상` · CAUSE `핵심원인` · GENERALIZATION `학교에서도그럴` · EMOTION_CLAIM `화가났군요` · PRESSURE `꼭해야해요`. `점수` · `정상이에요` · `정상입니다`는 정식 사전 v1(D 담당)에서 더한다(알려진 틈) |

## 5. 승인 문구 · 승인 음성 (J60 · J102 · J114 · J117)

| contentCode | 쓰임 | 시드 문구(임시) | 음성 파일(audioUrl) |
|---|---|---|---|
| `CLOSING` | 끝내기 마무리 인사(BR6.10, 한 번만, 3초 이내) | 오늘 이야기 즐거웠어. 다음에 또 만나! | `/media/approved/closing-v1.wav` |
| `WAITING_FILLER` | 게이트 폐기 뒤 대기 · 대체 안내(BR7.4) | 음, 잠깐만 생각해 볼게. | `/media/approved/waiting-filler-v1.wav` |
| `CONSENT_POLICY` | 현재 정책 버전 자리(최고 APPROVED 버전 행의 `content_text`, 임시 `1.0`) | — | 없음 |

- 음성 파일은 WAV · PCM16 · 24kHz · 모노이고 개인정보가 없다. U1 임시 파일은 생성기 `tools/seed/gen-approved-wav.mjs`가 만든 합성 무음 · 톤이다(J102, 실제 녹음은 D 담당).
- 세션 패키지는 시작 때 문구 버전을 고정해 Realtime에 건네고(`approvedContent`), Realtime은 START_SESSION으로 기기에 미리 받게 한다.

## 6. 분석 AI 작업 (U6 ResultPipeline · Report — FR4.3 · FR6.5 · FR7.4 · FR7.6 · FR7.11)

모든 호출은 Core TextAiGateway 한 곳에서 보낸다. 모델 · 프롬프트 버전은 결과 행에 남긴다(modelVersion · promptVersion). 프롬프트 본문은 core-api 리소스에 버전 파일로 두고 이 절의 입력 · 출력 형식을 지킨다.

### 6.1 공통

모든 작업의 시스템 지시 앞에 아래를 붙인다.

```text
너는 아동 대화 기록을 정리하는 도우미다. 결과는 보호자와 상담 전문가가 읽는다.
- 저장된 전사와 입력 자료에 있는 사실만 쓴다. 없는 내용을 지어내지 않는다.
- 인용은 전사의 글자를 그대로 쓰고 utteranceSeq를 함께 적는다.
- 진단 · 장애 · 점수 · 등급 · 백분율 · 향상률 · 성공률 · 치료 효과 · 결함 단정을 쓰지 않는다.
- 아이나 다른 사람의 의도 · 감정 · 성격을 단정하지 않는다. 아이가 직접 말한 것은 인용으로만 쓴다.
- 실제 학교 · 친구 관계에서도 그렇다고 일반화하지 않는다.
- 근거가 부족하면 지어내지 말고 해당 칸을 비우고 이유를 적는다.
- 지정한 JSON 스키마로만 답한다.
```

| 항목 | 규칙 |
|---|---|
| 입력 최소화(NFR3) | 아이 이름 대신 "아이", 다른 사람은 별칭(친구 A). 보호자 · 아이 · 세션 id, 이메일, 원음은 보내지 않는다. 활동은 요청 안 번호(activityNo)로 가리키고 서버가 세션으로 되돌린다. 08 네 칸은 경험 구조화에만 넣는다. 동의 철회 · 삭제된 자료는 넣지 않는다 |
| 온도 | 기록용 재판정 0, 생성 작업 0.2. 설정 `mujoong.text-ai.result.*` [U6 편차 (U6-f)] |
| 출력 검사 순서 | ① JSON 스키마 ② 인용 대조: `quote`가 그 utteranceSeq의 저장 전사와 글자 단위로 같아야 한다(앞뒤 공백만 무시). 다르면 그 인용을 버린다 ③ 금지 표현 2단계(6.6) |
| 실패 | 공급자 오류 · 시간 초과 · 스키마 실패는 결과 작업의 시도 실패다(timing `result.job-attempts`). 인용 · 금지 표현에 걸린 문장은 실패가 아니라 고정 템플릿으로 바꾼다 |

### 6.2 기록용 재판정 (연습 종료 확인 뒤, 온도 0)

- 입력: 전체 전사(seq · speaker · text), 목표마다 { goalSelectionId, displayName, coreFunction, judgmentCriteria }(BehaviorDefinitionLookup), 장면마다 { sceneNo(1 = ORIGINAL, 2 = NEW_SCENARIO), 상황 · 상대 말 · 기회 트리거 대본 }(ScenarioLookup), 진행 중 판정(record.practice.goals[].responses[]: scenarioId · responseType · utteranceSeq · label · notPerformedReason), 도움(cues[]: scenarioId · consentResponse · deliveredText · givenAt).
- U5b의 `record.practice.goals[]`는 시작 때 고정한 `behaviorKey`(문자열) · `behaviorVersion`(양의 정수)을 함께 싣는다. U6은 이 버전으로 BehaviorDefinitionLookup을 읽는다. 현재 목표 목록이나 최신 정의로 바꾸지 않으며, `judgmentCriteria` 원문은 이 스냅숏에 추가하지 않는다. 내부 기록에는 order · status · skipReason과 scenarios · responses · cues가 남고, 보호자 진행 조회는 8절의 네 필드만 사용한다.
- 지시 요점: 장면마다 첫 반응 · 도움 후 반응 · 결과를 나누고 핵심 기능 충족 여부를 판정한다. 예상 문구와 달라도 기능이 같으면 인정한다. 판독 불가 · 질문 핵심 미전달을 미관찰로 확정하지 않는다. 도움 후 반응이 첫 반응을 덮어쓰지 않는다.
- 출력:

```json
{
  "goals": [
    {
      "goalSelectionId": "uuid",
      "scenes": [
        {
          "sceneNo": 1,
          "firstResponse": { "utteranceSeq": 31, "quote": "그거 줘." },
          "afterHelpResponse": { "utteranceSeq": 33, "quote": "빨간 블록 하나만 빌려줄래? 탑 꼭대기에 올리고 싶어." },
          "value": "functional",
          "timing": "AFTER_HELP",
          "rationale": "도움 뒤 필요한 것과 이유를 함께 말해 부탁 기능을 충족",
          "notDoneReason": null
        }
      ]
    }
  ]
}
```

- value: functional · partial · none · uncertain, 또는 null(판정할 반응 없음 — notDoneReason 필수: TIME_OVER · SCENARIO_FAILED · ACTIVITY_ENDED · NO_RESPONSE). timing은 functional에만 쓴다: IMMEDIATE(첫 반응 · 새 상황 반응에서) · AFTER_HELP(도움 후 반응에서).
- 서버 처리: 진행 중 판정과 다르면 differsFromLive = true로 저장한다. 출력에 없는 장면은 null(ACTIVITY_ENDED)로 채운다.
- 장면 결과 칩 — 보호자 응답 · 리포트에 나가는 유일한 형태다(value · timing · rationale은 나가지 않는다).

| value · timing | sceneChip | chipText |
|---|---|---|
| functional · IMMEDIATE | DONE_IMMEDIATE | 바로 전달 |
| functional · AFTER_HELP | DONE_AFTER_HELP | 도움 뒤 전달 |
| partial · none | NEXT_TIME | 다음 기회로 |
| uncertain | ON_HOLD | 보류 |
| null | NOT_DONE | 미실시 · {사유}(TIME_OVER 시간 종료 · SCENARIO_FAILED 상황 준비 실패 · ACTIVITY_ENDED 활동 끝 · NO_RESPONSE 반응 없음) |

'전달'은 목표 행동 표현의 기본값이다.

### 6.3 결과 문장 (종료 확인 뒤)

- 입력: 자유대화는 경험 구조화와 인용 후보, 연습은 장면 판정과 인용, 공통으로 종료 사유 · 확보 범위.
- 출력: `{ "sentences": [ { "goalSelectionId": "uuid 또는 null", "text": "도움 뒤 \"같이 쌓아도 돼?\"라고 말했어요. 두 번째 장면은 시간이 다 돼 하지 않았어요.", "citations": [ { "utteranceSeq": 33 } ] } ] }`. 연습은 목표마다, 자유대화는 활동 하나에 1~2문장.
- 검사: 인용 대조 · 금지 표현(6.6) · 숫자는 시각 · 장면 번호만. 하나라도 걸리면 그 문장을 고정 템플릿으로 바꾼다 — 연습 "{목표 이름} 연습을 했어요. 장면 {n}개 중 {m}개를 진행했어요.", 자유대화 "{n}분 동안 이야기를 나눴어요."(n · m은 서버 집계).

### 6.4 결과 수준 (서버 계산, AI 아님)

FR7.11 표대로 FULL · PARTIAL · INSUFFICIENT를 정한다. 내용 있는 아이 발화 = 불확실 표시가 아니고 어절(공백으로 나눈 덩어리)이 3개 이상인 아이 확정 전사. 첫 반응이 기록된 장면 = responseType FIRST 또는 NEW_SCENARIO에 utteranceSeq가 있는 장면.

### 6.5 리포트 항목 (28 요청 때, REPORT 작업)

| 항목 | 만드는 쪽 | 규칙 |
|---|---|---|
| 1. 활동 개요 | 서버 집계 | 기간 · 자유대화 n회 · 연습 n회 · 총 n분 |
| 2. 관찰된 장면(인용) | 텍스트 AI | 대표 장면의 아이 발화 인용 2~5개 + 시각 |
| 3. 연습 목표와 장면 | 서버 집계 | 목표별 장면 수 · 장면 결과 칩(6.2) · 도움 횟수 |
| 4. 아동 정보 | 서버 | 이름 · 나이(ChildContext). 관심사 없음 |
| 5. 최근 실제 경험과 출처 | 텍스트 AI | 경험 구조화 항목 요약, 줄마다 source(GUARDIAN_INPUT · CHILD_RECALL)를 구조화 결과에서 그대로 단다. 보호자 입력 원문을 옮기지 않는다 |
| 6. 주요 대화 내용 | 텍스트 AI | 인용 중심 요약, 해석 · 단정 없음 |
| 7. 살펴본 화용 행동 | 서버 | 목표 이름 · 핵심 기능 정의(BehaviorDefinitionLookup) |
| 8. 제공한 도움 | 서버 집계 | 도움 횟수 · 동의 응답 분포, 예 1개(deliveredText 중 정답 문장 없는 것) |
| 9. 과거 기록과의 차이 | 서버 | 같은 목표의 장면 칩을 날짜순으로 나열(장면 · 도움 조건 함께). 범위 안 같은 목표 기록이 2회 미만이면 자료 부족. 향상 여부를 계산하지 않는다 |
| 10. 제한사항 | 텍스트 AI + 고정 문장 | 고정 문장 "AI와의 연습 장면에서 본 기록이며 실제 친구 관계에서도 같은지는 알 수 없어요." + 확인하지 못한 것 · 미실시 · 자료 부족 · 미확인 구간 |

- 텍스트 AI 출력: `{ "sections": [ { "sectionNo": 2, "text": "…", "missing": false, "citations": [ { "activityNo": 1, "utteranceSeq": 12, "quote": "…" } ] } ] }`(sectionNo 2 · 5 · 6 · 10만).
- 검사에 걸린 항목의 고정 템플릿: 그 항목에 쓸 수 있는 인용을 날짜순으로 나열한다. 인용이 하나도 없으면 text "자료 부족", missing = true.
- 항목 이름 · 순서는 FR6.5 표로 고정한다. 고지 고정 문구: "이 리포트는 진단 결과가 아니에요. 전문가에게 보여 줄 참고 자료예요. 제한사항은 마지막 항목에 있어요."
- level: 범위 안 활동의 결과 수준(6.4)이 모두 INSUFFICIENT면 INSUFFICIENT, 하나라도 FULL이 아니면 PARTIAL, 모두 FULL이면 FULL.

### 6.6 금지 표현 2단계 (결과 문장 · 생활 팁 · 리포트)

- 1차: 4절 형식의 사전 중 appliesTo에 RESULT_TEXT 또는 PDF가 있는 항목. 정식 사전 v1에 더할 초안은 아래 표다(분류는 4절의 category 7개로 묶는다).

| category | 초안 |
|---|---|
| DIAGNOSIS | 진단, 자폐, ASD, SCD, ADHD, 장애, 증상, 의심되, 검사가 필요 |
| SCORE | 점수, 등급, %, 퍼센트, 백분위, 향상률, 성공률, 정답률, 상위, 하위 |
| TREATMENT | 치료, 치료 효과, 치료가 필요, 처방 |
| CAUSE | 부족하, 결함, 문제가 있, 핵심 원인, 원인은, 못한다, 서툴다 |
| GENERALIZATION | 학교에서도, 항상, 늘 그, 언제나, 친구들과도 그럴 |
| EMOTION_CLAIM | 고 싶었던 거, 때문에 속상, 화가 났던 것 같, 성격이, 일부러 |
| PRESSURE | 꼭 좋아, 반드시, 효과가 있, 이렇게 말해야 해, 고쳐야, 매일 해야, 꾸준히 해야, 계속 해야 |

- 2차(U6-J 초안): 1차를 통과한 결과 문장 · 후보 이유 또는 리포트 AI 항목을 작업당 한 번에 묶는다. 입력 `{ "sentences": [{"id":"s1", "text":"…"}] }`, 출력 `{ "results": [{"id":"s1", "violation":true, "category":"EMOTION_CLAIM", "span":"…"}] }`. 모든 요청 id가 정확히 한 번 나와야 한다. 지시: "아래 문장이 금지 중 하나에 해당하는지 판단하라. 저장 전사와 대조를 통과한 직접 인용은 금지가 아니다. 해당하면 분류와 문제 구간을 돌려준다." 승인 생활 팁은 1차 사전만 적용한다(BR7.2).
- 걸리면: 결과 문장 · 리포트 항목은 고정 템플릿(6.3 · 6.5), 생활 팁은 보여 주지 않는다(FR6.3 '추가 관찰 필요').

### 6.7 경험 구조화 (U6-J 초안)

자유대화 전사(seq · speaker · text · uncertain), 종료 사유 · 확보 범위와 08 네 칸(knownContent · situationType · sourceType · question)을 입력한다. 아이 이름은 '아이', 인물은 별칭으로 바꾸고 식별자 · 원음은 보내지 않는다. 08 네 칸은 이 작업에만 전달한다.
출력은 `{ "characters": [{"alias":"친구 A", "source":"CHILD_RECALL", "utteranceSeq":1}], "situation": null, "partnerUtterance": null, "childResponse": null, "explanation": null }`이다. null이 아닌 칸은 `{text, source, utteranceSeq?}`이며 source는 GUARDIAN_INPUT 또는 CHILD_RECALL이다. CHILD_RECALL은 실제 아이 발화 번호와 글자 단위 근거가 필요하다. 의도 · 감정 등 추가 속성을 받지 않는다. 지시: 입력에 있는 사실만 분리하고 출처를 붙이며 없는 칸은 null로 둔다.

### 6.8 목표 후보 (U6-J 초안)

전사, 검증된 구조화(자유대화) 또는 장면 칩(연습), ACTIVE 정의 목록(behaviorKey · behaviorVersion · displayName · coreFunction)을 입력한다. 출력 `{ "candidates": [{"behaviorKey":"…", "behaviorVersion":1, "reasonText":"…", "evidence":[{"utteranceSeq":1,"quote":"…","kind":"CHILD_RECALL"}]}] }`에서 근거 인용 · ACTIVE 정의 · 금지 표현을 서버가 확인한 최대 3개만 등록한다. 근거 없는 후보와 보호자 입력만 근거인 후보는 버린다. 지시: 관찰된 아이 회상 발화만 근거로 쓰고 의도 · 감정을 단정하지 않는다.

### 6.9 U6 호출 예산과 생활 팁

결과 · 리포트 호출은 `mujoong.text-ai.result.request-timeout`과 작업 시도 잔여 시간 중 작은 값으로 제한한다. 게이트웨이 재시도 없이 작업당 총 3회 시도한다. INSUFFICIENT 사전 판정이면 AI 없이 DONE이다. 생활 팁은 목표 · 검증 후보 behaviorKey와 승인 항목의 교집합을 서버에서 찾고, title · condition · content · limitation에 1차 금지 사전을 적용한다. 사전 · 프롬프트는 U6-J 초안이고 D 담당 확인 대상이다.

## 7. U5a TextAiGateway (U5a-d)

Core의 사후 텍스트 AI 호출은 TextAiGateway 한 곳을 지난다. TextAiProvider의 실제 구현은 이 유닛에 없으며 기본값은 UNAVAILABLE다. 예약 환경 변수 이름은 MUJOONG_TEXT_AI_ENDPOINT, MUJOONG_TEXT_AI_MODEL, MUJOONG_TEXT_AI_API_KEY이다. 시간 · 재시도 값은 application.yml `mujoong.text-ai.*`(timing.md 9절)다. 값을 넣는 것만으로 활성화되지 않으며 공급자 어댑터와 통합 검증이 필요하다. Realtime 음성 설정과 공유하지 않는다.

입력 ScenarioPrompt는 목표 이름/행동 정의/기회 조건/상황 조건/관계, 나이, 관심사, 새 상황일 때 이전 상황 본문뿐이다. 아이/보호자 이름·식별자·전사·보호자 자유 입력·판정 기준은 보내지 않는다. 입력/출력 record의 문자열 표현과 예외는 본문을 숨긴다.

출력 GeneratedScenario는 scenarioText, triggerScript, relationType, opportunityCondition, modelVersion, promptVersion이다(상대 역할 지시는 모델에 받지 않는다, 아래). 모델이 준 validation 플래그는 받지 않는다. Gateway는 필드 크기·빈 값·모델/프롬프트 버전 및 7범주 금지 사전을 별도로 검사한다. 상황 본문 최대 8000자, 대사/기회 2000자, 관계 200자, 버전 80자(ASCII 식별자). 빈·잘못된 출력과 금지 표현은 기술 재시도 대상이 아니다.

Core는 고정 기회 조건과 트리거가 본문에 포함되는지 및 관계/기회 일치를 확인한다. 가상 상황 고지를 덧붙이고, 아이별 정규화 상황 지문으로 기존 ISSUED/EXPOSED 재사용을 막는다. NEW_SCENARIO의 같은 원문/문장부호 변형도 거절한다. 확인 실패는 한 번 다시 생성한 뒤 SCENARIO_GEN_FAILED(422)다. 두 번 모두 내용 확인에 실패했을 때만 그 요청 키를 실패로 굳히고, 이미 낸 상황과 같은 내용 · 다른 요청과의 저장 경합으로 끝난 422는 같은 키로 다시 요청하면 새로 만든다. 세션이 고정한 정의 버전을 쓸 수 없으면 400 VALIDATION_FAILED다. 의미적 재서술·언어 난도·정답 암시를 완전히 검사하는 것은 아니며 실제 공급자 도입 전에 승인 fixture 검토가 필요하다(U5a-J16/J17).

C10 partnerRoleText는 모델에 받지 않고 Core의 고정 역할 지시로 만든다(상대 역할만 수행, 아이의 답 대신 말하기/평가 금지, U5a-J22). 그래서 Gateway 검사와 공급자 출력에도 이 칸이 없다.

일시 실패에 한한 기술 재시도 1회(3초/호출, 250ms 간격)와 내용 재생성 1회는 모두 C10 전체 15초 안에서 수행한다. UTC/단조시계 남은 예산의 작은 쪽을 사용하며 실행 Future를 제한 시간에 취소한다. 취소를 무시하는 실제 공급자는 별도 통신 시한과 취소를 반드시 구현해야 한다. 생성된 상황은 내부 C10 응답에만 있고 보호자 목록에는 상황/판정/AI 원문이 없다.

## 8. U5b 연습 발화 · Cue · 재생 증거

실제 연결은 `RuntimePracticeExtension` → `PracticeOutput.speak(PracticeSpeech)` → `PracticeInstruction.forSpeech` → 기존 `ProviderRequestFactory.practiceInstruction` 입구다. `PracticeSpeech`는 서버 내부에서만 `correlationId` · `scenarioId` · `generation` · `kind` · `partnerRole` · `text`를 가진다. kind는 `SCENARIO` · `HELP_QUESTION` · `CUE`이며, 상대 역할은 200자 이하, 발화는 10201자 이하이고 빈 값과 허용하지 않은 제어 문자를 거절한다. 공급자 팩토리 앞에서 식별자를 버리고 역할·발화 JSON과 고정 템플릿만 남긴다. 기존 자유 지시문 생성자의 500자 제한은 유지하고, 타입 검증을 통과한 발화 템플릿만 JSON 이스케이프를 포함한 64000자 상한을 쓴다. 문자열 표현에는 본문을 넣지 않는다.

| 경계 | 실제 입력 · 출력과 검사 |
|---|---|
| 상황 발화 | Core가 검증한 상황의 `partnerRoleText`, `scenarioText + 줄바꿈 + triggerScript`를 사용한다. 도움 질문은 고정 문장 ‘도움을 받아 볼까?’다. 상황 발화에 정답 · 시범 · 도움을 덧붙이지 않는다. |
| Cue 생성 입력 | `CueBrief{situationSummary, partnerUtterance, focus}`만 `CueProvider.generate`에 넘긴다. 현재 focus는 스스로 다음 말을 떠올리게 하는 고정 지시다. 판정 기준 · 정답 문장 · 보호자 원문 · 계정 식별자 필드는 없다. |
| Cue 생성 출력 | 비동기 `Result{text, approved, containsAnswerSentence}`다. 현재 CUE 단계 · 동의 ACCEPTED · 같은 scenarioId/세대 · 아직 주지 않은 도움인지 확인하고, `approved=true`, `containsAnswerSentence=false`일 때만 발화로 보낸다. 오류 · 승인 실패 · 정답 포함은 재생 실패 경로로 간다. |
| 공급자 송신 | 서버 내부 식별자는 보내지 않는다. `session.instructions.correct.text`에는 고정 지시와 JSON 데이터 `{partnerRole, speech}`만 추가한다. 기존 응답 취소 → 지시 전달 → `session.response.create`를 직렬로 보내며, 낡은 출력 세대나 ACTIVE 밖이면 요청을 버린다. |
| 공급자 출력 | 기존 OutputGate를 통과하고, 공백을 제거한 실제 전사가 승인된 `PracticeSpeech.text`와 정확히 같아야 기기로 보낸다. 전사/음성 누락 · 내용 불일치 · 게이트 거절 · 송신 실패를 성공으로 취급하지 않는다. 실제 playbackId를 서버 내부 발화와 연결한다. |
| 기기 재생 증거 | PLAYBACK_MARK의 STARTED → STARTED, COMPLETED → COMPLETED, STOPPED → INTERRUPTED, FAILED → FAILED로 구분한다. PROGRESS는 단계 완료가 아니다. `PlaybackFact`는 발화의 상관 ID · 상황 ID · 세대와 서버 수신 시각을 보존한다. 누락 · 만료도 완료로 만들지 않는다. |

상황 COMPLETED의 **서버 수신 시각**이 `scenarios[].exposedAt`과 첫/새 상황 반응 기회의 시작이다. 도움 질문 COMPLETED부터 동의 대기, Cue STARTED부터 `cues[].givenAt`, Cue COMPLETED부터 도움 후 반응 기회를 계산한다. 사건을 큐에서 나중에 처리해도 수신 시각을 처리 시각으로 바꾸지 않는다. 거부 · 무응답 · 재생 전 실패의 Cue 행에는 `givenAt`이 없으므로 Cue 배열 길이는 실제 도움 제공 횟수가 아니다.

기술 일시정지와 STOP_CONFIRM이 겹치면 합집합 시간만 반응 기회에서 뺀다. 재생 사실이 지연 전달되어도 수신 뒤 실제 정지 구간만 `pausedFor`로 넘기고 일반 큐 지연은 더하지 않는다. 노출/도움 원본 시각과 이미 수락한 판정의 전체 시한은 바꾸지 않는다.

보호자 진행 조회는 `practice{goalIndex, goalCount, goalName, stage}`뿐이다. 상황 · Cue 원문, 진행 중 판정과 판정 기준은 보내지 않는다. 내부 `record.practice`의 응답/도움은 각자 scenarioId를 보존하고, 노출 정산은 `scenarios[].exposedAt`만 사용한다.

`CueProvider`와 진행 중 판정 공급자의 운영 기본은 미연결 실패다. 이 문서는 실제 공급자 연결 · 장치 재생 · 부하 검증 완료를 뜻하지 않는다. 해당 구현과 검증은 별도 인계 항목이다.

## 10. U8 공통 규칙 (GPT-6-Luna · 제브 구조화 입출력 · 판 리소스 · 승인 발화)

U8(계약 · 버전 리소스)이 정한 공통 규칙이다. Luna · 제브 구조화 입출력의 **정본은 아래 JSON Schema 파일**이고, 이 절은 규칙 · 금지 필드 · 실패 처리만 적는다. 코드는 같은 파일로 공급자에 구조화 출력을 요청하고, 프롬프트 판(`promptVersion`)과 스키마 판은 함께 올린다. 각 단위(U9 ~ U15)는 더하기만 한다 [U8 편차 (U8-a)].

### 10.1 스키마 파일

| 파일(`contracts/schemas/`) | 목적 | 호출 경로(단위) |
|---|---|---|
| `ai-judgment-request.schema.json` | 진행 중 판정 요청(J1 ~ J6, 공급자로 보내는 최소 필드) | realtime 판정 턴(U11) · `JudgeClient`(U9) |
| `ai-judgment-output.schema.json` | 진행 중 판정 출력(1 · 2순위와 확률) | 같음 |
| `ai-scenario-output.schema.json` | 연습 장면 대본 생성 출력(상대 역할 지시는 받지 않음, 7절 U5a-J22) | core 상황 생성(U10) |
| `ai-scenario-review-output.schema.json` | 생성과 분리한 자체 검토 출력(J7) | 같음 |
| `ai-goal-candidate-output.schema.json` | 목표 후보(6.8 U6 초안을 대체) | core 결과 작업(U14) |
| `ai-rejudgment-output.schema.json` | 새 연습 흐름 기록용 재판정 | 같음 |
| `ai-seed-output.schema.json` | 새 씨앗 | core 씨앗 생성(U15) |
| `guardrail-request.schema.json` | 제브 검사 요청(내부 형식, 어댑터가 공급자 형식으로 바꿈) | realtime 출력 게이트(U13) · `GuardrailClient` |
| `guardrail-result.schema.json` | 제브 검사 결과(내부 형식) | 같음 |
| `practice-record-v2.schema.json` | 기록 캡처 이벤트 `record.practice` 새 흐름 형식(`flowVersion` 필수) | U12가 채우고 U11이 발행, U14가 읽음 [U8 편차 (U8-d)] |

- 요청 스키마는 루트와 하위 객체 모두 `additionalProperties: false`로 닫는다. 아이 관련 문자열 필드의 `description`에는 "아이 이름은 '아이'로 치환된 값"을 적는다.
- 길이 초과 · 빈 응답 · 스키마 최종 실패는 어떤 목적에서도 성공으로 저장하지 않는다. 실패 사유 코드 · 모델 · 프롬프트 판 · 지연만 기록하고 요청 · 응답 원문은 남기지 않는다(BR5.3 · R-F14).

### 10.2 금지 필드와 외부 전달

- 외부 AI(Luna · 제브)로 보내는 필드는 스키마가 허용한 최소 필드뿐이다. 다음은 어느 요청 스키마의 속성도 될 수 없다: 아동 · 보호자 · 세션 · 기기 식별자(`childId` · `guardianId` · `sessionId` · `deviceId` 등), 키(`apiKey` 등), 원음(`audio`), 보호자 입력 원문, 이메일, 동의 상태. 아이 이름은 '아이'로 바꾼다(BR5.4 · NFR3.1).
- **R-F15** 공급자 약관 · 보호자 동의 확인 전에는 실제 아동 데이터를 외부 모델로 보내지 않는다. 실호출은 합성 자료로만 한다. '확인됨' 상태를 코드에서 판정하는 방법은 U9가 정한다(BR5.5).
- 시험 · CI · 검증은 가짜 공급자만 쓴다(R-F1). 테스트 대역의 성공을 실공급자 성공으로 표시하지 않는다(R-M11).

### 10.3 목적 → 호출 경로 → 재시도 분류

| 목적 | 호출 경로(단위) | 분류 | 호출 안 재시도 · 실패 뒤 |
|---|---|---|---|
| 진행 중 판정 J1 ~ J6 | realtime 판정 턴(U11) | `NONE` | 재시도 없음. `mujoong.timing.judgment.live-budget`(2초) 초과 · 형식 오류 · 선택지 밖 코드는 판정 보류 |
| 상황 생성 | core 상황 생성 요청(U10) | `REQUEST_BOUNDED` | 일시 실패 기술 재시도 1회 + 내용 재생성 1회(스키마 불일치 포함), 7절 15초 예산 안. 실패는 `SCENARIO_GEN_FAILED` |
| 자체 검토(J7) | core 상황 생성 요청(U10) | `REQUEST_BOUNDED` | 같은 15초 예산 |
| 새 씨앗 | core 결과 작업 뒤 씨앗 생성(U15) | `REQUEST_BOUNDED` | 실패하면 `skill_ref=NONE` 유지 |
| 결과 문장 | core 결과 작업(U14) | `JOB_ATTEMPT` | 호출 안 재시도 없음. 결과 작업 시도 실패로 세고 작업 재시도(timing.md 10절)로 다시 한다 |
| 목표 후보 | core 결과 작업(U14) | `JOB_ATTEMPT` | 같음(지금 코드에서 결과 작업 안에서만 부른다) |
| 기록용 재판정 | core 결과 작업(U14) | `JOB_ATTEMPT` | 같음 |

- 예산 밖 재시도 · 무한 재시도는 없다. 다른 경로에서 부르게 되면 그 단위가 분류를 다시 정한다(BR5.2).
- 6.9의 '작업당 총 3회'는 U14가 시도 수를 바꿀 때 함께 고친다(BR3.2). 새 HTTP 오류 code는 없다 — 판정 · 제브 실패는 내부 사유(보류 · 막힘), 상황 생성 실패는 기존 `SCENARIO_GEN_FAILED`다(BR7.3).

### 10.4 판 리소스와 세션 고정

- 판정 질문 · 선택지(J1 ~ J6, A §7.3 · B §5.2)는 realtime `judgment/option-sets-v1.json`, 제브 시스템 프롬프트 · 7개 범주 · 모드(C §3.1 · §3.3)는 `guardrail/policy-v1.json`에 둔다. J7은 core 상황 생성 자체 검토 프롬프트(U10)라 realtime 판에 넣지 않는다.
- realtime은 기동 때 두 파일을 읽어 검증하고, 파일이 없거나 형식이 틀리면 기동을 거부한다(BR2.1 · 4절 시드 사전과 같은 방식).
- 세션을 받을 때 두 판의 번호와 패키지의 승인 문구 판(`approvedContent` · `approvedSpeech`)을 세션 계획에 고정하고, 세션 정산이 끝나면 놓는다. 판정 · 검사 기록 행은 고정된 판 번호를 함께 남긴다(BR2.2, 기록은 U11 · U13).

### 10.5 원문 일치와 제브 생략

- 원문 일치는 출력 전사에서 공백을 뺀 문자열이 세션에 고정한 승인 발화 또는 등록된 대본 문장과 **정확히 같을 때만** `MATCHED`다. 공백은 ASCII 공백(space · `\t` · `\n` · `\x0B` · `\f` · `\r`)만 뺀다(`PracticeSpeech.matches`와 같은 규칙). NBSP · 전각 공백 · 제로폭 문자가 끼면 `NOT_MATCHED`다. 다른 정규화 · 유사도 판단은 하지 않는다(BR1.4 · NFR2.1).
- 세션 계획이 없으면(고정 전 · 놓은 뒤) `UNKNOWN`이다. 대본 등록 전의 대본 문장은 `NOT_MATCHED`다.
- **`MATCHED`일 때만** 제브 검사를 생략할 수 있다. `UNKNOWN` · `NOT_MATCHED`는 제브 생략 근거가 아니며 새 발화로 검사한다(NFR2.2 · AC2.2.3, 판단은 U13).

### 10.6 승인 발화(`APPROVED_SPEECH`)

- 음성 파일 없이 GPT-Live 지시 + 원문 일치로 재생하는 승인 문구는 `content_type` `APPROVED_SPEECH`로 시드한다(`infra/seed/004-u8-reference.sql`). 기존 `APPROVED_AUDIO`(음성 파일 필수)는 그대로다 [U8 편차 (U8-e)].
- core는 활동 유형별 허용 목록에 있고 `APPROVED_SPEECH`이며 APPROVED 판이 있는 code만 시작 패키지의 선택 속성 `approvedSpeech`(`{contentVersionId, text}`)에 싣는다. 한 code는 `approvedContent` · `approvedSpeech` 중 하나에만 들어간다. realtime이 두 맵에서 같은 code를 받으면 `approvedSpeech` 쪽을 버리고 `APP_ERROR`(`errorKind` `APPROVED_SPEECH_DUPLICATE_CODE`)만 남긴다 — 문구 텍스트는 로그에 남기지 않는다 [U8 편차 (U8-b)].

| content_code | 넣는 활동 | 정본 |
|---|---|---|
| `HELP_OFFER` | PRACTICE | B §3.1 `D_OFFER_HELP` 예시(v2 APPROVED, v1 DRAFT '같이 살펴볼까?'는 RETIRED) |
| `D_REST_ACK_START` | FREE_TALK · PRACTICE | B §3.1 `D_REST_ACK_START` |
| `D_REST_ACK` | FREE_TALK · PRACTICE | B §3.1 `D_REST_ACK` |
| `COACH_SWITCH` | PRACTICE | B §8.5 코치 역할 전환 표현 |
| `PEER_RETURN` | PRACTICE | B §8.5 친구 역할 복귀 표현 |

- `D_REPROMPT`는 B §3.1에서 '직전에 아이에게 한 무중의 말(출력 전사 그대로)'을 다시 말하는 지시라 고정 원문이 없다. 그래서 시드하지 않는다.

### 10.7 비밀 이름

- `OPENAI_API_KEY`(Luna): core-api · realtime-activity가 각자 받는다. `JEV_API_KEY`(제브): realtime-activity만 받는다. 둘 다 선택(OPTIONAL)이고, 비었거나 공백뿐이면 미설정으로 보고 실제 어댑터를 끈다(U9 · U13).
- Neo4j는 기존 `NEO4J_AUTH`를 쓴다. GPT-Live의 `PROVIDER_API_KEY`는 FR12.2 전까지 그대로다. 7절의 core 예약 이름 `MUJOONG_TEXT_AI_API_KEY`는 쓰지 않는다(BR4.2). 값은 어디에도 쓰지 않고 이름만 둔다(R-F3).

### 10.8 U9 Luna 호출 감사 로그 (`LUNA_CALL` · `LUNA_STARTUP`)

Luna 호출부(core-api · realtime-activity 각자)는 공급자 시도마다 `LUNA_CALL` 한 줄을 남긴다. 공급자를 부르지 않은 실패(`FORBIDDEN_INPUT` · 시한 0)도 `attempt` 0으로 한 줄 남긴다. 요청 · 응답 본문, 전사, 이름, 키는 어떤 속성에도 담지 않는다(U9 BR6.1 · BR6.2 · R-F14). 정본은 `kr.mujoong.contract.log.EventCode` · `AttrKey`이고 `infra/bin/log-hygiene-check` 허용 표와 대조한다(`infra/ci/test-host-tools.sh`).

| 사건 | 속성 | 값 형식 | 값 |
|---|---|---|---|
| `LUNA_CALL` | `lunaPurpose` | ENUM | `JUDGMENT_LIVE` · `SCENARIO_GENERATION` · `SCENARIO_REVIEW` · `SEED_GENERATION` · `RESULT_TEXT` · `GOAL_CANDIDATES` · `REJUDGMENT` |
| `LUNA_CALL` | `lunaOutcome` | ENUM | `OK` · `AUTH_FAILED` · `RATE_LIMITED` · `SERVER_ERROR` · `REQUEST_REJECTED` · `TRANSPORT_ERROR` · `TIMEOUT` · `EMPTY_OUTPUT` · `LENGTH_EXCEEDED` · `REFUSED` · `SCHEMA_INVALID` · `FORBIDDEN_INPUT` · `MODEL_MISSING` |
| `LUNA_CALL` | `model` · `responseModel` · `promptVersion` | LABEL | `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`. 요청 모델, 응답 모델(받았을 때), 보낸 프롬프트 판 |
| `LUNA_CALL` | `sentFields` | NAME_LIST | 보낸 입력의 최상위 키 이름을 쉼표로 이은 값. 이름마다 `[A-Za-z][A-Za-z0-9_]{0,63}`, 최대 32개 |
| `LUNA_CALL` | `latencyMs` · `attempt` | NUMBER | 시도 지연(밀리초), 시도 번호 1 ~ 3(미호출 실패는 0) |
| `LUNA_STARTUP` | `lunaState` | ENUM | `ENABLED`(env `OPENAI_API_KEY` 있음) · `DISABLED_NO_KEY`(없음 · 공백) |

- 형식에 맞지 않는 LABEL · NAME_LIST 값은 조용히 버리지 않고 `UNKNOWN`으로 쓴다. `lunaOutcome`이 `OK`가 아니면 줄 수준은 WARN이다.
- 줄의 `component`는 `core-api/LunaClient` 또는 `realtime/LunaClient`다.
