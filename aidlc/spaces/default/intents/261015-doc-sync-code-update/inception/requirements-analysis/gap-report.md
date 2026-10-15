# 무중 AI 동작 문서(15 폴더 · Luna/JEV 인계서) 대비 코드 갭 보고서

- 작성: 2026-10-15 · 읽기 전용 조사 · 기준 코드 `feat/doc-sync-code-update` = main `72d7034`
- 문서 약칭
  - **A** = `15_기술명세_AI동작_1015/무중_기술명세서_v0.3.md`
  - **B** = `15_기술명세_AI동작_1015/무중_AI동작명세_v0.2.md`
  - **C** = `mujungi_Luna_JEV_guardrail_implementation_handoff_v2_2026-10-15.md`
  - **D** = 같은 날 고친 기존 문서(`git diff HEAD -- …/documents`: 00_읽는순서, 개발명세 결정기록, 요구사항 v1.1, 40_기능명세 3편, ADR-002/004/005/006/007/009, architecture 4편, decision-log, 점검표)
- 상태 표기: 구현됨 / 일부 / 없음 / 문서와 다르게 구현. 규모: S(하루 안팎) · M(수일, 한 유닛 안) · L(여러 모듈·계약·마이그레이션·시험 동반).
- 실제 GPT-Live · GPT-6-Luna · 제브(Jev) API는 여기서 확인할 수 없으므로 선 형식의 "정답"은 [미확인]이다. 표의 "문서와 다르게"는 코드와 문서가 다르다는 사실만 뜻한다.

---

## 0. 문서 우선순위 진술

| 출처 | 진술(요지 인용) |
|---|---|
| `.claude/CLAUDE.md` | "기준 문서는 화면 설계 v1.5j … 문서끼리 다르면 화면 설계가 맞다." |
| 00_읽는순서.md (D에서 추가) | "그다음은 15_기술명세_AI동작_1015다. … 20~80의 문서 … 와 다르면 이 두 문서를 따른다. 특히 모델 역할(GPT-Live 아동 대화 · GPT-6-Luna 진행 중 판정·J7 … · 제브 가드레일) … 가드레일 대상과 처리 … 는 이 두 문서가 기준이다." 표 순서: 10 → 15 → 20 … 80. **C는 이 목록에 없다**(문서 폴더 최상위에 따로 있음). |
| A 머리말 | "화면 설계를 먼저 따른다. 그다음은 요구사항 명세서 v1.4 → 기능명세서 v1.3 → 이 문서 순이다. 개발 지식 폴더에서는 이 문서와 AI 동작 명세가 … 다른 문서 … 보다 앞선다." |
| B 머리말 | "… 기능명세서 v1.3 → 기술명세서 → 이 문서 순이다." (A가 B보다 앞섬) |
| C 머리말 | "**이 개정본을 이전 문서보다 우선한다.**"(이전 인계서 대비) · "작성 기준: 2026-10-15, main 72d70344…" · 상태 표기 `[확정 방향]`/`[구현 제안]`/`[미확인]` |

정리: 00_읽는순서와 A·B는 "15 폴더가 20~80보다 앞선다"를 명시하지만, **C와 A·B 사이의 우선순위를 정한 문서는 없다.** C의 "우선한다"는 이전 인계서에 대한 것이다. A·B가 참조하는 요구사항 v1.4 · 기능명세서 v1.3은 폴더에 없다(폴더에는 요구사항 v1.1 · 기능명세서 v1.0) [버전 불일치, 사실만 기록].

---

## 1. GPT-Live 음성 어댑터와 선 형식 (A §3, §5.6)

| 요구/진술 | 코드 상태 | 근거 | 규모 |
|---|---|---|---|
| 모델 `gpt-live-1` (A §1.2·§5.6) | 구현됨 | `realtime/voice/ProviderRequestFactory.MODEL`(L28, 코드 상수) | — |
| 연결 `wss://api.openai.com/v1/live/sessions` (A §5.6) | 구현됨 | `application-demo.yml`·`application-prod.yml` `mujoong.provider.url`, `ProviderHosts.ALLOWED_HOSTS={api.openai.com}` | — |
| 키 `OPENAI_API_KEY` (A §5.6·§11.3) | 문서와 다르게 | 환경 변수 `PROVIDER_API_KEY`(`ProviderClient` L32·86, `contracts/ai-io.md` §2) | S |
| `session.start` = `model`·`instructions`·`audio.format{type:"audio/pcm",rate:24000}`·`audio.output.voice` (A §5.6) | 문서와 다르게 | `ProviderRequestFactory.SessionStart(type, model, instructions, childContext{displayName,age,interests}, audio{input:"pcm16le/24000/1",output:…})`. voice 없음, childContext 필드는 A에 없음 | S (실 API 확인 뒤) |
| 지시 주입 `session.instructions.append`(`delegation_id:null`, 500토큰, `…appended` 응답) (A §3.2 6단계·§5.6, B §3.1) | 문서와 다르게 | 코드에 없음. 대신 `session.instructions.correct{text}`(교정·연습 발화) + `session.response.cancel{responseId}` + `session.response.create`(A에 없는 이벤트) — `ProviderRequestFactory` L36~39·138, `ai-io.md` §3 | M |
| 전사 이벤트 `session.input_transcript.delta`·`session.output_transcript.delta`, 출력 `session.output_audio.delta`(`start_ms`·`end_ms`) (A §5.6) | 일부 | 이름은 같음(`ProviderClient` L195~201). `start_ms`·`end_ms` 미사용 | S |
| A §3.7: "입력 전사 완료 이벤트를 찾지 못했다" → VAD로 발화 끝 판정 | 문서와 다르게 | 코드는 `session.input_transcript.done`(확정 전사·confidence), `session.input_audio.speech_stopped`, `session.output.done`(게이트 완결 신호)을 전제(`ProviderClient` L193~205, `ai-io.md` §3). 서버 VAD 없음. 실 API에 이 이벤트가 없으면 게이트 완결 판정(`SegmentBuffer.Sealed.complete()`)이 성립하지 않음 [미확인] | M |
| 오류 이벤트 `error` (A §5.6) | 문서와 다르게 | `session.error`(`ProviderClient` L204) | S |
| `session.input_audio.mute/unmute`, `session.update` (A §5.6·§3.8) | 없음 | 요청 레코드에 없음 | S |
| 출력마다 `outputId`·`epoch`, `output.begin{outputId,epoch}`, `output.stop{epoch}`, `playback{outputId,epoch,event}` (A §3.3·§3.6·§5.5) | 문서와 다르게 | 기기 프로토콜은 `OUTPUT_BEGIN(playbackId, source)`·`STOP_OUTPUT(reason)`·`PLAYBACK_MARK`(`contract/ws/message/OutputBegin.java`, `StopOutput.java`). 세대는 서버 내부 `outputGeneration`(`SessionExecutor.sealAndGate` L2169)이고 기기로 보내지 않음 | M (계약 변경 시) |
| `LiveVoiceAdapter`로 GPT-Live/Realtime API 교체 가능 (A §3.3·§9) | 일부 | `ProviderPort`·`ProviderClient`가 포트 역할. 이름·Realtime API 대체 구현 없음 | S |
| 승인 문구·고정 대본은 GPT-Live 원문 낭독 + 출력 전사 대조, `ScriptSpeaker` 경계 (A §3.1·§3.4, B §4.2) | 문서와 다르게 | 마무리·대기 문구는 미리 만든 WAV를 `PLAY_APPROVED_CONTENT`로 재생(`gate/ApprovedPlayback`, `ai-io.md` §5). 연습 발화만 GPT-Live 낭독 + `PracticeSpeech.matches`(공백 제거 뒤 완전 일치, L21~22). `ScriptSpeaker` 없음 | M |
| 다음 행동 지시 문안 `D_SAY`…`D_RESUME_TURN` (B §3.1·§3.2) | 없음 | `dialog/DialogInstructions`(역할 지시문·교정 문장), `voice/PracticeInstruction.forSpeech`(고정 템플릿 + JSON `{partnerRole, speech}`)만 | M |
| 재생 중 입력 전사를 아이 발화로 쓰지 않음 (A §3.5-4) | 일부 | `StopIntentJudge` 1단계(재생 중 확정 전사 → NONE)만. 일반 턴 입력 거르기는 [미확인] | S |
| 가짜 공급자 | 코드 형식만 지원 | `tools/fake-provider/server.mjs` L206~234(`session.start`·`input_audio.append`·`response.cancel`·`instructions.correct`·`response.create`·`close`), `test-support/…/FakeProviderWsServer`. 선 형식을 바꾸면 가짜 공급자 · `ProviderRequestWhitelistIT` · `tools/tests/u1~u4b`를 함께 고쳐야 함 | M |

