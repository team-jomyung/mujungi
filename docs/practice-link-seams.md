# 연습 연결부 결함 3건 + 교차 계약 시험 (practice-seams)

> 작업 묶음 2(core ↔ realtime 연결부) 결과 기록이다. 브랜치 `claude/practice-seams`(기준 `origin/main` fec5285).
> 사람 대신 모델이 지시서(작업 묶음 2)대로 구현했다. 사람이 정할 일은 아래 '사람이 할 일'에 남겼다.
> 이 브랜치는 실제 AI 공급자 · 실제 기기를 부르지 않았다. 시험 결과는 모두 테스트 대역(가짜 공급자) · 합성 자료 증거다(R-M11).

## 1. 바꾼 동작 한눈에

| 결함 | 전 | 후 | 앱 |
|---|---|---|---|
| (a) 노출 정산 | 새 흐름 기록(`flowVersion=practice-conversation-v1`)을 읽지 못해 정산이 늘 실패했다(`PracticeExposureReconciliationFailed` 되풀이, 다음 대본의 노출 이력이 빔) | `scenes[].scenarioRef` · `startedAt`을 읽어 대본마다 가장 이른 시작 시각으로 정산한다 | core |
| (b) `partnerRole` | core가 장면 `partnerRole`에 지시문을 넣고 realtime은 그것을 역할 이름으로 써서 "너는 아이의 제시된 상황의 … 역할이다"가 나갔다 | core는 `relation`에서 짧은 역할 이름(20자 이하, 대체값 '친구')을 만들고, 금지 규칙은 realtime 고정 지시가 맡는다. realtime은 옛 지시문 값을 '친구'로 바꾸는 방어를 둔다 | core · realtime |
| (c) 기회 문장 이중 재생 | 도입(장면 설명, 기회 문장 포함)을 재생한 뒤 OPPORTUNITY에서 기회 문장을 또 재생했다 | 설명이 기회 문장을 담고 끝까지 재생됐으면 첫 OPPORTUNITY 진입에서 다시 재생하지 않고 바로 아이 말을 기다린다 | realtime |
| (d) 교차 시험 | 두 앱이 각자 손으로 쓴 시험 자료만 썼다 | `contracts/fixtures/` 공유 고정 자료 2개를 contract-model · core · realtime 시험이 함께 읽는다 | 세 모듈 |

## 2. 고른 방안과 까닭

### 2.1 (c) 방법 A2 — 설명 전체 재생 + 기회 문장 재생 생략 (지시서 방법 A의 변형)

- 지시서 방법 A는 도입에서 기회 문장 부분을 **잘라 낸 문자열**을 승인 발화로 재생한다. 이 문자열은 core가 발급한 승인 원문이 아니다.
  연습이 출력을 쥔 동안 실행부는 출력 전사가 `speakApproved`에 넘긴 문자열과 원문 일치하면 제브를 건너뛴다(U13 분류 ①, ai-io.md 10.5).
  잘린 문자열을 넘기면 '승인 원문의 부분 문자열(앞 · 뒤 이어 붙임)도 승인 발화'로 읽는 셈이라 제브 생략 근거가 넓어진다.
- 그래서 이 브랜치는 그 해석을 택하지 않았다(PRACTICE-SEAMS-A1). 대신:
  - 도입은 지금처럼 **장면 설명 원문 그대로** 재생한다. `speakApproved`에는 core가 발급한 문장(설명 · 기회 문장 · 다시 설명)만 들어간다.
  - 설명이 기회 문장을 담고 있고(정규화 비교) 도입이 **끝까지 재생**(PLAYED)됐으면, 도입 직후의 OPPORTUNITY 진입에서 기회 문장을 다시 재생하지 않고 곧바로 입력 대기(무응답 시계)로 간다.
  - 도입이 끊겼거나(INTERRUPTED) 설명에 기회 문장이 없으면 지금처럼 기회 문장을 따로 재생한다(PRACTICE-SEAMS-A2).
  - 무응답 재안내(`reguide`)와 상황 다시 설명 뒤의 OPPORTUNITY 재진입은 지금처럼 기회 문장을 재생한다(의도된 동작).
- 결과: `PracticeSpeech.matches` 규칙(ASCII 공백만 빼고 구두점까지 같음)과 제브 생략 판단은 한 글자도 바뀌지 않는다. core 규칙('설명에 기회 문장 포함')도 그대로다.
- 비용: 기회 문장이 설명 가운데 있으면 아이가 마지막으로 들은 말이 기회 문장이 아닐 수 있다(PRACTICE-SEAMS-A3). 실호출 리허설에서 본다.

