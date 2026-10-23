# AI 입출력 계약 뼈대 (ai-io.md)

> 계약 9절(C14, WBS 1.7.3) + U1이 정하는 공급자 연결 · 선 형식 · 시드 사전 형식 · 승인 음성 형식. 소유: U5(D), 공급자 선 형식은 U1(B).
> 1 ~ 5절은 형식만 고정한다. 판정 기준 본문 · 프롬프트 · 금지 표현 사전 v1 · 대기 문구 본문은 D 담당 AI 입출력 계약 문서가 채운다(OQ4 · OQ14 · OQ15). 6절은 U6 분석 AI 작업의 입력 · 출력 · 지시 요점 · 검사 규칙까지 정한다.
> 공급자 선 형식(2 · 3절)은 U11이 실호출 3회(2026-10-16 ~ 17)로 관측한 형식으로 바꿨다(편차 (U11-a), 옛 J110 가정은 3.3절). 메시지 · 필드마다 출처를 적고, 관측되지 않은 것은 '미확인'이다.

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
| 키 | 환경 변수 `OPENAI_API_KEY`(GPT-Live 음성 중계와 Luna 판정이 같은 이름, 값은 어디에도 쓰지 않음, 편차 (U11-c)). realtime은 이 키를 **demo 프로필에서만 필수**로 본다(비면 기동 거부). 다른 프로필(CI · 검증 · 로컬)은 키 없이 가짜 공급자에 연결하고 연결 요청에 `Authorization` 헤더를 싣지 않는다 — 가짜 공급자는 헤더를 보지 않는다. 키가 있으면 `Authorization: Bearer <키>` 헤더로만 보낸다. 기기 · 프런트 번들 · 응답 · 로그에 나가지 않는다(BR7.2 · BR8.7) |
| 연결 | 세션마다 별도 WSS, 별도 가상 스레드가 연다(R3-1). 연결 시한 `mujoong.provider.connect-timeout`(10초, 20초 시한 이하). 실패 · 시한 초과는 FAILED(PROVIDER_UNAVAILABLE) |
| 오디오 | 입출력 PCM16LE · 모노(J27). 세션 시작 `audio.format{type: "audio/pcm", rate}`의 `rate`는 24000이고 **실호출 3회로 수락이 확인됐다**(16kHz도 수락). 표본율 변환은 하지 않는다. 기기 프레임 본문을 변환 없이 중계한다(J131 무변환 확인) |
| 전송 최소화 | 공급자로 가는 요청은 `ProviderRequestFactory` 한 곳에서만 만들고 요청 레코드는 지시문 문장 · 음성 프레임뿐이다. 아이 정보(이름 · 나이 · 관심사)는 별도 필드 없이 지시문 문장 안에 따옴표로 묶은 데이터로만 들어간다. 08 입력 네 칸 · 보호자 · 아이 · 기기 · 세션 id · 이메일 · 동의 · 판정 기준은 보내지 않는다(SD-20 · NFR3.1 · NFR3.2) |
| 로그 | 공급자로 가는 요청 본문 · 오는 이벤트 본문 · 전사를 로그에 쓰지 않는다(SD-15 · BR8.7) |

## 3. 공급자 선 형식 (U11 실호출 관측 — 편차 (U11-a), SDK 없이 JDK `java.net.http.WebSocket`, 가짜 공급자도 같은 형식)

텍스트 프레임 JSON 한 개 = 메시지 한 개, 공통 필드 `type`. 음성은 base64로 인코딩한 PCM16LE 24kHz 모노 바이트다. 모르는 type은 무시하고 개수만 센다(type 이름 · 본문은 남기지 않는다).
출처 표시: **실호출** = U11 실호출 3회(합성 음성 · 실제 API, `[실호출-합성]`)에서 관측 · 공급자 오류 문장에서 확인, **문서** = 공급자 공개 문서(live-conversations · voice-websockets)에만 있고 우리 실행에서는 관측되지 않은 것, **미확인** = 관측도 문서도 없는 것(Build and Test 실호출에서 확인). 관측하지 않은 필드는 적지 않는다.

공급자가 지원하는 클라이언트 메시지 type은 열다섯 개다(실호출 오류 문장): `session.start` · `session.update` · `session.close` · `session.input_audio.append` · `session.input_audio.mute` · `session.input_audio.unmute` · `session.instructions.append` · `session.thinking.append` · `session.commentary.append` · `session.output_audio.fixture.play` · `response.create` · `response.item.create` · `decision.create` · `decision.item.create` · `decision.item.delete`. 이 밖의 type은 `error{type: invalid_request_error, code: invalid_value, param: type}`로 거절된다. 응답 취소(`session.response.cancel`)와 지시 교정(`session.instructions.correct`), `session.response.create`는 목록에 없다. **서버는 아래 네 type만 보낸다**(`ProviderRequestFactory`가 이 네 레코드만 만든다). `response.create`는 Responses 위임 세션 전용이라 우리 세션(client 위임)에는 `error`("requires a session with Responses delegation")로 거절되고(실호출), `session.update`도 Responses 위임 설정에만 쓸 수 있어 보내지 않는다(문서). 자동 응답 · 말 끝(turn detection) 설정은 없다(문서).

| 방향 | type | 필드 | 출처 · 쓰임 |
|---|---|---|---|
| 서버 → 공급자 | `session.start` | `session{model, instructions, audio{format{type: "audio/pcm", rate: 24000}}}` — `model`은 코드 상수, `instructions`는 서버 템플릿 지시문(공통 역할 문장 + 아이 정보는 따옴표로 묶은 데이터로만 + 세션 계획의 활동 종류별 본문 판 `live-prompts/{free-talk,practice}.v<n>.txt`. 쓴 판 이름과 본문 sha256은 세션 사건 `LIVE_INSTRUCTIONS`로만 남고 본문은 남기지 않는다 [U11 편차 (U11-e)]) | 실호출(중첩 모양 · 24kHz 수락). 연결 직후 한 번. 응답 `session.started`가 ACTIVE 전이 조건의 하나다(BR6.8) |
| 서버 → 공급자 | `session.input_audio.append` | `audio`(base64, 기기 0x01 프레임 본문 960바이트 그대로) | 실호출. ACTIVE 뒤 ~ CLOSE_TRANSPORT 전 기기 프레임을 순번 순으로(BR7.1 · R3-8). 그 밖의 프레임은 버린다. 참여 확인(WAITING_CHILD)부터 중계하고 원음 기록은 ACTIVE부터다(U4b-J2) |
| 서버 → 공급자 | `session.instructions.append` | `event_id`(서버 발급, 확인 사건의 `client_event_id`로 돌아온다) · `delegation_id`(**항상 있어야 한다, 값은 `null`**) · `content`(지시문, 500토큰 이하) | 실호출. `delegation_id` 키가 없으면 공급자가 `error{missing_required_parameter, param delegation_id}`로 거절한다 — 이 프로젝트 JSON 코덱이 null 필드를 쓰지 않으므로 이 속성만 항상 쓰게 한다. 옛 본문 필드 `text`는 없다. **서버의 모든 발화는 이 지시 하나로 시작한다**(참여 뒤 첫 응답 · 확인 뒤 이어 가기 · 연습 발화 · 연습 `requestResponse` · 게이트 교정 · 판정 뒤 지시, 아래 '서버 발화'). 전사 · 걸린 패턴 · 미승인 원문은 넣지 않는다 |
| 서버 → 공급자 | `session.close` | — | 종료 · FAILED 전이 뒤 공급자 세션 정리(BR6.10 ④). 응답 `session.closed`(실호출) |
| 공급자 → 서버 | `session.started` | `session{id, ...}` | 실호출. `session.id`를 공급자 세션 id로 읽는다 |
| 공급자 → 서버 | `session.output_audio.delta` | `delta`(base64). **응답 id · 시간 정보가 없다** | 실호출. AI 말 음성 조각. 게이트 구간 버퍼에만 쌓는다(BR7.3 · BR11.6). 구간이 확정 · 통과될 때까지 기기로 보내지 않는다(BR7.4). 한 응답이 조각 300 ~ 350개(30 ~ 35초)일 수 있다 |
| 공급자 → 서버 | `session.output_transcript.delta` | `start_ms` · `end_ms` · `delta` · `event_id` | 실호출. AI 말 전사 조각. 게이트 구간 버퍼에만 쌓는다. **음성보다 앞서 도착하고 그 폭이 늘어난다**(전사 `end_ms`와 도착 때 오디오 시계의 차 0.3 ~ 2.1초). `start_ms` · `end_ms`는 세션 전사 시간선의 ms다 |
| 공급자 → 서버 | `session.input_transcript.delta` | `start_ms` · `end_ms` · `delta` · `event_id` | 실호출. 아이 전사 조각(PARTIAL, BR7.5). **완료 사건이 없다** — 서버가 조각을 묶어 확정한다(아래). `confidence`는 오지 않는다 |
| 공급자 → 서버 | `session.instructions.appended` | `event_id` · `client_event_id` · `start_ms` · `end_ms` | 실호출. 지시 확인 사건. "세션 시간선이 추가한 지시의 추정 끝에 닿을 때" 온다(문서). `client_event_id`가 우리가 실은 `event_id`와 맞고, `end_ms`가 지시 뒤 새 말이 시작할 수 있는 전사 시간선의 하한이다 |
| 공급자 → 서버 | `session.usage.updated` | `usage{seconds}` | 실호출. 누적 사용 초(해석기가 읽고 실호출 도구 보고서가 기록, 서버 지표에는 쓰지 않는다) |
| 공급자 → 서버 | `error` | `error{type, code, message, param}` | 실호출. 앱은 `code`만 읽고 `message` 원문은 읽지도 남기지도 않는다(R-F14). `session.started` 전이면 PROVIDER_UNAVAILABLE |
| 공급자 → 서버 | `session.closed` | `reason`(선택) | 실호출. 공급자가 세션을 닫음 |
| 공급자 → 서버 | `session.delegation.created` | — | 문서(모델이 위임할 때). 우리 세 번의 실행에서는 오지 않았다. 오면 모르는 type으로 이름만 센다 |

**없는 사건**(실호출 3회 · 문서 모두 확인): 입력 전사 완료(`session.input_transcript.done`), 말 끝(`speech_stopped`), 응답 끝 · 응답 경계(`session.output.done` 등), 응답 id, 입력 전사 `confidence`. 앱은 이 이름들을 읽지 않는다(와도 모르는 type이다).

**관측한 동작**(실호출, 표본 1 ~ 2):
- 모델은 **아이 말이 끝나기 전에** 자동으로 응답을 시작한다(마지막 입력 소리 프레임보다 3.5초 앞). 자동 응답은 끌 수 없다(문서).
- 지시는 **진행 중인 말을 끊을 수 있다**(문서). 유휴 중 보낸 지시는 말을 저절로 시작한다(첫 조각 +32 ~ +69ms). 말하는 중 보낸 지시는 **낡은 말이 1.1 ~ 1.8초 더 이어진 뒤 새 말이 쉼 없이**(조각 사이 최대 203ms) 시작한다 — 낡은 꼬리와 새 말을 조각 사이 무음으로 가를 수 없다. 확인 사건은 낡은 꼬리가 끝나기 전에 도착한다.
- 추가한 지시는 **이후 응답에도 남는다**("이제부터 말을 시작할 때마다 …" 지시의 표식이 평범한 발화 1회 · 2회 뒤에도 나왔다). 지우는 방법이 없다. "이번 한 번만 …" 지시는 그 한 번만 적용됐다(표본 1) — 그래서 모든 지시문이 "이번 한 번만 …, 그 뒤에는 평소처럼 …"으로 범위를 스스로 닫는다.
- 응답 안 조각 사이 쉼은 최대 234ms(800ms 이상 0번). 전사 `start_ms`는 도착 순서대로 늘어났고 모두 출력 오디오 시계 구간에 들었다.

