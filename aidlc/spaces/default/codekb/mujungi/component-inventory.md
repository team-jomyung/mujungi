# Component Inventory

> 기준: 커밋 570a625. 컴포넌트 이름은 `reverse-engineering-timestamp.md`의 `analyzed.components`와 글자 그대로 같다. 의존 관계의 상세는 `dependencies.md`, 파일 수 · 구조는 `code-structure.md`.

## backend/contract-model
- 종류: Gradle 라이브러리(Java 21). 운영 55 · 시험 18파일.
- 책임: 두 앱이 공유하는 계약 · 오류(`ErrorCode`) · 시간(`MujoongClock`, `DelayQueueTimerDriver`) · 타입.

## backend/core-api
- 종류: Spring Boot 앱(Java). 운영 342 · 시험 247파일(IT 90).
- 책임: 계정 · 아이 · 동의 · 기기 · 활동 · 결과 · 리포트 · 알림 · 지식 · luna. 보호자 REST와 내부 HTTP 수신.
- 의존: contract-model. realtime-activity와 직접 참조 금지.

## backend/realtime-activity
- 종류: Spring Boot 앱(Java). 운영 261 · 시험 244파일(IT 52).
- 책임: 세션 실행(`session`, `SessionExecutor`), 음성(`voice`), 출력 게이트(`gate`), 가드레일(`guardrail`), 판정(`judgment`), 연습(레거시 `practice` · 새 흐름 `practiceflow`), 기기 게이트웨이(`gateway`), 메시징, luna, 기동(`startup`).
- 의존: contract-model. core-api와 직접 참조 금지.

## backend/test-support
- 종류: 시험 전용 모듈. Testcontainers PostgreSQL · 가짜 공급자 서버(`MujoongPostgres.java` 971줄).
- 운영 jar에 들어가지 않는다(`verifyBootJarHasNoTestClasses`, R-F8).

## backend/build-logic
- 종류: Gradle 포함 빌드(Kotlin DSL). `mujoong.java-conventions`: Xlint, 태그 포함 · 제외, `-PcoverageScope`, JaCoCo.

## frontend
- 종류: Next.js 앱 한 개(TypeScript). `/parent`(보호자 웹) · `/device`(기기 웹) · `shared`. `src` 134파일 11,437줄.
- 백엔드와는 `contracts/`로만 만난다.

## tools
- 종류: Node ESM(JavaScript). 기기 시뮬레이터 · 가짜 공급자 · dev-edge · skeleton-e2e · 시드 도구. 시험 12파일 126개.

## infra
- 종류: 배포 구성(Compose · Nginx · 셸 · AWS · Terraform 디렉터리). 운영 / 검증 / dev / live(`compose.live.yml`) 구성.

## scripts
- 종류: 셸 · Java. `verify.sh`(+`verify/*.sh`, `HostMigrate.java`), `cloud-setup.sh`, `scripts/live/{up,down,keys,jev-probe}.sh`(실키 전체 기동, 프로젝트 `mujoong-live`).

## contracts
- 종류: 문서 · OpenAPI/AsyncAPI. 접점 목록은 `api-documentation.md`.

## ci
- 종류: Python. `security-gate.py` + `security-exceptions.yml`(R-M7).

## 주요 내부 구성요소(참고, 컴포넌트 아님)
`SessionExecutor`(4,361줄), `GuardrailCoordinator`(759), `JudgmentCoordinator`, `PracticeConversation`(862), `PracticeSessionExtension`의 책임과 구역은 `code-structure.md`에 있다.

## 집중 조사 보강 — 2026-10-20

아래 이름은 위 기존 컴포넌트의 하위 구성요소다. 이번 깊은 검증은 timestamp의 파일 목록으로 한정한다.

| 기존 컴포넌트 | 이번 확인 책임 |
|---|---|
| backend/realtime-activity | JoinFlow가 참여 수락을 ACTIVE로 전이. SessionExecutor가 전사를 묶고 응답을 봉인·검사·전송. ProviderClient가 공급자 이벤트를 매핑. LatencyRecorder가 시각·미관측 사유 기록 |
| backend/core-api | ActivityController 자유대화 시작 HTTP 경계 확인. 상세 시작 서비스·보호자 세션 보안은 이번 깊은 스캔 제외 |
| backend/contract-model | 음성 바이너리 규격과 PlaybackMark 계약 |
| frontend | 실제 마이크 캡처·resample·프레임 송신·Web Audio와 부모 폴링/DOM |
| tools | DeviceSimulator는 톤 입력과 모의 재생, seed 두 생성기는 말소리 없는 톤/무음 WAV |
| infra | compose.live.yml이 실제 공급자·Luna와 로컬 9443 환경 연결 |
| scripts | scripts/live는 존재와 가이드만 확인; 내부를 깊게 검증하지 않아 이번 analyzed.components에서 제외 |

추가 구현이 필요할 후보는 **새 진단 도구**(합성 말소리 생성/준비, 실제 브라우저 자동화, 안전한 단계별 관측 보고)다. 아직 생성하지 않았고 운영 컴포넌트로 등록하지 않는다.

## 집중 조사 보강 — 261020-service-guardrail-replac

기준: 2026-10-20, 커밋 2e3d7b0. 이전 절은 이전 의도의 기록으로 보존했다. 이번 검증 범위는 timestamp의 개별 파일에 한정한다.

이번 깊은 분석 컴포넌트는 **backend/realtime-activity** 하나다. 기존 다른 컴포넌트의 본문은 보존하되 깊은 검증으로 승계하지 않았다.

- dialog: 기본 지시와 후속 지시의 정책 일관성 경계.
- voice: 최소 아이 문맥과 공급자 요청 whitelist.
- session: 승인 안내·참여·직렬 지시·검사표 귀속.
- gate/content/guardrail: 전사-음성 완전성, 고정 정책 적재, JEV 검사 및 fail-closed.
- ZIP은 외부 사용자 입력 자료이며 실행 컴포넌트가 아니다. 새 어댑터나 연습 엔진이 패키지에 구현돼 있지 않다.

