# U15 개발 단위 정리

## 근거와 확정된 사항

- 정본: docs/units/u15/brief.md 및 이 기록의 inception/requirements-analysis/requirements.md.
- 기존 설계 재사용: aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/domain-design/components.md의 KnowledgeGraph와 같은 기록의 decisions.md 및 unit-of-work.md의 U15.
- 현재 기록의 Domain Design은 기존 컴포넌트 재사용으로 생략했다. 새 components.md가 있는 것으로 간주하지 않는다.
- 사용자가 지정한 U15 → U14 → U10 순서는 유지한다. 이 기록의 구현 대상은 U15이며, 뒤의 두 단위를 새로 분할하거나 이번 변경에 구현하지 않는다.
- 기존 답과 지시서로 경계, 배포, 의존성이 정해져 있어 추가 업무 질문은 없다.

## 개발 단위 계획

| Unit ID | Directory | Kind | 배포 | 규모 |
|---|---|---|---|---|
| U15 | u15-knowledge-graph | service | 기존 core-api와 함께 배포 | L |

- 단위는 하나다. 기존 KnowledgeGraph 책임 안에 포트·메모리 대역, V8 저장 구조, Neo4j 어댑터·기본 씨앗 적재, 새 씨앗 생성·검증, 삭제·기본 경로를 포함한다. 독립 서비스를 새로 배포하지 않는다.
- 현재 기록 안의 단위 간 의존성은 없다. U8의 계약, U9의 CoreLunaClient, 기존 U2 삭제 체계는 이미 존재하는 기반 의존성이다.
- U14에는 씨앗 목록·생성 서비스를, U10에는 구조 맥락·변형 계획 포트를 제공한다. 실제 호출 시점·후보 참조 연결·노출 기록 연결은 각 단위가 담당한다.
- 승인된 모든 FR을 U15로 추적한다. 동의·소유권·삭제 경쟁 검사, 활성 씨앗만 노출, 생성 기능 독립 중지, 삭제 이력 불명 시 재시도 조건도 포함한다.
- 지시서의 네 커밋 구분을 유지하고 메모리/Neo4j 시험, 기존 회귀시험, U15 범위 커버리지 80% 이상을 검증한다. 실제 AI 호출은 자동으로 수행하지 않는다.
- 생성할 문서는 unit-of-work.md, unit-of-work-dependency.md, unit-of-work-story-map.md, traceability.json이다.

## Consolidated Summary Confirmation

위 단일 U15 경계와 의존성을 기준으로 개발 단위 문서를 작성해도 맞습니까?

- Looks correct
- Request changes

[Answer]: Looks correct
