# 코드 품질 평가 (Code Quality Assessment)

> 기준(혼합): **이번 갱신** = 커밋 `9bcc706`(= main `7e30ee8` + U11 지시서), 2026-10-16 집중 스캔(인텐트 `261016-u11-judgment-turn`). 갱신 · 확장한 곳은 1절의 realtime 행, 5절 TD-1 · TD-2 · TD-4 · TD-7 · TD-8의 표시 내용, 새 6절 「U11 판정 턴 관점 위험(U11-Rn)」이다.
> **그 밖의 문장** = 커밋 `72d7034`(2026-10-15 전체 재스캔) 그대로다(STALE). 이 문서가 기술 부채(TD-n)와 U11 위험(U11-Rn) 목록의 정본이며, 다른 문서는 번호로 가리킨다.

## 1. 시험

| 위치 | 규모 | 비고 |
|---|---|---|
| `backend/contract-model/src/test/` | 15 클래스 · @Test 120 | 계약 타입 (72d7034) |
| `backend/core-api/src/test/` | 157 클래스 · @Test 996 | ArchUnit `core/arch` 포함 (72d7034) |
| `backend/realtime-activity/src/test/` | (2026-10-16 갱신) 164 파일 | ArchUnit `realtime/arch`(`RealtimeArchTest` · `LunaArchTest` · `U8LiveContentArchTest` · `SingleInstanceMarkerTest` · `TimeLiteralScanTest`), 실행기 시험 장치 `testing/`, 설정 대조 `config/ConfigKeyContractTest`, 스키마 `db/RealtimeSchemaIT` |
| `backend/test-support/src/test/` | 4 클래스 · @Test 21 | (72d7034) |
| `frontend/tests/u1 … u7/` | 시험 파일 73 | Vitest + Testing Library + jsdom (72d7034) |
| `tools/tests/u1 · u3 · u4a · u4b/` | 11 | Vitest (72d7034) |
| `infra/ci/test-*.sh` | — | Bash 음성 대조 시험 |
| `tools/skeleton-e2e/`, `scripts/verify/c-skeleton-turn.sh` | — | 끝-끝 스켈레톤 |

백엔드 시험 태그(72d7034, 전 모듈): db 107 · u1 142 · u2 15 · u3 24 · u4a 5 · u4b 24 · u5a 11 · u5b 34 · u6 35 · u7 17.
(2026-10-16 갱신) realtime-activity 시험 태그(클래스 기준): db 43 · u1 54 · u3 24 · u4b 23 · u5b 27 · u8 9 · u9 5 · `luna-live` 1(격리). **`u11` 태그 시험은 아직 없다.**

커버리지: 백엔드 JaCoCo 줄 80% 하한(모듈별, test-support 제외), `-PcoverageScope=<유닛>`로 `backend/coverage-scope/*.txt` 범위 측정(빈 목록 · 없는 파일이면 실패, J137). 프런트 v8 줄 80% 하한, `MUJOONG_COVERAGE_SCOPE`로 `frontend/coverage-scope/*.json` 범위 측정. CI가 전체 + U5b · U7 범위를 따로 검증. (2026-10-16 확인) `backend/coverage-scope/u11.txt`는 아직 없다.

실행기 시험 장치(2026-10-16 확인): `testing/SessionHarness`(수동 시계 `MutableClock` · `ManualTimerDriver` · `FakeProviderPort` · `ManualLauncher` · `FakeDeviceLink` · `MemoryStore`), 확장점 대역 `RecordingPracticeExtension`. `ManualLauncher`는 등록한 작업을 등록 순서대로 한 스레드에서 돌린다 → 운영의 가상 스레드 경쟁(U11-R2)을 재현하지 못한다.

이번 스캔에서 시험은 돌리지 않았다(읽기 전용). 기준선은 Code Generation 전에 잰다.

## 2. 린트 · 서식

