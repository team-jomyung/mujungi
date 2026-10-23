# U16 성능 요구

## Sources

- ../../../inception/requirements-analysis/requirements.md: NFR1–NFR6 및 FR1–FR7.
- ../functional-design/functional-spec.md 및 rules.md: 정책·안내/참여·종료·검수·사실 수명.
- ../../../inception/contract-design/contract-summary.md: C1–C6, 기존 시간/실패/호환 의미.
- ../../../inception/practices-discovery/team-practices.md: test-after/80%, 기존 전체 회귀와 배포/실호출 제외.
- aidlc/spaces/default/codekb/mujungi/technology-stack.md: brownfield 기술 기록. 이전 버전 표·배포 설명을 새로 인증하지 않음.

## Requirements

| ID | 요구/합격 기준 | 검증 |
|---|---|---|
| NFR2.2 | 기존 참여20초/재안내20초·공백4분/6분·재접속40초·종료확인30초·모호종료20초·승인재생20초와 재생/출력대기/기술 일시정지 제외를 유지한다 | 주입 시계로 직전/정확한 만료/직후와 정지·재개/경합 검증 |
| NFR6.2 | 내부 조회1초/연결500ms·무재시도, 판정4초·JEV800ms의 기존 마감과 큐 설정을 유지한다. 새 폴링/자동 AI 호출/재시도 루프0 | 실제 요청 대역 횟수/마감·설정 대조 |
| NFR5.2 | 정책 자원은 앱 시작 적재·세션 불변 참조로 사용한다. 일반 턴마다 원본 documents/파일 재읽기0 | 초기화/요청 경계 대역과 깨끗한 산출물 확인 |

## Capacity and Benchmarks

이번 변경은 새 처리량·동접/매출 성장·p95 지연 SLO를 정의하지 않는다. 기존 단일 세션 직렬 처리와 설정 상한이 기준이다. 새 정책의 자원 크기/실제 초기 요청 크기·적재 오류/송신 지연은 측정 근거로 남기되 문서만으로 무회귀를 주장하지 않는다. 자동 벤치마크는 가짜 공급자이며 실제 모델 지연과 구분한다.

## Assumptions & Open Questions

새 성능 계약/처리량 보장을 만들지 않는다. 실제 음성 길이20초 충족은 자원 제작 후 별도 확인한다.

