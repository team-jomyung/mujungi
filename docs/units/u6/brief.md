# U6 결과와 리포트 (u6-results-report) 구현 지시

브랜치: main에서 `unit/u6-results-report`를 새로 만든다.

같은 시기에 다른 유닛이 함께 진행된다.
- U4b(활동 세션 Realtime, realtime V3): `unit/u4b-activity-realtime`, PR #6. 작업은 끝났고 리뷰 · 병합을 기다린다.
- U5b(연습 Realtime · 연결 마무리, realtime V4): `docs/units/u5b/brief.md`. 아직 시작 전이다.

U2 · U3 · U4a(활동 Core · /parent, core V3) · U5a(연습 Core, core V4)는 main에 합쳐졌다(`docs/units/README.md`).

## 배경
- U6은 끝난 활동을 정리하고 기록 화면과 상담용 리포트를 만든다.
  - 정리: 경험 구조화 · 기록용 재판정 · 목표 후보 · 생활 팁 · 결과 문장 · 결과 수준
  - 화면: 26 · 27 · 28 · 30 · R-PDF, 17의 정리 상태
- 앞 유닛이 U6 몫으로 남긴 자리
  - U4a: `GET /api/records` · `GET /api/records/{id}`(대화 기록 칸)만 만들었다. 26 · 27 화면, 17 정리 끝 · 정리 실패, 「오늘 기록 보기」는 U6 몫이다(`docs/units/u4a/report.md` 남은 일, U4a-J11 · J14). ActivityRecorded를 내지만 구독자는 없다(U4a-J18).
  - U5a: 후보 등록 포트 CandidateRegistry · TextAiGateway · `PragmaticsKnowledge.requireVersion`을 두었다. 후보 생성 파이프라인 · 27 · 리포트는 하지 않았다(`docs/units/u5a/report.md`, U5a-J14).
- U6은 설계 문서가 아직 없다. **AI-DLC로 설계부터 한다**(아래 「AI-DLC로 진행하는 법」).

## 범위
ResultPipeline(주)
- 결과 작업 실행기 JobRunner(계약 8절)
  - 작업 종류: SESSION_RESULT · REPORT
  - 상태: REQUESTED → RUNNING → DONE/FAILED, 자동 3회(간격 5 · 15초, `mujoong.timing.result.job-attempts`)
  - 앱이 재시작되면 RUNNING을 FAILED로 굳힌다. 「다시 시도」는 FAILED일 때만 받는다.
- ActivityRecorded 구독(커밋 뒤 전달)으로 SESSION_RESULT를 접수한다.
  - 종료가 확인되지 않은 세션 · DECLINED는 정리하지 않는다(계약 3절 · 6절에 맞춰 설계에서 정한다).
- 작업 내용(`contracts/ai-io.md` 6절)
  - 경험 구조화(08 네 칸은 여기에만 넣는다)
  - 기록용 재판정(연습만, 온도 0): 장면 판정을 저장하고 진행 중 판정과의 차이를 표시한다.
  - 목표 후보 생성 → U5a CandidateRegistry.register
  - 생활 팁 매칭 · 결과 문장 · 결과 수준(서버 계산, 6.4) · 결과 버전
  - 인용 대조(저장 전사와 글자 단위), 금지 표현 2단계(6.6), 걸리면 고정 템플릿으로 바꾼다.

Report
- 28 미리보기(진행 중 제외) · 생성 · 취소
- 10개 항목 조립: 집계 · 인용 대조 · 결측 · 고정 템플릿 · 고지
- 30 리포트 확인 · 저장 확인 시트 값 · 저장 · 버림
- HTML → PDF, 한글 글꼴. 30과 같은 10개 항목, 인용 전사 일치, 진행 중 판정값 없음
- 리포트는 60초 안에 READY가 되어야 한다(`mujoong.timing.report.ready-target`, NFR8).

