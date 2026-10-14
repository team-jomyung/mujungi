# U6 결과와 리포트 — 정본 요약 (Requirements Analysis 조사)

> 저장소 `/home/jdmea/mujungi`, 기준 main `7a22b67`(U4b 병합 뒤). 읽기만 했고 저장소 파일은 고치지 않았다.
> 약칭: `R` = `aidlc/spaces/default/intents/261008-mujoong-service/`, `REQ` = `R/inception/requirements-analysis/requirements.md`,
> `ST` = `R/inception/user-stories/stories.md`, `CS` = `R/inception/contract-design/contract-summary.md`, `CMP` = `R/inception/domain-design/components.md`,
> `MU` = `R/inception/refined-mockups/mockups.md`, `AI` = `contracts/ai-io.md`, `TM` = `contracts/timing.md`, `BR` = `docs/units/u6/brief.md`,
> `KB` = `aidlc/spaces/default/codekb/mujungi/`. 줄 번호는 지금 main 기준이다.

---

## 1. 정본 요약

### 1.1 지시서 (BR)
- 범위: ResultPipeline(JobRunner SESSION_RESULT · REPORT, REQUESTED → RUNNING → DONE/FAILED, 자동 3회 간격 5 · 15초, 재시작 시 RUNNING → FAILED, 「다시 시도」는 FAILED에만), ActivityRecorded 구독, 6절 작업, Report(28 · 30 · PDF), 확장 J17(ScenarioLookup · BehaviorDefinitionLookup · CandidateEvidencePort 실제 어댑터), C5 API 9개, 화면 26 · 27 · 28 · 30 · R-PDF · 17 정리 상태 (BR 「범위」).
- 데이터: core `V5__u6_results_report.sql`(더하기만), `ORDER_RESULT = 70`(작업 · 결과 · 구조화 · 장면 판정 · 리포트 · 항목 · 메타 · **PDF 파일**) (BR 「데이터 · 삭제」, 「정해 둔 값」).
- 시험: 태그 `u6`, `backend/coverage-scope/u6.txt` 80%, `frontend/coverage-scope/` 새 파일, 시간 값은 줄여 주입. 실제 공급자 미연결, 기본 UNAVAILABLE, 가짜 공급자는 시험 소스에만 (BR 「정해 둔 값」).
- 완료 조건 = `bolt-plan.md` B7 (BR 「범위」 끝).

### 1.2 요구사항 (REQ)
| ID | 요점 (U6 관련) | 위치 |
|---|---|---|
| FR3.4 | 17: 마무리 중 → 정리 중 → 정리 끝(「오늘 기록 보기」 → 27) / 정리 실패(「기록에서 다시 시도」 → 27). 17엔 결과 · 해석 · 판정을 보이지 않음 | REQ:112-115 |
| FR4.3 | 연습 27 = 목표마다 장면 결과 칩 · 결과 문장 · 제공한 도움. 장면은 ORIGINAL · NEW_SCENARIO 둘. 칩은 기록용 재판정 값에서만(functional → 바로/도움 뒤 전달, partial · none → 다음 기회로, uncertain → 보류, 판정할 반응 없음 → 미실시 + 사유(예: 시간 종료 · 상황 생성 실패)). 진행 중 판정값 · 라벨 · 근거 · 확률 · 점수 비노출 | REQ:136-148 |
| FR4.4 | 4요소 갖춘 후보만 27 세 번째 칸, 「담기」/「담김 ✓」 → 18. 자유대화 · 연습 결과 모두에서 후보 | REQ:150-153 |
| FR6.1 | 26 달력 · 그날 목록, DECLINED는 종료 사실 · 시각만(상세 연결 없음), 27 「←」는 보던 날 유지 | REQ:199-201 |
| FR6.2 | 27 머리 · 끝난 이유 칸(사유 · 종료 확인 여부와 시각 · 확보 범위 · 미확인 구간) · 연습 목표 묶음 · 세 칸(대화 기록(전사 · AI 말 인용, **보호자 입력 배경**, 출처 구분, 미확인) · 생활 팁 · 후보) · 정리 중/실패/후보 없음/자료 부족 구분 | REQ:203-208 |
| FR6.3 | 승인된 Support Library에서 목표 행동 · 적용 조건 매칭, 조건 · 한계 함께. 초기 목록은 개발팀 제공. 없으면 '추가 관찰 필요'. 미승인 · 검색 점수 비노출 | REQ:210-212 |
| FR6.4 | 28 범위(빠른 선택 "3개(최근 2주 — 기본 · 이번 달 · 전체/직접)", 달력, 활동 체크, 진행 중 제외), 만드는 중 · 취소 · 실패 분기, 서버가 저장 기록 + 이름 · 나이로 10개 항목. 0건이면 단추 꺼짐, 60초 READY, 재시도 뒤 FAILED, period_from · period_to 저장 | REQ:214-218 |
| FR6.5 | 30 고지 + 10개 항목(표, 이름 · 순서 고정, 30 = PDF), 저장 확인 시트 → 저장했어요 → 공유/파일 열기/기록으로. 「나중에」 · 저장 전 「←」 = 버림. 결측 "자료 부족", 맞지 않는 인용 제거, 검증 실패 문장은 고정 템플릿. R-PDF 머리 · 바닥글 고지. 서비스 안 전송 없음 | REQ:220-241 |
| FR7.4 | 자유대화 끝 → 등장인물 · 상황 · 상대 말 · 아이 반응 · 설명 분리, 보호자 입력/아이 회상 출처 태깅, 의도 · 감정 추론 금지. 4요소 후보 자동 생성 · 등록, 근거는 실제 기록, 기록 없으면 후보 없음(G16) | REQ:259-262 |
| FR7.6 | 기록용 재판정: 연습 종료 확인 뒤 SESSION_RESULT가 장면마다 재판정(입력 = 전사 · 상황 대본 · 진행 중 판정 · 정의/기준 · 도움), IMMEDIATE · AFTER_HELP, 다르면 차이 표시 저장. 27 · 리포트는 재판정 값만 | REQ:269-273 |
| FR7.7 | (Should) 최종 실패면 27 · 17에 구분 표시, 「다시 시도」. 다시 해도 같은 활동 결과는 한 번만, 원본 불변 | REQ:275-277 |
| FR7.10 | 완료 푸시 조건(ACTIVE 거침 + CHILD_STOP · GUARDIAN_STOP · SILENCE + ENDED confirmed). 푸시에 '기록 정리 완료 여부' 넣지 않음 — U7 몫, U6은 17 상태만 | REQ:288-302 |
| FR7.11 | 종료 확인 뒤 결과 문장(연습 목표마다 1~2문장), 수치 · 원인 · 효과 금지, 템플릿 대체. 결과 수준 표(FULL · PARTIAL · INSUFFICIENT, 유효 기록 = 자유대화 내용 있는 발화 3개 / 연습 첫 반응 장면 1개). 화면 "17 · 27 · 30" | REQ:304-316 |
| NFR1 | 금지 표현 목록 기반 자동 시험(출력 게이트 · 27 · PDF 템플릿) | REQ:322 |
| NFR3 | 외부 모델에 최소 정보 | REQ:324 |
| NFR8 | 10개 항목 리포트 60초 이내 READY, **실패 시 3회 재시도 뒤 FAILED**, 활동 20건 시험 | REQ:329 |
| NFR9 | 남의 데이터 404/403, 로그에 전사 원문 없음 | REQ:330 |
| NFR12 | 리포트 메타 등 계정 삭제까지 보존, 삭제 뒤 DB · 파일 0건 | REQ:333 |
| A2 | 폴링은 12만 2초, 다른 화면은 진입 시 1회 | REQ:359 |
| A4 | 리포트(항목 · 인용) · 메타 서버 보존, 30 저장 시 계정 삭제까지, 「나중에」 · 「←」 버림은 서버에서도 폐기 | REQ:361 |
| A10 | 12 · 17 응답엔 전사 · 승인 AI 말만, 진행 중 판정 · 근거 · 보호자 입력 원문은 어떤 응답에도 없음. 재판정은 27 · 리포트 칩으로만, 경험 구조화는 27 대화 기록 칸 · 리포트 5항목 출처 표시 문장으로만 | REQ:367 |