- 프런트: ESLint flat config(`frontend/eslint.config.mjs`) — next core-web-vitals · typescript, `/parent` ↔ `/device` ↔ `shared` import 경계, `react/no-danger`, `react/jsx-no-literals`(화면 문구는 copy 모듈만), `getUserMedia` 사용처 1곳, 서비스 워커 금지(U7 보호자 SW 예외).
- 백엔드: javac `-Xlint:all,-processing,-serial,-this-escape,-try`(경고를 오류로 바꾸지 않음) + ArchUnit 계층 · 의존 규칙 + `TimeLiteralScanTest`. Java 포매터(Spotless · Checkstyle) · 줄 길이 검사 없음.
- `.prettierrc.json`(printWidth 100) · `.editorconfig` 있음. CI에 Prettier 검사 단계 없음.
- (2026-10-16 갱신) `SessionExecutor` 안 두 서식: U5b 추가분(`speak` L1405 ~ L1428, `enqueuePracticeProvider` L2342 ~ L2361)과 `PracticeSessionExtension` L148 ~ L166이 한 줄 여러 문장 · 완전 한정 이름(`java.util.…` 22곳 등)을 쓴다. 팀 규칙 Q7-A: 손대는 부분만 정리.

## 3. CI/CD

- `.github/workflows/ci.yml` 작업: changes · backend(`gradle clean check verifyBootJarHasNoTestClasses`) · u5b-scope · U7 범위 · backend-deps(Trivy) · frontend(ESLint · Vitest 커버리지 · `next build` · 번들 비밀 문자열 0건) · frontend-deps(npm audit) · tools · secrets(gitleaks) · infra-validate(Compose 불변식 12 · `nginx -t` · shellcheck · 마이그레이션 expand 검사) · images(6종 빌드 · Trivy · 이미지 점검 · GHCR 푸시) · skeleton. 액션은 커밋 SHA 고정. (72d7034; 2026-10-16에는 이름만 확인)
- `.github/workflows/deploy.yml`(단계 이름만 확인): 수동/자동 DEPLOY · ROLLBACK, OIDC → SSM `mujoong-deploy`, demo 환경. `SERVICE_HOST` · `AUTO_DEPLOY` 변수가 없어 실제 배포 단계는 건너뛴다.
- (2026-10-16 갱신) 실호출 시험 `lunaLiveTest`는 CI · `check`에 없고 루트 `verifyLunaLiveIsolation`이 격리를 강제한다(편차 U9-c). GPT-Live 실선 형식을 확인하는 같은 격리 작업은 없다.

## 4. 문서화

- 루트 `README.md` · `AGENTS.md`, `infra/RUNBOOK.md`(demo 운영 정본), `contracts/README.md` + `deviations.md`(171줄, U8 · U9 편차 표 포함), `docs/units/<u>/{brief,design,report}.md`(U11 지시서 포함), `docs/erd/ERD_v0.2.md`, `docs/change-history.md`.
- 코드 주석: 한국어 Javadoc이 촘촘하고 결정 ID(J · BR · U*-J · 편차)를 인용한다. TODO · FIXME는 앱 소스에 0건(남은 일은 문서로 관리). (2026-10-16 갱신) `SessionExecutor` 머리 주석은 U1 · U3 범위까지만 설명하고 U4b · U5b 추가분은 메서드 주석에 흩어져 있다.

## 5. 기술 부채 (TD)

