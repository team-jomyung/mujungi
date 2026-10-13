# 무중 아키텍처 결정 기록 (Domain Design 2.6)

> Domain Design(2.6) 산출물 · 2026-10-09 (클라우드 세션, 팀장 대리 판단)
> 입력: `requirements.md`(OQ1 · OQ2 · OQ6), `stories.md`(B1~B26 · OQ11), `components.md`, `domain-design-questions.md`(Q1~Q9), 50_ADR_2S · 60_ERD_v0.1 · 20_개발명세_1008 · 80_점검표_1008. 브라운필드 입력(`architecture` · `component-inventory`)과 `team-practices`는 없다(그린필드 · practices-discovery 생략).
> 번호는 팀 ADR_2S(ADR-001~010)에 이어 ADR-011부터 매긴다. 팀 ADR_2S 원본(`knowledge/documents/50_ADR_2S`)은 고치지 않는다. 화면 기준으로 고쳐야 할 ADR_2S 문장은 ADR-018의 표에 모았다(팀이 원본에 반영).
> 각 결정의 Jn은 `components.md`의 "준하 제안(가정)" 표 번호다. 상태는 모두 **Accepted(대리 판단 · 팀 확인 대기)** 이다.

## 결정 목록

| ADR | 제목 | 준하 제안 | 답한 질문 |
|---|---|---|---|
| ADR-011 | 결과 · 목표 · 상황 · 리포트의 소유를 Core로 통일 | J1 | OQ1 · Q1 |
| ADR-012 | 목표 정의 카탈로그는 Core 참조 테이블(버전), Realtime은 세션 스냅숏 | J2 | Q2 |
| ADR-013 | 결과 작업(ResultJob)은 Core 하나, 17 표시는 세션 상태 × 작업 상태 | J3 | Q3 |
| ADR-014 | 기기는 등록(Core)과 연결(Realtime)로 나눠 소유 | J4 | Q4 · OQ11 |
| ADR-015 | 진행 중 조회는 Realtime 중계, 끝난 기록은 종료 스냅숏으로 Core에 인계 | J5 · J6 · J10 | Q5 · Q6 |
| ADR-016 | 연습 상황 생성은 Core, 진행 · Cue · 판정은 Realtime | J7 | Q7 |
| ADR-017 | 리포트는 Core가 저장 기록으로 10개 항목을 만들고 서버에서 PDF로 렌더링 | J8 | OQ6 · Q8 |
| ADR-018 | 화면 설계 기준으로 ADR_2S · ERD를 고치는 21곳 | J12 | OQ2 |
| ADR-019 | 두 앱 안 모듈형 컴포넌트 26개와 앱 사이 접점 셋 | J9 · J11 | 컴포넌트 경계 |

---

## ADR-011: 결과 · 목표 · 상황 · 리포트의 소유를 Core로 통일

- **Context** — ERD v0.1은 목표 후보(core_goal_recommendation) · 담은 목표(core_practice_goal_selection) · 상황(core_scenario) · 08 배경(core_activity_context) · 생활 팁(core_support_item) · 결과 · 리포트를 core 스키마에 둔다. ADR_2S(ADR-007 §2.2 practice, data-model, result-pipeline §4)는 목표 정의 · 버전 · 선택 · 상황을 Realtime practice 소유로 두고 Core result Worker가 Realtime에 원격 등록하게 한다. 같은 사실을 두 앱이 쓰면 쓰기 주인이 둘이 되고(ADR-009 §2.3 위반), 보호자 화면 18 · 27 · 28 · 30은 모두 Core API를 부른다. 인수인계 규칙 3은 "결과·목표 소유는 Core로 통일(준하 제안)"이다. 10/9 스크럼 확인 묶음 7번이 이 질문이다(점검표 6절).
- **Decision** — Core가 결과 · 목표 후보 · 근거 · 담은 목표 · 상황 · 08 배경 · 생활 팁 · 결과 문서 · 리포트 · 사후 텍스트 AI 호출을 소유한다(컴포넌트 PracticeGoal · ResultPipeline · ActivityRecord · Report · PragmaticsKnowledge · TextAiGateway · ActivityCoordinator). Realtime은 진행 중 세션 · 기기 WSS · GPT-Live 중계 · 출력 게이트 · 제브 판정 프로토콜 · 세션–목표 스냅숏(realtime_session_goal)만 소유하고, 세션이 끝나면 Outbox → Inbox로 기록을 Core에 넘긴다(ADR-015).
- **Consequences**
  - 좋아지는 것: 보호자 화면이 Core 한 앱만 읽는다. 원격 목표 등록(Core → Realtime)과 그 응답 유실 복구 절차(result-pipeline "원격 등록의 영속 접수")가 사라진다. ERD v0.1의 테이블 배치를 거의 그대로 쓴다.
  - 나빠지는 것: 연습 중 다음 목표 상황을 Realtime이 Core에 동기로 요청해야 한다(ADR-016). Core 장애가 연습 중간 목표 전환을 막을 수 있다 — 그때 그 목표는 미실시(상황 생성 실패)로 넘어간다(B13).
  - 보안 · 규정: 아동 기록 · 리포트가 한 앱(Core)에 모여 계정 삭제(FR1.6)의 지휘가 단순해진다. Realtime 쪽 원본(전사 · 원음 · 연습 기록)은 DeletionRequested로 함께 지운다.