---

## 2. 출력 게이트 (A §3.3, §7.7 마지막 행들 · C §3.4)

| 요구/진술 | 코드 상태 | 근거 | 규모 |
|---|---|---|---|
| 일반 턴 출력 게이트 통과 후 기기 재생 (A §3.2) | 구현됨 | `OutputGate.inspect`(완결 구간·전사·PCM·시드 사전) → `recheck`(ACTIVE·세대·연결·기록기·레인) → 송신, `SessionExecutor.sealAndGate` L2165~2201. 응답 전체를 `SegmentBuffer`에 모은 뒤 판정(fail-closed) | — |
| 판정 턴: 게이트를 닫고 자동 응답을 버림, 버린 출력 전사는 '보류된 출력'으로 진행용 판정 기록에만 (A §3.3) | 일부 / 문서와 다르게 | "판정 턴" 개념 없음. 연습 모드(`practice.controlsOutput()`)에서 승인 발화와 완전 일치하지 않는 모든 출력을 `GATE_PATTERN`으로 폐기(L2183~2187). 폐기 전사를 판정 기록에 남기는 경로 없음 | M |
| 출력 폐기 때 `epoch` +1, `output.stop{epoch}` (A §3.6) | 일부 | 서버 세대 증가·`STOP_OUTPUT` 있음. 기기 측 epoch 비교 없음 [세부 미확인] | S |
| 아이 말 끝 뒤 10초 안 응답 없으면 판정 보류 → 무난한 되묻기(`D_HOLD`) (A §3.2-7) | 없음 | — | M (4절과 함께) |
| GPT-Live 출력 문장마다 2차 제브 검사, **결정 전 기준 기록만**(`GUARDRAIL`, `guardrail_event`) (A §7.7, B §10.3, D: GPT-Live 기기 요구 v0.1 "버퍼링하지 않고 … 문장 단위로 검사·기록") | 없음 | 제브 호출 경로 없음 | M |
| **C §3.4**: 신규 GPT-Live 발화만 `inspect` 뒤 · `recheck` 전에 JEV, `ALLOW`일 때만 송신, `BLOCK/REVIEW`·타임아웃·잘못된 JSON·버전 불일치는 재생 안 함, 늦은 `ALLOW` 무효 | 없음 | 끼울 자리는 `sealAndGate` L2176~2190. 실행기는 세션당 단일 스레드라 동기 HTTP를 그대로 넣을 수 없고 비동기 결과·세대 확인·버퍼 보관 시한이 필요 | L |
| 승인 원문과 정확히 일치하는 재생은 JEV 생략, 수정·추가 발화는 신규로 분류 (C §0.8·§2·§3.4-2) | 일부 | 판별 함수는 있음(`PracticeSpeech.matches`). 현재 연습 모드는 불일치를 곧바로 폐기하므로 "신규 발화 → JEV" 분기 없음. 자유대화는 전부 신규 발화 | S |
| JEV 결과를 `GateDecision`과 섞지 않음 (C §3.4-9) | 없음(설계 제약) | `gate/GateDecision.DiscardReason` | S |
| 출처별 호출 수·P50/95/99·차단·타임아웃 비율 계측 (C §3.4-10) | 없음 | `observability/RealtimeMetrics`에 해당 지표 없음 | S |
| 1차 사전 (A §7.7, B §10.1: 9분류 + '기기 음성' 분류 `나는 사람`·`비밀`·`잘했어`…) | 일부 | `gate/seed-dictionary.json` 7범주(DIAGNOSIS…PRESSURE) 시드 몇 개(`ai-io.md` §4 "U1 시드 항목"), 리터럴 패턴만. '기기 음성'·'효과 보장' 분류 없음 | M |
| 활동 뒤 무중 출력 전사 전체 1차 재검사·건수 기록 (A §7.7, B §10.3) | 없음 | — | S |

---

## 3. 제브(JEV) 가드레일 클라이언트 (A §5.7, §7.7 · B §10.2 · C §3)

