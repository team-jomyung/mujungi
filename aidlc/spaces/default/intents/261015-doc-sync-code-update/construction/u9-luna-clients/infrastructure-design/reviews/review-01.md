## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-16T01:26:57Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u9-luna-clients/infrastructure-design/cicd-pipeline.md > 2절 `skeleton` 행, 3절 | `LUNA_STARTUP` 사건을 "log-hygiene-check 허용 목록 갱신"만으로 해결한다고 적었다. 그러나 `infra/bin/log-hygiene-check:31-52`의 `ALLOWLIST_JSON`은 `EventCode.java`가 정본인 사건 코드 17종 고정 표이고, 표에 없는 event는 `:133`에서 위반(`.a += 1`)으로 센다. `test-host-tools.sh`가 이 표를 자바 정본과 대조한다(`:9-10`, `:30`). 따라서 `LUNA_STARTUP`을 넣으려면 공용 계약 모듈의 `EventCode`/`AttrKey`(와 그 문서)를 먼저 바꿔야 하고, 두 앱이 모두 그 코드를 내보내는 경로가 생긴다. 이 변경이 여기서는 빌드 · 시험 변경 목록에 없다. 빠뜨리면 main 푸시 `skeleton`의 로그 위생 단언 P2가 실패한다(PR `ci-gate`에서는 안 보이고 병합 뒤에야 드러난다). | `LUNA_STARTUP`이 `EventCode` 신규 코드인지 기존 코드(예: `STARTUP_RECOVERY`나 `APP_ERROR`)의 속성 재사용인지 정하고, 신규라면 `EventCode`/`AttrKey`, `log-hygiene-check` 표, `test-host-tools.sh` 대조, 관련 `contracts/` 문서를 같은 커밋에서 바꾸는 단계를 1절 빌드 표나 Code Generation 계획에 명시한다. 로컬에서 `verify.sh` P2까지 한 번 돌리는 확인도 적는다. | New |
| R-02 | Minor | cicd-pipeline.md > 4절 롤백 | 롤백을 "Parameter Store 값 삭제 → `render-env` → 앱 재기동"으로 적었다. 실제 RUNBOOK(`infra/RUNBOOK.md:537`)은 비밀 갱신 뒤 같은 SHA로 `DEPLOY`를 다시 실행해 컨테이너가 재생성되어야 반영된다고 한다. 재기동만으로는 env 파일이 바뀌어도 컨테이너 환경이 갱신되지 않을 수 있고, 팀 정본 롤백 경로(`deploy.yml mode=…`)와 어떻게 이어지는지도 없다. 또한 "삭제 시 빈 값"은 `render-env:11`의 OPTIONAL 동작에 기대는데 이 문서는 근거 줄을 대지 않았다. | 롤백 단계를 RUNBOOK의 키 갱신 절차(파라미터 변경 → 같은 SHA `DEPLOY`)와 맞춰 쓰고, 확인은 `LUNA_STARTUP lunaState=DISABLED_NO_KEY`로 유지한다. | New |
| R-03 | Minor | cicd-pipeline.md > 4절 1단계, traceability.json > NFR3.7 | R-F15 확인 기록 표를 "RUNBOOK에 적음"이라고 하고 NFR3.7을 `OK`로 추적하지만, 현재 `infra/RUNBOOK.md`에는 해당 표가 없다(`:238-239`는 문구만 있다). 설계 시점에는 아직 만들 일인 항목이 `OK`로 표시되어 있다. 절차형 통제(Q2-B)라는 사람 결정은 존중하며, 표가 Code Generation 산출물임을 분명히 하면 충분하다. | NFR3.7 target을 "Code Generation 단계에서 RUNBOOK에 표를 추가"로 고치거나 계획 단계에 등록한다. | New |
| R-04 | Minor | cicd-pipeline.md > 2절 취약점 행, 1절 2행 | `json-schema-validator` 3.0.6(`backend/gradle/libs.versions.toml:12`)이 `testImplementation`(`core-api/build.gradle.kts:40`, `realtime-activity/build.gradle.kts:32`)에서 `implementation`으로 옮겨지면 전이 의존성 전체가 `ci.yml` 206~216행의 HIGH · CRITICAL 게이트 대상이 된다. 로컬 1회 확인만 적었고, 걸릴 때의 대응(R-M7 예외 절차 vs 버전 조정)이 없다. 병합 직전 `ci-gate` 실패로 드러나는 위험이다. | 걸릴 경우 순서(먼저 버전 조정, 불가하면 `ci/security-exceptions.yml` 승인 절차)를 한 줄로 적는다. | New |
| R-05 | Minor | cicd-pipeline.md > 1절 3행 / 2절 첫 행 | "빌드 구성 시험(SD8)"이 `check`에 포함되어 `lunaLiveTest`가 `check`에 연결되지 않음과 기본 `test`의 고정 제외를 보증한다고 했으나, 시험이 어디에(어느 모듈, 어떤 태그로) 놓이는지 적지 않았다. build-logic 변경이라 두 앱의 `test`에 넣기 애매하고, 이 시험이 없으면 NFR5.1의 유일한 CI 방어선이 없다. 참고로 현재 `mujoong.java-conventions`(`:79-86`)는 `-PincludeTags`/`-PexcludeTags`만 다루며 고정 제외는 없다. | 시험 위치와 실행 경로(예: `check`에 걸리는 build-logic 시험)를 명시한다. | New |
| R-06 | Minor | cicd-pipeline.md > 3절 모델 env, Assumptions | `OPENAI_ANALYSIS_MODEL`/`OPENAI_JUDGE_MODEL`이 `compose.demo.yml`/`render-env`에 없으면 demo에서 설정할 수 없고 기본값 `gpt-6-luna`로만 동작한다. "demo 투입 때 정한다"로 미뤘으나, 사람이 Compose를 고치지 않고는 모델을 못 바꾼다는 점이 4절 투입 순서에는 빠져 있다. 범위 밖 결정은 존중한다. | 4절 투입 순서에 "모델 env 필요 시 Compose/render-env 변경 선행" 항목을 한 줄 추가한다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| 저장소 사실 대조(`render-env`, `compose.demo.yml`, `b-stack.sh`, `ci.yml`) | `OPENAI_API_KEY` 분배, CI 명령, trivy 단계는 문서 서술과 일치 | NFR3.6, 3.9, 5.4의 "새 인프라 없음" 주장은 사실로 확인됨 |
| `log-hygiene-check` 허용 표 대조 | `LUNA_STARTUP` 없음, 표는 EventCode 정본과 시험으로 묶여 있음 | R-01의 근거 |

### Summary

새 인프라가 없다는 library 단위 판단과 비밀 분배 · CI 명령 불변 주장은 저장소 사실과 맞는다. 가장 큰 구멍은 `LUNA_STARTUP`을 `log-hygiene-check` 표만 고치면 된다고 본 점으로, 실제로는 공용 `EventCode` 계약 변경이 따르며 병합 뒤 `skeleton`에서야 터진다. Major 1건(허용 한도 2건 이내)이고 나머지는 Minor라 READY이지만 R-01은 승인 전에 보강하는 편이 안전하다.
