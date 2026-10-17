# 코드 품질 평가 (Code Quality Assessment)

> 최신 확인: 2026-10-16, HEAD 7e30ee8, U15 집중 분석. 아래 최신 절이 U15 관련 과거 설명보다 우선한다. 이전 분석 본문은 이력으로 보존하며 이번 범위 밖의 검증 주장은 shallow로 낮췄다.

## U15 검증 결과와 후속 위험

기존 PragmaticsKnowledgeTest는 disabled 무호출, enabled-without-adapter 오류, 공개 행동키와 과거 정의를 고정한다. U15에서 이 단언을 보존한다. LunaArchTest와 기존 Luna 경계도 회귀 대상이다. U15 기능 자체는 미구현이며 통과율·커버리지 수치를 아직 보고하지 않는다.

필수 검증: child A/B 격리, 멱등 쓰기·삭제, NONE/꺼짐, 실제 Neo4j Cypher CRUD, 2초 제한과 늦은 작업, V8 권한·마이그레이션, 생성/삭제 경쟁, 외부 삭제 실패 재시도, 원문/개인정보 비노출, 내용 검증·재생성 예산, source zip 해시 보존, u15 LINE 80% 및 기존 u2/u5a/u6/u9 회귀.

이번 기준 실행은 Java 컴파일까지 진행했으나 PostgreSQL 초기화 스크립트의 CRLF 때문에 컨테이너가 exit 127로 실패했다(env: bash\\r). 성공한 기준 시험으로 취급하지 않는다. Linux 실행용 체크아웃 줄바꿈을 복구한 뒤 재검증해야 한다.

우선 위험: 삭제 실패를 빈 값으로 삼키는 오류, 삭제 후 늦은 생성 쓰기, 결과 FK와 삭제 순서 충돌, Luna 선택적 빈의 필수 주입, 중첩 재시도, 리포트용 금지문구 예외 오용. 아래 오래된 기술 부채 중 Luna 미연결 평가는 이번에 확인된 U9 구현으로 대체한다.

## 이전 분석 기록 — 2026-10-15, 현재 범위 밖은 재검증하지 않음

> 기준: 커밋 `72d7034`, 2026-10-15 전체 재스캔. 이 문서가 기술 부채(TD-n) 목록의 정본이며, 다른 문서는 TD 번호로 가리킨다.

## 1. 시험

| 위치 | 규모 | 비고 |
|---|---|---|
| `backend/contract-model/src/test/` | 15 클래스 · @Test 120 | 계약 타입 |
| `backend/core-api/src/test/` | 157 클래스 · @Test 996 | ArchUnit `core/arch` 포함 |
| `backend/realtime-activity/src/test/` | 126 클래스 · @Test 819 | ArchUnit `realtime/arch` 포함 |
| `backend/test-support/src/test/` | 4 클래스 · @Test 21 | |
| `frontend/tests/u1 … u7/` | 시험 파일 73 | Vitest + Testing Library + jsdom |
| `tools/tests/u1 · u3 · u4a · u4b/` | 11 | Vitest |
| `infra/ci/test-*.sh` | — | Bash 음성 대조 시험 |
| `tools/skeleton-e2e/`, `scripts/verify/c-skeleton-turn.sh` | — | 끝-끝 스켈레톤 |

백엔드 시험 태그: db 107 · u1 142 · u2 15 · u3 24 · u4a 5 · u4b 24 · u5a 11 · u5b 34 · u6 35 · u7 17.

커버리지: 백엔드 JaCoCo 줄 80% 하한(모듈별, test-support 제외), `-PcoverageScope=<유닛>`로 `backend/coverage-scope/*.txt` 범위 측정. 프런트 v8 줄 80% 하한, `MUJOONG_COVERAGE_SCOPE`로 `frontend/coverage-scope/*.json` 범위 측정. CI가 전체 + U5b · U7 범위를 따로 검증. org 기본 `classic` 범위의 줄 80% 하한은 이미 CI에 걸려 있다.

시험 개수 · 태그만 확인했고 시험 본문 품질은 보지 않았다.

## 2. 린트 · 서식

