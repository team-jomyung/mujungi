# AI 입출력 계약 뼈대 (ai-io.md)

> 계약 9절(C14, WBS 1.7.3) + U1이 정하는 공급자 연결 · 선 형식 · 시드 사전 형식 · 승인 음성 형식. 소유: U5(D), 공급자 선 형식은 U1(B).
> 형식만 고정한다. 판정 기준 본문 · 프롬프트 · 금지 표현 사전 v1 · 대기 문구 본문은 D 담당 AI 입출력 계약 문서가 채운다(OQ4 · OQ14 · OQ15).
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
