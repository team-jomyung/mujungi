# Requirements Analysis 질문 — U11 판정 턴 기반

> 기준 문서: `docs/units/u11/brief.md`(정본 지시서), 원래 기록 `261015-doc-sync-code-update`의 요구사항 FR3 · FR4.8 · FR5 · FR12.2 · NFR1 ~ NFR7, 스토리 US1.7 · US1.8 · US1.10 · US2.5, 코드 지식 베이스 `aidlc/spaces/default/codekb/mujungi/code-quality-assessment.md` 6절(U11-R1 ~ R13).
> 이 기록은 새 업무 요구사항을 만들지 않는다. 원래 요구사항을 U11 구현 단위로 다시 묶는다(project.md 교정). 그래서 정본끼리 부딪히거나 정본에 답이 없는 것만 묻고, 나머지는 맨 아래 가정 목록으로 한 번에 확인받는다.

## Q1. GPT-Live 실호출 확인을 언제 할까요?

지시서와 볼트 계획은 "Requirements Analysis 전에 GPT-Live 실호출(FR12.2)을 짧게 당겨, 서버 쪽 음성 전송 · 지시 주입 · 말 끝 신호를 확인하고, 안 되면 U11을 멈춘다"고 정했습니다(위험 R1).
그런데 이 확인을 돌릴 도구가 저장소에 없습니다. Luna에는 사람이 키를 넣고 돌리는 격리 작업 `lunaLiveTest`가 있지만, GPT-Live에는 그런 작업이 없습니다.
- 다운로드 폴더의 `mujung-live-test` 키트는 같은 선 형식(`wss://api.openai.com/v1/live/sessions`, `session.start`, `gpt-live-1`)으로 서버(파이썬) 쪽에서 음성을 보냅니다. 그래서 "서버 쪽 음성 전송"과 "말 끝 · 출력 전사 사건"은 이 키트로 확인할 수 있습니다.
- 다만 이 키트는 지시 주입(`session.instructions.correct` + `session.response.create`)을 보내지 않습니다. 그리고 `runs/`에 실행 결과가 아직 없습니다.

A. 지금 확인하고 결과를 받은 뒤 진행합니다. 님이 키트를 1세션 파일럿(약 9분, 약 $0.40)으로 돌려 서버 쪽 음성 전송을 먼저 확인합니다. 지시 주입은 U11 Code Generation 첫 단계에서 만드는 격리 작업으로 확인합니다. 키를 넣기 전에 `infra/RUNBOOK.md` 단계 4의 확인 기록을 남깁니다.
B. 설계는 지금 선 형식(J110 가정) 위에서 진행합니다. U11 Code Generation 첫 단계로 GPT-Live 격리 실호출 작업(`lunaLiveTest`처럼 `check` · CI 밖)을 만들어, 님이 서버 쪽 전송 · 지시 주입 · 말 끝 신호를 한 번에 확인합니다. 안 되면 그 시점에 멈춥니다. 위험은 설계가 뒤집히면 설계 작업이 버려진다는 점입니다.
C. 실호출 확인을 U11 밖(통합 작업 기록)으로 미루고, 가정 위에서 끝까지 구현합니다. 볼트 계획의 중단 조건을 쓰지 않게 되므로 권하지 않습니다.
X. Other (please specify)

[Answer]: B

## Q2. 새 판정 기록 표(realtime V5)를 더하면 깨지는 기존 단언을 고쳐도 될까요?

`judgment_live` 표를 realtime 마이그레이션 V5로 더하면 두 기존 시험이 깨집니다.
- `RealtimeSchemaIT`: 표 목록과 최신 판 "4"를 단언합니다.
- U5b `PracticeMigrationRegistrationTest`: `expectedVersion() == "4"`를 단언합니다.

