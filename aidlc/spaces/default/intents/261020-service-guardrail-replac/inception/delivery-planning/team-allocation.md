# 구현 담당 배정

## Sources

- delivery-planning-questions.md Q1-B/Q2-A, bolt-plan.md: 통합 Bolt1개·단일 세션·단위별 순차.
- ../units-generation/unit-of-work.md: 파일/계약 소유.
- ../practices-discovery/team-practices.md: 기존 작업 보호·검증·병합 방식.

## Allocation

Bolt는 설계부터 실행 가능한 결과까지 만드는 작업 묶음이다. 이번 B1에는 U16과 U17이 함께 들어가며 이 세션에서 이어서 진행한다. 별도 사람 팀 편성 단계는 실행하지 않았고, 모든 Bolt 구현 담당은 aidlc-developer-agent(AI)다. 역할별 설계/검증 담당과 단계가 지정한 독립 검토는 유지한다.

| Bolt | Unit | 구현 담당 | 역할별 확인 | 소유 |
|---|---|---|---|---|
| B1 service-guardrail-integration | U16 | aidlc-developer-agent | 설계 architect, 검증 quality, 필요한 단계의 독립 reviewer | 백엔드·공개 계약·Java DTO·Core 어댑터/사실 투영 |
| 같은 B1 | U17 | aidlc-developer-agent | 설계 architect, 검증 quality, 필요한 단계의 독립 reviewer | 프런트 타입/파싱·copy/화면·기기 웹 소비 |

## Coordination

- 동시 구현하지 않는다. U16의 고정 계약/검증된 결과를 U17이 소비하며 동일 파일을 양쪽이 중복 소유하지 않는다.
- API 스키마 변경은 U16에 모으고 U17이 새 필드를 임의 추가하지 않는다. 소유 변경이 필요하면 계획에 명시한다.
- 통합 Bolt 하나를 골랐다고 단위의 설계/코드 계획/검증을 생략하지 않는다. 최종 사용자 스토리는 양쪽 결과와 통합 근거로 확인한다.
- 별도 실제 팀 인원·처리 속도를 가정하지 않는다. 사람의 승인·외부 음성 자료는 AI 구현 배정만으로 확보되지 않는다.

## Assumptions & Open Questions

실제 안내 음성 제공/제작의 사람 담당과 승인 소요 시간은 미정이다. U16 상세 설계에서 필요한 자원과 확보 방법을 구체화한다. 자동으로 외부 사람에게 연락하거나 유료 호출하지 않는다.
