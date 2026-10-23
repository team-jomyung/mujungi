# U17 단위 시험 실행 지시

## 기준과 실행기

Loop-back 2: 기존 guardrail-live-updates.test.tsx의 F-R04 실패3개를 보존·해결했다(수정 전11개 중8 PASS/3 FAIL). 인증 세대·요청 순번·동일 세션 이탈/재진입 뒤 새 ACTIVE 성공→이전403 순서와, 세대·순번을 바꾸지 않은 AbortSignal 단독 취소를 검증한다. 구현 뒤 공통12·리뷰6·API16=34시험 PASS다. guardrail-review-regression의 현재403 session-only 제거/늦은 성공 금지/CSRF 및 api-client의401/403 의미를 유지한다. 기존 기능 명령은28파일322시험 PASS다. 최종 전체 결과는 verification-results.md 마지막 절을 따른다. 원래 완료된14단계의 기준선은 재실행하지 않았다. 브라우저/GUI/Next 서버 금지를 유지한다.

현재 Testing Contract의 test-after·Standard를 따른다. 기존 Vitest/jsdom·Testing Library와 v8 coverage·ESLint·Next build를 유지한다. 새 dependency/서버 저장소·공급자·브라우저 자동화 설치를 추가하지 않는다. runner/config는 frontend/vitest.config.ts와 package.json을 읽고 기존 실제 파일 명령으로 먼저 확인한다.

저장소 루트에서 실행 가능한 첫 단위 명령:

```sh
cd frontend && npx vitest run tests/parent/common/api/api-types.test.ts tests/device/audio/player.test.ts
```

이 명령은 기존 API/기기 범위에만 한정되고 새 시험 파일 생성 전에도 실행할 수 있다. 이후 각 층 구현→시험 작성/실행 순서를 지킨다.

## 새 시험 파일과 정확한 명령

새 파일은 해당 Step에서 작성한 뒤 아래 정확한 파일 필터로 실행한다. 경로/컴포넌트 분해 변경이 필요하면 실제 목록/범위/이 지시서를 함께 맞추고 이유를 요약한다. 없는 필터로 시험0개 성공을 만들지 않는다.

```sh
cd frontend && npx vitest run tests/parent/common/api/guardrail-presentation.test.ts
cd frontend && npx vitest run tests/parent/common/guardrail-live-updates.test.tsx
cd frontend && npx vitest run tests/parent/features/live/guardrail-participation.test.tsx
cd frontend && npx vitest run tests/parent/features/done/guardrail-done-presentation.test.ts
cd frontend && npx vitest run tests/parent/features/done/guardrail-done-screen.test.tsx tests/parent/features/done/guardrail-review-regression.test.tsx
cd frontend && npx vitest run tests/device/ws/guardrail-device-session.test.ts
cd frontend && npx vitest run tests/device/audio/guardrail-player.test.ts
cd frontend && npx vitest run tests/parent/features/live/guardrail-accessibility.test.tsx
```

상기 명령은 각각 저장소 루트에서 실행한다. 같은 셸에서 여러 줄을 이어 실행할 때는 작업 디렉터리를 중첩하지 말고 각 호출의 workdir을 frontend로 정해 cd 없이 실행해도 된다.

관련 기존 회귀의 정확한 파일 필터:

```sh
cd frontend && npx vitest run tests/parent/common/api/api-types.test.ts tests/parent/common/activity-logic.test.ts tests/parent/features/live/live-12.test.tsx tests/parent/features/live/p1-dialog.test.tsx tests/parent/features/done/done-17.test.tsx tests/parent/features/done/done-17-result.test.tsx tests/parent/features/activity-session-screens.test.tsx tests/parent/features/practice/practice-screens.test.tsx tests/device/ws/device-session.test.ts tests/device/ws/device-session-resume.test.ts tests/device/audio/player.test.ts tests/device/audio/player-progress.test.ts tests/device/audio/barge-in.test.ts tests/device/runtime/device-runtime-voice-session.test.ts tests/device/features/status/status-view.test.tsx tests/parent/common/api/api-client.test.ts tests/parent/common/api/account-api.test.ts tests/parent/features/login/dev-login.test.tsx tests/parent/features/login/login-01.test.tsx
```

기능 coverage 명령은 파일 생성/목록 검사 후 저장소 루트에서 실행한다:

