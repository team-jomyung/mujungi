# U13 제브 게이트 — Developer Code Scan (집중 스캔)

- 기준 커밋: `unit/u13-jev-guardrail` = main `6a78536` + 문서 커밋 `f1b0980`(지시서). 코드는 main과 같다.
- 근거 문서: `docs/units/u13/brief.md`. 줄 번호는 이 커밋 기준이며 `~`는 대략의 위치다.
- 이 파일 말고 아무것도 쓰지 않았다(CodeKB `aidlc/spaces/default/codekb/mujungi/`는 낡았고 건드리지 않음). 앱 · 시험 · 외부 공급자는 실행하지 않았다(R-F1 · R-F3).

## Developer Code Scan Results

### Scan Coverage
- **Analyzed deeply** (스냅숏 경로 안):
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/gate/` (`OutputGate` · `GateDecision` · `SegmentBuffer` · `OutputGeneration` · `ApprovedPlayback` · `SeedDictionary` 앞부분)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionExecutor.java` (게이트 · 출력 조각 · 세대 · 지시 송신 · 연습 출력 구간, 3,964줄 중 해당 부분)
  - `session/JudgmentCoordinator.java`(Env · postResult 패턴) · `session/JudgmentSupport.java` · `session/SessionMailbox.java`(메시지 종류)
  - `judgment/`(`JudgeClient` · `UnavailableJudgeClient` · `LunaJudgeClient` · `JudgmentConfiguration` · `JudgmentLiveRecorder` · `JudgmentLiveRepository` · `JudgmentLiveDeletionParticipant`)
  - `luna/`(`LunaConfiguration` · `LunaKeyPresentCondition` · `LunaTransport` · `LunaHosts`)
  - `content/`(`LiveContentRegistry` · `LiveContentResources`) · `voice/PracticeSpeech.java`
  - `startup/`(`RealtimeStartupChecks` · `ProfileGuardEnvironmentPostProcessor` · `SchemaVersionCheck`) · `runtime/RealtimeRuntimeConfig.java`
  - `log/`(`TranscriptWriter` · `TranscriptRepository` · `AsyncRecorder` 일부) · `observability/`(`RealtimeMetrics` · `EventLogger`)
  - `config/RealtimeProperties.java`(guardrail 부분) · `src/main/resources/`(`application*.yml` · `guardrail/policy-v1.json` · `db/migration/realtime/V5__u11_judgment_turn.sql`)
  - 시험 구조: `src/test/java/kr/mujoong/realtime/{arch,testing,session/JudgmentExecutorSupport,observability,db,practice}` 일부 · `src/test/resources/application-test.yml` · `backend/coverage-scope/u11.txt`
  - `backend/test-support/`(파일 목록 · `MutableClock` · `ManualTimerDriver` · `LunaResponsesStub` 존재만 확인)
  - `backend/contract-model/`(`EventLog.Component` · `AttrKey` 중 Luna 항목 위치만)
  - `contracts/`(`ai-io.md` 10절 · `timing.md` 13절 · `deviations.md` U8 편차 · `schemas/guardrail-request.schema.json` · `guardrail-result.schema.json`)
- **Skimmed only** (얕게 본 곳, 스냅숏 밖이거나 목록 수준):
  - `backend/core-api`: 패키지 목록만. 기록 · 리포트가 `SessionRecordCaptured` 스냅숏(`ResultSnapshotBuilder`)으로 발화를 받는다는 점만 확인(`RecordQueryService` · `SessionRecordCapturedHandler` 존재).
  - `frontend/`(`src/{app,device,parent,shared}`) · `infra/`(`bin/render-env` · `infra-validate` · `ci/*` · `compose.*`) · `tools/`(`fake-provider` · `device-simulator`) · `scripts/`: `JEV_API_KEY` 문자열 검색으로 비밀 이름 목록 위치만 확인. 내용은 읽지 않았다.
  - `realtime-activity` 안의 `gateway/` · `recording/` · `practice/`(연습 확장 구현) · `dialog/` · `messaging/` · `voice/ProviderClient` 계열은 이름과 호출 관계만 봤다.

### 1. 게이트 구조와 제브 삽입 지점

**현재 흐름** (`SessionExecutor.java`)
1. 공급자 출력 조각(`onOutputChunk` ~2096): `OutputGeneration.onAudio/onTranscript`가 받을지 버릴지 정하고, 받은 조각은 `SegmentBuffer`에 쌓는다. 매 조각마다 `SEGMENT_QUIET` 타이머를 다시 건다(기본 800ms, 시험 50ms).
2. 조용 시간 만기(`onOutputQuiet` ~2156 → `OutputGeneration.onQuiet` → `SEAL_RECEIVING`) 또는 아이 입력 재개(`onInputTranscriptDelta` ~1982 → `sealAndGate(true)`)에서 `sealAndGate(movedOn)`(~2782)을 부른다.
3. `sealAndGate`: `segment.sealQuiet/sealEarly` → `latency.segmentConfirmed` → `transcripts.aiDone` → `gate.inspect(sealed)`(구조 · 시드 사전; 실패는 `discard`) → 연습이 출력을 쥐고 있으면(`practice.controlsOutput()`) `pendingPracticeSpeech.matches(transcript)`가 아니면 `GATE_PATTERN` 폐기(~2805-2809) → `gate.recheck(activeAndCurrent, recorderDegraded, reserve)` → `send()`.
4. `send`(~2831): `latency.gatePassed` → **`transcripts.aiFinal(sealed.transcript(), true, passAt)`**(이 한 줄이 보호자 화면 승인) → `OutputBundle`(PCM + `() -> outputGeneration.get() == generation` 세대 확인 콜백) → `target.sendOutput`.
5. `discard`(~2866): `metrics.gateDiscard(reason)` → `transcripts.aiFinal(text, false, ...)` → 연습 발화면 `PRACTICE_DISCARD`로 세대를 올리고 FAILED 사실을 확장에 알림 → `correctAfterDiscard`(고정 교정 문장 지시, 세대당 1회 · 연달아 2회 상한 `MAX_CORRECTIONS_IN_A_ROW`) → 대기 문구(`WAITING_FILLER`) 또는 `listeningAgain`.

**삽입 지점**: `sealAndGate`의 `inspect` 통과 직후, 연습 원문 일치 검사 다음, `recheck` 직전이다(~2810, 위 3번의 두 `if` 사이). 이유는 다음과 같다.
- `OutputGate`는 순수 동기 판정 클래스이고 DB · 세션 상태를 보지 않는다(`OutputGate.java` 클래스 주석). 제브는 비동기 HTTP(최대 800ms)라서 **`OutputGate` 안이 아니라 실행기 쪽에 단계로 얹어야** 한다. `OutputGate`는 `inspect`(동기) + `recheck`(동기)를 그대로 두고, 둘 사이를 실행기가 "제브 대기"로 이어 붙이는 모양이 가장 작다. 브리프의 "inspect → [제브] → recheck → send"와 맞는다.
- 비동기 결과는 U11 선례대로 `SessionMailbox` 메시지(`JudgmentArrived`처럼 `GuardrailArrived`)로 실행기 스레드에 돌려놓는다. 완료 콜백 스레드는 `mailbox.putSafety`만 부른다(`JudgmentCoordinator.postResult` → `env.postFromProvider`). 실행기 스레드에서는 어떤 HTTP도 기다리지 않는다.
- `recheck`(NOT_ACTIVE 판정의 유일한 자리 · 출력 레인 예약)는 **ALLOW가 돌아온 뒤에** 불러야 한다. 지금은 `sealed.generation() == outputGeneration.get()`을 봉인 직후에 보지만, 제브 대기 후에는 대기 중 바뀐 상태를 다시 봐야 의미가 있다.

**늦은 ALLOW 무효화의 기준 (중요)** — 세대 번호가 **둘**이다.
| 이름 | 위치 | 올리는 곳 | 무엇을 막는가 |
|---|---|---|---|
| 기기 프레임 세대 `outputGeneration`(`AtomicLong`) | `SessionExecutor.bumpGeneration()` ~3864, `teardown` ~3903 | 끼어들기 · 종료 의도 · 연습 `speak` · 연결 교체 · 끊김 등 | 기기로 나가는 PCM 프레임(`OutputBundle`의 세대 콜백) · `recheck`의 `activeAndCurrent` |
| 공급자 출력 세대 `outputGen`(`OutputGeneration`) | `bumpOutput` ~2141, `sendInstruction` ~2216 | 지시 송신 · 판정 턴 · 보류 해제 · 연습 폐기 · 끼어들기 · 종료 의도 | 공급자 조각(`SegmentBuffer`의 `outputGen` 열쇠) |

