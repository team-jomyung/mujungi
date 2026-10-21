# 연습 연결부 결함 3건 + 교차 계약 시험 (practice-seams)

> 작업 묶음 2(core ↔ realtime 연결부) 결과 기록이다. 브랜치 `claude/practice-seams`(기준 `origin/main` fec5285).
> 사람 대신 모델이 지시서(작업 묶음 2)대로 구현했다. 사람이 정할 일은 아래 '사람이 할 일'에 남겼다.
> 이 브랜치는 실제 AI 공급자 · 실제 기기를 부르지 않았다. 시험 결과는 모두 테스트 대역(가짜 공급자) · 합성 자료 증거다(R-M11).

## 1. 바꾼 동작 한눈에

| 결함 | 전 | 후 | 앱 |
|---|---|---|---|
| (a) 노출 정산 | 새 흐름 기록(`flowVersion=practice-conversation-v1`)을 읽지 못해 정산이 늘 실패했다(`PracticeExposureReconciliationFailed` 되풀이, 다음 대본의 노출 이력이 빔) | `scenes[].scenarioRef` · `startedAt`을 읽어 대본마다 가장 이른 시작 시각으로 정산한다 | core |
| (b) `partnerRole` | core가 장면 `partnerRole`에 지시문을 넣고 realtime은 그것을 역할 이름으로 써서 "너는 아이의 제시된 상황의 … 역할이다"가 나갔다 | core는 `relation`에서 짧은 역할 이름(20자 이하, 대체값 '친구')을 만들고, 금지 규칙은 realtime 고정 지시가 맡는다. realtime은 옛 지시문 값을 '친구'로 바꾸는 방어를 둔다 | core · realtime |
| (c) 기회 문장 이중 재생 | 도입(장면 설명, 기회 문장 포함)을 재생한 뒤 OPPORTUNITY에서 기회 문장을 또 재생했다 | 설명이 기회 문장으로 끝나고(낱말 경계 일치) 끝까지 재생됐으면 첫 OPPORTUNITY 진입에서 다시 재생하지 않고 바로 아이 말을 기다린다. 도입 재생 중에 온 대답은 도입이 끝나면 J2로 판정한다 | realtime |
| (d) 교차 시험 | 두 앱이 각자 손으로 쓴 시험 자료만 썼다 | `contracts/fixtures/` 공유 고정 자료 2개를 contract-model · core · realtime 시험이 함께 읽는다 | 세 모듈 |

## 2. 고른 방안과 까닭

### 2.1 (c) 방법 A2 — 설명 전체 재생 + 기회 문장 재생 생략 (지시서 방법 A의 변형)

- 지시서 방법 A는 도입에서 기회 문장 부분을 **잘라 낸 문자열**을 승인 발화로 재생한다. 이 문자열은 core가 발급한 승인 원문이 아니다.
  연습이 출력을 쥔 동안 실행부는 출력 전사가 `speakApproved`에 넘긴 문자열과 원문 일치하면 제브를 건너뛴다(U13 분류 ①, ai-io.md 10.5).
  잘린 문자열을 넘기면 '승인 원문의 부분 문자열(앞 · 뒤 이어 붙임)도 승인 발화'로 읽는 셈이라 제브 생략 근거가 넓어진다.
- 그래서 이 브랜치는 그 해석을 택하지 않았다(PRACTICE-SEAMS-A1). 대신:
  - 도입은 지금처럼 **장면 설명 원문 그대로** 재생한다. `speakApproved`에는 core가 발급한 문장(설명 · 기회 문장 · 다시 설명)만 들어간다.
  - 설명이 기회 문장으로 **끝나고**(정규화 비교, 설명의 마지막 낱말들이 기회 문장과 같음) 도입이 **끝까지 재생**(PLAYED)됐으면, 도입 직후의 OPPORTUNITY 진입에서 기회 문장을 다시 재생하지 않고 곧바로 입력 대기(무응답 시계)로 간다(PRACTICE-SEAMS-A3 · A15).
  - 기회 문장이 설명 가운데 있거나, 간접 인용('같이 놀자고 했어')으로만 나오거나, 다른 낱말 안에 든 짧은 문장('사줘' 속 '줘')이면 지금처럼 기회 문장을 따로 재생한다. 아이가 상대의 직접 발화를 마지막에 듣게 하려는 것이다.
  - 기회 문장으로 끝나는 도입이 재생되는 동안 확정된 아이 전사는 마지막 하나만 두었다가, 도입이 끝까지 재생되면 OPPORTUNITY 입력 대기에서 곧바로 J2로 판정한다(PRACTICE-SEAMS-A13). 예전 흐름에서 기회 문장 재생 중 전사를 `earlyUtterance`로 잡아 두던 것과 같은 동작이다.
  - 도입이 끊겼거나(INTERRUPTED) 실패했거나 설명이 기회 문장으로 끝나지 않으면 지금처럼 기회 문장을 따로 재생하고, 도입 중에 온 전사는 대답으로 보지 않는다(PRACTICE-SEAMS-A2 · A14).
  - 무응답 재안내(`reguide`)와 상황 다시 설명 뒤의 OPPORTUNITY 재진입은 지금처럼 기회 문장을 재생한다(의도된 동작).