팀 규칙 R-F2는 "기존 시험 단언을 낮추거나 끄지 않는다"입니다. 이 단언들은 낮추는 것이 아니라 새 사실(판 5, 표 1개 추가)로 바꾸는 것이고, 앞 유닛(U4b → U5b)도 V4를 더할 때 같은 방식으로 늘린 것으로 보입니다.

A. 판 번호와 표 목록을 새 사실("5", `judgment_live` 추가)로 갱신합니다. 단언을 느슨하게(예: "4 이상") 바꾸지 않고, 커밋 본문에 까닭을 적습니다. (추천)
B. 갱신하지 않고, 기존 표의 `jsonb` 칸에 판정 기록을 담아 마이그레이션을 피합니다. 판정 기록을 따로 조회 · 삭제하기 어려워집니다.
C. 이 시험들을 고치는 일을 U11에서 하지 않고, 앞 유닛 담당과 따로 정합니다(U11 PR이 그때까지 막힘).
X. Other (please specify)

[Answer]: A

## Q3. 자유대화의 시작 응답(J1) · 종료 의도(J6) 판정을 기존 규칙 판정기와 어떻게 나눌까요?

지금 코드에는 같은 질문에 답하는 규칙 판정기가 이미 있습니다.
- 참여 확인: `JoinTracker`(ACCEPT · REFUSE · TOPIC_REFUSAL · UNCLEAR)
- 종료 의도: `StopIntentJudge`(기본 "무중아 그만할래" + 보호자 등록 표현 → STOP · CONFIRM · NONE)

정본은 "등록 종료 표현과 일치하면 판정 없이 바로 종료"(AC1.7.1), "등록 표현과 일치하지 않는 종료 뜻 후보일 때 Luna 판정"(AC1.7.3)이라고만 합니다. 누가 '후보'를 고르는지, J1을 언제 Luna에 보낼지는 정하지 않았습니다. 둘이 따로 답하면 결과가 엇갈릴 수 있습니다.

A. 규칙 판정기가 먼저 봅니다. 규칙이 확정하면(등록 표현 일치 = STOP, 명확한 ACCEPT · REFUSE) 판정 없이 처리하고, 규칙이 확정하지 못할 때만(종료 CONFIRM 후보, 참여 UNCLEAR) Luna 판정 턴을 엽니다. 키가 없으면 지금 동작 그대로입니다. (추천)
B. J1 · J6은 언제나 Luna가 판정하고, 규칙 판정기는 등록 종료 표현 즉시 종료(AC1.7.1)에만 씁니다. 참여 확인 · 종료 확인마다 판정 턴(최대 2초 + 보류)이 생깁니다.
C. 둘 다 돌려 Luna 결과를 기록만 하고(비교용), 진행은 지금 규칙 판정기로 합니다.
X. Other (please specify)

[Answer]: A

## Q4. 판정 보류 · 10초 응답 준비 초과 때 "무난한 되묻기 · 맞장구"를 무엇으로 낼까요?

FR3.4 · FR5.3 · AC1.8.3 · AC1.8.4는 판정 보류나 10초 초과 때 "다시 말해 줄래?" 같은 무난한 되묻기 · 맞장구를 하라고 합니다.
그런데 그 문장의 정본이 없습니다. `contracts/timing.md` 13절이 말하는 `D_HOLD` 되묻기 문구가 코드(`DialogInstructions`)와 U8 승인 발화 시드 어디에도 없습니다.

A. GPT-Live에 "판정 라벨 · 확률 · 기준 없이, 짧게 다시 말해 달라고 하거나 맞장구하라"는 서버 템플릿 지시를 넣고, 문장은 GPT-Live가 만듭니다. 이 문장은 기존 출력 게이트(U13 뒤에는 제브)를 거칩니다. 새 승인 문구는 만들지 않습니다. (추천)
B. 승인 발화(`APPROVED_SPEECH`) 고정 문구로 재생합니다. 정본에 원문이 없으므로 화면 설계 · 문구 정본 개정 제안(RP 표)을 남기고, 사람이 문구를 승인한 뒤 시드로 넣습니다. 승인 전까지 이 경로는 시험 대역 문구로만 돕니다.
C. 판정 보류는 A(지시), 10초 초과는 B(고정 문구)로 나눕니다.
X. Other (please specify)

