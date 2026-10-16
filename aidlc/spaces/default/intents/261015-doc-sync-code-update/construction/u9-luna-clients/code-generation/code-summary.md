# U9 Luna 호출부 — 코드 생성 요약

> 계획: `code-generation-plan.md`(18단계, Testing Contract test-after · Standard). 시험 실행 방법: `unit-test-instructions.md`.
> 브랜치: `unit/u9-luna-clients`(`feat/doc-sync-code-update` 586238b = `unit/u8-contracts-catalog` 436bf46과 같은 트리에서 땀). 병합 순서 U8 → U9.
> 커밋은 로컬에만 있고 push하지 않았다. `aidlc/` 작업 기록은 커밋하지 않았다(오케스트레이터 몫).

## 1. 커밋

| 커밋 | 층 · 단계 | 내용 |
|---|---|---|
| `8ba1473` | 층 1 · Step 3 ~ 4 | 로그 계약 묶음: `LUNA_CALL` · `LUNA_STARTUP`, 값 형식 LABEL · NAME_LIST, Component 둘, log-hygiene-check · test-host-tools.sh · RUNBOOK 8.4 · ai-io.md 10.8 |
| `71041d3` | 층 2 · Step 5 ~ 7 | 두 앱 `LunaProperties` · `LunaHosts` · `LunaPurpose`, yml · test 루프백 주소, `ConfigKeyContractTest`, 스키마 실행 자원 복사, `json-schema-validator` 실행 범위, `lunaLiveTest` · `verifyLunaLiveIsolation`, ai-io.md 10.9 |
| `6d313d6` | Step 8 | test-support `LunaResponsesStub`(루프백 Responses 대역 · 요청 검사 모드) |
| `bc145e9` | 층 3 · Step 9 ~ 10 | core `CoreLunaClient` 외 정리 · 스키마 짝 · 전송 · 조건 · 기동 확인, 시험 |
| `b1494f9` | 층 3 · Step 11 ~ 12 | realtime `RealtimeLunaClient` 외(코드 공유 없음), 시험 · realtime ArchUnit |
| `e3c91f3` | 층 4 · Step 13 ~ 14 | core `LunaTextAiProvider` · `LunaResultTextProvider` · `LunaAdapterConfiguration`, 실패 기본 빈 반대 조건, 경계 시험 · core ArchUnit |
| `1124f8c` | Step 15 | `CoreLunaLiveTest` · `RealtimeLunaLiveTest`(`luna-live`) |
| `838994d` | Step 16 | ai-io.md 10.2 · 10.10, deviations.md U9 편차, RUNBOOK R-F15 확인 기록 · 8.5, `U8ContractSchemasTest` 문장 대조 · `U9ContractDocsTest` |
| `75e531f` | Step 17 | `backend/coverage-scope/u9.txt` |

층 5(화면)는 해당 없음(프런트 · tools 변경 없음, 실행하지 않음).

## 2. 만든 것 (파일 목록은 `source-manifest.json`)