| 요구/진술 | 코드 상태 | 근거 | 규모 |
|---|---|---|---|
| `GuardrailClient`(A) / `SemanticGuardrailProvider`(C) 독립 포트 | 없음 | 저장소 전체 grep 0건(`GuardrailClient`·`SemanticGuardrail`·`jev`·`typesafe`) | M |
| REST 구현 `POST https://api.typesafe.ai/v1/systemone`, 모델 버전 고정(`jev-1.13.0`) (A §5.7) | 없음 | C §3.5는 엔드포인트·모델을 `[미확인]`으로 두고 추측 금지 | M (접근 승인 뒤) |
| 승인 전 가짜 구현(1차 사전만) (A §5.7) / 명시적 비활성·테스트 모드 (C §3.4) | 없음 | — | S |
| 요청·응답 스키마: B §10.2 분류 11개(`DIAGNOSIS`·`SCORE`·`TREATMENT`·`DEFICIT`·`GENERALIZE`·`MIND_READING`·`FORCE`·`PRESSURE`·`PERSONA`·`LEAK`·`PASS`)+확률 / C §3.1 `decision`(ALLOW/BLOCK/REVIEW)·`categories`(7범주)·`policyFlags`·`reasonCode`·`policyVersion` | 없음 | 두 문서 스키마가 다름(아래 부딪힘 3) | S~M |
| `guardrail_event` 테이블 (A §6.1) | 없음 | core V1~V7, realtime V1~V4 어디에도 없음 | S |
| 내부 이벤트 `GUARDRAIL`, `usage_log`의 `JEV` 호출 수 (A §5.4·§9) | 없음 | 내부 이벤트는 `SessionStateChanged`·`SessionRecordCaptured`·`DeviceStatusChanged`·`DeletionCompleted` 등 굵은 단위뿐(`contract/event/EventType.java`) | M |
| `content/guardrail/` 버전 관리, 세션 고정 (A §7.7·§7.8) | 없음 | 저장소에 `content/` 폴더 없음 | S |
| `JEV_API_KEY` 설정 (A §11.3) | 없음 | — | S |
| 제한 시간 800ms 후보 (A §5.7·§13) | 없음 | — | S |
| Luna 생성물(대본·주제 힌트·결과 문장·생활 팁·리포트)·승인 문구 배포 전 제브 검사 (A §7.7 1~4행) | 없음 | U6 2차 검사는 Luna `classify` 작업(`result/OutputChecker`, `prompts/result/classify.v1.txt`). **C는 이 경로를 금지** | M (채택 시) |

---

## 4. Luna 진행 중 판정 J1~J7 (A §4.3, §5.8, §7.3 · B §5)

| 요구/진술 | 코드 상태 | 근거 | 규모 |
|---|---|---|---|
| `JudgeClient`(Luna, Responses API 구조화 출력, `OPENAI_JUDGE_MODEL`) (A §2.5·§5.8) | 없음 | grep 0건. 가장 가까운 것은 `practice/EvaluationProvider`(요청: criteria·responseText, 결과: label·reason·reviewNeeded). 기본 빈은 `failedFuture("판정 공급자 미연결")`(`PracticeRuntimeConfiguration`). openai-java 의존성 없음 | M(포트) + M(어댑터) |
| J1 시작 안내 응답 5선택지(참여/휴식 요청/거부/무관·불명확/판독 불가) | 문서와 다르게 | `dialog/JoinTracker.classify` 낱말 규칙(ACCEPT/REFUSE/TOPIC_REFUSAL/UNCLEAR). 휴식 요청 분류 없음, Luna 호출 없음 | M |
| J6 종료 의도(종료 명확/불명확/종료 아님/휴식 요청) (A §3.5, B §6.2) | 문서와 다르게 | `dialog/StopIntentJudge` 규칙 판정(STOP/CONFIRM/NONE). "쉬고 싶어"는 CONFIRM. `REST_REQUESTED` 이벤트 없음(grep 0건) | M |
| J2/J3/J5 반응 판정(코드 `FULL`·`PARTIAL`·`HELP`·`NOT_UNDERSTOOD`·`IRRELEVANT`·`UNREADABLE`·`DELIVERED`·`STILL_SHORT`·`COPIED`) | 문서와 다르게 | `PracticeProtocol.response()` L171~178이 `judgmentCriteriaSnapshot`+발화를 평가 포트로 보냄. 라벨은 `functional/partial/none/uncertain`(`PracticeTypes.Label`) | L |
| J4 도움 동의(수락/거절/불명확) | 문서와 다르게 | `RuntimePracticeExtension` L127~128 완전 일치 낱말 목록("응"·"네"·"좋아"·"도와줘" / "아니"·"괜찮아"·"싫어"…) | S |
| 연습 상태 기계 `SCENE_INTRO`·`OPPORTUNITY`·`REASK`·`HELP_OFFER`·`FOCUS`·`RETRY`·`SCENE_WRAP`·`VARIANT`·`NEXT_GOAL`·`CLOSING` (A §4.3) | 문서와 다르게 | `PracticeTypes.Stage` = `SCENARIO_INTRO, FIRST_RESPONSE, HELP_OFFER, CUE, AFTER_CUE_RESPONSE, NEW_SCENARIO_RESPONSE`. 되묻기·상황 다시 설명·확인·보류·역할(친구/코치) 전환·장면 정리 단계 없음 | L |
| 변형 장면은 조건부(시간·참여 의사·10분 전·집중 연습 길이) (A §4.3 `SCENE_WRAP`) | 문서와 다르게 | `PracticeProtocol.judged()` L193~195: 첫 반응이 partial/none이면 `help()`, 그 밖은 항상 `prepareNew()`(새 상황 필수). 무응답도 `help()`(L186) | L |
| 목표당 10분(ACTIVE 시간) (A §4.3·§13) | 없음 | `practice/u5b-runtime.yml`에 없음 | S |
| 판정 제한 시간 2초, 시간 초과·형식 오류는 재시도 없이 보류 (A §13, B §5.1, D: ADR-005 §10.1·decision-log DL-005-07) | 문서와 다르게 | `judge-budget: 5s`, `judge-total-budget: 12s`, 시도 최대 2회 → `uncertain`(`u5b-runtime.yml`, `ResponseEvaluator` L64~111). `contracts/timing.md` L68도 "5초 + 재시도 1회" | S |
| 1·2순위 확률, 확신 문턱으로 보류 (A §5.8·§7.3) | 없음 | 결과에 확률 없음 | M |
| 진행용 판정 기록 `judgment_live`(point·options_version·top1/p1/top2/p2·held·hold_reason·latency_ms·next_action·withheld_output_text) | 문서와 다르게 | `realtime.realtime_practice_evaluation(data jsonb: label, attempts≤2)` (realtime V4) | M |
| 집중 연습: GPT-Live가 대본의 `coach_point`·`example_expression`을 받아 코치로 생성 (A §3.4·§4.3, B `D_FOCUS`) | 문서와 다르게 | `CueProvider`(외부 생성, `Result(text, approved, containsAnswerSentence)`), 기본 `UNAVAILABLE`. 정답 예시 포함이면 송신 금지(`StageOutputPolicy.cue`) | M |
| 다음 행동 지시에 판정 라벨·기준을 넣지 않음 (A §4.3, FR-F15) | 구현됨 | `PracticeInstruction.forSpeech`는 역할·발화 텍스트만, `ProviderRequestFactory` 타입에 판정 필드 없음 | — |
| J7 | 6절 참조 | | |