`sendInstruction`(SERVER_SEND · GATE_CORRECTION) · `bumpOutput(JUDGMENT_TURN | HOLD_RELEASED | PRACTICE_DISCARD)`는 **기기 프레임 세대를 올리지 않는다.** 그래서 지금의 `sealed.generation() == outputGeneration.get()` 검사만으로는 "제브 대기 중 서버가 다른 지시를 보내 응답 세대가 바뀐" 경우를 못 잡는다. 늦은 ALLOW는 다음을 **모두** 만족할 때만 유효해야 한다.
1. 요청 시점에 만든 검사표(ticket: `sealed.outputGen()` · `sealed.generation()` · 요청 id)와 도착한 결과의 id가 같다.
2. `outputGen.generation() == ticket.outputGen`(공급자 출력 세대 불변) **그리고** `outputGeneration.get() == ticket.deviceGen`(기기 프레임 세대 불변).
3. 상태 `ACTIVE`, `link`가 열려 있고 같은 연결, `terminated` 아님, `outputHeld()` 아님.
4. 연습 발화라면 `pendingPracticeSpeech`가 그 발화(correlationId)와 여전히 같다(`cancelSpeech` · `interruptPracticeSpeech`가 지운 뒤면 무효).
5. `judgment.isOpen()` 아님(판정 턴이 열리면 `bumpOutput(JUDGMENT_TURN)`으로 이미 `outputGen`이 올라가므로 2번이 잡는다).

**전사 ↔ PCM 일치 묶기**: `SegmentBuffer.Sealed`는 불변 레코드이고 전사 · PCM(`List.copyOf`로 불변 목록, 조각은 `audio.clone()` 사본)이 **한 `outputGen` 한 구간**에서 함께 만들어진다(`addAudio/addTranscript`가 `outputGen`이 다르면 `OTHER_GENERATION`으로 거절, `fromStart` · `complete`로 첫 조각부터 끊김까지 확인). 따라서 구조적 묶음은 이미 있다. 제브 단계는 **같은 `Sealed` 인스턴스를 검사표에 쥐고**, 후보 문장을 그 `transcript()`로, 송신을 그 `chunks()`로만 해야 한다(다시 봉인하거나 버퍼에서 다시 읽지 않는다). 주의: 이 전사는 공급자 모델이 낸 출력 전사이지 PCM을 독립적으로 받아 적은 것이 아니다. "같은 응답 · 같은 구간"을 넘어서 소리와 글이 실제로 같은지는 코드로 검증할 방법이 없다(브리프 AC2.1.5의 한계, 설계에서 명시할 것).

**대기 중 새 구간 · AI 행 충돌 (설계가 막아야 할 함정)**
- `TranscriptWriter.aiStart`는 열린 AI 행이 있으면 새 행을 만들지 않고 그 번호를 돌려준다(`TranscriptWriter` ~83-90). 지금은 `aiFinal`이 `send`/`discard`에서 구간 판정과 동시에 행을 닫는다. 제브 대기 동안 행을 열어 두면 대기 중 도착한 다음 조각이 **같은 AI 행 번호**를 재사용한다.
- 반대로 대기 중 행을 `FINAL · false`로 닫으면 늦은 ALLOW로 `approved=true`로 바꿀 수 없다. `TranscriptRepository.UPSERT`가 `where assembly_status = 'PARTIAL'`일 때만 덮기 때문이다(~38-49, 클래스 주석 "폐기된 AI 말이 뒤늦게 승인되지 않는다"). 이것은 보호자 숨김 쪽에는 안전한 성질이지만 제브 단계 설계에서는 제약이다.
- 따라서 제브 요청이 날아가 있는 동안 실행기는 **세션당 동시 검사 1건**으로 두고, 그동안 새 출력 조각을 `outputHeld()`처럼 버리든가(구간을 열지 않음) 대기시키든가를 정해야 한다. 현재 코드에는 이 상태 표현이 없다(`outputHeld()` ~1902는 WAITING_CHILD · 종료 확인 · 판정 · 연습 보류 네 조건뿐). 새 조건을 더하면 `watchHold`(~2237)가 풀림 시점에 `HOLD_RELEASED`로 세대를 올리니 상호작용을 설계서에 적을 것.
- 기기 쪽: 브리프는 "그동안 기기는 '생각 중'을 유지"라고 하는데, 현재 `discard()`는 `WAITING_FILLER` 재생 또는 `listeningAgain()`으로 간다(~2878-2889). 또 `thinking()`(~2745)과 `listeningAgain()`(~2755)은 `view == LISTENING` 조건과 `judgment.isOpen() || practiceThinking`로 THINKING을 유지한다. 제브 대기용 THINKING 유지 조건을 `listeningAgain`에 더해야 한다.

**아이 입력 재개와의 관계**: `onInputTranscriptDelta`는 열린 구간을 `sealAndGate(true)`로 확정한다. 이미 제브 대기 중인 구간은 `segment`에서 빠져 있으므로(봉인됨) 이 호출로는 영향이 없다. 아이가 말을 시작해도 출력 세대는 오르지 않는다(끼어들기 `onBargeIn`은 기기가 재생을 멈췄을 때만 `bumpGeneration` + `bumpOutput`). 즉 "제브 대기 중 아이가 말을 시작한 경우 늦은 ALLOW를 재생해도 되는가"는 현재 규칙으로는 재생된다. 브리프 FR6.5의 무효 조건(취소 · 중단 · 연결 해제 · 세대 변경)에는 아이 입력이 없으므로 **결정이 필요한 빈칸**이다(Gap G6).

### 2. 승인 원문과 생략 경로

- 두 군데에 서로 다른 일치 표현이 있다.
  1. **연습 출력 경로(U5b)**: `PracticeSpeech.matches(actual)` = `sameWithoutAsciiWhitespace(text, actual)`(`voice/PracticeSpeech.java`, 정규식 `\s+`만 제거, 그 밖 정규화 없음). 실행기는 `practice.controlsOutput()`이면 `pendingPracticeSpeech`가 있고 `matches`일 때만 통과시킨다(`SessionExecutor` ~2805-2809). 이 `pendingPracticeSpeech`는 `PracticeOutputPort.speak`(~1689)가 세운다. **제브 생략을 이 일치에 거는 것이 가장 직접적인 후크**다: 같은 `if`의 통과 가지에서 "원문 일치 → 제브 생략 후 `recheck`"로 진행.
  2. **세션 콘텐츠 레지스트리(U8)**: `LiveContentRegistry.matchApproved(sessionId, outputText)`(`content/LiveContentRegistry.java` ~197) → `MatchResult{MATCHED|NOT_MATCHED|UNKNOWN}`, `allowsGuardrailSkip()`는 MATCHED만 참. 승인 발화(`approvedSpeech`)와 등록된 대본 문장(`registerScript`)을 같은 `sameWithoutAsciiWhitespace`로 비교한다. 세션 계획은 `StartSessionHandler`가 `pin`(~292)하고 종료 때 `release`(~249 · ~267)한다.
- **갭**: `matchApproved`와 `registerScript`의 운영 호출자는 0개다(`grep`: `content/` 밖에서는 `StartSessionHandler`의 `pin`/`release`뿐). 또 `SessionExecutor.Services`에는 `LiveContentRegistry`가 없다(`JudgmentSupport`만 있음, `RealtimeRuntimeConfig.sessionServices` ~73-105). U13은 레지스트리(또는 좁은 포트)를 `Services`에 넣어야 한다. 그리고 `registerScript`는 U12가 부르므로 U13 시점에는 **대본 문장 일치는 항상 NOT_MATCHED**가 된다(승인 발화 `approvedSpeech`와 연습 `PracticeSpeech`만 일치 가능). 설계서에 "대본 경로는 U12가 등록한 뒤부터 유효"로 적을 것.
- 또한 자유대화의 승인 문구(`approvedContent`, 음성 파일 재생 `PlayApprovedContent`)는 GPT-Live 출력 구간이 아니라 기기 재생 명령이라 게이트를 지나지 않는다(`playApproved` → `sendControl`). 제브 생략 대상이 아니다.
- 일치 판단의 주의점: 두 경로 모두 정규화가 ASCII 공백 제거뿐이다. NBSP · 전각 공백 · 제로폭이 끼면 NOT_MATCHED(안전 쪽)이고 이것은 계약(`ai-io.md` 10.5)이 정한 대로다. 반대로 `OutputGate.inspect`의 시드 사전은 NFKC 등을 쓰는 다른 정규화(`SeedDictionary.normalize`)다. 두 정규화를 섞지 말 것(브리프 FR6.6 · AC2.2.x).
- 출처가 불확실할 때(`UNKNOWN`)는 생략 금지, 모델이 밝힌 출처 신뢰 금지다. 코드의 `pendingPracticeSpeech`는 서버가 지시를 보낸 쪽 상태이므로 "모델 주장"이 아니라 서버 기억이다. 이 구분을 설계서에 적을 것.

