# U16 논리 구성과 실패 범위

## Sources

- ../nfr-requirements/performance-requirements.md, security-requirements.md, scalability-requirements.md, reliability-requirements.md, observability-requirements.md 및 tech-stack-decisions.md: 상세 NFR20개.
- ../functional-design/functional-spec.md 및 rules.md: 동작/실패 전이·사실 수명과 단일 소유.
- ../../../inception/contract-design/contract-summary.md: C1–C6와 기존 인증/마감/호환 계약.
- ../../../inception/domain-design/components.md: 정책·판단·actor·검수/표시의 책임.
- nfr-design-questions.md Q1-A: 질문 전달 실패 후 복구 가능하면 듣기로 복귀, 지속 장애는 기존 기술 정리.

## Component Inventory

| 구성 | 소유/실패 범위 | 적용 품질 설계 |
|---|---|---|
| ServicePolicyCatalog | 앱 포함 정책/승인자원 메타데이터, 실패하면 해당 새 자유대화 시작 차단 | NFR5.1–NFR5.3, NFR4.2 |
| DialoguePolicy | 최소 문맥·고정 정책과 요청 의미, 실행/저장 권한 없음 | NFR1.3, NFR3.3, NFR6.1 |
| SessionLifecycle | 현재 세션 actor/시계/재생·기기확인/사실 저장·query projection | NFR1.2, NFR1.4, NFR2.1–NFR2.4, NFR6.3 |
| OutputGuard | 기존 JEV 후보/결과·정렬/세대, 실패하면 현재 후보 보류/폐기 | NFR2.1, NFR6.1–NFR6.2 |
| 기존 Core 중계 | 기존 소유 확인·내부HTTP, 실패하면 guardrail=null의 기존 사본응답 | NFR1.2, NFR2.3, NFR6.2 |
| 기존 승인 파일/기기WS | 승인자원 실제 전달·현재 재생보고, 실패해도 완료를 조작하지 않음 | NFR2.1–NFR2.2, NFR4.2–NFR4.3 |
| 기존 시험/로그 경계 | 허용 메타데이터/coverage/현재 근거 보고 | NFR1.1, NFR3.1–NFR3.3, NFR4.1, NFR5.1 |

## Infrastructure Handoff

새 서비스/DB/큐/캐시/로드밸런서/자동확장·클라우드 구성은 없다. 기존 앱 산출물과 DB권한·WS/내부HTTP를 사용한다. 미구현/미확인 범위를 새 인프라가 해결하는 것으로 돌리지 않는다. 필요하다면 기존 세션 사실 조회의 쿼리/인덱스만 코드 계획에서 근거와 함께 확정한다. 실제 배포·수동 스택 변경은 하지 않는다.

## Assumptions & Open Questions

여기서 component는 논리 책임이며 독립 배포 제품을 추가하는 것이 아니다. 실제 가동/암호화/용량은 파일만으로 인증하지 않는다.

