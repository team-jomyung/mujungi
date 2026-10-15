## Review

**Verdict:** NOT-READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-15T16:16:10Z
**Iteration:** 1

이 검토는 advisory 단일 패스다. 아래는 사람이 승인 전에 따져 볼 지적이고, 근거(파일 · 코드 사실)가 있는 것을 먼저 적었다.

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/contract-design/contract-summary.md > C-DS12 보호자 REST | 문서는 `parent-api.yaml`의 `SceneResult.timing`과 `GoalCandidateView.displayEvidence`에 "값 · 선택 필드만 더한다"고 한다. 그러나 `contracts/parent-api.yaml`에는 `SceneResult` · `GoalCandidateView` · `timing`이 없다(grep 0건). 실제 결과 형은 `contracts/schemas/session-result-response.schema.json`의 `PracticeGoal.scenes[]`이고 `sceneChip`(5개 enum)만 있으며 `additionalProperties: false`, `sceneNo`는 enum `[1,2]`다. `additionalProperties: false` 객체에 필드를 더하면 엄격 검증하는 받는 쪽(프런트 파서 · `ContractFilesTest` 예시 검증)에 깨는 변경이다. 또 현재 `SceneChipMapper`는 `timing=="AFTER_HELP"`만 DONE_AFTER_HELP로 접는데, 새 `AFTER_REASK`가 그대로 들어가면 DONE_AFTER_HELP가 아니라 DONE_IMMEDIATE로 떨어진다는 서술(접기 규칙)은 있으나 어느 스키마의 어느 필드가 바뀌는지는 틀렸다. | 실제 대상 스키마(`session-result-response` · 목표 후보 응답 스키마)의 이름과 필드로 C-DS12를 다시 쓴다. `additionalProperties: false`를 풀거나 `timing` 노출 여부와 `sceneNo` 범위(변형 장면)를 명시하고, 이것이 더하기만이 아니라 깨는 변경이면 `deviations.md` 행과 소비자 동시 머지로 분류한다. | New |
| R-02 | Major | contract-summary.md > C-DS13 시간 값 표 `mujoong.timing.result.retry-delays` | FR7.6 "추가 최대 3회(5 · 15 · 30초)"를 새 키 `result.retry-delays`로 더한다. 그러나 `contracts/timing.md`에는 이미 같은 재시도를 정하는 `mujoong.timing.result.job-attempts`(3, 최초 포함)와 `job-retry-backoff`(`[5s, 15s]`)가 있다(timing.md 176~177행). 새 키를 더하면 같은 작업에 재시도 설정이 둘이 되고, 어느 쪽이 이기는지 · 총 시도 수(3 → 4)가 계약에 없다. 값 표기도 기존은 목록 `[5s, 15s]`인데 새 값은 `5s, 15s, 30s`로 다르다. | 새 키를 만들지 말고 기존 `job-attempts`(4) · `job-retry-backoff`(`[5s, 15s, 30s]`)를 바꾸는 값 변경으로 적는다. 의미 변경(시도 수 증가)이므로 `deviations.md` 행과 R-M5 세 곳 동시 수정 대상으로 표시한다. | New |
| R-03 | Major | contract-summary.md > C-DS2 서문 · 받는 쪽(U14) | 새 기록 형식을 `snapshotVersion`을 올려 구분한다. 그러나 이 필드의 기존 의미는 "세션당 한 판"이다. 실제 DB는 `UNIQUE (session_id, snapshot_version)`, 상수 `ResultInputSnapshotRepository.SNAPSHOT_VERSION = 1`, 주석 "세션당 한 판(U4b는 1만 쓴다)"이고, 핵심 `core.record`는 이 값을 그대로 저장만 한다. 이것을 "기록 형식 판"으로 재해석하는 것은 더하기가 아니라 의미 변경이다(README의 깨는 변경 기준). 게다가 같은 블록에 이미 판별자 `practice.flowVersion`이 있어 `snapshotVersion` 상승은 중복이다. | `snapshotVersion`은 기존 의미를 유지하고 형식 구분은 `practice.flowVersion`(없으면 레거시)으로 한다. 정말 `snapshotVersion`을 쓰려면 의미 변경으로 보고 `deviations.md` 행과 U14 소비 규칙(1이면 레거시)을 근거와 함께 다시 정한다. FR7.5 문구도 맞춘다. | New |
| R-04 | Major | contract-summary.md > C-DS1 대본 조회, C-DS5 상황 생성 | (a) 새 응답도 `practice-scenario.schema.json`의 기존 필수 필드(`relationType` · `opportunityCondition` · `scenarioText` · `partnerRoleText` · `triggerScript` · `validation{goalIncluded:true, triggerIncluded:true, forbiddenFound:false}`)를 채워야 한다고 했다. 그런데 C-DS5 `ScenarioOutput`은 `scenes[]`만 내고, 이 레거시 필수 필드를 누가 어떤 규칙으로 만드는지(새 장면에서 파생하는지) 계약에 없다. 지금 `internal-api.yaml`을 소비하는 U5b 경로 시험이 새 응답에서 깨질 수 있다. (b) 새 `scenes[].partnerRole`을 모델이 낸다고 하나, 기존 `ai-io.md` 7절(U5a-J22)은 "`partnerRoleText`는 모델에 받지 않고 Core의 고정 역할 지시로 만든다"고 정했다. 새 계약이 이 안전 규칙을 조용히 뒤집는다. | (a) 새 응답에서 레거시 필수 필드의 출처(예: 첫 BASE 장면에서 서버가 파생)를 한 줄로 정한다. (b) `partnerRole`을 모델 출력에서 빼거나, 모델이 내는 것을 허용하려면 J22를 바꾸는 편차로 표시하고 서버 검사 규칙을 적는다. | New |
| R-05 | Major | contract-summary.md > 1. 계약 목록(C-DS3, C-DS8, C-DS4~C-DS7); unit-of-work-dependency.md 통합 지점 | 빠진 경계 둘. (a) 의존 간선 U13 → U11("출력 게이트의 보류 · 출력 세대 규칙 위에 제브 단계를 얹는다")이 어느 계약에도 없다. C-DS8은 제브 외부 경계(`GuardrailClient`)만 정하고, U11이 소유한 `OutputGate`가 U13 단계를 어디서 어떤 시그니처로 부르는지(차단 시 보류 · 세대 처리 · `GUARDRAIL_BLOCKED` 발행 주체)는 없다. 의존 문서가 U11 · U12 · U13이 `OutputGate`를 함께 고친다고 경고한 바로 그 지점이다. (b) U9 → U10 · U14 · U15(C-DS5~C-DS7)는 스키마만 있고 앱 안 Java 포트(예: Luna 호출부 인터페이스, 오류 · 시간 초과 결과 형)가 없다. C-DS4(`JudgeClient`)와 C-DS8(`GuardrailClient`)은 포트가 있어 형태가 비대칭이다. | (a) U11이 소유하는 게이트 단계 확장점(입력 · 출력 · 실패 시 동작 · 발행 이벤트)을 C-DS3 옆에 계약으로 추가한다. (b) U9가 core에 내놓는 호출 포트와 실패 결과 형을 C-DS5~C-DS7에 공통 한 항목으로 정한다. | New |
| R-06 | Minor | contract-summary.md > C-DS3 (이전 지적 R-01 · R-04 해소 주장), C-DS12 끝 문단 | "Domain Design R-01 해소"라고 쓰지만 C-DS12는 칩 매퍼를 U14 Functional Design에서 "나눈다"고 미룬 것이라 해소가 아니라 이월이다. 또 C-DS3의 `ExtensionEvent` 추가 값 `JUDGMENT_RESULT` 등은 실제로는 enum이 아니라 `Kind`(PLAYBACK_DONE · PLAYBACK_FACT · TIMER_FIRED · ASYNC_RESULT)와 `name` 문자열 구분인데, 문서는 어느 쪽에 더하는지 모호하다. `requestJudgment`가 "비동기 결과는 onExtensionEvent로"라 한 것은 기존 `ASYNC_RESULT` + `post(name, value)`로 충분해 새 Kind가 필요한지 불분명하다. | 해소 주장은 "이월 → U14 FD" 표현으로 바꾸고, 새 사건은 `Kind`를 늘릴지 `ASYNC_RESULT`의 `name` 상수로 둘지 정한다(기존 레거시 구현이 모르는 Kind를 만나도 깨지지 않게 기본 처리 명시). | New |
| R-07 | Minor | contract-summary.md > C-DS5 · C-DS6 · C-DS7 실패, 4절 열린 질문 1 | 실패 규칙이 어긋난다. C-DS5 · C-DS7은 "스키마 불일치 1회 다시"로 확정했는데 C-DS6은 같은 문제를 열린 질문 1로 남긴다. 열린 질문 1은 U9 · U10 · U14를 막는다고 적지만 U9 호출부 공통 정책이라 한쪽만 열려 있을 수 없다. 기존 `ai-io.md` 7절은 이미 "기술 재시도 1회 + 내용 재생성 1회, 전체 15초"를 정했다. | Luna 공통 재시도 정책을 한 항목으로 정하고(기존 7절 규칙 승계 여부 포함) C-DS5~C-DS7이 그것을 가리키게 한다. | New |
| R-08 | Minor | contract-summary.md > C-DS4 `JudgeRequest.childUtterance`, C-DS8 `GuardrailRequest.candidate` | 아동 발화를 외부 모델로 보내는 필드인데 R-F15(공급자 약관 · 동의 확인 전에는 아동 데이터를 외부 모델에 보내지 않는다) 전제가 계약에 한 줄도 없다. 이름 치환('아이')만 있고, 기동 시 동의 · 약관 확인 상태를 어떻게 가드하는지 없다. C-DS6의 `evidence[].quote`(아동 전사 인용)는 입력 · 출력 어느 쪽에 전사가 실리는지도 불명확하다. | 각 외부 AI 경계에 R-F15 가드(미확인이면 호출하지 않고 보류 · 폴백)를 금지 필드 문단에 더한다. 목표 후보 입력의 전사 범위를 명시한다. | New |
| R-09 | Minor | contract-summary.md > C-DS6 `GoalCandidateOutput` | 기존 `ai-io.md` 6.8(`behaviorKey` · `behaviorVersion` · `evidence.kind`)을 "대체"한다고 하면서 필수 필드(`skillRef` · `fourElements`)를 새로 요구한다. 기존 후보 생성 경로(U6)와 `LEGACY_BEHAVIOR` 읽기는 유지(C-DS10)되므로 공존 규칙이 필요한데, 대체인지 병행인지 `promptVersion` 기준 분기가 없다. | 병행 시기(레거시 스키마 판 유지 기간)와 분기 기준을 한 줄 적고 `deviations.md` 행으로 표시한다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| 직접 대조(`contracts/timing.md` 키 목록) | `result.job-attempts` · `job-retry-backoff` 이미 존재, 새 `result.retry-delays`와 중복 | R-02 근거 |
| 직접 대조(`contracts/parent-api.yaml` · `schemas/session-result-response.schema.json`) | `SceneResult` · `GoalCandidateView` 없음, `PracticeGoal.scenes[]` 객체는 `additionalProperties:false` | R-01 근거 |
| 직접 대조(`V3__u4b_session_lifecycle.sql` · `ResultInputSnapshotRepository`) | `snapshot_version`은 세션당 한 판(UNIQUE, 상수 1) | R-03 근거 |
| 직접 대조(`PracticeSessionExtension.java`) | 콜백 5개 · `PracticeOutput` 기존 능력(`speak` · `cancelSpeech` 포함) · `Signal` 3값은 문서와 일치, `ExtensionEvent.Kind`는 4개 | Q1-A 서술은 맞음, R-06 참고 |
| 의존 DAG 순환 검사(`unit-of-work-dependency.md` YAML) | 순환 없음. U14 → U12 간선 없이 이벤트 계약으로 끊은 것도 맞음 | 위상은 건전, 빠진 것은 R-05 (a)(b)의 계약 |
| 시간 수치 대조(FR3.4 · 5.3 · 6.2 · 9.5 · 7.6 · 2.5, 시간 키 표) | 2초 · 10초 · 800ms · 2초 · 15초 · 5/15/30초 모두 요구사항과 일치 | 값은 맞고, 키 구조만 R-02 |

### Summary

값 · 의존 위상 · 제브/Luna 경로 분리(FR2.6 · FR7.7)는 요구사항과 맞지만, 기존 `contracts/` 정본과 실제 코드와 부딪히는 곳이 다섯 군데 있다: 존재하지 않는 스키마 이름과 엄격 스키마에 필드를 더하는 C-DS12, 이미 있는 결과 재시도 키와의 중복, `snapshotVersion` 의미 변경, 레거시 필수 필드 출처 · 모델이 내는 `partnerRole`과 J22 충돌, 그리고 U13 → U11 게이트와 U9 호출 포트의 빠진 계약. 승인 전에 이 다섯 가지를 고치거나 의도적 편차로 승인해야 한다(Major 5개로 NOT-READY, advisory라 수정 루프는 없고 게이트에서 Request Changes로 반영).