---

## 5. Luna 분석 작업 (A §5.8, §7.4 · B §9 · C §5)

| 요구/진술 | 코드 상태 | 근거 | 규모 |
|---|---|---|---|
| openai-java `OpenAIClient` Bean + `LlmClient`, Responses API 구조화 출력 (A §5.8·§11.4) | 없음 | 의존성 없음. U5a `textai/TextAiProvider` 기본 빈은 `UNAVAILABLE`(`TextAiConfiguration`), U6 `result/ResultTextProvider` 기본 빈은 `PROVIDER_UNAVAILABLE`(`ResultTextConfiguration`) | M (U5a) + M (U6) |
| 설정 `OPENAI_API_KEY`·`OPENAI_ANALYSIS_MODEL`(기본 `gpt-6-luna`) (A §5.8·§11.3) | 문서와 다르게 | 예약 이름 `MUJOONG_TEXT_AI_ENDPOINT`·`_MODEL`·`_API_KEY`(core `application.yml` L223, `ai-io.md` §7), `mujoong.text-ai.request-timeout: 3s`, `result.request-timeout: 10s` | S |
| 주제 힌트 (A §7.4, B §9.2) | 없음 | — | M |
| 장면·기회 대본 출력(`theme`·`relationship`·`situation`·`topic`·`intro`·`friend_role`·`partner_opener`·`opportunity_line`·`reexplain`·`coach_point`·`example_expression` + VARIANT) (B §9.3) | 문서와 다르게 | `GeneratedScenario(scenarioText, triggerScript, relationType, opportunityCondition, modelVersion, promptVersion)`, `partnerRoleText`는 서버 고정(`ai-io.md` §7). 입력 `ScenarioPrompt`에 `skill_context`·`variation_plan`·노출 이력 없음 | L |
| 변형 장면 보존 판정 (A §7.4, B §9.3) | 없음 | — | M |
| 경험 구조화 (A §7.4, B §9.4) | 일부 | `structure` 작업 + `ResultAiSchema` + `prompts/result/structure.v1.txt`. 다음 자유대화용 이전 기록 요약은 없음 | S |
| 기록용 재판정 (A §7.4, B §9.5) | 일부 | `judgment` 작업. 스키마가 `sceneNo≤2`, `timing ∈ {IMMEDIATE, AFTER_HELP}`(`AFTER_REASK` 없음), `goalSelectionId` 키(`ResultAiSchema` L13) | M |
| 목표 후보·4요소 자유 생성 + `skill_ref` ("미리 정한 목록에서 고르지 않는다", B §9.6) | 문서와 다르게 | `candidates` 작업이 ACTIVE `core_behavior_definition`의 `behaviorKey`·`behaviorVersion`을 고름(`ResultAiSchema` L12, `core_goal_recommendation` FK). C §5.2는 코드 쪽과 같음 | L |
| 새 씨앗 생성 (A §6.4·§7.4, B §9.6) | 없음 | — | M |
| 결과 문장 (B §9.7) | 구현됨(구조) | `sentences` 작업, 불일치 시 고정 템플릿(`OutputChecker`) | — |
| 생활 팁: Luna가 최대 2개 생성 + 근거 장면 (A §7.4, B §9.8) | 문서와 다르게 | `TipMatcher`가 승인된 `core_support_item`과 행동 키 교집합을 고름(`ai-io.md` §6.9) | M |
| 리포트 10항목 (B §9.9) | 구현됨(구조) | `report` 작업은 2·5·6·10항목만 AI, 나머지 서버 집계(`ResultAiSchema` L14). 세부 일치는 [미확인] | S |
| 2차 금지 표현 검사 = 제브 (A §7.7) | 문서와 다르게 | Luna `classify` 작업(7범주) — C §5.2와 같음 | (부딪힘 1) |
| 결과 작업 재시도: 추가 최대 3회, 5·15·30초 (A §7.6) | 문서와 다르게 | `job-attempts: 3`, `job-retry-backoff: [5s, 15s]`(core `application.yml`), `attempt_count BETWEEN 0 AND 3`(V5) | S |
| 프롬프트 버전 파일 위치 `content/analysis-prompts/` (B §1.4) | 문서와 다르게 | `core-api/src/main/resources/prompts/result/*.v1.txt` | S |
| 결과 행에 model·prompt_version 저장 (A §5.8) | 구현됨 | `core_scenario.model_version/prompt_version`, U6 결과 행 | — |

---

## 6. 상황 자체 검토 / J7 (A §7.3·§7.4 · B §5.2 J7 · C §5.1)

| 요구/진술 | 코드 상태 | 근거 | 규모 |
|---|---|---|---|
| J7 생성 대본 사전 점검(Luna, `ANSWER_INCLUDED`·`EARLY_CUE`·`PRESSURE`·`PASS`, PASS+문턱만 통과, 1회 재생성 뒤 2회 실패면 시작 안 함) (A §7.3, B §5.2·§5.3) | 없음 | `TextAiProvider`는 `generateScenario` 하나뿐 | M |
| C §5.1 Luna 별도 자체 검토(`APPROVE`/`REJECT`/`UNCERTAIN`, `reasons` 예 `ANSWER_LEAK`, `reviewVersion`) | 없음 | C §1 "현재 U5a `TextAiProvider`에는 Luna 자체 검토 전용 포트·두 번째 호출 계약이 없다" — 코드로 확인 | M |
| 서버 독립 검사(스키마·크기·금지 표현·트리거 포함·관계/기회 일치·지문 중복·1회 재생성 뒤 `SCENARIO_GEN_FAILED`) | 구현됨 | `textai/TextAiGateway`, `practice/ScenarioContentPolicy`, `textai/u5a-forbidden-patterns.txt`, `core_scenario.content_fingerprint` UNIQUE(child_id, …) | — |
| 장면 소재 금지 분류(술·담배·위험·질병·폭력·돈·실명) (B §10.5) | 없음 | — | S |
| 관계·자리가 아동용 허용 값인지 (A §6.4, B §9.3 ④) | 없음 | `relationType`은 자유 텍스트(200자) | S (KG 뒤) |
| 대본 처리 순서: 구조 맥락 → 생성 → 자동 확인 → 금지 표현 → 보존 판정 → J7 → 제브 (A §7.4) | 없음 | 현재: 생성 → Gateway 검사 → 내용 확인 | M |
| U5a 전체 15초 안에 생성+검토+재생성 (C §5.1) | 일부 | `mujoong.practice.scenario-request-timeout: 15s`(C10)에 생성·기술 재시도 1회·내용 재생성 1회만 포함. 검토 호출 예산은 없음. A는 시작 전 모든 목표 생성(TD-16)만 말하고 15초를 언급하지 않음 | M |

