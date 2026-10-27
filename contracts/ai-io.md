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
| 리포트 항목 출력 | 6.5절 — 리포트 생성 때 AI를 부르지 않아 AI 출력이 없다. 10항목 모두 서버가 만든다 [편차 FT-1023-c] | Report → core_report_section(U6) |
| 금지 표현 2차 분류 출력 | 6.6절 | TextAiGateway(U6) |
| 금지 표현 사전 항목 | `{ id, pattern, category: DIAGNOSIS\|TREATMENT\|SCORE\|CAUSE\|GENERALIZATION\|EMOTION_CLAIM\|PRESSURE\|PROFANITY, appliesTo: [OUTPUT_GATE, RESULT_TEXT, PDF, APPROVED_CONTENT] }` — `PROFANITY`(욕설)는 2026-10-23 JIRA-266으로 더한 값이다(편차 FT-1023-b, 4절) | OutputGate(U1 시드 · U3 정식) · TextAiGateway · Report |

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

공급자가 지원하는 클라이언트 메시지 type은 열다섯 개다(실호출 오류 문장): `session.start` · `session.update` · `session.close` · `session.input_audio.append` · `session.input_audio.mute` · `session.input_audio.unmute` · `session.instructions.append` · `session.thinking.append` · `session.commentary.append` · `session.output_audio.fixture.play` · `response.create` · `response.item.create` · `decision.create` · `decision.item.create` · `decision.item.delete`. 이 밖의 type은 `error{type: invalid_request_error, code: invalid_value, param: type}`로 거절된다. 응답 취소(`session.response.cancel`)와 지시 교정(`session.instructions.correct`), `session.response.create`는 목록에 없다. **서버는 아래 다섯 type만 보낸다**(2026-10-22 승인된 연습 낭독에 `session.commentary.append` 추가; 같은 직렬 송신 큐와 출력 게이트를 사용한다). `response.create`는 Responses 위임 세션 전용이라 우리 세션(client 위임)에는 `error`("requires a session with Responses delegation")로 거절되고(실호출), `session.update`도 Responses 위임 설정에만 쓸 수 있어 보내지 않는다(문서). 자동 응답 · 말 끝(turn detection) 설정은 없다(문서).

| 방향 | type | 필드 | 출처 · 쓰임 |
|---|---|---|---|
| 서버 → 공급자 | `session.start` | `session{model, instructions, audio{format{type: "audio/pcm", rate: 24000}}}` — `model`은 코드 상수, `instructions`는 서버 템플릿 지시문(아이 정보는 따옴표로 묶은 데이터로만) | 실호출(중첩 모양 · 24kHz 수락). 연결 직후 한 번. 응답 `session.started`가 ACTIVE 전이 조건의 하나다(BR6.8) |
| 서버 → 공급자 | `session.input_audio.append` | `audio`(base64, 기기 0x01 프레임 본문 960바이트 그대로) | 실호출. ACTIVE 뒤 ~ CLOSE_TRANSPORT 전 기기 프레임을 순번 순으로(BR7.1 · R3-8). 그 밖의 프레임은 버린다. 참여 확인(WAITING_CHILD)부터 중계하고 원음 기록은 ACTIVE부터다(U4b-J2) |
| 서버 → 공급자 | `session.instructions.append` | `event_id`(서버 발급, 확인 사건의 `client_event_id`로 돌아온다) · `delegation_id`(**항상 있어야 한다, 값은 `null`**) · `content`(지시문, 500토큰 이하) | 실호출. `delegation_id` 키가 없으면 공급자가 `error{missing_required_parameter, param delegation_id}`로 거절한다 — 이 프로젝트 JSON 코덱이 null 필드를 쓰지 않으므로 이 속성만 항상 쓰게 한다. 옛 본문 필드 `text`는 없다. **일반 지시·교정·생성 역할 지시는 이 경로를 유지한다. 승인된 연습 낭독의 발화 요청은 아래 commentary 경로로 구분한다. 지시 수락은 발화 시작·재생을 보장하지 않는다**(참여 뒤 첫 응답 · 확인 뒤 이어 가기 · 연습 발화 · 연습 `requestResponse` · 게이트 교정 · 판정 뒤 지시, 아래 '서버 발화'). 전사 · 걸린 패턴 · 미승인 원문은 넣지 않는다 |
| 서버 → 공급자 | `session.commentary.append` | `event_id` · `delegation_id: null` · `content` | 2026-10-22 실호출. 승인된 연습 낭독의 음성 출력 요청. 동일한 고정 낭독 지시와 범위 종료 문장을 사용한다. 음성이 생성돼도 승인 원문과 다르면 기존 게이트가 차단한다. |
| 서버 → 공급자 | `session.close` | — | 종료 · FAILED 전이 뒤 공급자 세션 정리(BR6.10 ④). 응답 `session.closed`(실호출) |
| 공급자 → 서버 | `session.started` | `session{id, ...}` | 실호출. `session.id`를 공급자 세션 id로 읽는다 |
| 공급자 → 서버 | `session.output_audio.delta` | `delta`(base64). **응답 id · 시간 정보가 없다** | 실호출. AI 말 음성 조각. 게이트 구간 버퍼에만 쌓는다(BR7.3 · BR11.6). 구간이 확정 · 통과될 때까지 기기로 보내지 않는다(BR7.4). 한 응답이 조각 300 ~ 350개(30 ~ 35초)일 수 있다 |
| 공급자 → 서버 | `session.output_transcript.delta` | `start_ms` · `end_ms` · `delta` · `event_id` | 실호출. AI 말 전사 조각. 게이트 구간 버퍼에만 쌓는다. **음성보다 앞서 도착하고 그 폭이 늘어난다**(전사 `end_ms`와 도착 때 오디오 시계의 차 0.3 ~ 2.1초). `start_ms` · `end_ms`는 세션 전사 시간선의 ms다 |
| 공급자 → 서버 | `session.input_transcript.delta` | `start_ms` · `end_ms` · `delta` · `event_id` | 실호출. 아이 전사 조각(PARTIAL, BR7.5). **완료 사건이 없다** — 서버가 조각을 묶어 확정한다(아래). `confidence`는 오지 않는다 |
| 공급자 → 서버 | `session.instructions.appended` | `event_id` · `client_event_id` · `start_ms` · `end_ms` | 실호출. 지시 확인 사건. "세션 시간선이 추가한 지시의 추정 끝에 닿을 때" 온다(문서). `client_event_id`가 우리가 실은 `event_id`와 맞고, `end_ms`가 지시 뒤 새 말이 시작할 수 있는 전사 시간선의 하한이다 |
| 공급자 → 서버 | `session.commentary.appended` | `event_id` · `client_event_id` · `start_ms` · `end_ms` | 2026-10-22 실호출. 기존 지시 확인 사건과 같은 내부 상관·시간선 처리로 전달한다. 확인만으로 음성 생성·전달·재생 완료를 확정하지 않는다. |
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
- **지시 없이 올린 세대(설계 개정 A5)**: 끼어들기 · 종료 의도 · 연습 폐기 · 게이트 폐기 · 출력 보류 해제는 마지막 조각 시각과 세대를 올린 시각 중 늦은 쪽에서 `mujoong.gate.segment-quiet`(800ms)가 지나면 끊김을 확인하고 그 뒤 처음 오는 조각부터 받는다. 출력 보류 중(참여 확인 · 연습 보류)에는 단계와 관계없이 조각을 버린다. 응답 취소 type이 없으므로 이 경우 공급자로는 아무것도 보내지 않고 낡은 응답은 자연 종료까지 이어진다.
- **구간**: 받는 단계에서 마지막 조각 뒤 `segment-quiet`가 지나면 구간을 '완전'으로 봉인해 게이트에 넣는다(응답 끝 사건이 없다). 응답이 30초를 넘을 수 있어 구간 상한(20초 분량)으로 자르는 경로가 기본 경로다. 세대가 바뀌어 닫은 구간 · 첫 조각을 놓친 구간 · 아이 입력이 끊김 전에 재개되어 닫은 구간은 불완전이라 게이트가 GATE_EMPTY로 폐기한다(SD-17 3단계 fail-closed). 지시 뒤 전사만 받고 음성이 여유 시각에 닿지 못한 채 끝난 구간도 같다.
- **입력 전사 묶음(설계 개정 A7)**: 서버가 `session.input_transcript.delta`를 묶고, `mujoong.timing.provider.input-transcript-quiet`(timing.md 14절, 임시 1000ms) 동안 조각이 없으면 그 묶음을 확정 전사로 만든다. 확정 전사는 지금 확정 전사 입구(`InputTranscriptDone`, 신뢰도 null = 확실)와 같은 길로 흘러 참여 확인 · 종료 의도 · 연습 흐름을 바꾸지 않는다. 지연 출처는 `INPUT_TRANSCRIPT_FINAL` · `LAST_INPUT_FRAME`이다. 아이가 길게 쉬면 한 발화가 둘로 나뉠 수 있다.

### 3.2 가짜 공급자

백엔드 시험 `FakeProviderWsServer` · 인메모리 `FakeProviderPort` · C안 `tools/fake-provider/` 셋은 위 관측 형식만 내고 공급자처럼 엄격하다: 중첩 `session.started{session{id}}`, 응답 id 없는 `delta` 음성, 전사 조각 `{start_ms, end_ms, delta, event_id}`(출력 전사는 음성보다 앞서 도착), 입력 전사 완료 · 말 끝 · 응답 끝 사건 없음, 오류는 `error{error{type, code, param}}`. 지원 목록 밖 type은 `error{invalid_request_error, invalid_value, param type}`, `delegation_id` 키가 없는 `append`는 `error{missing_required_parameter, param delegation_id}`, `response.create`는 `error{invalid_request_error, "response.create requires a session with Responses delegation"}`로 거절한다. 올바른 `append`에는 확인 사건 `session.instructions.appended{client_event_id, start_ms, end_ms}`를 곧바로 돌려주고, 지시에 대한 새 말은 확인 `end_ms` 뒤에서 낡은 꼬리를 흉내 낸 채움 음성 다음에 시작한다(채움은 앱이 여유 시각까지 버린다). 지속형 지시(문장에 "이번 한 번만"이 없는 것)는 남고 한 번만 지시는 남지 않는 단순 모델을 `FakeProviderWsServer`가 흉내 낸다. 받은 입력 음성 바이트 수 · 해시와 보낸 출력 음성 해시를 기록하고(J131), `session.instructions.append`는 본문 길이 · 해시 · `delegation_id` 키 유무만 기록한다(원문 저장 없음). `Authorization` 헤더는 보지 않고 Bearer 형식인지만 기록한다 — 키가 비면 헤더가 없고 연결은 받는다. 시나리오: 정상 · 금지 패턴 응답 · 연결 거부 · `session.started` 없음 · 지연 · 연결 단계 무응답. 턴 종류 `join`은 참여 확인 대답("응") 입력 전사 조각만 보내고 AI 출력은 없다(`--turns join,normal,forbidden`). `--confidence` 옵션은 없다(신뢰도는 오지 않는다).

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
| 로딩 거부 | 파일 없음 · 파싱 실패 · 중복 id · category 8개 중 빈 것 · 공통 욕설 사전 로딩 실패 · 정규화 뒤 형태가 아닌 패턴(공백 · 구두점 · 대문자 라틴 · 정규식 메타 문자 포함) → Realtime 기동 거부(S1b, 종료 코드 1). 로딩 완료가 readiness 조건이다(S6) |
| U1 시드 항목(J101 · 사람 결정 12) | DIAGNOSIS `진단` · TREATMENT `치료` · SCORE `비정상` · CAUSE `핵심원인` · GENERALIZATION `학교에서도그럴` · EMOTION_CLAIM `화가났군요` · PRESSURE `꼭해야해요`. `점수` · `정상이에요` · `정상입니다`는 정식 사전 v1(D 담당)에서 더한다(알려진 틈) |
| 욕설 범주 `PROFANITY`(JIRA-266 · 편차 FT-1023-b) | 이 파일에는 범주 표지인 대표 항목 `SEED-PROFANITY-1`(`씨발새끼`) 하나만 둔다. 어휘 · 변형 · 예외 낱말은 두 앱 공통 욕설 사전(`backend/contract-model/src/main/resources/kr/mujoong/contract/text/profanity-lexicon.txt`, 판정기 `kr.mujoong.contract.text.ProfanityLexicon`)이 맡고, 게이트는 파일 항목을 먼저 본 뒤 공통 사전의 문장 판정을 본다. 공통 사전은 NFKC · 라틴 소문자 뒤 문장부호 · 숫자 · 기호를 지우고, 공백은 낱말 경계로 남겨 낱말마다(한 글자 낱말이 이어진 곳은 이어서) 찾으며, 예외 낱말(`시발점` · `수박씨` · `아저씨` 등)을 먼저 지운다. 이 파일은 공백을 지우고 찾으므로 낱말 경계를 넘어 걸릴 짧은 욕설(`아저씨 발` → `씨발`)은 파일에 두지 않는다. 걸리면 `GATE_PATTERN` 폐기 뒤 교정 지시 `PROFANITY`를 보낸다. 같은 공통 사전을 core 아이 이름 입력(오류 `CHILD_NAME_NOT_ALLOWED`)과 realtime 세션 시작 지시문의 이름 대체(중립 호칭 `친구`, 사유 코드 `ChildNameProfanityReplaced` INFO)에 쓴다 |

## 5. 승인 문구 · 승인 음성 (J60 · J102 · J114 · J117)