- **core `kr.mujoong.core.luna`:** `LunaProperties` · `LunaHosts` · `LunaPurpose` · `LunaFailureReason` · `LunaException` · `LunaRequest` · `LunaResult` · `SanitizedInput` · `LunaInputSanitizer` · `LunaSchemas` · `LunaTransport` · `CoreLunaClient`(동기) · `LunaKeyPresentCondition` · `LunaKeyAbsentCondition` · `LunaStartup` · `LunaConfiguration`, 하위 `adapter`의 `LunaTextAiProvider` · `LunaResultTextProvider` · `LunaAdapterConfiguration`, 실행 자원 `prompts/scenario/generation.v1.txt`.
- **realtime `kr.mujoong.realtime.luna`:** 같은 모양을 따로(`RealtimeLunaClient`는 `CompletionStage<LunaResult>`, `JUDGMENT_LIVE` · NONE · 요청 스키마 검증). `JudgeClient`는 U11 몫이라 만들지 않았다.
- **contract-model:** `EventCode` 19종 · `AttrKey` 8개 키 · LABEL · NAME_LIST 검증 · `Component.CORE_LUNA_CLIENT` · `REALTIME_LUNA_CLIENT`.
- **빌드:** 기본 `test`에만 `luna-live` 고정 제외(작업 입력에도 넣음) · 시험 JVM에 `OPENAI_API_KEY`를 넘기지 않음, `lunaLiveTest`(check · build 미연결, 보고서 `build/luna-live/report.txt`, 실행마다 새로), 루트 `verifyLunaLiveIsolation`(check가 의존, 작업 그래프가 정해질 때 계산).
- **기존 파일 변경:** `TextAiConfiguration` · `ResultTextConfiguration`의 실패 기본 빈에 `LunaKeyAbsentCondition`(`@ConditionalOnMissingBean` 유지), 두 앱 `application.yml` · `application-test.yml`, 두 앱 `ConfigKeyContractTest`(레코드 목록에 `LunaProperties`) · `TimeLiteralScanTest`(허용 목록 단위 표기 세 줄, 근거 동반), `StructuredLogFormatTest`(사건 코드 정확 일치 목록 17 → 19), `U8ContractSchemasTest`(R-F15 새 문장 대조 추가).

## 3. 결정 · 편차

| 구분 | 내용 | 까닭 |
|---|---|---|
| 설계 편차 U9-a · U9-b · U9-c | `contracts/deviations.md` U9 절에 적음(openai-java → JDK HttpClient, R-F15 운영 절차, `lunaLiveTest`) | Q1-A · Q2-B · Q4 추가 요구 |
| Code Generation 편차 1 | 실행 자원에 `ai-*.schema.json`뿐 아니라 `common.schema.json`도 복사한다 | `ai-rejudgment-output`이 `common.schema.json#/$defs/Uuid`를 가리켜 원본 검증 · 요청 사본 모두 그 파일이 필요하다. 요청 사본에는 그 정의를 끼워 넣는다(공급자는 바깥 파일을 읽지 못함). 정본은 여전히 한 곳 |
| Code Generation 편차 2 | `Component` 이름은 `CORE_LUNA_CLIENT` · `REALTIME_LUNA_CLIENT`(값은 `core-api/LunaClient` · `realtime/LunaClient`) | 한 enum에 같은 상수 이름 `LUNA_CLIENT`를 둘 둘 수 없다. 기존 `CORE_MAINTENANCE` · `REALTIME_MAINTENANCE`와 같은 방식 |
| Code Generation 편차 3 | `StructuredLogFormatTest`의 '정확히 17종' 단언을 19종 목록으로 늘렸다(정확 일치 단언은 그대로) | 계획 Step 4의 '기존 EventLog 시험 그대로 통과'는 사건 코드를 더하는 계약 변경과 함께는 성립할 수 없다. SD6 · cicd 1절 5행이 '포매터 검증과 그 시험'을 같은 커밋에서 고치라고 정했다(R-M10). 단언을 약하게 하지 않았다 |
| Code Generation 편차 4 | `coverage-scope/u9.txt`에서 U9가 고친 contract-model 로그 파일 4개를 뺐다 | 파일 대부분이 U1 코드라 u9 태그 시험만으로 재면 77.26%로 잡힌다. 그 파일들은 모듈 전체 줄 80%(CI)로 잰다(contract-model 98.52%). 계획의 범위 명령도 core · realtime만 잰다 |
| Code Generation 편차 5 | 상황 생성 지시 파일이 없어 `prompts/scenario/generation.v1.txt`(판 `scenario-generation.v1`)를 새로 썼다 | ai-io.md 7절 규칙(기회 조건 · 대사 포함, 관계 · 기회 그대로, 이름 · 진단 단정 금지)을 옮김. 실공급자 품질은 `lunaLiveTest` · U10 몫 |
| 열린 점 | `LunaTextAiProvider` 입력에 아이 관심사(`interests`)를 넣지 않는다 | BR2.1 금지 키(기존 PRIVATE_KEYS)라 넣어도 지워진다. ai-io.md 7절은 관심사를 입력으로 적었으므로 U10이 새 스키마 전환 때 다시 정한다 |
| 구현 판단 | 주소 모드: demo · prod가 있으면 STRICT, 없고 dev면 DEV, test면 TEST, 그 밖 STRICT | SD3 '가장 엄격한 규칙이 기본값'. demo+dev 같은 조합도 STRICT |
| 구현 판단 | 2xx인데 JSON이 아닌 본문 · `status`가 completed · incomplete가 아님 → `SERVER_ERROR`(일시) | BR4.4 표에 없는 경우를 공급자 쪽 이상으로 분류. ai-io.md 10.10 표에 적음 |
| 구현 판단 | `LUNA_CALL.responseModel`은 응답 모델이 요청 모델과 다를 때만 쓴다 | SD6 '응답 모델이 다르면 responseModel도' |
| 구현 판단 | json-schema-validator 3.0.6의 문자열 입력 API(`Schema.validate(String, InputFormat.JSON)`)가 있어 그대로 썼다. ArchUnit으로 `..luna..` → `tools.jackson..` 의존 0을 확인 | tech-stack-decisions 가정 |
| 구현 판단 | 시험 대역 빈은 `ApplicationContextRunner` 등록 순서대로 먼저 들어가 `@Primary` 없이 이긴다(키 있음 · 없음 둘 다 확인) | SD11 |

