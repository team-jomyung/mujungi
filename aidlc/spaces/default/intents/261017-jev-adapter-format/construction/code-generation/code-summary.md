**Collaborator:** developer-agent

# 제브 어댑터 선 형식 정정 — 코드 생성 요약

> 작업 기록 `261017-jev-adapter-format` · 브랜치 `unit/u13-jev-guardrail` · 범위 `bugfix` · 시험 방법 test-after · 전략 Standard · 2026-10-18
> 모든 합격은 **테스트 대역(가짜 제브 서버) 증거**다. 합성 대화 · 실제 API(제브 실호출) · 실제 기기 증거는 이번에 없다(R-M11). 실제 제브 응답 · 지연 · 분류 품질 · 고정 버전 모델 요청 · 오류 본문은 실호출 전까지 미확인이다.

## 1. 커밋

| 커밋 | 내용 |
|---|---|
| `d037b2e` fix(u13) | 문턱 설정 키 `mujoong.guardrail.jev.pass-threshold`(Step 1 · 2) |
| `44aad8f` fix(u13) | 정책 리소스 v2 · 질문 구성 · 문턱 기동 점검 · 어댑터 선 형식 · 판정 규칙 · `inspect`(Step 3 · 5 · 7의 운영 코드) |
| `900e910` test(u13) | 표적 회귀 · 판정 경계 · 정책 v2 · 문턱 기동 점검 · 실호출 도구 시험(Step 4 · 6 · 7의 시험) |
| `0f4f01e` docs(u13) | `contracts/ai-io.md` 10.12 · 스키마 예시(Step 8) |
| `46ee0e5` docs(u13) | `docs/units/u13/design.md` · `report.md` · `infra/RUNBOOK.md` 8.8(Step 8) |

## 2. 만든 · 고친 파일

운영:
- 새: `guardrail/JevQuestion.java`(질문 값 객체), `resources/guardrail/policy-v2.json`
- 고침: `JevGuardrailClient`(경로 · 본문 · `convert` · `decide` · `inspect` · `Inspection`), `GuardrailProperties`(`passThreshold` · `validThreshold`), `GuardrailStartupRules`(문턱 규칙 · 입력 5번째 칸), `GuardrailConfiguration`(`question`), `LiveContentResources`(정책 판 구조 · `BAD_INSTRUCTIONS` · `BAD_CRITERIA`), `RealtimeStartupChecks`(문턱 키 읽기), `GuardrailSupport`(기본 판 v2), `GuardrailVerdict`(Javadoc), `JevModels`(`wellFormed`), `application.yml`(`pass-threshold`), `backend/coverage-scope/u13.txt`(`JevQuestion` 추가)
- 안 건드림: `policy-v1.json`(이력, 코드가 읽지 않음), `GuardrailRequestFactory`, 기록 표 · 마이그레이션, `GuardrailCoordinator` 등 소비자

시험: 새 `GuardrailPolicyV2Test`, 다시 쓴 `JevGuardrailClientTest`, 고친 `JevLiveProbe` · `JevLiveTaskIsolationTest` · `GuardrailModeStartupChecksTest`(문턱 칸 4개) · `GuardrailConfigurationTest` · `LiveContentRegistryTest` · `U8RealtimeStartupChecksTest` · `ConfigKeyContractTest`(환경 변수 표에 `JEV_PASS_THRESHOLD`)

계약 · 문서: `contracts/ai-io.md`(10.3 · 10.4 · 10.11 · 새 10.12), `contracts/deviations.md`(`(U13-c)`), `contracts/schemas/guardrail-{request,result}.schema.json`(예시 판 이름 · BLOCK 예시 · 설명), `docs/units/u13/{design,report}.md`, `infra/RUNBOOK.md`

## 3. 정한 값 D1 ~ D11 이행

