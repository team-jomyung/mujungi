# 제브 어댑터 선 형식 정정 — 최종 교차 추적 확인

> 단계 10(Cross-Unit Final Coverage Gate). 이 작업 기록은 유닛 나누기를 건너뛰어 **단계 수준** `construction/code-generation/traceability.json` 한 파일이 모든 요구사항을 추적한다. 유닛별 파일은 없다. 사용자 스토리 단계는 이 기록에서 실행되지 않아 3구간 `AC` 열거 대상이 없다(`inception/user-stories/stories.md` 없음). 아래는 `inception/requirements-analysis/requirements.md`가 정의한 FR · NFR 29개 전부다.

## 판정

**부분 통과 — 열거한 ID 29개 중 22개 `OK`, 7개 `N/A`, `GAP` · `ORPHAN` · 누락 0개.**
단계 정의는 '각 ID가 `OK`로 덮일 것'을 요구하므로 `OK`가 아닌 7개는 형식상 미충족으로 간주하고 승인 관문에서 사람에게 드러낸다. 7개 모두 `traceability.json`에 사유가 적힌 `N/A`이고, 아래에서 근거를 확인했다.

## ID별 추적

| ID | 상태 | 소유 | 대상 · 근거 | 대상 파일 존재 |
|---|---|---|---|---|
| FR1.1 · FR1.2 | OK | stage-level code-generation | `JevGuardrailClient`(경로 · 인증 · `state` 구성) | 예 |
| FR1.3 | OK | 〃 | `JevQuestion` · `policy-v2.json`(질문 구성 · 라벨 8개) | 예 |
| FR2.1 · FR2.2 · FR2.3 · FR2.4 | OK | 〃 | `JevGuardrailClient`(응답 검증 · 판정 규칙 · 값 채우기 · 오류 매핑) | 예 |
| FR3.1 | OK | 〃 | `GuardrailProperties` · `application.yml`(문턱 키) | 예 |
| FR3.2 | OK | 〃 | `contracts/ai-io.md` · `deviations.md`(임시 시험값 표시) | 예 |
| FR3.3 | OK | 〃 | `GuardrailStartupRules`(문턱 범위 기동 점검) | 예 |
| FR4.1 · FR4.2 | OK | 〃 | `policy-v2.json` · `LiveContentResources`(v1 보존, v2 적재) | 예 |
| FR5.1 · FR5.2 · FR5.3 | OK | 〃 | `contracts/ai-io.md` 10.12 · `JevLiveProbe` · `docs/units/u13/design.md` · `report.md` · RUNBOOK 8.8 | 예(모두 커밋됨) |
| FR6.1 · FR6.2 | OK | 〃 | `JevGuardrailClientTest`(표적 회귀) · 영향받은 기존 시험의 새 사실 반영 | 예 |
| NFR1 · NFR2 · NFR3 · NFR4 · NFR5 | OK | 〃 | 실패 시 막힘 시험 · 비밀 · 원문 비노출 시험 · `TimeLiteralScanTest` · 게이트 내부 소비자 회귀 시험 · `backend/coverage-scope/u13.txt` | 예 |
| FR1 · FR2 · FR3 · FR4 · FR5 · FR6 | **N/A** | 〃 | 묶음 머리 ID — '하위 행이 추적'(FR3은 'D11: 앞 기록에 대응 요구 없음, 처음 요청에서 신설', FR6은 '시험 정비 — 업무 규칙 없음') | 해당 없음(하위 FR 행이 모두 OK) |
| NFR6 | **N/A** | 〃 | 증거 구분 — 작업 방식 요구. **이 Build and Test가 직접 충족:** 결과를 테스트 대역 · 합성 대화 · 실제 API · 실제 기기로 나눠 적었고 실제 제브 호출은 하지 않았다(`test-results.md` · `build-and-test-summary.md`) | 예 |

## 이 단계의 확인으로 다시 잰 것

- 묶음 머리 ID(FR1 ~ FR6)는 하위 행이 모두 `OK`이므로 실질 공백이 아니다.
- NFR6은 팀 규칙(`project.md` Corrections)이 정한 방식 — 작업 방식 요구사항은 업무 규칙을 만들지 않고 traceability에 `N/A`로 두고 Code Generation · Build and Test 단계에서 다룬다 — 그대로이며, 이 단계에서 실제 증거가 생겼다.

## 미충족(형식) 목록

없음(GAP · ORPHAN 0). 형식 판정 `OK`가 아닌 것은 위 `N/A` 7개이며 근거가 모두 확인된다. 승인 관문에서 '이대로 수용'인지 사람이 정한다.
