# U6 엔터티 모델 — 결과와 리포트

> Functional Design(3.1) · 작업 기록 `261014-u6-result-report`.
>
> 입력
> - `inception/requirements-analysis/requirements.md`(FR1 ~ FR16, NFR1 ~ NFR12)
> - 이 단계 질문 `functional-design-questions.md` Q1 ~ Q7
> - 원래 기록 `components.md` ResultPipeline · Report 엔터티(ResultJob · ResultJobAttempt · StructuredExperience · SceneJudgment · ActivityResult · SessionResultState · Report · ReportSection · ReportActivity), 계약 3절 C5 · 8절
> - core V1 ~ V4 마이그레이션의 실제 열, codekb `component-inventory.md`
>
> 이 작업 기록은 units-generation · domain-design을 건너뛰었으므로 원래 기록의 컴포넌트 설계와 기존 코드 구조를 도메인 설계로 본다. 아래 YAML이 정본이다. 새 표는 모두 core V5(`V5__u6_results_report.sql`, 더하기만)에 만든다. 기존 표는 `core_support_item`에 nullable 열 하나만 더하고, 그 밖의 기존 표(U4a · U5a 소유)는 바꾸지 않는다. 기존 표에 FK를 걸지 않는다(가정 C22).

```yaml
entities:
  # ── ResultPipeline (kr.mujoong.core.result) ──────────────────────────────
  - name: ResultJob
    description: 결과 작업 하나. SESSION_RESULT(세션 정리)와 REPORT(리포트 조립)를 같은 실행기가 돌린다(FR1.1, Q1 A).
    owner: core.result
    status: new
    table: core.core_result_job
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: job_type, type: enum, required: true, allowed: [SESSION_RESULT, REPORT] }
      - { name: subject_id, type: uuid, required: true, constraints: "SESSION_RESULT면 session_id, REPORT면 report_id" }
      - { name: guardian_id, type: uuid, required: true, constraints: "삭제 참가자 · 삭제 중 확인의 기준(FR14.1)" }
      - { name: idempotency_key, type: string, required: true, unique: true, constraints: "SESSION_RESULT = 'SR:' + sessionId + ':' + 차수(1부터, 다시 시도마다 +1). REPORT = 'RP:' + reportId" }
      - { name: trigger_type, type: enum, required: true, allowed: [ACTIVITY_RECORDED, RECONCILE, RETRY, REPORT_REQUEST] }
      - { name: status, type: enum, required: true, allowed: [REQUESTED, RUNNING, DONE, FAILED], default: REQUESTED }
      - { name: attempt_count, type: integer, required: true, min: 0, max: 3, default: 0, constraints: "시도를 시작할 때 1 늘린다. 3이면 더 시도하지 않는다" }
      - { name: next_attempt_at, type: timestamp, required: true, constraints: "REQUESTED일 때 이 시각 이후에만 선점한다. 최초 = 접수 시각, 실패 뒤 = +5초 · +15초(result.job-retry-backoff)" }
      - { name: lease_until, type: timestamp, required: false, constraints: "RUNNING일 때 선점 만료 시각 = 시작 + 시도 시한(SESSION_RESULT 30초, REPORT는 deadline_at)" }
      - { name: deadline_at, type: timestamp, required: false, constraints: "REPORT만. 접수 + report.ready-target(60초). 넘으면 남은 시도 없이 FAILED(FR9.3)" }
      - { name: last_error_code, type: enum, required: false, allowed: [PROVIDER_UNAVAILABLE, PROVIDER_ERROR, TIMEOUT, SCHEMA_INVALID, DEADLINE_EXCEEDED, SERVER_RESTART, ACCOUNT_DELETING, REPORT_DISCARDED, INTERNAL] }
      - { name: created_at, type: timestamp, required: true }
      - { name: updated_at, type: timestamp, required: true }
      - { name: finished_at, type: timestamp, required: false, constraints: "DONE · FAILED 때만" }
    constraints:
      - "선점 색인: (status, next_attempt_at)"
      - "같은 subject_id에 REQUESTED · RUNNING 작업은 최대 1개(부분 유일 색인, FR1.1 동시 1개)"
    relationships:
      - { to: ResultJobAttempt, cardinality: "1:0..3", direction: "job → attempts" }

  - name: ResultJobAttempt
    description: 작업의 시도 한 번(attemptNo 1~3).
    owner: core.result
    status: new
    table: core.core_result_job_attempt
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: job_id, type: uuid, required: true, references: ResultJob.id }
      - { name: attempt_no, type: integer, required: true, min: 1, max: 3 }
      - { name: started_at, type: timestamp, required: true }
      - { name: ended_at, type: timestamp, required: false }
      - { name: outcome, type: enum, required: false, allowed: [SUCCEEDED, FAILED, ABANDONED], constraints: "ABANDONED = 재시작 때 RUNNING이던 시도" }
      - { name: error_code, type: enum, required: false, allowed: "ResultJob.last_error_code와 같다" }
    constraints:
      - "UNIQUE(job_id, attempt_no)"

  - name: SessionResultState
    description: 세션 하나의 정리 상태 머리 행. 17 · 27의 jobStatus와 현재 결과를 가리킨다(FR8.2).
    owner: core.result
    status: new
    table: core.core_session_result_state
    attributes:
      - { name: session_id, type: uuid, required: true, unique: true, constraints: "core_activity_record.session_id와 같다(논리 참조)" }
      - { name: guardian_id, type: uuid, required: true }
      - { name: child_id, type: uuid, required: true }
      - { name: activity_type, type: enum, required: true, allowed: [FREE_TALK, PRACTICE] }
      - { name: eligibility, type: enum, required: true, allowed: [ELIGIBLE, NOT_ELIGIBLE], constraints: "BR2.2 판정 결과. NOT_ELIGIBLE이면 작업이 없고 jobStatus는 NONE. UNDECIDED(사본 상태 미반영)는 행을 만들지 않으므로 이 칸 값이 아니다" }
      - { name: current_job_id, type: uuid, required: false, references: ResultJob.id }
      - { name: job_status, type: enum, required: true, allowed: [NONE, REQUESTED, RUNNING, DONE, FAILED], constraints: "current_job_id의 status를 같은 트랜잭션에서 옮겨 적는다(조회 한 번으로 17 응답)" }
      - { name: current_result_id, type: uuid, required: false, references: ActivityResult.id, constraints: "DONE 결과 버전 중 현재 것. 다시 시도가 DONE이 될 때만 바뀐다" }
      - { name: run_ordinal, type: integer, required: true, min: 0, default: 0, constraints: "작업 차수. 다시 시도마다 +1(멱등 키의 차수)" }
      - { name: created_at, type: timestamp, required: true }
      - { name: updated_at, type: timestamp, required: true }

  - name: ActivityResult
    description: 세션 결과 한 버전. 다시 시도가 DONE이 되면 새 버전이 생기고 이전 버전은 보존한다(FR1.2, 가정 C21).
    owner: core.result
    status: new
    table: core.core_activity_result
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: session_id, type: uuid, required: true }
      - { name: version, type: integer, required: true, min: 1, constraints: "UNIQUE(session_id, version)" }
      - { name: job_id, type: uuid, required: true, references: ResultJob.id }
      - { name: result_level, type: enum, required: true, allowed: [FULL, PARTIAL, INSUFFICIENT] }
      - { name: sentences_json, type: json, required: true, constraints: "[{goalSelectionId?, text, templated}] — 연습은 목표마다 1~2개, 자유대화는 goalSelectionId 없음 1~2개, INSUFFICIENT면 빈 배열(FR5.1)" }
      - { name: help_json, type: json, required: true, constraints: "[{goalSelectionId, count, consent}] — 연습만, 자유대화는 빈 배열(FR4.3)" }
      - { name: tips_json, type: json, required: true, constraints: "[{supportItemId, title?, condition, content, limitation}] — 승인 항목만(FR7.1)" }
      - { name: no_tip_reason, type: enum, required: false, allowed: [NEED_MORE_OBSERVATION], constraints: "tips가 빈 배열일 때만" }
      - { name: matching_log_json, type: json, required: true, constraints: "{sessionTags[], matchedItemIds[], rejected[{supportItemId, reason}]} — 서버 내부 기록, 응답에 내보내지 않는다(AC5.2.3)" }
      - { name: pending_candidates_json, type: json, required: true, constraints: "검사를 통과해 등록할 후보 목록 [{behaviorKey, behaviorVersion, reasonText, evidence[{utteranceSeq, kind}]}], 없으면 빈 배열. 서버 내부, 응답에 내보내지 않는다(BR6.4)" }
      - { name: candidates_registered_at, type: timestamp, required: false, constraints: "CandidateRegistry 등록 성공 시각. 비어 있으면 누락 점검이 다시 등록한다(BR2.3 ②)" }
      - { name: created_at, type: timestamp, required: true }
      - { name: invalidated_at, type: timestamp, required: false, constraints: "더 새 버전이 현재가 되면 채운다(보존, 응답에는 현재 버전만)" }

  - name: StructuredExperience
    description: 자유대화 경험 구조화 결과(FR3.1). 08 원문과 원본 contentJson은 어떤 응답에도 나가지 않는다(FR8.3).
    owner: core.result
    status: new
    table: core.core_structured_experience
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: result_id, type: uuid, required: true, references: ActivityResult.id }
      - { name: session_id, type: uuid, required: true }
      - { name: content_json, type: json, required: true, constraints: "{characters[], situation, partnerUtterance, childResponse, explanation} 각 칸 = {text, source GUARDIAN_INPUT|CHILD_RECALL, utteranceSeq?}. 의도 · 감정 칸 없음(AC5.2.9). CHILD_RECALL이면 utteranceSeq ≥ 1" }
      - { name: created_at, type: timestamp, required: true }
    constraints:
      - "UNIQUE(result_id)"

  - name: SceneJudgment
    description: 연습 장면 하나의 기록용 재판정(FR4.1). 원값(value · timing · rationale)은 내부 전용, 응답은 칩만.
    owner: core.result
    status: new
    table: core.core_scene_judgment
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: result_id, type: uuid, required: true, references: ActivityResult.id }
      - { name: session_id, type: uuid, required: true }
      - { name: goal_selection_id, type: uuid, required: true, constraints: "record.practice.goals[].goalSelectionId" }
      - { name: goal_order, type: integer, required: true, min: 1 }
      - { name: scene_no, type: integer, required: true, allowed: [1, 2] }
      - { name: exposure_type, type: enum, required: true, allowed: [ORIGINAL, NEW_SCENARIO] }
      - { name: scenario_id, type: uuid, required: false, constraints: "노출되지 않은 장면이면 비어 있다" }
      - { name: value, type: enum, required: false, allowed: [functional, partial, none, uncertain], constraints: "null이면 미실시(not_done_reason 필수)" }
      - { name: timing, type: enum, required: false, allowed: [IMMEDIATE, AFTER_HELP] }
      - { name: rationale, type: text, required: false, constraints: "재판정 근거 문장. 내부 전용(FR8.3)" }
      - { name: live_label, type: enum, required: false, allowed: [functional, partial, none, uncertain, NOT_PERFORMED], constraints: "진행 중 판정(record.practice.responses[].label) 사본. 내부 전용" }
      - { name: differs_from_live, type: boolean, required: true }
      - { name: not_done_reason, type: enum, required: false, allowed: [TIME_OVER, SCENARIO_FAILED, ACTIVITY_ENDED, NO_RESPONSE], constraints: "value가 null일 때만(BR4.4 사상표)" }
      - { name: scene_chip, type: enum, required: true, allowed: [DONE_IMMEDIATE, DONE_AFTER_HELP, NEXT_TIME, ON_HOLD, NOT_DONE] }
      - { name: created_at, type: timestamp, required: true }
    constraints:
      - "UNIQUE(result_id, goal_selection_id, scene_no)"
      - "value IS NULL ⇔ scene_chip = NOT_DONE ⇔ not_done_reason IS NOT NULL"

  # ── Report (kr.mujoong.core.report) ──────────────────────────────────────
  - name: Report
    description: 상담용 리포트 한 건(FR9 · FR10).
    owner: core.report
    status: new
    table: core.core_report
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: guardian_id, type: uuid, required: true }
      - { name: child_id, type: uuid, required: true }
      - { name: idempotency_key, type: string, required: true, constraints: "UNIQUE(guardian_id, idempotency_key). 같은 키 = 첫 결과, 다른 본문 = 409" }
      - { name: request_hash, type: string, required: true, constraints: "{from, to, 정렬된 sessionIds}의 sha256" }
      - { name: status, type: enum, required: true, allowed: [GENERATING, READY, FAILED, SAVED, DISCARDED], default: GENERATING }
      - { name: period_from, type: date, required: true, constraints: "Asia/Seoul 날짜" }
      - { name: period_to, type: date, required: true, constraints: "period_from ≤ period_to ≤ 오늘(Asia/Seoul)" }
      - { name: activity_count, type: integer, required: false, min: 1 }
      - { name: citation_count, type: integer, required: false, min: 0, constraints: "(sessionId, utteranceSeq) 중복 제외 전사 인용 수(가정 G14)" }
      - { name: level, type: enum, required: false, allowed: [FULL, PARTIAL, INSUFFICIENT], constraints: "ai-io 6.5 level 규칙" }
      - { name: job_id, type: uuid, required: false, references: ResultJob.id }
      - { name: child_display_name, type: string, required: false, constraints: "생성 때 ChildContext.get으로 받은 이름. 30 · PDF 표시용, AI에는 보내지 않음" }
      - { name: child_age, type: integer, required: false }
      - { name: created_at, type: timestamp, required: true }
      - { name: ready_at, type: timestamp, required: false }
      - { name: saved_at, type: timestamp, required: false, constraints: "첫 saved만(멱등, G13)" }
      - { name: discarded_at, type: timestamp, required: false }
    constraints:
      - "상태 전이는 functional-spec 3.2만 성립한다"
      - "DISCARDED면 하위 행(ReportActivity · ReportSection · ReportCitation)이 0건이다(묘비, G12)"
    relationships:
      - { to: ReportActivity, cardinality: "1:1..n", direction: "report → activities" }
      - { to: ReportSection, cardinality: "1:0..10", direction: "report → sections" }
      - { to: ReportCitation, cardinality: "1:0..n", direction: "report → citations" }

  - name: ReportActivity
    description: 리포트에 고른 활동(체크한 세션).
    owner: core.report
    status: new
    table: core.core_report_activity
    attributes:
      - { name: report_id, type: uuid, required: true, references: Report.id }
      - { name: session_id, type: uuid, required: true }
      - { name: activity_date, type: date, required: true, constraints: "활동 기록 listed_at(줄 시각 = startedAt, 없으면 사본 요청 시각)의 Asia/Seoul 날짜 — 26과 같은 기준(U4a-J8)" }
      - { name: activity_type, type: enum, required: true, allowed: [FREE_TALK, PRACTICE] }
      - { name: result_status_at_build, type: enum, required: false, allowed: [NONE, REQUESTED, RUNNING, DONE, FAILED], constraints: "조립 시점 정리 상태(Q5 A 항목별 포함 판정의 근거)" }
    constraints:
      - "PRIMARY KEY(report_id, session_id)"

  - name: ReportSection
    description: 리포트 10개 항목 중 하나(ADR-017 core_report_section).
    owner: core.report
    status: new
    table: core.core_report_section
    attributes:
      - { name: report_id, type: uuid, required: true, references: Report.id }
      - { name: section_no, type: integer, required: true, min: 1, max: 10 }
      - { name: title, type: string, required: true, constraints: "ai-io 6.5 고정 이름" }
      - { name: text, type: text, required: true }
      - { name: missing, type: boolean, required: true, constraints: "자료 부족" }
      - { name: templated, type: boolean, required: true, constraints: "검사에 걸려 템플릿으로 바꿨거나 고정 문장" }
    constraints:
      - "PRIMARY KEY(report_id, section_no)"

  - name: ReportCitation
    description: 항목 안 인용 하나.
    owner: core.report
    status: new
    table: core.core_report_citation
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: report_id, type: uuid, required: true, references: Report.id }
      - { name: section_no, type: integer, required: true, min: 1, max: 10 }
      - { name: session_id, type: uuid, required: true }
      - { name: utterance_seq, type: integer, required: false, min: 1, constraints: "TRANSCRIPT · CHILD_RECALL이면 필수" }
      - { name: quote, type: text, required: false, constraints: "TRANSCRIPT · CHILD_RECALL이면 저장 전사와 글자 단위 일치(앞뒤 공백만 무시). GUARDIAN_INPUT이면 비어 있다(가정 C7)" }
      - { name: spoken_at, type: timestamp, required: false }
      - { name: source, type: enum, required: true, allowed: [TRANSCRIPT, GUARDIAN_INPUT, CHILD_RECALL] }

  # ── 기존 표 변경 (더하기만) ────────────────────────────────────────────
  - name: SupportItem
    description: 생활 팁 항목(U5a 기존 표 core_support_item). U6은 nullable title 열 하나만 더한다(FR7.1, 가정 C14).
    owner: core.knowledge
    status: existing-extended
    table: core.core_support_item
    attributes:
      - { name: title, type: string, required: false, constraints: "새 열(V5). 비어 있으면 응답 title을 생략하고 27은 content 첫 문장을 제목으로 쓴다" }

  # ── 읽기만 하는 기존 엔터티 (U4a · U5a 소유) ───────────────────────────
  - name: ActivityRecord
    description: U4a 활동 기록(core_activity_record). U6의 유일한 읽기 원천(J6). 바꾸지 않는다.
    owner: core.record
    status: existing
    attributes:
      - { name: session_id, type: uuid, required: true, unique: true }
      - { name: final_status, type: enum, allowed: [ENDED, ENDED_UNCONFIRMED, ABORTED, DECLINED, FAILED] }
      - { name: end_reason, type: string }
      - { name: confirmed, type: boolean }
      - { name: transcript_json, type: json, constraints: "utterances[seq, speaker, text, uncertain, startedAt, endedAt]" }
      - { name: practice_json, type: json, required: false, constraints: "record.practice. 객체 또는 null(U4b-d)" }
  - name: ActivityCopy
    description: U4a 세션 사본(core_activity). failure_code(SERVER_RESTART 등)를 FR15 판정에 읽는다.
    owner: core.activity
    status: existing
  - name: GoalRecommendation
    description: U5a 목표 후보(core_goal_recommendation · core_recommendation_evidence). U6은 CandidateRegistry 공개 서비스로만 쓰고 표를 직접 읽지 않는다(FR13.3).
    owner: core.practice
    status: existing
```

