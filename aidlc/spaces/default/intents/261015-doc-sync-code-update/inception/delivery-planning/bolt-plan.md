# 볼트 계획

> **볼트**는 한 번의 구현 묶음이다. 단위 하나 이상을 설계 · 코드 · 시험까지 끝내 실제로 돌아가는 결과를 내는 작업 한 바퀴를 말한다. 이 작업은 단위 하나를 볼트 하나로 한다(Q2-A).
> 근거: `../units-generation/unit-of-work.md` · `../units-generation/unit-of-work-dependency.md` · `../units-generation/unit-of-work-story-map.md`(단위 U8~U15와 의존 그래프), `../contract-design/contract-summary.md`(계약 15개 · 열린 질문 7개), `../requirements-analysis/requirements.md`, `../user-stories/stories.md`, `../domain-design/components.md`, `../refined-mockups/mockups.md`(27 · 목표 칸 화면 항목), 팀 규칙 `../practices-discovery/team-practices.md` · `team.md`, 질문 · 답 `delivery-planning-questions.md`(Q1-A 위험 먼저 · Q2-A · Q3-A 팀 방식 · Q4-A · Q4-1 A · Q5 · Q5-1 A · 가정 1~7).
> 팀 규칙 적용: 브랜치는 `main`에서 따고 PR은 merge commit, `ci-gate` 통과 뒤 병합한다. 병합 순서는 마이그레이션 번호 순서를 따른다. `classic` 범위라 첫 볼트를 끝-끝 조각으로 만드는 의식(**워킹 스켈레톤**: 처음부터 끝까지 이어지는 가장 얇은 조각)은 없다. 끝-끝 회귀 기준은 `bash scripts/verify.sh`다.

## 순서 요약

순서 전략은 **위험 먼저**다(Q1-A). 모두가 기대는 공통 기반(B1 · B2)을 먼저 끝낸다. 그다음 설계를 뒤집을 수 있는 가장 큰 위험, 즉 GPT-Live가 서버 쪽 음성 중계를 지원하는지를 B3의 첫 작업으로 확인한다. core 줄은 그와 병렬로 간다.

| 순서 | 볼트 | 단위 | 줄 | 담당 기록 | 마이그레이션 | 앞 볼트 |
|---|---|---|---|---|---|---|
| 1 | B1 | U8 계약 · 버전 리소스 | 공통 | **이 기록** | 없음 | — |
| 2 | B2 | U9 Luna 호출부 | 공통 | **이 기록** | 없음 | B1 |
| 3 | B3 | U11 판정 턴 기반 | realtime | 지시서 → 담당자 새 기록 | realtime V5 | B1 · B2 |
| 3 | B4 | U15 지식 그래프 | core | 지시서 → 담당자 새 기록 | core V8 | B1 · B2 |
| 4 | B5 | U13 제브 게이트 | realtime | 지시서 → 담당자 새 기록 | realtime V6 | B3 |
| 4 | B6 | U14 목표 · 결과 | core | 지시서 → 담당자 새 기록 | core V9 | B4 |
| 5 | B7 | U10 상황 생성 | core | 지시서 → 담당자 새 기록 | core V10 | B6 · B4 |
| 6 | B8 | U12 새 연습 흐름 | realtime | 지시서 → 담당자 새 기록 | realtime V7 | B5(파일 충돌) · B3 · B7 |

- 같은 '순서' 번호의 볼트는 동시에 만들 수 있다(B3 ‖ B4, B5 ‖ B6).
- core와 realtime은 Flyway 이력이 따로라 두 줄의 번호는 서로 기다리지 않는다. 같은 앱 안에서는 표의 번호 순서대로 병합한다.
- 마이그레이션이 필요 없다고 판명되면 그 번호는 다음 볼트로 넘기지 않고 비워 두지 않도록 Functional Design에서 다시 배정한다. 빈 번호는 Flyway가 허용하지만 팀 혼란을 막는다.
- B8을 B5 뒤에 둔 것은 의존 그래프에는 없는 순서다. U12와 U13이 같은 파일(`SessionExecutor` · `OutputGate`)을 고쳐 한 담당자가 차례로 맡는다(순서 근거 문서).

## 볼트별 계획

### B1 — U8 계약 · 버전 리소스 (이 기록)

