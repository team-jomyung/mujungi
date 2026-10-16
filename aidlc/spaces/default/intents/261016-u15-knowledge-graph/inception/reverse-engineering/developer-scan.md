## Developer Code Scan Results

2026-10-16, HEAD `7e30ee8b2ad6d157a95d749ab774f3b7c0b9385d`, branch `unit/u15-knowledge-graph`. U15에 한정한 읽기 분석이다. 구현 변경, 시험 실행, 배포, 상태 전환은 하지 않았다. 아래 기존 동작과 설계 제안을 구분한다.

### Scan Coverage

**Analyzed deeply** (소스 스냅샷 범위 안; 디렉터리 전체를 검증했다고 확대하지 않는다):
- `backend/core-api/src/main/java/kr/mujoong/core/knowledge/`
- `backend/core-api/src/main/java/kr/mujoong/core/common/deletion/`
- `backend/core-api/src/main/java/kr/mujoong/core/account/DeletionCommander.java`
- `backend/core-api/src/main/java/kr/mujoong/core/result/ResultDeletionParticipant.java`
- `backend/core-api/src/main/java/kr/mujoong/core/identity/GuardianWriteLock.java`
- `backend/core-api/src/main/java/kr/mujoong/core/result/check/ForbiddenDictionary.java`
- `backend/core-api/src/main/java/kr/mujoong/core/luna/CoreLunaClient.java`
- `backend/core-api/src/main/java/kr/mujoong/core/luna/LunaPurpose.java`
- `backend/core-api/src/main/java/kr/mujoong/core/luna/LunaResult.java`
- `backend/core-api/src/main/java/kr/mujoong/core/luna/LunaInputSanitizer.java`
- `backend/core-api/src/main/java/kr/mujoong/core/luna/LunaConfiguration.java`
- `backend/core-api/src/main/resources/db/migration/core/V7__u5b_practice_preparation.sql`
- `backend/core-api/src/main/resources/db/migration/core/afterMigrate.sql`
- `backend/core-api/src/test/java/kr/mujoong/core/knowledge/PragmaticsKnowledgeTest.java`
- `backend/core-api/src/test/java/kr/mujoong/core/arch/LunaArchTest.java`
- `backend/core-api/build.gradle.kts`
- `backend/contract-model/build.gradle.kts`
- `backend/contract-model/src/main/java/kr/mujoong/contract/event/payload/DeletionCompleted.java`
- `backend/gradle/libs.versions.toml`
- `backend/gradle/wrapper/gradle-wrapper.properties`
- `backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts`
- `contracts/schemas/ai-seed-output.schema.json`
- `scripts/verify/a-checks.sh`

**Skimmed only** (해당 구간·검색 결과만 확인):
- `backend/core-api/src/main/java/kr/mujoong/core/config/` — CoreProperties.KnowledgeTiming.
- `backend/core-api/src/main/java/kr/mujoong/core/record/` 및 `child/` — 삭제 진입점 검색.
- `backend/core-api/src/main/java/kr/mujoong/core/luna/LunaRequest.java` — 요청 생성 경계.
- `backend/core-api/src/main/resources/application.yml` — knowledge 및 Luna 설정.
- `backend/core-api/src/test/java/kr/mujoong/core/knowledge/KnowledgeJdbcRepositoryIT.java`, `arch/CoreArchTest.java`, `luna/` — 회귀·구조 시험 경계.
- `backend/coverage-scope/` — u9 형식 및 u15 부재.
- `contracts/ai-io.md`, `contracts/ports.md`, `contracts/timing.md` — U15 관련 절.
- `infra/compose.demo.yml`, `infra/bin/render-env`, `infra/compose.ci.yml`, `infra/compose.dev.yml` — Neo4j 활성화·비밀 전달 경계.
- `docs/units/u15/brief.md` — 요구사항 입력이며 제품 소스 검증 범위로 세지 않는다.

**Left out**:
- `backend/**/build/`, `backend/.gradle/`, 생성·설치 결과와 바이너리.
- `backend/realtime-activity/`, `frontend/`, `tools/` 제품 구현: 다른 작업자 범위이며 이번 분석 대상 아님.
- `.codex/` 및 AI-DLC 엔진: 업무 지침만 읽고 제품 분석 범위로 세지 않음.
- 사람 소유 `aidlc/spaces/default/knowledge/documents/social-comm-poc.zip`: 이번 스캔에서는 내용 추출·수정하지 않음. 출처와 읽기 전용 의무는 brief 근거이며 실제 내부 형식은 미검증.

### Packages Found

