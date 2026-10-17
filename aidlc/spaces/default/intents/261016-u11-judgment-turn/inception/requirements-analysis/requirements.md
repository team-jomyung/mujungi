# U11 판정 턴 기반 — 요구사항 명세

> 작업 기록 `261016-u11-judgment-turn` · Requirements Analysis(2.3) · 2026-10-16 첫 판, 2026-10-17 범위 확장 개정
>
> 이 문서는 원래 기록 `261015-doc-sync-code-update`의 요구사항 · 스토리 · 계약 중 U11 몫을 구현 단위로 다시 묶은 것이다. 새 업무 요구사항은 만들지 않았다. 2026-10-17 개정은 GPT-Live 실호출(Code Generation Step 3-1 ~ 3-3) 뒤 사람이 고른 범위 확장(Step 3-3 답 B)에 따라 원 FR12.2(선 형식 정비 · 키 이름 통일)를 이 기록으로 끌어왔다(FR12 · FR13).
> 출처가 서로 다르면 다음 순서로 따른다.
> 1. 화면 설계 v1.5j
> 2. 원래 기록의 승인 산출물: requirements · stories · contract-summary · components · decisions · unit-of-work · bolt-plan
> 3. 지시 `docs/units/u11/brief.md`, 계약 파일 `contracts/`, U8 · U9 설계와 편차(`contracts/deviations.md` (U8-a) ~ (U8-e) · (U9-a) ~ (U9-c))
> 4. 이 단계의 답(`requirements-analysis-questions.md` Q1 ~ Q12, 가정 확인, 요약 확인)
>
> 다만 GPT-Live 선 형식만은 Q6 답 A에 따라 **실호출 관측 → ADR(`protocol.md` · `ADR-004`) → 기술명세 v0.3(실호출로 확인된 필드만)** 순서로 따른다.
>
> 원래 기록의 요구사항 ID는 `원 FR3.1`처럼 "원"을 붙여 구분한다. 스토리 · 수용 기준 ID(US1.7 · AC1.8.2)는 원래 기록의 것을 그대로 쓴다. 이 문서의 `FR{n}` · `NFR{n}`은 이 작업 기록 안의 추적 키다. `U11-Rn`은 코드 지식 베이스 `code-quality-assessment.md` 6절의 위험 번호다.

## 의도 분석

- **목표:** 아이 말에 대한 진행 중 판정(J1 ~ J6)을 Luna로 하고, 판정하는 동안에는 GPT-Live의 확인되지 않은 자동 응답이 아이에게 들리지 않게 하며, 판정 뒤에는 서버가 정한 다음 행동 지시로만 대화를 잇게 한다. 판정이 불확실하거나 늦으면 아이를 교정하지 않고 무난하게 이어 간다.
- **범위 확장 목표:** 위 목표가 실제 GPT-Live에서 성립하도록, 코드의 GPT-Live 선 형식(J110 가정)을 실제 형식으로 바로잡고 공급자 키 이름을 하나로 통일한다. 실호출에서 지금 형식으로는 세션 시작이 거절되고(`invalid_json`), 지시 주입 · 응답 생성 · 응답 취소 메시지가 없으며, 출력 음성이 0바이트로 해석됐다(실호출 보고서).
- **사용자 가치:** 아이는 엉뚱한 말을 듣지 않고, 쉬거나 그만하고 싶을 때 바로 그렇게 되며, 무중이가 못 알아들어도 탓하는 말을 듣지 않는다(US1.7 · US1.8 · US1.10 · US2.5). 선 형식 정비가 없으면 실제 공급자를 붙인 순간 기기가 무음이 된다.
- **종류 · 범위:** 기존 realtime-activity 기능 확장과 공급자 연동 정비(브라운필드). realtime 앱, 가짜 공급자 2곳(`tools/fake-provider` · `test-support`), 기존 시험 단언, 끝-끝 C단계, `contracts/`, 비밀 이름 검사 목록(R-M9)을 고친다. 연습 상태 기계(U12) · 제브 게이트(U13)가 이 위에 얹힌다.
- **복잡도:** 높음. `SessionExecutor`(3,388줄)의 공급자 사건 · 출력 보류 · 송신 경로를 고치고, 다른 두 단위가 같은 파일을 이어서 고친다(위험 R4 · U11-R10). 선 형식 정비는 U1 ~ U5b 시험 약 20개 파일의 단언을 바꾼다.
- **두 묶음:** Q12 답 A에 따라 한 작업 기록 안에서 ① 선 형식 정비 · 키 이름 통일(FR12 · FR13)을 먼저 `main`에 합치고, ② 그 위에서 판정 턴(FR1 ~ FR11)을 합친다.

## 기능 요구사항

### FR1 진행 중 판정 호출 (`JudgeClient`)

**FR1.1 판정 포트** — Must · 출처 원 FR3.1, C-DS4, AC1.8.6
- realtime-activity에 새 포트 `JudgeClient`를 둔다. U9 `RealtimeLunaClient`(목적 `JUDGMENT_LIVE`)를 부른다. 기존 `EvaluationProvider`와는 별개다.
- 요청은 `contracts/schemas/ai-judgment-request.schema.json`, 결과는 `ai-judgment-output.schema.json`(1 · 2순위 선택지와 확률)을 따른다.
- 완료 기준: 가짜 판정기로 1 · 2순위 결과가 `JudgeResult`로 돌아오고, 요청에 아동 · 보호자 · 세션 식별자와 키 · 원음이 없는 것을 시험한다.

**FR1.2 판정 지점 · 선택지 판** — Must · 출처 원 FR3.2
- 판정 지점 J1 ~ J6과 선택지는 U8 판 리소스 `judgment/option-sets-v1.json`을 쓰고, 세션 시작 때 고정된 판(`LiveContentRegistry`의 `judgmentSetVersion`)을 세션 동안 바꾸지 않는다.
- 완료 기준: 세션 중 판 리소스가 바뀌어도 같은 세션의 판정 요청은 고정된 판의 선택지를 쓴다.

