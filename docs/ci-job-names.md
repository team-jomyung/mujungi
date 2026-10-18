# CI 작업 이름과 `ci-gate` 구성

구조 리팩토링 Step 22 ~ 23(BR4.4)에서 `.github/workflows/ci.yml`의 유닛 번호 이름을 기능 이름으로 바꿨다.
대응 정본은 `aidlc/spaces/default/intents/261018-structure-refactor/construction/functional-design/functional-spec.md` 3.1절이다.
동작(어느 시험을 어떤 하한으로 돌리는지)은 그대로다.

## 바뀐 이름

| 종류 | 이전 | 이후 |
|---|---|---|
| 작업 id · 이름 | `u5b-scope` | `practice-legacy-scope` |
| 백엔드 시험 태그(`-PincludeTags`) | `u5b` | `practice-legacy` |
| 백엔드 시험 태그(`-PincludeTags`) | `u7` | `web-push` |
| 백엔드 시험 태그(README 예시) | `u1` | `skeleton` |
| 범위 파일(`-PcoverageScope`) | `practice-legacy` · `practice-legacy-core` · `web-push` | 변경 없음(Step 21에서 이미 바뀜) |
| 프런트 범위(`MUJOONG_COVERAGE_SCOPE`) · 시험 목록 | `practice-legacy` · `web-push` | 변경 없음(Step 20 ~ 21에서 이미 바뀜) |
| 보고서 이름 | `u5b-reports` | `practice-legacy-reports` |
| 보고서 이름 | `backend-u7-reports` | `backend-web-push-reports` |
| 보고서 이름 | `frontend-u7-coverage` | `frontend-web-push-coverage` |
| 단계 이름 | `Realtime U5b …` · `Core U5b …` · `U5b 시험 …` · `U5b 프런트 …` | `Realtime 연습 레거시 …` · `Core 연습 레거시 …` · `연습 레거시 시험 …` · `연습 레거시 프런트 …` |
| 단계 이름 | `U7 태그 시험 …` · `U7 백엔드 …` · `U7 프런트 …` | `웹 푸시 태그 시험 …` · `웹 푸시 백엔드 …` · `웹 푸시 프런트 …` |

작업 `practice-legacy-scope`에는 연습 레거시 범위(Realtime · Core)와, 같은 작업 끝에 붙은 웹 푸시 백엔드 범위 단계가 함께 있다.

## `hashFiles` 조건 단계

파일 이름이 바뀌어도 조건이 조용히 거짓이 되지 않도록 새 이름과 실제 파일을 대조했다(AC-9의 코드 쪽 확인).

| 단계 | 조건 경로 | 파일 존재 |
|---|---|---|
| 웹 푸시 백엔드 범위 · 보고서 | `backend/coverage-scope/web-push.txt` | 있음 |
| 웹 푸시 프런트 범위 · 보고서 | `frontend/coverage-scope/web-push.json` | 있음 |
| 프런트 `vitest run $(cat coverage-scope/web-push.tests)` | `frontend/coverage-scope/web-push.tests` | 있음 |
| 프런트 `vitest run $(cat coverage-scope/practice-legacy.tests)` | `frontend/coverage-scope/practice-legacy.tests` | 있음 |

푸시 뒤 첫 CI 실행에서 위 `hashFiles` 조건 단계가 `skipped`가 아니라 `success`인지 단계 기록에서 확인한다(AC-9).

## `ci-gate` 구성

`ci-gate`는 `main` 브랜치 보호의 필수 검사 하나다. 집계 방식(건너뜀 허용, 실패 · 취소 불허, `changes` 성공 필수)은 그대로이고 `needs`만 새 작업 이름을 쓴다.

```
ci-gate.needs = [changes, backend, practice-legacy-scope, backend-deps, frontend, frontend-deps, tools, secrets, infra-validate, images, skeleton]
```

`ci-gate` 자체의 이름은 바뀌지 않아 브랜치 보호 설정을 고칠 필요가 없다.
