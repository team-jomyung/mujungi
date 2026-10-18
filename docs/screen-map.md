# 화면 번호 ↔ 파일 경로 대응표

화면 설계 v1.5j의 화면 번호로 프런트 파일을 찾는 표다. 구조 · 이름 리팩토링(작업 기록 `261018-structure-refactor`)은 화면 파일 이름에서 번호 접미(`Login01.tsx` 등)를 없애고 기능 폴더로 옮긴다. 번호는 이 표에서 찾는다.

- **상태: 단계 2 반영 + 기능끼리 import 정리(화면 훅 분리 · 요청 · 문구 위치 정리 끝).** "지금 경로"는 이동 전 경로, "이동 뒤 경로"는 실제 경로다. 화면 훅 9개는 만들었고, `account.ts` · `queries.ts`는 기능별 `api.ts`로 나눴으며, 연습 `api.ts` · `copy.ts`는 하나씩으로 합쳤다. 마무리(Step 25)에서 이 표를 실제 결과로 다시 확인한다.
- 기능끼리 import 정리(계획 외 추가, Step 11e): 둘 이상의 기능이 쓰는 파일은 `common/` 아래로 옮겼다. 아래 "단계 2 실제 결과 메모"와 1절의 "함께 옮기는 화면 곁 파일"에서 `features/{home,child,live}/api.ts` · `live/presentation.ts` · `live/useLivePolling` · `done/` 폴링 훅 · `free/failure.ts` · `practice/PracticeHeader`는 3절 끝의 표에 적은 새 위치에 있다. 옮긴 목록은 작업 기록 `construction/code-generation/cross-feature-imports.md`에 있다.
- 단계 2 실제 결과 메모: 훅 9개는 화면 곁 같은 폴더의 `use<기능>Screen.ts`다. 요청 함수는 `features/{consent,child,home,device,free,live,login}/api.ts`와 `common/api/{account,queries}.ts`(로그인 · 계정 · 공통 질의 설정만)에 있다. 연습 진행 파싱(`practiceProgress`)은 공통 코드가 쓰므로 `common/api/practiceProgress.ts`로, 말풍선 흐름 계산(`buildTimeline` · `gapText`)은 `common/components/transcriptTimeline.ts`로 옮겼다.
- 단계 1 실제 결과 메모: 화면 파일 17개 · 기기 3개는 표대로 옮겼다. 화면 export 이름도 번호를 지웠다(`Login01` -> `LoginScreen` 등, `presentDevice05` -> `presentDeviceScreen`, `D0View` -> `FirstBootView`, `T19View` -> `StatusView`).
- 경로는 모두 `frontend/src/` 아래다. 이동 뒤 보호자 경로는 `parent/features/<기능>/`, 기기 경로는 `device/features/<기능>/`이다.
- 라우트(`src/app/...`)는 바뀌지 않는다. 주소 `/parent/...`는 그대로이고 `page.tsx`의 import만 바뀐다.
- "화면 훅"은 리팩토링 단계 2(Step 9 ~ 10)에서 요청 · 오류 분기 · 이동(`router.*`)을 옮겨 담는 `use<기능>Screen` 훅이다. 훅이 없는 화면은 "—"로 적었다.

## 1. 보호자 화면 (`parent/screens` 17개)

