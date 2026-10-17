# U11 판정 턴 기반 — 단위 시험 안내 (범위 확장 개정판)

> 시험 전략 Standard(컴포넌트당 5 ~ 8개 + 핵심 경계 통합 시험), 방법 test-after(층 하나 구현 → 그 층 시험 작성 · 실행 → 다음 층). 백엔드 명령은 저장소의 `backend/`, tools 명령은 `tools/`에서 돌린다. 두 묶음: PR1(선 형식 · 키 통일, Step 4 ~ 14), PR2(판정 턴, Step 15 ~ 29).

## 1. 준비

- JDK 21, Docker(Testcontainers PostgreSQL), Gradle 래퍼(`./gradlew`), Node(`tools/` Vitest).
- 시험 · CI는 가짜 공급자(GPT-Live 대역: `test-support`의 `FakeProviderWsServer`, 인메모리 `FakeProviderPort`, `tools/fake-provider`) · 가짜 판정기 · 루프백 Luna 대역만 쓴다. `OPENAI_API_KEY`가 개발 PC env에 있어도 기본 `test` 작업은 시험 JVM에서 지운다(U9 관례). 옛 이름 `PROVIDER_API_KEY`는 쓰지 않는다.
- 프레임워크: JUnit 5 · AssertJ · Mockito, 실행기 시험 장치 `testing/SessionHarness`(수동 시계 `MutableClock` · `ManualTimerDriver` · `FakeProviderPort` · `ManualLauncher` · `FakeDeviceLink` · `MemoryStore`), ArchUnit. tools는 Vitest.

## 2. 이 단위만 돌리는 명령

| 목적 | 명령 |
|---|---|
| U11 시험 전부(단위 · 실행기 · 통합) | `./gradlew :realtime-activity:test -PincludeTags=u11 -Pmujoong.test.db=docker` |
| U11 범위 줄 커버리지(Step 27) | `./gradlew :realtime-activity:test :realtime-activity:jacocoTestReport -PincludeTags=u11 -PcoverageScope=u11 -Pmujoong.test.db=docker` (범위 파일 `backend/coverage-scope/u11.txt`, 하한 80%) |
| 선 형식 단언을 바꾼 앞 유닛 시험(Step 8) | `./gradlew :realtime-activity:test -Pmujoong.test.db=docker` (전체 realtime, 앞 유닛 태그 그대로) |
| 바꾼 판 단언 3곳(Step 15) | `./gradlew :realtime-activity:test --tests 'kr.mujoong.realtime.db.RealtimeSchemaIT' --tests 'kr.mujoong.realtime.practice.PracticeMigrationRegistrationTest' --tests 'kr.mujoong.realtime.practice.PracticeMigrationIT' -Pmujoong.test.db=docker` |
| 가짜 공급자 `tools/fake-provider` · `skeleton-e2e` 단위(Step 5 · 8) | `cd tools && npx vitest run` |
| 키 이름 통일 · 비밀 이름 검사(Step 4) | `infra/bin/infra-validate`, `bash infra/ci/test-infra-validate.sh`, `bash infra/ci/test-host-tools.sh`(GNU date를 PATH 앞에: project.md 교정), 이미지 점검 `infra/ci/image-checks.sh` |
| 계약 대조(Step 9 · 17) | `./gradlew :contract-model:test :realtime-activity:test --tests '*ConfigKeyContractTest' --tests '*TimeLiteralScanTest' --tests '*ContractFilesTest'` |
| GPT-Live 격리 실호출(사람만, Step 11) | `./gradlew :realtime-activity:gptLiveTest` — 키가 없으면 SKIPPED_NO_KEY이고 통과가 아니다. 키를 넣기 전에 `infra/RUNBOOK.md` 단계 4 확인 기록(Luna 판정 · GPT-Live 음성 중계) |

- 모든 U11 시험 클래스에는 `@Tag("u11")`을 붙이고, DB를 쓰는 통합 시험에는 `@Tag("db")`도 붙인다. 단위는 `*Test`, 통합은 `*IT`. tools 시험은 `tools/tests/u11/`.
- 앞 유닛 시험을 고칠 때는 그 파일의 태그를 그대로 둔다.
- 모듈 전체 확인(Step 13 · 29)은 `./gradlew :realtime-activity:check verifyBootJarHasNoTestClasses -Pmujoong.test.db=docker`(필요하면 `./gradlew clean check -Pmujoong.test.db=docker`)이고, 이것은 단위 범위 명령이 아니라 묶음 마무리 확인이다.

## 3. 컴포넌트별 시험 (최소 개수)

### PR1 — 선 형식 · 키 통일

