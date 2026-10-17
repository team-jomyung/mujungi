# Functional Design 질문 — U11 판정 턴 기반

> 기준: `inception/requirements-analysis/requirements.md`(FR1 ~ FR11, NFR1 ~ NFR8, 승인 때 받아들인 검토 지적 R-01 ~ R-06), 코드 지식 베이스 U11-R1 ~ R13, 그리고 이 단계 전에 한 읽기 전용 코드 조사(줄 번호는 `SessionExecutor.java` 커밋 `9bcc706` 기준).
> 요구사항에서 이미 정한 것(보류 기준값, 키 없을 때 보류, 송신 순서, 새 패키지 등)은 다시 묻지 않습니다. 코드를 보니 요구사항만으로는 정할 수 없는 곳 세 군데만 묻고, 나머지 설계 판단은 맨 아래 가정 묶음으로 확인받습니다.

## Q1. 승인 발화 `D_REST_ACK`는 음성 파일이 없는데, 어떻게 들려줄까요?

U8 시드 004의 `D_REST_ACK`("잠깐 쉬고 싶구나. 천천히 해도 괜찮아.")는 음성 파일이 없는 승인 발화(`APPROVED_SPEECH`)입니다.
- 지금 실행부의 승인 문구 재생(`PlayApprovedContent`)은 음성 파일이 있어야 합니다. `D_REST_ACK`로 부르면 `APPROVED_CONTENT_MISSING`만 남기고 바로 끝납니다(SE:1457-1466).
- 음성 없는 승인 발화를 들려주는 길은 하나뿐입니다. GPT-Live에 원문을 그대로 말하라고 지시하고(`speak`), 출력 게이트가 공백 제외 완전 일치를 확인하는 방식입니다(SE:1405-1430, SE:2183-2187). 이 길은 지금 출력권을 가진 연습 확장만 쓸 수 있습니다. 자유대화에는 이 길이 없습니다.
- `LiveContentRegistry.matchApproved` · `registerScript`는 만들어져 있지만 운영 코드에서 아무도 부르지 않습니다.

A. 실행부가 `speak`와 같은 방식(원문 그대로 말하라는 지시 + 게이트 완전 일치)을 자유대화 · 연습 공통으로 쓸 수 있게 넓히고, `LiveContentRegistry.matchApproved`로 원문 일치를 확인합니다. 일치하지 않으면 그 음성은 보내지 않고 다시 묻기 지시로 넘어갑니다. (추천)
B. `D_REST_ACK` 음성 파일을 만들어(합성음 자리 파일, 사람 승인 뒤 교체) 기존 `PlayApprovedContent`로 재생합니다. 시드 · 음성 자산 · 승인 절차가 함께 바뀝니다.
C. 이번에는 `D_REST_ACK`를 재생하지 않고, "받아 주는 말을 짧게 하고 하던 이야기로 돌아가라"는 일반 지시 하나로 대신합니다. AC1.7.6의 승인 문구 요구와 다르므로 편차로 적습니다.
X. Other (please specify)

[Answer]: C

## Q2. 판정 턴 동안 GPT-Live가 내는 자동 응답을 어떻게 다룰까요?

요구사항 FR3.1은 "판정 턴 동안의 출력은 음성은 버리고 전사는 `judgment_live`에만 남긴다"입니다.
지금 코드는 보류 중에 처음 보는 응답이 오면 곧바로 `session.response.cancel`로 취소하고, 이후 조각을 모두 버립니다(SE:1593-1597). 그래서 남길 전사가 거의 없습니다.

A. 지금처럼 바로 취소합니다. 취소 전에 이미 받은 조각의 전사만 `judgment_live`에 남깁니다(대개 비어 있음). 공급자 비용과 미확인 출력이 가장 적고, '버린 출력 전사' 기록은 있으면 남기는 수준이 됩니다. (추천)
B. 취소하지 않고 응답을 끝까지 받되, 음성은 버리고 전사만 `judgment_live`에 모읍니다. 판정이 끝나 지시를 넣을 때 취소합니다. 버린 출력 전사가 온전히 남지만, 공급자 비용이 늘고 지시 전 응답과 지시 뒤 응답을 가르는 일이 더 어려워집니다.
C. 판정 턴을 여는 순간 GPT-Live 자동 응답 생성을 막는 방법을 찾습니다(지금 선 형식에는 그런 메시지가 없어 FR9 실호출에서 확인이 필요합니다). 확인 전까지는 A로 동작합니다.
X. Other (please specify)

[Answer]: A

## Q3. 10초 응답 준비 시계(FR4.1)는 어느 턴에 적용할까요?