- 결과: `PracticeSpeech.matches` 규칙(ASCII 공백만 빼고 구두점까지 같음)과 제브 생략 판단은 한 글자도 바뀌지 않는다. core 규칙('설명에 기회 문장 포함')도 그대로다.
- 비용:
  - 기회 문장이 설명 끝에 있지 않은 대본은 지금처럼 두 번 듣는다(이중 재생이 그 경우에만 남는다). core 프롬프트는 위치를 정하지 않으므로(`generation.v2.txt` '기회 문장을 장면 설명에도 포함한다') 실제 모델 출력에서 얼마나 자주 끝에 오는지는 실호출 리허설에서 본다.
  - 1차 구현(설명 '포함'만 보고 생략)에는 두 위험이 있었다. ① 도입이 SCENE_INTRO(입력을 기다리지 않는 단계)에서 기회 문장까지 재생하므로, 도입 PLAYED보다 먼저 확정된 대답이 조용히 버려지고 무응답 시계만큼 기다린 뒤 재안내했다. 지시서 7절이 결함 (c)의 결과로 적은 '첫 트리거에 아이가 한 대답은 버려진다'가 기본 경로로 남은 셈이었다. ② 기회 문장이 가운데 · 간접 인용 · 낱말 속에 있어도 생략해, 아이가 상대의 직접 발화를 한 번도 못 들을 수 있었다. 검토 지적으로 둘 다 고쳤다(11절).
  - 남은 위험: 도입 앞부분에서 한 말(예: 설명 도중 '응')이 마지막 전사로 남으면 기회 문장 대답으로 판정된다(PRACTICE-SEAMS-A13). 재생 위치를 알려 주는 사건이 없어 '도입 끝 무렵 전사만'으로 좁히지 못했다. J2가 그 말을 짧은 대답(PARTIAL) · 엉뚱한 대답(IRRELEVANT) · 못 알아들음으로 보면 되묻기 · 상황 다시 설명 · 재안내로 이어지므로, 말없이 무응답 시계를 기다리는 것보다 낫다고 보았다. 실호출 리허설의 확인 항목에 넣는다.

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
| 지시서 7절 `SceneIntroTest` 사례 | 잘라 내기 사례 대신 '설명이 기회 문장으로 끝나는가' 판정 사례(끝 · 끝 인용 · 가운데 · 인용 뒤 서술 · 간접 인용 · 낱말 속 짧은 문장 · 공백 · 문장부호 차이 · 미포함 · 빈 정규화)를 둔다 | 위와 같다 |
| 지시서 7절 `PracticeSpeechMatchTest` 사례 | '잘린 도입 vs 공백만 다른 전사 → 참' 대신 '설명 원문 vs 공백만 다른 전사 → 참, 기회 문장을 뺀 앞부분 전사 → 거짓'을 고정한다 | 부분 문자열이 승인 발화가 아님을 시험으로 못 박는다 |

## 4. 가정 목록