| 컴포넌트 | 시험 클래스(예) | 개수 | 핵심 경우 |
|---|---|---|---|
| 키 · 기동 점검 · Luna 스위치 | `OpenAiKeyStartupChecksTest` · `LunaActivationTest` · `ProviderAuthHeaderTest` | 6 ~ 8 | demo 키 없음 거부, 다른 프로필 키 없음 기동, 키 없으면 `Authorization` 헤더 없음, 키 × 스위치 네 경우 `lunaState`와 빈 등록, core Luna 키만으로 켜짐 |
| 비밀 이름 검사 | `infra/ci` 시험 스크립트 | 기존 + 새 이름 | 새 이름 통과, 옛 이름 예외 밖 0건 |
| 요청 팩토리 | `ProviderRequestFactoryWireTest` | 5 ~ 8 | 중첩 세션 시작 키 집합, 송신 type 다섯뿐, `childContext` · `delegation` 없음, 음성 append 960B 검사, 식별자 · 키 없음 |
| 해석기 | `ProviderReaderWireTest` | 5 ~ 8 | `delta` 바이트 디코드, `error` → `Error(code)`, `usage.updated`, 모르는 type 개수, 디코드 실패 조각 버림 |
| 출력 세대 협력 객체 | `OutputGenerationTest` | 8 이상 | 설계 2.1 표 전 행, 끊김 기준 ①②, 자동 응답 시작 대기, 허가 뒤 조각 → 허가 무효, 출력 보류 중 버림, 보류 해제 세대 올림, 세대 또 올림, 구간 완전 · 불완전 |
| 구간 · 게이트 | `SegmentBufferGenerationTest` · `OutputGateGenerationTest` | 각 5 | 끊김 봉인 = 완전, 세대 변경 = 불완전, 마지막 조각 시각, 응답 끝 사건 우선(사건이 관측된 뒤) |
| 실행기 연결(세대 · 직렬 송신 · 취소 대체) | `GenerationSendExecutorTest` | 10 이상 | 응답 id 없는 조각으로 자유대화 한 턴이 기기에 소리로 나감, 끼어들기 · 종료 의도 · 연습 폐기 뒤 버린 세대 0건, 일곱 경로 같은 큐, append → create, 끊김 전 송신 0건, `SendAborted` → 재허가, 상한 만기 뒤 다음 송신 통과(큐 해제), `InstructionSent`가 `create` 직후 첫 조각보다 먼저 처리됨, `response.cancel` 송신 0건 |
| 가짜 공급자(통합) | `FakeProviderWireIT` | 4 ~ 6 | 새 형식 송수신, 목록 밖 type이면 `error{invalid_value}`, 응답 경계 = 조각 사이 무음 |
| 가짜 공급자(tools) | `tools/tests/u11/fake-provider-wire.test.mjs` | 4 ~ 6 | 같은 경우 |
| 앞 유닛 단언 갱신 | 설계 10절 목록 | 기존 | 같은 행동(순서 · 횟수 · 버림) 단언이 남아 있음, 바꾼 단언 목록을 `code-summary.md`에 |
| 격리 실호출 규칙 | `GptLiveIsolationTest` | 3 | `check`에 `gptLiveTest` 의존 없음, 키 없음 SKIPPED, 보고서 형식(전사 · 음성 · 키 없음) |
| 실호출 반영(Step 12) | 관측 사건별 시험 | 사건당 2 | 관측된 전사 · 말 끝 · 응답 경계 해석, 변환 경로(해당 시) 길이 · 표본 수, `auto-response-start` 값 시간 키 대조 |

### PR2 — 판정 턴

