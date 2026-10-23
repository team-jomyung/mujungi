# U16 서비스 정책 교체 구현 요약

> 최신 backend 전체 clean check는 통과했다(운영3모듈4,118개). 전체 verify.sh·U17 frontend/실브라우저 통합·실제 음성은 후속 미검증이다. 앞 절들의203개 파일/최초 검증과 Loop-back1의 실패는 이전 소스 이력이며, 최신216개 파일 결과는 끝의 Startup Initialization Budget 절 및 verification-results.md를 따른다.

## 변경 파일과 구현 범위

전체 앱 변경203개는 source-manifest.json에 파일 단위로 열거했다. 운영 Java35개, 시험/시험 지원151개, 런타임 자원6개, 기능 범위/시험 목록2개, 공개 계약/예제8개, migration 승인 안내1개다. 부모가 관리한 AI-DLC 기록·CodeKB·API 설명 문서는 앱 manifest에 넣지 않았다. frontend 앱 소스는 변경하지 않았다.

| 경계 | 주요 파일과 결과 |
|---|---|
| 정책 적재/요청 | PolicyBundle, ServicePolicyCatalog, service-policy 자원, ProviderRequestFactory/Connector, DialogInstructions/InstructionTemplates. UTF-8 앱 정책의 판·SHA를 시작 시 확인하고 모드/세션에 고정한다. 초기·후속 실제 공급자 요청에 연결한다. |
| 저장/원자성 | SessionGuardrailFacts/Repository, TransitionService, SessionTransitions, V8 사실 부분 인덱스. 같은 세션 행 잠금에서 statusVersion·facts revision·상태·outbox를 함께 갱신한다. |
| 안내/참여 | NoticeAssetCatalog, NoticeDelivery, JoinFlow, SessionClocks. 검증된 현재 안내의 실제 COMPLETED 이후에만 참여를 판정한다. 조기/오래된 입력은 차단하고 거절·불명확·두 참여 창을 구분한다. |
| 대화/종료 | ConversationPolicyState, StopConfirmFlow, StopIntentJudge, JudgmentPrompt. 모드 분리·거절 후 도움/휴식/단회 반응을 관리하고 실제 종료 질문 완료 후 현재 응답만 판정한다. |
| 출력/인사 | OutputAuthority, OutputGeneration, GuardrailCoordinator, EndingFlow. 후보의 정책/JEV 판·세대·목적·본문 해시를 귀속하고 검수 후와 프레임 전 현재성을 확인한다. |
| 조회/공개 계약 | GuardrailPresentation, GuardrailProjection, SessionViewQuery, SessionInternalController, Core LiveExtras/RealtimeSessionClient/SessionViewService. C1/C2 선택 객체를 중계하고 엄격한 enum·필수 nullable seq를 유지한다. |
| 별도 기술 회귀 | Neo4jChildKnowledgeGraph 및 GraphInitializationTechnicalRegressionServiceGuardrailTest, Neo4jIT, GraphWorkflowIT/GraphHttpDiagnostics. 전체 검증에서 발견한 기존 Graph 초기화의 세 HTTP 왕복 누적을 단일 HTTP 조건화 배치로 줄였다. |

## 핵심 결정과 요구 추적

앱 정책 service-0.1-app.1은 10,566 UTF-8 bytes이며 SHA256은 35ce5546aa40a0f2897f80e13afc26084e43bf0d2edfdc65fc6f80f669e32c04다. 원본 SHA256은 1d92f114af7b48cefd86ed3df03feea30cdecc6ac546b128c236427f3d7fe5b7이다. 서버가 완료한 안내/참여 질문을 모델이 중복하지 않는 문장 편차와 이유는 contracts/service-guardrail-policy-deviations.md에 기록했다. 사용자 원본 폴더는 런타임 입력이 아니다.

