# 기술 스택 결정 — U1 u1-walking-skeleton

> 단계: NFR Requirements (3.2) · 문서: tech-stack-decisions · 깊이 Standard · 날짜 2026-10-09 (클라우드 세션)
> 입력: `nfr-requirements-questions.md`(Q6 · Q7 · Q9 · Q10 · Q11), `functional-design/{functional-spec,rules,entities}.md`, `requirements.md`(NFR10 · NFR11 · 제약), `contract-summary.md`(4 · 8절), ADR-002 · ADR-006 · ADR-008.
> 대리 판단: J61 ~ J72는 사람 지시와 `docs/cloud_handoff.md` 2절에 따라 팀장(준하)의 대리가 정한 가정이다. 3.2 재점검에서 정한 결정은 5절에 RD-n으로 적었다(RD 번호는 재점검 지시의 결정 번호와 같다). 버전과 도구는 시작값이며 3.5 첫 볼트의 호환성 확인 결과로 이 문서의 해당 행만 고친다.
> 표기: `[Q6]`은 요구사항 문서의 시연 네 경로이고, 번호만 쓴 Q1 ~ Q12는 같은 폴더 질문 파일의 질문이다.

## 1. 요구사항

| ID | 우선순위 | 요구 | 측정 · 완료 기준 | 근거 |
|---|---|---|---|---|
| NFR10.1 | Must | /device는 데스크톱 · 노트북 Chrome 최신 2개 버전에서 D0 · T19 전 흐름이 동작한다(시연 기준). Android 태블릿 Chrome은 되면 좋음, iPad Safari는 범위 밖. | 시연 기준 기기에서 요구사항 [Q6] 네 경로 중 U1 구간 통과 | NFR10 · J71 |
| NFR10.2 | Must | /parent는 Android Chrome 최신 2개 버전(통합시험 기기)에서 04 · 05a · 08 · 12 · 17이 동작한다. | Android Chrome 통합시험 | NFR10 · J71 |
| NFR10.3 | Must | iOS Safari 16.4 이상에서 U1 보호자 화면이 깨지지 않고 렌더된다(푸시 · 공유는 보장하지 않음). | U1 화면 렌더 확인 | NFR10 · J71 |
| NFR10.4 | Must | 마이크는 HTTPS와 사용자 제스처(「켜기」) 뒤에만 요청한다. PWA 설치 · 서비스 워커는 쓰지 않는다. | 「켜기」 전 마이크 요청 0, 거부 시 BR9.5 화면 | NFR10 · BR9.5 |
| NFR11.1 | Could | /parent 코드는 기기 접점(WSS 프로토콜 · /device 화면)을 직접 참조하지 않고 Core REST의 DeviceView · 세션 응답만 쓴다. 기기 접점이 바뀌어도 /parent 코드 변경이 없다. | import 경계 검사(ESLint import 제한)로 /parent → /device 의존 0 | NFR11 · BR4.6 |

## 2. 배경과 해석

### 2.1 요구별 뜻

| ID | U1에서의 뜻 | 걸리는 곳 | 후속 유닛 몫 |
|---|---|---|---|
| NFR10.1 | 시험일의 Chrome 안정판과 그 직전 안정판 둘에서 D0 → 등록 → T19 켜기 → 자유대화 한 턴 → 끝내기가 돈다(요구사항 `[Q6]` 네 경로 중 U1 구간). 마이크 · Web Audio · WebSocket · 화면 켜짐 유지(BR9.5)가 Chrome에 있다는 전제다. 시험 기록에 실제 버전 번호를 적는다. | BR9.1 ~ BR9.7 | U3: T19 완성 후 같은 기준 |
| NFR10.2 | U1이 그리는 보호자 화면(04 기기 구역 · 05a · 08 · 12 · 17, P1)이 통합시험 기기(10/29)의 Android Chrome 최신 2개 버전에서 동작한다. | BR2.8 · BR4.5 · BR4.6 · BR6.4 · BR6.9 · BR6.15 | U2 ~ U7: 자기 화면을 같은 기기에서 |
| NFR10.3 | 동작 완전성이 아니라 렌더 확인이다. 기준선 16.4는 시작값이다(J71). | BR4.6 · BR6.15 | 각 유닛: 자기 화면 렌더 확인(01 ~ 30) |
| NFR10.4 | 마이크는 페이지 로드 · 등록 완료 · 재접속 어디서도 자동으로 요청하지 않는다(등록 직후도 꺼짐으로 착지, J56). 거부하면 BR9.5 화면을 보이고 연결하지 않는다. U1은 서비스 워커를 등록하지 않는다. /parent의 푸시용 서비스 워커는 U7이 더할 때 이 요구의 예외를 따로 정한다(5절). | BR9.1 ~ BR9.5 | U7: 푸시용 서비스 워커 |
| NFR11.1 | /parent는 Core REST의 DeviceView(등록 줄 · 네트워크 줄 · 판정, BR4.6)와 세션 응답만 쓴다. WSS 메시지 타입 · /device 화면 · 연결 상태 계산 코드를 import하지 않는다. 경로 구성은 3.5가 정하고 규칙은 '/parent 하위가 /device 하위를 import하지 않음'이다. | BR4.6 · BR4.3 · BR6.15 | 후속: 인형 등 새 기기 접점이 생겨도 /parent 변경 없음 |

