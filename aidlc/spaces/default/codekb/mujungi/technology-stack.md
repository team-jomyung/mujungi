# Technology Stack

> 기준: 커밋 570a625. 버전은 `backend/gradle/libs.versions.toml`, `frontend/package.json` 등에서 확인한 핸드오프 값이다. 모듈 간 의존 방향은 `dependencies.md`.

## 언어 · 런타임

| 항목 | 버전 · 비고 |
|---|---|
| Java | 21 |
| Kotlin DSL | Gradle 빌드 스크립트(`build-logic`) |
| TypeScript | 5.9.3 |
| JavaScript | `tools`(Node ESM) |
| Node | >=22 <23 |
| Python | `ci/security-gate.py` |
| Bash | `scripts/`, `infra/` |

## 백엔드

| 항목 | 버전 · 용도 |
|---|---|
| Spring Boot | 4.1.1 — webmvc · websocket · security · session-jdbc · data-jpa · jdbc · flyway · actuator · validation |
| Gradle | 8.14 (멀티 프로젝트 + `build-logic`) |
| Jackson 3 (`tools.jackson`) | 3.1.7 BOM — JSON은 한 곳 규칙(J139). Jackson 2 BOM 2.22.3은 보안 패치 덮어쓰기 |
| Flyway | core V1~V10, realtime V1~V7 (다음은 V11 · V8) |
| ArchUnit | 1.5.1 |
| JaCoCo | 0.8.14 |
| json-schema-validator | 3.0.6 |
| Java-WebSocket | 1.6.0 |
| springdoc | 3.1.1 (dev · test) |
| push2u | 0.3.0 |
| PostgreSQL | Testcontainers 시험(`-Pmujoong.test.db=docker`) |

## 프런트

Next.js 16.4.0 · React 19.3.0 · @tanstack/react-query 5.104.0 · Vitest 4.1.11(v8 커버리지) · Testing Library · jsdom 29 · ESLint 9.39.5.

## 시험 · 배포

- 시험: JUnit 5 · AssertJ · Mockito · ArchUnit · Spring Boot Test · Testcontainers / Vitest + Testing Library / Vitest(tools). 상세 수치는 `code-quality-assessment.md`.
- 배포: Docker Compose, Nginx, GitHub Actions(`ci.yml`, `deploy.yml`), GHCR 이미지 6종, demo는 EC2 1대(ap-northeast-2).

## 집중 조사 보강 — 음성 진단 기술

현재 읽은 구현의 기술: 브라우저 getUserMedia·AudioWorklet·Web Audio·WebSocket, PCM16LE/24kHz/모노, JDK WebSocket과 가상 스레드, Gradle 격리 Test 작업, Node ESM 시뮬레이터. 위 패키지 버전 표는 이전 store에서 보존한 값이며 이번 의도에서 의존성 manifest 전체를 다시 확인한 값이 아니다.

한국어 합성 말소리 생성 및 브라우저 자동화 의존성의 실제 설치 여부는 코드만으로 알 수 없다. 이 intent의 inception/reverse-engineering/environment-readiness.md에 별도 환경 조사 결과가 있다. 가상 마이크의 브라우저 플래그 지원, 입력 반복/소진 방식, AudioContext 실제 실행·물리 출력은 실행 단계에서 검증해야 한다.

## 집중 조사 보강 — 261020-service-guardrail-replac

기준: 2026-10-20, 커밋 2e3d7b0. 이전 절은 이전 의도의 기록으로 보존했다. 이번 검증 범위는 timestamp의 개별 파일에 한정한다.

이번 지시문 연결은 Java 템플릿·UTF-8 리소스·Jackson JsonCodec·JDK WebSocket/HttpClient·JUnit/AssertJ를 사용하는 기존 경계에 걸린다. backend/realtime-activity/build.gradle.kts의 Spring Boot 의존 및 격리 gptLiveTest/jevLiveTest 선언을 읽었다. 패키지 버전 manifest 전체는 다시 확인하지 않았으므로 위 버전 표를 최신으로 재인증하지 않는다. ZIP은 TXT/JSON/HTML 6파일이며 새 런타임 의존성이나 SDK 설치 지시가 아니다.