실제 승인 말소리가 준비되지 않아 운영 notice-assets.json은 비어 있다. placeholder·메타데이터 누락·본문/정책 불일치·잘못된 PCM은 재생 성공으로 간주하지 않고 일반 대화를 차단한다. 시험의 SPOKEN_VERIFIED 합성 자원 주입은 운영 청취 승인이나 실제 공급자 성공을 뜻하지 않는다.

후보의 session/mode/device·provider generation/정책·JEV 판/purpose/body SHA를 고정하고 검수 후 현재성을 다시 확인한다. 자유대화 REST_ACK는 현재 한 후보만 허용하며 기존 JEV 범주·확률/동점·800ms·음성/전사 대응을 유지한다. 종료 시 즉시 음소거는 추가 발화를 만들지 않는다. CLOSING 자체를 숨기지 않고 현재 승인 인사 STARTED 이후에만 PLAYING이다. 완료/중단/실패를 구분하며 연결 해제·기한·actor 제거/재시작 후 PLAYING은 복원하지 않는다.

종료 질문은 실제 재생 완료 이후 응답 대기를 연다. 전달 실패는 현재 확인을 취소하고 청취를 보존한다. J1/J6 실제 요청 조립과 판정결과 주입 상태효과 시험을 분리했다(R-01). 새 정보 요구의 의미 식별은 보수적인 참조를 유지하며 단순 문자열/턴 증가만으로 새 의미를 확정하지 않는다.

안정된 facts/statusVersion을 같은 전이로 저장한다. actor에만 있는 PENDING/PLAYING은 현재 상태·연결·기한이 맞을 때만 조회한다. 불일치·손상·부재 또는 Core 중계 실패는 null/UNKNOWN으로 처리하고 캐시나 사실 복사본으로 성공을 만들지 않는다. 종료 근거 seq는 확실하고 가시적인 동일 세션의 최종 CHILD 행만 인용한다.

V8은 기존 이벤트 표의 사실 revision 조회용 비유일 부분 인덱스만 추가한다. 기존 migration·표·권한·앱 쓰기 형식을 바꾸지 않아 이전 앱 쓰기와 공존한다. 기존65개 표·V1–V7 이력·부분 유일 인덱스2개·재적용0건·연습 세션 보존 단언을 유지하고 V8 확인을 추가했다. 실제 운영 migration 실행/승인을 수행한 기록은 만들지 않았다.

traceability.json은 할당된40 AC·25 BR·20 상세 NFR, 합계85개를 열거한다. 83개 OK는 존재하는 구현/시험 파일을 가리킨다. NFR3.2의 최종 전체 verify.sh는 Build and Test로, NFR4.3의 실제 승인 음성 생성·청취·등록은 후속으로 명시한다. NFR6.1에는 저장/삭제·외부 호출 보존의 추가 기술 회귀 시험을 연결했으며 실제 공급자 요청/모드 경계는 AC1.1/AC2.2 및 관련 BR의 요청 시험이 함께 검증한다.

## 기존 시험 변경과 계획 밖 기술 회귀

승인된 FR2/BR2가 바꾼 ‘누락 안내=완료·조기 참여 수락’ 기대는 현재 안내 실제 완료 뒤 수락·누락/실패 보류로 강화했다. 거절 시 반복 대안도 직접 거절·검증된 인사로 바꿨다. 기존 준비 fixture는 합성 검증 안내를 실제 완료하고 현재 안내의 session/contentVersion/playback/seq를 캡처한다. 기존 모델 후보 검수·음성 대응·개수·폐기·로그 비밀0건·시간/계약/ArchUnit 단언을 유지했다. API sinceSeq=0 조회에는 새 안내 행까지 포함하는3행/nextSeq3 경계로 강화했다. 실제 wire whitelist는 그대로이며 내부 정책 인자만 정확한 메서드 서명으로 확인한다.

원래 U16 범위를 벗어난 별도 기술 회귀는 전체 검증에서 반복한 GraphWorkflowIT 초기화 실패다. 실제 HTTP201/200과 ledger.successful의 TransactionTimedOutException, 세 HTTP1,453/294/269ms, 풀 대기0·시계 변경0으로 기존 JDBC2초 안의 누적을 확인했다. 첫 거대 단일 Cypher와 slotted 시도는 첫 요청2,003ms에서 실패하여 통과로 기록하지 않았다.