**FR1.3 판정 보류 규칙** — Must · 출처 원 FR3.3, AC1.8.1, 가정 2
- 1순위 확률이 하한보다 낮거나 1 · 2순위 차이가 최소 차이보다 작으면 판정 보류다.
- 기본값은 하한 0.6, 최소 차이 0.15다. 설정 키 `mujoong.judgment.min-confidence` · `mujoong.judgment.min-margin`은 `RealtimeProperties` 루트 레코드에 묶는다(시간 값이 아니다).
- 완료 기준: (0.59, 0.10) · (0.70, 0.60) · (0.70, 0.40)에서 각각 보류 · 보류 · 확정이 나온다. 경계값 0.6 · 0.15는 확정 쪽이다.

**FR1.4 시한 · 형식 오류** — Must · 출처 원 FR3.4, AC1.8.2, Q10 답 A(편차)
- 판정 한 번의 제한 시간은 `mujoong.timing.judgment.live-budget` **4s**다. 원 FR3.4 · AC1.8.2 · 원 NFR1의 "2초"와 다른 사람 결정이다(2026-10-16, Luna 실호출 지연 2.6 · 2.9초, 2초 안 0/2). 편차로 적고 시간 값 세 곳(`contracts/timing.md` 13절 · realtime `application.yml` · 설정 레코드, R-M5 · 편차 U8-c)을 같은 커밋에서 고친다.
- 시간 초과 · 형식 오류 · 공급자 오류는 재시도 없이 판정 보류다. 판정 보류는 교정이 따르는 행동('일부 전달' · '여전히 부족')으로 바꾸지 않는다.
- 완료 기준: 가짜 시계로 4초를 넘기면 재시도 없이 보류가 되고, 공급자 호출이 1회만 일어난다. 3.999초 도착은 확정으로 쓴다.

**FR1.5 출력 검증** — Must · 출처 ai-io.md 10.3, U11-R5, 가정 4
- 요청 선택지 밖 코드, 1 · 2순위가 같은 코드, 확률이 0 ~ 1 밖인 결과는 `JudgeClient`가 판정 보류로 바꾼다. 판정 출력 스키마 파일은 바꾸지 않는다.
- 완료 기준: 세 경우 모두 보류 사유가 따로 기록된다.

**FR1.6 키 없을 때** — Must · 출처 원 FR1.3(U9), U11-R6, 가정 3, R-F1
- 키(`OPENAI_API_KEY`, FR13 뒤 GPT-Live와 같은 키)가 없어 호출부 빈이 없으면 `JudgeClient`는 공급자를 부르지 않고 언제나 판정 보류(사유: 공급자 없음)를 돌려준다. 앱은 정상 기동한다.
- 시험 · CI · `verify.sh`는 가짜 판정기만 쓴다.
- 완료 기준: 키 없는 프로필에서 앱이 뜨고, 판정 요청마다 보류가 기록된다.

### FR2 진행용 판정 기록 (`judgment_live`)

**FR2.1 판정마다 기록 1건** — Must · 출처 원 FR3.6, AC1.8.6
- 판정이 끝나거나 보류 · 장애 취소될 때마다 `judgment_live` 행을 1건 남긴다.
- 담는 것: 판정 지점, 세션 · (있으면) 목표 · 장면, 입력 전사 참조, 선택지 판, 1 · 2순위와 확률, 보류 여부 · 사유, 걸린 시간, 다음 행동 지시 종류, 판정 턴 동안 버린 출력 전사.
- 이 기록은 보호자 화면 · 리포트 · 기록 스냅숏에 쓰지 않는다.
- 완료 기준: 확정 · 보류 · 장애 취소 각각 1건씩 남고, 보호자 API 응답과 `SessionRecordCaptured`에 이 기록 값이 없다.

**FR2.2 realtime V5와 기존 단언** — Must · 출처 bolt-plan B3, Q2 답 A, U11-R4
- 표는 realtime 마이그레이션 `V5__u11_judgment_turn.sql`로 더한다(더하기만).
- 이 때문에 깨지는 `RealtimeSchemaIT`(표 목록 · 최신 판)와 `PracticeMigrationRegistrationTest`(최신 판)의 단언은 새 사실("5", 표 추가)로 갱신한다. "4 이상"처럼 느슨하게 바꾸지 않고, 커밋 본문에 까닭을 적는다.
- 완료 기준: 두 시험이 새 단언으로 통과하고, FR12.9가 정한 선 형식 단언 외의 기존 단언은 바뀌지 않는다.

**FR2.3 삭제** — Must · 출처 원 NFR6, U11-R13, 가정 9
- 계정 삭제 때 `judgment_live`는 세션 행보다 먼저 지운다(삭제 참여자 순서가 세션 200 앞). `DeletionCompleted.counts`에는 항목을 더하지 않는다.
- 완료 기준: 계정 삭제 통합 시험 뒤 그 보호자의 `judgment_live` 행이 0건이다.

### FR3 판정 턴 출력 보류

**FR3.1 판정 턴 동안 출력 버리기** — Must · 출처 원 FR5.1, AC1.10.1, U11-R3, 가정 6, Q8 답 A
- 판정 턴(J1 ~ J6) 동안 GPT-Live 출력(지시 전 자동 응답 포함)은 기기로 보내지 않는다. 출력은 응답 id가 아니라 서버가 붙인 출력 구간 세대(FR12.5)로 가른다.
- 이 출력은 문장 버퍼 · 대화 기록기 **앞**에서 갈라, 음성은 버리고 전사(오면, FR12.7)는 `judgment_live`에만 남긴다. `realtime_transcript` · 보호자 중계에는 남기지 않는다.
- 완료 기준: 판정 턴 중 공급자 출력 음성 프레임이 기기로 0건 가고, 그 전사가 `realtime_transcript`에 0건, `judgment_live`에 남는다.

**FR3.2 지시 뒤 응답만 통과** — Must · 출처 원 FR5.2, AC1.10.2, C-DS15, 가정 5(Q6 · Q8로 개정)
- 판정 뒤 다음 행동 지시는 실제 메시지 `session.instructions.append` 다음 `response.create`로 넣고(FR12.3), 그 지시 뒤 새로 연 출력 세대의 응답만 게이트 검사로 넘긴다.
- 두 메시지는 한 송신 작업 안에서 이 순서로 보낸다(지금 `toProvider`의 순서 없는 송신을 쓰지 않는다, U11-R2).
- 완료 기준: 순서를 단언하는 대역에서 append → create 순서가 지켜지고, 지시 전 세대의 응답은 통과하지 않는다. 실제 GPT-Live에서의 성립은 FR9에서 `[실호출-합성]`으로 따로 보고한다.

