# U16 서비스 가드레일 시험 지시서

## Sources

- code-generation-plan.md Step1–Step18 및 Testing Contract: test-after, Standard, 컴포넌트당5–8개와 핵심 통합 시험.
- ../functional-design/functional-spec.md WF1–WF7/Validation Handoff, rules.md BR1.1–BR7.1.
- ../nfr-design/ 및 ../nfr-requirements/: 기존 마감·개인정보·원자성·모듈/기능 줄80%·증거 분리.
- ../../../inception/contract-design/contract-summary.md C1–C6: 실제 요청·현재 사실·호환/실패 경계.

## Runner and Configuration

모든 아래 명령의 작업 디렉터리는 저장소 backend/다. 기존 Gradle wrapper·Java21·JUnit5/AssertJ/Mockito·Testcontainers PostgreSQL·JaCoCo·ArchUnit을 재사용한다. 운영 비밀을 읽거나 출력하지 않는다. DB 시험은 -Pmujoong.test.db=docker를 명시하고 별도 시험 컨테이너만 사용한다. 기존 실호출 태그의 고정 제외/키 제거는 유지한다.

승인 뒤 새 코드를 쓰기 전 기존 runner가 실제 시험을 실행하는지 다음 명령으로 확인한다. 이 명령은 이 단위가 변경하는 기존 요청 경계 한 클래스로 한정한다.

```bash
./gradlew --no-daemon :realtime-activity:test --tests kr.mujoong.realtime.voice.ProviderRequestFactoryWireTest -Pmujoong.test.db=docker
```

계획 시점에는 실행하지 않았다. 환경/기존 실패는 PREREQ/FAIL로 기록하고 통과했다고 표시하지 않는다. 새 시험이 아직 없으면 아래 필터의 시험 없음은 합격이 아니며 첫 해당 층 구현 뒤 시험 파일을 작성하고 실행한다.

## Unit-Scoped Commands

층 구현 직후 해당 층의 새 시험을 먼저 실행한다. 모든 새 시험 이름은 *ServiceGuardrail*Test 또는 *ServiceGuardrail*IT이며 기능 태그 service-guardrail을 붙인다. 기존 시험에는 임의로 새 태그를 붙여 범위를 축소하지 않는다. 아래 클래스 필터는 해당 U16 새 시험만 선택한다.

정책/로직/요청/세션/조회와 DB 통합 전체(U16 Realtime):

```bash
./gradlew --no-daemon :realtime-activity:test --tests '*ServiceGuardrail*' -Pmujoong.test.db=docker
```

각 층만 실행할 때는 다음과 같이 실제 클래스 이름을 정확히 지정한다. 같은 패턴의 나머지 클래스 이름은 계획의 Test Matrix와 일치시킨다.

```bash
./gradlew --no-daemon :realtime-activity:test --tests kr.mujoong.realtime.dialog.ServicePolicyCatalogServiceGuardrailTest -Pmujoong.test.db=docker
./gradlew --no-daemon :realtime-activity:test --tests kr.mujoong.realtime.session.SessionGuardrailFactsServiceGuardrailIT -Pmujoong.test.db=docker
./gradlew --no-daemon :realtime-activity:test --tests kr.mujoong.realtime.dialog.StopConfirmServiceGuardrailTest -Pmujoong.test.db=docker
```

Core 중계 및 새 계약 대조 시험:

```bash
./gradlew --no-daemon :core-api:test --tests '*ServiceGuardrail*' -Pmujoong.test.db=docker
./gradlew --no-daemon :contract-model:test --tests '*ServiceGuardrail*'
```

Core에 SessionViewServiceServiceGuardrailTest/IT, contract-model에 GuardrailPresentationServiceGuardrailTest를 작성해 빈 선택을 방지한다. 기존 ContractFilesTest/ContractSchemasTest/ArchUnit 전체 실행은 단위 완료 검증과 최종 Build and Test의 기존 회귀에 속하며 여기서 새 시험 필터로 대체하지 않는다.

기능 범위 커버리지(U16 세 모듈의 실제 변경 소스를 service-guardrail.txt에 등록한 뒤):