- **서버 발화(설계 개정 A2 · A6)**: 응답 요청 type이 없어 지시 하나가 곧 발화다. 모든 서버 발화 경로는 세션 직렬 큐 하나를 지나 `append`로 나간다(큐는 `append` 순서를 지키는 일만 한다). 지시를 큐에 넣는 순간 출력 세대를 올려 낡은 조각을 바로 버린다. 모든 지시문은 `InstructionTemplates`가 만들고 ① 아주 짧은 맞장구("먼저 '음,'이라고 짧게 말한 뒤 …")로 시작하게 하고(연습 확장점이 만든 지시 — 승인된 발화를 그대로 읽는 발화 · 안내 — 는 원문 일치 검사 때문에 맞장구를 더하지 않고 범위를 닫는 문장만 붙인다) ② "이번 한 번만 위 지시를 따르고, 그 뒤에는 평소처럼 …"으로 끝나 범위를 닫는다. 게이트 교정 지시는 통과한 구간 없이 연달아 두 번까지만 보낸다(교정이 곧 발화라 끝없는 연쇄를 막는 상한).
- **출력 세대 · 새 말 시작(설계 개정 A4)**: 세션마다 세대 번호를 올리기만 한다. 오디오 시계 = 이 세션에서 받은 출력 음성의 누적 바이트 / 48(ms). 지시를 보내며 올린 세대는 ① `session.instructions.appended`(`client_event_id` 일치)의 `end_ms`를 `ackEndMs`로 기록하고 ② 그 뒤 처음 오는 **출력 전사 조각 중 `start_ms >= ackEndMs`**를 새 말의 시작(`newStartMs`)으로 보며 그 조각부터의 전사는 받고 ③ 오디오 시계(조각이 시작하는 위치)가 `newStartMs + mujoong.timing.provider.new-speech-margin`(timing.md 14절, 임시 700ms)에 닿는 첫 음성 조각부터 받는다. 첫 낱말이 잘려도 낡은 꼬리가 새는 것보다 낫다(fail-closed). 확인 사건이 `mujoong.timing.judgment.response-ready`(10초, 지시 송신부터) 안에 안 오면 사유 `OutputAckMissing`을 남기고 가장 최근 지시를 보낼 때의 오디오 시계 이후 `start_ms`인 전사로 ②를 대신해 계속하며, 새 말 전사가 시한 안에 없으면 사유 `OutputNewSpeechNotObserved`를 남기고 그 세대를 닫는다(아이는 아무 소리도 듣지 않는다 = 안전 쪽 실패). 같은 세대에서 지시를 다시 보내면(세대가 다시 오른다) 마지막 지시의 확인 기준으로 다시 맞춘다.
- **조용할 때 보낸 지시(설계 개정 A4-b, 3회차 V2)** [PDS 편차 (PDS-V2)]: 지시를 보낼 때 출력이 조용했으면 낡은 꼬리가 없으므로 A4 ② · ③을 쓰지 않는다. 조용함은 ⓐ 공급자 리더가 넣고 실행기가 아직 처리하지 않은 출력 조각이 없고 ⓑ 마지막 출력 조각(버린 · 보류한 조각 포함, 무음 조각 제외) 뒤로 `mujoong.gate.segment-quiet`(800ms)가 지났거나 받은 출력 조각이 아직 없을 때다. 이때는 확인 사건을 기다리지 않고 보낸 뒤 처음 오는 **시각 있는 출력 전사 조각(`start_ms >= 0`)이나 첫 음성 조각**을 새 말의 시작으로 보고 그 조각부터 여유 없이 받는다(시각 없는 전사 조각은 지금처럼 새 말을 열지 않는다). 실음성 실측(`docs/practice-dialogue-standard.md` 12.3)에서 유휴 중 보낸 지시의 새 말은 확인 사건보다 먼저 시작했고 첫 조각의 `start_ms`가 확인 `end_ms`보다 앞서, A4 ②가 첫머리를 잘랐다. 말하는 중 보낸 지시는 A4 그대로다(확인 `end_ms` + `new-speech-margin`). 게이트 교정은 구간을 봉인한 뒤 보내므로 늘 조용할 때 보낸 지시다. 지시마다 판단을 세션 사건 `OUTPUT_NEW_SPEECH`로 남긴다: `eventId` · `reason`(세대를 올린 까닭) · `start`(`QUIET` · `SPEAKING`) · `sentClockMs`(보낸 때의 오디오 시계) · `ackStartMs` · `ackEndMs` · `firstTranscriptStartMs` · `firstTranscriptClockMs`(받은 첫 전사 조각의 `start_ms` · 그때의 오디오 시계) · `audioFromClockMs`(받기 시작한 음성의 오디오 시계) · `discardedTranscriptChunks` · `discardedTranscriptChars` · `discardedAudioMs` · `heldChunks` · `outcome`(`RECEIVING` · `NEW_SPEECH_NOT_OBSERVED` · `NO_AUDIO` · `SUPERSEDED`), 모르는 값은 null이고 원문은 없다(R-F14). 조용할 때 시작한 말은 확인 사건이 오거나 다음 세대가 될 때 사건을 쓴다. 남은 위험: 조용할 때 보낸 지시 직후 자동 응답이 먼저 시작하면 그 말을 새 말로 받는다 — 승인 원문 읽기는 원문 불일치(GATE_PATTERN)로 막히고, 생성형 줄은 제브(연습 모드) · 기본 게이트를 지난 것만 들린다.
- **연습 발화 지시는 조용할 때 보낸다(4회차 R2)** [PDS 편차 (PDS-V3)]: 새 연습 흐름(말 간격을 맞추는 출력권 연습, `PracticeSessionExtension.pacesSpeech`)의 GPT-Live 연습 발화 지시 — 승인 원문 읽기(`PracticeOutput.speak`가 미리 만든 음성을 쓰지 못할 때)와 생성형 역할 지시(`speakGenerated`) — 는 위 조용함(ⓐ · ⓑ)일 때만 곧바로 보낸다. 말하는 중이면 앞 소리를 멈추고 공급자 출력을 막은 채 `mujoong.timing.provider.instruction-defer-max`(timing.md 14절, 임시 3초)까지 미루고, 처음 조용해질 때(마지막 조각 뒤 `segment-quiet`가 차는 때) 조용 시작으로 보내며, 상한이 지나면 지금처럼 말하는 중에 보낸다(A4). 실측(`docs/practice-dialogue-standard.md` 16.2 B3)에서 GPT-Live는 아이 말마다 스스로 지어낸 말을 했고, 그 말 도중에 보낸 지시 8번 중 3번이 첫머리 잘림 · 새 말 없음으로 틀어졌다(조용할 때 보낸 6번은 모두 정상). 미룬 지시는 새 연습 발화(바꾼다) · `cancelSpeech` · 아이의 새 말 시작(새 입력 전사 묶음 — 재생 중 끼어들기처럼 `INTERRUPTED`로 닫는다) · 확장의 출력 보류 · 기술 일시정지 · 종료 확인 · 종료 판정 · 연습 끝 · 세션 끝이 버리고, 낡은 지시는 보내지 않는다. 연습 밖 출력 보류(참여 확인 · 종료 확인 · 판정 턴) 중의 요청은 미루지 않는다. 연습 밖 지시(참여 안내 · 게이트 교정 · 판정 뒤 다음 행동 · 자유대화 응답 · `instructProvider`)와 레거시 연습 확장은 바뀌지 않는다. **한 번 다시 보내기**: 보낸 연습 발화가 새 말 없이 끝나거나(`OutputNewSpeechNotObserved`, 송신 실패 포함) 저절로 끝난 원문 읽기가 원문 대조로 버려지면(GATE_PATTERN, 아이 입력 재개로 닫은 구간은 빼고) 실패를 알리기 전에 같은 지시를 같은 길(미루기 포함)로 한 번 다시 보낸다(슬롯마다 한 번, 두 번째 실패는 지금처럼 `FAILED`). 세션 사건 `OUTPUT_NEW_SPEECH`에 `deferredMs`(미룬 시간, 미루지 않았으면 0) · `deferEnd`(`QUIET` · `CAP`, 미루지 않았으면 null) · `resend`(다시 보낸 지시인가)를 더한다. 출처 사건 `PRACTICE_SPEECH_SOURCE`는 요청마다 하나이고 미룬 지시는 보낼 때 · 버릴 때(사유 `DEFER_ABANDONED`) 남긴다(원문 없음 R-F14).
- **지시 없이 올린 세대(설계 개정 A5)**: 끼어들기 · 종료 의도 · 연습 폐기 · 게이트 폐기 · 출력 보류 해제는 마지막 조각 시각과 세대를 올린 시각 중 늦은 쪽에서 `mujoong.gate.segment-quiet`(800ms)가 지나면 끊김을 확인하고 그 뒤 처음 오는 조각부터 받는다. 출력 보류 중(참여 확인 · 종료 확인 질문 · 연습 보류)에는 단계와 관계없이 조각을 버린다. 응답 취소 type이 없으므로 이 경우 공급자로는 아무것도 보내지 않고 낡은 응답은 자연 종료까지 이어진다.
- **구간**: 받는 단계에서 마지막 조각 뒤 `segment-quiet`가 지나면 구간을 '완전'으로 봉인해 게이트에 넣는다(응답 끝 사건이 없다). 응답이 30초를 넘을 수 있어 구간 상한(20초 분량)으로 자르는 경로가 기본 경로다. 세대가 바뀌어 닫은 구간 · 첫 조각을 놓친 구간 · 아이 입력이 끊김 전에 재개되어 닫은 구간은 불완전이라 게이트가 GATE_EMPTY로 폐기한다(SD-17 3단계 fail-closed). 지시 뒤 전사만 받고 음성이 여유 시각에 닿지 못한 채 끝난 구간도 같다.
- **입력 전사 묶음(설계 개정 A7)**: 서버가 `session.input_transcript.delta`를 묶고, `mujoong.timing.provider.input-transcript-quiet`(timing.md 14절, 임시 1500ms) 동안 조각이 없으면 그 묶음을 확정 전사로 만든다. 확정 전사는 지금 확정 전사 입구(`InputTranscriptDone`, 신뢰도 null = 확실)와 같은 길로 흘러 참여 확인 · 종료 의도 · 연습 흐름을 바꾸지 않는다. 지연 출처는 `INPUT_TRANSCRIPT_FINAL` · `LAST_INPUT_FRAME`이다. 아이가 길게 쉬면 한 발화가 둘로 나뉠 수 있다.

### 3.2 가짜 공급자

백엔드 시험 `FakeProviderWsServer` · 인메모리 `FakeProviderPort` · C안 `tools/fake-provider/` 셋은 위 관측 형식만 내고 공급자처럼 엄격하다: 중첩 `session.started{session{id}}`, 응답 id 없는 `delta` 음성, 전사 조각 `{start_ms, end_ms, delta, event_id}`(출력 전사는 음성보다 앞서 도착), 입력 전사 완료 · 말 끝 · 응답 끝 사건 없음, 오류는 `error{error{type, code, param}}`. 지원 목록 밖 type은 `error{invalid_request_error, invalid_value, param type}`, `delegation_id` 키가 없는 `append`는 `error{missing_required_parameter, param delegation_id}`, `response.create`는 `error{invalid_request_error, "response.create requires a session with Responses delegation"}`로 거절한다. 올바른 `append`에는 확인 사건 `session.instructions.appended{client_event_id, start_ms, end_ms}`를 곧바로 돌려주고, 지시에 대한 새 말은 확인 `end_ms` 뒤에서 낡은 꼬리를 흉내 낸 채움 다음에 시작한다. `FakeProviderWsServer` · C안 가짜의 채움은 디지털 무음이라 앱이 무음 조각으로 분류해 오디오 시계만 늘린다 — 조용할 때 보낸 지시(A4-b)에서도 들리지 않는다. 인메모리 `FakeProviderPort`는 실행기와 같은 조용 간격(`segment-quiet`)으로 말하는 중 받은 지시에만 낡은 꼬리를 음성 조각으로 보내고(앱이 여유 시각까지 버린다), 조용할 때 받은 지시에는 같은 길이의 무음 조각만 보낸다(3회차 V2). 실제 공급자처럼 조용할 때 받은 지시의 첫 전사가 확인 `end_ms`보다 앞서 시작하는 모양은 인메모리 대역 시험(`OutputGenerationTest` · `GenerationSendExecutorTest`)이 직접 만든다. 지속형 지시(문장에 "이번 한 번만"이 없는 것)는 남고 한 번만 지시는 남지 않는 단순 모델을 `FakeProviderWsServer`가 흉내 낸다. 받은 입력 음성 바이트 수 · 해시와 보낸 출력 음성 해시를 기록하고(J131), `session.instructions.append`는 본문 길이 · 해시 · `delegation_id` 키 유무만 기록한다(원문 저장 없음). `Authorization` 헤더는 보지 않고 Bearer 형식인지만 기록한다 — 키가 비면 헤더가 없고 연결은 받는다. 시나리오: 정상 · 금지 패턴 응답 · 연결 거부 · `session.started` 없음 · 지연 · 연결 단계 무응답. 턴 종류 `join`은 참여 확인 대답("응") 입력 전사 조각만 보내고 AI 출력은 없다(`--turns join,normal,forbidden`). `--confidence` 옵션은 없다(신뢰도는 오지 않는다).

### 3.3 대체된 옛 가정(편차 표 보존)

U11 이전 가정은 아래 편차들로 계약에 들어 있었고 실호출 관측 형식이 대신한다(편차 (U11-a)). 편차 표의 옛 행은 지우지 않고 '(U11-a)로 대체'로 표시했다.

- 옛 응답 id 필드와 `session.output.done`을 그 응답의 전사 완료 신호로 쓰던 규칙은 출력 세대와 조용함 봉인(위)으로 대체됐다 [편차 (z)].
- 옛 `session.response.cancel`(끼어들기 · 종료 의도), `session.instructions.correct`(게이트 폐기 교정), 확정 전사의 선택 필드 `confidence`는 앞의 둘이 공급자 지원 목록 밖이라 서버 출력 세대와 `session.instructions.append`로 대체됐고, `confidence`는 오지 않는다 [편차 (aa)].
- 옛 `session.response.create`(참여 확인 뒤 첫 응답)는 응답 요청 type이 아예 없는 구조로 대체됐다 — 첫 응답도 지시 하나로 시작한다 [U4b 편차 (U4b-a)].
- 이 PR 안의 중간 가정(`response.create` + `append` 순서, 끊김 허가 · 대조 · `auto-response-start`, `text` 본문 필드, 입력 전사 완료 · 말 끝 사건)은 실호출 3회로 폐기됐다(병합된 적 없다).

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

결과 · 리포트 호출은 `mujoong.text-ai.result.request-timeout`과 작업 시도 잔여 시간 중 작은 값으로 제한한다. 게이트웨이 재시도 없이 작업당 최초 1회와 추가 최대 3회, 총 4회 시도하며 지연은 5·15·30초다(U14·FR7.6). INSUFFICIENT 사전 판정이면 AI 없이 DONE이다. 생활 팁은 레거시 목표 · 검증 후보 behaviorKey와 승인 항목의 교집합을 서버에서 찾고, 생성 후보를 임의 behaviorKey로 바꾸지 않는다. title · condition · content · limitation에 1차 금지 사전을 적용한다.

