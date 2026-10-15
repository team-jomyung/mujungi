① 개정: Core/Realtime·Google·고정 ID 기기·GPT-Live 릴레이/게이트·즉시 ENDING·앱별 복구·영속 전달의 현행 결정과 ADR-009/010, 첫 연결 POC를 연결했다.
② 대체: 직접 WebRTC/SDP/Sideband·3개 로그인·인사 후 ENDING·단일 재시작·기기 단기 Cookie·단일 Spring의 이전 선택과 오래된 Pending을 명시 이력으로 보존했다.
③ 남은 TBD: 실제 버전/SDK·API/필드/스키마·인증/기기·분할/전사/검사/버퍼/재생 보고·복구/보존·G-02/G-03·배포/실연동/POC와 기존 제안값 검증.

# Decision Log

이 문서는 팀 결정 결과와 판단·실험 확인이 남은 항목을 추적한다. 상세 근거와 결정 문구는 각 **기준 ADR**에 둔다. 유일한 개정 기준은 [audit v1.1](../ref/mujung-architecture-update-audit-v1.1.md)이며 **최신 사용자 확정 → v0.2 수정 → 기존 비충돌 내용** 순서를 적용한다. [v0.2 개요](../ref/2-spring-architecture-v0.2.md)와 [교차검토](../ref/2-spring-cross-validation-v0.2.md)는 보조 근거다. 과거 요구사항·동료 명세의 참조는 그 출처와 책임을 보존하는 기록이며 audit를 덮어쓰지 않는다.

`TBD`는 값·상세 또는 아직 결정하지 않은 범위, `Proposed`는 제안 상태, `검증 필요/미실시`는 실제 증거가 없는 상태다. **방향 적용과 구현·실연동·성능 검증 완료를 구분한다.** 기존 ADR의 Proposed 상태를 이 로그만으로 Accepted로 올리지 않는다. 반대로 상세 구현이 미정이라는 이유로 확정된 릴레이·두 앱·Google 등의 방향을 다시 후보로 낮추지 않는다. 담당 ADR의 Pending과 이 로그가 다르면 audit와 담당 ADR의 현행 결정·이력을 먼저 대조한다.

