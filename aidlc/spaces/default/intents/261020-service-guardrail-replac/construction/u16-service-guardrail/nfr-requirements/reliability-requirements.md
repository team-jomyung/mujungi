# U16 신뢰성 요구

## Sources

- ../../../inception/requirements-analysis/requirements.md: NFR1–NFR6 및 FR1–FR7.
- ../functional-design/functional-spec.md 및 rules.md: 정책·안내/참여·종료·검수·사실 수명.
- ../../../inception/contract-design/contract-summary.md: C1–C6, 기존 시간/실패/호환 의미.
- ../../../inception/practices-discovery/team-practices.md: test-after/80%, 기존 전체 회귀와 배포/실호출 제외.
- aidlc/spaces/default/codekb/mujungi/technology-stack.md: brownfield 기술 기록. 이전 버전 표·배포 설명을 새로 인증하지 않음.

## Requirements

| ID | 요구/합격 기준 | 검증 |
|---|---|---|
| NFR2.1 | 안내 미확인·현재 참여 미수락·검사 실패·지난 세대/모드/목적·중복/종료 경합에서 미확인 일반 음성0회, 중복ACTIVE/첫 응답0회, 종료 뒤 일반 재생 재개0회 | 실제 서버 상태/기기/검사 대역의 부정 경계 |
| NFR2.3 | 참여/중요 확정 사실의 기록은 관련 상태 전이와 원자적이다. 저장 실패/경합 패자의 출력 효과0회. 재시작/actor 제거/legacy/fallback은 근거 없는 사실을 복구하지 않는다 | DB/트랜잭션 경계·restart/fallback/삭제 시험 |
| NFR2.4 | 확인 질문이 미전달·실패·만료·중단됐을 때 ASKING이 무기한 남지 않는다. 실패 사건 또는 기존 재생 시한에서 현재 질문/보류 귀속을 해제할 처리 경로가 있어야 한다. 미전달 질문 답 추정/반복 질문0 | 각 실패 원인·늦은 COMPLETED/답·시계 시험. 구체적 재개/기술정리는 FD R-01의 열린 결정으로 구현 전 확정 |
| NFR6.1 | 기존 저장/삭제·동의·권한·연습·JEV 판/범주·공급자4type을 보존하고 새 자동AI 호출0 | 모드별 실제 요청/저장·정리 회귀 |
| NFR4.2 | 기존 톤/무음이나 synthetic 완료 표식은 실제 안내 전달 합격이 아니다. 실제 자원 미준비는 명시적 기능 미완료/보류로 보고한다 | 자원 verification 상태·보고서/대역 근거 대조 |

## Availability and Recovery

새99.x% 가용성·백업/복구시간·공급자 보관/삭제 SLA를 만들어 보장하지 않는다. 기존 기술 정리·고아 세션/재시작 처리·기기 확인 의미를 유지한다. Core fallback은 stale 여부와 무관하게 guardrail=null이며 일반 API의 다른 기존 기능은 기존 정책대로 제공한다.

## Carried Findings

Functional Design R-01(Major): 확인 질문 실패 후 구체적 전이 결정표가 빠졌다. 위 NFR2.4는 필요한 생존성 기준을 명시하며 그 설계가 수정됐다는 뜻이 아니다. R-02(Minor): CLOSING 중 현재 승인 인사 STARTED와 이전 재생 해제의 구별이 필요하다. 코드 계획/구현 전에 처리 방향을 명시해 확인한다. 여기서 기존 검토 결과를 해결로 바꾸거나 frozen 설계를 자동 수정하지 않는다.

## Assumptions & Open Questions

실제 말소리·20초 길이와 실패 후 전이 결정은 구현 전 준비 항목이다. 전체 서비스 준비 완료와 문서 추적/대역 합격을 구분한다.