확장(J17, 더하기만)
- U5a PracticeGoal에 `ScenarioLookup`, PragmaticsKnowledge에 `BehaviorDefinitionLookup` 읽기 포트를 붙인다(계약 8절).
  - main 코드에 둘 다 없다. U6이 확장으로 만든다.
  - 고정된 옛 정의 버전(RETIRED 포함)도 읽어야 한다. `requireVersion`을 쓴다.
- U5a `CandidateEvidencePort`의 실제 어댑터(근거 전사 확인)를 붙인다. 지금은 확인 불가를 돌려준다(`contracts/ports.md` 4절).

API(C5)
- `contracts/parent-api.yaml`에는 아직 C5가 없다. `contract-summary.md` 3절대로 더한다.
  - `GET /api/sessions/{id}/result` · `/result/status` · `POST /result/retry`
  - `GET /api/reports/preview` · `POST /api/reports` · `GET /api/reports/{id}` · `/pdf` · `POST /saved` · `/discard`
- 응답 스키마 시험으로 노출 금지 필드를 막는다.
  - 진행 중 판정값 · 재판정 라벨 · 근거 문장 · 보호자 입력 원문은 넣지 않는다. 장면 결과는 칩 5종으로만 내보낸다(FR4.3 · A10, 계약 2절).

화면
- 26 달력 · 그날 목록(U4a의 `/api/records`를 쓴다)
- 27 끝난 이유 · 연습 목표별 장면 결과 칩 · 결과 문장 · 제공한 도움 · 세 칸 · 정리 중/실패/자료 부족 · 후보 「담기」(U5a `POST /api/goal-selections`)
- 28 · 30 · 저장 확인 시트 · 「저장했어요」 · R-PDF
- 17 정리 끝 · 정리 실패, 「오늘 기록 보기」를 다시 켠다(U4a-J14가 감춰 뒀다).
- 27에 08 배경을 보일지 정한다(U4a-J11은 응답에서 뺐다).

데이터 · 삭제
- core 스키마 `V5__u6_results_report.sql`(더하기만)
- 계정 삭제 참가자 `ORDER_RESULT = 70`: 작업 · 결과 · 구조화 · 장면 판정 · 리포트 · 항목 · 메타 · PDF 파일. 활동 기록(90)보다 먼저 지운다.

스토리: US5.1 ~ US5.5 · US4.3 중 U4a · U5a가 하지 않은 수용 기준(`docs/units/u4a/design.md` · `docs/units/u5a/design.md` 2절).
완료 조건은 `bolt-plan.md` B7과 같다.

## 정본
기록 폴더 `R = aidlc/spaces/default/intents/261008-mujoong-service/`.

| 무엇 | 어디 |
|---|---|
| 범위 · 완료 조건 | `R/inception/units-generation/unit-of-work.md` U6 절, `R/inception/delivery-planning/bolt-plan.md` B7 |
| 요구사항 | `R/inception/requirements-analysis/requirements.md` FR4.3 · FR6.2 ~ FR6.5 · FR7.4 · FR7.6 · FR7.7 · FR7.11 · NFR8 · A4 · A10 |
| 스토리 · 수용 기준 | `R/inception/user-stories/stories.md` US4.3 · US5.1 ~ US5.5 |
| 화면 | `R/inception/refined-mockups/mockups.md` S-17 · S-26 · S-27 · S-28 · S-30 · R-PDF, §1 F3 ~ F6, §9-32 ~ 9-35 |
| 컴포넌트 · 엔터티 | `R/inception/domain-design/components.md` ResultPipeline · Report(SceneJudgment · ReportSection 포함), `decisions.md` ADR-017 |
| 계약 | `R/inception/contract-design/contract-summary.md` 2절 노출 금지 필드 · 3절 C5 · 6절 SessionRecordCaptured `record.practice` · 8절 JobRunner · CandidateRegistry · ScenarioLookup · BehaviorDefinitionLookup · ActivityRecorded · 9절 |
| 계약 파일 | `contracts/ai-io.md` 6절 · 7절, `contracts/ports.md` 4절, `contracts/timing.md`(`result.*` · `report.*`), `contracts/schemas/records-response.schema.json` · `record-detail-response.schema.json` |
| 앞 유닛 | `docs/units/u4a/design.md` · `report.md`, `docs/units/u5a/design.md` · `report.md` · `review-1.md`, `docs/units/u4b/design.md` U4b-R11(결과 입력 스냅숏) |

