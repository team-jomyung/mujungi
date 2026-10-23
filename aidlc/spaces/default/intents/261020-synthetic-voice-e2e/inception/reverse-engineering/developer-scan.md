# 합성 음성 전체 경로 — 개발자 집중 조사

## Developer Code Scan Results

기준: 2026-10-20, ops/synthetic-voice-e2e, commit 2c431bb00b5580032ca5d0bf73d8bd591fb03749. 별도 intent 261020-synthetic-voice-e2e, Standard, collaborators off의 단독 최종 링크다. 사용자가 고른 Focused scan이며 이번에는 소스/설정/컨테이너 변경, 합성 음성 생성, 유료 호출 또는 앱 시험을 하지 않았다.

### Scan Coverage

**Analyzed deeply** (아래 파일의 음성 진단 관련 동작. 거대 파일의 한계는 아래 참조):
- backend/realtime-activity/build.gradle.kts
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionExecutor.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/JoinFlow.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/JoinTracker.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/gate/OutputGate.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/gate/SegmentBuffer.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderClient.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/log/LatencyRecorder.java
- backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionViewQuery.java
- backend/realtime-activity/src/test/java/kr/mujoong/realtime/voice/GptLiveProbe.java
- backend/core-api/src/main/java/kr/mujoong/core/activity/web/ActivityController.java
- backend/contract-model/src/main/java/kr/mujoong/contract/audio/AudioFrameCodec.java
- backend/contract-model/src/main/java/kr/mujoong/contract/ws/message/PlaybackMark.java
- frontend/src/device/audio/capture.ts
- frontend/src/device/audio/player.ts
- frontend/src/device/audio/audioContext.ts
- frontend/src/device/audio/resample.ts
- frontend/src/device/ws/deviceSession.ts
- frontend/src/device/runtime/deviceRuntime.ts
- frontend/src/device/mic/micPermission.ts
- frontend/src/device/api/deviceApi.ts
- frontend/src/parent/common/api/live.ts
- frontend/src/parent/common/hooks/useLivePolling.ts
- frontend/src/parent/features/live/useLiveScreen.ts
- frontend/src/parent/common/components/TranscriptList.tsx
- frontend/src/parent/common/components/transcriptTimeline.ts
- frontend/src/parent/features/free/api.ts
- frontend/src/parent/features/free/useFreeTalkScreen.ts
- frontend/src/shared/protocol/frameCodec.ts
- frontend/src/app/device/page.tsx
- frontend/src/app/parent/sessions/[sessionId]/page.tsx
- tools/device-simulator/simulator.mjs
- tools/seed/gen-session-wav.mjs
- tools/seed/gen-approved-wav.mjs
- infra/compose.live.yml
- docs/live-run.md

**Skimmed only**
- 기존 store의 ./ 전체 분석 주장은 STALE이라 이번에 검증하지 않았다. timestamp의 shallow.paths에 ./와 기존 목록을 보존.
- backend/realtime-activity/src/main/resources/application.yml: 시간 설정 키만 확인(입력 조용 1500ms, 출력 조용 800ms, J1 4s, 제브 800ms, flush 200ms, latency sample 100).
- tools/skeleton-e2e/steps.mjs: 로그인·등록·참여·부모 items 준비 절차와 단언을 참고. 전체 시나리오 소유 로직 정독은 아님.
- backend/core-api/src/main/java/kr/mujoong/core/activity/web/SessionController.java: 경로와 DTO 설명 검색만.
- scripts/live/, contracts/: 파일 목록·가이드의 참조. 내부 전체 분석 안 함.
- SessionExecutor.java: 1970~2265(입력/출력), 2700~2940(봉인/검사/송신/폐기)을 깊게 읽었고 나머지 실행기 전체를 재검증하지 않음.
- GptLiveProbe.java: 50~180, 250~295, 375~445의 설정·직접 연결·입력을 집중해서 읽고 다른 실험 단계는 검색 참조.

**Left out**
- .codex/, .agents/ 설치 폴더와 aidlc/는 앱 분석에서 제외(필수 지식과 기존 CodeKB는 읽음).
- gitignore 대상, build/dist/coverage/target 산출물, node_modules/의존성, .gradle/.next/캐시.
- 범위 밖 기능(결과·리포트·연습 상세), 외부 계정·약관·가격·현재 컨테이너 실제 설정.
- 실제 하드웨어 마이크·스피커·아동 음성.