- **Alternatives Rejected**
  - *ADR_2S 그대로(Realtime practice 소유)*: 18 · 27 화면마다 Core → Realtime 동기 호출이 생기고, 결과 Worker의 원격 등록 · 재확인 절차가 7일 일정에 무겁다.
  - *목표는 Core, 상황 · 노출은 Realtime으로 분할*: 상황 노출 이력(FR7.5 중복 방지)을 확인하는 쪽(생성)과 쓰는 쪽(노출)이 갈려 앱 사이 조회가 늘어난다.

## ADR-012: 목표 정의 카탈로그는 Core 참조 테이블(버전), Realtime은 세션 스냅숏

- **Context** — 연습 대상은 4요소(행동 정의 · 기회 조건 · 상황 생성 조건 · 판정 기준)를 갖춘 목표만이다(FR4.4 · FR7.4 · FR7.6). ERD에는 behavior_key 문자열만 있고 정의 테이블이 없으며, data-model은 정의 · 버전의 엔터티 이름도 정하지 않았다. Neo4j 그래프 모델은 어느 문서에도 없다. 판정 기준이 세션 중에 바뀌면 같은 세션의 판정이 흔들린다(FR7.2의 버전 고정 원칙).
- **Decision** — Core PragmaticsKnowledge가 core_behavior_definition(behavior_key, version, 4요소, status)을 소유한다. 한 번 쓴 버전은 바꾸지 않고 새 버전을 만든다. 후보 · 담은 목표 · 상황은 behavior_key + version을 가리킨다. 연습 시작 때 Core가 세션 패키지에 정의 스냅숏을 넣고 Realtime PracticeProtocol이 realtime_session_goal에 고정한다. Neo4j는 공용 화용 지식 조회용이며 정의의 원장이 아니다. 판정 기준 본문 · 프롬프트는 AI 입출력 계약(WBS 1.7.3, OQ4)이 채운다.
- **Consequences**
  - 좋아지는 것: 판정이 세션에 고정된 기준만 쓴다. Realtime은 지식 저장소를 조회하지 않는다(STOP · heartbeat가 지식 조회를 기다리지 않는다, ADR-007 §2.1).
  - 나빠지는 것: 정의 데이터의 초기 적재(시드)가 필요하다 — 데모 시드(A6 · B22)에 목표 2~3개 정의를 함께 넣어야 한다.
  - 보안: 판정 기준은 세션 스냅숏에만 있고 대화 역할 지시문에는 들어가지 않는다(NFR3, DialogPolicy).
- **Alternatives Rejected**
  - *Neo4j 노드를 원장으로*: 그래프 모델이 미정이고 버전 고정 수단이 없다.
  - *Realtime 소유(ADR_2S)*: ADR-011과 충돌한다.

## ADR-013: 결과 작업(ResultJob)은 Core 하나, 17 표시는 세션 상태 × 작업 상태