### 2.2 기술 선택

| 영역 | 선택 | 근거 | 대안 · 전환 조건 |
|---|---|---|---|
| 백엔드 런타임 · 프레임워크 | Java 21 LTS · Spring Boot 4.1.x · Jackson 3 | ADR-002 기본 후보(Q10). Boot 4.1 채택 전제: Java 21, Jackson 3, 모의 객체는 @MockitoBean | 확정 조건: 첫 볼트에서 두 앱의 빌드 · 스캔 · 기동과 Spring Security · Session JDBC · springdoc · Testcontainers · Flyway · JPA/Hibernate 동작 확인, 3.x 예제는 4.1 호환성 확인 뒤에만 적용. 전환: 큰 호환성 문제가 있으면 Spring Boot 3.5.x로(Jackson 기준이 바뀌므로 직렬화 시험 재실행) |
| 빌드 | Gradle 8.14+ 멀티 프로젝트: core-api · realtime-activity · 공통 계약 | ADR-002: 두 실행 · 빌드 단위. 공통 라이브러리는 계약 · 오류 · 시간 · 타입만 공유하고 Entity · Repository · 도메인 Service · Boot 설정은 공유하지 않는다 | 확정 조건: 8.14.x와 9.x 중 사용할 버전을 빌드 환경에서 확정(ADR-002). 프로젝트 이름은 Q10 값을 시작값으로 쓴다 |
| 데이터베이스 | PostgreSQL 16, 물리 1개에 core · realtime 스키마 | Q10: 호환성 문제가 가장 적은 안정 버전. BR8.1 | 전환: 호환성 문제가 발견되면 다른 주 버전으로(이미지 태그로 고정, 3.4) |
| 스키마 변경 | Flyway(JPA ddl-auto는 validate) | ADR-002 기존 채택. 앱은 validate만, 적용은 배포 단계의 별도 마이그레이션 역할(BR8.1 · BR8.2) | 확정 조건: 적용 주체 · 위치 · 이력 설정은 3.4. 두 앱이 시작하며 경쟁 적용하는 방식은 쓰지 않는다 |
| 보호자 인증 · 세션 | Spring Security + Spring Session JDBC (MUJOONG_SESSION) | ADR-002 · ADR-008, NFR9.2. U1은 dev 시험 로그인으로 같은 세션 계층을 쓰고 구글 로그인은 U2 | JWT · Auth0 · Keycloak은 미채택(ADR-002) |
| API 문서 | springdoc(OpenAPI) | ADR-002. `/v3/api-docs`가 `contracts/` 정본과 어긋나는지 계약 시험에서 비교(방식은 3.3) | 확정 조건: Boot 4.1 호환(첫 볼트). OpenAPI → TypeScript 생성은 계약이 안정된 뒤 단계 도입이라 U1에는 두지 않는다 |
| 프런트엔드 | Node.js 22 LTS · Next.js App Router · React · TypeScript, 한 앱에 /parent · /device | Q10, ADR-002 · ADR-003, 화면 설계 | 전환: 프런트 2앱 분리는 ADR-003이 별도 결정으로 남겨 두었고 U1은 따르지 않는다. 확정 조건: Next.js · React 버전은 3.5 첫 볼트에서 Node 22와 맞는 안정판으로 고정하고, 그 버전의 지원 브라우저 하한이 NFR10.3(Safari 16.4)과 맞는지 확인 |
| /parent 서버 상태 | TanStack Query v5 | ADR-002 권고(12 폴링 · 재시도). /device의 WSS를 대신하지 않는다 | 확정 조건: 폴링 중단 · 재시도 · 백그라운드 동작을 실제 화면에서 확인 |
| 프런트 시험 · 품질 | Vitest · ESLint · Prettier | Q10, ADR-002. ESLint는 NFR11.1 경계 검사도 맡는다 | 설정 파일은 저장소 루트(org.md 코드 스타일) |
| 백엔드 시험 | JUnit 5 · Testcontainers(PostgreSQL) · ArchUnit | Q9, ADR-002. ArchUnit은 앱 사이 스키마 · 패키지 의존 금지(BR8.1)를 확인하며 그 시험은 NFR9.7이고 security 문서가 소유한다 | CI에 Docker가 필요하다. 시간 시험은 Clock 주입(NFR6.4) |
| 실행 · 배포 | Docker Compose, EC2 1대. U1 컨테이너: Nginx · Next.js · Core · Realtime · PostgreSQL와 인증서 갱신 작업 | ADR-006, K1. 마이그레이션은 일회성 작업이지 세 번째 상시 서버가 아니다 | Kubernetes는 채택하지 않고 자동 CD는 필수 범위가 아니다(ADR-006). 컨테이너 구성은 3.4 |
| 프록시 · 인증서 | Nginx(HTTPS · WSS 업그레이드), Let's Encrypt certbot 자동 갱신 | NFR9.1, 질문 파일 Q6 · Q10, BR8.9 | 확정 조건: 서비스 도메인과 허용 Origin 값(functional-spec 9절). 도메인 확보 전에는 인증서 방식을 확정하지 않는다(3.4) |
| 공급자 연결 | 표준 WebSocket 클라이언트로 시작(SDK 미사용), 공급자 어댑터 안에 격리 | ADR-002: SDK의 GPT-Live 주 WebSocket 지원은 미확정이라 가정하지 않는다 | 확정 조건: 공급자 Java SDK의 지원 범위(연결 · 이벤트 · 취소 · 교정)를 3.5에서 확인. 지원이 확인되면 어댑터 안쪽만 바꿔 전환하고 SDK 타입은 어댑터 밖으로 내보내지 않는다 |
| 관측 | Micrometer + Actuator(내부 엔드포인트), Logback JSON 인코더 | Q7 · J67, NFR9.15. 인코더 구현체는 3.5 | OpenTelemetry 수집기 · ELK · Loki는 두지 않음. 필요해지면 후속에서 |
| CI | GitHub Actions: 두 앱 build · test · lint · ArchUnit, 프런트 lint · test, 저장소 비밀 스캔(gitleaks류, 이력 포함), Gradle 의존성 검사, npm audit(high 이상, 예외는 사유 · 만료일을 적은 목록) | ADR-002, NFR9.10 · NFR9.13 | 실제 유료 AI API는 호출하지 않고 가짜 어댑터를 쓴다 |
| Neo4j | U1에서 쓰지 않음 | 팀이 채택했으나(ADR-002) 화용 지식 조회는 U1 범위 밖 | U1의 컨테이너 · 의존성 · 스키마 · 시험에 넣지 않는다. 쓰는 유닛이 자기 3.2 · 3.4에서 정한다 |
| Redis | U1에서 쓰지 않음(MVP 보류) | ADR-002: AI Cache를 도입하지 않는다. 소켓 레지스트리 · 시계 · 속도 제한은 앱 메모리, 세션 · 이벤트는 PostgreSQL | 재검토: AI 요청량 · 적중률 · 지연을 실측한 뒤 별도 결정. 수평 확장(NFR7.4)이 필요해져도 별도 결정 |

