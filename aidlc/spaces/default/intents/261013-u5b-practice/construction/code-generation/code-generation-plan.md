# U5b 코드 생성 계획

## Summary

- Builds: BT-F01의 JSX 문구 분리 오류 3건 수정; 기존 독립 구현을 보존하고 실제 통합은 별도 승인·검증으로 완료
- Touches: frontend/src/parent/practice/PracticeHeader.tsx·u5bCopy.ts, 필요한 기존 U5b 시험 및 실행 기록
- Tests: U5b 프런트 16건·U4a/U5a 116건 회귀, 기존 U5b 프런트 전체 범위 줄 80% 이상, lint·typecheck·production build

## Build and Test 재시도 범위

2026-10-14 Build and Test의 BT-F01에 대한 사용자 선택 Retry with fix를 반영한다. PracticeHeader.tsx의 제목 안에 있는 JSX 문자열 3건이 react/jsx-no-literals 검사에서 실패했다. 제목 문구의 조합을 기존 u5bCopy.ts로 옮기고 표시 문구와 접근성 의미를 유지한다. 예상 작업 시간은 20~40분, 추가 유료 자원 비용은 0, 변경 위험은 낮음이다.

기존 Step 1~15는 완료된 독립 구현의 기준으로 내용·순서·시험 기준과 체크 상태를 보존한다. 이번 재승인 이후 실행할 작업은 아래 Step 16~19이며 기존 Step 13~15 범위의 결함 수정과 재검증에 한정한다. ESLint 규칙, 커버리지 범위·80% 하한, 기존 시험 단언은 낮추지 않는다. 백엔드 소스와 빌드 입력이 그대로이면 기존 백엔드 검증 증거를 재사용하되 새로 실행했다고 기록하지 않는다. Build and Test의 필수 검증은 해당 단계에서 별도로 적용한다.

기존 Q1~Q6·CG-Q1 결정, U5b-J3~J5 및 I1~I5 미합의 상태, INT-1~INT-5 후속 통합 승인 경계를 유지한다. 메인·U4b Intent와 기존 답변·감사 기록을 변경하지 않는다. 실제 통합·공급자·부하·운영 시간 상한 등 Build and Test에서 미검증으로 남은 목표는 이 수정으로 충족된 것으로 처리하지 않는다.

- [x] Step 16 — 수정 준비: BT-F01의 원래 실패 결과와 test-results.md의 Loop-Back Log를 보존하고 변경 대상 두 파일 및 기존 프런트 시험·실행기를 확인한다. 기존 15단계와 이번 재시도를 실행 기록에서 구분한다.
- [x] Step 17 — 문구 분리 구현: PracticeHeader.tsx의 제목 문구 조합을 u5bCopy.ts의 함수 또는 속성으로 옮긴다. 목표 번호·전체 수·목표명, 기존 공백·구분자, 제목과 접근성 동작을 유지한다.
- [x] Step 18 — 프런트 재검증: 구현 후 기존 U5b 16건 및 U4a/U5a 116건을 실행하고 실제 발견 수·결과를 기록한다. 기존 시험에 관찰 가능한 동작 검증이 빠진 경우에만 보완한다. 기존 운영 파일 5개 전체의 줄 커버리지 80% 이상과 lint·typecheck·production build를 확인한다.
- [ ] Step 19 — 증거 갱신과 재검토: code-summary.md·traceability.json·docs/units/u5b/report.md에 실제 수정·검증 결과와 미검증 항목을 반영하고 새 아키텍처 검토를 요청한다. 이후 Build and Test는 기존 문서를 보완하는 방식으로 수행하고 원래 실패와 Loop-Back Log를 삭제하지 않는다. 전체 U5b 통합 완료로 전환하지 않는다.

## Sources

- 정본: docs/units/u5b/brief.md, 승인된 U5b requirements.md 및 construction/functional-design의 entities.md·rules.md·functional-spec.md·frontend-components.md·traceability.json.
- 기능 설계 Q1~Q6를 그대로 재사용한다. 추가 정책은 이 폴더 planning-clarifications.md의 CG-Q1 사용자 답변이다.
- 메인 인셉션의 stories.md US4.1/US4.2/US6.5, contract-summary.md C8/C10·기록·시간 계약은 읽기 전용 근거다. CodeKB는 aidlc/spaces/default/codekb/mujungi/다.
- 현재 로컬 Realtime에는 V1/V2가 있으며 U4b V3/PracticeSessionExtension의 조회 기준은 원격 ffcfb8a09a523b2cb8fff885b1a9fadaefa27251이다. 원격 조회는 병합/담당자 합의가 아니다.
- Java 21·Gradle/JUnit 5·PostgreSQL 시험 지원, frontend Vitest/jsdom·기존 coverage-scope 설정을 재사용한다.