## 4. 시험 결과 (증거 종류: **테스트 대역(루프백 `LunaResponsesStub`) · 합성 자료**)

### 기준선(Step 1, 변경 전 `./gradlew clean check -Pmujoong.test.db=docker`)

| 모듈 | 시험 | 실패 | 건너뜀 | 줄 커버리지 |
|---|---|---|---|---|
| contract-model | 314 | 0 | 0 | 98.48% |
| core-api | 1240 | 0 | 0 | 96.13% |
| realtime-activity | 1204 | 0 | 0 | 93.39% |
| test-support | 27 | 0 | 3 | (하한 대상 아님) |

### 변경 뒤 전체(Step 17, `./gradlew clean check verifyBootJarHasNoTestClasses -Pmujoong.test.db=docker`, BUILD SUCCESSFUL)

| 모듈 | 시험 | 실패 | 건너뜀 | 줄 커버리지(모듈 하한 80%) |
|---|---|---|---|---|
| contract-model | 322 | 0 | 0 | 98.52% |
| core-api | 1291 | 0 | 0 | 95.92% |
| realtime-activity | 1230 | 0 | 0 | 93.28% |
| test-support | 31 | 0 | 3(기존) | 42.76%(하한 대상 아님, J107) |

- 회귀 0: 기준선의 모든 시험이 그대로 통과(`TextAiGateway` · `ResultTextGateway` · `OutputChecker` · `PracticeStartApiIT` · `RealtimeStartupChecks` · 대조 · ArchUnit 포함, NFR6.1).
- `verifyLunaLiveIsolation`: 4개 모듈 — check 그래프에 `lunaLiveTest` 없음 · `test`는 `luna-live` 제외 · `lunaLiveTest`는 `luna-live`만 · 시험 JVM에 키 없음. 음성 확인: core `check`에 `lunaLiveTest`를 물리는 init 스크립트를 넣으면 '분리 위반'으로 실패한다(저장소 밖 스크립트로만 확인).
- `bootJar`: 두 앱 시험 항목 0건, `luna/schemas/` 자원 8개 · `json-schema-validator-3.0.6.jar` 포함.
- 전체 확인 뒤 커밋 이력을 고쳐 쓴 차이는 `LunaEventLogTest`의 시험 상수 한 줄뿐이고, 그 시험을 다시 돌려 통과했다(6절 gitleaks).

### U9 범위 (`-PincludeTags=u9 -PcoverageScope=u9`)

| 모듈 | U9 시험 | 범위 줄 커버리지(하한 80%) |
|---|---|---|
| core-api | 51 | 93.42%(639/684) |
| realtime-activity | 26 | 91.02%(507/557) |
| contract-model | 8(`LunaEventLogTest` 5 · `U9ContractDocsTest` 3) | 범위 밖(편차 4, 모듈 98.52%) |
| test-support | 4(`LunaResponsesStubTest`) | 하한 대상 아님 |