### Packages Found
- backend/realtime-activity — Spring Java 앱: 음성 중계·참여·게이트·관측.
- backend/core-api — Spring Java 앱: 보호자 자유대화 시작 HTTP 경계.
- backend/contract-model — Java 라이브러리: 프레임·재생 표식 계약.
- frontend — Next.js/TypeScript: device 실제 캡처·재생과 parent 폴링.
- tools — Node ESM: 시뮬레이터·seed 음성 생성.
- infra — Compose live 연결 구성.

### Build System
- Gradle Kotlin DSL. 이번에 읽은 realtime build는 contract-model 운영 의존, test-support 시험 의존.
- gptLiveTest/lunaReachTest/jevLiveTest는 명시적인 격리 Test 작업이며 check/build로 유료 호출을 끌어오지 않는 계약을 주석과 작업 구성에 보유.
- npm 프런트/도구의 현재 manifest 및 전체 버전은 재검증 안 함. 기존 CodeKB 기술표는 역사적 정보로 보존.

### APIs Discovered
전체 endpoint 수를 세지 않았다. 이번에 필요한 경계는 /api/device/bootstrap, status, claim, /api/device/register, /api/dev/login, /api/session/csrf, /api/home, /api/activities/free, /ws/device, /api/sessions/{id}?sinceSeq=n, /api/sessions/{id}/end다. 준비 접점의 일부는 기존 skeleton 도구와 deviceApi를 근거로 정리했고 백엔드 인증 필터 전체를 새로 검증한 것은 아니다. 구체 형식과 쿠키/CSRF 요구는 후보 api-documentation.md에 있다.

### Frameworks & Libraries
현재 읽은 구현: Spring MVC, JDK WebSocket, Web Audio/AudioWorklet/getUserMedia, TanStack Query, Node ws, Gradle JUnit Platform. 버전은 새로 조사하지 않았다.

### Test Coverage
- 기존 realtime src/test의 GptLiveProbe는 직접 공급자 실험. 기본 입력은 말소리가 없는 톤/무음 자리표시이며 GPT_LIVE_INPUT_PCM으로 외부 합성 WAV/PCM을 지정할 수 있다.
- tools/tests는 skeleton·voice-session 등 기능별 구조를 파일 목록으로 확인. 이번에 시험 본문/커버리지를 재측정하지 않았다.
- 현 단계에서 시험 실행 횟수는 0이며 과거 대역 시험 합격을 실공급자 성공으로 승격하지 않는다.

### Code Quality Indicators
- 관련 함수/타입 책임은 분리돼 있으나 실제 전체 거래를 자동 실행·보고하는 기존 도구는 확인하지 못했다.
- SessionViewQuery는 FINAL+승인 AI만 제공하며 보호자 표시 전 정책을 명확히 둔다.
- LatencyRecorder는 원문 없는 시각·개수 기반 관측과 미관측 사유를 보유.
- 린트·CI·coverage는 기존 문서만 보존, 이번에 파이프라인 본문을 다시 검증하지 않았다.

### Technical Debt Signals
- Q-S1: 공급자 직접 probe, 앱 서버 simulator, 실제 브라우저 세 경계가 분리돼 있어 공급자 성공을 전체 성공으로 착각할 위험.
- Q-S2: GATE_EMPTY 단일 코드가 incomplete/전사 없음/오디오 없음/정규화 빈 값/PCM 오류를 포괄하여 현 로그만으로 분리 진단이 어려움.
- Q-S3: 참여 전 비저장은 정책이지만 합성 시험에서도 짧은 긍정이 어떻게 매칭됐는지 관측할 지점이 부족.
- Q-S4: Web Audio STARTED는 스케줄 지연 타이머로 보고되며 물리 청취를 보장하지 않음(player.ts:451 이후).
- Q-S5: provider에는 입력 완료/응답 끝 id가 없어 서버의 입력 묶음·출력 조용 기준에 의존. 실제 조각 타이밍 검증이 필요.