### 2.2 (b) 라벨 표 (지시서 초안 그대로, PRACTICE-SEAMS-A4)

| relation | partnerRole |
|---|---|
| PEER · FRIEND_CASUAL | 친구 |
| FRIEND_CLOSE | 친한 친구 |
| CLASSMATE | 같은 반 친구 |
| CLUB_PEER | 동아리 친구 |
| CLUB_SENIOR | 동아리 선배 |
| CLUB_JUNIOR | 동아리 후배 |
| TEAMMATE | 같은 팀 친구 |
| TEACHER | 선생님 |
| FAMILY | 가족 |
| 그 밖의 `^[A-Z_]+$` 코드 | 친구 |
| 한국어 자유 문구(후보 목표) | 앞뒤 공백을 지우고 연속 공백을 하나로 줄인 뒤 20자(코드 포인트) 이하이고 `.?!。` · 제어 문자가 없으면 그대로, 아니면 '친구' |

- realtime `PEER_RULE` 끝에 "아이 대신 대답하지 않는다."를 덧붙이기만 했다. 기존 문장은 그대로다.
- 레거시 `partnerRoleText`와 `ScenarioContentPolicy.PARTNER_ROLE` 상수는 바꾸지 않았다.

### 2.3 (a) 정산 규칙

- `practice.flowVersion`이 있으면 새 흐름 분기, 값이 `practice-conversation-v1`이 아니면 실패(모르는 판은 정산하지 않음).
- `scenes`가 배열이 아니거나 장면이 객체가 아니면 실패. `scenarioRef`가 없거나 null이면 건너뜀, UUID가 아니면 실패. `startedAt`이 비면 건너뜀, 시각으로 안 읽히면 실패. 둘 중 하나라도 비면 그 장면은 형식 검사 없이 건너뛴다(PRACTICE-SEAMS-A12).
- 같은 `scenarioRef`는 가장 이른 `startedAt` 하나만 남기고 시각 순으로 낸다. 마이그레이션은 없다. 배포 뒤 다음 주기 `runOnce`가 미정산 준비를 다시 정산한다(사실상 백필).

## 3. 설계 편차

| 번호 | 내용 | 까닭 |
|---|---|---|
| 지시서 7절 방법 A → A2 | 잘린 도입 문자열을 승인 발화로 내지 않고, 설명 전체를 재생한 뒤 기회 문장 재생을 생략한다 | 승인 발화 판정(원문 일치 → 제브 생략) 근거를 넓히지 않는다 |
| 지시서 8절 realtime 진행 2 · 3단계 | '첫 슬롯 = 잘린 도입, 둘째 슬롯 = 기회 문장' 대신 '첫 슬롯 = 설명 원문, 재생 완료 뒤 둘째 슬롯 없이 입력 대기'를 단언한다 | 위와 같다 |
| 지시서 7절 `SceneIntroTest` 사례 | 잘라 내기 사례 대신 '설명이 기회 문장을 담는가' 판정 사례(끝 · 가운데 · 따옴표 · 공백 · 문장부호 차이 · 미포함 · 빈 정규화)를 둔다 | 위와 같다 |
| 지시서 7절 `PracticeSpeechMatchTest` 사례 | '잘린 도입 vs 공백만 다른 전사 → 참' 대신 '설명 원문 vs 공백만 다른 전사 → 참, 기회 문장을 뺀 앞부분 전사 → 거짓'을 고정한다 | 부분 문자열이 승인 발화가 아님을 시험으로 못 박는다 |

## 4. 가정 목록

