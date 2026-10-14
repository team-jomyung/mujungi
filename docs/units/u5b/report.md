# U5b 구현 · 통합 실행 기록

## 2026-10-14 통합 인수 진행

`bdf47ec`의 독립 구현을 보존한 채 `codex/u5b-integration`에서 실제 연결을 구현한다. 아래 기존 기록은 독립 구현 당시의 실행 증거다. 현재 설계와 사용자 확정 정책은 design.md 9절을 따른다. AI-DLC 엔진이 없는 환경에서 다른 작업자의 승인 기록을 이어 쓰지 않았다.

### 구현

- Core V7 준비 원장, POST /api/activities/practice, 고정 목표·순서·같은 키 재사용, C8 첫 상황 전달, ACTIVE C10 어댑터, ActivityRecorded 후 실제 노출 정산과 재시도.
- Realtime V4 자동 적용, 실제 PracticeSessionExtension, 단계별 타입 발화·실제 재생 상관관계·진행 조회, 원래 평가 시한을 지키는 종료 정산, 재시작 후 기록 복구, 삭제 참가자 150.
- 화면 18 시작 및 재시도, 화면 12 연습 머리·단계·끝내기. 오류·미확인 응답에 따라 멱등 키를 보존하거나 새로 만든다.
- 부모 API·시간·포트·편차 문서 및 ERD §8. Core V5(U6) → V6(U7) → V7(이 PR) 병합 순서는 사람이 지킨다. Realtime V3는 이미 기준 main에 포함된다.

### 확인한 실행 결과

| 검증 | 결과 |
|---|---|
| 프런트 전체 | 59파일 835건 통과, 줄 3102/3143 = 98.69% |
| 프런트 U5b | 3파일 32건 통과, 범위 줄 82/82 = 100% |
| 프런트 정적·빌드 | typecheck · ESLint · Next production build 통과 |
| Core U5b CI | 44건 통과(비DB 36 + DB 8), 줄 233/238 = 97.90%, run 38534201993 |
| Realtime U5b 비DB | 70건 통과, 정지 겹침·처리 실패·원래 판정 시한 정산 포함 |
| Realtime U5b CI | 147건 통과, 줄 843/872 = 96.67%, run 38534201993. 공급자 입구 후속 수정 뒤 최종 결과로 갱신 예정 |
| 기존 기능 포함 로컬 비DB 회귀 | u1/u3/u4b/u5b 태그. contract-model 293/293, Core 406/407, Realtime 857/858, test-support 21/21. 총 1577/1579 통과. 아래 기존 Windows 종료 시간 시험 2건 실패 |
| DB·리눅스·스켈레톤 | 로컬 Docker를 재시작/정리하지 않았다. 전체 비DB 회귀 중 기존 ScenarioServiceTest의 DB 태그 누락으로 Testcontainers 자동 호출이 발생해 그 실행을 중단했다. DB 검증은 GitHub Actions에서 진행 |

프런트 RED는 21건 중 13실패·8통과였고 실제 연결 후 기존 화면 포함 76건 통과, 최종 전체 835건을 확인했다. DB/삭제·정산·복구는 새 타입/API가 없는 상태의 컴파일 실패를 먼저 확인한 뒤 구현했다. Core 최초 실행은 준비 중 코드가 작성되어 RED 증거를 확보하지 못했으므로 실패 확인으로 계수하지 않는다.

### 계약과 가정

- 편차 (U5b-a)~(U5b-e), 가정 U5b-I1~I10은 contracts/deviations.md와 design.md 9절에 있다.
- 사용자 확정: 두 번째 상황 생성 실패는 이전 기록을 보존한 미완료 목표 뒤 다음 목표 진행. 판정 중 종료는 원래 제한 시간까지 기다려 결과 포함.
- 실제 공급자·장치 확인은 별도다. TextAiProvider/EvaluationProvider/CueProvider의 운영 기본은 미연결이며 가짜 성공을 만들지 않는다. 시험 대역은 테스트 소스에서만 사용한다.
- 판정 최종 저장 예산 2초는 운영 부하로 입증되지 않았다. 장애 뒤 시한을 넘겨 복구한 판정은 별도 오류로 관측하며 12초 충족으로 보고하지 않는다.
- 기존 U1/U3/U4/U5a 단언·하한을 낮추지 않는다. 실제 새 스키마의 표 수·최고 버전 기대값과 18의 시작 비활성 단언, 비동기 페이지 호출 방식만 기능 변경에 맞춰 갱신한다.

