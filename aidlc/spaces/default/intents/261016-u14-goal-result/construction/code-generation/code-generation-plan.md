# U14 Code Generation Plan

## Summary

- Builds: 불변 목표 후보·담은 목표, Luna 후보 생성, 새 연습 결과·재판정과 보호자 조회
- Touches: core-api 목표·결과·리포트·V9, contracts, 양 앱 시간 설정, frontend API 파서와 U14 시험
- Tests: 컴포넌트당 5~8개 단위 시험과 핵심 PostgreSQL 경계·경합 시험, 약 120~160개 신규 사례 및 기존 전체 회귀

## 범위와 실행 방식

승인된 requirements.md FR1.1~FR4.2/NFR1~NFR5, functional-spec.md D1~D6, rules.md BR1.1~BR4.2, entities.md를 구현한다. 원 US4.1~US4.6/FR7·FR8을 아래 단계에 연결한다. Zero-Unit refactor이므로 합성 Unit/Bolt/source-manifest를 만들지 않고 이 폴더에 계획·시험 지시·완료 요약·traceability를 둔다.

한 개발자가 순차 실행한다. 시험 가능한 층을 구현하면 그 층의 시험을 작성·실행하고 통과 후 다음 층으로 간다. 승인된 계획의 문구를 임의로 다시 쓰지 않고 완료한 체크박스만 표시한다. 실공급자·키·기기 사용과 배포·병합은 없다. 중단 후에는 체크뿐 아니라 실제 파일·커밋·시험 증거로 끝난 단계를 확인한다.

## 변경 영향과 호환 경계

- 높음: core/practice의 CandidateRegistry/PracticeGoalRepository 및 core/result/CandidateScreen의 목표 소유권을 core/practicegoal로 모으고 기존 진입점은 호환 위임을 유지한다. 기존 연습·장면 생성·선택 컨트롤러와 U5a/U5b 시험의 시그니처·뜻을 보존한다.
- 높음: SessionResultHandler, ResultData/ResultAiSchema, SceneJudgmentRepository, JobRunner와 ResultQueryService, report/ReportAssembler. 기존 guardian→job→report 잠금·lease·durable pending 후보 replay·결과 수준 계산은 유지한다.
- 중간: U15 ChildKnowledgeService/SeedGenerationService는 공개 경계를 사용한다. 후보·선택에 직접 쓰는 주체는 PracticeGoal만이다. 씨앗 생성은 DONE 커밋 뒤 실행하며 후보 불변 버전을 새로 게시한다.
- 중간: contracts/schemas의 목표/결과/재판정, parent-api.yaml, ai-io.md, timing.md 및 frontend/src/parent/api/results.ts. 새 후보 내부 reasonText는 공개하지 않고 displayEvidence를 기존 화면 모델로 최소 변환한다. legacy 응답은 보존한다.
- 낮음: backend/coverage-scope/u14.txt, frontend/tests/u14, docs/units/u14/design.md·report.md. frontend 27 화면·realtime 운영 코드·다른 단위 단언·커버리지 하한은 수정하지 않는다. realtime application.yml의 동일 시간 기본값만 세 번째 커밋에서 예외적으로 맞춘다.

## 순차 구현 단계

### 공통 준비

- [x] Step 1. 현재 브랜치·U15 기준 커밋·기존 미커밋 변경을 기록하고 기존 backend/frontend 전체 시험 기준선을 확보한다. 기존 결과/목표 시험을 먼저 확인하며, 테스트 실패가 환경 문제인지 제품 회귀인지 구분한다. JDK21·Docker·Node22 및 기존 JUnit/Vitest 설정과 정확 필터 명령을 검증한다. 이후 모든 신규 백엔드 시험에 @Tag("u14")를 둔다. 초기 runner 확인은 기존 SceneChipMapperTest만 정확 지정한다. (NFR1·NFR2)
- [x] Step 2. 실제 source/consumer를 읽고 아래 파일별 변경을 diff로 확인한 뒤 적용한다. 새로운 Spring 컴포넌트 wiring·기존 공개 생성자/포트 호환·테스트 패키지 배치를 정한다. 범위 파일에 누락/없는 경로가 있으면 실패하는 기존 검사를 그대로 쓴다. (NFR1·NFR5)

