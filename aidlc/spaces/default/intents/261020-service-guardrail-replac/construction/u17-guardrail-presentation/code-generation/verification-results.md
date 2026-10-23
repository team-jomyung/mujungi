# U17 실제 검증 결과

현재 수정/동결 결과는 마지막 「Loop-back 2」 절이다. 이전31파일/1049시험과32파일/1055시험·지문은 역사로 보존하며 최신 결과·동결 지문은 마지막 절에서 확인한다.

## 검증 대상과 동결

- 브랜치: feat/service-guardrail-replacement. 기준 HEAD: 2e3d7b0e0796844166fb54b21220512ff20ce9e1.
- Testing Contract: sha256:231770ff8fc8e342a5163711626da88820010bbc0b413c96ec9fc94bb2241ce4, test-after/Standard.
- 운영 소스16·시험13·범위2=31파일. source-manifest.json과 git의 frontend 최종 변경 목록을 대조했다. 최종 삭제 파일0·manifest 누락0. package/lockfile/vitest.config.ts 변경0.
- 앱 동결: 2026-10-21T04:17:32Z. 이후 앱/시험/범위 편집 없이 산출물만 완성했다.
- 이 문서의 재현용 바이트 지문: sha256:7bd8189c8f2cc6b03335f1fc90d367a6e2870ee3e0f1be4770ef432a9ff65909. 정렬한31개 행의 `path + TAB + sha256(file bytes) + LF`를 이어 붙여 SHA-256으로 계산했다. 엔진 발급 Source Fingerprint와 구분한다. 엔진 검토 바인딩은 부모가 발급한다.
- 기존 backend/contracts/infra/U16·다른 intent의 변경은 보존했다. 커밋/푸시/병합/배포/운영 migration 없음.

## 기준선과 최종 관문

아래 명령의 workdir은 frontend다. 기준선은 앱 편집 전 새로 실행한 baseline-results.md 결과이며 U16의 이전 결과를 재사용하지 않았다.

| 관문 | 기준선 | 최종 실제 결과 |
|---|---|---|
| npm run lint | 종료0 | 종료0 |
| npx vitest run tests --coverage | 75파일998시험 PASS | 종료0,83파일1049시험 PASS, skipped0 |
| 모듈 LINE | 3455/3506=98.54% | 3515/3566=98.56% |
| 모듈 statements | 3973/4087=97.21% | 4058/4174=97.22% |
| npm run build | 종료0 | 종료0, TypeScript/production build 성공 |
| npm run typecheck | 실행기/빌드가 대조 | 초기 타입오류2건 수정 뒤 종료0; 최종 build도 타입 검사 성공 |
| 기능 coverage | 신규 범위 없음 | 종료0,27파일312시험 PASS, LINE763/770=99.09% |
| git diff --check -- frontend | 앱 미변경 | 종료0 |

기능 statements804/816=98.52%, branches665/690=96.37%, functions172/177=97.17%. 모듈 branches3105/3313=93.72%, functions1059/1094=96.80%. 적용 하한은 기존 LINE80%만이다. 분기/함수 하한·skip·coverage 제외를 새로 만들지 않았다. 마지막 frontend/coverage 보고서는 기능 범위 실행 결과다. 전체 수치는 전체 실행 콘솔에서 기록했으며 두 분모를 섞지 않는다.

## 층별 구현 후 시험

각 행은 그 층 구현 → 해당 파일 작성 → 아래 실제 명령 실행의 순서다. 명령은 frontend에서 `npx vitest run <시험 파일>`로 실행했다.

| Step | 시험 파일 | 실제 결과 |
|---|---|---|
| 3 | tests/parent/common/api/guardrail-presentation.test.ts | 6 PASS |
| 5 | tests/parent/common/guardrail-live-updates.test.tsx | 8 PASS |
| 6 | tests/parent/features/live/guardrail-participation.test.tsx | 6 PASS |
| 7 | tests/parent/features/done/guardrail-done-presentation.test.ts | 8 PASS |
| 8 | tests/parent/features/done/guardrail-done-screen.test.tsx | 6 PASS |
| 9 | tests/device/ws/guardrail-device-session.test.ts | 6 PASS |
| 10 | tests/device/audio/guardrail-player.test.ts | 6 PASS |
| 11 | tests/parent/features/live/guardrail-accessibility.test.tsx | 5 PASS |

신규51시험. 전체1049−기준선998과 일치한다. 같은 컴포넌트의 하나의 시험 안에서도 필드·잘못된 seq·모드·상태를 경계 묶음으로 단언하며 기존 시험을 삭제하지 않았다. 기존 관련19파일261시험과 신규8파일51시험이 기능27파일312시험이다. 첫 실행기 확인 한정2파일33시험도 PASS다.