검토 지적 R-02의 남은 결정입니다. 지금 코드에는 아이 말 끝 ~ 첫 응답 사이를 재는 시계가 없습니다. `response-ready`(10s) 설정값은 있지만 운영 코드에서 읽는 곳이 없습니다.
기준 시점은 "아이 말 끝(`speech_stopped`, 없으면 확정 전사)"으로 보고, "준비됨"은 "보류에 걸리지 않고 출력이 시작된 응답의 첫 조각"으로 정하려 합니다. 남은 것은 적용 범위입니다.

A. 판정 턴에만 적용합니다. 판정 턴이 열린 뒤 10초 안에 지시 뒤 응답이 시작되지 않으면 보류로 기록하고 되묻기 · 맞장구를 지시합니다. 판정 없는 일반 대화 턴은 지금 동작 그대로입니다. (추천)
B. ACTIVE 중 모든 아이 말 턴에 적용합니다. GPT-Live가 10초 안에 응답하지 않으면 판정 턴이 아니어도 되묻기 · 맞장구를 지시합니다. 자유대화 동작이 바뀌고 기존 시험(U3 · U4b)에 영향이 갈 수 있습니다.
C. 판정 턴과 자유대화 일반 턴에 적용하고, 연습(출력권 확장)에서는 확장이 정하게 둡니다.
X. Other (please specify)

[Answer]: A

## Q1-1. 출력권을 가진 연습 중 휴식 요청이면 무엇이 들리나요? (Q1 답 C의 후속)

Q1에서 `D_REST_ACK` 대신 "받아 주는 말을 짧게 하고 하던 이야기로 돌아가라"는 일반 지시 하나로 대신하기로 했습니다(C).
그런데 요구사항 단계의 Q5-1(A)은 "출력권을 가진 연습 확장이 있으면 실행부는 지시하지 않고 '휴식 요청' 신호만 넘기고, 확장이 처리하지 않으면(지금 U5b) `D_REST_ACK`만 나간다"였습니다. `D_REST_ACK`가 없어지면 그 경우 아이에게 아무 말도 들리지 않게 됩니다. 두 답이 부딪혀 하나를 골라야 합니다.

A. 출력권을 가진 연습 확장이 있어도 실행부가 "받아 주는 말만 짧게 하라"(다시 묻기 없음)는 지시를 넣고, '휴식 요청' 신호를 확장점에 넘겨 다시 묻기는 확장이 합니다. U5b는 신호를 처리하지 않으므로 받아 주는 말만 들리고, U5b 진행은 그 뒤 원래 순서대로 이어집니다. (추천)
B. 출력권을 가진 연습 확장이 있으면 실행부는 아무 지시도 넣지 않고 신호만 넘깁니다. U5b 레거시 연습에서는 휴식 요청에 아무 말도 들리지 않습니다(U12가 붙으면 확장이 처리).
C. 출력권 확장 유무와 관계없이 실행부가 받아 주는 말 + 다시 묻기 일반 지시를 넣습니다(요구사항 Q5-1 B안). U5b 출력 순서와 겹칠 위험이 있습니다.
X. Other (please specify)

[Answer]: A

## Assumption Confirmation

코드 사실과 요구사항에 바로 맞는다고 보고 정한 설계 가정입니다. 한 묶음으로 확인해 주세요.

1. **휴식 · 종료 뜻 감지 경로(R-01)**: 새 휴식 사전을 만들지 않습니다. "쉬고 싶어 · 힘들어 · 졸려" 같은 말은 지금 `StopIntentJudge`의 불명확 목록에 있어 CONFIRM이 되고, 참여 확인 중에는 `JoinTracker`가 UNCLEAR로 분류합니다. 바로 그 순간에 J6(종료 의도) · J1(시작 응답) 판정 턴을 엽니다. 두 선택지 판에 이미 REST가 있습니다.
2. **명시적 요청의 즉시 처리(R-01)**: 등록 종료 표현(STOP)은 지금처럼 판정 · 게이트 없이 바로 종료합니다(AC1.7.4의 '서버 즉시 종료'). '제브 결과와 무관' 보장은 U13 몫입니다. 자유대화에는 도움 개념이 없고, 연습의 도움 요청 처리는 U12(지금은 U5b)가 합니다. U11은 도움 감지를 더하지 않습니다.
3. **J6 흐름**: CONFIRM이면 확인 질문을 재생하기 전에 J6 판정 턴을 엽니다.
   - '종료 명확'이면 종료합니다(근거 `LUNA_J6`).
   - '휴식 요청'이면 휴식 처리(FR6.4)를 합니다.
   - '불명확' · '판독 불가' · 보류면 지금의 확인 질문(`STOP_CONFIRM`) 흐름으로 갑니다.
   - '종료 아님'이면 지금의 확인 해소(RESUME)처럼 대화를 잇습니다.
   - 키가 없으면 지금 흐름 그대로입니다.