### 남은 일

최종 JVM 회귀·DB/스키마/범위 커버리지 CI 확인, PR 및 수동 스켈레톤 실행 결과 기록. 실제 장치 음성·Cue 내용 품질·실공급자·부하 성능은 이 환경에서 실행하지 않았고 후속 검증 대상이다.

#### 기존 Windows 종료 시간 시험

`CoreStartupSequenceTest`와 Realtime `DrainingLifecycleTest`의 `aStuckDatabaseCannotStretchTheShutdownThroughTheLastSnapshot`가 최종 로컬 회귀에서 각각 4.665초/4.558초로 3초 제한을 넘겼다. 시험·DrainingLifecycle·MetricSnapshotJob·SystemLoad는 기준 main과 같다. 동일 JDK의 새 JVM에서 OperatingSystemMXBean.getCpuLoad() 첫 호출을 별도 측정한 결과 약 2.479초, 후속 호출은 0.1ms 미만이었다. 시험은 DB 읽기 제한 2초 뒤 이 첫 측정까지 포함하므로 실패와 부합하지만, 실패 당시 구간별 계측이 없어 원인을 단정하지 않는다. 단언과 운영 구현을 바꾸지 않고 Linux CI에서 재확인한다.

첫 Linux CI(38534163447)에서는 위 종료 시간 시험 두 건이 모두 통과했다. 대신 새 비유일 부분 인덱스와 공급자 공개 메서드 입력 타입이 기존 계약 검사에 걸렸다. 새 V7 인덱스를 같은 조회를 지원하는 일반 복합 인덱스로 바꾸고, 발화는 기존 PracticeInstruction 입구로 전달하도록 수정했다. 기존 시험 단언과 허용 목록은 유지한다.

#### U6 인계

U6 PR #11의 소비 형식을 읽기 검토했다. `record.practice.goals[].behaviorKey/behaviorVersion`은 세션 시작 때 고정한 값으로 추가해 정의 조회 입력을 맞췄다. U6 코드와 브랜치는 수정하지 않았다. U6 담당자가 확인할 두 항목은 다음과 같다.

- `HelpSummarizer`: `cues.size()`에는 거절·무응답·재생 전 실패도 포함된다. 실제 도움 횟수는 `givenAt` 증거로 집계해야 한다.
- `NotDoneReasonMapper`: `goal.skipReason=TIME_OVER`를 소비해야 새 상황 생성 시간초과를 일반 `ACTIVITY_ENDED`와 구분할 수 있다.

---

## 독립 구현 당시 실행 기록

승인된 Step1~15의 U5b 독립 부품 구현 및 계약 대역 시험 결과다. 전체 U5b 통합 완료를 의미하지 않는다. Q1~Q6 결정과 I1~I5 미합의 상태를 유지한다.

## 실행 근거

Testing Contract: `sha256:84a32ec0d9dd634774d2a290b523e6e0d5e6f77259469f3112500519cb132392`, `test-after`.

| 시험 | 실제 결과 |
|---|---|
| U1 기준선: contract-model/core-api/realtime-activity/test-support | 명령 성공. contract-model 292건, core-api 622건 통과. test-support 27건 중 24건 통과·LOCAL 환경 3건 건너뜀. Realtime 건수는 후속 실행으로 XML이 교체되어 재확인 필요 |
| U3 Realtime 기준선 | 283건 통과, 실패·건너뜀 0건 |
| U4a·U5a Core 기준선 | 110건 통과, 실패·건너뜀 0건 |
| U4a·U5a frontend 기준선 | 4개 파일 116건 통과 |
| PracticeModelTest | 8건 통과 |
| PracticeRepositoryIT 첫 실행 | compileTestJava 실패: 시험 지원 Role.REALTIME 식별자가 없음. 실제 API Role.REALTIME_APP으로 수정한 뒤 아래 재실행에서 해소 |
| PracticeRepositoryIT 수정 후 | 8건 통과, 실패·오류·건너뜀 0건. XML 시각 2026-10-14T03:28:08.549Z |
| ResponseEvaluatorTest·EvaluationDeadlineIT | 각각 8건·6건 통과, 실패·오류·건너뜀 0건. 최신 파일 확인 재실행은 UP-TO-DATE 성공 |
| PracticeProtocolTest·HelpConsentPolicyTest | 각각 8건 통과, 합계 16건. 실패·오류·건너뜀 0건 |
| StageOutputPolicyTest·PracticeRecordProjectorTest·PracticeDeletionIT | 각각 5건·8건·5건, 합계 18건 통과. 실패·오류·건너뜀 0건 |
| FrozenGoalOrderTest·PracticePackageContractTest | 각각 6건 통과, 합계 12건. 실제 명령 성공 |
| frontend U5b 시작·진행 | 2개 파일 16건 통과. 2026-10-14 12:49:54 KST 실행 |