## 초기 실패·수정·재실행

1. 첫 관련 회귀19파일261시험은8 FAIL/253 PASS였다. 모두 아래 승인된 사실 표시/현재 세션 경계의 이전 기대와 충돌했다.
   - DECLINED에 NO_RESPONSE 사실이 없는데 무응답으로 기대하던 예: 해당 무응답 예만 명시적 NO_RESPONSE를 주고 신규 시험으로 null/UNKNOWN 일반 문구를 함께 단언.
   - CHILD_STOP의 기본 등록 표현/세션 종료 시각 인용: 실제 참조7번 CHILD final·uncertain=false의 다른 원문과10:12 행 시각으로 대조. 원문 없는 순수 표시는 인용 null도 단언.
   - CLOSING 자체를 인사로 기대: 해당 실제 인사 예에 PLAYING을 명시하고 인사/일반 note 줄을 따로 대조. 미조회는 로딩이며 인사 null을 추가 단언.
   - 끊김 뒤 STARTED 송신 및 다른 세션 STOP 기대: 현재 세션 외 표식/명령 차단을 단언. 새 세션에서 같은 opId는 한 번 실행되고 중복은 억제됨도 추가 대조.
2. 첫 typecheck는 OUTPUT_END의 nullable sessionId와 변경된 실제 원문 copy helper 시험 호출2건을 지적했다. nullable 현재 세션 검사와 명시 원문 인자로 수정했다.
3. 수정 뒤 기능27파일312시험 PASS·99.09%, typecheck PASS. 불확실 여부가 누락된 행을 인용하지 않는 경계를 추가로 강화하고 관련2파일21시험 PASS를 확인했다.
4. 최종 전체 lint/83파일1049시험/production build 모두 PASS. 마지막 기능 재실행도312시험·763/770 PASS다. 권한/요청/실패/숫자·연습/기기 기존 단언, 모든 기존 시험 수와 LINE80%를 유지했다.

## 기능 범위와 정확한 명령

frontend/coverage-scope/guardrail-presentation.json은 실제 수정 운영16파일 전체다. 기존 vitest.config.ts의 빈 파일 목록·존재하지 않는 파일·빈 시험 목록·없는 시험 파일 실패 규칙을 유지한다. .tests의27개 파일은 모두 실제 존재하며 아래 실행 목록과 같다. apiClient 변경으로 기존 api-client/account-api/dev-login/login-01 네 파일을 unit-test-instructions.md의 관련/기능 명령과 함께 추가했다.

```sh
MUJOONG_COVERAGE_SCOPE=guardrail-presentation npx vitest run tests/parent/common/api/guardrail-presentation.test.ts tests/parent/common/guardrail-live-updates.test.tsx tests/parent/features/live/guardrail-participation.test.tsx tests/parent/features/done/guardrail-done-presentation.test.ts tests/parent/features/done/guardrail-done-screen.test.tsx tests/device/ws/guardrail-device-session.test.ts tests/device/audio/guardrail-player.test.ts tests/parent/features/live/guardrail-accessibility.test.tsx tests/parent/common/api/api-types.test.ts tests/parent/common/activity-logic.test.ts tests/parent/features/live/live-12.test.tsx tests/parent/features/live/p1-dialog.test.tsx tests/parent/features/done/done-17.test.tsx tests/parent/features/done/done-17-result.test.tsx tests/parent/features/activity-session-screens.test.tsx tests/parent/features/practice/practice-screens.test.tsx tests/device/ws/device-session.test.ts tests/device/ws/device-session-resume.test.ts tests/device/audio/player.test.ts tests/device/audio/player-progress.test.ts tests/device/audio/barge-in.test.ts tests/device/runtime/device-runtime-voice-session.test.ts tests/device/features/status/status-view.test.tsx tests/parent/common/api/api-client.test.ts tests/parent/common/api/account-api.test.ts tests/parent/features/login/dev-login.test.tsx tests/parent/features/login/login-01.test.tsx --coverage
```

## 증거 종류와 실제 브라우저 한계

| 증거 | 수행 결과 |
|---|---|
| Vitest/jsdom·Testing Library/API·오디오 대역 | 위 모든 PASS. 합성 텍스트/가짜 fetch/Web Audio·WS harness 사용 |
| 실제 브라우저320px/200% 레이아웃·키보드 | 미완료. 기존 Chromium/CDP 세션 기동 뒤 Page.navigate가 완료되지 않아 실제 화면/레이아웃/키보드 합격 증거 없음 |
| 실기기 조작·회전·청취/이해 | 미실행 |
| 실제 유료 공급자/아동 데이터 | 사용0·실행0 |
| 실제 대역 서버 통합/전체 verify.sh | 이번 단계 미실행, Build and Test로 인계 |