```bash
./gradlew --no-daemon :realtime-activity:test :realtime-activity:jacocoTestCoverageVerification -PincludeTags=service-guardrail -PcoverageScope=service-guardrail -Pmujoong.test.db=docker
./gradlew --no-daemon :core-api:test :core-api:jacocoTestCoverageVerification -PincludeTags=service-guardrail -PcoverageScope=service-guardrail -Pmujoong.test.db=docker
./gradlew --no-daemon :contract-model:test :contract-model:jacocoTestCoverageVerification -PincludeTags=service-guardrail -PcoverageScope=service-guardrail
```

각 명령은 기능 태그로 U16에 한정한다. 여러 실행의 JaCoCo 결과가 덮어써질 수 있으므로 각 실행 직후 HTML/XML·종료코드와 검사 모드를 기록한다. coverageScope 적용 결과를 모듈 전체 커버리지로 보고하지 않는다. 기존 모듈 전체 검사는 승인된 단위 검증 명령으로 별도 수행하며 이 지시서의 반복 실행 목록에는 넣지 않는다.

## Cases and Doubles

| 층 | 합격 기준/필수 경계 |
|---|---|
| 정책/재현 | UTF-8 원본/적용본 해시, 빈/누락/충돌 실패, 세션 고정 판, 지시형 문맥·한글·이스케이프·상한 |
| 실제 초기/후속 요청 | 새 자유대화 본문1회, 연습 유지, 송신4종 whitelist/null delegation_id, 범위 닫기, 강제 첫 질문/맞장구/재개압박 제거 |
| 안내/참여 | SPOKEN_VERIFIED 대역 메타데이터, placeholder/해시·판 불일치 거절, 현재 COMPLETED만 승인, ACK/STARTED는 완료 아님, 조기/지난 J1 무효, 수락 전이/효과1회 |
| 대화/종료 | 거절/휴식/도움1회, 로컬/J6 실제 요청 8종, 모호 확인의 현재 질문 완료/창, 실패 정상복귀·장애정리, 늦은 완료/답 no-op |
| 출력/시간 | 기존 JEV범주·확률·실패·정렬, ALLOW 뒤 현재성, 종료/끼어들기/휴식 뒤 폐기, 현재 인사 STARTED/정리, 동일 만료 우선·기존20초/4분/6분/40초/30초 의미 |
| 저장/조회 | 실제 DB rollback·경합·revision/statusVersion·삭제/legacy/재시작, PLAYING 복구금지, 확실한 가시 CHILD seq만 인용, Core fallback null |
| 보안/관측 | 기존 인증/소유·기기 현재 연결, 일반 로그/공급자 추가 메타데이터의 민감 표식0건, 새 비밀/AI 호출/재시도0 |

기존 SessionHarness/FakeProviderPort/FakeJudgeClient/FakeGuardrailClient/주입 MujoongClock을 사용한다. 실제 wire 시험은 로컬 대역 서버로 요청을 캡처하며 J6 결과 주입 시험과 분리한다. DB 통합은 Spring 트랜잭션과 실제 PostgreSQL을 사용해 원자성을 확인한다. 목(mock)의 호출 순서만으로 DB rollback 성공을 주장하지 않는다.

테스트 자료는 합성 이름/전사·가짜 키 표식·대역 PCM만 사용한다. 정책 원본은 사용자 소유 자료로 읽기만 하고 런타임이 해당 폴더에 의존하지 않게 한다. 실제 승인 음성을 요구하는 시험은 시험 전용 manifest/fixture를 명시적으로 주입하며 운영 placeholder의 승인을 변경하지 않는다.

## Coverage and Evidence

신규 구현 컴포넌트당5–8개, 정상과 최소2개 오류/경계 시험을 적용한다. 핵심 경계 통합 시험은 추가다. 모듈 전체 및 service-guardrail 기능 범위 줄80%를 모두 지킨다. 빈 범위/없는 파일·시험0개·환경 누락·skip을 PASS로 처리하지 않는다. 기존 단언/계약 대조/ArchUnit/하한을 낮추거나 비활성화하지 않는다.

코드 계획 각 층의 구현→해당 시험 작성/실행을 지킨다. 실패를 고치고 같은 층을 확인한 뒤 다음 층으로 이동한다. source-manifest.json에 운영/시험/계약/목록/자원을 모두 기록하고 traceability에는 실제 코드·시험 근거와 결과를 연결한다. 새로운 테스트 구성이 필요하면 기존 Gradle 규약/격리를 유지하며 최소한으로 추가한다.

## Startup Initialization Budget Verification