**FR3.3 지시문 금지 내용** — Must · 출처 원 FR3.7
- 다음 행동 지시에는 판정 라벨 · 확률 · 판정 기준을 넣지 않는다.
- 완료 기준: 지시문 생성 시험에서 선택지 코드 · 확률 · 기준 문자열이 0건이다.

**FR3.4 '생각 중' 유지** — Must · 출처 AC1.10.4, U11-R11
- 판정 · 다음 행동 지시를 기다리는 동안 보낼 응답이 없으면 기기는 '생각 중'(T19, `SESSION_STATE{view: THINKING}`)을 유지한다. 버린 출력 때문에 '듣는 중'으로 되돌리지 않는다.
- 완료 기준: 판정 턴 중 버린 출력이 있어도 기기에 LISTENING이 가지 않는다.

### FR4 응답 준비 · 판정 보류 때의 진행

**FR4.1 10초 응답 준비** — Must · 출처 원 FR5.3, AC1.8.4
- 아이 말이 끝난 뒤 `mujoong.timing.judgment.response-ready`(10s) 안에 응답이 준비되지 않으면 판정 보류로 보고 무난한 되묻기 · 맞장구를 지시한다. '아이 말 끝'은 FR12.7이 확정한 신호(공급자 사건 또는 서버 확정)다.
- 완료 기준: 가짜 시계로 10초를 넘기면 보류 기록과 되묻기 지시가 각각 1건이다.

**FR4.2 보류 때 되묻기 · 맞장구 지시** — Must · 출처 원 FR3.4, AC1.8.3, Q4 답 A
- 판정 보류 · 10초 초과 때는 판정 라벨 · 확률 · 기준 없이 "짧게 다시 말해 달라고 하거나 맞장구하라"는 서버 템플릿 지시를 넣고, 문장은 GPT-Live가 만든다. 이 문장은 기존 출력 게이트(U13 뒤에는 제브)를 거친다. 새 승인 문구는 만들지 않는다.
- 완료 기준: 보류 사유마다 같은 템플릿 지시가 나가고, 교정 행동 지시는 0건이다.

**FR4.3 판독 불가 입력** — Must · 출처 AC1.8.5
- 입력 전사가 비었거나, 전사 제한 시간을 넘겼거나, 전사 신뢰도가 낮다고 표시됐거나, 음성 수신이 끊긴 경우에는 기능을 충족하지 못했다고 단정하지 않고 판독 불가 · 보류로 처리한다.
- 완료 기준: 네 경우 모두 판정 결과가 보류 · 판독 불가로 기록된다.

### FR5 장애 취소 · 늦은 결과

**FR5.1 장애 취소** — Must · 출처 원 FR5.4, AC1.10.3, 가정 7
- 판정 턴 중 음성 대화 장애(공급자 끊김 · 기술적 일시 정지)가 나면 진행 중 판정을 버리고 '장애 취소'로 기록한다.
- 완료 기준: 판정 중 공급자 끊김을 주입하면 장애 취소 기록 1건이 남고 그 판정 결과는 쓰이지 않는다.

**FR5.2 늦은 결과 버리기** — Must · 출처 원 FR5.4, U11-R12, 가정 7
- 판정마다 차례 id를 두고, 제한 시간 · 장애 취소 · 다음 차례 뒤 도착한 전사 · 판정은 진행에 쓰지 않는다. 판정 결과는 다른 스레드에서 실행기 편지함으로만 넘긴다.
- 완료 기준: 시한 뒤 도착한 판정 결과가 출력 · 상태를 바꾸지 않는다.

### FR6 종료 · 휴식

**FR6.1 등록 종료 표현은 판정 없이** — Must · 출처 원 FR3.5, AC1.7.1, Q3 답 A
- 기본 · 등록 종료 표현과 일치하면(`StopIntentJudge` STOP) 판정 턴을 열지 않고 바로 종료 처리한다.
- 완료 기준: 등록 표현 발화에서 Luna 호출 0건, 종료 처리 1건.

**FR6.2 판정 턴 중 종료 표현** — Must · 출처 원 FR3.5, AC1.7.2
- 판정 턴 중 등록 종료 표현이 나오면 진행 중인 판정을 멈추고 종료 처리한다. 판정 결과를 기다리지 않는다.
- 완료 기준: 판정 대기 중 종료 표현을 주입하면 판정 결과를 기다리지 않고 종료가 시작된다.

**FR6.3 종료 뜻 후보는 J6** — Must · 출처 AC1.7.3, Q3 답 A
- 규칙 판정기가 확정하지 못한 종료 뜻 후보(`StopIntentJudge` CONFIRM)일 때만 Luna J6 판정 턴을 연다.
  - '종료 명확'이면 종료한다.
  - '휴식 요청'이면 휴식으로 처리한다(FR6.4).
  - '불명확'이면 종료 의도를 한 번 확인한다(지금 종료 확인 흐름).
  - '종료 아님' · 보류면 대화를 잇는다(FR4.2).
- 키가 없으면(FR1.6) 지금 종료 확인 흐름 그대로다.
- 완료 기준: 네 결과 각각의 처리와 키 없을 때의 기존 흐름이 시험으로 확인된다.

**FR6.4 휴식 요청** — Must · 출처 AC1.7.6, Q5 답 B, Q5-1 답 A
- 휴식 요청이면 자유대화 · 연습 모두 U11이 승인 문구 `D_REST_ACK`를 재생한다.
- 다시 묻기는 "하던 이야기로 돌아가 마지막 질문을 짧게 다시 물어보라"는 일반 지시로 한다.
- 출력권을 가진 연습 확장(`controlsOutput()=true`)이 있으면 실행부는 다시 묻기 지시 대신 '휴식 요청' 신호를 확장점에 넘기고, 확장이 다시 묻는다. 확장이 처리하지 않으면(지금 U5b) `D_REST_ACK`만 나간다.
- 화면 설계에 없는 '쉬는 중' 기기 상태는 만들지 않고, 기존 공백 타이머는 그대로 둔다.
- 완료 기준: 자유대화 휴식에서 `D_REST_ACK` 1회 + 다시 묻기 지시 1회, 출력권 확장이 있는 세션에서 `D_REST_ACK` 1회 + 확장점 신호 1회 · 실행부 지시 0회.