## 구현 범위와 의존 조건

이번 실행은 단일 단계의 구현 반복이다. 우선 기존에 허용된 독립 부품과 명시적 계약 대역으로 구현한다. 운영 공급자는 미연결이며 합성 성공 공급자는 시험 소스에만 둔다. 아직 합의되지 않은 API/상대 실행기 변경을 승인된 것으로 간주하지 않는다.

| 항목 | 이번 계획에서 구현/검증 | 실제 통합 전에 필요한 것 |
|---|---|---|
| I1 | 재생 결과가 구분되는 U5b 입력·출력 포트, 장면/세대 검사와 시험 대역 | U4b 실제 재생 증거, 초기 자유 출력 차단, 길이/역할 입력 합의 |
| I2 | PENDING/FINALIZED/TERMINATED_PENDING 보존·조건부 원자 저장·오류 전달·정산 투영 부품 | 종료 대기 반응의 U4b/U6 외부 표현·정산 창·스냅숏 실패/복구 합의 |
| I3 | Clock을 주입한 단계 및 판정 시계, 순서별 경합 불변식 | 실제 정지형 단계 시계·SILENCE·종료/만료 우선순위 합의 |
| I4 | 새 상황 생성 실패/시간 초과 내부 사실과 ORIGINAL 보존 | 목표 최종 상태·스냅숏 미실시 표현·후속 전이 합의 |
| I5 | FrozenGoal/가중 순서/기존 C8 필드 패키지 변환, 준비 경계의 명세·대역 | PreparedStartContext·Core 준비 저장/API·V7 이후 변경·실제 활성 조회/멱등 시작 합의 |
| Realtime V4 | 독립 연습 표·저장소와 V4 마이그레이션 산출물 | 실제 V3→V4 순차 적용 시험과 U4b가 main에 먼저 병합된 증거 |

U4b 실행기의 복제나 가짜 ACTIVE를 만들지 않는다. 현재 컴파일 가능한 U5b 소유 포트 뒤에서 독립 로직을 작성하며, 존재하지 않는 U4b 타입을 운영 import로 추가하지 않는다. 실제 확장점 어댑터는 합의와 해당 소스의 정상 병합 후에 구현한다. V4를 V3 없는 공유 DB에 먼저 적용하지 않는다. DB 시험은 폐기 가능한 독립 DB에서만 수행하고 V3 이전 기초 저장 시험과 V3→V4 업그레이드 시험 결과를 구분한다. 상대 V3를 흉내 낸 테스트 마이그레이션을 만들지 않는다.

Core의 공개 연습 시작·준비 조회 API와 준비 저장 V7+, 현재 기본 실패 포트의 실제 빈 교체, 화면 18의 운영 시작 활성화는 I5 확인 전 실행 범위에 넣지 않는다. 프런트 표시·시작 조정 부품은 대역으로 검증하되 실제 시작 성공 기능을 활성화하지 않는다. 최종 추적표는 해당 AC를 Deferred로 유지하며 전체 U5b 통합 완료를 주장하지 않는다. 합의 자료가 도착하면 그 자료에 맞춘 후속 계획을 제시한다.

## 이번 완료와 전체 U5b 완료의 경계

기존 독립 구현 Plan Approval의 구현 권한은 아래 Step 1~15와 그 시험 기준에 한정한다. 15단계의 내용·순서·시험 기준은 유지한다. 이번 재시도 승인 범위는 위 Step 16~19의 결함 수정·재검증이며 후속 통합 권한을 추가하지 않는다. 이 계획의 완료 명칭은 **U5b 독립 부품 구현 및 계약 대역 시험 완료**다. 운영 연습 시작부터 종료·기록까지의 실제 U5b 통합 완료와 구분한다.

| 완료 구분 | 필요한 증거 | 이 상태로 허용되지 않는 판단 |
|---|---|---|
| 이번 독립 구현 완료 | 15단계 산출물, 독립/DB/계약 대역 시험, U1·U3·U4a·U5a 회귀, U5b 변경 범위 줄 커버리지 80% 이상, 구현 검토 | 실제 PracticeSessionExtension 연결 완료, 화면 18 운영 시작 가능, V3→V4 업그레이드 검증 완료, 전체 U5b 완료 |
| 전체 U5b 통합 완료 | 아래 후속 계획의 별도 승인과 구현·아키텍처 검토·실제 통합/회귀/마이그레이션 검증, 필수 통합 AC의 증거 | 대역 시험만으로 실제 연결을 대체하거나 미합의/미검증 필수 항목을 완료 처리 |