| 화면 설계 번호 | 화면 | 라우트 | 지금 경로 (`parent/…`) | 이동 뒤 경로 (`parent/features/…`) | 화면 훅 |
|---|---|---|---|---|---|
| 01 | 보호자 로그인 | `/parent/login` | `screens/Login01.tsx` | `login/LoginScreen.tsx` | — |
| 01 (구글 콜백) | 구글 콜백 교환 | `/parent/login/callback` | `screens/LoginCallback.tsx` | `login/LoginCallbackScreen.tsx` | — |
| (번호 없음) | 시험 로그인(dev 프로필만) | `/parent/dev-login` | `screens/DevLogin.tsx` | `login/DevLoginScreen.tsx` | — |
| 02 | 기록 · 동의 안내 + 약관 시트 | `/parent/consent` | `screens/Consent02.tsx` | `consent/ConsentScreen.tsx` | `consent/useConsentScreen` |
| 03 | 아이 정보 | `/parent/child` | `screens/Child03.tsx` | `child/ChildScreen.tsx` | `child/useChildScreen` |
| SET-01 | 종료 표현 설정 | `/parent/child/stop-expressions` | `screens/StopExpressions01.tsx` | `child/StopExpressionsScreen.tsx` | `child/useStopExpressionsScreen` |
| 04 | 홈 | `/parent` | `screens/Home04.tsx` | `home/HomeScreen.tsx` | `home/useHomeScreen` |
| 05 | 우리 집 기기 | `/parent/device` | `screens/Device05.tsx` | `device/DeviceScreen.tsx` | `device/useDeviceScreen` |
| 05a | 기기 등록 | `/parent/device/register` | `screens/Register05a.tsx` | `device/DeviceRegisterScreen.tsx` | `device/useDeviceRegisterScreen` |
| 08 | 자유대화 준비 | `/parent/free` | `screens/Free08.tsx` | `free/FreeTalkScreen.tsx` | `free/useFreeTalkScreen` |
| 12 | 진행(최소) + 끝내기 | `/parent/sessions/[sessionId]` | `screens/Live12.tsx` | `live/LiveScreen.tsx` | `live/useLiveScreen` |
| 17 | 활동 끝 | `/parent/sessions/[sessionId]/done` | `screens/Done17.tsx` | `done/DoneScreen.tsx` | `done/useDoneScreen` |
| 18 | 연습 | `/parent/practice` | `screens/Practice18.tsx` | `practice/PracticeScreen.tsx` | — |
| 26 | 기록 목록 | `/parent/records` | `screens/Records26.tsx` | `records/RecordsScreen.tsx` | — |
| 27 | 기록 상세 | `/parent/records/[sessionId]` | `screens/RecordDetail27.tsx` | `records/RecordDetailScreen.tsx` | — |
| 28 | 리포트 기간 선택 | `/parent/reports/new` | `screens/ReportRange28.tsx` | `reports/ReportRangeScreen.tsx` | — |
| 30 | 리포트 | `/parent/reports/[reportId]` | `screens/Report30.tsx` | `reports/ReportScreen.tsx` | — |

- 화면 훅 이름은 설계 3절의 `use<기능>Screen`을 따른다. 대상은 지정 화면 9개(Live · Home · Done · Device · DeviceRegister · Child · StopExpressions · Consent · FreeTalk)이고, 나머지 화면의 훅 분리는 이번 범위가 아니다.
- 함께 옮기는 화면 곁 파일: 홈 `activity/homeActivity` → `home/presentation.ts`, 진행 `activity/livePresentation` · `hooks/useLivePolling` → `live/presentation.ts` · `live/useLivePolling`, 활동 끝 `activity/donePresentation` · `hooks/useResultStatusPolling` → `done/presentation.ts` · `done/` 폴링 훅, 자유대화 `activity/freeFailure` → `free/failure.ts`, 리포트 `hooks/useReportPolling` → `reports/` 폴링 훅, 연습 `practice/*` → `practice/` 아래(`api.ts` · `copy.ts` 하나씩으로 합침).

## 2. 기기 화면 (`device/components` 3개)

| 화면 설계 번호 | 화면 | 지금 경로 (`device/…`) | 이동 뒤 경로 (`device/features/…`) |
|---|---|---|---|
| D0 | 처음 켜기 | `components/D0View.tsx` | `first-boot/FirstBootView.tsx` |
| T19 | 상태 화면 | `components/T19View.tsx` | `status/StatusView.tsx` |
| (번호 없음) | `?debug=1` 상태 로그 | `components/DebugLog.tsx` | `debug/DebugLog.tsx` |