**FR6.5 종료 근거 구분** — Should · 출처 AC1.7.5
- 종료가 등록 표현 일치로 났는지, Luna J6 판정으로 났는지를 realtime 쪽 기록에 구분해 남겨, 보호자 화면 17이 "따옴표 문구"와 "사유 줄"을 나눌 수 있게 한다. 화면 반영은 이 단위 밖이다.
- 완료 기준: 두 경로의 종료 기록이 서로 다른 근거 값을 갖는다. 계약 · 화면 연결 방법은 Functional Design에서 정한다(열린 질문 2).

### FR7 자유대화 판정

**FR7.1 J1 · J6만** — Must · 출처 원 FR4.8, AC2.5.1, Q3 답 A
- 자유대화는 상태 기계 없이 GPT-Live 자동 응답으로 진행하고, 판정 턴은 J1(시작 응답) · J6(종료 의도)뿐이다.
- J1은 참여 확인 규칙 판정기(`JoinTracker`)가 확정하지 못할 때(UNCLEAR)만 Luna 판정 턴을 연다. 명확한 ACCEPT · REFUSE는 판정 없이 지금처럼 처리한다.
- 완료 기준: 자유대화 한 판에서 판정 턴은 J1 · J6 외에 0건이고, 규칙이 확정한 발화에는 Luna 호출이 0건이다.

### FR8 연습 확장점 넓히기 (C-DS3)

**FR8.1 출력 능력 추가** — Must · 출처 C-DS3, bolt-plan B3, 가정 10
- `PracticeOutput`에 `requestJudgment(judgmentPoint, transcriptRef, criteriaRef)` · `announceRoleSwitch(toRole)` · `setThinking(on)`을 더한다. 기존 구현이 깨지지 않게 기본 구현(`false` 반환)으로 둔다.
- J2 ~ J5를 실제로 부르는 일은 U12 몫이다. U11 시험은 시험용 확장으로 이 능력을 확인한다.
- 완료 기준: 기존 U5b 확장 · `NoOpPracticeSessionExtension` · 시험 대역을 고치지 않고 컴파일 · 시험이 통과한다.

**FR8.2 확장 사건 추가** — Must · 출처 C-DS3
- `ExtensionEvent`에 `JUDGMENT_RESULT` · `JUDGMENT_ABSTAINED` · `HELD_OUTPUT_TRANSCRIPT` · `GUARDRAIL_BLOCKED`와 휴식 요청 신호(FR6.4)를 더한다. `GUARDRAIL_BLOCKED`는 자리만 두고 내보내는 일은 U13이다.
- 완료 기준: 판정 결과 · 보류 · 버린 출력 전사가 시험용 확장에 사건으로 도착한다.

**FR8.3 한 방향 · 금지 능력** — Must · 출처 ADR-005, project.md 교정
- 확장점은 실행부를 부르지 않는다. 실행부 → 확장점 한 방향만 둔다. 상태 전이 직접 호출 · DB 쓰기 · 기기 원시 명령 · 제브 우회 송신 · 판정 라벨 · 확률을 지시문에 넣기는 능력으로 주지 않는다.
- CLOSING · 정산 창에서는 능력 호출을 거절하고 `PRACTICE_EXTENSION_FAILED{reason: NOT_ACTIVE}`로 남긴다(지금 동작 유지).
- 완료 기준: ArchUnit 시험이 확장점 → 실행부 의존을 막고, 정산 창의 능력 호출이 거절된다.

### FR9 GPT-Live 실호출 확인 (원 FR12.2 일부)

**FR9.1 격리 실호출 작업** — Must · 출처 원 FR12.2, 위험 R1, Q1 답 B, 지시서 「첫 작업」 · **완료(Part A)**
- GPT-Live 격리 실호출 작업 `gptLiveTest`와 Luna 응답 확인 `lunaReachTest`를 만들었다(커밋 62304b6 · d8cf862 · 1cdae3c · 06d9c65 · deebd36). 둘 다 `lunaLiveTest`처럼 기본 `test` · `check` · CI · `verify.sh`에서 빠지고, 사람이 키를 넣고 직접 돌린다.
- 합성 자료만 쓰고, 키를 넣기 전에 `infra/RUNBOOK.md` 단계 4의 R-F15 확인 기록을 남긴다. 결과는 `[실호출-합성]`으로 따로 보고한다.
- 완료 기준: 작업이 `check`에 들어가지 않음을 빌드 규칙으로 막고, 키가 없으면 '건너뜀'(통과 아님)으로 보고한다. 선 형식 정비 뒤에는 이 작업이 운영 형식(FR12) 그대로 ① 서버 쪽 음성 전송 ② `instructions.append` + `response.create` 뒤 응답 ③ 말 끝 ④ 종료 형식을 확인한다.

**FR9.2 실호출 결과와 범위 결정** — Must · 출처 risk-and-sequencing-rationale 중단 조건, Code Generation Step 3-3 답 B
- 2026-10-16 세 번의 실호출 결과: 서버 쪽 음성 중계(ADR-007)는 성립한다(키트 형식에서 입력 275/275 송신, 출력 음성 469조각 수신). 지금 선 형식(J110 가정)은 실제와 다르다. 사람은 중단 대신 범위 확장(선 형식 정비를 이 기록에 넣음)을 골랐다.
- 따라서 원래 중단 조건의 "선 형식 변경 · 가짜 공급자 · u1 ~ u4b 시험 일괄 수정 · 키 이름 통일은 이 단위에서 하지 않는다"는 이 기록에서 더는 쓰지 않는다(FR12 · FR13).
- 남은 중단 조건: FR12.7의 합성 말소리 실호출에서 출력 전사 사건이 오지 않으면 구현을 멈추고 사람과 다시 정한다(출력 게이트 전제가 무너짐).
- 완료 기준: 실호출 결과표(관측 · 보고서 경로)와 계속 · 중단 결정이 단위 보고서에 남는다.

### FR10 기록 이벤트 (C-DS2)

**FR10.1 발행 경로 유지** — Must · 출처 C-DS2, 편차 (U8-d), 가정 11
- `SessionRecordCaptured`는 지금처럼 실행부가 발행한다. 새 연습 형식 구분은 `record.practice.flowVersion`이고, 값을 채우는 일은 U12 몫이다. U11은 판정 · 보류 출력 기록을 이 이벤트에 싣지 않는다.
- 완료 기준: 기존 U5b 형식 기록 이벤트 시험이 그대로 통과한다.