NFR5.2는 Deferred다. P-03 접근 가능한 이름/확인 초점/양방향 Tab, P-01 취소 초점·복귀, 동일 제목 DOM 유지/재초점0, 안전한 텍스트·줄바꿈·자유대화/연습 경계의 **가상 DOM** 시험은 PASS다. 실제320px/200% 레이아웃·브라우저 키보드와 기기 조작/회전은 별도 후속 점검이 필요하다. 인용 전체는 자동 낭독 영역 밖이며 같은 DOM 상태를 유지하는 시험만으로 실제 스크린리더 낭독 성공을 인증하지 않는다.

실점검 시도는 격리 Next127.0.0.1:3187과 기존 캐시 Chromium만 사용했다. 처음 일반 Chromium은 crashpad 초기화 오류로 종료됐고, headless shell CDP는 기동/연결됐으나 페이지 이동이 시간초과했다. 이후 사용자의 keyring 창 문의를 받아 모든 시도를 중단했고 브라우저를 다시 띄우지 않았다. 일반 Chromium 초기화가 시스템 keyring 요청을 유발했을 가능성이 있으나 직접 keyring 명령·비밀번호 입력/생성·비밀 저장소 설정 변경은 하지 않았다.

실행 플래그는 `--headless --no-sandbox --disable-gpu --remote-debugging-port=0 --user-data-dir=/tmp/u17-chrome-2 about:blank`였다. 본인이 시작한 CDP 세션57114/45840과 Next62782만 Ctrl-C로 종료130 확인, 앞선55403은 timeout→chrome.kill 종료1이었다. 정리 뒤3187 healthz curl은 종료7/HTTP000(접속 불가)이었다. 다른 브라우저·시스템 keyring·manual 스택은 건드리지 않았다. 일시 생성 AGENTS.md 제거·next-env.d.ts production 경로 복귀로 최종 추가 source 영향은 없다.

## 미해결 인계

- U16 R-01: 안내 전 전사 항목의 늦은 조각 재수락은 미해결 서버 귀속 문제다. UI가 ACTIVE를 표시한다고 해소하지 않는다. Build and Test에서 실행기 순서·WAITING_CHILD/원음 시작0·새 항목 수락을 별도 회귀·수정 검증한다.
- 운영 승인 말소리가 없다. 빈 운영 카탈로그 fail-closed 상태와 기존 합성 음원 대역을 구분한다. 음성 생성/청취 승인·운영 배포는 수행하지 않았다.
- NFR3.3의 프런트 관문은 PASS지만 전체 verify.sh·실제 대역 서버 통합은 Deferred다. NFR5.2의 실제 화면 부분도 Deferred다.
- 사용자 데이터·URL·query key·브라우저 저장소/로그에 전사/원음/정책 본문·해시·비밀을 새로 저장하지 않는다. 원문 인용은 이미 허용된 같은 세션 items의 안전한 React text만 쓴다.

## 동결 파일 SHA-256

