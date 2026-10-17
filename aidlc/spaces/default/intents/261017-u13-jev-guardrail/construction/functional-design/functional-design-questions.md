# Functional Design 질문 — U13 제브 게이트

> 기준 문서: 이 기록의 요구사항 `inception/requirements-analysis/requirements.md`(FR1 ~ FR8, NFR1 ~ NFR9, 열린 질문 1 ~ 10), 코드 스캔 `inception/reverse-engineering/developer-scan.md`(갭 G1 ~ G12, 위험 요약), 지시 `docs/units/u13/brief.md`, 코드 지식 베이스 `aidlc/spaces/default/codekb/mujungi/`.
> 이 기록은 유닛 나누기 · 도메인 설계를 건너뛰어 설계 문서는 유닛 폴더 없이 `construction/functional-design/`에 쓴다(project.md 교정). 정본 설계가 이미 있어 사람 판단이 필요한 것만 개별 질문으로 묻고, 코드 사실로 정할 수 있는 나머지는 맨 아래 가정 목록으로 한 번에 확인받는다.

## Q1. 제브 실검사 · 대역 · 비활성을 고르는 설정 키를 어떻게 만들까요?

요구사항(FR1.3 · FR7.1)은 세 가지 모드(실검사 / 대역 / 비활성)와 "demo · prod는 실검사 + 키 필수, dev는 대역 · 비활성 허용"만 정했고, 설정 키 이름과 값은 이 단계에서 정하라고 넘겼습니다. 이 키는 `contracts/ai-io.md` 10절과 설정 키 대조 시험에 들어가므로 한 번 정하면 바꾸기 어렵습니다.
참고: Luna는 불리언 스위치 하나(`mujoong.luna.enabled`)와 키 유무로 켜집니다. 제브는 "꺼짐"이 아니라 대역 · 비활성이라는 두 가지 비실검사가 있어서 불리언 하나로는 구분되지 않습니다.
- 대역(STUB): 제브 단계를 모두 지나가며(요청 구성 · 지연 · 이벤트 · 지표) 1차 사전을 통과한 구간을 ALLOW로 돌려줍니다. '대역'이 표시됩니다.
- 비활성(OFF): 제브 단계를 건너뛰어 지금 동작과 같습니다. '비활성'이 표시됩니다.

A. 키 하나 `mujoong.guardrail.jev.mode` = `REAL` · `STUB` · `OFF`. 기본값은 demo · prod = `REAL`(다른 값이면 기동 거부), dev = `STUB`. `REAL`인데 `JEV_API_KEY`가 없으면 어느 프로필이든 기동 거부(AC2.3.3의 "키가 없으면 실제 어댑터는 켜지지 않는다"). (추천)
B. Luna 선례처럼 불리언 스위치 `mujoong.guardrail.jev.enabled` + 키 유무. 켜짐 + 키 = 실검사, 꺼짐은 dev에서만 허용하고 대역과 비활성을 구분하지 않습니다(둘이 하나). 키는 하나지만 FR1.3의 '대역 / 비활성' 구분이 사라집니다.
C. 키 두 개 `mujoong.guardrail.jev.enabled`(실검사 여부) + `mujoong.guardrail.jev.stub`(비활성 아닐 때 대역). 조합이 늘어 규칙과 시험이 복잡해집니다.
X. Other (please specify)

[Answer]: A

## Q2. 제브가 검사하는 동안(최대 0.8초) 같은 세션에서 새 출력 조각이 도착하면 어떻게 할까요?

검사 중인 구간은 이미 봉인되어 있습니다. 그 사이 공급자가 다음 응답을 시작하면 새 조각이 도착합니다. 지금 보호자 화면용 AI 행(`aiStart`)은 열린 행이 있으면 그 번호를 재사용하고, 닫으면 나중에 `ALLOW`가 와도 승인으로 바꿀 수 없어서(`PARTIAL` 행만 덮어쓰기) 그냥 두면 앞 응답과 새 응답이 같은 행에 섞이거나 앞 응답을 승인하지 못하게 됩니다. 정본(FR3.3)은 "한 번에 한 구간만 검사하고 섞이지 않는다"까지만 말합니다.

