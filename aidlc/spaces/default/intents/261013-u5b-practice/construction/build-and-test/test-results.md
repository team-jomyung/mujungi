# U5b 빌드·시험 실행 결과

## 재검증 결과 — 2026-10-14T05:22:30Z

BT-F01 문구 분리와 사용자 승인 뒤 기존 문서를 보완하여 재검증했다. 기존 실패 기록과 Loop-Back Log 1은 보존한다. 이번 단계에서는 애플리케이션 소스·시험·설정·계약을 변경하지 않았다.

**실행 가능한 모든 빌드·시험 명령은 성공했다.** 독립 U5b 102건, U1 1,483건 중 1,480건 통과·기존 LOCAL 3건 건너뜀, U3 283건, Core U4a/U5a 110건, 프런트 U4a/U5a 116건을 확인했다. Realtime 줄 546/553=98.734%, Core 77/78=98.718%, 프런트 60/60=100%로 각각 80% 기준을 충족한다. lint 오류·경고는 0이며 typecheck와 production build도 성공했다.

최신 Target Verification Matrix는 **Met 11개 / Not Met 0개 / Unverified 12개**다. T-LINT는 이번 재검증으로 Met으로 바뀌었다. 나머지 실제 통합·운영 시간·성능·보안/접근성·마이그레이션·Cue 생성물 품질 목표는 그대로 Unverified다. 따라서 전체 단계 결과는 **FAIL**이며 독립 구현 검증 성공과 전체 U5b 완료를 구분한다.

## 현재 실패 처리 선택

남은 원인은 I1~I5 미합의와 실제 연결·공급자/부하·운영 R·브라우저/기기·V3→V4 및 평가자 확인 증거 부재다. 라이브러리/버전·이미지·인스턴스·알고리즘·플래그 교체만으로 이 미합의 입력과 실제 증거를 확보하는 구체적인 수정 후보는 없다. 이 판단은 작업량이 크다는 이유의 제외가 아니다. 로컬 5어절 비교 시험을 추가하더라도 실제 생성물과 평가자 승인 및 나머지 통합 목표를 대신하지 않는다.

기존 INT-1~INT-5의 착수 전제와 같은 Intent의 새 Code Generation 계획·별도 Plan Approval을 유지한다. 합의 자료와 U4b V3 main 병합 증거가 확보되면 최신 브랜치/CI 및 기준 커밋 이후 차이를 확인하고 실제 변경·시험·비용·위험을 산정해야 한다. 합의 전 외부 계약이나 운영 R을 임의 확정하지 않는다. 현재 재시도 누계는 1/3이며 새 loop-back은 실행하지 않았다.

사용자는 Accept failure를 선택했다. 전체 결과 FAIL과 미검증 12개를 보존한 채 이 단계의 승인 검토로 진행한다. 이 선택은 실제 배포나 전체 U5b 완료를 승인하지 않는다. INT-1~INT-5의 별도 계획·승인 및 실제 검증 의무를 유지한다.

## 재검증 명령별 증거

최초 실행 표의 BT ID와 같은 정확한 명령을 다시 실행했다. 코드 생성 시험 지침·빌드/통합 지침에 중복된 명령은 이번 실행에서 한 번만 수행했다. 예외로 최초 백엔드 빌드는 sandbox의 Gradle 캐시 잠금 쓰기 거절로 실행 전 실패했으며, 필요한 권한으로 재실행하여 성공했다. 이 환경 거절을 시험 실패나 성공 실행으로 숨기지 않는다.

