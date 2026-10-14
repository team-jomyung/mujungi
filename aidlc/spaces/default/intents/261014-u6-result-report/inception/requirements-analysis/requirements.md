# U6 결과와 리포트 — 요구사항 명세

> 작업 기록 `261014-u6-result-report` · Requirements Analysis(2.3) · 2026-10-14
>
> 이 문서는 원래 기록 `261008-mujoong-service`의 요구사항 · 스토리 · 계약 중 U6 몫을 추려 구현 단위로 다시 묶은 것이다. 새 업무 요구사항은 만들지 않았다. 출처가 서로 다르면 다음 순서로 따른다.
> 1. 화면 설계 v1.5j. 다만 27의 연습 기록 · 28의 만드는 중 문구 · 30 · R-PDF는 `mockups.md` §1 F3 ~ F6이 앞선다.
> 2. 원래 기록의 승인 산출물: requirements · stories · contract-summary · components · mockups
> 3. 지시 `docs/units/u6/brief.md`, 계약 파일 `contracts/`, 앞 유닛 설계(U4a · U4b · U5a)
> 4. 이 단계의 답변(`requirements-analysis-questions.md` Q1 ~ Q11, 가정 확인)
>
> 원래 기록의 요구사항 ID는 `원 FR6.2`처럼 앞에 "원"을 붙여 구분한다. 스토리 · 수용 기준 ID(US5.2 · AC5.2.4)는 원래 기록의 것을 그대로 쓴다. 이 문서의 `FR{n}` · `NFR{n}`은 이 작업 기록 안에서만 쓰는 추적 키다. 정본끼리 부딪힌 곳의 번호(C1 ~ C24)와 정본에 답이 없던 곳의 번호(G1 ~ G18)는 질문 파일의 것이다.

## 의도 분석

- **목표**: 끝난 활동을 정리하고, 보호자가 기록을 다시 보고, 상담용 리포트를 만들어 저장하게 한다.
  - 정리: 경험 구조화 · 기록용 재판정 · 목표 후보 · 생활 팁 · 결과 문장 · 결과 수준
  - 화면: 17의 정리 상태, 26 기록 달력, 27 기록 상세, 28 리포트 범위, 30 리포트 확인 · 저장, R-PDF
- **왜 지금인가**
  - U4a는 활동 기록을 저장하고 ActivityRecorded를 내지만 받는 쪽이 없다(U4a-J18). 17의 「오늘 기록 보기」는 감춰져 있고 26은 스텁이다.
  - U5a는 후보 등록 포트만 두었고 후보를 만드는 쪽이 없다(U5a-J14). 근거 확인 포트는 늘 "확인 불가"라 후보가 모두 거절된다.
  - core V5는 U6 몫이고, U7(core V6)은 U6 다음에만 합칠 수 있다.
- **유형 · 범위 · 복잡도**: 기존 코드 확장(brownfield). 범위는 `backend/core-api`(새 결과 · 리포트 패키지, U5a 컴포넌트 확장, 삭제 참가자), `frontend/src/parent/`(17 · 26 · 27 · 28 · 30), `contracts/`다. realtime 스키마는 바꾸지 않는다. 복잡도는 높다(XL). 비동기 작업 실행기, AI 출력 검사, 10개 항목 조립, 서버 PDF, 화면 다섯 개가 한 유닛에 모인다.
- **깊이**: Standard. 질문은 정본끼리 부딪히거나 정본에 답이 없는 곳 11건만 물었고, 나머지 27건은 추천안을 가정으로 받았다.

## 기능 요구사항

### FR1 결과 작업 실행기 (JobRunner)

**FR1.1 작업 모델** — Must · 출처 원 FR7.7, 계약 8절 JobRunner, components J3, brief 「범위」
- 작업 종류는 SESSION_RESULT · REPORT 둘이다. 상태는 REQUESTED → RUNNING → DONE 또는 FAILED다.
- 한 작업은 자동으로 최대 3회 시도한다: 최초, 5초 뒤, 15초 뒤(가정 C2). 시도마다 ResultJobAttempt(attemptNo 1~3 · triggerType)를 남긴다.
- 시도 실패 = 공급자 오류 · 시간 초과 · 출력 스키마 실패다. 인용 대조 · 금지 표현에 걸린 문장은 실패가 아니라 고정 템플릿으로 바꾼다(ai-io 6.1).
- 앱이 재시작되면 RUNNING 작업은 FAILED로 굳힌다. 재시도를 기다리던 REQUESTED 작업은 이어서 실행한다(가정 G5).
- 같은 대상(세션 또는 리포트)에 동시에 실행 중인 작업은 하나뿐이다.
- 처리기는 `JobRunner.register{jobType, handler}`로 붙이고, `submit{jobType, subjectId, idempotencyKey}`는 같은 키에 같은 작업을 돌려준다.
- 완료 기준
  - 3회 실패 → FAILED, 2회째 성공 → DONE, 재시작 → RUNNING만 FAILED · REQUESTED는 이어 실행을 시험으로 확인한다. 시간 값은 줄여 주입한다.
  - 같은 키 submit 두 번에 작업 1건이 남는다.

**FR1.2 다시 시도** — Must(원 FR7.7은 Should, B7 완료 조건) · 출처 원 FR7.7, AC5.2.7, 계약 C5 `POST /result/retry`
- 「다시 시도」는 결과 작업이 FAILED일 때만 받는다. 아니면 409 `JOB_NOT_FAILED`다.
- 다시 시도 = 같은 세션의 새 작업 행이다(시도 1~3 다시). 세션의 현재 작업 · 현재 결과 버전만 바뀌고 이전 버전과 원본 활동 기록은 그대로 둔다(가정 C21).
- 연타 · 중복 요청은 새 작업을 만들지 않는다.
- 완료 기준: FAILED → 다시 시도 → DONE에서 결과 버전 2개 · 현재 1개, DONE · RUNNING에서 409, 연타 3회에 작업 1건.