| 경로 | SHA-256 |
|---|---|
| frontend/coverage-scope/guardrail-presentation.json | ce5ad78978d2b8c884cfc62da338a37b14631b2a0c605cd94387bb04fd2869c4 |
| frontend/coverage-scope/guardrail-presentation.tests | 8a3a147c585e658f1dafc82267dc1a9a9e393a262b0a5e3aeb825ac694db11a7 |
| frontend/src/device/audio/player.ts | 1cfb72145e8dda49cf27de894c65cca5f4a3c581ce0fad94448873b6f19d7ea2 |
| frontend/src/device/ws/deviceSession.ts | 0f25b964d37a720488678779733784a745c25aa3439396efa105ba70778809f7 |
| frontend/src/parent/common/activity/livePresentation.ts | c25ad441f18ea9bd6a3c6e39983ba4d4a9f1e22d3c49e5035e1099ad2a2fe00b |
| frontend/src/parent/common/api/apiClient.ts | 868eeb6c3ee8b13175aa78ccf5793f2e9f83aabef50dbfefb77b3496b7f41c69 |
| frontend/src/parent/common/api/guardrailPresentation.ts | 740657c75e849fc1d432fc00321aaa4208fca0eff0c0c61ad4d425886b700b45 |
| frontend/src/parent/common/api/live.ts | c350bf0bfd5cf128eff159dec96f7d8221f2f1eb2cf8d35a16b896cbbf6c4f39 |
| frontend/src/parent/common/api/liveUpdates.ts | 8d36f5d7df545f8ccfe4c9efbaa8ff867a4e29507244d58ca8e6a66b59db9a45 |
| frontend/src/parent/common/api/types.ts | ae380845adcf41cfc588d00adcf7b2a09210aa9e161ec5b97ffb30bc89676d14 |
| frontend/src/parent/common/hooks/useLivePolling.ts | 042bf15fe9141395c40b620aac68643d39925fc848eb26accf9eb81005866499 |
| frontend/src/parent/features/done/DoneScreen.tsx | 15d99b0dc4def427bfc411cd4c333c53e5e822268c7476bb30fda1f5eb6d9b71 |
| frontend/src/parent/features/done/presentation.ts | 47f0c663ef2cebb6d8b47812775840ddcd0b4b616453fab9676af3a34dc8e9d2 |
| frontend/src/parent/features/done/useDoneScreen.ts | 5977959eaf32660c2d2328a875003a1e839b5faa2c07bd90421a22530da9839e |
| frontend/src/parent/features/live/LiveScreen.tsx | ce151b1b256baa88cfd1d23e6362284ae21552fb4ed46cc1005974229f8b2cd1 |
| frontend/src/parent/features/live/NoticeDialog.tsx | 270f214ed657fd5d75df9186ba77efec5b59ccc1788a6b770656ce94c5abbfbc |
| frontend/src/parent/features/live/useLiveScreen.ts | 81680ebadb0050e6b02567d0077bc04c587474ad95dfec82d51923a1bb1cd332 |
| frontend/src/shared/copy/activity.ts | 354fdd673e15f42e188b62e220a46410b18f987c9b7e80850bb8559a30cbf49d |
| frontend/tests/device/audio/guardrail-player.test.ts | f02064e923048fc1b9c069d6d3b7b823fe082bcbda4f3a7d26cb982a3c8a6a15 |
| frontend/tests/device/ws/device-session-resume.test.ts | e29609b177e0dc6f7d5c7c271a3ea501d169c0d9969d3209d4e22f2f5e84dc0c |
| frontend/tests/device/ws/device-session.test.ts | 7bdb0317420a699eaa8640e2b42c086d4e3e7d9630880d6ef1735f4081bfff0d |
| frontend/tests/device/ws/guardrail-device-session.test.ts | c7f355a275df2e1a87a26657bf21aea9f7fe9d6028c7eb17f0e8bb13d14b5d4f |
| frontend/tests/parent/common/activity-logic.test.ts | 85a5713cfdc73f3c623698de3e69e7e3f37b68e43c8a0d6ef7b77d49b300d8ea |
| frontend/tests/parent/common/api/guardrail-presentation.test.ts | ac913cf14fb2f8fc9c21cc5bc490f82cc6380d00d50ae21e07861449ff833683 |
| frontend/tests/parent/common/guardrail-live-updates.test.tsx | d4288e4012875bd3e0fa70ef21d03749a3f7da58ab7e3685dabd292b7dda7069 |
| frontend/tests/parent/features/activity-session-screens.test.tsx | 51f93ebeb31826218ce3bf5e0c03cf230aa0fe45d4600285a3099d875bcd4ced |
| frontend/tests/parent/features/done/done-17.test.tsx | e371a5356f079e7bcd5e8d105f25ac592446740af807a84083efcb5d63d5facd |
| frontend/tests/parent/features/done/guardrail-done-presentation.test.ts | 03f487672d5be46309c8928e9eb495abaa77fb1b1e1cdc35d6948bfdc82c324d |
| frontend/tests/parent/features/done/guardrail-done-screen.test.tsx | 257d1f7feb8e8bf39cc5f071208bcab3a5704a96353f79f8471c57c58ca65642 |
| frontend/tests/parent/features/live/guardrail-accessibility.test.tsx | 679484e99a6f7190feb81d52c18b39257b70eecb4c083ae33b79d889ada72185 |
| frontend/tests/parent/features/live/guardrail-participation.test.tsx | fd6949b38e6e69594b7f7608d337fd6cf6d2b6456a43c50378aefadb6f367331 |

## Loop-back 1 — 현재 수정·검증·동결 결과

### 실제 수정 경계와 원인

F-R01/R-01은 online&&error=null을 사실 신뢰로 쓴 것이 원인이었다. 현재 조회의 연결 사건 세대/요청 귀속과 실제 적용 객체에 성공 근거를 부여한다. offline/online만으로는 이전 PLAYING을 복원하지 않으며 사건 전 대기 응답·낮은 상태판·실패·취소·다른 세션/이전 요청은 새 근거가 아니다. 동일 판·동일 응답이 구조 공유로 같은 캐시 객체가 돼도 현재 성공 뒤에만 신뢰한다. 기존 폴링2초·공유·retry:false·no-store·시간 예산을 유지하고 별도 요청/타이머/AI 호출은 없다.