A. 검사 중 도착한 새 조각은 받지 않고 버립니다(새 구간을 열지 않아 AI 행이 섞이지 않음). 검사가 끝나면 다시 받습니다. 그 새 응답의 앞부분이 잘리면 첫 조각부터가 아닌 불완전 구간이 되어 안전 쪽으로 폐기됩니다. 가장 단순하고 안전합니다. (추천)
B. 새 조각을 따로 모아 두었다가 앞 검사가 끝난 뒤 이어서 검사합니다(대기열 1칸). 앞 응답의 AI 행은 `PARTIAL`로 유지하고 새 구간은 새 행으로 엽니다. 응답이 안 잘리지만 AI 행 · 세대 · 타이머 상태가 늘어 설계와 시험이 훨씬 커집니다.
X. Other (please specify)

[Answer]: B

## Q3. 막힌 뒤 "같은 다음 행동을 1회 다시 지시"할 때 어떤 지시문을 쓸까요?

정본(AC2.1.3)은 막히면 같은 다음 행동을 1회 다시 지시한다고 합니다. 그런데 막힌 출력은 두 종류입니다. ① 서버가 보낸 다음 행동 지시(U11 `NextAction` 템플릿: 되묻기 · 맞장구 등)에 대한 응답 ② 아이 말에 GPT-Live가 알아서 한 자유 응답(서버 지시 없음). ②에는 "같은 다음 행동"이 정해진 문장이 없습니다. 어떤 문장이든 GPT-Live 지시이고 그 응답도 제브를 거치며, 아이에게는 지시문이 들리지 않습니다.

A. ①은 같은 템플릿 지시를 다시 보내고, ②는 새 템플릿(예: `RETRY_FREE_TURN`: "방금 말은 쓰지 않는다. 같은 흐름에서 다른 말로 짧게 한 번만 이어 달라")을 U11 `InstructionTemplates`에 더합니다. 새 승인 문구는 만들지 않습니다. (추천)
B. ①②를 가리지 않고 새 템플릿 하나만 씁니다. 단순하지만 ①이 되묻기였다면 그 의도가 사라집니다.
C. 재지시 문구를 사람이 승인한 고정 문장(승인 발화)으로 만듭니다. 정본 문구 개정 제안(RP 표)과 승인 시드 추가가 필요하고 U13 범위가 넓어집니다.
X. Other (please specify)

[Answer]: A

## Assumption Confirmation

아래는 정본 문장이나 코드 사실과 바로 맞는다고 보고 정한 가정입니다. 한 묶음으로 확인해 주세요.