4. **J1 흐름**: 참여 확인 중 `JoinTracker`가 UNCLEAR일 때만 J1 판정 턴을 엽니다.
   - '참여'면 시작합니다.
   - '거부'면 지금의 거부 처리(대안 제안)로 갑니다.
   - '휴식 요청'이면 `D_REST_ACK` 뒤 참여 창을 그대로 둡니다.
   - '불명확' · '판독 불가' · 보류면 지금의 재안내(CLARIFY)로 갑니다.
5. **종료 근거(R-03, FR6.5)**: 지금 세션 사건 `CHILD_STOP_INTENT`의 `basis`(SNAPSHOT · CLEAR_PATTERN)에 `LUNA_J6` 값을 더해 realtime 사건 표에 남깁니다. 이 표는 realtime 내부라 계약 변경이 없습니다. 화면 17은 지금 서버 값을 쓰지 않고 상수 "무중아 그만할래"를 보이므로, 화면 반영은 프런트 단위 몫으로 남깁니다.
6. **늦게 온 원래 응답(R-02)**: 10초 초과 · 판정 보류로 되묻기 지시를 넣을 때는 세대 번호를 올리고 열린 응답을 취소해, 늦게 온 원래 응답이 되묻기와 겹쳐 들리지 않게 합니다.
7. **V5로 깨지는 세 번째 시험**: `RealtimeSchemaIT` · `PracticeMigrationRegistrationTest`에 더해 `PracticeMigrationIT`(L49)도 최신 판 "4"를 단언합니다. 요구사항 Q2 답 A와 같은 까닭으로 새 사실로 갱신합니다.
8. **설정 키**: `mujoong.judgment.min-confidence` · `min-margin`은 `RealtimeProperties` 루트에 `Judgment` 레코드로 더합니다. `ConfigKeyContractTest`는 고치지 않습니다.
9. **판정 기록 쓰기 · 삭제**:
   - 쓰기는 실행부를 기다리게 하지 않는 지금 관례(`AsyncRecorder`의 칸 → 플러시, 실패 시 앱 오류 기록 후 다음 틱 재시도, 같은 판정 id 덮어쓰기)를 따릅니다.
   - 삭제는 보호자 기준 참여자로 순서 180(연습 150 뒤, 세션 200 앞)에 둡니다.
10. **판정 턴 상태 객체**: 판정 차례 id · 보류 · 시계 · 늦은 결과 거르기는 실행기 스레드 전용 협력 객체(새 패키지 `kr.mujoong.realtime.judgment`)에 두고, `SessionExecutor`에는 호출 지점만 둡니다. Luna 결과는 다른 스레드에서 편지함(`putSafety`)으로만 넘깁니다.
11. **지시 송신 경로**: 판정 뒤 지시 · 되묻기 · 다시 묻기 지시는 세대 올림 → 열린 응답 취소 → `instructions.correct` → 세대 재확인 → `response.create`를 한 직렬 작업으로 보냅니다. 지금 `speak`가 쓰는 직렬 큐 방식을 자유대화에도 씁니다.
12. **'생각 중'**: 판정 턴 보류는 `outputHeld()`와 다른 조건으로 두어 THINKING을 보냅니다. `listeningAgain()`을 부르는 세 곳(SE:1862 · 2257 · 2453)은 판정 턴 중이면 LISTENING으로 되돌리지 않습니다.
13. **프롬프트 · 지시 템플릿**:
    - 판정 지시문과 `promptVersion`은 realtime 자원 파일(예: `judgment/prompt-v1.txt`)로 둡니다.
    - 되묻기 · 맞장구 · 다시 묻기 템플릿은 `DialogInstructions`의 지시 종류에 더합니다.
    - 어느 쪽에도 판정 라벨 · 확률 · 기준을 넣지 않습니다.

A. Accept assumptions
B. Convert to follow-up questions

[Answer]: A. Accept assumptions

## 지난 요약 확인 기록 (2026-10-16, 범위 확장 전)

> 아래는 첫 판 설계 때의 요약 확인이다(답: Looks correct). 범위 확장 개정의 요약 확인은 이 파일 맨 아래에 새로 둔다.