---

## 7. 지식 그래프 Neo4j (A §6.4 · C §6)

| 요구/진술 | 코드 상태 | 근거 | 규모 |
|---|---|---|---|
| `KnowledgeAdapter`(아이별 그래프: `initChildGraph`·`listSeeds`·`skillContext`·변형 계획·생성 씨앗 쓰기/지우기, 모든 Cypher에 `child_id`) (A §6.4) | 없음 | `knowledge/KnowledgeGraphLookup.findRelatedBehaviorKeys(behaviorKey)` 하나뿐, 구현 빈 없음, `mujoong.knowledge.neo4j.enabled: false`. `PragmaticsKnowledge.relatedBehaviorKeys()`는 호출하는 곳이 없음 | L |
| C §6.3 실제 Neo4j read adapter + 상황 생성 전용 타입 있는 조회 계약 | 없음 | 위와 같음 | M |
| 적재: `ai/kg/` 스크립트, social-comm-poc 원천 14씨앗·어휘 노드·`KgVersion` (A §6.4) | 없음 | `ai/` 폴더 없음. 원천 ZIP·`graph_out` 존재 여부 [미확인](C §6.2) | M |
| Neo4j 컨테이너 | 일부 | `infra/compose.demo.yml` L351~354 `neo4j:5.26.31-community`(프로필 `knowledge`, 꺼짐) | — |
| 그래프 장애·`skill_ref=NONE`이면 구조 맥락 없이 생성(2초 제한) (A §6.4·§13) | 일부(결과적으로) | 그래프를 전혀 쓰지 않으므로 항상 "맥락 없이" 경로 | — |
| 아동·계정·기록 삭제 때 아이 그래프·생성 씨앗 삭제 (A §6.5) | 없음 | 삭제 참여자(`*DeletionParticipant`)에 그래프 없음 | M |
| 시험·개발용 메모리 가짜 구현 (A §6.4·§11.2) | 없음 | — | S |

---

## 8. 데이터 모델 차이 (A §6.1·§6.2 ↔ Flyway core V1~V7, realtime V1~V4)

스키마 이름부터 다르다: A `core`·`activity`, 코드 `core`·`realtime`(테이블 접두사 `core_`·`realtime_`).

| A 테이블 | 코드 대응 | 상태 | 규모 |
|---|---|---|---|
| `session`(kind·status·end_reason·result_status·shortfalls·content_version·judge_spec_version…) | `core.core_activity`, `realtime.realtime_activity_session` | 일부(판정 명세 버전·shortfalls 없음 [세부 미확인]) | S |
| `utterance`(role FRIEND/COACH, output_kind APPROVED/SCRIPT/DIRECTED/FREE) | `realtime.realtime_transcript`(speaker CHILD/AI) | 일부(역할·출력 종류 없음) | S |
| `scene`(type BASE/VARIANT, script jsonb, skill_context, check_result) | `core.core_scenario`(kind ORIGINAL/NEW_SCENARIO, scenario_text·trigger_script·partner_role_text·validation_json) | 문서와 다르게 | M |
| `scene_exposure`(seed_id, context) | `core_scenario.status=EXPOSED`·`exposed_at`·`content_fingerprint` | 일부(씨앗·맥락 없음) | S |
| `generated_seed` | 없음 | 없음 | S |
| `goal_candidate`(4요소·skill_ref·kg_version·evidence) | `core.core_goal_recommendation`(behavior_key/version FK, reason_text) + `core_recommendation_evidence` | 문서와 다르게 | M |
| `saved_goal` | `core.core_practice_goal_selection` | 구현됨(이름 다름) | — |
| `scene_record`(first/after_help utterance, result_label…) | `realtime_practice_attempt`·`realtime_practice_response` + `core_scene_judgment` | 일부 | S |
| `help_given`(type REASK/FOCUS/REEXPLAIN/CLARIFY, example_given, consent_answer) | `realtime.realtime_practice_cue`(attempt당 UNIQUE 1건, consentResponse) | 문서와 다르게 | M |
| `judgment_live` | `realtime.realtime_practice_evaluation`(label·attempts) | 문서와 다르게 | M |
| `judgment_record`(differs_from_live) | `core.core_scene_judgment`(scene_no∈{1,2}, timing∈{IMMEDIATE,AFTER_HELP}, live_label, differs_from_live) | 일부 | M |
| `result_job`(세션당 유니크) | `core.core_result_job`(job_type SESSION_RESULT/REPORT, idempotency_key, attempt_count≤3) | 구현됨(재시도 횟수 다름) | S |
| `life_tip` | `core_activity_result.tips_json` + `no_tip_reason` | 일부(출처가 승인 지원 항목) | S |
| `report`·`report_pdf_log` | `core_report`·`core_report_section`·`core_report_citation`·`core_report_activity` | 구현됨(구조 다름) | — |
| `push_subscription`·`push_dispatch` | `core_push_subscription`·`core_push_dispatch` | 구현됨 | — |
| `guardrail_event` | 없음 | 없음 | S |
| `usage_log` | 없음 | 없음 | S |
| `runtime_session`·`outbox`(activity) | `realtime_activity_session`·`realtime_outbox_event` | 구현됨(이름 다름) | — |

A는 진행 기록을 아동 서비스가 이벤트(`UTTERANCE_FINAL`·`HELP_GIVEN`·`JUDGMENT`…)로 보내 core에 쌓는 구조이고, 코드는 realtime에 연습 기록을 두고 종료 때 스냅숏(`SessionRecordCaptured`, `realtime_result_input_snapshot`)으로 넘긴다. 테이블을 A대로 옮기면 이 경계도 바뀐다(L, 11절).

---

## 9. 승인 콘텐츠·버전 (A §7.8 · B §1.4)