### 1.3 스토리 · 수용 기준 (ST) — 앞 유닛 처리 표시
- **US3.6** (ST:295-312): U4a가 한 것 = AC3.6.1 · 3.6.2 앞부분(마무리 중 → 정리 중) · 3.6.5 · 3.6.6 · 3.6.7 · 3.6.9 · 3.6.10 (`docs/units/u4a/design.md:19`). AC3.6.11은 U4b. **U6 몫** = AC3.6.2 뒷부분(정리 끝 9-20), AC3.6.3(「오늘 기록 보기」 → 그 세션 27), AC3.6.4(정리 실패 9-21 → 27), AC3.6.8 유지, AC3.6.12(17 표시 = 세션 상태 × 결과 작업 상태: 정리 중 = (ENDED|ENDED_UNCONFIRMED|ABORTED|FAILED) + REQUESTED · RUNNING, 정리 끝 = READY, 정리 실패 = FAILED(자동 3회 뒤)).
- **US4.3** (ST:357-367): U5a가 한 것 = 후보 · 근거 등록/조회 포트, 담기/취소 API, 같은 선택 자원(AC4.3.2 · 4.3.3 · 4.3.5의 서버 쪽, 4.3.4의 등록 검사) (`docs/units/u5a/design.md:15`). **U6 몫** = AC4.3.1(27 연습 목표 칸 표시 "근거 · {관찰 문장} (hh:mm)", 후보 없음 9-28), 4.3.2 · 4.3.3 · 4.3.5의 27 화면, 4.3.4의 후보 생성 파이프라인.
- **US5.1** (ST:371-386): U4a는 `GET /api/records` 데이터만(AC5.1.2 줄 데이터 · 5.1.7 Asia/Seoul · 5.1.8 상태 거름, `docs/units/u4a/design.md:24`). **U6 몫** = AC5.1.1 ~ 5.1.10 화면 전부(26 지금은 redirect 스텁, KB `architecture.md:193`).
- **US5.2** (ST:388-406): U4a는 `GET /api/records/{id}` 대화 기록 칸 데이터(AC5.2.1 머리 · 5.2.2 전사 · gaps, 5.2.8 중 record-detail 스키마 부분). **U6 몫** = AC5.2.1 ~ 5.2.12 화면과 결과 API(5.2.3 팁 교집합, 5.2.4 정리 중 자동 갱신 · 다시 시도, 5.2.6, 5.2.7 FAILED일 때만 · 동시 작업 1개 · 이전 버전 보존, 5.2.8 결과 스키마의 금지 필드, 5.2.9 구조화에 의도 · 감정 필드 없음, 5.2.10 장면 칩 · 문장 · "도움 n회(동의함/거절함/답 없음)", 5.2.11 끝난 이유 칸, 5.2.12 재판정 일치 · 템플릿 · INSUFFICIENT 9-32).
- **US5.3** (ST:408-427) 전부 U6: AC5.3.1 최근 2주 = 오늘 포함 14일 기본 · 5.3.2 이번 달 = 1일~오늘, 전체 · 직접 = 기록 첫날~오늘 · 5.3.3 ~ 5.3.4 달력 · 5.3.5 진행 중 · FAILED · DECLINED 제외 · 5.3.6 0건 · 5.3.7 만드는 중 · 5.3.8 취소(B21) · 5.3.9 실패 분기 · 5.3.10 · 5.3.11 60초 · 3회 재시도 · 60초 넘으면 실패 화면 · 5.3.12 Asia/Seoul · 5.3.13 작업 1건 · 5.3.14 접근성.
- **US5.4** (ST:429-442) 전부 U6: AC5.4.1 ~ 5.4.8(파일명 `무중_상담용리포트_{아이}_mmdd-mmdd.pdf`, Web Share 미지원 시 숨김 B20, 「나중에」 · 「←」 버림, 저장 실패 토스트 9-30, 저장 사실만 이력).
- **US5.5** (ST:444-455) 전부 U6: AC5.5.1 10개 항목 · 집계 일치 · 5.5.2 인용 글자 단위 · 5.5.3 결측 · 템플릿 · 5.5.4 PDF 머리 · 바닥글 · 금지 내용 · 5.5.5 금지 표현 검사 대상(인용 부호 안 전사 제외) · 3 · 9항목 칩만 · 5항목 출처 · 남의 세션 "거절 · 제외(리포트에 0건)" · 한글 · 5.5.6 텍스트 PDF.
- 관련 가정: B19(DECLINED 날에도 점), B20(파일 열기 = 같은 PDF 새 탭, 공유 = Web Share), B21(취소 = 앱이 기다리기만 멈춤, 서버 작업은 저장 전 상태로 남아 A4로 폐기), B24(근거에 감정 추론 금지), OQ16(26 진입 날 · 30 「나중에」 라벨) (ST:575-619).

### 1.4 유닛 · 볼트
- `R/inception/units-generation/unit-of-work.md:63-69` U6: ResultPipeline(… 후보 생성 · **의미 검토** · 생활 팁 매칭 …), Report(28 미리보기 · 생성 · 취소 · 10개 항목 조립 · 30 · 시트 값 · HTML → PDF · 한글 글꼴 · 고지 · 저장/버림). 삭제 참가자 = 작업 · 결과 · 구조화 · 장면 판정 · 리포트 · 항목 · 메타(PDF 파일 언급 없음). 재판정 입력 = record.practice + PracticeGoal 상황 대본 · 정의 버전. U5b 전 계약 6절 예시 대역.
- `R/inception/delivery-planning/bolt-plan.md:91-96` **B7 = U6**(B6 = U7 웹 푸시, :17-18). 완료 조건: 결과 작업 하나(3회 · 재시작 FAILED) · 17 정리 상태 · 26 · 27(끝난 이유 · 칩 · 문장 · 도움 · 팁 · 후보 · 담기 · 자료 부족) · 재판정 저장 · 28 · 30(10개 항목 · 고지) · 시트 · PDF(30과 같음, 인용 일치, 진행 중 판정 없음) · 60초 READY. 가설: 60초 안 + 「다시 시도」 복구, 인용 대조 · 템플릿, 서버 PDF 한글 · Android Chrome 저장 · 공유(J8).

### 1.5 계약 요약 (CS)
- 2절 노출 금지 필드 (CS:34): 진행 중 판정(label · reason) · 재판정 원값(value · timing · rationale) · 구조화 원본(contentJson) · 08 원문 · 상황 본문 · 판정 기준. 칩 `sceneChip` 5종으로만. 응답 스키마 시험으로 막는다(CS:716).
- 2절 공통: 남의 자원 404, 삭제 접수 403 `ACCOUNT_DELETION_IN_PROGRESS`, 오류 봉투, 리포트 생성 `idempotencyKey`(같은 키 = 첫 결과, 다른 본문 409), 날짜 질의 Asia/Seoul (CS:29-33).
- 3절 C5 (CS:187-215): `GET /sessions/{id}/result` {jobStatus NONE|REQUESTED|RUNNING|DONE|FAILED, resultLevel?, sentences[{goalSelectionId?, text}], practice?{goals[{goalSelectionId, displayName, order, scenes[{sceneNo, exposureType, sceneChip DONE_IMMEDIATE|DONE_AFTER_HELP|NEXT_TIME|ON_HOLD|NOT_DONE, chipText, notDoneReason?}], help{count, consent ACCEPTED|DECLINED|NO_RESPONSE|NONE}}]}, tips[{supportItemId, condition, content, limitation}], noTipReason?, candidates[{recommendationId, displayName, reasonText, evidence[{utteranceSeq, kind}], selected, goalSelectionId?}]}; `/result/status` {sessionStatus, confirmed, jobStatus}; `POST /result/retry` 202 {REQUESTED} / 409 JOB_NOT_FAILED; `GET /reports/preview?from&to` {firstRecordDate, activities[{sessionId, date, activityType, startedAt}]}; `POST /reports` {from, to, sessionIds(≥1), idempotencyKey} → 202 {reportId, GENERATING} / 400 VALIDATION_FAILED; `GET /reports/{id}` {status GENERATING|READY|FAILED|SAVED|DISCARDED, level?, periodFrom, periodTo, activityCount, citationCount, child{displayName, age}, notice, sections[10]{sectionNo, title, text, missing, citations[{sessionId, utteranceSeq, quote, at, source TRANSCRIPT|GUARDIAN_INPUT|CHILD_RECALL}]}} (sections는 READY · SAVED만); `/pdf` (READY · SAVED만, 409 REPORT_NOT_READY); `POST /saved` 204; `POST /discard` 204.
- `contracts/parent-api.yaml`에 C5 없음 확인: 머리 주석 :4 "미룬 경로(U4 ~ U7 … /reports …)는 넣지 않는다", `/records`(:704) · `/records/{sessionId}`(:742)만 있다.
- 6절 SessionRecordCaptured (CS:556-575): finalStatus ENDED|ENDED_UNCONFIRMED|ABORTED|DECLINED|FAILED, confirmed, termination 4시각, snapshotHash, record{utterances[seq, speaker CHILD|AI, text, uncertain, startedAt, endedAt], gaps[from, to, kind DISCONNECT|UNCONFIRMED], recordScope, practice(nullable; goals[goalSelectionId, displayName, order, status DONE|SKIPPED|NOT_REACHED, skipReason, scenarios[scenarioId, exposureType, exposedAt], responses[scenarioId, responseType FIRST|AFTER_CUE|NEW_SCENARIO, utteranceSeq?, notPerformedReason?, label functional|partial|none|uncertain|NOT_PERFORMED], cues[scenarioId, consentResponse ACCEPTED|DECLINED|NO_RESPONSE, deliveredText, containsAnswerSentence, givenAt]]), audio}. 설명: "DECLINED면 utterances · practice · audio가 빈 배열".
- 7절 (CS:642-643): `result.job-attempts` 3회(간격 5 · 15초), `report.ready-target` 60초.
- 8절 (CS:656-683): ChildContext.get → {displayName, age, interests, …}(U6 Report 소비); DeletionParticipant(멱등, 파일 포함); CandidateRegistry.register{sessionId, childId, candidates[behaviorKey, behaviorVersion, reasonText, evidence[utteranceSeq, kind]]} · viewForSession → [{recommendationId, displayName, reasonText, selected}]; ScenarioLookup.get{scenarioIds} → [{scenarioId, goalSelectionId, behaviorKey, behaviorVersion, situation, partnerUtterance, triggerScript}]; BehaviorDefinitionLookup.get{behaviorKey, behaviorVersion} → {displayName, coreFunction, judgmentCriteria}; JobRunner.register{jobType, handler → DONE|FAILED(code)} · submit{jobType, subjectId, idempotencyKey} → {jobId, REQUESTED}; ActivityRecorded{sessionId, guardianId, childId, activityType, finalStatus, recordedAt}(커밋 뒤).
- 9절 (CS:691-708): 목표 후보 출력 kind GUARDIAN_INPUT|CHILD_RECALL, 경험 구조화 source GUARDIAN_INPUT|CHILD_TRANSCRIPT, 재판정 · 결과 문장 · 리포트 항목 · 금지 2차 분류 · 사전 항목 형식. 본문 · 프롬프트 · 사전 내용은 D 담당 문서(OQ4 · OQ14 · OQ15).
- 11절 (CS:718-732): C14 판정 기준 · 프롬프트 · 사전 v1 내용(U3 · U5 · U6), **C5 PDF 라이브러리 · 한글 글꼴(OQ6 → 3.3 · 3.4)**, C5 · C14 생성 모델과 60초 측정(활동 20건).

