# Code Quality Assessment

> 기준: 커밋 570a625. 개수는 어노테이션 · `it(` · 정규식 기반 근사다. 파일별 구역 · 줄 수 · 문자열 참조 표는 `code-structure.md`가 정본이고 여기서는 판단과 위험만 적는다.

## 시험 규모와 커버리지

| 대상 | 시험 | 비고 |
|---|---|---|
| contract-model | @Test 155 | 시험 18파일 |
| core-api | 1,530 | 247파일, IT 90 |
| realtime-activity | 1,511 | 244파일, IT 52 |
| test-support | 28 | |
| 백엔드 합계 | 약 3,224 | |
| frontend | 755 | 74파일(`tests/*` 87파일 중 `*.test.*`) |
| tools | 126 | 12파일 |

- 커버리지: 백엔드 모듈 전체 JaCoCo 줄 80%(`contract-model` · `core-api` · `realtime-activity`), 프런트 Vitest v8 줄 80%. `-PcoverageScope=<이름>` / `MUJOONG_COVERAGE_SCOPE=<이름>`이면 범위 파일(`backend/coverage-scope/*.txt` 18개 줄 합계 898, `frontend/coverage-scope/*.json` 8개)만 잰다. 목록이 비었거나 없는 파일이 있으면 실패한다(J137). **CI가 막는 범위는 u5b(백엔드 `u5b` · `u5b-core`, 프런트 `u5b`)와 u7뿐이다.**
- 태그(`@Tag` 사용 수): u1 142, db 134, u5b 33, u11 28, u4b 24, u3 23, u6 21, u13 20, u10 20, u12 18, u8 17, u7 17, u9 16, u2 15, u5a 9, u14 9, u15 6, u4a 5. 실호출 제외 태그 `luna-live` · `luna-reach` · `jev-live` · `gpt-live`는 빌드가 기본 제외를 단언한다.

## 린트 · 서식

- 프런트: ESLint flat config(영역 경계, `jsx-no-literals`, `getUserMedia` · `serviceWorker` 제한). CI가 `eslint .` 실패로 막는다.
- 백엔드: `-Xlint`만(`-Werror` 없음). **Java 포매터 · 줄 길이 검사 없음.** Prettier는 설정만 있고 실행되지 않으며 `.prettierignore`가 `backend/`를 뺀다. `CoreProperties.java:153`의 `PracticeTiming(Duration a,Duration b)`처럼 압축된 서식이 있다. 팀 규칙은 이번에 손대는 파일만 읽기 좋게 정리하고 `printWidth 100`을 강제 기준으로 쓰지 않는 것이다.
- 셸: `infra-validate`의 shellcheck.

## CI/CD

`ci.yml` 작업 14개: `changes`, `backend`, `u5b-scope`, `backend-deps`, `frontend`, `frontend-deps`, `tools`, `secrets`, `infra-validate`, `images`, `skeleton`(main 푸시만), `ci-gate`(필수 검사), `deploy`. 경로 필터가 `^backend/`, `^frontend/`, `contracts/`, `.github/` 등을 본다. `deploy.yml`은 별도다. `docs/` · `aidlc/` · 루트 Markdown만 바뀐 PR은 전 작업이 건너뛰어진 채 `ci-gate`가 통과한다.

## 문서

`README.md`, `AGENTS.md`, `docs/units/<유닛>/{brief,design,report}.md`, `docs/change-history.md`, `docs/live-run.md`. Java 클래스 머리 Javadoc이 풍부하다. 코드 주석이 결정 · 요구 ID를 인용하는 것이 팀 규칙이다.

## Technical Debt (리팩토링 관련)

| ID | 신호 | 근거 |
|---|---|---|
| Q1 | `SessionExecutor.java` 책임 과밀 | 4,361줄(둘째 큰 운영 파일 `PracticeConversation` 862줄의 5배), 메서드 선언 약 276, 결정 ID 약 315회. 구역별 분포는 `code-structure.md` |
| Q2 | 다른 큰 파일 | `GuardrailCoordinator` 759, `FileAudioRecorder` 619, `CoreProperties` 589, `RealtimeProperties` 570. 시험 `GptLiveProbe` 2,072, `PracticeConversationTest` 966 |
| Q3 | 이름 혼동 | `realtime.practice`(레거시 29파일) vs `realtime.practiceflow`(새 흐름 21파일) |
| Q4 | 프런트 번호 중심 이름과 누적 주석 | 화면 14개 번호 접미, `u5bCopy.ts`, 결정 · 요구 ID 약 407회(`src`) |
| Q5 | 화면에 요청 · 오류 · 이동 혼재 | `Live12` · `Home04` 등(`code-structure.md` 표). 오류 → 문구 한 길과 `PARENT_ROUTES`는 이미 있음 |
| Q6 | 분리 기준 불일치 | 연습 요청 `parent/practice/{api,startApi}.ts`, 문구 3곳으로 갈라짐 |
| Q7 | 문서 낡음(이번 의도와 무관) | `team.md`는 다음 Flyway 번호를 core V8 · realtime V5로 적지만 실제는 core V10, realtime V7까지 있다(다음 V11 · V8) |