- **PRACTICE-SEAMS-A1** 기회 문장 이중 재생은 방법 A(realtime만)로 고치되 승인 원문 부분 문자열을 승인 발화로 읽는 해석은 택하지 않는다(2.1). 지시서 0.5절 마지막 항목의 답은 '택하지 않음'이다.
- **PRACTICE-SEAMS-A2** 도입이 끊긴(INTERRUPTED) 경우 아이가 기회 문장을 못 들었을 수 있으므로 기회 문장을 따로 재생한다.
- **PRACTICE-SEAMS-A3** (검토 뒤 고침) 기회 문장 재생 생략은 설명의 마지막 낱말들이 정규화한 기회 문장과 같을 때만 한다. 처음에는 '어디에 있든 포함이면 생략'으로 두었으나, 아이가 상대의 직접 발화를 마지막에 듣지 못하는 경우(가운데 · 간접 인용 · 낱말 속 짧은 문장)가 생겨 좁혔다. 낱말 경계는 core 정규화 문자 부류(글자 · 숫자) 밖의 문자(공백 · 문장부호 · 따옴표)다. 설명 쪽 붙여 쓰기('이제그만할래')는 같은 것으로 본다. 길이 하한 같은 숫자 기준은 두지 않았다.
- **PRACTICE-SEAMS-A4** `partnerRole` 라벨 표 · 상한 20자(코드 포인트) · 대체값 '친구'는 지시서 초안 그대로 정했다. 후보 목표 자유 문구의 '공백 정리'는 앞뒤 공백 제거 + 연속 공백(ASCII 공백류)을 한 칸으로 줄이기다.
- **PRACTICE-SEAMS-A5** `practice-scenario.schema.json`의 `scenes[].partnerRole`에 `maxLength: 20`을 넣는다(제약 강화). core 출력은 라벨 규칙으로 늘 20자 이하이고, realtime은 런타임에 이 스키마로 검사하지 않으므로 배포 전에 저장된 옛 값(지시문)이 거절되지 않는다. 옛 값은 realtime 방어가 '친구'로 바꾼다.
- **PRACTICE-SEAMS-A6** PR 나누기: 이 브랜치 하나에 커밋을 지시서 10절의 PR-1(계약) · PR-2(core) · PR-3(realtime) 경계로 나눠 두었다(9절 커밋 표). 한 PR로 올리면 (b)의 '두 앱 같은 날 병합'이 저절로 지켜진다. 셋으로 나눌지는 사람이 정한다. **셋으로 나누면 PR-1(계약) → PR-3(realtime) → PR-2(core) 순으로 병합 · 배포한다.** core(PR-2)만 먼저 `main`에 들어가면 demo 자동 배포가 켜져 있는 한 realtime이 들어올 때까지 친구 지시문에서 '아이 대신 대답하지 않기' 금지 문장이 빠진다. 옛 realtime `PEER_RULE`에는 이 문장이 없고, core가 `partnerRole` 칸에 넣던 지시문(`PARTNER_ROLE`)으로만 들어갔기 때문이다. realtime을 먼저 넣으면 옛 지시문 값은 `RolePolicy.peerName`이 '친구'로 바꾸고 새 `PEER_RULE`이 금지 문장을 실으므로 어느 시점에도 금지 규칙이 빠지지 않는다(검토 지적, 11절). realtime 커밋(3 · 6 · 7 · 10)은 realtime 파일만 바꿔 core 커밋 없이도 빌드된다(정적 확인). 다만 `partnerRole` `maxLength: 20` 제약은 계약 커밋이 아니라 core 커밋에 넣었다. 계약 커밋만 먼저 병합하면 지시문(39자)을 싣는 기존 core 출력이 스키마 대조 시험(`PlannedGenerationIT` 등)에서 깨질 수 있기 때문이다(정적 판단, 커밋별로 따로 돌려 보지는 않았다). 교차 시험 커밋(4)은 core · realtime 커밋 둘 다에 기대므로 마지막 PR(PR-2)에 넣는다. 커밋 8은 커밋 4가 만든 realtime 교차 시험 파일을 고치므로 커밋 4와 함께 넣는다.
- **PRACTICE-SEAMS-A7** 공유 고정 자료 1의 order 1 목표는 후보 목표(데모 경로), order 2 목표는 기존 행동 목표다. BASE `relation`은 후보의 상대 문구 '친한 친구', VARIANT `relation`은 관계 코드 `CLASSMATE`로 두어 라벨 표의 두 갈래(자유 문구 · 코드)를 함께 지난다.
- **PRACTICE-SEAMS-A8** `ScenarioRepository.save`의 `Scenario` 조립을 순수 정적 팩토리 `ScenarioRepository.issued(...)`로 빼서 저장과 교차 시험이 같이 쓴다(지시서 8절). 운영 코드 변경이라 도연 확인이 필요하다.
- **PRACTICE-SEAMS-A9** 서식(team.md Code Style Q7-A): 작은 파일(`RecordedScenarioExposures`)은 팀 서식으로 다시 썼고, 큰 압축 파일(`ScenarioScriptGenerator` · `ScenarioRepository`)은 바꾼 부분만 팀 서식으로 쓰고 나머지는 두었다. 같은 날 병합해야 하는 변경의 검토 부담을 줄이려는 것이다.
- **PRACTICE-SEAMS-A10** realtime 이름 방어(`RolePolicy.peerName`)는 지시문 · 승인 발화 말하는 사람 표시 두 곳에서 쓰고, 읽기 경계(`PracticeScriptReader`)는 바꾸지 않았다. 대본 값 자체는 사실대로 두고 쓰는 곳에서만 접는다.
- **PRACTICE-SEAMS-A12** 새 흐름 장면에서 `scenarioRef`나 `startedAt` 중 하나라도 비면 다른 칸의 형식과 관계없이 그 장면을 건너뛴다. 정산할 수 없는 장면 하나 때문에 기록 전체 정산을 막지 않으려는 것이다. 두 칸이 다 있는데 형식이 틀리면 지금처럼 실패다.
- **PRACTICE-SEAMS-A13** 기회 문장으로 끝나는 도입이 재생되는 동안(도입 슬롯이 열린 동안) 확정된 전사는 마지막 하나만 두었다가 도입 PLAYED 뒤 J2로 판정한다. 마지막 하나만 두는 것은 예전 OPPORTUNITY 재생 중 처리와 같다. 재생 위치 사건이 없어 '도입 끝 무렵 전사만' 받는 좁히기는 하지 않았고, 도입 앞부분에서 한 말이 판정될 수 있는 위험은 실호출 리허설에서 본다.
- **PRACTICE-SEAMS-A14** 도입이 끊기거나(INTERRUPTED) 실패하면 도입 중에 온 전사는 버린다. 기회 문장을 따로 다시 재생하므로, 그 전에 한 말을 기회 문장 대답으로 보지 않는다. 다시 재생하는 동안 온 전사는 지금처럼 재생이 끝나면 판정한다.
- **PRACTICE-SEAMS-A15** VARIANT 장면에도 BASE와 같은 생략 규칙(A3)을 쓴다. core는 VARIANT 설명의 기회 문장 포함을 강제하지 않지만, 규칙이 실제 문장을 보고 판단하므로 포함 보장에 기대지 않는다. 설명이 기회 문장으로 끝나지 않는 VARIANT는 기회 문장을 따로 재생한다.
- **PRACTICE-SEAMS-A11** 지시서 0.5절의 나머지(디스코드 공지 수정 · 범위 밖 발견 담당 · 데모 DB 초기화)는 사람 몫이라 정하지 않고 '사람이 할 일'에 권장안만 적었다.