F-R02/R-02는 진행 GET403 뒤 이전 민감 cache가 남은 것이 원인이었다. readCurrentLiveView가 그 세션403에서 해당 세션의 data/items/facts와 진행 귀속만 무효화하고 폴링은 권한 거절에서 멈춘다. 이전 대기 응답은 복구하지 못하며 다른 세션·계정 cache·CSRF 갱신 재시도는 유지한다. 기존401/로그아웃/로그인 전환 동작은 그대로다.

R-03은 기존 target이 다중 경로/설명 문자열이어서 자동 추적이 실패한 것이 원인이었다. 모든 OK target은 현재 존재하는 단일 workspace-relative 구현/시험 파일이며 보조 파일·실행 설명은 justification이다. 기존56개 행(AC25/BR10/상세NFR15/상위NFR6) 유지+직접 FR31행=87행이다. 서버 FR은 N/A 또는 UI부분으로 한정하고 실제 음성/실브라우저/전체 verify를 OK로 바꾸지 않았다.

### 실패→수정→재실행

| 순서 | 실제 명령(frontend workdir) | 실제 결과 |
|---|---|---|
| 수정 전 부모 결함 기준선 | npx vitest run tests/parent/features/done/guardrail-review-regression.test.tsx | 종료1·1파일2FAIL. 원래 F-R01/F-R02 재현 |
| 연결 층 수정 뒤 첫 원래 사례 | npx vitest run tests/parent/features/done/guardrail-review-regression.test.tsx -t '단절 뒤' | 종료0·1PASS/1필터 미선택. 당시 아직 고치지 않은403을 통과로 기록하지 않음 |
| 연결 층 보강 뒤 | npx vitest run tests/parent/features/done/guardrail-review-regression.test.tsx -t '단절 뒤\|online 복구\|복구 뒤' | 종료0·3PASS/1필터 미선택. 코드에 skip을 추가하지 않음 |
| 권한 층 구현 뒤 원래 사례 포함 | npx vitest run tests/parent/features/done/guardrail-review-regression.test.tsx | 종료0·4PASS |
| 권한 반례 보강 및 기존 회귀 첫 실행 | 아래 관련5파일 명령 | 종료1·53PASS/1FAIL. 최초 미조회에 새로운 현재성 false를 조회오류처럼 표시함 |
| 로딩/오류와 사실 신뢰 구분 수정 뒤 | 같은 관련5파일 명령 | 종료0·5파일54PASS, 원래 부모2단언+추가4반례 모두 PASS |
| 최종 기능 측정 | 아래 정확한28파일 기능 명령 | 종료0·28파일318PASS·LINE791/798=99.12% |
| 최종 전체 lint | npm run lint | 종료0 |
| 최종 전체 시험 | npx vitest run tests --coverage | 종료0·84파일1055PASS·skipped0·LINE3543/3594=98.58% |
| 최종 production build | npm run build | 종료0·TypeScript/production 산출물 성공 |
| 추적 센서 | 아래 실제 엔진 명령 | 종료0·pass=true·invalid_targets=[]·findings_count0 |
| 공백 검사 | git diff --check -- frontend 및 해당 U17 code-generation 경로 | 종료0 |

관련5파일 실제 명령:

```sh
npx vitest run tests/parent/features/done/guardrail-review-regression.test.tsx tests/parent/common/guardrail-live-updates.test.tsx tests/parent/features/done/guardrail-done-screen.test.tsx tests/parent/common/api/api-client.test.ts tests/parent/features/live/p1-dialog.test.tsx
```

추적 센서 실제 명령(workspace root):

```sh
aidlc engine sensor-traceability --stage code-generation --output-path aidlc/spaces/default/intents/261020-service-guardrail-replac/construction/u17-guardrail-presentation/code-generation/traceability.json
```

기능 실제 명령(frontend):

```sh
MUJOONG_COVERAGE_SCOPE=guardrail-presentation npx vitest run tests/parent/common/api/guardrail-presentation.test.ts tests/parent/common/guardrail-live-updates.test.tsx tests/parent/features/live/guardrail-participation.test.tsx tests/parent/features/done/guardrail-done-presentation.test.ts tests/parent/features/done/guardrail-done-screen.test.tsx tests/parent/features/done/guardrail-review-regression.test.tsx tests/device/ws/guardrail-device-session.test.ts tests/device/audio/guardrail-player.test.ts tests/parent/features/live/guardrail-accessibility.test.tsx tests/parent/common/api/api-types.test.ts tests/parent/common/activity-logic.test.ts tests/parent/features/live/live-12.test.tsx tests/parent/features/live/p1-dialog.test.tsx tests/parent/features/done/done-17.test.tsx tests/parent/features/done/done-17-result.test.tsx tests/parent/features/activity-session-screens.test.tsx tests/parent/features/practice/practice-screens.test.tsx tests/device/ws/device-session.test.ts tests/device/ws/device-session-resume.test.ts tests/device/audio/player.test.ts tests/device/audio/player-progress.test.ts tests/device/audio/barge-in.test.ts tests/device/runtime/device-runtime-voice-session.test.ts tests/device/features/status/status-view.test.tsx tests/parent/common/api/api-client.test.ts tests/parent/common/api/account-api.test.ts tests/parent/features/login/dev-login.test.tsx tests/parent/features/login/login-01.test.tsx --coverage
```