2026-10-14 최종 전체 U5b 묶음은 Realtime 74건·Core 12건·프런트 16건, 합계 102건 모두 통과했으며 실패·오류·건너뜀은 0건이다. Realtime 설정 시험 5건과 UTC 역행 경계 추가 2건, 목표 간 작업 소유권 경계 3건이 포함된다. 기준선 성공은 최종 변경 후 회귀를 대신하지 않는다.

| 변경 범위 줄 커버리지 | 실제 측정 | 80% 검증 |
|---|---|---|
| Realtime U5b 전체 신규 운영 Java | 546/553, 98.734% | 통과 |
| Core U5b 전체 신규 운영 Java | 77/78, 98.718% | 통과 |
| frontend U5b 전체 신규 운영 TS/TSX | 59/59, 100% | 통과 |

프런트 `npm --prefix frontend run typecheck`도 통과했다. JaCoCo의 해당 패키지 LINE 카운터와 Vitest `coverage-summary.json`에서 수치를 확인했다. 기존 앱 전체 커버리지와 혼동하지 않는다. 최종 목표 간 작업 소유권 보완 후 Realtime 전체를 재실행하여 74건과 위 수치를 확인했다. 이전 71건/540÷547 수치는 최종 커버리지로 사용하지 않는다.

최종 프런트 U4a·U5a 회귀는 4개 파일 116건 모두 통과했다(2026-10-14 12:55:44 KST). 최종 U1 첫 회귀에서는 `ConfigKeyContractTest` 두 건이 신규 practice 키의 현재 앱 소유권/기존 `RealtimeProperties` 불일치를 발견했다. 기존 단언을 변경하지 않고 독립 설정을 자동 로드되지 않는 파일로 분리하는 수정 후 재검증한다. 해당 설정의 실제 앱 등록 및 timing.md 소유권 조정은 후속 통합 승인 범위다.

실패한 첫 U1 회귀의 전체 발견 수는 1,483건이다: contract-model 292건 통과, core-api 622건 통과, realtime 542건 중 설정 계약 2건 실패, test-support 27건 중 LOCAL 3건 건너뜀·24건 통과. 이 결과를 성공 회귀로 계수하지 않는다.

설정 분리 뒤 같은 U1 전체 명령을 다시 실행해 총 1,483건 중 실패·오류 0건, LOCAL 건너뜀 3건을 확인했다. 그 뒤 최종 점검의 Realtime 변경은 별도 재검증한다. 최종 보완은 동일 고정 목표 재전달 때 진행 상태 보존, UTC 전진 뒤 역행에도 최대 관측 시각 유지, 새 상황 콜백 저장/출력 예외의 명시 오류 전달이다. 고정 목표 이동·다음 목표 준비 실패·미노출 잔여 목표·실제 도움 후 반응·동의 경합 양 순서도 기존 사례에 추가했다.

최종 소스 고정 후 Realtime U1을 다시 실행해 542건 모두 통과했고 건너뜀·실패·오류는 0건이다. 다른 U1 모듈은 이 마지막 보완에서 변경하지 않았으므로 직전 전체 U1 성공 증거를 유지한다.

최종 Realtime U3 회귀도 283건 모두 통과했으며 건너뜀·실패·오류는 0건이다.

목표 간 작업 소유권의 최종 수정 후에는 Realtime U1·U3를 함께 재실행하여 총 825건 모두 통과했다. 실패·오류·건너뜀은 0건이며 기존 542건·283건의 회귀 범위를 유지했다.

