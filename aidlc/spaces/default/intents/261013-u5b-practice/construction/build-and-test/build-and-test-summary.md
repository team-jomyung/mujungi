# U5b 빌드·시험 요약

## 재검증 결과 — 2026-10-14T05:22:30Z

BT-F01 문구 분리와 사용자 승인 뒤 기존 문서를 보완하여 재검증했다. 기존 실패 기록과 Loop-Back Log 1은 보존한다. 이번 단계에서는 애플리케이션 소스·시험·설정·계약을 변경하지 않았다.

**실행 가능한 모든 빌드·시험 명령은 성공했다.** 독립 U5b 102건, U1 1,483건 중 1,480건 통과·기존 LOCAL 3건 건너뜀, U3 283건, Core U4a/U5a 110건, 프런트 U4a/U5a 116건을 확인했다. Realtime 줄 546/553=98.734%, Core 77/78=98.718%, 프런트 60/60=100%로 각각 80% 기준을 충족한다. lint 오류·경고는 0이며 typecheck와 production build도 성공했다.

최신 Target Verification Matrix는 **Met 11개 / Not Met 0개 / Unverified 12개**다. T-LINT는 이번 재검증으로 Met으로 바뀌었다. 나머지 실제 통합·운영 시간·성능·보안/접근성·마이그레이션·Cue 생성물 품질 목표는 그대로 Unverified다. 따라서 전체 단계 결과는 **FAIL**이며 독립 구현 검증 성공과 전체 U5b 완료를 구분한다.

## 현재 실패 처리 선택

남은 원인은 I1~I5 미합의와 실제 연결·공급자/부하·운영 R·브라우저/기기·V3→V4 및 평가자 확인 증거 부재다. 라이브러리/버전·이미지·인스턴스·알고리즘·플래그 교체만으로 이 미합의 입력과 실제 증거를 확보하는 구체적인 수정 후보는 없다. 이 판단은 작업량이 크다는 이유의 제외가 아니다. 로컬 5어절 비교 시험을 추가하더라도 실제 생성물과 평가자 승인 및 나머지 통합 목표를 대신하지 않는다.

기존 INT-1~INT-5의 착수 전제와 같은 Intent의 새 Code Generation 계획·별도 Plan Approval을 유지한다. 합의 자료와 U4b V3 main 병합 증거가 확보되면 최신 브랜치/CI 및 기준 커밋 이후 차이를 확인하고 실제 변경·시험·비용·위험을 산정해야 한다. 합의 전 외부 계약이나 운영 R을 임의 확정하지 않는다. 현재 재시도 누계는 1/3이며 새 loop-back은 실행하지 않았다.

사용자는 Accept failure를 선택했다. 전체 결과 FAIL과 미검증 12개를 보존한 채 이 단계의 승인 검토로 진행한다. 이 선택은 실제 배포나 전체 U5b 완료를 승인하지 않는다. INT-1~INT-5의 별도 계획·승인 및 실제 검증 의무를 유지한다.


## 범위와 원본 목록

직접 입력은 `code-generation-plan.md`, `unit-test-instructions.md`, `code-summary.md`다. NFR Requirements/NFR Design 단계는 미실행이며 산출물이 없다. U5b requirements.md의 NFR1~10·NFR12, functional-design/rules.md와 Q4/Q6, 코드 생성 계획의 Testing Contract·INT-1~5, 시험 지침을 대조했다. NFR5/7 수치의 읽기 전용 원본은 메인 Intent의 inception/requirements-analysis/requirements.md 비기능 요구사항 표다. NFR8 리포트는 U6 범위 제외이며 이 행렬의 측정 대상이 아니다. 단계/제품 전체 기준을 낮추거나 기존 Deferred를 OK로 바꾸지 않았다.

## 실행 결과