1. **제브 단계의 구조**: `kr.mujoong.realtime.guardrail`에 포트 `GuardrailClient`(`available()` + 비동기 완료, 예외 없이 모든 실패를 결과로 완료), 결과 타입 `GuardrailVerdict`(ALLOW / BLOCK / REVIEW / ERROR 계열; U11 `JudgeOutcome`과 이름을 섞지 않음), 어댑터 `JevGuardrailClient`, 대역 `StubGuardrailClient`, 비활성 구현을 둡니다. 실행기 쪽 협력 객체 `GuardrailCoordinator`가 U11 `JudgmentCoordinator`와 같은 방식(결과를 `SessionMailbox` 메시지 `GuardrailArrived`로 실행기 스레드에 돌려놓음)으로 `sealAndGate`의 `inspect` 통과 · 연습 원문 일치 뒤, `recheck` 앞에 끼어듭니다. `OutputGate`는 바꾸지 않습니다. 실행기에는 호출 지점만 더합니다.
2. **검사표(ticket)**: 요청 id · 봉인된 `Sealed` 인스턴스(후보 문장 = 그 `transcript()`, 송신 = 그 `chunks()`로만) · 기기 프레임 세대 · 공급자 출력 세대 · 기기 연결 id · 연습 발화 id(있으면)를 담습니다. `ALLOW`는 요청 id가 같고, 두 세대와 연결 id가 그대로이고, 상태가 `ACTIVE`이고, 종료 · 출력 보류가 아니고, 연습 발화가 그대로일 때만 유효합니다. 유효하면 그때 `recheck`(NOT_ACTIVE · RECORDER_DEGRADED · 출력 레인 예약)를 부르고 `send`합니다.
3. **전사-PCM 일치의 한계**: 같은 `Sealed`를 쥐는 것으로 "같은 응답 · 같은 구간"을 구조적으로 보장합니다. 전사는 공급자 모델이 낸 출력 전사이지 PCM을 독립적으로 받아 적은 것이 아니라서, 소리와 글이 실제로 같은지는 코드로 검증할 수 없다는 한계를 설계서에 적습니다.
4. **'새 말 시작' 신호**: 검사 시작 시점에 현재 입력 묶음 id를 검사표에 적고, 결과가 도착했을 때 입력 묶음 id가 달라졌으면(새 묶음이 열렸으면) 검사를 무효로 봅니다. 같은 묶음의 꼬리 조각은 무시합니다(FR4.3).
5. **막힘 처리**: 막힘(`BLOCK` · `REVIEW` · 시간 초과 · 공급자 오류 · 잘못된 JSON · 정책 판 불일치)은 모두 같은 흐름입니다. 구간을 `aiFinal(…, false)`로 닫고(기존 `discard` 경로의 사유 코드만 제브 사유로 구분), 기기는 `THINKING`을 유지(`listeningAgain`에 제브 대기 · 재지시 조건을 더함)하며 재지시(Q3) 1회, 또 막히면 자유대화는 그 턴을 포기하고 기존 대기 문구(`WAITING_FILLER`) 또는 듣기로 돌아갑니다. 재지시 횟수는 턴 단위로 세고, 턴은 아이의 확정 전사 하나로 시작해 다음 확정 전사 · 기기 끼어들기 · 세션 종료로 끝납니다.
6. **연습 갈래**: 연습 확장점에 "차단됨" 이벤트(`GuardrailBlocked` 같은 종류 하나)를 확장점 이벤트 열거형에 더하고 대역 주입 단위 시험까지 합니다. 운영 호출자는 U12입니다. 기존 확장점 구현체는 바꾸지 않습니다.
7. **기록 `guardrail_event`**: realtime V6(`V6__u13_jev_guardrail.sql`). 칸은 `id`(= 검사 요청 id, 멱등 덮어쓰기), `guardian_id`(NOT NULL, 삭제 기준), `session_id`(NOT NULL, FK `ON DELETE RESTRICT`), `mode`(`REAL` · `STUB` · `OFF` 등 CHECK), `source_kind`(`NEW_UTTERANCE` · `APPROVED_VERBATIM_SKIPPED`), `decision`(`ALLOW` · `BLOCK` · `REVIEW` · `TIMEOUT` · `ERROR` · `VOIDED` 등 CHECK), `reason_code`, `policy_version`, `latency_ms`, `created_at`입니다. 전사 원문 칸은 없습니다(R-F14). 쓰기는 U11 `JudgmentLiveRecorder`와 같은 모양의 새 비동기 기록기(`GuardrailEventRecorder`, 같은 단일 인스턴스 표식 SI-10을 지도에 더함)와 저장소로 합니다. 공통화는 하지 않고 같은 모양의 새 클래스를 둡니다.
8. **삭제 순서 상수**: 삭제 참여자 `GuardrailEventDeletionParticipant`의 순서는 판정 기록(180)과 세션(200) 사이인 185입니다. `DeletionCompleted.Counts`는 바꾸지 않습니다. `RealtimeDeletionParticipantOrderTest` · `RealtimeDb`의 비우기 목록을 새 사실로 갱신합니다.
9. **보호자 화면 승인 시점**: `send` 안의 `aiFinal(…, true)`를 제브 `ALLOW` 뒤(기기로 보내는 시점)로 미룹니다. 이미 그 자리에서 일어나므로 코드 변경은 제브 대기가 `send` 앞에 끼는 것뿐입니다. 막힌 구간은 승인 거짓으로 남아 12 중계 · 27 기록 · PDF에서 이미 가려집니다.
10. **승인 원문 생략**: `LiveContentRegistry`(또는 좁은 포트)를 `SessionExecutor.Services`에 연결해 `matchApproved`의 `MATCHED`일 때만 제브를 건너뜁니다. 연습 쪽 일치(`pendingPracticeSpeech.matches`)는 지금처럼 먼저 합니다. `UNKNOWN` · `NOT_MATCHED`는 제브를 거칩니다. 두 일치 모두 ASCII 공백 제거만 하는 정규화이고 `SeedDictionary.normalize`(NFKC 등)와 섞지 않습니다. 건너뛴 경우에도 `guardrail_event`를 `APPROVED_VERBATIM_SKIPPED`로 남깁니다.
11. **기동 점검**: `RealtimeStartupChecks.problems`에 제브 규칙(모드 × 프로필 × 키)을 더합니다. `Inputs` 레코드는 필드를 늘리지 않고 오버로드를 유지하는 기존 패턴을 따릅니다. 허용 호스트는 Luna 선례대로 코드 상수(`JevHosts.ALLOWED_HOSTS`) + 계약 문서(`ai-io.md` 10절) · 시험 대조입니다. 모델 이름은 설정의 고정 버전이고 `jev-latest` 같은 이동 별칭이면 기동 거부입니다.
12. **지표 · 로그**: 이름은 `RealtimeMetrics`에 상수 + 메서드로 더하고(`mujoong.guardrail.*` 접두어) 히스토그램 버킷을 `application.yml`에 더합니다. 이름 · 종류는 이 단위 설계서(`docs/units/u13/design.md`)에 적습니다(요구사항 Q3 답). 새 사건 코드는 만들지 않고 `appError(Component.OUTPUT_GATE, "<사유 코드>", Level)`의 사유 코드만 씁니다(`contract-model` 변경 없음, `log-hygiene-check` 허용 표 변경 없음).
13. **프로필 · 인프라**: 검증 스택(`compose.ci.yml`)은 `dev` 프로필이라 기본 `STUB`으로 영향이 없습니다. `image-checks.sh` ④의 demo 더미 기동은 실행 때 만든 가짜 `JEV_API_KEY`를 쓰고 가짜 호스트로 보내지 않습니다(`mujoong.provider.url`처럼 가짜 공급자 주소를 쓰는 방식은 Code Generation에서 확인). `render-env`의 realtime `JEV_API_KEY`를 demo 필수로 바꾸고 RUNBOOK에 키를 먼저 넣는 순서를 적습니다(R-M9).
14. **정본 두 군데의 정정**: `contracts/ai-io.md` 10.3 재시도 분류 표에 제브 행("재시도 없음", 시간 초과는 막힘), 계약 스키마 예시의 판 이름을 `jev-guardrail-v1`로 고칩니다. 편차는 `(U13-a)`부터 `contracts/deviations.md`에 적습니다(U13이 닫은 질문: 모드 키, 조각 처리, 재지시 문구).
15. **시험 설계**: 제브 대역은 시험이 손으로 푸는 `CompletableFuture`(`FakeJudgeClient` 선례), 실제 800ms를 기다리지 않습니다. 통합 시험은 `segment-quiet`를 운영값 800ms로 둡니다. 태그 `u13`, 범위 파일 `backend/coverage-scope/u13.txt`(앞 유닛 코드가 대부분인 `SessionExecutor`는 목록에 넣지 않고 사유를 적음, u11.txt 방식). 증거는 테스트 대역 · 합성 대화 · 실제 API · 실제 기기로 나눠 보고합니다.
16. **traceability**: 이 기록은 스토리 단계를 건너뛰어 상위 ID는 이 기록의 FR / NFR이고 원래 기록의 ID는 '원 FR6.1'로 구분합니다. 시험 · 문서 정비 같은 작업 방식 요구(FR8.1 · FR8.2)는 업무 규칙(BR)을 만들지 않고 N/A로 추적해 Code Generation 계획에서 다룹니다.
17. **검사 중 도착 조각 = 모았다가 이어서 검사 (Q2 답 B)**: 검사 중 도착한 새 출력 조각은 `SegmentBuffer`의 새 구간으로 모읍니다(봉인된 앞 구간은 검사 중이라 별개). 새 구간이 봉인되면 앞 검사가 끝날 때까지 **대기열 1칸**에 봉인된 구간을 보관합니다. 앞 구간의 AI 행은 `PARTIAL`로 두고 새 구간은 새 AI 행으로 엽니다(지금 `TranscriptWriter.aiStart`는 열린 행을 재사용하므로, 동시에 열린 AI 행 둘을 구분하도록 구간 열쇠로 묶는 변경이 필요하다. 세부는 설계서에서 정한다). 대기열이 찼는데 또 구간이 봉인되면 새 구간은 안전 쪽으로 폐기하고 사유 코드 `GUARDRAIL_QUEUE_FULL`을 남긴다. 앞 검사가 막힘이면 재지시로 공급자 출력 세대가 오르므로 대기 중 구간은 낡은 세대로 무효 · 폐기한다. 앞 검사가 `ALLOW`이면 보낸 뒤 대기 구간을 꺼내 검사하고, 꺼낼 때 검사표의 세대 · 연결 확인을 다시 한다.

