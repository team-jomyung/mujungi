# 업무 개요 (Business Overview)

> 기준(혼합): **이번 갱신** = 커밋 `9bcc706`(브랜치 `unit/u11-judgment-turn` = main `7e30ee8` + U11 지시서, 앱 소스는 main과 같음), 2026-10-16 집중 스캔(인텐트 `261016-u11-judgment-turn`). 갱신한 곳은 realtime-activity · contract-model `ws` · `event` · `contracts/`에 걸친 문장이며 「(2026-10-16 갱신)」으로 표시했다.
> **그 밖의 문장** = 커밋 `72d7034`(2026-10-15 전체 재스캔) 그대로다. 그 뒤 main에 들어온 U8 · U9의 core-api · frontend 쪽 변경은 다시 확인하지 않았다(STALE).
> 이 문서는 지금 코드가 하는 일만 적는다.

## 서비스 목적

무중(mujoong)은 6~12세 아이의 화용(대화 사용) 능력을 돕는 AI 음성 대화 서비스다. 보호자가 웹 화면에서 활동을 시작하면, 집에 둔 아이 기기(브라우저 화면)가 실시간 음성 AI와 대화하고, 끝난 뒤 보호자는 결과 · 기록 · 리포트를 본다.

## 사용자와 접점

| 사용자 | 접점 | 코드 위치 |
|---|---|---|
| 보호자 | 웹 `/parent/*` 화면 17개(로그인 · 동의 · 아이 정보 · 홈 · 기기 등록 · 자유 대화 · 연습 · 진행 중 · 종료 · 기록 · 리포트 · 중단 표현) | `frontend/src/parent/`, `frontend/src/app/parent/` |
| 아이 | 기기 웹 `/device`(D0 대기 화면 · T19 대화 화면), 마이크 · 스피커 | `frontend/src/device/`, `frontend/src/app/device/` |
| 운영자 | demo 단일 호스트 운영(수동 RUNBOOK), CI/CD 워크플로 | `infra/`, `.github/workflows/` |

## 핵심 기능 (코드에 있는 것)

1. **계정 · 동의** — Google OAuth 로그인(dev 환경은 dev 로그인), 정책 동의, 탈퇴(삭제 요청 → 두 앱 삭제 참여자). `core.identity` · `core.consent` · `core.account`.
2. **아이 정보 · 중단 표현** — 아이 프로필 1명, 아이가 대화를 멈추고 싶을 때 쓰는 말(중단 표현) 목록. `core.child`. Realtime은 세션 시작 때 받은 목록을 `Plan.stopExpressions`로 고정해 `StopIntentJudge`(규칙 판정, 기본 "무중아 그만할래" + 보호자 추가)로 확정 전사마다 판정한다(2026-10-16 갱신).
3. **기기 등록** — 등록 코드 → 기기 부트스트랩 · claim · 기기 토큰. 기기 연결 상태(OFFLINE · CONNECTED · UNSTABLE · DISCONNECTED · UNRESPONSIVE)를 보호자 화면에 보여 줌. `core.device` · `realtime.gateway`.
4. **자유 대화 활동(FREE_TALK)** — 보호자가 시작 → Realtime이 기기에 세션을 열고 음성 공급자와 중계 → 침묵 · 아이 중단 의도 · 보호자 종료로 끝남. 출력 게이트가 공급자 출력을 문장 단위로 걸러 승인된 것만 재생. 참여 확인(WAITING_CHILD)은 `JoinTracker` 규칙 분류(ACCEPT · REFUSE · TOPIC_REFUSAL · UNCLEAR)로 한다(2026-10-16 갱신).
5. **연습 활동(PRACTICE)** — 보호자가 연습 목표를 고르고(목표 선택 · 즐겨찾기 · 복원), Core가 텍스트 AI로 상황(시나리오)을 만들어 Realtime에 넘김. Realtime 연습 런타임(U5b `RuntimePracticeExtension`, 출력권 소유 `controlsOutput()=true`)이 세션 실행기의 확장점 포트(`PracticeSessionExtension`)에 붙어 판정 · 단서를 돌림. `core.practice` · `core.textai` · `realtime.practice`.
6. **결과 · 기록** — 세션이 끝나면 Realtime이 대화 기록 스냅숏을 이벤트(`SessionRecordCaptured`)로 보내고, Core 결과 작업이 결과 수준 계산 · AI 결과 문장 · 금지 표현 검사 · 생활 팁을 만든다. 보호자는 기록 목록 · 상세에서 본다. `core.result` · `core.record`.
7. **리포트** — 기간 미리보기 → 리포트 생성(텍스트 AI) → PDF 내려받기 · 저장 · 폐기. `core.report`.
8. **웹 푸시 알림** — 결과 준비 등 이벤트를 VAPID 웹 푸시로 보냄(보호자 서비스 워커는 `/parent/push-worker`). `core.notification`.
9. **(2026-10-16 갱신) 새 연습 흐름의 기반(U8 · U9, main 병합분)** — 아직 업무 흐름에 연결되지 않은 기반만 있다.
   - U8 계약 · 판 리소스: Luna · 제브 구조화 입출력 스키마 9종(`contracts/schemas/ai-*.schema.json` · `guardrail-*.schema.json`), 판정 선택지 판 리소스 `judgment/option-sets-v1.json`(J1 ~ J6), 가드레일 정책 `guardrail/policy-v1.json`, 세션별 판 고정 `LiveContentRegistry`, 승인 발화(`approvedSpeech`, 음성 파일 없는 승인 문구), 새 시간 키 7개(`timing.md` 13절).
   - U9 Luna 호출부: realtime `RealtimeLunaClient`(목적 `JUDGMENT_LIVE` 하나, OpenAI Responses API `POST /v1/responses`). 키(`OPENAI_API_KEY`)가 있을 때만 빈이 생긴다. 이 호출부를 부르는 업무 코드(판정 턴 · `JudgeClient`)는 아직 없다 — U11의 몫이다.