### 3. 외부 공급자 포트 · 어댑터 패턴 (선례)

| 선례 | 포트 | 구현 | 켜는 조건 | 기동 확인 |
|---|---|---|---|---|
| U11 판정 | `judgment/JudgeClient`(`available()` + `judge(...) -> CompletionStage<JudgeOutcome>`, 예외 없이 모든 실패를 보류 결과로 완료) | `LunaJudgeClient`(Luna 한 번 호출, 시한 = 호출자 마감과 `live-budget` 중 짧은 쪽, 재시도 없음) · `UnavailableJudgeClient`(`available()=false`) | `LunaKeyPresentCondition`(env `OPENAI_API_KEY` 비어 있지 않음 **그리고** `mujoong.luna.enabled=true`) → `RealtimeLunaClient` 빈 유무로 `JudgmentConfiguration.judgeClient` 선택 | `LunaConfiguration.lunaStartup` / `LunaStartup`(모드: `LunaHosts.modeFor(profiles)` STRICT/DEV/TEST) |
| U9 Luna 전송 | `LunaTransport.send(body, limit)` | JDK `HttpClient`(리다이렉트 없음 · 프록시 없음), 보내기 직전 `LunaHosts.check` 재확인, 키는 env에서 그때그때 읽음, 응답 바이트 상한, `orTimeout` | 위 조건 | 허용 호스트는 코드 상수(`LunaHosts.ALLOWED_HOSTS = {api.openai.com}`) |

- **제브용으로 따라 할 모양**: 새 패키지 `kr.mujoong.realtime.guardrail`(권장). `LunaArchTest.LUNA_FORBIDDEN_DEPENDENCIES`가 이미 `..luna..`가 `..guardrail..`에 의존하지 않도록 못 박아 두었고(AC2.4.1: Luna 경로는 제브를 부르지 않는다), 이 규칙은 `allowEmptyShould(false)`라 지금 `..guardrail..` 패키지가 없어도 통과한다. `kr.mujoong.realtime.guardrail`을 만들면 이 규칙이 비로소 의미를 갖는다. 반대로 `guardrail` → `luna`/`voice`/`judgment` 의존은 금지 규칙이 없으므로 의도적으로 막을 ArchUnit 규칙을 더하는 것을 검토(R-F2는 기존 규칙 약화 금지이지 추가 금지가 아님).
- `GuardrailClient`는 `JudgeClient`와 같은 모양(`available()` + 비동기 완료, 예외 없음)이 자연스럽다. 단 제브에는 "자동 ALLOW 없음"이 요구라서 `Unavailable` 쪽 결과가 ALLOW가 아니라 막힘(또는 비활성 모드일 때만 실검사 없음 + '대역'/'비활성' 표시)이어야 한다. `UnavailableJudgeClient`의 NO_PROVIDER 보류와 의미가 비슷하지만 **게이트에서는 기본값이 달라진다**(보류 = 이어 가기 vs 제브 막힘 = 재생 안 함). 이것을 같은 이름 규칙으로 혼동하지 않게 결과 타입을 `GuardrailVerdict`(ALLOW/BLOCK/REVIEW/ERROR 계열)로 따로 둘 것(ADR-006).
- 프로필별 규칙(`RealtimeStartupChecks.problems`, `startup/RealtimeStartupChecks.java`): 내부 토큰은 모든 프로필 필수. `OPENAI_API_KEY`는 **demo만** 필수. `OWNER_HASH_SECRET`은 demo · prod 필수. 공급자 URL은 `ProviderHosts.check`(demo · prod는 허용 호스트 목록, test는 루프백). 활성 프로필 `PUBLIC_PROFILES = {demo, prod}`. **제브 요구는 브리프가 "demo · prod"**이고 키 필수 조건이 OPENAI(demo만)와 다르다. 새 점검 항목 `JEV_API_KEY`(demo · prod 필수)를 `problems()`에 더하되 `Inputs` 레코드(`internalToken, openAiApiKey, ownerHashSecret, providerUrl, activeProfiles`)에 필드를 추가하면 이 생성자를 쓰는 모든 시험(`U8RealtimeStartupChecksTest` · `RealtimeStartupChecksTest` · `OpenAiKeyStartupChecksTest` 등)이 컴파일 영향을 받는다. 오버로드를 유지하는 패턴이 이미 있으니 따를 것. "제브 실검사가 꺼져 있으면"에 해당하는 설정 키(예: 스위치 `mujoong.guardrail.jev.enabled`)는 아직 없다. Luna의 `mujoong.luna.enabled`와 같은 이름 규칙으로 정해야 한다(설계 결정).
- 프로필 파일: `application-demo.yml` · `application-prod.yml`이 `mujoong.provider.url`(GPT-Live)만 가진다. 제브 주소 · 모델 기본값은 이 파일에 두는 것이 선례(`application-dev.yml` 주석: 주소는 dev 파일에 두지 않음, J141). 브리프의 "도메인 값을 코드 · 문서에 쓰지 않는다 / R-F3"는 `LunaHosts.ALLOWED_HOSTS` 같은 호스트 상수 코드와 충돌한다: Luna는 `api.openai.com`을 **코드 상수**로 갖고 계약 문서(`ai-io.md` 10.9)와 시험이 대조한다. 제브 허용 호스트를 같은 방식으로 코드에 두면 브리프가 금지한 "코드에 도메인"과 어긋난다. 기존 Luna · GPT-Live는 공개 공급자 호스트이고 R-F3의 "실제 도메인"은 이쪽 서비스 도메인으로 읽힌 선례가 있다. 어느 쪽인지 사람이 정해야 한다(Gap G9).
- **설정 키**: `mujoong.timing.guardrail.jev-budget: 800ms`는 `application.yml`(timing.guardrail 블록), `contracts/timing.md` 13절 표, `RealtimeProperties.GuardrailTiming(Duration jevBudget)`에 이미 있다(U8). `frontend/.../timing-defaults.json`에는 넣지 않는다(편차 U8-c). 새 시간 값을 더하면 R-M5의 세 곳(timing.md · `application.yml` · 설정 레코드)을 같은 커밋에서 고친다. `application-test.yml`에는 `guardrail` 키가 없어 기본값 800ms를 쓴다(주의: 시험에서 800ms를 실시간으로 기다리지 말고 대역이 즉시/수동으로 완료하게 할 것).
- **비밀 이름**: `JEV_API_KEY`는 U8이 R-M9 목록 전부에 이미 넣었다(`README.md` 113 · `infra/bin/log-hygiene-check` 58 · `infra/bin/render-env` 66 · `infra-validate` 156-164(realtime만) · `ci.yml` 249 · `infra/ci/image-checks.sh` 119 · `gen-env.sh` 143 · `test-host-tools.sh` · `test-infra-validate.sh`). `render-env`에서 OPTIONAL이다(비면 빈 변수). demo에서 필수로 만들면 `render-env`의 OPTIONAL 분류와 어긋나지는 않지만(앱 기동 검사가 필수를 강제) 운영 절차(RUNBOOK 단계 4 R-F15 기록)와 `infra/ci/gen-env.sh`(검증 스택은 빈 값)가 **demo 프로필 + 검증 스택**에서 어떻게 통과하는지 확인이 필요하다(아래 Gap G4). 새 비밀 이름은 없다(R-M9 새 작업 없음).

### 4. 영속화 관례 (새 표 `guardrail_event`, realtime V6)

