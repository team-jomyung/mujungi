# 기능끼리 import 정리 (Step 11e, 계획 외 추가)

사람이 승인한 추가 정리다. BR1.4(한 기능만 쓰면 `features/<기능>/`, 둘 이상의 기능이 쓰면 `parent/common/`)를 끝까지 지킨다. 동작 · 문구 · data-testid · 요청 내용은 바꾸지 않았고, 변경은 파일 위치 · import · 식별자 이름뿐이다(잘라낸 두 곳은 아래).

## 1. 분류 결과

`frontend/src/parent/features/*`의 모든 파일(이동 전 58개)을 상대 경로 · `@/parent/features/...` 둘 다 grep해 분류했다. 다른 기능 폴더가 import하는 파일은 아래 10건이다. 나머지는 자기 기능 안에서만 쓰거나 `common`만 import한다.

| 파일 (이전) | 쓰는 기능들 | 새 위치 | 비고 |
|---|---|---|---|
| `home/api.ts` | home · child · device · done · live | `common/api/home.ts` | 전체 이동 |
| `child/api.ts` | child · device (`useStopExpressionsQuery`) | `common/api/child.ts` | 전체 이동 |
| `live/api.ts` | live · done (`useLivePolling` 경유) | `common/api/live.ts` | 전체 이동. `useLivePolling`이 공통으로 가므로 공통이 기능을 import하지 않게 같이 옮김 |
| `practice/api.ts` | practice · records (`selectGoal` · `deleteGoal`) | `common/api/practice.ts` | **잘라서 이동**: 연습 목표 선택 요청 전부 이동, `practiceStartPort`는 practice 전용이라 `features/practice/startPort.ts`로 분리 |
| `free/failure.ts` | free · done · live · practice | `common/activity/failure.ts` | 전체 이동 |
| `live/presentation.ts` | live · done (`isStartFailure`) | `common/activity/livePresentation.ts` | 전체 이동 |
| `live/useLivePolling.ts` | live · done | `common/hooks/useLivePolling.ts` | 전체 이동 |
| `done/useResultStatusPolling.ts` | done · records | `common/hooks/useResultStatusPolling.ts` | 전체 이동 |
| `practice/PracticeHeader.tsx` | practice(시험) · live | `common/components/PracticeHeader.tsx` | 전체 이동 |
| `practice/copy.ts`의 `U5B_COPY` | practice · live · 공통 `PracticeHeader` | `common/copy/practice.ts` | **잘라서 이동**: `U5B_COPY`만 이동, 목록 문구(`PRACTICE_COPY` `PRACTICE_TEMP_COPY` `goalMeta`)는 practice 전용이라 `features/practice/copy.ts`에 남김 |

옮기지 않은 것: `consent/api.ts`(동의 전용), `device/api.ts` · `free/api.ts` · `login/api.ts` · `reports/api.ts` 등 한 기능만 쓰는 요청 파일, `home/presentation.ts` · `done/presentation.ts`(각 기능 전용).

## 2. 잘라낸 코드 (로직 변경 없음, 잘라 붙이기만)

- `practice/api.ts` → `practiceStartPort` 함수와 그 주석, `ApiError` · `parseStartResult` · `StartPort` import를 `features/practice/startPort.ts`로. `PracticeScreen`만 이 함수를 쓴다.
- `practice/copy.ts` → `U5B_COPY` 객체와 `attachParticle` · `PracticeStage` import를 `common/copy/practice.ts`로. 파일 머리 주석은 새 위치를 가리키게 고쳤다.

## 3. 범위 파일(`frontend/coverage-scope/*.json`)

| 범위 | 바뀐 항목 |
|---|---|
| `u1` | `home/api` → `common/api/home`, `live/api` → `common/api/live`, `live/useLivePolling` → `common/hooks/useLivePolling` |
| `u2` | `child/api` → `common/api/child` |
| `u4a` | `free/failure` → `common/activity/failure`, `live/presentation` → `common/activity/livePresentation` |
| `u5a` | `practice/api` → `common/api/practice` + `features/practice/startPort`, `common/copy/practice` 추가 |
| `u5b` | 위와 같고 `PracticeHeader` → `common/components/PracticeHeader` |
| `u6` | `done/useResultStatusPolling` → `common/hooks/useResultStatusPolling` |

잘라낸 조각(`startPort.ts` · `common/copy/practice.ts`)은 원래 그 코드가 들어 있던 범위(`u5a` · `u5b`)에 그대로 남도록 함께 넣어 대상 집합의 의미를 유지했다(BR4.2).

## 4. 새 ESLint 규칙

`frontend/eslint.config.mjs`: 기능 11개(child consent device done free home live login practice records reports)마다
- `import/no-restricted-paths` 구역 `target = features/<기능>`, `from = features/<나머지 기능>`,
- `no-restricted-imports` 패턴 `^@/parent/features/(나머지)(/|$)` · `^(\.\./)+(features/)?(나머지)(/|$)`(기기 영역 금지 패턴을 함께 적음).

기존 규칙(영역 경계, `common → features` 금지)은 낮추지 않았다. 확인 방법: 임시 파일 `features/home/TmpViolationRel.ts`(`../child/...`)와 `TmpViolationAlias.ts`(`@/parent/features/child/...`)가 두 규칙 모두에서 오류 4건을 냈고, 지웠다.