| 컴포넌트 | 시험 클래스(예) | 개수 | 핵심 경우 |
|---|---|---|---|
| V5 · 저장소 · 기록기 · 삭제 | `JudgmentLiveSchemaIT` · `JudgmentLiveRepositoryIT` · `JudgmentLiveRecorderTest` · `JudgmentDeletionParticipantIT` | 5 ~ 8 + 통합 | 표 · 제약, 쓰기 · 덮어쓰기, 외래 키 위반 폐기, 일시 실패 재시도, 실행기 비대기, 보호자 삭제 뒤 0건 |
| 설정 · 4초 시한 | `JudgmentSettingsTest` | 5 | 기본값 0.6 · 0.15, 0 ~ 1 밖 기동 거부, 바인딩, `live-budget` 4s 시간 키 세 곳 대조 |
| `JudgeClient` · 결과 검증 | `LunaJudgeClientTest` · `UnavailableJudgeClientTest` · `JudgeOutcomeValidatorTest` | 8 이상 | (1순위, 2순위) 벡터 (0.59, 0.10) · (0.70, 0.60) · (0.70, 0.40) · (0.60, 0.45) · (0.60, 0.46), 선택지 밖 · 중복 · 확률 이상, 4.000초 만기 = 보류 · 3.999초 = 확정, 시간 초과 · 공급자 오류 · 스키마 오류 호출 1회, Luna 없음, 판독 불가 입력 공급자 0회, 요청 금지 필드 없음 |
| 지시 템플릿 | `JudgmentInstructionsTest` | 5 | 세 지시문에 판의 모든 선택지 코드 · 숫자 확률 없음, 한도 · 제어 문자 |
| 판정 턴 협력 객체 | `JudgmentTurnTest` | 10 이상 | 상태 기계 전 행, 시계 셋 만기 · 해제(INSTRUCTING 진입 때 READY · LIVE 해제), STOPPED · SUPERSEDED · FAULT_CANCELLED · SESSION_EXITED, INSTRUCTING 중 확정 전사, `SendAborted` 두 갈래, 낡은 허가 `InstructionSent`, 늦은 turnId · 상태 다른 결과, 판정 단계 READY 먼저 만기(말 끝 6초 뒤 열린 턴) |
| 실행기 연결(출력 · 지시 · 보기) | `JudgmentTurnExecutorTest` | 8 이상 | 기기 출력 0 · 대화 기록 0 · 전사 모임, THINKING 유지 · 복원, append → create 순서, 지시 전 자동 응답 조각 버림, 지시 응답 시한, 상태 전이마다 SESSION_EXITED |
| 실행기 연결(J1 · J6 · 휴식) | `JoinJudgmentExecutorTest` · `StopJudgmentExecutorTest` | 각 6 ~ 8 | J1 세 줄 · 재안내 1회 · 창 만기 미룸, J6 다섯 줄 · 사건 표, R-13 발화 전달, R-14 시계 재개, 판정 중 STOP, `LUNA_J6` |
| 확장점 | `JudgmentExtensionPortTest` | 6 이상 | 받음 · 거절 4종 · 결과 · 보류 · 모은 전사 · 장애 사건, 기존 확장 무수정 |
| 장애 · 이탈 | `JudgmentFaultExecutorTest` | 5 이상 | 공급자 끊김 · `error` 사건 · 일시 정지 · 보호자 종료 · 동의 철회 · 늦은 결과 |
| Luna 없음 회귀 | `JudgmentNoProviderRegressionTest` | 3 이상 | J1 UNCLEAR · J6 CONFIRM의 보기 · 세션 사건 순서가 지금과 같음(키 없음 · 스위치 꺼짐 둘 다) |

## 4. 대역 · 시험 자료

- GPT-Live: 위 가짜 공급자 세 곳. 새 형식(응답 id · `output.done` 없음, `delta` 음성, 응답 경계는 조각 사이 무음)이다. 출력 조각 순서(자동 응답 첫 조각 · 판정 결과 · `InstructionSent` 표시)를 시험에서 직접 넣어 경쟁을 재현한다(`ManualLauncher`는 등록 순서대로 돌기 때문). 실호출에서 관측되지 않은 사건은 가짜 공급자도 내보내지 않는다(R-M11).
- Luna: 루프백 Luna 대역 또는 `JudgeClient` 가짜(정해진 `JudgeOutcome`을 돌려줌). 실제 공급자 금지(R-F1).
- 전사 · 아이 이름은 합성 값만 쓴다. 실제 아동 자료 · 실제 도메인 · 키 값을 시험에 쓰지 않는다(R-F3).
- 시각은 `MutableClock`, 시간 값은 설정 키에서만(시험 프로필로 줄여 주입).
- DB 통합은 Testcontainers PostgreSQL(`-Pmujoong.test.db=docker`). 사용자 `mujoong-manual` 스택은 쓰지 않는다(R-F12).
- `test-infra-validate.sh` · `test-host-tools.sh`는 GNU date(`/usr/bin/gnudate`)를 `date`로 PATH 앞에 두고 돌린다(이 PC의 uutils date는 `%3N`을 무시, project.md 교정).

## 5. 커버리지 목표

- U11 범위(`coverage-scope/u11.txt`) 줄 80% 이상. 모듈 `realtime-activity` 전체 줄 80% 이상 유지.
- 모자라면 시험을 더한다. 하한 · 기존 단언 · 대조 시험 · ArchUnit을 낮추거나 건너뛰지 않는다(R-F2).

## 6. 결과 보고

- 증거는 테스트 대역 · 합성 대화 · 실제 API · 실제 기기로 나눠 적는다(R-M11). 이 단위의 자동 시험은 모두 테스트 대역이고, Step 10 · 11의 `gptLiveTest`(TTS 실호출)만 `[실호출-합성]`이다. 선 형식 · 표본율 · 전사 사건 · 판정 지연의 "성립"은 `[실호출-합성]` 표로만 말하고, 가짜 공급자 통과를 실제 공급자 성립으로 적지 않는다.