최종 방식은 운영 KnowledgeConfiguration이 호출하는 prepareSchema에서 제약 commit 후 실제 세 정적 쿼리를 EXPLAIN으로 각각 준비하고, 실제 교체는 같은 세 문장을 같은 명시적 트랜잭션의 한 HTTP 요청으로 보낸다. 준비는 사용자 데이터 없는 합성 파라미터를 사용하고 실패를 시작 실패로 전파한다. 성공 전에 schemaReady를 열거나 자동 재시도하지 않는다. 정확한 쿼리 일치·준비1회·실패 후 ready 미개방·합성 child/version 쓰기0을 wire와 실제5.26.31로 검증했다. cold 계획 준비는1,215/406/299ms였고, 이후 초기화763ms/replaceBase582ms/commit30ms로 관측됐다. 삭제/재적재 문장 각각 version+digest MATCH를 통과해야 하므로 충돌 뒤 caller commit에도 부분 쓰기는 없다. 자녀 범위·generated/다른 자녀 보존·멱등·rollback·CAS·악성 문자열 파라미터 분리를 실제 Neo4j 및 로컬 HTTP로 검증했다. 자동 재시도/자동 commit·외부 AI 호출·시간 증가는 추가하지 않았다. 실제 제품 준비 경로 외에 시험만의 워밍업을 추가하지 않았다. 개별 HTTP와 JDBC의 기존2초 설정을 전체 초기화 wall-time 보장으로 표현하지 않는다. 기능 범위는 실제 변경35개 운영 클래스 전체로 확장했다. 승인된 계획 본문/시험 지시서와 기존80% 하한은 변경하지 않았다.

## 시험과 커버리지

승인된 test-after 층별 순서를 적용했다. 상세 실행 명령·실패/수정·취소/재실행 및 증거 구분은 baseline-results.md와 verification-results.md에 남겼다. 최초 기준선은 앱 변경 전에 통과했다. 기능 목록143개는 추적 자료이고 실제 runner는 includeTags=service-guardrail로 선택한다. 기존 scopedClassPatterns가35개 실제 운영 소스와 내부 클래스를 읽으며 빈 목록/없는 파일은 실패한다. 대형 기존 클래스의 일부 메서드만 제외하지 않았다.

| 최종 기능 범위 | 시험 | LINE covered/missed | 결과 |
|---|---:|---:|---|
| contract-model | 8 | 19/0 (100%) | PASS |
| core-api | 196 | 351/18 (95.12%) | PASS |
| realtime-activity | 1,095 | 4,122/316 (92.88%) | PASS |

기능 합계1,299개는 실패/오류/skip0이다. 신규 정책/조회157개·별도 기술 회귀 신규11개·기존 관련 회귀1,131개를 구별한다. 신규 기술 회귀는 로컬 HTTP10개와 실제 Neo4j 무쓰기 준비1개이며 기존 Neo4j10개/Workflow1개도 함께 보존했다. 최종 기능 Core 확대에서도 초기화562ms, 실제 replaceBase491ms로 기존2초를 유지했다.

최종 Construction Verification Command는 저장소 루트에서 다음을 그대로 실행했으며 종료0이었다.

```sh
(cd backend && ./gradlew --no-daemon clean check -Pmujoong.test.db=docker) && (cd frontend && npm run lint && npx vitest run tests --coverage && npm run build)
```

| 최종 전체 검증 | 시험 | LINE covered/total | 결과 |
|---|---:|---:|---|
| contract-model | 330 | 1,546/1,570 (98.47%) | PASS |
| core-api | 1,745 | 10,100/10,496 (96.23%) | PASS |
| realtime-activity | 2,012 | 14,879/15,867 (93.77%) | PASS |
| frontend | 998 (75 files) | 3,455/3,506 (98.54%) | lint/시험/build PASS |