- 마이그레이션 위치와 규칙: `src/main/resources/db/migration/realtime/V1…V5`, 마지막은 `V5__u11_judgment_turn.sql`(표 하나 + 색인 하나, 기존 표 제약 강화 없음, 시각은 앱 `MujoongClock`을 바인딩하고 DB 기본값 없음, `mujoong_owner`가 적용 · `realtime_app`은 기본 권한으로 DML만). 새 파일은 `V6__u13_jev_guardrail.sql`.
- `V5`의 구조를 따를 칸: `id uuid`(= 검사 요청 id로 멱등 덮어쓰기), `guardian_id uuid NOT NULL`(삭제 참여자 기준), `session_id uuid NOT NULL` + FK `ON DELETE RESTRICT`, 검사 제약(CHECK)으로 enum 문자열 고정, `created_at timestamptz NOT NULL`, `guardian_id` 색인. **전사 원문 칸은 두지 않는다**(R-F14). V5의 `held_output_transcript text`는 전사를 담는 선례이지만 제브 기록의 선례로 쓰면 안 된다(브리프 FR6.8 · R-F14).
- 쓰기 경로: `JudgmentLiveRecorder`(`@Component` + `@SingleInstanceAssumption(si = "SI-10", ...)`)가 전사 `AsyncRecorder`와 틱 · 정리에 얹혀 돈다(`host.onTick(this::tick)`, `host.onDrain(this::drain)`). `put`은 칸에 넣기만 하고 DB를 기다리지 않으며, 같은 id는 덮어쓰고, 일시 실패는 다음 틱 재시도, FK · CHECK 위반(23503 · 23514)은 버림, 삭제 중인 보호자는 `block`으로 거절. `MAX_PENDING = 500`. `JudgmentLiveRepository.upsert`는 `Transactions.requireActive`로 호출자 트랜잭션 안에서만 쓴다(`insert … on conflict (id) do update`). 이 클래스들은 **`judgment` 패키지에 있어** 재사용이 아니라 같은 모양의 새 클래스를 `guardrail` 패키지에 두게 된다(복붙 비용 · 공통화 여부는 설계 결정, 아래 부채 신호 참조).
- **`SingleInstanceMarkerTest` 목록(필수 갱신)**: SI-10 표식 지도에 `Set.of(AsyncRecorder.class, JudgmentLiveRecorder.class)`가 있다(`arch/SingleInstanceMarkerTest` ~123). 새 기록기 클래스가 미커밋 칸 목록을 가지면 같은 표식을 달고 이 지도에 더해야 시험이 통과한다(표식을 더하거나 빼면 지도도 함께 고치는 규칙, J319). 안 더하면 "표식이 없는데 메모리 대기열" 점검에서 걸린다.
- **삭제 참여자**: `RealtimeDeletionParticipant` 순서 상수: `ORDER_VOICE=100`, `ORDER_PRACTICE=150`, (판정 180은 `JudgmentLiveDeletionParticipant.ORDER_JUDGMENT`로 자기 클래스에만 있고 인터페이스에 없음), `ORDER_SESSION=200`, `ORDER_MESSAGING=900`. `guardrail_event`가 세션 FK를 가지면 **세션 삭제(200) 앞**에 지워야 하므로 판정(180)과 같은 구간이다(예: 185). `DeletionRequestedHandler.handle`(~125-145)은 `switch (participant.order())`로 `ORDER_VOICE/PRACTICE/SESSION`만 건수를 모으고 `default`는 건수에 안 넣는다. 그래서 새 참여자를 더해도 `DeletionCompleted.Counts(sessions, transcripts, audioFiles, practiceRecords)`는 그대로이고, 건수에 넣으려면 `contract-model`의 `DeletionCompleted.Counts` · 이벤트 스키마(`events.asyncapi.yaml` · `event-payloads.schema.json`) 변경이 따라온다(계약 변경 → R-M10, core 소비자 영향). 판정 기록 선례(U11)는 건수에 **넣지 않았다**(`default` 가지). 같은 선택이 가장 작다. 순서 시험: `RealtimeDeletionParticipantOrderTest`가 순서를 단언한다(새 상수/클래스에 맞춰 갱신).
- **스키마 · 마이그레이션 시험이 단언하는 곳(V6에 맞춰 고칠 것)**:
  - `db/RealtimeSchemaIT`: 표 목록 주석 "V1 일곱 + V2 세 + V3 한 + V4 다섯 + V5 한 표"(`pg_tables` 이름 순 목록 단언), 이력 버전 목록 `[…, ("5","SQL",true)]`, `max(version) == "5"`.
  - `practice/PracticeMigrationIT`: `new SchemaVersionCheck(jdbc).verify()` == "5". `practice/PracticeMigrationRegistrationTest`: `V5__u11_judgment_turn.sql` 존재, `SchemaVersionCheck.expectedVersion() == "5"`.
  - `db/JudgmentLiveSchemaIT`(표 형식 선례, 새 표 전용 IT를 같은 모양으로) · `testing/RealtimeDb`(시험 사이 비우는 표 목록, "판정 기록은 세션 행을 가리키므로 먼저 비운다": 새 표를 같은 위치에 더해야 `session` 삭제가 FK에 안 막힘).
  - `SchemaVersionCheck.expectedVersion()`은 클래스패스의 마이그레이션 파일 최대 버전을 읽으므로 V6 파일만 더하면 앱 쪽은 자동으로 "6"을 기대한다. 기존 시험의 하드코딩 "5"가 깨지는 것이 정상이며 그것을 바꾸는 것은 약화가 아니다(R-F2의 "기존 단언 낮추기"와 구별해 보고서에 근거를 적을 것).
  - 팀 문서: `team.md`가 "다음 번호는 core V8 · realtime V5"라고 적었으나 실제 realtime 마지막은 V5이므로 **다음은 V6**이다(`team.md` 줄이 낡음 → 브리프가 맞다. 문서 수정은 이 스캔 범위 밖, 후속 단계에서 보고).
  - `infra/migration-approvals.txt`: "기존 표에 제약을 더하는 문장"만 승인 대상(R-M3). 새 표만 만들면 불필요.
  - Flyway는 `outOfOrder` 꺼짐 → U12(V7)보다 먼저 병합되어야 한다(브리프 순서와 같음).

### 5. 이벤트 로그 · 지표 · 보호자 쪽 경로

- **로그**: `EventLogger`(`observability/`)가 유일한 입구. 계약의 타입 있는 `EventLog`(사건 코드별 허용 키, 자유 문자열 없음)를 쓰고 허용 표 밖 키는 test 프로필에서 예외, 그 밖은 버리고 `mujoong_log_invalid_attr_total` + APP_ERROR. 운영 코드는 `LoggerFactory` 직접 사용 금지(팀 규칙). 컴포넌트는 `contract-model`의 `EventLog.Component` 열거형(`OUTPUT_GATE(App.REALTIME, "OutputGate")` 이미 있음, 게이트 오류는 `events.appError(EventLog.Component.OUTPUT_GATE, e)`로 남김, `SessionExecutor` ~2822). 새 사건(예: `JEV_CALL`)이나 새 `AttrKey`를 만들면 `contract-model`(`EventCode` · `AttrKey`)과 `infra/bin/log-hygiene-check` 허용 표를 같은 커밋에서 맞춰야 하고(`ai-io.md` 10.8, `infra/ci/test-host-tools.sh`가 대조), 그러면 `contract-model` 변경이 core-api에도 번진다. **최소 변경안**은 새 사건 없이 `appError(Component.OUTPUT_GATE, "<사유 코드 문자열>", Level)` 사유 코드만 쓰는 것이다(U11이 `OutputAckMissing` 등으로 한 방식).
- **지표**: `RealtimeMetrics.gateDiscard(reason)` → `mujoong.gate.discard{reason}`(이미 있음, 사유 열거값만). 새 제브 지표(호출 수, P50/P95/P99, 차단 · 시간 초과 비율)는 `RealtimeMetrics`에 이름 상수 + 메서드를 더하고 `application.yml`의 `management.metrics.distribution.slo` 히스토그램 버킷에 새 이름을 더해야 분포가 나온다(`mujoong.relay.in` 등 선례). **브리프는 "지표 이름이 `contracts/` 표에 있어야 한다(R-M10)"고 하지만 `contracts/` 안에는 지표 이름 표가 없다**(`grep`: `mujoong.gate.discard`는 코드와 261008 기록의 `observability-design.md` · `monitoring-design.md`에만 있음). 기존 지표도 `contracts/`에 없으므로 R-M10을 문자 그대로 적용하면 대응 위치가 없다 → Gap G5.
- **보호자 쪽 경로 (숨김의 실체)**: 12 진행 조회 · 27 기록 · PDF 모두 `realtime_transcript.approved_for_display`로 걸러진다.
  - 12: `TranscriptRepository.SELECT_VISIBLE_AFTER`(~53-56): `speaker='CHILD' or (speaker='AI' and assembly_status='FINAL' and approved_for_display)`.
  - 기록 · 리포트: `ResultSnapshotBuilder`(~110-116)가 FINAL이고 `CHILD`이거나 `approvedForDisplay`인 행만 `utterances`로 담아 `SessionRecordCaptured`를 만든다. core는 이 스냅숏만 쓴다(core의 `RecordQueryService` · `SessionRecordCapturedHandler`가 소비자, 스캔은 얕게).
  - 따라서 **제브 BLOCK은 `aiFinal(..., false, ...)`로 닫으면 보호자 화면 · 기록 · PDF에서 이미 빠진다.** 새 필터를 core나 프런트에 더할 필요는 없다. 필요한 것은 (a) `approved=true`로 바뀌는 시점을 `send`에서 **제브 ALLOW 이후**로 미루는 것, (b) 아래 4)의 PARTIAL 유지 제약이다.
  - 주의 1: 폐기된 AI 구간의 텍스트는 `discard`가 `aiFinal(sealed.transcript(), false, now)`로 **DB `text` 칸에 원문을 그대로 쓴다**(`approved=false`로만 가림). `ERASE_BY_GUARDIAN`이 삭제 때 비운다. 제브가 막은 문장 원문이 DB에 남는 것은 R-F14의 "로그에 남기지 않는다"와는 다른 층(보호자 데이터 저장소)이지만, `guardrail_event`에 전사를 남기지 않는 결정과의 일관성 문제로 설계에서 확인할 만하다(Gap G8).
  - 주의 2: "12 중계는 ALLOW 뒤 재생이 시작된 구간부터" → 지금도 `aiFinal(true)`는 `send` 안(기기로 보내기 직전)에서 일어나므로 같은 의미로 맞춘다. 단 재생 **시작**(기기 `PlaybackMark STARTED`)이 아니라 서버가 기기로 보내는 시점이 `approved=true` 시점이다. 브리프 문구와 정확히 같은지는 요구사항에서 확인.