### FR2 결과 접수

**FR2.1 ActivityRecorded 구독** — Must · 출처 계약 8절 ActivityRecorded, U4a-J18, brief 「범위」
- ActivityRecorded(커밋 뒤 Spring 앱 이벤트)를 받아 SESSION_RESULT를 접수한다. 같은 세션은 한 번만 접수한다.
- 완료 기준: 활동 기록 저장 커밋 뒤에 작업 1건이 생기고, 롤백된 저장에는 작업이 생기지 않는다.

**FR2.2 정리 대상** — Must · 출처 질문 Q1(A) · Q2(B), AC3.6.12, components ResultPipeline
- 종료 상태가 된 활동 기록은 정리한다: ENDED · ENDED_UNCONFIRMED · ABORTED.
- 진행 중 서버 재시작으로 끝난 FAILED(failureCode SERVER_RESTART)도 발화가 1개 이상 있으면 정리한다(Q2 B, FR15).
- 정리하지 않는 것: DECLINED, 발화 0건(ACTIVE 전 종료), 발화 없는 FAILED. 이들의 jobStatus는 NONE이다.
- 지시서의 "종료가 확인되지 않은 세션은 정리하지 않는다"는 "종료 전(CLOSING 등) 세션은 정리하지 않는다"로 해석하고 편차 `(U6-x)`로 적는다.
- 완료 기준: 위 상태 조합마다 작업 유무를 시험으로 확인한다.

**FR2.3 누락 복구** — Must · 출처 가정 G6, codekb D1
- 앱 이벤트는 커밋 뒤 장애 때 사라질 수 있다. 기동 때와 주기적으로 활동 기록과 작업 표를 대조해 정리 대상인데 작업이 없는 세션을 접수한다. 주기는 새 시간 키로 둔다.
- 완료 기준: 이벤트를 받지 못한 활동 기록이 다음 점검에서 접수된다.

### FR3 경험 구조화

**FR3.1 구조화** — Must · 출처 원 FR7.4, AC5.2.9, ai-io 6절 · 6.1, 질문 Q7(A)
- 자유대화 활동에서 등장인물 · 상황 · 상대 말 · 아이 반응 · 설명을 나눠 저장한다(StructuredExperience).
- 출처는 보호자 입력(08 네 칸)과 아이 회상(전사)으로 꼬리표를 단다. 저장 · 응답 값은 `GUARDIAN_INPUT` · `CHILD_RECALL`로 통일한다(가정 C7).
- 의도 · 감정을 추론하는 필드를 두지 않는다.
- 08 네 칸은 구조화 입력으로만 쓴다. AI에는 아이 이름 대신 "아이"와 별칭을 보내고, id · 원음은 보내지 않는다(ai-io 6.1, 원 NFR3).
- 입력 · 지시 · 출력 절이 ai-io 6절에 없으므로 U6이 6절 형식으로 초안을 더하고 가정 `U6-J`로 표시한다. D 담당 확인을 기다린다.
- 완료 기준: 가짜 공급자 출력으로 구조화 행 저장, 출처 꼬리표 두 종류, 의도 · 감정 필드 없음(스키마 시험).

### FR4 기록용 재판정과 장면 결과 칩

**FR4.1 재판정** — Must · 출처 원 FR7.6, 원 FR4.3, ai-io 6.2, 질문 Q8(A)
- 연습 활동에서 장면마다 재판정한다. 입력은 전사 · 상황 대본(ScenarioLookup) · 진행 중 판정 · 행동 정의와 판정 기준(BehaviorDefinitionLookup) · 제공한 도움이다. 온도는 0이다.
- 결과는 SceneJudgment(value · timing IMMEDIATE/AFTER_HELP · differsFromLive)로 저장한다. 진행 중 판정과 다르면 차이를 표시해 저장한다. 다시 시도하면 새 결과 버전에 다시 쓴다.
- 장면은 ORIGINAL(장면 1) · NEW_SCENARIO(장면 2) 둘이다. 출력에 없는 장면은 null(ACTIVITY_ENDED)이다.
- 입력 `record.practice`는 객체 또는 null이다(U4b-d). 빈 배열은 받지 않는다.
- practice가 null인 연습 활동(U5b 전, 확장점 실패)은 재판정 없이 결과 수준 INSUFFICIENT로 DONE이 된다(가정 G3, Q8 A).
- 완료 기준: 계약 6절 예시 값 대역으로 장면 2개 재판정 · 차이 표시 저장, practice null → INSUFFICIENT · DONE.

**FR4.2 칩 사상** — Must · 출처 원 FR4.3, ai-io 6.2 칩 표, mockups S-27, 가정 C11
- 칩은 재판정 값에서만 만든다. 5종이다: DONE_IMMEDIATE(바로 전달) · DONE_AFTER_HELP(도움 뒤 전달) · NEXT_TIME(다음 기회로) · ON_HOLD(보류) · NOT_DONE(미실시 · {사유}).
- 미실시 사유는 시간 종료 · 상황 준비 실패 · 활동 끝 · 반응 없음이다. 문구는 임시 문구로 금지 표현 검사 대상에 넣는다.
- U5b가 채울 goals.status(DONE · SKIPPED · NOT_REACHED) · notPerformedReason → 미실시 사유 사상표는 Functional Design에서 초안을 정하고 U5b 담당과 맞출 안건으로 넘긴다(Q8 A).
- 진행 중 판정값 · 라벨 · 근거 · 확률 · 점수는 어떤 응답에도 내보내지 않는다(FR8.3).
- 완료 기준: 재판정 값 조합마다 칩 · chipText가 표대로 나온다. 색만으로 구분하지 않는다(화면).

**FR4.3 제공한 도움** — Must · 출처 AC5.2.10, 계약 C5 help, 가정 G4
- 목표마다 `help{count, consent}`를 낸다. count = 도움 제안 기록 수, consent = 첫 제안의 응답(ACCEPTED · DECLINED · NO_RESPONSE), 제안이 없으면 NONE이다.
- 완료 기준: 27에 "도움 n회(동의함/거절함/답 없음)"이 대역 값대로 보인다.