| 요구/진술 | 코드 상태 | 근거 | 규모 |
|---|---|---|---|
| `content/approved/`: 시작 안내(자유대화·연습)·마무리·종료 의도 확인·일상 질문 은행, 항목마다 ID·원문·버전 | 문서와 다르게 | 저장소 `content/` 없음. DB `core_approved_content`·`_version`(V1)에 `CLOSING`·`WAITING_FILLER`·`CONSENT_POLICY`만, 음성은 WAV(`ai-io.md` §5). 시작 안내 문장은 `DialogInstructions` 상수 | M |
| 일상 질문 은행 (FR-F03, B §4.1) | 없음 | — | S |
| `content/judge-points/`(J1~J7 질문·선택지·버전, 세션 고정) | 없음 | — | S |
| `content/guardrail/` | 없음 | — | S |
| `content/live-prompts/`, `content/analysis-prompts/` (B §1.4) | 문서와 다르게 | Java 상수(`DialogInstructions`), `prompts/result/*.v1.txt` | S |
| 세션 시작 때 버전 고정 | 일부 | 승인 문구 버전은 세션 패키지에 고정(`ai-io.md` §5). 판정 지점·가드레일 버전은 없음 | S |
| 배포 때 금지 표현 검사 통과 | [미확인] | — | S |

---

## 10. U6 결과 라벨·파이프라인 (A §7.5·§7.6 · C §4.4)

| 요구/진술 | 코드 상태 | 근거 | 규모 |
|---|---|---|---|
| 칩: 바로 / **되묻기 뒤** / 도움 뒤 + 목표 행동 표현(기본 '전달') (A §7.5, B §9.5) | 일부 | `result/SceneChipMapper`: `DONE_IMMEDIATE`("바로 전달")·`DONE_AFTER_HELP`("도움 뒤 전달")·`NEXT_TIME`·`ON_HOLD`·`NOT_DONE`+사유. `AFTER_REASK` 없음, 행동 표현 고정 '전달'. `core_scene_judgment` CHECK 제약도 두 값만 | S~M (마이그레이션 + 프런트 `SceneChip.tsx`) |
| `COPIED`는 `AFTER_HELP`로 보되 결과 문장에서 구분 (B §9.5) | 없음 | — | S |
| 장면 수: A는 기본 + 선택적 변형, C §4.4는 "없는 두 번째 장면을 실패로 표시"하지 말 것 | 문서와 다르게 | `SessionResultHandler` L122: 장면 1=FIRST/ORIGINAL, 장면 2=NEW_SCENARIO 고정 반복. `scene_no IN (1,2)` | M |
| 새 기록 사실(자연스러운 되묻기 해결·설명 제공·코치 전환/복귀·변형 생략 사유·종료/전사/재생 실패 구분) (C §4.4) | 없음 | — | M |
| 과거 기록 읽기 호환·버전 (C §4.4) | 없음 | — | M |
| 결과 상태 FULL/PARTIAL/INSUFFICIENT/FAILED (A §4.1, B §9.7) | 구현됨 | `result/ResultLevelCalculator` | — |
| 종료 확인 → 작업 등록 → PENDING → 워커 → 재시도 → FAILED → 다시 요청 (A §7.6) | 구현됨 | `ResultJob`·`JobRunnerLoop`·`ResultRetryService`, `core_result_job` | — |
| 씨앗 생성을 목표 후보 다음 단계로 (A §7.6) | 없음 | — | M (7절 뒤) |

---

## 11. 그 밖에 코드에 없거나 문서와 다른 것 (간단히)

- 서버 간 이벤트 세분화(A §5.4 `PARTICIPATION`·`REST_REQUESTED`·`UTTERANCE_FINAL`·`PLAYBACK`·`PRACTICE_PROGRESS`·`HELP_GIVEN`·`JUDGMENT`·`GUARDRAIL`·`USAGE`): 코드 `EventType`은 굵은 단위 5종. 문서와 다르게, L.
- 모듈 구조(A §2.5 core `ai`(LlmClient·JudgeClient·GuardrailClient), realtime `judge`·`guard`): 없음, S(패키지 신설).
- 휴식 요청(J1·J6, `REST_REQUESTED`, 공백 종료 사유 판정에 사용): 없음, M.
- 비용 추적 `usage_log`(GPT-Live `session.closed` 사용량·Luna 토큰·제브 호출 수, A §9): 없음, S.
- 공급자 요청 필드 감사 로그(A §7.1, NFR-04): 요청 필드는 타입으로 제한(`ProviderRequestFactory`, `ProviderRequestWhitelistIT`), 필드 이름 감사 로그는 없음. 일부, S.
- 판정·가드레일 요청에서 아이 이름을 '아이'로 바꿈(B §1.3): 판정·가드레일 호출 자체가 없음. U6는 '아이' 치환(`ai-io.md` §6.7). 일부.
- 응답 지연 측정 분리(판정 턴: 전사 대기·Luna 판정·지시 뒤 첫 음성, 가드레일 지연 따로, A §9): `log/LatencyRecorder`는 게이트 기준만. 일부, S.
- 기기 표시 `display{state}`(A §5.5) ↔ 코드 `SESSION_STATE`·`View`: 문서와 다르게(세부 [미확인]).
- 대본·씨앗의 아동 전사 겹침 5글자 검사(A §6.4·B §9.2): 없음, S.

---

## 문서끼리 부딪히는 곳