| ID | 내용 | 위치 · 증거 | 영향 | 관계 |
|---|---|---|---|---|
| TD-1 | **AI 공급자 운영 어댑터 부재 — 기본 빈이 항상 실패(fail-closed)** | `core/textai/TextAiConfiguration.java`(`unavailableTextAiProvider` → `UNAVAILABLE`), `core/result/ResultTextConfiguration.java`(`PROVIDER_UNAVAILABLE`) — 72d7034. `realtime/practice/PracticeRuntimeConfiguration.java` L46 ~ L48(`EvaluationProvider` 실패 future "판정 공급자 미연결", `CueProvider.UNAVAILABLE`) — 2026-10-16 재확인. (2026-10-16 갱신) U9가 realtime `RealtimeLunaClient`(`JUDGMENT_LIVE`)를 더했으나 **키가 있을 때만 빈**이고 소비자가 없다. core `CoreLunaClient`는 미재확인 | 운영에서 레거시 연습 판정 · 단서가 동작하지 않음. 판정 턴은 Luna 위에 새로 짜야 함 | U11-R6 |
| TD-2 | **음성 공급자 선 형식이 프로젝트 정의(J110)** | 모델 `gpt-live-1`, `session.*` type 체계, URL `/v1/live/sessions` — `realtime/voice/ProviderRequestFactory.java`, `ProviderClient.java`, `application-{demo,prod}.yml`, `contracts/ai-io.md` 3절 | 실공급자 형식과 다르면 연결 불가. 가짜 공급자 · `test-support` 대역도 같은 형식이라 함께 바꿔야 함. (2026-10-16 갱신) 응답 생성 확인 사건이 없다 | U11-R1 |
| TD-3 | 지식 그래프(Neo4j) 읽기 어댑터 없음 | `core/knowledge/KnowledgeGraphLookup` 인터페이스만, `mujoong.knowledge.neo4j.enabled=false`. Compose demo에 `neo4j` 서비스(프로필 knowledge)는 있음 | 기능 미사용, 설정 · 이미지만 존재 | — |
| TD-4 | **거대 클래스 `SessionExecutor`** | `realtime/session/SessionExecutor.java` 3,388줄 · 상태 필드 약 70 · 메서드 약 130, 이 파일만 17번 커밋. 다음: `FileAudioRecorder` 619 · `CoreProperties` 576 · `StopIntentJudge` 565 | 세션 동작 변경의 영향 범위 · 리뷰 비용 큼. U11 · U13 · U12가 차례로 고침 | U11-R10 |
| TD-5 | **압축 서식(한 줄에 메서드 전체)** | U6 계열 `core/report/web/ReportController.java`, `core/report/ReportRepository.java`, `core/result/SessionResultHandler.java`, `core/result/ResultAiSchema.java`, `core/result/ResultTextConfiguration.java`, 프런트 `parent/screens/Report30.tsx` · `RecordDetail27.tsx` · `ReportRange28.tsx` · `parent/api/results.ts`. (2026-10-16 갱신) realtime `SessionExecutor` · `PracticeSessionExtension`의 U5b 추가분 | 가독성 · diff 리뷰 저하. CI에 Java 포매터 · Prettier 검사가 없어 막히지 않음 | 팀 Q7-A |
| TD-6 | **시간 값이 세 곳에 중복** | 두 앱 `application.yml`의 `mujoong.timing.*` ↔ `contracts/timing.md` ↔ `frontend/src/shared/timing/timing-defaults.json`(시험이 대조). 서버 전용 키는 timing.md · `application.yml` · 설정 레코드(편차 U8-c) | 하나만 바꾸면 시험 실패 또는 불일치 | R-M5 |
| TD-7 | **마이그레이션 번호 · 승인 규칙** | 다음 번호 core V8 · realtime V5, Flyway `outOfOrder` 꺼짐, 기존 표 제약 강화는 `infra/migration-approvals.txt` 승인 행 필요. (2026-10-16 갱신) realtime 판 단언이 "4"에 묶임 | 병렬 브랜치 번호 충돌, V5 추가 시 앞 유닛 시험 단언 수정 필요 | U11-R4 |
| TD-8 | **커버리지 범위 목록 유지 부담** | `backend/coverage-scope/*.txt` · `frontend/coverage-scope/*.json` + 유닛 태그. (2026-10-16 확인) `u11.txt` 없음 | 파일을 옮기거나 새로 만들면 목록 수정 필요 | — |
| TD-9 | 유닛 혼합 이력 | U2~U5a는 엔진 없이 병렬 구현, U5b · U6 · U7은 통합 브랜치로 반영(`docs/units/README.md`) | 유닛마다 설계 문서 · 편차 기록의 밀도가 다름 | — |
| TD-10 | 알려진 흔들림 · 남은 일 | `RealtimeInternalApiIT` 부하 시 409, 세션 문구 6종 음성이 합성음 자리(`infra/seed-audio/*-v1|v2.wav`), 시드 003을 검증 스크립트 · RUNBOOK 시드 순서에 넣는 일 | 시험 간헐 실패, 데모 음성 품질 | — |

## 6. (2026-10-16 갱신) U11 판정 턴 관점 위험 (U11-Rn)

개발자 스캔(`aidlc/spaces/default/intents/261016-u11-judgment-turn/inception/reverse-engineering/developer-scan.md`)의 우려를 설계 담당이 코드로 다시 짚어 정리했다. 줄 번호는 커밋 `9bcc706` 기준. "다음 단계" 열은 결정이 아니라 어느 단계가 정해야 하는지만 적는다.