### 1.6 컴포넌트 · ADR
- `CMP:512-588` ResultPipeline: 작업 모델 J3(3회 · 재시작 FAILED · 「다시 시도」로만 새로 시작), REPORT는 Report 처리기에 맡김, 후보는 "서버 검사 + 의미 검토" 통과분만, 다시 해도 결과는 한 번만 현재 버전, **"미참여(DECLINED) · 기록 없는 활동은 작업을 만들지 않는다"**. 엔터티 ResultJob · ResultJobAttempt(attemptNo 1~3 · triggerType) · StructuredExperience · SceneJudgment(… value · timing · differsFromLive …, "다시 시도하면 새 버전에 다시 쓴다") · ActivityResult(version · resultLevel · contentJson · invalidatedAt) · SessionResultState(currentResultId · jobStatus).
- `CMP:644-713` Report: 범위 = 기간 + 활동 체크, 진행 중 제외, 0건 금지, REPORT 작업 60초 · 3회, 취소 = 앱만 멈춤(B21), A4 보존/폐기. 엔터티 Report(… savedAt · discardedAt) · ReportSection(sectionNo 1~10 유일 · templated) · ReportActivity.
- `CMP:1528-1547` J3(작업 하나), J6(ActivityRecord가 유일한 읽기 원천), **J8(PDF는 서버 HTML → PDF, 한글 글꼴 임베드, "파일은 서버에 남기지 않는다")**.
- `R/inception/domain-design/decisions.md:112-124` ADR-017: 1 · 3 · 4 · 7 · 8 · 9항목 서버 집계, 문장 항목은 TextAiGateway + 인용 대조 + 금지 2단계 → 템플릿, core_report_section, HTML 템플릿 → PDF(Java 라이브러리 예 OpenHTMLtoPDF, 한글 글꼴 임베드), AI에 이름 대신 별칭. 단점: 60초 합산, 글꼴이 이미지에 들어감. 대안 기각: 헤드리스 Chromium · 브라우저 인쇄.

### 1.7 화면 (MU)
- §1 F3 ~ F6 (MU:15-18): 27 연습 기록 = 끝난 이유 칸 + 목표별 칩 · 문장 · 도움, 28 만드는 중 "고른 기록으로 10개 항목을 만들고 있어요.", 30 = 고지 + 10개 항목 → 시트 → 저장했어요, R-PDF = "무중 상담용 리포트" · 고지 · 10개 항목 · 매 쪽 바닥글. F1-c 근거 감정 추론 제거.
- S-17 (MU:190-203): LINE-RESULT "기록을 정리하고 있어요" / 9-20 / 9-21, BTN-VIEW 「오늘 기록 보기」(정리 끝에 켜짐), BTN-RETRY 「기록에서 다시 시도」 → 27.
- S-26 (MU:213-215): 줄 글 3종, 활동 없음, 9-23, 9-24.
- S-27 (MU:217-231): HEAD · CARD-END(기술 문제 시 둘째 줄, 미확인이면 9-33) · LIST-GOALS(연습만) · SEG-TAB "대화 기록 · 생활 팁 n · 연습 목표 n" · TAB-TIPS **"제목 + 본문"** · 9-26 · 9-27 · TAB-GOALS · 9-28 · STATE 9-29 · 9-32. 칩 문구: 바로 전달 · 도움 뒤 전달 · 다음 기회로 · 보류 · "미실시 · {사유}(시간 종료 · 상황 준비 실패 · 활동 끝)", 색만으로 구분 금지.
- S-28 (MU:233-235), S-30 (MU:237-242), R-PDF (MU:244-247): 위 1.3의 문구 그대로. 바닥글 "무중 상담용 리포트 · {아이} · 쪽 n/N · 이 리포트는 진단 결과가 아니에요."
- §9 (MU:280-299): 9-19 ~ 9-21(17), 9-23 · 9-24(26), 9-25 ~ 9-29 · 9-32 · 9-33(27), 9-30 · 9-34(30), 9-35(푸시, U7). C2: 임시 문구.

### 1.8 계약 파일
- `AI` 6절 (:96-208): 6.1 공통 시스템 지시 전문 · 입력 최소화(별칭, id · 원음 미전송, activityNo) · 온도(재판정 0, 설정 `mujoong.ai.*`) · 검사 순서(스키마 → 인용 대조 → 금지 2단계) · 실패 규칙(공급자 오류 · 시간 초과 · 스키마 실패 = 시도 실패, 인용 · 금지는 템플릿). 6.2 재판정 입력 · 지시 요점 · 출력 JSON · value null + notDoneReason TIME_OVER|SCENARIO_FAILED|ACTIVITY_ENDED|NO_RESPONSE · differsFromLive · 칩 표. 6.3 결과 문장 출력 · 템플릿 2종. 6.4 결과 수준(내용 있는 발화 = 불확실 아님 + 어절 3개 이상, 첫 반응 장면 = FIRST · NEW_SCENARIO에 utteranceSeq). 6.5 항목별 만드는 쪽 · 규칙 · AI 출력(2 · 5 · 6 · 10) · 템플릿 · 고지 · level 규칙. 6.6 1차 사전(4절 형식, appliesTo RESULT_TEXT · PDF) + 초안 표 + 2차 AI 분류, 팁은 숨김.
- **6절에 없는 것**: 경험 구조화 · 목표 후보 생성 · 의미 검토 · 생활 팁 매칭의 입력/지시 절(1절 표에 출력 형식만), 작업별 프롬프트 본문, 정식 사전 v1(초안만), 생활 팁 목록.
- `AI` 7절 (:210-222): TextAiGateway는 상황 생성만, 기본 UNAVAILABLE, env `MUJOONG_TEXT_AI_*`, 설정 `mujoong.text-ai.*`.
- `contracts/ports.md` 4절 (:38-50): CandidateEvidencePort 기본 = 확인 불가 · 근거 없는 후보 거절, TextAiProvider UNAVAILABLE, PracticeDeletionParticipant @Order 150.
- `contracts/schemas/records-response.schema.json`: finalStatus ENDED|ENDED_UNCONFIRMED|ABORTED|DECLINED(FAILED 없음), goalCount · interrupted. `record-detail-response.schema.json`: additionalProperties false, {session, goals[displayName, order, status], utterances(Bubble: seq · speaker · text · final · uncertain · startedAt · endedAt), gaps, recordScope, endConfirmed} — 08 배경 · goalSelectionId 없음.
- `contracts/deviations.md`: U2-a ~ k, U4a-a · b, U4b-a ~ d, U5a-a ~ d. **U6 항목 없음 → 다음은 (U6-a)**. (U4b-c) 연습 마지막 목표 끝 = endReason CHILD_STOP + end_detail PRACTICE_COMPLETED, (U4b-d) practice는 객체 또는 null.

