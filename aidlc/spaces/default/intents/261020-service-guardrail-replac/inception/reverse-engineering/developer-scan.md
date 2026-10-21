## Developer Code Scan Results

### Scan Coverage

- **Analyzed deeply**: 다음 23개 개별 파일의 내용과 책임을 읽었다. 모두 pre-scan snapshot의 backend/realtime-activity 범위 안이다.
- backend/realtime-activity/build.gradle.kts
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/DialogInstructions.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/InstructionTemplates.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/StopConfirmFlow.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderRequestFactory.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderPort.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/ChildContext.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/JoinFlow.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/ProviderSendQueue.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/GuardrailCoordinator.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/gate/OutputGate.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/content/LiveContentResources.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/guardrail/GuardrailRequestFactory.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/guardrail/GuardrailRequest.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/guardrail/GuardrailVerdict.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/guardrail/GuardrailProperties.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/guardrail/JevQuestion.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/guardrail/JevGuardrailClient.java
- backend/realtime-activity/src/main/resources/guardrail/policy-v2.json
- backend/realtime-activity/src/main/resources/application.yml
- backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/DialogInstructionsTest.java
- backend/realtime-activity/src/test/java/kr/mujoong/realtime/voice/ProviderRequestFactoryWireTest.java
- backend/realtime-activity/src/test/java/kr/mujoong/realtime/guardrail/GuardrailPolicyV2Test.java
- **Skimmed only**: SessionExecutor의 초기 지시·참여 후 첫 응답·출력 보류·입력 전사·봉인·검사 호출 구역; LiveContentRegistry; contracts/ai-io.md 2·3·10.12·10.13절; 연습 경계; infra·docs·tools의 구조. 기존 STALE store의 frontend·core-api·contract-model·음성 진단 파일 deep coverage는 shallow로 내렸다.
- **Left out**: .git; AI-DLC 자체 설치/기록의 애플리케이션 분석; node_modules; .gradle; build/dist/coverage 등 생성물; 캐시; 실제 아동 원음·전사·키·컨테이너 실행 상태.

### Packages Found

- realtime-activity — Spring Boot Java 앱 — 음성 세션, 참여 확인, 연습 연결, 출력 검수.
- contract-model / core-api / frontend / tools / infra — 기존 store의 배경 지식만 보존. 이번 deep 분석 대상이 아니다.
- 사용자 ZIP — TXT·JSON·HTML 6개 파일 — 자유대화 정책 후보 및 구현 계약. 앱 컴포넌트가 아니다.

### Build System

- **Type**: Gradle Kotlin DSL.
- **Config Files**: backend/realtime-activity/build.gradle.kts.
- **Build Dependencies**: realtime-activity → contract-model; 시험 → test-support. 기존 Spring Boot webmvc/websocket/JDBC/JPA/Flyway/actuator/validation 및 schema validator를 사용한다. 의존 버전 manifest 전체는 재검증하지 않았다.
- gptLiveTest·jevLiveTest·lunaReachTest는 명시 호출용 격리 Test 작업이다. 이번에는 호출하지 않았다.

### APIs Discovered

- GPT-Live WSS — ProviderRequestFactory — 송신 type 4개: session.start, session.input_audio.append, session.instructions.append, session.close.
- session.start.session.instructions는 DialogInstructions.role + JOIN_FIRST_QUESTION에서 나온다. 모델 gpt-live-1, PCM 24kHz, 아이 문맥은 이름·나이·관심사의 따옴표 안 데이터다(ProviderRequestFactory.java:40, 132, 175).
- JEV HTTP — JevGuardrailClient — POST /v1/systemone. 내부 요청/결과와 공급자 선 형식을 어댑터가 분리한다. 후보·익명 최근 3턴, 정책 7범주+PASS, FAIL/REVIEW는 재생하지 않는다.
- 전체 REST·AsyncAPI 수는 이번에 세지 않았다.

### Frameworks & Libraries

- Java 21 계열 / Spring Boot / JDK WebSocket·HttpClient / JsonCodec(Jackson) / JUnit·AssertJ.
- 기존 technology-stack.md의 버전은 보존한 역사적 값이며 이번 전체 버전 인증이 아니다.

### Test Coverage

- **Test Directories**: backend/realtime-activity/src/test/java/kr/mujoong/realtime/{dialog,voice,guardrail,session,gate,content}.
- **Test Frameworks**: JUnit 5, AssertJ, Spring 시험/대역. 이번에 깊게 읽은 시험은 DialogInstructionsTest, ProviderRequestFactoryWireTest, GuardrailPolicyV2Test다.
- **Coverage Config**: 기존 JaCoCo 및 80% 규칙 존재는 store/메모리 배경. 이번 커버리지 실행·측정 없음.
- 회귀의 초점: 지시문 정책·이스케이프·금지 필드·wire 4 type·null delegation_id·후속 지시 범위 닫기·연습 경계·안내/참여 상태.

### Code Quality Indicators

