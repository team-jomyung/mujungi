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