### FR5 결과 문장과 결과 수준

**FR5.1 결과 문장** — Must · 출처 원 FR7.11, ai-io 6.3, 질문 Q5(A)
- 연습 목표마다 1~2문장, 자유대화는 활동 하나에 1~2문장을 만든다.
- 수치 · 원인 · 효과를 말하지 않는다. 숫자는 시각 · 장면 번호만 쓴다. 검사(스키마 → 인용 대조 → 금지 2단계)에 걸리면 템플릿 2종으로 바꾼다.
- 자유대화 결과 문장은 저장하고 `/result.sentences`(goalSelectionId 없음)로 내보내지만 27 자유대화 화면에는 그리지 않는다. 리포트 입력으로 쓴다. 화면 노출 여부는 팀장 확인 항목이다.
- 완료 기준: 가짜 공급자 출력이 금지 표현 · 인용 불일치를 포함하면 템플릿으로 바뀐다.

**FR5.2 결과 수준** — Must · 출처 원 FR7.11 표, ai-io 6.4, 가정 G2
- 서버가 FULL · PARTIAL · INSUFFICIENT를 계산한다. 유효 기록 = 자유대화는 내용 있는 발화(불확실 아님 + 어절 3개 이상) 3개, 연습은 첫 반응 장면(FIRST · NEW_SCENARIO에 utteranceSeq) 1개다.
- FULL은 정상 마무리일 때만이다: ENDED + 종료 확인 + CHILD_STOP · GUARDIAN_STOP · SILENCE(연습 마지막 목표 끝 = CHILD_STOP, U4b-c). 미확인 종료 · ABORTED · 재시작 FAILED는 FULL이 되지 않는다(Q1 A).
- INSUFFICIENT면 27에 9-32(자료 부족)를 보이고 후보를 만들지 않는다.
- 완료 기준: 결과 수준 표의 각 칸을 경계값(발화 2/3개, 어절 2/3개)으로 시험한다.

### FR6 목표 후보 생성

**FR6.1 후보 생성 · 등록** — Must · 출처 원 FR4.4 · 원 FR7.4, AC4.3.4, 계약 8절 CandidateRegistry, 질문 Q9(A) · Q10(A)
- 자유대화 · 연습 두 활동 모두에서 4요소를 갖춘 후보를 만든다. 결과 수준이 INSUFFICIENT면 만들지 않는다. 세션당 최대 3개다(가정).
- 근거는 실제 기록의 아이 회상(전사 발화 utteranceSeq ≥ 1, kind CHILD_RECALL)만 등록한다. 후보마다 근거가 1개 이상이어야 한다. 보호자 입력은 근거로 등록하지 않고 이유 문장 안에서 출처로만 언급한다.
- 의미 검토 = 근거 인용 대조 + 금지 표현 2단계 + 정의 ACTIVE 확인(서버 검사)이다. AI 의미 검토는 가정으로 미룬다.
- 결과 버전을 저장한 뒤 후보를 등록한다(가정 G8). 다시 시도로 같은 후보가 다시 나오면 U5a 중복 키로 합쳐지고, 새 근거만 더한다(가정 C21).
- 완료 기준: 근거 없는 후보 · 보호자 입력만 근거인 후보는 등록되지 않는다. INSUFFICIENT 세션에 후보 0건.

**FR6.2 근거 확인 어댑터** — Must · 출처 brief 「확장」, `contracts/ports.md` 4절, U5a-J15
- U5a `CandidateEvidencePort`의 실제 어댑터를 붙인다. 근거 utteranceSeq가 그 세션 활동 기록에 실제로 있는지 확인한다. 같은 타입 빈 교체(@ConditionalOnMissingBean)로 붙인다.
- 잠금을 쥔 채 외부 확인을 하지 않는다(U5a review-1 8).
- 완료 기준: 있는 발화 → 확인, 없는 발화 · 남의 세션 → 거절.

### FR7 생활 팁 매칭

**FR7.1 매칭** — Must · 출처 원 FR6.3, AC5.2.3, 질문 Q11(A), 가정 C14
- 승인된 Support Library에서 고른다. 매칭 태그는 behavior_key 하나로 해석한다: 세션 결과 태그 = 그 세션의 연습 목표와 후보의 behaviorKey 집합. 교집합이 1개 이상이면 매칭하고 매칭 로그를 남긴다. 적용 조건 태그 매칭은 이번 범위 밖이다.
- 팁은 조건 · 한계와 함께 낸다. 없으면 '추가 관찰 필요'(noTipReason)다. 미승인 항목 · 검색 점수는 내보내지 않는다.
- V5에서 support_item에 title 열을 더하고(더하기), 응답 tips에 선택 필드 `title`을 더한다(편차).
- 초기 팁은 정의마다 시드 몇 건을 둔다. D 담당 확인을 기다린다(Q7 A).
- 완료 기준: 매칭 · 비매칭 · 미승인 제외를 시험한다.

### FR8 결과 API (C5 결과 쪽)

**FR8.1 결과 조회** — Must · 출처 계약 3절 C5 `GET /api/sessions/{id}/result`
- 응답 모양은 계약 3절이다: jobStatus · resultLevel · sentences · practice{goals[scenes[sceneChip · chipText · notDoneReason], help]} · tips · noTipReason · candidates.
- candidates의 evidence · goalSelectionId는 U5a에 더한 읽기 메서드로 채운다(FR13.3).
- 완료 기준: 응답 스키마 시험 통과.

**FR8.2 정리 상태** — Must · 출처 계약 C5 `/result/status`, AC3.6.12, 가정 C19
- `{sessionStatus, confirmed, jobStatus}`를 낸다. 17의 정리 끝 = jobStatus DONE이다(AC3.6.12의 READY를 DONE으로 읽는다).