## 안전망

- ArchUnit 12파일 3,163줄: core `CoreArchTest` 453, `TimeLiteralScanTest` 301, `SingleInstanceMarkerTest` 375, `LunaArchTest`, `U2SingleSourceTest` / realtime `RealtimeArchTest` 525, `SingleInstanceMarkerTest` 462, `TimeLiteralScanTest` 291, `PracticeFlowArchTest` 124, `LunaArchTest`, `U8LiveContentArchTest` / contract-model `ContractModelArchTest` 305.
- 계약 대조: `ContractFilesTest`, `ErrorCodeTest`, `ConfigKeyContractTest`, `TimeLiteralScanTest`, `OpenApiContracts`, `frontend/tests/u1/shared/timing.test.ts`, `SeedDictionaryFileTest`.
- 프런트 경계: ESLint + `tests/u1/lint/eslint-boundary.test.ts`. 이름 바꾸는 이동의 import 경로(`@/…`와 상대 경로 223건 이상)는 컴파일 · `next build` · vitest가 잡는다.
- 끝-끝: `scripts/verify.sh` — A1 backend(`clean check verifyBootJarHasNoTestClasses`) → A2 frontend(`eslint .` · `vitest run tests --coverage` · `next build`) → A3 tools → A4 비밀 스캔 → F 지문 → B 기동 → C 기기 시뮬레이터 한 턴(C1~C12) → P1 교차 스키마 → P2 로그 위생 → P3 비밀 값 → T 작업 트리 동일. CI `skeleton` 작업은 main 푸시 때만 돈다.
- 위 시험의 낮춤 · 끄기 · 건너뛰기 금지(R-F2). 분리하려면 시험 쪽(`SingleInstanceMarkerTest` 표, `TimeLiteralScanTest` 허용 목록, `SessionHarness` 및 `SessionExecutor`를 부르는 약 30개 시험)을 같은 PR에서 맞춘다.

## 구멍과 미확인 사항

- `SessionExecutor` 특성화 시험은 `SessionExecutorTest`, `SessionExecutorU3Test`, `SessionExecutorReview{,2,3}U3Test`, `SessionExecutorFaultTest` 등으로 있으나, 구역 사이 호출 순서(메시지 수신 → 참여 확인 → 판정 → 확장점 → 게이트 → 끝내기)를 고정하는 시험이 따로 있는지는 읽지 않았다.
- `Live12` 시험의 정확한 위치(`tests/u3` 아님, `tests/u1/parent` 쪽일 수 있음)는 확인하지 못했다.
- 브랜치 보호의 필수 검사가 `ci-gate`뿐인지(= `u5b-scope` 등 CI 작업 이름 변경이 자유로운지)는 확인하지 못했다.
- 스캔 한계: 엔드포인트 수, core-api 내부, `contracts` 본문, `docs` 본문, 인프라 스크립트 내부는 깊게 읽지 않았다. 이동 호출 · 결정 ID 개수는 근사값이다.

## 집중 조사 보강 — 전체 경로 검증의 공백

### 이번 정적 조사로 확인한 사실
- GptLiveProbe는 GPT_LIVE_INPUT_PCM(WAV 또는 PCM)을 받아 운영 송수신 부품으로 공급자에 직접 연결한다. 기본 파일은 톤/무음 자리표시다. 기존 성공을 앱 전체 성공으로 해석할 수 없다.
- DeviceSimulator.sendInputFrames는 합성 톤이며 실제 한국어 전사를 입력하지 않는다. 첫 출력 프레임과 타이머로 STARTED/COMPLETED를 만들므로 실제 Web Audio·스피커 확인이 아니다.
- 참여 전 전사 비저장은 코드 정책이다. 빈 보호자 화면만으로 마이크 실패와 참여 판단 실패를 구분할 수 없다.
- OutputGate의 GATE_EMPTY는 전사/오디오 부재만이 아니라 incomplete·정규화 후 빈 전사·홀수 PCM 길이를 포괄한다. 사유 코드만으로 원인을 확정할 수 없다.
- 부모 화면은 기본 2초 폴링이며 숨은 탭에서는 주기 폴링을 멈춘다. SESSION_ITEMS_SERVED는 서버 응답 제공 시각이지 DOM 표시 시각이 아니다.
- 브라우저 플레이어의 STARTED는 Web Audio 스케줄 시각을 기준으로 한다. 물리 스피커에서 사람이 소리를 들었다는 증거와 구분한다.