## 5. 원 담당자와 맞출 것

- **성민(realtime)**: 방법 A2(설명 전체 재생 + 기회 문장 재생 생략)가 U12 흐름 의도에 맞는가. 생략 조건을 '설명이 기회 문장으로 끝남(낱말 경계)'으로 좁힌 것(A3 · A15)과, 도입 재생 중 온 대답을 도입이 끝나면 J2로 판정하는 것(A13 · A14)이 SCENE_INTRO · OPPORTUNITY 단계 뜻과 맞는가. 잘린 도입을 원하면 '승인 원문 부분 문자열 = 승인 발화' 해석을 U13이 먼저 승인해야 한다(열린 질문). `PEER_RULE` 덧붙임 · `RolePolicy.peerName` 방어.
- **도연(core)**: 라벨 표 · 상한 · 대체값, `ScenarioRepository.issued` 추출, `RecordedScenarioExposures` 새 흐름 분기, `docs/units/u10/design.md` 한 줄 수정.

## 6. 사람이 할 일

- 디스코드로 성민 · 도연에게 "이 셋((a) 노출 정산 · (b) partnerRole · (c) 기회 문장)은 준하가 한다"고 알리고, 이미 손댄 로컬 작업이 있는지 묻는다. 앞선 공지 초안의 담당 문구를 고칠지 정한다.
- 5절 확인을 받는다. 특히 방법 A2와 라벨 표.
- PR을 연다(한 PR 또는 셋, PRACTICE-SEAMS-A6). 리뷰어: core 부분 도연, realtime 부분 성민, 계약은 둘 다. (b) 때문에 core · realtime은 같은 날 병합한다. **셋으로 나누면 realtime(PR-3)을 core(PR-2)보다 먼저 병합 · 배포한다.** demo 자동 배포가 켜져 있으면 core만 들어간 `main`이 그대로 배포되어, 그 사이 친구 지시문에서 '아이 대신 대답하지 않기' 금지 문장이 빠진다(A6). 순서를 지키기 어려우면 한 PR로 올리거나, realtime PR이 들어올 때까지 저장소 변수 `AUTO_DEPLOY=false`(자동 배포 동결 스위치)로 자동 배포를 멈춘다.
- 범위 밖 발견 담당 지정(권장: 가상 상황 고지는 도연 · 성민 확인, VARIANT 계획은 도연의 변형 어휘 작업).
- 데모 DB에 남은 옛 READY 준비 패키지 초기화 여부를 정한다(권장: 초기화. realtime 방어로 동작은 한다).
- 실키 리허설(R-F15 확인 기록 뒤)에서 자유대화 → 연습 → 다음 경험 한 바퀴로 (a) 정산 · (b) 역할 이름 · (c) 재생 횟수를 실제로 본다. 이 브랜치는 실제 AI를 부르지 않았다. (c)에서는 다음도 본다: 실제 모델이 낸 BASE · VARIANT 설명이 기회 문장으로 끝나는 비율(끝나지 않으면 두 번 듣는다), 도입 재생 중 대답이 판정되는지, 도입 앞부분에서 한 말이 대답으로 판정되는 일이 있는지(A13), 실제 기기에서 아이가 말할 때 도입이 끊기는지(끊기면 A14 경로).