**FR8.3 노출 금지 필드** — Must · 출처 계약 2절, 원 A10, AC5.2.8, brief 「API」
- 진행 중 판정값 · 재판정 원값(value · timing · rationale) · 재판정 라벨 · 근거 문장 · 구조화 원본(contentJson) · 08 원문 · 상황 본문 · 판정 기준은 어떤 보호자 응답에도 넣지 않는다. 장면 결과는 칩 5종으로만 낸다.
- 응답 스키마(additionalProperties false) 시험으로 막는다.

**FR8.4 접근 제어** — Must · 출처 계약 2절 공통, 원 NFR9
- 남의 세션 · 리포트는 404, 계정 삭제 접수 중이면 403 `ACCOUNT_DELETION_IN_PROGRESS`, 오류 봉투는 공통 형식이다.

### FR9 리포트 생성

**FR9.1 미리보기** — Must · 출처 원 FR6.4, AC5.3.1 ~ 5.3.5, 계약 C5 `GET /api/reports/preview`, 가정 G9
- 기간(from · to, Asia/Seoul)의 활동 목록과 firstRecordDate를 낸다. 진행 중 · DECLINED 활동은 뺀다.
- 넣는 활동: ENDED · ENDED_UNCONFIRMED · ABORTED, 그리고 정리 대상인 재시작 FAILED(Q2 B). 정리 안 된 활동은 항목에서 자료 부족으로 다룬다.
- firstRecordDate = 미리보기 대상 활동의 첫날이다.

**FR9.2 생성 요청** — Must · 출처 원 FR6.4, AC5.3.6 ~ 5.3.13, 계약 C5 `POST /api/reports`, 가정 G11 · G15
- `{from, to, sessionIds(1개 이상), idempotencyKey}` → 202 `{reportId, GENERATING}`. 같은 키 = 첫 결과, 다른 본문 409.
- 남의 세션이 섞이면 요청 전체를 400 VALIDATION_FAILED로 거절한다.
- 28 「다시 시도」는 새 키로 새 리포트를 만들고 이전 것은 자동으로 버린다.
- REPORT 작업은 FR1 실행기로 돌린다.

**FR9.3 60초 마감** — Must · 출처 원 NFR8, AC5.3.11, 질문 Q3(A), 가정 C2
- `report.ready-target`(60초)은 서버 절대 마감이다. 접수부터 60초를 넘기면 남은 시도 없이 FAILED가 된다. 시도마다 AI 호출 시한은 남은 예산에서 나눈다.
- 앱은 60초가 지나면 실패 화면을 보인다. 서버 상태와 어긋나지 않는다.
- 완료 기준: 가짜 공급자 지연 주입으로 활동 20건 60초 안 READY, 마감 초과 → FAILED.

**FR9.4 10개 항목 조립** — Must · 출처 원 FR6.5, AC5.5.1 ~ 5.5.5, ADR-017, ai-io 6.5
- 1 · 3 · 4 · 7 · 8 · 9항목은 서버가 집계하고, 2 · 5 · 6 · 10항목은 AI 출력 + 인용 대조 + 금지 2단계를 거친다. 걸리면 템플릿(인용 날짜순 나열 / 없으면 "자료 부족")이다.
- 인용은 저장 전사와 글자 단위로 대조한다(앞뒤 공백만 무시). 맞지 않는 인용은 뺀다. 인용 부호 안의 전사는 금지 표현 검사에서 뺀다.
- 3 · 9항목은 칩만 쓴다. 9항목은 범위 안 같은 목표 2회 미만이면 자료 부족이고 향상을 계산하지 않는다. 5항목은 출처(보호자 입력/아이 회상)를 표시한다.
- 인용 출처: 전사 인용은 TRANSCRIPT, 보호자 입력은 quote 없이 출처 표시만 한다(가정 C7).
- citationCount = (세션, 발화 번호)로 중복을 뺀 전사 인용 수다(가정 G14).
- AI 입력은 서버가 항목별 인용 후보를 최대 N개로 줄인다. 활동 수 상한은 두지 않는다(가정 G10).
- 아이 이름 · 나이는 `ChildContext.get`으로 받는다. AI에는 별칭만 보낸다.

### FR10 리포트 확인 · 저장 · 버림 · PDF

**FR10.1 리포트 조회 · 상태** — Must · 출처 계약 C5 `GET /api/reports/{id}`
- status GENERATING · READY · FAILED · SAVED · DISCARDED. sections는 READY · SAVED에서만 낸다.

**FR10.2 저장 · 버림** — Must · 출처 원 FR6.5, 원 A4, AC5.4.1 ~ 5.4.8, 가정 G12 · G13
- 저장 순서: 앱이 PDF 받기에 성공한 뒤 `POST /saved`를 보낸다. 받기에 실패하면 9-30 토스트를 띄우고 30을 유지한다. saved는 멱등이다(첫 savedAt 유지).
- 「나중에」 · 저장 전 「←」 = `POST /discard`. 버리면 항목 · 활동 · 인용 행을 지우고 머리 행만 DISCARDED 묘비로 남긴다.
- 저장 안 된 READY · FAILED 리포트도 일정 시간 뒤 같은 방식으로 정리한다(새 시간 키).
- 저장된 리포트와 메타는 계정 삭제까지 보존한다(원 NFR12).

