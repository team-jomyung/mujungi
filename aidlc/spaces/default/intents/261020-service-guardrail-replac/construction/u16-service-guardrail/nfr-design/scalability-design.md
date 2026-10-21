# U16 확장·자원 격리 설계

## Sources

- ../nfr-requirements/performance-requirements.md, security-requirements.md, scalability-requirements.md, reliability-requirements.md, observability-requirements.md 및 tech-stack-decisions.md: 상세 NFR20개.
- ../functional-design/functional-spec.md 및 rules.md: 동작/실패 전이·사실 수명과 단일 소유.
- ../../../inception/contract-design/contract-summary.md: C1–C6와 기존 인증/마감/호환 계약.
- ../../../inception/domain-design/components.md: 정책·판단·actor·검수/표시의 책임.
- nfr-design-questions.md Q1-A: 질문 전달 실패 후 복구 가능하면 듣기로 복귀, 지속 장애는 기존 기술 정리.

## Existing Capacity Boundaries

| 요구 | 설계 해법 | 검증 |
|---|---|---|
| NFR6.3 | 현재 세션마다 단일 직렬 상태 소유, 기존 큐/재생/연결 상한을 유지한다. 새 worker pool·메시지 버스·인스턴스/샤드/자동 확장0 | 기존 구성/한도 대조·포화/중단 회귀 |
| NFR1.4 | 작은 도움/거절·휴식 상태는 현재 세션에서만 유지하고 정리 시 버린다. 사실은 확정 전이에만 기록하고 heartbeat/오디오조각/조회마다 추가하지 않는다 | 반복입력·조회/재생·새 세션·정리 시험 |
| NFR5.2 | 정책 자원을 불변 참조로 공유하며 원본/전사/음성 버퍼를 새 캐시에 복사하지 않는다 | 세션 생성/종료와 적재 횟수 시험 |

세션 상태는 stateful하므로 이번 작업에서 무상태 수평확장을 약속하지 않는다. 기존 단일 인스턴스/바인딩 전제와 DB schema 격리를 유지한다. CPU/메모리·동접 수치/확장 트리거는 운영 검증 없이 새로 정하지 않는다. 과부하/큐 포화에서는 기존 거절·실패 정리로 일반 음성을 차단한다.

## Assumptions & Open Questions

새 인프라나 클라우드 확장 정책은 필요하지 않다. 사실 조회 인덱스가 필요하면 구현 계획에 최소한으로 반영한다.