최종 Core U4a·U5a 회귀는 110건 모두 통과했고 건너뜀·실패·오류는 0건이다. 최종 프런트 회귀 116건과 함께 기존 U1·U3·U4a·U5a 단언을 유지했다. 소스 최종 고정 이후의 회귀에서 새 실패는 없다.

수정 뒤 독립 설정은 `backend/realtime-activity/src/main/resources/practice/u5b-independent.yml`이며 `PracticeConfigurationTest`가 명시적으로 읽는다. `application.yml`은 원래 상태로 되돌렸고 기존 설정 단언과 timing.md는 변경하지 않았다. 운영 판정 연결에는 검증된 양수 R, 설정 소유권 합의, 동일 `EvaluationClock`을 사용하는 판정 전용 TimerDriver가 필요하다. EvaluationClock은 UTC와 단조 경과 중 먼저 소진되는 예산을 사용하며 UTC 역행으로 늦은 공급자 응답을 유효하게 되돌리지 않는다. 기존 U4b 단계 시계나 생명주기는 수정하지 않았다.

판정의 R은 시험 입력이며 운영 기본값을 정하지 않았다. 최종 저장에 남은 D를 PostgreSQL 문장/잠금 제한과 트랜잭션 제한으로 전달하고 커밋 전 Clock 초과는 롤백한다. 커밋 응답이 D 이후에 확인되면 예산 위반으로 관측한다. 합성 저장 지연 시험은 실제 운영 부하·예약기 상한이나 12초 성능 달성의 증거가 아니다.

## 목표 간 비동기 작업 소유권

목표마다 다시 시작하는 generation만으로 예약 작업을 검사하면 이전 목표의 타이머가 다음 목표 기회를 조기 종료할 수 있다. `PracticeProtocol`의 모든 타이머와 상황 생성 완료는 예약 당시의 `sessionGoalId`, `attemptId`, 단계, generation 및 시도 상태를 불변 소유권으로 캡처하고 실행 시 현재 작업과 모두 일치해야만 반영한다. 취소 성공 여부에 의존하지 않고 이미 전달된 콜백도 실행 직전에 검사한다. 외부 계약이나 미합의 정책은 추가하지 않았다.

`PracticeProtocolTest`에 이전 목표의 t=20 만료가 t=2에 열린 다음 목표의 기회를 닫지 않고 t=22에만 무응답을 확정하는 시험, 실패한 목표2의 준비 타이머가 목표3 준비를 조기 종료하지 않는 시험, 목표2 준비 만료 뒤 늦게 도착한 결과가 목표3에 적용되지 않는 시험을 추가했다. 신규 3건을 포함한 PracticeProtocolTest 11건과 전체 Realtime U5b 74건이 통과했다.

## V4 산출물의 적용 경계

V4는 `backend/realtime-activity/src/main/resources/db/pending/realtime/V4__u5b_practice.sql`에 보관한다. 현재 자동 마이그레이션 위치에는 V1/V2만 존재하며, V3 미병합 상태에서 V4를 자동 적용하면 승인된 통합 경계를 넘는다. 독립 저장소 시험은 폐기 DB에 해당 파일을 명시 적용한다. U1의 기존 표 수·스키마 버전 검증은 수정하지 않는다.

같은 Intent의 후속 통합 계획 승인 후 실제 V3 기준을 확보하고 자동 마이그레이션 위치 이동과 V3→V4 적용·데이터 보존을 검증해야 한다. 현재 V3→V4 검증은 미실행이다.

## 재현 명령과 증거 위치

각 구현 층의 시험은 구현 뒤 명시 클래스 필터로 실행했다. 모델 8건 → 저장소 8건 → 판정 14건(최종 16건) → 단계/동의 16건 → 출력/기록/삭제 18건 → Core 12건 → 프런트 16건 순서다. 설정 5건은 Step14 전체 Realtime 묶음에 포함했다.

