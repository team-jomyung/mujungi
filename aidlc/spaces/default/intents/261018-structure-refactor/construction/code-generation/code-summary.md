# 구조 · 이름 리팩토링 — Code Generation 요약

> 작업 기록 `261018-structure-refactor` · 범위 `refactor` · 통합 브랜치 `claude/refactor-structure`. 이 작업은 동작을 바꾸지 않는다(NFR1). 아래 시험 결과는 모두 **테스트 대역(가짜 공급자) · 합성 대화** 기준이다. 실제 API · 실제 기기 성공으로 표시하지 않는다(R-M11).

## 만들거나 바꾼 것

main 기준(`d8358879`)으로 응용 소스 약 1,180개 경로가 바뀌었다. 이동은 옛 경로 삭제 + 새 경로로 센다. 전체 경로는 `source-manifest.json`에 영역 단위로 적었다.

| 단계 | 내용 | 결과 |
|---|---|---|
| 0 안전망 (Step 1 ~ 4) | `main` 기준선, 실행기 7구간 특성화 시험 확인 · 보강, 필드 소유자 표, 참조 점검표 · 화면 대응표 · AC 기준선 | `baseline.md` · `reference-checklist.md` · `docs/screen-map.md` |
| 1 프런트 이름 · 폴더 (Step 5 ~ 8) | 보호자 화면 17개 · 기기 화면 3개를 `parent|device/features/<기능>/`으로 이동, 번호 접미 제거, import · 범위 파일 · ESLint 경계 갱신 | 화면 파일은 `<기능>Screen.tsx` |
| 2 화면 흐름 훅 (Step 9 ~ 11) | 화면 훅 9개 분리, `account.ts` · `queries.ts` 분할, 연습 요청 · 문구 통합 | 화면 컴포넌트에서 `router.` · `catch` · `await` 제거(AC-3) |
| 3 실행기 분리 (Step 12 ~ 17) | `SessionExecutor`에서 `SessionContext` · `EndingFlow` · `SessionClocks` · `IdleClock` · `JoinFlow` 추출, 판정 턴을 `JudgmentCoordinator`로 흡수 | `SessionExecutor` 4,361줄 → 3,091줄(AC-4: 3,100줄 이하) |
| 4 설정 · 패키지 (Step 18 ~ 19) | 설정 레코드를 기능별 20개 파일로 분리, `practice/` → `practicelegacy/` | 설정 키 · 바인딩 prefix 불변 |
| 5 시험 · 범위 · CI (Step 20 ~ 23) | 프런트 · tools 시험 폴더를 소스 구조로, 범위 파일 이름을 기능 이름으로, Java 클래스 · `@Tag` · 자원 이름에서 유닛 번호 제거, CI 작업 이름 정리 | 시험 수 동일(core 1722 · realtime 1866) |
| 6 주석 정리 (Step 24) | `team.md` 규칙 4개를 학습 절차로 기록(사람 승인), 이 기록이 건드린 파일의 결정 ID 주석 정리 | 아래 "Step 24" 참조 |
| 마무리 (Step 25) | 문서 최종본, 비밀 스캔, 추적성 · 소스 목록 | `docs/screen-map.md` · `docs/refactor-structure-before-after.md` · `docs/ci-job-names.md` |

## Step 24 (주석 정리)

- 사람이 `team.md` 규칙 4개(화면 파일 이름 · 시험 폴더와 태그 이름 · 주석 결정 ID 인용 · 범위 파일 이름)를 모두 승인했고, 학습 절차(§13)로 `team.md`에 기록했다. 기존 줄은 지우지 않고 새 줄에 "대체"로 적었다. 그래서 `team.md`에는 옛 줄과 대체 줄이 함께 있다.
- 이 기록이 건드린 `frontend/src` · `backend/*/src/main` 파일 540줄에서 결정 ID 주석을 정리했다. **AC-7: 540줄 → 10줄.** 남은 10줄은 모두 코드다(`JudgmentPoint.J1` 등 열거값 9줄, 오류 사유 문자열 1줄).
- 바꾼 것은 132개 파일의 주석 줄뿐이다. 코드 줄 변경은 없는 것을 `git diff`로 확인했다. 줄을 지운 파일은 `SessionExecutor.java` 하나이고, `TimeLiteralScanTest` 등 줄 번호나 주석에 기대는 시험은 없다.
- 정리 방식은 결정 ID 나열을 **지우는 것**이 기본이다. 문서 링크로 바꾼 곳은 `RealtimeTimingProperties`의 `contracts/timing.md` 한 곳뿐이다. ID를 지운 문장 중 일부는 근거가 빠져 읽기에 어색할 수 있어, 리뷰에서 `git diff`로 훑어 달라고 요청한다.

## 핵심 결정 · 편차

- 둘 이상의 기능이 쓰는 파일은 계획 밖으로 `common/` 아래에 모았다(Step 11e, `cross-feature-imports.md`). 그래서 설계안의 `features/{home,child,live}/api.ts`는 `common/api/{home,child,live}.ts`가 되었다.
- 연습 확장점 구역과 게이트는 실행기에 남겼다(BR3.3 · BR3.5, `gate-boundary.md`).
- 설계 편차 FD-D1 ~ FD-D6은 `functional-design/traceability.json`을 따른다.
- Step 24의 주석 정리 스크립트는 셸로 돌려 파일을 고쳤다. 단계 산출물을 파일 쓰기 도구로 저장하라는 규칙(project.md)은 설계 문서 등 산출물에 대한 것이고, 이것은 응용 소스 주석이라 규칙 위반은 아니지만 알려 둔다.

## 시험 결과 (테스트 대역 · 합성 대화 기준)

| 검사 | 결과 |
|---|---|
| 백엔드 `clean check -Pmujoong.test.db=docker` (Step 24 뒤) | 통과 |
| 프런트 `eslint .` | 통과 |
| 프런트 `vitest run tests --coverage` | 74개 파일 · 994개 시험 통과, 줄 98.56% |
| 프런트 `next build` | 통과 |
| tools `vitest run tests` | 12개 파일 · 133개 시험 통과 |
| 비밀 스캔 `a-secrets.sh` | 통과(gitleaks는 로컬에 없어 CI `secrets` 작업 몫) |
| 전체 `bash scripts/verify.sh` | 단계 5 확인에서 통과. Step 24 이후에는 Build and Test에서 한 번 더 돈다 |
| 실제 API · 실제 기기 | 해당 없음(이 작업은 동작 변경이 없고 실호출 확인을 하지 않았다) |

## 알려 둘 것

- 원격 `main`과의 병합 따라잡기는 단계 5 확인 때 끝냈다. 그 뒤 새로 올라온 `main` 변경은 확인하지 않았다.
- 변경은 아직 커밋하지 않았다(작업 트리에 있음).