### 이전 관측과 이번 가설
대화에서 보고된 GATE_EMPTY 44건·oversize 1건, J1 약 4초 timeout, 짧은 긍정 미매칭은 이번 조사에서 로그를 다시 읽어 재계수하지 않은 **이전 관측**이다. 코드에서는 4초 판정 예산과 짧은 긍정 exact 매칭, ACTIVE 입력 delta가 열린 출력 구간을 조기 봉인하는 길을 확인했다. 실제 발화의 전사/조각 교차가 이번 실패를 만들었다는 것은 아직 **가설**이다.

### 다음 단계에서 시험해야 할 내용
합성 대본(응 / 좋아, 이야기하자 / 짧은 질문)을 각각 분리해 안내 완료·참여 수락·응답 송신·부모 DOM·GPT 출력 PLAYBACK_MARK를 확인한다. 무음 음성 송신 계수만으로 말소리 입력 성공을 판정하지 않고, 발화 구간의 에너지·길이·표본율과 전사 기대 일치를 함께 확인한다. 반복 WAV의 타이밍이 AI 발화에 끼어들어 STOPPED를 만드는 영향을 통제한다.

관측 보고는 단계별 PASS/FAIL/TIMEOUT/UNOBSERVED/SKIPPED와 시각·근거를 남기고 미도달 구간을 성공으로 채우지 않는다. 참여 전 원문은 운영 로그에 추가하지 말고 합성 대본과의 일치/불일치·길이·분류 코드로 확인할 방법을 설계한다. 구간 진단은 complete·전사 유무·오디오 길이·movedOn 등 비원문 정보로 분리한다. 새로운 앱 진단 계측이 필요한지는 설계 단계에서 결정한다.

### 이번 실행 여부
이번 단계에서는 앱·단위 시험·브라우저 대화·TTS 생성·실공급자 호출을 실행하지 않았다. 기존 시험 총수·커버리지·CI 목록은 과거 store의 기록을 보존했으며 현재 상태로 재인증하지 않았다.

## 집중 조사 보강 — 261020-service-guardrail-replac

기준: 2026-10-20, 커밋 2e3d7b0. 이전 절은 이전 의도의 기록으로 보존했다. 이번 검증 범위는 timestamp의 개별 파일에 한정한다.

### 새 교체 관련 확인과 공백

- DialogInstructionsTest는 기존 문장을 완전 일치로 고정한다. 교체 시 정책 내용·아이 데이터 이스케이프·금지 필드·wire whitelist·연습 회귀를 함께 검증해야 하며 기존 단언을 단순 삭제하는 방식은 피한다.
- JoinFlow.playJoinPrompt는 승인 안내가 없거나 송신 실패하면 onJoinPromptPlayed를 호출한다. 새 계약의 실제 안내 확인 요구와 차이가 있다. 안내 중 답변도 분류하므로 delivered notice와 참여 상태를 각각 확인해야 한다.
- InstructionTemplates.FIRST와 JOIN_FIRST_QUESTION은 관심사/일상 질문으로 시작하도록 강제하고 REST_ACK_AND_RETURN은 휴식 뒤 재개를 유도한다. 새 정책의 아이 이야기 우선·강요 금지와 조정이 필요하다.
- OutputGate와 GuardrailCoordinator는 완전성·세대·연결·출력 보류·새 입력·연습 슬롯을 확인한다. 다만 새 계약의 정책/프롬프트 해시와 안내 상태 기반 목적별 허용이 전부 구현됐다는 근거는 없다.
- 새 안전/관계/저장 사실 정책은 기존 7개 JEV 범주로 완전히 검출된다고 보장할 수 없다. 변경된 모델 응답 품질은 정적 문자열 시험이나 대역 시험만으로 인증할 수 없다.
- application.yml의 4분 공백 경고·6분 종료·40초 연결 복구·30초 종료 확인은 패키지 수치와 일치한다. 초기 참여 20초 창은 ZIP null(미정)과 다르지만 null을 삭제 명령으로 해석하지 않는다.
- 패키지 MANIFEST의 대상 5파일 크기·SHA256는 모두 일치했다. VERIFICATION.json은 문서 오프라인 검사만 보고하며 API/배포/기기 성공의 증거가 아니다.
- 앱·시험·실공급자·브라우저를 이번에 실행하지 않았다. 기존 시험 총수·커버리지·CI 목록과 과거 GATE_EMPTY 관측은 재인증하지 않았고 이번 결함 측정값으로 사용하지 않는다.

