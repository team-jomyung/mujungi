# 기술 스택 (Technology Stack)

> 기준(혼합): **이번 갱신** = 커밋 `9bcc706`(= main `7e30ee8` + U11 지시서), 2026-10-16 집중 스캔(인텐트 `261016-u11-judgment-turn`). realtime-activity에 걸친 행(「(2026-10-16 갱신)」 표시)만 갱신했다.
> **그 밖의 행** = 커밋 `72d7034`(2026-10-15 전체 재스캔) 그대로이며 판 번호는 이번에 다시 확인하지 않았다(STALE). 판 번호는 `backend/gradle/libs.versions.toml` · `frontend/package.json` · `tools/package.json` · Compose 이미지 태그 기준. 라이브러리가 어디서 왜 쓰이는지(의존 관계)는 [dependencies.md](dependencies.md)를 본다.

## 언어 · 런타임

| 항목 | 판 | 비고 |
|---|---|---|
| Java | 21 | 툴체인 · `options.release=21`, 가상 스레드 켬(`spring.threads.virtual`). realtime 실행기 보조 작업(공급자 · 기기 송신)도 가상 스레드 |
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
| (2026-10-16 갱신) realtime `processResources` | `contracts/schemas/ai-*.schema.json` · `common.schema.json`을 실행 자원 `luna/schemas/`로 복사 |
| (2026-10-16 갱신) `lunaLiveTest` | 태그 `luna-live` 실호출 시험 작업, `check` 그래프 밖 · 루트 `verifyLunaLiveIsolation`이 격리를 강제(편차 U9-c) |

## 백엔드 프레임워크 · 라이브러리

| 이름 | 판 |
|---|---|
| Spring Boot | 4.1.1 (webmvc · websocket(realtime) · security + session-jdbc(core) · data-jpa · jdbc · flyway · actuator · validation) |
| Jackson 3 (`tools.jackson`) | BOM 3.1.7(보안 상향). 직접 사용은 `JsonCodec` 한 곳(J139) |
| Jackson 2 | BOM 2.22.3(보안 상향, 전이) |
| Tomcat embed | 11.0.26(보안 제약 상향) |
| Flyway | Boot BOM |
| PostgreSQL JDBC | Boot BOM |
| Micrometer Prometheus | Boot BOM |
| springdoc-openapi | 3.1.1 (dev · test만) |
| openhtmltopdf-pdfbox | 1.0.10 |
| push2u-core | 0.3.0 |
| Apache HttpClient5 | Boot BOM |
| JDK `java.net.http.WebSocket` | JDK 21 (음성 공급자 연결, SDK 없음, J110) |
| (2026-10-16 갱신) JDK `java.net.http.HttpClient` | JDK 21 (realtime Luna 호출 `LunaTransport`, SDK 없음, 편차 U9-a) |
| (2026-10-16 갱신) networknt json-schema-validator | 3.0.6 — realtime **실행 범위**(Luna 요청 · 출력 스키마 검증, U9 Q3-A). 72d7034 기준에는 시험 범위로만 적혀 있었다 |

## 백엔드 시험

| 이름 | 판 |
|---|---|
| JUnit 5 · AssertJ · Mockito · Spring Boot Test | Boot BOM |
| Testcontainers PostgreSQL | Boot BOM |
| ArchUnit | 1.5.1 |
| networknt json-schema-validator | 3.0.6 |
| Java-WebSocket | 1.6.0 (가짜 공급자 WS 서버) |
| JaCoCo | 0.8.14 |
| jackson-dataformat-yaml | Boot BOM (설정 대조 시험 `ConfigKeyContractTest`용, 2026-10-16 확인) |

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
| 음성 AI(`wss://api.openai.com/v1/live/sessions`, 모델 `gpt-live-1`) | 실시간 대화 | 연결 코드 있음, 선 형식 자체 정의(TD-2). 응답 생성 확인 사건 없음(U11-R1) |
| (2026-10-16 갱신) Luna 판정(`POST https://api.openai.com/v1/responses`, 모델 `${OPENAI_JUDGE_MODEL:gpt-6-luna}`) | 진행 중 판정 `JUDGMENT_LIVE` | realtime 호출부 있음(U9), 키 `OPENAI_API_KEY` 있을 때만 빈. 판정 턴에서 부르는 코드 없음 |
| 텍스트 AI | 상황 · 결과 · 리포트 문장 | 72d7034 기준 포트만, 운영 어댑터 없음(TD-1). U9 `CoreLunaClient`는 미재확인 |
| Google OAuth | 보호자 로그인 | 구현됨 |
| Web Push(VAPID) | 알림 | 구현됨 |
| AWS(EC2 · SSM · GHCR) | demo 배포 | 워크플로 있음, 변수 미설정으로 배포 단계 건너뜀 |
