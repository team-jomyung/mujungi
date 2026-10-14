# U5b 구현 전 코드 조사

## Developer Code Scan Results

### Scan Coverage
전체 저장소를 대상으로 실행 단위·패키지·파일 목록·계약·시험/빌드 표면을 조사했다. 모든 모듈 본문을 깊게 검증했다는 뜻은 아니다.

- **Analyzed deeply**:
  - backend/settings.gradle.kts
  - backend/build.gradle.kts
  - backend/gradle/libs.versions.toml
  - backend/gradle/wrapper/gradle-wrapper.properties
  - backend/core-api/build.gradle.kts
  - backend/realtime-activity/build.gradle.kts
  - backend/contract-model/build.gradle.kts
  - backend/core-api/src/main/java/kr/mujoong/core/activity/PracticeStart.java
  - backend/core-api/src/main/java/kr/mujoong/core/activity/LiveExtras.java
  - backend/core-api/src/main/java/kr/mujoong/core/activity/web/ActivityController.java
  - backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioService.java
  - backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioConfiguration.java
  - backend/core-api/src/main/java/kr/mujoong/core/practice/PracticeSessionContextPort.java
  - backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioRecordPort.java
  - backend/core-api/src/main/java/kr/mujoong/core/practice/web/ScenarioInternalController.java
  - backend/core-api/src/main/java/kr/mujoong/core/practice/web/GoalSelectionController.java
  - backend/core-api/src/main/java/kr/mujoong/core/textai/TextAiConfiguration.java
  - backend/core-api/src/main/java/kr/mujoong/core/textai/ScenarioPrompt.java
  - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/web/SessionInternalController.java
  - backend/realtime-activity/src/main/java/kr/mujoong/realtime/gate/OutputGate.java
  - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderRequestFactory.java
  - frontend/src/parent/screens/Practice18.tsx
  - frontend/src/parent/screens/Live12.tsx
  - frontend/src/parent/practice/api.ts
  - frontend/package.json
  - tools/package.json
  - .github/workflows/ci.yml
  - infra/compose.dev.yml
  - infra/migrate/flyway-realtime.conf
  - contracts/schemas/internal-practice-scenario-request.schema.json
  - contracts/schemas/practice-scenario.schema.json
  - docs/units/u5b/brief.md
- **Skimmed only**:
  - backend/contract-model/src/
  - backend/test-support/src/
  - backend/core-api/src/test/
  - backend/realtime-activity/src/test/
  - backend/core-api/src/main/resources/
  - backend/realtime-activity/src/main/resources/
  - backend/core-api/src/main/java/kr/mujoong/core/
  - backend/realtime-activity/src/main/java/kr/mujoong/realtime/
  - backend/build-logic/
  - frontend/src/device/
  - frontend/src/shared/
  - frontend/tests/
  - contracts/
  - infra/
  - tools/
  - scripts/
  - ci/
  - docs/
  - SessionExecutor의 선언·전이·공급자·종료 일부 구간, StartActivityService 관련 검색 결과, SessionRecordCapturedHandler 일부 본문.
  - 기존 인셉션의 U5/B5/관련 컴포넌트와 U4b/U5a 문서: 현재 구현이 아닌 요구·설계 근거.
- **Left out**:
  - AI-DLC 설치·워크스페이스·생성 영역(지정된 규칙과 요구 근거만 별도 읽음).
  - .gitignore가 제외하는 의존성·빌드·비밀·IDE/도구 캐시: node_modules/, .next/, build/, coverage/, .gradle/ 등.
  - 음성 바이너리 내용, 운영 DB/클라우드/실공급자 상태.

### Packages Found
| 패키지 | 종류·언어 | 목적 |
|---|---|---|
| core-api | 서비스·Java | 계정·동의·아이·기기·활동 사본/기록·연습 기반 |
| realtime-activity | 서비스·Java | 실제 세션·기기 WSS·음성 중계·출력·기록 |
| contract-model | 공통 라이브러리·Java | 계약 DTO·상태·프레임·시간·로그 |
| test-support | 시험 전용 라이브러리·Java | DB·Clock·HTTP·공급자·기기 대역 |
| frontend | 웹·TS/React | /parent, /device와 shared |
| tools | JS ESM 시험 도구 | 가짜 공급자·기기·끝-끝·시드 |
| infra/scripts/ci | SQL·Shell·Python·YAML | 실행·마이그레이션·검증·보안 |

### Build System
- **Type**: Gradle Kotlin DSL + npm.
- **Config Files**: backend/settings.gradle.kts, build.gradle.kts, 모듈 build.gradle.kts, gradle/libs.versions.toml, frontend/package.json, tools/package.json.
- **Build Dependencies**: 두 앱→contract-model, 앱 시험→test-support. 상대 앱 직접 Java 의존은 선언되지 않았다. parent/device/shared import 경계는 ESLint 규칙으로 구성된다.