### 커밋 1: V9 + PracticeGoal 소유 이전 + SavedGoalReader

- [x] Step 3. V9__u14_goal_result.sql에 생성 후보·불변 버전·선택 스냅샷 저장과 필요한 새 사실/판정 확장 열을 추가한다. 기존 V1~V8 불변, legacy 행동 FK/필수 열 유지, 양의 가변 scene_no·AFTER_REASK·총4회 제약을 호환 확장한다. 새 표 FK/인덱스/활성 후보별 선택 유일성을 둔다. 기존 표 변경 문장 번호·해시·구 앱 쓰기 호환 근거를 문서화하되 실제 사람의 승인 서명은 만들지 않는다. (FR1.1·FR3.1·FR3.2·FR3.4, NFR4; US4.1·US4.2·US4.4·US4.6)
- [x] Step 4. 구현 직후 U14MigrationIT를 작성·실행한다. V8→V9 데이터 보존/legacy 쓰기, 후보 버전 불변·유일성·FK, 장면3 이상, 시도4와5 거절, AFTER_REASK 허용을 PostgreSQL에서 확인한다. 마이그레이션 승인 검사가 실제 사람 확인을 요구하면 구체 SQL/해시/근거를 부모에게 전달하며 우회하지 않는다. (NFR1·NFR4)
- [x] Step 5. PracticeGoal을 후보·선택의 단일 writer로 구현하고 legacy 클래스는 호환 facade로 연결한다. SavedGoalReader와 SavedGoalView/FourElements를 C-DS10 그대로 제공한다. recommendationId는 불변 버전 UUID로 하고 선택은 관측한 버전을 고정한다. active child+stable candidate 중복 담기는 기존 선택 반환, 삭제·복원 충돌은 기존 오류 의미를 따른다. (FR1.1·FR1.2, BR1.1·BR1.2; US4.6)
- [x] Step 6. 구현 직후 SavedGoalReaderTest, PracticeGoalRepositoryIT, CandidateVersionSelectionIT를 작성·실행한다. 기본·없음·legacy·타 아동·철회·삭제와 v1조회→v2게시→v1담기(NONE), v2담기(seed), v1활성시v2담기, 동시담기, 삭제뒤v2담기, v1복원충돌을 검증한다. (FR1.1·FR1.2, NFR3; US4.6)
- [x] Step 7. 위 층 시험과 기존 목표 관련 회귀가 초록인 상태에서 U14 관련 경로만 feat(u14): 목표 버전 저장과 담은 목표 읽기 구현 으로 커밋한다. stage 전 cached diff와 mode 변경을 확인하며 generated manifest·U15 audit·scratch는 섞지 않는다. (NFR1·NFR4)

### 커밋 2: Luna 목표 후보 + skillRef 검증 + 새 씨앗 연결

- [x] Step 8. GOAL_CANDIDATES 전용 schema/provider 경로와 후보 입력·검증을 구현한다. 식별자·연령·원음 제외/이름 비식별화, 최대3·이름12·displayEvidence40·4요소·정확 seq/quote·seed 허용 목록/NONE·OutputChecker/classify를 적용한다. 기존 RESULT_TEXT와 나누고 JOB_ATTEMPT 내부 재시도를 추가하지 않는다. NONE에 필요한 선택적 구조 추상화는 동일 응답의 검증된 내부 스키마로 받고 상수/원문 서명은 만들지 않는다. 계약 변경·편차를 함께 남긴다. (FR2.1·FR2.2, NFR2; US4.5)
- [x] Step 9. 구현 직후 GoalCandidateValidatorTest, GoalCandidateGatewayTest, LunaGoalCandidateProviderTest를 작성·실행한다. 5~8개/컴포넌트 이상으로 빈후보·길이·4요소빈칸·근거불일치·잘못된seed·금지표현·잘못된JSON·익명화·호출1회·후보없음에도DONE 가능을 검증한다. (FR2.1·FR2.2, NFR2·NFR3)
- [x] Step 10. 결과의 durable pending 후보 정본을 확장하고 커밋 후 등록·NONE 씨앗 생성·비교 후 새 버전 게시를 구현한다. 동일 candidate+version idempotency, 삭제/철회/최신버전 재확인, 실패 NONE 유지·DONE 불변을 보장한다. 외부 AI/graph 동안 DB 잠금을 잡지 않는다. (FR2.3·FR4.2, BR2.3·BR4.2; US4.5·US4.6)
- [x] Step 11. 구현 직후 CandidateSeedLinkTest와 CandidateSeedReplayIT를 작성·실행한다. 정상 새버전·graphoff·실패·타임아웃·구조없음·중복 callback·stale버전·동시담기·삭제/철회 경합·재생 후 한 번 게시를 확인한다. U15는 fake 포트로 격리한다. (FR2.3, NFR2·NFR3)
- [ ] Step 12. 위 층 시험 및 이전 층 회귀 후 feat(u14): Luna 후보 생성과 씨앗 연결 구현 으로 코드·동일 계약 변경을 함께 커밋한다. (FR2.1~FR2.3)

