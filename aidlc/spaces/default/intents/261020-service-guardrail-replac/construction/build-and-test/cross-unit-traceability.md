# 유닛 간 최종 요구 추적

## 현재 판정 — 2026-10-21T06:53:34Z

FAIL. FR31·상위 NFR6·세 부분 AC40, 총77개 중76개에 직접 OK 및 실제 단일 파일이 있다. NFR3은 Deferred로 남아 전체 OK 조건을 충족하지 않는다. 기존 U17 다중 파일 target과 FR 직접 연결 누락은 수정됐다. 전체 검증 차단을 문서상 OK로 바꾸지 않는다.

| ID | 결과 | 소유 유닛 | 실제 target 파일 |
|---|---|---|---|
| FR1 | OK | u16-service-guardrail | backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/ServicePolicyCatalog.java |
| FR1.1 | OK | u16-service-guardrail | backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/ServicePolicyCatalog.java |
| FR1.2 | OK | u16-service-guardrail | backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/ServicePolicyCatalog.java |
| FR1.3 | OK | u16-service-guardrail | backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/ServicePolicyCatalog.java |
| FR2 | OK | u16-service-guardrail | backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/JoinFlow.java |
| FR2.1 | OK | u16-service-guardrail | backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/JoinFlow.java |
| FR2.2 | OK | u16-service-guardrail | backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/NoticeDelivery.java |
| FR2.3 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ParticipationLateItemServiceGuardrailTest.java |
| FR2.4 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ParticipationServiceGuardrailTest.java |
| FR2.5 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/GreetingHandoffServiceGuardrailTest.java |
| FR3 | OK | u16-service-guardrail | backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/InstructionTemplates.java |
| FR3.1 | OK | u16-service-guardrail | backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/InstructionTemplates.java |
| FR3.2 | OK | u16-service-guardrail | backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/ConversationPolicyState.java |
| FR3.3 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ConversationPolicyServiceGuardrailTest.java |
| FR3.4 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/InstructionTemplatesServiceGuardrailTest.java |
| FR4 | OK | u16-service-guardrail | backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionExecutor.java |
| FR4.1 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/StopIntentServiceGuardrailTest.java |
| FR4.2 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/StopQuestionServiceGuardrailTest.java |
| FR4.3 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/OutputRuntimeServiceGuardrailTest.java |
| FR5 | OK | u16-service-guardrail | backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/ServicePolicyCatalog.java |
| FR5.1 | OK | u16-service-guardrail | backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/ServicePolicyCatalog.java |
| FR5.2 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/voice/ProviderRequestServiceGuardrailTest.java |
| FR5.3 | OK | u16-service-guardrail | backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/PolicyBundle.java |
| FR6 | OK | u16-service-guardrail | backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionClocks.java |
| FR6.1 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/SessionClocksCharacterizationTest.java |
| FR6.2 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/SessionClocksCharacterizationTest.java |
| FR6.3 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ParticipationServiceGuardrailTest.java |
| FR7 | OK | u16-service-guardrail | backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/GuardrailCoordinator.java |
| FR7.1 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/voice/ProviderRequestServiceGuardrailIT.java |
| FR7.2 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/guardrail/GuardrailPolicyV2Test.java |
| FR7.3 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/InstructionTemplatesServiceGuardrailTest.java |
| NFR1 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/PrivacyLogTest.java |
| NFR2 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/GreetingHandoffServiceGuardrailTest.java |
| NFR3 | Deferred | u16-service-guardrail | 전체 verify.sh 및 미검증 근거 유지 |
| NFR4 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ServiceGuardrailFixtures.java |
| NFR5 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ServicePolicyCatalogServiceGuardrailTest.java |
| NFR6 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/voice/ProviderRequestServiceGuardrailIT.java |
| AC1.1.1 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/voice/ProviderRequestServiceGuardrailTest.java |
| AC1.1.2 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ServicePolicyCatalogServiceGuardrailTest.java |
| AC1.1.3 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/voice/ProviderRequestServiceGuardrailTest.java |
| AC1.1.4 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ServicePolicyCatalogServiceGuardrailTest.java |
| AC1.1.5 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/voice/ProviderRequestServiceGuardrailIT.java |
| AC1.2.1 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/NoticeAssetServiceGuardrailTest.java |
| AC1.2.2 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/NoticeDeliveryServiceGuardrailIT.java |
| AC1.2.3 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ParticipationServiceGuardrailTest.java |
| AC1.2.4 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/NoticeDeliveryServiceGuardrailTest.java |
| AC1.2.5 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ParticipationServiceGuardrailTest.java |
| AC1.3.1 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ParticipationServiceGuardrailTest.java |
| AC1.3.2 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ParticipationServiceGuardrailTest.java |
| AC1.3.3 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ParticipationServiceGuardrailTest.java |
| AC1.3.4 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ParticipationServiceGuardrailTest.java |
| AC1.3.5 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ParticipationServiceGuardrailTest.java |
| AC1.4.1 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/InstructionTemplatesServiceGuardrailTest.java |
| AC1.4.2 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/InstructionTemplatesServiceGuardrailTest.java |
| AC1.4.3 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ConversationPolicyServiceGuardrailTest.java |
| AC1.4.4 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ConversationPolicyServiceGuardrailTest.java |
| AC1.4.5 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ServicePolicyCatalogServiceGuardrailTest.java |
| AC1.5.1 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/InstructionTemplatesServiceGuardrailTest.java |
| AC1.5.2 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ConversationPolicyServiceGuardrailTest.java |
| AC1.5.3 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/InstructionTemplatesServiceGuardrailTest.java |
| AC1.5.4 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/InstructionTemplatesServiceGuardrailTest.java |
| AC1.5.5 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/InstructionTemplatesServiceGuardrailTest.java |
| AC1.6.1 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/OutputRuntimeServiceGuardrailTest.java |
| AC1.6.2 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/OutputRuntimeServiceGuardrailTest.java |
| AC1.6.3 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/StopQuestionServiceGuardrailTest.java |
| AC1.6.4 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/judgment/JudgmentRequestServiceGuardrailTest.java |
| AC1.6.5 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/TechnicalPauseTest.java |
| AC2.1.1 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ServicePolicyCatalogServiceGuardrailTest.java |
| AC2.1.2 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ServicePolicyCatalogServiceGuardrailTest.java |
| AC2.1.3 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ServicePolicyCatalogServiceGuardrailTest.java |
| AC2.1.4 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ServicePolicyCatalogServiceGuardrailTest.java |
| AC2.1.5 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/RealtimeDeletionHandlerIT.java |
| AC2.2.1 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/voice/ProviderRequestServiceGuardrailTest.java |
| AC2.2.2 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/guardrail/GuardrailPolicyV2Test.java |
| AC2.2.3 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/gate/OutputGateTest.java |
| AC2.2.4 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/OutputRuntimeServiceGuardrailTest.java |
| AC2.2.5 | OK | u16-service-guardrail | backend/realtime-activity/src/test/java/kr/mujoong/realtime/guardrail/GuardrailPolicyV2Test.java |