- 기기의 인프라 폴더(`audio` · `ws` · `mic` · `power` · `runtime` · `machine` · `api`)와 `DeviceApp.tsx`는 옮기지 않는다.

## 3. 공통 코드의 위치 (화면이 아닌 것)

| 지금 (`parent/…`) | 이동 뒤 (`parent/common/…`) |
|---|---|
| `api/{apiClient,csrf,types}.ts` | `api/` |
| `api/account.ts`의 로그인 · 계정 부분 | `api/account.ts` (동의는 `features/consent/api.ts`, 자녀 · 정지 표현은 `features/child/api.ts`) |
| `api/activity.ts` · `api/results.ts` | `api/activity.ts` · `api/results.ts` |
| `api/queries.ts`의 공통 부분 | `api/queries.ts` (화면 전용 함수는 각 기능 `api.ts`로) |
| `api/push.ts` | `push/api.ts` |
| `api/reports.ts` | `features/reports/api.ts` |
| `hooks/useBackGuard.ts` | `hooks/useBackGuard.ts` |
| `push/*` | `push/` |
| `navigation.ts` · `oauth.ts` · `ParentProviders.tsx` | `common/` 바로 아래 |
| `parent/{parent,account,activity,result}.css` | `styles/` (기능별로 나누지 않음) |
| `components/{BottomTabBar,BottomSheet,ConfirmDialog,LiveRegion,MonthCalendar,TranscriptList}.tsx` | `components/` |
| 한 기능만 쓰는 컴포넌트(`ChipGroup` `TextArea200` → free, `CodeInput` → device, `DeviceCard` `SettingsSheet` `PushSettingsRow` → home, `EndReasonCard` `SceneChip` → records, `NoticeDialog` `StatusLine` → live) | 각 기능 폴더 |

여러 기능이 쓰는 기능 파일(Step 11e): 두 기능 이상이 쓰면 `features/`가 아니라 `common/`에 둔다.

| 이전 위치 (`parent/features/…`) | 쓰는 기능 | 새 위치 (`parent/common/…`) |
|---|---|---|
| `home/api.ts` | home · child · device · done · live | `api/home.ts` |
| `child/api.ts` | child · device | `api/child.ts` |
| `live/api.ts` | live · done(폴링 훅 경유) | `api/live.ts` |
| `practice/api.ts`의 목표 선택 요청 | practice · records | `api/practice.ts` (연습 시작 요청 `practiceStartPort`는 `features/practice/startPort.ts`로 잘라 둠) |
| `free/failure.ts` | free · done · live · practice | `activity/failure.ts` |
| `live/presentation.ts` | live · done | `activity/livePresentation.ts` |
| `live/useLivePolling.ts` | live · done | `hooks/useLivePolling.ts` |
| `done/useResultStatusPolling.ts` | done · records | `hooks/useResultStatusPolling.ts` |
| `practice/PracticeHeader.tsx` | practice · live | `components/PracticeHeader.tsx` |
| `practice/copy.ts`의 `U5B_COPY` | practice · live | `copy/practice.ts` (목록 문구 `PRACTICE_COPY` 등은 `features/practice/copy.ts`에 남음) |

- 컴포넌트 목적지는 Step 6에서 import 그래프를 다시 확인했고 위 표와 차이가 없었다(16개 모두 표대로 이동). 마무리(Step 25)에서 한 번 더 대조한다.

## 4. 번호로 찾는 방법

1. 화면 설계 v1.5j에서 화면 번호를 확인한다(예: 12 진행).
2. 이 표 1절에서 번호 행을 찾아 "이동 뒤 경로"를 연다(예: `parent/features/live/LiveScreen.tsx`).
3. 그 화면의 요청 · 오류 · 이동 로직은 같은 폴더의 `use<기능>Screen` 훅과 `api.ts`에 있다.
4. 예전 문서(`docs/units/**`)가 `Live12` 같은 옛 이름을 적고 있으면 이 표의 "지금 경로" 열로 읽는다. 이력 문서는 고치지 않는다.