| ID | 이번 실행 결과 |
|---|---|
| BT-B1 | 종료0; 두 bootJar UP-TO-DATE, jar 존재 확인 |
| BT-W1 | 종료0; Next production build, 16페이지 및 BUILD_ID 확인 |
| BT-W2 / BT-W3 | 각각 종료0; lint 오류·경고0, typecheck 성공 |
| BT-B2 | PracticeModelTest 8/8, 실패·오류·건너뜀0 |
| BT-B3 | Realtime U5b 74/74, 실패·오류·건너뜀0; 546/553줄 |
| BT-B4 | Core U5b 12/12, 실패·오류·건너뜀0; 77/78줄 |
| BT-B5 | FrozenGoalOrderTest 6/6, 실패·오류·건너뜀0 |
| BT-F1 / BT-F2 | 각각 U5b 16/16; coverage 실행은 줄60/60, 문장84/84, 함수18/18, 분기73/74 |
| BT-R1 | U1 1483 발견/1480 통과/실패0/오류0/기존 LOCAL3 건너뜀 |
| BT-R2 | U3 283/283, 실패·오류·건너뜀0 |
| BT-R3 | Core U4a/U5a 110/110, 실패·오류·건너뜀0 |
| BT-R4 | 프런트 U4a/U5a 116/116, 실패·건너뜀0 |

U1 모듈별 발견 수는 contract-model292, Core622, Realtime542, test-support27이며 마지막 모듈만 LOCAL3건을 건너뛰었다. contract-model/test-support는 이번에도 Gradle UP-TO-DATE와 기존 XML 재사용이며 강제 재실행으로 표시하지 않는다. 나머지 태그 시험은 실행 후 XML을 즉시 집계했다. U5b 고유 시험 102건에 개별 필터와 coverage 반복을 더하지 않는다.

최종 XML/JaCoCo 파일은 나중 태그의 보고서로 바뀌므로 위 U5b 수치는 해당 명령 직후 집계한 값이다. 운영 범위 목록 22+3+5개를 축소하지 않았다. 프런트 coverage-summary.json은 이번 60/60줄 보고서다.


## 범위와 실행 환경

2026-10-14 Build and Test에서 실행한 기록이다. Java 21.0.12.1, Node v22.23.3, npm10.9.9, Docker29.7.2를 확인했다. 설치된 잠금 의존성과 Gradle 캐시를 사용했다. 운영/공유 DB·실제 공급자 호출은 사용하지 않았다. PostgreSQL 시험은 기존 폐기 DB 지원을 사용했다. 각 명령은 아래 지침 경로의 정확한 명령이며 반복되는 공통 명령은 중복 실행하지 않았다. 개별 모델 명령과 전체 명령은 서로 다른 명령이므로 각각 실행하되 고유 시험 수는 중복 합산하지 않는다.

## 최초 실행 명령별 결과 (보존)

| ID | 명령/필터 | 발견/통과/실패/오류/건너뜀 또는 결과 |
|---|---|---|
| BT-B1 | Gradle core-api:bootJar realtime-activity:bootJar | 종료0, 두 jar 존재 |
| BT-W1 | npm --prefix frontend run build | 종료0, Next production build·16 정적 페이지 생성·BUILD_ID 존재 |
| BT-W2 | npm --prefix frontend run lint | 종료1, 오류3·경고0 |
| BT-W3 | npm --prefix frontend run typecheck | 종료0 |
| BT-B2 | PracticeModelTest -PincludeTags=u5b | 8/8/0/0/0 |
| BT-B3 | realtime-activity:test + jacocoTestCoverageVerification -PincludeTags=u5b -PcoverageScope=u5b | 74/74/0/0/0 |
| BT-B4 | core-api:test + jacocoTestCoverageVerification -PincludeTags=u5b -PcoverageScope=u5b-core | 12/12/0/0/0 |
| BT-B5 | FrozenGoalOrderTest -PincludeTags=u5b | 6/6/0/0/0; 전체12건에 포함 |
| BT-F1 | Vitest 명시 U5b 두 파일 | 16/16/0/0/0 |
| BT-F2 | 같은 두 파일 + MUJOONG_COVERAGE_SCOPE=u5b --coverage | 16/16/0/0/0; 고유16건 한 번만 계수 |
| BT-R1 | Gradle contract-model/core-api/realtime-activity/test-support -PincludeTags=u1 | 1483/1480/0/0/3 |
| BT-R2 | realtime-activity -PincludeTags=u3 | 283/283/0/0/0 |
| BT-R3 | core-api -PincludeTags=u4a,u5a | 110/110/0/0/0 |
| BT-R4 | Vitest tests/u4a tests/u5a | 116/116/0/0/0 |