## 최초 실행 기록

# 유닛 간 최종 요구 추적

## 판정

FAIL. 요구사항의 FR31개와 NFR 묶음6개, 합계37개에 code-generation 추적의 직접 OK/존재 파일 연결이 없다. AC40개는 U16의 존재하는 단일 시험 파일에 연결된다. 이것은 구현37개 부재를 의미하지 않으며 문서 연결 누락이다. U17의 세미콜론 다중 경로/설명 target은 단일 파일이 아니므로 그 자체로 유효하지 않다(검토 R-03:46개 invalid_targets).

## 범위와 검사

inception/requirements-analysis/requirements.md의 모든 FR/NFR 및 inception/user-stories/stories.md의 세 부분 AC를 열거하고, 두 construction/*/code-generation/traceability.json의 coverage에서 status=OK와 workspace 기준 실제 파일을 확인했다. 다른 단계의 BR/AC 간접 연결을 최종 직접 연결로 대체하지 않는다.

| ID | 결과 | 소유 유닛 | 실제 target 파일 |
|---|---|---|---|
| FR1 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR1.1 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR1.2 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR1.3 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR2 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR2.1 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR2.2 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR2.3 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR2.4 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR2.5 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR3 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR3.1 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR3.2 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR3.3 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR3.4 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR4 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR4.1 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR4.2 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR4.3 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR5 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR5.1 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR5.2 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR5.3 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR6 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR6.1 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR6.2 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR6.3 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR7 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR7.1 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR7.2 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| FR7.3 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| NFR1 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| NFR2 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| NFR3 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| NFR4 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| NFR5 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| NFR6 | GAP | 연결 필요 | 직접 OK/존재 파일 없음 |
| AC1.1.1 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/voice/ProviderRequestServiceGuardrailTest.java` |
| AC1.1.2 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ServicePolicyCatalogServiceGuardrailTest.java` |
| AC1.1.3 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/voice/ProviderRequestServiceGuardrailTest.java` |
| AC1.1.4 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ServicePolicyCatalogServiceGuardrailTest.java` |
| AC1.1.5 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/voice/ProviderRequestServiceGuardrailIT.java` |
| AC1.2.1 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/NoticeAssetServiceGuardrailTest.java` |
| AC1.2.2 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/NoticeDeliveryServiceGuardrailIT.java` |
| AC1.2.3 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ParticipationServiceGuardrailTest.java` |
| AC1.2.4 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/NoticeDeliveryServiceGuardrailTest.java` |
| AC1.2.5 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ParticipationServiceGuardrailTest.java` |
| AC1.3.1 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ParticipationServiceGuardrailTest.java` |
| AC1.3.2 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ParticipationServiceGuardrailTest.java` |
| AC1.3.3 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ParticipationServiceGuardrailTest.java` |
| AC1.3.4 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ParticipationServiceGuardrailTest.java` |
| AC1.3.5 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/ParticipationServiceGuardrailTest.java` |
| AC1.4.1 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/InstructionTemplatesServiceGuardrailTest.java` |
| AC1.4.2 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/InstructionTemplatesServiceGuardrailTest.java` |
| AC1.4.3 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ConversationPolicyServiceGuardrailTest.java` |
| AC1.4.4 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ConversationPolicyServiceGuardrailTest.java` |
| AC1.4.5 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ServicePolicyCatalogServiceGuardrailTest.java` |
| AC1.5.1 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/InstructionTemplatesServiceGuardrailTest.java` |
| AC1.5.2 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ConversationPolicyServiceGuardrailTest.java` |
| AC1.5.3 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/InstructionTemplatesServiceGuardrailTest.java` |
| AC1.5.4 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/InstructionTemplatesServiceGuardrailTest.java` |
| AC1.5.5 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/InstructionTemplatesServiceGuardrailTest.java` |
| AC1.6.1 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/OutputRuntimeServiceGuardrailTest.java` |
| AC1.6.2 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/OutputRuntimeServiceGuardrailTest.java` |
| AC1.6.3 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/StopQuestionServiceGuardrailTest.java` |
| AC1.6.4 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/judgment/JudgmentRequestServiceGuardrailTest.java` |
| AC1.6.5 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/TechnicalPauseTest.java` |
| AC2.1.1 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ServicePolicyCatalogServiceGuardrailTest.java` |
| AC2.1.2 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ServicePolicyCatalogServiceGuardrailTest.java` |
| AC2.1.3 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ServicePolicyCatalogServiceGuardrailTest.java` |
| AC2.1.4 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/ServicePolicyCatalogServiceGuardrailTest.java` |
| AC2.1.5 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/RealtimeDeletionHandlerIT.java` |
| AC2.2.1 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/voice/ProviderRequestServiceGuardrailTest.java` |
| AC2.2.2 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/guardrail/GuardrailPolicyV2Test.java` |
| AC2.2.3 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/gate/OutputGateTest.java` |
| AC2.2.4 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/session/OutputRuntimeServiceGuardrailTest.java` |
| AC2.2.5 | OK | u16-service-guardrail | `backend/realtime-activity/src/test/java/kr/mujoong/realtime/guardrail/GuardrailPolicyV2Test.java` |

## 조치

U16은 FR1–FR7 및 하위 요구/상위 NFR의 실제 담당 구현·시험 파일을 대응시킨다. U17은 UI 담당 FR/NFR과 각 OK target을 정확히 한 파일로 작성하고 다른 근거는 justification으로 옮긴다. 실제 음성/브라우저 미검증을 OK로 바꾸지 않는다. 대상 요구가 아직 미완료이면 사실대로 상태를 유지하며 최종 합격으로 보고하지 않는다.

