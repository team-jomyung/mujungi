# U15 코드 구현 계획

## Summary

- Builds: 아동별 지식 그래프, 구조 보존 변형, 검증된 씨앗 생성과 삭제 복구.
- Touches: core-api knowledge·luna·child·account, V8 마이그레이션, 계약·설정·U15 문서.
- Tests: 컴포넌트당 5~8개 단위 시험과 PostgreSQL·Neo4j·가짜 AI 경계 통합 시험, U15 줄 커버리지 80%.

## Sources

현재 기록의 inception/requirements-analysis/requirements.md, inception/units-generation/unit-of-work.md·unit-of-work-story-map.md와 construction/u15-knowledge-graph/functional-design/entities.md·rules.md·functional-spec.md·traceability.json을 따른다. contracts/knowledge-graph.md와 docs/units/u15/brief.md가 구현 경계를 정한다. 별도 사용자 스토리는 없으며 FR/BR 동번호로 추적한다.

## 구현 순서

- [x] Step 1: 기존 Gradle/JUnit/Testcontainers 실행 환경과 U15 필터를 확인한다. 기존 LunaSchemasTest 기준선은 통과했다. 실제 키 없이 시험하고 기존 계약·ArchUnit 단언을 보존한다.
- [x] Step 2: 아동 그래프·씨앗·5필드 서명·변형 계획·생성 결과와 저장 포트를 구현한다. V8에 아동 상태, 생성 예약, 성공 원장, 기록 삭제 표식을 추가한다. 기존 KnowledgeGraphLookup 단일 추상 메서드와 생성자를 유지한다. FR1·2·4·5.
- [x] Step 3: 모델 불변식과 V8 제약/원장 멱등성 시험을 작성·실행한다. 정상·중복·다른 아동·한도 2개·번호 재사용 금지를 확인한다.
- [x] Step 4: 안전한 ZIP 기본 자료 로더, 메모리 구현, JDBC 원장, 명시적 트랜잭션 Neo4j 어댑터를 구현한다. 기본 14개 검증, 판·해시 충돌 거절, 파라미터 바인딩과 생성 자료 보존을 적용한다. FR2.
- [x] Step 5: 로더와 저장소 단위/통합 시험을 작성·실행한다. 경로 탈출·크기·중복 충돌·잘못된 어휘, 실제 Neo4j 트랜잭션 종료·재연결·아동 격리를 확인한다.
- [x] Step 6: 접근 권한·현재 동의·삭제 상태 검사, 초기화, 활성 목록과 맥락 조회, 2초 제한과 SAME_CONTEXT/NEAR 변형을 구현한다. FR1·3·6.
- [x] Step 7: 접근/변형 시험을 작성·실행한다. 노출 2/3회, 정확히 한 요소 변경, 최근 중복, FAR 금지, 장애 빈 결과, 늦은 읽기의 쓰기 금지를 검증한다.
- [x] Step 8: seed-generation.v2 프롬프트, 공유 Luna 시도 예산, 내용 정규화·개인정보·금지·원문 겹침 검사, 중복 재사용, T1/T2/T3 생성과 중단 회수를 구현한다. FR4.
- [x] Step 9: 생성 단위/통합 시험을 작성·실행한다. 4/5글자 겹침, 60/61 코드포인트, 스키마/내용 공유 수정 슬롯, 기술 재시도, 전체 3회, 최초 시한, 동시 요청, 그래프 성공 뒤 DB 실패를 검증한다.
- [x] Step 10: 아동 등록 성공 후 초기화 연결, 순서 80 계정 삭제 참가자, 아동/기록 삭제 포트와 내구성 있는 삭제 의도를 구현한다. 그래프 꺼짐과 과거 자료 존재를 구분한다. FR1·5·6.
- [x] Step 11: 등록 실패 격리·삭제 재시도·삭제 중 늦은 응답·다른 기록 보존·설정 꺼짐·키 없는 기동 통합 시험을 작성·실행한다.
- [x] Step 12: 설정과 계약·비밀 이름 목록·환경 예시를 일치시키고 U15 커버리지 범위 및 증거를 정리한다. 시간 값 변경 시 두 앱·timing 계약·프런트 정본을 함께 반영한다. 신규 공개 HTTP·프런트 구현·배포는 없다.
- [x] Step 13: 코드 요약, FR/BR 추적표, source-manifest.json, docs/units/u15 구현·검증 문서를 작성하고 검토를 받는다. 이후 Build and Test에서 전체 회귀와 verify.sh를 실행하고 검증된 변경을 커밋·push한다.

## 검증 기준

메모리/Neo4j 공통 계약, 실제 PostgreSQL/Neo4j 장애 경계, 가짜 AI 공급자만 사용한다. 모듈 및 U15 줄 80%를 낮추지 않는다. 기존 수동 스택은 건드리지 않고 별도 시험 컨테이너만 사용한다. 성공 원장 없는 그래프 자료는 조회에서 숨기고 삭제가 불명확하면 재시도한다.

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