**FR10.3 PDF** — Must · 출처 원 FR6.5, AC5.5.4 · 5.5.6, ADR-017, J8, 질문 Q6(A)
- `GET /api/reports/{id}/pdf`(READY · SAVED만, 아니면 409 REPORT_NOT_READY) 때마다 저장된 항목으로 HTML 템플릿을 메모리 안에서 렌더링해 보낸다. 파일은 서버에 저장하지 않는다.
- 라이브러리는 OpenHTMLtoPDF(LGPL), 글꼴은 Noto Sans KR(OFL) 서브셋을 클래스패스 리소스로 넣는다. 정확한 버전 · 이미지 크기 증가 · 라이선스는 Functional Design에서 재어 적는다.
- 텍스트 PDF이고 30과 같은 10개 항목, 인용 전사 일치, 진행 중 판정값 없음이다. 머리 "무중 상담용 리포트" · 고지, 매 쪽 바닥글 "무중 상담용 리포트 · {아이} · 쪽 n/N · 이 리포트는 진단 결과가 아니에요."
- 파일명 `무중_상담용리포트_{아이}_mmdd-mmdd.pdf`(Content-Disposition `filename*` UTF-8), `Cache-Control: no-store`.
- 완료 기준: PDF 텍스트 추출이 30 항목과 같고, 한글이 깨지지 않으며, 금지 필드가 없다.

### FR11 화면 17 · 26 · 27

**FR11.1 17 정리 상태** — Must · 출처 원 FR3.4, AC3.6.2 ~ 3.6.4 · 3.6.8 · 3.6.12, mockups S-17, 가정 C17 · C18
- 정리 중 "기록을 정리하고 있어요" → 정리 끝 9-20(「오늘 기록 보기」 → 그 세션 27) / 정리 실패 9-21(「기록에서 다시 시도」 → 27).
- jobStatus NONE(DECLINED · 발화 0건)이면 「오늘 기록 보기」 없이 「홈으로」만 보인다.
- 17에는 결과 · 해석 · 판정 · 결과 문장을 보이지 않는다.
- 정리 중일 때만 2초(`parent.poll-interval`)로 `/result/status`를 폴링하고 끝나면 멈춘다.
- U1 J120(`frontend/tests/u1/parent/done-17.test.tsx:109-112`)과 U4a-J14(`frontend/tests/u4a/parent/screens-u4a.test.tsx:155`)의 감춤 단언 두 곳만 "정리 끝(DONE)에서만 보임"으로 바꾸고 설계에 까닭을 적는다.

**FR11.2 26 기록 달력** — Must · 출처 원 FR6.1, US5.1 AC5.1.1 ~ 5.1.10, mockups S-26, 가정 G16
- U4a `GET /api/records`를 쓴다. 달력 · 그날 목록, 줄 글 3종, 활동 없음, 9-23 · 9-24. DECLINED는 종료 사실 · 시각만 보이고 상세로 가지 않는다(B19 점 표시).
- 처음 들어올 때 선택 날 = 오늘. 27에서 돌아오면 보던 날을 유지한다. Asia/Seoul.
- 재시작 FAILED 정리 대상 활동도 목록에 보인다(FR15).

**FR11.3 27 기록 상세** — Must · 출처 원 FR6.2, US5.2 AC5.2.1 ~ 5.2.12, US4.3 AC4.3.1 ~ 4.3.5, mockups S-27 · §1 F3, 질문 Q4(B)
- 머리, 끝난 이유 칸(사유 · 종료 확인 여부와 시각 · 확보 범위 · 미확인 구간, 미확인이면 9-33), 연습 목표 묶음(칩 · 결과 문장 · 제공한 도움), SEG-TAB "대화 기록 · 생활 팁 n · 연습 목표 n".
- 대화 기록 탭은 U4a `GET /api/records/{id}`의 전사 · AI 말 · gaps를 쓴다. 기록 수신 공백을 침묵 · 거부로 쓰지 않는다.
- 08 배경은 27에 보이지 않는다(Q4 B). AC5.2.2의 "보호자 입력 배경 · 출처 구분" 표시는 이번 범위에서 빠지고 편차로 적는다.
- 생활 팁 탭: 제목 + 본문 + 조건 · 한계, 없으면 9-26 · 9-27.
- 목표 후보 칸: "근거 · {관찰 문장} (hh:mm)", 「담기」/「담김 ✓」(U5a `POST /api/goal-selections` · 취소), 후보 없음 9-28.
- 정리 중 · 정리 실패(「다시 시도」) · 자료 부족(9-32) 상태를 구분한다. 정리 중이면 2초로 폴링하고 끝나면 멈춘다. 기록이 아직 없으면(404) `/result/status`로 정리 중을 보인다(가정 G17).
- 「←」는 보던 날의 26으로 간다.

### FR12 화면 28 · 30 · R-PDF

**FR12.1 28 리포트 범위** — Must · 출처 원 FR6.4, US5.3, mockups S-28 · §1 F4, 가정 C23
- 빠른 선택 칩 3개: 최근 2주(오늘 포함 14일, 기본) · 이번 달(1일~오늘) · 전체 · 직접(기록 첫날~오늘 + 달력 직접 고르기). 미래 날짜는 고를 수 없다.
- 활동 체크, 0건이면 단추가 꺼지고 문구를 보인다. 만드는 중 "고른 기록으로 10개 항목을 만들고 있어요.", 취소 = 앱이 기다리기만 멈춤(B21), 실패 분기 · 다시 시도.
- 만드는 중일 때만 2초로 `/reports/{id}`를 폴링한다. 60초가 지나면 실패 화면이다.
- 「만들기」 재탭은 작업 1건이다(같은 idempotencyKey).

**FR12.2 30 확인 · 저장** — Must · 출처 원 FR6.5, US5.4 · US5.5, mockups S-30 · §1 F5
- 고지 + 10개 항목(표, 이름 · 순서 고정, 30 = PDF), 저장 확인 시트(인용 n개 등 값) → 「저장」 → 저장했어요 → 공유(Web Share, 미지원이면 숨김 B20) · 파일 열기(같은 PDF 새 탭) · 기록으로.
- 시트 「취소」 = 30 유지. 「나중에」 · 저장 전 「←」 = 버림.

**FR12.3 접근성** — Must · 출처 AC5.3.14, 화면 설계 공통
- 칩 · 상태를 색만으로 구분하지 않고, 상태 변화는 화면 낭독기에 알린다.

### FR13 U5a 확장 (J17, 더하기만)

