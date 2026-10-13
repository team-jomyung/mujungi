# U5b 연습 · Realtime 쪽과 연결 마무리 (u5b-practice) 구현 지시

브랜치: main에서 `unit/u5b-practice`를 새로 만든다.

같은 시기에 U4b(활동 세션 Realtime 쪽, realtime V3)가 `unit/u4b-activity-realtime`에서 함께 진행된다. 작업 기록은 `aidlc/spaces/default/intents/261013-u4b-activity-realtime/`이다.

U4a(활동 Core · /parent, core V3)와 U5a(연습 Core, core V4)는 main에 합쳐졌다(`docs/units/README.md`).

## 배경
- U5a는 연습의 Core 쪽만 만들었다.
  - 목표 담기 · 18 화면 · 행동 정의 · 텍스트 AI 출입구 · C10 상황 생성
- 실제 연습 진행은 U5b 몫으로 남겼다(`docs/units/u5a/report.md` 「연결 경계와 후속 작업」).
  - 시작 · 가중 랜덤 순서 고정 · 단계 진행 · Cue · 판정 · 노출 반영
- U5b는 설계 문서가 아직 없다. **AI-DLC로 설계부터 한다**(아래 「AI-DLC로 진행하는 법」).

## 범위
Realtime 쪽(주)
- PracticeProtocol
  - D-05 흐름 전체: 상황 안내 → 첫 반응(Cue 금지) → 판정 → 도움 동의 → Cue → 도움 후 반응 1회 → 새 상황 → 다음 목표
  - 시작 때 가중 랜덤 순서를 고정한다.
  - 다음 목표 상황은 C10(`POST /internal/practice/scenarios`)으로 요청한다. 15초를 넘기면 그 목표는 미실시로 처리한다(J26).
- ResponseEvaluator
  - 5초 + 재시도 1회, 실패하면 `uncertain`
  - 무반응은 판정 없이 `NOT_PERFORMED`
- 확장
  - OutputGate: 단계별 출력 규칙
  - VoiceRelay: 상황 역할 · Cue 지시. Cue 브리프에 정답 문장 · 판정 기준 · 보호자 원문을 넣지 않는다.
  - 진행 조회의 `practice` 칸: goalIndex · goalCount · goalName · stage
- 결과 입력 스냅숏 `record.practice`(계약 6절)
  - goals · scenarios · responses · cues를 채운다.
  - 반응 · 도움마다 `scenarioId`를 싣는다. U6 기록용 재판정의 입력이다.
- realtime V4와 Realtime 쪽 연습 데이터 삭제 참가자

Core 쪽(연결 마무리)
- `PracticeStart` 구현 · 빈 등록(`backend/core-api/.../activity/PracticeStart.java`, U4a가 자리만 뒀다)과 `POST /api/activities/practice`
  - 첫 상황을 두 번 만들지 못하면 422 SCENARIO_GEN_FAILED
- 실제 어댑터: `PracticeSessionContextPort` · `ScenarioRecordPort`(`contracts/ports.md` 4절, 지금은 SERVICE_NOT_READY)
- `ActivityRecorded`를 받은 뒤 `reconcileExposures`로 실제 노출만 EXPOSED로 바꾼다.
- 화면
  - 18의 연습 시작 버튼을 켠다(U5a는 비활성으로 뒀다).
  - 12 연습 머리 n/N · 단계 표시

스토리: US4.1 · US4.2 · US6.5 중 U5a가 하지 않은 수용 기준(`docs/units/u5a/design.md` 2절 「후속 통합」 열).
완료 조건은 `bolt-plan.md` B5와 같다.

## 정본
기록 폴더 `R = aidlc/spaces/default/intents/261008-mujoong-service/`.

| 무엇 | 어디 |
|---|---|
| 범위 · 완료 조건 | `R/inception/units-generation/unit-of-work.md` U5 절, `R/inception/delivery-planning/bolt-plan.md` B5 |
| 요구사항 · 스토리 | `R/inception/requirements-analysis/requirements.md`(FR5 · FR7.6 · NFR1 · NFR3), `R/inception/user-stories/stories.md` US4.1 · US4.2 · US6.5 |
| 화면 | `R/inception/refined-mockups/mockups.md` S-12(연습 머리) · S-18 |
| 컴포넌트 | `R/inception/domain-design/components.md` PracticeProtocol · ResponseEvaluator · OutputGate · VoiceRelay |
| 계약 | `R/inception/contract-design/contract-summary.md` 5절 C8(시작 패키지 `practice`) · C10, 6절 SessionRecordCaptured `record.practice`, 7절 `practice.*` 시간 키, 9절 판정 입출력 · Cue 브리프 |
| 계약 파일 | `contracts/ports.md` 4절, `contracts/schemas/internal-practice-scenario-request.schema.json` · `practice-scenario.schema.json`, `contracts/timing.md` 2절(예약된 `practice.*` 키), `contracts/ai-io.md` |
| 앞 유닛 | `docs/units/u5a/design.md` · `report.md` · `review-1.md`, `docs/units/u4b/design.md` 1절 · 3절 · 6절 |

