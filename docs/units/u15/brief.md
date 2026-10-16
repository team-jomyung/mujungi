# U15 지식 그래프 (u15-knowledge-graph) 구현 지시

core 줄의 첫 볼트(B4)다. core 줄은 **U15 → U14 → U10** 순서로 한 담당자가 차례로 한다(`docs/units/README.md` 「U8 ~ U15 문서 동기화 볼트」).
같은 시기에 realtime 줄(U11 → U13 → U12)이 다른 담당자에게서 진행된다. 두 줄은 파일이 겹치지 않는다. 만나는 곳은 U12 ↔ U10 대본 계약(C-DS1)뿐이다.

## 시작 전 상태
- U8(계약 · 버전 리소스)과 U9(Luna 호출부)가 끝났다. 브랜치 `unit/u9-luna-clients`에 U8 커밋까지 함께 있다.
- **브랜치 따는 곳**: U8 · U9가 `main`에 병합됐으면 `main`에서 `unit/u15-knowledge-graph`를 딴다. 아직이면 `unit/u9-luna-clients`에서 따고, U9가 병합된 뒤 `main`을 브랜치로 merge해 따라잡는다(team.md). PR은 U9 PR 다음에 연다.
- 마이그레이션: **core V8**(`V8__u15_knowledge_graph.sql`). core는 지금 V7까지다. U14 = V9, U10 = V10이 뒤를 잇는다.

## 배경
- 화용 지식 그래프(Neo4j)로 목표 후보의 씨앗(`skill_ref`)을 붙이고, 상황 생성에 구조 맥락과 변형 계획을 준다.
- 그래프가 없거나 장애여도 core 기능은 기본 경로로 안전하게 돌아야 한다. 빈 그래프 응답을 '개인화 완료'로 보이지 않는다(FR9.5).

## 범위
정본은 `unit-of-work.md` U15 절과 `bolt-plan.md` B4다. 만들 것:
- **Neo4j 어댑터 + 메모리 대역**(FR9.1 · FR9.7)
  - 아이 그래프 초기화 · 씨앗 요약 목록 · 구조 맥락(`skill_context`) · 변형 계획 · 만든 씨앗 쓰기와 지우기.
  - 모든 질의는 `childId`로 범위를 한정한다.
  - 메모리 대역은 기본 씨앗 목록을 채워 시험 · 개발 · CI에서 쓴다.
- **포트 확장(C-DS11)**: 기존 `kr.mujoong.core.knowledge.KnowledgeGraphLookup`(지금은 `findRelatedBehaviorKeys` 하나, 주석 "never child data")에 아래를 더한다.
  - `seedSummaries` · `skillContext` · `variantPlan` · `writeGeneratedSeed` · `deleteChild`
  - 이 포트가 아이 범위 데이터를 다루게 되므로, 기존 주석과 U5a 쓰임(`PragmaticsKnowledge`)이 깨지지 않게 Functional Design에서 정리한다.
- **공통 씨앗 적재 · 그래프 버전**(FR9.2)
  - 원천은 `aidlc/spaces/default/knowledge/documents/social-comm-poc.zip`이다. 요구사항 문장의 옛 경로(`knowledge/` 바로 아래)에서 옮겨졌다.
  - 이 zip은 커밋해도 된다고 정했다(외부 의존 E5). 다만 `knowledge/documents/`는 사람 소유 원본이라 고치지 않는다. 적재 스크립트가 읽기만 한다.
  - 대형 그래프 원본 `graph_out`은 없다(E6). 씨앗 · 어휘 중심으로 적재한다.
- **새 씨앗 생성 · 검사**(FR8.3, C-DS7)
  - 자유대화 결과 작업 뒤, `skill_ref = NONE`인 후보로 U9 `CoreLunaClient`(`LunaPurpose.SEED_GENERATION`, `ai-seed-output.schema.json`)를 부른다.
  - 검사: 허용 값 · 아동 전사와 5글자 이상 겹침 · 금지 표현 · 중복 · 불변 문장 길이. 실패하면 1회 다시 하고, 또 실패하면 `skill_ref = NONE`을 유지한다. 결과 상태는 바꾸지 않는다.