- `AsyncRecorder`는 칸 수 상한과 `isDegraded`(지연 상태)가 있고, 지연이면 게이트가 `RECORDER_DEGRADED`로 새 AI 구간을 폐기한다(`recheck`). 제브 대기 후에도 `recheck`가 이 점검을 다시 하므로 순서가 보존된다.

### 6. 시험 인프라 · ArchUnit · 커버리지

- **실행기 시험 하네스**: `testing/SessionHarness`(필드 `judgment`(`JudgmentSupport`)를 넣어 `SessionExecutor.Services`를 만든다, `new OutputGate(SeedDictionary.loadDefault())` 사용), `FakeProviderPort`, `FakeDeviceLink`, `FakeJudgeClient`(요청마다 약속(`CompletableFuture`)을 만들고 시험이 손으로 푼다 = 제브 대역의 모범), `ManualLauncher`, `ManualTime`, `InMemoryTransitions`, `TestEventLoggers`, `RecordingPracticeExtension`. `test-support`의 `MutableClock` · `ManualTimerDriver`로 시간을 손으로 민다. `JudgmentExecutorSupport`(`session` 시험 패키지)가 하네스 + 판정기를 묶는 선례이므로 `GuardrailExecutorSupport`를 같은 모양으로 두는 것이 자연스럽다.
- `SessionExecutor.Services` 생성자가 **네 개의 오버로드 + 정식 생성자**다(`judgment` 없음 / 정산 없음 / 확장 없음 모양, ~170-205). 필드를 하나 더하면 오버로드 사슬 수정이 필요하고 시험 코드(`SessionHarness` 등)가 영향을 받는다. 제브 장치를 `JudgmentSupport`처럼 `GuardrailSupport` 기록으로 묶어 `NONE` 기본값을 두면 기존 시험이 그대로 돈다(U11 선례). `NONE`이 "검사 없음"이면 자동 ALLOW가 되어 **실검사 모드의 자동 ALLOW 금지(FR6.4)**와 충돌할 수 있으므로 `NONE`의 의미(= 대역 · 비활성 '표시'가 붙는 비실검사 모드)를 설계서에 명시할 것.
- **`segment-quiet` 흔들림 역사**: `application-test.yml`의 `mujoong.gate.segment-quiet: 50ms`. 가짜 공급자는 출력 전사를 첫 음성보다 먼저 보내는데 그 틈이 50ms보다 길면 CPU가 찬 CI에서 음성 없는 전사 구간이 불완전로 폐기된다. 해결 선례: 해당 IT가 `Map.of("mujoong.gate.segment-quiet", "800ms")`로 운영값을 쓴다(`SessionVoiceU3IT` ~207, `RealtimeMetricsScrapeIT` ~84 — "3차 리뷰 3"). 제브 단계가 얹히면 (a) 가짜 공급자 → 제브 대역 사이에 비동기 완료 대기가 하나 더 생기고 (b) 통합 시험이 "ALLOW 뒤 재생 시작"을 기다리는 시간이 길어진다. **제브 연결 IT를 새로 쓸 때 처음부터 `segment-quiet`를 운영값으로 두고**, 제브 대역은 즉시 완료하거나 시험이 수동으로 푸는 `CompletableFuture`로 만들어 실제 시간 800ms를 기다리지 않게 할 것. 단위 시험(`ManualTime`)에서는 800ms 경계(정확히 800.000ms에서 시간 초과 · 800ms 직전 ALLOW 허용)를 시계를 밀어 검증.
- **ArchUnit**: `arch/RealtimeArchTest`(`NO_CORE_REFERENCE`(R-F5) · `NO_TEST_CODE_IN_PRODUCTION`(R-F8) · `NO_SYSTEM_TIME`(R-F6) · `PROVIDER_CLIENT_ONLY_IN_VOICE` · `PROVIDER_REQUESTS_ONLY_FROM_THE_FACTORY` 등), `arch/LunaArchTest`(@Tag u9), `arch/U8LiveContentArchTest`(레지스트리는 로그 의존 0), `arch/TimeLiteralScanTest`(숫자 시간 값 금지, 허용 목록은 근거와 함께), `judgment/JudgmentPackageArchTest`(@Tag u11). 제브 어댑터가 HTTP를 쓰면 `LunaArchTest.LUNA_NO_WEBSOCKET` 같은 모양으로 새 패키지 규칙(표준 출력 금지 · 로그 의존 금지 · 키 보관 금지)을 같은 `allowEmptyShould(false)` 패턴으로 더하는 것이 관례다. `PROVIDER_REQUESTS_ONLY_FROM_THE_FACTORY`는 GPT-Live 요청 규칙이라 제브 요청과는 별개다.
- **태그 · 범위 파일**: 유닛 태그 `@Tag("u11")` 같은 문자열을 시험 클래스에 붙이고(`@Tag("u9")` Luna 등), 범위 파일은 `backend/coverage-scope/u11.txt`처럼 `-PcoverageScope=<유닛>` 용 목록이다. 줄마다 저장소 루트 기준 운영 소스 경로 하나, 앞 주석에 "정직하게 적은 목록"이라고 쓴다. `u13.txt`를 새로 만들어야 하고(브리프의 `backend/coverage-scope/u13.txt`) 현재는 없다(디렉터리 목록: u1 u11 u14 u15 u2 u3 u4a u4b u5a u5b-core u5b u6 u7 u8 u9). u11.txt는 `SessionExecutor` 자체를 목록에 **넣지 않고** "앞 유닛 코드가 대부분이라 u11 태그 시험만으로 재면 줄이 낮다"고 사유를 적었다 → u13도 `SessionExecutor`를 목록에 넣으면 같은 문제가 생기므로 같은 방식으로 정직하게 처리할 것. CI가 실제로 막는 범위는 U5b · U7뿐(`team.md`)이라 u13 범위 80%는 로컬 · 검토 근거. 모듈 전체 줄 80%(JaCoCo `realtime-activity`)는 CI가 막는다.
- **스키마 계약 대조 시험**: 이 스캔은 `ConfigKeyContractTest` · `SeedDictionaryFileTest` · `LiveContentRegistryTest` · `U8RealtimeStartupChecksTest`의 존재만 확인했다. 제브 요청/결과 스키마(`contracts/schemas/guardrail-*.schema.json`)를 코드와 대조하는 시험은 realtime에 **아직 없다**(검색: `guardrail-request.schema.json` 참조는 `LunaHostsTest` 한 줄뿐, "스키마 파일이 클래스패스에 있다"류 확인으로 보임). 어댑터 변환의 형식 가정 시험이 이 대조 역할을 겸하게 될 것.

### 7. contracts/ 중 제브 관련

