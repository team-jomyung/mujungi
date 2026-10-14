# 코드 품질 평가 (Code Quality Assessment)

> 기준 커밋 `7a22b67`. CI 작업 목록은 `component-inventory.md` ci-workflow에만 적는다.

## 시험

| 영역 | 위치 · 수 | 도구 |
|---|---|---|
| core-api | `backend/core-api/src/test/java/kr/mujoong/core/**` 113개 + `V90__test_default_privilege_probe.sql` | JUnit 5 · AssertJ · Mockito · Spring Boot Test · Testcontainers · ArchUnit · json-schema-validator |
| realtime-activity | `backend/realtime-activity/src/test/java/**` 115개 | 같음 |
| contract-model | 15개 | JUnit |
| test-support | 4개 | JUnit |
| frontend | `frontend/tests/{u1,u2,u3,u4a,u5a}/**` 56개 `*.test.ts(x)` | Vitest · Testing Library · jsdom |
| tools | `tools/tests/` | Vitest |

### 커버리지 설정 (있음)

- 백엔드: 모듈마다 JaCoCo LINE 80%를 `check`에 건다(test-support 제외). `-PcoverageScope=<u>` → `backend/coverage-scope/<u>.txt` 파일만 측정, 목록이 비거나 없는 파일이 있으면 실패. 현재 u1 · u2 · u3 · u4a · u4b · u5a — **u6 없음**.
- 프런트: `vitest.config.ts` v8 줄 80%, `MUJOONG_COVERAGE_SCOPE=<u>` → `frontend/coverage-scope/<u>.json`. 현재 u1 · u2 · u3 · u4a · u5a — **u6 없음**.
- 태그(`@Tag`): db 75 · u1 142 · u2 15 · u3 24 · u4a 5 · u4b 24 · u5a 11 — **u6 없음**.
- CI는 범위 없이 모듈 전체 80%도 돈다.

## 정적 검사 · 구조 규칙

- 프런트: ESLint flat config(`/parent` ↔ `/device` import 경계, `react/no-danger`, `react/jsx-no-literals`로 문구는 copy 모듈만, getUserMedia · 서비스 워커 제한), Prettier, `.editorconfig`. `eslint-disable` 0건.
- 백엔드: 포매터 · 정적 분석 플러그인 없음(spotless · checkstyle · errorprone 0). 대신 `javac -Xlint:all` + 시험으로 거는 규칙:
  - `CoreArchTest`(ArchUnit): realtime 참조 0 · 시험 코드 의존 0 · 시스템 시각 직접 읽기 0 · 세션 사본 쓰기 위치 제한
  - `TimeLiteralScanTest` · `NoSqlNowTest`: 시간 상수 · SQL `now()` 금지
  - `ConfigKeyContractTest`: `contracts/timing.md` ↔ `application.yml` ↔ `CoreProperties` 키 · 기본값 일치
  - `ContractConformanceIT`: 응답 ↔ `contracts/schemas` 대조
- 표지: 운영 소스 TODO · FIXME · HACK 0건, `@SuppressWarnings` 6건(unchecked 캐스트).

## 문서

루트 `README.md`, `contracts/README.md`, `infra/RUNBOOK.md`, 유닛별 `docs/units/<u>/{brief,design,report}.md`. 운영 코드는 한국어 Javadoc에 근거 ID(FR · BR · J · AC)를 촘촘히 단다. 예외: U5a 코드(`knowledge` · `practice` · `textai`)는 영어 한 줄 압축 주석이라 주변 관례와 다르다.

## 기술 부채 (U6 관련 우선)

| # | 내용 | 위치 | U6 영향 |
|---|---|---|---|
| D1 | `ActivityRecorded` 운영 구독자 0, `ScenarioService.reconcileExposures` 운영 호출자 0 | `record/ActivityRecorded.java`, `practice/ScenarioService.java:138` | ResultPipeline이 첫 구독자. 재발행 없음 → 누락 복구 필요 |
| D2 | U6 자리표시 기본 빈(근거 포트 false · 메타 null · SERVICE_NOT_READY) | `practice/PracticePortConfiguration.java`, `practice/ScenarioConfiguration.java` | 같은 타입 빈으로 교체 |
| D3 | `ScenarioLookup` · `BehaviorDefinitionLookup` 타입 없음 | `practice/` · `knowledge/` | U6가 확장으로 추가 |
| D4 | `TextAiProvider` · `TextAiGateway`가 상황 생성 전용 | `textai/` | 6절 작업용 확장 필요 |
| D5 | 금지 표현 사전 형식 불일치(txt 18줄 vs ai-io.md 6.6 JSON `appliesTo`), Realtime `gate/seed-dictionary.json`과 별개 | `resources/textai/u5a-forbidden-patterns.txt` | 넓히면 U5a `CandidateRegistry` 동작도 바뀜 |
| D6 | 설정 키 불일치: ai-io `mujoong.ai.*` vs 코드 `mujoong.text-ai.*`; `result.job-attempts` · `report.ready-target`은 timing.md 예약표에만 | `TextAiProperties`, `contracts/timing.md` 2절 | `ConfigKeyContractTest` 때문에 세 곳 동시 변경 |
| D7 | 26 화면 스텁, `/api/records` 클라이언트 없음, 17 「오늘 기록 보기」 감춤 | `frontend/src/app/parent/records/page.tsx`, `Done17.tsx` | U1 J120 · U4a-J14 단언을 U6가 바꿈(설계에 까닭 기록) |
| D8 | PDF 저장 자리 · 글꼴 없음(읽기 전용 루트 · tmpfs 64m · 영속 볼륨 없음) | `infra/compose.demo.yml`, `core-api/Dockerfile` | compose · Dockerfile · `infra-validate` 불변식 변경 필요(OQ6) |
| D9 | `core_goal_recommendation.structured_experience_id`(FK 없음) | `V4__u5a_practice_core.sql` | V5에서 FK를 걸면 `migration-approvals.txt` 승인 필요 |
| D10 | `DeletionParticipant` Javadoc 상수 목록 · `DeletionCommander` 주석 | `common/deletion/DeletionParticipant.java` | 70 추가 시 같이 갱신 |
| D11 | `record.practice` 내부 모양이 `contracts/`에 없음(정본 contract-summary 6절만), 값은 U5b 전 null | `realtime/session/ResultSnapshotBuilder.java` | PRACTICE 경로는 계약 예시 대역으로만 시험 가능 |
| D12 | 큰 파일 `SessionExecutor.java` 3167줄, `MujoongPostgres.java` 971줄 | realtime · test-support | U6 무관 |

## 종합

- 구조 규칙 · 계약 대조 · 커버리지 관문이 시험으로 강하게 걸려 있어 **회귀 방어가 좋다**(healthy).
- U6 관점의 위험은 코드 품질보다 **빈 확장점과 계약 공백**(D1 ~ D6 · D11)이다. 특히 D6은 설정 키 하나 더하는 일이 계약 파일 수정까지 번지는 결합점이다.

## Sources

- 개발자 스캔 `.../developer-scan.md`(Test Coverage · Code Quality Indicators · Technical Debt Signals · Risks)
