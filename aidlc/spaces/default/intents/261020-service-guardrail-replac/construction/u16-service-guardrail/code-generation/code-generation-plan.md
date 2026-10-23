# U16 서비스 가드레일 코드 구현 계획

## Summary

- Builds: 늦은 참여 발화·녹음 후 재인사 방지(RG1)와, 사용자 추가 지시에 따른 Neo4j 초기화 전용 제한 시간 분리. 일반 요청2초 유지.
- Touches: 기존 U16 세션/첫 출력 경계에 Core Graph 초기화·서버 설정/시간 계약·관련 시험/추적/범위를 추가. U17 프런트 보존.
- Tests: 참여·RG1 기존 회귀를 유지하고 초기화 전용10초/일반2초·ready/마감/정리·실제 cold Neo4j를 검증. 모듈/기능 LINE80%·계약/ArchUnit 유지.

## Sources

- ../functional-design/{functional-spec,rules,entities}.md: WF1–WF7, BR1.1–BR7.1, 실제 안내 자원 후속 항목.
- ../nfr-requirements/ 및 ../nfr-design/: 상세 NFR20개, 기존 마감·80%·권한·원자성·관측 설계.
- ../../../inception/contract-design/contract-summary.md: C1–C6, 공개 guardrail 객체와 내부 조회·기기 재생 계약.
- ../../../inception/units-generation/{unit-of-work,unit-of-work-story-map}.md: U16 서버 소유와 U17 UI 소비 경계.
- ../../../inception/requirements-analysis/requirements.md 및 ../../../inception/user-stories/stories.md: FR1–FR7, NFR1–NFR6, US1.1–US2.2, R-01.
- 현재 코드: SessionTransitions.Extras, TransitionService의 TransactionTemplate/승자 조건부 전이, SessionEventRepository, SessionViewQuery, ProviderRequestFactory, DialogInstructions, JudgmentPrompt.
- backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts: includeTags·DB 선택·기능 범위 파일 검증 및 기존 실호출 격리.

## Scope and Impact

기존 feat/service-guardrail-replacement의 코드를 제자리에서 수정한다. 현재 작업 트리의 다른 기록·CodeKB·knowledge 변경은 보존한다. 계획 승인 전 애플리케이션 소스·시험·빌드 구성은 변경하지 않는다. 승인 후 기준선 검사부터 실행하며, 계획 체크박스보다 git 이력·실제 diff·시험 결과를 먼저 확인한다.

영향은 높음: 공용 세션 실행기·공급자 요청·Core 조회·공개 스키마에 걸친다. 자유대화와 연습의 실제 요청을 함께 대조하고, 앱 간 직접 코드/DB 참조는 만들지 않는다. U17의 frontend 코드·copy·화면·기기 웹은 이 계획에서 수정하지 않는다.

| 영역 | 주요 파일/신규 파일 제안 | 소비/영향 |
|---|---|---|
| 정책 | realtime/dialog/ServicePolicyCatalog.java, PolicyBundle.java, ConversationPolicyState.java; resources/service-policy/ | 자유대화 초기·후속 요청, 세션 고정 판 |
| 송신 | dialog/DialogInstructions.java, InstructionTemplates.java; voice/ProviderRequestFactory.java, ProviderClient.java, ProviderPort.java | 모드별 요청, 교정·재시도·연습 |
| 판단 | dialog/StopIntentJudge.java, StopConfirmFlow.java; judgment/JudgmentPrompt.java, resources/judgment/ | 기존 J1/J6 요청·결과, 추가 호출 없음 |
| 상태·출력 | session/SessionExecutor.java, SessionContext.java, JoinFlow.java, EndingFlow.java, GuardrailCoordinator.java, 관련 현재성/시계; gate/OutputGate.java | 안내·참여·휴식·종료·후보·기기 메시지 |
| 저장 | session/SessionTransitions.java, TransitionService.java, SessionEventRepository.java; 신규 SessionGuardrailFacts.java, SessionGuardrailFactsRepository.java | 기존 세션 이벤트·전이·삭제 수명, JDBC 원자성 |
| 조회 | session/SessionViewQuery.java, session/web/SessionInternalController.java; core/activity/RealtimeSessionClient.java, SessionViewService.java, LiveExtras.java; contract-model의 해당 DTO/신규 GuardrailPresentation | 기존 인증 후 C1/C2 중계, legacy/null/UNKNOWN |
| 계약 | contracts/schemas/guardrail-presentation.schema.json, live-view.schema.json, internal-session-view.schema.json; 관련 OpenAPI/AsyncAPI·ai-io·deviations | U17 소비, 엄격한 스키마/DTO 대조 |
| 시험 | 각 모듈 src/test/java의 *ServiceGuardrail*Test 및 *ServiceGuardrail*IT; backend/coverage-scope/service-guardrail.txt | 아래 검증 매트릭스와 기존 회귀 |

경로 표의 realtime/dialog 등은 backend/realtime-activity/src/main/java/kr/mujoong/realtime/ 기준이다. 새 타입은 기존 기능 패키지에 둔다. 테스트/커버리지는 유닛 번호 대신 service-guardrail을 쓴다. 적용되는 기존 파일과 새 파일은 최종 source-manifest.json에 빠짐없이 기록한다.

## Ordered Implementation Steps