### 하한·대조·변경 소유

- 전체 LINE3543/3594=98.58%, statements4085/4202=97.21%, branches3126/3337=93.67%, functions1064/1099=96.81%.
- 기능 LINE791/798=99.12%, statements831/844=98.45%, branches686/714=96.07%, functions177/182=97.25%.
- 기존 LINE80%·분모/단언/import/계약/runner/skip을 낮추지 않았다. 당시 -t의 미선택 시험은 단계별 수정 확인용 필터 결과일 뿐 최종에는 모든 시험을 실행했다.
- 기존 운영16파일 범위와 기존31개 manifest claim을 모두 유지했다. 부모 회귀1파일을 더해32claim이며 실제 최종 frontend 변경 목록32개와 일치한다. 추가 운영 파일0·최종 삭제0, scope 시험 목록28개 모두 실재. unit-test-instructions.md에도 같은 파일을 추가했다.
- 이번 수정 운영3개: frontend/src/parent/common/api/liveUpdates.ts, frontend/src/parent/common/hooks/useLivePolling.ts, frontend/src/parent/features/done/useDoneScreen.ts. 부모 회귀2개 단언을 그대로 유지하고 보강4개를 추가해 해당 파일6PASS다. 최초998+초기U17신규51+이번6=1055이며 기존 시험은 삭제하지 않았다.
- baseline-results.md는 최초 이력과 이번2FAIL 기준선을 별도로 보존한다. 위 첫31파일 지문/첫 검증은 역사적 기록이다. 마지막 frontend/coverage는 이번 전체 결과다. 기능 결과는 그 직전 별도 범위 실행의 실제 콘솔로 기록했다.

### 증거 한계와 인계

이번 실행은 jsdom·실제 QueryClient/ApiClient/화면+가짜 fetch 및 기존 오디오/WS 대역이다. 브라우저/Chromium/GUI/Next 서버·키링 조작은0회이며 실제320px/200%/브라우저 키보드/실기기 조작·회전은 계속 미검증이다(NFR5.2 Deferred). 실제 운영 말소리도 준비되지 않았고 합성 대역을 운영 승인 음성이라고 하지 않는다.

전체 verify.sh는 Build and Test에서 hook에 차단된 사실을 유지한다. 이 재시도에서는 해당 명령·backend 검사·global checkpoint를 실행하지 않았다. 전체 대역 서버 통합도 미해결이므로 NFR3/NFR3.3 Deferred를 유지한다. U16의 B-R01 늦은 입력과 RG1 재인사 방지는 별도 승인 backend 수정/회귀이며 현재 UI 성공으로 해결됐다고 주장하지 않는다. Build and Test 기록은 부모가 소유하며 여기서 수정하지 않았다. 유료 API/실아동/배포/운영 migration/manual 스택 사용·변경0회다.

### 현재32파일 동결 지문

앱 동결2026-10-21T05:08:10Z. 이후 앱/시험/scope 변경 없이 Step5 문서만 완성했다. 재현용 바이트 지문 sha256:f93b3979c2811d3817bc2492be6d90ec250961232d4a9066bde61f0a44501082. 정렬한32행의 path+TAB+sha256(file bytes)+LF를 해시한 값이며 엔진 검토 source binding과 구분한다. 부모가 fresh independent review/완료를 담당한다.