```sh
cd frontend && MUJOONG_COVERAGE_SCOPE=guardrail-presentation npx vitest run tests/parent/common/api/guardrail-presentation.test.ts tests/parent/common/guardrail-live-updates.test.tsx tests/parent/features/live/guardrail-participation.test.tsx tests/parent/features/done/guardrail-done-presentation.test.ts tests/parent/features/done/guardrail-done-screen.test.tsx tests/parent/features/done/guardrail-review-regression.test.tsx tests/device/ws/guardrail-device-session.test.ts tests/device/audio/guardrail-player.test.ts tests/parent/features/live/guardrail-accessibility.test.tsx tests/parent/common/api/api-types.test.ts tests/parent/common/activity-logic.test.ts tests/parent/features/live/live-12.test.tsx tests/parent/features/live/p1-dialog.test.tsx tests/parent/features/done/done-17.test.tsx tests/parent/features/done/done-17-result.test.tsx tests/parent/features/activity-session-screens.test.tsx tests/parent/features/practice/practice-screens.test.tsx tests/device/ws/device-session.test.ts tests/device/ws/device-session-resume.test.ts tests/device/audio/player.test.ts tests/device/audio/player-progress.test.ts tests/device/audio/barge-in.test.ts tests/device/runtime/device-runtime-voice-session.test.ts tests/device/features/status/status-view.test.tsx tests/parent/common/api/api-client.test.ts tests/parent/common/api/account-api.test.ts tests/parent/features/login/dev-login.test.tsx tests/parent/features/login/login-01.test.tsx --coverage
```

.tests는 최종 기능 실행에 포함하는 위 정확한 파일 목록을 열거한다. 수정 계정/인증 귀속의 추가 기존 시험이 필요하면 그 정확한 경로도 함께 보강한다. 이 문서의 모든 run 명령은 U17과 직접 관련된 파일 필터만 사용한다. 전체 프런트 baseline/최종 검증은 계획 Step1/13 및 승인된 Construction Verification Command의 별도 관문이며 단위 명령으로 중복 기재하지 않는다.

## 모킹·데이터·관측

ApiClient 대역은 C1 현재/구버전/구조 오류·미래 enum·동일판/null·세션A/B 응답을 명시적으로 만든다. 지연 Promise·AbortSignal·가짜 시계로 취소 불이행과 늦은 수동/폴링 응답을 재현한다. P-01 재시도는 같은 requestId와 실패/권한/이동 단언을 보존한다.

인용 fixture는 동일 세션 items의 seq·speaker CHILD·final·uncertain·text·시각을 명시한다. 등록 표현과 다른 종료 원문, HTML처럼 보이는 text·한글/따옴표/역슬래시/줄바꿈, 부재/숨김·다른 화자/불확실/빈 행을 검증한다. 사실 없는 fixture의 기본은 null/UNKNOWN이며 성공을 임의 주입하지 않는다.

기기는 기존 가짜 음원/오디오·WebSocket harness로 실제 시작/끝/중단/실패/끊김·이전 세션 콜백을 따로 발생시킨다. 전송 메시지/일반 대화 시작·원음 시작/완료 보고·확인 호출 수와 실제 화면/이동을 검증한다. 합성 대역에 운영 승인 말소리라고 표시하지 않는다. 실제 유료 공급자/아동 데이터·mujoong-manual을 사용하지 않는다.

## 커버리지·품질·증거

8컴포넌트 약51개(각5–8개)와 핵심 경계 통합을 계획한다. 기능 범위 json은 실제 수정 운영 소스 전체로 작성하고 빈/없는 경로는 실패시킨다. 기존 모듈 전체/기능 LINE80%·기존 단언·import/계약·시간 설정을 낮추거나 skip으로 우회하지 않는다. 부적합이 있으면 실패와 조치를 기록한다.

키보드/초점·접근 가능한 제목/동일 상태 반복 낭독은 컴포넌트로 확인한다. 실제320px/200%·긴 인용/기기 회전/조작 점검은 사용 가능한 환경에서 별도 증거를 남기며 jsdom PASS를 실제 브라우저/기기 성공으로 쓰지 않는다. 실행하지 못한 항목은 한계를 명시한다.

baseline-results.md와 verification-results.md는 명령·실패/수정/재실행·시험 수·LINE 분모/분자·기능/전체·대역/실제·앱 지문/동결 시점을 구분한다. U16 서버 전사 지적/운영 승인 음성 미준비·최종 verify.sh 인계를 누락하지 않는다.

## Loop-back 1의 한정 갱신

부모가 작성한 tests/parent/features/done/guardrail-review-regression.test.tsx의 원래2개 실패 단언을 보존하고4개 반례를 추가해6개로 실행한다. 기존 신규51개·회귀261개를 유지하여 기능 목록은28파일318시험이다. 위 정확한 단위/기능 명령에 해당 파일을 추가했으며 운영 범위16파일과 LINE80%·기존 runner는 유지한다. 최초 기준선과 이번 결함 기준선/재실행은 각각 baseline-results.md 및 verification-results.md의 Loop-back 1 절에 보존한다. 브라우저/Next 서버는 이번 실행에서 금지다.