[Answer]: A

## Q5. 휴식 요청 처리(AC1.7.6)는 U11에서 어디까지 할까요?

AC1.7.6은 "휴식 요청이면 받아 주는 승인 문구(`D_REST_ACK`)를 들려주고, 기다리던 질문을 다시 들려준다(`D_REPROMPT`)"입니다.
- `D_REST_ACK`는 U8 시드 004에 승인 발화로 들어 있습니다.
- `D_REPROMPT`는 정본에 고정 원문이 없어 U8이 넣지 않았습니다(편차 U8-e).
- 자유대화에는 '기다리던 질문'이 없고, 연습의 '기다리던 질문'은 U12 상태 기계가 압니다.

A. U11은 J6 판정 결과 '휴식 요청'을 확장점 신호로 넘기고, 자유대화에서는 `D_REST_ACK`만 재생한 뒤 대화를 이어 갑니다. 연습의 '기다리던 질문 다시 들려주기'는 U12가 합니다. 공백 타이머 · 기기 상태는 그대로 둡니다. (추천)
B. U11이 자유대화 · 연습 모두 `D_REST_ACK` + 다시 묻기 지시(GPT-Live 지시, 고정 문구 아님)까지 합니다.
C. 휴식 처리 전체를 U12로 넘기고, U11은 '휴식 요청' 판정 결과만 기록합니다(자유대화 휴식은 이번에 하지 않음).
X. Other (please specify)

[Answer]: B

## Q5-1. 연습 중 휴식 요청 뒤 '기다리던 질문 다시 묻기'는 무엇으로 정할까요? (Q5 답 B의 후속)

Q5에서 연습 · 자유대화 모두 U11이 `D_REST_ACK` + 다시 묻기 지시까지 하기로 했습니다(B).
그런데 연습에서 '기다리던 질문'이 무엇인지는 연습 흐름이 압니다. 지금 연습은 U5b 레거시 런타임이 출력권을 갖고(`controlsOutput()=true`) 대본으로 진행하고, 새 연습 흐름(U12)은 아직 없습니다. U11 실행부가 연습 출력에 직접 끼어들면 레거시 런타임의 출력 순서와 부딪힐 수 있습니다.

A. 실행부는 질문 내용을 모른 채, GPT-Live에 "방금 하던 이야기로 돌아가 마지막 질문을 짧게 다시 물어보라"는 일반 지시를 넣습니다. 자유대화 · 연습에 같은 지시를 씁니다. 출력권을 가진 연습 확장이 있으면 실행부는 지시하지 않고 확장점에 '휴식 요청' 신호만 넘겨, 확장(지금은 U5b, 뒤에 U12)이 다시 묻기를 합니다. 확장이 처리하지 않으면(U5b) `D_REST_ACK`만 나가고 다시 묻기는 없습니다. (추천)
B. A의 일반 지시를 연습에도 언제나 씁니다. 출력권 확장이 있어도 실행부가 `D_REST_ACK` 뒤 다시 묻기 지시를 직접 넣습니다. U5b 레거시 런타임의 출력과 겹칠 수 있어, 그 경우 U5b 진행 순서가 흐트러질 위험이 있습니다.
C. 확장점에 '다시 물을 질문'을 돌려주는 능력을 더해, 확장이 질문 원문을 주면 실행부가 그 원문을 다시 묻도록 지시합니다. U5b는 기본 구현(질문 없음)으로 두어 일반 지시로 떨어집니다.
X. Other (please specify)

[Answer]: A

## Assumption Confirmation

아래는 정본 문장이나 코드 사실과 바로 맞는다고 보고 정한 가정입니다. 한 묶음으로 확인해 주세요.

