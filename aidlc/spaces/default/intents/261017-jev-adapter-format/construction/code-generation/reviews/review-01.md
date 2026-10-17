## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-17T16:00:17Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Minor | backend/realtime-activity/src/main/java/kr/mujoong/realtime/guardrail/GuardrailProperties.java > record `passThreshold`(double) + GuardrailStartupRules.thresholdValid | `JEV_PASS_THRESHOLD`가 숫자가 아니면 Spring이 `GuardrailProperties` 레코드를 바인딩하다 먼저 실패할 수 있다. 그러면 기동 점검의 `pass-threshold(INVALID)` 경로(키 이름과 사유만 남김)에 닿기 전에 바인딩 예외가 난다. 예외 문구에 설정값이 실릴 수 있는지는 확인하지 못했다. 비밀 값은 아니지만 점검의 "값 없음" 약속과 어긋날 수 있다. | 비숫자 · 빈 문자열 env로 Spring 컨텍스트를 올려 어느 오류가 먼저 나는지 시험 하나로 확인한다. 바인딩 예외가 먼저면 `passThreshold`를 문자열로 받거나 기동 점검의 약속 범위를 문서에 적는다. | New |
| R-02 | Minor | backend/realtime-activity/src/main/java/kr/mujoong/realtime/guardrail/JevModels.java 모델 규칙 + contracts/ai-io.md > 10.12 열린 항목 | 공식 문서의 예시 `model`은 `jev-latest`(이동 별칭)인데 우리 규칙은 `-latest`를 거부한다. 고정 버전 이름을 제브가 받는지는 실호출 전까지 모른다. 거절되면 모든 검사가 `TRANSPORT_ERROR` 막힘이다(fail-closed이고 열린 항목으로는 적혀 있음). | 실호출(`jevLiveTest`) 전 사람이 제브 쪽에서 쓸 수 있는 고정 버전 이름을 확인해 `JEV_MODEL`로 넣게 RUNBOOK에 한 줄 더한다. 안 받으면 규칙 완화 여부를 사람이 정한다. | New |
| R-03 | Minor | backend/realtime-activity/src/main/resources/guardrail/policy-v2.json > `instructions` · `criteria` / aidlc/spaces/default/intents/261017-jev-adapter-format/construction/code-generation/code-summary.md > 4절 편차 6 | 정책 문안은 인계서 §3.2 맥락 규칙 8개 · 범주 설명 · §10.2 검사 질문(직접 인용 예외 포함)을 그대로 옮겼다. 지어낸 문안은 없다. 다만 §3.3 격리 문장은 필드 이름만 `state.candidate` · `state.context`로 바꿨고(요약에 적힘), 명세 §10.2의 분류 11개와 달리 7개 + `PASS`를 쓰는 기존 이원 체계가 이어진다. | 승인 화면에서 8라벨 체계를 앞 기록 결정으로 유지한다고 확인한다. 실호출 전에 AI 성능 시험 입력 문안으로 따로 승인받는다. | New |
| R-04 | Minor | contracts/deviations.md > (U13-b) 행 | (U13-b)가 예시 판 이름을 `jev-guardrail-v1`로 정정했다고 적어 두었고, 뒤의 (U13-c)가 `jev-guardrail-v2`로 올렸다. 행끼리 상호 참조 없이 읽으면 현재 판 이름이 v1처럼 읽힌다. | (U13-b) 행에 "(U13-c)로 v2로 대체됨" 한 구절을 더한다. | New |
| R-05 | Minor | aidlc/spaces/default/intents/261017-jev-adapter-format/construction/code-generation/code-summary.md > 5절 마지막 참고 | `U8RealtimeStartupChecksTest`가 단독 실행에서 Mockito 경고로 실패하는 순서 의존이 이미 있다는 사실을 요약이 정직하게 적었다. 이번 변경과 무관하나, 단독 재현하는 사람이 이번 정정의 회귀로 오해할 수 있다. | 후속 작업으로 시험 격리(경고 억제 또는 `@BeforeAll` 자가 부착)를 남긴다. 이번 병합을 막지는 않는다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| `./gradlew :realtime-activity:test -PincludeTags=u13 -Pmujoong.test.db=docker`(직접 실행) | 통과(출력 없음, 실패 0) | 테스트 대역(가짜 제브) 증거다. 실제 제브 성공이 아니다(R-M11). |
| grep `guardrail/check`·`candidateText`·`systemPrompt`·`policyFlags`·`policy-v1` (코드 · 시험 · 계약 · docs · infra) | 운영 · 시험 코드에 옛 형식 없음. 남은 것은 `policy-v1.json` 이력 파일, 내부 결과 스키마의 `policyFlags`(어댑터 출력 필드), deviations (U13-b) 이력 행뿐이고 codekb 문서는 옛 사실(이번 범위 밖) | 의도된 잔재다. R-04만 문서 정리 대상이다. |
| WebFetch `docs.typesafe.ai/api` · `quickstart` | `POST /v1/systemone`, Bearer, `state`·`model`·`questions`, choice `type`·`instructions`·`criteria`, 응답 `answers.<id>`의 `type`·`choice`·`probabilities`·`confidence`, 오류 400·401·413·415·422·429·529 | 요청 · 응답 읽기가 코드(`requestBody`, `convert`)와 일치한다. 응답 `type: choice` 요구도 quickstart 예시에 있다. |
| `git diff 421e1ad..HEAD` 기존 단언 대조 | 삭제 · 변경된 단언은 옛 형식(`systemPrompt`·`candidateText`·`policy-v1`) 기대의 치환이고, 새 단언이 더 많다 | R-F2 위반 없음. 하한 · 건너뜀 · `@Disabled` 변경 없음. |
| source-manifest 대조 | `writes` 29개 = diff 29개 | 빠짐 없음. |
| traceability 대조 | FR1.1 ~ FR6.2 · NFR1 ~ NFR6 모두 포함. 빠진 `FR8` · `FR8.1` · `FR8.2` · `NFR9`는 원 기록 ID이며 요구사항 본문에 '원 FR8.x'로만 쓰임 | 덮임. |