| 경로 | SHA-256 |
|---|---|
| frontend/coverage-scope/guardrail-presentation.json | ce5ad78978d2b8c884cfc62da338a37b14631b2a0c605cd94387bb04fd2869c4 |
| frontend/coverage-scope/guardrail-presentation.tests | b4c05bab0ddc7e081c1995fd0916f9952ee56e7d8b04ff33686c91e8ff9f6d00 |
| frontend/src/device/audio/player.ts | 1cfb72145e8dda49cf27de894c65cca5f4a3c581ce0fad94448873b6f19d7ea2 |
| frontend/src/device/ws/deviceSession.ts | 0f25b964d37a720488678779733784a745c25aa3439396efa105ba70778809f7 |
| frontend/src/parent/common/activity/livePresentation.ts | c25ad441f18ea9bd6a3c6e39983ba4d4a9f1e22d3c49e5035e1099ad2a2fe00b |
| frontend/src/parent/common/api/apiClient.ts | 868eeb6c3ee8b13175aa78ccf5793f2e9f83aabef50dbfefb77b3496b7f41c69 |
| frontend/src/parent/common/api/guardrailPresentation.ts | 740657c75e849fc1d432fc00321aaa4208fca0eff0c0c61ad4d425886b700b45 |
| frontend/src/parent/common/api/live.ts | c350bf0bfd5cf128eff159dec96f7d8221f2f1eb2cf8d35a16b896cbbf6c4f39 |
| frontend/src/parent/common/api/liveUpdates.ts | 3ecaf3eaeab66abf42b0ed4062af5ce78f70e7d137dc958d7afb3086e7ec5da6 |
| frontend/src/parent/common/api/types.ts | ae380845adcf41cfc588d00adcf7b2a09210aa9e161ec5b97ffb30bc89676d14 |
| frontend/src/parent/common/hooks/useLivePolling.ts | 78d1aab352b9cb1fd97f0207bf967228c170841de97d0064917fbdd64f680cad |
| frontend/src/parent/features/done/DoneScreen.tsx | 15d99b0dc4def427bfc411cd4c333c53e5e822268c7476bb30fda1f5eb6d9b71 |
| frontend/src/parent/features/done/presentation.ts | 47f0c663ef2cebb6d8b47812775840ddcd0b4b616453fab9676af3a34dc8e9d2 |
| frontend/src/parent/features/done/useDoneScreen.ts | de87e165fe9b308b38df11d880df67a28cb62ddb3f986ed2462369342385c922 |
| frontend/src/parent/features/live/LiveScreen.tsx | ce151b1b256baa88cfd1d23e6362284ae21552fb4ed46cc1005974229f8b2cd1 |
| frontend/src/parent/features/live/NoticeDialog.tsx | 270f214ed657fd5d75df9186ba77efec5b59ccc1788a6b770656ce94c5abbfbc |
| frontend/src/parent/features/live/useLiveScreen.ts | 81680ebadb0050e6b02567d0077bc04c587474ad95dfec82d51923a1bb1cd332 |
| frontend/src/shared/copy/activity.ts | 354fdd673e15f42e188b62e220a46410b18f987c9b7e80850bb8559a30cbf49d |
| frontend/tests/device/audio/guardrail-player.test.ts | f02064e923048fc1b9c069d6d3b7b823fe082bcbda4f3a7d26cb982a3c8a6a15 |
| frontend/tests/device/ws/device-session-resume.test.ts | e29609b177e0dc6f7d5c7c271a3ea501d169c0d9969d3209d4e22f2f5e84dc0c |
| frontend/tests/device/ws/device-session.test.ts | 7bdb0317420a699eaa8640e2b42c086d4e3e7d9630880d6ef1735f4081bfff0d |
| frontend/tests/device/ws/guardrail-device-session.test.ts | c7f355a275df2e1a87a26657bf21aea9f7fe9d6028c7eb17f0e8bb13d14b5d4f |
| frontend/tests/parent/common/activity-logic.test.ts | 85a5713cfdc73f3c623698de3e69e7e3f37b68e43c8a0d6ef7b77d49b300d8ea |
| frontend/tests/parent/common/api/guardrail-presentation.test.ts | ac913cf14fb2f8fc9c21cc5bc490f82cc6380d00d50ae21e07861449ff833683 |
| frontend/tests/parent/common/guardrail-live-updates.test.tsx | d4288e4012875bd3e0fa70ef21d03749a3f7da58ab7e3685dabd292b7dda7069 |
| frontend/tests/parent/features/activity-session-screens.test.tsx | 51f93ebeb31826218ce3bf5e0c03cf230aa0fe45d4600285a3099d875bcd4ced |
| frontend/tests/parent/features/done/done-17.test.tsx | e371a5356f079e7bcd5e8d105f25ac592446740af807a84083efcb5d63d5facd |
| frontend/tests/parent/features/done/guardrail-done-presentation.test.ts | 03f487672d5be46309c8928e9eb495abaa77fb1b1e1cdc35d6948bfdc82c324d |
| frontend/tests/parent/features/done/guardrail-done-screen.test.tsx | 257d1f7feb8e8bf39cc5f071208bcab3a5704a96353f79f8471c57c58ca65642 |
| frontend/tests/parent/features/done/guardrail-review-regression.test.tsx | 1d9ad49019cb8623c957d52fac359960cbf66a3e836938805a8e13407940287e |
| frontend/tests/parent/features/live/guardrail-accessibility.test.tsx | 679484e99a6f7190feb81d52c18b39257b70eecb4c083ae33b79d889ada72185 |
| frontend/tests/parent/features/live/guardrail-participation.test.tsx | fd6949b38e6e69594b7f7608d337fd6cf6d2b6456a43c50378aefadb6f367331 |

