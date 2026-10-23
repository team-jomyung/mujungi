# API Documentation

> 기준: 커밋 570a625. 이번 스캔은 `contracts/*.yaml` 본문을 정독하지 않아 **엔드포인트 수는 세지 않았다**. 아래는 계약 파일과 구현 위치의 대응이다.

## 외부 · 내부 접점

| 접점 | 계약 파일 | 구현 위치 |
|---|---|---|
| 보호자 REST | `contracts/parent-api.yaml` | core-api 컨트롤러(기능 패키지 `web`) |
| 기기 REST + WebSocket | `contracts/device-api.yaml`, `contracts/device-ws.asyncapi.yaml` | realtime-activity `gateway` |
| 내부 HTTP(두 앱 사이) | `contracts/internal-api.yaml` | `internalhttp`(core-api), realtime `web` |
| 이벤트(발신함 · 수신함) | `contracts/events.asyncapi.yaml` | 양쪽 앱 `messaging` |
| 외부 AI | `contracts/ai-io.md` | GPT-Live(realtime `voice` · `luna`), Luna, 제브 가드레일(`guardrail`), 지식 그래프 |

## 공통 계약 문서

- 오류: `contracts/errors.md` 표 ↔ `backend/contract-model/.../error/ErrorCode.java` enum(`ContractFilesTest` · `ErrorCodeTest`가 대조). 응답 봉투는 `{code, message, traceId}`이며 message는 code별 고정 문장이다. 받는 쪽은 모르는 code를 일반 오류로 처리한다. 보호자에게는 `ErrorCode#forGuardian()`으로 번역한다.
- 시간 값: `contracts/timing.md` ↔ 두 앱 `application.yml`의 `mujoong.timing.*` ↔ `frontend/src/shared/timing/timing-defaults.json`.
- 포트 · 설정: `contracts/ports.md`. 편차: `contracts/deviations.md`. 그 밖: `contracts/schemas/`, `knowledge-graph.md`, `u14-compatibility.md`.

## 이번 의도와의 관계

- 이름 정리는 이 계약 값(오류 코드 · 설정 키 · 시간 값 · 경로)을 바꾸지 않는다. 설정 레코드를 쪼갤 때도 `ConfigKeyContractTest`가 키와 바인딩 prefix를 대조하므로 같아야 한다(`code-structure.md`의 문자열 참조 표).
- 계약에 닿는 변경은 같은 작업에서 `contracts/`도 고쳐 CI 전 작업이 돌게 한다(R-M10).
- 클래스 이름이 계약 문서에 나오는 곳은 `contracts/deviations.md` 52행 한 곳이다.

## 집중 조사 보강 — 통합 진단의 접점

| 순서 | 접점 | 확인한 구현 / 제약 |
|---|---|---|
| 기기 등록 | POST /api/device/bootstrap → GET /api/device/bootstrap/status → POST /api/device/bootstrap/claim | deviceApi.ts. bootstrap 및 기기 토큰은 HttpOnly 쿠키로 관리 |
| 보호자 등록 | POST /api/device/register, 본문 code | 기존 skeleton 도구의 준비 경로 참고. 보호자 세션, Origin, CSRF 필요 |
| 로컬 로그인 | POST /api/dev/login → GET /api/session/csrf | 기존 skeleton 준비 경로 참고. 로컬 시험 로그인만 사용하며 실제 인증 정책 대체 근거 아님 |
| 시작 준비 | GET /api/home | canStart 및 기기 안정·동의·진행 세션 확인 필요 |
| 자유대화 시작 | POST /api/activities/free | ActivityController: 보호자+CSRF, {idempotencyKey,context?}, 202 {sessionId,status:REQUESTED}. 동의·기기 안정 등은 서비스가 검사 |
| 기기 연결 | /ws/device, HELLO→HELLO_OK, HEARTBEAT | 실제 deviceSession은 START_SESSION 승인 음성 prefetch 뒤 DEVICE_READY를 보내고 mic=OK일 때 입력 창을 엶 |
| 음성 | 0x01 입력 / 0x02 출력 | PCM16LE 24kHz 모노. 입력 480표본/20ms, 5바이트 머리 포함 965바이트. AudioFrameCodec 및 프런트 codec 일치 |
| 부모 조회 | GET /api/sessions/{id}?sinceSeq=n | fetchLiveView. 서버 내부 /internal/sessions/{id}/view가 아이 전사와 FINAL·표시 승인 AI만 반환 |
| 재생 증거 | PLAYBACK_MARK | playbackId, status, observedAt. observedAt은 기기 시계. STARTED/COMPLETED/FAILED/STOPPED를 승인 안내와 GPT 출력 source별로 구분 |
| 종료 | POST /api/sessions/{id}/end | {requestId}, CSRF. 장시간 유료 세션이 남지 않도록 종료 및 최종 상태 확인 필요 |

등록/로그인 세부 서버 보안 구현과 계약 YAML 전체는 정독하지 않았다. 준비 단계에서 기존 등록·진행 세션을 먼저 조회하고 같은 사용자의 기기를 무조건 재등록하지 않아야 한다. 브라우저 프로필/쿠키·등록 코드·CSRF를 보고서에 출력하지 않는다. 가상 마이크도 실제 /device의 켜기 클릭·HTTPS·마이크 허용·AudioWorklet·현재 세션 입력 창을 통과해야 한다.

## 집중 조사 보강 — 261020-service-guardrail-replac

기준: 2026-10-20, 커밋 2e3d7b0. 이전 절은 이전 의도의 기록으로 보존했다. 이번 검증 범위는 timestamp의 개별 파일에 한정한다.

새 service_contract.json은 artifact_kind=IMPLEMENTATION_CONTRACT_NOT_EXECUTABLE_CONFIG이며 application_fields_are_not_provider_api_fields=true다. 현재 공급자 session.start는 {type,session:{model,instructions,audio:{format:{type,rate}}}}이며 서버 내부 session_id·guardian_id·policy_version 등을 별도 공급자 필드로 추가할 근거가 아니다. 기존 append는 {type,event_id,delegation_id:null,content}; 500토큰 제한은 append 계약이고 새 초기 지시문 전체에 그대로 적용되는 제한으로 일반화하지 않는다.

현재 JEV는 POST /v1/systemone에 {model,state:{candidate,context},questions:{guardrail:{type,instructions,criteria}}}를 보내고 answers.guardrail.choice/probabilities를 읽는다. 7개 범주+PASS, threshold, 실패 차단 계약을 ZIP의 제안 4개 축으로 임의 치환하지 않는다. ZIP의 optional_output_guard는 DESIGN_ONLY_NOT_CONNECTED다. contracts/ai-io.md 관련 절만 검토했으며 전체 API를 재조사하지 않았다.

