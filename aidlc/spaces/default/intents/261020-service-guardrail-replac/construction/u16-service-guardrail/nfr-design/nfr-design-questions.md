# U16 NFR Design Questions

## Sources

- ../nfr-requirements/{performance,security,scalability,reliability,observability}-requirements.md 및 tech-stack-decisions.md: NFR1–NFR6의 상세 목표, 기존 시간/하한/권한 유지.
- ../functional-design/functional-spec.md 및 reviews/review-01.md: 종료 확인 질문 실패 전이 R-01(Major), CLOSING 실제 인사 표시 R-02(Minor).
- ../../../inception/contract-design/contract-summary.md: C1–C6, 현재 인사 STARTED 뒤에만 PLAYING, 최종 종료/실패/단절/만료에서 해제.
- ../../../inception/delivery-planning/bolt-plan.md: 자동 일반 완료 확인, 새 결정·코드 계획·실패는 사람 확인.
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionExecutor.java 및 EndingFlow.java의 STOP_CONFIRM/기존 실패·재생 추적: 현재 구현 참조이며 아래는 목표 설계 제안이다.

## Design Plan

기존 직렬 상태 소유·시간 제한·실패 차단·원자적 사실 저장·Core fallback null을 기존 구성 안에서 설계한다. 새 캐시/자동 확장/재시도·새 공급자/운영 대시보드를 도입하지 않는다. 모든 상세 NFR의 구현 경계·검증을 연결한다.

## Q1 — 종료 확인 질문을 전달하지 못한 경우

이 질문은 정말 모호한 종료 의사를 한 번 확인하는 도중의 실패다. 이미 명확한 종료는 즉시 종료하며 다시 묻지 않는다. 안내 완료 전 참여 실패와도 별개다.

- A. 복구 가능한 질문 실패는 듣기 상태로 복귀 (추천): 질문 자원/생성·검수·송신/재생 실패·만료에서는 현재 질문/대답 창·출력 보류 귀속을 해제한다. 연결/기기가 정상이고 여전히 ACTIVE이면 새 확인 질문이나 자동 재개 권유 없이 듣기로 돌아간다. 끼어들기는 그 새 발화를 현재 의미로 처리한다. 연결·기기/공급자 장애가 지속되면 기존 기술 일시정지·정리/종료를 따른다. 미전달 질문의 답·종료 동의를 추정하지 않는다.
- B. 질문 전달 실패는 기술 종료: 실패/만료에서 현재 질문 귀속을 해제하고 모두 기존 기술 실패/정리로 연결한다. 연결이 정상이어도 대화를 계속하지 않는다. 명확한 종료·거절로 표시하지 않는다.
- X. Other (please specify)

[Answer]: A. 복구 가능하면 듣기로 복귀

## 함께 명확히 할 인사 표시

상위 계약대로 CLOSING 진입에서는 이전 재생 사실을 해제하고, 현재 승인 인사의 실제 STARTED 이후에만 PLAYING을 허용한다. 그 인사의 완료/중단/실패·단절/만료 또는 최종 종료에서 해제한다. CLOSING 전체에서 일괄 숨긴다는 뜻이 아니다. 선택한 Q1 결정과 이 설명을 기능 설계에 보완하고 다시 검토한 뒤 품질 설계에 연결한다.

## Assumptions & Open Questions

Q1-A를 사용자가 선택했다. 해당 결정과 인사 관측 설명을 기능 설계에 반영하고 재검토한다. 실제 음성 생성 실행/승인은 별도이며 유료 호출은 아직 하지 않는다.