## 7. U5a TextAiGateway (U5a-d)

Core의 사후 텍스트 AI 호출은 TextAiGateway 한 곳을 지난다. TextAiProvider의 실제 구현은 이 유닛에 없으며 기본값은 UNAVAILABLE다. 예약 환경 변수 이름은 MUJOONG_TEXT_AI_ENDPOINT, MUJOONG_TEXT_AI_MODEL, MUJOONG_TEXT_AI_API_KEY이다. 시간 · 재시도 값은 application.yml `mujoong.text-ai.*`(timing.md 9절)다. 값을 넣는 것만으로 활성화되지 않으며 공급자 어댑터와 통합 검증이 필요하다. Realtime 음성 설정과 공유하지 않는다.

입력 ScenarioPrompt는 목표 이름/행동 정의/기회 조건/상황 조건/관계, 나이, 관심사, 새 상황일 때 이전 상황 본문뿐이다. 아이/보호자 이름·식별자·전사·보호자 자유 입력·판정 기준은 보내지 않는다. 입력/출력 record의 문자열 표현과 예외는 본문을 숨긴다.

- **열린 점(U10에서 정함):** U9 Luna 어댑터(`LunaTextAiProvider`)는 지금 관심사를 공급자로 보내지 않는다. `interests`가 10.10 보내기 전 정리의 금지 키(기존 결과 게이트웨이 `PRIVATE_KEYS`)라서 넣어도 지워지기 때문이다. 이 절의 입력 목록과 금지 키가 부딪히므로, 관심사를 보낼지는 U10이 새 스키마로 바꿀 때 정한다.

출력 GeneratedScenario는 scenarioText, triggerScript, relationType, opportunityCondition, modelVersion, promptVersion이다(상대 역할 지시는 모델에 받지 않는다, 아래). 모델이 준 validation 플래그는 받지 않는다. Gateway는 필드 크기·빈 값·모델/프롬프트 버전 및 7범주 금지 사전을 별도로 검사한다. 상황 본문 최대 8000자, 대사/기회 2000자, 관계 200자, 버전 80자(ASCII 식별자). 빈·잘못된 출력과 금지 표현은 기술 재시도 대상이 아니다.

Core는 고정 기회 조건과 트리거가 본문에 포함되는지 및 관계/기회 일치를 확인한다. 가상 상황 고지를 덧붙이고, 아이별 정규화 상황 지문으로 기존 ISSUED/EXPOSED 재사용을 막는다. NEW_SCENARIO의 같은 원문/문장부호 변형도 거절한다. 확인 실패는 한 번 다시 생성한 뒤 SCENARIO_GEN_FAILED(422)다. 두 번 모두 내용 확인에 실패했을 때만 그 요청 키를 실패로 굳히고, 이미 낸 상황과 같은 내용 · 다른 요청과의 저장 경합으로 끝난 422는 같은 키로 다시 요청하면 새로 만든다. 세션이 고정한 정의 버전을 쓸 수 없으면 400 VALIDATION_FAILED다. 의미적 재서술·언어 난도·정답 암시를 완전히 검사하는 것은 아니며 실제 공급자 도입 전에 승인 fixture 검토가 필요하다(U5a-J16/J17).

C10 partnerRoleText는 모델에 받지 않고 Core의 고정 역할 지시로 만든다(상대 역할만 수행, 아이의 답 대신 말하기/평가 금지, U5a-J22). 그래서 Gateway 검사와 공급자 출력에도 이 칸이 없다.

새 흐름 대본의 `scenes[].partnerRole`은 지시가 아니라 짧은 역할 이름이다(예: 친한 친구). 레거시 `partnerRoleText`만 고정 역할 지시로 남는다(10.15) [U12 편차 (U12-b)].

일시 실패에 한한 기술 재시도 1회(3초/호출, 250ms 간격)와 내용 재생성 1회는 모두 C10 전체 15초 안에서 수행한다. UTC/단조시계 남은 예산의 작은 쪽을 사용하며 실행 Future를 제한 시간에 취소한다. 취소를 무시하는 실제 공급자는 별도 통신 시한과 취소를 반드시 구현해야 한다. 생성된 상황은 내부 C10 응답에만 있고 보호자 목록에는 상황/판정/AI 원문이 없다.

## 8. U5b 연습 발화 · Cue · 재생 증거

실제 연결은 `RuntimePracticeExtension` → `PracticeOutput.speak(PracticeSpeech)` → `PracticeInstruction.forSpeech` → 기존 `ProviderRequestFactory.practiceInstruction` 입구다. `PracticeSpeech`는 서버 내부에서만 `correlationId` · `scenarioId` · `generation` · `kind` · `partnerRole` · `text`를 가진다. kind는 `SCENARIO` · `HELP_QUESTION` · `CUE`이며, 상대 역할은 200자 이하, 발화는 10201자 이하이고 빈 값과 허용하지 않은 제어 문자를 거절한다. 공급자 팩토리 앞에서 식별자를 버리고 역할·발화 JSON과 고정 템플릿만 남긴다. 기존 자유 지시문 생성자의 500자 제한은 유지하고, 타입 검증을 통과한 발화 템플릿만 JSON 이스케이프를 포함한 64000자 상한을 쓴다. 문자열 표현에는 본문을 넣지 않는다.

| 경계 | 실제 입력 · 출력과 검사 |
|---|---|
| 상황 발화 | Core가 검증한 상황의 `partnerRoleText`, `scenarioText + 줄바꿈 + triggerScript`를 사용한다. 도움 질문은 고정 문장 ‘도움을 받아 볼까?’다. 상황 발화에 정답 · 시범 · 도움을 덧붙이지 않는다. |
| Cue 생성 입력 | `CueBrief{situationSummary, partnerUtterance, focus}`만 `CueProvider.generate`에 넘긴다. 현재 focus는 스스로 다음 말을 떠올리게 하는 고정 지시다. 판정 기준 · 정답 문장 · 보호자 원문 · 계정 식별자 필드는 없다. |
| Cue 생성 출력 | 비동기 `Result{text, approved, containsAnswerSentence}`다. 현재 CUE 단계 · 동의 ACCEPTED · 같은 scenarioId/세대 · 아직 주지 않은 도움인지 확인하고, `approved=true`, `containsAnswerSentence=false`일 때만 발화로 보낸다. 오류 · 승인 실패 · 정답 포함은 재생 실패 경로로 간다. |
| 공급자 송신 | 서버 내부 식별자는 보내지 않는다. `session.instructions.append.content`에는 고정 지시와 JSON 데이터 `{partnerRole, speech}`를 싣고 끝에 범위를 닫는 문장을 붙인다(승인된 발화를 그대로 읽는 지시라 맞장구는 더하지 않는다). 응답 요청 type이 없으므로 이 지시 하나가 발화를 시작한다: 응답 취소 없이 출력 세대를 올리고 지시를 서버 송신 직렬 큐(3절)로 보내며, ACTIVE 밖이면 요청을 버린다. 확인 사건 · 새 말 전사가 시한 안에 오지 않거나 송신이 실패하면 확장에 실패를 알린다. |
| 공급자 출력 | 기존 OutputGate를 통과하고, 공백을 제거한 실제 전사가 승인된 `PracticeSpeech.text`와 정확히 같아야 기기로 보낸다. 전사/음성 누락 · 내용 불일치 · 게이트 거절 · 송신 실패를 성공으로 취급하지 않는다. 실제 playbackId를 서버 내부 발화와 연결한다. |
| 미리 만든 대본 줄 음성(3회차 V1) | 실행부가 그 줄(말하는 사람 표시 · 원문)의 음성을 미리 만들어 두었으면 위 공급자 송신 · 공급자 출력 대신 그 음성을 같은 게이트(기본 검사 → 송신 직전 재확인)와 같은 출력 묶음(`OUTPUT_BEGIN.source=SCENARIO_SCRIPT`)으로 보낸다. 재생 증거 · 말풍선 · 대화 기록은 같다. 못 쓰면 위 길 그대로다(10.16) [PDS 편차 (PDS-V1)]. |
| 기기 재생 증거 | PLAYBACK_MARK의 STARTED → STARTED, COMPLETED → COMPLETED, STOPPED → INTERRUPTED, FAILED → FAILED로 구분한다. PROGRESS는 단계 완료가 아니다. `PlaybackFact`는 발화의 상관 ID · 상황 ID · 세대와 서버 수신 시각을 보존한다. 누락 · 만료도 완료로 만들지 않는다. |

상황 COMPLETED의 **서버 수신 시각**이 `scenarios[].exposedAt`과 첫/새 상황 반응 기회의 시작이다. 도움 질문 COMPLETED부터 동의 대기, Cue STARTED부터 `cues[].givenAt`, Cue COMPLETED부터 도움 후 반응 기회를 계산한다. 사건을 큐에서 나중에 처리해도 수신 시각을 처리 시각으로 바꾸지 않는다. 거부 · 무응답 · 재생 전 실패의 Cue 행에는 `givenAt`이 없으므로 Cue 배열 길이는 실제 도움 제공 횟수가 아니다.

기술 일시정지와 STOP_CONFIRM이 겹치면 합집합 시간만 반응 기회에서 뺀다. 재생 사실이 지연 전달되어도 수신 뒤 실제 정지 구간만 `pausedFor`로 넘기고 일반 큐 지연은 더하지 않는다. 노출/도움 원본 시각과 이미 수락한 판정의 전체 시한은 바꾸지 않는다.

보호자 진행 조회는 `practice{goalIndex, goalCount, goalName, stage}`뿐이다. 상황 · Cue 원문, 진행 중 판정과 판정 기준은 보내지 않는다. 내부 `record.practice`의 응답/도움은 각자 scenarioId를 보존하고, 노출 정산은 `scenarios[].exposedAt`만 사용한다.

`CueProvider`와 진행 중 판정 공급자의 운영 기본은 미연결 실패다. 이 문서는 실제 공급자 연결 · 장치 재생 · 부하 검증 완료를 뜻하지 않는다. 해당 구현과 검증은 별도 인계 항목이다.

## 10. U8 공통 규칙 (GPT-6-Luna · 제브 구조화 입출력 · 판 리소스 · 승인 발화)

U8(계약 · 버전 리소스)이 정한 공통 규칙이다. Luna · 제브 구조화 입출력의 **정본은 아래 JSON Schema 파일**이고, 이 절은 규칙 · 금지 필드 · 실패 처리만 적는다. 코드는 같은 파일로 공급자에 구조화 출력을 요청하고, 프롬프트 판(`promptVersion`)과 스키마 판은 함께 올린다. 각 단위(U9 ~ U15)는 더하기만 한다 [U8 편차 (U8-a)].

### 10.1 스키마 파일

U15 씨앗 v2는 GENERATED/DUPLICATE/NONE의 배타적 출력 분기를 사용한다. 필드·중복 원장·재시도 공유 규칙은 [지식 그래프 내부 계약](knowledge-graph.md)을 따른다. outcome 없는 기존 2필드 출력은 공통 호출부 호환용이며 U15 생성 성공으로 수락하지 않는다.

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
- **R-F15** 공급자 약관 · 보호자 동의 확인 전에는 실제 아동 데이터를 외부 모델로 보내지 않는다. 실호출은 합성 자료로만 한다. U9는 코드로 판정하지 않고 RUNBOOK 확인 기록으로 지킨다(편차 U9-b). 키(`OPENAI_API_KEY`)를 넣는 순간 실제 어댑터가 켜지므로, `infra/RUNBOOK.md` 단계 4의 R-F15 확인 기록 표에 한 줄을 남기기 전에는 운영 환경에 키를 넣지 않는다 [U9 편차 (U9-b)].
- 시험 · CI · 검증은 가짜 공급자만 쓴다(R-F1). 테스트 대역의 성공을 실공급자 성공으로 표시하지 않는다(R-M11).

### 10.3 목적 → 호출 경로 → 재시도 분류

| 목적 | 호출 경로(단위) | 분류 | 호출 안 재시도 · 실패 뒤 |
|---|---|---|---|
| 진행 중 판정 J1 ~ J6 | realtime 판정 턴(U11) | `NONE` | 재시도 없음. `mujoong.timing.judgment.live-budget`(4초) 초과 · 형식 오류 · 선택지 밖 코드는 판정 보류 |
| 제브 검사(새 발화) | realtime 출력 게이트(U13) | `NONE` | 재시도 없음. `mujoong.timing.guardrail.jev-budget`(800ms) 초과(시간 초과는 막힘) · 전송 오류(비 2xx 포함) · 잘못된 응답(10.13)도 막힘이고 자동 `ALLOW`는 없다. 막힘 뒤 같은 다음 행동 지시를 다시 하는 것은 게이트 단계의 일이다(호출 안 재시도가 아님) [U13 편차 (U13-b)] |
| 상황 생성 | core 상황 생성 요청(U10) | `REQUEST_BOUNDED` | 일시 실패 기술 재시도 1회 + 내용 재생성 1회(스키마 불일치 포함), 7절 15초 예산 안. 실패는 `SCENARIO_GEN_FAILED` |
| 자체 검토(J7) | core 상황 생성 요청(U10) | `REQUEST_BOUNDED` | 같은 15초 예산 |
| 새 씨앗 | core 결과 작업 뒤 씨앗 생성(U15) | `REQUEST_BOUNDED` | 실패하면 `skill_ref=NONE` 유지 |
| 결과 문장 | core 결과 작업(U14) | `JOB_ATTEMPT` | 호출 안 재시도 없음. 결과 작업 시도 실패로 세고 작업 재시도(timing.md 10절)로 다시 한다 |
| 목표 후보 | core 결과 작업(U14) | `JOB_ATTEMPT` | 같음(지금 코드에서 결과 작업 안에서만 부른다) |
| 기록용 재판정 | core 결과 작업(U14) | `JOB_ATTEMPT` | 같음 |

