# 업무 개요 (Business Overview)

> 저장소 `mujungi` · 기준 커밋 `3748083` · 집중 스캔(`kind: partial`). 분석 범위는 `reverse-engineering-timestamp.md`의 `## Scope of Analysis`를 따른다. 이 문서는 Realtime 쪽(`backend/realtime-activity`)과 그 공유 계약을 중심으로 쓰였고, Core(`backend/core-api`)와 프런트엔드는 경계만 훑었다.

## 1. 서비스가 하는 일

무중(Mujoong)은 6 ~ 12세 아이의 화용 능력(상황과 상대에 맞게 말하기)을 집에서 돕는 AI 음성 대화 서비스다. 진단이나 치료 효과 판단은 하지 않는다.

| 사용자 | 접점 | 하는 일 |
|---|---|---|
| 보호자 | 폰 웹 `/parent` (Core API) | 로그인 · 동의 · 아이 정보 등록, 기기 등록, 활동(자유대화 · 연습) 준비 · 시작 · 끝내기, 진행 관찰, 기록 · 리포트 열람, 계정 삭제 |
| 아이 | 음성 기기(현재는 웹 클라이언트 `/device`) | 기기 「켜기」만 조작하고 말로 참여한다. "무중아 그만할래"로 끝낸다 |
| AI '무중' | 외부 음성 공급자(GPT-Live 계열, Realtime이 중계) | 실시간 음성 대화. 응답은 출력 게이트를 거쳐 기기로 나간다 |

구조는 두 Spring 앱(Core API · Realtime Activity) + Next.js 한 앱 두 접점 + PostgreSQL 한 대(`core` · `realtime` 스키마)이며, 두 앱은 내부 HTTP와 Outbox/Inbox 이벤트로만 이야기한다(자세한 구조는 `architecture.md`).

## 2. Realtime Activity의 업무 책임

Realtime은 "활동 세션이 실제로 돌아가는 동안"을 책임진다. Core가 세션 패키지(아이 맥락 · 동의 스냅숏 · 승인 문구 등)를 만들어 넘기면 Realtime이 다음을 수행한다.

1. **기기 연결 유지** — 기기 WSS(`/ws/device`) 핸드셰이크, Core를 통한 기기 토큰 검증, HELLO 인계, 하트비트 · 40초 무음 끊김 판정, 연결 상태를 Core에 10초 주기로 보고.
2. **세션 상태 기계(SessionLifecycle)** — 세션 하나를 세션당 직렬 실행기로 돌리며 상태 전이를 DB에 기록하고 `SessionStateChanged` 이벤트를 Outbox로 낸다.
3. **음성 중계** — 기기 입력 오디오를 공급자로 흘리고, 공급자 응답을 출력 게이트(종료 의도 · 시드 사전 · 구간 버퍼)를 거쳐 기기로 재생시킨다.
4. **종료 의도 판정** — 아이 발화에서 "그만"류 표현을 판정한다(`StopIntentJudge`).
5. **전사 · 녹음 · 지연 기록** — 비동기 기록기로 전사 · 지연(LATENCY_TURN) · 끊김 구간을 남기고 PCM 녹음 파일을 쓴다.
6. **진행 조회 제공** — Core가 보호자 진행 화면(12)용으로 묻는 세션 view(말풍선 · 끊김 구간 등)를 돌려준다.
7. **삭제 참여** — 계정 삭제 시 Realtime 쪽 데이터를 지운다(현재 참가자 코드만 있고 처리기 미연결).

## 3. 핵심 업무 개념 (용어)