U1 세부: contract-model292, Core622, Realtime542가 모두 통과했다. test-support27건 중24건 통과, 기존 LOCAL PostgreSQL 시험3건은 로컬 설치 부재로 건너뛰었으며 Docker 방식 시험과 구분한다. contract-model/test-support Gradle 태스크는 입력 불변에 따라 UP-TO-DATE였고 기존 XML을 재사용했다. 새로 강제 실행했다고 쓰지 않는다. 다른 시험 명령도 build cache/UP-TO-DATE 출력과 실제 실행을 구분해 명령 성공으로 기록한다.

새 고유 U5b 시험은 74+12+16=102건이다. 앞서 Code Generation에서 실행했던 결과와 별개로 위 명령들을 이번 단계에서 실행했다.

## 최초 실행 커버리지와 증거 수명 (보존)

| 범위 | 분자/분모 | 줄 커버리지 | 운영 파일 범위 |
|---|---|---|---|
| Realtime U5b | 546/553 | 98.734% | 22개·누락0 |
| Core U5b | 77/78 | 98.718% | 3개·누락0 |
| frontend U5b | 59/59 | 100% | 5개·경로 존재 |

각 범위 80% 하한을 통과했다. v8 frontend 분기73/74(98.64%), 함수17/17(100%)도 출력됐다. 하한·범위 목록·기존 단언을 변경하지 않았다.

Gradle XML은 backend/<module>/build/test-results/test/TEST-*.xml, JaCoCo는 build/reports/jacoco/test/jacocoTestReport.xml이다. 이 파일은 다음 태그 실행으로 교체되므로 U5b 수치는 BT-B3/B4 완료 직후 읽어 위 표에 보존했다. 마지막 XML/JaCoCo를 U5b 전용 보고서로 오인하지 않는다. frontend는 frontend/coverage/coverage-summary.json과 콘솔 수치를 확인했다. 실제 명령 전문은 build-instructions.md·integration-test-instructions.md 및 코드 생성 unit-test-instructions.md에 있다.

## 최초 실패 세부 (보존)

BT-F01: frontend/src/parent/practice/PracticeHeader.tsx:7.
- 7:24 Strings not allowed in JSX files: 연습 ·
- 7:49 Strings not allowed in JSX files: /
- 7:70 Strings not allowed in JSX files: 번째 목표 ·
- 규칙: react/jsx-no-literals. ESLint 종료1. 테스트 assertion/스택 추적 오류는 없으며 실패는 이 정적 검사에 한정된다.