- 예산 밖 재시도 · 무한 재시도는 없다. 다른 경로에서 부르게 되면 그 단위가 분류를 다시 정한다(BR5.2).
- U14는 6.9와 timing.result를 최초 포함 총4회·추가 지연5/15/30초로 맞춘다(BR3.2). GOAL_CANDIDATES·REJUDGMENT·RESULT_TEXT는 각 작업 시도 안에서 추가 재시도를 하지 않는다. 새 HTTP 오류 code는 없다 — 판정 · 제브 실패는 내부 사유(보류 · 막힘), 상황 생성 실패는 기존 `SCENARIO_GEN_FAILED`다(BR7.3).

### 10.4 판 리소스와 세션 고정

- 판정 질문 · 선택지(J1 ~ J6, A §7.3 · B §5.2)는 realtime `judgment/option-sets-v1.json`, 제브 검사 질문 · 라벨 8개 설명(7개 범주 + `PASS`) · 7개 범주 · 모드(C §3.1 · §3.2, AI 동작 명세 §10.2)는 `guardrail/policy-v2.json`에 둔다(U8의 `policy-v1.json`은 이력으로 남기고 코드가 읽지 않는다) [U13 편차 (U13-c)]. J7은 core 상황 생성 자체 검토 프롬프트(U10)라 realtime 판에 넣지 않는다.
- realtime은 기동 때 두 파일을 읽어 검증하고, 파일이 없거나 형식이 틀리면 기동을 거부한다(BR2.1 · 4절 시드 사전과 같은 방식).
- 판정 지시 본문은 realtime `judgment/prompt-v3.txt`(판 `prompt-v3`)이고, 지점별 질문 · 선택지 뜻은 위 선택지 판에서 이어 붙인다. 판을 올릴 때는 새 파일을 만들고 `JudgmentPrompt`의 판 상수만 바꾼다(옛 파일은 이력으로 남기고 코드가 읽지 않는다). v3은 v1 본문에 J4 규칙만 더했다: J4는 무중의 도움 제안과 '이어서 해 볼까?' 제안에 대한 대답에 쓰고, '그만하고 싶어' · '이제 그만할래' · '여기까지만 할래' · '오늘은 안 할래'처럼 제안을 받지 않고 그만하겠다는 말은 REFUSE다 [U11 편차 (U11-f)]. 선택지 판 `judgment-v1`과 판정 요청 · 출력 스키마는 그대로다.
- 세션을 받을 때 두 판의 번호와 패키지의 승인 문구 판(`approvedContent` · `approvedSpeech`)을 세션 계획에 고정하고, 세션 정산이 끝나면 놓는다. 판정 · 검사 기록 행은 고정된 판 번호를 함께 남긴다(BR2.2, 기록은 U11 · U13).
- GPT-Live 세션 지시문의 활동 본문은 realtime `live-prompts/free-talk.v1.txt`(판 `live-free-talk.v1`) · `live-prompts/practice.v1.txt`(판 `live-practice.v1`)다. 기동 때 읽고, 파일이 없거나 비었거나 임시 상한(4000자)을 넘거나 자리 표시자(`{ }`)가 있으면 기동을 거부한다. 판을 올릴 때는 새 파일을 만들고 `LiveSessionInstructions`의 판 상수 한 줄만 바꾼다. 세션마다 공급자가 `session.started`로 시작을 받아들였을 때 `LIVE_INSTRUCTIONS{kind, version, sha256}` 사건 하나를 남긴다(연결 뒤 `session.start` 전송이 실패하거나 `session.started`가 오지 않으면 남기지 않는다) [U11 편차 (U11-e)].

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

- `OPENAI_API_KEY`: core-api · realtime-activity가 각자 받는다. core-api에서는 Luna 판정 키이고, realtime에서는 Luna 판정 키이자 GPT-Live 음성 중계 키다(U11 키 통일, 편차 (U11-c)). `JEV_API_KEY`(제브): realtime-activity만 받는다. 둘 다 배포 파라미터로는 선택(OPTIONAL)이고, 비었거나 공백뿐이면 미설정으로 보고 실제 Luna 어댑터를 끈다(U9 · U13). realtime의 GPT-Live 음성 중계는 demo 프로필에서만 이 키가 필수다(2절).
- Neo4j는 기존 `NEO4J_AUTH`를 쓴다. 7절의 core 예약 이름 `MUJOONG_TEXT_AI_API_KEY`는 쓰지 않는다(BR4.2). 값은 어디에도 쓰지 않고 이름만 둔다(R-F3).

### 10.8 U9 Luna 호출 감사 로그 (`LUNA_CALL` · `LUNA_STARTUP`)

Luna 호출부(core-api · realtime-activity 각자)는 공급자 시도마다 `LUNA_CALL` 한 줄을 남긴다. 공급자를 부르지 않은 실패(`FORBIDDEN_INPUT` · 시한 0 · 보내기 직전 주소 재확인의 `REQUEST_REJECTED` · 빈 키의 `AUTH_FAILED`)도 `attempt` 0으로 한 줄 남기고, 이 경우는 실패의 시도 수(`attemptsUsed`)에 세지 않는다. 요청 · 응답 본문, 전사, 이름, 키는 어떤 속성에도 담지 않는다(U9 BR6.1 · BR6.2 · R-F14). 정본은 `kr.mujoong.contract.log.EventCode` · `AttrKey`이고 `infra/bin/log-hygiene-check` 허용 표와 대조한다(`infra/ci/test-host-tools.sh`).

| 사건 | 속성 | 값 형식 | 값 |
|---|---|---|---|
| `LUNA_CALL` | `lunaPurpose` | ENUM | `JUDGMENT_LIVE` · `SCENARIO_GENERATION` · `SCENARIO_REVIEW` · `SEED_GENERATION` · `RESULT_TEXT` · `GOAL_CANDIDATES` · `REJUDGMENT` |
| `LUNA_CALL` | `lunaOutcome` | ENUM | `OK` · `AUTH_FAILED` · `RATE_LIMITED` · `SERVER_ERROR` · `REQUEST_REJECTED` · `TRANSPORT_ERROR` · `TIMEOUT` · `EMPTY_OUTPUT` · `LENGTH_EXCEEDED` · `REFUSED` · `SCHEMA_INVALID` · `FORBIDDEN_INPUT` · `MODEL_MISSING` |
| `LUNA_CALL` | `model` · `responseModel` · `promptVersion` | LABEL | `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`. 요청 모델, 응답 모델(받았을 때), 보낸 프롬프트 판 |
| `LUNA_CALL` | `sentFields` | NAME_LIST | 보낸 입력의 최상위 키 이름을 쉼표로 이은 값. 이름마다 `[A-Za-z][A-Za-z0-9_]{0,63}`, 최대 32개 |
| `LUNA_CALL` | `latencyMs` · `attempt` | NUMBER | 시도 지연(밀리초), 시도 번호 1 ~ 3(미호출 실패는 0) |
| `LUNA_STARTUP` | `lunaState` | ENUM | `ENABLED`(env `OPENAI_API_KEY` 있음, realtime은 `mujoong.luna.enabled`도 true) · `DISABLED_NO_KEY`(없음 · 공백) · `DISABLED_SWITCH_OFF`(realtime만: 키는 있으나 `mujoong.luna.enabled`가 false, 편차 (U11-c)) |

- 형식에 맞지 않는 LABEL · NAME_LIST 값은 조용히 버리지 않고 `UNKNOWN`으로 쓴다. `lunaOutcome`이 `OK`가 아니면 줄 수준은 WARN이다.
- 줄의 `component`는 `core-api/LunaClient` 또는 `realtime/LunaClient`다.

### 10.9 U9 Luna 호출 설정 · 주소

- 허용 호스트: `api.openai.com`
- 주소 규칙: demo · prod · 프로필 없음 · 모르는 프로필 · `lunaLiveTest`는 `https` + 허용 호스트만(STRICT), dev는 그 규칙 또는 루프백(DEV), test는 루프백만(TEST). 어기면 기동을 거부하고(`MUJOONG_STARTUP_REJECTED`), 보내기 직전에도 같은 규칙으로 다시 확인한다. 리다이렉트를 따르지 않고 프록시를 쓰지 않는다(U9 BR1.4 · SD3).
- 실제 어댑터는 env `OPENAI_API_KEY`가 비어 있지 않고 공백뿐이 아닐 때만 켜진다(10.7 · U9 Q2-B). **realtime은 여기에 스위치 `mujoong.luna.enabled`(기본 false)가 true일 때만** 켜진다 — 키만으로는 켜지지 않는다(U11 편차 (U11-c)). core-api는 지금처럼 키만으로 켜진다. 아래 키는 시간 값이 아니며 `timing.md`에 두지 않는다. 시도 시한은 기존 키(core `mujoong.text-ai.request-timeout` · `mujoong.text-ai.result.request-timeout`, realtime `mujoong.timing.judgment.live-budget`)와 호출자 마감 중 짧은 쪽이다(U9 BR5.1).

| 설정 키 | 기본값 | 앱 | 뜻 |
|---|---|---|---|
| `mujoong.luna.model` | `${OPENAI_ANALYSIS_MODEL:gpt-6-luna}` | core-api | 요청 모델 이름. env가 비면 `gpt-6-luna` |
| `mujoong.luna.model` | `${OPENAI_JUDGE_MODEL:gpt-6-luna}` | realtime | 같음 |
| `mujoong.luna.enabled` | `false` | realtime | realtime Luna 스위치(시간 값 아님). 키가 있어도 false면 `RealtimeLunaClient`를 등록하지 않는다. demo에서 true로 바꾸기 전에 `infra/RUNBOOK.md` 단계 4의 R-F15 확인 기록을 남긴다 |
| `mujoong.luna.base-url` | `https://api.openai.com/v1` | core-api · realtime | Responses API 기준 주소(`{base-url}/responses`). test 프로필은 루프백으로 덮어쓴다 |
| `mujoong.luna.max-response-bytes` | `1000000` | core-api · realtime | 응답 본문 바이트 상한. 넘으면 `LENGTH_EXCEEDED` |
| `mujoong.luna.purpose.<목적>.max-output-tokens` | `4000` | core-api · realtime | 요청 `max_output_tokens`. core 목적: `scenario-generation` · `scenario-review` · `seed-generation` · `result-text` · `goal-candidates` · `rejudgment`, realtime 목적: `judgment-live` |
| `mujoong.luna.purpose.<목적>.reasoning-effort` | `medium`(core `scenario-generation` · `scenario-review`는 `low`) | core-api · realtime | 요청 `reasoning.effort`(`none` · `minimal` · `low` · `medium` · `high`). 상황 생성 · 검토는 시작 요청의 15초 예산을 함께 쓰므로 낮춘다 |

### 10.10 U9 호출 방식 · 실패 사유 · 기존 어댑터

- **호출 방식:** 두 앱이 각자(코드 공유 없음) JDK `HttpClient` + `JsonCodec`으로 Responses API `POST {base-url}/responses`를 직접 부른다 [U9 편차 (U9-a)]. 리다이렉트를 따르지 않고(3xx는 `REQUEST_REJECTED`) 프록시를 쓰지 않으며, 응답 본문은 `max-response-bytes`까지만 읽는다. 요청은 `model` · `instructions`(버전 파일의 지시) · `input`(정리된 데이터의 JSON 문자열, 지시와 다른 자리) · `text.format` · `max_output_tokens` · `reasoning.effort` · `store: false`이고 `temperature`는 보내지 않는다(기존 `ResultTextProvider.Request.temperature`는 받기만 하고 무시).
- **보내기 전 정리:** 모든 깊이에서 10.2 금지 키(+ core 결과 게이트웨이의 `audioRefs` · `audioRefsJson` · `interests` 등)를 지우고, 호출자가 준 별칭 표로 아이 이름을 바꾸며, 이메일 · 휴대전화 모양 값을 '연락처'로 가린다. 남으면 · 요청 스키마(`ai-judgment-request`)에 맞지 않으면 부르지 않고 `FORBIDDEN_INPUT`이다. UUID → '참조N'과 복원은 지금 `ResultTextGateway`가 맡는다.
  - 금지 키는 대소문자와 `_` · `-`를 가리지 않고 비교한다(`child_name` · `Child-Id` · `api_key` · `guardian-id`도 지운다). 정리 뒤 안전망은 이 비교를 다시 쓰지 않는 독립 검사로, 글자 · 숫자 밖 문자를 모두 뺀 꼴(예: `child.name`)까지 보고 남으면 `FORBIDDEN_INPUT`이다.
  - 별칭은 긴 이름부터(길이가 같으면 사전순) 한 번만 훑으며 바꾼다. 이름끼리 포함 관계(예: '민수'와 '수')여도 결과가 별칭 표 순서에 기대지 않고, 바꿔 넣은 별칭을 짧은 이름이 다시 바꾸지 않는다. 빈 이름은 무시한다.
  - realtime 호출부는 보내기 전 준비(정리 · 요청 스키마 검증 · 본문 조립) 중 예기치 못한 런타임 오류도 호출 스레드로 던지지 않고 `APP_ERROR`(예외 종류 이름만) 한 줄과 `FORBIDDEN_INPUT`(시도 0) 실패 완료로 바꾼다.
