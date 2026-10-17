# U15 의존성

## 근거와 단위 DAG

unit-of-work.md와 ../requirements-analysis/requirements.md, 기존 261015-doc-sync-code-update/inception/domain-design/components.md·decisions.md를 따른다. 현재 기록에는 stories.md가 없으며 FR을 추적한다.

```yaml
units:
  - name: u15-knowledge-graph
    kind: service
    depends_on: []
```

단위는 U15 하나여서 자체 의존·순환이 없다. 이 기록 안에서 병렬화할 독립 단위 집합은 없다. U8/U9/U2는 이미 구현된 기반이므로 이 DAG에 미선언 노드나 새 작업으로 넣지 않는다.

## 기존 기반과 통합 계약

| 기반/소비자 | 경계 | 책임 |
|---|---|---|
| U8 계약 | contracts/ai-io.md §10, ai-seed-output.schema.json, timing.md | U15가 변경한 스키마·시간 설정은 계약과 함께 갱신 |
| U9 CoreLunaClient | SEED_GENERATION | 기존 호출·기술/스키마 재시도 예산 공유, 실제 키 없는 자동 시험 |
| U2 DeletionParticipant | 아동·계정·기록 삭제 | U15 그래프/원장 삭제, 실패 RETRY, 비활성 이력 확인 |
| PostgreSQL core | generated_seed·버전 원장 V8 | 출처·모델·검사 결과 보관 및 재구성 |
| Neo4j | childId별 검증된 구조 투영 | 원문/후보·세션 출처 제외, 기존 NEO4J_AUTH 사용 |
| U14 PracticeGoal | seedSummaries·생성 서비스 | U15는 포트/자료, U14는 호출 시점과 후보 참조 연결 |
| U10 ScenarioGeneration | skillContext·variantPlan | U15는 규칙/입력 계약, U10은 실제 노출·보존 판정 연결 |

## 통합 실행 가능성과 범위

U15는 기존 core-api와 메모리 대역/Neo4j 통합 시험으로 독립 검증 가능하다. U14/U10의 미래 구현이 없어도 기존 기반과 가짜 AI를 통해 초기화·조회·생성·삭제·장애 경로를 검사한다. 기존 skeleton을 새 단위로 분리하지 않는다. 배포 및 경제적 개발 순서는 이 문서에서 새로 결정하지 않는다.