## Handoff Summary

### 핵심 코드 사실 및 근거

| 발견 | 근거 |
|---|---|
| 참여 전 전사는 저장·부모 표시하지 않고 J1 분류용으로만 처리 | SessionExecutor.java:2097,2164 및 JoinFlow.java:onJoinReply |
| 응/네 등 짧은 긍정은 정규화 후 exact 매칭, UNCLEAR만 J1 후보 | JoinTracker.java:ACCEPT_EXACT 및 classify; JoinFlow.java:145 이후 |
| 참여 전 GPT 출력 보류, 수락 후 첫 응답 지시 | SessionExecutor.java:2027 및 JoinFlow.java:acceptJoin |
| ACTIVE 입력 delta마다 열린 출력 조기 봉인 가능 | SessionExecutor.java:2097~2111; sealAndGate:2729 이후 |
| 800ms 전에 조기 봉인하면 불완전, GATE_EMPTY 가능 | SegmentBuffer.sealEarly, OutputGate.inspect, SessionExecutor.sealAndGate |
| 보호자 AI 승인은 검사 통과 후 송신 시점에 aiFinal(true) | SessionExecutor.java:2836 이후. 폐기는 aiFinal(false), SessionViewQuery가 노출 제외 |
| 부모는 2초 폴링, 숨은 탭 주기 중단 | useLivePolling.ts. actual timing override는 실행 단계 확인 |
| 공급자 파서가 입력/출력 delta만 앱 사건으로 변환 | ProviderClient.java:parse. 입력 done·말 끝·응답 끝을 현재 앱 사건으로 받지 않음 |
| 기존 simulator의 음성 및 재생은 합성 톤·모의 | simulator.mjs:sendInputFrames, #onBinary, OUTPUT_END, #mark |
| seed 생성기는 TTS가 아니라 톤/무음 | tools/seed/gen-{session,approved}-wav.mjs |
| gptLiveTest는 앱 우회 공급자 경로 | GptLiveProbe.java:50~80,250~292 및 realtime build.gradle.kts:51 이후 |

### 이전 관측과 가설의 분리

앞 대화의 gate_empty 44/oversize 1, J1 약 4초 timeout·짧은 긍정 미매칭은 이번에 원 로그를 읽거나 세지 않은 이전 보고다. 코드에서 timeout 설정과 매칭·조기 봉인 가능성을 독립 확인했을 뿐, 현재 환경에서 같은 실패가 재현됐다고 말하지 않는다. 전사 조각이 입력마다 응답을 잘라 폐기했다는 해석은 재현 대상 가설이다.

### 재사용과 구현 후보

1. 실제 /device를 Chromium 가상 마이크 WAV로 켜면 requestMicrophone→AudioWorklet→resample→DeviceSession→0x01 프레임 길을 통과한다. 입력은 24kHz PCM16LE 모노로 20ms/480표본. 브라우저 WAV 장치의 루프·시작 offset·무음 길이를 통제해 안내 완료 뒤 발화 하나만 넣는 방식을 정해야 한다. 앱 함수를 직접 호출하거나 서버에 전사를 주입한 결과를 이 경로 성공으로 세지 않는다.
2. 실제 보호자 브라우저에서 정상 로컬 로그인/CSRF·동의·기기 안정 확인 후 시작하고 live-transcript/live-bubble(data-speaker, data-final) DOM을 관찰한다. 기존 cookie/등록/시작 세션을 읽고 충돌 여부를 확인한 뒤 준비한다.
3. 새 실행 도구는 합성 대본→WAV 준비, 두 브라우저 컨텍스트(보호자/기기), WS 메타데이터/상태/부모 DOM/Web Audio 시계, JSON/Markdown 결과를 맡는 작은 단위로 나눌 수 있다. TTS 생성 방식/자동화 의존성 설치는 아직 정하지 않았고 environment-readiness.md를 후속 입력으로 사용한다.
4. 기본 자동 시험은 가짜 공급자로 유지. 사용자가 이번에 요청한 유료 실험만 명시 명령·호출 상한·전체 timeout·finally 종료·환경 키 비노출을 둔다. 규칙 메모리나 기존 CI를 바꾸지 않는다. SKIPPED_NO_KEY/TIMEOUT/UNOBSERVED는 PASS와 구분.
5. 새 앱 계측이 필요하면 원문 없이 입력 조각 수·길이·합성 대본 일치 결과·정규화/분류 코드, 구간 complete/오디오 길이/전사 유무/movedOn, 판정 결과/timeout 정도만 설계 후보로 둔다. 지금 로그에 원문을 더하지 않는다.