운영3모듈4,087개는 실패/오류/skip0이다. test-support31개는 실패/오류0이며 기존 LOCAL 전용3개가 Docker 선택에 따라 조건부 skip됐다. backend clean check는8m19s, frontend Vitest는61.06s였다. 전체 module/기능의 줄80%와 계약·ArchUnit 관문을 유지했다. 최종 전체 Graph도 초기화 true/전체672ms, cold 계획1,264/493/300ms, replaceBase588ms/commit49ms, ledger 예외0·풀 대기0·시계 변경false로 통과했다.

증거는 각 backend 모듈 build/test-results/test 및 build/reports/jacoco/test/jacocoTestReport.xml, frontend/coverage와 위 명령 출력이다. 최종 clean check가 기능 XML을 모듈 전체 보고서로 다시 생성했으므로 기능 수치는 각 기능 명령 완료 직후 읽어 verification-results.md에 보존했다. 최종 실행 시작/종료203개 앱 파일 내용/경로 지문 SHA256은 모두 b1d55ec3255b220c0dc563cba69e8f3676ffc4ffacc3f402e6ba2d24a03229cf로 일치했다. 이후 앱 변경은 없고 git diff --check -- backend contracts infra도 통과했다.

## 남은 범위와 인계

실제 말소리 생성·청취 승인·새 contentVersion/정책 판/본문 SHA/검수 메타데이터 등록은 후속이다. 준비 전 운영의 빈 카탈로그는 실패 보류하며 합성 fixture를 운영 승인 자산으로 취급하지 않는다. 실제 유료 모델 호출·실기기·실아동·원격 실행/배포·운영 migration·main 병합은 수행하지 않았다. 기존 mujoong-manual 스택도 변경하지 않았다.

U17은 C1/C2 선택 guardrail 객체와 예제를 사용해 현재 상태를 표시하고, CLOSING과 인사 PLAYING을 구별하며 기존 엄격한 클라이언트와 서버 전환을 함께 맞춰야 한다. actor 부재/재시작의 UNKNOWN·Core fallback null과 확실한 CHILD seq 경계를 유지한다. 본 U16에서 frontend 화면 구현을 완료했다고 주장하지 않는다.

최종 Build and Test의 전체 bash scripts/verify.sh와 실제 승인 음성 조건은 Deferred로 인계한다. 완성된 Step5 세 자료 이후의 외부 설계 검토·수정·수명주기/단위 완료 명령은 부모 orchestrator가 이어서 수행한다.

## Loop-back 1 — 참여 귀속과 녹음 인사 연결

기존 녹음 방식을 유지한다. 새 ParticipationInputBoundary는 quiet로 입력 묶음이 비워져도 안내 전 legacy item 식별과 입력 시간선 귀속을 보존한다. 실제 wire의 null itemId에는 유효 start_ms/end_ms가 필요하며, 완료 순간 수신된 세션 PCM 시간선 이상이고 관측한 과거 입력 끝보다 뒤에서 시작한 전사만 현재 참여 후보가 된다. 안전 편지함의 완료 표식이 이미 수신된 일반 전사를 앞지르는 경우에도 원문 없는 reader atomic watermark가 경계를 고정한다. 시간선0에서 관측한 입력이 없는 정상 첫 대답과 이미 관측한 과거0ms 꼬리를 구분한다. 식별·시간 둘 다 없는 입력은 현재 수락으로 추정하지 않는다. 내부 InputTranscriptDone 대역은 공급자 wire 사건이 아니다.

현재 안내 완료 뒤 기기 연결이 교체되면 옛 NoticeDelivery는 새 연결의 참여 근거가 될 수 없다. 공급자 연결은 기존처럼 세션 실행기 bootstrap에서 한 번 열리며, 기기 재연결이 공급자를 바꾸지 않는다. 새로운 공급자 재연결/원음·원문 영속·DB/외부 API는 추가하지 않았다. 수락/terminal 정리에서 최소 상태를 해제한다.