| # | 주제 | A/B 쪽 (인용) | C 쪽 (인용) | D·코드와의 관계 |
|---|---|---|---|---|
| 1 | **JEV 대상 범위** | A §7.1: 제브 = "Luna 생성물(장면 대본·주제 힌트·결과 문장·생활 팁·리포트)을 쓰기 전 검사, GPT-Live 출력 문장 검사, 승인 문구 배포 전 검사". A §7.4: "J7(Luna) → 가드레일(제브) → 통과분을 세션 계획에 고정". B §1.3 같은 내용 | C 머리말: "JEV는 Luna 출력물(연습 상황, 결과·리포트)을 검수하지 않으며". C §2 표: Luna U5a·U6 → "JEV 호출 하지 않음". C §3.1: "`LUNA_SCENARIO` 및 `LUNA_RESULT`를 JEV 입력 타입으로 추가하지 않는다" | D(기능명세서 v1.0 diff)는 A를 따라 "2차 제브(`GuardrailClient`). 데이터: guardrail_event". 코드는 U6 2차를 Luna `classify`로 함(C와 같음) |
| 2 | **GPT-Live 출력에 대한 JEV 시점·차단** | A §7.7 GPT-Live 출력 행: "출력 전사가 한 문장 끝날 때마다 … 결정 전 기준: 기록만 … 남은 재생을 멈추는(`output.stop`) 처리는 … 뒤 켠다". A §7.7: "GPT-Live 출력은 '검사 못 함'으로 기록하고 대화를 막지 않는다" | C §3.4-4: "`ALLOW`일 때만 다음으로 진행한다". C §3.4-7: "`BLOCK`, `REVIEW`, 타임아웃 … 시 해당 출력을 재생하지 않는다". C §3.4: "실패 시 자동 `ALLOW`는 금지", "미검수 음성을 먼저 보내면 안 된다" | D 안에서도 갈림: GPT-Live 기기 요구 v0.1 diff "결정 전 기준은 버퍼링하지 않고 출력 전사를 문장 단위로 검사·기록"(A 쪽) vs ADR-002/007/009·protocol.md·system-architecture.md diff "검사 실패·시간초과 … 폐기", "검사를 사후로 미루지 않는다"(C 쪽). 코드 게이트는 완결 응답을 모은 뒤 차단하는 구조(C 쪽) |
| 3 | **JEV 입출력 형식** | B §10.2: 검사 질문 + 분류 11개(`DIAGNOSIS`…`PERSONA`·`LEAK`·`PASS`), "`PASS`가 1순위이고 문턱 … 을 넘을 때만 통과". A §5.7: "받는 것: 고른 분류, 분류별 확률" | C §3.1: `decision` ∈ `ALLOW`/`BLOCK`/`REVIEW`, `categories` 7개(`CAUSE`·`EMOTION_CLAIM` 등, B의 `DEFICIT`·`MIND_READING`과 다름), `policyFlags`·`reasonCode`·`policyVersion` | 코드 1차 사전 범주는 C의 7개와 같음(`SeedDictionary.Category`) |
| 4 | **JEV 엔드포인트·모델을 아는가** | A §5.7: "`POST https://api.typesafe.ai/v1/systemone`. 모델 버전을 고정한다(예: `jev-1.13.0`)", "제브(Jev, TypeSafe AI)", A §15 기본 주소 | C §3.1: "실제 JEV 엔드포인트·모델·요청/응답·권한은 `[미확인]`". C §3.5: "URL/모델명을 추측하거나 … 하지 않는다" | — |
| 5 | **JEV에 보내는 맥락** | A §5.7: "보내는 것: 검사 질문, 분류 선택지 …, 검사할 문장" (맥락 없음) | C §3.1: `recentContext`(아동 발화 포함 최소 맥락), "최근 아동 발화는 … 최소 맥락 데이터로만" | — |
| 6 | **JEV 제한 시간·실패 처리** | A §13: "제브 가드레일 제한 시간 800ms (후보)". 생성물은 "1회 다시 요청 … 통과로 보지 않는다", GPT-Live 출력은 막지 않음 | C: 숫자 없음. 신규 발화는 타임아웃이면 재생 금지, P50/95/99 측정 뒤 정책 재설계 | — |
| 7 | **진행 중 판정(J1~J6) 존재 여부** | A §4.3·§7.3, B §5: Luna가 J2 "충분히 전달 / 일부 전달 / 도움 요청 …"을 골라 상태 기계가 `REASK`·`HELP_OFFER`·`FOCUS`를 정함. A §1.2: "진행 중 판정 모델 GPT-6-Luna … 아동 서비스가 직접 호출" | C §0-6: "첫 발화를 기계적으로 `functional/partial/none`으로 평가해 도움을 강제하지 않는다". C §0-2: Luna 용도는 U5a 생성·검토와 U6 분석뿐(진행 중 판정 언급 없음). C §4.1 "자연스러운 되묻기 … GPT-Live; 이를 곧바로 '미충족'으로 처리하거나 집중 연습 강제 금지" | D(ADR-005·decision-log·점검표·기능명세서 diff)는 A를 따라 "진행 중 판정은 GPT-6-Luna". 코드 `PracticeProtocol.judged()`는 둘 다와 다름(partial/none → 도움 강제, 항상 새 상황) |
| 8 | **되묻기를 누가 정하나** | B §5.3: "J2 일부 전달 → `D_REASK`" — 서버 상태 기계가 Luna 판정 뒤 지시 | C §4.2: GPT-Live가 즉흥으로 만든 "어느 색을 말하는 거야?"는 JEV 검수 대상(즉 GPT-Live가 스스로 되물음) | 코드 연습 모드는 승인 발화 외 GPT-Live 자유 발화를 모두 폐기 |
| 9 | **EvaluationProvider 처리** | D(기능명세서 v1.0 diff): "내부: Evaluation.Evaluate(GPT-6-Luna, `JudgeClient`)" — 판정 포트를 Luna로 연결 | C §0 "금지할 오해": 기존 `EvaluationProvider`는 "레거시 연습 반응 판정 포트", C §4.3 "새 연습 모드에서는 미연결 `EvaluationProvider`가 대화 진행을 막지 않도록 … 분리" | — |
| 10 | **진행 중 판정 시간 값** | A §13·B §5.1, D(ADR-005 §10.1): "2초 … 시간 초과·형식 오류는 재시도 없이 판정 보류" | C: 언급 없음 | 코드·`contracts/timing.md` L68: "5초 + 재시도 1회"(→ uncertain). D가 지운 옛 값이 계약 문서와 코드에 남아 있음 |
| 11 | **상황 자체 검토 형식** | A §7.3·B §5.2 J7: 선택지 `ANSWER_INCLUDED`·`EARLY_CUE`·`PRESSURE`·`PASS`, 확률·문턱. 대상 `intro`·`partner_opener`·`opportunity_line`·`reexplain`·`variant_intro`. 그 뒤 제브 | C §5.1: `decision` ∈ `APPROVE`/`REJECT`/`UNCERTAIN`, `reasons`(예 `ANSWER_LEAK`), `reviewVersion`, 6개 검토 항목(목표 적합성·연령·정답 누출·압박·자연스러움·변형 차이). JEV 없음 | 코드에는 둘 다 없음 |
| 12 | **변형 장면 생성 시점** | A §4.3 `VARIANT`: "대본은 기본 장면과 함께 미리 만들고 보존 판정을 통과한 것만 쓴다". B §9.3: "기본 장면 1개와 변형 장면 1개를 만든다" | C §4.1: "상황 확장 … 아이가 참여를 원하고 시간이 충분할 때만 … 상황 제안 — Luna U5a 변형 생성, 서버 제안/거절 처리"(진행 중 생성으로 읽힘) | 코드는 진행 중 `prepareNew()`로 C10 호출해 생성(C 쪽) |
| 13 | **지식 그래프에 무엇을 두나** | A §6.4: "아이마다 자기 그래프를 가진다", `ChildGraph {child_id}`, "그 아이의 자유대화에서 분석 AI가 만든 씨앗이 더해진다"(`origin: 'GENERATED'`) | C §0-4: Neo4j는 "승인된 공통 화용 지식 … 조회", "아동의 원문 대화 및 개인정보 저장소로 사용하지 않는다". C §6.3: "Neo4j에 … 아동/보호자 식별자, 미승인 추론을 적재하지 않는다" | A도 원문·세션 출처는 PostgreSQL에만 두지만 `child_id`와 AI 생성 씨앗(사람 승인 없음)은 Neo4j에 둠 → C의 "식별자·미승인 추론 적재 금지"와 충돌 |
| 14 | **그래프 사용처·키** | A §6.4: 씨앗 `seed:S1`~`S10`·`X1`~`X4`, 목표 후보 `skill_ref`, 장면 `skill_context`·변형 계획, 보존 판정, 씨앗 생성 | C §5.1·§6.2: `graphHints[{behaviorKey:"ACCEPT_STOP", …}]`, "무중의 행동 코드(`ACCEPT_STOP`, `ASK_TO_JOIN` …)와 PoC 분류의 명시적 매핑" — 행동 키 중심, 상황 생성 입력 확대만 | 코드 포트는 `findRelatedBehaviorKeys(behaviorKey)`(C 쪽 키 체계) |
| 15 | **목표 후보의 출처** | B §9.6: "미리 정한 목록에서 고르지 않는다", 4요소를 Luna가 생성, `skill_ref` 태그. A §6.4: "목표와 4요소는 분석 AI가 아이 기록에서 만들고 `core.goal_candidate`에 둔다" | C §5.2 `candidates`: "검증된 발화를 바탕으로 ACTIVE 행동 정의의 목표 후보 제안" | 코드(`core_goal_recommendation` → `core_behavior_definition` FK)는 C 쪽 |
| 16 | **U6 2차 금지 표현 검사 모델** | A §7.7: 결과 문장·리포트·생활 팁 "1차 → 2차(제브)" | C §5.2: `classify`는 "기존 U6 결과 문장 2차 금지 표현 분류 작업. 이는 JEV … 와 다른 경로", "U6의 Luna 결과·리포트는 JEV로 재검수하지 않으며" | 코드 = C |
| 17 | **분석 AI 설정 키** | A §11.3: `OPENAI_API_KEY`, `OPENAI_ANALYSIS_MODEL`·`OPENAI_JUDGE_MODEL`, `JEV_API_KEY` | C §9: "이미 사용 중인 `MUJOONG_TEXT_AI_*` 설정과 U6의 실제 Bean 연결 관계를 확인한다" | 코드는 `MUJOONG_TEXT_AI_*`(예약), realtime 공급자 키 `PROVIDER_API_KEY` |
| 18 | **포트 이름** | A §2.5·§9: `GuardrailClient`, `JudgeClient`, `LlmClient`, `KnowledgeAdapter` | C §3.1: "예시 이름은 `SemanticGuardrailProvider`", 기존 `KnowledgeGraphLookup` 확장 | 사소하지만 계약 이름을 하나로 정해야 함 |
| 19 | **코치 예시와 정답 금지** | A §4.3 `FOCUS`: "표현 예시 가능", B `D_FOCUS` | C §4.3: 기존 `CueProvider.Result(approved, containsAnswerSentence)`의 "전역 '정답 예시 금지'는 새 코치 역할과 충돌" — 역할별 정책 분리 | A·C는 같은 방향. 충돌은 코드(`StageOutputPolicy.cue`가 정답 포함이면 송신 금지)와 두 문서 사이 |
| 20 | **승인 문구 낭독 방식** | A §3.1: "승인 문구와 세션 고정 대본(기회 문장)도 GPT-Live가 원문을 낭독", 클립은 예외 | C §0-8: "서버 승인 고정 문구를 재생할 때에는 JEV 재검사를 생략"(방식 미지정) | 코드는 마무리·대기 문구를 WAV 클립으로 재생(A와 다름) |