- **Context** — ERD는 core_result_job(+attempt), 개발 명세는 REQUESTED → RUNNING → READY/FAILED, result-pipeline은 Realtime 쪽 기대 run · RESULT_PENDING · RESULT · INSUFFICIENT · RESULT_FAILED와 Core 쪽 PENDING · RUNNING · COMPLETED · FAILED를 둔다. 화면 17 · 27은 "정리 중 · 정리 끝 · 정리 실패 + 다시 시도"만 보인다(AC3.6.12 · AC5.2.4). 리포트도 비동기 생성이다(NFR8 60초 · 3회).
- **Decision** — ResultJob 모델은 Core ResultPipeline 하나다. 상태 REQUESTED → RUNNING → DONE / FAILED, 시도 최대 3회(core_result_job_attempt), 서버 재시작 때 RUNNING이던 작업은 FAILED로 닫고 보호자 「다시 시도」로만 새 작업을 만든다. 작업 종류는 SESSION_RESULT와 REPORT. 한 세션에 동시 작업은 하나이고 다시 시도해도 결과는 한 번만 현재 버전이 된다(FR7.7 · AC5.2.7). Realtime은 결과 작업 상태를 갖지 않는다. 17 표시는 아래 표대로다.

| 17 표시 | 세션 상태(Realtime 원장 · Core 사본) | 결과 작업 상태(Core) |
|---|---|---|
| 마무리 중 | CLOSING | (없음) |
| 정리 중 | ENDED · ENDED_UNCONFIRMED · ABORTED · FAILED(ACTIVE를 지난 세션) | REQUESTED · RUNNING (또는 기록 인계 전) |
| 정리 끝 | 위와 같음 | DONE |
| 정리 실패 + 「기록에서 다시 시도」 | 위와 같음 | FAILED(자동 3회 뒤) |
| 17 없음 | DECLINED(→ P3 → 04) · FAILED(시작 단계 → 08 · 18) | 작업 없음 |

  종료 사유 한 줄과 "기기 응답 없이 끝냄"(confirmed=false)은 세션 상태에서, 정리 상태는 작업 상태에서 따로 온다. ABORTED · ENDED_UNCONFIRMED · FAILED는 마무리 중 없이 정리 중부터 시작한다(AC3.6.10).
- **Consequences**
  - 좋아지는 것: 작업 실행기 하나(선점 · 재시도 · 재시작 처리)를 결과와 리포트가 같이 쓴다. Realtime ↔ Core의 결과 통지(ResultReady · ResultFailed) 이벤트가 필요 없다.
  - 나빠지는 것: 재시작 때 진행 중 작업이 자동으로 이어지지 않아 보호자가 다시 눌러야 한다(7일 일정의 단순성을 택함). ADR-010의 lease/generation 재선점은 쓰지 않는다.
  - 운영: 작업 FAILED는 17 · 27에 보이므로 사람이 알아챈다.
- **Alternatives Rejected**
  - *Realtime · Core 두 작업 모델(result-pipeline)*: ADR-011로 결과가 Core에 모였으므로 Realtime 쪽 기대 run이 쓸 데가 없다.
  - *재시작 때 RUNNING을 다시 선점(ADR-010 lease)*: 구현 비용이 크고 규칙 3이 FAILED로 정했다.
  - *리포트는 요청 안에서 동기 생성*: 60초 상한(NFR8) 안에서도 요청 타임아웃 · 「취소」 처리가 꼬인다.

## ADR-014: 기기는 등록(Core)과 연결(Realtime)로 나눠 소유