- **스키마:** 빌드가 `contracts/schemas/ai-*.schema.json`(+ 그 파일이 가리키는 `common.schema.json`)을 실행 자원 `luna/schemas/`로 복사한다. 요청 `text.format`에는 공급자가 받는 키워드(`type` · `properties` · `required` · `additionalProperties` · `items` · `enum` · `const` · `anyOf` · `$defs` · `$ref` · `description`)만 남긴 사본을 싣고(다른 파일 `$ref`는 정의를 끼워 넣음. 맨 위의 `anyOf` · `enum` · `const`는 공급자가 `invalid_json_schema`로 요청째 거절하므로 사본에서 뺀다), 모든 객체가 닫히고 모든 속성이 required일 때만 `strict: true`다. 응답 검증은 언제나 원본 계약 스키마 전체로 한다. 스키마를 읽지 못하거나 정상 예시가 검증을 통과하지 못하면 기동을 거부한다.
- **시도 · 재시도:** 시도 시한 = min(호출자 마감까지 남은 시간, 목적 시한 키). 0 이하면 부르지 않고 `TIMEOUT`. 재시도는 10.3 분류대로 `REQUEST_BOUNDED`만 일시 실패 기술 재시도 1회 + 스키마 불일치 재요청 1회(합계 ≤ 3)이고, 대기 = max(`mujoong.text-ai.retry.backoff`, 429 `Retry-After`)가 남은 시간보다 짧고, 대기 뒤 남는 시간이 최소 시도 창(새 키 없이 `mujoong.text-ai.retry.backoff` 값을 그대로 씀) 이상일 때만 한다. 401 · 403은 재시도하지 않는다.

| 실패 사유 | 언제 | 일시 | `TextAiProvider` 어댑터(BR8.1) | `ResultTextProvider` 어댑터(BR8.2) |
|---|---|---|---|---|
| `AUTH_FAILED` | 401 · 403 | 아니오 | `UNAVAILABLE` · retryable=false | `PROVIDER_ERROR` |
| `RATE_LIMITED` | 429 | 예 | `UNAVAILABLE` · retryable=true | `PROVIDER_ERROR` |
| `SERVER_ERROR` | 5xx · JSON이 아닌 응답 · 완료가 아닌 상태 | 예 | `UNAVAILABLE` · retryable=true | `PROVIDER_ERROR` |
| `REQUEST_REJECTED` | 그 밖 4xx(스키마 거절 포함) · 3xx · 허용 밖 주소 | 아니오 | `UNAVAILABLE` · retryable=false | `PROVIDER_ERROR` |
| `TRANSPORT_ERROR` | 연결 · 읽기 오류 | 예 | `UNAVAILABLE` · retryable=true | `PROVIDER_ERROR` |
| `TIMEOUT` | 시도 시한 초과 · 남은 시간 없음 | 아니오 | `TIMEOUT` · retryable=true | `TIMEOUT` |
| `EMPTY_OUTPUT` | 출력 텍스트가 빔 | 예 | `UNAVAILABLE` · retryable=true | `SCHEMA_INVALID` |
| `LENGTH_EXCEEDED` | `status: incomplete` · 본문 바이트 상한 초과 | 아니오 | `INVALID_OUTPUT` | `SCHEMA_INVALID` |
| `REFUSED` | 거절(refusal) 출력 | 아니오 | `INVALID_OUTPUT` | `SCHEMA_INVALID` |
| `SCHEMA_INVALID` | 원본 계약 스키마 위반 · JSON 객체가 아님 | 아니오 | `INVALID_OUTPUT` | `SCHEMA_INVALID` |
| `FORBIDDEN_INPUT` | 정리 뒤 금지 키 잔존 · 요청 스키마 위반 · (realtime) 보내기 전 준비 중 예기치 못한 오류 | 아니오 | `UNAVAILABLE` · retryable=false | `INTERNAL` |
| `MODEL_MISSING` | 응답 `model` 없음 | 아니오 | `INVALID_OUTPUT` | `PROVIDER_ERROR` |

- **기존 인터페이스 어댑터:** core `TextAiProvider`(상황 생성 지시 `prompts/scenario/generation.v1.txt`, 판 `scenario-generation.v1`) · `ResultTextProvider`(`prompts/result/{task}.{판}.txt`)의 Luna 구현은 출력 스키마 파일 없이 `json_object`로 **언제나 시도 1회**만 부른다. 재시도는 바깥 `TextAiGateway`(기술 재시도)와 결과 작업이 이미 한다. 길이 · 판 정규식 · 금지 표현(`TextAiGateway`), `ResultAiSchema` · 참조 복원 · 인용 대조(`ResultTextGateway`), 1차 사전 · `classify`(`OutputChecker`)는 그대로 돈다. 새 스키마로 바꾸는 일은 U10 · U14가 한다.
- **실호출 시험:** 사람이 env에 키를 넣고 돌리는 `./gradlew lunaLiveTest`(태그 `luna-live`)만 실공급자를 부른다. 기본 `test` · `check` · CI · `verify.sh`는 루프백 대역만 쓴다. 키가 없으면 `SKIPPED_NO_KEY`로 끝나고, 결과는 '실제 API · 합성 자료' 증거로 따로 보고한다 [U9 편차 (U9-c)].

### 10.11 U10 상황 생성 경로

실제 Luna 구성의 상황 생성은 `CONTRACT_SINGLE_CALL`로 생성 `scenario-generation.v7`과 별도 검토 `scenario-review.v5`를 각각 스키마 검증한다. 판 이름과 자원 경로는 `ScenarioScriptProvider.GENERATION_PROMPT_VERSION` · `GENERATION_INSTRUCTIONS` · `REVIEW_PROMPT_VERSION` · `REVIEW_INSTRUCTIONS` 한 곳에서 정하고, 이미 저장된 v2 ~ v6 생성 · v1 ~ v4 검토 대본은 공개 대본 계약(`practice-scenario.schema.json` `generation.promptVersion` · `selfReview.promptVersion` enum)에서 계속 받는다. v3는 v2 규칙에 다음을 더한다: 모든 장면의 `relation`은 입력 relation을 그대로 쓴다. `structureContext`가 있으면 BASE에도 `preservation`을 넣고 skillRef·invariantSentence·preservationRule을 글자 그대로 옮긴다. topic·place·preservation.participants·relationship·situation은 `structureContext.allowed*` 목록에서 골라 글자 그대로 쓴다. 출력 스키마는 preservation의 participants·relationship·situation을 서버 어휘 enum으로 묶는다(최상위 topic·place는 자유 문자열 그대로) [U10 편차 (U10-b)]. v4는 v3에 다음을 더한다(출력 모양은 같다): 모든 장면의 `sceneDescription`에 그 장면의 `opportunitySentence`를, BASE에는 입력 `opportunityCondition`도 글자 그대로 넣는다. 서버 검사 `ScenarioScriptValidation`은 BASE에서만 `opportunityCondition` · `opportunitySentence` 포함을 요구하고, v4는 VARIANT에도 같은 `opportunitySentence` 포함을 지시한다(realtime이 도입 뒤 기회 문장 재생을 생략하는 조건, [U12 편차 (U12-c)]). 또 처음 들리는 두 필드에는 아이가 스스로 알아채야 할 핵심 단서 · 상대 속마음 · 해야 할 행동을 설명하지 않고, `reexplanation`에 아이가 이미 실수했다고 쓰지 않는다. v5는 v4의 단서 금지를 검토 v3과 같은 기준으로 맞춘다(출력 모양은 같다): 도움 동의 없이 들리는 세 필드(`sceneDescription` · `opportunitySentence` · `reexplanation`)에 핵심 단서 · 상대 속마음 · 해야 할 행동 · 말해야 할 내용을 설명하지 않고 눈에 보이는 장면 사실(장소 · 물건 · 상대의 대사 · 행동)로만 드러내며, 서술자가 상대의 지식 · 감정 · 의도를 직접 설명하지 않는다(BASE에 글자 그대로 넣는 `opportunityCondition`은 예외). `reexplanation`은 무슨 일이 있었는지를 더 쉽게 다시 말할 뿐이다(docs/practice-live-fixes.md). v6는 대본 형식을 바꾼다(깨는 변경, [U10 편차 (U10-c)]): 장면에 친구 역할 첫 대사 `partnerOpener`를 더하고(생성 출력의 필수 칸, 300자 이하, BASE는 비어 있지 않고 VARIANT는 빈 문자열), 아이에게 `sceneDescription`(무중이 역할 밖에서 하는 장면 설명, 상황 사실만) → `partnerOpener` → `opportunitySentence`(친구 역할이 실제로 하는 대사, 단서가 이 대사 속에서 귀로 들림, 무대 지문 · 해설이 아님) 순서로 들린다. `opportunityCondition`은 장면이 만들어야 할 생성 조건이라 어느 필드에도 글자 그대로 옮기지 않는다. 그래서 서버 검사 `ScenarioScriptValidation`은 설명의 `opportunityCondition` · `opportunitySentence` 포함 요구를 없애고, BASE `partnerOpener`가 비어 있지 않고 300자 이하인지와 도움 동의 없이 들리는 네 필드(설명 · 첫 대사 · 기회 문장 · 다시 설명)에 목표 표현이 글자 그대로 없고 압박 문구가 없는지를 본다. 기회 조건이 장면에서 실제로 생기는지는 검토 v4가 의미로 본다. VARIANT의 설명은 무중이 변형을 제안할 때 들리고 realtime이 끝에 고정 문장을 붙이므로 질문으로 끝내지 않는다(10.15). 형식 예는 AI 동작 명세 §8.2의 블록 놀이 장면이고 내용은 따라 쓰지 않게 지시한다. 검토 v4는 들리는 필드를 네 개(`sceneDescription` · `partnerOpener` · `opportunitySentence` · `reexplanation`, VARIANT 설명은 제안 때 들림)로 넓히고, v3의 `opportunityCondition` 글자 그대로 예외를 없앤다. 기회 문장이 무대 지문 · 해설이라 소리로 단서를 알 수 없으면 INCOMPLETE_SCENE, 설명 · 첫 대사 · 기회 문장으로 기회 조건의 상황이 생기지 않으면 GOAL_MISMATCH, 들리는 필드에 평가 · 칭찬 · 재촉이 있으면 PRESSURE다(docs/practice-dialogue-standard.md). v7은 v6 형식에 빈칸 예문과 들리는 대사 규칙을 더한다(실측 F2 · F5, docs/practice-dialogue-standard.md 14절, [U10 편차 (U10-e)]). ① 생성 출력에 `expressionFrames`를 필수 칸으로 더한다. `expressionExamples`와 같은 순서 · 같은 개수(3개 이하, 각 500자 이하)이고, 예시마다 아이가 스스로 채울 핵심 내용 낱말 자리를 `뭐뭐`로 비운 빈칸 예문이다. 공개 대본(`practice-scenario`) · 시작 패키지에서는 선택 칸이라 판 v6까지 저장된 대본은 그대로 받는다. 빈칸 예문은 `focusPoints` · `expressionExamples`처럼 도움 동의 뒤에만 쓰는 코치 자료다(10.15 5항). ② `sceneDescription`은 놀이 · 무중이 맡은 친구 · 아이가 방금 한 일이나 원하는 것만 말하고 상대의 실수 · 단서 · 속마음을 쓰지 않는다('너는 파란색을 달라고 했어'는 되고 '무중이가 초록색을 건넸어'는 안 된다). ③ `partnerOpener`는 단서가 없는 짧은 도입 대사이고, 아이가 알아채야 할 단서(부탁 · 실수 · 질문)는 `opportunitySentence`에만 둔다. ④ `opportunitySentence`는 친구가 의심 없이 하는 자연스러운 말 · 행동이며, 자기 실수를 먼저 드러내거나('~인 줄 알았어') 맞는지 확인하지('맞아?') 않고 목표 표현이나 아이가 할 대답의 일부를 먼저 말하지 않는다. ⑤ VARIANT는 같은 관계 · 짜임 · 단서 종류를 지키고 장면 속 물건 · 활동(소재)을 바꾸며, BASE의 물건과 기회 문장을 다시 쓰지 않는다. 서버 검사 `ScenarioScriptValidation`은 이 가운데 결정적인 것만 더 본다. VARIANT 기회 문장이 BASE 기회 문장과 같으면(글자 · 숫자만 남긴 정규화 비교) 변형을 뺀다(`INVALID_VARIANT`). `expressionFrames`가 있으면 예시와 개수가 같고, 예문마다 `뭐뭐`가 있고, 예시와 같지 않고, `뭐뭐` 밖의 말이 남아야 한다. 빈칸 밖의 말이 예시에 순서대로 있어 맞춰 볼 수 있으면 비운 자리 가운데 하나는 꾸밈말 · 지시어 · 대명사 · 감탄사 목록(`잠깐` · `좀` · `그냥` 등) 밖의 말이어야 한다(빈칸 바로 뒤 조사는 받침 뒤 모양도 같은 것으로 본다). 어긴 장면이 BASE면 `INVALID_BASE`, VARIANT면 `INVALID_VARIANT`다. 맞춰 볼 수 없는 예문과 소재가 실제로 바뀌었는지는 검토가 의미로 본다. 검토 v5는 v4 기준에 다음을 더한다: 설명이 상대의 실수나 아이가 알아채야 할 단서를 말하면 EARLY_CUE, 첫 대사나 기회 문장이 목표 표현 · 아이가 할 대답의 일부를 먼저 말하거나 친구가 자기 실수를 먼저 드러내거나 맞는지 확인하면 EARLY_CUE, 빈칸 예문이 예시와 짝이 맞지 않거나 핵심 내용 대신 꾸밈말만 비웠으면 INCOMPLETE_SCENE, VARIANT가 BASE의 물건 · 활동을 그대로 쓰거나 BASE와 같은 기회 문장을 쓰면 UNCHANGED_VARIANT다. 검토 사유 목록은 그대로다. 안쪽 재시도 없이 바깥 요청이 최초 15초·내용 수정1·기술 재시도1·BASE-only 검토1(최대6호출)을 공유한다. 그래프 경로에서 첫 BASE가 단독 검토에서 승인되면 다음 생성에 `baseToPreserve`로 보내고, 다음 생성이 그 BASE의 문구를 고쳐 쓰면 검토 전에 보존 BASE로 되돌려 생성된 VARIANT와 함께 검토한다. 그래도 변형 때문에 실패하면(서버 검사가 변형을 빼거나 검토가 변형 사유로만 거절) 검토 입력(대본 포함)이 단독 검토 때와 같을 때만 그 승인을 그대로 써서 BASE만 발급하고(BASE-only 검토 호출 없음), 서버 검사는 다시 돈다. 발급 경로는 공개 대본 `generation.basePreservation`(`BASE_REPLACED` · `BASE_ONLY_EARLIER_REVIEW` · `BASE_ONLY_REVIEWED`)에 남는다 [U10 편차 (U10-d)]. 검토 입력은 생성 입력과 같은 정리 값에 `script`를 더한 것이고, 대본에 VARIANT 장면이 없으면(첫 BASE 검토 · BASE만 남긴 재검토) 다음 생성용 `variantPlan`을 넣지 않는다. 검토 v3은 v1 규칙에 장면 필드의 쓰임을 더한다: 도움 동의 없이 들리는 세 필드 `sceneDescription` · `opportunitySentence`(처음 들림) · `reexplanation`(상황을 잘못 알아들었을 때 한 번)을 같은 기준으로 ANSWER_INCLUDED(목표 표현이나 거의 같은 문장) · EARLY_CUE(핵심 단서 · 상대 속마음 · 해야 할 행동 · 말해야 할 내용을 설명함. 서술자가 상대의 지식 · 감정 · 의도를 직접 설명하는 것도 여기에 든다) 대상으로 본다. 실패가 아닌 것은 눈에 보이는 장면 사실(장소 · 물건 · 상대의 대사 · 행동)로 단서가 드러나게 한 것과 목표가 정한 `opportunityCondition`을 글자 그대로 옮긴 부분뿐이고, `reexplanation`은 무슨 일이 있었는지를 더 쉽게 다시 말하는 데까지만 허용한다. 도움 동의 뒤에만 쓰는 `focusPoints` · `expressionExamples`의 목표 표현 · 단서는 실패가 아니다. BASE의 `transferDistance` · `changedElement` 고정값은 검사하지 않고, VARIANT가 없는 대본에는 UNCHANGED_VARIANT · TRANSFER_MISMATCH를 쓰지 않는다. 바로 앞 판 v2는 `reexplanation`을 EARLY_CUE에서 빼고 목표 표현을 그대로 담을 때만 ANSWER_INCLUDED로 보아, 동의 없이 들리는 다시 설명이 의미 검토를 받지 않았다. v3은 그 예외를 없앤 판이다(docs/practice-live-fixes.md). 기존 직접 호출 어댑터 메서드의 json_object 계약과 다른 목적의 호출 예산은 그대로다.