## 업무 상태 어휘 (계약 정본: `contract-model`)

- 세션 상태 `SessionStatus` 12값: REQUESTED → PREPARING → DEVICE_ACK → WAITING_CHILD → ACTIVE → CLOSING → 끝 상태(ENDED · ENDED_UNCONFIRMED · ABORTED · DECLINED · FAILED), 그리고 해석 불가 값 UNRECOGNIZED.
- 종료 까닭 `EndReason`: GUARDIAN_STOP · CHILD_STOP · SILENCE · DISCONNECT · CONSENT_WITHDRAWN · UNCONFIRMED · DECLINED · FAILED.
- 실패 코드 `FailureCode`: DEVICE_NO_ACK · SCENARIO_GEN_FAILED · PROVIDER_UNAVAILABLE · SERVER_RESTART.
- 활동 종류 `ActivityType`: FREE_TALK · PRACTICE.
- (2026-10-16 갱신) 기기 화면 보기 `View`: LISTENING · THINKING 등. '생각 중'은 `SESSION_STATE{view: THINKING}`로 보낸다.

상태 전이 · 시간 값의 세부는 [architecture.md](architecture.md)의 상호작용 다이어그램과 [api-documentation.md](api-documentation.md)를 본다.

## 현재 업무상 한계 (코드 사실)

- **AI 운영 공급자 연결이 부분적이다.** (2026-10-16 갱신) realtime에는 U9 Luna 호출부가 생겼지만 판정 턴이 이를 부르지 않는다. 레거시 연습 판정(`EvaluationProvider`) · 단서(`CueProvider`) 기본 빈은 여전히 항상 실패한다. core 쪽 텍스트 AI(상황 생성 · 결과 · 리포트)는 72d7034 기준 기본 빈이 항상 실패였고, U9의 `CoreLunaClient`(`contracts/deviations.md` U9-a)가 그 뒤 들어왔으나 이번 스캔은 core를 다시 보지 않았다. 자세한 내용은 [code-quality-assessment.md](code-quality-assessment.md)의 TD-1.
- 음성 공급자 연결은 있으나 선 형식(모델 `gpt-live-1`, `session.*` 메시지)은 프로젝트가 정의한 것이다(TD-2). (2026-10-16 갱신) 응답 생성 확인(`response.created` 류) 사건이 없어 "지시 뒤에 온 응답"을 공식 신호로 가를 수 없다(U11-R1).
- **판정 턴은 아직 없다**(2026-10-16 갱신). 지금 '공급자 출력 보류'는 응답을 **취소**하는 장치뿐이고, 보류된 출력의 전사를 따로 남기는 경로 · 판정용 시계 · 판정 저장 표(`judgment_live`) · 보류 규칙 설정 키(`mujoong.judgment.min-confidence` · `min-margin`)가 없다.
- 지식 그래프(Neo4j)는 인터페이스만 있고 꺼져 있다(TD-3).
- 세션 안내 문구 음성 6종은 합성음 자리 파일이다(`infra/seed-audio/`).

## 업무 로직 확인 수준

- 2026-10-15 전체 재스캔은 패키지 · 경계 수준이었다.
- 2026-10-16 집중 스캔은 realtime 세션 실행기(`SessionExecutor`)의 공급자 사건 처리 · 확장점 신호 · 종료 의도 · 생각 중 · 게이트 · 공급자 송신 구간, 확장점 포트, 게이트 · 공급자 클라이언트, Luna 호출부, 판 리소스, 판정 관련 계약 문서를 깊게 봤다. core-api · frontend의 업무 로직 본문은 여전히 얕다([reverse-engineering-timestamp.md](reverse-engineering-timestamp.md)의 `shallow.paths`).