| contentCode | 쓰임 | 시드 문구(임시) | 음성 파일(audioUrl) |
|---|---|---|---|
| `CLOSING` | 끝내기 마무리 인사(BR6.10, 한 번만, 3초 이내) | 오늘 이야기 즐거웠어. 다음에 또 만나! | `/media/approved/closing-v1.wav` |
| `WAITING_FILLER` | 게이트 폐기 뒤 대기 · 대체 안내(BR7.4) | 음, 잠깐만 생각해 볼게. | `/media/approved/waiting-filler-v1.wav` |
| `CONSENT_POLICY` | 현재 정책 버전 자리(최고 APPROVED 버전 행의 `content_text`, 임시 `1.0`) | — | 없음 |

- 음성 파일은 WAV · PCM16 · 24kHz · 모노이고 개인정보가 없다. U1 임시 파일은 생성기 `tools/seed/gen-approved-wav.mjs`가 만든 합성 무음 · 톤이다(J102, 실제 녹음은 D 담당).
- 세션 패키지는 시작 때 문구 버전을 고정해 Realtime에 건네고(`approvedContent`), Realtime은 START_SESSION으로 기기에 미리 받게 한다.

## 6. 분석 AI 작업 (U6 ResultPipeline — FR4.3 · FR6.5 · FR7.4 · FR7.6 · FR7.11)

리포트(REPORT 작업)는 이 절의 AI를 부르지 않는다. 6.5는 리포트 항목을 서버가 만드는 규칙이다 [편차 FT-1023-c].

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

### 6.5 리포트 항목 (28 요청 때, REPORT 작업 — AI 호출 없음 [편차 FT-1023-c])

리포트 생성 때 텍스트 AI(항목 글 생성)와 금지 표현 2차 분류를 부르지 않는다. 입력은 모두 저장된 기록 · 결과 · 구조화 · 장면 판정이다. 2 · 5 · 6 · 10항목의 서버 문구는 임시 문구(J118)이고, 고정 문구는 1차 사전 시험(`ForbiddenDictionaryTest`)으로 금지 표현이 없음을 고정한다. 동의 철회로 끝난 활동의 전사 · 결과는 어느 항목의 글 · 인용에도 넣지 않는다(10항목 끝난 이유 집계에만 센다). 자료가 부족한 항목은 다른 설명 · 대체 내용 없이 "자료 부족"만 낸다.

정리 중 활동: 고른 활동 중 결과 정리가 끝나지 않은 활동(결과 머리 없음 · REQUESTED · RUNNING)이 있으면 REPORT 시도를 오류 코드 없이 미루고 `mujoong.timing.report.result-settle-poll`(10초) 간격으로 다시 본다(미룬 시도는 시도 횟수를 쓰지 않는다). 접수부터 `report.result-settle-wait`(5분)가 지나면 그때 있는 자료로 만든다(정리 중 활동은 10항목에 센다). 리포트 절대 마감 `report.ready-target`은 6분이다. 정리하지 못한(FAILED) 활동 · 결과 대상이 아닌(결과 머리가 NONE) 활동 · 동의 철회 활동은 기다리지 않는다.

인용 글: 저장 전사 발화에서 앞머리 문장부호 잡음("..", "?", "]" 등) · 뒤끝 괄호 · 구분 기호 · 대괄호 비언어 표지(`[cry`, `[hum]` 등)를 걷어 낸 글이다. 저장 전사 원문은 바꾸지 않는다. 표지가 앞뒤에만 있으면 인용 글은 원문의 부분 문자열이고, 가운데 표지를 걷은 경우에만 부분 문자열이 아니다(표지 자리를 공백 하나로 둔다). 걷어 낸 뒤 글자가 없거나 추임새("음" · "흠" · "어" 등)뿐인 발화는 인용 후보가 아니다. 한 항목 안에서 같은 (세션, 발화) 인용은 한 번만 단다.

| 항목 | 만드는 쪽 | 규칙 |
|---|---|---|
| 1. 활동 개요 | 서버 집계 | 기간 · 자유대화 n회 · 연습 n회 · 총 n분 |
| 2. 관찰된 장면(인용) | 서버 규칙 | 연습 장면 판정이 가리키는 아이 발화를 날짜순 인용(TRANSCRIPT)으로 낸다. U5b 형식은 장면 1 = FIRST, 장면 2 = NEW_SCENARIO 반응 발화와 같은 상황의 AFTER_CUE 반응 발화, 새 연습 흐름 형식은 장면 판정에 발화 번호가 없어 그 장면 시작부터 다음 장면 시작 전까지의 아이 발화(내용 있는 발화 먼저, 장면당 2개)다. 줄마다 "m/d hh:mm · 목표 이름 · 장면 n · 장면 결과 칩"을 쓴다. 정리 끝(DONE) 연습만 쓰고, 활동당 5개 · 항목 30개까지다. 연습 장면 인용이 하나도 없으면(자유대화만 · 정리 중 연습만 포함) "자료 부족"만 낸다(자유대화 대체 인용 없음) |
| 3. 연습 목표와 장면 | 서버 집계 | 목표별 장면 수 · 장면 결과 칩(6.2) · 도움 횟수 |
| 4. 아동 정보 | 서버 | 이름 · 나이(ChildContext). 관심사 없음 |
| 5. 최근 실제 경험과 출처 | 서버 규칙 | 정리 끝 활동의 저장된 경험 구조화 중 실제 생활 경험만 날짜순으로 낸다. 등장인물이 있고 모두 이번 대화의 참여자(별칭 아이 · 나 · 저 · AI · 에이아이 · 인공지능 · 무중 · 무중이, 소문자 · 공백 제거 뒤 비교)이며 상대가 한 말이 없는 구조화는 이번 대화 자체로 보고 통째로 뺀다. 남는 구조화의 CHILD_RECALL 칸은 "m/d · 칸 이름 · “칸 글”"(등장인물은 "m/d · 등장인물 · 별칭")과 그 발화 인용(CHILD_RECALL)이다. 참여자 별칭 · 1차 사전에 걸리는 별칭의 등장인물 줄, 칸 글이 근거 발화에 들어 있지 않은 줄(줄과 근거가 맞지 않음), 발화 번호가 전사에 없거나 인용할 수 없는 발화인 줄은 내지 않는다. GUARDIAN_INPUT 칸은 줄을 내지 않고(보호자 입력 원문 · 칸 이름만 남는 줄 없음), 그 구조화에서 줄이 하나라도 남을 때만 quote 없는 출처 인용(GUARDIAN_INPUT) 하나를 단다. 남는 줄이 없으면 "자료 부족" |
| 6. 주요 대화 내용 | 서버 규칙 | 결과 문장이 있는 정리 끝 활동마다 "m/d · 자유대화/연습 · 저장된 결과 문장(core_activity_result.sentences_json)"을 날짜순으로 낸다. 결과 문장이 없는 활동(정리 중 · 실패 · 문장 없음)은 줄을 내지 않는다(인용 대체 없음). 모든 활동에 결과 문장이 없으면 "자료 부족" |
| 7. 살펴본 화용 행동 | 서버 | 목표 이름 · 핵심 기능 정의(BehaviorDefinitionLookup) |
| 8. 제공한 도움 | 서버 집계 | 도움 횟수 · 동의 응답 분포, 예 1개(deliveredText 중 정답 문장 없는 것) |
| 9. 과거 기록과의 차이 | 서버 | 같은 목표의 장면 칩을 날짜순으로 나열(장면 · 도움 조건 함께). 범위 안 같은 목표 기록이 2회 미만이면 자료 부족. 향상 여부를 계산하지 않는다 |
| 10. 제한사항 | 고정 문장 + 서버 규칙 | 고른 활동에 연습이 있을 때만 고정 문장 "AI와의 연습 장면에서 본 기록이며 실제 친구 관계에서도 같은지는 알 수 없어요."를 첫 줄에 둔다(자유대화만이면 붙이지 않고 대체 문장도 없다). 해당할 때만 "정리 중인 활동 n개" · "정리하지 못한 활동 n개" · "종료가 확인되지 않은 활동 n개" · "끝난 이유 · (27 끝난 이유 문구) n회 …" · "미실시 장면 · (미실시 사유 문구) n개 …"를 더한다. 줄이 하나도 없으면 "자료 부족" |

- 2 · 5 · 6 · 10항목에 낼 줄이 없으면 text "자료 부족", missing = true다. 인용은 위 인용 글 규칙으로 잡음만 걷은 저장 전사 글이며 발화 시각을 함께 둔다.
- 항목 이름 · 순서는 FR6.5 표로 고정한다. 고지 고정 문구: "이 리포트는 진단 결과가 아니에요. 전문가에게 보여 줄 참고 자료예요. 제한사항은 마지막 항목에 있어요."
- level: 범위 안 활동의 결과 수준(6.4)이 모두 INSUFFICIENT면 INSUFFICIENT, 하나라도 FULL이 아니면 PARTIAL, 모두 FULL이면 FULL.
- 응답 모양(`report-response.schema.json`) · 화면 · PDF 모양은 바뀌지 않는다.

### 6.6 금지 표현 2단계 (결과 문장 · 생활 팁)

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

- 2차(U6-J 초안): 1차를 통과한 결과 문장 · 후보 이유를 작업당 한 번에 묶는다(리포트는 2차 분류를 부르지 않는다 [편차 FT-1023-c]). 입력 `{ "sentences": [{"id":"s1", "text":"…"}] }`, 출력 `{ "results": [{"id":"s1", "violation":true, "category":"EMOTION_CLAIM", "span":"…"}] }`. 모든 요청 id가 정확히 한 번 나와야 한다. 지시: "아래 문장이 금지 중 하나에 해당하는지 판단하라. 저장 전사와 대조를 통과한 직접 인용은 금지가 아니다. 해당하면 분류와 문제 구간을 돌려준다." 승인 생활 팁은 1차 사전만 적용한다(BR7.2).
- 걸리면: 결과 문장은 고정 템플릿(6.3), 생활 팁은 보여 주지 않는다(FR6.3 '추가 관찰 필요').

### 6.7 경험 구조화 (U6-J 초안)

자유대화 전사(seq · speaker · text · uncertain), 종료 사유 · 확보 범위와 08 네 칸(knownContent · situationType · sourceType · question)을 입력한다. 아이 이름은 '아이', 인물은 별칭으로 바꾸고 식별자 · 원음은 보내지 않는다. 08 네 칸은 이 작업에만 전달한다.
출력은 `{ "characters": [{"alias":"친구 A", "source":"CHILD_RECALL", "utteranceSeq":1}], "situation": null, "partnerUtterance": null, "childResponse": null, "explanation": null }`이다. null이 아닌 칸은 `{text, source, utteranceSeq?}`이며 source는 GUARDIAN_INPUT 또는 CHILD_RECALL이다. CHILD_RECALL은 실제 아이 발화 번호와 글자 단위 근거가 필요하다. 의도 · 감정 등 추가 속성을 받지 않는다. 지시: 입력에 있는 사실만 분리하고 출처를 붙이며 없는 칸은 null로 둔다.

### 6.8 목표 후보 (U6-J 초안)

전사, 검증된 구조화(자유대화) 또는 장면 칩(연습), ACTIVE 정의 목록(behaviorKey · behaviorVersion · displayName · coreFunction)을 입력한다. 출력 `{ "candidates": [{"behaviorKey":"…", "behaviorVersion":1, "reasonText":"…", "evidence":[{"utteranceSeq":1,"quote":"…","kind":"CHILD_RECALL"}]}] }`에서 근거 인용 · ACTIVE 정의 · 금지 표현을 서버가 확인한 최대 3개만 등록한다. 근거 없는 후보와 보호자 입력만 근거인 후보는 버린다. 지시: 관찰된 아이 회상 발화만 근거로 쓰고 의도 · 감정을 단정하지 않는다.

### 6.9 U6 호출 예산과 생활 팁

결과 호출은 `mujoong.text-ai.result.request-timeout`과 작업 시도 잔여 시간 중 작은 값으로 제한한다. 게이트웨이 재시도 없이 작업당 최초 1회와 추가 최대 3회, 총 4회 시도하며 지연은 5·15·30초다(U14·FR7.6). INSUFFICIENT 사전 판정이면 AI 없이 DONE이다. 생활 팁은 레거시 목표 · 검증 후보 behaviorKey와 승인 항목의 교집합을 서버에서 찾고, 생성 후보를 임의 behaviorKey로 바꾸지 않는다. title · condition · content · limitation에 1차 금지 사전을 적용한다.

## 7. U5a TextAiGateway (U5a-d)

Core의 사후 텍스트 AI 호출은 TextAiGateway 한 곳을 지난다. TextAiProvider의 실제 구현은 이 유닛에 없으며 기본값은 UNAVAILABLE다. 예약 환경 변수 이름은 MUJOONG_TEXT_AI_ENDPOINT, MUJOONG_TEXT_AI_MODEL, MUJOONG_TEXT_AI_API_KEY이다. 시간 · 재시도 값은 application.yml `mujoong.text-ai.*`(timing.md 9절)다. 값을 넣는 것만으로 활성화되지 않으며 공급자 어댑터와 통합 검증이 필요하다. Realtime 음성 설정과 공유하지 않는다.

입력 ScenarioPrompt는 목표 이름/행동 정의/기회 조건/상황 조건/관계, 나이, 관심사, 새 상황일 때 이전 상황 본문뿐이다. 아이/보호자 이름·식별자·전사·보호자 자유 입력·판정 기준은 보내지 않는다. 입력/출력 record의 문자열 표현과 예외는 본문을 숨긴다.