### 커밋 3: 새 결과 · 재판정 · 4회 재시도

- [ ] Step 13. practice-record-v2 reader/내부 모델을 구현한다. flowVersion 부재만 legacy, 정확한 새버전만 v2, 미지원버전/중복 sceneRunId 거절, 실제 시작 장면/goalRef·가변양수 번호·알려진 fact dedupe·변형생략/종료 구분을 보존한다. snapshotVersion 의미는 바꾸지 않는다. (FR3.2, BR3.2; US4.2·US4.3)
- [ ] Step 14. 구현 직후 PracticeRecordV2Test와 PracticeRecordV2IT를 작성·실행한다. legacy·새버전·미지원·1/3개장면·시작안한장면·unknown fact·중복fact·중복ID·변형생략·타아동목표를 검증한다. U12 생산부 대신 계약 fixture를 쓴다. (FR3.2, NFR3)
- [ ] Step 15. REJUDGMENT gateway/provider와 장면 결과 저장을 구현한다. 실제 sceneRunId 집합 일치·value/timing 일관성·AFTER_REASK·differs_from_live를 보존하고 진행중 판정과 다르면 기록용 판정을 사용한다. 저장·27칩·report mapper를 분리하며 safe 목표표현8자/전달 fallback, legacy 문구 유지, 혼합 report는 raw timing으로 집계한다. (FR3.1·FR3.3·FR3.5; US4.1·US4.3)
- [ ] Step 16. 구현 직후 RejudgmentGatewayTest, U14SceneProjectionTest, U14MixedReportIT, U14ResultPipelineIT를 작성·실행한다. 빠진/중복/불명 scene·value/timing 모순·단순 재발화·세종류timing·differs내부·unsafe표현·legacy/새혼합·없는2번째장면미생성·도움없음비실패를 검증한다. (FR3.1·FR3.3·FR3.5)
- [ ] Step 17. JobRunner/관련 timing을 총4회·5/15/30초로 맞추고 양 앱 application.yml, contracts/timing.md, frontend timing-defaults.json, ai-io.md 6.9/10.3을 같은 변경으로 갱신한다. 세 JOB_ATTEMPT 목적은 각 시도당 내부재시도0, lease·manual retry·최종FAILED를 유지한다. (FR3.4, NFR5; US4.4)
- [ ] Step 18. 구현 직후 U14ResultRetryIT와 U14TimingContractTest를 작성·실행한다. 최초+추가3회·각지연·5번째실행없음·분류별재시도·내부call count·최종실패/수동재요청·lease경합과 양앱/계약/frontend 기본값 동등을 검증한다. 기존 다른 유닛 단언은 수정하지 않는다. (FR3.4, NFR1·NFR5)
- [ ] Step 19. 위 층 및 앞 층 시험 후 feat(u14): 새 연습 결과와 재판정 및 재시도 구현 으로 시간 정본 전체를 함께 커밋한다. (FR3.1~FR3.5)

### 커밋 4: 보호자 REST + 삭제