- [ ] Step 1 — 기준선·실행 준비. git 상태/브랜치/이력과 실제 Java21·Docker·기존 Gradle runner를 확인한다. 승인된 단위 검증 명령으로 변경 전 결과를 보관한다. 실패면 기존 실패·환경 미준비를 구별하고 코드 수정 전에 보고한다. 기존 테스트 실행 준비 명령은 unit-test-instructions.md에 명시한다. 기존 운영/수동 스택은 건드리지 않는다. [NFR3, NFR4]
- [ ] Step 2 — 정책 자원/구성 층 구현. 사용자 원본은 수정하지 않고 service-0.1 원본 해시·앱 적용본 해시·편차를 새 앱 자원과 manifest로 고정한다. ServicePolicyCatalog/PolicyBundle은 UTF-8 바이트, 판 충돌/누락/빈 본문 검증과 자유대화/연습 선택을 제공한다. 턴별 파일 접근·hot reload 없음. 기존 기능과 다른 저장/열람/삭제 약속은 편차 근거로 교정한다. [US1.1, US2.1 서버 부분; BR1.1–BR1.3, BR5.1–BR5.2]
- [ ] Step 3 — 정책 층 시험 작성·실행. ServicePolicyCatalogServiceGuardrailTest: 정상/누락/빈 값/해시/판 충돌/세션 고정/한글 보존/모드 분리 8개. 실제 자원 복사/깨끗한 빌드 대조도 포함한다. [NFR5]
- [ ] Step 4 — 사실 자료형·저장 층 구현. 기존 realtime_session_event에 SESSION_GUARDRAIL_FACTS만 추가한다. SessionTransitions.Extras를 명시적 사실 값으로 확장하고 TransitionService의 기존 짧은 JDBC 트랜잭션에 직접 insert한다(AsyncRecorder에 확정 사실을 맡기지 않음). 상태 전이·Outbox·전사 삭제·사실 삽입을 함께 커밋하고 패자/실패의 효과는 실행하지 않는다. 상태를 바꾸지 않는 확정 사실도 같은 세션 행 FOR UPDATE 및 expected statusVersion 확인 아래 단조 revision으로 쓴다. 재시도에서 중복 revision/효과가 생기지 않게 기존 DB 재시도 의미와 맞춘다. [US1.3, US1.6, US2.1 서버 부분; BR5.3]
- [ ] Step 5 — 저장 층 시험 작성·실행. SessionGuardrailFactsServiceGuardrailTest 5–8개 및 SessionGuardrailFactsServiceGuardrailIT: rollback, 조건부 전이 패자, 동시 writer, 같은 revision의 상이 payload 거절, 상태 버전 불일치, 삭제 수명, 재시작 조회를 실제 PostgreSQL로 확인한다. 최신 사실은 세션·event_type 제한 쿼리로 읽고 created_at/UUID를 revision 순서로 쓰지 않는다. 쿼리 계획을 확인하며 인덱스 추가가 필요하면 최신 migration 번호/기존 SQL을 다시 확인해 새 additive migration만 작성한다. 현재 realtime 최댓값은 V7이며 옛 migration을 수정하지 않는다. 필요 시 같은 작업에서 infra/migration-approvals.txt에 근거를 남긴다. [NFR1.4, NFR2, NFR6]
- [ ] Step 6 — 실제 공급자 요청·지시 층 구현. 세션 고정 정책을 ProviderPort→ProviderClient→ProviderRequestFactory로 내부 인자로 전달하고 기존 공개 송신 whitelist는 유지한다. 자유대화 초기 정책은 한 번, 최소 이름/나이/관심사만 이스케이프한다. FIRST/CONTINUE/교정/재시도의 강제 질문·의무 맞장구·자동 재개 압박을 제거하고 이번 응답의 범위를 닫는다. 연습의 기존 역할·검증된 PracticeInstruction 경로를 유지한다. [US1.1, US1.4, US1.5, US2.2; C4]
- [ ] Step 7 — 요청 층 시험 작성·실행. ProviderRequestServiceGuardrailTest와 InstructionTemplatesServiceGuardrailTest 각 5–8개, 실제 wire 대역 IT. 한글·따옴표·역슬래시·제어문자·길이 상한·지시형 데이터·숨긴 ID0건·null delegation_id·500토큰 경계·모드 분리를 확인한다. [BR1.3–BR1.4, BR3.4]
- [ ] Step 8 — 안내·참여 층 구현. 기존 승인 카탈로그의 현재 콘텐츠 판/본문/파일 해시·형식·길이·검수판을 검증한다. PLACEHOLDER와 SPOKEN_VERIFIED를 구별하고 실제 음성 미준비면 기술 실패로 닫는다. 현재 연결/session/playback/정책/서버 마감을 묶은 안내 증거를 만들고 실제 COMPLETED만 인정한다. 완료 전 시작된 입력·판정은 무효화한다. 실제 참여 질문 완료 뒤 현재 수락/거절·무응답만 원자적으로 기록하며 승자만 원음 기록/첫 응답을 시작한다. [US1.2, US1.3; BR2.1–BR2.6]
- [ ] Step 9 — 안내·참여 층 시험 작성·실행. NoticeAssetServiceGuardrailTest, NoticeDeliveryServiceGuardrailTest, ParticipationServiceGuardrailTest 각 5–8개와 기기 WS 대역 IT. missing/placeholder/해시 불일치/만료/실패/다른 연결/중단 뒤 늦은 완료/중복/조기 발화·늦은 J1/동일 마감/이중 수락/기록 실패를 포함한다. 시험용 음성/검수 메타데이터 대역을 실서비스 승인으로 승격하지 않는다. [NFR2, NFR4]
- [ ] Step 10 — 대화·판단·종료 층 구현. 서버의 현재 정보 요구 참조·거절·resting·helpUsed와 목적 REST_ACK를 둔다. 문자열 순번만으로 의미 동일성을 보장하지 않고 불명확하면 추가 요구를 강제하지 않는다. StopIntentJudge와 J1/J6 고정 질문에 등록/비등록 종료·인용/역할 대사·부정·화제 거절·모호함·휴식 구분을 반영한다. 명확 종료는 즉시 처리하고 확인 ASKING의 실제 질문 완료 뒤에만 WAITING을 연다. 확인 질문 실패는 현재 질문 보류만 해제하고 정상 연결이면 자동 발화 없이 듣기로 복귀한다. 장애 지속/종료 선행은 기존 기술 정리/종료를 따른다. [US1.4–US1.6; BR3, BR4; NFR Design Q1-A]
- [ ] Step 11 — 대화·종료 층 시험 작성·실행. ConversationPolicyServiceGuardrailTest, StopIntentServiceGuardrailTest, JudgmentRequestServiceGuardrailTest, StopConfirmServiceGuardrailTest 각 5–8개 및 실제 로컬/J6 요청 8종 R-01 매트릭스. 상태효과 대역 시험과 실제 요청 조립 검증을 구별한다. 질문 자원/생성/검수/송신/재생 실패·만료·중단, 늦은 답, 반복 질문0회, 정상 WAITING 만료와 미전달 실패 차이를 확인한다. [BR4.1–BR4.4]
- [ ] Step 12 — 출력·인사 층 구현. OutputGate/GuardrailCoordinator의 기존 JEV7범주·800ms·정렬/완결·확률/동률/실패 정책을 유지하면서 session/mode/generation/목적/후보·정책판/JEV판을 내부 연결한다. ALLOW 후와 큐 전달 시 현재 actor 권한을 확인한다. 종료/끼어들기/휴식 뒤 옛 일반 출력은 폐기한다. 현재 승인 인사 STARTED 뒤에만 PLAYING, 완료/실패/중단/단절/만료/최종종료에서 해제하며 즉시 음소거는 추가발화0회다. [US1.6, US2.2; BR4.4, BR5.4, BR7.1]
- [ ] Step 13 — 출력 층 시험 작성·실행. OutputAuthorityServiceGuardrailTest 5–8개와 세션/검수 통합 IT. ALLOW 직후 취소·모드/세대 변경·휴식 수용1회·종료 후 늦은 후보·미확인 출력0회·인사 STARTED 전/후/정리·즉시중단·연습 경계를 검증한다. [NFR2, NFR6]
- [ ] Step 14 — API·조회 층 구현. GuardrailPresentation 선택 객체를 C1/C2 DTO와 스키마에 선언하고 기존 소유 확인→내부 HTTP→조회 경계를 유지한다. actor 불변 projection과 최신 확정 사실을 상태/버전으로 대조한다. actor 없음/재시작의 PLAYING/PENDING은 복구하지 않는다. 종료 인용은 같은 세션의 공개·확정·확실한 CHILD seq만 확인한다. Core 내부조회 실패는 stale=false여도 guardrail=null이며 별도 사실 사본은 저장하지 않는다. [US2.1 서버 부분, US1.6; C1–C3]
- [ ] Step 15 — 조회 층 시험 작성·실행. PresentationQueryServiceGuardrailTest 5–8개, Core의 SessionViewServiceServiceGuardrailTest 5–8개 및 SessionViewServiceGuardrailIT. legacy/없음/충돌 revision/삭제 전사/권한/terminal/재시작/Core fallback/동일 statusVersion의 동적 사실을 검증한다. U17에 정상·거절·무응답·UNKNOWN/null·인사 재생/종료 fixture를 인계한다. [BR5.3–BR5.4, NFR1]
- [ ] Step 16 — 계약·빌드·범위 검증. 각 층과 함께 바꾼 contracts/·Java DTO를 최종 대조하고 오류/시간/enum을 불필요하게 추가하지 않는다. 기존 계약 대조·ArchUnit을 약화하지 않고 필요한 새 계약 시험을 더한다. backend/coverage-scope/service-guardrail.txt와 service-guardrail.tests에 실제 변경된 운영 소스/시험을 기록한다. 기존 모듈/해당 기능 줄80%를 모두 만족한다. 기존 실호출 태그 격리를 유지한다. U17 프런트 층은 해당 단위의 후속 작업이다. [NFR3, NFR5, NFR6]
- [ ] Step 17 — 단위 검증·보안·회귀. 승인된 Construction Verification Command를 그대로 실행하고 현재 소스의 결과/커버리지·기존 회귀·로그 위생 근거를 기록한다. 최종 Build and Test에 전체 bash scripts/verify.sh를 넘긴다. 실제 유료 호출/원격 배포는 실행하지 않는다. [US2.2, NFR1–NFR6]
- [ ] Step 18 — 문서·추적·완료 자료. code-summary.md, traceability.json, source-manifest.json을 작성한다. BR/상세 NFR/40AC→코드·시험·결과와 U17 부분을 구분한다. 실제 안내 말소리 미준비/실호출·실기기 미실행을 명시하고 후속 생성·청취·등록 조건을 보존한다. 완성된 코드·Step5 자료 이후 외부 설계 검토를 요청한다. [전체 U16]

