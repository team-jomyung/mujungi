> 최신 정본: 아래 U10 갱신절(2026-10-17, HEAD eb34c68bf24e4520881b7ffebe1070564554dfc9). 이후 U14/U15/전체 분석 본문은 역사 자료이며, 이번에 검증한 범위는 timestamp의 analyzed 목록만이다.

## U10 집중 갱신 — 현재 업무와 구현 차이

현재 Core 상황 생성은 동결한 행동 정의로 단일 상황을 만들고 사전·구조 검사를 통과한 것만 발급한다. U15의 아동별 지식 서비스와 U14의 버전 고정 목표 읽기는 구현되어 있으나 이 생성 흐름에는 아직 연결되지 않았다. U10 자체 검토와 여러 BASE/VARIANT 장면, 저장·응답 선택 필드는 미구현이다. 여기서 U10 완료나 시험 성공을 주장하지 않는다.

새 목표를 실제로 연습하려면 생성 서비스뿐 아니라 준비 입구의 기존 행동 정의 가정도 풀어야 한다. 재생·기기 상태 기계는 U12 소유이며 이번 집중 분석에서 열지 않았다. 관심사 공급자 전달 여부는 지시서가 Functional Design의 사람 질문으로 남긴 결정이다.

---

> 현재 범위의 정본은 바로 아래 U14 갱신절이다. 그 뒤 원문은 보존 이력이며 재검증 범위는 timestamp에 한정한다.

## U14 집중 갱신 — 현재 코드 사실

2026-10-16, 기준 HEAD 1a4ef7b04f5cb4d6490e40c3b3d1a70c1d2b9014. U15 구현이 들어온 unit/u14-goal-result에서 확인했다. 아래 보존 본문의 U15 미구현 설명은 역사 자료다. ChildKnowledgeService와 SeedGenerationService는 현재 존재한다.

현재 후보는 행동 정의 카탈로그의 키·버전을 고르는 형태다. U14는 기록에서 생성한 목표를 보호자가 선택하고, 선택 당시 후보 버전을 보존하도록 바꾸는 작업이다. AFTER_REASK와 실제 재생 장면 수를 기록·결과·리포트에 보존해야 하며 27의 기존 다섯 표시 칩은 유지한다. 아직 U14 구현이나 검증 완료를 뜻하지 않는다.

---

# 업무 개요 (Business Overview)

> 최신 확인: 2026-10-16, HEAD 7e30ee8, U15 집중 분석. 아래 최신 절이 U15 관련 과거 설명보다 우선한다. 이전 분석 본문은 이력으로 보존하며 이번 범위 밖의 검증 주장은 shallow로 낮췄다.

## U15 업무 경계 — 2026-10-16 확인

기존 서비스는 보호자의 아동 연습을 지원하며 PostgreSQL의 행동 정의와 승인 지원문구를 권위 원장으로 사용한다. U15는 아동별 지식 그래프 및 생성 씨앗을 추가할 예정이며, 현재 코드에는 이 개인화 경로가 없다. 공개 행동 카탈로그 조회를 유지하면서 아동 데이터 격리, 원문 비저장, 삭제 재시도, 장애 시 NONE을 보장해야 한다. 생성 씨앗이 기존 불변 행동 정의나 결과 상태를 덮어쓰면 안 된다.

## 이전 분석 기록 — 2026-10-15, 현재 범위 밖은 재검증하지 않음