### 반박 시도 결과

**(1) 선 형식:** 요청 본문 최상위 키는 `model`·`state`·`questions` 셋이고 `questions.guardrail`은 `type=choice`·`instructions`·`criteria`(8라벨)이다. 후보 문장과 맥락은 `state`의 데이터 값이고 `instructions`에 섞이지 않는다. 경로 `/v1/systemone`과 Bearer는 맞다. 옛 형식은 코드 · 시험 · 계약에 남지 않았다.

**(2) 판정 안전성:** 자동 `ALLOW`는 `decide` 한 곳에서만 나오고 `PASS`가 1순위, PASS 확률이 문턱 이상, 다른 어느 라벨보다 엄격히 클 때뿐이다. `choice`가 최댓값 집합 밖이면 `INVALID_JSON`이다. 확률은 보낸 라벨 전부, 여분 키 없음, 숫자만, 유한, 0~1, 합 오차 0.001 이내로 검증한다. NaN · 무한대 · 음수 · 1 초과 · 누락은 막는다. 동률은 PASS면 `REVIEW`, 다른 라벨이면 `BLOCK`이다. 문턱은 `0 < x <= 1`이고 범위 밖이면 보내기 전 `UNAVAILABLE`이며 REAL 기동 점검도 거부한다. 우회 경로를 찾지 못했다. 단 R-01의 바인딩 순서는 확인되지 않았다.

**(3) 유출:** 비 2xx 본문은 `replacing(new byte[0])`로 버리고, 파싱 예외 메시지도 버리며, 어댑터는 아무것도 기록하지 않는다. `toString`은 키 · 주소 값을 내지 않는다. `jevLiveTest` 보고서 필드는 표본 이름 · 판정 · 라벨 · PASS 확률 · 사유 · 지연 · 응답 `model` · 문턱뿐이다.

**(5)(7) 정직성:** code-summary가 시험 순서 비엄밀, 셸 편집 절차 위반, 단독 실행 순서 의존, 보수적 검증 해석을 적었다. 모든 합격은 테스트 대역 증거로 표기했다. 열린 항목(고정 버전 모델 요청, 오류 본문, 합 허용 오차 0.001, 문턱 0.5 임시, `confidence` 의미)이 `ai-io.md` 10.12, report, RUNBOOK에 남아 있다.

### Summary

운영 코드는 공식 `systemone` 형식과 일치하고, 자동 `ALLOW` 경로는 `PASS` 1순위 + 문턱 이상 + 엄격 최대로 좁혀져 있으며, 비밀 · 원문 유출과 기존 단언 완화는 찾지 못했다. Critical · Major는 없고 Minor 다섯 건(바인딩 순서 확인, 모델 이름 확인, 문안 승인, 편차 행 정리, 시험 격리)은 승인 후 처리해도 된다. 모든 합격은 테스트 대역 증거이며 실제 제브 응답은 아직 미확인이다.