1. **ID 다시 묶기**: 이 기록의 요구사항은 새 번호(FR1 ~)를 쓰고, 원래 기록 ID를 '원 FR3.1'처럼 함께 적어 추적합니다. 원래 FR12.2의 나머지(선 형식 변경 · 가짜 공급자 · u1 ~ u4b 시험 일괄 수정 · 키 이름 통일)는 U11 범위 밖입니다(ADR-007, 지시서).
2. **보류 기준값**: 1순위 확률 하한 0.6, 1 · 2순위 차이 0.15를 기본값으로 둡니다(AC1.8.1). 설정 키 `mujoong.judgment.min-confidence` · `mujoong.judgment.min-margin`은 `RealtimeProperties` 루트 레코드에 묶어 `ConfigKeyContractTest`를 고치지 않습니다. 시간 값이 아니라 R-M5 세 곳 규칙은 걸리지 않습니다.
3. **키 없을 때**: Luna 키(`OPENAI_API_KEY`)가 없어 호출부 빈이 없으면, `JudgeClient`는 공급자를 부르지 않고 언제나 '판정 보류(공급자 없음)'를 돌려줍니다. 시험 · CI · `verify.sh`는 가짜 판정기만 씁니다(R-F1).
4. **출력 검증**: 선택지 밖 코드, 1 · 2순위가 같은 코드, 확률 범위 이상은 `JudgeClient`가 직접 걸러 판정 보류로 바꿉니다. 판정 출력 스키마 파일은 바꾸지 않습니다.
5. **송신 순서**: 판정 뒤 다음 행동 지시는 한 송신 작업 안에서 `session.instructions.correct` → `session.response.create` 순서로 보내, 지금 `toProvider`의 순서 없는 송신을 피합니다.
6. **보류 출력 갈래**: 판정 턴 동안 온 GPT-Live 출력은 문장 버퍼 · 대화 기록기 **앞**에서 갈라, 음성은 버리고 전사는 `judgment_live`에만 남깁니다. `realtime_transcript` · 보호자 중계에는 남기지 않습니다. 보낼 응답이 없는 동안 기기는 '생각 중'을 유지합니다.
7. **늦은 결과 · 장애**: 판정마다 차례 id를 두고, 장애 취소 · 다음 차례 뒤 도착한 전사 · 판정은 버립니다. 음성 대화 장애 경로(공급자 끊김 · 기술 일시정지)에서는 진행 중 판정을 '장애 취소'로 기록합니다.
8. **패키지 · 구조**: `JudgeClient`와 `judgment_live` 저장소는 새 패키지(예: `kr.mujoong.realtime.judgment`)에 둡니다(ArchUnit 제약). 판정 턴 상태는 실행기 스레드 전용 협력 객체로 떼어, 뒤에 이 파일을 고칠 U13 · U12와의 충돌을 줄입니다.
9. **판정 기록 삭제 · 계약**: `judgment_live`는 세션 행보다 먼저 지우는 삭제 참여자 순서(세션 200 앞)로 지웁니다. `DeletionCompleted.counts`에는 항목을 더하지 않습니다(계약 변경 없음).
10. **연습 판정 J2 ~ J5**: U11은 확장점 능력 `requestJudgment`와 결과 사건만 제공하고, J2 ~ J5를 실제로 부르는 일은 U12가 합니다. U11 시험은 시험용 확장으로 이 능력을 확인합니다.
11. **기록 이벤트**: `SessionRecordCaptured` 발행 경로는 그대로 두고, 새 형식 구분은 `flowVersion`(편차 U8-d)입니다. 값을 채우는 일은 U12 몫입니다.
12. **시험**: test-after, 태그 `u11`, 새 범위 파일 `backend/coverage-scope/u11.txt`(범위 줄 80%), 시험 전략 Standard입니다. 증거는 테스트 대역 · 합성 대화 · 실제 API · 실제 기기로 나눠 보고합니다(R-M11).

