# 제브 어댑터 선 형식 정정 — 요구사항 명세

> 작업 기록 `261017-jev-adapter-format` · Requirements Analysis · 2026-10-17 · 범위 `bugfix` · 깊이 Standard · 시험 전략 Standard
>
> 이 문서는 앞 기록 `261017-u13-jev-guardrail`(U13 제브 게이트)의 요구사항 중 **공급자와 주고받는 선 형식** 한 곳을 제브 공식 문서에 맞게 바로잡는 요구를 정리한다. 새 업무 요구사항은 만들지 않았다. 출처가 서로 다르면 다음 순서로 따른다.
> 1. 제브 공식 문서(선 형식의 정본): [quickstart](https://docs.typesafe.ai/introduction/quickstart) · [API](https://docs.typesafe.ai/api)
> 2. 앞 기록의 승인된 요구사항 · 설계(`261017-u13-jev-guardrail/…/requirements.md`, `functional-design/rules.md`)와 AI 동작 명세 v0.2 §10.2(판정 규칙)
> 3. 이 기록의 처음 요청(임시 문턱 0.5), 계약 파일 `contracts/`, 팀 규칙(`team.md` · `project.md`)
> 4. 이 단계의 답(`requirements-analysis-questions.md` Q1 ~ Q3, 가정 확인, 요약 확인)
>
> 앞 기록 ID는 `원 FR2.1`처럼 "원"을 붙여 구분한다. 이 문서의 `FR{n}` · `NFR{n}`은 이 작업 기록 안의 추적 키다. 코드 사실은 이 기록의 코드 스캔(`inception/reverse-engineering/developer-scan.md`)과 공유 CodeKB(`aidlc/spaces/default/codekb/mujungi/`)에서 왔다.

## 의도 분석

- **문제:** 앞 기록에서 만든 `JevGuardrailClient`는 공급자 형식을 알 수 없는 상태에서 가정한 형식(`POST /v1/guardrail/check`, `candidateText` 등 본문, `decision` · `categories` · `policyFlags` 응답)을 썼다. 제브 공식 문서는 `POST https://api.typesafe.ai/v1/systemone`, 요청 `state` · `model` · `questions`, 응답 `answers.<질문 ID>.choice` · `probabilities` · `confidence`다. 그대로 실호출하면 거의 확실히 실패한다. 가짜 제브 시험은 가정한 형식만 확인하므로 이 어긋남을 못 잡았다.
- **목표:** 어댑터가 공식 형식으로 요청하고, 응답을 기존 내부 판정(`ALLOW` · `BLOCK` · `REVIEW` · `FAILED`)으로 바꾸게 한다. 판정은 명세 §10.2의 규칙(`PASS`가 1순위이고 확률이 문턱 이상일 때만 통과)을 따른다. 문턱은 사람이 정한 임시 시험값 0.5이고 설정 키로 둔다.
- **종류 · 범위:** 버그 수정(브라운필드). 바뀌는 곳은 `guardrail` 패키지의 어댑터 · 요청 구성 입력 · 정책 리소스 · 설정과 그에 묶인 시험 · 계약 · 문서 · 실호출 도구다. 게이트 단계의 위치 · 상태 기계 · 재지시 규칙 · 기록 표 · 지표는 바꾸지 않는다.
- **복잡도:** 중간. 외부 공급자 형식 변경이라 변환 한 곳(`convert`)과 요청 구성(`requestBody`)이 중심이지만, 정책 리소스 새 판 · 설정 키 · 계약 대조 시험이 함께 움직인다. 공식 문서가 설명하지 않는 부분(오류 본문, 고정 버전 모델 요청, 지연)은 실호출로만 확정할 수 있다.
- **사용자 가치:** 실제 제브를 연결했을 때 검사가 동작한다. 형식이 틀려 모든 검사가 막힘(`FAILED`)으로 끝나 아이가 대화를 못 듣는 일을 막고, 판정 문턱을 설정으로 조정할 수 있게 한다.

## 기능 요구사항

### FR1 공식 호출 형식 (원 FR2.1 · 원 FR1.2)

**FR1.1 경로 · 인증 · 본문 뼈대** — Must · 출처 제브 공식 문서, 가정 2
- 어댑터는 `POST https://api.typesafe.ai/v1/systemone`로 한 번 호출한다(경로는 코드 상수, 호스트는 기존 허용 호스트 상수 `JevHosts`). 헤더는 `Authorization: Bearer <키>`와 `Content-Type: application/json`이다. 키는 호출 때마다 환경에서 읽고 보관 · 기록하지 않는다. 본문은 `state` · `model` · `questions` 세 필드다.
- 재시도 없음, `jev-budget` 800ms 상한, 리다이렉트 · 프록시 미사용, 응답 64KiB 상한, 허용 호스트 직전 확인은 앞 기록 BR2.1 · BR2.2 · BR2.8 그대로다.
- 완료 기준: 가짜 제브 서버가 위 경로 · 메서드 · 헤더 · 본문 필드 집합을 정확히 받는다. 옛 경로 `/v1/guardrail/check`는 코드와 시험 어디에도 남지 않는다.

**FR1.2 `state`** — Must · 출처 가정 3, 앞 기록 BR2.4 · BR2.5
- `state`는 객체이고 필드는 검사할 후보 문장 하나와 익명 최근 맥락 3턴 목록(발화 주체 · 문장)이다. 이름 치환 · 식별자 없음 · 길이 상한 · 지시문은 데이터 필드에만 넣는 현재 규칙(`GuardrailRequestFactory`가 만든 내부 요청)을 그대로 지킨다.
- 완료 기준: 본문에 아이 이름 · 식별자 필드가 없고, 후보 문장 안의 지시문이 `questions`나 `instructions`를 바꾸지 못한다(격리 시험).

**FR1.3 질문 구성** — Must · 출처 Q1(A) · Q3(A), 명세 §10.2
- `questions`는 `choice` 질문 하나(질문 ID는 고정 이름 하나)다. `instructions`는 정책 리소스의 한국어 검사 질문이고 `criteria`는 라벨 → 설명 맵이다. 선택지는 7개 범주 `DIAGNOSIS` · `TREATMENT` · `SCORE` · `CAUSE` · `GENERALIZATION` · `EMOTION_CLAIM` · `PRESSURE`와 `PASS`, 모두 8개다. 맥락 규칙(종료 의사 무시 · 정답 선공개 · 코치 전환 · 직접 인용 등)은 검사 질문 문구에 들어간다.
- 검사 질문에는 "아이가 직접 한 말을 인용 부호 안에 그대로 옮긴 것은 금지가 아니다"(명세 §10.2)가 들어간다.
- 완료 기준: 가짜 서버가 받은 `questions`의 선택지 라벨이 정확히 8개이고 `PASS`를 포함한다. 문안은 코드 상수가 아니라 정책 리소스에서 온다(FR4).

### FR2 응답 변환 · 판정 규칙 (원 FR1.2 · 원 FR2.2)

**FR2.1 응답 검증** — Must · 출처 가정 6
- 다음 중 하나면 `INVALID_JSON`(막힘)이다: 응답이 JSON 객체가 아님, 질문 ID의 답이 없음, `type`이 `choice`가 아님, `choice`가 보낸 선택지에 없음, `probabilities`가 숫자 맵이 아니거나 값이 0 ~ 1 밖, `PASS` 항목이 없음, 확률 합이 1에서 0.001 넘게 벗어남.
- 완료 기준: 위 각 경우를 가짜 서버로 시험하고 모두 호출 1회 · 재시도 없음 · `FAILED/INVALID_JSON`이다.

**FR2.2 판정 규칙** — Must · 출처 명세 §10.2, 가정 4, 처음 요청
- `PASS`가 1순위이고 `PASS` 확률이 문턱 **이상**이면 `ALLOW`. 다른 라벨이 1순위면 `BLOCK`(`reasonCode` = 그 라벨). `PASS`가 1순위지만 문턱 미만이면 `REVIEW`(기기로 나가지 않음, `reviewNeeded`). 위 조건 밖에는 자동 `ALLOW`가 없다.
- 1순위는 `choice` 필드로 읽는다. `probabilities`의 최댓값과 `choice`가 다르면 `INVALID_JSON`이다.
- 완료 기준: 문턱 직전(0.499…) · 정확히 0.5 · 직후, `PASS`가 1순위가 아닌 경우, 동률 경계를 시험한다.

**FR2.3 내부 값 채우기** — Must · 출처 Q2(A), 가정 6
- `reasonCode`는 막힘이면 고른 라벨, 문턱 미만 `REVIEW`면 `PASS_BELOW_THRESHOLD`다(기존 대문자 정규식을 지킨다). `policyVersion`은 요청에 쓴 정책 판 이름이다(응답에 없다). 확률은 `guardrail_event`에 저장하지 않는다.
- `categories`에는 막힘이면 고른 라벨 하나, 그 밖에는 빈 목록을 둔다. `policyFlags`는 비어 있다(공식 응답에 없음).
- 완료 기준: 소비자(`GuardrailCoordinator` · `GuardrailEventRecorder` · 지표 · `GuardrailBlockedEvent`)와 `guardrail_event` 칸이 바뀌지 않고, 정규식 · NOT NULL 제약을 모두 만족한다.

**FR2.4 오류 · 실패 매핑** — Must · 출처 가정 7, 앞 기록 BR2.1
- 비 2xx 응답(400 · 401 · 413 · 415 · 422 · 429 · 529 포함)은 모두 `TRANSPORT_ERROR`이고 재시도하지 않는다. 429 · 529의 지수 백오프 권고는 따르지 않는다(시간 초과 = 막힘). 오류 본문은 읽어 두지 않고 로그 · 기록에 쓰지 않는다. 시간 초과는 `TIMEOUT`, 허용 밖 호스트 · 주소 오류는 `TRANSPORT_ERROR`, 빈 키 · 모델 규칙 위반은 `UNAVAILABLE`이다(보내기 전 거절, 앞 기록 그대로).
- 응답에 정책 판이 없으므로 `POLICY_MISMATCH`는 어댑터가 더 이상 만들지 않는다. 열거값은 소비자 호환을 위해 남기고 '사용하지 않음'을 문서에 적는다.
- 완료 기준: 상태별 가짜 서버 시험이 모두 `TRANSPORT_ERROR`이고 호출 1회다.

### FR3 문턱 설정 (원 FR1.3)

**FR3.1 설정 키** — Must · 출처 처음 요청, 가정 5
- 설정 키 `mujoong.guardrail.jev.pass-threshold`(환경 변수 `JEV_PASS_THRESHOLD`, 기본값 0.5, 허용 범위 0 초과 1 이하)를 `GuardrailProperties` · `application.yml`에 둔다. 값은 코드에 박지 않는다. 시간 값이 아니므로 `timing.md`에는 두지 않는다.
- 완료 기준: `ConfigKeyContractTest`가 `ai-io.md` 10.11 표와 키를 대조해 통과한다. 이 키를 만드는 시험 호출부(`new GuardrailProperties(...)`)가 모두 맞춰진다.

**FR3.2 임시 시험값 표시** — Must · 출처 처음 요청, R-M10
- `contracts/ai-io.md` 10.11과 `contracts/deviations.md`의 새 편차 행에 0.5가 '임시 시험값(AI 성능 시험으로 확정)'이라고 적는다. 실호출 보고서에도 같은 표시를 남긴다.
- 완료 기준: 계약 문서에 임시 표시와 값이 있다. 문턱을 바꾸는 방법(설정 키만)이 RUNBOOK `jevLiveTest` 절에 적혀 있다.

**FR3.3 값 검증** — Should · 출처 가정 5에서 파생
- 허용 범위 밖(0 이하, 1 초과, 숫자 아님) 값은 REAL 모드의 기동 점검이 거부한다. STUB · OFF는 제브를 부르지 않으므로 영향이 없다.
- 완료 기준: `GuardrailModeStartupChecksTest`에 문턱 규칙 칸이 더해진다.

### FR4 정책 리소스 새 판 (원 FR2.3)

**FR4.1 `policy-v2.json`** — Must · 출처 Q3(A)
- `guardrail/policy-v2.json`(판 이름 `jev-guardrail-v2`)을 만든다. 한국어 검사 질문(`instructions`), 라벨별 설명(`criteria`: 7개 범주 + `PASS`), 모드 목록, 출처 메모를 담는다. 문안 변경은 판을 올리는 일이다.
- 완료 기준: 리소스 구조 시험(`LiveContentRegistryTest` 등)이 새 판을 확인하고 선택지 라벨 집합이 FR1.3과 같다.

**FR4.2 이력 보존과 소비자 정합** — Must · 출처 Q3(A), 코드 스캔 위험 3
- `policy-v1.json`은 지우지 않고 이력으로 남긴다. 이 리소스를 읽는 곳(`LiveContentRegistry` · 어댑터 구성 · 기동 점검 · 시험)이 새 판을 가리키게 하고, 7개 범주를 단언하는 기존 시험(`LiveContentRegistryTest` · `U8RealtimeStartupChecksTest` · `U8ContractSchemasTest`)은 새 판 사실로만 바꾼다(느슨하게 하지 않는다). `contracts/schemas/guardrail-*.schema.json`의 `categories` enum은 7개 그대로다.
- 완료 기준: `:realtime-activity:check`와 계약 대조 시험이 통과한다.

### FR5 계약 · 실호출 도구 · 문서 (원 FR8.2)

**FR5.1 계약** — Must · 출처 R-M10, 가정 9
- `contracts/ai-io.md`에 공급자 선 형식(경로 · 인증 · 요청 · 응답 · 판정 규칙 · 문턱)을 한 절로 적고, `deviations.md`에 이 정정의 행을 더한다(앞 기록 `(U13-a)` · `(U13-b)` 다음 글자). 내부 형식 스키마 예시는 새 규칙에 맞게 정정한다.
- 완료 기준: `ContractFilesTest` · `ConfigKeyContractTest`가 통과하고, 옛 경로 이름이 계약에 남지 않는다.

**FR5.2 `jevLiveTest`** — Must · 출처 가정 11, R-F14 · R-F15
- 실호출 도구가 어댑터를 통해 새 형식으로 호출한다. 보고서에는 표본 이름 · 판정 · 고른 라벨 · `PASS` 확률 · 응답 `model` · 지연만 남기고(후보 문장 · 키 · 주소 없음) 임시 문턱 표시를 적는다. 키가 없으면 지금처럼 건너뛴다. 기본 `test` · `check` · CI · `verify.sh`에는 들어가지 않는다.
- 완료 기준: `JevLiveTaskIsolationTest`가 계속 통과하고 키 없는 실행이 `SKIPPED_NO_KEY`로 끝난다.

**FR5.3 문서** — Must · 출처 가정 9
- `docs/units/u13/design.md` · `report.md`와 `infra/RUNBOOK.md` 8.8절을 새 형식에 맞춘다. 요구사항 문서 · 앞 기록의 승인된 설계 문서는 고치지 않는다(편차 표에 적는다).
- 완료 기준: 문서에 옛 경로 · 옛 응답 필드 이름이 '형식 가정'으로 남아 있지 않다.

### FR6 시험 (원 FR8.1)

**FR6.1 표적 회귀** — Must · 출처 `bugfix` 범위 바닥, 가정 10
- `JevGuardrailClientTest`를 공식 형식 기준으로 다시 쓴다: 경로 · 메서드 · 인증 · 본문 구조 · 판정 규칙 경계 · 모든 비정상 응답 · 오류 상태 · 키 비노출 · 호출 1회. 이 시험이 옛 형식으로 통과하던 어긋남을 막는 회귀다.
- 완료 기준: 새 시험이 옛 어댑터에서는 실패하고 고친 어댑터에서는 통과한다(경로 · 본문 구조 단언으로 확인).

**FR6.2 기존 시험 정합** — Must · 출처 R-F2
- 형식에 묶인 기존 시험(`GuardrailConfigurationTest` · `GuardrailRequestFactoryTest` 중 영향 부분 · `TimeLiteralScanTest` 허용 목록의 어댑터 `.orTimeout` 줄 등)은 새 사실로만 바꾼다. 하한 · 기존 단언 · ArchUnit · 계약 대조 시험을 낮추거나 끄지 않는다.
- 완료 기준: `:realtime-activity:check`와 `:core-api:check`가 실패 0 · 건너뜀 증가 0으로 통과한다.

## 비기능 요구사항

| ID | 요구 | 판정 방법 | 출처 |
|---|---|---|---|
| NFR1 | **실패 시 막힘 유지**: 형식 오류 · 오류 상태 · 시간 초과 · 문턱 미만 · 1순위가 `PASS`가 아님은 모두 재생하지 않는다. 자동 `ALLOW`는 FR2.2의 조건을 모두 통과한 경우뿐이다 | FR2 시험 전체, 앞 기록 `GuardrailTurnIT` 통과 | 원 NFR2 |
| NFR2 | **개인정보 최소화 유지**: 제브에는 후보 문장과 익명 최근 3턴만 보내고 식별자 · 키를 보내지 않는다. 로그 · 기록 · 지표 · 보고서에 후보 문장 원문 · 키 · 주소 · 오류 본문을 남기지 않는다 | 요청 본문 키 집합 시험, 키 비노출 시험, `jevLiveTest` 보고서 필드 검사 | 원 NFR3 · R-F14 |
| NFR3 | **시간 값 유지**: 제한 시간은 `mujoong.timing.guardrail.jev-budget`(800ms)에서만 읽고 새 시간 키를 만들지 않는다. 문턱은 시간 값이 아니다 | `TimeLiteralScanTest` · `ConfigKeyContractTest` 통과 | 원 NFR1 · R-F7 |
| NFR4 | **회귀 없음**: 게이트 단계 · 상태 기계 · 재지시 · 늦은 ALLOW 무효 · 대기열 · 승인 원문 생략 · 기록 표 · 지표 이름은 바뀌지 않고, 기존 U1 ~ U13 시험이 단언 변경 없이(새 형식 사실 반영 외) 통과한다 | `:realtime-activity:check` · `:core-api:check` · `verify.sh` | 원 NFR6 |
| NFR5 | **시험 · 하한**: test-after, Standard 전략, 모듈 줄 80% · `u13` 범위 줄 80%를 유지한다. 시험 · CI · 검증은 가짜 제브만 쓴다. 실호출은 `jevLiveTest`이고 사람이 키를 넣고 한다 | `check` · 범위 측정 · `verify.sh` PASS | 원 NFR5 · R-F1 · R-F2 |
| NFR6 | **증거 구분**: 결과는 테스트 대역 · 합성 대화 · 실제 API(`[실호출-합성]`) · 실제 기기로 나눠 보고하고, 가짜 제브 통과를 실제 제브 성공으로 적지 않는다. 실제 형식 · 지연 · 분류 품질은 실호출 전까지 미확인이다 | 보고서 증거 표 | 원 NFR9 · R-M11 |

## 제약

- 시험 · CI · 검증 명령에서 실제 유료 AI 공급자를 부르지 않는다(R-F1). 키 · 토큰 값 · 실제 서비스 도메인을 코드 · 설정 · 시험 · 문서 · 커밋 · 로그에 쓰지 않는다(R-F3). 제브 허용 호스트는 외부 공급자 호스트 상수 한 곳(`JevHosts`)과 `ai-io.md`의 허용 호스트 줄로 대조한다(앞 기록 편차). 공급자 약관 · 아동 데이터 전달 허용 범위 확인(R-F15, `infra/RUNBOOK.md` 단계 4의 제브 칸은 비어 있음) 전에는 아동 데이터를 외부로 보내지 않는다.
- 비밀 로그 · 보고서 위생(R-F14): 후보 문장 · 아동 발화 원문 · 요청 원문을 남기지 않는다.
- `core-api`와 `realtime-activity`는 서로 코드를 참조하지 않는다(R-F5). 운영 코드는 시스템 시각을 직접 읽지 않는다(R-F6).
- 마이그레이션을 만들지 않는다(Q2 답 A). 새 비밀 환경 변수는 없다. `JEV_PASS_THRESHOLD`는 비밀이 아니다(R-M9 대상 아님).
- 이 기록은 U13이 아직 `main`에 병합되기 전의 `unit/u13-jev-guardrail` 브랜치에서 이어 작업하고 같은 PR에 들어간다. `aidlc/` 기록은 함께 커밋한다(팀 규칙).
- 단계 산출물은 파일 쓰기 도구로 저장한다(project.md 교정).

## Assumptions & Open Questions

### 가정

| 번호 | 가정 | 근거 |
|---|---|---|
| A1 | ID 다시 묶기: 이 기록은 새 FR · NFR 번호를 쓰고 앞 기록 ID를 '원 FR2.1'처럼 적는다. 앞 기록의 승인 문서는 고치지 않고 편차 표에 적는다 | 가정 확인 A, project.md 교정 |
| A2 | 호출은 `POST /v1/systemone`, Bearer 인증, `state` · `model` · `questions`, `choice` 질문 하나다. 새 HTTP 라이브러리 없음(JDK `HttpClient`) | 가정 확인 A, 제브 공식 문서 |
| A3 | `state`는 후보 문장 + 익명 최근 3턴을 담은 객체다 | 가정 확인 A, 앞 기록 BR2.4 · BR2.5 |
| A4 | 판정 규칙: `PASS` 1순위이고 확률 `>=` 문턱이면 `ALLOW`, 다른 라벨 1순위면 `BLOCK`, `PASS` 1순위지만 문턱 미만이면 `REVIEW` | 가정 확인 A, 명세 §10.2 |
| A5 | 문턱은 임시 시험값 0.5이고 설정 키 `mujoong.guardrail.jev.pass-threshold`(`JEV_PASS_THRESHOLD`)로 둔다. 코드에 박지 않는다 | 가정 확인 A, 처음 요청 |
| A6 | 비정상 응답은 `INVALID_JSON`이다. 응답에 정책 판이 없으므로 `POLICY_MISMATCH`는 만들지 않는다 | 가정 확인 A, 제브 공식 응답 |
| A7 | 비 2xx는 모두 `TRANSPORT_ERROR`이고 재시도하지 않는다(429 · 529 포함) | 가정 확인 A, 앞 기록 BR2.1 |
| A8 | `JEV_MODEL`은 고정 버전 규칙(`-latest` 거부)을 유지한다. 요청에서 고정 버전이 되는지는 실호출로 확인한다 | 가정 확인 A, 제브 공식 문서에 설명 없음 |
| A9 | 계약 · 문서 갱신 범위: `ai-io.md` 새 절, `deviations.md` 새 행, 내부 스키마 예시 정정, `design.md` · `report.md` · RUNBOOK 8.8 | 가정 확인 A |
| A10 | 시험: 표적 회귀 + 형식에 묶인 기존 시험 정합. 하한 · 기존 단언 약화 없음 | 가정 확인 A, R-F2 |
| A11 | `jevLiveTest`는 새 형식으로 호출하고 보고서에 판정 · 라벨 · `PASS` 확률 · 응답 `model` · 지연만 남긴다 | 가정 확인 A |
| A12 | 게이트 단계 · 상태 기계 · 재지시 · 늦은 ALLOW · 대기열 · 승인 원문 생략 · 모드 키 · 기동 규칙 · 안전 타이머 · 지표 이름은 바뀌지 않는다 | 가정 확인 A |
| A13 | 검증: 단계마다 `:realtime-activity:check`, 계약 · 설정 키를 건드리면 `:core-api:check`도, 끝에서 `verify.sh` 전체 · 이미지 점검을 한 번 | 가정 확인 A, 앞 Build and Test 교훈 |
| A14 | 브랜치는 `unit/u13-jev-guardrail`을 이어 쓴다 | 가정 확인 A |

### 열린 질문

1. **고정 버전 모델 요청:** 공식 문서가 응답에 `jev-1.13.0`을 보일 뿐 요청 `model`에 고정 버전을 넣을 수 있는지 설명하지 않는다. 실호출에서 확인하고, 안 되면 모델 규칙(앞 기록 BR2.3)을 사람이 다시 정한다.
2. **오류 본문 형식:** 문서에 없다. 이 정정은 본문을 읽지 않으므로 영향이 없지만, 실호출에서 실제 모양을 보고 필요하면 사유 코드만 더한다.
3. **문턱 확정:** 0.5는 임시 시험값이다. 실제 확률 분포와 오검출 · 미검출은 AI 성능 시험에서 정한다(`jevLiveTest` 보고서의 `PASS` 확률이 입력).
4. **`confidence` 의미:** 문서는 "확률에서 유도된 확신"이라고만 적고 식을 주지 않는다. 이 정정은 `confidence`를 판정에 쓰지 않는다.
5. **한국어 문안 성능:** 한국어 검사 질문과 라벨 설명이 제브에서 얼마나 잘 분류되는지는 실호출 · AI 성능 시험으로만 알 수 있다(AC2.1.10 · AC2.1.16, `[실호출-합성]`).
6. **실제 아동 데이터 전송:** 키를 넣는 순간 제브가 실제 아동 발화 후보와 최근 맥락을 외부 공급자로 보낸다. R-F15 확인 기록(RUNBOOK 단계 4) 전에는 키를 넣지 않는다. 이 정정으로 바뀌지 않는다.
7. **`team.md`의 낡은 문장:** "다음 번호는 core V8 · realtime V5"는 실제와 다르다(realtime은 V6까지 있고 다음은 V7). 기억 파일은 학습 절차로만 고친다.
8. **확률 저장:** 이 정정은 `guardrail_event`에 확률을 저장하지 않는다(Q2 답 A). 문턱 조정에 서비스 데이터가 필요해지면 realtime V7과 core-api 이력 단언 갱신이 필요하다.

## 범위 밖

- 게이트 단계의 동작(위치 · 상태 기계 · 재지시 · 대기열 · 늦은 ALLOW · 승인 원문 생략), 연습 흐름, 기록 표 `guardrail_event`, 지표 이름 변경
- 분류 체계를 명세 §10.2의 11개로 바꾸는 일(Q1 답 A: 7개 + `PASS`)
- 제브 실호출 확인과 분류 품질, 문턱 확정 → 사람이 키를 넣은 뒤(`jevLiveTest`, `[실호출-합성]`)
- demo 호스트 Parameter Store 키 · 주소 · 모델 입력과 배포(RUNBOOK 절차만 적음)
- 제브 SDK 도입, 429 · 529 재시도 구현(U13 BR2.1을 유지)
- `score` · `noul` 질문 형식 사용(이 정정은 `choice`만 쓴다)

## Sources

- 이 단계의 질문과 답: `requirements-analysis-questions.md` Q1(A) · Q2(A) · Q3(A), 가정 확인 A. Accept assumptions, 요약 확인 Looks correct
- 초기 설명(project-description.json): "U13 제브 어댑터 선 형식 정정(u13-jev-adapter-format). 배경: U13(261017-u13-jev-guardrail)의 JevGuardrailClient가 가정한 선 형식 … 이 공식 문서와 다르다 …"
- 제브 공식 문서: https://docs.typesafe.ai/introduction/quickstart · https://docs.typesafe.ai/api (2026-10-17 조회: 호출 · 인증 · 요청 · 응답 · 오류 상태)
- 앞 기록 `aidlc/spaces/default/intents/261017-u13-jev-guardrail/`
  - `inception/requirements-analysis/requirements.md`: FR1 ~ FR8 · NFR1 ~ NFR9
  - `construction/functional-design/rules.md`: BR1.1 ~ BR2.8 · BR9.1 ~ BR9.3, `entities.md` GuardrailVerdict · GuardrailRequest
  - `construction/code-generation/code-summary.md` · `construction/build-and-test/test-results.md` · `build-and-test-summary.md`
- 기존 코드 파악: 이 기록의 `inception/reverse-engineering/developer-scan.md`(공식 형식과 현재 코드 차이 JEV-F1 ~ F12), 공유 CodeKB `aidlc/spaces/default/codekb/mujungi/` 9개 파일의 이번 갱신절
- 정본 문서: `aidlc/spaces/default/knowledge/documents/15_기술명세_AI동작_1015/무중_AI동작명세_v0.2.md` §10.2(검사 질문 · 분류 · 통과 규칙), `무중_기술명세서_v0.3.md` §5.7(제브), `mujungi_Luna_JEV_guardrail_implementation_handoff_v2_2026-10-15.md` §3.1 ~ §3.3(범주 7개 · 정책 · 시스템 프롬프트 초안)
- 계약 파일: `contracts/ai-io.md` 10.3 · 10.11, `contracts/deviations.md`(U13-a · U13-b), `contracts/schemas/guardrail-request.schema.json` · `guardrail-result.schema.json`
- 팀 규칙: `aidlc/spaces/default/memory/team.md`, `project.md`(R-F1 · R-F2 · R-F3 · R-F5 · R-F6 · R-F7 · R-F9 · R-F13 · R-F14 · R-F15 · R-M5 · R-M9 · R-M10 · R-M11, 교정)