화면 설계 v1.5j가 최우선이다. 다만 27의 연습 기록 · 28의 만드는 중 문구 · 30 · R-PDF는 `mockups.md` §1의 F3 ~ F6이 앞선다(`docs/units/README.md` 「규칙」).

## 정해 둔 값
- 마이그레이션
  - core 스키마에 `V5__u6_results_report.sql`을 둔다(더하기만).
  - realtime 스키마는 바꾸지 않는다.
- 병합 순서
  - Flyway `outOfOrder`가 꺼져 있다. core V4(U5a)가 이미 main에 있으므로 U6은 다른 유닛을 기다리지 않고 합칠 수 있다.
  - U7(core V6)은 U6 다음에 합친다.
  - U5b가 core 스키마를 바꾸면 V7 이후 번호를 쓴다(U5b 지시서).
- 삭제 순서: `DeletionParticipant`에 `ORDER_RESULT = 70`을 더한다. 지금 값은 활동 기록 90 · ACTIVITY 100 · 연습 150 · DEVICE 200 · CONSENT 250 · CHILD_PROFILE 300 · MESSAGING 900이다. U7은 60이다.
- 시험
  - 태그 `u6`, 커버리지 범위 `backend/coverage-scope/u6.txt`(U1 형식), 범위 줄 80% 이상
  - 프런트를 바꾸므로 `frontend/coverage-scope/`에도 범위 파일을 만든다.
  - 경계: 작업 3회 · 재시작 FAILED · 리포트 60초는 시간 값을 줄여 주입해 시험한다.
- 공급자: 실제 텍스트 AI 공급자는 연결하지 않는다. TextAiGateway의 기본값은 U5a처럼 UNAVAILABLE이다. 구조화 · 재판정 · 결과 문장 · 리포트 항목은 시험 소스의 가짜 공급자로 검증한다.
  - 공급자가 UNAVAILABLE이면 결과 작업은 실패로 끝나고 27은 「다시 시도」를 보인다. 이것도 시험한다.
- PDF: 라이브러리 · 한글 글꼴은 열린 질문이다(contract-summary 11절 C5, OQ6). 설계에서 정하고 이미지 크기 · 라이선스를 적는다.
- 건드리지 않는 것
  - U1 · U2 · U3 · U4a · U5a 시험의 단언과 커버리지 하한. 단, U1 J120 · U4a-J14의 「오늘 기록 보기」 감춤 단언은 U6이 바꾸는 동작이다. 바꾸는 시험과 까닭을 설계에 적는다.
  - 다른 사람의 작업 기록(`261008-mujoong-service` · `261013-u4b-activity-realtime` · U5b 기록)
  - `README.md` · `contracts/README.md`
- 문서: `docs/units/u6/design.md`(새로), `docs/units/u6/report.md`(새로). 바꾼 계약은 `contracts/` 파일을 먼저 고치고 편차는 `contracts/deviations.md`에 `(U6-a)`부터 적는다.