### APIs Discovered
- 목표 REST 5개: GoalSelectionController.
- 공개 활동 시작: ActivityController의 POST /api/activities/free 하나. practice 매핑 없음.
- Realtime 세션 내부 REST 3개: 시작·끝·진행. 시작은 FREE_TALK만 허용.
- Core C10: POST /internal/practice/scenarios 하나. 기본 세션 포트는 SERVICE_NOT_READY.
- 나머지 계정·기기·활동·기록·이벤트 계약 표면과 WSS는 api-documentation.md에 조사 수준을 표시했다.

### Frameworks & Libraries
Java21·Gradle8.14.3·Spring Boot4.1.1, Next16.4.0·React19.3.0·TS5.9.3·ReactQuery5.104.0·Node22, PostgreSQL16.15 dev 구성, Vitest4.1.11·JaCoCo0.8.14. 판 선언과 설치/배포 상태를 구분한다.

### Test Coverage
- **Test Directories**: backend 모듈 src/test, frontend/tests/u1/u2/u3/u4a/u5a, tools/tests, infra/ci.
- **Test Frameworks**: JUnit/Mockito/AssertJ/ArchUnit/Testcontainers, Vitest/Testing Library/jsdom.
- **Coverage Config**: backend JaCoCo와 frontend V8 줄80%·유닛 범위 구성 존재. U5b 범위 목록은 아직 없다.
- 이 스캔에서 시험을 실행하지 않았다. U5a 과거 report 수치를 현 체크아웃 성공/커버리지로 재사용하지 않는다.

### Code Quality Indicators
- **Linting**: ESLint 경계·문구·보안 규칙, Java 컴파일 경고/구조 시험 설정.
- **CI/CD**: ci.yml 실제 구성 읽음; deploy.yml 목록 확인. CI 실행 결과는 확인하지 않음.
- **Documentation**: 계약/유닛 지시·설계·보고와 코드의 규칙 ID 근거가 있다. 계획된 동작과 현재 구현은 분리했다.

### Technical Debt Signals
- SessionExecutor 1832줄: 전이·공급자·재생·시계·정리가 집중되며 U4b/U5b 접점 충돌 위험.
- PracticeStart 구현·빈/공개 practice 시작/Realtime practice 입력·응답·단계/실제 포트/노출 이벤트/UI 연습 머리가 미완성이다. U5a가 명시적으로 남긴 확장 범위다.
- ScenarioService는 활성 세션을 요구한다. 시작 전에 만드는 첫 상황 경로를 기존 C10과 어떻게 공유할지는 후속 설계에서 명시해야 한다.
- 완전한 TODO/중복/순환·최신 취약점 분석은 하지 않았다.

## Handoff Summary
- **Intent-relevant finding**: PracticeStart.java:14는 인터페이스, ScenarioConfiguration.java:14~20의 두 포트는 기본 SERVICE_NOT_READY, ActivityController.java:42는 free 시작, Practice18.tsx:90은 disabled, SessionInternalController.java:150은 FREE_TALK만 허용하고 :225는 practice=null이다. LiveExtras.java:92의 기존 필터는 연습 단계 6종을 지원한다. ScenarioService.java:138에는 실제 기록에 따른 reconcileExposures가 있다.
- **Risks / follow-up**: U5b brief는 U4b 완료를 가정하지 않고 계약 대역 병렬 개발을 허용한다. U4b 참여 후 ACTIVE·INTRO_PRACTICE 완료·마지막 목표 종료 신호·record.practice·중간 종료/삭제 정산 순서 합의가 실제 통합 선행이다. 현재 realtime 마이그레이션은 V1/V2이고 계획 V3(U4b) 뒤 V4(U5b)를 통합한다.
- 재사용 가능: Core 시작/세션·기록 포트 타입, C10/노출 정산, LiveExtras 필터, 출력 게이트·최소 공급자 요청 경계, 목표 UI. U4b 내부를 복제하지 않는 대역을 사용해야 한다.
- D 담당 판정 기준/프롬프트/정식 금지 사전 확정 증거는 후속 확인이다. 정본 인셉션 U5/B5를 읽기 전용 요구 근거로 사용했고 새 요구사항을 확정하지 않았다.
- 후보 9종은 aidlc/spaces/default/intents/261013-u5b-practice/.aidlc-engine/codekb-stage-mujungi/에 있다. 애플리케이션 코드는 바꾸지 않았고 게시·연결 증빙·수명주기 보고는 부모가 수행한다.