## 3. 수치와 설정 키

| 항목 | 값 | 출처 | 설정 키 |
|---|---|---|---|
| 지원 브라우저 | Chrome 최신 2개 버전(/device 데스크톱, /parent Android) | NFR10 · J71 | 코드가 읽지 않음(시험 기록에 버전 번호) |
| iOS Safari 하한 | 16.4 | NFR10 · J71 | 프런트 빌드 대상 브라우저 목록(3.5 확정) |
| Java | 21 LTS | J70 | Gradle 자바 툴체인(3.5) |
| Spring Boot | 4.1.x(전환 시 3.5.x) | J70 · ADR-002 | Gradle 플러그인 버전 |
| Gradle | 8.14+ | J70 · ADR-002 | Gradle wrapper 설정 |
| PostgreSQL | 16 | J70 | Compose 이미지 태그(3.4) |
| Node.js | 22 LTS | J70 | 프런트 `engines` 필드 · 이미지 태그 |
| TanStack Query | v5 | J70 · ADR-002 | 프런트 의존성 목록 |
| 마이크 요청 시점 | 「켜기」 클릭 뒤 | NFR10.4 · BR9.5 | 코드 규칙(설정 없음) |
| 허용되는 서비스 워커 | U1은 0개 | NFR10.4 · J71 | 코드 규칙(설정 없음) |