| 용어 | 뜻 | 정의 위치 |
|---|---|---|
| 세션(ActivitySession) | 아이 한 번의 활동(자유대화 또는 연습) 실행 | `realtime.realtime_activity_session` 표, `contract-model` `SessionStatus` |
| 세션 상태 11종 | PREPARING · DEVICE_ACK · WAITING_CHILD · ACTIVE · CLOSING · ENDED · ENDED_UNCONFIRMED · DECLINED · ABORTED · FAILED (+ Core 사본 전용 REQUESTED, `UNRECOGNIZED`) | `contract-model/.../status/SessionStatus.java` |
| 종료 사유 8종 | GUARDIAN_STOP · CHILD_STOP · SILENCE · DISCONNECT · CONSENT_WITHDRAWN · UNCONFIRMED · DECLINED · FAILED | `EndReason.java` |
| 실패 코드 | DEVICE_NO_ACK · SCENARIO_GEN_FAILED · PROVIDER_UNAVAILABLE · SERVER_RESTART | `FailureCode.java` |
| 세션 패키지 | Core → Realtime 시작 요청 본문(승인 문구 `approvedContent` 포함) | `contracts/schemas/internal-session-start-request.schema.json` |
| 승인 문구(approved content) | Core가 미리 승인한 재생 문구(마무리 · 대기 채움 등). Realtime은 내용 대신 재생만 맡음 | 위 스키마 66 ~ 80행 |
| 출력 게이트 | 공급자 응답을 기기로 보내기 전 막거나 통과시키는 관문 | `realtime.gate` |
| 결과 입력 스냅숏 · `SessionRecordCaptured` | 세션 종료 뒤 Core 결과 처리용 스냅숏과 그 이벤트 | `contract-model/.../payload/SessionRecordCaptured.java` (Realtime 생산 코드 없음) |

## 4. 현재 완성도와 U4b의 위치

유닛 단위로 개발된다. Realtime 쪽은 U1(워킹 스켈레톤 · 세션 경로)과 U3(음성 대화)가 main에 있고, U4b가 SessionLifecycle을 완성한다(정본 `docs/units/u4b/design.md`).

| 영역 | 현재(U1 · U3) | U4b가 더할 것 (설계 1절, 참고) |
|---|---|---|
| 상태 기계 | PREPARING → DEVICE_ACK → ACTIVE → CLOSING → ENDED / ENDED_UNCONFIRMED, FAILED · ABORTED. 사유는 도착 상태가 정함 | WAITING_CHILD · DECLINED, CHILD_STOP · SILENCE · CONSENT_WITHDRAWN. 사유는 전이 사건이 정함 |
| 시간 규칙 | 시작 응답 20초 · 마무리 확인 30초 · 끊김 40초(ACTIVE만) | 참여 대기 20초 + 재안내 뒤 20초, 공백 4분 경고 · 6분 종료, 점선 10초, 끊김을 WAITING_CHILD까지 |
| 참여 확인 | 없음(`JoinTracker`는 U3에서 제거됨) | 시작 안내 → 응답 분류 → ACTIVE 또는 DECLINED |
| 종료 의도 | STOP이면 출력만 막고 ACTIVE 유지 | STOP → CLOSING(CHILD_STOP), CONFIRM → 확인 질문 |
| 끝 처리 | 재시작이면 FAILED(SERVER_RESTART) | 정산 → 결과 스냅숏 → `SessionRecordCaptured` |
| 삭제 | 참가자 둘, 부르는 곳 없음 | `DeletionRequested` 처리기 → `DeletionCompleted` |

갭의 코드 근거는 `code-quality-assessment.md` 「기술 부채」에 한 번만 적었다.

## 5. 업무 규칙 추적

코드 Javadoc은 설계 규칙 · 결정 ID(BR6.x · J · U3-R · U3-J · SD · RD · PD 등)로 설계 문서에 추적된다. 예: `TransitionTable` Trigger의 BR6.6 ⑤ · BR6.7 · BR6.9 · BR6.10 ④ · BR6.12 ② ~ ④ · BR6.13. 시간 값의 정본은 `contracts/timing.md` 한 곳이다.

## Sources
- 개발자 스캔 `inception/reverse-engineering/developer-scan.md` (Scan Coverage · Packages Found · Handoff)
- `docs/project-description.md` 1 ~ 30행(훑기), `docs/units/u4b/design.md` 1 ~ 2절, `docs/units/u4b/brief.md`, `docs/units/README.md`(방향 잡기용)
- `backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/TransitionTable.java` 20 ~ 100행 직접 확인