## Persistence and Compatibility Decisions

중요 사실을 일반 비동기 로그에 넣으면 전이와 원자성을 보장하지 못하므로 기존 TransitionService 트랜잭션의 직접 저장으로 연결한다. 세션 행 잠금 아래 마지막 유효 revision을 읽고 다음 값을 발급하며 예상 statusVersion과 같은 revision의 payload를 검증한다. 저장 실패에서는 메모리 상태·원음 시작·첫 응답의 성공을 먼저 만들지 않는다. 조회는 event_type/세션을 제한해 최신 revision만 읽고 손상·충돌은 null/UNKNOWN으로 낮춘다.

새 테이블·DB 제품·기본 시간/오류·공급자 필드는 계획하지 않는다. 기존 DB 인덱스로 충분하지 않은 경우만 Step5의 새 additive migration을 사용한다. C1/C2 필드 추가는 이전 strict UI와 자동 호환이라고 주장하지 않으며 U17과 동시 전환이 필요한 사실을 인계한다. 실제 배포는 수행하지 않는다.

## Test Matrix

| 책임(12개) | 신규 시험 클래스 | 예상 단위 사례 |
|---|---|---|
| 정책 적재 | ServicePolicyCatalogServiceGuardrailTest | 5–8 |
| 공급자 요청 | ProviderRequestServiceGuardrailTest | 5–8 |
| 후속 지시 | InstructionTemplatesServiceGuardrailTest | 5–8 |
| 승인 자원 | NoticeAssetServiceGuardrailTest | 5–8 |
| 안내 증거 | NoticeDeliveryServiceGuardrailTest | 5–8 |
| 현재 참여 | ParticipationServiceGuardrailTest | 5–8 |
| 대화 상태 | ConversationPolicyServiceGuardrailTest | 5–8 |
| 로컬 종료 | StopIntentServiceGuardrailTest | 5–8 |
| J1/J6 요청 | JudgmentRequestServiceGuardrailTest | 5–8 |
| 종료 확인 | StopConfirmServiceGuardrailTest | 5–8 |
| 출력 권한 | OutputAuthorityServiceGuardrailTest | 5–8 |
| 사실/조회 | SessionGuardrailFactsServiceGuardrailTest, PresentationQueryServiceGuardrailTest, SessionViewServiceServiceGuardrailTest | 각 5–8 |