- **Context** — 화면 05는 서로 독립인 두 줄(기기 등록 · 기기 네트워크 현황)과 둘을 합친 판정 카드를 보여 준다(D-18). 등록은 서버 발급 8자리 코드 + 05a 입력이고(점검표 1, FR2.1) 계정당 기기 하나다. ADR_2S는 운영자 사전 시드 · 고정 ID였고 Device 엔터티 이름도 TBD다. 개발 명세는 "Core(등록) + Realtime(상태)"이지만 bootstrap 경로를 Realtime에 적어 한 테이블에 두 앱이 쓸 수 있다. stories OQ11은 서버 상태 7개 ↔ 보호자 5단계 대응을 이 단계에 넘겼다.
- **Decision** — Core DeviceRegistry가 core_device(코드 · 기기 비밀 해시 · 기기 토큰 해시 · 등록 상태 UNREGISTERED/REGISTERED/RELEASED · 보고받은 연결 상태 사본)를 소유하고 bootstrap · register API를 받는다. Realtime DeviceGateway가 realtime_device_connection(연결 상태 OFFLINE/CONNECTED/UNSTABLE/DISCONNECTED/UNRESPONSIVE · 마지막 수신 · 지연 · 최근 끊김)을 소유하고 WSS · heartbeat · 판정을 한다. WSS 접속 때 토큰은 Core 내부 HTTP로 확인하고(결과 캐시), 상태 변화는 DeviceStatusChanged(Outbox), 최신값은 주기 보고(내부 HTTP)로 Core에 간다. Core가 30초 넘게 보고를 못 받으면 "기기 상태 확인 불가"(B6). 서버 상태 7개 ↔ 5단계 대응은 `components.md` "기기 상태 대응표"다. 재등록(D0 "옛 Device 행 해제")은 계정의 기존 기기가 연결돼 있지 않을 때만 받아 같은 트랜잭션에서 옛 기기를 RELEASED로 바꾼다(J13). 보호자 쪽 재등록 진입 단추는 화면 설계에 없어 OQ12로 남긴다.
- **Consequences**
  - 좋아지는 것: 계정 1:1 · 재등록 해제 · 계정 삭제 때 토큰 무효가 계정 쪽에서 한 번에 지켜진다. heartbeat는 실시간 경로(Realtime)에만 있다.
  - 나빠지는 것: 기기 화면(D0)이 Core(bootstrap)와 Realtime(WSS) 두 곳을 부른다. 토큰 확인이 Core에 기대므로 Core가 내려가면 새 WSS 접속이 막힌다(이미 연결된 기기는 캐시로 유지).
  - 보안: 기기 토큰은 해시로만 저장하고 모델 키 · 세션 키는 기기에 주지 않는다(NFR9). 미등록 · 해제 토큰의 WSS는 거부된다.
- **Alternatives Rejected**
  - *Realtime이 등록 · 토큰 · 상태를 모두 소유*: 계정 1:1 제약과 계정 삭제 연쇄가 Realtime으로 넘어가고 Core 등록 API가 Realtime을 동기로 불러야 한다.
  - *Core가 heartbeat까지 받음*: 실시간 판정(지연 1초 · 40초)이 보호자 서버 부하 · 장애와 엮인다.
  - *운영자 시드 유지(ADR_2S)*: 화면 05a · D0과 어긋난다(점검표 1).

## ADR-015: 진행 중 조회는 Realtime 중계, 끝난 기록은 종료 스냅숏으로 Core에 인계

- **Context** — 12는 1~2초 폴링으로 상태와 전사를 받고(FR3.2 · 점검표 9) 말풍선은 말 끝 뒤 1.5초 이내가 목표다(NFR5). 보호자 인증은 Core에만 있다(ADR-008). 26 · 27 · 28 · 30 · PDF는 끝난 활동의 전사 · 연습 기록을 읽는다. ADR-009는 상대 스키마를 직접 읽는 View를 예외 · 미정으로 두고, 개발 명세는 TranscriptAppended · PracticeStageChanged · CueDelivered · EvaluationMade · AudioSegmentUploaded 같은 진행 중 개별 이벤트를 적었다. 규칙 3: "세션이 끝나면 Outbox→Inbox로 결과를 Core에 넘긴다."
- **Decision**
  1. 진행 중: ParentWeb → Core ActivityCoordinator(보호자 · 세션 소유 확인) → Realtime SessionLifecycle 진행 조회(내부 HTTP, 전사 since 순번). Core는 SessionStateChanged로 세션 상태 사본(core_activity)만 유지해 04 · 시작 검사에 쓴다.
  2. 종료 뒤: SessionLifecycle이 결과 입력 스냅숏(realtime_result_input_snapshot: 아이 전사 · 승인된 AI 말 · 불확실 표시 · 끊김/미확인 구간 · 연습 목표와 단계 기록 · 진행 중 판정값 · 원음 메타)을 만들어 SessionRecordCaptured로 보내고, Core ActivityRecord가 core_activity_record로 보관한다. 26 · 27 · 28 · 30 · PDF와 결과 작업은 이 Core 기록만 읽는다. DECLINED는 머리 정보만 넘긴다.
  3. 내부 이벤트는 Realtime → Core: SessionStateChanged · SessionRecordCaptured · DeviceStatusChanged · DeletionCompleted, Core → Realtime: DeviceReleased · DeletionRequested. 시작 · 끝내기는 즉시 명령이라 내부 HTTP다(ADR-010 "즉시 명령은 내부 HTTP"). 개발 명세의 진행 중 개별 이벤트들은 종료 스냅숏 하나로 합친다(J10).
