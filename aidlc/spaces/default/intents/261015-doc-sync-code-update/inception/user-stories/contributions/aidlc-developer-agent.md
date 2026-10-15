**Collaborator:** aidlc-developer-agent

## Contribution

관점: 지금 코드(`main` `72d7034`)로 각 스토리를 한 조각씩 구현 · 시험할 수 있는지, 숨은 의존이 있는지, 지금 구조로는 문장 그대로 만족할 수 없는 인수 기준이 있는지 본다. 근거는 코드 위치를 함께 적는다.

### 1. 코드에서 확인한 사실 (스토리에 영향 주는 것만)

| # | 사실 | 위치 | 영향 받는 스토리 |
|---|---|---|---|
| F1 | 연습 모드에서는 `practice.controlsOutput()`이 참이어서 실행기가 아이 말 뒤에 `response.create`를 부르지 않는다. GPT-Live 출력은 `pendingPracticeSpeech.matches(transcript)`일 때만 게이트를 지나고, 그 밖의 출력은 모두 `discard`된다. 즉 지금 연습은 "GPT-Live가 대본을 그대로 읽는" 구조다. | `SessionExecutor` L999 · L2183~2187, `PracticeOutputPort.speak` L1405~1430 | US1.2 · US1.5 · US1.10 · US2.1 · US2.2 |
| F2 | 실행기는 세션당 단일 스레드 메일함이다. 비동기 결과는 `events.execute`/메일함 사건으로 다시 들어오고 세대(`generation` · `outputGeneration`)로 늦은 결과를 버린다. 판정 포트(`EvaluationPort.request` → `u5b-judged` 사건 → `judged()`)가 이미 이 방식이다. | `PracticeProtocol` L171~197 · L222, `RuntimePracticeExtension` L77 · L149 | US1.8 · US1.10 · US2.1 |
| F3 | `sealAndGate`는 `inspect` → `recheck`(오디오 예약) → `send`를 **같은 호출 안에서 동기로** 한다. 제브(800ms)를 그 사이에 넣으려면 봉인된 구간(`SegmentBuffer.Sealed`)을 들고 비동기 결과를 기다리는 새 상태가 필요하다. `Sealed`가 전사와 PCM을 함께 가지므로 AC2.1.10(같은 응답 · 구간)은 그 객체를 그대로 넘기면 구조적으로 지켜진다. | `SessionExecutor.sealAndGate` L2165~2206 | US2.1 |
| F4 | 출력 보류 장치가 이미 있다: `outputHeld()`(참여 확인 · 종료 확인 · `practiceHold`) 동안 시작된 공급자 응답은 `refuseHeldResponse`로 취소된다. 판정 턴 보류는 이 조건에 "판정 중"을 더하는 형태로 만들 수 있다. | `SessionExecutor` L1593~1603 · L1692 · L1703 | US1.10 |
| F5 | 종료 의도는 규칙 기반 `StopIntentJudge` + `StopConfirmFlow`(CLOSE · ASK · RESUME)로 이미 처리하고, 참여 확인은 `JoinTracker`(`WAITING_CHILD`)가 한다. J6 · J1을 새로 만들면 이 둘과 겹친다. | `dialog/StopIntentJudge.java` · `StopConfirmFlow.java` · `JoinTracker.java` | US1.1 · US1.7 |
| F6 | 도움 동의(J4 자리)는 지금 키워드 목록("응", "도와줘", "싫어" …)으로 정한다. | `RuntimePracticeExtension` L127~128 | US1.4 |
| F7 | 승인 대본 일치 비교 `PracticeSpeech.matches`는 **공백만 지우고** 비교한다. 글자 단위 완전 일치가 아니다. GPT-Live 출력 전사는 공급자 ASR 결과라 문장부호 · 띄어쓰기가 원문과 달라질 수 있다. | `voice/PracticeSpeech.java` | US2.2 |
| F8 | 목표 후보 · 담기 · 상황이 모두 `core_behavior_definition(behavior_key, version)`에 FK로 묶여 있다: `core_goal_recommendation`(FK, NOT NULL), `core_practice_goal_selection`(복합 FK), `core_scenario`(`behavior_key` NOT NULL). 실시간 앱 `SessionGoal`은 `judgmentCriteriaSnapshot`(비어 있으면 거절)을 받는다. | core `V4__u5a_practice_core.sql` L38~66 · L72~, `SessionGoal.java` L10~16 | US4.5 · US4.6 |
| F9 | 결과 표의 제약: `scene_no IN (1,2)`, `timing IN ('IMMEDIATE','AFTER_HELP')`, `attempt_count BETWEEN 0 AND 3`. `ResultAiSchema`의 `judgment`는 `sceneNo>2`를 거절하고, `candidates`는 `behaviorKey` · `behaviorVersion`을 요구한다. `SceneChipMapper`에는 `AFTER_REASK`가 없다. | core `V5__u6_results_report.sql` L51 · L54, `ResultAiSchema.java`, `SceneChipMapper.java` | US4.1 · US4.2 · US4.4 · US4.5 |
| F10 | 실시간 연습 표의 제약: `response_type IN ('FIRST','AFTER_CUE','NEW_SCENARIO')`, 라벨 `functional/partial/none/uncertain`, 도움 `attempts BETWEEN 0 AND 2`, `attempt_type IN ('ORIGINAL','NEW_SCENARIO')`. 새 단계(`REASK` · `FOCUS` · `VARIANT` …)와 J 선택지를 기록하려면 realtime V5가 필요하다. | realtime `V4__u5b_practice.sql` L18 · L30 · L59~60 | 묶음 1 전체 · US4.2 |
| F11 | Spring 프로필은 `dev` · `demo` · `prod`(그리고 기본)뿐이다. `test` · `ci` 프로필은 없다. 검증 스택은 `compose.demo.yml` 위에 `compose.ci.yml`이 `SPRING_PROFILES_ACTIVE: dev`로 덮어쓴다. 기동 거부는 이미 `ProfileGuardEnvironmentPostProcessor`가 하고, `scripts/verify/b-host.sh`가 `demo` 프로필 가드 사례를 돈다. | `startup/ProfileGuardEnvironmentPostProcessor.java`, `infra/compose.ci.yml` L53 · L61, `b-host.sh` L223~224 | US2.3 |
| F12 | 삭제 참여자는 core 9개 · realtime 5개(`*DeletionParticipant`)가 있다. 그래프 참여자를 더하는 구조는 그대로 쓸 수 있다. | `common/deletion/DeletionParticipant.java` 외 | US5.2 |
| F13 | 다음 마이그레이션 번호는 core V8 · realtime V5다(Flyway `outOfOrder` 꺼짐, R-M2). | team.md | 데이터 변경이 있는 모든 스토리 |