사용자 결정 Q1-A로 최상위 interests의 이름을 정리한 최대10개·각80자 관심사만 SCENARIO_GENERATION/SCENARIO_REVIEW에 허용한다. 중첩 interests·일반 정리 API·다른 목적은 기존 금지를 유지한다. 이름은 소유 프로필에서 얻은 비공개 별칭으로 ‘아이’로 치환하고 연락처·과도한 자유문은 제외한다. 생성·검토 모두 같은 나이·최근 실제 노출3건·지정 원본·고정 목표의 정리 값을 쓴다. 원래 이름·식별자·판정 전용 기준·검토 원문을 공급자 데이터나 로그에 넣지 않는다. 모르는 타인의 이름 탐지 품질은 자동 시험으로 보장하지 않는다.

### 10.12 U13 제브 검사 설정 · 모드 · 주소

realtime 출력 게이트의 제브 단계(U13)가 읽는 설정이다. 시간 값이 아니며 `timing.md`에 두지 않는다. 검사 시한은 기존 키 `mujoong.timing.guardrail.jev-budget`(800ms, timing.md 13절)이고 호출 안 재시도는 없다(10.3). 값은 모두 환경 변수로만 받고 저장소에는 이름만 둔다(R-F3). 키 값 `JEV_API_KEY`는 호출 때마다 환경에서 읽고 어디에도 쓰지 않는다(10.7).

- 제브 허용 호스트: `api.typesafe.ai`
- 주소 규칙: demo · prod · 프로필 없음 · 모르는 프로필은 `https` + 허용 호스트만(STRICT), dev는 그 규칙 또는 루프백(DEV), test는 루프백만(TEST). 사용자 정보 · 질의 · 조각이 붙은 주소는 모두 거부한다. 어기면 기동을 거부하고(demo · prod, REAL), 보내기 직전에도 같은 규칙으로 다시 확인한다. 리다이렉트를 따르지 않고 프록시를 쓰지 않는다.
- 모델은 고정 버전이어야 하고 이동 별칭(`jev-latest` 등 `-latest`로 끝나는 이름)이면 demo · prod에서 기동을 거부한다.

| 설정 키 | 기본값 | 앱 | 뜻 |
|---|---|---|---|
| `mujoong.guardrail.jev.mode` | `${JEV_MODE:}` | realtime | 제브 단계 모드 `REAL` · `STUB` · `OFF`. 비면 프로필 기본값(demo · prod = `REAL`, 그 밖 = `STUB`). 세션 안에서 바뀌지 않는다 [U13 편차 (U13-a)] |
| `mujoong.guardrail.jev.base-url` | `${JEV_BASE_URL:}` | realtime | 제브 주소. 호스트는 위 허용 호스트 안이어야 한다 |
| `mujoong.guardrail.jev.model` | `${JEV_MODEL:}` | realtime | 고정 모델 버전. 요청의 모델 이름은 이 값과 같아야 한다 |
| `mujoong.guardrail.jev.pass-threshold` | `${JEV_PASS_THRESHOLD:0.5}` | realtime | 통과 문턱, 허용 `0 < x <= 1`. `PASS`가 1순위이고 `PASS` 확률이 이 값 **이상**일 때만 자동 통과한다(10.13). 비밀이 아니다. **임시 시험값이다 — AI 성능 시험으로 확정한다.** `REAL`에서 범위 밖이거나 숫자가 아니면 기동을 거부한다 [U13 편차 (U13-c)] |

기동 점검(모드 × 프로필 × 키): 공개 프로필(demo · prod)은 `REAL` + `JEV_API_KEY` + 허용 호스트 + 고정 모델이 아니면 기동을 거부하고(`STUB` · `OFF`도 거부), 그 밖(dev · test · 프로필 없음)은 `REAL` · `STUB` · `OFF`를 모두 허용하되 `REAL`이면 키가 있어야 한다. `STUB`은 제브 단계를 모두 지나며 1차 사전 통과를 대역 표시가 붙은 `ALLOW`(`reasonCode` `STUB_ALLOW`)로 돌려주고 `OFF`는 제브 단계를 건너뛴다. `OFF`에서는 연습 흐름의 생성형 발화(되묻기 · 친구 반응 · 코치 도움 · 생성형 제안 · 알림)도 자유 대화처럼 기본 게이트(금지 패턴 · 완결)와 송신 직전 재확인을 지나면 재생되고 슬롯은 재생 완료로 닫힌다. 이전에는 생성형 슬롯을 폐기했다(fail-closed) [U13 편차 (U13-d)]. `REAL` · `STUB`의 생성형 발화는 지금처럼 제브가 `ALLOW`한 것만 재생된다. 둘로 시작하면 기동 로그에 한 줄이 남는다. 대역의 통과는 실제 검사 성공이 아니고, `OFF`의 재생은 검사를 받지 않은 재생이다(R-M11).

정책 판 이름은 `guardrail/policy-v2.json`의 `jev-guardrail-v2`다(선 형식 정정으로 `policy-v1.json`의 `jev-guardrail-v1`을 올렸다, 10.13). 스키마 파일(`guardrail-request` · `guardrail-result`)의 예시가 쓰던 `guardrail-v1`은 이 판 이름 규칙으로 정정했다 [U13 편차 (U13-b)] [U13 편차 (U13-c)].

### 10.13 U13 제브 공급자 선 형식 · 판정 규칙