RG1은 기존 FIRST 요청 하나에 현재 검증 안내 녹음의 실제 완료 사실과 시작 인사·자기소개·안내/참여 질문 반복 금지를 명시한다. 아이 원문을 추가 복사하지 않고 질문·경험·정정을 먼저 따른다. CONTINUE 및 PRACTICE는 기존 적용 경계를 유지한다.

GreetingHandoff는 현재 첫 일반 출력의 명확한 재시작 인사·자기소개, 등록 안내 전체 또는 마지막 참여 질문의 반복을 후보의 음성/전사 전체와 함께 폐기한다. inspect/원문 생략/JEV 앞과 송신 직전에 검사하므로 ALLOW와 승인 원문도 우회하지 못한다. 교정·재생성·추가 AI 호출은 발행하지 않고 기존 폐기/듣기 흐름을 사용한다. 단순 후보 수신/큐 진입/가짜 완료는 첫 응답 완료가 아니다. 실제 프레임 송신, 현재 세대·연결·재생 ID 및 기존 playbackExpiry 안의 COMPLETED가 모두 맞을 때만 첫 구간 제약을 해제한다. FAILED/STOPPED/만료/종료·교체 뒤 늦은 확인은 제약을 풀지 않는다. 명시적 정체/이름/인사 요청과 인사 인용은 구분한다. 유한 표현 검사이며 모든 의미적 바꿔 말하기나 실제 모델의 항상 준수를 보장하지 않는다.

총 claim은 기존203개를 보존한207개다(새 운영 helper2개, 기존 B&T 참여 회귀와 새 RG1 시험2개 추가). 운영 scope는 기존35개 전체+helper2개=37개이며 시험 목록은143개+2개=145개다. 기존 OutputGate 구간 분리 시험의 인사 후보2개는 일반 수용 후보로 바꿔 새 RG1과 역할을 분리했고 출력2개/승인행2개/빈폐기0 단언과800ms는 유지했다. GenerationSendExecutorTest의 참여 전사 대역은 실제 nullable-ID wire처럼 timestamp를 제공하도록 보완했으며 기존 상태 단언은 유지했다. localhost ProviderClient IT는 null ID/시간선 디코딩→실행기 참여 경계와 강화된 실제 FIRST 요청 형식을 검증한다.

traceability는 기존85행을 보존하고 FR31개·상위 NFR6개·RG1을 직접 연결한123행이다. OK target은 존재하는 단일 workspace 상대 구현/시험 파일이다. 전체 verify.sh 미검증(NFR3.2)과 실제 음성 제작·청취·등록 미완료(NFR4.3)는 Deferred다. 전체 검증/호환 회귀의 현재 GAP을 자동 시험 합격으로 승격하지 않는다. 최신 실행 결과·실패/취소 및 소스 지문은 verification-results.md에 별도 기록한다.

전체 회귀 후 기존 원음/동의·지연 관측 시험에서도 첫 모델 후보가 인사로 설정된4개가 발견됐다. SessionVoiceIT1개·SessionVoiceTest2개·DeviceGatewayIT1개는 모델 후보만 ‘네 이야기를 듣고 있어’로 바꿨고, PCM/byte 수·동의·종료·CLOCK_OFFSET_MISSING/offset/RTT 단언을 보존했다. session/gate/web/observability의 첫 인사 출력에 한정해 대조했으며 순수 버퍼/스냅샷 자료와 참여 전 차단·RG1 반례는 변경하지 않았다. 새 연결의 늦은 ALLOW 시험은 기존 연결뿐 아니라 실제 교체 목적지의 출력/프레임도0임을 검사한다.