- Q1 C: `D_REST_ACK`(음성 파일 없는 승인 발화)는 재생하지 않고, "받아 주는 말을 짧게 하고 하던 이야기로 돌아가라"는 일반 지시로 대신합니다. 요구사항 FR6.4 · AC1.7.6의 승인 문구 요구와 다르므로 편차로 적습니다(승인된 요구사항 문서는 고치지 않음).
- Q1-1 A: 출력권을 가진 연습 확장이 있으면 실행부는 "받아 주는 말만 짧게"(다시 묻기 없음) 지시를 넣고 휴식 신호를 확장점에 넘깁니다. 다시 묻기는 확장(U12) 몫이고, U5b에서는 받아 주는 말만 들린 뒤 원래 순서로 이어집니다. 출력권 확장이 없으면(자유대화 등) 받아 주는 말 + 다시 묻기를 한 지시로 넣습니다.
- Q2 A: 판정 턴 동안 온 GPT-Live 자동 응답은 지금처럼 바로 취소하고, 취소 전에 받은 조각의 전사만 `judgment_live`에 남깁니다.
- Q3 A: 10초 응답 준비 시계는 판정 턴에만 적용합니다. 기준은 아이 말 끝(`speech_stopped`, 없으면 확정 전사)이고, '준비됨'은 보류에 걸리지 않고 출력이 시작된 응답의 첫 조각입니다. 일반 대화 턴은 지금 동작 그대로입니다.
- 가정 13개 수락: 휴식은 기존 CONFIRM · UNCLEAR 순간에 J6 · J1로(새 사전 없음) / 등록 종료는 지금처럼 즉시, 도움 감지는 안 더함 / J6 · J1 결과별 처리(J1 '휴식 요청'은 `D_REST_ACK` 대신 Q1 C의 일반 지시) / 종료 근거는 `CHILD_STOP_INTENT` basis에 `LUNA_J6` 추가 / 되묻기 때 늦은 원래 응답 취소 / `PracticeMigrationIT`도 판 단언 갱신 / `Judgment` 설정 레코드 / 판정 기록 비동기 쓰기 · 삭제 순서 180 / 협력 객체 · 편지함 / 지시는 직렬 한 작업 / 생각 중 유지 / 프롬프트는 자원 파일 · `DialogInstructions`.

## 범위 확장 개정 질문 (2026-10-17, 요구사항 재승인 뒤)

> 기준: 재승인된 `requirements.md`(FR12 선 형식 정비 · FR13 키 이름 통일 · FR1.4 판정 시한 4초, 승인 때 감수한 검토 지적 R-01 ~ R-05), 실호출 보고서(2026-10-16T08:39Z), 이번 단계 전에 한 읽기 전용 코드 조사(줄 번호는 지금 브랜치 기준, SE = `SessionExecutor.java`).
> 요구사항에서 정한 것(서버 세대 · 서버 쪽 버림, mute 안 씀, 24kHz 먼저 확인, TTS 실호출로 전사 사건 확인, 키 통일)은 다시 묻지 않습니다. 코드를 보니 그 결정만으로는 설계가 정해지지 않는 세 곳만 묻고, 나머지는 맨 아래 가정 묶음으로 확인받습니다.

## Q4. `output.done`이 없으면 출력 구간이 '완전하다'는 것을 무엇으로 정할까요?

지금 출력 게이트는 편차 (z)대로 "응답의 첫 조각부터 `session.output.done(responseId)`까지 담은 구간"만 완전한 구간으로 보고, 아니면 GATE_EMPTY로 버립니다(`OutputGate`:35-38, `SegmentBuffer`:155-162 · :210).
실제 공급자에는 `output.done`도 응답 id도 없습니다. 이대로면 모든 출력이 버려집니다. 이미 있는 출력 무음 시계 `SEGMENT_QUIET`(`mujoong.gate.segment-quiet` 800ms, SE:1698 · 2907)는 조각이 끊기면 구간을 봉인하지만, 지금은 그 구간을 '불완전'으로 봅니다.

A. 서버 세대(FR12.5)가 열린 뒤 첫 조각부터 받기 시작해, 조각이 `segment-quiet` 동안 끊기면 그 구간을 '완전'으로 봉인합니다. 세대가 바뀌어 끊긴 구간(끼어들기 · 지시 · 판정 턴)은 '불완전'으로 버립니다. 새 시간 키는 없습니다. TTS 실호출(FR12.7)에서 응답 끝 사건이 보이면 그 사건을 우선 씁니다. 편차 (z)를 대체하는 새 편차로 적습니다. (추천)
B. A와 같되, 무음 봉인 시간을 출력 전용 새 시간 키(예: `mujoong.timing.provider.output-settle`)로 따로 둡니다. 시간 값 세 곳(R-M5)을 함께 고칩니다.
C. 게이트를 문장 단위로 바꿉니다. 출력 전사 조각에 문장 끝(마침표 · 물음표)이 오면 그 문장까지를 완전한 구간으로 보고 바로 게이트에 넣습니다. 지연은 줄지만 게이트 · 문장 버퍼 구조가 크게 바뀌고 U13 제브 검사 단위도 바뀝니다.
X. Other (please specify)