### FR11 시험 · 문서 정비

**FR11.1 시험 · 범위 파일** — Must · 출처 원 NFR5, 지시서 「정해 둔 값」, 가정 12
- 시험 태그 `u11`, 범위 파일 `backend/coverage-scope/u11.txt`(빈 목록 · 없는 파일이면 측정 실패, J137)를 만든다. 선 형식 정비로 고친 운영 파일도 범위 파일에 넣는다.
- 완료 기준: 범위 줄 커버리지 80% 이상.

**FR11.2 문서** — Must · 출처 지시서 「AI-DLC로 진행하는 법」 · 「끝나면」
- Functional Design 결과를 `docs/units/u11/design.md`에 옮기고, 끝나면 `docs/units/u11/report.md`를 쓴다. 계약을 바꾸면 `contracts/`를 같은 작업에서 고치고 편차는 `(U11-a)`부터 적는다(R-M10).
- 완료 기준: 두 문서가 브랜치에 있고, 계약 대조 시험이 통과한다.

### FR12 GPT-Live 선 형식 정비 (원 FR12.2, 범위 확장 · 첫 묶음)

**FR12.1 형식 기준** — Must · 출처 Q6 답 A, 실호출 보고서, ADR-004
- 선 형식은 실호출 관측을 1순위, ADR(`50_ADR_2S/architecture/protocol.md` · `ADR-004`)을 2순위로 정한다. 기술명세 v0.3의 단정 필드(`rate 24000` · `start_ms` · `session.instructions.appended` · `delegation_id` 등)는 실호출로 확인된 것만 쓴다.
- 확인되지 않은 필드는 코드에서 기대하지 않고, 이 문서 열린 질문과 `contracts/ai-io.md`에 '미확인'으로 적는다.
- 완료 기준: `contracts/ai-io.md` 선 형식 표의 모든 메시지 · 필드에 출처(실호출 · ADR · 기술명세)가 붙어 있다.

**FR12.2 세션 시작 · 종료 형식** — Must · 출처 실호출 Step 3-2 · 3-3
- 세션 시작은 `session.start`에 중첩 모양 `session{model, instructions, audio{format{type: "audio/pcm", rate}}, …}`으로 보내고, `session.started`를 받아야 연결 성공으로 본다. 종료는 `session.close` → `session.closed`다.
- 완료 기준: 요청 팩토리 시험이 중첩 모양을 단언하고, `gptLiveTest` 운영 형식 ①이 `session.started`를 받는다(`[실호출-합성]`).

**FR12.3 지시 주입 · 응답 생성** — Must · 출처 실호출 Step 3-3(지원 type 15종), ADR `protocol.md`
- 지시 주입은 `session.instructions.append`, 응답 생성은 `response.create`로 보낸다. `session.instructions.correct` · `session.response.create` · `session.response.cancel`은 보내지 않는다(실제로 없다).
- 기존 참여 확인 뒤 첫 응답(편차 U4b-a)과 게이트 교정 지시(편차 (aa))도 이 두 메시지로 옮긴다.
- 완료 기준: 공급자로 나가는 type이 지원 목록 15종 안에만 있음을 시험이 단언하고, `gptLiveTest` ②에서 `error` 사건 0건이다(`[실호출-합성]`).

**FR12.4 출력 음성 해석** — Must · 출처 실호출 Step 3-3(수신 사건 모양)
- 출력 음성은 `session.output_audio.delta {type, delta(base64)}`로 읽는다. 응답 id를 기대하지 않는다. `session.usage.updated` · `error` · `session.closed`도 해석해 알려진 사건으로 다룬다.
- 완료 기준: 실호출 원시 출력이 0바이트가 아닌 바이트로 해석되고(`gptLiveTest`의 앱 해석기 줄), 모르는 type 기록에서 `session.usage.updated`가 빠진다.

**FR12.5 출력 구간 세대 · 서버 쪽 취소** — Must · 출처 Q8 답 A, 기술명세 v0.3 §3.3
- 서버가 출력 구간마다 자체 id와 세대(epoch)를 붙이고, 문장 버퍼 · 출력 게이트 · 판정 턴 보류 · 끼어들기는 응답 id 대신 이 세대로 묶고 가른다.
- 취소는 공급자에 요청하지 않고 서버에서 그 세대의 이후 조각을 버리는 것으로 한다. `session.input_audio.mute`/`unmute`는 쓰지 않는다(아이가 끼어들 수 있게 둔다).
- 세대가 바뀌는 시점(지시 주입 · 응답 생성 송신, 아이 말 시작, 끼어들기)은 Functional Design에서 정한다.
- 완료 기준: 응답 id 없는 가짜 공급자 출력으로 자유대화 한 턴이 기기에 소리로 나가고(무음 아님), 끼어들기 · 판정 턴에서 버린 세대의 조각이 기기로 0건 간다.

**FR12.6 표본율** — Must · 출처 Q7 답 A, J131
- 다음 실호출에서 24kHz를 새 중첩 모양으로 먼저 확인한다. 받아들여지면 변환 없이 지금 기기 계약(PCM16 24kHz, 20ms = 960바이트)을 유지한다.
- 받아들여지지 않으면 realtime 서버에서 24k ↔ 16k를 변환한다. 기기 계약 · 녹음 형식은 바꾸지 않고, 공급자 입출력 SHA 일치 단언만 바뀐다. 이 경우 J131("전 구간 24kHz, 중간 변환 없음")과의 편차를 `(U11-x)`로 적는다.
- 완료 기준: 실호출 보고서에 24kHz 수락 여부가 남고, 그 결과에 맞는 경로(변환 없음 · 변환)가 시험으로 확인된다.

**FR12.7 전사 · 말 끝 사건 확인** — Must · 출처 Q9 답 A, ADR-004, 기술명세 §3.7
- 선 형식 정비의 첫 작업으로 합성 말소리(TTS, 키트 `cache/`의 Piper 음성 등) 입력 실호출을 한 번 더 돌려, 출력 전사 · 입력 전사 · 말 끝 사건의 유무와 이름 · 모양을 확인한다. 이 실호출에서 FR12.6의 24kHz 확인도 함께 한다.
- 입력 전사 완료 사건이 없으면 서버가 무음 시간과 전사 조각 정지로 아이 말 끝 · 전사 완료를 확정한다(시간 값은 `mujoong.timing.*`, R-M5).
- 출력 전사가 오지 않으면 구현을 멈추고 사람과 다시 정한다(FR9.2).
- 완료 기준: 실호출 보고서에 세 사건의 관측 결과가 남고, 코드가 관측된 이름 · 모양만 해석한다.