### 단계별 필요한 증거와 시간

| 단계 | 관측 | 한계 |
|---|---|---|
| 합성 입력 | fixture id·해시·표본율·발화/무음 길이·입력 에너지, 첫/마지막 유성 0x01 | 프레임 수만으로 말소리 확인 안 됨. 캡처/resample 뒤 해시는 원본 WAV와 달라질 수 있음 |
| 전사/참여 | delta 수·묶음 확정 시각·기대 대본 일치/분류·WAITING_CHILD→ACTIVE·J1 결과 | 참여 전 원문 비저장 유지. 원문 없는 시험 전용 비교 방법은 설계 필요 |
| AI 출력 | 첫/마지막 provider audio/transcript, 세대 귀속 | 연결 성공만으로 앱 출력 성공 판정 안 됨 |
| 출력 검사 | complete·inspect 결과·제브 여부/판정·tSegmentConfirmed→tGatePass | GATE_EMPTY 세부 조건 분리 필요. 제브 STUB/REAL 명시 |
| 기기 전달 | source=GPT_LIVE의 OUTPUT_BEGIN/playbackId, 0x02 바이트/에너지, OUTPUT_END | 시작 안내·WAITING_FILLER 성공과 GPT 응답 성공 구분 |
| 브라우저 재생 | running AudioContext·GPT playbackId STARTED/COMPLETED/FAILED, 필요시 출력 buffer 계측 | STARTED는 타이머 기반. 물리 스피커 청취는 별도 수동 증거 |
| 부모 표시 | API items와 DOM AI/CHILD seq·final·첫 표시 시각 | tViewItemFirstServed는 DOM 시각 아님. 숨은 탭은 폴링 멈춤 |
| 지연 | 입력 끝→provider 첫 조각→구간 확정→게이트→첫 송신→기기 재생/부모 DOM | 미관측 구간 값을 만들지 않음. 서버 단조 구간과 브라우저 시계를 오프셋 없이 직접 빼지 않음 |

LATENCY_TURN의 providerResponseMs/quietWaitMs 및 tSpeechEnd,tProviderFirstOut,tSegmentConfirmed,tGatePass,tFirstFrameSent,tDevicePlayStart,tDevicePlayStartRecv,clockOffsetMs,rttMs를 재사용할 수 있다. SESSION_ITEMS_SERVED의 tViewItemReady/tViewItemFirstServed와 DOM 시각을 별개로 기록한다. 지연의 성공 보장 상한은 이번 정적 조사에서 확정할 수 없다.

### Risks / follow-up

합성 짧은 긍정, 더 긴 긍정, ACTIVE 질문을 별도 시나리오로 시험하고 각 실패의 마지막 성공 단계를 남길 것. 합성 파일 반복 입력이 출력 중 끼어들기를 유발할 수 있다. 실제 음성 API/TTS 비용 상한·시도 횟수와 현재 live 이미지 커밋·설정은 실행 전에 확인할 것. 기존 mujoong-manual/사용자 볼륨은 건드리지 말 것. 물리 스피커와 아동 음성 품질은 자동 브라우저 성공의 범위 밖이다.

### 후보 산출물과 병합 보존

기존 9개 문서를 모두 읽고 앞 8개 파일의 원문을 그대로 보존한 채 이번 집중 조사 절만 덧붙였다. timestamp도 이전 실행의 설명을 역사적 기록으로 보존하고 현재 Run Record와 정확한 partial Scope 블록으로 대체했다. 과거 전체 analyzed ./ 및 components는 이번 deep에 승계하지 않았다. 이번 후보는 .aidlc-engine/codekb-stage-mujungi/에만 있고 공유 store는 수정하지 않았다. scope compare·publish·link receipt는 orchestration 담당이 수행한다.