- **PRACTICE-SEAMS-A1** 기회 문장 이중 재생은 방법 A(realtime만)로 고치되 승인 원문 부분 문자열을 승인 발화로 읽는 해석은 택하지 않는다(2.1). 지시서 0.5절 마지막 항목의 답은 '택하지 않음'이다.
- **PRACTICE-SEAMS-A2** 도입이 끊긴(INTERRUPTED) 경우 아이가 기회 문장을 못 들었을 수 있으므로 기회 문장을 따로 재생한다.
- **PRACTICE-SEAMS-A3** 기회 문장이 설명의 어디에 있든(끝 · 가운데) 한 번 들었으면 다시 재생하지 않는다. 위치는 검사하지 않는다.
- **PRACTICE-SEAMS-A4** `partnerRole` 라벨 표 · 상한 20자(코드 포인트) · 대체값 '친구'는 지시서 초안 그대로 정했다. 후보 목표 자유 문구의 '공백 정리'는 앞뒤 공백 제거 + 연속 공백(ASCII 공백류)을 한 칸으로 줄이기다.
- **PRACTICE-SEAMS-A5** `practice-scenario.schema.json`의 `scenes[].partnerRole`에 `maxLength: 20`을 넣는다(제약 강화). core 출력은 라벨 규칙으로 늘 20자 이하이고, realtime은 런타임에 이 스키마로 검사하지 않으므로 배포 전에 저장된 옛 값(지시문)이 거절되지 않는다. 옛 값은 realtime 방어가 '친구'로 바꾼다.
- **PRACTICE-SEAMS-A6** PR 나누기: 이 브랜치 하나에 커밋을 지시서 10절의 PR-1(계약) · PR-2(core) · PR-3(realtime) 경계로 나눠 두었다(9절 커밋 표). 한 PR로 올리면 (b)의 '두 앱 같은 날 병합'이 저절로 지켜진다. 셋으로 나눌지는 사람이 정한다. 다만 `partnerRole` `maxLength: 20` 제약은 계약 커밋이 아니라 core 커밋에 넣었다. 계약 커밋만 먼저 병합하면 지시문(39자)을 싣는 기존 core 출력이 스키마 대조 시험(`PlannedGenerationIT` 등)에서 깨질 수 있기 때문이다(정적 판단, 커밋별로 따로 돌려 보지는 않았다). 교차 시험 커밋은 core · realtime 커밋 둘 다에 기댄다.
- **PRACTICE-SEAMS-A7** 공유 고정 자료 1의 order 1 목표는 후보 목표(데모 경로), order 2 목표는 기존 행동 목표다. BASE `relation`은 후보의 상대 문구 '친한 친구', VARIANT `relation`은 관계 코드 `CLASSMATE`로 두어 라벨 표의 두 갈래(자유 문구 · 코드)를 함께 지난다.
- **PRACTICE-SEAMS-A8** `ScenarioRepository.save`의 `Scenario` 조립을 순수 정적 팩토리 `ScenarioRepository.issued(...)`로 빼서 저장과 교차 시험이 같이 쓴다(지시서 8절). 운영 코드 변경이라 도연 확인이 필요하다.
- **PRACTICE-SEAMS-A9** 서식(team.md Code Style Q7-A): 작은 파일(`RecordedScenarioExposures`)은 팀 서식으로 다시 썼고, 큰 압축 파일(`ScenarioScriptGenerator` · `ScenarioRepository`)은 바꾼 부분만 팀 서식으로 쓰고 나머지는 두었다. 같은 날 병합해야 하는 변경의 검토 부담을 줄이려는 것이다.
- **PRACTICE-SEAMS-A10** realtime 이름 방어(`RolePolicy.peerName`)는 지시문 · 승인 발화 말하는 사람 표시 두 곳에서 쓰고, 읽기 경계(`PracticeScriptReader`)는 바꾸지 않았다. 대본 값 자체는 사실대로 두고 쓰는 곳에서만 접는다.
- **PRACTICE-SEAMS-A12** 새 흐름 장면에서 `scenarioRef`나 `startedAt` 중 하나라도 비면 다른 칸의 형식과 관계없이 그 장면을 건너뛴다. 정산할 수 없는 장면 하나 때문에 기록 전체 정산을 막지 않으려는 것이다. 두 칸이 다 있는데 형식이 틀리면 지금처럼 실패다.
- **PRACTICE-SEAMS-A11** 지시서 0.5절의 나머지(디스코드 공지 수정 · 범위 밖 발견 담당 · 데모 DB 초기화)는 사람 몫이라 정하지 않고 '사람이 할 일'에 권장안만 적었다.

## 5. 원 담당자와 맞출 것

- **성민(realtime)**: 방법 A2(설명 전체 재생 + 기회 문장 재생 생략)가 U12 흐름 의도에 맞는가. 잘린 도입을 원하면 '승인 원문 부분 문자열 = 승인 발화' 해석을 U13이 먼저 승인해야 한다(열린 질문). `PEER_RULE` 덧붙임 · `RolePolicy.peerName` 방어.
- **도연(core)**: 라벨 표 · 상한 · 대체값, `ScenarioRepository.issued` 추출, `RecordedScenarioExposures` 새 흐름 분기, `docs/units/u10/design.md` 한 줄 수정.

## 6. 사람이 할 일

