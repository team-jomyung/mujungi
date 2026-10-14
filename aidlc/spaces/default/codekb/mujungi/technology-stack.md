# 기술 스택 (Technology Stack)

> 기준 커밋 `3748083`. 판 번호의 출처는 `backend/gradle/libs.versions.toml`(직접 확인)과 개발자 스캔이다. 판 번호가 「BOM」인 항목은 Spring Boot BOM(`spring-boot-dependencies` 4.1.1)이 정한다. 모듈 간 · 외부 의존 관계는 `dependencies.md`에 있다.

## 1. 언어 · 런타임 · 빌드

| 항목 | 판 | 비고 |
|---|---|---|
| Java | 21 | 툴체인 + `--release 21`, 자동 다운로드 끔. 가상 스레드 사용(`spring.threads.virtual.enabled: true`, `TaskLauncher`) |
| Kotlin (Gradle DSL) | Gradle 내장 | `build-logic` 포함 빌드(kotlin-dsl) |
| Gradle | 8.14.3 | wrapper, sha256 고정, `org.gradle.parallel=true`, 버전 카탈로그 |
| 기반 이미지 | `eclipse-temurin:21.0.12_8-jre-noble` | 다이제스트 고정 + openssl .deb 스냅숏 패치 단계. 이미지 안 빌드 없음(bootJar COPY) |
| PostgreSQL | 16 | 운영 1대(`core` · `realtime` 스키마). 시험은 Testcontainers 또는 로컬 바이너리 |

## 2. 운영 라이브러리 (realtime-activity · contract-model)

| 라이브러리 | 판 | 용도 |
|---|---|---|
| Spring Boot | 4.1.1 | 앱 프레임워크 · BOM 플랫폼 |
| spring-boot-starter-webmvc | BOM | 내부 HTTP |
| spring-boot-starter-websocket | BOM | 기기 WSS `/ws/device` |
| spring-boot-starter-data-jpa · jdbc | BOM | 저장소(`ddl-auto: validate`, `default_schema: realtime`) |
| spring-boot-starter-flyway · flyway-core · flyway-database-postgresql | BOM | 앱에서는 꺼짐(`enabled: false`), 이력 표 읽기 · 시험 적용용 |
| spring-boot-starter-actuator · validation | BOM | 관리(127.0.0.1:9090) · 입력 검증 |
| micrometer-registry-prometheus | BOM | 지표(`RealtimeMetrics`), SLO 히스토그램 버킷 설정 |
| PostgreSQL JDBC · HikariCP | BOM | 드라이버 · 풀(`maximum-pool-size: 10`, connection-timeout 2000ms) |
| Jackson 3 (`tools.jackson`) | 3.1.7 (보안 BOM) | JSON. 직접 import는 `kr.mujoong.contract.json` 한 곳 |
| Jackson 2 BOM | 2.22.3 (보안 BOM) | 전이 의존 덮어쓰기 |
| Tomcat embed | 11.0.26 (보안 판 제약) | 서블릿 컨테이너(포트 8081, graceful shutdown) |
| SLF4J · Logback | BOM | 구조화 로그(`StructuredLogFormatter`, `logback-spring.xml`) |
| JDK `java.net.http` (HttpClient · WebSocket) | Java 21 | 내부 HTTP 클라이언트 · 공급자 WSS(SDK 없음) |
| Spring Security | 사용 안 함 | Realtime에는 없음(Core만) |
| springdoc-openapi | 3.1.1 | 카탈로그에만 있음(Core dev · test 전용, Realtime 미사용) |

## 3. 시험 · 품질 도구

| 도구 | 판 | 용도 |
|---|---|---|
| JUnit Jupiter / Platform | BOM | 시험 · 태그(`u1` · `u3` · `db` …) |
| AssertJ · Mockito (+ junit-jupiter) | BOM | 단위 시험 |
| Spring Boot Test (webmvc · data-jpa · jdbc 슬라이스, testcontainers) | BOM | 통합 시험 |
| Testcontainers (postgresql · junit-jupiter) | BOM | DB 시험(`-Pmujoong.test.db=auto\|docker\|local`) |
| ArchUnit | 1.5.1 | 구조 규칙 시험 |
| networknt json-schema-validator | 3.0.6 | 계약 JSON Schema 2020-12 검증 |
| jackson-dataformat-yaml | BOM | 계약 YAML 읽기(시험) |
| Java-WebSocket | 1.6.0 | 가짜 공급자 WS 서버(test-support 전용) |
| JaCoCo | 0.8.14 | 커버리지(LINE ≥ 0.80) |

## 4. 주변 스택 (훑기만)

| 영역 | 기술 | 판 |
|---|---|---|
| 프런트엔드 | Next.js · React · TypeScript · ESLint · Vitest | 16.4.0 · 19.3.0 · 5.9.3 · 9.39.5 · 4.1.11 |
| CI | GitHub Actions(`ci.yml` · `deploy.yml`), Trivy, gitleaks | — |
| 배포 | EC2 1대 Docker Compose, OIDC · SSM 배포 워크플로(현재 저장소 변수 미설정으로 건너뜀) | — |
| 계약 표기 | OpenAPI · AsyncAPI · JSON Schema 2020-12 | — |

## 5. 판 관리 규칙

- 운영 · 시험 의존성은 모두 `libs.versions.toml`에 선언하고 뒤 단계는 이 파일을 고치지 않는다(파일 머리 주석).
- 보안 패치 판(Tomcat · Jackson 3 · Jackson 2)은 Boot BOM보다 높여 덮으며, BOM이 따라잡으면 지운다(CI `backend-deps` Trivy HIGH · CRITICAL).
- Boot 4.1 판정이 실패하면 3.5.x로 내리고 Jackson 좌표를 함께 바꾸는 퇴로가 주석으로 남아 있다(J139).

## Sources
- `backend/gradle/libs.versions.toml` 1 ~ 70행 · `backend/realtime-activity/build.gradle.kts` · `application.yml` 직접 확인
- 개발자 스캔 `developer-scan.md` (Frameworks & Libraries · Build System)
