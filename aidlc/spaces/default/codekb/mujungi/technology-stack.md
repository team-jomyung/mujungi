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
