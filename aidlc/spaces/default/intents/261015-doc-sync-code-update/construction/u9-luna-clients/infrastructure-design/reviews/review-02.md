## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-16T01:30:06Z
**Iteration:** 2

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u9-luna-clients/infrastructure-design/cicd-pipeline.md > 2절 `skeleton` 행, 3절 | `LUNA_STARTUP` 사건을 "log-hygiene-check 허용 목록 갱신"만으로 해결한다고 적었다. 그러나 `infra/bin/log-hygiene-check:31-52`의 `ALLOWLIST_JSON`은 `EventCode.java`가 정본인 사건 코드 17종 고정 표이고, 표에 없는 event는 `:133`에서 위반(`.a += 1`)으로 센다. `test-host-tools.sh`가 이 표를 자바 정본과 대조한다(`:9-10`, `:30`). 따라서 `LUNA_STARTUP`을 넣으려면 공용 계약 모듈의 `EventCode`/`AttrKey`(와 그 문서)를 먼저 바꿔야 하고, 두 앱이 모두 그 코드를 내보내는 경로가 생긴다. 이 변경이 여기서는 빌드 · 시험 변경 목록에 없다. 빠뜨리면 main 푸시 `skeleton`의 로그 위생 단언 P2가 실패한다(PR `ci-gate`에서는 안 보이고 병합 뒤에야 드러난다). | `LUNA_STARTUP`이 `EventCode` 신규 코드인지 기존 코드(예: `STARTUP_RECOVERY`나 `APP_ERROR`)의 속성 재사용인지 정하고, 신규라면 `EventCode`/`AttrKey`, `log-hygiene-check` 표, `test-host-tools.sh` 대조, 관련 `contracts/` 문서를 같은 커밋에서 바꾸는 단계를 1절 빌드 표나 Code Generation 계획에 명시한다. 로컬에서 `verify.sh` P2까지 한 번 돌리는 확인도 적는다. | Resolved (reviewer) |
| R-02 | Minor | cicd-pipeline.md > 4절 롤백 | 롤백을 "Parameter Store 값 삭제 → `render-env` → 앱 재기동"으로 적었다. 실제 RUNBOOK(`infra/RUNBOOK.md:537`)은 비밀 갱신 뒤 같은 SHA로 `DEPLOY`를 다시 실행해 컨테이너가 재생성되어야 반영된다고 한다. 재기동만으로는 env 파일이 바뀌어도 컨테이너 환경이 갱신되지 않을 수 있고, 팀 정본 롤백 경로(`deploy.yml mode=…`)와 어떻게 이어지는지도 없다. 또한 "삭제 시 빈 값"은 `render-env:11`의 OPTIONAL 동작에 기대는데 이 문서는 근거 줄을 대지 않았다. | 롤백 단계를 RUNBOOK의 키 갱신 절차(파라미터 변경 → 같은 SHA `DEPLOY`)와 맞춰 쓰고, 확인은 `LUNA_STARTUP lunaState=DISABLED_NO_KEY`로 유지한다. | Resolved (reviewer) |
| R-03 | Minor | cicd-pipeline.md > 4절 1단계, traceability.json > NFR3.7 | R-F15 확인 기록 표를 "RUNBOOK에 적음"이라고 하고 NFR3.7을 `OK`로 추적하지만, 현재 `infra/RUNBOOK.md`에는 해당 표가 없다(`:238-239`는 문구만 있다). 설계 시점에는 아직 만들 일인 항목이 `OK`로 표시되어 있다. 절차형 통제(Q2-B)라는 사람 결정은 존중하며, 표가 Code Generation 산출물임을 분명히 하면 충분하다. | NFR3.7 target을 "Code Generation 단계에서 RUNBOOK에 표를 추가"로 고치거나 계획 단계에 등록한다. | Resolved (reviewer) |
| R-04 | Minor | cicd-pipeline.md > 2절 취약점 행, 1절 2행 | `json-schema-validator` 3.0.6(`backend/gradle/libs.versions.toml:12`)이 `testImplementation`(`core-api/build.gradle.kts:40`, `realtime-activity/build.gradle.kts:32`)에서 `implementation`으로 옮겨지면 전이 의존성 전체가 `ci.yml` 206~216행의 HIGH · CRITICAL 게이트 대상이 된다. 로컬 1회 확인만 적었고, 걸릴 때의 대응(R-M7 예외 절차 vs 버전 조정)이 없다. 병합 직전 `ci-gate` 실패로 드러나는 위험이다. | 걸릴 경우 순서(먼저 버전 조정, 불가하면 `ci/security-exceptions.yml` 승인 절차)를 한 줄로 적는다. | Resolved (reviewer) |
| R-05 | Minor | cicd-pipeline.md > 1절 3행 / 2절 첫 행 | "빌드 구성 시험(SD8)"이 `check`에 포함되어 `lunaLiveTest`가 `check`에 연결되지 않음과 기본 `test`의 고정 제외를 보증한다고 했으나, 시험이 어디에(어느 모듈, 어떤 태그로) 놓이는지 적지 않았다. build-logic 변경이라 두 앱의 `test`에 넣기 애매하고, 이 시험이 없으면 NFR5.1의 유일한 CI 방어선이 없다. 참고로 현재 `mujoong.java-conventions`(`:79-86`)는 `-PincludeTags`/`-PexcludeTags`만 다루며 고정 제외는 없다. | 시험 위치와 실행 경로(예: `check`에 걸리는 build-logic 시험)를 명시한다. | Unresolved |
| R-06 | Minor | cicd-pipeline.md > 3절 모델 env, Assumptions | `OPENAI_ANALYSIS_MODEL`/`OPENAI_JUDGE_MODEL`이 `compose.demo.yml`/`render-env`에 없으면 demo에서 설정할 수 없고 기본값 `gpt-6-luna`로만 동작한다. "demo 투입 때 정한다"로 미뤘으나, 사람이 Compose를 고치지 않고는 모델을 못 바꾼다는 점이 4절 투입 순서에는 빠져 있다. 범위 밖 결정은 존중한다. | 4절 투입 순서에 "모델 env 필요 시 Compose/render-env 변경 선행" 항목을 한 줄 추가한다. | Resolved (reviewer) |