- **열린 점(U10에서 정함):** U9 Luna 어댑터(`LunaTextAiProvider`)는 지금 관심사를 공급자로 보내지 않는다. `interests`가 10.10 보내기 전 정리의 금지 키(기존 결과 게이트웨이 `PRIVATE_KEYS`)라서 넣어도 지워지기 때문이다. 이 절의 입력 목록과 금지 키가 부딪히므로, 관심사를 보낼지는 U10이 새 스키마로 바꿀 때 정한다.

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
| 공급자 송신 | 서버 내부 식별자는 보내지 않는다. `session.instructions.append.content`에는 고정 지시와 JSON 데이터 `{partnerRole, speech}`를 싣고 끝에 범위를 닫는 문장을 붙인다(승인된 발화를 그대로 읽는 지시라 맞장구는 더하지 않는다). 응답 요청 type이 없으므로 이 지시 하나가 발화를 시작한다: 응답 취소 없이 출력 세대를 올리고 지시를 서버 송신 직렬 큐(3절)로 보내며, ACTIVE 밖이면 요청을 버린다. 확인 사건 · 새 말 전사가 시한 안에 오지 않거나 송신이 실패하면 확장에 실패를 알린다. |
| 공급자 출력 | 기존 OutputGate를 통과하고, 공백을 제거한 실제 전사가 승인된 `PracticeSpeech.text`와 정확히 같아야 기기로 보낸다. 전사/음성 누락 · 내용 불일치 · 게이트 거절 · 송신 실패를 성공으로 취급하지 않는다. 실제 playbackId를 서버 내부 발화와 연결한다. |
| 기기 재생 증거 | PLAYBACK_MARK의 STARTED → STARTED, COMPLETED → COMPLETED, STOPPED → INTERRUPTED, FAILED → FAILED로 구분한다. PROGRESS는 단계 완료가 아니다. `PlaybackFact`는 발화의 상관 ID · 상황 ID · 세대와 서버 수신 시각을 보존한다. 누락 · 만료도 완료로 만들지 않는다. |

상황 COMPLETED의 **서버 수신 시각**이 `scenarios[].exposedAt`과 첫/새 상황 반응 기회의 시작이다. 도움 질문 COMPLETED부터 동의 대기, Cue STARTED부터 `cues[].givenAt`, Cue COMPLETED부터 도움 후 반응 기회를 계산한다. 사건을 큐에서 나중에 처리해도 수신 시각을 처리 시각으로 바꾸지 않는다. 거부 · 무응답 · 재생 전 실패의 Cue 행에는 `givenAt`이 없으므로 Cue 배열 길이는 실제 도움 제공 횟수가 아니다.

기술 일시정지 시간만 반응 기회에서 뺀다(종료 확인 질문 STOP_CONFIRM은 2026-10-22에 없앴다, `deviations.md` FT-1022-b). 재생 사실이 지연 전달되어도 수신 뒤 실제 정지 구간만 `pausedFor`로 넘기고 일반 큐 지연은 더하지 않는다. 노출/도움 원본 시각과 이미 수락한 판정의 전체 시한은 바꾸지 않는다.

보호자 진행 조회는 `practice{goalIndex, goalCount, goalName, stage}`뿐이다. 상황 · Cue 원문, 진행 중 판정과 판정 기준은 보내지 않는다. 내부 `record.practice`의 응답/도움은 각자 scenarioId를 보존하고, 노출 정산은 `scenarios[].exposedAt`만 사용한다.

`CueProvider`와 진행 중 판정 공급자의 운영 기본은 미연결 실패다. 이 문서는 실제 공급자 연결 · 장치 재생 · 부하 검증 완료를 뜻하지 않는다. 해당 구현과 검증은 별도 인계 항목이다.

## 10. U8 공통 규칙 (GPT-6-Luna · 제브 구조화 입출력 · 판 리소스 · 승인 발화)

U8(계약 · 버전 리소스)이 정한 공통 규칙이다. Luna · 제브 구조화 입출력의 **정본은 아래 JSON Schema 파일**이고, 이 절은 규칙 · 금지 필드 · 실패 처리만 적는다. 코드는 같은 파일로 공급자에 구조화 출력을 요청하고, 프롬프트 판(`promptVersion`)과 스키마 판은 함께 올린다. 각 단위(U9 ~ U15)는 더하기만 한다 [U8 편차 (U8-a)].

### 10.1 스키마 파일

U15 씨앗 v2는 GENERATED/DUPLICATE/NONE의 배타적 출력 분기를 사용한다. 필드·중복 원장·재시도 공유 규칙은 [지식 그래프 내부 계약](knowledge-graph.md)을 따른다. outcome 없는 기존 2필드 출력은 공통 호출부 호환용이며 U15 생성 성공으로 수락하지 않는다.

| 파일(`contracts/schemas/`) | 목적 | 호출 경로(단위) |
|---|---|---|
| `ai-judgment-request.schema.json` | 진행 중 판정 요청(J1 ~ J6, 공급자로 보내는 최소 필드) | realtime 판정 턴(U11) · `JudgeClient`(U9) |
| `ai-scenario-output.schema.json` | 연습 장면 대본 생성 출력(상대 역할 지시는 받지 않음, 7절 U5a-J22) | core 상황 생성(U10) |
| `ai-scenario-review-output.schema.json` | 생성과 분리한 자체 검토 출력(J7) | 같음 |
| `ai-goal-candidate-output.schema.json` | 목표 후보(6.8 U6 초안을 대체) | core 결과 작업(U14) |
| `ai-rejudgment-output.schema.json` | 새 연습 흐름 기록용 재판정 | 같음 |
| `ai-seed-output.schema.json` | 새 씨앗 | core 씨앗 생성(U15) |
| `guardrail-request.schema.json` | 제브 검사 요청(내부 형식, 어댑터가 공급자 형식으로 바꿈) | realtime 출력 게이트(U13) · `GuardrailClient` |
| `guardrail-result.schema.json` | 제브 검사 결과(내부 형식) | 같음 |
| `practice-record-v2.schema.json` | 기록 캡처 이벤트 `record.practice` 새 흐름 형식(`flowVersion` 필수) | U12가 채우고 U11이 발행, U14가 읽음 [U8 편차 (U8-d)] |

- 진행 중 판정에는 출력 스키마가 없다. 판정은 Decisions API `choice` 질문 한 개이고 응답은 보낸 선택지 · 확률 규칙으로 검증한다(10.10). 이전 Responses 구조화 출력용 `ai-judgment-output.schema.json`은 지웠다 [편차 FT-1023-d].
- 요청 스키마는 루트와 하위 객체 모두 `additionalProperties: false`로 닫는다. 아이 관련 문자열 필드의 `description`에는 "아이 이름은 '아이'로 치환된 값"을 적는다.
- 길이 초과 · 빈 응답 · 스키마 최종 실패는 어떤 목적에서도 성공으로 저장하지 않는다. 실패 사유 코드 · 모델 · 프롬프트 판 · 지연만 기록하고 요청 · 응답 원문은 남기지 않는다(BR5.3 · R-F14).

### 10.2 금지 필드와 외부 전달

- 외부 AI(Luna · 제브)로 보내는 필드는 스키마가 허용한 최소 필드뿐이다. 다음은 어느 요청 스키마의 속성도 될 수 없다: 아동 · 보호자 · 세션 · 기기 식별자(`childId` · `guardianId` · `sessionId` · `deviceId` 등), 키(`apiKey` 등), 원음(`audio`), 보호자 입력 원문, 이메일, 동의 상태. 아이 이름은 '아이'로 바꾼다(BR5.4 · NFR3.1). **예외(JIRA-266 · 편차 FT-1023-b):** 제브 출력 검사 요청은 설정 `mujoong.guardrail.jev.send-child-name`(10.12)이 켜진 경우에만 후보 문장 · 맥락의 아이 이름을 바꾸지 않고 원문으로 보낸다(욕설로 저장된 이름을 제브가 보게 하려는 것). 기본은 꺼짐이고, `infra/RUNBOOK.md` R-F15 확인 기록에 제브의 아동 이름 전달 허용 범위 확인이 남기 전에는 켜지 않는다. Luna 등 다른 외부 AI 경로는 이 스위치와 관계없이 언제나 이름을 바꾼다.
- 진행 중 판정은 Decisions 끝점(10.9 · 10.10)으로만 간다. 외부 전달 필드는 **최소 필드**다: 입력 정리(금지 키 제거 · 별칭 치환 · 연락처 가림)를 지난 `ai-judgment-request` 모양 데이터(지점 · 판 번호 · 선택지 · 확정 전사 · 최근 3턴 · 기준)와, 판 리소스의 고정 글(판정 질문 지시문 · 지점 질문 · 선택지 뜻)뿐이다. 식별자 · 아이 이름 원문 · 원음은 가지 않는다 [편차 FT-1023-d].
- **R-F15** 공급자 약관 · 보호자 동의 확인 전에는 실제 아동 데이터를 외부 모델로 보내지 않는다. 실호출은 합성 자료로만 한다. U9는 코드로 판정하지 않고 RUNBOOK 확인 기록으로 지킨다(편차 U9-b). 키(`OPENAI_API_KEY`)를 넣는 순간 실제 어댑터가 켜지므로, `infra/RUNBOOK.md` 단계 4의 R-F15 확인 기록 표에 한 줄을 남기기 전에는 운영 환경에 키를 넣지 않는다 [U9 편차 (U9-b)]. realtime 진행 중 판정은 Decisions 끝점(`POST {base-url}/decisions`)으로만 가고, 이 끝점은 Responses와 보존 · 데이터 사용 조건이 따로일 수 있으며 요청에 `store` 매개변수가 없다(보존을 요청으로 끌 수 없다). 그래서 realtime Luna(`mujoong.luna.enabled`)를 켜기 전에 같은 확인 기록 표에 Decisions 끝점 확인(보존 · 데이터 사용 조건, `store` 매개변수 없음)을 남긴다 [편차 FT-1023-d].
- 시험 · CI · 검증은 가짜 공급자만 쓴다(R-F1). 테스트 대역의 성공을 실공급자 성공으로 표시하지 않는다(R-M11).

### 10.3 목적 → 호출 경로 → 재시도 분류

| 목적 | 호출 경로(단위) | 분류 | 호출 안 재시도 · 실패 뒤 |
|---|---|---|---|
| 진행 중 판정 J1 ~ J6 | realtime 판정 턴(U11). Decisions API `POST {base-url}/decisions` 한 길이다(다른 끝점 · 대체 경로 없음) [편차 FT-1023-d] | `NONE` | 재시도 없음. `mujoong.timing.judgment.live-budget`(4초) 초과 · 응답 검증 위반 · 공급자 오류는 판정 보류. 하한(`mujoong.judgment.min-confidence` · `min-margin`)에 못 미치면 보류 |
| 제브 검사(새 발화) | realtime 출력 게이트(U13) | `NONE` | 재시도 없음. `mujoong.timing.guardrail.jev-budget`(800ms) 초과(시간 초과는 막힘) · 전송 오류(비 2xx 포함) · 잘못된 응답(10.13)도 막힘이고 자동 `ALLOW`는 없다. 막힘 뒤 같은 다음 행동 지시를 다시 하는 것은 게이트 단계의 일이다(호출 안 재시도가 아님) [U13 편차 (U13-b)] |
| 상황 생성 | core 상황 생성 요청(U10) | `REQUEST_BOUNDED` | 일시 실패 기술 재시도 1회 + 내용 재생성 1회(스키마 불일치 포함), 7절 15초 예산 안. 실패는 `SCENARIO_GEN_FAILED` |
| 자체 검토(J7) | core 상황 생성 요청(U10) | `REQUEST_BOUNDED` | 같은 15초 예산 |
| 새 씨앗 | core 결과 작업 뒤 씨앗 생성(U15) | `REQUEST_BOUNDED` | 실패하면 `skill_ref=NONE` 유지 |
| 결과 문장 | core 결과 작업(U14) | `JOB_ATTEMPT` | 호출 안 재시도 없음. 결과 작업 시도 실패로 세고 작업 재시도(timing.md 10절)로 다시 한다 |
| 목표 후보 | core 결과 작업(U14) | `JOB_ATTEMPT` | 같음(지금 코드에서 결과 작업 안에서만 부른다) |
| 기록용 재판정 | core 결과 작업(U14) | `JOB_ATTEMPT` | 같음 |

- 예산 밖 재시도 · 무한 재시도는 없다. 다른 경로에서 부르게 되면 그 단위가 분류를 다시 정한다(BR5.2).
- 리포트(REPORT 작업)는 어떤 목적의 AI도 부르지 않는다. 지시 파일 `prompts/result/report.v1.txt`와 리포트 전용 구조화 출력 모드는 없앴다 [편차 FT-1023-c].
- U14는 6.9와 timing.result를 최초 포함 총4회·추가 지연5/15/30초로 맞춘다(BR3.2). GOAL_CANDIDATES·REJUDGMENT·RESULT_TEXT는 각 작업 시도 안에서 추가 재시도를 하지 않는다. 새 HTTP 오류 code는 없다 — 판정 · 제브 실패는 내부 사유(보류 · 막힘), 상황 생성 실패는 기존 `SCENARIO_GEN_FAILED`다(BR7.3).

### 10.4 판 리소스와 세션 고정