[Answer]: A

## Q5. 응답 id가 없을 때, 지시 뒤 새 응답과 남아 있던 자동 응답을 어떻게 가를까요?

요구사항 Q8(A)에서 취소는 서버 쪽 버림으로 정했습니다. 그런데 공급자는 취소를 모르므로, 판정 턴 동안이나 지시를 넣은 뒤에도 앞서 만들던 자동 응답을 계속 보냅니다. 조각에 응답 id가 없어서, 지시 뒤에 오는 조각이 남은 자동 응답인지 지시에 따른 새 응답인지 조각만 보고는 알 수 없습니다. 지금 설계의 BR3.7("지시 송신 뒤 처음 보인 응답 id")은 이 구별에 기댔습니다.

A. 세대를 올린 뒤(판정 턴 열기 · 지시 송신 · 끼어들기)에는 조각을 버리다가, 조각이 `segment-quiet` 동안 끊긴 뒤 처음 오는 조각부터 새 세대의 응답으로 받습니다. 지시는 그 '끊김'을 확인한 뒤에 보내 남은 자동 응답과 섞이지 않게 합니다. 지시 응답 시한(BR4.5)은 그대로 둡니다. TTS 실호출에서 응답 경계 사건이 보이면 그 사건으로 바꿉니다. (추천)
B. A에 더해, 지시를 보내기 전에 `session.update`로 자동 응답을 끄는(공급자 쪽에서 응답을 만들지 않게 하는) 설정이 있는지 TTS 실호출에서 확인하고, 있으면 판정 턴 동안 자동 응답을 끕니다. 확인 전까지는 A로 동작합니다.
C. 판정 턴 · 지시 뒤에는 출력 전사 내용으로 가릅니다. 지시 뒤 첫 문장이 지시 종류(되묻기 · 받아 주기)에 맞는지 검사해서 맞는 것만 받습니다. 판단이 모호하고 시험하기 어렵습니다.
X. Other (please specify)

[Answer]: A

## Q6. 키를 하나로 합치면 CI · 검증에서 Luna가 켜지는 문제를 어떻게 막을까요? (검토 지적 R-01 · R-02)

코드 조사로 요구사항에서 보지 못한 충돌이 나왔습니다.
- 지금 realtime은 **모든 프로필에서** `PROVIDER_API_KEY`가 비어 있으면 기동을 거부합니다(`RealtimeStartupChecks`:29-30 · :133-135). 그래서 CI · `verify.sh`는 자리값을 넣습니다(`infra/ci/gen-env.sh`:12 · :140).
- Luna는 `OPENAI_API_KEY`가 비어 있지 않으면 켜집니다(realtime · core의 `LunaKeyPresentCondition`).
- 이름만 `OPENAI_API_KEY`로 바꾸면 CI 자리값 때문에 Luna 실제 어댑터가 켜져 실제 공급자를 부르려 하게 됩니다(R-F1 위반 위험).
- 또 RUNBOOK 단계 4의 R-F15 확인 기록은 Luna 어댑터만 대상이고, GPT-Live(아이 음성 중계)는 대상이 아닙니다(`infra/RUNBOOK.md`:238-258).

A. realtime 기동 점검에서 GPT-Live 키를 **demo 프로필에서만** 필수로 바꾸고, CI · 검증 · 로컬은 키 없이 가짜 공급자에 연결합니다(자리값을 없앰). 그러면 키가 없는 곳에서는 Luna도 꺼집니다. RUNBOOK 단계 4의 R-F15 확인 대상을 "Luna 판정 · GPT-Live 음성 중계"로 넓히고, 확인 기록 뒤에만 `/mujoong/demo/OPENAI_API_KEY`를 넣습니다. 옛 이름 `/mujoong/demo/PROVIDER_API_KEY`는 RUNBOOK 이름 바꾸기 절차와 편차 표에만 남기고(R-02 예외), 배포 전에 이름을 바꾸라는 순서를 적습니다. (추천)
B. A에 더해 Luna를 키만으로 켜지 않고 별도 스위치(예: `mujoong.luna.enabled`)로도 켜게 합니다. 키가 있어도 스위치가 꺼져 있으면 Luna는 보류입니다. 설정 키 · 계약 문서 · 시험이 늘어납니다.
C. 키 통일(요구사항 Q11 B)을 되돌려 두 이름을 그대로 둡니다. 요구사항 FR13을 편차로 접습니다.
X. Other (please specify)

