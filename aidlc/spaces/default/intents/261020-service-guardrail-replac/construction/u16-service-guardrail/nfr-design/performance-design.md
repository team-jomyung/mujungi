# U16 성능 설계

## Sources

- ../nfr-requirements/performance-requirements.md, security-requirements.md, scalability-requirements.md, reliability-requirements.md, observability-requirements.md 및 tech-stack-decisions.md: 상세 NFR20개.
- ../functional-design/functional-spec.md 및 rules.md: 동작/실패 전이·사실 수명과 단일 소유.
- ../../../inception/contract-design/contract-summary.md: C1–C6와 기존 인증/마감/호환 계약.
- ../../../inception/domain-design/components.md: 정책·판단·actor·검수/표시의 책임.
- nfr-design-questions.md Q1-A: 질문 전달 실패 후 복구 가능하면 듣기로 복귀, 지속 장애는 기존 기술 정리.

## Immutable Policy and Budgets

| 요구 | 설계 해법 | 검증 |
|---|---|---|
| NFR5.2 | 앱 시작에 UTF-8 자원/해시·판을 검증해 불변 묶음을 구성하고 세션은 참조를 고정한다. 턴별 파일 접근/원본 폴더 접근 없음 | 초기화 횟수·자원 누락/해시·세션 판 고정 시험 |
| NFR6.2 | 기존 HTTP 연결/조회500ms/1초·판정4초·JEV800ms와 현재 큐를 사용한다. 새 병렬 호출·자동 재시도/백오프 없음 | 실제 송신 대역 횟수·기존 설정 경계 |
| NFR2.2 | 기존 서버 시계/현재 마감과 일시정지·재생/보류 제외를 재사용한다. 정확한 만료시각에서는 만료 우선 | 주입 시계 직전/경계/직후·경합 시험 |

정책은 불변이라 세션 중 TTL/캐시 무효화를 추가하지 않는다. 현재 조회 snapshot은 상태 변경 경계가 만든 불변 값으로 읽어 actor 외부에서 상태를 변경하지 않는다. DB 풀/HTTP 클라이언트·기존 비동기 호출 경계는 재사용하고 새 pool 수치나 p95/처리량 약속을 만들지 않는다.

## Query Work

정리된 세션의 최소 사실은 현재 세션/앱 사실 타입으로 한정한 최신 유효 revision 조회를 사용한다. 일반 세션 사건 전체를 새로 메모리에 재생하지 않는다. transcript seq 참조는 기존 공개/확정 전사 조회와 같은 가시성 검사에 묶는다. 기존 인덱스/쿼리 계획으로 충분한지 구현 시 확인하며 추가 인덱스가 필요하면 기존최댓값 다음 migration과 코드 계획에 명시한다. 새 저장 제품은 없다.

## Assumptions & Open Questions

최적화 성과/처리량은 아직 측정하지 않았다. 전체 자원/실공급자 지연 보장은 이번 대역 증거와 구분한다.