최신 정확 실패필터5개와 RG1 8개는 총13개 종료0/1m35s였다. 전체에서 나온 Lifecycle teardown FK 실패는 단독에서 재현되지 않았으며 해결 확정이 아니다. Core의 Neo4jIT/GraphWorkflowIT는 단독에서도 실패했다. GraphWorkflow의 기존 안전 HTTP 관측기를 실패 시에도 출력하고 같은 예외를 다시 던지도록 시험 진단만 보강했다. 첫 EXPLAIN(BASE_VERSION)이 HttpTimeoutException/2015ms, HTTP status0으로 기존2초 예산을 넘었다. 운영 Graph 구현/예산/CAS·시험 단언은 변경하지 않았다. 이 지속 실패 때문에 NFR3/NFR3.1 및 NFR6/NFR6.1은 GAP으로 남긴다(117 OK/4 GAP/2 Deferred). NFR3.2 전체 verify와 NFR4.3 실제 음성은 미완료다. Repair8의 전체 관문은 완료하지 않았으며 독립 검토/생명주기 처리는 부모가 이어간다.

최신 기능 검증에서 Realtime은1,112개·실패/오류/skip0, LINE4,229/4,557=92.80%, 종료0/7m48s였다. contract-model은8개·실패/오류/skip0, LINE19/19=100%, 종료0/42s였다. 두 모듈 모두 기존 기능80% verification task를 통과했다. 중첩된 정확 필터75개/13개를 여기에 합산하지 않는다. 전체 backend/계약/ArchUnit의 최신 통과를 주장하지 않는다.

최종207개 claim의 경로/내용 listing SHA256은 `2133a7be2d2ef072e40af13ad78fa069a87007a5cb140ae2adfdb6a0ca7fccae`다. scope37개·시험목록145개는 고유하며 모두 claim에 포함되고 실파일이 존재한다. 추적123행의 ID 집합이 upstream_ids와 일치하고117개 OK의 단일 target도 모두 존재한다. 이후 앱/시험 변경 없이 Step5 자료만 마무리한다. 실제0ms 정상 시작은 기존 통합 회귀로 검증했고, 과거0ms 꼬리의 별도 전용 시험을 추가했다고 표시하지 않는다.

Core 기능 명령은 종료1/4m10s,186개 보고 중184개 통과·2개 실패·오류/skip0이었다. Neo4jIT BeforeAll 실패는 기존11개 메서드 대신 initializationError1건으로 보고돼 원래196개 전체 통과가 아니다. GraphWorkflowIT도 첫 EXPLAIN에서 HttpTimeoutException/2,008ms로 실패했다. Core 최신 기능 coverage는 test 실패로 미실행이고 XML report가 없어 이전 수치를 재사용하지 않는다. Realtime/contract 기능 PASS와 이 Core 실패, 기존 전체 실패는 구분한다.

사용자가 다음 복구로 일반 요청2초를 유지하고 시작 초기화에 별도 제한 시간을 적용하도록 선택했다. 본 지문/검증은 그 변경 전 기록으로 동결한다. 새 brief/Testing Contract에 따른 다음 작업은 부모가 인계하며, 현재 자료는 전체 검증 미완료 상태다.

## Startup Initialization Budget — 사용자 선택에 따른 추가 복구

일반 요청2초는 유지하고 시작 준비에만 `mujoong.timing.knowledge.graph-initialization-budget`을 추가했다. 기본10초는 초기 구현값이다. Core 설정 레코드의 양수 검증/application.yml/timing 계약을 함께 맞췄고 프런트에는 서버 전용 키를 복제하지 않았다. 운영 KnowledgeConfiguration에서 두 budget과 기존 MujoongClock을 별도로 주입하며 공유 budget을 바꾸었다 복원하지 않는다.

Neo4jChildKnowledgeGraph.prepareSchema는 제약/commit/세 쿼리 EXPLAIN/계획 트랜잭션 종료에 하나의 전체 deadline을 사용한다. 각 HTTP는 남은 시간만 기다리고 최종 마감 검사까지 성공한 뒤 schemaReady를 연다. 실패 시 알려진 미종료 트랜잭션만 일반 budget을 별도 정리 전체 상한으로 사용한다(기본 최대2초 추가, 준비10초+정리2초의 네트워크 대기 한도). 정리 실패는 원래 실패에 suppressed로 연결하며 중단 플래그를 복구한다. 무쓰기 EXPLAIN·기존 일반 HTTP/연결/JDBC2초·세 문장 단일 교체/CAS·자녀 범위·생성 seed·rollback을 보존하고 자동 재시도를 추가하지 않았다. 기존 생성자/운영 구성 호출자는 새 인자로 함께 이관했다.