## 7. 범위 밖 발견 (보고만)

- 새 흐름 도입에 장면별 가상 상황 고지가 없다(`childText`는 레거시 `scenarioText`에만 붙는다). `docs/units/u10/design.md`는 '가상 장면 고지도 기존 정책을 유지한다'고 적었다.
- VARIANT 계획이 구조적으로 나오기 어렵다(BASE `relation`이 `PEER` 또는 한국어 문구인데 `VariantPlanner.plan`은 관계 코드만 받는다). 정적 추정이며 실행 확인은 안 했다.
- 배포 전에 만든 READY 준비 패키지에는 옛 지시문 `partnerRole`이 남아 있다(realtime 방어로 '친구'가 된다).

## 8. 바꾼 파일

| 앱 | 파일 |
|---|---|
| 계약 | `contracts/fixtures/practice-start-package.json`(새) · `contracts/fixtures/practice-record-v2.json`(새) · `contracts/schemas/practice-scenario.schema.json` · `contracts/schemas/practice-record-v2.schema.json` · `contracts/ai-io.md`(7절 한 문장, 10.15 새 절, 검토 뒤 10.15 2항 생략 조건 · 도입 중 대답) · `contracts/deviations.md`((U12-b) ~ (U12-d), 검토 뒤 (U12-b) 병합 순서 조건 · (U12-c) 조건) · `contracts/README.md` |
| contract-model 시험 | `ContractSchemasTest`(고정 자료 2개 스키마 통과 · partnerRole 길이 제약) |
| core 운영 | `practice/ScenarioContentPolicy`(`partnerRoleName` · 라벨 표) · `practice/ScenarioScriptGenerator`(장면 partnerRole = 역할 이름) · `practice/ScenarioRepository`(`issued` 정적 팩토리, 동작 무변경) · `practice/start/RecordedScenarioExposures`(새 흐름 분기, 팀 서식으로 다시 씀) |
| core 시험 | `ScenarioPartnerRoleTest`(새) · `ScenarioGenerationTest`(단언 1개: 지시문 → '친구') · `RecordedScenarioExposuresTest`(v2 사례, 기존 3개 시험은 서식만 바꾸고 단언 같음) · `PracticeStartApiIT`(v2 쌍둥이) · `PracticeLinkContractTest`(새) |
| realtime 운영 | `practiceflow/SceneIntro`(새, 순수, 끝 · 낱말 경계 규칙) · `practiceflow/PracticeConversation`(첫 OPPORTUNITY 재생 생략 · 도입 재생 중 대답을 도입 뒤 판정) · `practiceflow/RolePolicy`(`peerName`) · `practiceflow/PracticeInstructions`(`PEER_RULE` 덧붙임 · 역할 이름 방어) · `backend/coverage-scope/practice-flow.txt`(SceneIntro) |
| realtime 시험 | `SceneIntroTest`(새) · `PracticeConversationTest`(12개 더함, 이 브랜치가 더한 지시문 상한 시험 입력에 공백 한 칸) · `PracticeScriptFixtures`(대본 도우미 더함) · `voice/PracticeSpeechMatchTest`(1개 더함) · `PracticeLinkContractTest`(새, 진행 → 장면 행 → 고정 자료 2 대조 포함) |
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
| 6 | `fix(realtime): 기회 문장 재생 생략을 설명이 기회 문장으로 끝날 때로 좁힌다` | PR-3 realtime (검토 뒤) |
| 7 | `fix(realtime): 기회 문장으로 끝나는 도입이 재생되는 중에 온 아이 대답을 버리지 않는다` | PR-3 realtime (검토 뒤) |
| 8 | `test(practice): 고정 자료 1로 진행한 장면 행이 고정 자료 2의 첫 장면과 정산 칸이 같은지 본다` | 교차 시험(커밋 4와 함께, 마지막 PR) |
| 9 | `docs(contracts): 기회 문장 재생 생략 조건 · 도입 중 대답 처리와 partnerRole 병합 순서 조건을 적는다` | PR-1 계약 보강 |
| 10 | `test(realtime): 기회 문장보다 짧은 설명이 기회 문장을 담지 않는 사례를 더한다` | PR-3 realtime (검토 뒤) |
| 11 | `docs(practice): 검토 지적 반영(기회 문장 생략 조건 · 도입 중 대답 · 병합 순서 · 교차 시험)을 결과 기록에 적는다` | 문서 |