독립 구현의 code-summary.md·traceability.json·docs/units/u5b/report.md에는 완료 범위를 위 명칭으로 기록한다. 실제 통합이 필요한 AC는 부분 구현 파일을 연결할 수 있어도 전체 기준을 충족한 OK로 승격하지 않는다. Deferred 사유에 후속 통합 항목과 필요한 합의·시험 증거를 연결한다. 실패한 회귀 또는 80% 미달은 독립 구현 완료에서도 면제하지 않는다.

## 같은 Intent의 후속 통합 계획과 별도 승인

후속 통합은 **현재 U5b Intent(261013-u5b-practice) 안에서 별도 Code Generation 계획으로 수행**한다. 별도 Intent로 자동 분리하거나 현재 15단계에 조용히 추가하지 않는다. 이번 승인과 독립 구현 완료 승인은 후속 통합 구현 승인이 아니다.

독립 구현 이후 Build and Test를 수행하더라도 검증 대상은 독립 구현으로 명시한다. Build and Test가 누락된 실제 연결 코드를 대신 구현하거나 전체 통합 완료를 선언하지 않는다. 현재 워크플로에 후속 Code Generation이 자동 예약됐다고 주장하지 않는다. 후속 진입은 같은 Intent의 Code Generation 재진입 절차로 처리하고, 그 시점의 실제 입력으로 계획·시험 지침을 갱신한 뒤 **새 Plan Approval**을 요청한다. 현재 단계 포인터나 단계 구성을 이 문서 수정만으로 변경하지 않는다.

후속 계획 작성 전에는 독립 구현 계획·시험 지침·실행 결과의 버전을 U5b 기록에서 보존한다. 후속 Plan Approval에는 통합 전용 변경 파일, 계약 합의 근거, 최신 소스 차이, 정확한 시험 명령·마이그레이션 경로·완료 기준을 제시한다. I1~I5 내용에 따라 필요한 설계 보완도 U5b 문서에서 추적하며 기존 Q1~Q6 답변이나 메인·U4b Intent를 고치지 않는다.

**착수 전제:** U4b의 실제 V3 변경이 main에 병합됐다는 증거와 I1~I5의 담당자 합의가 모두 확보되어야 한다. 합의되지 않은 항목은 기존 상태를 유지한다. 전제 충족만으로 코드를 변경하지 않으며, 아래 범위를 구체화한 후속 계획의 별도 승인 후에 구현한다.

| 후속 통합 항목 | 후속 Code Generation 계획에 포함할 작업 | 완료 증거 |
|---|---|---|
| INT-1 Realtime 실제 연결 | PracticeProtocol을 실제 PracticeSessionExtension에 연결; 실제 재생 성공/실패·초기 출력·시계·종료·정산·삭제 참가자 순서 연결 | I1/I2/I3 합의와 실제 확장점 계약/통합 시험; 중복/늦은 사건·종료 경합 검증 |
| INT-2 Core 시작 준비와 API | PracticeStart 구현/빈 등록, 준비 문맥·영속 저장·필요한 V7 이후 마이그레이션, 준비/시작 API·멱등 인계 구현 | I5/U4a 합의; 첫 상황 준비 후 REQUESTED 진입, 기존 10초·C10 ACTIVE 유지, 422 및 중복 시작 방지 시험 |
| INT-3 실제 어댑터·기록/노출 | PracticeSessionContextPort·ScenarioRecordPort의 실제 어댑터, ActivityRecorded→reconcileExposures, record.practice와 삭제/실패 보존 연결 | 실제 고정 목표/활성 상태·실노출 반영, I2/I4의 정산·새 상황 실패 정책과 U6 소비 계약 시험 |
| INT-4 보호자 화면 연결 | 화면 18 연습 시작 활성화와 실제 API 오류/재시도 연결, 화면 12의 실제 n/N·단계 반영 | 실제 서버·기기 경계를 포함한 18→12→종료/기록 흐름; 원문/판정 미노출·멱등 재시도·조회 오류 구분 |
| INT-5 마이그레이션·종합 검증 | 실제 병합 V3에서 V4 순차 적용, 기존 데이터 보존·정리 확인, 추가 Core 변경 적용 검증, 부하/시간 예산 검증 | 실제 V3→V4 결과와 적용 커밋; U1·U3·U4a·U5a 및 통합 후 U4b 회귀; 확대된 U5b 변경 범위 줄 커버리지 80% 이상; 12초/R 검증 |

INT-1~INT-5는 전체 완료에 필요한 후속 작업 목록이며 이번 15단계의 추가 실행 항목이나 현재 승인 대상 구현이 아니다. 실제 공급자 연결은 기존 범위대로 미연결을 유지한다. 실제 애플리케이션 경계 통합 시험과 운영 AI 공급자 연결/성능 달성은 구분한다.

## 통합 직전 U4b 소스와 CI 재확인