표의 마지막 책임은 저장/Realtime 조회/Core 중계 3개 구현 컴포넌트로 나뉘므로 신규 단위 시험 목표는 14클래스·70–112개다. 핵심 경계 통합은 DB 원자성·실제 wire/J6 요청·기기 WS·조회 중계이며 새 *ServiceGuardrail*IT로 실행한다. 기존 회귀는 별도다. 각 책임은 정상+최소2개 오류/경계 사례를 포함한다.

## Assumptions & Open Questions

실제 서비스 안내 말소리 생성·청취·승인은 미완료다. 이번 계획은 현재 파일을 대역용으로 유지하고 미준비 시 일반 대화를 차단하는 승인 설계를 구현한다. 유료 GPT-Live 낭독 생성은 별도 실행 승인 이후의 후속 작업이며 이 계획 승인이 실호출 승인으로 바뀌지 않는다. 실제 자원이 없는 상태를 안내 기능/실기기 성공으로 보고하지 않는다.

계획 승인 뒤 환경/기존 실패 또는 새로운 권한·보존·계약 분할이 필요하면 해당 차이를 보고한다. 시험 하한·기존 단언·시간 제한을 낮추어 해결하지 않는다. 신규 파일명은 위 책임을 기준으로 하되 기존 코드에 이미 있는 동일 책임을 확인해 중복 클래스를 만들지 않는다.


## Repair Plan — Loop-back 1

### Sources and Scope

../../build-and-test/test-results.md B-R01과 Loop-back 1, cross-unit-traceability.md 및 현재 구현/지시서를 입력으로 한다. 2026-10-21 사용자 추가 지시: “그 방법을 유지하되, 발생할 수 있었던 인사 후 재인사 위험은 이번 작업에서 함께 해결할것”. 이를 RG1로 추적하며 FR2.5/FR3.1의 대화 연결을 구체화한다. 앞선 설계/요구 문서와 사용자 원본은 덮어쓰지 않는다.

이번 실행은 아래 Repair 단계만 수행한다. 위 체크된 Step1–18은 이전 시도의 완료 기록이고 재구현 지시가 아니다. U17 재연결/권한403 실패는 후속 수정 대상이며 이 U16 계획은 프런트 파일을 수정하지 않는다.

### Diagnosis and Wire Evidence

ParticipationLateItemServiceGuardrailTest의2개 합성 대역에서 WAITING_CHILD 대신 ACTIVE가 됐다. 안내 이전 입력이 quiet 뒤 새로 시작된 입력처럼 수신 시각을 얻는다. 실제 ProviderPort/ProviderClient에서는 공급자 itemId가 null이고 start_ms/end_ms가 있다. 명시적 itemId만 있는 앱 내부 대역2개를 실제 wire 검증으로 취급하거나, 과거 ID Set만 추가해 해결했다고 하지 않는다. nullable ID/전사 시간선과 기존 입력/공급자 세대의 실제 경계를 검증한다.

녹음 재생은 모델 자신의 대화 이력이 아니다. 현재 FIRST 지시는 안내/참여 완료를 알리지만 시작 인사/자기소개 반복을 명시적으로 막지 않으며, FIRST/CONTINUE의 자유대화 문장이 같은 경로다. 실제 완료된 현재 안내와 첫 일반 응답을 연결하고, 명확한 재시작 인사 후보가 기기 음성으로 나가기 전에 막는 처리가 필요하다. 요청 문구 시험만으로 이 행동 전체가 확인됐다고 하지 않는다.