**FR13.1 ScenarioLookup** — Must · 출처 계약 8절, brief 「확장」, 가정 C24
- U5a PracticeGoal 컴포넌트의 공개 서비스로 `ScenarioLookup.get{scenarioIds}`를 더한다. situation = scenario_text, partnerUtterance = trigger_script로 사상하고 partner_role_text는 넘기지 않는다.

**FR13.2 BehaviorDefinitionLookup** — Must · 출처 계약 8절, U5a report(requireVersion)
- PragmaticsKnowledge의 공개 서비스로 `BehaviorDefinitionLookup.get{behaviorKey, behaviorVersion}`을 더한다. 고정된 옛 버전(RETIRED 포함)도 `requireVersion`으로 읽는다.

**FR13.3 후보 읽기 메서드** — Must · 출처 질문 Q9(A), 가정 C9
- CandidateRegistry에 27용 읽기 메서드를 더한다(evidence · goalSelectionId 포함). 기존 `viewForSession`은 그대로 둔다.
- U6은 U5a 저장소를 다른 패키지에서 직접 읽지 않는다.
- 완료 기준(FR13 공통): U5a 시험의 단언 · 커버리지 하한은 그대로 통과한다.

### FR14 계정 삭제 참가자

**FR14.1 ORDER_RESULT = 70** — Must · 출처 brief 「데이터 · 삭제」, 계약 8절 DeletionParticipant, 원 NFR12, 가정 G7 · C22
- `DeletionParticipant`에 `ORDER_RESULT = 70`을 더한다. 활동 기록(90)보다 먼저 결과 작업 · 시도 · 결과 · 구조화 · 장면 판정 · 결과 상태 · 리포트 · 항목 · 활동 · 인용 · 메타를 지운다. PDF는 서버에 파일이 없으므로 파일 삭제 단계는 없다(FR10.3).
- 멱등이다. 쓰기 트랜잭션마다 보호자 상태를 확인해 삭제 중이면 작업을 멈추고, 늦게 끝난 작업이 남긴 행은 다시 지운다.
- 구조화 결과는 FK 없이 논리 참조로 둔다(U5a 후보 표의 structured_experience_id).
- 완료 기준: 계정 삭제 뒤 그 보호자의 U6 행 0건, 실행 중 작업과 겹쳐도 0건.

### FR15 재시작 FAILED 세션 노출 확장

**FR15.1 범위** — Must · 출처 질문 Q2(B), AC3.6.9 · AC3.6.12, U4b-R10
- 진행 중 서버 재시작으로 끝난 FAILED(failureCode SERVER_RESTART) 세션은 발화가 1개 이상 있으면 정리하고(FR2.2), 26 목록 · 27 상세 · 28 미리보기에 보인다. 끝난 이유 칸은 17과 같은 사유('기기 응답 없이 끝냈어요' 계열)를 쓴다.
- 이는 다음 정본을 바꾸는 편차다. `contracts/`를 먼저 고치고 `contracts/deviations.md`에 `(U6-x)`로 적는다.
  - `contracts/schemas/records-response.schema.json` finalStatus에 FAILED를 더한다(더하기).
  - U4a-J9(27의 FAILED 404)를 이 조건에서 풀고, AC5.1.8 · AC5.3.5의 "FAILED 제외"를 "정리 대상이 아닌 FAILED 제외"로 바꾼다.
- 바꾸는 U4a 시험 · 단언은 Functional Design에 목록과 까닭을 적는다.
- 완료 기준: 재시작 FAILED + 발화 있음 → 17 정리 끝 → 27 열림 · 26 표시 · 28 포함, 발화 없음 → NONE · 26 숨김.

### FR16 계약 · 문서 · 시험 정비

**FR16.1 계약 먼저** — Must · 출처 brief 「정해 둔 값」
- `contracts/parent-api.yaml`에 C5 9개 경로를 계약 3절대로 더한다. 결과 · 리포트 응답 JSON 스키마를 더한다.
- ai-io 6절에 구조화 · 후보 · 팁 매칭 절 초안을 더하고(Q7 A), 6.1의 `mujoong.ai.*`를 `mujoong.text-ai.*` 하위 키로 고친다(가정 C1).
- `contracts/timing.md`의 `result.*` · `report.*` 키를 코드 설정과 맞춘다(FR16.2).
- 편차는 `contracts/deviations.md`에 `(U6-a)`부터 적는다: 정리 대상 해석(Q1), 재시작 FAILED 노출(Q2), 08 배경 미표시(Q4), 재시도 3회 해석(C2), 키 이름(C1), 출처 값(C7), 폴링 해석(C18), 팁 title(C14) 등.

**FR16.2 시간 키 연결** — Must · 출처 `contracts/timing.md` 2절, codekb D6, 가정 C2 · G5 · G6 · G12
- `mujoong.timing.result.job-attempts: 3` · `result.job-retry-backoff: [5s, 15s]` · `report.ready-target: 60s`와, 정본에 값이 없던 새 키(시도 1회 시한, 누락 점검 주기, 저장 안 된 리포트 정리 시한, 결과 · 리포트 AI 호출 설정)를 timing.md · application.yml · CoreProperties 세 곳에 함께 더한다. ConfigKeyContractTest가 세 곳 일치를 강제한다.

**FR16.3 금지 표현 사전** — Must · 출처 ai-io 6.6, 가정 C13
- Core에 ai-io 4절 형식 JSON 사전(appliesTo RESULT_TEXT · PDF)을 새로 두고 결과 · 리포트 검사에 쓴다. 6.6 초안을 v1 후보로 쓴다. U5a txt 정책은 그대로 둔다.

**FR16.4 설계 · 보고 문서** — Must · 출처 brief 「정해 둔 값」 · 「AI-DLC로 진행하는 법」
- Functional Design 결과를 `docs/units/u6/design.md`에도 옮겨 둔다. 끝나면 `docs/units/u6/report.md`를 쓴다.
- `docs/units/README.md` 현황표의 U4b · U6 줄을 고친다.

