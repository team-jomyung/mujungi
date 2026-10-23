# U16 신뢰성 설계

## Sources

- ../nfr-requirements/performance-requirements.md, security-requirements.md, scalability-requirements.md, reliability-requirements.md, observability-requirements.md 및 tech-stack-decisions.md: 상세 NFR20개.
- ../functional-design/functional-spec.md 및 rules.md: 동작/실패 전이·사실 수명과 단일 소유.
- ../../../inception/contract-design/contract-summary.md: C1–C6와 기존 인증/마감/호환 계약.
- ../../../inception/domain-design/components.md: 정책·판단·actor·검수/표시의 책임.
- nfr-design-questions.md Q1-A: 질문 전달 실패 후 복구 가능하면 듣기로 복귀, 지속 장애는 기존 기술 정리.

## Failure Domains and Recovery

| 요구 | 설계 해법 | 검증 |
|---|---|---|
| NFR2.1 | 정책/실제안내/현재참여·현재후보/JEV/상태·모드·세대·목적을 모두 확인하는 누적 허용 조건. 하나라도 미확인이면 일반출력 보류 | 각 실패·중복·순서역전/경합에서 잘못된 일반재생0·중복시작0 |
| NFR2.3 | 확정 사실 event와 중요 세션 전이를 같은 원자적 저장 경계에서 조건부 반영. 저장 전이 승자만 후속효과 실행 | 저장실패/전이패자/재시작·actor제거·legacy/참조삭제 |
| NFR2.4 | 현재 질문ID/재생/창/시계·보류 소유를 해제하는 종료 확인 실패 전이. ACTIVE/정상 연결이면 자동 발화 없이 듣기, 지속장애는 기존 기술정리. 종료/다른보류는 해제하지 않음 | 질문 자원·생성/검수·송신/재생실패·중단/만료와 늦은완료/답 |
| NFR2.2 | 기존 참여/공백/확인/연결 시계와 제외 의미를 유지하고 복구 후 남은 시간을 재개 | 정지·재개/정확한 만료/기술장애·종료경합 |
| NFR6.1 | 기존 공급자4type·모드분리·JEV 판/범주·저장/권한/삭제를 그대로 사용, 새 재시도/호출 루프 없음 | 실제 요청/옵션·회귀 대조 |
| NFR4.2 | 기존 톤 파일은 placeholder로 구분하고 실제 안내 검증 조건을 충족하지 않으면 일반 시작 차단 | 자원 판/형식·미준비 상태와 대역 보고 |

## State and Read Recovery

상태 변경은 기존 actor만 수행하고 순서가 뒤집힌 결과는 현재 상관값/세대로 폐기한다. 재접속은 기존1·2·4초/이후5초·40초 상한이며 중단한 안내/질문을 완료로 꾸미지 않는다. 현재 승인 인사 STARTED는 CLOSING 중 PLAYING으로 관측할 수 있지만 이전 재생 사실은 CLOSING 진입에서 해제한다. 현재 인사 완료/중단/실패·단절/만료/최종 종료에 해제하고, 즉시 음소거는 인사 NONE이다.

Core 내부 조회 실패는 기존 사본 응답을 유지하되 guardrail=null이다. 이전 PLAYING을 재사용하지 않는다. actor가 없거나 재시작하면 저장된 확정 notice/참여/허용 전사 참조만 읽고 동적 재생은 UNKNOWN이다. 사실 개정의 revision/statusVersion/구조가 모순이면 미확인으로 낮춘다. 조회/복구가 ACTIVE를 생성하지 않는다.

## Health and Persistence

기존 준비/헬스·DB 장애·고아 세션 정리와 연결 수명을 유지한다. 새 회로 차단기/DB복제/백업/복구목표를 추가하지 않는다. 중요 사실 저장 실패는 출력 먼저 차단, 원자적 저장 rollback, 기존 장애 정리다. 정리 DB쓰기까지 실패하면 메모리의 실제 출력은 닫고 기존 고아 정리에 맡긴다. 오류를 정상종료/거절·무응답으로 꾸미지 않는다.

기존 이벤트의 최소 사실을 조회할 때는 세션ID·앱 사실 타입과 단조 revision으로 정렬/검증하며 마지막 사건의 임의UUID/기기시각을 권위로 삼지 않는다. 삭제/만료된 전사 참조는 null이다. 새 원문/원음 복사나 Core의 별도 사실 영속 사본이 없다.

## Assumptions & Open Questions

질문 실패 복구/인사 표시의 기능 설계 보완은 사용자 선택을 반영했으며 독립 재검토 결과를 별도로 기록한다. 실제 구현·음성 자원·실기기 성공으로 간주하지 않는다.