- `contracts/schemas/guardrail-request.schema.json`: 필수 `source`(상수 `GPT_LIVE_GENERATED`), `mode`(`FREE_TALK` · `PRACTICE_PEER` · `PRACTICE_COACH`), `candidate`(1~4000자), `policyVersion`(1~80자); 선택 `recentContext`(최대 3개, `speaker` ∈ `CHILD · PARTNER · COACH · MUJOONG`, `text` 1~2000자). `additionalProperties: false`. 식별자 · 키 속성 없음.
- `guardrail-result.schema.json`: 필수 `decision`(ALLOW/BLOCK/REVIEW) · `categories`(7개 enum 배열, 중복 없음) · `reasonCode`(`^[A-Z][A-Z0-9_]{0,79}$`) · `policyVersion`; 선택 `policyFlags`(`^[A-Z][A-Z0-9_]*$`) · `reviewNeeded`. 설명에 "BLOCK · REVIEW · 시간 초과 · 연결 실패 · 잘못된 JSON · 정책 판 불일치는 송신하지 않는다 / 실검사 모드에서 자동 ALLOW는 없다".
- `ai-io.md` 10절: 10.1(스키마 표), 10.2(금지 필드 · R-F15 · 키를 넣기 전 RUNBOOK 단계 4 기록), 10.3(재시도 분류: 제브는 표에 **없음** → 제브 재시도 정책은 계약에 미정, 브리프는 "제한 시간 800ms, 재시도 언급 없음 · 다시 지시 1회는 게이트 수준"), 10.4(판 리소스: `guardrail/policy-v1.json`), 10.5(원문 일치 · 생략), 10.7(비밀 이름), 10.8(`LUNA_CALL` 로그 표, 제브용 로그 표는 없음).
- `timing.md` 13절: `mujoong.timing.guardrail.jev-budget = 800ms` ("넘기면 막힘, 자동 ALLOW 없음, U13").
- `deviations.md`: `(U8-a)` 스키마 9종 + 규칙, `(U8-c)` 시간 키 7개. 제브 실호출 확정 시 `(U13-a)`부터 쓰기(브리프). `errors.md`: 제브 관련 HTTP 오류 코드 없음(`ai-io.md` 10.3 "새 HTTP 오류 code는 없다").
- 시스템 프롬프트 · 범주 · 모드 리소스는 이미 있다: `realtime-activity/src/main/resources/guardrail/policy-v1.json`(`version = "jev-guardrail-v1"`, `systemPrompt`, `categories` 7개, `modes` 3개), 기동 때 `LiveContentResources`가 적재 · 검증하고 `LiveContentRegistry.pin`이 세션에 판 번호를 고정한다(`SessionContentPlan.guardrailPolicyVersion()`). U13은 이 리소스를 소비하면 된다(새 리소스 불필요; 브리프 1번 "버전 리소스로 두고 세션 동안 고정"은 U8이 이미 해 둠).

### Packages Found
- `kr.mujoong.realtime.gate` — 기능 패키지 — Java 21 — 출력 구간 버퍼 · 세대 · 시드 사전 · 동기 게이트 판정
- `kr.mujoong.realtime.session` — 기능 패키지 — Java 21 — 세션 실행기(3,964줄) · 편지함 · 타이머 · 판정 협력 객체
- `kr.mujoong.realtime.judgment` — 기능 패키지 — Java 21 — U11 판정 포트 · Luna 어댑터 · 기록기(제브가 따를 선례)
- `kr.mujoong.realtime.luna` — 기능 패키지 — Java 21 — U9 Luna HTTP 호출부(제브와 섞이지 않게 ArchUnit으로 분리)
- `kr.mujoong.realtime.content` — 기능 패키지 — Java 21 — U8 버전 리소스 · 세션 콘텐츠 계획 · 원문 일치
- `kr.mujoong.realtime.log` — 기능 패키지 — Java 21 — 전사 · 지연 · 세션 틈 비동기 기록기(보호자 화면의 승인 판정)
- `kr.mujoong.realtime.startup` — 기능 패키지 — Java 21 — S1b 기동 점검 · 프로필 가드(제브 프로필 점검의 자리)
- (신설 후보) `kr.mujoong.realtime.guardrail` — 새 패키지 — `GuardrailClient` · 어댑터 · 대역 · 기록기 · 저장소 · 삭제 참여자
- `backend/contract-model` — 공용 계약 모듈 — Java — `EventLog` · `AttrKey` · `ErrorCode` · 이벤트 DTO(제브 호출부는 두지 않음, 팀 규칙)
- `backend/test-support` — 시험 지원 — Java — `MutableClock` · `ManualTimerDriver` · `FakeProviderWsServer` · `LunaResponsesStub`
- 얕게: `backend/core-api` · `frontend` · `infra` · `tools` · `scripts`

### Build System
- **Type**: Gradle 8.14(Java 21 · Spring Boot 4.1), 멀티 모듈 `contract-model` · `core-api` · `realtime-activity` · `test-support`, 컨벤션 플러그인 `build-logic`의 `mujoong.java-conventions`(`-Xlint:all,…`, `-Werror` 없음, JaCoCo).
- **Config Files**: `backend/build.gradle.kts` · `settings.gradle.kts` · `gradle.properties` · `build-logic/` · `backend/coverage-scope/*.txt`(`-PcoverageScope`)
- **Build Dependencies**: `realtime-activity` → `contract-model`(+ 시험은 `test-support`). `core-api`와는 코드 참조 금지(R-F5, `RealtimeArchTest.NO_CORE_REFERENCE`).
- 실행은 하지 않았다(JDK 21 설치 · Docker 필요, R-F1).

### APIs Discovered
- 제브 공급자(외부): 브리프상 `POST …/v1/systemone`(주소 · 모델은 설정 기본값, 코드 · 문서에 도메인 쓰지 않음). 응답 형식은 **아직 모른다**(브리프 "첫 작업: 위험 확인"). 어댑터 한 곳에서 내부 형식(`guardrail-result.schema.json`)으로 변환.
- realtime 내부: `SessionMailbox` 메시지 종류(`JudgmentArrived` · `JudgmentInstructionFailed` 등 약 20종, `SessionMailbox.java` ~30-170)가 비동기 결과 입구. 제브 결과용 새 메시지가 필요.
- 기존 HTTP: realtime 내부 API `SessionInternalController`(12 진행 조회 등)는 얕게만. 제브가 새 HTTP 엔드포인트를 더할 필요는 없다.

### Frameworks & Libraries
- Spring Boot 4.1 · Java 21(가상 스레드 `spring.threads.virtual.enabled`) · Spring JDBC `NamedParameterJdbcTemplate`(저장소 55파일 대 JPA 7파일: `team.md`) · Flyway(앱은 읽기만, `migrate` 컨테이너가 적용) · JUnit 5 · AssertJ · Mockito · ArchUnit · Testcontainers PostgreSQL · JaCoCo · Micrometer. HTTP 클라이언트: JDK `java.net.http.HttpClient`(Luna 선례). JSON: `kr.mujoong.contract.json.JsonCodec`(Jackson은 이 한 곳, J139; 편차 없이 쓸 것).

### Test Coverage
- **Test Directories**: `backend/realtime-activity/src/test/java/kr/mujoong/realtime/{arch,common,config,content,db,dialog,gate,gateway,judgment,log,luna,messaging,observability,practice,recording,session,startup,testing,voice,web}` · `src/test/resources/application-test.yml` · `backend/test-support/src/{main,test}`
- **Test Frameworks**: JUnit 5 · AssertJ · Mockito · ArchUnit · Testcontainers(`-Pmujoong.test.db=docker`, `@Tag("db")`).
- **Coverage Config**: 있음 — JaCoCo 모듈 줄 80%(CI) + `backend/coverage-scope/<유닛>.txt`. `u13.txt`는 없음(새로 만들어야 함).
- 게이트 쪽 기존 시험: `gate/{OutputGateTest, OutputGateGenerationTest, OutputGenerationTest, SegmentBufferTest, SeedDictionary*}`, 세션 쪽 `session/{GenerationSendExecutorTest, JudgmentTurnExecutorTest, JudgmentFaultExecutorTest, SessionVoiceU3Test, SessionVoiceU3IT, SessionExecutor*Test}`. 새 제브 단계는 `GenerationSendExecutorTest` · `JudgmentExecutorSupport` 모양을 따르면 된다.

### Code Quality Indicators
- **Linting**: 백엔드는 `-Xlint:all,-processing,-serial,-this-escape,-try`만(경고가 빌드를 안 막음), ArchUnit이 계층 · 의존 경계를 강제. Java 포매터 · 줄 길이 검사 없음(`team.md` Code Style, `printWidth 100`은 실제 관례가 아님).
- **CI/CD**: `.github/workflows/ci.yml`(`ci-gate` 필수 검사, 경로 필터), `infra-validate`(마이그레이션 expand 검사 · 비밀 이름 검사), `skeleton`은 main 푸시 때만.
- **Documentation**: 한국어 Javadoc이 결정 ID(J · BR · U*-J · 편차)를 인용하는 관례. `docs/units/u11/{design,report,handoff}.md`가 U11 사실의 정본(U13 브리프가 가리킴).