### 1.9 앞 유닛 문서
- U4a (`docs/units/u4a/design.md:118-132`, `report.md:97-104 · :141`): J9(27 404 = 남의 · 없음 · 기록 아직 없음 · **FAILED**, 정리 중 표시는 U6 결과 상태 API), J11(27 응답에 08 원문 안 넣음, 보일지는 U6), J14(17 종료면 늘 "기록을 정리하고 있어요", 「오늘 기록 보기」 감춤 — U1 J120 단언 유지), J16(FAILED + SERVER_RESTART면 17 '기기 응답 없이 끝냈어요'), J18(ActivityRecorded = Spring 앱 이벤트, AFTER_COMMIT, 구독자 없음), J23(활동 기록 삭제 90). 남은 일: 17 정리 끝/실패/「오늘 기록 보기」, 26 · 27 화면, 27 08 배경 → U6.
- U5a (`docs/units/u5a/design.md`, `report.md:24 · :86`, `review-1.md`): J1(:131, 후보 중복 키 = 출처 세션 + 아이 + 정의 버전, "근거 추가/대체 규칙은 U6 결과 버전 계약과 대조"), J7(:137, text-ai 3초 · 1회 · 250ms, "U6 작업 재시도 정책을 재사용하지 않는다"), J9(:139, structuredExperienceId nullable, U6 표 FK 없음), J14(:162, U6 기능 없음), J15(:163, 기본 포트는 @ConditionalOnMissingBean으로 교체), `requireVersion`은 U6 재판정용으로 남김(report:86), 리뷰 8(잠금 쥔 채 외부 확인 금지 → 근거 확인은 TX 밖).
- U4b (`docs/units/u4b/design.md:124-125 · :131 · :213`): R10(재시작 · 고아 세션도 FAILED + 스냅숏), R11(정산 뒤 스냅숏 한 번, utterances = 확정 아이 전사 + 게이트 통과 AI 말 · 승인 문구, uncertain = review_needed, practice null(U5b), DECLINED · ACTIVE 전 종료는 빈 배열, snapshotVersion 1), R17(연습 확장점, LAST_GOAL → CLOSING(CHILD_STOP, PRACTICE_COMPLETED)), J28(practice 객체 또는 null).

### 1.10 코드 사실 (KB, 검증됨)
- `KB/architecture.md:180-201` U6 접점: ActivityRecorded 구독자 0 · PRACTICE practice는 U5b 전 null · CandidateRegistry 있음 · ScenarioLookup/BehaviorDefinitionLookup 없음 · TextAiProvider/Gateway는 generateScenario만 · C5 없음 · core V4 최신 · `structured_experience_id` FK 없음 · 70 상수 없음 · PDF 라이브러리 · 글꼴 · 쓰기 볼륨 없음 · 26 스텁 · 17 감춤.
- `KB/code-quality-assessment.md:37-52` 부채 D1 ~ D11(누락 복구 경로 필요, 기본 빈 교체, 사전 형식 불일치(txt vs JSON), 설정 키 불일치 · ConfigKeyContractTest 세 곳 동시 변경, 읽기 전용 루트 · tmpfs 64m, record.practice 모양이 contracts/에 없음).
- 직접 확인: `SessionRecordCapturedHandler.java:34-37 · 111`(DECLINED만 비우고 FAILED 기록도 저장, 새로 넣을 때만 ActivityRecorded), `CandidateRegistry.java:82`(evidence kind ∈ {GUARDIAN_INPUT, CHILD_RECALL}, utteranceSeq ≥ 1 필수), `V4__u5a_practice_core.sql:50-56`(evidence CHECK utterance_sequence_no > 0), `:29-35`(core_support_item: behavior_key · condition_text · content_text · limitation_text, 제목 · 태그 없음), `:73-86`(core_scenario: scenario_text · partner_role_text · trigger_script, partnerUtterance 열 없음), `:100-118`(정의 3개 · 팁 1개 시드), `DeletionParticipant.java:19-34`(90 · 100 · 150 · 200 · 250 · 300 · 900), `infra/compose.demo.yml:154-176`(core-api mem 1.6g, /tmp 64m, hardening), 감춤 단언 `frontend/tests/u1/parent/done-17.test.tsx:109-112` · `frontend/tests/u4a/parent/screens-u4a.test.tsx:155`.

---

## 2. 시간 값 · 키

| 키 | 값 | 정의된 곳 | 코드 상태 |
|---|---|---|---|
| `mujoong.timing.result.job-attempts` | 3회(간격 5 · 15초) | CS:642 · TM:69(2절 예약표) · BR | **없음** (application.yml · CoreProperties에 없음, KB D6) |
| `mujoong.timing.report.ready-target` | 60초 | CS:643 · TM:70 · NFR8 | **없음** |
| `mujoong.text-ai.request-timeout` | 3s | TM:163 · application.yml:208-212 · TextAiProperties | 있음(U5a 상황 생성용) |
| `mujoong.text-ai.retry.max-retries` · `.backoff` | 1 · 250ms | TM:164-165 · application.yml | 있음 |
| `mujoong.ai.*`(모델 · 온도) | 값 없음 | AI 6.1 | **없음, 이름이 코드 관례와 다름**(부딪힘 C1) |
| `mujoong.timing.parent.poll-interval` | 2s | TM:44 (12 전용 "다른 화면은 진입 때 1회") | web에 있음 |
| `mujoong.timing.parent.request-timeout` | 10s | TM:76 | web에 있음(PDF 받기 · 리포트 요청에 적용됨) |
| `mujoong.limits.db-statement-timeout` · `db-lock-timeout` · `background-db-concurrency` | 10s · 2s · 3 | application.yml limits | 있음(리포트 집계 질의 · 작업 동시성에 걸림) |
| `mujoong.inbox.*` · `mujoong.outbox.*` | — | TM 4절 | 있음(ActivityRecorded는 Inbox가 아닌 앱 이벤트라 직접 관계 없음) |
| `mujoong.timing.session.record-settle` | 2s | TM:79 | realtime(결과 스냅숏 생성 시점) |

정본에 값이 **없는** U6 시간(뒤 4절 G5 · G11 · G12): 작업 시도 1회 시한, 작업 선점 임대(재시작 판정), 누락 접수 점검 주기, 17 · 27 · 28 정리 상태 폴링 주기, 저장 안 된 리포트 폐기 시한, U6 텍스트 AI 호출 시한(3s는 상황 생성 기준).

---

## 3. 부딪힘 (conflicts) — 24건

**C1. 텍스트 AI 설정 키 이름**
- AI 6.1(:119) "모델 · 값은 설정(`mujoong.ai.*`)" ↔ AI 7절(:212) · TM 9절 · `application.yml:208` `mujoong.text-ai.*`. ConfigKeyContractTest가 TM ↔ yml ↔ 설정 레코드를 대조(TM:10).
- 선택지: (a) `mujoong.text-ai.*` 아래에 작업별 하위 키(`mujoong.text-ai.result.*` · `.report.*` 모델 · 온도 · 시한)를 더하고 ai-io 6.1 문장을 고쳐 (U6-a) 편차로 적는다 (b) `mujoong.ai.*`를 새로 만들어 두 이름 공존 (c) U5a 키를 `mujoong.ai.*`로 개명(깨는 변경).
- 추천 (a): U5a 동작 · 시험 무변경, 키 한 줄기, 계약 파일 먼저 고치는 규칙(BR)과 맞다.

**C2. 자동 재시도 횟수 · 키 모양**
- CS:642 · TM:69 "3회(간격 5 · 15초)" = 시도 상한 3(최초 + 재시도 2) ↔ NFR8(REQ:329) · AC5.3.11 "실패 시 3회 재시도 뒤 FAILED"(= 최대 4회로 읽힘) ↔ AC3.6.12 "FAILED(자동 3회 뒤)". 또 키 하나에 횟수 + 간격 목록이 묶여 있어 타입 설정(Duration/int)으로 그대로 옮길 수 없다.
- 선택지: (a) 시도 3회(최초 + 5초 뒤 + 15초 뒤), 키 둘 `result.job-attempts: 3` · `result.job-retry-backoff: [5s, 15s]`(같은 그룹, 더하기) (b) 재시도 3회 = 시도 4회(간격 하나 더 필요, 정본에 값 없음) (c) 키 하나에 목록만 두고 횟수 = 목록 길이 + 1.
- 추천 (a): 간격이 두 개뿐이라 시도 3회가 정본 값과 맞고, NFR8 문장은 "자동 시도 3회"로 해석 명시(U6 편차).

**C3. 리포트 60초 목표와 재시도 · 시한 · 컨테이너 한도**
- NFR8 60초 READY + 3회 ↔ 재시도 대기만 5 + 15 = 20초, AC5.3.11 "60초를 넘기면 만들기 실패 화면(타임아웃)", text-ai 호출 3초(U5a 기준, 20건 입력에는 짧음), `limits.db-statement-timeout` 10s, core-api mem 1.6g · /tmp 64m · 읽기 전용 루트(`infra/compose.demo.yml:154-176`), 글꼴 임베드 PDF 렌더 시간. ADR-017 단점에 이미 적힘.
- 선택지: (a) ready-target을 서버 절대 마감으로 쓴다: 마감을 넘기면 남은 시도 없이 FAILED, 시도별 AI 시한은 남은 예산에서 나눔(U5a C10 15초 예산 방식과 같음) (b) 60초는 성공 경로 측정 목표로만 두고 3회는 끝까지 시도(서버 FAILED가 60초 뒤일 수 있음, 앱은 60초에 실패 화면) (c) 앱 타임아웃만 60초, 서버는 무제한.
- 추천 (a): AC5.3.11의 실패 화면과 서버 상태가 어긋나지 않고, 화면이 실패라는데 서버가 나중에 READY가 되는 고아 리포트(G11)가 없다. 20건 측정은 가짜 공급자 지연 주입으로 시험.