> 기준: 커밋 `72d7034`(브랜치 `feat/doc-sync-code-update` = origin/main), 2026-10-15 전체 재스캔.
> 이 문서는 지금 코드가 하는 일만 적는다. 갱신된 설계 문서(화면 설계 v1.5j 등)와의 차이는 다음 단계(Requirements Analysis)가 계산한다.

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
2. **아이 정보 · 중단 표현** — 아이 프로필 1명, 아이가 대화를 멈추고 싶을 때 쓰는 말(중단 표현) 목록. `core.child`.
3. **기기 등록** — 등록 코드 → 기기 부트스트랩 · claim · 기기 토큰. 기기 연결 상태(OFFLINE · CONNECTED · UNSTABLE · DISCONNECTED · UNRESPONSIVE)를 보호자 화면에 보여 줌. `core.device` · `realtime.gateway`.
4. **자유 대화 활동(FREE_TALK)** — 보호자가 시작 → Realtime이 기기에 세션을 열고 음성 공급자와 중계 → 침묵 · 아이 중단 의도 · 보호자 종료로 끝남. 출력 게이트가 공급자 출력을 문장 단위로 걸러 승인된 것만 재생.
5. **연습 활동(PRACTICE)** — 보호자가 연습 목표를 고르고(목표 선택 · 즐겨찾기 · 복원), Core가 텍스트 AI로 상황(시나리오)을 만들어 Realtime에 넘김. Realtime 연습 런타임이 판정 · 단서를 돌림. `core.practice` · `core.textai` · `realtime.practice`.
6. **결과 · 기록** — 세션이 끝나면 Realtime이 대화 기록 스냅샷을 이벤트로 보내고, Core 결과 작업이 결과 수준 계산 · AI 결과 문장 · 금지 표현 검사 · 생활 팁을 만든다. 보호자는 기록 목록 · 상세에서 본다. `core.result` · `core.record`.
7. **리포트** — 기간 미리보기 → 리포트 생성(텍스트 AI) → PDF 내려받기 · 저장 · 폐기. `core.report`.
8. **웹 푸시 알림** — 결과 준비 등 이벤트를 VAPID 웹 푸시로 보냄(보호자 서비스 워커는 `/parent/push-worker`). `core.notification`.

## 업무 상태 어휘 (계약 정본: `contract-model`)

- 세션 상태 `SessionStatus` 12값: REQUESTED → PREPARING → DEVICE_ACK → WAITING_CHILD → ACTIVE → CLOSING → 끝 상태(ENDED · ENDED_UNCONFIRMED · ABORTED · DECLINED · FAILED), 그리고 해석 불가 값 UNRECOGNIZED.
- 종료 까닭 `EndReason`: GUARDIAN_STOP · CHILD_STOP · SILENCE · DISCONNECT · CONSENT_WITHDRAWN · UNCONFIRMED · DECLINED · FAILED.
- 실패 코드 `FailureCode`: DEVICE_NO_ACK · SCENARIO_GEN_FAILED · PROVIDER_UNAVAILABLE · SERVER_RESTART.
- 활동 종류 `ActivityType`: FREE_TALK · PRACTICE.

상태 전이 · 시간 값의 세부는 [architecture.md](architecture.md)의 상호작용 다이어그램과 [api-documentation.md](api-documentation.md)를 본다.

## 현재 업무상 한계 (코드 사실)

- **AI 운영 공급자가 연결돼 있지 않다.** 텍스트 AI(상황 생성 · 결과 · 리포트)와 연습 판정 · 단서는 기본 빈이 항상 실패한다. 실제 동작은 시험 대역뿐이다. 자세한 내용은 [code-quality-assessment.md](code-quality-assessment.md)의 기술 부채 TD-1.
- 음성 공급자 연결은 있으나 선 형식(모델 `gpt-live-1`, `session.*` 메시지)은 프로젝트가 정의한 것이다(TD-2).
- 지식 그래프(Neo4j)는 인터페이스만 있고 꺼져 있다(TD-3).
- 세션 안내 문구 음성 6종은 합성음 자리 파일이다(`infra/seed-audio/`).

## 업무 로직 확인 수준

이번 스캔은 패키지 · 경계 수준이다. 결과 계산 · 연습 판정 · 세션 실행의 본문은 얕게 봤다. 문서 차이가 이 기능들에 걸리면 해당 경로를 집중 스캔해야 한다([reverse-engineering-timestamp.md](reverse-engineering-timestamp.md)의 `shallow.paths`).