| 번호 | 이행 |
|---|---|
| D1 | `decide`: `PASS` 1순위이고 PASS와 같은 확률의 다른 라벨이 있으면 `REVIEW(PASS_TIED)`, 아니고 `PASS >= 문턱`이면 `ALLOW`, 문턱 미만이면 `REVIEW(PASS_BELOW_THRESHOLD)`, `PASS`가 아니면 `BLOCK`(사유 = 라벨, 동률이어도). `choice`가 최댓값 집합 밖이면 `INVALID_JSON`. 비교는 `double` `==` · `>=` |
| D2 | `state = {candidate, context[{speaker,text}]}`, `mode` 안 보냄 |
| D3 | 질문 ID `guardrail`(코드 상수), `{type: choice, instructions, criteria(8개)}` |
| D4 | `policy-v2.json`(`jev-guardrail-v2`: `source` · `instructions` · `criteria` 8 · `categories` 7 · `modes` 3). 문안은 명세 §10.2 · 인계서 §3.2에서 옮김(아래 편차 6) |
| D5 | `policy-v1.json` 보존, `GUARDRAIL_RESOURCE`는 v2. `GuardrailPolicyV2Test`가 v1이 적재 거부됨을 확인 |
| D6 | `passThreshold`(double), `${JEV_PASS_THRESHOLD:0.5}`, `0 < x <= 1`, REAL 기동 점검이 거부(`pass-threshold(INVALID)`), `>=` 비교 |
| D7 | 허용 오차 0.001(`PROBABILITY_SUM_TOLERANCE`, 주석에 '임시, 실호출로 조정'), 값 0 ~ 1, `confidence` 안 읽음 |
| D8 | 막힘: `reasonCode` = 라벨 · `categories` = `[라벨]`, REVIEW: 사유 코드 둘 · 빈 `categories`, ALLOW: `PASS`, `policyFlags` 항상 빈 목록, `policyVersion` = 요청의 판 |
| D9 | `everyNon2xxStatusIsATransportErrorWithOneCallAndTheErrorBodyNeverSurfaces`: 표식 문자열이 판정 · `Inspection` · 로그에 없음 |
| D10 | `ai-io.md` 10.12 · `design.md`에 `-latest` 거부 규칙과 고정 버전 요청 가능 여부를 열린 항목으로 적음 |
| D11 | `traceability.json` FR3 행에 '앞 기록에 대응 요구 없음, 처음 요청에서 신설' |

비 2xx · 재시도 없음 · 오류 본문 안 읽음: 비 2xx면 본문 수신기를 `BodySubscribers.replacing`으로 바꿔 본문을 버린다. `POLICY_MISMATCH`는 더 만들지 않고 열거값에 사용 안 함 Javadoc을 적었다.

## 4. 계획 대비 편차 · 정직하게

1. **시험 순서가 층별로 엄밀하지 않았다.** 어댑터 생성자의 질문 원천 타입(`Function<String,Optional<JevQuestion>>`)이 정책 리소스(Step 3)와 어댑터(Step 5) · 실호출 도구(Step 7)를 한 번에 엮어, 운영 코드 세 단계를 먼저 쓰고 시험을 한 묶음으로 쓴 뒤 같이 돌렸다. 이전 단계 시험을 따로 돌린 것은 Step 1 · 2(문턱 키 · `ConfigKeyContractTest`)뿐이다. 최종 합격은 전부 구현 뒤에 쓴 시험으로 확인했다.
2. **작업 절차 위반: 파일 쓰기 도구 대신 셸로 파일을 고쳤다.** Step 1에서 시험 호출부 `new GuardrailProperties(...)` 4곳을 `sed -i`로, `JevLiveProbe`의 문턱 읽기 추가와 `GuardrailConfigurationTest` 한 줄을 `python3`로 고쳤다. 내용은 단순 치환 · 추가였고 이후 `JevLiveProbe`는 파일 쓰기 도구로 다시 썼으며 `d037b2e`에 들어갔다. 그 밖의 모든 변경은 파일 도구로 했다.
3. **기동 점검 입력**: `GuardrailStartupRules.Inputs`에 `passThreshold`(문자열) 5번째 칸을 더했다. `null`이면 설정이 없는 것으로 보고 점검하지 않는다(4칸 생성자 유지로 기존 시험 · 생성 경로 무변경). Spring 생성자는 `environment.getProperty(pass-threshold)`를 읽는다.
4. **응답 검증을 더 엄격하게 구현했다(가장 보수적):** `probabilities`에 보낸 라벨 밖의 키가 있거나 값이 숫자가 아니면(문자열 · 불리언 · null) `INVALID_JSON`이다. 계획 문구('모든 8개 라벨을 가진 숫자 맵')의 해석이며 실제 제브가 추가 키를 주면 막힘이 된다 — 실호출에서 확인할 열린 항목이다.
5. **동률이면서 문턱 미만이면 `PASS_TIED`가 먼저다**(D1 문장 순서). 계획이 정하지 않은 겹침이라 계약 10.12 표에 순서를 적었다.
6. **정책 문안 편차:** `instructions`는 명세 §10.2 검사 질문(직접 인용 예외 포함) + 인계서 §3.2 맥락 규칙 불릿 8개 + 인계서 §3.3의 지시 격리 문장을 옮겼다. 격리 문장의 필드 이름만 새 형식에 맞춰 `state.candidate` · `state.context`로 바꿨다. §3.3의 '확인 요소 5개'와 JSON 출력 지시는 넣지 않았다(요청 지시에 없고 `choice` 형식에 맞지 않음). `criteria`는 인계서 §3.2 '위험 판단 예시. 원칙: …'를 그대로 두었고 `PASS`는 명세의 '해당 없음'이다.
7. **어댑터가 문턱이 범위 밖이면 보내지 않고 `UNAVAILABLE`로 막는다**(계획에 없던 방어, 기동 점검과 별개로 직접 생성한 경우 대비). `available()`도 거짓이 된다.
8. `LiveContentResources.Problem.BAD_SYSTEM_PROMPT`를 `BAD_INSTRUCTIONS`로 바꾸고 `BAD_CRITERIA`를 더했다. `GuardrailSupport.DEFAULT_POLICY_VERSION`을 `jev-guardrail-v2`로 올렸다(세션 고정 판은 이미 리소스 판을 따랐다).
9. **영향 목록의 시험 중 바꾸지 않은 것:** `U8ContractSchemasTest`(음성 예시가 정책 판 이름과 무관), `TimeLiteralScanTest`(`.orTimeout(limit.toNanos(), TimeUnit.NANOSECONDS)` 줄을 그대로 둠), `GuardrailRequestFactoryTest`(내부 형식만 봄). 계획 Step 2의 `unit-test-instructions.md`는 명령이 그대로 유효해 고치지 않았다.
10. `docs/units/u13/brief.md`는 승인된 지시서라 고치지 않았다(첫머리의 '형식 가정' 문장이 남아 있다). 요구사항 FR5.3의 범위(`design.md` · `report.md` · RUNBOOK)는 고쳤다.
11. 이 PC 비밀 스캔(`bash scripts/verify/a-secrets.sh`)이 `aidlc/.../261017-u13-jev-guardrail/construction/build-and-test/test-results.md`(앞 기록의 aidlc 문서, 이번에 만지지 않음)에서 키 형식 1건을 보고한다. 이번 변경 파일(코드 · 시험 · contracts · docs · infra)에서는 걸림이 없다.