비교 출발점은 현재 검토 기준 `ffcfb8a09a523b2cb8fff885b1a9fadaefa27251`이다. 이 값이 이후에도 최신이라고 가정하지 않는다. 지금은 계획만 보완하며 최신 브랜치/CI를 확인했다고 기록하지 않는다.

후속 계획 작성 직전과 실제 통합 착수 직전에 원격 main·unit/u4b-activity-realtime 및 관련 PR을 다시 확인한다. 브랜치가 병합 뒤 삭제되었거나 squash 병합되었으면 해당 PR의 소스 커밋과 main 병합 커밋을 확인하며 단순 조상 관계만으로 병합 여부를 판단하지 않는다.

비교·증거 기록은 U5b의 후속 통합 준비 문서에 남긴다. 기록에는 조회 시각, 기준 커밋, 최신 U4b 소스 커밋, PR/병합 커밋, 실제 사용할 main 기준 커밋, 변경 파일/영향, I1~I5 합의 근거, CI 실행 링크·검증 대상 SHA·필수 검사 결과를 포함한다. 이전 브랜치나 다른 SHA의 성공 결과를 현재 코드의 CI 통과로 재사용하지 않는다. CI가 실패/진행 중이거나 확인 권한이 없으면 미확인으로 남기고 통합 준비 완료로 처리하지 않는다.

기준 커밋 이후의 PracticeSessionExtension·SessionExecutor·재생 사건/출력·시계·정산/삭제·V3 및 관련 계약 변경을 비교한다. 후속 승인 뒤 실제 착수 전 다시 변경된 내용이 발견되면 영향과 시험 변경을 계획에 반영하고 바뀐 통합 범위의 승인을 다시 받는다. U4b 자체 CI와 별도로 U5b 연결 결과의 통합 브랜치/PR SHA에 대한 CI를 검증한다.

## 전체 완료 전 확인 조건

이번 15단계의 완료나 독립 범위 Build and Test 통과만으로 현재 Intent 또는 전체 U5b를 최종 완료로 전환하지 않는다. 독립 단계의 완료 상태와 제품 전체 완료 상태를 보고서/추적표에서 분리한다. 아래 조건을 충족하기 전에는 후속 통합을 미완료로 유지하며, 최종 완료/배포 승인으로 넘어가 통합 누락을 감추지 않는다.

- U4b V3의 main 병합 및 최신 소스/CI 재확인 증거가 있다.
- I1~I5 합의가 기록됐고, 같은 Intent의 후속 Code Generation 계획에 별도 Plan Approval이 있다.
- INT-1~INT-5가 구현·검토·실제 통합 시험으로 확인됐다. 실제 시작·출력·종료/기록·노출·삭제와 필수 AC에 미합의/미검증 Deferred가 남지 않는다.
- 기존 U1·U3·U4a·U5a 회귀 및 통합 후 U4b 회귀가 통과하고, 변경된 U5b 전체 측정 범위의 줄 커버리지 80% 이상을 충족한다.
- 실제 V3→V4 및 필요한 Core 마이그레이션 검증이 완료되고, 최종 보고서가 독립 시험과 실제 통합 증거를 구분한다.

전제가 아직 충족되지 않았다면 현재 Intent의 남은 통합 작업과 의존 조건을 명시하여 다음 작업으로 유지한다. 단계 재진입/최종 상태 변경은 AI-DLC의 정식 전환으로만 수행하며 이 문서를 근거로 상태 파일을 직접 편집하지 않는다.

## CG-Q1 설계 결정과 상태 전이

**10초는 기존 계약값이다.** 메인 contract-summary.md 7절 practice.help-consent-wait와 contracts/timing.md 2절의 예약 키는 모두 10초다. 시계 시작점은 도움 동의 질문의 재생 완료다. 승인된 U5b FR4.2 AC4.2.4 및 AC4.2.10(b)/NFR6은 거부 DECLINED와 무응답 NO_RESPONSE를 구분한다. 이번 새 결정은 B14에서 미확정이었던 첫 무응답 뒤 HELP_OFFER 연결의 로컬 정책이다. 기존 Q1~Q6는 바꾸지 않는다.