**C4. 어떤 끝난 세션에 SESSION_RESULT를 만드나**
- BR "종료가 확인되지 않은 세션 · DECLINED는 정리하지 않는다(계약 3절 · 6절에 맞춰 설계에서 정한다)" ↔ AC3.6.12(ST:311) 정리 중 = (ENDED | **ENDED_UNCONFIRMED | ABORTED | FAILED**) + REQUESTED · RUNNING ↔ CMP:520 "DECLINED · 기록 없는 활동은 작업을 만들지 않는다" ↔ FR7.6/7.11 "(연습의) 종료가 확인되면/종료 확인 뒤" ↔ FR6.2 · AC5.2.11 "종료 확인 여부와 확보 범위를 따로 보여 준다"(미확인 종료도 27이 있음).
- 선택지: (a) '종료 확인' = 세션이 종료 상태(터미널)가 됨으로 읽고, 종료 상태 기록 중 DECLINED와 발화 0건(ACTIVE 전 종료)만 제외(jobStatus NONE) (b) confirmed=true(ENDED)만 정리, ENDED_UNCONFIRMED · ABORTED는 NONE (c) 모두 정리하되 confirmed=false면 결과 수준 상한 PARTIAL.
- 추천 (a) + 결과 수준은 FR7.11 표대로(정상 마무리 아님 → FULL 불가, G2): AC3.6.12 · 17 사유 카드(연결 끊김 · 기기 응답 없이)와 26 줄 "중간에 끊김"이 모두 27을 전제로 한다. BR 문장은 "종료 전(CLOSING 등) 세션은 정리하지 않는다"로 해석 명시.

**C5. FAILED(진행 중 서버 재시작) 세션의 정리 · 27**
- 17은 FAILED + SERVER_RESTART를 '기기 응답 없이 끝냈어요'로 보이고(U4a-J16, AC3.6.9) 정리 중을 거친다(AC3.6.12), 기록도 저장된다(`SessionRecordCapturedHandler.java:111`, U4b-R10). 그러나 26은 FAILED를 숨기고(AC5.1.8, records 스키마 enum), 27은 FAILED에 404(U4a-J9), 28도 제외(AC5.3.5). 정리 끝이 되면 「오늘 기록 보기」가 404로 간다.
- 선택지: (a) FAILED는 작업을 만들지 않고 17은 jobStatus NONE → 「오늘 기록 보기」 없이 「홈으로」만 (b) failureCode SERVER_RESTART + 발화 있음이면 정리하고 26 · 27 · 28에도 보이게 확장(U4a 계약 바꿈, AC5.1.8과 어긋남) (c) 정리는 하되 27로 못 가게 단추 숨김.
- 추천 (a): AC5.1.8 · AC5.3.5 · U4a-J9 세 곳이 FAILED 비노출로 일치하고 A7 경로는 Must 시연 밖(Q6). AC3.6.12의 FAILED는 "정리 상태가 없는 종료"로 해석 명시하고 (U6-x) 편차에 적는다.

**C6. 27의 08 배경(보호자 입력)**
- FR6.2(REQ:206) · AC5.2.2 "보호자 입력 배경, 보호자 입력/아이 회상 출처 구분" ↔ A10 · CS:34 "08 원문은 어떤 보호자 응답에도 없다, 경험 구조화는 27 대화 기록 칸의 출처 표시 문장으로만" ↔ U4a-J11(27 응답에서 뺌, U6이 정함). C5 `/result` 응답에 구조화 결과 칸이 없다(CS:188).
- 선택지: (a) 원문은 계속 빼고, `/result`에 구조화 항목 요약 문장 + source(보호자 입력/아이 회상)를 선택 필드로 더해(편차 (U6-a) 새 선택 필드) 대화 기록 탭 위에 출처 꼬리표로 보인다 (b) 27에 배경을 아예 보이지 않음 (c) 08 원문을 27에 그대로 보임.
- 추천 (a): A10 문장과 AC5.2.2를 동시에 만족하는 유일한 길. (c)는 CS:34 · A10 위반, (b)는 AC5.2.2 미달.

**C7. 출처 · 근거 종류 값 이름**
- 경험 구조화 source `GUARDIAN_INPUT|CHILD_TRANSCRIPT`(CS:703 · AI:19) ↔ 후보 evidence kind `GUARDIAN_INPUT|CHILD_RECALL`(CS:669 · AI:18 · 코드 CHECK) ↔ 리포트 citation source `TRANSCRIPT|GUARDIAN_INPUT|CHILD_RECALL`(CS:210) ↔ AI 6.5 "source(GUARDIAN_INPUT · CHILD_RECALL)를 구조화 결과에서 그대로 단다"(구조화엔 CHILD_RECALL이 없음). 또 리포트 citation에 GUARDIAN_INPUT `quote`가 있으면 "보호자 입력 원문을 옮기지 않음"(FR6.5 5항목)과 부딪힘.
- 선택지: (a) 저장 · 응답 값은 `GUARDIAN_INPUT` · `CHILD_RECALL`로 통일하고 구조화 내부 `CHILD_TRANSCRIPT`는 AI 출력 단계에서 CHILD_RECALL로 사상(사상표를 설계에 둠), 리포트 citation의 TRANSCRIPT는 2 · 6항목 전사 인용용으로 유지, GUARDIAN_INPUT citation은 quote 없이 출처 표시만 (b) 구조화 값을 계약 9절대로 두고 응답만 변환.
- 추천 (a): 화면 꼬리표("보호자 입력"/"아이 회상", MU:239)와 이미 저장된 U5a 값에 맞고, 08 원문 인용 위험을 닫는다.

**C8. 보호자 입력 근거에 utteranceSeq가 필수**
- 계약 evidence `{utteranceSeq, kind: GUARDIAN_INPUT|CHILD_RECALL}`(CS:669) · 코드 `CandidateRegistry.java:82`(utteranceSeq ≥ 1) · `V4…sql:53`(utterance_sequence_no > 0). 08 네 칸은 전사가 아니라 seq가 없다.
- 선택지: (a) GUARDIAN_INPUT 근거는 등록하지 않고 후보마다 CHILD_RECALL 근거(전사 seq)가 최소 하나 있어야 등록(근거 없는 후보는 등록 안 함) (b) 08 칸 번호(1~4)를 seq 자리에 쓰기로 규칙을 정함(의미 혼용, 시각 "(hh:mm)" 표시 불가) (c) U5a 표에 열을 더함(V5에서 U5a 표 변경, 승인 필요).
- 추천 (a): AC4.3.1 "근거 · {관찰 문장} (hh:mm)"은 전사 시각이 필요하고 U5a 코드 · 스키마 변경이 없다. 보호자 입력은 reasonText 문장 안에서만 출처로 언급.

**C9. 27 후보 칸이 쓸 데이터 부족**
- `CandidateRegistry.viewForSession` → {recommendationId, displayName, reasonText, selected}(코드 · CS:670) ↔ C5 `/result` candidates {…, evidence[{utteranceSeq, kind}], selected, goalSelectionId?}(CS:188). AC4.3.1 근거 시각, AC4.3.3 「담김 ✓」 취소(DELETE `/goal-selections/{goalSelectionId}`), AC4.3.5 18 「×」 뒤 27 되돌림에 evidence · goalSelectionId가 필요.
- 선택지: (a) U5a 컴포넌트에 읽기 메서드를 더함(J17 더하기만: 기존 viewForSession은 그대로, 새 반환형에 evidence · goalSelectionId) (b) U6이 U5a 저장소를 직접 읽음(BR "다른 패키지에서 직접 읽지 않는다" 위반) (c) 27이 `/goal-selections` 목록과 화면에서 대조.
- 추천 (a).

**C10. `record.practice` 칸 이름 · null 규칙**
- CS:568 설명 "DECLINED면 utterances · practice · audio가 빈 배열" ↔ (U4b-d) · U4b-J28 "practice는 객체 또는 null(자유대화 · DECLINED · ACTIVE 전 종료 · U5b 전 PRACTICE)" ↔ U5b 지시서(`docs/units/u5b/brief.md:91`) "FREE_TALK와 DECLINED이면 null 또는 빈 배열". 또 `notPerformedReason`(자유 문자열) ↔ AI 6.2 notDoneReason enum 4종, goals.status `DONE|SKIPPED|NOT_REACHED` · skipReason → 칩 사유 사상표 없음, practice 내부 모양이 `contracts/`에 없음(KB D11).
- 선택지: (a) U6 설계에서 "practice = 객체 또는 null, 빈 배열 없음"(U4b-d)과 사상표(NOT_REACHED → ACTIVITY_ENDED 또는 TIME_OVER, SKIPPED + 상황 실패 → SCENARIO_FAILED, NOT_PERFORMED 응답 → NO_RESPONSE)를 정하고 events.asyncapi에 practice 모양을 더하는 안을 U5b와 이틀 안에 같은 문장으로 맞춤(BR 맞물림 2) (b) U5b가 정할 때까지 U6은 계약 6절 예시만 받아들이고 사상은 가정(U6-J)으로.
- 추천 (a)를 U5b 합의 안건으로, 합의 전에는 (b)로 진행.

