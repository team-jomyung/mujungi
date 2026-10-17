> 현재 범위의 정본은 바로 아래 U14 갱신절이다. 그 뒤 원문은 보존 이력이며 재검증 범위는 timestamp에 한정한다.

## U14 집중 갱신 — 변경 표면

운영 서비스·저장소는 core.practice / core.result, 보고 투영은 core.report.ReportAssembler다. core V4에 후보·선택의 행동 정의 FK가 있으며 V5에 결과 시도·장면 제약이 있다. V8 파일은 U15로 이미 존재하므로 U14는 V9를 새로 추가한다. 적용된 V4/V5를 편집하지 않는다.

U15 ChildKnowledgeService, SeedGenerationService, StructureSignature는 구현되어 있고 별도 ChildKnowledgeGraphPort 및 Neo4j/메모리 어댑터 파일이 존재한다. 이번에는 서비스 통합 경계만 깊게 읽었으며 Cypher·전체 그래프 내부는 재검증하지 않았다. U14는 SavedGoalReader를 제공해야 한다(현 practice/result 선언 검색에서 미발견).

정확한 deep 파일은 timestamp에 제한한다. 나머지 기존 구조 설명은 보존한 과거 관찰이다.

---

# 코드 구조 (Code Structure)

> 최신 확인: 2026-10-16, HEAD 7e30ee8, U15 집중 분석. 아래 최신 절이 U15 관련 과거 설명보다 우선한다. 이전 분석 본문은 이력으로 보존하며 이번 범위 밖의 검증 주장은 shallow로 낮췄다.

## U15 집중 분석 구조

- core/knowledge: 모델, JDBC 저장소, 서비스, 공개 그래프 포트.
- core/luna: 선택적 외부 호출, 입력 정리, 목적별 스키마, 제한 재시도.
- core/common/deletion 및 account/DeletionCommander: 멱등 삭제 참가자와 재시도 지휘.
- core/result/check/ForbiddenDictionary: 금지문구 검사. 리포트 안내문 예외를 씨앗에 그대로 사용하지 않는다.
- contracts/schemas/ai-seed-output.schema.json: 현재 두 필드만 허용. core-api processResources가 복사하므로 별도 정본을 만들지 않는다.
- core V7 및 afterMigrate: 기존 마이그레이션 경계. U15 V8은 아직 없다.

정확한 검증 파일 목록은 시점 문서에 한정한다. 아래 전체 트리는 이전 스캔의 보존 자료다.

## 이전 분석 기록 — 2026-10-15, 현재 범위 밖은 재검증하지 않음

> 기준: 커밋 `72d7034`, 2026-10-15 전체 재스캔. 구성 요소별 책임은 [component-inventory.md](component-inventory.md), 빌드 의존은 [dependencies.md](dependencies.md)를 본다.

## 저장소 최상위

| 경로 | 분류 | 내용 |
|---|---|---|
| `backend/` | 앱 소스 | Gradle 멀티 프로젝트: `contract-model` · `core-api` · `realtime-activity` · `test-support` + 포함 빌드 `build-logic`, `coverage-scope/*.txt` |
| `frontend/` | 앱 소스 | Next.js 웹 앱 1개(`src/app` 라우트 · `src/parent` · `src/device` · `src/shared`), `tests/u1…u7`, `coverage-scope/*.json` |
| `tools/` | 시험 도구 | `fake-provider` · `device-simulator` · `skeleton-e2e` · `dev-edge` · `seed` · `lib`, `tests/` |
| `contracts/` | 계약 정본 | OpenAPI 3개 · AsyncAPI 2개 · JSON Schema 60개 · 규칙 문서(`timing` · `errors` · `ports` · `ai-io` · `deviations`) |
| `infra/` | 배포 구성 | Compose(dev · ci · demo) · nginx · migrate · postgres · backup · bundle · seed · seed-audio · host · bin · aws · ci 스크립트, `RUNBOOK.md`, `migration-approvals.txt` |
| `scripts/` | 검증 | `verify.sh`(A · B · C 단계) + `verify/*`, `cloud-setup.sh` |
| `ci/` | CI 보조 | `security-gate.py`, `security-exceptions.yml` |
| `.github/workflows/` | CI/CD | `ci.yml`, `deploy.yml` |
| `docs/` | 문서 | `units/<u>/{brief,design,report}.md`, `erd/`, `change-history.md`, `project-description.md`, `aidlc_drafts/` |
| `aidlc/`, `.claude/` 등 | 작업 방식 | AI-DLC 기록 · 설정(스캔 제외) |

## 백엔드 패키지 구조

루트 패키지: `kr.mujoong.contract` · `kr.mujoong.core` · `kr.mujoong.realtime` · (test-support) 하위.

### contract-model (`kr.mujoong.contract`, 약 4.2천 줄)