[Answer]: B

## Q6-1. Luna 스위치는 어느 앱에, 기본값은 무엇으로 둘까요? (Q6 답 B의 후속)

Q6에서 Luna를 키만으로 켜지 않고 별도 스위치로도 켜게 하기로 했습니다(B). 코드를 보면 Luna는 두 앱에 있습니다.
- realtime: 판정 턴(J1 · J6), 키 조건 `LunaKeyPresentCondition`.
- core-api: 상황 생성 · 결과 문장(`LunaTextAiProvider` · `LunaResultTextProvider`), 같은 키 조건.
- 지금 CI · 검증의 env 파일은 앱마다 따로이고(`infra/ci/gen-env.sh`), core에는 `OPENAI_API_KEY`가 이미 빈 값으로 들어갑니다. 키를 합친 뒤 실제 키가 들어가는 곳은 demo뿐입니다.
- 요구사항 제약은 "core-api 동작은 건드리지 않는다"입니다.

A. realtime에만 `mujoong.luna.enabled`(기본 `false`)를 둡니다. realtime의 Luna 어댑터는 키가 있고 스위치가 `true`일 때만 켜지고, demo 프로필은 R-F15 확인 기록 뒤 사람이 `true`로 바꿉니다. core는 지금처럼 키만으로 켜집니다(동작 그대로). 기동 기록 `LUNA_STARTUP lunaState`에 `DISABLED_SWITCH_OFF`를 더하고 `contracts/ai-io.md` 10절을 함께 고칩니다. (추천)
B. 두 앱 모두에 같은 이름의 스위치를 둡니다(기본 `false`). core의 Luna도 스위치가 꺼져 있으면 지금의 대체 구현(키 없음과 같음)으로 돕니다. 요구사항 제약 "core 동작 그대로"와 달라 편차로 적고, core 시험 · 계약도 함께 고칩니다.
C. realtime에만 두되 기본값을 `true`로 둡니다. 키가 있으면 지금처럼 켜지고, 끄고 싶을 때만 `false`로 둡니다. CI 보호는 Q6 A(자리값 없앰)에 기댑니다.
X. Other (please specify)

[Answer]: A

## 범위 확장 가정 확인 (Assumption Confirmation)

코드 사실과 재승인된 요구사항에 바로 맞는다고 보고 정한 설계 가정입니다. 한 묶음으로 확인해 주세요.

