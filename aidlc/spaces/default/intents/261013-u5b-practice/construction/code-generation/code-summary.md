# U5b 독립 구현 코드 요약

## 완료 범위

이번 결과는 **U5b 독립 부품 구현 및 계약 대역 시험 완료**다. 실제 U5b 통합과 현재 Intent 전체 완료를 의미하지 않는다. 승인 계획 Step 1~15의 독립 범위, Q1~Q6 및 CG-Q1을 구현했다. I1~I5·U5b-J3~J5의 미합의 통합 조건은 유지한다.

## 생성·변경 파일

| 경로 | 내용 |
|---|---|
| backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/ | 운영 Java 22개: 불변 모델, JDBC 저장소, 판정·시간 예산, 단계·출력 포트, 기록·삭제, 타입 설정 |
| backend/core-api/src/main/java/kr/mujoong/core/practice/start/ | FrozenGoal.java, WeightedGoalOrder.java, PreparedPracticePackageMapper.java |
| frontend/src/parent/practice/ | 신규 practiceStart.ts, PracticeStartAction.tsx, practiceProgress.ts, PracticeHeader.tsx, u5bCopy.ts |
| backend/realtime-activity/src/main/resources/db/pending/realtime/V4__u5b_practice.sql | 다섯 연습 표와 참조·중복·상태 제약; 자동 적용 제외 |
| backend/realtime-activity/src/main/resources/practice/u5b-independent.yml | 자동 로드하지 않는 독립 설정 예시; 명시 바인딩 시험 |
| backend/realtime-activity/src/test/java/kr/mujoong/realtime/practice/ | 시험 클래스 10개와 PracticeDb·EvaluationFixture·ProtocolFixture 대역/지원 파일 |
| backend/core-api/src/test/java/kr/mujoong/core/practice/start/ 및 frontend/tests/u5b/ | Core 시험 2개와 프런트 시험 2개 |
| backend/coverage-scope/u5b.txt·u5b-core.txt, frontend/coverage-scope/u5b.json | 신규 운영 파일 22+3+5개를 빠짐없이 측정 |
| docs/units/u5b/design.md·report.md | CG-Q1 추가 결정, 실행 증거, 계약 편차와 후속 통합 의무 |

기존 운영 앱 파일의 변경은 남기지 않았다. application.yml에 추가했던 설정은 기존 계약과 충돌하여 제거했다. 기존 화면 18·12, 실제 PracticeStart 빈, U4b 확장점 및 메인·U4b Intent는 수정하지 않았다. 작업 시작 전에 존재한 다른 미추적 파일과 감사 기록은 이번 구현의 산출물로 계수하지 않는다.

## 주요 구현 결정

- 반응을 먼저 저장하고 판정 대기·확정·종료 대기를 구분한다. 최종 Evaluation과 반응 라벨은 같은 트랜잭션에서 조건부 갱신한다. 중복 요청·종료·삭제 이후 늦은 결과를 차단한다.
- 최초 수락부터 전체 12초, 호출당 최대 5초·재시도 최대 1회를 적용한다. 큐·재시도·최종 저장 예산을 포함하고 R을 명시 주입한다. 저장 제한에 남은 예산을 전달하고 커밋 응답 지연은 시간 위반으로 드러낸다. 단조 경과와 최대 관측 시각으로 UTC 역행에 따른 예산 연장을 막는다.
- 첫 반응 20초 무응답은 NOT_PERFORMED/NO_RESPONSE로 보존하고 평가 호출 없이 도움 동의를 질문한다. 실제 질문 재생 완료부터 10초를 재며, 동의하면 Cue 1회, 거부·무응답이면 Cue 없이 새 상황을 준비한다.
- 새 상황 실패에서는 ORIGINAL과 도움을 보존한다. 미합의 외부 정산·후속 전이는 보류한다. 비동기 저장 실패는 오류 수신 포트로 전달한다.
- 타이머와 생성 완료는 목표·시도 ID, 단계, 세대와 시도 상태가 예약 당시와 모두 일치할 때만 반영한다. 이전 목표의 만료와 늦은 생성 결과가 다음 목표를 변경하지 않는 Clock 시험을 포함한다.
- 같은 고정 목표 재전달은 기존 진행 상태를 보존한다. Core 가중 비복원 추첨과 C8 필드 변환을 독립 시험하며 실제 준비 저장·API는 만들지 않는다.
- 보호자 진행은 허용된 네 필드만 처리한다. 공급자 성공 대역은 시험에만 존재하며 운영 연결이나 화면 시작 활성화를 추가하지 않는다.

## 검증 결과

2026-10-14 BT-F01 수정 후 프런트를 재검증했다. PracticeHeader.tsx는 u5bCopy.ts의 heading 함수를 사용하며 기존 제목 문구·공백·구분자를 유지한다. practice-progress.test.tsx의 기존 사례에 정확한 제목 textContent 단언을 추가했다. 시험 건수와 기존 단언·설정·커버리지 범위는 유지했다.

