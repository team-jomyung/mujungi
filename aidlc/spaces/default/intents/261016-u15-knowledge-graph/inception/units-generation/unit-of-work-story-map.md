# U15 요구사항 배정

## 기준과 전체 매핑

현재 ../requirements-analysis/requirements.md의 구체적 FR을 열거한다. 현재 stories.md는 없으며 원 기록의 스토리는 참고 출처다. unit-of-work.md, unit-of-work-dependency.md 및 기존 domain-design/components.md·decisions.md의 KnowledgeGraph 경계를 따른다.

| Requirement | Unit ID | Directory |
|---|---|---|
| FR1.1 | U15 | u15-knowledge-graph |
| FR1.2 | U15 | u15-knowledge-graph |
| FR1.3 | U15 | u15-knowledge-graph |
| FR1.4 | U15 | u15-knowledge-graph |
| FR1.5 | U15 | u15-knowledge-graph |
| FR2.1 | U15 | u15-knowledge-graph |
| FR2.2 | U15 | u15-knowledge-graph |
| FR2.3 | U15 | u15-knowledge-graph |
| FR2.4 | U15 | u15-knowledge-graph |
| FR2.5 | U15 | u15-knowledge-graph |
| FR3.1 | U15 | u15-knowledge-graph |
| FR3.2 | U15 | u15-knowledge-graph |
| FR3.3 | U15 | u15-knowledge-graph |
| FR4.1 | U15 | u15-knowledge-graph |
| FR4.2 | U15 | u15-knowledge-graph |
| FR4.3 | U15 | u15-knowledge-graph |
| FR4.4 | U15 | u15-knowledge-graph |
| FR4.5 | U15 | u15-knowledge-graph |
| FR4.6 | U15 | u15-knowledge-graph |
| FR4.7 | U15 | u15-knowledge-graph |
| FR5.1 | U15 | u15-knowledge-graph |
| FR5.2 | U15 | u15-knowledge-graph |
| FR5.3 | U15 | u15-knowledge-graph |
| FR5.4 | U15 | u15-knowledge-graph |
| FR5.5 | U15 | u15-knowledge-graph |
| FR6.1 | U15 | u15-knowledge-graph |
| FR6.2 | U15 | u15-knowledge-graph |
| NFR1 | U15 | u15-knowledge-graph |
| NFR2 | U15 | u15-knowledge-graph |
| NFR3 | U15 | u15-knowledge-graph |
| NFR4 | U15 | u15-knowledge-graph |
| NFR5 | U15 | u15-knowledge-graph |
| NFR6 | U15 | u15-knowledge-graph |

## 내부 구현 묶음과 교차 관심사

1. V8·포트·메모리: FR1, FR2.4 기반 및 기존 조회 호환.
2. Neo4j·적재: FR2 전체와 FR3, 아동별 투영·재구성.
3. 생성·검사: FR4, 소유권·동의 재확인과 원장/그래프 무결성.
4. 삭제·기본 경로: FR5·FR6, 전체 경로 회귀시험.

FR1.5와 NFR1~NFR6은 모든 묶음에 적용한다. 이 구분은 시험과 커밋 경계이며 앞 묶음에서 삭제·보안 설계를 미루는 허용이 아니다. U14/U10 소비자 연결은 명시적으로 범위 밖이며 U15의 포트 시험으로 경계를 검증한다.

## 커버리지 확인

구체적 FR 27개와 NFR 6개를 모두 U15에 배정했다. 부모 FR1~FR6은 묶음 제목이고 중복 요구사항이 아니다. traceability.json은 필수 구체 FR 전체를 열거하며 모든 OK 대상은 위 표의 U15와 일치한다. 미배정 FR 및 행 없는 단위는 없다. 이 배정은 구현/시험 통과를 의미하지 않는다.