**FR12.8 가짜 공급자 두 곳** — Must · 출처 원 FR12.2, R-F1
- `tools/fake-provider`와 `test-support`의 가짜 공급자를 FR12.2 ~ FR12.4 · FR12.7에서 정한 실제 형식으로 맞춘다. 지원 목록 밖 type을 받으면 실제처럼 `error{code: invalid_value, param: type}`를 돌려준다.
- 완료 기준: 두 가짜 공급자가 같은 형식 시험 묶음을 통과하고, `verify.sh` C단계(기기 시뮬레이터 한 턴)가 통과한다.

**FR12.9 기존 시험 단언 갱신** — Must · 출처 원 FR12.2, R-F2, Q2 답 A와 같은 원칙
- 선 형식 때문에 깨지는 U1 ~ U5b 시험(약 20개 파일)의 메시지 이름 · 필드 · 응답 id 단언은 새 실제 형식으로 바꾼다. 단언을 느슨하게 하거나 지우거나 건너뛰게 하지 않고, 같은 행동(순서 · 횟수 · 버림)을 세대 기준으로 다시 단언한다. 커버리지 하한 · 계약 대조 시험 · ArchUnit 시험은 고치지 않는다.
- 완료 기준: 바뀐 단언 목록(파일 · 전 · 후)을 커밋 본문이나 단위 보고서에 남기고, `check` 전체가 통과한다.

**FR12.10 계약 · 편차** — Must · 출처 R-M10, 원 FR12.2
- `contracts/ai-io.md` 2 · 3 · 8절을 실제 형식으로 고치고, 이 형식에 기대던 편차 (z)(`session.output.done`) · (aa)(`session.response.cancel` · `session.instructions.correct`) · (U4b-a)(`session.response.create`)를 대체하는 새 편차를 `(U11-a)`부터 적는다. 기존 편차 줄은 지우지 않고 대체됐다고 표시한다.
- 완료 기준: 계약 대조 시험이 통과하고, 편차 표에서 대체 관계를 따라갈 수 있다.

### FR13 공급자 키 이름 통일 (원 FR12.2, 범위 확장 · 첫 묶음)

**FR13.1 `OPENAI_API_KEY` 하나로** — Must · 출처 원 FR12.2, Q11 답 B
- GPT-Live 키 `PROVIDER_API_KEY`를 없애고 Luna와 같은 `OPENAI_API_KEY`로 받는다. realtime 운영 코드 · 설정(`application.yml` · `application-demo.yml`) · 기동 점검 · 실호출 도구 · 시험을 함께 바꾼다.
- 결과로 키 하나가 있으면 GPT-Live와 Luna(realtime · core)가 함께 켜지고, 없으면 GPT-Live는 가짜 · Luna는 보류로 남는다. demo 프로필이 GPT-Live 키를 필수로 요구하면 demo에서는 Luna도 언제나 켜진다.
- 완료 기준: 저장소(작업 기록 · 지난 기록 제외)에 `PROVIDER_API_KEY`가 0건이고, 키 있음 · 없음 두 경우의 기동 점검 시험이 통과한다.

**FR13.2 비밀 이름 검사 목록** — Must · 출처 R-M9, R-F3
- 같은 커밋에서 비밀 이름 검사 목록 전부(`ci.yml` 번들 검사, `infra/ci/image-checks.sh`, `infra/bin/infra-validate` · `render-env` · `log-hygiene-check`, `scripts/verify/`, `infra/ci/gen-env.sh`, `README.md`, `infra/RUNBOOK.md`의 Parameter Store 항목)와 관련 인프라 시험(`test-infra-validate.sh` · `test-host-tools.sh` · 불변식 표본)을 고친다. 값은 어디에도 쓰지 않는다.
- demo 호스트 Parameter Store의 실제 이름 변경은 저장소 밖 운영 일이므로 RUNBOOK에 절차만 적는다(이번 작업은 배포를 다루지 않음).
- 완료 기준: `infra-validate` · 이미지 점검 · 비밀 스캔이 통과한다.

## 비기능 요구사항

