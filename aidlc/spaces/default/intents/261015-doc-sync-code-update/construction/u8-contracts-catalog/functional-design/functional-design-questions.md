# U8 계약 · 버전 리소스 — Functional Design 질문지

> 근거: `../../../inception/units-generation/unit-of-work.md`(U8), `../../../inception/contract-design/contract-summary.md`(C-DS2 · C-DS9 · C-DS13 · C-DS14, 검토 지적 R-02 · R-03 · R-07), `../../../inception/requirements-analysis/requirements.md`(FR6.10 · FR10 · FR11), `../../../inception/domain-design/components.md`.
> 코드 · 계약 사실(이번 단계에서 직접 확인함)
> - 승인 문구의 정본은 core DB(`core_approved_content` · `core_approved_content_version`, 시드 SQL로만 넣음)다. core에는 이미 같은 이름의 `ApprovedContentCatalog`(core.activity)가 있다. 세션 시작 패키지 `approvedContent`가 `contentCode → {contentVersionId, text, audioUrl}`을 고정해 realtime에 넘긴다.
> - 연습 발화는 WAV가 아니라 GPT-Live 지시 + 공백 제거 완전 일치(`PracticeSpeech.matches`)로 낸다. '도움을 받아 볼까?'는 코드에 박혀 있다.
> - 시간 키는 timing.md의 `키 | 기본값 | 소유 앱` 표 + 앱 `application.yml` + 설정 레코드가 함께 맞아야 `ConfigKeyContractTest`가 통과한다. 연습 키(12절)는 `practice/u5b-runtime.yml`로 이 대조를 우회하고 있다.
> - Neo4j 비밀은 이미 `NEO4J_AUTH` 하나로 비밀 이름 목록에 있다. core는 `MUJOONG_TEXT_AI_API_KEY`라는 예약 이름을 주석에만 두었다.
> - `snapshotVersion`은 '세션당 한 판'(상수 1, DB 유일 제약)이다. `record.practice`는 스키마가 `object | null`뿐이고 U5b 모양은 문서(편차 U4b-c)로만 정해져 있다.
> 사람이 골라야 하는 것만 개별 질문으로 묻고, 코드 사실로 정해지는 것은 맨 아래 가정 묶음으로 확인받는다.

## Q1. 새 승인 문구(휴식 받아 주기 · 다시 묻기 · 코치 전환 · 친구 복귀 알림 등)는 어디에 두고 어떻게 재생할까

새 흐름에는 B 문서의 `D_REST_ACK_START` · `D_REST_ACK` · `D_REPROMPT`, 코치 전환 "잠깐, 무중이가 도와줄게", 복귀 "다시 친구 역할로 돌아갈게" 같은 고정 문구가 늘어난다. 지금 승인 문구는 두 갈래다: (가) core DB 시드 + WAV(종료 · 대기 문구, 기기가 직접 재생) (나) 코드에 박힌 문장 + GPT-Live 지시 + 완전 일치(연습 '도움을 받아 볼까?').

A. 모두 core DB 승인 문구로 시드하고(`content_code` · 버전 · 텍스트), 세션 시작 패키지로 고정해 realtime에 넘긴다. 재생은 지금 연습 방식(GPT-Live 지시 + 완전 일치, 음성 파일 없이)으로 하고, 완전 일치면 제브를 생략한다(FR6.6). 코드에 박힌 '도움을 받아 볼까?'도 같은 곳으로 옮긴다 — 장점: 승인 문구 정본이 한 곳(core DB), 세션 고정 · 버전 기록이 지금 구조 그대로 / 단점: 시드 SQL과 시작 패키지 조립을 함께 고쳐야 하고, 음성 파일 없는 승인 문구라는 새 종류(`audio_path` 없음)를 시드 규칙에 더해야 한다 (추천)
B. realtime 버전 리소스 파일(`seed-dictionary.json`처럼 기동 때 읽는 파일)에 둔다 — 장점: core를 고치지 않는다 / 단점: 승인 문구 정본이 core DB와 realtime 파일 두 곳으로 갈라진다
C. 고정 문구 대신 GPT-Live 지시로 매번 만들게 하고 제브 검사를 거친다 — 장점: 문구 관리가 필요 없다 / 단점: FR6.6 '원문 일치면 제브 생략'을 못 쓰고, 정본(B §8.5)의 정해진 알림 문구를 지키지 못할 수 있다
X. Other (please specify)

