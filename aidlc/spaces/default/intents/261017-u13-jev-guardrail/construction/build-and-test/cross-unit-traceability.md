# U13 제브 게이트 — 최종 교차 추적 확인

> 단계 10(Cross-Unit Final Coverage Gate). 이 작업 기록은 유닛 나누기를 건너뛰어 **단계 수준** `construction/code-generation/traceability.json` 한 파일이 모든 요구사항을 추적한다. 유닛별 파일은 없다. 사용자 스토리 단계는 이 기록에서 실행되지 않아 3구간 `AC` 열거 대상이 없다(`inception/user-stories/stories.md` 없음). 아래는 `inception/requirements-analysis/requirements.md`가 정의한 FR · NFR 39개 전부다.

## 판정

**부분 통과 — 열거한 ID 39개 중 27개 `OK`, 12개 `N/A`, `GAP` · `ORPHAN` · 누락 0개.**
단계 정의는 '각 ID가 `OK`로 덮일 것'을 요구하므로, `OK`가 아닌 12개는 형식상 미충족으로 간주하고 승인 관문에서 사람에게 드러낸다. 12개 모두 `traceability.json`에 정당한 사유가 적힌 `N/A`이고 대상 파일이 있다. 아래에서 각각 근거를 확인했다.

## ID별 추적

| ID | 상태 | 소유 | 대상 · 근거 | 대상 파일 존재 |
|---|---|---|---|---|
| FR1.1 · FR1.2 · FR1.3 | OK | stage-level code-generation | BR1.1 ~ BR1.5, BR2.6, BR9.2 · 포트 · 대역 · 모드 | 예 (`guardrail/` 패키지, `construction/functional-design/rules.md`) |
| FR2.1 · FR2.2 · FR2.3 | OK | 〃 | BR2.x · 어댑터 · 요청 구성 · 호스트 | 예 (`JevGuardrailClient`, `GuardrailRequestFactory`, `JevHosts`) |
| FR3.1 · FR3.2 · FR3.3 | OK | 〃 | BR3.x · BR5.x · BR6.x · `GuardrailCoordinator` · 대기열 | 예 (`session/GuardrailCoordinator.java`) |
| FR4.1 · FR4.2 · FR4.3 | OK | 〃 | BR4.x 늦은 ALLOW 무효 | 예 |
| FR5.1 · FR5.2 | OK | 〃 | BR7.x 승인 원문 생략 · 모드 범위 | 예 (`ApprovedVerbatimPort`) |
| FR6.1 · FR6.2 · FR6.3 · FR6.4 | OK | 〃 | BR8.x · V6 `guardrail_event` · 지표 | 예 (`V6__u13_jev_guardrail.sql`, `GuardrailEventRecorder`) |
| FR7.1 · FR7.2 | OK | 〃 | BR9.1 · BR9.3 · 기동 규칙 · 인프라 정렬 | 예 (`GuardrailStartupRules`, `infra/bin/render-env`) |
| NFR1 · NFR2 · NFR3 · NFR4 · NFR6 · NFR8 · NFR9 | OK | 〃 | BR2.1 · BR1.2 · BR2.4 · BR8.1 · BR1.6 · BR1.7 · BR1.4 | 예 |
| FR1 · FR2 · FR3 · FR4 · FR5 · FR6 · FR7 · FR8 | **N/A** | 〃 | 묶음 머리 ID — '하위 행이 추적' | 해당 없음(하위 FR 행이 모두 OK 또는 FR8.1 · 8.2의 N/A) |
| FR8.1 | **N/A** | 〃 | 시험 정비 — 업무 규칙 없음. `backend/coverage-scope/u13.txt` · `report.md` · 코드 생성 계획 단계로 다룸 | 예 |
| FR8.2 | **N/A** | 〃 | 문서 · 계약 정정 — `docs/units/u13/design.md` · `report.md` · `contracts/ai-io.md` · `deviations.md` · `jevLiveTest` | 예 (모두 커밋됨) |
| NFR5 | **N/A** | 〃 | 시험 정비 — 업무 규칙 없음. 단위 시험 안내 · 코드 생성 계획으로 다룸 | 예 |
| NFR7 | **N/A** | 〃 | 검증 실행 규칙(`mujoong-verify` 사용) — **이 Build and Test가 직접 충족:** 검증은 `verify.sh`의 Compose 프로젝트 `mujoong-verify`로 돌렸고 `mujoong-manual`은 건드리지 않았다 | 예 (`build/verify/report.txt`) |

## 이 단계의 확인으로 다시 잰 것

- **FR8 · NFR5 · NFR7은 업무 규칙이 아니라 작업 방식 요구**이며, 팀 규칙(`project.md` Corrections)이 '업무 규칙(BR)을 만들지 않고 traceability에 N/A로 추적해 Code Generation 계획에서 단계로 다룬다'고 정한 방식 그대로다.
- NFR7은 이 단계에서 실제 증거(`mujoong-verify` 프로젝트로 전체 검증을 돌림)가 생겼다.
- 묶음 머리 ID(FR1 ~ FR8)는 하위 행이 `OK`이므로 실질 공백이 아니다.

## 미충족(형식) 목록

없음(GAP · ORPHAN 0). 형식 판정 `OK`가 아닌 것은 위 `N/A` 12개이며, 근거가 모두 확인된다. 승인 관문에서 '이대로 수용'인지 사람이 정한다.