## Target Verification Matrix

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| T-BUILD | build-instructions.md | Core/Realtime bootJar와 Next production build 성공·산출물 존재 | Core/Realtime jar와 Next production build 성공·산출물3개 확인 | BT-B1/BT-W1; build-instructions.md | Build and Test | Met |
| T-COV-RT | code-generation/unit-test-instructions.md 합격 기준; brief.md 시험 | Realtime 신규 운영 파일 전체 줄 ≥80% | 546/553줄=98.734%; 운영22파일 누락0 | BT-B3 JaCoCo 직후 집계; backend/coverage-scope/u5b.txt | Build and Test | Met |
| T-COV-CORE | code-generation/unit-test-instructions.md 합격 기준 | Core 신규 운영 파일 전체 줄 ≥80% | 77/78줄=98.718%; 운영3파일 누락0 | BT-B4 JaCoCo 직후 집계; backend/coverage-scope/u5b-core.txt | Build and Test | Met |
| T-COV-WEB | code-generation/unit-test-instructions.md 합격 기준 | frontend 신규 운영 파일 전체 줄 ≥80% | 재검증 60/60줄=100%; 운영5파일 존재 | 재검증 BT-F2; frontend/coverage/coverage-summary.json | Build and Test | Met |
| T-STRATEGY | code-generation-plan.md Testing Contract | test-after; 부품 정상·오류/경계 사례; 시험 발견 0 금지 | U5b 74+12+16=102건; 정상·오류/경계 및 신규 목표간3건 확인 | BT-B3/4/F2; 코드 생성 Testing Contract와 시험 클래스 대조 | Build and Test | Met |
| T-REGRESSION | code-generation-plan.md 시험 범위 | U1·U3·U4a·U5a 기존 단언과 하한 유지·실패 0; skip 구분 | U1 1483중1480통과·기존LOCAL3skip; U3 283·Core110·frontend116 통과; 실패/오류0 | BT-R1~4; 실행 직후 XML/콘솔 집계 | Build and Test | Met |
| T-TIME-LOCAL | requirements.md NFR6; rules.md BR2/BR3/BR4; Q6 | Clock 첫20초·동의10초·Cue5초·준비15초; 판정전체12초/호출≤5초/재시도≤1회/저장R 포함 | Clock 정책·경계/중복/종료 시험 통과; 운영 지연 보장은 T-Q6-REAL과 구분 | BT-B3; EvaluationDeadlineIT/ResponseEvaluatorTest/PracticeProtocolTest/HelpConsentPolicyTest | Build and Test 독립 시험 | Met |
| T-WEIGHT | functional-spec.md Q4; code-generation-plan.md Step12 | 고정 시드 1,000회 첫 자리 비율 기대±5%p; 순서 불변 | 1,000회 고정시드±5%p 단언 및 재사용/불변성 시험 통과 | BT-B4/5; FrozenGoalOrderTest | Build and Test | Met |
| T-PRIVACY-LOCAL | requirements.md NFR1/2/3/9; rules.md BR3/BR4 | 허용 DTO 외 정보 유출0; 동의 없는 Cue0; 보호자 허용4필드 | Cue 브리프3필드·보호자4필드·동의없는 Cue차단 등 로컬 시험 통과 | BT-B3/F2; StageOutputPolicyTest/PracticeRecordProjectorTest/practice-progress.test.tsx | Build and Test 독립 시험 | Met |
| T-ATOMIC-LOCAL | requirements.md NFR4/12; code-generation-plan.md Step5/11 | 반응·최종라벨 원자 일치; 중복/늦은작업/종료/삭제경합 보존; 삭제후0건 | 실제 폐기 PostgreSQL의 원자성·중복·종료·삭제 경합 시험 통과 | BT-B3; PracticeRepositoryIT/PracticeDeletionIT | Build and Test 독립 DB 시험 | Met |
| T-TIME-INTEGRATED | requirements.md NFR6; code-generation-plan.md I1/I3/I5 | 실재생·정지형시계·SILENCE 합의; REQUESTED10초·C10ACTIVE 보존 | 실제 환경·합의·연결 검증 미실행 | 현재 증거 없음; performance-test-instructions.md | 미예약: 후속 INT-1/2/5 승인 필요 | Unverified |
| T-SECURITY-REAL | requirements.md NFR1/2/3/9; code-generation-plan.md INT-1~4 | 실제 인증·소유권/로그·생성출력/노출 경계 확인 | 실제 환경·합의·연결 검증 미실행 | 현재 증거 없음; security-test-instructions.md | 미예약: 후속 INT-1~4 승인 필요 | Unverified |
| T-ACCESSIBILITY-REAL | requirements.md NFR10 | 기준 브라우저·보조기기/키보드·44px 조작·실제18→12 흐름 | 실제 환경·합의·연결 검증 미실행 | 현재 증거 없음; security-test-instructions.md | 미예약: 후속 INT-4 승인 필요 | Unverified |
| T-MIGRATION-REAL | code-generation-plan.md INT-5; requirements.md 데이터제약 | 실제 V3→V4·기존데이터 보존; 실제 삭제참가순서 | 실제 환경·합의·연결 검증 미실행 | 현재 증거 없음; integration-test-instructions.md | 미예약: 후속 INT-1/5 승인 필요 | Unverified |
| T-LINT | org.md Code Style; frontend/eslint.config.mjs | ESLint 오류0 및 typecheck 성공 | 재검증 ESLint 오류·경고0, 종료0; typecheck 종료0 | 재검증 BT-W2/BT-W3; 원래 BT-F01 실패 기록 보존 | Build and Test | Met |
| T-CUE-QUALITY | requirements.md FR5.5 AC6.5.3 | 합성 세트 기대 발화 예와 연속5어절 일치0건 및 평가자 확인 | 5어절 직접 비교·평가자 확인 미실행 | 현재 증거 없음; security-test-instructions.md | 미예약: 후속 AI 계약/시험 승인 필요 | Unverified |
| T-NFR5-FIRST | 메인 requirements.md NFR5 TS-N01 (측정 후 확정) | 아이 말 끝→첫 음성 p95≤2.5초 | 실제 환경·합의·연결 검증 미실행 | 현재 증거 없음; performance-test-instructions.md | 미예약: 후속 INT-5 승인 필요 | Unverified |
| T-NFR5-RELAY | 메인 requirements.md NFR5 TS-N01 (측정 후 확정) | 중계 지연 증가≤0.5초 | 실제 환경·합의·연결 검증 미실행 | 현재 증거 없음; performance-test-instructions.md | 미예약: 후속 INT-5 승인 필요 | Unverified |
| T-NFR5-BUBBLE | 메인 requirements.md NFR5 TS-N02 (측정 후 확정) | 말 끝→말풍선 조각≤1.5초 | 실제 환경·합의·연결 검증 미실행 | 현재 증거 없음; performance-test-instructions.md | 미예약: 후속 INT-5 승인 필요 | Unverified |
| T-NFR5-INTERRUPT | 메인 requirements.md NFR5 TS-N04 (측정 후 확정) | 끼어들기 재생 중단≤0.5초 | 실제 환경·합의·연결 검증 미실행 | 현재 증거 없음; performance-test-instructions.md | 미예약: 후속 INT-5 승인 필요 | Unverified |
| T-NFR7-CONCURRENCY | 메인 requirements.md NFR7 TS-N07 (측정 후 확정) | EC2 1대 동시세션2개·지연 증가≤20% | 실제 환경·합의·연결 검증 미실행 | 현재 증거 없음; performance-test-instructions.md | 미예약: 후속 INT-5 승인 필요 | Unverified |
| T-Q6-REAL | functional-design Q6; code-generation-plan.md INT-5 | 실제 공급자/부하에서 수락→최종 원자커밋≤12초 | 실제 환경·합의·연결 검증 미실행 | 현재 증거 없음; performance-test-instructions.md | 미예약: 후속 INT-5 승인 필요 | Unverified |
| T-R-REAL | functional-design Q6; code-generation-plan.md Step7/INT-5 | 실제 DB/예약기 검증으로 저장예산R 확정 | 실제 환경·합의·연결 검증 미실행 | 현재 증거 없음; performance-test-instructions.md | 미예약: 후속 INT-5 승인 필요 | Unverified |