**C11. 미실시 칩 사유 문구 · 종류**
- FR4.3(REQ:146) "예: 시간 종료 · 상황 생성 실패" ↔ MU:231 "시간 종료 · 상황 준비 실패 · 활동 끝"(27 연습 기록은 F3 영역이라 mockups가 앞섬) ↔ AI 6.2 표에 `NO_RESPONSE 반응 없음` 추가. FR6.2/AC5.2.6 "기록 수신 공백을 침묵 · 거부로 쓰지 않는다"와 '반응 없음' 문구의 긴장.
- 선택지: (a) 문구는 mockups 셋 + AI의 NO_RESPONSE(연습 20초 무응답은 기록 수신 공백이 아니라 판정 결과 NOT_PERFORMED) — 문구는 C2 임시 문구로 표시 (b) NO_RESPONSE도 '활동 끝'류로 합침 (c) NO_RESPONSE를 NEXT_TIME으로.
- 추천 (a), 문구 "반응 없음"은 9절 임시 문구로 NFR1 · NFR2 사전 검사 대상에 넣고 D 담당 확인 항목으로 남김.

**C12. AI 작업 내용 비어 있음 (프롬프트 · 사전 · 팁 목록)**
- AI 머리(:4) "6절은 입력 · 출력 · 지시 요점 · 검사 규칙까지 정한다" + CS 9절 "본문 · 프롬프트 · 사전 내용은 D 담당 문서" ↔ 실제 6절엔 경험 구조화 · 후보 생성 · 의미 검토 · 팁 매칭 절이 없고, 사전 v1은 "초안"(AI:197), 팁 시드 1건(`V4…sql:115-118`, ACCEPT_STOP만). FR6.3 "초기 목록은 개발팀이 제공".
- 선택지: (a) 빠진 작업의 입력 · 출력 · 지시 요점을 U6이 6절 형식으로 초안을 더하고 가정(U6-J)으로 표시, 사전은 6.6 초안을 v1 후보로 씀, 팁은 정의 3개마다 시드 몇 건을 V5/참조 시드로 — D 담당 확인 대기 (b) D 답이 올 때까지 해당 작업을 고정 템플릿 · '추가 관찰 필요'로만.
- 추천 (a) (BR 「먼저 할 것」 2 그대로). 실공급자가 없어 시험은 가짜 공급자로 형식 · 검사 규칙만 확인.

**C13. 금지 표현 1차 사전의 형식 · 위치**
- AI 6.6 1차 = "4절 형식의 사전 중 appliesTo RESULT_TEXT · PDF"(4절 파일은 `backend/realtime-activity/.../gate/seed-dictionary.json`, Realtime 모듈) ↔ Core는 `resources/textai/u5a-forbidden-patterns.txt`(18줄, appliesTo 없음)를 `ForbiddenExpressionPolicy`로 쓰고 CandidateRegistry도 같은 정책(KB D5).
- 선택지: (a) Core에 4절 형식 JSON 사전 파일을 새로 두고 U6 결과 · 리포트 검사는 이것을 쓴다, U5a txt 정책은 그대로 (b) U5a txt를 넓힘(U5a 상황 생성 · 후보 등록 동작이 바뀜) (c) Realtime 파일을 공유 모듈로 옮김(U3 소유 변경).
- 추천 (a): 앞 유닛 단언 불변(BR 「건드리지 않는 것」).

**C14. 생활 팁 매칭 기준 · 표시 칸**
- AC5.2.3 "목표 행동 태그 · 적용 조건 태그가 세션 결과 태그와 교집합 ≥ 1(서버 매칭 로그)" · MU:227 "제목 + 본문" ↔ `core_support_item`은 behavior_key + condition_text(자유 글) · content · limitation, 제목 · 태그 열 없음, `PragmaticsKnowledge.supportItems(behaviorKey)` · C5 tips {supportItemId, condition, content, limitation}(제목 없음).
- 선택지: (a) 태그 = behavior_key 하나로 해석(세션 결과 태그 = 그 세션의 연습 목표 · 후보 behaviorKey 집합), 적용 조건 태그는 이번 범위 밖으로 두고 매칭 로그에 남김, 제목은 content 첫 문장 또는 V5에서 support_item에 title 열을 더함(더하기) (b) V5에 태그 표를 신설해 조건 태그 매칭 (c) AI가 condition_text 일치를 판정.
- 추천 (a) + 제목은 응답 선택 필드 `title`(편차)로 더하고 시드에 채움. (b)는 근거 자료(태그 목록)가 정본에 없다.

**C15. PDF 파일을 서버에 두나**
- BR 삭제 대상 "PDF 파일" ↔ J8(CMP:1538) "파일은 서버에 남기지 않는다" ↔ unit-of-work 삭제 목록(PDF 없음) ↔ 컨테이너 읽기 전용 · 영속 볼륨 없음(KB D8).
- 선택지: (a) `GET /pdf` 때마다 저장된 항목으로 렌더링해 스트림, 파일 저장 없음 → 삭제 참가자는 PDF 파일 0건(파일 삭제 단계 없음, 근거 J8) (b) 생성 시 파일 저장 + 볼륨 추가 + 삭제 참가자에 파일 삭제(compose · infra-validate 불변식 변경).
- 추천 (a): 30과 PDF가 같은 저장 항목을 그려 일치(ADR-017 이점), 인프라 변경 없음. BR 문구는 "PDF 파일이 생긴다면"으로 해석.

**C16. PDF 라이브러리 · 한글 글꼴 (OQ6)**
- CS:725 열린 질문, ADR-017 "Java 라이브러리(예 OpenHTMLtoPDF), 한글 글꼴 임베드", AC5.5.6 텍스트 PDF, 이미지 제약(1.6g · 읽기 전용 · /tmp 64m), BR "이미지 크기 · 라이선스를 적는다".
- 선택지: (a) OpenHTMLtoPDF(LGPL, PDFBox 기반, HTML/CSS → PDF) + 한글 OFL 글꼴(예: Noto Sans KR 부분집합 서브셋 TTF) 클래스패스 리소스 (b) iText 7/8(AGPL — 소스 공개 의무) (c) PDFBox 직접 그리기(HTML 템플릿 포기).
- 추천 (a): ADR-017의 HTML 템플릿 결정과 맞고 라이선스 부담이 작다. 글꼴은 서브셋으로 이미지 크기 증가를 줄이고, 렌더는 메모리 안(파일 쓰기 없음)으로 /tmp 제약을 피한다. 정확한 버전 · 크기는 설계에서 측정해 적는다.

**C17. 「오늘 기록 보기」 감춤 단언**
- U1 J120 단언 `frontend/tests/u1/parent/done-17.test.tsx:109-112` · U4a-J14 단언 `frontend/tests/u4a/parent/screens-u4a.test.tsx:155` ↔ AC3.6.2 · 3.6.3 · MU:200(정리 끝에 켜짐). BR이 이 두 단언만 예외로 허용.
- 선택지: (a) 두 시험의 "감춤" 단언을 "정리 끝(DONE)에서만 보임 · 그 밖 감춤"으로 바꾸고 설계에 바꾼 시험 · 까닭(AC3.6.2/3.6.3)을 적음 (b) 옛 시험은 두고 U6 시험만 추가(옛 단언이 실패하므로 불가).
- 추천 (a).

**C18. 정리 상태 · 리포트 상태 폴링**
- A2(REQ:359) · TM:44 "12만 폴링, 다른 화면은 진입 때 1회" ↔ AC5.2.4 "27 정리 중 자동 갱신", AC3.6.2 17 상태 전이, CS:210 `/reports/{id}` "만드는 중 폴링", 지금 Done17은 종료 상태까지 같은 주기로 다시 읽음(`Done17.tsx:26`).
- 선택지: (a) 정리 중(REQUESTED · RUNNING) · 만드는 중(GENERATING)일 때만 `parent.poll-interval`(2s)로 상태 경로를 폴링하고 끝나면 멈춤 — A2 해석 명시(편차) (b) 새 키로 다른 주기 (c) 폴링 없이 진입 때 1회(AC5.2.4 미달).
- 추천 (a).

**C19. 17 상태 이름 · 17에 결과 문장?**
- AC3.6.12 "정리 끝 = READY" ↔ C5 jobStatus `DONE`(리포트만 READY). FR7.11 머리 "화면 17 · 27 · 30" ↔ FR3.4 · AC3.6.8 "17에는 결과 · 해석이 보이지 않는다".
- 선택지: (a) 17 정리 끝 = jobStatus DONE으로 읽고(이름 차이 해석 명시), 17엔 결과 문장을 보이지 않음(FR7.11의 17은 정리 상태 표시로 해석) (b) 17에 결과 문장 노출.
- 추천 (a).

