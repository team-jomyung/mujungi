# 기술 스택 (Technology Stack)

> 기준 커밋 `7a22b67`. 판 출처: `backend/gradle/libs.versions.toml` · `frontend/package.json` · `tools/package.json` · Dockerfile. 모듈 사이 의존은 `dependencies.md`.

## 언어 · 런타임

| 항목 | 판 | 비고 |
|---|---|---|
| Java | 21 (toolchain, `options.release=21`) | 백엔드 전부 |
| Node.js | 22 (`.nvmrc`) | frontend · tools |
| TypeScript | 5.9.3 | frontend |
| Kotlin DSL | Gradle 8.14.3 wrapper | 빌드 |
| 앱 기반 이미지 | `eclipse-temurin:21.0.12_8-jre-noble` (digest 고정) | core-api · realtime-activity |
| DB | PostgreSQL 16 | 스키마 `core` · `realtime` |

## 백엔드 프레임워크 · 라이브러리

| 이름 | 판 | 용도 |
|---|---|---|
| Spring Boot | 4.1.1 | webmvc · websocket(realtime) · security(core) · session-jdbc(core) · data-jpa · jdbc · flyway · actuator · validation |
| Jackson | 3.1.7 (`tools.jackson`, 보안 판으로 BOM 덮음) · 2.22.3 (com.fasterxml, 전이) | JSON — `contract-model` `JsonCodec` 한 곳 |
| Tomcat embed | 11.0.26 (보안 판 제약) | 서블릿 컨테이너 |
| Flyway | Boot BOM 판 + `flyway-database-postgresql` | migrate 컨테이너가 적용 |
| PostgreSQL JDBC · HikariCP | BOM 판 | DB 접속 |
| Micrometer Prometheus | BOM 판 | 지표 |
| springdoc-openapi | 3.1.1 | core dev · test 프로필 전용 |

## 시험 · 품질

| 이름 | 판 | 용도 |
|---|---|---|
| JUnit Jupiter · AssertJ · Mockito | BOM 판 | 단위 시험 |
| Spring Boot Test | BOM 판 | webmvc · security · data-jpa · jdbc 슬라이스 |
| Testcontainers PostgreSQL | BOM 판 | DB 통합(`-Pmujoong.test.db` = docker · local · auto) |
| ArchUnit | 1.5.1 | 구조 규칙(`CoreArchTest`) |
| networknt json-schema-validator | 3.0.6 | `contracts/schemas` 2020-12 대조 |
| Java-WebSocket | 1.6.0 | test-support 가짜 공급자 서버 |
| JaCoCo | 0.8.14 | 줄 커버리지 80% |

## 프런트 · 도구

| 이름 | 판 | 용도 |
|---|---|---|
| Next.js | 16.4.0 | App Router 한 앱(`/parent` · `/device`) |
| React | 19.3.0 | UI |
| @tanstack/react-query | 5.104.0 | 서버 상태 |
| ESLint | 9.39.5 (eslint-config-next 16.4.0) | 영역 경계 · 문구 규칙 |
| Prettier | (`.prettierrc.json`, printWidth 100) | 포매터 |
| Vitest | 4.1.11 (@vitest/coverage-v8) | 시험 · 커버리지 |
| jsdom · Testing Library | 29.1.1 · — | DOM 시험 |
| ws | 8.21.3 | tools 가짜 공급자 · 시뮬레이터 |

## 배포 · 운영

Docker 다단계 빌드(이미지 6종 + 가짜 공급자), Docker Compose(`infra/compose.{demo,dev,ci}.yml`), Nginx, 호스트 bash 도구, AWS IAM/SSM, GitHub Actions(`ci.yml` · `deploy.yml`), Trivy · gitleaks · npm audit · `ci/security-gate.py`.

## U6 관련 공백

- **PDF 생성 라이브러리 · 한글 글꼴: 없음.** 카탈로그 · Dockerfile 어디에도 없다(정본 OQ6 미결). 고를 때 이미지 크기 · 라이선스 · `read_only` 루트 · `/tmp` 64m · 메모리 1.6g 제약(`component-inventory.md` infra.migrate)을 함께 본다.
- 실제 텍스트 AI 공급자 SDK 없음 — 지시서도 U6에서 연결하지 않는다(기본 UNAVAILABLE).

## Sources

- 개발자 스캔 `.../developer-scan.md`(Frameworks & Libraries · Build System)