**전체 결과: FAIL.** BT-F01 문구 분리 후 lint 오류는 해소됐다. 독립 시험·커버리지·빌드와 실제 통합/성능 및 생성물 품질 미검증을 구분하며, 남은 목표 12개는 Unverified다. 후속 validation 단계가 현재 계획에 예약돼 있지 않으므로 성공적인 검증 이관으로 처리하지 않는다. 단계 완료 승인·배포 준비·Intent 전체 완료를 선언하지 않는다.

- 빌드: Core/Realtime jar·Next production build 성공.
- 독립 시험: U5b 102건 통과, 신규 운영 파일 전체 줄 커버리지 각각 80% 이상.
- 기존 회귀: 새 시험 실패 없음. 기존 LOCAL PostgreSQL 3건 건너뜀은 미실행으로 구분한다.
- 코드 스타일: 문구 분리 후 재검증에서 lint 오류·경고 0, typecheck 성공.
- 실제 통합·실제 성능·생성물 품질·브라우저 경로: 미검증, 배포 준비 불충족.


상세 명령/실행 수치는 test-results.md, 경계·성능·보안 지침은 integration-test-instructions.md·performance-test-instructions.md·security-test-instructions.md에 기록한다. 빌드 지침도 생성했다. 기존 Q1~Q6/CG-Q1과 I1~I5·U5b-J3~J5는 유지했다. 메인·U4b Intent는 수정하지 않았다.

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

## 제품 전체 요구사항 연결

cross-unit-traceability.md는 FR11개·NFR11개·담당AC22개의 완료 연결을 검사했다. 전부 실제 통합 완료 OK가 없고, Deferred43개 및 범위 제외 N/A1개다. 제품 FR/NFR/AC의 부분 구현 증거 7개 경로와 전체 추적표의 구현·시험 증거 20개 경로는 모두 존재하지만 전체 기준을 충족한 것으로 세지 않는다. 전체 커버리지 판정은 FAIL이다.

## 최초 실패 처리 이력 (보존)

문제 BT-F01은 PracticeHeader.tsx 7행의 JSX 문구 세 곳이다. react/jsx-no-literals 규칙에 따라 화면 문구/포맷 함수를 기존 u5bCopy.ts로 옮기고 PracticeHeader.tsx가 이를 사용하도록 수정할 수 있다. 규칙 비활성화·예외 추가·표시 문구 변경은 필요하지 않다. 원인은 생성 소스이므로 이번 시험 단계에서 몰래 수정하지 않고 Code Generation 재진입의 후보로 제시한다.

예상 영향: 작업·표적 재검증 및 재검토 20~40분, 추가 유료 자원/외부 호출 비용 0, 표시 결과를 보존하는 두 파일 변경으로 위험 낮음. 문구 결과·접근성 기존 단언을 유지하며 frontend U5b/회귀, lint/typecheck/build를 재검증한다. 후속 계획은 이 수정 범위로 구체화해 새 Plan Approval을 받는다.

이 수정은 I1~I5·실제 성능·운영 R·V3→V4·화면 통합 미검증을 해소하지 않는다. 해당 부분은 기존 INT-1~5 계획의 별도 승인·합의가 필요하다. 합의 없는 외부 계약/API를 만들지 않는다. 현재 확정된 구현 후보는 문구 분리 수정이며 실제 통합 수정 공수는 합의 입력 전 정밀 산정하지 않는다. 후속 계획 수립 시 계약별 영향/비용/위험을 산정한다.

Construction Autonomy Mode는 설정돼 있지 않아 실패 후 사용자 선택을 기다린다. 자동 loop-back 사용은 0/3이며 아직 Code Generation 재진입이나 소스 수정을 실행하지 않았다. Retry with fix는 문구 분리 수정의 재계획·승인을 진행하며, Accept failure는 실패/미검증을 보존한 채 단계 승인 검토로 진행하는 선택이다. 어느 선택도 전체 U5b 완료나 배포 승인을 대신하지 않는다.