**C20. 자유대화 결과 문장을 어디에 보이나**
- FR7.11 · AI 6.3 "자유대화는 활동 하나에 1~2문장" 생성 ↔ FR6.2 · MU:224 결과 문장은 연습 LIST-GOALS에만, 화면 설계 C.S-27.23(자유대화 대화 기록)에 자리 없음 — 화면 설계 우선.
- 선택지: (a) 생성 · 저장 · `/result.sentences`(goalSelectionId null)로 내보내되 27 자유대화 화면엔 그리지 않음(자리 없음), 리포트 입력으로만 씀 (b) 27 끝난 이유 칸 아래 한 줄로 그림(화면 설계에 없는 요소 추가) (c) 자유대화는 생성하지 않음(FR7.11 미달).
- 추천 (a), 화면 노출 여부는 팀장 확인 항목으로.

**C21. 「다시 시도」의 작업 · 버전 모델**
- AC5.2.7 "연타 · 중복은 새 작업을 만들지 않는다(동시 작업 1개), 이전 결과 버전 · 원본 보존" · FR7.7 "결과가 한 번만 생성" ↔ CMP ResultJobAttempt attemptNo **1~3** · triggerType(수동 재시도가 시도 4번째가 되면 범위 밖) ↔ SceneJudgment "다시 시도하면 새 버전에 다시 쓴다" ↔ U5a-J1 후보 중복 키(세션 + 아이 + 정의 버전)로 재시도 후보는 합쳐짐, "근거 추가/대체 규칙은 U6이 정함".
- 선택지: (a) 「다시 시도」 = 같은 세션의 새 ResultJob 행(시도 1~3 다시), SessionResultState가 현재 작업 · 현재 결과를 가리킴, DONE 결과 버전만 currentResultId로 바뀜, 이미 등록된 후보는 그대로 두고 새 근거만 추가 (b) 같은 작업 행에 시도를 이어 붙임(attemptNo 상한 깨짐) (c) 실패한 작업의 부분 산출물 삭제 뒤 재실행.
- 추천 (a): 시도 상한 · 동시 1개 · 버전 보존을 모두 만족.

**C22. 삭제 순서와 구조화 결과 참조**
- `ORDER_RESULT = 70`(BR)이 구조화 결과를 PRACTICE 150보다 먼저 지움 ↔ `core_goal_recommendation.structured_experience_id`(V4, FK 없음, U5a-J9)가 그 행을 가리킴. V5에서 FK를 걸면 70이 먼저 지울 때 위반, 또 V4 표 변경은 `migration-approvals.txt` 승인 필요(KB D9).
- 선택지: (a) FK 없이 논리 참조 유지(삭제 순서 문제 없음, 매달린 id는 150이 곧 지움) (b) FK + ON DELETE SET NULL(V4 표 변경 승인) (c) 순서를 150 뒤로(BR 값과 다름, 활동 기록 90보다 먼저라는 조건 위반).
- 추천 (a).

**C23. 28 빠른 선택 개수**
- FR6.4(REQ:215) "빠른 선택 3개(최근 2주 · 이번 달 · 전체/직접)" · AC5.3.2 「전체 · 직접」 하나 ↔ AC5.3.1 · MU:235 "최근 2주 · 이번 달 · 전체 · 직접"(글자로는 네 개로 읽힘).
- 선택지: (a) 칩 3개, 셋째 라벨 "전체 · 직접"(누르면 기록 첫날~오늘 + 달력 직접 고르기) (b) 칩 4개.
- 추천 (a): FR6.4 · AC5.3.2가 같은 뜻이고 화면 그림 C.S-28.27.2~4가 세 칸(27.2 ~ 27.4)이다.

**C24. ScenarioLookup 출력 칸과 저장 열**
- CS:672-673 out {situation, partnerUtterance, triggerScript} ↔ `core_scenario`(V4:73-86) scenario_text · partner_role_text(고정 역할 지시, U5a-J22) · trigger_script · relation_type · opportunity_condition — 상대 말(partnerUtterance) 열이 없다.
- 선택지: (a) situation = scenario_text, partnerUtterance = trigger_script(기회 트리거 대사), partner_role_text는 넘기지 않음(역할 지시라 재판정 입력 아님), 사상을 (U6-x) 해석 명시로 (b) U5a에 열 추가(V4 변경).
- 추천 (a).

---

## 4. 정본에 답이 없는 것 (gaps) — 18건

- **G1 이 기록의 범위 대 뒤로 미룰 것**: U5b 전 PRACTICE 끝-끝, 실공급자, U7 진입 재조회. 선택지 (a) U6은 계약 6절 예시 대역 · 가짜 공급자로 전 기능 시험, U5b · 실공급자 통합 재시험은 남은 일로 (b) PRACTICE 재판정은 U5b 뒤로. 추천 (a)(BR 맞물림 2).
- **G2 '정상 마무리'(FULL 조건) 정의**: FR7.11 표에 정의 없음. 선택지 (a) FR7.10 완료 푸시 조건과 같게 = ENDED + confirmed + CHILD_STOP · GUARDIAN_STOP · SILENCE(연습 PRACTICE_COMPLETED는 CHILD_STOP, U4b-c) (b) ENDED만 (c) SILENCE 제외. 추천 (a). CONSENT_WITHDRAWN은 표대로 INSUFFICIENT.
- **G3 PRACTICE인데 practice = null**(U5b 전 · 확장점 실패 PRACTICE_EXTENSION_FAILED): 선택지 (a) 재판정 생략, 목표 묶음 없음, 결과 수준 = 유효 기록 기준 미달로 INSUFFICIENT(자료 부족), 작업은 DONE (b) 작업 FAILED. 추천 (a)(실패는 재시도로 고쳐지지 않음).
- **G4 제공한 도움 `help{count, consent}` 정의**: 목표당 도움 제안 횟수인지 전달된 Cue 수인지, 장면 2개의 동의 응답이 다를 때 consent 하나를 어떻게 고르는지 없음. 선택지 (a) count = cues[] 행 수(제안 · 동의 응답 기록 수), consent = 첫 제안의 응답, 없으면 NONE (b) count = 전달된 Cue 수(ACCEPTED만). 추천 (a)(A9: 목표당 Cue 1회라 실제로 0 또는 1, 화면 "도움 1회(거절함)"이 나오려면 제안 기준이어야 함).
- **G5 작업 실행기 세부**: 작업 멱등 키(SESSION_RESULT = sessionId + 차수), 선점 · 임대 시간, 시도 1회 시한, 재시작 때 **REQUESTED(재시도 대기) 작업** 처리(J3는 RUNNING만 말함), 실행 스레드 수. 선택지 (a) REQUESTED는 이어 실행, RUNNING만 FAILED, 시도 시한 = U6 키 새로(더하기) (b) 둘 다 FAILED. 추천 (a).
- **G6 ActivityRecorded 누락 복구**: 앱 이벤트라 커밋 뒤 장애 때 잃음(U4a-J18, KB D1). 선택지 (a) 기동 시 + 주기적으로 활동 기록 ↔ 작업 표 대조해 빠진 세션 접수(새 키) (b) 복구 없음(보호자 「다시 시도」도 FAILED가 아니라 불가). 추천 (a).
- **G7 계정 삭제와 실행 중 작업 경합**: FR1.6 "삭제 요청 뒤 늦게 도착한 자료는 저장되지 않는다". 선택지 (a) 쓰기 트랜잭션마다 보호자 행 잠금 · 상태 확인(U5a PracticeAccountPort 방식), 삭제 중이면 작업 중단 (b) 70 참가자가 작업 취소 후 삭제. 추천 (a) + (b)의 멱등 재삭제.
- **G8 부분 실패 경계**: 후보 등록(U5a 자체 TX)은 성공 · 이후 결과 저장 실패 시. 선택지 (a) 결과 버전 저장을 마지막 한 TX로 두고, 후보는 멱등 등록이라 재시도 때 합쳐짐(C21) (b) 후보 등록을 결과 저장 뒤로. 추천 (b)(실패 결과에 매달린 후보가 27에 먼저 보이지 않음) — 단 viewForSession이 결과 버전과 무관하게 보이므로 설계에서 확인.
- **G9 28 미리보기에 들어갈 활동**: 결과 작업이 정리 중 · 정리 실패 · INSUFFICIENT인 활동, 날짜 기준(startedAt, U4a-J8), firstRecordDate 정의(DECLINED 포함?). 선택지 (a) ENDED · ENDED_UNCONFIRMED · ABORTED 전부 포함, 정리 안 된 활동은 항목에서 자료 부족으로 처리 (b) 정리 끝(DONE)만. 추천 (a)(AC5.3.5는 상태만 거름), firstRecordDate는 미리보기 대상 활동의 첫날.
- **G10 리포트 크기 한도**: 「전체」 범위 활동 수 상한 · AI 입력 크기 · 나눠 보내기. 선택지 (a) 상한 없이 AI 입력은 항목별 인용 후보를 서버가 추려 최대 N개로 자름 (b) 활동 수 상한(예: 50) 넘으면 400. 추천 (a) + NFR8 20건 측정.
- **G11 리포트 재시도 · 시간 초과 · 취소의 서버 상태**: 28 「다시 시도」의 idempotencyKey(새 키?), 앱 60초 타임아웃 뒤 서버가 READY가 된 리포트, 취소한 리포트를 언제 폐기할지(B21 "A4 규칙으로"만). 선택지 (a) 다시 시도 = 새 키 새 리포트, 이전 것은 자동 폐기, C3 (a)로 시간 초과 고아 없음 (b) 같은 키 재사용(같은 키 = 첫 결과 FAILED가 돌아와 불가). 추천 (a).
- **G12 저장 안 된 리포트 폐기 · DISCARDED 표현**: A4 "서버에서도 폐기" ↔ C5 status DISCARDED가 존재. 선택지 (a) 버리면 항목 · 활동 · 인용 행 삭제, 머리 행은 DISCARDED 묘비(내용 없음)로 두고, 저장 안 된 READY · FAILED는 일정 시간 뒤 같은 방식으로 정리(새 키) (b) 행 전체 삭제 → GET 404(계약 DISCARDED 값과 어긋남). 추천 (a).
- **G13 저장 흐름 순서**: 시트 「저장」 → `GET /pdf` 받기 → `POST /saved` 순서 · 실패 처리 · 반복 저장 · 저장 뒤 재다운로드. 선택지 (a) 앱이 PDF 받기 성공 뒤 saved 기록, 받기 실패면 9-30 토스트 · 30 유지, saved는 멱등(첫 savedAt 유지) (b) saved 먼저. 추천 (a)(저장 안 된 것을 저장으로 남기지 않음).
- **G14 citationCount("인용 n개") 정의**: 항목 전체 인용의 합인지 (sessionId, utteranceSeq) 중복 제거인지. 추천: 중복 제거한 전사 인용 수(보호자 입력 출처 제외), 30 시트 · PDF가 같은 값.
- **G15 남의 세션이 섞인 생성 요청**: AC5.5.5 "거절 · 제외(리포트에 0건)" 둘 중 무엇인지. 선택지 (a) 요청 전체 400 VALIDATION_FAILED(존재 누설 없음) (b) 조용히 빼고 생성 (c) 404. 추천 (a)(U5a CandidateRegistry와 같은 처리, 0건 보장 · 앱 버그 노출).
- **G16 26 진입 때 선택 날**(OQ16): 선택지 (a) 오늘 (b) 가장 최근 활동일. 추천 (a)(달력 기본 달과 일치, 다른 날은 점으로 보임) — 27에서 돌아오면 보던 날(AC5.1.5).
- **G17 기록 도착 전 27**: `/records/{id}`는 기록 없음에 404(U4a-J9) → 17 「오늘 기록 보기」는 DONE 뒤에만 켜져 문제 없지만 26 · 직접 진입 때 처리. 선택지 (a) 27이 `/result/status`로 정리 중 표시, 기록 404면 정리 중으로 폴링 (b) 404 화면. 추천 (a).
- **G18 후보 생성 범위 · 의미 검토**: 연습 세션에서도 후보를 만드는지(FR4.4 "자유대화 · 연습 결과에서"), 목표당/세션당 후보 수, INSUFFICIENT면 후보 없음인지, unit-of-work · CMP의 "의미 검토"가 무엇인지(AI 2차 확인? 6절에 없음). 선택지 (a) 두 활동 모두 생성, INSUFFICIENT면 생성 안 함, 의미 검토 = 후보 근거 인용 대조 + 금지 표현 2단계 + 정의 ACTIVE 확인(서버 검사)으로 한정하고 AI 의미 검토는 가정으로 미룸 (b) 자유대화만. 추천 (a).