[Answer]: A

## Q2. Luna 호출의 재시도 규칙을 무엇으로 통일할까 (Contract Design 검토 R-07)

지금 규칙과 자료가 셋으로 갈린다: ① `ai-io.md` 7절(U5a 상황 생성): 일시 실패에 한한 기술 재시도 1회(호출당 3초 · 간격 250ms) + 내용 재생성 1회를 전체 15초 안에서. ② 요구사항 FR1.2: 스키마가 맞지 않으면 1회 다시 요청. ③ 조원 하네스 Luna 분석 설정: 자동 재시도 없음, 길이 초과 · 빈 응답은 실패로 저장. U8은 이 규칙을 `ai-io.md`에 적고, U9가 구현한다.

A. 목적별로 나눈다 — (a) 진행 중 판정: 재시도 없음(2초, 실패는 보류). (b) 상황 생성 · 자체 검토 · 새 씨앗(요청 안에서 끝나는 일): ①을 그대로 쓰고, 스키마 불일치는 '내용 재생성 1회'로 센다(②를 ① 안에 넣음). (c) 결과 · 목표 후보 · 재판정(결과 작업 안의 일): 호출 안에서는 재시도하지 않고, 결과 작업의 시도 횟수(작업 재시도)로만 다시 한다(③과 같은 방향). 길이 초과 · 빈 응답은 모든 목적에서 성공으로 저장하지 않는다 — 장점: 기존 규칙 ①을 깨지 않고 ② · ③을 모두 담는다 / 단점: 목적별 규칙이라 표가 필요하다 (추천)
B. 모든 목적에 ②만 적용(스키마 불일치 1회 재요청, 그 밖의 재시도 없음) — 장점: 단순하다 / 단점: ①(기술 재시도)을 깨는 변경이라 U5a 시험 · 계약을 고쳐야 한다
C. 모든 목적에 ③(자동 재시도 없음) — 장점: 비용 상한이 명확하다 / 단점: FR1.2와 ①을 모두 깨고, 상황 생성 실패율이 오른다
X. Other (please specify)

[Answer]: A

## Assumption Confirmation

아래는 코드 · 계약 사실로 정한 가정이다. 하나라도 다르면 B를 골라 주면 개별 질문으로 바꾼다.