1. **세션 시작 모양**: `ProviderRequestFactory`가 `session.start{session{model, instructions, audio{format{type: "audio/pcm", rate}}}}` 중첩 모양을 만듭니다. 키트가 보낸 `delegation{type: "client"}`는 넣지 않습니다(요구사항 Q8 A, `decision.*` 안 씀). 지금의 `childContext`는 공급자에 따로 필드가 없으므로, 필요한 값(이름 별칭 · 나이 · 관심사)은 서버 템플릿으로 `instructions` 문장 안에 넣습니다.
2. **해석기**: 출력 음성은 `delta`에서 읽고, 오류는 `error{error{type, code, param}}`를 `Error(code)`로, `session.usage.updated`는 알려진 사건으로 받아 사용량 초만 지표로 남깁니다. `session.output.done` · `session.error` · 응답 id 필드는 해석기와 내부 레코드에서 뺍니다. TTS 실호출에서 관측된 이름 · 모양만 더합니다(FR12.7).
3. **지시 주입**: 지금 `instructions.correct`를 쓰던 세 경로(게이트 교정 `correctAfterDiscard`, 연습 발화 `speak`/`practiceInstruction`, 판정 뒤 지시)를 모두 `session.instructions.append` → `response.create`로 옮깁니다. `append`가 세션 지시에 계속 쌓이는지는 TTS 실호출에서 확인하고, 쌓이면 지시 끝에 "이 지시는 다음 응답 한 번에만 적용"을 붙이는 템플릿으로 막습니다(그래도 안 되면 사람과 다시 정함).
4. **취소 대체**: `session.response.cancel` 송신을 모두 없애고, `cancelledResponses` · `correctedResponses` · `playbackResponses`의 응답 id 열쇠를 서버 세대 번호로 바꿉니다. 끼어들기 · 종료 의도 · 연습 폐기 · 판정 턴은 세대를 올리고, 기기에 이미 보낸 재생은 지금처럼 기기 쪽 정지 명령으로 멈춥니다.
5. **판정 턴 출력(설계 BR3.1 · U11-D2 개정)**: 첫 판 Q2 A("바로 취소")는 공급자 취소가 없어 "판정 턴 세대의 조각은 받되 음성은 버리고 전사만 모은다"로 바뀝니다. 그래서 버린 출력 전사가 예전보다 많이 남을 수 있습니다(2000자 한도는 그대로).
6. **말 끝 · 입력 전사**: `speech_stopped` · `input_transcript.done`이 TTS 실호출에서 오면 지금 흐름 그대로 씁니다. 오지 않으면 서버가 입력 전사 조각이 일정 시간 끊긴 것으로 확정합니다. 그때 필요한 새 시간 키의 이름 · 값은 실호출 관측 뒤 정하고, 세 곳(R-M5)을 같은 커밋에서 고칩니다. 지연 기록의 말 끝 출처는 지금 있는 `INPUT_TRANSCRIPT_FINAL` · `LAST_INPUT_FRAME`을 씁니다.
7. **표본율**: TTS 실호출에서 24kHz 수락을 먼저 봅니다. 안 되면 `ProviderClient` 송수신 바로 앞뒤 한 곳에서만 24k ↔ 16k 변환을 하고, 기기 쪽 프레임(960B) · 녹음 · `Sealed.totalMs` 계산은 그대로 둡니다. 이때 바뀌는 단언은 공급자 쪽 SHA 일치(`steps.mjs`:732-738 · :797, `ProviderRequestWhitelistIT`:121-122, `ProviderClientIT`:77)뿐이며, "변환 결과 길이 · 표본 수" 단언으로 바꿉니다(느슨하게 하지 않음).
8. **가짜 공급자**: `tools/fake-provider`(Node)와 `FakeProviderWsServer`(Java)는 새 형식을 내보냅니다. 응답 id · `output.done`을 빼고 음성을 `delta`에 담으며, 지원 목록 15종 밖 type에는 `error{code: invalid_value, param: type}`를 돌려줍니다. 인메모리 대역 `FakeProviderPort`는 내부 레코드 변경을 따라갑니다. 응답 경계는 조각 사이 무음(Q4 · Q5)으로 흉내 냅니다.
9. **시험 단언 갱신(FR12.9)**: 조사로 찾은 형식 단언 파일(U1 `OutputGateTest` · `SegmentBufferTest` · `ProviderClientIT` · `ProviderRequestWhitelistIT` · `ProviderResponseIdTest`, U3 `SessionVoiceU3Test` · `SessionExecutorReview*U3Test` · `ProviderClientU3IT` · `SessionVoiceU3IT`, U4b `SessionStopU4bTest` · `SessionJoinU4bTest` · `PracticeExtensionU4bTest` · `SessionLifecycleU4bIT`, tools u1 · u3 · u4b · `skeleton-e2e/steps.mjs`, `RealtimeArchTest` 등)은 같은 행동(순서 · 횟수 · 버림)을 세대 · 새 type으로 다시 단언합니다. 응답 id 자체를 시험하는 `ProviderResponseIdTest`는 세대 시험으로 바꿉니다. 바뀐 단언 목록(파일 · 전 · 후)을 코드 생성 요약에 남깁니다. 커버리지 하한 · `ContractFilesTest` · ArchUnit 규칙은 고치지 않습니다.
10. **계약 · 편차**: `contracts/ai-io.md` 2 · 3 · 8절을 새 형식으로 고치고, 편차 (z) · (aa) · (U4b-a)는 지우지 않고 "(U11-a)로 대체"로 표시합니다. 계약 편차는 `(U11-a)`부터 차례로, 설계 편차는 `U11-Dn`으로 따로 매깁니다(R-05). 24k ↔ 16k 변환을 하게 되면 다음 빈 `(U11-?)` 번호로 J131 편차를 적습니다.
11. **판정 시한 4초(R-04)**: 시한은 `live-budget` 4s이고, 시계가 정확히 4.000초에 만기되면 보류(TIMEOUT)입니다. 결과와 만기가 같은 틱에 오면 먼저 편지함에 들어온 쪽이 이깁니다. 10초 응답 준비는 아이 말 끝부터 재며 판정 4초를 포함합니다.
12. **PR 나누기 · 병합 순서(R-03)**: PR1(선 형식 · 키 통일 · 실호출 도구)은 TTS 실호출 결과(전사 · 말 끝 · 24kHz · append 누적 여부)가 단위 보고서에 남은 뒤에 `main`에 합칩니다. PR2(판정 턴)는 PR1 위에서 엽니다. 실호출 도구 `GptLiveProbe`의 app 형식은 새 운영 형식 그대로가 되고, kit 형식 비교 모드는 지웁니다.
13. **범위 파일**: 선 형식 정비로 고치는 운영 파일(`ProviderRequestFactory` · `ProviderClient` · `ProviderPort` · `SegmentBuffer` · `OutputGate` · 판정 · 세대 협력 객체)을 `backend/coverage-scope/u11.txt`에 넣습니다. `SessionExecutor`도 넣습니다(U1 · U3 · U4b 범위 파일이 이미 넣는 관례).