- **Consequences**
  - 좋아지는 것: 말풍선 지연에 Outbox 주기가 끼지 않는다. 끝난 기록은 Realtime이 멈춰도 Core에서 읽힌다. 앱 사이 이벤트 종류가 6개로 줄어 계약 · 시험이 작아진다.
  - 나빠지는 것: 전사 · 연습 기록이 Realtime 원본과 Core 기록 두 벌로 있다(삭제 때 둘 다 지운다). 진행 중 12는 Realtime 장애 때 "기기 상태 확인 불가 + 마지막 확인 시각"으로만 보인다.
  - 보안: 조회 응답과 리포트 · PDF 직렬화에서 진행 중 판정값 · 판정 근거 · 보호자 입력 원문을 뺀다. 기록용 재판정은 장면 결과 칩으로만, 경험 구조화는 출처 표시 문장으로만 나간다(FR4.3 · A10). 스냅숏 해시로 인계 내용이 같음을 확인한다.
- **Alternatives Rejected**
  - *진행 중 이벤트마다 Core에 복제(TranscriptAppended)*: 말풍선 지연이 Outbox 주기만큼 늘고 이벤트 수가 많다.
  - *Core가 realtime 스키마를 읽기 전용 View로 읽기*: ADR-009가 예외로 미정, 스키마 결합.
  - *끝난 기록도 조회마다 Realtime 내부 API로*: Realtime 장애가 26 · 27 · PDF까지 멈춘다.

## ADR-016: 연습 상황 생성은 Core, 진행 · Cue · 판정은 Realtime

- **Context** — 상황은 생성 뒤 자동 확인(목표 · 트리거 포함, 금지 표현)하고 실패하면 1회 재생성, 두 번 실패면 시작하지 않는다(FR7.5). 이전 활동에서 노출된 상황은 다시 쓰지 않는다. 다음 목표 상황은 앞 목표가 끝날 때 만든다(B13). Cue는 GPT-Live 실시간 5초 예산(FR5.5), 판정은 판정 LLM 5초 + 재시도 1회(FR7.6). 규칙 3 · 4: core_scenario는 Core, 제브 판정 프로토콜은 Realtime.
- **Decision** — Core PracticeGoal이 상황 생성 · 확인 · 재생성 1회 · 노출 이력 확인을 하고 core_scenario에 저장한다(텍스트 AI는 TextAiGateway). 첫 목표 상황은 연습 시작 요청 때 Core가 만들어 세션 패키지에 넣는다(두 번 실패면 시작 거절 SCENARIO_GEN_FAILED → 18). 다음 목표는 Realtime PracticeProtocol이 Core에 내부 HTTP로 요청하고 두 번 실패면 그 목표를 미실시로 넘긴다. 노출은 Realtime이 realtime_scenario_exposure에 남기고 종료 스냅숏으로 Core에 돌아와 core_scenario를 EXPOSED로 바꾼다. 단계 진행 · 도움 동의 · Cue(GPT-Live) · 판정(ResponseEvaluator)은 Realtime이다.
- **Consequences**
  - 좋아지는 것: 상황 · 노출 이력이 한 주인(Core)에게 있어 중복 방지가 단순하다. 판정 기준은 Realtime 세션 스냅숏에만 있고 대화 역할로 새지 않는다.
  - 나빠지는 것: 목표 전환 때 Core 왕복이 생긴다. 승인 대기 문구(판정 · 상황 준비 중 아이가 듣는 문구)는 OQ14로 남는다.
  - 보안: 상황 본문은 보호자에게 미리 보여 주지 않는다(FR4.2).
- **Alternatives Rejected**
  - *Realtime이 상황 생성(ADR_2S)*: ADR-011과 충돌, Realtime이 텍스트 AI · 노출 이력을 따로 가져야 한다.
  - *시작 때 모든 목표의 상황을 미리 생성*: 목표 N개 × 생성 · 확인 시간이 시작 지연(준비 10초 · 기기 응답 20초)과 겹친다.

## ADR-017: 리포트는 Core가 저장 기록으로 10개 항목을 만들고 서버에서 PDF로 렌더링