| ID | 위험 | 증거 | 왜 문제인가 | 다음 단계 |
|---|---|---|---|---|
| U11-R1 | **GPT-Live 선 형식 불확실(중단 조건)** | `ProviderClient` 수신 9종에 `response.created` 류 없음, 응답 id는 첫 출력 조각으로만 앎(`ProviderResponseId`). 선 형식은 J110 가정(ai-io.md 3절) | '지시 뒤 응답만 통과'(C-DS15)를 공식 신호가 아니라 세대 번호(`bumpGeneration`) · 취소 목록(`cancelledResponses`) 가정에 둘 수밖에 없다. 실선 형식(FR12.2)에서 맞는지는 실호출로만 확인. GPT-Live용 격리 실호출 작업이 없다 | FR12.2 실호출 결과 전에는 선 형식을 바꾸지 않음(ADR-007). Functional Design에서 가정과 실호출 확인 항목을 분리 |
| U11-R2 | **공급자 송신 순서 보장 없음** | `toProvider`(L2324 ~ L2340)가 출력권 없는 경로에서 송신마다 `launcher().launch`로 가상 스레드를 띄움(L2339). `ProviderClient.Session` 송신 잠금은 동시 쓰기만 막음. 순서 보장은 직렬 큐 `enqueuePracticeProvider`(L2342 ~ L2361)뿐이고 `controlsOutput()`일 때만 탐. `speak`(L1405 ~ L1428)는 한 작업 안에서 cancel → correct → create | `session.instructions.correct` 다음 `session.response.create`를 두 번의 `toProvider`로 보내면 공급자가 응답 생성을 먼저 받을 수 있다. `ManualLauncher`가 이 경쟁을 시험에서 드러내지 못한다 | Functional Design: 판정 턴 송신을 한 작업 안 연속 송신 또는 직렬 큐로. 시험은 순서를 단언하는 대역 필요 |
| U11-R3 | **보류 출력 경로가 '취소 + 원문 기록'** | `outputHeld()`(L1600 ~ L1604)가 참이면 `refuseHeldResponse`(L1593)가 `session.response.cancel`로 취소하고 이후 조각(전사 포함)을 버림. 보류가 아니면 `beginOutput`(L1734 ~ L1743)이 첫 조각에서 무조건 `transcripts.aiStart`(L1740). 게이트 폐기도 `aiFinal(text, false, …)`로 `realtime_transcript`에 원문 저장(`TranscriptWriter` L112 ~ L122), 표시 · 스냅숏에서만 거름(`ResultSnapshotBuilder` L115) | 판정 턴 요구(FR5: 자동 응답 음성은 버리되 전사는 `judgment_live`에만, `HELD_OUTPUT_TRANSCRIPT` 사건, 대화 기록에 남기지 않음)를 담을 갈래가 없다. 기존 보류에 얹으면 전사를 잃고, 게이트 경로에 태우면 대화 기록에 남는다 | Functional Design: 보류 출력은 구간 버퍼 · 전사 기록기 **앞**에서 갈라 `judgment_live`로만 |
| U11-R4 | **V5 추가 시 판 번호 단언** | `RealtimeSchemaIT` L46 ~ L56(표 목록) · L88 · L94(최신 판 "4"), `PracticeMigrationRegistrationTest` L22(`expectedVersion()` == "4"). `SchemaVersionCheck.expectedVersion()`은 classpath 최대 판을 스스로 계산 | `judgment_live`(realtime V5)를 더하면 앞 유닛 시험 단언이 깨진다. R-F2(기존 단언을 낮추지 않음)와의 관계를 사람이 정해야 한다. 앞 유닛 V4도 같은 방식으로 목록을 늘린 것으로 보임 | Requirements Analysis에서 질문: 단언 갱신(사실 반영)을 R-F2 위반으로 볼지 |
| U11-R5 | **판정 출력 스키마 검증 빈틈** | `ai-judgment-output.schema.json`: `first` · `second` 각 `{option: ^[A-Z][A-Z0-9_]*$, probability: 0~1}`만 | 요청 `options` 밖 코드, `first.option == second.option`, 확률 합 1 초과가 스키마를 통과한다. ai-io 10.3 "선택지 밖 코드는 판정 보류" | Functional Design: `JudgeClient` 구현이 직접 걸러 보류로 바꿈(스키마 변경은 계약 변경 — R-M10) |
| U11-R6 | **Luna 빈이 키 있을 때만** | `LunaConfiguration` L43 ~ L58 `@Conditional(LunaKeyPresentCondition)` — `LunaTransport` · `RealtimeLunaClient` 둘 다 | 시험 · 키 없는 demo에는 빈이 없어 `JudgeClient`가 주입 실패하거나 null 처리가 필요하다. R-F1(시험에서 실공급자 금지) · R-F15(확인 기록 전 아동 데이터 외부 전송 금지, 편차 U9-b)와 묶인다 | Functional Design: 빈 없을 때 '언제나 보류' 동작과 시험 대역을 정함 |
| U11-R7 | **겹치는 판정기** | 자유대화 J1(참여) = `JoinTracker`(ACCEPT · REFUSE · TOPIC_REFUSAL · UNCLEAR), J6(종료 의도) = `StopIntentJudge`(STOP · CONFIRM · NONE). `judgeStopIntent`(L1809) 호출 순서가 출력권 여부에 따라 다름 — 출력권 확장은 확장점 전달 **앞**, 아니면 **뒤** `thinking()` 조건 안(L1676 ~ L1688) | 같은 질문을 규칙과 Luna가 따로 답하면 결과가 엇갈린다. 등록 종료 표현 → 판정 멈춤(FR3.5)을 지키려면 종료 판정이 판정 턴 시작보다 앞서야 한다 | Functional Design: 대체/보조(예: 규칙이 UNCLEAR · CONFIRM일 때만 Luna) 결정, 종료 판정 순서 고정 |
| U11-R8 | **없는 설정 키 · 문구** | `mujoong.judgment.min-confidence` · `min-margin` 키 없음. `ConfigKeyContractTest`가 yml `mujoong.*` 키 집합 = 설정 레코드가 읽는 키 집합을 강제, `judgment` 묶음은 `TIMING_GOVERNED_GROUPS`(L73 ~ L75)에 없음. `contracts/timing.md` 13절의 응답 준비 대기 초과 `D_HOLD` 되묻기 문구가 `DialogInstructions`(`Correction` 열거 · `JOIN_FIRST_QUESTION`만)와 U8 승인 발화 시드(ai-io 10.6)에 없음. 다음 행동 지시 템플릿 · 프롬프트 지시문 · `promptVersion` 정본도 없음 | 새 키를 별도 설정 클래스로 두면 `ConfigKeyContractTest` 바인딩을 고쳐야 해 R-F2와 부딪힐 수 있다. `D_HOLD` 문구 정본이 없으면 대기 초과 동작을 구현할 수 없다 | 새 키는 `RealtimeProperties` 루트 레코드에 한 칸. `D_HOLD` · 지시 템플릿은 Requirements Analysis 질문(정본 개정이 필요하면 RP 표). 지시문에 판정 라벨 · 확률 · 기준을 넣지 않는 규칙(FR3.7)은 `PracticeInstruction` 같은 값 타입으로 강제 가능 |
| U11-R9 | **ArchUnit 패키지 제약** | `LunaArchTest` L31 ~ L37(`luna`는 `voice` · 제브 · 로그 프레임워크 · Jackson 참조 금지), `RealtimeArchTest` L174 ~ L180(`gate` · `voice`는 JDBC · 저장소 · Outbox 참조 금지) | `JudgeClient` 구현이나 `judgment_live` 저장소를 `luna` · `gate` · `voice`에 두면 아키텍처 시험이 실패하고, 시험을 고치는 것은 R-F2 위반 | Functional Design: 새 판정 패키지(예: `kr.mujoong.realtime.judgment`)에 둠 |
| U11-R10 | **`SessionExecutor` 파일 소유 · 충돌 면** | 3,388줄 · 상태 필드 약 70 · 메서드 약 130, 17번 커밋. U11 → U13 → U12가 차례로 고침(지시서 · project.md 교정). U13 제브 자리 = `sealAndGate`(L2165 ~ L2206) `inspect` 뒤 · `recheck` 앞 | 판정 턴 상태(차례 id · 보류 버퍼 · 응답 준비 시계 · 늦은 결과 거르기)를 이 클래스에 더 쌓으면 U13 · U12 병합 충돌과 리뷰 비용이 커진다 | Functional Design: 판정 턴 상태를 실행기 스레드 전용 협력 객체로 떼고, 실행기에는 호출 지점만 |
| U11-R11 | '생각 중'이 보류 중엔 안 나감 | `thinking()`(L2135 ~ L2139)은 `!outputHeld()`일 때만 THINKING. `listeningAgain()`(L2142)은 게이트 폐기 · 대기 문구 경로에서 LISTENING으로 | 판정 턴 보류를 `outputHeld()`에 합치면 FR5 "보낼 응답이 없으면 생각 중 유지"와 부딪힌다 | Functional Design: 판정 턴 보류를 `outputHeld()`와 다른 조건으로, 또는 `setThinking` 능력으로 |
| U11-R12 | 늦은 비동기 결과 거르기 틀 없음 | `RealtimeLunaClient.call`(L63)의 완료 콜백은 HTTP 클라이언트 스레드. 실행기 상태는 실행기 스레드만 바꿈. 편지함 경로(`PracticeOutput.post` → `putSafety` → `onPracticeEvent` L1224)는 있으나 비교 기준 '판정 차례 id'가 없음. 장애 경로 `providerLost()`(L1758) · `abortProviderLost()`(L1780) · `startPause`(L2046) | 판정 결과가 장애 취소 · 다음 차례 뒤에 도착하면 낡은 결과로 출력이 풀릴 수 있다(FR5 장애 취소) | Functional Design: 차례 id · 출력 세대로 늦은 결과를 버리고 장애 경로에서 판정 턴을 '장애 취소'로 기록 |
| U11-R13 | 삭제 · 계약 열린 질문 | `RealtimeDeletionParticipant.order()`: 음성 100 → 연습 150 → 세션 200 → 메시징 900. `DeletionCompleted.counts` 포함 여부는 U8 contract-summary 4절 열린 질문(U11 · U13 · U15). 실행기는 DB를 기다리지 않는 관례(`AsyncRecorder`) | `judgment_live`가 세션 행에 외래 키를 걸면 200 앞에서 지워야 한다. 쓰기 경로의 비동기 · 재시도 정책도 미정 | Requirements Analysis: counts 포함 여부, Functional Design: 삭제 순서 · 비동기 쓰기 |