## 최종 판정과 미검증

**전체 결과: FAIL.** BT-F01 문구 분리 후 lint 오류는 해소됐다. 독립 시험·커버리지·빌드와 실제 통합/성능 및 생성물 품질 미검증을 구분하며, 남은 목표 12개는 Unverified다. 후속 validation 단계가 현재 계획에 예약돼 있지 않으므로 성공적인 검증 이관으로 처리하지 않는다. 단계 완료 승인·배포 준비·Intent 전체 완료를 선언하지 않는다.

실제 사용자/공급자·부하·운영 R·시작/종료/노출/삭제 참가·V3→V4·기준 브라우저/44px 및 Cue5어절 비교/평가자 승인을 실행한 결과는 없다. 후속 검증 단계는 현재 실행 계획에 없으므로 Unverified를 성공으로 승계하지 않는다. cross-unit-traceability.md의 실제 제품 FR/NFR/AC 완료도 FAIL이다. 독립 부품 통과와 제품 전체 미완료를 동시에 보존한다.

## 최초 원인과 영향 추정 (보존)

문제 BT-F01은 PracticeHeader.tsx 7행의 JSX 문구 세 곳이다. react/jsx-no-literals 규칙에 따라 화면 문구/포맷 함수를 기존 u5bCopy.ts로 옮기고 PracticeHeader.tsx가 이를 사용하도록 수정할 수 있다. 규칙 비활성화·예외 추가·표시 문구 변경은 필요하지 않다. 원인은 생성 소스이므로 이번 시험 단계에서 몰래 수정하지 않고 Code Generation 재진입의 후보로 제시한다.

