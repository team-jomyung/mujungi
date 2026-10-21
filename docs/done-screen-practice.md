# 17 활동 끝 화면 — 연습 변형 (지시서 작업 묶음 3 (e))

- 브랜치: `claude/done-screen-practice` (origin/main `fec5285`에서 땀). PR은 아직 없다(사람이 연다).
- 범위: 프런트만. 계약(`contracts/`) · API · 백엔드 · 마이그레이션은 바꾸지 않았다.
- 기준: 화면 설계 v1.5j 문구표(`aidlc/spaces/default/knowledge/documents/10_화면설계_v1.5j/화면설계_v1.5j_문구표.md`)의 C.S-17.21.x · C.S-17.58.x, 「화면과 분기」의 S-17 · S-12.
- 원 담당: 프런트 보호자 화면은 도연. 17 화면 파일 이력은 baik(U4a) → 성민(U6 · 10/18 구조 리팩토링)이라 성민에게도 알린다.
- 이 문서 안의 가정 번호는 `DONE-SCREEN-PRACTICE-A<n>`, 편차 번호는 `DONE-SCREEN-PRACTICE-D<n>`이다. 아래 표에서는 `A<n>` · `D<n>`으로 줄여 쓴다.

## 1. 문제

연습이 끝난 뒤 17 화면이 자유대화 문구로 나왔다.

- 머리 '자유대화', 제목 '오늘 자유대화가 끝났어요', 마무리 중 제목 '오늘 자유대화를 마무리하고 있어요'가 고정이었다(`COPY.done`).
- 시간 줄이 'hh:mm ~ hh:mm · n분'이었다. 설계의 연습 시간 줄은 'hh:mm ~ hh:mm · 목표 N개'다.
- 하단 탭이 늘 '자유대화' 활성이었다.
- 17에서 시작 실패(FAILED · 시작 전)를 받으면 연습이어도 08(`/parent/free?failed=1…`)로 보냈다. 12는 연습이면 18(`/parent/practice?failed=1&reason=…`)로 보낸다.

## 2. 설계(문구표)

| ID | 상태 | 문구 |
|---|---|---|
| C.S-17.21.1 · C.S-17.58.1 | 연습 · 정리 끝 / 기기 응답 없이 끝냄 | 연습 · 목표 2개 |
| C.S-17.21.2 · C.S-17.58.2 | 같음 | 오늘 연습이 끝났어요 |
| C.S-17.21.3 · C.S-17.58.3 | 같음 | 10:40 ~ 10:52 · 목표 2개 |
| C.S-17.21.4 ~ 6 | 연습 · 정리 끝 | 기록을 정리하고 있어요 · 오늘 기록 보기 · 홈으로(자유대화와 같음) |
| C.S-17.21.7 ~ 10 | 연습 · 정리 끝 | 탭 홈 · 자유대화 · 연습 · 기록 |
| C.S-17.58.4 ~ 5 | 기기 응답 없이 끝냄 | 사유 카드(자유대화와 같음) |
| (없음) | 연습 · 마무리 중 | 문구표에 없다. C.S-17.73.x는 자유대화뿐이다. |

## 3. 바꾼 동작

| 상태 | 머리 | 제목 | 시간 줄 | 탭 |
|---|---|---|---|---|
| 연습 · 마무리 중(CLOSING) | 연습 · 목표 N개 | 오늘 연습을 마무리하고 있어요(제안, D1) | hh:mm ~ hh:mm · 목표 N개(끝 시각은 서버 시각) | 연습 활성, 자유대화 · 연습 잠김(그대로) |
| 연습 · 종료(ENDED · ABORTED 등) | 연습 · 목표 N개 | 오늘 연습이 끝났어요 | hh:mm ~ hh:mm · 목표 N개 | 연습 활성 |
| 연습 · 기기 응답 없이 끝냄 | 연습 · 목표 N개 | 오늘 연습이 끝났어요 | hh:mm ~ hh:mm · 목표 N개 | 연습 활성 |
| 연습 · 목표 수 모름 | 연습(제안, D2) | 위와 같음 | hh:mm ~ hh:mm(제안, D2) | 연습 활성 |
| 연습 · 시작 실패 | — | — | — | `/parent/practice?failed=1&reason=<코드>`로 이동(코드 없으면 `DEVICE_NO_ACK`) |
| 자유대화 · 모든 상태 | 바뀌지 않음 | 바뀌지 않음 | 바뀌지 않음 | 자유대화 활성(그대로) |