A. Accept assumptions
B. Convert to follow-up questions

[Answer]: A. Accept assumptions

## 범위 확장 질문 (2026-10-16 실호출 뒤, Code Generation에서 되돌아옴)

> Code Generation Step 3에서 GPT-Live 격리 실호출을 세 번 돌린 결과, 서버 쪽 음성 중계(ADR-007)는 성립하지만 지금 코드의 선 형식(J110 가정)이 실제와 다르다는 것이 확인됐다(실호출 보고서 `backend/realtime-activity/build/gpt-live/report.md`, `construction/code-generation/code-generation-questions.md` Step 3-1 ~ 3-3). 사람은 이 선 형식 정비를 U11 범위에 넣기로 했다(Step 3-3 답 B). Luna 실호출은 2/2 응답, 지연 2.6 · 2.9초였다.
> 관측된 사실: 실제 클라이언트 메시지 15종(`session.start` · `session.update` · `session.close` · `session.input_audio.append|mute|unmute` · `session.instructions.append` · `session.thinking.append` · `session.commentary.append` · `session.output_audio.fixture.play` · `response.create` · `response.item.create` · `decision.create|item.create|item.delete`). `session.instructions.correct` · `session.response.create` · `session.response.cancel`은 없다. 세션 시작은 `session{model, instructions, audio{format{type:"audio/pcm", rate}}, …}` 중첩 모양이어야 하고(16kHz로 수락 확인), 출력 음성 사건은 `{type, delta}`로 응답 id가 없다. 출력 전사 · 말 끝 사건은 무음 · 톤 입력이라 관측되지 않았다.
> 영향(읽기 전용 조사): 요청 팩토리 · 공급자 클라이언트 · 실행부의 응답 id 의존(문장 버퍼 · 게이트 · 취소), 가짜 공급자 2곳(`tools/fake-provider` · `test-support`), 기존 시험 약 20개 파일의 단언, 끝-끝 C단계, `contracts/ai-io.md` 2 · 3 · 8절과 편차 (z) · (aa) · (U4b-a).

## Q6. 실제 선 형식의 기준을 무엇으로 삼을까요?

팀 정본 문서끼리도 다릅니다. ADR(`50_ADR_2S/architecture/protocol.md` · `ADR-004`)은 세부 필드를 "TBD · 실연동 확인"으로 두고 `session.instructions.append` · 출력 전사 사건을 전제합니다. 기술명세 v0.3은 `rate 24000` · `start_ms` · `session.instructions.appended` · `delegation_id` 같은 필드를 단정하지만, 실호출과 맞지 않는 곳이 있습니다(16kHz만 확인됨, 출력 음성에 `start_ms` 없음).

A. 실호출 관측을 1순위, ADR(`protocol.md` · `ADR-004`)을 2순위로 삼고, 기술명세 v0.3의 단정 필드는 실호출로 확인된 것만 씁니다. 확인되지 않은 필드는 요구사항의 열린 항목으로 둡니다. (추천)
B. 기술명세 v0.3을 그대로 기준으로 삼고, 실호출과 다른 곳만 고칩니다.
C. 공급자 공개 문서([S7] 링크 등)를 사람이 먼저 확인해 정리해 주시면 그것을 기준으로 삼습니다(그때까지 이 단계가 멈춤).
X. Other (please specify)

[Answer]: A

## Q7. 표본율이 다르면 어디서 맞출까요?

기기 ↔ 서버 계약은 PCM16 24kHz(20ms = 960바이트)이고, 정본도 "전 구간 24kHz, 중간 변환 없음"(J131)입니다. 실호출은 16kHz(키트 형식)만 확인됐고, 24kHz를 새 중첩 모양으로 보냈을 때 받아들여지는지는 아직 모릅니다.