```text
./backend/gradlew -p backend --no-daemon :realtime-activity:test :realtime-activity:jacocoTestCoverageVerification -PincludeTags=u5b -PcoverageScope=u5b
./backend/gradlew -p backend --no-daemon :core-api:test :core-api:jacocoTestCoverageVerification -PincludeTags=u5b -PcoverageScope=u5b-core
MUJOONG_COVERAGE_SCOPE=u5b ./frontend/node_modules/.bin/vitest run --root frontend tests/u5b/practice-start.test.tsx tests/u5b/practice-progress.test.tsx --coverage
npm --prefix frontend run typecheck
./backend/gradlew -p backend --no-daemon :contract-model:test :core-api:test :realtime-activity:test :test-support:test -PincludeTags=u1
./backend/gradlew -p backend --no-daemon :realtime-activity:test -PincludeTags=u3
./backend/gradlew -p backend --no-daemon :realtime-activity:test -PincludeTags=u1,u3
./backend/gradlew -p backend --no-daemon :core-api:test -PincludeTags=u4a,u5a
./frontend/node_modules/.bin/vitest run --root frontend tests/u4a tests/u5a
```

Gradle XML은 각 모듈 `build/test-results/test/TEST-*.xml`, JaCoCo는 `build/reports/jacoco/test/jacocoTestReport.xml`이다. XML은 다음 필터 실행으로 교체되므로 위 집계는 각 명령 완료 직후 보존했다. 프런트 집계는 `frontend/coverage/coverage-summary.json`이다. 호스트 Gradle 캐시 쓰기 권한 때문에 승인된 승격 실행을 사용했다. 저장소 최초 실행의 장시간 도구 대기는 세션 ID를 반환하지 않았고 root에서 중복 Gradle 프로세스가 없음을 확인한 뒤 명령을 실행했다.

## BT-F01 문구 분리와 프런트 재검증

2026-10-14 승인된 재시도 Step 16~19에 따라 PracticeHeader.tsx의 제목 조합을 u5bCopy.ts의 heading 함수로 옮겼다. 기존 제목·공백·구분자·접근성 의미를 유지하고 practice-progress.test.tsx의 기존 사례에 정확한 제목 textContent 단언을 추가했다. 기존 16건과 설정·단언·커버리지 범위·하한은 유지했다.

| 실행 명령 | 실제 결과 |
|---|---|
| `./frontend/node_modules/.bin/vitest run --root frontend tests/u5b/practice-start.test.tsx tests/u5b/practice-progress.test.tsx` | 2파일 16건 통과 |
| `MUJOONG_COVERAGE_SCOPE=u5b ./frontend/node_modules/.bin/vitest run --root frontend tests/u5b/practice-start.test.tsx tests/u5b/practice-progress.test.tsx --coverage` | 16건 통과; 운영 5파일 줄 60/60=100%, 문장 84/84, 함수 18/18, 분기 73/74 |
| `./frontend/node_modules/.bin/vitest run --root frontend tests/u4a tests/u5a` | 4파일 116건 통과 |
| `npm --prefix frontend run lint` | 종료 0, 오류·경고 없음; 원래 오류 3건 해소 |
| `npm --prefix frontend run typecheck` | 종료 0 |
| `npm --prefix frontend run build` | 종료 0, production build 및 16페이지 생성 성공 |

앞의 59/59줄은 문구 분리 이전 측정이며 이번 결과는 60/60줄이다. 백엔드·빌드 입력을 이번 수정에서 변경하지 않았고 백엔드 시험도 다시 실행하지 않았다. 사전·사후 해시 비교를 수행한 것은 아니며 위의 이전 백엔드 결과를 신규 실행 증거로 제시하지 않는다. 기존 Build and Test 실패와 Loop-Back Log를 보존하며 해당 단계의 재검증을 면제하지 않는다.

실제 통합·운영 시간 예산·보안/접근성·마이그레이션·Cue 품질·성능/동시성 목표는 미검증 상태다. 특히 Cue의 5단어 일치 검사와 평가자 승인은 기존 대역 시험으로 검증되지 않았다. 이 수정으로 전체 U5b 통합 완료 또는 해당 목표 달성을 선언하지 않는다.

## 후속 통합 승인 경계

실제 U4b V3→V4 업그레이드, PracticeSessionExtension 연결, Core 준비 저장/API와 PracticeStart 빈, 실제 어댑터·노출 반영, 화면 18 시작 활성화는 미실행이다. 승인 계획 INT-1~INT-5에 따라 같은 Intent의 후속 Code Generation 계획과 별도 Plan Approval을 거쳐야 한다. I1~I5의 합의와 기준 커밋 `ffcfb8a09a523b2cb8fff885b1a9fadaefa27251` 이후 변경·최신 CI를 다시 확인한다. 현재 독립 시험을 해당 실제 통합의 통과로 대신하지 않는다.