- 사유 카드(보호자 끝냄 · 아이가 끝냄 · 자동 종료 · 연결 끊김 · 기기 응답 없이 끝냄), 마무리 카드 본문, 정리 줄, 「오늘 기록 보기」 · 「홈으로」는 연습과 자유대화가 같다.
- 머리 줄에 `data-testid="done-header"`를 더했다.

바꾼 파일:

- 운영
  - `frontend/src/shared/copy/ko.ts`: `COPY.done.practiceHeader` · `practiceEndedTitle`(문구표 원문), `PROPOSED_COPY.done.practiceClosingTitle` · `practiceHeaderWithoutGoals`(제안), 함수 `donePracticeHeader` · `donePracticeTimeRangeLine`
  - `frontend/src/parent/features/done/presentation.ts`: `presentDone`이 머리 · 시간 줄 · 활성 탭까지 정한다. 세 번째 인자 `goalCount`(기본 `null`)
  - `frontend/src/parent/features/done/useDoneScreen.ts`: 목표 수를 조회 캐시의 진행 값에서 읽음, 연습 시작 실패 경로
  - `frontend/src/parent/common/api/live.ts`: `mergeLiveView`가 연습 마무리 · 종료 응답에서 진행 값이 비면 앞 값을 남긴다(4.1)
  - `frontend/src/parent/features/done/DoneScreen.tsx`: 표시 판단 결과로 머리 · 시간 줄 · 탭을 그린다(시간 줄 계산은 `presentation.ts`로 옮김)
  - `frontend/src/parent/common/activity/failure.ts`: `practiceFailedRoute` 추가
  - `frontend/src/parent/features/live/useLiveScreen.ts`: 같은 경로를 `practiceFailedRoute`로 만든다(동작 같음)
- 시험
  - `frontend/tests/parent/features/done/done-17.test.tsx`: 연습 사례 8개(12가 캐시에 남긴 진행 값 → 17이 바로 종료 · 실행 중 FAILED를 받는 사례 2개 포함)
  - `frontend/tests/parent/common/api/api-types.test.ts`: `mergeLiveView` 연습 진행 값 남김 1개
  - `frontend/tests/parent/features/done/done-17-presentation.test.ts`(새 파일): 표시 판단 단위 시험 8개
  - `frontend/tests/shared/copy.test.ts`: 연습 문구 함수 1개
  - `frontend/tests/parent/common/activity-logic.test.ts`: `practiceFailedRoute` 1개
- 문서: 이 파일

새 운영 파일은 없어서 `frontend/coverage-scope/*.json`은 바꾸지 않았다. 새 시험 파일은 CI가 막는 범위 목록(`practice-legacy.tests` · `web-push.tests`)의 대상이 아니다.

## 4. 고른 방안과 까닭

### 4.1 목표 수 출처 — 진행 조회의 `practice.goalCount`를 화면이 기억한다

지시서가 든 후보 셋을 코드로 다시 확인했다.

| 후보 | 지금 코드 사실 | 판단 |
|---|---|---|
| (i) `view.practice?.goalCount` | 진행 중 · 마무리 중에만 온다. 종료 상태면 realtime이 비운다(`SessionViewQuery.View`가 종료 세션의 `live`를 `NONE`으로 바꿈 → `SessionInternalController.wire`의 `practice`가 null). core가 사본으로 답하면 늘 null이다(`SessionViewService.fromCopy`). 프런트 병합(`mergeLiveView`)은 고치기 전에는 다음 응답의 null을 그대로 썼다. | **고름.** 대신 조회 캐시를 합칠 때 마지막 값을 남긴다. |
| (ii) `/api/records`의 `goalCount` | 그날 목록(`?date=`)에만 있고 기록이 저장된 뒤에만 나온다. 값은 정리 결과의 목표 기록 수(`practiceJson`의 goals 수)라 '고른 목표 수'와 다를 수 있다. 17은 지금 이 API를 부르지 않는다. | 열린 질문(Q1). |
| (iii) 연습 시작 202 응답의 `goalCount` | `startPort.ts`의 `parseStartResult`가 `sessionId`만 남기고 버린다. 18 → 12 → 17로 값을 넘기는 통로가 없고, 새로 고침하면 잃는다. | 열린 질문(Q1). |

