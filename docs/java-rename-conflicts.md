# Java 클래스 이름 변경 — 충돌 처리표

구조 리팩토링 Step 22(BR4.5)에서 `U<번호>`가 든 Java 파일 103개의 이름에서 번호를 뺄 때,
번호를 뺀 이름이 같은 폴더의 다른 파일과 겹치는 20개를 번호 자리에 기능 이름(파스칼 케이스, 설계 3.1절 표)으로 구분했다.
나머지 83개는 번호만 뺐다. 이 표는 이름 변경 스크립트가 같은 폴더의 기존 파일 · 번호를 뺀 이름을 대조해 만든 것이다.

| 폴더(`kr/mujoong/…`) | 옛 이름 | 새 이름 | 겹친 이유 |
|---|---|---|---|
| core/db | `SeedU4aReferenceSqlIT` | `SeedActivitySessionReferenceSqlIT` | `SeedU8ReferenceSqlIT`와 번호를 빼면 같고, 번호 없는 `SeedReferenceSqlIT`가 이미 있음 |
| core/db | `SeedU8ReferenceSqlIT` | `SeedLiveContentReferenceSqlIT` | 위와 같음 |
| core/identity | `U8DevSeedRunnerIT` | `LiveContentDevSeedRunnerIT` | 번호 없는 `DevSeedRunnerIT`가 이미 있음 |
| core/result | `U14ResultApiIT` | `GoalResultResultApiIT` | 번호 없는 `ResultApiIT`가 이미 있음 |
| core/result | `U14ResultRetryIT` | `GoalResultResultRetryIT` | 번호 없는 `ResultRetryIT`가 이미 있음 |
| core/support | `U2Fixtures` | `ParentAccountFixtures` | `U8Fixtures`와 번호를 빼면 같음 |
| core/support | `U8Fixtures` | `LiveContentFixtures` | 위와 같음 |
| realtime/dialog | `JoinTrackerU4bTest` | `JoinTrackerSessionRuntimeTest` | 번호 없는 `JoinTrackerTest`가 이미 있음 |
| realtime/gateway | `ConnectionSenderU3Test` | `ConnectionSenderVoiceSessionTest` | 번호 없는 `ConnectionSenderTest`가 이미 있음 |
| realtime/gateway | `ConnectionStateWriterU3IT` | `ConnectionStateWriterVoiceSessionIT` | 번호 없는 `ConnectionStateWriterIT`가 이미 있음 |
| realtime/gateway | `DeviceStatusPolicyU3Test` | `DeviceStatusPolicyVoiceSessionTest` | 번호 없는 `DeviceStatusPolicyTest`가 이미 있음 |
| realtime/session | `SessionExecutorU3Test` | `SessionExecutorVoiceSessionTest` | 번호 없는 `SessionExecutorTest`가 이미 있음 |
| realtime/session | `SessionLifecycleU4bIT` | `SessionLifecycleSessionRuntimeIT` | 번호 없는 `SessionLifecycleIT`가 이미 있음 |
| realtime/session | `SessionStateEmitterU4bTest` | `SessionStateEmitterSessionRuntimeTest` | 번호 없는 `SessionStateEmitterTest`가 이미 있음 |
| realtime/session | `TransitionTableU4bTest` | `TransitionTableSessionRuntimeTest` | 번호 없는 `TransitionTableTest`가 이미 있음 |
| realtime/session/web | `SessionInternalControllerU3Test` | `SessionInternalControllerVoiceSessionTest` | `…U4bTest`와 번호를 빼면 같음 |
| realtime/session/web | `SessionInternalControllerU4bTest` | `SessionInternalControllerSessionRuntimeTest` | 위와 같음 |
| realtime/startup | `U8RealtimeStartupChecksTest` | `LiveContentRealtimeStartupChecksTest` | 번호 없는 `RealtimeStartupChecksTest`가 이미 있음 |
| realtime/voice | `ProviderClientU3IT` | `ProviderClientVoiceSessionIT` | 번호 없는 `ProviderClientIT`가 이미 있음 |
| realtime/web | `DeviceGatewayU3IT` | `DeviceGatewayVoiceSessionIT` | 번호 없는 `DeviceGatewayIT`가 이미 있음 |

- 이름 변경 방식: `git mv`로 파일을 옮기고, 옛 클래스 이름을 낱말 경계로 찾아 `backend/` · `contracts/` · `infra/` 등 이력 문서(`docs/` · `aidlc/`)가 아닌 파일의 참조를 같은 커밋에서 새 이름으로 고쳤다.
- 한 파일에 번호가 둘인 `U5aExtensionU6IT`는 번호를 모두 빼 `ExtensionIT`가 되었다(겹침 없음).
- 시험 메서드 이름에 든 `U8` 같은 낱말은 동작 이름이라 이번 범위(파일 · 경로 · 태그)에 넣지 않았다.