- 판정 질문 · 선택지(J1 ~ J6, A §7.3 · B §5.2)는 realtime `judgment/option-sets-v1.json`, 현재 제브 검사 질문 · 라벨 설명(`PROFANITY` + `PASS`) · 모드는 `guardrail/policy-v3.json`에 둔다. 이전 `policy-v1.json` · `policy-v2.json`은 이력으로 남긴다 [U13 편차 (U13-c)]. J7은 core 상황 생성 자체 검토 프롬프트(U10)라 realtime 판에 넣지 않는다.
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
| `mujoong.luna.enabled` | `false` | realtime | realtime Luna 스위치(시간 값 아님). 키가 있어도 false면 `RealtimeLunaClient`를 등록하지 않는다(판정은 보류 구현). 켜면 진행 중 판정은 언제나 Decisions API로 간다. demo에서 true로 바꾸기 전에 `infra/RUNBOOK.md` 단계 4의 R-F15 확인 기록(Decisions 끝점 확인 포함)을 남긴다 [편차 FT-1023-d] |
| `mujoong.luna.base-url` | `https://api.openai.com/v1` | core-api · realtime | OpenAI API 기준 주소(core-api `{base-url}/responses`, realtime `{base-url}/decisions`). 두 앱이 같은 주소 규칙을 쓴다. test 프로필은 루프백으로 덮어쓴다 |
| `mujoong.luna.max-response-bytes` | `1000000` | core-api · realtime | 응답 본문 바이트 상한. 넘으면 `LENGTH_EXCEEDED` |
| `mujoong.luna.purpose.<목적>.max-output-tokens` | `4000` | core-api | 요청 `max_output_tokens`. core 목적: `scenario-generation` · `scenario-review` · `seed-generation` · `result-text` · `goal-candidates` · `rejudgment` |
| `mujoong.luna.purpose.<목적>.reasoning-effort` | `medium` | core-api | 요청 `reasoning.effort`(`none` · `minimal` · `low` · `medium` · `high`). realtime Decisions 요청에는 출력 한도 · 추론 강도 매개변수가 없어 realtime에는 이 키가 없다 |

### 10.10 U9 호출 방식 · 실패 사유 · 기존 어댑터

- **호출 방식:** 두 앱이 각자(코드 공유 없음) JDK `HttpClient` + `JsonCodec`으로 직접 부른다 [U9 편차 (U9-a)]. core-api는 Responses API `POST {base-url}/responses`, realtime(진행 중 판정)은 Decisions API `POST {base-url}/decisions`(아래)다 [편차 FT-1023-d]. 리다이렉트를 따르지 않고(3xx는 `REQUEST_REJECTED`) 프록시를 쓰지 않으며, 응답 본문은 `max-response-bytes`까지만 읽는다. core-api 요청은 `model` · `instructions`(버전 파일의 지시) · `input`(정리된 데이터의 JSON 문자열, 지시와 다른 자리) · `text.format` · `max_output_tokens` · `reasoning.effort` · `store: false`이고 `temperature`는 보내지 않는다(기존 `ResultTextProvider.Request.temperature`는 받기만 하고 무시).
- **Decisions 경로(realtime 진행 중 판정의 유일한 경로) [편차 FT-1023-d]:** `POST {base-url}/decisions`. core Responses 호출과 같은 규칙(주소 재확인 · 리다이렉트 · 프록시 · 응답 상한 · 시한 · 보내기 전 정리 · 요청 스키마 검증 · 감사 한 줄(10.8))을 지나고 본문 모양과 2xx 응답 검증만 다르다.
  - 요청: `model`(`mujoong.luna.model`) · `input`(사용자 메시지 하나, 파트 `{"type":"input_json","data":<정리된 판정 입력>}`) · `questions`(`choice` 질문 한 개: `name` `judgment`, `instructions` = 판정 질문 지시문 `judgment/prompt-service-v3.txt`(응답 방식과 "아이의 가장 마지막 말(childUtterance) 하나만 판정한다" 포함) + 그 지점 질문, `choices` = 요청 선택지(판 순서) + 판의 선택지 뜻을 `description`으로). 데이터는 질문 지시문에 넣지 않는다(지시와 데이터 분리). `store` · `temperature` · `reasoning` · `max_output_tokens`는 Decisions에 없는 매개변수라 보내지 않는다(보존을 요청으로 끌 수 없어 10.2 R-F15 확인 기록 대상이다). 판정 기록 `promptVersion`은 `prompt-service-v3`이다. 이전 판 `prompt-service-v2`(Responses 출력 형식)는 이력으로 자원에 남는다.
  - 응답 검증(새 사유 코드 없음): 비 2xx는 아래 표와 같다. 2xx에서 JSON이 아니면 `SERVER_ERROR`, 질문 이름이 맞는 답이 없으면 `EMPTY_OUTPUT`(이름 없는 거절만 있으면 `REFUSED`), 같은 이름 답이 여럿이면 `SCHEMA_INVALID`, `type: refusal`이면 `REFUSED`, `model`이 없으면 `MODEL_MISSING`, 그 밖에 `type`이 `choice`가 아님 · `choice`가 보낸 선택지 밖 · `probabilities`가 보낸 선택지를 모두 덮지 않음(밖 · 중복 · 숫자 아님 · 0 ~ 1 밖 포함) · 고른 값의 확률이 가장 높지 않음(같으면 허용)이면 `SCHEMA_INVALID`다.
  - 판정: 확률 내림차순 1 · 2순위(같으면 공급자가 고른 값, 그다음 판 순서)를 검증기(하한 `min-confidence` → `LOW_CONFIDENCE`, 최소 차이 `min-margin` → `LOW_MARGIN`, 같으면 확정)에 넘긴다. 응답의 `confidence`는 쓰지 않는다(판정 규칙은 선택지 확률 기준이고 confidence는 정의가 다른 값). 판독 불가 입력은 공급자 없이 `UNREADABLE_INPUT`, 실패는 `TIMEOUT` · `SCHEMA_INVALID` · `PROVIDER_ERROR` 보류다. Decisions 장애 · 지연 때 대신 부를 다른 끝점은 없고 그 판정은 보류로 끝난다.
  - 예열(JIRA-282): 세션 첫 판정이 처음 도는 코드 · 새 연결 비용으로 시한을 넘지 않게, realtime은 앱 기동 뒤(ApplicationReady)와 세션 시작 신호를 기기에 보낸 직후 판정 경로를 예열한다. 합성 입력으로 정리 · 요청 스키마 검증 · 본문 조립을 한 번 돌리고(보내지 않음) 같은 호스트 `GET {base-url}/models`를 **키 없이** 한 번 보낸다(판정 · 과금 · 데이터 · 아동 정보 없음, 응답 상태는 보지 않음). 주소 규칙(10.3)을 어기면 보내지 않고, 연결 시한은 `mujoong.timing.judgment.live-budget`이며 기다리지 않는다. 판정 시도가 아니라 `LUNA_CALL`을 남기지 않고, 실패해도 판정 흐름 · 시한은 그대로다.
- **보내기 전 정리:** 모든 깊이에서 10.2 금지 키(+ core 결과 게이트웨이의 `audioRefs` · `audioRefsJson` · `interests` 등)를 지우고, 호출자가 준 별칭 표로 아이 이름을 바꾸며, 이메일 · 휴대전화 모양 값을 '연락처'로 가린다. 남으면 · 요청 스키마(`ai-judgment-request`)에 맞지 않으면 부르지 않고 `FORBIDDEN_INPUT`이다. UUID → '참조N'과 복원은 지금 `ResultTextGateway`가 맡는다.
  - 금지 키는 대소문자와 `_` · `-`를 가리지 않고 비교한다(`child_name` · `Child-Id` · `api_key` · `guardian-id`도 지운다). 정리 뒤 안전망은 이 비교를 다시 쓰지 않는 독립 검사로, 글자 · 숫자 밖 문자를 모두 뺀 꼴(예: `child.name`)까지 보고 남으면 `FORBIDDEN_INPUT`이다.
  - 별칭은 긴 이름부터(길이가 같으면 사전순) 한 번만 훑으며 바꾼다. 이름끼리 포함 관계(예: '민수'와 '수')여도 결과가 별칭 표 순서에 기대지 않고, 바꿔 넣은 별칭을 짧은 이름이 다시 바꾸지 않는다. 빈 이름은 무시한다.
  - realtime 호출부는 보내기 전 준비(정리 · 요청 스키마 검증 · 본문 조립) 중 예기치 못한 런타임 오류도 호출 스레드로 던지지 않고 `APP_ERROR`(예외 종류 이름만) 한 줄과 `FORBIDDEN_INPUT`(시도 0) 실패 완료로 바꾼다.
- **스키마:** 빌드가 `contracts/schemas/ai-*.schema.json`(+ 그 파일이 가리키는 `common.schema.json`)을 실행 자원 `luna/schemas/`로 복사한다. core-api는 요청 `text.format`에 공급자가 받는 키워드(`type` · `properties` · `required` · `additionalProperties` · `items` · `enum` · `const` · `anyOf` · `$defs` · `$ref` · `description`)만 남긴 사본을 싣고(다른 파일 `$ref`는 정의를 끼워 넣음), 모든 객체가 닫히고 모든 속성이 required일 때만 `strict: true`다. core 응답 검증은 언제나 원본 계약 스키마 전체로 한다. realtime은 요청 스키마(`ai-judgment-request`)만 읽는다(출력 스키마 없음, 응답은 위 Decisions 응답 검증). 스키마를 읽지 못하거나 정상 예시가 검증을 통과하지 못하면 기동을 거부한다.
- **시도 · 재시도:** 시도 시한 = min(호출자 마감까지 남은 시간, 목적 시한 키). 0 이하면 부르지 않고 `TIMEOUT`. 재시도는 10.3 분류대로 `REQUEST_BOUNDED`만 일시 실패 기술 재시도 1회 + 스키마 불일치 재요청 1회(합계 ≤ 3)이고, 대기 = max(`mujoong.text-ai.retry.backoff`, 429 `Retry-After`)가 남은 시간보다 짧고, 대기 뒤 남는 시간이 최소 시도 창(새 키 없이 `mujoong.text-ai.retry.backoff` 값을 그대로 씀) 이상일 때만 한다. 401 · 403은 재시도하지 않는다.

| 실패 사유 | 언제 | 일시 | `TextAiProvider` 어댑터(BR8.1) | `ResultTextProvider` 어댑터(BR8.2) |
|---|---|---|---|---|
| `AUTH_FAILED` | 401 · 403 | 아니오 | `UNAVAILABLE` · retryable=false | `PROVIDER_ERROR` |
| `RATE_LIMITED` | 429 | 예 | `UNAVAILABLE` · retryable=true | `PROVIDER_ERROR` |
| `SERVER_ERROR` | 5xx · JSON이 아닌 응답 · 완료가 아닌 상태 | 예 | `UNAVAILABLE` · retryable=true | `PROVIDER_ERROR` |
| `REQUEST_REJECTED` | 그 밖 4xx(스키마 거절 포함) · 3xx · 허용 밖 주소 | 아니오 | `UNAVAILABLE` · retryable=false | `PROVIDER_ERROR` |
| `TRANSPORT_ERROR` | 연결 · 읽기 오류 | 예 | `UNAVAILABLE` · retryable=true | `PROVIDER_ERROR` |
| `TIMEOUT` | 시도 시한 초과 · 남은 시간 없음 | 아니오 | `TIMEOUT` · retryable=true | `TIMEOUT` |
| `EMPTY_OUTPUT` | 출력 텍스트가 빔(Decisions: 질문 이름이 맞는 답 없음) | 예 | `UNAVAILABLE` · retryable=true | `SCHEMA_INVALID` |
| `LENGTH_EXCEEDED` | `status: incomplete` · 본문 바이트 상한 초과 | 아니오 | `INVALID_OUTPUT` | `SCHEMA_INVALID` |
| `REFUSED` | 거절(refusal) 출력(Decisions: `type: refusal` 답) | 아니오 | `INVALID_OUTPUT` | `SCHEMA_INVALID` |
| `SCHEMA_INVALID` | 원본 계약 스키마 위반 · JSON 객체가 아님(Decisions: 위 응답 검증 위반) | 아니오 | `INVALID_OUTPUT` | `SCHEMA_INVALID` |
| `FORBIDDEN_INPUT` | 정리 뒤 금지 키 잔존 · 요청 스키마 위반 · (realtime) 보내기 전 준비 중 예기치 못한 오류 | 아니오 | `UNAVAILABLE` · retryable=false | `INTERNAL` |
| `MODEL_MISSING` | 응답 `model` 없음 | 아니오 | `INVALID_OUTPUT` | `PROVIDER_ERROR` |

- **기존 인터페이스 어댑터:** core `TextAiProvider`(상황 생성 지시 `prompts/scenario/generation.v1.txt`, 판 `scenario-generation.v1`) · `ResultTextProvider`(`prompts/result/{task}.{판}.txt`)의 Luna 구현은 출력 스키마 파일 없이 `json_object`로 **언제나 시도 1회**만 부른다. 재시도는 바깥 `TextAiGateway`(기술 재시도)와 결과 작업이 이미 한다. 길이 · 판 정규식 · 금지 표현(`TextAiGateway`), `ResultAiSchema` · 참조 복원 · 인용 대조(`ResultTextGateway`), 1차 사전 · `classify`(`OutputChecker`)는 그대로 돈다. 새 스키마로 바꾸는 일은 U10 · U14가 한다.
- **실호출 시험:** 사람이 env에 키를 넣고 돌리는 `./gradlew lunaLiveTest`(태그 `luna-live`)만 실공급자를 부른다. 기본 `test` · `check` · CI · `verify.sh`는 루프백 대역만 쓴다. 키가 없으면 `SKIPPED_NO_KEY`로 끝나고, 결과는 '실제 API · 합성 자료' 증거로 따로 보고한다 [U9 편차 (U9-c)].

### 10.11 U10 상황 생성 경로