- 정상 흐름은 18 → 12 → 17이다. 12와 17은 같은 조회 캐시(`queryKeys.session(sessionId)`)를 쓰고, 폴링 응답은 늘 `mergeLiveView`로 앞 값과 합쳐 캐시에 들어간다. 12는 마무리(CLOSING)나 종료를 받으면 바로 17로 간다.
- 12가 받는 마지막 응답이 늘 진행 값이 있는 마무리 중은 아니다. 다음 두 경우 12는 진행 중(ACTIVE) 다음에 진행 값이 빈 응답을 받고 17로 간다.
  - 12는 2초마다 읽는다(`mujoong.timing.parent.poll-interval`). 마무리가 한 번 읽는 사이에 끝나면 12는 CLOSING을 보지 못하고 바로 ENDED를 받는다.
  - 실행 중 · 마무리 중에 realtime이 다시 시작되면 FAILED(`SERVER_RESTART`)가 된다(`TransitionTable.SERVER_RESTART`). 이것은 시작 실패가 아니라 12 · 17 모두 '끝남'으로 다룬다(`liveOutcome` → `done`).
- 고치기 전에는 `mergeLiveView`가 이 빈 값으로 캐시의 진행 값을 덮었다(앞 값을 남기는 것은 진행 중 잘못 온 값(`practiceIssue === "invalid"`)뿐이었다). 그래서 정상 흐름에서도 17이 처음 그릴 때 목표 수가 없어 대체 표시(D2)가 나왔다(검토 지적).
- 지금은 `mergeLiveView`가 같은 세션의 연습 응답이 마무리 중이거나 종료 상태이고 진행 값이 비었으면 앞 값을 남긴다. 새 진행 값이 오면 그 값을 쓴다. 자유대화 응답 · 다른 세션 응답에는 이 규칙이 없다.
  - 12 표시는 바뀌지 않는다. 12는 이 상태를 받으면 바로 17로 가기 때문이다.
  - 17의 `useDoneScreen`은 캐시 값의 `practice.goalCount`를 그대로 읽는다. 앞서 두었던 화면 안의 목표 수 기억(`useGoalCount`)은 같은 일을 캐시 합치기가 하게 되어 지웠다. 기억을 한 곳(캐시 합치기)에만 두면 12에서 넘어온 값과 17이 받은 값이 같은 규칙을 따른다.
- API · 계약을 바꾸지 않고, 새 호출을 더하지 않는다(작업 지시: 있는 값으로 할 수 있는 만큼만).
- 한계: 이 세션의 진행 값을 한 번도 받지 못한 채 17에 들어오면 목표 수가 없다. 대체 표시(D2)다.
  - 종료 뒤 새로 고침, 푸시 알림으로 바로 17을 여는 경우(조회 캐시가 비어 있음).
  - 12가 진행 중 응답을 한 번도 받지 못하고 바로 마무리 · 종료를 받은 경우(예: 시작 직후 끝냄. 이때 마무리 중 응답에 진행 값이 있으면 채워진다, A9).

### 4.2 표시 판단을 `presentDone` 한 곳에 모은다

- 머리 · 제목 · 시간 줄 · 활성 탭을 모두 `presentDone`이 정하고, 화면은 그 값만 그린다. 시간 줄 계산(`timeRangeFor`)을 화면에서 옮겼다. 활동 종류에 따른 갈래를 한 곳에 두어 순수 함수 시험으로 덮기 위해서다.
- 세 번째 인자 `goalCount`는 기본값이 `null`이다. 기존 두 인자 호출(`activity-logic.test.ts`)은 그대로 자유대화 결과를 낸다.