- **원문 비적재(FR9.3)**: 그래프에는 원문 전사 · 보호자 입력 원문을 넣지 않는다. 원문과 세션 출처는 PostgreSQL에만 둔다.
- **삭제 참여(FR9.4)**: 아동 · 계정 · 기록을 지우면 그 아이의 그래프와 만든 씨앗도 지운다.
  - `DeletionParticipant`에 새 순서 상수를 더한다. 제안값은 `ORDER_KNOWLEDGE_GRAPH = 80`이다(결과 70 뒤, 활동 기록 90 앞). 기존 값 60 · 70 · 90 · 100 · 150 · 200 · 250 · 300 · 900과 겹치지 않으면 Functional Design에서 바꿔도 된다.
- **장애 시 기본 경로(FR9.5)**: 꺼짐 · 장애 · 2초 초과(`mujoong.timing.knowledge.graph-budget`, U8이 이미 더함) · `skillRef = NONE`이면 빈 응답을 준다.

범위 밖: U14의 후보 `skill_ref` 붙이기와 U10의 구조 맥락 사용은 각 단위 몫이다. U15는 포트와 데이터만 준다.

## 정본
- 이 볼트의 인셉션 기록: `R = aidlc/spaces/default/intents/261015-doc-sync-code-update/`
- 원래 서비스 기록: `R0 = aidlc/spaces/default/intents/261008-mujoong-service/`

| 무엇 | 어디 |
|---|---|
| 범위 · 완료 조건 | `R/inception/units-generation/unit-of-work.md` U15 절, `R/inception/delivery-planning/bolt-plan.md` B4 |
| 요구사항 | `R/inception/requirements-analysis/requirements.md` FR9.1 ~ FR9.7 · FR8.3, 열린 항목 1 · 2 |
| 스토리 · 수용 기준 | `R/inception/user-stories/stories.md` US3.2 · US5.1 · US5.2 |
| 컴포넌트 · 결정 | `R/inception/domain-design/components.md` KnowledgeGraph, `decisions.md` |
| 계약 | `R/inception/contract-design/contract-summary.md` C-DS7 · C-DS11 · 4절 열린 질문(`DeletionCompleted.counts`에 그래프 항목을 더할지), `contracts/ai-io.md` 10절(10.3 재시도 분류 · 10.7 비밀 이름 `NEO4J_AUTH`), `contracts/schemas/ai-seed-output.schema.json`, `contracts/timing.md` 13절 |
| 위험 · 외부 의존 | `R/inception/delivery-planning/risk-and-sequencing-rationale.md`, `external-dependency-map.md` E4 · E5 · E6 |
| Luna 호출 방식 | U9 설계 `R/construction/u9-luna-clients/functional-design/`, 편차 `contracts/deviations.md` (U9-a) ~ (U9-c) |
| 기존 코드 | `core/knowledge/`(`KnowledgeGraphLookup` · `PragmaticsKnowledge` · `KnowledgeProperties`), `application.yml` `mujoong.knowledge.neo4j.enabled: false`, `infra/compose.demo.yml`의 Neo4j, `common/deletion/DeletionParticipant`, `core/luna/CoreLunaClient` |

## 정해 둔 값
- 마이그레이션 core `V8__u15_knowledge_graph.sql`: `generated_seed`와 그래프 버전 표. 더하기만 한다. 기존 표에 제약을 더하면 `infra/migration-approvals.txt`에 근거 한 줄을 적는다(R-M3).
- 비밀: 새 비밀을 만들지 않는다. Neo4j는 기존 `NEO4J_AUTH`를 쓴다. 새로 생기면 R-M9 목록 전부를 같은 커밋에서 고친다.
- 시간 값: `knowledge.graph-budget`(2s)은 이미 있다. 새 키가 필요하면 R-M5 세 곳(서버 전용 키는 timing-defaults 제외, 편차 (U8-c))을 함께 고친다.
- 시험: 태그 `u15`, 범위 `backend/coverage-scope/u15.txt`, 범위 줄 80%.
  - Neo4j 통합 시험은 Testcontainers 또는 개발용 인스턴스(E4)로 돈다. CI · `verify.sh`는 메모리 대역 또는 Testcontainers만 쓴다.
  - Luna는 가짜 공급자만 쓴다(R-F1). 실호출은 `lunaLiveTest`로 사람이 직접 하고 따로 보고한다(R-M11, 편차 (U9-c)).