### Ownership and Impact

운영 소유: realtime/session/SessionExecutor.java 및 필요한 기존 SessionContext/JoinFlow/GuardrailCoordinator 협력 경계, dialog/InstructionTemplates.java, voice/ProviderRequestFactory.java. 새 내부 helper가 필요하면 같은 기능 패키지에 두고 책임/최종 경로를 manifest에 포함한다. 기존 후보의 전사/음성 일치·현재성/JEV 검수와 그 하한은 유지한다. 추가 AI 판정·자동 재생성/새 재시도·공급자 type·SDK·DB/migration·원문 영속·시간 설정은 추가하지 않는다. 필요한 의미 계약 설명은 contracts/ai-io.md와 해당 기존 시험에서 함께 맞춘다.

예상 영향: 참여 귀속1–2시간, 인사 연결/출력 경계1–2시간, 추적 정리15–30분 및 별도 검증 시간. actor·세대·첫 출력 경계 위험은 중간이며, 실제 유료 호출 없이 로컬 대역을 사용해 추가 공급자 비용0이다. 녹음 파일 재생을 실시간 낭독으로 전환하거나 미검수 음성을 운영 승인하지 않는다.

### Ordered Repair Steps

- [x] Repair 1 — 실행 준비/기준선. 현재 diff와 기존203개 claim, 기존35개 운영 scope 및 현재 테스트/지시서 수정을 보존한다. 기존 runner 준비와 알려진 B-R01 실패2개를 구분해 기록한다. 실제 null itemId/start_ms/end_ms 경로와 기존 입력 프레임·공급자 세대/시계를 대조한다. 없는 시험 필터는 합격이 아니다. [US1.3, FR2.3, NFR3.3]
- [x] Repair 2 — 참여 업무 층 구현. 안내 이전에 시작된 입력이 quiet·재안내 창 뒤 늦게 도착해 참여를 새로 수락하지 못하도록 현재 입력 시작 귀속을 유지한다. 수신 시각만을 새 발화 근거로 쓰지 않는다. 실제 wire 및 레거시 내부 대역을 구분하며, 구 세대/정리 뒤 결과는 폐기한다. 새 원문/원음 영속 없이 최소 세션 내 상태를 사용하고 수락/거절/정리 시 해제한다. [US1.2/US1.3, FR2.2–FR2.5/FR6.3, NFR1.4/NFR2.1]
- [x] Repair 3 — 참여 층 시험 보강·실행. 기존 실패2개를 유지하고 실제 null ID/시간선 지연, 안내 완료 전 이미 quiet 된 입력, quiet 후 꼬리·재안내 창·구 세대와 정상 새 발화까지 현재 참여 컴포넌트5–8개 경계를 보강한다. 잘못된 ACTIVE/기록/첫 응답0회, 정상 새 수락/효과1회를 실제 실행기로 확인한다. 식별/시간 근거가 없으면 임의 현재 수락으로 추정하지 않는다. [AC1.2.5/AC1.3.1–AC1.3.5, NFR2/NFR3]
- [x] Repair 4 — RG1 인사 연결 층 구현. 현재 검증 안내의 실제 COMPLETED와 참여 수락 이후에만 첫 일반 응답 지시를 보낸다. 지시에는 승인된 시작 안내가 이미 전달됐다는 현재 사실과 시작 인사/자기소개/참여 질문을 반복하지 말고 아이의 마지막 말에 이어 답하라는 범위를 담는다. ACK/STARTED/실패/다른 세션/옛 연결을 완료로 추정하지 않는다. 기존 FIRST 발화 요청에 연결하고 추가 모델 호출이나 아이 원문 복사를 만들지 않는다. CONTINUE·연습·명시적 아이 질문에의 답과 구분한다. [RG1, FR2.5/FR3.1/FR7.3]
- [x] Repair 5 — RG1 출력 경계 구현. 첫 응답 구간의 명확한 재시작 인사·자기소개 또는 승인 시작 대본 반복을 현재 후보 전사에서 확인하고 기기 송신 전 해당 음성/전사 구간 전체를 보류/폐기한다. 전사만 잘라 음성과 다르게 만들거나 JEV ALLOW로 우회하지 않는다. 새 자동 교정/재생성 호출 없이 기존 듣기·대기/실패 처리로 연결하며 무한 재시도를 만들지 않는다. 첫 응답의 수명은 제안/수신이 아니라 실제 현재 재생/정리와 연결하고, 취소/끊김/종료·늦은 ALLOW·재접속으로 초기화해 재인사가 새지 않게 한다. [RG1, FR4.3, NFR2/NFR6]
- [x] Repair 6 — RG1 층 시험 작성·실행. 실제 ProviderRequestFactory 요청 조립과 SessionExecutor/출력·기기 링크 대역을 함께 검사한다. 아래 RG1 상태 행렬5–8개와 필요 핵심 통합을 추가해 불필요한 재인사/대본 반복 음성0프레임, 정상 후속 응답·자발 질문의 허용, 요청 횟수 불변을 단언한다. 단순 프롬프트 문자열 검사나 실모델 성공 주장을 완료 증거로 삼지 않는다. [RG1, US1.1/US1.2/US1.3/US1.4/US2.2, NFR3/NFR4]
- [x] Repair 7 — 추적·범위·계약. 현재 담당 FR31개·상위 NFR6개를 실제 단일 구현/시험 파일로 연결하고 기존40AC/BR/상세 NFR을 보존한다. RG1도 코드/시험으로 연결한다. 각 OK target은 한 파일이며 보조 설명은 justification이다. 새 시험과 실제 수정 운영 소스를 기능 목록/manifest에 포함하며 기존 분모를 축소하지 않는다. 계약 의미가 바뀌는 설명은 contracts/와 대조 시험에서 함께 반영한다. 미완료 음성/전체 verify는 OK로 바꾸지 않는다. [NFR3/NFR5, XTRACE]
- [x] Repair 8 — 회귀·완료 자료. 새 정확한 필터 및 기존 유닛 명령/기능3모듈 coverage, 모듈 전체/계약/ArchUnit 검사를 수행한다. 기존80%/단언/시간 설정은 유지한다. 전체 프런트의 알려진 U17 실패와 verify.sh 훅 차단·실음성/실브라우저 미검증을 구분한다. code-summary/traceability/source-manifest/verification-results를 갱신하고 이전 이력과 Build and Test의 append-only Loop-Back Log를 보존한 뒤 현재 시도 독립 검토로 넘긴다. [전체 U16]

