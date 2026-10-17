## Developer Code Scan Results

### Scan Coverage
- **Analyzed deeply**:
  - backend/core-api/src/main/java/kr/mujoong/core/practice/CandidateRegistry.java
  - backend/core-api/src/main/java/kr/mujoong/core/practice/PracticeGoalRepository.java
  - backend/core-api/src/main/java/kr/mujoong/core/practice/PracticeDeletionParticipant.java
  - backend/core-api/src/main/java/kr/mujoong/core/result/SessionResultHandler.java
  - backend/core-api/src/main/java/kr/mujoong/core/result/ResultAiSchema.java
  - backend/core-api/src/main/java/kr/mujoong/core/result/ResultData.java
  - backend/core-api/src/main/java/kr/mujoong/core/result/JobRunner.java
  - backend/core-api/src/main/java/kr/mujoong/core/result/ResultQueryService.java
  - backend/core-api/src/main/java/kr/mujoong/core/result/SceneChipMapper.java
  - backend/core-api/src/main/java/kr/mujoong/core/result/SceneJudgmentRepository.java
  - backend/core-api/src/main/java/kr/mujoong/core/result/CandidateScreen.java
  - backend/core-api/src/main/java/kr/mujoong/core/result/ResultDeletionParticipant.java
  - backend/core-api/src/main/java/kr/mujoong/core/report/ReportAssembler.java
  - backend/core-api/src/main/java/kr/mujoong/core/knowledge/ChildKnowledgeService.java
  - backend/core-api/src/main/java/kr/mujoong/core/knowledge/SeedGenerationService.java
  - backend/core-api/src/main/java/kr/mujoong/core/knowledge/StructureSignature.java
  - backend/core-api/src/main/resources/db/migration/core/V4__u5a_practice_core.sql
  - backend/core-api/src/main/resources/db/migration/core/V5__u6_results_report.sql
  - contracts/schemas/ai-goal-candidate-output.schema.json
  - contracts/schemas/ai-rejudgment-output.schema.json
  - contracts/schemas/practice-record-v2.schema.json
  - contracts/schemas/session-result-response.schema.json
  - contracts/README.md
  - frontend/src/parent/api/results.ts
  - frontend/src/shared/timing/timing-defaults.json
  - infra/migration-approvals.txt
- **Skimmed only**: application timing blocks in both apps; contracts ai-io/timing; core test and coverage-scope directories (two representative test bodies); knowledge adapter filenames. Entire package or entire contracts/ deep coverage is not claimed.
- **Left out**: build/, .gradle/, node_modules/, generated outputs and caches; AI-DLC harness/install and record workspace except instructions, prior knowledge and required upstream specification documents. Realtime implementation, 27 screen implementation, other frontend areas and infrastructure internals were not opened for this focused application scan.

### Packages Found
- core.practice — Java service/repository/controller feature; candidate registry and goal selection.
- core.result — Java job pipeline, validators, persisted judgments and parent query.
- core.report — Java report projection; focused ReportAssembler only.
- core.knowledge — Java U15 integration services; adapters exist but not deeply rechecked.

### Build System
- **Type**: existing Gradle Java backend and npm TypeScript frontend, inherited and not rebuilt in this scan.
- **Config Files**: existing module/build conventions remain historical shallow knowledge.
- **Build Dependencies**: core-api uses contract-model; tests use test-support (inherited).

### APIs Discovered
- Existing result query uses fixed five display chips and omits raw timing.
- CandidateRegistry/PracticeGoalRepository own legacy behavior-pinned recommendations; no SavedGoalReader declaration found in searched practice/result surface.
- U15 ChildKnowledgeService.seedSummaries(guardian,child) and skillContext(guardian,child,seedId) are implemented. SeedGenerationService.Request requires StructureSignature.

### Frameworks & Libraries
- Spring services, JDBC named parameters and TransactionTemplate in the read source; JsonCodec boundary. Library versions remain previous shallow record; no dependency freshness claim.

### Test Coverage
- **Test Directories**: core-api/src/test/java/.../result and practice; existing U6/U5a/U5b coverage.
- **Test Frameworks**: JUnit, Mockito/AssertJ from representative tests and existing records.
- **Coverage Config**: backend/coverage-scope/u6.txt and u15.txt present; u14.txt absent. No tests or coverage executed by this scan.

### Code Quality Indicators
- **Linting**: previous Java ArchUnit/javac and frontend ESLint knowledge preserved, not re-executed.
- **CI/CD**: U15 CI green per parent-provided evidence; this is not U14 validation.
- **Documentation**: U14 brief and four strict schemas provide concrete target; contracts README governs breaking changes.

### Technical Debt Signals
- SessionResultHandler:121 loops scene<=2; :150 template says two scenes; ResultAiSchema judgment rejects >2 and excludes AFTER_REASK.
- ReportAssembler:34 renders SceneChipMapper.text, merging report meaning with 27 chip projection.
- V5:7 attempt_count 0..3 and :17 attempt_no 1..3 both need review for four total attempts. V5:51 scene_no IN(1,2), :54 timing only IMMEDIATE/AFTER_HELP.
- V4 recommendation and selection behavior-key/version NOT NULL + composite FK mean free-generated goals need compatible additive ownership/storage design.
- Public schema/DTO exposes reasonText; frontend results parser requires it. Proposed internal reasonText removal must coordinate schema/parser; displayEvidence→existing UI model mapping is an option without changing 27. Do not leak raw AI reasonText as a compatibility shortcut.
- New practice-record-v2 flowVersion distinguishes format; snapshotVersion must keep existing semantics. Unknown facts must be ignored per contract description.
- New goal-candidate output has fourElements but no U15 five-field StructureSignature. Resolve safe internal model/schema input rather than inventing a signature from constants.
- Migration approval list explicitly requires a real human reviewer and statement hashes; do not fabricate signatures.

## Handoff Summary
- **Intent-relevant finding**: U14 has working legacy candidate/result infrastructure but fixed behavior definitions, two-scene parsing, shared report/chip projection and three-attempt DB constraints conflict with new contracts. U15 service boundary is already implemented at HEAD1a4ef7b; old CodeKB pre-U15 absence claims are historical.
- **Risks / follow-up**: preserve guardian→job→report locks, lease/idempotency and durable pending candidate replay; child-scope/consent checks; legacy read compatibility and deletion order70/150; generation failures must not change result status. Resolve strict schema and frontend parser compatibility plus U15 structure gap in Functional Design.