실제 Luna 구성의 상황 생성은 `CONTRACT_SINGLE_CALL`로 생성 `scenario-generation.v2`와 별도 검토 `scenario-review.v1`을 각각 스키마 검증한다. 안쪽 재시도 없이 바깥 요청이 `mujoong.timing.practice.scenario-request-timeout`(현재 30초)과 최대6호출을 공유한다. 내용 수정1회·기술 재시도1회·BASE-only 검토도 그 안에서 수행한다. 직전 거절 사유가 장면 보완 가능 사유(`INCOMPLETE_SCENE`·`ANSWER_INCLUDED`·`EARLY_CUE`·`GOAL_MISMATCH`)로만 구성되면 남은 예산 안에서 마지막 생성·별도 검토를 한 번 더 허용한다. 승인이나 서버 검사를 건너뛰지 않으며 반복·금지 내용 등의 거절을 이 보완 사유로 바꾸지 않는다. 기존 직접 호출 어댑터 메서드의 json_object 계약과 다른 목적의 호출 예산은 그대로다. 여는 말(`openingLine`) 필드와 옛 돌아보기 필드(`reflectionPrompt` · `reflectionSummary`) 처리는 10.17을 따른다 [FT-1023-g].

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
| `mujoong.guardrail.jev.send-child-name` | `${JEV_SEND_CHILD_NAME:false}` | realtime | `true`면 제브 요청 후보 문장 · 맥락의 아이 이름을 '아이'로 바꾸지 않고 원문으로 보낸다(10.2 예외, JIRA-266). 비밀이 아니고 시간 값이 아니다. **R-F15 확인 기록(제브의 아동 이름 전달 허용 범위) 전에는 켜지 않는다.** 다른 외부 AI 경로에는 영향이 없다 [편차 FT-1023-b] |
| `mujoong.guardrail.jev.pass-threshold` | `${JEV_PASS_THRESHOLD:0.5}` | realtime | 통과 문턱, 허용 `0 < x <= 1`. `PASS`가 1순위이고 `PASS` 확률이 이 값 **이상**일 때만 자동 통과한다(10.13). 비밀이 아니다. **임시 시험값이다 — AI 성능 시험으로 확정한다.** `REAL`에서 범위 밖이거나 숫자가 아니면 기동을 거부한다 [U13 편차 (U13-c)] |

기동 점검(모드 × 프로필 × 키): 공개 프로필(demo · prod)은 `REAL` + `JEV_API_KEY` + 허용 호스트 + 고정 모델이 아니면 기동을 거부하고(`STUB` · `OFF`도 거부), 그 밖(dev · test · 프로필 없음)은 `REAL` · `STUB` · `OFF`를 모두 허용하되 `REAL`이면 키가 있어야 한다. `STUB`은 제브 단계를 모두 지나며 1차 사전 통과를 대역 표시가 붙은 `ALLOW`(`reasonCode` `STUB_ALLOW`)로 돌려주고 `OFF`는 제브 단계를 건너뛴다. 둘로 시작하면 기동 로그에 한 줄이 남는다. 대역의 통과는 실제 검사 성공이 아니다(R-M11).

현재 정책 판은 `guardrail/policy-v3.json`의 `jev-guardrail-v3`이다. v3는 GPT-Live 출력 조각의 욕설·비속어·직접적인 모욕 표현만 검사한다. 기존 진단·평가·대화 맥락 분류는 Jev 검사에서 제외하며, 1차 출력 사전과 서비스 정책은 그대로 적용한다. `policy-v1.json`과 `policy-v2.json`은 이력으로 보관한다. 스키마 파일(`guardrail-request` · `guardrail-result`)의 예시가 쓰던 `guardrail-v1`은 이 판 이름 규칙으로 정정했다 [U13 편차 (U13-b)] [U13 편차 (U13-c)].

### 10.13 U13 제브 공급자 선 형식 · 판정 규칙