- 프런트: ESLint flat config(`frontend/eslint.config.mjs`) — next core-web-vitals · typescript, `/parent` ↔ `/device` ↔ `shared` import 경계, `react/no-danger`, `react/jsx-no-literals`(화면 문구는 copy 모듈만), `getUserMedia` 사용처 1곳, 서비스 워커 금지(U7 보호자 SW 예외).
- 백엔드: javac `-Xlint:all`(경고를 오류로 바꾸지 않음) + ArchUnit 계층 · 의존 규칙. Java 포매터(Spotless · Checkstyle) 없음.
- `.prettierrc.json`(printWidth 100) · `.editorconfig` 있음. CI에 Prettier 검사 단계 없음.

## 3. CI/CD

- `.github/workflows/ci.yml` 작업: changes · backend(`gradle clean check verifyBootJarHasNoTestClasses`) · u5b-scope · U7 범위 · backend-deps(Trivy) · frontend(ESLint · Vitest 커버리지 · `next build` · 번들 비밀 문자열 0건) · frontend-deps(npm audit) · tools · secrets(gitleaks) · infra-validate(Compose 불변식 12 · `nginx -t` · shellcheck · 마이그레이션 expand 검사) · images(6종 빌드 · Trivy · 이미지 점검 · GHCR 푸시) · skeleton. 액션은 커밋 SHA 고정.
- `.github/workflows/deploy.yml`(단계 이름만 확인): 수동/자동 DEPLOY · ROLLBACK, OIDC → SSM `mujoong-deploy`, demo 환경. `docs/units/README.md`에 따르면 `SERVICE_HOST` · `AUTO_DEPLOY` 변수가 없어 실제 배포 단계는 건너뛴다.

## 4. 문서화

- 루트 `README.md` · `AGENTS.md`, `infra/RUNBOOK.md`(demo 운영 정본), `contracts/README.md` + `deviations.md`(편차 67행), `docs/units/<u>/{brief,design,report}.md`, `docs/erd/ERD_v0.2.md`, `docs/change-history.md`.
- 코드 주석: 한국어 Javadoc이 촘촘하고 결정 ID(J · BR · U*-J · 편차)를 인용한다. TODO · FIXME는 앱 소스에 0건(남은 일은 문서로 관리).

## 5. 기술 부채 (TD)