## 10. 시험 결과

모두 **[테스트 대역] · 합성 자료** 증거다. 실제 API(GPT-Live · Luna · 제브) · 실제 기기 성공은 확인하지 않았다(R-M11, 실키 리허설 몫).

### 10.1 검토 지적 반영 뒤 (커밋 6 ~ 10)

| 증거 종류 | 명령 | 결과 |
|---|---|---|
| 대역 · 단위 + 통합(Testcontainers PostgreSQL) | `cd backend && ./gradlew --continue :realtime-activity:test :realtime-activity:jacocoTestReport -Pmujoong.test.db=docker` (커밋 9 상태) | realtime-activity 1898건 통과(1차보다 7건 많음), 실패 0 · 건너뜀 0. 모듈 줄 커버리지 93.79% |
| 대역 · 단위 | `:realtime-activity:test --tests "…SceneIntroTest"` (커밋 10 상태) | 11건 통과. 커밋 10 이전 전체 실행에서 `SceneIntro` 줄 92.9%(마지막 `return false` 한 줄)였던 것을 이 사례로 채웠다 |
| 대역 · 단위 | `:contract-model:test` (커밋 9 상태) | 325건 통과(`ContractDocsTest` · `ContractFilesTest` · `ContractSchemasTest` 포함) |
| 대역 · 단위 | `:core-api:test --tests` `LunaInputSanitizerTest` · `LunaHostsTest` · `TimingContractTest`(계약 문서를 읽는 시험) · `PracticeLinkContractTest` (커밋 9 상태) | 7 + 10 + 6 + 3건 통과. core 운영 · 시험 코드는 이번에 바꾸지 않아 core 전체는 다시 돌리지 않았다 |

- 범위 줄 커버리지(로컬 · 검토 근거, 커밋 9 상태 전체 실행 보고서): `practice-flow` 96.31%(23/23 파일, `PracticeConversation` 94.7% · `RolePolicy` 100% · `PracticeInstructions` 96.4% · `SceneIntro` 92.9% → 커밋 10 사례로 빠진 줄 채움).
- 교차 시험 확인(로컬, 되돌림): 고정 자료 2 `scenes[0].scenarioRef`를 c09로 바꾸면 realtime `PracticeLinkContractTest`의 진행 시험(새 단언)과 트리 비교 시험 2건이 깨진다.
- 지적 ① 재현: 검토자가 임시 시험으로 보인 '도입 PLAYED 전 대답 → 판정 0건 · 무응답 시계' 순서를 `PracticeConversationTest.anAnswerThatArrivesBeforeACarryingIntroFinishesIsJudgedAsTheOpportunityAnswerOnceTheIntroPlayed`로 고정했다. 고친 뒤 판정 1건(J2, 마지막 전사 seq) · 기회 문장 재생 1회 · 무응답 시계 없음이다.