재시도 결과: U5b 16건, U4a/U5a 116건 모두 통과. 프런트 운영 파일 5개 전체의 줄 60/60=100%, 문장 84/84, 함수 18/18, 분기 73/74다. lint 오류 3건을 해소했고 lint·typecheck·production build 모두 종료 0이며 16페이지를 생성했다. 아래 백엔드 수치는 이전 실행 증거다. 이번에는 백엔드·빌드 입력을 수정하거나 백엔드 시험을 재실행하지 않았으며 사전·사후 해시 일치까지 검증한 것은 아니다. 원래 BT 실패와 Loop-Back Log는 보존한다.

| 검증 | 결과 |
|---|---|
| Realtime U5b | 74건 통과, 실패·오류·건너뜀 0; 546/553줄 = 98.734% |
| Core U5b | 12건 통과, 실패·오류·건너뜀 0; 77/78줄 = 98.718% |
| 프런트 U5b | BT-F01 수정 후 16건 통과; 60/60줄 = 100%; lint·typecheck·production build 통과 |
| U1 전체 회귀 | 1,483건 발견, 1,480건 통과, 기존 LOCAL PostgreSQL 방식 3건 환경 미설치로 건너뜀, 실패·오류 0 |
| 최종 Realtime 변경 뒤 U1 | 542건 통과, 실패·오류·건너뜀 0 |
| U3 회귀 | 283건 통과, 실패·오류·건너뜀 0 |
| 목표 간 작업 소유권 최종 수정 뒤 Realtime U1·U3 | 합계 825건 재실행 통과, 실패·오류·건너뜀 0 |
| Core U4a·U5a / 프런트 U4a·U5a | 각각 110건 / 116건 통과 |

U5b 합계 102건이다. 각 층 구현 뒤 시험을 작성·실행한 test-after 순서를 유지했다. 설정·시간 경계와 목표 간 비동기 작업 소유권의 추가 사례 때문에 계획의 기본 60~96건보다 많으며 기준을 낮추지 않았다. 실제 PostgreSQL은 폐기 DB에서 검증했다. U1의 LOCAL 3건은 Docker 방식 통과와 구분한다. 실행 명령·실패 수정 이력·측정 경로는 docs/units/u5b/report.md에 기록했다. Gradle 결과 XML은 다음 태그 실행으로 교체되므로 명령 완료 직후 집계를 보존했다.

## 계획 대비 편차와 제한

1. V4는 db/pending/realtime에 보관한다. 현재 V3가 없는 자동 적용 경로에 넣지 않으며 후속 승인 통합에서 이동·V3→V4 검증이 필요하다.
2. 독립 설정은 공용 application.yml에 등록하지 않는다. 최초 추가 시 U1 설정 계약 2건이 실패했고, 기존 단언을 유지한 채 분리한 후 회귀가 통과했다. 실제 설정 소유권·RealtimeProperties 연결은 후속 계획에서 합의해야 한다.
3. 최초 저장소 시험은 존재하지 않는 시험 지원 역할 식별자로 컴파일 실패했다. 실제 Role.REALTIME_APP으로 수정 후 8건과 최종 묶음을 통과했다.
4. 실제 판정 공급자·운영 R·판정 타이머 연결·부하 성능은 미검증이다. 12초 Clock 시험은 운영 성능 보장이 아니다. 최종 커밋 실패/지연을 성공으로 위장하지 않는다.
5. 실제 확장점·Core 준비 저장/API·어댑터·화면 연결·V3→V4는 이번 완료 범위 밖이다. 추적표의 Deferred를 독립 시험으로 해소하지 않는다.
6. Build and Test에서 확인한 실제 통합·운영 시간 예산·보안/접근성·마이그레이션·Cue 품질·성능/동시성 미검증은 유지한다. 특히 Cue의 5단어 일치 검사와 평가자 승인은 출력 대역 시험만으로 검증된 것이 아니다. lint 수정 성공은 이 목표들의 달성 증거가 아니다.

## 후속 통합과 추적

같은 Intent의 후속 Code Generation 계획에 새 Plan Approval을 받아 INT-1~INT-5를 구현한다. U4b V3 main 병합 및 I1~I5 합의, 최신 U4b 브랜치/PR·CI와 기준 커밋 ffcfb8a09a523b2cb8fff885b1a9fadaefa27251 이후 차이를 다시 확인해야 한다. 독립 Build and Test 통과만으로 Intent 전체 완료로 전환하지 않는다.

traceability.json은 제품 전체 기준과 독립 검증을 분리한다. OK는 해당 로컬 규칙의 실제 구현/시험 파일만 가리킨다. 실제 통합이 필요한 AC·FR·NFR 및 규칙은 Deferred 사유와 독립 증거를 함께 기록한다. 상세 후속 승인 경계는 승인된 code-generation-plan.md를 따른다.