(그 밖 작은 것: PDF 응답 파일명의 한글 아이 이름은 `Content-Disposition filename*` UTF-8, `Cache-Control: no-store` — 설계에서 정하면 됨.)

---

## 5. 이미 정해져 있어 묻지 말 것

- 볼트: U6 = **B7**, U7 = B6 (bolt-plan.md:17-18 · :91). 지시서와 일치, 부딪힘 아님.
- 작업 상태 REQUESTED → RUNNING → DONE/FAILED, 종류 SESSION_RESULT · REPORT, 재시작 시 RUNNING → FAILED, 「다시 시도」는 FAILED만, 409 JOB_NOT_FAILED (J3 · CS:191 · BR).
- 시간 값: 자동 시도 3회 · 간격 5 · 15초, 리포트 60초 (CS:642-643). 값 자체는 묻지 않는다(키 모양만 C2).
- 칩 사상 5종 · chipText 문구 · '전달' 기본값, ORIGINAL = 장면 1 · NEW_SCENARIO = 장면 2, 출력에 없는 장면 = null(ACTIVITY_ENDED), differsFromLive 저장, 재판정 온도 0 (FR4.3 · AI 6.2).
- 결과 수준 표와 6.4의 두 정의(어절 3개 이상 · FIRST/NEW_SCENARIO에 utteranceSeq), 리포트 level 규칙 (FR7.11 · AI 6.4 · 6.5).
- 결과 문장 템플릿 2종, 숫자는 시각 · 장면 번호만, 인용 앞뒤 공백만 무시한 글자 대조, 검사 순서 스키마 → 인용 → 금지 2단계, 인용 · 금지 걸림은 실패 아님(템플릿) (AI 6.1 · 6.3).
- 10개 항목 이름 · 순서 · 항목별 만드는 쪽(1 · 3 · 4 · 7 · 8 · 9 서버, 2 · 5 · 6 · 10 AI), 9항목 = 범위 안 같은 목표 2회 미만이면 자료 부족 · 향상 계산 없음, 10항목 고정 문장, 고지 문구, 템플릿 = 인용 날짜순 나열 / 없으면 "자료 부족" (FR6.5 · AI 6.5).
- 30 · 시트 · 저장했어요 · R-PDF 머리 · 바닥글 · 파일명 문구, 「나중에」 · 저장 전 「←」 = 버림, 시트 「취소」 = 30 유지, 공유 = Web Share(미지원이면 숨김), 파일 열기 = 같은 PDF 새 탭 (AC5.4.x · B20 · MU S-30 · R-PDF).
- 28 동작: 최근 2주 = 오늘 포함 14일 기본, 이번 달 = 1일~오늘, 달력 탭 규칙 · 미래 불가, 0건 문구 · 단추 꺼짐, 만드는 중 · 실패 문구, 취소 = 앱만 멈춤(B21), 「만들기」 재탭 = 작업 1건, Asia/Seoul (AC5.3.x · MU S-28).
- 17 · 26 · 27 문구 9-20 · 9-21 · 9-23 · 9-24 · 9-25 ~ 9-29 · 9-32 · 9-33 · 9-34 · 9-30(임시 문구 C2이나 이 단계에서 다시 묻지 않음).
- 노출 금지 필드 목록과 응답 스키마 시험으로 막기, 남의 자원 404, 삭제 접수 403 (CS:29-34).
- ActivityRecorded는 커밋 뒤 Spring 앱 이벤트(U4a-J18), 기록은 세션당 한 번 · 해시 동일(U4a-J6 · J22 · U4b-J15), DECLINED는 머리만.
- 입력 최소화: 아이 이름 대신 "아이", 별칭, id · 원음 미전송, activityNo, 08 네 칸은 구조화에만 (AI 6.1 · FR7.4 · NFR3).
- 마이그레이션 core V5 더하기만, realtime 변경 없음, outOfOrder 꺼짐, U7 = V6, U5b = V7 이후 (BR · `docs/units/README.md:58`).
- 삭제 순서 `ORDER_RESULT = 70`(90 · 100 · 150 · 200 · 250 · 300 · 900, U7 60), 멱등 (BR · CS:662).
- 공급자: 실공급자 미연결, TextAiProvider 기본 UNAVAILABLE, 가짜 공급자는 시험 소스에만, UNAVAILABLE이면 결과 작업 FAILED · 27 「다시 시도」 (BR · ports.md 4절).
- 시험: 태그 u6, 백엔드 · 프런트 커버리지 범위 파일, 범위 줄 80%, 시간 값 줄여 주입 (BR).
- 확장(J17): ScenarioLookup · BehaviorDefinitionLookup은 U5a 컴포넌트의 공개 서비스로, 옛 버전(RETIRED 포함)은 `requireVersion`, CandidateEvidencePort 실제 어댑터(같은 타입 빈 교체, U5a-J15) (BR · u5a/report.md:86).
- 계약 변경 절차: `contracts/` 먼저, 편차는 `(U6-a)`부터, 더하기만 안전 (BR · CS:710-716).
- 문서 우선순위: 화면 설계 v1.5j, 단 27 연습 기록 · 28 만드는 중 · 30 · R-PDF는 mockups F3 ~ F6 (BR · REQ:4).
