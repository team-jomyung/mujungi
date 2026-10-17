# 유닛 병렬 구현 — 현황과 인계 (2026-10-14 기준)

U1 워킹 스켈레톤은 AI-DLC 엔진으로 끝까지 만들었다(설계 · 코드 · 시험 · 감사 기록이 `aidlc/` 아래에 있다).
U2 ~ U5a는 엔진 없이, 엔진이 만든 인셉션 산출물(스토리 · 계약 · 컴포넌트 · 유닛 · 볼트 계획)과 U1 코드를 스펙으로 삼아 유닛별 작업자가 병렬로 구현했다.
엔진의 승인 증빙이 클론별이라 다른 PC로 넘어가지 않았기 때문이다(`aidlc/` 감사 기록 c63ff7e 참고).
U4b부터는 다시 AI-DLC를 쓴다. 담당자마다 자기 PC에서 유닛용 작업 기록을 새로 만들고, 인셉션 산출물은 `261008-mujoong-service` 기록의 것을 그대로 쓴다. 남의 작업 기록은 이어 가지 않는다(만드는 법은 `u5b/brief.md` 「AI-DLC로 진행하는 법」).

## U8 ~ U15 문서 동기화 볼트 (2026-10-16 기준)

화면 설계 v1.5j와 새 문서(Luna · 제브 · 지식 그래프)에 코드를 맞추는 작업이다. 인셉션은 `261015-doc-sync-code-update` 기록에서 끝났다. 정본 폴더는 `R = aidlc/spaces/default/intents/261015-doc-sync-code-update/`다.
- 순서 근거: `R/inception/delivery-planning/bolt-plan.md` · `risk-and-sequencing-rationale.md` · `team-allocation.md`
- 외부 의존: `R/inception/delivery-planning/external-dependency-map.md`