- **Context** — 상담용 리포트는 이름 · 순서가 고정된 10개 항목이고(FR6.5), 저장 기록(경험 구조화 · 인용 · 기록용 재판정 · 제공한 도움 · 종료 사유 · 확보 범위)과 아이 정보(이름 · 나이)만으로 채운다. 수치는 서버 집계값만 쓰고, 인용은 저장 전사와 글자 단위로 일치해야 하며, 자료가 없으면 "자료 부족"으로 둔다. "진단 결과가 아니에요" 고지를 30 · PDF에 넣는다(NFR1 · D-13). 30에서 PDF를 저장 · 공유(Web Share API)하려면 파일이 필요하다(점검표 3절 'PDF 생성 방법', OQ6).
- **Decision** — Core Report가 REPORT 작업으로 항목을 만든다. 수치 · 목록 항목(1 · 3 · 4 · 7 · 8 · 9)은 서버가 집계 · 조립하고, 문장 항목은 TextAiGateway로 만든 뒤 인용 대조 · 금지 표현 2단계 검사를 거쳐 맞지 않으면 고정 템플릿 문장으로 바꾼다. 항목은 core_report_section에 저장하고, 30은 이 항목을 보여 주며, PDF는 서버에서 HTML 템플릿 → PDF로 렌더링한다(Java 라이브러리, 한글 글꼴 파일 임베드). 진행 중 판정값 · 점수 · 성공률은 입력에 넣지 않는다. 30에서 저장하면 리포트와 항목을 계정 삭제까지 보존하고 「나중에」 · 「←」로 버린 리포트는 폐기한다(A4). 라이브러리 이름(예: OpenHTMLtoPDF)과 글꼴 · 성능 검증은 3.3 NFR Design · 3.4에서 정한다.
- **Consequences**
  - 좋아지는 것: 컨테이너가 하나 덜 든다(EC2 1대). 30 화면과 PDF가 같은 저장 항목을 그리므로 내용이 어긋나지 않는다. 문구 · 표현 제한(NFR1) 검사를 항목 저장 전 한 곳에서 한다.
  - 나빠지는 것: 텍스트 AI 호출과 서버 렌더링을 합쳐 60초 안에 맞춰야 한다(활동 20건 기준 시험, NFR8). 한글 글꼴 파일이 이미지에 들어간다.
  - 보안: 동의 범위 밖 전사 · 인용이 들어가지 않게 입력 필드를 화이트리스트로 고정한다. 텍스트 AI에는 이름 대신 별칭을 보내고 아이 이름은 서버가 머리에만 넣는다(NFR3).
- **Alternatives Rejected**
  - *Next.js 서버에서 헤드리스 Chromium*: 별도 브라우저 런타임이 무겁고 컨테이너가 늘어난다.
  - *보호자 브라우저 인쇄*: Android Chrome에서 파일 공유가 안 되고 기기마다 결과가 다르다.

## ADR-018: 화면 설계 기준으로 ADR_2S · ERD를 고치는 21곳 (OQ2)

- **Context** — 점검표 2절이 화면 설계 · 개발 명세와 ADR_2S · ERD v0.1 · WBS가 다른 21곳을 모았다. 규칙 1: 화면 설계 v1.5j > 승인된 requirements · stories · mockups > 요구사항 v1.1 > ERD · ADR_2S > WBS. 원본 문서는 이 워크플로가 고치지 않는다.
- **Decision** — 21곳 모두 화면 쪽을 따른다. 처리와 이 산출물의 반영 위치는 아래 표다. ADR_2S · ERD · WBS 원본 문장 수정은 팀이 한다(열린 일 OQ2-a).