- [ ] Step 20. ResultQueryService/결과 DTO·선택/목록 REST에 새 후보 displayEvidence와 장면 timing을 투영하고 내부 reasonText/fourElements/skillRef/live/differs를 제외한다. legacy response 유지, strict schema에서 깨는 변경 분류와 parent-api/schema 예시·deviations를 함께 확정한다. (FR4.1; US4.1·US4.5·US4.6)
- [ ] Step 21. 구현 직후 U14ResultApiIT/U14GoalApiIT를 작성·실행한다. 새·legacy 직렬화, 비공개 필드 부재, timing 보존, 불변 추천핸들 선택, 기존 경로/오류봉투·인증·타아동 격리를 검증한다. (FR4.1, NFR3)
- [ ] Step 22. frontend/src/parent/api/results.ts의 새 displayEvidence→기존 화면 모델 최소 adapter를 구현하고 바로 frontend/tests/u14/results.test.ts를 작성·실행한다. legacy reasonText·새 displayEvidence·timing·선택핸들·null/빈값을 검증한다. 27 화면과 chip enum은 변경하지 않는다. (FR4.1, NFR5; US4.5)
- [ ] Step 23. 기존 ResultDeletionParticipant(70)/PracticeDeletionParticipant(150)를 확장하여 새 사실·판정·pending·후보버전·evidence·선택 snapshot을 FK 순서대로 지우고 late callback/replay의 부활을 막는다. U15(80)와 기존 잠금 순서를 유지한다. (FR4.2, NFR3)
- [ ] Step 24. 구현 직후 U14DeletionIT를 작성·실행한다. 아동·계정·기록 삭제, 일부실패 비완료, AI/seed 지연응답·pending replay·선택 경합, 철회와 중복 삭제를 확인한다. (FR4.2, NFR3)
- [ ] Step 25. backend/coverage-scope/u14.txt에 모든 새/수정 운영 Java 경로를 빠짐없이 넣고 태그 시험·실제 범위80%·계약대조·ArchUnit·기존 전체 backend check·frontend lint/coverage/build를 검증한다. 범위검증 빈목록/없는파일 실패와 기존 하한을 그대로 둔다. 전체 verify.sh compose는 Build and Test에서 모든 변경과 함께 실행한다. (NFR1~NFR5)
- [ ] Step 26. 앞 단계들과 REST/삭제 검증이 통과하면 feat(u14): 보호자 결과 조회와 삭제 확장 으로 커밋한다. code-summary.md·traceability.json·docs/units/u14/report.md에 구현파일·시험실측·편차·남은제약을 기록한다. traceability는 이 기록의 FR/NFR/BR 각각을 실제 경로 하나에 연결한다. 검토는 완료 산출물 뒤 부모가 순차 요청한다. 검토 보완 후 Build and Test/전체verify·PR CI를 완료하고 authorized branch push만 수행하며 배포·PR병합은 하지 않는다. (전체 FR/NFR/BR)

## 시험 계획과 완료 조건

각 신규 testable 컴포넌트 단위 5~8개 이상과 핵심 PostgreSQL 경계 시험을 작성한다. 위 파일 이름은 구현 책임을 고정하며 내부 분리 시 같은 책임의 시험으로 보완하고 빠진 경계를 만들지 않는다. 약120~160개는 계획치이며 실제 개수/실패/skip/커버리지는 완료 보고에서 측정한다. 신규 fixture는 합성 문장·무작위 UUID·고정 UTC Clock, 실키 없는 fake Luna/OutputChecker/U15로 구성한다.

기존 단언·ArchUnit·contract 대조·모듈80%와 U14범위80%를 낮추거나 제외하지 않는다. 다른 유닛의 기대값과 새 정본이 충돌하면 실제 차이와 계약 근거를 부모에게 보고한다. 신규 범위파일만으로 전체 모듈 측정을 대체하지 않는다. 실제 사람 검토를 요구하는 마이그레이션 승인 항목은 구체 SQL과 해시를 마련한 후 처리하며 이름을 추정하지 않는다. 시험 명령은 unit-test-instructions.md에 정확 범위로 둔다.

## 소스와 산출물