| 현재 | 사건 | 저장/호출 | 다음 |
|---|---|---|---|
| FIRST_RESPONSE | 실제 기회 시작 뒤 20초 무응답 | FIRST에 NOT_PERFORMED/NO_RESPONSE, utteranceSeq=null, 평가 호출 0회; 기회 한 번 닫기 | HELP_OFFER 질문 출력 요청 |
| HELP_OFFER 출력 대기 | 동의 질문의 실제 재생 완료 증거 | 이 증거의 서버 수신 시각으로 별도 10초 시계 시작 | HELP_OFFER 동의 대기 |
| HELP_OFFER 동의 대기 | 유효 동의 | ACCEPTED 한 번, Cue 요청 한 번 | CUE |
| CUE | 실제 제공 및 완료 증거 | 실제 도움 사실·내용·시각 보존 | AFTER_CUE_RESPONSE 한 번 |
| HELP_OFFER 동의 대기 | 명시적 거부 | DECLINED, Cue 호출 0회 | NEW_SCENARIO 준비 |
| HELP_OFFER 동의 대기 | 10초 만료 | NO_RESPONSE, Cue 호출 0회 | NEW_SCENARIO 준비 |
| 도움 질문/Cue 출력 | 실패·생략·완료 증거 없음 | 성공·무응답으로 위장하지 않음 | 실제 처리 정책은 I1/I3 합의 대기 |
| 어느 단계든 | 종료/삭제가 먼저 반영됨 | 기존 반응/도움 보존, 세대 무효화 | 추가 질문/Cue/새 기회 금지 |
| NEW_SCENARIO 준비 | 생성 실패/시간 초과 | ORIGINAL 기록 보존, 내부 실패만 기록 | I4 합의 전 최종 상태/후속 전이 보류 |

10초를 출력 요청 시각부터 시작하지 않는다. 중복 재생 완료 사건이 시계를 연장하지 않는다. 동의와 시계 만료의 동시 우선순위를 실제 U4b 계약으로 임의 확정하지 않고, 대역에서는 양쪽 직렬 순서를 검증해 결과가 한 번만 저장됨을 보장한다. Cue 5초 실패 규칙과 도움 후/새 상황 20초의 기존 설계 제안은 기능 설계를 따른다.

## 순서가 있는 구현 단계