## 요약

| 묶음 | 엔터티 | 비고 |
|---|---|---|
| 결과 작업 | ResultJob · ResultJobAttempt | SESSION_RESULT · REPORT 공용, DB 선점(Q1 A) |
| 세션 결과 | SessionResultState · ActivityResult · StructuredExperience · SceneJudgment | 결과는 버전, 현재 버전은 머리 행이 가리킨다 |
| 리포트 | Report · ReportActivity · ReportSection · ReportCitation | 버리면 머리 행만 묘비 |
| 기존 표 | SupportItem.title(더하기) | U4a · U5a 표는 바꾸지 않음, FK 없음 |

- 새 표 10개(ResultJob · ResultJobAttempt · SessionResultState · ActivityResult · StructuredExperience · SceneJudgment · Report · ReportActivity · ReportSection · ReportCitation)는 core V5 하나에 만든다. 기존 표에 제약을 더하지 않으므로 CI 마이그레이션 승인 줄은 필요 없다. `core_support_item.title`은 nullable 열 더하기다.
- 삭제 참가자 70(FR14.1)은 위 새 표 10개를 guardian_id(리포트 하위는 report_id)로 지운다. PDF 파일은 없다(FR10.3).
- 응답에 나가지 않는 칸: SceneJudgment.value · timing · rationale · live_label · differs_from_live, StructuredExperience.content_json, ActivityResult.matching_log_json · pending_candidates_json(FR8.3).

## Sources

- 요구사항: `inception/requirements-analysis/requirements.md` FR1 ~ FR15, NFR2 · NFR7 · NFR11
- 이 단계 답: `functional-design-questions.md` Q1(실행기) · Q2(시간 값) · Q3(사상표) · Q5(정리 상태별 포함)
- 원래 기록: `261008-mujoong-service/inception/domain-design/components.md` ResultPipeline · Report, `decisions.md` ADR-017, `contract-design/contract-summary.md` 3절 C5 · 6절 · 8절
- 기존 스키마: `backend/core-api/src/main/resources/db/migration/core/V1__core_base.sql`(core_activity.failure_code), `V3__u4a_activity_core.sql`(core_activity_record), `V4__u5a_practice_core.sql`(core_support_item · core_goal_recommendation · core_recommendation_evidence)