### 2. 지금 구조로는 문장 그대로 만족할 수 없는 인수 기준

1. **US1.2 · US1.5에 US2.1 의존이 숨어 있다 (F1).** 친구 역할의 자연스러운 되묻기("어느 색?")와 코치의 즉석 표현 예시는 대본이 아니라 GPT-Live가 **새로 만든 발화**다. 지금 연습 경로는 대본과 맞지 않는 출력을 모두 버리므로, 이 발화를 들려주려면 FR6.3의 `PRACTICE_PEER` · `PRACTICE_COACH` 제브 검사(US2.1)가 먼저 있어야 한다. 의존 목록에 "US2.1 → US1.2 · US1.5"를 더하자는 제안이다. 그러지 않으면 US1.2를 끝내도 demo에서 되묻기가 들리지 않거나, 검사 없이 들리게 된다(NFR2 위반).
2. **AC1.10.1의 "GPT-Live 자동 응답"이라는 표현 (F1 · F4).** 연습 모드에서는 서버가 응답 생성을 요청하지 않으므로 "자동 응답"은 공급자가 스스로 시작한 응답이나 이전 세대의 늦은 응답뿐이다. 다음처럼 바꾸면 시험할 수 있다: "판정 턴 동안 도착한 공급자 출력(스스로 시작한 응답 · 이전 세대 응답 포함)은 기기로 보내지 않는다." 자유대화(J1 · J6, FR4.8)는 서버가 `response.create`를 부르는 경로가 따로 있어서, 보류 지점이 두 군데라는 점도 AC에 드러나야 한다.
3. **AC1.10.2 "지시 뒤의 응답만"은 지금 선 형식에서 가짜 공급자로만 확인된다.** `speak()`는 세대를 올리고 `response.cancel(null)` → `instructions.correct` → `response.create` 순서로 보낸다. 지시 전에 시작됐지만 아직 `responseId`를 받지 못한 응답을 확실히 끊는지는 실제 공급자 동작에 달려 있다(요구사항 열린 항목 5). 이 AC는 "가짜 공급자 기준 통과, 실제 선 형식 확인은 FR12.2 증거"로 증거 구분을 적어야 R-M11에 맞는다.
4. **US1.1(J1) · US1.7(J6)이 기존 장치와 겹친다 (F5).** AC1.1.x가 `JoinTracker`의 참여 확인을 **대신하는지**, 그 **뒤에 붙는지** 정해야 한다. AC1.7.1 · AC1.7.3은 `StopIntentJudge`의 CLOSE · ASK와 사실상 같은 동작이다. J6 Luna 판정은 규칙 판정이 "후보"라고 본 경우에만 부르는 형태(규칙 판정 우선 → 애매할 때만 Luna)로 적으면 기존 시험(U4b 종료 확인)을 깨지 않는다.
5. **AC2.1.2 ~ AC2.1.7은 CI에서 합격/불합격이 나지 않는다.** 이 AC들은 "제브가 이 문장을 막는가"라는 **실모델 분류 품질**이다. 시험 · CI는 가짜 공급자만 쓰므로(R-F1), CI에서 확인할 수 있는 것은 "제브가 `BLOCK`/`REVIEW`를 돌려주면 재생하지 않는다"뿐이다. 두 층으로 나누자: (a) 서버 동작 AC — 가짜 제브 응답별 재생 여부(CI), (b) 분류 품질 AC — C §8.1 사례 목록을 FR12.1 합성 실호출로 확인(실제 API 증거). AC3.1.8의 "의미상 반복", AC3.1.7의 "관심사 반영"도 같은 이유로 서버가 확인할 수 있는 부분(지문 중복 · 필드 존재)과 실모델 평가 부분을 나눠야 한다.
6. **AC2.2.1 "글자까지 같으면" (F7).** 지금 비교는 공백만 무시한다. 또 GPT-Live가 대본을 읽은 출력의 전사는 문장부호 · 띄어쓰기 · 숫자 표기가 달라지기 쉬워, 엄격히 같게 하면 대부분 "새 발화"로 빠져 제브 호출과 지연이 늘어난다. AC에 **정규화 규칙(공백 · 문장부호까지 무시할지)**을 명시하자. 어느 쪽이든 단어 추가 · 삭제 · 변경은 새 발화라는 AC2.2.2는 유지된다. 참고로 승인 문구(`ApprovedPlayback`)는 녹음된 PCM이라 GPT-Live를 거치지 않으므로, 제브 생략 대상은 실질적으로 "세션 고정 대본을 GPT-Live가 읽은 것" 하나다.
7. **AC2.3.1 · AC2.3.2의 프로필 이름 (F11).** `test` · `ci` 프로필은 없다. 검증 스택과 CI는 `dev`로 돈다. AC를 "`dev` 프로필(검증 스택 · CI 포함)과 시험 컨텍스트"로 고치거나, 새 프로필을 만들 거라면 그 일을 스토리에 넣어야 한다. 또 `b-host.sh`의 `demo` 가드 사례와 이미지 점검에서 `demo` 프로필로 기동하는 경로가 있으면 제브 키 없이 기동 거부가 나므로, 그 사례들이 가짜 키(이름만, R-F3)나 기대 실패로 바뀌어야 한다. 이것은 `verify.sh` · `infra/ci/image-checks.sh`를 건드리는 일이고 R-M9(새 비밀 이름 목록 전부)와 같은 커밋이 된다.
8. **AC5.2.2 "그래프가 꺼져 있을 때 완료로 표시하지 않는다".** 문장 그대로면 그래프를 끈 환경(dev · CI · 지금의 demo)에서는 **모든 아동 · 계정 삭제가 영원히 끝나지 않는다.** 기존 삭제 시험도 깨진다. "그래프가 설정돼 있는데 연결 실패 · 시간 초과면 완료로 표시하지 않고 재시도한다"와 "그래프 기능이 꺼져 있고 그 환경에서 그래프에 쓴 적이 없으면 그래프 참여자는 할 일 없음으로 완료한다"를 나눠야 한다. 켰다가 끈 환경(이미 쓴 자료가 있을 수 있음)을 어떻게 볼지는 Functional Design 질문으로 남긴다.
9. **AC4.6.1 · AC4.6.3은 데이터 모델 변경 없이 만족할 수 없다 (F8).** Luna가 만든 후보는 `core_behavior_definition` 행이 아니므로 지금 FK 구조에 들어가지 않는다. 길은 둘이다: (a) 후보마다 행동 정의 행을 만들어 기존 FK를 그대로 쓴다(빠르지만 "승인 행동 정의"의 의미가 흐려지고 `ACTIVE` 제약 · 불변 트리거와 부딪힌다), (b) 새 후보 표(4요소 · `skill_ref` · `kg_version` · 근거)를 두고 담기 · 상황 표의 `behavior_key`를 "행동 정의 또는 후보" 중 하나를 가리키게 넓힌다(core V8, NOT NULL 완화 · CHECK 추가 → R-M3 승인 줄). 또 AC4.6.3(판정 기준이 4요소에서 나옴)은 core → realtime 세션 계획의 `judgmentCriteriaSnapshot`을 4요소에서 만드는 **앱 사이 계약 변경**(`contracts/`, R-M10)이 따른다. 스토리 문장은 좋지만 크기 표시와 분할이 필요하다(아래 3절).
10. **AC4.5.3 · US3.2는 그래프 자료에 기댄다.** 그래프가 꺼져 있으면 씨앗 요약 목록이 비어 모든 후보가 `skill_ref=NONE`이 된다. 그래서 US4.5는 P4 없이도 끝낼 수 있지만, AC4.5.3의 "목록에 있는 id" 경로는 메모리 대역(FR9.7)이 있어야 시험된다. 의존 목록에 "US4.5 AC4.5.3 → FR9.7 메모리 대역"을 적자.
11. **AC4.4.1 "추가 최대 3회"와 `attempt_count BETWEEN 0 AND 3` (F9).** 첫 시도 + 추가 3회 = 4회다. `attempt_count`가 무엇을 세는지(전체 시도 / 재시도)에 따라 core V8에서 CHECK를 넓혀야 할 수 있다. 설정만 고치는 S가 아닐 수 있다는 점을 적어 둔다.
12. **US1.3 · US1.5 · US1.6이 US3.1 AC3.1.6에 숨은 의존을 가진다.** "상황 다시 설명", "집중 연습 요점 · 표현 예시", "변형 장면"은 모두 상황 생성 결과에 새로 생기는 필드다. 이 필드가 core_scenario에 저장되고(core V8) 실시간 앱의 `PracticePorts.Scene`(지금은 상황 · 역할 · 트리거 3개)까지 계약으로 전달돼야 묶음 1이 쓸 수 있다. 의존 목록에 "US3.1(AC3.1.6) → US1.3 · US1.5 · US1.6"을 더하자.

