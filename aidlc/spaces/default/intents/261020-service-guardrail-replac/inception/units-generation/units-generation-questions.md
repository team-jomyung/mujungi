# Units Generation Plan and Questions

## Sources

- [desc] Initial description: 현재의 gpt-live 가드레일에서 무중_서비스용_가드레일로 교체하고 싶어
- [scope] Workflow-selected scope: classic / Standard, skeleton off, 이번 배포 제외.
- [D1] ../domain-design/components.md: ServicePolicyCatalog, DialoguePolicy, SessionLifecycle, OutputGuard, ConversationPresentation의 5개 논리 책임.
- [D2] ../domain-design/decisions.md: 단일 상태 소유·세션 고정 정책·모드 분리·기존 호출 경계·사실 투영.
- [R1] ../requirements-analysis/requirements.md: 승인된 범위와 FR/NFR.
- [S1] ../user-stories/stories.md: 8개 사용자 스토리·상위 R-01 후속 시험 인계.
- [UX1] ../refined-mockups/interaction-spec.md: RP-1–RP-4, 기존 화면과 기기/보호자 경계.
- [memory:M1] aidlc/spaces/default/memory/project.md 및 ../practices-discovery/team-practices.md: 파일 소유 우선, 같은 실행기 파일은 한 담당자, 기존 U15 뒤 번호 사용·기능 이름 시험/커버리지.
- [E1] docs/units/ 및 기존 units-generation 기록 조회: 이번 공간에 기존 U1–U15가 있어 새 단위는 U16부터 제안한다.

## Q1 — 단위 크기

논리 컴포넌트 5개를 각각 새 프로세스로 만들지 않는다. 기존 실행기·초기/후속 요청·안내 증거·검수 연결은 같은 서버 코드에서 함께 바뀌므로 소유 범위를 기준으로 묶는다.

- A. 백엔드와 화면·기기 웹 2개 (추천): U16 service가 정책/판단/상태/검수·기존 조회/제어 계약을 맡고, U17 ui가 기존 화면·기기 웹의 계약 소비/표시/재생 확인을 맡는다. U17은 U16의 계약/서버 사실에 의존한다.
- B. 정책 기반·서버 실행·화면 3개: U16 library는 정책 자원/순수 지시, U17 service는 세션/검수/조회 연결, U18 ui는 화면/기기 웹. 정책 기반을 독립 검토할 수 있으나 내부 API 인계와 단계 반복이 늘어난다.
- X. Other (please specify)

[Answer]: A. 백엔드 + 화면·기기 웹 2개 (Recommended)

## 제안 계획 — Q1-A의 2개 단위

| Unit ID | Directory | Kind | 책임 | 상대 복잡도 | 기존 배포와의 관계 |
|---|---|---|---|---|---|
| U16 | u16-service-guardrail | service | ServicePolicyCatalog·DialoguePolicy·SessionLifecycle·OutputGuard, 서버의 검증된 사실 투영과 필요한 최소 공개 계약/Java 모델·Core 경유 어댑터 | L | 기존 realtime-activity 중심 실행 산출물에 포함. 필요한 기존 Core 어댑터 변경 포함, 새 원격 서비스 없음 |
| U17 | u17-guardrail-presentation | ui | ConversationPresentation, RP-1–RP-4의 기존 copy/사유/인용/인사 매핑, 현재 기기 웹의 계약/재생 확인 소비 | M | 기존 frontend 산출물에 포함, 새 페이지/런타임 없음 |

### 토폴로지와 소유

- 기본 A안: `u17-guardrail-presentation depends on u16-service-guardrail`. U16은 이번 신규 Unit 의존이 없다. UI가 서버 사실을 추측하지 않아야 하기 때문이다.
- 상태·계약 생성·세션 사실/보존 책임은 U16, 프런트 copy/표시·현재 기기 웹 계약 소비는 U17이 소유한다. 동일 운영 파일을 두 단위에 배정하지 않는다.
- U16이 contracts/·Java DTO·필요한 기존 Core/Realtime 어댑터를 소유한다. U17은 프런트 API 타입/파싱과 표시를 소비한다. 구체적 필드·호환성·파일은 다음 Contract/Functional Design에서 확정한다.
- 의존성만 기록한다. 경제적 우선순위·권장 빌드 순서·임계 경로·Bolt 묶음은 다음 Delivery Planning에서 정한다.
- 현재 두 단위 사이에는 직접 의존이 있어 독립적인 Unit 병렬 집합은 없다. 계약 확정 뒤 UI 대역 작업을 할 수 있어도 Unit 의존을 없애지는 않는다.

### 범위와 검증

- U16은 새 지시문/모드 선택, 실제 안내/현재 참여, 종료·휴식·정정·같은 요구 1회, 기존 JEV/연습/시계/저장 보존, 종료 판단 로컬/J6 실제 요청 경계 시험을 포함한다.
- U17은 기존 레이아웃과 보호자 P-01을 유지하고 거절/무응답/기술 실패·실제 종료 표현·인사 재생 사실을 표시한다. 기기 확인 관련 wire 소비 변경이 필요하면 이 단위가 맡는다.
- 같은 스토리가 서버 결과와 UI 결과를 함께 요구할 수 있다. story-map에 주 담당과 협력 단위를 구분하고 부분 구현을 전체 스토리 완료로 표시하지 않는다.
- 두 단위는 기존 앱에 공유/내장되며 독립 배포 가능한 새 서비스가 아니다. 이번에는 실제 배포를 하지 않는다.
- 새 데이터 보관 정책·공급자·리포트/기억 전면 개편은 제외한다. 시험은 가짜 공급자이며 전체 회귀와 실제 모델/기기 성공의 증거 구분을 유지한다.
- classic의 skeleton off를 따른다. 새 최소 연결본/첫 단위 스켈레톤 승인 의식을 만들지 않는다. 기존 끝-끝 회귀는 유지한다.

## Plan Approval

Q1-A에 따라 U16 service(L), U17 ui(M)의 2개 단위를 제안한다. U17은 U16에 의존하며 기존 두 앱/프런트 산출물 안에서 구현하고 새 원격 서비스·배포는 없다. 아래 계획 선택 이후 실제 Unit 산출물과 실행 DAG를 생성한다.

- Approve Plan — 위 2개 단위·kind·소유·의존성으로 산출물 생성
- Revise Plan — 단위 경계/크기/의존성 수정

[Answer]: Approve Plan

## Assumptions & Open Questions

- 상대 복잡도 L/M은 현재 범위의 비교이며 소요일·실측 파일 수·일정 약속이 아니다. 공유 상태/계약 변경으로 서버 쪽이 더 크다.
- API/DB 필드·승인 음성 자원·정확한 코드 파일 수는 다음 계약/기능 설계에서 확인한다. 범위를 넓혀야 하는 차이는 사람에게 다시 묻는다.
- 논리 책임의 분리는 코드/데이터 소유의 분리이며, 단위 수나 배포 프로세스 수와 같을 필요가 없다.