설정층27개 PASS 뒤 업무층을 구현했다. 첫 좁은24개 중 새 빈 조회 대역이 commit 필드까지 제거한1개 fixture 실패를 보정하고 단언을 보존했다. 최신 설정/업무/기존 HTTP/구성/시간 대조58개 PASS, 실제 cold Neo4j/후속 합성연결22개 PASS다. 새 업무8개는 실제localhost2.3초 계획 준비 허용과 이후 일반2초 timeout, 누적/정확 마감의 ready 미개방, 실패/중단·유한 정리/원래 실패 보존, 모든 일반 요청2초를 검사한다. 실제 cold EXPLAIN1,155/422/295ms, 업무 initializeChild609ms·replaceBase494ms·commit24ms, ledger 예외0·풀 대기0·시계 변경false다. 실제 모델/기기/아동 성공은 아니다.

기존207claim/37scope/145시험목록을 보존해216claim/39scope/150시험목록으로 확장했다. 새 운영 scope는 CoreTimingProperties와 KnowledgeConfiguration이며 기존35+2 helper 분모를 축소하지 않았다. 앱/시험 동결 지문은 `7a6aaae11d59cf87f32f711dd1df70dbb20c3a48b93df8f592a8c1d71abf41c4`다. 상세 명령과 변경 전 실패·fixture 수정·훅 정상 복구는 verification-results.md에 보존한다.

확대한 Core 기능237개는 종료0/2m23s, LINE484covered/16missed=96.80%였다. 동일 소스의 Realtime 기능1,112개/92.80%와 contract8개/100% 증거를 유지해 기능 합계1,357개·실패/오류/skip0이다. 기능 및 좁은 회귀의 겹치는 사례를 중복 합산하지 않는다.

소스 동결 뒤 `./gradlew --no-daemon clean check -Pmujoong.test.db=docker`를 backend에서1회 실행했다. 종료0/8m26s, 최신 모듈 전체 결과는 다음과 같다.

| 모듈 전체 | 시험 | LINE covered/missed | 결과 |
|---|---:|---|---|
| contract-model | 330 | 1,547/23 (98.54%) | PASS |
| core-api | 1,759 | 10,134/393 (96.27%) | PASS |
| realtime-activity | 2,029 | 14,984/1,002 (93.73%) | PASS |

운영3모듈4,118개는 실패/오류/skip0이다. test-support31개는 실패/오류0이며 기존LOCAL3개가 Docker 선택으로 조건부 skip됐다. 모듈 줄80%·계약/ArchUnit·실호출 격리 검사는 그대로 통과했다. 전체 cold EXPLAIN HTTP200/1,217·410·225ms, 업무 initializeChild543ms·replaceBase476ms·commit24ms, ledger 실패0·풀 대기0·시계 변경false였다. 이전 Lifecycle teardown FK 실패는 이번 전체에서는 재현되지 않았지만 영구 원인을 고쳤다고 주장하지 않는다.

최종216claim 지문은 시작/종료 모두 `7a6aaae11d59cf87f32f711dd1df70dbb20c3a48b93df8f592a8c1d71abf41c4`로 일치한다. 추적123행은120 OK/0 GAP/3 Deferred다. NFR3.1/NFR6.1/NFR6은 최신전체 근거로 복구했고, 상위NFR3 및 NFR3.2는 전체verify 미검증, NFR4.3은 실제음성 제작/청취/등록 미완료로 유지한다. Repair8/9–13의 구현·허용된 검증·Step5 기록을 마무리해 동결하며 독립 검토/생명주기 및 후속 전체검증은 부모가 담당한다. frontend/프레임워크/운영스택 변경·유료 호출·실기기/아동·배포/commit/push는 수행하지 않았다.