## 비기능 요구사항

| ID | 요구사항 | 기준(통과/실패) | 출처 |
|---|---|---|---|
| NFR1 | **리포트 60초**: 10개 항목 리포트는 접수 60초 안에 READY가 된다 | 가짜 공급자 지연 주입, 활동 20건 시험에서 60초 안 READY. 마감 초과는 FAILED | 원 NFR8 · FR9.3 |
| NFR2 | **노출 금지**: 진행 중 판정 · 재판정 원값 · 근거 문장 · 구조화 원본 · 08 원문 · 상황 본문 · 판정 기준이 어떤 보호자 응답 · PDF에도 없다 | 응답 스키마(additionalProperties false) 시험과 PDF 텍스트 검사 통과 | 계약 2절 · 원 A10 · AC5.2.8 |
| NFR3 | **금지 표현**: 결과 문장 · 리포트 항목 · PDF 템플릿 · 27 고정 문구가 금지 표현 사전을 통과한다 | 사전 기반 자동 시험에서 0건(인용 부호 안 전사 제외) | 원 NFR1 · AC5.5.5 |
| NFR4 | **입력 최소화 · 개인정보**: AI에 아이 이름 · id · 원음을 보내지 않는다. 로그 · 오류 메시지에 전사 원문 · 08 원문을 넣지 않는다 | 가짜 공급자가 받은 요청 검사, 로그 검사에서 0건 | 원 NFR3 · 원 NFR9 · ai-io 6.1 |
| NFR5 | **접근 제어**: 남의 세션 · 리포트 404, 삭제 접수 중 403 | 경로별 시험 통과 | 계약 2절 · 원 NFR9 |
| NFR6 | **시간 값은 한 곳**: 모든 시간 값은 `contracts/timing.md`가 정본이고 설정으로 주입한다 | ConfigKeyContractTest · TimeLiteralScanTest 통과. 운영 소스의 시간 숫자 상수 0건 | 원 NFR6 · FR16.2 |
| NFR7 | **멱등 · 한 번**: 같은 세션의 결과는 동시에 하나만 만들어지고, 같은 키의 요청은 같은 결과를 돌려준다 | 동시 접수 · 연타 · 같은 idempotencyKey 시험에서 작업 1건 | 원 FR7.7 · AC5.2.7 · AC5.3.13 |
| NFR8 | **시험 커버리지**: `u6` 태그와 `backend/coverage-scope/u6.txt`(U1 형식), `frontend/coverage-scope/`의 U6 범위 파일을 만든다 | 범위 줄 커버리지 80% 이상, 모듈 전체 줄 커버리지 80% 이상 유지 | brief 「정해 둔 값」 · org Testing Posture |
| NFR9 | **회귀 없음**: U1 · U2 · U3 · U4a · U4b · U5a 시험이 모두 통과한다. FR11.1 · FR15.1이 바꾸는 단언 외에는 줄이거나 바꾸지 않는다 | 회귀 태그 실행과 CI 전체 check 통과 | brief 「건드리지 않는 것」 |
| NFR10 | **계약 적합성**: 응답 · 이벤트는 `contracts/` 스키마를 따른다 | 스키마 검증 시험 · ContractFilesTest 통과 | 계약 3절 · ADR-019 |
| NFR11 | **삭제 완결성**: 계정 삭제 뒤 그 보호자의 U6 행이 남지 않는다 | FR14.1 통합 시험에서 0건 | 원 FR1.6 · 원 NFR12 |
| NFR12 | **컨테이너 한도 안 PDF**: PDF 렌더링은 core-api 컨테이너 한도(메모리 1.6GB · /tmp 64MB · 읽기 전용 루트) 안에서 파일 쓰기 없이 동작한다 | 활동 20건 리포트 PDF 렌더링 시험에서 임시 파일 0개, 이미지 크기 증가를 설계에 기록 | Q6(A) · codekb D8 |

## 제약

- 마이그레이션: core 스키마에 `V5__u6_results_report.sql` 하나만 더한다(더하기만). realtime 스키마는 바꾸지 않는다. Flyway `outOfOrder`가 꺼져 있다. U7(core V6)은 U6 다음, U5b가 core를 바꾸면 V7 이후다.
- 기존 표에 제약을 더해야 하면 CI 마이그레이션 검사에 걸린다. 근거를 적어 `infra/migration-approvals.txt`에 한 줄을 더하고 리뷰를 받는다. support_item title 열 추가는 더하기(nullable)로 한다.
- 삭제 순서 `ORDER_RESULT = 70`(지금 값 90 · 100 · 150 · 200 · 250 · 300 · 900, U7 60).
- 공급자: 실제 텍스트 AI 공급자는 연결하지 않는다. TextAiProvider 기본은 UNAVAILABLE이고, 그러면 결과 작업은 FAILED로 끝나며 27은 「다시 시도」를 보인다. 구조화 · 재판정 · 결과 문장 · 리포트 항목은 시험 소스의 가짜 공급자로 검증한다.
- U5a 확장은 더하기만 한다(J17). U5a 시험 단언 · 커버리지 하한은 바꾸지 않는다.
- 건드리지 않는 것
  - U1 · U2 · U3 · U4a · U5a 시험의 단언과 커버리지 하한. 예외: FR11.1의 감춤 단언 두 곳, FR15.1의 FAILED 노출 관련 U4a 단언(설계에 목록과 까닭을 적음)
  - 다른 작업 기록(`261008-mujoong-service` · `261013-u4b-activity-realtime` · U5b 기록)
  - `README.md` · `contracts/README.md`
- 바꾼 계약은 `contracts/` 파일을 먼저 고치고 편차는 `contracts/deviations.md`에 `(U6-a)`부터 적는다.
- 브랜치는 `unit/u6-results-report`다. Code Generation 계획은 ResultPipeline → Report → 화면 순으로 나누고 단계마다 `feat(u6): …`로 커밋한다. main으로 PR을 열고 CI가 통과한 뒤 합친다. 이 작업 기록 폴더도 브랜치에 함께 커밋한다.