| ID | 내용 | 위치 · 증거 | 영향 | 갱신 문서 작업과의 관계 |
|---|---|---|---|---|
| TD-1 | **AI 공급자 운영 어댑터 부재 — 기본 빈이 항상 실패(fail-closed)** | `core/textai/TextAiConfiguration.java`(`unavailableTextAiProvider` → `TextAiException.Reason.UNAVAILABLE`), `core/result/ResultTextConfiguration.java`(`PROVIDER_UNAVAILABLE`), `realtime/practice/PracticeRuntimeConfiguration.java:46-51`(`EvaluationProvider` 실패 future, `CueProvider.UNAVAILABLE`). 실제 구현은 시험 대역(`FakeResultTextProvider` 등)뿐. 환경 변수 이름 `MUJOONG_TEXT_AI_ENDPOINT/MODEL/API_KEY`는 `application.yml` 주석에만 있음 | 운영에서 상황 생성 · 결과 문장 · 리포트 · 연습 판정 · 단서가 동작하지 않음. 기동 · 시험은 비밀 없이 가능 | `15_기술명세_AI동작_1015` 반영 시 핵심 변경 지점 |
| TD-2 | **음성 공급자 선 형식이 프로젝트 정의(J110)** | 모델 `gpt-live-1`, `session.*` type 체계, URL `/v1/live/sessions` — `realtime/voice/ProviderRequestFactory.java:27-39`, `ProviderClient.java:190-205`, `application-{demo,prod}.yml` | 실공급자 형식과 다르면 연결 불가. 가짜 공급자(`tools/fake-provider/server.mjs`) · `test-support` 대역도 같은 형식이라 함께 바꿔야 함 | `40_기능명세_GPT-Live` 반영 시 핵심 변경 지점 |
| TD-3 | 지식 그래프(Neo4j) 읽기 어댑터 없음 | `core/knowledge/KnowledgeGraphLookup` 인터페이스만, `mujoong.knowledge.neo4j.enabled=false`. Compose demo에 `neo4j` 서비스(프로필 knowledge)는 있음 | 기능 미사용, 설정 · 이미지만 존재 | 문서가 지식 그래프를 요구하면 새 어댑터 필요 |
| TD-4 | **거대 클래스 `SessionExecutor`** | `realtime/session/SessionExecutor.java` 3,388줄(상태 · 타이머 · 출력 · 연습 확장 실행). 다음: `FileAudioRecorder` 619 · `CoreProperties` 576 · `StopIntentJudge` 565 | 세션 동작 변경의 영향 범위 · 리뷰 비용 큼 | 세션 전이 · 시간 값 변경 시 집중 스캔 필요 |
| TD-5 | **압축 서식(한 줄에 메서드 전체)** | U6 계열 `core/report/web/ReportController.java`, `core/report/ReportRepository.java`, `core/result/SessionResultHandler.java`, `core/result/ResultAiSchema.java`, `core/result/ResultTextConfiguration.java`, 프런트 `parent/screens/Report30.tsx` · `RecordDetail27.tsx` · `ReportRange28.tsx` · `parent/api/results.ts`(250자 넘는 줄 다수) | 가독성 · diff 리뷰 저하. CI에 Java 포매터 · Prettier 검사가 없어 막히지 않음 | 결과 · 리포트 수정 시 서식 정리 여부 결정 필요 |
| TD-6 | **시간 값이 세 곳에 중복** | 두 앱 `application.yml`의 `mujoong.timing.*` ↔ `contracts/timing.md` ↔ `frontend/src/shared/timing/timing-defaults.json`(시험이 대조) | 하나만 바꾸면 시험 실패 또는 불일치 | 문서의 시간 값 변경은 세 곳 동시 수정 |
| TD-7 | **마이그레이션 번호 · 승인 규칙** | 다음 번호 core V8 · realtime V5, Flyway `outOfOrder` 꺼짐, 기존 표 제약 강화는 `infra/migration-approvals.txt` 승인 행이 있어야 CI expand 검사 통과 | 병렬 브랜치에서 번호 충돌 위험 | DB 변경 시 번호 · 승인 행 함께 |
| TD-8 | **커버리지 범위 목록 유지 부담** | `backend/coverage-scope/*.txt` · `frontend/coverage-scope/*.json` + 유닛 태그 u1~u7 | 파일을 옮기거나 새로 만들면 목록 수정 필요(목록에 없는 파일이면 측정 실패) | 새 파일 추가 시 범위 목록 · 태그 수정 |
| TD-9 | 유닛 혼합 이력 | U2~U5a는 엔진 없이 병렬 구현, U5b · U6 · U7은 통합 브랜치로 반영(`docs/units/README.md`) | 유닛마다 설계 문서 · 편차 기록의 밀도가 다름 | 문서 차이 계산 시 유닛별 편차 확인 |
| TD-10 | 알려진 흔들림 · 남은 일 | `RealtimeInternalApiIT` 부하 시 409, 세션 문구 6종 음성이 합성음 자리(`infra/seed-audio/*-v1|v2.wav`), 시드 003을 검증 스크립트 · RUNBOOK 시드 순서에 넣는 일 | 시험 간헐 실패, 데모 음성 품질 | — |

## 6. 이번 스캔의 확인 한계

- 업무 로직 본문(core result · practice · activity, realtime session · gateway, frontend parent · device)은 얕게 봤다. 위 TD 중 TD-1 · TD-2 · TD-6은 파일을 직접 읽어 확인했고, TD-4 · TD-5는 크기 · 줄 길이로 판단했다.
- 문서 차이가 특정 기능에 걸리면 그 경로를 집중 스캔해야 한다([reverse-engineering-timestamp.md](reverse-engineering-timestamp.md)의 `shallow.paths`).

## 7. 종합

시험 · CI · 계약 관리는 촘촘하다(시험 약 2천 개, 줄 80% 하한, 계약 시험, 불변식 검사). 약점은 (1) 외부 AI 운영 어댑터가 없고 음성 선 형식이 자체 정의라 실공급자 연결이 검증되지 않은 점, (2) 세션 실행기 집중과 압축 서식, (3) 시간 값 · 커버리지 범위 · 마이그레이션처럼 여러 곳을 함께 고쳐야 하는 규칙이다.