### 3. 너무 큰 스토리와 나누는 제안

| 스토리 | 왜 큰가 | 나누는 제안 |
|---|---|---|
| 묶음 1 전체(US1.1~1.10) | 모두 `PracticeProtocol` 상태 기계 교체(FR4.1) + realtime V5(F10) + 새 `JudgeClient` 포트를 공유한다. INVEST 메모대로 "판정 지점별"로 나누려면 공통 기반 조각이 먼저 필요하다. | **기반 조각**(스토리 아님, Construction 단계): `JudgeClient` 포트 + 가짜 Luna 판정 + 판정 턴 보류 + `judgment_live` + 2초 제한 · 보류 규칙 = US1.10 + US1.8. 그다음 J2/J3 경로(US1.2 · US1.3), 도움(US1.4 · US1.5), 무응답(US1.9), J1(US1.1), J6 · 자유대화(US1.7), 변형(US1.6) 순으로 한 조각씩. US1.7의 자유대화 J6은 `PracticeProtocol`이 아니라 실행기 · `StopConfirmFlow` 쪽이라 별도 조각이다. |
| US2.1(12개 AC) | 포트 · 실제 어댑터 · 가짜 제브 · 게이트 비동기 단계(F3) · 늦은 결과 · 계측 · 실모델 분류 품질이 한 스토리에 있다. | (a) 게이트에 제브 단계 넣기: 가짜 제브로 ALLOW만 재생, 실패 6종은 막기(AC 1 · 8 · 10 · 11), (b) 늦은 `ALLOW` · 취소 · 세대(AC9), (c) `guardrail_event` · 계측(AC12), (d) 분류 품질(AC 2~7)은 FR12.1 실호출 확인표로. 리드의 "검사 연결 / 실패 · 늦은 결과" 둘 분할보다 (c) · (d)를 떼는 쪽이 시험 경계가 분명하다. |
| US4.5 · US4.6 | 후보 생성 스키마 · 새 표 · 담기 FK · 상황 입력 · 실시간 계획 계약 · 과거 호환이 함께 있다(F8). | US4.5a 후보 생성 · 검증 · 저장(`ResultAiSchema candidates` 교체, core V8 새 표), US4.5b 보호자 화면(화면 설계 v1.5j 27 범위 확인 뒤), US4.6a 담기가 후보 버전을 가리킴(+ 과거 담기 호환 AC4.6.4), US4.6b 상황 생성 입력 · 판정 기준 전달(앱 사이 계약 변경). |
| US4.1 · US4.2 | 칩 하나처럼 보이지만 core V8 CHECK 완화 · `ResultAiSchema judgment` · `SceneChipMapper` · 프런트 `SceneChip.tsx` · 계약을 함께 고치고, 입력 사실은 묶음 1의 기록에 기댄다. | 결과 쪽(칩 · 스키마 · 마이그레이션)은 묶음 1과 독립으로 먼저 할 수 있다(합성 기록으로 시험). AC4.2.3(새 기록 사실을 U6 입력으로)은 묶음 1 기록 형식이 정해진 뒤 별도 조각. |
| US3.1 | 별도 자체 검토 요청 · 재생성 · 15초 예산 · 대본 필드 확장(AC3.1.6) · 계약 전달이 함께 있다. | US3.1a 생성/자체 검토 분리와 반려 처리(AC 1~5 · 9), US3.1b 대본 필드 확장과 실시간 전달(AC3.1.6, 묶음 1의 선행), AC3.1.7 · 3.1.8의 의미 판단 부분은 실호출 평가로. |