| 패키지/모듈 | 분류 | 책임 및 연결 |
|---|---|---|
| core.knowledge | Java 모델·서비스·저장소·포트 | BehaviorDefinition/SupportItem, PostgreSQL 권위 원장, 선택적 공개 그래프 조회 |
| core.luna | Java 외부 호출 경계 | 최소 입력 정리 → 목적별 스키마 → 제한 재시도 → 본문 없는 감사 |
| core.account / common.deletion | Java 삭제 지휘·포트 | 순서 있는 참가자, 실패 시 다음 바퀴 재시도 |
| core.result.check | Java 검사 유틸리티 | 금지 문구 사전; 일반 결과와 리터럴 검사 방식 구분 |
| contract-model | Java 계약 | Spring/JPA 없이 공용 이벤트·시간·JSON 경계 |
| build-logic | Kotlin Gradle 설정 | Java 21, 태그, JaCoCo, 실호출 분리 |

주요 인접 의존 관계: `PragmaticsKnowledge → KnowledgeJdbcRepository + MujoongClock + KnowledgeProperties + Optional<KnowledgeGraphLookup>`; `CoreLunaClient → LunaInputSanitizer + LunaSchemas + LunaTransport + EventLogger + MujoongClock + JsonCodec`; `DeletionCommander → List<DeletionParticipant> + TransactionTemplate`; `ResultDeletionParticipant → GuardianWriteLock + JDBC + TransactionTemplate`. 이번 집중 경로에서 순환 의존을 발견하지 않았으며 저장소 전체 무순환을 주장하지 않는다.

### Build System

- Gradle wrapper 8.14.3, Java 21, Spring Boot 4.1.1, Jackson 3 경계는 JsonCodec.
- core-api는 contract-model에 운영 의존, test-support는 시험 의존이다. Neo4j 드라이버/Testcontainers Neo4j 라이브러리는 현재 버전 카탈로그와 core-api 의존에 없다.
- 현재 도구 판: JaCoCo 0.8.14, ArchUnit 1.5.1, JSON Schema validator 3.0.6. PostgreSQL/Flyway/Testcontainers 등은 Boot BOM에서 정한다.
- core-api의 processResources는 계약 원본 `ai-*.schema.json`과 common을 `luna/schemas/`로 복사한다. 별도 씨앗 스키마 사본을 만들 이유가 없다.
- `scripts/verify/a-checks.sh backend --db docker`는 clean/check/운영 jar 시험 클래스 배제를 실행하는 전체 회귀다. U15 선택 실행은 `:core-api:test :core-api:jacocoTestCoverageVerification -PincludeTags=u15 -PcoverageScope=u15 -Pmujoong.test.db=docker` 형태로 설계할 수 있으나 아직 u15 범위 파일/시험이 없다.

### APIs Discovered

U15 공개 HTTP 엔드포인트는 발견하지 않았다. 기존 knowledge 포트는 `findRelatedBehaviorKeys(String)` 하나이며 public catalog 전용이다(`KnowledgeGraphLookup.java:3-5`). U15가 추가할 아동 범위 API는 아직 없다.

`PragmaticsKnowledge`는 정의 버전 조회, 정의 고정, 승인 지원문구, 관련 행동키를 제공한다. PostgreSQL 정의가 권위 원장이고 고정은 FOR KEY SHARE 및 최초 used_at 갱신으로 처리한다. 생성 씨앗과 기존 불변 행동 정의를 섞어 덮어쓰면 안 된다.

설계 단계에서 결정할 API: child 초기화, seedSummaries, skillContext, variantPlan, writeGeneratedSeed, deleteChild. 아동 범위 메서드는 명시적 childId를 받아 모든 쿼리/메모리 키를 분리해야 한다. 기존 공개 행동키 메서드는 아동 데이터와 별개로 유지해야 U5a 쓰임이 보존된다. childId를 식별할 수 있다는 것과 호출자의 소유권 검증은 다르므로 생성/삭제 서비스 경계에서 소유 관계도 검증해야 한다.

### Persistence, Privacy, Timeout