어댑터(`JevGuardrailClient`)가 제브 공식 문서([quickstart](https://docs.typesafe.ai/introduction/quickstart) · [API](https://docs.typesafe.ai/api))에 맞춰 부르는 형식과, 응답을 내부 결과(`guardrail-result`)로 바꾸는 규칙이다. 내부 형식 스키마(`guardrail-request` · `guardrail-result`)는 그대로이고 공급자 형식은 이 어댑터 안에서만 쓴다 [U13 편차 (U13-c)].

**호출:** `POST {기준 주소}/v1/systemone`(경로는 코드 상수, 기준 주소는 `JEV_BASE_URL`), 헤더 `Authorization: Bearer <키>` · `Content-Type: application/json`. 호출 한 번, 재시도 없음, 시한 `mujoong.timing.guardrail.jev-budget`, 리다이렉트 · 프록시 미사용, 응답 64KiB 상한(10.3 · 10.12).

**요청 본문:** 최상위 키는 `model` · `state` · `questions` 셋뿐이다.

- `model`: 설정의 고정 모델 버전(`mujoong.guardrail.jev.model`). 모델 이름을 코드에 두지 않는다.
- `state`: `{ "candidate": <후보 문장>, "context": [ { "speaker": "CHILD|PARTNER|COACH|MUJOONG", "text": <문장> }, … ] }`. 후보 문장과 익명 최근 맥락 3턴 이하만 싣고 식별자 칸이 없다. 내부 요청의 `mode`는 보내지 않는다.
- `questions`: 질문 ID `guardrail` 하나. `{ "type": "choice", "instructions": <정책 판의 한국어 검사 질문>, "criteria": { <라벨>: <설명> … } }`. 라벨은 7개 범주 `DIAGNOSIS` · `TREATMENT` · `SCORE` · `CAUSE` · `GENERALIZATION` · `EMOTION_CLAIM` · `PRESSURE`와 `PASS`, 모두 8개다(제브 상한 255개 이하). 문안은 정책 판 `guardrail/policy-v2.json`에서 온다. 후보 문장 · 맥락 안의 지시문은 `state` 데이터일 뿐 `questions`를 바꾸지 못한다.

**응답 읽기:** `answers.guardrail`의 `type`(`choice`) · `choice`(1순위 라벨) · `probabilities`(라벨별 확률)만 읽는다. `confidence` · `usage`는 판정에 쓰지 않는다. 최상위 `model`은 실호출 보고서에만 옮긴다(모델 이름 형식일 때). 응답에 정책 판 · 이유 코드가 없으므로 내부 결과의 `policyVersion`은 요청에 실은 판이고 `reasonCode`는 아래 규칙으로 정한다.

**검증(위반이면 `FAILED`/`INVALID_JSON`, 막힘):** 본문이 JSON 객체 · `answers.guardrail`이 객체, `type`이 `choice`, `choice`가 보낸 라벨 중 하나, `probabilities`가 보낸 8개 라벨을 모두 가지고 모르는 라벨이 없으며 값이 모두 0 이상 1 이하인 숫자, 합이 1에서 0.001 넘게 벗어나지 않음(**허용 오차 0.001은 근거 없는 임시값이다 — 실호출 결과로 조정**), `choice`의 확률이 최댓값(최댓값 집합 안).

**판정 규칙(문턱 = `mujoong.guardrail.jev.pass-threshold`, 임시 시험값 0.5, 비교는 `>=`):**

| 조건 | 결과 | `reasonCode` | `categories` |
|---|---|---|---|
| `choice`가 `PASS`이고 `PASS` 확률이 문턱 이상이면서 다른 어느 라벨 확률보다 엄격히 큼 | `ALLOW` | `PASS` | 빈 목록 |
| `choice`가 `PASS`이고 다른 라벨과 확률이 같음(동률 1순위) | `REVIEW`(재생하지 않음) | `PASS_TIED` | 빈 목록 |
| `choice`가 `PASS`이고 동률이 아니며 `PASS` 확률이 문턱 미만 | `REVIEW` | `PASS_BELOW_THRESHOLD` | 빈 목록 |
| `choice`가 `PASS`가 아님(라벨끼리 동률이어도) | `BLOCK` | 고른 라벨 | `[고른 라벨]` |

`policyFlags`는 언제나 빈 목록이다. 위 조건 밖에는 자동 `ALLOW`가 없다. 확률은 `guardrail_event`에 저장하지 않는다.

**오류 매핑:** 비 2xx(400 · 401 · 413 · 415 · 422 · 429 · 529 포함, 리다이렉트도)는 오류 본문을 읽지 않고 모두 `TRANSPORT_ERROR`이며 재시도하지 않는다(429 · 529의 백오프 권고도 따르지 않는다 — 시간 초과 = 막힘). 시간 초과는 `TIMEOUT`, 허용 밖 호스트 · 주소 오류는 `TRANSPORT_ERROR`, 빈 키 · 모델 규칙 · 문턱 범위 위반은 `UNAVAILABLE`(보내기 전 거절)이다. `POLICY_MISMATCH`는 응답에 정책 판이 없어 더 만들지 않는다(열거값은 호환을 위해 남김).

**열린 항목(실호출로만 확정):** 요청 `model`에 고정 버전 이름이 받아들여지는지(공식 예시 요청은 `-latest` 이동 별칭을 쓰고 우리 규칙은 그것을 거부한다), 오류 본문 모양, `confidence`의 뜻, 한국어 문안의 분류 품질, 지연, 확률 합 허용 오차, 문턱 0.5. 키를 넣기 전 R-F15 확인 기록(RUNBOOK 단계 4)이 먼저다.

### 10.14 U12 새 연습 흐름 횟수 한도

realtime 새 연습 흐름(U12)이 읽는 설정이다. 시간 값이 아니며 `timing.md`에 두지 않는다. 시간 한도(무응답 재안내 10초 · 집중 90초 · 목표 ACTIVE 10분)는 기존 키 `mujoong.timing.conversation.no-response-wait` · `focus-max` · `goal-active-limit`(timing.md 13절)이 맡고, 이 표는 횟수만 정한다. 비밀이 아니고 환경 변수가 없다.

| 설정 키 | 기본값 | 앱 | 뜻 |
|---|---|---|---|
| `mujoong.practice-flow.retry-max` | `2` | realtime | 장면 하나에서 코치와 함께 '다시 해 보기'를 할 수 있는 최대 횟수(AC1.5.4). 0 이상 정수, 0은 하지 않음 |
| `mujoong.practice-flow.reexplain-max` | `1` | realtime | 장면 하나에서 상황을 다시 설명하는 최대 횟수(AC1.3.x, OPPORTUNITY와 RETRY가 공유). 0 이상 정수, 0은 하지 않음 |

### 10.15 U10 ↔ U12 대본 · 기록 연결 규칙

core 상황 생성(U10)이 내는 대본(`practice-scenario.schema.json`의 `scriptVersion=scenario-script.v1` · `scenes[]`)과 realtime 새 연습 흐름(U12)이 내는 기록(`practice-record-v2.schema.json`)을 두 앱이 같은 뜻으로 읽는 규칙이다. 대본 판(`scenario-script.v1`)과 기록 모양은 바꾸지 않고 뜻을 정한다. 생성 판 v6에서 장면에 선택 칸 `partnerOpener`(친구 첫 대사)를 더했다 [U10 편차 (U10-c)]. 생성 판 v7에서 장면에 선택 칸 `expressionFrames`(`expressionExamples`와 1:1인 빈칸 예문, 아이가 채울 자리는 `뭐뭐`)를 더했다 [U10 편차 (U10-e)]. realtime은 이 칸을 읽고 없으면 빈 목록으로 둔다(`PracticeScriptReader`).

1. **`scenes[].partnerRole` = 짧은 역할 이름** [U12 편차 (U12-b)]
   - 지시문이 아니다. 모델은 이 칸을 내지 않고(`ai-scenario-output.schema.json`), core가 발급할 때 장면 `relation`에서 만든다(`ScenarioContentPolicy.partnerRoleName`).
   - 관계 코드: `PEER` · `FRIEND_CASUAL` → 친구, `FRIEND_CLOSE` → 친한 친구, `CLASSMATE` → 같은 반 친구, `CLUB_PEER` → 동아리 친구, `CLUB_SENIOR` → 동아리 선배, `CLUB_JUNIOR` → 동아리 후배, `TEAMMATE` → 같은 팀 친구, `TEACHER` → 선생님, `FAMILY` → 가족, 그 밖의 대문자 코드 → 친구.
   - 후보 목표의 자유 문구(상대): 앞뒤 공백을 지우고 연속 공백을 한 칸으로 줄인 뒤 20자(코드 포인트) 이하이고 `.?!。` · 제어 문자가 없으면 그대로, 아니면 친구.
   - 상한은 20자(코드 포인트, 스키마 `maxLength: 20`), 대체값은 친구다.
   - 금지 규칙(정답 · 모범 문장을 먼저 말하지 않기, 고쳐 주거나 평가하지 않기, 아이 대신 대답하지 않기)은 realtime 친구 지시문의 고정 문장이 맡는다.
   - realtime은 이 값을 "너는 아이의 {이름} 역할이다"와 승인 발화의 말하는 사람 표시에 쓰고, 비었거나 상한을 넘거나 문장부호 · 제어 문자가 있는 값(배포 전에 저장된 옛 지시문 값)은 친구로 바꿔 쓴다(`RolePolicy.peerName`). 대본 값 자체는 고치지 않는다.
2. **도입 · 첫 대사 · 기회 문장 재생** [U12 편차 (U12-c)] [U12 편차 (U12-e)] [U10 편차 (U10-c)]
   - 생성 판 v6부터 BASE 설명은 기회 조건 · 기회 문장을 담지 않는다(core 포함 검사 폐지, 10.11). 레거시 `scenarioText`(고지 + BASE 설명) · `triggerScript`(BASE 기회 문장) · 지문 · 검증 표시(`goalIncluded` · `triggerIncluded` · `forbiddenFound`)는 지금처럼 실리고, 검증 표시는 '서버 검사와 별도 검토를 통과했다'는 뜻이다(설명이 두 문장을 글자 그대로 담는다는 뜻이 아니다). 판 v5까지 저장된 대본은 설명이 두 문장을 담은 그대로다.
   - realtime 도입은 `sceneDescription` 원문을 자르지 않고 승인 발화로 재생한다. `partnerOpener`가 있으면 이어서 첫 대사를, 그다음 `opportunitySentence`를 재생한다(무중의 설명 → 친구 첫 대사 → 친구 기회 문장). 없거나 비었으면 설명 뒤 곧바로 기회 문장이다. 승인 발화에는 core가 발급한 문장 원문만 들어가므로 10.5의 원문 일치 · 제브 생략 규칙은 넓어지지 않는다(승인 원문의 일부는 승인 발화가 아니다).
   - 승인 발화의 말하는 사람 표시(`PracticeSpeech.partnerRole`, 공급자에 가는 `{partnerRole, speech}`의 `partnerRole`)는 문장 종류로 정한다: 장면 설명 · 상황 다시 설명은 무중(`무중`), 첫 대사 · 기회 문장은 친구 역할 이름(1항). 12 화면 말풍선(`speaker` CHILD · AI)과 `record.practice` 모양은 바뀌지 않는다.
   - 첫 대사가 없는 옛 대본에서 설명이 기회 문장으로 끝나고(같은 문자 부류의 정규화 비교, 설명의 마지막 낱말들이 기회 문장과 같음) 도입이 끝까지 재생(PLAYED)됐으면, 도입 직후 OPPORTUNITY 진입에서 기회 문장을 다시 재생하지 않고 곧바로 아이 말을 기다린다. 이 규칙은 BASE · VARIANT에 같이 쓴다. 기회 문장이 설명 가운데 있거나 간접 인용으로만 나오거나 다른 낱말 안에 들어 있으면 따로 재생한다. 도입이 끊겼거나 설명이 기회 문장으로 끝나지 않을 때도 따로 재생한다. 첫 대사가 있으면 아이가 마지막으로 들은 말이 첫 대사이므로 설명이 기회 문장으로 끝나도 따로 재생한다.
   - 기회 문장으로 끝나는 도입(옛 대본)이 재생되는 동안 확정된 아이 전사는 마지막 하나만 두었다가 도입이 끝까지 재생되면 OPPORTUNITY(J2) 입력으로 판정한다. 도입이 끊기거나 실패하면 그 전사는 버리고 기회 문장을 다시 재생한다. 설명 · 첫 대사가 재생되는 동안 확정된 전사는 대답으로 보지 않는다.
   - 첫 대사 재생이 실패하면(장면 행이 생긴 뒤) 재생 실패로 장면을 정리하고, 끊기면 기회 문장으로 간다.
   - 무응답 대기(`mujoong.timing.conversation.no-response-wait`, timing.md 13절)는 질문 재생이 끝날 때 걸고 아이 말이 시작되면 멈춘다(4회차 R1). 실행부는 ACTIVE 중 새 입력 전사 묶음의 첫 조각(끼어들기 봉인과 같은 신호)마다 한 번 출력권 연습에 `onChildSpeechStarted`로 알리고(참여 확인 · 기술 일시정지 · 종료 확인 · 종료 판정 중에는 알리지 않는다), 흐름은 그 말의 확정 전사가 올 때까지 무응답 시계를 걸지 않는다 — 우리 말이 재생되는 중에 시작된 말도 같다. 확정 전사는 지금처럼 판정한다. 확정 전사 없이 끝난 말(빈 묶음 · 종료 의도로 실행부가 맡은 말)은 말 시작부터 같은 길이의 안전 대기가 끝날 때 무응답 대기를 처음부터 다시 건다. 자유대화 · 레거시 연습 확장 · 종료 의도 처리는 바뀌지 않는다.
   - 무응답 재안내는 기회 문장만 다시 재생한다. 상황 다시 설명 뒤의 OPPORTUNITY 재진입은 장면에 첫 대사가 있으면 첫 대사 → 기회 문장을 다시 재생하고, 없으면 기회 문장만 재생한다. 다시 재생하는 첫 대사 동안 확정된 아이 전사는 대답으로 보지 않고, 그 첫 대사가 끊기면 기회 문장으로 가며 실패 · 막힘은 재생 실패로 장면을 정리한다 [U12 편차 (U12-g)]. 친구 · 코치 지시문의 '상황:'에는 설명 전체를 그대로 둔다.
3. **record.practice v2 → core 노출 정산** [U12 편차 (U12-d)]
   - core는 `flowVersion=practice-conversation-v1` 기록의 `scenes[].scenarioRef` · `startedAt`을 노출로 읽는다(`RecordedScenarioExposures`). 장면을 시작한 대본이 아이에게 보인 대본이다.
   - 같은 `scenarioRef`(BASE와 VARIANT가 같은 대본)는 가장 이른 `startedAt` 하나로 정산한다. `scenarioRef`나 `startedAt`이 없는 장면은 건너뛴다.
   - 모르는 `flowVersion` · 틀린 모양(장면 목록이 배열이 아님 · 식별자나 시각이 형식에 안 맞음)은 정산하지 않고 실패로 남긴다(정산 완료로 표시하지 않는다).
   - 정산 주기(`runOnce`)가 미정산 준비를 다시 정산하므로 이전 기록도 배포 뒤 다음 주기에 정산된다. 마이그레이션은 없다.
4. **공유 고정 자료와 교차 시험**
   - `contracts/fixtures/practice-start-package.json`: core가 생성 → 발급 → 시작 패키지 조립으로 낸 `practice` 조각(후보 목표 + 기존 행동 목표, BASE · VARIANT 대본).
   - `contracts/fixtures/practice-record-v2.json`: realtime 기록 조립기(`PracticeFlowRecordAssembler`) 출력.
   - 시험: contract-model `ContractSchemasTest`(두 자료가 각 스키마를 통과), core `PracticeLinkContractTest`(core 출력이 자료 1과 트리로 같음 · 자료 2를 정산이 읽음), realtime `PracticeLinkContractTest`(자료 1로 한 장면 진행 · 조립기 출력이 자료 2와 트리로 같음), core `PracticeStartApiIT`(자료 2 모양으로 실제 DB 정산). 한쪽 모양이 바뀌면 세 모듈 시험이 함께 깨진다.
   - 자료는 합성 문장과 `00000000-0000-4000-8000-…` 식별자만 쓴다.
5. **장면 진행 말: 친구 반응 · 도움 제안 · 변형 제안 · 코치 도움** [U12 편차 (U12-f)]
   - 판정한 아이 대답이 장면을 닫으면(J2 `FULL` · J3 `DELIVERED` · J5의 정리 결과) 칭찬 · 채점 대신 친구가 역할 안에서 한 번 반응한다(B §3.1 `D_CONTINUE` · `D_RESUME`). 친구 역할이면 곧바로, 코치 역할이면 복귀 알림(`PEER_RETURN`)이 들린 뒤다. 생성형 친구 슬롯(제브 `PRACTICE_PEER`)이고 지시문은 칭찬 · 교정 · 새 질문을 막는다. 반응 중의 아이 말은 판정하지 않고 무응답 시계를 걸지 않는다. 장면 결과(`liveOutcome.timing`)는 반응 전에 정해지며 반응이 실패하거나 막혀도 바뀌지 않는다(재생 실패만 `PLAYBACK_FAILED` 사실로 남는다). 무응답 · 판독 불가 · 보류 · 막힘 · 시계 한도 · 도움 거절로 닫힌 장면에는 반응하지 않는다(B §3.1 `D_WRAP` · `D_BACK_TO_SCENE`은 이 판의 범위 밖이다).
   - 도움 제안(B §3.1 `D_OFFER_HELP`)은 친구가 아니라 무중이 역할 밖에서 한다. 시작 패키지 `approvedSpeech`의 `HELP_OFFER`가 있으면 그 원문을 말하는 사람 표시 `무중`의 승인 발화로 재생하고(원문 일치라 제브 생략, 10.5), 없으면 코치 슬롯(제브 `PRACTICE_COACH`)의 생성형 발화로 낸다. 재안내도 같은 길이다.
   - 변형 제안(B §3.1 `D_VARIANT_OFFER`): 판 v6 대본(BASE에 `partnerOpener`가 있다)이면 VARIANT `sceneDescription` 원문 + 공백 + 고정 문장 `이어서 해 볼까?`를 무중의 승인 발화로 재생한다. VARIANT 설명은 무중의 장면 전환 말이고(10.11) 붙는 꼬리는 서버 고정 문장뿐이라 원문 일치 · 제브 생략 근거가 넓어지지 않는다. 수락하면 설명을 다시 읽지 않고 그때 변형 장면 행을 만든 뒤 VARIANT `partnerOpener`(있으면) → `opportunitySentence` 순서로 재생한다(B §3.1 `D_VARIANT_START`). 판 v5까지의 대본은 VARIANT 설명이 기회 문장을 글자 그대로 담으므로 제안에 쓰지 않고, 무중의 생성형 질문(코치 슬롯)으로 묻고 수락 뒤 지금처럼 설명 → 기회 문장으로 시작한다.
   - 코치 도움(B §3.1 `D_FOCUS`): 도움 동의 · 요청 뒤 첫 도움은 `focusPoints` 첫 요점 → 빈칸 예문 → '네 말로 해 볼래?'이고, 다시 해 보기 뒤의 도움은 표현 예시 하나다. 장면에 `expressionFrames`가 있으면(생성 판 v7) 첫 빈칸 예문을 글자 그대로 넣게 지시하고(제어 문자를 지운 한 줄이 지시문 칸 상한 80자 안이고 `뭐뭐`가 있을 때), 없으면(옛 대본) GPT-Live가 `expressionExamples` 첫 예시의 핵심 낱말을 `뭐뭐`로 비워 만든다. 지시문은 요점과 빈칸 예문을 빠뜨리지 말라고 하고, '말틀' 같은 지시문 낱말을 쓰지 않으며 '빈칸 예문'이라는 말을 소리 내지 않게 한다 [U12 편차 (U12-g)]. 기록의 `exampleGiven`과 사실 `EXPRESSION_EXAMPLE`은 대본에 표현 예시가 있고 빈칸 예문이나 예시를 줬을 때만 남는다.
   - 되묻기(B §3.1 `D_REASK`)는 친구 역할 그대로, 아이가 자기 말로 채워 답할 열린 질문 하나다. 아이가 원하는 것이나 할 말의 낱말은 장면 설명에 나와 있어도 먼저 말하거나 그 낱말을 넣어 확인하지 않고, 지금 장면과 상관없는 새 질문을 하지 않는다. 생성형 지시문(되묻기 · 친구 반응 · 코치 도움 · 생성형 도움 제안 · 변형 제안 · 알림)은 모두 이모지 · 기호 · 괄호 없이 말로만 하게 한다 [U12 편차 (U12-g)].
   - 종료 · 휴식 요청이나 세션 종료가 이미 정리된 장면(친구 반응 · 복귀 알림 · 변형 제안 중)에 오면 정리 때 정한 `liveOutcome.timing`을 지우지 않고, 원 장면(BASE)이면 변형 평가가 그 순간 냈을 `variantSkipReason`만 더한다(종료 · 휴식을 반영한 BR6.1 순서: 변형 장면이 없는 대본은 `NO_VARIANT`, 집중 한도로 닫힌 장면은 `FOCUS_LIMIT`, 나머지는 `END_OR_REST`).
   - 화면 진행 6값은 그대로다. 친구 반응 동안에는 직전 값을 유지한다(변형 장면이면 `NEW_SCENARIO_RESPONSE`).

### 10.16 3회차 V1 연습 대본 줄 음성(미리 합성) [PDS 편차 (PDS-V1)]

실음성 실측(`docs/practice-dialogue-standard.md` 12.3 · 15절)에서 GPT-Live가 승인 원문을 그대로 읽지 못해(덧붙임 · 바꿔 읽기) 원문 대조가 대본 줄을 막았다. 그래서 realtime은 연습 대본 줄을 GPT-Live 원문 읽기 대신 미리 만든 음성으로 낸다(팀장 결정 2026-10-23). 기술명세서 §3.4는 아이에게 들리는 승인 문구 · 고정 대본이 `ScriptSpeaker` 경계(GPT-Live 낭독 / 클립 재생)를 거치고 문구별 출력 방식을 바꿔도 상태 기계는 바뀌지 않는다고 정하고, AI 동작 명세 v0.2 §4.2는 자주 어긋나는 문구를 클립으로 바꾸는 후보로 올리게 한다. 이 판은 그 클립을 ① 대상 문구만이 아니라 아래 대본 줄 전부에, ② GPT-Live 목소리 녹음이 아니라 OpenAI TTS로, ③ 미리 녹음하지 않고 세션 시작 때 만든다 — 그래서 대본 줄 목소리는 GPT-Live 목소리와 다를 수 있다.

1. **대상(대본 줄)**: 새 연습 흐름이 `PracticeOutput.speak`로 승인 원문 그대로 내는 줄 — BASE · VARIANT 장면 설명(무중), 친구 첫 대사 · 기회 문장(친구), 상황 다시 설명(무중), 변형 제안(판 v6 대본의 VARIANT 설명 + `이어서 해 볼까?`, 무중), 승인 발화 `HELP_OFFER`(무중), 실행부가 내는 역할 전환 · 복귀 알림 `COACH_SWITCH` · `PEER_RETURN`(말하는 사람 표시 `코치` · `친구`, 무중 말투). 생성형 줄(되묻기 · 친구 반응 · 코치 도움 등, 10.15 5항)은 지금처럼 GPT-Live가 그 자리에서 만든다.
2. **만드는 때**: 참여 확인(WAITING_CHILD) 진입 때 확장이 첫 대본의 줄을 알려 주고(`PracticeSessionExtension.speechLines`), 실행부가 알림 두 줄을 더해 맡긴다. 다음 목표 대본은 도착할 때 확장이 `PracticeOutput.prepareSpeech`로 맡긴다. 기다리지 않는다.
3. **합성**: OpenAI Audio API `POST {base-url}/audio/speech`(`model` · `voice` · `input` · `response_format: "pcm"` · 말투 `instructions`), 24kHz s16 모노 PCM(GPT-Live 출력 · 기기 0x02 프레임과 같은 형식, 바꾸지 않는다). 키는 env `OPENAI_API_KEY`(Luna · GPT-Live와 같다, 새 비밀 없음, 10.7). 주소 규칙은 Luna와 같다(`LunaHosts`, 10.9: demo · prod · 프로필 없음은 https + `api.openai.com`만, test는 루프백만, dev는 둘 다) — 기동 때 한 번 확인하고 어기면 기동을 거부하며(값 미출력), 보내기 직전에 다시 확인한다. 리다이렉트를 따르지 않고 프록시를 쓰지 않으며 본문은 출력 구간 상한(20초 분량)까지만 읽는다. 시한 `mujoong.timing.speech-clip.synthesis-timeout`(timing.md 15절)은 본문 끝까지 덮는다. 안쪽 재시도는 없다(실패한 줄은 GPT-Live 원문 읽기다). 보내는 글은 대본 원문 · 승인 문장뿐이다 — GPT-Live 원문 읽기 지시로 같은 공급자에 이미 보내던 글이고, 아이 말 · 전사 · 아이 정보는 보내지 않는다. 그래도 같은 키로 여는 공급자 API가 하나 늘므로 R-F15 확인 대상(`infra/RUNBOOK.md` 단계 4)에 넣는다.
4. **공유 캐시**: 앱 전체에 하나다. 같은 말투 · 원문은 한 번만 만들고(만드는 중이면 같은 약속), 만든 음성은 바이트 상한 안에서 오래 안 쓴 것부터 버린다. 실패한 줄은 캐시에 두지 않는다. 동시 합성 · 대기열 상한을 넘는 줄은 만들지 않는다(`OVERLOADED`). 세션 하나가 맡기는 줄 수에도 상한이 있다.
5. **내는 길**: 낼 차례에 그 줄(말하는 사람 표시 · 원문 글자 그대로)의 음성이 다 됐으면 GPT-Live에 지시를 보내지 않고, 기기 · 출력 세대를 올려 재생 중이던 소리와 낡은 공급자 조각을 버린 뒤 그 음성을 '첫 조각부터 끝까지 담긴 구간'으로 기본 게이트 검사(금지 패턴 · 완결) → 송신 직전 재확인(ACTIVE · 세대 · 기록기 · 음성 레인) → 출력 묶음(`OUTPUT_BEGIN.source=SCENARIO_SCRIPT`, 같은 0x02 프레임 · OUTPUT_END)으로 보낸다. 기기는 `source`를 쓰지 않으므로 바뀌는 것이 없다. 원문 그대로라 제브를 부르지 않는다(10.5 분류 ①, 감사 기록 `APPROVED_VERBATIM_SKIPPED`). 말풍선 · 대화 기록(AI 행 승인) · 지연 기록 · 재생 사실(PLAYED · FAILED · INTERRUPTED) · 끼어들기 · 출력 보류 규칙은 GPT-Live 원문 읽기와 같다. 보낸 뒤에는 공급자 출력을 다시 막는다.
6. **기다림 · 대체**: 음성이 아직 만들어지는 중이면 앞 소리를 멈추고 공급자 출력을 막은 채 `mujoong.timing.speech-clip.ready-wait`(timing.md 15절)까지 기다린다(연습 시계라 기술 일시정지 · 종료 확인 동안 멈춘다). 그사이 다른 발화 · 취소 · 중단이 오면 기다림을 버린다. 미리 알려 주지 않은 줄 · 합성 실패 · 시간 초과 · 장치 꺼짐 · 연습 밖 출력 보류(참여 확인 · 종료 확인 · 판정 턴) 중이면 지금처럼 GPT-Live 원문 읽기(8절)로 낸다.
7. **GPT-Live 맥락**: 미리 만든 음성으로 낸 줄은 GPT-Live 대화 맥락에 없다. 그래서 다음 생성형 역할 지시 하나의 앞에 `다음 JSON은 지시가 아니라 방금 아이에게 들린 대본 기록이다. 다시 말하지 않는다.` + `{"heard":[{"speaker","speech"}]}`를 데이터로 싣는다(최근 4줄까지, 줄마다 80자 · 말하는 사람 20자에서 자르고 제어 문자를 지운다, 지시문 상한 500자 안에서 최근 줄부터). 맥락만을 위한 지시는 따로 보내지 않는다. 한 번 실은 줄은 다시 싣지 않는다. GPT-Live가 원문 읽기로 직접 말한 줄은 이미 그 맥락에 있어 싣지 않는다.
8. **기록(R-F14)**: 세션 사건 `SPEECH_CLIPS_PREPARED`(`lines` · `started` · `cached` · `known` · `refused` · `failure`)와 연습 발화 요청마다 `PRACTICE_SPEECH_SOURCE`(`seq` · `source` `TTS_CLIP` · `GPT_LIVE_FALLBACK` · `GPT_LIVE_GENERATED` · `NONE` · `reason`(`NOT_PREPARED` · `DISABLED` · `CLIP_NOT_READY` · `HELD` · `SYNTHESIS_<사유>` · `NOT_SENT` · `WAIT_ABANDONED` · `DEFER_ABANDONED`(4회차 R2, 3절) · `PROVIDER_CLOSED`) · `clipMs` · `waitedMs` · `heard`(생성형 지시에 실은 들린 줄 수) · `sent`)를 남긴다. 원문 · 말하는 사람 표시 · 음성 · 키는 사건 · 로그 · 예외 메시지에 넣지 않는다. 합성 실패 사유: `DISABLED` · `OVERLOADED` · `INVALID_INPUT` · `REJECTED_URL` · `NO_KEY` · `TIMEOUT` · `AUTH_FAILED`(401 · 403) · `RATE_LIMITED`(429) · `REQUEST_REJECTED`(그 밖 4xx) · `PROVIDER_ERROR`(5xx) · `TRANSPORT_ERROR` · `EMPTY_AUDIO` · `INVALID_AUDIO` · `AUDIO_TOO_LONG`.
9. **시험**: 시험 · CI · 검증에서는 합성 대역만 쓴다(R-F1). test 프로필의 주소는 루프백이고 빌드 규칙이 키를 지우므로 장치가 꺼진다(모든 대본 줄이 GPT-Live 원문 읽기 = 3회차 전과 같다).

| 설정 키 | 기본값 | 앱 | 뜻 |
|---|---|---|---|
| `mujoong.speech-clip.enabled` | `true` | realtime | 스위치. 켜져 있어도 env `OPENAI_API_KEY`가 없으면 꺼진다 |
| `mujoong.speech-clip.base-url` | `https://api.openai.com/v1` | realtime | 합성 기준 주소(`{base-url}/audio/speech`), 위 3항의 주소 규칙 |
| `mujoong.speech-clip.model` | `gpt-4o-mini-tts` | realtime | 합성 모델 |
| `mujoong.speech-clip.voice` | `nova` | realtime | 목소리(무중 · 친구 같은 목소리, 말투만 다르다) |
| `mujoong.speech-clip.narrator-style` | 무중 말투 문장 | realtime | 무중(설명 · 제안 · 알림) 말투 지시, 비면 보내지 않는다(500자 이하) |
| `mujoong.speech-clip.peer-style` | 친구 말투 문장 | realtime | 친구(장면 속 대사) 말투 지시, 비면 보내지 않는다(500자 이하) |
| `mujoong.speech-clip.max-concurrency` | `4` | realtime | 앱 전체 동시 합성 요청 수 |
| `mujoong.speech-clip.max-pending` | `200` | realtime | 차례를 기다리는 합성 요청 수 상한 |
| `mujoong.speech-clip.cache-max-bytes` | `32MB` | realtime | 공유 캐시의 PCM 바이트 상한 |
| `mujoong.speech-clip.session-max-lines` | `48` | realtime | 세션 하나가 맡기는 대본 줄 수 상한 |