### 10.2 1차 구현 (커밋 1 ~ 4)

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

## 11. 검토 지적 처리 (2차)

1차 구현(커밋 1 ~ 5) 뒤 받은 검토 지적 4건을 코드로 다시 확인했다. 넷 다 맞는 지적이라 고쳤다.

| 심각도 | 지적 | 확인한 사실 | 처리 |
|---|---|---|---|
| major | A2에서 기회 문장이 SCENE_INTRO(입력을 기다리지 않는 단계) 안에서 재생되어, 도입 PLAYED 전에 온 대답이 버려지고 무응답 대기 뒤 재안내한다 | `onChildUtterance`는 `phase.awaitsInput()`일 때만 `earlyUtterance`를 두고, SCENE_INTRO는 여기에 들지 않았다. 실행부(`SessionExecutor`)는 기기 재생 중에도 확정 전사를 확장에 넘긴다 | 커밋 7: 기회 문장으로 끝나는 도입의 슬롯이 열린 동안 온 전사를 마지막 하나만 두고, 도입 PLAYED 뒤 OPPORTUNITY 입력 대기에서 J2로 판정한다. 도입이 끊기면 버린다(A13 · A14). 시험 4개 |
| minor | 병합 순서가 바뀌어도 동작한다고만 적었는데, core를 먼저 배포하면 '아이 대신 대답하지 않기' 금지 문장이 빠진다 | `origin/main`(fec5285)의 realtime `PEER_RULE`에는 그 문장이 없고, core `PARTNER_ROLE`("…아이의 답을 대신 말하거나 평가하지 마.")을 `partnerRole` 칸으로만 받았다. 커밋 2 이후 core는 이 칸에 이름만 넣는다 | 커밋 9 · 10: (U12-b) 행 · A6 · 6절에 'realtime을 core보다 먼저 병합 · 배포, 또는 한 PR' 조건을 적었다. realtime이 먼저면 옛 지시문 값은 '친구'가 되고 새 `PEER_RULE`이 금지 문장을 싣는다 |
| minor | `carriesOpportunity`가 경계 없이 포함만 보고, VARIANT는 core가 포함을 보장하지 않아 아이가 직접 발화를 못 들을 수 있다 | `ScenarioScriptValidation`은 BASE에만 포함을 검사한다. '같이 놀자고 했어'와 '같이 놀자!', '사줘'와 '줘.'가 모두 '담는다'로 판정됐다 | 커밋 6 · 10: 설명의 마지막 낱말들이 기회 문장과 같을 때만 생략한다(BASE · VARIANT 같은 규칙, A3 · A15). 가운데 · 간접 인용 · 낱말 속 짧은 문장은 따로 재생한다 |
| minor | realtime 교차 시험의 진행 시험이 기록기를 보지 않아, 진행이 싣는 `scenarioRef`와 고정 자료 2의 c01이 이어서 확인되지 않는다 | 진행 시험은 `h.recorder`를 보지 않고 기록 시험은 `SceneRunRecord`를 손으로 만들었다 | 커밋 8: 진행 시험 끝에 장면 행의 `scenarioRef` · `goalRef` · `sceneKind`와, 그 행을 조립한 첫 장면이 고정 자료 2 `scenes[0]`과 같은지 본다 |

- 이 브랜치가 더한 시험 중 단언을 바꾼 곳: `SceneIntroTest`의 '가운데 · 따옴표 안도 담는다' 사례 2개(새 규칙에서 거짓으로 바뀜 → '가운데 · 인용 뒤 서술은 따로 재생' 사례로 고침)와 'ＯＫ 하고 친구가 웃었어' 사례(설명 끝으로 옮김). `PracticeConversationTest` 지시문 상한 시험은 입력에 공백 한 칸만 넣었고 단언은 그대로다. `main`에 있던 시험 단언은 바꾸지 않았다.