### RG1 Acceptance Matrix

| ID | 입력/조건 | 관측 기준 |
|---|---|---|
| RG1.1 | 현재 녹음 안내 COMPLETED→현재 참여 수락 | 첫 실제 요청에 안내 전달 사실·재시작 인사/자기소개/참여 질문 반복 금지, 기존1회 요청 |
| RG1.2 | 완료 누락/STARTED/실패·옛 세션·연결/중복 수락 | 안내 완료 맥락/일반 출력 권한을 만들지 않음; 중복 첫 요청0 |
| RG1.3 | 아이 “응, 안녕” 뒤 모델 “안녕, 나는 무중이야…” | 불필요한 재시작 후보의 음성0프레임; 전사/음성 동시 폐기 |
| RG1.4 | 모델이 승인 시작 대본/참여 질문을 다시 읽고 JEV는 ALLOW | 실제 송신 전 중복 차단이 유지되고 완료/인용 성공으로 기록하지 않음 |
| RG1.5 | 아이의 첫 실질 질문/경험·정정 | 그 말에 이어가는 정상 응답은 통과; 새 질문/인사를 강제하지 않음 |
| RG1.6 | 아이가 “너 누구야?”/이름을 명시적으로 질문하거나 인사를 인용 | 필요한 답/인용까지 무조건 막지 않음; 재시작 인사와 구분하는 반례 |
| RG1.7 | 끊김/끼어들기/종료/재접속 후 구 후보/늦은 ALLOW | 인사/안내 또는 일반 음성을 다시 살리지 않음 |
| RG1.8 | PRACTICE·이후 정상 대화/CONTINUE | 자유대화 첫 인사 제약을 다른 모드/모든 턴에 무차별 적용하지 않음 |

재시작 감지는 검증 가능한 범위의 명확한 표현/현재 목적과 상태를 다룬다. 임의의 모든 의미적 바꿔 말하기를 탐지하거나 실제 GPT-Live가 항상 지시를 지킨다고 주장하지 않는다. 실제 모델/실기기 품질은 별도 증거로 남기며 자동 대역 성공과 구분한다. 운용 가능한 음성 제작/청취/등록 미준비도 별도 미완료다.

### Repair Verification

backend 작업 디렉터리의 현재 실제 파일과 추가 파일을 정확히 지정한다. 알려진 실패와 수정 뒤 결과를 구분한다.

```bash
./gradlew --no-daemon :realtime-activity:test --tests kr.mujoong.realtime.session.ParticipationLateItemServiceGuardrailTest --tests kr.mujoong.realtime.session.ParticipationServiceGuardrailTest -Pmujoong.test.db=docker
./gradlew --no-daemon :realtime-activity:test --tests kr.mujoong.realtime.session.GreetingHandoffServiceGuardrailTest --tests kr.mujoong.realtime.dialog.InstructionTemplatesServiceGuardrailTest --tests kr.mujoong.realtime.voice.ProviderRequestServiceGuardrailTest -Pmujoong.test.db=docker
```

GreetingHandoffServiceGuardrailTest는 추가할 시험 파일명이며 해당 층 구현 뒤 작성한다. 없는 필터/시험0개를 성공으로 처리하지 않는다. 나머지 기존 명령과 범위도 유지한다.

## Startup Initialization Budget

### Source and Decision

2026-10-21 사용자 추가 지시: “일반 요청 2초는 유지하고, 시작 시 초기화에 별도 제한 시간 적용”.
이는 이번 전체 검증에서 확인한 Neo4j 시작 준비 실패의 복구 범위다. 일반 graph-budget=2s, JDBC 업무 트랜잭션 예산, GPT-Live/제브/Luna 시간 설정은 그대로 유지한다. 앞선 “시간 설정 변경 없음” 및 “Graph 예산 유지”는 이 사용자 지시 이전의 이력이며, 새 초기화 전용 설정만 이 절에 따라 추가한다. 처음18단계 및 완료된 Repair1–7을 재구현하지 않는다.