- **포함 단위:** U8(`u8-contracts-catalog`, spec). 스켈레톤 표시 없음.
- **완료 기준**
  - `contracts/`에 공통 계약이 들어갔다.
    - `ai-io.md` 규칙 · 금지 필드
    - `schemas/ai-*.schema.json` · `guardrail-*.schema.json`(Q2-A)
    - 기록 캡처 이벤트 `record.practice` 필드
    - 시간 키 8개를 세 곳에 함께(R-M5)
    - 비밀 이름 목록 전부(R-M9)
    - `deviations.md` 행
  - `ApprovedContentCatalog`(버전 리소스 적재 · 세션 고정 · 대본 등록 · 원문 일치)과 단위 시험이 있다.
  - Contract Design 지적 중 이 단위 몫(R-02 재시도 키 중복 → 기존 `result.job-attempts` · `job-retry-backoff` 값 변경, R-03 `snapshotVersion` 대신 `flowVersion`, R-07 Luna 공통 재시도 정책)을 실제 `contracts/` 파일과 대조해 바로잡았다.
  - 계약 대조 시험(`ContractFilesTest` · `ErrorCodeTest` · `ConfigKeyContractTest` · `TimeLiteralScanTest` · `timing.test.ts`)과 백엔드 · 프런트 정적 · 단위 시험이 통과한다. `ci-gate`가 통과한다.
- **확신 가설(이 볼트로 알게 되는 것):** 공통 계약을 먼저 고정하면 다른 단위가 계약 대조 시험을 깨지 않고 더하기만으로 이어 갈 수 있다. 기존 정본과 부딪히는 곳이 이 볼트에서 모두 드러난다.
- **보여 줄 것:** 계약 대조 시험 전부 통과 화면, 새 스키마 예시가 검증을 통과하는 시험, 세션 시작 때 콘텐츠 버전이 고정되는 단위 시험.

### B2 — U9 Luna 호출부 (이 기록)

- **포함 단위:** U9(`u9-luna-clients`, library).
- **완료 기준**
  - core · realtime 각자에 Luna 호출부가 있다(openai-java Responses API · 구조화 출력, B1의 스키마 사용).
    - 키가 없으면 꺼진다.
    - 필드 이름 감사 로그가 남는다.
    - 아이 이름을 '아이'로 치환한다.
    - 요청 · 응답 본문은 로그에 남기지 않는다.
  - 기존 `TextAiProvider` · `ResultTextProvider` 기본 실패 빈을 설정으로 켜고 끈다.
  - 가짜 공급자가 있다.
  - 시험 · CI는 가짜 공급자만 쓴다(R-F1).
  - 실제 공급자 확인은 합성 자료로만 하고 `[실호출-합성]`으로 따로 보고한다(R-M11 · R-F15).
- **확신 가설:** 두 앱이 코드를 공유하지 않고도(ADR-004) 같은 계약으로 Luna를 부를 수 있고, 키 없는 환경(CI · dev)에서 지금 동작이 그대로 유지된다.
- **보여 줄 것:** 가짜 공급자로 구조화 출력 · 스키마 재요청 · 시간 초과가 시험으로 통과한다. 키가 있는 이 PC에서는 합성 요청 한 번의 실호출 결과를 따로 보인다.

### B3 — U11 판정 턴 기반 (지시서)

- **포함 단위:** U11(`u11-judgment-turn`).
- **첫 작업(위험 확인):** GPT-Live 실호출 확인(FR12.2)을 짧게 당긴다. 다음 셋을 합성 대화로 확인한다.
  - 서버 쪽 음성 전송이 되는지(ADR-007)
  - 세션 생성 · 지시 주입 · 종료 형식
  - 말 끝 신호
  - 조원 하네스의 `POST /v1/live/sessions` · `/attach` 형식을 참고한다. 안 되면 볼트를 멈추고 설계를 다시 정한다(순서 근거 문서의 중단 조건).
- **완료 기준**
  - `JudgeClient` · 보류 규칙 · `judgment_live`(realtime V5)가 있다.
  - 판정 턴 출력 보류 · '생각 중' 유지 · 10초 응답 준비 · 장애 취소 · 종료 표현 즉시 처리 · 자유대화 J1 · J6가 동작한다.
  - 확장점(`PracticeSessionExtension` · `PracticeOutput`)을 넓혔다(Q1-A of Contract Design, 레거시 U5b 경로는 그대로).
  - Domain Design R-03 · R-04 · R-05, Contract Design R-05(a)(게이트 단계 확장점) · R-06을 Functional Design 첫 작업으로 정리했다.
  - US1.7 · US1.8 · US1.10 · US2.5 인수 시험(`[자동-대역]`)이 통과한다.
  - 유닛 범위 줄 80%(`coverage-scope/u11`).
- **확신 가설:** 판정 턴 동안 GPT-Live 출력을 버리고 지시 뒤 응답만 내보내는 구조가 실제 공급자 형식에서도 성립한다.
- **보여 줄 것:** 기기 시뮬레이터로 판정 턴 동안 미확인 출력이 기기로 가지 않고 '생각 중'이 유지되는 끝-끝 시험. 실호출 확인표의 `[실호출-합성]` 결과.