## 4. 시험 · 검증 방법

| ID | 시험 | 단계 |
|---|---|---|
| NFR10.1 | 시연 기준 기기의 Chrome 최신 2개 버전 각각에서 D0 코드 → 등록 → T19 켜기 → 자유대화 한 턴 → 끝내기 통과, 실제 버전 번호 기록 | Build and Test |
| NFR10.2 | 통합시험 기기의 Android Chrome 최신 2개 버전에서 04 · 05a · 08 · 12 · 17 통과(10/29 통합시험) | Build and Test |
| NFR10.3 | iOS Safari 16.4 이상(실기기 또는 시뮬레이터)에서 U1 /parent 화면이 깨지지 않고 렌더되는지 확인. 푸시 · 공유는 제외 | Build and Test |
| NFR10.4 | 「켜기」 클릭 전(D0 → T19 착지 · 새로고침 · 재접속 포함) getUserMedia 호출이 0건이고, 거부를 모킹하면 BR9.5 화면이 나오며 WSS 연결이 0건인 화면 시험 | 3.5 구현 |
| NFR10.4 | 번들 · 소스 검색에서 서비스 워커 등록 코드와 PWA 매니페스트가 없다 | 3.5 구현 |
| NFR11.1 | ESLint import 제한으로 /parent → /device import 0건. 위반 샘플이 실제로 lint 실패하는지 규칙 시험, CI에서 실행 | 3.5 구현 |

확정 전 확인 세 건(Boot 4.1 호환성 · Gradle 버전 · 공급자 SDK 지원)은 3.5 첫 볼트에서 확인하고, 결과를 2.2절의 해당 행에 적는다. 실패하면 그 행의 전환 조건대로 간다.

## 5. 열린 질문과 가정

| 번호 | 내용 | 정할 곳 |
|---|---|---|
| J70 | 백엔드 · 프런트 스택은 Q10의 값이 시작값이다. Boot 4.1.x 검증 실패 시 3.5.x로 전환하고, PostgreSQL 16은 호환성 문제가 가장 적은 안정 버전으로 고른 시작값이다 | 3.5 첫 볼트 |
| J71 | 브라우저 범위: /device 데스크톱 Chrome 최신 2개, /parent Android Chrome 최신 2개, iOS Safari 16.4+는 렌더만 확인한다. 16.4는 시작값이다 | 11/2 측정 뒤 팀 |
| J66 | 인증서는 Let's Encrypt certbot 자동 갱신을 시작값으로 둔다 | 3.4 |
| J67 | 관측은 Micrometer + Actuator + JSON 로그로 시작한다. 인코더 구현체는 3.5가 고른다 | 3.5 |
| J69 | JUnit 5 · Testcontainers · ArchUnit · Vitest는 시작값이다. 도구 이름은 3.5에서 확정한다 | 3.5 |
| RD-14 | 정정: NFR11.1 시험에서 NFR9.7 시험(ArchUnit)을 뺐고 경계 검사 도구를 ESLint import 제한으로 고쳤다. CI 행에 비밀 스캔 · Gradle 의존성 검사 · npm audit(high 이상 + 예외 목록)을 더했다. `Q6` 표기는 요구사항 [Q6] 네 경로 중 U1 구간과 질문 파일 Q6로 풀어 썼다 | 확정 |
| RD-1 | FD 보강 후보 A1 ~ A6은 reliability · security · performance · scalability 문서 5절에 있고 이 문서의 스택 선택에는 영향이 없다 | 3.5 |
| 열림 1 | 서비스 도메인과 인증서 방식(허용 Origin의 실제 값 포함) | 3.4, 클라우드 담당 |
| 열림 2 | NFR10.4의 '서비스 워커 없음'과 U7 웹 푸시(서비스 워커 필요)가 부딪칠 수 있다. U1은 서비스 워커를 쓰지 않고, /parent에 한정한 예외를 U7이 정할지 | 사람, U7 3.2 전 |
| 열림 3 | iOS Safari 16.4 이상 시험 기기(iPhone) 확보 | 팀 |
| 열림 4 | Next.js · React 정확한 버전 번호. 이 문서는 정하지 않는다 | 3.5 첫 볼트 |
| 열림 5 | OpenAPI → TypeScript 생성 도입 시점(계약이 안정된 뒤) | 후속 유닛 3.3 |
