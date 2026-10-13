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
| 서버 → 공급자 | `session.input_audio.append` | `audio`(base64, 기기 0x01 프레임 본문 960바이트 그대로) | ACTIVE 뒤 ~ CLOSE_TRANSPORT 전 기기 프레임을 순번 순으로(BR7.1 · R3-8). 그 밖의 프레임은 버린다 |
| 서버 → 공급자 | `session.close` | — | 종료 · FAILED 전이 뒤 공급자 세션 정리(BR6.10 ④) |
| 서버 → 공급자 | `session.response.cancel` | `responseId`(선택 — 없으면 만들던 응답 전부) | 끼어들기(PLAYBACK_MARK STOPPED · LOCAL_VOICE_ACTIVITY)와 아이 종료 의도 판정 때 만들던 · 재생하던 응답을 취소한다. 공급자는 그 응답의 남은 조각을 보내지 않는다(U3-R5 · R12 · U3-J11 · J24) [편차 (aa)] |
| 서버 → 공급자 | `session.instructions.correct` | `text`(서버 고정 문장) | 게이트 폐기(GATE_PATTERN · 미완 GATE_EMPTY · SEGMENT_OVERSIZE) 뒤 응답당 한 번 지시를 교정한다. 본문은 범주별 고정 템플릿이고 전사 · 걸린 패턴 · 미승인 원문을 넣지 않는다(U3-R7 · U3-J12 · SD-17) [편차 (aa)] |
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
| 온도 | 기록용 재판정 0, 생성 작업은 낮은 값. 모델 · 값은 설정(`mujoong.ai.*`) |
| 출력 검사 순서 | ① JSON 스키마 ② 인용 대조: `quote`가 그 utteranceSeq의 저장 전사와 글자 단위로 같아야 한다(앞뒤 공백만 무시). 다르면 그 인용을 버린다 ③ 금지 표현 2단계(6.6) |
| 실패 | 공급자 오류 · 시간 초과 · 스키마 실패는 결과 작업의 시도 실패다(timing `result.job-attempts`). 인용 · 금지 표현에 걸린 문장은 실패가 아니라 고정 템플릿으로 바꾼다 |

### 6.2 기록용 재판정 (연습 종료 확인 뒤, 온도 0)

- 입력: 전체 전사(seq · speaker · text), 목표마다 { goalSelectionId, displayName, coreFunction, judgmentCriteria }(BehaviorDefinitionLookup), 장면마다 { sceneNo(1 = ORIGINAL, 2 = NEW_SCENARIO), 상황 · 상대 말 · 기회 트리거 대본 }(ScenarioLookup), 진행 중 판정(record.practice.goals[].responses[]: scenarioId · responseType · utteranceSeq · label · notPerformedReason), 도움(cues[]: scenarioId · consentResponse · deliveredText · givenAt).
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

- 2차: 1차에 걸린 문장과 결과 문장 · 리포트 문장 전체를 텍스트 AI로 한 번 더 분류한다. 지시: "아래 문장이 금지 중 하나에 해당하는지 판단하라. 아이가 직접 한 말을 인용 부호 안에 그대로 옮긴 것은 금지가 아니다. 해당하면 분류와 문제 구간을 돌려준다." 출력 `{ "violation": true, "category": "EMOTION_CLAIM", "span": "…" }`.
- 걸리면: 결과 문장 · 리포트 항목은 고정 템플릿(6.3 · 6.5), 생활 팁은 보여 주지 않는다(FR6.3 '추가 관찰 필요').

## 7. U5a TextAiGateway (U5a-d)

Core의 사후 텍스트 AI 호출은 TextAiGateway 한 곳을 지난다. TextAiProvider의 실제 구현은 이 유닛에 없으며 기본값은 UNAVAILABLE다. 예약 환경 변수 이름은 MUJOONG_TEXT_AI_ENDPOINT, MUJOONG_TEXT_AI_MODEL, MUJOONG_TEXT_AI_API_KEY이다. 시간 · 재시도 값은 application.yml `mujoong.text-ai.*`(timing.md 9절)다. 값을 넣는 것만으로 활성화되지 않으며 공급자 어댑터와 통합 검증이 필요하다. Realtime 음성 설정과 공유하지 않는다.

입력 ScenarioPrompt는 목표 이름/행동 정의/기회 조건/상황 조건/관계, 나이, 관심사, 새 상황일 때 이전 상황 본문뿐이다. 아이/보호자 이름·식별자·전사·보호자 자유 입력·판정 기준은 보내지 않는다. 입력/출력 record의 문자열 표현과 예외는 본문을 숨긴다.

출력 GeneratedScenario는 scenarioText, triggerScript, relationType, opportunityCondition, modelVersion, promptVersion이다(상대 역할 지시는 모델에 받지 않는다, 아래). 모델이 준 validation 플래그는 받지 않는다. Gateway는 필드 크기·빈 값·모델/프롬프트 버전 및 7범주 금지 사전을 별도로 검사한다. 상황 본문 최대 8000자, 대사/기회 2000자, 관계 200자, 버전 80자(ASCII 식별자). 빈·잘못된 출력과 금지 표현은 기술 재시도 대상이 아니다.

Core는 고정 기회 조건과 트리거가 본문에 포함되는지 및 관계/기회 일치를 확인한다. 가상 상황 고지를 덧붙이고, 아이별 정규화 상황 지문으로 기존 ISSUED/EXPOSED 재사용을 막는다. NEW_SCENARIO의 같은 원문/문장부호 변형도 거절한다. 확인 실패는 한 번 다시 생성한 뒤 SCENARIO_GEN_FAILED(422)다. 두 번 모두 내용 확인에 실패했을 때만 그 요청 키를 실패로 굳히고, 이미 낸 상황과 같은 내용 · 다른 요청과의 저장 경합으로 끝난 422는 같은 키로 다시 요청하면 새로 만든다. 세션이 고정한 정의 버전을 쓸 수 없으면 400 VALIDATION_FAILED다. 의미적 재서술·언어 난도·정답 암시를 완전히 검사하는 것은 아니며 실제 공급자 도입 전에 승인 fixture 검토가 필요하다(U5a-J16/J17).

C10 partnerRoleText는 모델에 받지 않고 Core의 고정 역할 지시로 만든다(상대 역할만 수행, 아이의 답 대신 말하기/평가 금지, U5a-J22). 그래서 Gateway 검사와 공급자 출력에도 이 칸이 없다.

일시 실패에 한한 기술 재시도 1회(3초/호출, 250ms 간격)와 내용 재생성 1회는 모두 C10 전체 15초 안에서 수행한다. UTC/단조시계 남은 예산의 작은 쪽을 사용하며 실행 Future를 제한 시간에 취소한다. 취소를 무시하는 실제 공급자는 별도 통신 시한과 취소를 반드시 구현해야 한다. 생성된 상황은 내부 C10 응답에만 있고 보호자 목록에는 상황/판정/AI 원문이 없다.