화면 설계 v1.5j가 최우선이다(`docs/units/README.md` 「규칙」).

## 정해 둔 값
- 마이그레이션
  - realtime 스키마에 `V4__u5b_practice.sql`을 둔다(더하기만).
    - 연습 표 다섯: realtime_session_goal · realtime_practice_attempt · response · cue · evaluation, 계약 9절 표
  - core 스키마를 바꿔야 하면 V7 이후로 잡는다. V5 = U6 · V6 = U7이다.
- 병합 순서
  - Flyway `outOfOrder`가 꺼져 있다. 그래서 **U4b(realtime V3)가 main에 들어간 뒤에** U5b를 합친다.
  - U4b보다 먼저 끝나면 PR을 열어 두고 기다린다.
- Core 삭제 순서: 연습 참가자는 이미 @Order 150이다. Realtime 쪽 연습 데이터는 U4b의 DeletionRequested 처리기 참가자 순서에 맞춘다(세션 행보다 먼저).
- 시험
  - 태그 `u5b`, 커버리지 범위 `backend/coverage-scope/u5b.txt`(U1 형식), 범위 줄 80% 이상
  - 프런트를 바꾸면 frontend 범위 파일도 만든다.
- 공급자: 실제 공급자는 연결하지 않는다. 판정 · Cue는 시험 소스의 가짜 공급자로 검증하고, 운영 기본값은 U5a처럼 미연결이다.
- 건드리지 않는 것
  - U1 · U3 · U4a · U5a 시험의 단언과 커버리지 하한
  - 다른 사람의 작업 기록(`261008-mujoong-service` · `261013-u4b-activity-realtime`)
  - `README.md` · `contracts/README.md`
- 문서: `docs/units/u5b/design.md`(새로), `docs/units/u5b/report.md`(새로)

## U4b와 맞물리는 곳 (먼저 맞출 것)
U5b는 U4b의 SessionLifecycle에 연습 경로를 붙이는 쪽이다(U5 → U4 확장). 그래서 두 사람이 겹치는 곳을 **처음 이틀 안에** 정해 둔다.
1. **연습 경로 확장점**
   - ACTIVE 안에서 연습 단계를 누가 진행하는지 정한다.
   - 목표 하나가 끝나면 다음 목표로 가고, 마지막 목표 뒤에는 CLOSING으로 간다. 이 신호를 어떤 인터페이스로 넘길지 정한다.
   - 정한 이름 · 메서드는 U4b 설계(`docs/units/u4b/design.md`)와 U5b 설계 양쪽에 같은 문장으로 적는다.
2. **참여 확인과 연습 안내**
   - U4b JoinTracker의 시작 안내가 PRACTICE일 때 `INTRO_PRACTICE`를 쓰는지 확인한다.
   - 그 뒤에 첫 상황 안내로 넘어가는 순서를 확인한다.
3. **결과 스냅숏**
   - U4b가 만드는 결과 입력 스냅숏에서 `record.practice` 칸을 U5b가 채운다.
   - FREE_TALK와 DECLINED이면 null 또는 빈 배열이다(계약 6절).
4. **중간 종료**
   - 연습 중 CHILD_STOP · SILENCE · DISCONNECT로 끝나면, 남은 목표를 NOT_REACHED로 정산한다.
   - 이 정산이 U4b 정산 순서의 어디에 들어가는지 정한다.

U4b가 합쳐지기 전에는 위에서 합의한 인터페이스의 대역으로 만든다.
- 대역은 계약 예시 값으로만 만들고, U4b 내부를 흉내 내지 않는다(`team-allocation.md`).
- U4b가 main에 들어오면 main을 합치고 대역을 실제 연결로 바꾼 뒤 통합 시험을 돌린다.