## 가정

| 번호 | 가정 | 근거 |
|---|---|---|
| A1 | 질문 파일 「Assumption Confirmation」의 27건(C1 · C2 · C7 · C11 · C13 · C17 ~ C19 · C21 ~ C24 · G2 · G4 ~ G17)은 추천안대로 유효하다. 이 문서의 FR에 반영했다 | 가정 확인 A |
| A2 | ai-io 6절에 없는 구조화 · 후보 생성 · 팁 매칭의 입력 · 지시 · 출력, 프롬프트 본문, 금지 사전 v1, 생활 팁 시드는 U6 초안이고 가정 `U6-J`다. D 담당 답이 오면 고친다 | Q7(A) · brief 「먼저 할 것」 2 |
| A3 | 연습 결과는 계약 6절 예시 값 대역으로 구현 · 시험한다. practice = 객체 또는 null을 기준으로 삼는다. U5b와 칩 사유 사상표 · null 규칙을 맞추면 설계에 반영한다 | Q8(A) · brief 맞물림 2 |
| A4 | 세션당 후보는 최대 3개다 | Q10(A) |
| A5 | 자유대화 결과 문장의 27 화면 노출은 하지 않는다. 팀장이 노출을 정하면 별도 작업으로 한다 | Q5(A) |
| A6 | OpenHTMLtoPDF와 Noto Sans KR 서브셋이 컨테이너 한도 안에서 60초 마감을 지킨다. 측정 결과가 다르면 Functional Design에서 다시 정한다 | Q6(A) · ADR-017 |
| A7 | U4b가 main에 들어왔으므로 자유대화 끝-끝(대화 → 17 정리 끝 → 27)은 이 기록의 Build and Test에서 실제 스냅숏으로 시험할 수 있다 | 기존 코드 파악(main 7a22b67) |

## 범위 밖

- `record.practice` 채우기 · 연습 Realtime → U5b. U5b가 들어온 뒤 연습 끝 → 27 칩 · 문장 끝-끝 재시험은 남은 일이다.
- 실제 텍스트 AI 공급자 어댑터와 실제 프롬프트 성능 측정
- AI 의미 검토 단계(후보), 생활 팁 적용 조건 태그 매칭
- 27의 08 배경 표시(Q4 B), 자유대화 결과 문장의 27 표시(Q5 A)
- 완료 푸시 · 구독 → U7. U6은 17의 정리 상태만 채운다.
- 배포 파이프라인 · 운영 배포. 이 작업 기록에서 뺐다.

## 열린 질문

- 칩 사유 사상표(goals.status · notPerformedReason → 미실시 사유)와 practice null 규칙: Functional Design에서 초안을 정하고 U5b 담당과 맞춘다(사람이 맞춤).
- AI 작업 프롬프트 · 금지 사전 · 생활 팁 내용: D 담당 확인을 기다린다.
- PDF 라이브러리 · 글꼴의 정확한 버전과 이미지 크기 증가: Functional Design에서 잰다.
- 새 시간 키(시도 시한 · 누락 점검 주기 · 리포트 정리 시한)의 값: Functional Design에서 정하고 timing.md에 적는다.
- 재시작 FAILED 노출(FR15)로 바뀌는 U4a 시험 · 스키마의 정확한 목록: Functional Design에서 적는다.
- 자유대화 결과 문장 27 노출: 팀장 확인.

## Sources

- 이 단계의 질문과 답: `requirements-analysis-questions.md` Q1 ~ Q11, 가정 확인 A. Accept assumptions, 요약 확인 Looks correct
- 초기 설명(project-description.json): "Unit6 개발 시작". 기준 문서는 사람이 고른 `docs/units/u6/brief.md`다(이 단계의 설정 질문).
- 지시: `docs/units/u6/brief.md`(범위 · 정본 · 정해 둔 값 · 맞물리는 곳 · 먼저 할 것)
- 원래 기록: `aidlc/spaces/default/intents/261008-mujoong-service/inception/`
  - `requirements-analysis/requirements.md`: FR3.4 · FR4.3 · FR4.4 · FR6.1 ~ FR6.5 · FR7.4 · FR7.6 · FR7.7 · FR7.10 · FR7.11, NFR1 · NFR3 · NFR8 · NFR9 · NFR12, A2 · A4 · A10
  - `user-stories/stories.md`: US3.6 · US4.3 · US5.1 ~ US5.5와 가정 B19 · B20 · B21 · B24 · OQ16
  - `units-generation/unit-of-work.md` U6, `delivery-planning/bolt-plan.md` B7
  - `contract-design/contract-summary.md` 2절 · 3절 C5 · 6절 · 7절 · 8절 · 9절 · 11절
  - `domain-design/components.md` ResultPipeline · Report · J3 · J6 · J8, `decisions.md` ADR-017
  - `refined-mockups/mockups.md` §1 F3 ~ F6, S-17 · S-26 · S-27 · S-28 · S-30 · R-PDF, §9
- 계약 파일: `contracts/ai-io.md` 6절 · 7절, `contracts/ports.md` 4절, `contracts/timing.md`, `contracts/schemas/records-response.schema.json` · `record-detail-response.schema.json`, `contracts/parent-api.yaml`, `contracts/deviations.md`
- 앞 유닛: `docs/units/u4a/design.md` · `report.md`(J9 · J11 · J14 · J16 · J18), `docs/units/u5a/design.md` · `report.md` · `review-1.md`(J1 · J7 · J9 · J14 · J15), `docs/units/u4b/design.md`(R10 · R11 · R17 · J28), `docs/units/u5b/brief.md`(맞물리는 곳)
- 기존 코드 파악: `aidlc/spaces/default/codekb/mujungi/business-overview.md` · `architecture.md`(U6 접점 요약) · `code-structure.md` · `code-quality-assessment.md`(D1 ~ D12)