| 패키지 | 파일 · 줄 | 내용 |
|---|---|---|
| `ws` | 22 · 996 | `WsMessageType`(17종) · `WsEnvelope` · `WsPayload` · `CloseCode` · `message/*` |
| `event` | 10 · 595 | `EventType`(6종) · `EventEnvelope` · `AggregateType` · `payload/*` |
| `status` | 7 · 202 | `SessionStatus` · `EndReason` · `EndRequestReason` · `FailureCode` · `ActivityType` · `ConnectionState` · `RegistrationStatus` |
| `log` | 5 · 956 | 구조화 로그 |
| `json` | 1 · 448 | Jackson 3 직접 사용 유일 지점 |
| `time` · `audio` · `error` · `profile` · `secret` · `trace` | 소형 | 시계 · 음성 형식 · 오류 봉투 · 프로필 · 비밀 · 추적 |

### core-api (`kr.mujoong.core`, 23 패키지, 약 23.6천 줄)

| 패키지 | 파일 · 줄 | 역할 |
|---|---|---|
| `activity` | 18 · 2,787 | 활동 시작 · 종료 · 현재 세션 · 세션 뷰, Realtime 내부 클라이언트 |
| `device` | 17 · 2,222 | 등록 코드 · 부트스트랩 · claim · 기기 토큰 검증 · 연결 문구 |
| `messaging` | 14 · 2,024 | Core Outbox/Inbox, 상태 · 기기 상태 이벤트 처리기 |
| `identity` | 20 · 1,938 | Google 로그인 · dev 로그인 · 시드 · 보호자/아이/동의 엔티티 |
| `notification` | 29 · 1,710 | 웹 푸시 구독 · 발송 스케줄 · VAPID 전송 |
| `practice` | 29 · 1,586 | 목표 선택 · 상황 · 연습 시작 · 내부 상황 API |
| `web` | 14 · 1,205 | 공통 웹(홈 · 정책 · 오류 처리 등) |
| `result` | 41 · 1,169 | 결과 작업 · 수준 계산 · AI 결과 스키마 · 금지 사전 · 팁 · 재시도 (압축 서식, TD-5) |
| `security` | 10 · 1,135 | 보안 설정 · 기기 쿠키 · Origin · 내부 토큰 · 속도 제한 |
| `record` | 9 · 957 | 기록 목록 · 상세 |
| `observability` | 7 · 952 | 지표 · 로그 |
| `config` | 4 · 688 | `CoreProperties`(576줄) 등 |
| `consent` · `startup` · `account` · `child` · `common` | 각 5~8 | 동의 · 기동 점검 · 계정 삭제 · 아이 · 공통 |
| `report` | 14 · 495 | 리포트 조립 · PDF 렌더 · 보존 (압축 서식) |
| `internalhttp` | 2 · 377 | 내부 HTTP 클라이언트 · 인증 인터셉터 |
| `catalog` · `textai` · `knowledge` · `health` | 소형 | 승인 콘텐츠 목록 · 텍스트 AI 포트 · 지식 그래프 포트 · 헬스 |

### realtime-activity (`kr.mujoong.realtime`, 17 패키지, 약 25.9천 줄)

| 패키지 | 파일 · 줄 | 역할 |
|---|---|---|
| `session` | 34 · 7,885 | 세션 상태 · 전이 표 · 타이머 · 실행기(`SessionExecutor` 3,388줄) · 기록 캡처 · 내부 컨트롤러 |
| `gateway` | 21 · 3,552 | `/ws/device` 핸들러 · 핸드셰이크 · 하트비트 · 연결 상태 · 소켓 등록 |
| `messaging` | 19 · 1,960 | Realtime Outbox/Inbox |
| `log` | 9 · 1,948 | 대화 기록 · 지연 · 세션 공백 기록 |
| `practice` | 29 · 1,609 | 연습 런타임(판정 · 단서 · 상황 클라이언트) |
| `voice` | 10 · 1,390 | 공급자 연결 · 요청 생성 · 입력 중계 · 음성 탭 |
| `dialog` | 5 · 1,208 | 역할 지시문 · 침묵 시계 · 중단 의도 판정(565줄) · 중단 확인 · 합류 추적 |
| `observability` | 8 · 1,151 | 지표 |
| `gate` | 6 · 885 | 출력 게이트 · 문장 버퍼 · 시드 사전 · 승인 재생 · 마무리 순서 |
| `startup` · `recording` · `common` · `web` · `config` · `internalhttp` · `runtime` · `health` | 소형~중형 | 기동 · 원음 녹음(`FileAudioRecorder` 619줄) · 공통 · 웹 · 설정 · 내부 HTTP · 타이머/작업 실행기 · 헬스 |

### test-support

`db`(PostgreSQL 시험 DB, 971줄) · `device`(기기 시뮬레이터) · `http`(계약 대역) · `provider`(가짜 공급자 WS) · `time`(가변 Clock) · `log` · `secret`.

## 자원 파일