### 4. 그 밖의 구현 메모

- **시간 값 위치:** 2초 판정 · 10초 응답 준비 · 800ms 제브 · 2초 그래프 · 목표당 10분 · 집중 연습 90초는 `mujoong.timing.*` 키로 두고 R-M5의 세 곳을 같은 커밋에서 고친다. 확률 하한 0.6 · 차이 0.15 · 최근 3턴 · 시도 2회처럼 시간이 아닌 [기본값]은 다른 설정 네임스페이스에 둔다. 스토리 머리말의 "[기본값]은 설정만 고친다"에 이 구분을 한 줄 더하면 좋다.
- **ACTIVE 시간(AC1.6.1 · AC1.6.4):** 실행기가 이미 일시 정지 구간(`practicePauses`)을 관리하므로 목표별 ACTIVE 시간 계산은 그 구간을 빼는 방식으로 만들 수 있다.
- **마이그레이션 번호:** 새 표 · 제약 변경이 core V8(후보 · 칩 · 재시도 · 대본 필드)과 realtime V5(`judgment_live` · `guardrail_event` · 연습 CHECK 완화)에 몰린다. 조각을 나누면 번호를 조각 순서대로 미리 정해 둬야 한다(R-M2). 이미 `main`에 들어간 번호는 고치지 않는다. Delivery Planning 입력으로 넘긴다.
- **AC1.2.5 · FR3.7:** 지시문 생성기(`PracticeInstruction`) 단위 시험으로 바로 확인할 수 있다. 좋은 AC다.
- **AC2.1.11 · AC3.1.9(요청 필드 제한):** `ProviderRequestWhitelistIT`와 같은 허용 목록 시험 패턴이 이미 있으므로 제브 · Luna 요청에도 같은 방식을 쓰면 된다.