어댑터(`JevGuardrailClient`)가 제브 공식 문서([quickstart](https://docs.typesafe.ai/introduction/quickstart) · [API](https://docs.typesafe.ai/api))에 맞춰 부르는 형식과, 응답을 내부 결과(`guardrail-result`)로 바꾸는 규칙이다. 내부 형식 스키마(`guardrail-request` · `guardrail-result`)는 그대로이고 공급자 형식은 이 어댑터 안에서만 쓴다 [U13 편차 (U13-c)].

**호출:** `POST {기준 주소}/v1/systemone`(경로는 코드 상수, 기준 주소는 `JEV_BASE_URL`), 헤더 `Authorization: Bearer <키>` · `Content-Type: application/json`. 호출 한 번, 재시도 없음, 시한 `mujoong.timing.guardrail.jev-budget`, 리다이렉트 · 프록시 미사용, 응답 64KiB 상한(10.3 · 10.12).

**요청 본문:** 최상위 키는 `model` · `state` · `questions` 셋뿐이다.

- `model`: 설정의 고정 모델 버전(`mujoong.guardrail.jev.model`). 모델 이름을 코드에 두지 않는다.
- `state`: `{ "candidate": <후보 문장>, "context": [ { "speaker": "CHILD|PARTNER|COACH|MUJOONG", "text": <문장> }, … ] }`. 후보 문장과 익명 최근 맥락 3턴 이하만 싣고 식별자 칸이 없다. 내부 요청의 `mode`는 보내지 않는다.
- `questions`: 질문 ID `guardrail` 하나. `{ "type": "choice", "instructions": <정책 판의 한국어 검사 질문>, "criteria": { <라벨>: <설명> … } }`. 현재 라벨은 `PROFANITY`와 `PASS` 두 개다. 문안은 정책 판 `guardrail/policy-v3.json`에서 온다. 과거 결과의 7개 범주는 이력 호환을 위해 결과 스키마에 남긴다. 후보 문장 · 맥락 안의 지시문은 `state` 데이터일 뿐 `questions`를 바꾸지 못한다.

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

### 10.15 자유대화 서비스 정책 적용

자유대화는 앱에 포함한 `service-0.1-app.3` 정책을 세션에 고정한다. 원본/적용본 해시와 문장 편차는 `service-guardrail-policy-deviations.md`에 기록한다. 이름·나이·관심사만 기존 상한·이스케이프로 데이터 구역에 둔다. 초기 안내/현재 참여 수락은 서버가 확인하며, FIRST/CONTINUE·교정·재시도 지시는 강제 관심사 질문·고정 맞장구·휴식 뒤 자동 재개를 추가하지 않는다. 연습의 기존 역할과 검증된 발화 입구는 유지한다.

녹음 안내는 GPT-Live 자신이 발화한 이력으로 추정하지 않는다. 현재 연결의 검증 안내 `COMPLETED`와 참여 수락 뒤 기존 FIRST 지시 하나로 실제 안내 전달 사실을 알리고, 시작 인사·자기소개·대본·참여 질문을 반복하지 않고 아이의 마지막 말에 이어 답하게 한다(RG1). 명확한 재시작 인사/자기소개와 등록 시작 대본 반복은 첫 현재 응답이 실제 기기 프레임으로 송신되고 현재 세대의 재생 완료를 확인하기 전까지 음성·전사 후보 전체를 폐기한다. 제브 ALLOW나 승인 원문 생략도 이 검사를 우회하지 않는다. 별도 AI 판정·자동 재생성·교정 호출은 추가하지 않는다. 아이의 명시적 이름/정체 질문에 대한 답, 인사 인용, 이후 대화와 연습은 구분한다. 이 유한한 표현/상태 검사는 임의의 모든 의미적 바꿔 말하기 탐지나 실모델의 항상 준수를 보장하지 않는다.

참여 전 입력 전사 quiet는 과거 발화를 새 현재 발화로 바꾸는 근거가 아니다. 실제 null `itemId` 입력은 현재 안내 완료 때의 세션 오디오 시간선과 관측 입력 끝 watermark 이후에 시작한 유효 `start_ms/end_ms`만 현재 참여 후보로 삼는다. 시간/식별 근거가 둘 다 없는 전사는 참여 수락으로 추정하지 않는다. 내부 합성 완료 사건과 legacy item ID 대역은 실제 공급자 wire 증거와 구분한다.

J1/J6의 고정 질문·선택지는 `judgment-service-v1`, 공통 지시문은 `prompt-service-v3`(Decisions 질문 지시문, 10.10 · 편차 FT-1023-d)을 사용한다. 판정 요청 스키마·4초 마감·호출 횟수는 그대로다. JEV의 `policyVersion`은 기존 JEV 판이며 서비스 정책판으로 바꾸지 않는다. 내부 검사표만 session/mode/generation/목적·후보 본문 해시·서비스/JEV판을 연결한다. ALLOW 뒤에도 actor의 현재 출력 권한을 다시 확인한다. 확률/동률·800ms·실패/REVIEW 차단은 유지한다. 현재 Jev 범주와 출력 단위는 10.15절의 v3 정책을 따른다.

안내·참여 질문·종료 확인·인사는 현재 승인 본문/음성 판과 해시·PCM16LE 24kHz 모노 형식·길이·SPOKEN_VERIFIED 검수판이 일치해야 전달 후보가 된다. 실제 서비스 말소리는 후속 제작/청취/등록 전까지 미준비다. 운영 notice-assets manifest는 비어 있으며 톤·무음이나 합성 시험 자원은 실제 안내로 승격하지 않는다. 새 공급자 API 필드·SDK·자동 AI 호출은 추가하지 않는다.


### 10.15 Jev 출력 단위별 검사 (v3)

시간 정보가 있는 `session.output_transcript.delta` 하나를 출력 단위로 삼는다. 음성에는 개별 시간 정보가 없으므로 세션 PCM 누적 바이트 시계(24kHz PCM16 모노, 48바이트/ms)로 해당 전사의 `end_ms`까지 정확히 분리한다. 각 단위가 완성되는 즉시 1차 게이트 → 비동기 Jev → 송신 직전 권한 확인 → 기기 송신을 수행한다. 전체 발화 종료나 추가 무음 대기를 요구하지 않는다. 하나의 음성 프레임이 전사 경계를 넘으면 바이트 단위로 나누며, 전사가 없는 꼬리는 송신하지 않는다.

검사는 출력 순서대로 진행하며, 앞 단위의 판정 동안 최대 128개 단위를 보관한다(추가 대기 음성 상한은 기존 구간 상한 960,000바이트). Jev에는 현재 조각과 직전에 통과한 출력의 마지막 64자만 전달하여 조각 경계에 걸친 욕설을 판단한다. 아동의 대화 맥락은 이 경로에 보내지 않는다. BLOCK·REVIEW·검사 실패·정렬 실패·대기열 초과는 남은 응답을 무효화한다. 기존의 한 차례 재지시와 끼어들기·종료·연결 변경에 대한 권한 검사는 유지한다.

전사가 음성보다 앞서 도착하면 `mujoong.timing.judgment.response-ready` 한도 안에서 대응 음성을 기다린다. 시간 정보가 없거나 음성부터 시작해 전사 대응을 확인할 수 없는 제공자는 기존 무음 구간 검사 경로를 사용한다. 생성형 연습 발화에도 단위별 검사를 적용하되 연습 슬롯의 완료 사실은 모든 단위의 검사·재생과 발화 종료가 확인된 뒤 한 번만 보낸다. 등록 원문 재생 경로는 유지한다.

단위 테스트의 PASS/BLOCK은 합성 입력과 테스트 대역의 결과이며 실제 Jev 분류 정확도·운영 지연 측정 결과가 아니다.

문장부호 · 공백만 든 전사 조각(예: `,`)은 따로 검사하지 않고 다음 조각과 합친다(합칠 다음 조각이 없는 말끝 조각은 아래 FT-1023-e). 검사가 도는 동안 쌓인 뒤따르는 단위는 후보 문장 3,000자 상한 안에서 한꺼번에 묶어 Jev에 한 번 보낸다. 검사 중이 아니면 첫 단위는 기다리지 않고 바로 보낸다. 자유대화 응답은 **전사가 음성보다 먼저 오는 성질을 이용해**, 전사 조각이 도착해 새 말의 음성이 시작되면 그 조각이 가리키는 음성이 다 오길 기다리지 않고 바로 검사한다. 사전 검사만 통과한 전사로 요청하고(음성은 아직 오는 중이다), 유효한 `ALLOW`(또는 원문 일치)가 나면 그 단위의 전사가 끝나는 PCM 위치까지 **도착한 음성을 도착하는 대로** 기기에 보낸다. `ALLOW` 전에는 어떤 음성도 나가지 않고, 통과한 단위의 끝 위치를 넘는 음성은 나가지 않는다. 응답이 `ALLOW`보다 먼저 끝나도 검사 중인 단위의 음성은 마지막 단위가 정리될 때까지 보관한다. 연습 흐름이 출력을 쥔 응답은 기존처럼 음성이 다 온 단위만 검사한다. 묶인 단위는 한 번의 `ALLOW`로 순서대로 재생하고, `BLOCK` · 무효 · 오류는 묶음 전체를 재생 없이 닫는다.

**구두점뿐인 단위와 재생이 시작된 연습 발화의 꼬리 (2026-10-23, [편차 FT-1023-e]).** 단위별 경로(시간 정보로 음성과 맞춘 단위 — 전사 먼저 방출 · 연습의 음성이 다 온 단위)에서 전사가 사전 정규화(NFKC · 공백 · 제로폭 · 구두점 제거) 뒤 비지만 원문에 문장부호가 있는 단위(GPT-Live 말끝의 `?` 등, "구두점뿐인 단위")는 빈 전사(`GATE_EMPTY` / `EMPTY_TRANSCRIPT`)로 버리지 않고 그 음성을 재생한다(말끝 억양 보존). 사전 · 제브가 볼 글이 없어 제브를 부르지 않고, 원문 일치 생략과 같은 모양의 검사 기록(`guardrail_event` `APPROVED_VERBATIM_SKIPPED` · `ALLOW` · 사유 `PUNCTUATION_ONLY` · 지연 0)과 앱 로그 사유 코드 `GuardrailSkippedPunctuationOnly`(INFO, 원문 없음)를 남긴다. 앞 단위가 제브 검사 중이거나 대기 중이면 음성 순서를 지키려고 대기열 뒤에 두고, 차례가 오면 제브 없이 낸다(앞 대기 단위와 한 검사로 묶이면 그 검사 글에 붙는다). 음성 없음 · 홀수 PCM 길이 · 불완전 단위 검사는 그대로 거친다. 원문이 null · 빈 문자열 · 공백 · 제로폭 · 서식 문자뿐인 단위와 무음으로 봉인한 전체 구간(단위별 경로 밖)의 빈 전사는 지금처럼 `EMPTY_TRANSCRIPT`로 버린다(fail-closed). 전사 먼저 방출 경로는 다음 조각과 합치려고 붙잡아 둔 말끝 구두점 조각을 응답이 조용해지면(`segment-quiet`) 그대로 단위로 낸다 — 남은 전사 시한이 지나 출력 세대를 올리며 말끝 음성을 버리던 길이 없어진다. 등록 원문 읽기(연습 승인 발화)는 단위별 경로를 타지 않으므로 원문 일치 검사(`PracticeSpeech.matches`)에는 영향이 없다.

생성형 연습 발화가 단위별로 나가다 **이미 단위 하나라도 기기 송신에 넘긴 뒤** 같은 발화의 단위가 `GATE_EMPTY`로 버려지면, 같은 지시를 다시 요청하지 않고(아이가 같은 말을 두 번 듣지 않게) 그 응답의 나머지를 받지 않은 채 나간 부분까지 '재생됨'으로 닫는다 — 보낸 음성이 모두 끝나면 확장에 `COMPLETED` 한 번(사유 코드 `PracticeTailUnitDroppedAfterPlayback`, INFO). 이미 보낸 음성은 끊지 않는다(출력 세대 · 출력 권한을 바꾸지 않는다). 남은 전사가 음성과 맞춰지지 못한 채 시한이 지나도 같은 길로 닫는다(`PracticeTailTextUnalignedAfterPlayback`, INFO). 금지 표현 · 제브 막힘 · 송신 재확인 폐기는 지금처럼 실패이고, 단위가 하나도 나가지 않은 발화의 실패는 지금처럼 확장이 재요청 1회 뒤 실패를 정한다. 단위별로 나가는 생성형 발화의 기기 `STARTED`도 확장에 넘긴다(전에는 삼켜 확장이 '재생 중'을 몰랐다).

보호자 화면의 AI 말풍선은 응답 하나가 행 하나다. 단위별로 통과한 전사는 모아 두었다가 응답이 끝났을 때(남은 검사 · 대기 단위가 없을 때), 아이 입력 전사가 시작될 때, 출력 세대가 올라 응답을 버릴 때 한 번에 `FINAL`로 낸다. 응답 도중에는 확정 전 칸(`PARTIAL`, 텍스트 비움)만 보인다.

### 10.16 입력 중계: 안내 녹음 · 말하는 동안

- **참여 안내 녹음(`INTRO_FREE_TALK` · `INTRO_PRACTICE` · `RE_PROMPT` · `DECLINE_ALTERNATIVE`) 재생 중**에는 아이 소리를 입력으로 받지 않는다. 서버는 기기 프레임을 같은 길이의 무음으로 바꿔 공급자에 보내(공급자 세션 시간선은 이어 간다) 끝 표식(`COMPLETED`) · 실패 · 참여 수락 · 거절에서 푼다. 기기는 이 녹음이 재생되는 동안 마이크 음성을 끼어들기로 보지 않아 녹음을 끊지 않는다.
- **GPT-Live가 말하는 동안에도 아이 소리는 실시간으로 그대로 보낸다.** 말하는 동안 소리를 모았다가 몰아 보내는 방식은 공급자 입력 속도 한도(`input_audio_rate_limit_exceeded`)에 걸려 아이 말이 버려져서 쓰지 않는다(2026-10-22 실호출 확인).
- 참여 대답이 인식되지 않을 때 원인을 가릴 수 있도록 참여 전 전사 조각을 버릴 때 창마다 사유 한 번을 로그 사유 코드 `ParticipationInputDroppedNOT_OPEN` · `ParticipationInputDroppedBEFORE_WINDOW` · `ParticipationInputDroppedBEFORE_WATERMARK` · `ParticipationInputDroppedLEGACY_ITEM`(INFO)로만 남긴다. 전사 원문은 남기지 않는다.
- **시작 의사 판정(J1)은 제브로 한다(2026-10-22부터 자유대화에서는 J1을 열지 않는다 — 아래 항목).** 제브가 실제 모드(`REAL`)이면 J1은 제브 `systemone`의 `choice` 질문으로 판정한다(질문 ID `judgment`, 질문 · 라벨 설명은 판정 선택지 판의 J1 질문과 선택지 뜻, 확률 1 · 2순위는 기존 판정 검증기의 하한 · 최소 차이 규칙을 그대로 받는다). 제브를 쓸 수 없으면 Luna로 간다. 자유대화의 시작 의사는 **거절인가만** 본다: 사전 분류가 명확한 거절이거나 판정이 `DECLINE`으로 확정되면 대화를 끝내고(거절 흐름), 그 밖(참여 · 불명확 · 휴식 · 판독 불가 · 보류 · 공급자 장애 · 판정을 열 수 없음)은 모두 대화를 시작한다. 무응답(창 만기)은 기존처럼 다시 안내한다. J2 ~ J6은 아직 Luna이며, 같은 구현으로 지점만 더하면 제브로 옮길 수 있다(`JudgmentConfiguration.JEV_POINTS`).
- **참여 확인 중 공급자가 스스로 시작한 응답은 버리지 않고 보관한다.** GPT-Live는 아이 말이 끝나면 서버 지시 없이 응답을 시작한다. 안내가 끝난 뒤 이 응답의 전사 · 음성 · 무음 조각을 상한(음성 20초 분량 · 전사 8,192자) 안에서 보관했다가, 참여가 수락되면 새 세대의 첫 응답으로 다시 흘려 넣는다(이때 첫 응답 지시는 따로 보내지 않는다). 보관한 응답이 없거나 상한을 넘었으면 기존처럼 첫 응답 지시를 보낸다. 거절 · 불명확이면 보관한 응답은 쓰지 않는다. 다시 흘려 넣은 응답도 제브 검사 · 출력 사전 · 송신 직전 재확인을 똑같이 거친다. 로그 사유 코드는 `JoinHeldOutputReplayed` · `JoinHeldOutputNone` · `JoinHeldOutputOverflow`(INFO)다.
- **보관한 응답이 없으면 스스로 시작을 기다린다(2026-10-22) · 대기 직후 시작한 답도 받는다(2026-10-23, JIRA-261).** 보관한 응답이 비어 있으면 지시 없이 세대를 올려 끊김이 확인된 것으로 두고 `mujoong.timing.provider.join-self-start-wait`만큼 기다린다(`JoinSelfStartWait`). 그사이 조각이 오면 그것이 첫 응답이다(`JoinSelfStartSeen`, 지시 없음). 조각이 하나도 없으면 첫 응답 지시를 보낸다(`JoinSelfStartMissed`). 이 지시 세대는 **첫 조각을 확인 사건 · 전사 시간선과 관계없이 새 응답의 시작으로 받는다**(`JoinSelfStartLateAccepted`, INFO). 모델이 대기 직후 스스로 시작한 답은 전사 시간선이 지시 확인(`end_ms`)보다 앞이라 A4 규칙으로는 낡은 말로 모두 버려져 약 18초 동안 아무 말도 들리지 않았기 때문이다(2026-10-23 12:46 세션). 끊김 확인 뒤 지시를 보낼 때까지 조각이 없었으므로 그 뒤 처음 오는 조각은 앞 응답의 꼬리가 아니다. 이 예외는 끊김이 확인된 단계(조각 없음)에서 보낸 그 지시 세대의 첫 조각에만 쓰고, 첫 조각이 출력 보류 중에 오거나 다음 세대로 넘어가면 사라진다. 그 밖의 모든 지시는 A4 규칙(확인 사건 · 전사 시간선 · 여유)으로 낡은 꼬리를 버린다. 모델이 스스로 시작한 답 뒤에 지시에 대한 답을 또 하면 두 답이 이어서 들릴 수 있다(실호출에서는 관측되지 않음).
- **응답 정체 안전망(2026-10-23, JIRA-270).** 자유대화 ACTIVE에서 아이의 빈 아닌 확정 전사 뒤, 또는 전사 끝이 "생각해 볼게" · "정리해서 말해 볼게" · "잠깐만" 같은 미루는 말(사전 규칙, 한글 음절만 비교)로 끝난 AI 응답이 조용함으로 봉인된 뒤 `mujoong.timing.provider.response-stall-wait` 동안 받는 출력 조각도 아이 입력 전사 조각도 없으면, 이어 말하기 지시(`ResponseStart.STALL_RESUME`: 미루지 말고 아이의 마지막 말에 바로 짧게 답하고, 모르면 모른다고 말하고, 새 질문을 붙이지 않는다)를 **아이 턴마다 한 번만** 보낸다(`ResponseStallNudge`, 보낼 수 없었으면 `ResponseStallNudgeNotSent`, 미루는 말로 끝난 응답을 보면 `ResponseStallDeferralEnd`, 모두 INFO · 원문 없음). 아이가 "잠깐 생각할게"처럼 기다려 달라고 한 말 뒤에는 걸지 않는다(서비스 정책). 기기 재생 · 승인 문구 재생 · 제브 검사가 남았으면 같은 시간만큼 다시 기다리고, 아이가 말하는 중 · 출력 보류(참여 확인 · 휴식 · 연습 보류) · 판정 턴 · 다른 지시의 새 말 대기 · 참여 직후 스스로 시작 대기 · 열린 응답 · 연습이 출력을 쥔 때 · ACTIVE 밖에서는 보내지 않는다. 보낸 지시는 다른 서버 발화와 같이 세대를 올리고 `response-ready` 시한을 건다. 공백 시계(`silence-warning` · `silence-end`, 아이 침묵 누적)와 연습 무응답 재안내(`conversation.no-response-wait`)는 아이가 말하지 않는 것을 재고, 이 안전망은 모델이 답하지 않는 것을 잰다. 공백 시계는 이 지시로 리셋되지 않는다.
- **전사 표지 정리(2026-10-23, JIRA-262).** 전사 행을 저장하기 전에(`TranscriptWriter`) AI 행은 영문 대괄호 비언어 표지(`[hum` · `[hum]` 등, 닫는 괄호 없어도)를, 아이 행은 같은 표지 · 짝 없는 `]` · 앞머리 문장부호 잡음(`.` · `..` 등)을 지운다. 대괄호가 없는 AI 글은 바꾸지 않고, 한글이 든 대괄호 쌍은 사람 말일 수 있어 둔다. 아이의 울음 같은 표지도 지운다(표지 글자가 아이 말로 인용 · 판정되지 않게 한다). 표지만 있던 아이 말은 빈 전사(검토 표시)로 남는다. 아이 확정 전사는 저장 전에 같은 규칙으로 정리한 글로 판정 기억 · 제브 대화 문맥 · 종료 판정 · 연습 확장점에 넘긴다(참여 확인 대답은 지금 동작을 지키려고 정리하지 않는다). 제브 출력 후보(AI 전사 단위)에는 표지가 그대로 들어간다.
- **무음 묶음 진단(2026-10-23).** 기기가 "말하는 중"인데 소리가 나지 않는 경우를 가리려고, 기기로 보내는 AI 음성 묶음마다 디지털 무음(표본 값 0) 비율을 센다. 전부 무음이면 `OutputBundleAllSilent`, 90% 이상이면 `OutputBundleMostlySilent`이고, 응답의 첫 묶음이면 이름에 `First`가 붙는다(`OutputBundleFirstAllSilent` · `OutputBundleFirstMostlySilent`). 묶음의 공급자 음성이 아이의 마지막 입력 전사 조각보다 먼저 끝났으면(아이가 말하는 중에 앞 응답이 늦게 나감) `OutputBundleAfterChildInput`을 남긴다. 모두 INFO 사유 코드이고 원문 · 음성은 남기지 않는다(R-F14). 동작은 바꾸지 않는다.
- **판정 AI J1(참여 대답) · J6(종료 의도)는 자유대화에서 열지 않는다(2026-10-22 결정).** 참여 대답은 거절이 아니면 불명확해도 바로 참여를 수락하고, 종료 의도는 규칙 판정기의 STOP(등록 종료 표현 — 기본 "무중아 그만할래" + 보호자 추가 표현, 물음꼴 포함)만으로 끝낸다(2026-10-23부터 판정기 자체의 명확한 종료 표현 · 즉시 멈춤으로는 끝내지 않는다, `deviations.md` FT-1023-a). 불명확한 종료 신호로 확인 질문이나 판정을 여는 단계는 없다. 그래서 판정 중 출력 보류로 응답이 사라지던 경로도 없다. 연습 확장점이 요청하는 판정(J2 ~ J5)은 그대로다. **연습의 참여 대답도 같은 분류 규칙이다(2026-10-23, [편차 FT-1023-f]):** 주제 거절 표현은 불명확으로 보고, 거절이면 대안 제시(`DECLINE_ALTERNATIVE`) 없이 바로 DECLINED, 거절이 아니면(불명확 포함) 재안내 없이 바로 ACTIVE다. 수락한 참여 대답은 연습도 보호자 화면에 아이 행 하나로 남는다(AI 참여 안내 → 수락한 아이 말 → 첫 연습 발화, 기록만 하고 연습 확장점의 아이 발화로 넘기지 않는다, 동의 철회 · 빈 대답이면 남기지 않는다). 무응답(창 만기)의 재안내 · 종료 결정표는 두 활동 모두 그대로다.
- **버린 출력 진단(2026-10-22).** 출력 세대를 올릴 때 그 세대에서 보류로 버린 조각이 있었으면 `OutputDiscardedHeld`, 지시 전 낡은 말로 버린 조각이 있었으면 `OutputDiscardedStale`(INFO)을 한 번씩 남긴다. `OutputNewSpeechNotObserved` 직전에는 마지막 지시 뒤 출력 조각이 하나도 없었으면 `OutputNotObservedProviderSilent`, 있었으면 `OutputNotObservedOnlyStaleChunks`(WARN)를 남긴다. 원문은 남기지 않는다(R-F14).
- **공급자 오류 코드.** 입력 쪽 오류(`input_`으로 시작하는 코드)는 지금 나오는 AI 응답을 버리지 않는다. 공급자 `error` 사건마다 오류 코드를 사유 코드 `ProviderErrorCode_<코드>`(WARN)로 남긴다. 코드가 소문자 · 숫자 · 밑줄로 된 2 ~ 61자 식별자일 때만 싣고, 그 밖은 `ProviderErrorCode_unknown`이다(자유 문자열 · 메시지 · 매개변수는 남기지 않는다, R-F14).
- **안내 직후 첫 응답의 인사 차단 완화.** 아이의 마지막 말에 인사(안녕 · 반가워 · 하이 · 헬로)가 있었으면 그 인사에 답하는 인사말은 시작 인사 반복으로 보지 않는다. 자기소개(`나는 AI` · `나는 무중`)와 안내 · 참여 질문 문장 반복은 계속 막는다.


### 10.17 연습 장면 시작 · 오류에도 대화 유지 [FT-1023-g]

**대본 필드(상황 생성 출력 `ai-scenario-output.schema.json`).** 생성 · 검토 지시문 판(`scenario-generation.v2` · `scenario-review.v1`)은 그대로 두고 내용만 고쳤다. 2026-10-24부터는 10.18의 `scenario-generation.v3` · `scenario-review.v2`다.

| 필드 | 쓰는 때 | 규칙 |
|---|---|---|
| `openingLine` | 모든 장면(선택) | 장면 안내 뒤 상대가 먼저 거는 여는 말, 1 ~ 2문장(200자 이하). 화자는 상대. 기회 문장과 같아도 된다(10.18, 이전의 `OPENING_REPEATS_OPPORTUNITY` 거절은 없앴다), 정답 · 아이 행동 · 목표 표현은 검토(`ANSWER_INCLUDED` · `EARLY_CUE`)와 서버 검사가 막는다 |
| `spokenIntroduction` | 기존 | 아이의 처지 · 필요(무엇을 하는 중이고 무엇이 필요하거나 불편한지)와 구체적인 상대를 담는다. 빠지면 검토가 `INCOMPLETE_SCENE`으로 거절 |

생성 직후 여는 말이 위 서버 검사를 못 맞추면 대본 전체를 버리지 않고 그 필드만 빼고(진단 사유 `ScenarioSceneStartDropped`) 검토로 보낸다. 내용을 빼기만 하고 더하지 않으므로 추가 호출은 없다.

이전 대화 돌아보기는 하지 않는다(2026-10-24 결정: 초기 안내가 느려 원래 대화 요약도 없앴다). 상황 생성 입력에 아이 발화 인용(`reflectionContext`)을 넣지 않고, 생성 출력에는 돌아보기 질문(`reflectionPrompt`)과 원래 대화 요약(`reflectionSummary`) 필드가 없다. 저장된 옛 대본에 이 필드가 있으면 core · realtime 모두 모르는 필드로 무시한다. `openingLine`이 없으면 장면 안내 뒤 바로 기회 문장이다.

**연습 흐름(realtime `PracticeConversation`).** 목표 안내 → 장면 도입 → 여는 말(`openingLine` 승인 낭독, 상대 역할 · 낭독 신호 `PEER`) → 아이 대답 한 차례 또는 `mujoong.timing.conversation.no-response-wait` 만기 → 기회 문장 → 판정(기존 그대로). 여는 말에 대한 아이 말은 판정하지 않고 무응답 재안내 횟수 · 장면 결과에 넣지 않는다(전사는 그대로 남는다). 장면 행의 시작은 장면 도입 시작 그대로다. (2026-10-24: 여는 말 뒤 기회 문장을 강제로 읽는 단계는 10.18로 없앴다.)

**오류에도 대화 유지.** 발화 · 판정 · 전사 오류로 장면이나 연습을 닫지 않는다.

- 끝내 못 들려준 발화(생성형 재요청 1회 뒤 실패, 승인 낭독 재요청 · 복구 뒤 실패, 제브 막힘, 슬롯 시한 초과)는 건너뛰고 다음 단계로 간다. 재생 실패면 장면 사실 `PLAYBACK_FAILED`를 남기고, 막힘은 사실 없이 건너뛴다. 막힌 말은 다시 내지 않는다(안전 규칙 그대로).
- 단계별 다음: 목표 안내 → 장면 도입, 장면 도입 → (장면 행을 만들고) 여는 말 또는 기회 문장, 여는 말 → 기회 문장, 입력 단계의 질문 → 그 단계의 아이 말 대기, 역할 전환 알림 → 코치 도움, 코치 도움 → 다시 해 보기, 복귀 알림 → 친구 역할 대화, 친구 마무리 답 → 변형 평가(장면은 정상 원인 유지), 목표 마무리 → 다음 목표 또는 마무리.
- 판독 불가 · 판정 보류 · 장애 · 불명확: 첫 번째는 지금처럼 교정 없이 같은 안내 1회, 두 번째는 닫지 않고 다음 도움 단계로 간다(기회 · 되묻기 → 도움 제안, 도움 제안 → 동의 없는 코치 도움(예시 없음), 다시 해 보기 → 남은 횟수 안에서 코치 도움 · 없으면 집중 한도 정리). 판독 불가 사실 `TRANSCRIPT_FAILED`는 그대로 남긴다.
- 연습을 끝내는 길은 종료 표현 · 쉬기 요청, 보호자 종료, 공백 경고 · 종료, 목표 시간 상한(`goal-active-limit`) · 집중 상한(`focus-max`) · 다시 해 보기 횟수, 모든 목표 완료, 실제 끊김뿐이다. 확장점 신호 `OUTPUT_FAILED`와 실행부 전이 `PRACTICE_OUTPUT_FAILED`는 없앴다. 옛 세션의 `end_detail PRACTICE_OUTPUT_FAILED`는 기록 · 화면이 그대로 읽고 보여 준다.


### 10.18 연습 프롬프트 묶음 v1 적용 [FT-1024-f]

조원이 작성한 연습 프롬프트 묶음 v1(2026-10-24)을 다음처럼 연결했다. 묶음 원문은 리소스로 그대로 두고, 판 이름은 파일 이름과 첫 줄로 구분한다.

| 묶음 파일 | 쓰는 곳 | 연결 방식 |
|---|---|---|
| `practice-system.v1.txt` | realtime `practice/practice-system.v1.txt` | 연습 세션의 `session.start` 지시문에 아이 정보 역할 문장 뒤로 원문 그대로 한 번 싣는다(`PracticeSystemPrompt`). 턴마다 보내는 500자 지시에는 넣지 않는다. 연습 세션은 자유대화의 참여 뒤 첫 질문 문장(`JOIN_FIRST_QUESTION`)을 싣지 않는다 |
| `turn-instructions.v1.txt` | realtime `PracticeInstructions` | 기존 턴 지시를 ROLE_REPLY · ROLE_CLARIFY · INPUT_CONFIRM · OFFER_COACH · COACH_HINT · COACH_EXAMPLE · RESUME_ROLE · OFFER_VARIANT · CLOSE 문구로 바꿨다. 목표명을 읽지 않고, 역할 전환에 목소리 변화를 약속하지 않으며, 발화마다 '음,'을 붙이지 않는다. 500자 상한은 그대로다 |
| `generation.v3.txt` · `review.v2.txt` | core `prompts/scenario/` | 상황 생성 · 검토 판을 `scenario-generation.v3` · `scenario-review.v2`로 올렸다. 생성 · 검토 스키마 키와 사유 코드는 그대로다. 시작 패키지 `practice-scenario.schema.json`의 판 값은 저장된 옛 대본 때문에 옛 판과 새 판을 모두 받는다 |

**바뀐 진행 규칙.**
- 여는 말(`openingLine`)이 장면의 첫 상대 말이다. 여는 말이 있으면 그것만 들려주고 아이의 대답을 기회 문장 대답(J2)으로 바로 판정한다. 기회 문장(`opportunitySentence`)을 이어 강제로 읽지 않는다. 여는 말과 기회 문장은 같은 문장이어도 된다(서버 검사 거절 없음). 여는 말이 없는 옛 대본은 지금처럼 기회 문장으로 시작한다.
- 대화형 연습(`flexibleDialogue`)에서 판정 보류 · 장애는 아이 말을 못 들은 것으로 다루지 않는다. '잘 못 들었어' 확인 없이 상대 역할로 대화를 잇는다(ROLE_REPLY). 확인 질문(INPUT_CONFIRM)은 전사 자체가 불명확할 때만 한 번 쓰고, 두 번째에는 도움 제안으로 넘기지 않고 대화를 잇는다(사실 `TRANSCRIPT_FAILED`는 남긴다).
- 목표 달성(J2 `FULL`) 뒤의 상대 답은 지금 상호작용을 정리하는 답(`peerSettle`)이다: 아이의 제안 · 요청을 받아들여 그 결과로 짧게 잇고 새 주제 · 새 질문을 꺼내지 않는다. 아이가 질문했으면 얼버무리지 않고 장면에 어울리는 구체적인 답을 바로 한다(장면 자료에 없는 것은 상대가 자연스럽게 정해도 된다, 2026-10-27 실키 — "어떤 이야기?"에 답을 피해 아이가 되물었다). 정리 답은 장면당 2번(`SETTLE_REPLY_MAX`)까지이고, 그 뒤 아이 말에는 대화 정리 판정(J3)과 관계없이 마무리 답(`peerCompletion`)으로 장면을 닫는다. 목표 달성 뒤 J3 보류 · 장애는 '정리됨'으로 보고 바로 마무리 답으로 닫는다(2026-10-24 실키 사례: 목표 달성 뒤 J3가 9번 중 6번 보류돼 잡담이 보호자 종료까지 이어졌다). 장면 결과(목표 달성 시점)는 그대로다.
- 연습 무응답 시계(`no-response`)가 아이가 말하는 중(실행부 입력 전사 묶음이 열려 확정 전)에 만료되면 재촉하지 않고 다시 건다. 그 말이 확정되면 판정이 시계를 끈다(2026-10-27 실키 — 말하는 도중 재촉 발화가 나가고 그 말은 18초 뒤 `RESPONSE_NOT_READY`로 보류됐다). 확장점은 이를 읽기 전용 질의 `PracticeOutput.childSpeaking()`으로 안다.

**아직 연결하지 않은 것.** 묶음의 판정 계약 `practice-assessment.v1.txt`(대화 관련성 · 목표 근거 · 지원 · 참여를 따로 돌려주는 JSON)는 지금 진행 중 판정이 쓰는 Decisions API의 선택지 하나 고르기 형식과 맞지 않아, 판정 방식 결정 뒤 따로 적용한다. 출력 검토 `practice-output-review.v1.txt`는 묶음 스스로 제브 대체 배포본이 아니라고 적었다. 낭독 중 아이 질문이 들어올 때의 출력 중지 · 발화 보존, 도움 뒤 `expectsReply` 메타데이터, 기록 단계 구분도 아직 서버 동작이 없다. `dialogue-examples.txt` · `regression-cases.jsonl`은 수동 회귀 검수 자료로 둔다.

## 자유대화 전 보호자 입력 참고

보호자의 입력 원문을 Live 지시문에 넣지 않는다. Core가 고정 키워드와 상황 구분에서 추출한 `conversationGuide.topics`(열거형 1~3개)와 `sourceType`(SEEN, HEARD_FROM_CHILD, UNSPECIFIED)만 자유대화 시작 내부 요청에 선택적으로 포함한다. 이름·연락처·사건 주장·임의 지시문을 담을 문자열 필드는 없다. 아이 기본 정보의 기존 childContext(이름·나이·관심사)는 유지한다.

Realtime은 고정된 중립 질문으로 바꾸며 보호자의 주장을 아이가 말한 사실로 간주하지 않는다. 출처를 아이에게 노출하거나 답을 유도하지 않는다. 지원하지 않는 주제는 일상 질문으로 대체한다. 연습대화에는 이 guide를 전달하지 않는다. 대화 후 구조화는 개인정보를 정리한 원래 보호자 입력과 실제 아이 발화를 각각 사용하고, 출처와 발화 근거를 구분한다. 새 optional 필드 수신을 지원하는 Realtime을 Core보다 먼저 적용한다.


### 개별 출력 폐기 감사 로그 (`OUTPUT_DISCARDED`)

- `realtime/OutputGate`, INFO, 반복 병합 없음. 기존 `ownerHash`(세션 해시)·세션 `traceId`와 `seq`로 해당 AI 전사 행을 찾는다. `seq=0`은 아직 전사 행이 없는 취소다.
- 허용 속성: `seq`, `discardReason`, `discardDetail`, `outputGeneration`, `deviceGeneration`, `currentDeviceGeneration`, `segmentComplete`, `audioBytes`, `transcriptChars`, `expectedChars`, `generatedSpeech`, `practicePhase`, `practiceSub`, `tProviderFirstOut`, `tTranscriptFinal`. 없는 자료는 생략한다.
- `discardReason`은 기존 게이트 사유(무효화는 `VOIDED`), `discardDetail`은 `DiscardDetail`의 고정 코드다. `GATE_EMPTY`를 구간 불완전·빈 전사·음성 없음·홀수 PCM 길이·검사 예외로 구분한다(단위별 경로의 구두점뿐인 단위는 폐기하지 않는다 — 10.15 [편차 FT-1023-e]). 대본 불일치, 금지 표현, 공급자 오류/음성 없음, 아이 입력에 의한 미재생 취소, Jev 차단/시간 초과/오류/무효화 및 전송 실패도 구분한다.
- 판정 우선순위와 폐기·재시도·종료·Jev 정책은 변경하지 않는다. 음성·전사·승인 대본·보호자 메모·예외 메시지·원본 세션 UUID는 로그에 쓰지 않는다. 길이와 시간은 진단용이며 표시 승인이나 안전 검사 근거를 대신하지 않는다.
- 연습 단계는 폐기를 관측한 시점이다. 제브 대기 중 보관된 구간은 보관 행의 `seq`를 사용해 뒤에 열린 AI 행과 혼동하지 않는다. 기존 기록에 소급해 사유를 추정하지 않는다.

## JIRA-271 Realtime 사용량 수집

이번 범위는 **GPT-Live 연결과 Realtime Luna 실시간 판정 호출의 사용량 저장**이다. 관리자 인증·조회 API·화면·단가 계산은 후속 범위다. Core의 연습 준비·리포트·구조화 호출과 Jev는 포함하지 않는다. 자유대화에서 Luna 판정이 실제 호출되지 않았다면 Luna 행도 생기지 않는다. 기존 회원·활동·판정 표와 공급자 요청 본문은 변경하지 않는다.

| 경계 | 수집 값 | 저장 단위·해석 |
|---|---|---|
| `voice.ProviderConnector`의 수신기 장식자 | `session.usage.updated.usage.seconds`, `session.closed.usage.seconds`, 공급자 세션 ID·요청 모델 | 연결 한 개의 누적 초를 같은 키로 교체한다. 주기별 값을 더하지 않는다. 실제 공급자 연결이 여러 개면 각각 별도 행이다 |
| `luna.RealtimeLunaClient.decide`의 HTTP 완료 콜백 | `model`, `usage.input_tokens`, `output_tokens`, `input_tokens_details.cached_tokens`, `output_tokens_details.reasoning_tokens` | Decisions 실제 전송 시도 한 번당 한 행. 업무 응답이 거절·스키마 오류여도 받은 사용량은 별도로 기록한다. 보내기 전 거절은 행을 만들지 않는다. 판정 턴 ID는 공급자로 보내지 않고 서버 내부에서만 연결한다 |

공급자 정의는 [GPT-Live 대화 가이드](https://developers.openai.com/api/docs/guides/live-conversations)와 [Decisions 생성 API](https://developers.openai.com/api/reference/resources/decisions/methods/create)를 따른다. 음성은 토큰을 추정하지 않고 공급자가 준 **초**를 저장한다. 캐시 입력은 입력 토큰의 일부이고 추론 토큰은 출력 토큰의 일부이므로 전체 토큰에 다시 더하지 않는다. Decisions 응답은 `id`를 보장하지 않으므로 `response_id`는 없으면 `NULL`로 유지하고 서버가 생성한 호출 ID를 행 키로 쓴다. 키·오디오·전사·프롬프트·응답 원문은 사용량 표와 관측 지표에 저장하지 않는다.

비용 화면은 [Decisions 요금 규칙](https://developers.openai.com/api/docs/guides/decisions)을 별도로 적용한다. Decisions 출력 토큰은 과금하지 않으므로 Responses 출력 단가를 그대로 곱하지 않는다. 현재 표는 `input_tokens_details.cache_write_tokens`를 별도로 보관하지 않아 캐시 쓰기 요금을 정확하게 재현할 수 없다. 비용 단계에서 필요한 저장 구조 보완 또는 근사 계산 범위를 정한다. `COMPLETE`는 입력·출력 수치 수신 여부이며 청구액 재현을 보장하는 상태가 아니다.

`AiUsageCollector.offer`는 별도의 유한 메모리 큐에 값을 전달한다. 대화·전사 큐를 공유하지 않으며 수신 경로에서 DB·빈자리·재시도를 기다리지 않는다. 큐 잠금 경쟁이나 가득 참은 즉시 거절하고 지표로 남긴다. 정상이어도 값 생성·파싱·큐 전달 비용은 있으므로 성능 영향이 없다고 단정하지 않는다.

단일 `AiUsageRecorder` 저장 작업이 기존 Realtime DB 풀을 사용한다. `V9__ai_usage.sql`의 새 `realtime.realtime_ai_usage` 표에 기록하며 Core 스키마를 읽지 않는다. Luna는 기존 판정 턴 ID로 `realtime_judgment_live`가 생긴 뒤 세션·보호자를 연결한다. 판정 기록이 아직 없으면 대기 순서를 뒤로 보내 재시도한다. 기본 5분 동안 연결·저장되지 않으면 폐기하고 `expired`를 센다. 연결되지 않은 고아 행을 DB에 만들지 않는다.

| 행의 `capture_status` | 의미 |
|---|---|
| `COMPLETE` | 음성은 종료 이벤트의 유효한 최종 초를 받음. Luna는 해당 응답의 입력·출력 토큰을 모두 받음 |
| `PARTIAL` | 음성은 중간 누적 초만 있음. Luna는 입력·출력 중 일부만 있음 |
| `UNKNOWN` | 연결/전송 시도는 관측했지만 유효한 사용량을 확인하지 못함. 미확인 수치는 SQL `NULL`이며 실제 0과 구분 |

위 상태는 **개별 행**의 상태이며 전체 활동의 모든 호출이 수집되었다는 보장은 아니다. 큐 거절·기한 만료·강제 종료로 행 자체가 없을 수 있다. 후속 비용 화면은 집계 대상 범위와 "수집된 사용량 기준 예상 비용으로 실제 청구액과 다를 수 있습니다"를 표시한다. 폐기 지표는 코드에만 두고 관리자 화면에는 추가하지 않는다. 미확인 수치(`NULL`)를 0으로 더하거나 과거 미수집 활동을 0원·수집 완료로 바꾸지 않는다. 응답 ID가 있는 Luna 중복 응답은 고유 인덱스로 중복 저장을 막고 음성은 스냅샷 revision으로 오래된 갱신을 무시한다.

기본은 `MUJOONG_AI_USAGE_ENABLED=false`다. 종료 시 최종 사용량을 별도로 기다리지 않는다. 마지막 중간 누적값만 있으면 `PARTIAL`, 연결이 닫히기 전에 유효한 `session.closed` 최종값을 수신하면 `COMPLETE`, 사용량을 받지 못하면 `UNKNOWN`으로 저장한다. 기존 송신·WebSocket 닫기의 타임아웃은 유지하며 사용량 전용 대기와 추가 AI 호출은 하지 않는다. 시간·큐 한도는 [timing.md 15절](timing.md#15-jira-271-사용량-수집)과 Realtime `application.yml`에 정의한다. 메모리 큐는 `@SingleInstanceAssumption(SI-10)` 대상이며 강제 프로세스 종료 시 미저장 값은 유실될 수 있다.

계정 삭제에서는 실행 중 세션을 먼저 멈추는 기존 흐름 뒤에 `AiUsageDeletionParticipant`(170)가 새 쓰기를 차단하고 진행 중 저장을 제한된 시간 기다린 뒤 보호자의 사용량을 지운다. 판정 기록(180)·세션(200)보다 먼저 실행된다. 진행 중 저장을 정리하지 못하면 삭제 요청을 실패로 돌려 기존 Inbox 재시도를 따른다. DB의 세션 외래 키도 삭제된 세션의 지연 사용량 복원을 막는다.

관측: `mujoong.ai.usage.pending`, `mujoong.ai.usage.records{outcome=accepted|full|contention|stopped|stored|retry|unlinked|expired|discarded}`. `accepted`는 중간 스냅샷·중복 전달을 포함하는 전달 횟수, `retry`·`unlinked`는 저장 시도 횟수다. 어느 것도 대화 수나 고유 호출 수로 해석하지 않는다. 검증·실호출 절차는 [RUNBOOK 18절](../infra/RUNBOOK.md#18-jira-271-사용량-수집-수동-검증)에 둔다.

## JIRA-277 Core 사용량 수집

이번 범위는 **Core가 대화 시작 전 · 종료 후에 부르는 Luna(Responses API) 호출의 사용량 저장**이다. 관리자 인증 · 조회 API · 화면 · 단가 계산과 Jev 비용은 후속 범위다. Realtime의 GPT-Live · 실시간 판정 사용량은 JIRA-271이 따로 저장한다. 기존 회원 · 활동 · 결과 표와 공급자 요청 본문은 바꾸지 않는다.

| 목적(`purpose`) | 언제 | 붙이는 대상(`session_id`) |
|---|---|---|
| `SCENARIO_GENERATION` · `SCENARIO_REVIEW` | 연습 장면 생성 · 검수(시작 준비, 진행 중 새 장면) | 연습 준비 ID. 준비가 성공하면 같은 ID로 활동이 시작되고, 실패하면 활동 행이 없는 시작 실패 비용이다 |
| `RESULT_TEXT` · `GOAL_CANDIDATES` · `REJUDGMENT` | 종료 후 결과 작업(구조화 · 판정 · 결과 문장 · 후보 · 2차 분류 · 목표 추천 · 재판정) | 결과 작업의 활동 세션 ID |
| `SEED_GENERATION` | 결과에서 나온 목표 후보의 지식 시드 생성 | 그 후보가 나온 활동 세션 ID. 화면은 목적으로 따로 묶을 수 있다 |

- **경계**: `luna.CoreLunaClient.call`이 실제로 보낸 시도마다 응답 본문에서 `model` · `id` · `usage.input_tokens` · `usage.output_tokens` · `usage.input_tokens_details.cached_tokens`(캐시 읽기) · `usage.input_tokens_details.cache_write_tokens`(캐시 쓰기) · `usage.output_tokens_details.reasoning_tokens`만 읽어 한 행을 만든다. 업무 응답이 거절 · 스키마 오류 · 내용 검사 불통과여도 받은 사용량은 기록하고, 응답을 받지 못한 시도(시간 초과 · 전송 오류)는 `UNKNOWN` 행이다. 보내기 전 거절(입력 정리 실패 · 시한 0 · 주소 재확인 · 빈 키)은 행을 만들지 않는다. 기술 재시도 · 스키마 재요청도 시도마다 따로 기록하고 같은 호출은 `call_id`로 묶는다. 캐시 읽기 · 캐시 쓰기는 서로 겹치지 않는 입력 토큰의 일부이고, 추론 토큰은 출력 토큰의 일부라 더하지 않는다. 캐시 쓰기는 일반 입력 · 캐시 읽기와 단가가 달라([프롬프트 캐싱](https://developers.openai.com/api/docs/guides/prompt-caching)) `cache_write_tokens`로 따로 보관한다. 일반 입력 = `input_tokens - cached_input_tokens - cache_write_tokens`이다. Core는 `prompt_cache_options`를 보내지 않아 기본 보관 시간만 쓰므로 보관 시간별 세부(`cache_write_tokens_details`)는 저장하지 않는다.
- **활동 연결**: 연결 대상은 서버 내부 값이며 공급자 요청에 넣지 않는다. 기존 요청 · 게이트웨이 시그니처를 바꾸지 않으려고 작업 진입점(`SessionResultHandler` · `ScenarioService` · `SeedGenerationService`)이 `usage.AiUsageScope`로 대상을 열고, 다른 실행기로 일을 넘기는 곳(`ResultTextGateway` · `TextAiGateway` · `ScenarioDeadline`)이 대상을 함께 넘긴다. 이 경로 밖에서 생긴 호출은 `guardian_id` · `session_id`가 비어 있는 **연결 미확인** 행이다.
- **저장**: `AiUsageCollector.offer`는 전용 유한 메모리 큐에 넣기만 하고 DB를 기다리지 않는다. 저장 작업 하나(`AiUsageRecorder`)가 `V12__ai_usage.sql`의 `core.core_ai_usage`에 기록마다 짧은 트랜잭션으로 넣는다. 같은 행 키 · 같은 응답 `id`는 한 행만 남긴다. 큐가 가득 차면 새 기록을 버리고, 저장 실패는 다음 주기에 다시 시도하며, 보관 시간을 넘기면 버린다. 사용량 수집 실패는 결과 작업 · 장면 생성 · 시드 생성의 결과를 바꾸지 않는다. 미저장 기록은 프로세스 메모리에 있어 강제 종료 때 잃을 수 있다.
- **미확인 · 누락**: 미확인 토큰은 SQL `NULL`이고 실제 0과 다르다. `capture_status`는 행 하나의 상태(`COMPLETE` 입력 · 출력 모두, `PARTIAL` 일부, `UNKNOWN` 없음)이며 모든 호출이 수집되었다는 보장은 아니다. 후속 비용 화면은 "수집된 사용량 기준 예상 비용"으로 표시하고 `NULL`을 0원으로 더하지 않는다. 기간 합계는 `observed_at`(시도가 끝난 시각) 기준이다.
- **계정 삭제**: `AiUsageDeletionParticipant`(순서 50)가 그 보호자의 대기 기록을 버리고 진행 중 저장이 끝나기를 제한 시간 기다린 뒤 행을 지운다. 끝나지 않으면 예외로 지휘의 재시도를 따른다. 그 뒤 늦게 저장된 행은 지휘의 두 번째 참가자 호출이 지우고, 보호자 행이 지워진 뒤에는 외래 키가 저장을 막는다.
- **스위치 · 관측**: 기본은 꺼짐(`MUJOONG_CORE_AI_USAGE_ENABLED=false`, Realtime 스위치와 따로)이다. 시간 · 한도는 [timing.md 16절](timing.md#16-jira-277-core-사용량-수집)과 Core `application.yml`(`mujoong.limits.ai-usage.*`)에 둔다. 지표는 `mujoong.ai.usage.pending`, `mujoong.ai.usage.records{outcome=accepted|full|stopped|stored|retry|expired|discarded}`이며 대화 수 · 고유 호출 수로 해석하지 않는다. 키 · 프롬프트 · 입력 · 출력 원문은 사용량 표와 지표에 남기지 않는다.