| # | 항목 | 이 워크플로의 처리 | 반영 위치 |
|---|---|---|---|
| 1 | 기기 등록 방식 | 서버 발급 8자리 코드 + 05a. ADR-003 · 008의 'Pairing Code 미채택'은 채택으로 | DeviceRegistry · core_device(신설) · ADR-014 |
| 2 | 원음 저장 | 원음 저장(동의 4항목), ADR 여섯 곳 '원음 미저장' 문장 정정, 위치 · 백업은 3.4 | VoiceRelay · AudioRecording |
| 3 | 세션 상태 이름 | 화면 11개, ENDED_UNCONFIRMED를 enum에, PAUSED · ENDING · PARTIAL · RESULT_* 없음 | SessionLifecycle · ActivitySession |
| 4 | 종료 사유 값 | 8개 enum(GUARDIAN_STOP · CHILD_STOP · SILENCE · DISCONNECT · CONSENT_WITHDRAWN · UNCONFIRMED · DECLINED · FAILED) | ActivitySession.endReason |
| 5 | 보호자 끝냄 마무리 인사 | 모든 종료에 승인 마무리 인사 1회(고정 승인 콘텐츠) | SessionLifecycle · ApprovedContentCatalog |
| 6 | 아이 무응답 | 참여 대기 20초 → 재안내 → 20초 → DECLINED, 진행 중 4분 경고 · 6분 SILENCE | SessionLifecycle · DialogPolicy |
| 7 | 기기 상태 단계 · 끊김 시간 | 5단계 · 지연 1초 · 5분 안 2회 · 40초, Device 상태 두 축 | DeviceGateway · `components.md` 대응표 |
| 8 | 쉬기 · 재개 | 없음(PAUSED 미구현) | SessionLifecycle |
| 9 | 보호자 실시간 수신 | 12 말풍선 유지, 전송은 2초 폴링, 세션 조회 응답에 전사 포함 | ActivityCoordinator · ADR-015 |
| 10 | 푸시 알림 | 웹 푸시 장애 3종 + 활동 완료 1종 Must, core_push_token(신설) | Notification |
| 11 | 아이 선택 · 목적 입력 | 없음. core_activity_context.purpose 삭제 | ActivityContext |
| 12 | 08 네 칸의 쓰임 | 결과 구조화 입력만, 대화 모델 미전달 | ActivityCoordinator · ResultPipeline · DialogPolicy |
| 13 | 동의 · 철회 · 계정 삭제 | 철회 없음, 계정 삭제 접수형(DELETE /api/me 계열), core_consent.child_id nullable, 세션 동의 스냅숏 4항목 | ConsentPolicy · AccountDeletion · ActivitySession.consentSnapshot |
| 14 | 종료 표현 | 문장 전체 2~20자 · 추가 5개, expression_suffix → expression_text(20), 인식은 Realtime이 전사로 판단 | ChildProfile · DialogPolicy |
| 15 | 연습 세션 목표 수 | 여러 개(n/N), realtime_session_goal(신설), 단일 목표 컬럼 삭제 | PracticeProtocol · SessionGoal |
| 16 | 결과 소유권 | Core 통일 | ADR-011 |
| 17 | 리포트 내용 | 저장 기록 기반 10개 항목(core_report_section 신설) + 서버 PDF, period_from · period_to 추가, result_id 삭제 | Report · ADR-017 |
| 18 | 출력 게이트 | ADR-004대로, 12 말풍선은 승인된 AI 말만(approved_for_display) | OutputGate · Transcript |
| 19 | 플랫폼 | Next.js 웹 한 앱 두 접점, PWA 아님(웹 푸시는 서비스 워커만) | ParentWeb · DeviceWeb |
| 20 | 아이 수 모델 | 1:1, GuardianChild 없음, core_child.guardian_id UNIQUE, 시드 보호자 2 · 아이 2 · 기기 2 | ChildProfile |
| 21 | 목표 후보가 목록에 들어가는 방식 | 후보 생성 · 등록은 자동, 18에 쌓는 것은 보호자 「담기」 | PracticeGoal |

- **Consequences** — ERD v0.1은 v0.2로 고쳐야 한다(신설 8 테이블 · 컬럼 변경 J12). ADR_2S 원본은 팀이 고칠 때까지 이 표가 우선한다. WBS 문구 수정은 점검표 4절 제안대로이며 Units Generation · Delivery Planning이 이 표를 기준으로 쓴다.
- **Alternatives Rejected**
  - *ADR_2S를 기준으로 화면을 바꾸기*: 규칙 1 · 승인된 requirements와 어긋난다.
  - *충돌을 단계마다 그때그때 정하기*: 같은 질문이 2.7 · 2.8 · 3.1에서 반복되고 답이 갈린다.

## ADR-019: 두 앱 안 모듈형 컴포넌트 26개와 앱 사이 접점 셋