### Technical Debt Signals
- **`SessionExecutor.java` 3,964줄 · `Services` 생성자 오버로드 사슬**(`Services` ~170-205에 4개 오버로드 + 정식): 새 장치를 더하는 비용이 크다. 단일 스레드 규약(실행기 한 스레드, 락 없음, 상태 대부분 `private` 필드)이라 비동기 완료를 반드시 메시지로 돌려놓아야 한다. 제브 단계를 별도 클래스(예: `GuardrailCoordinator`, `JudgmentCoordinator`처럼 `Env` 인터페이스로 실행기에 닿는 협력 객체)로 빼는 것이 U11이 이미 한 선례이고 이쪽이 파일 충돌 · 줄 수 증가를 줄인다. U12도 같은 파일을 고치므로(팀 규칙: 같은 파일은 한 사람이 차례로) 변경 줄을 최소로 둘 것.
- **세대 번호 둘(기기 프레임 세대 / 공급자 출력 세대)이 같은 클래스에 공존**: 위 1절 표. 늦은 ALLOW를 잘못 판정하기 쉽다. 설계서에 표를 그대로 옮기고 시험으로 못 박을 것(`sendInstruction`이 기기 세대를 올리지 않는 사실).
- **`TranscriptWriter`의 단일 열린 AI 행 · PARTIAL 전용 덮어쓰기**: 비동기 제브 단계가 얹히면 행 수명과 구간 수명이 갈라진다(1절 · 5절). 현재 구조는 "구간 한 개 = AI 행 한 개 = 게이트 판정 한 번(동기)"을 가정한다.
- **`JudgmentLiveRecorder` 재사용 불가(패키지 · 타입 고정) → 복붙 위험**: `@SingleInstanceAssumption`, `block`/`drain`/`tick`, `MAX_PENDING`, 영구 오류 판별(23503 · 23514)이 모두 클래스 안에 있다. 제브 기록기를 또 하나 복제하면 SI-10 지도 · 삭제 참여자 · 정리 훅이 늘어난다. 공통 기반으로 뽑을지는 이번 작업 범위(refactor 스코프, 최소 변경)와 균형을 설계에서 정할 것(팀 학습: 공통화보다 선례 따르기가 파일 충돌이 적음).
- **`RealtimeStartupChecks.Inputs` 레코드에 필드 추가 시 시험 대량 컴파일 영향**.
- **`PracticeSessionExtension.ExtensionEvent.Kind.GUARDRAIL_BLOCKED`**: 자리만 있고 아무도 보내지 않는다(`SessionExecutor`에 사용처 없음, 주석 "가드레일 차단 사건의 자리(아직 보내지 않는다)"). U13이 연습 발화가 제브에 막힐 때 이 사건을 확장에 알릴지(U12의 역할 전환 보류 AC1.5.6와 연결) 정해야 한다. 브리프 "하지 않는 것"에는 연습 상태 기계가 있으므로 U13이 사건을 보낼지 U12에 넘길지 경계 결정이 필요.
- 보호자 화면에 원문이 남는 구간(5절 주의 1).
- `team.md` Deployment/Way of Working의 "다음 번호는 core V8 · realtime V5" 문장이 낡음(실제 realtime 다음 V6).

### Gaps between brief and code (해결하지 않고 표시)
| ID | 브리프 문장 | 실제 코드 | 영향 |
|---|---|---|---|
| G1 | "`OutputGate.inspect`(구조·1차 사전) 뒤, `recheck` 전에 부른다 … `OutputGate`" | `OutputGate`는 동기 · 순수 판정 클래스. 비동기 대기는 담을 수 없고 호출 흐름은 `SessionExecutor.sealAndGate`에 있다 | 제브 단계는 실행기(또는 협력 객체) 쪽에 두어야 한다. "OutputGate 안 한 단계"(ADR-006)는 개념적 표현으로 읽어야 한다 |
| G2 | "세대가 올라가면 그 구간은 무효, 출력 세대 변경 뒤 ALLOW 무효" | 세대 번호가 둘이고 `sendInstruction` · 판정 턴 · 보류 해제는 기기 세대를 올리지 않는다. `send`의 세대 콜백은 기기 세대만 본다 | 검사표에 두 번호를 모두 담아 비교해야 한다(1절) |
| G3 | "BLOCK 등 → 같은 다음 행동을 1회 다시 지시, 또 막히면 그 장면 정리 후 다음 단계, 그동안 '생각 중' 유지" | 기존 폐기 뒤 처리는 범주별 고정 교정 문장 지시(`correctAfterDiscard`, 세대당 1회 · 연달아 2회) + 대기 문구(`WAITING_FILLER`) 또는 `listeningAgain`. "같은 다음 행동 재지시"에 해당하는 기존 장치는 없다(재지시의 '다음 행동'은 U11 `NextAction`/`InstructionTemplates`) | 재지시를 어느 흐름(U11 `NextAction` 재송신 vs 교정 문장 재사용)으로 구현할지 설계 결정 필요. 두 번째 막힘 뒤 "그 장면 정리"의 정의(자유대화/연습별)가 코드에 없다 |
| G4 | "demo · prod에서 제브 실검사 꺼짐 또는 `JEV_API_KEY` 없음 → 기동 실패" | `render-env`는 `JEV_API_KEY`를 OPTIONAL로 두고 `gen-env.sh`(검증 스택 · CI)는 빈 값으로 둔다. 검증 스택이 사용하는 프로필 · `infra/ci/image-checks.sh` · `scripts/verify/b-host.sh` · `b-stack.sh`의 demo 가드가 이 규칙에 맞는지는 이 스캔에서 읽지 않았다(인프라는 얕게) | 새 기동 규칙이 demo 프로필로 도는 검증 스택(`compose.demo.yml` + `compose.ci.yml`, `127.0.0.1:8443`)을 깨뜨릴 수 있다. 브리프는 dev에서 대역 허용이라 했는데 검증 스택이 어느 프로필인지 확인 필요(Functional Design 첫 작업) |
| G5 | "계측 항목은 지표 이름이 `contracts/` 표에 있어야 한다(R-M10)" | `contracts/`에는 지표 이름 표가 없다(기존 `mujoong.gate.discard`도 코드 + 261008 기록 설계 문서에만 있음) | 어디에 지표 표를 둘지(`contracts/timing.md` 새 절? 새 파일?) 사람 결정. 그냥 코드에만 더하면 R-M10 문장 위반 소지 |
| G6 | "취소 · 중단 · 연결 해제 · 세대 변경 뒤 도착한 ALLOW는 무효" | 아이 입력 재개는 세대를 올리지 않는다(기기가 재생을 멈출 때만 끼어들기로 올림) | 제브 대기 중 아이가 말을 시작하면 ALLOW가 재생될 수 있다. 정책 결정 필요 |
| G7 | "비동기 기록기는 U11 `JudgmentLiveRecorder`의 관례를 따른다" | `JudgmentLiveRecorder` 등이 `judgment` 패키지 · 타입 고정, 공통 기반 클래스 없음. SI-10 표식 지도에 클래스를 직접 열거 | 복제하고 지도(`SingleInstanceMarkerTest`)에 더하는 것이 현재 방식(위 4절). 공통화는 별도 판단 |
| G8 | "전사 원문은 담지 않는다(R-F14)" (`guardrail_event`) | 폐기된 AI 구간 원문이 `realtime_transcript.text`(approved=false)로 이미 저장됨(`discard` → `aiFinal(text,false)`); U11 `held_output_transcript`도 원문 저장 선례 | `guardrail_event`는 원문 없이 만들 수 있으나, 막힌 문장이 transcript 표에 남는 것은 별도 확인 필요(보호자 화면에서는 가려짐) |
| G9 | "주소 · 모델은 설정 기본값(도메인 값을 코드 · 문서에 쓰지 않는다: 환경 변수 이름만, R-F3)" | Luna는 허용 호스트를 **코드 상수**(`LunaHosts.ALLOWED_HOSTS`)로 두고 계약 문서 · 시험이 대조, GPT-Live도 `ProviderHosts` 코드 상수 + `application-demo.yml`에 공급자 URL 기본값(`wss://api.openai.com/...`)이 이미 파일에 있음 | 같은 호스트 허용 목록 방식을 제브에 쓰면 브리프의 "도메인 값 금지"와 어긋난다. 기존 두 공급자의 선례와 R-F3의 해석(우리 서비스 도메인 vs 공급자 호스트) 사람 확인 필요 |
| G10 | "승인 원문 + `PracticeSpeech.matches`로 완전 일치하면 제브 생략" | `LiveContentRegistry.matchApproved`의 운영 호출자가 없고 `Services`에 레지스트리가 없다. `registerScript` 호출자도 없다(U12가 부를 예정). 연습 경로의 일치는 `pendingPracticeSpeech.matches`가 `sealAndGate`에서 이미 한다 | U13에서 레지스트리를 `Services`에 연결해야 한다. 대본 일치는 U12 이후 유효 |
| G11 | "제한 시간 800ms, 실검사 모드 자동 ALLOW 금지" + 계약 `ai-io.md` 10.3 재시도 분류 표 | 제브는 재시도 분류 표에 행이 없다. `jev-budget`은 "한 번의 상한"(timing.md) | 호출 안 재시도 없음으로 해석(Luna 판정과 동일, "바깥 게이트웨이가 이미 재시도하는 경로의 안쪽 어댑터는 재시도하지 않는다" 팀 학습)할지 계약에 한 줄 더할지 확인 |
| G12 | "시스템 프롬프트(C §3.3)와 분류 선택지는 버전 리소스" | 이미 U8이 `guardrail/policy-v1.json`(version `jev-guardrail-v1`)로 만들고 기동 검증 · 세션 고정까지 함 | 새 리소스 불필요. 다만 `guardrail-request.schema.json` 예시의 `policyVersion: "guardrail-v1"`은 실제 판 이름 `jev-guardrail-v1`과 다르다(예시일 뿐이지만 어댑터 변환 시험이 예시를 쓰면 판 불일치를 만든다) |