컴포넌트별: `LunaHosts` 7 · 7, `LunaInputSanitizer` 6(+ realtime 정리 · 스키마 묶음 6), `LunaSchemas` 6, `LunaTransport` 7(+ realtime 1), `CoreLunaClient` 9 · `RealtimeLunaClient` 8, 빈 조건 7 · 3, 어댑터 경계 7, ArchUnit 2 · 2.

### 인프라 스크립트

- `bash infra/ci/test-host-tools.sh`: 37건 중 어긋남 2건 — `turn-metrics-one-turn` · `turn-metrics-p95-judged`. **변경 전 파일로 돌려도 같은 2건이 실패**(기존 · U9와 무관). U9 사례 `log-hygiene-allowlist-matches-java(19종)` · `log-hygiene-luna-events` 통과.
- `infra/bin/infra-validate`: compose-config · compose-invariants · demo-profile · secret-env-files · nginx · migrations · bundle PASS, **shellcheck는 이 PC에 없어 MISSING**(통과로 보지 않음). 대신 고친 두 스크립트(`test-host-tools.sh` · `log-hygiene-check`)만 `koalaman/shellcheck:v0.10.0` 컨테이너로 `-x -S style` 검사해 0건. 전체 shellcheck는 CI가 돈다.

### 의존성 취약점(SD9 · NFR5.4)

- 의존성 이동 뒤 bootJar 두 개를 CI와 같은 trivy 이미지(`aquasec/trivy@sha256:62b1e65e…` 0.74.0) · 같은 명령(`rootfs --scanners vuln --severity HIGH,CRITICAL`, `ci/security-gate.py`가 만든 예외 목록)으로 돌림: **core-api.jar 0건, realtime-activity.jar 0건**. 예외 · 판 변경 없음(`libs.versions.toml` 그대로).
- `infra/ci/image-checks.sh`(이미지 빌드 · 점검)는 로컬에서 돌리지 않았다(CI 이미지 점검 작업이 돈다).

### 실호출(Step 15)

- `./gradlew lunaLiveTest`를 키 없이(`env -u OPENAI_API_KEY`) 돌림: 두 시험 모두 **SKIPPED**, 보고서 `app=core outcome=SKIPPED_NO_KEY` · `app=realtime outcome=SKIPPED_NO_KEY`. 기본 `test`는 `--tests '*CoreLunaLiveTest'`로 골라도 '시험 없음'(태그 제외 확인).

## 5. 증거 종류 구분 (R-M11)

| 종류 | 이번 단계 결과 |
|---|---|
| 테스트 대역(루프백 `LunaResponsesStub`) | 위 시험 전부. 요청 모양(`store=false` · `temperature` 없음 · 비지원 키워드 없는 요청 사본 · strict 판정)은 대역의 요청 검사 모드 기준이며 실제 공급자가 받아들이는지는 확인하지 않았다 |
| 합성 자료 | 시험 입력은 모두 합성 값(가짜 이름 · 전사 · UUID · 연락처 모양) |
| 실제 API | **없음**. `lunaLiveTest`는 키 없이 SKIPPED만 확인. 실호출 · 응답 모델 · 지연은 사람이 키를 넣고 돌려야 한다(RUNBOOK 8.5) |
| 실제 기기 | **없음**(U9는 기기 경로가 없다) |

## 6. 로컬에서 확인하지 못한 것 · 주의