추가로 지킬 사실(위험은 아님):

- 확장점 포트는 더하기만으로 넓힐 수 있다 — `PracticeOutput`의 `default false` 선례(`speak` · `cancelSpeech`), `ExtensionEvent.Kind`를 다 훑는 `switch`가 운영 · 시험 코드에 없음(값 추가가 컴파일을 깨지 않음).
- 레거시 `EvaluationProvider`(U5b)와 새 `JudgeClient`는 이름 · 역할이 겹치지만 별개로 둔다(지시서).
- 스캔 범위에서 `Instant.now()` · `System.currentTimeMillis()` 직접 사용은 없다. 숫자 리터럴은 `SegmentBuffer.MAX_BYTES`(바이트) · `InputRelay.VOICE_ENERGY_THRESHOLD`(진폭) · `LunaTransport`의 `Retry-After` 해석 정도이고 허용 목록으로 관리된다.

## 7. 이번 스캔의 확인 한계

- 2026-10-15 전체 재스캔: 업무 로직 본문(core result · practice · activity, realtime session · gateway, frontend parent · device)은 얕게 봤다. TD-1 · TD-2 · TD-6은 파일을 직접 읽어 확인했고, TD-4 · TD-5는 크기 · 줄 길이로 판단했다.
- 2026-10-16 집중 스캔: realtime 실행기 · 확장점 · 게이트 · 공급자 · Luna · 판 리소스 · 판정 계약을 깊게 봤다. gateway · messaging(삭제 참여자 밖) · startup · observability · recording · health · web · internalhttp, 연습 런타임 본문, 나머지 시험 본문, core-api · frontend · tools · infra는 다시 보지 않았다. 시험은 돌리지 않았다.
- 저장소 판정이 STALE이므로 2026-10-15의 깊은 경로는 범위 블록에서 `shallow.paths`로 내렸다([reverse-engineering-timestamp.md](reverse-engineering-timestamp.md)).

## 8. 종합

시험 · CI · 계약 관리는 촘촘하다(시험 약 2천 개, 줄 80% 하한, 계약 시험, 불변식 검사, Luna 실호출 격리). 약점은 (1) 외부 AI 운영 경로가 부분적이고 음성 선 형식이 자체 정의라 실공급자 연결이 검증되지 않은 점, (2) 세션 실행기 집중과 압축 서식, (3) 시간 값 · 커버리지 범위 · 마이그레이션처럼 여러 곳을 함께 고쳐야 하는 규칙이다. (2026-10-16 갱신) U11 판정 턴은 확장점 · Luna 호출부 · 판 리소스라는 좋은 기반 위에 서지만, 보류 출력 갈래(U11-R3) · 송신 순서(U11-R2) · 선 형식 가정(U11-R1)은 그대로 기대면 요구를 어기므로 Functional Design에서 먼저 풀어야 한다.