- [ ] Step 1 — 실행 경계와 시험 준비: 현재 브랜치·기존 변경을 확인하고 U5b 소유 경로만 선택한다. 기존 Gradle 태그/JaCoCo·Vitest 설정과 DB 지원을 확인한다. unit-test-instructions.md의 첫 시험 명령이 실행 가능한 의존 환경을 마련한다. 기존 시험/하한을 변경하지 않는다. 상위 메인·U4b Intent, README.md, contracts/README.md는 수정하지 않는다. (US4.1/US4.2/US6.5, NFR6/NFR9)
- [ ] Step 2 — 데이터 모델 구현: backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/에 SessionGoal·PracticeAttempt·PracticeResponse·CueDelivery·Evaluation 및 반응 소유 EvaluationWork를 구현한다. 동일 기회 유일성, PENDING의 null 라벨, FINALIZED의 라벨 일치, TERMINATED_PENDING 보존, attempts 0~2, 영속 예산/세대를 반영한다. (US4.2, BR2.2/BR3.1/BR5.1)
- [ ] Step 3 — 모델 시험 작성·실행: PracticeModelTest에서 유효/무효 참조, FIRST/AFTER_CUE/NEW_SCENARIO 분리, 무반응과 uncertain 구분, 필수·조건부 필드 및 중복을 검증한다. (AC4.2.3/5/7)
- [ ] Step 4 — 저장소·스키마 구현: V4__u5b_practice.sql에 연습 표 다섯 개와 필요한 제약/색인을 더한다. EvaluationWork는 response의 내부 열/문맥으로 영속화해 불필요한 여섯 번째 업무 표를 만들지 않는다. PracticeRepository/JdbcPracticeRepository는 최초 수락, 작업 선점, 최종 결과·반응 라벨의 원자 갱신, 세대 확인, 종료 직렬화와 삭제를 제공한다. DB 시각은 앱 Clock을 바인딩한다. 외부 호출 중 DB 잠금을 유지하지 않는다. (BR3.1/BR5.1/BR5.3/BR5.4, NFR4/NFR9/NFR12)
- [ ] Step 5 — 저장 경계 시험 작성·실행: PracticeRepositoryIT에서 PostgreSQL 유일성, 최종 갱신 중간 실패 롤백, 동시 중복, 종료/최종화 순서, 삭제 이후 늦은 결과, 재시작 뒤 예산 보존을 검증한다. 폐기 DB에서 V4 자체 제약을 확인하되 실제 V3→V4 적용은 해당 버전 확보 후 별도로 검증하고 미실행이면 보류 근거를 기록한다. (AC4.2.7, NFR4/NFR12)
- [ ] Step 6 — 판정 구현: ResponseEvaluator·EvaluationBudget·판정 공급자 포트를 구현한다. 유효 반응 수락부터 최종 커밋까지 12초, 호출당 최대 5초·재시도 최대 1회, 저장 예약 R과 C=D-R을 사용한다. 큐/왕복/검증/재시도 간격/DB 시간을 모두 포함하고 양수 예산이 없으면 호출/재시도를 생략한다. 시간 초과는 반응 보존 후 uncertain/reviewNeeded로 확정한다. 유효 토큰·소유권·최종화 선점으로 늦은 결과와 중복을 차단한다. (Q6, BR3.1, FR7.6/NFR6)
- [ ] Step 7 — 판정/시간 시험 작성·실행: ResponseEvaluatorTest·EvaluationDeadlineIT에 5초 경계, 호출 0/1/2회, C와 D의 경계, 큐·DB 지연, 최종 커밋 지연, 종료·중복·재시작·설정 변경을 넣는다. R은 시험 입력으로 사용하고 운영 미검증 기본값은 정하지 않는다. 실제 부하 검증 전에는 R 운영 적용과 12초 성능 달성을 미확인으로 표시한다. (AC4.2.7, Q6, NFR6)
- [ ] Step 8 — 연습 단계 구현: PracticeProtocol과 U5b 소유 사건/출력 포트를 구현한다. 고정 목표 진행, CG-Q1 첫 무응답→HELP_OFFER, 동의 뒤 Cue 1회, 도움 후/새 상황 반응, 정상 다음 목표와 마지막 마무리 신호를 처리한다. I4 실패 경로는 내부 보존에서 멈추고 임의 DONE/SKIPPED/다음 전이를 만들지 않는다. 종료 후 작업을 막고 U4b 생명주기 소유권을 지킨다. (US4.2/US6.5, BR2.1~BR2.6, CG-Q1)
- [ ] Step 9 — 단계/동의 시험 작성·실행: PracticeProtocolTest·HelpConsentPolicyTest에 아래 Clock 표의 첫 무응답 및 도움 동의 흐름을 구현한다. 실제 U4b 실행기 복제가 아닌 최소 포트 대역을 시험 소스에 둔다. 재생 성공/실패/중단/누락, 순서별 종료 경합과 중복 사건을 별개 사례로 검증한다. (AC4.2.3/4/5/6/10, AC6.5.3/4/6)
- [ ] Step 10 — 출력·기록 부품 구현: StageOutputPolicy·CueBrief·PracticeRecordProjector·PracticeDeletionService를 작성한다. 첫/새 상황 Cue 금지, 최소 정보, 실제 제공·노출만 기록, 완료 반응만 최종 라벨 투영, 판정 대기 종료의 합의 필요 결과를 명시적으로 반환한다. 불완전 기록을 정상 practice=null로 숨기지 않는다. 삭제 서비스는 자기 데이터만 멱등 제거하며 실제 참가자 등록은 U4b 순서 150 합의 연결 후 수행한다. (BR3.2/BR4.1~BR4.4/BR5.1~BR5.4)
- [ ] Step 11 — 출력·기록 시험 작성·실행: StageOutputPolicyTest·PracticeRecordProjectorTest·PracticeDeletionIT에 정보 유출, 실제 재생 증거, ORIGINAL 보존, 판정 대기 정산 보류, 중복 정산과 삭제 롤백을 넣는다. 기존 OutputGate/VoiceRelay 생명주기 경로는 합의 전 바꾸지 않는다. (AC4.2.7/8, AC6.5.1/2/3/5/7/8, NFR3/NFR12)
- [ ] Step 12 — Core 독립 부품 구현과 시험: backend/core-api/src/main/java/kr/mujoong/core/practice/start/의 FrozenGoal·WeightedGoalOrder·PreparedPracticePackageMapper를 작성한 뒤 FrozenGoalOrderTest·PracticePackageContractTest를 작성/실행한다. Q4 가중식·비복원 추첨·고정 원본 재사용, 기존 PracticeStart.Goal/C8 대응, 첫 상황 위치 변환을 검증한다. 실제 준비 저장/API/빈 연결, ACTIVE 조회의 relationType 원본 및 C10 활성 검증 접점은 I5로 보류하고 계약 대역으로 요구 능력만 명시한다. (US4.1, BR1.1~BR1.6, AC4.1.3/4/10/12/AC4.2.9)
- [ ] Step 13 — 프런트 독립 부품 구현과 시험: frontend/src/parent/practice/에 시작 요청 조정 부품·진행 파서/머리 표시를 작성하고 tests/u5b/practice-start.test.tsx·practice-progress.test.tsx를 실행한다. 같은 키 보존·전송 오류와 실제 단계 구분·네 필드 허용 목록·접근성을 검증한다. 기존 화면 연결은 API/확장점 합의가 확보된 부분만 수행하고 화면 18의 실제 시작 활성화는 I5 보류다. UI에 내부 판정/보류 코드나 새 조작을 노출하지 않는다. (BR6.1/BR6.2, AC4.1.3/10, AC4.2.2/8)
- [ ] Step 14 — 설정/커버리지/회귀: 독립 부품이 읽을 타입 있는 시간 설정을 구현하고 관련 테스트를 실행한다. 기존 예약 20초/10초/5초를 유지하고 12초 키는 Q6의 추가 기준으로 구분한다. R 미설정이면 실제 판정 연결을 활성화하지 않으며 기존 자유대화 앱 전체를 불필요하게 막지 않는다. backend/coverage-scope/u5b.txt·u5b-core.txt, frontend/coverage-scope/u5b.json에 실제 변경 운영 파일을 빠짐없이 넣고 각 80% 줄 하한을 검증한다. 범위 목록에서 어려운 파일을 빼거나 기존 단언을 낮추지 않는다. (NFR3/NFR4/NFR6/NFR9, brief)
- [ ] Step 15 — 문서/추적/인계: code-summary.md·traceability.json·docs/units/u5b/report.md를 실제 실행 증거로 작성한다. docs/units/u5b/design.md에 CG-Q1 추가 결정과 기존 10초 근거를 덧붙이되 Q1~Q6나 기존 감사 기록을 고치지 않는다. 실제 구현/시험 파일만 OK로 연결하고 미합의 실제 시작·출력·정산·노출 연결과 V3 업그레이드 미검증은 Deferred로 남긴다. 구현/대역 시험 완료와 전체 연습 통합 완료를 구분하여 재검토에 넘긴다. (모든 담당 AC/BR/NFR)