A. 다음 실호출에서 24kHz를 새 중첩 모양으로 먼저 확인합니다. 되면 변환 없이 지금 기기 계약을 유지하고, 안 되면 서버(realtime)에서 24k ↔ 16k를 변환합니다(기기 계약 유지, 입출력 SHA 일치 단언만 바뀜). (추천)
B. 확인 없이 바로 서버 변환(24k ↔ 16k)으로 갑니다.
C. 기기까지 16kHz로 바꿉니다(프런트 · 계약 · 스키마 · 녹음 형식 전부 변경).
X. Other (please specify)

[Answer]: A

## Q8. 응답 id도 취소 메시지도 없을 때, 출력 구별 · 취소를 어떻게 할까요?

실제 출력 음성 사건에는 응답 id가 없고, 공급자 쪽 응답 취소 메시지도 없습니다. 지금 실행부(문장 버퍼 · 게이트 · 판정 턴 설계의 BR3.7)는 응답 id로 출력을 묶고 취소합니다. 이대로면 모든 출력이 버려져 기기가 무음이 됩니다(안전 쪽 실패).

A. 서버가 출력 구간마다 자체 id · 세대(epoch)를 붙이고, 취소는 공급자에 요청하지 않고 서버에서 버리는 것으로 바꿉니다(기술명세 v0.3 §3.3 방식). 판정 턴 · 보류 중 출력은 세대로 가릅니다. `input_audio.mute`는 쓰지 않습니다. (추천)
B. A에 더해 판정 턴 · 보류 동안 `session.input_audio.mute`/`unmute`로 공급자 입력을 막습니다(아이가 끼어들 수 없게 됨).
C. `delegation: client` + `decision.*` 구조를 조사해 공급자 쪽에서 응답 생성을 통제하는 방식을 씁니다(정본에 없음, 조사 비용 큼, 실호출 추가 필요).
X. Other (please specify)

[Answer]: A

## Q9. 출력 전사 · 말 끝 · 입력 전사 완료 사건을 어떻게 확인할까요?

지금 출력 게이트는 출력 전사로 문장을 검사하고(U13 제브 검사도 여기에 기댑니다), 참여 확인 · 종료 판정 · 연습은 모두 입력 전사 완료 사건(`InputTranscriptDone`)에서 시작합니다. 이번 실호출은 무음 · 톤 입력이라 이 사건들이 오는지 확인하지 못했습니다. 정본도 "발화별 완료 신호를 가정하지 않는다"(ADR-004)고 적었습니다.

A. 선 형식 정비의 첫 작업으로 합성 말소리 입력(TTS)으로 실호출을 한 번 더 돌려 출력 전사 · 입력 전사 · 말 끝 사건이 오는지와 이름 · 모양을 확인합니다. 입력 전사 완료 사건이 없으면 기술명세 §3.7대로 서버가 무음과 전사 조각 정지로 확정합니다. 출력 전사가 없으면 멈추고 사람과 다시 정합니다(게이트 전제가 무너짐). (추천)
B. 정본(전사 사건 있음)을 믿고 설계 · 구현을 진행하고, 확인은 Build and Test에서 합니다.
X. Other (please specify)

[Answer]: A

## Q10. Luna 판정 시한 (사람 결정 기록)

Luna 실호출 지연이 2.6 · 2.9초(2초 안 0/2)라, 사람이 판정 시한을 2초에서 4초로 늘리기로 했습니다(2026-10-16). 원래 요구사항 NFR1 · FR3.4 · AC1.8.2의 "2초"와 다르므로 편차로 적고, 시간 값 세 곳(`contracts/timing.md` 13절 · realtime `application.yml` · 설정 레코드, R-M5 · 편차 U8-c)을 같은 커밋에서 고칩니다.

A. 4초
X. Other (please specify)

[Answer]: A

## Q11. 공급자 키 이름을 통일할까요?