- core: `application{,-dev,-demo,-prod,-load-test}.yml`, `db/migration/core/V1~V7 + afterMigrate.sql`, `prompts/result/*.v1.txt`, `forbidden/result-v1.json`, `textai/u5a-forbidden-patterns.txt`, `report/`(폰트 등), `logback-spring.xml`.
- realtime: `application{,-dev,-demo,-prod}.yml`, `db/migration/realtime/V1~V4 + afterMigrate.sql`, `gate/`(시드 사전), `practice/u5b-*.yml`, `logback-spring.xml`.

## 프런트엔드 구조

- `src/app/` 라우트: `/parent`(홈) · `/parent/login`(+ `callback`, `google` route) · `/parent/dev-login` · `/parent/consent` · `/parent/child`(+ `stop-expressions`) · `/parent/device`(+ `register`) · `/parent/free` · `/parent/practice` · `/parent/sessions/[sessionId]`(+ `done`) · `/parent/records`(+ `[sessionId]`) · `/parent/reports/new` · `/parent/reports/[reportId]` · `/parent/push-worker`(route) · `/device` · `/device/pcm-capture-worklet`(route) · `/healthz`(route).
- `src/parent/`: `screens/`(17 스크린: Login01 · LoginCallback · DevLogin · Consent02 · Child03 · Home04 · Device05 · Register05a · Free08 · Live12 · Done17 · Practice18 · Records26 · RecordDetail27 · ReportRange28 · Report30 · StopExpressions01), `api/` · `activity/` · `practice/` · `push/` · `hooks/` · `components/`.
- `src/device/`: `components/`(D0View · T19View · DebugLog), `machine/`(화면 상태 기계) · `ws/` · `mic/` · `audio/` · `runtime/` · `power/` · `api/`.
- `src/shared/`: `protocol/`(WS 계약 대응) · `timing/`(`timing-defaults.json`) · `copy/`(화면 문구) · `errors/` · `security/` · `styles/` · `toast/`.

## 코드 패턴

- **포트 + 기본 빈**: 외부 AI · 지식 그래프는 인터페이스와 `@ConditionalOnMissingBean` 기본 실패 빈(TD-1).
- **삭제 참여자**: 각 패키지가 `*DeletionParticipant`를 구현해 계정 삭제에 참여.
- **Outbox/Inbox 쌍**: Writer · Sender · Repository · Receiver · Dispatcher · Repoller · CleanupJob · `ControlEventRearm`이 양 앱에 대칭으로 있음.
- **전이 표**: `TransitionTable` · `SessionTransitions` · `TransitionService`로 상태 전이를 표로 관리.
- **주석**: 한국어 Javadoc, 결정 ID(J · BR · U*-J · 편차) 인용.
- **화면 문구**: 프런트 화면 문구는 `shared/copy` 모듈만(ESLint `react/jsx-no-literals`).

---

## U11 집중 갱신 (261016-u11-judgment-turn, 2026-10-16 스캔 — main 병합 때 보존한 절)

> main의 U14 · U15 갱신본을 바탕으로 두고, U11 작업 기록의 집중 스캔에서 갱신한 절만 아래에 보존했다. 줄 번호 · 커밋은 2026-10-16 `9bcc706` 기준이며 PR1(선 형식 정비) 이전 코드다.

## realtime 세션 실행기 내부 지도 (2026-10-16 집중 스캔)

`session/SessionExecutor.java`(3,388줄) 중 U11이 닿는 구간. 줄 번호는 커밋 `9bcc706` 기준.

| 구간 | 줄 | 내용 |
|---|---|---|
| 확장점 신호 · 출력 능력 | L1174 ~ L1556 | `practiceSignal`, `onPracticeEvent`(L1224, `ASYNC_RESULT` 등 처리), 기술 일시정지 중 `pausedPracticeEvents` 적재, 내부 클래스 `PracticeOutputPort`(L1403 ~), `practiceActive()` · `refusePractice` → `PRACTICE_EXTENSION_FAILED{reason, capability}` |
| 공급자 사건 처리 | L1557 ~ L1760 | `refuseHeldResponse`(L1593), `outputHeld()`(L1600), `SpeechStopped`(L1642) → `thinking()`, `InputTranscriptDone`(L1660) → 참여 응답 · 확장점 · 종료 의도(L1676 ~ L1688), `beginOutput`(L1734 ~ L1743, 첫 조각에 `transcripts.aiStart`), `providerLost()`(L1758) |
| 공급자 끊김 · 중단 | L1780 ~ | `abortProviderLost()` |
| 종료 의도 | L1809 ~ L1905 | `judgeStopIntent`(`StopIntentJudge` 결과 STOP · CONFIRM · NONE 처리) |
| 기술 일시정지 | L2046 ~ | `startPause` |
| 생각 중 · 게이트 · 공급자 송신 | L2116 ~ L2375 | `thinking()`(L2135) · `listeningAgain()`(L2142), `sealAndGate`(L2165 ~ L2206), `toProvider`(L2324 ~ L2340) · `enqueuePracticeProvider`(L2342 ~ L2361) |
| 정산 | L1113 | `finishSettle` → `SessionSettlement.capture` |
