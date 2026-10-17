# Requirements Analysis 질문 — 제브 어댑터 선 형식 정정

> 기준: 이 기록의 처음 요청(제브 공식 형식 `POST /v1/systemone`, 문턱 임시 시험값 0.5), 제브 공식 문서 [quickstart](https://docs.typesafe.ai/introduction/quickstart) · [API](https://docs.typesafe.ai/api), 코드 스캔 `inception/reverse-engineering/developer-scan.md`와 공유 CodeKB(`aidlc/spaces/default/codekb/mujungi/`), 앞 기록 `261017-u13-jev-guardrail`의 요구사항 · 설계(FR1 ~ FR8, BR1.1 ~ BR9.3).
> 이 기록은 새 업무 요구사항을 만들지 않는다. U13 제브 게이트가 공급자와 주고받는 선 형식 한 곳을 공식 문서에 맞게 바로잡는다(범위 `bugfix`). 그래서 정본끼리 부딪히거나 정본에 답이 없는 것만 묻고, 코드 사실과 바로 맞는 나머지는 맨 아래 가정 묶음으로 확인받는다.
> 이 범위(`bugfix`)에는 Functional Design 단계가 없으므로, 설계에 닿는 결정은 이 질문과 Code Generation 계획에서 끝낸다.

## Q1. 제브에 보낼 분류 선택지를 어느 체계로 할까요?

정본 문서끼리 다릅니다.
- **Luna · JEV 구현 인계서 v2 §3.2**(그리고 이미 구현된 `policy-v1.json`, 계약 스키마, 시험 3곳): 범주 7개 `DIAGNOSIS` · `TREATMENT` · `SCORE` · `CAUSE` · `GENERALIZATION` · `EMOTION_CLAIM` · `PRESSURE`. 역할 혼동 · 종료 무시 같은 맥락 규칙은 별도 `policyFlags`로 둠.
- **AI 동작 명세 v0.2 §10.2**: 금지 분류 10개 + `PASS` = 11개 `DIAGNOSIS` · `SCORE` · `TREATMENT` · `DEFICIT` · `GENERALIZE` · `MIND_READING` · `FORCE` · `PRESSURE` · `PERSONA` · `LEAK` · `PASS`. 겹치는 이름은 4개뿐입니다(`DIAGNOSIS` · `SCORE` · `TREATMENT` · `PRESSURE`).

제브 공식 형식에서는 선택지가 `choice` 질문의 `criteria`(라벨 → 설명)이고 응답은 1순위 라벨과 라벨별 확률입니다. 통과를 판정하려면 `PASS`라는 선택지가 반드시 필요합니다(어느 쪽을 골라도 `PASS`는 더합니다). 이 선택은 아이에게 말하기 전 검사 기준을 정하므로 제가 임의로 고르지 않습니다.

A. 7개 범주 + `PASS`로 합니다(8개 선택지). 이미 구현 · 승인된 `policy-v1.json`의 범주 이름과 계약 스키마(`categories` enum)를 그대로 쓰고 `PASS`만 선택지에 더합니다. 맥락 규칙은 검사 질문 문구에 넣습니다. 바뀌는 범위가 가장 작습니다. (추천)
B. 명세 §10.2의 11개로 바꿉니다(`PASS` 포함). 범주 이름이 4개만 겹치므로 정책 리소스 · 계약 스키마 enum · 시험 3곳 · `contracts/` 표기를 모두 바꿔야 하고, 인계서 v2의 범주 이름은 사라집니다.
C. 두 체계를 합쳐(합집합) 선택지를 씁니다. 선택지가 17개 안팎으로 늘어 라벨이 겹치는 뜻을 가르기 어렵습니다.
X. Other (please specify)

[Answer]: A

## Q2. 공식 응답에 없는 값(`reasonCode` · `policyVersion` · `categories` · `policyFlags`)과 확률을 어떻게 기록할까요?

공식 응답은 `answers.<질문 ID>.choice`(1순위 라벨), `probabilities`(라벨별 확률), `confidence`뿐입니다. 그런데 지금 내부 판정(`GuardrailVerdict`)과 기록 표 `guardrail_event`는 `reasonCode`(NOT NULL, 대문자 정규식) · `policyVersion`(NOT NULL)을 요구하고, 확률을 담을 칸은 없습니다. 칸을 더하려면 realtime 마이그레이션 V7이 필요하고 core-api의 이력 단언 시험(`FlywayConfIT` · `SchemaPrivilegeIT`)도 함께 고쳐야 합니다. 아이 발화 원문은 어느 쪽이든 기록하지 않습니다(R-F14).

A. 표는 바꾸지 않습니다. 막힘이면 `reasonCode`를 고른 라벨(예 `DIAGNOSIS`)로, 문턱 미만 REVIEW면 `PASS_BELOW_THRESHOLD`로 하고, `policyVersion`은 우리가 보낸 정책 판 이름을 기록합니다. 확률은 기록 표에 저장하지 않고 `jevLiveTest` 보고서(실호출 때 사람이 보는 곳)에 `PASS` 확률로 남겨 문턱을 정할 때 씁니다. (추천)
B. `guardrail_event`에 고른 라벨과 `PASS` 확률 칸을 더합니다(V7). 문턱 조정에 실서비스 데이터를 쓸 수 있지만 마이그레이션 · 삭제 참여자 · 시험 갱신이 늘고 이 정정이 버그 수정 범위를 벗어납니다.
X. Other (please specify)

[Answer]: A

## Q3. 검사 질문 문구와 선택지 설명은 어디에 두고 어떻게 관리할까요?

지금은 정책 리소스 `guardrail/policy-v1.json`의 `systemPrompt`(인계서 §3.3 초안) 한 덩어리를 요청에 `systemPrompt`로 보냅니다. 공식 형식에서는 이 내용이 `choice` 질문의 `instructions`(검사 질문)와 `criteria`(라벨 → 설명)로 나뉘어야 합니다. 명세 §10.2는 검사 질문을 "아래 문장이 6~12살 아이와 보호자에게 보이거나 들려도 되는가? … 아이가 직접 한 말을 인용 부호 안에 그대로 옮긴 것은 금지가 아니다."라고 적습니다. 기술명세서는 "문안 · 분류는 AI 성능 시험으로 정한다"고 합니다.

A. 정책 리소스를 새 판(`policy-v2.json`, 판 이름 `jev-guardrail-v2`)으로 만들어 검사 질문(`instructions`)과 라벨별 설명(`criteria`)을 한국어 문안으로 저장하고, 어댑터는 이 리소스를 읽어 요청을 만듭니다. 문안을 바꿀 때 코드를 안 고치고 판만 올립니다. `policy-v1.json`은 이력으로 남깁니다. (추천)
B. 문안을 어댑터 코드 상수로 둡니다. 가장 단순하지만 문안 변경이 코드 변경이 되고 판 관리가 사라집니다.
X. Other (please specify)

[Answer]: A

## Assumption Confirmation

아래는 제브 공식 문서 · 코드 사실 · 앞 기록의 승인된 규칙에 바로 맞는다고 보고 정한 가정입니다. 한 묶음으로 확인해 주세요.

1. **ID 다시 묶기**: 이 기록의 요구사항은 새 번호(FR1 ~, NFR1 ~)를 쓰고, 앞 기록의 ID를 '원 FR2.1'처럼 함께 적어 추적합니다. 앞 기록의 승인된 문서는 고치지 않고 이 기록의 설계 · 편차 표(`U13-c` 이후)에 바뀐 점을 적습니다.
2. **호출 형식**: `POST https://api.typesafe.ai/v1/systemone`(경로는 코드 상수, 호스트는 허용 호스트 상수), 헤더 `Authorization: Bearer <키>` · `Content-Type: application/json`, 본문 `state` · `model` · `questions`. 질문은 `choice` 하나(질문 ID는 고정 이름 하나). 키는 지금처럼 호출 때마다 환경에서 읽고 보관하지 않습니다. 새 HTTP 라이브러리는 더하지 않습니다(JDK `HttpClient` 유지).
3. **`state` 모양**: 문자열이 아니라 객체로 보냅니다. 검사할 후보 문장 필드 하나와 익명 최근 맥락 3턴 목록 필드(발화 주체 · 문장). 아이 이름 치환 · 식별자 없음 · 지시문은 데이터 필드에만 넣는 현재 규칙(`GuardrailRequestFactory`)을 그대로 지킵니다.
4. **판정 규칙(명세 §10.2)**: `PASS`가 1순위이고 `PASS` 확률이 문턱 이상이면 `ALLOW`. 다른 라벨이 1순위면 `BLOCK`(`reasonCode` = 그 라벨). `PASS`가 1순위지만 문턱 미만이면 `REVIEW`(기기로 나가지 않음, `reviewNeeded`). 문턱 비교는 `>=`입니다.
5. **문턱**: 사람이 정한 임시 시험값 0.5(50%)를 쓰고, 코드에 박지 않고 설정 키 `mujoong.guardrail.jev.pass-threshold`(환경 변수 `JEV_PASS_THRESHOLD`, 기본값 0.5, 허용 범위 0 초과 1 이하)로 둡니다. `GuardrailProperties` · `application.yml` · `contracts/ai-io.md` 10.11 · `contracts/deviations.md`(새 편차 행)에 '임시 시험값, AI 성능 시험으로 확정'이라고 적고 `ConfigKeyContractTest` 대조를 맞춥니다. 시간 값이 아니므로 `timing.md`에는 두지 않습니다.
6. **응답 검증과 실패 매핑**: 응답이 JSON 객체가 아니거나, 질문 ID의 답이 없거나, `type`이 `choice`가 아니거나, `choice`가 보낸 선택지에 없거나, `probabilities`가 숫자 맵이 아니거나 0 ~ 1 밖이거나, `PASS`가 없거나, 합이 1에서 0.001 넘게 벗어나면 `INVALID_JSON`입니다(막힘). 응답에 정책 판이 없으므로 `POLICY_MISMATCH`는 어댑터가 더 이상 만들지 않습니다(열거값은 소비자 호환을 위해 남기되 사용하지 않음 표시). `ALLOW`는 위 4번의 조건을 모두 통과한 경우뿐이고, 그 밖에는 자동 통과가 없습니다.
7. **오류와 재시도**: 비 2xx(400 · 401 · 413 · 415 · 422 · 429 · 529 포함)는 모두 `TRANSPORT_ERROR`이고, 429 · 529도 재시도하지 않습니다(앞 기록 BR2.1: 시간 초과 = 막힘, 재시도 없음). 오류 본문은 읽어 두지 않고 로그 · 기록에 쓰지 않습니다. 시간 상한 `jev-budget` 800ms · 응답 64KiB 상한 · 리다이렉트 미추종 · 프록시 없음은 그대로입니다.
8. **모델 이름**: `JEV_MODEL`은 지금 규칙(고정 버전, `-latest` 거부)을 유지합니다. 공식 문서는 응답에 `jev-1.13.0`을 보여 줄 뿐 요청에서 고정 버전을 지정할 수 있는지 설명하지 않으므로, 이는 **실호출로 확인할 열린 항목**입니다(`jevLiveTest` 보고서에 응답 `model`을 남김).
9. **계약 · 문서**: 내부 형식(`contracts/schemas/guardrail-*.schema.json`의 `decision` · `categories` · `reasonCode`)은 내부 소비자가 쓰는 값이라 가능한 한 유지하고 예시를 정정합니다. `contracts/ai-io.md`에는 공급자 선 형식(경로 · 요청 · 응답 · 문턱)을 한 절로 적고, `deviations.md`에 이 정정의 행을 더하며, `docs/units/u13/design.md` · `report.md`와 `infra/RUNBOOK.md`의 `jevLiveTest` 절을 새 형식에 맞춥니다.
10. **시험**: 버그 수정 범위의 표적 회귀를 둡니다. `JevGuardrailClientTest`를 공식 형식 기준으로 다시 쓰고(경로 · 인증 · 요청 구조 · 판정 규칙 경계 0.5 직전 · 직후 · 모든 비정상 응답 · 오류 상태 · 키 비노출), `GuardrailConfigurationTest` · `ConfigKeyContractTest` · `TimeLiteralScanTest` 허용 목록 등 형식에 묶인 시험을 맞춥니다. 기존 단언을 지우거나 느슨하게 하지 않고, 하한(범위 줄 80%)은 낮추지 않습니다. 모두 가짜 제브 서버만 씁니다(R-F1).
11. **`jevLiveTest`**: 어댑터를 통해 새 형식으로 호출하도록 맞추고, 보고서에 판정 · 고른 라벨 · `PASS` 확률 · 응답 `model` · 지연만 남깁니다(후보 문장 원문 · 키 · 주소 없음, R-F14). 키가 없으면 지금처럼 건너뜁니다.
12. **변하지 않는 것**: 게이트 단계의 위치 · 상태 기계 · 재지시 규칙 · 늦은 ALLOW 무효 · 대기열 · 승인 원문 생략 · 모드 키 · 기동 규칙 · 안전 타이머 · 지표 이름은 바꾸지 않습니다. 마이그레이션은 만들지 않습니다(Q2가 A일 때).
13. **검증**: 시험 전략 Standard, test-after. 단계마다 바꾼 쪽(`:realtime-activity:check`)을 돌리고, 계약 · 설정 키를 건드리면 `:core-api:check`도 돌립니다(앞 Build and Test의 교훈). 전체 `bash scripts/verify.sh --mode compose`와 이미지 점검은 Build and Test에서 한 번 돌립니다. 증거는 테스트 대역 · 합성 대화 · 실제 API(제브 실호출) · 실제 기기를 따로 적고, 실제 제브 호출은 사람이 키를 넣고 한 뒤에만 `[실호출-합성]`으로 보고합니다(R-M11).
14. **브랜치**: U13이 아직 `main`에 병합되기 전이라 `unit/u13-jev-guardrail`에서 이어서 작업하고 같은 PR에 들어갑니다. `aidlc/` 기록은 함께 커밋합니다.

A. Accept assumptions
B. Convert to follow-up questions

[Answer]: A. Accept assumptions

## Consolidated Summary Confirmation

- Q1 분류 체계: 7개 범주 + `PASS`(8개 선택지). 맥락 규칙은 검사 질문 문구에 넣는다.
- Q2 기록 방식: 표 변경 없음(마이그레이션 없음). `reasonCode` = 고른 라벨(문턱 미만 REVIEW는 `PASS_BELOW_THRESHOLD`), `policyVersion` = 보낸 정책 판 이름, 확률은 `jevLiveTest` 보고서에만.
- Q3 문안 위치: 정책 리소스 새 판 `policy-v2.json`(판 `jev-guardrail-v2`)에 검사 질문(`instructions`)과 라벨별 설명(`criteria`)을 한국어로 저장, `policy-v1.json`은 이력으로 보존.
- 가정 14개 수용: 호출 `POST /v1/systemone` · Bearer · `state`(객체) · `model` · `questions`(choice 하나), 판정 규칙(`PASS` 1순위 그리고 확률 `>=` 문턱이면 `ALLOW`, 다른 라벨 1순위면 `BLOCK`, `PASS` 1순위지만 문턱 미만이면 `REVIEW`), 임시 문턱 0.5를 설정 키 `mujoong.guardrail.jev.pass-threshold`로 두고 임시 시험값으로 표시, 비정상 응답은 `INVALID_JSON`, 비 2xx는 모두 `TRANSPORT_ERROR`(재시도 없음), 모델은 고정 버전 규칙 유지(요청에서 고정 버전이 되는지는 실호출로 확인할 열린 항목), 계약 · 문서 · 시험 · `jevLiveTest` 갱신, 게이트 동작은 바뀌지 않음, 브랜치는 `unit/u13-jev-guardrail` 유지.

Does this all look correct before I generate the requirements artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