2026-10-01의 **Neo4j 채택**, **MVP GPT-Live 단일 전사·별도 STT 보류**, **Spring Security OAuth2 Client·Spring Session JDBC**, **자체 아이디·비밀번호 가입 제외**는 유지한다. 전사 보류 목적·이유는 [ADR-004 §2.14](ADR-004-communication-voice.md#mvp-transcription), 현행 Google 로그인/Guardian·서비스 세션은 [ADR-008 §3.2](ADR-008-auth-access-control.md#guardian-social-login)가 소유한다. 로그인 공급자 범위의 변경 이력은 아래 대체 표에 남긴다.

## audit v1.1(2026-10-07)의 현행 결정과 대체 관계

아래 표의 **이전 선택 열은 모두 대체된 이력**이다. 현재 구현·시험 지시로 사용하지 않는다. 방향의 근거는 audit §2~7이며, 미정 값은 각 후속 표와 담당 문서의 Pending에 남긴다.

| 주제 | 대체된 이전 선택·Pending | 현행 결정과 연결 | 남은 확인 |
| --- | --- | --- | --- |
| 음성 연결 | 브라우저↔GPT-Live WebRTC 직접 연결·SDP 교환·Data Channel·서버 Sideband. 원본 ADR-004 Alternative A의 Spring 음성 중계 미채택과 DL-004-01/02/04의 이전 연결 시험 | 기기↔Realtime `/ws` WSS 음성·제어·재생 보고 + Realtime↔GPT-Live 주 WebSocket 릴레이 **B안**. 원본 Alternative A 번호와 최신 B안 명칭은 다른 분류다. [ADR-004](ADR-004-communication-voice.md) | 분할/전사 대응·게이트·재생·SDK·지연 실연동 |
| 로그인 공급자 | 2026-10-01 카카오·구글·네이버 3개 소셜 선택, 3개 등록값/콜백·네이버 공개 검수와 DL-011·DL-008-01/12의 이전 범위 | **Google만**. Guardian·서비스 세션·로그아웃·CSRF·현재 관계/동의 검사는 유지. [ADR-008](ADR-008-auth-access-control.md) | Google 등록/가입·토큰 보관·실연동 |
| 아동 종료 | 인사 재생 후 ENDING, DL-005-11의 종료 순서 미정 | 유효한 종료 표현 확인 **즉시 ENDING인 A안** → 일반 출력 차단·버퍼/기기 큐 취소 → 고정된 승인 마지막 인사 1회 예외 → 실제 정리. [ADR-005](ADR-005-session-events-timeouts.md) | 승인 인사 제공 실패·실제 정리 확인 상세 |
| 재시작 | 서버 시작 시 실시간 활동과 RUNNING Job 일괄 정리 | Core는 자신의 Job/Outbox/Inbox, Realtime은 소유 활동/전달을 복구·정리한다. 메모리 음성 버퍼를 복원하거나 미확인 재생을 성공으로 채우지 않는다. [ADR-005](ADR-005-session-events-timeouts.md), [ADR-009](ADR-009-execution-boundary.md) | 소유권/lease·이전 실행 차단·장애 시험 |
| 기기 | `/device` 단기 인증 Cookie·운영자 일회용 발급 코드와 TTL/발급 Endpoint Pending, 전역 Available 0/1/2+ 개수 기준 | 고정 ID 사전 시드·대기 WSS·상태 보고/보호자 상태 카드·보호자 시작 push. 허용 기기·현재 연결·점유·원자 할당은 유지. [ADR-003](ADR-003-child-device-ui.md), [ADR-008](ADR-008-auth-access-control.md) | 고정 ID는 운영 인증의 증거가 아님. 실제 값·연결/운영 인증·표시 상세 TBD |
| 실행 경계 | 단일 Spring 안의 실행·같은 프로세스 호출/이벤트 전제 | **Core API Spring + Realtime Activity Spring**. 실행 특성·상태 소유권 기준이며 Result Worker는 Core 내부. [ADR-009](ADR-009-execution-boundary.md) | 두 빌드/기동·스캔·공통 코드·합산 자원 시험 |
| 앱 간 영속 전달 | 단일 앱의 메모리 이벤트만으로 결과 요청/완료를 연결하던 전제 | 즉시 명령은 내부 HTTP, 영속 요청/완료·정책 변경은 **Outbox/Inbox + 내부 HTTP, Broker 없음**. [ADR-010](ADR-010-durable-delivery.md) | 커밋/ACK·멱등·run/lease/fencing·복구 구현 |
| 부분 결과 | DL-005-09에서 부분 결과 생성의 필요성 자체를 TBD로 두던 표현 | 참여 이후 **유효 기록이면 부분 결과**. 활동 `PARTIAL`과 Core 결과 준비/실패는 다른 축이다. [결과 파이프라인](architecture/result-pipeline.md) | 미참여/자료 부족/철회 상세·데이터/API·재시도 |

현행 공급자는 **GPT-Live API(`gpt-live-1`)**다. 우리 앱 이름 Realtime과 공급자 API 이름을 구분한다. 서버 주 연결은 `wss://api.openai.com/v1/live/sessions` → 모델/설정을 담은 `session.start` → `session.started`이며, 기기 메시지와 공급자 이벤트는 [protocol](architecture/protocol.md)·[ADR-004](ADR-004-communication-voice.md)의 GPT-Live 대조표를 따른다. 운영 모델/Java SDK 지원·종료/취소/저장 설정은 실연동 TBD다. delegation은 확인 항목으로만 남는다.

검사 구간의 출력은 **앱 분할 → 전사 대응 → 상태·권한·최신성 및 필요한 경량 의미 검사 → 동일 음성 승인 → 전달 직전 재확인**을 거친다. 전사 누락/오대응·실패·시간초과·취소 음성은 폐기하고 정해진 대체 안내 경로로 처리한다. 검사 수행이 전달 조건이라는 계약을 모든 위험 발견의 보장으로 표현하지 않는다. 무거운 교육 판정·KG를 동기 게이트에서 분리하는 것은 **우리 설계 권고**이며 필수 안전 검사를 생략하는 뜻이 아니다.

폐기 음성도 모델 맥락에 남는다. 허용된 계속 대화에서는 앱 교정 지시 `session.instructions.append`와 새 출력 재검사를 연결하며 교정 ACK는 재생 승인·교정 성공·맥락 삭제가 아니다. 같은 말을 다시 생성한 음성에 이전 승인을 재사용하지 않는다. 끼어들기·쉬기·종료·철회의 승인/버퍼/대기열 취소를 구분하고 늦은 승인으로 재생을 되살리지 않는다. `playbackMark`는 기기 관측 실제 재생 범위이며 송신/ACK·실제 청취·이해와 다르다.

## 대체·미채택 항목의 Pending 정리

- DL-004-01/02/04의 이전 연결 최종 선택·브라우저 이벤트 Allow List·연결 시험은 위 이력으로 종결했다. 현행 릴레이 방향을 다시 묻지 않고 기기/공급자 계약·SDK 구현과 측정을 확인한다.
- DL-008-06/07의 이전 기기 발급·수명 결정은 데모 현행 Pending에서 종결했다. 운영 인증·실제 페어링은 DL-008-10/11의 후속 범위이며 고정 ID로 해결됐다고 쓰지 않는다.
- DL-005-09/11의 부분 결과 필요성·아동 종료 순서 미정은 현행 결정으로 대체했다. 데이터 표현·실패/미확인 처리·실연동은 남는다.
- v11의 `ADR-007 작성 여부`는 기존 문서가 있어 종결했다. ADR-009/010도 현재 문서가 있으며 구현/시험 완료를 뜻하지 않는다.
- 원본 DL-004-05의 Java 실패 시 별도 공급자 프로세스는 **미채택 조건부 대안**으로 보존한다. 현재 두 Spring 방향/Adapter를 먼저 검증하고 실패 증거가 생기면 별도 결정한다. 세 번째 상시 앱을 채택하지 않는다.
- 프론트 두 앱·Vite·CloudFront·QR claim·UI 표시 상태 전체·delegation은 새 확정 기술이 아니다. 기존 한 Next.js의 `/parent`·`/device` 두 접점을 유지하고 표시 요구로 DB Enum을 확장하지 않는다.

<a id="goal-cue-decisions"></a>
## 2026-10-02 목표 생성·Cue 반영

근거와 적용 범위는 [README의 동기화 기록](README.md#goal-cue-update)을 따른다. 원래의 정책·비충돌 제안과 ID를 보존하며 2026-10-07 audit가 정리한 앱 소유권·게이트·실제 제공 계약을 연결했다. 동료 명세의 실제 경로·버전, 프롬프트·사례 결과는 제공/검증 대기다.

| 항목 | 반영 내용 | 기준·상세 | 상태 |
| --- | --- | --- | --- |
| DL-GOAL-01 | 대화 근거로 행동을 정의하고 4요소를 구성한 경우만 아동별 목록에 추가. 고정 목록 제한·사람 사전 승인 필수 없음 | FR-C02/F06, [모듈 책임](ADR-007-module-boundaries.md#goal-and-cue-ownership) | 기존 요구사항 확정 반영 |
| DL-GOAL-02 | Core가 구조화 기록·허용 근거·관련 과거 기록·기존 목표·공통 지식으로 생성. 코드 검사와 별도 AI 의미 검토를 통과한 후보만 Realtime 공식 계약으로 등록 | [목표 생성 계약](architecture/result-pipeline.md#goal-generation-contract) | Proposed 구현안 · 사례/원격 등록 검증 전 |
| DL-GOAL-03 | 아동별 정의·근거·선택은 PostgreSQL. 목표 정의/목록/선택은 Realtime, 추천 산출물/근거는 Core 소유이며 물리 분해/참조는 TBD. 공통 지식은 Core 경계의 Neo4j. 정의 버전 고정·의미 중복 확인·개인 생성 결과의 자동 공용 지식 승격 금지 | [정의·버전 모델](architecture/data-model.md#goal-definition-model) | 소유권 방향 적용 · 물리 모델 Proposed/TBD |
| DL-GOAL-04 | 근거 부족은 추천 없음, 내용 부적합은 작업 누적 수정 생성 1회. 일시 기술 오류는 Core Worker 공통 재시도·체크포인트. 전달 재시도·내용 수정·LLM 기술·수동 새 실행을 구분하고 추천 없음과 전체 결과 자료 부족을 구분 | [목표 실패 처리](architecture/result-pipeline.md#goal-generation-contract) | Proposed 구현안 · Worker 추가 3회/5·15·30초는 잠정 |
| DL-CUE-01 | GPT-Live 실시간 생성, 첫 반응 보호·동의·정답 금지·실제 제공 기록·아동의 도움 후 반응 1회 | FR-E07/E08/F09/F14, [Cue 계약](architecture/protocol.md#cue-contract) | 기존 요구사항 확정 반영 |
| DL-CUE-02 | Realtime이 제공 조건·허용 정보를 통제하고 GPT-Live에 CueBrief 전달. 대화 역할에 판정 기준·이전 판정·보호자 원문 전달 금지 | [모듈 책임](ADR-007-module-boundaries.md#goal-and-cue-ownership), [Cue 계약](architecture/protocol.md#cue-contract) | 2026-10-02 합의 기본안·현행 소유권 연결 · 검증 전 |
| DL-CUE-03 | 최초 요청부터 도움 음성 실제 재생 시작까지 총 5초이며 구간 분할·전사 대응·검사·송신/재생 대기를 포함. 이내 명확한 미수락·일시 오류·전혀 미제공일 때만 추가 1회. 수락/재생 불명확·일부 재생이면 자동 재요청 금지. 재요청으로 최초 시각을 초기화하지 않음 | [Cue 단계](architecture/session-state.md#cue-lifecycle), ADR-005 | 합의한 초기 설정값 · 미측정/조정 가능 |
| DL-CUE-04 | 생성 전사·검사/승인·실제 제공 범위·동의·중단을 별도 참조. 게이트 통과한 동일 음성만 전달하고 실제 제공은 playbackMark로 검증. 오류·부적절 제공·미확인 재생을 아동 실패로 바꾸지 않음 | [Cue 기록](architecture/data-model.md#cue-delivery-model), [실패 분기](architecture/session-state.md#cue-lifecycle) | 현행 전달 조건 적용 · 재생 관측/검사 시험 전 |

추천의 근거·4요소·불확실 처리, Cue의 동의·기록·늦은 출력 차단은 기존 원칙이다. 자동 검토 절차와 통과/실패 예시를 구체화하는 일을 새 정책 회의가 필요한 상태로 되돌리지 않는다. 첫/도움 후/새 상황은 구분하며 전체 결과·목표/연습·Cue 구현은 작은 첫 연결·합성 Cue 예산 POC와 구분한다.

## 10/1 팀 검토: 공통 판단

아래는 10/1의 기존 항목 ID와 비충돌 내용을 이어 관리하는 표다. 대체된 선택에는 2026-10-07 audit의 현행 방향을 적용했으며 원래 선택의 이력은 앞 표를 따른다.

| 항목 | 확인할 내용 | 기준 문서 | 상태 |
| --- | --- | --- | --- |
| DL-001 | Public Repository 사용 여부 | [ADR-001](ADR-001-repository-git.md) | TBD |
| DL-002 | Java 21·Spring Boot 4.1.x 호환성 확인 후 최종 채택 여부; 문제가 크면 Spring Boot 3.x 검토 | [ADR-002](ADR-002-technology-defaults.md) | Proposed |
| DL-003 | Neo4j를 화용 지식 그래프·온톨로지 저장소로 채택. PostgreSQL은 서비스 운영 데이터 담당 | [ADR-002](ADR-002-technology-defaults.md) | **Accepted · 2026-10-01 팀 확정** |
| DL-004 | ADR-001~010과 architecture의 현행 방향·역할/참조 동기화 및 팀 검토. 이전 ADR-002 v10/v11의 상세 설계 이관 기록은 출처로 보존 | [README](README.md), [진행 기록](../ref/revision-progress.md) | 문서 개정·정적 점검 / 팀 검토·구현·실연동은 별도 |
| DL-005 | 동료 GPT-Live 상세 명세의 실제 경로·버전 및 D-03·D-06 판단 항목 확인 | 동료 GPT-Live 상세 명세 | 명세 필요 |
| DL-006 | 삭제·보존 정책과 NFR-08 정합성. 기존 v11의 실제 삭제 제안과 전사/스냅샷·메시지·체크포인트 정리 상세, 철회 효력/저장 경합(G-03) | 요구사항 NFR-08, [데이터 모델](architecture/data-model.md), [인증](architecture/auth.md) | 상세 TBD · 철회 후 신규/지연 자료 미저장 원칙 유지 |
| DL-007 | 기존 IntentDetector 비교 항목: 호출어/명령·유효 표현을 서버가 확인하는 상세 기준과 지연·정확도. 방식의 새 채택은 없음 | 동료 GPT-Live 상세 명세, [ADR-005](ADR-005-session-events-timeouts.md) | TBD |
| DL-008 | 승인 문구 음성 제공 방식·실제 재생/종료 확인 | 동료 GPT-Live 상세 명세, [ADR-004](ADR-004-communication-voice.md) | TBD |
| DL-009 | 첫 반응 보호 → 필요 시 도움 동의 → GPT-Live 실시간 Cue → 도움 후 아동 반응 1회. 역할별 전달·기록·실패는 DL-CUE-01~04 적용 | FR-E07/E08/F09/F14/F15, [Cue 단계](architecture/session-state.md#cue-lifecycle) | 기존 정책 확정 반영 · 구현 검증 필요 |
| DL-010 | MVP는 GPT-Live 전사만 사용. 별도 STT·병렬 비교는 새 STT 조사·선정·연동·검증 일정 부족으로 보류 | [ADR-004 §2.14](ADR-004-communication-voice.md#mvp-transcription) | **Accepted · 2026-10-01 팀 회의** |
| DL-011 | 보호자 Google 소셜 가입·로그인 + Spring Security OAuth2 Client + Spring Session JDBC. 자체 비밀번호 가입·로그인과 외부 인증 플랫폼은 미채택 | [ADR-008 §3.2](ADR-008-auth-access-control.md#guardian-social-login) | 최신 사용자 확정 적용 · 실연동 별도 |

이전 상세 초안 v10/v11의 제안 수치와 구현 후보는 이 로그만으로 확정되지 않는다. 이번 문서 개정·정적 검토는 Java·DB·네트워크·GPT-Live/LLM 실연동이나 AI 품질·지연·부하 시험이 아니다.

## 저장소·기술·아동용 UI: ADR-001~003

| 항목 | 판단 또는 검증할 내용 | 기준 문서 | 상태 |
| --- | --- | --- | --- |
| DL-001-01 | 브랜치·커밋·PR 규칙에 대한 팀 승인 | [ADR-001](ADR-001-repository-git.md) | Proposed |
| DL-001-02 | GitHub Organization·Jira 연동·리뷰 1명·CI 조건 및 두 앱 빌드의 실제 설정 | [ADR-001](ADR-001-repository-git.md) | 적용 검증 필요 |
| DL-001-03 | `AGENTS.md`, `CLAUDE.md`, `aidlc-docs/` 배치와 AI 도구의 참조 동작 | [ADR-001](ADR-001-repository-git.md) | 적용 검증 필요 |
| DL-002-01 | Gradle 8.14+ 또는 9.x 중 실제 버전 선택 | [ADR-002](ADR-002-technology-defaults.md) | TBD |
| DL-002-02 | 첫 볼트에서 두 Boot·Spring Security/Session JDBC, springdoc `/v3/api-docs`, Testcontainers, 기존 Flyway의 두 앱/스키마 적용·각 앱 validate, JPA/Hibernate와 GPT-Live 주 WebSocket Adapter 호환성 확인 | [ADR-002](ADR-002-technology-defaults.md) | 검증 필요 |
| DL-002-03 | `/parent` REST 조회에 TanStack Query v5 적용; 상태별 Polling 중단·재시도 후 갱신·백그라운드 동작 확인 | [ADR-002](ADR-002-technology-defaults.md), [ADR-004](ADR-004-communication-voice.md) | Proposed·검증 필요 |
| DL-002-04 | `openai-java` 직접 사용 범위·SDK 버전과 GPT-Live 주 WebSocket 지원/Adapter 구현 검증 | [ADR-002](ADR-002-technology-defaults.md), [ADR-007](ADR-007-module-boundaries.md) | Proposed·검증 필요 |
| DL-002-05 | springdoc 검증 후 OpenAPI → TypeScript 생성 도구·범위·CI 계약 검사 결정 | [ADR-002](ADR-002-technology-defaults.md), [protocol](architecture/protocol.md) | 단계적 도입 권고·검증 필요 |
| DL-002-06 | Redis AI Cache MVP 보류 권고; 반복 요청량·적중률·지연 실측 후 재검토 | [ADR-002](ADR-002-technology-defaults.md) | Proposed |
| DL-003-01 | 화면 없는 기기를 대신하는 `/device`의 최소 글자·버튼, 준비/연결·마이크·활동 및 보호자 상태 카드 표현 | [ADR-003](ADR-003-child-device-ui.md) | 상세 TBD · 표시용 DB Enum 확대 없음 |
| DL-003-02 | UI Library 후보와 채택 여부 | [ADR-003](ADR-003-child-device-ui.md) | TBD |
| DL-003-03 | 대상 브라우저의 마이크 권한·WSS 음성 입출력·상태 보고·playbackMark와 실제 중단/재생 관찰 | [ADR-003](ADR-003-child-device-ui.md) | 검증 필요 |
| DL-003-04 | 실제 운영 기기 등록·페어링·인증 범위와 제품 시연의 실제/Mock 범위(Q-DEVICE-01/G-01) | [ADR-003](ADR-003-child-device-ui.md), [ADR-008](ADR-008-auth-access-control.md) | 운영 범위 후속 · 데모 범위 TBD |

ADR-003의 통신 계약은 ADR-004, 세션 정책은 ADR-005, 인증/인가 경계는 ADR-008을 따른다. 기기 준비와 아동 참여는 구분하며 참여·의미·활동 상태의 최종 판정은 서버 책임이다.

## 통신·음성: ADR-004

| 항목 | 판단 또는 검증할 내용 | 상태 |
| --- | --- | --- |
| DL-004-01 | 릴레이 B안의 선택은 적용. First Bolt에서 두 WSS 경계·출력 분할/전사/게이트·동일 음성 전달·회복·실제 재생과 지연을 시험 | 방향 확정 · 구현/POC 미실시 |
| DL-004-02 | 기기 `/ws` 음성·제어 메시지/오디오 형식·Envelope와 GPT-Live 이벤트 매핑을 구분 | 상세 TBD |
| DL-004-03 | playbackMark의 관측 주체·대상·위치 단위·송신 시점·늦은 보고·보고 불가·활용·공급자 맥락 반영, 질문/안내 전체 완료 범위 | 목적 확정 · 필드/단위/신뢰/실연동 TBD |
| DL-004-04 | Java/SDK의 주 WebSocket 시작·입출력/전사·취소/교정·오류/종료·정리 순서와 실제 지원 검증 | 검증 필요 |
| DL-004-05 | 앞 절에 보존한 별도 공급자 프로세스 대안의 재검토 조건은 Java 실패 증거·영향 확인. 현재 릴레이/Adapter와 두 앱 방향 유지 | 미채택 조건부 대안 · 자동 도입 없음 |
| DL-004-06 | GPT-Live 주 연결의 종료/정리·usage·공급자 저장 미사용 설정과 지원 범위 | TBD |
| DL-004-07 | 보호자 REST Polling 주기 최종값(1~2초 초기안), 조회 대상별 최종 상태 중단·백그라운드·오류 재시도 | TBD |
| DL-004-08 | GPT-Live 단일 전사 사용, 별도 STT의 서비스 내 병렬 비교와 추가 STT 비교 시험 보류. 사유는 일정·선정·연동·검증 부담 | **Accepted · 2026-10-01 팀 회의, §2.14** |
| DL-004-09 | GPT-Live 전사 수신·발화 조립·누락/불확실 표시·추천/판정 보류 계약과 합성·성인 시험 | 구현 검증 필요 · 기준 TBD |
| DL-004-10 | 중요한 전사 오류 반복 또는 근거 신뢰도 개선 필요 시, 일정 확보 후 별도 STT 후보·동일 음성/발화 대응·비교/실패 처리 재검토 | **후속 보류 · 날짜/담당 TBD** |
| DL-004-11 | 앱의 출력 분할/완료 기준·음성/전사 대응·VAD/noise gate, 버퍼 시간/크기/포화, 경량 의미 검사 기준(모델은 제브 가드레일, 기술명세서 v0.3 §7.7)·대체 안내/재생성 조건·한도 | 상세 TBD · 필수 검사/승인 전달 방향 적용 |
| DL-004-12 | 끼어들기/쉬기/종료/철회 취소·늦은 승인 무효, 폐기 발화의 모델 맥락·앱 교정과 새 출력 재검사 | 현행 책임 적용 · 취소/교정 실연동 TBD |

기준: [ADR-004](ADR-004-communication-voice.md)·[protocol](architecture/protocol.md). 장애 Timeout·Grace Period는 ADR-005, 기기/내부 서비스 인증은 ADR-008을 따른다. 키는 서버에 두고 서비스 상태와 공급자 상태를 분리한다. 실시간 원음은 임시 메모리에서 사용·폐기하며 DB·Outbox/Inbox·일반 로그·디스크에 우회 저장하지 않는다. 기존 승인 고정 음성 파일과 원음의 보존 범위를 구분한다.

## 세션·결과: ADR-005

| 항목 | 판단 또는 검증할 내용 | 상태 |
| --- | --- | --- |
| DL-005-01 | 시작 확인 무응답 `60초` 초기안 | Proposed |
| DL-005-02 | 앱이 정의한 유효 질문/안내 전체의 실제 기기 재생 완료 → `10초 → 재안내 1회`. 구간/전사/게이트/재생 대기는 무응답에 포함하지 않음. 재안내 이후 대기·미실시/종료 조건과 시작 참여 확인 적용 여부는 TBD(Q-TIME-01) | **2026-10-02 합의한 초기값·미측정 / 이후 조건 TBD** |
| DL-005-03 | 쉬는 중 타임아웃 `10분` 초기안 | Proposed |
| DL-005-04 | `/device` WSS 장애 시 자체 종료 `약 5초` 초기안, 릴레이 적용/실제 중단 확인 | Proposed·검증 필요 |
| DL-005-05 | 서버 Grace Period `약 6~7초` 초기안 | Proposed·릴레이 적용 검증 필요 |
| DL-005-06 | VoiceClient Offline 판정 `약 15초` 초기안 | Proposed |
| DL-005-07 | 진행 중 판정은 GPT-6-Luna. 제한 시간 2초 후보, 시간 초과·형식 오류는 재시도 없이 보류(기술명세서 v0.3 §5.8·§13). AI 출력 검사는 제브(Jev) 가드레일(§7.7) | 모델 2026-10-15 팀 결정 · 시간값 첫 볼트 측정 |
| DL-005-08 | ActivitySession 최대 길이 | TBD |
| DL-005-09 | 참여 이후 유효 기록이면 부분 결과. 활동 PARTIAL과 Core 결과 준비/실패·수동 재시도는 분리하며 늦은 결과로 활동을 부활시키지 않음 | 요구 적용 · 데이터/API/분기 상세 TBD |
| DL-005-10 | `first_response` 미실시 이후 다음 단계 진행 방식 | TBD |
| DL-005-11 | 아동 유효 종료 확인 즉시 ENDING A안·일반 출력/버퍼 취소·고정 승인 마지막 인사 1회 예외·실제 정리. 보호자 종료의 안내 정책과 구분 | 순서 적용 · 문구/제공 실패·정리 확인 상세 TBD |
| DL-005-12 | Core/Realtime 앱별 소유 범위 복구·메모리 음성 비복원·미확인 재생 보존, 종료/부분 활동 부활 금지 | 방향 적용 · 소유권/lease/이전 실행 차단·장애 시험 TBD |

합의한 무응답 `10초 → 재안내 1회`와 Cue `총 5초·조건부 추가 1회`는 구현·측정 대상이며 값이 없는 미결정으로 되돌리지 않는다. Cue 예산에는 게이트 대기가 포함되고 재요청으로 예산을 초기화하지 않는다. 나머지 제안 시간값·분기는 [ADR-005](ADR-005-session-events-timeouts.md)의 Pending 및 [실제 재생 기준 무응답](ADR-005-session-events-timeouts.md#no-response-wait)을 따르며 동료 GPT-Live 상세 명세 D-03·D-06과 통합 시험으로 확인한다. 승인 문구 자체는 동료 명세가 소유한다. 끼어들기만으로 ENDING을 만들지 않으며 상태·사유·미실시를 분리한다.

## 시연·배포: ADR-006

| 항목 | 판단 또는 검증할 내용 | 상태 |
| --- | --- | --- |
| DL-006-01 | HTTPS 방식: IP 인증서, 임시 Subdomain, localhost 중심 시연 중 선택 | TBD |
| DL-006-02 | Elastic IP 최종 사용 여부 | TBD |
| DL-006-03 | EC2 사양: 8GB급 초기 후보를 두 앱/DB·중계/버퍼/검사·Worker/Sender 합산 부하로 실측 | Proposed·미측정 |
| DL-006-04 | 시연 구성에 Neo4j 포함. 실제 배포·재시작 검증은 ADR-006 Pending에서 관리 | **Accepted · 2026-10-01 구성 확정** |
| DL-006-05 | Docker 이미지 빌드 위치: EC2 또는 Local/CI + Registry | TBD |
| DL-006-06 | 인증서 발급·갱신 방식 | TBD |
| DL-006-07 | 백업 파일의 실제 보관 위치와 복구 시험 | TBD·검증 필요 |
| DL-006-08 | 두 앱·기존 Flyway 단일 적용/각 앱 validate·상태 확인/복구를 포함한 `deploy.sh` 세부 절차 | TBD |
| DL-006-09 | 최종 시연 장소의 기기 WSS·서버 GPT-Live 주 연결·릴레이/게이트·취소/실제 재생과 지연/장애 시험 결과 | 검증 필요 |
| DL-006-10 | Core/Realtime 포트·공개 허용/내부 차단 경로·키/내부 인증 비밀 주입/회전, 스키마/쓰기 역할·마이그레이션 이력/순서 | 상세 TBD |

기준: [ADR-006](ADR-006-demo-deployment.md). EC2 한 대·Docker Compose·별도 PostgreSQL 컨테이너·볼륨/백업·DB 비공개·수동 배포 등 기본 구조와 위 미결정 값을 구분한다. 두 앱이 공유 호스트·DB·외부 공급자의 장애나 자원 경쟁을 없애지는 않는다.

## 모듈 경계: ADR-007

| 항목 | 판단 또는 검증할 내용 | 상태 |
| --- | --- | --- |
| DL-007-01 | Core/Realtime 책임 배치를 따르는 최종 모듈·Gradle 프로젝트 목록 | 상세 TBD |
| DL-007-02 | `content` 독립 모듈 채택 여부 | TBD |
| DL-007-03 | `knowledge` 저장소는 Core 경계의 Neo4j. 그래프 모델·Adapter 구현 검증은 후속 상세 설계 | **Accepted · 2026-10-01 선택 확정** |
| DL-007-04 | Activity Port의 이름·범위와 호출자 소유 Port/원격 Adapter 상세 | TBD |
| DL-007-05 | Realtime Session Event Queue 구현 | TBD |
| DL-007-06 | 앱 내부 모듈 Event 구현: Spring Application Event 또는 자체 Queue. 앱 간 영속 전달은 ADR-010 적용 | 내부 구현 TBD · 원격 전달 방향 적용 |
| DL-007-07 | `ai/prompts/` 실제 경로와 Prompt Version 방식 | TBD |
| DL-007-08 | 앱/모듈 소유권·공통 계약·scan/scheduler 경계를 포함하는 ArchUnit 규칙 상세 | TBD |
| DL-007-09 | `openai-java` 등 외부 SDK 허용 패키지(`voice/provider`, `ai/provider`)와 ArchUnit 검사 | TBD |
| DL-007-10 | 앱 소유 로컬 Transaction 경계의 상세/예외. 상대 Repository·도메인 직접 쓰기는 허용하지 않음 | 상세 TBD |

기준: [ADR-007](ADR-007-module-boundaries.md). Activity 상태 변경을 세션 이벤트 경로로 모으고 상태 처리기에서 긴 외부 호출을 기다리지 않는다. Realtime은 구간/전사/게이트·릴레이·재생 관측, Core는 결과 Worker·공통 지식과 승인 콘텐츠 원본을 소유한다. 공통 코드는 최소 계약·타입/오류·시간 범위이며 Entity/Repository/도메인 Service·Boot 전체 설정 공유를 허용하지 않는다. 도메인 소유권·순수 상태 머신·Adapter/Fake·CI 유료 호출 제외 원칙은 유지한다.

## 인증·접근 제어: ADR-008

| 항목 | 판단 또는 검증할 내용 | 상태 |
| --- | --- | --- |
| DL-008-01 | Google 소셜 가입·로그인, Spring Security OAuth2 Client + Spring Session JDBC. 자체 비밀번호 가입·JWT 서비스 인증·Auth0/Keycloak 플랫폼은 현재 미채택 | 최신 사용자 확정 적용 · 실연동 별도 |
| DL-008-02 | 보호자 인증 만료 시간과 Cookie 이름/경로/수명 | TBD |
| DL-008-03 | Core Session JDBC의 기존 Flyway 테이블·재시작·만료·Logout 즉시 무효화와 Principal/SecurityContext 직렬화 | 상세 TBD·검증 필요 |
| DL-008-04 | CSRF Token 전달 방식 | TBD |
| DL-008-05 | 필수 동의 철회 시 기존 로그인 Session 처리 여부. 신규/지연 자료 이용·저장 차단과 STOP/안전 정리는 별도 적용 | Session 상세 TBD |
| DL-008-06 | 이전 기기 발급 수명 Pending은 대체 표로 종결. 고정 ID 데모와 운영용 기기 인증의 경계를 적용 | 이전 Pending 종결 · 운영 인증 DL-008-10 |
| DL-008-07 | 이전 기기 발급 Endpoint Pending은 대체 표로 종결. 사전 시드→대기 WSS→상태 보고/카드→보호자 시작 push의 메시지/조회 상세 | 방향 적용 · 현행 메시지/API TBD |
| DL-008-08 | 고정 ID의 실제 값/형식·시드와 현재 연결·중복/교체/재연결·활동 결합 규칙 | 고정 ID 방향 적용 · 상세 TBD |
| DL-008-09 | 환경별 Origin Allow List | TBD |
| DL-008-10 | 다중 사용자 운영 전 실제 Device Authentication 설계 | 추후 결정 |
| DL-008-11 | 실제 기기 Pairing 방식. QR claim 자동 채택 없음 | 추후 결정 |
| DL-008-12 | Google 앱·Client ID/Secret·권한·환경별 Redirect URI·속성 매핑과 실제 공개 조건/콜백 검증 | 등록·실연동 필요 |
| DL-008-13 | Google 계정과 내부 Guardian 매핑·최초 가입·필요 서비스 동의, 중복 가입·동시 콜백·공급자 취소/오류 처리 | 상세 계약 TBD·검증 필요 |
| DL-008-14 | Google 범위의 계정 연결·해제·회원 탈퇴와 공급자 토큰 보존/폐기·Authorized Client 실제 저장 확인. 이메일만으로 자동 병합하지 않음 | TBD·검증 필요 |
| DL-008-15 | OAuth state·사용 시 OIDC nonce/ID Token 검증, Session ID 교체·내부 Principal 직렬화·로그아웃·CSRF 및 Nginx 콜백 전달 | 구현 검증 필요 |
| DL-008-16 | 앱 간 서비스 인증·비밀 주입/회전·검증 주체/대상/행위·현재 권한 전달·공개 내부 경로 차단 | 방향 적용 · 상세 TBD |
| DL-008-17 | Core 장애 중 보호자 제어(G-02), 철회 효력 시각·확인 후 송신/저장 경합과 외부 진행 작업 처리(G-03) | 미해결 계약 |

기준: [ADR-008](ADR-008-auth-access-control.md)·[auth](architecture/auth.md). 공급자 토큰과 서비스 세션, 인증과 현재 인가를 구분한다. 기기 고정 ID는 운영 인증의 증거가 아니며 Origin·허용 ID·현재 연결·활동/권한 검사를 유지한다. 앱 간 내부망 또는 전달 Header만 신뢰하지 않고 수신 서비스 인증을 적용한다. 최신 권한을 확인할 수 없으면 새 이용을 보류/차단하되 STOP/안전 정리는 막지 않는다. 철회 Outbox 알림만으로 확인-송신/저장 경합이 해결됐다고 기록하지 않는다.

## 실행 경계: ADR-009

| 항목 | 현행 결정 또는 확인할 내용 | 상태 |
| --- | --- | --- |
| DL-009-01 | 실행 특성·상태 소유권으로 Core API Spring과 Realtime Activity Spring 분리. 부모/아동·AI/비AI 분리가 아님. Worker는 Core, Sender/Inbox Processor는 각 앱 내부 | 방향 적용 · 실제 실행/격리 검증 전 |
| DL-009-02 | Monorepo 내 두 실행/빌드, 최소 공통 계약, 각 Boot scan/scheduler 격리의 실제 경로·프로젝트/Artifact/이미지·CI Task | 상세 TBD |
| DL-009-03 | PostgreSQL 물리 1개·앱별 스키마/쓰기 역할, 기존 Flyway의 소유 마이그레이션 작성/단일 배포 적용·각 앱 validate | 방향 적용 · 이름/권한/이력/실행/실패·호환 배포 상세 TBD |
| DL-009-04 | Core는 자신의 Job/전달 복구, Realtime은 소유 진행 음성 활동 정리·메모리 비복원. 현재 run의 유효 결과 참조는 보존 | 방향 적용 · 소유권/lease·장애 시험 TBD |
| DL-009-05 | 공유 호스트/DB·Core 경유 제어/권한·콘텐츠 의존, 합산 메모리/실행기/DB pool·외부 호출/릴레이 부하, 향후 활동/연결 소유자 라우팅 | 미해결 의존·용량/확장 상세 TBD |

기준: [ADR-009](ADR-009-execution-boundary.md)·[시스템 아키텍처](architecture/system-architecture.md). 두 앱 선택을 모든 기능의 독립 가용성·처리량 보장으로 표현하지 않는다. 현행 원격 명령/쓰기·도메인 소유권을 유지하며 다른 앱의 Repository를 직접 수정하지 않는다.

## 영속 전달: ADR-010

| 항목 | 현행 결정 또는 확인할 내용 | 상태 |
| --- | --- | --- |
| DL-010-01 | 즉시 명령/조회·원격 목표 등록은 내부 HTTP. 결과 요청/완료·정책 변경은 업무 변경+Outbox 로컬 커밋→내부 HTTP→영속 수신 커밋 뒤 ACK. 초기 Broker 없음 | 방향 적용 · 구현/통합 시험 전 |
| DL-010-02 | 업무 반영+중복 확인을 함께 커밋하거나 Inbox를 먼저 영속 저장한 뒤 ACK. Inbox 후처리는 업무 변경+처리완료 원자 반영·미처리 복구. ACK 유실/중복 허용·같은 업무 효과 중복 방지 | 계약 적용 · 트랜잭션/제약 상세·장애 시험 TBD |
| DL-010-03 | 요청·이벤트·실시간 op·결과 run·입력/상태/정책 버전의 역할 분리, 현재 결과 실행 확인·이전 run 차단·늦은 결과로 활동 부활 금지 | 원칙 적용 · 실제 필드/DTO/순서·충돌 표현 TBD |
| DL-010-04 | Core Worker의 결과·목표 생성/검사·체크포인트·Realtime 원격 등록. 응답 유실은 같은 후보/입력 버전/요청으로 재확인하고 성공 AI 단계 재생성 금지 | 방향 적용 · 등록 멱등/원자성·실연동 TBD |
| DL-010-05 | 전달 재시도·LLM 기술 오류·내용 수정·보호자 수동 실행을 분리, Sender/Processor/Worker 선점·lease/fencing·동시 한도·backoff/장기 장애 처리 | 상세 TBD · 기존 제안값은 해당 상태 유지 |
| DL-010-06 | 최소 참조 전달·중복/폐기 표식·스냅샷/메시지/체크포인트 보존·삭제, 늦은 재전송이 자료를 복원하지 않도록 현재 정책 검사 | 원칙 적용 · 기간/정리 순서·G-03 상세 TBD |
| DL-010-07 | 유효 부분 결과의 준비/실패·수동 재시도/Job 재사용 표현과 실제 제공 범위 근거 사용 | 요구 적용 · 데이터/API 상세 TBD |

기준: [ADR-010](ADR-010-durable-delivery.md)·[결과 파이프라인](architecture/result-pipeline.md)·[데이터 모델](architecture/data-model.md). 수신 ACK는 업무 처리·AI 성공·실제 재생 완료가 아니다. 영속 전달과 결과 생성에 필요한 구현·운영/부하 시험은 미실시다.

## 첫 연결과 지연 POC의 상태

기준은 [first-bolt](architecture/first-bolt.md)의 작은 합성·성인 수직 흐름과 §6.1이다. 두 앱 → Google/Guardian·동의 → 고정 ID 대기 WSS/상태 카드·보호자 시작 push → GPT-Live 릴레이 → 분할/전사/검사·승인 음성 → playbackMark → 차단 후 회복·즉시 ENDING·앱별 복구를 연결한다. 단일 전사·서버 키·역할별 정보 제한을 유지한다.

| audit §5.3 POC | 확인할 내용 | 현재 상태 |
| --- | --- | --- |
| 출력 구간 분할 | 짧은 쉼·긴 답변·조각 도착 지연·멈춤 뒤 발화를 전체 완료로 오인하지 않는지 | 미실시 · 구간/전체 완료 기준 TBD |
| 음성·전사 대응 | 부족·누락·늦음·오대응에서 미검사 음성 폐기/대체 안내와 대응 대기 | 미실시 · 매핑/실패 상세 TBD |
| 승인 후 실제 첫 재생 | 발화 종료 관측→구간/전사 확보→검사→전달→기기 첫 재생의 구간별 시간. 안전 대체 안내와 유용 답변 첫 재생을 구분 | 미실시 · 관측/시계 대응·표본/p50/p95·수용 기준 TBD |
| 취소·늦은 승인 | 끼어들기·쉬기·종료·철회의 서버 버퍼/기기 큐·승인 무효와 실제 중단/늦은 보고 | 미실시 · 실제 재생/취소 계약 TBD |
| 차단 이후 대화 | 폐기 발화의 모델 맥락 잔존·앱 교정/대체 안내·새 출력 재검사, 교정 ACK와 재생 승인 차이 | 미실시 · 회복 품질/실연동 TBD |
| 무응답·Cue 예산 | 전체 질문/안내 실제 재생 후 무응답 10초·재안내 1회. Cue 최초 요청→실제 시작 5초에 게이트 대기 포함·재요청 시각 초기화 금지 | 초기값/예산 유지 · 달성 미측정 |

기반/실연동·접근/권한·중복/응답 유실·종료/복구·영속 ACK/멱등·철회/삭제·자원/부하 시험도 first-bolt 및 담당 ADR의 계획을 따른다. 표본이나 지연 수치를 새로 만들지 않으며 미관측 시각/재생·없는 완료 이벤트를 성공 근거로 채우지 않는다. 첫 2주 일정과 성인 2명·별도 기기 시험, Polling 1~2초·8GB급 등의 기존 후보는 제안/측정 대상으로 남긴다.

**미검증**은 증거가 없다는 뜻, **미구현**은 후속 구현 범위라는 뜻, **기능 제외**는 별도 범위 결정이다. 현재 첫 연결·POC·장애/성능은 미실시다. 전체 결과/Worker·목표/연습·Cue·전사 수정/검수·운영 도구의 후속 구현을 요구사항 삭제로 바꾸지 않는다. 유효 기록의 부분 결과와 필수 게이트/안전 정리는 첫 볼트 후속 항목이라는 이유로 제외하지 않는다. 제품 실제/Mock 선택(G-01), G-02/G-03은 미해결 계약이다.

## 기록 방법

후속 결정은 audit 우선순위와 담당 ADR의 Decision·Pending·상태를 먼저 갱신하고 해당 상세 설계·시험 증거에 연결한다. 이 로그의 해당 행에는 선택 결과·근거·결정일·확인자 또는 실제 실험 결과 링크를 기록한다. 대체된 선택은 이력으로 남기고 새 요약을 쌓지 않는다. 팀 합의 전 수치·예시는 `Proposed/TBD`, 합의된 초기값은 `미측정`, 실연동 증거가 없는 항목은 `검증 필요/미실시`를 유지한다.