2026-10-21 사용자 지시 “일반 요청 2초는 유지하고, 시작 시 초기화에 별도 제한 시간 적용”에 따른 추가 검증이다. 새 graph-initialization-budget 기본10s 및 일반 graph-budget2s 분리를 설정/계약 대조와 실제 시작 경로에서 확인한다. 새 초기화 deadline/누적 마감/ready 미개방/일반2s 유지·중단/정리·무쓰기와 CAS 보존 시험을 해당 층 구현 후 보강한다. 기존 단언/예산/80%를 유지하며 과거 실패·취소 결과를 보존한다.

backend에서 설정 층 확인:

```bash
./gradlew --no-daemon :core-api:test --tests kr.mujoong.core.config.TimingPropertiesTest --tests kr.mujoong.core.config.ConfigKeyContractTest --tests kr.mujoong.core.startup.CorePropertiesValidationTest -Pmujoong.test.db=docker
```

초기화/원자성/실제 cold 준비 확인:

```bash
./gradlew --no-daemon :core-api:test --tests kr.mujoong.core.knowledge.GraphInitializationTechnicalRegressionServiceGuardrailTest --tests kr.mujoong.core.knowledge.Neo4jIT --tests kr.mujoong.core.practicegoal.GraphWorkflowIT -Pmujoong.test.db=docker
```

새 시험을 다른 파일에 분리하면 최종 실제 필터를 명시하고 시험0개는 성공으로 처리하지 않는다. 성공 후 기존 세 모듈 기능 명령·변경쪽 전체 clean check/계약/ArchUnit을 수행하며 frontend/global checkpoint/전체 verify는 부모가 정상 절차로 처리한다.

## Handoff and Limits

### Loop-back 1 추가 범위

기존 녹음 재생을 유지하며 code-generation-plan.md의 Repair1–8 및 RG1.1–RG1.8을 적용한다. 이미 Build and Test에서 추가한 ParticipationLateItemServiceGuardrailTest의2개 실패는 기준선의 알려진 결함이며, 실제 공급자의 itemId=null/start_ms/end_ms 경계로 보강해야 한다. 임의 ID만 기억해 합격시키지 않는다.

```bash
./gradlew --no-daemon :realtime-activity:test --tests kr.mujoong.realtime.session.ParticipationLateItemServiceGuardrailTest --tests kr.mujoong.realtime.session.ParticipationServiceGuardrailTest -Pmujoong.test.db=docker
./gradlew --no-daemon :realtime-activity:test --tests kr.mujoong.realtime.session.GreetingHandoffServiceGuardrailTest --tests kr.mujoong.realtime.dialog.InstructionTemplatesServiceGuardrailTest --tests kr.mujoong.realtime.voice.ProviderRequestServiceGuardrailTest -Pmujoong.test.db=docker
```

GreetingHandoffServiceGuardrailTest는 해당 층 구현 뒤 작성할 예정 파일이다. 정확한 실제 파일명/필터를 확인하고 시험0개는 실패로 처리한다. 참여/인사 각 컴포넌트5–8개 경계와 정상·최소2개 오류/반례를 보강한다. 실제 요청에 현재 안내 완료와 재시작 금지가 담기는지, 대역 모델이 이를 어겨도 중복 음성0프레임인지, 정상 아이 질문/인용·연습을 막지 않는지 함께 검증한다. 새 자동 AI 재생성/재시도·유료 호출 없이 기존 호출 수/마감·기능 줄80%와 모든 기존 단언을 유지한다. 신규 실제 변경은 manifest/기능 목록에 포함한다. 프롬프트 문자열 검사만으로 전체 동작이나 실모델의 항상 준수를 보장하지 않는다.

승인된 Construction Verification Command 및 최종 전체 verify.sh는 단위/Build and Test에서 별도로 실행한다. 이 지시서의 모든 실행 명령은 U16 범위에 한정되어 전체 시험을 단위마다 중복 호출하지 않는다. 최종 U17 통합은 실제 대역 Realtime→Core→UI/기기 소비를 확인한다.

자동-대역/실호출-합성/실제기기/실제아동 증거는 구분한다. 이 계획의 자동 실행은 가짜 공급자만 사용한다. 실제 안내 말소리 생성·청취·승인과 유료 호출은 미실행이며 대역 성공을 실제 안내 기능 완성으로 표시하지 않는다.