## Positions

- AGREE: 페르소나를 아이 · 보호자 둘로 두고 운영 요구를 N/A로 추적하는 것(Q1-B) — 코드 쪽 운영 일(FR1 · FR10~12)은 Construction 단계로 다루는 편이 조각 경계가 분명하다.
- AGREE: C §8 항목을 AC로 1:1 옮기고 [기본값]을 적은 것 — 시험 이름과 바로 이어져 Build and Test 계획이 쉬워진다.
- AGREE: AC1.2.5(지시문에 판정 라벨 · 확률 금지) — 지시문 생성기 단위 시험으로 합격/불합격이 바로 난다.
- OBJECT: 「의존과 순서」에 숨은 의존이 빠져 있다 — US2.1 → US1.2 · US1.5(새 발화는 제브 없이는 연습 경로에서 버려짐, F1), US3.1 AC3.1.6 → US1.3 · US1.5 · US1.6(대본 필드), FR9.7 메모리 대역 → AC4.5.3을 더해야 한다.
- OBJECT: AC2.1.2 ~ AC2.1.7을 지금 문장 그대로 두는 것 — 실모델 분류 품질이라 가짜 공급자만 쓰는 CI에서 판정되지 않는다. "가짜 제브 결과별 서버 동작"과 "FR12.1 실호출 확인표"로 나눠야 R-F1 · R-M11과 맞는다.
- OBJECT: AC5.2.2 "그래프가 꺼져 있으면 완료로 표시하지 않는다" — 그래프를 끈 dev · CI · demo에서 모든 삭제가 끝나지 않게 된다. "설정됐는데 실패"와 "꺼져 있고 쓴 적 없음"을 나눠야 한다.
- OBJECT: AC2.3.2의 `test` · `ci` 프로필 — 그런 프로필이 없다(F11). `dev` 프로필 · 시험 컨텍스트로 고치고, `demo` 프로필로 기동하는 기존 가드 사례 · 이미지 점검이 받는 영향을 적어야 한다.
- OBJECT: US4.6을 한 스토리로 두는 것 — 후보 데이터 모델 변경(FK 구조, core V8)과 core → realtime 계획 계약 변경이 함께 있어 한 조각으로 끝낼 수 없다. US4.5a/b · US4.6a/b로 나누자.
- OBJECT: AC2.2.1 "글자까지 같으면"을 정규화 규칙 없이 두는 것 — 지금 코드는 공백만 무시하고(F7), ASR 전사의 문장부호 차이 때문에 엄격 일치는 대부분 새 발화로 빠진다. 정규화 범위를 AC에 명시해야 한다.
- OBJECT: AC1.10.1의 "GPT-Live 자동 응답" 표현 — 연습 모드는 서버가 응답 생성을 요청하지 않는다(F1). "판정 턴 동안 도착한 모든 공급자 출력"으로 바꾸고, 실제 선 형식의 취소 동작은 FR12.2 증거로 구분해야 한다.
- AGREE: INVEST 메모의 US2.1 분할 방향 — 다만 계측(AC12)과 분류 품질(AC2~7)을 따로 떼는 4분할을 제안한다(3절).