| ID | 요구사항 | 기준(통과/실패) | 출처 |
|---|---|---|---|
| NFR1 | **지연 값은 설정으로**: 판정 **4초**(Q10 편차) · 응답 준비 10초 · FR12.7의 서버 확정 무음 시간은 `mujoong.timing.*` 설정에서만 읽는다. 판정 걸린 시간을 기록해 P50 · P95 · P99를 낼 수 있게 한다 | `ConfigKeyContractTest` · `TimeLiteralScanTest` 통과, 운영 소스의 시간 숫자 상수 0건, `judgment_live` 걸린 시간 칸 | 원 NFR1 · R-F7 · R-M5 · Q10 |
| NFR2 | **실패 시 막힘**: 판정 보류 · 시간 초과 · 형식 오류 · 장애 취소는 '판정 성공'으로 표시하지 않는다. 판정 턴 중 실패가 나도 미확인 출력이 기기로 가지 않는다 | 실패 주입 시험에서 기기 출력 0건, 판정 기록이 보류 · 취소로 남음 | 원 NFR2 · R-M11 |
| NFR3 | **개인정보 최소화**: 공급자에게는 판정에 필요한 전사와 최근 대화 3턴까지만 보낸다. 아이 이름은 '아이'로 바꾸고 식별자 · 보호자 입력 원문 · 원음은 보내지 않는다. 로그 · 실호출 보고서에는 전사 · 버린 출력 원문을 남기지 않는다(사유 코드 · 판 · 시간 · 개수만) | 가짜 판정기가 받은 요청 검사, `LogCapture` 검사 0건 | 원 NFR3 · R-F14 · R-F15 |
| NFR4 | **관측**: 판정마다 지점 · 결과(확정 · 보류 사유 · 장애 취소) · 걸린 시간 · 모델 · 프롬프트 판 · 선택지 판을 기록한다. 공급자 `error` 사건은 type · code · param만 사유 코드로 남긴다 | `judgment_live` 칸과 이벤트 로그 사유 코드 시험 | 원 NFR4 |
| NFR5 | **시험**: test-after, Standard 전략, 모듈 줄 80% · `u11` 범위 줄 80%. 시험 · CI · `verify.sh`는 가짜 공급자 · 가짜 판정기만 쓴다. 실호출은 격리 작업으로만 한다 | `check` · 범위 측정 통과, `verify.sh` PASS | 원 NFR5 · R-F1 · team.md |
| NFR6 | **회귀 없음 · 호환**: U1 ~ U9 시험이 모두 통과한다. 바꾸는 기존 단언은 FR2.2(두 곳)와 FR12.9(선 형식)뿐이고, 둘 다 새 사실로 바꾸며 느슨하게 하지 않는다. 레거시 U5b 연습 경로는 떼지 않고 그대로 돈다. 기기 ↔ 서버 계약(PCM16 24kHz)은 바꾸지 않는다 | `check`와 `verify.sh` 통과, 바뀐 단언 목록 | 원 NFR6 · ADR-001 · ADR-007 · R-F2 · Q7 |
| NFR7 | **운영 격리**: 사용자의 수동 테스트 스택 `mujoong-manual`과 그 볼륨을 건드리지 않는다. 검증은 `mujoong-verify`로 한다 | 검증 명령이 다른 Compose 프로젝트명 · 포트를 씀 | 원 NFR7 · R-F12 |
| NFR8 | **구조 경계**: `JudgeClient` 구현과 `judgment_live` 저장소는 새 패키지(예: `kr.mujoong.realtime.judgment`)에 두고, `luna` · `gate` · `voice`의 금지 의존을 지킨다. 판정 턴 상태와 출력 세대 상태는 실행기 스레드 전용 협력 객체로 떼어 `SessionExecutor`에는 호출 지점만 둔다 | ArchUnit 시험(고치지 않음) 통과 | U11-R9 · U11-R10 · 가정 8 |
| NFR9 | **증거 구분**: 선 형식 · 표본율 · 전사 사건 · 판정 지연의 성립은 테스트 대역 · 합성 대화 · 실제 API(`[실호출-합성]`) · 실제 기기로 나눠 보고하고, 가짜 공급자 통과를 실제 공급자 성립으로 적지 않는다 | 단위 보고서의 증거 표 | R-M11 · Q9 |

## 제약

- 마이그레이션: realtime `V5__u11_judgment_turn.sql` 하나만 더한다(더하기만). core 스키마는 바꾸지 않는다. Flyway `outOfOrder`가 꺼져 있고, U13 = realtime V6, U12 = V7이 뒤를 잇는다. 선 형식 정비 묶음에는 마이그레이션이 없다.
- 기존 표에 제약을 더하면 `infra/migration-approvals.txt`에 근거 한 줄을 적는다(R-M3). 새 표만 만들면 걸리지 않는다.
- 새 비밀은 없다. 비밀 이름 `PROVIDER_API_KEY`를 `OPENAI_API_KEY`로 합치므로 R-M9 목록 전부를 같은 커밋에서 고친다(FR13.2).
- 시간 값을 바꾸면 R-M5 세 곳(서버 전용 키는 timing.md · `application.yml` · 설정 레코드, 편차 U8-c)을 함께 고친다.
- 건드리지 않는 것: 프런트 · 기기 ↔ 서버 계약, `backend/core-api`의 동작(키 이름은 이미 `OPENAI_API_KEY`), 다른 단위의 커버리지 하한 · 계약 대조 시험 · ArchUnit 시험, 다른 작업 기록.
- 브랜치 · PR(Q12 답 A): 브랜치는 `unit/u11-judgment-turn`이다. 첫 묶음(FR12 · FR13 + 이미 있는 실호출 도구)을 먼저 PR로 `main`에 합치고(`ci-gate` 통과 뒤, 합친 뒤 `main` skeleton 확인), 그 위에서 판정 턴 묶음(FR1 ~ FR11)을 두 번째 PR로 연다. 두 번째 PR의 브랜치 이름과 첫 PR을 어디서 딸지는 Code Generation 계획에서 정한다. 커밋은 `feat(u11): …` 형식이고, 이 작업 기록 폴더도 커밋한다.

## Assumptions & Open Questions

### 가정

| 번호 | 가정 | 근거 |
|---|---|---|
| A1 | 질문 파일 「Assumption Confirmation」의 12건은 유효하다. 다만 범위 확장으로 가정 1의 "원 FR12.2 나머지는 범위 밖"은 선 형식 · 가짜 공급자 · 기존 시험 · 키 이름에 대해 더는 맞지 않고(FR12 · FR13), 가정 5의 메시지 이름은 `session.instructions.append` → `response.create`로 바뀐다(FR3.2) | 가정 확인 A, Step 3-3 답 B, 요약 확인 |
| A2 | 실호출에서 관측되지 않은 사건 · 필드(출력 전사 · 입력 전사 완료 · 말 끝 · 24kHz)는 FR12.6 · FR12.7의 합성 말소리 실호출로 확인하기 전까지 코드가 기대하지 않는다 | Q6 · Q7 · Q9 답 A |
| A3 | 지시서의 "Requirements Analysis 전에 실호출 결과를 받는다"는 사람 결정으로 "Code Generation 첫 단계에서 확인"으로 바뀌었고, 그 결과로 범위가 넓어졌다(지시서와의 편차, 계약 편차 아님) | Q1 답 B, Step 3-3 답 B |
| A4 | 되묻기 · 맞장구 · 다시 묻기는 고정 문구가 아니라 GPT-Live 지시로 만들고, 그 문장은 기존 출력 게이트를 거친다. U13 전까지 제브 검사는 없다 | Q4 답 A · Q5-1 답 A |
| A5 | 승인된 Functional Design(2026-10-16)은 FR1 ~ FR11에 대해서는 대부분 다시 쓰고, 응답 id · 메시지 이름 · 2초 시한에 기대던 규칙만 이 개정에 맞춰 고친다 | Step 3-3 답 B("요구사항 · 기능 설계로 되돌아가 개정") |

### 열린 질문