- 디스코드로 성민 · 도연에게 "이 셋((a) 노출 정산 · (b) partnerRole · (c) 기회 문장)은 준하가 한다"고 알리고, 이미 손댄 로컬 작업이 있는지 묻는다. 앞선 공지 초안의 담당 문구를 고칠지 정한다.
- 5절 확인을 받는다. 특히 방법 A2와 라벨 표.
- PR을 연다(한 PR 또는 셋, PRACTICE-SEAMS-A6). 리뷰어: core 부분 도연, realtime 부분 성민, 계약은 둘 다. (b) 때문에 core · realtime은 같은 날 병합한다.
- 범위 밖 발견 담당 지정(권장: 가상 상황 고지는 도연 · 성민 확인, VARIANT 계획은 도연의 변형 어휘 작업).
- 데모 DB에 남은 옛 READY 준비 패키지 초기화 여부를 정한다(권장: 초기화. realtime 방어로 동작은 한다).
- 실키 리허설(R-F15 확인 기록 뒤)에서 자유대화 → 연습 → 다음 경험 한 바퀴로 (a) 정산 · (b) 역할 이름 · (c) 재생 횟수를 실제로 본다. 이 브랜치는 실제 AI를 부르지 않았다.

## 7. 범위 밖 발견 (보고만)

- 새 흐름 도입에 장면별 가상 상황 고지가 없다(`childText`는 레거시 `scenarioText`에만 붙는다). `docs/units/u10/design.md`는 '가상 장면 고지도 기존 정책을 유지한다'고 적었다.
- VARIANT 계획이 구조적으로 나오기 어렵다(BASE `relation`이 `PEER` 또는 한국어 문구인데 `VariantPlanner.plan`은 관계 코드만 받는다). 정적 추정이며 실행 확인은 안 했다.
- 배포 전에 만든 READY 준비 패키지에는 옛 지시문 `partnerRole`이 남아 있다(realtime 방어로 '친구'가 된다).

## 8. 바꾼 파일

| 앱 | 파일 |
|---|---|
| 계약 | `contracts/fixtures/practice-start-package.json`(새) · `contracts/fixtures/practice-record-v2.json`(새) · `contracts/schemas/practice-scenario.schema.json` · `contracts/schemas/practice-record-v2.schema.json` · `contracts/ai-io.md`(7절 한 문장, 10.15 새 절) · `contracts/deviations.md`((U12-b) ~ (U12-d)) · `contracts/README.md` |
| contract-model 시험 | `ContractSchemasTest`(고정 자료 2개 스키마 통과 · partnerRole 길이 제약) |
| core 운영 | `practice/ScenarioContentPolicy`(`partnerRoleName` · 라벨 표) · `practice/ScenarioScriptGenerator`(장면 partnerRole = 역할 이름) · `practice/ScenarioRepository`(`issued` 정적 팩토리, 동작 무변경) · `practice/start/RecordedScenarioExposures`(새 흐름 분기, 팀 서식으로 다시 씀) |
| core 시험 | `ScenarioPartnerRoleTest`(새) · `ScenarioGenerationTest`(단언 1개: 지시문 → '친구') · `RecordedScenarioExposuresTest`(v2 사례, 기존 3개 시험은 서식만 바꾸고 단언 같음) · `PracticeStartApiIT`(v2 쌍둥이) · `PracticeLinkContractTest`(새) |
| realtime 운영 | `practiceflow/SceneIntro`(새, 순수) · `practiceflow/PracticeConversation`(첫 OPPORTUNITY 재생 생략) · `practiceflow/RolePolicy`(`peerName`) · `practiceflow/PracticeInstructions`(`PEER_RULE` 덧붙임 · 역할 이름 방어) · `backend/coverage-scope/practice-flow.txt`(SceneIntro) |
| realtime 시험 | `SceneIntroTest`(새) · `PracticeConversationTest`(8개 더함) · `PracticeScriptFixtures`(대본 도우미 더함) · `voice/PracticeSpeechMatchTest`(1개 더함) · `PracticeLinkContractTest`(새) |
| 문서 | `docs/units/u10/design.md`(41행 한 문장) · 이 문서 |

- 마이그레이션 · 화면 문구 · 프롬프트 판 · 레거시 `partnerRoleText` · `practicelegacy`는 바꾸지 않았다.
- 새 로그 줄은 없다. 새 예외 메시지는 고정 문구('연습 기록 판 오류' 등)뿐이고 기록 값을 담지 않는다(시험이 확인).

## 9. 커밋

