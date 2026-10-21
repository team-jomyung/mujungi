# U16 관측·증거 설계

## Sources

- ../nfr-requirements/performance-requirements.md, security-requirements.md, scalability-requirements.md, reliability-requirements.md, observability-requirements.md 및 tech-stack-decisions.md: 상세 NFR20개.
- ../functional-design/functional-spec.md 및 rules.md: 동작/실패 전이·사실 수명과 단일 소유.
- ../../../inception/contract-design/contract-summary.md: C1–C6와 기존 인증/마감/호환 계약.
- ../../../inception/domain-design/components.md: 정책·판단·actor·검수/표시의 책임.
- nfr-design-questions.md Q1-A: 질문 전달 실패 후 복구 가능하면 듣기로 복귀, 지속 장애는 기존 기술 정리.

## Evidence and Allowed Signals

| 요구 | 설계 해법 | 검증/보고 |
|---|---|---|
| NFR1.1 | 기존 이유 코드·판/해시·허용 상관ID/시각·지연을 구조화하고 본문/키를 배제 | 로그 위생/직렬화 민감 표식0건 |
| NFR3.1 | 코드 계획을 시험 가능한 층으로 분리하고 층 구현→해당시험 순서. 기존 모듈/기능 줄80%·계약/ArchUnit 유지 | 기능 이름 coverage 범위/시험 결과 |
| NFR3.2 | 승인된 Unit검증 명령의 현재 소스/종료코드 근거와 최종 전체 verify.sh·변경 경계 통합을 보관 | PREREQ/FAIL/PASS 분리 |
| NFR3.3 | 실제 초기/후속/local/J6 요청 검사와 판정결과→상태효과·현재성/조회시험을 분리 | 요청을 검증하지 않은 상태효과만으로 정책전달 합격을 주장하지 않음 |
| NFR4.1 | 모든 자동 자료는 가짜 공급자/합성 유형으로 표기하고 실모델/기기/아이 평가와 분리 | 자동·실호출·실기기별 상태/근거 표 |
| NFR4.3 | GPT-Live 사전제작은 별도 호출결과·전사/청취/형식·길이/해시·판/등록을 추적 | 호출 미실행/청취미완료/등록미완료 구분 |
| NFR5.1 | 정본문서 해시·적용본 편차/해시→앱자원→실제 요청 대역/시험을 연결 | 깨끗한 빌드/원문대조·자원manifest |

기존 관측/카운터/보고 파일을 사용하고 새 운영 대시보드·외부 알림·추적 제품을 추가하지 않는다. 정책/현재성·오류/실패 메타데이터는 일반 로그의 허용 목록만 사용하며 새 원문/음성은 로그로 수집하지 않는다. 실제 호출이 없으면 가격·잔액·계정 권한을 추정하지 않는다.

## Assumptions & Open Questions

새 운영SLO/알림 임계값·외부사람 연락은 범위 밖이다. 대역 합격을 실제 음성/안내의 의미 전달로 바꾸지 않는다.