- **Context** — 실행 구조(두 Spring 앱 + Next.js 한 앱, PostgreSQL 앱별 스키마)는 바꾸지 않는다(K1). ADR-007은 모듈 후보(auth · guardian · child · consent · activity · voice · conversation · practice · evaluation · result · report · ai · content · knowledge)를 두고 모듈 사이 순환 · Repository 직접 접근을 금지한다. 화면 기준 결정(ADR-011 · 014 · 015)으로 Core에 새 책임(기기 등록 · 활동 창구 · 활동 기록 · 알림 · 계정 삭제)이 생겼다.
- **Decision** — `components.md`의 26개 컴포넌트로 나눈다(화면 2 · Core 15 · Realtime 9). 원칙:
  1. 엔터티는 정확히 한 컴포넌트가 쓴다. 다른 앱 엔터티는 DB FK 없는 논리 참조만.
  2. 앱 사이 동기 호출은 셋뿐: ActivityCoordinator → SessionLifecycle(시작 · 끝내기 · 진행 조회), DeviceGateway → DeviceRegistry(토큰 확인 · 상태 보고), PracticeProtocol → PracticeGoal(다음 목표 상황). 나머지는 CoreMessaging ↔ RealtimeMessaging(Outbox → Inbox).
  3. 업무 컴포넌트 사이 순환은 없다. 앱 안 역방향 알림은 앱 내부 사건(ActivityRecorded 등)이나 처리기 등록으로 푼다. 의도된 순환은 두 메시징 컴포넌트 한 쌍뿐이다.
  4. sessionId는 Core가 발급하고 Realtime은 같은 값을 쓴다(J11).
  5. 공급자 SDK는 TextAiGateway(Core) · VoiceRelay · ResponseEvaluator(Realtime) 안에만 둔다.
  ADR-007 모듈과의 대응: auth+guardian → GuardianIdentity, consent → ConsentPolicy, child → ChildProfile, content → ApprovedContentCatalog, knowledge → PragmaticsKnowledge, ai → TextAiGateway, result → ResultPipeline, report → Report, activity → SessionLifecycle(+DialogPolicy), voice → DeviceGateway · VoiceRelay · OutputGate, conversation → ConversationLog, practice → PracticeProtocol(실행) + PracticeGoal(목표 · 상황, Core로 이동), evaluation → ResponseEvaluator. 신설: DeviceRegistry · ActivityCoordinator · ActivityRecord · Notification · AccountDeletion · CoreMessaging · RealtimeMessaging.
- **Consequences**
  - 좋아지는 것: 담당 A~E가 컴포넌트 묶음으로 나뉜다(유닛은 2.7). 컴포넌트마다 소유 데이터가 분명해 계정 삭제 · 시험 범위가 정해진다.
  - 나빠지는 것: 컴포넌트 수가 많아 7일 구현에서 패키지 경계를 지키는 규율이 필요하다(ArchUnit류 검사는 3.3에서 검토).
  - 운영: 앱 사이 접점이 셋 + 이벤트 6종이라 장애 지점이 좁다.
- **Alternatives Rejected**
  - *ADR-007 모듈 이름 그대로(신설 없음)*: 기기 등록 · 활동 기록 · 계정 삭제가 갈 곳이 없어 기존 모듈에 섞인다.
  - *Realtime의 voice를 하나로*: 출력 게이트 안전 시험을 독립으로 돌릴 수 없다.
  - *메시징을 공용 라이브러리 하나로*: 각 앱은 자기 Outbox/Inbox만 복구해야 하므로(ADR-010) 소유가 앱별이어야 한다.

## 열린 일 (뒤 단계 · 팀)

| 번호 | 할 일 | 누가 · 언제 |
|---|---|---|
| OQ2-a | ADR_2S · ERD v0.1 · WBS 원본에 ADR-018 표 반영(ERD v0.2) | 팀(A · B · E) · AI-DLC 밖 |
| OQ3 | 내부 HTTP 3종 · 이벤트 6종 · 기기 WSS · REST의 물리 계약, heartbeat · 상태 보고 주기 | Contract Design(2.8) |
| OQ4 · OQ14 | 판정 기준 본문 · 프롬프트, 연습 단계 enum, 판정 대기 승인 문구 | Contract Design · Functional Design · D |
| OQ5 | 원음 · 승인 음성 파일 위치 · 백업, HTTPS · 도메인 | Infrastructure Design(3.4) |
| OQ13 | 코드 유효 기간 · 같은 토큰 동시 연결 처리 | Contract Design(2.8) |
| OQ15 | 금지 표현 사전 v1 소유 · 위치 | Contract Design · Build and Test |
