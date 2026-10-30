# 현재 DB 구조와 과거 ERD

[ERD v0.2](ERD_v0.2.md)는 과거 설계 스냅샷으로 보존한다. 현재 애플리케이션 DB 구조는 다음 마이그레이션을 버전 순서대로 적용한 결과이며, ERD v0.2만으로 전체 구조를 설명하지 않는다.

- [Core 마이그레이션](../../backend/core-api/src/main/resources/db/migration/core): 2026-10-30 main 기준 V1~V13.
- [Realtime 마이그레이션](../../backend/realtime-activity/src/main/resources/db/migration/realtime): 같은 기준 V1~V9.
- [역할·권한 초기화](../../infra/postgres/init/01-roles.sql)와 [Flyway 설정](../../infra/migrate)은 적용 경로와 권한의 근거다.

Core에는 초기 계정·활동 구조 외에 결과/리포트(V5), 웹 푸시(V6), 연습 준비(V7), 지식 그래프 연계(V8), 목표 결과(V9), 상황 생성(V10), 아이 생년월일(V11), AI 사용량(V12), 관리자 계정(V13) 변경이 있다. Realtime에는 음성·세션·연습·판정·출력 안전 검사·연습 흐름과 AI 사용량 변경이 누적되어 있다. 구체적인 테이블·제약·인덱스는 각 SQL을 확인한다.

Neo4j는 별도 그래프 저장소이며 PostgreSQL ERD에 전부 표현되지 않는다. [Neo4j 실행·검증 안내](../neo4j-runtime.md)를 함께 본다.

이 문서는 저장소에 정의된 구조를 안내한다. 현재 실행 DB에 어떤 버전까지 적용됐는지 또는 실제 데이터의 연결이 올바른지는 별도 읽기 전용 검증이 필요하다. 이번 문서 최신화는 DB 변경·초기화·마이그레이션 실행을 하지 않는다.
