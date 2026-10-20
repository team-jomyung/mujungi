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