## 5. 시험 결과(테스트 대역 증거)

| 실행 | 결과 |
|---|---|
| `:realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker` | BUILD SUCCESSFUL, **1,660개 통과 · 실패 0 · 건너뜀 0**(앞 기록 1,641개 → +19). JaCoCo 모듈 하한 통과 |
| `:core-api:check -Pmujoong.test.db=docker` | BUILD SUCCESSFUL, 1,561개 통과 · 실패 0 · 건너뜀 0 |
| `:contract-model:clean :contract-model:check` | BUILD SUCCESSFUL, 322개 통과 · 실패 0 |
| `u13` 태그 시험 | 188개(앞 169개) 통과 · 실패 0 |
| u13 범위 줄 커버리지(`-PincludeTags=u13 -PcoverageScope=u13`, 파일 32개) | **86.3%**(1,781 / 2,063줄, 하한 80% 이상). `JevGuardrailClient` 95.3% · `JevQuestion` 100% · `GuardrailStartupRules` 100% |
| `GuardrailModeStartupChecksTest` 14 · `GuardrailPolicyV2Test` 7(새) · `JevGuardrailClientTest` 18 · `JevLiveTaskIsolationTest` 8 | 모두 통과 |

참고: `U8RealtimeStartupChecksTest.theSpringConstructorReadsTheEnvironmentAndLoadsTheVersionResources`는 **단독으로** 다른 시험 없이 돌리면 Mockito 자가 부착 경고가 stderr에 찍혀 `err` 비어 있음 단언이 실패한다(Mockito를 처음 쓰는 시험이 되는 경우, 이번 변경과 무관한 시험 순서 의존). 전체 `check`에서는 통과한다.

## 6. 남은 위험 · 실호출 몫(`[실호출-합성]`, 사람이 키를 넣은 뒤)

- 실제 제브가 이 요청 형식(특히 `model`에 고정 버전 이름, 한국어 `criteria` 8개)을 받아들이는지, 오류 본문 모양, `probabilities`의 추가 키 여부, `confidence` 의미, 확률 합 허용 오차 0.001, 문턱 0.5(모두 임시)는 미확인이다. 형식이 어긋나면 모든 검사가 `TRANSPORT_ERROR`/`INVALID_JSON` 막힘이 되어 아이가 대화를 못 듣는다(fail-closed).
- 한국어 문안의 분류 품질과 지연(800ms 시한)은 실호출 · AI 성능 시험으로만 안다.
- R-F15 확인 기록(RUNBOOK 단계 4)이 비어 있어 키를 넣으면 실제 아동 발화 후보가 외부로 나간다. 그 전에는 합성 표본(`jevLiveTest`)만 쓴다.
- `verify.sh`(끝-끝) · 이미지 점검은 이번 호출에서 돌리지 않았다(Build and Test 몫).