관측 근거는 단독 GraphWorkflowIT에서 begin201/657ms, 제약 준비200/1544ms 및200/180ms, commit200/107ms, 다음begin201/26ms 이후 첫 EXPLAIN(BASE_VERSION)이 응답 없이 HttpTimeoutException/2015ms가 된 안전 HTTP 진단이다. 단독2개(Core Neo4jIT/GraphWorkflowIT)와 기능 범위에서도 재현됐다. 첫 계획 준비 경로의2초 초과는 확인했지만 JVM/CPU/GC 기여도는 미확정이다. 데이터 손상·인증 실패나 참여/RG1 원인으로 추정하지 않는다.

### Implementation and Impact

운영 소유에 CoreTimingProperties.java, core-api application.yml, KnowledgeConfiguration.java, Neo4jChildKnowledgeGraph.java 및 필요한 기존 호출자/시험·contracts/timing.md·설정 대조를 추가한다. 새 키는 mujoong.timing.knowledge.graph-initialization-budget, 시작 준비 작업 기본값은10s로 한다. 이 값은 기존 관측의 제약/계획 준비 여러 단계를 담기 위한 초기 기본값이며 실제 반복 성공으로 검증한다. 일반 graph-budget=2s를 전역으로 늘리거나 공유 mutable budget을 임시 변경하지 않는다.

초기화는 명시적인 한정 deadline으로 begin/제약/commit/EXPLAIN을 처리하고 각 요청은 남은 시간보다 길게 기다리지 않는다. 종료/실패 정리는 기존 원자성과 유한 대기 조건을 보존하며, 정리의 별도 상한이 필요하면 전체 초기화 시간과 분리해 계약·시험·결과에 명시한다. 초기화 실패/중단/시간 초과이면 schemaReady를 열지 않고 시작 실패로 전파한다. 무한 재시도·자동 재시도·임의 sleep·준비 전 ready 우회는 추가하지 않는다. 운영 읽기·쓰기·교체·commit 요청 및 ChildKnowledgeService 업무2초 경계는 그대로다.

예상 노력30–60분+좁은/전체 회귀 시간, 추가 유료 공급자 비용0, 위험 중간(초기화 deadline과 자원 정리/준비 상태 분리). 운영/수동 스택 재시작·배포·DB 삭제·migration 변경은 없다. 소스/범위 분모는 기존207claim/37운영 파일에서 필요한 경로를 더하며 축소하지 않는다.

### Ordered Steps

- [x] Repair 9 — 설정 층 구현 후 즉시 시험. 초기화 전용 양수 Duration 설정과 기본10s를 Core 설정·contracts/timing.md에 함께 기록한다. 기존 일반2s와 서버 전용 키 소유를 유지하고 설정 바인딩/기본값/무효값/일반 값 불변 등 필요한5–8개 경계를 시험한다. 프런트에서 소비하지 않는 서버 설정을 프런트 시간 값에 복제하지 않는다. [사용자 초기화 지시, NFR3/NFR5/NFR6]
- [x] Repair 10 — 초기화 업무 층 구현. 운영 구성에서 별도 budget을 주입하고 prepareSchema 전체 준비 deadline/요청별 남은 시간과 성공 이후 schemaReady를 분리한다. 일반 요청/원자적 교체/CAS/digest/자녀 범위/rollback·실패 후 정리를 보존한다. 기존3쿼리 EXPLAIN과 무쓰기 의미를 유지하며 일반2s를 변경하지 않는다. [사용자 초기화 지시]
- [x] Repair 11 — 초기화 층 시험 작성·실행. 정상 준비,2초 초과 준비의 새 budget 안 성공, 초기화 전용 시한 실패/ready 미개방, 준비 단계 누적 마감, 일반 요청2초 유지, 중단/실패 정리, 중복 준비와 데이터 무쓰기/CAS 보존 등 컴포넌트5–8개+핵심 HTTP/실제 Neo4j 통합을 확인한다. 기존 단언/예산을 낮추지 않고 대역과 실제DB 증거를 구분한다.
- [x] Repair 12 — 같은 실제 cold 시작 경로의 Neo4jIT/GraphWorkflowIT 및 기존 HTTP 회귀를 검증한다. 원래 실패를 보존하고 새 결과와 준비/일반 요청 시간을 따로 남긴다. 그 뒤 Core/Realtime/contract 기능 범위 및 변경쪽 전체 check·대조/ArchUnit을 필요한 순서로 수행한다. 과거 소스/취소/부분 실행을 최신 전체 성공으로 바꾸지 않는다.
- [x] Repair 13 — Step5/범위/manifest/추적을 갱신하고 동결한다. Repair8의 미완료 상태는 실제 새 검증 결과에 따라 갱신하며 기존 전체 실패·진단·Loop-Back Log는 보존한다. 전체 verify/실제 음성/실브라우저는 별도 미검증으로 유지한다. 소스 동결 후 독립 검토와 부모의 정상 검증 경로로 넘긴다.


## Testing Contract