### B4 — U15 지식 그래프 (지시서)

- **포함 단위:** U15(`u15-knowledge-graph`).
- **완료 기준**
  - Neo4j 어댑터와 메모리 대역(기본 씨앗 목록 채움)이 있다.
  - 공통 씨앗 적재 스크립트와 그래프 버전이 있다. 원천은 저장소 안 `knowledge/documents/social-comm-poc.zip`(Q5-1 A).
  - 씨앗 요약 · 구조 맥락 · 변형 계획 조회(2초)가 된다.
  - 새 씨앗 생성 · 검사(core V8 `generated_seed` · 그래프 버전 표)가 된다.
  - 삭제 참여자(순서 상수는 기존 값과 겹치지 않게)가 있다.
  - 꺼짐 · 장애 시 기본 경로로 간다.
  - US3.2 · US5.1 · US5.2 인수 시험이 통과한다. Neo4j 통합 시험은 개발용 인스턴스 또는 Testcontainers로 돈다.
- **확신 가설:** 그래프가 없거나 장애여도 core 기능이 안전하게 돌고, 있으면 child_id로 범위를 한정한 조회가 2초 안에 된다.
- **보여 줄 것:** 메모리 대역 · Neo4j 양쪽에서 같은 조회 시험이 통과하고, 그래프를 끈 상태의 기본 경로 시험이 통과한다.

### B5 — U13 제브 게이트 (지시서)

- **포함 단위:** U13(`u13-jev-guardrail`).
- **완료 기준**
  - 제브 포트 · 실제 어댑터 · 대역 · 비활성 모드가 있다.
  - demo · prod 기동 검사가 있다.
  - `guardrail_event`(realtime V6)가 있다.
  - B3의 게이트에 제브 단계를 얹었다. 새 발화 / 승인 원문 분류, 늦은 ALLOW 무효, 전사-PCM 일치가 된다.
  - 조원 하네스의 `service_policy.json` · 오프라인 사례 24개를 시험 계획의 참고로 쓴다.
  - US2.1(서버 동작) · US2.2 · US2.3 인수 시험이 통과한다. 제브 실호출 확인은 `[실호출-합성]`으로 따로 보고한다.
- **확신 가설:** 제브 검사를 넣어도 판정 턴 · 출력 세대 규칙과 엇갈리지 않고, 실패는 모두 막힘으로 처리된다.
- **보여 줄 것:** 가짜 제브로 ALLOW · BLOCK · 시간 초과 · 늦은 ALLOW · 원문 일치 생략 시험이 통과한다. 실제 제브 합성 호출 결과를 따로 보인다.

### B6 — U14 목표 · 결과 (지시서)

- **포함 단위:** U14(`u14-goal-result`).
- **완료 기준**
  - `PracticeGoal`이 후보 · 담은 목표를 소유한다(core V9). `CandidateRegistry` · `PracticeGoalRepository` · `CandidateScreen`을 옮긴다.
  - Luna 목표 후보 생성 · 검증 · 담기(후보 버전 고정)가 된다. 과거 행동 정의 목표도 읽힌다.
  - 결과 쪽:
    - `AFTER_REASK` 시점
    - 27 표시 접기 — 칩 매퍼를 저장 · 27 · 리포트로 나눈다(Domain Design R-01)
    - 장면 수 고정 해제
    - 새 기록 사실 입력(`flowVersion` 구분)
    - 재판정 `differs_from_live`
    - 재시도 3회
  - 삭제 참여를 넓혔다(Domain Design R-02).
  - Contract Design R-01(실제 `session-result-response.schema.json` 대조, 엄격 스키마 변경은 깨는 변경으로 분류) · R-09를 Functional Design 첫 작업으로 정리했다.
  - US4.1 ~ US4.6 인수 시험이 통과한다.
- **확신 가설:** 목표 데이터의 주인을 하나로 모아도(ADR-002) 과거 목표 · 결과가 원래 의미로 읽히고, 27 화면은 바뀌지 않는다.
- **보여 줄 것:** 과거 U5b 기록과 새 기록이 섞인 리포트 집계 시험, 27이 같은 칩 5종을 받는 API 시험, 후보 '없음' 경로 시험.

### B7 — U10 상황 생성 (지시서)

- **포함 단위:** U10(`u10-scenario-generation`).
- **완료 기준**
  - 생성 → 분리된 자체 검토 → 서버 검사 → 재생성을 15초 안에 한다.
  - 새 대본 필드(core V10)를 저장하고 `practice-scenario.schema.json`에 더했다. 레거시 필수 필드는 첫 BASE 장면에서 서버가 만들고, `partnerRole`은 모델 출력이 아니라 Core가 고정한다(Contract Design R-04).
  - 담은 목표 · 구조 맥락을 입력으로 쓰고, 변형 장면 보존 판정을 한다.
  - US3.1 인수 시험이 통과한다. 15초 실측은 `[실호출-합성]`으로 따로 보고한다.