### 4.3 연습 시작 실패 경로를 12와 공유한다

- 12(`useLiveScreen.ts`)가 문자열로 만들던 경로를 `failure.ts`의 `practiceFailedRoute`로 옮기고 12와 17이 같이 쓴다. 두 곳이 따로 만들면 다시 어긋날 수 있어서다. 12의 동작은 같다(기존 `practice-screens.test.tsx`의 `…reason=DEVICE_NO_ACK` 단언이 그대로 통과).
- 18은 `reason`을 `startFailureReason`으로 문장으로 바꿔 보인다(기존).

### 4.4 문구 위치

- 문구표 원문은 `COPY.done`에 C.S 번호 주석과 함께 두었다. '연습 · 목표 N개'와 '… · 목표 N개'는 자리를 채우는 함수다(다른 화면 문구 함수와 같은 꼴).
- 문구표에 없는 두 가지(연습 마무리 중 제목, 목표 수 모름의 머리)는 `PROPOSED_COPY.done`에 근거 주석과 함께 두었다. 목표 수 모름의 머리 값은 새 글자를 만들지 않고 `COPY.done.practiceHeader`('연습')를 가리킨다.
- 화면 파일에는 글자를 직접 쓰지 않았다(`react/jsx-no-literals` 통과).

## 5. 설계 편차

| 번호 | 내용 | 까닭 | 승인 |
|---|---|---|---|
| D1 | 연습 마무리 중 제목을 '오늘 연습을 마무리하고 있어요'로 보인다. | 문구표에 연습 마무리 중 상태가 없다(C.S-17.73.x는 자유대화뿐). 자유대화 문구를 연습에 보이면 지금 문제와 같다. 지시서 (e)의 예시 문구를 그대로 썼다. | 도연 승인 전 |
| D2 | 목표 수를 모르면 머리 '연습', 시간 줄 'hh:mm ~ hh:mm'(목표 수 · 분 없음)을 보인다. | 종료 뒤 처음 들어오면 목표 수가 오지 않는다(4.1). 틀린 숫자나 자유대화식 'n분'을 보이지 않으려고 줄인다. 지시서의 제안대로다. | 도연 승인 전 |
| D3 | 첫 응답을 받기 전(조회 캐시도 없을 때)에는 지금까지처럼 자유대화 마무리 문구가 잠깐 보인다. | 활동 종류를 응답 전에는 알 수 없다. 중립 표시로 바꾸면 자유대화 17의 기존 동작 · 시험이 바뀐다. 12에서 넘어오는 정상 흐름은 캐시가 있어 보이지 않는다. | 열린 질문(Q4) |

## 6. 가정

