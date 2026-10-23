# 사용자 스토리와 구현 단위 대응

## Sources

- ../user-stories/stories.md: US1.1–US1.6, US2.1–US2.2와 40개 AC.
- ../requirements-analysis/requirements.md: 상위 FR/NFR 및 범위 제외.
- ../domain-design/components.md 및 decisions.md: 책임과 엔티티 소유.
- unit-of-work.md, unit-of-work-dependency.md: 승인한 U16/U17의 소유·의존.

## Story Map

각 행의 주 담당은 추적용 단일 키이며 전체 스토리를 그 단위만으로 끝낸다는 뜻이 아니다. 협력 부분이 있는 스토리는 서버와 프런트 결과가 모두 충족돼야 완료다.

| Story ID | Unit ID | Directory | 협력 단위 | 배정 내용/완료 경계 |
|---|---|---|---|---|
| US1.1 | U16 | u16-service-guardrail | 없음 | 정책 적재/해시·모드별 요청·최소 문맥·기존 wire 유지 |
| US1.2 | U16 | u16-service-guardrail | U17 (u17-guardrail-presentation) | U16 안내 증거/허가, U17 기존 화면·기기 재생/보고 소비. 실제 자원/완료 확인과 대역을 구분 |
| US1.3 | U16 | u16-service-guardrail | U17 (u17-guardrail-presentation) | U16 안내 뒤 참여·사유/저장 보호, U17 RP-2 표시·기기 현재 확인 |
| US1.4 | U16 | u16-service-guardrail | 없음 | 초기/후속 질문·화제 거절·출처를 존중하는 정책/실행 연결 |
| US1.5 | U16 | u16-service-guardrail | 없음 | 정정·도움 한 번·지속 지시 범위·끼어들기 취소 |
| US1.6 | U16 | u16-service-guardrail | U17 (u17-guardrail-presentation) | U16 종료 로컬/J6 요청·결과·효과·시간/세대/증거, U17 RP-3/4·기기 소비·보호자 확인 유지 |
| US2.1 | U17 | u17-guardrail-presentation | U16 (u16-service-guardrail) | U17 기존 화면의 사실 표시, U16 모델/안내의 서비스 사실과 확인된 결과 투영. 저장 정책 전면 개편 없음 |
| US2.2 | U16 | u16-service-guardrail | U17 (u17-guardrail-presentation) | U16 기존 연습/JEV/호출/저장 경계, U17 연습·모드/구버전·미확인 표시 회귀 |

## 단위 안의 구현 순서

- U16: 정책/모드·문맥 층 → 현재 안내/참여·대화/종료·검수 층 → 사실 투영/계약·기존 회귀. 각 층 구현 뒤 해당 시험을 작성·실행한다.
- U17: 계약 타입/파싱 → copy/사유/인용/인사 매핑 → 필요한 기기 웹 확인 소비 → 변경 표면 접근성/연습 회귀. 각 층의 시험을 바로 수행한다.
- 이는 단위 내부 분해다. 단위 사이 경제적 우선순위·일정·Bolt 묶음은 다음 전달 계획에서 정한다.

## 스토리 밖 품질 추적

| 항목 | 적용 단위 | 후속 단계 |
|---|---|---|
| NFR1 로그/비밀/모델 문맥 보호 | U16 중심, U17은 표시/클라이언트 로그 보호 | NFR Requirements/Design, Functional Design, Build and Test |
| NFR2 실패·지난 결과·중복 처리 | U16 및 U17의 계약 소비 | Functional Design, Code Generation, Build and Test |
| NFR3 test-after/줄 80%/기존 전체 회귀 | U16, U17 | Code Generation, Build and Test |
| NFR4 대역/실모델/실기기 증거 구분 | U16, U17 | Build and Test |
| NFR5 정책 재현/해시·문자 보존 | U16 정책, U17 한글/인용 표시 | Functional Design, Code Generation, Build and Test |
| NFR6 기존 호출·계약 호환 | U16 제공, U17 소비 | Contract Design, NFR Requirements, Build and Test |
| User Stories R-01 | U16 | Functional Design의 실제 로컬/J6 정책 요청 경계와 그 시험. 스토리 결과 대역만으로 해소하지 않음 |

## Coverage Verification

상위 8개 US ID를 모두 배정했고 U16/U17 모두 주 담당 행이 있다. traceability.json의 OK target은 해당 행의 Unit ID와 일치한다. 협력 관계/전체 스토리 완료는 위 표와 각 단위 완료 기준을 함께 사용한다. 단위별 부분 합격을 전체 서비스 교체·실제 음성 성공으로 보고하지 않는다.
