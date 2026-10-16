# U15 개발 단위

## 기준과 범위

현재 ../requirements-analysis/requirements.md와 units-generation-questions.md의 확인된 계획을 따른다. 기존 aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/domain-design/components.md의 KnowledgeGraph 및 decisions.md, 같은 기록의 units-generation/unit-of-work.md U15를 재사용한다. 현재 Domain Design은 생략되어 새 components.md를 만들지 않았다. 현재 stories.md는 생성하지 않았으므로 FR 단위로 추적한다. docs/units/u15/brief.md가 구현 지시서다.

## Unit 정의

| Unit ID | Directory | Kind | 배포 모델 | 상대 규모 |
|---|---|---|---|---|
| U15 | u15-knowledge-graph | service | 기존 core-api 공유 배포 | L |

U15는 기존 core-api 실행 서비스에 포함되는 KnowledgeGraph 구현 단위다. 별도 실행 서비스나 배포 파이프라인은 만들지 않는다. Neo4j·PostgreSQL 및 기존 CoreLunaClient와 삭제 체계의 결합 때문에 규모 L로 잡는다.

## 책임과 경계

- 아동별 초기화와 childId 필수 조회, 소유권·동의·삭제 상태 확인. 기존 공개 행동키 조회는 호환 유지한다.
- 같은 원천을 쓰는 메모리/Neo4j 구현, 기본 씨앗 14개와 어휘 적재, 버전 갱신·재구성, generated_seed 원장용 V8.
- 활성 씨앗 요약, 구조 맥락, SAME_CONTEXT/NEAR 변형 계획. 실제 노출과 보존 판정 연결은 U10 소유다.
- 자유대화 NONE 후보의 씨앗 생성·검증·저장, 활동당 2개와 후보 멱등성, 공유 재시도 예산. 생성 호출 시점과 후보 skill_ref 연결은 U14 소유다.
- 개인정보 최소화, 그래프와 원장 부분 실패 복구, 삭제 참여, 동의 철회·삭제와 늦은 생성 경쟁 방지.
- 그래프 꺼짐·장애·2초 초과 시 빈 조회, 삭제 실패는 RETRY. 생성 기능은 조회와 독립적으로 중지할 수 있다.

## 구현 제약과 완료 기준

FR1.1~FR6.2 전체와 NFR1~NFR6을 이 단위가 소유한다. 구현 내부 묶음은 지시서대로 V8/포트/메모리 → Neo4j/적재 → 생성/검사 → 삭제/기본 경로다. 이는 U15 내부 구현 구분이며 다른 단위 간 경제적 우선순위를 새로 정하지 않는다.

Java 21·JsonCodec·MujoongClock·EventLog를 사용하고 기존 realtime/프런트 코드와 타 단위 시험 단언을 변경하지 않는다. 비밀 값은 커밋하지 않는다. U15 범위 커버리지 80% 이상, 단위·DB/Neo4j 통합 및 U2/U5a/U6/U9 회귀·ArchUnit·전체 verify를 검증한다. 자동 시험은 가짜 AI를 사용한다. 설계와 결과는 docs/units/u15/design.md 및 report.md에 반영한다. 검증 전 성공을 주장하지 않으며 push와 배포/main 병합을 구별한다.