- 실공급자: Responses API의 미완료 · 거절 필드 모양, 요청용 스키마 사본 수락, `temperature` 없는 요청 수락, `json_object` 형식의 'JSON' 단어 요구 여부 — 첫 `lunaLiveTest`에서 확인한다.
- 전체 shellcheck(`infra-validate`), `image-checks.sh`, 끝-끝 `verify.sh`(Build and Test 몫).
- `test-host-tools.sh`의 turn-metrics 2건은 변경 전부터 이 PC에서 실패한다(U9 밖).
- gitleaks(CI와 같은 이미지 · 설정)로 U9 커밋을 검사하다 `LunaEventLogTest`의 응답 모델 속성 값(날짜가 붙은 모델 이름) 한 줄이 `generic-api-key` 오탐으로 잡혔다. CI는 이력 전체를 보므로, push하지 않은 로컬 이력을 다시 써서 값을 상수로 옮겼다(허용 목록 추가 없음, R-F9). 다시 검사해 0건.
- 개발 PC env에 `OPENAI_API_KEY`가 있어도 기본 `test` 작업은 시험 JVM에서 그 키를 지운다. IDE에서 시험을 직접 돌리면 이 보호가 없다.
- `OPENAI_ANALYSIS_MODEL` · `OPENAI_JUDGE_MODEL`을 Compose · `render-env`에 넣는 일은 demo 투입 작업(이번 범위 밖)이다. 없으면 `gpt-6-luna`로 동작한다.

## 7. 코드 검토 반영 (review-01, 사람 선택 Q1-A: 지금 고침)

커밋 `54cfc89` `fix(luna): U9 코드 검토 지적 반영 …`(로컬, push 안 함). 순서는 test-after로 층마다 고치고 바로 그 층 시험을 돌렸다(정리 → 전송 · 빈 → 호출부).

| 지적 | 고친 것 | 시험 |
|---|---|---|
| R-01 (Major) 별칭 치환 순서 | 두 앱 `LunaInputSanitizer.orderedAliases`: 빈 이름 · null 값을 빼고 긴 이름 먼저(같으면 사전순). `applyAliases`는 왼쪽부터 한 번만 훑으며 각 자리의 가장 긴 이름을 바꾸므로 바꿔 넣은 '아이'를 짧은 이름이 다시 바꾸지 않는다. 두 앱 `LunaRequest`도 같은 순서의 읽기 전용 사본으로 보관(`Map.copyOf` 제거) | core `overlappingNamesAreReplacedLongestFirst…` · `aReplacedAliasIsNeverMatchedAgain…`, realtime `overlappingNamesAreReplacedLongestFirst…`: '민수' · '수' · '민' · '민준'을 표 순서 둘로 넣어 결과가 같고 '민' · '수' · '준' 조각이 하나도 남지 않음, '아' 이름이 '아이'를 다시 바꾸지 않음 |
| R-02 금지 키 변형 | 1차 판정 `isForbiddenKey`는 소문자 + `_` · `-` 제거 뒤 비교. 정리 뒤 안전망 `containsForbiddenKey`는 1차를 다시 쓰지 않는 독립 판정(글자 · 숫자만 남긴 소문자 꼴, 예: `child.name`)으로, 남으면 `FORBIDDEN_INPUT`. 금지 목록은 그대로(ai-io.md 10.2 + core PRIVATE_KEYS) | core `forbiddenKeyVariants…` · `theSafetyNetIsAnIndependentCheck…`, realtime `forbiddenKeyVariantsAreRemovedAndTheSafetyNetChecksIndependently` |
| R-03 짧은 재시도 창 | core `CoreLunaClient.worthRetrying`: 대기 < 남은 시간이고 (남은 시간 − 대기) ≥ `mujoong.text-ai.retry.backoff`일 때만 재시도(BR3.6 주석, 새 숫자 시간 값 없음 · R-F7) | core `noRetryWhenTheTimeLeftAfterTheWaitIsBelowTheBackoffWindow`(시험 값 backoff 1초: 남은 1.5초 → 재시도 없음, 2.5초 → 재시도) |
| R-04 보내기 전 거절의 시도 수 | 두 앱 `LunaTransport.rejectBeforeSend`(시한 0 → TIMEOUT · 허용 밖 주소 → REQUEST_REJECTED · 빈 키 → AUTH_FAILED)를 호출부가 시도 전에 확인: `LUNA_CALL attempt=0`, `LunaException.attemptsUsed`는 실제로 보낸 시도 수(첫 시도면 0). `send`도 같은 확인을 유지 | core `aRefusalBeforeSendingIsNotCountedAsAnAttempt`, realtime 같은 이름 시험, core `LunaTransportTest.thePreSendCheck…` |
| R-05 realtime 던지지 않음 | `RealtimeLunaClient.call`이 보내기 전 구간 전체(`start`)를 감싸 런타임 예외를 `APP_ERROR`(errorKind = 예외 클래스 이름만, `events.appError`) + `FORBIDDEN_INPUT`(시도 0) 실패 완료로 바꾼다. 기록마저 실패해도 던지지 않는다(근거 주석). 원인 메시지는 남기지 않는다(BR6.2) | realtime `anUnexpectedRuntimeErrorBeforeSendingBecomesAFailedStageInsteadOfAThrow`(스키마 대역이 던짐 · null 요청 · 기록 실패 대역) |
| R-06 HttpClient 닫기 | 두 앱 `LunaTransport implements AutoCloseable`(`close()` → `client.close()`), 빈은 `@Bean(destroyMethod = "close")` | core `LunaConfigurationTest.closingTheContextClosesTheSharedHttpClient` · realtime 같은 이름, core 전송 시험의 `close()` 확인 |
| R-07 `StructuredLogFormatTest` 17 → 19 | 코드 변경 없음. 2 · 3절 'Code Generation 편차 3'에 근거(R-M10, 정확 일치 단언 유지)가 이미 적혀 있음을 확인. 사람 확인 대상 | — |
| R-08 관심사(`interests`) | 코드 변경 없음. `contracts/ai-io.md` 7절에 '열린 점(U10에서 정함)' 한 줄: 지금 Luna 어댑터는 금지 키라 관심사를 보내지 않고, 7절 입력 목록과의 충돌은 U10이 정함 | — |