원래 요구사항 FR12.2는 선 형식을 확인할 때 키 이름 통일(`PROVIDER_API_KEY` → `OPENAI_API_KEY`)도 정한다고 했습니다. 실제로 두 키는 같은 OpenAI 키입니다. 통일하면 비밀 이름 검사 목록 전부(R-M9: `ci.yml` · `image-checks.sh` · `infra-validate` · `render-env` · `log-hygiene-check` · `gen-env.sh` · `scripts/verify` · README · RUNBOOK 등 약 15곳)를 같은 커밋에서 바꿔야 합니다.

A. 이번에는 바꾸지 않습니다. `PROVIDER_API_KEY`(GPT-Live)와 `OPENAI_API_KEY`(Luna)를 따로 둡니다. 통일은 운영 배포 작업 때 정합니다. (추천)
B. 이번에 `OPENAI_API_KEY` 하나로 통일하고 R-M9 목록을 모두 고칩니다.
X. Other (please specify)

[Answer]: B

## Q12. 커밋 · PR을 어떻게 나눌까요?

선 형식 정비는 기존 U1 ~ U5b 시험 단언을 많이 바꾸는 큰 변경이고, U11 판정 턴은 그 위에 얹힙니다.

A. 한 작업 기록 안에서 두 묶음으로 나눕니다. 먼저 선 형식 정비 PR(코드 · 가짜 공급자 · 시험 · 계약)을 `main`에 합치고, 그 위에서 U11 판정 턴 PR을 엽니다. 리뷰가 쉽고 문제가 생기면 따로 되돌릴 수 있습니다. (추천)
B. PR 하나로 함께 합칩니다.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Q1 B: 설계는 지금 GPT-Live 선 형식(J110 가정) 위에서 진행합니다. U11 Code Generation 첫 단계로 GPT-Live 격리 실호출 작업(`check` · CI 밖, 사람이 키를 넣고 실행)을 만들어 서버 쪽 음성 전송 · 지시 주입 · 말 끝 신호를 한 번에 확인하고, 안 되면 그때 멈춥니다. 지시서의 "Requirements Analysis 전에 확인"과 다른 사람 결정이라 요구사항에 편차로 적습니다.
- Q2 A: realtime V5(`judgment_live`)를 더하며 `RealtimeSchemaIT`와 `PracticeMigrationRegistrationTest`의 판 번호 · 표 목록 단언을 새 사실("5", 표 추가)로 갱신합니다. 느슨하게 바꾸지 않고 커밋에 까닭을 적습니다.
- Q3 A: 자유대화 J1 · J6은 규칙 판정기(`JoinTracker` · `StopIntentJudge`)가 먼저 보고, 규칙이 확정하지 못할 때(종료 CONFIRM 후보 · 참여 UNCLEAR)만 Luna 판정 턴을 엽니다. 키가 없으면 지금 동작 그대로입니다.
- Q4 A: 판정 보류 · 10초 응답 준비 초과 때의 되묻기 · 맞장구는 판정 라벨 · 확률 · 기준 없는 서버 템플릿 지시로 GPT-Live가 만들고, 기존 출력 게이트(U13 뒤 제브)를 거칩니다. 새 승인 문구는 만들지 않습니다.
- Q5 B: 휴식 요청이면 자유대화 · 연습 모두 U11이 `D_REST_ACK`를 재생하고 다시 묻기까지 처리합니다.
- Q5-1 A: 다시 묻기는 "하던 이야기로 돌아가 마지막 질문을 짧게 다시 물어보라"는 일반 지시로 합니다. 출력권을 가진 연습 확장이 있으면 지시 대신 '휴식 요청' 신호를 확장점에 넘겨 확장이 다시 묻습니다. U5b는 처리하지 않으므로 그 경우 `D_REST_ACK`만 나갑니다.
- 가정 12개 수락: 원 ID 추적 · FR12.2 나머지 범위 밖 / 보류 기준 0.6 · 0.15(`RealtimeProperties` 루트) / 키 없으면 언제나 보류 · 시험은 가짜 판정기 / 선택지 밖 · 중복 코드는 `JudgeClient`가 보류로 / 지시는 한 작업 안 correct → create / 보류 출력은 버퍼 · 기록기 앞에서 갈라 `judgment_live`에만, 생각 중 유지 / 차례 id로 늦은 결과 버림 · 장애 취소 기록 / 새 패키지 `judgment` · 협력 객체 / 삭제는 세션 200 앞, `DeletionCompleted.counts` 변경 없음 / J2 ~ J5는 능력만, 호출은 U12 / 기록 이벤트 발행 경로 유지 / 태그 `u11` · `u11.txt` 범위 80%.
- 범위 확장(코드 생성 Step 3-3 답 B): GPT-Live 선 형식 정비(요청 팩토리 · 공급자 클라이언트 · 실행부의 응답 id 의존 · 가짜 공급자 2곳 · 기존 시험 단언 · 끝-끝 C단계 · `contracts/ai-io.md`와 관련 편차)를 U11 범위에 넣습니다. 그래서 가정 1의 "FR12.2 나머지는 범위 밖"은 선 형식 · 가짜 공급자 · 기존 시험 · 키 이름에 대해 더는 맞지 않고, 가정 5의 `session.instructions.correct` → `session.response.create`는 실제 메시지(`session.instructions.append` → `response.create`)로 바뀝니다.
- Q6 A: 선 형식의 기준은 실호출 관측 1순위, ADR(`protocol.md` · `ADR-004`) 2순위입니다. 기술명세 v0.3의 단정 필드는 실호출로 확인된 것만 쓰고, 확인되지 않은 필드는 요구사항의 열린 항목으로 둡니다.
- Q7 A: 다음 실호출에서 24kHz를 새 중첩 모양으로 먼저 확인합니다. 받아들여지면 변환 없이 기기 계약(PCM16 24kHz)을 유지하고, 안 되면 realtime 서버에서 24k ↔ 16k를 변환합니다(기기 계약 유지, 입출력 SHA 일치 단언만 바뀜, J131 편차로 기록).
- Q8 A: 서버가 출력 구간마다 자체 id · 세대(epoch)를 붙여 출력을 묶고, 취소는 공급자에 요청하지 않고 서버에서 버립니다(기술명세 v0.3 §3.3 방식). 판정 턴 · 보류 중 출력은 세대로 가르고, `session.input_audio.mute`는 쓰지 않습니다.
- Q9 A: 선 형식 정비의 첫 작업으로 합성 말소리(TTS) 입력 실호출을 한 번 더 돌려 출력 전사 · 입력 전사 · 말 끝 사건의 유무와 이름 · 모양을 확인합니다. 입력 전사 완료 사건이 없으면 서버가 무음과 전사 조각 정지로 확정하고(기술명세 §3.7), 출력 전사가 없으면 멈추고 사람과 다시 정합니다.
- Q10 A: Luna 판정 시한을 2초에서 4초로 늘리고(원 NFR1 · FR3.4 · AC1.8.2 편차), 시간 값 세 곳을 같은 커밋에서 고칩니다(R-M5).
- Q11 B: 이번에 공급자 키 이름을 `OPENAI_API_KEY` 하나로 통일하고, 비밀 이름 검사 목록 전부(R-M9)를 같은 커밋에서 고칩니다. 결과로 키 하나가 있으면 GPT-Live와 Luna(realtime · core)가 함께 켜지고, 없으면 둘 다 가짜 · 보류로 남습니다. demo 호스트 Parameter Store의 실제 이름 변경은 저장소 밖 운영 일이라 RUNBOOK에 절차만 적습니다(이번 작업은 배포를 다루지 않음).
- Q12 A: 한 작업 기록 안에서 두 묶음으로 나눕니다. 먼저 선 형식 정비 PR(코드 · 가짜 공급자 · 시험 · 계약 · 키 이름 통일)을 `main`에 합치고, 그 위에서 U11 판정 턴 PR을 엽니다.

Does this all look correct before I generate the requirements artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