입력: docs/units/u14/brief.md, 이 기록 inception/requirements-analysis/requirements.md 및 construction/functional-design/{functional-spec,rules,entities}.md, docs/units/u14/design.md, upstream 261015-doc-sync-code-update의 US4.1~US4.6/C-DS10/C-DS12, 현재 core.practice/result/report/knowledge와 contracts 정본.

산출물: application code/test/config/contracts, 본 계획의 완료 체크, unit-test-instructions.md, code-summary.md, traceability.json, docs/units/u14/report.md. Zero-Unit source-manifest 없음.

## Testing Contract

```json
{
  "version": 1,
  "methodology": "test-after",
  "source": "team",
  "ordering": "시험할 수 있는 층을 하나 구현하면, 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 넘어간다.",
  "scope": "refactor",
  "test_strategy": "standard",
  "project_type": "brownfield",
  "applicable_notes": [
    {
      "layer": "org",
      "text": "We treat tests as a first-class deliverable in every Bolt. The specific\nmethodology (TDD, BDD, ATDD, or classic test-after) is affirmed at\npractices-discovery and recorded in `team.md` under this heading with explicit\n`Methodology` and `Ordering` fields; Code Generation resolves those fields\nindependently from coverage, tooling, and scope notes.\n\nWhen no posture has been affirmed, our default per scope is:\n- **Methodology**: test-after\n- **Ordering**: implement each applicable testable layer, then write and run\n  that layer's tests.\n- `mvp`, `enterprise`, `feature`, `infra`, `classic` add an 80% line-coverage\n  floor and CI execution before merge.\n- `bugfix`, `security-patch` add a targeted regression for the specific\n  bug/vulnerability and require the existing suite to remain green.\n- `express` uses the Minimal strategy: requirement-driven unit tests (one per\n  requirement, with a happy-path floor per component); existing tests remain\n  green.\n- `poc`, `refactor`, `workshop` add no extra new-test floor and require the\n  existing suite to remain green.\n\nThe active `Test Strategy` still applies in every scope and determines test\nvolume/types. Scope floors are additive; they never reduce or replace the\nselected strategy.\n\nBuild and Test verifies defined coverage floors and affirmed quality targets;\nthey may not be weakened to make a step pass.\n\nAffirm a stricter posture in `team.md` if the team commits to one."
    },
    {
      "layer": "team",
      "text": "- **Methodology**: test-after\n- **Ordering**: 시험할 수 있는 층을 하나 구현하면, 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 넘어간다.\n- 계약에 닿는 변경도 같은 순서를 따른다(Q3-A). 다만 설계 문서 변경이 계약에 닿으면 같은 작업에서 `contracts/`도 함께 고친다(`discovered-rules.md` R-M10).\n- 커버리지 하한(줄 기준만, 분기 · 함수 하한 없음):\n  - 모듈 전체 줄 80%를 CI가 막는다. 백엔드 JaCoCo는 `contract-model` · `core-api` · `realtime-activity`(`test-support`는 제외), 프런트 Vitest v8은 `src/**/*.{ts,tsx}`(`*.d.ts`만 제외).\n  - 유닛 범위 줄 80%는 범위 파일(`backend/coverage-scope/<유닛>.txt` · `frontend/coverage-scope/<유닛>.json`)이 있는 작업에 적용한다. **CI가 실제로 막는 범위는 U5b · U7뿐이다**(백엔드 `u5b` · `u5b-core` · `u7`, 프런트 `u5b` · `u7`). 나머지 범위 파일은 로컬 · 검토 근거다.\n  - `tools/`에는 커버리지 하한이 없다(시험 지원 코드, J107).\n  - 범위 측정은 빈 목록 · 없는 파일이면 실패한다(J137). 범위 파일을 새로 쓰면 이 방식을 그대로 따른다.\n  - `classic` 범위의 줄 80% 하한 · 병합 전 CI 실행과 같다.\n- 하한 · 기존 단언 · 대조 시험 · ArchUnit 시험은 시험을 통과시키려고 낮추거나 끄거나 건너뛰게 하지 않는다. 모자라면 시험을 더하거나 차이를 보고한다.\n- 시험 종류:\n  - 단위: JUnit 5 · AssertJ · Mockito(백엔드), Vitest + Testing Library + jsdom(프런트), Vitest(tools).\n  - 통합: Spring Boot 통합 시험 + Testcontainers PostgreSQL(`-Pmujoong.test.db=docker`), 상대 앱은 스텁.\n  - 계약 대조: `ContractFilesTest` · `ErrorCodeTest` · `ConfigKeyContractTest` · `TimeLiteralScanTest` · `OpenApiContracts` · `frontend/tests/u1/shared/timing.test.ts` · `SeedDictionaryFileTest`가 `contracts/`와 코드를 대조한다.\n  - 아키텍처: ArchUnit(`core/arch` · `realtime/arch`), ESLint `/parent` ↔ `/device` ↔ `shared` import 경계.\n  - 끝-끝: `bash scripts/verify.sh` C안(기기 시뮬레이터 + 가짜 공급자).\n  - 인프라: `infra-validate`(Compose 불변식 · `nginx -t` · shellcheck · 마이그레이션 expand 검사), 이미지 점검, 관문 음성 대조.\n- 시험 이름 · 태그: 단위 `*Test`, 통합 `*IT`, 백엔드 유닛 태그 `@Tag(\"u1\")` ~ `@Tag(\"u7\")`와 `@Tag(\"db\")`, 프런트 · tools는 `tests/<유닛>/` 폴더.\n- 관문의 한계: PR의 `ci-gate`는 정적 · 단위 · 통합 · 범위 커버리지 · 보안 · 인프라 · 이미지까지 본다. 끝-끝 회귀(`skeleton`)는 `main` 병합 뒤에 처음 돈다. 그래서 이번 작업은 Build and Test의 전체 `verify.sh`로 병합 전 끝-끝을 확인한다.\n- 시험 · CI · 검증에서는 가짜 공급자만 쓴다(ADR-002).\n- 결과 보고는 테스트 대역 · 합성 대화 · 실제 API · 실제 기기 성공을 따로 적고, 대역 성공을 실공급자 성공으로 표시하지 않는다(`discovered-rules.md` R-M11, Q10-A).\n- 이번 작업 기록의 시험 전략은 Standard다(컴포넌트당 5 ~ 8개 + 핵심 경계 통합 시험)."
    }
  ],
  "obligations": {
    "strategy": "standard",
    "strategy_volume": [
      "Five to eight tests per component.",
      "Unit tests plus integration tests for key boundaries.",
      "Add E2E, performance, or security tests when requirements demand them."
    ],
    "scope_floor": [
      "Keep the existing test suite green.",
      "This scope adds no extra new-test floor beyond the selected test strategy."
    ],
    "combination_rule": "Apply every selected-strategy obligation and every scope-floor obligation; neither replaces the other, and a targeted scope regression may add the narrowest necessary test type beyond the strategy default."
  },
  "plan_profile": {
    "methodology": "test-after",
    "runner_step": "Verify the existing test runner/configuration and record the exact unit-scoped command.",
    "runner_ready_before_first_test": true,
    "testable_layers": [
      "Data model / database behavior",
      "Repository / data access",
      "Business logic",
      "API / endpoint",
      "Frontend behavior"
    ],
    "steps": [
      "Project structure and production configuration skeleton.",
      "Verify the existing test runner/configuration and record the exact unit-scoped command.",
      "Data model / database behavior - implement.",
      "Data model / database behavior - write and run its tests after implementation.",
      "Repository / data access - implement.",
      "Repository / data access - write and run its tests after implementation.",
      "Business logic - implement.",
      "Business logic - write and run its tests after implementation.",
      "API / endpoint - implement.",
      "API / endpoint - write and run its tests after implementation.",
      "Frontend behavior - implement.",
      "Frontend behavior - write and run its tests after implementation.",
      "Environment/build configuration.",
      "Documentation and traceability."
    ]
  },
  "input_sha256": "sha256:cf30d2355aca9923bc50fde838dea7e43f427b9bf61958052caae840662d7c40",
  "contract_sha256": "sha256:5d6b9d7096340e0b040d134b020e781b77463b2dd951572f4b7f4829522c435e"
}
```