계약 문서(`contracts/ai-io.md`, R-M10): 10.8 `attempt` 0 범위(주소 재확인 · 빈 키 포함, 시도 수에 세지 않음), 10.10 키 정규화 · 독립 안전망 · 별칭 순서 · realtime 예기치 못한 오류 → `FORBIDDEN_INPUT`(실패 사유 표 행 보강) · 재시도 최소 창, 7절 R-08 열린 점. 사유 이름 · 오류 코드 · 설정 키는 새로 만들지 않았다.

### 시험 결과 (증거 종류: 테스트 대역(루프백 `LunaResponsesStub`) · 합성 자료, R-M11)

- U9 태그 `./gradlew :contract-model:test :core-api:test :realtime-activity:test -PincludeTags=u9`: 통과. core 59(+8) · realtime 31(+5) · contract-model 8.
- U9 범위 커버리지 `… -PincludeTags=u9 -PcoverageScope=u9`: 통과. core **93.86%(688/733)**, realtime **91.87%(565/615)**(하한 80%, 바꾸지 않음).
- 전체 `./gradlew clean check verifyBootJarHasNoTestClasses -Pmujoong.test.db=docker`: BUILD SUCCESSFUL.

| 모듈 | 시험 | 실패 | 건너뜀 | 줄 커버리지(모듈 하한 80%) |
|---|---|---|---|---|
| contract-model | 322 | 0 | 0 | 98.45% |
| core-api | 1299 | 0 | 0 | 95.94% |
| realtime-activity | 1235 | 0 | 0 | 93.28% |
| test-support | 31 | 0 | 3(기존) | 42.76%(하한 대상 아님) |

- gitleaks(CI와 같은 이미지 v8.30.1 다이제스트 · `.gitleaks.toml`, `586238b..HEAD` 10커밋): 0건.
- 커밋 전 `git diff --cached --summary`: 모드 변경 없음.
- `source-manifest.json` · `traceability.json`: 고친 경로가 모두 이미 목록에 있고 추적 대상이 바뀌지 않아 그대로 둔다.
- 다시 돌리지 않은 것: `infra/bin/infra-validate` · `test-host-tools.sh`(인프라 스크립트 변경 없음), `verify.sh`(Build and Test 몫), 실호출(`lunaLiveTest`, 키 없음).