## Clock 및 경합 시험의 구체 조건

| 대상 | 입력 | 기대 결과 |
|---|---|---|
| 첫 반응 | 실제 트리거 완료 t0 후 20초 직전/정각/직후 | 직전에는 열린 기회, 만료 승자에서는 NOT_PERFORMED/NO_RESPONSE 1개·평가 0회·도움 동의 질문 1개 |
| 도움 질문 재생 | 요청 뒤 지연, 실패/중단/누락, 완료 중복 | 완료 증거 전 10초 시계 없음; 실패를 아이 무응답으로 만들지 않음; 동일 완료는 시계 재설정 없음 |
| 동의 무응답 | 완료 수신 h0부터 10초 직전/정각/직후 | 직전 무응답 확정 없음; 만료 승자에서 NO_RESPONSE·Cue 0회·새 상황 준비 1회 |
| 동의/거부 | 유효 ACCEPTED/DECLINED 및 중복 | 동의만 Cue 1회와 실제 완료 뒤 도움 후 기회 1회; 거부는 Cue 0회·새 상황 준비 |
| 같은 시각 사건 | 동의→만료, 만료→동의; 종료→재생/만료, 재생/만료→종료 | 순서별 수락 승자 한 명·중복 저장/출력 없음; 실제 우선순위는 I3 미확정 유지 |
| 새 상황 실패 | 도움 거부/무응답 뒤 NEW_SCENARIO 생성 실패 | 기존 FIRST·동의/도움·노출 보존, 가짜 scenarioId 없음; I4 후속 전이 보류 |
| 판정 | 각 호출의 min(si+5초,C) 직전/정각/직후 | 시한 미만 검증 완료만 유효 후보, 토큰 만료 뒤 늦은 결과 거절 |
| 전체 판정 | 최초 저장/큐/두 시도/최종 저장을 합친 D=t0+12초 | 최종 커밋 D 이하만 시간 기준 충족; C부터 uncertain 저장 선예약; D 이후 커밋은 위반 관측 |
| 판정 종료/재시작 | 종료가 먼저/최종 커밋이 먼저, 중복 수락/작업 재개 | TERMINATED_PENDING 보존 또는 일치하는 최종 라벨, 최초 예산·횟수 불변, 외부 미합의 상태 추가 없음 |

## 시험 범위와 완료 구분

12개 시험 묶음은 PracticeModel, PracticeRepository, ResponseEvaluator, EvaluationDeadline, PracticeProtocol, HelpConsentPolicy, StageOutputPolicy, PracticeRecordProjector, PracticeDeletion, FrozenGoalOrder, PracticePackageContract, 프런트 시작/진행 묶음이다. 각 5~8개 의미 있는 사례가 기본이며 복잡한 경합은 추가한다. 설정 검증은 관련 묶음에 포함한다. 수치 맞추기용 반복/항상 통과 시험은 쓰지 않는다.

모든 U5b 백엔드 시험은 @Tag("u5b")를 사용한다. 각 층 구현 뒤 해당 시험을 작성·실행한다. PostgreSQL 원자성은 실제 DB로, 시간은 MutableClock 및 제어 가능한 예약기/공급자/저장소 대역으로 검증한다. 운영 네트워크 호출이나 실제 아동 자료는 사용하지 않는다.