| 줄 | 순서 | 유닛 | 상태 | 브랜치 | 마이그레이션 | 지시서 |
|---|---|---|---|---|---|---|
| 공통 | 1 | U8 계약 · 버전 리소스 | **main 병합**(10/16, PR #21) | `unit/u8-contracts-catalog` → `unit/u9-luna-clients`에 포함 | 없음 | `R/construction/u8-contracts-catalog/` |
| 공통 | 2 | U9 Luna 호출부 | **main 병합**(10/16, PR #21). main CI #60 skeleton 통과 | `unit/u9-luna-clients` | 없음 | `R/construction/u9-luna-clients/` |
| realtime | 3 → 4 → 6 | U11 판정 턴 → U13 제브 게이트 → U12 새 연습 흐름 | U8 · U9 담당자가 이어 맡음. **U11 PR1(선 형식 · 키 통일) main 병합**(10/17, PR #28), **PR2(판정 턴) 진행 중**(`docs/units/u11/design.md` · `report.md`). U12는 U13 뒤 대역으로 일부, U10 병합 뒤 완료 | `unit/u11-judgment-turn` · `unit/u13-…` · `unit/u12-…` | realtime V5 · V6 · V7 | `u11/brief.md` · `u11/design.md` · `u11/report.md`(U13 · U12는 앞 볼트 뒤 작성) |
| core | 3 → 4 → 5 | U15 지식 그래프 → U14 목표 · 결과 → U10 상황 생성 | **팀원에게 넘김** | `unit/u15-knowledge-graph` · `unit/u14-goal-result` · `unit/u10-scenario-generation` | core V8 · V9 · V10 | `u15/brief.md` · `u14/brief.md` · `u10/brief.md` |

- **시작 브랜치**: U8 · U9는 PR #21로 `main`에 합쳐졌다(`main` CI #60 skeleton 통과). 새 볼트는 최신 `main`에서 딴다.
- **두 줄은 서로 기다리지 않는다.** core와 realtime은 Flyway 이력이 따로라서다. 같은 앱 안에서만 번호 순서대로 합친다.
- **두 줄이 만나는 곳은 U12 ↔ U10 대본 계약(C-DS1)뿐이다.** U12 담당자가 `practice-scenario.schema.json` 선택 필드를 계약 PR로 먼저 넣고 대역으로 개발한다. U10은 그 계약을 쓰고, 고칠 곳이 있으면 두 담당자가 같은 날 함께 합친다. U12는 U10 병합 뒤 실제 대본으로 마무리한다.
- **삭제 순서 상수**: 지금 60 · 70 · 90 · 100 · 150 · 200 · 250 · 300 · 900을 쓴다. U15 그래프 제안값은 80이다.
- **이 PC가 WSL Ubuntu이고 `date`가 uutils이면** `%3N` 자릿수가 깨진다. 그래서 `infra/ci/test-host-tools.sh` turn-metrics 2건이 실패한다. GNU `date`(`gnudate`)를 PATH 앞에 두고 돈다. CI에는 영향이 없다.
- 실호출(`./gradlew lunaLiveTest`)은 사람이 키를 넣고 합성 자료로만 한다. 넣기 전에 `infra/RUNBOOK.md` 단계 4의 R-F15 확인 기록을 먼저 남긴다(편차 (U9-b) · (U9-c)).

## 현황

| 유닛 | 상태 | 브랜치 | 문서 |
|---|---|---|---|
| U1 워킹 스켈레톤 | main | — | `aidlc/…/construction/u1-walking-skeleton/` |
| U2 보호자 계정 | **main 병합**(10/12, fbdb5a5). 3차 리뷰 후속 9건은 U4a 브랜치 첫 커밋(8d0f009)에 있어 U4a와 함께 main에 들어온다 | 병합됨 | `u2/brief.md` · `design.md` · `report.md` |
| U3 음성 대화 | **main 병합**(10/13, PR #2, 2493ee3). 리뷰 3차와 종료 의도 판정 보강까지 | 병합됨 | `u3/brief-cloud.md` · `design.md` · `report.md`(9절 남은 일) |
| U4a 활동 세션 Core · /parent 화면 | **main 병합**(10/13, PR #2, 7a8a1d7). 리뷰 1차 병합 전 5건 반영. core V3 | 병합됨 | `u4a/brief.md` · `design.md` · `report.md` · `review-1.md` |
| U5a 연습 Core | **main 병합**(10/13, PR #4, de2cbe6). 리뷰 1차 11건 반영. core V4 | 병합됨 | `u5a/brief.md` · `design.md` · `report.md`(리뷰 반영 절) · `review-1.md` |
| U4b 활동 세션 Realtime | **main 병합**(10/14, PR #8, 7a22b67). realtime V3 | 병합됨 | `u4b/design.md` · `u4b/brief.md` |
| U5b 연습 Realtime · 연결 마무리 | **구현 완료 · 통합 반영**(10/14). realtime V4 · core V7. 실제 시작·재생·판정·종료·노출 정산 연결 | `codex/u5b-integration` → `codex/all-units-integration` | `u5b/brief.md` · `u5b/design.md` · `u5b/report.md` |
| U6 결과와 리포트 | **구현 완료 · 통합 반영**(10/14). core V5. U5b 실제 도움 제공 증거·시간초과 사유를 소비 | `snapshot/u6-results-report-20261014` → `codex/all-units-integration` | `u6/brief.md` · `u6/design.md` · `u6/report.md` |
| U7 웹 푸시 | **구현 완료 · 통합 반영**(10/14). core V6. 완료 사건 → 푸시 클릭 → U6 결과 조회 연결. 실기 확인은 별도 | `unit/u7-web-push` → `codex/all-units-integration` | `u7/brief.md` · `u7/design.md` · `u7/report.md` |

## 병합 순서 (꼭 지킨다)

- Flyway `outOfOrder`가 꺼져 있어 낮은 버전이 먼저 들어가야 한다. core는 V3(U4a) → V4(U5a) → **V5(U6) → V6(U7) → V7(U5b 시작 준비)**, realtime은 V3(U4b) → V4(U5b). U5b 통합 PR은 core V7도 포함하므로 U6 · U7 뒤에 병합한다.
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

## 규칙

- 화면 설계 v1.5j가 최우선이다. 다만 27의 연습 기록 · 28의 만드는 중 문구 · 30 · R-PDF는 `refined-mockups/mockups.md` §1의 F3 ~ F6이 앞선다(10개 항목 리포트 · 기록용 재판정).
- 마이그레이션 번호: core V2 = U2 · V3 = U4a · V4 = U5a · V5 = U6 · V6 = U7 · V7 = U5b 시작 준비(`core_practice_preparation`), realtime V2 = U3 · V3 = U4b · V4 = U5b.
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