| 순서 | 커밋 | 지시서 PR 경계 |
|---|---|---|
| 1 | `feat(contracts): 연습 연결부 공유 고정 자료와 대본 · 기록 연결 규칙을 더한다` | PR-1 계약 |
| 2 | `fix(core): 새 연습 흐름 기록으로 장면 노출을 정산하고 장면 partnerRole을 역할 이름으로 낸다` | PR-2 core |
| 3 | `fix(realtime): 기회 문장을 한 번만 재생하고 친구 역할 이름을 지시문과 분리한다` | PR-3 realtime |
| 4 | `test(practice): core 출력 → realtime 진행, realtime 기록 → core 정산 교차 계약 시험을 더한다` | PR-2 · PR-3 뒤 |
| 5 | `docs(practice): 연습 연결부 결과 기록과 u10 설계 문장을 고친다` | 문서 |

## 10. 시험 결과

모두 **[테스트 대역] · 합성 자료** 증거다. 실제 API(GPT-Live · Luna · 제브) · 실제 기기 성공은 확인하지 않았다(R-M11, 실키 리허설 몫).
마지막 코드 커밋(`test(practice): …`) 상태에서 돌렸다. 커밋 하나하나를 따로 돌리지는 않았다.

| 증거 종류 | 명령 | 결과 |
|---|---|---|
| 대역 · 단위 + 통합(Testcontainers PostgreSQL) | `cd backend && ./gradlew --continue :contract-model:test :core-api:test -Pmujoong.test.db=docker` | contract-model 325건 · core-api 1751건 통과, 실패 0 · 건너뜀 0. 기준선 실패로 알려진 `GraphWorkflowIT`도 이번에는 통과 |
| 대역 · 단위 + 통합 | `cd backend && ./gradlew --continue :realtime-activity:test -Pmujoong.test.db=docker` | realtime-activity 1891건 통과, 실패 0 · 건너뜀 0 |
| 대역 · 층별(구현 중) | core `RecordedScenarioExposuresTest` · `ScenarioPartnerRoleTest` · `ScenarioGenerationTest` · `ScenarioContractTest` / `PracticeStartApiIT` / realtime `practiceflow.*Test` · `PracticeSpeechMatchTest` / 교차 시험 | 45건 · 9건 · 168건 · (325 + 12 + 3)건 통과 |

- 모듈 전체 줄 커버리지(JaCoCo): contract-model 98.52% · core-api 96.24% · realtime-activity 93.79%(하한 80% 그대로).
- 범위 줄 커버리지(같은 실행의 JaCoCo 보고서에서 범위 목록 파일만 셈, 로컬 · 검토 근거): `practice-flow` 96.36%(23/23 파일, `SceneIntro` 100% · `RolePolicy` 100% · `PracticeInstructions` 96.4% · `PracticeConversation` 94.6%), `practice-legacy-core` 98.38%(`RecordedScenarioExposures` 100%), `scenario-generation` 96.77%(`ScenarioScriptGenerator` 92.6%), `practice-core` 97.54%(`ScenarioContentPolicy` 100% · `ScenarioRepository` 99.1%).
- 5절 IT(`PracticeStartApiIT.newFlowRecordReconcilesEachIssuedScenarioOnceAtItsEarliestSceneStart`)는 장면 3(같은 세션에서 `/internal/practice/scenarios`로 실제 발급한 둘째 대본)을 **넣었다**. 확인한 것: 두 대본 모두 `EXPOSED`, 첫 `runOnce`에서 `exposure_reconciled_at` 채워짐, 첫 대본 `exposed_at` = 가장 이른 장면(BASE) 시작 시각, `recentExposed`에 두 대본, 두 주기 동안 `PracticeExposureReconciliationFailed` 출력 0줄.
- 키 이름 바꿔 보기(로컬, 되돌림):
  - 고정 자료 1의 `partnerRole` → `partnerName`: contract-model 2건(`ContractSchemasTest` 스키마 통과 · 길이 제약), core 1건(`PracticeLinkContractTest` 트리 비교), realtime 2건(`PracticeLinkContractTest` 읽기 · 진행)이 깨졌다.
  - 고정 자료 2의 `scenarioRef` → `scenarioId`: contract-model 1건, core 2건(`RecordedScenarioExposuresTest` · `PracticeLinkContractTest` 정산 읽기), realtime 1건(조립기 트리 비교)이 깨졌다.
- 돌리지 않은 것: CI와 같은 `./gradlew --no-daemon clean check verifyBootJarHasNoTestClasses -Pmujoong.test.db=docker`, `bash scripts/verify.sh`(다음 단계의 전체 검사 몫). 실키 실호출.