1. **출력 전사 · 말 끝 사건:** 실제로 오는지, 이름 · 모양이 무엇인지 FR12.7 실호출로 확인한다. 출력 전사가 없으면 출력 게이트 · U13 제브 검사의 전제가 무너지므로 멈추고 다시 정한다.
2. **24kHz 수락:** FR12.6 실호출 결과로 변환 여부를 정한다.
3. **세대 경계:** 출력 구간 세대가 바뀌는 시점과 끼어들기 때 버림 범위를 Functional Design에서 정한다(FR12.5).
4. **종료 근거 전달(FR6.5):** 보호자 화면 17이 쓰는 종료 근거를 지금 이벤트 · 계약이 실어 나르는지 Functional Design 첫 작업으로 확인한다. 계약에 더해야 하면 `contracts/`와 편차로 처리하고, 화면 반영은 프런트 단위 몫으로 넘긴다.
5. **판정 기록 쓰기 방식:** 실행기가 DB를 기다리지 않는 관례(`AsyncRecorder`)에 맞춘 비동기 쓰기 · 실패 처리를 Functional Design에서 정한다(U11-R13).
6. **J6 대상 범위:** `StopIntentJudge` CONFIRM 외에 '휴식' 뜻 발화를 어떻게 J6 후보로 잡을지 Functional Design에서 코드로 확인한다.
7. **실제 아동 데이터 전송:** 키를 넣는 순간 GPT-Live와 판정 턴이 함께 실제 아동 음성 · 전사를 공급자로 보낸다(FR13.1). R-F15 확인 기록(RUNBOOK 단계 4) 전에는 키를 넣지 않는다(편차 U9-b). 운영 투입 시점은 이 단위 밖이다.
8. **기술명세 v0.3의 미확인 필드:** `start_ms` · `session.instructions.appended` · `delegation_id` 등은 실호출로 확인될 때까지 쓰지 않는다. `delegation` · `decision.*` 구조는 이번에 조사하지 않는다(Q8 답 A).

## 범위 밖

- 연습 상태 기계 · 역할 정책 · J2 ~ J5 호출 · `record.practice` 채우기 → U12
- 제브 검사 단계 · `GUARDRAIL_BLOCKED` 내보내기 → U13(이 단위는 자리만 둔다)
- 공급자 쪽 응답 생성 통제(`delegation: client` · `decision.*`), 입력 음소거(`session.input_audio.mute`) → 쓰지 않음(Q8 답 A)
- 기기 · 프런트의 표본율 변경(Q7 답 A, 기기 계약 유지)
- demo 호스트 Parameter Store의 실제 키 이름 변경 · 배포(RUNBOOK 절차만 적음)
- 보호자 화면 17의 종료 근거 표시 반영(FR6.5의 화면 쪽)
- 원음 녹음의 S3 저장 전환(2026-10-16 사람 결정, U10 병합 뒤 별도 작업 기록). 전사 텍스트(`judgment_live`의 버린 출력 전사 포함)는 PostgreSQL에 그대로 둔다
- 배포 파이프라인 · 운영 배포(이 작업 기록에서 뺐다)

## Sources

- 이 단계의 질문과 답: `requirements-analysis-questions.md` Q1(B) · Q2(A) · Q3(A) · Q4(A) · Q5(B) · Q5-1(A), 가정 확인 A. Accept assumptions, 범위 확장 질문 Q6(A) · Q7(A) · Q8(A) · Q9(A) · Q10(A) · Q11(B) · Q12(A), 요약 확인 Looks correct(2026-10-17)
- 초기 설명(project-description.json): "U11 판정 턴 기반(u11-judgment-turn) 구현. 지시 docs/units/u11/brief.md를 정본으로 한다. 인셉션 산출물은 261015-doc-sync-code-update 기록의 것을 그대로 쓴다."
- 실호출 결과: `construction/code-generation/code-generation-questions.md` Step 3-1 ~ 3-3(답 A · A · B), `backend/realtime-activity/build/gpt-live/report.md`(2026-10-16T08:39Z, 키트 형식, 지원 type 15종 · 수신 사건 모양), Luna 응답 확인 2/2 · 2602 · 2938ms
- 지시: `docs/units/u11/brief.md`(첫 작업 · 범위 · 정본 · 정해 둔 값 · 맞물리는 곳 · 진행 방법)
- 원래 기록 `aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/`
  - `requirements-analysis/requirements.md`: FR3.1 ~ FR3.7 · FR4.8 · FR5.1 ~ FR5.4 · FR12.2, NFR1 ~ NFR7, 열린 항목 5
  - `user-stories/stories.md`: US1.7(AC1.7.1 ~ AC1.7.6) · US1.8(AC1.8.1 ~ AC1.8.6) · US1.10(AC1.10.1 ~ AC1.10.4) · US2.5(AC2.5.1)
  - `units-generation/unit-of-work.md` U11, `delivery-planning/bolt-plan.md` B3 · `risk-and-sequencing-rationale.md` R1 · R3 · R4 · R8 · 중단 조건
  - `contract-design/contract-summary.md` C-DS2 · C-DS3 · C-DS4 · C-DS15 · 4절
  - `domain-design/decisions.md` ADR-001 · ADR-005 · ADR-007
- 팀 정본 선 형식: `50_ADR_2S/architecture/protocol.md` · `ADR-004`, 기술명세 v0.3(§3.3 출력 구간 id · 서버 쪽 버림, §3.7 서버 말 끝 확정)
- 계약 파일: `contracts/ai-io.md` 2 · 3 · 8 · 10절, `contracts/timing.md` 13절, `contracts/schemas/ai-judgment-request.schema.json` · `ai-judgment-output.schema.json` · `practice-record-v2.schema.json`, `contracts/deviations.md` (z) · (aa) · (U4b-a) · (U8-a) ~ (U8-e) · (U9-a) ~ (U9-c)
- 기존 코드 파악: `aidlc/spaces/default/codekb/mujungi/business-overview.md` · `architecture.md`(U11 판정 턴 접점, 상호작용 4 · 5) · `code-structure.md`(실행기 내부 지도) · `code-quality-assessment.md` 6절(U11-R1 ~ R13), 키 사용처 검색(`PROVIDER_API_KEY` · `OPENAI_API_KEY`)
- 팀 규칙: `aidlc/spaces/default/memory/team.md`, `project.md`(R-F1 · R-F2 · R-F3 · R-F5 · R-F7 · R-F12 · R-F14 · R-F15 · R-M3 · R-M5 · R-M9 · R-M10 · R-M11, 교정)