A. Accept assumptions
B. Convert to follow-up questions

[Answer]: A. Accept assumptions

## Consolidated Summary Confirmation

- 첫 판 결정(2026-10-16)은 그대로 둡니다: Q1 C(휴식은 `D_REST_ACK` 대신 일반 지시) · Q1-1 A · Q2 A · Q3 A(10초 시계는 판정 턴만) · 첫 판 가정 13개. 다만 범위 확장 가정 5에 따라 Q2 A의 "바로 취소"는 "판정 턴 세대의 조각은 음성을 버리고 전사만 모음"으로 바뀌고, 지시 메시지는 `session.instructions.append` → `response.create`로 바뀝니다.
- Q4 A: 출력 구간은 새 세대의 첫 조각부터 받아, 조각이 `segment-quiet`(800ms) 동안 끊기면 '완전'으로 봉인해 게이트에 넣습니다. 세대가 바뀌어 끊긴 구간은 버립니다. 새 시간 키는 없고, TTS 실호출에서 응답 끝 사건이 보이면 그 사건을 우선 씁니다. 편차 (z)를 대체하는 계약 편차로 적습니다.
- Q5 A: 세대를 올린 뒤(판정 턴 열기 · 지시 송신 · 끼어들기)에는 조각을 버리다가, `segment-quiet` 동안 끊긴 뒤 처음 오는 조각부터 새 응답으로 받습니다. 지시는 그 끊김을 확인한 뒤에 보냅니다. 지시 응답 시한(BR4.5)은 그대로이고, 실호출에서 응답 경계 사건이 보이면 그 사건으로 바꿉니다.
- Q6 B: realtime 기동 점검의 GPT-Live 키 필수는 demo 프로필에만 두고, CI · 검증 · 로컬은 키 없이 가짜 공급자에 연결합니다(자리값 없앰). R-F15 확인 대상을 GPT-Live 음성 중계까지 넓히고, 옛 이름 `PROVIDER_API_KEY`는 RUNBOOK 이름 바꾸기 절차 · 편차 표에만 남기며 배포 전에 Parameter Store 이름을 바꾸라는 순서를 적습니다. 여기에 더해 Luna를 키만으로 켜지 않고 스위치로도 켜게 합니다.
- Q6-1 A: 스위치 `mujoong.luna.enabled`(기본 `false`)는 realtime에만 둡니다. realtime Luna는 키가 있고 스위치가 `true`일 때만 켜지며, demo는 R-F15 확인 기록 뒤 사람이 `true`로 바꿉니다. core는 지금처럼 키만으로 켜집니다. 기동 기록 `LUNA_STARTUP lunaState`에 `DISABLED_SWITCH_OFF`를 더하고 `contracts/ai-io.md` 10절을 같은 작업에서 고칩니다.
- 범위 확장 가정 13개 수락: 중첩 세션 시작(`delegation` 없음, 아이 정보는 instructions 문장으로) / 해석기는 `delta` · `error` · `usage.updated`, `output.done` · 응답 id 제거 / 지시 세 경로를 append → create로, append 누적은 실호출로 확인 / 취소는 세대로 대체, `response.cancel` 송신 없음 / 판정 턴 출력은 전사만 모음 / 말 끝 · 입력 전사가 없으면 서버 확정(새 시간 키는 관측 뒤) / 표본율 변환은 `ProviderClient` 한 곳, SHA 단언만 길이 · 표본 수 단언으로 / 가짜 공급자 두 곳 새 형식 · 목록 밖 type은 오류 / 형식 단언 파일 갱신 목록을 코드 생성 요약에 / 편차 (z) · (aa) · (U4b-a)는 `(U11-a)`부터로 대체 표시, 설계 편차는 `U11-Dn` / 4.000초 만기는 보류, 10초는 말 끝부터(판정 포함) / PR1은 TTS 실호출 결과 뒤 병합, `GptLiveProbe` kit 모드 삭제 / 범위 파일 `u11.txt`에 형식 · 세대 · 판정 파일과 `SessionExecutor`.

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