> R-01 Reviewer note: 로그 계약 묶음이 추가되었다. EventCode, AttrKey, log-hygiene-check ALLOWLIST_JSON(31~52행, 실제 파일과 일치), test-host-tools.sh(458행 이후 자바 정본 대조, 실제 파일과 일치), RUNBOOK 로그 절, contracts/ai-io.md 10절이 한 커밋으로 묶였고 누락 시의 실패 경로(ci-gate, skeleton P2)도 적혀 있다.; Required action: 없음.

> R-02 Reviewer note: 롤백이 'Parameter Store 키 비움 후 같은 SHA DEPLOY 재실행'으로 바뀌었고, 재기동만으로는 환경이 갱신되지 않는다는 점도 적혀 있다. RUNBOOK 537행의 OPENAI_API_KEY 회전 행과 일치한다.; Required action: 없음.

> R-03 Reviewer note: R-F15 확인 기록 표와 순서를 'U9 Code Generation에서 RUNBOOK에 새로 쓰는 산출물'로 표시했다. 현재 RUNBOOK 238~239행에는 문구만 있어 사실과 맞다.; Required action: 없음.

> R-04 Reviewer note: trivy에 걸릴 때의 대응 순서(① 같은 메이저 안에서 고친 판으로 올림 ② security-exceptions.yml에 올리고 사람 승인 ③ 승인 전 병합 금지)가 추가되었다. R-M7과 맞는다.; Required action: 없음.

> R-05 Reviewer note: 시험 위치 후보(build-logic TestKit 또는 앱 @Tag("u9") 시험)와 세 가지 단언이 적혔다. 다만 backend/build-logic에는 현재 src/main만 있고 시험 디렉터리가 없다. 포함 빌드의 시험은 루트 gradlew check에 자동으로 묶이지 않을 수 있어, '127행에서 돈다'는 문장은 앱 쪽 시험을 택할 때만 확실하다.; Required action: Code Generation 계획에서 위치를 정할 때 CI 127행(루트 check)에서 실제로 실행되는 경로임을 확인하고 적는다. 차단 사항은 아니다.

> R-06 Reviewer note: 모델 이름 env를 compose.demo.yml 및 render-env에 더하는 선행 항목이 순서 0으로 들어갔다. 없으면 기본값으로만 동작한다는 점도 적혀 있다.; Required action: 없음.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| 저장소 사실 확인 (log-hygiene-check, test-host-tools.sh, RUNBOOK 238~239/537, ci.yml 127, java-conventions) | 문서의 줄 번호와 설명이 실제 파일과 일치. java-conventions에는 excludeTags 속성 처리만 있고 luna-live 고정 제외는 아직 없어 이 단계의 변경 목록으로 올바르다. | R-01~R-04, R-06 해소 확인. R-05만 부분 해소. |

### Summary

사람이 고른 Q1-A 개정이 R-01의 로그 계약 묶음과 Minor 4건을 실제로 반영했고, 기록된 줄 번호도 저장소와 맞는다. 남은 것은 빌드 구성 시험의 실행 경로 확인(R-05) 하나뿐이며 차단 사항이 아니다.