---

## 규모 요약

| 영역 | S | M | L | 비고 |
|---|---|---|---|---|
| 1. GPT-Live 선 형식 | 7 | 5 | 0 | 실 API 확인이 먼저(선 형식 정답 [미확인]) |
| 2. 출력 게이트 | 5 | 3 | 1 | L = 게이트 안 JEV 동기 차단(C §3.4) |
| 3. JEV 클라이언트 | 6 | 3 | 0 | 엔드포인트·스키마 결정 대기 |
| 4. Luna 진행 중 판정 | 3 | 7 | 3 | 상태 기계 개편이 핵심 L |
| 5. Luna 분석 작업 | 5 | 6 | 2 | 대본 스키마·목표 후보 모델이 L |
| 6. 자체 검토 / J7 | 2 | 4 | 0 | |
| 7. 지식 그래프 | 1 | 3 | 1 | |
| 8. 데이터 모델 | 9 | 5 | 0 | 이벤트 경계 이전 시 11절 L 추가 |
| 9. 승인 콘텐츠 | 5 | 1 | 0 | |
| 10. U6 라벨·파이프라인 | 2 | 5 | 0 | |
| 11. 기타 | 6 | 1 | 1 | L = 서버 간 이벤트 세분화 |

(행 단위 대략치. "S~M"은 M으로 셈, 이미 구현됨·— 행은 뺌.)

선행 관계
1. **문서 결정(코드 아님)이 최우선**: 부딪힘 1·2·7·13·15(JEV 범위·차단 방식, 진행 중 판정 유무, 그래프 내용, 목표 후보 모델)를 정하지 않으면 2·3·4·5·7·10절의 설계가 갈린다. C와 A·B 사이 우선순위 진술이 어디에도 없다.
2. **1절(실 GPT-Live 선 형식 확인)** → 2절(게이트·판정 턴), 4절(지시 주입 `append` vs `correct`+`response.create`).
3. **5절 Luna 실어댑터(openai-java, 설정 키)** → 4절 J1~J6 어댑터, 6절 J7/자체 검토, 5절 나머지 작업.
4. **4절 연습 상태 기계 개편** → 8절(`judgment_live`·`help_given`·`scene`) → 10절(장면 수·칩·SessionResultHandler) — C §7도 P2 → U6 호환을 묶음.
5. **3절 JEV 포트·가짜 구현** → 2절 게이트 삽입(C P3). 실어댑터는 접근 승인 뒤.
6. **7절 그래프 적재·어댑터** → 5절 대본 `skill_context`·보존 판정·씨앗 생성 → 6절 관계·자리 허용 값 검사.
7. 9절(`content/` 버전 폴더)은 4절 판정 지점·3절 가드레일 버전 고정의 전제.