## AI-DLC로 진행하는 법
작업 기록은 사람마다 **자기 PC에서 새로** 만든다. 승인 증빙이 PC별이어서 남의 기록은 이어 갈 수 없다.
1. 저장소를 받고 `unit/u5b-practice` 브랜치로 옮긴 뒤 Claude Code에서 아래를 입력한다.

   ```
   /aidlc --new-intent --scope refactor "U5b 연습 Realtime 쪽과 연결 마무리(u5b-practice) 구현. 지시 docs/units/u5b/brief.md를 정본으로 한다. 인셉션 산출물은 261008-mujoong-service 기록의 것을 그대로 쓴다. 대화와 산출물은 한국어로 쓴다(코드 식별자·고정 토큰 제외)."
   ```

2. 안내대로 작업 기록을 만든다. 이름은 `u5b-practice`로 한다.
3. 배포 2단계를 뺀다. `/aidlc compose "deployment-pipeline과 deployment-execution을 빼줘"`를 입력하고, 확인 질문에 승인한다.
4. `/aidlc --depth Standard --test-strategy Standard`로 깊이와 시험 전략을 올린다.
5. 이렇게 하면 단계가 이렇게 남는다: 기존 코드 파악 → Requirements Analysis(U5b 몫만 추림) → Functional Design → Code Generation → Build and Test.
   - Requirements Analysis는 뒤 단계가 그 결과물을 필수로 받아서 뺄 수 없다.
   - Functional Design 결과는 `docs/units/u5b/design.md`에도 옮겨 둔다(다른 유닛과 같은 자리).
6. 자기 기록 폴더(`aidlc/spaces/default/intents/<날짜>-u5b-practice/`)는 브랜치에 함께 커밋한다.

## 먼저 할 것
1. 「U4b와 맞물리는 곳」 1 ~ 4를 U4b 담당과 맞춘다.
2. 판정 기준 본문 · 프롬프트 · 금지 표현 사전이 AI 입출력 계약(WBS 1.7.3, `contracts/ai-io.md`)에 어디까지 있는지 확인한다.
   - 비어 있는 것은 D 담당에게 묻는다.
   - 답을 기다리는 동안에는 가정(U5b-J)으로 두고 진행한다.
3. 층을 쌓는 순서. 시간이 모자라면 이 순서로 한다.
   - 먼저: V4와 저장소, PracticeProtocol 단계 진행, ResponseEvaluator, `record.practice`
   - 다음: Core PracticeStart · 실제 어댑터 · 노출 반영, 18 시작 · 12 머리
   - 뒤로: 중간 종료 정산, 문서
   - 층마다 시험을 쓰고 돌린 뒤 `feat(u5b): …`로 커밋한다.

## 시험 명령(저장소 루트 기준)
- 자기 유닛(모듈마다 따로 돌린다. 커버리지 범위 옵션을 두 모듈에 한꺼번에 걸면 설정 단계에서 실패한다, U5a brief 참고)
  - Realtime: `cd backend && ./gradlew --no-daemon :realtime-activity:test :realtime-activity:jacocoTestCoverageVerification -PincludeTags=u5b -PcoverageScope=u5b`
  - Core: `cd backend && ./gradlew --no-daemon :core-api:test -PincludeTags=u5b`
  - Core 쪽 줄이 많아지면 범위 파일을 `u5b-core.txt`로 나눈다. 그러면 `:core-api:jacocoTestCoverageVerification -PcoverageScope=u5b-core`도 돌린다.
- 회귀
  - `-PincludeTags=u1`(커버리지 검증 없이 :contract-model:test :core-api:test :realtime-activity:test :test-support:test)
  - `-PincludeTags=u3`(:realtime-activity:test만)
  - `-PincludeTags=u5a`(:core-api:test)
  - U4b가 들어온 뒤에는 `-PincludeTags=u4b`
- Windows에서는 리눅스 전용 검사(infra-validate · 이미지 관문 · 스켈레톤)를 돌릴 수 없다. PR을 열어 GitHub CI로 확인한다.

## 끝나면
`docs/units/u5b/report.md`에 다음을 쓰고 main으로 PR을 연다. U4b가 먼저 합쳐지고 CI가 초록인 것을 확인한 뒤 합친다.
- 만든 것, 시험 결과(건수 · 커버리지)
- 계약 편차, 가정(U5b-J), U4b와 합의한 확장점
- 남은 일(실제 공급자 연결, U6 넘김)
