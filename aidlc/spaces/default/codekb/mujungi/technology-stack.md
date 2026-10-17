# 기술 스택 (Technology Stack)

> 최신 확인: 2026-10-16, HEAD 7e30ee8, U15 집중 분석. 아래 최신 절이 U15 관련 과거 설명보다 우선한다. 이전 분석 본문은 이력으로 보존하며 이번 범위 밖의 검증 주장은 shallow로 낮췄다.

## U15 검증 기술 스택

이번 파일 검증: Java 21, Gradle wrapper 8.14.3, Spring Boot 4.1.1, JaCoCo 0.8.14, ArchUnit 1.5.1, JSON Schema validator 3.0.6. Jackson 3는 JsonCodec 경계를 사용한다. Neo4j Java 드라이버와 Neo4j Testcontainers 의존은 아직 없다. Compose의 Neo4j 5.26.31-community 표기는 부분 확인이며 실제 U15 서버 통합 검증은 미실행이다.

## 이전 분석 기록 — 2026-10-15, 현재 범위 밖은 재검증하지 않음

> 기준: 커밋 `72d7034`, 2026-10-15 전체 재스캔. 판 번호는 `backend/gradle/libs.versions.toml` · `frontend/package.json` · `tools/package.json` · Compose 이미지 태그 기준. 라이브러리가 어디서 왜 쓰이는지(의존 관계)는 [dependencies.md](dependencies.md)를 본다.

## 언어 · 런타임

| 항목 | 판 | 비고 |
|---|---|---|
| Java | 21 | 툴체인 · `options.release=21`, 가상 스레드 켬(`spring.threads.virtual`) |
| Kotlin DSL | Gradle 내장 | `build-logic` 규약 플러그인 |
| TypeScript | 5.9.3 | 프런트 |
| JavaScript(ESM) | Node 22(`.nvmrc`) | tools |
| Bash | — | infra · scripts |
| Python | 3 | `ci/security-gate.py` |
| SQL | PostgreSQL 16 | Flyway 마이그레이션 |

## 빌드

| 도구 | 판 |
|---|---|
| Gradle | 8.14.3(wrapper), 버전 카탈로그, 포함 빌드 |
| npm | Node 22, `frontend` · `tools` 각각 package-lock |
| Docker | 이미지 6종(core-api · realtime-activity · frontend · migrate · backup · bundle) + 가짜 공급자 |

## 백엔드 프레임워크 · 라이브러리

| 이름 | 판 |
|---|---|
| Spring Boot | 4.1.1 (webmvc · websocket(realtime) · security + session-jdbc(core) · data-jpa · jdbc · flyway · actuator · validation) |
| Jackson 3 (`tools.jackson`) | BOM 3.1.7(보안 상향) |
| Jackson 2 | BOM 2.22.3(보안 상향, 전이) |
| Tomcat embed | 11.0.26(보안 제약 상향) |
| Flyway | Boot BOM |
| PostgreSQL JDBC | Boot BOM |
| Micrometer Prometheus | Boot BOM |
| springdoc-openapi | 3.1.1 (dev · test만) |
| openhtmltopdf-pdfbox | 1.0.10 |
| push2u-core | 0.3.0 |
| Apache HttpClient5 | Boot BOM |
| JDK `java.net.http.WebSocket` | JDK 21 (공급자 연결, SDK 없음) |

## 백엔드 시험

| 이름 | 판 |
|---|---|
| JUnit 5 · AssertJ · Mockito · Spring Boot Test | Boot BOM |
| Testcontainers PostgreSQL | Boot BOM |
| ArchUnit | 1.5.1 |
| networknt json-schema-validator | 3.0.6 |
| Java-WebSocket | 1.6.0 (가짜 공급자 WS 서버) |
| JaCoCo | 0.8.14 |

## 프런트엔드

| 이름 | 판 |
|---|---|
| Next.js | 16.4.0 (App Router, standalone) |
| React / React DOM | 19.3.0 |
| @tanstack/react-query | 5.104.0 |
| Vitest / @vitest/coverage-v8 | 4.1.11 |
| @testing-library/react · dom · user-event · jest-dom | 16.3.3 · 10.4.2 · 14.6.7 · 6.10.0 |
| jsdom | 29.1.1 |
| ESLint / eslint-config-next | 9.39.5 / 16.4.0 |

## tools

| 이름 | 판 |
|---|---|
| ws | 8.21.3 |
| Vitest | 4.1.11 |

## 인프라 · 운영

| 이름 | 판 |
|---|---|
| PostgreSQL 서버 | 16.15 |
| Nginx | 1.30.5-alpine |
| certbot | v5.8.0 |
| neo4j | 5.26.31-community (Compose 프로필 knowledge, 앱 어댑터 없음) |
| Docker Compose | dev · ci · demo 파일 |
| GitHub Actions | 액션 커밋 SHA 고정, OIDC → AWS SSM |
| Trivy · npm audit · gitleaks · shellcheck | CI 보안 · 정적 점검 |

## 외부 서비스

| 서비스 | 용도 | 상태 |
|---|---|---|
| 음성 AI(`wss://api.openai.com/v1/live/sessions`) | 실시간 대화 | 연결 코드 있음, 선 형식 자체 정의(TD-2) |
| 텍스트 AI | 상황 · 결과 · 리포트 문장 | 포트만, 운영 어댑터 없음(TD-1) |
| Google OAuth | 보호자 로그인 | 구현됨 |
| Web Push(VAPID) | 알림 | 구현됨 |
| AWS(EC2 · SSM · GHCR) | demo 배포 | 워크플로 있음, 변수 미설정으로 배포 단계 건너뜀 |