## Loop-back 2 — F-R04/R-04 현재 검증과 동결

Testing Contract sha256:231770ff8fc8e342a5163711626da88820010bbc0b413c96ec9fc94bb2241ce4의 test-after/Standard를 유지했다. 원래14단계는 역사로 보존하고 승인된 새 시도의 Repair1–5만 수행했다. 아래 명령은 frontend 작업 디렉터리에서 실행했다.

| 실제 실행 | 명령 | 결과 |
|---|---|---|
| F-R04 기존 실패 재현 | npx vitest run tests/parent/common/guardrail-live-updates.test.tsx | 종료1·11개 중8PASS/3FAIL, identity/sequence/leave의 새 성공 캐시가 undefined |
| tiny fix와 취소 반례 직후 | npx vitest run tests/parent/common/guardrail-live-updates.test.tsx tests/parent/features/done/guardrail-review-regression.test.tsx tests/parent/common/api/api-client.test.ts | 종료0·3파일34PASS(공통12/리뷰6/API16) |
| 기능 coverage | 위 Loop-back 1의 정확한28파일 기능 명령과 동일 | 종료0·28파일322PASS |
| 전체 lint | npm run lint | 종료0 |
| 전체 coverage | npx vitest run tests --coverage | 종료0·84파일1059PASS·skipped0 |
| production build | npm run build | 종료0·TypeScript와 정적 생성 포함 |

- 기능 LINE794/801=99.12%, statements833/846=98.46%, branches692/720=96.11%, functions177/182=97.25%.
- 전체 LINE3546/3597=98.58%, statements4087/4204=97.21%, branches3132/3343=93.68%, functions1064/1099=96.81%.
- 기존 부모3단언을 보존·해결하고 AbortSignal 단독 취소1개를 더했다. 최초1055에서 새 회귀4개가 더해1059이며 삭제/skip/하한·분모 축소0이다. 기존 current403의 session-only cache/items/facts 제거, 다른 세션 보존, 늦은 성공 복구 금지, CSRF 갱신/동일 requestId 재시도와401의 기존 의미가 관련34시험에서 PASS다.
- 운영 수정은 liveUpdates.ts 한 파일의403 부작용 앞 현재성 검사뿐이다. 403 이외 인증 정책은 그대로이며 apiClient를 수정하지 않았다. 폴링 주기·URL·공개 응답·no-store·runner/config/package/lockfile·backend/contracts/infra/U16 수정0이다.
- source-manifest32경로와 실제 frontend 변경 목록 일치, 운영16파일이 기능 scope에 모두 포함, 시험28경로 전부 실재, traceability87행의 OK target 누락0이다. 경로/소유 표면이 같아 manifest와 scope 내용은 보존했다. 추적은 BR17.1/NFR1.1/NFR2.1의 설명에 현재403/늦은4034경계를 추가했다.

### 미검증 범위와 책임

이번 결과는 jsdom/실제 QueryClient·ApiClient/가짜 fetch와 기존 오디오·WS 대역의 결과다. 브라우저/Chromium/GUI/Next 서버·키링 실행/조작0. NFR5.2의 가상 DOM 키보드·초점 단언 PASS와 실제320px/200% 레이아웃·브라우저 키보드·실기기 조작/회전 미완료를 분리하며 Deferred를 유지한다. 실제 운영 말소리 미준비, 전체 verify.sh hook 차단과 전체 대역 서버 통합 미완료도 유지한다(NFR3/NFR3.3 Deferred). backend/global checkpoint/verify.sh 재실행0이며 부모가 소유한 Build and Test 기록/리뷰/라우팅/lifecycle은 수정하지 않았다. 이전 U16 미해결 기록은 당시 이력이며 후속 backend 수정 상태는 부모 기록에서 확인한다.

### 현재32파일 동결 지문

앱 동결2026-10-21T07:02:08Z. 재현용 바이트 지문 sha256:1fafac26b9f0de78e237da0d8edbe2685599db9c558b19756b3ea0e95ec1d2bf. 이전32파일 표에서 아래2개만 변경됐고 나머지30개 바이트 해시는 동일하다. 지문 알고리즘은 정렬된32행의 path+TAB+sha256(file bytes)+LF SHA-256이며 엔진 review source binding과 구분한다.

| 경로 | 현재 SHA-256 |
|---|---|
| frontend/src/parent/common/api/liveUpdates.ts | f7e2cb827e2830498fc0ce6ddb2a293e7c43a16e4944ef33a6329134cbdab4e2 |
| frontend/tests/parent/common/guardrail-live-updates.test.tsx | d68c47e0f5d4922e279f26a5112975008748baf8e1ee555a66b5e7acc2b95b13 |