### 위험 요약 (실행기에 한 단계를 더할 때)
1. **동시성**: 완료 콜백은 다른 스레드에서 온다. 반드시 `mailbox.putSafety(...)` 메시지로 실행기 스레드에 돌려놓는다. `SessionExecutor`의 상태 필드는 락이 없다. 어댑터는 실행기 필드를 만지지 않는다.
2. **`SEGMENT_QUIET`와 `pendingOutputChunks` 레이스**: `onOutputQuiet`는 처리 안 된 출력 조각이 있으면(안전 편지함이 일반 편지함을 앞지른 경우) 봉인하지 않는다(~2157). 제브 대기가 얹혀 있으면 봉인 직후 도착한 다음 응답 조각이 새 구간을 연다(`fromStart=false`면 불완전 폐기)는 경로를 이어 검토.
3. **ALLOW 대기 중 세션 종료/정산**: `teardown`이 `outputGeneration`을 올리고 타이머를 취소하므로 늦은 결과는 `terminated`로 버려진다. 제브 요청 자체 취소(`CompletableFuture.cancel`)와 HTTP 취소 전파는 `LunaJudgeClient`가 하는 대로(`outcome.whenComplete(cancelled → lunaFuture.cancel)`) 따를 것.
4. **기기 연결 교체**: 새 `link`로 바뀌면(LinkReplaced) 대기 중 ALLOW가 이전 연결을 대상으로 송신하지 않도록 검사표에 연결 id를 담는다(`target.isOpen()` + `connectionId`).
5. **검사 중 공급자 응답 연속**: 같은 응답의 꼬리 조각이 대기 중 계속 도착한다. 대기 중 도착 조각의 처리 정책(버림 vs 보관)을 정하지 않으면 AI 행 · 구간이 섞인다(1절).
6. **지연**: 제브 대기만큼 첫 응답 소리가 늦어진다(최대 800ms + 네트워크). `LatencyRecorder`(`gatePassed` · `segmentConfirmed`)의 지표 의미("tGatePass")가 제브 대기를 포함하게 되므로 `latency` 기록 위치를 확인(NFR1은 측정 항목이라 합격 기준 없음).
7. **시험 틈**: 위 6절의 `segment-quiet` 50ms 흔들림. 새 통합 시험은 운영값 800ms를 쓰고 제브 대역은 수동 완료.
8. **R-F1 · R-M11**: 시험 · CI · `verify.sh`는 가짜 제브만. 결과는 "테스트 대역" · "합성 대화" · "실제 API" · "실제 기기"를 따로 적는다. 가짜 제브(`guardrail` 대역, 1차 사전만)의 통과를 실검사로 표기하지 않는다. 기동 로그 · 지표에 '대역/비활성' 표시를 남겨 구분할 것.

## Handoff Summary
- **Intent-relevant finding**:
  - 제브 단계의 정확한 삽입 지점은 `SessionExecutor.sealAndGate`(~2782-2829)에서 `gate.inspect` 통과 + 연습 원문 일치 검사(~2805-2809) 뒤, `gate.recheck`(~2812-2816) 앞이다. `OutputGate`는 동기 · 순수 클래스라 비동기 대기를 담지 못하므로 실행기 쪽 협력 객체(U11 `JudgmentCoordinator` + `SessionMailbox` 메시지 + `Env` 모양)로 얹는 것이 선례와 맞고 변경이 작다.
  - 늦은 ALLOW 무효화에는 **두 세대 번호**를 모두 비교해야 한다. 기기 프레임 세대 `outputGeneration`(`bumpGeneration`, ~3864)과 공급자 출력 세대 `outputGen`(`bumpOutput` ~2141 · `sendInstruction` ~2216). 지시 송신 · 판정 턴 · 보류 해제는 기기 세대를 올리지 않으므로 지금의 `sealed.generation()` 비교만으로는 부족하다. 검사표에는 `Sealed` 인스턴스 · 두 세대 · 연결 id · 연습 발화 correlationId를 담는다.
  - 보호자 화면 숨김은 새 필터 없이 `TranscriptWriter.aiFinal(…, approved=false)`로 이미 구현된다(12는 `TranscriptRepository.SELECT_VISIBLE_AFTER` ~53-56, 기록 · PDF는 `ResultSnapshotBuilder` ~110-116). 필요한 변경은 승인 시점(`send`의 `aiFinal(true)`)을 제브 ALLOW 뒤로 두는 것과, 대기 중 AI 행 재사용 문제(`TranscriptWriter.aiStart`가 열린 행이면 재사용, `TranscriptRepository.UPSERT`는 PARTIAL만 덮음)를 막는 것이다.
  - 연습 원문 일치 후크는 이미 `sealAndGate`에 있다(`pendingPracticeSpeech.matches`). `LiveContentRegistry.matchApproved`는 운영 호출자가 없고 `SessionExecutor.Services`에 레지스트리가 없다.
  - 선례 재사용: `guardrail/policy-v1.json`(U8)은 이미 있고 기동 때 검증 · 세션 고정됨. `JEV_API_KEY`는 R-M9 목록 전부에 이미 들어 있다. `mujoong.timing.guardrail.jev-budget=800ms`도 이미 있다. 새 패키지 `kr.mujoong.realtime.guardrail`은 `LunaArchTest`가 이미 예약해 둔 이름 공간이다.
  - V6 마이그레이션이 닿는 시험 · 목록: `RealtimeSchemaIT`(표 목록 · 버전 "5"), `PracticeMigrationIT` · `PracticeMigrationRegistrationTest`(버전 "5"), `testing/RealtimeDb`(비우는 표 순서), `SingleInstanceMarkerTest`(SI-10 지도), `RealtimeDeletionParticipantOrderTest`(순서). 새 삭제 참여자는 세션 삭제(200) 앞(예 185)이고 `DeletionCompleted.Counts`에는 넣지 않는 것이 U11 선례다.
- **Risks / follow-up**:
  - 설계에서 사람 결정이 필요한 갭: G3(재지시 방식 · "장면 정리"의 정의), G4(demo 프로필로 도는 검증 스택과 새 기동 규칙의 충돌 여부), G5(`contracts/`에 지표 표가 없음, R-M10 문장 대응 위치), G6(제브 대기 중 아이 입력), G9(허용 호스트 코드 상수와 R-F3 해석), G11(제브 재시도 분류).
  - `team.md`의 "다음 번호는 core V8 · realtime V5"는 낡았고 실제 realtime 다음은 V6이다.
  - 새 통합 시험은 `mujoong.gate.segment-quiet`를 운영값 800ms로 두고 제브 대역은 수동 완료 약속으로 만든다(U11 `RealtimeMetricsScrapeIT` · `SessionVoiceU3IT` 선례). 시험에서 실제 800ms를 기다리지 않는다.
  - 제브 실호출 응답 형식은 미확정이다. 어댑터 변환의 형식 가정은 시험 하나로 고정하고, 실호출은 사람 몫으로 `[실호출-합성]`으로 따로 보고한다(R-F1 · R-F13 · R-F15).
  - 이 스캔은 core-api · frontend · infra · tools를 얕게만 봤다. G4처럼 인프라 스크립트(`image-checks.sh` · `scripts/verify/b-host.sh` · `b-stack.sh` · `infra/ci/gen-env.sh`)와 demo 프로필 조합은 Functional Design에서 한 번 더 읽어야 한다.
