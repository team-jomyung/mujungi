# U5b 최종 요구사항 연결 검사

## 판정

2026-10-14 BT-F01 수정 후 재검증에서도 최신 code-generation/traceability.json의 제품 기준 44행은 Deferred 43개·N/A 1개다. 제품 행의 부분 구현 경로 7개와 전체 추적표의 구현·시험 경로 20개 모두 존재한다. 제목 문구 단언과 lint 통과는 AC4.2.2의 실제 화면 연결 완료를 대신하지 않는다. 기존 표와 후속 통합 승인 조건을 유지한다.

FAIL: 제품 전체 FR/NFR 및 담당 AC의 OK 완료 증거가 없다. 독립 구현 승인과 별개로 실제 통합이 필요한 항목은 Deferred를 유지한다. NFR8의 N/A는 범위 제외 사유이며 OK를 대신하지 않는다. 실제 통합 누락을 허위 해소하지 않는다.

## ID별 근거

원본: inception/requirements-analysis/requirements.md와 승인된 code-generation/traceability.json. 이 Intent의 User Stories 단계는 미실행이다. 재사용 US4.1/US4.2/US6.5에서 배정된 AC를 함께 열거한다. FR4/FR5 같은 문장 속 범주 표기는 하위 FR ID의 별도 요구사항으로 만들지 않는다. FR7.8처럼 묶음 참조의 축약 ID는 코드 생성 추적표와 대조한다.

| ID | 추적 상태 | 소유 | 부분 구현 파일 | 미충족 사유 |
|---|---|---|---|---|
| AC4.1.3 | Deferred | U5b 독립/후속 INT | frontend/src/parent/practice/practiceStart.ts | I5/INT-2·INT-4: Core 준비 저장·API·실제 시작 인계·화면 연결 미실행 |
| AC4.1.4 | Deferred | U5b 독립/후속 INT | backend/core-api/src/main/java/kr/mujoong/core/practice/start/WeightedGoalOrder.java | I5/INT-2·INT-4: Core 준비 저장·API·실제 시작 인계·화면 연결 미실행 |
| AC4.1.10 | Deferred | U5b 독립/후속 INT | frontend/src/parent/practice/practiceStart.ts | I5/INT-2·INT-4: Core 준비 저장·API·실제 시작 인계·화면 연결 미실행 |
| AC4.1.12 | Deferred | U5b 독립/후속 INT | backend/core-api/src/main/java/kr/mujoong/core/practice/start/FrozenGoal.java | I5/INT-2·INT-4: Core 준비 저장·API·실제 시작 인계·화면 연결 미실행 |
| AC4.2.1 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| AC4.2.2 | Deferred | U5b 독립/후속 INT | frontend/src/parent/practice/PracticeHeader.tsx | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| AC4.2.3 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| AC4.2.4 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| AC4.2.5 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| AC4.2.6 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| AC4.2.7 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/ResponseEvaluator.java | I2/I3/INT-1·INT-5: 실제 판정/타이머 연결·운영 R·부하/저장 상한과 종료 투영 검증 필요 |
| AC4.2.8 | Deferred | U5b 독립/후속 INT | frontend/src/parent/practice/practiceProgress.ts | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| AC4.2.9 | Deferred | U5b 독립/후속 INT | backend/core-api/src/main/java/kr/mujoong/core/practice/start/FrozenGoal.java | I5/INT-2·INT-4: Core 준비 저장·API·실제 시작 인계·화면 연결 미실행 |
| AC4.2.10 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| AC6.5.1 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| AC6.5.2 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| AC6.5.3 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| AC6.5.4 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| AC6.5.5 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| AC6.5.6 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| AC6.5.7 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| AC6.5.8 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1/I4/INT-3: 실제 상황 어댑터·노출 반영 및 실패 정책 합의 미완료 |
| FR1.6 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I5/INT-1·INT-2: 실제 삭제 참가자 등록·Core 준비 데이터 삭제 통합 미실행 |
| FR4.1 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I5/INT-2·INT-4: Core 준비 저장·API·실제 시작 인계·화면 연결 미실행 |
| FR4.2 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| FR4.3 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| FR5.5 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| FR5.6 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| FR5.7 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| FR7.1 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| FR7.5 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1/I4/INT-3: 실제 상황 어댑터·노출 반영 및 실패 정책 합의 미완료 |
| FR7.6 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I2/I3/INT-1·INT-5: 실제 판정/타이머 연결·운영 R·부하/저장 상한과 종료 투영 검증 필요 |
| FR7.8 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| NFR1 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| NFR2 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| NFR3 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| NFR4 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| NFR5 | Deferred | U5b 독립/후속 INT | 없음: 실측/범위 제외 | 전체 서비스 실측 목표는 상위 인셉션의 측정 전 조건을 유지한다. 실제 공급자 연동·부하 검증이 남아 있으며 독립 계약 대역 시험으로 달성을 주장하지 않는다. 후속 통합 승인 후 검증한다. |
| NFR6 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I2/I3/INT-1·INT-5: 실제 판정/타이머 연결·운영 R·부하/저장 상한과 종료 투영 검증 필요 |
| NFR7 | Deferred | U5b 독립/후속 INT | 없음: 실측/범위 제외 | 전체 서비스 지연·동시성 목표는 실제 공급자와 부하 시험으로 검증해야 한다. 이번 독립 구현은 성능 실측 완료가 아니며 후속 통합 승인 후 검증한다. |
| NFR8 | N/A | U5b 독립/후속 INT | 없음: 실측/범위 제외 | 리포트 생성은 U6 소유 범위로 이번 U5b 독립 구현 범위에서 제외한다. U5b 기록 투영 대역 시험은 리포트 생성 완료 증거가 아니다. |
| NFR9 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| NFR10 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I1~I5/INT-1~INT-5: 독립 부품·대역 증거만 확보; 실제 재생·생명주기·기록·화면 경계 통합 및 담당자 합의 필요 |
| NFR12 | Deferred | U5b 독립/후속 INT | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java | I5/INT-1·INT-2: 실제 삭제 참가자 등록·Core 준비 데이터 삭제 통합 미실행 |

## 미완료 처리

FR 11개·NFR 11개·담당 AC 22개를 확인한다. 실제 OK 기준으로 완료된 FR/NFR/AC는 0개다. Deferred와 N/A를 완료로 세지 않는다. 위 부분 구현 파일은 존재 여부를 검사하되 그 존재만으로 제품 기준을 충족하지 않는다.

같은 Intent에서 U4b main 병합·I1~I5 합의 후 후속 Code Generation 새 Plan Approval과 실제 검증이 필요하다. 그 전 배포/Intent 전체 완료로 전환하지 않는다.