회귀는 아래 승인 범위의 별도 명령을 저장소 루트에서 실행하며 기존 하한 검증을 바꾸지 않는다. U4b 태그 회귀는 해당 코드 정상 통합 후만 실행한다. 현재 없는 시험이 0건으로 끝난 것을 통과로 세지 않는다.

```text
./backend/gradlew -p backend --no-daemon :contract-model:test :core-api:test :realtime-activity:test :test-support:test -PincludeTags=u1
./backend/gradlew -p backend --no-daemon :realtime-activity:test -PincludeTags=u3
./backend/gradlew -p backend --no-daemon :core-api:test -PincludeTags=u4a,u5a
./frontend/node_modules/.bin/vitest run --root frontend tests/u4a tests/u5a
```

위 회귀 경로/태그의 실제 존재와 시험 발견 수는 Step 1에서 확인하고 존재하는 해당 유닛의 정확한 경로를 사용한다. 실행 결과의 환경 차단/실패는 기록하고 임의 면제하지 않는다. 실제 CI/공급자·부하·V3 업그레이드·전체 화면 흐름은 현재 계획 승인만으로 완료되지 않는다.

## Testing Contract

```json
{
  "version": 1,
  "methodology": "test-after",
  "source": "org",
  "ordering": "implement each applicable testable layer, then write and run that layer's tests.",
  "scope": "refactor",
  "test_strategy": "standard",
  "project_type": "brownfield",
  "applicable_notes": [
    {
      "layer": "org",
      "text": "We treat tests as a first-class deliverable in every Bolt. The specific\nmethodology (TDD, BDD, ATDD, or classic test-after) is affirmed at\npractices-discovery and recorded in `team.md` under this heading with explicit\n`Methodology` and `Ordering` fields; Code Generation resolves those fields\nindependently from coverage, tooling, and scope notes.\n\nWhen no posture has been affirmed, our default per scope is:\n- **Methodology**: test-after\n- **Ordering**: implement each applicable testable layer, then write and run\n  that layer's tests.\n- `mvp`, `enterprise`, `feature`, `infra`, `classic` add an 80% line-coverage\n  floor and CI execution before merge.\n- `bugfix`, `security-patch` add a targeted regression for the specific\n  bug/vulnerability and require the existing suite to remain green.\n- `express` uses the Minimal strategy: requirement-driven unit tests (one per\n  requirement, with a happy-path floor per component); existing tests remain\n  green.\n- `poc`, `refactor`, `workshop` add no extra new-test floor and require the\n  existing suite to remain green.\n\nThe active `Test Strategy` still applies in every scope and determines test\nvolume/types. Scope floors are additive; they never reduce or replace the\nselected strategy.\n\nBuild and Test verifies defined coverage floors and affirmed quality targets;\nthey may not be weakened to make a step pass.\n\nAffirm a stricter posture in `team.md` if the team commits to one."
    }
  ],
  "obligations": {
    "strategy": "standard",
    "strategy_volume": [
      "Five to eight tests per component.",
      "Unit tests plus integration tests for key boundaries.",
      "Add E2E, performance, or security tests when requirements demand them."
    ],
    "scope_floor": [
      "Keep the existing test suite green.",
      "This scope adds no extra new-test floor beyond the selected test strategy."
    ],
    "combination_rule": "Apply every selected-strategy obligation and every scope-floor obligation; neither replaces the other, and a targeted scope regression may add the narrowest necessary test type beyond the strategy default."
  },
  "plan_profile": {
    "methodology": "test-after",
    "runner_step": "Verify the existing test runner/configuration and record the exact unit-scoped command.",
    "runner_ready_before_first_test": true,
    "testable_layers": [
      "Data model / database behavior",
      "Repository / data access",
      "Business logic",
      "API / endpoint",
      "Frontend behavior"
    ],
    "steps": [
      "Project structure and production configuration skeleton.",
      "Verify the existing test runner/configuration and record the exact unit-scoped command.",
      "Data model / database behavior - implement.",
      "Data model / database behavior - write and run its tests after implementation.",
      "Repository / data access - implement.",
      "Repository / data access - write and run its tests after implementation.",
      "Business logic - implement.",
      "Business logic - write and run its tests after implementation.",
      "API / endpoint - implement.",
      "API / endpoint - write and run its tests after implementation.",
      "Frontend behavior - implement.",
      "Frontend behavior - write and run its tests after implementation.",
      "Environment/build configuration.",
      "Documentation and traceability."
    ]
  },
  "input_sha256": "sha256:322f3cce51ad835b556974215be0ae0a5aba55c673c92ff6b1b327e7eb2426e8",
  "contract_sha256": "sha256:84a32ec0d9dd634774d2a290b523e6e0d5e6f77259469f3112500519cb132392"
}
```