예상 영향: 작업·표적 재검증 및 재검토 20~40분, 추가 유료 자원/외부 호출 비용 0, 표시 결과를 보존하는 두 파일 변경으로 위험 낮음. 문구 결과·접근성 기존 단언을 유지하며 frontend U5b/회귀, lint/typecheck/build를 재검증한다. 후속 계획은 이 수정 범위로 구체화해 새 Plan Approval을 받는다.

이 수정은 I1~I5·실제 성능·운영 R·V3→V4·화면 통합 미검증을 해소하지 않는다. 해당 부분은 기존 INT-1~5 계획의 별도 승인·합의가 필요하다. 합의 없는 외부 계약/API를 만들지 않는다. 현재 확정된 구현 후보는 문구 분리 수정이며 실제 통합 수정 공수는 합의 입력 전 정밀 산정하지 않는다. 후속 계획 수립 시 계약별 영향/비용/위험을 산정한다.

Construction Autonomy Mode는 설정돼 있지 않아 실패 후 사용자 선택을 기다린다. 자동 loop-back 사용은 0/3이며 아직 Code Generation 재진입이나 소스 수정을 실행하지 않았다. Retry with fix는 문구 분리 수정의 재계획·승인을 진행하며, Accept failure는 실패/미검증을 보존한 채 단계 승인 검토로 진행하는 선택이다. 어느 선택도 전체 U5b 완료나 배포 승인을 대신하지 않는다.

## 최초 산출물 검사 (보존)

필수 문서 7개를 생성했다. 직접 입력 code-generation-plan·unit-test-instructions·code-summary의 참조를 확인한다. 처음 직접 실행한 upstream 검사에서는 consumes 인자를 생략해 검사 대상이 없었으며 검증 증거로 사용하지 않았다. 명시 인자를 준 검사에서는 code-summary 참조 누락을 발견해 이 단계 문서에서 보완했고 재검사 통과했다. 최종 필수 섹션·참조·문자/경로 검사는 아래 후속 확인 결과로 보존한다.

최종 required-sections는 7개 문서 전부 통과했고, 명시 consumes/deliverables를 사용한 upstream-coverage도 통과했다. 문서의 Pending 및 일본어 가나 검색은 0건이며 git diff --check도 통과했다. 측정 목표 23개는 Met10개·Not Met1개·Unverified12개로 확정했다. 소스 변경은 수행하지 않았다.

## Loop-Back Log

### Loop-back 1 — 2026-10-14T04:56:45Z

- Diagnosis: BT-F01, PracticeHeader.tsx 7행의 JSX 리터럴 3개가 기존 react/jsx-no-literals 규칙을 위반했다. 빌드·타입 검사·U5b 시험은 통과했다.
- Root-cause stage: code-generation. 생성된 화면 문구의 위치 문제이며 검사 설정 결함이 아니다.
- Planned fix: PracticeHeader.tsx의 표시 문구를 기존 u5bCopy.ts의 문구/포맷 함수로 옮긴다. 표시 결과와 접근성 단언을 유지한다. frontend U5b·U4a/U5a 회귀·커버리지·lint·typecheck·production build를 재검증한다. 기존 독립 구현 산출물과 미합의 실제 통합 범위는 보존한다.
- Estimated impact: 수정·표적 재검증·재검토 20~40분, 추가 유료 자원 비용 0, 두 파일의 문구 분리로 위험 낮음. 실제 통합·성능 미검증은 해소되지 않는다.
- 사용자 선택: Retry with fix. 새 Code Generation 계획의 Plan Approval을 받은 뒤 구현한다. 재진입 시 이 로그는 Modify로 보존한다. 자동/사용자 승인 loop-back 누계 1/3.