## 다른 유닛과 맞물리는 곳
1. **U4b(PR #6, 아직 main에 없음)**
   - U6의 입력은 Realtime이 만드는 결과 입력 스냅숏이다. U4a가 SessionRecordCaptured로 받아 core 활동 기록에 넣고 ActivityRecorded를 낸다.
   - 스냅숏 모양은 `docs/units/u4b/design.md` U4b-R11과 계약 6절이다: utterances · gaps · recordScope · practice · audio, snapshotHash.
   - U4b가 합쳐지기 전에는 계약 6절 예시 값으로 core 활동 기록을 만들어 시험한다. U4b가 들어오면 main을 합치고 자유대화 끝에서 끝까지(대화 → 17 정리 끝 → 27) 다시 시험한다.
2. **U5b(시작 전)**
   - 연습의 기록용 재판정 입력은 `record.practice`다. U5b가 반응 · 도움마다 `scenarioId`를 싣는다.
   - U5b가 합쳐지기 전에는 계약 6절 예시 값으로 만든 대역으로 시험한다. 대역은 계약 예시 값으로만 만들고 U5b 내부를 흉내 내지 않는다(`team-allocation.md`).
   - U5b가 들어오면 연습 끝 → 27 장면 결과 칩 · 결과 문장을 다시 시험한다.
   - `record.practice`의 칸 이름 · null 규칙(FREE_TALK · DECLINED)은 U5b 담당과 처음 이틀 안에 같은 문장으로 맞춘다.
3. **U5a(main)**
   - CandidateRegistry · 후보 담기 · 18은 U5a 것을 그대로 쓴다.
   - ScenarioLookup · BehaviorDefinitionLookup은 U6이 U5a 컴포넌트에 더한다. U5a 저장소를 다른 패키지에서 직접 읽지 않고 그 컴포넌트의 공개 서비스로 낸다.
4. **U7(시작 전)**
   - 완료 푸시를 누르면 17로 간다. 17의 정리 상태는 U6 `/result/status`가 채운다. U7은 U6 데이터를 쓰지 않는다.

## AI-DLC로 진행하는 법
작업 기록은 사람마다 **자기 PC에서 새로** 만든다. 승인 증빙이 PC별이어서 남의 기록은 이어 갈 수 없다.
1. 저장소를 받고 `unit/u6-results-report` 브랜치로 옮긴 뒤 Claude Code에서 아래를 입력한다.

   ```
   /aidlc --new-intent --scope refactor "U6 결과와 리포트(u6-results-report) 구현. 지시 docs/units/u6/brief.md를 정본으로 한다. 인셉션 산출물은 261008-mujoong-service 기록의 것을 그대로 쓴다. 대화와 산출물은 한국어로 쓴다(코드 식별자·고정 토큰 제외)."
   ```

2. 안내대로 작업 기록을 만든다. 이름은 `u6-results-report`로 한다.
3. 배포 2단계를 뺀다. `/aidlc compose "deployment-pipeline과 deployment-execution을 빼줘"`를 입력하고, 확인 질문에 승인한다.
4. `/aidlc --depth Standard --test-strategy Standard`로 깊이와 시험 전략을 올린다.
5. 이렇게 하면 단계가 이렇게 남는다: 기존 코드 파악 → Requirements Analysis(U6 몫만 추림) → Functional Design → Code Generation → Build and Test.
   - Requirements Analysis는 뒤 단계가 그 결과물을 필수로 받아서 뺄 수 없다.
   - Functional Design 결과는 `docs/units/u6/design.md`에도 옮겨 둔다(다른 유닛과 같은 자리).
   - U6은 XL이다. Code Generation 계획을 ResultPipeline → Report → 화면 순으로 나누고, 단계마다 커밋한다.
6. 자기 기록 폴더(`aidlc/spaces/default/intents/<날짜>-u6-results-report/`)는 브랜치에 함께 커밋한다.

## 먼저 할 것
1. 「다른 유닛과 맞물리는 곳」 2를 U5b 담당과 맞춘다.
2. `contracts/ai-io.md` 6절에 프롬프트 본문 · 금지 표현 사전 · 생활 팁 목록이 어디까지 있는지 확인한다.
   - 비어 있는 것은 D 담당에게 묻는다.
   - 답을 기다리는 동안에는 가정(U6-J)으로 두고 진행한다.
3. PDF 라이브러리 · 한글 글꼴을 정한다(OQ6). 서버 이미지에 글꼴을 넣는 방법과 크기를 확인한다.
