# 유닛 병렬 구현 — 현황과 인계 (2026-10-13 기준)

U1 워킹 스켈레톤은 AI-DLC 엔진으로 끝까지 만들었다(설계 · 코드 · 시험 · 감사 기록이 `aidlc/` 아래에 있다).
U2부터는 엔진 없이, 엔진이 만든 인셉션 산출물(스토리 · 계약 · 컴포넌트 · 유닛 · 볼트 계획)과 U1 코드를 스펙으로 삼아 유닛별 작업자가 병렬로 구현했다.
엔진의 승인 증빙이 클론별이라 다른 PC로 넘어가지 않았기 때문이다(`aidlc/` 감사 기록 c63ff7e 참고).

## 현황

| 유닛 | 상태 | 브랜치 | 문서 |
|---|---|---|---|
| U1 워킹 스켈레톤 | main | — | `aidlc/…/construction/u1-walking-skeleton/` |
| U2 보호자 계정 | **main 병합**(10/12, fbdb5a5). 3차 리뷰 후속 9건은 U4a 브랜치 첫 커밋(8d0f009)에 있어 U4a와 함께 main에 들어온다 | 병합됨 | `u2/brief.md` · `design.md` · `report.md` |
| U3 음성 대화 | **main 병합**(10/13, PR #2, 2493ee3). 리뷰 3차와 종료 의도 판정 보강까지 | 병합됨 | `u3/brief-cloud.md` · `design.md` · `report.md`(9절 남은 일) |
| U4a 활동 세션 Core · /parent 화면 | 구현 끝. 리뷰 1차 병합 전 5건 반영, main 병합. **PR #2 CI 대기**. core V3 | `unit/u4a-activity-core` | `u4a/brief.md` · `design.md` · `report.md` · `review-1.md` |
| U5a 연습 Core | 구현 끝. 리뷰 1차 11건 반영, U4a(main 포함) 병합. **CI 대기**(U4a 다음에 병합). core V4 | `unit/u5a-practice-core` | `u5a/brief.md` · `design.md` · `report.md`(리뷰 반영 절) · `review-1.md` |
| U4b 활동 세션 Realtime | 설계만 있다(클라우드 세션). **구현 전**. realtime V3 | main에서 새로 `unit/u4b-activity-realtime` | `u4b/design.md` · **`u4b/brief.md`** |
| U5b · U6 · U7 | 시작 전 | — | `aidlc/…/inception/units-generation/unit-of-work.md` U5 · U6 · U7 절. U6 · U7은 맨 아래 「U6 · U7 정본」 |

## 병합 순서 (꼭 지킨다)

- Flyway `outOfOrder`가 꺼져 있어 낮은 버전이 먼저 들어가야 한다. core는 V3(U4a) → V4(U5a) → V5(U6) → V6(U7), realtime은 V3(U4b) → V4(U5b).
- 그래서 **U4a를 U5a보다 먼저** main에 합친다. U5a는 U4a가 들어간 main을 합친 뒤 V4 번호 그대로 들어온다.
- 합칠 때 겹치는 곳
  - `FlywayConfIT` 표 수 · 이력 행, 스키마 버전 기대값 시험
  - `contracts/deviations.md` · `contracts/timing.md`(양쪽 행을 모두 둔다)
  - `DeletionParticipant` 순서 상수. 지금 쓰는 값은 U4a 기록 90 · ACTIVITY 100 · DEVICE 200 · CONSENT 250 · CHILD_PROFILE 300 · MESSAGING 900이다. U5a의 ORDER_PRACTICE는 300보다 작고 이 값들과 겹치지 않게 정한다(예: 150). U6(결과 · 장면 판정 · 리포트)은 70, U7(구독 · 발송 기록)은 60을 쓴다(활동 기록 90보다 먼저 지운다).

## CI (10/13 정리)

main CI는 U2 병합(10/12) 때부터 실패하고 있었다. 원인은 둘이었다.

1. **마이그레이션 expand 검사**: U2 core V2의 7문장이 걸렸다. 기존 표 제약 강화 6문장과 `DROP NOT NULL` 오탐 1문장이다. 설계(cicd 2.7.2)의 'contract는 리뷰어 승인으로 예외'를 구현했다.
   - 사람이 확인한 문장을 `infra/migration-approvals.txt`에 사유와 함께 적는다.
   - 검사기(`infra/bin/infra-validate --migration-approvals`)는 파일 · 문장 번호 · 문장 해시가 함께 맞는 문장만 넘긴다.
   - 앞으로 기존 표에 제약을 더하는 마이그레이션도 이 검사에 걸린다. 이전 앱이 깨지지 않는 근거를 적어 한 줄을 더하고 리뷰를 받는다.
2. **Core 시험 연결 시한**: `internal-http.connect-timeout`가 50ms라 바쁜 CI 기계에서 503이 났다. 500ms로 올렸다(U4a 브랜치와 같은 수정).

더 알아 둘 것:

- **배포**: CI가 통과해도 배포 작업의 실제 배포 단계는 건너뛴다. 저장소 변수 `SERVICE_HOST` · `AUTO_DEPLOY`가 아직 없어서다.
- **Windows 개발 PC**: infra-validate · 이미지 관문 · 스켈레톤 검증은 리눅스에서만 돈다. 그러니 병합 전에 GitHub PR의 CI 결과를 꼭 확인한다.

## 알려진 흔들림 · 남은 일

- `RealtimeInternalApiIT`(U1 시험): 부하가 크면 202 대신 409가 난다. 시험 프로필 `closing-confirm` 3초 · 고아 점검 1초 때문이다(U3 report 9절). CLOSING을 손보는 U4b 몫이다.
- U3 report 9절: 계약 · 설계 문장 정리 4건.
- U4a report 남은 일
  - 시드 003을 검증 스크립트와 RUNBOOK의 시드 순서(001 → 002 → 003)에 넣는다.
  - 개발용 시드에 세션 문구를 넣을지 정한다.
  - 세션 문구 6종을 실제로 녹음하고 사람이 승인한다. 지금 음성은 합성음 자리다.
- U4a 리뷰는 아직이다. 조정자 검증만 통과했다.

## 규칙

- 화면 설계 v1.5j가 최우선이다. 다만 27의 연습 기록 · 28의 만드는 중 문구 · 30 · R-PDF는 `refined-mockups/mockups.md` §1의 F3 ~ F6이 앞선다(10개 항목 리포트 · 기록용 재판정).
- 마이그레이션 번호: core V2 = U2 · V3 = U4a · V4 = U5a · V5 = U6 · V6 = U7, realtime V2 = U3 · V3 = U4b · V4 = U5b.
- 각 작업자는 `docs/units/<유닛>/design.md` · `report.md`를 남기고, 리뷰를 거쳐 main에 합친다.

## U6 · U7 정본 (지시서를 쓸 때 읽을 곳)

기록 폴더 `R = aidlc/spaces/default/intents/261008-mujoong-service/`.

| 무엇 | 어디 |
|---|---|
| 범위 · 완료 조건 | `R/inception/units-generation/unit-of-work.md` U6 · U7 절, `R/inception/delivery-planning/bolt-plan.md` B6 · B7 |
| 요구사항 | `R/inception/requirements-analysis/requirements.md` FR4.3 · FR6.2 ~ FR6.5 · FR7.4 · FR7.6 · FR7.7 · FR7.10 · FR7.11 · NFR8 · A4 · A10 |
| 스토리 · 수용 기준 | `R/inception/user-stories/stories.md` US1.7 · US4.3 · US5.1 ~ US5.5 |
| 화면 | `R/inception/refined-mockups/mockups.md` S-17 · S-26 · S-27 · S-28 · S-30 · R-PDF, §1 F3 ~ F6, §9-32 ~ 9-35 |
| 컴포넌트 · 엔터티 | `R/inception/domain-design/components.md` ResultPipeline · Report · Notification(SceneJudgment · ReportSection 포함), `decisions.md` ADR-017 |
| 계약 | `R/inception/contract-design/contract-summary.md` 2절 노출 금지 필드 · 3절 C5 · C6 · 6절 SessionRecordCaptured.record.practice · 8절 ScenarioLookup · BehaviorDefinitionLookup · 9절, `contracts/ai-io.md` 6절 |

맞물림
- U5b(Realtime)는 결과 입력 스냅숏 record.practice의 반응 · 도움마다 scenarioId를 싣는다. U6은 이것을 기록용 재판정의 입력으로 쓴다. U5b가 합쳐지기 전에는 계약 6절 예시 값으로 만든 대역으로 시험한다.
- ScenarioLookup · BehaviorDefinitionLookup이 U5a에 없으면 U6이 PracticeGoal · PragmaticsKnowledge에 읽기 포트를 확장으로 붙인다(J17).
- U7의 완료 푸시는 ActivityCoordinator 세션 사본의 ACTIVE 도달 여부가 필요하다. U4a 사본에 없으면 U7이 사본에 기록 칸을 더한다(더하기만).