- **확신 가설:** 자체 검토 요청을 생성과 분리해도 15초 예산 안에 승인 대본이 나오고, 반려 대본은 절대 발급되지 않는다.
- **보여 줄 것:** 가짜 Luna로 REJECT · UNCERTAIN · 스키마 실패 · 시간 초과가 발급되지 않는 시험, 레거시 U5b 경로가 같은 응답을 계속 읽는 시험.

### B8 — U12 새 연습 흐름 (지시서)

- **포함 단위:** U12(`u12-practice-conversation`).
- **완료 기준**
  - SCENE_INTRO ~ CLOSING 상태 기계와 J1~J5가 동작한다.
  - 친구 · 코치 역할 정책이 있다.
  - 도움 · 역할 전환 · 변형 · 생략 사유 기록(realtime V7)이 있다.
  - `practiceRecord`로 새 기록 사실을 넘긴다.
  - 레거시 포트가 새 흐름에서 떼어져 있다.
  - US1.1 ~ US1.6 · US1.9 인수 시험이 통과한다. US1.2 · US1.5는 B5(제브)가 합쳐진 뒤 끝-끝으로 확인한다.
- **확신 가설:** 되묻기로 해결되면 도움을 강제하지 않는 유연한 흐름이 레거시 판정 · 단서 포트 없이도 멈추지 않는다(AC1.1.5).
- **보여 줄 것:** 기기 시뮬레이터로 "그거 줘" → "어느 색?" → "파란색" 시나리오가 집중 도움 없이 '되묻기 뒤'로 기록되는 끝-끝 시험(AC1.2.3).

## 이 기록의 Construction 범위와 넘기기

- 이 기록은 **B1(U8) · B2(U9)만** Construction 한다(Q4-A · Q4-1 A).
- B3 ~ B8은 이 단계를 승인한 뒤 볼트마다 지시서(`docs/units/u10` ~ `u15/brief.md`)를 써서 담당자에게 넘긴다. 담당자는 자기 PC에서 새 작업 기록을 만든다(팀 방식, `docs/units/README.md`).
  - 지시서에는 다음을 적는다.
    - 「AI-DLC로 진행하는 법」(설명 문구 · 기록 이름 · 브랜치 · 뺄 단계 · 깊이와 시험 전략)
    - 이 기록의 인셉션 산출물 경로
    - 해당 단위의 받아들인 위험 목록
    - 마이그레이션 번호
  - 지시서는 `docs/` 브랜치에서 쓰고 PR로 합친다(team.md 브랜치 접두어 `docs/<주제>`).
- 이 기록의 Construction이 U10 ~ U15를 건너뛰게 하는 방법은 Construction에 들어갈 때 정한다(단위별 '건너뜀' 보고 또는 계획 조정). 건너뛴 단위를 완료로 표시하지 않는다.
- Build and Test(이 기록)는 B1 · B2 범위의 전체 검증(`bash scripts/verify.sh`)만 책임진다. 모든 볼트가 합쳐진 뒤의 끝-끝 확인과 실호출 · 실기기 확인(FR12)은 마지막 볼트 담당 기록 또는 별도 통합 작업 기록에서 한다(project.md 교정: 병합 뒤 통합 점검은 별도 작업 기록).

## Sources

- `../units-generation/unit-of-work.md`, `../units-generation/unit-of-work-dependency.md`, `../units-generation/unit-of-work-story-map.md`
- `../contract-design/contract-summary.md`, `../domain-design/components.md`, `../domain-design/decisions.md`
- `../requirements-analysis/requirements.md`, `../user-stories/stories.md`, `../refined-mockups/mockups.md`
- `../practices-discovery/team-practices.md`, `aidlc/spaces/default/memory/team.md` · `project.md`
- `docs/units/README.md`(팀 유닛 방식 · 병합 순서 · 삭제 참여자 순서 상수)
- 마이그레이션 현황: `backend/core-api/.../db/migration/core/`(V1 ~ V7) · `backend/realtime-activity/.../db/migration/realtime/`(V1 ~ V4)
- `delivery-planning-questions.md`

## Assumptions & Open Questions

- 볼트별 사람 담당자 이름은 지시서를 넘길 때 정한다(가정 7).
- 실기기 검증 담당 · 일정은 아직 없다(Q5에서 고르지 않음). FR12.3 확인표는 준비하되 실행 일정은 미정이다(`external-dependency-map.md`).
- 마이그레이션 번호는 각 단위 Functional Design에서 실제 필요 여부를 확인한 뒤 확정한다.