A. Accept assumptions
B. Convert to follow-up questions

[Answer]: A. Accept assumptions

## Consolidated Summary Confirmation

- Q1 설정 키: 키 하나 `mujoong.guardrail.jev.mode` = `REAL` · `STUB` · `OFF`. 기본값은 demo · prod = `REAL`(다른 값이면 기동 거부), dev = `STUB`. `REAL`인데 `JEV_API_KEY`가 없으면 어느 프로필이든 기동 거부.
- Q2 검사 중 도착한 새 출력 조각: 모았다가 앞 검사가 끝난 뒤 이어서 검사한다(대기열 1칸). 앞 응답의 AI 행은 `PARTIAL`로 유지하고 새 구간은 새 행으로 연다. 대기열이 차면 새 구간은 폐기한다.
- Q3 재지시 문구: 서버 지시에 대한 응답이면 같은 템플릿 지시를 다시 보내고, 자유 응답이면 새 템플릿 `RETRY_FREE_TURN`을 `InstructionTemplates`에 더한다. 새 승인 문구는 만들지 않는다.
- 가정 17개를 그대로 받아들인다(제브 단계 구조, 검사표 · 늦은 ALLOW 무효 조건, 전사-PCM 일치의 한계, 새 말 시작 신호, 막힘 처리 · 턴 단위 재지시, 연습 "차단됨" 이벤트, `guardrail_event` V6 칸, 삭제 순서 185, 보호자 화면 승인 시점, 승인 원문 생략 입구, 기동 점검 · 허용 호스트 · 모델 고정, 지표 · 로그, 프로필 · 인프라, 계약 정정과 편차 `(U13-a)`, 시험 설계, traceability, 대기열 1칸).

Does this all look correct before I generate the functional design artifacts?

- Looks correct
- Request changes

[Answer]: Looks correct