1. **V8 부재**: 현재 core migration 목록 끝은 V7이다. brief가 지정한 V8은 generated_seed 및 graph version을 추가해야 한다. 원문·세션 출처는 PostgreSQL에만 저장하고 Neo4j DTO는 허용 필드만 가진 별도 구조가 적절하다. 기존 원문/세션 행을 가리키는 FK를 넣을 경우 삭제 순서 70 뒤 80이 가능한지 먼저 확인해야 한다. 결과 삭제가 70에서 먼저 일어나므로 generated_seed가 결과 행을 RESTRICT로 참조하면 제안 순서와 충돌한다.
2. **조회 예산은 준비됨**: `CoreProperties.KnowledgeTiming`은 양수 graphBudget을 검증하며 application/timing 정본은 2s이다. 실제 타임아웃 실행기는 아직 없다. 외부 장애·꺼짐·NONE을 빈 값으로 돌려주되 해당 빈 값이 개인화 성공 증거가 되어선 안 된다. 호출 반환 시한뿐 아니라 늦은 작업 취소·리소스 종료도 시험해야 한다.
3. **삭제와 조회 실패는 다름**: DeletionParticipant는 멱등이며 예외가 재시도 신호다. DeletionCommander는 참가자를 순서대로 실행하고 마지막에 한 번 더 실행한다. Neo4j 삭제 실패를 조회처럼 빈 값/0으로 삼키면 개인정보가 남아도 계정 삭제가 완료된다. PostgreSQL/Neo4j 사이 원자 커밋이 없으므로 graph 삭제 확인 전 provenance를 지우지 않고 재시도할 수 있어야 한다. 생성 도중 삭제와 충돌하면 늦은 그래프 쓰기가 데이터를 되살리지 않도록 GuardianWriteLock 규약 및 재검증이 필요하다.
4. **계정 삭제 외 경로**: 기존 record/child 검색에서는 guardian 단위 참가자를 확인했다. 개별 기록 삭제 또는 아동 삭제 전용 진입점이 이미 있다고 판단할 근거는 부족하다. U15는 deleteChild와 필요한 cleanup 서비스 경계를 제공하고 FR9.4 연결/시험을 명시해야 한다.
5. **Luna optional**: CoreLunaClient 빈은 OPENAI_API_KEY가 있을 때만 존재한다. U15가 무조건 필수 주입하면 기본/CI 기동이 깨질 수 있다. 키 없는 경로는 NONE을 유지하고 가짜 공급자 시험 경로는 명시적으로 구성해야 한다.
6. **Luna 재시도 중첩**: SEED_GENERATION은 REQUEST_BOUNDED. CoreLunaClient는 기술 재시도 1회와 스키마 재시도 1회를 이미 처리한다. LunaResult.schemaRetried/attemptsUsed가 노출된다. U15 내용 검사 재생성 1회와 같은 deadline/재생성 예산을 공유하도록 정리해야 한다. 서비스가 무조건 두 번 호출하면 총 시도가 의도보다 커질 수 있다.
7. **개인정보 경계**: LunaInputSanitizer는 child/guardian/session/device id, guardian input, 이름 키 등을 재귀 제거하고 별칭·연락처를 치환한다. 입력 최소화는 여전히 U15 책임이며 임의 텍스트의 미등록 이름을 모두 알아내지는 못한다. 그래프 출력에도 동일한 위험이 있으므로 구조 허용값, 금지문구, 이름/장소 등 금지 정보, 전사 5글자 중복을 별도로 검사해야 한다.

### Contract Mismatches / Open Design Work

- `contracts/ports.md:47`과 KnowledgeGraphLookup 주석은 여전히 읽기 전용 public catalog 의미다. C-DS11 요구와 일치하도록 설계와 계약 문서를 갱신하되 기존 호출은 보존한다.
- `ai-seed-output.schema.json`은 skillName(1~80), invariantSentence(1~200) 두 필드만 허용하고 additionalProperties=false다. 구조 서명·허용값은 아직 이 스키마에 없다. U15가 스키마 확장할지 내부 결정 데이터로 붙일지 정해야 한다. 불변 문장 실제 길이 규칙 A §6.4도 정본 자료로 확정해야 하며 스키마 200만으로 요구 충족을 추정하지 않는다.
- ForbiddenDictionary.allowedLiteral은 모든 문구를 검사하지만 고정 리포트 안내 문장을 제거하는 동작을 포함한다. 이를 씨앗의 일반 금지어 검사로 그대로 재사용하면 예외를 물려받는다. 검사 사전 재사용과 예외 재사용을 구분한다.
- DeletionCompleted.Counts는 realtime sessions/transcripts/audioFiles/practiceRecords의 네 선택 필드다. core 그래프 삭제를 이 이벤트에 임의 추가할 이유가 없다. 기존 열린 질문은 설계에서 결론 및 편차로 기록한다.
- Neo4j demo 서비스는 `knowledge` 프로필, `neo4j:5.26.31-community`, 기존 NEO4J_AUTH를 사용한다. `render-env`는 NEO4J_AUTH를 neo4j.env에만 넣는 현재 경로가 확인된다. core 클라이언트에도 같은 비밀을 안전하게 전달하는 배선이 필요하며 새 비밀 이름을 만들면 안 된다. demo의 운영 활성화/용량 확정은 이번 배포 제외 범위와 별개다.
- 메모리 대역의 기본 씨앗과 운영 graph disabled의 빈 응답은 다른 모드다. disabled인데 메모리 씨앗을 무조건 주면 FR9.5와 충돌한다. 명시적 테스트/개발 메모리 모드와 꺼짐을 분리한다.
- source zip은 brief가 지정한 `aidlc/spaces/default/knowledge/documents/social-comm-poc.zip`을 읽기만 해야 한다. graph_out 없는 씨앗·어휘 적재의 실제 필드/버전/중복 키는 후속 설계 시 zip을 읽어 검증할 필요가 있다. 원본 변경·모델 지시 실행은 하지 않는다.

