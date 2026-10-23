# U16 확장·자원 한도 요구

## Sources

- ../../../inception/requirements-analysis/requirements.md: NFR1–NFR6 및 FR1–FR7.
- ../functional-design/functional-spec.md 및 rules.md: 정책·안내/참여·종료·검수·사실 수명.
- ../../../inception/contract-design/contract-summary.md: C1–C6, 기존 시간/실패/호환 의미.
- ../../../inception/practices-discovery/team-practices.md: test-after/80%, 기존 전체 회귀와 배포/실호출 제외.
- aidlc/spaces/default/codekb/mujungi/technology-stack.md: brownfield 기술 기록. 이전 버전 표·배포 설명을 새로 인증하지 않음.

## Requirements

| ID | 요구/합격 기준 | 검증 |
|---|---|---|
| NFR6.3 | 기존 actor/메일함/입출력 큐·재생 상한과 단일 소유를 유지하고 새 프로세스·큐 제품·자동 확장 구성0 | 기존 설정/구조·현재성 회귀 |
| NFR1.4 | 확정 사실은 세션의 기존 이벤트 수명 안에만 남긴다. 화제/도움 상태의 새 장기 원문 목록0 | 반복/새 세션/정리 및 삭제 시험 |
| NFR5.2 | 정책 본문은 불변 앱 자원/세션 참조로 공유하고 요청마다 파일/정본을 반복 읽지 않는다 | 적재 횟수/정책 참조 시험 |

## Growth and Capacity

새 동접 수·시간당 요청·다중 인스턴스 보장을 제공하지 않는다. 기존 단일 인스턴스 전제와 정리/세션 한도가 기준이다. 새 사실 사건은 확정 상태 변화에만 남기고 오디오 조각/heartbeat/조회마다 사건을 추가하지 않는다. 필요 용량은 실제 구현의 사건 수/최대 payload를 기록해 기존 제한과 대조하며, 미측정 용량을 충분하다고 가정하지 않는다.

## Assumptions & Open Questions

새 세션 전체 길이/턴/시도 예산 상한을 만들어 기존 기능을 제한하지 않는다. 운영 확장·인프라 변경은 범위 밖이다.