- **Linting**: 기존 Java Xlint 관례, 별도 formatter 없음(기존 기록 보존).
- **CI/CD**: 기존 .github/workflows 기록을 보존했으나 이번에 전체 재검증하지 않았다.
- **Documentation**: 타입·불변식 Javadoc과 contracts/ai-io.md가 공급자 경계를 설명한다. 모델 기본 지시와 JEV 정책이 별개라는 점은 구현에서 확인했다.

### Technical Debt Signals

1. 기본 지시가 Java 문자열이며 새 정책 버전·해시를 세션에 묶는 별도 프롬프트 자원/메타데이터 연결은 현재 조사에서 확인되지 않았다.
2. 후속 지시는 공급자 세션에 지속한다. InstructionTemplates.FIRST/REST_ACK_AND_RETURN은 새 자유대화 정책과 충돌할 수 있다(InstructionTemplates.java:37, 66).
3. JoinFlow.playJoinPrompt는 리소스 부재/송신 실패 시 onJoinPromptPlayed로 진행한다(JoinFlow.java:99). 실제 안내 전달 확인을 요구하는 패키지와 의미가 다르다.
4. 기존 JEV 7개 범주가 service_truth·autonomy·relationship_integrity·general_safety라는 새 제안 축의 전체 검출을 보장하지 않는다.
5. 연습 모드는 별도 PracticeInstruction 경계가 있으나 기본 sessionStart는 activity mode를 받지 않는다. 자유대화 전용 정책을 공통 시작 지시에 넣을 경우 연습 영향 검토가 필요하다.

### 입력 패키지 확인

- 경로: aidlc/spaces/default/knowledge/documents/무중_서비스용_가드레일_20261016.zip
- DocumentKB ID: 01a15818-3d3a-736f-abb0-a451fb39f3b0 (부모가 현재 intent에 연결).
- ZIP SHA256: 94c982939b5637ea1729a447932d2be7db7c6a5ae55db4c1146002a75e46e270.
- Python zipfile로 메모리에서 읽고 원본/추출물을 쓰지 않았다. MANIFEST 대상 5파일의 bytes·SHA256 모두 일치.
- live_service_instructions.txt, service_contract.json, START_HERE.txt, MANIFEST.json, VERIFICATION.json 본문과 HTML 섹션/라이선스 선언 유무를 확인했다. HTML 전체 문서의 모든 규칙을 별도 재검토하지 않았다.
- service-0.1은 NOT_DEPLOYED; service_contract.json은 IMPLEMENTATION_CONTRACT_NOT_EXECUTABLE_CONFIG다. JEV/기억/연습 구현을 제공하지 않는다.
- 참조 원본 화면설계 HTML과 v03_full.txt는 ZIP에 없다. 저장소의 v1.5j PDF/문구표는 확인했으나 source hash와 동일한 원본인지 인증하지 않았다. 라이선스/재배포 허락 선언은 찾지 못했다.

## Handoff Summary

- **Intent-relevant finding**: 가장 직접적인 교체점은 DialogInstructions.role → ProviderRequestFactory.sessionStart의 GPT-Live 기본 지시다. JEV 어댑터/정책과 별도다. 새 지시문 전체를 기존 기본 지시에 중복 연결하거나 service_contract.json을 API 본문으로 넣는 방식은 패키지 성격과 맞지 않는다.
- **Risks / follow-up**: 요구사항에서 프롬프트 교체만 할지 서버 안내·참여·종료·기억/결과 정책 갭도 포함할지 확정한다. 첫 안내의 실제 전달, 초기 20초 참여 창과 null 미정, 반복 방문, 원음 보관/삭제/재생 사실, 자유대화/연습 모드 분리, 후속 지시 충돌, 라이선스와 누락 근거 원본을 열린 항목으로 넘긴다. ZIP을 근거로 현재 보관/삭제/기기 기능을 구현 완료로 선언하지 않는다.
- 기존 수치 4분/6분·40초·30초는 application.yml과 맞는다. 타이머 시작/리셋/재생 제외 의미는 새 계약에서 UNRESOLVED이며 추가 제품 결정이 필요하다.
- OutputGate.inspect와 GuardrailCoordinator 검사표·세대 재확인 등 기존 fail-closed 경계는 보존한다. 새로운 정책 전 범위 준수와 모델 품질은 별도 인수·검증 문제다.
- 앱 수정·테스트·외부 AI·배포·수동 실행 스택 조작 없음. 정적 소스 사실과 패키지 제안 및 실행 증거를 구분했다.

## 게시 준비 정보

- Candidate: .aidlc-engine/codekb-stage-mujungi/ (정확히 canonical Markdown 9개).
- Fingerprint: c5577822c642b4b65e6840698ad8a961f2d42bc3.
- Check: VALID; partial, 23 analyzed paths, 1 component, 58 shallow paths; source fingerprint match.
- Compare: NARROWER. 이전 합성 음성 진단 경로/컴포넌트의 깊은 검증을 승계하지 않고 본문을 보존했으며 경로는 shallow로 내렸다. 정확한 tool coverage 목록은 부모에게 전달한다.
- 부모가 snapshot generation/source로 게시하며 이 링크에서는 공유 store 게시·link receipt·gate·workflow 상태 전이를 하지 않는다.