```json
{
  "version": 1,
  "methodology": "test-after",
  "source": "team",
  "ordering": "시험할 수 있는 층을 하나 구현하면, 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 넘어간다.",
  "scope": "classic",
  "test_strategy": "standard",
  "project_type": "brownfield",
  "applicable_notes": [
    {
      "layer": "org",
      "text": "We treat tests as a first-class deliverable in every Bolt. The specific\nmethodology (TDD, BDD, ATDD, or classic test-after) is affirmed at\npractices-discovery and recorded in `team.md` under this heading with explicit\n`Methodology` and `Ordering` fields; Code Generation resolves those fields\nindependently from coverage, tooling, and scope notes.\n\nWhen no posture has been affirmed, our default per scope is:\n- **Methodology**: test-after\n- **Ordering**: implement each applicable testable layer, then write and run\n  that layer's tests.\n- `mvp`, `enterprise`, `feature`, `infra`, `classic` add an 80% line-coverage\n  floor and CI execution before merge.\n- `bugfix`, `security-patch` add a targeted regression for the specific\n  bug/vulnerability and require the existing suite to remain green.\n- `express` uses the Minimal strategy: requirement-driven unit tests (one per\n  requirement, with a happy-path floor per component); existing tests remain\n  green.\n- `poc`, `refactor`, `workshop` add no extra new-test floor and require the\n  existing suite to remain green.\n\nThe active `Test Strategy` still applies in every scope and determines test\nvolume/types. Scope floors are additive; they never reduce or replace the\nselected strategy.\n\nBuild and Test verifies defined coverage floors and affirmed quality targets;\nthey may not be weakened to make a step pass.\n\nAffirm a stricter posture in `team.md` if the team commits to one."
    },
    {
      "layer": "team",
      "text": "- **Methodology**: test-after\n- **Ordering**: 시험할 수 있는 층을 하나 구현하면, 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 넘어간다.\n- 계약에 닿는 변경도 같은 순서를 따른다(Q3-A). 다만 설계 문서 변경이 계약에 닿으면 같은 작업에서 `contracts/`도 함께 고친다(`discovered-rules.md` R-M10).\n- 커버리지 하한(줄 기준만, 분기 · 함수 하한 없음):\n  - 모듈 전체 줄 80%를 CI가 막는다. 백엔드 JaCoCo는 `contract-model` · `core-api` · `realtime-activity`(`test-support`는 제외), 프런트 Vitest v8은 `src/**/*.{ts,tsx}`(`*.d.ts`만 제외).\n  - 범위 파일이 있는 기능에는 기능 범위 줄 80%를 적용한다(`backend/coverage-scope/<기능>.txt` · `frontend/coverage-scope/<기능>.json`, 시험 목록 `<기능>.tests`). 현재 CI가 막는 범위는 프런트 `practice-legacy` · `web-push`이며, 백엔드 범위 파일은 로컬 · 검토 근거다.\n  - `tools/`에는 커버리지 하한이 없다(시험 지원 코드, J107).\n  - 범위 측정은 빈 목록 · 없는 파일이면 실패한다(J137). 범위 파일을 새로 쓰면 이 방식을 그대로 따른다.\n  - `classic` 범위의 줄 80% 하한 · 병합 전 CI 실행과 같다.\n- 하한 · 기존 단언 · 대조 시험 · ArchUnit 시험은 시험을 통과시키려고 낮추거나 끄거나 건너뛰게 하지 않는다. 모자라면 시험을 더하거나 차이를 보고한다.\n- 시험 종류:\n  - 단위: JUnit 5 · AssertJ · Mockito(백엔드), Vitest + Testing Library + jsdom(프런트), Vitest(tools).\n  - 통합: Spring Boot 통합 시험 + Testcontainers PostgreSQL(`-Pmujoong.test.db=docker`), 상대 앱은 스텁.\n  - 계약 대조: `ContractFilesTest` · `ErrorCodeTest` · `ConfigKeyContractTest` · `TimeLiteralScanTest` · `OpenApiContracts` · `frontend/tests/u1/shared/timing.test.ts` · `SeedDictionaryFileTest`가 `contracts/`와 코드를 대조한다.\n  - 아키텍처: ArchUnit(`core/arch` · `realtime/arch`), ESLint `/parent` ↔ `/device` ↔ `shared` import 경계.\n  - 끝-끝: `bash scripts/verify.sh` C안(기기 시뮬레이터 + 가짜 공급자).\n  - 인프라: `infra-validate`(Compose 불변식 · `nginx -t` · shellcheck · 마이그레이션 expand 검사), 이미지 점검, 관문 음성 대조.\n- 시험 클래스 이름은 단위 `*Test`, 통합 `*IT`를 기본으로 한다. 백엔드 태그는 기능 이름(`practice-legacy` · `web-push` · `skeleton` 등), DB 시험은 `db`를 쓴다. 프런트·tools 시험은 소스 구조 폴더(`tests/parent/features/…` · `tests/shared/…`)에 둔다.\n- 관문의 한계: PR `ci-gate`는 changes · backend · backend-deps · frontend · frontend-deps · tools · secrets · infra-validate를 집계한다. 정적·단위·통합·적용 범위 커버리지·보안·인프라는 경로 조건에 따라 실행된다. 이미지는 관문 통과 뒤 main에서 실행되며, 끝-끝 `skeleton`은 main 전용 임시 비차단이다. 이번 작업은 Build and Test의 전체 `verify.sh`로 병합 전 끝-끝을 확인한다.\n- 시험 · CI · 검증에서는 가짜 공급자만 쓴다(ADR-002).\n- 결과 보고는 테스트 대역 · 합성 대화 · 실제 API · 실제 기기 성공을 따로 적고, 대역 성공을 실공급자 성공으로 표시하지 않는다(`discovered-rules.md` R-M11, Q10-A).\n- 이번 작업 기록의 시험 전략은 Standard다(컴포넌트당 5 ~ 8개 + 핵심 경계 통합 시험)."
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
  "input_sha256": "sha256:665dd09bf47887dda46a323f3ec4d9a3bec9d30c9bd22f0b892bce61f8fe2479",
  "contract_sha256": "sha256:231770ff8fc8e342a5163711626da88820010bbc0b413c96ec9fc94bb2241ce4"
}
```

