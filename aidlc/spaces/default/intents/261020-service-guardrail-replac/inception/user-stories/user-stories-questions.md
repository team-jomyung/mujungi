# User Stories Plan and Questions

## Sources

- [desc] Initial description: 현재의 gpt-live 가드레일에서 무중_서비스용_가드레일로 교체하고 싶어
- [scope] Workflow-selected scope: classic / Standard.
- [R1] ../requirements-analysis/requirements.md: 승인된 범위와 Q1-A, Q2-A, Q3 Accept assumptions.
- [R2] aidlc/spaces/default/codekb/mujungi/business-overview.md 및 component-inventory.md: 기존 역할·서비스 경계.
- [memory:M1] ../practices-discovery/team-practices.md: 시험 기준 및 배포 제외.

## Story Plan

- 사용자 역할: 아이(주 역할), 같은 계정으로 서비스를 사용하는 보호자(보조 역할) 2개. 새 운영자·전문가 계정은 추가하지 않는다.
- 형식: 한국어 사용자 관점 문장, 안정적 US/AC ID, Given/When/Then, INVEST, 요구사항에 따른 MoSCoW.
- 기본 분해: 사용자 여정 중심의 작은 결과 8개 — 올바른 자유대화 정책, 매 세션 안내, 참여 선택, 대화/화제 선택, 이해/정정, 휴식/종료, 정확한 서비스 사실, 기존 연습·검수의 연속성.
- 우선순위: 승인된 Must 요구사항에서 나온 스토리는 Must Have. 최종 구현 묶음·순서는 후속 전달 계획에서 정한다.
- 각 스토리에 정상·실패·경계 인수 조건을 포함한다. 모델 발화 문구의 주관적 품질과 서버가 강제하는 결과는 구분한다.
- 개발·시험·로그·빌드 같은 운영 제약은 억지 사용자 역할을 만들지 않고 ‘스토리 밖 추적’ 표와 traceability의 후속 단계로 연결한다.

## Q1 — 스토리 분해 방식

승인된 아이·보호자 2개 역할을 기준으로 위 사용자 여정을 8개 스토리로 나눌까요? 제품 범위를 새로 결정하는 질문이 아니라, 설계·구현·시험에서 검토할 크기를 정하는 질문이다.

- A. 사용자 여정별 8개 스토리 (추천): 안내·참여·대화·정정·휴식/종료 등의 결과를 나누고 역할별 가치를 표시한다.
- B. 기능별로 재분해: 프롬프트·안내/참여·대화 제어·서비스 사실/기존 기능 보호의 기능 묶음을 중심으로 쓰고 필요한 결과를 그 아래 둔다.
- X. Other (please specify)

[Answer]: A. 사용자 여정별 8개 스토리 (Recommended)

## Assumptions & Open Questions

- 새 페르소나나 요구사항, 연습·저장·기억 신규 구현을 추가하지 않는다.
- 기존 요구사항의 원본/라이선스·실제 공급자 품질 한계는 승계하며, 이 단계에서 해결됐다고 하지 않는다.
- 사용자 여정별 8개 스토리 구성을 선택했다. 별도 요약 확인은 꺼져 있고 단계 승인은 별도다.
