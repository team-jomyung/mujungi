# U15 최종 요구사항 추적

## 입력과 판정

입력은 이 기록의 requirements.md와 U15 code-generation-plan.md, unit-test-instructions.md, code-summary.md, traceability.json이다. 이 기록은 User Stories 단계를 실행하지 않아 자체 3단 AC는 없고 원 기록의 계약을 FR로 다시 묶었다. 다른 유닛은 이번 생성/검증 대상이 아니다.

최종 판정 PASS. 상세 FR 27개, 묶음 FR1~6, NFR1~6 및 원 ID 연결을 확인했다. 코드 생성의 NFR4/NFR6 Deferred는 Build and Test로 넘긴 항목이며 이 단계에서 실제 전체 실행과 문서/커밋 증거로 닫았다. 역사적 인계 상태를 소급해 코드 검토가 인증한 것처럼 바꾸지 않는다. NFR6은 문서·작업 순서 요구이므로 문서와 커밋 확인이 증거이며 행동 시험으로 위장하지 않는다. 현재 미충족 요구사항은 없다.

## ID별 연결

| ID | 최종 상태 | 소유 단계/단위 | 존재하는 대상/증거 | 판정 근거 |
|---|---|---|---|---|
| FR1.1 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR1.2 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR1.3 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR1.4 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR1.5 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR2.1 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15SeedArchiveLoaderTest.java | 전체 실행 포함 시험 통과 |
| FR2.2 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15SeedArchiveLoaderTest.java | 전체 실행 포함 시험 통과 |
| FR2.3 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15SeedArchiveLoaderTest.java | 전체 실행 포함 시험 통과 |
| FR2.4 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15SeedArchiveLoaderTest.java | 전체 실행 포함 시험 통과 |
| FR2.5 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15SeedArchiveLoaderTest.java | 전체 실행 포함 시험 통과 |
| FR3.1 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15RulesTest.java | 전체 실행 포함 시험 통과 |
| FR3.2 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15RulesTest.java | 전체 실행 포함 시험 통과 |
| FR3.3 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15RulesTest.java | 전체 실행 포함 시험 통과 |
| FR4.1 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR4.2 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR4.3 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR4.4 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR4.5 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR4.6 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR4.7 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR5.1 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR5.2 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR5.3 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR5.4 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR5.5 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR6.1 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ConfigurationTest.java | 전체 실행 포함 시험 통과 |
| FR6.2 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ConfigurationTest.java | 전체 실행 포함 시험 통과 |
| NFR1 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| NFR2 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15AccessPolicyTest.java | 전체 실행 포함 시험 통과 |
| NFR3 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15Neo4jIT.java | 전체 실행 포함 시험 통과 |
| NFR4 | OK | Build and Test / U15 | scripts/verify.sh; docs/units/u15/report.md | 전체 실행과 범위 관문으로 인계 종료 |
| NFR5 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ConfigurationTest.java | 전체 실행 포함 시험 통과 |
| NFR6 | OK | Build and Test / U15 | docs/units/u15/design.md; docs/units/u15/implementation.md; docs/units/u15/report.md; git log | 문서 존재·내용 및 네 구현 커밋 순서 직접 확인 |
| FR1 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR2 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15SeedArchiveLoaderTest.java | 전체 실행 포함 시험 통과 |
| FR3 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15RulesTest.java | 전체 실행 포함 시험 통과 |
| FR4 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR5 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR6 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ConfigurationTest.java | 전체 실행 포함 시험 통과 |
| FR8.3 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR9.1 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15RulesTest.java | 전체 실행 포함 시험 통과 |
| FR9.2 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15SeedArchiveLoaderTest.java | 전체 실행 포함 시험 통과 |
| FR9.3 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15AccessPolicyTest.java | 전체 실행 포함 시험 통과 |
| FR9.4 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |
| FR9.5 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ConfigurationTest.java | 전체 실행 포함 시험 통과 |
| FR9.6 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15RulesTest.java | 전체 실행 포함 시험 통과 |
| FR9.7 | OK | u15-knowledge-graph | backend/core-api/src/test/java/kr/mujoong/core/knowledge/U15ServiceIT.java | 전체 실행 포함 시험 통과 |

## 최종 확인

49e7a4a(V8·포트·메모리), a09490f(Neo4j·적재), 7fcaee1(생성·검사), 5945323(삭제·기본 경로) 순서와 후속 수정 커밋을 확인했다. source-manifest의 대상 파일이 존재한다. 전체 운영 소스는 검증 소스 3cee1bbfe4a4에 들어 있으며 이후에는 문서/기록만 바뀌었다. 수치·환경·실호출 한계는 build-and-test-summary.md와 docs/units/u15/report.md를 따른다.