| 번호 | 가정 |
|---|---|
| DONE-SCREEN-PRACTICE-A1 | '목표 N개'의 N은 이번 연습에서 고른 목표 수, 곧 12 머리 'n/N번째 목표'의 N(`practice.goalCount`)이다. 기록 목록의 `goalCount`(정리 결과의 목표 기록 수)와는 다를 수 있다. |
| DONE-SCREEN-PRACTICE-A2 | 목표 수를 모를 때의 대체 표시는 지시서 제안(머리 '연습', 시간 줄 'hh:mm ~ hh:mm')을 따른다(D2). |
| DONE-SCREEN-PRACTICE-A3 | 마무리 카드 본문 '무중이 마무리 인사를 하고 있어요' · '기기가 멈추면 기록을 정리해요.'(C.S-17.73.4 · 5)는 활동과 무관한 문장이라 연습에도 그대로 쓴다. |
| DONE-SCREEN-PRACTICE-A4 | 마무리 중의 머리 · 시간 줄도 연습 꼴로 보인다. 자유대화 문구표가 마무리 중(C.S-17.73.1 · 3)과 정리 중(C.S-17.16.1 · 3)에 같은 머리 · 시간 줄 꼴을 쓰는 것과 같은 규칙으로 읽었다. |
| DONE-SCREEN-PRACTICE-A5 | 활동 종류가 응답에 없거나 모르는 값(`UNKNOWN`)이면 지금까지처럼 자유대화로 보인다. |
| DONE-SCREEN-PRACTICE-A6 | 목표 수 기억은 화면 상태에만 둔다. 브라우저 저장소에 넣지 않는다(새로 고침 뒤에는 대체 표시). 저장소 값은 다른 세션과 섞이거나 오래 남을 위험이 있어서다. |
| DONE-SCREEN-PRACTICE-A7 | 연습 시작 실패는 12와 똑같이 사유 코드를 늘 싣는다(코드가 없으면 `DEVICE_NO_ACK`). 08로 가는 자유대화처럼 기본 사유를 빼는 규칙은 연습에 쓰지 않는다(12의 기존 동작). |
| DONE-SCREEN-PRACTICE-A8 | 탭은 기존 4탭 막대(홈 · 자유대화 · 연습 · 기록)를 그대로 쓰고 활성만 바꾼다. 자유대화 문구표(C.S-17.16.7 ~ 9)가 탭을 3개로 적은 것은 활성 탭 이름이 머리와 겹쳐 빠진 것으로 읽었다. 정리 중 · 끝에 연습 탭을 누르면 18이다(S-17 분기 표, 기존 `routeForTab`). |
| DONE-SCREEN-PRACTICE-A9 | realtime은 마무리 중(CLOSING)에도 실행기가 살아 있으면 연습 진행 값을 낸다고 보았다(`IdleClock.publishLive`가 `practiceProgress()`를 그대로 쓴다, 정적 분석). 마지막 목표가 끝나 진행 값이 비어도 조회 캐시에 앞서 받은 진행 값이 있으면 `mergeLiveView`가 그것을 남긴다. 실제 세션으로는 확인하지 않았다. |
| DONE-SCREEN-PRACTICE-A10 | 마무리 중 · 종료 응답에서 진행 값이 빈 것은 '이 세션에 목표가 없음'이 아니라 '더 보내지 않음'이라고 보았다(종료 세션은 realtime이 비우고 core 사본에는 없다, 4.1). 목표 수는 한 세션 안에서 바뀌지 않으므로 앞 값을 남겨도 틀린 숫자를 보이지 않는다고 보았다. 마무리 중에 새 진행 값이 오면 그 값을 쓴다. |

## 7. 원 담당자와 맞출 것 · 열린 질문

| 번호 | 누구와 | 무엇을 |
|---|---|---|
| Q1 | 도연(성민 · 준하 참고) | 목표 수 출처. 지금은 (i) 진행 조회 값 + 화면 기억이다. 종료 뒤 처음 들어온 경우까지 채우려면 (ii) 정리 끝 뒤 기록 API의 목표 수를 17이 더 부를지(의미가 A1과 다를 수 있음), (iii) 시작 202 응답의 `goalCount`를 18 → 12 → 17로 넘길지(같은 탭에서만 유효), (iv) 종료 세션 조회에도 연습 요약(목표 수)을 남기도록 계약을 바꿀지(`contracts/` · core · realtime 같은 날 병합, 이번 범위 밖)를 정한다. |
| Q2 | 도연 | 연습 마무리 중 제목 제안 '오늘 연습을 마무리하고 있어요'(D1) 승인. 승인되면 문구표 개정 제안으로 올릴지도 정한다. |
| Q3 | 도연 | 목표 수 모름의 대체 표시(D2) 승인. |
| Q4 | 도연 · 성민 | 첫 응답 전 자유대화 문구가 잠깐 보이는 것(D3)을 중립 로딩 표시로 바꿀지. 바꾸면 자유대화 17 시험의 단언도 함께 바뀐다. |
| 알림 | 성민 | 17 화면 파일(`DoneScreen.tsx` · `useDoneScreen.ts` · `presentation.ts`)을 고쳤고, 12의 연습 시작 실패 경로를 `failure.ts`의 `practiceFailedRoute`로 옮겼다(동작 같음). |
| 알림 | 도연 · 성민 | 12 · 17이 같이 쓰는 `live.ts`의 `mergeLiveView`를 고쳤다. 연습 응답이 마무리 중 · 종료 상태이고 진행 값이 비면 앞 값을 남긴다(4.1). 12는 이 상태에서 바로 17로 가므로 12 표시는 같다. 다른 화면이 마무리 · 종료 뒤 진행 값을 '비었음'으로 읽어야 한다면 알려 달라(지금은 그런 화면이 없다). |