- 건드리지 않는 것: `backend/realtime-activity`, 프런트, 다른 단위의 시험 단언과 커버리지 하한(R-F2).

## 다른 단위와 맞물리는 곳
1. **U14(다음 볼트)**: 목표 후보의 `skill_ref`를 `seedSummaries` 목록으로 검증한다. 새 씨앗 생성은 U14의 결과 작업 뒤에 일어나므로, 부르는 시점은 U14가 정하고 U15는 생성 · 검사 서비스를 준다.
2. **U10**: `skillContext` · `variantPlan`을 상황 생성 입력과 변형 장면 보존 판정(FR9.6)에 쓴다.
3. **U9**: `CoreLunaClient.call(LunaRequest.of(SEED_GENERATION, …))`로 부른다. 금지 키 정리와 '아이' 치환은 U9가 한다. 입력에는 최소 필드만 싣는다.
4. **삭제(U2)**: 기존 `DeletionCommander` 참가자 틀을 따른다.

## AI-DLC로 진행하는 법
작업 기록은 **자기 PC에서 새로** 만든다. 승인 증빙이 PC별이어서 남의 기록은 이어 갈 수 없다.
1. 저장소를 받고 위 「브랜치 따는 곳」대로 `unit/u15-knowledge-graph`를 만든 뒤 아래를 입력한다.

   ```
   /aidlc --new-intent --scope refactor "U15 지식 그래프(u15-knowledge-graph) 구현. 지시 docs/units/u15/brief.md를 정본으로 한다. 인셉션 산출물은 261015-doc-sync-code-update 기록의 것을 그대로 쓴다."
   ```

2. 작업 기록 이름은 `u15-knowledge-graph`로 한다.
3. 배포 2단계를 뺀다. `/aidlc compose "deployment-pipeline과 deployment-execution을 빼줘"`를 입력하고 승인한다.
4. `/aidlc --depth Standard --test-strategy Standard`를 입력한다.
5. 단계: 기존 코드 파악 → Requirements Analysis → Functional Design → Code Generation → Build and Test.
   - Requirements Analysis는 새 업무 요구사항을 만들지 않는다. `261015-doc-sync-code-update`의 FR9 · FR8.3 · US3.2 · US5.1 · US5.2를 구현 단위로 다시 묶고, 원래 ID는 '원 FR9.1'처럼 구분한다.
   - Functional Design의 첫 작업은 실제 `contracts/` 파일과 `KnowledgeGraphLookup` 기존 쓰임을 대조하는 것이다.
   - Functional Design 결과는 `docs/units/u15/design.md`에도 옮긴다.
   - Code Generation 계획은 이 순서로 나누고 단계마다 `feat(u15): …`로 커밋한다.
     1. V8 + 포트 확장 + 메모리 대역
     2. Neo4j 어댑터 + 씨앗 적재 스크립트
     3. 새 씨앗 생성 · 검사
     4. 삭제 참여 + 기본 경로
6. 자기 기록 폴더(`aidlc/spaces/default/intents/<날짜>-u15-knowledge-graph/`)는 브랜치에 함께 커밋한다.

## 끝나면
`docs/units/u15/report.md`에 아래를 쓰고 `main`으로 PR을 연다. `ci-gate`가 초록이면 합친다(R-M1).
- 만든 것, 시험 결과(건수 · 커버리지)
- 계약 편차 `(U15-a)`, 가정, 설계와 달라진 곳
- 증거는 종류별로 따로 적는다: 메모리 대역 / Neo4j Testcontainers / 개발용 Neo4j / 실호출(R-M11)
- 남은 일
