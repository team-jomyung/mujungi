# 업무 개요 (Business Overview)

> 기준: 커밋 `7a22b67` (origin/main, U4b PR #8 병합 뒤). 인텐트 `261014-u6-result-report` 전체 재스캔(깊이 Minimal).
> 정본 우선순위: 화면 설계 v1.5j(`docs/project-description.md`) > 다른 문서. 이 저장소 문서는 코드에서 읽은 사실만 적는다.

## 서비스 한 줄

**무중(mujoong)** — 6 ~ 12세 아이의 화용(대화 상황에 맞는 말하기) 능력을 돕는 AI 음성 대화 서비스. 아이는 전용 기기(`/device`)로 AI와 말하고, 보호자는 웹(`/parent`)으로 기기 · 동의 · 아이 정보 · 연습 목표 · 기록을 다룬다.

## 사용자와 영역

| 사용자 | 영역 | 주요 일 |
|---|---|---|
| 보호자 | `/parent` (Next.js), Core REST `/api/**` | 로그인 · 동의 · 아이 등록 · 기기 등록 · 자유대화 / 연습 시작 · 기록 보기(26 · 27) · 연습 목표 고르기 · 리포트(28 · 30, **아직 없음**) |
| 아이 | `/device` (Next.js), Realtime WebSocket | 음성 대화(자유대화 FREE_TALK · 연습 PRACTICE), 멈춤 표현 |
| 운영자 | infra 호스트 도구 · RUNBOOK | 배포 · 백업 · 마이그레이션 승인 |

## 업무 흐름 (현재 main에 있는 것)

1. **계정 · 동의 · 아이**(U1 · U2): Google 로그인, 약관 · 동의 판, 아이 정보 · 멈춤 표현.
2. **기기**(U1 · U3): 기기 등록 토큰, 연결 상태.
3. **활동 세션**(U4a Core · U4b Realtime): 보호자가 시작 → Realtime 세션 실행기가 음성 대화 진행 → 끝나면 Realtime이 **결과 입력 스냅숏**을 만들어 `SessionRecordCaptured` 이벤트로 Core에 보냄 → Core가 **활동 기록 원장**(`core.core_activity_record`)에 저장하고 앱 내부 사건 `ActivityRecorded`를 냄.
4. **연습 Core**(U5a): 화용 지식(행동 정의 · 지원 문구), 연습 목표 선택, 상황(Scenario) 생성, **목표 후보 등록 포트**(`CandidateRegistry`).
5. **기록 조회**(U4a): `GET /api/records`(달력 · 그날 목록), `GET /api/records/{sessionId}`(대화 기록 칸). 화면 26은 아직 redirect 스텁.

## U6(결과와 리포트)가 채울 빈 곳

U6 지시서(`docs/units/u6/brief.md`)가 말하는 업무 — 활동 뒤 **정리**(경험 구조화 · 기록용 재판정 · 목표 후보 · 생활 팁 · 결과 문장 · 결과 수준)와 **화면**(26 · 27 · 28 · 30 · R-PDF, 17 정리 상태)과 **상담용 리포트** — 는 코드에 아직 하나도 없다. 붙을 자리는 다음과 같다(상세는 `architecture.md` 「U6 접점」, `component-inventory.md`).

- 입력: `ActivityRecorded`(운영 구독자 0개), 활동 기록 원장의 transcript · gaps · practice JSON. PRACTICE 기록의 `practice`는 **U5b 전까지 null**.
- 출력: `CandidateRegistry.register`(기본 근거 포트가 늘 거절), 보호자 C5 API(계약 파일에 없음).
- 확장: `ScenarioLookup` · `BehaviorDefinitionLookup` 포트 없음.
- 알림(U7) 자리도 코드에 없다. `core/messaging`은 앱 사이 Outbox/Inbox 메시징이지 보호자 알림이 아니다.

## 업무 규칙 중 코드가 강제하는 것 (U6와 맞닿는 것만)

- **보호자 응답 노출 금지**(FR4.3 · A10): 판정값 · 구조화 · 08 원문은 보호자 응답 모양에서 뺀다(`RecordQueryService` · `RecordController`, `ContractConformanceIT` 스키마 대조).
- **기록 상태**: `final_status ∈ {ENDED, ENDED_UNCONFIRMED, ABORTED, DECLINED, FAILED}`. FAILED는 목록 · 상세에서 제외(상세 404), DECLINED는 본문 없이 머리만.
- **금지 표현**: 후보 근거 문장은 `ForbiddenExpressionPolicy`(U5a 사전)를 통과해야 한다.
- **계정 삭제 순서**: `DeletionParticipant` 순서 상수대로 지운다(U6는 70을 더할 예정).

## Sources

- 개발자 스캔 `aidlc/spaces/default/intents/261014-u6-result-report/inception/reverse-engineering/developer-scan.md`(Packages Found · APIs Discovered · U6 관련 코드 위치 · Handoff Summary)
- `docs/units/u6/brief.md`(범위 · 앞 유닛이 남긴 자리)