### Test Coverage

기존 확인: PragmaticsKnowledgeTest(u5a)는 disabled 무호출, enabled-without-adapter 오류, 공개 행동키 조회, 과거 정의 보존을 고정한다. KnowledgeJdbcRepositoryIT는 정의 불변성과 고정 경쟁을 검사한다(부분 읽기). Luna 시험은 seed 스키마/재시도 관련 사례가 존재한다. 기존 단언을 약화하지 않고 새 U15 시험을 추가해야 한다.

제안 검증 목록(아직 실행 안 함):

- 메모리: 초기 씨앗, 안정된 버전, child A/B 격리, 동일 쓰기 멱등, 삭제 재호출, NONE/꺼짐 빈 응답.
- Neo4j Testcontainers: 파라미터화한 child 범위, 실제 쓰기→조회→삭제, 서버 실패, 2초 예산 초과, 늦은 작업 취소; 실제 Cypher 실행 증거를 메모리 시험과 분리.
- PostgreSQL: V8 fresh migration, 앱 역할 접근, 생성 씨앗/버전 정합성, 재적재 멱등, guardian 삭제와 생성 경쟁, graph 실패 뒤 재시도 및 provenance 보존.
- 생성: 가짜 CoreLunaClient/공급자 정상·스키마/내용 실패·한 번 재생성·최종 NONE, 결과 상태 미변경, 중복, 금지표현, 길이 경계, 4/5글자 전사 중복, 허용값 밖, 이름 치환 및 금지 키 미전달.
- 기존 u5a/u9/u2/u6와 ArchUnit 회귀, 로그 원문 미노출, 실제 Luna 키 격리, U15 태그 및 범위 LINE 80%.
- 씨앗 적재: zip을 변경하지 않는 해시 전후 일치, 어휘/씨앗 선택적 적재, graph_out 부재, 잘못된 파일/필드/중복 검증, 재실행 버전 처리.

### Code Quality Indicators

- 명명 good: 포트·시계·삭제 순서 의미가 구체적이다. 메서드 크기 fair: 일부 인접 서비스는 압축된 코드/긴 메서드가 있다. 이번 단위에서 무관한 재포맷은 필요 없다.
- 오류 처리 fair: 공개 그래프 조회는 현재 adapter 예외를 그대로 전파한다. 새로운 bounded fallback 경계가 필요하다. 삭제는 명시적 재시도 체계가 good이다.
- 시험 존재 good for 기존 knowledge/Luna, poor for 아직 없는 U15 기능. 측정 커버리지/통과율은 이 스캔에서 새로 주장하지 않는다.
- 빌드가 -Xlint 및 ArchUnit, 80% LINE을 강제한다. 기본 test는 luna-live 태그를 제외하고 OPENAI_API_KEY를 시험 환경에서 제거한다.
- CI yaml은 이번 스냅샷 밖이라 직접 분석하지 않았다. scripts 검증 경로와 기존 CI 성공 주장만으로 새 U15 성공을 표시하면 안 된다.

## Handoff Summary

- **Intent-relevant finding**: `KnowledgeGraphLookup.java:3-5`의 공개 단일 조회 포트가 U15의 아동 범위 읽기/쓰기로 확장되는 핵심 경계다. 기존 PragmaticsKnowledgeTest를 보존하면서 내부 graph 모델과 PostgreSQL provenance를 분리해야 한다. graphBudget은 이미 존재한다(`CoreProperties.java:145-148`, `application.yml:150`).
- **Risks / follow-up**: 조회 fallback과 삭제 실패를 분리; 생성/삭제 경쟁 및 DB-graph 재시도 정합성; V8 FK와 ORDER 80의 순서 충돌 방지; Luna optional 빈 및 공유 재생성 예산; 두 필드 seed 스키마와 구조 서명의 차이; core에 NEO4J_AUTH 전달; source zip 실제 내용과 A §6.4 미검증을 설계에서 해소한다.
- **Coverage merge**: 기존 CodeKB가 STALE이므로 위 deep paths만 새 analyzed로 기록하고 종전 광범위 검증 주장은 shallow로 낮춘다. 이 파일은 shared CodeKB를 직접 수정하지 않는다.