1. **realtime 컴포넌트 이름:** core에 이미 `ApprovedContentCatalog`가 있으므로, 설계의 realtime 쪽 컴포넌트는 `LiveContentRegistry`(판정 선택지 · 가드레일 프롬프트 · 분류 판 리소스, 세션 대본 등록, 원문 일치 확인)로 이름을 바꾼다. 승인 문구 자체는 core `ApprovedContentCatalog`가 계속 정본이다(Q1-A일 때).
2. **판정 · 가드레일 버전 리소스:** J1 ~ J6 질문 · 선택지와 제브 시스템 프롬프트 · 분류는 realtime 자원 파일(`judgment/option-sets-v1.json` · `guardrail/policy-v1.json` 등, `seed-dictionary.json`처럼 기동 때 읽고 실패하면 기동 거부)로 둔다. 세션 시작 때 판 번호를 실행 계획(메모리)에 고정하고, 판정 · 가드레일 기록 행마다 판 번호를 남겨 세션 고정을 증명한다. 새 DB 표는 만들지 않는다.
3. **시간 키 위치:** 새 시간 키는 timing.md에 새 절을 만들어 `키 | 기본값 | 소유 앱` 표로 두고, 앱 `application.yml` · 설정 레코드에 함께 넣는다(R-M5, `ConfigKeyContractTest`가 대조). 연습 키도 u5b처럼 별도 yml로 우회하지 않는다. 화면이 읽지 않으므로 `timing-defaults.json`에는 넣지 않는다(프런트 시험은 화면 키만 본다).
4. **기존 키 값 변경은 쓰는 단위가:** 결과 재시도(`result.job-attempts` 3 → 4, `job-retry-backoff` `[5s, 15s]` → `[5s, 15s, 30s]`)처럼 기존 키의 값을 바꾸는 일은 그 값을 쓰는 단위(U14)가 코드와 같은 PR에서 한다. U8은 새 키만 더한다(Contract Design 검토 R-02 해소 방향).
5. **비밀 이름:** 새 비밀 이름은 `OPENAI_API_KEY`(Luna, FR1.1) · `JEV_API_KEY`(FR6.2) 둘이다. Neo4j는 기존 `NEO4J_AUTH`를 그대로 쓴다(계약 요약 C-DS14의 `NEO4J_URI` · `USERNAME` · `PASSWORD`를 바로잡음). 두 이름을 R-M9 목록 전부(ci.yml 번들 검사 · image-checks.sh · infra-validate 두 곳 · render-env 세 곳 · log-hygiene-check · scripts/verify 두 곳 · gen-env.sh · README · RUNBOOK 단계 4와 회전 표)에 같은 커밋으로 넣는다. GPT-Live의 `PROVIDER_API_KEY`는 FR12.2 전까지 그대로 두고, core 예약 이름 `MUJOONG_TEXT_AI_API_KEY`는 쓰지 않는다고 ai-io.md에 적는다.
6. **기록 이벤트 형식:** `snapshotVersion`은 기존 뜻(세션당 한 판)을 지킨다. 새 연습 기록은 `record.practice.flowVersion: "practice-conversation-v1"`로 구분하고, 없으면 U5b 형식이다(Contract Design 검토 R-03 해소). 새 모양은 `contracts/schemas/practice-record-v2.schema.json`으로 두고 편차 행을 단다.
7. **편차 표기:** 최근 유닛과 같이 유닛 접두 기호 `U8-a` …와 YAML 키 `x-u8-deviation`, Markdown `[U8 편차 (U8-a)]`를 쓴다. 소문자 기호 `(ad)`는 쓰지 않는다(`ContractFilesTest` 목록을 건드리지 않음).
8. **AI 스키마 파일:** Contract Design Q2-A대로 `contracts/schemas/ai-*.schema.json` · `guardrail-*.schema.json`을 두고 `$id` 규약을 지킨다. 스펙 YAML이 참조하지 않으므로 예시 검증 대상은 아니며, U8 단위 시험이 스키마별 정상 · 거절 예시를 검증한다.
9. **새 오류 코드 없음:** HTTP 오류 코드는 더하지 않는다. 판정 · 제브 실패는 내부 사유(보류 · 막힘)로, 상황 생성 실패는 기존 `SCENARIO_GEN_FAILED`로 처리한다.

A. Accept assumptions
B. Convert to follow-up questions
X. Other (please specify)

[Answer]: A. Accept assumptions

## Q3. (검토 뒤) 설계 검토의 Major 지적 2건을 어떻게 처리할까

아키텍처 검토는 '진행 가능'이었지만 Major 2건이 남았다. R-01: 승인 발화 시드 · 조립의 세부(기존 `HELP_OFFER` DRAFT 행을 v2로 올릴지 새 코드로 둘지, 활동 유형별로 실을 code 목록, 음성 없는 문구의 `content_type` 값, dev 시드(`DevSeedRunner`)에도 넣을지). R-02: U8이 더할 새 시간 키 목록(계약 요약 8개 중 `result.retry-delays`는 기존 키와 중복), `timing-defaults.json` 제외의 R-M5 예외 근거, `practice.*` 새 키와 기존 `u5b-runtime.yml` 바인딩의 관계. Minor 4건(R-03 ~ R-06)도 같은 묶음이다.

A. 설계 보강 후 진행 — 지금 이 문서들(entities · rules · functional-spec)에 위 세부를 정해 넣고, 다시 검토를 받은 뒤 다음 단계로 간다 (추천)
B. 코드 생성 계획에서 정하기 — 설계는 그대로 두고, Code Generation 계획(사람이 승인함)에 이 세부를 정해 넣는다
X. Other (please specify)

[Answer]: A