## 8. 사람이 할 일

- 도연과 Q1 ~ Q4를 정하고 성민에게 알린다(이 브랜치 작업자는 사람에게 묻지 않았다).
- PR을 열어 원 담당자 리뷰를 받고, GitHub `ci-gate`가 녹색인지 확인한 뒤 병합한다. 이 브랜치는 push하지 않았다.
- 실제 기기와 실제 세션으로 연습 한 바퀴(18 → 12 → 17)를 돌려 17의 머리 · 제목 · 시간 줄 · 탭이 바뀌는지 본다([실기기] 미확인). 특히 마무리 중에 목표 수가 오는지(A9)를 본다.
- 제안 문구가 승인되면 화면 설계 문구표 개정 제안(정본은 고치지 않고 RP 표로)을 남긴다.

## 9. 시험 결과

증거 종류: 모두 **[대역]**(시험 대역 — 가짜 HTTP 응답을 돌려주는 시험 도구와 jsdom)이다. **[실호출-합성] 안 함, [실기기] 안 함, 실제 AI 공급자 호출 없음.**

| 명령(`frontend/`에서) | 결과 |
|---|---|
| `npx --no-install vitest run tests/parent/features/done tests/parent/common/activity-logic.test.ts tests/parent/common/api/api-types.test.ts tests/parent/features/practice tests/parent/features/live tests/parent/features/activity-session-screens.test.tsx tests/shared/test-ids.test.tsx tests/shared/routes.test.tsx tests/shared/copy.test.ts` | 14 파일 · 252건 통과, 실패 0 |
| `npx --no-install vitest run tests --coverage` | 76 파일 · 1,017건 통과, 실패 0. 전체 줄 98.55% · 문장 97.22% · 분기 93.60% · 함수 96.85% |
| 바뀐 운영 파일 줄 커버리지(위 실행) | `DoneScreen.tsx` 100% · `useDoneScreen.ts` 100% · `presentation.ts` 96.55% · `live.ts` 100% · `failure.ts` 100% · `ko.ts` 100% · `useLiveScreen.ts` 97.4% |
| `MUJOONG_COVERAGE_SCOPE=practice-legacy npx --no-install vitest run $(cat coverage-scope/practice-legacy.tests) --coverage` | 3 파일 · 32건 통과, 범위 줄 96.06%(하한 80%) |
| `MUJOONG_COVERAGE_SCOPE=web-push npx --no-install vitest run $(cat coverage-scope/web-push.tests) --coverage` | 6 파일 · 89건 통과, 범위 줄 88.17%(하한 80%, `ko.ts`가 이 범위에 들어 있음) |
| `npx --no-install eslint .` | 오류 · 경고 0 |
| `npx --no-install tsc --noEmit` | 통과 |
| `npx --no-install next build` | 통과(`/parent/sessions/[sessionId]/done` 포함) |

- 시험 검증: `mergeLiveView`의 마무리 · 종료 진행 값 남김을 일부러 끈 코드로 돌리면 5건(병합 단위 시험 1건, 17 연습 사례 4건: 마무리 중 → 종료 목표 수 유지, 12 캐시 → 바로 ENDED, 12 캐시 → 바로 실행 중 FAILED, 기기 응답 없이 끝냄)이 실패하는 것을 확인하고 되돌렸다.
- 기존 자유대화 17 시험(`done-17.test.tsx`의 기존 11건, `done-17-result.test.tsx` 6건, `activity-session-screens.test.tsx`, `activity-logic.test.ts`의 17 사유 표)은 고치지 않고 그대로 통과했다. 기존 단언 · 하한은 낮추지 않았다.
- 백엔드는 바꾸지 않아 Gradle 시험은 돌리지 않았다.
